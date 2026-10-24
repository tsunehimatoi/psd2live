package io.github.psd2live.core.modifier

import io.github.psd2live.core.DocumentGenerators
import io.github.psd2live.core.RigEditOverlay
import io.github.psd2live.core.RigSwingEdit
import io.github.psd2live.core.interior.NativeInteriorEffect
import io.github.psd2live.core.sim.RigSimEdit

/**
 * The document's one stack: the swings, simulations and interior effects with the modifiers, in the order they run
 * after the skeleton's skin. Each keeps its own settings where it always has; the order is [RigEditOverlay.stackOrder],
 * by entry ID - a modifier's own, a generator's graph node (`swing:<id>`, `simulation:<id>`, `interiorEffect:<id>`).
 *
 * A document that never ordered its stack runs as documents always have: every swing, then every simulation, then
 * every interior effect, then the modifiers. Once ordered, an entry the order does not name yet runs where it would
 * have: a new generator before the named entries, a new modifier after them.
 */
object ModifierStack {
	sealed interface Entry {
		/** The entry's ID in the order and the graph. */
		val id: String
		val enabled: Boolean

		class Modifier(val modifier: RigModifier) : Entry {
			override val id get() = modifier.id
			override val enabled get() = modifier.enabled
		}
		class Swing(val swing: RigSwingEdit) : Entry {
			override val id get() = DocumentGenerators.swingId(swing)
			override val enabled get() = !swing.baked
		}
		class Simulation(val simulation: RigSimEdit) : Entry {
			override val id get() = DocumentGenerators.simulationId(simulation)
			override val enabled get() = simulation.enabled && simulation.bake != null
		}
		class Interior(val effect: NativeInteriorEffect) : Entry {
			override val id get() = interiorId(effect)
			override val enabled get() = effect.enabled
		}
	}

	fun interiorId(effect: NativeInteriorEffect) = "interiorEffect:" + effect.id

	/** The stack of [overlay] in the order it runs. */
	fun entries(overlay: RigEditOverlay): List<Entry> {
		val generators: List<Entry> = overlay.swingEdits.map { Entry.Swing(it) } + overlay.simEdits.map { Entry.Simulation(it) } +
			overlay.interiorEffects.map { Entry.Interior(it) }
		val modifiers = overlay.modifiers.map { Entry.Modifier(it) }
		if (overlay.stackOrder.isEmpty()) return generators + modifiers
		val byId = (generators + modifiers).associateBy { it.id }
		val named = overlay.stackOrder.mapNotNull { byId[it] }.distinctBy { it.id }
		val listed = named.mapTo(HashSet()) { it.id }
		return generators.filter { it.id !in listed } + named + modifiers.filter { it.id !in listed }
	}

	/**
	 * [overlay] with its stack in the order of [ids], every entry named: the order the stack runs in, and the modifiers
	 * kept in it among themselves.
	 */
	fun ordered(overlay: RigEditOverlay, ids: List<String>): RigEditOverlay {
		val entries = entries(overlay)
		require(ids.toSet() == entries.mapTo(HashSet()) { it.id } && ids.distinct().size == ids.size) { "Name every stack entry once" }
		val modifiers = overlay.modifiers.associateBy { it.id }
		return overlay.copy(stackOrder = ids, modifiers = ids.mapNotNull { modifiers[it] })
	}

	/**
	 * [overlay] as a bake of the entry [id] reads it: what runs before it stays, the modifiers after it go, the
	 * simulations after it lose their bakes and the interior effects after it are off - those write their keyforms
	 * over its result, and a bake that read them would count them twice.
	 */
	fun before(overlay: RigEditOverlay, id: String): RigEditOverlay {
		val entries = entries(overlay)
		val index = entries.indexOfFirst { it.id == id }
		if (index < 0) return overlay
		val later = entries.drop(index + 1).mapTo(HashSet()) { it.id }
		return overlay.copy(
			modifiers = overlay.modifiers.filterNot { it.id in later },
			simEdits = overlay.simEdits.map { if (DocumentGenerators.simulationId(it) in later) it.copy(bake = null) else it },
			interiorEffects = overlay.interiorEffects.map { if (interiorId(it) in later) it.enabled(false) else it },
		)
	}

	/**
	 * What the stack before simulation [id] does to its meshes, as text for its bake's fingerprint: the modifiers and
	 * interior effects there that act on them. Empty when nothing does, as in every document from before the stack.
	 */
	fun precedingOn(overlay: RigEditOverlay, id: String, meshes: Set<String>, resolve: (RigModifier) -> List<String>): String {
		val entries = entries(overlay)
		val index = entries.indexOfFirst { it.id == id }
		if (index <= 0) return ""
		return entries.take(index).filter { it.enabled }.mapNotNull { entry ->
			when (entry) {
				is Entry.Modifier -> entry.modifier.takeIf { m -> resolve(m).any { it in meshes } }?.toJson()?.toString()
				is Entry.Interior -> entry.effect.takeIf { it.target in meshes }?.toJson()?.toString()
				else -> null
			}
		}.joinToString("|")
	}
}
