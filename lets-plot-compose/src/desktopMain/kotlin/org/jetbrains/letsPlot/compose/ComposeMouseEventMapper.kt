package org.jetbrains.letsPlot.compose

import androidx.compose.ui.input.pointer.*
import org.jetbrains.letsPlot.commons.event.*
import org.jetbrains.letsPlot.commons.event.MouseEventSpec.*
import org.jetbrains.letsPlot.commons.geometry.Vector
import org.jetbrains.letsPlot.commons.intern.observable.event.EventHandler
import org.jetbrains.letsPlot.commons.registration.Registration
import kotlin.math.roundToInt

class ComposeMouseEventMapper : MouseEventSource, PointerInputEventHandler {
    private val mouseEventPeer = MouseEventPeer()
    private var clickCount: Int = 0
    private var lastClickTime: Long = 0
    private var offsetX: Float = 0f
    private var offsetY: Float = 0f
    private var pointerTraceCount: Int = 0
    private val pointerTraceEnabled: Boolean =
        System.getenv("LETS_PLOT_POINTER_TRACE").equals("true", ignoreCase = true)

    fun setOffset(offsetX: Float, offsetY: Float) {
        this.offsetX = offsetX
        this.offsetY = offsetY
    }

    suspend fun handlePointerInput(scope: PointerInputScope) {
        with(scope) {
            invoke()
        }
    }

    override fun addEventHandler(eventSpec: MouseEventSpec, eventHandler: EventHandler<MouseEvent>): Registration {
        return mouseEventPeer.addEventHandler(eventSpec, eventHandler)
    }

    override suspend fun PointerInputScope.invoke() {
        awaitPointerEventScope {
            while (true) {
                handlePointerEvent(awaitPointerEvent(PointerEventPass.Initial), density)
            }
        }
    }


    internal fun handleDesktopMouseBoundary(
        localX: Float,
        localY: Float,
        density: Float,
        entered: Boolean
    ) {
        val vector = Vector(
            ((localX / density) - offsetX).roundToInt(),
            ((localY / density) - offsetY).roundToInt()
        )
        val mouseEvent = MouseEvent(
            vector.x,
            vector.y,
            Button.NONE,
            KeyModifiers.emptyModifiers()
        )

        if (pointerTraceEnabled && pointerTraceCount < 40) {
            println(
                "LETS_PLOT_POINTER_TRACE type=${if (entered) "Enter" else "Exit"} source=awt " +
                    "position=$localX,$localY density=$density " +
                    "adjusted=${vector.x},${vector.y} pressed=false"
            )
            pointerTraceCount++
        }

        mouseEventPeer.dispatch(
            if (entered) MOUSE_ENTERED else MOUSE_LEFT,
            mouseEvent
        )
    }

    internal fun handleDesktopMouseMove(
        localX: Float,
        localY: Float,
        density: Float,
        pressed: Boolean,
        isCtrl: Boolean,
        isAlt: Boolean,
        isShift: Boolean,
        isMeta: Boolean
    ) {
        val vector = Vector(
            ((localX / density) - offsetX).roundToInt(),
            ((localY / density) - offsetY).roundToInt()
        )
        val modifiers = KeyModifiers(
            isCtrl = isCtrl,
            isAlt = isAlt,
            isShift = isShift,
            isMeta = isMeta
        )

        if (pointerTraceEnabled && pointerTraceCount < 40) {
            println(
                "LETS_PLOT_POINTER_TRACE type=Move source=awt " +
                    "position=$localX,$localY density=$density " +
                    "adjusted=${vector.x},${vector.y} pressed=$pressed"
            )
            pointerTraceCount++
        }

        dispatchMove(vector, pressed, modifiers)
    }

    internal fun handlePointerEvent(event: PointerEvent, density: Float) {
        val change = event.changes.firstOrNull() ?: return
        val position = change.position

        // Convert logical pixel coordinates to physical pixel coordinates for SVG interaction.
        val adjustedX = ((position.x / density) - offsetX).roundToInt()
        val adjustedY = ((position.y / density) - offsetY).roundToInt()
        val vector = Vector(adjustedX, adjustedY)

        if (pointerTraceEnabled && pointerTraceCount < 40) {
            println(
                "LETS_PLOT_POINTER_TRACE type=${event.type} " +
                    "position=${position.x},${position.y} density=$density " +
                    "adjusted=${vector.x},${vector.y} pressed=${change.pressed}"
            )
            pointerTraceCount++
        }

        val modifiers = extractModifiers(event)
        val mouseEvent = if (change.pressed) {
            MouseEvent(vector.x, vector.y, Button.LEFT, modifiers)
        } else {
            MouseEvent(vector.x, vector.y, Button.NONE, modifiers)
        }

        when (event.type) {
            PointerEventType.Press -> {
                val currentTime = System.currentTimeMillis()
                clickCount = if (currentTime - lastClickTime < 300) {
                    clickCount + 1
                } else {
                    1
                }
                lastClickTime = currentTime
                mouseEventPeer.dispatch(MOUSE_PRESSED, mouseEvent)
            }

            PointerEventType.Release -> {
                if (clickCount > 0) {
                    dispatchClick(event, clickCount, density.toDouble())
                    if (clickCount > 1) {
                        clickCount = 0
                    }
                }
                mouseEventPeer.dispatch(MOUSE_RELEASED, mouseEvent)
            }

            PointerEventType.Move -> dispatchMove(vector, change.pressed, modifiers)

            PointerEventType.Enter -> mouseEventPeer.dispatch(MOUSE_ENTERED, mouseEvent)
            PointerEventType.Exit -> mouseEventPeer.dispatch(MOUSE_LEFT, mouseEvent)

            PointerEventType.Scroll -> {
                val scrollDelta = change.scrollDelta
                val scrollAmount = if (kotlin.math.abs(scrollDelta.x) > kotlin.math.abs(scrollDelta.y)) {
                    scrollDelta.x.toDouble()
                } else {
                    scrollDelta.y.toDouble()
                }

                val wheelMouseEvent = MouseWheelEvent(
                    x = vector.x,
                    y = vector.y,
                    button = Button.NONE,
                    modifiers = modifiers,
                    scrollAmount = scrollAmount
                )
                mouseEventPeer.dispatch(MOUSE_WHEEL_ROTATED, wheelMouseEvent)
            }
        }
    }


    private fun dispatchMove(
        vector: Vector,
        pressed: Boolean,
        modifiers: KeyModifiers
    ) {
        val mouseEvent = MouseEvent(
            vector.x,
            vector.y,
            if (pressed) Button.LEFT else Button.NONE,
            modifiers
        )
        if (pressed) {
            mouseEventPeer.dispatch(MOUSE_DRAGGED, mouseEvent)
        } else {
            mouseEventPeer.dispatch(MOUSE_MOVED, mouseEvent)
        }
    }

    private fun dispatchClick(event: PointerEvent, clickCount: Int, density: Double) {
        val position = event.changes.first().position
        // Convert logical pixel coordinates to physical pixel coordinates for SVG interaction
        val adjustedX = ((position.x / density) - offsetX).roundToInt()
        val adjustedY = ((position.y / density) - offsetY).roundToInt()
        val vector = Vector(adjustedX, adjustedY)
        val modifiers = extractModifiers(event)
        val mouseEvent = MouseEvent(vector.x, vector.y, Button.LEFT, modifiers)

        when (clickCount) {
            1 -> mouseEventPeer.dispatch(MOUSE_CLICKED, mouseEvent)
            2 -> mouseEventPeer.dispatch(MOUSE_DOUBLE_CLICKED, mouseEvent)
            else -> return
        }
    }

    private fun extractModifiers(event: PointerEvent): KeyModifiers {
        return KeyModifiers(
            isCtrl = event.keyboardModifiers.isCtrlPressed,
            isAlt = event.keyboardModifiers.isAltPressed,
            isShift = event.keyboardModifiers.isShiftPressed,
            isMeta = event.keyboardModifiers.isMetaPressed
        )
    }

}