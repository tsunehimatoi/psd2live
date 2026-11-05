package io.github.psd2live.project

import io.github.psd2live.core.*
import io.github.psd2live.core.modifier.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class RigModifierPersistenceTest {
    @TempDir lateinit var temp: Path

    private fun stack() = RigModifierStack()
        .add("motion", 1, listOf(ModifierTarget("mesh", "mesh")), presetGroup = "recommended")
        .withOverrides("m1", listOf(buildJsonObject { put("op", "structure"); putJsonArray("edits") {} }))
        .remove("m1")
        .add("physics", 1, listOf(ModifierTarget("part", "body")), buildJsonObject { put("input", "ParamX") })
        .update("m2") { it.copy(enabled = false) }
        .add("motion", 1, listOf(ModifierTarget("mesh", "mesh")))
        .update("m3") { it.copy(enabled = false, inputFingerprint = "a".repeat(64)) }
        .move("m3", 0)

    @Test fun modifierStateChangesRevisionAndSurvivesWorkingStoreAndProjectReopen() = runBlocking<Unit> {
        val project = SyntheticProject(temp.resolve("project"), 1, 8, 1)
        val before = project.tree.head().snapshot
        val document = before.copy(rigEdits = before.rigEdits.copy(modifierStack = stack().toJson()))
        val revision = WorkspaceRevisions.of(document)
        assertNotEquals(WorkspaceRevisions.of(before), revision)
        assertEquals(WorkspaceRevisions.reference(document), revision)
        project.tree.commit(project.tree.head().node.id, document, revision, revision, "Add modifiers", "user")
        project.store.persistHistory(SyntheticProject.PROJECT, project.tree.state())
        val loaded = WorkspaceStore(project.store.root).loadHistory(SyntheticProject.PROJECT)!!
        assertEquals(document.rigEdits, loaded.head().snapshot.rigEdits)
        assertEquals(revision, WorkspaceRevisions.of(loaded.head().snapshot))
        val file = temp.resolve("modifiers.psd2live")
        ProjectRepository(writeHeadCache = false).save(project.capture(), file)
        ProjectRepository().open(file).use { opened ->
            assertEquals(document.rigEdits, opened.history.head().snapshot.rigEdits)
            assertEquals(revision, WorkspaceRevisions.of(opened.history.head().snapshot))
            assertEquals(stack(), RigModifierStack.fromJson(opened.history.head().snapshot.rigEdits.modifierStack!!))
            assertEquals(WorkspaceRevisions.of(before), WorkspaceRevisions.of(opened.history.selections().first().snapshot))
        }
    }

    @Test fun modifierRigNodeRequiresANewSchemaAndSplitsWithoutDroppingAnyState() {
        val snapshot = buildJsonObject { putJsonObject("rigEdits") { put("modifierStack", stack().toJson()) } }
        val index = ProjectFormatV2.split(temp, snapshot)
        val hash = index.getValue("nodes").jsonObject.getValue("rig").jsonPrimitive.content
        val node = Json.parseToJsonElement(Files.readString(temp.resolve("document/nodes/rig/$hash.json"))).jsonObject
        assertEquals(ProjectFormatV2.NODE_SCHEMA_MODIFIERS, node.getValue("schema").jsonPrimitive.int)
        val restored = ProjectFormatV2.join(temp, index).getValue("rigEdits").jsonObject.getValue("modifierStack")
        assertEquals(stack().toJson(), restored)
        assertEquals(ProjectFormatV2.NODE_SCHEMA, ProjectFormatV2.schemaOf("rig", buildJsonObject { putJsonObject("rigEdits") {} }))
    }
}
