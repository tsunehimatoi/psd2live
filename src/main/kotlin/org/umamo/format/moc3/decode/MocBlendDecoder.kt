package org.umamo.format.moc3.decode

import org.umamo.format.moc3.moc.MocSections
import org.umamo.format.moc3.moc.Section
import org.umamo.format.moc3.model.ArtMeshKeyform
import org.umamo.format.moc3.model.BlendShape
import org.umamo.format.moc3.model.BlendShapeKeyform
import org.umamo.format.moc3.model.BlendShapeLimit
import org.umamo.format.moc3.model.BlendShapeTarget
import org.umamo.format.moc3.model.Part
import org.umamo.format.moc3.model.Rgb
import org.umamo.format.moc3.model.RotationKeyform
import org.umamo.format.moc3.model.WarpKeyform

/**
 * The per-object payload sizes and row anchor a blend-shape record's delta rows need, over the
 * shared [KeyformValueTables]. Bundled so [BlendShapeDecoder] can lift the per-key delta payloads
 * without a dozen loose parameters.
 */
internal class BlendDeltaTables(
	val keyformValues: KeyformValueTables,
	val colorTables: ColorTables,
	val warpControlPointCounts: IntArray,
	val drawableVertexCounts: IntArray,
	/**
	 * First color-table row of the blend delta region: the moc-6 offscreen keyform prefix plus
	 * every object's base keyform rows.  Computed from CONTENT, not from the table length - the
	 * raw element region is 64-byte zero-padded, so length-based anchoring drifts by the pad.
	 */
	val colorDeltaRowStart: Int,
)

/**
 * The deduplicated blend-weight limit pool (MOC3 v4+ §5.6 sections 123/124 + 131-136).
 *
 * A record ranges into SUB_INDEX, whose entries name a shared pool of (parameter, keys, weights)
 * curves; records commonly point at the same pool entry, so the pool is stored once and expanded
 * per record here.  Absent tables decode as no limits, like any absent section.
 */
internal class BlendLimitPool(sections: MocSections) {
	private val recordSubstart = sections.intArray(Section.BLENDSHAPE_RECORD_SUBSTART)
	private val recordCornerCount = sections.intArray(Section.BLENDSHAPE_RECORD_CORNER_COUNT)
	private val hasSubTables = sections.isPresent(Section.BLENDSHAPE_SUB_INDEX)
	private val subIndex = if (hasSubTables) sections.intArray(Section.BLENDSHAPE_SUB_INDEX) else IntArray(0)
	private val subParameter = if (hasSubTables) sections.intArray(Section.BLENDSHAPE_SUB_PARAMETER) else IntArray(0)
	private val subKeyOffset = if (hasSubTables) sections.intArray(Section.BLENDSHAPE_SUB_KEY_OFFSET) else IntArray(0)
	private val subKeyCount = if (hasSubTables) sections.intArray(Section.BLENDSHAPE_SUB_KEY_COUNT) else IntArray(0)
	private val subKeys = if (hasSubTables) sections.floatArray(Section.BLENDSHAPE_SUB_KEYS) else FloatArray(0)
	private val subWeights = if (hasSubTables) sections.floatArray(Section.BLENDSHAPE_SUB_WEIGHT_VALUES) else FloatArray(0)

	/**
	 * Expands one record's sub-binding refs into its limit curves (empty when uncapped).
	 *
	 * @param Int recordIndex The record's index in the record tables.
	 * @return List<BlendShapeLimit> The record's limits, pool entries expanded per record.
	 */
	fun limitsFor(recordIndex: Int): List<BlendShapeLimit> {
		if (!hasSubTables || recordIndex >= recordCornerCount.size) {
			return emptyList()
		}
		val cornerCount = recordCornerCount[recordIndex]
		if (cornerCount == 0) {
			return emptyList()
		}
		val cornerStart = recordSubstart[recordIndex]
		return (cornerStart until cornerStart + cornerCount).map { cornerIndex ->
			val subBinding = subIndex[cornerIndex]
			val keyOffset = subKeyOffset[subBinding]
			val keyCount = subKeyCount[subBinding]
			BlendShapeLimit(
				parameterIndex = subParameter[subBinding],
				keyPositions = subKeys.copyOfRange(keyOffset, keyOffset + keyCount),
				weights = subWeights.copyOfRange(keyOffset, keyOffset + keyCount),
			)
		}
	}
}

/**
 * Per-record addressing into the color tables' blend-delta region (MOC3 §5.6 sections 108-113).
 *
 * The delta region follows the base rows (and, on MOC3 v6, the offscreen keyform prefix), holding
 * one row per (record, key) for warp, mesh, and rotation records in global record order - part
 * records own no color rows.  It anchors at the content-derived base-row total
 * ([BlendDeltaTables.colorDeltaRowStart]); anchoring at table length minus the delta total drifts
 * by the element region's 64-byte zero padding (2 rows on Model A, 10 on Model C - caught by
 * their authored color morphs).
 *
 * The region is also a later format addition, so presence is probed rather than assumed: a
 * 4.2-era bake with blend shapes carries the base rows only (corpus: Azxiana.moc3, V42 - CountInfo
 * 23/24 there count base + prefix rows while fields 7-9 still include the deltas).  When the
 * tables do not cover the read extent, every record resolves to the absent sentinel and the deltas
 * decode as null colors.
 */
internal class BlendColorDeltas(
	sections: MocSections,
	recordCount: Int,
	recordBinding: IntArray,
	bindingKeyCount: IntArray,
	deltaTables: BlendDeltaTables,
) {
	private val colorTables: ColorTables = deltaTables.colorTables

	/** Each record's first delta row, or -1 when it owns none (part-owned, or the region absent). */
	private val recordColorRow: IntArray

	init {
		val partOwnedRecord = partOwnedRecords(sections, recordCount)
		val colorReadRecord = colorReadRecords(sections, recordCount)
		// Measure the read extent over exactly the rows that will be dereferenced: the record
		// tables carry a few TRAILING records referenced by no group on every blend corpus model,
		// and the stored region ends at the referenced records' rows (modelA: table 1808 = read
		// extent 1806 + 2 rows padding; modelC: 2208 = 2198 + 10).
		var requiredRowEnd = deltaTables.colorDeltaRowStart
		var rowProbeCursor = deltaTables.colorDeltaRowStart
		for (recordIndex in 0 until recordCount) {
			if (!partOwnedRecord[recordIndex]) {
				val rowEnd = rowProbeCursor + bindingKeyCount[recordBinding[recordIndex]]
				if (colorReadRecord[recordIndex] && rowEnd > requiredRowEnd) {
					requiredRowEnd = rowEnd
				}
				rowProbeCursor = rowEnd
			}
		}
		val rows = IntArray(recordCount) { -1 }
		if (colorTables.isPresent && requiredRowEnd <= colorTables.rowCount) {
			var colorRowCursor = deltaTables.colorDeltaRowStart
			for (recordIndex in 0 until recordCount) {
				if (!partOwnedRecord[recordIndex]) {
					rows[recordIndex] = colorRowCursor
					colorRowCursor += bindingKeyCount[recordBinding[recordIndex]]
				}
			}
		}
		recordColorRow = rows
	}

	/**
	 * Marks the records owned by a part object group, which carry no color rows.
	 *
	 * @param MocSections sections    The model's typed sections.
	 * @param Int         recordCount Total blend-shape records.
	 * @return BooleanArray One flag per record.
	 */
	private fun partOwnedRecords(sections: MocSections, recordCount: Int): BooleanArray {
		val owned = BooleanArray(recordCount)
		if (!sections.isPresent(Section.BLENDSHAPE_PART_OBJECT)) {
			return owned
		}
		val partRecordStarts = sections.intArray(Section.BLENDSHAPE_PART_RECORD_START)
		val partRecordCounts = sections.intArray(Section.BLENDSHAPE_PART_RECORD_COUNT)
		for (groupIndex in partRecordStarts.indices) {
			val partRecordEnd = partRecordStarts[groupIndex] + partRecordCounts[groupIndex]
			for (recordIndex in partRecordStarts[groupIndex] until partRecordEnd) {
				owned[recordIndex] = true
			}
		}
		return owned
	}

	/**
	 * Marks the records a warp, mesh, or rotation object group references - the only ones whose
	 * color rows are ever dereferenced.
	 *
	 * @param MocSections sections    The model's typed sections.
	 * @param Int         recordCount Total blend-shape records.
	 * @return BooleanArray One flag per record.
	 */
	private fun colorReadRecords(sections: MocSections, recordCount: Int): BooleanArray {
		val referenced = BooleanArray(recordCount)
		val groups =
			listOf(
				Triple(
					Section.BLENDSHAPE_WARP_OBJECT,
					Section.BLENDSHAPE_WARP_RECORD_START,
					Section.BLENDSHAPE_WARP_RECORD_COUNT,
				),
				Triple(
					Section.BLENDSHAPE_MESH_OBJECT,
					Section.BLENDSHAPE_MESH_RECORD_START,
					Section.BLENDSHAPE_MESH_RECORD_COUNT,
				),
				Triple(
					Section.BLENDSHAPE_ROTATION_OBJECT,
					Section.BLENDSHAPE_ROTATION_RECORD_START,
					Section.BLENDSHAPE_ROTATION_RECORD_COUNT,
				),
			)
		for ((objectSection, startSection, countSection) in groups) {
			if (!sections.isPresent(objectSection)) {
				continue
			}
			val recordStarts = sections.intArray(startSection)
			val recordCounts = sections.intArray(countSection)
			for (groupIndex in recordStarts.indices) {
				for (recordIndex in recordStarts[groupIndex] until recordStarts[groupIndex] + recordCounts[groupIndex]) {
					if (recordIndex in 0 until recordCount) {
						referenced[recordIndex] = true
					}
				}
			}
		}
		return referenced
	}

	/**
	 * Multiply-color delta at key [keyIndex] of record [recordIndex].
	 *
	 * @param Int recordIndex The record's index in the record tables.
	 * @param Int keyIndex    The key's index within the record's binding.
	 * @return Rgb? The multiply-color delta row, or null when the record owns no color rows.
	 */
	fun multiplyDelta(recordIndex: Int, keyIndex: Int): Rgb? {
		val colorRow = recordColorRow[recordIndex]
		// Guarded here rather than folded into the row accessor: a negative row is the "no delta
		// rows" sentinel, and offsetting it by keyIndex would land on a real row.
		return if (colorRow < 0) {
			null
		} else {
			colorTables.multiplyAtRow(colorRow + keyIndex)
		}
	}

	/**
	 * Screen-color delta at key [keyIndex] of record [recordIndex].
	 *
	 * @param Int recordIndex The record's index in the record tables.
	 * @param Int keyIndex    The key's index within the record's binding.
	 * @return Rgb? The screen-color delta row, or null when the record owns no color rows.
	 */
	fun screenDelta(recordIndex: Int, keyIndex: Int): Rgb? {
		val colorRow = recordColorRow[recordIndex]
		// Same sentinel guard as multiplyDelta above.
		return if (colorRow < 0) {
			null
		} else {
			colorTables.screenAtRow(colorRow + keyIndex)
		}
	}
}

/**
 * Decodes the blend-shape records: the binding structure, each record's blend-weight limits, and
 * the typed per-key delta payloads.
 *
 * The records live in one flat table addressed by per-kind object groups (MOC3 v4+ §5.6 for
 * meshes/warps, v5+ for rotations and parts).  A group names its objects by KIND-LOCAL index,
 * which is what sizes a delta payload, while the emitted [BlendShape] carries the deformer or
 * drawable index instead - hence both travel together through the decode.
 */
internal class BlendShapeDecoder(
	private val sections: MocSections,
	parameterCount: Int,
	private val keyPositions: FloatArray,
	private val deltaTables: BlendDeltaTables,
) {
	private val bindingKeyOffset = sections.intArray(Section.BLENDSHAPE_BINDING_KEY_OFFSET)
	private val bindingKeyCount = sections.intArray(Section.BLENDSHAPE_BINDING_KEY_COUNT)
	private val bindingNeutral = sections.intArray(Section.BLENDSHAPE_BINDING_NEUTRAL)
	private val recordBinding = sections.intArray(Section.BLENDSHAPE_RECORD_BINDING)
	private val recordBase = sections.intArray(Section.BLENDSHAPE_RECORD_BASE)
	private val bindingOwner = resolveBindingOwners(sections, parameterCount)
	private val limitPool = BlendLimitPool(sections)
	private val colorDeltas =
		BlendColorDeltas(sections, recordBinding.size, recordBinding, bindingKeyCount, deltaTables)

	/** The shared value tables the delta rows live in; aliased to keep the payload reads legible. */
	private val keyformValues = deltaTables.keyformValues

	/**
	 * Resolves the owning parameter of every blend-shape binding.
	 *
	 * Unlike the main keyform bindings, whose slot table is a flat concatenation, these carry
	 * explicit begin/count ranges per parameter (MOC3 §5.6).
	 *
	 * @param MocSections sections       The model's typed sections.
	 * @param Int         parameterCount Number of parameters.
	 * @return IntArray The owning parameter index per binding.
	 */
	private fun resolveBindingOwners(sections: MocSections, parameterCount: Int): IntArray {
		val parameterBegin = sections.intArray(Section.BLENDSHAPE_PARAMETER_BEGIN)
		val parameterBindingCount = sections.intArray(Section.BLENDSHAPE_PARAMETER_COUNT)
		val owners = IntArray((0 until parameterCount).sumOf { parameterBindingCount[it] })
		for (parameterIndex in 0 until parameterCount) {
			for (bindingIndex in 0 until parameterBindingCount[parameterIndex]) {
				owners[parameterBegin[parameterIndex] + bindingIndex] = parameterIndex
			}
		}
		return owners
	}

	/**
	 * Decodes every object group's records, in warp, mesh, rotation, then part order.
	 *
	 * @param List<Int> warpToDeformer     Maps a warp local index to its deformer index.
	 * @param List<Int> rotationToDeformer Maps a rotation local index to its deformer index.
	 * @return List<BlendShape> The decoded records.
	 */
	fun decodeAll(warpToDeformer: List<Int>, rotationToDeformer: List<Int>): List<BlendShape> {
		val blendShapes = ArrayList<BlendShape>()
		blendShapes +=
			groupRecords(
				Section.BLENDSHAPE_WARP_OBJECT,
				Section.BLENDSHAPE_WARP_RECORD_START,
				Section.BLENDSHAPE_WARP_RECORD_COUNT,
				BlendShapeTarget.WARP,
				warpToDeformer,
			)
		blendShapes +=
			groupRecords(
				Section.BLENDSHAPE_MESH_OBJECT,
				Section.BLENDSHAPE_MESH_RECORD_START,
				Section.BLENDSHAPE_MESH_RECORD_COUNT,
				BlendShapeTarget.ART_MESH,
				null,
			)
		blendShapes +=
			groupRecords(
				Section.BLENDSHAPE_ROTATION_OBJECT,
				Section.BLENDSHAPE_ROTATION_RECORD_START,
				Section.BLENDSHAPE_ROTATION_RECORD_COUNT,
				BlendShapeTarget.ROTATION,
				rotationToDeformer,
			)
		// MOC3 v5+ §5.6 sections 143-145: part blend shapes (the object index is a part index).
		blendShapes +=
			groupRecords(
				Section.BLENDSHAPE_PART_OBJECT,
				Section.BLENDSHAPE_PART_RECORD_START,
				Section.BLENDSHAPE_PART_RECORD_COUNT,
				BlendShapeTarget.PART,
				null,
			)
		return blendShapes
	}

	/**
	 * Decodes the records of one target kind's object group.
	 *
	 * @param Section          objectSection The per-object index section for this group.
	 * @param Section          startSection  The per-object record-start section.
	 * @param Section          countSection  The per-object record-count section.
	 * @param BlendShapeTarget target        The target kind these records deform.
	 * @param List<Int>?       toDeformer    Local-index to deformer-index map, or null when the
	 *                                       object index already names the target directly.
	 * @return List<BlendShape> This group's records (empty when the section is absent).
	 */
	private fun groupRecords(
		objectSection: Section,
		startSection: Section,
		countSection: Section,
		target: BlendShapeTarget,
		toDeformer: List<Int>?,
	): List<BlendShape> {
		if (!sections.isPresent(objectSection)) {
			return emptyList()
		}
		val objectIndices = sections.intArray(objectSection)
		val recordStarts = sections.intArray(startSection)
		val recordCounts = sections.intArray(countSection)
		val groupShapes = ArrayList<BlendShape>()
		for (groupIndex in objectIndices.indices) {
			val localObjectIndex = objectIndices[groupIndex]
			val objectIndex = toDeformer?.getOrElse(localObjectIndex) { localObjectIndex } ?: localObjectIndex
			val recordStart = recordStarts[groupIndex]
			for (recordIndex in recordStart until recordStart + recordCounts[groupIndex]) {
				groupShapes.add(blendShape(target, localObjectIndex, objectIndex, recordIndex))
			}
		}
		return groupShapes
	}

	/**
	 * Builds one record's [BlendShape], resolving its binding and lifting its delta payloads.
	 *
	 * @param BlendShapeTarget target           Which kind of object this record deforms.
	 * @param Int              localObjectIndex The target's kind-local index (sizes the payloads).
	 * @param Int              objectIndex      The deformer/drawable/part index the record targets.
	 * @param Int              recordIndex      The record's index in the record tables.
	 * @return BlendShape The decoded record.
	 */
	private fun blendShape(
		target: BlendShapeTarget,
		localObjectIndex: Int,
		objectIndex: Int,
		recordIndex: Int,
	): BlendShape {
		val bindingIndex = recordBinding[recordIndex]
		val keys =
			keyPositions.copyOfRange(
				bindingKeyOffset[bindingIndex],
				bindingKeyOffset[bindingIndex] + bindingKeyCount[bindingIndex],
			)
		return BlendShape(
			target,
			objectIndex,
			bindingOwner[bindingIndex],
			keys,
			bindingNeutral[bindingIndex],
			recordBase[recordIndex],
			limitPool.limitsFor(recordIndex),
			keyformsFor(target, localObjectIndex, recordIndex, bindingKeyCount[bindingIndex]),
		)
	}

	/**
	 * Lifts one record's per-key delta payloads out of the shared value tables, at rows
	 * `recordBase + keyIndex` (MOC3 §5.6; the same tables the base keyforms use).
	 *
	 * @param BlendShapeTarget target           The record's target kind.
	 * @param Int              localObjectIndex The target's kind-local index (warp/rotation local,
	 *                                          drawable, or part index) - sizes the payload.
	 * @param Int              recordIndex      The record's index in the record tables.
	 * @param Int              keyCount         The record's binding key count.
	 * @return List<BlendShapeKeyform> One delta payload per key, kind matching [target].
	 */
	private fun keyformsFor(
		target: BlendShapeTarget,
		localObjectIndex: Int,
		recordIndex: Int,
		keyCount: Int,
	): List<BlendShapeKeyform> =
		(0 until keyCount).map { keyIndex ->
			val deltaRow = recordBase[recordIndex] + keyIndex
			when (target) {
				BlendShapeTarget.WARP -> warpDelta(localObjectIndex, recordIndex, keyIndex, deltaRow)
				BlendShapeTarget.ART_MESH -> meshDelta(localObjectIndex, recordIndex, keyIndex, deltaRow)
				BlendShapeTarget.ROTATION -> rotationDelta(recordIndex, keyIndex, deltaRow)
				// MOC3 §5.6: part delta rows are draw-order floats in section 58.
				BlendShapeTarget.PART -> BlendShapeKeyform.Part(keyformValues.partDrawOrder[deltaRow])
			}
		}

	/**
	 * One warp control-point delta payload.
	 *
	 * @param Int localObjectIndex The warp's local index, which sizes the lattice.
	 * @param Int recordIndex      The record's index in the record tables.
	 * @param Int keyIndex         The key's index within the record's binding.
	 * @param Int deltaRow         The record's row for this key.
	 * @return BlendShapeKeyform.Warp The delta payload.
	 */
	private fun warpDelta(localObjectIndex: Int, recordIndex: Int, keyIndex: Int, deltaRow: Int): BlendShapeKeyform.Warp {
		// MOC3 §5.6: warp delta rows index packed position blocks via section 60 into 71.
		val controlPointCount = deltaTables.warpControlPointCounts[localObjectIndex]
		val positionOffset = keyformValues.warpPositionIndex[deltaRow]
		return BlendShapeKeyform.Warp(
			WarpKeyform(
				keyformValues.positionValues.copyOfRange(positionOffset, positionOffset + controlPointCount * 2),
				keyformValues.warpOpacity[deltaRow],
				colorDeltas.multiplyDelta(recordIndex, keyIndex),
				colorDeltas.screenDelta(recordIndex, keyIndex),
			),
		)
	}

	/**
	 * One art-mesh vertex delta payload.
	 *
	 * @param Int localObjectIndex The drawable index, which sizes the vertex block.
	 * @param Int recordIndex      The record's index in the record tables.
	 * @param Int keyIndex         The key's index within the record's binding.
	 * @param Int deltaRow         The record's row for this key.
	 * @return BlendShapeKeyform.Mesh The delta payload.
	 */
	private fun meshDelta(localObjectIndex: Int, recordIndex: Int, keyIndex: Int, deltaRow: Int): BlendShapeKeyform.Mesh {
		// MOC3 §5.6: mesh delta rows index packed position blocks via section 70 into 71.
		val vertexCount = deltaTables.drawableVertexCounts[localObjectIndex]
		val positionOffset = keyformValues.positionIndex[deltaRow]
		return BlendShapeKeyform.Mesh(
			ArtMeshKeyform(
				keyformValues.positionValues.copyOfRange(positionOffset, positionOffset + vertexCount * 2),
				keyformValues.artMeshOpacity[deltaRow],
				keyformValues.artMeshDrawOrder[deltaRow],
				colorDeltas.multiplyDelta(recordIndex, keyIndex),
				colorDeltas.screenDelta(recordIndex, keyIndex),
			),
		)
	}

	/**
	 * One rotation affine delta payload.
	 *
	 * @param Int recordIndex The record's index in the record tables.
	 * @param Int keyIndex    The key's index within the record's binding.
	 * @param Int deltaRow    The record's row for this key.
	 * @return BlendShapeKeyform.Rotation The delta payload.
	 */
	private fun rotationDelta(recordIndex: Int, keyIndex: Int, deltaRow: Int): BlendShapeKeyform.Rotation =
		// MOC3 §5.6: rotation delta rows sit directly in the affine tables 61-67.
		BlendShapeKeyform.Rotation(
			RotationKeyform(
				keyformValues.rotationOriginX[deltaRow],
				keyformValues.rotationOriginY[deltaRow],
				keyformValues.rotationAngle[deltaRow],
				keyformValues.rotationScale[deltaRow],
				keyformValues.rotationReflectX[deltaRow] != 0,
				keyformValues.rotationReflectY[deltaRow] != 0,
				keyformValues.rotationOpacity[deltaRow],
				colorDeltas.multiplyDelta(recordIndex, keyIndex),
				colorDeltas.screenDelta(recordIndex, keyIndex),
			),
		)
}