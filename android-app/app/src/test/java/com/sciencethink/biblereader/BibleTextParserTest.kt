package com.sciencethink.biblereader

import org.junit.Assert.assertEquals
import org.junit.Test

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
}
