package example

import io.github.psd2live.algorithm.worker.AlgorithmObjects
import io.github.psd2live.format.model.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import kotlin.test.*

class AlgorithmObjectsTest {
    @Test fun objectDeltaKeepsUnchangedMeshesAndRejectsCorruptedResources() {
        val directory = Files.createTempDirectory("algorithm-objects-")
        try {
            val input = RigIR(Canvas(64f, 64f), emptyList(), meshes = listOf(Mesh("mesh", "Mesh", null, geometry = null, offsets = null)))
            val before = AlgorithmObjects.encode(input) { AlgorithmObjects.write(directory, it) }
            val output = input.copy(parameters = listOf(Parameter("param", "Parameter", 0f, 1f, 0f)))
            val after = AlgorithmObjects.encode(output) { AlgorithmObjects.write(directory, it) }
            val delta = AlgorithmObjects.delta(before, after, output)
            assertEquals(setOf("frame"), delta.getValue("changed").jsonObject.keys)
            val restored = AlgorithmObjects.apply(before, delta)
            assertEquals(output, AlgorithmObjects.decode(restored, delta.getValue("mesh_order").jsonArray,
                delta.getValue("deformer_order").jsonArray, directory))
            val frame = restored.getValue("frame").jsonObject
            val path = directory.resolve(frame.getValue("sha256").jsonPrimitive.content)
            val bytes = Files.readAllBytes(path); bytes[0] = (bytes[0].toInt() xor 1).toByte(); Files.write(path, bytes)
            assertFailsWith<IllegalArgumentException> { AlgorithmObjects.read(directory, frame) }
            assertFailsWith<IllegalArgumentException> {
                AlgorithmObjects.read(directory, buildJsonObject { put("sha256", "../escape"); put("bytes", 1) })
            }
        } finally {
            Files.list(directory).use { entries -> entries.forEach(Files::delete) }
            Files.delete(directory)
        }
    }
}
