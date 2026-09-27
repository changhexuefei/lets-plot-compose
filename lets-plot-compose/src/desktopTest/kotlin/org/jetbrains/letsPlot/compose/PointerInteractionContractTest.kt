package org.jetbrains.letsPlot.compose

import org.jetbrains.letsPlot.commons.event.MouseEventSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PointerInteractionContractTest {

    @Test
    fun pressedMoveMapsToDraggedAndUnpressedMoveMapsToMoved() {
        assertEquals(
            MouseEventSpec.MOUSE_DRAGGED,
            PointerInteractionContract.moveEventSpec(pressed = true)
        )
        assertEquals(
            MouseEventSpec.MOUSE_MOVED,
            PointerInteractionContract.moveEventSpec(pressed = false)
        )
    }

    @Test
    fun wheelUsesDominantAbsoluteAxisAndPreservesSign() {
        assertEquals(
            7.0,
            PointerInteractionContract.dominantScrollAmount(x = 7.0, y = 2.0)
        )
        assertEquals(
            -9.0,
            PointerInteractionContract.dominantScrollAmount(x = 2.0, y = -9.0)
        )
        assertEquals(
            -4.0,
            PointerInteractionContract.dominantScrollAmount(x = 4.0, y = -4.0)
        )
    }

    @Test
    fun keyboardModifiersPreserveAllComposeModifierFlags() {
        val modifiers = PointerInteractionContract.keyModifiers(
            isCtrl = true,
            isAlt = false,
            isShift = true,
            isMeta = true
        )

        assertTrue(modifiers.isCtrl)
        assertFalse(modifiers.isAlt)
        assertTrue(modifiers.isShift)
        assertTrue(modifiers.isMeta)
    }
}
