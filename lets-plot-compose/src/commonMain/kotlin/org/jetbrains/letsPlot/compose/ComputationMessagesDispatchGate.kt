package org.jetbrains.letsPlot.compose

/**
 * Allows computation messages to be delivered once per processed plot spec.
 *
 * Repaint/resize/spec-override updates for the same raw spec must not repeatedly notify the
 * consumer, while replacing the figure/spec must make the next computation result observable.
 */
internal class ComputationMessagesDispatchGate {
    private var lastSpecKey: Int? = null
    private var dispatchedForCurrentSpec: Boolean = false

    fun shouldDispatch(specKey: Int): Boolean {
        if (lastSpecKey != specKey) {
            lastSpecKey = specKey
            dispatchedForCurrentSpec = false
        }

        if (dispatchedForCurrentSpec) {
            return false
        }

        dispatchedForCurrentSpec = true
        return true
    }
}
