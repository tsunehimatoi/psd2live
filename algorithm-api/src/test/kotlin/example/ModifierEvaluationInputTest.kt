package example

import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.model.*
import kotlin.test.*

class ModifierEvaluationInputTest {
    private val p = Parameter("p", "P", -1f, 1f, 0f)
    private val parent = Deformer.Rotation("parent", "Parent", null, null, 0f, null)
    private val child = parent.copy(id = "child", parent = "parent")
    private val mesh = Mesh("mesh", "Mesh", "child", geometry = null, offsets = null)
    private val rig = RigIR(Canvas(100f, 100f), listOf(p), meshes = listOf(mesh), deformers = listOf(parent, child))

    @Test fun individualAndMissingReadsIgnoreUnrelatedObjectsButDetectRelevantChanges() {
        val view = ModifierEvaluationInput(rig)
        assertEquals(p, view.parameter("p")); assertNull(view.parameter("missing"))
        val reads = view.reads()
        assertTrue(reads.matches(rig.copy(meshes = listOf(mesh.copy(opacity = .5f)))))
        assertTrue(reads.matches(rig.copy(parameters = rig.parameters + p.copy(id = "unrelated"))))
        assertFalse(reads.matches(rig.copy(parameters = listOf(p.copy(max = 2f)))))
        assertFalse(reads.matches(rig.copy(parameters = rig.parameters + p.copy(id = "missing"))))
        assertEquals(setOf(ModifierReadKey("parameter", "p"), ModifierReadKey("parameter", "missing")), reads.keys)
    }

    @Test fun collectionReadsTrackMembershipOrderAndSnapshotsDoNotGrow() {
        val view = ModifierEvaluationInput(rig)
        view.parameters
        val snapshot = view.reads()
        view.mesh("mesh")
        assertEquals(setOf(ModifierReadKey("parameter")), snapshot.keys)
        assertFalse(snapshot.matches(rig.copy(parameters = listOf(p, p.copy(id = "new")))))
        assertTrue(snapshot.matches(rig.copy(meshes = emptyList())))
    }

    @Test fun parentQueriesObserveTheCompleteChainAndRejectCyclesAndMissingParents() {
        val view = ModifierEvaluationInput(rig)
        assertEquals(listOf(child, parent), view.parentsOfMesh("mesh"))
        assertFalse(view.reads().matches(rig.copy(deformers = listOf(parent.copy(baseAngle = 10f), child))))
        assertFailsWith<IllegalArgumentException> {
            ModifierEvaluationInput(rig.copy(deformers = listOf(child))).parentsOfMesh("mesh")
        }
        assertFailsWith<IllegalArgumentException> {
            ModifierEvaluationInput(rig.copy(deformers = listOf(parent.copy(parent = "child"), child))).parentsOfMesh("mesh")
        }
    }
}
