package dev.spinet.audio

import net.sourceforge.jaad.SampleBuffer
import net.sourceforge.jaad.aac.Decoder
import java.io.File
import java.io.RandomAccessFile

internal object AacToWav {

    fun convert(input: File): File {
        val data = input.readBytes()
        val track = Mp4Audio.read(data)
        val decoder = Decoder.create(track.config)
        val buffer = SampleBuffer().apply { isBigEndian = false }
        val out = File.createTempFile("spinet_audio_", ".wav")
        var rate = 0
        var channels = 0
        var bits = 16
        var written = 0L
        try {
            RandomAccessFile(out, "rw").use { raf ->
                raf.setLength(0)
                raf.write(ByteArray(44))
                for (i in track.offsets.indices) {
                    val start = track.offsets[i]
                    val size = track.sizes[i]
                    if (start < 0 || size <= 0 || start + size > data.size) continue
                    val decoded = runCatching { decoder.decodeFrame(data.copyOfRange(start, start + size), buffer) }
                    if (decoded.isFailure) continue
                    val pcm = buffer.data ?: continue
                    if (pcm.isEmpty()) continue
                    if (rate == 0) {
                        rate = buffer.sampleRate
                        channels = buffer.channels
                        bits = buffer.bitsPerSample
                    }
                    raf.write(pcm)
                    written += pcm.size
                }
                check(written > 0 && rate > 0) { "no audio could be decoded" }
                raf.seek(0)
                raf.write(wavHeader(written, rate, channels, bits))
            }
        } catch (e: Exception) {
            out.delete()
            throw e
        }
        return out
    }

    private fun wavHeader(dataLength: Long, rate: Int, channels: Int, bits: Int): ByteArray {
        val h = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val blockAlign = channels * bits / 8
        h.put("RIFF".toByteArray(Charsets.US_ASCII))
        h.putInt((36 + dataLength).coerceAtMost(0xFFFFFFFFL).toInt())
        h.put("WAVE".toByteArray(Charsets.US_ASCII))
        h.put("fmt ".toByteArray(Charsets.US_ASCII))
        h.putInt(16)
        h.putShort(1)
        h.putShort(channels.toShort())
        h.putInt(rate)
        h.putInt(rate * blockAlign)
        h.putShort(blockAlign.toShort())
        h.putShort(bits.toShort())
        h.put("data".toByteArray(Charsets.US_ASCII))
        h.putInt(dataLength.coerceAtMost(0xFFFFFFFFL).toInt())
        return h.array()
    }
}

internal class Mp4Audio(val config: ByteArray, val offsets: IntArray, val sizes: IntArray) {

    private class Box(val type: String, val start: Int, val body: Int, val end: Int)

    companion object {

        fun read(d: ByteArray): Mp4Audio {
            val top = boxes(d, 0, d.size)
            val moov = top.firstOrNull { it.type == "moov" } ?: error("no moov box")
            val trak = children(d, moov).filter { it.type == "trak" }.firstOrNull { isSound(d, it) }
                ?: error("no audio track")
            val trackId = child(d, trak, "tkhd")?.let { trackIdOf(d, it) } ?: 1
            val stbl = path(d, trak, "mdia", "minf", "stbl") ?: error("no sample table")
            val config = audioConfig(d, stbl) ?: error("no AAC decoder config")

            val offsets = ArrayList<Int>()
            val sizes = ArrayList<Int>()
            plainSamples(d, stbl, offsets, sizes)

            val trex = child(d, moov, "mvex")?.let { mvex ->
                children(d, mvex).firstOrNull { it.type == "trex" && u32(d, it.body + 4).toInt() == trackId }
            }
            val defaultSize = trex?.let { u32(d, it.body + 16).toInt() } ?: 0
            for (moof in top.filter { it.type == "moof" }) {
                fragmentSamples(d, moof, trackId, defaultSize, offsets, sizes)
            }
            check(offsets.isNotEmpty()) { "no audio samples" }
            return Mp4Audio(config, offsets.toIntArray(), sizes.toIntArray())
        }

        private fun boxes(d: ByteArray, from: Int, to: Int): List<Box> {
            val list = ArrayList<Box>()
            var i = from
            while (i + 8 <= to) {
                var size = u32(d, i)
                val type = String(d, i + 4, 4, Charsets.ISO_8859_1)
                var header = 8
                if (size == 1L) {
                    if (i + 16 > to) break
                    size = u64(d, i + 8)
                    header = 16
                } else if (size == 0L) {
                    size = (to - i).toLong()
                }
                if (size < header || i + size > to) break
                list += Box(type, i, i + header, (i + size).toInt())
                i += size.toInt()
            }
            return list
        }

        private fun children(d: ByteArray, box: Box, skip: Int = 0) = boxes(d, box.body + skip, box.end)

        private fun child(d: ByteArray, box: Box, type: String) = children(d, box).firstOrNull { it.type == type }

        private fun path(d: ByteArray, box: Box, vararg types: String): Box? {
            var current = box
            for (t in types) current = child(d, current, t) ?: return null
            return current
        }

        private fun isSound(d: ByteArray, trak: Box): Boolean {
            val hdlr = path(d, trak, "mdia", "hdlr") ?: return false
            return String(d, hdlr.body + 8, 4, Charsets.ISO_8859_1) == "soun"
        }

        private fun trackIdOf(d: ByteArray, tkhd: Box): Int {
            val version = d[tkhd.body].toInt()
            return u32(d, tkhd.body + if (version == 1) 20 else 12).toInt()
        }

        private fun audioConfig(d: ByteArray, stbl: Box): ByteArray? {
            val stsd = child(d, stbl, "stsd") ?: return null
            val entry = children(d, stsd, skip = 8).firstOrNull { it.type == "mp4a" } ?: return null
            val version = u16(d, entry.body + 8)
            val extra = when (version) {
                1 -> 16
                2 -> 36
                else -> 0
            }
            val esds = boxes(d, entry.body + 28 + extra, entry.end).firstOrNull { it.type == "esds" }
                ?: boxes(d, entry.body + 28, entry.end).firstOrNull { it.type == "esds" }
                ?: return null
            return decoderSpecificInfo(d, esds.body + 4, esds.end)
        }

        private fun decoderSpecificInfo(d: ByteArray, from: Int, to: Int): ByteArray? {
            var i = from
            while (i < to) {
                val tag = d[i].toInt() and 0xff
                i++
                var length = 0
                for (k in 0 until 4) {
                    val b = d[i].toInt() and 0xff
                    i++
                    length = (length shl 7) or (b and 0x7f)
                    if (b and 0x80 == 0) break
                }
                when (tag) {
                    0x03 -> {
                        val flags = d[i + 2].toInt() and 0xff
                        i += 3
                        if (flags and 0x80 != 0) i += 2
                        if (flags and 0x40 != 0) i += 1 + (d[i].toInt() and 0xff)
                        if (flags and 0x20 != 0) i += 2
                    }
                    0x04 -> i += 13
                    0x05 -> return if (i + length <= to) d.copyOfRange(i, i + length) else null
                    else -> i += length
                }
            }
            return null
        }

        private fun plainSamples(d: ByteArray, stbl: Box, offsets: MutableList<Int>, sizes: MutableList<Int>) {
            val stsz = child(d, stbl, "stsz") ?: return
            val fixed = u32(d, stsz.body + 4).toInt()
            val count = u32(d, stsz.body + 8).toInt()
            if (count == 0) return
            val sampleSizes = IntArray(count) { if (fixed != 0) fixed else u32(d, stsz.body + 12 + it * 4).toInt() }

            val chunkOffsets: LongArray = child(d, stbl, "stco")?.let { box ->
                LongArray(u32(d, box.body + 4).toInt()) { u32(d, box.body + 8 + it * 4) }
            } ?: child(d, stbl, "co64")?.let { box ->
                LongArray(u32(d, box.body + 4).toInt()) { u64(d, box.body + 8 + it * 8) }
            } ?: return

            val stsc = child(d, stbl, "stsc") ?: return
            val entries = u32(d, stsc.body + 4).toInt()
            val firstChunk = IntArray(entries) { u32(d, stsc.body + 8 + it * 12).toInt() }
            val perChunk = IntArray(entries) { u32(d, stsc.body + 12 + it * 12).toInt() }

            var sample = 0
            for (chunk in chunkOffsets.indices) {
                val number = chunk + 1
                var e = 0
                while (e + 1 < entries && firstChunk[e + 1] <= number) e++
                var position = chunkOffsets[chunk]
                repeat(perChunk.getOrElse(e) { 0 }) {
                    if (sample >= count) return
                    offsets += position.toInt()
                    sizes += sampleSizes[sample]
                    position += sampleSizes[sample]
                    sample++
                }
            }
        }

        private fun fragmentSamples(
            d: ByteArray,
            moof: Box,
            trackId: Int,
            trexDefaultSize: Int,
            offsets: MutableList<Int>,
            sizes: MutableList<Int>
        ) {
            for (traf in children(d, moof).filter { it.type == "traf" }) {
                val tfhd = child(d, traf, "tfhd") ?: continue
                val tfFlags = u32(d, tfhd.body) and 0xFFFFFF
                if (u32(d, tfhd.body + 4).toInt() != trackId) continue
                var p = tfhd.body + 8
                var base = moof.start.toLong()
                if (tfFlags and 0x01 != 0L) { base = u64(d, p); p += 8 }
                if (tfFlags and 0x02 != 0L) p += 4
                if (tfFlags and 0x08 != 0L) p += 4
                var defaultSize = trexDefaultSize
                if (tfFlags and 0x10 != 0L) { defaultSize = u32(d, p).toInt(); p += 4 }

                var next = base
                for (trun in children(d, traf).filter { it.type == "trun" }) {
                    val flags = u32(d, trun.body) and 0xFFFFFF
                    val count = u32(d, trun.body + 4).toInt()
                    var q = trun.body + 8
                    var position = next
                    if (flags and 0x01 != 0L) { position = base + u32(d, q).toInt(); q += 4 }
                    if (flags and 0x04 != 0L) q += 4
                    repeat(count) {
                        if (flags and 0x100 != 0L) q += 4
                        var size = defaultSize
                        if (flags and 0x200 != 0L) { size = u32(d, q).toInt(); q += 4 }
                        if (flags and 0x400 != 0L) q += 4
                        if (flags and 0x800 != 0L) q += 4
                        offsets += position.toInt()
                        sizes += size
                        position += size
                    }
                    next = position
                }
            }
        }

        private fun u16(d: ByteArray, i: Int): Int =
            ((d[i].toInt() and 0xff) shl 8) or (d[i + 1].toInt() and 0xff)

        private fun u32(d: ByteArray, i: Int): Long =
            ((d[i].toLong() and 0xff) shl 24) or ((d[i + 1].toLong() and 0xff) shl 16) or
                ((d[i + 2].toLong() and 0xff) shl 8) or (d[i + 3].toLong() and 0xff)

        private fun u64(d: ByteArray, i: Int): Long = (u32(d, i) shl 32) or u32(d, i + 4)
    }
}
