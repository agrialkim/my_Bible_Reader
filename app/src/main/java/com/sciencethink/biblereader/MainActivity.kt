package com.sciencethink.biblereader

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity(), TextToSpeech.OnInitListener {
    private lateinit var textToSpeech: TextToSpeech
    private lateinit var selectedFileLabel: TextView
    private lateinit var statusLabel: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var selectFileButton: Button
    private lateinit var generateButton: Button
    private lateinit var playButton: Button
    private lateinit var pauseButton: Button
    private lateinit var stopButton: Button
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val audioUris = mutableListOf<Uri>()
    private var chapters: List<BibleChapter> = emptyList()
    private var isTtsReady = false
    @Volatile
    private var isGenerating = false
    @Volatile
    private var currentUtteranceId: String? = null
    private var currentChapterIndex = 0
    private var generationToken = 0
    private var runFolder = ""
    private var player: MediaPlayer? = null
    private var playbackIndex = 0
    private var pausedForPlayback = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        selectedFileLabel = findViewById(R.id.selectedFile)
        statusLabel = findViewById(R.id.status)
        progressBar = findViewById(R.id.progress)
        generateButton = findViewById(R.id.generateButton)
        playButton = findViewById(R.id.playButton)
        pauseButton = findViewById(R.id.pauseButton)
        stopButton = findViewById(R.id.stopButton)

        selectFileButton = findViewById(R.id.selectFileButton)
        selectFileButton.setOnClickListener { selectTextFile() }
        generateButton.setOnClickListener { startGeneration() }
        playButton.setOnClickListener { playOrResume() }
        pauseButton.setOnClickListener { pausePlayback() }
        stopButton.setOnClickListener { stopAll() }

        textToSpeech = TextToSpeech(this, this)
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            statusLabel.text = "휴대폰의 음성 엔진을 시작하지 못했습니다."
            return
        }

        val languageStatus = textToSpeech.setLanguage(Locale.KOREAN)
        if (languageStatus == TextToSpeech.LANG_MISSING_DATA ||
            languageStatus == TextToSpeech.LANG_NOT_SUPPORTED
        ) {
            statusLabel.text = "한국어 음성 데이터가 없습니다. Android 설정에서 한국어 TTS 음성을 설치해 주세요."
            return
        }

        isTtsReady = true
        statusLabel.text = "한국어 음성 준비됨"
        updateButtons()
        textToSpeech.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                runOnUiThread {
                    val chapter = chapters.getOrNull(currentChapterIndex)
                    if (isGenerating && utteranceId == currentUtteranceId && chapter != null) {
                        statusLabel.text =
                            "음성 생성 중 ${currentChapterIndex + 1}/${chapters.size}: ${chapter.book} ${chapter.number}장"
                    }
                }
            }

            override fun onDone(utteranceId: String?) {
                if (!isGenerating || utteranceId != currentUtteranceId) return
                val completedIndex = currentChapterIndex
                val completedRunFolder = runFolder
                val file = tempAudioFile(completedIndex)
                val fileName = audioFileName(completedIndex)
                ioExecutor.execute {
                    try {
                        val uri = publishAudio(file, fileName, completedRunFolder)
                        runOnUiThread {
                            if (!isGenerating || utteranceId != currentUtteranceId) {
                                contentResolver.delete(uri, null, null)
                                file.delete()
                                return@runOnUiThread
                            }
                            audioUris.add(uri)
                            file.delete()
                            currentChapterIndex++
                            if (isGenerating && currentChapterIndex < chapters.size) {
                                synthesizeCurrentChapter()
                            } else if (isGenerating) {
                                isGenerating = false
                                currentUtteranceId = null
                                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                                progressBar.progress = 100
                                progressBar.visibility = View.GONE
                                statusLabel.text =
                                    "${audioUris.size}개 장별 WAV 파일을 음악/BibleReader/$runFolder 에 저장했습니다."
                                updateButtons()
                            }
                        }
                    } catch (error: Exception) {
                        file.delete()
                        runOnUiThread {
                            if (isGenerating) failGeneration("파일 저장 실패: ${error.message}")
                        }
                    }
                }
            }

            override fun onError(utteranceId: String?) {
                if (isGenerating && utteranceId == currentUtteranceId) {
                    runOnUiThread {
                        if (isGenerating && utteranceId == currentUtteranceId) {
                            failGeneration("한국어 음성을 생성하지 못했습니다.")
                        }
                    }
                }
            }
        })
    }

    private fun selectTextFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/plain"
        }
        startActivityForResult(intent, REQUEST_OPEN_TEXT)
    }

    @Deprecated("Uses the system document picker for broad Android compatibility.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_OPEN_TEXT || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: error("선택한 파일을 읽을 수 없습니다.")
            val text = decodeText(bytes)
            chapters = BibleTextParser.parse(text)
            audioUris.clear()
            selectedFileLabel.text = "${uri.lastPathSegment ?: "선택한 파일"} · ${chapters.size}개 장"
            statusLabel.text =
                "파일을 읽었습니다. 설치된 한국어 TTS 음성에 따라 오프라인 생성이 가능합니다."
            updateButtons()
        } catch (error: Exception) {
            chapters = emptyList()
            selectedFileLabel.text = "선택된 파일 없음"
            statusLabel.text = error.message ?: "텍스트 파일을 읽지 못했습니다."
            updateButtons()
        }
    }

    private fun decodeText(bytes: ByteArray): String {
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        ) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: CharacterCodingException) {
            Charset.forName("MS949").newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        }
    }

    private fun startGeneration() {
        if (!isTtsReady || chapters.isEmpty() || isGenerating) return
        stopPlayer()
        audioUris.clear()
        currentChapterIndex = 0
        generationToken++
        runFolder = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
            .plus("_$generationToken")
        isGenerating = true
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        progressBar.progress = 0
        progressBar.visibility = View.VISIBLE
        statusLabel.text = "음성 생성 준비 중..."
        updateButtons()
        synthesizeCurrentChapter()
    }

    private fun synthesizeCurrentChapter() {
        if (!isGenerating || currentChapterIndex >= chapters.size) return
        val chapter = chapters[currentChapterIndex]
        val file = tempAudioFile(currentChapterIndex)
        val utteranceId = "$runFolder-chapter-$currentChapterIndex"
        currentUtteranceId = utteranceId
        file.parentFile?.mkdirs()
        val result = textToSpeech.synthesizeToFile(
            chapter.text,
            Bundle(),
            file,
            utteranceId
        )
        if (result == TextToSpeech.ERROR) {
            failGeneration("음성 합성을 시작하지 못했습니다.")
        } else {
            progressBar.progress = (currentChapterIndex * 100) / chapters.size
        }
    }

    private fun tempAudioFile(index: Int): File =
        File(cacheDir, "bible-reader-$runFolder/${audioFileName(index)}")

    private fun audioFileName(index: Int): String {
        val chapter = chapters[index]
        return "%04d_%s_%03d.wav".format(Locale.ROOT, index + 1, chapter.book, chapter.number)
    }

    private fun publishAudio(source: File, fileName: String, folder: String): Uri {
        check(source.isFile && source.length() > 0) { "음성 데이터가 생성되지 않았습니다." }
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
            put(
                MediaStore.Audio.Media.RELATIVE_PATH,
                "${Environment.DIRECTORY_MUSIC}/BibleReader/$folder"
            )
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("음성 파일 저장 위치를 만들지 못했습니다.")
        try {
            contentResolver.openOutputStream(uri, "w")?.use { output ->
                source.inputStream().use { input -> input.copyTo(output) }
            } ?: error("음성 파일을 쓸 수 없습니다.")
            contentResolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) },
                null,
                null
            )
            return uri
        } catch (error: Exception) {
            contentResolver.delete(uri, null, null)
            throw error
        }
    }

    private fun failGeneration(message: String) {
        isGenerating = false
        currentUtteranceId = null
        textToSpeech.stop()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        File(cacheDir, "bible-reader-$runFolder").deleteRecursively()
        progressBar.visibility = View.GONE
        statusLabel.text = message
        updateButtons()
    }

    private fun playOrResume() {
        if (pausedForPlayback) {
            player?.start()
            pausedForPlayback = false
            statusLabel.text = "재생 중"
            updateButtons()
            return
        }
        if (audioUris.isEmpty()) return
        if (playbackIndex >= audioUris.size) playbackIndex = 0
        playAt(playbackIndex)
    }

    private fun playAt(index: Int) {
        if (index >= audioUris.size) {
            statusLabel.text = "재생이 끝났습니다."
            playbackIndex = 0
            updateButtons()
            return
        }
        stopPlayer()
        playbackIndex = index
        val nextPlayer = MediaPlayer()
        player = nextPlayer
        try {
            nextPlayer.setDataSource(this, audioUris[index])
            nextPlayer.setOnPreparedListener {
                it.start()
                statusLabel.text = "재생 중 ${index + 1}/${audioUris.size}"
                updateButtons()
            }
            nextPlayer.setOnCompletionListener {
                it.release()
                player = null
                playbackIndex++
                playAt(playbackIndex)
            }
            nextPlayer.setOnErrorListener { failedPlayer, _, _ ->
                failedPlayer.release()
                player = null
                statusLabel.text = "음성 파일을 재생하지 못했습니다."
                updateButtons()
                true
            }
            nextPlayer.prepareAsync()
            statusLabel.text = "재생 준비 중 ${index + 1}/${audioUris.size}"
            updateButtons()
        } catch (error: Exception) {
            stopPlayer()
            statusLabel.text = "재생 오류: ${error.message}"
            updateButtons()
        }
    }

    private fun pausePlayback() {
        val activePlayer = player ?: return
        if (activePlayer.isPlaying) {
            activePlayer.pause()
            pausedForPlayback = true
            statusLabel.text = "일시정지"
            updateButtons()
        }
    }

    private fun stopAll() {
        if (isGenerating) {
            isGenerating = false
            currentUtteranceId = null
            generationToken++
            textToSpeech.stop()
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            File(cacheDir, "bible-reader-$runFolder").deleteRecursively()
            progressBar.visibility = View.GONE
            statusLabel.text = "음성 생성이 중지되었습니다."
        }
        stopPlayer()
        playbackIndex = 0
        updateButtons()
    }

    private fun stopPlayer() {
        player?.let {
            try {
                it.stop()
            } catch (_: IllegalStateException) {
            }
            it.release()
        }
        player = null
        pausedForPlayback = false
    }

    private fun updateButtons() {
        selectFileButton.isEnabled = !isGenerating
        generateButton.isEnabled = isTtsReady && chapters.isNotEmpty() && !isGenerating
        playButton.isEnabled = audioUris.isNotEmpty() && !isGenerating
        pauseButton.isEnabled = player?.isPlaying == true
        stopButton.isEnabled = isGenerating || player != null
    }

    override fun onDestroy() {
        isGenerating = false
        currentUtteranceId = null
        if (::textToSpeech.isInitialized) textToSpeech.stop()
        stopPlayer()
        if (::textToSpeech.isInitialized) {
            textToSpeech.shutdown()
        }
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        ioExecutor.shutdownNow()
        super.onDestroy()
    }

    companion object {
        private const val REQUEST_OPEN_TEXT = 1001
    }
}
