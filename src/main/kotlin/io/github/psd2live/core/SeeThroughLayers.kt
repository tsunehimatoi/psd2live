package io.github.psd2live.core

import io.github.psd2live.i18n.tr
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.RuntimeFeature
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

    private fun kernel() = io.github.psd2live.algorithms.binding.SeeThroughBinding(copyName = { name -> tr("model.drawable.seeThrough", name) })
    private fun Candidate.captured() = io.github.psd2live.algorithms.binding.SeeThroughBinding.Candidate(original, eyes, bangs)
    private fun Settings.captured() = io.github.psd2live.algorithms.binding.SeeThroughBinding.Settings(brows, eyes, opacity, drawOrderStep, invertedMasks)

    fun apply(model: PuppetModel, candidates: List<Candidate>, settings: Settings,
              idFor: (DrawableId) -> DrawableId = ::copyId, earlierCopies: Set<String> = emptySet()): Result {
        val result = kernel().apply(model, candidates.map { it.captured() }, settings.captured(), idFor, earlierCopies)
        return Result(result.model, result.copies, result.notes.map { Note(it.original, DropReason.valueOf(it.reason.name)) }, result.windows)
    }

    fun refresh(model: PuppetModel, shown: RigPreviewModel?): PuppetModel {
        if (shown == null) return model
        return kernel().refresh(model, shown.rig.puppet, shown.rig.seeThroughCopies, shown.rig.seeThroughWindows,
            shown.rig.seeThrough.orEmpty().map { it.captured() }, Settings.of(shown.config).captured())
    }

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
