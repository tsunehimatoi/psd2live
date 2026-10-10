package io.github.psd2live.core

import io.github.psd2live.targets.cubism.PuppetIr
import org.umamo.edit.VertexSource
import org.umamo.edit.withDeformerDeleted
import org.umamo.edit.withDeformerMoved
import org.umamo.runtime.keyform.FormInterpolator
import org.umamo.runtime.keyform.RotationPivotInterpolator
import org.umamo.runtime.keyform.WarpLatticeInterpolator
import org.umamo.runtime.keyform.withAxisCollapsed
import org.umamo.runtime.keyform.withKeyInserted
import org.umamo.runtime.model.*
import io.github.psd2live.format.model.RigIR as Ir

/**
 * Regeneration as a three-way merge (see the materialized rig design, section 5): the generators ran again on
 * changed input, and the user's changes to their previous output carry over onto the new output.
 *
 * [merge] takes the previous generated rig G, the new one G' and the authored rig M (G with the user's edits). Objects
 * match by id; "changed" always means "differs from G" by content (compared through the neutral IR, so stored and
 * rebuilt rigs compare alike). An object the user did not change follows G'; one the generators did not change keeps
 * the user's version; one both changed merges field by field, the user's value winning a conflict. Geometry the user
 * changed carries over as a residual (M - G per keyform cell and at rest) moved into the new parent's space and onto
 * new vertices through texture coordinates. An object the user created stays, re-homed when its parent vanished; one
 * the user deleted stays deleted; one the generators dropped goes unless the user changed it. Nothing fails: what does
 * not carry over cleanly is kept as the user had it, or dropped, and reported as an [Issue].
 *
 * G and G' are generated rigs as the document shows them before its edits: [BuiltRig.resolvedPuppet], with the parts of
 * version 2 splits in place of what they supersede, so a part the generators skin or re-parent is a generated object.
 */
object RigRegeneration {
	enum class IssueKind(val code: String) {
		/** A user object whose parent the generators dropped now hangs from the nearest surviving ancestor. */
		REHOMED("rehomed"),
		/** An object or parameter the generators dropped stays because the user changed or uses it. */
		RETIRED_KEPT("retired_kept"),
		/** The user and the generators changed the same value; the user's value stays. */
		CONFLICT("conflict"),
		/** The generators moved an object the user changed under another parent; the user's changes moved with it. */
		REPARENTED("reparented"),
		/** The generators changed a mesh's vertices; the user's per-vertex changes moved onto the new ones. */
		TOPOLOGY_MIGRATED("topology_migrated"),
		/** The user changed a mesh's vertices; the generators' new mesh was not taken. */
		TOPOLOGY_KEPT("topology_kept"),
		/** The user changed a mesh's vertices; they stay, and the generators' new parent and keyforms moved onto them. */
		TOPOLOGY_FOLLOWED("topology_followed"),
		/** A user object followed what its parent held to the deformer the generators handed it to. */
		FOLLOWED("followed"),
		/** A regeneration checkpoint an earlier build merged differently: merged again, and the correction carried forward. */
		REMERGED("remerged"),
		/** A user object that no longer has what it refers to (a mesh, a vertex) was removed. */
		DROPPED("dropped"),
	}

	data class Issue(val kind: IssueKind, val target: String, val detail: String = "")

	class Result(val model: PuppetModel, val issues: List<Issue>)

	/** Where each of an object's three versions is: in G, G' and M. */
	private class Versions<T>(val g: T?, val g2: T?, val m: T?)

	internal fun merge(previous: PuppetModel, next: PuppetModel, authored: PuppetModel, checkpoint: () -> Unit = {}): Result =
		Merge(previous, next, authored, checkpoint).run()

	private class Merge(val g: PuppetModel, val g2: PuppetModel, val m: PuppetModel, val checkpoint: () -> Unit) {
		val issues = ArrayList<Issue>()
		val irG: Ir = PuppetIr.toIr(g)
		val irG2: Ir = PuppetIr.toIr(g2)
		val irM: Ir = PuppetIr.toIr(m)

		fun issue(kind: IssueKind, target: String, detail: String = "") { issues += Issue(kind, target, detail) }

		/** The value of a field: G' when the user left it, M when the generators left it, else M and a conflict. */
		fun <T> pick(gv: T, g2v: T, mv: T, target: String, field: String): T = when {
			mv == gv -> g2v
			g2v == gv -> mv
			mv == g2v -> mv
			else -> { issue(IssueKind.CONFLICT, target, field); mv }
		}

		/** [pick] deciding by the IR values [gi], [g2i], [mi] and returning the model values. */
		fun <T> pickBy(gi: Any?, g2i: Any?, mi: Any?, g2v: T, mv: T, target: String, field: String): T = when {
			mi == gi -> g2v
			g2i == gi -> mv
			mi == g2i -> mv
			else -> { issue(IssueKind.CONFLICT, target, field); mv }
		}

		fun run(): Result {
			val deformers = mergeDeformers()
			val parameters = mergeParameters()
			checkpoint()
			val frame = g2.copy(parameters = parameters.values.toList(), deformers = deformers.list, drawables = emptyList(), glues = emptyList(),
				deformPaths = emptyList(), vertexGroups = emptyList())
			val drawables = mergeDrawables(frame)
			checkpoint()
			var model = frame.copy(drawables = drawables.list)
			model = model.copy(vertexGroups = mergeVertexGroups(model, drawables), deformPaths = mergePaths(model, drawables),
				glues = mergeGlues(model, drawables))
			model = withTree(model)
			model = withMasks(model)
			model = followSuccessors(model, deformers.vanished.toSet())
			// Generated deformers the user left that the generators dropped: their user children re-home upward, rest pose baked.
			for (id in deformers.vanished) {
				// A child the generators dropped too is no user object moving up.
				val children = model.deformers.filter { it.parent == id && it.id !in deformers.vanished }.map { it.id.raw } +
					model.drawables.filter { it.parentDeformerId == id }.map { it.id.raw }
				// Unwrapping maps the children through the deformer's one unkeyed form: its rest pose, as the generators dropped its motion.
				model = model.copy(deformers = model.deformers.map { if (it.id == id) resting(it, model.parameters) else it }).withDeformerDeleted(id)
				children.forEach { issue(IssueKind.REHOMED, it, id.raw) }
			}
			model = withParameters(model, parameters)
			return Result(model.withDerivedRenderRoot(), issues)
		}

		// ---- Succession

		/**
		 * The deformers whose contents the generators handed to another, read off G and G': every object G hangs under
		 * D that G' still has hangs under one deformer X != D in G', and either D is gone from G' (the arm hang warps a
		 * skeleton replaces) or X is new in G' and sits below D (the torso warp a skeleton splices under the breath warp).
		 * Objects split across several parents name no successor.
		 */
		fun successors(): Map<DeformerId, DeformerId> {
			val g2Deformers = g2.deformers.associateBy { it.id }
			val inG = g.deformers.mapTo(HashSet()) { it.id }
			val g2Parents = HashMap<String, DeformerId?>()
			g2.deformers.forEach { g2Parents["d:${it.id.raw}"] = it.parent }
			g2.drawables.forEach { g2Parents["m:${it.id.raw}"] = it.parentDeformerId }
			val children = HashMap<DeformerId, MutableList<String>>()
			g.deformers.forEach { d -> d.parent?.let { children.getOrPut(it) { ArrayList() } += "d:${d.id.raw}" } }
			g.drawables.forEach { d -> d.parentDeformerId?.let { children.getOrPut(it) { ArrayList() } += "m:${d.id.raw}" } }
			val out = LinkedHashMap<DeformerId, DeformerId>()
			for (deformer in g.deformers) {
				val kept = children[deformer.id].orEmpty().filter { it in g2Parents }
				if (kept.isEmpty()) continue
				val target = kept.map { g2Parents[it] }.distinct().singleOrNull() ?: continue
				if (target == deformer.id) continue
				val vanished = deformer.id !in g2Deformers
				val interposed = target !in inG && generateSequence(g2Deformers[target]?.parent) { g2Deformers[it]?.parent }.any { it == deformer.id }
				// A vanished deformer whose contents went to its own parent re-homes them there anyway, rest pose and all.
				if (vanished && target == deformer.parent) continue
				if (vanished || interposed) out[deformer.id] = target
			}
			return out
		}

		/**
		 * [model] with each user object - one the user created, or hung under another parent than G gives it - whose
		 * parent the generators handed on ([successors]) under the successor: a mesh where it shows at the default pose
		 * (as a structure `bind` with space=canvas carries it), a deformer when the move leaves every mesh below it in
		 * place. [vanished] are the generated deformers about to be unwrapped; what they still hold re-homes upward.
		 */
		fun followSuccessors(model: PuppetModel, vanished: Set<DeformerId>): PuppetModel {
			val successors = successors()
			if (successors.isEmpty()) return model
			val present = model.deformers.mapTo(HashSet()) { it.id }
			fun successor(parent: DeformerId?): DeformerId? {
				var current = parent ?: return null
				val seen = HashSet<DeformerId>()
				while (seen.add(current)) {
					val next = successors[current] ?: break
					if (next !in present) break
					current = next
				}
				return current.takeIf { it != parent }
			}
			val gDrawables = g.drawables.associateBy { it.id }; val gDeformers = g.deformers.associateBy { it.id }
			var current = model
			for (drawable in model.drawables) {
				val generated = gDrawables[drawable.id]
				if (generated != null && generated.parentDeformerId == drawable.parentDeformerId) continue
				val target = successor(drawable.parentDeformerId) ?: continue
				val moved = current.copy(drawables = current.drawables.map { if (it.id == drawable.id) it.copy(parentDeformerId = target) else it })
				current = try {
					RigStructureEdits.carriedToParent(current, moved, drawable.id).also { issue(IssueKind.FOLLOWED, "mesh:${drawable.id.raw}", target.raw) }
				} catch (failure: IllegalArgumentException) {
					if (drawable.parentDeformerId !in vanished) issue(IssueKind.CONFLICT, "mesh:${drawable.id.raw}", "it could not follow its parent's contents to ${target.raw}")
					current
				}
			}
			for (deformer in model.deformers) {
				val generated = gDeformers[deformer.id]
				if (generated != null && generated.parent == deformer.parent) continue
				val target = successor(deformer.parent) ?: continue
				if (target in current.deformerSelfAndDescendants(deformer.id)) continue
				val moved = current.withDeformerMoved(deformer.id, target, null)
				if (sameRest(current, moved, current.deformerSelfAndDescendants(deformer.id))) {
					current = moved
					issue(IssueKind.FOLLOWED, "deformer:${deformer.id.raw}", target.raw)
				} else if (deformer.parent !in vanished) {
					issue(IssueKind.CONFLICT, "deformer:${deformer.id.raw}", "it could not follow its parent's contents to ${target.raw}")
				}
			}
			return current
		}

		/** Whether every mesh under [deformers] shows in [b] at the default pose where it does in [a]. */
		fun sameRest(a: PuppetModel, b: PuppetModel, deformers: Set<DeformerId>): Boolean {
			val ids = a.drawables.filter { it.parentDeformerId in deformers }.mapTo(HashSet()) { it.id }
			if (ids.isEmpty()) return true
			val evaluator = org.umamo.render.eval.CpuDeformationEvaluator()
			val before = evaluator.evaluate(a, emptyMap()).worldPositions
			val after = evaluator.evaluate(b, emptyMap()).worldPositions
			return ids.all { id ->
				val x = before[id] ?: return@all true; val y = after[id] ?: return@all false
				x.size == y.size && x.indices.all { kotlin.math.abs(x[it] - y[it]) < 0.05f }
			}
		}

		/** [deformer] with one unkeyed form: its grid at every parameter's default. */
		fun resting(deformer: Deformer, parameters: List<Parameter>): Deformer {
			val defaults = parameters.associate { it.id to it.default }
			fun <F> rest(grid: KeyformGrid<F>?, interpolator: FormInterpolator<F>): KeyformGrid<F>? {
				var current = grid ?: return null
				while (current.axes.isNotEmpty()) {
					val axis = current.axes.first()
					val value = (defaults[axis.parameterId] ?: 0f).coerceIn(axis.keys.first(), axis.keys.last())
					current = current.withKeyInserted(axis.parameterId, value, interpolator)
					if (current.axes.size == 1) {
						val keys = current.axes.single().keys
						val index = keys.indices.minByOrNull { kotlin.math.abs(keys[it] - value) } ?: return null
						val form = current.cells.firstOrNull { it.coordinate.single() == index }?.form ?: return null
						return KeyformGrid(emptyList(), listOf(KeyformCell(intArrayOf(), form)))
					}
					current = current.withAxisCollapsed(axis.parameterId, value) ?: return null
				}
				return current
			}
			return when (deformer) {
				is Deformer.Warp -> deformer.copy(geometryGrid = rest(deformer.geometryGrid, WarpLatticeInterpolator))
				is Deformer.Rotation -> deformer.copy(geometryGrid = rest(deformer.geometryGrid, RotationPivotInterpolator))
			}
		}

		// ---- Order

		/**
		 * The merged order of [kept] ids: G' order when the user left the order of what G and M share, else M's;
		 * the other side's additions are inserted after their nearest preceding neighbour there.
		 */
		fun <K> order(gIds: List<K>, g2Ids: List<K>, mIds: List<K>, kept: Set<K>): List<K> {
			val shared = mIds.filter { it in gIds.toHashSet() }
			val userOrder = shared != gIds.filter { it in mIds.toHashSet() }
			val (primary, secondary) = if (userOrder) mIds to g2Ids else g2Ids to mIds
			val result = primary.filterTo(ArrayList()) { it in kept }
			val present = result.toHashSet()
			var previous: K? = null
			for (id in secondary) {
				if (id !in present && id in kept) {
					result.add(previous?.let { result.indexOf(it) + 1 } ?: 0, id)
					present += id
				}
				if (id in present) previous = id
			}
			for (id in kept) if (id !in present) { result += id; present += id }
			return result
		}

		// ---- Parameters

		fun mergeParameters(): LinkedHashMap<ParameterId, Parameter> {
			val byG = g.parameters.associateBy { it.id }; val byG2 = g2.parameters.associateBy { it.id }; val byM = m.parameters.associateBy { it.id }
			val values = LinkedHashMap<ParameterId, Parameter>()
			for (id in byG.keys + byG2.keys + byM.keys) {
				val gv = byG[id]; val g2v = byG2[id]; val mv = byM[id]
				when {
					gv != null && g2v != null && mv != null -> values[id] = pick(gv, g2v, mv, "parameter:${id.raw}", "definition")
					gv != null && g2v == null && mv != null -> values[id] = mv // retired unless nothing uses it ([withParameters])
					gv == null && mv != null -> { if (g2v != null && g2v != mv) issue(IssueKind.CONFLICT, "parameter:${id.raw}", "added by both"); values[id] = mv }
					gv == null && g2v != null -> values[id] = g2v
				}
			}
			val order = order(g.parameters.map { it.id }, g2.parameters.map { it.id }, m.parameters.map { it.id }, values.keys)
			return order.associateWithTo(LinkedHashMap()) { values.getValue(it) }
		}

		/** [model] with the parameters it needs: retired generated ones go unless something keys on them. */
		fun withParameters(model: PuppetModel, merged: Map<ParameterId, Parameter>): PuppetModel {
			val used = usedParameters(model)
			val gIds = g.parameters.mapTo(HashSet()) { it.id }; val g2Ids = g2.parameters.mapTo(HashSet()) { it.id }
			val kept = merged.values.filter { parameter ->
				val retired = parameter.id in gIds && parameter.id !in g2Ids
				if (!retired) true else (parameter.id in used).also { if (it) issue(IssueKind.RETIRED_KEPT, "parameter:${parameter.id.raw}") }
			}
			val keptIds = kept.mapTo(HashSet()) { it.id }
			// A parameter an object still keys on is never left out.
			val missing = used.filter { it !in keptIds }.mapNotNull { id -> m.parameters.firstOrNull { it.id == id } ?: g.parameters.firstOrNull { it.id == id } }
			val parameters = kept + missing
			val ids = parameters.mapTo(HashSet()) { it.id }
			val links = mergeLinks().filter { it.horizontal in ids && it.vertical in ids }
			return model.copy(parameters = parameters, parameterLinks = links, parameterTree = mergeTree(ids))
		}

		fun usedParameters(model: PuppetModel): Set<ParameterId> {
			val used = HashSet<ParameterId>()
			fun channels(grids: ChannelGrids) = grids.gridsByChannel.values.forEach { grid -> grid.axes.forEach { used += it.parameterId } }
			fun <F : Any> blends(bindings: List<BlendShapeBinding<F>>) = bindings.forEach { b -> used += b.parameterId; b.limits.forEach { used += it.parameterId } }
			for (d in model.deformers) {
				channels(d.channelGrids)
				when (d) {
					is Deformer.Warp -> { d.geometryGrid?.axes?.forEach { used += it.parameterId }; blends(d.blendShapes) }
					is Deformer.Rotation -> { d.geometryGrid?.axes?.forEach { used += it.parameterId }; blends(d.blendShapes) }
				}
			}
			for (d in model.drawables) { d.geometryGrid?.axes?.forEach { used += it.parameterId }; channels(d.channelGrids); blends(d.blendShapes) }
			for (p in model.parts) { channels(p.channelGrids); blends(p.blendShapes) }
			for (glue in model.glues) channels(glue.channelGrids)
			return used
		}

		fun mergeLinks(): List<ParameterLink> {
			val removed = g.parameterLinks.filter { it !in g2.parameterLinks }.toSet()
			val added = g2.parameterLinks.filter { it !in g.parameterLinks }
			return (m.parameterLinks.filter { it !in removed } + added.filter { it !in m.parameterLinks }).distinct()
		}

		/** The parameter tree: G''s when the user left G's, else M's; either way pruned to [ids], the rest appended. */
		fun mergeTree(ids: Set<ParameterId>): List<ParameterNode> {
			val tree = if (irM.parameterTree == irG.parameterTree) g2.parameterTree else m.parameterTree
			val seen = HashSet<ParameterId>()
			fun prune(nodes: List<ParameterNode>): List<ParameterNode> = nodes.mapNotNull { node -> when (node) {
				is ParameterNode.Param -> node.takeIf { it.id in ids && seen.add(it.id) }
				is ParameterNode.Group -> node.copy(children = prune(node.children))
			} }
			val pruned = prune(tree)
			if (tree.isEmpty()) return tree
			return pruned + ids.filter { it !in seen }.map { ParameterNode.Param(it) }
		}

		// ---- Deformers

		inner class MergedDeformers(val list: List<Deformer>, val vanished: List<DeformerId>)

		fun mergeDeformers(): MergedDeformers {
			val byG = g.deformers.associateBy { it.id }; val byG2 = g2.deformers.associateBy { it.id }; val byM = m.deformers.associateBy { it.id }
			val irByG = irG.deformers.associateBy { it.id }; val irByG2 = irG2.deformers.associateBy { it.id }; val irByM = irM.deformers.associateBy { it.id }
			val merged = LinkedHashMap<DeformerId, Deformer>()
			val vanished = ArrayList<DeformerId>()
			for (id in byG.keys + byG2.keys + byM.keys) {
				val v = Versions(byG[id], byG2[id], byM[id])
				val target = "deformer:${id.raw}"
				when {
					v.g != null && v.g2 != null && v.m != null -> merged[id] = mergeDeformer(v.g, v.g2, v.m, irByG.getValue(id.raw), irByG2.getValue(id.raw), irByM.getValue(id.raw))
					v.g != null && v.g2 == null && v.m != null -> {
						merged[id] = v.m
						if (irByM[id.raw] == irByG[id.raw]) vanished += id else issue(IssueKind.RETIRED_KEPT, target)
					}
					v.g == null && v.m != null -> { if (v.g2 != null) issue(IssueKind.CONFLICT, target, "added by both"); merged[id] = v.m }
					v.g == null && v.g2 != null -> merged[id] = v.g2
				}
			}
			// A generated deformer under one the user deleted hangs from that one's parent instead.
			val present = merged.keys
			for ((id, deformer) in merged) {
				var parent = deformer.parent
				while (parent != null && parent !in present) parent = byG2[parent]?.parent ?: byG[parent]?.parent ?: byM[parent]?.parent
				if (parent != deformer.parent) merged[id] = deformer.reparented(parent)
			}
			val order = order(g.deformers.map { it.id }, g2.deformers.map { it.id }, m.deformers.map { it.id }, merged.keys)
			return MergedDeformers(order.map { merged.getValue(it) }, vanished)
		}

		fun Deformer.reparented(parent: DeformerId?): Deformer = when (this) {
			is Deformer.Warp -> copy(parent = parent)
			is Deformer.Rotation -> copy(parent = parent)
		}

		fun mergeDeformer(gd: Deformer, g2d: Deformer, md: Deformer,
		                  gi: io.github.psd2live.format.model.Deformer, g2i: io.github.psd2live.format.model.Deformer,
		                  mi: io.github.psd2live.format.model.Deformer): Deformer {
			if (mi == gi) return g2d
			if (g2i == gi) return md
			val target = "deformer:${gd.id.raw}"
			if (gd::class != g2d::class || gd::class != md::class) { issue(IssueKind.CONFLICT, target, "kind"); return md }
			if (md.parent != gd.parent) { issue(IssueKind.CONFLICT, target, "the user re-parented it; the generators' changes are not applied"); return md }
			val parentMoved = g2d.parent != gd.parent
			return when (g2d) {
				is Deformer.Warp -> {
					gd as Deformer.Warp; md as Deformer.Warp
					gi as io.github.psd2live.format.model.Deformer.Warp; g2i as io.github.psd2live.format.model.Deformer.Warp
					mi as io.github.psd2live.format.model.Deformer.Warp
					val userLattice = mi.lattice != gi.lattice || mi.shapes != gi.shapes || mi.rows != gi.rows || mi.columns != gi.columns
					val lattice = when {
						!userLattice -> g2d.geometryGrid
						g2i.lattice == gi.lattice && g2i.rows == gi.rows && g2i.columns == gi.columns && !parentMoved -> md.geometryGrid
						parentMoved || g2d.rows != gd.rows || g2d.columns != gd.columns -> {
							issue(IssueKind.CONFLICT, target, "lattice: the generators re-framed it; the user's lattice edits are not applied"); g2d.geometryGrid
						}
						else -> sumLattices(gd.geometryGrid, g2d.geometryGrid, md.geometryGrid) ?: run { issue(IssueKind.CONFLICT, target, "lattice"); md.geometryGrid }
					}
					val keepUserShape = userLattice && lattice === md.geometryGrid
					g2d.copy(name = pick(gd.name, g2d.name, md.name, target, "name"), partId = pick(gd.partId, g2d.partId, md.partId, target, "part"),
						isQuadTransform = pick(gd.isQuadTransform, g2d.isQuadTransform, md.isQuadTransform, target, "quad"),
						rows = if (keepUserShape) md.rows else g2d.rows, columns = if (keepUserShape) md.columns else g2d.columns,
						geometryGrid = lattice,
						channelGrids = pickBy(gi.channels, g2i.channels, mi.channels, g2d.channelGrids, md.channelGrids, target, "channels"),
						opacity = pick(gd.opacity, g2d.opacity, md.opacity, target, "opacity"),
						multiplyColor = pick(gd.multiplyColor, g2d.multiplyColor, md.multiplyColor, target, "multiply"),
						screenColor = pick(gd.screenColor, g2d.screenColor, md.screenColor, target, "screen"),
						isSelectable = pick(gd.isSelectable, g2d.isSelectable, md.isSelectable, target, "selectable"),
						isVisible = pick(gd.isVisible, g2d.isVisible, md.isVisible, target, "visible"),
						isEnabled = pick(gd.isEnabled, g2d.isEnabled, md.isEnabled, target, "enabled"),
						blendShapes = if (keepUserShape) md.blendShapes else g2d.blendShapes)
				}
				is Deformer.Rotation -> {
					gd as Deformer.Rotation; md as Deformer.Rotation
					gi as io.github.psd2live.format.model.Deformer.Rotation; g2i as io.github.psd2live.format.model.Deformer.Rotation
					mi as io.github.psd2live.format.model.Deformer.Rotation
					val userPivot = mi.pivot != gi.pivot || mi.shapes != gi.shapes || mi.baseAngle != gi.baseAngle
					val pivot = when {
						!userPivot -> g2d.geometryGrid
						g2i.pivot == gi.pivot && !parentMoved -> md.geometryGrid
						parentMoved -> { issue(IssueKind.CONFLICT, target, "pivot: the generators re-parented it; the user's pivot edits are not applied"); g2d.geometryGrid }
						else -> sumPivots(gd.geometryGrid, g2d.geometryGrid, md.geometryGrid) ?: run { issue(IssueKind.CONFLICT, target, "pivot"); md.geometryGrid }
					}
					val keepUser = userPivot && pivot === md.geometryGrid
					g2d.copy(name = pick(gd.name, g2d.name, md.name, target, "name"), partId = pick(gd.partId, g2d.partId, md.partId, target, "part"),
						baseAngle = if (keepUser) md.baseAngle else g2d.baseAngle, geometryGrid = pivot,
						channelGrids = pickBy(gi.channels, g2i.channels, mi.channels, g2d.channelGrids, md.channelGrids, target, "channels"),
						opacity = pick(gd.opacity, g2d.opacity, md.opacity, target, "opacity"),
						multiplyColor = pick(gd.multiplyColor, g2d.multiplyColor, md.multiplyColor, target, "multiply"),
						screenColor = pick(gd.screenColor, g2d.screenColor, md.screenColor, target, "screen"),
						flipX = pick(gd.flipX, g2d.flipX, md.flipX, target, "flipX"), flipY = pick(gd.flipY, g2d.flipY, md.flipY, target, "flipY"),
						isSelectable = pick(gd.isSelectable, g2d.isSelectable, md.isSelectable, target, "selectable"),
						isVisible = pick(gd.isVisible, g2d.isVisible, md.isVisible, target, "visible"),
						isEnabled = pick(gd.isEnabled, g2d.isEnabled, md.isEnabled, target, "enabled"),
						blendShapes = if (keepUser) md.blendShapes else g2d.blendShapes)
				}
			}
		}

		/** G' + (M - G) cell by cell and point by point, when the three grids have the same axes, cells and sizes. */
		fun sumLattices(a: KeyformGrid<WarpLatticeForm>?, b: KeyformGrid<WarpLatticeForm>?, c: KeyformGrid<WarpLatticeForm>?): KeyformGrid<WarpLatticeForm>? {
			if (a == null || b == null || c == null || !aligned(a, b) || !aligned(a, c)) return null
			return KeyformGrid(b.axes, b.cells.mapIndexed { index, cell ->
				val g = a.cells[index].form.controlPoints; val n = cell.form.controlPoints; val u = c.cells[index].form.controlPoints
				if (g.size != n.size || g.size != u.size) return null
				KeyformCell(cell.coordinate, WarpLatticeForm(FloatArray(n.size) { n[it] + u[it] - g[it] }))
			})
		}

		fun sumPivots(a: KeyformGrid<RotationPivotForm>?, b: KeyformGrid<RotationPivotForm>?, c: KeyformGrid<RotationPivotForm>?): KeyformGrid<RotationPivotForm>? {
			if (a == null || b == null || c == null || !aligned(a, b) || !aligned(a, c)) return null
			return KeyformGrid(b.axes, b.cells.mapIndexed { index, cell ->
				val g = a.cells[index].form; val n = cell.form; val u = c.cells[index].form
				KeyformCell(cell.coordinate, RotationPivotForm(n.originX + u.originX - g.originX, n.originY + u.originY - g.originY,
					n.angle + u.angle - g.angle, n.scale + u.scale - g.scale))
			})
		}

		fun <F> aligned(a: KeyformGrid<F>, b: KeyformGrid<F>) = a.axes.size == b.axes.size &&
			a.axes.indices.all { a.axes[it].parameterId == b.axes[it].parameterId && a.axes[it].keys.contentEquals(b.axes[it].keys) } &&
			a.cells.size == b.cells.size && a.cells.indices.all { a.cells[it].coordinate.contentEquals(b.cells[it].coordinate) }

		// ---- Drawables

		/** A mesh whose vertices the generators replaced: its old (M = G) and new mesh and how new vertices derive from old ones. */
		inner class Migration(val old: DrawableMesh, val new: DrawableMesh, val sources: List<VertexSource>, val glueMap: IntArray,
		                      val oldCanvas: DrawableMesh, val newCanvas: DrawableMesh)

		inner class MergedDrawables(val list: List<Drawable>, val migrations: Map<DrawableId, Migration>, val userTopology: Set<DrawableId>)

		fun mergeDrawables(frame: PuppetModel): MergedDrawables {
			val byG = g.drawables.associateBy { it.id }; val byG2 = g2.drawables.associateBy { it.id }; val byM = m.drawables.associateBy { it.id }
			val irByG = irG.meshes.associateBy { it.id }; val irByG2 = irG2.meshes.associateBy { it.id }; val irByM = irM.meshes.associateBy { it.id }
			val merged = LinkedHashMap<DrawableId, Drawable>()
			val migrations = HashMap<DrawableId, Migration>()
			val userTopology = HashSet<DrawableId>()
			for (id in byG.keys + byG2.keys + byM.keys) {
				checkpoint()
				val gd = byG[id]; val g2d = byG2[id]; val md = byM[id]
				val target = "mesh:${id.raw}"
				when {
					gd != null && g2d != null && md != null ->
						merged[id] = mergeDrawable(frame, gd, g2d, md, irByG.getValue(id.raw), irByG2.getValue(id.raw), irByM.getValue(id.raw), migrations, userTopology)
					gd != null && g2d == null && md != null -> if (irByM[id.raw] != irByG[id.raw]) { merged[id] = md; issue(IssueKind.RETIRED_KEPT, target) }
					gd == null && md != null -> { if (g2d != null) issue(IssueKind.CONFLICT, target, "added by both"); merged[id] = md }
					gd == null && g2d != null -> merged[id] = g2d
				}
			}
			val order = order(g.drawables.map { it.id }, g2.drawables.map { it.id }, m.drawables.map { it.id }, merged.keys)
			return MergedDrawables(order.map { merged.getValue(it) }, migrations, userTopology)
		}

		fun topology(mesh: DrawableMesh?) = mesh?.let { it.positions.size to it.indices.toList() }

		fun mergeDrawable(frame: PuppetModel, gd: Drawable, g2d: Drawable, md: Drawable,
		                  gi: io.github.psd2live.format.model.Mesh, g2i: io.github.psd2live.format.model.Mesh, mi: io.github.psd2live.format.model.Mesh,
		                  migrations: MutableMap<DrawableId, Migration>, userTopology: MutableSet<DrawableId>): Drawable {
			if (mi == gi) return g2d
			if (g2i == gi) return md
			val target = "mesh:${gd.id.raw}"
			var result = g2d.copy(
				name = pick(gd.name, g2d.name, md.name, target, "name"),
				blendMode = pick(gd.blendMode, g2d.blendMode, md.blendMode, target, "blend"),
				maskedBy = pick(gd.maskedBy, g2d.maskedBy, md.maskedBy, target, "masks"),
				drawOrder = pick(gd.drawOrder, g2d.drawOrder, md.drawOrder, target, "drawOrder"),
				opacity = pick(gd.opacity, g2d.opacity, md.opacity, target, "opacity"),
				multiplyColor = pick(gd.multiplyColor, g2d.multiplyColor, md.multiplyColor, target, "multiply"),
				screenColor = pick(gd.screenColor, g2d.screenColor, md.screenColor, target, "screen"),
				invertMask = pick(gd.invertMask, g2d.invertMask, md.invertMask, target, "invertMask"),
				alphaBlendMode = pick(gd.alphaBlendMode, g2d.alphaBlendMode, md.alphaBlendMode, target, "alphaBlend"),
				culling = pick(gd.culling, g2d.culling, md.culling, target, "culling"),
				isVisible = pick(gd.isVisible, g2d.isVisible, md.isVisible, target, "visible"),
				isSelectable = pick(gd.isSelectable, g2d.isSelectable, md.isSelectable, target, "selectable"),
				textureSourceId = pick(gd.textureSourceId, g2d.textureSourceId, md.textureSourceId, target, "textureSource"),
				userData = pick(gd.userData, g2d.userData, md.userData, target, "userData"),
			)
			val parameters = frame.parameters.associateBy { it.id }
			// Channels: the user's change on top of the new generated channels.
			if (mi.channels != gi.channels) result = result.copy(channelGrids = if (g2i.channels == gi.channels) md.channelGrids else
				PrimitiveResidual.composeChannels(parameters, result, PrimitiveResidual.channels(m, md, gd, checkpoint)))
			// Geometry, then the blend shapes in the space it ended in.
			val geometry = mergeGeometry(frame, parameters, result, gd, g2d, md, gi, g2i, mi, migrations, userTopology)
			return geometry.drawable.copy(blendShapes = mergeBlends(gd, g2d, md, gi, g2i, mi, geometry))
		}

		/**
		 * A merged mesh's geometry and the space it is in: the user's parent and vertices ([user]), or the generators'.
		 * [toNext] carries a delta from the user's space into the generators' (null: it cannot be carried); a delta
		 * already in the result's space carries as it is.
		 */
		inner class Geometry(val drawable: Drawable, val user: Boolean, val fromNext: ((FloatArray) -> FloatArray)? = { it },
		                     toNext: () -> ((FloatArray) -> FloatArray)?) {
			val toNext by lazy(toNext)
		}

		fun mergeGeometry(frame: PuppetModel, parameters: Map<ParameterId, Parameter>, result: Drawable, gd: Drawable, g2d: Drawable, md: Drawable,
		                  gi: io.github.psd2live.format.model.Mesh, g2i: io.github.psd2live.format.model.Mesh, mi: io.github.psd2live.format.model.Mesh,
		                  migrations: MutableMap<DrawableId, Migration>, userTopology: MutableSet<DrawableId>): Geometry {
			val target = "mesh:${gd.id.raw}"
			val sameSpace = md.parentDeformerId == g2d.parentDeformerId && topology(md.mesh) == topology(g2d.mesh)
			val unchanged: (FloatArray) -> FloatArray = { it }
			// A delta in the user's space carried into the generators', the residual's way; null when it cannot be.
			fun carrier(migration: Migration?): ((FloatArray) -> FloatArray)? = try {
				val oldMesh = requireNotNull(md.mesh); val newMesh = requireNotNull(g2d.mesh)
				val space = PrimitiveResidual.ParentSpace(m, md, frame.copy(drawables = listOf(g2d)), g2d,
					if (migration == null) newMesh.positions else FloatArray(oldMesh.positions.size) { 0.5f })
				val carry: (FloatArray) -> FloatArray = { values -> space.convert(values).let { moved -> migration?.let { PrimitiveResidual.transfer(moved, it.sources) } ?: moved } }
				carry
			} catch (failure: IllegalArgumentException) {
				null
			}
			fun user(drawable: Drawable) = Geometry(drawable, true) { if (sameSpace) unchanged else null }
			val userGeometry = mi.geometry != gi.geometry || mi.offsets != gi.offsets || mi.parent != gi.parent
			if (!userGeometry) return Geometry(result, false) { when {
				sameSpace -> unchanged
				topology(md.mesh) == topology(g2d.mesh) -> carrier(null)
				else -> migration(gd, g2d)?.let(::carrier)
			} }
			if (md.parentDeformerId != gd.parentDeformerId) {
				if (g2i.geometry != gi.geometry || g2i.offsets != gi.offsets || g2i.parent != gi.parent)
					issue(IssueKind.CONFLICT, target, "the user re-parented it; the generators' geometry is not applied")
				return user(result.copy(parentDeformerId = md.parentDeformerId, mesh = md.mesh, geometryGrid = md.geometryGrid))
			}
			if (topology(md.mesh) != topology(gd.mesh)) {
				userTopology += gd.id
				val generatorsChanged = g2i.geometry != gi.geometry || g2i.offsets != gi.offsets || g2i.parent != gi.parent
				if (generatorsChanged) onUserTopology(frame, parameters, result, gd, g2d, md)?.let { (geometry, matched) ->
					issue(if (matched) IssueKind.TOPOLOGY_FOLLOWED else IssueKind.TOPOLOGY_KEPT, target)
					return geometry
				}
				if (topology(g2d.mesh) != topology(gd.mesh) || g2i.parent != gi.parent) issue(IssueKind.TOPOLOGY_KEPT, target)
				// The user left the parent but it stays theirs: say so, or the next merge reads it as their choice silently.
				if (g2i.parent != gi.parent) issue(IssueKind.CONFLICT, target, "the generators' new parent cannot hold the user's mesh; it stays under the old one")
				return user(result.copy(parentDeformerId = md.parentDeformerId, mesh = md.mesh, geometryGrid = md.geometryGrid))
			}
			if (g2i.geometry == gi.geometry && g2i.offsets == gi.offsets && g2i.parent == gi.parent)
				return user(result.copy(mesh = md.mesh, geometryGrid = md.geometryGrid))
			val oldMesh = requireNotNull(md.mesh); val newMesh = requireNotNull(g2d.mesh)
			val migration = if (topology(newMesh) == topology(oldMesh)) null else migration(gd, g2d)
			if (topology(newMesh) != topology(oldMesh) && migration == null) {
				issue(IssueKind.CONFLICT, target, "the generators replaced its vertices and they could not be matched; the user's geometry is not applied")
				return Geometry(result, false) { null }
			}
			migration?.let { migrations[gd.id] = it; issue(IssueKind.TOPOLOGY_MIGRATED, target) }
			if (g2d.parentDeformerId != gd.parentDeformerId) issue(IssueKind.REPARENTED, target, g2d.parentDeformerId?.raw.orEmpty())
			return try {
				val space = PrimitiveResidual.ParentSpace(m, md, frame.copy(drawables = listOf(g2d)), g2d,
					if (migration == null) newMesh.positions else FloatArray(oldMesh.positions.size) { 0.5f })
				fun carried(values: FloatArray) = migration?.let { PrimitiveResidual.transfer(values, it.sources) } ?: values
				val rest = FloatArray(oldMesh.positions.size) { requireNotNull(gd.mesh).positions[it] - oldMesh.positions[it] }
				val restMoved = space.convert(rest).let { moved -> FloatArray(moved.size) { -moved[it] } }
				val newRest = carried(restMoved).let { d -> FloatArray(newMesh.positions.size) { newMesh.positions[it] + d[it] } }
				val residual = PrimitiveResidual.carry(PrimitiveResidual.delta(m, md, g, gd, null, checkpoint)) { carried(space.convert(it)) }
				Geometry(result.copy(mesh = DrawableMesh(newRest, newMesh.uvs, newMesh.indices),
					geometryGrid = sum(parameters, g2d.geometryGrid, residual, newMesh.positions.size)), false) {
					if (sameSpace) unchanged else { values -> carried(space.convert(values)) }
				}
			} catch (failure: IllegalArgumentException) {
				issue(IssueKind.CONFLICT, target, "the user's geometry could not be moved into the new parent: ${failure.message}")
				Geometry(result, false) { null }
			}
		}

		/**
		 * A mesh whose topology the user changed (under the parent G gave it) while the generators changed it: the
		 * user's vertices under G''s parent, carrying G''s change. Each of G and G' is sampled on the user's vertices
		 * through canvas texture coordinates; the rest is where M shows it, moved as the generators moved the mesh on
		 * the canvas; the keyforms are G''s plus the user's residual over G, carried into the new parent at rest. When
		 * the meshes cannot be matched the user's mesh still moves under the new parent where it shows, with its own
		 * keyforms carried and none of the generators' (`false` beside the geometry). Null when the new parent cannot
		 * hold the mesh where it shows.
		 */
		fun onUserTopology(frame: PuppetModel, parameters: Map<ParameterId, Parameter>, result: Drawable, gd: Drawable, g2d: Drawable,
		                   md: Drawable): Pair<Geometry, Boolean>? = try {
			val mesh = requireNotNull(md.mesh); val size = mesh.positions.size
			val userCanvas = canvasMesh(m, md)
			fun sources(model: PuppetModel, drawable: Drawable) = try {
				RasterMeshJournal.prepare(canvasMesh(model, drawable), userCanvas, checkpoint).sources
			} catch (failure: IllegalArgumentException) {
				null
			}
			val fromG = sources(g, gd)
			val fromG2 = fromG?.let { sources(g2, g2d) }
			fun world(model: PuppetModel, drawable: Drawable) = requireNotNull(org.umamo.render.eval.drawableSpaceMapping(model, emptyMap(), drawable.id))
				.localToWorld(requireNotNull(drawable.mesh).positions)
			val shown = world(m, md)
			val shifted = if (fromG == null || fromG2 == null) shown else PrimitiveResidual.transfer(world(g2, g2d), fromG2).let { next ->
				val previous = PrimitiveResidual.transfer(world(g, gd), fromG)
				FloatArray(size) { shown[it] + next[it] - previous[it] }
			}
			val placed = md.copy(parentDeformerId = g2d.parentDeformerId)
			val from = requireNotNull(org.umamo.render.eval.drawableSpaceMapping(m, emptyMap(), md.id))
			val to = requireNotNull(org.umamo.render.eval.drawableSpaceMapping(frame.copy(drawables = listOf(placed)), emptyMap(), md.id))
			val all = (0 until size / 2).toSet()
			val rest = to.worldToLocalLinearized(shifted, FloatArray(size) { 0.5f }, shifted, all)
			val reached = to.localToWorld(rest)
			require(rest.all(Float::isFinite) && reached.indices.all { kotlin.math.abs(reached[it] - shifted[it]) < 0.05f }) { "The new parent cannot hold the mesh" }
			val toNext: (FloatArray) -> FloatArray = { delta ->
				if (delta.all { it == 0f }) FloatArray(delta.size) else {
					val moved = to.worldToLocalLinearized(from.localToWorld(FloatArray(size) { mesh.positions[it] + delta[it] }), rest, shifted, all)
					FloatArray(size) { moved[it] - rest[it] }
				}
			}
			val fromNext: ((FloatArray) -> FloatArray)? = fromG2?.let { s -> { values: FloatArray -> PrimitiveResidual.transfer(values, s) } }
			// The user's residual over G on the user's vertices, in the parent both share (all of the user's keyforms unmatched).
			fun default(id: ParameterId) = parameters[id]?.default ?: 0f
			val axes = PrimitiveResidual.unionAxes((md.geometryGrid?.axes.orEmpty() + gd.geometryGrid?.axes.orEmpty()).filter { it.parameterId in parameters })
			val gSize = requireNotNull(gd.mesh).positions.size
			var changed = false
			val cells = PrimitiveResidual.coordinates(axes).map { coordinate ->
				checkpoint()
				val key = PrimitiveResidual.key(axes, coordinate)
				val value = { id: ParameterId -> key[id] ?: default(id) }
				val authored = PrimitiveResidual.sample(md.geometryGrid, size, value)
				val generated = fromG?.let { PrimitiveResidual.transfer(PrimitiveResidual.sample(gd.geometryGrid, gSize, value), it) } ?: FloatArray(size)
				val residual = toNext(FloatArray(size) { authored[it] - generated[it] })
				if (residual.any { kotlin.math.abs(it) > 1e-5f }) changed = true
				KeyformCell(coordinate, MeshDeltaForm(residual))
			}
			val residual = if (changed) KeyformGrid(axes, cells) else null
			val next = fromNext?.let { PrimitiveResidual.carry(g2d.geometryGrid, it) }
			Geometry(result.copy(parentDeformerId = g2d.parentDeformerId, mesh = DrawableMesh(rest, mesh.uvs, mesh.indices),
				geometryGrid = sum(parameters, next, residual, size)), false, fromNext) { toNext } to (fromNext != null)
		} catch (failure: IllegalArgumentException) {
			null
		}

		/**
		 * The blend shapes of a merged mesh, parameter by parameter: one the user left follows G', one the generators left
		 * keeps the user's, one both changed keeps the user's; the user's own stay and the generators' new ones arrive.
		 * A shape's deltas are in its mesh's parent space and on its vertices, so each must be in [geometry]'s: in the
		 * generators' space the user's are carried there, and in the user's a generated shape made in another space is not
		 * applied (the user's stays, if any) - its deltas there would throw the vertices across the canvas.
		 */
		fun mergeBlends(gd: Drawable, g2d: Drawable, md: Drawable, gi: io.github.psd2live.format.model.Mesh,
		                g2i: io.github.psd2live.format.model.Mesh, mi: io.github.psd2live.format.model.Mesh, geometry: Geometry): List<BlendShapeBinding<MeshForm>> {
			val target = "mesh:${gd.id.raw}"
			val irG = gi.shapes.associateBy { it.parameter }; val irG2 = g2i.shapes.associateBy { it.parameter }; val irM = mi.shapes.associateBy { it.parameter }
			val byG2 = g2d.blendShapes.associateBy { it.parameterId }; val byM = md.blendShapes.associateBy { it.parameterId }
			val size = geometry.drawable.mesh?.positions?.size
			// Each side's binding in the result's space; null when it cannot be put there.
			fun generated(binding: BlendShapeBinding<MeshForm>): BlendShapeBinding<MeshForm>? {
				if (geometry.user && geometry.toNext == null) return null
				val place = geometry.fromNext ?: return null
				return binding.copy(forms = binding.forms.map { form ->
					form?.let { MeshForm(place(it.positionDeltas), it.drawOrder, it.opacity, it.multiplyColor, it.screenColor) }
				})
			}
			fun authored(binding: BlendShapeBinding<MeshForm>): BlendShapeBinding<MeshForm>? = if (geometry.user) binding else
				geometry.toNext?.let { carry -> binding.copy(forms = binding.forms.map { form ->
					form?.let { MeshForm(carry(it.positionDeltas), it.drawOrder, it.opacity, it.multiplyColor, it.screenColor) }
				}) }
			val ids = (g2d.blendShapes.map { it.parameterId } + md.blendShapes.map { it.parameterId }).distinct()
			return ids.mapNotNull { id ->
				val gv = irG[id.raw]; val g2v = irG2[id.raw]; val mv = irM[id.raw]
				// Whose version the three-way rule keeps: the user's (true), the generators' (false), or neither (null).
				val users = when {
					gv != null && g2v != null && mv != null -> when {
						mv == gv -> false
						g2v == gv || mv == g2v -> true
						else -> { issue(IssueKind.CONFLICT, target, "blend shape ${id.raw}"); true }
					}
					gv != null && mv == null -> null // the user deleted it
					gv != null -> if (mv != gv) true else null // the generators dropped it; the user's change stays
					g2v != null -> { if (mv != null) issue(IssueKind.CONFLICT, target, "blend shape ${id.raw} added by both"); mv != null }
					else -> true
				} ?: return@mapNotNull null
				val chosen = if (users) byM[id]?.let(::authored) ?: byG2[id]?.let(::generated)
					else byG2[id]?.let(::generated) ?: byM[id]?.let(::authored)
				if (chosen == null) issue(IssueKind.DROPPED, target, "blend shape ${id.raw}: made in another parent space")
				chosen?.takeIf { binding -> binding.forms.all { it == null || it.positionDeltas.size == size } }
			}
		}

		/** How [g2d]'s vertices derive from [gd]'s, matched by canvas texture coordinates; null when they cannot be. */
		fun migration(gd: Drawable, g2d: Drawable): Migration? = try {
			val old = requireNotNull(gd.mesh); val new = requireNotNull(g2d.mesh)
			val oldCanvas = canvasMesh(g, gd); val newCanvas = canvasMesh(g2, g2d)
			val plan = RasterMeshJournal.prepare(oldCanvas, newCanvas, checkpoint)
			Migration(old, new, plan.sources, plan.glueMap, oldCanvas, newCanvas)
		} catch (failure: IllegalArgumentException) {
			null
		}

		/** [drawable]'s mesh with canvas texture coordinates as both positions and uvs. */
		fun canvasMesh(model: PuppetModel, drawable: Drawable): DrawableMesh {
			val mesh = requireNotNull(drawable.mesh)
			val canvas = runCatching { RasterMeshJournal.TextureCoordinates(model, drawable).toCanvas(mesh.uvs) }.getOrDefault(mesh.uvs)
			return DrawableMesh(canvas, canvas, mesh.indices)
		}

		/** [base] plus [extra] at every key of the union of their axes ([extra] null leaves [base]). */
		fun sum(parameters: Map<ParameterId, Parameter>, base: KeyformGrid<MeshDeltaForm>?, extra: KeyformGrid<MeshDeltaForm>?, size: Int): KeyformGrid<MeshDeltaForm>? {
			if (extra == null) return base
			fun default(id: ParameterId) = parameters[id]?.default ?: 0f
			val axes = PrimitiveResidual.unionAxes(base?.axes.orEmpty() + extra.axes)
			return KeyformGrid(axes, PrimitiveResidual.coordinates(axes).map { coordinate ->
				val key = PrimitiveResidual.key(axes, coordinate)
				val value = { id: ParameterId -> key[id] ?: default(id) }
				val a = PrimitiveResidual.sample(base, size, value); val b = PrimitiveResidual.sample(extra, size, value)
				KeyformCell(coordinate, MeshDeltaForm(FloatArray(size) { a[it] + b[it] }))
			})
		}

		// ---- Per-mesh data

		fun mergeVertexGroups(model: PuppetModel, drawables: MergedDrawables): List<VertexGroup> {
			fun key(group: VertexGroup) = group.drawableId to group.name
			val byG = g.vertexGroups.associateBy(::key); val byG2 = g2.vertexGroups.associateBy(::key); val byM = m.vertexGroups.associateBy(::key)
			val counts = model.drawables.associate { it.id to (it.mesh?.vertexCount ?: 0) }
			val result = ArrayList<VertexGroup>()
			for (k in (m.vertexGroups.map(::key) + g2.vertexGroups.map(::key)).distinct()) {
				val gv = byG[k]; val g2v = byG2[k]; val mv = byM[k]
				val target = "vertex_group:${k.first.raw}/${k.second}"
				val userOwned = mv != null && (gv == null || !sameGroup(gv, mv))
				val value = when {
					mv == null -> if (gv == null) g2v else null // the user deleted it
					!userOwned -> g2v ?: if (gv != null) null else mv
					else -> drawables.migrations[k.first]?.let { mv.copy(weights = PrimitiveResidual.transferScalars(mv.weights, it.sources)) } ?: mv
				} ?: continue
				if (value.weights.size != counts[value.drawableId]) { if (userOwned) issue(IssueKind.DROPPED, target, "its mesh changed"); continue }
				result += value
			}
			return result
		}

		fun sameGroup(a: VertexGroup, b: VertexGroup) = a.kind == b.kind && a.weights.contentEquals(b.weights)

		fun mergePaths(model: PuppetModel, drawables: MergedDrawables): List<DeformPath> {
			val byG = g.deformPaths.associateBy { it.id }; val byG2 = g2.deformPaths.associateBy { it.id }; val byM = m.deformPaths.associateBy { it.id }
			val meshes = model.drawables.associateBy { it.id }
			val result = ArrayList<DeformPath>()
			for (id in (m.deformPaths.map { it.id } + g2.deformPaths.map { it.id }).distinct()) {
				val gv = byG[id]; val g2v = byG2[id]; val mv = byM[id]
				val userOwned = mv != null && mv != gv
				var value = when {
					mv == null -> if (gv == null) g2v else null
					!userOwned -> g2v ?: if (gv != null) null else mv
					else -> mv
				} ?: continue
				val mesh = meshes[value.drawableId]?.mesh
				if (mesh == null) { if (userOwned) issue(IssueKind.DROPPED, "path:$id", "its mesh is gone"); continue }
				if (userOwned) drawables.migrations[value.drawableId]?.let { migration ->
					value = try { DeformPathJournal.rebind(value, migration.oldCanvas, migration.newCanvas) } catch (failure: IllegalArgumentException) {
						issue(IssueKind.DROPPED, "path:$id", "it could not be placed on the new mesh"); continue
					}
				}
				if (value.points.any { maxOf(it.a, it.b, it.c) >= mesh.vertexCount }) { if (userOwned) issue(IssueKind.DROPPED, "path:$id", "its mesh changed"); continue }
				result += value
			}
			return result
		}

		fun mergeGlues(model: PuppetModel, drawables: MergedDrawables): List<Glue> {
			fun key(glue: Glue) = glue.id ?: "${glue.meshA.raw}|${glue.meshB.raw}"
			val irByKey = { ir: Ir -> ir.glues.associateBy { it.id ?: "${it.meshA}|${it.meshB}" } }
			val irByG = irByKey(irG); val irByM = irByKey(irM)
			val byG = g.glues.associateBy(::key); val byG2 = g2.glues.associateBy(::key); val byM = m.glues.associateBy(::key)
			val counts = model.drawables.associate { it.id to (it.mesh?.vertexCount ?: -1) }
			val result = ArrayList<Glue>()
			for (k in (m.glues.map(::key) + g2.glues.map(::key)).distinct()) {
				val gv = byG[k]; val g2v = byG2[k]; val mv = byM[k]
				val userOwned = mv != null && irByM[k] != irByG[k]
				var value = when {
					mv == null -> if (gv == null) g2v else null
					!userOwned -> g2v ?: if (gv != null) null else mv
					else -> mv
				} ?: continue
				if (userOwned) {
					fun remap(mesh: DrawableId, index: Int) = drawables.migrations[mesh]?.glueMap?.getOrNull(index) ?: index
					value = value.copy(pairs = value.pairs.map { GluePair(remap(value.meshA, it.indexA), remap(value.meshB, it.indexB), it.weightA, it.weightB) })
				}
				val a = counts[value.meshA] ?: -1; val b = counts[value.meshB] ?: -1
				if (a < 0 || b < 0 || value.pairs.any { it.indexA !in 0 until a || it.indexB !in 0 until b }) {
					if (userOwned) issue(IssueKind.DROPPED, "glue:$k", "its meshes changed"); continue
				}
				result += value
			}
			return result
		}

		// ---- Part tree and masks

		/** The part tree: G''s when the user left G's, else M's with G''s new parts; every present object exactly once. */
		fun withTree(model: PuppetModel): PuppetModel {
			val userTree = irM.parts != irG.parts || irM.rootChildren != irG.rootChildren
			val (source, other) = if (userTree) m to g2 else g2 to m
			val known = source.parts.mapTo(HashSet()) { it.id }
			val gParts = g.parts.mapTo(HashSet()) { it.id }
			// Parts only the other side has that are new there: G''s new parts (user tree), or the user's own (generated tree).
			val added = other.parts.filter { it.id !in known && (if (userTree) it.id !in gParts else it.id !in gParts) }
			var parts = source.parts + added
			var roots = source.rootChildren
			fun containerOf(tree: PuppetModel, child: OrgChild): PartId? = tree.parts.firstOrNull { child in it.children }?.id
			for (part in added) {
				val ref = OrgChild.Part(part.id)
				val parent = containerOf(other, ref)?.takeIf { id -> parts.any { it.id == id } }
				if (parent == null) roots = roots + ref else parts = parts.map { if (it.id == parent) it.copy(children = it.children + ref) else it }
			}
			val drawableIds = model.drawables.mapTo(HashSet()) { it.id }
			val partIds = parts.mapTo(HashSet()) { it.id }
			val placed = HashSet<OrgChild>()
			fun clean(children: List<OrgChild>) = children.filter { child ->
				when (child) { is OrgChild.Drawable -> child.id in drawableIds; is OrgChild.Part -> child.id in partIds } && placed.add(child)
			}
			roots = clean(roots)
			parts = parts.map { it.copy(children = clean(it.children)) }
			// Drawables the tree misses: where the other side (else G) had them, else the root.
			for (drawable in model.drawables) {
				val ref = OrgChild.Drawable(drawable.id)
				if (ref in placed) continue
				val parent = (containerOf(other, ref) ?: containerOf(g, ref))?.takeIf { it in partIds }
				if (parent == null) roots = roots + ref else parts = parts.map { if (it.id == parent) it.copy(children = it.children + ref) else it }
				placed += ref
			}
			return model.copy(parts = parts, rootChildren = roots,
				rootPartId = (if (userTree) m.rootPartId else g2.rootPartId)?.takeIf { it in partIds })
		}

		fun withMasks(model: PuppetModel): PuppetModel {
			val ids = model.drawables.mapTo(HashSet()) { it.id }
			return model.copy(drawables = model.drawables.map { drawable ->
				if (drawable.maskedBy.all { it in ids }) drawable else drawable.copy(maskedBy = drawable.maskedBy.filter { it in ids })
			})
		}
	}
}
