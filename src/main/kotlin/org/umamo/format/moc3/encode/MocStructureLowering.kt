package org.umamo.format.moc3.encode

import org.umamo.format.moc3.moc.Section
import org.umamo.format.moc3.model.WarpDeformer

/**
 * Synthesizes the structural/topology sections from [context], keyed by section-table index.
 *
 * @param MocLoweringContext context The shared lowering derivations.
 * @return Map Section index → element-region bytes (no trailing padding).
 */
internal fun structuralSections(context: MocLoweringContext): Map<Int, ByteArray> {
	val doc = context.doc
	val sink = SectionSink(doc.version)

	doc.canvas?.let { canvas ->
		sink.putFloats(Section.CANVAS, canvas.pixelsPerUnit, canvas.originX, canvas.originY, canvas.width, canvas.height, 0f)
	}

	// parameters
	sink.putIds(Section.PARAM_ID, doc.parameters.map { it.id })
	sink.putFloats(Section.PARAM_MAX, doc.parameters.map { it.maximumValue })
	sink.putFloats(Section.PARAM_MIN, doc.parameters.map { it.minimumValue })
	sink.putFloats(Section.PARAM_DEFAULT, doc.parameters.map { it.defaultValue })
	sink.putInts(Section.PARAM_REPEAT, doc.parameters.map { if (it.repeats) 1 else 0 })
	if (doc.parameters.any { it.type != null }) {
		sink.putInts(Section.PARAM_TYPE, doc.parameters.map { it.type!!.number })
	}

	// parts
	sink.putIds(Section.PART_ID, doc.parts.map { it.id })
	// MOC3 §5.6 s7/s8: a paired flag column, the same shape as the deformer (s13/s14) and art-mesh
	// (s37/s38) pairs.  The FIRST carries visibility; the SECOND is 1 on every part of every corpus
	// sample and its meaning is unpinned, which `PairedVisibilityFlagProbeTest` pins as an invariant.
	// Projecting isVisible into both would put a 0 in a column the editor has never been observed
	// writing one into - a hidden part would make our bake the only file in existence that deviates.
	sink.putInts(Section.PART_VISIBLE_ARTMESHES, doc.parts.map { if (it.isVisible) 1 else 0 })
	sink.putInts(Section.PART_VISIBLE_DEFORMERS, doc.parts.map { 1 })
	sink.putInts(Section.PART_PARENT, doc.parts.map { it.parentPartIndex })
	sink.putInts(Section.PART_KEYFORM_BINDING, doc.parts.map { it.keyformBindingIndex })

	// drawables (art meshes) + topology
	sink.putIds(Section.ARTMESH_ID, doc.artMeshes.map { it.id })
	sink.putInts(Section.ARTMESH_TEXTURE, doc.artMeshes.map { it.textureIndex })
	sink.putBytes(Section.ARTMESH_CONSTANT_FLAGS, ByteArray(doc.artMeshes.size) { doc.artMeshes[it].constantFlags.toByte() })
	// MOC3 v6 §5.6 s153: per-drawable packed extended blend (v6-only; the sink drops it below v6).
	sink.putInts(Section.ARTMESH_EXTENDED_BLEND, doc.artMeshes.map { it.extendedBlend })
	// Hidden art meshes are CARRIED with the flag clear, never dropped - the official editor deletes
	// them by default, but Umamo has no option to and doing it silently would be destructive.
	sink.putInts(Section.ARTMESH_IS_VISIBLE, doc.artMeshes.map { if (it.isVisible) 1 else 0 })
	sink.putInts(Section.ARTMESH_IS_ENABLED, doc.artMeshes.map { if (it.isEnabled) 1 else 0 })
	sink.putInts(Section.ARTMESH_VERTEX_COUNT, doc.artMeshes.map { it.vertexCount })
	sink.putInts(Section.ARTMESH_INDEX_COUNT, doc.artMeshes.map { it.triangleIndices.size })
	sink.putInts(Section.ARTMESH_MASK_COUNT, doc.artMeshes.map { it.maskDrawableIndices.size })
	sink.putInts(Section.ARTMESH_PARENT_PART, doc.artMeshes.map { it.parentPartIndex })
	sink.putInts(Section.ARTMESH_PARENT_DEFORMER, doc.artMeshes.map { it.parentDeformerIndex })
	sink.putInts(Section.ARTMESH_KEYFORM_BINDING, doc.artMeshes.map { it.keyformBindingIndex })
	sink.putInts(Section.ARTMESH_KEYFORM_COUNT, doc.artMeshes.map { doc.keyformBinding(it.keyformBindingIndex)?.gridSize ?: 1 })
	sink.putFloatConcat(Section.ARTMESH_UV_DATA, doc.artMeshes) { it.vertexUvs }
	sink.putShortConcat(Section.ARTMESH_INDEX_DATA, doc.artMeshes) { it.triangleIndices }
	// The mask-index block holds (moc 6) the offscreens' mask lists as a PREFIX, then the
	// drawables' mask lists (MOC3 §5.6 section 80; s158 offsets from the block start - pinned on
	// Model A against the CMO3 clip lists).  The prefix synthesizes from the typed
	// Offscreen.maskIndices; a doc predating the typed extraction (index count != maskCount)
	// carries the section instead.
	if (doc.offscreens.all { it.maskIndices.size == it.maskCount }) {
		val maskIndexValues = ArrayList<Int>()
		for (offscreen in doc.offscreens) {
			offscreen.maskIndices.forEach { maskIndexValues.add(it) }
		}
		for (mesh in doc.artMeshes) {
			mesh.maskDrawableIndices.forEach { maskIndexValues.add(it) }
		}
		sink.putInts(Section.MASK_INDEX_DATA, maskIndexValues)
	}

	// deformers (unified list + per-type).  The block's leading runtime slot is sized by
	// [MocRuntimeSlots] along with every other object block's, so it is not written here.
	sink.putIds(Section.DEFORMER_ID, doc.deformers.map { it.id })
	sink.putInts(Section.DEFORMER_KEYFORM_BINDING, doc.deformers.map { it.keyformBindingIndex })
	sink.putInts(Section.DEFORMER_IS_VISIBLE, doc.deformers.map { if (it.isVisible) 1 else 0 })
	sink.putInts(Section.DEFORMER_IS_ENABLED, doc.deformers.map { if (it.isEnabled) 1 else 0 })
	sink.putInts(Section.DEFORMER_PARENT_PART, doc.deformers.map { it.parentPartIndex })
	sink.putInts(Section.DEFORMER_PARENT, doc.deformers.map { it.parentDeformerIndex })
	sink.putInts(Section.DEFORMER_TYPE, doc.deformers.map { if (it is WarpDeformer) 0 else 1 })
	// per-deformer index within its type group (warps vs rotations), in deformer-list order
	val localIndex = IntArray(doc.deformers.size)
	run {
		var nextWarpLocal = 0
		var nextRotationLocal = 0
		for ((deformerIndex, deformer) in doc.deformers.withIndex()) {
			localIndex[deformerIndex] = if (deformer is WarpDeformer) nextWarpLocal++ else nextRotationLocal++
		}
	}
	sink.putInts(Section.DEFORMER_LOCAL_INDEX, localIndex.toList())
	val warps = context.warps
	val rotations = context.rotations
	sink.putInts(Section.WARP_CONTROL_POINT_COUNT, warps.map { (it.rows + 1) * (it.columns + 1) })
	sink.putInts(Section.WARP_ROWS, warps.map { it.rows })
	sink.putInts(Section.WARP_COLUMNS, warps.map { it.columns })
	sink.putInts(Section.WARP_MODE, warps.map { it.mode })
	sink.putInts(Section.WARP_KEYFORM_BINDING, warps.map { it.keyformBindingIndex })
	sink.putFloats(Section.ROTATION_BASE_ANGLE, rotations.map { it.baseAngle })
	sink.putInts(Section.ROTATION_KEYFORM_BINDING, rotations.map { it.keyformBindingIndex })

	// glue topology (mesh pair + binding)
	sink.putInts(Section.GLUE_MESH_A, doc.glues.map { it.meshAIndex })
	sink.putInts(Section.GLUE_MESH_B, doc.glues.map { it.meshBIndex })
	sink.putInts(Section.GLUE_KEYFORM_BINDING, doc.glues.map { it.keyformBindingIndex })

	return sink.toMap()
}