package dev.spinet.recognition

import com.sun.jna.Function
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.platform.win32.COM.COMUtils
import com.sun.jna.platform.win32.Guid
import com.sun.jna.platform.win32.Ole32

object WasapiLoopback {
    val isSupported: Boolean =
        System.getProperty("os.name").orEmpty().lowercase().contains("win")

    private val CLSID_MMDeviceEnumerator = Guid.GUID.fromString("{BCDE0395-E52F-467C-8E3D-C4579291692E}")
    private val IID_IMMDeviceEnumerator = Guid.GUID.fromString("{A95664D2-9614-4F35-A746-DE8DB63617E6}")
    private val IID_IAudioClient = Guid.GUID.fromString("{1CB9AD4C-DBFA-4C32-B178-C2F568A703B2}")
    private val IID_IAudioCaptureClient = Guid.GUID.fromString("{C8ADBD64-E71E-48A0-A4DE-185C395CD317}")

    private const val CLSCTX_ALL = 23
    private const val AUDCLNT_SHAREMODE_SHARED = 0
    private const val AUDCLNT_STREAMFLAGS_LOOPBACK = 0x00020000
    private const val AUDCLNT_BUFFERFLAGS_SILENT = 0x2
    private const val COINIT_MULTITHREADED = 0

    private const val VT_RELEASE = 2
    private const val VT_ENUM_GET_DEFAULT_ENDPOINT = 4
    private const val VT_DEVICE_ACTIVATE = 3
    private const val VT_CLIENT_INITIALIZE = 3
    private const val VT_CLIENT_GET_MIX_FORMAT = 8
    private const val VT_CLIENT_START = 10
    private const val VT_CLIENT_STOP = 11
    private const val VT_CLIENT_GET_SERVICE = 14
    private const val VT_CAPTURE_GET_BUFFER = 3
    private const val VT_CAPTURE_RELEASE_BUFFER = 4
    private const val VT_CAPTURE_GET_NEXT_PACKET = 5

    private const val TARGET_RATE = 16000

    private class MixFormat(val channels: Int, val sampleRate: Int, val bitsPerSample: Int, val isFloat: Boolean)

    fun capture(seconds: Int): ShortArray? {
        if (!isSupported) return null
        return runCatching { captureInternal(seconds) }.getOrNull()
    }

    private fun captureInternal(seconds: Int): ShortArray? {
        val ole = Ole32.INSTANCE

        val didInit = COMUtils.SUCCEEDED(ole.CoInitializeEx(Pointer.NULL, COINIT_MULTITHREADED))
        try {
            val enumRef = PointerByReference()
            val hrCreate = ole.CoCreateInstance(
                CLSID_MMDeviceEnumerator, Pointer.NULL, CLSCTX_ALL, IID_IMMDeviceEnumerator, enumRef,
            )
            if (COMUtils.FAILED(hrCreate)) return null
            val mmEnum = enumRef.value ?: return null

            try {
                val deviceRef = PointerByReference()
                var hr = call(mmEnum, VT_ENUM_GET_DEFAULT_ENDPOINT, 0, 0, deviceRef)
                if (hr != 0) return null
                val device = deviceRef.value ?: return null

                try {
                    val clientRef = PointerByReference()
                    hr = call(device, VT_DEVICE_ACTIVATE, IID_IAudioClient, CLSCTX_ALL, Pointer.NULL, clientRef)
                    if (hr != 0) return null
                    val client = clientRef.value ?: return null

                    try {
                        val fmtRef = PointerByReference()
                        hr = call(client, VT_CLIENT_GET_MIX_FORMAT, fmtRef)
                        if (hr != 0) return null
                        val fmtPtr = fmtRef.value ?: return null
                        val format = parseFormat(fmtPtr)

                        val bufferDuration = 10_000_000L
                        hr = call(
                            client, VT_CLIENT_INITIALIZE,
                            AUDCLNT_SHAREMODE_SHARED, AUDCLNT_STREAMFLAGS_LOOPBACK,
                            bufferDuration, 0L, fmtPtr, Pointer.NULL,
                        )
                        ole.CoTaskMemFree(fmtPtr)
                        if (hr != 0) return null

                        val captureRef = PointerByReference()
                        hr = call(client, VT_CLIENT_GET_SERVICE, IID_IAudioCaptureClient, captureRef)
                        if (hr != 0) return null
                        val captureClient = captureRef.value ?: return null

                        try {
                            call(client, VT_CLIENT_START)
                            val nativeMono = drainForDuration(captureClient, format, seconds)
                            call(client, VT_CLIENT_STOP)
                            return nativeMono?.let { resampleTo16k(it, format.sampleRate) }
                        } finally {
                            call(captureClient, VT_RELEASE)
                        }
                    } finally {
                        call(client, VT_RELEASE)
                    }
                } finally {
                    call(device, VT_RELEASE)
                }
            } finally {
                call(mmEnum, VT_RELEASE)
            }
        } finally {
            if (didInit) ole.CoUninitialize()
        }
    }

    private fun drainForDuration(captureClient: Pointer, format: MixFormat, seconds: Int): FloatArray? {
        val maxFrames = format.sampleRate.toLong() * seconds
        val mono = ArrayList<Float>(minOf(maxFrames, 5_000_000L).toInt())
        val deadline = System.nanoTime() + seconds.toLong() * 1_000_000_000L

        val nextRef = com.sun.jna.ptr.IntByReference()
        val nFramesRef = com.sun.jna.ptr.IntByReference()
        val flagsRef = com.sun.jna.ptr.IntByReference()
        val dataRef = PointerByReference()

        while (System.nanoTime() < deadline && mono.size < maxFrames) {
            if (call(captureClient, VT_CAPTURE_GET_NEXT_PACKET, nextRef) != 0) break
            if (nextRef.value == 0) {
                Thread.sleep(5)
                continue
            }
            if (call(
                    captureClient, VT_CAPTURE_GET_BUFFER,
                    dataRef, nFramesRef, flagsRef, Pointer.NULL, Pointer.NULL,
                ) != 0
            ) break
            val numFrames = nFramesRef.value
            val silent = (flagsRef.value and AUDCLNT_BUFFERFLAGS_SILENT) != 0
            val dataPtr = dataRef.value

            if (numFrames > 0) {
                val block = FloatArray(numFrames)
                if (!silent && dataPtr != null) fillMono(dataPtr, numFrames, format, block)
                for (v in block) mono.add(v)
            }
            call(captureClient, VT_CAPTURE_RELEASE_BUFFER, numFrames)
        }
        if (mono.size < format.sampleRate) return null
        return FloatArray(mono.size) { mono[it] }
    }

    private fun resampleTo16k(mono: FloatArray, srcRate: Int): ShortArray {
        if (srcRate == TARGET_RATE) {
            return ShortArray(mono.size) { mono[it].coerceIn(-32768f, 32767f).toInt().toShort() }
        }
        val outLen = (mono.size.toLong() * TARGET_RATE / srcRate).toInt()
        val out = ShortArray(outLen)
        val step = srcRate.toDouble() / TARGET_RATE
        for (i in 0 until outLen) {
            val pos = i * step
            val i0 = pos.toInt().coerceAtMost(mono.size - 1)
            val i1 = (i0 + 1).coerceAtMost(mono.size - 1)
            val frac = (pos - i0).toFloat()
            val v = mono[i0] * (1 - frac) + mono[i1] * frac
            out[i] = v.coerceIn(-32768f, 32767f).toInt().toShort()
        }
        return out
    }

    private fun fillMono(dataPtr: Pointer, numFrames: Int, format: MixFormat, dst: FloatArray) {
        val ch = format.channels
        if (format.isFloat && format.bitsPerSample == 32) {
            val floats = dataPtr.getFloatArray(0, numFrames * ch)
            for (f in 0 until numFrames) {
                var sum = 0f
                for (c in 0 until ch) sum += floats[f * ch + c]
                dst[f] = (sum / ch) * 32767f
            }
        } else if (!format.isFloat && format.bitsPerSample == 16) {
            val shorts = dataPtr.getShortArray(0, numFrames * ch)
            for (f in 0 until numFrames) {
                var sum = 0
                for (c in 0 until ch) sum += shorts[f * ch + c].toInt()
                dst[f] = (sum.toFloat() / ch)
            }
        } else if (!format.isFloat && format.bitsPerSample == 32) {
            val ints = dataPtr.getIntArray(0, numFrames * ch)
            for (f in 0 until numFrames) {
                var sum = 0.0
                for (c in 0 until ch) sum += ints[f * ch + c].toDouble() / 65536.0
                dst[f] = (sum / ch).toFloat()
            }
        }
    }

    private fun parseFormat(p: Pointer): MixFormat {
        val formatTag = p.getShort(0).toInt() and 0xFFFF
        val channels = p.getShort(2).toInt() and 0xFFFF
        val sampleRate = p.getInt(4)
        val bits = p.getShort(14).toInt() and 0xFFFF
        var isFloat = formatTag == 3
        if (formatTag == 0xFFFE) {
            val subFormatData1 = p.getInt(24)
            isFloat = subFormatData1 == 3
        }
        return MixFormat(channels, sampleRate, bits, isFloat)
    }

    private fun call(thisPtr: Pointer, vtableIndex: Int, vararg args: Any?): Int {
        val vtable = thisPtr.getPointer(0)
        val fnPtr = vtable.getPointer(vtableIndex.toLong() * Native.POINTER_SIZE)
        val fn = Function.getFunction(fnPtr)
        val full = arrayOfNulls<Any?>(args.size + 1)
        full[0] = thisPtr
        System.arraycopy(args, 0, full, 1, args.size)
        return fn.invokeInt(full)
    }
}
