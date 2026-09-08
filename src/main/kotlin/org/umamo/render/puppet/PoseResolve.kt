package org.umamo.render.puppet

import org.umamo.render.eval.DeformerWorld
import org.umamo.render.eval.MeshBlendState
import org.umamo.render.eval.PoseDeformInputs
import org.umamo.render.eval.RenderPlanComposite
import org.umamo.render.eval.RenderPlanDrawable
import org.umamo.render.eval.RenderPlanNode
import org.umamo.render.eval.flattenRenderPlan
import org.umamo.render.eval.paintOrder
import org.umamo.render.eval.renderPlan
import org.umamo.render.glsl.MAX_GLUES
import org.umamo.runtime.eval.WeightedCell
import org.umamo.runtime.model.ColorRgb
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.RenderGroup

/**
 * One drawable's resolved per-pose deform state - what the backend must hand its shaders to draw it.
 *
 * @property DrawableId    drawableId  The drawable.
 * @property List          corners     The active keyform-grid corners and their blend weights.
 * @property DeformerWorld parentWorld The baked parent-deformer transform, or null for a direct mesh.
 * @property Float         opacity     The pose-blended opacity.
 */
internal class PosedDrawable(
	val drawableId: DrawableId,
	val corners: List<WeightedCell>,
	val parentWorld: DeformerWorld?,
	val opacity: Float,
	val multiplyColor: ColorRgb = ColorRgb.MultiplyIdentity,
	val screenColor: ColorRgb = ColorRgb.ScreenIdentity,
	val blend: MeshBlendState? = null,
)

/**
 * A pose resolved against what a backend actually has resident.
 *
 * @property Map        posed           Every drawable that produced geometry this pose, in
 *   [PoseDeformInputs.drawables] order.  This is a SUPERSET of what is drawn: it includes index-less glue
 *   anchors and hidden mask sources, both of which must still deform.
 * @property List       renderPlan      The drawn drawables in resolved back-to-front order WITH the
 *   isolated group boundaries preserved ([RenderPlanComposite] nodes) - what a compositing renderer
 *   walks.  Filtered like [drawOrder]; a composite node whose subtree filtered to empty is dropped.
 * @property List       drawOrder       The drawn drawables, back-to-front (last = front): posed AND
 *   renderable AND shown, in resolved render order.  Always equals the flattened [renderPlan].
 * @property FloatArray glueIntensities Per-glue weld intensity by glue index, length [MAX_GLUES].
 */
internal class ResolvedPose(
	val posed: Map<DrawableId, PosedDrawable>,
	val renderPlan: List<RenderPlanNode>,
	val drawOrder: List<DrawableId>,
	val glueIntensities: FloatArray,
)

/**
 * Resolves a prepared pose against a backend's residency: which drawables deform, which are actually
 * drawn and in what order, and each glue's weld intensity.
 *
 * Backend-neutral - it decides WHAT to draw, never how.  The one thing it needs from the backend is
 * [renderableById], because "posed" and "drawn" are genuinely different questions and only the backend
 * knows the answer to the second.
 *
 * @param PoseDeformInputs inputs         The prepared per-pose inputs.
 * @param Map              renderableById Resident drawable id → whether it carries triangles.  The KEYS
 *   are the residency set (a drawable the backend never uploaded cannot pose); the VALUE says whether it
 *   draws anything.  The two differ: an index-less glue anchor is resident and poses - its deformed
 *   positions are a weld partner - but renders nothing.
 * @param Set              shownIds       The resolved Parts-panel visibility cascade.
 * @param List             baseOrder      The model's flat drawable order, for a group-less model.
 * @param RenderGroup      renderRoot     The draw-order group tree.
 * @param FloatArray       glueIntensities A caller-owned array of length [MAX_GLUES] this fills in place
 *   (and returns in [ResolvedPose.glueIntensities]), so a per-pose render allocates no intensity buffer.
 * @return ResolvedPose The pose, resolved.
 */
internal fun resolvePose(
	inputs: PoseDeformInputs,
	renderableById: Map<DrawableId, Boolean>,
	shownIds: Set<DrawableId>,
	baseOrder: List<DrawableId>,
	renderRoot: RenderGroup,
	glueIntensities: FloatArray,
): ResolvedPose {
	// LinkedHashMap: iteration follows inputs.drawables order, which the backend's per-pose uploads walk.
	val posed = LinkedHashMap<DrawableId, PosedDrawable>(inputs.drawables.size)
	val drawOrderById = HashMap<DrawableId, Float>(inputs.drawables.size)
	for (drawableInputs in inputs.drawables) {
		if (drawableInputs.drawableId !in renderableById) {
			continue // not resident: the backend has nothing to draw it with
		}
		val corners = drawableInputs.corners ?: continue // hidden at this pose
		if (drawableInputs.isParented && drawableInputs.parentWorld == null) {
			continue // a hidden ancestor deformer produced no transform, so the mesh has no geometry
		}
		posed[drawableInputs.drawableId] =
			PosedDrawable(
				drawableInputs.drawableId,
				corners,
				drawableInputs.parentWorld,
				drawableInputs.opacity,
				drawableInputs.multiplyColor,
				drawableInputs.screenColor,
				drawableInputs.blend,
			)
		drawOrderById[drawableInputs.drawableId] = drawableInputs.drawOrder
	}

	// Weld intensity per pose, gated on BOTH meshes being posed. Pass 1 only writes a posed glue mesh's
	// region of the shared position store; if a partner is unposed its region is uninitialised, so welding
	// toward it would read garbage (random spikes on a live, dirty GPU). A zero intensity makes the weld
	// skip the partner read entirely - matching the CPU applyGluesResolved, which skips a glue whose
	// partner produced no geometry. Note this asks whether the partner POSED, not whether it draws: an
	// index-less anchor is a legitimate partner.
	glueIntensities.fill(1f) // slots beyond glues.size stay 1f and are never read by the shader
	for ((glueIndex, glue) in inputs.glues.withIndex()) {
		if (glueIndex >= MAX_GLUES) {
			continue // beyond the shader's glueIntensity[] array: this glue renders unwelded
		}
		val bothPosed = glue.meshA in posed && glue.meshB in posed
		glueIntensities[glueIndex] = if (bothPosed) glue.intensity else 0f
	}

	// Hierarchical render plan over the draw-order group tree (with per-pose animated part order); the
	// flat base order when the model carries no groups.  The drawn-filter applies to the PLAN (an
	// isolated subtree that filters to empty disappears with its composite), and the flat draw order
	// is its flattening - the two views can never disagree.
	val plan =
		if (renderRoot.children.isEmpty()) {
			paintOrder(baseOrder, drawOrderById).map(::RenderPlanDrawable)
		} else {
			renderPlan(renderRoot, drawOrderById, inputs.partDrawOrders)
		}
	val drawnPlan = filterPlan(plan) { it in posed && renderableById[it] == true && it in shownIds }
	return ResolvedPose(posed, drawnPlan, flattenRenderPlan(drawnPlan), glueIntensities)
}

/**
 * Filters a render plan's drawable leaves by [isDrawn], dropping a composite node whose whole
 * subtree filtered away (compositing an empty layer would be a wasted pass, and the visibility
 * cascade lands here - a hidden part's drawables all fail the shown filter).
 *
 * @param List<RenderPlanNode> nodes   The plan nodes.
 * @param Function             isDrawn Whether a drawable is actually drawn this pose.
 * @return List<RenderPlanNode> The filtered plan.
 */
private fun filterPlan(nodes: List<RenderPlanNode>, isDrawn: (DrawableId) -> Boolean): List<RenderPlanNode> =
	nodes.mapNotNull { node ->
		when (node) {
			is RenderPlanDrawable -> node.takeIf { isDrawn(it.id) }
			is RenderPlanComposite -> {
				val children = filterPlan(node.children, isDrawn)
				if (children.isEmpty()) {
					null
				} else {
					RenderPlanComposite(node.partId, node.composite, children)
				}
			}
		}
	}