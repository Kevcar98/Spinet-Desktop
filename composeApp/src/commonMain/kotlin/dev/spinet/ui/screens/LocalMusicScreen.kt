package dev.spinet.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.spinet.model.SourcePlugin
import dev.spinet.model.Track
import dev.spinet.model.matchKey

@Composable
fun LocalMusicScreen(
    folderLabel: String,
    tracks: List<Track>,
    loading: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onTrackClick: (Track) -> Unit,
    favoriteTrackIds: Set<String> = emptySet(),
    onToggleFavorite: ((Track) -> Unit)? = null,
    serverTargets: List<SourcePlugin> = emptyList(),
    onUploadToServer: ((String) -> Unit)? = null,
    onUploadTrackToServer: ((Track, String) -> Unit)? = null,
    onDeleteTrack: ((Track) -> Unit)? = null,
    onDeleteAll: (() -> Unit)? = null,
    onMoveTrackToFolder: ((Track) -> Unit)? = null,
    onMove: ((Int, Int) -> Unit)? = null,
    isOnServer: (Track) -> Boolean = { it.serverBaseUrl.isNotBlank() },

    deleteAllWhere: String = "this device",

    currentTrackKey: String? = null,
    playingProgress: Float = 0f,

    onPlayAll: (() -> Unit)? = null,

    shuffle: Boolean = false,
    onToggleShuffle: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val hasFolder = folderLabel.isNotBlank()
    var menuOpen by remember { mutableStateOf(false) }
    var confirmDeleteAll by remember { mutableStateOf(false) }
    val reorderable = onMove != null
    var reorderMode by remember { mutableStateOf(false) }
    var sortMode by remember { mutableStateOf(SortMode.CUSTOM) }

    var filter by remember { mutableStateOf("") }
    val shownTracks = remember(tracks, sortMode, filter) {
        val sorted = sortTracks(tracks, sortMode)
        val needle = filter.trim()
        if (needle.isBlank()) sorted else sorted.filter {
            it.title.contains(needle, ignoreCase = true) ||
                it.artist.contains(needle, ignoreCase = true)
        }
    }
    val reorderState = rememberReorderState()

    Column(modifier = modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                text = if (hasFolder) folderLabel else "On this device",
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (hasFolder) {
                if (onToggleShuffle != null) {
                    IconButton(onClick = onToggleShuffle) {
                        Icon(
                            Icons.Filled.Shuffle,
                            contentDescription = if (shuffle) "Shuffle on" else "Shuffle off",
                            tint = if (shuffle) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                        )
                    }
                }
                if (shownTracks.isNotEmpty()) {
                    FilledIconButton(onClick = {
                        onPlayAll?.invoke() ?: onTrackClick(shownTracks.first())
                    }) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = "Play all")
                    }
                }
                SortButton(sortMode) { sortMode = it }
                if (reorderable) {
                    IconButton(
                        onClick = { reorderMode = !reorderMode },
                        enabled = sortMode == SortMode.CUSTOM
                    ) {
                        Icon(
                            Icons.Filled.SwapVert,
                            contentDescription = if (reorderMode) "Reorder on" else "Reorder off",
                            tint = if (reorderMode && sortMode == SortMode.CUSTOM) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                        )
                    }
                }
                IconButton(onClick = onRefresh) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Rescan folder")
                }
                val hasUpload = onUploadToServer != null && serverTargets.isNotEmpty()
                if (hasUpload || onDeleteAll != null) {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "More")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        if (hasUpload) {
                            serverTargets.forEach { target ->
                                DropdownMenuItem(
                                    text = { Text("Upload all to ${target.name}") },
                                    onClick = { menuOpen = false; onUploadToServer!!(target.baseUrl) }
                                )
                            }
                        }
                        if (onDeleteAll != null) {
                            DropdownMenuItem(
                                text = { Text("Delete all from $deleteAllWhere") },
                                onClick = { menuOpen = false; confirmDeleteAll = true }
                            )
                        }
                    }
                }
            }
        }

        if (hasFolder && tracks.size >= 10) {
            OutlinedTextField(
                value = filter,
                onValueChange = { filter = it },
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (filter.isNotBlank()) {
                        IconButton(onClick = { filter = "" }) {
                            Icon(Icons.Filled.Close, contentDescription = "Clear")
                        }
                    }
                },
                placeholder = { Text("Filter these tracks") },
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
            )
        }

        if (confirmDeleteAll && onDeleteAll != null) {
            AlertDialog(
                onDismissRequest = { confirmDeleteAll = false },
                title = { Text("Delete all?") },
                text = { Text("Permanently delete ${tracks.size} tracks from $deleteAllWhere?") },
                confirmButton = {
                    TextButton(onClick = { confirmDeleteAll = false; onDeleteAll() }) { Text("Delete") }
                },
                dismissButton = {
                    TextButton(onClick = { confirmDeleteAll = false }) { Text("Cancel") }
                }
            )
        }

        if (!hasFolder) {
            Text(
                text = "No music folder chosen yet. Pick one in Settings > Local music. Files inside play here, even offline.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp)
            )
            return@Column
        }

        when {
            loading -> Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator()
                Text("Scanning folder…", modifier = Modifier.padding(start = 12.dp))
            }

            tracks.isEmpty() -> Text(
                text = "No audio files found in this folder.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp)
            )

            else -> {
                val listState = androidx.compose.foundation.lazy.rememberLazyListState()
                androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                itemsIndexed(shownTracks, key = { _, track -> track.id }) { index, track ->

                    val grouping = sortMode == SortMode.ARTIST_AZ || sortMode == SortMode.ARTIST_ZA
                    val prevArtist = shownTracks.getOrNull(index - 1)?.artist?.trim()?.lowercase()
                    if (grouping && prevArtist != track.artist.trim().lowercase()) {
                        Text(
                            text = track.artist.ifBlank { "Unknown artist" },
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp, start = 4.dp)
                        )
                    }
                    val isCurrent = currentTrackKey != null && track.matchKey() == currentTrackKey
                    TrackRow(
                        modifier = Modifier.reorderableItem(reorderState, index),
                        track = track,
                        onClick = { onTrackClick(track) },
                        isCurrent = isCurrent,
                        progress = if (isCurrent) playingProgress else 0f,
                        isFavorite = track.id in favoriteTrackIds,
                        alsoOnServer = isOnServer(track),
                        serverTargets = serverTargets,
                        onToggleFavorite = onToggleFavorite?.let { cb -> { cb(track) } },
                        onUploadToServer = onUploadTrackToServer?.let { cb -> { baseUrl -> cb(track, baseUrl) } },
                        onDeleteFromDevice = onDeleteTrack?.let { cb -> { cb(track) } },
                        onMoveToFolder = onMoveTrackToFolder?.let { cb -> { cb(track) } },
                        leadingHandle = if (reorderable && reorderMode && sortMode == SortMode.CUSTOM) {
                            {
                                DragHandle(
                                    Modifier.reorderHandle(
                                        state = reorderState,
                                        index = index,
                                        itemCount = shownTracks.size,
                                        key = track.id,
                                        onMove = { from, to -> onMove?.invoke(from, to) }
                                    )
                                )
                            }
                        } else null
                    )
                }
                }
                FastScrollbar(listState, Modifier.align(Alignment.CenterEnd))
                }
            }
        }
    }
}
