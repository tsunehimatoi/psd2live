package example

import io.github.psd2live.algorithm.worker.*
import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.compile.RigIrBinary
import io.github.psd2live.format.model.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class AlgorithmChangesTest {
    private fun resources(run: (Path) -> Unit) {
        val directory = Files.createTempDirectory("algorithm-changes-")
        try { run(directory) } finally {
            Files.list(directory).use { entries -> entries.forEach(Files::delete) }
            Files.delete(directory)
        }
    }
    private val p = Parameter("p", "P", -1f, 1f, 0f)
    private val mesh = Mesh("m", "M", null, geometry = null, offsets = null)

    @Test fun sparseChangesRoundTripWithoutUnchangedGeometry() = resources { directory ->
        val input = RigIR(Canvas(128f, 128f), listOf(p), meshes = listOf(mesh), rootPart = "old")
        val changes = ModifierModelChanges(parameters = ModifierObjectChanges(
            updated = listOf(ModifierObjectUpdate(p, p.copy(name = "Edited")))),
            rootPart = ModifierFieldChange("old", null),
            rootChildren = ModifierFieldChange(emptyList(), listOf(ChildRef.MeshRef("m"))))
        val wire = AlgorithmChanges.encode(changes) { AlgorithmObjects.write(directory, it) }
        val restored = AlgorithmChanges.decode(wire, directory)
        val output = restored.apply(input)
        assertEquals(changes.apply(input), output)
        assertSame(input.meshes, output.meshes)
        assertEquals(changes.contributions(), restored.contributions())
        assertEquals(changes.writeKinds(), restored.writeKinds())
        val carrier = RigIrBinary.decode(AlgorithmObjects.read(directory, wire.getValue("after")))
        assertTrue(carrier.meshes.isEmpty())
        assertNull(output.rootPart)
    }

    @Test fun collectionOperationsRetainTheirPreconditionsAcrossTransport() = resources { directory ->
        val other = p.copy(id = "q")
        val input = RigIR(Canvas(64f, 64f), listOf(p, other), meshes = listOf(mesh))
        val changes = ModifierModelChanges(parameters = ModifierObjectChanges(removed = listOf(other), reused = listOf(p)),
            meshes = ModifierObjectChanges(updated = listOf(ModifierObjectUpdate(mesh, mesh.copy(opacity = .5f))),
                added = listOf(mesh.copy(id = "new"))))
        val wire = AlgorithmChanges.encode(changes) { AlgorithmObjects.write(directory, it) }
        val restored = AlgorithmChanges.decode(wire, directory)
        assertEquals(changes.apply(input), restored.apply(input))
        assertFailsWith<IllegalArgumentException> {
            restored.apply(input.copy(meshes = listOf(mesh.copy(opacity = .7f))))
        }
    }

    @Test fun unknownFieldsCountsAndCorruptedResourcesAreRejected() = resources { directory ->
        val wire = AlgorithmChanges.encode(ModifierModelChanges(parameters = ModifierObjectChanges(added = listOf(p)))) {
            AlgorithmObjects.write(directory, it)
        }
        fun invalid(key: String, value: JsonElement) = JsonObject(wire + (key to value))
        assertFailsWith<IllegalArgumentException> { AlgorithmChanges.decode(invalid("schema", JsonPrimitive("future")), directory) }
        assertFailsWith<IllegalArgumentException> { AlgorithmChanges.decode(invalid("fields", JsonArray(listOf(JsonPrimitive("unknown")))), directory) }
        assertFailsWith<IllegalArgumentException> { AlgorithmChanges.decode(invalid("objects", buildJsonObject {
            put("parameters", JsonArray(listOf(2, 0, 0, 0).map(::JsonPrimitive)))
        }), directory) }
        assertFailsWith<IllegalArgumentException> { AlgorithmChanges.decode(invalid("objects", buildJsonObject {
            put("parameters", JsonArray(listOf(-1, 0, 0, 0).map(::JsonPrimitive)))
        }), directory) }
        val ref = wire.getValue("after").jsonObject
        val file = directory.resolve(ref.getValue("sha256").jsonPrimitive.content)
        val bytes = Files.readAllBytes(file); bytes[0] = (bytes[0].toInt() xor 1).toByte(); Files.write(file, bytes)
        assertFailsWith<IllegalArgumentException> { AlgorithmChanges.decode(wire, directory) }
    }
}
