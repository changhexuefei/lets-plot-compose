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
import androidx.compose.runtime.SideEffect
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
import org.jetbrains.letsPlot.compose.PlotPanelRaw
import org.jetbrains.letsPlot.core.interact.InteractionSpec
import org.jetbrains.letsPlot.core.plot.builder.interact.tools.FigureModelOptions.COORD_XLIM_TRANSFORMED
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
import java.util.concurrent.atomic.AtomicInteger
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
    val externalFigureModel = AtomicReference<PlotFigureModel?>()
    val figureVariantSetter = AtomicReference<((Boolean) -> Unit)?>()
    val computationCallbackCount = AtomicInteger(0)
    val recompositionCount = AtomicInteger(0)
    val contentDisposeCount = AtomicInteger(0)
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
        var recompositionGeneration by remember { mutableIntStateOf(0) }

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
                        recompositionGeneration = recompositionGeneration,
                        externalFigureModelRef = externalFigureModel,
                        figureVariantSetterRef = figureVariantSetter,
                        computationCallbackCount = computationCallbackCount,
                        recompositionCount = recompositionCount,
                        contentDisposeCount = contentDisposeCount
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
                val firstWindow = awaitVisibleWindow(visibleWindow)
                delay(2_500)
                ensureNoAsyncFailure(asyncFailure)
                val renderImage = captureWindow(robot, firstWindow, outputDir.resolve("01-render.png"))
                assertImageHasContent(renderImage)
                assertSmokeFigureRendered(renderImage)
                checkpoint("render")

                awaitComputationCallbackCount(computationCallbackCount, expected = 1)
                check(computationCallbackCount.get() == 1) {
                    "Initial figure dispatched computation messages more than once: " +
                        "count=${computationCallbackCount.get()}"
                }

                val recompositionsBeforeProbe = recompositionCount.get()
                recompositionGeneration += 1
                delay(600)
                ensureNoAsyncFailure(asyncFailure)
                val recompositionsAfterProbe = recompositionCount.get()
                check(recompositionsAfterProbe > recompositionsBeforeProbe) {
                    "Explicit recomposition probe did not recompose SmokeContent: " +
                        "before=$recompositionsBeforeProbe after=$recompositionsAfterProbe"
                }
                check(contentDisposeCount.get() == 0) {
                    "Explicit recomposition unexpectedly disposed consumer resources: " +
                        "disposeCount=${contentDisposeCount.get()}"
                }
                checkpoint("recomposition")

                val figureModel = awaitFigureModel(externalFigureModel)
                val programmaticDir = outputDir.resolve("programmatic")
                Files.createDirectories(programmaticDir)

                figureModel.updateSpecOverride(null)
                figureModel.updateView()
                delay(900)
                val programmaticBaseline = captureWindow(
                    robot,
                    firstWindow,
                    programmaticDir.resolve("01-baseline.png")
                )

                figureModel.updateSpecOverride(
                    mapOf(COORD_XLIM_TRANSFORMED to listOf(-1.25, 1.25))
                )
                figureModel.updateView()
                delay(900)
                val programmaticOverride = captureWindow(
                    robot,
                    firstWindow,
                    programmaticDir.resolve("02-override.png")
                )
                val programmaticOverrideRatio =
                    pixelDifferenceRatio(programmaticBaseline, programmaticOverride)
                check(programmaticOverrideRatio > IMAGE_CHANGE_THRESHOLD) {
                    "Programmatic FigureModel override did not visibly change the Desktop consumer. " +
                        "ratio=$programmaticOverrideRatio"
                }

                figureModel.updateSpecOverride(null)
                figureModel.updateView()
                delay(900)
                val programmaticRollback = captureWindow(
                    robot,
                    firstWindow,
                    programmaticDir.resolve("03-rollback.png")
                )
                val programmaticRollbackRatio =
                    pixelDifferenceRatio(programmaticBaseline, programmaticRollback)
                check(programmaticRollbackRatio < 0.002) {
                    "Programmatic FigureModel rollback did not restore the Desktop consumer baseline. " +
                        "ratio=$programmaticRollbackRatio"
                }
                check(computationCallbackCount.get() == 1) {
                    "Same-spec programmatic updates redispatched computation messages: " +
                        "count=${computationCallbackCount.get()}"
                }
                checkpoint("figure-model-programmatic")

                val lifecycleDir = outputDir.resolve("lifecycle")
                Files.createDirectories(lifecycleDir)
                val reconnectBaseline = captureWindow(
                    robot,
                    firstWindow,
                    lifecycleDir.resolve("01-before-figure-replace.png")
                )

                val setAlternateFigure = awaitFigureVariantSetter(figureVariantSetter)
                setAlternateFigure(true)
                delay(1_000)
                ensureNoAsyncFailure(asyncFailure)
                awaitComputationCallbackCount(computationCallbackCount, expected = 2)
                check(computationCallbackCount.get() == 2) {
                    "Replacement figure did not start exactly one new computation-message cycle: " +
                        "count=${computationCallbackCount.get()}"
                }

                val reboundModel = awaitFigureModel(externalFigureModel)
                check(reboundModel === figureModel) {
                    "Replacing the figure created a different external PlotFigureModel instance."
                }

                val replacedFigure = captureWindow(
                    robot,
                    firstWindow,
                    lifecycleDir.resolve("02-after-figure-replace.png")
                )
                val figureReplaceDiffRatio =
                    pixelDifferenceRatio(reconnectBaseline, replacedFigure)
                check(figureReplaceDiffRatio > IMAGE_CHANGE_THRESHOLD) {
                    "Replacing the figure did not visibly update the Desktop consumer. " +
                        "ratio=$figureReplaceDiffRatio"
                }

                figureModel.updateSpecOverride(
                    mapOf(COORD_XLIM_TRANSFORMED to listOf(-1.5, 1.5))
                )
                figureModel.updateView()
                delay(900)

                val controlledReplacement = captureWindow(
                    robot,
                    firstWindow,
                    lifecycleDir.resolve("03-replacement-programmatic-override.png")
                )
                val replacementControlDiffRatio =
                    pixelDifferenceRatio(replacedFigure, controlledReplacement)
                check(replacementControlDiffRatio > IMAGE_CHANGE_THRESHOLD) {
                    "The reused PlotFigureModel did not control the replacement figure. " +
                        "ratio=$replacementControlDiffRatio"
                }
                check(computationCallbackCount.get() == 2) {
                    "Same-spec replacement override redispatched computation messages: " +
                        "count=${computationCallbackCount.get()}"
                }

                figureModel.updateSpecOverride(null)
                figureModel.updateView()
                setAlternateFigure(false)
                delay(1_000)
                awaitComputationCallbackCount(computationCallbackCount, expected = 3)
                check(computationCallbackCount.get() == 3) {
                    "Restoring the original spec did not start exactly one new computation-message cycle: " +
                        "count=${computationCallbackCount.get()}"
                }

                val restoredModel = awaitFigureModel(externalFigureModel)
                check(restoredModel === figureModel) {
                    "Restoring the original figure replaced the external PlotFigureModel instance."
                }
                captureWindow(
                    robot,
                    firstWindow,
                    lifecycleDir.resolve("04-restored-original-figure.png")
                )
                checkpoint("figure-model-reconnect")

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
                if (System.getenv("SMOKE_TOOLTIP_STABLE_BASELINE").equals("true", ignoreCase = true)) {
                    movePointerOutsidePlot(robot, firstWindow)
                    delay(
                        System.getenv("SMOKE_TOOLTIP_DWELL_MS")
                            ?.toLongOrNull()
                            ?.coerceIn(100L, 2_000L)
                            ?: 300L
                    )
                }
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
                val beforeZoom = hoverImage
                val afterZoom = exerciseWheelZoom(robot, firstWindow, beforeZoom)
                check(ImageIO.write(afterZoom, "png", outputDir.resolve("05-zoom.png").toFile())) {
                    "No PNG ImageIO writer available for zoom evidence."
                }
                ensureNoAsyncFailure(asyncFailure)
                checkpoint("zoom")
                val beforePan = afterZoom
                exerciseDragPan(robot, firstWindow)
                delay(900)
                val afterPan = captureWindow(robot, firstWindow, outputDir.resolve("06-pan.png"))
                check(pixelDifferenceRatio(beforePan, afterPan) > IMAGE_CHANGE_THRESHOLD) {
                    "Ctrl+Shift drag pan did not visibly change the plot."
                }
                ensureNoAsyncFailure(asyncFailure)
                checkpoint("pan")
                densityScale = 1.25f
                delay(1_000)
                val density125 = captureWindow(robot, firstWindow, outputDir.resolve("07-density-1.25.png"))
                assertImageHasContent(density125)
                ensureNoAsyncFailure(asyncFailure)
                checkpoint("density-1.25")
                densityScale = 1.5f
                delay(1_000)
                val density15 = captureWindow(robot, firstWindow, outputDir.resolve("08-density-1.5.png"))
                check(pixelDifferenceRatio(density125, density15) > IMAGE_CHANGE_THRESHOLD) {
                    "Changing LocalDensity from 1.25x to 1.5x did not visibly re-render the consumer."
                }
                ensureNoAsyncFailure(asyncFailure)
                checkpoint("density-1.5")
                runOnAwtEdtAndWait {
                    firstWindow.dispatchEvent(WindowEvent(firstWindow, WindowEvent.WINDOW_CLOSING))
                }
                awaitWindowClosed(visibleWindow, firstWindow)
                repeat(40) {
                    if (contentDisposeCount.get() >= 1) return@repeat
                    delay(50)
                }
                check(contentDisposeCount.get() >= 1) {
                    "Closing the Desktop consumer did not execute the PlotFigureModel disposal path."
                }
                checkpoint("close")
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
                        appendLine(
                            "desktop.renderPath.requested=" +
                                (System.getProperty("letsplot.compose.desktop.renderPath") ?: "default")
                        )
                        appendLine(
                            "graphite.runtime.activation=" +
                                (System.getProperty("letsplot.compose.graphite.runtime.activation") ?: "ABSENT")
                        )
                        appendLine(
                            "graphite.runtime.backend=" +
                                (System.getProperty("letsplot.compose.graphite.runtime.backend") ?: "ABSENT")
                        )
                        appendLine(
                            "graphite.runtime.compatibility=" +
                                (System.getProperty("letsplot.compose.graphite.runtime.compatibility") ?: "ABSENT")
                        )
                        appendLine(
                            "graphite.runtime.compatibility.reason=" +
                                (System.getProperty("letsplot.compose.graphite.runtime.compatibility.reason") ?: "ABSENT")
                        )
                        appendLine(
                            "graphite.runtime.compatibility.compose=" +
                                (System.getProperty("letsplot.compose.graphite.runtime.compatibility.compose") ?: "ABSENT")
                        )
                        appendLine(
                            "graphite.runtime.compatibility.skiko=" +
                                (System.getProperty("letsplot.compose.graphite.runtime.compatibility.skiko") ?: "ABSENT")
                        )
                        appendLine(
                            "graphite.runtime.compatibility.profile=" +
                                (System.getProperty("letsplot.compose.graphite.runtime.compatibility.profile") ?: "ABSENT")
                        )
                        appendLine(
                            "graphite.runtime.compatibility.platform=" +
                                (System.getProperty("letsplot.compose.graphite.runtime.compatibility.platform") ?: "ABSENT")
                        )
                        appendLine(
                            "graphite.runtime.compatibility.java=" +
                                (System.getProperty("letsplot.compose.graphite.runtime.compatibility.java") ?: "ABSENT")
                        )
                        appendLine(
                            "desktop.offscreen.failure=" +
                                (System.getProperty("letsplot.compose.desktop.offscreen.failure") ?: "ABSENT")
                        )
                        appendLine(
                            "desktop.offscreen.failureCount=" +
                                (System.getProperty("letsplot.compose.desktop.offscreen.failureCount") ?: "0")
                        )
                        appendLine(
                            "graphite.runtime.injectedFailure=" +
                                (System.getProperty("letsplot.compose.graphite.runtime.injectedFailure") ?: "ABSENT")
                        )
                        appendLine(
                            "graphite.runtime.paintAttempts=" +
                                (System.getProperty("letsplot.compose.graphite.runtime.paintAttempts") ?: "0")
                        )
                        appendLine(
                            "graphite.runtime.contextCreated=" +
                                (System.getProperty("letsplot.compose.graphite.runtime.contextCreated") ?: "ABSENT")
                        )
                        appendLine(
                            "graphite.runtime.targetCreated=" +
                                (System.getProperty("letsplot.compose.graphite.runtime.targetCreated") ?: "ABSENT")
                        )
                        appendLine(
                            "graphite.runtime.disposeCount=" +
                                (System.getProperty("letsplot.compose.graphite.runtime.disposeCount") ?: "0")
                        )
                        appendLine(
                            "graphite.runtime.cleanup=" +
                                (System.getProperty("letsplot.compose.graphite.runtime.cleanup") ?: "ABSENT")
                        )
                        appendLine("lifecycle.recomposition=PASS")
                        appendLine("lifecycle.recomposition.before=$recompositionsBeforeProbe")
                        appendLine("lifecycle.recomposition.after=$recompositionsAfterProbe")
                        appendLine("lifecycle.resource_disposal=PASS")
                        appendLine("lifecycle.resource_dispose_count=${contentDisposeCount.get()}")
                        appendLine("figure_model.external=TRUE")
                        appendLine("figure_model.programmatic_override=PASS")
                        appendLine("figure_model.programmatic_rollback=PASS")
                        appendLine("figure_model.override_diff_ratio=$programmaticOverrideRatio")
                        appendLine("figure_model.rollback_diff_ratio=$programmaticRollbackRatio")
                        appendLine("figure_model.reconnect_after_figure_replace=PASS")
                        appendLine("figure_model.replacement_control=PASS")
                        appendLine("figure_model.replace_diff_ratio=$figureReplaceDiffRatio")
                        appendLine("figure_model.replacement_control_diff_ratio=$replacementControlDiffRatio")
                        appendLine("computation_messages.initial_dispatch=PASS")
                        appendLine("computation_messages.same_spec_dedup=PASS")
                        appendLine("computation_messages.spec_replace_redispatch=PASS")
                        appendLine("computation_messages.restore_redispatch=PASS")
                        appendLine("computation_messages.pre_reopen_count=3")
                        appendLine("checks=render,recomposition,resource-disposal,figure-model-programmatic,figure-model-reconnect,computation-message-redispatch,resize,tooltip,zoom,pan,density-1.0,density-1.25,density-1.5,close,reopen")
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
    recompositionGeneration: Int,
    externalFigureModelRef: AtomicReference<PlotFigureModel?>,
    figureVariantSetterRef: AtomicReference<((Boolean) -> Unit)?>,
    computationCallbackCount: AtomicInteger,
    recompositionCount: AtomicInteger,
    contentDisposeCount: AtomicInteger
) {
    var alternateFigure by remember { mutableStateOf(false) }
    val figure = remember(alternateFigure) {
        if (alternateFigure) createAlternateFigure() else createFigure()
    }
    val figureModel = remember { PlotFigureModel() }

    SideEffect {
        val count = recompositionCount.incrementAndGet()
        println("SMOKE_RECOMPOSITION generation=$recompositionGeneration count=$count")
    }

    DisposableEffect(figureModel, externalFigureModelRef, figureVariantSetterRef) {
        externalFigureModelRef.set(figureModel)
        figureVariantSetterRef.set { alternate -> alternateFigure = alternate }
        onDispose {
            figureVariantSetterRef.set(null)
            externalFigureModelRef.compareAndSet(figureModel, null)
            figureModel.dispose()
            val disposeCount = contentDisposeCount.incrementAndGet()
            println("SMOKE_RESOURCE_DISPOSE count=$disposeCount")
        }
    }

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
                Text("Lets-Plot consumer smoke")
                @Suppress("UNUSED_VARIABLE")
                val lifecycleRecompositionProbe = recompositionGeneration

                key(alternateFigure) {
                    PlotPanel(
                        figure = figure,
                        figureModel = figureModel,
                        preserveAspectRatio = false,
                        modifier = Modifier.fillMaxSize()
                    ) { messages ->
                        val count = computationCallbackCount.incrementAndGet()
                        println("SMOKE_COMPUTATION_MESSAGES dispatch=$count size=${messages.size}")
                        messages.forEach { println("SMOKE_PLOT_MESSAGE $it") }
                    }
                }
            }
        }
    }
}

@Suppress("unused")
@Composable
private fun PublicApiDefaultsCompileProbe(figure: Figure) {
    PlotPanel(figure = figure)
    PlotPanelRaw(rawSpec = mutableMapOf())
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

private fun createAlternateFigure(): Figure {
    val xValues = listOf(-3.0, -1.0, 1.0, 3.0)
    val yValues = listOf(9.0, 1.0, 1.0, 9.0)
    val data = mapOf(
        "x" to xValues,
        "y" to yValues
    )

    return letsPlot(data) +
        geomPoint(size = 18.0, color = "#3366CC") {
            x = "x"
            y = "y"
        }
}

private suspend fun awaitComputationCallbackCount(
    counter: AtomicInteger,
    expected: Int
) {
    repeat(100) {
        if (counter.get() >= expected) {
            return
        }
        delay(100)
    }
    error(
        "Timed out waiting for computation-message callback count $expected; " +
            "actual=${counter.get()}"
    )
}

private suspend fun awaitFigureVariantSetter(
    ref: AtomicReference<((Boolean) -> Unit)?>
): (Boolean) -> Unit {
    repeat(150) {
        ref.get()?.let { return it }
        delay(100)
    }
    error("Timed out waiting for figure replacement control.")
}

private suspend fun awaitFigureModel(
    ref: AtomicReference<PlotFigureModel?>
): PlotFigureModel {
    repeat(150) {
        ref.get()?.let { return it }
        delay(100)
    }
    error("Timed out waiting for external PlotFigureModel.")
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

    // Preserve the canonical probe defaults. Preview lanes may widen the search
    // and dwell longer without changing the visible-change assertion threshold.
    val dwellMs = System.getenv("SMOKE_TOOLTIP_DWELL_MS")
        ?.toLongOrNull()
        ?.coerceIn(100L, 2_000L)
        ?: 300L
    val xRadius = System.getenv("SMOKE_TOOLTIP_X_RADIUS")
        ?.toIntOrNull()
        ?.coerceIn(40, 240)
        ?: 120
    val yRadius = System.getenv("SMOKE_TOOLTIP_Y_RADIUS")
        ?.toIntOrNull()
        ?.coerceIn(30, 180)
        ?: 90

    val offsets = buildList {
        add(0 to 0)

        val xOffsets = (-xRadius..xRadius step 40).toList()
        val yOffsets = (-yRadius..yRadius step 30).toList()
        for (dy in yOffsets) {
            for (dx in xOffsets) {
                if (dx != 0 || dy != 0) {
                    add(dx to dy)
                }
            }
        }
    }

    var latest = baseline
    var bestImage = baseline
    var bestDifference = 0.0
    var bestOffset = 0 to 0

    for ((dx, dy) in offsets) {
        val screenX = centerX + dx
        val screenY = centerY + dy

        robot.mouseMove(screenX, screenY)
        delay(dwellMs)
        latest = screenCapture(robot, window)
        val difference = pixelDifferenceRatio(baseline, latest)

        if (difference > bestDifference) {
            bestDifference = difference
            bestOffset = dx to dy
            bestImage = latest
        }

        // The threshold is deliberately unchanged: a candidate still passes only
        // when hovering produces a visible repaint above the canonical threshold.
        if (difference > IMAGE_CHANGE_THRESHOLD) {
            println(
                "SMOKE_TOOLTIP_HIT mode=robot offset=${dx},${dy} difference=$difference"
            )
            return latest
        }
    }

    println(
        "SMOKE_TOOLTIP_MISS bestOffset=${bestOffset.first},${bestOffset.second} " +
            "bestDifference=$bestDifference threshold=$IMAGE_CHANGE_THRESHOLD"
    )
    return bestImage
}

private fun movePointerOutsidePlot(robot: Robot, window: AwtWindow) {
    val origin = window.locationOnScreen
    robot.mouseMove(
        origin.x + 8,
        origin.y + 8
    )
}

private suspend fun exerciseWheelZoom(
    robot: Robot,
    window: AwtWindow,
    baseline: BufferedImage
): BufferedImage {
    val origin = window.locationOnScreen
    val centerX = origin.x + window.width / 2
    val centerY = origin.y + window.height / 2 + 18

    // Keep this probe end-to-end: Robot still drives the real desktop input path.
    // Compose 1.13 preview can occasionally observe the wheel event before the
    // freshly pressed modifier state is reflected in pointer keyboard modifiers.
    // Prime that state with a tiny real mouse move, then send several small wheel
    // ticks and require an actual visible plot change before accepting the probe.
    robot.mouseMove(centerX, centerY)
    delay(150)

    robot.keyPress(KeyEvent.VK_CONTROL)
    robot.keyPress(KeyEvent.VK_SHIFT)
    try {
        delay(180)
        robot.mouseMove(centerX + 1, centerY)
        delay(120)
        robot.mouseMove(centerX, centerY)
        delay(120)

        var bestImage = baseline
        var bestDifference = 0.0

        repeat(4) { attempt ->
            robot.mouseWheel(-1)
            delay(450)

            val candidate = screenCapture(robot, window)
            val difference = pixelDifferenceRatio(baseline, candidate)
            if (difference > bestDifference) {
                bestDifference = difference
                bestImage = candidate
            }

            println(
                "SMOKE_ZOOM_ATTEMPT attempt=${attempt + 1} " +
                    "difference=$difference threshold=$IMAGE_CHANGE_THRESHOLD"
            )

            if (difference > IMAGE_CHANGE_THRESHOLD) {
                println(
                    "SMOKE_ZOOM_HIT mode=robot attempt=${attempt + 1} difference=$difference"
                )
                return candidate
            }
        }

        error(
            "Ctrl+Shift wheel zoom did not visibly change the plot after 4 real wheel ticks. " +
                "bestDifference=$bestDifference threshold=$IMAGE_CHANGE_THRESHOLD"
        )
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

private fun assertSmokeFigureRendered(image: BufferedImage) {
    var redPixels = 0
    var greenPixels = 0

    var y = 0
    while (y < image.height) {
        var x = 0
        while (x < image.width) {
            val rgb = image.getRGB(x, y)
            val r = (rgb shr 16) and 0xFF
            val g = (rgb shr 8) and 0xFF
            val b = rgb and 0xFF

            if (r > 180 && g < 120 && b < 120) {
                redPixels++
            }
            if (g > 120 && r < 160 && b < 160) {
                greenPixels++
            }
            x += 2
        }
        y += 2
    }

    check(redPixels >= 250 && greenPixels >= 250) {
        "Smoke figure markers were not rendered: redPixels=$redPixels greenPixels=$greenPixels. " +
            "The window may contain an internal renderer error panel instead of the plot."
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
