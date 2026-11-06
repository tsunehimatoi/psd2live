package example

import io.github.psd2live.algorithms.binding.Bounds
import io.github.psd2live.algorithms.binding.ScaffoldBody
import io.github.psd2live.algorithms.binding.ScaffoldFramesContext
import io.github.psd2live.algorithms.binding.ScaffoldHead
import io.github.psd2live.algorithms.binding.Side
import io.github.psd2live.targets.cubism.PuppetIr
import kotlinx.serialization.json.*
import org.umamo.runtime.model.DeformerId
import kotlin.test.*

class ScaffoldFramesContextTest {
    private val body = Bounds(0.3f, 2.4f, 231.7f, 408f)
    private val head = ScaffoldHead.Input(
        body, Bounds(41f, 20f, 190f, 157f), Bounds(56f, 44f, 151f, 129f),
        ScaffoldHead.Point(103.2f, 98.7f), ScaffoldHead.Point(99.5f, 102.2f), 8.125f, true,
        listOf(
            ScaffoldHead.Region(ScaffoldHead.Feature.EYE, Side.RIGHT, Bounds(59f, 53f, 81f, 69f), "right eye"),
            ScaffoldHead.Region(ScaffoldHead.Feature.EYE, Side.LEFT, Bounds(110f, 55f, 132f, 72f), "left eye"),
            ScaffoldHead.Region(ScaffoldHead.Feature.IRIS, Side.LEFT, Bounds(117f, 58f, 123f, 65f), "iris", "gaze"),
        ),
        frontHair = ScaffoldHead.Hair(Bounds(40f, 40f, 170f, 91f), sway = false),
        backHair = ScaffoldHead.Hair(Bounds(34f, 41f, 180f, 174f), sway = true),
    )
    private val standing = ScaffoldBody.Standing(
        Bounds(43f, 230f, 168f, 410f),
        listOf(ScaffoldBody.Leg(ScaffoldBody.Joint(98.123456789123, 252.75), ScaffoldBody.Joint(100.0, 317.25),
            ScaffoldBody.Joint(99.5, 393.125), 407.75)),
        19.123456789012,
    )
    private val label = { key: String -> "名称 ${key.substringAfterLast('.')}" }

    @Test fun serializedMeasurementsProduceTheSameFramesAndOnlyCaptureUsedLabels() {
        val capture = ScaffoldFramesContext.capture(body, standing, head, label)
        val decoded = Json.parseToJsonElement(capture.toString()).jsonObject
        val stored = decoded.getValue("standing").jsonObject
        assertEquals(standing.kneesInRest, stored.getValue("knees_in_rest").jsonPrimitive.double)
        val storedHip = stored.getValue("legs").jsonArray.single().jsonObject.getValue("hip").jsonArray
        assertEquals(standing.legs.single().hip.x, storedHip.first().jsonPrimitive.double)
        val labels = decoded.getValue("labels").jsonObject.keys
        assertTrue("model.deformer.eyes" in labels)
        assertTrue("model.deformer.backHairPhysics" in labels)
        assertTrue("model.deformer.frontHairPhysics" !in labels)
        assertTrue("model.deformer.brows" !in labels)

        val direct = ScaffoldBody.build(body, standing, label) + ScaffoldHead.build(head, label).deformers
        val actual = ScaffoldFramesContext.build(decoded)
        assertEquals(direct.map(PuppetIr::deformerToIr), actual.map(PuppetIr::deformerToIr))
        assertTrue(actual.all { ScaffoldFramesContext.owns(it.id) })
        assertTrue(ScaffoldFramesContext.owns(DeformerId("DeformEarOcclusionBoth")))
        assertFalse(ScaffoldFramesContext.owns(DeformerId("DeformArmHang_L")))
    }

    @Test fun unknownSchemaEnumsAndInvalidCoordinatesAreRejected() {
        val capture = ScaffoldFramesContext.capture(body, null, head, label)
        assertFailsWith<IllegalArgumentException> {
            ScaffoldFramesContext.build(replace(capture, "schema", JsonPrimitive("scaffold_frames/99")))
        }
        val source = capture.getValue("head").jsonObject
        val region = source.getValue("regions").jsonArray.first().jsonObject
        val invalid = replace(source, "regions", JsonArray(listOf(replace(region, "side", JsonPrimitive("UNKNOWN")))))
        assertFailsWith<IllegalArgumentException> {
            ScaffoldFramesContext.build(replace(capture, "head", invalid))
        }
        assertFailsWith<IllegalArgumentException> {
            val overflow = Json.parseToJsonElement("1e999")
            ScaffoldFramesContext.build(replace(capture, "head", replace(source, "initial_angle", overflow)))
        }
        assertFailsWith<IllegalArgumentException> {
            ScaffoldFramesContext.build(replace(capture, "head", replace(source, "feature_displacement", JsonPrimitive("true"))))
        }
        assertFailsWith<IllegalArgumentException> {
            ScaffoldFramesContext.capture(body.copy(right = body.left), null, head, label)
        }
        assertFailsWith<IllegalArgumentException> {
            ScaffoldFramesContext.capture(body, standing.copy(kneesInRest = Double.NaN), head, label)
        }
    }

    @Test fun decodingAndGeometryGenerationObserveCancellation() {
        val capture = ScaffoldFramesContext.capture(body, standing, head, label)
        var checks = 0
        assertFailsWith<InterruptedException> {
            ScaffoldFramesContext.build(capture) { if (++checks == 2) throw InterruptedException() }
        }
        checks = 0
        assertFailsWith<InterruptedException> {
            ScaffoldFramesContext.build(capture) { if (++checks == 10) throw InterruptedException() }
        }
    }

    private fun replace(value: JsonObject, key: String, replacement: JsonElement) =
        JsonObject(value + (key to replacement))
}
