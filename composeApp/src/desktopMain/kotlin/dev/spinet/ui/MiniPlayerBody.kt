package dev.spinet.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.times
import kotlin.math.roundToInt
import coil3.compose.LocalPlatformContext
import coil3.compose.SubcomposeAsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import okio.Path.Companion.toPath
import dev.spinet.AppBranding
import dev.spinet.audio.PlaybackState
import dev.spinet.ui.components.ThinSlider
import dev.spinet.ui.components.VOLUME_STEP
import dev.spinet.ui.screens.TrackThumbnail
import dev.spinet.viewmodel.AppUiState
import dev.spinet.viewmodel.RepeatMode

@Composable
internal fun MiniPlayerBody(
    state: AppUiState,
    onShuffle: () -> Unit,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onCycleRepeat: () -> Unit,
    onSetVolume: (Float) -> Unit,
    onMinimize: () -> Unit,
    onClose: () -> Unit,
    titleBarContainer: @Composable (@Composable () -> Unit) -> Unit = { it() }
) {
    val track = state.currentTrack
    val playing = state.playback == PlaybackState.PLAYING ||
        state.playback == PlaybackState.BUFFERING

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val showVolume = maxHeight >= 210.dp

        val chrome = TITLE_BAR_HEIGHT +
            CONTROLS_HEIGHT +
            (if (showVolume) VOLUME_HEIGHT else 0.dp) +
            (if (showVolume) ROW_SPACING * 3 else ROW_SPACING * 2) +
            BOTTOM_PADDING
        val rowHeight = (maxHeight - chrome).coerceAtLeast(0.dp)
        val artSize = minOf(
            rowHeight - ART_INSET,

            maxWidth * MAX_ART_WIDTH_FRACTION,
            MAX_ART
        ).coerceAtLeast(MIN_ART)

        val textScale = (artSize / BASE_ART).coerceIn(MIN_TEXT_SCALE, MAX_TEXT_SCALE)
        val art = track?.thumbnailUrl.orEmpty()
        if (art.isNotBlank()) {
            val model: Any = if (art.startsWith("http")) art else art
                .removePrefix("file:///")
                .removePrefix("file://")
                .removePrefix("file:/")
                .replace("%20", " ")
                .toPath()
            SubcomposeAsyncImage(
                model = ImageRequest.Builder(LocalPlatformContext.current)
                    .data(model)
                    .crossfade(true)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().blur(28.dp)
            )
        } else {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
        }

        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.35f),
                    1f to Color.Black.copy(alpha = 0.65f)
                )
            )
        )

        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.Transparent,
            contentColor = Color.White
        ) {
            Column(
                modifier = Modifier.fillMaxSize()
                    .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                titleBarContainer {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = AppBranding.NAME,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.6f),
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = onMinimize, modifier = Modifier.size(28.dp)) {
                            Icon(
                                Icons.Filled.Remove,
                                contentDescription = "Minimize",
                                tint = Color.White.copy(alpha = 0.8f),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        IconButton(onClick = onClose, modifier = Modifier.size(28.dp)) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "Close",
                                tint = Color.White.copy(alpha = 0.8f),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TrackThumbnail(track?.thumbnailUrl ?: "", size = artSize)

                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = track?.title ?: "Nothing playing",
                            style = MaterialTheme.typography.bodyMedium.scaledBy(textScale),
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            text = track?.artist ?: "",
                            style = MaterialTheme.typography.labelSmall.scaledBy(textScale),
                            color = Color.White.copy(alpha = 0.7f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(
                        2.dp,
                        Alignment.CenterHorizontally
                    ),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onShuffle) {
                        Icon(
                            Icons.Filled.Shuffle,
                            contentDescription = "Shuffle",

                            tint = if (state.shuffle) MaterialTheme.colorScheme.primary
                            else Color.White.copy(alpha = 0.45f)
                        )
                    }
                    IconButton(
                        onClick = onPrevious,
                        enabled = state.hasPrevious || state.positionMs > 3_000
                    ) {
                        Icon(
                            Icons.Filled.SkipPrevious,
                            contentDescription = "Previous",
                            tint = Color.White
                        )
                    }

                    Box(contentAlignment = Alignment.Center) {
                        ProgressRing(
                            progress = if (state.durationMs > 0L) {
                                state.positionMs.toFloat() / state.durationMs
                            } else 0f,
                            modifier = Modifier.size(42.dp)
                        )
                        IconButton(onClick = onPlayPause, enabled = track != null) {
                            Icon(
                                if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                contentDescription = if (playing) "Pause" else "Play",
                                tint = Color.White
                            )
                        }
                    }
                    IconButton(onClick = onNext, enabled = track != null) {
                        Icon(
                            Icons.Filled.SkipNext,
                            contentDescription = "Next",
                            tint = Color.White
                        )
                    }
                    IconButton(onClick = onCycleRepeat) {
                        Icon(
                            if (state.repeatMode == RepeatMode.ONE) Icons.Filled.RepeatOne
                            else Icons.Filled.Repeat,
                            contentDescription = "Repeat",
                            tint = if (state.repeatMode != RepeatMode.OFF) MaterialTheme.colorScheme.primary
                            else Color.White.copy(alpha = 0.45f)
                        )
                    }
                }

                if (showVolume) {
                    VolumeRow(volume = state.volume, onSetVolume = onSetVolume)
                }
            }
        }
    }
}

@Composable
private fun VolumeRow(volume: Float, onSetVolume: (Float) -> Unit) {
    var levelBeforeMute by remember { mutableStateOf(volume.takeIf { it > 0f } ?: 0.5f) }
    val muted = volume <= 0f

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = {
                if (muted) {
                    onSetVolume(levelBeforeMute)
                } else {
                    levelBeforeMute = volume
                    onSetVolume(0f)
                }
            },
            modifier = Modifier.size(24.dp)
        ) {
            Icon(
                if (muted) Icons.AutoMirrored.Filled.VolumeOff
                else Icons.AutoMirrored.Filled.VolumeUp,
                contentDescription = if (muted) "Unmute" else "Mute",
                modifier = Modifier.size(18.dp),
                tint = Color.White.copy(alpha = 0.8f)
            )
        }
        ThinSlider(
            value = volume,
            onValueChange = onSetVolume,
            modifier = Modifier.weight(1f),

            trackColor = Color.White.copy(alpha = 0.3f),
            scrollStep = VOLUME_STEP
        )

        Text(
            text = "${(volume * 100).roundToInt()}%",
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.8f),
            textAlign = TextAlign.End,
            modifier = Modifier.width(34.dp)
        )
    }
}

private val TITLE_BAR_HEIGHT = 34.dp
private val CONTROLS_HEIGHT = 48.dp
private val VOLUME_HEIGHT = 48.dp
private val ROW_SPACING = 8.dp
private val BOTTOM_PADDING = 12.dp

private val ART_INSET = 8.dp

private val MIN_ART = 36.dp
private val MAX_ART = 150.dp

private const val MAX_ART_WIDTH_FRACTION = 0.32f

private val BASE_ART = 56.dp
private const val MIN_TEXT_SCALE = 0.9f
private const val MAX_TEXT_SCALE = 1.6f

private fun TextStyle.scaledBy(factor: Float): TextStyle =
    if (factor == 1f) this else copy(
        fontSize = fontSize * factor,

        lineHeight = if (lineHeight.isSpecified) lineHeight * factor else lineHeight
    )

@Composable
private fun ProgressRing(progress: Float, modifier: Modifier = Modifier) {
    val sweep = 360f * progress.coerceIn(0f, 1f)

    val played = MaterialTheme.colorScheme.primary
    Canvas(modifier) {
        val stroke = size.minDimension * 0.07f

        val inset = stroke / 2f
        val arcSize = Size(size.width - stroke, size.height - stroke)
        drawArc(
            color = Color.White.copy(alpha = 0.28f),
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = arcSize,
            style = Stroke(width = stroke, cap = StrokeCap.Round)
        )

        if (sweep > 0f) {
            drawArc(
                color = played,
                startAngle = -90f,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
        }
    }
}
