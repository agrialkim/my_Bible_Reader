package com.sciencethink.biblereader

data class BibleChapter(
    val book: String,
    val number: Int,
    val text: String,
    val spokenText: String,
    val verseLineIndices: Map<Int, Int> = emptyMap()
)

object BibleTextParser {
    val bookNames = mapOf(
        "창" to "창세기", "출" to "출애굽기", "레" to "레위기", "민" to "민수기",
        "신" to "신명기", "수" to "여호수아", "삿" to "사사기", "룻" to "룻기",
        "삼상" to "사무엘상", "삼하" to "사무엘하", "왕상" to "열왕기상",
        "왕하" to "열왕기하", "대상" to "역대상", "대하" to "역대하",
        "스" to "에스라", "느" to "느헤미야", "에" to "에스더", "욥" to "욥기",
        "시" to "시편", "잠" to "잠언", "전" to "전도서", "아" to "아가",
        "사" to "이사야", "렘" to "예레미야", "애" to "예레미야애가", "겔" to "에스겔",
        "단" to "다니엘", "호" to "호세아", "욜" to "요엘", "암" to "아모스",
        "옵" to "오바댜", "욘" to "요나", "미" to "미가", "나" to "나훔",
        "합" to "하박국", "습" to "스바냐", "학" to "학개", "슥" to "스가랴",
        "말" to "말라기", "마" to "마태복음", "막" to "마가복음", "눅" to "누가복음",
        "요" to "요한복음", "행" to "사도행전", "롬" to "로마서", "고전" to "고린도전서",
        "고후" to "고린도후서", "갈" to "갈라디아서", "엡" to "에베소서",
        "빌" to "빌립보서", "골" to "골로새서", "살전" to "데살로니가전서",
        "살후" to "데살로니가후서", "딤전" to "디모데전서", "딤후" to "디모데후서",
        "딛" to "디도서", "몬" to "빌레몬서", "히" to "히브리서", "약" to "야고보서",
        "벧전" to "베드로전서", "벧후" to "베드로후서", "요일" to "요한일서",
        "요이" to "요한이서", "요삼" to "요한삼서", "유" to "유다서", "계" to "요한계시록"
    )

    private val versePrefix = Regex(
        """^\s*([가-힣]{1,8})\s*(\d{1,3})\s*:\s*(\d{1,3})(?:\s*-\s*(\d{1,3}))?\s*(?:<([^>\r\n]*)>)?\s*"""
    )
    private val parentheticalReference = Regex(
        """\(([가-힣]{1,8})\s*(\d{1,3})\s*:\s*(\d{1,3})(?:\s*-\s*(\d{1,3}))?\)"""
    )

    fun parse(source: String): List<BibleChapter> {
        val chapters = mutableListOf<BibleChapter>()
        var currentBook: String? = null
        var currentNumber: Int? = null
        var displayTextLines = mutableListOf<String>()
        var spokenTextLines = mutableListOf<String>()
        val leadingDisplayLines = mutableListOf<String>()
        val leadingSpokenLines = mutableListOf<String>()
        val verseLineIndices = mutableMapOf<Int, Int>()
        val leadingVerseLineIndices = mutableMapOf<Int, Int>()

        for (line in source.lineSequence()) {
            val match = versePrefix.find(line)
            if (match == null) {
                val cleanedDisplay = line.trim()
                val cleanedSpoken = formatReferences(line.trim())
                if (cleanedDisplay.isNotEmpty()) {
                    if (currentBook == null) {
                        leadingDisplayLines.add(cleanedDisplay)
                        leadingSpokenLines.add(cleanedSpoken)
                    } else {
                        displayTextLines.add(cleanedDisplay)
                        spokenTextLines.add(cleanedSpoken)
                    }
                }
                continue
            }

            val abbreviation = match.groupValues.getOrNull(1).orEmpty()
            val chapter = match.groupValues.getOrNull(2)?.toIntOrNull() ?: 1
            val startVerse = match.groupValues.getOrNull(3).orEmpty()
            val endVerse = match.groupValues.getOrNull(4).orEmpty()
            val title = match.groupValues.getOrNull(5).orEmpty().trim()
            val verseNumDisplay = if (endVerse.isNotEmpty()) "$startVerse-$endVerse" else startVerse

            if (abbreviation != currentBook || chapter != currentNumber) {
                if (currentBook != null && currentNumber != null) {
                    chapters.add(
                        BibleChapter(
                            bookNames[currentBook] ?: currentBook,
                            currentNumber,
                            displayTextLines.joinToString("\n"),
                            spokenTextLines.joinToString("\n"),
                            verseLineIndices.toMap()
                        )
                    )
                }

                currentBook = abbreviation
                currentNumber = chapter
                displayTextLines = leadingDisplayLines.toMutableList()
                spokenTextLines = leadingSpokenLines.toMutableList()
                leadingDisplayLines.clear()
                leadingSpokenLines.clear()
                
                verseLineIndices.clear()
                verseLineIndices.putAll(leadingVerseLineIndices)
                leadingVerseLineIndices.clear()

                val bookName = bookNames[abbreviation] ?: abbreviation
                displayTextLines.add("$bookName ${chapter}장")
                spokenTextLines.add("$bookName ${numberToKorean(chapter)}장.")
            }

            if (title.isNotEmpty()) {
                displayTextLines.add(title)
                spokenTextLines.add(formatReferences(title))
            }

            val rawVerse = line.substring(match.range.last + 1).trim()
            if (rawVerse.isNotEmpty()) {
                val verseDisplay = if (verseNumDisplay.isNotEmpty()) "$verseNumDisplay $rawVerse" else rawVerse
                displayTextLines.add(verseDisplay)
                
                val verseStartInt = startVerse.toIntOrNull()
                val verseEndInt = endVerse.toIntOrNull() ?: verseStartInt
                if (verseStartInt != null && verseEndInt != null) {
                    for (v in verseStartInt..verseEndInt) {
                        if (currentBook == null) {
                            leadingVerseLineIndices[v] = leadingSpokenLines.size
                        } else {
                            verseLineIndices[v] = spokenTextLines.size
                        }
                    }
                }
                
                spokenTextLines.add(formatReferences(rawVerse))
            }
        }

        if (currentBook != null && currentNumber != null) {
            chapters.add(
                BibleChapter(
                    bookNames[currentBook] ?: currentBook,
                    currentNumber,
                    displayTextLines.joinToString("\n"),
                    spokenTextLines.joinToString("\n"),
                    verseLineIndices.toMap()
                )
            )
        }
        require(chapters.isNotEmpty()) { "성경 장절 표기가 있는 텍스트 파일을 선택해 주세요." }
        return chapters
    }

    private fun formatReferences(text: String): String =
        parentheticalReference.replace(text) { match ->
            val book = bookNames[match.groupValues[1]] ?: match.groupValues[1]
            val chapter = numberToKorean(match.groupValues[2].toInt())
            val start = numberToKorean(match.groupValues[3].toInt())
            val end = match.groupValues.getOrNull(4).orEmpty()
            if (end.isEmpty()) {
                "($book ${chapter}장 ${start}절)"
            } else {
                "($book ${chapter}장 ${start}에서 ${numberToKorean(end.toInt())}절)"
            }
        }

    private fun numberToKorean(number: Int): String {
        if (number == 0) return "영"
        val digits = listOf("", "일", "이", "삼", "사", "오", "육", "칠", "팔", "구")
        val units = listOf("", "십", "백")
        return number.toString().reversed().mapIndexedNotNull { position, char ->
            val digit = char.digitToInt()
            if (digit == 0) null
            else (if (digit == 1 && position > 0) "" else digits[digit]) + units[position]
        }.asReversed().joinToString("")
    }
}
