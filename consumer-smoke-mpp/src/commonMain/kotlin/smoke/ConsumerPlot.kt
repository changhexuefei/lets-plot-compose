package smoke

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import org.jetbrains.letsPlot.compose.PlotFigureModel
import org.jetbrains.letsPlot.compose.PlotPanel
import org.jetbrains.letsPlot.compose.PlotPanelRaw
import org.jetbrains.letsPlot.core.interact.InteractionSpec
import org.jetbrains.letsPlot.geom.geomPoint
import org.jetbrains.letsPlot.letsPlot

@Composable
fun ConsumerPlot() {
    val data = mapOf(
        "x" to listOf(1, 2, 3),
        "y" to listOf(1, 4, 9)
    )
    val figure = remember(data) {
        letsPlot(data) + geomPoint(size = 18.0, color = "#D62728") {
            x = "x"
            y = "y"
        }
    }
    val figureModel = remember { PlotFigureModel() }

    LaunchedEffect(figureModel) {
        figureModel.setDefaultInteractions(
            listOf(
                InteractionSpec(InteractionSpec.Name.WHEEL_ZOOM),
                InteractionSpec(InteractionSpec.Name.DRAG_PAN)
            )
        )
    }
    DisposableEffect(figureModel) {
        onDispose { figureModel.dispose() }
    }

    PlotPanel(
        figure = figure,
        figureModel = figureModel,
        modifier = Modifier
            .fillMaxSize()
            .testTag("consumer-plot-root")
    )
}

@Suppress("unused")
@Composable
private fun PublicApiDefaultsCompileProbe() {
    PlotPanel(
        figure = letsPlot(mapOf("x" to listOf(1), "y" to listOf(1))) + geomPoint {
            x = "x"
            y = "y"
        }
    )
    PlotPanelRaw(rawSpec = mutableMapOf())
}
