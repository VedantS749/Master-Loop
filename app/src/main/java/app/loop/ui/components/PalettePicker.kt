package app.loop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.loop.model.Palette
import app.loop.ui.theme.dynamicColorSupported
import app.loop.ui.theme.isDark
import app.loop.ui.theme.paletteScheme

/** Row of palette presets, each previewed in its own colours for the current light or dark mode. */
@Composable
fun PalettePicker(selected: Palette, onSelect: (Palette) -> Unit) {
    val here = MaterialTheme.colorScheme
    val dark = here.isDark
    val black = here.background == androidx.compose.ui.graphics.Color.Black
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Palette.entries.filter { it != Palette.SYSTEM || dynamicColorSupported }.forEach { p ->
            val sch = paletteScheme(p, dark, black)
            val on = p == selected
            Column(
                Modifier.width(104.dp).clip(RoundedCornerShape(18.dp)).background(sch.background)
                    .border(if (on) 2.5.dp else 1.dp, if (on) here.onSurface else here.outlineVariant, RoundedCornerShape(18.dp))
                    .clickable { onSelect(p) }.padding(12.dp),
            ) {
                Box(Modifier.height(34.dp)) {
                    listOf(sch.primary, sch.secondary, sch.tertiary).forEachIndexed { i, col ->
                        Box(Modifier.offset(x = (i * 22).dp).size(34.dp).clip(CircleShape).background(col).border(2.dp, sch.background, CircleShape))
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(p.label, style = MaterialTheme.typography.labelLarge, color = sch.onSurface, modifier = Modifier.weight(1f))
                    if (on) Icon(Icons.Rounded.Check, "Selected", tint = sch.primary, modifier = Modifier.size(16.dp))
                }
                Text(p.blurb, style = MaterialTheme.typography.labelSmall, color = sch.onSurfaceVariant, maxLines = 2, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}
