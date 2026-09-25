package smoke

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.delay
import org.jetbrains.letsPlot.Figure
import org.jetbrains.letsPlot.compose.PlotFigureModel
import org.jetbrains.letsPlot.compose.PlotPanel
import org.jetbrains.letsPlot.core.interact.InteractionSpec
import org.jetbrains.letsPlot.geom.geomPoint
import org.jetbrains.letsPlot.letsPlot
import java.awt.EventQueue
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.Robot
import java.awt.Window as AwtWindow
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.WindowEvent
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.imageio.ImageIO
import kotlin.math.abs

private const val WINDOW_TITLE = "Lets-Plot Consumer Smoke"
private const val IMAGE_CHANGE_THRESHOLD = 0.00005

fun main() {
    check(!GraphicsEnvironment.isHeadless()) {
        "Windows consumer smoke requires a graphical desktop; java.awt is headless."
    }

    val outputDir = Paths.get(
        System.getenv("SMOKE_OUTPUT_DIR")
            ?: "build/smoke"
    ).toAbsolutePath()
    Files.createDirectories(outputDir)

    val visibleWindow = AtomicReference<AwtWindow?>()
    val asyncFailure = AtomicReference<Throwable?>()
    val completed = AtomicBoolean(false)

    Thread.setDefaultUncaughtExceptionHandler { thread, error ->
        asyncFailure.compareAndSet(null, error)
        System.err.println("SMOKE_ASYNC_FAILURE thread=${thread.name}: ${error.stackTraceToString()}")
    }

    application(exitProcessOnExit = false) {
        var showWindow by remember { mutableStateOf(true) }
        var windowGeneration by remember { mutableIntStateOf(0) }
        var densityScale by remember { mutableFloatStateOf(1.0f) }
        var phase by remember { mutableStateOf("boot") }

        if (showWindow) {
            key(windowGeneration) {
                Window(
                    onCloseRequest = { showWindow = false },
                    title = WINDOW_TITLE,
                    state = rememberWindowState(width = 920.dp, height = 680.dp)
                ) {
                    DisposableEffect(window) {
                        visibleWindow.set(window)
                        onDispose {
                            visibleWindow.compareAndSet(window, null)
                        }
                    }

                    SmokeContent(
                        densityScale = densityScale,
                        phase = phase
                    )
                }
            }
        }

        LaunchedEffect(Unit) {
            try {
                val robot = Robot().apply {
                    autoDelay = 70
                    isAutoWaitForIdle = false
                }

                phase = "render"
                val firstWindow = awaitVisibleWindow(visibleWindow)
                delay(2_500)
                ensureNoAsyncFailure(asyncFailure)
                val renderImage = captureWindow(robot, firstWindow, outputDir.resolve("01-render.png"))
                assertImageHasContent(renderImage)
                checkpoint("render")

                phase = "resize"
                val initialWindowWidth = firstWindow.width
                val initialWindowHeight = firstWindow.height
                val requestedWidth = (initialWindowWidth - 140).coerceAtLeast(640)
                val requestedHeight = (initialWindowHeight - 90).coerceAtLeast(480)
                runOnAwtEdtAndWait {
                    firstWindow.setSize(requestedWidth, requestedHeight)
                    firstWindow.setLocation(60, 60)
                }
                delay(1_000)
                check(
                    kotlin.math.abs(firstWindow.width - initialWindowWidth) >= 40 ||
                        kotlin.math.abs(firstWindow.height - initialWindowHeight) >= 40
                ) {
                    "Resize did not take effect: before=${initialWindowWidth}x${initialWindowHeight}, " +
                        "after=${firstWindow.width}x${firstWindow.height}"
                }
                ensureNoAsyncFailure(asyncFailure)
                val resizeImage = captureWindow(robot, firstWindow, outputDir.resolve("02-resize.png"))
                assertImageHasContent(resizeImage)
                check(pixelDifferenceRatio(renderImage, resizeImage) > IMAGE_CHANGE_THRESHOLD) {
                    "Resize did not produce a visible plot/layout change."
                }
                checkpoint("resize")

                phase = "tooltip-hover"
                val tooltipBaseline = captureWindow(
                    robot,
                    firstWindow,
                    outputDir.resolve("03-tooltip-before.png")
                )
                val hoverImage = exerciseTooltipHover(robot, firstWindow, tooltipBaseline)
                ImageIO.write(hoverImage, "png", outputDir.resolve("04-tooltip-hover.png").toFile())
                check(pixelDifferenceRatio(tooltipBaseline, hoverImage) > IMAGE_CHANGE_THRESHOLD) {
                    "Hover did not produce a visible tooltip/repaint change."
                }
                ensureNoAsyncFailure(asyncFailure)
                checkpoint("tooltip")

                phase = "zoom"
                val beforeZoom = hoverImage
                exerciseWheelZoom(robot, firstWindow)
                delay(900)
                val afterZoom = captureWindow(robot, firstWindow, outputDir.resolve("05-zoom.png"))
                check(pixelDifferenceRatio(beforeZoom, afterZoom) > IMAGE_CHANGE_THRESHOLD) {
                    "Ctrl+Shift wheel zoom did not visibly change the plot."
                }
                ensureNoAsyncFailure(asyncFailure)
                checkpoint("zoom")

                phase = "pan"
                val beforePan = afterZoom
                exerciseDragPan(robot, firstWindow)
                delay(900)
                val afterPan = captureWindow(robot, firstWindow, outputDir.resolve("06-pan.png"))
                check(pixelDifferenceRatio(beforePan, afterPan) > IMAGE_CHANGE_THRESHOLD) {
                    "Ctrl+Shift drag pan did not visibly change the plot."
                }
                ensureNoAsyncFailure(asyncFailure)
                checkpoint("pan")

                phase = "density-1.25"
                densityScale = 1.25f
                delay(1_000)
                val density125 = captureWindow(robot, firstWindow, outputDir.resolve("07-density-1.25.png"))
                assertImageHasContent(density125)
                ensureNoAsyncFailure(asyncFailure)
                checkpoint("density-1.25")

                phase = "density-1.5"
                densityScale = 1.5f
                delay(1_000)
                val density15 = captureWindow(robot, firstWindow, outputDir.resolve("08-density-1.5.png"))
                check(pixelDifferenceRatio(density125, density15) > IMAGE_CHANGE_THRESHOLD) {
                    "Changing LocalDensity from 1.25x to 1.5x did not visibly re-render the consumer."
                }
                ensureNoAsyncFailure(asyncFailure)
                checkpoint("density-1.5")

                phase = "close"
                runOnAwtEdtAndWait {
                    firstWindow.dispatchEvent(WindowEvent(firstWindow, WindowEvent.WINDOW_CLOSING))
                }
                awaitWindowClosed(visibleWindow, firstWindow)
                checkpoint("close")

                phase = "reopen"
                densityScale = 1.0f
                windowGeneration += 1
                showWindow = true
                val reopenedWindow = awaitVisibleWindow(visibleWindow)
                check(reopenedWindow !== firstWindow) {
                    "Window close/reopen reused the disposed AWT window instance."
                }
                delay(1_800)
                val reopenedImage = captureWindow(robot, reopenedWindow, outputDir.resolve("09-reopen.png"))
                assertImageHasContent(reopenedImage)
                ensureNoAsyncFailure(asyncFailure)
                checkpoint("reopen")

                Files.writeString(
                    outputDir.resolve("smoke-summary.txt"),
                    buildString {
                        appendLine("result=PASS")
                        appendLine("java.version=${System.getProperty("java.version")}")
                        appendLine("java.vendor=${System.getProperty("java.vendor")}")
                        appendLine("os.name=${System.getProperty("os.name")}")
                        appendLine("os.version=${System.getProperty("os.version")}")
                        appendLine("renderApi=${System.getProperty("skiko.renderApi")}")
                        appendLine("checks=render,resize,tooltip,zoom,pan,density-1.0,density-1.25,density-1.5,close,reopen")
                    }
                )

                completed.set(true)
                println("SMOKE_RESULT PASS")
            } catch (error: Throwable) {
                asyncFailure.compareAndSet(null, error)
                System.err.println("SMOKE_RESULT FAIL: ${error.stackTraceToString()}")
            } finally {
                exitApplication()
            }
        }
    }

    asyncFailure.get()?.let { throw it }
    check(completed.get()) { "Consumer smoke did not reach successful completion." }
}

@Composable
private fun SmokeContent(
    densityScale: Float,
    phase: String
) {
    val figure = remember { createFigure() }
    val figureModel = remember { PlotFigureModel() }

    LaunchedEffect(figureModel) {
        figureModel.setDefaultInteractions(
            listOf(
                InteractionSpec(
                    InteractionSpec.Name.WHEEL_ZOOM,
                    keyModifiers = listOf(
                        InteractionSpec.KeyModifier.CTRL,
                        InteractionSpec.KeyModifier.SHIFT
                    )
                ),
                InteractionSpec(
                    InteractionSpec.Name.DRAG_PAN,
                    keyModifiers = listOf(
                        InteractionSpec.KeyModifier.CTRL,
                        InteractionSpec.KeyModifier.SHIFT
                    )
                )
            )
        )
    }

    CompositionLocalProvider(LocalDensity provides Density(densityScale)) {
        MaterialTheme {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp)
            ) {
                Text("Consumer smoke | phase=$phase | density=${"%.2f".format(densityScale)}x")

                PlotPanel(
                    figure = figure,
                    figureModel = figureModel,
                    preserveAspectRatio = false,
                    modifier = Modifier.fillMaxSize()
                ) { messages ->
                    messages.forEach { println("SMOKE_PLOT_MESSAGE $it") }
                }
            }
        }
    }
}

private fun createFigure(): Figure {
    val values = listOf(-2.0, -1.0, 0.0, 1.0, 2.0)
    val data = mapOf(
        "x" to values,
        "y" to values,
        "group" to listOf("A", "A", "CENTER", "B", "B")
    )

    return letsPlot(data) +
        geomPoint(size = 14.0, alpha = 0.9) {
            x = "x"
            y = "y"
            color = "group"
        }
}

private suspend fun awaitVisibleWindow(ref: AtomicReference<AwtWindow?>): AwtWindow {
    repeat(150) {
        ref.get()
            ?.takeIf { it.isShowing && it.width > 0 && it.height > 0 }
            ?.let { return it }
        delay(100)
    }
    error("Timed out waiting for Compose window.")
}

private suspend fun awaitWindowClosed(
    ref: AtomicReference<AwtWindow?>,
    oldWindow: AwtWindow
) {
    repeat(100) {
        if (!oldWindow.isShowing && ref.get() !== oldWindow) {
            return
        }
        delay(100)
    }
    error("Timed out waiting for Compose window to close.")
}

private suspend fun exerciseTooltipHover(
    robot: Robot,
    window: AwtWindow,
    baseline: BufferedImage
): BufferedImage {
    val origin = window.locationOnScreen
    val centerX = origin.x + window.width / 2
    val centerY = origin.y + window.height / 2 + 18

    val offsets = listOf(
        0 to 0,
        -12 to 0,
        12 to 0,
        0 to -12,
        0 to 12,
        -20 to -10,
        20 to 10
    )

    robot.mouseMove(centerX - 140, centerY - 90)
    delay(250)

    var latest = baseline
    for ((dx, dy) in offsets) {
        robot.mouseMove(centerX + dx, centerY + dy)
        delay(650)
        latest = screenCapture(robot, window)

        if (pixelDifferenceRatio(baseline, latest) > IMAGE_CHANGE_THRESHOLD) {
            return latest
        }
    }

    return latest
}

private fun exerciseWheelZoom(robot: Robot, window: AwtWindow) {
    movePointerToPlotCenter(robot, window)
    robot.keyPress(KeyEvent.VK_CONTROL)
    robot.keyPress(KeyEvent.VK_SHIFT)
    try {
        robot.mouseWheel(-4)
    } finally {
        robot.keyRelease(KeyEvent.VK_SHIFT)
        robot.keyRelease(KeyEvent.VK_CONTROL)
    }
}

private suspend fun exerciseDragPan(robot: Robot, window: AwtWindow) {
    val origin = window.locationOnScreen
    val startX = origin.x + window.width / 2
    val startY = origin.y + window.height / 2 + 18

    robot.mouseMove(startX, startY)
    robot.keyPress(KeyEvent.VK_CONTROL)
    robot.keyPress(KeyEvent.VK_SHIFT)
    robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
    try {
        repeat(10) { step ->
            robot.mouseMove(startX + (step + 1) * 10, startY + (step + 1) * 4)
            delay(30)
        }
    } finally {
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
        robot.keyRelease(KeyEvent.VK_SHIFT)
        robot.keyRelease(KeyEvent.VK_CONTROL)
    }
}

private fun movePointerToPlotCenter(robot: Robot, window: AwtWindow) {
    val origin = window.locationOnScreen
    robot.mouseMove(
        origin.x + window.width / 2,
        origin.y + window.height / 2 + 18
    )
}

private fun captureWindow(
    robot: Robot,
    window: AwtWindow,
    path: Path
): BufferedImage {
    val image = screenCapture(robot, window)
    Files.createDirectories(path.parent)
    check(ImageIO.write(image, "png", path.toFile())) {
        "No PNG ImageIO writer available."
    }
    check(Files.size(path) > 5_000) {
        "Captured image is unexpectedly small: $path"
    }
    return image
}

private fun screenCapture(robot: Robot, window: AwtWindow): BufferedImage {
    val origin = window.locationOnScreen
    return robot.createScreenCapture(
        Rectangle(origin.x, origin.y, window.width, window.height)
    )
}

private fun assertImageHasContent(image: BufferedImage) {
    val sampleStepX = (image.width / 40).coerceAtLeast(1)
    val sampleStepY = (image.height / 40).coerceAtLeast(1)
    val colors = HashSet<Int>()

    var y = 0
    while (y < image.height) {
        var x = 0
        while (x < image.width) {
            colors += image.getRGB(x, y)
            x += sampleStepX
        }
        y += sampleStepY
    }

    check(colors.size >= 12) {
        "Rendered window looks blank or nearly uniform: only ${colors.size} sampled colors."
    }
}

private fun pixelDifferenceRatio(
    left: BufferedImage,
    right: BufferedImage
): Double {
    val width = minOf(left.width, right.width)
    val height = minOf(left.height, right.height)
    val step = 2
    var changed = 0L
    var total = 0L

    var y = 0
    while (y < height) {
        var x = 0
        while (x < width) {
            val a = left.getRGB(x, y)
            val b = right.getRGB(x, y)

            val ar = (a shr 16) and 0xFF
            val ag = (a shr 8) and 0xFF
            val ab = a and 0xFF
            val br = (b shr 16) and 0xFF
            val bg = (b shr 8) and 0xFF
            val bb = b and 0xFF

            if (abs(ar - br) + abs(ag - bg) + abs(ab - bb) > 18) {
                changed++
            }
            total++
            x += step
        }
        y += step
    }

    return if (total == 0L) 0.0 else changed.toDouble() / total.toDouble()
}

private fun runOnAwtEdtAndWait(block: () -> Unit) {
    if (EventQueue.isDispatchThread()) {
        block()
    } else {
        EventQueue.invokeAndWait(block)
    }
}

private fun ensureNoAsyncFailure(asyncFailure: AtomicReference<Throwable?>) {
    asyncFailure.get()?.let { throw it }
}

private fun checkpoint(name: String) {
    println("SMOKE_CHECK $name PASS")
}
