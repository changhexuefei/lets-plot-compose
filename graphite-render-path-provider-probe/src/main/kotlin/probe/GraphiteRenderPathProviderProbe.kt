package probe

import org.jetbrains.letsPlot.Figure
import org.jetbrains.letsPlot.commons.geometry.DoubleVector
import org.jetbrains.letsPlot.commons.registration.Registration
import org.jetbrains.letsPlot.compose.DESKTOP_RENDER_PATH_PROPERTY
import org.jetbrains.letsPlot.compose.DesktopOffscreenRenderer
import org.jetbrains.letsPlot.compose.DesktopOffscreenRendererRegistry
import org.jetbrains.letsPlot.compose.DesktopRenderPath
import org.jetbrains.letsPlot.compose.paintDesktopPlot
import org.jetbrains.letsPlot.compose.paintOnSkiaCanvas
import org.jetbrains.letsPlot.compose.resolveDesktopRenderPath
import org.jetbrains.letsPlot.compose.canvas.SkiaCanvasPeer
import org.jetbrains.letsPlot.compose.canvas.SkiaContext2d
import org.jetbrains.letsPlot.compose.canvas.SkiaFontManager
import org.jetbrains.letsPlot.core.spec.config.PlotConfig
import org.jetbrains.letsPlot.core.util.MonolithicCommon.processRawSpecs
import org.jetbrains.letsPlot.core.util.sizing.SizingPolicy.Companion.fitContainerSize
import org.jetbrains.letsPlot.geom.geomPoint
import org.jetbrains.letsPlot.intern.toSpec
import org.jetbrains.letsPlot.letsPlot
import org.jetbrains.letsPlot.raster.view.PlotCanvasDrawable
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Surface
import org.jetbrains.skiko.toImage
import org.jetbrains.skia.gpu.graphite.BackendTexture
import org.jetbrains.skia.gpu.graphite.GraphiteContext
import org.jetbrains.skia.gpu.graphite.VulkanFormat
import org.jetbrains.skia.gpu.graphite.VulkanImageUsageFlags
import org.jetbrains.skia.gpu.graphite.VulkanTextureInfo
import org.jetbrains.skia.gpu.graphite.wrapBackendTexture
import org.jetbrains.skiko.ExperimentalSkikoApi
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
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs

private const val WIDTH = 800
private const val HEIGHT = 550
private const val LOGICAL_PLOT_WIDTH = 560
private const val LOGICAL_PLOT_HEIGHT = 380
private const val BRIDGE_DENSITY = 1.25
private const val PLOT_X = 24.0
private const val PLOT_Y = 20.0
private const val WHITE = -1
private const val MAX_PIXEL_DIFFERENCE_RATIO = 0.10
private const val PIXEL_DELTA_THRESHOLD = 48

private data class VulkanObjects(
    val instance: VkInstance,
    val physicalDevice: VkPhysicalDevice,
    val device: VkDevice,
    val queue: VkQueue,
    val queueFamilyIndex: Int,
    val deviceName: String
)

private data class VulkanImage(
    val image: Long,
    val memory: Long
)

private data class PreparedPlot(
    val drawable: PlotCanvasDrawable,
    val registration: Registration
)

private class GraphiteBackendProvider(
    private val evidence: MutableMap<String, String>,
    private val graphitePng: File
) : DesktopOffscreenRenderer {
    private var vulkan: VulkanObjects? = null
    private var graphiteContext: GraphiteContext? = null
    private var disposed = false
    private var paintCount = 0
    private var contextCreateCount = 0
    private var contextReuseCount = 0
    private var disposeCount = 0

    @OptIn(ExperimentalSkikoApi::class)
    override fun paint(
        targetCanvas: org.jetbrains.skia.Canvas,
        width: Int,
        height: Int,
        density: Double,
        plotPosition: DoubleVector,
        paint: (SkiaContext2d) -> Unit
    ) {
        check(!disposed) { "Graphite provider is already disposed" }
        check(width == WIDTH && height == HEIGHT) {
            "Unexpected provider target size: ${width}x${height}"
        }

        paintCount++
        evidence["provider.invoked"] = "PASS"
        evidence["provider.paint_count"] = paintCount.toString()
        evidence["provider.width"] = width.toString()
        evidence["provider.height"] = height.toString()
        evidence["provider.density"] = density.toString()
        evidence["provider.plot_position"] = "${plotPosition.x},${plotPosition.y}"

        val (vk, context) = ensurePersistentContext()
        if (paintCount > 1) {
            contextReuseCount++
            evidence["graphite.context.reuse"] = "PASS"
            evidence["graphite.context.reuse_count"] = contextReuseCount.toString()
        }

        val target = createRenderImage(vk).also {
            evidence["vulkan.image"] = "CREATED"
            evidence["vulkan.image.memory"] = "BOUND"
            evidence["render_target.lifecycle"] = "PER_PAINT"
        }

        val graphitePixels: IntArray
        try {
            context.makeRecorder().use { recorder ->
                BackendTexture.makeVulkan(
                    width = width,
                    height = height,
                    textureInfo = VulkanTextureInfo(
                        format = VulkanFormat(VK_FORMAT_B8G8R8A8_UNORM),
                        imageUsageFlags =
                            VulkanImageUsageFlags.COLOR_ATTACHMENT or
                                VulkanImageUsageFlags.INPUT_ATTACHMENT or
                                VulkanImageUsageFlags.TRANSFER_SRC or
                                VulkanImageUsageFlags.TRANSFER_DST
                    ),
                    imageLayout = VK_IMAGE_LAYOUT_UNDEFINED,
                    queueFamilyIndex = vk.queueFamilyIndex,
                    imagePtr = target.image
                ).use { backendTexture ->
                    val surface = Surface.wrapBackendTexture(
                        recorder = recorder,
                        backendTexture = backendTexture,
                        colorSpace = null
                    ) ?: error("Graphite Surface.wrapBackendTexture returned null")

                    surface.use {
                        evidence["graphite.surface"] = "CREATED"
                        surface.canvas.clear(WHITE)

                        paintOnSkiaCanvas(
                            canvas = surface.canvas,
                            density = density,
                            plotPosition = plotPosition,
                            paint = paint
                        )
                        evidence["graphite.provider.paint"] = "PASS"

                        recorder.snap().use { recording ->
                            context.insertRecording(recording)
                            context.submit(syncCpu = true)
                        }
                        evidence["graphite.submit"] = "PASS"

                        surface.makeImageSnapshot().use { snapshot ->
                            check(snapshot.width == width && snapshot.height == height)
                            evidence["graphite.snapshot"] = "PASS"
                        }

                        graphitePixels = readBackGraphiteImage(vk, target.image)
                        if (paintCount == 1) {
                            savePixels(graphitePixels, graphitePng)
                        }
                        evidence["graphite.readback"] = "PASS"
                    }
                }
            }
        } finally {
            vkDeviceWaitIdle(vk.device)
            vkDestroyImage(vk.device, target.image, null)
            vkFreeMemory(vk.device, target.memory, null)
        }

        val buffered = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        buffered.setRGB(0, 0, width, height, graphitePixels, 0, width)
        buffered.toImage().use { image ->
            targetCanvas.drawImage(image, 0f, 0f)
        }

        evidence["provider.composite_to_target"] = "PASS"
        evidence["provider.lifecycle"] = "PERSISTENT_CONTEXT"
        evidence["graphite.context.create_count"] = contextCreateCount.toString()
    }

    @OptIn(ExperimentalSkikoApi::class)
    private fun ensurePersistentContext(): Pair<VulkanObjects, GraphiteContext> {
        val existingVk = vulkan
        val existingContext = graphiteContext
        if (existingVk != null && existingContext != null) {
            return existingVk to existingContext
        }

        val vk = createVulkanObjects()
        evidence["vulkan.device.name"] = vk.deviceName
        try {
            val context = GraphiteContext.makeVulkan(
                instancePtr = vk.instance.address(),
                physicalDevicePtr = vk.physicalDevice.address(),
                devicePtr = vk.device.address(),
                queuePtr = vk.queue.address(),
                graphicsQueueIndex = vk.queueFamilyIndex,
                maxApiVersion = VK_API_VERSION_1_1
            )
            vulkan = vk
            graphiteContext = context
            contextCreateCount++
            evidence["graphite.context"] = "CREATED"
            evidence["graphite.context.create_count"] = contextCreateCount.toString()
            evidence["vulkan.device.lifecycle"] = "PERSISTENT_CONTEXT"
            return vk to context
        } catch (t: Throwable) {
            vkDestroyDevice(vk.device, null)
            vkDestroyInstance(vk.instance, null)
            throw t
        }
    }

    override fun dispose() {
        if (disposed) return
        disposed = true
        disposeCount++
        evidence["provider.dispose_count"] = disposeCount.toString()

        val vk = vulkan
        try {
            if (vk != null) {
                vkDeviceWaitIdle(vk.device)
            }
            graphiteContext?.close()
            graphiteContext = null
            evidence["graphite.context.dispose"] = "PASS"
        } finally {
            if (vk != null) {
                vkDestroyDevice(vk.device, null)
                vkDestroyInstance(vk.instance, null)
                evidence["vulkan.device.dispose"] = "PASS"
            }
            vulkan = null
        }
    }
}

@OptIn(ExperimentalSkikoApi::class)
fun main() {
    val outputDir = File(
        System.getenv("GRAPHITE_PROBE_OUTPUT_DIR")
            ?: "graphite-render-path-provider-probe/build/probe"
    ).apply { mkdirs() }
    val resultFile = outputDir.resolve("graphite-render-path-provider.txt")
    val directPng = outputDir.resolve("direct-native-canvas.png")
    val graphitePng = outputDir.resolve("graphite-provider-offscreen.png")
    val compositedPng = outputDir.resolve("graphite-provider-composited.png")
    val secondFramePng = outputDir.resolve("graphite-provider-second-frame.png")

    var stage = "startup"
    var prepared: PreparedPlot? = null
    val evidence = linkedMapOf(
        "schema" to "1",
        "probe.type" to "graphite-render-path-provider",
        "feature_flag.property" to DESKTOP_RENDER_PATH_PROPERTY,
        "feature_flag.value" to "graphite-offscreen",
        "requested.path" to "OFFSCREEN_COMPOSITE",
        "production.renderer.default" to "NATIVE_CANVAS",
        "provider.binding" to "DesktopOffscreenRendererRegistry",
        "provider.packaging" to "PROBE_ONLY",
        "provider.lifecycle" to "REQUESTED",
        "platform" to System.getProperty("os.name"),
        "java.version" to System.getProperty("java.version"),
        "skiko.version" to (System.getenv("GRAPHITE_SKIKO_VERSION") ?: "unknown"),
        "lwjgl.version" to (System.getenv("GRAPHITE_LWJGL_VERSION") ?: "unknown"),
        "renderer.adoption" to "OPT_IN_ONLY",
        "vulkan.provider" to (System.getenv("GRAPHITE_VULKAN_PROVIDER") ?: "unknown")
    )

    try {
        check(System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
            "Graphite render-path provider probe is intentionally Windows-only"
        }

        stage = "plot-prepare"
        prepared = preparePlot(evidence)
        evidence["plot.spec.processing"] = "PASS"
        evidence["plot.drawable.update"] = "PASS"

        stage = "direct-control"
        val directPixels = renderDirectViaSeam(prepared.drawable, directPng)
        evidence.putAll(assertPlotStructure("direct", directPixels))
        evidence["direct.path"] = "NATIVE_CANVAS"
        evidence["direct.paint"] = "PASS"

        val directVulkanDll = System.getenv("GRAPHITE_VULKAN_DIRECT_DLL")
        if (!directVulkanDll.isNullOrBlank()) {
            stage = "vulkan-preload"
            val directDll = File(directVulkanDll).absoluteFile
            check(directDll.isFile) { "Direct Vulkan library does not exist: " + directDll.absolutePath }
            System.setProperty("org.lwjgl.vulkan.libname", directDll.absolutePath)
            System.load(directDll.absolutePath)
            evidence["vulkan.direct.dll"] = "LOADED"
        }

        stage = "provider-install"
        check(DesktopOffscreenRendererRegistry.current() == null) {
            "Unexpected pre-installed Desktop offscreen renderer"
        }
        val provider = GraphiteBackendProvider(evidence, graphitePng)
        val registration = DesktopOffscreenRendererRegistry.install(provider)
        evidence["provider.install"] = "PASS"

        val previousFlag = System.getProperty(DESKTOP_RENDER_PATH_PROPERTY)
        val compositedPixels: IntArray
        val secondFramePixels: IntArray
        try {
            System.setProperty(DESKTOP_RENDER_PATH_PROPERTY, "graphite-offscreen")
            val resolved = resolveDesktopRenderPath()
            check(resolved == DesktopRenderPath.OFFSCREEN_COMPOSITE) {
                "Feature flag did not resolve to OFFSCREEN_COMPOSITE: $resolved"
            }
            evidence["feature_flag.resolve"] = "PASS"

            stage = "provider-render-first-frame"
            compositedPixels = renderViaSelectedProvider(prepared.drawable)
            savePixels(compositedPixels, compositedPng)

            stage = "provider-render-second-frame"
            secondFramePixels = renderViaSelectedProvider(prepared.drawable)
            savePixels(secondFramePixels, secondFramePng)

            val frameDifference = pixelDifferenceRatio(compositedPixels, secondFramePixels)
            evidence["multi_frame.pixel_difference_ratio"] = frameDifference.toString()
            check(frameDifference <= 0.001) {
                "Persistent Graphite context produced inconsistent consecutive frames: difference=$frameDifference"
            }
            evidence["multi_frame.consistency"] = "PASS"
            evidence["multi_frame.count"] = "2"
            evidence["graphite.context.reuse"] = "PASS"
        } finally {
            if (previousFlag == null) {
                System.clearProperty(DESKTOP_RENDER_PATH_PROPERTY)
            } else {
                System.setProperty(DESKTOP_RENDER_PATH_PROPERTY, previousFlag)
            }
            registration.dispose()
        }

        stage = "provider-dispose"
        check(DesktopOffscreenRendererRegistry.current() == null) {
            "Graphite provider registration did not release ownership"
        }
        evidence["provider.registration.dispose"] = "PASS"
        check(evidence["provider.paint_count"] == "2") {
            "Expected two provider paints, got ${evidence["provider.paint_count"]}"
        }
        check(evidence["graphite.context.create_count"] == "1") {
            "Persistent Graphite context was recreated: ${evidence["graphite.context.create_count"]}"
        }
        check(evidence["graphite.context.reuse_count"] == "1") {
            "Persistent Graphite context reuse count is unexpected: ${evidence["graphite.context.reuse_count"]}"
        }
        check(evidence["provider.dispose_count"] == "1") {
            "Provider dispose count is unexpected: ${evidence["provider.dispose_count"]}"
        }
        check(evidence["graphite.context.dispose"] == "PASS")
        check(evidence["vulkan.device.dispose"] == "PASS")
        evidence["provider.lifecycle"] = "PERSISTENT_CONTEXT"
        evidence["persistent.context.lifecycle"] = "PASS"

        stage = "composite-structure"
        evidence.putAll(assertPlotStructure("provider", compositedPixels))

        stage = "comparison"
        val difference = pixelDifferenceRatio(directPixels, compositedPixels)
        evidence["comparison.pixel_difference_ratio"] = difference.toString()
        evidence["comparison.max_allowed_ratio"] = MAX_PIXEL_DIFFERENCE_RATIO.toString()
        check(difference <= MAX_PIXEL_DIFFERENCE_RATIO) {
            "Graphite provider output diverged from native control: " +
                "difference=$difference max=$MAX_PIXEL_DIFFERENCE_RATIO"
        }
        evidence["comparison"] = "PASS"

        evidence["result"] = "PERSISTENT_GRAPHITE_CONTEXT_CAPABLE"
        evidence["failure.stage"] = "none"
        writeEvidence(resultFile, evidence)

        println("PERSISTENT_GRAPHITE_CONTEXT_RESULT PASS")
        evidence.forEach { (key, value) -> println("$key=$value") }
    } catch (t: Throwable) {
        evidence["result"] = "FAIL"
        evidence["failure.stage"] = stage
        evidence["failure.type"] = t::class.qualifiedName ?: t::class.simpleName.orEmpty()
        evidence["failure.message"] = sanitize(t.message ?: "no message")
        writeEvidence(resultFile, evidence)
        println("PERSISTENT_GRAPHITE_CONTEXT_RESULT FAIL stage=$stage")
        throw t
    } finally {
        prepared?.registration?.dispose()
    }
}

private fun renderViaSelectedProvider(drawable: PlotCanvasDrawable): IntArray {
    Surface.makeRasterN32Premul(WIDTH, HEIGHT).use { surface ->
        surface.canvas.clear(WHITE)

        val effective = paintDesktopPlot(
            canvas = surface.canvas,
            width = WIDTH,
            height = HEIGHT,
            density = BRIDGE_DENSITY,
            plotPosition = DoubleVector(PLOT_X, PLOT_Y)
        ) { context ->
            drawable.paint(context)
        }

        check(effective == DesktopRenderPath.OFFSCREEN_COMPOSITE) {
            "Unexpected effective render path: $effective"
        }

        surface.makeImageSnapshot().use { image ->
            Bitmap.makeFromImage(image).use { bitmap ->
                return IntArray(WIDTH * HEIGHT) { index ->
                    bitmap.getColor(index % WIDTH, index / WIDTH)
                }
            }
        }
    }
}

private fun renderDirectViaSeam(drawable: PlotCanvasDrawable, png: File): IntArray {
    Surface.makeRasterN32Premul(WIDTH, HEIGHT).use { surface ->
        surface.canvas.clear(WHITE)
        val effective = paintDesktopPlot(
            canvas = surface.canvas,
            width = WIDTH,
            height = HEIGHT,
            density = BRIDGE_DENSITY,
            plotPosition = DoubleVector(PLOT_X, PLOT_Y),
            requestedPath = DesktopRenderPath.NATIVE_CANVAS
        ) { context ->
            drawable.paint(context)
        }
        check(effective == DesktopRenderPath.NATIVE_CANVAS)

        surface.makeImageSnapshot().use { image ->
            Bitmap.makeFromImage(image).use { bitmap ->
                val pixels = IntArray(WIDTH * HEIGHT) { index ->
                    bitmap.getColor(index % WIDTH, index / WIDTH)
                }
                savePixels(pixels, png)
                return pixels
            }
        }
    }
}

private fun preparePlot(evidence: MutableMap<String, String>): PreparedPlot {
    val processedSpec = processRawSpecs(createFigure().toSpec(), frontendOnly = false)
    check(!PlotConfig.isFailure(processedSpec)) {
        "Lets-Plot spec processing failed: " + PlotConfig.getErrorMessage(processedSpec)
    }

    val drawable = PlotCanvasDrawable()
    val registration = drawable.mapToCanvas(SkiaCanvasPeer(SkiaFontManager.DEFAULT))
    val messages = mutableListOf<String>()
    drawable.resize(LOGICAL_PLOT_WIDTH, LOGICAL_PLOT_HEIGHT)
    drawable.update(processedSpec, fitContainerSize(preserveAspectRatio = false)) { messages += it }

    check(drawable.size.x > 0 && drawable.size.y > 0) {
        "PlotCanvasDrawable produced an invalid size: ${drawable.size}"
    }
    evidence["plot.drawable.size"] = "${drawable.size.x}x${drawable.size.y}"
    evidence["plot.computation.messages"] = messages.size.toString()
    return PreparedPlot(drawable, registration)
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

private fun renderSoftware(drawable: PlotCanvasDrawable, png: File): IntArray {
    Surface.makeRasterN32Premul(WIDTH, HEIGHT).use { surface ->
        paintPlotDrawable(drawable, surface)
        surface.makeImageSnapshot().use { image ->
            Bitmap.makeFromImage(image).use { bitmap ->
                val pixels = IntArray(WIDTH * HEIGHT)
                for (y in 0 until HEIGHT) {
                    for (x in 0 until WIDTH) {
                        pixels[y * WIDTH + x] = bitmap.getColor(x, y)
                    }
                }
                savePixels(pixels, png)
                return pixels
            }
        }
    }
}

private fun compositeReadbackOnSoftwareCanvas(pixels: IntArray, png: File): IntArray {
    val buffered = BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB)
    buffered.setRGB(0, 0, WIDTH, HEIGHT, pixels, 0, WIDTH)

    buffered.toImage().use { image ->
        Surface.makeRasterN32Premul(WIDTH, HEIGHT).use { surface ->
            surface.canvas.clear(WHITE)
            surface.canvas.drawImage(image, 0f, 0f)
            surface.makeImageSnapshot().use { snapshot ->
                Bitmap.makeFromImage(snapshot).use { bitmap ->
                    val composited = IntArray(WIDTH * HEIGHT)
                    for (y in 0 until HEIGHT) {
                        for (x in 0 until WIDTH) {
                            composited[y * WIDTH + x] = bitmap.getColor(x, y)
                        }
                    }
                    savePixels(composited, png)
                    return composited
                }
            }
        }
    }
}

private fun paintPlotDrawable(drawable: PlotCanvasDrawable, surface: Surface) {
    surface.canvas.clear(WHITE)
    paintOnSkiaCanvas(
        canvas = surface.canvas,
        density = BRIDGE_DENSITY,
        plotPosition = DoubleVector(PLOT_X, PLOT_Y)
    ) { context ->
        drawable.paint(context)
    }
}

private fun assertPlotStructure(prefix: String, pixels: IntArray): Map<String, String> {
    val white = pixels.count { color ->
        channel(color, 16) >= 235 && channel(color, 8) >= 235 && channel(color, 0) >= 235
    }
    val red = pixels.count { color ->
        channel(color, 16) > 180 && channel(color, 8) < 120 && channel(color, 0) < 120
    }
    val green = pixels.count { color ->
        channel(color, 8) > 120 && channel(color, 16) < 160 && channel(color, 0) < 160
    }
    val dark = pixels.count { color ->
        channel(color, 16) <= 130 && channel(color, 8) <= 130 && channel(color, 0) <= 130
    }
    val plotInk = countInkInRect(pixels, 24, 24, WIDTH - 24, HEIGHT - 24)
    val sampledColors = linkedSetOf<Int>()
    for (y in 0 until HEIGHT step 8) {
        for (x in 0 until WIDTH step 8) {
            sampledColors += pixels[y * WIDTH + x] and 0x00FFFFFF
        }
    }

    check(white > pixels.size * 0.35) { "$prefix plot background coverage is unexpectedly low: $white" }
    check(red >= 40) { "$prefix red plot markers were not rendered: redPixels=$red" }
    check(green >= 40) { "$prefix green plot markers were not rendered: greenPixels=$green" }
    check(dark >= 300) { "$prefix axes/text coverage is too low: darkPixels=$dark" }
    check(plotInk >= 1000) { "$prefix plot area is unexpectedly blank: plotInk=$plotInk" }
    check(sampledColors.size >= 8) { "$prefix plot has too few sampled colors: ${sampledColors.size}" }

    return mapOf(
        "$prefix.structure" to "PASS",
        "$prefix.white_pixels" to white.toString(),
        "$prefix.red_pixels" to red.toString(),
        "$prefix.green_pixels" to green.toString(),
        "$prefix.dark_pixels" to dark.toString(),
        "$prefix.plot_ink" to plotInk.toString(),
        "$prefix.sampled_colors" to sampledColors.size.toString()
    )
}

private fun countInkInRect(pixels: IntArray, left: Int, top: Int, right: Int, bottom: Int): Int {
    var count = 0
    for (y in top until bottom) {
        for (x in left until right) {
            val color = pixels[y * WIDTH + x]
            val r = channel(color, 16)
            val g = channel(color, 8)
            val b = channel(color, 0)
            if (r < 225 || g < 225 || b < 225) count++
        }
    }
    return count
}

private fun pixelDifferenceRatio(left: IntArray, right: IntArray): Double {
    check(left.size == right.size)
    var changed = 0
    for (i in left.indices) {
        val a = left[i]
        val b = right[i]
        val delta =
            abs(channel(a, 16) - channel(b, 16)) +
                abs(channel(a, 8) - channel(b, 8)) +
                abs(channel(a, 0) - channel(b, 0))
        if (delta > PIXEL_DELTA_THRESHOLD) changed++
    }
    return changed.toDouble() / left.size.toDouble()
}

private fun channel(color: Int, shift: Int): Int = (color ushr shift) and 0xFF

private fun savePixels(pixels: IntArray, file: File) {
    val image = BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB)
    image.setRGB(0, 0, WIDTH, HEIGHT, pixels, 0, WIDTH)
    check(ImageIO.write(image, "png", file)) { "Failed to write PNG: ${file.absolutePath}" }
}

private fun createVulkanObjects(): VulkanObjects {
    MemoryStack.stackPush().use { stack ->
        val appInfo = VkApplicationInfo.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_APPLICATION_INFO)
            .pApplicationName(stack.UTF8("lets-plot-graphite-render-path-provider-probe"))
            .applicationVersion(1)
            .pEngineName(stack.UTF8("none"))
            .engineVersion(1)
            .apiVersion(VK_API_VERSION_1_1)
        val instanceCreateInfo = VkInstanceCreateInfo.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO)
            .pApplicationInfo(appInfo)
        val instancePtr = stack.mallocPointer(1)
        checkVk(vkCreateInstance(instanceCreateInfo, null, instancePtr), "vkCreateInstance")
        val instance = VkInstance(instancePtr[0], instanceCreateInfo)

        try {
            val physicalDeviceCount = stack.ints(0)
            checkVk(vkEnumeratePhysicalDevices(instance, physicalDeviceCount, null), "vkEnumeratePhysicalDevices(count)")
            check(physicalDeviceCount[0] > 0) { "No Vulkan physical device is available" }
            val physicalDevicePtrs = stack.mallocPointer(physicalDeviceCount[0])
            checkVk(vkEnumeratePhysicalDevices(instance, physicalDeviceCount, physicalDevicePtrs), "vkEnumeratePhysicalDevices(list)")

            for (i in 0 until physicalDeviceCount[0]) {
                val physicalDevice = VkPhysicalDevice(physicalDevicePtrs[i], instance)
                val queueFamilyCount = stack.ints(0)
                vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice, queueFamilyCount, null)
                if (queueFamilyCount[0] == 0) continue
                val queueFamilies = VkQueueFamilyProperties.calloc(queueFamilyCount[0], stack)
                vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice, queueFamilyCount, queueFamilies)
                val graphicsQueueIndex = (0 until queueFamilyCount[0]).firstOrNull { index ->
                    (queueFamilies[index].queueFlags() and VK_QUEUE_GRAPHICS_BIT) != 0
                } ?: continue

                val queuePriorities = stack.floats(1.0f)
                val queueCreateInfos = VkDeviceQueueCreateInfo.calloc(1, stack)
                queueCreateInfos[0]
                    .sType(VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO)
                    .queueFamilyIndex(graphicsQueueIndex)
                    .pQueuePriorities(queuePriorities)
                val deviceCreateInfo = VkDeviceCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO)
                    .pQueueCreateInfos(queueCreateInfos)
                val devicePtr = stack.mallocPointer(1)
                if (vkCreateDevice(physicalDevice, deviceCreateInfo, null, devicePtr) != VK_SUCCESS) continue
                val device = VkDevice(devicePtr[0], physicalDevice, deviceCreateInfo)
                val queuePtr = stack.mallocPointer(1)
                vkGetDeviceQueue(device, graphicsQueueIndex, 0, queuePtr)
                val queue = VkQueue(queuePtr[0], device)
                val properties = VkPhysicalDeviceProperties.calloc(stack)
                vkGetPhysicalDeviceProperties(physicalDevice, properties)
                if (properties.apiVersion() < VK_API_VERSION_1_1) {
                    vkDestroyDevice(device, null)
                    continue
                }
                return VulkanObjects(instance, physicalDevice, device, queue, graphicsQueueIndex, properties.deviceNameString())
            }
            error("No Vulkan 1.1+ physical device with a usable graphics queue/device was found")
        } catch (t: Throwable) {
            vkDestroyInstance(instance, null)
            throw t
        }
    }
}

private fun createRenderImage(vk: VulkanObjects): VulkanImage {
    MemoryStack.stackPush().use { stack ->
        val imageCreateInfo = VkImageCreateInfo.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO)
            .imageType(VK_IMAGE_TYPE_2D)
            .format(VK_FORMAT_B8G8R8A8_UNORM)
            .mipLevels(1)
            .arrayLayers(1)
            .samples(VK_SAMPLE_COUNT_1_BIT)
            .tiling(VK_IMAGE_TILING_OPTIMAL)
            .usage(
                VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT or
                    VK_IMAGE_USAGE_INPUT_ATTACHMENT_BIT or
                    VK_IMAGE_USAGE_TRANSFER_SRC_BIT or
                    VK_IMAGE_USAGE_TRANSFER_DST_BIT
            )
            .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
            .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED)
        imageCreateInfo.extent().width(WIDTH).height(HEIGHT).depth(1)

        val imagePtr = stack.mallocLong(1)
        checkVk(vkCreateImage(vk.device, imageCreateInfo, null, imagePtr), "vkCreateImage")
        val image = imagePtr[0]
        try {
            val requirements = VkMemoryRequirements.calloc(stack)
            vkGetImageMemoryRequirements(vk.device, image, requirements)
            val memoryProperties = VkPhysicalDeviceMemoryProperties.calloc(stack)
            vkGetPhysicalDeviceMemoryProperties(vk.physicalDevice, memoryProperties)
            val memoryTypeIndex = findMemoryType(requirements.memoryTypeBits(), VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, memoryProperties)
            val allocationInfo = VkMemoryAllocateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                .allocationSize(requirements.size())
                .memoryTypeIndex(memoryTypeIndex)
            val memoryPtr = stack.mallocLong(1)
            checkVk(vkAllocateMemory(vk.device, allocationInfo, null, memoryPtr), "vkAllocateMemory")
            val memory = memoryPtr[0]
            try {
                checkVk(vkBindImageMemory(vk.device, image, memory, 0), "vkBindImageMemory")
                return VulkanImage(image, memory)
            } catch (t: Throwable) {
                vkFreeMemory(vk.device, memory, null)
                throw t
            }
        } catch (t: Throwable) {
            vkDestroyImage(vk.device, image, null)
            throw t
        }
    }
}

private fun readBackGraphiteImage(vk: VulkanObjects, image: Long): IntArray {
    val byteSize = (WIDTH * HEIGHT * 4).toLong()
    MemoryStack.stackPush().use { stack ->
        val bufferInfo = VkBufferCreateInfo.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
            .size(byteSize)
            .usage(VK_BUFFER_USAGE_TRANSFER_DST_BIT)
            .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
        val bufferPtr = stack.mallocLong(1)
        checkVk(vkCreateBuffer(vk.device, bufferInfo, null, bufferPtr), "vkCreateBuffer")
        val stagingBuffer = bufferPtr[0]
        var stagingMemory = 0L
        var commandPool = 0L
        try {
            val requirements = VkMemoryRequirements.calloc(stack)
            vkGetBufferMemoryRequirements(vk.device, stagingBuffer, requirements)
            val memoryProperties = VkPhysicalDeviceMemoryProperties.calloc(stack)
            vkGetPhysicalDeviceMemoryProperties(vk.physicalDevice, memoryProperties)
            val memoryTypeIndex = findMemoryType(
                requirements.memoryTypeBits(),
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT or VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                memoryProperties
            )
            val allocationInfo = VkMemoryAllocateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                .allocationSize(requirements.size())
                .memoryTypeIndex(memoryTypeIndex)
            val memoryPtr = stack.mallocLong(1)
            checkVk(vkAllocateMemory(vk.device, allocationInfo, null, memoryPtr), "vkAllocateMemory(readback)")
            stagingMemory = memoryPtr[0]
            checkVk(vkBindBufferMemory(vk.device, stagingBuffer, stagingMemory, 0), "vkBindBufferMemory")

            val poolInfo = VkCommandPoolCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO)
                .flags(VK_COMMAND_POOL_CREATE_TRANSIENT_BIT)
                .queueFamilyIndex(vk.queueFamilyIndex)
            val poolPtr = stack.mallocLong(1)
            checkVk(vkCreateCommandPool(vk.device, poolInfo, null, poolPtr), "vkCreateCommandPool")
            commandPool = poolPtr[0]
            val allocInfo = VkCommandBufferAllocateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
                .commandPool(commandPool)
                .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                .commandBufferCount(1)
            val commandPtr = stack.mallocPointer(1)
            checkVk(vkAllocateCommandBuffers(vk.device, allocInfo, commandPtr), "vkAllocateCommandBuffers")
            val commandBuffer = VkCommandBuffer(commandPtr[0], vk.device)
            val beginInfo = VkCommandBufferBeginInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
                .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)
            checkVk(vkBeginCommandBuffer(commandBuffer, beginInfo), "vkBeginCommandBuffer")

            val barrier = VkImageMemoryBarrier.calloc(1, stack)
            barrier[0]
                .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                .srcAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT)
                .dstAccessMask(VK_ACCESS_TRANSFER_READ_BIT)
                .oldLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                .newLayout(VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .image(image)
            barrier[0].subresourceRange()
                .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(0)
                .levelCount(1)
                .baseArrayLayer(0)
                .layerCount(1)
            vkCmdPipelineBarrier(
                commandBuffer,
                VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
                VK_PIPELINE_STAGE_TRANSFER_BIT,
                0, null, null, barrier
            )

            val copy = VkBufferImageCopy.calloc(1, stack)
            copy[0].bufferOffset(0).bufferRowLength(0).bufferImageHeight(0)
            copy[0].imageSubresource()
                .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                .mipLevel(0)
                .baseArrayLayer(0)
                .layerCount(1)
            copy[0].imageOffset().set(0, 0, 0)
            copy[0].imageExtent().set(WIDTH, HEIGHT, 1)
            vkCmdCopyImageToBuffer(commandBuffer, image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, stagingBuffer, copy)
            checkVk(vkEndCommandBuffer(commandBuffer), "vkEndCommandBuffer")
            val submitInfo = VkSubmitInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_SUBMIT_INFO)
                .pCommandBuffers(stack.pointers(commandBuffer.address()))
            checkVk(vkQueueSubmit(vk.queue, submitInfo, VK_NULL_HANDLE), "vkQueueSubmit(readback)")
            checkVk(vkQueueWaitIdle(vk.queue), "vkQueueWaitIdle(readback)")

            val mappedPtr = stack.mallocPointer(1)
            checkVk(vkMapMemory(vk.device, stagingMemory, 0, byteSize, 0, mappedPtr), "vkMapMemory")
            try {
                val bytes = MemoryUtil.memByteBuffer(mappedPtr[0], byteSize.toInt())
                val pixels = IntArray(WIDTH * HEIGHT)
                for (i in pixels.indices) {
                    val offset = i * 4
                    val b = bytes.get(offset).toInt() and 0xFF
                    val g = bytes.get(offset + 1).toInt() and 0xFF
                    val r = bytes.get(offset + 2).toInt() and 0xFF
                    val a = bytes.get(offset + 3).toInt() and 0xFF
                    pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
                }
                return pixels
            } finally {
                vkUnmapMemory(vk.device, stagingMemory)
            }
        } finally {
            if (commandPool != 0L) vkDestroyCommandPool(vk.device, commandPool, null)
            if (stagingMemory != 0L) vkFreeMemory(vk.device, stagingMemory, null)
            vkDestroyBuffer(vk.device, stagingBuffer, null)
        }
    }
}

private fun findMemoryType(typeBits: Int, requiredFlags: Int, properties: VkPhysicalDeviceMemoryProperties): Int {
    for (index in 0 until properties.memoryTypeCount()) {
        val supported = (typeBits and (1 shl index)) != 0
        val flags = properties.memoryTypes(index).propertyFlags()
        if (supported && (flags and requiredFlags) == requiredFlags) return index
    }
    error("No Vulkan memory type matched required flags 0x" + requiredFlags.toString(16))
}

private fun checkVk(result: Int, operation: String) {
    check(result == VK_SUCCESS) { "$operation failed with Vulkan result $result" }
}

private fun writeEvidence(file: File, values: Map<String, String>) {
    file.writeText(values.entries.joinToString(separator = "\n", postfix = "\n") { (key, value) -> "$key=$value" })
}

private fun sanitize(value: String): String = value.replace('\n', ' ').replace('\r', ' ').take(500)
