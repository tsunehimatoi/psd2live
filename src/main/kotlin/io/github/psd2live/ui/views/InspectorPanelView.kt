package io.github.psd2live.ui.views

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
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
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.CanvasEditor
import io.github.psd2live.ui.components.ColorPickerPopupContent
import io.github.psd2live.ui.components.CompactButton
import io.github.psd2live.ui.components.CompactCheckbox
import io.github.psd2live.ui.components.CompactDropdown
import io.github.psd2live.ui.components.CompactNumberSpinner
import io.github.psd2live.ui.components.CompactTextField
import io.github.psd2live.ui.components.IconChevron
import io.github.psd2live.ui.components.IconReset
import io.github.psd2live.ui.state.PSD2LiveState
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography
import org.umamo.edit.withDeformerPart
import org.umamo.edit.withDeformerQuadTransform
import org.umamo.edit.withDrawableDrawOrder
import org.umamo.edit.withOrgChildMoved
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.ColorRgb
import org.umamo.runtime.model.DeformPath
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.PartId
import java.awt.Cursor
import kotlin.math.roundToInt

@Composable
internal fun InspectorPanelView(
    editor: CanvasEditor,
    viewModel: PSD2LiveViewModel,
    state: PSD2LiveState,
    modifier: Modifier = Modifier,
) {
    val colors = LocalToolColors.current
    val typography = LocalToolTypography.current
    val puppet = state.previewModel?.rig?.puppet

    // Resolve active selection
    val selectedDeformerId = state.selectedDeformerId
    val selectedLayerId = state.selectedLayerId
    val layerDrawableId = state.previewModel?.rig?.layerIdByDrawableId?.entries?.firstOrNull { it.value == selectedLayerId }?.key
        ?: selectedLayerId

    val selectedDeformer = puppet?.deformers?.firstOrNull { it.id.raw == selectedDeformerId }
    val selectedDrawable = puppet?.drawables?.firstOrNull { it.id.raw == layerDrawableId || it.id.raw == selectedLayerId }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.panelBackground)
            .verticalScroll(rememberScrollState())
            .padding(vertical = 4.dp, horizontal = 6.dp),
    ) {
        // Tab Header bar matching screenshots
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
            Text(
                text = tr("tab.inspector"),
                style = typography.caption.copy(fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold),
                color = colors.textPrimary,
                modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
            )
            Divider(color = colors.textPrimary.copy(alpha = 0.6f), thickness = 1.5.dp)
        }

        when {
            selectedDeformer is Deformer.Warp -> {
                WarpDeformerInspector(
                    warp = selectedDeformer,
                    allParts = puppet.parts.map { it.id.raw to it.name },
                    allDeformers = puppet.deformers.map { it.id.raw to it.name },
                    editor = editor,
                    viewModel = viewModel,
                )
            }
            selectedDeformer is Deformer.Rotation -> {
                RotationDeformerInspector(
                    rotation = selectedDeformer,
                    allParts = puppet.parts.map { it.id.raw to it.name },
                    allDeformers = puppet.deformers.map { it.id.raw to it.name },
                    editor = editor,
                    viewModel = viewModel,
                )
            }
            selectedDrawable != null -> {
                val activePath = editor.selectedPath()?.takeIf { it.drawableId == selectedDrawable.id }
                Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (activePath != null) {
                        DeformPathInspector(
                            path = activePath,
                            drawable = selectedDrawable,
                            editor = editor,
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Divider(color = colors.border.copy(alpha = 0.55f), thickness = 1.dp)
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                    ArtMeshInspector(
                        drawable = selectedDrawable,
                        allParts = puppet.parts.map { it.id.raw to it.name },
                        allDeformers = puppet.deformers.map { it.id.raw to it.name },
                        allDrawables = puppet.drawables.map { it.id.raw to it.name },
                        editor = editor,
                        viewModel = viewModel,
                        state = state,
                    )
                }
            }
            else -> {
                // Empty state
                Box(
                    modifier = Modifier.fillMaxSize().padding(top = 48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = tr("inspector.noSelection"),
                            style = typography.body.copy(fontSize = 12.sp, fontWeight = FontWeight.Medium),
                            color = colors.textMuted,
                        )
                        Text(
                            text = tr("inspector.noSelectionHint"),
                            style = typography.caption.copy(fontSize = 10.5.sp),
                            color = colors.textDisabled,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Cubism-style deform-path inspector: curve width/hardness, per-point fold line, and target mesh.
 */
@Composable
private fun DeformPathInspector(
    path: DeformPath,
    drawable: Drawable,
    editor: CanvasEditor,
) {
    val colors = LocalToolColors.current
    val typography = LocalToolTypography.current
    val pointIndex = editor.pathPoint.takeIf { it in path.points.indices }
    val selectedPoint = pointIndex?.let { path.points[it] }
    val editable = editor.editable && !editor.busy

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        InspectorSectionBox(title = tr("inspector.pathCurve")) {
            InspectorFormRow(label = tr("inspector.deformPathWidth")) {
                CompactNumberSpinner(
                    value = path.width.toDouble(),
                    onValueChange = { w ->
                        if (editable) editor.changePath { it.copy(width = w.toFloat().coerceAtLeast(0f)) }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    min = 0.0,
                    max = 100000.0,
                    decimals = 2,
                    step = 1.0,
                    enabled = editable,
                    height = 23.dp,
                )
            }

            InspectorFormRow(label = tr("inspector.deformPathHardness")) {
                CompactNumberSpinner(
                    value = path.hardness * 100.0,
                    onValueChange = { h ->
                        if (editable) editor.changePath { it.copy(hardness = (h.toFloat() / 100f).coerceIn(0f, 1f)) }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    min = 0.0,
                    max = 100.0,
                    decimals = 0,
                    step = 1.0,
                    unit = "%",
                    enabled = editable,
                    height = 23.dp,
                )
            }

            InspectorFormRow(label = tr("inspector.pathClosed")) {
                CompactCheckbox(
                    checked = path.closed,
                    onCheckedChange = { closed ->
                        if (!editable) return@CompactCheckbox
                        if (closed && path.points.size < 3) return@CompactCheckbox
                        editor.changePath { it.copy(closed = closed) }
                    },
                    enabled = editable && (path.closed || path.points.size >= 3),
                )
            }

            InspectorFormRow(label = tr("inspector.pathEditLevel")) {
                CompactDropdown(
                    items = listOf(2 to "L2", 3 to "L3"),
                    selectedItem = path.editLevel to "L${path.editLevel}",
                    onItemSelected = { (level, _) ->
                        if (!editable) return@CompactDropdown
                        editor.changePath { it.copy(editLevel = level) }
                        editor.pathLevel = level
                    },
                    itemLabel = { it.second },
                    modifier = Modifier.fillMaxWidth(),
                    height = 23.dp,
                    enabled = editable,
                )
            }
        }

        // Per-control-point: fold line (折线) — each path point has its own corner flag
        InspectorFormRow(label = tr("inspector.foldLine")) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CompactCheckbox(
                    checked = selectedPoint?.corner == true,
                    onCheckedChange = { corner ->
                        if (!editable || pointIndex == null) return@CompactCheckbox
                        editor.changePath { p ->
                            p.copy(
                                points = p.points.mapIndexed { i, pt ->
                                    if (i == pointIndex) pt.copy(corner = corner) else pt
                                },
                            )
                        }
                    },
                    enabled = editable && selectedPoint != null,
                )
                Text(
                    text = pointIndex?.let { "${it + 1}/${path.points.size}" } ?: tr("inspector.pathPointNone"),
                    style = typography.caption.copy(fontSize = 10.5.sp),
                    color = if (selectedPoint != null) colors.textMuted else colors.textDisabled,
                )
            }
        }

        InspectorSectionBox(title = tr("inspector.pathTarget")) {
            InspectorFormRow(label = tr("inspector.pathMain")) {
                CompactTextField(
                    value = drawable.name.ifBlank { drawable.id.raw },
                    onValueChange = {},
                    enabled = false,
                    modifier = Modifier.fillMaxWidth(),
                    height = 23.dp,
                )
            }

            InspectorFormRow(label = tr("inspector.pathChild")) {
                CompactTextField(
                    value = "",
                    onValueChange = {},
                    enabled = false,
                    modifier = Modifier.fillMaxWidth(),
                    height = 23.dp,
                )
            }
        }
    }
}

@Composable
private fun InspectorSectionBox(
    title: String,
    content: @Composable () -> Unit,
) {
    val colors = LocalToolColors.current
    val typography = LocalToolTypography.current
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = title,
            style = typography.caption.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
            color = colors.textPrimary,
            modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.panelElevated, RoundedCornerShape(4.dp))
                .border(1.dp, colors.border.copy(alpha = 0.55f), RoundedCornerShape(4.dp))
                .padding(horizontal = 6.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            content()
        }
    }
}

/**
 * Inspector view for ArtMesh (Drawable) - matches Screenshot 1
 */
@Composable
private fun ArtMeshInspector(
    drawable: Drawable,
    allParts: List<Pair<String, String>>,
    allDeformers: List<Pair<String, String>>,
    allDrawables: List<Pair<String, String>>,
    editor: CanvasEditor,
    viewModel: PSD2LiveViewModel,
    state: PSD2LiveState,
) {
    val colors = LocalToolColors.current
    val typography = LocalToolTypography.current
    var userDataExpanded by remember { mutableStateOf(false) }
    var userDataText by remember { mutableStateOf("") }
    var clipIdPickerOpen by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // 1. 名称 (Name)
        InspectorFormRow(label = tr("inspector.name")) {
            val nameEdit = "mesh.name.${drawable.id.raw}"
            CompactTextField(
                value = drawable.name,
                onValueChange = { newName ->
                    viewModel.applyRigStructureLive(nameEdit, "rename", "mesh", drawable.id.raw, buildJsonObject { put("name", JsonPrimitive(newName)) })
                },
                onEditEnd = { viewModel.endEditorField(nameEdit) },
                modifier = Modifier.fillMaxWidth(),
                height = 23.dp,
            )
        }

        // 2. ID
        InspectorFormRow(label = tr("inspector.id")) {
            CompactTextField(
                value = drawable.id.raw,
                onValueChange = {},
                enabled = false,
                modifier = Modifier.fillMaxWidth(),
                height = 23.dp,
            )
        }

        // 3. 部件 (Part)
        InspectorFormRow(label = tr("inspector.part")) {
            val partOptions = listOf("" to tr("inspector.none")) + allParts
            val currentPartId = state.previewModel?.rig?.puppet?.parts?.firstOrNull { part ->
                OrgChild.Drawable(drawable.id) in part.children
            }?.id?.raw ?: ""
            val selectedItem = partOptions.firstOrNull { it.first == currentPartId } ?: partOptions.first()
            CompactDropdown(
                items = partOptions,
                selectedItem = selectedItem,
                onItemSelected = { selected ->
                    val nextPart = selected.first.takeIf { it.isNotEmpty() }?.let(::PartId)
                    viewModel.applyRigStructure("move", "mesh", drawable.id.raw, buildJsonObject { put("parent_id", nextPart?.raw?.let(::JsonPrimitive) ?: JsonNull) })
                },
                itemLabel = { it.second },
                modifier = Modifier.fillMaxWidth(),
                height = 23.dp,
            )
        }

        // 4. 变形器 (Deformer)
        InspectorFormRow(label = tr("inspector.deformer")) {
            val deformerOptions = listOf("" to tr("inspector.none")) + allDeformers
            val selectedItem = deformerOptions.firstOrNull { it.first == (drawable.parentDeformerId?.raw ?: "") }
                ?: deformerOptions.first()
            CompactDropdown(
                items = deformerOptions,
                selectedItem = selectedItem,
                onItemSelected = { selected ->
                    val nextDeformer = selected.first.takeIf { it.isNotEmpty() }?.let(::DeformerId)
                    viewModel.applyRigStructure("bind", "mesh", drawable.id.raw, buildJsonObject { put("space", JsonPrimitive("local")); put("parent_id", nextDeformer?.raw?.let(::JsonPrimitive) ?: JsonNull) })
                },
                itemLabel = { it.second },
                modifier = Modifier.fillMaxWidth(),
                height = 23.dp,
            )
        }

        // 5. 剪贴ID (Clip ID) with list picker button and crosshair button
        InspectorFormRow(label = tr("inspector.clipId")) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    CompactTextField(
                        value = drawable.maskedBy.joinToString(",") { it.raw },
                        onValueChange = { text ->
                            val ids = text.split(",", " ", ";")
                                .map { it.trim() }
                                .filter { it.isNotEmpty() }
                                .map(::DrawableId)
                            viewModel.applyRigStaticNow("mesh", drawable.id.raw, "masked_by" to JsonArray(ids.map { JsonPrimitive(it.raw) }))
                        },
                        modifier = Modifier.fillMaxWidth(),
                        height = 23.dp,
                    )
                }

                // List Button (dropdown)
                Box {
                    ActionButton(
                        onClick = { clipIdPickerOpen = true },
                        tooltip = tr("inspector.selectClipFromList"),
                    ) {
                        ListIcon(color = colors.textPrimary)
                    }
                    DropdownMenu(
                        expanded = clipIdPickerOpen,
                        onDismissRequest = { clipIdPickerOpen = false },
                    ) {
                        allDrawables.filter { it.first != drawable.id.raw }.forEach { (dId, dName) ->
                            val isMask = drawable.maskedBy.any { it.raw == dId }
                            DropdownMenuItem(onClick = {
                                val nextList = if (isMask) {
                                    drawable.maskedBy.filter { it.raw != dId }
                                } else {
                                    drawable.maskedBy + DrawableId(dId)
                                }
                                viewModel.applyRigStaticNow("mesh", drawable.id.raw, "masked_by" to JsonArray(nextList.map { JsonPrimitive(it.raw) }))
                                clipIdPickerOpen = false
                            }) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    CompactCheckbox(checked = isMask, onCheckedChange = {})
                                    Text("$dName ($dId)", fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }

                // Target crosshair Button (pick from canvas)
                ActionButton(
                    onClick = {
                        state.selectedLayerId?.let { currentLayer ->
                            val otherLayers = state.effectiveVisibleLayerIds - currentLayer
                            otherLayers.firstOrNull()?.let { maskLayer ->
                                val maskId = state.previewModel?.rig?.layerIdByDrawableId?.entries?.firstOrNull { it.value == maskLayer }?.key ?: maskLayer
                                viewModel.applyRigStaticNow("mesh", drawable.id.raw, "masked_by" to JsonArray(listOf(JsonPrimitive(maskId))))
                            }
                        }
                    },
                    tooltip = tr("inspector.pickClipFromCanvas"),
                ) {
                    TargetIcon(color = colors.textPrimary)
                }
            }
        }

        // 6. 反转蒙版 (Invert Mask)
        InspectorFormRow(label = tr("inspector.invertMask")) {
            CompactCheckbox(
                checked = drawable.invertMask,
                onCheckedChange = { inverted ->
                    viewModel.applyRigStaticNow("mesh", drawable.id.raw, "invert_mask" to JsonPrimitive(inverted))
                },
            )
        }

        // 7. 绘制顺序 (Draw Order)
        InspectorFormRow(label = tr("inspector.drawOrder")) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                CompactNumberSpinner(
                    value = drawable.drawOrder.toDouble(),
                    onValueChange = { order ->
                        viewModel.updatePuppetModel { it.withDrawableDrawOrder(drawable.id, order.toFloat()) }
                        viewModel.setLayerDrawOrder(drawable.id.raw, order.toFloat())
                    },
                    modifier = Modifier.weight(1f),
                    min = 0.0,
                    max = 1000.0,
                    step = 1.0,
                    decimals = 0,
                    height = 23.dp,
                )
                KeyframeDotButton()
            }
        }

        // 8. 不透明度 (Opacity)
        InspectorFormRow(label = tr("inspector.opacity")) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                CompactNumberSpinner(
                    value = (drawable.opacity * 100).toDouble(),
                    onValueChange = { pct ->
                        val op = (pct / 100f).toFloat().coerceIn(0f, 1f)
                        viewModel.applyRigStaticLive("mesh.opacity.${drawable.id.raw}", "mesh", drawable.id.raw, "opacity" to JsonPrimitive(op))
                    },
                    onEditEnd = { viewModel.endEditorField("mesh.opacity.${drawable.id.raw}") },
                    modifier = Modifier.weight(1f),
                    min = 0.0,
                    max = 100.0,
                    step = 5.0,
                    decimals = 0,
                    unit = "%",
                    height = 23.dp,
                )
            }
        }

        // 9. 正片叠底色 (Multiply Color)
        InspectorColorRow(
            label = tr("inspector.multiplyColor"),
            colorRgb = drawable.multiplyColor,
            defaultColor = ColorRgb.MultiplyIdentity,
            onColorChanged = { newColor ->
                viewModel.applyRigStaticLive("mesh.multiply_color.${drawable.id.raw}", "mesh", drawable.id.raw, "multiply_color" to newColor.toJsonArray())
            },
            onEditEnd = { viewModel.endEditorField("mesh.multiply_color.${drawable.id.raw}") },
        )

        // 10. 屏幕色 (Screen Color)
        InspectorColorRow(
            label = tr("inspector.screenColor"),
            colorRgb = drawable.screenColor,
            defaultColor = ColorRgb.ScreenIdentity,
            onColorChanged = { newColor ->
                viewModel.applyRigStaticLive("mesh.screen_color.${drawable.id.raw}", "mesh", drawable.id.raw, "screen_color" to newColor.toJsonArray())
            },
            onEditEnd = { viewModel.endEditorField("mesh.screen_color.${drawable.id.raw}") },
        )

        // 11. 混合模式 (Blend Mode)
        InspectorFormRow(label = tr("inspector.blendMode")) {
            val modeItems = listOf(
                BlendMode.Normal to tr("inspector.blendMode.normal"),
                BlendMode.Multiply to tr("inspector.blendMode.multiply"),
                BlendMode.Additive to tr("inspector.blendMode.additive"),
            )
            val currentMode = modeItems.firstOrNull {
                it.first == drawable.blendMode ||
                (drawable.blendMode == BlendMode.MultiplyPremultiplied && it.first == BlendMode.Multiply) ||
                (drawable.blendMode == BlendMode.AdditivePremultiplied && it.first == BlendMode.Additive)
            } ?: modeItems.first()

            CompactDropdown(
                items = modeItems,
                selectedItem = currentMode,
                onItemSelected = { selected ->
                    viewModel.applyRigStaticNow("mesh", drawable.id.raw, "blend_mode" to JsonPrimitive(selected.first.name))
                },
                itemLabel = { it.second },
                modifier = Modifier.fillMaxWidth(),
                height = 23.dp,
            )
        }

        // 12. 剔除 (Culling)
        InspectorFormRow(label = tr("inspector.culling")) {
            CompactCheckbox(
                checked = drawable.culling,
                onCheckedChange = { culled ->
                    viewModel.applyRigStaticNow("mesh", drawable.id.raw, "culling" to JsonPrimitive(culled))
                },
            )
        }

        // 13. 用户数据 (User Data)
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(24.dp)
                    .clickable { userDataExpanded = !userDataExpanded },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                IconChevron(
                    expanded = userDataExpanded,
                    modifier = Modifier.size(12.dp),
                    tint = colors.textMuted,
                )
                Text(
                    text = tr("inspector.userData"),
                    style = typography.caption.copy(fontSize = 11.sp, fontWeight = FontWeight.Medium),
                    color = colors.textPrimary,
                )
            }

            AnimatedVisibility(
                visible = userDataExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                CompactTextField(
                    value = userDataText,
                    onValueChange = { userDataText = it },
                    placeholder = tr("inspector.userDataPlaceholder"),
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, bottom = 4.dp),
                    height = 23.dp,
                )
            }
        }

        // 14. 顶点信息 (Vertex Info X, Y)
        VertexInfoRows(editor = editor)
    }
}

/**
 * Inspector view for Warp Deformer - matches Screenshot 2
 */
@Composable
private fun WarpDeformerInspector(
    warp: Deformer.Warp,
    allParts: List<Pair<String, String>>,
    allDeformers: List<Pair<String, String>>,
    editor: CanvasEditor,
    viewModel: PSD2LiveViewModel,
) {
    val colors = LocalToolColors.current
    val typography = LocalToolTypography.current

    var bezierCols by remember(warp.id.raw) {
        mutableStateOf(editor.warpBezierDivisions[warp.id.raw]?.second ?: 2)
    }
    var bezierRows by remember(warp.id.raw) {
        mutableStateOf(editor.warpBezierDivisions[warp.id.raw]?.first ?: 2)
    }

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // 1. 名称 (Name)
        InspectorFormRow(label = tr("inspector.name")) {
            CompactTextField(
                value = warp.name,
                onValueChange = { newName ->
                    viewModel.applyRigStructureLive("warp.name.${warp.id.raw}", "rename", "warp", warp.id.raw, buildJsonObject { put("name", JsonPrimitive(newName)) })
                },
                onEditEnd = { viewModel.endEditorField("warp.name.${warp.id.raw}") },
                modifier = Modifier.fillMaxWidth(),
                height = 23.dp,
            )
        }

        // 2. ID
        InspectorFormRow(label = tr("inspector.id")) {
            CompactTextField(
                value = warp.id.raw,
                onValueChange = {},
                enabled = false,
                modifier = Modifier.fillMaxWidth(),
                height = 23.dp,
            )
        }

        // 3. 部件 (Part)
        InspectorFormRow(label = tr("inspector.part")) {
            val partOptions = listOf("" to tr("inspector.none")) + allParts
            val selectedItem = partOptions.firstOrNull { it.first == (warp.partId?.raw ?: "") }
                ?: partOptions.first()
            CompactDropdown(
                items = partOptions,
                selectedItem = selectedItem,
                onItemSelected = { selected ->
                    val nextPart = selected.first.takeIf { it.isNotEmpty() }?.let(::PartId)
                    viewModel.applyRigStructure("part", "warp", warp.id.raw, buildJsonObject { put("part_id", nextPart?.raw?.let(::JsonPrimitive) ?: JsonNull) })
                },
                itemLabel = { it.second },
                modifier = Modifier.fillMaxWidth(),
                height = 23.dp,
            )
        }

        // 4. 变形器 (Parent Deformer)
        InspectorFormRow(label = tr("inspector.deformer")) {
            val deformerOptions = listOf("" to tr("inspector.none")) + allDeformers.filter { it.first != warp.id.raw }
            val selectedItem = deformerOptions.firstOrNull { it.first == (warp.parent?.raw ?: "") }
                ?: deformerOptions.first()
            CompactDropdown(
                items = deformerOptions,
                selectedItem = selectedItem,
                onItemSelected = { selected ->
                    val nextParent = selected.first.takeIf { it.isNotEmpty() }?.let(::DeformerId)
                    viewModel.applyRigStructure("move", "warp", warp.id.raw, buildJsonObject { put("space", JsonPrimitive("local")); put("parent_id", nextParent?.raw?.let(::JsonPrimitive) ?: JsonNull) })
                },
                itemLabel = { it.second },
                modifier = Modifier.fillMaxWidth(),
                height = 23.dp,
            )
        }

        // 5. 不透明度 (Opacity)
        InspectorFormRow(label = tr("inspector.opacity")) {
            CompactNumberSpinner(
                value = (warp.opacity * 100).toDouble(),
                onValueChange = { pct ->
                    val op = (pct / 100f).toFloat().coerceIn(0f, 1f)
                    viewModel.applyRigStaticLive("warp.opacity.${warp.id.raw}", "warp", warp.id.raw, "opacity" to JsonPrimitive(op))
                },
                onEditEnd = { viewModel.endEditorField("warp.opacity.${warp.id.raw}") },
                modifier = Modifier.fillMaxWidth(),
                min = 0.0,
                max = 100.0,
                step = 5.0,
                decimals = 0,
                unit = "%",
                height = 23.dp,
            )
        }

        // 6. 正片叠底色 (Multiply Color)
        InspectorColorRow(
            label = tr("inspector.multiplyColor"),
            colorRgb = warp.multiplyColor,
            defaultColor = ColorRgb.MultiplyIdentity,
            onColorChanged = { newColor ->
                viewModel.applyRigStaticLive("warp.multiply_color.${warp.id.raw}", "warp", warp.id.raw, "multiply_color" to newColor.toJsonArray())
            },
            onEditEnd = { viewModel.endEditorField("warp.multiply_color.${warp.id.raw}") },
        )

        // 7. 屏幕色 (Screen Color)
        InspectorColorRow(
            label = tr("inspector.screenColor"),
            colorRgb = warp.screenColor,
            defaultColor = ColorRgb.ScreenIdentity,
            onColorChanged = { newColor ->
                viewModel.applyRigStaticLive("warp.screen_color.${warp.id.raw}", "warp", warp.id.raw, "screen_color" to newColor.toJsonArray())
            },
            onEditEnd = { viewModel.endEditorField("warp.screen_color.${warp.id.raw}") },
        )

        // 8. 转换的分裂数量 (Conversion Division: cols x rows)
        InspectorFormRow(label = tr("inspector.conversionDivision")) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                CompactNumberSpinner(
                    value = warp.columns.toDouble(),
                    onValueChange = { nextCols ->
                        val cols = nextCols.toInt().coerceIn(1, 32)
                        viewModel.updatePuppetModel { model ->
                            model.copy(deformers = model.deformers.map {
                                if (it.id == warp.id && it is Deformer.Warp) it.copy(columns = cols) else it
                            })
                        }
                    },
                    modifier = Modifier.weight(1f),
                    min = 1.0,
                    max = 32.0,
                    step = 1.0,
                    decimals = 0,
                    height = 23.dp,
                )
                Text("x", fontSize = 11.sp, color = colors.textMuted)
                CompactNumberSpinner(
                    value = warp.rows.toDouble(),
                    onValueChange = { nextRows ->
                        val rows = nextRows.toInt().coerceIn(1, 32)
                        viewModel.updatePuppetModel { model ->
                            model.copy(deformers = model.deformers.map {
                                if (it.id == warp.id && it is Deformer.Warp) it.copy(rows = rows) else it
                            })
                        }
                    },
                    modifier = Modifier.weight(1f),
                    min = 1.0,
                    max = 32.0,
                    step = 1.0,
                    decimals = 0,
                    height = 23.dp,
                )
                Text(tr("inspector.dimensionHint"), fontSize = 10.sp, color = colors.textMuted)
            }
        }

        // 9. <编辑级别设置> (Edit Level Settings Header)
        Box(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = tr("inspector.editLevelSettings"),
                style = typography.caption.copy(fontSize = 11.sp, fontWeight = FontWeight.Medium),
                color = colors.textPrimary,
            )
        }

        // 10. 贝塞尔分割数 (Bezier Division)
        InspectorFormRow(label = tr("inspector.bezierDivision")) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                CompactNumberSpinner(
                    value = bezierCols.toDouble(),
                    onValueChange = {
                        val next = it.toInt().coerceIn(1, 16)
                        bezierCols = next
                        editor.warpBezierDivisions[warp.id.raw] = bezierRows to next
                        editor.ensureBezierState()
                    },
                    modifier = Modifier.weight(1f),
                    min = 1.0,
                    max = 16.0,
                    step = 1.0,
                    decimals = 0,
                    height = 23.dp,
                )
                Text("x", fontSize = 11.sp, color = colors.textMuted)
                CompactNumberSpinner(
                    value = bezierRows.toDouble(),
                    onValueChange = {
                        val next = it.toInt().coerceIn(1, 16)
                        bezierRows = next
                        editor.warpBezierDivisions[warp.id.raw] = next to bezierCols
                        editor.ensureBezierState()
                    },
                    modifier = Modifier.weight(1f),
                    min = 1.0,
                    max = 16.0,
                    step = 1.0,
                    decimals = 0,
                    height = 23.dp,
                )
                Text(tr("inspector.dimensionHint"), fontSize = 10.sp, color = colors.textMuted)
            }
        }

        // 11. 贝塞尔编辑类型 (Bezier Edit Type)
        InspectorFormRow(label = tr("inspector.bezierEditType")) {
            val editTypes = listOf("retainStructure" to tr("inspector.retainStructure"))
            CompactDropdown(
                items = editTypes,
                selectedItem = editTypes.first(),
                onItemSelected = {},
                itemLabel = { it.second },
                modifier = Modifier.fillMaxWidth(),
                height = 23.dp,
            )
        }

        // 12. 重置贝塞尔控制点 (Reset Bezier Control Points Button)
        Row(modifier = Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 4.dp)) {
            Spacer(Modifier.width(88.dp))
            CompactButton(
                text = tr("inspector.resetBezier"),
                onClick = {
                    // Trigger resetting warp control points to uniform grid
                    editor.target()?.let { t ->
                        if (t.kind == "warp") editor.topology("reset")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                height = 24.dp,
            )
        }

        // 13. 顶点信息 (Vertex Info X, Y)
        VertexInfoRows(editor = editor)

        // 14. 变形器转换(3.2方法) (isQuadTransform)
        InspectorFormRow(label = tr("inspector.quadTransform")) {
            CompactCheckbox(
                checked = warp.isQuadTransform,
                onCheckedChange = { quad ->
                    viewModel.applyRigStaticNow("warp", warp.id.raw, "quad" to JsonPrimitive(quad))
                },
            )
        }
    }
}

/**
 * Inspector view for Rotation Deformer
 */
@Composable
private fun RotationDeformerInspector(
    rotation: Deformer.Rotation,
    allParts: List<Pair<String, String>>,
    allDeformers: List<Pair<String, String>>,
    editor: CanvasEditor,
    viewModel: PSD2LiveViewModel,
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        InspectorFormRow(label = tr("inspector.name")) {
            CompactTextField(
                value = rotation.name,
                onValueChange = { newName ->
                    viewModel.applyRigStructureLive("rotation.name.${rotation.id.raw}", "rename", "rotation", rotation.id.raw, buildJsonObject { put("name", JsonPrimitive(newName)) })
                },
                onEditEnd = { viewModel.endEditorField("rotation.name.${rotation.id.raw}") },
                modifier = Modifier.fillMaxWidth(),
                height = 23.dp,
            )
        }

        InspectorFormRow(label = tr("inspector.id")) {
            CompactTextField(
                value = rotation.id.raw,
                onValueChange = {},
                enabled = false,
                modifier = Modifier.fillMaxWidth(),
                height = 23.dp,
            )
        }

        InspectorFormRow(label = tr("inspector.part")) {
            val partOptions = listOf("" to tr("inspector.none")) + allParts
            val selectedItem = partOptions.firstOrNull { it.first == (rotation.partId?.raw ?: "") }
                ?: partOptions.first()
            CompactDropdown(
                items = partOptions,
                selectedItem = selectedItem,
                onItemSelected = { selected ->
                    val nextPart = selected.first.takeIf { it.isNotEmpty() }?.let(::PartId)
                    viewModel.applyRigStructure("part", "rotation", rotation.id.raw, buildJsonObject { put("part_id", nextPart?.raw?.let(::JsonPrimitive) ?: JsonNull) })
                },
                itemLabel = { it.second },
                modifier = Modifier.fillMaxWidth(),
                height = 23.dp,
            )
        }

        InspectorFormRow(label = tr("inspector.deformer")) {
            val deformerOptions = listOf("" to tr("inspector.none")) + allDeformers.filter { it.first != rotation.id.raw }
            val selectedItem = deformerOptions.firstOrNull { it.first == (rotation.parent?.raw ?: "") }
                ?: deformerOptions.first()
            CompactDropdown(
                items = deformerOptions,
                selectedItem = selectedItem,
                onItemSelected = { selected ->
                    val nextParent = selected.first.takeIf { it.isNotEmpty() }?.let(::DeformerId)
                    viewModel.applyRigStructure("move", "rotation", rotation.id.raw, buildJsonObject { put("space", JsonPrimitive("local")); put("parent_id", nextParent?.raw?.let(::JsonPrimitive) ?: JsonNull) })
                },
                itemLabel = { it.second },
                modifier = Modifier.fillMaxWidth(),
                height = 23.dp,
            )
        }

        InspectorFormRow(label = tr("inspector.opacity")) {
            CompactNumberSpinner(
                value = (rotation.opacity * 100).toDouble(),
                onValueChange = { pct ->
                    val op = (pct / 100f).toFloat().coerceIn(0f, 1f)
                    viewModel.applyRigStaticLive("rotation.opacity.${rotation.id.raw}", "rotation", rotation.id.raw, "opacity" to JsonPrimitive(op))
                },
                onEditEnd = { viewModel.endEditorField("rotation.opacity.${rotation.id.raw}") },
                modifier = Modifier.fillMaxWidth(),
                min = 0.0,
                max = 100.0,
                step = 5.0,
                decimals = 0,
                unit = "%",
                height = 23.dp,
            )
        }

        InspectorColorRow(
            label = tr("inspector.multiplyColor"),
            colorRgb = rotation.multiplyColor,
            defaultColor = ColorRgb.MultiplyIdentity,
            onColorChanged = { newColor ->
                viewModel.applyRigStaticLive("rotation.multiply_color.${rotation.id.raw}", "rotation", rotation.id.raw, "multiply_color" to newColor.toJsonArray())
            },
            onEditEnd = { viewModel.endEditorField("rotation.multiply_color.${rotation.id.raw}") },
        )

        InspectorColorRow(
            label = tr("inspector.screenColor"),
            colorRgb = rotation.screenColor,
            defaultColor = ColorRgb.ScreenIdentity,
            onColorChanged = { newColor ->
                viewModel.applyRigStaticLive("rotation.screen_color.${rotation.id.raw}", "rotation", rotation.id.raw, "screen_color" to newColor.toJsonArray())
            },
            onEditEnd = { viewModel.endEditorField("rotation.screen_color.${rotation.id.raw}") },
        )

        VertexInfoRows(editor = editor)
    }
}

/**
 * Standard Form Row layout: right-aligned label on left, input controls on right
 */
@Composable
private fun InspectorFormRow(
    label: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val colors = LocalToolColors.current
    val typography = LocalToolTypography.current

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = typography.caption.copy(fontSize = 11.sp),
            color = colors.textPrimary,
            textAlign = TextAlign.End,
            modifier = Modifier.width(88.dp).padding(end = 8.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            content()
        }
    }
}

/**
 * Color Row matching screenshots: Rectangular swatch preview + Hex text + Reset button
 */
@Composable
private fun InspectorColorRow(
    label: String,
    colorRgb: ColorRgb,
    defaultColor: ColorRgb,
    onColorChanged: (ColorRgb) -> Unit,
    /** The picker popup and the hex field share one session: it ends when both are done with. */
    onEditStart: () -> Unit = {},
    onEditEnd: () -> Unit = {},
) {
    val colors = LocalToolColors.current
    val density = LocalDensity.current
    var colorPickerOpen by remember { mutableStateOf(false) }
    // Opening the popup starts a colour session and dismissing it ends one, so a drag across the picker
    // records the colour the user settled on rather than one node per sample. The first composition sees
    // `false` and ends a session that was never open, which is a no-op.
    LaunchedEffect(colorPickerOpen) { if (colorPickerOpen) onEditStart() else onEditEnd() }

    val intColor = colorRgbToInt(colorRgb)
    val hexString = colorRgbToHex(colorRgb)
    var hexInput by remember(hexString) { mutableStateOf(hexString) }

    InspectorFormRow(label = label) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            // Rectangle Color Swatch (Click to open color picker popup)
            Box {
                Box(
                    modifier = Modifier
                        .size(width = 28.dp, height = 21.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color(intColor))
                        .border(1.dp, colors.border, RoundedCornerShape(2.dp))
                        .clickable { colorPickerOpen = true }
                        .pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)))
                )

                if (colorPickerOpen) {
                    Popup(
                        alignment = Alignment.TopEnd,
                        offset = IntOffset(0, with(density) { 26.dp.roundToPx() }),
                        onDismissRequest = { colorPickerOpen = false },
                        properties = PopupProperties(focusable = true),
                    ) {
                        ColorPickerPopupContent(
                            initialColor = intColor,
                            sampledColor = null,
                            onColorChanged = { newInt ->
                                onColorChanged(intToColorRgb(newInt))
                            },
                            onDismiss = { colorPickerOpen = false },
                        )
                    }
                }
            }

            // Hex Text Input
            CompactTextField(
                value = hexInput,
                onValueChange = { nextHex ->
                    hexInput = nextHex
                    parseHexToColorRgb(nextHex)?.let(onColorChanged)
                },
                onEditEnd = onEditEnd,
                modifier = Modifier.weight(1f),
                height = 23.dp,
            )

            // Undo / Reset Button (Circular arrow icon)
            ActionButton(
                onClick = { onColorChanged(defaultColor) },
                tooltip = tr("editor.reset"),
            ) {
                IconReset(modifier = Modifier.size(12.dp), tint = colors.textMuted)
            }
        }
    }
}

/**
 * 顶点信息 (Vertex Info X, Y)
 */
@Composable
private fun VertexInfoRows(editor: CanvasEditor) {
    val target = editor.target()
    val chosenIndex = editor.vertices.firstOrNull()
    val coords = remember(target, chosenIndex, editor.vertices) {
        if (target != null && chosenIndex != null && chosenIndex * 2 + 1 < target.geometry.points.size) {
            val px = target.geometry.points[chosenIndex * 2]
            val py = target.geometry.points[chosenIndex * 2 + 1]
            String.format("%.1f", px) to String.format("%.1f", py)
        } else {
            "-" to "-"
        }
    }

    InspectorFormRow(label = "${tr("inspector.vertexInfo")} X:") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            CompactTextField(
                value = coords.first,
                onValueChange = {},
                enabled = false,
                modifier = Modifier.weight(1f),
                height = 23.dp,
            )
            KeyframeDotButton()
        }
    }

    InspectorFormRow(label = "Y:") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            CompactTextField(
                value = coords.second,
                onValueChange = {},
                enabled = false,
                modifier = Modifier.weight(1f),
                height = 23.dp,
            )
            KeyframeDotButton()
        }
    }
}

/**
 * Small Square Button with centered dot/keyframe indicator
 */
@Composable
private fun KeyframeDotButton(modifier: Modifier = Modifier) {
    val colors = LocalToolColors.current
    Box(
        modifier = modifier
            .size(23.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(colors.panelElevated)
            .border(1.dp, colors.border, RoundedCornerShape(3.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(4.5.dp)
                .background(colors.textMuted, RoundedCornerShape(1.dp))
        )
    }
}

/**
 * Compact icon action button
 */
@Composable
private fun ActionButton(
    onClick: () -> Unit,
    tooltip: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val colors = LocalToolColors.current
    Box(
        modifier = modifier
            .size(23.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(colors.panelElevated)
            .border(1.dp, colors.border, RoundedCornerShape(3.dp))
            .clickable(onClick = onClick)
            .pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR))),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

/**
 * List Icon for Clip ID row
 */
@Composable
private fun ListIcon(color: Color, modifier: Modifier = Modifier.size(13.dp)) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = 1.2f
        drawLine(color, Offset(w * 0.15f, h * 0.28f), Offset(w * 0.85f, h * 0.28f), strokeWidth = stroke)
        drawLine(color, Offset(w * 0.15f, h * 0.52f), Offset(w * 0.85f, h * 0.52f), strokeWidth = stroke)
        drawLine(color, Offset(w * 0.15f, h * 0.76f), Offset(w * 0.85f, h * 0.76f), strokeWidth = stroke)
    }
}

/**
 * Target / Crosshair Icon for Clip ID row
 */
@Composable
private fun TargetIcon(color: Color, modifier: Modifier = Modifier.size(13.dp)) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = 1.2f
        drawCircle(color, radius = w * 0.35f, center = Offset(w * 0.5f, h * 0.5f), style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke))
        drawLine(color, Offset(w * 0.5f, h * 0.05f), Offset(w * 0.5f, h * 0.3f), strokeWidth = stroke)
        drawLine(color, Offset(w * 0.5f, h * 0.7f), Offset(w * 0.5f, h * 0.95f), strokeWidth = stroke)
        drawLine(color, Offset(w * 0.05f, h * 0.5f), Offset(w * 0.3f, h * 0.5f), strokeWidth = stroke)
        drawLine(color, Offset(w * 0.7f, h * 0.5f), Offset(w * 0.95f, h * 0.5f), strokeWidth = stroke)
    }
}

// Helpers for Color conversion
private fun colorRgbToInt(rgb: ColorRgb): Int {
    val r = (rgb.red.coerceIn(0f, 1f) * 255f).roundToInt()
    val g = (rgb.green.coerceIn(0f, 1f) * 255f).roundToInt()
    val b = (rgb.blue.coerceIn(0f, 1f) * 255f).roundToInt()
    return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}

private fun colorRgbToHex(rgb: ColorRgb): String {
    val r = (rgb.red.coerceIn(0f, 1f) * 255f).roundToInt()
    val g = (rgb.green.coerceIn(0f, 1f) * 255f).roundToInt()
    val b = (rgb.blue.coerceIn(0f, 1f) * 255f).roundToInt()
    return String.format("#%02X%02X%02X", r, g, b)
}

private fun intToColorRgb(intColor: Int): ColorRgb {
    val r = ((intColor shr 16) and 0xFF) / 255f
    val g = ((intColor shr 8) and 0xFF) / 255f
    val b = (intColor and 0xFF) / 255f
    return ColorRgb(r, g, b)
}

private fun parseHexToColorRgb(hex: String): ColorRgb? {
    val clean = hex.trim().removePrefix("#")
    if (clean.length != 6) return null
    val rgb = clean.toIntOrNull(16) ?: return null
    val r = ((rgb shr 16) and 0xFF) / 255f
    val g = ((rgb shr 8) and 0xFF) / 255f
    val b = (rgb and 0xFF) / 255f
    return ColorRgb(r, g, b)
}


/** A colour as the `static` action takes it: three 0..1 channels. */
private fun ColorRgb.toJsonArray() =
    JsonArray(listOf(JsonPrimitive(red), JsonPrimitive(green), JsonPrimitive(blue)))
