package app.murmure.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import app.murmure.ui.theme.M

/** La tuile Brainmeat : obsidienne, filet, numéro mono, titre en capitales. */
@Composable
fun Tile(
    index: String?,
    title: String,
    modifier: Modifier = Modifier,
    trailing: String? = null,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    val interaction = remember { MutableInteractionSource() }
    val view = LocalView.current
    Column(
        modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.pressScale(interaction, 0.985f) else Modifier)
            .clip(shape)
            .background(M.Surface)
            .border(1.dp, M.Line, shape)
            .then(if (onClick != null) Modifier.clickable(interactionSource = interaction, indication = null) { Feedback.tap(view); onClick() } else Modifier)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (index != null) { Text(index, style = MaterialTheme.typography.labelSmall, color = M.Copper); Spacer(Modifier.width(8.dp)) }
            Text(title.uppercase(), style = MaterialTheme.typography.labelSmall, color = M.Muted, modifier = Modifier.weight(1f))
            if (trailing != null) Text(trailing.uppercase(), style = MaterialTheme.typography.labelSmall, color = M.Faint)
        }
        Spacer(Modifier.height(12.dp))
        content()
    }
}
