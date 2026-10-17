package dev.spinet.local

import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dev.spinet.model.Track
import dev.spinet.model.TrackSource
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import java.io.File
import java.net.URI
import java.util.logging.Level
import java.util.logging.Logger
import dev.spinet.ui.DesktopFilePickers

private val AUDIO_EXTS = setOf(
    "mp3", "flac", "m4a", "m4b", "mp4", "ogg", "oga", "opus", "wav", "aac", "aiff", "aif"
)

private val quietTagger: Unit = run {
    Logger.getLogger("org.jaudiotagger").level = Level.WARNING
}

@Composable
actual fun PickLocalFolderButton(text: String, onPicked: (LocalFolder) -> Unit) {
    val scope = rememberCoroutineScope()
    OutlinedButton(onClick = {
        scope.launch {
            val dir = withContext(Dispatchers.IO) { DesktopFilePickers.chooseDirectory() }
            if (dir != null) onPicked(LocalFolder(dir.absolutePath, dir.name))
        }
    }) { Text(text) }
}

actual suspend fun moveLocalTrack(
    streamUrl: String,
    rootUri: String,
    folder: String
): Boolean = withContext(Dispatchers.IO) {
    runCatching {
        val file = File(URI(streamUrl))
        val root = File(rootUri)
        if (!file.isFile || !root.isDirectory) return@withContext false
        val destDir = if (folder.isBlank()) root else File(root, folder)
        if (!destDir.exists() && !destDir.mkdirs()) return@withContext false
        var dest = File(destDir, file.name)
        if (dest.absolutePath == file.absolutePath) return@withContext true
        var n = 1
        while (dest.exists()) {
            dest = File(destDir, "${file.nameWithoutExtension} ($n).${file.extension}")
            n++
        }
        file.renameTo(dest)
    }.getOrDefault(false)
}

actual suspend fun embedLocalMetadata(
    streamUrl: String,
    title: String,
    artist: String,
    artworkBytes: ByteArray?
): Boolean = withContext(Dispatchers.IO) {
    runCatching {
        val file = File(URI(streamUrl))
        if (!file.isFile) return@withContext false
        val audio = AudioFileIO.read(file)
        val tag = audio.tagOrCreateAndSetDefault
        if (title.isNotBlank()) tag.setField(FieldKey.TITLE, title)
        if (artist.isNotBlank()) tag.setField(FieldKey.ARTIST, artist)
        if (artworkBytes != null && artworkBytes.isNotEmpty()) {
            val art = org.jaudiotagger.tag.images.ArtworkFactory.getNew()
            art.binaryData = artworkBytes
            art.mimeType = if ((artworkBytes[0].toInt() and 0xFF) == 0x89) "image/png" else "image/jpeg"
            runCatching { tag.deleteArtworkField() }
            tag.setField(art)
        }
        audio.commit()

        true
    }.getOrDefault(false)
}

actual fun defaultLocalFolder(): LocalFolder? {
    val music = File(System.getProperty("user.home"), "Music")

    if (!music.isDirectory && !music.mkdirs()) return null
    return LocalFolder(music.absolutePath, music.name)
}

private val trackCache = java.util.concurrent.ConcurrentHashMap<String, Triple<Long, Long, Track>>()

actual suspend fun listLocalTracks(folderUri: String): List<Track> = withContext(Dispatchers.IO) {
    val root = runCatching { File(folderUri) }.getOrNull()?.takeIf { it.isDirectory }
        ?: return@withContext emptyList()

    root.walkTopDown()
        .filter { it.isFile && it.extension.lowercase() in AUDIO_EXTS }
        .map { file ->
            val key = "${root.absolutePath}|${file.absolutePath}"
            val mtime = file.lastModified()
            val size = file.length()
            val cached = trackCache[key]
            if (cached != null && cached.first == mtime && cached.second == size) {
                cached.third
            } else {
                buildTrack(file, topFolderOf(root, file)).also {
                    trackCache[key] = Triple(mtime, size, it)
                }
            }
        }
        .sortedBy { it.title.lowercase() }
        .toList()
}

private fun topFolderOf(root: File, file: File): String {
    val rel = runCatching { file.parentFile?.relativeTo(root)?.path.orEmpty() }.getOrDefault("")
    if (rel.isBlank() || rel == ".") return ""
    return rel.replace('\\', '/').substringBefore('/')
}

private fun buildTrack(file: File, folder: String = ""): Track {
    val stem = file.nameWithoutExtension
    var title = stem
    var artist = "Unknown artist"
    var durationLabel = ""
    var artUri = ""

    runCatching {
        val audio = AudioFileIO.read(file)
        audio.tag?.let { tag ->
            tag.getFirst(FieldKey.TITLE)?.takeIf { it.isNotBlank() }?.let { title = it }
            tag.getFirst(FieldKey.ARTIST)?.takeIf { it.isNotBlank() }?.let { artist = it }
            runCatching { tag.firstArtwork?.binaryData }.getOrNull()?.let { pic ->
                artUri = cacheArtwork(file.absolutePath, pic)
            }
        }
        audio.audioHeader?.trackLength?.let { secs -> durationLabel = formatDuration(secs.toLong()) }
    }

    if (artist == "Unknown artist") {
        val dash = stem.indexOf(" - ")
        if (dash > 0) {
            artist = stem.substring(0, dash).trim()
            title = stem.substring(dash + 3).trim()
        }
    }

    val fileUri = file.toURI().toString()
    return Track(
        id = "local:$fileUri",
        title = title,
        artist = artist,
        durationLabel = durationLabel,
        thumbnailUrl = artUri,
        source = TrackSource.LOCAL,
        itemId = "",
        streamUrl = fileUri,
        downloadable = false,
        localFolder = folder
    )
}

actual suspend fun readLocalTrackBytes(streamUrl: String): ByteArray? = withContext(Dispatchers.IO) {
    fileFromUrl(streamUrl)?.takeIf { it.isFile }?.let { runCatching { it.readBytes() }.getOrNull() }
}

actual suspend fun deleteLocalTrack(streamUrl: String): Boolean = withContext(Dispatchers.IO) {
    fileFromUrl(streamUrl)?.let { runCatching { it.delete() }.getOrDefault(false) } ?: false
}

private fun fileFromUrl(streamUrl: String): File? = runCatching {
    if (streamUrl.startsWith("file:")) File(URI(streamUrl)) else File(streamUrl)
}.getOrNull()

private fun cacheArtwork(sourcePath: String, bytes: ByteArray): String {
    return runCatching {
        val dir = File(System.getProperty("java.io.tmpdir"), "spinet-localart").apply { mkdirs() }
        val file = File(dir, "${sourcePath.hashCode()}.img")
        if (!file.exists() || file.length() != bytes.size.toLong()) file.writeBytes(bytes)
        file.absolutePath
    }.getOrDefault("")
}

private fun formatDuration(totalSeconds: Long): String {
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}

actual fun isUsableLocalArt(path: String): Boolean =
    runCatching { File(path).isFile }.getOrDefault(false)
