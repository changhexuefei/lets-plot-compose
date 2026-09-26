package probe

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.delay
import org.jetbrains.letsPlot.commons.registration.Registration
import org.jetbrains.letsPlot.compose.PlotFigureModel
import org.jetbrains.letsPlot.compose.PlotPanel
import org.jetbrains.letsPlot.core.interact.InteractionSpec
import java.awt.Rectangle
import java.awt.Robot
import java.awt.event.InputEvent
import java.awt.image.BufferedImage
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil
import kotlin.system.exitProcess

private const val PERFORMANCE_RENDER_PATH_PROPERTY = "letsplot.compose.desktop.renderPath"
private const val PERFORMANCE_WINDOW_WIDTH = 800
private const val PERFORMANCE_WINDOW_HEIGHT = 550
private const val PERFORMANCE_WARMUP_ROUNDS = 2
private const val PERFORMANCE_MEASURED_ROUNDS = 4
private const val PERFORMANCE_INTERACTION_TIMEOUT_MS = 5_000L
private const val PERFORMANCE_READY_NON_WHITE = 80
private const val PERFORMANCE_VISIBLE_CHANGE_NON_WHITE = 35

private enum class PerformancePath(
    val envValue: String,
    val propertyValue: String,
    val evidenceValue: String
) {
    NATIVE("native", "native", "NATIVE_CANVAS"),
    GRAPHITE("graphite", "graphite-offscreen", "OFFSCREEN_COMPOSITE");

    companion object {
        fun fromEnvironment(value: String?): PerformancePath =
            entries.firstOrNull { it.envValue.equals(value?.trim(), ignoreCase = true) }
                ?: error("PLOT_PERF_RENDER_PATH must be native or graphite")
    }
}

private data class WindowFingerprint(
    val hash: Long,
    val nonWhite: Int
)

private data class LatencySample(
    val kind: String,
    val elapsedMs: Double
)

fun main() {
    val mode = PerformancePath.fromEnvironment(System.getenv("PLOT_PERF_RENDER_PATH"))
    val outputDir = File(
        System.getenv("PLOT_PERFORMANCE_OUTPUT_DIR")
            ?: "graphite-plotpanel-shadow-probe/build/performance"
    ).apply { mkdirs() }
    val resultFile = outputDir.resolve("plotpanel-performance-${mode.envValue}.txt")

    val evidence = linkedMapOf(
        "schema" to "1",
        "probe.type" to "compose-plotpanel-native-graphite-performance-evidence",
        "render.path" to mode.evidenceValue,
        "measurement.kind" to "REAL_WINDOW_INPUT_TO_VISIBLE_CHANGE",
        "measurement.policy" to "OBSERVATIONAL_ONLY",
        "interaction.input" to "AWT_ROBOT_REAL_WINDOW",
        "warmup.rounds" to PERFORMANCE_WARMUP_ROUNDS.toString(),
        "measured.rounds" to PERFORMANCE_MEASURED_ROUNDS.toString(),
        "measured.interactions" to (PERFORMANCE_MEASURED_ROUNDS * 2).toString(),
        "production.renderer.default" to "NATIVE_CANVAS",
        "renderer.adoption" to "SHADOW_OPT_IN_ONLY",
        "public.api.change" to "NONE"
    )

    val completed = AtomicBoolean(false)
    var registration: Registration? = null
    val backend = if (mode == PerformancePath.GRAPHITE) {
        PersistentGraphiteShadowBackend(evidence)
    } else {
        null
    }
    val previousProperty = System.getProperty(PERFORMANCE_RENDER_PATH_PROPERTY)

    Thread.setDefaultUncaughtExceptionHandler { _, throwable ->
        if (completed.compareAndSet(false, true)) {
            evidence["result"] = "FAIL"
            evidence["failure.stage"] = "uncaught"
            evidence["failure.type"] = throwable::class.qualifiedName ?: throwable::class.simpleName.orEmpty()
            evidence["failure.message"] = sanitizePerformance(throwable.message ?: throwable.toString())
            writePerformanceEvidence(resultFile, evidence)
        }
        exitProcess(45)
    }

    Thread({
        Thread.sleep(75_000)
        if (completed.compareAndSet(false, true)) {
            evidence["result"] = "FAIL"
            evidence["failure.stage"] = "watchdog"
            evidence["failure.type"] = "TIMEOUT"
            evidence["failure.message"] = "PlotPanel performance evidence probe did not complete within 75 seconds"
            writePerformanceEvidence(resultFile, evidence)
            exitProcess(46)
        }
    }, "plotpanel-performance-watchdog").apply {
        isDaemon = true
        start()
    }

    val figureModel = PlotFigureModel().apply {
        setDefaultInteractions(
            listOf(
                InteractionSpec(InteractionSpec.Name.WHEEL_ZOOM),
                InteractionSpec(InteractionSpec.Name.DRAG_PAN)
            )
        )
    }

    try {
        check(System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
            "PlotPanel performance evidence probe is intentionally Windows-only"
        }

        if (mode == PerformancePath.GRAPHITE) {
            preloadDirectVulkan(evidence)
            registration = installInternalRenderer(checkNotNull(backend), evidence)
            evidence["provider.install"] = "PASS"
        }

        System.setProperty(PERFORMANCE_RENDER_PATH_PROPERTY, mode.propertyValue)

        val samples = mutableListOf<LatencySample>()
        var windowReadyMs = 0.0

        application(exitProcessOnExit = false) {
            val state = rememberWindowState(
                width = PERFORMANCE_WINDOW_WIDTH.dp,
                height = PERFORMANCE_WINDOW_HEIGHT.dp
            )

            Window(
                onCloseRequest = ::exitApplication,
                state = state,
                title = "Lets-Plot PlotPanel Performance Evidence",
                undecorated = true,
                resizable = false
            ) {
                val composeWindow = window

                PlotPanel(
                    figure = createFigure(),
                    figureModel = figureModel,
                    modifier = Modifier.fillMaxSize(),
                    computationMessagesHandler = {}
                )

                LaunchedEffect(Unit) {
                    val readyStart = System.nanoTime()
                    waitForVisiblePlot(composeWindow)
                    windowReadyMs = elapsedMs(readyStart)

                    repeat(PERFORMANCE_WARMUP_ROUNDS) { index ->
                        measureWheelLatency(
                            window = composeWindow,
                            figureModel = figureModel,
                            rotation = if (index % 2 == 0) 1 else -1
                        )
                        measurePanLatency(
                            window = composeWindow,
                            figureModel = figureModel,
                            direction = if (index % 2 == 0) -1 else 1
                        )
                    }
                    evidence["warmup"] = "PASS"

                    repeat(PERFORMANCE_MEASURED_ROUNDS) { index ->
                        samples += LatencySample(
                            kind = "wheel",
                            elapsedMs = measureWheelLatency(
                                window = composeWindow,
                                figureModel = figureModel,
                                rotation = if (index % 2 == 0) 1 else -1
                            )
                        )
                        samples += LatencySample(
                            kind = "pan",
                            elapsedMs = measurePanLatency(
                                window = composeWindow,
                                figureModel = figureModel,
                                direction = if (index % 2 == 0) -1 else 1
                            )
                        )
                    }

                    evidence["interaction.state_changes"] = samples.size.toString()
                    evidence["interaction.visible_changes"] = samples.size.toString()
                    evidence["measurement.samples"] = samples.size.toString()
                    evidence["window.ready_ms"] = formatMs(windowReadyMs)
                    evidence["latency.overall.median_ms"] = formatMs(percentile(samples.map { it.elapsedMs }, 0.50))
                    evidence["latency.overall.p95_ms"] = formatMs(percentile(samples.map { it.elapsedMs }, 0.95))
                    evidence["latency.overall.max_ms"] = formatMs(samples.maxOf { it.elapsedMs })
                    evidence["latency.wheel.median_ms"] =
                        formatMs(percentile(samples.filter { it.kind == "wheel" }.map { it.elapsedMs }, 0.50))
                    evidence["latency.pan.median_ms"] =
                        formatMs(percentile(samples.filter { it.kind == "pan" }.map { it.elapsedMs }, 0.50))

                    samples.forEachIndexed { index, sample ->
                        evidence["sample.${index + 1}.kind"] = sample.kind
                        evidence["sample.${index + 1}.ms"] = formatMs(sample.elapsedMs)
                    }

                    evidence["compose.plotpanel"] = "PASS"
                    evidence["measurement.complete"] = "PASS"
                    exitApplication()
                }
            }
        }

        figureModel.dispose()
        evidence["figure_model.dispose"] = "PASS"

        if (mode == PerformancePath.GRAPHITE) {
            val beforeDispose = checkNotNull(backend).snapshot()
            check(beforeDispose.contextCreateCount == 1) {
                "Graphite context create count is unexpected during performance evidence: ${beforeDispose.contextCreateCount}"
            }
            check(beforeDispose.targetCreateCount == 1) {
                "Graphite render target was unexpectedly recreated during fixed-size performance evidence"
            }
            check(beforeDispose.targetReuseCount > 0) {
                "Graphite render target reuse was not observed during performance evidence"
            }
            evidence["graphite.context.create_count"] = beforeDispose.contextCreateCount.toString()
            evidence["render_target.create_count"] = beforeDispose.targetCreateCount.toString()
            evidence["render_target.reuse_count"] = beforeDispose.targetReuseCount.toString()
            evidence["graphite.context_reuse"] = "PASS"
            evidence["render_target.reuse"] = "PASS"

            registration?.dispose()
            registration = null

            val afterDispose = backend.snapshot()
            check(afterDispose.providerDisposeCount == 1) {
                "Graphite provider dispose count is unexpected: ${afterDispose.providerDisposeCount}"
            }
            check(afterDispose.targetDisposeCount >= afterDispose.targetCreateCount) {
                "Graphite performance render target was not fully disposed"
            }
            evidence["provider.dispose_count"] = afterDispose.providerDisposeCount.toString()
            evidence["render_target.dispose_count"] = afterDispose.targetDisposeCount.toString()
            evidence["graphite.cleanup"] = "PASS"
        } else {
            evidence["native.path"] = "PASS"
        }

        evidence["result"] = "PLOTPANEL_PERFORMANCE_SAMPLE_CAPABLE"
        evidence["failure.stage"] = "none"
        writePerformanceEvidence(resultFile, evidence)

        completed.set(true)
        println("PLOTPANEL_PERFORMANCE_${mode.name}_RESULT PASS")
        evidence.forEach { (key, value) -> println("$key=$value") }
    } catch (t: Throwable) {
        evidence["result"] = "FAIL"
        evidence["failure.stage"] = evidence["failure.stage"] ?: "main"
        evidence["failure.type"] = t::class.qualifiedName ?: t::class.simpleName.orEmpty()
        evidence["failure.message"] = sanitizePerformance(t.message ?: t.toString())
        writePerformanceEvidence(resultFile, evidence)
        throw t
    } finally {
        registration?.dispose()
        if (previousProperty == null) {
            System.clearProperty(PERFORMANCE_RENDER_PATH_PROPERTY)
        } else {
            System.setProperty(PERFORMANCE_RENDER_PATH_PROPERTY, previousProperty)
        }
    }
}

private suspend fun waitForVisiblePlot(window: java.awt.Window) {
    val robot = Robot(window.graphicsConfiguration.device).apply { autoDelay = 5 }
    window.toFront()

    repeat(150) {
        delay(20)
        val sample = fingerprintWindow(window, robot)
        if (sample.nonWhite >= PERFORMANCE_READY_NON_WHITE) {
            return
        }
    }
    error("PlotPanel did not become visibly ready")
}

private suspend fun measureWheelLatency(
    window: java.awt.Window,
    figureModel: PlotFigureModel,
    rotation: Int
): Double {
    val robot = Robot(window.graphicsConfiguration.device).apply { autoDelay = 5 }
    preparePointerAtCenter(window, robot)
    val baselineSignature = specOverrideSignature(figureModel)
    val baselineFingerprint = fingerprintWindow(window, robot).hash
    val started = System.nanoTime()

    robot.mouseWheel(rotation)

    return waitForVisibleInteractionChange(
        window = window,
        robot = robot,
        figureModel = figureModel,
        previousSignature = baselineSignature,
        previousFingerprint = baselineFingerprint,
        started = started,
        interactionName = "wheel"
    )
}

private suspend fun measurePanLatency(
    window: java.awt.Window,
    figureModel: PlotFigureModel,
    direction: Int
): Double {
    val robot = Robot(window.graphicsConfiguration.device).apply { autoDelay = 5 }
    val center = preparePointerAtCenter(window, robot)
    val baselineSignature = specOverrideSignature(figureModel)
    val baselineFingerprint = fingerprintWindow(window, robot).hash
    val started = System.nanoTime()

    val targetX = center.first + 55 * direction
    val targetY = center.second + 30 * direction
    robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
    try {
        robot.mouseMove(targetX, targetY)
    } finally {
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
    }

    return waitForVisibleInteractionChange(
        window = window,
        robot = robot,
        figureModel = figureModel,
        previousSignature = baselineSignature,
        previousFingerprint = baselineFingerprint,
        started = started,
        interactionName = "pan"
    )
}

private suspend fun waitForVisibleInteractionChange(
    window: java.awt.Window,
    robot: Robot,
    figureModel: PlotFigureModel,
    previousSignature: String,
    previousFingerprint: Long,
    started: Long,
    interactionName: String
): Double {
    val deadline = System.nanoTime() + PERFORMANCE_INTERACTION_TIMEOUT_MS * 1_000_000

    while (System.nanoTime() < deadline) {
        val stateChanged = specOverrideSignature(figureModel) != previousSignature
        if (stateChanged) {
            val fingerprint = fingerprintWindow(window, robot)
            if (
                fingerprint.hash != previousFingerprint &&
                fingerprint.nonWhite >= PERFORMANCE_VISIBLE_CHANGE_NON_WHITE
            ) {
                return elapsedMs(started)
            }
        }
        delay(10)
    }

    error("PlotPanel $interactionName interaction did not produce state and visible changes")
}

private suspend fun preparePointerAtCenter(
    window: java.awt.Window,
    robot: Robot
): Pair<Int, Int> {
    window.toFront()
    val location = window.locationOnScreen
    val size = window.size
    val x = location.x + size.width / 2
    val y = location.y + size.height / 2
    robot.mouseMove(x, y)
    delay(30)
    return x to y
}

private fun fingerprintWindow(
    window: java.awt.Window,
    robot: Robot
): WindowFingerprint {
    val location = window.locationOnScreen
    val size = window.size
    check(size.width > 0 && size.height > 0) {
        "Compose window has invalid size: ${size.width}x${size.height}"
    }

    val image: BufferedImage = robot.createScreenCapture(
        Rectangle(location.x, location.y, size.width, size.height)
    )

    var hash = 1125899906842597L
    var nonWhite = 0
    for (y in 0 until image.height step 8) {
        for (x in 0 until image.width step 8) {
            val color = image.getRGB(x, y)
            val r = (color ushr 16) and 0xFF
            val g = (color ushr 8) and 0xFF
            val b = color and 0xFF
            if (r < 235 || g < 235 || b < 235) nonWhite++
            hash = 31L * hash + (color and 0x00FFFFFF)
        }
    }
    return WindowFingerprint(hash = hash, nonWhite = nonWhite)
}

private fun percentile(values: List<Double>, quantile: Double): Double {
    require(values.isNotEmpty()) { "No performance samples were collected" }
    val sorted = values.sorted()
    val index = (ceil(quantile * sorted.size).toInt() - 1).coerceIn(0, sorted.lastIndex)
    return sorted[index]
}

private fun elapsedMs(started: Long): Double =
    (System.nanoTime() - started) / 1_000_000.0

private fun formatMs(value: Double): String =
    String.format(Locale.US, "%.3f", value)

private fun writePerformanceEvidence(file: File, evidence: Map<String, String>) {
    file.parentFile?.mkdirs()
    file.writeText(
        evidence.entries.joinToString(separator = System.lineSeparator(), postfix = System.lineSeparator()) {
            "${it.key}=${it.value}"
        }
    )
}

private fun sanitizePerformance(value: String): String =
    value.replace('\r', ' ').replace('\n', ' ').trim().take(500)
