package org.umamo.format.moc3.decode

import org.umamo.format.moc3.MocDocument
import org.umamo.format.moc3.moc.MocModel
import org.umamo.format.moc3.moc.MocSections
import org.umamo.format.moc3.moc.Section
import org.umamo.format.moc3.moc.Sections
import org.umamo.format.moc3.model.BlendShape
import org.umamo.format.moc3.model.Glue
import org.umamo.format.moc3.model.GlueVertexPair
import org.umamo.format.moc3.model.Offscreen
import org.umamo.format.moc3.model.OffscreenKeyform
import org.umamo.format.moc3.model.Part
import org.umamo.format.moc3.model.RenderOrderChild
import org.umamo.format.moc3.model.RenderOrderGroup
import org.umamo.format.moc3.model.RotationDeformer
import org.umamo.format.moc3.model.WarpDeformer

/**
 * Resolves a parsed [MocModel] into the semantic [MocDocument]: the keyform-binding grid and each
 * object's per-keyform values (vertex positions, opacity, draw-order, color, deformer transforms).
 *
 * Reads the typed Layer-1 sections and follows the base/index tables - it does not evaluate the
 * model (no interpolation/cascade). Blend shapes (MOC3 v4+) and offscreens (MOC3 v6) are assembled
 * too.  Every section index is modeled in [Section], so nothing is left to raw access.
 *
 * @see <a href="https://docs.umamo.org/format/MOC3.md">MOC3.md §5.6</a>
 */
public object MocDecoder {
	/**
	 * Decodes [model] into a [MocDocument].
	 *
	 * @param MocModel model A parsed `.moc3` container.
	 * @return MocDocument The semantic model.
	 */
	public fun decode(model: MocModel): MocDocument {
		val sections = model.sections
		val deformerCount = model.deformerCount
		val drawables = model.drawables()

		val bindings = MocBindingResolver(sections)
		bindings.registerStoredBindings(model.countInfo.getOrElse(Sections.CI_KEYFORM_BINDINGS) { 0 })

		// ---- value tables ----
		val colorTables = ColorTables(sections)
		val keyformValues = KeyformValueTables(sections)

		// PARTS
		val partKeyformBinding = sections.intArray(Section.PART_KEYFORM_BINDING)
		val partKeyformBase = sections.intArray(Section.PART_KEYFORM_BASE)
		// s7/s8 are a pair whose split is unpinned (both are 1 corpus-wide); take s7 and default to
		// visible when a stripped file omits it.
		val partVisible = sections.intArray(Section.PART_VISIBLE_ARTMESHES)
		val partList =
			model.parts().mapIndexed { partIndex, part ->
				val keyformBinding = partKeyformBinding[partIndex] // for parts, 0 means static (no binding)
				val gridSize = bindings.staticAwareGridSize(keyformBinding)
				Part(
					part.id,
					part.parentPartIndex,
					keyformBinding,
					FloatArray(gridSize) { keyIndex -> keyformValues.partDrawOrder[partKeyformBase[partIndex] + keyIndex] },
					isVisible = partVisible.getOrElse(partIndex) { 1 } != 0,
				)
			}

		// DEFORMERS
		val decodedDeformers = DeformerDecoder(sections, bindings, colorTables, keyformValues).decodeAll(deformerCount)
		val deformerList = decodedDeformers.deformers

		// ART MESHES
		// MASK_INDEX_DATA is shared: the offscreen entries are its prefix and the drawables' masks
		// follow, so both decoders read the one table (MOC3 v6 §5.6 section 80).
		val maskData = sections.intArray(Section.MASK_INDEX_DATA)
		val artMeshList = MocArtMeshDecoder(sections, bindings, colorTables, keyformValues, maskData).decodeAll(drawables)

		// GLUES
		val glueList = decodeGlues(sections)
		// Register glue bindings in the cache like every other object kind: a glue names a binding
		// from the same shared table (MOC3.md §5.6), and without this a glue-exclusive binding would
		// be missing from MocDocument.keyformBinding (dropping the glue's parameter-driven intensity
		// downstream) AND from MocDocument.bindings (dropping its table rows from a re-bake).
		for (glue in glueList) {
			bindings.binding(glue.keyformBindingIndex)
		}

		// RENDER-ORDER GROUPS
		val groupList =
			decodeRenderOrderGroups(sections, model.countInfo.getOrElse(Sections.CI_RENDER_ORDER_GROUPS) { 0 })

		// BLEND SHAPES (MOC3 v4+)
		// MOC3 §5.6: blend delta rows share the base keyforms' value tables (appended after the
		// base rows at each record's RECORD_BASE), so the extraction needs the same tables plus
		// each target object's payload size (warp control-point count, drawable vertex count).
		val blendDeltaTables =
			BlendDeltaTables(
				keyformValues = keyformValues,
				colorTables = colorTables,
				warpControlPointCounts = decodedDeformers.warpControlPointCounts,
				drawableVertexCounts = IntArray(drawables.size) { drawableIndex -> drawables[drawableIndex].vertexCount },
				colorDeltaRowStart =
					model.countInfo.getOrElse(Sections.CI_OFFSCREEN_KEYFORMS) { 0 } +
						deformerList.sumOf { deformer ->
							when (deformer) {
								is WarpDeformer -> deformer.keyforms.size
								is RotationDeformer -> deformer.keyforms.size
							}
						} +
						artMeshList.sumOf { it.keyforms.size },
			)
		val blendShapeList =
			decodeBlendShapes(
				sections,
				model.parameterCount,
				bindings.keyPositions,
				decodedDeformers.warpToDeformer,
				decodedDeformers.rotationToDeformer,
				blendDeltaTables,
			)

		// OFFSCREENS (MOC3 v6)
		// The offscreen mask entries are the PREFIX of MASK_INDEX_DATA, addressed per offscreen by
		// s158 (the cumulative scan of s159, offset from the block start - MOC3 §5.6 section 80).
		val offscreenList =
			decodeOffscreens(
				sections,
				model.countInfo.getOrElse(Sections.CI_OFFSCREENS) { 0 },
				partList,
				colorTables,
				maskData,
			)

		// Probed last, and deliberately so: it measures the trailing region against the bindings
		// registered by every object kind above, the glue pass included.
		val keyPositionsHasParameterUnion = bindings.hasParameterUnionRegion(blendShapeList.isEmpty())

		return MocDocument(
			version = model.version,
			canvas = model.canvasInfo,
			parameters = model.parameters(),
			keyformBindings = bindings.collected,
			parts = partList,
			deformers = deformerList,
			artMeshes = artMeshList,
			glues = glueList,
			renderOrderGroups = groupList,
			blendShapes = blendShapeList,
			offscreens = offscreenList,
			keyPositionsHasParameterUnion = keyPositionsHasParameterUnion,
		)
	}

	/**
	 * Decodes the blend-shape records (MOC3 v4+ for meshes/warps, MOC3 v5+ for rotations and parts).
	 *
	 * @param MocSections      sections           The model's typed sections.
	 * @param Int              parameterCount     Number of parameters (sizes the per-parameter binding ranges).
	 * @param FloatArray       keyPositions       The shared key-position table.
	 * @param List<Int>        warpToDeformer     Maps a warp local index to its deformer index.
	 * @param List<Int>        rotationToDeformer Maps a rotation local index to its deformer index.
	 * @param BlendDeltaTables deltaTables        The shared value tables the delta rows live in.
	 * @return List<BlendShape> The decoded blend-shape records (empty when absent).
	 */
	private fun decodeBlendShapes(
		sections: MocSections,
		parameterCount: Int,
		keyPositions: FloatArray,
		warpToDeformer: List<Int>,
		rotationToDeformer: List<Int>,
		deltaTables: BlendDeltaTables,
	): List<BlendShape> {
		if (!sections.isPresent(Section.BLENDSHAPE_PARAMETER_BEGIN)) {
			return emptyList()
		}
		return BlendShapeDecoder(sections, parameterCount, keyPositions, deltaTables)
			.decodeAll(warpToDeformer, rotationToDeformer)
	}

	/**
	 * Decodes the offscreen render targets (moc 6), including their per-keyform opacity/color and
	 * mask indices. An offscreen's keyforms ride its owner part's keyform grid (no offscreen
	 * keyform-binding section exists; MOC3 §5.6, OffscreenKeyformProbeTest): opacity rows in
	 * section 161 and the color tables' PREFIX rows are laid out per offscreen in offscreen order.
	 *
	 * @param MocSections sections    The model's typed sections.
	 * @param Int         count       Number of offscreens from CountInfo.
	 * @param List<Part>  parts       The decoded parts (their grids size the keyform runs).
	 * @param ColorTables colorTables The shared color tables (§5.6 sections 108-113); an offscreen's
	 *                                rows are the block's prefix, so they address it by row directly.
	 * @param IntArray    maskData    The full MASK_INDEX_DATA table (§5.6 section 80).
	 * @return List<Offscreen> The decoded offscreens (empty when absent).
	 */
	private fun decodeOffscreens(
		sections: MocSections,
		count: Int,
		parts: List<Part>,
		colorTables: ColorTables,
		maskData: IntArray,
	): List<Offscreen> {
		if (count == 0 || !sections.isPresent(Section.OFFSCREEN_OWNER_PART)) {
			return emptyList()
		}
		val owner = sections.intArray(Section.OFFSCREEN_OWNER_PART)
		val flags = sections.byteArray(Section.OFFSCREEN_CONSTANT_FLAGS)
		val blendModes = sections.intArray(Section.OFFSCREEN_BLEND_MODE) // one packed value per offscreen
		val maskCounts = sections.intArray(Section.OFFSCREEN_MASK_COUNT)
		val maskBases = sections.intArray(Section.OFFSCREEN_MASK_BASE)
		val opacity = sections.floatArray(Section.OFFSCREEN_OPACITY)
		var keyformRowCursor = 0
		return (0 until count).map { offscreenIndex ->
			val keyformCount = parts[owner[offscreenIndex]].drawOrderKeyforms.size
			val keyforms =
				(0 until keyformCount).map { keyIndex ->
					val keyformRow = keyformRowCursor + keyIndex
					OffscreenKeyform(
						opacity[keyformRow],
						colorTables.multiplyAtRow(keyformRow),
						colorTables.screenAtRow(keyformRow),
					)
				}
			keyformRowCursor += keyformCount
			// MOC3 v6 §5.6 s158: the offscreen's masks sit at the s158 offset from the BLOCK START -
			// the offscreen entries are the block's prefix, before the drawables' masks (pinned on
			// Model A: pupil offscreens clip the Whites masks, matching the CMO3 clipGuidList).
			val maskIndices = maskData.copyOfRange(maskBases[offscreenIndex], maskBases[offscreenIndex] + maskCounts[offscreenIndex])
			Offscreen(
				owner[offscreenIndex],
				flags[offscreenIndex].toInt() and 0xFF,
				blendModes[offscreenIndex],
				maskCounts[offscreenIndex],
				keyforms,
				maskIndices,
			)
		}
	}

	/**
	 * Decodes the glues (seam-welds between art meshes).
	 *
	 * @param MocSections sections The model's typed sections.
	 * @return List<Glue> The decoded glues (empty when absent).
	 */
	private fun decodeGlues(sections: MocSections): List<Glue> {
		val meshA = sections.intArray(Section.GLUE_MESH_A)
		if (meshA.isEmpty()) {
			return emptyList()
		}
		val meshB = sections.intArray(Section.GLUE_MESH_B)
		val glueId = sections.idArray(Section.GLUE_ID)
		val keyformBinding = sections.intArray(Section.GLUE_KEYFORM_BINDING)
		val glueVertexStart = sections.intArray(Section.GLUE_VERTEX_START)
		val glueVertexCount = sections.intArray(Section.GLUE_VERTEX_COUNT)
		val keyOffset = sections.intArray(Section.GLUE_KEY_OFFSET)
		val keyCount = sections.intArray(Section.GLUE_KEY_COUNT)
		val weights = sections.floatArray(Section.GLUE_WEIGHTS)
		val indices = sections.shortArray(Section.GLUE_VERTEX_INDICES)
		val intensities = sections.floatArray(Section.GLUE_INTENSITIES)
		return (meshA.indices).map { glueIndex ->
			val vertexStartIndex = glueVertexStart[glueIndex]
			val pairCount = glueVertexCount[glueIndex] / 2
			val pairs =
				(0 until pairCount).map { pairIndex ->
					GlueVertexPair(
						indices[vertexStartIndex + 2 * pairIndex].toInt() and 0xFFFF,
						indices[vertexStartIndex + 2 * pairIndex + 1].toInt() and 0xFFFF,
						weights[vertexStartIndex + 2 * pairIndex],
						weights[vertexStartIndex + 2 * pairIndex + 1],
					)
				}
			Glue(
				// A stripped file without s90 leaves the id blank rather than failing the decode.
				glueId.getOrElse(glueIndex) { "" },
				meshA[glueIndex],
				meshB[glueIndex],
				keyformBinding[glueIndex],
				pairs,
				intensities.copyOfRange(keyOffset[glueIndex], keyOffset[glueIndex] + keyCount[glueIndex]),
			)
		}
	}

	/**
	 * Decodes the render-order group tree (the draw-order hierarchy).
	 *
	 * @param MocSections sections   The model's typed sections.
	 * @param Int         groupCount Number of render-order groups from CountInfo.
	 * @return List<RenderOrderGroup> The decoded groups (empty when absent).
	 */
	private fun decodeRenderOrderGroups(
		sections: MocSections,
		groupCount: Int,
	): List<RenderOrderGroup> {
		if (groupCount == 0) {
			return emptyList()
		}
		val childCount = sections.intArray(Section.RENDER_ORDER_CHILD_COUNT)
		val childKind = sections.intArray(Section.RENDER_ORDER_CHILD_KIND)
		val childIndex = sections.intArray(Section.RENDER_ORDER_CHILD_INDEX)
		val childGroupIndex = sections.intArray(Section.RENDER_ORDER_GROUP_INDEX)
		var childBase = 0
		return (0 until groupCount).map { groupIndex ->
			val children =
				(0 until childCount[groupIndex]).map { childOrdinal ->
					RenderOrderChild(
						childKind[childBase + childOrdinal],
						childIndex[childBase + childOrdinal],
						childGroupIndex[childBase + childOrdinal],
					)
				}
			childBase += childCount[groupIndex]
			RenderOrderGroup(children)
		}
	}
}