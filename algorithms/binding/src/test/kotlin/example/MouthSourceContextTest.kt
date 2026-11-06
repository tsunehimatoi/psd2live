package example

import io.github.psd2live.algorithms.binding.Bounds
import io.github.psd2live.algorithms.binding.LayeredMouthFrame
import io.github.psd2live.algorithms.binding.MouthCurve
import io.github.psd2live.algorithms.binding.MouthFit
import io.github.psd2live.algorithms.binding.MouthOptions
import io.github.psd2live.algorithms.binding.MouthSourceContext
import io.github.psd2live.core.modifier.ModifierAlgorithmContext
import io.github.psd2live.core.modifier.ModifierAlgorithmResource
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.umamo.runtime.model.ParameterId
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MouthSourceContextTest {
    private val upper = List(MouthFit.SAMPLES) { i -> -.12f * profile(MouthFit.u(i)) }
    private val lower = List(MouthFit.SAMPLES) { i -> .20f * profile(MouthFit.u(i)) }
    private val options = MouthOptions(early = .21f, formStrength = .83f, curve = MouthCurve.preset("w"),
        exactClose = false, vowels = true, capture = true, jaw = true, jawShare = .19f,
        depth = true, depthAmount = .72f, spring = true, tongue = true, inside = .31f,
        reach = 1.17f, teethWiden = .23f, narrow = .13f)

    @Test
    fun `captured measurements preserve actual layered and whole-mouth motion`() {
        for (layered in listOf(null, layeredFrame())) {
            val captured = MouthSourceContext(MouthFit(10f, 20f, 1f, 0f, 40f, upper, lower, layered),
                options, lineWidth = if (layered == null) null else .02f)
            val encoded = captured.encode()
            val decoded = MouthSourceContext.decode(encoded)
            assertEquals(captured, decoded)
            assertEquals(encoded, decoded.encode())
            val mouthForm = ParameterId("ParamMouthForm")
            val mouthOpen = ParameterId("ParamMouthOpenY")
            fun points(context: MouthSourceContext) = context.rig().let { rig ->
                rig.keyed(mouthForm, mouthOpen) { pose -> rig.point(27f, 18f, pose) }
            }
            val before = points(captured)
            val after = points(decoded)
            assertTrue(before.cells.all { it.form.first.isFinite() && it.form.second.isFinite() })
            assertTrue(before.cells.map { it.form }.distinct().size > 1)
            assertEquals(before.axes.map { it.parameterId }, after.axes.map { it.parameterId })
            before.axes.zip(after.axes).forEach { (a, b) -> assertContentEquals(a.keys, b.keys) }
            assertEquals(before.cells.size, after.cells.size)
            before.cells.zip(after.cells).forEach { (a, b) ->
                assertContentEquals(a.coordinate, b.coordinate)
                assertEquals(a.form, b.form)
            }
        }
    }

    @Test
    fun `malformed measurement resources cannot be used to construct a rig`() {
        val fit = MouthFit(10f, 20f, 1f, 0f, 40f, upper, lower)
        val valid = MouthSourceContext(fit, options, null).encode()
        val bytes = valid.resources.values.single().read()
        fun encoded(payload: ByteArray, digest: String? = null): ModifierAlgorithmContext {
            val resource = ModifierAlgorithmResource(payload)
            val name = digest ?: resource.digest
            return ModifierAlgorithmContext(buildJsonObject {
                put("schema", MouthSourceContext.SCHEMA)
                put("measurement", name)
            }, mapOf(name to resource))
        }
        assertFailsWith<IllegalArgumentException> { MouthSourceContext.decode(ModifierAlgorithmContext(valid.values)) }
        assertFailsWith<IllegalArgumentException> { MouthSourceContext.decode(encoded(bytes, "bad-digest")) }
        assertFailsWith<IllegalArgumentException> { MouthSourceContext.decode(encoded(bytes + 0.toByte())) }
        assertFailsWith<IllegalArgumentException> { MouthSourceContext.decode(encoded(bytes.copyOf(bytes.size - 1))) }
        val nonFinite = bytes.copyOf().also { byteArrayOf(0x7f, -0x80, 0, 0).copyInto(it) }
        assertFailsWith<IllegalArgumentException> { MouthSourceContext.decode(encoded(nonFinite)) }
        assertFailsWith<IllegalArgumentException> { MouthSourceContext(fit.copy(upper = emptyList()), options, null).encode() }
    }

    private fun layeredFrame(): LayeredMouthFrame {
        fun rim(world: Boolean, height: Float) = FloatArray(130) { i ->
            val u = (i / 2) / 64f
            if (i % 2 == 0) u * 40f + (if (world) 10f else -20f)
            else profile(u) * height + (if (world) 20f else 0f)
        }
        return LayeredMouthFrame(hasLines = true, centerX = 30f, centerY = 20f, exX = 1f, exY = 0f,
            frameWidth = 40f, upperWorld65 = rim(true, -5f), lowerWorld65 = rim(true, 8f),
            upperLocal65 = rim(false, -5f), lowerLocal65 = rim(false, 8f), lineNorm = .02f,
            openColsX = floatArrayOf(-15f, 0f, 15f), openTopY = floatArrayOf(-1f, -5f, -1f),
            openBotY = floatArrayOf(2f, 8f, 2f), upperLipBoundsLocal = Bounds(-20f, -7f, 20f, 0f),
            lowerLipBoundsLocal = Bounds(-20f, 0f, 20f, 10f), tongueBoundsLocal = Bounds(-6f, 3f, 6f, 7f),
            hUtVis0 = 3f, utSideReserve = 1f, hLtVis0 = 2f)
    }

    private fun profile(u: Float) = 4f * u * (1f - u)
}
