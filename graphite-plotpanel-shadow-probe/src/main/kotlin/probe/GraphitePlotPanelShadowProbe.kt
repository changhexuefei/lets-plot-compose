package probe

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.delay
import org.jetbrains.letsPlot.Figure
import org.jetbrains.letsPlot.commons.geometry.DoubleVector
import org.jetbrains.letsPlot.commons.registration.Registration
import org.jetbrains.letsPlot.compose.PlotFigureModel
import org.jetbrains.letsPlot.compose.PlotPanel
import org.jetbrains.letsPlot.compose.canvas.SkiaContext2d
import org.jetbrains.letsPlot.compose.canvas.SkiaFontManager
import org.jetbrains.letsPlot.core.interact.InteractionSpec
import org.jetbrains.letsPlot.geom.geomPoint
import org.jetbrains.letsPlot.letsPlot
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Surface
import org.jetbrains.skia.gpu.graphite.BackendTexture
import org.jetbrains.skia.gpu.graphite.GraphiteContext
import org.jetbrains.skia.gpu.graphite.VulkanFormat
import org.jetbrains.skia.gpu.graphite.VulkanImageUsageFlags
import org.jetbrains.skia.gpu.graphite.VulkanTextureInfo
import org.jetbrains.skia.gpu.graphite.wrapBackendTexture
import org.jetbrains.skiko.ExperimentalSkikoApi
import org.jetbrains.skiko.toImage
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil
import org.lwjgl.vulkan.VK10.*
import org.lwjgl.vulkan.VK11.VK_API_VERSION_1_1
import org.lwjgl.vulkan.VkApplicationInfo
import org.lwjgl.vulkan.VkBufferCreateInfo
import org.lwjgl.vulkan.VkBufferImageCopy
import org.lwjgl.vulkan.VkCommandBuffer
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo
import org.lwjgl.vulkan.VkCommandBufferBeginInfo
import org.lwjgl.vulkan.VkCommandPoolCreateInfo
import org.lwjgl.vulkan.VkDevice
import org.lwjgl.vulkan.VkDeviceCreateInfo
import org.lwjgl.vulkan.VkDeviceQueueCreateInfo
import org.lwjgl.vulkan.VkImageCreateInfo
import org.lwjgl.vulkan.VkImageMemoryBarrier
import org.lwjgl.vulkan.VkInstance
import org.lwjgl.vulkan.VkInstanceCreateInfo
import org.lwjgl.vulkan.VkMemoryAllocateInfo
import org.lwjgl.vulkan.VkMemoryRequirements
import org.lwjgl.vulkan.VkPhysicalDevice
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties
import org.lwjgl.vulkan.VkPhysicalDeviceProperties
import org.lwjgl.vulkan.VkQueue
import org.lwjgl.vulkan.VkQueueFamilyProperties
import org.lwjgl.vulkan.VkSubmitInfo
import java.awt.Rectangle
import java.awt.Robot
import java.awt.event.InputEvent
import java.awt.image.BufferedImage
import java.io.File
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import kotlin.system.exitProcess

private const val DESKTOP_RENDER_PATH_PROPERTY = "letsplot.compose.desktop.renderPath"
private const val WINDOW_WIDTH = 800
private const val WINDOW_HEIGHT = 550
private const val RESIZED_WINDOW_WIDTH = 960
private const val RESIZED_WINDOW_HEIGHT = 640
private const val STRESS_ROUNDS_BEFORE_RESIZE = 4
private const val STRESS_ROUNDS_AFTER_RESIZE = 4
private const val WHITE = -1

fun main() {
    val outputDir = File(
        System.getenv("GRAPHITE_SHADOW_OUTPUT_DIR")
            ?: "graphite-plotpanel-shadow-probe/build/probe"
    ).apply { mkdirs() }

    val resultFile = outputDir.resolve("graphite-plotpanel-shadow.txt")
    val initialScreenshot = outputDir.resolve("plotpanel-graphite-initial.png")
    val wheelZoomScreenshot = outputDir.resolve("plotpanel-graphite-wheel-zoom.png")
    val dragPanScreenshot = outputDir.resolve("plotpanel-graphite-drag-pan.png")
    val resizedScreenshot = outputDir.resolve("plotpanel-graphite-resized.png")

    val evidence = linkedMapOf(
        "schema" to "1",
        "probe.type" to "compose-plotpanel-interaction-stress-regression",
        "compose.version" to "1.13.0-alpha01",
        "skiko.version" to (System.getenv("GRAPHITE_SKIKO_VERSION") ?: "unknown"),
        "lwjgl.version" to (System.getenv("GRAPHITE_LWJGL_VERSION") ?: "unknown"),
        "production.renderer.default" to "NATIVE_CANVAS",
        "feature_flag.value" to "graphite-offscreen",
        "provider.binding" to "INTERNAL_REGISTRY_REFLECTION",
        "provider.packaging" to "PROBE_ONLY",
        "renderer.adoption" to "SHADOW_OPT_IN_ONLY",
        "interaction.input" to "AWT_ROBOT_REAL_WINDOW",
        "public.api.change" to "NONE"
    )

    var registration: Registration? = null
    var previousProperty: String? = null
    val backend = PersistentGraphiteShadowBackend(evidence)
    val completed = AtomicBoolean(false)

    Thread.setDefaultUncaughtExceptionHandler { _, throwable ->
        if (completed.compareAndSet(false, true)) {
            evidence["result"] = "FAIL"
            evidence["failure.stage"] = "uncaught"
            evidence["failure.type"] = throwable::class.qualifiedName ?: throwable::class.simpleName.orEmpty()
            evidence["failure.message"] = sanitize(throwable.message ?: throwable.toString())
            writeEvidence(resultFile, evidence)
        }
        exitProcess(42)
    }

    Thread({
        Thread.sleep(90_000)
        if (completed.compareAndSet(false, true)) {
            evidence["result"] = "FAIL"
            evidence["failure.stage"] = "watchdog"
            evidence["failure.type"] = "TIMEOUT"
            evidence["failure.message"] = "PlotPanel interaction stress regression probe did not complete within 90 seconds"
            writeEvidence(resultFile, evidence)
            exitProcess(43)
        }
    }, "plotpanel-graphite-shadow-watchdog").apply {
        isDaemon = true
        start()
    }

    try {
        check(System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
            "PlotPanel Graphite shadow probe is intentionally Windows-only"
        }

        preloadDirectVulkan(evidence)

        val plotPanelLocation = Class.forName("org.jetbrains.letsPlot.compose.PlotPanelKt")
            .protectionDomain
            ?.codeSource
            ?.location
            ?.toString()
            .orEmpty()
        check(plotPanelLocation.contains("lets-plot-compose-desktop", ignoreCase = true)) {
            "PlotPanel was not loaded from the published desktop artifact: $plotPanelLocation"
        }
        evidence["compose.plotpanel.production_artifact"] = "PASS"

        registration = installInternalRenderer(backend, evidence)
        evidence["provider.install"] = "PASS"

        previousProperty = System.getProperty(DESKTOP_RENDER_PATH_PROPERTY)
        System.setProperty(DESKTOP_RENDER_PATH_PROPERTY, "graphite-offscreen")

        val figure = createFigure()
        val figureModel = PlotFigureModel().apply {
            setDefaultInteractions(
                listOf(
                    InteractionSpec(InteractionSpec.Name.WHEEL_ZOOM),
                    InteractionSpec(InteractionSpec.Name.DRAG_PAN)
                )
            )
        }
        evidence["interaction.default.wheel_zoom"] = "ENABLED"
        evidence["interaction.default.drag_pan"] = "ENABLED"

        var initialBaseline: BackendSnapshot? = null
        var wheelZoomObserved = false
        var dragPanObserved = false
        var resizedObserved = false

        application(exitProcessOnExit = false) {
            val state = rememberWindowState(width = WINDOW_WIDTH.dp, height = WINDOW_HEIGHT.dp)

            Window(
                onCloseRequest = ::exitApplication,
                state = state,
                title = "Lets-Plot Graphite PlotPanel Shadow Probe",
                undecorated = true,
                resizable = true
            ) {
                val composeWindow = window

                PlotPanel(
                    figure = figure,
                    figureModel = figureModel,
                    modifier = Modifier.fillMaxSize(),
                    computationMessagesHandler = {}
                )

                LaunchedEffect(Unit) {
                    val stable = waitForStableFrames(backend)
                    initialBaseline = stable
                    evidence["compose.window"] = "STARTED"
                    evidence["compose.plotpanel"] = "PASS"
                    evidence["compose.plotpanel.render_path"] = "OFFSCREEN_COMPOSITE"
                    evidence["compose.plotpanel.initial_size"] = "${stable.width}x${stable.height}"

                    delay(350)
                    captureWindow(composeWindow, initialScreenshot)
                    assertScreenshotHasPlot(initialScreenshot)
                    evidence["compose.plotpanel.initial_capture"] = "PASS"

                    val initialOverrideSignature = specOverrideSignature(figureModel)
                    val wheelBaseline = backend.snapshot()

                    performWheelZoom(composeWindow)
                    evidence["interaction.wheel_zoom.robot"] = "PASS"

                    val wheelSnapshot = waitForInteractionStateChange(
                        backend = backend,
                        figureModel = figureModel,
                        previousSignature = initialOverrideSignature,
                        baselineFrames = wheelBaseline.successfulFrames,
                        interactionName = "wheel zoom"
                    )
                    wheelZoomObserved = true
                    evidence["interaction.wheel_zoom.state_change"] = "PASS"
                    evidence["interaction.wheel_zoom.repaint"] = "PASS"
                    evidence["interaction.wheel_zoom.override_count"] =
                        figureModel.specOverrideState.value.specOverrides.size.toString()

                    delay(250)
                    captureWindow(composeWindow, wheelZoomScreenshot)
                    assertScreenshotHasPlot(wheelZoomScreenshot)
                    evidence["interaction.wheel_zoom.capture"] = "PASS"

                    val wheelOverrideSignature = specOverrideSignature(figureModel)
                    val panBaseline = wheelSnapshot

                    performDragPan(composeWindow)
                    evidence["interaction.drag_pan.robot"] = "PASS"

                    val panSnapshot = waitForInteractionStateChange(
                        backend = backend,
                        figureModel = figureModel,
                        previousSignature = wheelOverrideSignature,
                        baselineFrames = panBaseline.successfulFrames,
                        interactionName = "drag pan"
                    )
                    dragPanObserved = true
                    evidence["interaction.drag_pan.state_change"] = "PASS"
                    evidence["interaction.drag_pan.repaint"] = "PASS"
                    evidence["interaction.drag_pan.override_count"] =
                        figureModel.specOverrideState.value.specOverrides.size.toString()

                    delay(250)
                    captureWindow(composeWindow, dragPanScreenshot)
                    assertScreenshotHasPlot(dragPanScreenshot)
                    evidence["interaction.drag_pan.capture"] = "PASS"

                    val preResizeStress = runInteractionStress(
                        window = composeWindow,
                        backend = backend,
                        figureModel = figureModel,
                        rounds = STRESS_ROUNDS_BEFORE_RESIZE - 1,
                        phase = "pre-resize"
                    )
                    check(preResizeStress.contextCreateCount == 1) {
                        "Graphite context was recreated during pre-resize interaction stress"
                    }
                    check(preResizeStress.targetCreateCount == panSnapshot.targetCreateCount) {
                        "Persistent render target was recreated without resize during pre-resize stress"
                    }
                    check(preResizeStress.targetResizeCount == panSnapshot.targetResizeCount) {
                        "Unexpected render-target resize was observed during pre-resize stress"
                    }
                    check(preResizeStress.targetReuseCount > panSnapshot.targetReuseCount) {
                        "Persistent render target was not reused during pre-resize stress"
                    }
                    evidence["stress.pre_resize.rounds"] = STRESS_ROUNDS_BEFORE_RESIZE.toString()
                    evidence["stress.pre_resize"] = "PASS"

                    composeWindow.setSize(RESIZED_WINDOW_WIDTH, RESIZED_WINDOW_HEIGHT)

                    val resized = waitForResizeFrames(backend, preResizeStress)
                    resizedObserved = true
                    evidence["compose.plotpanel.resize"] = "PASS"
                    evidence["compose.plotpanel.resized_size"] = "${resized.width}x${resized.height}"

                    delay(350)
                    captureWindow(composeWindow, resizedScreenshot)
                    assertScreenshotHasPlot(resizedScreenshot)
                    evidence["compose.plotpanel.resized_capture"] = "PASS"

                    check(resized.contextCreateCount == 1) {
                        "Graphite context was recreated across PlotPanel resize: ${resized.contextCreateCount}"
                    }
                    check(resized.targetCreateCount > preResizeStress.targetCreateCount) {
                        "Resize did not recreate the persistent render target"
                    }
                    check(resized.targetResizeCount > preResizeStress.targetResizeCount) {
                        "Resize lifecycle was not observed"
                    }
                    check(resized.layoutReuseObserved) {
                        "Persistent render target layout reuse was not observed"
                    }

                    evidence["graphite.context.create_count"] = resized.contextCreateCount.toString()
                    evidence["render_target.reuse"] = "PASS"
                    evidence["render_target.resize"] = "PASS"
                    evidence["image.layout.reuse_transition"] = "PASS"
                    check(figureModel.specOverrideState.value.specOverrides.isNotEmpty()) {
                        "Plot interaction overrides were lost after resize"
                    }
                    evidence["interaction.state.after_resize"] = "PRESERVED"
                    evidence["compose.recomposition.after_resize"] = "PASS"

                    val postResizeStress = runInteractionStress(
                        window = composeWindow,
                        backend = backend,
                        figureModel = figureModel,
                        rounds = STRESS_ROUNDS_AFTER_RESIZE,
                        phase = "post-resize"
                    )
                    check(postResizeStress.contextCreateCount == 1) {
                        "Graphite context was recreated during post-resize interaction stress"
                    }
                    check(postResizeStress.targetCreateCount == resized.targetCreateCount) {
                        "Persistent render target was recreated without resize during post-resize stress"
                    }
                    check(postResizeStress.targetResizeCount == resized.targetResizeCount) {
                        "Unexpected render-target resize was observed during post-resize stress"
                    }
                    check(postResizeStress.targetReuseCount > resized.targetReuseCount) {
                        "Persistent render target was not reused during post-resize stress"
                    }
                    check(figureModel.specOverrideState.value.specOverrides.isNotEmpty()) {
                        "Plot interaction overrides were lost during post-resize stress"
                    }

                    val expectedStressInteractions =
                        (STRESS_ROUNDS_BEFORE_RESIZE + STRESS_ROUNDS_AFTER_RESIZE) * 2
                    evidence["stress.post_resize.rounds"] = STRESS_ROUNDS_AFTER_RESIZE.toString()
                    evidence["stress.post_resize"] = "PASS"
                    evidence["stress.total.rounds"] =
                        (STRESS_ROUNDS_BEFORE_RESIZE + STRESS_ROUNDS_AFTER_RESIZE).toString()
                    evidence["stress.total.interactions"] = expectedStressInteractions.toString()
                    evidence["stress.state_changes"] = expectedStressInteractions.toString()
                    evidence["stress.repaints"] = expectedStressInteractions.toString()
                    evidence["stress.context_reuse"] = "PASS"
                    evidence["stress.render_target.reuse"] = "PASS"
                    evidence["stress.render_target.resize_boundary"] = "PASS"

                    exitApplication()
                }
            }
        }

        check(initialBaseline != null) { "Initial PlotPanel baseline was not captured" }
        check(wheelZoomObserved) { "Wheel zoom interaction phase did not complete" }
        check(dragPanObserved) { "Drag pan interaction phase did not complete" }
        check(resizedObserved) { "PlotPanel resize phase did not complete" }

        figureModel.dispose()
        evidence["figure_model.dispose"] = "PASS"

        registration.dispose()
        registration = null

        val final = backend.snapshot()
        check(final.providerDisposeCount == 1) {
            "Provider dispose count is unexpected: ${final.providerDisposeCount}"
        }
        check(final.contextCreateCount == 1) {
            "Graphite context create count is unexpected: ${final.contextCreateCount}"
        }
        check(final.targetDisposeCount >= final.targetCreateCount) {
            "Not all persistent render targets were disposed: created=${final.targetCreateCount} disposed=${final.targetDisposeCount}"
        }

        evidence["provider.dispose_count"] = final.providerDisposeCount.toString()
        evidence["graphite.context.create_count"] = final.contextCreateCount.toString()
        evidence["render_target.create_count"] = final.targetCreateCount.toString()
        evidence["render_target.reuse_count"] = final.targetReuseCount.toString()
        evidence["render_target.resize_count"] = final.targetResizeCount.toString()
        evidence["render_target.dispose_count"] = final.targetDisposeCount.toString()
        evidence["provider.registration.dispose"] = "PASS"
        evidence["persistent.context.lifecycle"] = "PASS"
        evidence["persistent.render_target.lifecycle"] = "PASS"
        evidence["compose.plotpanel.dispose"] = "PASS"
        evidence["interaction.regression"] = "PASS"
        evidence["interaction.stress"] = "PASS"
        evidence["result"] = "COMPOSE_PLOTPANEL_INTERACTION_STRESS_REGRESSION_CAPABLE"
        evidence["failure.stage"] = "none"
        writeEvidence(resultFile, evidence)

        completed.set(true)
        println("COMPOSE_PLOTPANEL_INTERACTION_STRESS_REGRESSION_RESULT PASS")
        evidence.forEach { (key, value) -> println("$key=$value") }
    } catch (t: Throwable) {
        evidence["result"] = "FAIL"
        evidence["failure.stage"] = evidence["failure.stage"] ?: "main"
        evidence["failure.type"] = t::class.qualifiedName ?: t::class.simpleName.orEmpty()
        evidence["failure.message"] = sanitize(t.message ?: t.toString())
        writeEvidence(resultFile, evidence)
        throw t
    } finally {
        if (previousProperty == null) {
            System.clearProperty(DESKTOP_RENDER_PATH_PROPERTY)
        } else {
            System.setProperty(DESKTOP_RENDER_PATH_PROPERTY, previousProperty)
        }

        registration?.dispose()
    }
}

internal fun specOverrideSignature(figureModel: PlotFigureModel): String =
    figureModel.specOverrideState.value.specOverrides.toString()

private suspend fun waitForInteractionStateChange(
    backend: PersistentGraphiteShadowBackend,
    figureModel: PlotFigureModel,
    previousSignature: String,
    baselineFrames: Int,
    interactionName: String
): BackendSnapshot {
    repeat(160) {
        val snapshot = backend.snapshot()
        val currentSignature = specOverrideSignature(figureModel)
        if (
            currentSignature != previousSignature &&
            figureModel.specOverrideState.value.specOverrides.isNotEmpty() &&
            snapshot.successfulFrames > baselineFrames
        ) {
            return snapshot
        }
        delay(100)
    }
    error("PlotPanel $interactionName did not mutate spec override state and repaint")
}

private suspend fun performWheelZoom(
    window: java.awt.Window,
    rotation: Int = -1,
    notches: Int = 3
) {
    val robot = Robot(window.graphicsConfiguration.device).apply { autoDelay = 60 }
    window.toFront()
    delay(200)

    val location = window.locationOnScreen
    val size = window.size
    val x = location.x + size.width / 2
    val y = location.y + size.height / 2

    robot.mouseMove(x, y)
    repeat(notches) {
        robot.mouseWheel(rotation)
        delay(100)
    }
}

private suspend fun performDragPan(
    window: java.awt.Window,
    deltaX: Int = 120,
    deltaY: Int = 70
) {
    val robot = Robot(window.graphicsConfiguration.device).apply { autoDelay = 40 }
    window.toFront()
    delay(150)

    val location = window.locationOnScreen
    val size = window.size
    val startX = location.x + size.width / 2
    val startY = location.y + size.height / 2
    val maxDeltaX = maxOf(1, size.width / 5)
    val maxDeltaY = maxOf(1, size.height / 7)
    val endX = startX + deltaX.coerceIn(-maxDeltaX, maxDeltaX)
    val endY = startY + deltaY.coerceIn(-maxDeltaY, maxDeltaY)

    robot.mouseMove(startX, startY)
    robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
    try {
        val steps = 8
        for (step in 1..steps) {
            val x = startX + (endX - startX) * step / steps
            val y = startY + (endY - startY) * step / steps
            robot.mouseMove(x, y)
            delay(60)
        }
    } finally {
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
    }
    delay(150)
}

private suspend fun runInteractionStress(
    window: java.awt.Window,
    backend: PersistentGraphiteShadowBackend,
    figureModel: PlotFigureModel,
    rounds: Int,
    phase: String
): BackendSnapshot {
    var latest = backend.snapshot()

    repeat(rounds) { index ->
        val wheelSignature = specOverrideSignature(figureModel)
        val wheelBaseline = latest
        val wheelRotation = if (index % 2 == 0) 1 else -1
        performWheelZoom(
            window = window,
            rotation = wheelRotation,
            notches = 2
        )
        latest = waitForInteractionStateChange(
            backend = backend,
            figureModel = figureModel,
            previousSignature = wheelSignature,
            baselineFrames = wheelBaseline.successfulFrames,
            interactionName = "$phase wheel zoom #${index + 1}"
        )

        val panSignature = specOverrideSignature(figureModel)
        val panBaseline = latest
        val direction = if (index % 2 == 0) -1 else 1
        performDragPan(
            window = window,
            deltaX = 70 * direction,
            deltaY = 40 * direction
        )
        latest = waitForInteractionStateChange(
            backend = backend,
            figureModel = figureModel,
            previousSignature = panSignature,
            baselineFrames = panBaseline.successfulFrames,
            interactionName = "$phase drag pan #${index + 1}"
        )
    }

    return latest
}

private suspend fun waitForStableFrames(backend: PersistentGraphiteShadowBackend): BackendSnapshot {
    repeat(160) {
        val current = backend.snapshot()
        if (
            current.successfulFrames >= 2 &&
            current.targetReuseCount >= 1 &&
            current.width > 0 &&
            current.height > 0
        ) {
            return current
        }
        delay(100)
    }
    error("PlotPanel did not produce two reusable Graphite frames")
}

private suspend fun waitForResizeFrames(
    backend: PersistentGraphiteShadowBackend,
    baseline: BackendSnapshot
): BackendSnapshot {
    repeat(160) {
        val current = backend.snapshot()
        if (
            current.width > 0 &&
            current.height > 0 &&
            (current.width != baseline.width || current.height != baseline.height) &&
            current.successfulFrames > baseline.successfulFrames &&
            current.targetCreateCount > baseline.targetCreateCount &&
            current.targetResizeCount > baseline.targetResizeCount
        ) {
            return current
        }
        delay(100)
    }
    error("PlotPanel Graphite target did not recover after window resize")
}

internal fun createFigure(): Figure {
    val values = listOf(-2.0, -1.0, 0.0, 1.0, 2.0)
    return letsPlot(
        mapOf(
            "x" to values,
            "y" to values,
            "group" to listOf("A", "A", "CENTER", "B", "B")
        )
    ) +
        geomPoint(size = 14.0, alpha = 0.9) {
            x = "x"
            y = "y"
            color = "group"
        }
}

private fun captureWindow(window: java.awt.Window, file: File) {
    window.toFront()
    val location = window.locationOnScreen
    val size = window.size
    check(size.width > 0 && size.height > 0) {
        "Compose window has invalid size: ${size.width}x${size.height}"
    }

    val robot = Robot(window.graphicsConfiguration.device)
    val image = robot.createScreenCapture(Rectangle(location.x, location.y, size.width, size.height))
    check(ImageIO.write(image, "png", file)) { "Failed to write screenshot: ${file.absolutePath}" }
}

private fun assertScreenshotHasPlot(file: File) {
    val image = ImageIO.read(file) ?: error("Unable to decode screenshot: ${file.absolutePath}")
    val sampled = linkedSetOf<Int>()
    var nonWhite = 0
    var dark = 0

    for (y in 0 until image.height step 4) {
        for (x in 0 until image.width step 4) {
            val color = image.getRGB(x, y)
            val r = (color ushr 16) and 0xFF
            val g = (color ushr 8) and 0xFF
            val b = color and 0xFF
            sampled += color and 0x00FFFFFF
            if (r < 235 || g < 235 || b < 235) nonWhite++
            if (r < 140 && g < 140 && b < 140) dark++
        }
    }

    // Interaction views can legitimately move some marks outside the viewport.
    // Keep the blank-screen guard strong enough to require plot ink, axes/text and
    // multiple colors without assuming the initial number of visible marks.
    check(nonWhite >= 180) { "PlotPanel screenshot is unexpectedly blank: nonWhite=$nonWhite" }
    check(dark >= 20) { "PlotPanel screenshot has too little axes/text ink: dark=$dark" }
    check(sampled.size >= 8) { "PlotPanel screenshot has too few sampled colors: ${sampled.size}" }
}

private fun writeEvidence(file: File, values: Map<String, String>) {
    file.parentFile.mkdirs()
    file.writeText(
        values.entries.joinToString(separator = "\n", postfix = "\n") { (key, value) ->
            "$key=$value"
        }
    )
}

private fun sanitize(value: String): String =
    value.replace('\n', ' ').replace('\r', ' ').take(1000)
