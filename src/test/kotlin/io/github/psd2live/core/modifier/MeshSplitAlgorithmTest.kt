package io.github.psd2live.core.modifier

import io.github.psd2live.algorithms.binding.MeshSplitAlgorithm
import io.github.psd2live.format.model.BlendBinding
import io.github.psd2live.format.model.Canvas
import io.github.psd2live.format.model.ChildRef
import io.github.psd2live.format.model.Floats
import io.github.psd2live.format.model.Ints
import io.github.psd2live.format.model.KeyAxis
import io.github.psd2live.format.model.KeyCell
import io.github.psd2live.format.model.KeyGrid
import io.github.psd2live.format.model.Mesh
import io.github.psd2live.format.model.MeshGeometry
import io.github.psd2live.format.model.MeshOffsets
import io.github.psd2live.format.model.MeshShape
import io.github.psd2live.format.model.Part
import io.github.psd2live.format.model.RenderGroup
import io.github.psd2live.format.model.RenderMesh
import io.github.psd2live.format.model.Rgb
import io.github.psd2live.format.model.RigIR
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MeshSplitAlgorithmTest {

    @Test
    fun `splits disconnected mesh islands and updates parts rootChildren and renderRoot`() {
        val originalMesh = Mesh(
            id = "mesh_twins",
            name = "Twins",
            parent = null,
            geometry = MeshGeometry(
                positions = Floats.values(
                    // Island B (lower on canvas, y = 50..60)
                    0f, 50f, 10f, 50f, 5f, 60f,
                    // Island A (higher on canvas, y = 10..20)
                    0f, 10f, 10f, 10f, 5f, 20f,
                ),
                uvs = Floats.values(
                    0.0f, 0.5f, 0.1f, 0.5f, 0.05f, 0.6f,
                    0.0f, 0.1f, 0.1f, 0.1f, 0.05f, 0.2f,
                ),
                indices = Ints.values(0, 1, 2, 3, 4, 5),
            ),
            offsets = KeyGrid(
                axes = listOf(KeyAxis("ParamX", Floats.values(-1f, 1f))),
                cells = listOf(
                    KeyCell(
                        Ints.values(0),
                        MeshOffsets(Floats.values(1f, 2f, 3f, 4f, 5f, 6f, 10f, 20f, 30f, 40f, 50f, 60f)),
                    ),
                ),
            ),
            shapes = listOf(
                BlendBinding(
                    parameter = "ParamBlend",
                    keys = Floats.values(0f, 1f),
                    neutralIndex = 0,
                    shapes = listOf(
                        null,
                        MeshShape(
                            deltas = Floats.values(0f, 1f, 0f, 2f, 0f, 3f, 0f, 4f, 0f, 5f, 0f, 6f),
                            drawOrder = 0f,
                            opacity = 0f,
                            multiply = Rgb.White,
                            screen = Rgb.Black,
                        ),
                    ),
                ),
            ),
            page = 0,
        )

        val ir = RigIR(
            canvas = Canvas(100f, 100f),
            parameters = emptyList(),
            parts = listOf(Part(id = "part_root", name = "Root", children = listOf(ChildRef.MeshRef("mesh_twins")))),
            rootChildren = listOf(ChildRef.MeshRef("mesh_twins")),
            meshes = listOf(originalMesh),
            renderRoot = RenderGroup(null, RigIR.DEFAULT_DRAW_ORDER, listOf(RenderMesh("mesh_twins"))),
        )

        val instance = RigModifierInstance(
            id = "m_split",
            type = "mesh.split",
            version = 1,
            targets = listOf(ModifierTarget("mesh", "mesh_twins")),
            config = buildJsonObject {
                putJsonArray("pieceIds") { add(kotlinx.serialization.json.JsonPrimitive("top_piece")); add(kotlinx.serialization.json.JsonPrimitive("bottom_piece")) }
                putJsonArray("names") { add(kotlinx.serialization.json.JsonPrimitive("Top")); add(kotlinx.serialization.json.JsonPrimitive("Bottom")) }
            },
        )

        val changes = MeshSplitAlgorithm().changes(ir, instance, ModifierAlgorithmContext()) {}
        assertEquals(listOf("mesh_twins"), changes.meshes.removed.map { it.id })
        assertEquals(listOf("top_piece", "bottom_piece"), changes.meshes.added.map { it.id })
        assertEquals(listOf("Top", "Bottom"), changes.meshes.added.map { it.name })

        val result = changes.apply(ir)
        assertEquals(listOf("top_piece", "bottom_piece"), result.meshes.map { it.id })

        val top = result.meshes[0]
        assertEquals(Floats.values(0f, 10f, 10f, 10f, 5f, 20f), top.geometry?.positions)
        assertEquals(Ints.values(0, 1, 2), top.geometry?.indices)
        assertEquals(Floats.values(10f, 20f, 30f, 40f, 50f, 60f), top.offsets?.cells?.single()?.form?.deltas)
        assertEquals(Floats.values(0f, 4f, 0f, 5f, 0f, 6f), top.shapes.single().shapes[1]?.deltas)

        val bottom = result.meshes[1]
        assertEquals(Floats.values(0f, 50f, 10f, 50f, 5f, 60f), bottom.geometry?.positions)
        assertEquals(Ints.values(0, 1, 2), bottom.geometry?.indices)
        assertEquals(Floats.values(1f, 2f, 3f, 4f, 5f, 6f), bottom.offsets?.cells?.single()?.form?.deltas)

        assertEquals(
            listOf(ChildRef.MeshRef("top_piece"), ChildRef.MeshRef("bottom_piece")),
            result.parts.single().children,
        )
        assertEquals(
            listOf(ChildRef.MeshRef("top_piece"), ChildRef.MeshRef("bottom_piece")),
            result.rootChildren,
        )
        assertEquals(
            listOf(RenderMesh("top_piece"), RenderMesh("bottom_piece")),
            result.renderRoot.children,
        )
    }

    @Test
    fun `single connected island returns empty changes`() {
        val singleMesh = Mesh(
            id = "mesh_single",
            name = "Single",
            parent = null,
            geometry = MeshGeometry(
                positions = Floats.values(0f, 0f, 10f, 0f, 10f, 10f, 0f, 10f),
                uvs = Floats.values(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f),
                indices = Ints.values(0, 1, 2, 0, 2, 3),
            ),
            offsets = null,
        )
        val ir = RigIR(
            canvas = Canvas(100f, 100f),
            parameters = emptyList(),
            meshes = listOf(singleMesh),
        )
        val instance = RigModifierInstance(
            id = "m_split",
            type = "mesh.split",
            version = 1,
            targets = listOf(ModifierTarget("mesh", "mesh_single")),
        )
        val changes = MeshSplitAlgorithm().changes(ir, instance, ModifierAlgorithmContext()) {}
        assertTrue(changes.meshes.isEmpty)
        assertNotNull(MeshSplitAlgorithmPlugin.definition)
    }
}
