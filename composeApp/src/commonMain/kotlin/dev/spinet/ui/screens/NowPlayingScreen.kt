package dev.spinet.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import dev.spinet.ui.components.ThinSlider
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.LocalPlatformContext
import coil3.compose.SubcomposeAsyncImage
import coil3.compose.SubcomposeAsyncImageContent
import coil3.request.ImageRequest
import coil3.request.crossfade
import dev.spinet.audio.PlaybackState
import dev.spinet.model.SourcePlugin
import dev.spinet.model.Track

@Composable
fun NowPlayingScreen(
    track: Track?,
    playback: PlaybackState,
    isFavorite: Boolean,
    positionMs: Long,
    durationMs: Long,
    hasNext: Boolean = false,
    hasPrevious: Boolean = false,
    onClose: () -> Unit,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onNext: () -> Unit = {},
    onPrevious: () -> Unit = {},
    onSeek: (Long) -> Unit,
    onToggleFavorite: () -> Unit,
    onAddToPlaylist: () -> Unit,
    canDownload: Boolean = false,
    onDownload: () -> Unit = {},
    alreadyOnDevice: Boolean = false,
    alreadyOnServer: Boolean = false,
    serverTargets: List<SourcePlugin> = emptyList(),
    onUploadToServer: ((String) -> Unit)? = null,
    transferProgress: Float? = null,
    transferLabel: String? = null,
    modifier: Modifier = Modifier
) {
    if (track == null) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(24.dp)
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
                Text(
                    text = "Now Playing",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Nothing playing yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        return
    }

    val hasBackdrop = track.thumbnailUrl.isNotBlank()
    Box(modifier.fillMaxSize()) {
        if (hasBackdrop) {
            SubcomposeAsyncImage(
                model = ImageRequest.Builder(LocalPlatformContext.current)
                    .data(artModel(track.thumbnailUrl))
                    .crossfade(true)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().blur(48.dp)
            )
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.45f),
                        1f to Color.Black.copy(alpha = 0.75f)
                    )
                )
            )
        } else {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
        }
        val base = MaterialTheme.colorScheme

        val overlay = if (!hasBackdrop) base else base.copy(
            surface = Color(0xFF1D1D1F),
            surfaceContainer = Color(0xFF242428),
            surfaceVariant = Color(0x33FFFFFF),
            onSurface = Color.White,
            onSurfaceVariant = Color(0xB3FFFFFF),
            onBackground = Color.White
        )
        MaterialTheme(colorScheme = overlay) {
        Surface(
            color = Color.Transparent,
            contentColor = if (hasBackdrop) Color.White
            else MaterialTheme.colorScheme.onBackground
        ) {
        Column(Modifier.fillMaxSize().padding(24.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                text = "Now Playing",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 24.dp)
                .aspectRatio(1f)
                .background(
                    MaterialTheme.colorScheme.surfaceVariant,
                    RoundedCornerShape(24.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            val artIcon = @Composable {
                Icon(
                    Icons.Filled.MusicNote,
                    contentDescription = null,
                    modifier = Modifier.size(96.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (track.thumbnailUrl.isBlank()) {
                artIcon()
            } else {
                val artUrl = if (LocalHighQualityArt.current) track.thumbnailUrl
                else track.thumbnailUrl
                SubcomposeAsyncImage(
                    model = ImageRequest.Builder(LocalPlatformContext.current)
                        .data(artUrl)
                        .crossfade(true)
                        .build(),
                    contentDescription = "Album art",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(24.dp)),
                    error = { artIcon() },
                    loading = { artIcon() },
                    success = { SubcomposeAsyncImageContent() }
                )
            }
        }

        Text(
            text = track.title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = track.artist,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = playback.name,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 4.dp)
        )

        SeekBar(
            positionMs = positionMs,
            durationMs = durationMs,
            onSeek = onSeek,
            modifier = Modifier.padding(top = 16.dp)
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onToggleFavorite) {
                Icon(
                    if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    contentDescription = "Like",
                    tint = if (isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            IconButton(onClick = onPrevious, enabled = hasPrevious) {
                Icon(Icons.Filled.SkipPrevious, contentDescription = "Previous")
            }

            val playing = playback == PlaybackState.PLAYING || playback == PlaybackState.BUFFERING
            FilledIconButton(
                onClick = { if (playing) onPause() else onPlay() },
                modifier = Modifier.size(72.dp)
            ) {
                Icon(
                    if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (playing) "Pause" else "Play",
                    modifier = Modifier.size(36.dp)
                )
            }

            IconButton(onClick = onNext, enabled = hasNext) {
                Icon(Icons.Filled.SkipNext, contentDescription = "Next")
            }

            IconButton(onClick = onAddToPlaylist) {
                Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = "Add to playlist")
            }
        }

        val busy = transferProgress != null

        val canSaveToDevice = canDownload && !alreadyOnDevice
        val canUpload = onUploadToServer != null && serverTargets.isNotEmpty() && !alreadyOnServer
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            if (canSaveToDevice) {
                TextButton(onClick = onDownload, enabled = !busy) {
                    Icon(Icons.Filled.Download, contentDescription = null)
                    Text("  Save to this device")
                }
            }
            if (canUpload) {
                if (serverTargets.size == 1) {
                    TextButton(onClick = { onUploadToServer!!(serverTargets.first().baseUrl) }, enabled = !busy) {
                        Icon(Icons.Filled.CloudUpload, contentDescription = null)
                        Text("  Upload to server")
                    }
                } else {
                    var menuOpen by remember { mutableStateOf(false) }
                    TextButton(onClick = { menuOpen = true }, enabled = !busy) {
                        Icon(Icons.Filled.CloudUpload, contentDescription = null)
                        Text("  Upload to server")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        serverTargets.forEach { target ->
                            DropdownMenuItem(
                                text = { Text("Upload to ${target.name}") },
                                onClick = { menuOpen = false; onUploadToServer!!(target.baseUrl) }
                            )
                        }
                    }
                }
            }
        }

        if (transferProgress != null) {
            Column(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                if (transferProgress < 0f) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(
                        progress = { transferProgress },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if (!transferLabel.isNullOrBlank()) {
                    Text(
                        text = transferLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
        }
        }
        }
    }
}

@Composable
private fun SeekBar(
    positionMs: Long,
    durationMs: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableStateOf(0f) }
    val max = durationMs.coerceAtLeast(1L).toFloat()
    val shown = if (dragging) dragValue else positionMs.toFloat().coerceIn(0f, max)

    Column(modifier = modifier.fillMaxWidth()) {
        ThinSlider(
            value = (shown / max).coerceIn(0f, 1f),
            enabled = durationMs > 0L,
            onValueChange = { fraction -> dragging = true; dragValue = fraction * max },
            onValueChangeFinished = {
                onSeek(dragValue.toLong())
                dragging = false
            },
            modifier = Modifier.fillMaxWidth()
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                text = formatTime(shown.toLong()),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = if (durationMs > 0L) formatTime(durationMs) else "--:--",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun formatTime(ms: Long): String {
    val totalSec = ms / 1000
    val m = totalSec / 60
    val s = totalSec % 60
    return "$m:${s.toString().padStart(2, '0')}"
}
