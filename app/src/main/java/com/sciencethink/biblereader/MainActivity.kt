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
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
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
    private var highlightedLineIndex: Int? = null
    private var forceDelayOnce: Long = 0L

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
        applyDisplaySettings()

        searchIconButton.setOnClickListener { showSearchDialog() }
        bibleTitleTextView.setOnClickListener { showBibleSelectionDialog() }
        settingsIconButton.setOnClickListener {
            val intent = Intent(this, SettingsActivity::class.java)
            startActivityForResult(intent, REQUEST_SETTINGS)
        }
        playPauseToggleButton.setOnClickListener { playOrPauseToggle() }
        utilityIconButton.setOnClickListener {
            showDisplaySettingsDialog()
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

    private fun applyDisplaySettings(themeMode: String? = null) {
        val prefs = getSharedPreferences("bible_prefs", Context.MODE_PRIVATE)
        val mode = themeMode ?: prefs.getString("theme_mode", "normal") ?: "normal"
        val fontSize = prefs.getFloat("font_size", 12f)
        
        bibleTextView.textSize = fontSize
        
        val topBarLayout = findViewById<android.widget.LinearLayout>(R.id.topBarLayout)
        val bottomBarLayout = findViewById<android.widget.LinearLayout>(R.id.bottomBarLayout)
        val innerLayout = bibleScrollView.getChildAt(0) as? android.widget.LinearLayout
        when (mode) {
            "dark" -> {
                topBarLayout.setBackgroundColor(android.graphics.Color.parseColor("#1E3A8A")) // 짙은 감청색
                bottomBarLayout.setBackgroundColor(android.graphics.Color.parseColor("#1E3A8A"))
                bibleScrollView.setBackgroundColor(android.graphics.Color.parseColor("#121212"))
                innerLayout?.setBackgroundColor(android.graphics.Color.parseColor("#121212"))
                bibleTextView.setTextColor(android.graphics.Color.parseColor("#E2E8F0"))
            }
            "bluelight" -> {
                topBarLayout.setBackgroundColor(android.graphics.Color.parseColor("#2563EB")) // 원래 파란색
                bottomBarLayout.setBackgroundColor(android.graphics.Color.parseColor("#2563EB"))
                bibleScrollView.setBackgroundColor(android.graphics.Color.parseColor("#FFF9C4"))
                innerLayout?.setBackgroundColor(android.graphics.Color.parseColor("#FFF9C4"))
                bibleTextView.setTextColor(android.graphics.Color.parseColor("#333333"))
            }
            else -> { // normal
                topBarLayout.setBackgroundColor(android.graphics.Color.parseColor("#2563EB"))
                bottomBarLayout.setBackgroundColor(android.graphics.Color.parseColor("#2563EB"))
                bibleScrollView.setBackgroundColor(android.graphics.Color.parseColor("#F8FAFC"))
                innerLayout?.setBackgroundColor(android.graphics.Color.parseColor("#FFFFFF"))
                bibleTextView.setTextColor(android.graphics.Color.parseColor("#0F172A"))
            }
        }
    }

    private fun showDisplaySettingsDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_display_settings, null)
        val normalBtn = dialogView.findViewById<Button>(R.id.themeNormalBtn)
        val darkBtn = dialogView.findViewById<Button>(R.id.themeDarkBtn)
        val blueBtn = dialogView.findViewById<Button>(R.id.themeBlueBtn)
        
        val speedSeekBar = dialogView.findViewById<SeekBar>(R.id.displaySpeedSeekBar)
        val speedValue = dialogView.findViewById<TextView>(R.id.displaySpeedValue)
        
        val delaySeekBar = dialogView.findViewById<SeekBar>(R.id.delaySeekBar)
        val delayValue = dialogView.findViewById<TextView>(R.id.delayValue)
        
        val fontMinus = dialogView.findViewById<Button>(R.id.fontMinusBtn)
        val fontPlus = dialogView.findViewById<Button>(R.id.fontPlusBtn)
        val fontValue = dialogView.findViewById<TextView>(R.id.fontValueText)
        
        val prefs = getSharedPreferences("bible_prefs", Context.MODE_PRIVATE)
        var currentTheme = prefs.getString("theme_mode", "normal") ?: "normal"
        
        fun updateThemeUI(mode: String) {
            val selectedBg = android.graphics.Color.parseColor("#94A3B8")
            val unselectedBg = android.graphics.Color.parseColor("#E2E8F0")
            
            normalBtn.setBackgroundColor(if (mode == "normal") selectedBg else unselectedBg)
            darkBtn.setBackgroundColor(if (mode == "dark") selectedBg else unselectedBg)
            blueBtn.setBackgroundColor(if (mode == "bluelight") selectedBg else unselectedBg)
            
            normalBtn.setTextColor(if (mode == "normal") android.graphics.Color.WHITE else android.graphics.Color.BLACK)
            darkBtn.setTextColor(if (mode == "dark") android.graphics.Color.WHITE else android.graphics.Color.BLACK)
            blueBtn.setTextColor(if (mode == "bluelight") android.graphics.Color.WHITE else android.graphics.Color.BLACK)
        }
        
        updateThemeUI(currentTheme)
        
        normalBtn.setOnClickListener { currentTheme = "normal"; updateThemeUI("normal"); applyDisplaySettings(currentTheme) }
        darkBtn.setOnClickListener { currentTheme = "dark"; updateThemeUI("dark"); applyDisplaySettings(currentTheme) }
        blueBtn.setOnClickListener { currentTheme = "bluelight"; updateThemeUI("bluelight"); applyDisplaySettings(currentTheme) }
        
        speedSeekBar.max = 20
        val savedMultiplier = prefs.getFloat("scroll_speed_multiplier", 1.0f)
        speedSeekBar.progress = ((savedMultiplier - 0.5f) * 10f).toInt().coerceIn(0, 20)
        speedValue.text = String.format(java.util.Locale.US, "%.1f배", savedMultiplier)
        
        speedSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val multi = 0.5f + (progress * 0.1f)
                speedValue.text = String.format(java.util.Locale.US, "%.1f배", multi)
                prefs.edit().putFloat("scroll_speed_multiplier", multi).apply()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        
        delaySeekBar.max = 4 // 0, 1, 2, 3, 4 -> 0, 5, 10, 15, 20초
        val savedDelay = prefs.getInt("initial_delay_sec", 15)
        delaySeekBar.progress = savedDelay / 5
        delayValue.text = "${savedDelay}초"
        
        delaySeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val sec = progress * 5
                delayValue.text = "${sec}초"
                prefs.edit().putInt("initial_delay_sec", sec).apply()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        
        var currentFont = prefs.getFloat("font_size", 12f)
        fontValue.text = "${currentFont.toInt()}"
        
        val updateFont = {
            fontValue.text = "${currentFont.toInt()}"
            prefs.edit().putFloat("font_size", currentFont).apply()
            applyDisplaySettings(currentTheme)
        }
        
        fontMinus.setOnClickListener {
            if (currentFont > 8f) {
                currentFont -= 1f
                updateFont()
            }
        }
        fontPlus.setOnClickListener {
            if (currentFont < 40f) {
                currentFont += 1f
                updateFont()
            }
        }
        
        AlertDialog.Builder(this)
            .setTitle("화면 및 재생 설정")
            .setView(dialogView)
            .setPositiveButton("닫기") { _, _ -> 
                prefs.edit().putString("theme_mode", currentTheme).apply()
                if (isPlaying) {
                    stopSmoothAutoScroll()
                    startSmoothAutoScrollForCurrentChapter(true)
                }
            }
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
            
            val lines = chapter.text.split("\n")
            val spannable = android.text.SpannableStringBuilder()
            
            val prefs = getSharedPreferences("bible_prefs", Context.MODE_PRIVATE)
            val isDarkMode = prefs.getString("theme_mode", "normal") == "dark"
            val highlightColor = if (isDarkMode) android.graphics.Color.parseColor("#334155") else android.graphics.Color.parseColor("#FFD1DC")
            
            for ((index, line) in lines.withIndex()) {
                val spacedLine = line.toCharArray().joinToString("\u200B")
                val start = spannable.length
                spannable.append(spacedLine)
                if (index == highlightedLineIndex) {
                    spannable.setSpan(
                        android.text.style.BackgroundColorSpan(highlightColor), 
                        start,
                        spannable.length,
                        android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
                if (index < lines.size - 1) {
                    spannable.append("\n")
                }
            }
            bibleTextView.text = spannable
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
                    
                    val savedDelayMs = prefs.getInt("initial_delay_sec", 15) * 1000L
                    if (forceDelayOnce > 0L) {
                        startDelay = forceDelayOnce
                        forceDelayOnce = 0L
                    } else if (!resuming) {
                        startDelay = savedDelayMs
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

        val dialogView = layoutInflater.inflate(R.layout.dialog_search, null)
        val searchEditText = dialogView.findViewById<EditText>(R.id.searchEditText)
        val bookSpinner = dialogView.findViewById<android.widget.Spinner>(R.id.bookSpinner)
        val chapterSpinner = dialogView.findViewById<android.widget.Spinner>(R.id.chapterSpinner)
        val verseSpinner = dialogView.findViewById<android.widget.Spinner>(R.id.verseSpinner)
        val otCategoryButton = dialogView.findViewById<android.widget.Button>(R.id.otCategoryButton)
        val ntCategoryButton = dialogView.findViewById<android.widget.Button>(R.id.ntCategoryButton)

        val uniqueBooks = chapters.map { it.book }.distinct()
        val bookAdapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_item, uniqueBooks)
        bookAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        bookSpinner.adapter = bookAdapter

        val otCategories = arrayOf("모세오경", "역사서 (구약)", "시가서", "대선지서", "소선지서")
        val ntCategories = arrayOf("복음서", "역사서 (신약)", "서신서", "예언서 (계시록)")
        
        val categoryMap = mapOf(
            "모세오경" to listOf("창세기", "출애굽기", "레위기", "민수기", "신명기"),
            "역사서 (구약)" to listOf("여호수아", "사사기", "룻기", "사무엘상", "사무엘하", "열왕기상", "열왕기하", "역대상", "역대하", "에스라", "느헤미야", "에스더"),
            "시가서" to listOf("욥기", "시편", "잠언", "전도서", "아가"),
            "대선지서" to listOf("이사야", "예레미야", "예레미야애가", "에스겔", "다니엘"),
            "소선지서" to listOf("호세아", "요엘", "아모스", "오바댜", "요나", "미가", "나훔", "하박국", "스바냐", "학개", "스가랴", "말라기"),
            "복음서" to listOf("마태복음", "마가복음", "누가복음", "요한복음"),
            "역사서 (신약)" to listOf("사도행전"),
            "서신서" to listOf("로마서", "고린도전서", "고린도후서", "갈라디아서", "에베소서", "빌립보서", "골로새서", "데살로니가전서", "데살로니가후서", "디모데전서", "디모데후서", "디도서", "빌레몬서", "히브리서", "야고보서", "베드로전서", "베드로후서", "요한일서", "요한이서", "요한삼서", "유다서"),
            "예언서 (계시록)" to listOf("요한계시록")
        )

        fun selectFirstAvailableBook(category: String) {
            val booksInCategory = categoryMap[category] ?: return
            val firstAvailable = booksInCategory.firstOrNull { uniqueBooks.contains(it) }
            if (firstAvailable != null) {
                val index = uniqueBooks.indexOf(firstAvailable)
                bookSpinner.setSelection(index)
            } else {
                Toast.makeText(this@MainActivity, "해당 분류의 성경이 포함되어 있지 않습니다.", Toast.LENGTH_SHORT).show()
            }
        }

        otCategoryButton.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("구약 분류 선택")
                .setItems(otCategories) { _, which ->
                    selectFirstAvailableBook(otCategories[which])
                }
                .show()
        }

        ntCategoryButton.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("신약 분류 선택")
                .setItems(ntCategories) { _, which ->
                    selectFirstAvailableBook(ntCategories[which])
                }
                .show()
        }

        fun syncEditText() {
            val book = bookSpinner.selectedItem as? String ?: return
            val chap = (chapterSpinner.selectedItem as? String)?.replace("장", "") ?: "1"
            val verse = (verseSpinner.selectedItem as? String)?.replace("절", "") ?: "1"
            searchEditText.setText("$book $chap:$verse")
            searchEditText.setSelection(searchEditText.text.length)
        }

        bookSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                val selectedBook = uniqueBooks[position]
                val bookChapters = chapters.filter { it.book == selectedBook }
                val chapterNumbers = bookChapters.map { "${it.number}장" }
                
                val chapterAdapter = android.widget.ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_item, chapterNumbers)
                chapterAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                chapterSpinner.adapter = chapterAdapter

                chapterSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, chapterPos: Int, id: Long) {
                        val selectedChapter = bookChapters[chapterPos]
                        val verses = selectedChapter.verseLineIndices.keys.sorted().map { "${it}절" }
                        val verseAdapter = android.widget.ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_item, if (verses.isEmpty()) listOf("1절") else verses)
                        verseAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                        verseSpinner.adapter = verseAdapter
                        
                        verseSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                            override fun onItemSelected(p0: android.widget.AdapterView<*>?, p1: android.view.View?, p2: Int, p3: Long) {
                                syncEditText()
                            }
                            override fun onNothingSelected(p0: android.widget.AdapterView<*>?) {}
                        }
                    }
                    override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
                }
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        val currentChapter = chapters.getOrNull(currentChapterIndex)
        if (currentChapter != null) {
            val bookIndex = uniqueBooks.indexOf(currentChapter.book)
            if (bookIndex >= 0) {
                bookSpinner.setSelection(bookIndex)
            }
        }

        AlertDialog.Builder(this)
            .setTitle("성경 구절 검색")
            .setView(dialogView)
            .setPositiveButton("이동") { _, _ ->
                val query = searchEditText.text.toString().trim()
                if (query.isNotEmpty()) {
                    jumpToQuery(query)
                } else {
                    val book = bookSpinner.selectedItem as? String ?: return@setPositiveButton
                    val chapStr = chapterSpinner.selectedItem as? String ?: return@setPositiveButton
                    val verseStr = verseSpinner.selectedItem as? String ?: return@setPositiveButton
                    
                    val chapterNum = chapStr.replace("장", "").toIntOrNull() ?: 1
                    val verseNum = verseStr.replace("절", "").toIntOrNull() ?: 1
                    
                    val chapIdx = chapters.indexOfFirst { it.book == book && it.number == chapterNum }
                    jumpToLocation(chapIdx, verseNum)
                }
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
        jumpToLocation(chapIdx, verseQuery)
    }

    private fun jumpToLocation(chapIdx: Int, verseNum: Int?) {
        if (chapIdx == -1) {
            Toast.makeText(this, "해당 장을 찾을 수 없습니다.", Toast.LENGTH_SHORT).show()
            return
        }

        stopPlayback()
        currentChapterIndex = chapIdx
        
        val chapter = chapters[chapIdx]
        currentLineIndex = if (verseNum != null && chapter.verseLineIndices.containsKey(verseNum)) {
            chapter.verseLineIndices[verseNum]!!
        } else {
            0
        }
        
        runOnUiThread {
            highlightedLineIndex = currentLineIndex
            updateCurrentChapterDisplay()
            bibleScrollView.post {
                val layout = bibleTextView.layout
                if (layout != null) {
                    val originalLines = chapter.text.split("\n")
                    var originalOffset = 0
                    for (i in 0 until currentLineIndex.coerceAtMost(originalLines.size)) {
                        originalOffset += originalLines[i].length + 1
                    }
                    val modifiedOffset = originalOffset * 2
                    
                    val layoutLine = layout.getLineForOffset(modifiedOffset.coerceAtMost(bibleTextView.text.length))
                    val y = layout.getLineTop(layoutLine)
                    
                    val childHeight = bibleScrollView.getChildAt(0)?.height ?: 0
                    val totalContentHeight = childHeight + bibleScrollView.paddingTop + bibleScrollView.paddingBottom
                    val maxScroll = (totalContentHeight - bibleScrollView.height).coerceAtLeast(0)
                    
                    // 시작 위치를 약간 아래로 하여 윗부분이 가려지지 않게 함 (100px 여백 추가)
                    pausedScrollY = (y + bibleTextView.top - 100).coerceIn(0, maxScroll)
                    bibleScrollView.scrollTo(0, pausedScrollY)
                } else {
                    pausedScrollY = 0
                    bibleScrollView.scrollTo(0, 0)
                }
                
                val savedDelayMs = getSharedPreferences("bible_prefs", Context.MODE_PRIVATE).getInt("initial_delay_sec", 15) * 1000L
                forceDelayOnce = savedDelayMs // 검색이나 특정 위치로 이동 후 재생 시 딜레이 대기

                isPaused = true
                isPlaying = false
                playPauseToggleButton.setImageResource(R.drawable.ic_play)
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                stopSmoothAutoScroll()
                textToSpeech.stop()
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

    private fun syncLineWithScrollPosition() {
        val chapter = chapters.getOrNull(currentChapterIndex) ?: return
        val layout = bibleTextView.layout ?: return
        
        // 윗부분이 가려지지 않게 100px 여백을 두었으므로, 보정치 + 120을 더해 실제 눈에 보이는 줄을 찾음
        val scrollY = (bibleScrollView.scrollY + 120 - bibleTextView.top).coerceAtLeast(0)
        val layoutLine = layout.getLineForVertical(scrollY)
        val modifiedOffset = layout.getLineStart(layoutLine)
        
        // Zero-Width Space가 삽입되어 있으므로 텍스트 인덱스를 2로 나눔
        val originalOffset = modifiedOffset / 2
        
        val originalText = chapter.text
        var lineCount = 0
        for (i in 0 until originalOffset.coerceAtMost(originalText.length)) {
            if (originalText[i] == '\n') {
                lineCount++
            }
        }
        
        currentLineIndex = lineCount.coerceIn(0, chapter.spokenText.split("\n").size - 1)
        pausedScrollY = bibleScrollView.scrollY
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
        } else {
            // 사용자가 수동으로 스크롤을 움직인 경우에만 동기화 수행
            if (kotlin.math.abs(bibleScrollView.scrollY - pausedScrollY) > 5) {
                syncLineWithScrollPosition()
                forceDelayOnce = 5000L // 수동 스크롤 조작 후 재생 시 5초 대기
            }
            
            isPaused = false
            isPlaying = true
            playPauseToggleButton.setImageResource(R.drawable.ic_pause)
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            
            val isFromStart = currentLineIndex == 0
            if (isFromStart && forceDelayOnce == 0L) {
                val savedDelayMs = getSharedPreferences("bible_prefs", Context.MODE_PRIVATE).getInt("initial_delay_sec", 15) * 1000L
                forceDelayOnce = savedDelayMs
            }
            
            playCurrentChapter(resuming = !isFromStart)
        }
    }

    private fun playCurrentChapter(resuming: Boolean = false) {
        if (!isPlaying || currentChapterIndex >= chapters.size) return
        highlightedLineIndex = null
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
