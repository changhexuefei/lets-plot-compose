/*
 * Copyright (c) 2026. JetBrains s.r.o.
 * Use of this source code is governed by the MIT license that can be found in the LICENSE file.
 */

package org.jetbrains.letsPlot.compose

import org.jetbrains.letsPlot.commons.geometry.DoubleVector
import org.jetbrains.letsPlot.commons.values.Color
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Surface
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopPlotRendererBridgeTest {
    @Test
    fun defaultsToDirectSkia() {
        assertEquals(
            DesktopPlotRendererMode.DIRECT_SKIA,
            resolveDesktopPlotRendererMode(systemPropertyValue = null, environmentValue = null)
        )
    }

    @Test
    fun explicitExperimentalFlagSelectsOffscreenRaster() {
        assertEquals(
            DesktopPlotRendererMode.EXPERIMENTAL_OFFSCREEN_RASTER,
            resolveDesktopPlotRendererMode(
                systemPropertyValue = EXPERIMENTAL_OFFSCREEN_RASTER_RENDERER,
                environmentValue = null
            )
        )
    }

    @Test
    fun systemPropertyTakesPrecedenceOverEnvironment() {
        assertEquals(
            DesktopPlotRendererMode.DIRECT_SKIA,
            resolveDesktopPlotRendererMode(
                systemPropertyValue = "direct-skia",
                environmentValue = EXPERIMENTAL_OFFSCREEN_RASTER_RENDERER
            )
        )
    }

    @Test
    fun unknownFlagFallsBackToDirectSkia() {
        assertEquals(
            DesktopPlotRendererMode.DIRECT_SKIA,
            resolveDesktopPlotRendererMode(
                systemPropertyValue = "unexpected-renderer",
                environmentValue = null
            )
        )
    }

    @Test
    fun offscreenRasterMatchesDirectSkiaOutput() {
        val direct = render(DesktopPlotRendererMode.DIRECT_SKIA)
        val offscreen = render(DesktopPlotRendererMode.EXPERIMENTAL_OFFSCREEN_RASTER)

        assertEquals(direct.size, offscreen.size)

        var changed = 0
        for (index in direct.indices) {
            val left = direct[index]
            val right = offscreen[index]
            val delta =
                abs(channel(left, 16) - channel(right, 16)) +
                    abs(channel(left, 8) - channel(right, 8)) +
                    abs(channel(left, 0) - channel(right, 0)) +
                    abs(channel(left, 24) - channel(right, 24))
            if (delta > 8) changed++
        }

        val difference = changed.toDouble() / direct.size.toDouble()
        assertTrue(difference <= 0.001, "Renderer outputs diverged: difference=$difference")
    }

    private fun render(mode: DesktopPlotRendererMode): IntArray {
        val width = 96
        val height = 72

        Surface.makeRasterN32Premul(width, height).use { surface ->
            surface.canvas.clear(0xFFFFFFFF.toInt())

            paintPlotOnDesktopCanvas(
                canvas = surface.canvas,
                width = width,
                height = height,
                density = 1.5,
                plotPosition = DoubleVector(7.0, 5.0),
                mode = mode
            ) { context ->
                context.setFillStyle(Color.RED)
                context.fillRect(0.0, 0.0, 14.0, 10.0)
                context.setFillStyle(Color.BLUE)
                context.fillRect(16.0, 4.0, 9.0, 12.0)
            }

            surface.makeImageSnapshot().use { image ->
                Bitmap.makeFromImage(image).use { bitmap ->
                    return IntArray(width * height) { index ->
                        bitmap.getColor(index % width, index / width)
                    }
                }
            }
        }
    }

    private fun channel(color: Int, shift: Int): Int = (color ushr shift) and 0xFF
}
