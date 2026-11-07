package io.github.psd2live.core.sim

import io.github.psd2live.format.model.ColliderIR
import io.github.psd2live.format.model.Floats
import io.github.psd2live.format.model.Ints
import io.github.psd2live.format.model.SimBends
import io.github.psd2live.format.model.SimLongRange
import io.github.psd2live.format.model.SimParticles
import io.github.psd2live.format.model.SimStatic
import io.github.psd2live.format.model.SimStretch
import io.github.psd2live.format.model.SimTarget
import io.github.psd2live.format.model.SimTriangles
import io.github.psd2live.format.model.SimWelds
import io.github.psd2live.format.model.SimulationIR
import org.umamo.runtime.model.PuppetModel

/**
 * The baked simulations as the runtime's advanced mode plays them live: each one's scene exactly as the bake
 * built and calibrated it, with what its bake put into the rig (mode parameters, pendulums, static corrections)
 * so the runtime can take that back out while it simulates.
 *
 * Built from the rig the export compiles, whose bakes are written in: at the default pose, where scenes are built
 * and calibrated, the mode parameters rest at zero and the static corrections at their rest keys, so the scene is
 * the one the bake had.
 */
internal object SimExport {
	/** Steps per second the bake fits at, and so the rate the runtime steps at. */
	private const val FPS = 60f

	class Exported(val simulations: List<SimulationIR>, val colliders: List<ColliderIR>)

	fun export(model: PuppetModel, sims: List<RigSimEdit>, parameters: Set<String>, physicsGroups: Set<String>): Exported {
		val simulations = ArrayList<SimulationIR>()
		val colliders = ArrayList<ColliderIR>()
		var failure: IllegalStateException? = null
		for (edit in sims) {
			val bake = edit.bake?.takeIf { edit.enabled } ?: continue
			val scene = try {
				SimScene.build(model, edit).also { it.calibrate(model) }
			} catch (cancelled: java.util.concurrent.CancellationException) {
				throw cancelled
			} catch (error: Exception) {
				// Report every simulation that cannot be prepared, not only the first; the caller still fails.
				val reported = IllegalStateException("Cannot prepare runtime simulation '${edit.id}': ${error.message}", error)
				if (failure == null) failure = reported else failure.addSuppressed(reported)
				continue
			}
			val ids = edit.colliders.mapIndexed { k, c ->
				val id = "${edit.id}#$k"
				colliders += ColliderIR(id, capsule = c.b != c.a, mesh = c.mesh, vertexA = c.a, vertexB = c.b, radiusA = c.radius, radiusB = c.radiusB, friction = c.friction)
				id
			}
			simulations += scene(edit, scene, bake, parameters, physicsGroups, ids)
		}
		failure?.let { throw it }
		return Exported(simulations, colliders)
	}

	private fun scene(edit: RigSimEdit, scene: SimScene, bake: SimBakeResult, parameters: Set<String>, physicsGroups: Set<String>,
					  colliders: List<String>): SimulationIR {
		val solver = scene.solver
		val state = scene.state
		val n = state.count
		fun floats(values: FloatArray) = Floats.wrap(values.copyOf())
		val anchors = (0 until n).map(scene::anchorOf)
		val statics = bake.statics.filter { it.parameter in parameters }.map { axis ->
			SimStatic(axis.parameter, Floats.wrap(axis.keys.copyOf()), axis.offsets.mapValues { (_, per) -> per.map { Floats.wrap(it.copyOf()) } })
		}
		return SimulationIR(
			id = edit.id, fps = FPS, substeps = solver.settings.substeps,
			gravityX = solver.settings.gravityX, gravityY = solver.settings.gravityY,
			windX = solver.settings.windX, windY = solver.settings.windY, pinCompliance = solver.settings.pinCompliance,
			targets = scene.offsets.keys.map { SimTarget(it.raw, scene.vertexCounts.getValue(it)) },
			particles = SimParticles(floats(state.invMass), floats(state.damping), floats(state.windFactor), floats(solver.pinWeight),
				floats(solver.goalCompliance), floats(state.goalOffsetX), floats(state.goalOffsetY),
				anchors.map { it?.first?.raw ?: "" }, Ints.wrap(IntArray(n) { anchors[it]?.second ?: 0 })),
			stretch = solver.stretch.let { SimStretch(Ints.wrap(it.a.copyOf()), Ints.wrap(it.b.copyOf()), floats(it.rest), floats(it.compliance), floats(it.compressionCompliance)) },
			triangles = solver.triangles.let { SimTriangles(Ints.wrap(it.a.copyOf()), Ints.wrap(it.b.copyOf()), Ints.wrap(it.c.copyOf()), floats(it.areaCompliance)) },
			bends = solver.bends.let { SimBends(Ints.wrap(it.t1.copyOf()), Ints.wrap(it.t2.copyOf()), floats(it.compliance)) },
			welds = solver.welds.let { SimWelds(Ints.wrap(it.a.copyOf()), Ints.wrap(it.b.copyOf()), floats(it.weightA), floats(it.weightB), floats(it.compliance)) },
			longRange = solver.longRange.let { SimLongRange(Ints.wrap(it.particle.copyOf()), Ints.wrap(it.root.copyOf()), floats(it.maxDistance)) },
			parameters = edit.outputParameters.filter { it in parameters },
			physicsGroups = bake.pendulums.map { it.id }.filter { it in physicsGroups },
			statics = statics,
			colliders = colliders,
		)
	}
}
