package example

import io.github.psd2live.core.modifier.*
import kotlinx.serialization.json.*
import kotlin.test.*

class ModifierSourceInputsTest {
    private val layer = ModifierSourceLayer("source", "EYEWHITE", "LEFT", left = 10f, top = 20f,
        width = 2, height = 1, rgba = "pixels", bounds = ModifierSourceBounds(10f, 20f, 12f, 21f),
        centroidX = 11f, centroidY = 20.5f, opaquePixels = 1)

    @Test fun sourceSnapshotsSurviveContextRoundTripAndDoNotExposeMutablePixels() {
        val pixels = byteArrayOf(1, 2, 3, -1, 4, 5, 6, 0)
        val layers = mutableListOf(layer)
        val targetMap = mutableMapOf("mesh" to "source")
        val captured = ModifierSourceInputs(layers, mapOf("pixels" to ModifierAlgorithmResource(pixels)), targetMap,
            ModifierSourceTransform(xx = -1f, tx = 100f, ty = 5f))
        layers.clear(); targetMap.clear(); pixels.fill(0)
        val restored = ModifierSourceInputs.from(captured.context())
        assertEquals(layer, restored.layerForTarget("mesh"))
        assertEquals(90f to 25f, restored.toModel.map(10f, 20f))
        val raster = restored.raster(layer)
        assertSame(raster, restored.raster(layer))
        assertEquals(255, raster.alpha(0, 0)); assertEquals(0, raster.alpha(1, 0))
        assertEquals(0, raster.alpha(-1, 0)); assertEquals(0, raster.alpha(0, 1))
        raster.rgba().fill(0)
        assertEquals(255, raster.alpha(0, 0))
        assertEquals(captured.raster(layer).digest, raster.digest)
    }

    @Test fun malformedSourceDescriptorsAndReferencesAreRejected() {
        val bytes = mapOf("pixels" to ModifierAlgorithmResource(ByteArray(8)))
        assertFailsWith<IllegalArgumentException> { ModifierSourceInputs(listOf(layer, layer), bytes) }
        assertFailsWith<IllegalArgumentException> { ModifierSourceInputs(listOf(layer), emptyMap()) }
        assertFailsWith<IllegalArgumentException> { ModifierSourceInputs(listOf(layer), bytes, mapOf("mesh" to "missing")) }
        assertFailsWith<IllegalArgumentException> { ModifierSourceInputs(listOf(layer.copy(width = 3)), bytes) }
        assertFailsWith<IllegalArgumentException> { layer.copy(width = Int.MAX_VALUE, height = Int.MAX_VALUE) }
        assertFailsWith<IllegalArgumentException> { layer.copy(left = Float.NaN) }
        val context = ModifierSourceInputs(listOf(layer), bytes).context()
        assertFailsWith<IllegalArgumentException> {
            ModifierSourceInputs.from(ModifierAlgorithmContext(JsonObject(context.values + ("schema" to JsonPrimitive("unknown"))), context.resources))
        }
    }
}
