package probe

import org.jetbrains.skia.Surface
import org.jetbrains.skia.gpu.graphite.BackendTexture
import org.jetbrains.skia.gpu.graphite.GraphiteContext
import org.jetbrains.skia.gpu.graphite.Recorder
import org.jetbrains.skia.gpu.graphite.Recording
import org.jetbrains.skia.gpu.graphite.wrapBackendTexture
import org.jetbrains.skiko.ExperimentalSkikoApi

/**
 * Compile-only proof that the Skiko 0.153.x Graphite JVM surface needed by a
 * future Lets-Plot Compose renderer is available.
 *
 * Nothing here creates a GPU context or loads a Graphite native runtime.
 */
@OptIn(ExperimentalSkikoApi::class)
object GraphiteApiAvailabilityProbe {
    fun makeRecorder(context: GraphiteContext): Recorder = context.makeRecorder()

    fun snap(recorder: Recorder): Recording = recorder.snap()

    fun submit(context: GraphiteContext, recording: Recording) {
        context.insertRecording(recording)
        context.submit(syncCpu = false)
    }

    fun wrapSurface(
        recorder: Recorder,
        backendTexture: BackendTexture
    ): Surface? = Surface.wrapBackendTexture(
        recorder = recorder,
        backendTexture = backendTexture,
        colorSpace = null
    )
}
