package org.umamo.render

import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.render.eval.DeformedGeometry
import org.umamo.render.eval.drawableSpaceMapping
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.MeshDeltaForm
import org.umamo.runtime.model.MeshForm
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel
import kotlin.math.max
import kotlin.math.min

/** The warp inverse's neutral seed: the middle of the normalized [0,1] lattice. */
private const val LATTICE_CENTRE: Float = 0.5f

/**
 * Rewrites each drawable's rest mesh to its default-pose canvas-space geometry, matching the CMO3
 * convention the editor is built on.
 *
 * The runtime convention (set by CMO3, verified against the corpus): Drawable.mesh.positions is the
 * EDITABLE canvas-space geometry - what the gizmo overlay edits and the viewport maps gestures into -
 * while each MeshForm's absolute positions (base + delta) live in the drawable's PARENT-DEFORMER
 * space.  The deformation eval only ever sees `base + Σ wᵢ·Δᵢ` with weights summing to 1, so the base
 * cancels and this mixed-space encoding is exact, not an approximation.
 *
 * A `.moc3` stores only the parent-space keyforms, so :interop's `Moc3Import` can give
 * a warp/rotation-parented drawable nothing better than a parent-local base.  This pass finishes the
 * import: it evaluates the default pose through the validated deformer cascade (glue excluded - the
 * weld is a render-time effect, not rest geometry), takes the pre-Y-negation canvas positions as the
 * new base, and re-expresses every keyform delta against it so `base + delta` still reconstructs the
 * same parent-space absolutes.  Drawables the raw default pose hides (a keyform axis whose keys do
 * not bracket the driving parameter's default - the toggle-part authoring pattern) get a second
 * evaluation at a pose with those parameters clamped into their axes' key ranges, so their rest
 * meshes still land in canvas space.  It lives in `:render` (not `:runtime`) because it is the
 * evaluator that turns parent-space keyforms into canvas geometry.
 *
 * @param PuppetModel model An imported model whose rest meshes may be parent-local (a MOC3 import).
 * @return PuppetModel The model with canvas-space rest meshes (a drawable that stays hidden even at
 *                     the clamped pose keeps its parent-local base, which still evaluates correctly).
 */
fun restMeshesToCanvasSpace(model: PuppetModel): PuppetModel {
	val preGlueModel = model.copy(glues = emptyList())
	val evaluator = CpuDeformationEvaluator()
	val defaultPose = evaluator.evaluate(preGlueModel, emptyMap())

	// Second chance for drawables absent from the raw default pose: clamp every involved parameter
	// into its axes' key ranges and evaluate once more.  Only the still-missing drawables read from
	// this pose, so in-range drawables keep their true default geometry.
	val fallback = defaultPoseFallbackFor(model, defaultPose)
	val clampedPose = fallback?.let { rescue -> evaluator.evaluate(preGlueModel, rescue.clampedDefaults) }

	val drawables =
		model.drawables.map { drawable ->
			val mesh = drawable.mesh ?: return@map drawable
			val worldPositions =
				defaultPose.worldPositions[drawable.id]
					?: clampedPose?.worldPositions?.get(drawable.id)
					?: return@map drawable
			if (worldPositions.size != mesh.positions.size) {
				return@map drawable
			}
			// All-or-nothing: a size-mismatched delta array holds ABSOLUTE positions by the importer's
			// fallback convention, so rebasing any prefix of it would double-count the base and mix two
			// spaces in one array.  A drawable carrying such a cell keeps its whole grid AND base
			// untouched - partially rewriting either would corrupt what malformed data still encodes.
			// Blend-shape forms are rest-relative like the grid cells, so they gate (and rebase) the
			// same way - leaving them on the old base while the grid moves would shift every blend
			// contribution by exactly (new base - old base).
			val anyCellMismatches =
				(drawable.geometryGrid?.cells?.any { cell -> cell.form.positionDeltas.size != mesh.positions.size } ?: false) ||
					drawable.blendShapes.any { binding ->
						binding.forms.any { form -> form != null && form.positionDeltas.size != mesh.positions.size }
					}
			if (anyCellMismatches) {
				return@map drawable
			}
			// The eval negates Y into world space; canvas space is the pre-negation Y-down convention.
			val canvasBase =
				FloatArray(worldPositions.size) { coordIndex ->
					if (coordIndex % 2 == 1) -worldPositions[coordIndex] else worldPositions[coordIndex]
				}
			// Only the geometry rebases - and since the split that is structural rather than something the
			// copy has to remember: the channel tracks are a separate field and are simply not touched.
			val rebasedGeometry =
				drawable.geometryGrid?.let { grid ->
					KeyformGrid(
						grid.axes,
						grid.cells.map { cell ->
							val oldDeltas = cell.form.positionDeltas
							KeyformCell(
								cell.coordinate,
								MeshDeltaForm(
									FloatArray(oldDeltas.size) { coordIndex ->
										(mesh.positions[coordIndex] + oldDeltas[coordIndex]) - canvasBase[coordIndex]
									},
								),
							)
						},
					)
				}
			val rebasedBlendShapes =
				drawable.blendShapes.map { binding ->
					binding.copy(
						forms =
							binding.forms.map { form ->
								form?.let { meshForm ->
									MeshForm(
										FloatArray(meshForm.positionDeltas.size) { coordIndex ->
											(mesh.positions[coordIndex] + meshForm.positionDeltas[coordIndex]) - canvasBase[coordIndex]
										},
										meshForm.drawOrder,
										meshForm.opacity,
										meshForm.multiplyColor,
										meshForm.screenColor,
									)
								}
							},
					)
				}
			drawable.copy(
				mesh = DrawableMesh(canvasBase, mesh.uvs, mesh.indices),
				geometryGrid = rebasedGeometry,
				blendShapes = rebasedBlendShapes,
			)
		}
	return model.copy(drawables = drawables)
}

/**
 * The drawables the raw default pose hides, paired with the clamped pose that rescues them.
 *
 * @property Set<DrawableId>          hiddenIds       Drawables with a mesh but no default-pose geometry.
 * @property Map<ParameterId, Float>  clampedDefaults The pose to evaluate those drawables at.
 */
private class DefaultPoseFallback(
	val hiddenIds: Set<DrawableId>,
	val clampedDefaults: Map<ParameterId, Float>,
)

/**
 * Which drawables need the clamped second-chance pose, and what that pose is.
 *
 * Both directions of the canvas-space conversion ask this, and they MUST agree: the forward pass maps
 * such a drawable through the clamped pose, so an export that inverted it through the raw default
 * would be undoing a transform that was never applied.
 *
 * No `keyforms != null` gate: an unkeyed drawable evaluates at its rest mesh rather than being skipped,
 * so one still absent from the default pose is genuinely hidden (a hidden ancestor deformer) and
 * deserves the same second chance as any other.
 *
 * @param PuppetModel      model       The model being converted.
 * @param DeformedGeometry defaultPose Its evaluation at the raw default parameters.
 * @return DefaultPoseFallback? The hidden set and their pose, or null when nothing is hidden.
 */
private fun defaultPoseFallbackFor(model: PuppetModel, defaultPose: DeformedGeometry): DefaultPoseFallback? {
	val hiddenAtDefault =
		model.drawables.filter { drawable ->
			drawable.mesh != null && defaultPose.worldPositions[drawable.id] == null
		}.map { drawable -> drawable.id }
	if (hiddenAtDefault.isEmpty()) {
		return null
	}
	return DefaultPoseFallback(hiddenAtDefault.toSet(), clampedDefaultsFor(model, hiddenAtDefault))
}

/**
 * The export's space seam for [puppet]: inverts a drawable's canvas-space rest mesh back through its
 * parent-deformer chain, at the same pose [restMeshesToCanvasSpace] mapped it forward with.
 *
 * That pose is the neutral one for most drawables - it is the pose the rest mesh is defined at, so
 * inverting there is the forward pass read backwards.  A drawable the raw default hides went forward
 * through the clamped second-chance pose instead, so it is inverted through that same clamped pose;
 * using the raw default for it would undo a transform that was never applied.
 *
 * A drawable the chain cannot map (a deformer with no lattice anywhere) returns null, which the export
 * turns into a notice rather than a silently mis-scaled mesh.
 *
 * @param PuppetModel puppet The rig being exported.
 * @return Function2 The seam: drawable id plus interleaved canvas-space positions to parent-space
 *                   positions, or null when the chain cannot invert.
 */
fun canvasToParentSpaceFor(puppet: PuppetModel): (DrawableId, FloatArray) -> FloatArray? {
	// Resolved once per export rather than per drawable: the evaluation is the expensive part and the
	// answer is a property of the model, not of whichever drawable is being written.
	val preGlueModel = puppet.copy(glues = emptyList())
	val defaultPose = CpuDeformationEvaluator().evaluate(preGlueModel, emptyMap())
	val fallback = defaultPoseFallbackFor(puppet, defaultPose)

	return { drawableId, positions ->
		val pose =
			if (fallback != null && drawableId in fallback.hiddenIds) {
				fallback.clampedDefaults
			} else {
				emptyMap()
			}
		drawableSpaceMapping(puppet, pose, drawableId)?.let { mapping ->
			// worldToLocal expects the renderer's Y-negated world space, and every vertex is solved.
			val world = FloatArray(positions.size) { index -> if (index % 2 == 0) positions[index] else -positions[index] }
			// The seed matters only for the warp inverse, and it must be a LATTICE UV, not a canvas
			// coordinate: seeding Newton with the canvas-space value starts it hundreds of units outside
			// the [0,1] lattice, where the damped step cannot walk back.  The lattice center is the
			// neutral seed - at most half a lattice away from any target, which the damped step covers.
			val seed = FloatArray(positions.size) { LATTICE_CENTRE }
			mapping.worldToLocal(world, seed, positions.indices.step(2).map { index -> index / 2 }.toSet())
		}
	}
}

/**
 * The clamped evaluation pose for drawables hidden at the raw default: every parameter driving a
 * hidden drawable's own keyform grid or any grid on its ancestor deformer chain is coerced into the
 * intersection of those axes' key ranges (the tightest span that satisfies every involved axis).
 * When the intersection is empty (conflicting axes - pathological), the lower bound wins; unlisted
 * parameters keep their defaults via the evaluator's fallback.
 *
 * @param PuppetModel      model     The imported model.
 * @param List<DrawableId> hiddenIds The drawables absent from the raw default pose.
 * @return Map<ParameterId, Float> The clamped values for the involved parameters only.
 */
private fun clampedDefaultsFor(model: PuppetModel, hiddenIds: List<DrawableId>): Map<ParameterId, Float> {
	val drawableById = model.drawables.associateBy { it.id }
	val deformerById = model.deformers.associateBy { it.id }
	val lowerByParameter = HashMap<ParameterId, Float>()
	val upperByParameter = HashMap<ParameterId, Float>()

	fun addAxes(grid: KeyformGrid<*>?) {
		for (axis in grid?.axes.orEmpty()) {
			if (axis.keys.isEmpty()) {
				continue
			}
			val firstKey = axis.keys.first()
			val lastKey = axis.keys.last()
			lowerByParameter[axis.parameterId] = max(lowerByParameter[axis.parameterId] ?: firstKey, firstKey)
			upperByParameter[axis.parameterId] = min(upperByParameter[axis.parameterId] ?: lastKey, lastKey)
		}
	}

	for (drawableId in hiddenIds) {
		val drawable = drawableById[drawableId] ?: continue
		addAxes(drawable.geometryGrid)
		var parentId = drawable.parentDeformerId
		var chainSteps = 0
		while (parentId != null && chainSteps <= model.deformers.size) {
			val deformer = deformerById[parentId] ?: break
			// Geometry AND channel tracks: a deformer keyed only on, say, opacity still constrains which
			// parameters have to be clamped for the second-chance default-pose evaluation.
			when (deformer) {
				is Deformer.Warp -> addAxes(deformer.geometryGrid)
				is Deformer.Rotation -> addAxes(deformer.geometryGrid)
			}
			deformer.channelGrids.gridsByChannel.values.forEach { track: KeyformGrid<*> -> addAxes(track) }
			parentId = deformer.parent
			chainSteps++
		}
	}

	val defaultByParameter = model.parameters.associate { parameter -> parameter.id to parameter.default }
	return buildMap {
		for ((parameterId, lowerBound) in lowerByParameter) {
			val upperBound = upperByParameter[parameterId] ?: lowerBound
			val defaultValue = defaultByParameter[parameterId] ?: 0f
			val clampedValue = if (lowerBound <= upperBound) defaultValue.coerceIn(lowerBound, upperBound) else lowerBound
			put(parameterId, clampedValue)
		}
	}
}