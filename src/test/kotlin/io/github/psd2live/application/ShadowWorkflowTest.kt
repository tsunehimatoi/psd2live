package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.compile.ExportOptions
import io.github.psd2live.history.WorkspaceHistoryTree
import io.github.psd2live.project.*
import io.github.psd2live.targets.cubism.Cmo3Target
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.umamo.format.cmo3.Cmo3
import org.umamo.runtime.model.PartGroupMode
import java.nio.file.Path
import kotlin.test.*

class ShadowWorkflowTest {
    @TempDir lateinit var temp: Path

    private suspend fun createRuntime(layerCount: Int = 3): Pair<WorkspaceRuntime<RigPreviewModel>, WorkspaceDocumentCommands> {
        val source = SyntheticProject(temp.resolve("source"), layerCount, 12, 1).tree.head().snapshot
        val config = PipelineConfig(
            atlasSize = 256,
            meshSpacing = 12,
            rigEdits = RigEditOverlay(
                parameterEdits = listOf(
                    RigParameterEdit("ParamAngleX", "Angle X", -30f, 30f, 0f, created = true),
                    RigParameterEdit("ParamAngleY", "Angle Y", -30f, 30f, 0f, created = true),
                ),
                modifierStack = RigModifierStack().toJson(),
            ),
        )
        val document = source.copy(settings = WorkspaceSettingsCodec.encode(config), rigEdits = config.rigEdits)
        val builder = WorkspacePreviewBuilder()
        val runtime = WorkspaceRuntime<RigPreviewModel>(
            { builder.build(it) },
            rebuildFrom = { doc, previous -> builder.build(doc, previous) },
        )
        val model = builder.build(document)
        runtime.install(runtime.state.value.state, "project", document, model)
        return runtime to WorkspaceDocumentCommands(runtime)
    }

    @Test
    fun `shadow put world_set apply delete and export round-trip`(): Unit = runBlocking {
        val (runtime, commands) = createRuntime(3)
        val drawables = runtime.capture().model.rig.puppet.drawables.map { it.id.raw }
        assertEquals(3, drawables.size)
        val face = drawables[0]
        val bang1 = drawables[1]
        val bang2 = drawables[2]

        suspend fun exec(op: String, req: JsonObject) = runtime.capture().let {
            commands.execute(it.projectId, it.state, op, listOf(WorkspaceDocumentOperation(op, req)), MutationAuthor.USER)
        }

        // 1. Add cast shadow (2 bangs -> 1 face)
        exec("shadow_put", buildJsonObject {
            put("kind", "cast")
            put("name", "Bangs Cast")
            put("casters", JsonArray(listOf(JsonPrimitive(bang1), JsonPrimitive(bang2))))
            put("receivers", JsonArray(listOf(JsonPrimitive(face))))
        })
        // 2. Add form shadow on face
        exec("shadow_put", buildJsonObject {
            put("kind", "form")
            put("name", "Face Side")
            put("targets", JsonArray(listOf(JsonPrimitive(face))))
        })

        val afterAdd = runtime.capture()
        val stack = RigModifierStack.fromJson(requireNotNull(afterAdd.document.rigEdits.modifierStack))
        assertTrue(stack.instances.any { it.type == ShadowAlgorithmPlugin.TYPE_WORLD })
        val castInst = stack.instances.single { it.type == ShadowAlgorithmPlugin.TYPE_CAST }
        val formInst = stack.instances.single { it.type == ShadowAlgorithmPlugin.TYPE_FORM }

        val puppetAfterAdd = afterAdd.model.rig.puppet
        assertTrue(
            puppetAfterAdd.parts.any { it.groupMode is PartGroupMode.Isolated && it.composite.maskedBy.any { m -> m.raw == face } },
            "Expected isolated part; parts=${puppetAfterAdd.parts.map { it.id.raw to it.groupMode }}, report=${afterAdd.model.rig.modifierReport}",
        )
        assertTrue(puppetAfterAdd.drawables.any { it.invertMask })

        // 3. Update world lighting and verify preview compare toggle
        exec("shadow_world_set", buildJsonObject {
            put("azimuth", -40.0)
            put("elevation", 30.0)
            put("preview_enabled", false)
        })
        assertFalse(runtime.capture().model.rig.puppet.drawables.any { it.invertMask })

        exec("shadow_world_set", buildJsonObject {
            put("preview_enabled", true)
        })
        assertTrue(runtime.capture().model.rig.puppet.drawables.any { it.invertMask })

        // 4. Export CMO3 & verify serialization round-trip
        val ir = RigIrCompiler.compile(runtime.capture().model, tileArt = true)
        val cmo3Bytes = Cmo3.write(Cmo3Target().convert(ir, ExportOptions("shadow")).model)
        assertTrue(cmo3Bytes.isNotEmpty())
        Cmo3.read(cmo3Bytes)

        // 5. Bake/apply cast shadow into ordinary layers
        exec("shadow_apply", buildJsonObject { put("id", castInst.id) })
        val afterApply = runtime.capture()
        val appliedStack = RigModifierStack.fromJson(requireNotNull(afterApply.document.rigEdits.modifierStack))
        assertTrue(castInst.id in appliedStack.appliedNodes)
        assertTrue(afterApply.model.rig.puppet.parts.any { it.groupMode is PartGroupMode.Isolated })

        // 6. Save & reopen ProjectFormatV2
        val revision = WorkspaceRevisions.of(afterApply.document)
        val store = WorkspaceStore(temp.resolve("shadow-project"))
        store.persistHistory("project", WorkspaceHistoryTree(afterApply.document, revision, revision).state())
        val reopened = WorkspaceStore(store.root).loadHistory("project")!!.head().snapshot
        assertEquals(afterApply.document.rigEdits.modifierStack, reopened.rigEdits.modifierStack)

        // 7. Delete form shadow
        exec("shadow_delete", buildJsonObject { put("id", formInst.id) })
        assertFalse(runtime.capture().model.rig.puppet.drawables.any { it.invertMask })

        // 8. Verify modifier configuration schemas include all shadow types
        assertTrue(WorkspaceModifierSchemas.configuration("shadow.world").isNotEmpty())
        assertTrue(WorkspaceModifierSchemas.configuration("shadow.cast").isNotEmpty())
        assertTrue(WorkspaceModifierSchemas.configuration("shadow.form").isNotEmpty())
        assertTrue(WorkspaceModifierSchemas.addition.isNotEmpty())
    }
}
