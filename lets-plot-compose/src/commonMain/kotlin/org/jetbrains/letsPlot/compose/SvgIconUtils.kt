/*
 * Copyright (c) 2024 JetBrains s.r.o.
 * Use of this source code is governed by the MIT license that can be found in the LICENSE file.
 */

package org.jetbrains.letsPlot.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalDensity
import org.jetbrains.compose.resources.decodeToSvgPainter

internal object SvgIconUtils {

    @Composable
    fun rememberSvgIcon(
        svgString: String,
        iconColor: Color
    ): Painter {
        val density = LocalDensity.current
        return remember(svgString, iconColor, density) {
            val coloredSvg = svgString.replace(
                """stroke="none"""",
                """stroke="none" fill="${colorToHex(iconColor)}""""
            )
            coloredSvg.toByteArray().decodeToSvgPainter(density)
        }
    }

    private fun colorToHex(color: Color): String {
        fun component(value: Float): String =
            (value * 255f)
                .toInt()
                .coerceIn(0, 255)
                .toString(16)
                .padStart(2, '0')

        return "#" +
            component(color.red) +
            component(color.green) +
            component(color.blue)
    }
}
