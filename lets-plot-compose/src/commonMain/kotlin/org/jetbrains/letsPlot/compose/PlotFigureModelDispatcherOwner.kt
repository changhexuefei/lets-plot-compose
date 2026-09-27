package org.jetbrains.letsPlot.compose

import org.jetbrains.letsPlot.core.interact.event.ToolEventDispatcher

/**
 * Tracks the dispatcher installed by one PlotPanel composition.
 *
 * Releasing a stale composition must not clear a newer dispatcher that another
 * composition has already installed into the same PlotFigureModel.
 */
internal class PlotFigureModelDispatcherOwner(
    private val figureModel: PlotFigureModel
) {
    private var ownedDispatcher: ToolEventDispatcher? = null

    fun bind(dispatcher: ToolEventDispatcher) {
        if (ownedDispatcher === dispatcher && figureModel.toolEventDispatcher === dispatcher) {
            return
        }

        figureModel.toolEventDispatcher = dispatcher
        ownedDispatcher = dispatcher
    }

    fun release() {
        val dispatcher = ownedDispatcher
        if (dispatcher != null && figureModel.toolEventDispatcher === dispatcher) {
            figureModel.toolEventDispatcher = null
        }
        ownedDispatcher = null
    }
}
