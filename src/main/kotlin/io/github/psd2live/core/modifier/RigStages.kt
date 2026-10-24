package io.github.psd2live.core.modifier

import io.github.psd2live.core.DocumentGenerators
import io.github.psd2live.core.RigEditOverlay
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.PuppetModel

/**
 * A mesh's or deformer's stack as Live2D computes it: its own keyforms, then every parent deformer's, innermost first.
 * Each of these is a stage; the order is the hierarchy's, changed by reparenting. Within a stage its keyforms come from
 * contributions - the generators of the base rig, swings, simulations, interior effects, modifiers, the user's own
 * keys - and the entries of the modifier stack ([ModifierStack]) that act on it.
 */
internal object RigStages {
	/** What made a stage, or an axis of its keyforms. */
	data class Owner(val kind: Kind, val id: String) {
		enum class Kind { COMPONENT, SWING, SIMULATION, INTERIOR, MODIFIERS, SKIN, MANUAL }
	}

	/** One parameter axis of a stage's keyforms, and who made it. */
	data class Axis(val parameter: String, val owner: Owner)

	/**
	 * One stage: [key] is the mesh's ID or `warp:<id>` / `rotation:<id>`, [owner] who made the deformer (null for a mesh),
	 * [axes] its keyform axes, [entries] the stack entries acting on it, in the order they run.
	 */
	data class Stage(val key: String, val kind: String, val id: String, val name: String, val owner: Owner?, val axes: List<Axis>,
					 val entries: List<ModifierStack.Entry>) {
		/** Whether anything acts on it beyond its own keys. */
		val contributed: Boolean get() = entries.isNotEmpty() || axes.any { it.owner.kind != Owner.Kind.MANUAL }
	}

	const val ROTATION = "rotation:"

	/** Who made deformer [id]: a swing wrapping its targets, a generator of the base rig, else the user. */
	fun deformerOwner(overlay: RigEditOverlay, id: String): Owner =
		overlay.swingEdits.firstOrNull { !it.baked && id in it.targets }?.let { Owner(Owner.Kind.SWING, it.id) }
			?: Owner(Owner.Kind.MODIFIERS, "").takeIf { id.startsWith(ModifierStage.PREFIX) }
			?: io.github.psd2live.core.RigComponents.ofDeformer(id)?.let { Owner(Owner.Kind.COMPONENT, it.id) }
			?: Owner(Owner.Kind.MANUAL, "")

	/**
	 * The stages of [key] (a mesh's ID, `warp:<id>` or `rotation:<id>`) in [model], its own first and then each parent's.
	 * An axis belongs to the generator the document's graph gives its keyforms ([DocumentGenerators.graph]), else to
	 * the base rig's generator of its parameter, else to the user.
	 */
	fun chain(model: PuppetModel, overlay: RigEditOverlay, key: String): List<Stage> {
		val graph = DocumentGenerators.graph(overlay, model = model)
		val deformers = model.deformers.associateBy { it.id.raw }
		val entries = ModifierStack.entries(overlay)
		fun owner(kind: String, target: String, parameter: String): Owner {
			val node = DocumentGenerators.owner(graph, DocumentGenerators.keyform(kind, target, parameter))?.id
			return when {
				node == null -> io.github.psd2live.core.RigComponents.ofParameter(parameter)?.let { Owner(Owner.Kind.COMPONENT, it.id) } ?: Owner(Owner.Kind.MANUAL, "")
				node == ModifierSampler.NODE -> Owner(Owner.Kind.MODIFIERS, "")
				node == DocumentGenerators.SKIN -> Owner(Owner.Kind.SKIN, "")
				node.startsWith("swing:") -> Owner(Owner.Kind.SWING, node.removePrefix("swing:"))
				node.startsWith("simulation:") -> Owner(Owner.Kind.SIMULATION, node.removePrefix("simulation:"))
				node.startsWith("interiorEffect:") -> Owner(Owner.Kind.INTERIOR, node.removePrefix("interiorEffect:"))
				else -> io.github.psd2live.core.RigComponents.ofParameter(parameter)?.let { Owner(Owner.Kind.COMPONENT, it.id) } ?: Owner(Owner.Kind.MANUAL, "")
			}
		}
		fun acting(stageKey: String, mesh: Boolean) = entries.filter { entry ->
			when (entry) {
				is ModifierStack.Entry.Modifier -> stageKey in entry.modifier.target.keys(model)
				is ModifierStack.Entry.Swing -> !mesh && !entry.swing.baked && stageKey.removePrefix(ModifierTarget.WARP) in entry.swing.targets
				is ModifierStack.Entry.Simulation -> mesh && (stageKey in entry.simulation.targets || entry.simulation.bake?.vertexCounts?.containsKey(stageKey) == true)
				is ModifierStack.Entry.Interior -> mesh && entry.effect.target == stageKey
			}
		}
		fun deformerStage(deformer: Deformer): Stage {
			val warp = deformer is Deformer.Warp
			val kind = if (warp) "warp" else "rotation"
			val stageKey = (if (warp) ModifierTarget.WARP else ROTATION) + deformer.id.raw
			val axes = (if (deformer is Deformer.Warp) deformer.geometryGrid?.axes else (deformer as Deformer.Rotation).geometryGrid?.axes).orEmpty()
			return Stage(stageKey, kind, deformer.id.raw, deformer.name, deformerOwner(overlay, deformer.id.raw),
				axes.map { Axis(it.parameterId.raw, owner(kind, deformer.id.raw, it.parameterId.raw)) }, acting(stageKey, false))
		}
		val stages = ArrayList<Stage>()
		val start: String? = when {
			key.startsWith(ModifierTarget.WARP) -> key.removePrefix(ModifierTarget.WARP)
			key.startsWith(ROTATION) -> key.removePrefix(ROTATION)
			else -> {
				val mesh = model.drawables.firstOrNull { it.id.raw == key } ?: return emptyList()
				stages += Stage(key, "mesh", key, mesh.name, null,
					mesh.geometryGrid?.axes.orEmpty().map { Axis(it.parameterId.raw, owner("mesh", key, it.parameterId.raw)) }, acting(key, true))
				mesh.parentDeformerId?.raw
			}
		}
		val seen = HashSet<String>()
		for (id in generateSequence(start) { deformers[it]?.parent?.raw }) {
			if (!seen.add(id)) break
			stages += deformerStage(deformers[id] ?: break)
		}
		return stages
	}
}
