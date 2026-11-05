package io.github.psd2live.core

import io.github.psd2live.i18n.tr
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
import org.umamo.runtime.model.RuntimeFeature
import org.umamo.runtime.model.visibleDrawableIds
import org.umamo.runtime.model.withDerivedRenderRoot

/**
 * Anime-style features showing through the bangs, the two usual Live2D setups for it.
 *
 * - A feature copy: a copy of each eyebrow (and each closed-eye line, or any eye part that fades) drawn just above the
 *   front hair at partial opacity and clipped to it, so the feature reads through the hair while the original keeps its
 *   place under it - duplicate the art mesh, raise its draw order above the bangs, clip it with the bang meshes' ids and
 *   lower its opacity.
 * - A window in the bangs, for the eyes: each bang over an eye is clipped inverted by the eye's whites and lashes (and
 *   an iris no white clips), and a copy of the bang clipped by them draws in its place at the remaining opacity. The eye
 *   underneath stays whole - irides and highlights clipped by their whites show as they are, which a copy over the bangs
 *   cannot do: clipping masks only add up, so no copy is clipped by both its white and the bangs.
 *
 * Two stages. The base rig records the [Candidate]s - each brow or eye part whose bangs cover it at rest, with those
 * bangs - in [BuiltRig.seeThrough], whatever the settings say. The finished rig ([apply]), after the journal, the
 * generators, the generated overrides, layer visibility and draw-order overrides, makes what the [Settings] ask for out
 * of the meshes as they are then: so a copy follows every deformer, keyform, edit and override of its original, takes
 * its masks from the bangs still drawn above the feature and its draw order from where those bangs are now, and the
 * settings change it without regenerating. Neither the copies nor a bang's window are in the authored rig: nothing
 * edits them ([WorkspaceGeneratedCopyEdit]); [BuiltRig.seeThroughCopies] names the copies and
 * [BuiltRig.seeThroughWindows] the windowed bangs, never their ids alone.
 */
object SeeThroughLayers {
	/** Appended to the original's id: the copy's id, fixed for every build. */
	const val SUFFIX = "_SeeThrough"
	const val DEFAULT_OPACITY = 0.5f
	val OPACITY_RANGE = 0f..1f

	fun copyId(original: DrawableId) = DrawableId(original.raw + SUFFIX)

	/**
	 * What the base records as candidates: version 2 records every part of an eye, version 1 only lashes and closed-eye
	 * lines. A stored rig recording other ones is regenerated ([MaterializedRigCodec]).
	 */
	const val CANDIDATE_RULES = 2

	/** A feature the bangs cover at rest, as the base rig found it: [eyes] for a part of an eye, else a brow. */
	data class Candidate(val original: String, val eyes: Boolean, val bangs: List<String>)

	/** Why a recorded candidate made no copy although the settings ask for it. */
	enum class DropReason(val wire: String) {
		/** The original is gone: deleted, split or replaced since the base found it. */
		ORIGINAL_MISSING("original_missing"),
		/** Every bang mesh the base found over it is gone. */
		MASKS_MISSING("masks_missing"),
		/** Another mesh already has the copy's id. */
		ID_TAKEN("id_taken"),
		/** Every bang over the eye part has clipping masks of its own, which a window would replace. */
		BANGS_CLIPPED("bangs_clipped"),
		/** The document's runtime target has no inverted masks, which a window in the bangs needs. */
		TARGET_UNSUPPORTED("target_unsupported"),
	}

	data class Note(val original: String, val reason: DropReason)

	/**
	 * What the document asks for: brows and eyes, their opacity, the gap between generated draw orders, and whether the
	 * runtime target has the [invertedMasks] a window in the bangs needs.
	 */
	data class Settings(val brows: Boolean, val eyes: Boolean, val opacity: Float, val drawOrderStep: Int, val invertedMasks: Boolean = true) {
		val enabled get() = brows || eyes

		companion object {
			val OFF = Settings(brows = false, eyes = false, opacity = DEFAULT_OPACITY, drawOrderStep = DRAW_ORDER_STEP)

			/** [config]'s settings; an imported or mesh-only model has no copies. */
			fun of(config: PipelineConfig): Settings =
				if (config.rigEdits.importedCmo3 != null || config.meshOnly) OFF
				else Settings(config.seeThroughBrows, config.seeThroughEyes, config.seeThroughOpacity.coerceIn(OPACITY_RANGE), config.drawOrderStep,
					config.runtimeTarget.supports(RuntimeFeature.ReversedMask))
		}
	}

	/**
	 * [config] making no copies: for the builds a generation step reads as rig data (the meshes, ids and bindings it
	 * migrates or records), never shows - the copies are made again when the result is.
	 */
	fun withoutCopies(config: PipelineConfig) = if (!config.seeThroughBrows && !config.seeThroughEyes) config
		else config.copy(seeThroughBrows = false, seeThroughEyes = false)

	/**
	 * The finished model, the copies it holds (copy id to original id), the bangs it windows (bang id to the eye parts
	 * clipping it inverted) and the candidates that made nothing.
	 */
	class Result(val model: PuppetModel, val copies: Map<String, String>, val notes: List<Note>, val windows: Map<String, List<String>> = emptyMap())

	private val BROWS = setOf(SemanticTag.EYEBROW)
	/** Every part of an eye: whites, irides with their highlights, lashes and closed-eye lines. */
	private val EYES = setOf(SemanticTag.EYEWHITE, SemanticTag.IRIDES, SemanticTag.EYELASH, SemanticTag.EYE_CLOSE)

	/**
	 * Every brow and eye part of [model] that front hair art reaches over at rest, with those bangs: [layerOf] gives
	 * each head drawable's layer (null for any other). Draw order is not looked at here: it is read when the copies are
	 * made, after the user's edits of it.
	 */
	fun candidates(model: PuppetModel, alphaThreshold: Int, layerOf: (DrawableId) -> ClassifiedLayer?): List<Candidate> {
		val bangs = model.drawables.mapNotNull { drawable -> layerOf(drawable.id)?.takeIf { it.semantic.tag == SemanticTag.FRONT_HAIR }?.let { drawable.id to it } }
		if (bangs.isEmpty()) return emptyList()
		return model.drawables.mapNotNull { drawable ->
			val layer = layerOf(drawable.id)?.takeIf { (it.semantic.tag in BROWS || it.semantic.tag in EYES) && it.opaquePixels > 0 } ?: return@mapNotNull null
			val reach = layer.bounds.let { it.copy(top = it.top - it.height * 0.5f, bottom = it.bottom + it.height * 0.5f) }
			val covering = bangs.filter { (_, hair) -> covers(hair, reach, alphaThreshold) }.map { it.first.raw }
			if (covering.isEmpty()) null else Candidate(drawable.id.raw, layer.semantic.tag in EYES, covering)
		}
	}

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
	 * them and opens none.
	 */
	fun apply(
		model: PuppetModel, candidates: List<Candidate>, settings: Settings,
		idFor: (DrawableId) -> DrawableId = ::copyId, earlierCopies: Set<String> = emptySet(),
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
		for ((bangId, parts) in windows) {
			val bang = byId.getValue(bangId)
			val id = idFor(bang.id)
			val reason = when {
				bang.maskedBy.isNotEmpty() || bang.invertMask -> DropReason.BANGS_CLIPPED
				id.raw in byId -> DropReason.ID_TAKEN
				else -> null
			}
			if (reason != null) { parts.forEach { refused.putIfAbsent(it, reason) }; continue }
			val masks = parts.toList()
			windowed[bangId] = bang.copy(maskedBy = masks, invertMask = true)
			made += copyOf(bang, id, masks, windowOrder(model, bang, halfStep, earlierCopies), 1f - settings.opacity) to bang
		}
		// An eye part is reported only when no bang over it opened.
		val opened = windowed.values.flatMapTo(HashSet()) { it.maskedBy }
		refused.forEach { (part, reason) -> if (part !in opened) notes += Note(part.raw, reason) }
		if (made.isEmpty()) return Result(model, emptyMap(), notes)
		val before = made.groupBy({ it.second.id }, { it.first.id })
		fun placed(children: List<OrgChild>) = children.flatMap { child ->
			if (child is OrgChild.Drawable) before[child.id].orEmpty().map { OrgChild.Drawable(it) } + child else listOf(child)
		}
		val finished = model.copy(drawables = model.drawables.map { windowed[it.id.raw] ?: it } + made.map { it.first },
			parts = model.parts.map { part -> if (part.children.none { it is OrgChild.Drawable && it.id in before }) part else part.copy(children = placed(part.children)) },
			rootChildren = placed(model.rootChildren)).withDerivedRenderRoot()
		return Result(finished, made.associate { (copy, original) -> copy.id.raw to original.id.raw }, notes,
			windowed.mapValues { (_, bang) -> bang.maskedBy.map { it.raw } })
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

	/**
	 * [model] - a preview of an uncommitted edit of [shown]'s rig - with each copy [shown] made, and [model] carries over
	 * unchanged, made again where its original or bangs changed: masks, draw order and look as the commit will make them;
	 * one whose bangs no longer qualify is left out. A copy [model] holds as another instance than [shown] was made
	 * with the model (a whole rebuilt preview) and stays as it is, so such a preview comes back as the same instance.
	 * The one entry for every canvas and inspector preview.
	 */
	fun refresh(model: PuppetModel, shown: RigPreviewModel?): PuppetModel {
		val copies = shown?.rig?.seeThroughCopies.orEmpty()
		if (copies.isEmpty() || model === shown?.rig?.puppet) return model
		val windows = shown!!.rig.seeThroughWindows
		val before = shown.rig.puppet.drawables.associateBy { it.id.raw }
		val byId = model.drawables.associateBy { it.id.raw }
		val settings = Settings.of(shown.config)
		val bangs = shown.rig.seeThrough.orEmpty().associate { it.original to it.bangs }
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
				val masks = drawable.maskedBy.filter { it.raw in byId }.ifEmpty { return@mapNotNull null }
				return@mapNotNull copyOf(original, drawable.id, masks, windowOrder(model, original, halfStep, copies.keys), 1f - settings.opacity)
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
		return model.copy(drawables = drawables, parts = model.parts.map { it.copy(children = live(it.children)) },
			rootChildren = live(model.rootChildren)).withDerivedRenderRoot()
	}

	private fun copyOf(original: Drawable, id: DrawableId, masks: List<DrawableId>, order: Float, alpha: Float): Drawable {
		val tracks = LinkedHashMap(original.channelGrids.gridsByChannel)
		tracks.remove(FormChannel.DRAW_ORDER)
		tracks[FormChannel.OPACITY]?.let { tracks[FormChannel.OPACITY] = scaled(it, alpha) }
		return original.copy(id = id, name = tr("model.drawable.seeThrough", original.name), maskedBy = masks, invertMask = false,
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

	/**
	 * Whether [hair]'s art covers any of [reach] at rest: some of its pixels there are above [threshold]. Its box alone
	 * would say yes for bangs swept aside, whose copies would only cost a mask.
	 */
	private fun covers(hair: ClassifiedLayer, reach: Bounds, threshold: Int): Boolean {
		val box = hair.bounds
		if (box.left >= reach.right || reach.left >= box.right || box.top >= reach.bottom || reach.top >= box.bottom) return false
		val layer = hair.source
		val raster = layer.raster
		if (raster.width <= 0 || raster.height <= 0 || layer.bounds.width <= 0 || layer.bounds.height <= 0) return false
		// The raster spans the layer's rectangle at its own resolution.
		val sx = raster.width / layer.bounds.width.toFloat(); val sy = raster.height / layer.bounds.height.toFloat()
		val x0 = ((reach.left - layer.bounds.left) * sx).toInt().coerceIn(0, raster.width)
		val x1 = kotlin.math.ceil((reach.right - layer.bounds.left) * sx).toInt().coerceIn(0, raster.width)
		val y0 = ((reach.top - layer.bounds.top) * sy).toInt().coerceIn(0, raster.height)
		val y1 = kotlin.math.ceil((reach.bottom - layer.bounds.top) * sy).toInt().coerceIn(0, raster.height)
		for (y in y0 until y1) for (x in x0 until x1)
			if ((raster.rgba[(y * raster.width + x) * 4 + 3].toInt() and 0xff) > threshold) return true
		return false
	}
}

/**
 * [this] with the copies over the bangs and the windows in them [settings] asks for made from its finished puppet
 * ([SeeThroughLayers.apply]), the copies on their originals' pages. A rig whose base recorded no candidates (an imported
 * one) is returned as it is.
 */
internal fun BuiltRig.withSeeThrough(settings: SeeThroughLayers.Settings): BuiltRig {
	val candidates = seeThrough.orEmpty()
	if (candidates.isEmpty() || !settings.enabled) return if (seeThroughCopies.isEmpty() && seeThroughNotes.isEmpty() && seeThroughWindows.isEmpty()) this
		else copy(seeThroughCopies = emptyMap(), seeThroughNotes = emptyList(), seeThroughWindows = emptyMap())
	val result = SeeThroughLayers.apply(puppet, candidates, settings)
	val pages = if (result.copies.isEmpty()) pageByDrawableId
		else pageByDrawableId + result.copies.mapNotNull { (copy, original) -> pageByDrawableId[original]?.let { copy to it } }
	return copy(puppet = result.model, pageByDrawableId = pages, seeThroughCopies = result.copies, seeThroughNotes = result.notes,
		seeThroughWindows = result.windows)
}

/**
 * [this] after meshes left its puppet (a soft layer deletion), whose mask lists no longer name them: a windowed bang
 * none of whose eye parts remain is unclipped again and its copy goes, as with no masks it would draw all over it.
 */
internal fun BuiltRig.withSeeThroughWindowsPruned(): BuiltRig {
	if (seeThroughWindows.isEmpty()) return this
	val present = puppet.drawables.mapTo(HashSet()) { it.id.raw }
	val kept = seeThroughWindows.filterKeys { it in present }.mapValues { (_, parts) -> parts.filter { it in present } }.filterValues { it.isNotEmpty() }
	if (kept == seeThroughWindows) return this
	val closed = seeThroughWindows.keys - kept.keys
	val copies = seeThroughCopies.filterValues { it in closed }.keys
	val drawables = puppet.drawables.filter { it.id.raw !in copies }
		.map { if (it.id.raw in closed) it.copy(maskedBy = emptyList(), invertMask = false) else it }
	fun live(children: List<OrgChild>) = children.filter { it !is OrgChild.Drawable || it.id.raw !in copies }
	return copy(puppet = puppet.copy(drawables = drawables, parts = puppet.parts.map { it.copy(children = live(it.children)) },
		rootChildren = live(puppet.rootChildren)).withDerivedRenderRoot(), pageByDrawableId = pageByDrawableId - copies,
		seeThroughCopies = seeThroughCopies - copies, seeThroughWindows = kept)
}

/**
 * [drawable] with the masks its document gives it: a bang the finished rig windows ([BuiltRig.seeThroughWindows])
 * without the window's inverted masks, which the authored bang never had - what an editor shows and edits from.
 */
fun BuiltRig.authoredMasksOf(drawable: Drawable): Drawable =
	if (drawable.id.raw in seeThroughWindows) drawable.copy(maskedBy = emptyList(), invertMask = false) else drawable
