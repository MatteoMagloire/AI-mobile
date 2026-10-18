package com.matteomagloire.aimobile.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.matteomagloire.aimobile.R

/** Direction artistique « Une lueur dans la poche » : sombre et calme, l'ambre pour ce qui est vivant. */
object Axo {
    val Ink = Color(0xFF0F1115) // fond
    val Card = Color(0xFF171A21) // cartes, champs de saisie
    val Slate = Color(0xFF1F232C) // surfaces, bulles de l'utilisateur
    val Line = Color(0xFF2A2F3A) // bordures
    val Linen = Color(0xFFECE7DF) // texte
    val TextSecondary = Color(0xFFB7B2AA)
    val Muted = Color(0xFF9AA0AB)
    val Faint = Color(0xFF6F7682)
    val Amber = Color(0xFFF0A35E) // l'IA, ce qui est actif
    val AmberLight = Color(0xFFF7C998)
    val AmberSoft = Color(0xFFC9A27C)
    val OnAmber = Color(0xFF1A1208)
    val Ember = Color(0xFF2B2118) // bulles d'Axo
    val EmberBorder = Color(0xFF3E2D1F)
    val AmberBorder = Color(0xFF5A3E26)
    val Frost = Color(0xFF7C8BA1) // mémoire froide
    val Blush = Color(0xFFE4677A) // erreurs, joues d'Axo
    val Good = Color(0xFF8BC79A) // cible atteinte
}

private val Bricolage = FontFamily(
    Font(R.font.bricolage_semibold, FontWeight.SemiBold),
    Font(R.font.bricolage_bold, FontWeight.Bold),
)

private val Figtree = FontFamily(
    Font(R.font.figtree_regular, FontWeight.Normal),
    Font(R.font.figtree_medium, FontWeight.Medium),
    Font(R.font.figtree_semibold, FontWeight.SemiBold),
)

val JetBrainsMono = FontFamily(
    Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(R.font.jetbrains_mono_medium, FontWeight.Medium),
)

/** Mesures techniques, liens et étiquettes. */
val MonoStyle = TextStyle(fontFamily = JetBrainsMono, fontSize = 13.sp, lineHeight = 18.sp)

/** Petits intertitres en capitales (« AUJOURD'HUI », « MESURES »). */
val OverlineStyle = TextStyle(
    fontFamily = JetBrainsMono,
    fontSize = 11.sp,
    letterSpacing = 0.1.em,
    color = Axo.Faint,
)

private val colors = darkColorScheme(
    primary = Axo.Amber,
    onPrimary = Axo.OnAmber,
    primaryContainer = Axo.Ember,
    onPrimaryContainer = Axo.Amber,
    secondary = Axo.Frost,
    onSecondary = Axo.Ink,
    secondaryContainer = Axo.Slate,
    onSecondaryContainer = Axo.Linen,
    tertiary = Axo.AmberLight,
    onTertiary = Axo.OnAmber,
    background = Axo.Ink,
    onBackground = Axo.Linen,
    surface = Axo.Ink,
    onSurface = Axo.Linen,
    surfaceVariant = Axo.Slate,
    onSurfaceVariant = Axo.TextSecondary,
    surfaceTint = Axo.Amber,
    surfaceContainerLowest = Axo.Ink,
    surfaceContainerLow = Axo.Card,
    surfaceContainer = Axo.Card,
    surfaceContainerHigh = Axo.Slate,
    surfaceContainerHighest = Axo.Line,
    outline = Axo.Line,
    outlineVariant = Axo.Line,
    error = Axo.Blush,
    onError = Axo.OnAmber,
)

private val typography = Typography(
    displaySmall = TextStyle(fontFamily = Bricolage, fontWeight = FontWeight.Bold, fontSize = 36.sp, lineHeight = 40.sp, letterSpacing = (-0.02).em),
    headlineMedium = TextStyle(fontFamily = Bricolage, fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 32.sp, letterSpacing = (-0.01).em),
    headlineSmall = TextStyle(fontFamily = Bricolage, fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 28.sp),
    titleLarge = TextStyle(fontFamily = Bricolage, fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 24.sp),
    titleMedium = TextStyle(fontFamily = Bricolage, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 24.sp),
    titleSmall = TextStyle(fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = Figtree, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = Figtree, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = Figtree, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = Figtree, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontFamily = Figtree, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 16.sp),
)

/** Thème sombre toujours : il repose les yeux et économise la batterie sur l'écran OLED du S21. */
@Composable
fun AiMobileTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, typography = typography, content = content)
}
