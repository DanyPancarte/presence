package com.dany.presence.ui

import androidx.compose.foundation.shape.GenericShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.dany.presence.R

/** Cyberpunk 2077 palette. Mirrors the :root tokens of tools/preview/mockup.html. */
object Cp {
    val bg = Color(0xFF050608)
    val cyan = Color(0xFF00F0FF)
    val cyanDim = Color(0x7300F0FF)
    val yellow = Color(0xFFFCEE0A)
    val red = Color(0xFFFF003C)
    val ink = Color(0xFFD7F7FA)
    val mute = Color(0xFF5D8A90)
    val panel = Color(0xB8040C10)

    val display = FontFamily(
        Font(R.font.rajdhani_medium, FontWeight.Medium),
        Font(R.font.rajdhani_semibold, FontWeight.SemiBold),
        Font(R.font.rajdhani_bold, FontWeight.Bold),
    )
    val mono = FontFamily(Font(R.font.sharetechmono))

    /** Panel silhouette: top-right and bottom-left corners cut. */
    fun cut(px: Float) = GenericShape { size, _ ->
        moveTo(0f, 0f)
        lineTo(size.width - px, 0f)
        lineTo(size.width, px)
        lineTo(size.width, size.height)
        lineTo(px, size.height)
        lineTo(0f, size.height - px)
        close()
    }
}
