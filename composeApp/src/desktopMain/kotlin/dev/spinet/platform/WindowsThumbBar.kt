package dev.spinet.platform

import com.sun.jna.CallbackReference
import com.sun.jna.Function
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.COM.COMUtils
import com.sun.jna.platform.win32.GDI32
import com.sun.jna.platform.win32.Guid
import com.sun.jna.platform.win32.Ole32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinGDI
import com.sun.jna.platform.win32.WinReg
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.Window
import java.awt.geom.Line2D
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import javax.swing.SwingUtilities

object WindowsThumbBar {
    val isSupported: Boolean =
        System.getProperty("os.name").orEmpty().lowercase().contains("win")

    private val CLSID_TaskbarList = Guid.GUID.fromString("{56FDF344-FD6D-11D0-958A-006097C9A090}")
    private val IID_ITaskbarList3 = Guid.GUID.fromString("{EA1AFB91-9E28-4B86-90E9-9E9F8A5EEFAF}")

    private const val CLSCTX_INPROC_SERVER = 1
    private const val COINIT_APARTMENTTHREADED = 2

    private const val VT_RELEASE = 2
    private const val VT_HR_INIT = 3
    private const val VT_THUMBBAR_ADD_BUTTONS = 15
    private const val VT_THUMBBAR_UPDATE_BUTTONS = 16

    private const val WM_COMMAND = 0x0111
    private const val THBN_CLICKED = 0x1800
    private const val GWLP_WNDPROC = -4
    private const val SM_CXSMICON = 49

    private const val THB_ICON = 0x2
    private const val THB_TOOLTIP = 0x4
    private const val THB_FLAGS = 0x8
    private const val THBF_ENABLED = 0x0
    private const val THBF_DISABLED = 0x1

    private const val ID_PREVIOUS = 1
    private const val ID_PLAY_PAUSE = 2
    private const val ID_NEXT = 3

    private const val BI_RGB = 0
    private const val DIB_RGB_COLORS = 0

    @Structure.FieldOrder("dwMask", "iId", "iBitmap", "hIcon", "szTip", "dwFlags")
    class ThumbButton : Structure() {
        @JvmField var dwMask: Int = 0
        @JvmField var iId: Int = 0
        @JvmField var iBitmap: Int = 0
        @JvmField var hIcon: Pointer? = null

        @JvmField var szTip: CharArray = CharArray(260)
        @JvmField var dwFlags: Int = 0
    }

    private interface WndProc : com.sun.jna.Callback {
        fun callback(
            hWnd: WinDef.HWND,
            uMsg: Int,
            wParam: WinDef.WPARAM,
            lParam: WinDef.LPARAM
        ): WinDef.LRESULT
    }

    private interface User32Ex : StdCallLibrary {
        fun CreateIconIndirect(iconInfo: WinGDI.ICONINFO): WinDef.HICON?
    }

    private interface Gdi32Ex : StdCallLibrary {
        fun CreateBitmap(w: Int, h: Int, planes: Int, bitCount: Int, bits: Pointer?): WinDef.HBITMAP?
    }

    private val user32Ex: User32Ex? by lazy {
        runCatching {
            Native.load("user32", User32Ex::class.java, W32APIOptions.DEFAULT_OPTIONS)
        }.getOrNull()
    }

    private val gdi32Ex: Gdi32Ex? by lazy {
        runCatching {
            Native.load("gdi32", Gdi32Ex::class.java, W32APIOptions.DEFAULT_OPTIONS)
        }.getOrNull()
    }

    private var taskbarList: Pointer? = null
    private var hwnd: WinDef.HWND? = null
    private var buttons: Array<ThumbButton>? = null
    private val icons = mutableListOf<WinDef.HICON>()
    private var playIcon: Pointer? = null
    private var pauseIcon: Pointer? = null
    private var showingPause = false
    private var taskbarCreatedMessage = 0
    private var previousProc: Pointer? = null
    private var installed = false

    private var onPrevious: () -> Unit = {}
    private var onPlayPause: () -> Unit = {}
    private var onNext: () -> Unit = {}

    private val windowProc = object : WndProc {
        override fun callback(
            hWnd: WinDef.HWND,
            uMsg: Int,
            wParam: WinDef.WPARAM,
            lParam: WinDef.LPARAM
        ): WinDef.LRESULT {
            if (taskbarCreatedMessage != 0 && uMsg == taskbarCreatedMessage) {
                runCatching { addButtons() }
            } else if (uMsg == WM_COMMAND) {
                val w = wParam.toInt()
                if ((w ushr 16) and 0xFFFF == THBN_CLICKED) {
                    val action = when (w and 0xFFFF) {
                        ID_PREVIOUS -> onPrevious
                        ID_PLAY_PAUSE -> onPlayPause
                        ID_NEXT -> onNext
                        else -> null
                    }
                    if (action != null) {
                        SwingUtilities.invokeLater { runCatching { action() } }
                        return WinDef.LRESULT(0)
                    }
                }
            }
            val prev = previousProc
            return if (prev == null) WinDef.LRESULT(0)
            else User32.INSTANCE.CallWindowProc(prev, hWnd, uMsg, wParam, lParam)
        }
    }

    fun install(
        window: Window,
        onPrevious: () -> Unit,
        onPlayPause: () -> Unit,
        onNext: () -> Unit
    ) {
        if (!isSupported || installed) return
        runCatching {
            val handle = Native.getWindowPointer(window) ?: return
            hwnd = WinDef.HWND(handle)
            this.onPrevious = onPrevious
            this.onPlayPause = onPlayPause
            this.onNext = onNext

            taskbarCreatedMessage = User32.INSTANCE.RegisterWindowMessage("TaskbarButtonCreated")
            previousProc = User32.INSTANCE.SetWindowLongPtr(
                hwnd,
                GWLP_WNDPROC,
                CallbackReference.getFunctionPointer(windowProc)
            )
            installed = true

            addButtons()
        }.onFailure { warn("install failed", it) }
    }

    fun setPlaying(playing: Boolean) {
        if (!isSupported || !installed) return
        if (playing == showingPause) return
        showingPause = playing
        runCatching {
            val array = buttons ?: return
            array[1].hIcon = if (playing) pauseIcon else playIcon
            setTip(array[1], if (playing) "Pause" else "Play")
            updateButtons()
        }
    }

    fun setSkipEnabled(previous: Boolean, next: Boolean) {
        if (!isSupported || !installed) return
        runCatching {
            val array = buttons ?: return
            val before = array[0].dwFlags to array[2].dwFlags
            array[0].dwFlags = if (previous) THBF_ENABLED else THBF_DISABLED
            array[2].dwFlags = if (next) THBF_ENABLED else THBF_DISABLED
            if (before == (array[0].dwFlags to array[2].dwFlags)) return
            updateButtons()
        }
    }

    fun dispose() {
        if (!isSupported || !installed) return
        installed = false
        runCatching {
            previousProc?.let { User32.INSTANCE.SetWindowLongPtr(hwnd, GWLP_WNDPROC, it) }
            previousProc = null
            taskbarList?.let { list -> call(list, VT_RELEASE) }
            taskbarList = null
            icons.forEach { runCatching { User32.INSTANCE.DestroyIcon(it) } }
            icons.clear()
            buttons = null
        }
    }

    private fun warn(what: String, error: Throwable? = null) {
        System.err.println("thumb bar: $what" + (error?.let { " (${it::class.simpleName}: ${it.message})" } ?: ""))
    }

    private fun call(thisPtr: Pointer, vtableIndex: Int, vararg args: Any?): Int {
        val vtable = thisPtr.getPointer(0)
        val fn = Function.getFunction(vtable.getPointer(vtableIndex.toLong() * Native.POINTER_SIZE))
        val full = arrayOfNulls<Any?>(args.size + 1)
        full[0] = thisPtr
        System.arraycopy(args, 0, full, 1, args.size)
        return fn.invokeInt(full)
    }

    private fun updateButtons() {
        val list = taskbarList ?: return
        val array = buttons ?: return
        array.forEach {
            it.dwMask = THB_ICON or THB_TOOLTIP or THB_FLAGS
            it.write()
        }
        call(list, VT_THUMBBAR_UPDATE_BUTTONS, hwnd, array.size, array[0].pointer)
    }

    private fun addButtons() {
        val window = hwnd ?: return

        Ole32.INSTANCE.CoInitializeEx(Pointer.NULL, COINIT_APARTMENTTHREADED)

        val ref = PointerByReference()
        val hr = Ole32.INSTANCE.CoCreateInstance(
            CLSID_TaskbarList, Pointer.NULL, CLSCTX_INPROC_SERVER, IID_ITaskbarList3, ref
        )
        if (COMUtils.FAILED(hr)) {
            warn("CoCreateInstance(TaskbarList) failed, hr=0x%08X".format(hr))
            return
        }
        val list = ref.value ?: return
        val init = call(list, VT_HR_INIT)
        if (init != 0) {
            warn("ITaskbarList3::HrInit failed, hr=0x%08X".format(init))
            return
        }
        taskbarList = list

        val size = User32.INSTANCE.GetSystemMetrics(SM_CXSMICON).coerceIn(16, 64)
        val colour = glyphColour()
        playIcon = iconOf(drawGlyph(size, colour, Glyph.PLAY))
        pauseIcon = iconOf(drawGlyph(size, colour, Glyph.PAUSE))
        val previousIcon = iconOf(drawGlyph(size, colour, Glyph.PREVIOUS))
        val nextIcon = iconOf(drawGlyph(size, colour, Glyph.NEXT))

        val array = ThumbButton().toArray(3).map { it as ThumbButton }.toTypedArray()
        array[0].iId = ID_PREVIOUS
        array[0].hIcon = previousIcon
        setTip(array[0], "Previous")
        array[1].iId = ID_PLAY_PAUSE
        array[1].hIcon = if (showingPause) pauseIcon else playIcon
        setTip(array[1], if (showingPause) "Pause" else "Play")
        array[2].iId = ID_NEXT
        array[2].hIcon = nextIcon
        setTip(array[2], "Next")
        array.forEach {
            it.dwMask = THB_ICON or THB_TOOLTIP or THB_FLAGS
            it.dwFlags = THBF_ENABLED
            it.write()
        }
        buttons = array

        val added = call(list, VT_THUMBBAR_ADD_BUTTONS, window, array.size, array[0].pointer)
        if (added != 0) warn("ThumbBarAddButtons failed, hr=0x%08X".format(added))
        else if (playIcon == null) warn("buttons added but the icons did not build")
    }

    private fun setTip(button: ThumbButton, text: String) {
        val tip = CharArray(260)
        text.take(259).forEachIndexed { i, c -> tip[i] = c }
        button.szTip = tip
    }

    private fun glyphColour(): Color = runCatching {
        val light = Advapi32Util.registryGetIntValue(
            WinReg.HKEY_CURRENT_USER,
            "Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
            "SystemUsesLightTheme"
        )
        if (light != 0) Color(0x20, 0x20, 0x20) else Color.WHITE
    }.getOrDefault(Color.WHITE)

    private enum class Glyph { PREVIOUS, PLAY, PAUSE, NEXT }

    private fun drawGlyph(size: Int, colour: Color, glyph: Glyph): BufferedImage {
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics() as Graphics2D
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = colour
        val s = size.toFloat()

        val pad = s * 0.22f
        val left = pad
        val right = s - pad
        val top = pad
        val bottom = s - pad
        when (glyph) {
            Glyph.PLAY -> g.fill(triangle(left, top, right, bottom, pointingRight = true))

            Glyph.PAUSE -> {
                val bar = ((right - left) * 0.32f).coerceAtLeast(1f)
                g.fill(java.awt.geom.Rectangle2D.Float(left, top, bar, bottom - top))
                g.fill(java.awt.geom.Rectangle2D.Float(right - bar, top, bar, bottom - top))
            }

            Glyph.NEXT, Glyph.PREVIOUS -> {
                val forward = glyph == Glyph.NEXT
                val barWidth = (s * 0.11f).coerceAtLeast(1f)
                val tipEdge = if (forward) right - barWidth * 1.6f else left + barWidth * 1.6f
                g.fill(
                    if (forward) triangle(left, top, tipEdge, bottom, pointingRight = true)
                    else triangle(tipEdge, top, right, bottom, pointingRight = false)
                )
                g.stroke = BasicStroke(barWidth)
                val x = if (forward) right - barWidth / 2f else left + barWidth / 2f
                g.draw(Line2D.Float(x, top, x, bottom))
            }
        }
        g.dispose()
        return image
    }

    private fun triangle(l: Float, t: Float, r: Float, b: Float, pointingRight: Boolean): Path2D.Float {
        val path = Path2D.Float()
        if (pointingRight) {
            path.moveTo(l.toDouble(), t.toDouble())
            path.lineTo(r.toDouble(), ((t + b) / 2f).toDouble())
            path.lineTo(l.toDouble(), b.toDouble())
        } else {
            path.moveTo(r.toDouble(), t.toDouble())
            path.lineTo(l.toDouble(), ((t + b) / 2f).toDouble())
            path.lineTo(r.toDouble(), b.toDouble())
        }
        path.closePath()
        return path
    }

    private fun iconOf(image: BufferedImage): Pointer? = runCatching {
        val user = user32Ex ?: return null
        val gdi = gdi32Ex ?: return null
        val w = image.width
        val h = image.height

        val info = WinGDI.BITMAPINFO()
        info.bmiHeader.biSize = info.bmiHeader.size()
        info.bmiHeader.biWidth = w

        info.bmiHeader.biHeight = -h
        info.bmiHeader.biPlanes = 1
        info.bmiHeader.biBitCount = 32
        info.bmiHeader.biCompression = BI_RGB

        val bits = PointerByReference()
        val colour = GDI32.INSTANCE.CreateDIBSection(null, info, DIB_RGB_COLORS, bits, null, 0)
            ?: return null
        val pixels = bits.value
        if (pixels == null) {
            GDI32.INSTANCE.DeleteObject(colour)
            return null
        }

        val argb = IntArray(w * h)
        image.getRGB(0, 0, w, h, argb, 0, w)

        pixels.write(0, argb, 0, argb.size)

        val mask = gdi.CreateBitmap(w, h, 1, 1, null)
        if (mask == null) {
            GDI32.INSTANCE.DeleteObject(colour)
            return null
        }

        val iconInfo = WinGDI.ICONINFO()
        iconInfo.fIcon = true
        iconInfo.hbmColor = colour
        iconInfo.hbmMask = mask
        iconInfo.write()
        val icon = user.CreateIconIndirect(iconInfo)

        GDI32.INSTANCE.DeleteObject(colour)
        GDI32.INSTANCE.DeleteObject(mask)
        if (icon == null) return null
        icons += icon
        icon.pointer
    }.getOrNull()
}
