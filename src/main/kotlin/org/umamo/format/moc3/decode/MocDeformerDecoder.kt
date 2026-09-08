package org.umamo.format.moc3.decode

import org.umamo.format.moc3.moc.MocSections
import org.umamo.format.moc3.moc.Section
import org.umamo.format.moc3.model.Deformer
import org.umamo.format.moc3.model.RotationDeformer
import org.umamo.format.moc3.model.RotationKeyform
import org.umamo.format.moc3.model.WarpDeformer
import org.umamo.format.moc3.model.WarpKeyform

/** The decoded deformer block, plus the index maps and payload sizes the blend path needs. */
internal class DecodedDeformers(
	val deformers: List<Deformer>,
	/** Warp local index -> deformer index; blend-shape warp records are stored against the local one. */
	val warpToDeformer: List<Int>,
	/** Rotation local index -> deformer index, for the same reason. */
	val rotationToDeformer: List<Int>,
	/** Control points per warp, which sizes both a base warp keyform and a warp delta payload. */
	val warpControlPointCounts: IntArray,
)

/**
 * Decodes the deformer block into typed [WarpDeformer]s and [RotationDeformer]s.
 *
 * Both kinds share one deformer index space and one common head (MOC3 §5.6 sections 11-15), but
 * each kind's own tables are densely packed over only its own members.  So the walk advances the
 * shared index while carrying a separate local cursor per kind, and every per-type read is by that
 * local index, never the deformer index.  Those local indices are also how blend-shape records
 * name their target, which is what [DecodedDeformers] carries the maps for.
 */
internal class DeformerDecoder(
	sections: MocSections,
	private val bindings: MocBindingResolver,
	private val colorTables: ColorTables,
	private val keyformValues: KeyformValueTables,
) {
	private val deformerType = sections.intArray(Section.DEFORMER_TYPE)
	private val deformerParent = sections.intArray(Section.DEFORMER_PARENT)

	// The block's common head (MOC3 §5.6 S11-S15).
	private val deformerId = sections.idArray(Section.DEFORMER_ID)

	// Section 12 is the same binding that the warp/rotation sections 19/25 have.
	// Warp and Rotation have their own keyform binding which duplicates the deformer binding.
	// We don't necessarily need it, but we read and write it since there is a chance it might cause a MOC3
	// validation error in the runtime.
	private val deformerKeyformBinding = sections.intArray(Section.DEFORMER_KEYFORM_BINDING)
	private val deformerIsVisible = sections.intArray(Section.DEFORMER_IS_VISIBLE)
	private val deformerIsEnabled = sections.intArray(Section.DEFORMER_IS_ENABLED)
	private val deformerParentPart = sections.intArray(Section.DEFORMER_PARENT_PART)

	private val warpKeyformBinding = sections.intArray(Section.WARP_KEYFORM_BINDING)
	private val warpKeyformBase = sections.intArray(Section.WARP_KEYFORM_BASE)
	private val warpRows = sections.intArray(Section.WARP_ROWS)
	private val warpColumns = sections.intArray(Section.WARP_COLUMNS)
	private val warpMode = if (sections.isPresent(Section.WARP_MODE)) sections.intArray(Section.WARP_MODE) else null
	private val warpColorBase =
		if (sections.isPresent(Section.WARP_COLOR_BASE)) sections.intArray(Section.WARP_COLOR_BASE) else null

	private val rotationKeyformBinding = sections.intArray(Section.ROTATION_KEYFORM_BINDING)
	private val rotationKeyformBase = sections.intArray(Section.ROTATION_KEYFORM_BASE)
	private val rotationBaseAngle = sections.floatArray(Section.ROTATION_BASE_ANGLE)
	private val rotationColorBase =
		if (sections.isPresent(Section.ROTATION_COLOR_BASE)) sections.intArray(Section.ROTATION_COLOR_BASE) else null

	/** Control points per warp: an (rows + 1) x (columns + 1) lattice of them. */
	private val warpControlPointCounts =
		IntArray(warpRows.size) { warpLocalIndex ->
			(warpRows[warpLocalIndex] + 1) * (warpColumns[warpLocalIndex] + 1)
		}

	/**
	 * Decodes every deformer, dispatching on the section 11 type column.
	 *
	 * @param Int deformerCount The model's deformer count.
	 * @return DecodedDeformers The deformers in index order, with the per-kind index maps.
	 */
	fun decodeAll(deformerCount: Int): DecodedDeformers {
		var nextWarpLocal = 0
		var nextRotationLocal = 0
		val warpToDeformer = ArrayList<Int>()
		val rotationToDeformer = ArrayList<Int>()
		val deformers = ArrayList<Deformer>(deformerCount)
		for (deformerIndex in 0 until deformerCount) {
			if (deformerType[deformerIndex] == 0) {
				warpToDeformer.add(deformerIndex)
				deformers.add(warpDeformer(deformerIndex, nextWarpLocal++))
			} else {
				rotationToDeformer.add(deformerIndex)
				deformers.add(rotationDeformer(deformerIndex, nextRotationLocal++))
			}
		}
		return DecodedDeformers(deformers, warpToDeformer, rotationToDeformer, warpControlPointCounts)
	}

	/**
	 * Builds one warp deformer and its FFD lattice keyforms.
	 *
	 * @param Int deformerIndex  Its index in the shared deformer space (the common head's row).
	 * @param Int warpLocalIndex Its index among warps (every per-warp table's row).
	 * @return WarpDeformer The decoded deformer.
	 */
	private fun warpDeformer(deformerIndex: Int, warpLocalIndex: Int): WarpDeformer {
		val keyformBinding = warpKeyformBinding[warpLocalIndex]
		val keyformBase = warpKeyformBase[warpLocalIndex]
		val controlPointCount = warpControlPointCounts[warpLocalIndex]
		val keyforms =
			(0 until bindings.binding(keyformBinding).gridSize).map { gridIndex ->
				val positionOffset = keyformValues.warpPositionIndex[keyformBase + gridIndex]
				WarpKeyform(
					keyformValues.positionValues.copyOfRange(positionOffset, positionOffset + controlPointCount * 2),
					keyformValues.warpOpacity[keyformBase + gridIndex],
					colorTables.multiplyForKeyform(warpColorBase?.get(warpLocalIndex), gridIndex),
					colorTables.screenForKeyform(warpColorBase?.get(warpLocalIndex), gridIndex),
				)
			}
		return WarpDeformer(
			id = deformerId.getOrElse(deformerIndex) { "" },
			keyformBindingIndex = keyformBinding,
			isVisible = deformerIsVisible.getOrElse(deformerIndex) { 1 } != 0,
			isEnabled = deformerIsEnabled.getOrElse(deformerIndex) { 1 } != 0,
			parentPartIndex = deformerParentPart.getOrElse(deformerIndex) { -1 },
			parentDeformerIndex = deformerParent[deformerIndex],
			rows = warpRows[warpLocalIndex],
			columns = warpColumns[warpLocalIndex],
			mode = warpMode?.get(warpLocalIndex) ?: 0,
			keyforms = keyforms,
		)
	}

	/**
	 * Builds one rotation deformer and its pivot-transform keyforms.
	 *
	 * @param Int deformerIndex      Its index in the shared deformer space (the common head's row).
	 * @param Int rotationLocalIndex Its index among rotations (every per-rotation table's row).
	 * @return RotationDeformer The decoded deformer.
	 */
	private fun rotationDeformer(deformerIndex: Int, rotationLocalIndex: Int): RotationDeformer {
		val keyformBinding = rotationKeyformBinding[rotationLocalIndex]
		val keyformBase = rotationKeyformBase[rotationLocalIndex]
		val keyforms =
			(0 until bindings.binding(keyformBinding).gridSize).map { gridIndex ->
				RotationKeyform(
					keyformValues.rotationOriginX[keyformBase + gridIndex],
					keyformValues.rotationOriginY[keyformBase + gridIndex],
					keyformValues.rotationAngle[keyformBase + gridIndex],
					keyformValues.rotationScale[keyformBase + gridIndex],
					keyformValues.rotationReflectX[keyformBase + gridIndex] != 0,
					keyformValues.rotationReflectY[keyformBase + gridIndex] != 0,
					keyformValues.rotationOpacity[keyformBase + gridIndex],
					colorTables.multiplyForKeyform(rotationColorBase?.get(rotationLocalIndex), gridIndex),
					colorTables.screenForKeyform(rotationColorBase?.get(rotationLocalIndex), gridIndex),
				)
			}
		return RotationDeformer(
			id = deformerId.getOrElse(deformerIndex) { "" },
			keyformBindingIndex = keyformBinding,
			isVisible = deformerIsVisible.getOrElse(deformerIndex) { 1 } != 0,
			isEnabled = deformerIsEnabled.getOrElse(deformerIndex) { 1 } != 0,
			parentPartIndex = deformerParentPart.getOrElse(deformerIndex) { -1 },
			parentDeformerIndex = deformerParent[deformerIndex],
			baseAngle = rotationBaseAngle[rotationLocalIndex],
			keyforms = keyforms,
		)
	}
}