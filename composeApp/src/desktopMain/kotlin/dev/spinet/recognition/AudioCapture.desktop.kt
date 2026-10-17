package dev.spinet.recognition

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.Mixer
import javax.sound.sampled.TargetDataLine

actual fun createAudioCapture(): AudioCapture? = DesktopAudioCapture()

private const val LOOPBACK_ID = 9000

private class DesktopAudioCapture : AudioCapture {
    private data class Entry(val info: Mixer.Info, val format: AudioFormat)

    private val candidateFormats = listOf(
        AudioFormat(16000f, 16, 1, true, false),
        AudioFormat(44100f, 16, 1, true, false),
        AudioFormat(44100f, 16, 2, true, false),
        AudioFormat(48000f, 16, 2, true, false),
    )

    private val systemAudioHints = listOf(
        "stereo mix", "stereomix", "what u hear", "what you hear",
        "loopback", "wave out", "cable output", "voicemeeter out",
    )

    private fun entries(): List<Entry> =
        AudioSystem.getMixerInfo().mapNotNull { info ->
            val mixer = try {
                AudioSystem.getMixer(info)
            } catch (_: Exception) {
                return@mapNotNull null
            }
            val format = candidateFormats.firstOrNull { f ->
                try {
                    mixer.isLineSupported(DataLine.Info(TargetDataLine::class.java, f))
                } catch (_: Exception) {
                    false
                }
            }
            format?.let { Entry(info, it) }
        }

    override fun sources(): List<CaptureSource> = buildList {
        if (WasapiLoopback.isSupported) {
            add(CaptureSource(id = LOOPBACK_ID, name = "This PC's audio", isSystemAudio = true))
        }

        entries().forEachIndexed { index, entry ->
            val lower = entry.info.name.lowercase()
            add(
                CaptureSource(
                    id = index,
                    name = entry.info.name,
                    isSystemAudio = systemAudioHints.any { it in lower },
                )
            )
        }
    }

    override suspend fun record(sourceId: Int, seconds: Int): ShortArray? =
        withContext(Dispatchers.IO) {
            if (sourceId == LOOPBACK_ID) {
                return@withContext WasapiLoopback.capture(seconds)
            }
            val entry = entries().getOrNull(sourceId) ?: return@withContext null
            runCatching { capture(entry, seconds) }.getOrNull()
        }

    private fun capture(entry: Entry, seconds: Int): ShortArray? {
        val format = entry.format
        val line = AudioSystem.getMixer(entry.info)
            .getLine(DataLine.Info(TargetDataLine::class.java, format)) as TargetDataLine
        line.open(format)
        line.start()
        val frameSize = format.frameSize
        val totalBytes = (format.sampleRate.toInt() * seconds) * frameSize
        val buf = ByteArray(totalBytes)
        var off = 0
        try {
            while (off < buf.size) {
                val n = line.read(buf, off, minOf(8192, buf.size - off))
                if (n <= 0) break
                off += n
            }
        } finally {
            line.stop()
            line.close()
        }
        val frames = off / frameSize

        if (frames < format.sampleRate.toInt()) return null
        return toMono16k(buf, frames, format)
    }

    private fun toMono16k(buf: ByteArray, frames: Int, format: AudioFormat): ShortArray {
        val channels = format.channels
        val frameSize = format.frameSize
        val mono = FloatArray(frames)
        for (f in 0 until frames) {
            var sum = 0
            val base = f * frameSize
            for (ch in 0 until channels) {
                val lo = buf[base + ch * 2].toInt() and 0xFF
                val hi = buf[base + ch * 2 + 1].toInt()
                sum += (hi shl 8) or lo
            }
            mono[f] = sum.toFloat() / channels
        }
        val srcRate = format.sampleRate.toDouble()
        if (srcRate == 16000.0) {
            return ShortArray(frames) { mono[it].toInt().coerceIn(-32768, 32767).toShort() }
        }
        val outLen = (frames * 16000 / srcRate).toInt()
        val out = ShortArray(outLen)
        val step = srcRate / 16000.0
        for (i in 0 until outLen) {
            val pos = i * step
            val i0 = pos.toInt().coerceAtMost(frames - 1)
            val i1 = (i0 + 1).coerceAtMost(frames - 1)
            val frac = (pos - i0).toFloat()
            val v = mono[i0] * (1 - frac) + mono[i1] * frac
            out[i] = v.toInt().coerceIn(-32768, 32767).toShort()
        }
        return out
    }
}
