package org.umamo.interop.moc3.import

import org.umamo.format.moc3.moc.ConstantFlag
import org.umamo.format.moc3.model.BlendShapeTarget
import org.umamo.interop.alphaBlendOfPacked
import org.umamo.interop.colorBlendOfPacked
import org.umamo.runtime.keyform.asChannelTrack
import org.umamo.runtime.keyform.channelGridsOf
import org.umamo.runtime.model.BlendShapeBinding
import org.umamo.runtime.model.ChannelGrids
import org.umamo.runtime.model.ChannelValue
import org.umamo.runtime.model.ColorRgb
import org.umamo.runtime.model.DEFAULT_DRAW_ORDER
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.FormChannel
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.Part
import org.umamo.runtime.model.PartComposite
import org.umamo.runtime.model.PartForm
import org.umamo.runtime.model.PartGroupMode
import org.umamo.format.moc3.model.Part as MocPart

/**
 * The runtime parts and the org tree's top level, which are one result because they are one traversal.
 *
 * @property List<Part>     records      The runtime parts, in FILE order (the addressing every moc index uses).
 * @property List<OrgChild> rootChildren The org tree's top level, in panel order.
 */
internal class Moc3ImportedParts(
	val records: List<Part>,
	val rootChildren: List<OrgChild>,
)

/**
 * Imports the parts list and the org tree's root children.
 *
 * Runs AFTER [importRenderRoot] and the panel-index reconstruction it feeds, because MOC3 stores parent
 * indices but no interleaved panel order: each parent's sub-parts and drawables are sorted by the panel
 * index recovered from the render tree, a part taking the minimum over its descendants, with ties
 * keeping file order.  Handed an empty [panelIndexByDrawable] this still succeeds and simply produces a
 * different, plausible ordering - which is why `Moc3ImportOrderTest` pins the sequence rather than
 * leaving it to be noticed.
 *
 * Malformed parent links are normalized so nothing is dropped from the outliner: an out-of-range parent
 * index goes to the root, and every member of a parent cycle is re-parented to the root (breaking the
 * cycle's edges keeps the forest acyclic and every part reachable).
 *
 * @param Moc3ImportContext context              The import's derived state.
 * @param Map               panelIndexByDrawable Reconstructed panel index per drawable.
 * @return Moc3ImportedParts The parts (file order) and the root children (panel order).
 */
internal fun importParts(
	context: Moc3ImportContext,
	panelIndexByDrawable: Map<DrawableId, Int>,
): Moc3ImportedParts {
	val mocDocument = context.mocDocument
	val partIds = context.partIds
	val drawableIdsByFileIndex = context.drawableIdsByFileIndex
	val partCount = mocDocument.parts.size
	val drawOrderGroupPartIndices = drawOrderGroupPartIndices(context)

	// Normalize part parents in two steps: an out-of-range index goes to the root, and any part
	// whose ancestor chain never reaches the root (a malformed parent CYCLE - every member
	// in-range, so the range check alone misses it) is re-parented to the root too.  Without this
	// the whole cycle cluster is unreachable from childrenOf(-1), and since both the outliner and
	// the renderer's visibility gate walk the org tree from the root, its parts AND drawables
	// silently vanish.  Re-parenting every cycle member breaks all cycle edges, so the resulting
	// forest is acyclic and complete.
	val rangedParentIndices =
		IntArray(partCount) { partIndex ->
			val parentIndex = mocDocument.parts[partIndex].parentPartIndex
			if (parentIndex in 0 until partCount && parentIndex != partIndex) parentIndex else -1
		}

	fun reachesRoot(startIndex: Int): Boolean {
		var currentIndex = rangedParentIndices[startIndex]
		var steps = 0
		while (currentIndex != -1) {
			if (steps > partCount) {
				return false
			}
			steps++
			currentIndex = rangedParentIndices[currentIndex]
		}
		return true
	}

	val normalizedParentIndices =
		IntArray(partCount) { partIndex ->
			if (reachesRoot(partIndex)) rangedParentIndices[partIndex] else -1
		}

	val childPartIndices = HashMap<Int, MutableList<Int>>()
	val childDrawableIndices = HashMap<Int, MutableList<Int>>()
	mocDocument.parts.forEachIndexed { partIndex, _ ->
		childPartIndices.getOrPut(normalizedParentIndices[partIndex], ::mutableListOf).add(partIndex)
	}
	mocDocument.artMeshes.forEachIndexed { drawableIndex, artMesh ->
		val parentIndex = if (artMesh.parentPartIndex in 0 until partCount) artMesh.parentPartIndex else -1
		childDrawableIndices.getOrPut(parentIndex, ::mutableListOf).add(drawableIndex)
	}

	// A part's panel index is the minimum over its descendants' reconstructed indices, memoized over
	// the parent-index tree.  The cache is seeded before recursing so a malformed parent cycle
	// terminates instead of overflowing the stack.
	val partPanelIndexCache = HashMap<Int, Int>()

	fun partPanelIndex(partIndex: Int): Int {
		partPanelIndexCache[partIndex]?.let { cachedIndex ->
			return cachedIndex
		}
		partPanelIndexCache[partIndex] = Int.MAX_VALUE
		var minimumIndex = Int.MAX_VALUE
		for (drawableIndex in childDrawableIndices[partIndex].orEmpty()) {
			val panelIndex = panelIndexByDrawable[drawableIdsByFileIndex[drawableIndex]] ?: Int.MAX_VALUE
			if (panelIndex < minimumIndex) {
				minimumIndex = panelIndex
			}
		}
		for (childPartIndex in childPartIndices[partIndex].orEmpty()) {
			val panelIndex = partPanelIndex(childPartIndex)
			if (panelIndex < minimumIndex) {
				minimumIndex = panelIndex
			}
		}
		partPanelIndexCache[partIndex] = minimumIndex
		return minimumIndex
	}

	fun childrenOf(parentIndex: Int): List<OrgChild> {
		data class ChildEntry(val child: OrgChild, val panelIndex: Int)

		val entries = ArrayList<ChildEntry>()
		for (childPartIndex in childPartIndices[parentIndex].orEmpty()) {
			entries.add(ChildEntry(OrgChild.Part(partIds[childPartIndex]), partPanelIndex(childPartIndex)))
		}
		for (drawableIndex in childDrawableIndices[parentIndex].orEmpty()) {
			val drawableId = drawableIdsByFileIndex[drawableIndex]
			entries.add(
				ChildEntry(
					OrgChild.Drawable(drawableId),
					panelIndexByDrawable[drawableId] ?: Int.MAX_VALUE,
				),
			)
		}
		return entries.sortedBy { entry -> entry.panelIndex }.map { entry -> entry.child }
	}

	val parts =
		mocDocument.parts.mapIndexed { partIndex, source ->
			// MOC3 (runtime format) only records composite data for offscreen parts, so this is null for
			// the rest; the composite is stored latently and applied only while the part is Isolated.
			val offscreenComposite = partCompositeOf(context, source, partIndex)
			val partChannels = partChannelsOf(context, source, partIndex)
			Part(
				id = partIds[partIndex],
				// cdi3: DisplayPart.name is the display label; fall back to the id.
				name = context.partNameById[source.id] ?: source.id,
				children = childrenOf(partIndex),
				// MOC3 §5.6 s7/s8 carry the part's visibility (both flags, split unpinned).  Sketch and
				// lock ARE editor-only state the bake drops, so those still default to shown/unlocked.
				isVisible = source.isVisible,
				isSketch = false,
				isSelectable = true,
				// An owned offscreen wins over render-order-group membership (an isolated part is
				// always grouped; the bake records both).
				groupMode =
					when {
						offscreenComposite != null -> PartGroupMode.Isolated
						partIndex in drawOrderGroupPartIndices -> PartGroupMode.Grouped
						else -> PartGroupMode.PassThrough
					},
				drawOrder = partStaticDrawOrder(context, source),
				channelGrids = partChannels,
				composite = offscreenComposite ?: PartComposite(),
				// MOC3 v5+ §5.6: a part-target blend record, whose only channel is the draw order.
				blendShapes = partBlendShapesOfBound(context, source, partIndex, partChannels),
			)
		}
	return Moc3ImportedParts(parts, childrenOf(-1))
}

/**
 * The static (default-pose) draw order of a moc part - the sort key of its render-order slot.
 *
 * @param Moc3ImportContext context The import's derived state.
 * @param MocPart           source  The moc part.
 * @return Int The quantized draw order (Cubism default 500 when the part carries no keyforms).
 */
internal fun partStaticDrawOrder(
	context: Moc3ImportContext,
	source: MocPart,
): Int {
	// MOC3: PART_KEYFORM_BINDING 0 means static for parts (a single draw-order value), unlike
	// meshes/deformers where 0 is a real binding.
	val binding = if (source.keyformBindingIndex > 0) context.bindingOf(source.keyformBindingIndex) else null
	val defaultCell = defaultCellIndexOf(context, binding)
	val drawOrder =
		source.drawOrderKeyforms.getOrElse(defaultCell) {
			source.drawOrderKeyforms.firstOrNull() ?: DEFAULT_DRAW_ORDER.toFloat()
		}
	return (drawOrder + 0.001f).toInt()
}

/**
 * The compositing settings of a moc part, or null when the part owns no offscreen (i.e. its
 * group mode is not Isolated).
 *
 * Takes the part's file index rather than looking the offscreen up by the part's id, for the same
 * reason [partBlendShapesOfBound] does: a moc addresses its offscreens by index and nothing guarantees
 * the id strings are unique, so two same-named parts would both read whichever record came last.
 *
 * @param Moc3ImportContext context   The import's derived state.
 * @param MocPart           source    The moc part.
 * @param Int               partIndex The part's file index.
 * @return PartComposite? The runtime compositing settings, or null.
 */
internal fun partCompositeOf(
	context: Moc3ImportContext,
	source: MocPart,
	partIndex: Int,
): PartComposite? {
	val offscreen = context.offscreenByPartIndex[partIndex] ?: return null
	// The first keyform doubles as the static fallback (a static part stores exactly one row).
	val staticKeyform = offscreen.keyforms.firstOrNull()
	return PartComposite(
		// MOC3 v6 §5.6 s157: packed colorMode | (alphaMode shl 8).
		blendMode = colorBlendOfPacked(offscreen.blendMode),
		alphaBlendMode = alphaBlendOfPacked(offscreen.blendMode),
		// MOC3 §5.6: offscreen mask sources are drawable file indices (the MASK_INDEX_DATA prefix).
		maskedBy =
			offscreen.maskIndices.toList()
				.mapNotNull { maskIndex -> context.drawableIdsByFileIndex.getOrNull(maskIndex) },
		// MOC3 v6 §5.6 s156 bit 3: invert clipping mask (same bit position as the drawable flag).
		invertMask = offscreen.constantFlags and ConstantFlag.IS_INVERTED_MASK != 0,
		opacity = staticKeyform?.opacity ?: 1f,
		multiplyColor = colorRgbOf(staticKeyform?.multiplyColor) ?: ColorRgb.MultiplyIdentity,
		screenColor = colorRgbOf(staticKeyform?.screenColor) ?: ColorRgb.ScreenIdentity,
	)
}

/**
 * The parameter-driven per-channel tracks of a moc part, empty when the part is static.  Carries
 * the draw order always; for an isolated part the offscreen's keyformed opacity/color
 * channels merge in, riding the same grid cells (MOC3 §5.6: Σ owner grid == CountInfo 36).
 *
 * @param Moc3ImportContext context   The import's derived state.
 * @param MocPart           source    The moc part.
 * @param Int               partIndex The part's file index, which is how its offscreen names it.
 * @return ChannelGrids The part's per-channel tracks, empty when it is unbound.
 */
internal fun partChannelsOf(
	context: Moc3ImportContext,
	source: MocPart,
	partIndex: Int,
): ChannelGrids {
	if (source.keyformBindingIndex <= 0) {
		return ChannelGrids.Empty
	}
	val offscreenKeyforms = context.offscreenByPartIndex[partIndex]?.keyforms
	val bundled =
		gridOf(context, context.bindingOf(source.keyformBindingIndex)) { gridIndex ->
			source.drawOrderKeyforms.getOrNull(gridIndex)?.let { drawOrder ->
				val offscreenKeyform = offscreenKeyforms?.getOrNull(gridIndex)
				PartForm(
					drawOrder = drawOrder,
					opacity = offscreenKeyform?.opacity ?: 1f,
					multiplyColor = colorRgbOf(offscreenKeyform?.multiplyColor) ?: ColorRgb.MultiplyIdentity,
					screenColor = colorRgbOf(offscreenKeyform?.screenColor) ?: ColorRgb.ScreenIdentity,
				)
			}
		} ?: return ChannelGrids.Empty
	// Fan the one bundled grid out into per-channel tracks sharing its axes: a pure re-shape, so the
	// blended values are bit-identical to what the bundled cell produced.
	return channelGridsOf(
		FormChannel.DRAW_ORDER to bundled.asChannelTrack { form -> ChannelValue.Scalar(form.drawOrder) },
		FormChannel.OPACITY to bundled.asChannelTrack { form -> ChannelValue.Scalar(form.opacity) },
		FormChannel.MULTIPLY_COLOR to bundled.asChannelTrack { form -> ChannelValue.Color(form.multiplyColor) },
		FormChannel.SCREEN_COLOR to bundled.asChannelTrack { form -> ChannelValue.Color(form.screenColor) },
	)
}

/**
 * [partBlendShapesOf] with this part's records looked up.
 *
 * Takes the part's file index rather than searching for it by id.  A MOC3 addresses blend records
 * by index and nothing guarantees the id strings are unique, so resolving the index from the id
 * would give two same-named parts the SAME records - the first part's, applied twice, with the
 * second part's own records never read.  The caller is iterating by index already.
 *
 * @param Moc3ImportContext context      The import's derived state.
 * @param MocPart           source       The moc part.
 * @param Int               partIndex    The part's file index.
 * @param ChannelGrids      channelGrids The part's tracks, already built by the caller.
 * @return List<BlendShapeBinding<PartForm>> The runtime bindings, empty when the part has none.
 */
private fun partBlendShapesOfBound(
	context: Moc3ImportContext,
	source: MocPart,
	partIndex: Int,
	channelGrids: ChannelGrids,
): List<BlendShapeBinding<PartForm>> {
	val records = context.blendRecordsByTarget[BlendShapeTarget.PART to partIndex].orEmpty()
	if (records.isEmpty()) {
		return emptyList()
	}
	return partBlendShapesOf(context, partStaticDrawOrder(context, source).toFloat(), channelGrids, records)
}

/**
 * The part file indices the render-order tree names as "Group by Draw Order" groups.
 *
 * @param Moc3ImportContext context The import's derived state.
 * @return Set<Int> The grouped parts' file indices.
 */
private fun drawOrderGroupPartIndices(context: Moc3ImportContext): Set<Int> =
	buildSet {
		// The draw-order tree: moc3 stores it explicitly (MOC3 §5.6 render-order groups, group 0 = root),
		// so it is taken as the baked authority rather than re-derived from the reconstructed org tree.
		// Parts referenced as kind-1 children are the "Group by Draw Order" parts.
		for (group in context.mocDocument.renderOrderGroups) {
			for (child in group.children) {
				if (child.kind == 1) {
					add(child.index)
				}
			}
		}
	}