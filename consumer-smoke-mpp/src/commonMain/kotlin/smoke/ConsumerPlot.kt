package smoke

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.jetbrains.letsPlot.compose.PlotPanel
import org.jetbrains.letsPlot.compose.PlotPanelRaw
import org.jetbrains.letsPlot.geom.geomPoint
import org.jetbrains.letsPlot.letsPlot

@Composable
fun ConsumerPlot() {
    val data = mapOf(
        "x" to listOf(1, 2, 3),
        "y" to listOf(1, 4, 9)
    )
    val figure = letsPlot(data) + geomPoint {
        x = "x"
        y = "y"
    }

    PlotPanel(
        figure = figure,
        modifier = Modifier.fillMaxSize()
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
