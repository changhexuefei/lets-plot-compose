/*
 * Copyright (c) 2026. JetBrains s.r.o.
 * Use of this source code is governed by the MIT license that can be found in the LICENSE file.
 */

package org.jetbrains.letsPlot.compose

import org.jetbrains.letsPlot.commons.geometry.DoubleVector
import org.jetbrains.skia.Surface
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopRenderPathTest {
    @Test
    fun nativeCanvasIsTheDefaultAndUnknownValuesFailClosed() {
        assertEquals(DesktopRenderPath.NATIVE_CANVAS, resolveDesktopRenderPath(null))
        assertEquals(DesktopRenderPath.NATIVE_CANVAS, resolveDesktopRenderPath(""))
        assertEquals(DesktopRenderPath.NATIVE_CANVAS, resolveDesktopRenderPath("native"))
        assertEquals(DesktopRenderPath.NATIVE_CANVAS, resolveDesktopRenderPath("unexpected-value"))
    }

    @Test
    fun explicitOffscreenAliasesSelectExperimentalPath() {
        assertEquals(DesktopRenderPath.OFFSCREEN_COMPOSITE, resolveDesktopRenderPath("offscreen"))
        assertEquals(DesktopRenderPath.OFFSCREEN_COMPOSITE, resolveDesktopRenderPath("offscreen-composite"))
        assertEquals(DesktopRenderPath.OFFSCREEN_COMPOSITE, resolveDesktopRenderPath("GRAPHITE-OFFSCREEN"))
    }

    @Test
    fun missingOffscreenProviderFallsBackToNativeCanvas() {
        val previous = DesktopOffscreenRendererRegistry.renderer
        DesktopOffscreenRendererRegistry.renderer = null
        try {
            var painted = false
            Surface.makeRasterN32Premul(32, 32).use { surface ->
                val effective = paintDesktopPlot(
                    canvas = surface.canvas,
                    density = 1.0,
                    plotPosition = DoubleVector.ZERO,
                    requestedPath = DesktopRenderPath.OFFSCREEN_COMPOSITE
                ) {
                    painted = true
                }

                assertTrue(painted)
                assertEquals(DesktopRenderPath.NATIVE_CANVAS, effective)
            }
        } finally {
            DesktopOffscreenRendererRegistry.renderer = previous
        }
    }

    @Test
    fun installedOffscreenProviderOwnsTheExperimentalPaintPath() {
        val previous = DesktopOffscreenRendererRegistry.renderer
        var providerInvoked = false
        var painterInvoked = false

        DesktopOffscreenRendererRegistry.renderer = DesktopOffscreenRenderer { targetCanvas, density, plotPosition, paint ->
            providerInvoked = true
            paintOnSkiaCanvas(
                canvas = targetCanvas,
                density = density,
                plotPosition = plotPosition,
                paint = paint
            )
        }

        try {
            Surface.makeRasterN32Premul(32, 32).use { surface ->
                val effective = paintDesktopPlot(
                    canvas = surface.canvas,
                    density = 1.25,
                    plotPosition = DoubleVector(2.0, 3.0),
                    requestedPath = DesktopRenderPath.OFFSCREEN_COMPOSITE
                ) {
                    painterInvoked = true
                }

                assertEquals(DesktopRenderPath.OFFSCREEN_COMPOSITE, effective)
                assertTrue(providerInvoked)
                assertTrue(painterInvoked)
            }
        } finally {
            DesktopOffscreenRendererRegistry.renderer = previous
        }

        assertFalse(DesktopOffscreenRendererRegistry.renderer === DesktopOffscreenRendererRegistry)
    }
}
