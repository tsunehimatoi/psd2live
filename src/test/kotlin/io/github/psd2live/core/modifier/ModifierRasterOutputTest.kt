package io.github.psd2live.core.modifier

import io.github.psd2live.core.PipelineConfig
import io.github.psd2live.format.model.*
import kotlin.test.*

class ModifierRasterOutputTest {
    private val geometry = MeshGeometry(Floats.values(0f, 0f, 1f, 0f, 0f, 1f),
        Floats.values(0f, 0f, 2f, 0f, 0f, 2f), Ints.values(0, 1, 2))
    private val owner = Mesh("owner", "Owner", null, geometry = geometry, offsets = null)
    private val input = RigIR(Canvas(8f, 8f), emptyList(), meshes = listOf(owner),
        rootChildren = listOf(ChildRef.MeshRef(owner.id)))
    private fun output(owner: Mesh = this.owner) = ModifierRasterOutput(owner,
        this.owner.copy(id = "generated"), SourceLayer("asset", "Generated", "", 2, 3, 2, 2, true),
        ModifierAlgorithmResource(ByteArray(16) { -1 }))

    @Test fun rasterPixelsBecomeAnEditableSourceAndAtlasBinding() {
        val result = RigModifierRasterAssets.attach(input, listOf(output()), PipelineConfig(atlasSize = 64))
        val mesh = result.meshes.single { it.id == "generated" }
        val tile = result.textures.tiles.single()
        val placement = assertNotNull(tile.placement)
        val page = result.textures.pages.single()
        assertEquals(listOf(ChildRef.MeshRef("generated"), ChildRef.MeshRef("owner")), result.rootChildren)
        assertEquals(input.meshes.single(), result.meshes.single { it.id == "owner" })
        assertEquals("asset", result.authoring.sources.single().layers.single().key)
        assertEquals(tile.id, mesh.tile)
        assertEquals(0, result.textures.bindings[mesh.id])
        assertEquals(placement.x / page.width, mesh.geometry!!.uvs[0])
        assertEquals((placement.x + 2 * placement.scaleX) / page.width, mesh.geometry!!.uvs[2])
        assertEquals(16, result.textures.tileArt.single().rgba.size)
        assertTrue(page.png.size > 0)
        assertTrue(input.textures.pages.isEmpty())
    }

    @Test fun staleOwnersDuplicateOutputsAndCancellationPublishNothing() {
        assertFailsWith<IllegalArgumentException> {
            RigModifierRasterAssets.attach(input, listOf(output(owner.copy(opacity = 0.5f))), PipelineConfig())
        }
        assertFailsWith<IllegalArgumentException> {
            RigModifierRasterAssets.attach(input, listOf(output(), output()), PipelineConfig())
        }
        assertFailsWith<InterruptedException> {
            RigModifierRasterAssets.attach(input, listOf(output()), PipelineConfig()) { throw InterruptedException() }
        }
        assertEquals(listOf(owner), input.meshes)
        assertTrue(input.authoring.sources.isEmpty())
    }
}
