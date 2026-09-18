package io.github.psd2live.ui.views

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Divider
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.BrushShape
import io.github.psd2live.ui.CanvasEditor
import io.github.psd2live.ui.CanvasTool
import io.github.psd2live.ui.SelectionStyle
import io.github.psd2live.ui.components.CompactButton
import io.github.psd2live.ui.components.CompactNumberSpinner
import io.github.psd2live.ui.components.CompactSectionHeader
import io.github.psd2live.ui.components.CompactToggleChip
import io.github.psd2live.ui.state.PSD2LiveState
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography
import kotlin.math.abs

@Composable
internal fun ToolDetailsView(
    editor: CanvasEditor,
    viewModel: PSD2LiveViewModel,
    state: PSD2LiveState,
    modifier: Modifier = Modifier,
) {
    val colors = LocalToolColors.current
    val typography = LocalToolTypography.current
    val target = editor.target()
    val isPathTool = editor.tool == CanvasTool.CREATE_DEFORM_PATH || editor.drawingPath

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 1. Current Tool & Target Card
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(colors.panelElevated)
                .border(BorderStroke(1.dp, colors.divider), RoundedCornerShape(6.dp))
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(colors.accent, RoundedCornerShape(2.dp))
                    )
                    Text(
                        text = tr("editor.tool.${editor.tool.name.lowercase()}"),
                        style = typography.body.copy(fontSize = 12.sp, fontWeight = FontWeight.Bold),
                        color = colors.textPrimary,
                    )
                }

                Text(
                    text = if (editor.busy) tr("editor.saving") else if (editor.editable) tr("editor.ready") else tr("editor.readonly"),
                    style = typography.caption.copy(fontSize = 10.sp),
                    color = if (editor.busy) colors.warning else if (editor.editable) colors.accent else colors.textDisabled,
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = tr("inspector.target"),
                    style = typography.caption.copy(fontSize = 11.sp),
                    color = colors.textMuted,
                )
                Text(
                    text = target?.geometry?.name ?: tr("editor.select"),
                    style = typography.body.copy(fontSize = 11.sp, fontWeight = FontWeight.Medium),
                    color = if (target != null) colors.textPrimary else colors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // 2. Selection Style (Box / Lasso)
        if (editor.tool in listOf(CanvasTool.SELECT, CanvasTool.LASSO_SELECT, CanvasTool.BRUSH_SELECT)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = tr("editor.selectionMode"),
                    style = typography.caption.copy(fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold),
                    color = colors.textMuted,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CompactToggleChip(
                        text = tr("editor.mode.box"),
                        selected = editor.selectionStyle == SelectionStyle.BOX,
                        onToggle = { editor.selectionStyle = SelectionStyle.BOX },
                        height = 24.dp,
                    )
                    CompactToggleChip(
                        text = tr("editor.mode.lasso"),
                        selected = editor.selectionStyle == SelectionStyle.LASSO,
                        onToggle = { editor.selectionStyle = SelectionStyle.LASSO },
                        height = 24.dp,
                    )
                }
            }
        }

        // 3. Target Pose / Parameter Picker
        if (target != null && editor.hierarchyMode == io.github.psd2live.ui.EditHierarchyMode.DEFORM) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = tr("editor.targetPose"),
                    style = typography.caption.copy(fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold),
                    color = colors.textMuted,
                )
                var paramMenuOpen by remember { mutableStateOf(false) }
                Box {
                    CompactButton(
                        text = if (editor.parameter == null) if (target.geometry.axes.isEmpty()) tr("editor.base") else tr("editor.pose") else editor.parameter!!,
                        onClick = { paramMenuOpen = true },
                        modifier = Modifier.fillMaxWidth(),
                        height = 25.dp,
                    )
                    DropdownMenu(
                        expanded = paramMenuOpen,
                        onDismissRequest = { paramMenuOpen = false },
                    ) {
                        DropdownMenuItem({ editor.parameter = null; paramMenuOpen = false }) {
                            Text(if (target.geometry.axes.isEmpty()) tr("editor.base") else tr("editor.pose"))
                        }
                        editor.model.parameters.forEach { p ->
                            DropdownMenuItem({ editor.parameter = p.id.raw; paramMenuOpen = false }) {
                                Text(p.name)
                            }
                        }
                    }
                }
            }
        }

        Divider(color = colors.divider, thickness = 0.8.dp)

        // 4. Tool-Specific Parameters
        when (editor.tool) {
            CanvasTool.SELECT, CanvasTool.LASSO_SELECT, CanvasTool.BRUSH_SELECT -> {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (editor.hierarchyMode == io.github.psd2live.ui.EditHierarchyMode.STRUCTURE && target?.kind == "mesh") {
                        Text(
                            text = tr("editor.elementMode"),
                            style = typography.caption.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold),
                            color = colors.textPrimary,
                        )
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("vertex", "edge", "face").forEachIndexed { i, key ->
                                CompactButton(
                                    text = tr("editor.$key"),
                                    onClick = { editor.elementMode = i },
                                    isPrimary = editor.elementMode == i,
                                    modifier = Modifier.weight(1f),
                                    height = 24.dp,
                                )
                            }
                        }

                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = tr("editor.topology"),
                            style = typography.caption.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold),
                            color = colors.textPrimary,
                        )
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            listOf("split", "connect", "merge", "delete").forEach { action ->
                                CompactButton(
                                    text = tr("editor.$action"),
                                    onClick = { editor.topology(action) },
                                    enabled = editor.editable && editor.vertices.isNotEmpty(),
                                    modifier = Modifier.weight(1f),
                                    height = 24.dp,
                                )
                            }
                        }

                        Divider(color = colors.divider, thickness = 0.8.dp)
                    }

                    if (target?.kind == "mesh") {
                        Text(
                            text = tr("editor.deformers"),
                            style = typography.caption.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold),
                            color = colors.textPrimary,
                        )
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            CompactButton(
                                text = tr("editor.createWarp"),
                                onClick = { editor.createWarp() },
                                enabled = editor.editable,
                                modifier = Modifier.weight(1f),
                                height = 25.dp,
                            )
                            CompactButton(
                                text = tr("editor.createRotation"),
                                onClick = { editor.createWarp(rotation = true) },
                                enabled = editor.editable,
                                modifier = Modifier.weight(1f),
                                height = 25.dp,
                            )
                        }
                    } else {
                        Text(
                            text = tr("editor.select"),
                            style = typography.caption.copy(fontSize = 10.5.sp),
                            color = colors.textMuted,
                        )
                    }

                    if (editor.tool == CanvasTool.SELECT && editor.hasTransformSelection) {
                        Divider(color = colors.divider, thickness = 0.8.dp)
                        PreciseTransformColumn(editor)
                    }
                }
            }

            CanvasTool.CREATE_WARP -> {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = tr("editor.tool.create_warp"),
                        style = typography.caption.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold),
                        color = colors.textPrimary,
                    )
                    Text(
                        text = tr("editor.createWarpHint"),
                        style = typography.caption.copy(fontSize = 10.5.sp),
                        color = colors.textMuted,
                    )
                    CompactSectionHeader(title = "Grid Divisions")
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        CompactNumberSpinner(
                            value = editor.warpCreateGridRows.toDouble(),
                            onValueChange = { editor.warpCreateGridRows = it.toInt() },
                            modifier = Modifier.weight(1f),
                            min = 2.0,
                            max = 20.0,
                            unit = "R",
                            height = 24.dp,
                        )
                        CompactNumberSpinner(
                            value = editor.warpCreateGridCols.toDouble(),
                            onValueChange = { editor.warpCreateGridCols = it.toInt() },
                            modifier = Modifier.weight(1f),
                            min = 2.0,
                            max = 20.0,
                            unit = "C",
                            height = 24.dp,
                        )
                    }
                    CompactSectionHeader(title = "Bezier Divisions")
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        CompactNumberSpinner(
                            value = editor.warpCreateBezierRows.toDouble(),
                            onValueChange = { editor.warpCreateBezierRows = it.toInt() },
                            modifier = Modifier.weight(1f),
                            min = 1.0,
                            max = 10.0,
                            unit = "BR",
                            height = 24.dp,
                        )
                        CompactNumberSpinner(
                            value = editor.warpCreateBezierCols.toDouble(),
                            onValueChange = { editor.warpCreateBezierCols = it.toInt() },
                            modifier = Modifier.weight(1f),
                            min = 1.0,
                            max = 10.0,
                            unit = "BC",
                            height = 24.dp,
                        )
                    }
                }
            }

            CanvasTool.CREATE_ROTATION -> {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = tr("editor.tool.create_rotation"),
                        style = typography.caption.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold),
                        color = colors.textPrimary,
                    )
                    Text(
                        text = tr("editor.createRotationHint"),
                        style = typography.caption.copy(fontSize = 10.5.sp),
                        color = colors.textMuted,
                    )
                }
            }

            CanvasTool.GLUE -> {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = tr("editor.tool.glue"),
                        style = typography.caption.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold),
                        color = colors.textPrimary,
                    )
                    Text(
                        text = tr("editor.glueHint"),
                        style = typography.caption.copy(fontSize = 10.5.sp),
                        color = colors.textMuted,
                    )
                }
            }

            CanvasTool.BRUSH, CanvasTool.SMOOTH, CanvasTool.INFLATE -> {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = tr("editor.brushSettings"),
                        style = typography.caption.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold),
                        color = colors.textPrimary,
                    )

                    // Shape selector chips
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        listOf(
                            BrushShape.CIRCLE to tr("editor.brushShape.circle"),
                            BrushShape.LINE to tr("editor.brushShape.line"),
                            BrushShape.RECTANGLE to tr("editor.brushShape.rectangle"),
                        ).forEach { (shape, label) ->
                            CompactToggleChip(
                                text = label,
                                selected = editor.brushShape == shape,
                                onToggle = { editor.brushShape = shape },
                                height = 24.dp,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }

                    // Angle settings for Line / Rectangle
                    AnimatedVisibility(
                        visible = editor.brushShape != BrushShape.CIRCLE,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut(),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(colors.panelElevated, RoundedCornerShape(4.dp))
                                .padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    text = tr("editor.angle"),
                                    style = typography.caption.copy(fontSize = 11.sp),
                                    color = colors.textMuted,
                                    modifier = Modifier.width(42.dp),
                                )
                                CompactNumberSpinner(
                                    value = editor.brushAngle.toDouble(),
                                    onValueChange = { editor.brushAngle = it.toFloat().mod(360f) },
                                    modifier = Modifier.weight(1f),
                                    min = 0.0,
                                    max = 360.0,
                                    step = 15.0,
                                    unit = "°",
                                    height = 24.dp,
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                listOf(0f, 45f, 90f, 135f).forEach { ang ->
                                    val isQuickActive = abs(editor.brushAngle - ang) < 1f
                                    CompactToggleChip(
                                        text = "${ang.toInt()}°",
                                        selected = isQuickActive,
                                        onToggle = { editor.brushAngle = ang },
                                        showCheckWhenSelected = false,
                                        height = 22.dp,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                        }
                    }

                    // Radius row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = tr("editor.radius"),
                            style = typography.caption.copy(fontSize = 11.sp),
                            color = colors.textMuted,
                            modifier = Modifier.width(42.dp),
                        )
                        CompactNumberSpinner(
                            value = editor.radius.toDouble(),
                            onValueChange = { editor.radius = it.toFloat() },
                            modifier = Modifier.weight(1f),
                            min = 4.0,
                            max = 500.0,
                            unit = "px",
                            height = 24.dp,
                        )
                    }

                    // Strength row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = tr("editor.strength"),
                            style = typography.caption.copy(fontSize = 11.sp),
                            color = colors.textMuted,
                            modifier = Modifier.width(42.dp),
                        )
                        CompactNumberSpinner(
                            value = (editor.strength * 100).toDouble(),
                            onValueChange = { editor.strength = it.toFloat() / 100f },
                            modifier = Modifier.weight(1f),
                            min = 1.0,
                            max = 100.0,
                            unit = "%",
                            height = 24.dp,
                        )
                    }

                    // Hardness row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = tr("editor.hardness"),
                            style = typography.caption.copy(fontSize = 11.sp),
                            color = colors.textMuted,
                            modifier = Modifier.width(42.dp),
                        )
                        CompactNumberSpinner(
                            value = (editor.hardness * 100).toDouble(),
                            onValueChange = { editor.hardness = it.toFloat() / 100f },
                            modifier = Modifier.weight(1f),
                            min = 0.0,
                            max = 95.0,
                            unit = "%",
                            height = 24.dp,
                        )
                    }

                    // Inflate Direction
                    if (editor.tool == CanvasTool.INFLATE) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            CompactToggleChip(
                                text = tr("editor.mode.inflate"),
                                selected = !editor.inflateInvert,
                                onToggle = { editor.inflateInvert = false },
                                enabled = editor.editable,
                                height = 24.dp,
                                modifier = Modifier.weight(1f),
                            )
                            CompactToggleChip(
                                text = tr("editor.mode.shrink"),
                                selected = editor.inflateInvert,
                                onToggle = { editor.inflateInvert = true },
                                enabled = editor.editable,
                                height = 24.dp,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }

            CanvasTool.CREATE_DEFORM_PATH -> {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = tr("editor.pathDeform"),
                        style = typography.caption.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold),
                        color = colors.textPrimary,
                    )

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        CompactButton(
                            text = tr("editor.newPath"),
                            onClick = { editor.cancel(); editor.drawingPath = true },
                            enabled = target?.kind == "mesh" && editor.editable,
                            modifier = Modifier.weight(1f),
                            height = 25.dp,
                        )
                        if (editor.drawingPath) {
                            CompactButton(
                                text = tr("editor.finishPath"),
                                onClick = { editor.finishPath() },
                                enabled = editor.draft.size >= 2,
                                modifier = Modifier.weight(1f),
                                height = 25.dp,
                            )
                        }
                    }

                    val active = editor.selectedPath()
                    if (active != null) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(colors.panelElevated, RoundedCornerShape(4.dp))
                                .padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                if (!active.closed) {
                                    CompactButton(
                                        text = tr("editor.extend"),
                                        onClick = { editor.extendPath() },
                                        enabled = editor.editable,
                                        modifier = Modifier.weight(1f),
                                        height = 24.dp,
                                    )
                                }
                                CompactButton(
                                    text = tr("editor.delete"),
                                    onClick = { editor.deletePathPoint() },
                                    enabled = editor.editable,
                                    modifier = Modifier.weight(1f),
                                    height = 24.dp,
                                )
                                CompactButton(
                                    text = tr(if (active.closed) "editor.openPath" else "editor.closePath"),
                                    onClick = { editor.changePath { it.copy(closed = !it.closed) } },
                                    enabled = editor.editable && active.points.size >= 3,
                                    modifier = Modifier.weight(1f),
                                    height = 24.dp,
                                )
                            }

                            if (editor.pathPoint in active.points.indices) {
                                CompactButton(
                                    text = tr("editor.corner"),
                                    onClick = {
                                        editor.changePath { p ->
                                            p.copy(points = p.points.mapIndexed { i, pt ->
                                                if (i == editor.pathPoint) pt.copy(corner = !pt.corner) else pt
                                            })
                                        }
                                    },
                                    enabled = editor.editable,
                                    modifier = Modifier.fillMaxWidth(),
                                    height = 24.dp,
                                )
                            }

                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(tr("editor.width"), color = colors.textMuted, fontSize = 11.sp, modifier = Modifier.width(42.dp))
                                CompactNumberSpinner(
                                    value = active.width.toDouble(),
                                    onValueChange = { w -> editor.changePath { it.copy(width = w.toFloat()) } },
                                    modifier = Modifier.weight(1f),
                                    min = 0.001,
                                    max = 10000.0,
                                    decimals = 3,
                                    step = 0.01,
                                    enabled = editor.editable,
                                    height = 24.dp,
                                )
                            }

                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(tr("editor.hardness"), color = colors.textMuted, fontSize = 11.sp, modifier = Modifier.width(42.dp))
                                CompactNumberSpinner(
                                    value = (active.hardness * 100).toDouble(),
                                    onValueChange = { h -> editor.changePath { it.copy(hardness = h.toFloat() / 100f) } },
                                    modifier = Modifier.weight(1f),
                                    min = 0.0,
                                    max = 100.0,
                                    enabled = editor.editable,
                                    height = 24.dp,
                                )
                            }
                        }
                    }

                    CompactButton(
                        text = "Level ${editor.pathLevel}",
                        onClick = { editor.pathLevel = if (editor.pathLevel == 2) 3 else 2; editor.activePath = null },
                        height = 24.dp,
                    )
                }
            }
            else -> {}
        }

        Spacer(Modifier.weight(1f))
        Divider(color = colors.divider, thickness = 0.8.dp)

        // 5. History Undo / Redo Actions
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = tr("editor.history"),
                style = typography.caption.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold),
                color = colors.textPrimary,
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CompactButton(
                    text = tr("editor.undo"),
                    onClick = { viewModel.undoHistory() },
                    enabled = editor.editable,
                    modifier = Modifier.weight(1f),
                    height = 26.dp,
                )
                CompactButton(
                    text = tr("editor.redo"),
                    onClick = { viewModel.redoHistory() },
                    enabled = editor.editable,
                    modifier = Modifier.weight(1f),
                    height = 26.dp,
                )
            }
        }
    }
}

/**
 * The numeric transform, shared by TRANSFORM, MESH and WARP so all three edit a multi-point selection
 * through the same controls and — because preciseTransform centres on the same pivot the box does —
 * about the same point.
 *
 * The pending values live here rather than on the editor, so leaving the tool and coming back starts
 * from the identity again.
 */
@Composable
private fun PreciseTransformColumn(editor: CanvasEditor) {
    val colors = LocalToolColors.current
    val typography = LocalToolTypography.current

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = tr("editor.preciseTransform"),
            style = typography.caption.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold),
            color = colors.textPrimary,
        )

        if (!editor.hasTransformSelection) {
            Text(
                text = tr("editor.select"),
                style = typography.caption.copy(fontSize = 10.5.sp),
                color = colors.textMuted,
            )
        }

        var posX by remember { mutableStateOf(0.0) }
        var posY by remember { mutableStateOf(0.0) }
        var scaleVal by remember { mutableStateOf(100.0) }
        var rotateVal by remember { mutableStateOf(0.0) }

        // Move row
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            CompactNumberSpinner(posX, { posX = it }, Modifier.weight(1f), min = -10000.0, max = 10000.0, decimals = 1, unit = "X", height = 24.dp)
            CompactNumberSpinner(posY, { posY = it }, Modifier.weight(1f), min = -10000.0, max = 10000.0, decimals = 1, unit = "Y", height = 24.dp)
            CompactButton(
                text = tr("editor.apply"),
                onClick = { editor.preciseTransform(first = posX.toFloat(), second = posY.toFloat()) },
                enabled = editor.editable && editor.hasTransformSelection && (posX != 0.0 || posY != 0.0),
                height = 24.dp,
            )
        }

        // Scale row
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            CompactNumberSpinner(scaleVal, { scaleVal = it }, Modifier.weight(1f), min = 0.1, max = 10000.0, decimals = 1, unit = "%", height = 24.dp)
            CompactButton(
                text = tr("editor.apply"),
                onClick = { editor.preciseTransform(first = scaleVal.toFloat(), scaleMode = true) },
                enabled = editor.editable && editor.hasTransformSelection && scaleVal != 100.0,
                height = 24.dp,
            )
        }

        // Rotate row
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            CompactNumberSpinner(rotateVal, { rotateVal = it }, Modifier.weight(1f), min = -360.0, max = 360.0, decimals = 1, unit = "°", height = 24.dp)
            CompactButton(
                text = tr("editor.apply"),
                onClick = { editor.preciseTransform(first = rotateVal.toFloat(), rotateMode = true) },
                enabled = editor.editable && editor.hasTransformSelection && rotateVal != 0.0,
                height = 24.dp,
            )
        }
    }
}
