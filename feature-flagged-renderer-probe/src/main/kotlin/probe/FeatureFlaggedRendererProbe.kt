package probe

import org.jetbrains.letsPlot.commons.geometry.DoubleVector
import org.jetbrains.letsPlot.commons.values.Color
import org.jetbrains.letsPlot.compose.DESKTOP_RENDERER_ENVIRONMENT_VARIABLE
import org.jetbrains.letsPlot.compose.DESKTOP_RENDERER_SYSTEM_PROPERTY
import org.jetbrains.letsPlot.compose.EXPERIMENTAL_OFFSCREEN_RASTER_RENDERER
import org.jetbrains.letsPlot.compose.DesktopPlotRendererMode
import org.jetbrains.letsPlot.compose.paintPlotOnDesktopCanvas
import org.jetbrains.letsPlot.compose.resolveDesktopPlotRendererMode
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Surface
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs

private const val WIDTH = 320
private const val HEIGHT = 220
private const val WHITE = -1
private const val MAX_PIXEL_DIFFERENCE_RATIO = 0.001
private const val PIXEL_DELTA_THRESHOLD = 8

fun main() {
    val outputDir = File(
        System.getenv("FEATURE_FLAGGED_RENDERER_PROBE_OUTPUT_DIR")
            ?: "feature-flagged-renderer-probe/build/probe"
    ).apply { mkdirs() }

    val resultFile = outputDir.resolve("feature-flagged-renderer.txt")
    val directPng = outputDir.resolve("direct-skia.png")
    val offscreenPng = outputDir.resolve("experimental-offscreen-raster.png")

    var stage = "startup"
    val evidence = linkedMapOf(
        "schema" to "1",
        "probe.type" to "feature-flagged-desktop-renderer",
        "renderer.system_property" to DESKTOP_RENDERER_SYSTEM_PROPERTY,
        "renderer.environment_variable" to DESKTOP_RENDERER_ENVIRONMENT_VARIABLE,
        "renderer.experimental.flag" to EXPERIMENTAL_OFFSCREEN_RASTER_RENDERER,
        "platform" to System.getProperty("os.name"),
        "java.version" to System.getProperty("java.version"),
        "skiko.version" to (System.getenv("GRAPHITE_SKIKO_VERSION") ?: "unknown"),
        "production.graphite.dependency" to "ABSENT",
        "renderer.adoption" to "OPT_IN_ONLY"
    )

    try {
        stage = "default-resolution"
        check(
            resolveDesktopPlotRendererMode(
                systemPropertyValue = null,
                environmentValue = null
            ) == DesktopPlotRendererMode.DIRECT_SKIA
        )
        evidence["renderer.default"] = "DIRECT_SKIA"
        evidence["default.behavior.unchanged"] = "PASS"

        stage = "flag-resolution"
        check(
            resolveDesktopPlotRendererMode(
                systemPropertyValue = EXPERIMENTAL_OFFSCREEN_RASTER_RENDERER,
                environmentValue = null
            ) == DesktopPlotRendererMode.EXPERIMENTAL_OFFSCREEN_RASTER
        )
        evidence["renderer.flagged"] = "EXPERIMENTAL_OFFSCREEN_RASTER"

        stage = "precedence-resolution"
        check(
            resolveDesktopPlotRendererMode(
                systemPropertyValue = "direct-skia",
                environmentValue = EXPERIMENTAL_OFFSCREEN_RASTER_RENDERER
            ) == DesktopPlotRendererMode.DIRECT_SKIA
        )
        evidence["renderer.system_property_precedence"] = "PASS"

        stage = "unknown-fallback"
        check(
            resolveDesktopPlotRendererMode(
                systemPropertyValue = "unknown-renderer",
                environmentValue = null
            ) == DesktopPlotRendererMode.DIRECT_SKIA
        )
        evidence["renderer.unknown_flag_fallback"] = "DIRECT_SKIA"

        stage = "direct-render"
        val directPixels = render(DesktopPlotRendererMode.DIRECT_SKIA)
        savePixels(directPixels, directPng)
        evidence.putAll(assertScene("direct", directPixels))
        evidence["direct.paint"] = "PASS"

        stage = "offscreen-render"
        val offscreenPixels = render(DesktopPlotRendererMode.EXPERIMENTAL_OFFSCREEN_RASTER)
        savePixels(offscreenPixels, offscreenPng)
        evidence.putAll(assertScene("offscreen", offscreenPixels))
        evidence["offscreen.paint"] = "PASS"

        stage = "comparison"
        val difference = pixelDifferenceRatio(directPixels, offscreenPixels)
        evidence["comparison.pixel_difference_ratio"] = difference.toString()
        evidence["comparison.max_allowed_ratio"] = MAX_PIXEL_DIFFERENCE_RATIO.toString()
        check(difference <= MAX_PIXEL_DIFFERENCE_RATIO) {
            "Feature-flagged offscreen output diverged from Direct Skia: " +
                "difference=$difference max=$MAX_PIXEL_DIFFERENCE_RATIO"
        }
        evidence["comparison"] = "PASS"

        evidence["result"] = "FEATURE_FLAGGED_RENDERER_CAPABLE"
        evidence["failure.stage"] = "none"
        writeEvidence(resultFile, evidence)

        println("FEATURE_FLAGGED_RENDERER_RESULT PASS")
        evidence.forEach { (key, value) -> println("$key=$value") }
    } catch (t: Throwable) {
        evidence["result"] = "FAIL"
        evidence["failure.stage"] = stage
        evidence["failure.type"] = t::class.qualifiedName ?: t::class.simpleName.orEmpty()
        evidence["failure.message"] = sanitize(t.message ?: "no message")
        writeEvidence(resultFile, evidence)
        println("FEATURE_FLAGGED_RENDERER_RESULT FAIL stage=$stage")
        throw t
    }
}

private fun render(mode: DesktopPlotRendererMode): IntArray {
    Surface.makeRasterN32Premul(WIDTH, HEIGHT).use { surface ->
        surface.canvas.clear(WHITE)

        paintPlotOnDesktopCanvas(
            canvas = surface.canvas,
            width = WIDTH,
            height = HEIGHT,
            density = 1.25,
            plotPosition = DoubleVector(18.0, 14.0),
            mode = mode
        ) { context ->
            context.setFillStyle(Color.RED)
            context.fillRect(0.0, 0.0, 52.0, 34.0)

            context.setFillStyle(Color.BLUE)
            context.fillRect(68.0, 22.0, 38.0, 46.0)

            context.setFillStyle(Color.GREEN)
            context.fillRect(118.0, 8.0, 26.0, 22.0)
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

private fun assertScene(prefix: String, pixels: IntArray): Map<String, String> {
    val red = pixels.count { color ->
        channel(color, 16) > 180 && channel(color, 8) < 100 && channel(color, 0) < 100
    }
    val blue = pixels.count { color ->
        channel(color, 0) > 160 && channel(color, 16) < 120 && channel(color, 8) < 160
    }
    val green = pixels.count { color ->
        channel(color, 8) > 100 && channel(color, 16) < 160 && channel(color, 0) < 160
    }

    check(red > 500) { "$prefix red scene coverage too low: $red" }
    check(blue > 500) { "$prefix blue scene coverage too low: $blue" }
    check(green > 200) { "$prefix green scene coverage too low: $green" }

    return mapOf(
        "$prefix.structure" to "PASS",
        "$prefix.red_pixels" to red.toString(),
        "$prefix.blue_pixels" to blue.toString(),
        "$prefix.green_pixels" to green.toString()
    )
}

private fun pixelDifferenceRatio(left: IntArray, right: IntArray): Double {
    check(left.size == right.size)
    var changed = 0

    for (index in left.indices) {
        val a = left[index]
        val b = right[index]
        val delta =
            abs(channel(a, 24) - channel(b, 24)) +
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

private fun writeEvidence(file: File, values: Map<String, String>) {
    file.parentFile.mkdirs()
    file.writeText(values.entries.joinToString(separator = "\n", postfix = "\n") { (key, value) -> "$key=$value" })
}

private fun sanitize(value: String): String =
    value.replace('\n', ' ').replace('\r', ' ').take(1200)
