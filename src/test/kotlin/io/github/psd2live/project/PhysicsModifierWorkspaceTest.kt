package io.github.psd2live.project

import io.github.psd2live.application.WorkspaceDocumentOperation
import io.github.psd2live.application.WorkspaceDocumentEdits
import io.github.psd2live.application.WorkspaceModifierEdits
import io.github.psd2live.application.WorkspacePreviewBuilder
import io.github.psd2live.core.*
import io.github.psd2live.core.modifier.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class PhysicsModifierWorkspaceTest {
    @TempDir lateinit var temp: Path

    @Test fun missingPhysicsPinSurvivesReopenAndBlocksApplyAndExport(): Unit = runBlocking {
        val project = SyntheticProject(temp, 1, 12, 1)
        val (document, stack) = fixture(project)
        val instance = stack.instances.single()
        val unavailable = instance.copy(plugin = requireNotNull(instance.plugin).copy(version = "99.99.99", digest = "0".repeat(64)))
        verifyUnavailablePhysics(project, document, stack.copy(instances = listOf(unavailable)))
    }

    @Test fun missingUnpinnedVersionSurvivesReopenAndBlocksApplyAndExport(): Unit = runBlocking {
        val project = SyntheticProject(temp, 1, 12, 1)
        val (document, stack) = fixture(project)
        val legacy = stack.instances.single().copy(plugin = null)
        assertTrue(ModifierPluginCatalog.available(legacy))
        val unpinned = document.copy(rigEdits = document.rigEdits.copy(
            modifierStack = stack.copy(instances = listOf(legacy)).toJson()))
        val restored = WorkspacePreviewBuilder().build(unpinned)
        assertEquals(ModifierStatus.ACTIVE, assertNotNull(restored.modifierEvaluation).entries.single().status)

        val unavailable = legacy.copy(version = 99)
        verifyUnavailablePhysics(project, document, stack.copy(instances = listOf(unavailable)))
    }

    @Test fun pinnedPackageWithoutAlgorithmVersionBlocksApplyAndExport(): Unit = runBlocking {
        val project = SyntheticProject(temp, 1, 12, 1)
        val (document, stack) = fixture(project)
        val unavailable = stack.instances.single().copy(version = 99)
        verifyUnavailablePhysics(project, document, stack.copy(instances = listOf(unavailable)))
    }

    private suspend fun verifyUnavailablePhysics(project: SyntheticProject, document: WorkspaceDocument, missing: RigModifierStack) {
        val unavailable = missing.instances.single()
        assertFalse(ModifierPluginCatalog.available(unavailable))
        val saved = document.copy(rigEdits = document.rigEdits.copy(modifierStack = missing.toJson()))
        val reopened = reopen(project, saved)
        val preview = WorkspacePreviewBuilder().build(reopened)

        assertEquals(missing, RigModifierStack.fromJson(requireNotNull(reopened.rigEdits.modifierStack)))
        val row = preview.rig.modifierReport!!.getValue("entries").jsonArray.single().jsonObject
        assertEquals("missing_plugin", row.getValue("status").jsonPrimitive.content)
        assertTrue(row.getValue("contributions").jsonArray.isEmpty())
        assertTrue(assertNotNull(preview.rig.modifierOutput).physics.groups.isEmpty())
        assertFailsWith<IllegalArgumentException> {
            WorkspaceModifierEdits.apply(applyOperation(unavailable.id), reopened, preview)
        }
        val output = temp.resolve("missing-export")
        val failure = assertFailsWith<IllegalArgumentException> { PSD2LivePipeline().export(preview, "test.psd", output) }
        assertContains(failure.message.orEmpty(), "${unavailable.type}@${unavailable.version}")
        assertFalse(Files.exists(output))
    }

    @Test fun applyingPhysicsTransfersOwnershipAndExportReadsBackAfterReopen(): Unit = runBlocking {
        val project = SyntheticProject(temp, 1, 12, 1)
        val (document, stack) = fixture(project)
        val instance = stack.instances.single()
        val preview = WorkspacePreviewBuilder().build(document)
        val expected = RigIrCompiler.compile(preview)
        val setting = RigPhysicsEdit.fromJson(instance.config).copy(id = "AuthoredPendulum")

        assertEquals(listOf(Physics3Json.group(setting)), expected.physics.groups)
        assertEquals(PhysicsOrigin.MODIFIER, PhysicsCatalog.groups(preview).single().origin)
        assertFailsWith<IllegalArgumentException> {
            WorkspaceDocumentEdits.physics(document, preview, buildJsonObject {
                put("id", setting.id)
                put("enabled", false)
            })
        }
        val applied = WorkspaceModifierEdits.apply(applyOperation(instance.id), document, preview)
        val remaining = RigModifierStack.fromJson(requireNotNull(applied.rigEdits.modifierStack))
        assertTrue(remaining.instances.isEmpty())
        assertEquals(instance.plugin, remaining.appliedCapabilities.single().plugin)
        assertEquals(listOf(setting), applied.rigEdits.physicsEdits)
        assertEquals(listOf(setting.id), applied.rigEdits.physicsOrder)

        val restored = WorkspacePreviewBuilder().build(reopen(project, applied))
        assertEquals(expected, RigIrCompiler.compile(restored))
        assertEquals(PhysicsOrigin.CUSTOM, PhysicsCatalog.groups(restored).single().origin)
        val output = temp.resolve("applied-export")
        PSD2LivePipeline().export(restored, "test.psd", output)
        assertEquals(Physics3Json.Physics3(listOf(setting), 60f),
            Physics3Json.read(Files.readString(output.resolve("test.physics3.json"))))
    }

    private fun fixture(project: SyntheticProject): Pair<WorkspaceDocument, RigModifierStack> {
        val source = project.tree.head().snapshot
        val empty = RigModifierStack()
        val config = PipelineConfig(atlasSize = 64, meshSpacing = 8, exportMoc3 = true, exportCmo3 = false,
            rigEdits = RigEditOverlay(physicsFps = 60, modifierStack = empty.toJson(), parameterEdits = listOf(
                RigParameterEdit("ParamInput", "Input", -1f, 1f, 0f, created = true),
                RigParameterEdit("ParamOutput", "Output", -1f, 1f, 0f, created = true))))
        val mesh = PSD2LivePipeline().buildPreview(source.source, config).rig.puppet.drawables.single().id.raw
        val setting = RigPhysicsEdit("draft", "Pendulum",
            listOf(PhysicsInput("ParamInput", weight = 75f, reflect = true)),
            listOf(PhysicsOutput("ParamOutput", vertex = 2, scale = 0.5f, type = PhysicsSourceType.X)),
            listOf(PhysicsSegment(length = 6f), PhysicsSegment(length = 4f)),
            PhysicsNormalization(angleMin = -20f, angleMax = 30f))
        val stack = empty.add("physics", 1, listOf(ModifierTarget("mesh", mesh)), setting.toJson(),
            outputAliases = mapOf("physics" to "AuthoredPendulum"))
        return source.copy(settings = WorkspaceSettingsCodec.encode(config),
            rigEdits = config.rigEdits.copy(modifierStack = stack.toJson())) to stack
    }

    private fun applyOperation(id: String) = WorkspaceDocumentOperation("modifier_apply", buildJsonObject { put("id", id) })

    private suspend fun reopen(project: SyntheticProject, document: WorkspaceDocument): WorkspaceDocument {
        val revision = WorkspaceRevisions.of(document)
        project.tree.commit(project.tree.head().node.id, document, revision, revision, "Physics modifier", "user")
        project.store.persistHistory(SyntheticProject.PROJECT, project.tree.state())
        val path = temp.resolve("physics.psd2live")
        ProjectRepository(writeHeadCache = false).save(project.capture(), path)
        return ProjectRepository().open(path).use { it.history.head().snapshot }
    }
}
