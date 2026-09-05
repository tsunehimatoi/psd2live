package io.github.psd2live.agent

import io.github.psd2live.core.*
import io.github.psd2live.history.WorkspaceHistoryTree
import org.umamo.format.art.SourceArt
import org.umamo.format.art.SourceLayer
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.*

class AgentAssetAndRigPersistenceTest {
    private val spatial = AgentViewSpatialMetadata( pixelWidth = 5, pixelHeight = 5,
        canvasWidth = 100f, canvasHeight = 100f, requestedViewRect = Bounds(10f, 20f, 60f, 70f),
        viewRect = Bounds(10f, 20f, 60f, 70f), canvasUnitsPerPixelX = 10f, canvasUnitsPerPixelY = 10f)

    private fun matteImage() = BufferedImage(5, 5, BufferedImage.TYPE_INT_ARGB).also { image ->
        for (y in 0..4) for (x in 0..4) image.setRGB(x, y, 0xff00ff00.toInt())
        for (y in 1..3) for (x in 1..3) image.setRGB(x, y, 0xff445588.toInt())
        image.setRGB(2, 2, 0xff00ff00.toInt()) // Same-color detail enclosed by foreground.
    }

    @Test fun `matte cleanup preserves enclosed details and source pixels`() {
        val source = matteImage()
        val result = cleanGeneratedMatte(source, "#00ff00", 16)
        assertEquals(0, result.getRGB(0, 0) ushr 24)
        assertEquals(source.getRGB(2, 2), result.getRGB(2, 2))
        assertEquals(source.getRGB(1, 1), result.getRGB(1, 1))
        assertEquals(255, source.getRGB(0, 0) ushr 24)
        assertFailsWith<IllegalArgumentException> { cleanGeneratedMatte(source, "checkerboard", 16) }
        assertFailsWith<IllegalArgumentException> { cleanGeneratedMatte(source, "#00ff00", 255) }
    }

    @Test fun `import rejects opaque hair and keys matte without spatial drift or asset collision`() {
        val png = ByteArrayOutputStream().also { ImageIO.write(matteImage(), "png", it) }.toByteArray()
        val store = AgentPngAssetStore()
        val raw = store.import(AgentPngImportRequest(png, "view"), spatial)
        assertFailsWith<IllegalArgumentException> {
            store.import(AgentPngImportRequest(png, "view", requireTransparency = true), spatial)
        }
        val cleaned = store.import(AgentPngImportRequest(png, "view", solidBackground = "#00ff00", requireTransparency = true), spatial)
        assertNotEquals(raw.id, cleaned.id)
        assertEquals(raw.placement, cleaned.placement)
        assertEquals(0, store.require(cleaned.id).rgba[3].toInt())
        assertEquals(255, store.require(raw.id).rgba[3].toInt() and 255)
    }

    @Test fun `warp and physics edits survive history disk restore and checkout`() {
        val root = Files.createTempDirectory("agent-rig-persistence")
        try {
            val source = object : SourceArt {
                override val widthPx = 100
                override val heightPx = 100
                override val layers = emptyList<SourceLayer>()
            }
            val original = AgentWorkspaceDocument(source, emptyMap(), emptySet(), emptyMap(), emptyMap(), RigEditOverlay.Empty)
            val edits = RigEditOverlay(
                warpEdits = listOf(RigWarpEdit("lock-warp", "Lock", "parent", listOf("mesh"))),
                physicsEdits = listOf(RigPhysicsEdit("lock-physics", "Lock physics", "ParamAngleX", "LockSway")))
            val tree = WorkspaceHistoryTree(original, "r0", "h0")
            val originalHead = tree.head().node.id
            val committed = tree.commit(originalHead, original.copy(rigEdits = edits), "r1", "h1", "Independent lock")
            AgentWorkspaceStore(root).persistHistory("project", tree.state())
            val restored = assertNotNull(AgentWorkspaceStore(root).loadHistory("project"))
            assertEquals(edits, restored.head().snapshot.rigEdits)
            assertEquals(RigEditOverlay.Empty, restored.checkout(originalHead).snapshot.rigEdits)
            assertEquals(edits, restored.checkout(committed.node.id).snapshot.rigEdits)
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
