import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ComposeViewport
import org.jetbrains.letsPlot.compose.PlotFigureModel
import org.jetbrains.letsPlot.core.interact.InteractionSpec
import org.jetbrains.letsPlot.core.plot.builder.interact.tools.FigureModelOptions.COORD_XLIM_TRANSFORMED
import smoke.ConsumerPlot

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    ComposeViewport(
        viewportContainerId = "ComposeTarget",
        content = {
            val figureModel = remember {
                PlotFigureModel().apply {
                    setDefaultInteractions(
                        listOf(
                            InteractionSpec(InteractionSpec.Name.WHEEL_ZOOM),
                            InteractionSpec(InteractionSpec.Name.DRAG_PAN)
                        )
                    )
                }
            }
            DisposableEffect(figureModel) {
                onDispose { figureModel.dispose() }
            }

            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clickable {
                                figureModel.updateSpecOverride(
                                    mapOf(COORD_XLIM_TRANSFORMED to listOf(1.4, 2.6))
                                )
                                figureModel.updateView()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        BasicText("Apply FigureModel override")
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clickable {
                                figureModel.updateSpecOverride(null)
                                figureModel.updateView()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        BasicText("Rollback FigureModel override")
                    }
                }

                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    ConsumerPlot(externalFigureModel = figureModel)
                }
            }
        }
    )
}
