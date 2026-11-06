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
}
