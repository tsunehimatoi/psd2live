package io.github.psd2live.scenario

import io.github.psd2live.application.*
import io.github.psd2live.core.*
import io.github.psd2live.core.modifier.ModifierAuthorEdits
import io.github.psd2live.core.modifier.ModifierStatus
import io.github.psd2live.ui.EditHierarchyMode
import io.github.psd2live.ui.canvasGeometryCommand
import kotlinx.serialization.json.JsonArray
import org.junit.jupiter.api.io.TempDir
import org.umamo.edit.MeshTopology
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.VertexGroup
import org.umamo.runtime.model.VertexGroupKind
import java.nio.file.Path
import kotlin.test.*

class MeshPointEditingScenario {
    @TempDir lateinit var temp: Path

    @Test fun dragAndMergeTheGeneratedSurfaceWithPaintedWeightsThenReopen() = Studio.run(temp, checks = emptySet()) {
        val psd = Characters.figurePsd(temp)
        step("fresh PSD with a live scaffold") {
            WorkspaceSourceImporter(runtime).importPsd(psd, null, runtime.state.value.state, modifiers = WorkspaceInitialModifiers.RECOMMENDED)
        }
        val source = now.document.source.layers.single { it.name == "front_hair" }
        val id = DrawableId(mesh(source.id.raw))
        fun target() = puppet.drawables.single { it.id == id }
        fun mesh() = requireNotNull(target().mesh)
        fun pin() = puppet.vertexGroups.single { it.drawableId == id && it.name == "Edited pin" }.weights
        val initial = mesh()
        val statuses = now.model.modifierEvaluation!!.entries.filter { it.status == ModifierStatus.ACTIVE }.map { it.instance.id }
        val point = RigGeometryTools.geometry(puppet, "mesh", id.raw, emptyMap()).points.copyOf()
        point[0] += .003f
        val move = canvasGeometryCommand(EditHierarchyMode.EDIT, "mesh", id.raw, emptyMap(), point)
        // Desktop precompiles the live drag before the workspace records it.
        val (preview, gesture) = RigAuthoringJournal.compile(puppet, JsonArray(listOf(move)))
        val dragged = preview.drawables.single { it.id == id }.mesh!!
        assertFalse(initial.positions.contentEquals(dragged.positions))
        journal("move generated hair vertex", JsonArray(gesture))
        assertContentEquals(dragged.positions, mesh().positions)
        assertContentEquals(dragged.uvs, mesh().uvs)
        undo()
        assertContentEquals(initial.positions, mesh().positions)
        redo()
        reopen()
        assertContentEquals(dragged.positions, mesh().positions)

        val weights = FloatArray(mesh().vertexCount) { (it % 5) / 4f }
        journal("save a manually painted group", JsonArray(listOf(VertexGroupJournal.encode(VertexGroup("Edited pin", id, VertexGroupKind.PIN, weights)))))
        assertContentEquals(weights, pin())
        val edge = MeshTopology.uniqueEdges(mesh().indices).first()
        val selected = listOf(edge.endpointLow, edge.endpointHigh)
        val command = req("op" to "canvas_topology", "id" to id.raw, "action" to "merge", "vertices" to selected)
        val expected = RigAuthoringJournal.apply(puppet, command)
        val priorGroups = now.document.rigEdits.modifierStack!!
        journal("merge after the saved weights", JsonArray(listOf(command)))
        val merged = expected.drawables.single { it.id == id }.mesh!!
        assertEquals(initial.vertexCount - 1, mesh().vertexCount)
        assertContentEquals(merged.indices, mesh().indices)
        assertContentEquals(merged.positions, mesh().positions)
        val migrated = expected.vertexGroups.single { it.drawableId == id && it.name == "Edited pin" }.weights
        assertContentEquals(migrated, pin())
        assertTrue(now.model.modifierEvaluation!!.entries.any { it.instance.type == ModifierAuthorEdits.TYPE && it.status == ModifierStatus.ACTIVE })
        undo()
        assertEquals(priorGroups, now.document.rigEdits.modifierStack)
        assertContentEquals(weights, pin())
        redo()
        reopen()
        assertContentEquals(migrated, pin())
        assertContentEquals(merged.uvs, mesh().uvs)
        // All of the original rig generators are still present after replay.
        assertTrue(now.model.modifierEvaluation!!.entries.filter { it.instance.id in statuses && it.instance.type != "simulation" }.all { it.status == ModifierStatus.ACTIVE })
    }
}
