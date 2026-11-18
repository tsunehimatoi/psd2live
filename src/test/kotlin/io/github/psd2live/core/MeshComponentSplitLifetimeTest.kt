package io.github.psd2live.core

import io.github.psd2live.algorithms.binding.MeshSplitAlgorithm
import io.github.psd2live.core.modifier.ModifierAlgorithmContext
import io.github.psd2live.core.modifier.ModifierTarget
import io.github.psd2live.core.modifier.RigModifierInstance
import io.github.psd2live.format.model.Canvas
import io.github.psd2live.format.model.Floats
import io.github.psd2live.format.model.Ints
import io.github.psd2live.format.model.Mesh
import io.github.psd2live.format.model.MeshGeometry
import io.github.psd2live.format.model.RigIR
import io.github.psd2live.project.WorkspaceSourceLayer
import kotlinx.coroutines.*
import org.umamo.format.art.*
import org.umamo.runtime.model.DrawableMesh
import kotlin.test.*

class MeshComponentSplitLifetimeTest {
    private fun source(width: Int, height: Int, rgba: ByteArray) = WorkspaceSourceLayer(
        LayerId("layer"), "Layer", "", SourceLayerKind.Raster, true, 0, LayerBounds(10, 20, width, height),
        1f, false, LayerBlend.Normal, ChannelMask.ALL, LayerRaster(width, height, rgba), null, null, false)

    @Test fun manySeparatedIslandsKeepPixelBoundariesAndPiecesOutliveTheScan() = runBlocking {
        val rgba = ByteArray(8 * 2 * 4) { offset -> if (offset % 4 == 3) 255.toByte() else (offset / 4 % 8).toByte() }
        val art = source(8, 2, rgba)
        val vertices = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 3f, 0f, 4f, 0f, 3f, 1f, 6f, 0f, 7f, 0f, 6f, 1f)
        val mesh = DrawableMesh(vertices, vertices.copyOf(), IntArray(9) { it })
        val scan = async {
            val context = currentCoroutineContext()
            MeshComponentSplit.detect(mesh, art, vertices) { context.ensureActive() }!!
        }
        val plan = scan.await()
        scan.join()
        assertFalse(scan.isActive)
        val pieces = plan.pieces(listOf("One", "Two", "Three"))
        assertEquals(listOf(LayerBounds(10, 20, 3, 2), LayerBounds(13, 20, 3, 2), LayerBounds(16, 20, 2, 2)), pieces.map { it.bounds })
        assertEquals(pieces.map { LayerSpace.of(it).canvasBounds() }, plan.componentBounds)
        pieces.forEach { piece ->
            for (y in 0 until piece.raster.height) for (x in 0 until piece.raster.width) {
                val local = (y * piece.raster.width + x) * 4
                val original = (y * 8 + x + piece.bounds.left - 10) * 4
                assertContentEquals(rgba.copyOfRange(original, original + 4), piece.raster.rgba.copyOfRange(local, local + 4))
            }
        }
    }

    @Test fun overlappingProjectionsStillAssignBothTriangleIslandsTheirSourcePixels() {
        val rgba = ByteArray(4 * 4 * 4)
        rgba[3] = 255.toByte()
        rgba[15 * 4 + 3] = 255.toByte()
        val art = source(4, 4, rgba)
        val vertices = floatArrayOf(0f, 0f, 4f, 0f, 0f, 4f, 4f, 4f, 4f, 1f, 1f, 4f)
        val mesh = DrawableMesh(vertices, vertices.copyOf(), IntArray(6) { it })
        val plan = MeshComponentSplit.detect(mesh, art, vertices)!!
        val pieces = plan.pieces(listOf("One", "Two"))
        assertEquals(listOf(LayerBounds(10, 20, 1, 1), LayerBounds(13, 23, 1, 1)), pieces.map { it.bounds })
        assertTrue(pieces.all { it.raster.rgba[3] == 255.toByte() })
    }

    @Test fun sourceSplitAndModifierAgreeOnInterleavedTriangles() {
        val art = source(10, 4, ByteArray(10 * 4 * 4) { 255.toByte() })
        val positions = floatArrayOf(0f, 0f, 3f, 0f, 0f, 3f, 3f, 3f, 6f, 0f, 9f, 0f, 6f, 3f, 9f, 3f)
        val triangles = intArrayOf(4, 5, 6, 0, 1, 2, 5, 7, 6, 1, 3, 2)
        val plan = assertNotNull(MeshComponentSplit.detect(DrawableMesh(positions, positions, triangles), art, positions))
        val mesh = Mesh("source", "Source", null, geometry = MeshGeometry(
            Floats.wrap(positions), Floats.wrap(positions), Ints.wrap(triangles)), offsets = null)
        val ir = RigIR(canvas = Canvas(10f, 4f), parameters = emptyList(), meshes = listOf(mesh))
        val instance = RigModifierInstance("split", "mesh.split", 1, listOf(ModifierTarget("mesh", mesh.id)))
        val modifierPieces = MeshSplitAlgorithm().changes(ir, instance, ModifierAlgorithmContext()) {}.meshes.added
        assertEquals(2, modifierPieces.size)
        modifierPieces.forEachIndexed { index, piece ->
            val expected = plan.ownerByVertex.indices.filter { plan.ownerByVertex[it] == index }
                .flatMap { listOf(positions[it * 2], positions[it * 2 + 1]) }
            val geometry = assertNotNull(piece.geometry)
            assertContentEquals(expected.toFloatArray(), geometry.positions.shared())
            assertContentEquals(intArrayOf(0, 1, 2, 1, 3, 2), geometry.indices.shared())
        }
    }
}
