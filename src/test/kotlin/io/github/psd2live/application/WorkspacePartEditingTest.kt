package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.core.modifier.*
import io.github.psd2live.project.MutationAuthor
import io.github.psd2live.project.WorkspaceStore
import io.github.psd2live.scenario.Characters
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.*

class WorkspacePartEditingTest {
    @TempDir lateinit var temp: Path

    @Test fun browLidEditSurvivesPublicReadUndoRedoAndStoreReopen(): Unit = runBlocking {
        val artwork = Characters.browed()
        val builder = WorkspacePreviewBuilder()
        val empty = artwork.copy(rigEdits = RigEditOverlay(modifierStack = RigModifierStack().toJson()))
        val material = builder.build(empty)
        val targets = material.rig.puppet.drawables.map { ModifierTarget("mesh", it.id.raw) }
        val oldConfig = buildJsonObject {
            put("scaffold", "structure"); put("lid", true); put("lidCoupling", false)
            putJsonObject("subConfigs") {
                putJsonObject("brow.lid-coupling") {
                    put("id", "kept-lid"); put("version", 1)
                    put("targets", Json.encodeToJsonElement(targets))
                    putJsonObject("dependencies") { put("eye/fitted", "eye") }
                    putJsonObject("values") {
                        RigLegacyBindings.spec("brow.lid-coupling").knobs.first().let { put(it.id, it.range.start) }
                    }
                }
            }
        }
        val stack = RigModifierStack().add("rig.scaffold", 2, targets, id = "structure")
            .add("eye", 1, targets, buildJsonObject { put("scaffold", "structure") }, id = "eye")
            .add("brow", 1, targets, oldConfig, id = "brow")
        val document = empty.copy(rigEdits = RigEditOverlay(modifierStack = stack.toJson()))
        val runtime = WorkspaceRuntime<RigPreviewModel>({ builder.build(it) }, rebuildFrom = { doc, prior -> builder.build(doc, prior) })
        runtime.install(runtime.state.value.state, "parts", document, builder.build(document))
        val before = runtime.capture()
        val next = ModifierPartConfig.withFlag(oldConfig, "lidCoupling", true)
        val request = buildJsonObject { put("id", "brow"); put("type", "brow"); put("config", next) }
        validateOperationSchema(JsonObject(request + ("state" to JsonPrimitive(before.state))), WorkspaceModifierSchemas.request("modifier_update"))
        WorkspaceDocumentCommands(runtime).execute(before.projectId, before.state, "Brow coupling",
            listOf(WorkspaceDocumentOperation("modifier_update", request)), MutationAuthor.USER)
        val edited = runtime.capture()
        val observation = WorkspaceModifierQueries.describe(edited)
        validateOperationSchema(observation, WorkspaceModifierSchemas.stack)
        assertEquals(next, observation.getValue("entries").jsonArray.last().jsonObject.getValue("config"))
        assertEquals(oldConfig["subConfigs"], next["subConfigs"])
        runtime.checkout(edited.projectId, edited.state, before.historyHead)
        assertEquals(oldConfig, RigModifierStack.fromJson(runtime.capture().document.rigEdits.modifierStack!!).instances.last().config)
        runtime.checkout(edited.projectId, runtime.capture().state, edited.historyHead)
        val store = WorkspaceStore(temp.resolve("store"))
        store.persistHistory("parts", runtime.history())
        val restored = assertNotNull(WorkspaceStore(store.root).loadHistory("parts")).head().snapshot
        assertEquals(next, RigModifierStack.fromJson(restored.rigEdits.modifierStack!!).instances.last().config)
        assertEquals(RigIrCompiler.compile(edited.model), RigIrCompiler.compile(builder.build(restored)))
    }
}
