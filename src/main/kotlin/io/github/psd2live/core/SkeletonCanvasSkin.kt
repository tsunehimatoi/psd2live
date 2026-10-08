package io.github.psd2live.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.render.eval.DeformerWorld
import org.umamo.render.eval.buildDeformerWorlds
import org.umamo.runtime.eval.colorAt
import org.umamo.runtime.eval.gridCorners
import org.umamo.runtime.eval.meshGridDefaultDeltas
import org.umamo.runtime.eval.scalarAt
import org.umamo.runtime.model.BlendShapeBinding
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.FormChannel
import org.umamo.runtime.model.KeyformAxis
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.MeshDeltaForm
import org.umamo.runtime.model.MeshForm
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.ParameterKind
import org.umamo.runtime.model.PuppetModel
import kotlin.math.abs

/**
 * Bone skinning of meshes the journal hangs under deformers of its own.
 *
 * The skeleton bakes before the journal replays, and it skins a bound mesh by hanging it under its bone's rotation
 * deformer, its positions and keyforms in that bone's space. A journal record that later places the mesh itself - a
 * structure `bind` to a deformer that is not a bone, a `warp` record wrapping it in a new Warp - keeps the mesh's
 * local coordinates, so it cannot replay on a mesh the bake moved into bone space. Such a mesh ([placed]) is left
 * where it hangs by the bake (which still refines and welds it with its limb, see [SkeletonRig.apply]), and this
 * stage, after the journal, writes the bones' effect onto it wherever the journal put it.
 *
 * The effect is defined on the canvas: at a pose, every vertex goes where its bones carry it - the frames
 * [SkeletonPoseSolver.frames] reads off the rig, blended across each joint by the vertex's weights
 * ([SkeletonManualWeights], [SkeletonWeights]) and corrected by the joint templates, the ARAP solve and the surface
 * fairing exactly as the bake corrects a mesh it hangs under a bone ([SkeletonRig.corrected]). That canvas position
 * is carried into the mesh's own parent through the inverse of the parent chain at the same pose, so whatever warps
 * stand above the mesh, it shows where the bones put it, and the difference from rest is written as the mesh's
 * keyforms on the bone parameters - blend shapes for the parameters that are blend shapes (the bones the blend plan
 * found independent, and the rig poses). The bones' parameters, the body halves its limb hangs from and the rig
 * poses are the axes; one that moves no vertex adds none.
 *
 * Its keyforms add to whatever the mesh is keyed on already. Edits of the generated cells are recorded as
 * `generated_override` of this stage ([DocumentGenerators.SKIN]); a blend shape the journal already gave the mesh on
 * one of these parameters is the user's and is kept as it is.
 *
 * Glue: a glued vertex whose partner is another skinned mesh gets the same canvas target as the partner at every
 * key. A partner skinned by the bake is evaluated as the rig moves it; two meshes of this stage meet where the glue's
 * weights put them. Those vertices are fixed handles of the ARAP solve, so the mesh bends smoothly into the seam.
 */
internal object SkeletonCanvasSkin {
	/** Canvas pixels below which a parameter is taken to move a mesh not at all. */
	private const val STILL_PX = 1e-3f

	/**
	 * Meshes bound to a limb bone of [overlay]'s skeleton that its edits place under a deformer of their own: the
	 * last structure `bind` of the mesh names a deformer other than a bone's, or a `warp` record wraps it. Legacy
	 * edits count first, then the journal in order. Empty without an enabled skeleton.
	 */
	fun placed(overlay: RigEditOverlay): Set<String> {
		val spec = overlay.skeleton?.takeIf { it.enabled } ?: return emptySet()
		val bound = SkeletonRig.jointBones(spec).flatMapTo(HashSet()) { it.drawableIds }
		if (bound.isEmpty()) return emptySet()
		val bones = spec.bones.mapTo(HashSet()) { it.deformerId }
		val placed = LinkedHashSet<String>()
		fun structure(edit: JsonObject) {
			if (edit["action"]?.jsonPrimitive?.contentOrNull != "bind" || edit["kind"]?.jsonPrimitive?.contentOrNull != "mesh") return
			val id = edit["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it in bound } ?: return
			if (edit["parent_id"]?.jsonPrimitive?.contentOrNull in bones) placed -= id else placed += id
		}
		fun warp(meshIds: List<String>) { for (id in meshIds) if (id in bound) placed += id }
		overlay.structureEdits.forEach(::structure)
		overlay.warpEdits.forEach { warp(it.meshIds) }
		for (command in overlay.authoringJournal) when (command["op"]?.jsonPrimitive?.contentOrNull) {
			"structure" -> (command["edits"] as? JsonArray).orEmpty().forEach { (it as? JsonObject)?.let(::structure) }
			"warp" -> (command["warp"] as? JsonObject)?.get("mesh_ids")?.let { ids ->
				warp((ids as? JsonArray).orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull })
			}
		}
		return placed
	}

	/**
	 * The vertex counts [journal]'s vertex-addressed records expect of each mesh: geometry edits, vertex groups and
	 * generated overrides of a mesh.
	 */
	fun addressedCounts(journal: List<JsonObject>): Map<String, Set<Int>> {
		val counts = LinkedHashMap<String, MutableSet<Int>>()
		fun add(id: String?, count: Int?) { if (id != null && count != null) counts.getOrPut(id) { LinkedHashSet() } += count }
		for (command in journal) when (command["op"]?.jsonPrimitive?.contentOrNull) {
			"canvas_geometry" -> if (command["kind"]?.jsonPrimitive?.contentOrNull !in setOf("warp", "rotation"))
				add(command["id"]?.jsonPrimitive?.contentOrNull, CanvasGeometryJournal.size(command)?.div(2))
			VertexGroupJournal.PUT -> add(command["target"]?.jsonPrimitive?.contentOrNull?.removePrefix("mesh:"), (command["weights"] as? JsonArray)?.size)
			GeneratedOverrides.OP -> command["target"]?.jsonPrimitive?.contentOrNull?.takeIf { it.startsWith("mesh:") }?.let { target ->
				add(target.removePrefix("mesh:"), (command["points"] as? JsonArray)?.size?.div(2))
			}
		}
		return counts
	}

	/**
	 * The meshes of [placed] the skeleton must leave their vertices: a vertex-addressed record of [overlay] expects
	 * the vertex count the mesh has before the skeleton refines it in [model] (a record written while the mesh was
	 * not refined), which new vertices would no longer match.
	 */
	fun lockedTopology(model: PuppetModel, overlay: RigEditOverlay, placed: Set<String>): Set<String> {
		if (placed.isEmpty()) return emptySet()
		val counts = addressedCounts(overlay.authoringJournal)
		return placed.filterTo(LinkedHashSet()) { id ->
			val count = model.drawables.firstOrNull { it.id.raw == id }?.mesh?.vertexCount
			count != null && counts[id]?.contains(count) == true
		}
	}

	/** The parameters whose keyforms this stage may write on each mesh of [placed], for the generator graph. */
	fun claims(spec: SkeletonSpec, placed: Set<String>): Map<String, List<String>> {
		val joints = SkeletonRig.jointBones(spec)
		val rootOf = SkeletonRig.skinRoots(joints, SkeletonRig.jointParents(spec))
		val poses = SkeletonPoses.available(spec).filter { it.rig }.map { it.id.raw }
		val out = LinkedHashMap<String, List<String>>()
		for (id in placed) {
			val bone = joints.firstOrNull { id in it.drawableIds } ?: continue
			val tree = joints.filter { rootOf[it.id] == rootOf[bone.id] }
			out[id] = (tree.map { it.parameterId } + bodyHalves(spec, tree.first()).map { it.parameterId } + poses).distinct()
		}
		return out
	}

	/** The body halves above [root], the bone a limb hangs from, nearest first. */
	private fun bodyHalves(spec: SkeletonSpec, root: SkeletonBone): List<SkeletonBone> =
		generateSequence(root.parentId?.let(spec::bone)) { it.parentId?.let(spec::bone) }
			.filter { it.role.body && it.length >= 1f }.toList()

	/** What [apply] reads of [model]: the parameters, every deformer, the meshes it skins and their glue partners and those glues. */
	fun reads(model: PuppetModel, placed: Set<String>): GeneratorReuse.Reads {
		val glues = model.glues.filter { it.meshA.raw in placed || it.meshB.raw in placed }
		val ids = (placed + glues.flatMap { listOf(it.meshA.raw, it.meshB.raw) }).toSortedSet()
		val byId = model.drawables.associateBy { it.id.raw }
		return GeneratorReuse.Reads(model.parameters, model.parameterTree, listOf(glues, model.runtimeTarget),
			model.deformers.mapTo(HashSet()) { it.id.raw }, model.deformers, ids, ids.map { byId[it] })
	}

	/** One mesh this stage skins, with everything its poses share. */
	private class Subject(
		val drawable: Drawable,
		val tree: List<SkeletonBone>,
		val skinBones: List<SkinBone>,
		val skins: List<VertexSkin>,
		/** Rest positions in the parent's space, the default keyforms included, and on the canvas. */
		val local: FloatArray,
		val canvas: FloatArray,
		val templates: SkeletonJointTemplates,
		val surface: SkeletonSurfaceFairing,
	) {
		val id: DrawableId get() = drawable.id
		val pins = ArrayList<Pin>()
		lateinit var free: SkeletonArap
		lateinit var pinned: SkeletonArap

		/**
		 * The parameters that carry the whole limb rigidly - its root bone's and the body halves' above it - when every
		 * glue partner hangs in the same limb: a pose is then solved without them and their frame applied on top, which
		 * is what the deformers do (a child bone's frame is its parent's frame after its own), at a fraction of the solves.
		 */
		var outer: Set<ParameterId> = emptySet()
	}

	/**
	 * Vertex [vertex] of a subject glued to vertex [other] of [partner]; [weight] is how far the subject's side moves
	 * toward the partner (the glue's weight on that side over both).
	 */
	private class Pin(val vertex: Int, val partner: DrawableId, val other: Int, val weight: Float, val baked: Boolean)

	/**
	 * [model] with the meshes of [placed] skinned to [spec]'s bones in canvas space; see [SkeletonCanvasSkin]. A
	 * model the skeleton was not baked into - no body warp, or a bone parameter missing - is returned unchanged.
	 */
	fun apply(model: PuppetModel, spec: SkeletonSpec, placed: Set<String>): PuppetModel {
		if (placed.isEmpty() || !spec.enabled || model.deformers.none { it.id == SkeletonRig.bodyId }) return model
		val joints = SkeletonRig.jointBones(spec)
		val parentOf = SkeletonRig.jointParents(spec)
		val rootOf = SkeletonRig.skinRoots(joints, parentOf)
		val parameters = model.parameters.associateBy { it.id }
		val defaults: (ParameterId) -> Float = { parameters[it]?.default ?: 0f }
		val restWorlds = buildDeformerWorlds(model.deformers, defaults, defaults)
		val byId = model.drawables.associateBy { it.id }

		// The subjects in the order the bake visits bound meshes.
		val subjects = LinkedHashMap<DrawableId, Subject>()
		for (bone in joints) for (raw in bone.drawableIds) {
			if (raw !in placed || DrawableId(raw) in subjects) continue
			val drawable = byId[DrawableId(raw)] ?: continue
			val mesh = drawable.mesh ?: continue
			val tree = joints.filter { rootOf[it.id] == rootOf.getValue(bone.id) }
			if (tree.any { ParameterId(it.parameterId) !in parameters }) continue
			val parent = drawable.parentDeformerId
			val world = parent?.let(restWorlds::get)
			if (parent != null && world == null) continue
			val d0 = meshGridDefaultDeltas(drawable, defaults)
			val local = FloatArray(mesh.positions.size) { mesh.positions[it] + (d0?.get(it) ?: 0f) }
			val canvas = toCanvas(world, local)
			val skinBones = SkeletonRig.skinBones(tree, parentOf)
			val skins = SkeletonManualWeights.weights(canvas, mesh.indices, tree, parentOf, spec.manualWeights[raw])
			subjects[drawable.id] = Subject(drawable, tree, skinBones, skins, local, canvas,
				SkeletonJointTemplates(canvas, mesh.indices, skins, skinBones, tree.map { it.role }),
				SkeletonSurfaceFairing(canvas, mesh.indices))
		}
		if (subjects.isEmpty()) return model

		// Glued partners: another subject, or a mesh the bake skinned (bound to a limb bone, not among the subjects).
		val baked = joints.flatMapTo(HashSet()) { bone -> bone.drawableIds.map(::DrawableId) } - subjects.keys
		val partners = LinkedHashSet<DrawableId>()
		for (glue in model.glues) for ((self, other, flip) in listOf(Triple(glue.meshA, glue.meshB, false), Triple(glue.meshB, glue.meshA, true))) {
			val subject = subjects[self] ?: continue
			val isSubject = other in subjects
			if (!isSubject && (other !in baked || byId[other]?.mesh == null)) continue
			for (pair in glue.pairs) {
				val own = if (flip) pair.weightB else pair.weightA
				val theirs = if (flip) pair.weightA else pair.weightB
				val vertex = if (flip) pair.indexB else pair.indexA
				val index = if (flip) pair.indexA else pair.indexB
				if (vertex !in subject.skins.indices) continue
				val sum = own + theirs
				subject.pins += Pin(vertex, other, index, if (sum > 1e-6f) own / sum else 0.5f, !isSubject)
			}
			if (!isSubject) partners += other
		}
		val treeOf = joints.flatMap { bone -> bone.drawableIds.map { DrawableId(it) to rootOf.getValue(bone.id) } }.toMap()
		for (subject in subjects.values) {
			val root = subject.tree.first()
			val inner = subject.tree.drop(1).mapTo(HashSet()) { it.parameterId }
			if (subject.pins.all { treeOf[it.partner] == root.id } && root.parameterId !in inner)
				subject.outer = (listOf(root) + bodyHalves(spec, root)).map { ParameterId(it.parameterId) }.filterTo(HashSet()) { it.raw !in inner }
		}
		for (subject in subjects.values) {
			val triangles = subject.drawable.mesh!!.indices
			val fixedBaked = subject.pins.filter { it.baked }.mapTo(HashSet()) { it.vertex }
			val fixedAll = subject.pins.mapTo(HashSet()) { it.vertex }
			subject.free = SkeletonArap(subject.canvas, triangles, BooleanArray(subject.skins.size) { !subject.skins[it].rigid && it !in fixedBaked })
			subject.pinned = if (fixedAll == fixedBaked) subject.free
				else SkeletonArap(subject.canvas, triangles, BooleanArray(subject.skins.size) { !subject.skins[it].rigid && it !in fixedAll })
		}

		// The rig at a pose, shared by every subject: the bones' frames and the baked partners on the canvas.
		val frames = HashMap<Map<ParameterId, Float>, Map<String, (Double, Double) -> DoubleArray>>()
		fun framesAt(values: Map<ParameterId, Float>) = frames.getOrPut(values) { SkeletonPoseSolver.frames(model, spec, values, restWorlds) }
		val partnerModel = model.copy(drawables = model.drawables.filter { it.id in partners }, glues = emptyList())
		val partnerPositions = HashMap<Map<ParameterId, Float>, Map<DrawableId, FloatArray>>()
		fun partnersAt(values: Map<ParameterId, Float>) = partnerPositions.getOrPut(values) {
			if (partners.isEmpty()) emptyMap() else CpuDeformationEvaluator().evaluate(partnerModel, values).worldPositions
				.mapValues { (_, world) -> FloatArray(world.size) { if (it % 2 == 1) -world[it] else world[it] } }
		}
		val parentWorlds = HashMap<Pair<DeformerId, Map<ParameterId, Float>>, DeformerWorld?>()
		fun parentAt(subject: Subject, values: Map<ParameterId, Float>): DeformerWorld? {
			val parent = subject.drawable.parentDeformerId ?: return null
			if (values.isEmpty()) return restWorlds[parent]
			return parentWorlds.getOrPut(parent to values) { SkeletonRig.worlds(model, values, setOf(parent))[parent] }
		}

		// Where the bones put each vertex on the canvas at a pose: pass 1 pins only baked partners, pass 2 also meets
		// the other subjects.
		val solved = HashMap<Triple<DrawableId, Map<ParameterId, Float>, Boolean>, FloatArray>()
		fun solve(subject: Subject, values: Map<ParameterId, Float>, meet: Boolean): FloatArray {
			val pass = meet && subject.pinned !== subject.free
			val outer = values.filterKeys { it in subject.outer }
			if (outer.isNotEmpty()) return solved.getOrPut(Triple(subject.id, values, pass)) {
				val inner = solve(subject, values - outer.keys, meet)
				val frame = framesAt(outer)[subject.tree.first().id] ?: return@getOrPut inner
				FloatArray(inner.size).also { out ->
					for (i in inner.indices step 2) {
						val p = frame(inner[i].toDouble(), inner[i + 1].toDouble())
						out[i] = p[0].toFloat(); out[i + 1] = p[1].toFloat()
					}
				}
			}
			return solved.getOrPut(Triple(subject.id, values, pass)) {
				val (target, seed, angles, carry) = linear(subject, framesAt(values))
				val pins = HashMap<Int, FloatArray>()
				for (pin in subject.pins) {
					val displacement = if (pin.baked) {
						val at = partnersAt(values)[pin.partner] ?: continue
						val rest = partnersAt(emptyMap())[pin.partner] ?: continue
						if (pin.other * 2 + 1 >= at.size) continue
						floatArrayOf(at[pin.other * 2] - rest[pin.other * 2], at[pin.other * 2 + 1] - rest[pin.other * 2 + 1])
					} else if (pass) {
						val other = subjects.getValue(pin.partner)
						if (pin.other * 2 + 1 >= other.canvas.size) continue
						val mine = solve(subject, values, false)
						val theirs = solve(other, values, false)
						val ax = mine[pin.vertex * 2] - subject.canvas[pin.vertex * 2]; val ay = mine[pin.vertex * 2 + 1] - subject.canvas[pin.vertex * 2 + 1]
						val bx = theirs[pin.other * 2] - other.canvas[pin.other * 2]; val by = theirs[pin.other * 2 + 1] - other.canvas[pin.other * 2 + 1]
						floatArrayOf(ax + (bx - ax) * pin.weight, ay + (by - ay) * pin.weight)
					} else continue
					val sum = pins.getOrPut(pin.vertex) { FloatArray(3) }
					sum[0] += displacement[0]; sum[1] += displacement[1]; sum[2] += 1f
				}
				for ((vertex, sum) in pins) {
					target[vertex * 2] = subject.canvas[vertex * 2] + sum[0] / sum[2]
					target[vertex * 2 + 1] = subject.canvas[vertex * 2 + 1] + sum[1] / sum[2]
				}
				val corrected = if (values.isEmpty()) target
					else SkeletonRig.corrected(if (pass) subject.pinned else subject.free, subject.templates, subject.surface, target, seed, angles, carry)
				for (vertex in pins.keys) { corrected[vertex * 2] = target[vertex * 2]; corrected[vertex * 2 + 1] = target[vertex * 2 + 1] }
				corrected
			}
		}

		// The skin at a pose as offsets in the parent's space from where the same solve puts the mesh at rest.
		fun local(subject: Subject, values: Map<ParameterId, Float>): FloatArray {
			val canvas = solve(subject, values, meet = true)
			val world = parentAt(subject, values) ?: return canvas.copyOf()
			val out = FloatArray(canvas.size)
			for (i in canvas.indices step 2) {
				val p = SkeletonRig.inverse(world, canvas[i], canvas[i + 1], floatArrayOf(subject.local[i], subject.local[i + 1]))
				out[i] = p[0]; out[i + 1] = p[1]
			}
			return out
		}
		val rests = HashMap<DrawableId, FloatArray>()
		val deltas = HashMap<Pair<DrawableId, Map<ParameterId, Float>>, FloatArray>()
		fun deltasAt(subject: Subject, at: Map<ParameterId, Float>): FloatArray {
			val values = at.filterValues { it != 0f }
			if (values.isEmpty()) return FloatArray(subject.local.size)
			return deltas.getOrPut(subject.id to values) {
				val rest = rests.getOrPut(subject.id) { local(subject, emptyMap()) }
				val posed = local(subject, values)
				FloatArray(posed.size) { posed[it] - rest[it] }
			}.copyOf()
		}

		val kinds = parameters.mapValues { it.value.kind }
		val plans = LinkedHashMap<DrawableId, List<KeyformAxis>>()
		for (subject in subjects.values) {
			plans[subject.id] = plan(spec, subject, kinds, { values ->
				// Measured on the canvas through the parent at rest, as the bake measures a bone's keys.
				val world = subject.drawable.parentDeformerId?.let { restWorlds[it] }
				val d = deltasAt(subject, values)
				toCanvas(world, FloatArray(d.size) { subject.local[it] + d[it] })
			}) { values ->
				// Whether the pose moves any vertex off where its parent carries it.
				val (target) = linear(subject, framesAt(values))
				val carried = toCanvas(parentAt(subject, values), subject.local)
				target.indices.any { abs(target[it] - carried[it]) > STILL_PX }
			}
		}
		// Subjects glued to each other key a parameter both move on the keys of both, so their seam meets on each.
		val component = HashMap<DrawableId, DrawableId>()
		fun find(id: DrawableId): DrawableId = component[id]?.takeIf { it != id }?.let(::find) ?: id
		for (subject in subjects.values) for (pin in subject.pins) if (!pin.baked) component[find(subject.id)] = find(pin.partner)
		val shared = HashMap<Pair<DrawableId, ParameterId>, FloatArray>()
		for ((id, axes) in plans) for (axis in axes) {
			val key = find(id) to axis.parameterId
			shared[key] = (shared[key]?.let { it + axis.keys } ?: axis.keys).distinct().sorted().toFloatArray()
		}

		var result = model
		for (subject in subjects.values) {
			val axes = plans.getValue(subject.id).map { KeyformAxis(it.parameterId, shared.getValue(find(subject.id) to it.parameterId)) }
			if (axes.isEmpty()) continue
			val skinned = build(model, subject, axes, kinds, ::deltasAt)
			result = result.copy(drawables = result.drawables.map { if (it.id == subject.id) skinned else it })
		}
		return result
	}

	/** [local] through [world] onto the canvas; unchanged without a parent. */
	private fun toCanvas(world: DeformerWorld?, local: FloatArray): FloatArray {
		if (world == null) return local.copyOf()
		val out = FloatArray(local.size)
		for (i in local.indices step 2) world.apply(local[i], local[i + 1], out, i)
		return out
	}

	/** Linear blend skinning of [subject] in [frames]: the target, the ARAP seed, each bone's turn and its linear part. */
	private data class Linear(val target: FloatArray, val seed: FloatArray, val angles: FloatArray, val carry: List<DoubleArray>)

	private fun linear(subject: Subject, frames: Map<String, (Double, Double) -> DoubleArray>): Linear {
		val canvas = subject.canvas
		val tree = subject.tree
		val frame = tree.map { bone -> frames[bone.id] ?: { x: Double, y: Double -> doubleArrayOf(x, y) } }
		val angles = FloatArray(tree.size) { b ->
			val bone = tree[b]
			val head = frame[b](bone.headX.toDouble(), bone.headY.toDouble())
			val tail = frame[b](bone.tailX.toDouble(), bone.tailY.toDouble())
			SkeletonIk.wrap(SkeletonIk.heading(tail[0] - head[0], tail[1] - head[1]) - SkeletonRig.restHeading(bone)).toFloat()
		}
		val carry = frame.map { f ->
			val o = f(0.0, 0.0); val x = f(1.0, 0.0); val y = f(0.0, 1.0)
			doubleArrayOf(x[0] - o[0], y[0] - o[0], x[1] - o[1], y[1] - o[1])
		}
		val target = FloatArray(canvas.size)
		val seed = FloatArray(canvas.size)
		for ((vertex, skin) in subject.skins.withIndex()) {
			val x = canvas[vertex * 2].toDouble(); val y = canvas[vertex * 2 + 1].toDouble()
			val from = frame[skin.from](x, y)
			var tx = from[0].toFloat(); var ty = from[1].toFloat()
			if (!skin.rigid) {
				val to = frame[skin.to](x, y)
				tx += (to[0].toFloat() - tx) * skin.weight
				ty += (to[1].toFloat() - ty) * skin.weight
			}
			target[vertex * 2] = tx; target[vertex * 2 + 1] = ty
			// The angular field is only the ARAP solve's starting guess, as in the bake.
			if (skin.rigid) { seed[vertex * 2] = tx; seed[vertex * 2 + 1] = ty } else {
				val joint = subject.skinBones[skin.to]
				val p = SkeletonIk.rotate(x, y, joint.headX, joint.headY, SkeletonIk.wrap((angles[skin.to] - angles[skin.from]).toDouble()) * skin.weight)
				val q = frame[skin.from](p[0], p[1])
				seed[vertex * 2] = q[0].toFloat(); seed[vertex * 2 + 1] = q[1].toFloat()
			}
		}
		return Linear(target, seed, angles, carry)
	}

	/**
	 * The axes [subject] is skinned on: each parameter that moves it, keyed as sparsely as the bake keys a bone (the
	 * keyform axes thinned as the bake thins them), and the rig poses on their own keys. Empty when nothing moves it.
	 */
	private fun plan(
		spec: SkeletonSpec,
		subject: Subject,
		kinds: Map<ParameterId, ParameterKind>,
		canvasAt: (Map<ParameterId, Float>) -> FloatArray,
		moves: (Map<ParameterId, Float>) -> Boolean,
	): List<KeyformAxis> {
		val sampling = spec.sampling
		// Candidate parameters and their ranges: the bones that move a vertex, the body halves the limb hangs from.
		val ranges = LinkedHashMap<ParameterId, Pair<Float, Float>>()
		fun range(bone: SkeletonBone) {
			val id = ParameterId(bone.parameterId)
			if (id !in kinds) return
			val current = ranges[id]
			ranges[id] = if (current == null) bone.minAngle to bone.maxAngle else minOf(current.first, bone.minAngle) to maxOf(current.second, bone.maxAngle)
		}
		val moving = sortedSetOf<Int>()
		for (set in SkeletonRig.canvasDependencies(subject.skins, subject.skinBones)) moving += set
		for (index in moving) range(subject.tree[index])
		bodyHalves(spec, subject.tree.first()).forEach(::range)
		for (id in ranges.keys.toList()) {
			val (low, high) = ranges.getValue(id)
			if (listOf(low, high).filter { it != 0f }.none { moves(mapOf(id to it)) }) ranges.remove(id)
		}
		val poses = SkeletonPoses.available(spec).filter { pose ->
			pose.rig && pose.id in kinds && pose.keys.any { it != 0f && moves(mapOf(pose.id to it)) }
		}
		if (ranges.isEmpty() && poses.isEmpty()) return emptyList()
		val sides = ranges.mapValues { (id, range) -> SkeletonRig.fittedSides(range, sampling) { canvasAt(mapOf(id to it)) } }
		return SkeletonRig.gridAxes(sides.filterKeys { kinds[it] != ParameterKind.BLEND_SHAPE }, ranges, sampling) +
			sides.filterKeys { kinds[it] == ParameterKind.BLEND_SHAPE }.map { (id, side) ->
				KeyformAxis(id, SkeletonRig.keysOf(ranges.getValue(id), side.first, side.second))
			} + poses.map { KeyformAxis(it.id, it.keys) }
	}

	/**
	 * [subject]'s drawable with the skin on [all] its axes added: a keyform axis for each parameter that is not a blend
	 * shape, a blend shape for each one that is.
	 */
	private fun build(
		model: PuppetModel,
		subject: Subject,
		all: List<KeyformAxis>,
		kinds: Map<ParameterId, ParameterKind>,
		deltasAt: (Subject, Map<ParameterId, Float>) -> FloatArray,
	): Drawable {
		val drawable = subject.drawable
		val axes = all.filter { kinds[it.parameterId] != ParameterKind.BLEND_SHAPE }
		val blendAxes = all.filter { kinds[it.parameterId] == ParameterKind.BLEND_SHAPE }
		val grid = merged(drawable.geometryGrid, axes) { values -> deltasAt(subject, values.filterKeys { id -> axes.any { it.parameterId == id } }) }
		val mesh = drawable.mesh!!
		var skinned = drawable.copy(
			mesh = DrawableMesh(mesh.positions, mesh.uvs, SkeletonRig.foldDrawOrder(subject.canvas, mesh.indices, subject.skins, subject.skinBones)),
			geometryGrid = grid,
		)
		val defaults: (ParameterId) -> Float = { id -> model.parameters.firstOrNull { it.id == id }?.default ?: 0f }
		val reference = meshGridDefaultDeltas(skinned, defaults) ?: FloatArray(mesh.positions.size)
		val drawOrder = skinned.channelGrids.scalarAt(FormChannel.DRAW_ORDER, skinned.drawOrder, defaults)
		val opacity = skinned.channelGrids.scalarAt(FormChannel.OPACITY, skinned.opacity, defaults)
		val multiply = skinned.channelGrids.colorAt(FormChannel.MULTIPLY_COLOR, skinned.multiplyColor, defaults)
		val screen = skinned.channelGrids.colorAt(FormChannel.SCREEN_COLOR, skinned.screenColor, defaults)
		val shapes = ArrayList<BlendShapeBinding<MeshForm>>()
		for (axis in blendAxes) {
			// The user's own blend shape on this parameter stands as it is.
			if (skinned.blendShapes.any { it.parameterId == axis.parameterId }) continue
			val forms = axis.keys.map { key -> if (key == 0f) null else deltasAt(subject, mapOf(axis.parameterId to key)) }
			if (forms.all { shape -> shape == null || shape.all { abs(it) < SkeletonRig.POSE_EPSILON } }) continue
			shapes += SkeletonRig.blendBinding(axis.parameterId, axis.keys) { ki ->
				val shape = forms[ki]!!
				MeshForm(FloatArray(shape.size) { reference[it] + shape[it] }, drawOrder, opacity, multiply, screen)
			}
		}
		skinned = skinned.copy(blendShapes = skinned.blendShapes + shapes)
		return skinned
	}

	/**
	 * [existing] with [added] axes summed in: a parameter both key gets the keys of both, and every cell is the
	 * existing grid at that coordinate plus [skin] there.
	 */
	private fun merged(existing: KeyformGrid<MeshDeltaForm>?, added: List<KeyformAxis>, skin: (Map<ParameterId, Float>) -> FloatArray): KeyformGrid<MeshDeltaForm>? {
		if (added.isEmpty()) return existing
		val axes = ArrayList<KeyformAxis>()
		for (axis in existing?.axes.orEmpty()) {
			val more = added.firstOrNull { it.parameterId == axis.parameterId }
			axes += if (more == null) axis else KeyformAxis(axis.parameterId, (axis.keys + more.keys).distinct().sorted().toFloatArray())
		}
		for (axis in added) if (axes.none { it.parameterId == axis.parameterId }) axes += axis
		val cells = existing?.cellsByLinearIndex
		return KeyformGrid(axes, SkeletonRig.cartesian(axes).map { coordinate ->
			val values = axes.indices.associate { axes[it].parameterId to axes[it].keys[coordinate[it]] }
			val deltas = skin(values.filterValues { it != 0f }).copyOf()
			if (existing != null && cells != null) {
				val corners = gridCorners(existing) { values[it] ?: 0f }
				if (corners != null) for (corner in corners) {
					val form = cells[corner.linearIndex]?.form ?: continue
					for (i in deltas.indices) deltas[i] += corner.weight * form.positionDeltas[i]
				}
			}
			KeyformCell(coordinate, MeshDeltaForm(deltas))
		})
	}
}
