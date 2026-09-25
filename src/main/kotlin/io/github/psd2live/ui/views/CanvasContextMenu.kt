package io.github.psd2live.ui.views

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.BrushShape
import io.github.psd2live.ui.CREATION_TOOLS
import io.github.psd2live.ui.CanvasEditor
import io.github.psd2live.ui.CanvasTool
import io.github.psd2live.ui.CreatePlacementKind
import io.github.psd2live.ui.CreateRelation
import io.github.psd2live.ui.EditHierarchyMode
import io.github.psd2live.ui.PaintShape
import io.github.psd2live.ui.components.CompactButton
import io.github.psd2live.ui.components.CompactMenuDivider
import io.github.psd2live.ui.components.CompactMenuItem
import io.github.psd2live.ui.components.CompactSlider
import io.github.psd2live.ui.components.CompactToggleChip
import io.github.psd2live.ui.components.IconCheck
import io.github.psd2live.ui.components.IconClose
import io.github.psd2live.ui.components.IconDeformPath
import io.github.psd2live.ui.components.IconRotationDeformer
import io.github.psd2live.ui.components.IconTrash
import io.github.psd2live.ui.components.IconWarpDeformer
import io.github.psd2live.ui.components.PaintFgBgSwatch
import io.github.psd2live.ui.components.TreeContextMenu
import io.github.psd2live.ui.components.toHex
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.Fill

internal fun canvasContextMenuHasContent(editor: CanvasEditor): Boolean {
    if (editor.placement != null) return true
    if (editor.tool in CREATION_TOOLS) return true
    return when (editor.hierarchyMode) {
        EditHierarchyMode.SELECT -> editor.tool in setOf(CanvasTool.SELECT, CanvasTool.LASSO_SELECT)
        EditHierarchyMode.DEFORM,
        EditHierarchyMode.EDIT,
        EditHierarchyMode.PAINT -> true
    }
}

@Composable
internal fun CanvasContextMenu(
    editor: CanvasEditor,
    expanded: Boolean,
    clickOffset: Offset,
    onDismissRequest: () -> Unit,
    onAction: () -> Unit = {},
) {
    if (!expanded && !canvasContextMenuHasContent(editor)) return

    TreeContextMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        clickOffset = clickOffset,
        minWidth = 220.dp,
        maxWidth = 248.dp,
        frosted = true,
    ) {
        Column(
            modifier = Modifier
                .heightIn(max = 380.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            if (editor.placement != null) {
                PlacementContextMenuContent(editor, onDismissRequest, onAction)
                return@Column
            }
            if (editor.tool in CREATION_TOOLS) {
                CreationToolContextMenuContent(editor, onDismissRequest, onAction)
                return@Column
            }
            when (editor.hierarchyMode) {
                EditHierarchyMode.SELECT -> SelectModeContextMenu(editor, onDismissRequest, onAction)
                EditHierarchyMode.DEFORM -> DeformModeContextMenu(editor, onDismissRequest, onAction)
                EditHierarchyMode.EDIT -> EditModeContextMenu(editor, onDismissRequest, onAction)
                EditHierarchyMode.PAINT -> PaintModeContextMenu(editor, onDismissRequest, onAction)
            }
        }
    }
}

@Composable
private fun MenuSectionLabel(title: String) {
    val colors = LocalToolColors.current
    val typography = LocalToolTypography.current
    Text(
        text = title.uppercase(),
        style = typography.caption.copy(
            fontSize = 8.5.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.4.sp,
        ),
        color = colors.textMuted.copy(alpha = 0.75f),
        modifier = Modifier.padding(start = 6.dp, end = 6.dp, top = 3.dp, bottom = 1.dp),
    )
}

// ─── SELECT mode ─────────────────────────────────────────────────────────────

@Composable
private fun ColumnScope.SelectModeContextMenu(
    editor: CanvasEditor,
    onDismissRequest: () -> Unit,
    onAction: () -> Unit,
) {
    when (editor.tool) {
        CanvasTool.SELECT, CanvasTool.LASSO_SELECT -> {
            SelectionActionsSection(editor, onDismissRequest, onAction, objectMode = true)
            if (editor.target()?.kind == "mesh") {
                CompactMenuDivider()
                MenuSectionLabel(tr("editor.deformers"))
                ActionGrid(
                    listOf(
                        ActionSpec(
                            tr("editor.createWarp"),
                            enabled = editor.editable,
                            icon = { IconWarpDeformer(it, Modifier.size(12.dp)) },
                        ) {
                            // Same place-then-confirm entry as the hierarchy-tree Add menu.
                            val meshId = editor.target()?.id ?: return@ActionSpec
                            editor.beginTreeCreate(
                                CreatePlacementKind.WARP,
                                CreateRelation.AS_PARENT,
                                false,
                                meshId,
                            )
                            onAction()
                            onDismissRequest()
                        },
                        ActionSpec(
                            tr("editor.createRotation"),
                            enabled = editor.editable,
                            icon = { IconRotationDeformer(modifier = Modifier.size(12.dp), tint = it) },
                        ) {
                            val meshId = editor.target()?.id ?: return@ActionSpec
                            editor.beginTreeCreate(
                                CreatePlacementKind.ROTATION,
                                CreateRelation.AS_PARENT,
                                false,
                                meshId,
                            )
                            onAction()
                            onDismissRequest()
                        },
                        ActionSpec(
                            tr("editor.treeAddPath"),
                            enabled = editor.editable,
                            icon = { IconDeformPath(modifier = Modifier.size(12.dp), tint = it) },
                        ) {
                            val meshId = editor.target()?.id ?: return@ActionSpec
                            editor.beginTreeCreate(
                                CreatePlacementKind.PATH,
                                CreateRelation.AS_CHILD,
                                false,
                                meshId,
                            )
                            onAction()
                            onDismissRequest()
                        },
                    )
                )
            }
        }
        else -> Unit
    }
}

// ─── DEFORM mode ─────────────────────────────────────────────────────────────

@Composable
private fun ColumnScope.DeformModeContextMenu(
    editor: CanvasEditor,
    onDismissRequest: () -> Unit,
    onAction: () -> Unit,
) {
    when (editor.tool) {
        CanvasTool.SELECT, CanvasTool.LASSO_SELECT -> {
            SelectionActionsSection(editor, onDismissRequest, onAction, objectMode = false)
        }
        CanvasTool.BRUSH_SELECT -> {
            ParamsPanel {
                ParamSliderRow(
                    label = tr("editor.radius"),
                    value = editor.radius,
                    onValueChange = { editor.radius = it },
                    valueRange = 1f..500f,
                    display = "${editor.radius.toInt()}px",
                )
            }
            CompactMenuDivider()
            SelectionActionsSection(editor, onDismissRequest, onAction, objectMode = false)
        }
        CanvasTool.BRUSH, CanvasTool.SMOOTH, CanvasTool.INFLATE -> DeformBrushParamsSection(editor)
        else -> Unit
    }
}

// ─── EDIT mode ───────────────────────────────────────────────────────────────

@Composable
private fun ColumnScope.EditModeContextMenu(
    editor: CanvasEditor,
    onDismissRequest: () -> Unit,
    onAction: () -> Unit,
) {
    val isMesh = editor.target()?.kind == "mesh"
    when (editor.tool) {
        CanvasTool.SELECT, CanvasTool.LASSO_SELECT -> {
            if (isMesh) {
                ElementModeSection(editor)
                CompactMenuDivider()
                TopologySection(editor, onDismissRequest, onAction)
                CompactMenuDivider()
            }
            SelectionActionsSection(editor, onDismissRequest, onAction, objectMode = false)
        }
        CanvasTool.BRUSH_SELECT -> {
            if (isMesh) {
                ElementModeSection(editor)
                CompactMenuDivider()
            }
            ParamsPanel {
                ParamSliderRow(
                    label = tr("editor.radius"),
                    value = editor.radius,
                    onValueChange = { editor.radius = it },
                    valueRange = 1f..500f,
                    display = "${editor.radius.toInt()}px",
                )
            }
            if (isMesh) {
                CompactMenuDivider()
                TopologySection(editor, onDismissRequest, onAction)
                CompactMenuDivider()
            }
            SelectionActionsSection(editor, onDismissRequest, onAction, objectMode = false)
        }
        CanvasTool.BRUSH, CanvasTool.SMOOTH, CanvasTool.INFLATE -> {
            DeformBrushParamsSection(editor)
            if (isMesh) {
                CompactMenuDivider()
                TopologySection(editor, onDismissRequest, onAction)
            }
        }
        CanvasTool.SUBDIVIDE -> {
            ParamsPanel {
                ParamSliderRow(
                    label = tr("editor.radius"),
                    value = editor.radius,
                    onValueChange = { editor.radius = it },
                    valueRange = 1f..500f,
                    display = "${editor.radius.toInt()}px",
                )
            }
            Spacer(Modifier.height(3.dp))
            CompactButton(
                text = tr("editor.subdivide"),
                onClick = { editor.topology("subdivide"); onAction(); onDismissRequest() },
                enabled = editor.editable && editor.vertices.isNotEmpty(),
                isPrimary = true,
                leadingIcon = {
                    IconMenuSubdivide(LocalToolColors.current.accentText, Modifier.size(12.dp))
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 3.dp),
                height = 22.dp,
            )
            if (isMesh) {
                CompactMenuDivider()
                TopologySection(editor, onDismissRequest, onAction, exclude = setOf("subdivide"))
            }
        }
        CanvasTool.KNIFE -> {
            ParamsPanel {
                ParamSliderRow(
                    label = tr("editor.knifeSnapRadius"),
                    value = editor.knifeSnapRadius,
                    onValueChange = { editor.knifeSnapRadius = it },
                    valueRange = 3f..30f,
                    display = "${editor.knifeSnapRadius.toInt()}px",
                )
            }
            Spacer(Modifier.height(3.dp))
            ActionGrid(
                listOf(
                    ActionSpec(
                        tr("editor.finishCut"),
                        enabled = editor.editable && editor.knifeDraft.size >= 2,
                        primary = true,
                        icon = { IconCheck(modifier = Modifier.size(11.dp), tint = it) },
                    ) { editor.finishKnife(); onAction(); onDismissRequest() },
                    ActionSpec(
                        tr("editor.undoPoint"),
                        enabled = !editor.busy && editor.knifeDraft.isNotEmpty(),
                        icon = { IconMenuUndo(it, Modifier.size(12.dp)) },
                    ) { editor.undoDraftPoint(); onAction() },
                )
            )
            CompactButton(
                text = tr("action.cancel"),
                onClick = { editor.cancel(); onAction(); onDismissRequest() },
                enabled = !editor.busy && editor.knifeDraft.isNotEmpty(),
                danger = true,
                leadingIcon = {
                    IconClose(
                        modifier = Modifier.size(9.dp),
                        tint = if (!editor.busy && editor.knifeDraft.isNotEmpty()) {
                            LocalToolColors.current.error
                        } else {
                            LocalToolColors.current.textDisabled
                        },
                    )
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 3.dp, vertical = 2.dp),
                height = 22.dp,
            )
        }
        else -> Unit
    }
}

// ─── PAINT mode ──────────────────────────────────────────────────────────────

@Composable
private fun ColumnScope.PaintModeContextMenu(
    editor: CanvasEditor,
    onDismissRequest: () -> Unit,
    onAction: () -> Unit,
) {
    when (editor.tool) {
        CanvasTool.PAINT_BRUSH -> {
            PaintColorBlock(editor)
            ParamsPanel {
                ParamSliderRow(
                    label = tr("editor.radius"),
                    value = editor.paintBrushSize,
                    onValueChange = { editor.paintBrushSize = it },
                    valueRange = 1f..256f,
                    display = "${editor.paintBrushSize.toInt()}px",
                )
                ParamSliderRow(
                    label = tr("editor.hardness"),
                    value = editor.paintHardness * 100f,
                    onValueChange = { editor.paintHardness = (it / 100f).coerceIn(0f, 1f) },
                    valueRange = 0f..100f,
                    display = "${(editor.paintHardness * 100).toInt()}%",
                )
                ParamSliderRow(
                    label = tr("editor.opacity"),
                    value = editor.paintOpacity * 100f,
                    onValueChange = { editor.paintOpacity = (it / 100f).coerceIn(0.01f, 1f) },
                    valueRange = 1f..100f,
                    display = "${(editor.paintOpacity * 100).toInt()}%",
                )
            }
            CompactMenuDivider()
            PaintHistorySection(editor, onDismissRequest, onAction)
        }
        CanvasTool.PAINT_PENCIL -> {
            PaintColorBlock(editor)
            ParamsPanel {
                ParamSliderRow(
                    label = tr("editor.radius"),
                    value = editor.paintPencilSize,
                    onValueChange = { editor.paintPencilSize = it },
                    valueRange = 1f..64f,
                    display = "${editor.paintPencilSize.toInt()}px",
                )
                ParamSliderRow(
                    label = tr("editor.opacity"),
                    value = editor.paintOpacity * 100f,
                    onValueChange = { editor.paintOpacity = (it / 100f).coerceIn(0.01f, 1f) },
                    valueRange = 1f..100f,
                    display = "${(editor.paintOpacity * 100).toInt()}%",
                )
            }
            CompactMenuDivider()
            PaintHistorySection(editor, onDismissRequest, onAction)
        }
        CanvasTool.PAINT_ERASER -> {
            ParamsPanel {
                ParamSliderRow(
                    label = tr("editor.radius"),
                    value = editor.paintEraserSize,
                    onValueChange = { editor.paintEraserSize = it },
                    valueRange = 1f..256f,
                    display = "${editor.paintEraserSize.toInt()}px",
                )
                ParamSliderRow(
                    label = tr("editor.hardness"),
                    value = editor.paintHardness * 100f,
                    onValueChange = { editor.paintHardness = (it / 100f).coerceIn(0f, 1f) },
                    valueRange = 0f..100f,
                    display = "${(editor.paintHardness * 100).toInt()}%",
                )
                ParamSliderRow(
                    label = tr("editor.opacity"),
                    value = editor.paintOpacity * 100f,
                    onValueChange = { editor.paintOpacity = (it / 100f).coerceIn(0.01f, 1f) },
                    valueRange = 1f..100f,
                    display = "${(editor.paintOpacity * 100).toInt()}%",
                )
            }
            CompactMenuDivider()
            PaintHistorySection(editor, onDismissRequest, onAction)
        }
        CanvasTool.PAINT_BUCKET -> {
            PaintColorBlock(editor)
            ParamsPanel {
                ParamSliderRow(
                    label = tr("editor.tolerance"),
                    value = editor.paintTolerance.toFloat(),
                    onValueChange = { editor.paintTolerance = it.toInt().coerceIn(0, 255) },
                    valueRange = 0f..255f,
                    display = "${editor.paintTolerance}",
                )
            }
            CompactMenuDivider()
            PaintHistorySection(editor, onDismissRequest, onAction)
        }
        CanvasTool.PAINT_EYEDROPPER -> {
            PaintColorBlock(editor)
            CompactMenuDivider()
            PaintHistorySection(editor, onDismissRequest, onAction)
        }
        CanvasTool.PAINT_SHAPE -> {
            PaintColorBlock(editor)
            ParamsPanel {
                SegmentedChips {
                    PaintShape.entries.forEach { shape ->
                        CompactToggleChip(
                            text = tr(shape.labelKey),
                            selected = editor.paintShape == shape,
                            onToggle = { editor.selectPaintShape(shape) },
                            showCheckWhenSelected = false,
                            height = 20.dp,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                ParamSliderRow(
                    label = tr("editor.width"),
                    value = editor.paintBrushSize,
                    onValueChange = { editor.paintBrushSize = it },
                    valueRange = 1f..128f,
                    display = "${editor.paintBrushSize.toInt()}px",
                )
                ParamSliderRow(
                    label = tr("editor.opacity"),
                    value = editor.paintOpacity * 100f,
                    onValueChange = { editor.paintOpacity = (it / 100f).coerceIn(0.01f, 1f) },
                    valueRange = 1f..100f,
                    display = "${(editor.paintOpacity * 100).toInt()}%",
                )
                if (editor.paintShape.canFill) {
                    SegmentedChips {
                        CompactToggleChip(
                            text = tr("editor.outline"),
                            selected = !editor.paintShapeFilled,
                            onToggle = { editor.paintShapeFilled = false },
                            showCheckWhenSelected = false,
                            height = 20.dp,
                            modifier = Modifier.weight(1f),
                        )
                        CompactToggleChip(
                            text = tr("editor.filled"),
                            selected = editor.paintShapeFilled,
                            onToggle = { editor.paintShapeFilled = true },
                            showCheckWhenSelected = false,
                            height = 20.dp,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            CompactMenuDivider()
            PaintHistorySection(editor, onDismissRequest, onAction)
        }
        else -> PaintHistorySection(editor, onDismissRequest, onAction)
    }
}

// ─── Creation / placement ────────────────────────────────────────────────────

@Composable
private fun ColumnScope.PlacementContextMenuContent(
    editor: CanvasEditor,
    onDismissRequest: () -> Unit,
    onAction: () -> Unit,
) {
    ActionGrid(
        listOf(
            ActionSpec(tr("editor.placementConfirm"), enabled = editor.editable, primary = true, icon = {
                IconCheck(modifier = Modifier.size(11.dp), tint = it)
            }) {
                editor.confirmPlacement(); onAction(); onDismissRequest()
            },
            ActionSpec(tr("editor.placementCancel"), danger = true, icon = {
                IconClose(modifier = Modifier.size(9.dp), tint = it)
            }) {
                editor.cancelPlacement(); onAction(); onDismissRequest()
            },
        )
    )
}

@Composable
private fun ColumnScope.CreationToolContextMenuContent(
    editor: CanvasEditor,
    onDismissRequest: () -> Unit,
    onAction: () -> Unit,
) {
    when (editor.tool) {
        CanvasTool.CREATE_WARP, CanvasTool.CREATE_ROTATION -> {
            CompactButton(
                text = tr("editor.createFromSelection"),
                onClick = {
                    editor.createWarp(editor.tool == CanvasTool.CREATE_ROTATION)
                    onAction(); onDismissRequest()
                },
                enabled = editor.editable && editor.target()?.kind == "mesh",
                leadingIcon = {
                    val tint = if (editor.editable && editor.target()?.kind == "mesh") {
                        LocalToolColors.current.textMuted
                    } else LocalToolColors.current.textDisabled
                    if (editor.tool == CanvasTool.CREATE_ROTATION) {
                        IconRotationDeformer(modifier = Modifier.size(12.dp), tint = tint)
                    } else {
                        IconWarpDeformer(tint, Modifier.size(12.dp))
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 3.dp),
                height = 22.dp,
            )
            CompactButton(
                text = tr("action.cancel"),
                onClick = { editor.cancel(); onAction(); onDismissRequest() },
                danger = true,
                leadingIcon = { IconClose(modifier = Modifier.size(9.dp), tint = LocalToolColors.current.error) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 3.dp, vertical = 2.dp),
                height = 22.dp,
            )
        }
        CanvasTool.CREATE_DEFORM_PATH -> {
            if (editor.drawingPath) {
                ActionGrid(
                    listOf(
                        ActionSpec(
                            tr("editor.finishPath"),
                            enabled = editor.draft.size >= 2,
                            primary = true,
                            icon = { IconCheck(modifier = Modifier.size(11.dp), tint = it) },
                        ) {
                            editor.finishPath(); onAction(); onDismissRequest()
                        },
                        ActionSpec(
                            tr("editor.undoPoint"),
                            enabled = editor.draft.isNotEmpty(),
                            icon = { IconMenuUndo(it, Modifier.size(12.dp)) },
                        ) {
                            editor.undoDraftPoint(); onAction()
                        },
                    )
                )
            } else {
                CompactButton(
                    text = tr("editor.newPath"),
                    onClick = { editor.cancel(); editor.drawingPath = true; onAction(); onDismissRequest() },
                    enabled = editor.target()?.kind == "mesh" && editor.editable,
                    leadingIcon = {
                        IconDeformPath(
                            modifier = Modifier.size(12.dp),
                            tint = if (editor.target()?.kind == "mesh" && editor.editable) {
                                LocalToolColors.current.textMuted
                            } else LocalToolColors.current.textDisabled,
                        )
                    },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 3.dp),
                    height = 22.dp,
                )
            }
            CompactButton(
                text = tr("action.cancel"),
                onClick = { editor.cancel(); onAction(); onDismissRequest() },
                danger = true,
                leadingIcon = { IconClose(modifier = Modifier.size(9.dp), tint = LocalToolColors.current.error) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 3.dp, vertical = 2.dp),
                height = 22.dp,
            )
        }
        CanvasTool.GLUE -> {
            val pair = editor.glueMeshPair()
            val pairs = if (pair != null) editor.gluePreviewPoints().size else 0
            ParamsPanel {
                ParamSliderRow(
                    label = tr("editor.glueDistance"),
                    value = editor.glueDistance,
                    onValueChange = { editor.glueDistance = it },
                    valueRange = 1f..500f,
                    display = "${editor.glueDistance.toInt()}px",
                )
            }
            if (pair == null) {
                Text(
                    text = tr("editor.glueNeedTwo", editor.glueMeshCount()),
                    style = LocalToolTypography.current.caption.copy(fontSize = 10.5.sp),
                    color = LocalToolColors.current.warning,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            } else {
                Text(
                    text = "${tr("editor.glueMeshA")} ${editor.meshLabel(pair.first)}",
                    style = LocalToolTypography.current.caption.copy(fontSize = 10.5.sp),
                    color = LocalToolColors.current.textPrimary,
                    modifier = Modifier.padding(horizontal = 6.dp),
                )
                Text(
                    text = "${tr("editor.glueMeshB")} ${editor.meshLabel(pair.second)}",
                    style = LocalToolTypography.current.caption.copy(fontSize = 10.5.sp),
                    color = LocalToolColors.current.textPrimary,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            CompactButton(
                text = tr("editor.glueSwap"),
                onClick = { editor.swapGlueEnds(); onAction() },
                enabled = pair != null && editor.editable,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 3.dp, vertical = 2.dp),
                height = 22.dp,
            )
            CompactButton(
                text = tr(if (editor.glueAlreadyBound()) "editor.glueReplace" else "editor.glueCreate"),
                onClick = { editor.applyGlue(); onAction(); onDismissRequest() },
                enabled = pair != null && pairs > 0 && editor.editable,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 3.dp, vertical = 2.dp),
                height = 22.dp,
            )
        }
        else -> Unit
    }
}

// ─── Shared sections ─────────────────────────────────────────────────────────

@Composable
private fun ColumnScope.SelectionActionsSection(
    editor: CanvasEditor,
    onDismissRequest: () -> Unit,
    onAction: () -> Unit,
    objectMode: Boolean,
) {
    MenuSectionLabel(tr("editor.selectionMode"))
    val colors = LocalToolColors.current
    CompactMenuItem(
        text = tr("shortcut.selectAll"),
        onClick = { editor.selectAll(invert = false); onAction(); onDismissRequest() },
        enabled = editor.editable,
        icon = {
            IconMenuSelectAll(if (editor.editable) colors.textMuted else colors.textDisabled, Modifier.size(13.dp))
        },
    )
    CompactMenuItem(
        text = tr("help.shortcuts.invertSelection"),
        onClick = { editor.selectAll(invert = true); onAction(); onDismissRequest() },
        enabled = editor.editable,
        icon = {
            IconMenuInvert(if (editor.editable) colors.textMuted else colors.textDisabled, Modifier.size(13.dp))
        },
    )
    if (!objectMode) {
        CompactMenuItem(
            text = tr("shortcut.selectLinked"),
            onClick = { editor.selectLinked(); onAction(); onDismissRequest() },
            enabled = editor.editable && editor.vertices.isNotEmpty(),
            icon = {
                IconMenuLinked(
                    if (editor.editable && editor.vertices.isNotEmpty()) colors.textMuted else colors.textDisabled,
                    Modifier.size(13.dp),
                )
            },
        )
    }
    CompactMenuItem(
        text = tr("editor.deselect"),
        onClick = {
            if (objectMode) editor.objects = emptySet()
            else {
                editor.vertices = emptySet()
                editor.selectedEdges = emptySet()
                editor.selectedFaces = emptySet()
            }
            onAction(); onDismissRequest()
        },
        enabled = if (objectMode) editor.objects.isNotEmpty() else editor.vertices.isNotEmpty(),
        icon = {
            val on = if (objectMode) editor.objects.isNotEmpty() else editor.vertices.isNotEmpty()
            IconClose(modifier = Modifier.size(10.dp), tint = if (on) colors.textMuted else colors.textDisabled)
        },
    )
}

@Composable
private fun ColumnScope.ElementModeSection(editor: CanvasEditor) {
    MenuSectionLabel(tr("editor.elementMode"))
    ParamsPanel {
        SegmentedChips {
            listOf("vertex", "edge", "face").forEachIndexed { i, key ->
                CompactToggleChip(
                    text = tr("editor.$key"),
                    selected = editor.elementMode == i,
                    onToggle = { editor.elementMode = i },
                    showCheckWhenSelected = false,
                    height = 20.dp,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun ColumnScope.TopologySection(
    editor: CanvasEditor,
    onDismissRequest: () -> Unit,
    onAction: () -> Unit,
    exclude: Set<String> = emptySet(),
) {
    val enabled = editor.editable && editor.vertices.isNotEmpty()
    MenuSectionLabel(tr("editor.topology"))
    ActionGrid(
        listOf("split", "subdivide", "connect", "merge", "delete", "duplicate")
            .filter { it !in exclude }
            .map { action ->
                ActionSpec(
                    text = tr("editor.$action"),
                    enabled = enabled,
                    danger = action == "delete",
                    icon = { tint -> TopologyActionIcon(action, tint) },
                ) {
                    editor.topology(action)
                    onAction()
                    onDismissRequest()
                }
            }
    )
}

@Composable
private fun ColumnScope.DeformBrushParamsSection(editor: CanvasEditor) {
    MenuSectionLabel(tr("editor.brushSettings"))
    ParamsPanel {
        SegmentedChips {
            BrushShape.entries.forEach { shape ->
                CompactToggleChip(
                    text = tr(shape.labelKey),
                    selected = editor.brushShape == shape,
                    onToggle = { editor.brushShape = shape },
                    showCheckWhenSelected = false,
                    height = 20.dp,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        ParamSliderRow(
            label = tr("editor.radius"),
            value = editor.radius,
            onValueChange = { editor.radius = it },
            valueRange = 1f..500f,
            display = "${editor.radius.toInt()}px",
        )
        ParamSliderRow(
            label = tr("editor.hardness"),
            value = editor.hardness * 100f,
            onValueChange = { editor.hardness = (it / 100f).coerceIn(0f, 0.95f) },
            valueRange = 0f..95f,
            display = "${(editor.hardness * 100).toInt()}%",
        )
        ParamSliderRow(
            label = tr("editor.strength"),
            value = editor.strength * 100f,
            onValueChange = { editor.strength = (it / 100f).coerceIn(0.01f, 1f) },
            valueRange = 1f..100f,
            display = "${(editor.strength * 100).toInt()}%",
        )
        if (editor.brushShape != BrushShape.CIRCLE) {
            ParamSliderRow(
                label = tr("editor.angle"),
                value = editor.brushAngle,
                onValueChange = { editor.brushAngle = it.mod(360f) },
                valueRange = 0f..360f,
                display = "${editor.brushAngle.toInt()}°",
            )
        }
        if (editor.tool == CanvasTool.INFLATE) {
            SegmentedChips {
                CompactToggleChip(
                    text = tr("editor.mode.inflate"),
                    selected = !editor.inflateInvert,
                    onToggle = { editor.inflateInvert = false },
                    enabled = editor.editable,
                    showCheckWhenSelected = false,
                    height = 20.dp,
                    modifier = Modifier.weight(1f),
                )
                CompactToggleChip(
                    text = tr("editor.mode.shrink"),
                    selected = editor.inflateInvert,
                    onToggle = { editor.inflateInvert = true },
                    enabled = editor.editable,
                    showCheckWhenSelected = false,
                    height = 20.dp,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun ColumnScope.PaintHistorySection(
    editor: CanvasEditor,
    onDismissRequest: () -> Unit,
    onAction: () -> Unit,
) {
    val session = editor.paintSession
    MenuSectionLabel(tr("editor.history"))
    ActionGrid(
        listOf(
            ActionSpec(
                tr("editor.undo"),
                enabled = session != null && editor.canUndoPaint(),
                icon = { IconMenuUndo(it, Modifier.size(12.dp)) },
            ) {
                editor.undoPaint(); onAction(); onDismissRequest()
            },
            ActionSpec(
                tr("editor.redo"),
                enabled = session != null && editor.canRedoPaint(),
                icon = { IconMenuRedo(it, Modifier.size(12.dp)) },
            ) {
                editor.redoPaint(); onAction(); onDismissRequest()
            },
        )
    )
    CompactButton(
        text = tr("editor.paintClear"),
        onClick = { editor.clearCurrentLayerPaint(); onAction(); onDismissRequest() },
        enabled = session != null,
        danger = true,
        leadingIcon = {
            IconTrash(
                modifier = Modifier.size(11.dp),
                tint = if (session != null) LocalToolColors.current.error else LocalToolColors.current.textDisabled,
            )
        },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 3.dp, vertical = 2.dp),
        height = 22.dp,
    )
}

// ─── UI primitives ───────────────────────────────────────────────────────────

private data class ActionSpec(
    val text: String,
    val enabled: Boolean = true,
    val primary: Boolean = false,
    val danger: Boolean = false,
    val icon: (@Composable (Color) -> Unit)? = null,
    val onClick: () -> Unit,
)

@Composable
private fun ActionGrid(actions: List<ActionSpec>) {
    val colors = LocalToolColors.current
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 3.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        actions.chunked(2).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                row.forEach { action ->
                    val tint = when {
                        !action.enabled -> colors.textDisabled
                        action.danger -> colors.error
                        action.primary -> colors.accentText
                        else -> colors.textMuted
                    }
                    CompactButton(
                        text = action.text,
                        onClick = action.onClick,
                        enabled = action.enabled,
                        isPrimary = action.primary,
                        danger = action.danger,
                        leadingIcon = action.icon?.let { draw -> { draw(tint) } },
                        modifier = Modifier.weight(1f),
                        height = 22.dp,
                    )
                }
                if (row.size == 1) Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ParamsPanel(content: @Composable ColumnScope.() -> Unit) {
    val colors = LocalToolColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 3.dp, vertical = 1.dp)
            .background(colors.windowBackground.copy(alpha = 0.38f), RoundedCornerShape(4.dp))
            .padding(horizontal = 7.dp, vertical = 5.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
        content = content,
    )
}

@Composable
private fun SegmentedChips(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        content = content,
    )
}

@Composable
private fun ParamSliderRow(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    display: String,
) {
    val colors = LocalToolColors.current
    val typography = LocalToolTypography.current
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = label,
                color = colors.textMuted,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Text(
                text = display,
                color = colors.textPrimary,
                style = typography.monoSmall.copy(fontSize = 9.5.sp),
            )
        }
        CompactSlider(
            value = value.coerceIn(valueRange.start, valueRange.endInclusive),
            onValueChange = onValueChange,
            valueRange = valueRange,
            modifier = Modifier.fillMaxWidth(),
            height = 12.dp,
        )
    }
}

@Composable
private fun PaintColorBlock(editor: CanvasEditor) {
    val colors = LocalToolColors.current
    val typography = LocalToolTypography.current
    val swatches = rememberPaintSwatches()

    ParamsPanel {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PaintFgBgSwatch(
                foreground = editor.paintColor,
                background = editor.paintSecondaryColor,
                onForegroundChanged = { editor.paintColor = it },
                onBackgroundChanged = { editor.paintSecondaryColor = it },
                onSwap = { editor.swapPaintColors() },
                squareSize = 15.dp,
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    text = editor.paintColor.toHex(),
                    color = colors.textPrimary,
                    style = typography.monoSmall.copy(fontSize = 10.sp),
                    maxLines = 1,
                )
                Text(
                    text = editor.paintSecondaryColor.toHex(),
                    color = colors.textMuted,
                    style = typography.monoSmall.copy(fontSize = 9.sp),
                    maxLines = 1,
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            swatches.forEach { col ->
                val selected = editor.paintColor == col
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(12.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(col)
                        .border(
                            BorderStroke(1.dp, if (selected) colors.accent else colors.border.copy(alpha = 0.45f)),
                            RoundedCornerShape(2.dp),
                        )
                        .clickable { editor.paintColor = col },
                )
            }
        }
    }
}

@Composable
private fun rememberPaintSwatches(): List<Color> = listOf(
    Color(0xFF000000), Color(0xFFFFFFFF), Color(0xFF7F7F7F),
    Color(0xFFED1C24), Color(0xFFFF7F27), Color(0xFFFFF200),
    Color(0xFF22B14C), Color(0xFF00A2E8), Color(0xFF3F48CC),
    Color(0xFFA349A4),
)

@Composable
private fun TopologyActionIcon(action: String, tint: Color) {
    when (action) {
        "split" -> IconMenuSplit(tint, Modifier.size(12.dp))
        "subdivide" -> IconMenuSubdivide(tint, Modifier.size(12.dp))
        "connect" -> IconMenuConnect(tint, Modifier.size(12.dp))
        "merge" -> IconMenuMerge(tint, Modifier.size(12.dp))
        "delete" -> IconTrash(modifier = Modifier.size(11.dp), tint = tint)
        "duplicate" -> IconMenuDuplicate(tint, Modifier.size(12.dp))
        else -> Unit
    }
}

@Composable
private fun IconMenuUndo(tint: Color, modifier: Modifier = Modifier.size(12.dp)) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(1.3f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val arc = Path().apply {
            moveTo(w * 0.78f, h * 0.32f)
            cubicTo(w * 0.72f, h * 0.12f, w * 0.28f, h * 0.12f, w * 0.22f, h * 0.42f)
        }
        drawPath(arc, tint, style = stroke)
        val head = Path().apply {
            moveTo(w * 0.12f, h * 0.28f)
            lineTo(w * 0.22f, h * 0.48f)
            lineTo(w * 0.38f, h * 0.36f)
        }
        drawPath(head, tint, style = stroke)
        drawLine(tint, Offset(w * 0.22f, h * 0.72f), Offset(w * 0.78f, h * 0.72f), 1.3f, cap = StrokeCap.Round)
    }
}

@Composable
private fun IconMenuRedo(tint: Color, modifier: Modifier = Modifier.size(12.dp)) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(1.3f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val arc = Path().apply {
            moveTo(w * 0.22f, h * 0.32f)
            cubicTo(w * 0.28f, h * 0.12f, w * 0.72f, h * 0.12f, w * 0.78f, h * 0.42f)
        }
        drawPath(arc, tint, style = stroke)
        val head = Path().apply {
            moveTo(w * 0.88f, h * 0.28f)
            lineTo(w * 0.78f, h * 0.48f)
            lineTo(w * 0.62f, h * 0.36f)
        }
        drawPath(head, tint, style = stroke)
        drawLine(tint, Offset(w * 0.22f, h * 0.72f), Offset(w * 0.78f, h * 0.72f), 1.3f, cap = StrokeCap.Round)
    }
}

@Composable
private fun IconMenuSelectAll(tint: Color, modifier: Modifier = Modifier.size(12.dp)) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(1.2f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawRoundRect(
            tint,
            Offset(w * 0.12f, h * 0.12f),
            androidx.compose.ui.geometry.Size(w * 0.76f, h * 0.76f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.5f, 1.5f),
            style = stroke,
        )
        drawLine(tint, Offset(w * 0.28f, h * 0.38f), Offset(w * 0.42f, h * 0.55f), 1.3f, cap = StrokeCap.Round)
        drawLine(tint, Offset(w * 0.42f, h * 0.55f), Offset(w * 0.72f, h * 0.28f), 1.3f, cap = StrokeCap.Round)
    }
}

@Composable
private fun IconMenuInvert(tint: Color, modifier: Modifier = Modifier.size(12.dp)) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(1.2f, cap = StrokeCap.Round)
        drawCircle(tint, w * 0.36f, Offset(w * 0.5f, h * 0.5f), style = stroke)
        drawPath(
            Path().apply {
                moveTo(w * 0.5f, h * 0.14f)
                lineTo(w * 0.5f, h * 0.86f)
                arcTo(
                    androidx.compose.ui.geometry.Rect(w * 0.14f, h * 0.14f, w * 0.86f, h * 0.86f),
                    90f,
                    180f,
                    forceMoveTo = false,
                )
                close()
            },
            tint,
            style = Fill,
        )
    }
}

@Composable
private fun IconMenuLinked(tint: Color, modifier: Modifier = Modifier.size(12.dp)) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(1.2f, cap = StrokeCap.Round)
        drawCircle(tint, w * 0.18f, Offset(w * 0.28f, h * 0.5f), style = stroke)
        drawCircle(tint, w * 0.18f, Offset(w * 0.72f, h * 0.5f), style = stroke)
        drawLine(tint, Offset(w * 0.42f, h * 0.5f), Offset(w * 0.58f, h * 0.5f), 1.3f, cap = StrokeCap.Round)
    }
}

@Composable
private fun IconMenuSplit(tint: Color, modifier: Modifier = Modifier.size(12.dp)) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        drawLine(tint, Offset(w * 0.12f, h * 0.72f), Offset(w * 0.88f, h * 0.28f), 1.3f, cap = StrokeCap.Round)
        drawCircle(tint, w * 0.12f, Offset(w * 0.5f, h * 0.5f), style = Fill)
        drawCircle(tint, w * 0.1f, Offset(w * 0.18f, h * 0.68f), style = Stroke(1.1f))
        drawCircle(tint, w * 0.1f, Offset(w * 0.82f, h * 0.32f), style = Stroke(1.1f))
    }
}

@Composable
private fun IconMenuSubdivide(tint: Color, modifier: Modifier = Modifier.size(12.dp)) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(1.2f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val path = Path().apply {
            moveTo(w * 0.5f, h * 0.12f)
            lineTo(w * 0.12f, h * 0.86f)
            lineTo(w * 0.88f, h * 0.86f)
            close()
        }
        drawPath(path, tint, style = stroke)
        drawCircle(tint, w * 0.09f, Offset(w * 0.31f, h * 0.49f), style = Fill)
        drawCircle(tint, w * 0.09f, Offset(w * 0.69f, h * 0.49f), style = Fill)
        drawCircle(tint, w * 0.09f, Offset(w * 0.5f, h * 0.86f), style = Fill)
    }
}

@Composable
private fun IconMenuConnect(tint: Color, modifier: Modifier = Modifier.size(12.dp)) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        drawCircle(tint, w * 0.14f, Offset(w * 0.22f, h * 0.72f), style = Fill)
        drawCircle(tint, w * 0.14f, Offset(w * 0.78f, h * 0.28f), style = Fill)
        drawLine(tint, Offset(w * 0.3f, h * 0.64f), Offset(w * 0.7f, h * 0.36f), 1.3f, cap = StrokeCap.Round)
    }
}

@Composable
private fun IconMenuMerge(tint: Color, modifier: Modifier = Modifier.size(12.dp)) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(1.2f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawLine(tint, Offset(w * 0.18f, h * 0.28f), Offset(w * 0.5f, h * 0.5f), 1.3f, cap = StrokeCap.Round)
        drawLine(tint, Offset(w * 0.82f, h * 0.28f), Offset(w * 0.5f, h * 0.5f), 1.3f, cap = StrokeCap.Round)
        drawLine(tint, Offset(w * 0.5f, h * 0.5f), Offset(w * 0.5f, h * 0.82f), 1.3f, cap = StrokeCap.Round)
        drawCircle(tint, w * 0.12f, Offset(w * 0.5f, h * 0.5f), style = Fill)
        val left = Path().apply {
            moveTo(w * 0.18f, h * 0.28f)
            lineTo(w * 0.28f, h * 0.18f)
            lineTo(w * 0.3f, h * 0.34f)
        }
        val right = Path().apply {
            moveTo(w * 0.82f, h * 0.28f)
            lineTo(w * 0.72f, h * 0.18f)
            lineTo(w * 0.7f, h * 0.34f)
        }
        drawPath(left, tint, style = stroke)
        drawPath(right, tint, style = stroke)
    }
}

@Composable
private fun IconMenuDuplicate(tint: Color, modifier: Modifier = Modifier.size(12.dp)) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(1.2f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawRoundRect(
            tint,
            Offset(w * 0.12f, h * 0.28f),
            androidx.compose.ui.geometry.Size(w * 0.52f, h * 0.58f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.2f, 1.2f),
            style = stroke,
        )
        drawRoundRect(
            tint,
            Offset(w * 0.34f, h * 0.12f),
            androidx.compose.ui.geometry.Size(w * 0.52f, h * 0.58f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.2f, 1.2f),
            style = stroke,
        )
    }
}
