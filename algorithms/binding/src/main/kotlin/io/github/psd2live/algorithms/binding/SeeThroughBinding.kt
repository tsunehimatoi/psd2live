package io.github.psd2live.algorithms.binding

import org.umamo.runtime.model.AlphaBlendMode
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.BlendShapeBinding
import org.umamo.runtime.model.Part
import org.umamo.runtime.model.PartId
import org.umamo.runtime.model.PartForm
import org.umamo.runtime.model.PartComposite
import org.umamo.runtime.model.PartGroupMode
import org.umamo.runtime.model.ChannelGrids
import org.umamo.runtime.model.ChannelValue
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.FormChannel
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.MeshForm
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.visibleDrawableIds
import org.umamo.runtime.model.withDerivedRenderRoot

/** Pure feature-copy and inverted-mask evaluation from captured coverage candidates. */
class SeeThroughBinding(
    private val copyName: (String) -> String,
    private val checkpoint: () -> Unit = {},
) {
    data class Candidate(val original: String, val eyes: Boolean, val bangs: List<String>)
    enum class DropReason { ORIGINAL_MISSING, MASKS_MISSING, ID_TAKEN, BANGS_CLIPPED, TARGET_UNSUPPORTED }
    data class Note(val original: String, val reason: DropReason)
    data class Settings(val brows: Boolean, val eyes: Boolean, val opacity: Float, val drawOrderStep: Int, val invertedMasks: Boolean = true, val compositeMasks: Boolean = false) {
        val enabled get() = brows || eyes
    }
    class Result(val model: PuppetModel, val copies: Map<String, String>, val notes: List<Note>, val windows: Map<String, List<String>> = emptyMap())

	/**
	 * Whether eye part [original] shows through a window in its bangs rather than as a copy over them: it does unless it
	 * fades on its own (a closed-eye line), since a mask clips whatever the opacity of its mesh. One clipped by masks of
	 * its own (an iris by its white) shows through their window and needs none.
	 */
	private fun windowed(original: Drawable) = original.channelGrids[FormChannel.OPACITY] == null

	/**
	 * [model] with what [settings] asks for of [candidates].
	 *
	 * A feature copy's masks are its bangs still in the model, shown, and drawn above the original at every keyform;
	 * with none it is not made. It draws just above the highest of them and below the next mesh, at its original's
	 * opacity (static and keyed) times [Settings.opacity], and lists just in front of its original in the original's
	 * part, so hiding or fading that part hides it too. Its parent, mesh, keyforms, blend shapes, colors and visibility
	 * are the original's; it cannot be picked on the canvas.
	 *
	 * A window opens in each such bang over an unclipped eye part: the bang is clipped inverted by every eye part it
	 * covers, and a copy of it clipped by them, at its opacity times one minus [Settings.opacity], draws just above it
	 * (below any feature copy over it) and lists just in front of it in its part. A bang with masks of its own keeps
	 * them on its drawable and applies the eye window on an isolated parent when the runtime supports composite masks.
	 */
	fun apply(
		model: PuppetModel, candidates: List<Candidate>, settings: Settings,
		idFor: (DrawableId) -> DrawableId, earlierCopies: Set<String> = emptySet(),
	): Result {
		if (!settings.enabled || candidates.isEmpty()) return Result(model, emptyMap(), emptyList())
		val byId = model.drawables.associateBy { it.id.raw }
		val shown by lazy { model.visibleDrawableIds() }
		val notes = ArrayList<Note>()
		val made = ArrayList<Pair<Drawable, Drawable>>()
		val halfStep = (settings.drawOrderStep / 2f).coerceAtLeast(0.5f)
		// Each bang to the eye parts it opens over, in candidate order.
		val windows = LinkedHashMap<String, LinkedHashSet<DrawableId>>()
		for (candidate in candidates) {
			checkpoint()
			if (if (candidate.eyes) !settings.eyes else !settings.brows) continue
			val original = byId[candidate.original] ?: run { notes += Note(candidate.original, DropReason.ORIGINAL_MISSING); continue }
			if (candidate.eyes && windowed(original)) {
				if (original.maskedBy.isNotEmpty()) continue
				if (candidate.bangs.none { it in byId }) { notes += Note(candidate.original, DropReason.MASKS_MISSING); continue }
				if (!settings.invertedMasks) { notes += Note(candidate.original, DropReason.TARGET_UNSUPPORTED); continue }
				val above = highestOrder(original)
				for (bang in candidate.bangs.mapNotNull(byId::get)) if (bang.id in shown && lowestOrder(bang) > above)
					windows.getOrPut(bang.id.raw) { LinkedHashSet() } += original.id
				continue
			}
			val id = idFor(original.id)
			if (id.raw in byId) { notes += Note(candidate.original, DropReason.ID_TAKEN); continue }
			if (candidate.bangs.none { it in byId }) { notes += Note(candidate.original, DropReason.MASKS_MISSING); continue }
			val (masks, order) = place(model, byId, shown, original, candidate.bangs, halfStep, earlierCopies) ?: continue
			made += copyOf(original, id, masks, order, settings.opacity) to original
		}
		val windowed = LinkedHashMap<String, Drawable>()
		val refused = LinkedHashMap<DrawableId, DropReason>()
		val wrappers = LinkedHashMap<DrawableId, Part>()
		val openedWindows = LinkedHashMap<String, List<String>>()
		val partIds = model.parts.mapTo(HashSet()) { it.id }
		for ((bangId, parts) in windows) {
			checkpoint()
			val bang = byId.getValue(bangId)
			val id = idFor(bang.id)
			val reason = when {
				(bang.maskedBy.isNotEmpty() || bang.invertMask) && !settings.compositeMasks -> DropReason.BANGS_CLIPPED
				id.raw in byId -> DropReason.ID_TAKEN
				else -> null
			}
			if (reason != null) { parts.forEach { refused.putIfAbsent(it, reason) }; continue }
			val masks = parts.toList()
			val order = windowOrder(model, bang, halfStep, earlierCopies)
			if (bang.maskedBy.isNotEmpty() || bang.invertMask) {
				// A mask list is a union. Nest the old clip inside the new window to form an intersection.
				val outsideId = PartId(idFor(DrawableId("$bangId:window-outside")).raw)
				val insideId = PartId(idFor(DrawableId("$bangId:window-inside")).raw)
				if (outsideId == insideId || outsideId in partIds || insideId in partIds) {
					parts.forEach { refused.putIfAbsent(it, DropReason.ID_TAKEN) }; continue
				}
				partIds += outsideId; partIds += insideId
				val copy = copyOf(bang, id, bang.maskedBy, order, 1f - settings.opacity).copy(invertMask = bang.invertMask)
				wrappers[bang.id] = windowPart(bang, outsideId, masks, true)
				wrappers[id] = windowPart(copy, insideId, masks, false)
				windowed[bangId] = bang.copy(blendMode = BlendMode.Normal, alphaBlendMode = AlphaBlendMode.Over)
				made += copy.copy(blendMode = BlendMode.Normal, alphaBlendMode = AlphaBlendMode.Over) to bang
			} else {
				windowed[bangId] = bang.copy(maskedBy = masks, invertMask = true)
				made += copyOf(bang, id, masks, order, 1f - settings.opacity) to bang
			}
			openedWindows[bangId] = masks.map { it.raw }
		}
		// An eye part is reported only when no bang over it opened.
		val opened = openedWindows.values.flatten().mapTo(HashSet(), ::DrawableId)
		refused.forEach { (part, reason) -> if (part !in opened) notes += Note(part.raw, reason) }
		if (made.isEmpty()) return Result(model, emptyMap(), notes)
		val before = made.groupBy({ it.second.id }, { it.first.id })
		fun node(id: DrawableId): OrgChild = wrappers[id]?.let { OrgChild.Part(it.id) } ?: OrgChild.Drawable(id)
		val placedIds = HashSet<DrawableId>()
		fun placed(children: List<OrgChild>) = children.flatMap { child ->
			if (child is OrgChild.Drawable) {
				placedIds += child.id
				before[child.id].orEmpty().map(::node) + node(child.id)
			} else listOf(child)
		}
		val placedParts = model.parts.map { it.copy(children = placed(it.children)) }
		val placedRoot = placed(model.rootChildren)
		val orphaned = before.keys.filter { it !in placedIds }.flatMap { before[it].orEmpty().map(::node) + node(it) }
		val finished = model.copy(drawables = model.drawables.map { windowed[it.id.raw] ?: it } + made.map { it.first },
			parts = placedParts + wrappers.values,
			rootChildren = placedRoot + orphaned).withDerivedRenderRoot()
		return Result(finished, made.associate { (copy, original) -> copy.id.raw to original.id.raw }, notes,
			openedWindows)
	}

	/**
	 * The draw order of the copy of windowed [bang]: above its highest order, half as far as a feature copy over it would
	 * draw, so the brows copied over the bangs stay in front of the window.
	 */
	private fun windowOrder(model: PuppetModel, bang: Drawable, halfStep: Float, copies: Set<String> = emptySet()): Float {
		val top = highestOrder(bang)
		val next = model.drawables.asSequence().filter { it.id != bang.id && it.id.raw !in copies }.map(::lowestOrder).filter { it > top }.minOrNull()
		return (top + (if (next == null) halfStep else minOf(halfStep, (next - top) / 2f)) / 2f).coerceIn(0f, 1000f)
	}

	/**
	 * The masks of a copy of [original] among [bangs] - those present, shown and drawn above it at every keyform - and
	 * its draw order: [halfStep] above the highest of them, below the next mesh other than [copies]. Null when no bang
	 * qualifies.
	 */
	private fun place(model: PuppetModel, byId: Map<String, Drawable>, shown: Set<DrawableId>, original: Drawable, bangs: List<String>,
	                  halfStep: Float, copies: Set<String> = emptySet()): Pair<List<DrawableId>, Float>? {
		val above = highestOrder(original)
		val masks = bangs.mapNotNull(byId::get).filter { it.id in shown && lowestOrder(it) > above }
		if (masks.isEmpty()) return null
		val top = masks.maxOf(::highestOrder)
		val skipped = masks.mapTo(HashSet()) { it.id } + original.id
		val next = model.drawables.asSequence().filter { it.id !in skipped && it.id.raw !in copies }.map(::lowestOrder).filter { it > top }.minOrNull()
		return masks.map { it.id } to (top + if (next == null) halfStep else minOf(halfStep, (next - top) / 2f)).coerceIn(0f, 1000f)
	}

	fun refresh(model: PuppetModel, previous: PuppetModel, copies: Map<String, String>, windows: Map<String, List<String>>,
	            candidates: List<Candidate>, settings: Settings): PuppetModel {
		if (copies.isEmpty() || model === previous) return model
		val before = previous.drawables.associateBy { it.id.raw }
		val byId = model.drawables.associateBy { it.id.raw }
		val bangs = candidates.associate { it.original to it.bangs }
		val wrappers = model.parts.filter { part -> part.groupMode == PartGroupMode.Isolated && part.children.size == 1 }
			.mapNotNull { part -> (part.children.single() as? OrgChild.Drawable)?.let { it.id.raw to part } }.toMap()
		val visible by lazy { model.visibleDrawableIds() }
		val halfStep = (settings.drawOrderStep / 2f).coerceAtLeast(0.5f)
		var changed = false
		val drawables = model.drawables.mapNotNull { drawable ->
			val originalId = copies[drawable.id.raw] ?: return@mapNotNull drawable
			if (drawable !== before[drawable.id.raw]) return@mapNotNull drawable
			val original = byId[originalId] ?: run { changed = true; return@mapNotNull null }
			if (originalId in windows) {
				// The copy in a bang's window: its masks are the window's eye parts, which it follows by id.
				if (original === before[originalId]) return@mapNotNull drawable
				changed = true
				val nested = wrappers[drawable.id.raw]?.composite?.maskedBy?.map { it.raw } == windows[originalId]
				val masks = (if (nested) original.maskedBy else drawable.maskedBy).filter { it.raw in byId }
				if (!nested && masks.isEmpty()) return@mapNotNull null
				val copy = copyOf(original, drawable.id, masks, windowOrder(model, original, halfStep, copies.keys), 1f - settings.opacity)
				return@mapNotNull if (nested) copy.copy(invertMask = original.invertMask) else copy
			}
			val near =bangs[originalId] ?: drawable.maskedBy.map { it.raw }
			if (original === before[originalId] && near.all { byId[it] === before[it] }) return@mapNotNull drawable
			changed = true
			val (masks, order) = place(model, byId, visible, original, near, halfStep, copies.keys) ?: return@mapNotNull null
			copyOf(original, drawable.id, masks, order, settings.opacity)
		}
		if (!changed) return model
		val kept = drawables.mapTo(HashSet()) { it.id }
		fun live(children: List<OrgChild>) = children.filter { it !is OrgChild.Drawable || it.id in kept }
		val refreshedById = drawables.associateBy { it.id.raw }
		val previousParts = previous.parts.associateBy { it.id }
		val refreshedParts = model.parts.map { part ->
			val child = (part.children.singleOrNull() as? OrgChild.Drawable)?.id?.raw
			val originalId = copies[child] ?: child
			val drawable = refreshedById[child]
			if (drawable != null && originalId in windows && wrappers[child] === part &&
				part === previousParts[part.id] && part.composite.maskedBy.map { it.raw } == windows[originalId]) {
				val fresh = windowPart(drawable, part.id, part.composite.maskedBy, part.composite.invertMask)
				part.copy(channelGrids = fresh.channelGrids, drawOrder = fresh.drawOrder, blendShapes = fresh.blendShapes)
			} else part.copy(children = live(part.children))
		}
		return model.copy(drawables = drawables, parts = refreshedParts,
			rootChildren = live(model.rootChildren)).withDerivedRenderRoot()
	}

	private fun windowPart(drawable: Drawable, id: PartId, masks: List<DrawableId>, inverted: Boolean): Part {
		val order = drawable.channelGrids[FormChannel.DRAW_ORDER] ?: KeyformGrid(emptyList(),
			listOf(KeyformCell(intArrayOf(), ChannelValue.Scalar(drawable.drawOrder) as ChannelValue)))
		return Part(id = id, name = copyName(drawable.name), children = listOf(OrgChild.Drawable(drawable.id)),
			groupMode = PartGroupMode.Isolated, drawOrder = drawable.drawOrder.toInt(),
			channelGrids = ChannelGrids(mapOf(FormChannel.DRAW_ORDER to order)),
			composite = PartComposite(blendMode = drawable.blendMode, alphaBlendMode = drawable.alphaBlendMode,
				maskedBy = masks, invertMask = inverted),
			blendShapes = drawable.blendShapes.map { shape -> BlendShapeBinding(shape.parameterId, shape.keys,
				shape.neutralIndex, shape.forms.map { it?.let { form -> PartForm(form.drawOrder) } }, shape.limits) })
	}

	private fun copyOf(original: Drawable, id: DrawableId, masks: List<DrawableId>, order: Float, alpha: Float): Drawable {
		val tracks = LinkedHashMap(original.channelGrids.gridsByChannel)
		tracks.remove(FormChannel.DRAW_ORDER)
		tracks[FormChannel.OPACITY]?.let { tracks[FormChannel.OPACITY] = scaled(it, alpha) }
		return original.copy(id = id, name = copyName(original.name), maskedBy = masks, invertMask = false,
			channelGrids = if (tracks.isEmpty()) ChannelGrids.Empty else ChannelGrids(tracks), drawOrder = order,
			opacity = original.opacity * alpha, isSelectable = false, textureSourceId = original.textureSourceId ?: original.id,
			blendShapes = shapes(original, order, alpha), userData = "")
	}

	private fun shapes(original: Drawable, order: Float, alpha: Float) = original.blendShapes.map { binding ->
		binding.copy(forms = binding.forms.map { form -> form?.let { MeshForm(it.positionDeltas, order, it.opacity * alpha, it.multiplyColor, it.screenColor) } })
	}

	private fun scaled(track: KeyformGrid<ChannelValue>, alpha: Float): KeyformGrid<ChannelValue> = KeyformGrid(track.axes,
		track.cells.map { cell -> KeyformCell(cell.coordinate, (cell.form as? ChannelValue.Scalar)?.let { ChannelValue.Scalar(it.value * alpha) } ?: cell.form) })

	private fun orders(drawable: Drawable): List<Float> =
		drawable.channelGrids[FormChannel.DRAW_ORDER]?.cells?.mapNotNull { (it.form as? ChannelValue.Scalar)?.value }?.takeIf { it.isNotEmpty() }
			?: listOf(drawable.drawOrder)

	/** The highest draw order [drawable] takes at any keyform. */
	private fun highestOrder(drawable: Drawable) = orders(drawable).max()

	/** The lowest draw order [drawable] takes at any keyform. */
	private fun lowestOrder(drawable: Drawable) = orders(drawable).min()

}
