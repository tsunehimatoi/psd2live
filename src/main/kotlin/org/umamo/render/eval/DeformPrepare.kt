package org.umamo.render.eval

import org.umamo.runtime.eval.WeightedCell
import org.umamo.runtime.eval.colorAt
import org.umamo.runtime.eval.gridCorners
import org.umamo.runtime.eval.scalarAt
import org.umamo.runtime.eval.scalarOrNull
import org.umamo.runtime.model.ChannelValue
import org.umamo.runtime.model.ColorRgb
import org.umamo.runtime.model.DEFAULT_DRAW_ORDER
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.FormChannel
import org.umamo.runtime.model.GluePair
import org.umamo.runtime.model.KeyableTarget
import org.umamo.runtime.model.KeyformOwner
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PartId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.RenderGroup

/**
 * Backend-neutral per-pose deform inputs for one drawable - the cheap output of [preparePose]: which
 * keyform cells are active and their weights ([corners]), the mesh's baked parent-deformer transform
 * ([parentWorld]; null for a direct, deformer-less mesh), and the blended scalar attributes. [corners]
 * null means the mesh is hidden at this pose; [isParented] with a null [parentWorld] means a hidden
 * ancestor deformer (also hidden). Nothing here is per-vertex - the CPU finishes it in [applyCpuDeform],
 * and the GPU path uploads the same corners (→ weight uniforms) + baked transform instead.
 */
internal class DrawableDeformInputs(
	val drawableId: DrawableId,
	val corners: List<WeightedCell>?,
	val parentWorld: DeformerWorld?,
	val isParented: Boolean,
	val drawOrder: Float,
	val opacity: Float,
	val multiplyColor: ColorRgb = ColorRgb.MultiplyIdentity,
	val screenColor: ColorRgb = ColorRgb.ScreenIdentity,
	val blend: MeshBlendState? = null,
)

/**
 * The corner set of an entity with no keyform grid: one full-weight corner into a cell map that holds
 * nothing, so every blend contributes zero and the entity evaluates at its rest state.
 *
 * Shared rather than rebuilt per drawable per frame, and deliberately NOT an empty list - an empty corner
 * set would make the weights sum to zero, which is a different thing entirely.
 */
private val REST_CORNERS: List<WeightedCell> = listOf(WeightedCell(0, 1f))

/** A glue affecter with its pose-blended weld [intensity] already resolved, so the apply pass is param-free. */
internal class GlueInputs(
	val meshA: DrawableId,
	val meshB: DrawableId,
	val pairs: List<GluePair>,
	val intensity: Float,
)

/**
 * The full per-pose, backend-neutral deform inputs ([preparePose]'s output): per-drawable weights + baked
 * transforms and the resolved glues. The CPU finishes it per vertex via [applyCpuDeform]; the GPU path
 * uploads the same data as uniforms instead of re-uploading every deformed vertex - that re-use is exactly
 * why the deformation is split into a cheap "prepare" and a backend-specific "apply".
 */
internal class PoseDeformInputs(
	val drawables: List<DrawableDeformInputs>,
	val glues: List<GlueInputs>,
	/** Pose-blended draw order per grouped part (animated part order); missing → the static value. */
	val partDrawOrders: Map<PartId, Float> = emptyMap(),
	/**
	 * Pose-blended composite channels per ISOLATED part (opacity, multiply/screen colors), present
	 * for every isolated group - a static or out-of-range part carries its PartComposite fallbacks,
	 * so the composite pass never has to re-derive them.
	 */
	val partCompositeStates: Map<PartId, PartRenderState> = emptyMap(),
)

/**
 * Computes the cheap, backend-neutral deform inputs for [model] at [parameters]: the multilinear keyform
 * weights per mesh and each mesh's baked deformer-cascade transform - never per-vertex geometry. Shared
 * verbatim by the CPU ([applyCpuDeform]) and the eventual GPU apply paths.
 *
 * @param PuppetModel model      The rig.
 * @param Map         parameters Parameter id → value (partial; the rest default).
 * @param Map         channelOverrides Pending unkeyed channel edits, which win over the stored value - how
 *   a value the user typed but has not keyed shows in the viewport without entering the document.
 * @return PoseDeformInputs The backend-neutral per-pose inputs.
 */
internal fun preparePose(
	model: PuppetModel,
	parameters: Map<ParameterId, Float>,
	channelOverrides: Map<KeyableTarget, ChannelValue> = emptyMap(),
): PoseDeformInputs {
	val defaults = model.parameters.associate { it.id to it.default }
	val paramValue: (ParameterId) -> Float = { parameters[it] ?: defaults[it] ?: 0f }
	val defaultValue: (ParameterId) -> Float = { defaults[it] ?: 0f }
	// Null in the steady state (no pending unkeyed edit), so every lookup below short-circuits before
	// constructing its KeyableTarget key - the per-frame path otherwise allocated a handful of key
	// objects per entity purely to probe an empty map.
	val overrides = channelOverrides.takeIf { pending -> pending.isNotEmpty() }
	val deformerWorlds = buildDeformerWorlds(model.deformers, paramValue, defaultValue, channelOverrides)
	val drawables = ArrayList<DrawableDeformInputs>(model.drawables.size)
	for (drawable in model.drawables) {
		if (drawable.mesh?.positions == null) {
			continue
		}
		// An UNKEYED drawable renders at its rest mesh rather than vanishing: one full-weight corner into
		// an empty cell map contributes no deltas, and every blend site already treats a missing cell as a
		// zero contribution. Null corners keep meaning HIDDEN (a pose outside a keyed grid's range), so
		// "no grid" and "out of range" stay distinguishable - they are opposite outcomes.
		val grid = drawable.geometryGrid
		val corners = if (grid != null) gridCorners(grid, paramValue) else REST_CORNERS
		val parentDeformerId = drawable.parentDeformerId
		val parentWorld = parentDeformerId?.let { deformerWorlds[it] }
		val blend = meshBlendState(drawable, paramValue, defaultValue)
		// Each channel resolves against its own track and falls back to the drawable's static; a channel
		// out of range never hides, which is the geometry grid's decision alone (corners == null above).
		val drawableOwner = KeyformOwner.Drawable(drawable.id)
		var drawOrder =
			drawable.channelGrids.scalarAt(
				FormChannel.DRAW_ORDER,
				drawable.drawOrder,
				paramValue,
				overrides?.get(KeyableTarget(drawableOwner, FormChannel.DRAW_ORDER)),
			)
		var opacity =
			drawable.channelGrids.scalarAt(
				FormChannel.OPACITY,
				drawable.opacity,
				paramValue,
				overrides?.get(KeyableTarget(drawableOwner, FormChannel.OPACITY)),
			)
		var multiplyColor =
			drawable.channelGrids.colorAt(
				FormChannel.MULTIPLY_COLOR,
				drawable.multiplyColor,
				paramValue,
				overrides?.get(KeyableTarget(drawableOwner, FormChannel.MULTIPLY_COLOR)),
			)
		var screenColor =
			drawable.channelGrids.colorAt(
				FormChannel.SCREEN_COLOR,
				drawable.screenColor,
				paramValue,
				overrides?.get(KeyableTarget(drawableOwner, FormChannel.SCREEN_COLOR)),
			)
		// Blend shapes: additive deltas on every channel the record carries, not just the scalars.  Each
		// contribution's form holds its stored delta plus the grid-at-default reference (added at import),
		// so subtracting that reference back out here recovers the delta exactly.  Opacity and the colours
		// clamp to [0,1] only AFTER summing - clamping per contribution would bias a record whose
		// neighbours pull the other way.  Draw order is left unrounded (the Umamo C++ Runtime rounds
		// (int)(0.001+v) at sort time; Umamo sorts floats - MOC3.md §5.6).
		if (blend != null) {
			for (contribution in blend.contributions) {
				val form = contribution.form
				drawOrder += contribution.weight * (form.drawOrder - blend.referenceDrawOrder)
				opacity += contribution.weight * (form.opacity - blend.referenceOpacity)
				multiplyColor =
					ColorRgb(
						multiplyColor.red + contribution.weight * (form.multiplyColor.red - blend.referenceMultiplyColor.red),
						multiplyColor.green +
							contribution.weight * (form.multiplyColor.green - blend.referenceMultiplyColor.green),
						multiplyColor.blue +
							contribution.weight * (form.multiplyColor.blue - blend.referenceMultiplyColor.blue),
					)
				screenColor =
					ColorRgb(
						screenColor.red + contribution.weight * (form.screenColor.red - blend.referenceScreenColor.red),
						screenColor.green + contribution.weight * (form.screenColor.green - blend.referenceScreenColor.green),
						screenColor.blue + contribution.weight * (form.screenColor.blue - blend.referenceScreenColor.blue),
					)
			}
			opacity = opacity.coerceIn(0f, 1f)
			multiplyColor = multiplyColor.coerceToUnit()
			screenColor = screenColor.coerceToUnit()
		}
		// Then the parent deformer chain's accumulated channels. A deformer's opacity multiplies, its
		// multiply color multiplies, its screen color screens - each already folded over every ancestor
		// deformer by buildDeformerWorlds, so one composition here covers the whole chain. Clamped
		// because a blended color can leave [0,1] at the ends of a keyed range.
		parentWorld?.let { world ->
			opacity *= world.accumulatedOpacity
			multiplyColor =
				ColorRgb(
					(multiplyColor.red * world.accumulatedMultiplyColor.red).coerceIn(0f, 1f),
					(multiplyColor.green * world.accumulatedMultiplyColor.green).coerceIn(0f, 1f),
					(multiplyColor.blue * world.accumulatedMultiplyColor.blue).coerceIn(0f, 1f),
				)
			screenColor =
				ColorRgb(
					screenCompose(screenColor.red, world.accumulatedScreenColor.red).coerceIn(0f, 1f),
					screenCompose(screenColor.green, world.accumulatedScreenColor.green).coerceIn(0f, 1f),
					screenCompose(screenColor.blue, world.accumulatedScreenColor.blue).coerceIn(0f, 1f),
				)
		}
		drawables.add(
			DrawableDeformInputs(
				drawableId = drawable.id,
				corners = corners,
				parentWorld = parentWorld,
				isParented = parentDeformerId != null,
				drawOrder = drawOrder,
				opacity = opacity,
				multiplyColor = multiplyColor,
				screenColor = screenColor,
				blend = blend,
			),
		)
	}
	val glues =
		model.glues.map { glue ->
			GlueInputs(
				meshA = glue.meshA,
				meshB = glue.meshB,
				pairs = glue.pairs,
				intensity =
					glue.channelGrids.scalarAt(
						FormChannel.GLUE_INTENSITY,
						glue.intensity,
						paramValue,
						overrides?.get(KeyableTarget(KeyformOwner.Glue(glue.meshA, glue.meshB), FormChannel.GLUE_INTENSITY)),
					),
			)
		}
	// Blend each group's (animated) part draw order, so the renderer can position whole groups per
	// pose - part groups with parameter-driven draw order swap front/back as their parameter moves.
	// The same walk blends each ISOLATED group's composite channels (opacity, multiply/screen
	// colors), falling back to the PartComposite statics when the part has no grid or the axis is
	// out of range.
	val partDrawOrders = HashMap<PartId, Float>()
	val partCompositeStates = HashMap<PartId, PartRenderState>()
	// The render tree carries a group's tracks but not its part's blend records, so the blend pass
	// needs the part itself.  Taken from the model, which memoizes it: this runs once per rendered
	// frame, so building the map here would allocate one entry per part per frame for data that is
	// immutable across all of them.
	val partsById = model.partById

	fun blendGroupStates(group: RenderGroup) {
		val partId = group.partId
		val channels = group.channelGrids
		val partOwner = partId?.let { KeyformOwner.Part(it) }
		if (partId != null && partOwner != null) {
			// Null when the part has no draw-order track or the pose is out of its range; with no blend
			// record contributing either, the map entry is then left ABSENT so the renderer keeps the
			// part's static slot - the map's sparseness is the signal.
			val tracked =
				channels.scalarOrNull(
					FormChannel.DRAW_ORDER,
					paramValue,
					overrides?.get(KeyableTarget(partOwner, FormChannel.DRAW_ORDER)),
				)
			// A part-target blend record moves the slot too, and it can do so on a part with no track at
			// all - so an entry appears whenever EITHER contributes, with the static standing in as the
			// base when only the record does.
			val blendDelta = partsById[partId]?.let { part -> partBlendDrawOrderDelta(part, paramValue, defaultValue) }
			if (tracked != null || blendDelta != null) {
				val base = tracked ?: partsById[partId]?.drawOrder?.toFloat() ?: DEFAULT_DRAW_ORDER.toFloat()
				partDrawOrders[partId] = base + (blendDelta ?: 0f)
			}
		}
		val composite = group.composite
		if (partId != null && composite != null) {
			// Each composite channel resolves independently against its own static, so an isolated part
			// can key opacity alone without dragging its tints onto the same axes.
			partCompositeStates[partId] =
				PartRenderState(
					channels.scalarAt(
						FormChannel.OPACITY,
						composite.opacity,
						paramValue,
						partOwner?.let { owner -> overrides?.get(KeyableTarget(owner, FormChannel.OPACITY)) },
					),
					channels.colorAt(
						FormChannel.MULTIPLY_COLOR,
						composite.multiplyColor,
						paramValue,
						partOwner?.let { owner -> overrides?.get(KeyableTarget(owner, FormChannel.MULTIPLY_COLOR)) },
					),
					channels.colorAt(
						FormChannel.SCREEN_COLOR,
						composite.screenColor,
						paramValue,
						partOwner?.let { owner -> overrides?.get(KeyableTarget(owner, FormChannel.SCREEN_COLOR)) },
					),
				)
		}
		for (child in group.children) {
			if (child is RenderGroup) {
				blendGroupStates(child)
			}
		}
	}
	blendGroupStates(model.renderRoot)
	return PoseDeformInputs(drawables, glues, partDrawOrders, partCompositeStates)
}

/**
 * Finishes [inputs] on the CPU: per drawable, blends the active corners into local positions, pushes them
 * through the baked parent transform, negates Y, then welds the glue pairs in place. Output is identical
 * to evaluating the model in one pass.
 *
 * @param PuppetModel      model  The rig (for each drawable's base positions + grid).
 * @param PoseDeformInputs inputs The prepared per-pose inputs.
 * @return DeformedGeometry World positions + scalars per visible drawable.
 */
internal fun applyCpuDeform(model: PuppetModel, inputs: PoseDeformInputs): DeformedGeometry {
	val drawableById = model.drawables.associateBy { it.id }
	val worldPositions = HashMap<DrawableId, FloatArray>(inputs.drawables.size)
	val drawOrders = HashMap<DrawableId, Float>(inputs.drawables.size)
	val opacities = HashMap<DrawableId, Float>(inputs.drawables.size)
	for (drawableInputs in inputs.drawables) {
		val corners = drawableInputs.corners ?: continue
		// A parented mesh whose ancestor deformer is hidden produces no geometry (the cascade omitted it).
		if (drawableInputs.isParented && drawableInputs.parentWorld == null) {
			continue
		}
		val drawable = drawableById[drawableInputs.drawableId] ?: continue
		val base = drawable.mesh?.positions ?: continue
		// A null grid is an unkeyed drawable, which deforms to its rest mesh - see preparePose.
		worldPositions[drawableInputs.drawableId] =
			deformMeshWorldFromCorners(drawable.geometryGrid, base, corners, drawableInputs.parentWorld, drawableInputs.blend)
		drawOrders[drawableInputs.drawableId] = drawableInputs.drawOrder
		opacities[drawableInputs.drawableId] = drawableInputs.opacity
	}
	applyGluesResolved(inputs.glues, worldPositions)
	return DeformedGeometry(worldPositions, drawOrders, opacities)
}

/**
 * Seam-welds each glue's vertex pairs in place from pre-resolved intensities: `A' = A + (B−A)·wA·i`,
 * `B' = B + (A−B)·wB·i` (the Umamo C++ Runtime's `applyGlue`, on the Y-flipped world buffers).
 *
 * @param List<GlueInputs>            glues          The resolved glue affecters.
 * @param Map<DrawableId,FloatArray> worldPositions Per-drawable deformed positions (mutated).
 */
internal fun applyGluesResolved(glues: List<GlueInputs>, worldPositions: Map<DrawableId, FloatArray>) {
	for (glue in glues) {
		val vertsA = worldPositions[glue.meshA] ?: continue
		val vertsB = worldPositions[glue.meshB] ?: continue
		val intensity = glue.intensity
		for (pair in glue.pairs) {
			val indexA = pair.indexA * 2
			val indexB = pair.indexB * 2
			if (indexA + 1 >= vertsA.size || indexB + 1 >= vertsB.size) {
				continue
			}
			val ax = vertsA[indexA]
			val ay = vertsA[indexA + 1]
			val bx = vertsB[indexB]
			val by = vertsB[indexB + 1]
			vertsA[indexA] = (bx - ax) * pair.weightA * intensity + ax
			vertsA[indexA + 1] = (by - ay) * pair.weightA * intensity + ay
			vertsB[indexB] = (ax - bx) * pair.weightB * intensity + bx
			vertsB[indexB + 1] = (ay - by) * pair.weightB * intensity + by
		}
	}
}