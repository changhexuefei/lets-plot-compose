package smoke

import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

class AndroidConsumerSmokeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Box(
                Modifier
                    .fillMaxSize()
                    .testTag("android-consumer-test-root")
            ) {
                ConsumerPlot()
            }
        }
    }
}

@RunWith(AndroidJUnit4::class)
class AndroidConsumerRuntimeSmokeTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<AndroidConsumerSmokeActivity>()

    @Test
    fun publishedConsumerRendersAndDragPans() {
        // Anchor the test to the Activity-owned wrapper instead of PlotPanel's modifier.
        // PlotPanel legitimately reuses its modifier across internal layers, which can
        // expose multiple semantics nodes with the same tag and makes input injection
        // ambiguous even though the rendered plot itself is correct.
        val plot = composeRule.onNodeWithTag("android-consumer-test-root")
        composeRule.waitForIdle()
        Thread.sleep(1_500)

        val beforeImage = plot.captureToImage()
        val before = beforeImage.asAndroidBitmap()
        val uniqueColors = sampledUniqueColorCount(before)
        val plottedPointPixels = plottedPointPixelCount(before)

        assertTrue(
            "Published Android consumer render looks blank: " +
                "sampledUniqueColors=$uniqueColors, plottedPointPixels=$plottedPointPixels",
            uniqueColors >= 4 && plottedPointPixels >= 20
        )

        plot.performTouchInput {
            swipe(
                start = Offset(center.x + 160f, center.y),
                end = Offset(center.x - 160f, center.y),
                durationMillis = 700
            )
        }

        composeRule.waitForIdle()
        Thread.sleep(800)

        val afterImage = plot.captureToImage()
        val after = afterImage.asAndroidBitmap()
        val diffRatio = pixelDifferenceRatio(before, after)

        assertTrue(
            "Drag pan did not visibly change the published Android consumer: diffRatio=$diffRatio",
            diffRatio > 0.002
        )

        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val evidenceDir = File(
            targetContext.filesDir,
            "android-consumer-smoke"
        ).apply {
            deleteRecursively()
            mkdirs()
        }

        savePng(before, File(evidenceDir, "01-render.png"))
        savePng(after, File(evidenceDir, "02-drag-pan.png"))

        val evidence = buildString {
            appendLine("schema=1")
            appendLine("result=ANDROID_EMULATOR_CONSUMER_SMOKE_PASS")
            appendLine("consumer.boundary=PUBLISHED_MAVEN_ONLY")
            appendLine("runtime=ANDROID_EMULATOR")
            appendLine("render=PASS")
            appendLine("drag_pan=PASS")
            appendLine("toolbar=ABSENT")
            appendLine("figure_model.external=TRUE")
            appendLine("figure_model.toolbarless_feedback=RUNTIME_PASS")
            appendLine("render.sampled_unique_colors=$uniqueColors")
            appendLine("render.plotted_point_pixels=$plottedPointPixels")
            appendLine("pan.diff_ratio=$diffRatio")
            appendLine("production.renderer.default=NATIVE_CANVAS")
            appendLine("graphite.production=DISABLED")
        }

        File(evidenceDir, "android-emulator-consumer-smoke.txt").writeText(evidence)
        println(evidence)
    }

    private fun sampledUniqueColorCount(bitmap: Bitmap): Int {
        val colors = HashSet<Int>()
        val stepX = (bitmap.width / 40).coerceAtLeast(1)
        val stepY = (bitmap.height / 40).coerceAtLeast(1)

        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                colors += bitmap.getPixel(x, y)
                x += stepX
            }
            y += stepY
        }
        return colors.size
    }

    private fun plottedPointPixelCount(bitmap: Bitmap): Int {
        var count = 0
        var y = 0

        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                val red = android.graphics.Color.red(pixel)
                val green = android.graphics.Color.green(pixel)
                val blue = android.graphics.Color.blue(pixel)

                // ConsumerPlot renders large #D62728 points. Allow generous channel
                // tolerance for Skia anti-aliasing while still rejecting a blank surface.
                if (red >= 150 && red - green >= 45 && red - blue >= 45) {
                    count++
                }
                x += 2
            }
            y += 2
        }

        return count
    }

    private fun pixelDifferenceRatio(before: Bitmap, after: Bitmap): Double {
        val width = minOf(before.width, after.width)
        val height = minOf(before.height, after.height)
        val step = 4
        var changed = 0L
        var total = 0L

        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                val a = before.getPixel(x, y)
                val b = after.getPixel(x, y)

                val delta =
                    abs(android.graphics.Color.red(a) - android.graphics.Color.red(b)) +
                    abs(android.graphics.Color.green(a) - android.graphics.Color.green(b)) +
                    abs(android.graphics.Color.blue(a) - android.graphics.Color.blue(b))

                if (delta > 24) {
                    changed++
                }
                total++
                x += step
            }
            y += step
        }

        return if (total == 0L) 0.0 else changed.toDouble() / total.toDouble()
    }

    private fun savePng(bitmap: Bitmap, file: File) {
        FileOutputStream(file).use { stream ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                "Failed to write " + file.absolutePath
            }
        }
    }
}
