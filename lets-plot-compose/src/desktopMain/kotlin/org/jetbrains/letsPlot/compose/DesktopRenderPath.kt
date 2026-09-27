/*
 * Copyright (c) 2026. JetBrains s.r.o.
 * Use of this source code is governed by the MIT license that can be found in the LICENSE file.
 */

package org.jetbrains.letsPlot.compose

import org.jetbrains.letsPlot.commons.geometry.DoubleVector
import org.jetbrains.letsPlot.compose.canvas.SkiaContext2d
import org.jetbrains.letsPlot.commons.registration.Registration
import org.jetbrains.skia.Canvas

internal const val DESKTOP_RENDER_PATH_PROPERTY = "letsplot.compose.desktop.renderPath"

private const val GRAPHITE_RUNTIME_BOOTSTRAP_CLASS =
    "org.jetbrains.letsPlot.compose.graphite.runtime.GraphiteRuntimeBootstrap"

internal const val DESKTOP_OFFSCREEN_FAILURE_PROPERTY =
    "letsplot.compose.desktop.offscreen.failure"
internal const val DESKTOP_OFFSCREEN_FAILURE_COUNT_PROPERTY =
    "letsplot.compose.desktop.offscreen.failureCount"

internal enum class DesktopRenderPath {
    NATIVE_CANVAS,
    OFFSCREEN_COMPOSITE
}

internal fun interface DesktopOffscreenRenderer {
    fun paint(
        targetCanvas: Canvas,
        width: Int,
        height: Int,
        density: Double,
        plotPosition: DoubleVector,
        paint: (SkiaContext2d) -> Unit
    )

    /**
     * Releases resources owned by this provider.
     *
     * The default is a no-op so lightweight/stateless providers remain SAM-compatible.
     * Stateful GPU providers override this to release persistent context/device resources.
     */
    fun dispose() {}
}

internal object DesktopOffscreenRendererRegistry {
    private val lock = Any()
    private var renderer: DesktopOffscreenRenderer? = null
    private val disposedRenderers =
        java.util.Collections.newSetFromMap(
            java.util.IdentityHashMap<DesktopOffscreenRenderer, Boolean>()
        )

    fun install(renderer: DesktopOffscreenRenderer): Registration {
        synchronized(lock) {
            this.renderer = renderer
            disposedRenderers.remove(renderer)
        }

        return Registration.onRemove {
            disposeOnce(renderer)
        }
    }

    fun current(): DesktopOffscreenRenderer? {
        return synchronized(lock) { renderer }
    }

    fun invalidate(renderer: DesktopOffscreenRenderer) {
        disposeOnce(renderer)
    }

    private fun disposeOnce(renderer: DesktopOffscreenRenderer) {
        val shouldDispose = synchronized(lock) {
            if (this.renderer === renderer) {
                this.renderer = null
            }
            disposedRenderers.add(renderer)
        }

        if (shouldDispose) {
            renderer.dispose()
        }
    }
}

internal object DesktopOffscreenRuntimeBootstrap {
    private val lock = Any()
    private var attempted = false
    private var activationOverrideForTests: (() -> Boolean)? = null

    fun ensureInstalled(): Boolean {
        if (DesktopOffscreenRendererRegistry.current() != null) {
            return true
        }

        return synchronized(lock) {
            if (DesktopOffscreenRendererRegistry.current() != null) {
                return@synchronized true
            }
            if (attempted) {
                return@synchronized false
            }

            attempted = true
            val activated = activationOverrideForTests?.invoke() ?: activateFromClasspath()
            activated && DesktopOffscreenRendererRegistry.current() != null
        }
    }

    internal fun resetForTests(
        activationOverride: (() -> Boolean)? = null
    ) {
        synchronized(lock) {
            attempted = false
            activationOverrideForTests = activationOverride
        }
    }

    private fun activateFromClasspath(): Boolean {
        return try {
            val classLoader =
                Thread.currentThread().contextClassLoader
                    ?: DesktopOffscreenRuntimeBootstrap::class.java.classLoader
            val bootstrapClass = Class.forName(
                GRAPHITE_RUNTIME_BOOTSTRAP_CLASS,
                true,
                classLoader
            )
            val activate = bootstrapClass.getMethod("activate")
            activate.invoke(null) == true
        } catch (t: Throwable) {
            when (t) {
                is VirtualMachineError,
                is ThreadDeath -> throw t
                else -> false
            }
        }
    }
}

internal object DesktopOffscreenFailureState {
    private val lock = Any()

    fun record(error: Throwable) {
        synchronized(lock) {
            val current =
                System.getProperty(DESKTOP_OFFSCREEN_FAILURE_COUNT_PROPERTY)
                    ?.toIntOrNull()
                    ?: 0
            System.setProperty(
                DESKTOP_OFFSCREEN_FAILURE_COUNT_PROPERTY,
                (current + 1).toString()
            )
            System.setProperty(
                DESKTOP_OFFSCREEN_FAILURE_PROPERTY,
                error::class.qualifiedName ?: error::class.simpleName ?: "Throwable"
            )
        }
    }

    internal fun resetForTests() {
        synchronized(lock) {
            System.clearProperty(DESKTOP_OFFSCREEN_FAILURE_PROPERTY)
            System.clearProperty(DESKTOP_OFFSCREEN_FAILURE_COUNT_PROPERTY)
        }
    }
}

internal fun resolveDesktopRenderPath(
    configuredValue: String? = System.getProperty(DESKTOP_RENDER_PATH_PROPERTY)
): DesktopRenderPath {
    return when (configuredValue?.trim()?.lowercase()) {
        "offscreen",
        "offscreen-composite",
        "graphite-offscreen" -> DesktopRenderPath.OFFSCREEN_COMPOSITE

        else -> DesktopRenderPath.NATIVE_CANVAS
    }
}

/**
 * Routes desktop plot painting through the selected renderer path.
 *
 * The default remains Compose's native Skia canvas. The offscreen path is
 * intentionally provider-driven so the production frontend does not acquire a
 * hard dependency on Graphite/Vulkan. If the experimental path is requested
 * but no provider is installed, or the target size is not drawable yet,
 * painting safely falls back to nativeCanvas.
 *
 * Returns the effective path used for this frame.
 */
internal fun paintDesktopPlot(
    canvas: Canvas,
    width: Int,
    height: Int,
    density: Double,
    plotPosition: DoubleVector,
    requestedPath: DesktopRenderPath = resolveDesktopRenderPath(),
    paint: (SkiaContext2d) -> Unit
): DesktopRenderPath {
    if (requestedPath == DesktopRenderPath.OFFSCREEN_COMPOSITE && width > 0 && height > 0) {
        val renderer =
            DesktopOffscreenRendererRegistry.current()
                ?: run {
                    DesktopOffscreenRuntimeBootstrap.ensureInstalled()
                    DesktopOffscreenRendererRegistry.current()
                }

        renderer?.let {
            try {
                it.paint(
                    targetCanvas = canvas,
                    width = width,
                    height = height,
                    density = density,
                    plotPosition = plotPosition,
                    paint = paint
                )
                return DesktopRenderPath.OFFSCREEN_COMPOSITE
            } catch (t: Throwable) {
                when (t) {
                    is VirtualMachineError,
                    is ThreadDeath -> throw t
                    else -> {
                        DesktopOffscreenFailureState.record(t)
                        DesktopOffscreenRendererRegistry.invalidate(it)
                    }
                }
            }
        }
    }

    paintOnSkiaCanvas(
        canvas = canvas,
        density = density,
        plotPosition = plotPosition,
        paint = paint
    )
    return DesktopRenderPath.NATIVE_CANVAS
}
