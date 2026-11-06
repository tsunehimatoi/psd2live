package example

import io.github.psd2live.algorithms.binding.*
import io.github.psd2live.algorithms.binding.ScaffoldParts.Slot
import io.github.psd2live.targets.cubism.PuppetIr
import kotlinx.serialization.json.*
import org.umamo.runtime.model.DeformerId
import kotlin.test.*

class ScaffoldBranchesContextTest {
    private val body = Bounds(0f, 0f, 200f, 240f)
    private val face = Bounds(45f, 26f, 159f, 91f)
    private val hair = Bounds(25f, 18f, 174f, 88f)
    private val armLeft = Bounds(145f, 102f, 198f, 144f)
    private val armRight = Bounds(3f, 100f, 58f, 151f)
    private val head = ScaffoldHead.Input(body, Bounds(20f, 15f, 180f, 100f), face, ScaffoldHead.Point(100f, 73f),
        ScaffoldHead.Point(100f, 73f), 0f, false,
        listOf(ScaffoldHead.Region(ScaffoldHead.Feature.EYE, Side.NONE, face, "eyes")),
        frontHair = ScaffoldHead.Hair(hair, sway = false))
    private val layers = listOf(
        layer("sleeve-r", "Sleeve_R", "HANDWEAR", Side.RIGHT, armRight, Slot.BODY),
        layer("sleeve-l", "Sleeve_L", "HANDWEAR", Side.LEFT, armLeft, Slot.BODY),
        layer("locks-r", "Locks_R", "FRONT_HAIR", Side.RIGHT, Bounds(30f, 22f, 80f, 80f), Slot.FRONT_HAIR),
        layer("locks-l", "Locks_L", "FRONT_HAIR", Side.LEFT, Bounds(120f, 25f, 168f, 83f), Slot.FRONT_HAIR),
        layer("eye-l", "Eye_L", "EYEWHITE", Side.LEFT, face, Slot.FACE),
        layer("eye-r", "Eye_R", "EYEWHITE", Side.RIGHT, face, Slot.FACE),
    )
    private val base = ScaffoldBody.build(body, null, { it }) + ScaffoldHead.build(head, { it }).deformers

    @Test fun derivesPairAndArmParentsFromMeasurementsAndRehomesHairWithoutSway() {
        val captured = ScaffoldBranchesContext.capture(layers, body, null, head, { "组 $it" }, { "$it hang" })
        val encoded = Json.parseToJsonElement(captured.toString()).jsonObject
        val branches = ScaffoldBranchesContext.build(encoded, base)
        val sleevePair = DeformerId("DeformPair_Sleeve")
        val hairPair = DeformerId("DeformPair_Locks")
        val hairFollow = DeformerId("DeformHairFrontFollow")
        assertEquals(listOf("DeformPair_Sleeve", "DeformPair_Locks", "DeformArmHang_L", "DeformArmHang_R"),
            branches.deformers.map { it.id.raw })
        val armsFrame = padded(armRight.union(armLeft))
        val locksFrame = padded(layers[2].bounds.union(layers[3].bounds))
        val expected = listOf(
            ScaffoldPairs.identityWarp(sleevePair, "组 Sleeve", ScaffoldHead.breathId, armsFrame, body, Slot.BODY.id),
            ScaffoldPairs.identityWarp(hairPair, "组 Locks", hairFollow, locksFrame, hair, Slot.FRONT_HAIR.id),
            ScaffoldPairs.identityWarp(DeformerId("DeformArmHang_L"), "Sleeve_L hang", sleevePair,
                armLeft.expanded(0.06f), armsFrame, Slot.BODY.id, 6, 3),
            ScaffoldPairs.identityWarp(DeformerId("DeformArmHang_R"), "Sleeve_R hang", sleevePair,
                armRight.expanded(0.06f), armsFrame, Slot.BODY.id, 6, 3),
        )
        assertEquals(expected.map(PuppetIr::deformerToIr), branches.deformers.map(PuppetIr::deformerToIr))
        assertEquals(DeformerId("DeformArmHang_L"), branches.parents.getValue("sleeve-l").id)
        assertEquals(hairPair, branches.parents.getValue("locks-r").id)
        assertEquals(ScaffoldHead.featureId(ScaffoldHead.Feature.EYE, Side.NONE), branches.parents.getValue("eye-r").id)
    }

    @Test fun invalidLayerEnumFailsAndBuildCanBeCanceled() {
        val capture = ScaffoldBranchesContext.capture(layers, body, null, head, { it }, { it })
        val first = capture.getValue("layers").jsonArray.first().jsonObject
        val invalid = JsonObject(first + ("slot" to JsonPrimitive("UNRECOGNIZED")))
        assertFailsWith<IllegalArgumentException> {
            ScaffoldBranchesContext.build(JsonObject(capture + ("layers" to JsonArray(listOf(invalid)))), base)
        }
        var checks = 0
        assertFailsWith<InterruptedException> {
            ScaffoldBranchesContext.build(capture, base) { if (++checks == 4) throw InterruptedException() }
        }
    }

    private fun padded(bounds: Bounds): Bounds {
        val x = maxOf(bounds.width * 0.04f, 4f)
        val y = maxOf(bounds.height * 0.04f, 4f)
        return Bounds(bounds.left - x, bounds.top - y, bounds.right + x, bounds.bottom + y)
    }

    private fun layer(id: String, name: String, tag: String, side: Side, bounds: Bounds, slot: Slot) =
        ScaffoldBranchesContext.Layer(id, name, tag, side, bounds, slot)
}
