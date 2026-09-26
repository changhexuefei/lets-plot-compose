package probe

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.delay
import org.jetbrains.skia.Image
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.Robot
import java.awt.image.BufferedImage
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.system.exitProcess

private const val SOURCE_WIDTH = 800
private const val SOURCE_HEIGHT = 550
private const val PIXEL_DELTA_THRESHOLD = 70
private const val MAX_PIXEL_DIFFERENCE_RATIO = 0.15

private val completed = AtomicBoolean(false)

fun main() {
    val outputDir = File(
        System.getenv("GRAPHITE_COMPOSE_PROBE_OUTPUT_DIR")
            ?: "graphite-compose-offscreen-integration-probe/build/probe"
    ).apply { mkdirs() }

    val resultFile = outputDir.resolve("graphite-compose-offscreen-integration.txt")
    val screenshotFile = outputDir.resolve("compose-native-canvas.png")
    val expectedFile = outputDir.resolve("compose-native-canvas-expected.png")

    val sourceFile = System.getenv("GRAPHITE_COMPOSE_SOURCE_PNG")
        ?.takeIf { it.isNotBlank() }
        ?.let(::File)
        ?: error("GRAPHITE_COMPOSE_SOURCE_PNG is required")

    require(sourceFile.isFile) {
        "Graphite offscreen source PNG does not exist: ${sourceFile.absolutePath}"
    }

    val sourceBuffered = ImageIO.read(sourceFile)
        ?: error("Unable to decode Graphite offscreen source PNG: ${sourceFile.absolutePath}")

    require(sourceBuffered.width == SOURCE_WIDTH && sourceBuffered.height == SOURCE_HEIGHT) {
        "Unexpected Graphite offscreen source size: ${sourceBuffered.width}x${sourceBuffered.height}"
    }

    val sourceSha256 = sha256(sourceFile)
    val sourceImage = Image.makeFromEncoded(sourceFile.readBytes())
    val drawObserved = AtomicBoolean(false)
    val nativeCanvasObserved = AtomicBoolean(false)

    val baseEvidence = linkedMapOf(
        "schema" to "1",
        "probe.type" to "graphite-compose-offscreen-integration",
        "source.provenance" to "GRAPHITE_OFFSCREEN_ARTIFACT",
        "source.sha256" to sourceSha256,
        "source.width" to sourceBuffered.width.toString(),
        "source.height" to sourceBuffered.height.toString(),
        "offscreen.transfer" to "CPU_READBACK",
        "compose.version" to "1.13.0-alpha01",
        "skiko.version" to (System.getenv("GRAPHITE_SKIKO_VERSION") ?: "unknown"),
        "compose.window" to "REQUESTED",
        "compose.canvas" to "REQUESTED",
        "compose.native_canvas" to "REQUESTED",
        "compose.native_canvas.draw_image" to "REQUESTED",
        "compose.screen_capture" to "REQUESTED",
        "production.renderer.switch" to "NOT_IMPLEMENTED",
        "renderer.adoption" to "NOT_STARTED"
    )

    Thread.setDefaultUncaughtExceptionHandler { _, throwable ->
        recordFailure(resultFile, baseEvidence, "uncaught", throwable)
        sourceImage.close()
        exitProcess(42)
    }

    Thread(
        {
            Thread.sleep(30_000)
            if (completed.compareAndSet(false, true)) {
                val evidence = LinkedHashMap(baseEvidence)
                evidence["result"] = "FAIL"
                evidence["failure.stage"] = "watchdog"
                evidence["failure.type"] = "TIMEOUT"
                evidence["failure.message"] = "Compose nativeCanvas probe did not complete within 30 seconds"
                writeResult(resultFile, evidence)
                sourceImage.close()
                exitProcess(43)
            }
        },
        "graphite-compose-offscreen-watchdog"
    ).apply {
        isDaemon = true
        start()
    }

    try {
        application {
            val state = rememberWindowState(width = SOURCE_WIDTH.dp, height = SOURCE_HEIGHT.dp)

            Window(
                onCloseRequest = ::exitApplication,
                state = state,
                title = "Graphite Compose Offscreen Integration Probe",
                undecorated = true,
                resizable = false
            ) {
                val composeWindow = window

                Canvas(Modifier.fillMaxSize()) {
                    drawRect(Color.White)
                    drawIntoCanvas { composeCanvas ->
                        val native = composeCanvas.nativeCanvas
                        nativeCanvasObserved.set(true)

                        val scaleX = size.width / sourceImage.width.toFloat()
                        val scaleY = size.height / sourceImage.height.toFloat()
                        native.save()
                        native.scale(scaleX, scaleY)
                        native.drawImage(sourceImage, 0f, 0f)
                        native.restore()

                        drawObserved.set(true)
                    }
                }

                LaunchedEffect(Unit) {
                    try {
                        repeat(150) {
                            if (drawObserved.get() && nativeCanvasObserved.get() && composeWindow.isShowing) {
                                delay(1_500)

                                composeWindow.toFront()
                                delay(250)

                                val location = composeWindow.locationOnScreen
                                val size = composeWindow.size
                                require(size.width > 0 && size.height > 0) {
                                    "Compose window has invalid size: ${size.width}x${size.height}"
                                }

                                val robot = Robot(composeWindow.graphicsConfiguration.device)
                                val screenshot = robot.createScreenCapture(
                                    Rectangle(location.x, location.y, size.width, size.height)
                                )
                                check(ImageIO.write(screenshot, "png", screenshotFile)) {
                                    "Failed to write Compose nativeCanvas screenshot"
                                }

                                val expected = scaleBufferedImage(sourceBuffered, screenshot.width, screenshot.height)
                                check(ImageIO.write(expected, "png", expectedFile)) {
                                    "Failed to write expected Compose nativeCanvas image"
                                }

                                val difference = pixelDifferenceRatio(expected, screenshot)
                                check(difference <= MAX_PIXEL_DIFFERENCE_RATIO) {
                                    "Compose nativeCanvas output diverged from Graphite offscreen input: " +
                                        "difference=$difference max=$MAX_PIXEL_DIFFERENCE_RATIO"
                                }

                                if (completed.compareAndSet(false, true)) {
                                    val evidence = LinkedHashMap(baseEvidence)
                                    evidence["result"] = "COMPOSE_OFFSCREEN_INTEGRATION_CAPABLE"
                                    evidence["compose.window"] = "STARTED"
                                    evidence["compose.window.size"] = "${screenshot.width}x${screenshot.height}"
                                    evidence["compose.canvas"] = "PASS"
                                    evidence["compose.native_canvas"] = "PASS"
                                    evidence["compose.native_canvas.draw_image"] = "PASS"
                                    evidence["compose.screen_capture"] = "PASS"
                                    evidence["comparison.pixel_difference_ratio"] = difference.toString()
                                    evidence["comparison.max_allowed_ratio"] = MAX_PIXEL_DIFFERENCE_RATIO.toString()
                                    evidence["comparison"] = "PASS"
                                    evidence["failure.stage"] = "none"
                                    writeResult(resultFile, evidence)

                                    println("GRAPHITE_COMPOSE_OFFSCREEN_RESULT PASS")
                                    evidence.forEach { (key, value) -> println("$key=$value") }
                                }

                                exitApplication()
                                return@LaunchedEffect
                            }
                            delay(100)
                        }

                        error("Compose Canvas/nativeCanvas draw was not observed")
                    } catch (throwable: Throwable) {
                        recordFailure(resultFile, baseEvidence, "compose-capture", throwable)
                        exitApplication()
                        throw throwable
                    }
                }
            }
        }
    } catch (throwable: Throwable) {
        recordFailure(resultFile, baseEvidence, "application", throwable)
        throw throwable
    } finally {
        sourceImage.close()
    }
}

private fun scaleBufferedImage(source: BufferedImage, width: Int, height: Int): BufferedImage {
    if (source.width == width && source.height == height) {
        val copy = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val graphics = copy.createGraphics()
        try {
            graphics.drawImage(source, 0, 0, null)
        } finally {
            graphics.dispose()
        }
        return copy
    }

    val scaled = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    val graphics = scaled.createGraphics()
    try {
        graphics.setRenderingHint(
            RenderingHints.KEY_INTERPOLATION,
            RenderingHints.VALUE_INTERPOLATION_BILINEAR
        )
        graphics.drawImage(source, 0, 0, width, height, null)
    } finally {
        graphics.dispose()
    }
    return scaled
}

private fun pixelDifferenceRatio(expected: BufferedImage, actual: BufferedImage): Double {
    require(expected.width == actual.width && expected.height == actual.height)

    var changed = 0L
    val total = expected.width.toLong() * expected.height.toLong()

    for (y in 0 until expected.height) {
        for (x in 0 until expected.width) {
            val left = expected.getRGB(x, y)
            val right = actual.getRGB(x, y)
            val delta =
                abs(channel(left, 16) - channel(right, 16)) +
                    abs(channel(left, 8) - channel(right, 8)) +
                    abs(channel(left, 0) - channel(right, 0))
            if (delta > PIXEL_DELTA_THRESHOLD) changed++
        }
    }

    return changed.toDouble() / total.toDouble()
}

private fun channel(color: Int, shift: Int): Int = (color ushr shift) and 0xFF

private fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count > 0) digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

private fun recordFailure(
    resultFile: File,
    baseEvidence: Map<String, String>,
    stage: String,
    throwable: Throwable
) {
    if (!completed.compareAndSet(false, true)) return

    val evidence = LinkedHashMap(baseEvidence)
    evidence["result"] = "FAIL"
    evidence["failure.stage"] = stage
    evidence["failure.type"] = throwable::class.qualifiedName ?: throwable::class.simpleName.orEmpty()
    evidence["failure.message"] = sanitize(
        generateSequence(throwable) { it.cause }
            .take(8)
            .joinToString(" | ") { it.toString() }
    )
    writeResult(resultFile, evidence)
    println("GRAPHITE_COMPOSE_OFFSCREEN_RESULT FAIL stage=$stage")
}

private fun writeResult(file: File, values: Map<String, String>) {
    file.parentFile.mkdirs()
    file.writeText(
        values.entries.joinToString(
            separator = "\n",
            postfix = "\n"
        ) { (key, value) -> "$key=$value" }
    )
}

private fun sanitize(value: String): String =
    value.replace('\n', ' ').replace('\r', ' ').take(1200)
