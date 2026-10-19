package com.matteomagloire.aimobile.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp
import com.matteomagloire.aimobile.ui.theme.Axo

/** Icônes au trait de la DA : 1,8 px, bouts arrondis. La couleur vient de `tint`. */
object AxoIcons {
    private fun circle(cx: Float, cy: Float, r: Float) = "M${cx - r},${cy}a$r,$r 0 1,0 ${2 * r},0a$r,$r 0 1,0 ${-2 * r},0"

    private fun stroke(name: String, vararg paths: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            for (d in paths) {
                addPath(
                    pathData = PathParser().parsePathString(d).toNodes(),
                    stroke = SolidColor(Color.White),
                    strokeLineWidth = 1.8f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
        }.build()

    /** Le symbole du Cerveau : un noyau et ses liens. */
    val Brain = stroke(
        "brain",
        circle(12f, 12f, 3.2f), circle(5f, 6f, 1.6f), circle(19f, 7f, 1.6f), circle(17f, 19f, 1.6f),
        "M6.3 7l3.3 3M17.6 8l-3.2 2.6M15.9 17.6l-1.8-3",
    )
    val Settings = stroke("settings", "M4 7h9M17 7h3M4 17h3M11 17h9", circle(15f, 7f, 2f), circle(9f, 17f, 2f))
    val Send = stroke("send", "M12 19V5M6 11l6-6 6 6")
    val Stop = stroke("stop", "M9 8h6a1 1 0 0 1 1 1v6a1 1 0 0 1-1 1H9a1 1 0 0 1-1-1V9a1 1 0 0 1 1-1z")
    val Back = stroke("back", "M19 12H5M11 6l-6 6 6 6")
    val Chevron = stroke("chevron", "M9 6l6 6-6 6")
    val Thinking = stroke("thinking", "M9 18h6M10 21h4M12 3a6 6 0 0 0-3.5 10.9V16h7v-2.1A6 6 0 0 0 12 3z")
    val Speed = stroke("speed", "M4.5 17a8 8 0 1 1 15 0", "M12 14l3.5-4.5", circle(12f, 14f, 1.2f))
    val Compare = stroke("compare", "M12 4v15M8 19h8M5 8h14M5 8l-2.5 6a2.6 2.6 0 0 0 5 0L5 8M19 8l-2.5 6a2.6 2.6 0 0 0 5 0L19 8")
    val Download = stroke("download", "M12 4v11M7 10l5 5 5-5M5 20h14")
    val Delete = stroke("delete", "M5 7h14M10 7V5h4v2M7 7l1 13h8l1-13")
    val Check = stroke("check", "M5 12.5l4.5 4.5L19 7")
    val Close = stroke("close", "M6 6l12 12M18 6L6 18")
    val Filter = stroke("filter", "M4 6h16M7 12h10M10 18h4")
    val Recenter = stroke("recenter", circle(12f, 12f, 3f), "M12 3v3M12 18v3M3 12h3M18 12h3")
    /** Suivre Axo : un œil. */
    val Follow = stroke("follow", "M2.5 12s3.5-6.5 9.5-6.5 9.5 6.5 9.5 6.5-3.5 6.5-9.5 6.5S2.5 12 2.5 12z", circle(12f, 12f, 2.8f))

    /** Rejouer : une flèche qui fait le tour. */
    val Replay = stroke("replay", "M4.27 9.93A8 8 0 1 1 6.34 17.66", "M4.27 9.93L3.6 5.6M4.27 9.93l4.3-.9")
    val Link = stroke(
        "link",
        "M10 14a4 4 0 0 1 0-5.66l2.34-2.34a4 4 0 0 1 5.66 5.66l-1.17 1.17",
        "M14 10a4 4 0 0 1 0 5.66l-2.34 2.34a4 4 0 0 1-5.66-5.66l1.17-1.17",
    )

    /**
     * Le visage d'Axo (icône piste C) en couleurs : tête ambre, branchies-liens, un lien givre
     * pour la mémoire froide. Viewport de l'icône de l'app (108).
     */
    val Face: ImageVector by lazy {
        ImageVector.Builder("axo", 108.dp, 108.dp, 108f, 108f).apply {
            addPath(
                pathData = PathParser().parsePathString(
                    "M38,53L26,42M35,60L22,58M38,67L26,73M70,53L82,42M73,60L86,58M70,67L82,73",
                ).toNodes(),
                stroke = SolidColor(Axo.Amber),
                strokeLineWidth = 2.6f,
                strokeLineCap = StrokeCap.Round,
            )
            val nodes = listOf(26f to 42f, 22f to 58f, 82f to 42f, 86f to 58f, 82f to 73f)
            addPath(
                pathData = PathParser().parsePathString(nodes.joinToString("") { (x, y) -> circle(x, y, 3.6f) }).toNodes(),
                fill = SolidColor(Axo.AmberLight),
            )
            addPath(pathData = PathParser().parsePathString(circle(26f, 73f, 3.6f)).toNodes(), fill = SolidColor(Axo.Frost))
            addPath(
                pathData = PathParser().parsePathString("M34,61a20,16 0 1,0 40,0a20,16 0 1,0 -40,0").toNodes(),
                fill = SolidColor(Axo.Amber),
            )
            addPath(
                pathData = PathParser().parsePathString(circle(46f, 58f, 2.7f) + circle(62f, 58f, 2.7f)).toNodes(),
                fill = SolidColor(Axo.OnAmber),
                pathFillType = PathFillType.NonZero,
            )
            addPath(
                pathData = PathParser().parsePathString("M49,66Q54,70 59,66").toNodes(),
                stroke = SolidColor(Axo.OnAmber),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
            )
        }.build()
    }
}
