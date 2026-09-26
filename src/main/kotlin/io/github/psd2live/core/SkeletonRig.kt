package io.github.psd2live.core

import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.render.eval.DeformerWorld
import org.umamo.render.eval.RotationWorld
import org.umamo.render.eval.buildDeformerWorlds
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.Glue
import org.umamo.runtime.model.GluePair
import org.umamo.runtime.model.KeyformAxis
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.MeshDeltaForm
import org.umamo.runtime.model.MeshForm
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.RotationPivotForm
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
 * Cubism has no bones, so the skeleton becomes two things a Cubism runtime already understands:
 *
 * - **A rotation deformer per bone**, nested as the bones are. A rotation deformer interpolates its
 *   angle rather than its vertices, so a limb turned to any parameter value is an exact rigid rotation -
 *   it neither shortens between keys nor multiplies keyforms when several joints move at once.
 * - **Corrective mesh keyforms across each joint.** A mesh that spans a joint hangs under one of the
 *   bones (its "home") and its keyforms carry the rest of the limb: vertices past a joint turn with the
 *   bone across it, and vertices inside the joint band turn by a weighted fraction of that angle about the
 *   joint (see [SkeletonWeights]). The keys are dense in angle, so the linear blend between them stays
 *   on the arc.
 *
 * Legs additionally get two whole-body poses - a crouch and a weight shift - solved by two-bone IK at bake
 * time so the feet stay where they are while the hips move (see [addLegPoses]).
 */
internal object SkeletonRig {
	val crouchId = ParameterId("ParamSkelCrouch")
	val weightId = ParameterId("ParamSkelWeight")

	private val bodyId = DeformerId("DeformBodyXY")
	private val breathId = DeformerId("DeformBodyZBreath")
	private val headRotationId = DeformerId("DeformHeadRotation")

	/** Widest angle step between two keys of a corrective keyform axis. */
	private const val KEY_STEP = 7.5

	/** A mesh with this share of its vertices on one bone is that bone's part of a split limb. */
	private const val SPLIT_PART_SHARE = 0.8

	/** A bone carrying this share of a spanning mesh counts as part of the drawing. */
	private const val SPANNED_SHARE = 0.15

	/** Vertices of two parts this close at rest, in canvas pixels, are the same point and get glued. */
	private const val GLUE_TOLERANCE = 1f

	/** Most keyforms one mesh may carry; beyond it the keys thin out evenly. */
	private const val MAX_MESH_CELLS = 600

	private val crouchKeys = floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f)
	private val weightKeys = floatArrayOf(-1f, -0.5f, 0f, 0.5f, 1f)

	/** The limb bones of [spec] that get a deformer: non-anchor bones with a usable length. */
	fun limbBones(spec: SkeletonSpec): List<SkeletonBone> =
		spec.topological().filter { !it.role.anchor && it.length >= 1f }

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
	 * The skinning tree each limb bone belongs to, by the ID of the tree's root. A body bone roots its own
	 * tree and so does every limb hanging from one: the limb turns with the body, but its meshes and the
	 * torso's never blend into each other.
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
		val limbIds = bones.mapTo(HashSet()) { it.id }
		val parentOf = bones.associate { it.id to limbParent(spec, it, limbIds) }

		var model = base
		var canvas = restCanvas(model)

		// 1. Which meshes each limb tree skins: a mesh bound to any bone of the tree.
		val rootOf = skinRoots(bones, parentOf)
		val drawableRoot = LinkedHashMap<String, String>()
		for (bone in bones) for (id in bone.drawableIds) {
			val drawable = model.drawables.firstOrNull { it.id.raw == id } ?: continue
			if (drawable.mesh == null || drawable.id !in canvas) continue
			drawableRoot.putIfAbsent(id, rootOf.getValue(bone.id))
		}
		val treeBones = bones.groupBy { rootOf.getValue(it.id) }

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

		// 3. One rotation deformer per bone, and the head carried by the upper body.
		model = addRotations(model, bones, parentOf, spec, frame)
		model = addParameters(model, bones)
		bones.firstOrNull { it.role == BoneRole.UPPER_BODY }?.let { model = reparentKeepingRest(model, headRotationId, DeformerId(it.deformerId)) }

		// 4. Hips that move while the feet stay put.
		model = addLegPoses(model, spec, bones, parentOf)

		// 5. Every skinned mesh under its home bone, with its joints baked into its keyforms.
		for ((id, root) in drawableRoot) {
			val drawableId = DrawableId(id)
			model = skinDrawable(model, drawableId, canvas.getValue(drawableId), treeBones.getValue(root), parentOf)
		}

		// 6. Split parts of one limb welded where their outlines meet, and no deformer left holding nothing.
		model = glueSplitParts(model, drawableRoot, canvas)
		model = pruneEmptyBones(model, bones)
		return model.withDerivedRenderRoot()
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
	// Rotation deformers

	/**
	 * The generated deformer a bone with no limb parent hangs from. Bones on the head turn with the head
	 * Z rotation; the upper body bends with body Z and the breath; the lower body, and a bone hanging
	 * from nothing, stay on the body warp alone so the feet stay where they stand.
	 */
	private fun attachWarp(model: PuppetModel, spec: SkeletonSpec, bone: SkeletonBone): DeformerId {
		val lineage = generateSequence(bone) { it.parentId?.let(spec::bone) }
		val present = model.deformers.mapTo(HashSet()) { it.id }
		return when {
			lineage.any { it.role == BoneRole.HEAD } && headRotationId in present -> headRotationId
			lineage.any { it.role == BoneRole.UPPER_BODY } && breathId in present -> breathId
			else -> bodyId
		}
	}

	/**
	 * Moves deformer [id] under [parentId] without moving it at rest: its pivot is carried into the new
	 * parent's space, and its angle and scale lose whatever turn and scale the new parent adds.
	 */
	private fun reparentKeepingRest(model: PuppetModel, id: DeformerId, parentId: DeformerId): PuppetModel {
		val deformer = model.deformers.firstOrNull { it.id == id } as? Deformer.Rotation ?: return model
		if (deformer.parent == parentId || model.deformers.none { it.id == parentId }) return model
		val before = worlds(model, emptyMap(), setOf(id, parentId))
		val old = before[id] as? RotationWorld ?: return model
		val pivot = FloatArray(2).also { old.apply(0f, 0f, it, 0) }
		val origin = inverse(before.getValue(parentId), pivot[0], pivot[1])
		val grid = deformer.geometryGrid ?: return model
		fun moved(angleShift: Float, scaleFactor: Float) = deformer.copy(
			parent = parentId,
			baseAngle = deformer.baseAngle - angleShift,
			geometryGrid = KeyformGrid(grid.axes, grid.cells.map { cell ->
				KeyformCell(cell.coordinate, RotationPivotForm(origin[0], origin[1], cell.form.angle, cell.form.scale * scaleFactor))
			}),
		)
		fun with(next: Deformer.Rotation) = model.copy(deformers = model.deformers.map { if (it.id == id) next else it })
		val trial = with(moved(0f, 1f))
		val now = worlds(trial, emptyMap(), setOf(id))[id] as? RotationWorld ?: return model
		val drift = SkeletonIk.wrap((angleOf(now) - angleOf(old)).toDouble()).toFloat()
		val scale = scaleOf(now).takeIf { it > 1e-6f }?.let { scaleOf(old) / it } ?: 1f
		return with(moved(drift, scale))
	}

	private fun addRotations(
		base: PuppetModel,
		bones: List<SkeletonBone>,
		parentOf: Map<String, SkeletonBone?>,
		spec: SkeletonSpec,
		frame: Bounds,
	): PuppetModel {
		val restWorlds = worlds(base, emptyMap())
		val partId = base.parts.firstOrNull { it.id.raw == "PartBody" }?.id
		val rotations = bones.map { bone ->
			val parentBone = parentOf[bone.id]
			val parentId = parentBone?.let { DeformerId(it.deformerId) } ?: attachWarp(base, spec, bone)
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
	 * Adds [crouchId] and [weightId] when the skeleton has legs.
	 *
	 * Cubism parameters cannot drive other parameters, so a pose is extra axes rather than a macro: the
	 * body warp gets the hip motion as keyforms, and every leg's rotation deformers get the joint angles
	 * that keep its ankle planted, solved by two-bone IK at each key. The angles add to the leg's own
	 * parameter, so a posed leg can still be swung by hand. The knee always gives outward, which is how
	 * a front-facing figure reads as bending its knees.
	 */
	private fun addLegPoses(base: PuppetModel, spec: SkeletonSpec, bones: List<SkeletonBone>, parentOf: Map<String, SkeletonBone?>): PuppetModel {
		val legs = legs(spec)
		if (legs.isEmpty()) return base
		val body = base.deformers.firstOrNull { it.id == bodyId } as? Deformer.Warp ?: return base
		val bodyGrid = body.geometryGrid ?: return base
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

		val bodyKeysCrouch = floatArrayOf(0f, 1f)
		val bodyKeysWeight = floatArrayOf(-1f, 0f, 1f)
		val newBodyAxes = bodyGrid.axes + KeyformAxis(crouchId, bodyKeysCrouch) + KeyformAxis(weightId, bodyKeysWeight)
		val newBodyCells = ArrayList<KeyformCell<WarpLatticeForm>>()
		for (cell in bodyGrid.cells) for (ci in bodyKeysCrouch.indices) for (wi in bodyKeysWeight.indices) {
			val m = motion(bodyKeysCrouch[ci].toDouble(), bodyKeysWeight[wi].toDouble(), weightDrop)
			val points = cell.form.controlPoints
			val moved = FloatArray(points.size)
			for (i in points.indices step 2) {
				val p = m.apply(points[i].toDouble(), points[i + 1].toDouble())
				moved[i] = p[0].toFloat()
				moved[i + 1] = p[1].toFloat()
			}
			newBodyCells += KeyformCell(cell.coordinate + intArrayOf(ci, wi), WarpLatticeForm(moved))
		}
		var model = base.copy(
			deformers = base.deformers.map { if (it.id == bodyId) body.copy(geometryGrid = KeyformGrid(newBodyAxes, newBodyCells)) else it },
			parameters = base.parameters.filterNot { it.id == crouchId || it.id == weightId } +
				Parameter(crouchId, tr("skeleton.param.crouch"), 0f, 1f, 0f) +
				Parameter(weightId, tr("skeleton.param.weight"), -1f, 1f, 0f),
		)

		// Joint angles per (crouch, weight) key, read against the body the evaluator actually builds so
		// the pivots the IK starts from are the ones the runtime will use.
		val offsets = HashMap<String, Array<DoubleArray>>()
		for (leg in legs) {
			offsets[leg.thigh.id] = Array(crouchKeys.size) { DoubleArray(weightKeys.size) }
			offsets[leg.shin.id] = Array(crouchKeys.size) { DoubleArray(weightKeys.size) }
			leg.foot?.let { offsets[it.id] = Array(crouchKeys.size) { DoubleArray(weightKeys.size) } }
		}
		val rest = worlds(model, emptyMap())
		for (ci in crouchKeys.indices) for (wi in weightKeys.indices) {
			val posed = worlds(model, mapOf(crouchId to crouchKeys[ci], weightId to weightKeys[wi]))
			for (leg in legs) {
				val thighId = DeformerId(leg.thigh.deformerId)
				val hip = FloatArray(2).also { posed.getValue(thighId).apply(0f, 0f, it, 0) }
				val inherited = SkeletonIk.wrap((angleOf(posed.getValue(thighId)) - angleOf(rest.getValue(thighId))).toDouble())
				val (thighTurn, shinTurn) = legTurns(leg, hip[0].toDouble(), hip[1].toDouble(), centerX)
				offsets.getValue(leg.thigh.id)[ci][wi] = SkeletonIk.wrap(thighTurn - inherited)
				offsets.getValue(leg.shin.id)[ci][wi] = SkeletonIk.wrap(shinTurn - thighTurn)
				leg.foot?.let { offsets.getValue(it.id)[ci][wi] = -shinTurn }
			}
		}
		model = model.copy(deformers = model.deformers.map { deformer ->
			val bone = bones.firstOrNull { it.deformerId == deformer.id.raw }
			val table = bone?.let { offsets[it.id] }
			if (bone == null || table == null || deformer !is Deformer.Rotation) return@map deformer
			val grid = deformer.geometryGrid!!
			val own = grid.axes.single()
			val cells = ArrayList<KeyformCell<RotationPivotForm>>()
			for (oi in own.keys.indices) for (ci in crouchKeys.indices) for (wi in weightKeys.indices) {
				val form = grid.cells.first { it.coordinate[0] == oi }.form
				cells += KeyformCell(
					intArrayOf(oi, ci, wi),
					RotationPivotForm(form.originX, form.originY, form.angle + table[ci][wi].toFloat(), form.scale),
				)
			}
			deformer.copy(geometryGrid = KeyformGrid(listOf(own, KeyformAxis(crouchId, crouchKeys), KeyformAxis(weightId, weightKeys)), cells))
		})
		return model
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
		val axes = meshAxes(base, driving.map { tree[it] })

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
		return base.copy(drawables = base.drawables.map { if (it.id == drawableId) skinned else it })
	}

	/**
	 * The bone a mesh hangs under.
	 *
	 * A mesh that is essentially one bone's part - a split forearm with a sliver of elbow blend - hangs
	 * under that bone, so each part of a split limb gets its own rotation deformer pivoting on its joint.
	 * A mesh that spans several bones hangs under the highest of them: one rotation deformer turns the
	 * whole art mesh at the limb's root, and every joint below it bends in the mesh's own keyforms.
	 * Hanging it lower would only give it a pivot in the middle of the drawing and extra deformers that
	 * hold nothing.
	 */
	internal fun homeBone(skins: List<VertexSkin>, bones: List<SkinBone>): Int {
		val load = DoubleArray(bones.size)
		for (skin in skins) {
			load[skin.from] += 1.0 - skin.weight
			load[skin.to] += skin.weight.toDouble()
		}
		val total = load.sum().coerceAtLeast(1e-9)
		val main = load.indices.maxBy { load[it] }
		if (load[main] >= total * SPLIT_PART_SHARE) return main
		fun depth(index: Int) = generateSequence(index) { bones[it].parent.takeIf { p -> p >= 0 } }.count()
		return load.indices.filter { load[it] >= total * SPANNED_SHARE }.minBy { depth(it) }
	}

	/**
	 * The keyform axes of a mesh driven by [bones]: each bone's own parameter keyed densely in angle,
	 * plus any pose axis those bones' deformers carry.
	 */
	private fun meshAxes(model: PuppetModel, bones: List<SkeletonBone>): List<KeyformAxis> {
		val own = LinkedHashMap<ParameterId, Pair<Float, Float>>()
		val poses = LinkedHashMap<ParameterId, FloatArray>()
		for (bone in bones) {
			val id = ParameterId(bone.parameterId)
			val range = own[id]
			own[id] = if (range == null) bone.minAngle to bone.maxAngle else minOf(range.first, bone.minAngle) to maxOf(range.second, bone.maxAngle)
			val deformer = model.deformers.firstOrNull { it.id.raw == bone.deformerId } as? Deformer.Rotation
			// A pose moves a mesh through the joint angles its IK chose, keyed as densely as the deformers.
			deformer?.geometryGrid?.axes?.filter { it.parameterId != id }?.forEach { poses.putIfAbsent(it.parameterId, it.keys) }
		}
		val fixedCells = poses.values.fold(1) { acc, keys -> acc * keys.size }
		var step = KEY_STEP
		fun keysFor(range: Pair<Float, Float>): FloatArray {
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
		var ownKeys = own.mapValues { keysFor(it.value) }
		while (ownKeys.values.fold(fixedCells) { acc, keys -> acc * keys.size } > MAX_MESH_CELLS && step < 90.0) {
			step *= 1.15
			ownKeys = own.mapValues { keysFor(it.value) }
		}
		return ownKeys.map { KeyformAxis(it.key, it.value) } + poses.map { KeyformAxis(it.key, it.value) }
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

	/**
	 * Glues the parts of a split limb together.
	 *
	 * Skinning already moves two overlapping parts identically - a vertex's skin depends only on where it
	 * sits - so a seam cannot open by itself. The glue is for the vertices drawn on the seam of both
	 * parts: welded, they stay one point through float rounding and through any keyform later added to one
	 * side only. Only vertices that coincide at rest are paired, so the glue never pulls the picture.
	 */
	private fun glueSplitParts(model: PuppetModel, drawableRoot: Map<String, String>, canvas: Map<DrawableId, FloatArray>): PuppetModel {
		val byTree = drawableRoot.entries.groupBy({ it.value }, { DrawableId(it.key) })
		val glues = model.glues.toMutableList()
		val parentOf = model.drawables.associate { it.id to it.parentDeformerId }
		for (members in byTree.values) {
			for (i in members.indices) for (j in i + 1 until members.size) {
				val a = members[i]
				val b = members[j]
				if (parentOf[a] == parentOf[b]) continue
				if (glues.any { (it.meshA == a && it.meshB == b) || (it.meshA == b && it.meshB == a) }) continue
				val pa = canvas[a] ?: continue
				val pb = canvas[b] ?: continue
				val pairs = ArrayList<GluePair>()
				val used = HashSet<Int>()
				for (va in 0 until pa.size / 2) {
					var best = -1
					var bestDistance = GLUE_TOLERANCE
					for (vb in 0 until pb.size / 2) {
						if (vb in used) continue
						val d = hypot(pa[va * 2] - pb[vb * 2], pa[va * 2 + 1] - pb[vb * 2 + 1])
						if (d <= bestDistance) {
							best = vb
							bestDistance = d
						}
					}
					if (best >= 0) {
						used += best
						pairs += GluePair(va, best, 0.5f, 0.5f)
					}
				}
				if (pairs.isNotEmpty()) glues += Glue(a, b, pairs, intensity = 1f, id = "GlueSkel__${a.raw}__${b.raw}")
			}
		}
		return model.copy(glues = glues)
	}

	/** Bone deformers with nothing under them - no mesh and no other deformer - removed, deepest first. */
	private fun pruneEmptyBones(model: PuppetModel, bones: List<SkeletonBone>): PuppetModel {
		val boneDeformers = bones.mapTo(HashSet()) { DeformerId(it.deformerId) }
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
