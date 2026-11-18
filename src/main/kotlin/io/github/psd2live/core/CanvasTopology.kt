package io.github.psd2live.core

import org.umamo.edit.MergeTarget
import org.umamo.edit.MeshElement
import org.umamo.edit.MeshManualOps
import org.umamo.edit.MeshShapes
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.withDerivedRenderRoot
import kotlinx.serialization.json.*
import org.umamo.edit.MeshRefinementOps
import org.umamo.edit.MeshTopology
import org.umamo.edit.MeshTopologyOps
import org.umamo.edit.TopologyOpResult
import org.umamo.runtime.model.DrawableMesh

/**
 * The mesh topology ops the canvas runs, as a pure function of (mesh, action, selection).
 *
 * Extracted from the reducer because the editor has to ask the same question the reducer will answer.
 * The journal carries only the action name, but the editor needs to know **what the op made** - which
 * vertices stay selected, which faces are newly filled - and a `TopologyOpResult` has no way back through
 * a persisted JSON command. Evaluating the same pure function against the same mesh answers identically
 * by construction, so there is still exactly one definition of what each action does, and no new field,
 * no reducer change and no command plumbing.
 *
 * The cost is one extra `O(V+T)` pass per commit, against the `MeshTopology.uniqueEdges` the overlay
 * already runs on every redraw.
 *
 * キャンバスのトポロジ操作を純関数として切り出したもの。reducer とエディタが同じ定義を共有する。
 */
internal object CanvasTopology {

    /**
     * Builds the op [action] names, or null when it does not apply to this mesh and selection.
     *
     * @param DrawableMesh mesh The mesh the op reads (never mutated).
     * @param String action The action name carried by the journal command.
     * @param Set<Int> selected The selected vertex indices.
     * @return TopologyOpResult? The edit and what it made, or null.
     */
    fun build(
        mesh: DrawableMesh,
        action: String,
        selected: Set<Int>,
        anchors: List<MeshRefinementOps.KnifeAnchor> = emptyList(),
        edges: Set<MeshElement.Edge> = emptySet(),
        shapes: () -> MeshShapes = { MeshShapes() },
    ): TopologyOpResult? = when (action) {
        "merge" -> MeshTopologyOps.mergeVertices(mesh, selected.sorted(), MergeTarget.AtCenter)
        "duplicate" -> MeshTopologyOps.duplicateElements(mesh, selected)
        "connect" -> if (selected.size == 2) MeshBoundaryEdits.connect(mesh, selected.min(), selected.max()) else null
        "delete" -> MeshRefinementOps.deleteWithFill(mesh, selected)
            ?: selected.singleOrNull()?.let { MeshBoundaryEdits.deleteVertexFan(mesh, it) }
        "subdivide", "split" -> MeshRefinementOps.subdivideEdges(mesh, edges.ifEmpty { subdivisionEdges(mesh, selected) })
        "knife" -> MeshRefinementOps.knifeCut(mesh, anchors)
        // Hand placement: free points only - an anchor on an existing vertex has nothing to add.
        "add_points" -> MeshManualOps.addPoints(mesh, anchors.filterIsInstance<MeshRefinementOps.KnifeAnchor.AtPoint>().map { it.x to it.y }, shapes())
        // Only the edges named: deleting every edge a vertex or face selection spans is never what was meant.
        "delete_edges" -> MeshManualOps.deleteEdges(mesh, edges, shapes())
        "retriangulate" -> MeshManualOps.retriangulate(mesh, selected, shapes())
        else -> error("Unknown topology operation")
    }

    /**
     * The shapes the hand-placement flips must stay valid in ([MeshShapes]): the drawable as it shows at the default
     * pose, to judge triangle quality in canvas space, and its rest mesh plus every keyform cell and blend form, which
     * must all stay convex. The reducer and the editor's pre-check both read it from the model, so they agree.
     */
    fun shapes(model: PuppetModel, id: DrawableId): MeshShapes {
        val drawable = model.drawables.firstOrNull { it.id == id } ?: return MeshShapes()
        val rest = drawable.mesh?.positions ?: return MeshShapes()
        fun shifted(deltas: FloatArray) = if (deltas.size != rest.size) null else FloatArray(rest.size) { rest[it] + deltas[it] }
        val forms = drawable.geometryGrid?.cells.orEmpty().mapNotNull { shifted(it.form.positionDeltas) } +
            drawable.blendShapes.flatMap { binding -> binding.forms.mapNotNull { form -> form?.let { shifted(it.positionDeltas) } } }
        val shown = shownAtDefault(model, drawable)
        return MeshShapes(shown?.takeIf { it.size == rest.size && it.all(Float::isFinite) }, forms)
    }

    /**
     * [drawable] as it shows at the default pose, evaluated on a model cut down to it, the meshes glued to it and the
     * deformers those hang from: the world positions the whole model gives (masks do not move a mesh, and glue pulls
     * each pair toward its partner's deformed position), at the cost of a few meshes and their parent chains, so
     * replaying many hand edits stays cheap.
     */
    private fun shownAtDefault(model: PuppetModel, drawable: Drawable): FloatArray? {
        val glues = model.glues.filter { it.meshA == drawable.id || it.meshB == drawable.id }
        val ids = (glues.flatMap { listOf(it.meshA, it.meshB) } + drawable.id).toSet()
        val kept = model.drawables.filter { it.id in ids }.map { it.copy(maskedBy = emptyList()) }
        val byId = model.deformers.associateBy { it.id }
        val chains = kept.flatMap { mesh ->
            generateSequence(mesh.parentDeformerId?.let(byId::get)) { deformer -> deformer.parent?.let(byId::get) }.take(model.deformers.size)
        }.map { it.id }.toSet()
        val cut = model.copy(deformers = model.deformers.filter { it.id in chains }, drawables = kept, glues = glues, parts = emptyList(),
            rootChildren = kept.map { OrgChild.Drawable(it.id) }, rootPartId = null, vertexGroups = emptyList()).withDerivedRenderRoot()
        val defaults = model.parameters.associate { it.id to it.default }
        return org.umamo.render.eval.CpuDeformationEvaluator().evaluate(cut, defaults).worldPositions[drawable.id]
    }

    /** Every action [build] knows, in the order the public schema lists them. */
    val ACTIONS = listOf("merge", "duplicate", "connect", "delete", "subdivide", "split", "knife", "add_points", "delete_edges", "retriangulate")

    fun refusalKey(mesh: DrawableMesh, action: String, selected: Set<Int>): String = when {
        action == "connect" && selected.size != 2 -> "editor.topologyRefused.selectPair"
        action == "connect" && MeshElement.Edge.of(selected.min(), selected.max()) in MeshTopology.uniqueEdges(mesh.indices) ->
            "editor.topologyRefused.existingEdge"
        action == "delete" && selected.isEmpty() -> "editor.topologyRefused.selectVertices"
        action == "delete" && mesh.vertexCount - selected.size < 3 -> "editor.topologyRefused.minimumVertices"
        else -> "editor.topologyRefused.$action"
    }

    /** A single selected vertex refines its incident edges; a region refines all fully selected edges. */
    fun subdivisionEdges(mesh: DrawableMesh, selected: Set<Int>): Set<MeshElement.Edge> =
        if (selected.size == 1) MeshTopology.uniqueEdges(mesh.indices).filterTo(LinkedHashSet()) {
            it.endpointLow in selected || it.endpointHigh in selected
        } else MeshTopology.edgesWithBothEndpointsSelected(mesh.indices, selected)

    /**
     * The knife's anchors as the journal carries them. A vertex anchor is its index; a free point is its
     * position in the drawable's own space, which is what lets the reducer re-locate it against the mesh
     * it is actually building - the editor resolved it against the mesh as it stood when it was clicked.
     */
    fun encodeAnchors(anchors: List<MeshRefinementOps.KnifeAnchor>): JsonArray = JsonArray(anchors.map { anchor ->
        when (anchor) {
            is MeshRefinementOps.KnifeAnchor.AtVertex -> buildJsonObject { put("vertex", anchor.index) }
            is MeshRefinementOps.KnifeAnchor.AtPoint -> buildJsonObject { put("x", anchor.x); put("y", anchor.y) }
        }
    })

    fun parseAnchors(value: JsonArray?): List<MeshRefinementOps.KnifeAnchor> = value.orEmpty().map { element ->
        val anchor = element.jsonObject
        val vertex = anchor["vertex"]?.jsonPrimitive?.intOrNull
        if (vertex != null) {
            MeshRefinementOps.KnifeAnchor.AtVertex(vertex)
        } else {
            MeshRefinementOps.KnifeAnchor.AtPoint(anchor.getValue("x").jsonPrimitive.float, anchor.getValue("y").jsonPrimitive.float)
        }
    }

    /** The vertices an op leaves selected, as the editor's flat index set. */
    fun selectedVertices(result: TopologyOpResult?): Set<Int> =
        result?.newElements.orEmpty().filterIsInstance<MeshElement.Vertex>().mapTo(LinkedHashSet()) { it.index }

    /** The faces an op created, for the provisional-fill marker. */
    fun createdFaces(result: TopologyOpResult?): Set<Int> =
        result?.newElements.orEmpty().filterIsInstance<MeshElement.Face>().mapTo(LinkedHashSet()) { it.triangleIndex }
}
