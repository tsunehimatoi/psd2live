package io.github.psd2live.application

import kotlinx.serialization.json.*

/** Staged assets retain original-pixel processing and explicit source-canvas placement. */
internal object WorkspaceAssetResultSchemas {
    private val s = WorkspaceResultSchema
    private val rectangle = s.obj(listOf("left", "top", "width", "height").associateWith { s.number() })
    private val point = s.obj(mapOf("x" to s.number(), "y" to s.number()))
    private val anchors = s.dictionary(point)
    private val transform = s.obj(listOf("x", "y", "scale_x", "scale_y", "rotation_degrees").associateWith { s.number() } +
        mapOf("mirror_x" to s.boolean(), "mirror_y" to s.boolean()))
    private val processingFields = mapOf("edge_width" to s.integer(0, 8), "foreground_points" to s.array(point), "background_points" to s.array(point))
    private val processing = s.obj(processingFields, emptySet())
    private val matteDiagnostic = s.obj(mapOf(
        "status" to s.choices("background_mismatch", "review_edges", "processed"), "matte_color" to s.string(),
        "border_match_fraction" to s.number(0, 1), "removed_pixels" to s.integer(0), "unmixed_edge_pixels" to s.integer(0),
        "unresolved_edge_pixels" to s.integer(0), "possible_enclosed_matte_pixels" to s.integer(0),
        "orientation_changed" to s.constant(false), "advice" to s.string(), "quality" to WorkspaceQualitySchemas.report,
    ), setOf("status", "matte_color", "border_match_fraction", "removed_pixels", "unmixed_edge_pixels", "unresolved_edge_pixels", "possible_enclosed_matte_pixels", "orientation_changed", "advice"))
    private val diagnostic = s.union(listOf(s.obj(mapOf("mode" to s.constant("native_alpha"), "quality" to WorkspaceQualitySchemas.report), setOf("mode")), matteDiagnostic))
    private val detailFields = mapOf("version" to s.integer(2, 2), "reference_id" to s.handle(),
        "solid_background" to s.nullable(s.string()), "background_tolerance" to s.integer(0, 64),
        "registration_required" to s.constant(true), "processing" to processing, "diagnostics" to diagnostic,
        "raw_sha256" to s.handle(), "content_pixel_rect" to rectangle)
    private val details = s.union(listOf(s.obj(emptyMap()), s.obj(detailFields, detailFields.keys - "content_pixel_rect")))
    private val canvasRectangle = s.obj(listOf("left", "top", "right", "bottom", "width", "height").associateWith { s.number() })
    private fun importedAsset(details: JsonObject) = s.obj(mapOf("details" to details, "assetId" to s.handle(), "sha256" to s.handle(),
        "mimeType" to s.constant("image/png"), "pixelWidth" to s.integer(1), "pixelHeight" to s.integer(1),
        "sourceSpatialReferenceId" to s.string(), "canvasRect" to canvasRectangle,
        "canvasUnitsPerPixelX" to s.number(), "canvasUnitsPerPixelY" to s.number()))
    private val record = mapOf("id" to s.handle(), "version" to s.integer(2, 2), "project_id" to s.handle())
    private val referenceFields = record + mapOf("kind" to s.constant("reference"), "revision_id" to s.handle(),
        "source_layer_id" to s.handle(), "piece_id" to s.string(), "coordinate_space" to s.constant("canvas_top_left_y_down"),
        "pose_kind" to s.constant("source_raster"), "canvas_width" to s.integer(1), "canvas_height" to s.integer(1),
        "source_canvas_rect" to rectangle, "pixel_width" to s.integer(1), "pixel_height" to s.integer(1),
        "pixel_to_canvas" to transform, "background_color" to s.string(), "target_anchors" to anchors,
        "source_parent_id" to s.handle(), "occlusion" to s.string(), "calibration_layer_ids" to s.array(s.handle()), "generation_brief" to s.string())
    private val reference = s.obj(referenceFields, referenceFields.keys - "source_parent_id")
    private val registration = s.obj(record + mapOf("kind" to s.constant("registration"), "asset_id" to s.handle(), "reference_id" to s.handle(),
        "quality" to WorkspaceQualitySchemas.report, "mode" to s.choices("frame", "landmarks", "absolute"), "transform" to transform, "canvas_bounds" to rectangle,
        "anchor_rms_canvas_units" to s.number(0), "orientation_conflict" to s.boolean(), "orientation" to s.choices("explicit_reflection", "preserved"),
        "generated_anchors" to anchors, "target_anchors" to anchors, "source_revision" to s.handle(), "current_revision" to s.handle(), "advice" to s.string()),
        (record.keys + setOf("kind", "asset_id", "reference_id", "mode", "transform", "canvas_bounds", "anchor_rms_canvas_units", "orientation_conflict", "orientation", "generated_anchors", "target_anchors", "source_revision", "current_revision", "advice")))
    private val inspectedDetails = s.union(listOf(s.obj(emptyMap()), s.obj(detailFields, detailFields.keys - "content_pixel_rect"),
        s.obj(detailFields + mapOf("reference" to reference, "registrations" to s.array(registration), "orientation_diagnostic" to s.string()),
            (detailFields.keys - "content_pixel_rect") + setOf("reference", "registrations", "orientation_diagnostic"))))
    private val placementFields = mapOf("registration_id" to s.handle(), "insertion" to s.string(), "reference_layer_id" to s.string())
    private val preview = s.obj(mapOf("revision_id" to s.handle(), "pose_kind" to s.constant("source_raster"), "canvas_rect" to rectangle,
        "placements" to s.array(s.obj(placementFields, setOf("registration_id")), 1, Int.MAX_VALUE), "history_changed" to s.constant(false)))

    fun recordForOperation(id: String): JsonObject? = when (id) {
        "asset_prepare_reference" -> reference
        "asset_register" -> registration
        "asset_reprocess" -> s.obj(mapOf("asset_id" to s.handle(), "details" to details))
        "asset_import_png" -> importedAsset(details)
        "asset_inspect" -> s.obj(mapOf("asset" to importedAsset(inspectedDetails), "transparentPixels" to s.integer(0), "translucentPixels" to s.integer(0),
            "image_order" to s.constant("processed, original (when retained)")))
        "asset_preview_composite" -> preview
        else -> null
    }

    fun forOperation(id: String): JsonObject? {
        val schema = recordForOperation(id) ?: return null
        if (id !in WorkspaceAssetSessions.supported) return schema
        return JsonObject(schema + mapOf("properties" to JsonObject(schema.getValue("properties").jsonObject + s.identity),
            "required" to JsonArray((schema.getValue("required").jsonArray + s.identity.keys.map(::JsonPrimitive)).distinct())))
    }
}
