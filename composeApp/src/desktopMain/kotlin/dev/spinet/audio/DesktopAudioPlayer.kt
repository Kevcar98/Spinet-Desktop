package dev.spinet.audio

import javafx.application.Platform
import javafx.embed.swing.JFXPanel
import javafx.scene.media.Media
import javafx.scene.media.MediaPlayer
import javafx.util.Duration
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import kotlin.math.abs
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DesktopAudioPlayer : AudioPlayer {
    private companion object {
        const val POSITION_EMIT_MS = 250L
        val DECODABLE = setOf("m4a", "mp4", "m4b")

        const val SLOW_CHECK_MS = 6_000L
        const val SLOW_MIN_BYTES = 256L * 1024

        const val CHUNK_BYTES = 8L * 1024 * 1024
    }

    private val _playbackState = MutableStateFlow(PlaybackState.IDLE)
    override val playbackState: Flow<PlaybackState> = _playbackState

    private val _positionMs = MutableStateFlow(0L)
    override val positionMs: Flow<Long> = _positionMs

    private val _durationMs = MutableStateFlow(0L)
    override val durationMs: Flow<Long> = _durationMs

    private val _nowPlaying = MutableStateFlow<NowPlayingInfo?>(null)
    override val nowPlaying: Flow<NowPlayingInfo?> = _nowPlaying

    private val _transport = MutableSharedFlow<TransportCommand>()
    override val transportCommands: Flow<TransportCommand> = _transport.asSharedFlow()

    private var player: MediaPlayer? = null

    private val _autoAdvanced = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    override val autoAdvanced: Flow<Unit> = _autoAdvanced.asSharedFlow()

    private var nextPlayer: MediaPlayer? = null
    private var nextMedia: Media? = null
    private var nextTemp: File? = null
    private var nextInfo: NowPlayingInfo? = null

    private val fxStarted: Boolean by lazy {
        System.setProperty("prism.order", "sw")
        System.setProperty("prism.vsync", "false")
        runCatching {
            val latch = java.util.concurrent.CountDownLatch(1)
            Platform.startup { latch.countDown() }
            latch.await(10, TimeUnit.SECONDS)
        }.recoverCatching {
            if (it is IllegalStateException) true else { JFXPanel(); true }
        }
        true
    }

    private val fetchExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "desktop-audio-fetch").apply { isDaemon = true }
    }

    private val playExecutor = Executors.newCachedThreadPool { r ->
        Thread(r, "desktop-audio-play").apply { isDaemon = true }
    }

    private val activeCalls = java.util.concurrent.ConcurrentHashMap.newKeySet<Call>()

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)

            .readTimeout(8, TimeUnit.SECONDS)

            .callTimeout(120, TimeUnit.SECONDS)
            .build()
    }
    @Volatile private var loadGeneration = 0
    private var tempFile: File? = null

    private val prefetchCache = java.util.concurrent.ConcurrentHashMap<String, File>()

    @Volatile private var volume: Double = 1.0

    private var lastPositionEmit = 0L

    private fun onFx(block: () -> Unit) {
        check(fxStarted)
        if (Platform.isFxApplicationThread()) block() else Platform.runLater(block)
    }

    override fun play(url: String, title: String, artist: String, artworkUrl: String?) {
        playRequestedAt = System.currentTimeMillis()
        _nowPlaying.value = NowPlayingInfo(title, artist, artworkUrl)
        _playbackState.value = PlaybackState.BUFFERING
        _positionMs.value = 0L
        _durationMs.value = 0L
        lastPositionEmit = 0L
        val gen = ++loadGeneration

        cancelFetches()

        onFx {
            player?.dispose()
            player = null

            disposeNext()
        }

        prefetchCache.remove(url)?.let { file ->
            if (file.isFile && file.length() > 0) {
                log("play: prefetched file hit")
                onFx { startMedia(file.toURI().toString(), gen, temp = file) }
                return
            }
        }

        if (isDirectlyPlayable(url)) {
            log("play: direct stream (known-streamable type)")
            val fallback = url.takeIf { it.startsWith("http") }
            onFx { startMedia(url, gen, fallbackUrl = fallback) }
            return
        }

        if (isHttpMp4(url) && !decodeFirst) {
            log("play: direct m4a/mp4 stream attempt")
            onFx { startMedia(url, gen, fallbackUrl = url) }
            return
        }

        log("play: no streamable type: full temp download")
        downloadAndPlay(url, gen)
    }

    @Volatile private var playRequestedAt = 0L
    private fun log(msg: String) {
        val dt = System.currentTimeMillis() - playRequestedAt
        println("[DesktopAudioPlayer] +${dt}ms $msg")
    }

    private fun downloadAndPlay(url: String, gen: Int) {
        playExecutor.execute {
            try {
                val file = downloadToTemp(url)
                log("download finished (${file.length() / 1024} KB)")
                if (gen != loadGeneration) { file.delete(); return@execute }
                onFx { startMedia(file.toURI().toString(), gen, temp = file) }
            } catch (e: Exception) {
                System.err.println("[DesktopAudioPlayer] stream fetch failed: ${e.message}")
                if (gen == loadGeneration) _playbackState.value = PlaybackState.ERROR
            }
        }
    }

    private fun startMedia(source: String, gen: Int, temp: File? = null, fallbackUrl: String? = null) {
        if (gen != loadGeneration) { temp?.delete(); return }
        if (decodeFirst && playDecodedCopy(source, gen, discard = temp)) return
        runCatching {
            player?.dispose()
            tempFile?.takeIf { it != temp }?.delete()
            tempFile = temp
            val media = Media(source)
            val mp = MediaPlayer(media)
            mp.volume = volume
            player = mp
            wire(mp, media, gen, source, fallbackUrl)
            mp.play()
        }.onFailure {
            System.err.println("[DesktopAudioPlayer] could not open $source")
            if (fallbackUrl != null && gen == loadGeneration) {
                downloadAndPlay(fallbackUrl, gen)
            } else if (gen == loadGeneration && playDecodedCopy(source, gen)) {
                return@onFailure
            } else {
                it.printStackTrace()
                _playbackState.value = PlaybackState.ERROR
            }
        }
    }

    private fun wire(
        mp: MediaPlayer,
        media: Media,
        gen: Int,
        source: String,
        fallbackUrl: String?,
        fadeInAllowed: Boolean = true
    ) {
        run {
            mp.currentTimeProperty().addListener { _, _, t ->
                val ms = t.toMillis()

                val msLong = ms.toLong()
                if (abs(msLong - lastPositionEmit) >= POSITION_EMIT_MS) {
                    lastPositionEmit = msLong
                    _positionMs.value = msLong
                }

                val dur = _durationMs.value.toDouble()
                val fadeIn = if (fadeInAllowed) (ms / 400.0).coerceIn(0.0, 1.0) else 1.0
                val fadeOut = if (dur > 1_500 && nextPlayer == null) {
                    ((dur - ms) / 700.0).coerceIn(0.0, 1.0)
                } else 1.0
                mp.volume = volume * fadeIn * fadeOut
            }
            mp.setOnReady {
                log("media ready")
                _durationMs.value = media.duration.toMillis().toLong()
            }
            mp.statusProperty().addListener { _, _, status ->
                _playbackState.value = when (status) {
                    MediaPlayer.Status.PLAYING -> PlaybackState.PLAYING
                    MediaPlayer.Status.PAUSED -> PlaybackState.PAUSED
                    MediaPlayer.Status.STALLED -> PlaybackState.BUFFERING
                    else -> _playbackState.value
                }
            }
            mp.setOnEndOfMedia {
                if (nextPlayer != null) promoteNext() else _playbackState.value = PlaybackState.ENDED
            }
            mp.setOnError {
                System.err.println("[DesktopAudioPlayer] JavaFX error for $source: ${mp.error}")
                if (fallbackUrl != null && gen == loadGeneration) {
                    mp.dispose()
                    if (player == mp) player = null
                    downloadAndPlay(fallbackUrl, gen)
                } else if (gen == loadGeneration && playDecodedCopy(media.source, gen)) {
                    mp.dispose()
                    if (player == mp) player = null
                } else {
                    mp.error?.printStackTrace()
                    _playbackState.value = PlaybackState.ERROR
                }
            }
        }
    }

    @Volatile private var decodeFirst = System.getProperty("spinet.decodeAudio") == "true"

    private fun playDecodedCopy(source: String, gen: Int, discard: File? = null): Boolean {
        if (!source.startsWith("file:")) return false
        val file = runCatching { File(java.net.URI(source)) }.getOrNull() ?: return false
        if (!file.isFile || file.extension.lowercase() !in DECODABLE) return false
        log(if (decodeFirst) "decoding ${file.name} to plain audio" else "player can't open ${file.name}; decoding it to plain audio")
        decodeFirst = true
        _playbackState.value = PlaybackState.BUFFERING
        playExecutor.execute {
            val wav = try {
                AacToWav.convert(file).also { discard?.delete() }
            } catch (e: Exception) {
                discard?.delete()
                System.err.println("[DesktopAudioPlayer] decoding ${file.name} failed: $e")
                if (gen == loadGeneration) _playbackState.value = PlaybackState.ERROR
                return@execute
            }
            log("decoded to ${wav.length() / 1024} KB of plain audio")
            if (gen != loadGeneration) { wav.delete(); return@execute }
            onFx { startMedia(wav.toURI().toString(), gen, temp = wav) }
        }
        return true
    }

    private fun isDirectlyPlayable(url: String): Boolean {
        if (url.startsWith("file:")) {
            val p = url.substringBefore('?').lowercase()
            return p.endsWith(".mp3") || p.endsWith(".m4a") || p.endsWith(".aac") ||
                p.endsWith(".wav") || p.endsWith(".aiff") || p.endsWith(".mp4")
        }

        val path = url.substringBefore('?').lowercase()
        return path.endsWith(".mp3") || path.endsWith(".aac") ||
            path.endsWith(".wav") || path.endsWith(".aiff")
    }

    private fun isHttpMp4(url: String): Boolean {
        if (!url.startsWith("http")) return false
        val path = url.substringBefore('?').lowercase()
        return path.endsWith(".m4a") || path.endsWith(".mp4")
    }

    override fun enqueueNext(url: String, title: String, artist: String, artworkUrl: String?) {
        val gen = loadGeneration
        val info = NowPlayingInfo(title, artist, artworkUrl)

        fun build(source: String, temp: File?) = onFx {
            if (gen != loadGeneration) { temp?.delete(); return@onFx }
            disposeNext()
            runCatching {
                val media = Media(source)
                val mp = MediaPlayer(media)

                mp.volume = 0.0
                nextMedia = media
                nextPlayer = mp
                nextTemp = temp
                nextInfo = info
            }.onFailure { temp?.delete() }
        }

        prefetchCache.remove(url)?.let { file ->
            if (file.isFile && file.length() > 0) { build(file.toURI().toString(), file); return }
        }
        if (isDirectlyPlayable(url)) { build(url, null); return }
        fetchExecutor.execute {
            try {
                val file = prefetchCache.remove(url)?.takeIf { it.isFile && it.length() > 0 }
                    ?: downloadToTemp(url)
                if (gen != loadGeneration) { file.delete(); return@execute }
                build(file.toURI().toString(), file)
            } catch (e: Exception) {
            }
        }
    }

    override fun clearNext() = onFx { disposeNext() }

    private fun disposeNext() {
        nextPlayer?.dispose()
        nextPlayer = null
        nextMedia = null
        nextTemp?.delete()
        nextTemp = null
        nextInfo = null
    }

    private fun promoteNext() {
        val mp = nextPlayer ?: return
        val media = nextMedia
        val temp = nextTemp
        val info = nextInfo
        nextPlayer = null
        nextMedia = null
        nextTemp = null
        nextInfo = null

        val outgoing = player
        val outgoingTemp = tempFile
        player = mp
        tempFile = temp
        info?.let { _nowPlaying.value = it }
        _positionMs.value = 0L
        _durationMs.value = media?.duration?.toMillis()?.toLong() ?: 0L

        wire(mp, media ?: return, loadGeneration, "queued", fallbackUrl = null, fadeInAllowed = false)
        mp.volume = volume
        mp.play()
        _playbackState.value = PlaybackState.PLAYING
        _autoAdvanced.tryEmit(Unit)

        outgoing?.dispose()
        outgoingTemp?.takeIf { it != temp }?.delete()
    }

    override fun prefetch(url: String) {
        if (!url.startsWith("http") || prefetchCache.containsKey(url)) return
        fetchExecutor.execute {
            if (prefetchCache.containsKey(url)) return@execute
            try {
                val file = downloadToTemp(url)

                prefetchCache.keys
                    .filter { it != url }
                    .forEach { k -> prefetchCache.remove(k)?.delete() }
                prefetchCache[url] = file
            } catch (e: Exception) {
            }
        }
    }

    private fun downloadToTemp(url: String): File {
        var file: File? = null
        var output: java.io.OutputStream? = null
        try {
            var pos = 0L
            while (true) {
                val request = Request.Builder()
                    .url(url)
                    .header(
                        "User-Agent",
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                    )
                    .header("Range", "bytes=$pos-${pos + CHUNK_BYTES - 1}")
                    .build()
                val call = http.newCall(request)
                activeCalls += call
                try {
                    call.execute().use { response ->

                        if (response.code == 416 && output != null) return file!!
                        if (!response.isSuccessful) error("HTTP ${response.code}")
                        val input = response.body?.byteStream() ?: error("Empty response body")
                        var got = 0L
                        if (output == null) {
                            val head = ByteArray(16)
                            var headLen = 0
                            while (headLen < head.size) {
                                val r = input.read(head, headLen, head.size - headLen)
                                if (r < 0) break
                                headLen += r
                            }
                            file = File.createTempFile("spinet_audio_", sniffSuffix(head, headLen, url))
                                .also { it.deleteOnExit() }
                            output = file!!.outputStream()
                            output!!.write(head, 0, headLen)
                            got += headLen
                        }
                        got += copyWatchingSpeed(input, output!!, call)
                        pos += got
                        val total = response.header("Content-Range")
                            ?.substringAfterLast('/')?.toLongOrNull()
                        val finished = response.code != 206 ||
                            got < CHUNK_BYTES ||
                            (total != null && pos >= total)
                        if (finished) {
                            output!!.close()
                            return file!!
                        }
                    }
                } finally {
                    activeCalls -= call
                }
            }
        } catch (e: Exception) {
            runCatching { output?.close() }
            file?.delete()
            throw e
        }
    }

    private fun cancelFetches() {
        activeCalls.toList().forEach { it.cancel() }
    }

    private fun copyWatchingSpeed(input: java.io.InputStream, output: java.io.OutputStream, call: Call): Long {
        val started = System.currentTimeMillis()
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        var checked = false
        while (true) {
            val n = input.read(buffer)
            if (n < 0) return total
            output.write(buffer, 0, n)
            total += n
            if (!checked && System.currentTimeMillis() - started >= SLOW_CHECK_MS) {
                checked = true
                if (total < SLOW_MIN_BYTES) {
                    call.cancel()
                    error("download too slow (${total / 1024} KB in ${SLOW_CHECK_MS / 1000}s)")
                }
            }
        }
    }

    private fun sniffSuffix(head: ByteArray, len: Int, url: String): String {
        if (len >= 8 &&
            head[4] == 'f'.code.toByte() && head[5] == 't'.code.toByte() &&
            head[6] == 'y'.code.toByte() && head[7] == 'p'.code.toByte()
        ) return ".m4a"

        if (len >= 3 &&
            head[0] == 'I'.code.toByte() && head[1] == 'D'.code.toByte() && head[2] == '3'.code.toByte()
        ) return ".mp3"
        if (len >= 2 && (head[0].toInt() and 0xFF) == 0xFF && (head[1].toInt() and 0xE0) == 0xE0) return ".mp3"
        val ext = url.substringBefore('?').substringAfterLast('.', "").lowercase()
        return if (ext in setOf("mp3", "m4a", "aac", "wav", "aiff", "mp4")) ".$ext" else ".m4a"
    }

    override fun pause() = onFx { player?.pause() }

    override fun resume() = onFx { player?.play() }

    override fun seekTo(positionMs: Long) = onFx { player?.seek(Duration.millis(positionMs.toDouble())) }

    override fun setVolume(volume: Float) {
        this.volume = volume.coerceIn(0f, 1f).toDouble()
        onFx { player?.volume = this.volume }
    }

    override fun stop() {
        loadGeneration++
        cancelFetches()
        onFx {
            player?.dispose()
            player = null
            disposeNext()
            tempFile?.delete()
            tempFile = null
            _playbackState.value = PlaybackState.IDLE
            _positionMs.value = 0L
        }
    }

    override fun release() {
        loadGeneration++
        onFx {
            player?.dispose()
            player = null
            disposeNext()
            tempFile?.delete()
            tempFile = null
        }
        prefetchCache.values.forEach { it.delete() }
        prefetchCache.clear()
        cancelFetches()
        fetchExecutor.shutdownNow()
        playExecutor.shutdownNow()
    }
}
