package org.umamo.format.moc3.encode

import org.umamo.format.moc3.moc.Section
import org.umamo.format.moc3.model.BlendShape
import org.umamo.format.moc3.model.BlendShapeTarget

/**
 * Synthesizes the blend-shape record/binding/limit sections from [context] (MOC3 v4+ §5.6:
 * 115-136 plus the per-kind object trios 125-130/143-148).  Bindings and the limit sub-binding
 * pool are re-deduplicated from the per-record expansions (the decoder expands the pool per
 * record); RECORD_BASE is recomputed as the per-kind running cursor after the base keyforms,
 * which reproduces the decoded values for an unedited document.
 *
 * @param MocLoweringContext context The shared lowering derivations.
 * @return Map Section index → element-region bytes.  A document with no blend records still yields
 *             the per-parameter binding ranges 115/116, which a v4+ file carries either way; only a
 *             document with no parameters at all yields an empty map.
 */
internal fun blendShapeSections(context: MocLoweringContext): Map<Int, ByteArray> {
	val doc = context.doc
	val sink = SectionSink(doc.version)
	val layout = context.blendLayout

	// 115/116: per-parameter binding ranges.  A binding-less parameter stores begin = 0, NOT the
	// running cumulative (probed on Model A/B/C - unlike SUBSTART's carry convention), unless the
	// parameter is itself blend-shape-typed, which stores -1 instead.
	//
	// Written from the layout on EVERY document, blend records or not.  A v4+ blend-free file still
	// carries both columns, and an empty layout produces exactly what that file holds, so one path
	// covers both kinds of document - the alternative is a second copy of the -1/0 filler rule that
	// has to be kept in agreement with this one by hand.  Below v4 the sink drops both sections.
	//
	// A document with NO parameters writes neither: the columns are per-parameter, and emitting
	// them empty would override a section `MocEncoder.bake` would otherwise carry from its reference.
	if (doc.parameters.isNotEmpty()) {
		sink.putInts(Section.BLENDSHAPE_PARAMETER_BEGIN, layout.parameterBegin.toList())
		sink.putInts(Section.BLENDSHAPE_PARAMETER_COUNT, layout.parameterBindingCount.toList())
	}
	if (!context.hasBlendShapes) {
		return sink.toMap()
	}

	// 117-119: per-binding key runs (absolute float offsets into KEY_POSITIONS' second region,
	// after the main-grid keys) + neutral indices.
	val mainGridKeyTotal = context.mainGridKeyTotal
	val bindingKeyOffsets = ArrayList<Int>()
	var bindingKeyCursor = mainGridKeyTotal
	for (bindingKeys in layout.bindingKeys) {
		bindingKeyOffsets.add(bindingKeyCursor)
		bindingKeyCursor += bindingKeys.size
	}
	sink.putInts(Section.BLENDSHAPE_BINDING_KEY_OFFSET, bindingKeyOffsets)
	sink.putInts(Section.BLENDSHAPE_BINDING_KEY_COUNT, layout.bindingKeys.map { it.size })
	sink.putInts(Section.BLENDSHAPE_BINDING_NEUTRAL, layout.bindingNeutral.toList())

	// 120-122: per-record binding refs, value-table bases (per-kind running cursors after the
	// base keyforms), and the redundant key-count copy.
	sink.putInts(Section.BLENDSHAPE_RECORD_BINDING, layout.bindingIndexOfRecord.toList())
	val warps = context.warps
	val rotations = context.rotations
	val kindCursors =
		hashMapOf(
			BlendShapeTarget.WARP to warps.sumOf { it.keyforms.size },
			BlendShapeTarget.ART_MESH to doc.artMeshes.sumOf { it.keyforms.size },
			BlendShapeTarget.ROTATION to rotations.sumOf { it.keyforms.size },
			BlendShapeTarget.PART to doc.parts.sumOf { it.drawOrderKeyforms.size },
		)
	val recordBases = ArrayList<Int>()
	for (record in layout.recordsInFileOrder) {
		val cursor = kindCursors.getValue(record.target)
		recordBases.add(cursor)
		kindCursors[record.target] = cursor + record.keyPositions.size
	}
	sink.putInts(Section.BLENDSHAPE_RECORD_BASE, recordBases)
	sink.putInts(Section.BLENDSHAPE_RECORD_KEY_COUNT, layout.recordsInFileOrder.map { it.keyPositions.size })

	// 123/124 + 131-136: limit corner refs into the deduplicated sub-binding pool.  SUBSTART is
	// running-cumulative (cornerless records carry the running value).
	val substarts = ArrayList<Int>()
	val cornerCounts = ArrayList<Int>()
	val subIndices = ArrayList<Int>()
	var cornerCursor = 0
	for (record in layout.recordsInFileOrder) {
		substarts.add(cornerCursor)
		cornerCounts.add(record.limits.size)
		cornerCursor += record.limits.size
		for (limit in record.limits) {
			subIndices.add(layout.poolIndexOf(limit))
		}
	}
	sink.putInts(Section.BLENDSHAPE_RECORD_SUBSTART, substarts)
	sink.putInts(Section.BLENDSHAPE_RECORD_CORNER_COUNT, cornerCounts)
	if (layout.pool.isNotEmpty()) {
		val subKeyOffsets = ArrayList<Int>()
		val subKeyCounts = ArrayList<Int>()
		val subKeys = ArrayList<Float>()
		val subWeights = ArrayList<Float>()
		for (poolEntry in layout.pool) {
			subKeyOffsets.add(subKeys.size)
			subKeyCounts.add(poolEntry.keyPositions.size)
			poolEntry.keyPositions.forEach { subKeys.add(it) }
			poolEntry.weights.forEach { subWeights.add(it) }
		}
		sink.putInts(Section.BLENDSHAPE_SUB_INDEX, subIndices)
		sink.putInts(Section.BLENDSHAPE_SUB_PARAMETER, layout.pool.map { it.parameterIndex })
		sink.putInts(Section.BLENDSHAPE_SUB_KEY_OFFSET, subKeyOffsets)
		sink.putInts(Section.BLENDSHAPE_SUB_KEY_COUNT, subKeyCounts)
		sink.putFloats(Section.BLENDSHAPE_SUB_KEYS, subKeys)
		sink.putFloats(Section.BLENDSHAPE_SUB_WEIGHT_VALUES, subWeights)
	}

	// Per-kind object trios: objects owning records (kind-local indices ascending), with each
	// object's record range as GLOBAL record indices.
	fun putTrio(
		records: List<BlendShape>,
		recordOffset: Int,
		localIndexOf: (Int) -> Int,
		objectSection: Section,
		startSection: Section,
		countSection: Section,
	) {
		if (records.isEmpty()) {
			return
		}
		val objectIndices = ArrayList<Int>()
		val recordStarts = ArrayList<Int>()
		val recordCounts = ArrayList<Int>()
		for ((recordOrdinal, record) in records.withIndex()) {
			val localObjectIndex = localIndexOf(record.targetIndex)
			if (objectIndices.isEmpty() || objectIndices.last() != localObjectIndex) {
				objectIndices.add(localObjectIndex)
				recordStarts.add(recordOffset + recordOrdinal)
				recordCounts.add(1)
			} else {
				recordCounts[recordCounts.size - 1] = recordCounts.last() + 1
			}
		}
		sink.putInts(objectSection, objectIndices)
		sink.putInts(startSection, recordStarts)
		sink.putInts(countSection, recordCounts)
	}

	val meshRecordOffset = layout.warpRecords.size
	val partRecordOffset = meshRecordOffset + layout.meshRecords.size
	val rotationRecordOffset = partRecordOffset + layout.partRecords.size
	putTrio(
		layout.warpRecords,
		0,
		{ deformerIndex -> layout.warpLocalByDeformer[deformerIndex] ?: deformerIndex },
		Section.BLENDSHAPE_WARP_OBJECT,
		Section.BLENDSHAPE_WARP_RECORD_START,
		Section.BLENDSHAPE_WARP_RECORD_COUNT,
	)
	putTrio(
		layout.meshRecords,
		meshRecordOffset,
		{ drawableIndex -> drawableIndex },
		Section.BLENDSHAPE_MESH_OBJECT,
		Section.BLENDSHAPE_MESH_RECORD_START,
		Section.BLENDSHAPE_MESH_RECORD_COUNT,
	)
	putTrio(
		layout.partRecords,
		partRecordOffset,
		{ partIndex -> partIndex },
		Section.BLENDSHAPE_PART_OBJECT,
		Section.BLENDSHAPE_PART_RECORD_START,
		Section.BLENDSHAPE_PART_RECORD_COUNT,
	)
	putTrio(
		layout.rotationRecords,
		rotationRecordOffset,
		{ deformerIndex -> layout.rotationLocalByDeformer[deformerIndex] ?: deformerIndex },
		Section.BLENDSHAPE_ROTATION_OBJECT,
		Section.BLENDSHAPE_ROTATION_RECORD_START,
		Section.BLENDSHAPE_ROTATION_RECORD_COUNT,
	)
	return sink.toMap()
}