package io.github.psd2live.application

import io.github.psd2live.core.LayerClassificationOverride
import io.github.psd2live.core.LayerType
import io.github.psd2live.core.RasterMeshCreation
import io.github.psd2live.core.RigPreviewModel
import io.github.psd2live.core.SemanticTag
import io.github.psd2live.core.Side
import io.github.psd2live.project.WorkspaceDocument
import kotlinx.serialization.json.jsonPrimitive
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.Part
import org.umamo.runtime.model.PartId
import org.umamo.runtime.model.withDerivedRenderRoot

/**
 * A reference in the layer stack is in the model: once the project is generated, each reference layer
 * ([io.github.psd2live.project.WorkspaceLayout.referenceLayerIds]) is an ArtMesh of its own, created from its pixels
 * as an imported image is ([WorkspaceLayerInsertionEdits.materialize]) - a member of the rig the generators never
 * remesh, drawn by drawing order among the other ArtMeshes, moved, bound and deformed like any of them. Its mesh sits
 * in the reference part ([PART_ID]), a Cubism guide-image part: exports leave it out unless they write guide images.
 * The same mesh is available before semantic generation, including in a new Layout project.
 */
internal object WorkspaceReferenceMeshes {
    /** The guide-image part holding the reference layers' meshes. */
    const val PART_ID = "PartReference"
    private const val PART_NAME = "Reference"

    /** The layers of [document] whose mesh was created for them rather than generated: imported images, references. */
    fun createdLayerIds(document: WorkspaceDocument): Set<String> = document.rigEdits.authoringJournal
        .filter { it["op"]?.jsonPrimitive?.content == RasterMeshCreation.OP }
        .mapNotNullTo(HashSet()) { it["layer_id"]?.jsonPrimitive?.content }

    /** The reference layers of [document] that are in the model: those with their own mesh. */
    fun meshed(document: WorkspaceDocument): Set<String> {
        val references = document.layout.referenceLayerIds
        if (references.isEmpty()) return emptySet()
        return createdLayerIds(document).filterTo(HashSet()) { it in references }
    }

    /**
     * [document] with every reference layer in the model: those still without a mesh get one,
     * at the root, read by no generator ([LayerType.PRESET], no tag). [model] is the model before the edit.
     */
    fun ensure(document: WorkspaceDocument, model: RigPreviewModel, checkCancelled: () -> Unit = {}): WorkspaceDocument {
        if (document.layout.referenceLayerIds.isEmpty()) return document
        val created = createdLayerIds(document)
        val missing = document.source.layers.filter { layer ->
            val id = layer.id.raw
            id in document.layout.referenceLayerIds && id !in created && id !in document.deletedLayerIds &&
                layer.raster.width > 0 && layer.raster.height > 0
        }.mapTo(LinkedHashSet()) { it.id.raw }
        if (missing.isEmpty()) return document
        val classified = document.copy(layerOverrides = document.layerOverrides +
            missing.associateWith { LayerClassificationOverride(LayerType.PRESET, SemanticTag.UNKNOWN, Side.NONE) })
        return WorkspaceLayerInsertionEdits.materialize(classified, model, missing, null, checkCancelled)
    }

    /** [model] with the meshes of [document]'s reference layers gathered in the guide-image part, front of the root. */
    fun sketched(model: RigPreviewModel, document: WorkspaceDocument): RigPreviewModel {
        val references = meshed(document)
        if (references.isEmpty()) return model
        val puppet = model.rig.puppet
        val ids = puppet.drawables.filter { model.rig.layerIdByDrawableId[it.id.raw] in references }.mapTo(LinkedHashSet()) { it.id }
        if (ids.isEmpty()) return model
        val moved = ids.map { OrgChild.Drawable(it) }
        fun keep(children: List<OrgChild>) = children.filterNot { it is OrgChild.Drawable && it.id in ids }
        val partId = PartId(PART_ID)
        val parts = puppet.parts.filter { it.id != partId }.map { it.copy(children = keep(it.children)) } +
            Part(partId, PART_NAME, moved, isSketch = true)
        val roots = listOf<OrgChild>(OrgChild.Part(partId)) + keep(puppet.rootChildren).filterNot { it is OrgChild.Part && it.id == partId }
        return model.copy(rig = model.rig.copy(puppet = puppet.copy(parts = parts, rootChildren = roots).withDerivedRenderRoot()))
            .retainModifierCachesFrom(model)
    }
}
