package org.jetbrains.letsPlot.compose

import org.jetbrains.letsPlot.core.interact.InteractionSpec
import org.jetbrains.letsPlot.core.interact.event.ToolEventDispatcher
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertSame

class PlotFigureModelDispatcherOwnerTest {

    @Test
    fun releaseClearsDispatcherOwnedByThisComposition() {
        val figureModel = PlotFigureModel()
        val dispatcher = FakeToolEventDispatcher()
        val owner = PlotFigureModelDispatcherOwner(figureModel)

        owner.bind(dispatcher)
        assertSame(dispatcher, figureModel.toolEventDispatcher)

        owner.release()
        assertNull(figureModel.toolEventDispatcher)
    }

    @Test
    fun staleOwnerDoesNotClearDispatcherInstalledByReplacementComposition() {
        val figureModel = PlotFigureModel()
        val oldDispatcher = FakeToolEventDispatcher()
        val replacementDispatcher = FakeToolEventDispatcher()
        val staleOwner = PlotFigureModelDispatcherOwner(figureModel)

        staleOwner.bind(oldDispatcher)
        figureModel.toolEventDispatcher = replacementDispatcher

        staleOwner.release()

        assertSame(replacementDispatcher, figureModel.toolEventDispatcher)
        figureModel.dispose()
    }

    @Test
    fun bindingSameDispatcherTwiceDoesNotReinitializeFigureModel() {
        val figureModel = PlotFigureModel()
        val dispatcher = FakeToolEventDispatcher()
        val owner = PlotFigureModelDispatcherOwner(figureModel)

        owner.bind(dispatcher)
        owner.bind(dispatcher)

        assertSame(dispatcher, figureModel.toolEventDispatcher)
        kotlin.test.assertEquals(1, dispatcher.initCallbackCount)

        owner.release()
    }

    @Test
    fun ownerCanRebindWithoutAccumulatingStaleCleanup() {
        val figureModel = PlotFigureModel()
        val first = FakeToolEventDispatcher()
        val second = FakeToolEventDispatcher()
        val owner = PlotFigureModelDispatcherOwner(figureModel)

        owner.bind(first)
        owner.bind(second)
        assertSame(second, figureModel.toolEventDispatcher)

        owner.release()
        assertNull(figureModel.toolEventDispatcher)
    }

    private class FakeToolEventDispatcher : ToolEventDispatcher {
        var initCallbackCount: Int = 0
            private set

        override fun initToolEventCallback(callback: (Map<String, Any>) -> Unit) {
            initCallbackCount++
        }

        override fun activateInteractions(
            origin: String,
            interactionSpecList: List<InteractionSpec>
        ) = Unit

        override fun deactivateInteractions(origin: String): List<InteractionSpec> = emptyList()

        override fun deactivateAll() = Unit

        override fun setDefaultInteractions(interactionSpecList: List<InteractionSpec>) = Unit

        override fun deactivateAllSilently(): Map<String, List<InteractionSpec>> = emptyMap()
    }
}
