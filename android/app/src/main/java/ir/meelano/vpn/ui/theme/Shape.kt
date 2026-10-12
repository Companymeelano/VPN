package ir.meelano.vpn.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Radii: card 16 / sheet 20 top / chip pill / the ring is a circle. Nothing else. */
val MeelanoShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** 4/8 spacing scale + the fixed sizes the control core is drawn with. */
@Immutable
data class Spacing(
    val s1: Dp = 4.dp,
    val s2: Dp = 8.dp,
    val s3: Dp = 12.dp,
    val s4: Dp = 16.dp,
    val s5: Dp = 24.dp,
    val s6: Dp = 32.dp,
    val ring: Dp = 172.dp,
    val ringStroke: Dp = 6.dp,
    val rowHeight: Dp = 64.dp,
    val flagW: Dp = 19.dp,
    val flagH: Dp = 14.dp,
    val touch: Dp = 48.dp,
)

val LocalSpacing = staticCompositionLocalOf { Spacing() }
