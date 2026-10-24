package io.github.psd2live.core

import org.umamo.runtime.model.*

/**
 * The base rig's generators as role components: what each makes (its deformers, by the start of their IDs, and its
 * parameters), what it is made from (the layer roles of its members) and the rig values that tune it ([RigTuning]
 * groups). The modifiers panel shows one card per component the model has; the stage view names a deformer's or a
 * keyform axis's component from here.
 *
 * A component switched off ([PipelineConfig.disabledComponents]) still builds its deformers, so the hierarchy and every
 * edit on it stay, but each of its parameters is flattened at its default on everything the base rig made ([gate]):
 * nothing it generated moves. What reads its parameters elsewhere - the eyes' reaction to Angle X, made with the eyes
 * but keyed on the head's parameter - goes with it.
 */
internal object RigComponents {
	class Component(
		val id: String,
		val groups: List<RigTuning.Group>,
		val parameters: Set<String>,
		val deformers: List<String>,
		val members: Set<SemanticTag>,
	)

	private val P = StandardParameters

	val HEAD = Component("head", listOf(RigTuning.Group.HEAD, RigTuning.Group.NOSE_EARS), setOf(P.ANGLE_X.raw, P.ANGLE_Y.raw, P.ANGLE_Z.raw),
		listOf("DeformHead", "DeformFace", "DeformFeatureDisplacement", "DeformEars"),
		setOf(SemanticTag.FACE, SemanticTag.FACE_DETAIL, SemanticTag.NOSE, SemanticTag.EARS, SemanticTag.HEADWEAR, SemanticTag.EARWEAR))
	val EYES = Component("eyes", listOf(RigTuning.Group.EYES), setOf(P.EYE_L_OPEN, P.EYE_R_OPEN, P.EYE_BALL_X, P.EYE_BALL_Y, P.EYE_BALL_FORM).map { it.raw }.toSet(),
		listOf("DeformEye"), setOf(SemanticTag.EYEWHITE, SemanticTag.IRIDES, SemanticTag.EYELASH, SemanticTag.EYE_CLOSE, SemanticTag.EYEWEAR))
	val BROWS = Component("brows", listOf(RigTuning.Group.BROWS), setOf(P.BROW_L_Y.raw, P.BROW_R_Y.raw), listOf("DeformBrows"), setOf(SemanticTag.EYEBROW))
	val MOUTH = Component("mouth", listOf(RigTuning.Group.MOUTH), setOf(P.MOUTH_FORM.raw, P.MOUTH_OPEN.raw), listOf("DeformMouth"),
		setOf(SemanticTag.MOUTH, SemanticTag.MOUTH_OPEN, SemanticTag.MOUTH_CLOSE, SemanticTag.TOOTH_T, SemanticTag.TOOTH_B, SemanticTag.TONGUE))
	val BODY = Component("body", listOf(RigTuning.Group.TURN, RigTuning.Group.OPEN, RigTuning.Group.LEAN, RigTuning.Group.PROPORTION, RigTuning.Group.DEPTH),
		setOf(P.BODY_X.raw, P.BODY_Y.raw, P.BODY_LEAN.raw, P.PROPORTION.raw), listOf("DeformBody", "DeformLegs", "DeformArm"),
		setOf(SemanticTag.NECK, SemanticTag.NECKWEAR, SemanticTag.TOPWEAR, SemanticTag.HANDWEAR, SemanticTag.BOTTOMWEAR, SemanticTag.LEGWEAR, SemanticTag.FOOTWEAR))
	val BREATH = Component("breath", listOf(RigTuning.Group.BREATH), setOf(P.BODY_Z.raw, P.BREATH.raw), listOf("DeformBodyZBreath"),
		setOf(SemanticTag.NECK, SemanticTag.TOPWEAR))
	val HAIR = Component("hair", listOf(RigTuning.Group.HAIR), setOf(P.HAIR_FRONT.raw, P.HAIR_BACK.raw), listOf("DeformHair"),
		setOf(SemanticTag.FRONT_HAIR, SemanticTag.BACK_HAIR))
	/** The skeleton's own deformers; its bones' parameters are the skeleton's to switch, not a component's. */
	val SKELETON = Component("skeleton", emptyList(), emptySet(), listOf("DeformSkel"), emptySet())

	/** In panel order; a deformer is matched against the longer ID starts first, so the breath's warp is not the body's. */
	val all: List<Component> = listOf(HEAD, EYES, BROWS, MOUTH, BODY, BREATH, HAIR, SKELETON)

	/** Those a user can switch off: every one with parameters of its own. */
	val switchable: List<Component> get() = all.filter { it.parameters.isNotEmpty() }

	fun of(id: String): Component? = all.firstOrNull { it.id == id }

	/** The component that makes deformer [id], if the base rig makes it. */
	fun ofDeformer(id: String): Component? =
		all.flatMap { c -> c.deformers.map { it to c } }.sortedByDescending { it.first.length }.firstOrNull { id.startsWith(it.first) }?.second

	/** The component whose parameter [id] is. */
	fun ofParameter(id: String): Component? = all.firstOrNull { id in it.parameters }

	/** The components [model]'s base rig has: those with a deformer there or a parameter its meshes are keyed on. */
	fun present(model: PuppetModel): List<Component> {
		val deformers = model.deformers.map { it.id.raw }
		val keyed = (model.drawables.flatMap { it.geometryGrid?.axes.orEmpty() } + model.deformers.flatMap { d ->
			when (d) { is Deformer.Warp -> d.geometryGrid?.axes.orEmpty(); is Deformer.Rotation -> d.geometryGrid?.axes.orEmpty() }
		}).map { it.parameterId.raw }.toSet()
		return all.filter { c -> deformers.any { id -> ofDeformer(id) == c } || c.parameters.any { it in keyed } }
	}

	/**
	 * [model] (the base rig) with every parameter of the [disabled] components flattened at its default on all deformers
	 * and meshes, their geometry and their channels: the structure is kept, nothing they generated moves.
	 */
	fun gate(model: PuppetModel, disabled: Set<String>): PuppetModel {
		val flat = disabled.mapNotNull(::of).flatMap { it.parameters }.toSet()
		if (flat.isEmpty()) return model
		val defaults = model.parameters.associate { it.id.raw to it.default }
		fun <T> collapse(grid: KeyformGrid<T>?): KeyformGrid<T>? {
			var current = grid ?: return null
			for (p in flat) {
				val axis = current.axes.indexOfFirst { it.parameterId.raw == p }
				if (axis < 0) continue
				val keys = current.axes[axis].keys
				val keep = keys.indices.minBy { kotlin.math.abs(keys[it] - (defaults[p] ?: 0f)) }
				current = KeyformGrid(current.axes.filterIndexed { i, _ -> i != axis }, current.cells.filter { it.coordinate[axis] == keep }.map { cell ->
					KeyformCell(IntArray(cell.coordinate.size - 1) { if (it < axis) cell.coordinate[it] else cell.coordinate[it + 1] }, cell.form)
				})
			}
			return current
		}
		fun channels(grids: ChannelGrids) = if (grids.isEmpty) grids else ChannelGrids(grids.gridsByChannel.mapValues { collapse(it.value)!! })
		return model.copy(
			deformers = model.deformers.map { d ->
				when (d) {
					is Deformer.Warp -> d.copy(geometryGrid = collapse(d.geometryGrid), channelGrids = channels(d.channelGrids))
					is Deformer.Rotation -> d.copy(geometryGrid = collapse(d.geometryGrid), channelGrids = channels(d.channelGrids))
				}
			},
			drawables = model.drawables.map { it.copy(geometryGrid = collapse(it.geometryGrid), channelGrids = channels(it.channelGrids)) },
		)
	}
}
