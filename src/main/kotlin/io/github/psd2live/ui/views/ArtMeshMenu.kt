package io.github.psd2live.ui.views

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.psd2live.application.ImageLayerInsertion
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.CreatePlacementKind
import io.github.psd2live.ui.CreateRelation
import io.github.psd2live.ui.components.*
import io.github.psd2live.ui.state.PSD2LiveState
import io.github.psd2live.ui.state.referenceConversion
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.state.ShortcutAction
import io.github.psd2live.ui.utils.NativeFilePicker
import io.github.psd2live.ui.views.texture.FloatingMenuRow
import io.github.psd2live.ui.views.texture.FloatingMenuSection
import io.github.psd2live.ui.views.tooloptions.GlidingColumn
import org.umamo.runtime.model.Drawable
import kotlin.math.roundToInt

/** The hierarchy's ArtMesh menu uses the same shell, rows and conversion position as [ReferenceMenu]. */
@Composable
internal fun ArtMeshMenu(
    drawable: Drawable,
    layerId: String?,
    parentId: String?,
    state: PSD2LiveState,
    viewModel: PSD2LiveViewModel,
    close: () -> Unit,
    onRequestSetOrder: ((String, String, Float, Float, Boolean) -> Unit)? = null,
    onRequestCreate: ((CreatePlacementKind, CreateRelation, Boolean, String) -> Unit)? = null,
    /** How many layers the hierarchy has selected; the rows below still act on this row's layer only. */
    selectionCount: Int = 1,
) {
    val editable = !state.isBusy && !state.workspaceEditBusy && !state.editorDraftBusy
    val rows = mutableListOf<Triple<Boolean, Boolean, @Composable () -> Unit>>()
    fun add(highlight: Boolean = true, danger: Boolean = false, content: @Composable () -> Unit) { rows += Triple(highlight, danger, content) }
    fun section(key: String) = add(false) { FloatingMenuSection(tr(key)) }
    add(false) { FloatingMenuSection(drawable.name) }
    if (selectionCount > 1) add(false) { FloatingMenuSection(tr("canvas.hierarchy.menuScopeSingle", selectionCount)) }
    if (layerId != null) {
        add(highlight = state.referenceConversion(layerId).enabled) { ReferenceConversionMenuRow(layerId, state, viewModel, close) }
        section("canvas.hierarchy.menuView")
        val isolated = state.isolatedLayerId == layerId
        val visible = state.isLayerVisible(layerId)
        add { FloatingMenuRow(tr(if (isolated) "canvas.hierarchy.unsoloLayer" else "canvas.hierarchy.soloLayer"),
            { close(); viewModel.isolateLayer(layerId) }, selected = isolated, trailing = "SOLO".takeIf { isolated },
            icon = { IconEye(true, Modifier.size(12.dp), it) }) }
        add { FloatingMenuRow(tr(if (visible) "canvas.hierarchy.hideLayer" else "canvas.hierarchy.showLayer"),
            { close(); viewModel.toggleLayerVisibility(layerId) }, icon = { IconEye(visible, Modifier.size(12.dp), it) }) }
        section("canvas.hierarchy.menuAdd")
        add(highlight = editable) { FloatingMenuRow(tr("editor.treeAddWarpParent"),
            { close(); onRequestCreate?.invoke(CreatePlacementKind.WARP, CreateRelation.AS_PARENT, false, drawable.id.raw) },
            enabled = editable, icon = { IconWarpDeformer(it, Modifier.size(12.dp)) }) }
        add(highlight = editable) { FloatingMenuRow(tr("editor.treeAddRotationParent"),
            { close(); onRequestCreate?.invoke(CreatePlacementKind.ROTATION, CreateRelation.AS_PARENT, false, drawable.id.raw) },
            enabled = editable, icon = { IconRotationDeformer(Modifier.size(12.dp), it) }) }
        if (drawable.mesh != null) {
            val swung = io.github.psd2live.core.SwingCatalog.entries(state.rigEdits).any { swing -> swing.targets.any { it == drawable.id.raw || it == drawable.parentDeformerId?.raw } }
            add(highlight = editable) { FloatingMenuRow(tr(if (swung) "swing.menuEdit" else "swing.menu"),
                { close(); viewModel.beginSwing(listOf(drawable.id.raw)) }, enabled = editable,
                icon = { IconWarpDeformer(it, Modifier.size(12.dp)) }) }
            add(highlight = editable) { FloatingMenuRow(tr("editor.treeAddPath"),
                { close(); onRequestCreate?.invoke(CreatePlacementKind.PATH, CreateRelation.AS_CHILD, false, drawable.id.raw) },
                enabled = editable, icon = { IconDeformPath(Modifier.size(12.dp), it) }) }
        }
        add(highlight = editable) { FloatingMenuRow(tr("canvas.hierarchy.importLayer"), {
            close()
            val picked = NativeFilePicker.chooseTransparentImages()
            if (picked.isNotEmpty()) viewModel.importLayersFromFiles(picked, ImageLayerInsertion(parentId, drawable.id.raw))
        }, enabled = editable, icon = { IconSelectionBounds(it, Modifier.size(12.dp)) }) }
        if (drawable.mesh != null) {
            section("hitArea.menuSection")
            hitAreaMenuRows(drawable, state, viewModel, editable, close) { highlight, content -> add(highlight, content = content) }
        }
        section("canvas.hierarchy.menuSettings")
        add(highlight = editable && drawable.mesh != null) { FloatingMenuRow(if (viewModel.depthSplitMiddleIds(drawable.id.raw).isNotEmpty())
            tr("editor.depthSplit.quick", drawable.name) else tr("editor.depthSplit.menu"),
            { close(); viewModel.requestDepthSplit(drawable.id.raw) }, enabled = editable && drawable.mesh != null,
            icon = { IconDrawOrder(Modifier.size(12.dp), it) }) }
        val order = state.getEffectiveDrawOrder(drawable.id.raw, layerId, drawable.drawOrder)
        val ownLayer = layerId?.takeUnless { state.previewModel?.rig?.copiedDrawableIds?.contains(drawable.id.raw) == true }
        val overridden = ownLayer in state.drawOrderOverrides || drawable.id.raw in state.drawOrderOverrides
        add(highlight = editable) { FloatingMenuRow(tr("canvas.hierarchy.setDrawOrder"),
            { close(); onRequestSetOrder?.invoke(ownLayer ?: drawable.id.raw, drawable.name, order, drawable.drawOrder, overridden) },
            selected = overridden, enabled = editable, trailing = order.roundToInt().toString(),
            icon = { IconDrawOrder(Modifier.size(12.dp), it) }) }
        if (overridden) add(highlight = editable) { FloatingMenuRow(tr("canvas.drawOrder.reset"),
            { close(); viewModel.resetLayerDrawOrder(ownLayer ?: drawable.id.raw) }, enabled = editable,
            icon = { IconReset(Modifier.size(12.dp), it) }) }
        add(highlight = editable && drawable.mesh != null) { FloatingMenuRow(tr("canvas.hierarchy.splitMeshComponents"),
            { close(); viewModel.requestMeshSplit(layerId) }, enabled = editable && drawable.mesh != null,
            icon = { IconMeshWireframe(it, Modifier.size(12.dp)) }) }
        objectRows(drawable, state, viewModel, editable, close, ::add)
        add(highlight = editable, danger = true) { FloatingMenuRow(tr("canvas.hierarchy.deleteLayer"),
            { close(); viewModel.deleteLayer(layerId) }, enabled = editable,
            danger = true, icon = { IconTrash(Modifier.size(12.dp), it) }) }
    }
    GlidingColumn(Modifier.width(236.dp), rows.size, { rows[it].first }, dangers = { rows[it].second }) { rows[it].third() }
}

/**
 * Copy, paste and mesh duplication for the mesh a menu is about: copy takes the hierarchy's selection when this mesh is
 * in it, paste places the copies beside their originals, and duplication copies this mesh's topology onto the other
 * selected meshes.
 */
internal fun objectRows(drawable: Drawable, state: PSD2LiveState, viewModel: PSD2LiveViewModel, editable: Boolean, close: () -> Unit,
                        add: (Boolean, Boolean, @Composable () -> Unit) -> Unit) {
    val self = "mesh:${drawable.id.raw}"
    val selection = viewModel.selectedObjectTargets()
    val targets = viewModel.meshDuplicationTargets(drawable.id.raw)
    add(false, false) { FloatingMenuSection(tr("objectOps.section")) }
    add(true, false) { FloatingMenuRow(tr("objectOps.copy"), { close(); viewModel.copyObjects(if (self in selection) selection else listOf(self)) },
        trailing = state.keymap.labelFor(ShortcutAction.COPY), icon = { GridIcon(Modifier.size(12.dp), it) { copySheets() } }) }
    val canPaste = editable && viewModel.objectClipboard.isNotEmpty()
    add(canPaste, false) { FloatingMenuRow(tr("objectOps.paste"), { close(); viewModel.pasteObjects() }, enabled = canPaste,
        trailing = state.keymap.labelFor(ShortcutAction.PASTE), icon = { GridIcon(Modifier.size(12.dp), it) { pasteBoard() } }) }
    if (drawable.mesh != null && targets.isNotEmpty() && self in selection) add(editable, false) {
        FloatingMenuRow(tr("objectOps.duplicateMesh", targets.size), { close(); viewModel.duplicateMesh(drawable.id.raw, targets) },
            enabled = editable, icon = { IconMeshWireframe(it, Modifier.size(12.dp)) })
    }
}

