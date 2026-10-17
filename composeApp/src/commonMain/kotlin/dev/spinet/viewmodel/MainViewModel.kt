package dev.spinet.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import dev.spinet.AppBranding
import dev.spinet.audio.AudioPlayer
import dev.spinet.audio.PlaybackState
import dev.spinet.audio.TransportCommand
import dev.spinet.data.ServerAccess
import dev.spinet.data.BrowsePlaylistDto
import dev.spinet.data.ImportedSource
import dev.spinet.data.ImportedTheme
import dev.spinet.data.NetworkRepository
import dev.spinet.data.nowEpochMillis
import dev.spinet.data.PlaylistImportResult
import dev.spinet.data.PluginBundle
import dev.spinet.util.openUrlExternally
import dev.spinet.data.SearchUnavailableException
import dev.spinet.download.DownloadLedger
import dev.spinet.download.DownloadResult
import dev.spinet.download.FileDownloader
import dev.spinet.download.SeedEntry
import dev.spinet.download.UploadLedger
import dev.spinet.library.LibraryRepository
import dev.spinet.local.LocalFolder
import dev.spinet.local.defaultLocalFolder
import dev.spinet.local.deleteLocalTrack
import dev.spinet.local.embedLocalMetadata
import dev.spinet.local.isLocalFileReadable
import dev.spinet.local.listLocalTracks
import dev.spinet.local.moveLocalTrack
import dev.spinet.local.readLocalTrackBytes
import dev.spinet.model.NowPlayingState
import dev.spinet.model.PlaylistSyncState
import dev.spinet.model.Playlist
import dev.spinet.data.FIRST_SERVER_ID
import dev.spinet.data.forgetSignIn
import dev.spinet.data.saveSignIn
import dev.spinet.data.signInFor
import dev.spinet.data.forgetSavedPasswordsIfNotRemembered
import dev.spinet.data.serversNeedingPassword
import dev.spinet.data.setRememberServerPasswords
import dev.spinet.model.SourceKind
import dev.spinet.model.SourcePlugin
import dev.spinet.model.ThemeSpec
import dev.spinet.model.Track
import dev.spinet.model.TrackSource
import dev.spinet.model.looseKey
import dev.spinet.model.matchKey
import dev.spinet.model.songMatchKey
import dev.spinet.plugins.PluginManager
import dev.spinet.recognition.CaptureSource
import dev.spinet.recognition.IdentifyPhase
import dev.spinet.recognition.RecognizedSong
import dev.spinet.recognition.createAudioCapture
import dev.spinet.settings.SettingsRepository
import dev.spinet.theme.ThemeRegistry
import dev.spinet.util.AudioContainer
import dev.spinet.update.UpdateInstaller
import dev.spinet.update.UpdateChecker
import dev.spinet.update.AvailableUpdate
import dev.spinet.local.isUsableArt
import dev.spinet.local.portableArt

private const val SERVER_IMPORTED_FOLDER = "Imported"

enum class RepeatMode { OFF, ALL, ONE }

data class ServerEntry(
    val id: String,
    val name: String,
    val baseUrl: String,
    val username: String,
    val password: String,
    val enabled: Boolean
)

data class AppUiState(
    val searchQuery: String = "",
    val searchResults: List<Track> = emptyList(),
    val isSearching: Boolean = false,
    val searchError: String? = null,

    val identifyPhase: IdentifyPhase = IdentifyPhase.IDLE,
    val identifyResult: RecognizedSong? = null,
    val identifyError: String? = null,
    val identifySources: List<CaptureSource> = emptyList(),
    val downloadStatus: String? = null,

    val failedDownloads: List<Track> = emptyList(),

    val failedDownloadsFolder: String = "",
    val playlists: List<Playlist> = emptyList(),
    val favorites: List<Track> = emptyList(),
    val favoriteTrackIds: Set<String> = emptySet(),

    val serverLibraryKeys: Set<String> = emptySet(),
    val currentTrack: Track? = null,

    val queue: List<Track> = emptyList(),
    val queueIndex: Int = -1,

    val sourceQueue: List<Track> = emptyList(),
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val shuffle: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val experimentalPluginEnabled: Boolean = false,
    val sources: List<SourcePlugin> = emptyList(),
    val backendTestStatus: String? = null,
    val themes: List<ThemeSpec> = emptyList(),
    val selectedThemeId: String = "",
    val importStatus: String? = null,
    val playlistImportStatus: String? = null,
    val playlistImportPreview: PlaylistImportResult? = null,

    val versionPickerFor: Track? = null,
    val versionOptions: List<Track> = emptyList(),
    val versionLoading: Boolean = false,
    val playback: PlaybackState = PlaybackState.IDLE,

    val browsePlaylists: List<BrowsePlaylistDto> = emptyList(),

    val browseTracks: List<Track> = emptyList(),
    val browseLoading: Boolean = false,
    val browseError: String? = null,

    val localFolderLabel: String = "",
    val localTracks: List<Track> = emptyList(),

    val localFolderPath: String = "",
    val localLoading: Boolean = false,

    val serverHost: String = "",

    val autoSyncEnabled: Boolean = false,

    val themeSyncEnabled: Boolean = true,

    val selectedTrackIds: Set<String> = emptySet(),

    val availableUpdate: AvailableUpdate? = null,

    val updateDismissed: Boolean = false,

    val updateDialogOpen: Boolean = false,

    val updateBusy: Boolean = false,

    val updateError: String? = null,

    val playbackSyncEnabled: Boolean = true,

    val servers: List<ServerEntry> = emptyList(),
    val rememberPasswords: Boolean? = null,
    val passwordPrompt: ServerEntry? = null,
    val passwordPromptError: String? = null,
    val serverConfigStatus: String? = null,

    val transferProgress: Float? = null,
    val transferLabel: String? = null,

    val transferQueueDepth: Int = 0,

    val settingsSectionOrder: List<String> = emptyList(),
    val settingsLocked: Boolean = true,

    val highQualityArt: Boolean = true,

    val sleepTimerRemainingMs: Long? = null,

    val sleepAtEndOfTrack: Boolean = false,

    val searchHistory: List<String> = emptyList(),

    val mostPlayed: List<Track> = emptyList(),

    val playCounts: Map<String, Int> = emptyMap(),

    val hasPrevious: Boolean = false,

    val volume: Float = 1f
) {
    fun failedDownloadsFor(folder: String): Int =
        if (failedDownloadsFolder == folder) failedDownloads.size else 0
}

@kotlinx.serialization.Serializable
data class PlayStat(val count: Int, val track: Track)

private fun List<Track>.distinctById(): List<Track> {
    val seen = HashSet<String>()
    return filter { it.id.isBlank() || seen.add(it.id) }
}

private fun collapseStats(stats: MutableMap<String, PlayStat>): MutableMap<String, PlayStat> {
    val byId = HashMap<String, String>()
    val out = LinkedHashMap<String, PlayStat>()
    for ((key, stat) in stats.entries.sortedByDescending { it.value.count }) {
        val id = stat.track.id
        if (id.isBlank()) {
            out[key] = stat
            continue
        }
        val kept = byId[id]
        if (kept == null) {
            byId[id] = key
            out[key] = stat
        } else {
            val existing = out.getValue(kept)
            out[kept] = existing.copy(count = existing.count + stat.count)
        }
    }
    return out
}

@kotlinx.serialization.Serializable
private data class SessionSnapshot(
    val queue: List<Track> = emptyList(),
    val index: Int = -1,
    val positionMs: Long = 0L,
    val shuffle: Boolean = false,
    val repeat: RepeatMode = RepeatMode.OFF,
    val sourceQueue: List<Track> = emptyList(),

    val history: List<Track> = emptyList(),

    val updatedAt: Long = 0L
)

class MainViewModel(
    private val audioPlayer: AudioPlayer,
    private val repository: NetworkRepository,
    private val settingsRepository: SettingsRepository,
    private val pluginManager: PluginManager,
    private val themeRegistry: ThemeRegistry,
    private val fileDownloader: FileDownloader,
    private val libraryRepository: LibraryRepository,

    private val updateInstaller: UpdateInstaller? = null
) : ViewModel() {
    private val downloadLedger = DownloadLedger()
    private val uploadLedger = UploadLedger()

    private val _uiState = MutableStateFlow(
        AppUiState(
            playlists = libraryRepository.playlists(),
            favorites = libraryRepository.favorites(),
            favoriteTrackIds = libraryRepository.favorites().map { it.id }.toSet(),
            experimentalPluginEnabled = settingsRepository.experimentalPluginEngineEnabled,
            sources = pluginManager.all(),
            themes = themeRegistry.all(),
            selectedThemeId = themeRegistry.selectedId(),
            localFolderLabel = settingsRepository.localFolderLabel,
            localFolderPath = settingsRepository.localFolderUri,
            serverHost = libraryServerUrl().orEmpty(),
            servers = serverEntries(),
            settingsSectionOrder = settingsRepository.settingsOrderCsv
                .split(",").map { it.trim() }.filter { it.isNotBlank() },
            settingsLocked = settingsRepository.settingsLocked,
            autoSyncEnabled = settingsRepository.syncAutoEnabled,
            themeSyncEnabled = settingsRepository.syncThemeEnabled,
            playbackSyncEnabled = settingsRepository.syncPlaybackEnabled,
            highQualityArt = settingsRepository.highQualityArtEnabled,
            searchHistory = settingsRepository.searchHistoryRaw
                .split("\n").map { it.trim() }.filter { it.isNotBlank() }
        )
    )
    val uiState: StateFlow<AppUiState> = _uiState.asStateFlow()

    private companion object {
        const val MAX_FAILED_IN_A_ROW = 3

        val SERVER_DOWN_FOR = kotlin.time.Duration.parse("2m")

        const val UPLOAD_RETRY_DELAY_MS = 3_000L

        const val SERVER_RECHECK_MS = 30_000L

        val UPLOAD_MARK_KEPT = kotlin.time.Duration.parse("10m")

        const val QUEUE_WINDOW_BEHIND = 10

        const val QUEUE_WINDOW_TOTAL = 60

        const val DEVICE_NAME = "desktop"
    }

    private var searchJob: Job? = null

    private val statsJson = Json { ignoreUnknownKeys = true }

    private val HISTORY_WINDOW = 50

    private val playHistory = ArrayDeque<Track>()
    private val playStats: MutableMap<String, PlayStat> = collapseStats(
        runCatching {
            statsJson.decodeFromString<MutableMap<String, PlayStat>>(
                settingsRepository.playStatsJson.ifBlank { "{}" }
            )
        }.getOrDefault(mutableMapOf())
    )

    private val sessionJson = Json { ignoreUnknownKeys = true }

    private var needsRestore = false
    private var restoredPositionMs = 0L
    private var lastSessionSaveMs = 0L

    private var pendingResumeSeekMs = 0L

    private fun playbackSyncUrl(): String? =
        if (settingsRepository.syncPlaybackEnabled) libraryServerUrl() else null

    private fun saveSession() {
        val s = _uiState.value
        if (s.currentTrack == null || s.queue.isEmpty()) return
        val now = nowEpochMillis()
        val snap = SessionSnapshot(
            queue = s.queue,
            index = s.queueIndex,
            positionMs = s.positionMs,
            shuffle = s.shuffle,
            repeat = s.repeatMode,
            sourceQueue = s.sourceQueue,
            history = historyWindow(),
            updatedAt = now
        )
        runCatching { settingsRepository.lastSessionJson = sessionJson.encodeToString(snap) }
        pushNowPlayingToServer(s.currentTrack!!, s.positionMs, s.durationMs, now)
    }

    private fun pushNowPlayingToServer(track: Track, positionMs: Long, durationMs: Long, updatedAt: Long) {
        val base = playbackSyncUrl() ?: return
        val s = _uiState.value
        val playing = s.playback == PlaybackState.PLAYING || s.playback == PlaybackState.BUFFERING
        val window = queueWindow(s.queue, s.queueIndex)
        viewModelScope.launch {
            repository.putNowPlaying(
                base,
                NowPlayingState(
                    track = track.copy(thumbnailUrl = shareableArtUrl(track)),
                    positionMs = positionMs,
                    durationMs = durationMs,

                    updatedAt = 0L,
                    device = DEVICE_NAME,
                    isPlaying = playing,
                    queue = window.queue,
                    queueIndex = window.index,

                    sourceQueue = emptyList(),
                    history = historyWindow(),
                    shuffle = s.shuffle,
                    repeat = s.repeatMode.name
                )
            )
        }
    }

    private data class QueueWindow(val queue: List<Track>, val index: Int)

    private fun queueWindow(queue: List<Track>, index: Int): QueueWindow {
        if (queue.size <= QUEUE_WINDOW_TOTAL) {
            return QueueWindow(queue, index.coerceIn(0, (queue.size - 1).coerceAtLeast(0)))
        }
        val safeIndex = index.coerceIn(0, queue.size - 1)
        val start = (safeIndex - QUEUE_WINDOW_BEHIND).coerceIn(0, queue.size - QUEUE_WINDOW_TOTAL)
        return QueueWindow(
            queue = queue.subList(start, start + QUEUE_WINDOW_TOTAL).toList(),
            index = safeIndex - start
        )
    }

    private fun shareableArtUrl(track: Track): String {
        val art = track.thumbnailUrl
        if (art.startsWith("http://", true) || art.startsWith("https://", true)) return art
        val base = track.serverBaseUrl.trim().trimEnd('/')
        return if (base.isNotBlank() && track.id.isNotBlank()) "$base/art/${track.id}" else ""
    }

    private fun restoreSession() {
        val snap = runCatching {
            sessionJson.decodeFromString<SessionSnapshot>(
                settingsRepository.lastSessionJson.ifBlank { "{}" }
            )
        }.getOrNull()
        val track = snap?.queue?.getOrNull(snap.index)
        if (track != null) {
            needsRestore = true
            restoredPositionMs = snap.positionMs

            playHistory.clear()
            playHistory.addAll(snap.history.takeLast(HISTORY_WINDOW))
            _uiState.value = _uiState.value.copy(
                currentTrack = track,
                queue = snap.queue,
                queueIndex = snap.index,
                sourceQueue = snap.sourceQueue.ifEmpty { snap.queue },
                shuffle = snap.shuffle,
                repeatMode = snap.repeat,
                positionMs = snap.positionMs,
                durationMs = 0L,
                playback = PlaybackState.PAUSED
            )
        }

        publishHasPrevious()
        maybeRestoreFromServer(localUpdatedAt = snap?.updatedAt ?: 0L)
    }

    fun refreshRemoteSession() {
        val local = runCatching {
            sessionJson.decodeFromString<SessionSnapshot>(
                settingsRepository.lastSessionJson.ifBlank { "{}" }
            )
        }.getOrNull()
        maybeRestoreFromServer(localUpdatedAt = local?.updatedAt ?: 0L)
    }

    private fun maybeRestoreFromServer(localUpdatedAt: Long) {
        viewModelScope.launch { pullRemoteSession(localUpdatedAt) }
    }

    private suspend fun pullRemoteSession(localUpdatedAt: Long) {
        val base = playbackSyncUrl() ?: return
        run {
            val remote = repository.getNowPlaying(base, withHistory = true) ?: return
            if (remote.updatedAt <= localUpdatedAt) return
            if (!needsRestore && _uiState.value.playback == PlaybackState.PLAYING) return

            val serverByKey = runCatching { repository.browsePlaylistTracks(base, "__all__") }
                .getOrDefault(emptyList())
                .associateBy { it.matchKey() }
            fun mapped(t: Track): Track = serverByKey[t.matchKey()] ?: t
            val resume = mapped(remote.track)
            val queue = remote.queue.map { mapped(it) }.ifEmpty { listOf(resume) }
            val source = remote.sourceQueue.map { mapped(it) }.ifEmpty { queue }

            playHistory.clear()
            playHistory.addAll(remote.history.map { mapped(it) }.takeLast(HISTORY_WINDOW))
            val idx = remote.queueIndex.coerceIn(0, (queue.size - 1).coerceAtLeast(0))
            val repeat = runCatching { RepeatMode.valueOf(remote.repeat) }.getOrDefault(RepeatMode.OFF)
            needsRestore = true
            restoredPositionMs = remote.positionMs
            _uiState.value = _uiState.value.copy(
                currentTrack = resume,
                queue = queue,
                queueIndex = idx,
                sourceQueue = source,
                shuffle = remote.shuffle,
                repeatMode = repeat,
                positionMs = remote.positionMs,
                durationMs = 0L,
                playback = PlaybackState.PAUSED
            )
            publishHasPrevious()
        }
    }

    private val audioCapture = createAudioCapture()
    private var identifyJob: Job? = null

    private val skippedPasswordPrompts = mutableSetOf<String>()

    init {
        settingsRepository.forgetSavedPasswordsIfNotRemembered()
        _uiState.value = _uiState.value.copy(
            servers = serverEntries(),
            rememberPasswords = rememberPasswordsChoice(),
            passwordPrompt = nextPasswordPrompt()
        )
        publishMostPlayed()
        _uiState.value = _uiState.value.copy(
            identifySources = runCatching { audioCapture?.sources() }.getOrNull().orEmpty()
        )

        val savedVolume = settingsRepository.volume.coerceIn(0f, 1f)
        audioPlayer.setVolume(savedVolume)
        _uiState.value = _uiState.value.copy(volume = savedVolume)
        viewModelScope.launch {
            audioPlayer.playbackState.collect { state ->
                _uiState.value = _uiState.value.copy(playback = state)

                if (state == PlaybackState.PLAYING || state == PlaybackState.BUFFERING) {
                    needsRestore = false
                }
                if (state == PlaybackState.PLAYING) failedInARow = 0
                if (state == PlaybackState.PAUSED) saveSession()
                if (state == PlaybackState.ENDED) onSongEnded()
                if (state == PlaybackState.ERROR) onPlaybackError()
            }
        }
        viewModelScope.launch {
            audioPlayer.autoAdvanced.collect { onAutoAdvanced() }
        }
        viewModelScope.launch {
            audioPlayer.positionMs.collect { pos ->
                _uiState.value = _uiState.value.copy(positionMs = pos)

                if (pos - lastSessionSaveMs > 5_000L || pos < lastSessionSaveMs) {
                    lastSessionSaveMs = pos
                    if (_uiState.value.playback == PlaybackState.PLAYING) saveSession()
                }
            }
        }
        viewModelScope.launch {
            audioPlayer.durationMs.collect { dur ->
                _uiState.value = _uiState.value.copy(durationMs = dur)

                if (dur > 0 && pendingResumeSeekMs > 0 && pendingResumeSeekMs < dur) {
                    audioPlayer.seekTo(pendingResumeSeekMs)
                    pendingResumeSeekMs = 0L
                } else if (dur > 0) {
                    pendingResumeSeekMs = 0L
                }
            }
        }
        viewModelScope.launch {
            audioPlayer.nowPlaying.collect { info ->
                if (info != null && _uiState.value.currentTrack == null) {
                    _uiState.value = _uiState.value.copy(
                        currentTrack = Track(
                            id = "restored:${info.title}",
                            title = info.title,
                            artist = info.artist,
                            durationLabel = "",
                            thumbnailUrl = info.artworkUrl.orEmpty(),
                            source = TrackSource.ONLINE,
                            itemId = "",
                            streamUrl = "",
                            downloadable = false
                        )
                    )
                }
            }
        }
        viewModelScope.launch {
            audioPlayer.transportCommands.collect { command ->
                when (command) {
                    TransportCommand.NEXT -> onNext()
                    TransportCommand.PREVIOUS -> onPrevious()
                }
            }
        }

        runSearch("")

        refreshServerLibrary()

        refreshLocalTracks()

        restoreSession()

        if (settingsRepository.syncAutoEnabled) {
            libraryServerUrl()?.let { base ->
                viewModelScope.launch {
                    repository.getPlaylistSync(base)?.let { applyRemoteSync(it) }
                }
            }
        }
    }

    fun onPickLocalFolder(folder: LocalFolder) {
        settingsRepository.localFolderUri = folder.uri
        settingsRepository.localFolderLabel = folder.label
        _uiState.value = _uiState.value.copy(
            localFolderLabel = folder.label,
            localFolderPath = folder.uri
        )
        refreshLocalTracks()
    }

    fun refreshLocalTracks() {
        viewModelScope.launch { scanLocalTracks() }
    }

    private suspend fun scanLocalTracks(showLoading: Boolean = true) {
        var uri = settingsRepository.localFolderUri
        var label = _uiState.value.localFolderLabel
        if (uri.isBlank()) {
            val fallback = defaultLocalFolder()
            if (fallback == null) {
                _uiState.value = _uiState.value.copy(localTracks = emptyList(), localFolderLabel = "")
                return
            }
            uri = fallback.uri
            label = fallback.label
        }
        if (showLoading) _uiState.value = _uiState.value.copy(localLoading = true)
        val tracks = listLocalTracks(uri)
        _uiState.value = _uiState.value.copy(
            localTracks = withKnownArtwork(tracks),
            localLoading = false,
            localFolderLabel = label
        )
    }

    fun onRefreshLocalWithMetadata() {
        enqueueTransfer {
            scanLocalTracks()

            val missing = _uiState.value.localTracks.filter {
                needsArtUpgrade(it.thumbnailUrl) || it.artist == "Unknown artist"
            }
            if (missing.isEmpty()) return@enqueueTransfer
            val knownArt = knownArtworkByKey()
            beginTransfer("Fetching music info…")
            var ok = 0
            missing.forEachIndexed { i, track ->
                coroutineContext.ensureActive()
                updateTransferProgress(i.toFloat() / missing.size)
                _uiState.value = _uiState.value.copy(
                    transferLabel = "Tagging ${i + 1}/${missing.size}"
                )
                val artist = track.artist.takeIf { it != "Unknown artist" }.orEmpty()
                val info = repository.lookupTrackInfo(artist, track.title)

                val artUrl = info?.artworkUrl?.takeIf { it.isNotBlank() }
                    ?: knownArt[track.matchKey()]
                    ?: return@forEachIndexed
                val art = runCatching { repository.downloadBytes(artUrl) }.getOrNull()
                val done = embedLocalMetadata(
                    track.streamUrl,
                    info?.title?.ifBlank { track.title } ?: track.title,
                    info?.artist?.ifBlank { track.artist } ?: track.artist,
                    art
                )
                if (done) ok++
            }
            finishTransfer("Updated $ok file(s).")
            scanLocalTracks()
            refreshCurrentTrackArtwork()
        }
    }

    fun onRefreshTracksMetadata(tracks: List<Track>, force: Boolean = false) {
        enqueueTransfer {
            println("[MetaRefresh] checking ${tracks.size} track(s)")
            val missing = if (force) tracks else tracks.filter {
                needsArtUpgrade(it.thumbnailUrl) || it.artist.isBlank() || it.artist == "Unknown artist"
            }
            println(
                "[MetaRefresh] ${missing.size} missing art/artist: " +
                    missing.joinToString { "'${it.title}'" }
            )
            if (missing.isEmpty()) {
                beginTransfer("Checking metadata…")
                finishTransfer("Metadata already up to date (${tracks.size} track(s) have art + artist).")
                return@enqueueTransfer
            }
            beginTransfer("Fetching music info…")
            val knownArt = knownArtworkByKey()
            var ok = 0
            missing.forEachIndexed { i, track ->
                coroutineContext.ensureActive()
                updateTransferProgress(i.toFloat() / missing.size)
                _uiState.value = _uiState.value.copy(
                    transferLabel = "Tagging ${i + 1}/${missing.size}"
                )
                val artist = track.artist.takeIf { it.isNotBlank() && it != "Unknown artist" }.orEmpty()
                val info = repository.lookupTrackInfo(artist, track.title)

                val artUrl = info?.artworkUrl?.takeIf { it.isNotBlank() }
                    ?: knownArt[track.matchKey()]
                if (artUrl == null) {
                    println("[MetaRefresh] no cover found for '${track.artist} - ${track.title}'")
                    return@forEachIndexed
                }
                val merged = track.copy(
                    title = info?.title?.ifBlank { track.title } ?: track.title,
                    artist = info?.artist?.ifBlank { track.artist } ?: track.artist,
                    thumbnailUrl = artUrl
                )
                libraryRepository.replaceInPlaylists(track.id, merged)
                libraryRepository.replaceInFavorites(track.id, merged)
                applyStatRename(track, merged)
                ok++
            }
            println("[MetaRefresh] done, updated $ok")
            runCatching { settingsRepository.playStatsJson = statsJson.encodeToString(playStats) }
            publishMostPlayed()
            finishTransfer("Updated $ok track(s).")
            refreshLibrary()
            refreshCurrentTrackArtwork()
        }
    }

    private fun knownArtworkByKey(): Map<String, String> = artworkIndex(
        _uiState.value.playlists.flatMap { it.tracks } +
            _uiState.value.favorites +
            _uiState.value.localTracks
    )

    private fun artworkIndex(tracks: List<Track>): Map<String, String> {
        val known = mutableMapOf<String, String>()
        for (t in tracks) {
            val art = t.thumbnailUrl
            if (art.isNotBlank() && isUsableArt(art) && !known.containsKey(t.matchKey())) {
                known[t.matchKey()] = art
            }
        }
        return known
    }

    private fun fillArt(track: Track, known: Map<String, String>): Track {
        if (track.thumbnailUrl.isNotBlank() && isUsableArt(track.thumbnailUrl)) return track
        val better = known[track.matchKey()]
        return if (better == null) {
            if (track.thumbnailUrl.isBlank()) track else track.copy(thumbnailUrl = "")
        } else {
            track.copy(thumbnailUrl = better)
        }
    }

    fun onRetagLocalDownloads() {
        val locals = _uiState.value.localTracks
        if (locals.isEmpty()) {
            enqueueTransfer {
                beginTransfer("Fetching music info\u2026")
                finishTransfer("No downloaded files in this folder to tag.")
            }
            return
        }
        enqueueTransfer {
            beginTransfer("Fetching music info…")
            val knownArt = knownArtworkByKey()
            var ok = 0
            var skipped = 0
            locals.forEachIndexed { i, track ->
                coroutineContext.ensureActive()
                updateTransferProgress(i.toFloat() / locals.size)
                _uiState.value = _uiState.value.copy(
                    transferLabel = "Tagging ${i + 1}/${locals.size}"
                )
                val info = repository.lookupTrackInfo(track.artist, track.title)
                val artUrl = info?.artworkUrl?.takeIf { it.isNotBlank() }
                    ?: knownArt[track.matchKey()]
                if (artUrl == null) {
                    skipped++
                    return@forEachIndexed
                }
                val art = runCatching { repository.downloadBytes(artUrl) }.getOrNull()
                val done = embedLocalMetadata(
                    track.streamUrl,
                    info?.title?.takeIf { it.isNotBlank() } ?: track.title,
                    info?.artist?.takeIf { it.isNotBlank() } ?: track.artist,
                    art
                )
                if (done) ok++ else skipped++
            }
            finishTransfer(
                "Updated $ok file(s)" + (if (skipped > 0) ", $skipped skipped." else ".")
            )
            scanLocalTracks()
            refreshCurrentTrackArtwork()
        }
    }

    private fun withKnownArtwork(tracks: List<Track>): List<Track> {
        if (tracks.none { it.thumbnailUrl.isBlank() }) return tracks
        val known = knownArtworkByKey()
        if (known.isEmpty()) return tracks
        return tracks.map { fillArt(it, known) }
    }

    private fun refreshCurrentTrackArtwork() {
        val current = _uiState.value.currentTrack ?: return
        if (!needsArtUpgrade(current.thumbnailUrl)) return
        val key = current.matchKey()
        val candidates = (
            _uiState.value.playlists.flatMap { it.tracks } +
                _uiState.value.favorites +
                _uiState.value.localTracks
            ).filter { it.matchKey() == key && it.thumbnailUrl.isNotBlank() }
        val fresh = candidates.firstOrNull { !needsArtUpgrade(it.thumbnailUrl) }
            ?: candidates.firstOrNull { current.thumbnailUrl.isBlank() }
            ?: return
        if (fresh.thumbnailUrl == current.thumbnailUrl) return
        _uiState.value = _uiState.value.copy(
            currentTrack = current.copy(thumbnailUrl = fresh.thumbnailUrl)
        )
    }

    private fun needsArtUpgrade(thumbnailUrl: String): Boolean {
        if (thumbnailUrl.isBlank()) return true
        return thumbnailUrl.startsWith("http", ignoreCase = true) && "/art/" !in thumbnailUrl
    }

    fun onMoveTrackToFolder(track: Track, folder: String) {
        viewModelScope.launch {
            val ok = when {
                track.source == TrackSource.LOCAL -> {
                    val root = settingsRepository.localFolderUri
                        .ifBlank { defaultLocalFolder()?.uri.orEmpty() }
                    root.isNotBlank() && moveLocalTrack(track.streamUrl, root, folder)
                }
                track.serverBaseUrl.isNotBlank() ->
                    repository.moveOnServer(track.serverBaseUrl, track.itemId, folder)
                else -> false
            }
            _uiState.value = _uiState.value.copy(
                downloadStatus = if (ok) "Moved to ${folder.ifBlank { if (track.source == TrackSource.LOCAL) "music folder" else "Imported" }}."
                else "✗ Move failed."
            )
            if (ok) {
                if (track.source == TrackSource.LOCAL) {
                    refreshLocalTracks()
                } else {
                    onRefreshBrowse()
                    refreshServerLibrary()
                }
            }
        }
    }

    fun onSearchQueryChange(query: String) {
        _uiState.value = _uiState.value.copy(searchQuery = query)
        runSearch(query)
    }

    fun onSearchCommitted(query: String) {
        val clean = query.trim()
        if (clean.isBlank() || repository.isLink(clean)) return
        val updated = (listOf(clean) + _uiState.value.searchHistory.filterNot {
            it.equals(clean, ignoreCase = true)
        }).take(10)
        settingsRepository.searchHistoryRaw = updated.joinToString("\n")
        _uiState.value = _uiState.value.copy(searchHistory = updated)
    }

    private val IDENTIFY_SECONDS = 9

    private val IDENTIFY_SECONDS_LONG = 20

    fun onStartIdentify(sourceId: Int, longListen: Boolean = false) {
        val capture = audioCapture ?: return
        identifyJob?.cancel()
        identifyJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                identifyPhase = IdentifyPhase.LISTENING,
                identifyResult = null,
                identifyError = null,
            )
            val seconds = if (longListen) IDENTIFY_SECONDS_LONG else IDENTIFY_SECONDS
            val pcm = capture.record(sourceId, seconds)
            if (pcm == null) {
                _uiState.value = _uiState.value.copy(
                    identifyPhase = IdentifyPhase.ERROR,
                    identifyError = "Couldn't capture any audio. Make sure something is actually playing, then try again.",
                )
                return@launch
            }
            _uiState.value = _uiState.value.copy(identifyPhase = IdentifyPhase.MATCHING)
            try {
                val song = repository.recognizeSong(pcm)
                _uiState.value = if (song == null) {
                    _uiState.value.copy(identifyPhase = IdentifyPhase.NO_MATCH)
                } else {
                    _uiState.value.copy(identifyPhase = IdentifyPhase.RESULT, identifyResult = song)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    identifyPhase = IdentifyPhase.ERROR,
                    identifyError = "Couldn't reach the recognition service. Check your connection and try again.",
                )
            }
        }
    }

    fun onDismissIdentify() {
        identifyJob?.cancel()
        _uiState.value = _uiState.value.copy(
            identifyPhase = IdentifyPhase.IDLE,
            identifyResult = null,
            identifyError = null,
        )
    }

    fun onSearchIdentified() {
        val song = _uiState.value.identifyResult ?: return
        onDismissIdentify()
        onSearchQueryChange(listOf(song.artist, song.title).filter { it.isNotBlank() }.joinToString(" "))
    }

    fun onRemoveSearchHistory(query: String) {
        val updated = _uiState.value.searchHistory.filterNot { it == query }
        settingsRepository.searchHistoryRaw = updated.joinToString("\n")
        _uiState.value = _uiState.value.copy(searchHistory = updated)
    }

    fun onClearSearchHistory() {
        settingsRepository.searchHistoryRaw = ""
        _uiState.value = _uiState.value.copy(searchHistory = emptyList())
    }

    fun onPreviewVersionFromLink(input: String) {
        val clean = input.trim()
        if (clean.isBlank()) return
        viewModelScope.launch {
            val track = runCatching { repository.openLink(clean) }.getOrNull()
            if (track == null) {
                _uiState.value = _uiState.value.copy(
                    searchError = "Couldn't resolve that link. Check the URL and try again."
                )
            } else {
                onPreviewVersion(track)
            }
        }
    }

    fun onChooseVersionFromLink(input: String) {
        val clean = input.trim()
        if (clean.isBlank()) return
        viewModelScope.launch {
            val track = runCatching { repository.openLink(clean) }.getOrNull()
            if (track == null) {
                _uiState.value = _uiState.value.copy(
                    searchError = "Couldn't resolve that link. Check the URL and try again."
                )
            } else {
                onChooseVersion(track)
            }
        }
    }

    private fun runSearch(query: String) {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSearching = true, searchError = null)
            try {
                if (repository.isLink(query)) {
                    val track = repository.openLink(query)
                    _uiState.value = _uiState.value.copy(
                        searchResults = listOfNotNull(track),
                        isSearching = false,
                        searchError = if (track == null)
                            "Couldn't resolve that link. Check the URL or try again." else null
                    )
                    return@launch
                }
                val results = repository.search(query)
                _uiState.value = _uiState.value.copy(
                    searchResults = results.distinctById(),
                    isSearching = false
                )
            } catch (e: SearchUnavailableException) {
                _uiState.value = _uiState.value.copy(
                    isSearching = false,
                    searchError = e.message
                )
            } catch (e: Exception) {
                val detail = e.message ?: e.toString()
                _uiState.value = _uiState.value.copy(
                    isSearching = false,
                    searchError = "${e::class.simpleName}: $detail"
                )
            }
        }
    }

    fun onTrackClicked(
        track: Track,
        queue: List<Track> = listOf(track),

        preserveOrder: Boolean = false
    ) {
        val skipOnFailure = advancingOnItsOwn
        pendingResumeSeekMs = 0L
        _uiState.value = _uiState.value.copy(positionMs = 0L, durationMs = 0L)
        advancingOnItsOwn = false
        recordPlay(track)
        viewModelScope.launch {
            streamRetried = false

            val local = preferLocalCopy(track)
            val playTrack = if (local.source == TrackSource.LOCAL) local else preferServerCopy(track)
            originalForCurrent = track
            fallbackTried = false
            pushHistory(playTrack)
            val q: List<Track>
            val idx: Int
            val src: List<Track>
            if (preserveOrder) {
                q = _uiState.value.queue.ifEmpty { queue }
                idx = q.indexOfFirst { it.id == track.id }
                    .let { if (it >= 0) it else q.indexOfFirst { s -> s.matchKey() == track.matchKey() } }
                    .coerceAtLeast(0)
                src = _uiState.value.sourceQueue.ifEmpty { q }
            } else {
                val (bq, bidx) = buildQueue(track, queue)
                q = bq; idx = bidx; src = queue
            }
            _uiState.value = _uiState.value.copy(
                searchError = null,
                currentTrack = playTrack,
                sourceQueue = src,
                queue = q,
                queueIndex = idx
            )
            publishHasPrevious()
            try {
                val cached = prefetchedNext?.takeIf { it.first == playTrack.matchKey() }?.second
                val t0 = kotlin.time.TimeSource.Monotonic.markNow()
                val (played, url) = if (cached != null) playTrack to cached else resolveWithServerFallback(playTrack)
                println(
                    "[MainViewModel] resolveStreamUrl took ${t0.elapsedNow().inWholeMilliseconds}ms " +
                        "(cached=${cached != null}, source=${played.source}, server=${played.serverBaseUrl.isNotBlank()})"
                )
                if (played !== playTrack) _uiState.value = _uiState.value.copy(currentTrack = played)
                audioPlayer.play(url, played.title, played.artist, played.thumbnailUrl)
                prefetchNext()

                saveSession()
            } catch (e: CancellationException) {
                throw e
            } catch (e: SearchUnavailableException) {
                _uiState.value = _uiState.value.copy(searchError = e.message)
                if (skipOnFailure) skipFailedTrack(playTrack, e.message)
            } catch (e: Exception) {
                val detail = e.message ?: e.toString()
                _uiState.value = _uiState.value.copy(
                    searchError = "${e::class.simpleName}: $detail"
                )
                if (skipOnFailure) skipFailedTrack(playTrack, detail)
            }
        }
    }

    private var prefetchedNext: Pair<String, String>? = null
    private var prefetchJob: Job? = null

    private fun prefetchNext() {
        prefetchJob?.cancel()
        prefetchJob = viewModelScope.launch {
            prefetchedNext = null

            val n = if (_uiState.value.sleepAtEndOfTrack) null else computeNext()
            if (n == null) {
                audioPlayer.clearNext()
                return@launch
            }
            val local = preferLocalCopy(n)
            val playN = if (local.source == TrackSource.LOCAL) local else preferServerCopy(n)
            prefetchedNext = runCatching {
                playN.matchKey() to repository.resolveStreamUrl(playN)
            }.getOrNull()
            val url = prefetchedNext?.second
            if (url == null) {
                audioPlayer.clearNext()
                return@launch
            }

            audioPlayer.prefetch(url)

            audioPlayer.enqueueNext(url, playN.title, playN.artist, playN.thumbnailUrl)
        }
    }

    private fun onAutoAdvanced() {
        val next = computeNext() ?: return
        val state = _uiState.value
        val queue = state.queue
        val idx = queue.indexOfFirst { it.id == next.id }
            .let { if (it >= 0) it else queue.indexOfFirst { q -> q.matchKey() == next.matchKey() } }
        recordPlay(next)
        pushHistory(next)
        streamRetried = false
        _uiState.value = state.copy(
            currentTrack = preferLocalCopy(next),
            queueIndex = idx.coerceAtLeast(0)
        )
        publishHasPrevious()
        prefetchNext()
        saveSession()
    }

    fun onPlayClicked() {
        val cur = _uiState.value.currentTrack ?: return

        if (!needsRestore) {
            audioPlayer.resume()
            return
        }
        needsRestore = false
        viewModelScope.launch {
            val localAt = runCatching {
                sessionJson.decodeFromString<SessionSnapshot>(
                    settingsRepository.lastSessionJson.ifBlank { "{}" }
                ).updatedAt
            }.getOrDefault(0L)
            runCatching { pullRemoteSession(localAt) }

            val resumeTrack = _uiState.value.currentTrack ?: return@launch
            val local = preferLocalCopy(resumeTrack)
            val playTrack = if (local.source == TrackSource.LOCAL) local
                else preferServerCopy(resumeTrack)
            _uiState.value = _uiState.value.copy(currentTrack = playTrack)
            try {
                val (played, url) = resolveWithServerFallback(playTrack)
                if (played !== playTrack) _uiState.value = _uiState.value.copy(currentTrack = played)
                audioPlayer.play(url, played.title, played.artist, played.thumbnailUrl)

                if (restoredPositionMs > 0) {
                    pendingResumeSeekMs = restoredPositionMs
                    audioPlayer.seekTo(restoredPositionMs)
                }
                prefetchNext()
                saveSession()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(searchError = e.message ?: e.toString())
            }
        }
    }

    fun onToggleShuffle() {
        val on = !_uiState.value.shuffle
        _uiState.value = _uiState.value.copy(shuffle = on)
        val cur = _uiState.value.currentTrack
        val source = _uiState.value.sourceQueue.ifEmpty { _uiState.value.queue }
        if (cur != null && source.isNotEmpty()) {
            val anchor = source.firstOrNull { it.id == cur.id }
                ?: source.firstOrNull { it.matchKey() == cur.matchKey() }
                ?: source.first()
            val (q, idx) = buildQueue(anchor, source)
            _uiState.value = _uiState.value.copy(queue = q, queueIndex = idx)
        }

        prefetchNext()
    }

    fun onCycleRepeat() {
        val next = when (_uiState.value.repeatMode) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
        }
        _uiState.value = _uiState.value.copy(repeatMode = next)

        prefetchNext()
    }

    fun onPlayAll(tracks: List<Track>) {
        val start = if (_uiState.value.shuffle) tracks.randomOrNull() else tracks.firstOrNull()
        start?.let { onTrackClicked(it, tracks) }
    }

    private fun onSongEnded() {
        if (_uiState.value.sleepAtEndOfTrack) {
            _uiState.value = _uiState.value.copy(sleepAtEndOfTrack = false)
            audioPlayer.pause()
            return
        }
        if (_uiState.value.repeatMode == RepeatMode.ONE) {
            _uiState.value.currentTrack?.let { onTrackClicked(it, _uiState.value.queue, preserveOrder = true) }
            return
        }
        val next = computeNext() ?: return
        advancingOnItsOwn = true
        onTrackClicked(next, _uiState.value.queue, preserveOrder = true)
    }

    private fun recordPlay(track: Track) {
        val key = track.matchKey()
        val current = _uiState.value.currentTrack
        if (current != null && current.matchKey() == key) return
        val prev = playStats[key]
        playStats[key] = PlayStat((prev?.count ?: 0) + 1, track)
        runCatching { settingsRepository.playStatsJson = statsJson.encodeToString(playStats) }
        publishMostPlayed()
    }

    private fun applyStatRename(old: Track, merged: Track) {
        val oldKey = old.matchKey()
        val newKey = merged.matchKey()
        val stat = playStats.remove(oldKey) ?: playStats.remove(newKey) ?: return
        val existing = playStats[newKey]
        playStats[newKey] =
            if (existing != null && existing.count > stat.count) existing.copy(track = merged)
            else stat.copy(track = merged)
    }

    private fun publishMostPlayed() {
        val seen = HashSet<String>()
        val top = playStats.values
            .sortedByDescending { it.count }
            .filter { it.track.id.isBlank() || seen.add(it.track.id) }
            .take(50)

        val known = knownArtworkByKey()
        _uiState.value = _uiState.value.copy(
            mostPlayed = top.map { fillArt(it.track, known) },
            playCounts = playStats.mapValues { (_, v) -> v.count }
        )
    }

    private var sleepJob: Job? = null

    fun onSetSleepTimer(minutes: Int?) {
        sleepJob?.cancel()
        sleepJob = null
        if (minutes == null) {
            _uiState.value = _uiState.value.copy(sleepTimerRemainingMs = null)
            return
        }
        sleepJob = viewModelScope.launch {
            var remaining = minutes * 60_000L
            _uiState.value = _uiState.value.copy(sleepTimerRemainingMs = remaining)
            while (remaining > 0) {
                delay(1_000)
                remaining -= 1_000
                _uiState.value = _uiState.value.copy(sleepTimerRemainingMs = remaining)
            }
            audioPlayer.pause()
            _uiState.value = _uiState.value.copy(sleepTimerRemainingMs = null)
        }
    }

    fun onToggleSleepAtEndOfTrack() {
        _uiState.value = _uiState.value.copy(
            sleepAtEndOfTrack = !_uiState.value.sleepAtEndOfTrack
        )

        prefetchNext()
    }

    fun onNext() {
        val next = computeNext() ?: return
        onTrackClicked(next, _uiState.value.queue, preserveOrder = true)
    }

    private fun computeNext(): Track? {
        val state = _uiState.value
        val queue = state.queue
        if (queue.isEmpty()) return null
        if (state.repeatMode == RepeatMode.ONE) return queue.getOrNull(state.queueIndex)
        return when {
            state.queueIndex + 1 < queue.size -> queue[state.queueIndex + 1]
            state.repeatMode == RepeatMode.ALL -> queue.firstOrNull()
            else -> null
        }
    }

    private fun buildQueue(track: Track, source: List<Track>): Pair<List<Track>, Int> {
        if (!_uiState.value.shuffle || source.size <= 1) {
            val idx = source.indexOfFirst { it.id == track.id }
                .let { if (it >= 0) it else source.indexOfFirst { s -> s.matchKey() == track.matchKey() } }
            return source to idx.coerceAtLeast(0)
        }
        val rest = source.filter { it.id != track.id }.shuffled()
        return (listOf(track) + rest) to 0
    }

    private fun preferLocalCopy(track: Track, allowLooseMatch: Boolean = true): Track {
        if (track.source == TrackSource.LOCAL && isLocalFileReadable(track.streamUrl)) return track

        fun key(t: Track) = t.matchKey()
        val wanted = key(track)
        fun match(predicate: (Track) -> Boolean): Track? =
            _uiState.value.localTracks.firstOrNull { predicate(it) && it.id !in failedLocalIds }

        val local = match { key(it) == wanted }

            ?: if (allowLooseMatch) {
                track.looseKey()?.let { loose -> match { it.looseKey() == loose } }
            } else {
                null
            }
            ?: return track

        return if (local.thumbnailUrl.isBlank()) local.copy(thumbnailUrl = track.thumbnailUrl) else local
    }

    private fun preferServerCopy(track: Track): Track {
        if (track.serverBaseUrl.isNotBlank()) return track
        val wanted = trackKey(track)

        serverCopyFor(wanted)?.let { return it }
        val state = _uiState.value
        val owned = (state.favorites +
            state.playlists.flatMap { it.tracks } +
            state.browseTracks)
            .firstOrNull { trackKey(it) == wanted && it.serverBaseUrl.isNotBlank() }
        return owned ?: track
    }

    private fun uploadExtFor(bytes: ByteArray, track: Track): String =
        AudioContainer.extensionOf(bytes)
            ?: track.streamUrl.substringAfterLast('.', "").lowercase()
                .takeIf { it.isNotBlank() && it.length <= 5 }
            ?: "mp3"

    private fun historyWindow(): List<Track> =
        playHistory.toList().takeLast(HISTORY_WINDOW)

    private fun serverUploadName(artist: String, title: String, ext: String): String {
        val base = "$artist - $title"
            .replace(Regex("[/\\\\:*?\"<>|\\x00-\\x1F]"), "_")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(180)
            .ifBlank { "track" }
        return "$base.$ext"
    }

    private fun preferLocalFile(track: Track): Track {
        if (track.source == TrackSource.LOCAL) return track
        val wanted = trackKey(track)
        return _uiState.value.localTracks
            .firstOrNull { trackKey(it) == wanted } ?: track
    }

    private var originalForCurrent: Track? = null
    private var fallbackTried = false

    private val failedLocalIds = mutableSetOf<String>()

    private fun onPlaybackError() {
        val cur = _uiState.value.currentTrack ?: return
        if (cur.source != TrackSource.LOCAL) {
            retryOrSkipStream(cur)
            return
        }
        val orig = originalForCurrent ?: return
        if (fallbackTried) return
        fallbackTried = true

        failedLocalIds.add(cur.id)
        viewModelScope.launch {
            val alt = preferServerCopy(orig)
            runCatching {
                val (played, url) = resolveWithServerFallback(alt)
                _uiState.value = _uiState.value.copy(currentTrack = played)
                audioPlayer.play(url, played.title, played.artist, played.thumbnailUrl)
            }
        }
    }

    private var streamRetried = false
    private var failedInARow = 0
    private var advancingOnItsOwn = false

    private fun retryOrSkipStream(cur: Track) {
        if (streamRetried) {
            skipFailedTrack(cur, null)
            return
        }
        streamRetried = true
        val at = _uiState.value.positionMs
        println("[MainViewModel] playback error on \"${cur.title}\" at ${at}ms, asking its source again")
        viewModelScope.launch {
            try {
                val (played, url) = resolveWithServerFallback(cur)
                if (played !== cur) _uiState.value = _uiState.value.copy(currentTrack = played)

                if (at > 0) pendingResumeSeekMs = at
                audioPlayer.play(url, played.title, played.artist, played.thumbnailUrl)
                prefetchNext()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                skipFailedTrack(cur, e.message ?: e.toString())
            }
        }
    }

    private fun skipFailedTrack(track: Track, reason: String?) {
        pendingResumeSeekMs = 0L
        failedInARow++
        println("[MainViewModel] \"${track.title}\" won't play ($reason); failures in a row: $failedInARow")
        val next = computeNext()
        if (next == null || failedInARow >= MAX_FAILED_IN_A_ROW) {
            failedInARow = 0
            _uiState.value = _uiState.value.copy(
                searchError = "Playback stopped: \"${track.title}\" wouldn't play" +
                    (reason?.let { ". $it" } ?: ".")
            )
            return
        }
        advancingOnItsOwn = true
        onTrackClicked(next, _uiState.value.queue, preserveOrder = true)
        _uiState.value = _uiState.value.copy(searchError = "Skipped \"${track.title}\": it wouldn't play.")
    }

    private var suppressHistoryPush = false

    private fun pushHistory(newTrack: Track) {
        val prev = _uiState.value.currentTrack
        if (!suppressHistoryPush && prev != null && prev.id != newTrack.id) {
            playHistory.addLast(prev)
            while (playHistory.size > 100) playHistory.removeFirst()
        }
        suppressHistoryPush = false
        publishHasPrevious()
    }

    private fun publishHasPrevious() {
        val can = playHistory.isNotEmpty() || _uiState.value.queueIndex > 0
        if (_uiState.value.hasPrevious != can) {
            _uiState.value = _uiState.value.copy(hasPrevious = can)
        }
    }

    fun onPrevious() {
        val state = _uiState.value
        if (state.positionMs > 3_000L) {
            state.currentTrack?.let { onTrackClicked(it, state.queue, preserveOrder = true) }
            return
        }
        val lastPlayed = playHistory.removeLastOrNull()
        if (lastPlayed != null) {
            suppressHistoryPush = true
            onTrackClicked(lastPlayed, state.queue, preserveOrder = true)
            return
        }
        val prev = state.queue.getOrNull(state.queueIndex - 1) ?: return
        onTrackClicked(prev, state.queue, preserveOrder = true)
    }

    private fun trackKey(t: Track) = t.matchKey()

    private fun isAlreadyOnDevice(track: Track): Boolean {
        if (track.source == TrackSource.LOCAL) return true
        val k = trackKey(track)
        return _uiState.value.localTracks.any { trackKey(it) == k }
    }

    fun onDownloadClicked(track: Track) {
        if (!track.downloadable) return
        if (isAlreadyOnDevice(track)) {
            _uiState.value = _uiState.value.copy(downloadStatus = "Already on this device.")
            beginTransfer("Already on this device")
            finishTransfer("Already on this device.")
            return
        }
        enqueueTransfer {
            beginTransfer("Saving…")
            val status = try {
                val source = preferServerCopy(track)
                val url = resolveDownloadUrl(source)
                val result = fileDownloader.downloadAudio(
                    url, source.title, source.artist, source.thumbnailUrl
                ) { p -> updateTransferProgress(p) }
                when (result) {
                    is DownloadResult.Success -> {
                        downloadLedger.record("", track, result.location)
                        "Saved to ${result.location}"
                    }
                    is DownloadResult.Error -> "Download failed: ${result.message}"
                }
            } catch (e: Exception) {
                "Download failed: ${e.message ?: e.toString()}"
            }
            finishTransfer(status)
            refreshLocalTracks()
        }
    }

    private var transferJob: Job? = null

    private val transferQueue = ArrayDeque<suspend () -> Unit>()

    private val transferQueueLock = Mutex()

    private fun enqueueTransfer(block: suspend () -> Unit) {
        viewModelScope.launch {
            transferQueueLock.withLock { transferQueue.addLast(block) }
            publishQueueDepth()
            if (transferJob?.isActive == true) return@launch
            transferJob = viewModelScope.launch {
                while (true) {
                    val next = transferQueueLock.withLock { transferQueue.removeFirstOrNull() }
                        ?: break
                    publishQueueDepth()
                    next()

                    runCatching { scanLocalTracks(showLoading = false) }
                }
                publishQueueDepth()
            }
        }
    }

    private fun publishQueueDepth() {
        _uiState.value = _uiState.value.copy(transferQueueDepth = transferQueue.size)
    }

    fun cancelTransfer() {
        transferJob?.cancel()
        transferJob = null

        transferQueue.clear()
        _uiState.value = _uiState.value.copy(
            transferProgress = null,
            transferLabel = null,
            downloadStatus = "Cancelled",
            transferQueueDepth = 0
        )
    }

    private fun beginTransfer(label: String) {
        _uiState.value = _uiState.value.copy(
            downloadStatus = label, transferProgress = -1f, transferLabel = label
        )
    }

    private fun updateTransferProgress(p: Float) {
        _uiState.value = _uiState.value.copy(transferProgress = p)
    }

    private fun finishTransfer(status: String) {
        val ok = !status.startsWith("✗") && !status.startsWith("Download failed")
        _uiState.value = _uiState.value.copy(
            downloadStatus = status,
            transferProgress = 1f,
            transferLabel = if (ok) "Done" else status
        )

        viewModelScope.launch {
            delay(2500)
            _uiState.value = _uiState.value.copy(transferProgress = null, transferLabel = null)
        }
    }

    private suspend fun resolveDownloadUrl(track: Track): String {
        repository.resolveExtensionDownload(track)?.let { return it.url }
        if (track.source != TrackSource.LOCAL && track.serverBaseUrl.isBlank()) {
            throw SearchUnavailableException("This track's source doesn't allow saving it.", null)
        }
        return repository.resolveStreamUrl(track)
    }

    fun onDownloadTrackToServer(track: Track, serverBaseUrl: String) {
        enqueueTransfer {
            beginTransfer("Saving to server…")
            val status = try {
                if (repository.existsOnServer(serverBaseUrl, track.title, track.artist)) {
                    "Already on the server."
                } else {
                    val native = repository.resolveExtensionDownload(track)
                    when {
                        native != null -> {
                            val bytes = repository.downloadAudioBytes(native.url)
                            repository.uploadFileToServer(
                                serverBaseUrl,
                                serverUploadName(track.artist, track.title, native.ext),
                                bytes,
                                title = track.title,
                                artist = track.artist,
                                artUrl = track.thumbnailUrl
                            )
                            uploadLedger.record(serverBaseUrl, SERVER_IMPORTED_FOLDER, track)
                            markOnServer(track, serverBaseUrl)
                            "Saved to server."
                        }
                        else -> "✗ This track can't be sent to a server."
                    }
                }
            } catch (e: SearchUnavailableException) {
                "✗ ${e.message}"
            } catch (e: Exception) {
                "✗ ${e.message ?: e.toString()}"
            }
            finishTransfer(status)
            if (status == "Saved to server.") refreshServerLibrary()
        }
    }

    fun onDownloadPlaylistToDevice(
        tracks: List<Track>,
        playlistName: String = "",
        force: Boolean = false
    ) {
        val savable = tracks.filter { it.downloadable }
        if (savable.isEmpty()) {
            _uiState.value = _uiState.value.copy(downloadStatus = "Nothing here can be saved.")
            return
        }
        enqueueTransfer {
            beginTransfer("Saving to device…")
            if (force) downloadLedger.forget(playlistName)
            val total = savable.size
            var ok = 0
            var failed = 0
            var skipped = 0
            val failures = mutableListOf<Track>()
            savable.forEachIndexed { i, track ->
                coroutineContext.ensureActive()
                _uiState.value = _uiState.value.copy(
                    transferLabel = "Saving to device ${i + 1}/$total",
                    downloadStatus = "Saving to device ${i + 1}/$total: ${track.title}…"
                )

                if (downloadLedger.isSaved(playlistName, track) || isAlreadyOnDevice(track)) {
                    skipped++
                    updateTransferProgress((i + 1).toFloat() / total)
                    return@forEachIndexed
                }
                try {
                    val source = preferServerCopy(track)
                    val url = resolveDownloadUrl(source)

                    val result = fileDownloader.downloadAudio(
                        url, source.title, source.artist, source.thumbnailUrl, subfolder = playlistName
                    ) { p ->
                        val frac = if (p < 0f) 0f else p
                        updateTransferProgress((i + frac) / total)
                    }
                    when (result) {
                        is DownloadResult.Success -> {
                            ok++
                            downloadLedger.record(playlistName, track, result.location)
                        }
                        is DownloadResult.Error -> {
                            failed++
                            failures += track
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failed++
                    failures += track
                }
                updateTransferProgress((i + 1).toFloat() / total)
            }
            _uiState.value = _uiState.value.copy(
                failedDownloads = failures.toList(),
                failedDownloadsFolder = playlistName
            )
            finishTransfer(
                "Saved $ok to device" +
                    (if (skipped > 0) ", $skipped already saved" else "") +
                    (if (failed > 0) ", $failed failed: retry from the playlist menu." else ".")
            )
            refreshLocalTracks()
        }
    }

    fun onRetryFailedDownloads() {
        val state = _uiState.value
        val failed = state.failedDownloads
        if (failed.isEmpty()) return
        _uiState.value = state.copy(failedDownloads = emptyList())
        onDownloadPlaylistToDevice(failed, state.failedDownloadsFolder)
    }

    fun onForgetDownloadHistory(playlistName: String) {
        viewModelScope.launch {
            downloadLedger.forget(playlistName)
            _uiState.value = _uiState.value.copy(
                downloadStatus = "Download history cleared: the next run re-downloads everything."
            )
        }
    }

    fun onUploadPlaylistToServer(
        tracks: List<Track>,
        serverBaseUrl: String,
        playlistName: String = ""
    ) {
        if (tracks.isEmpty()) {
            _uiState.value = _uiState.value.copy(downloadStatus = "Nothing here to send.")
            return
        }
        enqueueTransfer {
            beginTransfer("Uploading…")

            var listingOk = true
            val claimedKeys = try {
                repository.browsePlaylistTracks(serverBaseUrl, "__all__")
                    .map { it.matchKey() }.toMutableSet()
            } catch (e: Exception) {
                listingOk = false
                mutableSetOf()
            }

            val ledgerFolder = playlistName.trim().ifBlank { SERVER_IMPORTED_FOLDER }

            if (uploadLedger.isEmptyFor(serverBaseUrl)) {
                seedUploadLedgerFrom(serverBaseUrl)
            }

            if (listingOk) uploadLedger.pruneTo(serverBaseUrl, claimedKeys.toSet())
            val total = tracks.size
            var ok = 0
            val failures = mutableListOf<String>()
            var skipped = 0
            tracks.forEachIndexed { i, original ->
                coroutineContext.ensureActive()

                val track = preferLocalFile(original)
                updateTransferProgress(i.toFloat() / total)
                _uiState.value = _uiState.value.copy(
                    transferLabel = "Uploading ${i + 1}/$total",
                    downloadStatus = "Uploading to server ${i + 1}/$total: ${track.title}…"
                )

                if (!claimedKeys.add(track.matchKey()) ||
                    (!listingOk && repository.existsOnServer(serverBaseUrl, track.title, track.artist))
                ) {
                    skipped++
                    return@forEachIndexed
                }

                if (!listingOk && uploadLedger.isUploaded(serverBaseUrl, ledgerFolder, track)) {
                    skipped++
                    return@forEachIndexed
                }

                var failure = uploadOneToServer(track, serverBaseUrl, playlistName)
                if (failure?.retryable == true) {
                    delay(UPLOAD_RETRY_DELAY_MS)
                    failure = uploadOneToServer(track, serverBaseUrl, playlistName)
                }
                if (failure == null) {
                    ok++
                    uploadLedger.record(serverBaseUrl, ledgerFolder, track)
                    markOnServer(original, serverBaseUrl)
                } else {
                    failures += "\"${original.title}\" (${failure.reason})"
                }
            }
            finishTransfer(uploadSummary(ok, skipped, failures))

            if (ok > 0) refreshServerLibrary()
        }
    }

    private class UploadFailure(val reason: String, val retryable: Boolean)

    private suspend fun uploadOneToServer(track: Track, serverBaseUrl: String, folder: String): UploadFailure? {
        return try {
            if (track.source == TrackSource.LOCAL) {
                val bytes = readLocalTrackBytes(track.streamUrl)
                    ?: return UploadFailure("the file on this device couldn't be read", retryable = false)
                repository.uploadFileToServer(
                    serverBaseUrl,
                    serverUploadName(track.artist, track.title, uploadExtFor(bytes, track)),
                    bytes,
                    folder,
                    title = track.title,
                    artist = track.artist,
                    artUrl = track.thumbnailUrl
                )
            } else {
                val native = repository.resolveExtensionDownload(track)
                    ?: return UploadFailure("no switched-on source can save it", retryable = false)
                val bytes = repository.downloadAudioBytes(native.url)
                repository.uploadFileToServer(
                    serverBaseUrl,
                    serverUploadName(track.artist, track.title, native.ext),
                    bytes,
                    folder,
                    title = track.title,
                    artist = track.artist,
                    artUrl = track.thumbnailUrl
                )
            }
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            UploadFailure(failureReason(e), retryable = true)
        }
    }

    private fun failureReason(e: Exception): String =
        (e.message ?: e::class.simpleName ?: "error")
            .removePrefix("Upload failed: ")
            .lineSequence().first()
            .take(80)

    private fun uploadSummary(ok: Int, skipped: Int, failures: List<String>): String = buildString {
        append("Uploaded $ok to server")
        if (skipped > 0) append(", $skipped already there")
        if (failures.isEmpty()) {
            append(".")
        } else {
            append(", ${failures.size} failed: ")
            append(failures.take(3).joinToString("; "))
            if (failures.size > 3) append("; and ${failures.size - 3} more")
            append(".")
        }
    }

    private suspend fun seedUploadLedgerFrom(serverBaseUrl: String) {
        val history = repository.fetchServerTransfers(serverBaseUrl).mapNotNull { entry ->
            val key = songMatchKey(entry.artist, entry.title)

            if (key.trim('|').isBlank()) return@mapNotNull null
            SeedEntry(
                key = key,
                folder = entry.folder.ifBlank { SERVER_IMPORTED_FOLDER },
                title = entry.title,
                artist = entry.artist,
                at = entry.at,
                deleted = entry.event == "delete"
            )
        }
        uploadLedger.seedFrom(serverBaseUrl, history)
    }

    fun onDeleteLocalTrack(track: Track) {
        viewModelScope.launch {
            beginTransfer("Deleting…")

            val current = _uiState.value.currentTrack
            if (current != null && current.matchKey() == track.matchKey()) {
                audioPlayer.stop()
            }
            var ok = false
            for (attempt in 0 until 5) {
                ok = try { deleteLocalTrack(track.streamUrl) } catch (e: Exception) { false }
                if (ok) break
                delay(200)
            }
            if (ok) {
                _uiState.value = _uiState.value.copy(
                    localTracks = _uiState.value.localTracks.filterNot { it.id == track.id }
                )
            }
            finishTransfer(if (ok) "Deleted from device." else "✗ Couldn't delete from device.")
        }
    }

    fun onDeleteLocalCopy(track: Track) {
        val local = preferLocalCopy(track, allowLooseMatch = false)
        if (local.source == TrackSource.LOCAL) onDeleteLocalTrack(local)
    }

    fun onDeleteFromServer(track: Track) {
        val target = if (track.serverBaseUrl.isBlank()) preferServerCopy(track) else track
        if (target.serverBaseUrl.isBlank()) return
        viewModelScope.launch {
            beginTransfer("Deleting from server…")
            val status = try {
                repository.deleteFromServer(target.serverBaseUrl, target.itemId)

                val gone = setOf(target.id)
                val s = _uiState.value
                _uiState.value = s.copy(
                    browseTracks = s.browseTracks.filterNot { it.id in gone },
                    searchResults = s.searchResults.filterNot { it.id in gone },
                    favorites = s.favorites.filterNot { it.id in gone },
                    playlists = s.playlists.map { pl ->
                        pl.copy(tracks = pl.tracks.filterNot { it.id in gone })
                    }
                )
                refreshServerLibrary()
                "Deleted from server."
            } catch (e: Exception) {
                "✗ ${e.message ?: e}"
            }
            finishTransfer(status)
        }
    }

    fun onDeleteLocalTracks(tracks: List<Track>) {
        val locals = tracks.filter { it.source == TrackSource.LOCAL }
        if (locals.isEmpty()) return
        enqueueTransfer {
            beginTransfer("Deleting…")

            val currentKey = _uiState.value.currentTrack?.matchKey()
            if (currentKey != null && locals.any { it.matchKey() == currentKey }) {
                audioPlayer.stop()
                delay(200)
            }
            var ok = 0
            for (t in locals) {
                if (runCatching { deleteLocalTrack(t.streamUrl) }.getOrDefault(false)) ok++
            }
            refreshLocalTracks()
            finishTransfer("Deleted $ok from device.")
        }
    }

    fun onDeleteAllLocal() {
        onDeleteLocalTracks(_uiState.value.localTracks)
    }

    fun onDeleteLocalFolder(folder: String) {
        onDeleteLocalTracks(_uiState.value.localTracks.filter { it.localFolder == folder })
    }

    fun onDeleteBrowseFolder(baseUrl: String, playlistId: String) {
        enqueueTransfer {
            beginTransfer("Deleting folder from server…")
            val tracks = try {
                repository.browsePlaylistTracks(baseUrl, playlistId)
            } catch (e: Exception) {
                finishTransfer("✗ Couldn't load that folder.")
                return@enqueueTransfer
            }
            val serverTracks = tracks.filter { it.serverBaseUrl.isNotBlank() }
            var ok = 0
            for (t in serverTracks) {
                runCatching { repository.deleteFromServer(t.serverBaseUrl, t.itemId) }.onSuccess { ok++ }
            }
            onBrowseSource(baseUrl)
            finishTransfer("Deleted $ok from server.")
        }
    }

    fun onPlayBrowsePlaylist(baseUrl: String, playlistId: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(browseError = null)
            val tracks = try {
                repository.browsePlaylistTracks(baseUrl, playlistId)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    browseError = e.message ?: "Couldn't load this folder."
                )
                return@launch
            }
            if (tracks.isEmpty()) return@launch
            onPlayAll(tracks)
        }
    }

    fun onPickVersion(track: Track) {
        _uiState.value = _uiState.value.copy(
            versionPickerFor = track,
            versionOptions = emptyList(),
            versionLoading = true
        )
        viewModelScope.launch {
            val query = listOf(track.artist, track.title).filter { it.isNotBlank() }.joinToString(" ")
            val results = try {
                repository.search(query)
            } catch (e: Exception) {
                emptyList()
            }
            _uiState.value = _uiState.value.copy(versionOptions = results, versionLoading = false)
        }
    }

    fun onDismissVersionPicker() {
        _uiState.value = _uiState.value.copy(
            versionPickerFor = null, versionOptions = emptyList(), versionLoading = false
        )
    }

    fun onPreviewVersion(track: Track) {
        viewModelScope.launch {
            val playTrack = preferLocalCopy(track)
            _uiState.value = _uiState.value.copy(currentTrack = playTrack, searchError = null)
            try {
                val url = repository.resolveStreamUrl(playTrack)
                audioPlayer.play(url, playTrack.title, playTrack.artist, playTrack.thumbnailUrl)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(searchError = e.message ?: e.toString())
            }
        }
    }

    fun onChooseVersion(chosen: Track) {
        val original = _uiState.value.versionPickerFor ?: return

        val merged = chosen.copy(
            thumbnailUrl = original.thumbnailUrl.ifBlank { chosen.thumbnailUrl }
        )

        libraryRepository.replaceInPlaylists(original.id, merged)

        val s = _uiState.value
        val newQueue = s.queue.map { if (it.id == original.id) merged else it }
        _uiState.value = s.copy(
            queue = newQueue,
            favorites = s.favorites.map { if (it.id == original.id) merged else it },
            browseTracks = s.browseTracks.map { if (it.id == original.id) merged else it },
            searchResults = s.searchResults.map { if (it.id == original.id) merged else it },
            versionPickerFor = null,
            versionOptions = emptyList(),
            versionLoading = false
        )
        refreshLibrary()

        onTrackClicked(merged, newQueue.ifEmpty { listOf(merged) })
    }

    fun onPauseClicked() {
        audioPlayer.pause()
    }

    fun onSeek(positionMs: Long) {
        audioPlayer.seekTo(positionMs)
    }

    fun onSetVolume(volume: Float) {
        val v = volume.coerceIn(0f, 1f)
        audioPlayer.setVolume(v)
        settingsRepository.volume = v
        _uiState.value = _uiState.value.copy(volume = v)
    }

    fun onBrowseSource(baseUrl: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                browseLoading = true,
                browseError = null,
                browsePlaylists = emptyList(),
                browseTracks = emptyList()
            )
            val playlists = try {
                repository.browsePlaylists(baseUrl)
            } catch (e: Exception) {
                emptyList()
            }
            _uiState.value = _uiState.value.copy(
                browseLoading = false,
                browsePlaylists = playlists,
                browseError = if (playlists.isEmpty()) {
                    "This source has no browsable folders. Use search instead."
                } else null
            )
        }
    }

    private var lastBrowseBaseUrl: String = ""
    private var lastBrowsePlaylistId: String = ""

    fun onSearchBrowseSource(baseUrl: String, query: String) {
        val clean = query.trim()
        if (clean.isBlank()) return
        lastBrowseBaseUrl = baseUrl

        lastBrowsePlaylistId = ""
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(browseLoading = true, browseError = null)
            val tracks = try {
                repository.searchLibrary(baseUrl, clean)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    browseLoading = false,
                    browseError = e.message ?: "Couldn't search this server."
                )
                return@launch
            }
            _uiState.value = _uiState.value.copy(
                browseLoading = false,
                browseTracks = tracks.distinctById()
            )
            enrichBrowseArtwork()
        }
    }

    fun onOpenBrowsePlaylist(baseUrl: String, playlistId: String) {
        lastBrowseBaseUrl = baseUrl
        lastBrowsePlaylistId = playlistId
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(browseLoading = true, browseError = null)
            val tracks = try {
                repository.browsePlaylistTracks(baseUrl, playlistId)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    browseLoading = false,
                    browseError = e.message ?: "Couldn't load this folder."
                )
                return@launch
            }
            _uiState.value = _uiState.value.copy(
                browseLoading = false,
                browseTracks = tracks.distinctById()
            )
            enrichBrowseArtwork()
        }
    }

    private fun enrichBrowseArtwork() {
        val blanks = _uiState.value.browseTracks.filter { it.thumbnailUrl.isBlank() }
        if (blanks.isEmpty()) return
        viewModelScope.launch {
            blanks.forEach { track ->
                val query = listOf(track.artist, track.title)
                    .filter { it.isNotBlank() }.joinToString(" ")
                if (query.isBlank()) return@forEach
                val art = try {
                    repository.search(query).firstOrNull { it.thumbnailUrl.isNotBlank() }?.thumbnailUrl
                } catch (e: Exception) {
                    null
                } ?: return@forEach
                _uiState.value = _uiState.value.copy(
                    browseTracks = _uiState.value.browseTracks.map {
                        if (it.id == track.id && it.thumbnailUrl.isBlank()) it.copy(thumbnailUrl = art) else it
                    }
                )
            }
        }
    }

    fun onRefreshBrowse() {
        val baseUrl = lastBrowseBaseUrl
        val playlistId = lastBrowsePlaylistId
        if (baseUrl.isBlank() || playlistId.isBlank()) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(browseLoading = true, browseError = null)
            repository.rescanServer(baseUrl)
            val tracks = try {
                repository.browsePlaylistTracks(baseUrl, playlistId)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    browseLoading = false,
                    browseError = e.message ?: "Couldn't refresh this folder."
                )
                return@launch
            }
            _uiState.value = _uiState.value.copy(
                browseLoading = false,
                browseTracks = tracks.distinctById()
            )
            enrichBrowseArtwork()
        }
    }

    private fun refreshLibrary() {
        val storedPlaylists = libraryRepository.playlists()
        val storedFavorites = libraryRepository.favorites()

        val known = artworkIndex(
            storedPlaylists.flatMap { it.tracks } + storedFavorites + _uiState.value.localTracks
        )
        _uiState.value = _uiState.value.copy(
            playlists = storedPlaylists.map { p -> p.copy(tracks = p.tracks.map { fillArt(it, known) }) },
            favorites = storedFavorites.map { fillArt(it, known) },
            favoriteTrackIds = storedFavorites.map { it.id }.toSet()
        )

        autoPushSync()
    }

    private fun libraryServerUrl(): String? = serverBasesInOrder().firstOrNull()

    private fun serverBasesInOrder(): List<String> =
        pluginManager.servers().filter { it.enabled }.map { it.baseUrl.trim().trimEnd('/') }.filter { it.isNotBlank() }

    private fun serverEntries(): List<ServerEntry> =
        pluginManager.servers().map { server ->
            val (username, password) = settingsRepository.signInFor(server.id)
            ServerEntry(server.id, server.name, server.baseUrl, username, password, server.enabled)
        }

    fun refreshServerLibrary() {
        val bases = serverBasesInOrder()
        if (bases.isEmpty()) {
            serverCopies = emptyMap()
            serverLibraryTracks = emptyMap()
            _uiState.value = _uiState.value.copy(serverLibraryKeys = emptySet())
            return
        }
        viewModelScope.launch {
            val copies = linkedMapOf<String, MutableList<Track>>()
            val tracks = mutableListOf<Track>()
            var anyAnswered = false
            for (base in bases) {
                val found = try {
                    repository.browsePlaylistTracks(base, "__all__")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    markServerDown(base)
                    continue
                }
                downServers.remove(base)
                anyAnswered = true
                tracks += found
                found.filter { it.serverBaseUrl.isNotBlank() }
                    .forEach { copies.getOrPut(trackKey(it)) { mutableListOf() }.add(it) }
            }
            if (!anyAnswered) return@launch

            serverCopies = copies
            serverLibraryTracks = copies.mapValues { it.value.first() }
            val listed = (tracks.map { it.matchKey() } + tracks.mapNotNull { it.looseKey() }).toSet()

            uploadedNotListed.entries.removeAll { (key, at) ->
                key in listed || at.elapsedNow() > UPLOAD_MARK_KEPT
            }
            _uiState.value = _uiState.value.copy(serverLibraryKeys = listed + uploadedNotListed.keys)

            if (uploadedNotListed.isNotEmpty() && !recheckPending) {
                recheckPending = true
                delay(SERVER_RECHECK_MS)
                recheckPending = false
                refreshServerLibrary()
            }
        }
    }

    private val uploadedNotListed = mutableMapOf<String, kotlin.time.TimeMark>()
    private var recheckPending = false

    private fun markOnServer(track: Track, serverBaseUrl: String) {
        if (serverBaseUrl.trimEnd('/') != libraryServerUrl()?.trimEnd('/')) return
        val now = kotlin.time.TimeSource.Monotonic.markNow()
        listOfNotNull(track.matchKey(), track.looseKey()).forEach { uploadedNotListed[it] = now }
        _uiState.value = _uiState.value.copy(
            serverLibraryKeys = _uiState.value.serverLibraryKeys + uploadedNotListed.keys
        )
    }

    private var serverLibraryTracks: Map<String, Track> = emptyMap()

    private var serverCopies: Map<String, List<Track>> = emptyMap()

    private val downServers = mutableMapOf<String, kotlin.time.TimeMark>()

    private fun serverIsDown(base: String): Boolean =
        downServers[base.trimEnd('/')]?.let { it.elapsedNow() < SERVER_DOWN_FOR } ?: false

    private fun markServerDown(base: String) {
        println("[MainViewModel] server $base didn't answer; trying the next one for a while")
        downServers[base.trimEnd('/')] = kotlin.time.TimeSource.Monotonic.markNow()
    }

    private fun serverCopyFor(key: String): Track? {
        val copies = serverCopies[key] ?: return serverLibraryTracks[key]
        return copies.firstOrNull { !serverIsDown(it.serverBaseUrl) } ?: copies.firstOrNull()
    }

    private suspend fun resolveWithServerFallback(track: Track): Pair<Track, String> {
        val stale = track.serverBaseUrl.isNotBlank() &&
            (serverIsDown(track.serverBaseUrl) || track.serverBaseUrl.trimEnd('/') !in serverBasesInOrder())
        try {
            if (stale) throw SearchUnavailableException("This song's server isn't available.", null)
            return track to repository.resolveStreamUrl(track)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (track.serverBaseUrl.isBlank()) throw e
            if (!stale) markServerDown(track.serverBaseUrl)
            val others = serverCopies[trackKey(track)].orEmpty().filter {
                it.serverBaseUrl.trimEnd('/') != track.serverBaseUrl.trimEnd('/') && !serverIsDown(it.serverBaseUrl)
            }
            for (copy in others) {
                try {
                    return copy to repository.resolveStreamUrl(copy)
                } catch (c: CancellationException) {
                    throw c
                } catch (_: Exception) {
                    markServerDown(copy.serverBaseUrl)
                }
            }
            val original = originalForCurrent
                ?.takeIf { it.serverBaseUrl.isBlank() && it.source != TrackSource.LOCAL && trackKey(it) == trackKey(track) }
                ?: throw e
            return original to repository.resolveStreamUrl(original)
        }
    }

    fun onToggleFavorite(track: Track) {
        libraryRepository.toggleFavorite(track)
        refreshLibrary()
    }

    private fun buildSyncState(): PlaylistSyncState = PlaylistSyncState(
        playlists = libraryRepository.playlists().map { p ->
            p.copy(tracks = p.tracks.map { it.copy(thumbnailUrl = portableArt(it.thumbnailUrl)) })
        },
        favorites = libraryRepository.favorites().map {
            it.copy(thumbnailUrl = portableArt(it.thumbnailUrl))
        },
        updatedAt = nowEpochMillis(),
        device = "desktop",

        playStatsJson = runCatching {
            statsJson.encodeToString(
                playStats.mapValues { (_, v) ->
                    v.copy(track = v.track.copy(thumbnailUrl = portableArt(v.track.thumbnailUrl)))
                }.toMutableMap()
            )
        }.getOrDefault(""),

        themes = if (settingsRepository.syncThemeEnabled)
            themeRegistry.all().filterNot { it.builtIn } else emptyList(),
        sources = pluginManager.all().filterNot { it.builtIn },
        selectedThemeId = if (settingsRepository.syncThemeEnabled)
            themeRegistry.selectedId() else "",
        settingsOrderCsv = settingsRepository.settingsOrderCsv
    )

    private fun applyRemoteSync(remote: PlaylistSyncState) {
        libraryRepository.mergeSynced(
            remote.playlists.map { p ->
                p.copy(tracks = p.tracks.map { it.copy(thumbnailUrl = portableArt(it.thumbnailUrl)) })
            },
            remote.favorites.map { it.copy(thumbnailUrl = portableArt(it.thumbnailUrl)) }
        )
        if (remote.playStatsJson.isNotBlank()) {
            runCatching {
                statsJson.decodeFromString<MutableMap<String, PlayStat>>(remote.playStatsJson)
            }.getOrNull()?.forEach { (key, stat) ->
                val cur = playStats[key]
                if (cur == null || stat.count > cur.count) {
                    playStats[key] = stat.copy(
                        track = stat.track.copy(
                            thumbnailUrl = portableArt(stat.track.thumbnailUrl)
                        )
                    )
                }
            }
            runCatching { settingsRepository.playStatsJson = statsJson.encodeToString(playStats) }
            publishMostPlayed()
        }

        if (settingsRepository.syncThemeEnabled) {
            val themeIds = themeRegistry.all().map { it.id }.toSet()
            remote.themes.filterNot { it.builtIn || it.id in themeIds }.forEach { t ->
                themeRegistry.install(t.name, t.primary, t.secondary, t.tertiary, t.background, t.dark)
            }
        }
        val sourceIds = pluginManager.all().map { it.id }.toSet()
        remote.sources.filterNot { it.builtIn || it.id in sourceIds }.forEach { s ->
            pluginManager.upsert(s.id, s.name, s.baseUrl)
        }

        if (settingsRepository.syncThemeEnabled &&
            remote.selectedThemeId.isNotBlank() &&
            themeRegistry.all().any { it.id == remote.selectedThemeId }
        ) {
            themeRegistry.select(remote.selectedThemeId)
        }

        if (remote.settingsOrderCsv.isNotBlank()) {
            settingsRepository.settingsOrderCsv = remote.settingsOrderCsv
        }
        refreshLibrary()
        _uiState.value = _uiState.value.copy(
            themes = themeRegistry.all(),
            selectedThemeId = themeRegistry.selectedId(),
            sources = pluginManager.all(),
            settingsSectionOrder = settingsRepository.settingsOrderCsv
                .split(",").map { it.trim() }.filter { it.isNotBlank() }
        )
    }

    fun onSyncPlaylistsToServer() {
        val base = libraryServerUrl() ?: return
        viewModelScope.launch {
            beginTransfer("Syncing to server…")
            val ok = repository.putPlaylistSync(base, buildSyncState())
            finishTransfer(
                if (ok) "Library synced to server."
                else "Couldn't reach the server: sync failed."
            )
        }
    }

    fun onFetchPlaylistsFromServer() {
        val base = libraryServerUrl() ?: return
        viewModelScope.launch {
            beginTransfer("Fetching from server…")
            val remote = repository.getPlaylistSync(base)
            if (remote == null) {
                finishTransfer("No sync on the server yet: sync from a device first.")
                return@launch
            }
            applyRemoteSync(remote)
            finishTransfer(
                "Fetched ${remote.playlists.size} playlist(s) and " +
                    "${remote.favorites.size} liked song(s) from the server."
            )
        }
    }

    fun onToggleAutoSync() {
        val on = !settingsRepository.syncAutoEnabled
        settingsRepository.syncAutoEnabled = on
        _uiState.value = _uiState.value.copy(autoSyncEnabled = on)
        if (on) {
            val base = libraryServerUrl() ?: return
            viewModelScope.launch {
                repository.getPlaylistSync(base)?.let { applyRemoteSync(it) }
                repository.putPlaylistSync(base, buildSyncState())
            }
        }
    }

    fun onToggleThemeSync() {
        val on = !settingsRepository.syncThemeEnabled
        settingsRepository.syncThemeEnabled = on
        _uiState.value = _uiState.value.copy(themeSyncEnabled = on)
        if (on) autoPushSync()
    }

    fun onTogglePlaybackSync() {
        val on = !settingsRepository.syncPlaybackEnabled
        settingsRepository.syncPlaybackEnabled = on
        _uiState.value = _uiState.value.copy(playbackSyncEnabled = on)
        if (on) saveSession()
    }

    private var autoPushJob: Job? = null

    private fun autoPushSync() {
        if (!settingsRepository.syncAutoEnabled) return
        val base = libraryServerUrl() ?: return
        autoPushJob?.cancel()
        autoPushJob = viewModelScope.launch {
            delay(2_000)
            repository.putPlaylistSync(base, buildSyncState())
        }
    }

    private val updateChecker by lazy {
        UpdateChecker(client = repository.httpClient, repo = "Kevcar98/Spinet-Desktop")
    }

    fun onCheckForUpdates(manual: Boolean = false) {
        val installer = updateInstaller ?: return
        viewModelScope.launch {
            val found = updateChecker.check(installer::wants)
            _uiState.value = _uiState.value.copy(
                availableUpdate = found,
                updateDismissed = false,

                updateDialogOpen = manual && found != null,
                updateError = when {
                    !manual || found != null -> null
                    updateChecker.lastError != null ->
                        "Couldn't check for updates: ${updateChecker.lastError}"
                    else -> "You are on the latest version."
                }
            )
        }
    }

    fun onInstallUpdate() {
        if (_uiState.value.updateBusy) return
        val installer = updateInstaller ?: return
        val update = _uiState.value.availableUpdate ?: return
        val blocked = installer.blockedReason()
        if (blocked != null) {
            _uiState.value = _uiState.value.copy(updateError = blocked)
            installer.resolveBlock()
            return
        }
        _uiState.value = _uiState.value.copy(updateBusy = true, updateError = null)
        viewModelScope.launch {
            val result = runCatching {
                val bytes = repository.downloadBytes(update.downloadUrl)
                val sums = update.checksumsUrl?.let {
                    runCatching { repository.downloadBytes(it).decodeToString() }.getOrNull()
                }
                installer.install(update.downloadName, bytes, sums)
            }.getOrElse { "Download failed: ${it.message ?: it::class.simpleName}" }

            _uiState.value = _uiState.value.copy(
                updateBusy = false,
                updateError = result,

                availableUpdate = if (result == null) null else _uiState.value.availableUpdate,
                updateDismissed = false,
                updateDialogOpen = _uiState.value.updateDialogOpen && result != null
            )
        }
    }

    fun onDismissUpdate() {
        _uiState.value = _uiState.value.copy(
            updateDismissed = true,
            updateDialogOpen = false,
            updateError = null
        )
    }

    fun onShowUpdate() {
        _uiState.value = _uiState.value.copy(updateDismissed = false, updateDialogOpen = true)
    }

    fun onToggleSelect(track: Track) {
        val current = _uiState.value.selectedTrackIds
        val next = if (track.id in current) current - track.id else current + track.id
        _uiState.value = _uiState.value.copy(selectedTrackIds = next)
    }

    fun onSelectAll(tracks: List<Track>) {
        _uiState.value = _uiState.value.copy(
            selectedTrackIds = _uiState.value.selectedTrackIds + tracks.map { it.id }
        )
    }

    fun onClearSelection() {
        if (_uiState.value.selectedTrackIds.isEmpty()) return
        _uiState.value = _uiState.value.copy(selectedTrackIds = emptySet())
    }

    fun onSelectRangeTo(track: Track, visible: List<Track>) {
        val anchor = lastSelectAnchor
        val to = visible.indexOfFirst { it.id == track.id }
        if (to < 0) return
        val from = visible.indexOfFirst { it.id == anchor }
        if (anchor == null || from < 0) {
            onToggleSelect(track)
            lastSelectAnchor = track.id
            return
        }
        val range = if (from <= to) from..to else to..from
        _uiState.value = _uiState.value.copy(
            selectedTrackIds = _uiState.value.selectedTrackIds +
                range.map { visible[it].id }
        )
    }

    private var lastSelectAnchor: String? = null

    fun onToggleSelectAnchored(track: Track) {
        onToggleSelect(track)
        lastSelectAnchor = track.id
    }

    fun selectedTracks(from: List<Track>): List<Track> {
        val ids = _uiState.value.selectedTrackIds
        return from.filter { it.id in ids }
    }

    fun onAddTracksToPlaylist(playlistId: String, tracks: List<Track>) {
        if (tracks.isEmpty()) return
        tracks.forEach { libraryRepository.addToPlaylist(playlistId, it) }
        refreshLibrary()
        autoPushSync()
    }

    fun onRemoveTracksFromPlaylist(playlistId: String, tracks: List<Track>) {
        if (tracks.isEmpty()) return
        val playlist = _uiState.value.playlists.firstOrNull { it.id == playlistId } ?: return
        tracks.forEach { wanted ->
            val key = wanted.matchKey()

            val existing = playlist.tracks.firstOrNull { it.id == wanted.id || it.matchKey() == key }
            if (existing != null) libraryRepository.removeFromPlaylist(playlistId, existing.id)
        }
        refreshLibrary()
        autoPushSync()
    }

    fun onCreatePlaylistWith(name: String, tracks: List<Track>) {
        val created = libraryRepository.createPlaylist(name) ?: return
        tracks.forEach { libraryRepository.addToPlaylist(created.id, it) }
        refreshLibrary()
        autoPushSync()
    }

    fun onCreatePlaylist(name: String) {
        libraryRepository.createPlaylist(name) ?: return
        refreshLibrary()
    }

    fun onDeletePlaylist(id: String) {
        libraryRepository.deletePlaylist(id)
        refreshLibrary()
    }

    fun onRenamePlaylist(id: String, newName: String) {
        libraryRepository.renamePlaylist(id, newName)
        refreshLibrary()
    }

    fun onMovePlaylistTrack(playlistId: String, fromIndex: Int, toIndex: Int) {
        libraryRepository.movePlaylistTrack(playlistId, fromIndex, toIndex)
        refreshLibrary()
    }

    fun onMoveFavorite(fromIndex: Int, toIndex: Int) {
        libraryRepository.moveFavorite(fromIndex, toIndex)
        refreshLibrary()
    }

    fun onMovePlaylist(fromIndex: Int, toIndex: Int) {
        libraryRepository.movePlaylist(fromIndex, toIndex)
        refreshLibrary()
    }

    fun onMoveBrowseTrack(fromIndex: Int, toIndex: Int) {
        val list = _uiState.value.browseTracks.toMutableList()
        if (fromIndex !in list.indices || toIndex !in list.indices || fromIndex == toIndex) return
        list.add(toIndex, list.removeAt(fromIndex))
        _uiState.value = _uiState.value.copy(browseTracks = list.distinctById())
    }

    fun onMoveLocalTrack(fromIndex: Int, toIndex: Int) {
        val list = _uiState.value.localTracks.toMutableList()
        if (fromIndex !in list.indices || toIndex !in list.indices || fromIndex == toIndex) return
        list.add(toIndex, list.removeAt(fromIndex))
        _uiState.value = _uiState.value.copy(localTracks = list)
    }

    fun exportPlaylistJson(id: String): String = libraryRepository.exportPlaylistJson(id).orEmpty()

    fun onImportPlaylist(text: String) {
        libraryRepository.importPlaylistJson(text) ?: return
        refreshLibrary()
    }

    fun onAddToPlaylist(playlistId: String, track: Track) {
        libraryRepository.addToPlaylist(playlistId, track)
        refreshLibrary()
    }

    fun onRemoveFromPlaylist(playlistId: String, trackId: String) {
        libraryRepository.removeFromPlaylist(playlistId, trackId)
        refreshLibrary()
    }

    fun onExperimentalPluginToggled(enabled: Boolean) {
        settingsRepository.experimentalPluginEngineEnabled = enabled
        _uiState.value = _uiState.value.copy(experimentalPluginEnabled = enabled)
    }

    fun onToggleSource(id: String, enabled: Boolean) {
        pluginManager.setEnabled(id, enabled)
        _uiState.value = _uiState.value.copy(sources = pluginManager.all(), servers = serverEntries(), serverHost = libraryServerUrl().orEmpty())
    }

    fun onSaveExtensionSettings(pluginId: String, values: Map<String, String>) {
        pluginManager.saveExtensionSettings(pluginId, values)
        _uiState.value = _uiState.value.copy(sources = pluginManager.all(), servers = serverEntries(), serverHost = libraryServerUrl().orEmpty())
    }

    fun onExtensionsChanged() {
        pluginManager.rescanExtensions()
        _uiState.value = _uiState.value.copy(sources = pluginManager.all(), servers = serverEntries(), serverHost = libraryServerUrl().orEmpty())
    }

    fun onInstallSource(name: String, url: String) {
        pluginManager.install(name, url) ?: return
        _uiState.value = _uiState.value.copy(
            sources = pluginManager.all(),
            servers = serverEntries(),
            serverHost = libraryServerUrl().orEmpty(),
            backendTestStatus = null
        )
    }

    fun onUninstallSource(id: String) {
        pluginManager.uninstall(id)
        _uiState.value = _uiState.value.copy(sources = pluginManager.all(), servers = serverEntries(), serverHost = libraryServerUrl().orEmpty())
    }

    fun onRenameSource(id: String, newName: String) {
        pluginManager.rename(id, newName)
        _uiState.value = _uiState.value.copy(sources = pluginManager.all(), servers = serverEntries(), serverHost = libraryServerUrl().orEmpty())
    }

    fun onUpdateSource(id: String, newName: String, newBaseUrl: String) {
        pluginManager.update(id, newName, newBaseUrl)
        _uiState.value = _uiState.value.copy(sources = pluginManager.all(), servers = serverEntries(), serverHost = libraryServerUrl().orEmpty())
    }

    fun onSaveServer(id: String?, name: String, address: String, username: String, password: String) {
        var url = address.trim().trimEnd('/')
        if (url.substringAfter("://").isBlank()) {
            _uiState.value = _uiState.value.copy(serverConfigStatus = "✗ Enter the server's address")
            return
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) url = "http://$url"
        val servers = pluginManager.servers()
        val serverId = id
            ?: servers.firstOrNull { it.baseUrl.trimEnd('/') == url }?.id
            ?: if (servers.none { it.id == FIRST_SERVER_ID }) FIRST_SERVER_ID else "server_" + url.hashCode().toUInt().toString(16)
        val label = name.trim().ifBlank { servers.firstOrNull { it.id == serverId }?.name ?: serverLabel(url) }

        pluginManager.uninstall("server_media")
        pluginManager.upsert(serverId, label, url)
        settingsRepository.saveSignIn(serverId, username.trim(), password)
        if (serverId == FIRST_SERVER_ID) {
            val port = url.substringAfter("://").substringAfter(':', "").substringBefore('/')
            settingsRepository.serverHost = if (port.isEmpty()) url else url.removeSuffix(":$port")
            settingsRepository.serverLibraryPort = port
        }

        _uiState.value = _uiState.value.copy(
            sources = pluginManager.all(),
            servers = serverEntries(),
            serverHost = libraryServerUrl().orEmpty(),
            serverConfigStatus = "Checking $label…"
        )
        downServers.remove(url)

        viewModelScope.launch {
            val access = repository.checkServerAccess(url)
            val entered = username.isNotBlank() || password.isNotEmpty()
            _uiState.value = _uiState.value.copy(serverConfigStatus = "$label: " + serverAccessMessage(access, entered))
            if (access != ServerAccess.OPEN && access != ServerAccess.SIGNED_IN) return@launch

            refreshServerLibrary()

            if (url == libraryServerUrl() && settingsRepository.syncAutoEnabled) {
                repository.getPlaylistSync(url)?.let { applyRemoteSync(it) }
                repository.putPlaylistSync(url, buildSyncState())
            }
        }
    }

    private fun rememberPasswordsChoice(): Boolean? =
        if (settingsRepository.canChooseToRememberPasswords) settingsRepository.rememberServerPasswords else null

    private fun nextPasswordPrompt(): ServerEntry? {
        val waiting = settingsRepository.serversNeedingPassword().map { it.id }.filterNot { it in skippedPasswordPrompts }
        return serverEntries().firstOrNull { it.id in waiting }
    }

    fun onToggleRememberPasswords(remember: Boolean) {
        settingsRepository.setRememberServerPasswords(remember)
        _uiState.value = _uiState.value.copy(rememberPasswords = rememberPasswordsChoice(), servers = serverEntries())
    }

    fun onEnterServerPassword(id: String, password: String, remember: Boolean) {
        val server = serverEntries().firstOrNull { it.id == id } ?: return
        if (remember) settingsRepository.setRememberServerPasswords(true)
        settingsRepository.saveSignIn(id, server.username, password)
        downServers.remove(server.baseUrl.trimEnd('/'))
        viewModelScope.launch {
            val access = repository.checkServerAccess(server.baseUrl)
            val problem = when (access) {
                ServerAccess.WRONG_SIGN_IN, ServerAccess.NEEDS_SIGN_IN -> "That password didn't work. Try again."
                ServerAccess.LOCKED_OUT -> "Too many wrong tries. Wait a few minutes, then try again."
                else -> null
            }
            if (problem != null) {
                settingsRepository.saveSignIn(id, server.username, "")
                _uiState.value = _uiState.value.copy(passwordPromptError = problem)
                return@launch
            }
            _uiState.value = _uiState.value.copy(
                servers = serverEntries(),
                rememberPasswords = rememberPasswordsChoice(),
                passwordPrompt = nextPasswordPrompt(),
                passwordPromptError = null
            )
            refreshServerLibrary()
        }
    }

    fun onSkipServerPassword(id: String) {
        skippedPasswordPrompts += id
        _uiState.value = _uiState.value.copy(passwordPrompt = nextPasswordPrompt(), passwordPromptError = null)
    }

    fun onRemoveServer(id: String) {
        pluginManager.uninstall(id)
        settingsRepository.forgetSignIn(id)
        if (id == FIRST_SERVER_ID) settingsRepository.serverHost = ""
        _uiState.value = _uiState.value.copy(
            sources = pluginManager.all(),
            servers = serverEntries(),
            serverHost = libraryServerUrl().orEmpty(),
            serverConfigStatus = null
        )
        refreshServerLibrary()
    }

    fun onMoveServer(id: String, up: Boolean) {
        pluginManager.moveServer(id, up)
        _uiState.value = _uiState.value.copy(
            sources = pluginManager.all(),
            servers = serverEntries(),
            serverHost = libraryServerUrl().orEmpty()
        )
        refreshServerLibrary()
    }

    private fun serverLabel(url: String): String =
        url.substringAfter("://").substringBefore('/').ifBlank { "Server" }

    private fun serverAccessMessage(access: ServerAccess, signInEntered: Boolean): String = when (access) {
        ServerAccess.OPEN ->
            if (signInEntered) "✓ Server configured. It doesn't ask for a sign-in, so the username and password aren't used."
            else "✓ Server configured."
        ServerAccess.SIGNED_IN -> "✓ Server configured and signed in."
        ServerAccess.NEEDS_SIGN_IN -> "✗ This server asks for a username and password. Enter the ones set on the server."
        ServerAccess.WRONG_SIGN_IN -> "✗ The server refused that username or password."
        ServerAccess.LOCKED_OUT -> "✗ Too many wrong sign-ins from this connection. Wait 10 minutes, then try again."
        ServerAccess.UNREACHABLE -> "✗ Saved, but the server didn't answer. Check the address and port, and that it's running."
    }

    fun onToggleSettingsLock(locked: Boolean) {
        settingsRepository.settingsLocked = locked
        _uiState.value = _uiState.value.copy(settingsLocked = locked)
    }

    fun onHighQualityArtToggled(enabled: Boolean) {
        settingsRepository.highQualityArtEnabled = enabled
        _uiState.value = _uiState.value.copy(highQualityArt = enabled)
    }

    fun onReorderSettings(order: List<String>) {
        settingsRepository.settingsOrderCsv = order.joinToString(",")
        _uiState.value = _uiState.value.copy(settingsSectionOrder = order)
        autoPushSync()
    }

    fun onSelectTheme(id: String) {
        themeRegistry.select(id)
        _uiState.value = _uiState.value.copy(selectedThemeId = themeRegistry.selectedId())
        autoPushSync()
    }

    fun onInstallTheme(
        name: String,
        primary: String,
        secondary: String,
        tertiary: String,
        background: String,
        dark: Boolean
    ) {
        themeRegistry.install(name, primary, secondary, tertiary, background, dark) ?: return
        _uiState.value = _uiState.value.copy(
            themes = themeRegistry.all(),
            selectedThemeId = themeRegistry.selectedId()
        )
        autoPushSync()
    }

    fun onUninstallTheme(id: String) {
        themeRegistry.uninstall(id)
        _uiState.value = _uiState.value.copy(
            themes = themeRegistry.all(),
            selectedThemeId = themeRegistry.selectedId()
        )
    }

    private val importJson = Json { ignoreUnknownKeys = true }

    fun onImportPlugin(jsonText: String) {
        val bundle = try {
            importJson.decodeFromString<PluginBundle>(jsonText)
        } catch (e: Exception) {
            _uiState.value = _uiState.value.copy(
                importStatus = "✗ Invalid plugin file: ${e.message ?: "could not parse JSON"}"
            )
            return
        }

        var added = 0
        bundle.sources.forEach { s ->
            if (pluginManager.install(s.name, s.baseUrl) != null) added++
        }
        bundle.themes.forEach { t ->
            val installed = themeRegistry.install(
                t.name, t.primary, t.secondary, t.tertiary, t.background, t.dark
            )
            if (installed != null) added++
        }

        _uiState.value = _uiState.value.copy(
            sources = pluginManager.all(),
            themes = themeRegistry.all(),
            selectedThemeId = themeRegistry.selectedId(),
            importStatus = if (added > 0) {
                "✓ Imported $added item(s)."
            } else {
                "✗ Nothing valid to import in that file."
            }
        )
    }

    fun onRetryUnmatchedImport() {
        val preview = _uiState.value.playlistImportPreview ?: return
        if (preview.unmatched.isEmpty()) return
        enqueueTransfer {
            beginTransfer("Retrying unmatched…")
            val before = preview.unmatched.size
            val merged = try {
                repository.retryUnmatchedTracks(preview) { done, total ->
                    updateTransferProgress(if (total > 0) done.toFloat() / total else -1f)
                    _uiState.value = _uiState.value.copy(
                        playlistImportStatus = "Retrying $done of $total…"
                    )
                }
            } catch (e: CancellationException) {
                _uiState.value = _uiState.value.copy(playlistImportStatus = "Retry cancelled.")
                throw e
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(playlistImportStatus = "✗ ${e.message ?: e}")
                finishTransfer("Retry failed")
                return@enqueueTransfer
            }
            val found = before - merged.unmatched.size
            _uiState.value = _uiState.value.copy(
                playlistImportPreview = merged,
                playlistImportStatus = if (found > 0) {
                    "Matched ${merged.matched.size} of ${merged.totalCount} tracks (retry found $found)."
                } else {
                    "Matched ${merged.matched.size} of ${merged.totalCount} tracks. The retry found nothing new."
                }
            )
            finishTransfer("Retry found $found")
        }
    }

    fun onConfirmPlaylistImport(playlistName: String) {
        val preview = _uiState.value.playlistImportPreview ?: return
        val name = playlistName.ifBlank { preview.sourceName }
        val playlist = libraryRepository.createPlaylist(name) ?: return

        libraryRepository.addTracksToPlaylist(playlist.id, preview.matched)
        refreshLibrary()
        _uiState.value = _uiState.value.copy(
            playlistImportPreview = null,
            playlistImportStatus = "✓ Created \"$name\" with ${preview.matched.size} tracks."
        )
    }

    fun onCancelPlaylistImport() {
        val status = _uiState.value.playlistImportStatus

        if (status?.startsWith("Retrying") == true) {
            cancelTransfer()
            _uiState.value = _uiState.value.copy(playlistImportStatus = "Retry cancelled.")
            return
        }
        val running = _uiState.value.playlistImportPreview == null &&
            (status?.startsWith("Matching") == true || status?.startsWith("Reading") == true)
        if (running) cancelTransfer()
        _uiState.value = _uiState.value.copy(playlistImportPreview = null, playlistImportStatus = null)
    }

    fun onImportPlaylistLink(url: String) {
        if (url.isBlank()) return
        enqueueTransfer {
            beginTransfer("Opening playlist…")
            _uiState.value = _uiState.value.copy(
                playlistImportStatus = "Opening link…",
                playlistImportPreview = null
            )
            val result = try {
                repository.importPlaylistLink(url) { done, total ->
                    updateTransferProgress(if (total > 0) done.toFloat() / total else -1f)
                    _uiState.value = _uiState.value.copy(playlistImportStatus = "Matching $done of $total…")
                }
            } catch (e: CancellationException) {
                _uiState.value = _uiState.value.copy(playlistImportStatus = "Import cancelled.", playlistImportPreview = null)
                throw e
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(playlistImportStatus = "✗ ${e.message ?: e}")
                finishTransfer("Import failed")
                return@enqueueTransfer
            }
            _uiState.value = _uiState.value.copy(
                playlistImportStatus = "Matched ${result.matched.size} of ${result.totalCount} tracks.",
                playlistImportPreview = result
            )
            finishTransfer("Matched ${result.matched.size} tracks")
        }
    }

    fun onImportCsvPlaylist(csvText: String, playlistName: String = "") {
        enqueueTransfer {
            beginTransfer("Matching playlist…")
            _uiState.value = _uiState.value.copy(
                playlistImportStatus = "Reading CSV…",
                playlistImportPreview = null
            )
            val result = try {
                repository.importCsvPlaylist(csvText, playlistName) { done, total ->
                    updateTransferProgress(if (total > 0) done.toFloat() / total else -1f)
                    _uiState.value = _uiState.value.copy(
                        playlistImportStatus = "Matching $done of $total…"
                    )
                }
            } catch (e: CancellationException) {
                _uiState.value = _uiState.value.copy(
                    playlistImportStatus = "Import cancelled.",
                    playlistImportPreview = null
                )
                throw e
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(playlistImportStatus = "✗ ${e.message ?: e}")
                finishTransfer("Import failed")
                return@enqueueTransfer
            }
            _uiState.value = _uiState.value.copy(
                playlistImportStatus = "Matched ${result.matched.size} of ${result.totalCount} tracks.",
                playlistImportPreview = result
            )
            finishTransfer("Matched ${result.matched.size} tracks")
        }
    }

    fun exportPluginsJson(): String {
        val state = _uiState.value
        val sources = state.sources
            .filter { !it.builtIn && it.kind == SourceKind.SERVER }
            .map { ImportedSource(it.name, it.baseUrl) }
        val themes = state.themes
            .filter { !it.builtIn }
            .map { ImportedTheme(it.name, it.primary, it.secondary, it.tertiary, it.background, it.dark) }
        return importJson.encodeToString(PluginBundle(sources, themes))
    }

    fun onTestBackend(url: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(backendTestStatus = "Testing…")
            val status = try {
                repository.testBackend(url)
                "✓ Connected: backend is working."
            } catch (e: SearchUnavailableException) {
                "✗ ${e.message}"
            } catch (e: Exception) {
                "✗ ${e.message ?: e.toString()}"
            }
            _uiState.value = _uiState.value.copy(backendTestStatus = status)
        }
    }
}
