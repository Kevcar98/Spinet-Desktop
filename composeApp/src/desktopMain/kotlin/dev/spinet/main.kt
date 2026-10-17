@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.spinet

import dev.spinet.data.savedServerSignIns
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import dev.spinet.viewmodel.RepeatMode
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.drop
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowDecoration
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.awt.Dimension
import dev.spinet.audio.DesktopAudioPlayer
import dev.spinet.audio.PlaybackState
import dev.spinet.data.NetworkRepository
import dev.spinet.data.createPlatformHttpClient
import dev.spinet.download.DesktopFileDownloader
import dev.spinet.library.LibraryRepository
import dev.spinet.plugins.PluginManager
import dev.spinet.settings.SettingsRepository
import dev.spinet.theme.ThemeRegistry
import dev.spinet.theme.toColorScheme
import coil3.compose.LocalPlatformContext
import coil3.compose.SubcomposeAsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import okio.Path.Companion.toPath
import dev.spinet.ui.MiniPlayerBody
import dev.spinet.ui.components.VOLUME_STEP
import dev.spinet.ui.screens.TrackThumbnail
import dev.spinet.viewmodel.MainViewModel
import androidx.compose.runtime.DisposableEffect
import dev.spinet.platform.WindowsThumbBar
import dev.spinet.platform.WindowsSnap
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.painterResource
import dev.spinet.platform.WindowEdgeSnap
import dev.spinet.update.createUpdateInstaller

fun main() {
    keepLog()
    runApp()
}

private fun runApp() = application {
    val audioPlayer = remember { DesktopAudioPlayer() }
    val fileDownloader = remember { DesktopFileDownloader() }

    val settingsRepository = remember { SettingsRepository() }
    val pluginManager = remember { PluginManager(settingsRepository) }
    val themeRegistry = remember { ThemeRegistry(settingsRepository) }
    val libraryRepository = remember { LibraryRepository(settingsRepository) }
    val repository = remember {
        NetworkRepository(
            client = createPlatformHttpClient(),
            sourcesProvider = { pluginManager.enabledOrdered() },
            extensionSources = { pluginManager.extensions.sources },
            serverSignIns = { settingsRepository.savedServerSignIns() }
        )
    }
    val viewModel = remember {
        MainViewModel(
            audioPlayer, repository, settingsRepository,
            pluginManager, themeRegistry, fileDownloader, libraryRepository,
            createUpdateInstaller()
        )
    }

    var showMiniPlayer by remember { mutableStateOf(false) }

    val appIcon = painterResource("spinet-icon.png")
    val windowState = rememberWindowState(width = 1180.dp, height = 760.dp)

    val closeApp = {
        audioPlayer.release()
        exitApplication()
    }

    Window(
        onCloseRequest = closeApp,
        state = windowState,
        title = AppBranding.NAME,

        icon = appIcon,

        decoration = WindowDecoration.Undecorated(resizerThickness = 8.dp),

        onKeyEvent = { event ->
            if (event.type != KeyEventType.KeyDown) return@Window false
            val s = viewModel.uiState.value
            when {
                event.key == Key.Spacebar -> {
                    if (s.playback == PlaybackState.PLAYING || s.playback == PlaybackState.BUFFERING) {
                        viewModel.onPauseClicked()
                    } else {
                        viewModel.onPlayClicked()
                    }
                    true
                }
                event.key == Key.DirectionRight && event.isCtrlPressed -> {
                    viewModel.onNext(); true
                }
                event.key == Key.DirectionLeft && event.isCtrlPressed -> {
                    viewModel.onPrevious(); true
                }
                event.key == Key.DirectionRight -> {
                    viewModel.onSeek((s.positionMs + 5_000).coerceAtMost(s.durationMs)); true
                }
                event.key == Key.DirectionLeft -> {
                    viewModel.onSeek((s.positionMs - 5_000).coerceAtLeast(0)); true
                }
                else -> false
            }
        }
    ) {
        window.minimumSize = Dimension(900, 600)

        val windowInfo = LocalWindowInfo.current
        LaunchedEffect(viewModel) {
            snapshotFlow { windowInfo.isWindowFocused }
                .drop(1)
                .filter { it }
                .collect { viewModel.refreshRemoteSession() }
        }

        val state by viewModel.uiState.collectAsState()

        LaunchedEffect(Unit) {
            WindowsSnap.enable(window)
            WindowEdgeSnap.install(window)
        }

        DisposableEffect(viewModel) {
            WindowsThumbBar.install(
                window = window,
                onPrevious = viewModel::onPrevious,
                onPlayPause = {
                    val s = viewModel.uiState.value
                    if (s.playback == PlaybackState.PLAYING || s.playback == PlaybackState.BUFFERING) {
                        viewModel.onPauseClicked()
                    } else {
                        viewModel.onPlayClicked()
                    }
                },
                onNext = viewModel::onNext
            )
            onDispose { WindowsThumbBar.dispose() }
        }
        val isPlaying = state.playback == PlaybackState.PLAYING ||
            state.playback == PlaybackState.BUFFERING
        LaunchedEffect(isPlaying) { WindowsThumbBar.setPlaying(isPlaying) }
        val canSkipNext = state.queue.isNotEmpty() && (
            state.queueIndex < state.queue.size - 1 ||
                state.shuffle || state.repeatMode != RepeatMode.OFF
            )
        LaunchedEffect(state.hasPrevious, canSkipNext) {
            WindowsThumbBar.setSkipEnabled(state.hasPrevious, canSkipNext)
        }
        val colorScheme = state.themes
            .firstOrNull { it.id == state.selectedThemeId }
            ?.toColorScheme()
            ?: lightColorScheme()
        MaterialTheme(colorScheme = colorScheme) {
            Column(Modifier.fillMaxSize()) {
                AppTitleBar(
                    windowState = windowState,
                    onMinimize = { windowState.isMinimized = true },
                    onToggleMaximize = {
                        windowState.placement =
                            if (windowState.placement == WindowPlacement.Maximized)
                                WindowPlacement.Floating
                            else WindowPlacement.Maximized
                    },
                    onClose = closeApp
                )
                App(
                    audioPlayer = audioPlayer,
                    fileDownloader = fileDownloader,
                    settingsRepository = settingsRepository,
                    pluginManager = pluginManager,
                    themeRegistry = themeRegistry,
                    libraryRepository = libraryRepository,
                    repository = repository,
                    viewModel = viewModel,
                    onOpenMiniPlayer = { showMiniPlayer = true }
                )
            }
        }
    }

    val miniState = rememberWindowState(width = 380.dp, height = 230.dp)

    if (showMiniPlayer) {
        MiniPlayerWindow(
            viewModel = viewModel,
            miniState = miniState,
            onClose = { showMiniPlayer = false }
        )
    }
}

@androidx.compose.runtime.Composable
private fun FrameWindowScope.AppTitleBar(
    windowState: WindowState,
    onMinimize: () -> Unit,
    onToggleMaximize: () -> Unit,
    onClose: () -> Unit
) {
    WindowDraggableArea {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().height(38.dp)
                    .padding(start = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .onGloballyPositioned { coords ->

                            val b = coords.boundsInWindow()
                            WindowEdgeSnap.setDragStrip(
                                b.left.toInt(), b.top.toInt(),
                                b.right.toInt(), b.bottom.toInt()
                            )
                            WindowsSnap.setTitleBarBounds(
                                b.left.toInt(), b.top.toInt(),
                                b.right.toInt(), b.bottom.toInt()
                            )
                        },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = AppBranding.NAME,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
                IconButton(onClick = onMinimize, modifier = Modifier.size(34.dp)) {
                    Icon(
                        Icons.Filled.Remove,
                        contentDescription = "Minimize",
                        modifier = Modifier.size(16.dp)
                    )
                }
                IconButton(onClick = onToggleMaximize, modifier = Modifier.size(34.dp)) {
                    Icon(
                        Icons.Filled.CropSquare,
                        contentDescription = if (windowState.placement == WindowPlacement.Maximized)
                            "Restore" else "Maximize",
                        modifier = Modifier.size(14.dp)
                    )
                }
                IconButton(onClick = onClose, modifier = Modifier.size(34.dp)) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Close",
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun MiniPlayerWindow(
    viewModel: MainViewModel,
    miniState: WindowState,
    onClose: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    val colorScheme = state.themes
        .firstOrNull { it.id == state.selectedThemeId }
        ?.toColorScheme()
        ?: lightColorScheme()

    val appIcon = painterResource("spinet-icon.png")
    Window(
        onCloseRequest = onClose,
        state = miniState,
        title = "${AppBranding.NAME} Mini",
        icon = appIcon,
        alwaysOnTop = true,

        decoration = WindowDecoration.Undecorated(resizerThickness = 6.dp),

        onKeyEvent = { event ->
            if (event.type != KeyEventType.KeyDown) return@Window false
            val current = viewModel.uiState.value
            fun nudgeVolume(delta: Float): Boolean {
                viewModel.onSetVolume((current.volume + delta).coerceIn(0f, 1f))
                return true
            }
            when (event.key) {
                Key.DirectionUp, Key.DirectionRight -> nudgeVolume(VOLUME_STEP)
                Key.DirectionDown, Key.DirectionLeft -> nudgeVolume(-VOLUME_STEP)
                Key.Spacebar -> {
                    val playing = current.playback == PlaybackState.PLAYING ||
                        current.playback == PlaybackState.BUFFERING
                    if (playing) viewModel.onPauseClicked() else viewModel.onPlayClicked()
                    true
                }
                else -> false
            }
        }
    ) {
        LaunchedEffect(Unit) { WindowsSnap.roundCorners(window) }

        window.minimumSize = Dimension(300, 170)
        MaterialTheme(colorScheme = colorScheme) {
            MiniPlayerBody(
                state = state,
                onShuffle = viewModel::onToggleShuffle,
                onPrevious = viewModel::onPrevious,
                onPlayPause = {
                    val playing = state.playback == PlaybackState.PLAYING ||
                        state.playback == PlaybackState.BUFFERING
                    if (playing) viewModel.onPauseClicked() else viewModel.onPlayClicked()
                },
                onNext = viewModel::onNext,
                onCycleRepeat = viewModel::onCycleRepeat,
                onSetVolume = viewModel::onSetVolume,
                onMinimize = { miniState.isMinimized = true },
                onClose = onClose,

                titleBarContainer = { content -> WindowDraggableArea { content() } }
            )
        }
    }
}
