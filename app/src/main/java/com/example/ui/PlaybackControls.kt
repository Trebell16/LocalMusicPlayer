package com.example.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.playback.LoopMode

/**
 * PlaybackControls is a highly polished, unified Material 3 control component.
 * It integrates an optional seekable Progress Slider with the Play, Pause,
 * Skip Next (Forward), Skip Previous (Backward) actions alongside toggleable
 * Shuffle and Repeat (Loop) controls inside a cohesive layout.
 */
@Composable
fun PlaybackControls(
    isPlaying: Boolean,
    onPlayPauseClick: () -> Unit,
    onNextClick: () -> Unit,
    onPreviousClick: () -> Unit,
    modifier: Modifier = Modifier,
    isCompact: Boolean = false,
    isShuffle: Boolean = false,
    onShuffleClick: (() -> Unit)? = null,
    loopMode: LoopMode = LoopMode.NO_LOOP,
    onLoopModeClick: (() -> Unit)? = null,
    showShuffleRepeat: Boolean = !isCompact,
    positionMs: Long = 0L,
    durationMs: Long = 0L,
    onSeek: ((Long) -> Unit)? = null,
    showProgress: Boolean = false,
    controlColor: Color = MaterialTheme.colorScheme.onBackground,
    primaryContainerColor: Color = MaterialTheme.colorScheme.primary,
    onPrimaryColor: Color = Color(0xFF381E72)
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scaleFactor by animateFloatAsState(
        targetValue = if (isPressed) 0.92f else 1.0f,
        animationSpec = tween(durationMillis = 100),
        label = "PlayPauseScale"
    )

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Seekable Progress Column (Displayed above the main controls if enabled)
        if (showProgress) {
            val progressRatio = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f
            var sliderValue by remember(positionMs) { mutableStateOf(progressRatio) }
            var isDragging by remember { mutableStateOf(false) }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                Slider(
                    value = if (isDragging) sliderValue else progressRatio,
                    onValueChange = { value ->
                        isDragging = true
                        sliderValue = value
                    },
                    onValueChangeFinished = {
                        isDragging = false
                        onSeek?.invoke((sliderValue * durationMs).toLong())
                    },
                    colors = SliderDefaults.colors(
                        activeTrackColor = primaryContainerColor,
                        inactiveTrackColor = primaryContainerColor.copy(alpha = 0.15f),
                        thumbColor = primaryContainerColor
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("playback_progress_slider")
                )
                
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = formatDuration(if (isDragging) (sliderValue * durationMs).toLong() else positionMs),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = controlColor.copy(alpha = 0.6f)
                    )
                    Text(
                        text = formatDuration(durationMs),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = controlColor.copy(alpha = 0.6f)
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        // Action Buttons Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = if (isCompact) 4.dp else 16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Toggleable Shuffle Button
            if (showShuffleRepeat) {
                IconButton(
                    onClick = { onShuffleClick?.invoke() },
                    modifier = Modifier
                        .size(44.dp)
                        .testTag("control_playback_shuffle")
                ) {
                    CustomShuffleIcon(
                        color = if (isShuffle) MaterialTheme.colorScheme.primary else Color.Gray.copy(alpha = 0.6f),
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            // Skip Previous (Backward)
            IconButton(
                onClick = onPreviousClick,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .size(if (isCompact) 40.dp else 56.dp)
                    .testTag("control_playback_previous")
            ) {
                Canvas(modifier = Modifier.size(if (isCompact) 24.dp else 34.dp)) {
                    val w = size.width
                    val h = size.height
                    
                    // Draw vertical bar on left
                    drawRoundRect(
                        color = controlColor,
                        topLeft = Offset(w * 0.22f, h * 0.25f),
                        size = Size(w * 0.12f, h * 0.5f),
                        cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx())
                    )
                    // Draw backward arrow polygon
                    val path = Path().apply {
                        moveTo(w * 0.78f, h * 0.25f)
                        lineTo(w * 0.40f, h * 0.5f)
                        lineTo(w * 0.78f, h * 0.75f)
                        close()
                    }
                    drawPath(path, controlColor)
                }
            }

            // Play/Pause Action Floating Container
            Box(
                modifier = Modifier
                    .scale(scaleFactor)
                    .size(if (isCompact) 56.dp else 76.dp)
                    .shadow(
                        elevation = if (isCompact) 3.dp else 6.dp,
                        shape = RoundedCornerShape(if (isCompact) 18.dp else 26.dp)
                    )
                    .background(
                        brush = Brush.linearGradient(
                            colors = listOf(
                                primaryContainerColor,
                                MaterialTheme.colorScheme.tertiary
                            )
                        ),
                        shape = RoundedCornerShape(if (isCompact) 18.dp else 26.dp)
                    )
                    .clip(RoundedCornerShape(if (isCompact) 18.dp else 26.dp))
                    .clickable(
                        interactionSource = interactionSource,
                        indication = LocalIndication.current,
                        onClick = onPlayPauseClick
                    )
                    .testTag("control_playback_play_pause"),
                contentAlignment = Alignment.Center
            ) {
                if (isPlaying) {
                    // Customized sharp pause bars
                    Canvas(modifier = Modifier.size(if (isCompact) 22.dp else 30.dp)) {
                        val w = size.width
                        val h = size.height
                        val barWidth = w * 0.18f
                        val spacing = w * 0.16f
                        val startX1 = (w - (barWidth * 2 + spacing)) / 2f
                        val startY = h * 0.24f
                        val barHeight = h * 0.52f

                        drawRoundRect(
                            color = onPrimaryColor,
                            topLeft = Offset(startX1, startY),
                            size = Size(barWidth, barHeight),
                            cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx())
                        )
                        drawRoundRect(
                            color = onPrimaryColor,
                            topLeft = Offset(startX1 + barWidth + spacing, startY),
                            size = Size(barWidth, barHeight),
                            cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx())
                        )
                    }
                } else {
                    // Customized sharp play triangle
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Play",
                        tint = onPrimaryColor,
                        modifier = Modifier.size(if (isCompact) 28.dp else 40.dp)
                    )
                }
            }

            // Skip Next (Forward)
            IconButton(
                onClick = onNextClick,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .size(if (isCompact) 40.dp else 56.dp)
                    .testTag("control_playback_next")
            ) {
                Canvas(modifier = Modifier.size(if (isCompact) 24.dp else 34.dp)) {
                    val w = size.width
                    val h = size.height
                    
                    // Draw forward arrow polygon
                    val path = Path().apply {
                        moveTo(w * 0.22f, h * 0.25f)
                        lineTo(w * 0.60f, h * 0.5f)
                        lineTo(w * 0.22f, h * 0.75f)
                        close()
                    }
                    drawPath(path, controlColor)
                    // Draw vertical bar on right
                    drawRoundRect(
                        color = controlColor,
                        topLeft = Offset(w * 0.66f, h * 0.25f),
                        size = Size(w * 0.12f, h * 0.5f),
                        cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx())
                    )
                }
            }

            // Toggleable Repeat Button
            if (showShuffleRepeat) {
                IconButton(
                    onClick = { onLoopModeClick?.invoke() },
                    modifier = Modifier
                        .size(44.dp)
                        .testTag("control_playback_repeat")
                ) {
                    CustomLoopIcon(
                        color = when (loopMode) {
                            LoopMode.LOOP_ALL -> MaterialTheme.colorScheme.primary
                            LoopMode.LOOP_ONE -> MaterialTheme.colorScheme.tertiary
                            LoopMode.NO_LOOP -> Color.Gray.copy(alpha = 0.6f)
                        },
                        loopOne = loopMode == LoopMode.LOOP_ONE,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }
}

private fun formatDuration(ms: Long): String {
    val totalSec = ms / 1000
    val min = totalSec / 60
    val sec = totalSec % 60
    val minStr = if (min < 10) "0$min" else "$min"
    val secStr = if (sec < 10) "0$sec" else "$sec"
    return "$minStr:$secStr"
}

@Composable
fun CustomShuffleIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        
        // Parallel diagonal threads with crossing lines
        drawLine(color = color, start = Offset(w * 0.2f, h * 0.3f), end = Offset(w * 0.5f, h * 0.3f), strokeWidth = 2.dp.toPx())
        drawLine(color = color, start = Offset(w * 0.5f, h * 0.3f), end = Offset(w * 0.8f, h * 0.7f), strokeWidth = 2.dp.toPx())
        val arrow1 = Path().apply {
            moveTo(w * 0.8f, h * 0.7f)
            lineTo(w * 0.74f, h * 0.6f)
            lineTo(w * 0.82f, h * 0.6f)
            close()
        }
        drawPath(arrow1, color)

        drawLine(color = color, start = Offset(w * 0.2f, h * 0.7f), end = Offset(w * 0.5f, h * 0.7f), strokeWidth = 2.dp.toPx())
        drawLine(color = color, start = Offset(w * 0.5f, h * 0.7f), end = Offset(w * 0.8f, h * 0.3f), strokeWidth = 2.dp.toPx())
        val arrow2 = Path().apply {
            moveTo(w * 0.8f, h * 0.3f)
            lineTo(w * 0.74f, h * 0.4f)
            lineTo(w * 0.82f, h * 0.4f)
            close()
        }
        drawPath(arrow2, color)
    }
}

@Composable
fun CustomLoopIcon(color: Color, loopOne: Boolean, modifier: Modifier = Modifier) {
    val bgColor = MaterialTheme.colorScheme.background
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val center = Offset(w / 2, h / 2)
            val radius = w * 0.28f
            drawCircle(
                color = color,
                radius = radius,
                style = Stroke(width = 2.dp.toPx())
            )
            
            // Draw gap in circle to look like loop arrows
            drawArc(
                color = bgColor,
                startAngle = -45f,
                sweepAngle = 30f,
                useCenter = false,
                style = Stroke(width = 4.dp.toPx())
            )

            // Draw a neat arrow tip representing looping motion
            val arrowPath = Path().apply {
                moveTo(center.x + radius + 4.dp.toPx(), center.y - 4.dp.toPx())
                lineTo(center.x + radius, center.y + 2.dp.toPx())
                lineTo(center.x + radius - 4.dp.toPx(), center.y - 4.dp.toPx())
                close()
            }
            drawPath(arrowPath, color)
        }
        if (loopOne) {
            Text(
                "1",
                fontSize = 10.sp,
                color = color,
                fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.offset(y = (-1).dp)
            )
        }
    }
}
