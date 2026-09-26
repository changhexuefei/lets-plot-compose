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
import org.jetbrains.letsPlot.compose.PlotPanel
import org.jetbrains.letsPlot.compose.canvas.SkiaContext2d
import org.jetbrains.letsPlot.compose.canvas.SkiaFontManager
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
private const val WHITE = -1

private data class VulkanObjects(
    val instance: VkInstance,
    val physicalDevice: VkPhysicalDevice,
    val device: VkDevice,
    val queue: VkQueue,
    val queueFamilyIndex: Int,
    val deviceName: String
)

private data class PersistentRenderTarget(
    val image: Long,
    val memory: Long,
    val width: Int,
    val height: Int,
    var layout: Int = VK_IMAGE_LAYOUT_UNDEFINED
)

private data class BackendSnapshot(
    val successfulFrames: Int,
    val contextCreateCount: Int,
    val targetCreateCount: Int,
    val targetReuseCount: Int,
    val targetResizeCount: Int,
    val targetDisposeCount: Int,
    val providerDisposeCount: Int,
    val width: Int,
    val height: Int,
    val layoutReuseObserved: Boolean
)

private class PersistentGraphiteShadowBackend(
    private val evidence: MutableMap<String, String>
) {
    private var vulkan: VulkanObjects? = null
    private var graphiteContext: GraphiteContext? = null
    private var target: PersistentRenderTarget? = null
    private var disposed = false

    private val successfulFrames = AtomicInteger()
    private val contextCreateCount = AtomicInteger()
    private val targetCreateCount = AtomicInteger()
    private val targetReuseCount = AtomicInteger()
    private val targetResizeCount = AtomicInteger()
    private val targetDisposeCount = AtomicInteger()
    private val providerDisposeCount = AtomicInteger()

    @Volatile
    private var lastWidth = 0

    @Volatile
    private var lastHeight = 0

    @Volatile
    private var layoutReuseObserved = false

    fun snapshot(): BackendSnapshot = BackendSnapshot(
        successfulFrames = successfulFrames.get(),
        contextCreateCount = contextCreateCount.get(),
        targetCreateCount = targetCreateCount.get(),
        targetReuseCount = targetReuseCount.get(),
        targetResizeCount = targetResizeCount.get(),
        targetDisposeCount = targetDisposeCount.get(),
        providerDisposeCount = providerDisposeCount.get(),
        width = lastWidth,
        height = lastHeight,
        layoutReuseObserved = layoutReuseObserved
    )

    @OptIn(ExperimentalSkikoApi::class)
    fun paint(args: Array<out Any?>?) {
        check(!disposed) { "Graphite shadow provider is disposed" }
        require(args != null && args.size == 6) { "Unexpected DesktopOffscreenRenderer.paint arguments" }

        val targetCanvas = args[0] as org.jetbrains.skia.Canvas
        val width = args[1] as Int
        val height = args[2] as Int
        val density = args[3] as Double
        val plotPosition = args[4] as DoubleVector
        @Suppress("UNCHECKED_CAST")
        val paint = args[5] as (SkiaContext2d) -> Unit

        check(width > 0 && height > 0) { "Invalid PlotPanel target size: ${width}x${height}" }

        val (vk, context) = ensureContext()
        val renderTarget = ensureTarget(vk, width, height)

        if (renderTarget.layout == VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL) {
            layoutReuseObserved = true
            evidence["image.layout.reuse_transition"] = "PASS"
        }

        val pixels: IntArray
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
                imageLayout = renderTarget.layout,
                queueFamilyIndex = vk.queueFamilyIndex,
                imagePtr = renderTarget.image
            ).use { backendTexture ->
                val surface = Surface.wrapBackendTexture(
                    recorder = recorder,
                    backendTexture = backendTexture,
                    colorSpace = null
                ) ?: error("Graphite Surface.wrapBackendTexture returned null")

                surface.use {
                    surface.canvas.clear(WHITE)

                    val context2d = SkiaContext2d(surface.canvas, SkiaFontManager.DEFAULT)
                    try {
                        context2d.scale(density)
                        context2d.translate(plotPosition.x, plotPosition.y)
                        paint(context2d)
                    } finally {
                        context2d.dispose()
                    }

                    recorder.snap().use { recording ->
                        context.insertRecording(recording)
                        context.submit(syncCpu = true)
                    }
                    renderTarget.layout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL

                    pixels = readBack(vk, renderTarget)
                }
            }
        }

        val buffered = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        buffered.setRGB(0, 0, width, height, pixels, 0, width)
        buffered.toImage().use { image ->
            targetCanvas.drawImage(image, 0f, 0f)
        }

        lastWidth = width
        lastHeight = height
        successfulFrames.incrementAndGet()
        evidence["graphite.context"] = "CREATED"
        evidence["graphite.submit"] = "PASS"
        evidence["graphite.readback"] = "PASS"
        evidence["provider.composite_to_target"] = "PASS"
        evidence["provider.lifecycle"] = "PERSISTENT_CONTEXT_AND_RENDER_TARGET"
        evidence["render_target.lifecycle"] = "PERSISTENT_BY_SIZE"
    }

    private fun ensureTarget(vk: VulkanObjects, width: Int, height: Int): PersistentRenderTarget {
        val existing = target
        if (existing != null && existing.width == width && existing.height == height) {
            targetReuseCount.incrementAndGet()
            return existing
        }

        if (existing != null) {
            vkDeviceWaitIdle(vk.device)
            disposeTarget(vk, existing)
            targetResizeCount.incrementAndGet()
        }

        return createRenderImage(vk, width, height).also {
            target = it
            targetCreateCount.incrementAndGet()
        }
    }

    @OptIn(ExperimentalSkikoApi::class)
    private fun ensureContext(): Pair<VulkanObjects, GraphiteContext> {
        val existingVk = vulkan
        val existingContext = graphiteContext
        if (existingVk != null && existingContext != null) {
            return existingVk to existingContext
        }

        val vk = createVulkanObjects()
        val context = try {
            GraphiteContext.makeVulkan(
                instancePtr = vk.instance.address(),
                physicalDevicePtr = vk.physicalDevice.address(),
                devicePtr = vk.device.address(),
                queuePtr = vk.queue.address(),
                graphicsQueueIndex = vk.queueFamilyIndex,
                maxApiVersion = VK_API_VERSION_1_1
            )
        } catch (t: Throwable) {
            vkDestroyDevice(vk.device, null)
            vkDestroyInstance(vk.instance, null)
            throw t
        }

        vulkan = vk
        graphiteContext = context
        contextCreateCount.incrementAndGet()
        evidence["vulkan.device.name"] = vk.deviceName
        return vk to context
    }

    @OptIn(ExperimentalSkikoApi::class)
    fun dispose() {
        if (disposed) return
        disposed = true
        providerDisposeCount.incrementAndGet()

        val vk = vulkan
        try {
            if (vk != null) {
                vkDeviceWaitIdle(vk.device)
            }

            graphiteContext?.close()
            graphiteContext = null

            if (vk != null) {
                target?.let { disposeTarget(vk, it) }
            }
        } finally {
            if (vk != null) {
                vkDestroyDevice(vk.device, null)
                vkDestroyInstance(vk.instance, null)
            }
            target = null
            vulkan = null
        }
    }

    private fun disposeTarget(vk: VulkanObjects, renderTarget: PersistentRenderTarget) {
        vkDestroyImage(vk.device, renderTarget.image, null)
        vkFreeMemory(vk.device, renderTarget.memory, null)
        targetDisposeCount.incrementAndGet()
        if (target === renderTarget) {
            target = null
        }
    }
}

fun main() {
    val outputDir = File(
        System.getenv("GRAPHITE_SHADOW_OUTPUT_DIR")
            ?: "graphite-plotpanel-shadow-probe/build/probe"
    ).apply { mkdirs() }

    val resultFile = outputDir.resolve("graphite-plotpanel-shadow.txt")
    val initialScreenshot = outputDir.resolve("plotpanel-graphite-initial.png")
    val resizedScreenshot = outputDir.resolve("plotpanel-graphite-resized.png")

    val evidence = linkedMapOf(
        "schema" to "1",
        "probe.type" to "compose-plotpanel-persistent-graphite-shadow",
        "compose.version" to "1.13.0-alpha01",
        "skiko.version" to (System.getenv("GRAPHITE_SKIKO_VERSION") ?: "unknown"),
        "lwjgl.version" to (System.getenv("GRAPHITE_LWJGL_VERSION") ?: "unknown"),
        "production.renderer.default" to "NATIVE_CANVAS",
        "feature_flag.value" to "graphite-offscreen",
        "provider.binding" to "INTERNAL_REGISTRY_REFLECTION",
        "provider.packaging" to "PROBE_ONLY",
        "renderer.adoption" to "SHADOW_OPT_IN_ONLY",
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
        Thread.sleep(45_000)
        if (completed.compareAndSet(false, true)) {
            evidence["result"] = "FAIL"
            evidence["failure.stage"] = "watchdog"
            evidence["failure.type"] = "TIMEOUT"
            evidence["failure.message"] = "PlotPanel Graphite shadow probe did not complete within 45 seconds"
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
        var initialBaseline: BackendSnapshot? = null
        var resizedObserved = false

        application {
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

                    composeWindow.setSize(RESIZED_WINDOW_WIDTH, RESIZED_WINDOW_HEIGHT)

                    val resized = waitForResizeFrames(backend, stable)
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
                    check(resized.targetCreateCount > stable.targetCreateCount) {
                        "Resize did not recreate the persistent render target"
                    }
                    check(resized.targetResizeCount > stable.targetResizeCount) {
                        "Resize lifecycle was not observed"
                    }
                    check(resized.layoutReuseObserved) {
                        "Persistent render target layout reuse was not observed"
                    }

                    evidence["graphite.context.create_count"] = resized.contextCreateCount.toString()
                    evidence["render_target.reuse"] = "PASS"
                    evidence["render_target.resize"] = "PASS"
                    evidence["image.layout.reuse_transition"] = "PASS"
                    evidence["compose.recomposition.after_resize"] = "PASS"

                    exitApplication()
                }
            }
        }

        check(initialBaseline != null) { "Initial PlotPanel baseline was not captured" }
        check(resizedObserved) { "PlotPanel resize phase did not complete" }

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
        evidence["result"] = "COMPOSE_PLOTPANEL_PERSISTENT_GRAPHITE_SHADOW_CAPABLE"
        evidence["failure.stage"] = "none"
        writeEvidence(resultFile, evidence)

        completed.set(true)
        println("COMPOSE_PLOTPANEL_PERSISTENT_GRAPHITE_SHADOW_RESULT PASS")
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

private fun installInternalRenderer(
    backend: PersistentGraphiteShadowBackend,
    evidence: MutableMap<String, String>
): Registration {
    val rendererInterface = Class.forName("org.jetbrains.letsPlot.compose.DesktopOffscreenRenderer")
    val registryClass = Class.forName("org.jetbrains.letsPlot.compose.DesktopOffscreenRendererRegistry")
    val registry = registryClass.getField("INSTANCE").get(null)

    val proxy = Proxy.newProxyInstance(
        rendererInterface.classLoader,
        arrayOf(rendererInterface)
    ) { proxyInstance, method, args ->
        when {
            method.name == "paint" -> {
                backend.paint(args)
                Unit
            }
            method.name == "dispose" -> {
                backend.dispose()
                Unit
            }
            method.name == "toString" -> "PersistentGraphiteShadowRenderer"
            method.name == "hashCode" -> System.identityHashCode(proxyInstance)
            method.name == "equals" -> proxyInstance === args?.firstOrNull()
            else -> error("Unexpected renderer method: ${method.name}")
        }
    }

    val install = registryClass.methods.singleOrNull { method ->
        method.name.startsWith("install") &&
            method.parameterCount == 1 &&
            method.parameterTypes[0].isAssignableFrom(rendererInterface)
    } ?: error(
        "DesktopOffscreenRendererRegistry.install was not found; methods=" +
            registryClass.methods.joinToString { it.name }
    )

    evidence["provider.registry.method"] = install.name
    evidence["provider.interface"] = rendererInterface.name

    return install.invoke(registry, proxy) as Registration
}

private fun createFigure(): Figure {
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

    check(nonWhite >= 250) { "PlotPanel screenshot is unexpectedly blank: nonWhite=$nonWhite" }
    check(dark >= 30) { "PlotPanel screenshot has too little axes/text ink: dark=$dark" }
    check(sampled.size >= 8) { "PlotPanel screenshot has too few sampled colors: ${sampled.size}" }
}

private fun preloadDirectVulkan(evidence: MutableMap<String, String>) {
    val directVulkanDll = System.getenv("GRAPHITE_VULKAN_DIRECT_DLL")
    if (!directVulkanDll.isNullOrBlank()) {
        val directDll = File(directVulkanDll).absoluteFile
        check(directDll.isFile) { "Direct Vulkan library does not exist: ${directDll.absolutePath}" }
        System.setProperty("org.lwjgl.vulkan.libname", directDll.absolutePath)
        System.load(directDll.absolutePath)
        evidence["vulkan.direct.dll"] = "LOADED"
    }
    evidence["vulkan.provider"] = System.getenv("GRAPHITE_VULKAN_PROVIDER") ?: "system"
}

private fun createRenderImage(vk: VulkanObjects, width: Int, height: Int): PersistentRenderTarget {
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
        imageCreateInfo.extent().width(width).height(height).depth(1)

        val imagePtr = stack.mallocLong(1)
        checkVk(vkCreateImage(vk.device, imageCreateInfo, null, imagePtr), "vkCreateImage")
        val image = imagePtr[0]

        try {
            val requirements = VkMemoryRequirements.calloc(stack)
            vkGetImageMemoryRequirements(vk.device, image, requirements)

            val memoryProperties = VkPhysicalDeviceMemoryProperties.calloc(stack)
            vkGetPhysicalDeviceMemoryProperties(vk.physicalDevice, memoryProperties)

            val memoryTypeIndex = findMemoryType(
                requirements.memoryTypeBits(),
                VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
                memoryProperties
            )

            val allocationInfo = VkMemoryAllocateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                .allocationSize(requirements.size())
                .memoryTypeIndex(memoryTypeIndex)

            val memoryPtr = stack.mallocLong(1)
            checkVk(vkAllocateMemory(vk.device, allocationInfo, null, memoryPtr), "vkAllocateMemory")
            val memory = memoryPtr[0]

            try {
                checkVk(vkBindImageMemory(vk.device, image, memory, 0), "vkBindImageMemory")
                return PersistentRenderTarget(image, memory, width, height)
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

private fun readBack(vk: VulkanObjects, target: PersistentRenderTarget): IntArray {
    val byteSize = (target.width * target.height * 4).toLong()

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
                .oldLayout(target.layout)
                .newLayout(VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .image(target.image)

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
                0,
                null,
                null,
                barrier
            )

            val copy = VkBufferImageCopy.calloc(1, stack)
            copy[0].bufferOffset(0).bufferRowLength(0).bufferImageHeight(0)
            copy[0].imageSubresource()
                .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                .mipLevel(0)
                .baseArrayLayer(0)
                .layerCount(1)
            copy[0].imageOffset().set(0, 0, 0)
            copy[0].imageExtent().set(target.width, target.height, 1)

            vkCmdCopyImageToBuffer(
                commandBuffer,
                target.image,
                VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                stagingBuffer,
                copy
            )

            checkVk(vkEndCommandBuffer(commandBuffer), "vkEndCommandBuffer")

            val submitInfo = VkSubmitInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_SUBMIT_INFO)
                .pCommandBuffers(stack.pointers(commandBuffer.address()))

            checkVk(vkQueueSubmit(vk.queue, submitInfo, VK_NULL_HANDLE), "vkQueueSubmit(readback)")
            checkVk(vkQueueWaitIdle(vk.queue), "vkQueueWaitIdle(readback)")
            target.layout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL

            val mappedPtr = stack.mallocPointer(1)
            checkVk(vkMapMemory(vk.device, stagingMemory, 0, byteSize, 0, mappedPtr), "vkMapMemory")

            try {
                val bytes = MemoryUtil.memByteBuffer(mappedPtr[0], byteSize.toInt())
                val pixels = IntArray(target.width * target.height)

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

private fun createVulkanObjects(): VulkanObjects {
    MemoryStack.stackPush().use { stack ->
        val appInfo = VkApplicationInfo.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_APPLICATION_INFO)
            .pApplicationName(stack.UTF8("lets-plot-graphite-plotpanel-shadow-probe"))
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
            checkVk(
                vkEnumeratePhysicalDevices(instance, physicalDeviceCount, null),
                "vkEnumeratePhysicalDevices(count)"
            )
            check(physicalDeviceCount[0] > 0) { "No Vulkan physical device is available" }

            val physicalDevicePtrs = stack.mallocPointer(physicalDeviceCount[0])
            checkVk(
                vkEnumeratePhysicalDevices(instance, physicalDeviceCount, physicalDevicePtrs),
                "vkEnumeratePhysicalDevices(list)"
            )

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

                return VulkanObjects(
                    instance = instance,
                    physicalDevice = physicalDevice,
                    device = device,
                    queue = queue,
                    queueFamilyIndex = graphicsQueueIndex,
                    deviceName = properties.deviceNameString()
                )
            }

            error("No Vulkan 1.1+ physical device with a usable graphics queue/device was found")
        } catch (t: Throwable) {
            vkDestroyInstance(instance, null)
            throw t
        }
    }
}

private fun findMemoryType(
    typeBits: Int,
    requiredFlags: Int,
    properties: VkPhysicalDeviceMemoryProperties
): Int {
    for (index in 0 until properties.memoryTypeCount()) {
        val supported = (typeBits and (1 shl index)) != 0
        val flags = properties.memoryTypes(index).propertyFlags()
        if (supported && (flags and requiredFlags) == requiredFlags) {
            return index
        }
    }
    error("No Vulkan memory type matched required flags 0x" + requiredFlags.toString(16))
}

private fun checkVk(result: Int, operation: String) {
    check(result == VK_SUCCESS) { "$operation failed with Vulkan result $result" }
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
