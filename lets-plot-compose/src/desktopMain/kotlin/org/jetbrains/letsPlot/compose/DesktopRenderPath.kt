/*
 * Copyright (c) 2026. JetBrains s.r.o.
 * Use of this source code is governed by the MIT license that can be found in the LICENSE file.
 */

package org.jetbrains.letsPlot.compose

import org.jetbrains.letsPlot.commons.geometry.DoubleVector
import org.jetbrains.letsPlot.compose.canvas.SkiaContext2d
import org.jetbrains.skia.Canvas

internal const val DESKTOP_RENDER_PATH_PROPERTY = "letsplot.compose.desktop.renderPath"

internal enum class DesktopRenderPath {
    NATIVE_CANVAS,
    OFFSCREEN_COMPOSITE
}

internal fun interface DesktopOffscreenRenderer {
    fun paint(
        targetCanvas: Canvas,
        density: Double,
        plotPosition: DoubleVector,
        paint: (SkiaContext2d) -> Unit
    )
}

internal object DesktopOffscreenRendererRegistry {
    @Volatile
    var renderer: DesktopOffscreenRenderer? = null
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
 * but no provider is installed, painting safely falls back to nativeCanvas.
 *
 * Returns the effective path used for this frame.
 */
internal fun paintDesktopPlot(
    canvas: Canvas,
    density: Double,
    plotPosition: DoubleVector,
    requestedPath: DesktopRenderPath = resolveDesktopRenderPath(),
    paint: (SkiaContext2d) -> Unit
): DesktopRenderPath {
    if (requestedPath == DesktopRenderPath.OFFSCREEN_COMPOSITE) {
        DesktopOffscreenRendererRegistry.renderer?.let { renderer ->
            renderer.paint(
                targetCanvas = canvas,
                density = density,
                plotPosition = plotPosition,
                paint = paint
            )
            return DesktopRenderPath.OFFSCREEN_COMPOSITE
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
