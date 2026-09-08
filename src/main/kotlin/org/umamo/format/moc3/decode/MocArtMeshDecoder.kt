package org.umamo.format.moc3.decode

import org.umamo.format.moc3.moc.MocDrawable
import org.umamo.format.moc3.moc.MocSections
import org.umamo.format.moc3.moc.Section
import org.umamo.format.moc3.model.ArtMesh
import org.umamo.format.moc3.model.ArtMeshKeyform

/**
 * Decodes the drawables into [ArtMesh]es: their geometry slices and per-keyform values.
 *
 * A drawable's UVs, triangle indices, and mask indices are not addressed by any index table -
 * they are CONCATENATED per drawable in drawable order, so the decode carries a running cursor
 * per table and advances it by that drawable's own counts.  The mask cursor is the one that does
 * not start at zero: the offscreen mask entries are the block's prefix (MOC3 v6 §5.6 section 80).
 */
internal class MocArtMeshDecoder(
	sections: MocSections,
	private val bindings: MocBindingResolver,
	private val colorTables: ColorTables,
	private val keyformValues: KeyformValueTables,
	private val maskData: IntArray,
) {
	private val keyformBindingIndex = sections.intArray(Section.ARTMESH_KEYFORM_BINDING)
	private val keyformBase = sections.intArray(Section.ARTMESH_KEYFORM_BASE)
	private val parentDeformer = sections.intArray(Section.ARTMESH_PARENT_DEFORMER)
	private val colorBase =
		if (sections.isPresent(Section.ARTMESH_COLOR_BASE)) sections.intArray(Section.ARTMESH_COLOR_BASE) else null

	// MOC3 v6 §5.6 s153: per-drawable packed extended blend (0 = legacy constant-flags blend).
	private val extendedBlend =
		if (sections.isPresent(Section.ARTMESH_EXTENDED_BLEND)) sections.intArray(Section.ARTMESH_EXTENDED_BLEND) else null

	// s37 is the visibility toggle (pinned by joining miku_verycursed against its CMO3 twin); s38 is
	// 1 on every drawable of every corpus sample and is carried only so a bake reproduces it.
	private val isVisible = sections.intArray(Section.ARTMESH_IS_VISIBLE)
	private val isEnabled = sections.intArray(Section.ARTMESH_IS_ENABLED)

	private val uvData = sections.floatArray(Section.ARTMESH_UV_DATA)
	private val indexData = sections.shortArray(Section.ARTMESH_INDEX_DATA)

	/**
	 * Where the drawables' mask entries begin: the offscreen entries are the block's PREFIX and
	 * the drawables' masks follow (pinned on Model A against the CMO3 ground truth + the runtime's
	 * s158 addressing, which offsets from the block start).  Pre-v6 there is no prefix.
	 */
	private val offscreenMaskTotal =
		if (sections.isPresent(Section.OFFSCREEN_MASK_COUNT)) {
			sections.intArray(Section.OFFSCREEN_MASK_COUNT).sum()
		} else {
			0
		}

	/**
	 * Decodes every drawable, walking the concatenated geometry tables in drawable order.
	 *
	 * @param List<MocDrawable> drawables The model's drawables, whose counts drive the cursors.
	 * @return List<ArtMesh> The decoded art meshes, in drawable order.
	 */
	fun decodeAll(drawables: List<MocDrawable>): List<ArtMesh> {
		var vertexBase = 0
		var indexBase = 0
		var maskBase = offscreenMaskTotal
		return drawables.mapIndexed { drawableIndex, drawable ->
			val vertexCount = drawable.vertexCount
			val uvs = uvData.copyOfRange(vertexBase * 2, vertexBase * 2 + vertexCount * 2)
			val triangleIndices = indexData.copyOfRange(indexBase, indexBase + drawable.indexCount)
			val maskIndices = maskData.copyOfRange(maskBase, maskBase + drawable.maskCount)
			vertexBase += vertexCount
			indexBase += drawable.indexCount
			maskBase += drawable.maskCount
			artMesh(drawableIndex, drawable, uvs, triangleIndices, maskIndices)
		}
	}

	/**
	 * Builds one art mesh from its already-sliced geometry plus its per-keyform values.
	 *
	 * @param Int          drawableIndex   The drawable's index (every per-drawable table's row).
	 * @param MocDrawable  drawable        The drawable header (id, texture, flags, counts).
	 * @param FloatArray   uvs             This drawable's slice of the UV table.
	 * @param ShortArray   triangleIndices This drawable's slice of the index table.
	 * @param IntArray     maskIndices     This drawable's slice of the mask table.
	 * @return ArtMesh The decoded art mesh.
	 */
	private fun artMesh(
		drawableIndex: Int,
		drawable: MocDrawable,
		uvs: FloatArray,
		triangleIndices: ShortArray,
		maskIndices: IntArray,
	): ArtMesh {
		val keyformBinding = keyformBindingIndex[drawableIndex]
		val base = keyformBase[drawableIndex]
		val keyforms =
			(0 until bindings.binding(keyformBinding).gridSize).map { gridIndex ->
				val positionOffset = keyformValues.positionIndex[base + gridIndex]
				ArtMeshKeyform(
					keyformValues.positionValues.copyOfRange(
						positionOffset,
						positionOffset + drawable.vertexCount * 2,
					),
					keyformValues.artMeshOpacity[base + gridIndex],
					keyformValues.artMeshDrawOrder[base + gridIndex],
					colorTables.multiplyForKeyform(colorBase?.get(drawableIndex), gridIndex),
					colorTables.screenForKeyform(colorBase?.get(drawableIndex), gridIndex),
				)
			}
		return ArtMesh(
			drawable.id,
			drawable.textureIndex,
			drawable.constantFlags,
			extendedBlend?.get(drawableIndex) ?: 0,
			// Default to visible: a stripped file omitting the flags means "nothing is hidden".
			isVisible.getOrElse(drawableIndex) { 1 } != 0,
			isEnabled.getOrElse(drawableIndex) { 1 } != 0,
			drawable.parentPartIndex,
			parentDeformer[drawableIndex],
			uvs,
			triangleIndices,
			maskIndices,
			keyformBinding,
			keyforms,
		)
	}
}