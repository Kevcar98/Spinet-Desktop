package dev.spinet.download

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import dev.spinet.data.nowEpochMillis
import dev.spinet.model.Track
import dev.spinet.model.matchKey

@Serializable
data class UploadRecord(
    val server: String,
    val folder: String,
    val key: String,
    val title: String,
    val artist: String,
    val uploadedAt: Long
)

data class SeedEntry(
    val key: String,
    val folder: String,
    val title: String,
    val artist: String,
    val at: Long,

    val deleted: Boolean
)

@Serializable
data class UploadLedgerFile(
    val updatedAt: Long = 0L,
    val songs: List<UploadRecord> = emptyList()
)

class UploadLedger(
    private val json: Json = Json { ignoreUnknownKeys = true; prettyPrint = true }
) {
    private var cache: MutableMap<String, UploadRecord>? = null

    private fun idOf(server: String, folder: String, key: String) =
        "${server.trim().trimEnd('/')}|${folder.trim()}|$key"

    private suspend fun songs(): MutableMap<String, UploadRecord> {
        cache?.let { return it }
        val raw = readUploadLedgerFile()
        val loaded = if (raw.isNullOrBlank()) {
            mutableMapOf()
        } else {
            try {
                json.decodeFromString<UploadLedgerFile>(raw)
                    .songs.associateBy { idOf(it.server, it.folder, it.key) }
                    .toMutableMap()
            } catch (e: Exception) {
                mutableMapOf()
            }
        }
        cache = loaded
        return loaded
    }

    private suspend fun persist() {
        val all = cache.orEmpty().values.sortedBy { it.uploadedAt }
        writeUploadLedgerFile(
            json.encodeToString(UploadLedgerFile(updatedAt = nowEpochMillis(), songs = all))
        )
    }

    suspend fun isUploaded(server: String, folder: String, track: Track): Boolean =
        songs().containsKey(idOf(server, folder, track.matchKey()))

    suspend fun record(server: String, folder: String, track: Track) {
        val all = songs()
        val key = track.matchKey()
        all[idOf(server, folder, key)] = UploadRecord(
            server = server.trim().trimEnd('/'),
            folder = folder.trim(),
            key = key,
            title = track.title,
            artist = track.artist,
            uploadedAt = nowEpochMillis()
        )
        persist()
    }

    suspend fun isEmptyFor(server: String): Boolean {
        val host = server.trim().trimEnd('/')
        return songs().values.none { it.server == host }
    }

    suspend fun seedFrom(server: String, history: List<SeedEntry>): Int {
        if (history.isEmpty()) return 0
        val host = server.trim().trimEnd('/')
        val replayed = mutableMapOf<String, SeedEntry>()
        history.forEach { entry ->
            val id = idOf(host, entry.folder, entry.key)
            if (entry.deleted) replayed.remove(id) else replayed[id] = entry
        }

        val all = songs()
        var added = 0
        replayed.forEach { (id, entry) ->
            if (all.containsKey(id)) return@forEach
            all[id] = UploadRecord(
                server = host,
                folder = entry.folder,
                key = entry.key,
                title = entry.title,
                artist = entry.artist,
                uploadedAt = entry.at
            )
            added++
        }
        if (added > 0) persist()
        return added
    }

    suspend fun pruneTo(server: String, presentKeys: Set<String>): Int {
        val all = songs()
        val host = server.trim().trimEnd('/')
        val stale = all.filterValues { it.server == host && it.key !in presentKeys }.keys
        if (stale.isEmpty()) return 0
        stale.toList().forEach { all.remove(it) }
        persist()
        return stale.size
    }

    suspend fun forget(server: String, folder: String) {
        val all = songs()
        val prefix = idOf(server, folder, "")
        val removed = all.keys.filter { it.startsWith(prefix) }
        if (removed.isEmpty()) return
        removed.forEach { all.remove(it) }
        persist()
    }
}
