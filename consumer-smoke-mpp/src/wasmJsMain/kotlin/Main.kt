import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import smoke.ConsumerPlot

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    ComposeViewport(
        viewportContainerId = "ComposeTarget",
        content = { ConsumerPlot() }
    )
}
