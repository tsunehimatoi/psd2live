package io.github.psd2live.core

import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.render.eval.DeformerWorld
import org.umamo.render.eval.RotationWorld
import org.umamo.render.eval.buildDeformerWorlds
import org.umamo.runtime.eval.colorAt
import org.umamo.runtime.eval.meshGridDefaultDeltas
import org.umamo.runtime.eval.rotationFormAt
import org.umamo.runtime.eval.scalarAt
import org.umamo.runtime.eval.warpControlPointsAt
import org.umamo.runtime.model.BlendShapeBinding
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.FormChannel
import org.umamo.runtime.model.Glue
import org.umamo.runtime.model.GluePair
import org.umamo.runtime.model.KeyformAxis
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.MeshDeltaForm
import org.umamo.runtime.model.MeshForm
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterGroupId
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.ParameterKind
import org.umamo.runtime.model.ParameterNode
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.RotationForm
import org.umamo.runtime.model.RotationPivotForm
import org.umamo.runtime.model.RuntimeFeature
import org.umamo.runtime.model.WarpForm
import org.umamo.runtime.model.WarpLatticeForm
import org.umamo.runtime.model.withDerivedRenderRoot
import io.github.psd2live.i18n.tr
import io.github.psd2live.core.mesh.Point
import io.github.psd2live.core.mesh.pointInTriangleInclusive
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max

/**
 * Bakes an authored skeleton into a Cubism rig.
 *
 * Cubism has no bones, and a rotation deformer passes on only a pivot and an angle: whatever bends the
 * warps above it never reaches what hangs below. So the skeleton is laid out the way the body deforms,
 * warps first and rotations only where a part really is rigid:
 *
 * - **The two body halves bend one warp at the end of the body chain.** The torso bend sits under the
 *   breath warp, an identity lattice over the body that turns what lies past the waist about it and
 *   blends across a band there (see [BodyBend], [addBodyWarps]). Every body turn, breath and bend above
 *   passes straight through it, to the torso meshes and to the limbs and the head that hang from it. The
 *   legs stand apart from it in the legs warp of [BodyStance], so nothing that moves the body moves the
 *   feet.
 * - **A rotation deformer per limb bone**, nested as the bones are and hung from its body half's bend. A
 *   rotation deformer interpolates its angle rather than its vertices, so a limb turned to any parameter
 *   value is an exact rigid rotation - it neither shortens between keys nor multiplies keyforms when
 *   several joints move at once.
 * - **Corrective mesh keyforms across each joint.** A mesh that spans a joint hangs under one of the
 *   bones (its "home") and its keyforms carry the rest of the limb: vertices past a joint turn with the
 *   bone across it, and vertices inside the joint band turn by a weighted fraction of that angle about the
 *   joint (see [SkeletonWeights]). Each axis takes the fewest keys that keep the linear blend between
 *   them on the arc. Where no vertex moves with two bones at once, their bends only add, and on a runtime
 *   with blend shapes on rotation deformers such a bone turns by blend shapes rather than by a keyform
 *   axis, so its keys add to the others' instead of multiplying them (see [planBlend]).
 *
 * On top of these, every preset pose of [SkeletonPoses] - a crouch, a weight shift, a tail swing - is one
 * blend-shape parameter that adds its turns to the bones and its bends to the meshes (see [addPoses]).
 * The leg poses move the pelvis and bend the legs warp as [BodyStance] poses, and legs skinned to bones
 * are solved by two-bone IK at bake time so the feet stay where they are while the hips move.
 */
internal object SkeletonRig {
	internal val bodyId = DeformerId("DeformBodyXY")
	private val breathId = DeformerId("DeformBodyZBreath")
	private val headRotationId = DeformerId("DeformHeadRotation")

	/** The torso's bend: under the breath warp, over every mesh and limb the torso carries. */
	val torsoWarpId = DeformerId("DeformSkelTorso")
	private val skeletonGroupId = ParameterGroupId("ParamGroupSkeleton")

	/**
	 * How far, in canvas pixels, a vertex of one part may sit from a vertex or the outline of another and
	 * still be welded to it, as the glue brush's matching distance.
	 */
	private const val GLUE_TOLERANCE = 2f

	/**
	 * Weld weights of a glue across a joint. The two sides sum to 1, so a pair still meets on one point,
	 * and that point lies near the child part: the child keeps its shape and the parent's end follows it.
	 */
	private const val GLUE_CHILD_WEIGHT = 0.2f
	private const val GLUE_PARENT_WEIGHT = 1f - GLUE_CHILD_WEIGHT

	/** Default half width of a body half's waist band, as a fraction of the bone's length. */
	private const val WAIST_BAND = 0.25f

	/** Widest waist band, as a fraction of the bone's length. */
	private const val MAX_WAIST_BAND = 0.45f

	/** Columns of a body half's warp; its rows follow the waist band so the bend stays smooth. */
	private const val BODY_WARP_COLUMNS = 4

	/** Home-space units below which a pose leaves a mesh no shape of its own. */
	internal const val POSE_EPSILON = 1e-4f

	/** The bones of [spec] the rig moves: non-anchor bones with a usable length, body halves included. */
	fun limbBones(spec: SkeletonSpec): List<SkeletonBone> =
		spec.topological().filter { !it.role.anchor && it.length >= 1f }

	/** The bones that turn a rotation deformer and skin meshes: every moving bone but the body halves, which bend warps. */
	fun jointBones(spec: SkeletonSpec): List<SkeletonBone> = limbBones(spec).filterNot { it.role.body }

	/** Half width in pixels of the band about the waist across which [bone], a body half, bends. */
	fun waistBand(bone: SkeletonBone): Float {
		val limit = (bone.length * MAX_WAIST_BAND).coerceAtLeast(1f)
		return (bone.blendWidth ?: (bone.length * WAIST_BAND)).coerceIn(1f, limit)
	}

	/** Within a limb, a bone's parent is a limb bone: the body half a limb hangs from is not part of its skin. */
	fun jointParents(spec: SkeletonSpec): Map<String, SkeletonBone?> {
		val ids = limbBones(spec).mapTo(HashSet()) { it.id }
		return jointBones(spec).associate { it.id to limbParent(spec, it, ids)?.takeUnless { p -> p.role.body } }
	}

	/** The nearest ancestor of [bone] that is itself a limb bone, or null when it hangs from an anchor. */
	fun limbParent(spec: SkeletonSpec, bone: SkeletonBone, limbIds: Set<String>): SkeletonBone? {
		var parent = bone.parentId?.let(spec::bone)
		while (parent != null && parent.id !in limbIds) {
			if (parent.role.anchor) return null
			parent = parent.parentId?.let(spec::bone)
		}
		return parent
	}

	/**
	 * The skinning tree each limb bone belongs to, by the ID of the tree's root. A bone with no parent in
	 * [parentOf] roots its own tree, as every limb hanging from a body half does: the limb rides the body's
	 * warps, but its meshes and the torso's never blend into each other.
	 */
	fun skinRoots(bones: List<SkeletonBone>, parentOf: Map<String, SkeletonBone?>): Map<String, String> {
		val rootOf = HashMap<String, String>()
		for (bone in bones) {
			val parent = parentOf[bone.id]
			rootOf[bone.id] = if (parent == null || parent.role.body) bone.id else rootOf.getValue(parent.id)
		}
		return rootOf
	}

	/**
	 * Baked skeletons by the content hash of what the bake reads. Skinning takes seconds on a full figure while
	 * hashing its inputs takes milliseconds, so a rebuild with an unchanged skeleton and base rig - an undo, a
	 * history checkout, an export after the preview, a classification change elsewhere and back - reuses the bake.
	 *
	 * What the bake reads ([BakeInputs]): the meshes the bones bind; the deformers it hangs bones, warps and
	 * meshes from (the body, breath, lean and legs warps, the head rotation, the bones' own ids and the parents
	 * of the bound meshes) with all their ancestors; the parameters, parts, glues and parameter tree; and of
	 * every other deformer and drawable only its id, kind, order and parent - the torso warp adopts what hangs
	 * on its host. The atlas, the source files and the deform paths it neither reads nor writes, and of the
	 * hand-edited topology only the bound meshes matter. The key holds exactly that.
	 *
	 * A hit with a different base takes everything the key leaves out from the new base: the unread deformers
	 * and drawables (re-parented as the bake re-parented them), the atlas, the sources and the paths, and the
	 * draw order is derived again - what [apply] makes of the new base. A bake that changed an unread object any
	 * other way is kept only for a base whose whole IR matches. Rigs are large; only the last few are kept.
	 */
	private class BakeInputs(val drawables: Set<DrawableId>, val deformers: Set<DeformerId>)

	private class Bake(
		/** The base it was baked on; null for a bake read back from a project's head cache. */
		val base: PuppetModel?,
		val output: PuppetModel,
		val inputs: BakeInputs,
		/** The base's deformers the narrowed key leaves out: a hit takes them from the new base. */
		val unread: Set<DeformerId>,
		/** Unread drawables and deformers the bake moved under another deformer, and that deformer. */
		val reparentedDrawables: Map<DrawableId, DeformerId?>,
		val reparentedDeformers: Map<DeformerId, DeformerId?>,
		/** The full IR hash of [base] when the bake changed something the narrowed key leaves out; null otherwise. */
		val exact: String?,
		/** The skeleton definition baked, as JSON text, to find the bake of a document's skeleton. */
		val spec: String,
	)

	/**
	 * A bake as a project's head cache stores it ([io.github.psd2live.project.ProjectHeadCache]): the cache key,
	 * the output as IR without what every hit takes from the new base (atlas, sources, deform paths), and the
	 * record of what the bake did to the objects the key leaves out. Seeding it makes a later [generate] with the
	 * same inputs hit as if this process had baked it; with other inputs the key differs and it is never used.
	 */
	class StoredBake(
		val key: String,
		val spec: String,
		val output: io.github.psd2live.format.model.RigIR,
		val drawables: List<String>,
		val deformers: List<String>,
		val unread: List<String>,
		val reparentedDrawables: Map<String, String?>,
		val reparentedDeformers: Map<String, String?>,
		val exact: String?,
	)

	/** Version of the bake and its key; raise it whenever the bake or what the key covers changes. */
	const val BAKE_VERSION = "skeleton-3"

	private const val CACHE_CAPACITY = 4
	private val bakes = object : LinkedHashMap<String, Bake>(16, 0.75f, true) {
		override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bake>?): Boolean = size > CACHE_CAPACITY
	}
	private var hits = 0
	private var misses = 0
	private val lastKey = ThreadLocal<String?>()

	/** The cache key of the last [generate] on this thread that baked or looked up a bake, then forgets it. */
	internal fun takeLastKey(): String? = lastKey.get().also { lastKey.remove() }

	/**
	 * Marks the bake under [key] most recently used, as a hit would: a caller that reuses a rig with that bake
	 * without asking again (the rig builder's stage cache) keeps it the one [storedBake] hands a saved project.
	 */
	internal fun touch(key: String) { synchronized(bakes) { bakes[key] } }

	/** [apply] through the bake cache. The key covers every input the bake reads, including the names' language. */
	fun generate(
		base: PuppetModel,
		spec: SkeletonSpec,
		frame: Bounds,
		lockedTopology: Set<String> = emptySet(),
		stance: BodyStance? = null,
		canvasSkinned: Set<String> = emptySet(),
	): PuppetModel {
		if (!spec.enabled || base.deformers.none { it.id == bodyId } || limbBones(spec).isEmpty()) return base
		val inputs = bakeInputs(base, spec)
		val key = cacheKey(base, spec, frame, lockedTopology, stance, inputs, canvasSkinned)
		lastKey.set(key)
		synchronized(bakes) {
			val cached = bakes[key]
			if (cached != null && (cached.exact == null || cached.exact == fullHash(base))) {
				hits++
				return if (cached.base === base) cached.output else rebased(cached, base)
			}
			misses++
		}
		val output = apply(base, spec, frame, lockedTopology, stance, canvasSkinned)
		val bake = recordBake(base, output, inputs, spec.toJson().toString())
		synchronized(bakes) { bakes[key] = bake }
		return output
	}

	private fun bakeInputs(base: PuppetModel, spec: SkeletonSpec): BakeInputs {
		val bones = limbBones(spec)
		val drawables = bones.flatMapTo(HashSet()) { bone -> bone.drawableIds.map(::DrawableId) }
		val roots = hashSetOf(bodyId, breathId, headRotationId, torsoWarpId, BodyStance.legsWarpId, BodyStance.leanWarpId)
		for (bone in bones) { roots += DeformerId(bone.deformerId); roots += DeformerId(bone.deformerId + "Stance") }
		for (drawable in base.drawables) if (drawable.id in drawables) drawable.parentDeformerId?.let(roots::add)
		val byId = base.deformers.associateBy { it.id }
		val deformers = HashSet<DeformerId>()
		for (root in roots) {
			var next: DeformerId? = root
			while (next != null && deformers.add(next)) next = byId[next]?.parent
		}
		return BakeInputs(drawables, deformers)
	}

	private fun cacheKey(base: PuppetModel, spec: SkeletonSpec, frame: Bounds, lockedTopology: Set<String>, stance: BodyStance?,
						 inputs: BakeInputs, canvasSkinned: Set<String>): String {
		val read = base.copy(
			drawables = base.drawables.filter { it.id in inputs.drawables },
			deformers = base.deformers.filter { it.id in inputs.deformers },
			atlas = org.umamo.runtime.model.PuppetAtlas.Empty, sources = emptyList(), deformPaths = emptyList(),
			renderRoot = org.umamo.runtime.model.RenderGroup(null, org.umamo.runtime.model.DEFAULT_DRAW_ORDER, emptyList()),
		)
		return io.github.psd2live.format.compile.document.ContentHash.of(BAKE_VERSION, io.github.psd2live.targets.cubism.PuppetIr.toIr(read),
			base.deformers.map { Triple(it.id.raw, it.parent?.raw, it.javaClass.simpleName) },
			base.drawables.map { it.id.raw to it.parentDeformerId?.raw }, spec.toJson(), frame,
			lockedTopology.filter { DrawableId(it) in inputs.drawables }.sorted(), stance?.contentKey, io.github.psd2live.i18n.I18n.currentLanguage.tag,
			// Only when there are any, so the key of every other bake stays what it was.
			*listOfNotNull(canvasSkinned.filter { DrawableId(it) in inputs.drawables }.sorted().takeIf { it.isNotEmpty() }).toTypedArray())
	}

	private fun fullHash(model: PuppetModel) =
		io.github.psd2live.format.compile.document.ContentHash.of(io.github.psd2live.targets.cubism.PuppetIr.toIr(model))

	/** [deformer] hung from [parent]; null for a kind that has no parent to change. */
	private fun withParent(deformer: Deformer, parent: DeformerId?): Deformer? = when (deformer) {
		is Deformer.Warp -> deformer.copy(parent = parent)
		is Deformer.Rotation -> deformer.copy(parent = parent)
		else -> null
	}

	/** What the bake did to the objects the narrowed key leaves out, so a later base can take their place. */
	private fun recordBake(base: PuppetModel, output: PuppetModel, inputs: BakeInputs, spec: String): Bake {
		val drawablesBefore = base.drawables.associateBy { it.id }
		val deformersBefore = base.deformers.associateBy { it.id }
		val reparentedDrawables = HashMap<DrawableId, DeformerId?>()
		val reparentedDeformers = HashMap<DeformerId, DeformerId?>()
		var narrow = output.atlas === base.atlas && output.sources === base.sources && output.deformPaths === base.deformPaths &&
			output.drawables.filter { it.id !in inputs.drawables }.map { it.id } == base.drawables.filter { it.id !in inputs.drawables }.map { it.id }
		if (narrow) for (drawable in output.drawables) {
			if (drawable.id in inputs.drawables) continue
			val old = drawablesBefore.getValue(drawable.id)
			if (drawable === old) continue
			if (drawable != old.copy(parentDeformerId = drawable.parentDeformerId)) { narrow = false; break }
			reparentedDrawables[drawable.id] = drawable.parentDeformerId
		}
		if (narrow) for (deformer in output.deformers) {
			// A deformer the base lacked is the bake's own, made from what the key holds.
			if (deformer.id in inputs.deformers) continue
			val old = deformersBefore[deformer.id] ?: continue
			if (deformer === old) continue
			if (deformer.javaClass != old.javaClass || deformer != withParent(old, deformer.parent)) { narrow = false; break }
			reparentedDeformers[deformer.id] = deformer.parent
		}
		// Every unread base deformer must survive the bake, or a rebase could not take it from the new base.
		if (narrow) {
			val kept = output.deformers.mapTo(HashSet()) { it.id }
			narrow = base.deformers.all { it.id in inputs.deformers || it.id in kept }
		}
		val unread = base.deformers.mapNotNullTo(HashSet()) { it.id.takeIf { id -> id !in inputs.deformers } }
		return Bake(base, output, inputs, unread, reparentedDrawables, reparentedDeformers, if (narrow) null else fullHash(base), spec)
	}

	/** [bake]'s output with what the narrowed key left out taken from [base] instead of the base it was baked on. */
	private fun rebased(bake: Bake, base: PuppetModel): PuppetModel {
		val drawables = base.drawables.associateBy { it.id }
		val deformers = base.deformers.associateBy { it.id }
		val unread = bake.unread
		return bake.output.copy(
			drawables = bake.output.drawables.map { drawable ->
				if (drawable.id in bake.inputs.drawables) drawable else {
					val next = drawables.getValue(drawable.id)
					if (drawable.id in bake.reparentedDrawables) next.copy(parentDeformerId = bake.reparentedDrawables[drawable.id]) else next
				}
			},
			deformers = bake.output.deformers.map { deformer ->
				if (deformer.id !in unread) deformer else {
					val next = deformers.getValue(deformer.id)
					if (deformer.id in bake.reparentedDeformers) withParent(next, bake.reparentedDeformers[deformer.id])!! else next
				}
			},
			atlas = base.atlas, sources = base.sources, deformPaths = base.deformPaths,
		).withDerivedRenderRoot()
	}

	/** Hits and misses of the skeleton cache, for tests and measurements. */
	internal val cacheHits: Int get() = synchronized(bakes) { hits }
	internal val cacheMisses: Int get() = synchronized(bakes) { misses }
	internal fun clearCache() = synchronized(bakes) { bakes.clear(); clears++ }

	/** How often the cache was cleared: a cache of rigs holding bakes keys by it, so clearing reaches them too. */
	@Volatile internal var clears = 0
		private set

	/** The most recently used bake of [spec], to store with a project whose head has that skeleton; null when none. */
	fun storedBake(spec: SkeletonSpec): StoredBake? {
		val text = spec.toJson().toString()
		val (key, bake) = synchronized(bakes) { bakes.entries.lastOrNull { it.value.spec == text }?.toPair() } ?: return null
		val output = bake.output.copy(atlas = org.umamo.runtime.model.PuppetAtlas.Empty, sources = emptyList(), deformPaths = emptyList())
		// A hit takes every unread drawable and deformer from its new base ([rebased]): only their ids and order matter.
		val ir = io.github.psd2live.targets.cubism.PuppetIr.toIr(output).let { ir ->
			val drawables = bake.inputs.drawables.mapTo(HashSet()) { it.raw }
			val unread = bake.unread.mapTo(HashSet()) { it.raw }
			ir.copy(
				meshes = ir.meshes.map { if (it.id in drawables) it else io.github.psd2live.format.model.Mesh(it.id, "", null, geometry = null, offsets = null) },
				deformers = ir.deformers.map { if (it.id !in unread) it else io.github.psd2live.format.model.Deformer.Warp(it.id, "", null, null, 0, 0, false, null) },
			)
		}
		return StoredBake(key, bake.spec, ir,
			bake.inputs.drawables.map { it.raw }.sorted(), bake.inputs.deformers.map { it.raw }.sorted(), bake.unread.map { it.raw }.sorted(),
			bake.reparentedDrawables.entries.sortedBy { it.key.raw }.associate { it.key.raw to it.value?.raw },
			bake.reparentedDeformers.entries.sortedBy { it.key.raw }.associate { it.key.raw to it.value?.raw }, bake.exact)
	}

	/** Adds [stored] to the bake cache under its key; a bake this process already holds for that key is kept. */
	fun seed(stored: StoredBake) {
		val bake = Bake(null, io.github.psd2live.targets.cubism.PuppetIr.toPuppet(stored.output),
			BakeInputs(stored.drawables.mapTo(HashSet(), ::DrawableId), stored.deformers.mapTo(HashSet(), ::DeformerId)),
			stored.unread.mapTo(HashSet(), ::DeformerId),
			stored.reparentedDrawables.entries.associate { DrawableId(it.key) to it.value?.let(::DeformerId) },
			stored.reparentedDeformers.entries.associate { DeformerId(it.key) to it.value?.let(::DeformerId) }, stored.exact, stored.spec)
		synchronized(bakes) { bakes.putIfAbsent(stored.key, bake) }
	}

	/**
	 * Bakes [spec] into [base]. [frame] is the character bounds the body warp spans, and [stance] how the
	 * figure stands, which places the leg poses; without one it is read off the skeleton. Meshes in
	 * [lockedTopology] keep their vertices: they carry hand-made topology edits that replay by vertex
	 * index, which new vertices would misplace.
	 *
	 * Meshes in [canvasSkinned] are those the journal hangs under a deformer of its own (see [SkeletonCanvasSkin]):
	 * the bake refines and welds them with the rest of their limb and counts them in the blend plan, but leaves
	 * them where they hang, unskinned. The bone their limb would hang them from keeps its deformer, as if they
	 * hung there, and the skin is written onto them after the journal replays.
	 */
	fun apply(
		base: PuppetModel,
		spec: SkeletonSpec,
		frame: Bounds,
		lockedTopology: Set<String> = emptySet(),
		stance: BodyStance? = null,
		canvasSkinned: Set<String> = emptySet(),
	): PuppetModel {
		if (!spec.enabled || base.deformers.none { it.id == bodyId }) return base
		val bones = limbBones(spec)
		if (bones.isEmpty()) return base
		val joints = bones.filterNot { it.role.body }
		val parentOf = jointParents(spec)

		var model = base
		var canvas = restCanvas(model)

		// 1. Which meshes each limb tree skins: a mesh bound to any bone of the tree. A mesh bound to a body
		// half stays on the warp it hangs from, which that half's own warp is spliced above.
		val rootOf = skinRoots(joints, parentOf)
		val drawableRoot = LinkedHashMap<String, String>()
		for (bone in joints) for (id in bone.drawableIds) {
			val drawable = model.drawables.firstOrNull { it.id.raw == id } ?: continue
			if (drawable.mesh == null || drawable.id !in canvas) continue
			drawableRoot.putIfAbsent(id, rootOf.getValue(bone.id))
		}
		val treeBones = joints.groupBy { rootOf.getValue(it.id) }
		val candidates = blendCandidates(model, joints)

		// 2. Enough vertices across every joint the mesh crosses.
		for ((id, root) in drawableRoot) {
			if (id in lockedTopology) continue
			val skinBones = skinBones(treeBones.getValue(root), parentOf)
			val bands = skinBones.indices.filter { skinBones[it].parent >= 0 }.map { child ->
				val n = SkeletonWeights.bandNormal(skinBones, child)
				val c = skinBones[child]
				JointBand(c.headX, c.headY, n[0], n[1], c.blend)
			}
			val drawableId = DrawableId(id)
			val (refined, frameAfter) = SkeletonMeshRefine.refine(model, drawableId, canvas.getValue(drawableId), bands, spec.sampling.jointMeshSegments)
			model = refined
			canvas = canvas + (drawableId to frameAfter)
		}

		// 2b. Split parts of one limb welded wherever they overlap, before skinning so every new vertex
		// gets its joints baked like the rest.
		val seams = weldSplitParts(model, canvas, drawableRoot, treeBones, parentOf, lockedTopology, candidates, spec.manualWeights)
		model = seams.model
		canvas = seams.canvas

		// 2c. The bone each mesh hangs under, and the bone parameters whose turns only ever add.
		val plan = planBlend(model, canvas, drawableRoot, treeBones, parentOf, candidates, spec.manualWeights, canvasSkinned)

		// 3. The body halves spliced into the body chain as a warp - the head rotation and everything else on
		// the breath warp ends up under it - and a rotation deformer per limb bone hung from it, the legs'
		// from the legs warp.
		val bends = LinkedHashMap<String, BodyBend>()
		model = addBodyWarps(model, bones.filter { it.role.body }, frame, bends, spec.sampling)
		model = addRotations(model, joints, parentOf, spec, frame, bends, plan.blend)
		model = addParameters(model, bones, plan.blend)

		// 4. The preset poses, hips that move while the feet stay put among them.
		val poses = SkeletonPoses.available(spec).filter { it.rig }
		val standing = stance ?: BodyStance.of(spec, frame)
		model = addPoses(model, spec, bones, poses, bends, standing)

		// 4b. A leg bone that skinned meshes hang under carries them through a warp of its own on Body X and
		// Body Y, since its rotation passes on none of the legs warp's bend.
		val legHomes = LinkedHashMap<SkeletonBone, MutableList<DrawableId>>()
		for ((id, root) in drawableRoot) {
			if (id in canvasSkinned) continue
			val home = treeBones.getValue(root)[plan.homes.getValue(id)]
			if (home.role in legRoles) legHomes.getOrPut(home) { ArrayList() } += DrawableId(id)
		}
		val hosts = HashMap<String, DeformerId>()
		model = addLegStanceWarps(model, legHomes, canvas, standing, hosts)

		// 5. Every skinned mesh under its home bone, with its joints baked into its keyforms and its poses
		// into its blend shapes.
		for ((id, root) in drawableRoot) {
			if (id in canvasSkinned) continue
			val drawableId = DrawableId(id)
			val tree = treeBones.getValue(root)
			val home = plan.homes.getValue(id)
			model = skinDrawable(model, drawableId, canvas.getValue(drawableId), tree, parentOf, poses,
				home, plan.blend, spec.sampling, spec.manualWeights[id], hosts[tree[home].id])
		}

		// 6. The welded parts glued, no deformer left holding nothing, and no joint inside one mesh left as a
		// rotation of its own.
		model = model.copy(glues = model.glues + seams.glues)
		// The bone each mesh skinned after the journal would hang from holds it all the same.
		val held = drawableRoot.filterKeys { it in canvasSkinned }.mapTo(HashSet()) { (id, root) ->
			DeformerId(treeBones.getValue(root)[plan.homes.getValue(id)].deformerId)
		}
		model = pruneEmptyBones(model, joints, held)
		model = foldLinkBones(model, joints, spec.sampling, held)
		stance?.let { model = withLean(model, joints, it) }
		model = withArmSwing(model, joints, standing)
		model = withSkeletonGroup(model, bones, poses)
		return model.withDerivedRenderRoot()
	}

	/**
	 * The bone parameters that may become blend shapes at all: none on a runtime without blend shapes on
	 * rotation deformers, and none the physics reads or writes (the tails and the wings, see
	 * [PhysicsGenerator]).
	 */
	private fun blendCandidates(model: PuppetModel, joints: List<SkeletonBone>): Set<String> =
		if (!model.runtimeTarget.supports(RuntimeFeature.ExtendedBlendShapes)) emptySet()
		else joints.filter { it.role != BoneRole.TAIL && it.role != BoneRole.WING }.mapTo(HashSet()) { it.parameterId }

	/** Where each skinned mesh hangs, by its bone's index in its tree, and the bone parameters that are blend shapes. */
	private class BlendPlan(val homes: Map<String, Int>, val blend: Set<ParameterId>)

	/**
	 * Which of the [candidates] become blend shapes.
	 *
	 * A keyform grid holds a form for every combination of its axes, so a mesh bent at two joints carries
	 * the product of both bones' keys. Where no vertex moves with more than one of them the bends simply
	 * add, and blend shapes - which add - need only the sum of the keys. A parameter becomes a blend shape
	 * when that holds everywhere it acts: in every mesh, and in every rotation its bone folds into as a link
	 * (two links in a row carry a pivot round both turns at once). A parameter has one kind for the whole
	 * model, so one use that multiplies it with another keeps it a keyform axis everywhere.
	 */
	private fun planBlend(
		model: PuppetModel,
		canvas: Map<DrawableId, FloatArray>,
		drawableRoot: Map<String, String>,
		treeBones: Map<String, List<SkeletonBone>>,
		parentOf: Map<String, SkeletonBone?>,
		candidates: Set<String>,
		manualWeights: Map<String, SkeletonWeightMap>,
		canvasSkinned: Set<String> = emptySet(),
	): BlendPlan {
		val homes = HashMap<String, Int>()
		val coupled = HashSet<String>()
		for ((id, root) in drawableRoot) {
			val tree = treeBones.getValue(root)
			val skinBones = skinBones(tree, parentOf)
			val drawableId = DrawableId(id)
			val frame = canvas.getValue(drawableId)
			val triangles = model.drawables.first { it.id == drawableId }.mesh!!.indices
			val skins = SkeletonManualWeights.weights(frame, triangles, tree, parentOf, manualWeights[id])
			val home = homeBone(skins, skinBones, frame, triangles) { tree[it].parameterId in candidates }
			homes[id] = home
			// A mesh skinned in canvas space hangs from no bone: every bone up its limb moves it.
			for (moving in if (id in canvasSkinned) canvasDependencies(skins, skinBones) else dependencies(skins, skinBones, home)) {
				val parameters = moving.mapTo(HashSet()) { tree[it].parameterId }
				if (parameters.size > 1) coupled += parameters
			}
		}
		// A link holds no mesh but carries meshes below it, and folds into its children (see [foldLinkBones]).
		val homeIds = drawableRoot.mapTo(HashSet()) { (id, root) -> treeBones.getValue(root)[homes.getValue(id)].id }
		val bones = treeBones.values.flatten()
		val children = bones.groupBy { parentOf[it.id]?.id }
		fun holds(bone: SkeletonBone): Boolean = bone.id in homeIds || children[bone.id].orEmpty().any(::holds)
		fun isLink(bone: SkeletonBone) = parentOf[bone.id] != null && bone.id !in homeIds && holds(bone)
		for (bone in bones) {
			val parent = parentOf[bone.id] ?: continue
			if (isLink(bone) && isLink(parent)) {
				coupled += bone.parameterId
				coupled += parent.parameterId
			}
		}
		return BlendPlan(homes, (candidates - coupled).mapTo(HashSet(), ::ParameterId))
	}

	/**
	 * Every limb rotation hung straight from the body warps, growing and shrinking with the body's lean and
	 * proportions (see [BodyStance.limbScale]): a warp passes a rotation its pivot and its turn but never its
	 * scale, so an arm would otherwise stay its size while the chest it hangs from comes toward the viewer.
	 * The lean warp's axes join its own, keyed as the lean warp keys them.
	 */
	private fun withLean(model: PuppetModel, bones: List<SkeletonBone>, stance: BodyStance): PuppetModel {
		val axes = (model.deformers.firstOrNull { it.id == BodyStance.leanWarpId } as? Deformer.Warp)?.geometryGrid?.axes
			?.takeIf { it.size == 2 && it[0].parameterId == StandardParameters.BODY_LEAN } ?: return model
		val hosts = setOf(torsoWarpId, breathId, BodyStance.leanWarpId, bodyId)
		val combos = axes[0].keys.indices.flatMap { a -> axes[1].keys.indices.map { b -> intArrayOf(a, b) } }
		val byDeformer = bones.associateBy { it.deformerId }
		return model.copy(deformers = model.deformers.map { deformer ->
			if (deformer !is Deformer.Rotation || deformer.parent !in hosts) return@map deformer
			val bone = byDeformer[deformer.id.raw] ?: return@map deformer
			val grid = deformer.geometryGrid ?: return@map deformer
			if (grid.axes.any { axis -> axes.any { it.parameterId == axis.parameterId } }) return@map deformer
			val scales = combos.map { c -> stance.limbScale(bone.headY.toDouble(), axes[0].keys[c[0]], axes[1].keys[c[1]]).toFloat() }
			if (scales.all { abs(it - 1f) < 5e-3f }) return@map deformer
			deformer.copy(geometryGrid = KeyformGrid(grid.axes + axes, grid.cells.flatMap { cell ->
				combos.indices.map { n -> KeyformCell(cell.coordinate + combos[n], cell.form.let { RotationPivotForm(it.originX, it.originY, it.angle, it.scale * scales[n]) }) }
			}))
		})
	}

	/**
	 * Every upper arm hung straight from the body warps keyed on Body X, so the arm swings about its shoulder
	 * as [BodyStance.armSwing] has it rather than turning with the torso: a rotation takes from the warp it
	 * hangs on the warp's turn at its pivot, which goes the way the body turns and would carry the hand
	 * further out. Each key takes that turn back out and adds the swing.
	 */
	private fun withArmSwing(model: PuppetModel, bones: List<SkeletonBone>, stance: BodyStance): PuppetModel {
		val keys = floatArrayOf(-10f, 0f, 10f)
		val hosts = setOf(torsoWarpId, breathId, BodyStance.leanWarpId, bodyId)
		val byDeformer = bones.filter { it.role == BoneRole.UPPER_ARM }.associateBy { it.deformerId }
		var result = model
		for (deformer in model.deformers) {
			if (deformer !is Deformer.Rotation || deformer.parent !in hosts) continue
			byDeformer[deformer.id.raw] ?: continue
			val grid = deformer.geometryGrid ?: continue
			if (grid.axes.any { it.parameterId == StandardParameters.BODY_X }) continue
			fun turned(by: Float) = result.copy(deformers = result.deformers.map { d ->
				if (d.id != deformer.id) d else deformer.copy(geometryGrid = KeyformGrid(grid.axes, grid.cells.map { cell ->
					KeyformCell(cell.coordinate, cell.form.let { RotationPivotForm(it.originX, it.originY, it.angle + by, it.scale) })
				}))
			})
			fun angle(m: PuppetModel, x: Float) = angleOf(worlds(m, mapOf(StandardParameters.BODY_X to x), setOf(deformer.id)).getValue(deformer.id))
			val rest = angle(result, 0f)
			// How the key's angle reads in the world: a rotation's handle turns clockwise on the canvas.
			val sense = SkeletonIk.wrap((angle(turned(1f), 0f) - rest).toDouble()).toFloat()
			if (abs(sense) < 0.5f) continue
			val offsets = keys.map { x ->
				if (x == 0f) 0f else {
					val inherited = SkeletonIk.wrap((angle(result, x) - rest).toDouble()).toFloat()
					(stance.armSwing(x).toFloat() - inherited) / sense
				}
			}
			if (offsets.all { abs(it) < 1e-3f }) continue
			val swung = deformer.copy(geometryGrid = KeyformGrid(grid.axes + KeyformAxis(StandardParameters.BODY_X, keys), grid.cells.flatMap { cell ->
				keys.indices.map { k ->
					KeyformCell(cell.coordinate + k, cell.form.let { RotationPivotForm(it.originX, it.originY, it.angle + offsets[k], it.scale) })
				}
			}))
			result = result.copy(deformers = result.deformers.map { if (it.id == deformer.id) swung else it })
		}
		return result
	}

	/**
	 * The bone and pose parameters gathered into one folder of the parameter tree, after the folders
	 * already there. A model with no tree lists every parameter flat and is left so.
	 */
	private fun withSkeletonGroup(model: PuppetModel, bones: List<SkeletonBone>, poses: List<SkeletonPose>): PuppetModel {
		if (model.parameterTree.isEmpty()) return model
		val present = model.parameters.mapTo(HashSet()) { it.id }
		val ids = (bones.map { ParameterId(it.parameterId) } + poses.map { it.id }).distinct().filter { it in present }
		if (ids.isEmpty()) return model
		val skeletonIds = ids.toSet()
		fun without(nodes: List<ParameterNode>): List<ParameterNode> = nodes.mapNotNull { node ->
			when (node) {
				is ParameterNode.Param -> node.takeIf { it.id !in skeletonIds }
				is ParameterNode.Group -> if (node.id == skeletonGroupId) null else node.copy(children = without(node.children))
			}
		}
		val group = ParameterNode.Group(skeletonGroupId, tr("model.group.skeleton"), true, ids.map { ParameterNode.Param(it) })
		return model.copy(parameterTree = without(model.parameterTree) + group)
	}

	/** Every drawable's rest vertices in canvas pixels (y down), before glue. */
	internal fun restCanvas(model: PuppetModel): Map<DrawableId, FloatArray> =
		CpuDeformationEvaluator().evaluate(model.copy(glues = emptyList()), emptyMap()).worldPositions
			.mapValues { (_, world) -> FloatArray(world.size) { if (it % 2 == 1) -world[it] else world[it] } }

	/** [tree] as the skinning sees it, parents indexed within the tree. */
	internal fun skinBones(tree: List<SkeletonBone>, parentOf: Map<String, SkeletonBone?>): List<SkinBone> {
		val index = tree.withIndex().associate { it.value.id to it.index }
		return tree.map { bone ->
			val parent = parentOf[bone.id]
			SkinBone(
				parent = parent?.let { index[it.id] } ?: -1,
				headX = bone.headX.toDouble(),
				headY = bone.headY.toDouble(),
				tailX = bone.tailX.toDouble(),
				tailY = bone.tailY.toDouble(),
				blend = if (parent != null && parent.id in index) SkeletonWeights.blendHalfWidth(bone, parent.length) else 0.0,
			)
		}
	}

	// ---------------------------------------------------------------------------------------------------
	// Body warps

	/**
	 * How the body halves bend: an identity lattice over its host's space in which each half turns every
	 * point past the waist about the bone's head - the joint of the two halves - fading in across
	 * [waistBand] on either side of it. Past the band the turn is rigid, so a shoulder or a hip joint turns
	 * exactly with its half, and inside it the torso bends instead of creasing.
	 *
	 * The lattice is fine because nothing below resamples it: a warp under a warp only moves the child's
	 * control points, so each bend warp is the last warp above the meshes and limbs it carries.
	 */
	internal class BodyBend(val id: DeformerId, hostRest: DeformerWorld, val bones: List<SkeletonBone>, frame: Bounds) {
		private val host = hostRest
		private val bands = DoubleArray(bones.size) { waistBand(bones[it]).toDouble() }
		val columns = BODY_WARP_COLUMNS

		/** A row every half width of the narrowest band, so each bend spans two rows. */
		val rows = ceil(frame.height / bands.min()).toInt().coerceIn(6, 16)

		private val local = FloatArray((rows + 1) * (columns + 1) * 2).also { points ->
			var i = 0
			for (row in 0..rows) for (column in 0..columns) {
				points[i++] = column.toFloat() / columns
				points[i++] = row.toFloat() / rows
			}
		}
		private val canvas = FloatArray(local.size).also { for (i in local.indices step 2) hostRest.apply(local[i], local[i + 1], it, i) }

		/** How much of the turn of bone [index] canvas point ([x], [y]) takes: none short of its band, all past it. */
		fun weight(index: Int, x: Double, y: Double): Double {
			val bone = bones[index]
			val length = bone.length.toDouble().coerceAtLeast(1e-6)
			val along = ((x - bone.headX) * (bone.tailX - bone.headX) + (y - bone.headY) * (bone.tailY - bone.headY)) / length
			val t = ((along + bands[index]) / (2.0 * bands[index])).coerceIn(0.0, 1.0)
			return t * t * (3.0 - 2.0 * t)
		}

		/** The lattice in the host's space with each of [bones] turned by [degrees] (the rig's convention). */
		fun lattice(degrees: DoubleArray): FloatArray {
			if (degrees.all { it == 0.0 }) return local.copyOf()
			val out = FloatArray(local.size)
			for (i in local.indices step 2) {
				val x = canvas[i].toDouble()
				val y = canvas[i + 1].toDouble()
				var p = doubleArrayOf(x, y)
				for (b in bones.indices) {
					if (degrees[b] == 0.0) continue
					p = SkeletonIk.rotate(p[0], p[1], bones[b].headX.toDouble(), bones[b].headY.toDouble(), degrees[b] * weight(b, x, y))
				}
				val back = inverse(host, p[0].toFloat(), p[1].toFloat(), floatArrayOf(local[i], local[i + 1]))
				out[i] = back[0]
				out[i + 1] = back[1]
			}
			return out
		}

		/** Every bone's own parameter as an axis, keyed densely enough that the turn stays on its arc. */
		fun grid(sampling: SkeletonSampling = SkeletonSampling()): KeyformGrid<WarpLatticeForm> {
			val axes = bones.map { KeyformAxis(ParameterId(it.parameterId), angleKeys(it.minAngle to it.maxAngle, sampling.minimumStepDegrees.toDouble())) }
			return KeyformGrid(axes, cartesian(axes).map { coordinate ->
				KeyformCell(coordinate, WarpLatticeForm(lattice(DoubleArray(bones.size) { b ->
					axes[b].keys[coordinate[b]] * bones[b].direction.toDouble()
				})))
			})
		}
	}

	/**
	 * Splices the body halves' bend into the body chain.
	 *
	 * The torso bend goes under the breath warp (the body warp when there is none) and takes over all its
	 * children: the torso and the clothes, the arms, the wings, the tail and the head. It turns with both
	 * halves, so a skirt and a tail follow the hips and a coat bends at the waist. The legs are not among
	 * them: they stand in the legs warp, so neither the breath nor a turn of the hips lifts the feet.
	 *
	 * Being the identity in its host's space at rest, a bend warp moves nothing it takes over and changes
	 * none of its coordinates. [bends] receives the warp each body bone is read from.
	 */
	private fun addBodyWarps(base: PuppetModel, bodies: List<SkeletonBone>, frame: Bounds, bends: MutableMap<String, BodyBend>, sampling: SkeletonSampling): PuppetModel {
		if (bodies.isEmpty()) return base
		val halves = bodies.sortedBy { if (it.role == BoneRole.UPPER_BODY) 0 else 1 }
		val torsoHost = if (base.deformers.any { it.id == breathId }) breathId else bodyId
		return splice(base, torsoWarpId, tr("model.deformer.skeletonTorso"), torsoHost, halves, frame, adoptDeformers = true, bends, sampling)
	}

	/**
	 * Adds the bend warp [id] of [bones] under [host], taking over the host's meshes - the limb meshes the
	 * rig builder leaves on the body warp, bound to a bone or not - and its deformers when [adoptDeformers].
	 */
	private fun splice(
		model: PuppetModel,
		id: DeformerId,
		name: String,
		host: DeformerId,
		bones: List<SkeletonBone>,
		frame: Bounds,
		adoptDeformers: Boolean,
		bends: MutableMap<String, BodyBend>,
		sampling: SkeletonSampling,
	): PuppetModel {
		val hostRest = worlds(model, emptyMap(), setOf(host))[host] ?: return model
		val bend = BodyBend(id, hostRest, bones, frame)
		val partId = model.parts.firstOrNull { it.id.raw == "PartBody" }?.id
		val warp = Deformer.Warp(id, name, host, partId, bend.rows, bend.columns, true, bend.grid(sampling))
		val deformers = if (!adoptDeformers) model.deformers else model.deformers.map { deformer ->
			when {
				deformer.parent != host -> deformer
				deformer is Deformer.Warp -> deformer.copy(parent = id)
				deformer is Deformer.Rotation -> deformer.copy(parent = id)
				else -> deformer
			}
		}
		val at = deformers.indexOfFirst { it.id == host } + 1
		for (bone in bones) bends[bone.id] = bend
		return model.copy(
			deformers = deformers.subList(0, at) + warp + deformers.subList(at, deformers.size),
			drawables = model.drawables.map { if (it.parentDeformerId == host) it.copy(parentDeformerId = id) else it },
		)
	}

	/**
	 * The deformer [bone] turns: a limb bone's own rotation, or the bend warp a body half is read from. A
	 * leg bone with no rotation of its own - legs drawn on one layer - is read from the legs warp they bend in.
	 */
	fun deformerOf(model: PuppetModel, bone: SkeletonBone): DeformerId = when {
		bone.role in legRoles && model.deformers.none { it.id.raw == bone.deformerId } &&
			model.deformers.any { it.id == BodyStance.legsWarpId } -> BodyStance.legsWarpId
		!bone.role.body -> DeformerId(bone.deformerId)
		else -> torsoWarpId
	}

	private val legRoles = setOf(BoneRole.THIGH, BoneRole.SHIN, BoneRole.FOOT)

	// ---------------------------------------------------------------------------------------------------
	// Rotation deformers

	/**
	 * The deformer a limb bone with no limb parent hangs from. Bones on the head turn with the head Z
	 * rotation, and a leg stands in the legs warp; any other limb of a body half hangs from that half's
	 * warp, so it rides every bend of the body chain down to there. Without one, an upper limb takes the
	 * breath warp and anything else the body warp.
	 */
	private fun attachDeformer(model: PuppetModel, spec: SkeletonSpec, bone: SkeletonBone, bends: Map<String, BodyBend>): DeformerId {
		val lineage = generateSequence(bone) { it.parentId?.let(spec::bone) }
		val present = model.deformers.mapTo(HashSet()) { it.id }
		if (lineage.any { it.role == BoneRole.HEAD } && headRotationId in present) return headRotationId
		if (lineage.any { it.role in legRoles } && BodyStance.legsWarpId in present) return BodyStance.legsWarpId
		val body = lineage.firstOrNull { it.role.body }
		body?.let { bends[it.id] }?.let { return it.id }
		return if (body?.role == BoneRole.UPPER_BODY && breathId in present) breathId else bodyId
	}

	private fun addRotations(
		base: PuppetModel,
		bones: List<SkeletonBone>,
		parentOf: Map<String, SkeletonBone?>,
		spec: SkeletonSpec,
		frame: Bounds,
		bends: Map<String, BodyBend>,
		blend: Set<ParameterId>,
	): PuppetModel {
		val restWorlds = worlds(base, emptyMap())
		val partId = base.parts.firstOrNull { it.id.raw == "PartBody" }?.id
		val rotations = bones.map { bone ->
			val parentBone = parentOf[bone.id]
			val parentId = parentBone?.let { DeformerId(it.deformerId) } ?: attachDeformer(base, spec, bone, bends)
			// A bone's local frame has its up axis (-y) along the bone, so its children sit in the parent's
			// frame turned by the parent's own orientation.
			val origin = if (parentBone != null) {
				val local = SkeletonIk.rotate((bone.headX - parentBone.headX).toDouble(), (bone.headY - parentBone.headY).toDouble(),
					0.0, 0.0, -restOrientation(parentBone))
				floatArrayOf(local[0].toFloat(), local[1].toFloat())
			} else {
				invertWorld(
					restWorlds.getValue(parentId), bone.headX, bone.headY,
					floatArrayOf((bone.headX - frame.left) / frame.width.coerceAtLeast(1f), (bone.headY - frame.top) / frame.height.coerceAtLeast(1f)),
				)
			}
			Deformer.Rotation(
				id = DeformerId(bone.deformerId),
				name = bone.name,
				parent = parentId,
				partId = partId,
				baseAngle = SkeletonIk.wrap(restOrientation(bone) - (parentBone?.let(::restOrientation) ?: 0.0)).toFloat(),
				geometryGrid = null,
				handleLength = bone.length,
			).withOwnAngle(bone, origin[0], origin[1], 1f, blend)
		}
		var model = base.copy(deformers = base.deformers + rotations)
		// A warp can carry a slight turn or scale at rest; take it out of the base angle and the scale so
		// each bone rests exactly along the segment it was drawn as, with pixel-sized children.
		val rest = worlds(model, emptyMap())
		model = model.copy(deformers = model.deformers.map { deformer ->
			val bone = bones.firstOrNull { it.deformerId == deformer.id.raw } ?: return@map deformer
			if (parentOf[bone.id] != null || deformer !is Deformer.Rotation) return@map deformer
			val world = rest[deformer.id] as? RotationWorld ?: return@map deformer
			val drift = SkeletonIk.wrap((angleOf(world) - restOrientation(bone))).toFloat()
			val scale = scaleOf(world).takeIf { it > 1e-6f } ?: 1f
			val form = deformer.geometryGrid!!.cells.first().form
			deformer.copy(baseAngle = deformer.baseAngle - drift).withOwnAngle(bone, form.originX, form.originY, 1f / scale, blend)
		})
		return model
	}

	/** Direction of [bone] from head to tail in degrees, in the rig's convention (+x is 0, turning toward +y). */
	internal fun restHeading(bone: SkeletonBone): Double =
		SkeletonIk.heading((bone.tailX - bone.headX).toDouble(), (bone.tailY - bone.headY).toDouble())

	/**
	 * The rotation that points a deformer along [bone]. A Cubism rotation deformer's handle points up
	 * (local -y) at angle 0 and turns clockwise, so a bone pointing up rests at 0 and one pointing down
	 * at 180.
	 */
	internal fun restOrientation(bone: SkeletonBone): Double = SkeletonIk.wrap(restHeading(bone) + 90.0)

	/**
	 * The bone's own axis: the angle it turns from its rest heading. Keys at the limits and at rest are
	 * enough - a rotation deformer interpolates the angle itself, so every value between is exact. A bone
	 * in [blend] keeps one rest form and turns by a blend shape over the same keys, which interpolates the
	 * angle just as exactly.
	 */
	private fun Deformer.Rotation.withOwnAngle(
		bone: SkeletonBone,
		originX: Float,
		originY: Float,
		scale: Float,
		blend: Set<ParameterId>,
	): Deformer.Rotation {
		val id = ParameterId(bone.parameterId)
		val keys = floatArrayOf(bone.minAngle, 0f, bone.maxAngle).distinct().sorted().toFloatArray()
		if (id !in blend) {
			return copy(geometryGrid = KeyformGrid(listOf(KeyformAxis(id, keys)), keys.indices.map { i ->
				KeyformCell(intArrayOf(i), RotationPivotForm(originX, originY, keys[i] * bone.direction, scale))
			}))
		}
		val turn = blendBinding(id, keys) { i ->
			RotationForm(originX, originY, keys[i] * bone.direction, scale, false, false, opacity, multiplyColor, screenColor)
		}
		return copy(
			geometryGrid = KeyformGrid(emptyList(), listOf(KeyformCell(intArrayOf(), RotationPivotForm(originX, originY, 0f, scale)))),
			blendShapes = blendShapes.filterNot { it.parameterId == id } + turn,
		)
	}

	private fun addParameters(model: PuppetModel, bones: List<SkeletonBone>, blend: Set<ParameterId>): PuppetModel {
		var parameters = model.parameters
		for (bone in bones) {
			val id = ParameterId(bone.parameterId)
			val kind = if (id in blend) ParameterKind.BLEND_SHAPE else ParameterKind.NORMAL
			val existing = parameters.firstOrNull { it.id == id }
			parameters = if (existing == null) {
				parameters + Parameter(id, bone.name, bone.minAngle, bone.maxAngle, 0f, kind = kind)
			} else {
				parameters.map { if (it.id == id) it.copy(min = minOf(it.min, bone.minAngle), max = maxOf(it.max, bone.maxAngle), kind = kind) else it }
			}
		}
		return model.copy(parameters = parameters)
	}

	// ---------------------------------------------------------------------------------------------------
	// Leg poses

	/** A leg the poses can drive: a thigh and its shin, and the foot below them when there is one. */
	internal class Leg(val thigh: SkeletonBone, val shin: SkeletonBone, val foot: SkeletonBone?) {
		val ankleX: Double get() = shin.tailX.toDouble()
		val ankleY: Double get() = shin.tailY.toDouble()
		val reach: Double get() = (thigh.length + shin.length).toDouble()
	}

	/**
	 * The legs of [spec]: a thigh hanging from the body with a shin below it. A leg needs no mesh of its
	 * own - legs drawn on one layer bend in the legs warp by these joints (see [BodyStance]) - so every
	 * such chain stands.
	 */
	internal fun legs(spec: SkeletonSpec): List<Leg> {
		val bones = limbBones(spec)
		val ids = bones.mapTo(HashSet()) { it.id }
		val parentOf = bones.associate { it.id to limbParent(spec, it, ids) }
		return bones.filter { it.role == BoneRole.THIGH && parentOf[it.id]?.role?.body != false }.mapNotNull { thigh ->
			val shin = bones.firstOrNull { it.role == BoneRole.SHIN && parentOf[it.id]?.id == thigh.id } ?: return@mapNotNull null
			val foot = bones.firstOrNull { it.role == BoneRole.FOOT && parentOf[it.id]?.id == shin.id }
			Leg(thigh, shin, foot)
		}
	}

	/** Where the legs' knees give: away from the midpoint between the hips. */
	internal fun legCenter(legs: List<Leg>): Double = legs.map { it.thigh.headX.toDouble() }.average()

	/**
	 * How far the thigh and the shin of [leg] turn from rest, in world degrees, so that its ankle stays
	 * where it was drawn while its hip sits at ([hipX], [hipY]). Two-bone IK with the knee on the side
	 * away from [centerX], or toward it when [inward].
	 */
	internal fun legTurns(leg: Leg, hipX: Double, hipY: Double, centerX: Double, inward: Boolean = false): Pair<Double, Double> {
		val kneeX = leg.shin.headX.toDouble()
		val kneeY = leg.shin.headY.toDouble()
		val l1 = hypot(kneeX - leg.thigh.headX, kneeY - leg.thigh.headY)
		val l2 = hypot(leg.ankleX - kneeX, leg.ankleY - kneeY)
		val outward = (if (leg.thigh.headX < centerX) -1.0 else 1.0) * (if (inward) -1.0 else 1.0)
		val knee = listOf(1.0, -1.0)
			.map { SkeletonIk.twoBone(hipX, hipY, l1, l2, leg.ankleX, leg.ankleY, it) }
			.maxBy { it[0] * outward }
		val thighTurn = SkeletonIk.wrap(
			SkeletonIk.heading(knee[0] - hipX, knee[1] - hipY) - SkeletonIk.heading(kneeX - leg.thigh.headX, kneeY - leg.thigh.headY),
		)
		val shinTurn = SkeletonIk.wrap(
			SkeletonIk.heading(leg.ankleX - knee[0], leg.ankleY - knee[1]) - SkeletonIk.heading(leg.ankleX - kneeX, leg.ankleY - kneeY),
		)
		return thighTurn to shinTurn
	}

	/**
	 * Adds every rig pose of [SkeletonPoses.available] as a blend-shape parameter; the gestures play on
	 * the bones' own parameters instead (see [SkeletonPoses]).
	 *
	 * Cubism parameters cannot drive other parameters, so a pose is baked where it acts: the leg poses
	 * move the pelvis by the hip motion, bend the legs warp under it as a [BodyStance] pose, and turn every
	 * skinned leg's rotation deformers by the joint angles that keep its ankle planted, solved by two-bone
	 * IK at each key; the other poses turn their bones by
	 * [SkeletonPoses.rigTurns] - a body half by turning its warp's lattice about the waist, as its own
	 * parameter does. Each is a blend shape on those deformers, so it adds to the bones' own
	 * parameters - a posed limb can still be swung by hand - and poses add to each other instead of
	 * multiplying keyforms. Each leg pose is solved with the other at rest, so both at once only
	 * approximate the joint solve. The knee gives outward, which is how a front-facing figure reads as
	 * bending its knees, except under [SkeletonPoses.kneesIn].
	 */
	private fun addPoses(
		base: PuppetModel,
		spec: SkeletonSpec,
		bones: List<SkeletonBone>,
		poses: List<SkeletonPose>,
		bends: Map<String, BodyBend>,
		stance: BodyStance,
	): PuppetModel {
		if (poses.isEmpty()) return base
		var model = base.copy(
			parameters = base.parameters.filterNot { p -> SkeletonPoses.all.any { it.id == p.id } } +
				poses.map { Parameter(it.id, tr(it.nameKey), it.min, it.max, 0f, kind = ParameterKind.BLEND_SHAPE) },
		)
		// World degrees each bone turns per pose, one entry per key of the pose.
		val offsets = HashMap<String, LinkedHashMap<SkeletonPose, FloatArray>>()
		fun offset(boneId: String, pose: SkeletonPose) =
			offsets.getOrPut(boneId) { LinkedHashMap() }.getOrPut(pose) { FloatArray(pose.keys.size) }
		val legPoses = poses.filter { it.legs }
		val joints: Map<String, LegJointPose> = if (legPoses.isEmpty()) emptyMap() else addLegPoses(model, spec, legPoses, stance).let { (posed, joints) ->
			model = posed
			joints
		}
		for ((boneId, joint) in joints) for ((pose, turns) in joint.turns) {
			if (pose in legPoses) offset(boneId, pose).let { for (i in it.indices) it[i] += turns[i] }
		}
		val byId = bones.associateBy { it.id }
		for (pose in poses) for ((ki, key) in pose.keys.withIndex()) {
			for ((boneId, turn) in SkeletonPoses.rigTurns(spec, pose, key)) {
				val bone = byId[boneId] ?: continue
				offset(boneId, pose)[ki] += turn * bone.direction
			}
		}
		val defaults = model.parameters.associate { it.id to it.default }
		val default: (ParameterId) -> Float = { defaults[it] ?: 0f }
		val bendById = bends.values.associateBy { it.id }
		return model.copy(deformers = model.deformers.map { deformer ->
			when (deformer) {
				is Deformer.Rotation -> {
					val bone = bones.firstOrNull { it.deformerId == deformer.id.raw && !it.role.body }
					val table = bone?.let { offsets[it.id] } ?: return@map deformer
					val reference = rotationFormAt(deformer.geometryGrid, default) ?: RotationPivotForm(0f, 0f, 0f, 1f)
					deformer.copy(blendShapes = deformer.blendShapes.filterNot { b -> table.keys.any { it.id == b.parameterId } } +
						table.map { (pose, angles) ->
							poseBinding(pose) { ki ->
								RotationForm(reference.originX, reference.originY, reference.angle + angles[ki],
									reference.scale * (joints[bone.id]?.scales?.get(pose)?.get(ki) ?: 1f), false, false,
									deformer.opacity, deformer.multiplyColor, deformer.screenColor)
							}
						})
				}
				// A bend warp turns its lattice about the waist by what each of its halves takes from the pose, on
				// top of whatever the halves' own parameters hold.
				is Deformer.Warp -> {
					val bend = bendById[deformer.id] ?: return@map deformer
					val posed = poses.filter { pose -> bend.bones.any { offsets[it.id]?.containsKey(pose) == true } }
					if (posed.isEmpty()) return@map deformer
					val reference = warpControlPointsAt(deformer.geometryGrid, default) ?: return@map deformer
					val straight = bend.lattice(DoubleArray(bend.bones.size))
					deformer.copy(blendShapes = deformer.blendShapes.filterNot { b -> posed.any { it.id == b.parameterId } } +
						posed.map { pose ->
							poseBinding(pose) { ki ->
								val turned = bend.lattice(DoubleArray(bend.bones.size) { b ->
									offsets[bend.bones[b].id]?.get(pose)?.get(ki)?.toDouble() ?: 0.0
								})
								WarpForm(FloatArray(reference.size) { reference[it] + turned[it] - straight[it] },
									deformer.opacity, deformer.multiplyColor, deformer.screenColor)
							}
						})
				}
			}
		})
	}

	/** A blend binding of [pose] over its keys, with [form] at every key but the neutral one. */
	private fun <T : Any> poseBinding(pose: SkeletonPose, form: (Int) -> T): BlendShapeBinding<T> = blendBinding(pose.id, pose.keys, form)

	/** A blend binding of [parameterId] over [keys], which hold 0, with [form] at every key but the neutral one. */
	internal fun <T : Any> blendBinding(parameterId: ParameterId, keys: FloatArray, form: (Int) -> T): BlendShapeBinding<T> {
		val neutral = keys.indexOfFirst { it == 0f }
		return BlendShapeBinding(parameterId, keys, neutral, keys.indices.map { if (it == neutral) null else form(it) })
	}

	/**
	 * The leg [poses] as blend shapes: the hips' motion on the body warp, the legs bending under it on the
	 * legs warp, and how every skinned leg joint turns under them
	 * (see [solveLegPoses]).
	 */
	private fun addLegPoses(
		base: PuppetModel,
		spec: SkeletonSpec,
		poses: List<SkeletonPose>,
		stance: BodyStance,
	): Pair<PuppetModel, Map<String, LegJointPose>> {
		val legs = legs(spec)
		if (legs.isEmpty()) return base to emptyMap()
		val hipsId = bodyId
		val defaults = base.parameters.associate { it.id to it.default }
		val default: (ParameterId) -> Float = { defaults[it] ?: 0f }
		// Both warps are roots, so their control points are canvas pixels.
		fun shaped(warp: Deformer.Warp, moved: (SkeletonPose, Float, Double, Double) -> DoubleArray): Deformer.Warp {
			val reference = warpControlPointsAt(warp.geometryGrid, default) ?: return warp
			val opacity = warp.channelGrids.scalarAt(FormChannel.OPACITY, warp.opacity, default)
			val multiply = warp.channelGrids.colorAt(FormChannel.MULTIPLY_COLOR, warp.multiplyColor, default)
			val screen = warp.channelGrids.colorAt(FormChannel.SCREEN_COLOR, warp.screenColor, default)
			return warp.copy(blendShapes = warp.blendShapes.filterNot { b -> poses.any { it.id == b.parameterId } } + poses.map { pose ->
				poseBinding(pose) { ki ->
					val out = FloatArray(reference.size)
					for (i in reference.indices step 2) {
						val p = moved(pose, pose.keys[ki], reference[i].toDouble(), reference[i + 1].toDouble())
						out[i] = p[0].toFloat()
						out[i + 1] = p[1].toFloat()
					}
					WarpForm(out, opacity, multiply, screen)
				}
			})
		}
		val solved = HashMap<Pair<SkeletonPose, Float>, BodyStance.Solved>()
		fun solve(pose: SkeletonPose, key: Float) = solved.getOrPut(pose to key) { stance.Solved(stancePose(pose, key, stance)) }
		val model = base.copy(deformers = base.deformers.map { deformer ->
			when {
				deformer !is Deformer.Warp -> deformer
				deformer.id == hipsId -> shaped(deformer) { pose, key, x, y -> solve(pose, key).pelvis.apply(x, y) }
				deformer.id == BodyStance.legsWarpId -> shaped(deformer) { pose, key, x, y -> solve(pose, key).legPoint(x, y) }
				else -> deformer
			}
		})
		return model to solveLegPoses(model, legs, stance)
	}

	/** Hips lowered at a full crouch, in leg lengths. */
	private const val CROUCH_DROP = 0.1

	/** The stance of a leg pose at [key]: how far the hips move and how the knees give. */
	internal fun stancePose(pose: SkeletonPose, key: Float, stance: BodyStance): BodyStance.Pose {
		val k = key.toDouble()
		return when (pose) {
			SkeletonPoses.weight -> stance.weightPose(key)
			// Knees that meet in the middle need less drop than a crouch to read.
			SkeletonPoses.kneesIn -> BodyStance.Pose(drop = 0.05 * k, kneeIn = 40.0 * k)
			SkeletonPoses.hop -> BodyStance.Pose(lift = 0.18 * k)
			// A crouch bends the knees toward the viewer and a little apart.
			else -> BodyStance.Pose(drop = CROUCH_DROP * k, kneeIn = -8.0 * k)
		}
	}

	/**
	 * One leg joint under the leg poses: its turn relative to its parent, in world degrees, at every key
	 * of each of [SkeletonPoses.legPoses], each solved with the others at rest.
	 */
	internal class LegJointPose(val turns: Map<SkeletonPose, FloatArray>, val scales: Map<SkeletonPose, FloatArray> = emptyMap()) {
		/** The turn at the pose values [value], weighed between keys the way the evaluator weighs blend shapes. */
		fun turnAt(value: (SkeletonPose) -> Float): Float =
			turns.entries.sumOf { (pose, keys) -> at(pose, keys, value(pose)).toDouble() }.toFloat()

		fun scaleAt(value: (SkeletonPose) -> Float): Float =
			scales.entries.fold(1f) { scale, (pose, keys) -> scale * at(pose, keys, value(pose)) }

		private fun at(pose: SkeletonPose, turns: FloatArray, value: Float): Float =
			SkeletonPoses.bracket(pose, value).sumOf { (key, t) -> (turns[pose.keys.indexOfFirst { it == key }] * t).toDouble() }.toFloat()
	}

	/**
	 * Every leg joint of [legs] under the leg poses of [model], whose body warp already carries the hip
	 * motion. The IK is read against the body the evaluator actually builds, so the pivots it starts
	 * from are the ones the runtime will use. A thigh's turn is only right before the thighs carry their
	 * own pose shapes; the joints below it read the same either way.
	 */
	internal fun solveLegPoses(model: PuppetModel, legs: List<Leg>, stance: BodyStance? = null): Map<String, LegJointPose> {
		if (legs.isEmpty()) return emptyMap()
		val centerX = legCenter(legs)
		val rest = worlds(model, emptyMap())
		val weightScales = legs.flatMap { listOfNotNull(it.thigh.id, it.shin.id, it.foot?.id) }
			.associateWith { FloatArray(SkeletonPoses.weight.keys.size) { 1f } }
		fun turns(pose: SkeletonPose): List<Map<String, Float>> = pose.keys.map { key ->
			if (key == 0f || pose.airborne) return@map emptyMap()
			val posed = worlds(model, mapOf(pose.id to key))
			buildMap {
				for (leg in legs) {
					val thighId = DeformerId(leg.thigh.deformerId)
					val thigh = posed[thighId] as? RotationWorld ?: continue
					val hip = FloatArray(2).also { thigh.apply(0f, 0f, it, 0) }
					val inherited = SkeletonIk.wrap((angleOf(thigh) - angleOf(rest.getValue(thighId))).toDouble())
					val (thighTurn, shinTurn) = if (pose == SkeletonPoses.weight) {
						// Weight transfer bends toward the viewer, not sideways. Read the projected
						// joints from the stance warp and foreshorten each segment to reach them.
						val restWarp = rest[BodyStance.legsWarpId]
						val posedWarp = posed[BodyStance.legsWarpId]
						val fallback = stance?.Solved(stance.weightPose(key))
						fun project(x: Float, y: Float): DoubleArray {
							if (restWarp == null || posedWarp == null) return fallback?.legPoint(x.toDouble(), y.toDouble()) ?: doubleArrayOf(x.toDouble(), y.toDouble())
							val local = inverse(restWarp, x, y)
							val point = FloatArray(2).also { posedWarp.apply(local[0], local[1], it, 0) }
							return doubleArrayOf(point[0].toDouble(), point[1].toDouble())
						}
						val knee = project(leg.shin.headX, leg.shin.headY)
						val ankle = project(leg.ankleX.toFloat(), leg.ankleY.toFloat())
						val thighScale = (hypot(knee[0] - hip[0], knee[1] - hip[1]) / leg.thigh.length).toFloat()
						val shinScale = (hypot(ankle[0] - knee[0], ankle[1] - knee[1]) / leg.shin.length).toFloat()
						val inheritedScale = scaleOf(thigh) / scaleOf(rest.getValue(thighId) as RotationWorld)
						val index = pose.keys.indexOfFirst { it == key }
						weightScales.getValue(leg.thigh.id)[index] = thighScale / inheritedScale
						weightScales.getValue(leg.shin.id)[index] = shinScale / thighScale
						leg.foot?.let { weightScales.getValue(it.id)[index] = 1f / shinScale }
						SkeletonIk.wrap(SkeletonIk.heading(knee[0] - hip[0], knee[1] - hip[1]) - restHeading(leg.thigh)) to
							SkeletonIk.wrap(SkeletonIk.heading(ankle[0] - knee[0], ankle[1] - knee[1]) - restHeading(leg.shin))
					} else legTurns(leg, hip[0].toDouble(), hip[1].toDouble(), centerX, pose == SkeletonPoses.kneesIn)
					put(leg.thigh.id, SkeletonIk.wrap(thighTurn - inherited).toFloat())
					put(leg.shin.id, SkeletonIk.wrap(shinTurn - thighTurn).toFloat())
					leg.foot?.let { put(it.id, (-shinTurn).toFloat()) }
				}
			}
		}
		val solved = SkeletonPoses.legPoses.associateWith { turns(it) }
		return legs.flatMap { listOfNotNull(it.thigh.id, it.shin.id, it.foot?.id) }.associateWith { id ->
			LegJointPose(solved.mapValues { (_, keys) -> FloatArray(keys.size) { keys[it][id] ?: 0f } }, mapOf(SkeletonPoses.weight to weightScales.getValue(id)))
		}
	}

	/**
	 * A warp under each leg bone of [homes] over the meshes that hang from it, keyed on Body X, Body Y and
	 * the proportions.
	 *
	 * The legs warp moves the hips and bends the knees with the body parameters (see [BodyStance]), but a
	 * rotation deformer passes on only its pivot and its angle, so a leg skinned to bones would stand still
	 * under them. At every key the warp's lattice holds each of its points where the stance puts the point
	 * it covers at rest, read back through the bone as the evaluator places it there: the hips follow the
	 * body, the knees give and the feet stay down, whatever the bone itself inherits. At rest it is the
	 * identity, and the bone's own turns carry it as they carry the meshes. [hosts] receives the warp of
	 * each bone.
	 */
	private fun addLegStanceWarps(
		base: PuppetModel,
		homes: Map<SkeletonBone, List<DrawableId>>,
		canvas: Map<DrawableId, FloatArray>,
		stance: BodyStance,
		hosts: MutableMap<String, DeformerId>,
	): PuppetModel {
		if (!stance.standing || homes.isEmpty()) return base
		val axes = listOf(
			KeyformAxis(StandardParameters.BODY_X, floatArrayOf(-10f, 0f, 10f)),
			KeyformAxis(StandardParameters.BODY_Y, floatArrayOf(-10f, 0f, 10f)),
			KeyformAxis(StandardParameters.PROPORTION, floatArrayOf(-10f, 0f, 10f)),
		)
		var model = base
		for ((bone, drawables) in homes) {
			val boneId = DeformerId(bone.deformerId)
			val rotation = model.deformers.firstOrNull { it.id == boneId } as? Deformer.Rotation ?: continue
			val rest = worlds(model, emptyMap(), setOf(boneId)).getValue(boneId)
			var left = Float.MAX_VALUE; var top = Float.MAX_VALUE; var right = -Float.MAX_VALUE; var bottom = -Float.MAX_VALUE
			for (id in drawables) {
				val points = canvas[id] ?: continue
				for (i in points.indices step 2) {
					val local = inverse(rest, points[i], points[i + 1])
					left = minOf(left, local[0]); right = maxOf(right, local[0])
					top = minOf(top, local[1]); bottom = maxOf(bottom, local[1])
				}
			}
			if (left > right || top > bottom) continue
			// Room for the bone's own turns of the meshes' far ends, which the lattice carries on past its edges.
			val pad = (stance.legLength * LEG_STANCE_PAD).toFloat()
			left -= pad; right += pad; top -= pad; bottom += pad
			val columns = LEG_STANCE_COLUMNS
			val rows = ((bottom - top) / (stance.legLength * LEG_STANCE_ROW_SPACING)).toInt().coerceIn(4, 16)
			val restPoints = FloatArray((columns + 1) * (rows + 1) * 2)
			for (row in 0..rows) for (column in 0..columns) {
				val i = (row * (columns + 1) + column) * 2
				restPoints[i] = left + (right - left) * column / columns
				restPoints[i + 1] = top + (bottom - top) * row / rows
			}
			val restCanvas = FloatArray(restPoints.size)
			for (i in restPoints.indices step 2) rest.apply(restPoints[i], restPoints[i + 1], restCanvas, i)
			val cells = ArrayList<KeyformCell<WarpLatticeForm>>()
			for ((si, size) in axes[2].keys.withIndex()) for ((yi, y) in axes[1].keys.withIndex()) for ((xi, x) in axes[0].keys.withIndex()) {
				if (x == 0f && y == 0f && size == 0f) {
					cells += KeyformCell(intArrayOf(xi, yi, si), WarpLatticeForm(restPoints.copyOf()))
					continue
				}
				val solved = stance.Solved(stance.bodyPose(x, y))
				val values = mapOf(StandardParameters.BODY_X to x, StandardParameters.BODY_Y to y, StandardParameters.PROPORTION to size)
				val world = worlds(model, values, setOf(boneId)).getValue(boneId)
				val points = FloatArray(restPoints.size)
				for (i in restPoints.indices step 2) {
					val target = stance.legsAt(solved.legPoint(restCanvas[i].toDouble(), restCanvas[i + 1].toDouble()), size)
					val local = inverse(world, target[0].toFloat(), target[1].toFloat(), floatArrayOf(restPoints[i], restPoints[i + 1]))
					points[i] = local[0]
					points[i + 1] = local[1]
				}
				cells += KeyformCell(intArrayOf(xi, yi, si), WarpLatticeForm(points))
			}
			val id = DeformerId(bone.deformerId + "Stance")
			val warp = Deformer.Warp(id, tr("model.deformer.legStance", bone.name), boneId, rotation.partId, rows, columns, true,
				KeyformGrid(axes, cells))
			val at = model.deformers.indexOfFirst { it.id == boneId } + 1
			model = model.copy(deformers = model.deformers.subList(0, at) + warp + model.deformers.subList(at, model.deformers.size))
			hosts[bone.id] = id
		}
		return model
	}

	/** A leg bone's stance warp: columns across the leg, rows down it this far apart, and its margin, in leg lengths. */
	private const val LEG_STANCE_COLUMNS = 4
	private const val LEG_STANCE_ROW_SPACING = 0.06
	private const val LEG_STANCE_PAD = 0.08

	// ---------------------------------------------------------------------------------------------------
	// Skinning

	/**
	 * Re-homes one mesh under its proximal covered joint (or the best geometric fallback) and bakes the rest of its limb into
	 * corrective keyforms.
	 *
	 * Each keyform is computed against the deformer transforms the evaluator itself builds for that cell,
	 * so a rigid part of the mesh lands exactly where the bone's own deformer would put it and the joint
	 * blend starts and ends on those same positions.
	 */
	private fun skinDrawable(
		base: PuppetModel,
		drawableId: DrawableId,
		canvas: FloatArray,
		tree: List<SkeletonBone>,
		parentOf: Map<String, SkeletonBone?>,
		poses: List<SkeletonPose>,
		home: Int,
		blend: Set<ParameterId>,
		sampling: SkeletonSampling,
		manual: SkeletonWeightMap?,
		host: DeformerId? = null,
	): PuppetModel {
		val drawable = base.drawables.firstOrNull { it.id == drawableId } ?: return base
		val mesh = drawable.mesh ?: return base
		if (canvas.size != mesh.positions.size) return base
		val skinBones = skinBones(tree, parentOf)
		val skins = SkeletonManualWeights.weights(canvas, mesh.indices, tree, parentOf, manual)
		val arap = SkeletonArap(canvas, mesh.indices, BooleanArray(skins.size) { !skins[it].rigid })
		val surface = SkeletonSurfaceFairing(canvas, mesh.indices)
		val jointTemplates = SkeletonJointTemplates(canvas, mesh.indices, skins, skinBones, tree.map { it.role })
		val deformerOf = tree.map { DeformerId(it.deformerId) }

		// Bones whose angle changes where a vertex sits relative to home.
		val driving = sortedSetOf<Int>()
		for (moving in dependencies(skins, skinBones, home)) driving += moving

		// The mesh hangs from its home bone, or from the warp under it that carries it on the body parameters.
		val homeId = host ?: deformerOf[home]
		val relevant = deformerOf.toSet() + listOfNotNull(drawable.parentDeformerId, homeId)
		val rest = worlds(base, emptyMap(), relevant)
		val homeRest = rest.getValue(homeId)
		val restBase = FloatArray(canvas.size)
		for (i in canvas.indices step 2) {
			val local = inverse(homeRest, canvas[i], canvas[i + 1])
			restBase[i] = local[0]
			restBase[i + 1] = local[1]
		}

		val bindFrom = skins.mapIndexed { vertex, skin -> inverse(rest.getValue(deformerOf[skin.from]), canvas[vertex * 2], canvas[vertex * 2 + 1]) }
		val bindTo = skins.mapIndexed { vertex, skin -> inverse(rest.getValue(deformerOf[skin.to]), canvas[vertex * 2], canvas[vertex * 2 + 1]) }
		val restAngle = FloatArray(tree.size) { angleOf(rest.getValue(deformerOf[it])) }
		fun computeDeltas(values: Map<ParameterId, Float>): FloatArray {
			val posed = if (values.isEmpty()) rest else worlds(base, values, relevant)
			val homeWorld = posed.getValue(homeId)
			val out = FloatArray(canvas.size)
			val scratch = FloatArray(2)
			val other = FloatArray(2)
			val target = FloatArray(canvas.size)
			val seed = FloatArray(canvas.size)
			val angles = FloatArray(tree.size) { angleOf(posed.getValue(deformerOf[it])) - restAngle[it] }
			for ((vertex, skin) in skins.withIndex()) {
				val from = bindFrom[vertex]
				posed.getValue(deformerOf[skin.from]).apply(from[0], from[1], scratch, 0)
				if (!skin.rigid) {
					val to = bindTo[vertex]
					posed.getValue(deformerOf[skin.to]).apply(to[0], to[1], other, 0)
					for (axis in 0..1) scratch[axis] += (other[axis] - scratch[axis]) * skin.weight
				}
				target[vertex * 2] = scratch[0]; target[vertex * 2 + 1] = scratch[1]
				// The previous angular field is only an initial guess for ARAP, avoiding the
				// collapsed LBS starting state at a tight bend. It no longer defines the final skin.
				if (skin.rigid) { seed[vertex * 2] = scratch[0]; seed[vertex * 2 + 1] = scratch[1] } else {
					val joint = skinBones[skin.to]
					val p = SkeletonIk.rotate(canvas[vertex * 2].toDouble(), canvas[vertex * 2 + 1].toDouble(), joint.headX, joint.headY,
						SkeletonIk.wrap((angles[skin.to] - angles[skin.from]).toDouble()) * skin.weight)
					val local = inverse(rest.getValue(deformerOf[skin.from]), p[0].toFloat(), p[1].toFloat())
					posed.getValue(deformerOf[skin.from]).apply(local[0], local[1], seed, vertex * 2)
				}
			}
			// Carry cage residuals with the full parent affine transform, including stance
			// foreshortening. Rotation alone would detach the correction from scaled limbs.
			val carry = deformerOf.map { id ->
				val points = FloatArray(6)
				for ((i, point) in listOf(0f to 0f, 1f to 0f, 0f to 1f).withIndex()) {
					val local = inverse(rest.getValue(id), point.first, point.second)
					posed.getValue(id).apply(local[0], local[1], points, i * 2)
				}
				doubleArrayOf((points[2] - points[0]).toDouble(), (points[4] - points[0]).toDouble(),
					(points[3] - points[1]).toDouble(), (points[5] - points[1]).toDouble())
			}
			val corrected = corrected(arap, jointTemplates, surface, target, seed, angles, carry)
			for (vertex in skins.indices) {
				val inHome = inverse(homeWorld, corrected[vertex * 2], corrected[vertex * 2 + 1])
				out[vertex * 2] = inHome[0] - restBase[vertex * 2]
				out[vertex * 2 + 1] = inHome[1] - restBase[vertex * 2 + 1]
			}
			return out
		}
		// Fitting, grid baking and pose shapes often request the same sample. Cache the solve;
		// return a copy because additive pose shapes subtract their reference in place.
		val samples = HashMap<Map<ParameterId, Float>, FloatArray>()
		fun deltasAt(values: Map<ParameterId, Float>): FloatArray {
			val key = values.filterValues { it != 0f }
			return samples.getOrPut(key) { computeDeltas(key) }.copyOf()
		}

		// Each bone keyed as sparsely as its arcs allow; the blend-shape bones add, the rest multiply.
		val ranges = LinkedHashMap<ParameterId, Pair<Float, Float>>()
		for (bone in driving.map { tree[it] }) {
			val id = ParameterId(bone.parameterId)
			val range = ranges[id]
			ranges[id] = if (range == null) bone.minAngle to bone.maxAngle else minOf(range.first, bone.minAngle) to maxOf(range.second, bone.maxAngle)
		}
		// Stance warps use normalized coordinates, while rotation homes use pixels. Measure the
		// interpolation error in canvas pixels in both cases; a tolerance in normalized space would
		// accept almost any inverse rotation arc and shrink the proximal limb between its keys.
		val sides = ranges.mapValues { (id, range) -> fittedSides(range, sampling) { value ->
			val deltas = deltasAt(mapOf(id to value))
			FloatArray(deltas.size).also { points ->
				for (i in deltas.indices step 2) homeRest.apply(restBase[i] + deltas[i], restBase[i + 1] + deltas[i + 1], points, i)
			}
		} }
		val axes = gridAxes(sides.filterKeys { it !in blend }, ranges, sampling)
		val blendAxes = sides.filterKeys { it in blend }.map { (id, side) -> KeyformAxis(id, keysOf(ranges.getValue(id), side.first, side.second)) }

		val skinGrid = if (axes.isEmpty()) null else KeyformGrid(axes, cartesian(axes).map { coordinate ->
			val values = axes.indices.associate { axes[it].parameterId to axes[it].keys[coordinate[it]] }
			KeyformCell(coordinate, MeshDeltaForm(deltasAt(values)))
		})

		// Whatever the mesh was keyed on before, carried into the new space and laid under the skin.
		val parentWorld = drawable.parentDeformerId?.let { rest[it] }
		fun carried(deltas: FloatArray): FloatArray {
			val out = FloatArray(deltas.size)
			val scratch = FloatArray(2)
			for (i in deltas.indices step 2) {
				val x = mesh.positions[i] + deltas[i]
				val y = mesh.positions[i + 1] + deltas[i + 1]
				if (parentWorld != null) parentWorld.apply(x, y, scratch, 0) else { scratch[0] = x; scratch[1] = y }
				val local = inverse(homeRest, scratch[0], scratch[1])
				out[i] = local[0] - restBase[i]
				out[i + 1] = local[1] - restBase[i + 1]
			}
			return out
		}
		val carriedGrid = drawable.geometryGrid?.let { grid ->
			KeyformGrid(grid.axes, grid.cells.map { KeyformCell(it.coordinate, MeshDeltaForm(carried(it.form.positionDeltas))) })
		}
		val grid = combine(carriedGrid, skinGrid)
		val blendShapes = drawable.blendShapes.map { binding ->
			binding.copy(forms = binding.forms.map { form ->
				form?.let { MeshForm(carried(it.positionDeltas), it.drawOrder, it.opacity, it.multiplyColor, it.screenColor) }
			})
		}
		val skinned = drawable.copy(
			parentDeformerId = homeId,
			mesh = DrawableMesh(restBase, mesh.uvs, foldDrawOrder(canvas, mesh.indices, skins, skinBones)),
			geometryGrid = grid,
			blendShapes = blendShapes,
		)
		val added = poses.map { KeyformAxis(it.id, it.keys) } + blendAxes
		val posed = skinned.copy(blendShapes = skinned.blendShapes + additiveShapes(base, skinned, added, ::deltasAt))
		return base.copy(drawables = base.drawables.map { if (it.id == drawableId) posed else it })
	}

	/**
	 * The skin at one pose: [target] (each vertex where its bones carry it, blended across its joint) corrected by
	 * the joint templates, the ARAP solve seeded with [seed] and the surface fairing. [angles] is each bone's turn
	 * from rest in degrees and [carry] the linear part of each bone's transform, row-major.
	 */
	internal fun corrected(arap: SkeletonArap, jointTemplates: SkeletonJointTemplates, surface: SkeletonSurfaceFairing,
						   target: FloatArray, seed: FloatArray, angles: FloatArray, carry: List<DoubleArray>): FloatArray {
		val (guide, guideWeights) = jointTemplates.guide(seed, angles, carry, target)
		val folding = jointTemplates.folding(angles)
		val corrected = arap.solve(target, seed, guide, guideWeights, folding)
		val closedFold = jointTemplates.folding(angles, closed = true)
		for (v in 0 until corrected.size / 2) if (closedFold[v]) {
			corrected[v * 2] = guide[v * 2]; corrected[v * 2 + 1] = guide[v * 2 + 1]
		}
		surface.apply(corrected, jointTemplates.fairing(angles))
		return corrected
	}

	/** Painter order for a folded 2D limb: draw proximal material before distal material.
	 * Delaunay insertion order is unrelated to surface depth and alternates the two branches
	 * along the contact seam. Vertex IDs, winding, UVs and connectivity stay unchanged. */
	internal fun foldDrawOrder(canvas: FloatArray, indices: IntArray, skins: List<VertexSkin>, bones: List<SkinBone>): IntArray {
		if (skins.isEmpty() || skins.all { it.rigid && it.from == skins[0].from }) return indices
		fun depth(bone: Int): Double = if (bones[bone].parent < 0) 0.0 else 1.0 + depth(bones[bone].parent)
		val depths = DoubleArray(bones.size) { depth(it) }
		val material = DoubleArray(skins.size) { v ->
			val skin = skins[v]
			val bone = bones[skin.to]
			val ux = bone.tailX - bone.headX; val uy = bone.tailY - bone.headY
			val length2 = ux * ux + uy * uy
			val axial = if (length2 > 1e-8) ((canvas[v * 2] - bone.headX) * ux + (canvas[v * 2 + 1] - bone.headY) * uy) / length2 else 0.0
			depths[skin.to] + axial.coerceIn(-1.0, 1.0)
		}
		val faces = (0 until indices.size / 3).sortedBy { face -> (0..2).sumOf { material[indices[face * 3 + it]] } }
		return IntArray(indices.size) { indices[faces[it / 3] * 3 + it % 3] }
	}

	/**
	 * The blend shapes the poses and the blend-shape bones in [axes] bend [drawable] by: at each key, how
	 * far that parameter alone moves every vertex relative to its home bone, as [deltasAt] measures it
	 * through the deformers it turns. One that only carries the mesh rigidly with its home bone leaves it
	 * no shape.
	 */
	private fun additiveShapes(
		model: PuppetModel,
		drawable: Drawable,
		axes: List<KeyformAxis>,
		deltasAt: (Map<ParameterId, Float>) -> FloatArray,
	): List<BlendShapeBinding<MeshForm>> {
		if (axes.isEmpty()) return emptyList()
		val defaults = model.parameters.associate { it.id to it.default }
		val default: (ParameterId) -> Float = { defaults[it] ?: 0f }
		val rest = deltasAt(emptyMap())
		val reference = meshGridDefaultDeltas(drawable, default) ?: FloatArray(rest.size)
		val drawOrder = drawable.channelGrids.scalarAt(FormChannel.DRAW_ORDER, drawable.drawOrder, default)
		val opacity = drawable.channelGrids.scalarAt(FormChannel.OPACITY, drawable.opacity, default)
		val multiply = drawable.channelGrids.colorAt(FormChannel.MULTIPLY_COLOR, drawable.multiplyColor, default)
		val screen = drawable.channelGrids.colorAt(FormChannel.SCREEN_COLOR, drawable.screenColor, default)
		return axes.mapNotNull { axis ->
			val shapes = axis.keys.map { key ->
				if (key == 0f) null else deltasAt(mapOf(axis.parameterId to key)).also { for (i in it.indices) it[i] -= rest[i] }
			}
			if (shapes.all { shape -> shape == null || shape.all { abs(it) < POSE_EPSILON } }) return@mapNotNull null
			blendBinding(axis.parameterId, axis.keys) { ki ->
				val shape = shapes[ki]!!
				MeshForm(FloatArray(shape.size) { reference[it] + shape[it] }, drawOrder, opacity, multiply, screen)
			}
		}
	}

	/**
	 * The bone a mesh hangs under: its most proximal covered joint, then a geometric fallback.
	 *
	 * A mesh reaching the limb's attachment follows that bone exactly at the attachment, even if most
	 * vertices lie farther down the limb. When its art starts below that joint, the first joint inside
	 * the mesh is its home. If the mesh covers no joint, prefer an uncoupled bone with the most skin load.
	 * Every other joint is baked into the mesh's own keyforms. This choice is shared with seam welding
	 * and blend-shape planning, so the rig and glue agree on which bone carries each part.
	 */
	internal fun homeBone(skins: List<VertexSkin>, bones: List<SkinBone>, canvas: FloatArray, triangles: IntArray,
		blendable: (Int) -> Boolean = { false }): Int {
		val load = DoubleArray(bones.size)
		for (skin in skins) {
			load[skin.from] += 1.0 - skin.weight
			load[skin.to] += skin.weight.toDouble()
		}
		// A joint covered by the mesh is an attachment point, regardless of how many vertices lie
		// farther along the limb. Choose the most proximal covered joint: its portion then follows a
		// rotation deformer exactly, with no inverse keyform arc that can drift between parameter keys.
		// This also places a short sleeve under its elbow when the shoulder lies outside that mesh.
		fun depth(index: Int): Int {
			var current = index
			var result = 0
			while (bones[current].parent >= 0) { result++; current = bones[current].parent }
			return result
		}
		val points = List(canvas.size / 2) { Point(canvas[it * 2].toDouble(), canvas[it * 2 + 1].toDouble()) }
		val covered = bones.indices.filter { load[it] > 0.0 && covers(points, triangles, bones[it].headX, bones[it].headY) }
		if (covered.isNotEmpty()) return covered.minWith(compareBy<Int> { depth(it) }.thenByDescending { load[it] })
		// A mesh that covers no joint keeps the geometric choice that minimizes coupled axes.
		fun coupled(home: Int) = dependencies(skins, bones, home).any { moving -> moving.size > 1 && moving.any(blendable) }
		val carrying = load.indices.filter { load[it] > 0.0 }.ifEmpty { load.indices.toList() }
		return carrying.minWith(compareBy<Int> { if (coupled(it)) 1 else 0 }.thenByDescending { load[it] })
	}

	/** Whether a joint lies on the mesh surface in the rest pose. */
	private fun covers(points: List<Point>, triangles: IntArray, x: Double, y: Double): Boolean {
		val joint = Point(x, y)
		for (i in triangles.indices step 3) {
			val a = points[triangles[i]]
			val b = points[triangles[i + 1]]
			val c = points[triangles[i + 2]]
			if (x < minOf(a.x, b.x, c.x) || x > maxOf(a.x, b.x, c.x) ||
				y < minOf(a.y, b.y, c.y) || y > maxOf(a.y, b.y, c.y)) continue
			val twiceArea = (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)
			if (abs(twiceArea) > 1e-8 && pointInTriangleInclusive(joint, a, b, c)) return true
		}
		return false
	}

	/**
	 * Per vertex, the bones whose turn moves it relative to [home]: every joint between the bone it
	 * follows and home, and the joint it blends across.
	 */
	private fun dependencies(skins: List<VertexSkin>, bones: List<SkinBone>, home: Int): List<Set<Int>> {
		fun chain(index: Int): Set<Int> = generateSequence(index) { bones[it].parent.takeIf { p -> p >= 0 } }.toSet()
		val homeChain = chain(home)
		val chains = HashMap<Int, Set<Int>>()
		return skins.map { skin ->
			val own = chains.getOrPut(skin.from) { chain(skin.from) }
			val moving = (own - homeChain) + (homeChain - own)
			if (skin.rigid) moving else moving + skin.to
		}
	}

	/** Per vertex, every bone whose turn moves it on the canvas: the bones up from the one it follows, and the joint it blends across. */
	internal fun canvasDependencies(skins: List<VertexSkin>, bones: List<SkinBone>): List<Set<Int>> {
		val chains = HashMap<Int, Set<Int>>()
		fun chain(index: Int): Set<Int> = chains.getOrPut(index) { generateSequence(index) { bones[it].parent.takeIf { p -> p >= 0 } }.toSet() }
		return skins.map { skin -> if (skin.rigid) chain(skin.from) else chain(skin.from) + chain(skin.to) }
	}

	/**
	 * How many evenly spaced keys each side of 0 needs across [range] so the linear blend between two
	 * neighbours of [at] - a mesh's home-space deltas at a value - strays from it by no more than
	 * the configured tolerance, with keys never closer than the configured minimum step.
	 */
	internal fun fittedSides(range: Pair<Float, Float>, sampling: SkeletonSampling, at: (Float) -> FloatArray): Pair<Int, Int> {
		val memo = HashMap<Float, FloatArray>()
		fun sample(value: Float) = memo.getOrPut(value) { at(value) }
		fun side(limit: Float): Int {
			val most = ceil(abs(limit) / sampling.minimumStepDegrees - 1e-6).toInt()
			return (1..most).firstOrNull { n ->
				(0 until n).all { i -> straight(sample(limit * i / n), sample(limit * (i + 1) / n), sample(limit * (i + 0.5f) / n), sampling.tolerancePx) }
			} ?: most
		}
		return side(range.first) to side(range.second)
	}

	/** Whether every vertex of [middle] lies within [tolerance] of halfway between [a] and [b]. */
	private fun straight(a: FloatArray, b: FloatArray, middle: FloatArray, tolerance: Float): Boolean {
		for (i in middle.indices step 2) {
			val dx = middle[i] - (a[i] + b[i]) * 0.5
			val dy = middle[i + 1] - (a[i + 1] + b[i + 1]) * 0.5
			if (dx * dx + dy * dy > tolerance * tolerance) return false
		}
		return true
	}

	/**
	 * The keyform axes of a mesh's multiplying bones, each with the keys of [sides] on either side of 0,
	 * thinned from the densest side down while the grid would hold more than the configured limit.
	 */
	internal fun gridAxes(sides: Map<ParameterId, Pair<Int, Int>>, ranges: Map<ParameterId, Pair<Float, Float>>, sampling: SkeletonSampling): List<KeyformAxis> {
		val counts = sides.mapValues { intArrayOf(it.value.first, it.value.second) }
		while (counts.values.fold(1L) { acc, c ->
			if (acc > sampling.maxMeshKeyforms) acc else acc * (c[0] + c[1] + 1)
		} > sampling.maxMeshKeyforms) {
			val densest = counts.values.maxBy { it[0] + it[1] }
			val side = if (densest[0] >= densest[1]) 0 else 1
			if (densest[side] <= 1) break
			densest[side]--
		}
		return counts.map { (id, c) -> KeyformAxis(id, keysOf(ranges.getValue(id), c[0], c[1])) }
	}

	/** Keys across [range] (which holds 0) no more than [step] degrees apart, even on each side of 0. */
	private fun angleKeys(range: Pair<Float, Float>, step: Double): FloatArray =
		keysOf(range, ceil(abs(range.first) / step - 1e-6).toInt(), ceil(range.second / step - 1e-6).toInt())

	/**
	 * The widest step that keeps a pivot [radius] pixels out within the configured tolerance of its arc
	 * between two keys, and never narrower than the configured minimum step.
	 */
	private fun arcStep(radius: Double, sampling: SkeletonSampling): Double =
		if (radius <= sampling.tolerancePx) 90.0 else max(sampling.minimumStepDegrees.toDouble(), Math.toDegrees(2.0 * acos(1.0 - sampling.tolerancePx / radius)))

	/** [below] evenly spaced keys from [range]'s start to 0 and [above] from 0 to its end, 0 among them. */
	internal fun keysOf(range: Pair<Float, Float>, below: Int, above: Int): FloatArray {
		return (-below..above).map { i ->
			when {
				i < 0 -> range.first * (-i).toFloat() / below
				i > 0 -> range.second * i.toFloat() / above
				else -> 0f
			}
		}.toFloatArray()
	}

	/** Every coordinate of a grid over [axes], axis 0 fastest. */
	internal fun cartesian(axes: List<KeyformAxis>): List<IntArray> {
		val total = axes.fold(1) { acc, axis -> acc * axis.keys.size }
		return (0 until total).map { linear ->
			var rest = linear
			IntArray(axes.size) { axis ->
				val size = axes[axis].keys.size
				(rest % size).also { rest /= size }
			}
		}
	}

	/**
	 * Two independent delta grids summed over the union of their axes. When they share an axis the sum is
	 * not defined per cell, and the skin wins: it is the deformation the skeleton exists for.
	 */
	private fun combine(first: KeyformGrid<MeshDeltaForm>?, second: KeyformGrid<MeshDeltaForm>?): KeyformGrid<MeshDeltaForm>? {
		if (first == null) return second
		if (second == null) return first
		if (first.axes.any { a -> second.axes.any { it.parameterId == a.parameterId } }) return second
		val axes = first.axes + second.axes
		val firstCells = first.cellsByLinearIndex
		val secondCells = second.cellsByLinearIndex
		return KeyformGrid(axes, cartesian(axes).mapNotNull { coordinate ->
			val a = firstCells[first.linearIndexOf(coordinate.copyOfRange(0, first.axes.size))] ?: return@mapNotNull null
			val b = secondCells[second.linearIndexOf(coordinate.copyOfRange(first.axes.size, coordinate.size))] ?: return@mapNotNull null
			KeyformCell(coordinate, MeshDeltaForm(FloatArray(a.form.positionDeltas.size) { a.form.positionDeltas[it] + b.form.positionDeltas[it] }))
		})
	}

	/** Split parts welded at rest: the model, every mesh's rest vertices after, and the glues to add. */
	private class Seams(val model: PuppetModel, val canvas: Map<DrawableId, FloatArray>, val glues: List<Glue>)

	/**
	 * Welds the parts of a split limb together wherever they overlap, the way the glue brush welds a
	 * stroke over the whole of both meshes.
	 *
	 * Every vertex of one part that lies over the other, or within [GLUE_TOLERANCE] of its outline, gets a
	 * partner there: a nearby vertex of the other part slides onto it, or the other part gains a vertex
	 * exactly there. Both only rearrange the mesh under the same picture, and each pair starts on one
	 * point, so the glue never pulls the artwork at rest. Skinning alone moves two overlapping parts almost
	 * alike; the glue closes what their different triangles and keys leave, and holds through any keyform
	 * later added to one side only.
	 *
	 * Parts hanging under the same bone move identically and are left alone. A part in [lockedTopology]
	 * keeps its vertices and only pairs with seeds already on one of them.
	 */
	private fun weldSplitParts(
		base: PuppetModel,
		baseCanvas: Map<DrawableId, FloatArray>,
		drawableRoot: Map<String, String>,
		treeBones: Map<String, List<SkeletonBone>>,
		parentOf: Map<String, SkeletonBone?>,
		lockedTopology: Set<String>,
		candidates: Set<String>,
		manualWeights: Map<String, SkeletonWeightMap>,
	): Seams {
		var model = base
		val canvas = HashMap(baseCanvas)
		val glues = ArrayList<Glue>()
		for ((root, tree) in treeBones) {
			val members = drawableRoot.filterValues { it == root }.keys.map(::DrawableId)
			if (members.size < 2) continue
			val skinBones = skinBones(tree, parentOf)
			val home = members.associateWith { id ->
				val frame = canvas.getValue(id)
				val triangles = model.drawables.first { it.id == id }.mesh!!.indices
				homeBone(SkeletonManualWeights.weights(frame, triangles, tree, parentOf, manualWeights[id.raw]), skinBones, frame, triangles) { tree[it].parameterId in candidates }
			}
			fun isAncestor(ancestor: Int, bone: Int) =
				generateSequence(skinBones[bone].parent.takeIf { it >= 0 }) { skinBones[it].parent.takeIf { p -> p >= 0 } }.any { it == ancestor }
			for (i in members.indices) for (j in i + 1 until members.size) {
				val a = members[i]
				val b = members[j]
				val homeA = home.getValue(a)
				val homeB = home.getValue(b)
				if (homeA == homeB) continue
				if (model.glues.any { (it.meshA == a && it.meshB == b) || (it.meshA == b && it.meshB == a) }) continue
				val frameA = canvas.getValue(a)
				val frameB = canvas.getValue(b)
				val welded = weldGlueFrames(
					model, a, b, frameA, frameB,
					hitsA = (0 until frameA.size / 2).toSet(),
					hitsB = (0 until frameB.size / 2).toSet(),
					tolerance = GLUE_TOLERANCE,
					fixedA = a.raw in lockedTopology,
					fixedB = b.raw in lockedTopology,
				)
				model = welded.model
				canvas[a] = welded.frameA
				canvas[b] = welded.frameB
				if (welded.pairs.isEmpty()) continue
				val (weightA, weightB) = when {
					isAncestor(homeA, homeB) -> GLUE_PARENT_WEIGHT to GLUE_CHILD_WEIGHT
					isAncestor(homeB, homeA) -> GLUE_CHILD_WEIGHT to GLUE_PARENT_WEIGHT
					else -> GLUE_DEFAULT_WEIGHT to GLUE_DEFAULT_WEIGHT
				}
				val pairs = welded.pairs.map { GluePair(it.indexA, it.indexB, weightA, weightB) }
				glues += Glue(a, b, pairs, intensity = 1f, id = "GlueSkel__${a.raw}__${b.raw}")
			}
		}
		return Seams(model, canvas, glues)
	}

	/**
	 * Bone deformers with nothing under them - no mesh and no other deformer - removed, deepest first, and
	 * the legs bend with them when no leg or tail hangs from it.
	 */
	private fun pruneEmptyBones(model: PuppetModel, bones: List<SkeletonBone>, held: Set<DeformerId>): PuppetModel {
		val boneDeformers = bones.mapTo(HashSet()) { DeformerId(it.deformerId) }
		var deformers = model.deformers
		while (true) {
			val used = HashSet<DeformerId>(held)
			deformers.forEach { d -> d.parent?.let(used::add) }
			model.drawables.forEach { d -> d.parentDeformerId?.let(used::add) }
			val empty = deformers.filter { it.id in boneDeformers && it.id !in used }.mapTo(HashSet()) { it.id }
			if (empty.isEmpty()) break
			deformers = deformers.filterNot { it.id in empty }
		}
		return model.copy(deformers = deformers)
	}

	/**
	 * Bone rotations that hold no mesh but still carry the bones below them folded into those bones, top
	 * down, so one mesh never has more than one rotation deformer: a thigh and shin drawn as one mesh hang
	 * under the thigh alone, and the shoe below takes the knee into its own keyforms instead of hanging
	 * from a shin rotation that turns nothing else.
	 *
	 * Only a link under another bone's rotation folds: angles add across two rotations, so the child's
	 * angle is the sum of both and only its pivot needs keys along the link's arc (see [foldLink]).
	 */
	private fun foldLinkBones(model: PuppetModel, bones: List<SkeletonBone>, sampling: SkeletonSampling, held: Set<DeformerId>): PuppetModel {
		val boneDeformers = bones.mapTo(HashSet()) { DeformerId(it.deformerId) }
		val boneParameters = bones.mapTo(HashSet()) { ParameterId(it.parameterId) }
		var result = model
		for (bone in bones) {
			val id = DeformerId(bone.deformerId)
			val link = result.deformers.firstOrNull { it.id == id } as? Deformer.Rotation ?: continue
			val parent = link.parent?.takeIf { it in boneDeformers } ?: continue
			if (result.deformers.none { it.id == parent && it is Deformer.Rotation }) continue
			if (id in held || result.drawables.any { it.parentDeformerId == id }) continue
			val children = result.deformers.filter { it.parent == id }
			if (children.isEmpty() || children.any { it !is Deformer.Rotation }) continue
			result = foldLink(result, link, parent, children.map { it as Deformer.Rotation }, boneParameters, sampling)
		}
		return result
	}

	/**
	 * Re-hangs [children] from [link]'s parent [host] with [link] folded into each. The link's axes join
	 * each child's, keyed densely enough that the child's pivot keeps to its arc between keys as closely
	 * as the meshes around it keep to theirs; its angle and scale compose with the child's, and its pose
	 * shapes add to the child's. A link whose own turn is a blend shape passes it on the same way, re-keyed
	 * along the arc.
	 */
	private fun foldLink(
		model: PuppetModel,
		link: Deformer.Rotation,
		host: DeformerId,
		children: List<Deformer.Rotation>,
		boneParameters: Set<ParameterId>,
		sampling: SkeletonSampling,
	): PuppetModel {
		val defaults = model.parameters.associate { it.id to it.default }
		val default: (ParameterId) -> Float = { defaults[it] ?: 0f }
		// The children's pivots swing round the link's at this reach, keyed to stay as close to the arc as the
		// meshes around them stay to theirs.
		val reach = children.maxOf { child -> rotationFormAt(child.geometryGrid, default)?.let { hypot(it.originX, it.originY) } ?: 0f }
		val step = arcStep(reach.toDouble(), sampling)
		val linkAxes = link.geometryGrid!!.axes.map { axis -> KeyformAxis(axis.parameterId, angleKeys(axis.keys.first() to axis.keys.last(), step)) }
		val linkRest = rotationFormAt(link.geometryGrid, default) ?: RotationPivotForm(0f, 0f, 0f, 1f)
		val linkShapes = link.blendShapes.map { binding ->
			if (binding.parameterId !in boneParameters || binding.keys.size < 2) binding
			else resampled(binding, linkRest, angleKeys(binding.keys.first() to binding.keys.last(), step))
		}

		val folded = children.associate { child ->
			val keys = LinkedHashMap<ParameterId, FloatArray>()
			for (axis in linkAxes + child.geometryGrid!!.axes) {
				keys[axis.parameterId] = (keys[axis.parameterId]?.let { it + axis.keys } ?: axis.keys).distinct().sorted().toFloatArray()
			}
			val axes = keys.map { KeyformAxis(it.key, it.value) }
			val childRest = rotationFormAt(child.geometryGrid, default) ?: RotationPivotForm(0f, 0f, 0f, 1f)

			// The child's pivot in the host's space, where the evaluator puts it at [values].
			fun pivot(values: Map<ParameterId, Float>): FloatArray {
				val worlds = worlds(model, values, setOf(child.id))
				val world = worlds.getValue(child.id) as RotationWorld
				return inverse(worlds.getValue(host), world.xform.ox, world.xform.oy)
			}

			val grid = KeyformGrid(axes, cartesian(axes).map { coordinate ->
				val values = axes.indices.associate { axes[it].parameterId to axes[it].keys[coordinate[it]] }
				val at: (ParameterId) -> Float = { values[it] ?: default(it) }
				val l = rotationFormAt(link.geometryGrid, at)!!
				val c = rotationFormAt(child.geometryGrid, at)!!
				val origin = pivot(values)
				KeyformCell(coordinate, RotationPivotForm(origin[0], origin[1], l.angle + c.angle, l.scale * c.scale))
			})

			val poses = (linkShapes + child.blendShapes).map { it.parameterId }.distinct()
			val blendShapes = child.blendShapes.filterNot { it.parameterId in poses } + poses.map { pose ->
				val linkShape = linkShapes.firstOrNull { it.parameterId == pose }
				val childShape = child.blendShapes.firstOrNull { it.parameterId == pose }
				val binding = childShape ?: linkShape!!
				binding.copy(forms = binding.keys.indices.map { ki ->
					if (ki == binding.neutralIndex) return@map null
					val l = linkShape?.forms?.getOrNull(ki)
					val c = childShape?.forms?.getOrNull(ki)
					val origin = pivot(mapOf(pose to binding.keys[ki]))
					RotationForm(origin[0], origin[1], (l?.angle ?: linkRest.angle) + (c?.angle ?: childRest.angle),
						(l?.scale ?: linkRest.scale) * (c?.scale ?: childRest.scale), false, false,
						child.opacity, child.multiplyColor, child.screenColor)
				})
			}
			child.id to child.copy(parent = host, baseAngle = link.baseAngle + child.baseAngle, geometryGrid = grid, blendShapes = blendShapes)
		}
		return model.copy(deformers = model.deformers.mapNotNull { if (it.id == link.id) null else folded[it.id] ?: it })
	}

	/** [binding], a turn linear between its keys, re-keyed at [keys]; [rest] stands at its neutral key. */
	private fun resampled(binding: BlendShapeBinding<RotationForm>, rest: RotationPivotForm, keys: FloatArray): BlendShapeBinding<RotationForm> {
		val sample = binding.forms.firstNotNullOf { it }
		fun at(i: Int) = binding.forms[i] ?: RotationForm(rest.originX, rest.originY, rest.angle, rest.scale,
			sample.flipX, sample.flipY, sample.opacity, sample.multiplyColor, sample.screenColor)
		return blendBinding(binding.parameterId, keys) { k ->
			val value = keys[k]
			val upper = binding.keys.indexOfFirst { it >= value }.coerceIn(1, binding.keys.size - 1)
			val a = at(upper - 1)
			val b = at(upper)
			val t = ((value - binding.keys[upper - 1]) / (binding.keys[upper] - binding.keys[upper - 1])).coerceIn(0f, 1f)
			fun mix(x: Float, y: Float) = x + (y - x) * t
			RotationForm(mix(a.originX, b.originX), mix(a.originY, b.originY), mix(a.angle, b.angle), mix(a.scale, b.scale),
				a.flipX, a.flipY, a.opacity, a.multiplyColor, a.screenColor)
		}
	}

	// ---------------------------------------------------------------------------------------------------
	// Deformer transforms

	internal fun worlds(model: PuppetModel, values: Map<ParameterId, Float>, only: Set<DeformerId>? = null): Map<DeformerId, DeformerWorld> {
		val defaults = model.parameters.associate { it.id to it.default }
		val deformers = if (only == null) model.deformers else {
			// [only] and every ancestor, which is all a transform of [only] depends on.
			val byId = model.deformers.associateBy { it.id }
			val keep = HashSet<DeformerId>()
			for (id in only) {
				var next: DeformerId? = id
				while (next != null && keep.add(next)) next = byId[next]?.parent
			}
			model.deformers.filter { it.id in keep }
		}
		return buildDeformerWorlds(deformers, { values[it] ?: defaults[it] ?: 0f }, { defaults[it] ?: 0f })
	}

	/** World angle of a deformer's local x axis, degrees. A warp reads as the turn at its center. */
	internal fun angleOf(world: DeformerWorld): Float = when (world) {
		is RotationWorld -> Math.toDegrees(atan2(world.xform.c14.toDouble(), world.xform.c12.toDouble())).toFloat()
		else -> {
			val a = FloatArray(2).also { world.apply(0.5f, 0.5f, it, 0) }
			val b = FloatArray(2).also { world.apply(0.51f, 0.5f, it, 0) }
			Math.toDegrees(atan2((b[1] - a[1]).toDouble(), (b[0] - a[0]).toDouble())).toFloat()
		}
	}

	private fun scaleOf(world: RotationWorld): Float = hypot(world.xform.c12, world.xform.c14)

	/** Where ([x], [y]) in world space sits in [world]'s local space. */
	internal fun inverse(world: DeformerWorld, x: Float, y: Float, guess: FloatArray? = null): FloatArray = when (world) {
		is RotationWorld -> {
			val m = world.xform
			val det = m.c12 * m.c13 - m.c15 * m.c14
			val dx = x - m.ox
			val dy = y - m.oy
			floatArrayOf((m.c13 * dx - m.c15 * dy) / det, (-m.c14 * dx + m.c12 * dy) / det)
		}
		else -> invertWorld(world, x, y, guess ?: floatArrayOf(0.5f, 0.5f))
	}

	/** Newton's method on a warp: the local point whose image is ([x], [y]). */
	private fun invertWorld(world: DeformerWorld, x: Float, y: Float, guess: FloatArray): FloatArray {
		var u = guess[0].toDouble()
		var v = guess[1].toDouble()
		val p = FloatArray(2)
		val pu = FloatArray(2)
		val pv = FloatArray(2)
		repeat(40) {
			world.apply(u.toFloat(), v.toFloat(), p, 0)
			val ex = x - p[0].toDouble()
			val ey = y - p[1].toDouble()
			if (ex * ex + ey * ey < 1e-8) return floatArrayOf(u.toFloat(), v.toFloat())
			val h = 1e-3
			world.apply((u + h).toFloat(), v.toFloat(), pu, 0)
			world.apply(u.toFloat(), (v + h).toFloat(), pv, 0)
			val a = (pu[0] - p[0]) / h
			val c = (pu[1] - p[1]) / h
			val b = (pv[0] - p[0]) / h
			val d = (pv[1] - p[1]) / h
			val det = a * d - b * c
			if (abs(det) < 1e-12) return floatArrayOf(u.toFloat(), v.toFloat())
			u += (d * ex - b * ey) / det
			v += (-c * ex + a * ey) / det
		}
		return floatArrayOf(u.toFloat(), v.toFloat())
	}
}
