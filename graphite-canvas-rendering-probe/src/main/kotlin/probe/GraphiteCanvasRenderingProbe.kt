package probe

import org.jetbrains.letsPlot.commons.values.Color as LpColor
import org.jetbrains.letsPlot.compose.canvas.SkiaContext2d
import org.jetbrains.letsPlot.compose.canvas.SkiaFontManager
import org.jetbrains.letsPlot.core.canvas.Font
import org.jetbrains.letsPlot.core.canvas.FontWeight
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Surface
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

private const val WIDTH = 640
private const val HEIGHT = 420
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

@OptIn(ExperimentalSkikoApi::class)
fun main() {
    val outputDir = File(
        System.getenv("GRAPHITE_PROBE_OUTPUT_DIR")
            ?: "graphite-canvas-rendering-probe/build/probe"
    ).apply { mkdirs() }
    val resultFile = outputDir.resolve("graphite-canvas-rendering.txt")
    val softwarePng = outputDir.resolve("software-skia-context2d.png")
    val graphitePng = outputDir.resolve("graphite-skia-context2d.png")

    var stage = "startup"
    val evidence = linkedMapOf(
        "schema" to "1",
        "probe.type" to "graphite-canvas-rendering",
        "adapter" to "SkiaContext2d",
        "platform" to System.getProperty("os.name"),
        "java.version" to System.getProperty("java.version"),
        "skiko.version" to (System.getenv("GRAPHITE_SKIKO_VERSION") ?: "unknown"),
        "lwjgl.version" to (System.getenv("GRAPHITE_LWJGL_VERSION") ?: "unknown"),
        "surface.width" to WIDTH.toString(),
        "surface.height" to HEIGHT.toString(),
        "scene.axes" to "REQUESTED",
        "scene.path" to "REQUESTED",
        "scene.points" to "REQUESTED",
        "scene.text" to "REQUESTED",
        "plotpanel.integration" to "NOT_ATTEMPTED",
        "renderer.adoption" to "NOT_STARTED",
        "vulkan.provider" to (System.getenv("GRAPHITE_VULKAN_PROVIDER") ?: "unknown"),
        "vulkan.requested.api" to "1.1"
    )

    try {
        check(System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
            "Graphite Canvas rendering probe is intentionally Windows-only"
        }

        stage = "software-render"
        val softwarePixels = renderSoftware(softwarePng)
        val softwareMetrics = assertSceneStructure("software", softwarePixels)
        evidence.putAll(softwareMetrics)
        evidence["software.baseline"] = "PASS"

        val directVulkanDll = System.getenv("GRAPHITE_VULKAN_DIRECT_DLL")
        if (!directVulkanDll.isNullOrBlank()) {
            stage = "vulkan-preload"
            val directDll = File(directVulkanDll).absoluteFile
            check(directDll.isFile) { "Direct Vulkan library does not exist: " + directDll.absolutePath }
            System.setProperty("org.lwjgl.vulkan.libname", directDll.absolutePath)
            System.load(directDll.absolutePath)
            evidence["vulkan.direct.dll"] = "LOADED"
        }

        stage = "vulkan-create"
        val vk = createVulkanObjects()
        evidence["vulkan.device.name"] = vk.deviceName

        val target = try {
            stage = "vulkan-image"
            createRenderImage(vk).also {
                evidence["vulkan.image"] = "CREATED"
                evidence["vulkan.image.memory"] = "BOUND"
            }
        } catch (t: Throwable) {
            vkDestroyDevice(vk.device, null)
            vkDestroyInstance(vk.instance, null)
            throw t
        }

        val graphitePixels: IntArray
        try {
            stage = "graphite-context"
            GraphiteContext.makeVulkan(
                instancePtr = vk.instance.address(),
                physicalDevicePtr = vk.physicalDevice.address(),
                devicePtr = vk.device.address(),
                queuePtr = vk.queue.address(),
                graphicsQueueIndex = vk.queueFamilyIndex,
                maxApiVersion = VK_API_VERSION_1_1
            ).use { context ->
                evidence["graphite.context"] = "CREATED"
                context.makeRecorder().use { recorder ->
                    BackendTexture.makeVulkan(
                        width = WIDTH,
                        height = HEIGHT,
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
                        evidence["graphite.backendTexture"] = "CREATED"
                        val surface = Surface.wrapBackendTexture(
                            recorder = recorder,
                            backendTexture = backendTexture,
                            colorSpace = null
                        ) ?: error("Graphite Surface.wrapBackendTexture returned null")

                        surface.use {
                            evidence["graphite.surface"] = "CREATED"
                            stage = "graphite-skia-context2d"
                            drawSceneWithLetsPlotAdapter(surface)
                            evidence["graphite.skiaContext2d"] = "PAINTED"

                            stage = "graphite-submit"
                            recorder.snap().use { recording ->
                                context.insertRecording(recording)
                                context.submit(syncCpu = true)
                            }
                            evidence["graphite.submit"] = "PASS"

                            stage = "graphite-readback"
                            graphitePixels = readBackGraphiteImage(vk, target.image)
                            savePixels(graphitePixels, graphitePng)
                            evidence["graphite.readback"] = "PASS"
                        }
                    }
                }
            }
        } finally {
            vkDeviceWaitIdle(vk.device)
            vkDestroyImage(vk.device, target.image, null)
            vkFreeMemory(vk.device, target.memory, null)
            vkDestroyDevice(vk.device, null)
            vkDestroyInstance(vk.instance, null)
        }

        stage = "graphite-structure"
        val graphiteMetrics = assertSceneStructure("graphite", graphitePixels)
        evidence.putAll(graphiteMetrics)
        evidence["scene.axes"] = "PASS"
        evidence["scene.path"] = "PASS"
        evidence["scene.points"] = "PASS"
        evidence["scene.text"] = "PASS"

        stage = "backend-comparison"
        val difference = pixelDifferenceRatio(softwarePixels, graphitePixels)
        evidence["comparison.pixel_difference_ratio"] = difference.toString()
        evidence["comparison.max_allowed_ratio"] = MAX_PIXEL_DIFFERENCE_RATIO.toString()
        check(difference <= MAX_PIXEL_DIFFERENCE_RATIO) {
            "Graphite SkiaContext2d output diverged from SOFTWARE baseline: " +
                "difference=$difference max=$MAX_PIXEL_DIFFERENCE_RATIO"
        }
        evidence["comparison"] = "PASS"

        evidence["result"] = "CANVAS_RENDERING_CAPABLE"
        evidence["failure.stage"] = "none"
        writeEvidence(resultFile, evidence)
        println("GRAPHITE_CANVAS_RENDERING_RESULT PASS")
        evidence.forEach { (key, value) -> println("$key=$value") }
    } catch (t: Throwable) {
        evidence["result"] = "FAIL"
        evidence["failure.stage"] = stage
        evidence["failure.type"] = t::class.qualifiedName ?: t::class.simpleName.orEmpty()
        evidence["failure.message"] = sanitize(t.message ?: "no message")
        writeEvidence(resultFile, evidence)
        println("GRAPHITE_CANVAS_RENDERING_RESULT FAIL stage=$stage type=" + evidence["failure.type"])
        throw t
    }
}

private fun renderSoftware(png: File): IntArray {
    Surface.makeRasterN32Premul(WIDTH, HEIGHT).use { surface ->
        drawSceneWithLetsPlotAdapter(surface)
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

private fun drawSceneWithLetsPlotAdapter(surface: Surface) {
    val ctx = SkiaContext2d(surface.canvas, SkiaFontManager.DEFAULT)
    try {
        ctx.setFillStyle(LpColor.WHITE)
        ctx.fillRect(0.0, 0.0, WIDTH.toDouble(), HEIGHT.toDouble())

        ctx.setStrokeStyle(LpColor.DARK_GRAY)
        ctx.setLineWidth(2.0)
        ctx.beginPath()
        ctx.moveTo(74.0, 342.0)
        ctx.lineTo(586.0, 342.0)
        ctx.moveTo(74.0, 342.0)
        ctx.lineTo(74.0, 60.0)
        for (x in listOf(154.0, 234.0, 314.0, 394.0, 474.0, 554.0)) {
            ctx.moveTo(x, 337.0)
            ctx.lineTo(x, 347.0)
        }
        for (y in listOf(86.0, 142.0, 198.0, 254.0, 310.0)) {
            ctx.moveTo(69.0, y)
            ctx.lineTo(79.0, y)
        }
        ctx.stroke()

        ctx.setStrokeStyle(LpColor.PACIFIC_BLUE)
        ctx.setLineWidth(4.0)
        ctx.beginPath()
        ctx.moveTo(94.0, 292.0)
        ctx.bezierCurveTo(170.0, 262.0, 196.0, 150.0, 260.0, 190.0)
        ctx.bezierCurveTo(322.0, 230.0, 358.0, 94.0, 424.0, 126.0)
        ctx.bezierCurveTo(472.0, 150.0, 522.0, 112.0, 566.0, 82.0)
        ctx.stroke()

        ctx.setStrokeStyle(LpColor.WHITE)
        ctx.setFillStyle(LpColor.RED)
        ctx.setLineWidth(2.0)
        listOf(
            112.0 to 282.0,
            224.0 to 182.0,
            326.0 to 214.0,
            438.0 to 120.0,
            548.0 to 94.0
        ).forEach { (x, y) -> ctx.drawCircle(x, y, 8.0) }

        ctx.setFillStyle(LpColor.BLACK)
        ctx.setFont(Font(fontWeight = FontWeight.BOLD, fontSize = 20.0, fontFamily = "Arial"))
        ctx.fillText("Lets-Plot SkiaContext2d", 78.0, 34.0)
        ctx.setFont(Font(fontSize = 13.0, fontFamily = "Arial"))
        ctx.fillText("SOFTWARE vs Graphite", 78.0, 52.0)
        ctx.fillText("X axis", 300.0, 382.0)
        ctx.fillText("Y", 44.0, 194.0)
        ctx.fillText("0", 62.0, 360.0)
        ctx.fillText("5", 550.0, 360.0)
    } finally {
        ctx.dispose()
    }
}

private fun assertSceneStructure(prefix: String, pixels: IntArray): Map<String, String> {
    val white = pixels.count { color ->
        channel(color, 16) >= 235 && channel(color, 8) >= 235 && channel(color, 0) >= 235
    }
    val red = pixels.count { color ->
        channel(color, 16) >= 180 && channel(color, 8) <= 120 && channel(color, 0) <= 120
    }
    val blue = pixels.count { color ->
        channel(color, 0) >= 140 && channel(color, 16) <= 140 && channel(color, 8) <= 190
    }
    val dark = pixels.count { color ->
        channel(color, 16) <= 120 && channel(color, 8) <= 120 && channel(color, 0) <= 120
    }
    val titleInk = countInkInRect(pixels, 70, 10, 360, 62)
    val axesInk = countInkInRect(pixels, 55, 55, 600, 365)

    check(white > pixels.size * 0.50) { "$prefix background coverage is unexpectedly low: $white" }
    check(red >= 150) { "$prefix point coverage is too low: redPixels=$red" }
    check(blue >= 150) { "$prefix path coverage is too low: bluePixels=$blue" }
    check(dark >= 300) { "$prefix axes/text coverage is too low: darkPixels=$dark" }
    check(titleInk >= 80) { "$prefix title text was not visibly rendered: titleInk=$titleInk" }
    check(axesInk >= 300) { "$prefix axes/path area is unexpectedly blank: axesInk=$axesInk" }

    return mapOf(
        "$prefix.structure" to "PASS",
        "$prefix.white_pixels" to white.toString(),
        "$prefix.red_pixels" to red.toString(),
        "$prefix.blue_pixels" to blue.toString(),
        "$prefix.dark_pixels" to dark.toString(),
        "$prefix.title_ink" to titleInk.toString(),
        "$prefix.axes_ink" to axesInk.toString()
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
            .pApplicationName(stack.UTF8("lets-plot-graphite-canvas-rendering-probe"))
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
