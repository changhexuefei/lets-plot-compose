/*
 * Copyright (c) 2026. JetBrains s.r.o.
 * Use of this source code is governed by the MIT license that can be found in the LICENSE file.
 */

package org.jetbrains.letsPlot.compose

import org.jetbrains.letsPlot.commons.geometry.DoubleVector
import org.jetbrains.letsPlot.commons.values.Color
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Surface
import kotlin.test.Test
import kotlin.test.assertTrue

class DesktopSkiaPaintBridgeTest {
    @Test
    fun appliesDensityBeforePlotTranslation() {
        Surface.makeRasterN32Premul(64, 64).use { surface ->
            surface.canvas.clear(0xFFFFFFFF.toInt())

            paintOnSkiaCanvas(
                canvas = surface.canvas,
                density = 2.0,
                plotPosition = DoubleVector(3.0, 4.0)
            ) { context ->
                context.setFillStyle(Color.RED)
                context.fillRect(0.0, 0.0, 4.0, 5.0)
            }

            surface.makeImageSnapshot().use { image ->
                Bitmap.makeFromImage(image).use { bitmap ->
                    // scale(2) followed by translate(3, 4) means the translation
                    // is expressed in logical plot units and therefore lands at
                    // physical pixel (6, 8).
                    assertRed(bitmap.getColor(7, 9))
                    assertRed(bitmap.getColor(13, 17))

                    // These pixels would be red if translation happened before
                    // density scaling or if plotPosition were treated as physical.
                    assertWhite(bitmap.getColor(3, 4))
                    assertWhite(bitmap.getColor(5, 7))
                }
            }
        }
    }

    @Test
    fun propagatesPainterFailure() {
        Surface.makeRasterN32Premul(16, 16).use { surface ->
            val failure = runCatching {
                paintOnSkiaCanvas(
                    canvas = surface.canvas,
                    density = 1.5,
                    plotPosition = DoubleVector(2.0, 3.0)
                ) {
                    error("paint failure sentinel")
                }
            }.exceptionOrNull()

            assertTrue(failure is IllegalStateException)
            assertTrue(failure?.message?.contains("paint failure sentinel") == true)
        }
    }

    private fun assertRed(color: Int) {
        val r = (color ushr 16) and 0xFF
        val g = (color ushr 8) and 0xFF
        val b = color and 0xFF
        assertTrue(r >= 220 && g <= 40 && b <= 40, "Expected red pixel, got 0x%08X".format(color))
    }

    private fun assertWhite(color: Int) {
        val r = (color ushr 16) and 0xFF
        val g = (color ushr 8) and 0xFF
        val b = color and 0xFF
        assertTrue(r >= 240 && g >= 240 && b >= 240, "Expected white pixel, got 0x%08X".format(color))
    }
}
