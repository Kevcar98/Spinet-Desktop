package dev.spinet.audio

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

enum class PlaybackState {
    IDLE,
    BUFFERING,
    PLAYING,
    PAUSED,
    ENDED,
    ERROR
}

enum class TransportCommand { NEXT, PREVIOUS }

data class NowPlayingInfo(
    val title: String,
    val artist: String,
    val artworkUrl: String?
)

interface AudioPlayer {
    val playbackState: Flow<PlaybackState>

    val positionMs: Flow<Long>

    val durationMs: Flow<Long>

    val nowPlaying: Flow<NowPlayingInfo?>

    val transportCommands: Flow<TransportCommand>

    fun play(url: String, title: String, artist: String, artworkUrl: String?)

    fun pause()

    fun stop() = pause()

    fun resume()

    fun seekTo(positionMs: Long)

    fun setVolume(volume: Float)

    val autoAdvanced: Flow<Unit> get() = emptyFlow()

    fun enqueueNext(url: String, title: String, artist: String, artworkUrl: String?) {}

    fun clearNext() {}

    fun prefetch(url: String) {}

    fun release()
}
