package io.github.psd2live.core.sim

import org.umamo.runtime.model.PuppetModel
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import io.github.psd2live.bridge.PuppetIr
import io.github.psd2live.format.model.*
import io.github.psd2live.core.PhysicsInput

class SimExportTest {
    @Test fun stationaryInputsBakeAsZeroResponseAndRemainExportable() {
        val mesh = Mesh("cloth", "Cloth", null, geometry = MeshGeometry(
            Floats.values(0f, 0f, 20f, 0f, 0f, 20f), Floats.values(0f, 0f, 1f, 0f, 0f, 1f),
            Ints.values(0, 1, 2)), offsets = null)
        val source = PuppetIr.toPuppet(RigIR(Canvas(100f, 100f),
            listOf(Parameter("Input", "Input", -1f, 1f, 0f)), meshes = listOf(mesh)))
        val setting = RigSimEdit("static-cloth", "Cloth", SimKind.CLOTH, listOf(mesh.id), inputs = listOf(PhysicsInput("Input")))
        val bake = SimBaker.bake(source, setting, SimBaker.Options(duration = .02f, parallel = false))
        assertTrue(bake.modes.isEmpty())
        assertTrue(bake.statics.isEmpty())
        val restored = RigSimEdit.fromJson(setting.copy(bake = bake).toJson())
        assertFalse(SimBake.stale(source, restored))
        assertEquals(bake.fingerprint, restored.bake?.fingerprint)
        SimExport.export(source, listOf(restored), emptySet(), emptySet())
    }

	private val model = PuppetModel(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), null)
	private val edit = RigSimEdit("cloth", "Cloth", SimKind.CLOTH, listOf("missing"),
		bake = SimBakeResult("test", mapOf("missing" to 3), emptyList(), emptyList()))

	@Test fun anEnabledBakeThatCannotBuildItsSceneReportsTheSimulationInsteadOfDroppingIt() {
		val failure = assertFailsWith<IllegalStateException> {
			SimExport.export(model, listOf(edit), emptySet(), emptySet())
		}
		assertTrue(failure.message.orEmpty().contains("cloth"))
		assertTrue(failure.cause is IllegalArgumentException)
	}

	@Test fun disabledAndUnbakedSimulationsDoNotRequireAScene() {
		val result = SimExport.export(model, listOf(edit.copy(enabled = false), edit.copy(bake = null)), emptySet(), emptySet())
		assertTrue(result.simulations.isEmpty())
	}
}
