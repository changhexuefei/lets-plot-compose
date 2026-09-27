package org.jetbrains.letsPlot.compose

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ComputationMessagesDispatchGateTest {

    @Test
    fun dispatchesOnlyOnceForSameSpec() {
        val gate = ComputationMessagesDispatchGate()

        assertTrue(gate.shouldDispatch(101))
        assertFalse(gate.shouldDispatch(101))
        assertFalse(gate.shouldDispatch(101))
    }

    @Test
    fun specChangeReenablesDispatch() {
        val gate = ComputationMessagesDispatchGate()

        assertTrue(gate.shouldDispatch(101))
        assertFalse(gate.shouldDispatch(101))

        assertTrue(gate.shouldDispatch(202))
        assertFalse(gate.shouldDispatch(202))
    }

    @Test
    fun returningToPreviousSpecIsStillANewDispatchCycle() {
        val gate = ComputationMessagesDispatchGate()

        assertTrue(gate.shouldDispatch(101))
        assertTrue(gate.shouldDispatch(202))
        assertTrue(gate.shouldDispatch(101))
    }
}
