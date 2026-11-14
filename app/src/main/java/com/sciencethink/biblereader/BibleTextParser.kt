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

    fun numberToKorean(num: Int): String {
        if (num <= 0) return num.toString()
        val units = arrayOf("", "일", "이", "삼", "사", "오", "육", "칠", "팔", "구")
        if (num < 10) return units[num]

        val sb = StringBuilder()
        val thousand = num / 1000
        val hundred = (num % 1000) / 100
        val ten = (num % 100) / 10
        val one = num % 10

        if (thousand > 0) {
            if (thousand > 1) sb.append(units[thousand])
            sb.append("천")
        }
        if (hundred > 0) {
            if (hundred > 1) sb.append(units[hundred])
            sb.append("백")
        }
        if (ten > 0) {
            if (ten > 1) sb.append(units[ten])
            sb.append("십")
        }
        if (one > 0) {
            sb.append(units[one])
        }
        return sb.toString()
    }

    private val versePrefix = Regex(
        """^\s*([가-힣]{1,8})\s*(\d{1,3})\s*:\s*(\d{1,3})(?:\s*-\s*(\d{1,3}))?\s*(?:<([^>\r\n]*)>)?\s*"""
    )
    private val bracketRegex = Regex("""\(([^)]+)\)""")
    private val patternA = Regex("""^\s*([가-힣]{1,8})\s*(\d{1,3})\s*:\s*(.+)$""")
    private val patternB = Regex("""^\s*(\d{1,3})\s*:\s*(.+)$""")

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

    private fun formatReferences(text: String): String {
        val bracketFormatted = bracketRegex.replace(text) { match ->
            val content = match.groupValues[1]
            val parsed = parseBracketContent(content)
            if (parsed.isNotEmpty()) " $parsed " else ""
        }

        val refPattern = Regex("""([가-힣]{1,8})\s*(\d{1,3})\s*:\s*(\d{1,3})(?:\s*-\s*(\d{1,3}))?""")
        val expandedRef = refPattern.replace(bracketFormatted) { match ->
            val rawBook = match.groupValues[1]
            val book = bookNames[rawBook] ?: rawBook
            val chap = match.groupValues[2].toIntOrNull() ?: 1
            val startV = match.groupValues[3].toIntOrNull() ?: 1
            val endV = match.groupValues[4].toIntOrNull()

            val chapKor = numberToKorean(chap)
            val startVKor = numberToKorean(startV)

            if (endV != null) {
                val endVKor = numberToKorean(endV)
                "$book ${chapKor}장 ${startVKor}절에서 ${endVKor}절"
            } else {
                "$book ${chapKor}장 ${startVKor}절"
            }
        }

        val withChapKor = Regex("""(\d+)장""").replace(expandedRef) { match ->
            val num = match.groupValues[1].toIntOrNull()
            if (num != null) "${numberToKorean(num)}장" else match.value
        }
        val withVerseKor = Regex("""(\d+)절""").replace(withChapKor) { match ->
            val num = match.groupValues[1].toIntOrNull()
            if (num != null) "${numberToKorean(num)}절" else match.value
        }

        return withVerseKor.replace(Regex("""\s+"""), " ").trim()
    }

    private fun parseBracketContent(content: String): String {
        val sections = content.split(";")
        var lastBook: String? = null
        var lastChapter: Int? = null
        val resultList = mutableListOf<String>()

        for (section in sections) {
            val subSections = section.split(",")
            for (sub in subSections) {
                val trimmed = sub.trim()
                if (trimmed.isEmpty()) continue

                var book: String? = null
                var chapter: Int? = null
                var versePart: String = trimmed

                val matchA = patternA.find(trimmed)
                if (matchA != null) {
                    val rawBook = matchA.groupValues[1]
                    book = bookNames[rawBook] ?: rawBook
                    chapter = matchA.groupValues[2].toIntOrNull()
                    versePart = matchA.groupValues[3].trim()
                } else {
                    val matchB = patternB.find(trimmed)
                    if (matchB != null) {
                        chapter = matchB.groupValues[1].toIntOrNull()
                        versePart = matchB.groupValues[2].trim()
                    }
                }

                val isBookChanged = book != null && book != lastBook
                val isChapterChanged = chapter != null && chapter != lastChapter

                if (book != null) lastBook = book
                if (chapter != null) lastChapter = chapter

                val formattedVerse = formatVersePart(versePart)
                if (formattedVerse.isEmpty()) continue

                val currentBook = lastBook
                val currentChapter = lastChapter

                val prefix = when {
                    isBookChanged && currentBook != null && currentChapter != null -> "$currentBook ${numberToKorean(currentChapter)}장 "
                    isChapterChanged && currentChapter != null -> "${numberToKorean(currentChapter)}장 "
                    else -> ""
                }

                resultList.add("$prefix$formattedVerse")
            }
        }

        return if (resultList.isNotEmpty()) {
            resultList.joinToString(" ")
        } else {
            content
        }
    }

    private fun formatVersePart(versePart: String): String {
        if (versePart.isEmpty()) return ""
        val dashIndex = versePart.indexOf('-')
        return if (dashIndex != -1) {
            val startStr = versePart.substring(0, dashIndex).trim()
            val endStr = versePart.substring(dashIndex + 1).trim()
            val startNum = startStr.toIntOrNull()
            val endNum = endStr.toIntOrNull()
            if (startNum != null && endNum != null) {
                "${numberToKorean(startNum)}절에서 ${numberToKorean(endNum)}절"
            } else if (startStr.isNotEmpty() && endStr.isNotEmpty()) {
                "$startStr 에서 ${endStr}절"
            } else {
                val num = versePart.toIntOrNull()
                if (num != null) "${numberToKorean(num)}절" else "${versePart}절"
            }
        } else {
            val num = versePart.toIntOrNull()
            if (num != null) "${numberToKorean(num)}절" else "${versePart}절"
        }
    }
}
