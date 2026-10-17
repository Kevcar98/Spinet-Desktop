package dev.spinet

import dev.spinet.settings.appDataDir
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.PrintStream
import java.time.LocalDateTime

internal fun keepLog() {
    runCatching {
        val sink = FileOutputStream(File(appDataDir(), "spinet.log"), false)
        System.setOut(PrintStream(Tee(System.out, sink), true, "UTF-8"))
        System.setErr(PrintStream(Tee(System.err, sink), true, "UTF-8"))
        println("[Spinet] $APP_VERSION started ${LocalDateTime.now()} on ${System.getProperty("os.name")}")
    }
}

private class Tee(private val screen: OutputStream, private val file: OutputStream) : OutputStream() {
    private val line = java.io.ByteArrayOutputStream()

    override fun write(b: Int) {
        screen.write(b)
        synchronized(line) {
            line.write(b)
            if (b == 10) writeLine()
        }
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        screen.write(b, off, len)
        synchronized(line) {
            for (i in off until off + len) {
                line.write(b[i].toInt())
                if (b[i] == 10.toByte()) writeLine()
            }
        }
    }

    override fun flush() {
        screen.flush()
        runCatching { file.flush() }
    }

    private fun writeLine() {
        runCatching { file.write(dev.spinet.util.LogRedactor.redact(line.toString(Charsets.UTF_8.name())).toByteArray(Charsets.UTF_8)) }
        line.reset()
    }
}
