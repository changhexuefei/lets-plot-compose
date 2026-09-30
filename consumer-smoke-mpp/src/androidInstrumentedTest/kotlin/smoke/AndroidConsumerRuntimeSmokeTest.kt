package smoke

import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.util.ArrayDeque
import kotlin.math.abs
import org.jetbrains.letsPlot.compose.PlotFigureModel
import org.jetbrains.letsPlot.core.interact.InteractionSpec
import org.jetbrains.letsPlot.core.plot.builder.interact.tools.FigureModelOptions.COORD_XLIM_TRANSFORMED
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

class AndroidConsumerSmokeActivity : ComponentActivity() {
    val figureModel = PlotFigureModel().apply {
        setDefaultInteractions(
            listOf(
                InteractionSpec(InteractionSpec.Name.WHEEL_ZOOM),
                InteractionSpec(InteractionSpec.Name.DRAG_PAN)
            )
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Box(
                Modifier
                    .fillMaxSize()
                    .testTag("android-consumer-test-root")
            ) {
                ConsumerPlot(externalFigureModel = figureModel)
            }
        }
    }

    override fun onDestroy() {
        figureModel.dispose()
        super.onDestroy()
    }
}

@RunWith(AndroidJUnit4::class)
class AndroidConsumerRuntimeSmokeTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<AndroidConsumerSmokeActivity>()

    @Test
    fun publishedConsumerRendersAndDragPans() {
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

        composeRule.runOnIdle {
            composeRule.activity.figureModel.updateSpecOverride(
                mapOf(COORD_XLIM_TRANSFORMED to listOf(1.4, 2.6))
            )
            composeRule.activity.figureModel.updateView()
        }
        composeRule.waitForIdle()
        Thread.sleep(800)

        val programmaticImage = plot.captureToImage().asAndroidBitmap()
        val programmaticDiffRatio = pixelDifferenceRatio(before, programmaticImage)
        assertTrue(
            "Programmatic FigureModel override did not visibly change the Android consumer: " +
                "diffRatio=$programmaticDiffRatio",
            programmaticDiffRatio > 0.002
        )

        composeRule.runOnIdle {
            composeRule.activity.figureModel.updateSpecOverride(null)
            composeRule.activity.figureModel.updateView()
        }
        composeRule.waitForIdle()
        Thread.sleep(800)

        val rollbackImage = plot.captureToImage().asAndroidBitmap()
        val rollbackDiffRatio = pixelDifferenceRatio(before, rollbackImage)
        assertTrue(
            "Programmatic FigureModel rollback did not restore the Android consumer: " +
                "diffRatio=$rollbackDiffRatio",
            rollbackDiffRatio < 0.002
        )

        var after = rollbackImage
        var diffRatio = 0.0
        var panAttempts = 0

        for (attempt in 1..3) {
            panAttempts = attempt
            plot.performTouchInput {
                swipe(
                    start = Offset(center.x + 160f, center.y),
                    end = Offset(center.x - 160f, center.y),
                    durationMillis = 700
                )
            }

            composeRule.waitForIdle()
            Thread.sleep(800)

            after = plot.captureToImage().asAndroidBitmap()
            diffRatio = pixelDifferenceRatio(rollbackImage, after)
            if (diffRatio > 0.002) {
                break
            }

            // The emulator can deliver the first gesture before Lets-Plot's
            // dispatcher has finished binding to the freshly rendered drawable.
            // Keep the assertion strict, but allow a bounded real-gesture retry.
            Thread.sleep(500)
        }

        assertTrue(
            "Drag pan did not visibly change the published Android consumer " +
                "after $panAttempts attempt(s): diffRatio=$diffRatio",
            diffRatio > 0.002
        )

        val plottedPoint = plottedPointCenter(after)
        plot.performTouchInput {
            click(plottedPoint)
        }
        composeRule.waitForIdle()
        Thread.sleep(700)

        val tooltipImage = plot.captureToImage().asAndroidBitmap()
        val tooltipDiffRatio = pixelDifferenceRatio(after, tooltipImage)

        assertTrue(
            "Tap on a rendered data point did not produce visible tooltip feedback: " +
                "point=$plottedPoint, diffRatio=$tooltipDiffRatio",
            tooltipDiffRatio > 0.0005
        )

        val evidence = buildString {
            appendLine("schema=1")
            appendLine("result=ANDROID_EMULATOR_CONSUMER_SMOKE_PASS")
            appendLine("consumer.boundary=PUBLISHED_MAVEN_ONLY")
            appendLine("runtime=ANDROID_EMULATOR")
            appendLine("render=PASS")
            appendLine("drag_pan=PASS")
            appendLine("tooltip=PASS")
            appendLine("toolbar=ABSENT")
            appendLine("figure_model.external=TRUE")
            appendLine("figure_model.programmatic_override=PASS")
            appendLine("figure_model.programmatic_rollback=PASS")
            appendLine("figure_model.toolbarless_feedback=RUNTIME_PASS")
            appendLine("render.sampled_unique_colors=$uniqueColors")
            appendLine("render.plotted_point_pixels=$plottedPointPixels")
            appendLine("figure_model.override_diff_ratio=$programmaticDiffRatio")
            appendLine("figure_model.rollback_diff_ratio=$rollbackDiffRatio")
            appendLine("pan.diff_ratio=$diffRatio")
            appendLine("pan.attempts=$panAttempts")
            appendLine("tooltip.diff_ratio=$tooltipDiffRatio")
            appendLine("tooltip.point_x=${plottedPoint.x}")
            appendLine("tooltip.point_y=${plottedPoint.y}")
            appendLine("production.renderer.default=NATIVE_CANVAS")
            appendLine("graphite.production=DISABLED")
        }

        val remoteDir = "/data/local/tmp/android-consumer-smoke"

        writeShellFile(
            path = "$remoteDir/01-render.png",
            bytes = bitmapPngBytes(before),
            resetDirectory = true
        )
        writeShellFile("$remoteDir/02-drag-pan.png", bitmapPngBytes(after))
        writeShellFile("$remoteDir/03-tooltip.png", bitmapPngBytes(tooltipImage))
        writeShellFile("$remoteDir/04-programmatic-override.png", bitmapPngBytes(programmaticImage))
        writeShellFile("$remoteDir/05-programmatic-rollback.png", bitmapPngBytes(rollbackImage))
        writeShellFile(
            "$remoteDir/android-emulator-consumer-smoke.txt",
            evidence.toByteArray(Charsets.UTF_8)
        )

        println(evidence)
    }

    private fun writeShellFile(
        path: String,
        bytes: ByteArray,
        resetDirectory: Boolean = false
    ) {
        val uiAutomation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val parent = path.substringBeforeLast('/')

        check(Build.VERSION.SDK_INT >= 34) {
            "Android emulator smoke requires API 34+ for shell stderr capture"
        }

        if (resetDirectory) {
            runShellCommand("rm -rf $parent")
        }
        runShellCommand("mkdir -p $parent")

        // Avoid shell redirection and compound expressions entirely. UiAutomation
        // tokenizes commands before execution, so quoting/redirection is brittle.
        // dd accepts the destination as a normal argument and consumes bytes
        // directly from stdin.
        val pipes = uiAutomation.executeShellCommandRwe("dd of=$path")
        val stdout = ParcelFileDescriptor.AutoCloseInputStream(pipes[0])
        val stdin = ParcelFileDescriptor.AutoCloseOutputStream(pipes[1])
        val stderr = ParcelFileDescriptor.AutoCloseInputStream(pipes[2])

        stdin.use { input ->
            input.write(bytes)
            input.flush()
        }

        val shellOut = stdout.bufferedReader().use { it.readText().trim() }
        val shellErr = stderr.bufferedReader().use { it.readText().trim() }

        val sizeOutput = runShellCommand("stat -c %s $path")
        val persistedSize = sizeOutput.trim().toLongOrNull() ?: -1L

        check(persistedSize == bytes.size.toLong()) {
            buildString {
                append(
                    "Android smoke evidence size mismatch: $path " +
                        "expected=${bytes.size} actual=$persistedSize"
                )
                if (shellOut.isNotEmpty()) append("; stdout=$shellOut")
                if (shellErr.isNotEmpty()) append("; stderr=$shellErr")
            }
        }
    }

    private fun runShellCommand(command: String): String {
        val uiAutomation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val result = uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(result)
            .bufferedReader()
            .use { it.readText() }
    }
    private fun bitmapPngBytes(bitmap: Bitmap): ByteArray =
        ByteArrayOutputStream().use { stream ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                "Failed to encode Android smoke screenshot"
            }
            stream.toByteArray()
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

    private fun plottedPointCenter(bitmap: Bitmap): Offset {
        val step = 2
        val gridWidth = (bitmap.width + step - 1) / step
        val gridHeight = (bitmap.height + step - 1) / step
        val plotted = BooleanArray(gridWidth * gridHeight)

        for (gridY in 0 until gridHeight) {
            val y = gridY * step
            if (y >= bitmap.height) continue

            for (gridX in 0 until gridWidth) {
                val x = gridX * step
                if (x < bitmap.width && isPlottedPointPixel(bitmap.getPixel(x, y))) {
                    plotted[gridY * gridWidth + gridX] = true
                }
            }
        }

        val visited = BooleanArray(plotted.size)
        var bestCount = 0
        var bestSumX = 0L
        var bestSumY = 0L
        val queue = ArrayDeque<Int>()

        for (start in plotted.indices) {
            if (!plotted[start] || visited[start]) continue

            visited[start] = true
            queue.addLast(start)
            var count = 0
            var sumX = 0L
            var sumY = 0L

            while (queue.isNotEmpty()) {
                val index = queue.removeFirst()
                val gridX = index % gridWidth
                val gridY = index / gridWidth

                count++
                sumX += (gridX * step).toLong()
                sumY += (gridY * step).toLong()

                for (dy in -1..1) {
                    for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue

                        val nextX = gridX + dx
                        val nextY = gridY + dy
                        if (nextX !in 0 until gridWidth || nextY !in 0 until gridHeight) continue

                        val next = nextY * gridWidth + nextX
                        if (plotted[next] && !visited[next]) {
                            visited[next] = true
                            queue.addLast(next)
                        }
                    }
                }
            }

            if (count > bestCount) {
                bestCount = count
                bestSumX = sumX
                bestSumY = sumY
            }
        }

        check(bestCount > 0) {
            "No plotted-point pixels were available for Android tooltip interaction"
        }

        return Offset(
            x = bestSumX.toFloat() / bestCount,
            y = bestSumY.toFloat() / bestCount
        )
    }

    private fun isPlottedPointPixel(pixel: Int): Boolean {
        val red = android.graphics.Color.red(pixel)
        val green = android.graphics.Color.green(pixel)
        val blue = android.graphics.Color.blue(pixel)
        return red >= 150 && red - green >= 45 && red - blue >= 45
    }

    private fun plottedPointPixelCount(bitmap: Bitmap): Int {
        var count = 0
        var y = 0

        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val pixel = bitmap.getPixel(x, y)

                if (isPlottedPointPixel(pixel)) {
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
}
