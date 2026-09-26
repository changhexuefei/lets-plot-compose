/*
 * Copyright (c) 2026. JetBrains s.r.o.
 * Use of this source code is governed by the MIT license that can be found in the LICENSE file.
 */

package org.jetbrains.letsPlot.compose

import org.jetbrains.letsPlot.commons.geometry.DoubleVector
import org.jetbrains.letsPlot.compose.canvas.SkiaContext2d
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Surface

internal const val DESKTOP_RENDERER_SYSTEM_PROPERTY = "letsPlot.compose.desktop.renderer"
internal const val DESKTOP_RENDERER_ENVIRONMENT_VARIABLE = "LETS_PLOT_COMPOSE_DESKTOP_RENDERER"
internal const val EXPERIMENTAL_OFFSCREEN_RASTER_RENDERER = "experimental-offscreen-raster"

internal enum class DesktopPlotRendererMode {
    DIRECT_SKIA,
    EXPERIMENTAL_OFFSCREEN_RASTER
}

/**
 * Resolves the opt-in Desktop renderer mode.
 *
 * The production default is intentionally DIRECT_SKIA. Unknown values also fall
 * back to DIRECT_SKIA so an invalid external configuration cannot silently
 * switch the rendering path.
 */
internal fun resolveDesktopPlotRendererMode(
    systemPropertyValue: String? = System.getProperty(DESKTOP_RENDERER_SYSTEM_PROPERTY),
    environmentValue: String? = System.getenv(DESKTOP_RENDERER_ENVIRONMENT_VARIABLE)
): DesktopPlotRendererMode {
    val requested = systemPropertyValue
        ?.takeIf { it.isNotBlank() }
        ?: environmentValue?.takeIf { it.isNotBlank() }

    return when (requested?.trim()?.lowercase()) {
        EXPERIMENTAL_OFFSCREEN_RASTER_RENDERER -> DesktopPlotRendererMode.EXPERIMENTAL_OFFSCREEN_RASTER
        else -> DesktopPlotRendererMode.DIRECT_SKIA
    }
}

/**
 * Desktop PlotPanel renderer switch.
 *
 * DIRECT_SKIA preserves the existing production path exactly.
 * EXPERIMENTAL_OFFSCREEN_RASTER is opt-in only and exists to validate the
 * offscreen/composite lifecycle before a Graphite-backed provider is wired.
 */
internal fun paintPlotOnDesktopCanvas(
    canvas: Canvas,
    width: Int,
    height: Int,
    density: Double,
    plotPosition: DoubleVector,
    mode: DesktopPlotRendererMode = resolveDesktopPlotRendererMode(),
    paint: (SkiaContext2d) -> Unit
) {
    when (mode) {
        DesktopPlotRendererMode.DIRECT_SKIA -> {
            paintOnSkiaCanvas(
                canvas = canvas,
                density = density,
                plotPosition = plotPosition,
                paint = paint
            )
        }

        DesktopPlotRendererMode.EXPERIMENTAL_OFFSCREEN_RASTER -> {
            if (width <= 0 || height <= 0) {
                return
            }

            Surface.makeRasterN32Premul(width, height).use { offscreenSurface ->
                offscreenSurface.canvas.clear(0x00000000)

                paintOnSkiaCanvas(
                    canvas = offscreenSurface.canvas,
                    density = density,
                    plotPosition = plotPosition,
                    paint = paint
                )

                offscreenSurface.makeImageSnapshot().use { image ->
                    canvas.drawImage(image, 0f, 0f)
                }
            }
        }
    }
}
