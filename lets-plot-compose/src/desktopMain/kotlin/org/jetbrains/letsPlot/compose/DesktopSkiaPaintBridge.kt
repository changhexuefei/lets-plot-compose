/*
 * Copyright (c) 2026. JetBrains s.r.o.
 * Use of this source code is governed by the MIT license that can be found in the LICENSE file.
 */

package org.jetbrains.letsPlot.compose

import org.jetbrains.letsPlot.commons.geometry.DoubleVector
import org.jetbrains.letsPlot.compose.canvas.SkiaContext2d
import org.jetbrains.letsPlot.compose.canvas.SkiaFontManager
import org.jetbrains.skia.Canvas

/**
 * Desktop Skia painting boundary used by the Compose frontend.
 *
 * Keeping SkiaContext2d construction and transform setup behind this small seam
 * makes the renderer surface source replaceable without coupling PlotPanel
 * lifecycle, sizing or interaction handling to a particular Skia backend.
 *
 * The current production path still passes Compose's native Skia canvas.
 */
internal fun paintOnSkiaCanvas(
    canvas: Canvas,
    density: Double,
    plotPosition: DoubleVector,
    paint: (SkiaContext2d) -> Unit
) {
    val context = SkiaContext2d(canvas, SkiaFontManager.DEFAULT)
    try {
        context.scale(density)
        context.translate(plotPosition.x, plotPosition.y)
        paint(context)
    } finally {
        context.dispose()
    }
}
