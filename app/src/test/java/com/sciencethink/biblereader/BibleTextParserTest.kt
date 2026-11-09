package com.sciencethink.biblereader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BibleTextParserTest {
    @Test
    fun removesVerseRangesAndFormatsChapterAnnouncements() {
        val chapters = BibleTextParser.parse(
            listOf(
                "창1:1 <천지 창조> 태초에 하나님이 천지를 창조하시니라",
                "대상16:12-13 그의 종 이스라엘",
                "삿1:11 <옷니엘이 드빌을 치다(수 15:13-19)> 드빌의 주민들을 쳤으니"
            ).joinToString("\n")
        )

        assertEquals(3, chapters.size)
        assertEquals(
            "역대상 십육장.\n그의 종 이스라엘",
            chapters[1].text
        )
        assertEquals(
            "사사기 일장.\n옷니엘이 드빌을 치다(여호수아 십오장 십삼에서 십구절)\n드빌의 주민들을 쳤으니",
            chapters[2].text
        )
    }

    @Test
    fun parsesGenesisAndMatthewVersesCorrectly() {
        val sampleText = """
            창1:1 <천지 창조> 태초에 하나님이 천지를 창조하시니라
            창1:2 땅이 혼돈하고 공허하며 깊음 위에 있고 하나님의 영은 수면 위에 운행하시니라
            마1:1 <예수 그리스도의 계보> 아브라함과 다윗의 자손 예수 그리스도의 계보라
        """.trimIndent()

        val chapters = BibleTextParser.parse(sampleText)

        assertEquals(2, chapters.size)
        assertEquals("창세기", chapters[0].book)
        assertEquals(1, chapters[0].number)
        assertEquals(
            "창세기 일장.\n천지 창조\n태초에 하나님이 천지를 창조하시니라\n땅이 혼돈하고 공허하며 깊음 위에 있고 하나님의 영은 수면 위에 운행하시니라",
            chapters[0].text
        )

        assertEquals("마태복음", chapters[1].book)
        assertEquals(1, chapters[1].number)
    }

    @Test
    fun testGenerateAudioFromATxt() {
        val packageDir = File("src/test/java/com/sciencethink/biblereader").absoluteFile
        val aTxtFile = File(packageDir, "a.txt")
        assertTrue("a.txt 파일이 해당 폴더에 존재해야 합니다: ${aTxtFile.absolutePath}", aTxtFile.exists())

        val sourceText = aTxtFile.readText(Charsets.UTF_8)
        val chapters = BibleTextParser.parse(sourceText)

        val resultDir = File(packageDir, "result").apply {
            mkdirs()
            listFiles()?.forEach { it.delete() }
        }

        val fullTextBuilder = StringBuilder()

        chapters.forEachIndexed { index, chapter ->
            val fileName = "%04d_%s_%03d.mp3".format(index + 1, chapter.book, chapter.number)
            val outputFile = File(resultDir, fileName)
            synthesizeAudio(chapter.text, outputFile)
            assertTrue(outputFile.exists() && outputFile.length() > 0)
            fullTextBuilder.append(chapter.text).append("\n\n")
        }

        // 전체 합쳐진 음성 파일 (테스트 수행 시간 타임스탬프 MP3 파일 생성 ex: 20261110_072124.mp3)
        val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
        val combinedMp3 = File(resultDir, "${timestamp}.mp3")
        synthesizeAudio(fullTextBuilder.toString(), combinedMp3)
        assertTrue(combinedMp3.exists() && combinedMp3.length() > 0)
    }

    private fun synthesizeAudio(text: String, outputFile: File) {
        val edgeTtsExe = File("C:/Users/ScienceThink/a/BibleReader/my_Bible_Reader/.venv/Scripts/edge-tts.exe")
        if (edgeTtsExe.exists()) {
            val tempTextFile = File.createTempFile("tts_input_", ".txt").apply {
                writeText(text, Charsets.UTF_8)
            }
            try {
                val process = ProcessBuilder(
                    edgeTtsExe.absolutePath,
                    "--file", tempTextFile.absolutePath,
                    "--voice", "ko-KR-SunHiNeural",
                    "--write-media", outputFile.absolutePath
                ).start()
                process.waitFor()
                if (outputFile.exists() && outputFile.length() > 0) return
            } finally {
                tempTextFile.delete()
            }
        }

        val cleanText = text.replace("\"", "`").replace("\r", " ").replace("\n", " ")
        val outputPath = outputFile.absolutePath.replace("\\", "/")
        val script = "Add-Type -AssemblyName System.Speech; \$s = New-Object System.Speech.Synthesis.SpeechSynthesizer; \$s.SetOutputToWaveFile('$outputPath'); \$s.Speak('$cleanText'); \$s.Dispose()"
        val process = ProcessBuilder("powershell", "-Command", script).start()
        process.waitFor()
    }
}
