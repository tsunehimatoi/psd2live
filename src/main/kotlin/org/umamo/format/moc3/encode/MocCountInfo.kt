package org.umamo.format.moc3.encode

import org.umamo.format.moc3.io.LittleEndianWriter
import org.umamo.format.moc3.model.BlendShape
import org.umamo.format.moc3.model.BlendShapeKeyform

/**
 * Synthesizes the CountInfo block (section 0) from [context]: the per-object-kind counts and the
 * cumulative totals the runtime allocates its working buffers from.  The per-kind keyform
 * totals (6-10, 14) INCLUDE the blend-shape delta rows (MOC3 §5.6: CountInfo counts the full
 * shared tables, base plus delta), and fields 23-36 carry the blend-shape and offscreen totals.
 *
 * Every total is taken from the shared context rather than recomputed, because CountInfo declares
 * the extent of tables the other producers write - a second traversal here could disagree with them.
 *
 * @param MocLoweringContext context The shared lowering derivations (document, per-kind deformer
 *                                   lists, blend layout, and the keyform-grid totals).
 * @return ByteArray The CountInfo element-region bytes (`u32[fieldCount]`).
 */
internal fun countInfoSection(context: MocLoweringContext): ByteArray {
	val doc = context.doc
	val warps = context.warps
	val rotations = context.rotations
	val blendLayout = context.blendLayout.takeIf { context.hasBlendShapes }

	fun padded(coordinateCount: Int): Int = (coordinateCount + 15) / 16 * 16

	/**
	 * Sums the record keys of [records] (the per-kind delta-row total).
	 *
	 * @param List<BlendShape>? records One kind's records, or null when the doc has no blends.
	 * @return Int The delta-row total.
	 */
	fun deltaRowsOf(records: List<BlendShape>?): Int = records?.sumOf { it.keyPositions.size } ?: 0
	val positionFloatCount =
		warps.sumOf { warp -> warp.keyforms.sumOf { padded(it.controlPoints.size) } } +
			doc.artMeshes.sumOf { mesh -> mesh.keyforms.sumOf { padded(it.vertexPositions.size) } } +
			(
				blendLayout?.let { layout ->
					layout.warpRecords.sumOf { record ->
						record.keyforms.filterIsInstance<BlendShapeKeyform.Warp>().sumOf { padded(it.form.controlPoints.size) }
					} +
						layout.meshRecords.sumOf { record ->
							record.keyforms.filterIsInstance<BlendShapeKeyform.Mesh>().sumOf { padded(it.form.vertexPositions.size) }
						}
				} ?: 0
			)
	// The block widens to 64 words at MOC3 v5, NOT at MOC3 v6: every v5 corpus file's section 0 is 256
	// bytes, and a v5 model with rotation blend shapes writes field 33 (LimeBirb).  Capping v5 at 32
	// would silently drop that field, and the re-decode would then find zero blend-shape rotations.
	val fieldCount = if (doc.version.byteValue >= 5) 64 else 32
	val countInfo = IntArray(fieldCount)
	countInfo[0] = doc.parts.size
	countInfo[1] = doc.deformers.size
	countInfo[2] = warps.size
	countInfo[3] = rotations.size
	countInfo[4] = doc.artMeshes.size
	countInfo[5] = doc.parameters.size
	countInfo[6] = doc.parts.sumOf { it.drawOrderKeyforms.size } + deltaRowsOf(blendLayout?.partRecords)
	countInfo[7] = warps.sumOf { it.keyforms.size } + deltaRowsOf(blendLayout?.warpRecords)
	countInfo[8] = rotations.sumOf { it.keyforms.size } + deltaRowsOf(blendLayout?.rotationRecords)
	countInfo[9] = doc.artMeshes.sumOf { it.keyforms.size } + deltaRowsOf(blendLayout?.meshRecords)
	countInfo[10] = positionFloatCount
	countInfo[11] = doc.bindings.sumOf { it.axes.size } // total keyform-binding slots
	// 12 sizes the runtime's keyform-binding array, which it then indexes by an object's raw
	// keyformBindingIndex - so the TABLE WIDTH, matching sections 73/74, not the entry count.
	countInfo[12] = context.bindingCount
	countInfo[13] = context.totalParamBindings
	// 14 counts KEY_POSITIONS' full extent: the main-grid dedup keys, the blend binding key runs,
	// and the per-parameter unions (whose counts are section 104).  Each term is the same value the
	// grid producer wrote, taken from the shared context rather than recomputed.
	countInfo[14] = context.mainGridKeyTotal + context.blendLayout.bindingKeys.sumOf { it.size }
	if (context.writesUnionRegion) {
		countInfo[14] += context.unionKeysByParameter.sumOf { it.size }
	}
	countInfo[15] = 2 * doc.artMeshes.sumOf { it.vertexCount }
	countInfo[16] = doc.artMeshes.sumOf { it.triangleIndices.size }
	// 17 sizes MASK_INDEX_DATA, which on MOC3 v6 includes the offscreens' mask prefix (§5.6).
	countInfo[17] = doc.artMeshes.sumOf { it.maskDrawableIndices.size } + doc.offscreens.sumOf { it.maskCount }
	countInfo[18] = doc.renderOrderGroups.size
	countInfo[19] = doc.renderOrderGroups.sumOf { it.children.size }
	countInfo[20] = doc.glues.size
	countInfo[21] = 2 * doc.glues.sumOf { it.pairs.size }
	countInfo[22] = doc.glues.sumOf { it.intensityKeyforms.size }

	/**
	 * Writes CountInfo field [fieldIndex] when the version's block is wide enough.
	 *
	 * @param Int fieldIndex The CountInfo word index.
	 * @param Int value      The field value.
	 */
	fun putField(fieldIndex: Int, value: Int) {
		if (fieldIndex < fieldCount) {
			countInfo[fieldIndex] = value
		}
	}
	val offscreenKeyformTotal = doc.offscreens.sumOf { it.keyforms.size }
	// 23/24: the color tables' actual row count - the moc-6 offscreen keyform prefix, the base
	// keyform rows, and the blend delta rows only when the bake carries the delta region.  A
	// 4.2-era bake counts base + prefix here even though fields 7-9 still include the delta
	// rows for the geometry/opacity tables (corpus: Azxiana.moc3, ci23 13957 vs ci7+8+9 13997).
	val colorRowTotal =
		if (blendLayout != null && !context.hasColorDeltaRows) {
			warps.sumOf { it.keyforms.size } + rotations.sumOf { it.keyforms.size } +
				doc.artMeshes.sumOf { it.keyforms.size } + offscreenKeyformTotal
		} else {
			countInfo[7] + countInfo[8] + countInfo[9] + offscreenKeyformTotal
		}
	if (blendLayout != null || doc.offscreens.isNotEmpty()) {
		putField(23, colorRowTotal)
		putField(24, colorRowTotal)
	}
	if (blendLayout != null) {
		putField(25, blendLayout.bindingKeys.size)
		putField(26, blendLayout.recordsInFileOrder.size)
		putField(27, blendLayout.warpRecords.map { it.targetIndex }.distinct().size)
		putField(28, blendLayout.meshRecords.map { it.targetIndex }.distinct().size)
		putField(29, blendLayout.recordsInFileOrder.sumOf { it.limits.size })
		putField(30, blendLayout.pool.size)
		putField(31, blendLayout.pool.sumOf { it.keyPositions.size })
		putField(32, blendLayout.partRecords.map { it.targetIndex }.distinct().size)
		putField(33, blendLayout.rotationRecords.map { it.targetIndex }.distinct().size)
		// 34: glue blend shapes - corpus-empty, unmodeled (stays zero).
	}
	putField(35, doc.offscreens.size)
	putField(36, offscreenKeyformTotal)
	return intList(countInfo.toList())
}

/**
 * Encodes [values] as a packed little-endian `i32[]`.
 *
 * @param List<Int> values The integers to write.
 * @return ByteArray The packed bytes.
 */
private fun intList(values: List<Int>): ByteArray {
	val writer = LittleEndianWriter(values.size * 4)
	values.forEach(writer::writeInt32)
	return writer.toByteArray()
}