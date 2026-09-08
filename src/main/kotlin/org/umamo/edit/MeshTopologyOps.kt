package org.umamo.edit

import org.umamo.runtime.model.DrawableMesh
import kotlin.math.abs

/**
 * Where a merge's surviving vertex lands (Blender's Merge menu): the merged vertices' centroid, or the
 * first / last vertex of the selection order.
 *
 * マージ後の頂点の位置（中心・最初・最後）。
 */
enum class MergeTarget {
	AtCenter,
	AtFirst,
	AtLast,
}

/**
 * One topology operation's outcome, ready for the session to commit: the mesh edit plus the elements
 * that should be selected afterwards (the duplicated copies, the merge survivor, the rip copies, the
 * cut path).  Null from a builder means the operation refused (nothing applicable, or a degenerate
 * geometry it will not guess at).
 *
 * トポロジ操作の結果。適用する編集と、操作後に選択される要素。
 *
 * @property MeshTopologyEdit edit The mesh edit to commit.
 * @property Set<MeshElement> newElements The elements selected after the edit (the new topology's ids).
 */
class TopologyOpResult(
	val edit: MeshTopologyEdit,
	val newElements: Set<MeshElement>,
)

/**
 * Pure builders for the topology operations (Shift+D duplicate, M merge, V rip, J connect): each reads
 * one mesh and produces a [TopologyOpResult] for [withMeshTopologyEdit] to commit, or null to refuse.
 * They never touch the model - the session's commitMeshTopology is the single mutation path.
 *
 * トポロジ操作の純粋な構築関数。モデルには触れず、編集内容と新しい選択を返す。
 */
object MeshTopologyOps {
	/**
	 * Duplicates the covered vertices (and every face fully covered by them) in place: the copies append
	 * after the existing vertices, keyform deltas copy per vertex, and the new selection is the copies -
	 * ready for the auto-grab that follows a Blender Shift+D.  Refuses (null) when nothing is covered.
	 *
	 * The induced subgraph is faces-only by data model: DrawableMesh is triangles-only, so a fully
	 * covered EDGE whose triangles have an uncovered third corner has no representation of its own and
	 * is deliberately not carried - duplicating two adjacent vertices yields two unconnected points
	 * (an accepted limitation; Blender would carry the edge because its meshes store edges as
	 * first-class elements).
	 *
	 * Glue note: a duplicated vertex's glue pair stays welded to the ORIGINAL vertex, never the copy -
	 * the old-to-new remap in withMeshTopologyEdit is first-claim, and the kept original (enumerated
	 * before the copies) claims its old index first.  That matches Shift+D expectations (the copy is a
	 * free floating clone).
	 *
	 * @param DrawableMesh mesh The mesh to duplicate within.
	 * @param Set<Int> coveredVertices The vertices the selection covers.
	 * @return TopologyOpResult? The edit + the copies as the new selection, or null.
	 */
	fun duplicateElements(mesh: DrawableMesh, coveredVertices: Set<Int>): TopologyOpResult? {
		val orderedCovered = coveredVertices.filter { vertexIndex -> vertexIndex in 0 until mesh.vertexCount }.sorted()
		if (orderedCovered.isEmpty()) {
			return null
		}
		val oldCount = mesh.vertexCount
		val copyIndexByOld = HashMap<Int, Int>(orderedCovered.size)
		orderedCovered.forEachIndexed { copyOrdinal, oldIndex -> copyIndexByOld[oldIndex] = oldCount + copyOrdinal }

		val newCount = oldCount + orderedCovered.size
		val newPositions = FloatArray(newCount * 2)
		val newUvs = FloatArray(newCount * 2)
		mesh.positions.copyInto(newPositions)
		mesh.uvs.copyInto(newUvs, endIndex = minOf(mesh.uvs.size, newUvs.size))
		orderedCovered.forEachIndexed { copyOrdinal, oldIndex ->
			val newIndex = oldCount + copyOrdinal
			newPositions[newIndex * 2] = mesh.positions[oldIndex * 2]
			newPositions[newIndex * 2 + 1] = mesh.positions[oldIndex * 2 + 1]
			if (oldIndex * 2 + 1 < mesh.uvs.size) {
				newUvs[newIndex * 2] = mesh.uvs[oldIndex * 2]
				newUvs[newIndex * 2 + 1] = mesh.uvs[oldIndex * 2 + 1]
			}
		}

		// Faces whose three corners are all covered duplicate too, over the copied vertices.
		val copiedTriangles = ArrayList<Int>()
		val triangleCount = mesh.indices.size / 3
		for (triangleIndex in 0 until triangleCount) {
			val cornerA = mesh.indices[triangleIndex * 3]
			val cornerB = mesh.indices[triangleIndex * 3 + 1]
			val cornerC = mesh.indices[triangleIndex * 3 + 2]
			if (cornerA in copyIndexByOld && cornerB in copyIndexByOld && cornerC in copyIndexByOld) {
				copiedTriangles.add(copyIndexByOld.getValue(cornerA))
				copiedTriangles.add(copyIndexByOld.getValue(cornerB))
				copiedTriangles.add(copyIndexByOld.getValue(cornerC))
			}
		}
		val newIndices = mesh.indices + copiedTriangles.toIntArray()

		val sources = ArrayList<VertexSource>(newCount)
		for (oldIndex in 0 until oldCount) {
			sources.add(VertexSource.FromOld(oldIndex))
		}
		for (oldIndex in orderedCovered) {
			sources.add(VertexSource.FromOld(oldIndex))
		}
		val newElements = copyIndexByOld.values.map { copyIndex -> MeshElement.Vertex(copyIndex) }.toSet<MeshElement>()
		return TopologyOpResult(MeshTopologyEdit(DrawableMesh(newPositions, newUvs, newIndices), sources), newElements)
	}

	/**
	 * Merges the given vertices into one survivor (Blender's M): AtCenter lands on their centroid with
	 * averaged keyform deltas and UVs; AtFirst / AtLast keep the first / last vertex of the selection
	 * order verbatim.  Unmerged vertices keep their order (so their indices only shift down); the
	 * survivor appends last.  Triangles remap onto the survivor and degenerate ones (fewer than three
	 * distinct corners) drop.  Refuses (null) with fewer than two vertices.
	 *
	 * @param DrawableMesh mesh The mesh to merge within.
	 * @param List<Int> orderedVertices The vertices to merge, in selection order (first / last matter).
	 * @param MergeTarget target Where the survivor lands.
	 * @return TopologyOpResult? The edit + the survivor as the new selection, or null.
	 */
	fun mergeVertices(mesh: DrawableMesh, orderedVertices: List<Int>, target: MergeTarget): TopologyOpResult? {
		val merged = orderedVertices.filter { vertexIndex -> vertexIndex in 0 until mesh.vertexCount }.distinct()
		if (merged.size < 2) {
			return null
		}
		val mergedSet = merged.toSet()
		val oldCount = mesh.vertexCount
		val keptOld = (0 until oldCount).filter { oldIndex -> oldIndex !in mergedSet }
		val survivorIndex = keptOld.size
		val newCount = keptOld.size + 1

		val oldToNew = IntArray(oldCount) { -1 }
		keptOld.forEachIndexed { newIndex, oldIndex -> oldToNew[oldIndex] = newIndex }
		for (oldIndex in merged) {
			oldToNew[oldIndex] = survivorIndex
		}

		val newPositions = FloatArray(newCount * 2)
		val newUvs = FloatArray(newCount * 2)

		fun uvX(oldIndex: Int): Float = if (oldIndex * 2 < mesh.uvs.size) mesh.uvs[oldIndex * 2] else 0f

		fun uvY(oldIndex: Int): Float = if (oldIndex * 2 + 1 < mesh.uvs.size) mesh.uvs[oldIndex * 2 + 1] else 0f
		keptOld.forEachIndexed { newIndex, oldIndex ->
			newPositions[newIndex * 2] = mesh.positions[oldIndex * 2]
			newPositions[newIndex * 2 + 1] = mesh.positions[oldIndex * 2 + 1]
			newUvs[newIndex * 2] = uvX(oldIndex)
			newUvs[newIndex * 2 + 1] = uvY(oldIndex)
		}
		val survivorSource: VertexSource
		when (target) {
			MergeTarget.AtCenter -> {
				var sumX = 0f
				var sumY = 0f
				var sumU = 0f
				var sumV = 0f
				for (oldIndex in merged) {
					sumX += mesh.positions[oldIndex * 2]
					sumY += mesh.positions[oldIndex * 2 + 1]
					sumU += uvX(oldIndex)
					sumV += uvY(oldIndex)
				}
				newPositions[survivorIndex * 2] = sumX / merged.size
				newPositions[survivorIndex * 2 + 1] = sumY / merged.size
				newUvs[survivorIndex * 2] = sumU / merged.size
				newUvs[survivorIndex * 2 + 1] = sumV / merged.size
				survivorSource = VertexSource.AverageOf(merged)
			}

			MergeTarget.AtFirst, MergeTarget.AtLast -> {
				val keeper = if (target == MergeTarget.AtFirst) merged.first() else merged.last()
				newPositions[survivorIndex * 2] = mesh.positions[keeper * 2]
				newPositions[survivorIndex * 2 + 1] = mesh.positions[keeper * 2 + 1]
				newUvs[survivorIndex * 2] = uvX(keeper)
				newUvs[survivorIndex * 2 + 1] = uvY(keeper)
				survivorSource = VertexSource.FromOld(keeper)
			}
		}

		// Remap the triangles onto the survivor, dropping the ones the merge degenerates.
		val newIndices = ArrayList<Int>(mesh.indices.size)
		val triangleCount = mesh.indices.size / 3
		for (triangleIndex in 0 until triangleCount) {
			val cornerA = oldToNew[mesh.indices[triangleIndex * 3]]
			val cornerB = oldToNew[mesh.indices[triangleIndex * 3 + 1]]
			val cornerC = oldToNew[mesh.indices[triangleIndex * 3 + 2]]
			if (cornerA != cornerB && cornerB != cornerC && cornerC != cornerA) {
				newIndices.add(cornerA)
				newIndices.add(cornerB)
				newIndices.add(cornerC)
			}
		}

		val sources = ArrayList<VertexSource>(newCount)
		for (oldIndex in keptOld) {
			sources.add(VertexSource.FromOld(oldIndex))
		}
		sources.add(survivorSource)
		return TopologyOpResult(
			MeshTopologyEdit(DrawableMesh(newPositions, newUvs, newIndices.toIntArray()), sources),
			setOf(MeshElement.Vertex(survivorIndex)),
		)
	}

	/**
	 * Rips the covered vertices apart (Blender's V): each covered vertex that sits between triangle fans
	 * is duplicated, and the adjacent triangles the caller marks (the cursor's side) re-point at the
	 * copy - so the following grab pulls that side away, opening a seam.  Refuses (null) when no
	 * triangle actually re-points (every covered vertex was surrounded by unmarked triangles or has no
	 * fan to split).
	 *
	 * @param DrawableMesh mesh The mesh to rip within.
	 * @param Set<Int> coveredVertices The vertices the selection covers.
	 * @param Function triangleTakesCopy True when the triangle ordinal should follow the ripped copies
	 *   (the overlay marks the triangles on the pointer's side).
	 * @return TopologyOpResult? The edit + the copies as the new selection, or null.
	 */
	fun ripElements(mesh: DrawableMesh, coveredVertices: Set<Int>, triangleTakesCopy: (Int) -> Boolean): TopologyOpResult? {
		val orderedCovered = coveredVertices.filter { vertexIndex -> vertexIndex in 0 until mesh.vertexCount }.sorted()
		if (orderedCovered.isEmpty()) {
			return null
		}
		val oldCount = mesh.vertexCount
		val triangleCount = mesh.indices.size / 3
		// A vertex rips only when its fan splits: at least one adjacent triangle on each side.
		val copyIndexByOld = LinkedHashMap<Int, Int>()
		for (oldIndex in orderedCovered) {
			var marked = 0
			var unmarked = 0
			for (triangleIndex in 0 until triangleCount) {
				val hasCorner =
					mesh.indices[triangleIndex * 3] == oldIndex ||
						mesh.indices[triangleIndex * 3 + 1] == oldIndex ||
						mesh.indices[triangleIndex * 3 + 2] == oldIndex
				if (hasCorner) {
					if (triangleTakesCopy(triangleIndex)) {
						marked++
					} else {
						unmarked++
					}
				}
			}
			if (marked > 0 && unmarked > 0) {
				copyIndexByOld[oldIndex] = oldCount + copyIndexByOld.size
			}
		}
		if (copyIndexByOld.isEmpty()) {
			return null
		}

		val newCount = oldCount + copyIndexByOld.size
		val newPositions = FloatArray(newCount * 2)
		val newUvs = FloatArray(newCount * 2)
		mesh.positions.copyInto(newPositions)
		mesh.uvs.copyInto(newUvs, endIndex = minOf(mesh.uvs.size, newUvs.size))
		for ((oldIndex, copyIndex) in copyIndexByOld) {
			newPositions[copyIndex * 2] = mesh.positions[oldIndex * 2]
			newPositions[copyIndex * 2 + 1] = mesh.positions[oldIndex * 2 + 1]
			if (oldIndex * 2 + 1 < mesh.uvs.size) {
				newUvs[copyIndex * 2] = mesh.uvs[oldIndex * 2]
				newUvs[copyIndex * 2 + 1] = mesh.uvs[oldIndex * 2 + 1]
			}
		}
		val newIndices = mesh.indices.copyOf()
		for (triangleIndex in 0 until triangleCount) {
			if (!triangleTakesCopy(triangleIndex)) {
				continue
			}
			for (cornerSlot in 0 until 3) {
				val copyIndex = copyIndexByOld[newIndices[triangleIndex * 3 + cornerSlot]]
				if (copyIndex != null) {
					newIndices[triangleIndex * 3 + cornerSlot] = copyIndex
				}
			}
		}

		val sources = ArrayList<VertexSource>(newCount)
		for (oldIndex in 0 until oldCount) {
			sources.add(VertexSource.FromOld(oldIndex))
		}
		for (oldIndex in copyIndexByOld.keys) {
			sources.add(VertexSource.FromOld(oldIndex))
		}
		val newElements = copyIndexByOld.values.map { copyIndex -> MeshElement.Vertex(copyIndex) }.toSet<MeshElement>()
		return TopologyOpResult(MeshTopologyEdit(DrawableMesh(newPositions, newUvs, newIndices), sources), newElements)
	}

	/**
	 * Connects two vertices with a cut (Blender's J): walks the straight segment between them, splits
	 * every edge it properly crosses (a new lerped vertex per crossing), and retriangulates the crossed
	 * triangles so the cut becomes real edges.  Deliberately strict: refuses (null) when the vertices
	 * already share an edge, when the segment crosses nothing, or when any crossed triangle's crossing
	 * pattern is degenerate (a collinear graze, an on-vertex hit, more than two crossings) - a refusal
	 * with a notice always beats emitting a broken mesh.
	 *
	 * @param DrawableMesh mesh The mesh to cut within.
	 * @param Int vertexA The cut's first endpoint.
	 * @param Int vertexB The cut's second endpoint.
	 * @return TopologyOpResult? The edit + the cut path as the new selection, or null.
	 */
	fun connectVertices(mesh: DrawableMesh, vertexA: Int, vertexB: Int): TopologyOpResult? {
		val vertexCount = mesh.vertexCount
		if (vertexA == vertexB || vertexA !in 0 until vertexCount || vertexB !in 0 until vertexCount) {
			return null
		}
		if (MeshTopology.uniqueEdges(mesh.indices).contains(MeshElement.Edge.of(vertexA, vertexB))) {
			return null
		}
		val startX = mesh.positions[vertexA * 2]
		val startY = mesh.positions[vertexA * 2 + 1]
		val endX = mesh.positions[vertexB * 2]
		val endY = mesh.positions[vertexB * 2 + 1]

		// One crossing per unique edge, shared by both triangles on that edge.
		val crossingByEdge = LinkedHashMap<MeshElement.Edge, Int>()
		val crossingSources = ArrayList<VertexSource.LerpOf>()
		val oldCount = vertexCount
		for (edge in MeshTopology.uniqueEdges(mesh.indices)) {
			if (edge.endpointLow == vertexA || edge.endpointLow == vertexB || edge.endpointHigh == vertexA || edge.endpointHigh == vertexB) {
				continue
			}
			val crossing =
				properSegmentCrossing(
					startX,
					startY,
					endX,
					endY,
					mesh.positions[edge.endpointLow * 2],
					mesh.positions[edge.endpointLow * 2 + 1],
					mesh.positions[edge.endpointHigh * 2],
					mesh.positions[edge.endpointHigh * 2 + 1],
				) ?: continue
			crossingByEdge[edge] = oldCount + crossingSources.size
			crossingSources.add(VertexSource.LerpOf(edge.endpointLow, edge.endpointHigh, crossing))
		}
		if (crossingByEdge.isEmpty()) {
			return null
		}

		val newCount = oldCount + crossingSources.size
		val newPositions = FloatArray(newCount * 2)
		val newUvs = FloatArray(newCount * 2)
		mesh.positions.copyInto(newPositions)
		mesh.uvs.copyInto(newUvs, endIndex = minOf(mesh.uvs.size, newUvs.size))
		crossingSources.forEachIndexed { crossingOrdinal, source ->
			val newIndex = oldCount + crossingOrdinal
			val t = source.t
			newPositions[newIndex * 2] = mesh.positions[source.oldA * 2] + (mesh.positions[source.oldB * 2] - mesh.positions[source.oldA * 2]) * t
			newPositions[newIndex * 2 + 1] =
				mesh.positions[source.oldA * 2 + 1] + (mesh.positions[source.oldB * 2 + 1] - mesh.positions[source.oldA * 2 + 1]) * t
			if (source.oldA * 2 + 1 < mesh.uvs.size && source.oldB * 2 + 1 < mesh.uvs.size) {
				newUvs[newIndex * 2] = mesh.uvs[source.oldA * 2] + (mesh.uvs[source.oldB * 2] - mesh.uvs[source.oldA * 2]) * t
				newUvs[newIndex * 2 + 1] = mesh.uvs[source.oldA * 2 + 1] + (mesh.uvs[source.oldB * 2 + 1] - mesh.uvs[source.oldA * 2 + 1]) * t
			}
		}

		// Retriangulate the crossed triangles; refuse on any pattern the split rules do not cover.
		val newIndices = ArrayList<Int>(mesh.indices.size + crossingSources.size * 6)
		val triangleCount = mesh.indices.size / 3
		for (triangleIndex in 0 until triangleCount) {
			val corners = intArrayOf(mesh.indices[triangleIndex * 3], mesh.indices[triangleIndex * 3 + 1], mesh.indices[triangleIndex * 3 + 2])
			val crossedSlots = ArrayList<Int>(2)
			val crossingIndices = ArrayList<Int>(2)
			for (cornerSlot in 0 until 3) {
				val edge = MeshElement.Edge.of(corners[cornerSlot], corners[(cornerSlot + 1) % 3])
				val crossingIndex = crossingByEdge[edge]
				if (crossingIndex != null) {
					crossedSlots.add(cornerSlot)
					crossingIndices.add(crossingIndex)
				}
			}
			val hasEndpointCorner = corners.contains(vertexA) || corners.contains(vertexB)
			when {
				crossedSlots.isEmpty() -> {
					newIndices.add(corners[0])
					newIndices.add(corners[1])
					newIndices.add(corners[2])
				}

				// The cut enters at an endpoint corner and exits through the opposite edge: a two-way fan.
				crossedSlots.size == 1 && hasEndpointCorner -> {
					val slot = crossedSlots[0]
					val crossing = crossingIndices[0]
					val apex = corners[(slot + 2) % 3]
					if (apex != vertexA && apex != vertexB) {
						// The crossing sits on an edge whose opposite corner is not the endpoint - a graze the
						// split rules do not cover.
						return null
					}
					newIndices.add(apex)
					newIndices.add(corners[slot])
					newIndices.add(crossing)
					newIndices.add(apex)
					newIndices.add(crossing)
					newIndices.add(corners[(slot + 1) % 3])
				}

				// The cut passes straight through: two crossed edges sharing a corner - one triangle at that
				// corner plus a fan over the remaining quad.
				crossedSlots.size == 2 -> {
					val slotFirst = crossedSlots[0]
					val slotSecond = crossedSlots[1]
					val crossingFirst = crossingIndices[0]
					val crossingSecond = crossingIndices[1]
					// The corner both crossed edges share.
					val sharedCorner =
						when {
							slotFirst == 0 && slotSecond == 1 -> 1
							slotFirst == 1 && slotSecond == 2 -> 2
							slotFirst == 0 && slotSecond == 2 -> 0
							else -> return null
						}
					val previousCorner = corners[(sharedCorner + 2) % 3]
					val nextCorner = corners[(sharedCorner + 1) % 3]
					// Crossing on the edge ENTERING the shared corner vs LEAVING it, in winding order.
					val entering =
						if (slotFirst == (sharedCorner + 2) % 3) {
							crossingFirst
						} else if (slotSecond == (sharedCorner + 2) % 3) {
							crossingSecond
						} else {
							-1
						}
					val leaving =
						if (slotFirst == sharedCorner) {
							crossingFirst
						} else if (slotSecond == sharedCorner) {
							crossingSecond
						} else {
							-1
						}
					if (entering < 0 || leaving < 0) {
						return null
					}
					newIndices.add(corners[sharedCorner])
					newIndices.add(leaving)
					newIndices.add(entering)
					newIndices.add(entering)
					newIndices.add(leaving)
					newIndices.add(nextCorner)
					newIndices.add(entering)
					newIndices.add(nextCorner)
					newIndices.add(previousCorner)
				}

				else -> return null
			}
		}

		val sources = ArrayList<VertexSource>(newCount)
		for (oldIndex in 0 until oldCount) {
			sources.add(VertexSource.FromOld(oldIndex))
		}
		sources.addAll(crossingSources)
		val newElements =
			buildSet<MeshElement> {
				add(MeshElement.Vertex(vertexA))
				add(MeshElement.Vertex(vertexB))
				for (crossingIndex in crossingByEdge.values) {
					add(MeshElement.Vertex(crossingIndex))
				}
			}
		return TopologyOpResult(MeshTopologyEdit(DrawableMesh(newPositions, newUvs, newIndices.toIntArray()), sources), newElements)
	}

	/**
	 * The proper-crossing parameter of segment (a, b) against segment (p, q), or null: strictly interior
	 * on BOTH segments (an epsilon inside the endpoints) and non-parallel - the strictness is what lets
	 * [connectVertices] refuse grazes and on-vertex hits instead of guessing.
	 *
	 * @return Float? The parameter along (p, q) at the crossing, or null when there is no proper crossing.
	 */
	private fun properSegmentCrossing(
		startX: Float,
		startY: Float,
		endX: Float,
		endY: Float,
		edgeStartX: Float,
		edgeStartY: Float,
		edgeEndX: Float,
		edgeEndY: Float,
	): Float? {
		val segmentX = endX - startX
		val segmentY = endY - startY
		val edgeX = edgeEndX - edgeStartX
		val edgeY = edgeEndY - edgeStartY
		val denominator = segmentX * edgeY - segmentY * edgeX
		if (abs(denominator) < 1e-6f) {
			return null
		}
		val betweenX = edgeStartX - startX
		val betweenY = edgeStartY - startY
		val segmentT = (betweenX * edgeY - betweenY * edgeX) / denominator
		val edgeT = (betweenX * segmentY - betweenY * segmentX) / denominator
		val epsilon = 1e-4f
		if (segmentT <= epsilon || segmentT >= 1f - epsilon || edgeT <= epsilon || edgeT >= 1f - epsilon) {
			return null
		}
		return edgeT
	}
}