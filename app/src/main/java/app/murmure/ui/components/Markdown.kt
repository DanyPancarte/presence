package app.murmure.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.murmure.ui.theme.M

/** Rendu Markdown léger : titres, listes, gras, italique et [[liens]] cliquables. */
@Composable
fun MarkdownText(md: String, modifier: Modifier = Modifier, onLink: (String) -> Unit = {}) {
    val lines = md.replace("\r", "").split("\n")
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        lines.forEach { raw ->
            val line = raw.trimEnd()
            when {
                line.isBlank() -> Spacer(Modifier.padding(2.dp))
                line.startsWith("### ") -> Text(inline(line.drop(4), onLink), style = MaterialTheme.typography.titleMedium, color = M.Text)
                line.startsWith("## ") -> Text(inline(line.drop(3), onLink), style = MaterialTheme.typography.headlineSmall, color = M.Text, modifier = Modifier.padding(top = 8.dp))
                line.startsWith("# ") -> Text(inline(line.drop(2), onLink), style = MaterialTheme.typography.headlineMedium, color = M.Text, modifier = Modifier.padding(top = 8.dp))
                Regex("^\\s*([-*•]|\\d+\\.)\\s+").containsMatchIn(line) -> {
                    val m = Regex("^(\\s*)([-*•]|\\d+\\.)\\s+").find(line)!!
                    val indent = (m.groupValues[1].length / 2).coerceAtMost(3)
                    val bullet = if (m.groupValues[2].endsWith(".")) m.groupValues[2] else "•"
                    Row(Modifier.padding(start = (indent * 14).dp)) {
                        Text(bullet, color = M.Lilac, style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.width(10.dp))
                        Text(inline(line.substring(m.range.last + 1), onLink), style = MaterialTheme.typography.bodyLarge, color = M.Text)
                    }
                }
                line.startsWith("> ") -> Text(inline(line.drop(2), onLink), style = MaterialTheme.typography.bodyLarge.copy(fontStyle = FontStyle.Italic), color = M.Muted)
                else -> Text(inline(line, onLink), style = MaterialTheme.typography.bodyLarge, color = M.Text)
            }
        }
    }
}

private val token = Regex("\\[\\[([^\\]]+)]]|\\*\\*([^*]+)\\*\\*|\\*([^*]+)\\*|_([^_]+)_")

fun inline(s: String, onLink: (String) -> Unit, linkColor: Color = M.Lilac): AnnotatedString = buildAnnotatedString {
    var i = 0
    token.findAll(s).forEach { m ->
        append(s.substring(i, m.range.first))
        when {
            m.groupValues[1].isNotEmpty() -> {
                val target = m.groupValues[1]
                withLink(
                    LinkAnnotation.Clickable(
                        tag = target,
                        styles = TextLinkStyles(SpanStyle(color = linkColor, fontWeight = FontWeight.SemiBold, background = linkColor.copy(alpha = 0.12f))),
                    ) { onLink(target) }
                ) { append(target) }
            }
            m.groupValues[2].isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(m.groupValues[2]) }
            m.groupValues[3].isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(m.groupValues[3]) }
            m.groupValues[4].isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(m.groupValues[4]) }
        }
        i = m.range.last + 1
    }
    append(s.substring(i))
}
