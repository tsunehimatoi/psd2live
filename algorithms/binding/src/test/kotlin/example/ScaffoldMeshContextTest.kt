package example

import io.github.psd2live.algorithms.binding.Bounds
import io.github.psd2live.algorithms.binding.ScaffoldArms
import io.github.psd2live.algorithms.binding.ScaffoldMesh
import io.github.psd2live.algorithms.binding.ScaffoldMesh.Geometry
import io.github.psd2live.algorithms.binding.ScaffoldMeshContext
import io.github.psd2live.core.modifier.ModifierAlgorithmContext
import io.github.psd2live.core.modifier.ModifierAlgorithmResource
import io.github.psd2live.format.compile.RigIrBinary
import kotlinx.serialization.json.*
import org.umamo.runtime.model.DeformerId
import kotlin.test.*

class ScaffoldMeshContextTest {
    private val bounds = Bounds(0f, 0f, 100f, 100f)
    private val raw = ScaffoldMesh.RawMesh(floatArrayOf(0.1f, 20f, 1f, -0f, 2f, 4f),
        floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f), intArrayOf(0, 1, 2))
    private val parents = mapOf("source" to ScaffoldArms.Parent(DeformerId("frame"), bounds))
    private val materials = listOf(
        Geometry.Zero,
        Geometry.Eye(true, bounds, listOf(bounds), 10f, -2, floatArrayOf(9f, 1f, 11f), 34f, 38f, 88f),
        Geometry.Iris(65f, 80f, 4.5f, 11f),
        Geometry.Brow(10f, 12f),
        Geometry.Mouth(Bounds(50f, 45f, 80f, 95f),
            (0..6).map { ScaffoldMesh.CurvePoint(it / 6f, 0f) }, 8f, 48f, 1.8f, 10.5f, 7f),
    ).mapIndexed { index, geometry -> ScaffoldMeshContext.Material("mesh-$index", "source", raw, geometry) }

    @Test fun binaryAndJsonRoundTripKeepsSourceTopologyAndNeutralBits() {
        val captured = ScaffoldMeshContext.capture(materials)
        assertEquals(setOf("mesh_inputs"), captured.values.keys)
        assertEquals(setOf("source_meshes"), captured.resources.keys)
        val resource = RigIrBinary.decode(captured.resources.getValue("source_meshes").read())
        assertTrue(resource.parts.isEmpty() && resource.deformers.isEmpty() && resource.rootChildren.isEmpty())
        assertTrue(resource.meshes.all { it.parent == null && it.offsets == null && it.tile == null })
        assertContentEquals(raw.rigPositions.map { it.toRawBits() }.toIntArray(),
            resource.meshes.first().geometry!!.positions.toArray().map { it.toRawBits() }.toIntArray())
        val parsed = ModifierAlgorithmContext(Json.parseToJsonElement(captured.values.toString()).jsonObject, captured.resources)
        val output = ScaffoldMeshContext.build(parsed, parents)
        assertEquals(materials.map { it.id }, output.map { it.id.raw })
        for ((material, drawable) in materials.zip(output)) {
            val parent = parents.getValue(material.sourceId)
            val (mesh, grid) = ScaffoldMesh.build(material.raw, parent, material.geometry)
            assertEquals(parent.id, drawable.parentDeformerId)
            assertContentEquals(mesh.positions, drawable.mesh!!.positions)
            assertContentEquals(mesh.uvs, drawable.mesh!!.uvs)
            assertContentEquals(mesh.indices, drawable.mesh!!.indices)
            val actual = drawable.geometryGrid!!
            assertTrue(actual.axes.isEmpty())
            assertContentEquals(grid.cells.single().form.positionDeltas.map { it.toRawBits() }.toIntArray(),
                actual.cells.single().form.positionDeltas.map { it.toRawBits() }.toIntArray())
        }
    }

    @Test fun malformedInputsAndUnexpectedBoundDataAreRejected() {
        assertFailsWith<IllegalArgumentException> { ScaffoldMeshContext.capture(listOf(materials.first(), materials.first())) }
        assertFailsWith<IllegalArgumentException> {
            ScaffoldMeshContext.capture(listOf(materials.first().copy(raw = raw.copy(atlasUvs = FloatArray(6) { Float.NaN }))))
        }
        assertFailsWith<IllegalArgumentException> {
            ScaffoldMeshContext.capture(listOf(materials.first().copy(raw = raw.copy(triangles = intArrayOf(0, 1, 4)))))
        }
        val captured = ScaffoldMeshContext.capture(listOf(materials.first()))
        assertFailsWith<IllegalArgumentException> { ScaffoldMeshContext.build(captured, emptyMap()) }
        val resource = RigIrBinary.decode(captured.resources.getValue("source_meshes").read())
        val injected = resource.copy(meshes = resource.meshes.map { it.copy(parent = "prebound") })
        val invalidResource = ModifierAlgorithmContext(captured.values,
            mapOf("source_meshes" to ModifierAlgorithmResource(RigIrBinary.encode(injected))))
        assertFailsWith<IllegalArgumentException> { ScaffoldMeshContext.build(invalidResource, parents) }

        val inputs = captured.values.getValue("mesh_inputs").jsonObject
        val record = inputs.getValue("materials").jsonArray.single().jsonObject
        for (geometry in listOf(
            buildJsonObject { put("kind", "unsupported") },
            buildJsonObject { put("kind", "zero"); put("extra", true) },
            buildJsonObject { put("kind", "brow"); put("height", Json.parseToJsonElement("1e999")); put("lift", 12) },
        )) {
            val invalidRecord = JsonObject(record + ("geometry" to geometry))
            val invalidInputs = JsonObject(inputs + ("materials" to JsonArray(listOf(invalidRecord))))
            val invalid = ModifierAlgorithmContext(JsonObject(mapOf("mesh_inputs" to invalidInputs)), captured.resources)
            assertFailsWith<IllegalArgumentException> { ScaffoldMeshContext.build(invalid, parents) }
        }
    }

    @Test fun decodingAndGenerationObserveCancellation() {
        val captured = ScaffoldMeshContext.capture(materials)
        var calls = 0
        assertFailsWith<InterruptedException> {
            ScaffoldMeshContext.build(captured, parents) { if (++calls == 4) throw InterruptedException() }
        }
        calls = 0
        assertFailsWith<InterruptedException> {
            ScaffoldMeshContext.build(captured, parents) { if (++calls == 12) throw InterruptedException() }
        }
    }
}
