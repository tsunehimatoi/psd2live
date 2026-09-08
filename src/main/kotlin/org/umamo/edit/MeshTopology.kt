package org.umamo.edit

/**
 * Pure topology queries over a triangle index list (three vertex indices per triangle).  The single
 * source of truth for Blender's select-mode rules: [MeshSelectionOps.changeSelectMode] uses these to
 * convert a selection between domains, and the gizmo overlay uses the same derive-up functions for its
 * cross-domain display highlights - so the flush / derive semantics live (and are tested) once.
 *
 * 三角形インデックス列に対する純粋なトポロジ照会。選択モード変換と表示ハイライトの共通基盤。
 */
object MeshTopology {
	/**
	 * The deduplicated undirected edges of a triangle index list.  Each interior edge is shared by two
	 * triangles but must appear once, so edges are canonicalized to (low, high) before deduplication.
	 *
	 * @param IntArray triangleIndices The mesh triangle vertex indices (three per triangle).
	 * @return List<MeshElement.Edge> The unique edges, in first-encounter order.
	 */
	fun uniqueEdges(triangleIndices: IntArray): List<MeshElement.Edge> {
		val edges = LinkedHashSet<MeshElement.Edge>()
		val triangleCount = triangleIndices.size / 3
		for (triangleIndex in 0 until triangleCount) {
			edges.addAll(edgesOfTriangle(triangleIndices, triangleIndex))
		}
		return edges.toList()
	}

	/**
	 * The three canonical edges of one triangle.
	 *
	 * @param IntArray triangleIndices The mesh triangle vertex indices (three per triangle).
	 * @param Int triangleIndex The triangle ordinal.
	 * @return List<MeshElement.Edge> The triangle's edges.
	 */
	fun edgesOfTriangle(triangleIndices: IntArray, triangleIndex: Int): List<MeshElement.Edge> {
		val cornerA = triangleIndices[triangleIndex * 3]
		val cornerB = triangleIndices[triangleIndex * 3 + 1]
		val cornerC = triangleIndices[triangleIndex * 3 + 2]
		return listOf(
			MeshElement.Edge.of(cornerA, cornerB),
			MeshElement.Edge.of(cornerB, cornerC),
			MeshElement.Edge.of(cornerC, cornerA),
		)
	}

	/**
	 * The three vertex indices of one triangle.
	 *
	 * @param IntArray triangleIndices The mesh triangle vertex indices (three per triangle).
	 * @param Int triangleIndex The triangle ordinal.
	 * @return Set<Int> The triangle's vertex indices.
	 */
	fun verticesOfTriangle(triangleIndices: IntArray, triangleIndex: Int): Set<Int> =
		setOf(
			triangleIndices[triangleIndex * 3],
			triangleIndices[triangleIndex * 3 + 1],
			triangleIndices[triangleIndex * 3 + 2],
		)

	/**
	 * The union of vertices covered by a set of elements: a vertex covers itself, an edge its two
	 * endpoints, a face its three corners.  This is the flush-down rule (any mode to vertex mode), and
	 * what the G / S / R operators transform for an edge or face selection.
	 *
	 * @param Set<MeshElement> elements The selected elements (any mix of domains).
	 * @param IntArray triangleIndices The mesh triangle vertex indices (three per triangle).
	 * @return Set<Int> The covered vertex indices.
	 */
	fun coveredVertexIndices(elements: Set<MeshElement>, triangleIndices: IntArray): Set<Int> {
		val covered = HashSet<Int>()
		for (element in elements) {
			when (element) {
				is MeshElement.Vertex -> covered.add(element.index)

				is MeshElement.Edge -> {
					covered.add(element.endpointLow)
					covered.add(element.endpointHigh)
				}

				is MeshElement.Face -> covered.addAll(verticesOfTriangle(triangleIndices, element.triangleIndex))
			}
		}
		return covered
	}

	/**
	 * The edges whose both endpoints are in the selected vertex set - the vertex-to-edge derive-up rule,
	 * also used to highlight edges while selecting vertices.
	 *
	 * @param IntArray triangleIndices The mesh triangle vertex indices (three per triangle).
	 * @param Set<Int> selectedVertexIndices The selected vertex indices.
	 * @return Set<MeshElement.Edge> The qualifying edges.
	 */
	fun edgesWithBothEndpointsSelected(triangleIndices: IntArray, selectedVertexIndices: Set<Int>): Set<MeshElement.Edge> =
		uniqueEdges(triangleIndices)
			.filter { edge -> edge.endpointLow in selectedVertexIndices && edge.endpointHigh in selectedVertexIndices }
			.toSet()

	/**
	 * The faces whose every vertex is in the selected vertex set - the vertex-to-face derive-up rule,
	 * also used to highlight faces while selecting vertices.
	 *
	 * @param IntArray triangleIndices The mesh triangle vertex indices (three per triangle).
	 * @param Set<Int> selectedVertexIndices The selected vertex indices.
	 * @return Set<MeshElement.Face> The qualifying faces.
	 */
	fun facesWithAllVerticesSelected(triangleIndices: IntArray, selectedVertexIndices: Set<Int>): Set<MeshElement.Face> {
		val faces = HashSet<MeshElement.Face>()
		val triangleCount = triangleIndices.size / 3
		for (triangleIndex in 0 until triangleCount) {
			if (verticesOfTriangle(triangleIndices, triangleIndex).all { vertexIndex -> vertexIndex in selectedVertexIndices }) {
				faces.add(MeshElement.Face(triangleIndex))
			}
		}
		return faces
	}

	/**
	 * The faces whose all three own edges are in the selected edge set - the edge-to-face derive-up rule,
	 * also used to highlight faces while selecting edges.  Strict per Blender: a face whose vertices are
	 * merely covered by neighboring faces' selected edges does not qualify.
	 *
	 * @param IntArray triangleIndices The mesh triangle vertex indices (three per triangle).
	 * @param Set<MeshElement.Edge> selectedEdges The selected (canonical) edges.
	 * @return Set<MeshElement.Face> The qualifying faces.
	 */
	fun facesWithAllEdgesSelected(triangleIndices: IntArray, selectedEdges: Set<MeshElement.Edge>): Set<MeshElement.Face> {
		val faces = HashSet<MeshElement.Face>()
		val triangleCount = triangleIndices.size / 3
		for (triangleIndex in 0 until triangleCount) {
			if (edgesOfTriangle(triangleIndices, triangleIndex).all { edge -> edge in selectedEdges }) {
				faces.add(MeshElement.Face(triangleIndex))
			}
		}
		return faces
	}

	/**
	 * The per-vertex neighbor table of a triangle mesh: entry v lists the vertices sharing an edge with
	 * vertex v, deduplicated (an interior edge is shared by two triangles but yields one neighbor pair).
	 * Vertices outside every triangle (or an out-of-range index in a malformed list) get an empty entry.
	 * Build once per mesh and feed the connectivity walks below - Select Linked's flood fill and the
	 * Individual Origins island split.
	 *
	 * @param Int vertexCount The mesh's vertex count (the table's size).
	 * @param IntArray triangleIndices The mesh triangle vertex indices (three per triangle).
	 * @return List<IntArray> The neighbor vertex indices per vertex.
	 */
	fun buildVertexAdjacency(vertexCount: Int, triangleIndices: IntArray): List<IntArray> {
		val neighborSets = List(vertexCount) { HashSet<Int>() }

		fun connect(vertexA: Int, vertexB: Int) {
			if (vertexA in 0 until vertexCount && vertexB in 0 until vertexCount && vertexA != vertexB) {
				neighborSets[vertexA].add(vertexB)
				neighborSets[vertexB].add(vertexA)
			}
		}
		val triangleCount = triangleIndices.size / 3
		for (triangleIndex in 0 until triangleCount) {
			val cornerA = triangleIndices[triangleIndex * 3]
			val cornerB = triangleIndices[triangleIndex * 3 + 1]
			val cornerC = triangleIndices[triangleIndex * 3 + 2]
			connect(cornerA, cornerB)
			connect(cornerB, cornerC)
			connect(cornerC, cornerA)
		}
		return neighborSets.map { neighbors -> neighbors.toIntArray() }
	}

	/**
	 * Every vertex connected to [seedVertexIndex] through the mesh's edges, the seed included - the flood
	 * fill behind Select Linked (Blender's L).  An out-of-range seed yields an empty set.
	 *
	 * @param List<IntArray> adjacency The neighbor table from [buildVertexAdjacency].
	 * @param Int seedVertexIndex The vertex to flood from.
	 * @return Set<Int> The seed's whole connected component.
	 */
	fun connectedVertices(adjacency: List<IntArray>, seedVertexIndex: Int): Set<Int> {
		if (seedVertexIndex !in adjacency.indices) {
			return emptySet()
		}
		val reached = HashSet<Int>()
		val frontier = ArrayDeque<Int>()
		reached.add(seedVertexIndex)
		frontier.add(seedVertexIndex)
		while (frontier.isNotEmpty()) {
			val vertexIndex = frontier.removeFirst()
			for (neighbor in adjacency[vertexIndex]) {
				if (reached.add(neighbor)) {
					frontier.add(neighbor)
				}
			}
		}
		return reached
	}

	/**
	 * Splits a vertex subset into its connected islands: the components of the sub-graph induced on
	 * [vertexIndices] (two selected vertices join an island only when a mesh edge connects them within
	 * the subset).  This is Blender's Individual Origins grouping - each island transforms about its own
	 * pivot.  Out-of-range indices are dropped; the island order follows the subset's iteration order.
	 *
	 * @param List<IntArray> adjacency The neighbor table from [buildVertexAdjacency].
	 * @param Set<Int> vertexIndices The vertex subset to split.
	 * @return List<Set<Int>> The islands, each a disjoint subset of [vertexIndices].
	 */
	fun selectionIslands(adjacency: List<IntArray>, vertexIndices: Set<Int>): List<Set<Int>> {
		val unvisited = vertexIndices.filterTo(LinkedHashSet()) { vertexIndex -> vertexIndex in adjacency.indices }
		val islands = ArrayList<Set<Int>>()
		while (unvisited.isNotEmpty()) {
			val seed = unvisited.first()
			val island = HashSet<Int>()
			val frontier = ArrayDeque<Int>()
			island.add(seed)
			unvisited.remove(seed)
			frontier.add(seed)
			while (frontier.isNotEmpty()) {
				val vertexIndex = frontier.removeFirst()
				for (neighbor in adjacency[vertexIndex]) {
					if (neighbor in unvisited) {
						unvisited.remove(neighbor)
						island.add(neighbor)
						frontier.add(neighbor)
					}
				}
			}
			islands.add(island)
		}
		return islands
	}
}