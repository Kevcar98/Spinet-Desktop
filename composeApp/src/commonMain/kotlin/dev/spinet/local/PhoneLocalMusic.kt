package dev.spinet.local

import androidx.compose.runtime.Composable
import dev.spinet.model.Track

data class LocalFolder(val uri: String, val label: String)

@Composable
expect fun PickLocalFolderButton(text: String, onPicked: (LocalFolder) -> Unit)

expect fun defaultLocalFolder(): LocalFolder?

expect fun isUsableLocalArt(path: String): Boolean

fun isUsableArt(url: String): Boolean =
    url.startsWith("http://", ignoreCase = true) ||
        url.startsWith("https://", ignoreCase = true) ||
        (url.isNotBlank() && isUsableLocalArt(url))

fun portableArt(url: String): String =
    if (url.startsWith("http://", ignoreCase = true) ||
        url.startsWith("https://", ignoreCase = true)
    ) url else ""

expect suspend fun listLocalTracks(folderUri: String): List<Track>

expect suspend fun readLocalTrackBytes(streamUrl: String): ByteArray?

expect suspend fun deleteLocalTrack(streamUrl: String): Boolean

expect suspend fun moveLocalTrack(streamUrl: String, rootUri: String, folder: String): Boolean

expect suspend fun embedLocalMetadata(
    streamUrl: String,
    title: String,
    artist: String,
    artworkBytes: ByteArray?
): Boolean
