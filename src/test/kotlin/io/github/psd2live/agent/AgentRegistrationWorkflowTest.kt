package io.github.psd2live.agent

import io.github.psd2live.core.*
import kotlinx.serialization.json.*
import org.umamo.format.art.*
import java.awt.image.BufferedImage
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.*

class AgentRegistrationWorkflowTest {
    private fun point(x: Double, y: Double) = AgentPoint(x, y)
    private fun anchors() = buildJsonObject { put("root", point(24.0, 12.0).json()); put("tip", point(64.0, 100.0).json()) }

    @Test fun `landmarks recover recentered scale and rotation but never infer reflection`() {
        val source = listOf(point(10.0, 10.0), point(10.0, 70.0), point(30.0, 40.0))
        val wanted = AgentPlacementTransform(63.0, 22.0, .7, rotationDegrees = 25.0)
        val fit = registerAgentLandmarks(source, source.map(wanted::point))
        assertEquals(0.0, fit.rmsError, 1e-8)
        assertEquals(wanted.x, fit.transform.x, 1e-8)
        assertEquals(wanted.rotationDegrees, fit.transform.rotationDegrees, 1e-8)
        val reflected = wanted.copy(mirrorX = true)
        val conflict = registerAgentLandmarks(source, source.map(reflected::point))
        assertTrue(conflict.orientationConflict)
        assertFalse(conflict.transform.mirrorX)
        val explicit = registerAgentLandmarks(source, source.map(reflected::point), mirrorX = true)
        assertFalse(explicit.orientationConflict)
        assertEquals(0.0, explicit.rmsError, 1e-8)
        assertFailsWith<IllegalArgumentException> { registerAgentLandmarks(listOf(source[0], source[0]), source.take(2)) }
    }

    @Test fun `frame placement retains declared noncentral crop and letterbox across resolutions`() {
        val target = Bounds(100f, 200f, 300f, 300f)
        for (resolution in listOf(1f, 2f, 4f)) {
            val frame = Bounds(30f*resolution, 10f*resolution, 430f*resolution, 210f*resolution)
            val transform = registerAgentFrame(frame, target)
            assertEquals(point(100.0, 200.0), transform.point(point(frame.left.toDouble(), frame.top.toDouble())))
            assertEquals(point(300.0, 300.0), transform.point(point(frame.right.toDouble(), frame.bottom.toDouble())))
        }
        assertFailsWith<IllegalArgumentException> { registerAgentFrame(Bounds(0f,0f,100f,100f), target) }
    }

    private fun source(): SourceArt {
        val layer = object : SourceLayer {
            override val id = LayerId("hair")
            override val name = "front hair"
            override val groupPath = ""
            override val kind = SourceLayerKind.Raster
            override val visible = true
            override val order = 0
            override val bounds = LayerBounds(24, 12, 64, 96)
            override val opacity = 1f
            override val clipped = false
            override val blend = LayerBlend.Normal
            override val raster = LayerRaster(64,96,ByteArray(64*96*4) { if(it%4==3) 255.toByte() else 80 })
        }
        return object : SourceArt { override val widthPx=128; override val heightPx=128; override val layers=listOf(layer) }
    }

    @Test fun `reference staging preview placement and disk recovery retain original pixels and existing Warp`() {
        val root=Files.createTempDirectory("registration-workflow")
        try {
            val source=source()
            var document=AgentWorkspaceDocument(source,emptyMap(),emptySet(),emptyMap(),emptyMap(),RigEditOverlay.Empty)
            val config=PipelineConfig(atlasSize=256,meshSpacing=16)
            val pipeline=PSD2LivePipeline()
            val initial=pipeline.buildPreview(source,config)
            val parent=initial.rig.puppet.drawables.first().parentDeformerId!!.raw
            val store=AgentWorkspaceStore(root); val assets=AgentPngAssetStore()
            val api=AgentAssetWorkflow("project","r0",document,store,assets)
            val reference=api.prepare(buildJsonObject {
                put("layer_id","hair");put("piece_id","front hair 1");put("source_parent_id",parent)
                put("background_color","#FFFFFF");put("target_anchors",anchors());put("target_long_edge",128)
            })
            assertEquals(2,reference.images.size)
            val refId=reference.metadata.text("id")
            val generated=BufferedImage(48,64,BufferedImage.TYPE_INT_ARGB).also { image ->
                for(y in 0 until 64) for(x in 0 until 48) image.setRGB(x,y,0xffffffff.toInt())
                for(y in 8..55) for(x in 10..27) image.setRGB(x,y,0xff335588.toInt())
                for(y in 8..20) for(x in 28..35) image.setRGB(x,y,0xffff3333.toInt()) // asymmetric right branch
            }
            val raw=png(generated)
            val imported=assets.import(AgentPngImportRequest(raw,refId,solidBackground="#FFFFFF",referenceId=refId),store.loadSpatial("project",refId)!!)
            store.persistAsset("project",assets.require(imported.id))
            fun register(x:Double)=api.register(buildJsonObject {
                put("asset_id",imported.id);put("mode","absolute");put("transform",AgentPlacementTransform(x,15.0,.75).json())
            }).metadata
            val first=register(25.0);val second=register(65.0)
            val preview=api.preview(buildJsonObject {
                putJsonArray("placements") { for(r in listOf(first,second)) add(buildJsonObject { put("registration_id",r.text("id")) }) }
                putJsonArray("replace_layer_ids") { add("hair") };put("target_long_edge",128)
            })
            assertFalse(preview.metadata.flag("history_changed"))
            val composite=ImageIO.read(preview.images.single().inputStream())
            assertEquals(0,composite.getRGB(24,12) ushr 24)
            assertTrue(composite.getRGB(35,35) ushr 24 > 0)
            assertTrue(composite.getRGB(75,35) ushr 24 > 0)
            val asset=assets.require(imported.id)
            val placed=placedAsset(asset,first)
            val request=AgentAddLayerRequest(assetId=imported.id,name="front hair 1",semanticTag="FRONT_HAIR",side="NONE",parentDeformerId=parent,layerId="lock1",expectedHistoryHeadNodeId="h0")
            val added=document.addLayer(placed,request)
            document=added.first.copy(rigEdits=RigEditOverlay(assetLayers=mapOf("lock1" to buildJsonObject { put("registration_id",first.text("id")) }),calibrationLayerIds=setOf("hair")))
            val before=document.source.layers.single { it.id.raw=="lock1" }
            val moved=document.replacePlacedLayer("lock1",placedAsset(asset,second))
            val restored=moved.replacePlacedLayer("lock1",placedAsset(asset,first))
            val after=restored.source.layers.single { it.id.raw=="lock1" }
            assertEquals(before.bounds,after.bounds);assertContentEquals(before.raster.rgba,after.raster.rgba)
            val rig=pipeline.buildPreview(restored.source,config.copy(parentOverrides=restored.parentOverrides,rigEdits=restored.rigEdits))
            assertEquals(parent,rig.rig.puppet.drawables.single { rig.rig.layerIdByDrawableId[it.id.raw]=="lock1" }.parentDeformerId?.raw)
            assertEquals(initial.analysis.anchors,rig.analysis.anchors)
            val reloaded=AgentWorkspaceStore(root)
            assertContentEquals(raw,reloaded.loadAsset("project",imported.id)!!.originalPng)
            assertEquals(first,reloaded.loadWorkflow("project",first.text("id")))
            assertContentEquals(placed.rgba,placedAsset(reloaded.loadAsset("project",imported.id)!!,first).rgba)
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun `matting handles flat colors enclosed holes protected foreground and fake transparency diagnostics`() {
        for(background in listOf(0xffffff,0x000000,0x00cc55)) {
            val image=BufferedImage(32,32,BufferedImage.TYPE_INT_ARGB)
            for(y in 0..31) for(x in 0..31) image.setRGB(x,y,0xff000000.toInt() or background)
            for(y in 6..25) for(x in 6..25) image.setRGB(x,y,0xff334499.toInt())
            image.setRGB(15,15,0xff000000.toInt() or background)
            val hints=buildJsonObject { putJsonArray("background_points") { add(point(15.0,15.0).json()) } }
            val result=processGeneratedMatte(image,"#%06X".format(background),8,hints)
            assertEquals(0,result.image.getRGB(0,0) ushr 24)
            assertEquals(0,result.image.getRGB(15,15) ushr 24)
            assertEquals(image.getRGB(10,10),result.image.getRGB(10,10))
            assertEquals(255,image.getRGB(0,0) ushr 24)
            val protected=processGeneratedMatte(image,"#%06X".format(background),8,buildJsonObject {
                putJsonArray("foreground_points") { add(point(15.0,15.0).json()) }
            })
            assertEquals(255,protected.image.getRGB(15,15) ushr 24)
        }
        val checker=BufferedImage(32,32,BufferedImage.TYPE_INT_ARGB)
        for(y in 0..31) for(x in 0..31) checker.setRGB(x,y,if((x/4+y/4)%2==0) 0xffffffff.toInt() else 0xffaaaaaa.toInt())
        assertEquals("background_mismatch",processGeneratedMatte(checker,"#FFFFFF",8,JsonObject(emptyMap())).diagnostics.text("status"))
    }
}
