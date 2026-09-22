package io.github.psd2live.core

import kotlinx.serialization.json.*
import org.umamo.runtime.model.*
import kotlin.test.*

class ParameterDefinitionTest {
    private fun emptyModel() = PuppetModel(parameters = emptyList(), parts = emptyList(), deformers = emptyList(), drawables = emptyList(), rootChildren = emptyList(), rootPartId = null)
    private fun command(action: String, min: Float = -1f, default: Float = 0f, max: Float = 1f) = buildJsonObject {
        put("op", "structure")
        putJsonArray("edits") { add(buildJsonObject {
            put("action", action); put("kind", "parameter"); put("id", "ParamTest")
            if (action != "delete") {
                put("name", if (action == "update") "Renamed" else "Test")
                put("min", min); put("default", default); put("max", max)
            }
        }) }
    }
    @Test fun definitionsReplayThroughAuthoringHistory() {
        val empty = emptyModel()
        val commands = JsonArray(listOf(command("create"), command("update", -30f, 10f, 30f)))
        val (updated, journal) = RigAuthoringJournal.compile(empty, commands)
        assertEquals(Parameter(ParameterId("ParamTest"), "Renamed", -30f, 30f, 10f), updated.parameters.single())
        assertEquals(updated, journal.fold(empty, RigAuthoringJournal::apply))
        assertEquals(empty.parameters, emptyList())
        val deleted = RigAuthoringJournal.apply(updated, command("delete"))
        assertTrue(deleted.parameters.isEmpty())
        assertTrue(deleted.parameterTree.isEmpty())
    }
    @Test fun invalidDefinitionsCannotEnterHistory() {
        val empty = emptyModel()
        assertFailsWith<IllegalArgumentException> { RigAuthoringJournal.apply(empty, command("update")) }
        assertFailsWith<IllegalArgumentException> { RigAuthoringJournal.apply(empty, command("create", 1f, 0f, -1f)) }
        assertFailsWith<IllegalArgumentException> { RigAuthoringJournal.apply(empty, command("create", -1f, 2f, 1f)) }
        assertFailsWith<IllegalArgumentException> { RigAuthoringJournal.apply(empty, command("create", 0f, 0f, 0f)) }
        val created = RigAuthoringJournal.apply(empty, command("create"))
        assertFailsWith<IllegalArgumentException> { RigAuthoringJournal.apply(created, command("create")) }
    }
}
