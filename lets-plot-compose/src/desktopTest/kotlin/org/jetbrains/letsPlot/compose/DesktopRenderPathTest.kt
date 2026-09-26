/*
 * Copyright (c) 2026. JetBrains s.r.o.
 * Use of this source code is governed by the MIT license that can be found in the LICENSE file.
 */

package org.jetbrains.letsPlot.compose

import org.jetbrains.letsPlot.commons.geometry.DoubleVector
import org.jetbrains.letsPlot.commons.registration.Registration
import org.jetbrains.letsPlot.commons.values.Color
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Surface
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
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
        assertNull(DesktopOffscreenRendererRegistry.current())

        var painted = false
        Surface.makeRasterN32Premul(32, 32).use { surface ->
            val effective = paintDesktopPlot(
                canvas = surface.canvas,
                width = 32,
                height = 32,
                density = 1.0,
                plotPosition = DoubleVector.ZERO,
                requestedPath = DesktopRenderPath.OFFSCREEN_COMPOSITE
            ) {
                painted = true
            }

            assertTrue(painted)
            assertEquals(DesktopRenderPath.NATIVE_CANVAS, effective)
        }
    }

    @Test
    fun installedOffscreenProviderReceivesTargetSizeAndOwnsPaintPath() {
        assertNull(DesktopOffscreenRendererRegistry.current())

        var providerInvoked = false
        var painterInvoked = false
        var observedWidth = -1
        var observedHeight = -1

        val registration = DesktopOffscreenRendererRegistry.install(
            DesktopOffscreenRenderer {
                    targetCanvas,
                    width,
                    height,
                    density,
                    plotPosition,
                    paint ->
                providerInvoked = true
                observedWidth = width
                observedHeight = height
                paintOnSkiaCanvas(
                    canvas = targetCanvas,
                    density = density,
                    plotPosition = plotPosition,
                    paint = paint
                )
            }
        )

        try {
            Surface.makeRasterN32Premul(48, 36).use { surface ->
                val effective = paintDesktopPlot(
                    canvas = surface.canvas,
                    width = 48,
                    height = 36,
                    density = 1.25,
                    plotPosition = DoubleVector(2.0, 3.0),
                    requestedPath = DesktopRenderPath.OFFSCREEN_COMPOSITE
                ) {
                    painterInvoked = true
                }

                assertEquals(DesktopRenderPath.OFFSCREEN_COMPOSITE, effective)
                assertTrue(providerInvoked)
                assertTrue(painterInvoked)
                assertEquals(48, observedWidth)
                assertEquals(36, observedHeight)
            }
        } finally {
            registration.dispose()
        }

        assertNull(DesktopOffscreenRendererRegistry.current())
    }

    @Test
    fun staleRegistrationDisposesItsProviderWithoutClearingReplacement() {
        assertNull(DesktopOffscreenRendererRegistry.current())

        var firstInvoked = false
        var secondInvoked = false
        var firstDisposeCount = 0
        var secondDisposeCount = 0

        val firstRenderer = lifecycleRenderer(
            onPaint = { firstInvoked = true },
            onDispose = { firstDisposeCount++ }
        )
        val secondRenderer = lifecycleRenderer(
            onPaint = { secondInvoked = true },
            onDispose = { secondDisposeCount++ }
        )

        val first = DesktopOffscreenRendererRegistry.install(firstRenderer)
        val second = DesktopOffscreenRendererRegistry.install(secondRenderer)

        try {
            first.dispose()
            assertEquals(1, firstDisposeCount)
            assertEquals(0, secondDisposeCount)
            assertTrue(DesktopOffscreenRendererRegistry.current() === secondRenderer)

            Surface.makeRasterN32Premul(24, 24).use { surface ->
                val effective = paintDesktopPlot(
                    canvas = surface.canvas,
                    width = 24,
                    height = 24,
                    density = 1.0,
                    plotPosition = DoubleVector.ZERO,
                    requestedPath = DesktopRenderPath.OFFSCREEN_COMPOSITE
                ) {}

                assertEquals(DesktopRenderPath.OFFSCREEN_COMPOSITE, effective)
                assertTrue(!firstInvoked)
                assertTrue(secondInvoked)
            }
        } finally {
            second.dispose()
        }

        assertEquals(1, firstDisposeCount)
        assertEquals(1, secondDisposeCount)
        assertNull(DesktopOffscreenRendererRegistry.current())
    }

    @Test
    fun rasterReferenceProviderMatchesNativeCanvasPixels() {
        val native = render(DesktopRenderPath.NATIVE_CANVAS, installRasterProvider = false)
        val offscreen = render(DesktopRenderPath.OFFSCREEN_COMPOSITE, installRasterProvider = true)

        assertEquals(native.size, offscreen.size)

        var changed = 0
        for (index in native.indices) {
            val left = native[index]
            val right = offscreen[index]
            val delta =
                abs(channel(left, 24) - channel(right, 24)) +
                    abs(channel(left, 16) - channel(right, 16)) +
                    abs(channel(left, 8) - channel(right, 8)) +
                    abs(channel(left, 0) - channel(right, 0))
            if (delta > 8) changed++
        }

        val difference = changed.toDouble() / native.size.toDouble()
        assertTrue(difference <= 0.001, "Offscreen provider output diverged: difference=$difference")
    }

    private fun render(
        requestedPath: DesktopRenderPath,
        installRasterProvider: Boolean
    ): IntArray {
        val width = 96
        val height = 72
        var registration: Registration? = null

        if (installRasterProvider) {
            assertNull(DesktopOffscreenRendererRegistry.current())
            registration = DesktopOffscreenRendererRegistry.install(
                DesktopOffscreenRenderer {
                        targetCanvas,
                        targetWidth,
                        targetHeight,
                        density,
                        plotPosition,
                        paint ->
                    Surface.makeRasterN32Premul(targetWidth, targetHeight).use { offscreen ->
                        offscreen.canvas.clear(0x00000000)
                        paintOnSkiaCanvas(
                            canvas = offscreen.canvas,
                            density = density,
                            plotPosition = plotPosition,
                            paint = paint
                        )
                        offscreen.makeImageSnapshot().use { image ->
                            targetCanvas.drawImage(image, 0f, 0f)
                        }
                    }
                }
            )
        }

        try {
            Surface.makeRasterN32Premul(width, height).use { surface ->
                surface.canvas.clear(0xFFFFFFFF.toInt())

                val effective = paintDesktopPlot(
                    canvas = surface.canvas,
                    width = width,
                    height = height,
                    density = 1.5,
                    plotPosition = DoubleVector(7.0, 5.0),
                    requestedPath = requestedPath
                ) { context ->
                    context.setFillStyle(Color.RED)
                    context.fillRect(0.0, 0.0, 14.0, 10.0)
                    context.setFillStyle(Color.BLUE)
                    context.fillRect(16.0, 4.0, 9.0, 12.0)
                }

                assertEquals(requestedPath, effective)

                surface.makeImageSnapshot().use { image ->
                    Bitmap.makeFromImage(image).use { bitmap ->
                        return IntArray(width * height) { index ->
                            bitmap.getColor(index % width, index / width)
                        }
                    }
                }
            }
        } finally {
            registration?.dispose()
        }
    }

    private fun passthroughRenderer(onInvoke: () -> Unit): DesktopOffscreenRenderer {
        return DesktopOffscreenRenderer { targetCanvas, _, _, density, plotPosition, paint ->
            onInvoke()
            paintOnSkiaCanvas(
                canvas = targetCanvas,
                density = density,
                plotPosition = plotPosition,
                paint = paint
            )
        }
    }

    private fun lifecycleRenderer(
        onPaint: () -> Unit,
        onDispose: () -> Unit
    ): DesktopOffscreenRenderer {
        return object : DesktopOffscreenRenderer {
            override fun paint(
                targetCanvas: org.jetbrains.skia.Canvas,
                width: Int,
                height: Int,
                density: Double,
                plotPosition: DoubleVector,
                paint: (org.jetbrains.letsPlot.compose.canvas.SkiaContext2d) -> Unit
            ) {
                onPaint()
                paintOnSkiaCanvas(
                    canvas = targetCanvas,
                    density = density,
                    plotPosition = plotPosition,
                    paint = paint
                )
            }

            override fun dispose() {
                onDispose()
            }
        }
    }

    private fun channel(color: Int, shift: Int): Int = (color ushr shift) and 0xFF
}
