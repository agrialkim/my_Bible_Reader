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
import android.app.AlertDialog
import android.widget.EditText
import android.widget.Toast
import java.io.File
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
    private var koreanVoices: List<android.speech.tts.Voice> = emptyList()
    private var isTtsReady = false
    private var isPlaying = false
    private var isPaused = false
    private var currentChapterIndex = 0
    private var currentLineIndex = 0
    private var pausedScrollY = 0
    private var scrollAnimator: ValueAnimator? = null

    data class BibleFile(val name: String, val isAsset: Boolean, val file: java.io.File? = null)

    private fun getAvailableBibles(): List<BibleFile> {
        val list = mutableListOf<BibleFile>()
        val rawAssets = (assets.list("") ?: emptyArray()).filter { it.endsWith(".txt") }
        val preferredOrder = listOf("개역개정.txt", "KJV.txt", "새번역.txt")
        val sortedAssets = rawAssets.sortedWith(Comparator { a, b ->
            val indexA = preferredOrder.indexOf(a)
            val indexB = preferredOrder.indexOf(b)
            if (indexA != -1 && indexB != -1) indexA.compareTo(indexB)
            else if (indexA != -1) -1
            else if (indexB != -1) 1
            else a.compareTo(b)
        })
        sortedAssets.forEach { list.add(BibleFile(it, true)) }

        val importedDir = File(filesDir, "imported_bibles")
        if (importedDir.exists()) {
            importedDir.listFiles()?.filter { it.name.endsWith(".txt") }?.sortedBy { it.name }?.forEach {
                list.add(BibleFile(it.name, false, it))
            }
        }
        return list
    }

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

        searchIconButton.setOnClickListener { showSearchDialog() }
        bibleTitleTextView.setOnClickListener { showBibleSelectionDialog() }
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

    private fun showBibleSelectionDialog() {
        val bibles = getAvailableBibles()
        val names = bibles.map { it.name.removeSuffix(".txt") }.toTypedArray()
        
        AlertDialog.Builder(this)
            .setTitle("성경 선택")
            .setItems(names) { _, which ->
                val selected = bibles[which]
                val prefs = getSharedPreferences("bible_prefs", Context.MODE_PRIVATE)
                prefs.edit().putString("selected_bible_name", selected.name)
                            .putBoolean("selected_bible_is_asset", selected.isAsset).apply()
                loadBible(selected)
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun loadInitialAsset() {
        val prefs = getSharedPreferences("bible_prefs", Context.MODE_PRIVATE)
        val savedName = prefs.getString("selected_bible_name", "개역개정.txt") ?: "개역개정.txt"
        val isAsset = prefs.getBoolean("selected_bible_is_asset", true)
        
        val bible = BibleFile(savedName, isAsset, if (!isAsset) File(File(filesDir, "imported_bibles"), savedName) else null)
        loadBible(bible)
    }

    private fun loadBible(bible: BibleFile) {
        try {
            val bytes = if (bible.isAsset) {
                assets.open(bible.name).use { it.readBytes() }
            } else {
                bible.file?.readBytes() ?: throw Exception("파일이 없습니다.")
            }
            val text = decodeText(bytes)
            chapters = BibleTextParser.parse(text)
            currentChapterIndex = 0
            currentLineIndex = 0
            pausedScrollY = 0
            val displayName = bible.name.removeSuffix(".txt")
            bibleTitleTextView.text = displayName
            updateCurrentChapterDisplay()
        } catch (error: Exception) {
            chapters = emptyList()
            currentChapterIndex = 0
            currentLineIndex = 0
            pausedScrollY = 0
            bibleTextView.text = error.message ?: "파일을 읽지 못했습니다."
            bibleTitleTextView.text = "오류"
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

    private fun showSearchDialog() {
        if (chapters.isEmpty()) {
            Toast.makeText(this, "먼저 성경 텍스트 파일을 열어주세요.", Toast.LENGTH_SHORT).show()
            return
        }
        val input = EditText(this).apply {
            hint = "예: 창 1:5, 창세기 1장 5절"
            setPadding(48, 32, 48, 32)
        }
        AlertDialog.Builder(this)
            .setTitle("성경 구절 검색")
            .setView(input)
            .setPositiveButton("이동") { _, _ ->
                val query = input.text.toString().trim()
                jumpToQuery(query)
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun jumpToQuery(query: String) {
        val regex = Regex("""([가-힣]{1,8})\s*(\d{1,3})(?:\s*[장:\-\s]\s*(\d{1,3}))?""")
        val match = regex.find(query)
        if (match == null) {
            Toast.makeText(this, "검색어를 인식할 수 없습니다. (예: 창 1:5)", Toast.LENGTH_SHORT).show()
            return
        }
        val bookQuery = match.groupValues[1]
        val chapterQuery = match.groupValues[2].toIntOrNull() ?: 1
        val verseQuery = match.groupValues.getOrNull(3)?.toIntOrNull()

        val fullBookName = BibleTextParser.bookNames[bookQuery] ?: bookQuery

        val chapIdx = chapters.indexOfFirst { it.book == fullBookName && it.number == chapterQuery }
        if (chapIdx == -1) {
            Toast.makeText(this, "해당 장을 찾을 수 없습니다.", Toast.LENGTH_SHORT).show()
            return
        }

        stopPlayback()
        currentChapterIndex = chapIdx
        
        val chapter = chapters[chapIdx]
        currentLineIndex = if (verseQuery != null && chapter.verseLineIndices.containsKey(verseQuery)) {
            chapter.verseLineIndices[verseQuery]!!
        } else {
            0
        }
        
        runOnUiThread {
            updateCurrentChapterDisplay()
            bibleScrollView.post {
                val lines = chapter.spokenText.split("\n")
                val maxLines = lines.size
                
                val childHeight = bibleScrollView.getChildAt(0)?.height ?: 0
                val totalContentHeight = childHeight + bibleScrollView.paddingTop + bibleScrollView.paddingBottom
                val visibleHeight = bibleScrollView.height
                val maxScroll = (totalContentHeight - visibleHeight).coerceAtLeast(0)
                
                if (maxScroll > 0 && maxLines > 0) {
                    pausedScrollY = (maxScroll * (currentLineIndex.toFloat() / maxLines)).toInt()
                    bibleScrollView.scrollTo(0, pausedScrollY)
                } else {
                    pausedScrollY = 0
                    bibleScrollView.scrollTo(0, 0)
                }
                
                isPaused = false
                isPlaying = true
                playPauseToggleButton.setImageResource(R.drawable.ic_pause)
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                playCurrentChapter(resuming = true)
            }
        }
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
        private const val REQUEST_SETTINGS = 1002
    }
}
