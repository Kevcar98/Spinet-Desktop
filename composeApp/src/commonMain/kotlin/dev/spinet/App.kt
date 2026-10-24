package dev.spinet

import dev.spinet.data.savedServerSignIns
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.ui.text.style.TextAlign
import dev.spinet.ui.components.ThinSlider
import dev.spinet.ui.components.VOLUME_STEP
import kotlin.math.roundToInt
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Slider
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import coil3.ImageLoader
import coil3.compose.LocalPlatformContext
import coil3.compose.SubcomposeAsyncImage
import coil3.compose.setSingletonImageLoaderFactory
import coil3.network.ktor3.KtorNetworkFetcherFactory
import coil3.request.ImageRequest
import coil3.request.crossfade
import dev.spinet.audio.AudioPlayer
import dev.spinet.audio.PlaybackState
import dev.spinet.data.NetworkRepository
import dev.spinet.data.createPlatformHttpClient
import dev.spinet.download.FileDownloader
import dev.spinet.library.LibraryRepository
import dev.spinet.navigation.Destination
import dev.spinet.plugins.PluginManager
import dev.spinet.theme.ThemeRegistry
import dev.spinet.theme.toColorScheme
import dev.spinet.settings.SettingsRepository
import dev.spinet.model.SourceKind
import dev.spinet.model.SourcePlugin
import dev.spinet.model.Track
import dev.spinet.model.TrackSource
import dev.spinet.model.looseKey
import dev.spinet.model.matchKey
import dev.spinet.local.PickLocalFolderButton
import dev.spinet.ui.screens.AddToPlaylistDialog
import dev.spinet.ui.screens.BrowsePlaylistsScreen
import dev.spinet.ui.screens.IdentifySongAction
import dev.spinet.ui.screens.LibraryScreen
import dev.spinet.ui.screens.LocalFolderEntry
import dev.spinet.ui.screens.LocalFoldersScreen
import dev.spinet.ui.screens.LocalMusicScreen
import dev.spinet.ui.screens.MoveToFolderDialog
import dev.spinet.ui.screens.PlaylistDetailScreen
import dev.spinet.ui.screens.SearchScreen
import dev.spinet.ui.screens.SettingsScreen
import dev.spinet.ui.screens.SupportScreen
import dev.spinet.ui.screens.TrackThumbnail
import dev.spinet.ui.screens.artModel
import dev.spinet.viewmodel.MainViewModel
import dev.spinet.viewmodel.RepeatMode
import androidx.compose.runtime.LaunchedEffect
import dev.spinet.ui.screens.UpdateDialog
import dev.spinet.update.createUpdateInstaller
import dev.spinet.APP_VERSION
import dev.spinet.local.defaultLocalFolder
import dev.spinet.ui.screens.UpdateBanner

private const val LOCAL_ALL_TRACKS = "__all_local__"

private enum class SidePanel { None, NowPlaying, Queue }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(
    audioPlayer: AudioPlayer,
    fileDownloader: FileDownloader,
    settingsRepository: SettingsRepository = remember { SettingsRepository() },
    pluginManager: PluginManager = remember { PluginManager(settingsRepository) },
    themeRegistry: ThemeRegistry = remember { ThemeRegistry(settingsRepository) },
    libraryRepository: LibraryRepository = remember { LibraryRepository(settingsRepository) },
    repository: NetworkRepository = remember {
        NetworkRepository(
            client = createPlatformHttpClient(),
            sourcesProvider = { pluginManager.enabledOrdered() },
            extensionSources = { pluginManager.extensions.sources },
            serverSignIns = { settingsRepository.savedServerSignIns() }
        )
    },
    viewModel: MainViewModel = viewModel {
        MainViewModel(
            audioPlayer, repository, settingsRepository,
            pluginManager, themeRegistry, fileDownloader, libraryRepository,
            createUpdateInstaller()
        )
    },

    onOpenMiniPlayer: (() -> Unit)? = null
) {
    setSingletonImageLoaderFactory { context ->
        ImageLoader.Builder(context)
            .components { add(KtorNetworkFetcherFactory()) }
            .crossfade(true)
            .build()
    }

    val state by viewModel.uiState.collectAsState()
    val canFindVersions = state.sources.any { it.kind == SourceKind.EXTENSION && it.enabled }
    val extensionRepositories = remember {
        dev.spinet.extensions.extensionPlatform?.let {
            dev.spinet.extensions.ExtensionRepositories(
                settingsRepository,
                repository.httpClient,
                installedVersions = { pluginManager.extensions.sources.associate { s -> s.id to s.versionCode } },
                onExtensionsChanged = viewModel::onExtensionsChanged
            )
        }
    }
    val extensionState = extensionRepositories?.state?.collectAsState()
    val colorScheme = state.themes
        .firstOrNull { it.id == state.selectedThemeId }
        ?.toColorScheme()
        ?: lightColorScheme()

    MaterialTheme(colorScheme = colorScheme) {
        dev.spinet.extensions.ExtensionDropTarget(
            message = extensionState?.value?.message,
            onDropped = { files -> extensionRepositories?.installFromFiles(files) }
        ) {
        androidx.compose.runtime.CompositionLocalProvider(
            dev.spinet.ui.screens.LocalHighQualityArt provides state.highQualityArt
        ) {
        var current by remember { mutableStateOf(Destination.Library) }

        var libraryDetail by remember { mutableStateOf<String?>(null) }

        var browseSource by remember { mutableStateOf<SourcePlugin?>(null) }
        var browseFolderTitle by remember { mutableStateOf<String?>(null) }

        var localFolder by remember { mutableStateOf<String?>(null) }

        var sidePanel by remember { mutableStateOf(SidePanel.None) }
        var prevPanel by remember { mutableStateOf(SidePanel.None) }
        fun showPanel(p: SidePanel) {
            if (p != sidePanel) { prevPanel = sidePanel; sidePanel = p }
        }
        fun closePanel() { sidePanel = prevPanel; prevPanel = SidePanel.None }
        val showNowPlaying = sidePanel == SidePanel.NowPlaying
        val showQueue = sidePanel == SidePanel.Queue
        var fullscreen by remember { mutableStateOf(false) }

        LaunchedEffect(Unit) { viewModel.onCheckForUpdates() }

        state.availableUpdate?.takeIf { state.updateDialogOpen }?.let { update ->
            UpdateDialog(
                update = update,
                busy = state.updateBusy,
                error = state.updateError,
                onInstall = viewModel::onInstallUpdate,
                onDismiss = viewModel::onDismissUpdate
            )
        }

        state.passwordPrompt?.let { server ->
            dev.spinet.ui.screens.ServerPasswordDialog(
                server = server,
                error = state.passwordPromptError,
                onSubmit = { password, remember -> viewModel.onEnterServerPassword(server.id, password, remember) },
                onSkip = { viewModel.onSkipServerPassword(server.id) }
            )
        }

        var addTarget by remember { mutableStateOf<Track?>(null) }

        var addSelection by remember { mutableStateOf(false) }

        var moveTarget by remember { mutableStateOf<Track?>(null) }

        val defaultFolder = remember { defaultLocalFolder() }
        var showFolderPrompt by remember {
            mutableStateOf(settingsRepository.localFolderUri.isBlank())
        }
        if (showFolderPrompt) {
            AlertDialog(
                onDismissRequest = { showFolderPrompt = false },
                title = { Text("Where should music be saved?") },
                text = {
                    Text(
                        if (defaultFolder != null) {
                            "Downloads and your offline library live in this folder. " +
                                "Use ${defaultFolder.label}, or pick your own. You can " +
                                "change this later in Settings."
                        } else {
                            "Pick a folder that holds your music so it plays here, even offline. You can change this later in Settings."
                        }
                    )
                },
                confirmButton = {
                    PickLocalFolderButton(text = "Choose folder") { folder ->
                        viewModel.onPickLocalFolder(folder)
                        settingsRepository.localFolderPrompted = true
                        showFolderPrompt = false
                    }
                },
                dismissButton = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { showFolderPrompt = false }) { Text("Not now") }
                        if (defaultFolder != null) {
                            TextButton(onClick = {
                                viewModel.onPickLocalFolder(defaultFolder)
                                settingsRepository.localFolderPrompted = true
                                showFolderPrompt = false
                            }) { Text("Use ${defaultFolder.label}") }
                        }
                    }
                }
            )
        }

        val browsableSources = state.sources.filter {
            it.enabled && it.kind == SourceKind.SERVER
        }

        val deviceKeys = remember(state.localTracks) {
            (state.localTracks.map { it.matchKey() } +
                state.localTracks.mapNotNull { it.looseKey() }).toSet()
        }
        val isOnDevice: (Track) -> Boolean = { track ->
            track.matchKey() in deviceKeys || track.looseKey()?.let { it in deviceKeys } == true
        }

        val serverKeys = remember(state.favorites, state.playlists, state.browseTracks, state.serverLibraryKeys) {
            val onServer = (state.favorites +
                state.playlists.flatMap { it.tracks } +
                state.browseTracks)
                .filter { it.serverBaseUrl.isNotBlank() }
            (onServer.map { it.matchKey() } +
                onServer.mapNotNull { it.looseKey() }).toSet() + state.serverLibraryKeys
        }
        val isOnServer: (Track) -> Boolean = { track ->
            track.serverBaseUrl.isNotBlank() || track.matchKey() in serverKeys ||
                track.looseKey()?.let { it in serverKeys } == true
        }

        val nowKey = state.currentTrack?.matchKey()
        val nowProgress = if (state.durationMs > 0L)
            state.positionMs.toFloat() / state.durationMs else 0f

        val localFolderEntries = remember(state.localTracks) {
            state.localTracks
                .filter { it.localFolder.isNotBlank() }
                .groupingBy { it.localFolder }
                .eachCount()
                .map { (name, count) -> LocalFolderEntry(name, count) }
                .sortedBy { it.name.lowercase() }
        }
        val localShownTracks = when (localFolder) {
            null -> emptyList()
            LOCAL_ALL_TRACKS -> state.localTracks
            else -> state.localTracks.filter { it.localFolder == localFolder }
        }

        val contentModifier = Modifier.fillMaxSize()

        Box(modifier = Modifier.fillMaxSize()) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    NavigationRail {
                        Destination.entries.forEach { destination ->
                            NavigationRailItem(
                                selected = current == destination,
                                onClick = { current = destination },
                                icon = { Icon(destination.icon, contentDescription = destination.label) },
                                label = { Text(destination.label) }
                            )
                        }
                    }

                    Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                        when (current) {
                            Destination.Search -> SearchScreen(
                                query = state.searchQuery,
                                results = state.searchResults,
                                isSearching = state.isSearching,
                                error = state.searchError,
                                downloadStatus = state.downloadStatus,
                                favoriteTrackIds = state.favoriteTrackIds,
                                serverTargets = browsableSources,
                                onQueryChange = viewModel::onSearchQueryChange,
                                onTrackClick = { track ->

                                    viewModel.onSearchCommitted(state.searchQuery)
                                    viewModel.onTrackClicked(track, state.searchResults)
                                },
                                onDownloadToPhone = viewModel::onDownloadClicked,
                                onDownloadToServer = { track, baseUrl -> viewModel.onDownloadTrackToServer(track, baseUrl) },
                                onToggleFavorite = viewModel::onToggleFavorite,
                                onAddToPlaylist = { addTarget = it },
                                onDeleteFromServer = { track -> viewModel.onDeleteFromServer(track) },
                                isOnDevice = isOnDevice,
                                isOnServer = isOnServer,
                                searchHistory = state.searchHistory,
                                onClearHistory = viewModel::onClearSearchHistory,
                                onRemoveHistory = viewModel::onRemoveSearchHistory,
                                identifyContent = {
                                    if (repository.canRecognize()) {
                                    IdentifySongAction(
                                        phase = state.identifyPhase,
                                        result = state.identifyResult,
                                        error = state.identifyError,
                                        sources = state.identifySources,
                                        onStart = viewModel::onStartIdentify,
                                        onDismiss = viewModel::onDismissIdentify,
                                        onSearchResult = viewModel::onSearchIdentified,
                                    )
                                }
                                },
                                modifier = contentModifier
                            )

                            Destination.Library -> {
                                val browsing = browseSource
                                val detail = libraryDetail
                                when {
                                    browsing != null && browseFolderTitle != null -> PlaylistDetailScreen(
                                        title = browseFolderTitle!!,
                                        tracks = state.browseTracks,
                                        favoriteTrackIds = state.favoriteTrackIds,
                                        isFavoritesList = false,
                                        onBack = { browseFolderTitle = null },
                                        onTrackClick = { track -> viewModel.onTrackClicked(track, state.browseTracks) },
                                        onPlayAll = { viewModel.onPlayAll(state.browseTracks) },
                                        shuffle = state.shuffle,
                                        onToggleShuffle = viewModel::onToggleShuffle,
                                        onToggleFavorite = viewModel::onToggleFavorite,
                                        onRemove = null,
                                        onDelete = null,
                                        serverTargets = browsableSources,
                                        onDownloadAll = { viewModel.onDownloadPlaylistToDevice(state.browseTracks, browseFolderTitle ?: "") },
                                        onRedownloadAll = {
                                            viewModel.onDownloadPlaylistToDevice(
                                                state.browseTracks, browseFolderTitle ?: "", force = true
                                            )
                                        },
                                        onRetryFailedDownloads = viewModel::onRetryFailedDownloads,
                                        failedDownloadCount = state.failedDownloadsFor(browseFolderTitle ?: ""),
                                        onSaveTrackToDevice = { track -> viewModel.onDownloadClicked(track) },
                                        onUploadTrackToServer = { track, baseUrl -> viewModel.onUploadPlaylistToServer(listOf(track), baseUrl) },
                                        onDeleteTrackFromServer = { track -> viewModel.onDeleteFromServer(track) },
                                        onDeleteTrackFromDevice = { track -> viewModel.onDeleteLocalCopy(track) },
                                        onMoveTrackToFolder = { track -> moveTarget = track },
                                        onAddToPlaylist = { addTarget = it },
                                        selectedTrackIds = state.selectedTrackIds,
                                        onToggleSelect = viewModel::onToggleSelectAnchored,
                                        onRangeSelect = { track, visible -> viewModel.onSelectRangeTo(track, visible) },
                                        onClearSelection = viewModel::onClearSelection,
                                        onSelectAll = viewModel::onSelectAll,
                                        onAddSelectionToPlaylist = { addSelection = true },
                                        onPickVersion = if (canFindVersions) { track -> viewModel.onPickVersion(track) } else null,
                                        onRefresh = { viewModel.onRefreshBrowse() },
                                        onMove = { from, to -> viewModel.onMoveBrowseTrack(from, to) },
                                        currentTrackKey = nowKey,
                                        playingProgress = nowProgress,
                                        isOnDevice = isOnDevice,
                                isOnServer = isOnServer,
                                        modifier = contentModifier
                                    )

                                    browsing != null -> BrowsePlaylistsScreen(
                                        sourceName = browsing.name,
                                        playlists = state.browsePlaylists,
                                        loading = state.browseLoading,
                                        error = state.browseError,
                                        onBack = { browseSource = null },
                                        onSearch = { query ->

                                            browseFolderTitle = "Search: $query"
                                            viewModel.onSearchBrowseSource(browsing.baseUrl, query)
                                        },
                                        onOpenPlaylist = { pl ->
                                            browseFolderTitle = pl.name
                                            viewModel.onOpenBrowsePlaylist(browsing.baseUrl, pl.id)
                                        },
                                        onPlayPlaylist = { pl ->
                                            viewModel.onPlayBrowsePlaylist(browsing.baseUrl, pl.id)
                                        },
                                        onDeletePlaylist = { pl ->
                                            viewModel.onDeleteBrowseFolder(browsing.baseUrl, pl.id)
                                        },
                                        modifier = contentModifier
                                    )

                                    detail == null -> LibraryScreen(
                                        playlists = state.playlists,
                                        favoritesCount = state.favorites.size,
                                        browsableSources = browsableSources,
                                        localFolderLabel = state.localFolderLabel,
                                        localTrackCount = state.localTracks.size,
                                        onOpenFavorites = { libraryDetail = "favorites" },
                                        mostPlayedCount = state.mostPlayed.size,
                                        onOpenMostPlayed = { libraryDetail = "mostplayed" },
                                        onOpenPlaylist = { libraryDetail = it },
                                        onPlayPlaylist = { pl -> viewModel.onPlayAll(pl.tracks) },
                                        onOpenLocal = { libraryDetail = "local"; localFolder = null },
                                        onBrowseSource = { source ->
                                            browseSource = source
                                            browseFolderTitle = null
                                            viewModel.onBrowseSource(source.baseUrl)
                                        },
                                        onCreatePlaylist = viewModel::onCreatePlaylist,
                                        onImportPlaylist = viewModel::onImportPlaylist,
                                        onRenamePlaylist = viewModel::onRenamePlaylist,
                                        onDeletePlaylist = viewModel::onDeletePlaylist,
                                        onMovePlaylist = viewModel::onMovePlaylist,
                                        onDownloadPlaylist = { pl ->
                                            viewModel.onDownloadPlaylistToDevice(pl.tracks, pl.name)
                                        },
                                        onUploadPlaylist = { pl, baseUrl ->
                                            viewModel.onUploadPlaylistToServer(pl.tracks, baseUrl, pl.name)
                                        },
                                        exportPlaylistJson = { pl -> viewModel.exportPlaylistJson(pl.id) },
                                        syncAvailable = state.serverHost.isNotBlank(),
                                        onSyncPlaylists = viewModel::onSyncPlaylistsToServer,
                                        onFetchPlaylists = viewModel::onFetchPlaylistsFromServer,
                                        modifier = contentModifier
                                    )

                                    detail == "local" && localFolder == null -> LocalFoldersScreen(
                                        title = "On this device",
                                        allCount = state.localTracks.size,
                                        folders = localFolderEntries,
                                        loading = state.localLoading,
                                        onBack = { libraryDetail = null },
                                        onOpenAll = { localFolder = LOCAL_ALL_TRACKS },
                                        onPlayAll = {
                                            viewModel.onPlayAll(state.localTracks)
                                        },
                                        onOpenFolder = { localFolder = it },
                                        onPlayFolder = { name ->
                                            viewModel.onPlayAll(state.localTracks.filter { it.localFolder == name })
                                        },
                                        onDeleteFolder = { name -> viewModel.onDeleteLocalFolder(name) },
                                        modifier = contentModifier
                                    )

                                    detail == "local" -> LocalMusicScreen(
                                        folderLabel = if (localFolder == LOCAL_ALL_TRACKS) "All tracks" else localFolder!!,
                                        tracks = localShownTracks,
                                        loading = state.localLoading,
                                        onBack = { localFolder = null },
                                        onRefresh = viewModel::onRefreshLocalWithMetadata,
                                        onTrackClick = { track -> viewModel.onTrackClicked(track, localShownTracks) },
                                        favoriteTrackIds = state.favoriteTrackIds,
                                        onToggleFavorite = viewModel::onToggleFavorite,
                                        serverTargets = browsableSources,
                                        onUploadToServer = { baseUrl -> viewModel.onUploadPlaylistToServer(localShownTracks, baseUrl) },
                                        onUploadTrackToServer = { track, baseUrl -> viewModel.onUploadPlaylistToServer(listOf(track), baseUrl) },
                                        onDeleteTrack = { track -> viewModel.onDeleteLocalTrack(track) },
                                        onMoveTrackToFolder = { track -> moveTarget = track },

                                        onDeleteAll = if (localFolder == LOCAL_ALL_TRACKS) {
                                            { viewModel.onDeleteAllLocal() }
                                        } else {
                                            { viewModel.onDeleteLocalTracks(localShownTracks) }
                                        },
                                        deleteAllWhere = if (localFolder == LOCAL_ALL_TRACKS) "this device" else "this folder",
                                        currentTrackKey = nowKey,
                                        playingProgress = nowProgress,

                                        onMove = if (localFolder == LOCAL_ALL_TRACKS) {
                                            { from, to -> viewModel.onMoveLocalTrack(from, to) }
                                        } else null,
                                        isOnServer = isOnServer,
                                        onPlayAll = { viewModel.onPlayAll(localShownTracks) },
                                        shuffle = state.shuffle,
                                        onToggleShuffle = viewModel::onToggleShuffle,
                                        modifier = contentModifier
                                    )

                                    detail == "mostplayed" -> PlaylistDetailScreen(
                                        title = "Most Played",
                                        tracks = state.mostPlayed,
                                        favoriteTrackIds = state.favoriteTrackIds,
                                        isFavoritesList = false,
                                        onBack = { libraryDetail = null },
                                        onTrackClick = { track -> viewModel.onTrackClicked(track, state.mostPlayed) },
                                        onPlayAll = { viewModel.onPlayAll(state.mostPlayed) },
                                        shuffle = state.shuffle,
                                        onToggleShuffle = viewModel::onToggleShuffle,
                                        onToggleFavorite = viewModel::onToggleFavorite,
                                        onRemove = { viewModel.onRemoveFromMostPlayed(listOf(it)) },
                                        removeText = "Remove from Most Played",
                                        onRemoveSelection = { chosen ->
                                            viewModel.onRemoveFromMostPlayed(chosen)
                                            viewModel.onClearSelection()
                                        },
                                        onDelete = null,
                                        serverTargets = browsableSources,
                                        onSaveTrackToDevice = { track -> viewModel.onDownloadClicked(track) },
                                        onUploadTrackToServer = { track, baseUrl -> viewModel.onUploadPlaylistToServer(listOf(track), baseUrl) },
                                        onDeleteTrackFromServer = { track -> viewModel.onDeleteFromServer(track) },
                                        onDeleteTrackFromDevice = { track -> viewModel.onDeleteLocalCopy(track) },
                                        onAddToPlaylist = { addTarget = it },
                                        selectedTrackIds = state.selectedTrackIds,
                                        onToggleSelect = viewModel::onToggleSelectAnchored,
                                        onRangeSelect = { track, visible -> viewModel.onSelectRangeTo(track, visible) },
                                        onClearSelection = viewModel::onClearSelection,
                                        onSelectAll = viewModel::onSelectAll,
                                        onAddSelectionToPlaylist = { addSelection = true },
                                        onPickVersion = if (canFindVersions) { track -> viewModel.onPickVersion(track) } else null,
                                        onRefresh = { viewModel.onRefreshTracksMetadata(state.mostPlayed) },
                                        onRefreshAll = { viewModel.onRefreshTracksMetadata(state.mostPlayed, force = true) },
                                        currentTrackKey = nowKey,
                                        playingProgress = nowProgress,
                                        isOnDevice = isOnDevice,
                                        isOnServer = isOnServer,
                                        modifier = contentModifier
                                    )

                                    detail == "favorites" -> PlaylistDetailScreen(
                                        title = "Liked Songs",
                                        tracks = state.favorites,
                                        favoriteTrackIds = state.favoriteTrackIds,
                                        isFavoritesList = true,
                                        onBack = { libraryDetail = null },
                                        onTrackClick = { track -> viewModel.onTrackClicked(track, state.favorites) },
                                        onPlayAll = { viewModel.onPlayAll(state.favorites) },
                                        shuffle = state.shuffle,
                                        onToggleShuffle = viewModel::onToggleShuffle,
                                        onToggleFavorite = viewModel::onToggleFavorite,
                                        onRemove = viewModel::onToggleFavorite,
                                        onRemoveSelection = { chosen ->

                                            chosen.filter { it.id in state.favoriteTrackIds }
                                                .forEach(viewModel::onToggleFavorite)
                                            viewModel.onClearSelection()
                                        },
                                        onDelete = null,
                                        onMove = { from, to -> viewModel.onMoveFavorite(from, to) },
                                        serverTargets = browsableSources,
                                        onDownloadAll = { viewModel.onDownloadPlaylistToDevice(state.favorites, "Liked Songs") },
                                        onRedownloadAll = {
                                            viewModel.onDownloadPlaylistToDevice(state.favorites, "Liked Songs", force = true)
                                        },
                                        onRetryFailedDownloads = viewModel::onRetryFailedDownloads,
                                        failedDownloadCount = state.failedDownloadsFor("Liked Songs"),
                                        onUploadToServer = { baseUrl -> viewModel.onUploadPlaylistToServer(state.favorites, baseUrl, "Liked Songs") },
                                        onSaveTrackToDevice = { track -> viewModel.onDownloadClicked(track) },
                                        onUploadTrackToServer = { track, baseUrl -> viewModel.onUploadPlaylistToServer(listOf(track), baseUrl) },
                                        onDeleteTrackFromServer = { track -> viewModel.onDeleteFromServer(track) },
                                        onDeleteTrackFromDevice = { track -> viewModel.onDeleteLocalCopy(track) },
                                        onAddToPlaylist = { addTarget = it },
                                        selectedTrackIds = state.selectedTrackIds,
                                        onToggleSelect = viewModel::onToggleSelectAnchored,
                                        onRangeSelect = { track, visible -> viewModel.onSelectRangeTo(track, visible) },
                                        onClearSelection = viewModel::onClearSelection,
                                        onSelectAll = viewModel::onSelectAll,
                                        onAddSelectionToPlaylist = { addSelection = true },
                                        onPickVersion = if (canFindVersions) { track -> viewModel.onPickVersion(track) } else null,
                                        onRefresh = { viewModel.onRefreshTracksMetadata(state.favorites) },
                                        onRefreshAll = { viewModel.onRefreshTracksMetadata(state.favorites, force = true) },
                                        currentTrackKey = nowKey,
                                        playingProgress = nowProgress,
                                        isOnDevice = isOnDevice,
                                isOnServer = isOnServer,
                                        modifier = contentModifier
                                    )

                                    else -> {
                                        val playlist = state.playlists.firstOrNull { it.id == detail }
                                        if (playlist == null) {
                                            libraryDetail = null
                                        } else {
                                            PlaylistDetailScreen(
                                                title = playlist.name,
                                                tracks = playlist.tracks,
                                                favoriteTrackIds = state.favoriteTrackIds,
                                                isFavoritesList = false,
                                                onBack = { libraryDetail = null },
                                                onTrackClick = { track -> viewModel.onTrackClicked(track, playlist.tracks) },
                                                onPlayAll = { viewModel.onPlayAll(playlist.tracks) },
                                                shuffle = state.shuffle,
                                                onToggleShuffle = viewModel::onToggleShuffle,
                                                onToggleFavorite = viewModel::onToggleFavorite,
                                                onRemove = { viewModel.onRemoveFromPlaylist(playlist.id, it.id) },
                                                onRemoveSelection = { chosen ->
                                                    viewModel.onRemoveTracksFromPlaylist(playlist.id, chosen)
                                                    viewModel.onClearSelection()
                                                },
                                                onDelete = {
                                                    viewModel.onDeletePlaylist(playlist.id)
                                                    libraryDetail = null
                                                },
                                                onRename = { viewModel.onRenamePlaylist(playlist.id, it) },
                                                onMove = { from, to -> viewModel.onMovePlaylistTrack(playlist.id, from, to) },
                                                exportJson = { viewModel.exportPlaylistJson(playlist.id) },
                                                serverTargets = browsableSources,
                                                onDownloadAll = { viewModel.onDownloadPlaylistToDevice(playlist.tracks, playlist.name) },
                                                onRedownloadAll = {
                                                    viewModel.onDownloadPlaylistToDevice(playlist.tracks, playlist.name, force = true)
                                                },
                                                onRetryFailedDownloads = viewModel::onRetryFailedDownloads,
                                                failedDownloadCount = state.failedDownloadsFor(playlist.name),
                                                onUploadToServer = { baseUrl -> viewModel.onUploadPlaylistToServer(playlist.tracks, baseUrl, playlist.name) },
                                                onSaveTrackToDevice = { track -> viewModel.onDownloadClicked(track) },
                                                onUploadTrackToServer = { track, baseUrl -> viewModel.onUploadPlaylistToServer(listOf(track), baseUrl) },
                                                onDeleteTrackFromServer = { track -> viewModel.onDeleteFromServer(track) },
                                        onDeleteTrackFromDevice = { track -> viewModel.onDeleteLocalCopy(track) },
                                                onAddToPlaylist = { addTarget = it },
                                                selectedTrackIds = state.selectedTrackIds,
                                                onToggleSelect = viewModel::onToggleSelectAnchored,
                                                onRangeSelect = { track, visible -> viewModel.onSelectRangeTo(track, visible) },
                                                onClearSelection = viewModel::onClearSelection,
                                                onSelectAll = viewModel::onSelectAll,
                                                onAddSelectionToPlaylist = { addSelection = true },
                                        onPickVersion = if (canFindVersions) { track -> viewModel.onPickVersion(track) } else null,
                                                onRefresh = { viewModel.onRefreshTracksMetadata(playlist.tracks) },
                                                onRefreshAll = { viewModel.onRefreshTracksMetadata(playlist.tracks, force = true) },
                                                currentTrackKey = nowKey,
                                                playingProgress = nowProgress,
                                                isOnDevice = isOnDevice,
                                isOnServer = isOnServer,
                                                modifier = contentModifier
                                            )
                                        }
                                    }
                                }
                            }

                            Destination.Settings -> SettingsScreen(
                                extensionRepositories = extensionRepositories,
                                extensionSettingsFor = pluginManager::extensionSettings,
                                onSaveExtensionSettings = viewModel::onSaveExtensionSettings,
                                experimentalEnabled = state.experimentalPluginEnabled,
                                sources = state.sources,
                                backendTestStatus = state.backendTestStatus,
                                themes = state.themes,
                                selectedThemeId = state.selectedThemeId,
                                importStatus = state.importStatus,
                                playlistImportStatus = state.playlistImportStatus,
                                playlistImportPreview = state.playlistImportPreview,
                                onImportPlaylistLink = viewModel::onImportPlaylistLink,
                                onImportCsvPlaylist = { csv -> viewModel.onImportCsvPlaylist(csv) },
                                serverHost = state.serverHost,
                                servers = state.servers,
                                serverConfigStatus = state.serverConfigStatus,
                                onSaveServer = viewModel::onSaveServer,
                                onRemoveServer = viewModel::onRemoveServer,
                                onMoveServer = viewModel::onMoveServer,
                                rememberPasswords = state.rememberPasswords,
                                onToggleRememberPasswords = viewModel::onToggleRememberPasswords,
                                appVersion = APP_VERSION,
                    updateStatus = state.updateError,
                    updateBusy = state.updateBusy,
                    onCheckForUpdates = { viewModel.onCheckForUpdates(manual = true) },
                    autoSync = state.autoSyncEnabled,
                                onToggleAutoSync = viewModel::onToggleAutoSync,
                                themeSync = state.themeSyncEnabled,
                                onToggleThemeSync = viewModel::onToggleThemeSync,
                                playbackSync = state.playbackSyncEnabled,
                                onTogglePlaybackSync = viewModel::onTogglePlaybackSync,
                                localFolderLabel = state.localFolderLabel,
                                localFolderPath = state.localFolderPath,
                                localTrackCount = state.localTracks.size,
                                onPickLocalFolder = viewModel::onPickLocalFolder,
                                onRefreshLocal = viewModel::onRefreshLocalWithMetadata,
                                onRetagDownloads = viewModel::onRetagLocalDownloads,
                                onConfirmPlaylistImport = viewModel::onConfirmPlaylistImport,
                                onCancelPlaylistImport = viewModel::onCancelPlaylistImport,
                                onRetryUnmatchedImport = viewModel::onRetryUnmatchedImport,
                                onExperimentalToggle = viewModel::onExperimentalPluginToggled,
                                onToggleSource = viewModel::onToggleSource,
                                onInstallSource = viewModel::onInstallSource,
                                onUninstallSource = { id ->

                                    val origin = pluginManager.extensionOrigin(id)
                                    val name = state.sources.firstOrNull { it.id == id }?.name ?: "the extension"
                                    if (origin != null && extensionRepositories != null) extensionRepositories.uninstall(origin, name)
                                    else viewModel.onUninstallSource(id)
                                },
                                onRenameSource = viewModel::onRenameSource,
                                onUpdateSource = viewModel::onUpdateSource,
                                onTestBackend = viewModel::onTestBackend,
                                onSelectTheme = viewModel::onSelectTheme,
                                onInstallTheme = viewModel::onInstallTheme,
                                onUninstallTheme = viewModel::onUninstallTheme,
                                onImportPlugin = viewModel::onImportPlugin,
                                onExportPlugins = viewModel::exportPluginsJson,
                                sectionOrder = state.settingsSectionOrder,
                                locked = state.settingsLocked,
                                onReorder = viewModel::onReorderSettings,
                                onToggleLock = viewModel::onToggleSettingsLock,
                                highQualityArt = state.highQualityArt,
                                onHighQualityArtToggle = viewModel::onHighQualityArtToggled,
                                modifier = contentModifier
                            )

                            Destination.Support -> SupportScreen(
                                                                modifier = contentModifier
                            )
                        }
                    }

                    if (showNowPlaying) {
                        NowPlayingPanel(
                            track = state.currentTrack,
                            playback = state.playback,
                            onClose = { closePanel() }
                        )
                    }

                    if (showQueue) {
                        QueuePanel(
                            queue = state.queue,
                            currentIndex = state.queueIndex,
                            onPlay = { track -> viewModel.onTrackClicked(track, state.queue) },
                            onClose = { closePanel() }
                        )
                    }
                }

                TransferBar(
                    progress = state.transferProgress,
                    label = state.transferLabel?.let { base ->

                        if (state.transferQueueDepth > 0) {
                            "$base (+${state.transferQueueDepth} queued)"
                        } else base
                    },
                    onCancel = viewModel::cancelTransfer
                )
                DesktopPlaybackBar(
                    track = state.currentTrack,
                    playback = state.playback,
                    positionMs = state.positionMs,
                    durationMs = state.durationMs,
                    hasNext = state.queue.isNotEmpty() && (
                        state.queueIndex in 0 until (state.queue.size - 1) ||
                            state.shuffle || state.repeatMode != RepeatMode.OFF
                        ),
                    hasPrevious = state.hasPrevious || state.positionMs > 3_000,
                    shuffle = state.shuffle,
                    repeatMode = state.repeatMode,
                    isFavorite = state.currentTrack?.id?.let { it in state.favoriteTrackIds } ?: false,
                    canDownload = state.currentTrack?.downloadable == true,
                    alreadyOnDevice = state.currentTrack?.let(isOnDevice) ?: false,
                    alreadyOnServer = state.currentTrack?.let(isOnServer) ?: false,
                    serverTargets = browsableSources,
                    sleepTimerRemainingMs = state.sleepTimerRemainingMs,
                    sleepAtEndOfTrack = state.sleepAtEndOfTrack,
                    onSetSleepTimer = viewModel::onSetSleepTimer,
                    onToggleSleepAtEnd = viewModel::onToggleSleepAtEndOfTrack,
                    volume = state.volume,
                    nowPlayingOpen = showNowPlaying,
                    queueOpen = showQueue,
                    fullscreen = fullscreen,
                    onOpenMiniPlayer = onOpenMiniPlayer,
                    onToggleFullscreen = { fullscreen = !fullscreen },
                    onToggleNowPlaying = {
                        if (sidePanel == SidePanel.NowPlaying) closePanel()
                        else showPanel(SidePanel.NowPlaying)
                    },
                    onToggleQueue = {
                        if (sidePanel == SidePanel.Queue) closePanel()
                        else showPanel(SidePanel.Queue)
                    },
                    onToggleShuffle = viewModel::onToggleShuffle,
                    onCycleRepeat = viewModel::onCycleRepeat,
                    onToggleFavorite = { state.currentTrack?.let(viewModel::onToggleFavorite) },
                    onAddToPlaylist = { addTarget = state.currentTrack },
                    onDownload = { state.currentTrack?.let(viewModel::onDownloadClicked) },
                    onUploadToServer = { baseUrl ->
                        state.currentTrack?.let { viewModel.onUploadPlaylistToServer(listOf(it), baseUrl) }
                    },
                    onVolumeChange = { v -> viewModel.onSetVolume(v) },
                    onPlay = viewModel::onPlayClicked,
                    onPause = viewModel::onPauseClicked,
                    onNext = viewModel::onNext,
                    onPrevious = viewModel::onPrevious,
                    onSeek = viewModel::onSeek
                )
            }
        }

        if (fullscreen && state.currentTrack != null) {
            FullscreenPlayer(
                track = state.currentTrack,
                playback = state.playback,
                positionMs = state.positionMs,
                durationMs = state.durationMs,
                hasNext = state.queue.isNotEmpty() && (
                    state.queueIndex in 0 until (state.queue.size - 1) ||
                        state.shuffle || state.repeatMode != RepeatMode.OFF
                    ),
                hasPrevious = state.hasPrevious || state.positionMs > 3_000,
                shuffle = state.shuffle,
                repeatMode = state.repeatMode,
                isFavorite = state.currentTrack?.id?.let { it in state.favoriteTrackIds } ?: false,
                volume = state.volume,
                onVolumeChange = { v -> viewModel.onSetVolume(v) },
                onExit = { fullscreen = false },
                onPlay = viewModel::onPlayClicked,
                onPause = viewModel::onPauseClicked,
                onNext = viewModel::onNext,
                onPrevious = viewModel::onPrevious,
                onSeek = viewModel::onSeek,
                onToggleShuffle = viewModel::onToggleShuffle,
                onCycleRepeat = viewModel::onCycleRepeat,
                onToggleFavorite = { state.currentTrack?.let(viewModel::onToggleFavorite) }
            )
        }

        state.availableUpdate
            ?.takeIf { !state.updateDismissed && !state.updateDialogOpen && !fullscreen }
            ?.let { update ->
                UpdateBanner(
                    update = update,
                    busy = state.updateBusy,
                    error = state.updateError,
                    onInstall = viewModel::onInstallUpdate,
                    onIgnore = viewModel::onDismissUpdate,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 16.dp, bottom = 104.dp)
                )
            }
        }

        if (addSelection) {
            val pool = state.favorites + state.mostPlayed + state.browseTracks +
                state.localTracks + state.playlists.flatMap { it.tracks }
            val chosen = pool.distinctBy { it.id }.filter { it.id in state.selectedTrackIds }
            AddToPlaylistDialog(
                playlists = state.playlists,
                tracks = chosen,
                onDismiss = { addSelection = false },
                onAdd = { id, tracks -> viewModel.onAddTracksToPlaylist(id, tracks) },
                onRemove = { id, tracks -> viewModel.onRemoveTracksFromPlaylist(id, tracks) },
                onCreate = { name, tracks ->
                    viewModel.onCreatePlaylistWith(name, tracks)

                    viewModel.onClearSelection()
                    addSelection = false
                }
            )
        }

        addTarget?.let { target ->
            AddToPlaylistDialog(
                playlists = state.playlists,
                tracks = listOf(target),
                onDismiss = { addTarget = null },
                onAdd = { id, tracks -> viewModel.onAddTracksToPlaylist(id, tracks) },
                onRemove = { id, tracks -> viewModel.onRemoveTracksFromPlaylist(id, tracks) },
                onCreate = { name, tracks -> viewModel.onCreatePlaylistWith(name, tracks) }
            )
        }

        moveTarget?.let { target ->

            val folders = if (target.source == TrackSource.LOCAL) {
                state.localTracks.map { it.localFolder }
                    .filter { it.isNotBlank() && it != target.localFolder }
                    .distinct().sorted()
            } else {
                state.browsePlaylists
                    .filter { it.id != "__all__" }
                    .map { it.name }
                    .distinct().sorted()
            }
            MoveToFolderDialog(
                trackTitle = target.title,
                folders = folders,
                onMove = { folder ->
                    viewModel.onMoveTrackToFolder(target, folder)
                    moveTarget = null
                },
                onDismiss = { moveTarget = null }
            )
        }

        state.versionPickerFor?.let { original ->
            VersionPickerDialog(
                original = original,
                options = state.versionOptions,
                loading = state.versionLoading,
                playingKey = state.currentTrack?.matchKey(),
                isPlaying = state.playback == PlaybackState.PLAYING ||
                    state.playback == PlaybackState.BUFFERING,
                onPreview = { opt -> viewModel.onPreviewVersion(opt) },
                onStopPreview = viewModel::onPauseClicked,
                onPick = { chosen -> viewModel.onChooseVersion(chosen) },
                onUseLink = { url -> viewModel.onChooseVersionFromLink(url) },
                onPreviewLink = { url -> viewModel.onPreviewVersionFromLink(url) },
                onSearch = { q -> viewModel.onSearchVersions(q) },
                onDismiss = viewModel::onDismissVersionPicker
            )
        }
        }
        }
    }
}

@Composable
private fun VersionPickerDialog(
    original: Track,
    options: List<Track>,
    loading: Boolean,
    playingKey: String?,
    isPlaying: Boolean,
    onPreview: (Track) -> Unit,
    onStopPreview: () -> Unit,
    onPick: (Track) -> Unit,

    onUseLink: (String) -> Unit,

    onPreviewLink: (String) -> Unit,
    onSearch: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var linkText by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf("")
    }
    var searchText by androidx.compose.runtime.remember(original.id) {
        androidx.compose.runtime.mutableStateOf(
            listOf(original.artist, original.title).filter { it.isNotBlank() }.joinToString(" ")
        )
    }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Close") }
        },
        title = { Text("Other versions") },
        text = {
            Column {
                Text(
                    "${original.title} - ${original.artist}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "▶ preview · Use = replace this song with that version",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.OutlinedTextField(
                        value = searchText,
                        onValueChange = { searchText = it },
                        singleLine = true,
                        label = { Text("Search") },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            imeAction = androidx.compose.ui.text.input.ImeAction.Search
                        ),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                            onSearch = { onSearch(searchText) }
                        ),
                        modifier = Modifier.weight(1f)
                    )
                    androidx.compose.material3.TextButton(
                        onClick = { onSearch(searchText) },
                        enabled = searchText.isNotBlank() && !loading
                    ) { Text("Search") }
                }
                Spacer(Modifier.height(8.dp))
                when {
                    loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.CircularProgressIndicator(
                            modifier = Modifier.size(20.dp)
                        )
                        Text("  Finding versions…")
                    }
                    options.isEmpty() -> Text("Nothing found. Try different words above.")
                    else -> androidx.compose.foundation.lazy.LazyColumn(
                        modifier = Modifier.heightIn(max = 360.dp)
                    ) {
                        items(options, key = { it.id }) { opt ->
                            val previewing = playingKey != null && opt.matchKey() == playingKey
                            androidx.compose.material3.ListItem(
                                leadingContent = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        androidx.compose.material3.IconButton(
                                            onClick = {
                                                if (previewing && isPlaying) onStopPreview()
                                                else onPreview(opt)
                                            }
                                        ) {
                                            Icon(
                                                if (previewing && isPlaying)
                                                    Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                                contentDescription = if (previewing && isPlaying)
                                                    "Pause preview" else "Preview",
                                                tint = if (previewing) MaterialTheme.colorScheme.primary
                                                else MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        TrackThumbnail(opt.thumbnailUrl, size = 40.dp)
                                    }
                                },
                                headlineContent = {
                                    Text(opt.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                },
                                supportingContent = {
                                    Text(
                                        listOf(opt.artist, opt.durationLabel)
                                            .filter { it.isNotBlank() }.joinToString(" · "),
                                        maxLines = 1, overflow = TextOverflow.Ellipsis
                                    )
                                },
                                trailingContent = {
                                    androidx.compose.material3.TextButton(onClick = { onPick(opt) }) {
                                        Text("Use")
                                    }
                                }
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.OutlinedTextField(
                        value = linkText,
                        onValueChange = { linkText = it },
                        singleLine = true,
                        label = { Text("Or paste a link") },
                        modifier = Modifier.weight(1f)
                    )
                    androidx.compose.material3.IconButton(
                        onClick = { onPreviewLink(linkText.trim()) },
                        enabled = linkText.isNotBlank()
                    ) {
                        Icon(
                            Icons.Filled.PlayArrow,
                            contentDescription = "Preview link",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    androidx.compose.material3.TextButton(
                        onClick = {
                            onUseLink(linkText.trim())
                            linkText = ""
                        },
                        enabled = linkText.isNotBlank()
                    ) { Text("Use") }
                }
            }
        }
    )
}

@Composable
private fun TransferBar(progress: Float?, label: String?, onCancel: () -> Unit) {
    if (progress == null) return
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, tonalElevation = 3.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                if (!label.isNullOrBlank()) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
                if (progress < 0f) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            if (progress < 1f) {
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    if (ms <= 0L) return "0:00"
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable

internal fun DesktopPlaybackBar(
    track: Track?,
    playback: PlaybackState,
    positionMs: Long,
    durationMs: Long,
    hasNext: Boolean,
    hasPrevious: Boolean,
    shuffle: Boolean,
    repeatMode: RepeatMode,
    isFavorite: Boolean,
    canDownload: Boolean,
    alreadyOnDevice: Boolean,
    alreadyOnServer: Boolean,
    serverTargets: List<SourcePlugin>,
    sleepTimerRemainingMs: Long?,
    sleepAtEndOfTrack: Boolean,
    onSetSleepTimer: (Int?) -> Unit,
    onToggleSleepAtEnd: () -> Unit,
    volume: Float,
    nowPlayingOpen: Boolean,
    queueOpen: Boolean,
    fullscreen: Boolean,
    onOpenMiniPlayer: (() -> Unit)?,
    onToggleFullscreen: () -> Unit,
    onToggleNowPlaying: () -> Unit,
    onToggleQueue: () -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onToggleFavorite: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onDownload: () -> Unit,
    onUploadToServer: (String) -> Unit,
    onVolumeChange: (Float) -> Unit,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Long) -> Unit
) {
    val playing = playback == PlaybackState.PLAYING || playback == PlaybackState.BUFFERING
    var overflowOpen by remember { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
      BoxWithConstraints {
        val compact = maxWidth < 1100.dp
        val leftWidth = if (compact) 240.dp else 320.dp
        val rightWidth = if (compact) 330.dp else 380.dp

        val showVolumePercent = !compact
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.width(leftWidth),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TrackThumbnail(track?.thumbnailUrl ?: "", size = 48.dp)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = track?.title ?: "Nothing playing",
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = track?.artist ?: "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(onClick = onToggleFavorite, enabled = track != null) {
                    Icon(
                        if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        contentDescription = if (isFavorite) "Remove from Liked" else "Add to Liked",
                        tint = if (isFavorite) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Box {
                    IconButton(onClick = { overflowOpen = true }, enabled = track != null) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "More")
                    }
                    DropdownMenu(expanded = overflowOpen, onDismissRequest = { overflowOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Add to playlist") },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = null) },
                            onClick = { overflowOpen = false; onAddToPlaylist() }
                        )

                        if (canDownload && !alreadyOnDevice) {
                            DropdownMenuItem(
                                text = { Text("Save to device") },
                                leadingIcon = { Icon(Icons.Filled.Download, contentDescription = null) },
                                onClick = { overflowOpen = false; onDownload() }
                            )
                        }
                        if (!alreadyOnServer) {
                            serverTargets.forEach { target ->
                                DropdownMenuItem(
                                    text = { Text("Upload to ${target.name}") },
                                    leadingIcon = { Icon(Icons.Filled.Cloud, contentDescription = null) },
                                    onClick = { overflowOpen = false; onUploadToServer(target.baseUrl) }
                                )
                            }
                        }

                        listOf(15, 30, 60).forEach { min ->
                            DropdownMenuItem(
                                text = { Text("Sleep in $min min") },
                                leadingIcon = { Icon(Icons.Filled.Bedtime, contentDescription = null) },
                                onClick = { overflowOpen = false; onSetSleepTimer(min) }
                            )
                        }
                        DropdownMenuItem(
                            text = {
                                Text(if (sleepAtEndOfTrack) "Don't sleep at end of track" else "Sleep at end of track")
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Filled.Bedtime,
                                    contentDescription = null,
                                    tint = if (sleepAtEndOfTrack) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
                            onClick = { overflowOpen = false; onToggleSleepAtEnd() }
                        )
                        if (sleepTimerRemainingMs != null) {
                            DropdownMenuItem(
                                text = { Text("Cancel sleep timer (${sleepTimerRemainingMs / 60000}m left)") },
                                leadingIcon = { Icon(Icons.Filled.Bedtime, contentDescription = null) },
                                onClick = { overflowOpen = false; onSetSleepTimer(null) }
                            )
                        }
                    }
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onToggleShuffle) {
                        Icon(
                            Icons.Filled.Shuffle,
                            contentDescription = if (shuffle) "Shuffle on" else "Shuffle off",
                            tint = if (shuffle) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                        )
                    }
                    IconButton(onClick = onPrevious, enabled = hasPrevious) {
                        Icon(Icons.Filled.SkipPrevious, contentDescription = "Previous")
                    }
                    IconButton(
                        onClick = { if (playing) onPause() else onPlay() },
                        enabled = track != null
                    ) {
                        Icon(
                            if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (playing) "Pause" else "Play"
                        )
                    }
                    IconButton(onClick = onNext, enabled = hasNext) {
                        Icon(Icons.Filled.SkipNext, contentDescription = "Next")
                    }
                    IconButton(onClick = onCycleRepeat) {
                        Icon(
                            if (repeatMode == RepeatMode.ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                            contentDescription = "Repeat",
                            tint = if (repeatMode == RepeatMode.OFF)
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                            else MaterialTheme.colorScheme.primary
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = formatTime(positionMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    ThinSlider(
                        value = if (durationMs > 0L) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f,
                        onValueChange = { frac ->
                            if (durationMs > 0L) onSeek((frac * durationMs).toLong())
                        },
                        enabled = track != null && durationMs > 0L,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = formatTime(durationMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Row(
                modifier = Modifier.width(rightWidth),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onToggleQueue) {
                    Icon(
                        Icons.AutoMirrored.Filled.QueueMusic,
                        contentDescription = "Queue",
                        tint = if (queueOpen) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                var levelBeforeMute by remember { mutableStateOf(volume.takeIf { it > 0f } ?: 0.5f) }
                val muted = volume <= 0f
                IconButton(
                    onClick = {
                        if (muted) {
                            onVolumeChange(levelBeforeMute)
                        } else {
                            levelBeforeMute = volume
                            onVolumeChange(0f)
                        }
                    },

                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        if (muted) Icons.AutoMirrored.Filled.VolumeOff
                        else Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = if (muted) "Unmute" else "Mute",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                ThinSlider(
                    value = volume,
                    onValueChange = onVolumeChange,
                    modifier = Modifier.weight(1f),
                    scrollStep = VOLUME_STEP
                )

                if (showVolumePercent) {
                    Text(
                        text = "${(volume * 100).roundToInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.End,
                        modifier = Modifier.width(34.dp)
                    )
                }
                IconButton(onClick = onToggleNowPlaying, enabled = track != null) {
                    Icon(
                        if (nowPlayingOpen) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowUp,
                        contentDescription = if (nowPlayingOpen) "Hide now playing" else "Show now playing"
                    )
                }
                if (onOpenMiniPlayer != null) {
                    IconButton(onClick = onOpenMiniPlayer, enabled = track != null) {
                        Icon(Icons.Filled.PictureInPictureAlt, contentDescription = "Mini player")
                    }
                }
                IconButton(onClick = onToggleFullscreen, enabled = track != null) {
                    Icon(
                        if (fullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                        contentDescription = if (fullscreen) "Exit fullscreen" else "Fullscreen"
                    )
                }
            }
        }
      }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FullscreenPlayer(
    track: Track?,
    playback: PlaybackState,
    positionMs: Long,
    durationMs: Long,
    hasNext: Boolean,
    hasPrevious: Boolean,
    shuffle: Boolean,
    repeatMode: RepeatMode,
    isFavorite: Boolean,
    volume: Float,
    onVolumeChange: (Float) -> Unit,
    onExit: () -> Unit,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Long) -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onToggleFavorite: () -> Unit
) {
    val playing = playback == PlaybackState.PLAYING || playback == PlaybackState.BUFFERING
    val art = track?.thumbnailUrl.orEmpty()

    val onArt = if (art.isNotBlank()) Color.White else MaterialTheme.colorScheme.onBackground
    val subOnArt = if (art.isNotBlank()) Color.White.copy(alpha = 0.72f)
        else MaterialTheme.colorScheme.onSurfaceVariant
    val dimOnArt = if (art.isNotBlank()) Color.White.copy(alpha = 0.5f)
        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = if (art.isBlank()) MaterialTheme.colorScheme.onBackground else Color.White
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (art.isNotBlank()) {
                SubcomposeAsyncImage(
                    model = ImageRequest.Builder(LocalPlatformContext.current)
                        .data(artModel(art))
                        .crossfade(true)
                        .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().blur(60.dp)
                )
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(
                            0f to Color.Black.copy(alpha = 0.45f),
                            1f to Color.Black.copy(alpha = 0.75f)
                        )
                    )
                )
            }

            Row(
                modifier = Modifier.align(Alignment.TopEnd).padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onToggleFavorite) {
                    Icon(
                        if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        contentDescription = if (isFavorite) "Remove from Liked" else "Add to Liked",
                        tint = if (isFavorite) MaterialTheme.colorScheme.primary else onArt
                    )
                }
                IconButton(onClick = onExit) {
                    Icon(Icons.Filled.FullscreenExit, contentDescription = "Exit fullscreen", tint = onArt)
                }
            }

            Column(
                modifier = Modifier.align(Alignment.Center).padding(24.dp).width(560.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                TrackThumbnail(track?.thumbnailUrl ?: "", size = 360.dp)
                Spacer(Modifier.height(24.dp))
                Text(
                    text = track?.title ?: "Nothing playing",
                    style = MaterialTheme.typography.headlineSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = track?.artist ?: "",
                    style = MaterialTheme.typography.titleMedium,
                    color = subOnArt,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(24.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = formatTime(positionMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = subOnArt
                    )
                    ThinSlider(
                        value = if (durationMs > 0L) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f,
                        onValueChange = { frac -> if (durationMs > 0L) onSeek((frac * durationMs).toLong()) },
                        enabled = track != null && durationMs > 0L,
                        modifier = Modifier.weight(1f),

                        trackColor = Color.White.copy(alpha = 0.3f)
                    )
                    Text(
                        text = formatTime(durationMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = subOnArt
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onToggleShuffle) {
                        Icon(
                            Icons.Filled.Shuffle,
                            contentDescription = if (shuffle) "Shuffle on" else "Shuffle off",
                            tint = if (shuffle) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                        )
                    }
                    IconButton(onClick = onPrevious, enabled = hasPrevious) {
                        Icon(Icons.Filled.SkipPrevious, contentDescription = "Previous")
                    }
                    IconButton(
                        onClick = { if (playing) onPause() else onPlay() },
                        enabled = track != null
                    ) {
                        Icon(
                            if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (playing) "Pause" else "Play"
                        )
                    }
                    IconButton(onClick = onNext, enabled = hasNext) {
                        Icon(Icons.Filled.SkipNext, contentDescription = "Next")
                    }
                    IconButton(onClick = onCycleRepeat) {
                        Icon(
                            if (repeatMode == RepeatMode.ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                            contentDescription = "Repeat",
                            tint = if (repeatMode == RepeatMode.OFF) dimOnArt
                            else MaterialTheme.colorScheme.primary
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))

                var levelBeforeMute by remember { mutableStateOf(volume.takeIf { it > 0f } ?: 0.5f) }
                val muted = volume <= 0f
                Row(
                    modifier = Modifier.width(260.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = {
                            if (muted) {
                                onVolumeChange(levelBeforeMute)
                            } else {
                                levelBeforeMute = volume
                                onVolumeChange(0f)
                            }
                        }
                    ) {
                        Icon(
                            if (muted) Icons.AutoMirrored.Filled.VolumeOff
                            else Icons.AutoMirrored.Filled.VolumeUp,
                            contentDescription = if (muted) "Unmute" else "Mute",
                            tint = onArt
                        )
                    }
                    ThinSlider(
                        value = volume,
                        onValueChange = onVolumeChange,
                        modifier = Modifier.weight(1f),

                        trackColor = Color.White.copy(alpha = 0.3f),

                        scrollStep = VOLUME_STEP
                    )

                    Text(
                        text = "${(volume * 100).roundToInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = subOnArt,
                        textAlign = TextAlign.End,
                        modifier = Modifier.width(34.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun NowPlayingPanel(
    track: Track?,
    playback: PlaybackState,
    onClose: () -> Unit
) {
    val art = track?.thumbnailUrl.orEmpty()
    Surface(
        modifier = Modifier.width(360.dp).fillMaxHeight(),
        tonalElevation = 2.dp,
        color = MaterialTheme.colorScheme.surface,
        contentColor = if (art.isBlank()) MaterialTheme.colorScheme.onSurface else Color.White
    ) {
        Box(Modifier.fillMaxSize()) {
            if (art.isNotBlank()) {
                SubcomposeAsyncImage(
                    model = ImageRequest.Builder(LocalPlatformContext.current)
                        .data(artModel(art))
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
            }
            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Now Playing",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onClose) {
                        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Close")
                    }
                }
                Spacer(Modifier.height(16.dp))
                TrackThumbnail(track?.thumbnailUrl ?: "", size = 320.dp)
                Spacer(Modifier.height(20.dp))
                Text(
                    text = track?.title ?: "Nothing playing",
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = track?.artist ?: "",
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (art.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant
                    else Color.White.copy(alpha = 0.75f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (track != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = playback.name,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (art.isBlank()) MaterialTheme.colorScheme.primary
                        else Color.White.copy(alpha = 0.9f)
                    )
                }
            }
        }
    }
}

@Composable
private fun QueuePanel(
    queue: List<Track>,
    currentIndex: Int,
    onPlay: (Track) -> Unit,
    onClose: () -> Unit
) {
    Surface(
        modifier = Modifier.width(360.dp).fillMaxHeight(),
        tonalElevation = 2.dp,
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Queue",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Close queue")
                }
            }
            HorizontalDivider()
            if (queue.isEmpty()) {
                Text(
                    text = "Nothing queued.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp)
                )
            } else {
                val offset = currentIndex.coerceAtLeast(0)
                val upNext = queue.drop(offset)
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    itemsIndexed(upNext, key = { i, t -> "${i + offset}_${t.id}" }) { i, track ->
                        val isCurrent = (i + offset) == currentIndex
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .clickable { onPlay(track) }
                                .padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TrackThumbnail(track.thumbnailUrl, size = 40.dp)
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = track.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = if (isCurrent) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = track.artist,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
