package com.dany.presence.hud

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.dany.presence.R

/**
 * Palette and type of the instrument. Mirrors the :root tokens of tools/preview/mockup.html.
 * Poster typography: tiny spaced uppercase labels (Archivo Narrow), mono numbers (IBM Plex Mono).
 */
object HudTheme {
    val night = Color(0xFF04060B)
    val bone = Color(0xFFE6E1D6)
    val ash = Color(0xFF6F7278)
    val gold = Color(0xFFE2B25C)
    val ember = Color(0xFFFF7A2A)

    /** Archivo Narrow, one variable file; the weight axis is set per FontWeight. */
    val narrow = FontFamily(
        Font(R.font.archivonarrow, FontWeight.Normal),
        Font(R.font.archivonarrow, FontWeight.Medium),
    )
    val mono = FontFamily(Font(R.font.ibmplexmono))

    /** Tiny spaced label, 0.2 em tracking. Callers uppercase the text. */
    fun label(size: TextUnit = 9.sp, weight: FontWeight = FontWeight.Normal, color: Color = ash) = TextStyle(
        fontFamily = narrow, fontWeight = weight, fontSize = size, letterSpacing = size * 0.2f, lineHeight = size * 1.3f, color = color,
    )

    /** Mono number (clock). */
    fun number(size: TextUnit = 11.sp, color: Color = bone) = TextStyle(
        fontFamily = mono, fontSize = size, letterSpacing = size * 0.08f, lineHeight = size * 1.2f, color = color,
    )

    /** The one sentence at the bottom: not uppercase, looser tracking. */
    fun said(color: Color = bone) = TextStyle(
        fontFamily = narrow, fontWeight = FontWeight.Normal, fontSize = 11.sp, letterSpacing = 1.1.sp, lineHeight = 15.sp, color = color,
    )
}
