package dev.spinet.platform

import java.awt.AWTEvent
import java.awt.GraphicsEnvironment
import java.awt.Point
import java.awt.Rectangle
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.MouseEvent

object WindowEdgeSnap {
    private const val EDGE_PX = 12

    private const val MOVE_THRESHOLD_PX = 8

    private var strip: IntArray? = null
    private var pressedInStrip = false
    private var pressOrigin: Point? = null
    private var installed = false

    fun setDragStrip(left: Int, top: Int, right: Int, bottom: Int) {
        strip = intArrayOf(left, top, right, bottom)
    }

    fun install(window: Window) {
        if (installed) return
        installed = true
        runCatching {
            Toolkit.getDefaultToolkit().addAWTEventListener({ event ->
                val mouse = event as? MouseEvent ?: return@addAWTEventListener
                runCatching { onMouse(window, mouse) }
            }, AWTEvent.MOUSE_EVENT_MASK)
        }.onFailure { installed = false }
    }

    private fun onMouse(window: Window, event: MouseEvent) {
        when (event.id) {
            MouseEvent.MOUSE_PRESSED -> {
                pressedInStrip = false
                pressOrigin = null
                if (!window.isShowing) return
                val bounds = strip ?: return
                val origin = window.locationOnScreen
                val screen = event.locationOnScreen
                val x = screen.x - origin.x
                val y = screen.y - origin.y
                if (x >= bounds[0] && x < bounds[2] && y >= bounds[1] && y < bounds[3]) {
                    pressedInStrip = true
                    pressOrigin = screen
                }
            }

            MouseEvent.MOUSE_RELEASED -> {
                val origin = pressOrigin
                val wasDrag = pressedInStrip && origin != null
                pressedInStrip = false
                pressOrigin = null
                if (!wasDrag || !window.isShowing) return
                val released = event.locationOnScreen
                val travelled = maxOf(
                    kotlin.math.abs(released.x - origin!!.x),
                    kotlin.math.abs(released.y - origin.y)
                )
                if (travelled < MOVE_THRESHOLD_PX) return
                snapIfAtEdge(window, released)
            }
        }
    }

    private fun snapIfAtEdge(window: Window, pointer: Point) {
        val usable = usableBoundsAt(pointer) ?: return
        val target = when {
            pointer.y <= usable.y + EDGE_PX ->
                Rectangle(usable.x, usable.y, usable.width, usable.height)

            pointer.x <= usable.x + EDGE_PX ->
                Rectangle(usable.x, usable.y, usable.width / 2, usable.height)

            pointer.x >= usable.x + usable.width - 1 - EDGE_PX ->
                Rectangle(
                    usable.x + usable.width - usable.width / 2,
                    usable.y,
                    usable.width / 2,
                    usable.height
                )

            else -> return
        }
        if (window.bounds != target) window.bounds = target
    }

    private fun usableBoundsAt(pointer: Point): Rectangle? {
        val device = GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices
            .firstOrNull { it.defaultConfiguration.bounds.contains(pointer) }
            ?: return null
        val configuration = device.defaultConfiguration
        val bounds = Rectangle(configuration.bounds)
        val insets = runCatching {
            Toolkit.getDefaultToolkit().getScreenInsets(configuration)
        }.getOrNull()
        if (insets != null) {
            bounds.x += insets.left
            bounds.y += insets.top
            bounds.width -= insets.left + insets.right
            bounds.height -= insets.top + insets.bottom
        }
        return bounds
    }
}
