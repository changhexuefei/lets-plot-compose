package probe

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import org.jetbrains.skia.gpu.graphite.GraphiteContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.system.exitProcess
import kotlinx.coroutines.delay

private val completed = AtomicBoolean(false)

fun main() {
    val outputDir = File(
        System.getenv("GRAPHITE_COMPOSE_PROBE_OUTPUT_DIR")
            ?: "graphite-compose-integration-probe/build/probe"
    ).apply { mkdirs() }
    val resultFile = outputDir.resolve("graphite-compose-integration.txt")
    val drawObserved = AtomicBoolean(false)

    // Force the Graphite API class onto this runtime classpath without creating a GPU context.
    val graphiteClass = GraphiteContext::class.java.name

    Thread.setDefaultUncaughtExceptionHandler { _, throwable ->
        recordFailure(resultFile, throwable, graphiteClass)
        exitProcess(42)
    }

    thread(name = "graphite-compose-probe-watchdog", isDaemon = true) {
        Thread.sleep(20_000)
        if (completed.compareAndSet(false, true)) {
            writeResult(
                resultFile,
                mapOf(
                    "result" to "UNEXPECTED_FAILURE",
                    "failure.kind" to "TIMEOUT",
                    "failure.message" to "Compose probe did not complete within 20 seconds",
                    "graphite.class" to graphiteClass
                )
            )
            exitProcess(43)
        }
    }

    try {
        application {
            Window(
                onCloseRequest = ::exitApplication,
                title = "Graphite Compose Integration Probe"
            ) {
                Column(Modifier.fillMaxSize().padding(24.dp)) {
                    Text("Compose 1.13 preview + Skiko Graphite 0.153 integration probe")
                    Canvas(Modifier.size(320.dp, 180.dp)) {
                        drawRect(Color(0xFF3366CC))
                        drawCircle(Color(0xFFCC3333), radius = 28f, center = center)
                        drawObserved.set(true)
                    }
                }

                LaunchedEffect(Unit) {
                    repeat(120) {
                        if (drawObserved.get()) {
                            // Leave enough time for text/Skia drawing to execute on the UI thread.
                            delay(1_200)
                            exitApplication()
                            return@LaunchedEffect
                        }
                        delay(100)
                    }
                    error("Compose Canvas draw was not observed")
                }
            }
        }

        if (completed.compareAndSet(false, true)) {
            writeResult(
                resultFile,
                mapOf(
                    "result" to "READY_CANDIDATE",
                    "compose.window" to "STARTED",
                    "compose.canvas.draw" to drawObserved.get().toString(),
                    "graphite.class" to graphiteClass,
                    "graphite.context.creation" to "NOT_ATTEMPTED",
                    "plotpanel.integration" to "NOT_ATTEMPTED",
                    "renderer.adoption" to "NOT_STARTED"
                )
            )
            println("GRAPHITE_COMPOSE_RESULT READY_CANDIDATE")
        }
    } catch (throwable: Throwable) {
        recordFailure(resultFile, throwable, graphiteClass)
        throw throwable
    }
}

private fun recordFailure(resultFile: File, throwable: Throwable, graphiteClass: String) {
    if (!completed.compareAndSet(false, true)) return

    val chain = throwableChain(throwable)
    val knownAbiMismatch = chain.any { current ->
        current is NoSuchMethodError &&
            (current.message?.contains("FontStyle", ignoreCase = true) == true)
    }
    val result = if (knownAbiMismatch) "EXPECTED_INCOMPATIBLE" else "UNEXPECTED_FAILURE"

    writeResult(
        resultFile,
        mapOf(
            "result" to result,
            "failure.kind" to (throwable::class.qualifiedName ?: throwable::class.simpleName.orEmpty()),
            "failure.message" to sanitize(chain.joinToString(" | ") { it.toString() }),
            "known.abi.mismatch" to knownAbiMismatch.toString(),
            "graphite.class" to graphiteClass,
            "graphite.context.creation" to "NOT_ATTEMPTED",
            "plotpanel.integration" to "NOT_ATTEMPTED",
            "renderer.adoption" to "NOT_STARTED"
        )
    )
    println("GRAPHITE_COMPOSE_RESULT $result")
}

private fun throwableChain(root: Throwable): List<Throwable> {
    val result = mutableListOf<Throwable>()
    var current: Throwable? = root
    while (current != null && current !in result) {
        result += current
        current = current.cause
    }
    return result
}

private fun writeResult(file: File, values: Map<String, Any>) {
    file.parentFile.mkdirs()
    file.writeText(values.entries.joinToString(separator = "\n", postfix = "\n") { (key, value) -> "$key=$value" })
}

private fun sanitize(value: String): String =
    value.replace('\n', ' ').replace('\r', ' ').take(1000)
