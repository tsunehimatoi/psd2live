package example

import io.github.psd2live.algorithm.worker.*
import io.github.psd2live.core.modifier.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import kotlin.test.*

class AlgorithmContextsTest {
    @Test fun capturesBytesAndRoundTripsReferencesWithoutDuplicatePayloads() {
        val directory = Files.createTempDirectory("algorithm-context-")
        try {
            val pixels = byteArrayOf(1, 2, 3, 4)
            val resource = ModifierAlgorithmResource(pixels)
            val context = ModifierAlgorithmContext(buildJsonObject { put("layer", "body") }, mapOf("pixels" to resource))
            pixels[0] = 99
            resource.read()[0] = 88
            assertContentEquals(byteArrayOf(1, 2, 3, 4), resource.read())
            val encoded = AlgorithmContexts.encode(context) { AlgorithmObjects.write(directory, it) }
            assertEquals(context, AlgorithmContexts.decode(encoded, directory))
            assertEquals(encoded, AlgorithmContexts.encode(context) { AlgorithmObjects.write(directory, it) })
            assertEquals(2L, Files.list(directory).use { it.count() })
            Files.write(directory.resolve(resource.digest), byteArrayOf(9, 2, 3, 4))
            assertFailsWith<IllegalArgumentException> { AlgorithmContexts.decode(encoded, directory) }
            assertFailsWith<IllegalArgumentException> { AlgorithmContexts.decode(JsonObject(encoded + ("version" to JsonPrimitive(2))), directory) }
            assertEquals(ModifierAlgorithmContext.Empty, AlgorithmContexts.decode(null, directory))
        } finally {
            Files.walk(directory).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }
    @Test fun typedSourceInputsRetainPixelsTargetsAndCoordinatesThroughResourceTransport() {
        val directory = Files.createTempDirectory("algorithm-source-context-")
        try {
            val layer = ModifierSourceLayer("layer", "FACE", "NONE", left = 12f, top = 20f,
                width = 1, height = 1, rgba = "pixels", bounds = ModifierSourceBounds(12f, 20f, 13f, 21f),
                centroidX = 12.5f, centroidY = 20.5f, opaquePixels = 1)
            val sources = ModifierSourceInputs(listOf(layer), mapOf("pixels" to ModifierAlgorithmResource(byteArrayOf(1, 2, 3, -1))),
                mapOf("mesh" to "layer"), ModifierSourceTransform(tx = -10f, ty = -20f))
            val wire = AlgorithmContexts.encode(sources.context()) { AlgorithmObjects.write(directory, it) }
            val restored = ModifierSourceInputs.from(AlgorithmContexts.decode(wire, directory))
            assertEquals(layer, restored.layerForTarget("mesh"))
            assertEquals(2f to 0f, restored.toModel.map(12f, 20f))
            assertEquals(255, restored.raster(layer).alpha(0, 0))
            assertEquals(sources.raster(layer).digest, restored.raster(layer).digest)
        } finally {
            Files.list(directory).use { entries -> entries.forEach(Files::delete) }
            Files.delete(directory)
        }
    }
}
