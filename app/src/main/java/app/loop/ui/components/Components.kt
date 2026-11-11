package app.loop.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.loop.model.Category
import app.loop.model.MetricDef
import app.loop.ui.theme.color

@Composable
fun LoopCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    accent: Color? = null,
    padding: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(22.dp)
    val base = modifier
        .fillMaxWidth()
        .clip(shape)
        .background(MaterialTheme.colorScheme.surfaceContainer)
        .border(BorderStroke(1.dp, (accent ?: MaterialTheme.colorScheme.outlineVariant).copy(alpha = if (accent != null) 0.45f else 0.7f)), shape)
    val m = if (onClick != null) base.clickable(onClick = onClick) else base
    Column(m.padding(padding), content = content)
}

@Composable
fun IconBadge(icon: ImageVector, color: Color, size: Dp = 40.dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size).clip(CircleShape).background(color.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = color, modifier = Modifier.size(size * 0.55f))
    }
}

@Composable
fun SectionTitle(title: String, modifier: Modifier = Modifier, subtitle: String? = null, trailing: (@Composable RowScope.() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing?.invoke(this)
    }
}

@Composable
fun CategoryLabel(category: Category, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(category.color()))
        Spacer(Modifier.width(8.dp))
        Text(category.title.uppercase(), style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.2.sp), color = category.color())
    }
}

@Composable
fun Pill(text: String, color: Color, modifier: Modifier = Modifier, filled: Boolean = false) {
    Box(
        modifier.clip(RoundedCornerShape(50)).background(if (filled) color else color.copy(alpha = 0.15f)).padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = if (filled) Color.White else color, fontWeight = FontWeight.SemiBold)
    }
}

/** Segmented selector used for 7D / 30D / 90D etc. */
@Composable
fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, accent: Color, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        options.forEachIndexed { i, label ->
            val sel = i == selected
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(11.dp))
                    .background(if (sel) accent else Color.Transparent)
                    .clickable(remember { MutableInteractionSource() }, null) { onSelect(i) }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = MaterialTheme.typography.labelLarge, color = if (sel) Color.White else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, icon: ImageVector? = null, color: Color = MaterialTheme.colorScheme.primary) {
    Button(
        onClick, modifier.fillMaxWidth().height(52.dp), enabled = enabled, shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = Color.White, disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh, disabledContentColor = MaterialTheme.colorScheme.outline),
    ) {
        if (icon != null) { Icon(icon, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)) }
        Text(text, style = MaterialTheme.typography.labelLarge.copy(fontSize = 16.sp))
    }
}

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, color: Color = MaterialTheme.colorScheme.onSurface) {
    OutlinedButton(
        onClick, modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), colors = ButtonDefaults.outlinedButtonColors(contentColor = color),
    ) {
        if (icon != null) { Icon(icon, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)) }
        Text(text, style = MaterialTheme.typography.labelLarge.copy(fontSize = 15.sp))
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, body: String, color: Color, actionLabel: String? = null, onAction: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(vertical = 24.dp, horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        IconBadge(icon, color, size = 64.dp)
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(18.dp))
            PrimaryButton(actionLabel, onAction, color = color)
        }
    }
}

/** Header row for secondary screens: back arrow + title. */
@Composable
fun DetailHeader(title: String, onBack: () -> Unit, modifier: Modifier = Modifier, subtitle: String? = null, trailing: (@Composable RowScope.() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = MaterialTheme.colorScheme.onSurface) }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing?.invoke(this)
    }
}

/** A tappable row with chevron, used in settings lists. */
@Composable
fun NavRow(icon: ImageVector, title: String, subtitle: String? = null, color: Color = MaterialTheme.colorScheme.primary, onClick: () -> Unit, trailing: String? = null) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBadge(icon, color, size = 38.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (trailing != null) Text(trailing, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.outline)
    }
}

@Composable
fun Divider() { Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))) }

/** A small "i" button that opens the metric explainer, so no number is ever a mystery. */
@Composable
fun InfoButton(onClick: () -> Unit, modifier: Modifier = Modifier, tint: Color = MaterialTheme.colorScheme.outline) {
    IconButton(onClick, modifier.size(32.dp)) { Icon(Icons.Rounded.Info, "What is this?", tint = tint, modifier = Modifier.size(18.dp)) }
}

/** Bottom sheet explaining one metric in plain language. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MetricInfoSheet(def: MetricDef, onDismiss: () -> Unit, female: Boolean = false) {
    val color = def.category.color()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 22.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(def.category.icon(), color, 44.dp)
                Spacer(Modifier.width(12.dp))
                Column {
                    CategoryLabel(def.category)
                    Text(def.title, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface)
                }
            }
            InfoBlock("What it is", def.what, color)
            InfoBlock("Why it matters", def.why + (if (female) app.loop.model.Metrics.femaleNote(def.id)?.let { " $it" }.orEmpty() else ""), color)
            InfoBlock("What's good", def.good, color)
            InfoBlock("How Loop works it out", app.loop.model.Glossary.method(def.id), color)
            val w = app.loop.model.Metrics.window(def.id)
            InfoBlock("Part of the day", w.title + ". " + w.blurb + ".", color)
            GlossaryTerms(app.loop.model.Glossary.termsFor(def.id), color)
            Text(
                "Loop's numbers are estimates for general wellness, not medical advice.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@Composable
private fun InfoBlock(label: String, text: String, color: Color) {
    Column {
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.sp), color = color)
        Spacer(Modifier.height(3.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** The glossary terms a metric's explanation relies on. */
@Composable
fun GlossaryTerms(terms: List<app.loop.model.Term>, color: Color) {
    if (terms.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("GLOSSARY", style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.sp), color = color)
        terms.forEach { t ->
            Column {
                Text(t.name, style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.sp), color = MaterialTheme.colorScheme.onSurface)
                Text(t.meaning, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Glossary sheet for a live tile that is not a stored metric (pNN50, effort, trend and so on). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveEntrySheet(entry: app.loop.model.LiveEntry, color: Color, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 22.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(entry.title, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface)
            InfoBlock("What it is", entry.what, color)
            InfoBlock("How Loop works it out", entry.how, color)
            GlossaryTerms(entry.terms.mapNotNull { app.loop.model.Glossary.term(it) }, color)
            Text("Live numbers are estimates for general wellness, not medical advice.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        }
    }
}

/** Dismissable inline banner. */
@Composable
fun Banner(text: String, color: Color, actionLabel: String? = null, onAction: (() -> Unit)? = null, onClose: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Brush.horizontalGradient(listOf(color.copy(alpha = 0.20f), color.copy(alpha = 0.08f))))
            .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(16.dp)).padding(start = 14.dp, top = 8.dp, bottom = 8.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
        if (actionLabel != null && onAction != null) {
            androidx.compose.material3.TextButton(onAction) { Text(actionLabel, color = color, fontWeight = FontWeight.Bold) }
        }
        if (onClose != null) IconButton(onClose, Modifier.size(32.dp)) { Icon(Icons.Rounded.Close, "Dismiss", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp)) }
    }
}
