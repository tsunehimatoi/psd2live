package io.github.psd2live.scenario

import io.github.psd2live.application.WorkspaceDocumentOperation
import io.github.psd2live.application.WorkspaceInitialHair
import io.github.psd2live.application.WorkspaceInitialModifiers
import io.github.psd2live.application.WorkspaceInitialPreset
import io.github.psd2live.application.WorkspacePartitionCommands
import io.github.psd2live.application.WorkspaceSourceImporter
import io.github.psd2live.core.PipelineConfig
import io.github.psd2live.core.modifier.ModifierStatus
import io.github.psd2live.project.MutationAuthor
import io.github.psd2live.tools.Renderer
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import org.umamo.format.cmo3.Cmo3
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.interop.cmo3.Cmo3Import
import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class DepthSplitTest {
    @TempDir lateinit var temp: Path

    @Test fun depthSlicesInAModifierWorkspaceSurviveReplayAndReopen() = Studio.run(temp) {
        step("import with recommended modifiers") {
            WorkspaceSourceImporter(runtime).importPsd(Characters.headPsd(temp), null, runtime.state.value.state,
                initialConfig = PipelineConfig(atlasSize = 256, exportMoc3 = false), modifiers = WorkspaceInitialModifiers.RECOMMENDED)
        }
        val hair = now.model.analysis.layers.single { it.source.name == "hair" }.source.id.raw
        val face = now.model.analysis.layers.single { it.source.name == "face" }.source.id.raw
        val source = mesh(hair)
        val middle = mesh(face)
        val headTurn = ParameterId("ParamAngleX")
        val beforeTurn = CpuDeformationEvaluator().evaluate(puppet, mapOf(headTurn to 20f)).worldPositions.getValue(DrawableId(source))
        val active = now.model.modifierEvaluation!!.entries.filter { it.status == ModifierStatus.ACTIVE }.map { it.instance.id }
        assertTrue(active.isNotEmpty())
        val request = buildJsonObject {
            put("source_id", source)
            putJsonArray("middle_ids") { add(middle) }
            put("back_layer_id", "depth:hair-back")
            put("front_layer_id", "depth:hair-front")
        }
        val before = now
        val split = step("split hair around the face") {
            WorkspacePartitionCommands(runtime).execute(before.projectId, before.state,
                listOf(WorkspaceDocumentOperation("source_split_depth", request)), "Depth split", MutationAuthor.USER)
        }
        assertTrue(split.commit.applied)
        val back = mesh("depth:hair-back")
        val front = mesh("depth:hair-front")
        fun checkGeometry(model: PuppetModel) {
            val vertices = CpuDeformationEvaluator().evaluate(model, mapOf(headTurn to 20f)).worldPositions
            val backVertices = vertices.getValue(DrawableId(back))
            val frontVertices = vertices.getValue(DrawableId(front))
            assertEquals(backVertices.size, frontVertices.size)
            backVertices.indices.forEach { assertEquals(backVertices[it], frontVertices[it], .001f) }
        }
        fun checkSlices() {
            val inactive = now.model.modifierEvaluation!!.entries.filter { it.instance.id in active && it.status != ModifierStatus.ACTIVE }
            assertTrue(inactive.isEmpty(), inactive.joinToString("\n") { "${it.instance.type}: ${it.status} ${it.errorDetails ?: it.missingDependencies}" })
            val drawables = puppet.drawables.associateBy { it.id.raw }
            assertFalse(source in drawables)
            assertTrue(drawables.getValue(back).drawOrder < drawables.getValue(middle).drawOrder)
            assertTrue(drawables.getValue(middle).drawOrder < drawables.getValue(front).drawOrder)
            assertTrue(puppet.glues.any { it.meshA.raw == back && it.meshB.raw == front })
            checkGeometry(puppet)
        }
        checkSlices()
        val afterTurn = CpuDeformationEvaluator().evaluate(puppet, mapOf(headTurn to 20f)).worldPositions.getValue(DrawableId(back))
        assertEquals(beforeTurn.size, afterTurn.size)
        beforeTurn.indices.forEach { assertEquals(beforeTurn[it], afterTurn[it], .001f) }
        undo()
        assertEquals(source, mesh(hair))
        redo()
        val beforeErase = Renderer(now.model, 128).render(emptyMap())
        val frontBeforePaint = now.document.source.layers.single { it.id.raw == "depth:hair-front" }.raster.rgba
        edit("erase from the front slice", "source_paint_eraser", req("layer_id" to "depth:hair-front",
            "points" to listOf(listOf(26, 20), listOf(36, 22)), "radius" to 3, "rebuild_mesh" to true))
        val painted = now.document.source.layers.single { it.id.raw == "depth:hair-front" }.raster.rgba
        assertFalse(frontBeforePaint.contentEquals(painted))
        assertContentEquals(frontBeforePaint, now.document.source.layers.single { it.id.raw == "depth:hair-back" }.raster.rgba)
        val afterErase = Renderer(now.model, 128).render(emptyMap())
        assertNotEquals(beforeErase.getRGB(58, 42), afterErase.getRGB(58, 42))
        reopen()
        checkSlices()
        val cmo3 = export("cmo3").single { it.toString().endsWith(".cmo3") }
        val readback = Cmo3Import.fromModelSource(Cmo3.read(Files.readAllBytes(cmo3)).root as CModelSource)
        checkGeometry(readback)
    }

    @Test fun aLiveHairSimulationFollowsTheRearSlice() = Studio.run(temp) {
        step("import with hair simulation") {
            WorkspaceSourceImporter(runtime).importPsd(Characters.headPsd(temp), null, runtime.state.value.state,
                initialConfig = PipelineConfig(atlasSize = 256, exportMoc3 = false), modifiers = WorkspaceInitialModifiers.CUSTOM,
                preset = WorkspaceInitialPreset(frontHair = WorkspaceInitialHair.SIMULATION))
        }
        fun simulation() = now.model.modifierEvaluation!!.entries.single { it.instance.type == "simulation" }
        val initial = simulation()
        assertEquals(ModifierStatus.ACTIVE, initial.status, initial.error)
        val hair = now.model.analysis.layers.single { it.source.name == "hair" }.source.id.raw
        val face = now.model.analysis.layers.single { it.source.name == "face" }.source.id.raw
        edit("split simulated hair", "source_split_depth", req("source_id" to mesh(hair), "middle_ids" to listOf(mesh(face)),
            "back_layer_id" to "depth:sim-back", "front_layer_id" to "depth:sim-front"))
        val result = simulation()
        assertEquals(initial.instance.id, result.instance.id)
        assertEquals(ModifierStatus.ACTIVE, result.status, result.error)
        assertEquals(listOf(mesh("depth:sim-back")), result.instance.targets.map { it.id })
    }
}
