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
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.state.ShortcutAction
import io.github.psd2live.ui.utils.NativeFilePicker
import io.github.psd2live.ui.views.texture.FloatingMenuRow
import io.github.psd2live.ui.views.texture.FloatingMenuSection
import io.github.psd2live.ui.views.tooloptions.GlidingColumn
import org.umamo.runtime.model.Deformer

/** Deformer actions use the same menu rows and gliding highlight as meshes and references. */
@Composable
internal fun DeformerMenu(
    deformer: Deformer,
    chainIds: List<String>,
    state: PSD2LiveState,
    viewModel: PSD2LiveViewModel,
    close: () -> Unit,
    expandBranch: () -> Unit,
    collapseBranch: () -> Unit,
    onRequestCreate: ((CreatePlacementKind, CreateRelation, Boolean, String) -> Unit)?,
) {
    val editable = !state.isBusy && !state.workspaceEditBusy && !state.editorDraftBusy
    val rows = mutableListOf<Triple<Boolean, Boolean, @Composable () -> Unit>>()
    fun add(highlight: Boolean = true, danger: Boolean = false, content: @Composable () -> Unit) { rows += Triple(highlight, danger, content) }
    add(false) { FloatingMenuSection(deformer.name) }
    add(false) { FloatingMenuSection(tr("canvas.hierarchy.menuAdd")) }
    add(highlight = editable) { FloatingMenuRow(tr("editor.treeAddWarpParent"),
        { close(); onRequestCreate?.invoke(CreatePlacementKind.WARP, CreateRelation.AS_PARENT, true, deformer.id.raw) },
        enabled = editable, icon = { IconContextualWarp(it, Modifier.size(12.dp)) }) }
    add(highlight = editable) { FloatingMenuRow(tr("editor.treeAddWarpChild"),
        { close(); onRequestCreate?.invoke(CreatePlacementKind.WARP, CreateRelation.AS_CHILD, true, deformer.id.raw) },
        enabled = editable, icon = { IconWarpDeformer(it, Modifier.size(12.dp)) }) }
    add(highlight = editable) { FloatingMenuRow(tr("editor.treeAddRotationParent"),
        { close(); onRequestCreate?.invoke(CreatePlacementKind.ROTATION, CreateRelation.AS_PARENT, true, deformer.id.raw) },
        enabled = editable, icon = { IconRotationDeformer(Modifier.size(12.dp), it) }) }
    if (deformer is Deformer.Warp) {
        val swung = state.previewModel?.let { io.github.psd2live.core.SwingCatalog.find(state.rigEdits, it.rig.puppet, listOf(deformer.id.raw)) } != null
        add(highlight = editable) { FloatingMenuRow(tr(if (swung) "swing.menuEdit" else "swing.menu"),
            { close(); viewModel.beginSwing(listOf(deformer.id.raw)) }, enabled = editable,
            icon = { IconWarpDeformer(it, Modifier.size(12.dp)) }) }
    }
    add(highlight = editable) { FloatingMenuRow(tr("canvas.hierarchy.importLayer"), {
        close()
        val picked = NativeFilePicker.chooseTransparentImages()
        if (picked.isNotEmpty()) viewModel.importLayersFromFiles(picked, ImageLayerInsertion(deformer.id.raw))
    }, enabled = editable, icon = { IconSelectionBounds(it, Modifier.size(12.dp)) }) }
    val self = "${if (deformer is Deformer.Warp) "warp" else "rotation"}:${deformer.id.raw}"
    add(false) { FloatingMenuSection(tr("objectOps.section")) }
    add { FloatingMenuRow(tr("objectOps.copy"), { close(); viewModel.copyObjects(listOf(self)) },
        trailing = state.keymap.labelFor(ShortcutAction.COPY), icon = { GridIcon(Modifier.size(12.dp), it) { copySheets() } }) }
    val canPaste = editable && viewModel.objectClipboard.isNotEmpty()
    add(highlight = canPaste) { FloatingMenuRow(tr("objectOps.pasteInto"), { close(); viewModel.pasteObjects(parent = self) },
        enabled = canPaste, icon = { GridIcon(Modifier.size(12.dp), it) { pasteBoard() } }) }
    add(false) { FloatingMenuSection(tr("canvas.hierarchy.menuView")) }
    add { FloatingMenuRow(tr("canvas.hierarchy.expandBranch"), { close(); expandBranch() },
        icon = { IconExpandBranch(Modifier.size(12.dp), it) }) }
    add { FloatingMenuRow(tr("canvas.hierarchy.collapseBranch"), { close(); collapseBranch() },
        icon = { IconCollapseBranch(Modifier.size(12.dp), it) }) }
    // The row's name may be one link of a collapsed chain: say whether each delete removes that link or the chain.
    for (row in deformerDeleteRows(chainIds, deformer.id.raw)) {
        add(highlight = editable, danger = true) { FloatingMenuRow(
            if (row.count > 0) tr(row.labelKey, row.count) else tr(row.labelKey),
            { close(); viewModel.deleteDeformers(row.idsInnermostFirst) }, enabled = editable, danger = true,
            icon = { IconTrash(Modifier.size(12.dp), it) }) }
    }
    GlidingColumn(Modifier.width(236.dp), rows.size, { rows[it].first }, dangers = { rows[it].second }) { rows[it].third() }
}

/** One delete row of the deformer menu: the whole chain, or just the link the menu is about. */
internal data class DeformerDeleteRow(val labelKey: String, val count: Int, val idsInnermostFirst: List<String>)

/**
 * The delete rows for a collapsed chain. A single-deformer chain keeps the one "delete" row; a chain names the
 * difference between removing the right-clicked link and removing every link, so the title and the action agree.
 */
internal fun deformerDeleteRows(chainIds: List<String>, focusedId: String): List<DeformerDeleteRow> =
    if (chainIds.size > 1) listOf(
        DeformerDeleteRow("canvas.hierarchy.deleteDeformerCurrent", 0, listOf(focusedId)),
        DeformerDeleteRow("canvas.hierarchy.deleteDeformerChain", chainIds.size, chainIds.asReversed()),
    ) else listOf(DeformerDeleteRow("canvas.hierarchy.deleteDeformer", 0, chainIds.asReversed()))
