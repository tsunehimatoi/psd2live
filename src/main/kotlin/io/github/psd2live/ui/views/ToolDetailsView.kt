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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.BrushShape
import io.github.psd2live.ui.PaintShape
import io.github.psd2live.ui.CanvasEditor
import io.github.psd2live.ui.CanvasTarget
import io.github.psd2live.ui.CanvasTool
import io.github.psd2live.ui.SelectionStyle
import io.github.psd2live.ui.WarpAddTo
import io.github.psd2live.ui.WarpSizeStrategy
import io.github.psd2live.ui.components.CompactButton
import io.github.psd2live.ui.components.CompactDropdown
import io.github.psd2live.ui.components.CompactNumberSpinner
import io.github.psd2live.ui.components.CompactSectionHeader
import io.github.psd2live.ui.components.CompactToggleChip
import io.github.psd2live.ui.components.PaintColorChip
import io.github.psd2live.ui.components.toHex
import io.github.psd2live.ui.state.PSD2LiveState
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.ToolColors
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

        if (target?.kind == "rotation") {
            Text(tr("editor.rotationGestureHint"), style = typography.caption, color = colors.textMuted)
            PreciseTransformColumn(editor)
        }
        if (editor.tool == CanvasTool.CREATE_WARP || editor.tool == CanvasTool.CREATE_ROTATION) {
            CompactButton(
                text = tr("editor.createFromSelection"),
                onClick = { editor.createWarp(editor.tool == CanvasTool.CREATE_ROTATION) },
                enabled = editor.editable && (
                    (editor.tool == CanvasTool.CREATE_WARP && editor.warpAddTo == WarpAddTo.CHILD_OF_SELECTED_DEFORMER &&
                        state.selectedDeformerId != null) ||
                        target?.kind == "mesh"
                    ),
                modifier = Modifier.fillMaxWidth(),
            )
            if (editor.tool == CanvasTool.CREATE_ROTATION) {
                Text(tr("editor.rotationScopeHint"), style = typography.caption, color = colors.textMuted)
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

        // 3. Target Pose / Parameter Picker. Warp and rotation edits still address a pose; an ArtMesh
        //    edit does not, so for a mesh this control would do nothing and is not offered.
        if (target != null && target.kind != "mesh" && editor.hierarchyMode == io.github.psd2live.ui.EditHierarchyMode.DEFORM) {
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
                    if (editor.hierarchyMode == io.github.psd2live.ui.EditHierarchyMode.EDIT && target?.kind == "mesh") {
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
                            listOf("split", "subdivide", "connect", "merge", "delete", "duplicate").forEach { action ->
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

                    CompactSectionHeader(title = tr("editor.warpAddTo"))
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf(
                            WarpAddTo.PARENT_OF_SELECTED to "editor.warpAddTo.parentOfSelected",
                            WarpAddTo.CHILD_OF_SELECTED_DEFORMER to "editor.warpAddTo.childOfDeformer",
                            WarpAddTo.SPECIFY_PARENT to "editor.warpAddTo.specifyParent",
                        ).forEach { (mode, key) ->
                            CompactToggleChip(
                                text = tr(key),
                                selected = editor.warpAddTo == mode,
                                onToggle = { editor.warpAddTo = mode },
                                height = 24.dp,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    if (editor.warpAddTo == WarpAddTo.SPECIFY_PARENT) {
                        val deformerOptions = listOf("" to tr("inspector.none")) +
                            editor.model.deformers.map { it.id.raw to it.name }
                        val selected = deformerOptions.firstOrNull { it.first == (editor.warpSpecifyParentId ?: "") }
                            ?: deformerOptions.first()
                        CompactDropdown(
                            items = deformerOptions,
                            selectedItem = selected,
                            onItemSelected = { editor.warpSpecifyParentId = it.first.takeIf { id -> id.isNotEmpty() } },
                            itemLabel = { it.second },
                            modifier = Modifier.fillMaxWidth(),
                            height = 24.dp,
                        )
                    }

                    CompactSectionHeader(title = tr("editor.warpPart"))
                    val partOptions = listOf("" to tr("editor.warpPart.inherit")) +
                        editor.model.parts.map { it.id.raw to it.name }
                    val partSelected = partOptions.firstOrNull { it.first == (editor.warpCreatePartId ?: "") }
                        ?: partOptions.first()
                    CompactDropdown(
                        items = partOptions,
                        selectedItem = partSelected,
                        onItemSelected = { editor.warpCreatePartId = it.first.takeIf { id -> id.isNotEmpty() } },
                        itemLabel = { it.second },
                        modifier = Modifier.fillMaxWidth(),
                        height = 24.dp,
                    )

                    CompactSectionHeader(title = tr("inspector.conversionDivision"))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(3 to 3, 5 to 5, 8 to 8).forEach { (r, c) ->
                            CompactToggleChip(
                                text = "${r}×${c}",
                                selected = editor.warpCreateGridRows == r && editor.warpCreateGridCols == c,
                                onToggle = {
                                    editor.warpCreateGridRows = r
                                    editor.warpCreateGridCols = c
                                },
                                height = 24.dp,
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        CompactNumberSpinner(
                            value = editor.warpCreateGridCols.toDouble(),
                            onValueChange = { editor.warpCreateGridCols = it.toInt().coerceIn(1, 32) },
                            modifier = Modifier.weight(1f),
                            min = 1.0,
                            max = 32.0,
                            unit = "C",
                            height = 24.dp,
                        )
                        CompactNumberSpinner(
                            value = editor.warpCreateGridRows.toDouble(),
                            onValueChange = { editor.warpCreateGridRows = it.toInt().coerceIn(1, 32) },
                            modifier = Modifier.weight(1f),
                            min = 1.0,
                            max = 32.0,
                            unit = "R",
                            height = 24.dp,
                        )
                    }
                    CompactSectionHeader(title = tr("inspector.bezierDivision"))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(2 to 2, 3 to 3, 5 to 5).forEach { (r, c) ->
                            CompactToggleChip(
                                text = "${r}×${c}",
                                selected = editor.warpCreateBezierRows == r && editor.warpCreateBezierCols == c,
                                onToggle = {
                                    editor.warpCreateBezierRows = r
                                    editor.warpCreateBezierCols = c
                                },
                                height = 24.dp,
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        CompactNumberSpinner(
                            value = editor.warpCreateBezierCols.toDouble(),
                            onValueChange = { editor.warpCreateBezierCols = it.toInt().coerceIn(1, 16) },
                            modifier = Modifier.weight(1f),
                            min = 1.0,
                            max = 16.0,
                            unit = "C",
                            height = 24.dp,
                        )
                        CompactNumberSpinner(
                            value = editor.warpCreateBezierRows.toDouble(),
                            onValueChange = { editor.warpCreateBezierRows = it.toInt().coerceIn(1, 16) },
                            modifier = Modifier.weight(1f),
                            min = 1.0,
                            max = 16.0,
                            unit = "R",
                            height = 24.dp,
                        )
                    }

                    CompactSectionHeader(title = tr("editor.warpSizeStrategy"))
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf(
                            WarpSizeStrategy.SELECTION_BOUNDS to "editor.warpSize.selection",
                            WarpSizeStrategy.KEYFORM_ENVELOPE to "editor.warpSize.keyform",
                            WarpSizeStrategy.CENTER_ALIGN to "editor.warpSize.center",
                        ).forEach { (strategy, key) ->
                            CompactToggleChip(
                                text = tr(key),
                                selected = editor.warpSizeStrategy == strategy,
                                onToggle = { editor.warpSizeStrategy = strategy },
                                height = 24.dp,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }

                    CompactToggleChip(
                        text = tr("editor.sequentialCreate"),
                        selected = editor.sequentialCreate,
                        onToggle = { editor.sequentialCreate = !editor.sequentialCreate },
                        height = 24.dp,
                        modifier = Modifier.fillMaxWidth(),
                    )
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
                    Text(
                        text = tr("editor.rotationMountHint"),
                        style = typography.caption.copy(fontSize = 10.5.sp),
                        color = colors.textMuted,
                    )
                    val (deformerIds, drawableIds) = editor.rotationScopeIds()
                    if (deformerIds.isNotEmpty() || drawableIds.isNotEmpty()) {
                        Text(
                            text = tr("editor.rotationScopeCount", deformerIds.size, drawableIds.size),
                            style = typography.caption.copy(fontSize = 10.5.sp),
                            color = colors.warning,
                        )
                    }
                    CompactSectionHeader(title = tr("editor.warpPart"))
                    val partOptions = listOf("" to tr("editor.warpPart.inherit")) +
                        editor.model.parts.map { it.id.raw to it.name }
                    val partSelected = partOptions.firstOrNull { it.first == (editor.warpCreatePartId ?: "") }
                        ?: partOptions.first()
                    CompactDropdown(
                        items = partOptions,
                        selectedItem = partSelected,
                        onItemSelected = { editor.warpCreatePartId = it.first.takeIf { id -> id.isNotEmpty() } },
                        itemLabel = { it.second },
                        modifier = Modifier.fillMaxWidth(),
                        height = 24.dp,
                    )
                    CompactToggleChip(
                        text = tr("editor.sequentialCreate"),
                        selected = editor.sequentialCreate,
                        onToggle = { editor.sequentialCreate = !editor.sequentialCreate },
                        height = 24.dp,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            CanvasTool.GLUE -> {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(tr("editor.glueDistance"), style = typography.caption, color = colors.textMuted)
                    CompactNumberSpinner(value = editor.glueDistance.toDouble(), onValueChange = { editor.glueDistance = it.toFloat() }, min = 1.0, max = 500.0, unit = "px", height = 24.dp)
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
                    Text(
                        text = tr("editor.pathCreateHint"),
                        style = typography.caption.copy(fontSize = 10.5.sp),
                        color = colors.textMuted,
                    )
                    Text(
                        text = tr("editor.pathLevelHint", editor.pathLevel),
                        style = typography.caption.copy(fontSize = 10.5.sp),
                        color = colors.textMuted,
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

                    CompactSectionHeader(title = tr("editor.pathEditLevel"))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(2, 3).forEach { level ->
                            CompactToggleChip(
                                text = "L$level",
                                selected = editor.pathLevel == level,
                                onToggle = { editor.pathLevel = level },
                                height = 24.dp,
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
            CanvasTool.SUBDIVIDE -> {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CompactNumberSpinner(value = editor.radius.toDouble(), onValueChange = { editor.radius = it.toFloat() }, min = 1.0, max = 500.0, unit = "px", height = 24.dp)
                    Text(
                        text = tr("editor.subdivideHint"),
                        style = typography.caption.copy(fontSize = 10.5.sp),
                        color = colors.textMuted,
                    )
                    CompactButton(
                        text = tr("editor.subdivide"),
                        onClick = { editor.topology("subdivide") },
                        enabled = editor.editable && editor.vertices.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth(),
                        height = 24.dp,
                    )
                }
            }
            CanvasTool.KNIFE -> {
                Text(tr("editor.knifeGestureHint"), style = typography.caption, color = colors.textMuted)
                // No snap toggle: snapping is always on and the radius is the only thing to tune. Inside it a
                // click takes the vertex or edge; outside it the click drops a new point.
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(tr("editor.knifeSnapRadius"), color = colors.textMuted, fontSize = 11.sp)
                    CompactNumberSpinner(value = editor.knifeSnapRadius.toDouble(),
                        onValueChange = { editor.knifeSnapRadius = it.toFloat() }, min = 3.0, max = 30.0, unit = "px", height = 24.dp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CompactButton(text = tr("editor.finishCut"), onClick = { editor.finishKnife() }, enabled = editor.editable && editor.knifeDraft.size >= 2)
                    CompactButton(text = tr("editor.undoPoint"), onClick = { editor.undoDraftPoint() }, enabled = !editor.busy && editor.knifeDraft.isNotEmpty())
                    CompactButton(text = tr("action.cancel"), onClick = { editor.cancel() }, enabled = !editor.busy && editor.knifeDraft.isNotEmpty())
                }
            }
            CanvasTool.PAINT_BRUSH, CanvasTool.PAINT_PENCIL, CanvasTool.PAINT_ERASER,
            CanvasTool.PAINT_BUCKET, CanvasTool.PAINT_EYEDROPPER,
            CanvasTool.PAINT_SHAPE -> {
                PaintToolDetailsColumn(editor, target)
            }
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

        // Scale and rotate need a selection with extent — two points or more. A lone point spans nothing, so
        // its only operation is the move above; the rows would be controls that cannot do what they say.
        if (editor.selectionHasExtent) {
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
}

/**
 * The opacity the tip lands at, on every tool that stamps one: the same number whichever of the three
 * is in hand, and shown for each of them rather than only for the brush it is usually set on.
 */
@Composable
private fun PaintOpacityRow(editor: CanvasEditor, colors: ToolColors) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(tr("editor.opacity"), color = colors.textMuted, fontSize = 11.sp, modifier = Modifier.width(42.dp))
        CompactNumberSpinner(
            value = (editor.paintOpacity * 100).toDouble(),
            onValueChange = { editor.paintOpacity = (it.toFloat() / 100f).coerceIn(0.01f, 1f) },
            modifier = Modifier.weight(1f),
            min = 1.0, max = 100.0, step = 5.0, unit = "%", height = 24.dp
        )
    }
}

@Composable
private fun PaintToolDetailsColumn(editor: CanvasEditor, target: CanvasTarget?) {
    val colors = LocalToolColors.current
    val typography = LocalToolTypography.current
    val paintT = editor.paintTarget()
    val layerId = editor.targetLayerId(paintT)
    val layerName = layerId?.let { lid ->
        editor.state.previewModel?.rig?.puppet?.drawables
            ?.firstOrNull { editor.state.previewModel?.rig?.layerIdByDrawableId?.get(it.id.raw) == lid }
            ?.name ?: lid
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Target layer status
        Text(
            text = tr("editor.paintTargetLayer"),
            style = typography.caption.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold),
            color = colors.textPrimary,
        )
        if (paintT != null && layerId != null) {
            Text(
                text = layerName ?: layerId,
                style = typography.caption.copy(fontSize = 11.sp),
                color = colors.accent,
                maxLines = 1,
            )
        } else {
            Text(
                text = tr("editor.paintSelectLayerHint"),
                style = typography.caption.copy(fontSize = 10.5.sp),
                color = colors.error,
            )
        }

        Divider(color = colors.divider, thickness = 0.5.dp)

        // Palette & Swatches
        Text(
            text = tr("editor.paintPalette"),
            style = typography.caption.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold),
            color = colors.textPrimary,
        )
        // Foreground and background, drawn the way every paint program draws them: two overlapping
        // squares with the one in hand in front, the swap beside them, and the values written out - a
        // colour is quoted as often as it is judged. X swaps them from the canvas, where the hand is.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(modifier = Modifier.size(42.dp, 38.dp)) {
                PaintColorChip(
                    color = editor.paintSecondaryColor,
                    onColorChanged = { editor.paintSecondaryColor = it },
                    modifier = Modifier.align(Alignment.BottomEnd).size(26.dp),
                    popupOffset = 30.dp,
                    border = BorderStroke(1.dp, colors.border),
                )
                PaintColorChip(
                    color = editor.paintColor,
                    onColorChanged = { editor.paintColor = it },
                    modifier = Modifier.align(Alignment.TopStart).size(28.dp),
                    popupOffset = 32.dp,
                    border = BorderStroke(1.5.dp, colors.accent),
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = "${tr("editor.paint.foreground")}  ${editor.paintColor.toHex()}",
                    color = colors.textPrimary,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                )
                Text(
                    text = "${tr("editor.paint.background")}  ${editor.paintSecondaryColor.toHex()}",
                    color = colors.textMuted,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                )
            }
            CompactButton(
                text = "⇄",
                onClick = { editor.swapPaintColors() },
                height = 24.dp,
            )
        }

        // Swatches grid
        val swatches = listOf(
            Color(0xFF000000), Color(0xFFFFFFFF), Color(0xFF7F7F7F), Color(0xFFC3C3C3),
            Color(0xFFED1C24), Color(0xFFFF7F27), Color(0xFFFFF200), Color(0xFF22B14C),
            Color(0xFF00A2E8), Color(0xFF3F48CC), Color(0xFFA349A4), Color(0xFFFFAEC9),
            Color(0xFFFFDFC4), Color(0xFFB97A57)
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            swatches.take(7).forEach { col ->
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(col)
                        .border(1.dp, if (editor.paintColor == col) colors.accent else colors.border, RoundedCornerShape(3.dp))
                        .clickable { editor.paintColor = col }
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            swatches.drop(7).forEach { col ->
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(col)
                        .border(1.dp, if (editor.paintColor == col) colors.accent else colors.border, RoundedCornerShape(3.dp))
                        .clickable { editor.paintColor = col }
                )
            }
        }

        Divider(color = colors.divider, thickness = 0.5.dp)

        // Tool-specific parameter rows
        when (editor.tool) {
            CanvasTool.PAINT_BRUSH -> {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(tr("editor.radius"), color = colors.textMuted, fontSize = 11.sp, modifier = Modifier.width(42.dp))
                    CompactNumberSpinner(
                        value = editor.paintBrushSize.toDouble(),
                        onValueChange = { editor.paintBrushSize = it.toFloat().coerceIn(1f, 256f) },
                        modifier = Modifier.weight(1f),
                        min = 1.0, max = 256.0, step = 1.0, unit = "px", height = 24.dp
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(tr("editor.hardness"), color = colors.textMuted, fontSize = 11.sp, modifier = Modifier.width(42.dp))
                    CompactNumberSpinner(
                        value = (editor.paintHardness * 100).toDouble(),
                        onValueChange = { editor.paintHardness = (it.toFloat() / 100f).coerceIn(0f, 1f) },
                        modifier = Modifier.weight(1f),
                        min = 0.0, max = 100.0, step = 5.0, unit = "%", height = 24.dp
                    )
                }
                PaintOpacityRow(editor, colors)
            }
            CanvasTool.PAINT_PENCIL -> {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(tr("editor.radius"), color = colors.textMuted, fontSize = 11.sp, modifier = Modifier.width(42.dp))
                    CompactNumberSpinner(
                        value = editor.paintPencilSize.toDouble(),
                        onValueChange = { editor.paintPencilSize = it.toFloat().coerceIn(1f, 64f) },
                        modifier = Modifier.weight(1f),
                        min = 1.0, max = 64.0, step = 1.0, unit = "px", height = 24.dp
                    )
                }
                PaintOpacityRow(editor, colors)
            }
            CanvasTool.PAINT_ERASER -> {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(tr("editor.radius"), color = colors.textMuted, fontSize = 11.sp, modifier = Modifier.width(42.dp))
                    CompactNumberSpinner(
                        value = editor.paintEraserSize.toDouble(),
                        onValueChange = { editor.paintEraserSize = it.toFloat().coerceIn(1f, 256f) },
                        modifier = Modifier.weight(1f),
                        min = 1.0, max = 256.0, step = 2.0, unit = "px", height = 24.dp
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(tr("editor.hardness"), color = colors.textMuted, fontSize = 11.sp, modifier = Modifier.width(42.dp))
                    CompactNumberSpinner(
                        value = (editor.paintHardness * 100).toDouble(),
                        onValueChange = { editor.paintHardness = (it.toFloat() / 100f).coerceIn(0f, 1f) },
                        modifier = Modifier.weight(1f),
                        min = 0.0, max = 100.0, step = 5.0, unit = "%", height = 24.dp
                    )
                }
                PaintOpacityRow(editor, colors)
            }
            CanvasTool.PAINT_BUCKET -> {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(tr("editor.tolerance"), color = colors.textMuted, fontSize = 11.sp, modifier = Modifier.width(42.dp))
                    CompactNumberSpinner(
                        value = editor.paintTolerance.toDouble(),
                        onValueChange = { editor.paintTolerance = it.toInt().coerceIn(0, 255) },
                        modifier = Modifier.weight(1f),
                        min = 0.0, max = 255.0, step = 4.0, height = 24.dp
                    )
                }
            }
            CanvasTool.PAINT_SHAPE -> {
                // The three faces, the same way the deform brush offers its own.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    PaintShape.entries.forEach { shape ->
                        CompactToggleChip(
                            text = tr(shape.labelKey),
                            selected = editor.paintShape == shape,
                            onToggle = { editor.selectPaintShape(shape) },
                            height = 24.dp,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(tr("editor.width"), color = colors.textMuted, fontSize = 11.sp, modifier = Modifier.width(42.dp))
                    CompactNumberSpinner(
                        value = editor.paintBrushSize.toDouble(),
                        onValueChange = { editor.paintBrushSize = it.toFloat().coerceIn(1f, 128f) },
                        modifier = Modifier.weight(1f),
                        min = 1.0, max = 128.0, step = 1.0, unit = "px", height = 24.dp
                    )
                }
                // A line has no inside, so the fill choice belongs to the box shapes only.
                AnimatedVisibility(
                    visible = editor.paintShape.canFill,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut(),
                ) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        CompactToggleChip(
                            text = tr("editor.outline"),
                            selected = !editor.paintShapeFilled,
                            onToggle = { editor.paintShapeFilled = false },
                            height = 24.dp,
                            modifier = Modifier.weight(1f),
                        )
                        CompactToggleChip(
                            text = tr("editor.filled"),
                            selected = editor.paintShapeFilled,
                            onToggle = { editor.paintShapeFilled = true },
                            height = 24.dp,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            else -> {}
        }

        Divider(color = colors.divider, thickness = 0.5.dp)

        // Actions
        val hasSession = editor.paintSession != null
        val uncommittedCount = editor.paintSession?.strokeCount ?: 0
        if (paintT != null && layerId != null) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CompactButton(
                    text = tr("editor.undo"),
                    onClick = { editor.undoPaint() },
                    enabled = editor.canUndoPaint(),
                    modifier = Modifier.weight(1f),
                    height = 24.dp,
                )
                CompactButton(
                    text = tr("editor.redo"),
                    onClick = { editor.redoPaint() },
                    enabled = editor.canRedoPaint(),
                    modifier = Modifier.weight(1f),
                    height = 24.dp,
                )
            }
            CompactButton(
                text = tr("editor.paintClear"),
                onClick = { editor.clearCurrentLayerPaint() },
                modifier = Modifier.fillMaxWidth(),
                height = 24.dp,
            )

            Spacer(Modifier.height(4.dp))

            // Commit / Discard
            CompactButton(
                text = if (uncommittedCount > 0) tr("editor.paint.applyCount", uncommittedCount) else tr("editor.paint.apply"),
                onClick = { editor.promptCommitPaintSession() },
                enabled = hasSession && (editor.paintSession?.isDirty == true || uncommittedCount > 0),
                isPrimary = true,
                modifier = Modifier.fillMaxWidth(),
                height = 26.dp,
            )
            CompactButton(
                text = tr("editor.paint.discard"),
                onClick = { editor.discardPaintSession() },
                enabled = hasSession && (editor.paintSession?.isDirty == true || uncommittedCount > 0),
                modifier = Modifier.fillMaxWidth(),
                height = 24.dp,
            )
        }
    }
}
