package app.murmure.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.murmure.R

/** Palette « Nuit douce » : encre profonde + pastels lumineux. */
object M {
    val Ink = Color(0xFF14121F)
    val Night = Color(0xFF1B1829)
    val Surface = Color(0xFF221E33)
    val Surface2 = Color(0xFF2C2742)
    val Line = Color(0xFF3A3452)
    val Text = Color(0xFFF4F1FF)
    val Muted = Color(0xFF9E97BD)
    val Faint = Color(0xFF6B6488)

    val Lilac = Color(0xFFC9B6FF)
    val Peach = Color(0xFFFFB8A0)
    val Mint = Color(0xFFA8E6CF)
    val Sky = Color(0xFF9FD3FF)
    val Butter = Color(0xFFFFE59A)
    val Rose = Color(0xFFFFB3D1)
    val Coral = Color(0xFFFF8F8F)

    val pastels = listOf(Lilac, Peach, Mint, Sky, Butter, Rose)
    fun pastel(i: Int) = pastels[((i % pastels.size) + pastels.size) % pastels.size]
}

val Fraunces = FontFamily(
    Font(R.font.fraunces_500, FontWeight.Medium),
    Font(R.font.fraunces_600, FontWeight.SemiBold),
    Font(R.font.fraunces_700, FontWeight.Bold),
)

val Manrope = FontFamily(
    Font(R.font.manrope_400, FontWeight.Normal),
    Font(R.font.manrope_500, FontWeight.Medium),
    Font(R.font.manrope_600, FontWeight.SemiBold),
    Font(R.font.manrope_700, FontWeight.Bold),
)

private val typography = Typography(
    displayLarge = TextStyle(fontFamily = Fraunces, fontWeight = FontWeight.SemiBold, fontSize = 44.sp, lineHeight = 48.sp, letterSpacing = (-1).sp),
    displayMedium = TextStyle(fontFamily = Fraunces, fontWeight = FontWeight.SemiBold, fontSize = 34.sp, lineHeight = 40.sp, letterSpacing = (-0.5).sp),
    headlineLarge = TextStyle(fontFamily = Fraunces, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 34.sp),
    headlineMedium = TextStyle(fontFamily = Fraunces, fontWeight = FontWeight.SemiBold, fontSize = 23.sp, lineHeight = 29.sp),
    headlineSmall = TextStyle(fontFamily = Fraunces, fontWeight = FontWeight.Medium, fontSize = 20.sp, lineHeight = 26.sp),
    titleLarge = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.Bold, fontSize = 19.sp, lineHeight = 24.sp),
    titleMedium = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.Normal, fontSize = 17.sp, lineHeight = 26.sp),
    bodyMedium = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp),
    bodySmall = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.Bold, fontSize = 15.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.6.sp),
)

private val scheme = darkColorScheme(
    primary = M.Lilac,
    onPrimary = M.Ink,
    primaryContainer = M.Surface2,
    onPrimaryContainer = M.Text,
    secondary = M.Peach,
    onSecondary = M.Ink,
    tertiary = M.Mint,
    onTertiary = M.Ink,
    background = M.Ink,
    onBackground = M.Text,
    surface = M.Night,
    onSurface = M.Text,
    surfaceVariant = M.Surface,
    onSurfaceVariant = M.Muted,
    surfaceContainer = M.Surface,
    surfaceContainerHigh = M.Surface2,
    surfaceContainerHighest = M.Surface2,
    surfaceContainerLow = M.Night,
    outline = M.Line,
    outlineVariant = M.Line,
    error = M.Coral,
)

@Composable
fun MurmureTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = typography) {
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.material3.LocalContentColor provides M.Text,
            content = content,
        )
    }
}
