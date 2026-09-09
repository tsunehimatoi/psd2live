package io.github.psd2live.agent

import io.github.psd2live.core.Bounds
import io.github.psd2live.core.RigEditOverlay
import io.github.psd2live.history.WorkspaceHistoryTree
import kotlinx.serialization.json.*
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.*

class AgentAuthoringTest {
    @Test fun historyPersistsStructureJournal() {
        val edit=Json.parseToJsonElement("""{"action":"rename","kind":"mesh","id":"hair","name":"Bang"}""").jsonObject
        val document=AgentWorkspaceDocument(WorkspaceSourceArt(10,10,emptyList(),emptyList()),emptyMap(),emptySet(),emptyMap(),emptyMap(),
            RigEditOverlay(structureEdits=listOf(edit)))
        val root=Files.createTempDirectory("psd2live-history-test")
        try {
            val store=AgentWorkspaceStore(root)
            val history=WorkspaceHistoryTree(document,"revision","hash")
            store.persistHistory("project",history.state())
            val restored=assertNotNull(store.loadHistory("project"))
            assertEquals(history.head().node.id,restored.head().node.id)
            assertEquals(listOf(edit),restored.head().snapshot.rigEdits.structureEdits)
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun nativeAlphaReferenceImportDoesNotStripWhitePaint() {
        val image=BufferedImage(2,2,BufferedImage.TYPE_INT_ARGB)
        image.setRGB(1,1,0xffffffff.toInt())
        val png=ByteArrayOutputStream().also { ImageIO.write(image,"png",it) }.toByteArray()
        val rect=Bounds(0f,0f,2f,2f)
        val spatial=AgentViewSpatialMetadata(pixelWidth=2,pixelHeight=2,canvasWidth=2f,canvasHeight=2f,
            requestedViewRect=rect,viewRect=rect,canvasUnitsPerPixelX=1f,canvasUnitsPerPixelY=1f)
        val store=AgentPngAssetStore()
        val asset=store.import(AgentPngImportRequest(png,referenceId="reference",requireTransparency=true),spatial)
        val rgba=store.require(asset.id).rgba
        assertEquals(0,rgba[3].toInt() and 255)
        assertEquals(255,rgba[15].toInt() and 255)
        assertEquals(255,rgba[12].toInt() and 255)
    }

    @Test fun coverageReportsPartialAlphaAndEmptyRegion() {
        val image=BufferedImage(2,2,BufferedImage.TYPE_INT_ARGB)
        image.setRGB(0,0,0xffffffff.toInt())
        image.setRGB(1,0,0x40ffffff)
        val report=measureCoverage(image,128)
        assertEquals(3,report.getValue("uncoveredPixelCount").jsonPrimitive.int)
        assertEquals(0.75,report.getValue("uncoveredFraction").jsonPrimitive.double)
        assertFailsWith<IllegalArgumentException> { measureCoverage(image,0) }
    }

    @Test fun optionalKnowledgeHasNoBrokenTopics() {
        for(topic in listOf("overview","geometry","hair","variants","face","assets")) assertTrue(loadAgentReference(topic).isNotBlank())
        assertFailsWith<IllegalStateException> { loadAgentReference("../../secret") }
    }
}
