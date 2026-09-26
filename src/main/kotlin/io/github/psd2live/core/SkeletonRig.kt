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
import org.umamo.runtime.model.WarpForm
import org.umamo.runtime.model.WarpLatticeForm
import org.umamo.runtime.model.withDerivedRenderRoot
import io.github.psd2live.i18n.tr
import kotlin.math.abs
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
 * - **The two body halves bend warps at the end of the body chain.** The torso bend sits under the
 *   breath warp and the legs bend under the body warp beside it, each an identity lattice over the
 *   character that turns what lies past the waist about it and blends across a band there (see
 *   [BodyBend], [addBodyWarps]). Every body turn, breath and bend above passes straight through them, to
 *   the torso meshes and to the limbs and the head that hang from them.
 * - **A rotation deformer per limb bone**, nested as the bones are and hung from its body half's bend. A
 *   rotation deformer interpolates its angle rather than its vertices, so a limb turned to any parameter
 *   value is an exact rigid rotation - it neither shortens between keys nor multiplies keyforms when
 *   several joints move at once.
 * - **Corrective mesh keyforms across each joint.** A mesh that spans a joint hangs under one of the
 *   bones (its "home") and its keyforms carry the rest of the limb: vertices past a joint turn with the
 *   bone across it, and vertices inside the joint band turn by a weighted fraction of that angle about the
 *   joint (see [SkeletonWeights]). The keys are dense in angle, so the linear blend between them stays
 *   on the arc.
 *
 * On top of these, every preset pose of [SkeletonPoses] - a crouch, a weight shift, a tail swing - is one
 * blend-shape parameter that adds its turns to the bones and its bends to the meshes (see [addPoses]).
 * The leg poses are solved by two-bone IK at bake time so the feet stay where they are while the hips
 * move.
 */
internal object SkeletonRig {
	private val bodyId = DeformerId("DeformBodyXY")
	private val breathId = DeformerId("DeformBodyZBreath")
	private val headRotationId = DeformerId("DeformHeadRotation")

	/** The torso's bend: under the breath warp, over every mesh and limb the torso carries. */
	val torsoWarpId = DeformerId("DeformSkelTorso")

	/** The legs' bend: under the body warp beside the breath warp, over the legs and the tail. */
	val legsWarpId = DeformerId("DeformSkelLegs")
	private val skeletonGroupId = ParameterGroupId("ParamGroupSkeleton")

	/** Widest angle step between two keys of a corrective keyform axis. */
	private const val KEY_STEP = 7.5

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

	/** Most keyforms one mesh may carry; beyond it the keys thin out evenly. */
	private const val MAX_MESH_CELLS = 600

	/** Home-space units below which a pose leaves a mesh no shape of its own. */
	private const val POSE_EPSILON = 1e-4f

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
	 * Bakes [spec] into [base]. [frame] is the character bounds the body warp spans. Meshes in
	 * [lockedTopology] keep their vertices: they carry hand-made topology edits that replay by vertex
	 * index, which new vertices would misplace.
	 */
	fun apply(base: PuppetModel, spec: SkeletonSpec, frame: Bounds, lockedTopology: Set<String> = emptySet()): PuppetModel {
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
			val (refined, frameAfter) = SkeletonMeshRefine.refine(model, drawableId, canvas.getValue(drawableId), bands)
			model = refined
			canvas = canvas + (drawableId to frameAfter)
		}

		// 2b. Split parts of one limb welded wherever they overlap, before skinning so every new vertex
		// gets its joints baked like the rest.
		val seams = weldSplitParts(model, canvas, drawableRoot, treeBones, parentOf, lockedTopology)
		model = seams.model
		canvas = seams.canvas

		// 3. The body halves spliced into the body chain as warps - the head rotation and everything else on
		// the breath warp ends up under the upper body - and a rotation deformer per limb bone hung from them.
		val bends = LinkedHashMap<String, BodyBend>()
		model = addBodyWarps(model, bones.filter { it.role.body }, frame, bends)
		model = addRotations(model, joints, parentOf, spec, frame, bends)
		model = addParameters(model, bones)

		// 4. The preset poses, hips that move while the feet stay put among them.
		val poses = SkeletonPoses.available(spec)
		model = addPoses(model, spec, bones, poses, bends)

		// 5. Every skinned mesh under its home bone, with its joints baked into its keyforms and its poses
		// into its blend shapes.
		for ((id, root) in drawableRoot) {
			val drawableId = DrawableId(id)
			model = skinDrawable(model, drawableId, canvas.getValue(drawableId), treeBones.getValue(root), parentOf, poses)
		}

		// 6. The welded parts glued, no deformer left holding nothing, and no joint inside one mesh left as a
		// rotation of its own.
		model = model.copy(glues = model.glues + seams.glues)
		model = pruneEmptyBones(model, joints)
		model = foldLinkBones(model, joints)
		model = withSkeletonGroup(model, bones, poses)
		return model.withDerivedRenderRoot()
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
		fun grid(): KeyformGrid<WarpLatticeForm> {
			val axes = bones.map { KeyformAxis(ParameterId(it.parameterId), angleKeys(it.minAngle to it.maxAngle, KEY_STEP)) }
			return KeyformGrid(axes, cartesian(axes).map { coordinate ->
				KeyformCell(coordinate, WarpLatticeForm(lattice(DoubleArray(bones.size) { b ->
					axes[b].keys[coordinate[b]] * bones[b].direction.toDouble()
				})))
			})
		}
	}

	/**
	 * Splices the body halves' bends into the body chain.
	 *
	 * The torso bend goes under the breath warp (the body warp when there is none) and takes over all its
	 * children: the torso and the clothes, the arms, the wings and the head. It turns with both halves, so
	 * a skirt follows the hips and a coat bends at the waist. The legs bend goes under the body warp beside
	 * the breath, with the lower body alone: the legs and the tail hang from it, so breathing never lifts
	 * the feet off the ground, while the hips turn them exactly as they turn the skirt.
	 *
	 * Being the identity in its host's space at rest, a bend warp moves nothing it takes over and changes
	 * none of its coordinates. [bends] receives the warp each body bone is read from.
	 */
	private fun addBodyWarps(base: PuppetModel, bodies: List<SkeletonBone>, frame: Bounds, bends: MutableMap<String, BodyBend>): PuppetModel {
		if (bodies.isEmpty()) return base
		val halves = bodies.sortedBy { if (it.role == BoneRole.UPPER_BODY) 0 else 1 }
		val torsoHost = if (base.deformers.any { it.id == breathId }) breathId else bodyId
		var model = splice(base, torsoWarpId, tr("model.deformer.skeletonTorso"), torsoHost, halves, frame, adoptDeformers = true, bends)
		val lower = halves.firstOrNull { it.role == BoneRole.LOWER_BODY }
		if (lower != null && torsoHost != bodyId) {
			model = splice(model, legsWarpId, tr("model.deformer.skeletonLegs"), bodyId, listOf(lower), frame, adoptDeformers = false, bends)
		}
		return model
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
	): PuppetModel {
		val hostRest = worlds(model, emptyMap(), setOf(host))[host] ?: return model
		val bend = BodyBend(id, hostRest, bones, frame)
		val partId = model.parts.firstOrNull { it.id.raw == "PartBody" }?.id
		val warp = Deformer.Warp(id, name, host, partId, bend.rows, bend.columns, true, bend.grid())
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

	/** The deformer [bone] turns: a limb bone's own rotation, or the bend warp a body half is read from. */
	fun deformerOf(model: PuppetModel, bone: SkeletonBone): DeformerId = when {
		!bone.role.body -> DeformerId(bone.deformerId)
		bone.role == BoneRole.LOWER_BODY && model.deformers.any { it.id == legsWarpId } -> legsWarpId
		else -> torsoWarpId
	}

	// ---------------------------------------------------------------------------------------------------
	// Rotation deformers

	/**
	 * The deformer a limb bone with no limb parent hangs from. Bones on the head turn with the head Z
	 * rotation; a limb of a body half hangs from that half's warp, so it rides every bend of the body chain
	 * down to there. Without one, an upper limb takes the breath warp and anything else the body warp.
	 */
	private fun attachDeformer(model: PuppetModel, spec: SkeletonSpec, bone: SkeletonBone, bends: Map<String, BodyBend>): DeformerId {
		val lineage = generateSequence(bone) { it.parentId?.let(spec::bone) }
		val present = model.deformers.mapTo(HashSet()) { it.id }
		if (lineage.any { it.role == BoneRole.HEAD } && headRotationId in present) return headRotationId
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
				geometryGrid = ownAngleGrid(bone, origin[0], origin[1], 1f),
				handleLength = bone.length,
			)
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
			deformer.copy(baseAngle = deformer.baseAngle - drift, geometryGrid = ownAngleGrid(bone, form.originX, form.originY, 1f / scale))
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
	 * enough - a rotation deformer interpolates the angle itself, so every value between is exact.
	 */
	private fun ownAngleGrid(bone: SkeletonBone, originX: Float, originY: Float, scale: Float): KeyformGrid<RotationPivotForm> {
		val keys = floatArrayOf(bone.minAngle, 0f, bone.maxAngle).distinct().sorted().toFloatArray()
		val axis = KeyformAxis(ParameterId(bone.parameterId), keys)
		return KeyformGrid(listOf(axis), keys.indices.map { i ->
			KeyformCell(intArrayOf(i), RotationPivotForm(originX, originY, keys[i] * bone.direction, scale))
		})
	}

	private fun addParameters(model: PuppetModel, bones: List<SkeletonBone>): PuppetModel {
		var parameters = model.parameters
		for (bone in bones) {
			val id = ParameterId(bone.parameterId)
			val existing = parameters.firstOrNull { it.id == id }
			parameters = if (existing == null) {
				parameters + Parameter(id, bone.name, bone.minAngle, bone.maxAngle, 0f)
			} else {
				parameters.map { if (it.id == id) it.copy(min = minOf(it.min, bone.minAngle), max = maxOf(it.max, bone.maxAngle)) else it }
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
	 * The legs the poses can drive. A leg with no mesh skinned to it is left out: bending its bones would
	 * move nothing, while the hips would still sink and carry the unskinned legs down with them.
	 */
	internal fun legs(spec: SkeletonSpec): List<Leg> {
		val bones = limbBones(spec)
		val ids = bones.mapTo(HashSet()) { it.id }
		val parentOf = bones.associate { it.id to limbParent(spec, it, ids) }
		return bones.filter { it.role == BoneRole.THIGH && parentOf[it.id]?.role?.body != false }.mapNotNull { thigh ->
			val shin = bones.firstOrNull { it.role == BoneRole.SHIN && parentOf[it.id]?.id == thigh.id } ?: return@mapNotNull null
			val foot = bones.firstOrNull { it.role == BoneRole.FOOT && parentOf[it.id]?.id == shin.id }
			Leg(thigh, shin, foot).takeIf { leg -> listOfNotNull(leg.thigh, leg.shin, leg.foot).any { it.drawableIds.isNotEmpty() } }
		}
	}

	/** Where the legs' knees give: away from the midpoint between the hips. */
	internal fun legCenter(legs: List<Leg>): Double = legs.map { it.thigh.headX.toDouble() }.average()

	/**
	 * How far the thigh and the shin of [leg] turn from rest, in world degrees, so that its ankle stays
	 * where it was drawn while its hip sits at ([hipX], [hipY]). Two-bone IK with the knee on the side
	 * away from [centerX].
	 */
	internal fun legTurns(leg: Leg, hipX: Double, hipY: Double, centerX: Double): Pair<Double, Double> {
		val kneeX = leg.shin.headX.toDouble()
		val kneeY = leg.shin.headY.toDouble()
		val l1 = hypot(kneeX - leg.thigh.headX, kneeY - leg.thigh.headY)
		val l2 = hypot(leg.ankleX - kneeX, leg.ankleY - kneeY)
		val outward = if (leg.thigh.headX < centerX) -1.0 else 1.0
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

	/** The rigid motion the body warp makes at crouch [t] and weight [w]: a turn about the hips, then a shift. */
	private class HipMotion(val cx: Double, val cy: Double, val degrees: Double, val dx: Double, val dy: Double) {
		fun apply(x: Double, y: Double): DoubleArray {
			val p = SkeletonIk.rotate(x, y, cx, cy, degrees)
			return doubleArrayOf(p[0] + dx, p[1] + dy)
		}
	}

	/**
	 * Adds every pose of [SkeletonPoses.available] as a blend-shape parameter.
	 *
	 * Cubism parameters cannot drive other parameters, so a pose is baked where it acts: the leg poses
	 * move the body warp by the hip motion and turn every leg's rotation deformers by the joint angles
	 * that keep its ankle planted, solved by two-bone IK at each key; the other poses turn their bones by
	 * [SkeletonPoses.boneTurns] - a body half by turning its warp's lattice about the waist, as its own
	 * parameter does. Each is a blend shape on those deformers, so it adds to the bones' own
	 * parameters - a posed limb can still be swung by hand - and poses add to each other instead of
	 * multiplying keyforms. Each leg pose is solved with the other at rest, so both at once only
	 * approximate the joint solve. The knee always gives outward, which is how a front-facing figure
	 * reads as bending its knees.
	 */
	private fun addPoses(
		base: PuppetModel,
		spec: SkeletonSpec,
		bones: List<SkeletonBone>,
		poses: List<SkeletonPose>,
		bends: Map<String, BodyBend>,
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
		val joints: Map<String, LegJointPose> = if (legPoses.isEmpty()) emptyMap() else addLegPoses(model, spec, legPoses).let { (posed, joints) ->
			model = posed
			joints
		}
		for ((boneId, joint) in joints) {
			offset(boneId, SkeletonPoses.crouch).let { for (i in it.indices) it[i] += joint.crouch[i] }
			offset(boneId, SkeletonPoses.weight).let { for (i in it.indices) it[i] += joint.weight[i] }
		}
		val byId = bones.associateBy { it.id }
		for (pose in poses) for ((ki, key) in pose.keys.withIndex()) {
			for ((boneId, turn) in SkeletonPoses.boneTurns(spec, pose, key)) {
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
								RotationForm(reference.originX, reference.originY, reference.angle + angles[ki], reference.scale, false, false,
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
	private fun <T : Any> poseBinding(pose: SkeletonPose, form: (Int) -> T): BlendShapeBinding<T> {
		val neutral = pose.keys.indexOfFirst { it == 0f }
		return BlendShapeBinding(pose.id, pose.keys, neutral, pose.keys.indices.map { if (it == neutral) null else form(it) })
	}

	/**
	 * The hip motion of the leg [poses] as blend shapes on the body warp, and how every leg joint turns
	 * under them (see [solveLegPoses]).
	 */
	private fun addLegPoses(
		base: PuppetModel,
		spec: SkeletonSpec,
		poses: List<SkeletonPose>,
	): Pair<PuppetModel, Map<String, LegJointPose>> {
		val legs = legs(spec)
		if (legs.isEmpty()) return base to emptyMap()
		val body = base.deformers.firstOrNull { it.id == bodyId } as? Deformer.Warp ?: return base to emptyMap()
		val centerX = legCenter(legs)
		val centerY = legs.map { it.thigh.headY.toDouble() }.average()
		val legLength = legs.map { it.reach }.average()
		val crouchDrop = legLength * 0.06
		val shift = legLength * 0.045
		val tilt = 3.0

		fun motion(t: Double, w: Double, weightDrop: Double) =
			HipMotion(centerX, centerY, -tilt * w, shift * w, crouchDrop * t + weightDrop * abs(w))

		// Shifting the hips sideways would stretch the far leg; sink them until both feet stay in reach.
		var weightDrop = 0.0
		for (sign in listOf(-1.0, 1.0)) {
			var drop = 0.0
			while (drop < legLength * 0.2) {
				val m = motion(0.0, sign, drop)
				val ok = legs.all { leg ->
					val hip = m.apply(leg.thigh.headX.toDouble(), leg.thigh.headY.toDouble())
					hypot(leg.ankleX - hip[0], leg.ankleY - hip[1]) <= leg.reach * 0.995
				}
				if (ok) break
				drop += legLength * 0.002
			}
			weightDrop = max(weightDrop, drop)
		}
		fun motionOf(pose: SkeletonPose, key: Float) =
			if (pose == SkeletonPoses.crouch) motion(key.toDouble(), 0.0, weightDrop) else motion(0.0, key.toDouble(), weightDrop)

		val defaults = base.parameters.associate { it.id to it.default }
		val default: (ParameterId) -> Float = { defaults[it] ?: 0f }
		val reference = warpControlPointsAt(body.geometryGrid, default) ?: return base to emptyMap()
		val opacity = body.channelGrids.scalarAt(FormChannel.OPACITY, body.opacity, default)
		val multiply = body.channelGrids.colorAt(FormChannel.MULTIPLY_COLOR, body.multiplyColor, default)
		val screen = body.channelGrids.colorAt(FormChannel.SCREEN_COLOR, body.screenColor, default)
		val bodyShapes = poses.map { pose ->
			poseBinding(pose) { ki ->
				val m = motionOf(pose, pose.keys[ki])
				val moved = FloatArray(reference.size)
				for (i in reference.indices step 2) {
					val p = m.apply(reference[i].toDouble(), reference[i + 1].toDouble())
					moved[i] = p[0].toFloat()
					moved[i + 1] = p[1].toFloat()
				}
				WarpForm(moved, opacity, multiply, screen)
			}
		}
		val model = base.copy(deformers = base.deformers.map { deformer ->
			if (deformer.id != bodyId) deformer
			else body.copy(blendShapes = body.blendShapes.filterNot { b -> poses.any { it.id == b.parameterId } } + bodyShapes)
		})

		return model to solveLegPoses(model, legs)
	}

	/**
	 * One leg joint under the two leg poses: its turn relative to its parent, in world degrees, at every
	 * key of [SkeletonPoses.crouch] and of [SkeletonPoses.weight], each solved with the other at rest.
	 */
	internal class LegJointPose(val crouch: FloatArray, val weight: FloatArray) {
		/** The turn at crouch [c] and weight [w], weighed between keys the way the evaluator weighs blend shapes. */
		fun turnAt(c: Float, w: Float): Float = at(SkeletonPoses.crouch, crouch, c) + at(SkeletonPoses.weight, weight, w)

		private fun at(pose: SkeletonPose, turns: FloatArray, value: Float): Float =
			SkeletonPoses.bracket(pose, value).sumOf { (key, t) -> (turns[pose.keys.indexOfFirst { it == key }] * t).toDouble() }.toFloat()
	}

	/**
	 * Every leg joint of [legs] under the leg poses of [model], whose body warp already carries the hip
	 * motion. The IK is read against the body the evaluator actually builds, so the pivots it starts
	 * from are the ones the runtime will use. A thigh's turn is only right before the thighs carry their
	 * own pose shapes; the joints below it read the same either way.
	 */
	internal fun solveLegPoses(model: PuppetModel, legs: List<Leg>): Map<String, LegJointPose> {
		if (legs.isEmpty()) return emptyMap()
		val centerX = legCenter(legs)
		val rest = worlds(model, emptyMap())
		fun turns(pose: SkeletonPose): List<Map<String, Float>> = pose.keys.map { key ->
			if (key == 0f) return@map emptyMap()
			val posed = worlds(model, mapOf(pose.id to key))
			buildMap {
				for (leg in legs) {
					val thighId = DeformerId(leg.thigh.deformerId)
					val thigh = posed[thighId] ?: continue
					val hip = FloatArray(2).also { thigh.apply(0f, 0f, it, 0) }
					val inherited = SkeletonIk.wrap((angleOf(thigh) - angleOf(rest.getValue(thighId))).toDouble())
					val (thighTurn, shinTurn) = legTurns(leg, hip[0].toDouble(), hip[1].toDouble(), centerX)
					put(leg.thigh.id, SkeletonIk.wrap(thighTurn - inherited).toFloat())
					put(leg.shin.id, SkeletonIk.wrap(shinTurn - thighTurn).toFloat())
					leg.foot?.let { put(it.id, (-shinTurn).toFloat()) }
				}
			}
		}
		val crouch = turns(SkeletonPoses.crouch)
		val weight = turns(SkeletonPoses.weight)
		return legs.flatMap { listOfNotNull(it.thigh.id, it.shin.id, it.foot?.id) }.associateWith { id ->
			LegJointPose(FloatArray(crouch.size) { crouch[it][id] ?: 0f }, FloatArray(weight.size) { weight[it][id] ?: 0f })
		}
	}

	// ---------------------------------------------------------------------------------------------------
	// Skinning

	/**
	 * Re-homes one mesh under the bone that carries most of it and bakes the rest of its limb into
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
	): PuppetModel {
		val drawable = base.drawables.firstOrNull { it.id == drawableId } ?: return base
		val mesh = drawable.mesh ?: return base
		if (canvas.size != mesh.positions.size) return base
		val skinBones = skinBones(tree, parentOf)
		val skins = SkeletonWeights.skin(canvas, skinBones)
		val deformerOf = tree.map { DeformerId(it.deformerId) }

		val home = homeBone(skins, skinBones)

		// Bones whose angle changes where a vertex sits relative to home.
		fun chain(index: Int): Set<Int> = generateSequence(index) { skinBones[it].parent.takeIf { p -> p >= 0 } }.toSet()
		val homeChain = chain(home)
		val driving = sortedSetOf<Int>()
		for (skin in skins) {
			driving += (chain(skin.from) - homeChain) + (homeChain - chain(skin.from))
			if (!skin.rigid) driving += skin.to
		}
		val axes = meshAxes(driving.map { tree[it] })

		val relevant = deformerOf.toSet() + listOfNotNull(drawable.parentDeformerId)
		val rest = worlds(base, emptyMap(), relevant)
		val homeRest = rest.getValue(deformerOf[home])
		val restBase = FloatArray(canvas.size)
		for (i in canvas.indices step 2) {
			val local = inverse(homeRest, canvas[i], canvas[i + 1])
			restBase[i] = local[0]
			restBase[i + 1] = local[1]
		}

		val restAngle = FloatArray(tree.size) { angleOf(rest.getValue(deformerOf[it])) }
		fun deltasAt(values: Map<ParameterId, Float>): FloatArray {
			val posed = if (values.isEmpty()) rest else worlds(base, values, relevant)
			val homeWorld = posed.getValue(deformerOf[home])
			val out = FloatArray(canvas.size)
			val scratch = FloatArray(2)
			for ((vertex, skin) in skins.withIndex()) {
				var x = canvas[vertex * 2].toDouble()
				var y = canvas[vertex * 2 + 1].toDouble()
				if (!skin.rigid) {
					val relative = (angleOf(posed.getValue(deformerOf[skin.to])) - restAngle[skin.to]) -
						(angleOf(posed.getValue(deformerOf[skin.from])) - restAngle[skin.from])
					val pivot = skinBones[skin.to]
					val turned = SkeletonIk.rotate(x, y, pivot.headX, pivot.headY, SkeletonIk.wrap(relative.toDouble()) * skin.weight)
					x = turned[0]
					y = turned[1]
				}
				val local = inverse(rest.getValue(deformerOf[skin.from]), x.toFloat(), y.toFloat())
				posed.getValue(deformerOf[skin.from]).apply(local[0], local[1], scratch, 0)
				val inHome = inverse(homeWorld, scratch[0], scratch[1])
				out[vertex * 2] = inHome[0] - restBase[vertex * 2]
				out[vertex * 2 + 1] = inHome[1] - restBase[vertex * 2 + 1]
			}
			return out
		}

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
			parentDeformerId = deformerOf[home],
			mesh = DrawableMesh(restBase, mesh.uvs, mesh.indices),
			geometryGrid = grid,
			blendShapes = blendShapes,
		)
		val posed = skinned.copy(blendShapes = skinned.blendShapes + poseShapes(base, skinned, poses, ::deltasAt))
		return base.copy(drawables = base.drawables.map { if (it.id == drawableId) posed else it })
	}

	/**
	 * The blend shapes the [poses] bend [drawable] by: at each key, how far the pose alone moves every
	 * vertex relative to its home bone, as [deltasAt] measures it through the deformers the poses turn.
	 * A pose that only carries the mesh rigidly with its home bone leaves it no shape.
	 */
	private fun poseShapes(
		model: PuppetModel,
		drawable: Drawable,
		poses: List<SkeletonPose>,
		deltasAt: (Map<ParameterId, Float>) -> FloatArray,
	): List<BlendShapeBinding<MeshForm>> {
		if (poses.isEmpty()) return emptyList()
		val defaults = model.parameters.associate { it.id to it.default }
		val default: (ParameterId) -> Float = { defaults[it] ?: 0f }
		val rest = deltasAt(emptyMap())
		val reference = meshGridDefaultDeltas(drawable, default) ?: FloatArray(rest.size)
		val drawOrder = drawable.channelGrids.scalarAt(FormChannel.DRAW_ORDER, drawable.drawOrder, default)
		val opacity = drawable.channelGrids.scalarAt(FormChannel.OPACITY, drawable.opacity, default)
		val multiply = drawable.channelGrids.colorAt(FormChannel.MULTIPLY_COLOR, drawable.multiplyColor, default)
		val screen = drawable.channelGrids.colorAt(FormChannel.SCREEN_COLOR, drawable.screenColor, default)
		return poses.mapNotNull { pose ->
			val shapes = pose.keys.map { key ->
				if (key == 0f) null else deltasAt(mapOf(pose.id to key)).also { for (i in it.indices) it[i] -= rest[i] }
			}
			if (shapes.all { shape -> shape == null || shape.all { abs(it) < POSE_EPSILON } }) return@mapNotNull null
			poseBinding(pose) { ki ->
				val shape = shapes[ki]!!
				MeshForm(FloatArray(shape.size) { reference[it] + shape[it] }, drawOrder, opacity, multiply, screen)
			}
		}
	}

	/**
	 * The bone a mesh hangs under: the one carrying most of it.
	 *
	 * Each part of a split limb hangs under its own bone, pivoting on its joint. A mesh that spans
	 * several bones hangs under the one it mostly draws - a stocking under the shin even when its top
	 * reaches up the thigh - so turning that bone's rotation deformer turns the part of the drawing it
	 * names. The rest of the mesh follows the other joints in its keyforms: the bones above it keep their
	 * rotations as pivots that carry it, and those below it hold nothing and are pruned or folded.
	 */
	internal fun homeBone(skins: List<VertexSkin>, bones: List<SkinBone>): Int {
		val load = DoubleArray(bones.size)
		for (skin in skins) {
			load[skin.from] += 1.0 - skin.weight
			load[skin.to] += skin.weight.toDouble()
		}
		return load.indices.maxBy { load[it] }
	}

	/**
	 * The keyform axes of a mesh driven by [bones]: each bone's own parameter keyed densely in angle.
	 * Poses are not axes; they reach the mesh as blend shapes.
	 */
	private fun meshAxes(bones: List<SkeletonBone>): List<KeyformAxis> {
		val own = LinkedHashMap<ParameterId, Pair<Float, Float>>()
		for (bone in bones) {
			val id = ParameterId(bone.parameterId)
			val range = own[id]
			own[id] = if (range == null) bone.minAngle to bone.maxAngle else minOf(range.first, bone.minAngle) to maxOf(range.second, bone.maxAngle)
		}
		var step = KEY_STEP
		var ownKeys = own.mapValues { angleKeys(it.value, step) }
		while (ownKeys.values.fold(1) { acc, keys -> acc * keys.size } > MAX_MESH_CELLS && step < 90.0) {
			step *= 1.15
			ownKeys = own.mapValues { angleKeys(it.value, step) }
		}
		return ownKeys.map { KeyformAxis(it.key, it.value) }
	}

	/** Keys across [range] (which holds 0) no more than [step] degrees apart, even on each side of 0. */
	private fun angleKeys(range: Pair<Float, Float>, step: Double): FloatArray {
		val below = ceil(abs(range.first) / step - 1e-6).toInt()
		val above = ceil(range.second / step - 1e-6).toInt()
		return (-below..above).map { i ->
			when {
				i < 0 -> range.first * (-i).toFloat() / below
				i > 0 -> range.second * i.toFloat() / above
				else -> 0f
			}
		}.toFloatArray()
	}

	/** Every coordinate of a grid over [axes], axis 0 fastest. */
	private fun cartesian(axes: List<KeyformAxis>): List<IntArray> {
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
	): Seams {
		var model = base
		val canvas = HashMap(baseCanvas)
		val glues = ArrayList<Glue>()
		for ((root, tree) in treeBones) {
			val members = drawableRoot.filterValues { it == root }.keys.map(::DrawableId)
			if (members.size < 2) continue
			val skinBones = skinBones(tree, parentOf)
			val home = members.associateWith { homeBone(SkeletonWeights.skin(canvas.getValue(it), skinBones), skinBones) }
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
	private fun pruneEmptyBones(model: PuppetModel, bones: List<SkeletonBone>): PuppetModel {
		val boneDeformers = bones.mapTo(HashSet()) { DeformerId(it.deformerId) } + legsWarpId
		var deformers = model.deformers
		while (true) {
			val used = HashSet<DeformerId>()
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
	private fun foldLinkBones(model: PuppetModel, bones: List<SkeletonBone>): PuppetModel {
		val boneDeformers = bones.mapTo(HashSet()) { DeformerId(it.deformerId) }
		var result = model
		for (bone in bones) {
			val id = DeformerId(bone.deformerId)
			val link = result.deformers.firstOrNull { it.id == id } as? Deformer.Rotation ?: continue
			val parent = link.parent?.takeIf { it in boneDeformers } ?: continue
			if (result.deformers.none { it.id == parent && it is Deformer.Rotation }) continue
			if (result.drawables.any { it.parentDeformerId == id }) continue
			val children = result.deformers.filter { it.parent == id }
			if (children.isEmpty() || children.any { it !is Deformer.Rotation }) continue
			result = foldLink(result, link, parent, children.map { it as Deformer.Rotation })
		}
		return result
	}

	/**
	 * Re-hangs [children] from [link]'s parent [host] with [link] folded into each. The link's axes join
	 * each child's, keyed as the host's meshes key them so the child's pivot moves between keys exactly
	 * as the mesh it is drawn against; its angle and scale compose with the child's, and its pose shapes
	 * add to the child's.
	 */
	private fun foldLink(model: PuppetModel, link: Deformer.Rotation, host: DeformerId, children: List<Deformer.Rotation>): PuppetModel {
		val defaults = model.parameters.associate { it.id to it.default }
		val default: (ParameterId) -> Float = { defaults[it] ?: 0f }
		val linkAxes = link.geometryGrid!!.axes.map { axis ->
			val meshKeys = model.drawables.filter { it.parentDeformerId == host }
				.mapNotNull { d -> d.geometryGrid?.axes?.firstOrNull { it.parameterId == axis.parameterId }?.keys }
				.maxByOrNull { it.size }
			KeyformAxis(axis.parameterId, meshKeys ?: angleKeys(axis.keys.first() to axis.keys.last(), KEY_STEP))
		}
		val linkRest = rotationFormAt(link.geometryGrid, default) ?: RotationPivotForm(0f, 0f, 0f, 1f)

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

			val poses = (link.blendShapes + child.blendShapes).map { it.parameterId }.distinct()
			val blendShapes = child.blendShapes.filterNot { it.parameterId in poses } + poses.map { pose ->
				val linkShape = link.blendShapes.firstOrNull { it.parameterId == pose }
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
