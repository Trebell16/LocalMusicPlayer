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
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
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
                    Icon(
                        imageVector = Icons.Default.Shuffle,
                        contentDescription = "Shuffle",
                        tint = if (isShuffle) primaryContainerColor else Color.Gray.copy(alpha = 0.6f),
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
                Icon(
                    imageVector = Icons.Default.SkipPrevious,
                    contentDescription = "Previous",
                    tint = controlColor,
                    modifier = Modifier.size(if (isCompact) 24.dp else 34.dp)
                )
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
                    Icon(
                        imageVector = Icons.Default.Pause,
                        contentDescription = "Pause",
                        tint = onPrimaryColor,
                        modifier = Modifier.size(if (isCompact) 28.dp else 40.dp)
                    )
                } else {
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
                Icon(
                    imageVector = Icons.Default.SkipNext,
                    contentDescription = "Next",
                    tint = controlColor,
                    modifier = Modifier.size(if (isCompact) 24.dp else 34.dp)
                )
            }

            // Toggleable Repeat Button
            if (showShuffleRepeat) {
                IconButton(
                    onClick = { onLoopModeClick?.invoke() },
                    modifier = Modifier
                        .size(44.dp)
                        .testTag("control_playback_repeat")
                ) {
                    val repeatIcon = if (loopMode == LoopMode.LOOP_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat
                    val repeatColor = when (loopMode) {
                        LoopMode.LOOP_ALL -> primaryContainerColor
                        LoopMode.LOOP_ONE -> MaterialTheme.colorScheme.tertiary
                        LoopMode.NO_LOOP -> Color.Gray.copy(alpha = 0.6f)
                    }
                    Icon(
                        imageVector = repeatIcon,
                        contentDescription = "Repeat Mode",
                        tint = repeatColor,
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


