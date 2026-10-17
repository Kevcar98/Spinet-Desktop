package dev.spinet.download

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import dev.spinet.data.nowEpochMillis
import dev.spinet.model.Track
import dev.spinet.model.matchKey

@Serializable
data class DownloadRecord(
    val key: String,
    val title: String,
    val artist: String,
    val location: String,
    val savedAt: Long
)

@Serializable
data class DownloadLedgerFile(
    val folder: String = "",
    val updatedAt: Long = 0L,
    val songs: List<DownloadRecord> = emptyList()
)

class DownloadLedger(
    private val json: Json = Json { ignoreUnknownKeys = true; prettyPrint = true }
) {
    private val cache = mutableMapOf<String, MutableMap<String, DownloadRecord>>()

    private suspend fun songsFor(folder: String): MutableMap<String, DownloadRecord> {
        val name = folder.trim()
        cache[name]?.let { return it }
        val raw = readDownloadLedgerFile(name)
        val loaded = if (raw.isNullOrBlank()) {
            mutableMapOf()
        } else {
            try {
                json.decodeFromString<DownloadLedgerFile>(raw)
                    .songs.associateBy { it.key }.toMutableMap()
            } catch (e: Exception) {
                mutableMapOf()
            }
        }
        cache[name] = loaded
        return loaded
    }

    private suspend fun persist(folder: String) {
        val name = folder.trim()
        val songs = cache[name].orEmpty().values.sortedBy { it.savedAt }
        val written = writeDownloadLedgerFile(
            name,
            json.encodeToString(
                DownloadLedgerFile(
                    folder = name,
                    updatedAt = nowEpochMillis(),
                    songs = songs
                )
            )
        )
        if (!written) {
            cache.remove(name)
        }
    }

    suspend fun isSaved(folder: String, track: Track): Boolean =
        songsFor(folder).containsKey(track.matchKey())

    suspend fun record(folder: String, track: Track, location: String) {
        val songs = songsFor(folder)
        songs[track.matchKey()] = DownloadRecord(
            key = track.matchKey(),
            title = track.title,
            artist = track.artist,
            location = location,
            savedAt = nowEpochMillis()
        )
        persist(folder)
    }

    suspend fun countFor(folder: String): Int = songsFor(folder).size

    suspend fun forget(folder: String) {
        cache[folder.trim()] = mutableMapOf()
        persist(folder)
    }
}
