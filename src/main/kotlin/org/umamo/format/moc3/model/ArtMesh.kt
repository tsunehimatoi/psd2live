package org.umamo.format.moc3.model

/** One keyform of an [ArtMesh]: the deformed vertex positions plus opacity/draw-order/color. */
public data class ArtMeshKeyform(
	/** Vertex positions, `vertexCount` points as interleaved `x,y` (model space). */
	val vertexPositions: FloatArray,
	val opacity: Float,
	val drawOrder: Float,
	val multiplyColor: Rgb?,
	val screenColor: Rgb?,
) {
	override fun equals(other: Any?): Boolean =
		this === other ||
			(
				other is ArtMeshKeyform &&
					vertexPositions.contentEquals(other.vertexPositions) &&
					opacity == other.opacity &&
					drawOrder == other.drawOrder &&
					multiplyColor == other.multiplyColor &&
					screenColor == other.screenColor
			)

	override fun hashCode(): Int {
		var hash = vertexPositions.contentHashCode()
		hash = 31 * hash + opacity.hashCode()
		hash = 31 * hash + drawOrder.hashCode()
		hash = 31 * hash + (multiplyColor?.hashCode() ?: 0)
		hash = 31 * hash + (screenColor?.hashCode() ?: 0)
		return hash
	}
}

/**
 * An art mesh (drawable): static topology (UVs, triangles, mask list) plus per-keyform geometry.
 *
 * EN: [vertexUvs] (interleaved `u,v`) and [triangleIndices] are static; the vertex positions are
 *     keyed in [keyforms] over [keyformBindingIndex]'s grid. [parentDeformerIndex] is -1 for a mesh
 *     parented directly to its part.
 */
public data class ArtMesh(
	val id: String,
	val textureIndex: Int,
	/** [org.umamo.format.moc3.moc.ConstantFlag] bitmask. */
	val constantFlags: Int,
	/**
	 * Packed 5.3 extended blend, colorMode or (alphaMode shl 8); 0 = legacy (the constant-flags
	 * 2-bit blend field applies instead).  MOC3 v6 §5.6 s153.
	 */
	val extendedBlend: Int = 0,
	/**
	 * MOC3 §5.6 s37: whether the art mesh is visible (the editor's eye toggle).  The official editor
	 * normally DELETES a hidden mesh at bake time and only carries it - flagged false here - when
	 * "export invisible drawable" is on, which is why almost every corpus file has this true
	 * throughout.  An Umamo export carries hidden meshes this same way unless its own export options
	 * asked to omit them.
	 */
	val isVisible: Boolean = true,
	/** MOC3 §5.6 s38: the drawable's second flag; 1 throughout the corpus (see `ARTMESH_IS_ENABLED`). */
	val isEnabled: Boolean = true,
	val parentPartIndex: Int,
	val parentDeformerIndex: Int,
	val vertexUvs: FloatArray,
	val triangleIndices: ShortArray,
	val maskDrawableIndices: IntArray,
	val keyformBindingIndex: Int,
	val keyforms: List<ArtMeshKeyform>,
) {
	/** Number of vertices (UVs / each keyform's positions hold this many `x,y` pairs). */
	val vertexCount: Int get() = vertexUvs.size / 2

	override fun equals(other: Any?): Boolean =
		this === other ||
			(
				other is ArtMesh &&
					id == other.id &&
					textureIndex == other.textureIndex &&
					constantFlags == other.constantFlags &&
					extendedBlend == other.extendedBlend &&
					isVisible == other.isVisible &&
					isEnabled == other.isEnabled &&
					parentPartIndex == other.parentPartIndex &&
					parentDeformerIndex == other.parentDeformerIndex &&
					vertexUvs.contentEquals(other.vertexUvs) &&
					triangleIndices.contentEquals(other.triangleIndices) &&
					maskDrawableIndices.contentEquals(other.maskDrawableIndices) &&
					keyformBindingIndex == other.keyformBindingIndex &&
					keyforms == other.keyforms
			)

	override fun hashCode(): Int = id.hashCode()
}