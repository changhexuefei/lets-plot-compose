/*
 * Copyright (c) 2024 JetBrains s.r.o.
 * Use of this source code is governed by the MIT license that can be found in the LICENSE file.
 */

package org.jetbrains.letsPlot.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.dp

internal object SvgIconUtils {

    @Composable
    fun rememberSvgIcon(
        svgString: String,
        iconColor: Color
    ): Painter {
        val imageVector = remember(svgString, iconColor) {
            createImageVector(svgString, iconColor)
        }
        return rememberVectorPainter(imageVector)
    }

    private fun createImageVector(
        svgString: String,
        iconColor: Color
    ): ImageVector {
        val pathData = buildList {
            PATH_REGEX.findAll(svgString).forEach { match ->
                add(match.groupValues[1])
            }
            CIRCLE_REGEX.findAll(svgString).forEach { match ->
                val cx = match.groupValues[1].toFloat()
                val cy = match.groupValues[2].toFloat()
                val r = match.groupValues[3].toFloat()
                add(circlePath(cx, cy, r))
            }
        }

        require(pathData.isNotEmpty()) {
            "Unsupported toolbar SVG: expected at least one <path> or <circle> element"
        }

        val builder = ImageVector.Builder(
            name = "LetsPlotToolbarIcon",
            defaultWidth = ICON_SIZE.dp,
            defaultHeight = ICON_SIZE.dp,
            viewportWidth = ICON_SIZE,
            viewportHeight = ICON_SIZE
        )
        val fill = SolidColor(iconColor)

        pathData.forEach { data ->
            builder.addPath(
                pathData = PathParser().parsePathString(data).toNodes(),
                fill = fill
            )
        }

        return builder.build()
    }

    private fun circlePath(cx: Float, cy: Float, r: Float): String {
        val left = cx - r
        val right = cx + r
        return "M$left,$cy A$r,$r 0 1,0 $right,$cy A$r,$r 0 1,0 $left,$cy Z"
    }

    private const val ICON_SIZE = 16f

    private val PATH_REGEX =
        Regex("""<path[^>]*\bd="([^"]+)"[^>]*/?>""")

    private val CIRCLE_REGEX =
        Regex("""<circle[^>]*\bcx="([^"]+)"[^>]*\bcy="([^"]+)"[^>]*\br="([^"]+)"[^>]*/?>""")
}
