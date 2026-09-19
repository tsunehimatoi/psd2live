package io.github.psd2live.core

import org.umamo.edit.MergeTarget
import org.umamo.edit.MeshElement
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
    ): TopologyOpResult? = when (action) {
        "merge" -> MeshTopologyOps.mergeVertices(mesh, selected.sorted(), MergeTarget.AtCenter)
        "duplicate" -> MeshTopologyOps.duplicateElements(mesh, selected)
        "connect" -> if (selected.size == 2) MeshTopologyOps.connectVertices(mesh, selected.first(), selected.last()) else null
        "delete" -> MeshRefinementOps.deleteWithFill(mesh, selected)
        "subdivide", "split" -> MeshRefinementOps.subdivideEdges(mesh, edges.ifEmpty { subdivisionEdges(mesh, selected) })
        "knife" -> MeshRefinementOps.knifeCut(mesh, anchors)
        else -> error("Unknown topology operation")
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
