package example

import io.github.psd2live.algorithms.binding.ScaffoldParts
import io.github.psd2live.algorithms.binding.ScaffoldParts.Slot
import io.github.psd2live.algorithms.binding.ScaffoldPartsContext
import kotlinx.serialization.json.*
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.OrgChild
import kotlin.test.*

class ScaffoldPartsContextTest {
    @Test fun capturedClassificationsPreserveTiesAndBuildEmptySlots() {
        val materials = listOf(
            material("unknown", Slot.UNKNOWN, 0),
            material("faceB", Slot.FACE, 2),
            material("faceA", Slot.FACE, 2),
            material("body", Slot.BODY, 3),
            material("faceFirst", Slot.FACE, -1),
        )
        val label = { key: String -> "名称 ${key.substringAfterLast('.')}" }
        val captured = ScaffoldPartsContext.capture(materials, label)
        val encoded = Json.parseToJsonElement(captured.toString()).jsonObject
        assertEquals(materials, ScaffoldPartsContext.decode(encoded).materials)
        val result = ScaffoldPartsContext.build(encoded)
        assertEquals(ScaffoldParts.build(materials, label), result)
        assertEquals(listOf("faceFirst", "faceB", "faceA").map { OrgChild.Drawable(DrawableId(it)) },
            result.parts.single { it.id == Slot.FACE.id }.children)
        assertEquals(listOf("body", "unknown").map { OrgChild.Drawable(DrawableId(it)) },
            result.parts.single { it.id == Slot.BODY.id }.children)
        assertTrue(result.parts.single { it.id == Slot.BACK_HAIR.id }.children.isEmpty())
        assertEquals("名称 backHair", result.parts.single { it.id == Slot.BACK_HAIR.id }.name)
    }

    @Test fun unknownSlotsAndDuplicateIdsFailDuringDecode() {
        val captured = ScaffoldPartsContext.capture(listOf(material("a", Slot.FACE, 1)), { it })
        val record = captured.getValue("materials").jsonArray.single().jsonObject
        val invalid = JsonObject(captured + ("materials" to JsonArray(
            listOf(JsonObject(record + ("slot" to JsonPrimitive("NEW_SLOT")))),
        )))
        assertFailsWith<IllegalArgumentException> { ScaffoldPartsContext.build(invalid) }
        val duplicated = JsonObject(captured + ("materials" to JsonArray(listOf(record, record))))
        assertFailsWith<IllegalArgumentException> { ScaffoldPartsContext.decode(duplicated) }
    }

    @Test fun captureAndBuildCheckCancellation() {
        val materials = listOf(material("a", Slot.FACE, 1), material("b", Slot.BODY, 2))
        val captured = ScaffoldPartsContext.capture(materials, { it })
        var captureChecks = 0
        assertFailsWith<InterruptedException> {
            ScaffoldPartsContext.capture(materials, { it }) { if (++captureChecks == 2) throw InterruptedException() }
        }
        var buildChecks = 0
        assertFailsWith<InterruptedException> {
            ScaffoldPartsContext.build(captured) { if (++buildChecks == 4) throw InterruptedException() }
        }
        assertEquals(7, ScaffoldPartsContext.build(captured).parts.size)
    }

    private fun material(id: String, slot: Slot, order: Int) = ScaffoldParts.Material(DrawableId(id), slot, order)
}
