package org.umamo.edit

/**
 * Which kind of mesh element a pick selects and a selection stores, mirroring Blender's vertex / edge /
 * face select modes.
 *
 * 編集モードの選択対象の種類。Blender の頂点・辺・面選択モードに対応する。
 */
enum class MeshSelectMode {
	Vertex,
	Edge,
	Face,
}

/**
 * One selectable mesh element: a vertex, an undirected edge, or a face.  Element identity is LOCAL to
 * its drawable's mesh (a vertex index means nothing without knowing which mesh), so callers pair an
 * element with its drawable.  The mesh topology is immutable while editing (copy-on-write replaces only
 * positions), so vertex indices, canonical (low, high) edge pairs, and triangle indices are stable
 * identities across every gesture.
 *
 * メッシュの選択可能要素。頂点・無向辺・面（三角形）のいずれか。要素の同一性はメッシュ内ローカル。
 */
sealed interface MeshElement {
	/**
	 * A single mesh vertex.
	 *
	 * @property Int index The vertex index (into the mesh's vertex list).
	 */
	data class Vertex(val index: Int) : MeshElement

	/**
	 * An undirected mesh edge, canonicalized so (a, b) and (b, a) are one identity.  Construct via [of].
	 *
	 * @property Int endpointLow The smaller endpoint vertex index.
	 * @property Int endpointHigh The larger endpoint vertex index.
	 */
	data class Edge(val endpointLow: Int, val endpointHigh: Int) : MeshElement {
		companion object {
			/**
			 * Builds the canonical undirected edge for two endpoint vertex indices, given in either order.
			 *
			 * @param Int vertexA One endpoint vertex index.
			 * @param Int vertexB The other endpoint vertex index.
			 * @return Edge The canonical (low, high) edge.
			 */
			fun of(vertexA: Int, vertexB: Int): Edge =
				if (vertexA <= vertexB) {
					Edge(vertexA, vertexB)
				} else {
					Edge(vertexB, vertexA)
				}
		}
	}

	/**
	 * A single mesh face.  The mesh is triangles-only, so a face is one triangle of the index list.
	 *
	 * @property Int triangleIndex The triangle ordinal (three consecutive entries of the index list).
	 */
	data class Face(val triangleIndex: Int) : MeshElement
}
