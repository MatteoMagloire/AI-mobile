package com.matteomagloire.aimobile.ui

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.matteomagloire.aimobile.ui.theme.Axo
import com.matteomagloire.aimobile.ui.theme.MonoStyle
import com.matteomagloire.aimobile.ui.theme.OverlineStyle
import java.util.Locale

/** Barre du haut des écrans secondaires : retour, titre. */
@Composable
fun AxoTopBar(title: String, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) { Icon(AxoIcons.Back, contentDescription = "Retour", tint = Axo.TextSecondary) }
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        actions()
    }
}

/** Carte de la DA : fond légèrement plus clair, bordure fine, coins de 20. [asleep] : une note en veille, plus sombre. */
@Composable
fun AxoCard(
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    asleep: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val background = when {
        highlighted -> Axo.Ember
        asleep -> Axo.Night
        else -> Axo.Card
    }
    val border = when {
        highlighted -> Axo.AmberBorder
        asleep -> Axo.NightBorder
        else -> Axo.Line
    }
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(background)
            .border(BorderStroke(1.dp, border), RoundedCornerShape(20.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(Locale.FRENCH), style = OverlineStyle, modifier = modifier.padding(start = 4.dp, top = 8.dp))
}

/** Bouton principal : ambre plein. */
@Composable
fun AxoButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    Row(
        modifier
            .defaultMinSize(minHeight = 48.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (enabled) Axo.Amber else Axo.Slate)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        val color = if (enabled) Axo.OnAmber else Axo.Faint
        if (icon != null) Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        Text(text, style = MaterialTheme.typography.labelLarge, color = color, textAlign = TextAlign.Center)
    }
}

/** Bouton secondaire : braise et bordure ambre. */
@Composable
fun AxoOutlineButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    color: Color = Axo.Amber,
) {
    Row(
        modifier
            .defaultMinSize(minHeight = 44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (enabled) Axo.Ember else Color.Transparent)
            .border(BorderStroke(1.dp, if (enabled) Axo.AmberBorder else Axo.Line), RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
    ) {
        val tint = if (enabled) color else Axo.Faint
        if (icon != null) Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
        Text(text, style = MaterialTheme.typography.labelLarge, color = tint)
    }
}

/** Lien discret (« Rétablir », « Recommencer »). */
@Composable
fun AxoTextButton(text: String, onClick: () -> Unit, color: Color = Axo.Amber, enabled: Boolean = true) {
    Box(
        Modifier
            .defaultMinSize(minHeight = 48.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = if (enabled) color else Axo.Faint)
    }
}

/** Ligne de réglage qui ouvre un écran. */
@Composable
fun EntryRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Axo.Ember)
                .border(BorderStroke(1.dp, Axo.AmberBorder), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, contentDescription = null, tint = Axo.Amber, modifier = Modifier.size(20.dp)) }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Axo.Muted)
        }
        Icon(AxoIcons.Chevron, contentDescription = null, tint = Axo.Faint, modifier = Modifier.size(18.dp))
    }
}

/** Champ de saisie de la DA : fond sombre, bordure fine, curseur ambre. */
@Composable
fun AxoTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    singleLine: Boolean = false,
    textStyle: TextStyle = MaterialTheme.typography.bodyMedium,
    background: Color = Axo.Ink,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = singleLine,
        textStyle = textStyle.copy(color = Axo.Linen),
        cursorBrush = SolidColor(Axo.Amber),
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(background)
            .border(BorderStroke(1.dp, Axo.Line), RoundedCornerShape(14.dp))
            .padding(12.dp),
        decorationBox = { field ->
            Box {
                if (value.isEmpty() && placeholder.isNotEmpty()) Text(placeholder, style = textStyle, color = Axo.Faint)
                field()
            }
        },
    )
}

@Composable
fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit, detail: String? = null) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onChange(!checked) }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = Axo.Muted)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Axo.OnAmber,
                checkedTrackColor = Axo.Amber,
                uncheckedThumbColor = Axo.Muted,
                uncheckedTrackColor = Axo.Slate,
                uncheckedBorderColor = Axo.Line,
            ),
        )
    }
}

/** Barre d'avancement ambre ; sans valeur, elle tourne en continu. */
@Composable
fun AmberProgress(progress: Float?, modifier: Modifier = Modifier) {
    if (progress == null) {
        LinearProgressIndicator(modifier.fillMaxWidth().height(3.dp), color = Axo.Amber, trackColor = Axo.Line)
    } else {
        LinearProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            modifier = modifier.fillMaxWidth().height(3.dp),
            color = Axo.Amber,
            trackColor = Axo.Line,
            drawStopIndicator = {},
        )
    }
}

/** Choix parmi quelques options (onglets, nombre de cœurs). */
@Composable
fun ChoiceChip(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .heightIn(min = 40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) Axo.Ember else Axo.Card)
            .border(BorderStroke(1.dp, if (selected) Axo.AmberBorder else Axo.Line), RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = if (selected) Axo.Amber else Axo.TextSecondary)
    }
}

/** Une mesure : nom, valeur (police mono) et, si elle a une cible, atteinte ou pas. */
@Composable
fun MetricRow(label: String, value: String, target: String? = null, ok: Boolean? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            if (target != null) Text(target, style = MaterialTheme.typography.bodySmall, color = Axo.Muted)
        }
        Text(value, style = MonoStyle, color = if (ok == false) Axo.Blush else Axo.Linen)
        if (ok != null) {
            Spacer(Modifier.width(8.dp))
            Icon(
                if (ok) AxoIcons.Check else AxoIcons.Close,
                contentDescription = if (ok) "Cible atteinte" else "Cible manquée",
                tint = if (ok) Axo.Good else Axo.Blush,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** Petite pastille (« Utilisé par Axo », « Sur le téléphone »). */
@Composable
fun Badge(text: String, active: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = if (active) Axo.Amber else Axo.Muted,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (active) Axo.Ember else Axo.Slate)
            .border(BorderStroke(1.dp, if (active) Axo.AmberBorder else Axo.Line), RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

// --- Mise en forme des nombres, à la française ---

fun seconds(ms: Long): String = "%.1f s".format(Locale.FRANCE, ms / 1000.0)

fun tokensPerSecond(tps: Double): String = "%.1f tok/s".format(Locale.FRANCE, tps)

fun gigabytes(bytes: Long): String = "%.1f Go".format(Locale.FRANCE, bytes / 1e9)

fun megabytes(bytes: Long): String = "%.0f Mo".format(Locale.FRANCE, bytes / 1e6)

fun celsius(c: Float): String = if (c.isNaN()) "?" else "%.1f °C".format(Locale.FRANCE, c)
