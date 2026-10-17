package dev.spinet.tools

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import dev.spinet.DesktopPlaybackBar
import dev.spinet.audio.PlaybackState
import dev.spinet.model.Track
import dev.spinet.model.TrackSource
import dev.spinet.ui.MiniPlayerBody
import dev.spinet.viewmodel.AppUiState
import dev.spinet.viewmodel.RepeatMode
import org.jetbrains.skia.EncodedImageFormat
import java.io.File

private val SAMPLE_TRACK = Track(
    id = "sample",
    title = "BAILE INoLVIDABLE",
    artist = "Bad Bunny",
    durationLabel = "6:05",
    thumbnailUrl = System.getProperty("shot.art").orEmpty(),
    source = TrackSource.LOCAL,
    itemId = "",
    streamUrl = "",
    downloadable = false
)

private val SAMPLE_STATE = AppUiState(
    currentTrack = SAMPLE_TRACK,
    playback = PlaybackState.PLAYING,
    positionMs = 95_000L,
    durationMs = 365_000L,
    shuffle = true,
    repeatMode = RepeatMode.ALL,
    volume = 0.7f
)

private val MINI_SIZES = listOf(
    Triple("min-300x170", 300, 170),
    Triple("short-380x205", 380, 205),
    Triple("default-380x230", 380, 230),
    Triple("tall-420x320", 420, 320),
    Triple("wide-620x230", 620, 230)
)

private val BAR_WIDTHS = listOf(
    "narrow-900" to 900,
    "default-1180" to 1180,
    "wide-1600" to 1600
)

private const val BAR_HEIGHT = 110

fun main(args: Array<String>) {
    val outDir = File(args.firstOrNull() ?: "build/screenshots").absoluteFile
    outDir.mkdirs()
    var count = 0

    for ((name, w, h) in MINI_SIZES) {
        val file = File(outDir, "miniplayer-$name.png")
        render(file, w, h) {
            MaterialTheme(colorScheme = lightColorScheme()) {
                MiniPlayerBody(
                    state = SAMPLE_STATE,
                    onShuffle = {}, onPrevious = {}, onPlayPause = {}, onNext = {},
                    onCycleRepeat = {}, onSetVolume = {}, onMinimize = {}, onClose = {}
                )
            }
        }
        println("wrote ${file.name}  (${w}x$h)")
        count++
    }

    for ((name, width) in BAR_WIDTHS) {
        for ((themeName, scheme) in listOf(
            "light" to lightColorScheme(),
            "dark" to darkColorScheme()
        )) {
            val file = File(outDir, "playbackbar-$name-$themeName.png")
            render(file, width, BAR_HEIGHT) {
                MaterialTheme(colorScheme = scheme) { SamplePlaybackBar() }
            }
            println("wrote ${file.name}  (${width}x$BAR_HEIGHT, $themeName)")
            count++
        }
    }

    println("done: $count screenshots in $outDir")
}

@Composable
private fun SamplePlaybackBar() {
    DesktopPlaybackBar(
        track = SAMPLE_TRACK,
        playback = PlaybackState.PLAYING,
        positionMs = 95_000L,
        durationMs = 365_000L,
        hasNext = true,
        hasPrevious = true,
        shuffle = true,
        repeatMode = RepeatMode.ALL,
        isFavorite = true,
        canDownload = true,
        alreadyOnDevice = false,
        alreadyOnServer = false,
        serverTargets = emptyList(),
        sleepTimerRemainingMs = null,
        sleepAtEndOfTrack = false,
        onSetSleepTimer = {},
        onToggleSleepAtEnd = {},
        volume = 0.7f,
        nowPlayingOpen = false,
        queueOpen = false,
        fullscreen = false,
        onOpenMiniPlayer = {},
        onToggleFullscreen = {},
        onToggleNowPlaying = {},
        onToggleQueue = {},
        onToggleShuffle = {},
        onCycleRepeat = {},
        onToggleFavorite = {},
        onAddToPlaylist = {},
        onDownload = {},
        onUploadToServer = {},
        onVolumeChange = {},
        onPlay = {},
        onPause = {},
        onNext = {},
        onPrevious = {},
        onSeek = {}
    )
}

private fun render(file: File, width: Int, height: Int, content: @Composable () -> Unit) {
    val density = 2f
    ImageComposeScene(
        width = (width * density).toInt(),
        height = (height * density).toInt(),
        density = Density(density),
        content = content
    ).use { scene ->

        var frame = 0L
        repeat(MAX_FRAMES) {
            scene.render(frame)
            if (!scene.hasInvalidations()) return@repeat
            frame += FRAME_NANOS
            Thread.sleep(FRAME_MILLIS)
        }
        val image = scene.render(frame)
        val data = image.encodeToData(EncodedImageFormat.PNG) ?: error("PNG encode returned null")
        file.writeBytes(data.bytes)
    }
}

private const val MAX_FRAMES = 120
private const val FRAME_MILLIS = 16L
private const val FRAME_NANOS = FRAME_MILLIS * 1_000_000
