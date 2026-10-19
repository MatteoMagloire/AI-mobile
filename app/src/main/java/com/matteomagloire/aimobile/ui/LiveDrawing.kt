package com.matteomagloire.aimobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.matteomagloire.aimobile.brain.Caption
import com.matteomagloire.aimobile.brain.LiveBrain
import com.matteomagloire.aimobile.ui.theme.Axo

// Le Cerveau en direct, dessiné de la même façon dans le graphe et dans la conversation. [unit] : un dp en px.

internal val EdgeColor = Color(0xFF3A3F4B)
internal val NodeLight = Color(0xFFE3B488)
internal val Sleeping = Axo.Frost.copy(alpha = 0.55f)

/** L'éclat d'une note trouvée ou lue. */
internal fun DrawScope.drawLight(center: Offset, radius: Float, light: Float) {
    drawCircle(Axo.Amber.copy(alpha = 0.2f * light), radius * (1.8f + 0.7f * light), center)
}

/** Le battement d'une note qu'Axo lit : un halo qui respire, un anneau qui s'élargit et s'efface. */
internal fun DrawScope.drawPulse(center: Offset, radius: Float, pulse: Float, unit: Float) {
    drawCircle(Axo.Amber.copy(alpha = 0.10f + 0.14f * pulse), radius * (1.7f + 0.5f * pulse), center)
    drawCircle(Axo.AmberLight.copy(alpha = 0.1f + 0.5f * (1f - pulse)), radius * (1.3f + 1.4f * pulse), center, style = Stroke(1.4f * unit))
}

/** Axo écrit dans la note : un arc qui tourne autour d'elle. */
internal fun DrawScope.drawWriting(center: Offset, radius: Float, clock: Long, grow: Float, unit: Float) {
    val r = radius + 4f * unit
    val start = (clock % 900L) / 900f * 360f
    drawArc(
        Axo.AmberLight.copy(alpha = grow.coerceIn(0f, 1f)),
        start,
        110f,
        useCenter = false,
        topLeft = Offset(center.x - r, center.y - r),
        size = Size(2 * r, 2 * r),
        style = Stroke(2f * unit, cap = StrokeCap.Round),
    )
}

/** La boule qui voyage sur un lien. */
internal fun DrawScope.drawBall(at: Offset, unit: Float) {
    drawCircle(Axo.Amber.copy(alpha = 0.25f), 9f * unit, at)
    drawCircle(Axo.AmberLight, 3.6f * unit, at)
}

/** La couleur d'une note : [base], éclairée par [light], ou qui passe du givre à sa couleur d'éveil ([wake]). */
internal fun liveColor(base: Color, light: Float, wake: Float?): Color {
    val c = if (wake != null) lerp(Sleeping, if (base == Sleeping) Axo.Amber else base, wake) else base
    return if (light > 0f) lerp(c, Axo.AmberLight, 0.5f * light) else c
}

/** « Axo lit « Pixel » », avec le titre des notes ([title] : null si la note n'existe plus). */
internal fun captionText(caption: Caption, title: (String) -> String?): String {
    val name = caption.noteId?.let(title)?.let { "« $it »" }
    return when (caption.kind) {
        Caption.Kind.THINKING -> "Axo pense à ton message…"
        Caption.Kind.ANSWERING -> "Axo te répond…"
        Caption.Kind.NOTING -> "Axo range ses notes…"
        Caption.Kind.SEARCHING -> "Axo cherche dans sa mémoire…"
        Caption.Kind.READING -> if (name != null) "Axo lit $name" else "Axo lit une note"
        Caption.Kind.FOLLOWING -> if (name != null) "Axo suit le lien vers $name" else "Axo suit un lien"
        Caption.Kind.WRITING -> if (name != null) "Axo écrit dans $name" else "Axo écrit"
        Caption.Kind.CREATING -> if (name != null) "Axo crée la note $name" else "Axo crée une note"
        Caption.Kind.WAKING -> if (name != null) "Axo réveille $name" else "Axo réveille une note"
    }
}

/** Ce qu'Axo fait en ce moment, en une ligne (« Axo lit « Pixel » »). Ne se recompose qu'au changement. */
@Composable
internal fun LiveCaption(live: LiveBrain, clock: () -> Long, title: (String) -> String?, modifier: Modifier = Modifier) {
    val caption by remember(live) { derivedStateOf { live.caption(clock()) } }
    val c = caption ?: return
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(Axo.Amber))
        Text(captionText(c, title), style = MaterialTheme.typography.labelMedium, color = Axo.Linen, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
