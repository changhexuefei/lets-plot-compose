package probe

import org.jetbrains.skia.gpu.graphite.GraphiteContext
import org.jetbrains.skiko.ExperimentalSkikoApi
import org.lwjgl.system.MemoryStack
import org.lwjgl.vulkan.VK10.*
import org.lwjgl.vulkan.VkApplicationInfo
import org.lwjgl.vulkan.VkDevice
import org.lwjgl.vulkan.VkDeviceCreateInfo
import org.lwjgl.vulkan.VkDeviceQueueCreateInfo
import org.lwjgl.vulkan.VkInstance
import org.lwjgl.vulkan.VkInstanceCreateInfo
import org.lwjgl.vulkan.VkPhysicalDevice
import org.lwjgl.vulkan.VkPhysicalDeviceProperties
import org.lwjgl.vulkan.VkQueue
import org.lwjgl.vulkan.VkQueueFamilyProperties
import java.io.File

private data class VulkanObjects(
    val instance: VkInstance,
    val physicalDevice: VkPhysicalDevice,
    val device: VkDevice,
    val queue: VkQueue,
    val queueFamilyIndex: Int,
    val deviceName: String,
    val apiVersion: Int
)

@OptIn(ExperimentalSkikoApi::class)
fun main() {
    val outputDir = File(
        System.getenv("GRAPHITE_PROBE_OUTPUT_DIR")
            ?: "graphite-runtime-probe/build/probe"
    ).apply { mkdirs() }
    val resultFile = outputDir.resolve("graphite-runtime-capability.txt")

    var stage = "startup"
    val evidence = linkedMapOf(
        "schema" to "1",
        "probe.type" to "graphite-runtime-capability",
        "platform" to System.getProperty("os.name"),
        "java.version" to System.getProperty("java.version"),
        "skiko.version" to (System.getenv("GRAPHITE_SKIKO_VERSION") ?: "unknown"),
        "lwjgl.version" to (System.getenv("GRAPHITE_LWJGL_VERSION") ?: "unknown"),
        "compose.integration" to "NOT_ATTEMPTED",
        "plotpanel.integration" to "NOT_ATTEMPTED",
        "renderer.adoption" to "NOT_STARTED"
    )

    try {
        check(System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
            "Runtime capability probe is intentionally Windows-only"
        }

        stage = "vulkan-create"
        val vk = createVulkanObjects()
        evidence["vulkan.instance"] = "CREATED"
        evidence["vulkan.device"] = "CREATED"
        evidence["vulkan.queue"] = "CREATED"
        evidence["vulkan.device.name"] = vk.deviceName
        evidence["vulkan.queue.family"] = vk.queueFamilyIndex.toString()
        evidence["vulkan.api.version"] = vk.apiVersion.toString()

        try {
            stage = "graphite-context"
            GraphiteContext.makeVulkan(
                instancePtr = vk.instance.address(),
                physicalDevicePtr = vk.physicalDevice.address(),
                devicePtr = vk.device.address(),
                queuePtr = vk.queue.address(),
                graphicsQueueIndex = vk.queueFamilyIndex,
                maxApiVersion = VK_API_VERSION_1_0
            ).use { context ->
                evidence["graphite.native.library"] = "LOADED"
                evidence["graphite.context"] = "CREATED"

                stage = "graphite-recorder"
                context.makeRecorder().use { recorder ->
                    evidence["graphite.recorder"] = "CREATED"

                    stage = "graphite-record-submit"
                    recorder.snap().use { recording ->
                        context.insertRecording(recording)
                        context.submit(syncCpu = true)
                    }
                    evidence["graphite.submit"] = "PASS"
                }
            }
        } finally {
            vkDeviceWaitIdle(vk.device)
            vkDestroyDevice(vk.device, null)
            vkDestroyInstance(vk.instance, null)
        }

        evidence["result"] = "RUNTIME_CAPABLE"
        evidence["failure.stage"] = "none"
        writeEvidence(resultFile, evidence)
        println("GRAPHITE_RUNTIME_RESULT PASS")
        evidence.forEach { (key, value) -> println("$key=$value") }
    } catch (t: Throwable) {
        evidence["result"] = "FAIL"
        evidence["failure.stage"] = stage
        evidence["failure.type"] = t::class.qualifiedName ?: t::class.simpleName.orEmpty()
        evidence["failure.message"] = sanitize(t.message ?: "no message")
        writeEvidence(resultFile, evidence)
        println("GRAPHITE_RUNTIME_RESULT FAIL stage=$stage type=${evidence["failure.type"]}")
        throw t
    }
}

private fun createVulkanObjects(): VulkanObjects {
    MemoryStack.stackPush().use { stack ->
        val appInfo = VkApplicationInfo.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_APPLICATION_INFO)
            .pApplicationName(stack.UTF8("lets-plot-graphite-runtime-probe"))
            .applicationVersion(1)
            .pEngineName(stack.UTF8("none"))
            .engineVersion(1)
            .apiVersion(VK_API_VERSION_1_0)

        val instanceCreateInfo = VkInstanceCreateInfo.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO)
            .pApplicationInfo(appInfo)

        val instancePtr = stack.mallocPointer(1)
        checkVk(
            vkCreateInstance(instanceCreateInfo, null, instancePtr),
            "vkCreateInstance"
        )
        val instance = VkInstance(instancePtr[0], instanceCreateInfo)

        try {
            val physicalDeviceCount = stack.ints(0)
            checkVk(vkEnumeratePhysicalDevices(instance, physicalDeviceCount, null), "vkEnumeratePhysicalDevices(count)")
            check(physicalDeviceCount[0] > 0) { "No Vulkan physical device is available on this Windows runner" }

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
                val deviceResult = vkCreateDevice(physicalDevice, deviceCreateInfo, null, devicePtr)
                if (deviceResult != VK_SUCCESS) continue

                val device = VkDevice(devicePtr[0], physicalDevice, deviceCreateInfo)
                val queuePtr = stack.mallocPointer(1)
                vkGetDeviceQueue(device, graphicsQueueIndex, 0, queuePtr)
                val queue = VkQueue(queuePtr[0], device)

                val properties = VkPhysicalDeviceProperties.calloc(stack)
                vkGetPhysicalDeviceProperties(physicalDevice, properties)
                return VulkanObjects(
                    instance = instance,
                    physicalDevice = physicalDevice,
                    device = device,
                    queue = queue,
                    queueFamilyIndex = graphicsQueueIndex,
                    deviceName = properties.deviceNameString(),
                    apiVersion = properties.apiVersion()
                )
            }

            error("No Vulkan physical device with a usable graphics queue/device was found")
        } catch (t: Throwable) {
            vkDestroyInstance(instance, null)
            throw t
        }
    }
}

private fun checkVk(result: Int, operation: String) {
    check(result == VK_SUCCESS) { "$operation failed with Vulkan result $result" }
}

private fun writeEvidence(file: File, values: Map<String, String>) {
    file.writeText(values.entries.joinToString(separator = "\n", postfix = "\n") { (key, value) -> "$key=$value" })
}

private fun sanitize(value: String): String =
    value.replace('\n', ' ').replace('\r', ' ').take(500)
