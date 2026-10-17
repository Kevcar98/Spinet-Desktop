package dev.spinet.platform

import com.sun.jna.CallbackReference
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import java.awt.Window

object WindowsSnap {
    val isSupported: Boolean =
        System.getProperty("os.name").orEmpty().lowercase().contains("win")

    private const val GWL_STYLE = -16

    private const val WS_THICKFRAME = 0x00040000

    private const val WS_MAXIMIZEBOX = 0x00010000
    private const val WS_MINIMIZEBOX = 0x00020000

    private const val WS_CAPTION = 0x00C00000

    private const val SWP_NOMOVE = 0x0002
    private const val SWP_NOSIZE = 0x0001
    private const val SWP_NOZORDER = 0x0004
    private const val SWP_NOACTIVATE = 0x0010
    private const val SWP_FRAMECHANGED = 0x0020

    private const val GWLP_WNDPROC = -4
    private const val WM_LBUTTONDOWN = 0x0201
    private const val WM_SYSCOMMAND = 0x0112

    private const val SC_MOVE_CAPTION = 0xF012

    @Volatile
    private var titleBar: IntArray? = null

    private const val TOP_RESIZE_PX = 8

    @Volatile
    var osDragWorks: Boolean = false
        private set

    fun setTitleBarBounds(left: Int, top: Int, right: Int, bottom: Int) {
        titleBar = intArrayOf(left, top, right, bottom)
    }

    private const val GW_CHILD = 5

    private var frame: WinDef.HWND? = null
    private var childPreviousProc: Pointer? = null

    private val childProc = object : WndProc {
        override fun callback(
            hWnd: WinDef.HWND,
            uMsg: Int,
            wParam: WinDef.WPARAM,
            lParam: WinDef.LPARAM
        ): WinDef.LRESULT {
            if (uMsg == WM_LBUTTONDOWN) {
                val bounds = titleBar
                val top = frame
                val ex = user32Ex
                if (bounds != null && top != null && ex != null) {
                    val x = (lParam.toLong() and 0xFFFF).toShort().toInt()
                    val y = ((lParam.toLong() shr 16) and 0xFFFF).toShort().toInt()
                    val inCaption = x >= bounds[0] && x < bounds[2] &&
                        y >= bounds[1] + TOP_RESIZE_PX && y < bounds[3]
                    if (inCaption) {
                        ex.ReleaseCapture()
                        val started = System.currentTimeMillis()

                        ex.DefWindowProc(
                            top,
                            WM_SYSCOMMAND,
                            WinDef.WPARAM(SC_MOVE_CAPTION.toLong()),
                            WinDef.LPARAM(0)
                        )

                        if (System.currentTimeMillis() - started > 50) osDragWorks = true
                        return WinDef.LRESULT(0)
                    }
                }
            }
            val prev = childPreviousProc ?: return WinDef.LRESULT(0)
            return User32.INSTANCE.CallWindowProc(prev, hWnd, uMsg, wParam, lParam)
        }
    }
    private const val WM_NCCALCSIZE = 0x0083
    private const val WM_NCACTIVATE = 0x0086

    private const val HTCAPTION = 2

    private const val DWMWA_WINDOW_CORNER_PREFERENCE = 33
    private const val DWMWCP_ROUND = 2

    private interface Dwmapi : com.sun.jna.win32.StdCallLibrary {
        fun DwmSetWindowAttribute(
            hWnd: WinDef.HWND,
            attribute: Int,
            value: com.sun.jna.ptr.IntByReference,
            size: Int
        ): Int
    }

    private val dwmapi: Dwmapi? by lazy {
        runCatching {
            Native.load("dwmapi", Dwmapi::class.java, com.sun.jna.win32.W32APIOptions.DEFAULT_OPTIONS)
        }.getOrNull()
    }

    private interface User32Ex : com.sun.jna.win32.StdCallLibrary {
        fun ScreenToClient(hWnd: WinDef.HWND, point: WinDef.POINT): Boolean
        fun ReleaseCapture(): Boolean
        fun GetWindow(hWnd: WinDef.HWND, cmd: Int): WinDef.HWND?
        fun DefWindowProc(
            hWnd: WinDef.HWND,
            msg: Int,
            wParam: WinDef.WPARAM,
            lParam: WinDef.LPARAM
        ): WinDef.LRESULT
    }

    private val user32Ex: User32Ex? by lazy {
        runCatching {
            Native.load("user32", User32Ex::class.java, com.sun.jna.win32.W32APIOptions.DEFAULT_OPTIONS)
        }.getOrNull()
    }

    private interface WndProc : com.sun.jna.Callback {
        fun callback(
            hWnd: WinDef.HWND,
            uMsg: Int,
            wParam: WinDef.WPARAM,
            lParam: WinDef.LPARAM
        ): WinDef.LRESULT
    }

    private var previousProc: Pointer? = null

    private val windowProc = object : WndProc {
        override fun callback(
            hWnd: WinDef.HWND,
            uMsg: Int,
            wParam: WinDef.WPARAM,
            lParam: WinDef.LPARAM
        ): WinDef.LRESULT {
            if (uMsg == WM_NCCALCSIZE && wParam.toInt() != 0) {
                return WinDef.LRESULT(0)
            }

            if (uMsg == WM_NCACTIVATE) {
                val ex = user32Ex
                if (ex != null) {
                    return ex.DefWindowProc(hWnd, uMsg, wParam, WinDef.LPARAM(-1))
                }
            }
            val prev = previousProc ?: return WinDef.LRESULT(0)
            return User32.INSTANCE.CallWindowProc(prev, hWnd, uMsg, wParam, lParam)
        }
    }

    fun enable(window: Window) {
        if (!isSupported) return
        runCatching {
            val handle = Native.getWindowPointer(window) ?: return
            val hwnd = WinDef.HWND(handle)
            val user32 = User32.INSTANCE
            val current = user32.GetWindowLong(hwnd, GWL_STYLE)
            val wanted = current or WS_THICKFRAME or WS_MAXIMIZEBOX or
                WS_MINIMIZEBOX or WS_CAPTION
            if (wanted == current) return
            user32.SetWindowLong(hwnd, GWL_STYLE, wanted)

            if (previousProc == null) {
                frame = hwnd
                previousProc = user32.SetWindowLongPtr(
                    hwnd, GWLP_WNDPROC, CallbackReference.getFunctionPointer(windowProc)
                )

                val child = user32Ex?.GetWindow(hwnd, GW_CHILD)
                if (child != null) {
                    childPreviousProc = user32.SetWindowLongPtr(
                        child, GWLP_WNDPROC, CallbackReference.getFunctionPointer(childProc)
                    )
                }
            }
            user32.SetWindowPos(
                hwnd, null, 0, 0, 0, 0,
                SWP_NOMOVE or SWP_NOSIZE or SWP_NOZORDER or SWP_NOACTIVATE or SWP_FRAMECHANGED
            )
            roundCorners(window)
        }
    }

    fun roundCorners(window: Window) {
        if (!isSupported) return
        runCatching {
            val handle = Native.getWindowPointer(window) ?: return@runCatching
            dwmapi?.DwmSetWindowAttribute(
                WinDef.HWND(handle),
                DWMWA_WINDOW_CORNER_PREFERENCE,
                com.sun.jna.ptr.IntByReference(DWMWCP_ROUND),
                4
            )
        }
    }
}
