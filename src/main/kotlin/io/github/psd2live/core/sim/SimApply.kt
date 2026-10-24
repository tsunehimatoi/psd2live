package io.github.psd2live.core.sim

import io.github.psd2live.core.GeneratedOverrides
import io.github.psd2live.core.RigEditOverlay
import io.github.psd2live.core.DocumentGenerators
import kotlinx.serialization.json.*
import org.umamo.runtime.model.*

/**
 * Applies a baked simulation, as a modifier is applied: its keyforms become ordinary journal entries - the mode
 * parameters made, their keys on each baked mesh and every cell's shape, with the user's edits of its cells folded in
 * - and its pendulums ordinary physics groups, so the rig looks and moves as before and the simulation is gone. A
 * simulation that writes its modes as blend shapes is refused: the journal keys keyform axes only.
 */
internal object SimApply {
	const val OP = "simulation_apply"

	fun apply(overlay: RigEditOverlay, authored: PuppetModel, id: String): RigEditOverlay {
		val sim = requireNotNull(overlay.simEdits.firstOrNull { it.id == id }) { "Simulation not found: $id" }
		val bake = requireNotNull(sim.bake?.takeIf { sim.enabled }) { "Simulation $id has no enabled bake to apply" }
		require(!SimGenerator.usesBlendShapes(authored, sim)) { "Simulation $id writes its modes as blend shapes, which cannot be applied; set its modes as keyform axes first" }
		// The simulation alone on the rig the journal makes: what the entries replay before every generator.
		val written = SimGenerator.apply(authored, listOf(sim))
		val known = authored.parameters.mapTo(HashSet()) { it.id.raw }
		val commands = ArrayList<JsonObject>()
		val created = written.parameters.filter { it.id.raw !in known }
		if (created.isNotEmpty()) commands += buildJsonObject {
			put("op", "structure")
			putJsonArray("edits") {
				created.forEach { p ->
					addJsonObject {
						put("action", "create"); put("kind", "parameter"); put("id", p.id.raw); put("name", p.name)
						put("min", p.min); put("max", p.max); put("default", p.default)
						put("parameter_kind", p.kind.name); put("repeat", p.repeat)
					}
				}
			}
		}
		val node = DocumentGenerators.simulationId(sim)
		for (mesh in bake.vertexCounts.keys) {
			val before = authored.drawables.firstOrNull { it.id.raw == mesh }?.geometryGrid
			val grid = written.drawables.firstOrNull { it.id.raw == mesh }?.geometryGrid ?: continue
			if (grid === before) continue
			val had = before?.axes.orEmpty().map { it.parameterId }.toSet()
			for (axis in grid.axes) {
				val keys = before?.axes?.firstOrNull { it.parameterId == axis.parameterId }?.keys
				if (axis.parameterId in had && keys != null && keys.contentEquals(axis.keys)) continue
				commands += buildJsonObject {
					put("op", "parameter_keys"); put("target", "mesh:$mesh"); put("parameter", axis.parameterId.raw)
					put("action", "add"); put("track", "geometry")
					putJsonArray("values") { axis.keys.forEach { add(it) } }
				}
			}
			fun key(cell: KeyformCell<MeshDeltaForm>) = grid.axes.withIndex().associate { (a, axis) -> axis.parameterId.raw to axis.keys[cell.coordinate[a]] }
			for (cell in grid.cells) {
				// An edit of this cell keeps what the user changed there, on the applied value.
				val override = overlay.authoringJournal.firstOrNull { entry ->
					GeneratedOverrides.isOverride(entry) && entry["generator"]?.jsonPrimitive?.contentOrNull == node &&
						entry["target"]?.jsonPrimitive?.contentOrNull == "mesh:$mesh" &&
						entry["key"]?.jsonObject?.mapValues { it.value.jsonPrimitive.float }?.let { k ->
							key(cell).all { (p, v) -> kotlin.math.abs((k[p] ?: v) - v) <= org.umamo.runtime.eval.EPS_KEY }
						} == true
				}
				val deltas = cell.form.positionDeltas
				val shown = override?.let { o ->
					val base = o.getValue("base").jsonArray.map { it.jsonPrimitive.float }
					val points = o.getValue("points").jsonArray.map { it.jsonPrimitive.float }
					if (base.size == deltas.size && points.size == deltas.size) FloatArray(deltas.size) { deltas[it] + points[it] - base[it] } else null
				} ?: deltas
				commands += buildJsonObject {
					put("op", "set"); put("target", "mesh:$mesh")
					putJsonObject("key") { key(cell).forEach { (p, v) -> put(p, v) } }
					putJsonObject("geometry") { putJsonArray("positionDeltas") { shown.forEach { add(it) } } }
				}
			}
		}
		// The pendulums stay, now the user's; a group the user already overrode is theirs as it is.
		val available = written.parameters.mapTo(HashSet()) { it.id.raw }
		val pendulums = SimGenerator.physicsRules(listOf(sim), available).filter { p -> overlay.physicsEdits.none { it.id == p.id } }
		return overlay.copy(
			simEdits = overlay.simEdits.filterNot { it.id == id },
			physicsEdits = overlay.physicsEdits + pendulums,
			authoringJournal = overlay.authoringJournal.filterNot { entry ->
				GeneratedOverrides.isOverride(entry) && entry["generator"]?.jsonPrimitive?.contentOrNull == node
			} + commands,
		)
	}
}
