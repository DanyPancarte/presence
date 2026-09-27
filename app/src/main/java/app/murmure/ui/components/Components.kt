package app.murmure.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material.icons.Icons
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.ui.platform.LocalView
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.murmure.ui.theme.M

@Composable
fun Card(
    modifier: Modifier = Modifier,
    tint: Color? = null,
    onClick: (() -> Unit)? = null,
    padding: Dp = 18.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(M.Surface)
            .border(1.dp, (tint ?: M.Line).copy(alpha = if (tint != null) 0.45f else 1f), shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(padding),
        content = content,
    )
}

@Composable
fun Tag(
    text: String,
    color: Color = M.Lilac,
    modifier: Modifier = Modifier,
    selected: Boolean = true,
    leading: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(7.dp)
    val interaction = remember { MutableInteractionSource() }
    val view = LocalView.current
    Row(
        modifier
            .then(if (onClick != null) Modifier.pressScale(interaction, 0.93f) else Modifier)
            .clip(shape)
            .background(if (selected) M.Surface2 else Color.Transparent)
            .border(1.dp, if (selected) color.copy(alpha = 0.7f) else M.Line, shape)
            .then(if (onClick != null) Modifier.clickable(interactionSource = interaction, indication = null) { Feedback.tap(view); onClick() } else Modifier)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) { Text(leading, style = MaterialTheme.typography.labelSmall, color = if (selected) M.Copper else M.Faint); Spacer(Modifier.width(7.dp)) }
        Text(
            text, style = MaterialTheme.typography.labelMedium,
            color = if (selected) M.Text else M.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        if (trailing != null) { Spacer(Modifier.width(4.dp)); trailing() }
    }
}

@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier, color: Color = M.Muted) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = color, modifier = modifier)
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, action: (@Composable RowScope.() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(top = 8.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        action?.invoke(this)
    }
}

@Composable
fun TopBar(
    title: String,
    onBack: (() -> Unit)?,
    backIcon: ImageVector = Icons.AutoMirrored.Rounded.ArrowBack,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp).height(52.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) IconAction(backIcon, "Retour", onClick = onBack)
        else Spacer(Modifier.width(12.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        actions()
    }
}

@Composable
fun PrimaryButton(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = M.Lilac,
    enabled: Boolean = true,
    leading: ImageVector? = null,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(10.dp)
    val interaction = remember { MutableInteractionSource() }
    val view = LocalView.current
    Row(
        modifier
            .pressScale(interaction)
            .clip(shape)
            .background(if (enabled) (if (color == M.Lilac) M.Text else color) else M.Surface2)
            .clickable(enabled = enabled, interactionSource = interaction, indication = null) { Feedback.tap(view); Feedback.play(Feedback.Sound.TAP, 0.45f); onClick() }
            .padding(horizontal = 22.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) { Icon(leading, null, tint = M.Ink, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)) }
        Text(text.uppercase(), style = MaterialTheme.typography.labelLarge, color = if (enabled) M.Ink else M.Faint)
    }
}

@Composable
fun GhostButton(text: String, modifier: Modifier = Modifier, color: Color = M.Text, leading: ImageVector? = null, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    val interaction = remember { MutableInteractionSource() }
    val view = LocalView.current
    Row(
        modifier.pressScale(interaction).clip(shape).border(1.dp, M.Line, shape)
            .clickable(interactionSource = interaction, indication = null) { Feedback.tap(view); onClick() }
            .padding(horizontal = 20.dp, vertical = 15.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) { Icon(leading, null, tint = color, modifier = Modifier.size(19.dp)); Spacer(Modifier.width(8.dp)) }
        Text(text.uppercase(), style = MaterialTheme.typography.labelLarge, color = color)
    }
}

@Composable
fun Dot(color: Color, size: Dp = 8.dp) = Box(Modifier.size(size).clip(CircleShape).background(color))

@Composable
fun PulsingDot(color: Color, size: Dp = 9.dp) {
    val t = rememberInfiniteTransition(label = "dot")
    val a by t.animateFloat(0.35f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "a")
    Box(Modifier.size(size).clip(CircleShape).background(color.copy(alpha = a)))
}

@Composable
fun EmptyState(emoji: String, title: String, body: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        DotRing(fraction = 0f, size = 44.dp, dots = 20)
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall, color = M.Text)
        Spacer(Modifier.height(6.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = M.Muted, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
fun Banner(text: String, color: Color = M.Butter, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(M.Surface)
            .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(8.dp)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Dot(color)
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = M.Text)
    }
}

@Composable
fun StatTile(value: String, label: String, color: Color, modifier: Modifier = Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(12.dp)).background(M.Surface)
            .border(1.dp, M.Line, RoundedCornerShape(12.dp)).padding(14.dp)
    ) {
        Text(value, style = MaterialTheme.typography.headlineMedium, color = M.Text, fontWeight = FontWeight.Medium)
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = M.Faint)
    }
}

/** Le mood est un niveau, pas un visage : cinq crans. */
val moodFaces = listOf("1", "2", "3", "4", "5")
val moodLabels = listOf("Dur", "Bof", "Correct", "Bien", "Super")


/** Bouton icône avec infobulle (appui long) et retour haptique. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IconAction(icon: ImageVector, label: String, tint: Color = M.Muted, onClick: () -> Unit) {
    val view = LocalView.current
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip(containerColor = M.Surface2, contentColor = M.Text) { Text(label, style = MaterialTheme.typography.labelMedium) } },
        state = rememberTooltipState(),
    ) {
        IconButton(onClick = { Feedback.tap(view); onClick() }) { Icon(icon, label, tint = tint) }
    }
}
