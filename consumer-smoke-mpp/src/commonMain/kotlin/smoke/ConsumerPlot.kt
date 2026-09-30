package smoke

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import org.jetbrains.letsPlot.compose.PlotFigureModel
import org.jetbrains.letsPlot.compose.PlotPanel
import org.jetbrains.letsPlot.compose.PlotPanelRaw
import org.jetbrains.letsPlot.core.interact.InteractionSpec
import org.jetbrains.letsPlot.geom.geomPoint
import org.jetbrains.letsPlot.letsPlot

@Composable
fun ConsumerPlot(
    externalFigureModel: PlotFigureModel? = null,
    alternateFigure: Boolean = false
) {
    val figure = remember(alternateFigure) {
        val data = if (alternateFigure) {
            mapOf(
                "x" to listOf(1, 2, 3, 4),
                "y" to listOf(9, 4, 1, 4)
            )
        } else {
            mapOf(
                "x" to listOf(1, 2, 3),
                "y" to listOf(1, 4, 9)
            )
        }

        letsPlot(data) +
            geomPoint(
                size = if (alternateFigure) 20.0 else 18.0,
                color = if (alternateFigure) "#3366CC" else "#D62728"
            ) {
                x = "x"
                y = "y"
            }
    }
    val figureModel = remember(externalFigureModel) {
        externalFigureModel ?: PlotFigureModel().apply {
            setDefaultInteractions(
                listOf(
                    InteractionSpec(InteractionSpec.Name.WHEEL_ZOOM),
                    InteractionSpec(InteractionSpec.Name.DRAG_PAN)
                )
            )
        }
    }
    DisposableEffect(figureModel, externalFigureModel) {
        onDispose {
            if (externalFigureModel == null) {
                figureModel.dispose()
            }
        }
    }

    key(alternateFigure) {
        PlotPanel(
            figure = figure,
            figureModel = figureModel,
            modifier = Modifier.fillMaxSize()
        )
    }
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
