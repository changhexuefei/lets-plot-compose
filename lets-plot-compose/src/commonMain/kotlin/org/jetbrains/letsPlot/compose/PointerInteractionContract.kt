package org.jetbrains.letsPlot.compose

import kotlin.math.abs
import org.jetbrains.letsPlot.commons.event.KeyModifiers
import org.jetbrains.letsPlot.commons.event.MouseEventSpec

/**
 * Shared pointer-interaction semantics used by all Compose targets.
 *
 * Keeping these rules in common code prevents Desktop, Android, and WasmJS
 * from drifting in drag, wheel-axis, and keyboard-modifier behavior.
 */
internal object PointerInteractionContract {
    fun moveEventSpec(pressed: Boolean): MouseEventSpec =
        if (pressed) MouseEventSpec.MOUSE_DRAGGED else MouseEventSpec.MOUSE_MOVED

    fun dominantScrollAmount(x: Double, y: Double): Double =
        if (abs(x) > abs(y)) x else y

    fun shouldDispatchClick(clickCount: Int, dragged: Boolean): Boolean =
        clickCount > 0 && !dragged

    fun clickCountAfterRelease(clickCount: Int, dragged: Boolean): Int =
        if (dragged || clickCount > 1) 0 else clickCount

    fun keyModifiers(
        isCtrl: Boolean,
        isAlt: Boolean,
        isShift: Boolean,
        isMeta: Boolean
    ): KeyModifiers = KeyModifiers(
        isCtrl = isCtrl,
        isAlt = isAlt,
        isShift = isShift,
        isMeta = isMeta
    )
}
