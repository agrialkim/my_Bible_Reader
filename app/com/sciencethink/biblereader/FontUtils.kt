package com.sciencethink.biblereader

import android.content.Context
import android.graphics.Typeface

object FontUtils {
    val fontKeys = listOf("nanum_gothic", "nanum_myeongjo", "sans-serif", "serif", "monospace")
    val fontNames = listOf("나눔고딕", "나눔명조", "기본 고딕", "기본 명조", "고정폭")

    fun getFontTypeface(context: Context, fontKey: String): Typeface {
        return when (fontKey) {
            "nanum_gothic" -> try {
                context.resources.getFont(R.font.nanum_gothic)
            } catch (_: Exception) { Typeface.SANS_SERIF }
            "nanum_myeongjo" -> try {
                context.resources.getFont(R.font.nanum_myeongjo)
            } catch (_: Exception) { Typeface.SERIF }
            "serif" -> Typeface.SERIF
            "monospace" -> Typeface.MONOSPACE
            "sans-serif" -> Typeface.SANS_SERIF
            else -> try {
                context.resources.getFont(R.font.nanum_gothic)
            } catch (_: Exception) { Typeface.DEFAULT }
        }
    }
}
