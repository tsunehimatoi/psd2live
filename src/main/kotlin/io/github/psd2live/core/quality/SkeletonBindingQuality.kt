package io.github.psd2live.core.quality

import io.github.psd2live.core.SkeletonCanvasSkin
import io.github.psd2live.core.SkeletonRig
import io.github.psd2live.core.SkeletonSpec
import kotlinx.serialization.json.*
import org.umamo.runtime.model.PuppetModel

/**
 * Stable codes for whether the skeleton moves what it should. The rule alone decides severity and category; callers
 * and the UI read the code, never a localized message.
 */
enum class SkeletonBindingRule(val severity: String, val category: String) {
	/** A mesh bound to a limb bone that no bone of its limb moves: not hung under them, and not skinned to them. */
	SKELETON_BINDING_INEFFECTIVE("warning", "quality"),
	/**
	 * A mesh on the breath warp beside the torso warp instead of under it, keyed on neither body half: the upper and
	 * lower body never bend it.
	 */
	SKELETON_TORSO_BYPASSED("warning", "quality"),
	/** A bone binds a mesh the rig does not have. */
	SKELETON_BINDING_MISSING("info", "validity"),
}

/** One finding: [mesh] and, for a binding, the [bone] that binds it. */
data class SkeletonBindingIssue(val rule: SkeletonBindingRule, val mesh: String, val bone: String? = null)

/**
 * Observation report (version 1) of the skeleton's reach over the finished rig: bound meshes no bone of their limb
 * moves, meshes on the breath warp that bypass the torso warp, and bindings of meshes the rig lacks. Nothing blocks:
 * `can_proceed` is always true, and a document without an enabled, baked skeleton yields a complete empty report.
 */
object SkeletonBindingQuality {
	const val VERSION = 1
	const val DOMAIN = "skeleton"
	const val CHECK_ID = "skeleton_reach"
	const val SCOPE = "Whether every mesh bound to a bone follows it, and every mesh on the body follows the torso's bend."

	/** The findings for [model], the finished rig, and [spec], the document's skeleton; bindings first, in bone order. */
	fun issues(model: PuppetModel, spec: SkeletonSpec?): List<SkeletonBindingIssue> {
		if (spec == null || !spec.enabled || model.deformers.none { it.id == SkeletonRig.bodyId }) return emptyList()
		val present = model.drawables.mapTo(HashSet()) { it.id.raw }
		val out = ArrayList<SkeletonBindingIssue>()
		val unskinned = SkeletonCanvasSkin.unskinned(model, spec)
		for (bone in SkeletonRig.limbBones(spec)) for (mesh in bone.drawableIds) when {
			mesh !in present -> out += SkeletonBindingIssue(SkeletonBindingRule.SKELETON_BINDING_MISSING, mesh, bone.id)
			!bone.role.body && mesh in unskinned -> out += SkeletonBindingIssue(SkeletonBindingRule.SKELETON_BINDING_INEFFECTIVE, mesh, bone.id)
		}
		if (model.deformers.none { it.id == SkeletonRig.torsoWarpId }) return out
		val deformers = model.deformers.associateBy { it.id }
		// A mesh skinned on the canvas carries the body halves' turns in its own keyforms.
		val halves = spec.bones.filter { it.role.body }.mapTo(HashSet()) { org.umamo.runtime.model.ParameterId(it.parameterId) }
		for (drawable in model.drawables) {
			val chain = generateSequence(drawable.parentDeformerId) { deformers[it]?.parent }.toList()
			if (SkeletonRig.breathId !in chain || SkeletonRig.torsoWarpId in chain) continue
			if (drawable.geometryGrid?.axes.orEmpty().any { it.parameterId in halves } || drawable.blendShapes.any { it.parameterId in halves }) continue
			out += SkeletonBindingIssue(SkeletonBindingRule.SKELETON_TORSO_BYPASSED, drawable.id.raw)
		}
		return out
	}

	fun finding(issue: SkeletonBindingIssue): JsonObject = buildJsonObject {
		put("code", issue.rule.name)
		put("severity", issue.rule.severity)
		put("category", issue.rule.category)
		put("domain", DOMAIN)
		put("target", "mesh:${issue.mesh}")
		putJsonObject("evidence") { issue.bone?.let { put("bone", it) } }
	}

	fun report(issues: List<SkeletonBindingIssue>): JsonObject = buildJsonObject {
		put("version", VERSION)
		put("domain", DOMAIN)
		put("fence", "observation")
		put("decision", if (issues.isEmpty()) "accept" else "accept_with_diagnostics")
		put("can_proceed", true)
		put("complete", true)
		put("scope", SCOPE)
		putJsonArray("checks") { add(buildJsonObject { put("id", CHECK_ID); put("scope", SCOPE); put("complete", true) }) }
		put("findings", JsonArray(issues.map(::finding)))
	}
}
