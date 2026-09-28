package org.jetbrains.letsPlot.compose

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DefaultInteractionFeedbackOwnerTest {

    @Test
    fun toolbarKeepsFallbackFeedbackDisabled() {
        val figureModel = PlotFigureModel()
        val owner = DefaultInteractionFeedbackOwner(
            figureModel = figureModel,
            hasToolbar = true,
            errorMessageHandler = {}
        )

        assertFalse(owner.isActive)

        owner.dispose()
        figureModel.dispose()
    }

    @Test
    fun toolbarlessPlotInstallsFallbackFeedbackOwner() {
        val figureModel = PlotFigureModel()
        val owner = DefaultInteractionFeedbackOwner(
            figureModel = figureModel,
            hasToolbar = false,
            errorMessageHandler = {}
        )

        assertTrue(owner.isActive)

        owner.dispose()
        figureModel.dispose()
    }
}
