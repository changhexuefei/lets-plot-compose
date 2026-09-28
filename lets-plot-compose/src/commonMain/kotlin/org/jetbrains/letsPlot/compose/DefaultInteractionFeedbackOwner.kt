package org.jetbrains.letsPlot.compose

import org.jetbrains.letsPlot.commons.registration.Registration
import org.jetbrains.letsPlot.core.plot.builder.interact.tools.DefaultFigureToolsController

/**
 * Owns the fallback figure-tools feedback callback used when a plot has no built-in toolbar.
 *
 * PlotToolbar installs its own DefaultFigureToolsController callback. Without a toolbar we
 * still need a controller so wheel/drag interaction feedback updates the shared FigureModel
 * consistently on Desktop, Android, and WasmJS.
 */
internal class DefaultInteractionFeedbackOwner(
    figureModel: PlotFigureModel,
    hasToolbar: Boolean,
    errorMessageHandler: (String) -> Unit
) {
    val isActive: Boolean = !hasToolbar

    private val registration: Registration =
        if (isActive) {
            val controller = DefaultFigureToolsController(
                figure = figureModel,
                errorMessageHandler = errorMessageHandler
            )
            figureModel.addToolEventCallback { event ->
                controller.handleToolFeedback(event)
            }
        } else {
            Registration.EMPTY
        }

    fun dispose() {
        registration.dispose()
    }
}
