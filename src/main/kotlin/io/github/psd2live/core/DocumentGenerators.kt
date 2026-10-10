package io.github.psd2live.core

import io.github.psd2live.core.sim.SimGenerator
import io.github.psd2live.format.compile.document.GeneratorGraph
import io.github.psd2live.format.compile.document.GeneratorNode
import org.umamo.runtime.model.PuppetModel

/**
 * The generators of a document as a dependency graph: what each reads and owns, by stable id.
 *
 * The rig builder makes the base rig from the source art and settings in stages - each layer's mesh footprint,
 * the scaffold (anchors, face rig, frames and deformers), each layer's mesh and keyforms, then assembly and the
 * atlas binding - whose outputs [RigStageCache] keeps under keys of only what each reads; the skeleton stage bakes the authored
 * skeleton into it; the journal replays the user's edits; each swing and each baked simulation then adds the
 * keyforms of its own parameters (and the skeleton skins the meshes the journal placed under deformers of its own,
 * [SKIN]); generated overrides merge the user's edits of those keyforms; physics and
 * the generated motions read the finished rig. Replay runs the swings and simulations in the graph's order,
 * and an edit of a keyform is recorded as an override of the generator that owns it.
 *
 * Object ids: `document:<part>` for document state, `rig:<stage>` for whole-rig stages, `parameter:<id>` for
 * a generated parameter, `keyform:<kind>:<target>@<parameter>` for the keyforms a generator adds on
 * [kind] (`warp` or `mesh`) [target] along [parameter], `physics` and `motions` for the compiled groups and clips.
 */
internal object DocumentGenerators {
	const val RIG = "rig"
	const val RIG_FOOTPRINTS = "rig.footprints"
	const val RIG_SCAFFOLD = "rig.scaffold"
	const val RIG_MESHES = "rig.meshes"
	private const val MESH = "mesh:"

	/** The mesh stage of layer [layerId]. */
	fun meshId(layerId: String) = MESH + layerId
	const val SKELETON = "skeleton"

	/**
	 * The skin of the meshes the journal places under deformers of their own ([SkeletonCanvasSkin]): it runs after the
	 * journal and owns their keyforms on the bone and rig pose parameters.
	 */
	const val SKIN = "skeleton.skin"

	/** The pinned inputs of version 2 `art_primitive` records ([ArtPrimitiveV2]): their parts' meshes, layers and roles. */
	const val PRIMITIVES = "document:primitives"
	const val JOURNAL = "journal"
	const val OVERRIDES = "overrides"
	const val PHYSICS = "physics"
	const val MOTIONS = "motions"
	private const val SWING = "swing:"
	private const val SIMULATION = "simulation:"

	fun keyform(kind: String, target: String, parameter: String) = "keyform:$kind:$target@$parameter"
	fun swingId(swing: RigSwingEdit) = SWING + swing.id
	fun simulationId(sim: io.github.psd2live.core.sim.RigSimEdit) = SIMULATION + sim.id

	/**
	 * The graph of [overlay]'s generators. A generated object only one generator may own; should a document
	 * give two the same parameter, the earlier keeps it and the later one's claim is dropped, so replay still
	 * runs (the generators report the clash themselves).
	 *
	 * The rig generator's stages come first: footprints (every layer's pixels and mesh settings), the scaffold
	 * (layer roles and footprints, settings, the skeleton), the meshes - one node per layer of [layers] (its
	 * pixels, role and mesh override, and the scaffold), or one `rig.meshes` node for all when none are named -
	 * and `rig`, the assembly and atlas binding that owns the base rig.
	 *
	 * With version 2 `art_primitive` records the footprints, scaffold, meshes and skeleton also read their pinned
	 * inputs ([PRIMITIVES]), and [primitives] - the side channel of the base rig ([PrimitiveSkins.ownership]) -
	 * names the generated keyforms of each part, `keyform:mesh:<part>@<parameter>`: the part layer's mesh stage
	 * (`rig.meshes` when no layers are named, `rig` when its layer is not among them) owns its feature axes and
	 * the skeleton its bone axes, so an edit of one is recorded as an override.
	 */
	fun graph(overlay: RigEditOverlay, layers: List<String> = emptyList(), primitives: PrimitiveSkins = PrimitiveSkins.None): GeneratorGraph {
		val owned = HashSet<String>()
		fun claim(ids: Collection<String>) = ids.filterTo(LinkedHashSet()) { owned.add(it) }
		val pinned = if (overlay.authoringJournal.any(ArtPrimitiveV2::isV2)) setOf(PRIMITIVES) else emptySet()
		val meshNodes = layers.mapTo(HashSet(), ::meshId)
		val partKeyforms = LinkedHashMap<String, MutableList<String>>()
		for ((part, axes) in primitives.ownership) for ((parameter, node) in axes) {
			val owner = when {
				node == SKELETON -> SKELETON
				layers.isEmpty() -> RIG_MESHES
				node in meshNodes -> node
				else -> RIG
			}
			partKeyforms.getOrPut(owner) { ArrayList() } += keyform("mesh", part.raw, parameter.raw)
		}
		val nodes = ArrayList<GeneratorNode>()
		nodes += GeneratorNode(RIG_FOOTPRINTS, setOf("document:source", "document:settings", "document:meshes") + pinned, claim(listOf("rig:footprints")))
		nodes += GeneratorNode(RIG_SCAFFOLD, setOf("rig:footprints", "document:layers", "document:settings", "document:skeleton") + pinned,
			claim(listOf("rig:scaffold")))
		val meshes = if (layers.isEmpty()) {
			nodes += GeneratorNode(RIG_MESHES, setOf("rig:scaffold", "document:source", "document:layers", "document:meshes", "document:settings") + pinned,
				claim(listOf("rig:meshes") + partKeyforms[RIG_MESHES].orEmpty()))
			listOf("rig:meshes")
		} else layers.map { layer ->
			nodes += GeneratorNode(meshId(layer), setOf("rig:scaffold", "document:source:$layer", "document:layers:$layer",
				"document:meshes:$layer", "document:settings") + pinned, claim(listOf("rig:mesh:$layer") + partKeyforms[meshId(layer)].orEmpty()))
			"rig:mesh:$layer"
		}
		nodes += GeneratorNode(RIG, (meshes + listOf("rig:scaffold", "document:source", "document:settings", "document:layers")).toSet(),
			claim(listOf("rig:base") + partKeyforms[RIG].orEmpty()))
		// The skeleton skins the parts of v2 records with the base: it reads their pinned inputs too.
		nodes += GeneratorNode(SKELETON, setOf("rig:base", "document:skeleton") + pinned, claim(listOf("rig:skeleton") + partKeyforms[SKELETON].orEmpty()))
		nodes += GeneratorNode(JOURNAL, setOf("rig:skeleton", "document:journal"), claim(listOf("rig:authored")))
		val keyforms = LinkedHashSet<String>()
		val parameters = LinkedHashSet<String>()
		// Present only when the journal places a bound mesh, so every other document keeps its graph.
		val placed = SkeletonCanvasSkin.placed(overlay)
		if (placed.isNotEmpty()) {
			val writes = claim(SkeletonCanvasSkin.claims(overlay.skeleton!!, placed).flatMap { (mesh, ids) -> ids.map { keyform("mesh", mesh, it) } })
			nodes += GeneratorNode(SKIN, setOf("rig:authored", "document:skeleton"), writes)
			keyforms += writes
		}
		for (swing in overlay.swingEdits) {
			val writes = if (swing.baked) emptySet() else claim(swing.parameterIds.map { "parameter:$it" } +
				swing.targets.flatMap { target -> swing.parameterIds.map { keyform("warp", target, it) } })
			nodes += GeneratorNode(swingId(swing), setOf("rig:authored", "document:${swingId(swing)}"), writes)
			keyforms += writes.filter { it.startsWith("keyform:") }; parameters += writes.filter { it.startsWith("parameter:") }
		}
		for (sim in overlay.simEdits) {
			val bake = sim.bake?.takeIf { sim.enabled }
			val outputs = sim.outputParameters
			val writes = if (bake == null) emptySet() else claim(outputs.map { "parameter:$it" } +
				bake.vertexCounts.keys.flatMap { mesh -> outputs.map { keyform("mesh", mesh, it) } })
			nodes += GeneratorNode(simulationId(sim), setOf("rig:authored", "document:${simulationId(sim)}"), writes)
			keyforms += writes.filter { it.startsWith("keyform:") }; parameters += writes.filter { it.startsWith("parameter:") }
		}
		nodes += GeneratorNode(OVERRIDES, keyforms + "rig:authored" + "document:overrides" + "document:generators", claim(listOf("rig:final")))
		nodes += GeneratorNode(PHYSICS, parameters + "rig:final" + "document:physics" + "document:skeleton", claim(listOf("physics")))
		nodes += GeneratorNode(MOTIONS, setOf("rig:final", "physics", "document:motions", "document:skeleton"), claim(listOf("motions")))
		return GeneratorGraph(nodes)
	}

	/** The generator that owns [objectId] in [graph], if any. */
	fun owner(graph: GeneratorGraph, objectId: String): GeneratorNode? = graph.order.firstOrNull { objectId in it.writes }

	/**
	 * Runs the generators that write onto the replayed rig - the skin of journal-placed meshes, swings, then
	 * simulations - in the graph's order. Each is a pure function of its settings and the rig before it.
	 */
	fun generate(model: PuppetModel, overlay: RigEditOverlay, graph: GeneratorGraph = graph(overlay)): PuppetModel {
		val swings = overlay.swingEdits.associateBy(::swingId)
		val sims = overlay.simEdits.associateBy(::simulationId)
		var current = model
		for (node in graph.order) {
			if (node.id == SKIN) {
				val spec = overlay.skeleton ?: continue
				val placed = SkeletonCanvasSkin.placed(overlay)
				current = GeneratorReuse.run(node.id, spec to placed, current, SkeletonCanvasSkin.reads(current, placed)) {
					RigBuildProfile.stage("skeleton: canvas skin") { SkeletonCanvasSkin.apply(it, spec, placed) }
				}
			}
			swings[node.id]?.let { swing ->
				current = GeneratorReuse.run(node.id, swing, current, swingReads(current, swing)) { SwingGenerator.apply(it, listOf(swing)) }
			}
			sims[node.id]?.let { sim ->
				current = GeneratorReuse.run(node.id, sim, current, simulationReads(current, sim)) { SimGenerator.apply(it, listOf(sim)) }
			}
		}
		return current
	}

	/**
	 * What a swing reads from the rig: each target Warp with all its ancestors (the parent world the swing
	 * measures in), the parameters and their tree (it creates its own and reads defaults) and the world origin
	 * (the automatic fulcrum). It writes its parameters, the tree and its target Warps.
	 */
	fun swingReads(model: PuppetModel, swing: RigSwingEdit): GeneratorReuse.Reads {
		if (swing.baked) return GeneratorReuse.Reads.NONE
		val byId = HashMap<String, org.umamo.runtime.model.Deformer>().apply { model.deformers.forEach { putIfAbsent(it.id.raw, it) } }
		val chain = LinkedHashMap<String, Any?>()
		for (target in swing.targets) {
			var id: String? = target
			while (id != null && id !in chain) { val deformer = byId[id]; chain[id] = deformer; id = deformer?.parent?.raw }
		}
		return GeneratorReuse.Reads(model.parameters, model.parameterTree, listOf(model.worldOriginX), chain.keys, chain.values.toList(), emptySet(), emptyList())
	}

	/**
	 * What a baked simulation reads from the rig: its target and baked meshes (geometry, blend shapes and
	 * channels), the parameters and their tree and the runtime target (whether blend shapes are available).
	 * It writes its parameters, the tree and those meshes.
	 */
	fun simulationReads(model: PuppetModel, sim: io.github.psd2live.core.sim.RigSimEdit): GeneratorReuse.Reads {
		val bake = sim.bake?.takeIf { sim.enabled } ?: return GeneratorReuse.Reads.NONE
		val ids = (sim.targets + bake.vertexCounts.keys).toSortedSet()
		val byId = HashMap<String, org.umamo.runtime.model.Drawable>().apply { model.drawables.forEach { putIfAbsent(it.id.raw, it) } }
		return GeneratorReuse.Reads(model.parameters, model.parameterTree, listOf(model.runtimeTarget), emptySet(), emptyList(), ids, ids.map { byId[it] })
	}

	/** The document parts that differ between [before] and [after], as the graph's `document:` ids. */
	fun changed(before: RigEditOverlay, after: RigEditOverlay): Set<String> = buildSet {
		if (before.skeleton != after.skeleton) add("document:skeleton")
		// Adding or removing a swing or simulation changes which keyforms are generated at all.
		if (before.swingEdits.map(::swingId) + before.simEdits.map(::simulationId) != after.swingEdits.map(::swingId) + after.simEdits.map(::simulationId))
			add("document:generators")
		val journal = { overlay: RigEditOverlay -> overlay.authoringJournal.filterNot { GeneratedOverrides.isOverride(it) } }
		if (journal(before) != journal(after) || before.parameterEdits != after.parameterEdits || before.structureEdits != after.structureEdits ||
			before.keyformSetEdits != after.keyformSetEdits || before.keyformCopyEdits != after.keyformCopyEdits ||
			before.keyformDeleteEdits != after.keyformDeleteEdits || before.warpEdits != after.warpEdits ||
			before.deletedParameterIds != after.deletedParameterIds) add("document:journal")
		if (before.authoringJournal.filter(ArtPrimitiveV2::isV2) != after.authoringJournal.filter(ArtPrimitiveV2::isV2)) add(PRIMITIVES)
		if (before.authoringJournal.filter(GeneratedOverrides::isOverride) != after.authoringJournal.filter(GeneratedOverrides::isOverride))
			add("document:overrides")
		val swingsBefore = before.swingEdits.associateBy(::swingId); val swingsAfter = after.swingEdits.associateBy(::swingId)
		for (id in swingsBefore.keys + swingsAfter.keys) if (swingsBefore[id] != swingsAfter[id]) add("document:$id")
		val simsBefore = before.simEdits.associateBy(::simulationId); val simsAfter = after.simEdits.associateBy(::simulationId)
		for (id in simsBefore.keys + simsAfter.keys) if (simsBefore[id]?.toJson() != simsAfter[id]?.toJson()) add("document:$id")
		if (before.physicsEdits != after.physicsEdits || before.disabledPhysicsIds != after.disabledPhysicsIds ||
			before.physicsOrder != after.physicsOrder || before.physicsFps != after.physicsFps) add("document:physics")
		if (before.motionClips != after.motionClips || before.motionPresets != after.motionPresets) add("document:motions")
	}

	/** The generators [after] must run again over [before]: those reading a changed part, and everything downstream. */
	fun stale(before: RigEditOverlay, after: RigEditOverlay): List<String> {
		val changed = changed(before, after)
		// What a changed or removed generator wrote before changes too.
		val dropped = graph(before).order.filter { "document:${it.id}" in changed }.flatMap { it.writes }
		return graph(after).stale(changed + dropped).map { it.id }
	}
}

/**
 * Reuse of a swing's or simulation's output while what it reads is unchanged.
 *
 * A generator declares its reads ([Reads]: the parameters and their tree, a few model scalars and the listed
 * deformers or drawables). After it runs, its output is recorded as a patch - the new parameter list and tree
 * and the replaced objects - if it changed nothing else and only objects it reads; otherwise it is not cached.
 * A later run whose reads equal a recorded one (each object the same instance, else equal) applies the patch
 * to its own input instead: objects outside the reads keep their current values. Comparing references first
 * costs next to nothing; a content hash of the reads would cost more than the generators themselves.
 */
internal object GeneratorReuse {
	private const val ENTRIES = 16

	/** Off: every generator runs (`-Dpsd2live.replayCheckpoints=false` turns this and the replay checkpoints off). */
	@Volatile var enabled: Boolean = System.getProperty("psd2live.replayCheckpoints") != "false"

	private val hitCount = java.util.concurrent.atomic.AtomicLong()
	private val missCount = java.util.concurrent.atomic.AtomicLong()
	val hits: Long get() = hitCount.get()
	val misses: Long get() = missCount.get()

	class Reads(
		val parameters: List<org.umamo.runtime.model.Parameter>,
		val tree: List<org.umamo.runtime.model.ParameterNode>,
		val scalars: List<Any?>,
		val deformerIds: Set<String>,
		val deformers: List<Any?>,
		val drawableIds: Set<String>,
		val drawables: List<Any?>,
	) {
		fun sameAs(other: Reads): Boolean = same(parameters, other.parameters) && same(tree, other.tree) && same(scalars, other.scalars) &&
			deformerIds == other.deformerIds && same(deformers, other.deformers) && drawableIds == other.drawableIds && same(drawables, other.drawables)

		companion object {
			/** The generator writes nothing (a baked swing, a simulation without an enabled bake). */
			val NONE = Reads(emptyList(), emptyList(), emptyList(), emptySet(), emptyList(), emptySet(), emptyList())
		}
	}

	private fun same(a: List<Any?>, b: List<Any?>): Boolean {
		if (a === b) return true
		if (a.size != b.size) return false
		for (i in a.indices) { val x = a[i]; val y = b[i]; if (x !== y && x != y) return false }
		return true
	}

	private class Entry(
		val node: String,
		val settings: Any,
		val reads: Reads,
		val parameters: List<org.umamo.runtime.model.Parameter>,
		val tree: List<org.umamo.runtime.model.ParameterNode>,
		val deformers: Map<String, org.umamo.runtime.model.Deformer>,
		val drawables: Map<String, org.umamo.runtime.model.Drawable>,
	) {
		fun matches(node: String, settings: Any, reads: Reads) =
			this.node == node && (this.settings === settings || this.settings == settings) && this.reads.sameAs(reads)
	}

	private val entries = ArrayList<Entry>()

	fun clear() = synchronized(entries) { entries.clear() }

	fun run(node: String, settings: Any, model: PuppetModel, reads: Reads, generate: (PuppetModel) -> PuppetModel): PuppetModel {
		if (reads === Reads.NONE || !enabled) return generate(model)
		val hit = synchronized(entries) {
			entries.firstOrNull { it.matches(node, settings, reads) }?.also { entries.remove(it); entries.add(0, it) }
		}
		if (hit != null) {
			hitCount.incrementAndGet()
			return model.copy(parameters = hit.parameters, parameterTree = hit.tree,
				deformers = if (hit.deformers.isEmpty()) model.deformers else model.deformers.map { hit.deformers[it.id.raw] ?: it },
				drawables = if (hit.drawables.isEmpty()) model.drawables else model.drawables.map { hit.drawables[it.id.raw] ?: it })
		}
		missCount.incrementAndGet()
		val output = generate(model)
		record(node, settings, model, reads, output)?.let { entry ->
			synchronized(entries) {
				entries.removeAll { it.matches(node, settings, reads) }
				entries.add(0, entry)
				while (entries.size > ENTRIES) entries.removeAt(entries.size - 1)
			}
		}
		return output
	}

	/** [output] as a patch of [input], or null when it changed anything beyond what a patch carries. */
	private fun record(node: String, settings: Any, input: PuppetModel, reads: Reads, output: PuppetModel): Entry? {
		fun <T : Any> changed(before: List<T>, after: List<T>, id: (T) -> String, allowed: Set<String>): Map<String, T>? {
			if (before === after) return emptyMap()
			if (before.size != after.size) return null
			val out = HashMap<String, T>()
			for (i in before.indices) {
				val a = before[i]; val b = after[i]
				if (a === b) continue
				val key = id(a)
				if (id(b) != key || key !in allowed || key in out) return null
				out[key] = b
			}
			// A duplicated id elsewhere in the list would take the patch too.
			if (out.isNotEmpty() && before.count { id(it) in out } != out.size) return null
			return out
		}
		val deformers = changed(input.deformers, output.deformers, { it.id.raw }, reads.deformerIds) ?: return null
		val drawables = changed(input.drawables, output.drawables, { it.id.raw }, reads.drawableIds) ?: return null
		// Every other field must be untouched.
		val rest = output.copy(parameters = input.parameters, parameterTree = input.parameterTree,
			deformers = input.deformers, drawables = input.drawables)
		if (rest != input) return null
		return Entry(node, settings, reads, output.parameters, output.parameterTree, deformers, drawables)
	}
}
