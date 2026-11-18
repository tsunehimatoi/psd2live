package io.github.psd2live.format.eval

import io.github.psd2live.format.model.*
import io.github.psd2live.targets.runtime.P2lrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PreviewSimulationTest {
	private fun model(): RigIR {
		val empty = Floats.Empty
		val indices = Ints.Empty
		val simulation = SimulationIR(
			id = "cloth", fps = 60f, substeps = 4, gravityX = 0f, gravityY = -980f,
			windX = 0f, windY = 0f, pinCompliance = 0.0001f,
			targets = listOf(SimTarget("cloth", 3)),
			particles = SimParticles(Floats.values(1f, 1f, 1f), Floats.values(1f, 1f, 1f), Floats.values(1f, 1f, 1f),
				Floats.values(1f, 1f, 0f), Floats.values(-1f, -1f, -1f), Floats.values(0f, 0f, 0f), Floats.values(0f, 0f, 0f),
				listOf("", "", ""), Ints.values(0, 0, 0)),
			stretch = SimStretch(indices, indices, empty, empty, empty),
			triangles = SimTriangles(indices, indices, indices, empty),
			bends = SimBends(indices, indices, empty), welds = SimWelds(indices, indices, empty, empty, empty),
			longRange = SimLongRange(indices, indices, empty), parameters = emptyList(), physicsGroups = emptyList(), statics = emptyList(),
		)
		return RigIR(
			Canvas(64f, 64f),
			listOf(Parameter("output", "Output", -30f, 30f, 0f), Parameter("driver", "Driver", -30f, 30f, 0f)),
			meshes = listOf(Mesh("cloth", "Cloth", null, offsets = null,
				geometry = MeshGeometry(Floats.values(0f, 0f, 20f, 0f, 10f, 20f), Floats.values(0f, 0f, 1f, 0f, 0.5f, 1f), Ints.values(0, 1, 2)))),
			physics = Physics(listOf(PhysicsGroup("ordinary", "Ordinary",
				listOf(PhysicsInput("driver", 100f, PhysicsSource.ANGLE, false)),
				listOf(PhysicsOutput("output", 1, 1f, 50f, PhysicsSource.ANGLE, false)),
				listOf(PhysicsSegment(10f, 1f, 1f, 1f)), PhysicsNormalization(-10f, 0f, 10f, -30f, 0f, 30f))), 60f),
			advanced = AdvancedIR(simulations = listOf(simulation)),
		)
	}

	private fun runtime(): P2lRuntime = assertNotNull(P2lRuntime.load(nativeRuntimeLibrary()), "Incompatible runtime ABI")

	@Test fun simulationMovesWhilePausedAndPreservesThePhysicsAlreadyInTheHostPose() {
		val runtime = runtime()
		runtime.load(model()).use { rig ->
			rig.behaviors(0)
			val baked = rig.vertices(0)
			rig.setAdvanced(rig.advancedAvailable)
			rig.values = floatArrayOf(10f, 0f)
			rig.update(1f / 60f) // The workspace composes its ordinary physics once.
			assertEquals(5f, rig.current[0], 0.0001f)
			rig.values = rig.current
			rig.resetSimulation()
			rig.updateSimulation(0f)
			val initial = rig.vertices(0)
			repeat(60) { rig.updateSimulation(1f / 60f) }
			assertEquals(5f, rig.current[0], 0.0001f)
			assertTrue(rig.vertices(0)[5] > initial[5] + 1f, "The paused preview must advance the live simulation")
			rig.setAdvanced(0)
			rig.evaluate()
			assertContentEquals(baked, rig.vertices(0))
		}
	}

	@Test fun instancesKeepIndependentSimulationAndOutliveTheSharedModelHandle() {
		val runtime = runtime()
		val shared = runtime.loadModel(P2lrt.write(model()))
		val first = shared.createRig()
		val second = shared.createRig()
		try {
			first.setAdvanced(first.advancedAvailable)
			second.setAdvanced(second.advancedAvailable)
			shared.close()
			assertFailsWith<IllegalStateException> { shared.createRig() }
			first.updateSimulation(0f)
			second.updateSimulation(0f)
			val rest = second.vertices(0)
			repeat(60) { first.updateSimulation(1f / 60f) }
			assertTrue(first.vertices(0)[5] > rest[5] + 1f)
			assertContentEquals(rest, second.vertices(0))
		} finally {
			shared.close()
			first.close()
			second.close()
		}
	}
}
