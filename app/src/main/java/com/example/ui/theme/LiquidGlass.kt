package com.example.ui.theme

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/**
 * Animated liquid blobs background. Renders floating organic radial blobs
 * behind a lush glassmorphic frosted surface.
 */
@Composable
fun AnimatedLiquidBackground(
    modifier: Modifier = Modifier,
    isDarkTheme: Boolean = true
) {
    val infiniteTransition = rememberInfiniteTransition(label = "LiquidBlobs")

    // Slow sinusoidal floating offsets
    val time by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 2f * java.lang.Math.PI.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(12000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "Time"
    )

    // Breathing sizes
    val pulse by infiniteTransition.animateFloat(
        initialValue = 0.9f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(
            animation = tween(6000, easing = SineIntensityEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "Pulse"
    )

    Box(modifier = modifier.fillMaxSize()) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height

            if (width == 0f || height == 0f) return@Canvas

            // Deep background canvas
            val baseBg = if (isDarkTheme) Color(0xFF0F0E13) else Color(0xFFF7F4FA)
            drawRect(color = baseBg)

            // Liquid Blob 1 - Lavender / Neon Violet
            val b1X = width * 0.3f + width * 0.15f * sin(time.toDouble()).toFloat()
            val b1Y = height * 0.4f + height * 0.12f * cos(time.toDouble()).toFloat()
            val b1Radius = width * 0.55f * pulse
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0xFF8B5CF6).copy(alpha = 0.38f),
                        Color(0xFFC084FC).copy(alpha = 0.12f),
                        Color.Transparent
                    ),
                    center = Offset(b1X, b1Y),
                    radius = b1Radius
                ),
                center = Offset(b1X, b1Y),
                radius = b1Radius
            )

            // Liquid Blob 2 - Cyan / Teal Glow
            val b2X = width * 0.75f + width * 0.12f * cos((time + 1.5).toDouble()).toFloat()
            val b2Y = height * 0.25f + height * 0.15f * sin((time + 1.5).toDouble()).toFloat()
            val b2Radius = width * 0.5f * (2f - pulse)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0xFF06B6D4).copy(alpha = 0.32f),
                        Color(0xFF2DD4BF).copy(alpha = 0.1f),
                        Color.Transparent
                    ),
                    center = Offset(b2X, b2Y),
                    radius = b2Radius
                ),
                center = Offset(b2X, b2Y),
                radius = b2Radius
            )

            // Liquid Blob 3 - Coral Rose Accents
            val b3X = width * 0.5f + width * 0.2f * sin((time - 1.0).toDouble()).toFloat()
            val b3Y = height * 0.75f + height * 0.1f * cos((time - 1.0).toDouble()).toFloat()
            val b3Radius = width * 0.6f * pulse
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0xFFEC4899).copy(alpha = 0.28f),
                        Color(0xFFF43F5E).copy(alpha = 0.08f),
                        Color.Transparent
                    ),
                    center = Offset(b3X, b3Y),
                    radius = b3Radius
                ),
                center = Offset(b3X, b3Y),
                radius = b3Radius
            )
        }

        // Luxurious frosted-glass semi-translucent overlay pane that blurs and blends the background colors together
        val tintColor = if (isDarkTheme) Color(0x9E0C0B0F) else Color(0x94FFFFFF)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(tintColor)
        )
    }
}

private val SineIntensityEasing = Easing { fraction ->
    sin(fraction * Math.PI).toFloat()
}

/**
 * Modifier that applies custom liquid glass effects.
 * Builds physical looking light highlights (specularity), a translucent background base,
 * and a light-refracting edge border contour.
 */
fun Modifier.liquidGlassCard(
    cornerRadius: Dp = 24.dp,
    borderWidth: Dp = 1.0.dp,
    isDarkTheme: Boolean = true
): Modifier {
    return this
        // 1. Draw elegant ambient drop shadow behind the card to create true depth separation from the moving background
        .drawBehind {
            val cornerRadiusPx = cornerRadius.toPx()
            val shadowColor = if (isDarkTheme) Color(0x3B000000) else Color(0x14000000)
            
            // Soft atmospheric diffuse glow shadow
            drawRoundRect(
                color = shadowColor,
                topLeft = Offset(0f, 4f),
                size = Size(size.width, size.height),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(cornerRadiusPx, cornerRadiusPx)
            )
            // Tighter structural ambient occlusion shadow underneath
            drawRoundRect(
                color = shadowColor.copy(alpha = shadowColor.alpha * 0.75f),
                topLeft = Offset(0f, 10f),
                size = Size(size.width, size.height),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(cornerRadiusPx, cornerRadiusPx)
            )
        }
        // 2. Clip and render the frosted surface contents
        .then(
            graphicsLayer {
                clip = true
                shape = RoundedCornerShape(cornerRadius)
            }.drawWithContent {
                val cornerRadiusPx = cornerRadius.toPx()

                // A. Base frosted glass material using a subtle gradient.
                // Light shines from above, so top is slightly more illuminated.
                val glassBgBrush = if (isDarkTheme) {
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0x59211D2B), // Refractive violet-slate dark top
                            Color(0x3D110E18)  // Translucent dark bottom
                        )
                    )
                } else {
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xA3FFFFFF), // Crisp ultra-white top
                            Color(0x4DFFFFFF)  // Translucent bottom
                        )
                    )
                }
                drawRect(brush = glassBgBrush)

                // B. Draw card children contents
                drawContent()

                // C. Diagonal specular glare reflection catching overhead sun light
                val glossBrush = Brush.linearGradient(
                    colors = listOf(
                        Color.White.copy(alpha = if (isDarkTheme) 0.12f else 0.24f),
                        Color.White.copy(alpha = 0.02f),
                        Color.Transparent,
                        Color.White.copy(alpha = 0.06f)
                    ),
                    start = Offset(0f, 0f),
                    end = Offset(size.width, size.height * 1.3f)
                )
                drawRect(brush = glossBrush, blendMode = BlendMode.Overlay)

                // D. Physical beveled borders
                val strokePx = borderWidth.toPx()
                val halfStroke = strokePx / 2f

                // Specular Light Bevel (Shining on top-left edge)
                val lightBorderBrush = Brush.linearGradient(
                    colors = listOf(
                        Color.White.copy(alpha = if (isDarkTheme) 0.45f else 0.75f),
                        Color.White.copy(alpha = if (isDarkTheme) 0.12f else 0.20f),
                        Color.Transparent
                    ),
                    start = Offset(0f, 0f),
                    end = Offset(size.width * 0.75f, size.height * 0.75f)
                )

                // Shadow Occlusion Bevel (Dark shading on bottom-right edge)
                val darkBorderBrush = Brush.linearGradient(
                    colors = listOf(
                        Color.Transparent,
                        Color.Black.copy(alpha = if (isDarkTheme) 0.08f else 0.03f),
                        Color.Black.copy(alpha = if (isDarkTheme) 0.32f else 0.15f)
                    ),
                    start = Offset(0f, 0f),
                    end = Offset(size.width, size.height)
                )

                // Draw specular border run
                drawRoundRect(
                    brush = lightBorderBrush,
                    topLeft = Offset(halfStroke, halfStroke),
                    size = Size(size.width - strokePx, size.height - strokePx),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(cornerRadiusPx, cornerRadiusPx),
                    style = Stroke(width = strokePx)
                )

                // Draw dark ground anchor run
                drawRoundRect(
                    brush = darkBorderBrush,
                    topLeft = Offset(halfStroke, halfStroke),
                    size = Size(size.width - strokePx, size.height - strokePx),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(cornerRadiusPx, cornerRadiusPx),
                    style = Stroke(width = strokePx)
                )
            }
        )
}

/**
 * Click behavior with a bouncy physical liquid scale reaction.
 */
@Composable
fun Modifier.liquidGlassClickable(
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    onClick: () -> Unit
): Modifier {
    val isPressed by interactionSource.collectIsPressedAsState()
    val scaleFactor by animateFloatAsState(
        targetValue = if (isPressed) 0.94f else 1.0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "LiquidGlassPress"
    )

    return this
        .scale(scaleFactor)
        .clickable(
            interactionSource = interactionSource,
            indication = androidx.compose.foundation.LocalIndication.current,
            onClick = onClick
        )
}
