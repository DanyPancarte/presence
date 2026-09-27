package app.murmure.ui.theme

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.murmure.R

/**
 * Brainmeat — un seul matériau (obsidienne), une encre, un accent (cuivre).
 * Les anciens noms pastel sont conservés comme *rôles* mais remappés sur une gamme tonale
 * sobre : plus aucune couleur ne décore, chacune signifie.
 */
object M {
    val Ink = Color(0xFF0B0B0D)
    val Night = Color(0xFF111114)
    val Surface = Color(0xFF17171B)
    val Surface2 = Color(0xFF1F1F24)
    val Line = Color(0xFF2A2A31)
    val Text = Color(0xFFECE9E2)
    val Muted = Color(0xFF8E8B84)
    val Faint = Color(0xFF5B5953)

    val Copper = Color(0xFFC8845A)
    val CopperDim = Color(0xFF7A5238)

    val Lilac = Color(0xFFB9B4C6)
    val Peach = Copper
    val Mint = Color(0xFF9FB7A6)
    val Sky = Color(0xFF9EAEBC)
    val Butter = Color(0xFFC9BC9A)
    val Rose = Color(0xFFC0A3A6)
    val Coral = Color(0xFFC96B5A)

    val pastels = listOf(Lilac, Copper, Mint, Sky, Butter, Rose)
    fun pastel(i: Int) = pastels[((i % pastels.size) + pastels.size) % pastels.size]
}

val Mono = FontFamily(
    Font(R.font.jetbrains_mono_400, FontWeight.Normal),
    Font(R.font.jetbrains_mono_500, FontWeight.Medium),
    Font(R.font.jetbrains_mono_600, FontWeight.SemiBold),
)

val Grotesk = FontFamily(
    Font(R.font.space_grotesk_400, FontWeight.Normal),
    Font(R.font.space_grotesk_500, FontWeight.Medium),
    Font(R.font.space_grotesk_600, FontWeight.SemiBold),
    Font(R.font.space_grotesk_700, FontWeight.Bold),
)

val Fraunces = Grotesk
val Manrope = Grotesk

private val typography = Typography(
    displayLarge = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.Medium, fontSize = 56.sp, lineHeight = 56.sp, letterSpacing = (-2).sp),
    displayMedium = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.Medium, fontSize = 38.sp, lineHeight = 40.sp, letterSpacing = (-1.2).sp),
    headlineLarge = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.Medium, fontSize = 28.sp, lineHeight = 32.sp, letterSpacing = (-0.6).sp),
    headlineMedium = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.Medium, fontSize = 23.sp, lineHeight = 28.sp, letterSpacing = (-0.4).sp),
    headlineSmall = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.Medium, fontSize = 19.sp, lineHeight = 24.sp),
    titleLarge = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.Medium, fontSize = 17.sp, lineHeight = 22.sp),
    titleMedium = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 20.sp),
    titleSmall = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.4.sp),
    bodyLarge = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.Normal, fontSize = 17.sp, lineHeight = 26.sp),
    bodyMedium = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp),
    bodySmall = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.6.sp),
    labelMedium = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Normal, fontSize = 11.5.sp, lineHeight = 16.sp, letterSpacing = 0.5.sp),
    labelSmall = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Normal, fontSize = 10.sp, lineHeight = 14.sp, letterSpacing = 1.2.sp),
)

private val scheme = darkColorScheme(
    primary = M.Copper, onPrimary = M.Ink, primaryContainer = M.Surface2, onPrimaryContainer = M.Text,
    secondary = M.Lilac, onSecondary = M.Ink, tertiary = M.Mint, onTertiary = M.Ink,
    background = M.Ink, onBackground = M.Text, surface = M.Night, onSurface = M.Text,
    surfaceVariant = M.Surface, onSurfaceVariant = M.Muted, surfaceContainer = M.Surface,
    surfaceContainerHigh = M.Surface2, surfaceContainerHighest = M.Surface2, surfaceContainerLow = M.Night,
    outline = M.Line, outlineVariant = M.Line, error = M.Coral,
)

@Composable
fun MurmureTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = typography) {
        CompositionLocalProvider(LocalContentColor provides M.Text, content = content)
    }
}
