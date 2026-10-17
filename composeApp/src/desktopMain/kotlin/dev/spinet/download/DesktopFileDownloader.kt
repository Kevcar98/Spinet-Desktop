package dev.spinet.download

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

private val AUDIO_EXTS = listOf("mp3", "m4a", "mp4", "flac", "ogg", "opus", "wav", "aac")

private fun openHttp(url: String): HttpURLConnection {
    val parsed = runCatching { URI(url).toURL() }
        .getOrElse { @Suppress("DEPRECATION") java.net.URL(url) }
    return parsed.openConnection() as HttpURLConnection
}

class DesktopFileDownloader : FileDownloader {
    override suspend fun downloadAudio(
        url: String,
        title: String,
        artist: String,
        artworkUrl: String,
        subfolder: String,
        onProgress: (Float) -> Unit
    ): DownloadResult = withContext(Dispatchers.IO) {
        val base = if (artist.isNotBlank()) "$artist - $title" else title
        val stem = sanitize(base)
        val sub = subfolder.trim().let { if (it.isBlank()) "" else sanitize(it) }

        val root = musicRootDir()
        val targetDir = if (sub.isBlank()) root else File(root, sub)
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            return@withContext DownloadResult.Error("Could not create folder: ${targetDir.absolutePath}")
        }

        val existing = AUDIO_EXTS.map { File(targetDir, "$stem.$it") }
            .firstOrNull { it.isFile && it.length() > 0 }
        if (existing != null) {
            onProgress(1f)
            val where = if (sub.isBlank()) targetDir.absolutePath else "${root.name}/$sub"
            return@withContext DownloadResult.Success(where)
        }

        val artwork = fetchArtwork(artworkUrl)
        var outFile: File? = null
        try {
            outFile = streamToFile(url, targetDir, stem, title, artist, artwork, onProgress)
            val where = if (sub.isBlank()) targetDir.absolutePath else "${root.name}/$sub"
            DownloadResult.Success(where)
        } catch (e: Exception) {
            outFile?.delete()
            DownloadResult.Error(e.message ?: "Download failed")
        }
    }

    private fun streamToFile(
        url: String,
        targetDir: File,
        stem: String,
        title: String,
        artist: String,
        artwork: ByteArray?,
        onProgress: (Float) -> Unit
    ): File {
        val stream = PiecewiseHttpInputStream(url) {
            openHttp(it).apply {
                connectTimeout = 15_000
                readTimeout = 15_000
            }
        }
        val total = stream.totalLength
        stream.use { input ->
            onProgress(if (total > 0) 0f else -1f)

            val header = ByteArray(16)
            val headerRead = readFully(input, header)
            var done = headerRead.toLong()

            val isMp4 = headerRead >= 8 &&
                header[4] == 'f'.code.toByte() && header[5] == 't'.code.toByte() &&
                header[6] == 'y'.code.toByte() && header[7] == 'p'.code.toByte()
            val outFile = File(targetDir, if (isMp4) "$stem.m4a" else "$stem.mp3")
            val tag = if (isMp4) ByteArray(0) else id3v2Tag(title, artist, artwork)

            try {
            outFile.outputStream().use { output ->
                if (tag.isNotEmpty()) output.write(tag)

                val incomingId3 = !isMp4 && headerRead >= 10 &&
                    header[0] == 'I'.code.toByte() &&
                    header[1] == 'D'.code.toByte() &&
                    header[2] == '3'.code.toByte()
                if (incomingId3) {
                    val existingSize = decodeSynchsafe(header, 6)
                    val hasFooter = (header[5].toInt() and 0x10) != 0
                    val tagTotal = 10L + existingSize + if (hasFooter) 10L else 0L
                    if (tagTotal < headerRead) {
                        output.write(header, tagTotal.toInt(), headerRead - tagTotal.toInt())
                    } else {
                        var toSkip = tagTotal - headerRead
                        val skipBuf = ByteArray(64 * 1024)
                        while (toSkip > 0) {
                            val n = input.read(skipBuf, 0, minOf(skipBuf.size.toLong(), toSkip).toInt())
                            if (n < 0) break
                            toSkip -= n
                            done += n
                        }
                    }
                } else if (headerRead > 0) {
                    output.write(header, 0, headerRead)
                }

                val buffer = ByteArray(64 * 1024)
                var read = input.read(buffer)
                while (read >= 0) {
                    output.write(buffer, 0, read)
                    done += read
                    if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                    read = input.read(buffer)
                }

                if (total > 0 && done < total) {
                    throw java.io.IOException("Incomplete download ($done/$total bytes)")
                }
            }
            } catch (e: Exception) {
                outFile.delete()
                throw e
            }
            if (isMp4) {
                remuxToPlainMp4(outFile)
                tagM4a(outFile, title, artist, artwork)
            }
            onProgress(1f)
            return outFile
        }
    }

    private val ffmpegPath: String? by lazy {
        sequenceOf("C:\\ffmpeg\\bin\\ffmpeg.exe", "ffmpeg").firstOrNull { name ->
            runCatching {
                ProcessBuilder(name, "-version").redirectErrorStream(true).start()
                    .apply { inputStream.readBytes(); waitFor() }
                    .exitValue() == 0
            }.getOrDefault(false)
        }
    }

    private fun remuxToPlainMp4(file: File) {
        val ffmpeg = ffmpegPath ?: return

        val tmp = File(file.parentFile, file.nameWithoutExtension + ".remux.m4a")
        runCatching {
            val proc = ProcessBuilder(
                ffmpeg, "-y", "-loglevel", "error", "-i", file.absolutePath,
                "-c", "copy", "-movflags", "+faststart", tmp.absolutePath
            ).redirectErrorStream(true).start()
            proc.inputStream.readBytes()
            if (proc.waitFor() == 0 && tmp.length() > 0) {
                if (file.delete()) tmp.renameTo(file) else tmp.delete()
            } else {
                tmp.delete()
            }
        }.onFailure { tmp.delete() }
    }

    private fun tagM4a(file: File, title: String, artist: String, artwork: ByteArray?) {
        runCatching {
            val audio = org.jaudiotagger.audio.AudioFileIO.read(file)
            val tag = audio.tagOrCreateAndSetDefault
            if (title.isNotBlank()) tag.setField(org.jaudiotagger.tag.FieldKey.TITLE, title)
            if (artist.isNotBlank()) tag.setField(org.jaudiotagger.tag.FieldKey.ARTIST, artist)
            if (artwork != null) {
                val art = org.jaudiotagger.tag.images.ArtworkFactory.getNew()
                art.binaryData = artwork
                art.mimeType = if (
                    artwork.size >= 2 && (artwork[0].toInt() and 0xFF) == 0x89
                ) "image/png" else "image/jpeg"
                runCatching { tag.deleteArtworkField() }
                tag.setField(art)
            }
            audio.commit()
        }.onFailure {
            println("Tagging failed for ${file.name}: ${it.message ?: it}")
        }
    }

    private fun readFully(input: java.io.InputStream, dst: ByteArray): Int {
        var off = 0
        while (off < dst.size) {
            val n = input.read(dst, off, dst.size - off)
            if (n < 0) break
            off += n
        }
        return off
    }

    private fun decodeSynchsafe(b: ByteArray, offset: Int): Int =
        ((b[offset].toInt() and 0x7F) shl 21) or
            ((b[offset + 1].toInt() and 0x7F) shl 14) or
            ((b[offset + 2].toInt() and 0x7F) shl 7) or
            (b[offset + 3].toInt() and 0x7F)

    private fun sanitize(title: String): String =
        title.replace(Regex("[^A-Za-z0-9-_ ]"), "_").trim().ifEmpty { "track" }

    private fun fetchArtwork(artworkUrl: String): ByteArray? {
        if (artworkUrl.isBlank() || !artworkUrl.startsWith("http")) return null
        return try {
            val connection = openHttp(artworkUrl).apply {
                connectTimeout = 15_000
                readTimeout = 15_000
            }
            connection.inputStream.use { it.readBytes() }.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            null
        }
    }

    private fun id3v2Tag(title: String, artist: String, artwork: ByteArray?): ByteArray {
        val frames = ArrayList<Byte>()
        appendTextFrame(frames, "TIT2", title)
        appendTextFrame(frames, "TPE1", artist)
        if (artwork != null) appendApicFrame(frames, artwork)
        if (frames.isEmpty()) return ByteArray(0)

        val header = ArrayList<Byte>()
        header.addAll("ID3".toByteArray(Charsets.US_ASCII).toList())
        header.add(0x04); header.add(0x00)
        header.add(0x00)
        header.addAll(synchsafe(frames.size).toList())
        return header.toByteArray() + frames.toByteArray()
    }

    private fun appendTextFrame(out: ArrayList<Byte>, id: String, text: String) {
        if (text.isBlank()) return
        val body = text.toByteArray(Charsets.UTF_8)
        val payload = ByteArray(1 + body.size + 1)
        payload[0] = 0x03
        System.arraycopy(body, 0, payload, 1, body.size)
        payload[payload.size - 1] = 0x00

        out.addAll(id.toByteArray(Charsets.US_ASCII).toList())
        out.addAll(synchsafe(payload.size).toList())
        out.add(0x00); out.add(0x00)
        out.addAll(payload.toList())
    }

    private fun appendApicFrame(out: ArrayList<Byte>, image: ByteArray) {
        val mime = when {
            image.size >= 8 && image[0].toInt() and 0xFF == 0x89 &&
                image[1].toInt() and 0xFF == 0x50 -> "image/png"
            else -> "image/jpeg"
        }
        val payload = ArrayList<Byte>()
        payload.add(0x03)
        payload.addAll(mime.toByteArray(Charsets.US_ASCII).toList())
        payload.add(0x00)
        payload.add(0x03)
        payload.add(0x00)
        payload.addAll(image.toList())

        out.addAll("APIC".toByteArray(Charsets.US_ASCII).toList())
        out.addAll(synchsafe(payload.size).toList())
        out.add(0x00); out.add(0x00)
        out.addAll(payload)
    }

    private fun synchsafe(v: Int): ByteArray = byteArrayOf(
        ((v ushr 21) and 0x7F).toByte(),
        ((v ushr 14) and 0x7F).toByte(),
        ((v ushr 7) and 0x7F).toByte(),
        (v and 0x7F).toByte()
    )
}
