package io.github.psd2live.agent

import io.github.psd2live.core.*
import kotlinx.serialization.json.*
import java.awt.AlphaComposite
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.UUID
import javax.imageio.ImageIO
import kotlin.math.*

/** Immutable auxiliary records scoped to one project. None of these operations commits model history. */
internal class AgentAssetWorkflow(
    private val projectId: String, private val revision: String, private val document: AgentWorkspaceDocument,
    private val store: AgentWorkspaceStore, private val assets: AgentPngAssetStore,
) {
    fun record(id: String, kind: String): JsonObject = store.loadWorkflow(projectId, id).also { require(it.text("kind") == kind) { "Expected $kind record" } }
    fun asset(id: String): AgentPngAsset = assets.find(id) ?: store.loadAsset(projectId, id)?.let(assets::remember)
        ?: throw IllegalArgumentException("Asset not found: $id")
    private fun save(kind: String, fields: JsonObject): JsonObject {
        val value = JsonObject(fields + mapOf("id" to JsonPrimitive("$kind-${UUID.randomUUID()}"), "kind" to JsonPrimitive(kind),
            "version" to JsonPrimitive(2), "project_id" to JsonPrimitive(projectId)))
        store.persistWorkflow(projectId, value.text("id"), value)
        return value
    }
    private fun publicRecord(record: JsonObject) = JsonObject(record.filterKeys { !it.startsWith("_") })

    fun prepare(a: JsonObject): AgentWorkflowResult {
        val sourceId = a.text("layer_id")
        val layer = document.source.layers.singleOrNull { it.id.raw == sourceId } ?: error("Source layer not found: $sourceId")
        val matte = a.text("background_color")
        require(Regex("#[0-9a-fA-F]{6}").matches(matte)) { "background_color must be #RRGGBB" }
        val rect = (a["source_canvas_rect"] as? JsonObject)?.rect() ?: Bounds(layer.bounds.left.toFloat(), layer.bounds.top.toFloat(),
            (layer.bounds.left+layer.bounds.width).toFloat(), (layer.bounds.top+layer.bounds.height).toFloat())
        val targetAnchors = a.points("target_anchors")
        require(targetAnchors.size >= 2) { "Specify target_anchors in source canvas coordinates (root and tip, optionally side)" }
        val longEdge = a.number("target_long_edge", 1024.0).toInt()
        require(longEdge in 64..4096)
        val scale = longEdge / max(rect.width, rect.height).toDouble()
        val width = max(1, (rect.width*scale).roundToInt()); val height=max(1,(rect.height*scale).roundToInt())
        fun render(context: Boolean): BufferedImage {
            val image=BufferedImage(width,height,BufferedImage.TYPE_INT_ARGB_PRE)
            val g=image.createGraphics()
            try {
                g.color=Color(matte.drop(1).toInt(16)); g.fillRect(0,0,width,height)
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BICUBIC)
                g.scale(width/rect.width.toDouble(),height/rect.height.toDouble()); g.translate(-rect.left.toDouble(),-rect.top.toDouble())
                if (context) g.drawImage(PreviewRenderer.composite(document.source),0,0,null)
                else g.drawImage(PreviewRenderer.rasterImage(layer.raster.width,layer.raster.height,layer.raster.rgba),layer.bounds.left,layer.bounds.top,null)
            } finally { g.dispose() }
            return image
        }
        val clean=png(render(false)); val context=render(true)
        context.createGraphics().let { g -> try {
            g.color=Color.RED
            targetAnchors.forEach { (name,p) ->
                val x=((p.x-rect.left)*width/rect.width).roundToInt(); val y=((p.y-rect.top)*height/rect.height).roundToInt()
                g.drawOval(x-5,y-5,10,10); g.drawString(name,x+7,y)
            }
        } finally { g.dispose() } }
        val contextPng=png(context)
        val r=save("reference",buildJsonObject {
            put("revision_id",revision); put("source_layer_id",sourceId); put("piece_id",a.text("piece_id"))
            put("coordinate_space","canvas_top_left_y_down"); put("pose_kind","source_raster")
            put("canvas_width",document.source.widthPx); put("canvas_height",document.source.heightPx)
            put("source_canvas_rect",rect.json()); put("pixel_width",width); put("pixel_height",height)
            put("pixel_to_canvas",AgentPlacementTransform(rect.left.toDouble(),rect.top.toDouble(),rect.width/width.toDouble(),rect.height/height.toDouble()).json())
            put("background_color",matte); put("target_anchors",a.getValue("target_anchors"))
            a["source_parent_id"]?.let { put("source_parent_id", it) }
            put("occlusion",a["occlusion"] ?: JsonPrimitive(""))
            putJsonArray("calibration_layer_ids") {
                val ids=document.rigEdits.calibrationLayerIds.ifEmpty { document.source.layers.filter { (it as? AgentWorkspaceSourceLayer)?.derived != true }.map { it.id.raw }.toSet() }
                ids.sorted().forEach { add(JsonPrimitive(it)) }
            }
            put("generation_brief","Paint only ${a.text("piece_id")} as its own complete root-to-tip volume. Preserve reference frame and direction where possible, extend under declared occluders, and retain padding. Use UNIFORM $matte background; no transparency claim, checkerboard, labels or cast shadows. Red labels are context only. If the generator recenters the content, use landmark registration, never assume its position was retained.")
            put("_clean_png",Base64.getEncoder().encodeToString(clean)); put("_context_png",Base64.getEncoder().encodeToString(contextPng))
        })
        // Save its affine spatial reference separately for the legacy import machinery.
        store.persistSpatial(projectId,r.text("id"), AgentViewSpatialMetadata(pixelWidth=width,pixelHeight=height,
            canvasWidth=document.source.widthPx.toFloat(),canvasHeight=document.source.heightPx.toFloat(),requestedViewRect=rect,viewRect=rect,
            canvasUnitsPerPixelX=rect.width/width,canvasUnitsPerPixelY=rect.height/height))
        return AgentWorkflowResult(publicRecord(r),listOf(clean,contextPng))
    }

    fun register(a: JsonObject): AgentWorkflowResult {
        val asset=asset(a.text("asset_id"))
        val referenceId = asset.public.details["reference_id"]?.jsonPrimitive?.content
            ?: a["reference_id"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("reference_id is required for legacy asset registration")
        val ref=record(referenceId,"reference")
        val mode=a.text("mode")
        var residual=0.0; var conflict=false
        val transform=when(mode) {
            "frame" -> {
                val pixels=(a["generated_pixel_rect"] as? JsonObject)?.rect() ?: Bounds(0f,0f,asset.public.pixelWidth.toFloat(),asset.public.pixelHeight.toFloat())
                require(pixels.left>=0 && pixels.top>=0 && pixels.right<=asset.public.pixelWidth && pixels.bottom<=asset.public.pixelHeight) { "generated_pixel_rect outside PNG" }
                val canvas=(a["source_canvas_rect"] as? JsonObject)?.rect() ?: ref.getValue("source_canvas_rect").jsonObject.rect()
                registerAgentFrame(pixels,canvas,a.flag("allow_stretch"))
            }
            "landmarks" -> {
                val source=a.points("generated_anchors"); val targets=if(a.containsKey("target_anchors")) a.points("target_anchors") else ref.points("target_anchors")
                require(source.size>=2 && source.keys.all { it in targets }) { "Every generated anchor must have a target anchor" }
                source.values.forEach { require(it.x in 0.0..asset.public.pixelWidth.toDouble() && it.y in 0.0..asset.public.pixelHeight.toDouble()) { "Generated anchors are PNG pixel coordinates" } }
                val fit=registerAgentLandmarks(source.values.toList(),source.keys.map { targets.getValue(it) },a.flag("mirror_x"),a.flag("mirror_y"))
                residual=fit.rmsError; conflict=fit.orientationConflict; fit.transform
            }
            "absolute" -> AgentPlacementTransform.parse(a.getValue("transform").jsonObject).also {
                require(a.flag("allow_stretch") || abs(it.scaleX-it.scaleY)/max(it.scaleX,it.scaleY)<.005) { "Nonuniform scaling requires allow_stretch=true" }
            }
            else -> throw IllegalArgumentException("mode must be frame, landmarks or absolute")
        }
        val bounds=transform.bounds(asset.public.pixelWidth,asset.public.pixelHeight)
        require(bounds.width>0 && bounds.height>0 && bounds.width.toDouble()*bounds.height<=16_777_216) { "Placement exceeds raster size budget" }
        val r=save("registration",buildJsonObject {
            put("asset_id",asset.public.id); put("reference_id",referenceId); put("mode",mode); put("transform",transform.json())
            put("canvas_bounds",bounds.json()); put("anchor_rms_canvas_units",residual); put("orientation_conflict",conflict)
            put("orientation",if(transform.mirrorX xor transform.mirrorY) "explicit_reflection" else "preserved")
            put("generated_anchors",a["generated_anchors"] ?: JsonObject(emptyMap()))
            put("target_anchors",a["target_anchors"] ?: ref.getValue("target_anchors"))
            put("source_revision",ref.getValue("revision_id")); put("current_revision",revision)
            put("advice",if(conflict) "Anchor handedness conflicts. Inspect original vs reference; correct anchor labels or explicitly request mirror. No automatic reflection applied." else "Inspect assembled position; residual is diagnostic, not a contour-equality gate.")
        })
        return AgentWorkflowResult(r, listOf(placedAsset(asset,r).preview().png))
    }

    fun reprocess(a: JsonObject): AgentWorkflowResult {
        val previous=asset(a.text("asset_id")); val referenceId=previous.public.details.text("reference_id")
        val ref=record(referenceId,"reference"); val spatial=store.loadSpatial(projectId,referenceId) ?: error("Reference spatial metadata missing")
        val imported=assets.import(AgentPngImportRequest(requireNotNull(previous.originalPng) { "Legacy asset has no original PNG" },referenceId,
            solidBackground=a["solid_background"]?.jsonPrimitive?.content ?: ref.text("background_color"),
            backgroundTolerance=a.number("background_tolerance",16.0).toInt(),requireTransparency=true,referenceId=referenceId,
            processing=a["processing"] as? JsonObject ?: JsonObject(emptyMap())),spatial)
        val saved=assets.require(imported.id); store.persistAsset(projectId,saved)
        return AgentWorkflowResult(buildJsonObject { put("asset_id",imported.id); put("details",imported.details) },listOf(saved.preview().png))
    }

    fun preview(a: JsonObject): AgentWorkflowResult {
        val entries=a.getValue("placements").jsonArray.map { it.jsonObject }
        require(entries.isNotEmpty())
        val excluded=a.strings("replace_layer_ids").toSet()
        require(excluded.all { id -> document.source.layers.any { it.id.raw==id } }) { "Unknown replace_layer_ids" }
        val canvas=BufferedImage(document.source.widthPx,document.source.heightPx,BufferedImage.TYPE_INT_ARGB_PRE)
        val g=canvas.createGraphics()
        val background=a["background_color"]?.jsonPrimitive?.content
        val remaining=document.source.layers.filter { it.id.raw !in excluded && it.id.raw !in document.deletedLayerIds && document.layerVisibility[it.id.raw] != false }
        // Each placement can be interleaved above/below a source layer, in caller painter order.
        val ordered=remaining.map { it.id.raw to (it as org.umamo.format.art.SourceLayer?) }.toMutableList()
        val placed=linkedMapOf<String,AgentPngAsset>()
        entries.forEachIndexed { i,e ->
            val reg=record(e.text("registration_id"),"registration"); val image=placedAsset(asset(reg.text("asset_id")),reg)
            val key="preview-$i"; placed[key]=image
            val anchor=e["reference_layer_id"]?.jsonPrimitive?.content
            val mode=e["insertion"]?.jsonPrimitive?.content ?: "top"
            val index=when(mode) {
                "top" -> ordered.size; "bottom" -> 0
                "above","below" -> ordered.indexOfFirst { it.first==anchor }.also { require(it>=0) { "Preview insertion anchor not present" } } + if(mode=="above") 1 else 0
                else -> throw IllegalArgumentException("insertion must be top, bottom, above or below")
            }
            ordered.add(index,key to null)
        }
        try {
            if(background!=null) { require(Regex("#[0-9a-fA-F]{6}").matches(background));g.color=Color(background.drop(1).toInt(16));g.fillRect(0,0,canvas.width,canvas.height) }
            for((key,layer) in ordered) {
                if(layer!=null) {
                    if(!layer.visible || layer.opacity<=0) continue
                    g.composite=AlphaComposite.getInstance(AlphaComposite.SRC_OVER,layer.opacity)
                    g.drawImage(PreviewRenderer.rasterImage(layer.raster.width,layer.raster.height,layer.raster.rgba),layer.bounds.left,layer.bounds.top,null)
                } else {
                    val item=placed.getValue(key);g.composite=AlphaComposite.SrcOver
                    g.drawImage(PreviewRenderer.rasterImage(item.public.pixelWidth,item.public.pixelHeight,item.rgba),item.public.placement.canvasRect.left.toInt(),item.public.placement.canvasRect.top.toInt(),null)
                }
            }
        } finally { g.dispose() }
        val viewport=(a["source_canvas_rect"] as? JsonObject)?.rect() ?: Bounds(0f,0f,canvas.width.toFloat(),canvas.height.toFloat())
        val longEdge=a.number("target_long_edge",1024.0).toInt();require(longEdge in 64..4096)
        val s=longEdge/max(viewport.width,viewport.height).toDouble()
        val output=BufferedImage(max(1,(viewport.width*s).roundToInt()),max(1,(viewport.height*s).roundToInt()),BufferedImage.TYPE_INT_ARGB_PRE)
        output.createGraphics().let { q -> try { q.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BICUBIC);q.scale(s,s);q.translate(-viewport.left.toDouble(),-viewport.top.toDouble());q.drawImage(canvas,0,0,null) } finally { q.dispose() } }
        return AgentWorkflowResult(buildJsonObject { put("revision_id",revision);put("pose_kind","source_raster");put("canvas_rect",viewport.json());put("placements",a.getValue("placements"));put("history_changed",false) },listOf(png(output)))
    }
}

internal fun png(image: BufferedImage): ByteArray = ByteArrayOutputStream().also { ImageIO.write(image,"png",it) }.toByteArray()

/** Render absolute placement from the unmodified processed pixels; never from a previous placed raster. */
internal fun placedAsset(asset: AgentPngAsset, registration: JsonObject): AgentPngAsset {
    require(registration.text("asset_id")==asset.public.id)
    val transform=AgentPlacementTransform.parse(registration.getValue("transform").jsonObject)
    val b=transform.bounds(asset.public.pixelWidth,asset.public.pixelHeight)
    val left=floor(b.left).toInt();val top=floor(b.top).toInt()
    val width=ceil(b.right).toInt()-left;val height=ceil(b.bottom).toInt()-top
    require(width>0 && height>0 && width.toLong()*height<=16_777_216) { "Invalid/oversized placement" }
    val image=BufferedImage(width,height,BufferedImage.TYPE_INT_ARGB_PRE)
    image.createGraphics().let { g -> try {
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        g.translate(-left.toDouble(),-top.toDouble());g.transform(transform.affine())
        g.drawImage(PreviewRenderer.rasterImage(asset.public.pixelWidth,asset.public.pixelHeight,asset.rgba),0,0,null)
    } finally { g.dispose() } }
    val rgba=ByteArray(width*height*4)
    val pixels=image.getRGB(0,0,width,height,null,0,width)
    pixels.forEachIndexed { i,p -> rgba[i*4]=(p ushr 16).toByte();rgba[i*4+1]=(p ushr 8).toByte();rgba[i*4+2]=p.toByte();rgba[i*4+3]=(p ushr 24).toByte() }
    return AgentPngAsset(asset.public.copy(pixelWidth=width,pixelHeight=height,placement=AgentCanvasPlacement("canvas_top_left_y_down",
        Bounds(left.toFloat(),top.toFloat(),(left+width).toFloat(),(top+height).toFloat()),width,height,1f,1f,asset.public.placement.sourceViewId)),rgba,asset.originalPng)
}
