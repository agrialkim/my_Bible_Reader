package com.sciencethink.biblereader

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.ImageButton
import android.widget.ScrollView
import android.widget.TextView
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Locale

class MainActivity : Activity(), TextToSpeech.OnInitListener {
    private lateinit var textToSpeech: TextToSpeech
    private lateinit var searchIconButton: ImageButton
    private lateinit var bibleTitleTextView: TextView
    private lateinit var settingsIconButton: ImageButton
    private lateinit var bibleScrollView: ScrollView
    private lateinit var bibleTextView: TextView
    private lateinit var playPauseToggleButton: ImageButton
    private lateinit var currentPositionTextView: TextView
    private lateinit var utilityIconButton: ImageButton

    private var chapters: List<BibleChapter> = emptyList()
    private var assetFiles: List<String> = emptyList()
    private var koreanVoices: List<android.speech.tts.Voice> = emptyList()
    private var isTtsReady = false
    private var isPlaying = false
    private var isPaused = false
    private var currentChapterIndex = 0
    private var currentLineIndex = 0
    private var pausedScrollY = 0
    private var scrollAnimator: ValueAnimator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        searchIconButton = findViewById(R.id.searchIconButton)
        bibleTitleTextView = findViewById(R.id.bibleTitleTextView)
        settingsIconButton = findViewById(R.id.settingsIconButton)
        bibleScrollView = findViewById(R.id.bibleScrollView)
        bibleTextView = findViewById(R.id.bibleTextView)
        playPauseToggleButton = findViewById(R.id.playPauseToggleButton)
        currentPositionTextView = findViewById(R.id.currentPositionTextView)
        utilityIconButton = findViewById(R.id.utilityIconButton)

        loadInitialAsset()

        searchIconButton.setOnClickListener { selectTextFile() }
        settingsIconButton.setOnClickListener {
            val intent = Intent(this, SettingsActivity::class.java)
            startActivityForResult(intent, REQUEST_SETTINGS)
        }
        playPauseToggleButton.setOnClickListener { playOrPauseToggle() }
        utilityIconButton.setOnClickListener {
            // Utility action
        }

        textToSpeech = TextToSpeech(this, this)
    }

    private fun loadInitialAsset() {
        try {
            val rawFiles = (assets.list("") ?: emptyArray()).filter { it.endsWith(".txt") }
            val preferredOrder = listOf("개역개정.txt", "KJV.txt", "새번역.txt")
            assetFiles = rawFiles.sortedWith(Comparator { a, b ->
                val indexA = preferredOrder.indexOf(a)
                val indexB = preferredOrder.indexOf(b)
                if (indexA != -1 && indexB != -1) indexA.compareTo(indexB)
                else if (indexA != -1) -1
                else if (indexB != -1) 1
                else a.compareTo(b)
            })

            val prefs = getSharedPreferences("bible_prefs", Context.MODE_PRIVATE)
            val savedIndex = prefs.getInt("asset_index", 0).coerceIn(0, (assetFiles.size - 1).coerceAtLeast(0))
            val fileName = assetFiles.getOrNull(savedIndex) ?: "개역개정.txt"

            loadAssetFile(fileName)
        } catch (_: Exception) {
            bibleTextView.text = "성경 본문을 불러오지 못했습니다."
        }
    }

    private fun loadAssetFile(fileName: String) {
        try {
            val bytes = assets.open(fileName).use { it.readBytes() }
            val text = decodeText(bytes)
            chapters = BibleTextParser.parse(text)
            currentChapterIndex = 0
            currentLineIndex = 0
            pausedScrollY = 0
            val displayName = fileName.removeSuffix(".txt")
            bibleTitleTextView.text = displayName
            updateCurrentChapterDisplay()
        } catch (error: Exception) {
            chapters = emptyList()
            currentChapterIndex = 0
            currentLineIndex = 0
            pausedScrollY = 0
            bibleTextView.text = error.message ?: "파일을 읽지 못했습니다."
        }
    }

    private fun updateCurrentChapterDisplay() {
        val chapter = chapters.getOrNull(currentChapterIndex)
        if (chapter != null) {
            currentPositionTextView.text = "${chapter.book} ${chapter.number}장"
            // \u200B (Zero-Width Space)를 매 글자 사이에 삽입하여 글자 단위 줄바꿈(word-break: break-all) 강제
            bibleTextView.text = chapter.text.toCharArray().joinToString("\u200B")
        } else {
            bibleTextView.text = "선택된 본문이 없습니다."
            currentPositionTextView.text = "준비됨"
        }
    }

    private fun startSmoothAutoScrollForCurrentChapter(resuming: Boolean) {
        val prefs = getSharedPreferences("bible_prefs", Context.MODE_PRIVATE)
        val autoScroll = prefs.getBoolean("auto_scroll_enabled", true)
        if (!autoScroll) return

        stopSmoothAutoScroll()

        bibleScrollView.viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                bibleScrollView.viewTreeObserver.removeOnGlobalLayoutListener(this)

                val childHeight = bibleScrollView.getChildAt(0)?.height ?: 0
                val totalContentHeight = childHeight + bibleScrollView.paddingTop + bibleScrollView.paddingBottom
                val visibleHeight = bibleScrollView.height
                val maxScroll = (totalContentHeight - visibleHeight).coerceAtLeast(0)
                if (maxScroll <= 0) return

                val chapter = chapters.getOrNull(currentChapterIndex) ?: return

                val speedMultiplier = prefs.getFloat("scroll_speed_multiplier", 1.0f).coerceIn(0.2f, 5.0f)
                val baseDurationMs = (chapter.spokenText.length * 100L).coerceIn(3000L, 300000L)
                val totalDurationMs = (baseDurationMs / speedMultiplier).toLong().coerceIn(2000L, 300000L)

                val startScroll = if (resuming && pausedScrollY > 0) pausedScrollY else bibleScrollView.scrollY
                val remainingRatio = if (maxScroll > startScroll) (maxScroll - startScroll).toFloat() / maxScroll.toFloat() else 0f
                val remainingDurationMs = (totalDurationMs * remainingRatio).toLong().coerceIn(1000L, totalDurationMs)

                scrollAnimator = ValueAnimator.ofInt(startScroll, maxScroll).apply {
                    duration = remainingDurationMs
                    interpolator = LinearInterpolator()
                    // 새 장 시작 시(처음부터 읽을 때) 7.5초 대기 후 스크롤 시작
                    if (!resuming) {
                        startDelay = 7500L
                    }
                    addUpdateListener { animator ->
                        val valY = animator.animatedValue as Int
                        bibleScrollView.scrollTo(0, valY)
                    }
                    start()
                }
            }
        })
    }

    private fun stopSmoothAutoScroll() {
        scrollAnimator?.cancel()
        scrollAnimator = null
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            bibleTextView.text = "음성 엔진을 시작하지 못했습니다."
            return
        }

        val languageStatus = textToSpeech.setLanguage(Locale.KOREAN)
        if (languageStatus == TextToSpeech.LANG_MISSING_DATA ||
            languageStatus == TextToSpeech.LANG_NOT_SUPPORTED
        ) {
            bibleTextView.text = "한국어 음성 데이터가 없습니다."
            return
        }

        isTtsReady = true
        setupVoiceFromPrefs()
    }

    private fun setupVoiceFromPrefs() {
        try {
            koreanVoices = textToSpeech.voices?.filter { it.locale.language == "ko" } ?: emptyList()
            if (koreanVoices.isNotEmpty()) {
                val prefs = getSharedPreferences("bible_prefs", Context.MODE_PRIVATE)
                val voiceIndex = prefs.getInt("voice_index", 0).coerceIn(0, koreanVoices.size - 1)
                koreanVoices.getOrNull(voiceIndex)?.let {
                    textToSpeech.voice = it
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun selectTextFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/plain"
        }
        startActivityForResult(intent, REQUEST_OPEN_TEXT)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        if (requestCode == REQUEST_SETTINGS) {
            loadInitialAsset()
            setupVoiceFromPrefs()
            if (isPlaying) {
                startSmoothAutoScrollForCurrentChapter(true)
            }
            return
        }
        if (requestCode == REQUEST_OPEN_TEXT) {
            val uri = data?.data ?: return
            try {
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: error("파일을 읽을 수 없습니다.")
                val text = decodeText(bytes)
                stopPlayback()
                chapters = BibleTextParser.parse(text)
                currentChapterIndex = 0
                currentLineIndex = 0
                pausedScrollY = 0
                val rawName = uri.lastPathSegment ?: "선택한 파일"
                bibleTitleTextView.text = rawName.removeSuffix(".txt")
                updateCurrentChapterDisplay()
            } catch (error: Exception) {
                chapters = emptyList()
                currentChapterIndex = 0
                currentLineIndex = 0
                pausedScrollY = 0
                bibleTextView.text = error.message ?: "텍스트 파일을 읽지 못했습니다."
            }
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

    private fun playOrPauseToggle() {
        if (!isTtsReady || chapters.isEmpty()) return
        if (isPlaying) {
            pausedScrollY = bibleScrollView.scrollY
            textToSpeech.stop()
            isPlaying = false
            isPaused = true
            stopSmoothAutoScroll()
            playPauseToggleButton.setImageResource(R.drawable.ic_play)
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else if (isPaused) {
            isPaused = false
            isPlaying = true
            playPauseToggleButton.setImageResource(R.drawable.ic_pause)
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            playCurrentChapter(resuming = true)
        } else {
            isPlaying = true
            isPaused = false
            currentChapterIndex = 0
            currentLineIndex = 0
            pausedScrollY = 0
            playPauseToggleButton.setImageResource(R.drawable.ic_pause)
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            playCurrentChapter(resuming = false)
        }
    }

    private fun playCurrentChapter(resuming: Boolean = false) {
        if (!isPlaying || currentChapterIndex >= chapters.size) return
        val chapter = chapters[currentChapterIndex]
        updateCurrentChapterDisplay()

        if (!resuming) {
            currentLineIndex = 0
            pausedScrollY = 0
            bibleScrollView.scrollTo(0, 0)
        } else {
            bibleScrollView.scrollTo(0, pausedScrollY)
        }
        startSmoothAutoScrollForCurrentChapter(resuming)

        val lines = chapter.spokenText.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) {
            currentChapterIndex++
            currentLineIndex = 0
            playCurrentChapter(false)
            return
        }

        val startLine = if (resuming) currentLineIndex.coerceIn(0, lines.size - 1) else 0

        for (i in startLine until lines.size) {
            val line = lines[i]
            val queueMode = if (i == startLine) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            val isLastLine = (i == lines.size - 1)
            val utteranceId = if (isLastLine) {
                "chapter-done-$currentChapterIndex"
            } else {
                "chapter-line-$currentChapterIndex-$i"
            }

            textToSpeech.speak(line, queueMode, null, utteranceId)
        }

        textToSpeech.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                if (!isPlaying) return
                val parts = utteranceId?.split("-") ?: return
                val chapIdx = when {
                    utteranceId.startsWith("chapter-done-") -> utteranceId.substringAfter("chapter-done-").toIntOrNull()
                    utteranceId.startsWith("chapter-line-") -> parts.getOrNull(2)?.toIntOrNull()
                    else -> null
                }
                val lineIdx = when {
                    utteranceId.startsWith("chapter-line-") -> parts.getOrNull(3)?.toIntOrNull()
                    utteranceId.startsWith("chapter-done-") -> lines.size - 1
                    else -> null
                }

                if (chapIdx != null && chapIdx != currentChapterIndex) {
                    currentChapterIndex = chapIdx
                    currentLineIndex = 0
                    pausedScrollY = 0
                    runOnUiThread {
                        updateCurrentChapterDisplay()
                        bibleScrollView.scrollTo(0, 0)
                        startSmoothAutoScrollForCurrentChapter(false)
                    }
                } else if (lineIdx != null) {
                    currentLineIndex = lineIdx
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
                            currentLineIndex = 0
                            pausedScrollY = 0
                            if (currentChapterIndex < chapters.size) {
                                playCurrentChapter(false)
                            } else {
                                // 모든 장을 다 읽었을 때 처음(0번째 장)부터 다시 시작
                                currentChapterIndex = 0
                                currentLineIndex = 0
                                pausedScrollY = 0
                                playCurrentChapter(false)
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
                            stopSmoothAutoScroll()
                            playPauseToggleButton.setImageResource(R.drawable.ic_play)
                            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                            textToSpeech.stop()
                        }
                    }
                }
            }
        })
    }

    private fun stopPlayback() {
        isPlaying = false
        isPaused = false
        currentChapterIndex = 0
        currentLineIndex = 0
        pausedScrollY = 0
        textToSpeech.stop()
        stopSmoothAutoScroll()
        playPauseToggleButton.setImageResource(R.drawable.ic_play)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        updateCurrentChapterDisplay()
        bibleScrollView.scrollTo(0, 0)
    }

    override fun onDestroy() {
        isPlaying = false
        isPaused = false
        currentLineIndex = 0
        pausedScrollY = 0
        stopSmoothAutoScroll()
        if (::textToSpeech.isInitialized) {
            textToSpeech.stop()
            textToSpeech.shutdown()
        }
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onDestroy()
    }

    companion object {
        private const val REQUEST_OPEN_TEXT = 1001
        private const val REQUEST_SETTINGS = 1002
    }
}
