package com.sciencethink.biblereader

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Locale

class MainActivity : Activity(), TextToSpeech.OnInitListener {
    private lateinit var textToSpeech: TextToSpeech
    private lateinit var selectedFileLabel: TextView
    private lateinit var statusLabel: TextView
    private lateinit var assetSpinner: Spinner
    private lateinit var voiceSpinner: Spinner
    private lateinit var loadAssetButton: Button
    private lateinit var selectFileButton: Button
    private lateinit var playButton: Button
    private lateinit var pauseButton: Button
    private lateinit var stopButton: Button

    private var chapters: List<BibleChapter> = emptyList()
    private var assetFiles: List<String> = emptyList()
    private var koreanVoices: List<android.speech.tts.Voice> = emptyList()
    private var isTtsReady = false
    private var isPlaying = false
    private var isPaused = false
    private var currentChapterIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        selectedFileLabel = findViewById(R.id.selectedFile)
        statusLabel = findViewById(R.id.status)
        assetSpinner = findViewById(R.id.assetSpinner)
        voiceSpinner = findViewById(R.id.voiceSpinner)
        loadAssetButton = findViewById(R.id.loadAssetButton)
        selectFileButton = findViewById(R.id.selectFileButton)
        playButton = findViewById(R.id.playButton)
        pauseButton = findViewById(R.id.pauseButton)
        stopButton = findViewById(R.id.stopButton)

        setupAssetSpinner()

        loadAssetButton.setOnClickListener { loadSelectedAssetBible() }
        selectFileButton.setOnClickListener { selectTextFile() }
        playButton.setOnClickListener { playOrResume() }
        pauseButton.setOnClickListener { pausePlayback() }
        stopButton.setOnClickListener { stopPlayback() }

        textToSpeech = TextToSpeech(this, this)
    }

    private fun setupAssetSpinner() {
        try {
            val rawFiles = (assets.list("") ?: emptyArray()).filter { it.endsWith(".txt") }
            val preferredOrder = listOf("개역개정.txt")
            assetFiles = rawFiles.sortedWith(Comparator { a, b ->
                val indexA = preferredOrder.indexOf(a)
                val indexB = preferredOrder.indexOf(b)
                if (indexA != -1 && indexB != -1) indexA.compareTo(indexB)
                else if (indexA != -1) -1
                else if (indexB != -1) 1
                else a.compareTo(b)
            })

            if (assetFiles.isNotEmpty()) {
                val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, assetFiles).apply {
                    setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                }
                assetSpinner.adapter = adapter
                val defaultIndex = assetFiles.indexOf("개역개정.txt").takeIf { it >= 0 } ?: 0
                assetSpinner.setSelection(defaultIndex)
                loadAssetFile(assetFiles[defaultIndex])

                assetSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(
                        parent: android.widget.AdapterView<*>?,
                        view: View?,
                        position: Int,
                        id: Long
                    ) {
                        val fileName = assetFiles.getOrNull(position) ?: return
                        if (chapters.isEmpty() || !selectedFileLabel.text.contains(fileName.removeSuffix(".txt"))) {
                            stopPlayback()
                            loadAssetFile(fileName)
                        }
                    }

                    override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
                }
            } else {
                selectedFileLabel.text = "기본 제공 성경 파일 없음"
            }
        } catch (_: Exception) {
            assetFiles = emptyList()
        }
    }

    private fun setupVoiceSpinner() {
        try {
            koreanVoices = textToSpeech.voices?.filter { it.locale.language == "ko" } ?: emptyList()
            if (koreanVoices.isNotEmpty()) {
                var femaleCount = 1
                var maleCount = 1
                var generalCount = 1

                val voiceNames = koreanVoices.map { voice ->
                    val lowerName = voice.name.lowercase(Locale.ROOT)
                    when {
                        lowerName.contains("female") || lowerName.contains("sunhi") ||
                        lowerName.contains("heami") || lowerName.contains("zira") ||
                        lowerName.contains("jimin") || lowerName.contains("seohyeon") ||
                        lowerName.contains("yuna") || lowerName.contains("sora") -> {
                            "여성 음성 ${femaleCount++}"
                        }
                        lowerName.contains("male") || lowerName.contains("injoon") ||
                        lowerName.contains("hyunsu") || lowerName.contains("bongjin") ||
                        lowerName.contains("david") || lowerName.contains("minsu") -> {
                            "남성 음성 ${maleCount++}"
                        }
                        else -> {
                            "음성 ${generalCount++}"
                        }
                    }
                }

                val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, voiceNames).apply {
                    setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                }
                voiceSpinner.adapter = adapter

                // Keep ko-kr-x-kob-local as default if present, or index 0
                val defaultIndex = koreanVoices.indexOfFirst {
                    it.name.contains("ko-kr-x-kob-local", ignoreCase = true)
                }.takeIf { it >= 0 } ?: 0

                voiceSpinner.setSelection(defaultIndex)
                koreanVoices.getOrNull(defaultIndex)?.let { defaultVoice ->
                    textToSpeech.voice = defaultVoice
                }

                voiceSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                        koreanVoices.getOrNull(position)?.let { selectedVoice ->
                            textToSpeech.voice = selectedVoice
                        }
                    }
                    override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun loadSelectedAssetBible() {
        val selectedIndex = assetSpinner.selectedItemPosition
        val fileName = assetFiles.getOrNull(selectedIndex) ?: return
        stopPlayback()
        loadAssetFile(fileName)
    }

    private fun loadAssetFile(fileName: String) {
        try {
            val bytes = assets.open(fileName).use { it.readBytes() }
            val text = decodeText(bytes)
            chapters = BibleTextParser.parse(text)
            currentChapterIndex = 0
            val displayName = fileName.removeSuffix(".txt")
            selectedFileLabel.text = displayName
            statusLabel.text = "$displayName 파일을 불러왔습니다. 재생 버튼을 누르세요."
            updateButtons()
        } catch (error: Exception) {
            chapters = emptyList()
            currentChapterIndex = 0
            val displayName = fileName.removeSuffix(".txt")
            selectedFileLabel.text = "파일 읽기 실패: $displayName"
            statusLabel.text = error.message ?: "$displayName 파일을 읽지 못했습니다."
            updateButtons()
        }
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
        setupVoiceSpinner()
        statusLabel.text = "한국어 음성 준비됨. 목소리를 선택하고 재생하세요."
        updateButtons()

        textToSpeech.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                if (!isPlaying) return
                val parts = utteranceId?.split("-") ?: return
                val chapIdx = when {
                    utteranceId.startsWith("chapter-done-") -> utteranceId.substringAfter("chapter-done-").toIntOrNull()
                    utteranceId.startsWith("chapter-line-") -> parts.getOrNull(2)?.toIntOrNull()
                    else -> null
                }
                if (chapIdx != null && chapIdx != currentChapterIndex) {
                    currentChapterIndex = chapIdx
                }
                runOnUiThread {
                    val chapter = chapters.getOrNull(currentChapterIndex)
                    if (isPlaying && chapter != null) {
                        statusLabel.text =
                            "재생 중 ${currentChapterIndex + 1}/${chapters.size}: ${chapter.book} ${chapter.number}장"
                    }
                }
            }

            override fun onDone(utteranceId: String?) {
                if (!isPlaying) return
                if (utteranceId?.startsWith("chapter-done-") == true) {
                    val finishedChapIdx = utteranceId.substringAfter("chapter-done-").toIntOrNull()
                    if (finishedChapIdx == currentChapterIndex) {
                        runOnUiThread {
                            if (!isPlaying) return@runOnUiThread
                            currentChapterIndex++
                            if (currentChapterIndex < chapters.size) {
                                playCurrentChapter()
                            } else {
                                isPlaying = false
                                isPaused = false
                                currentChapterIndex = 0
                                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                                statusLabel.text = "성경 읽기가 완료되었습니다."
                                updateButtons()
                            }
                        }
                    }
                }
            }

            @Suppress("DEPRECATION")
            override fun onError(utteranceId: String?) {
                if (isPlaying) {
                    runOnUiThread {
                        if (isPlaying) {
                            isPlaying = false
                            isPaused = false
                            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                            textToSpeech.stop()
                            statusLabel.text = "한국어 음성을 재생하지 못했습니다."
                            updateButtons()
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
            stopPlayback()
            chapters = BibleTextParser.parse(text)
            currentChapterIndex = 0
            val rawName = uri.lastPathSegment ?: "선택한 파일"
            selectedFileLabel.text = rawName.removeSuffix(".txt")
            statusLabel.text = "파일을 읽었습니다. 재생 버튼을 누르세요."
            updateButtons()
        } catch (error: Exception) {
            chapters = emptyList()
            currentChapterIndex = 0
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

    private fun playOrResume() {
        if (!isTtsReady || chapters.isEmpty()) return
        if (isPaused) {
            isPaused = false
            isPlaying = true
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            playCurrentChapter()
            statusLabel.text = "재생 중"
            updateButtons()
            return
        }
        if (isPlaying) return

        isPlaying = true
        isPaused = false
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (currentChapterIndex >= chapters.size) currentChapterIndex = 0
        playCurrentChapter()
        updateButtons()
    }

    private fun playCurrentChapter() {
        if (!isPlaying || currentChapterIndex >= chapters.size) return
        val chapter = chapters[currentChapterIndex]
        val lines = chapter.text.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) {
            currentChapterIndex++
            playCurrentChapter()
            return
        }

        for ((lineIndex, line) in lines.withIndex()) {
            val queueMode = if (lineIndex == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            val isLastLine = (lineIndex == lines.size - 1)
            val utteranceId = if (isLastLine) {
                "chapter-done-$currentChapterIndex"
            } else {
                "chapter-line-$currentChapterIndex-$lineIndex"
            }

            textToSpeech.speak(
                line,
                queueMode,
                null,
                utteranceId
            )
        }
    }

    private fun pausePlayback() {
        if (!isPlaying) return
        textToSpeech.stop()
        isPlaying = false
        isPaused = true
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val chapter = chapters.getOrNull(currentChapterIndex)
        val chapterDesc = if (chapter != null) "${chapter.book} ${chapter.number}장" else "${currentChapterIndex + 1}번째"
        statusLabel.text = "일시정지 ($chapterDesc 위치)"
        updateButtons()
    }

    private fun stopPlayback() {
        isPlaying = false
        isPaused = false
        currentChapterIndex = 0
        textToSpeech.stop()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        statusLabel.text = "정지됨 (처음으로 초기화됨)"
        updateButtons()
    }

    private fun updateButtons() {
        assetSpinner.isEnabled = !isPlaying && !isPaused
        voiceSpinner.isEnabled = !isPlaying && !isPaused
        loadAssetButton.isEnabled = !isPlaying && !isPaused && assetFiles.isNotEmpty()
        selectFileButton.isEnabled = !isPlaying && !isPaused
        playButton.isEnabled = isTtsReady && chapters.isNotEmpty() && (!isPlaying || isPaused)
        pauseButton.isEnabled = isPlaying
        stopButton.isEnabled = isPlaying || isPaused
    }

    override fun onDestroy() {
        isPlaying = false
        isPaused = false
        if (::textToSpeech.isInitialized) {
            textToSpeech.stop()
            textToSpeech.shutdown()
        }
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onDestroy()
    }

    companion object {
        private const val REQUEST_OPEN_TEXT = 1001
    }
}
