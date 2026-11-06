package example

import io.github.psd2live.algorithms.binding.Bounds
import io.github.psd2live.algorithms.binding.ScaffoldPairs
import io.github.psd2live.algorithms.binding.Side
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.PartId
import kotlin.test.*

class ScaffoldPairsTest {
    private val parent = DeformerId("face")
    private val part = PartId("PartFace")
    private val parentFrame = Bounds(0f, 0f, 100f, 100f)

    @Test fun matchedLayersBuildStaticNormalizedLatticeInSourceOrder() {
        val result = ScaffoldPairs.build(
            listOf(
                layer("r", "Ｐｉｎ-Ｒ", Side.RIGHT, Bounds(40f, 30f, 50f, 40f)),
                layer("l", "pin-left", Side.LEFT, Bounds(10f, 20f, 20f, 30f)),
                layer("other", "pin-left", Side.LEFT, Bounds(0f, 0f, 1f, 1f), DeformerId("body")),
                layer("center", "pin", Side.NONE, Bounds(0f, 0f, 1f, 1f)),
            ),
            setOf("DeformPair_Pin"),
            { "Pair: $it" },
        ).single()

        assertEquals(listOf("r", "l"), result.layerIds)
        assertEquals(Bounds(6f, 16f, 54f, 44f), result.bounds)
        val warp = result.warp
        assertEquals(DeformerId("DeformPair_Pin_2"), warp.id)
        assertEquals("Pair: Pin", warp.name)
        assertEquals(parent, warp.parent)
        assertEquals(part, warp.partId)
        val grid = assertNotNull(warp.geometryGrid)
        assertTrue(grid.axes.isEmpty())
        val lattice = grid.cells.single().form.controlPoints
        assertEquals(32, lattice.size)
        for (row in 0..3) for (column in 0..3) {
            val offset = (row * 4 + column) * 2
            assertEquals(0.06f + (column.toFloat() / 3) * (0.54f - 0.06f), lattice[offset])
            assertEquals(0.16f + (row.toFloat() / 3) * (0.44f - 0.16f), lattice[offset + 1])
        }
    }

    @Test fun nonAsciiPairsUseSemanticFallbackAndRespectCancellation() {
        val layers = listOf(
            layer("left", "左饰品", Side.LEFT, Bounds(0f, 0f, 10f, 10f)),
            layer("right", "右饰品", Side.RIGHT, Bounds(190f, 190f, 200f, 200f)),
        )
        val pair = ScaffoldPairs.build(layers, emptySet(), { it }).single()
        assertEquals(DeformerId("DeformPair_HeadAccessory"), pair.warp.id)
        assertEquals(Bounds(-8f, -8f, 208f, 208f), pair.bounds)
        assertFailsWith<InterruptedException> {
            ScaffoldPairs.build(layers, emptySet(), { it }) { throw InterruptedException() }
        }
    }

    private fun layer(id: String, name: String, side: Side, bounds: Bounds, defaultParent: DeformerId = parent) =
        ScaffoldPairs.Layer(id, name, "HEAD_ACCESSORY", side, bounds, defaultParent, parentFrame, part)
}
