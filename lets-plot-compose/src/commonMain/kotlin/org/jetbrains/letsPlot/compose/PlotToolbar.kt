/*
 * Copyright (c) 2024 JetBrains s.r.o.
 * Use of this source code is governed by the MIT license that can be found in the LICENSE file.
 */

package org.jetbrains.letsPlot.compose

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.jetbrains.letsPlot.core.plot.builder.interact.tools.DefaultFigureToolsController
import org.jetbrains.letsPlot.core.plot.builder.interact.tools.FigureModel
import org.jetbrains.letsPlot.core.plot.builder.interact.tools.ToggleTool
import org.jetbrains.letsPlot.core.plot.builder.interact.tools.ToggleToolModel
import org.jetbrains.letsPlot.core.plot.builder.interact.tools.ToolSpecs.BBOX_ZOOM_TOOL_SPEC
import org.jetbrains.letsPlot.core.plot.builder.interact.tools.ToolSpecs.CBOX_ZOOM_TOOL_SPEC
import org.jetbrains.letsPlot.core.plot.builder.interact.tools.ToolSpecs.PAN_TOOL_SPEC
import org.jetbrains.letsPlot.core.plot.builder.interact.tools.res.ToolbarIcons

@Suppress("FunctionName")
@Composable
fun PlotToolbar(figureModel: FigureModel) {
    var panToolState by remember(figureModel) { mutableStateOf(false) }
    var bboxZoomToolState by remember(figureModel) { mutableStateOf(false) }
    var cboxZoomToolState by remember(figureModel) { mutableStateOf(false) }

    val panTool = remember(figureModel) { ToggleTool(PAN_TOOL_SPEC) }
    val bboxZoomTool = remember(figureModel) { ToggleTool(BBOX_ZOOM_TOOL_SPEC) }
    val cboxZoomTool = remember(figureModel) { ToggleTool(CBOX_ZOOM_TOOL_SPEC) }

    val controller = remember(figureModel) {
        DefaultFigureToolsController(
            figureModel,
            errorMessageHandler = { println(it) }
        )
    }

    DisposableEffect(figureModel, controller) {
        val registration = figureModel.addToolEventCallback { event ->
            controller.handleToolFeedback(event)
        }
        onDispose {
            registration.dispose()
        }
    }

    val panToolModel = remember(controller, panTool) {
        object : ToggleToolModel() {
            override fun setState(selected: Boolean) {
                panToolState = selected
            }
        }.also { controller.registerTool(panTool, it) }
    }

    val bboxZoomToolModel = remember(controller, bboxZoomTool) {
        object : ToggleToolModel() {
            override fun setState(selected: Boolean) {
                bboxZoomToolState = selected
            }
        }.also { controller.registerTool(bboxZoomTool, it) }
    }

    val cboxZoomToolModel = remember(controller, cboxZoomTool) {
        object : ToggleToolModel() {
            override fun setState(selected: Boolean) {
                cboxZoomToolState = selected
            }
        }.also { controller.registerTool(cboxZoomTool, it) }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(33.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(C_BACKGR_TRANSPARENT)
                .border(
                    BorderStroke(1.dp, Color(200, 200, 200)),
                    RoundedCornerShape(8.dp)
                )
                .padding(horizontal = 5.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            SvgIconButton(
                svgString = ToolbarIcons.PAN_TOOL,
                isSelected = panToolState,
                onClick = { panToolModel.action() },
                contentDescription = "Pan"
            )
            SvgIconButton(
                svgString = ToolbarIcons.ZOOM_CORNER,
                isSelected = bboxZoomToolState,
                onClick = { bboxZoomToolModel.action() },
                contentDescription = "Rubber Band Zoom"
            )
            SvgIconButton(
                svgString = ToolbarIcons.ZOOM_CENTER,
                isSelected = cboxZoomToolState,
                onClick = { cboxZoomToolModel.action() },
                contentDescription = "Centerpoint Zoom"
            )
            SvgIconButton(
                svgString = ToolbarIcons.RESET,
                isSelected = false,
                onClick = { controller.resetFigure(deactiveTools = true) },
                contentDescription = "Reset"
            )
        }
    }
}

@Composable
private fun SvgIconButton(
    svgString: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    val iconColor = if (isSelected) C_STROKE_SEL else C_STROKE
    val backgroundColor = when {
        isSelected -> C_BACKGR_SEL
        isHovered -> C_BACKGR_HOVER
        else -> Color.Transparent
    }

    val icon = SvgIconUtils.rememberSvgIcon(
        svgString = svgString,
        iconColor = iconColor
    )

    Box(
        modifier = modifier
            .size(22.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(backgroundColor)
            .hoverable(interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(16.dp)
        )
    }
}

private val C_BACKGR = Color(247, 248, 250)
private val C_STROKE = Color(110, 110, 110)
private val C_BACKGR_HOVER = Color(218, 219, 221)
private val C_BACKGR_SEL = Color(69, 114, 232)
private val C_STROKE_SEL = Color.White

private const val ALPHA = 0.8f

private val C_BACKGR_TRANSPARENT = Color(
    red = (C_BACKGR.red - 1.0f * (1 - ALPHA)) / ALPHA,
    green = (C_BACKGR.green - 1.0f * (1 - ALPHA)) / ALPHA,
    blue = (C_BACKGR.blue - 1.0f * (1 - ALPHA)) / ALPHA,
    alpha = ALPHA
)
