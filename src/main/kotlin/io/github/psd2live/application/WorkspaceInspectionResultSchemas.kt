package io.github.psd2live.application

import io.github.psd2live.core.*
import kotlinx.serialization.json.*
import org.umamo.runtime.model.RuntimeTarget
import org.umamo.runtime.model.VertexGroupKind

/** Every inspect scope has a discriminator, including empty pages and an unloaded project. */
internal object WorkspaceInspectionResultSchemas {
    private val s = WorkspaceResultSchema
    private val identity = mapOf("state" to s.handle(), "project_id" to s.nullable(s.handle()), "history_node_id" to s.handle())
    private fun scoped(scope: String, fields: Map<String, JsonObject>, required: Set<String> = fields.keys) =
        s.obj(identity + mapOf("scope" to s.constant(scope)) + fields, setOf("state", "project_id", "scope") + required)
    private val axes = s.dictionary(s.array(s.number()))
    private val pathFields = mapOf("id" to s.handle(), "level" to s.integer(), "width" to s.number(), "hardness" to s.number(),
        "closed" to s.boolean(), "pointCount" to s.integer(0))
    private val objectFields = mapOf("target" to s.handle(), "name" to s.string(), "parent" to s.handle(), "visible" to s.boolean(),
        "forms" to s.integer(0), "axes" to axes,
        "channels" to s.array(s.obj(mapOf("channel" to s.handle(), "value" to s.string(), "axes" to axes), setOf("channel", "value"))),
        "paths" to s.array(s.obj(pathFields), 1, Int.MAX_VALUE),
        "inherited" to s.array(s.obj(mapOf("target" to s.handle(), "name" to s.string(), "parameters" to s.array(s.handle())))))
    private val preview = s.obj(mapOf("values" to s.dictionary(s.number()), "locked" to s.array(s.handle())))
    private val shapeFields = mapOf("kind" to s.choices(*SwingKind.entries.map { it.name }.toTypedArray()),
        "parameters" to s.array(s.handle(), 1, RigSwingEdit.MAX_SEGMENTS), "magnitude" to s.number(), "lift" to s.number(),
        "softness" to s.number(), "zoom" to s.number(), "parallel" to s.number(), "flip" to s.constant(true),
        "physics" to s.nullable(s.obj(listOf("length", "mobility", "delay", "acceleration", "output_scale").associateWith { s.number() })))
    private val swingFields = mapOf("id" to s.handle(), "name" to s.handle(), "targets" to s.array(s.handle(), 1, Int.MAX_VALUE),
        "fulcrum" to s.choices(*SwingFulcrum.entries.map { it.name }.toTypedArray()), "preset" to s.choices(*SwingPreset.entries.map { it.name }.toTypedArray()),
        "tilt" to s.number(), "offset_along" to s.number(), "offset_across" to s.number(), "baked" to s.constant(true),
        "motions" to s.array(s.obj(shapeFields, shapeFields.keys - "flip"), 1, 2))
    private val swing = s.obj(swingFields, setOf("id", "name", "targets", "fulcrum", "preset", "motions"))
    private val projectFields = mapOf("loaded" to s.boolean(), "busy" to s.boolean(), "canvas" to s.array(s.integer(1), 0, 2),
        "selection" to s.handle(), "layers" to s.integer(0), "parameters" to s.integer(0), "persistenceError" to s.string(),
        "quality" to s.obj(mapOf("overrides" to WorkspaceQualitySchemas.generatedOverrides, "regeneration" to WorkspaceQualitySchemas.regeneration,
            "skeleton" to WorkspaceQualitySchemas.skeleton)))
    private val objectRowFields = mapOf("target" to s.handle(), "name" to s.string(), "parentId" to s.nullable(s.handle()), "layerId" to s.handle())
    private val layer = s.obj(mapOf("id" to s.handle(), "name" to s.string(), "visible" to s.boolean(),
        "role" to s.choices(*SemanticTag.entries.map { it.name.lowercase() }.toTypedArray()),
        "side" to s.choices(*Side.entries.map { it.name.lowercase() }.toTypedArray()),
        "type" to s.choices(*LayerType.entries.map { it.name.lowercase() }.toTypedArray()), "parameter" to s.string(),
        "switch_id" to s.integer(), "mesh" to WorkspaceSettingsResultTypes.layerMesh, "bounds" to s.vector(4)))
    private val parameter = s.obj(mapOf("id" to s.handle(), "name" to s.string(), "min" to s.number(), "max" to s.number(), "default" to s.number()))
    private val group = s.obj(mapOf("target" to s.handle(), "name" to s.string(),
        "kind" to s.choices(*VertexGroupKind.entries.map { it.jsonName }.toTypedArray()), "vertices" to s.integer(0), "weighted" to s.integer(0), "max" to s.number()))
    private fun page(scope: String, item: JsonObject) = scoped(scope,
        mapOf("items" to s.array(item, 0, 64), "total" to s.integer(0), "next" to s.integer(1)), setOf("items", "total"))
    private val inspect = s.union(listOf(
        scoped("object", objectFields, setOf("target", "name", "visible", "channels", "inherited")),
        scoped("project", projectFields, setOf("loaded", "busy", "canvas", "layers", "parameters")),
        scoped("physics", mapOf("fps" to s.integer(), "groups" to s.array(WorkspacePhysicsResultTypes.group))),
        scoped("swings", mapOf("swings" to s.array(swing))),
        scoped("simulations", mapOf("simulations" to s.array(WorkspaceSimulationResultSchemas.inspectedSimulation),
            "glues" to s.array(s.obj(mapOf("key" to s.handle(), "pairs" to s.integer(0)))))),
        scoped("settings", mapOf("settings" to WorkspaceSettingsResultTypes.settings)),
        scoped("preview", mapOf("preview" to preview)),
        page("objects", s.obj(objectRowFields, objectRowFields.keys - "layerId")), page("layers", layer), page("parameters", parameter),
        page("paths", s.obj(pathFields + ("target" to s.handle()))), page("vertex_groups", group),
    ))
    fun forOperation(id: String): JsonObject? = if (id == "workspace_inspect") inspect else null
}

/** Canonical saved generation settings include defaults and document-owned mesh overrides. */
internal object WorkspaceSettingsResultTypes {
    private val s = WorkspaceResultSchema
    private val ratio = mapOf("edgeRatio" to s.number(), "gradation" to s.number())
    private val fill = s.obj(mapOf("poisson" to s.obj(ratio + ("jitter" to s.number())),
        "quadtree" to s.obj(ratio + ("angle" to s.number())), "fractal" to s.obj(ratio + ("angle" to s.number())),
        "paving" to s.obj(ratio + ("maxRows" to s.integer()))))
    private val meshFields = mapOf("outerMargin" to s.number(), "edgeMode" to s.choices(*MeshEdgeMode.entries.map { it.name }.toTypedArray()),
        "edgeWidth" to s.number(), "maxEdgeDistance" to s.number(), "interiorDensity" to s.number(),
        "fillAlgorithm" to s.choices(*MeshFillAlgorithm.entries.map { it.name }.toTypedArray()),
        "suppressBoundaryDiagonals" to s.boolean(), "fillParameters" to fill, "wrap" to s.number())
    val layerMesh = s.obj(meshFields + ("overridden" to s.boolean()))
    /** A stored override leaves out a wrap of zero. */
    private val storedMesh = s.obj(meshFields, meshFields.keys - "wrap")
    private val upscaleFields = mapOf("scale" to s.integer(1, 4), "python" to s.string(), "nunifDirectory" to s.string(),
        "modelDirectory" to s.string(), "tileSize" to s.integer(64, 512), "noiseLevel" to s.integer(-1, 3), "neuralAlpha" to s.boolean())
    // TextureUpscaleConfig serialization intentionally omits default values.
    private val upscale = s.obj(upscaleFields, emptySet())
    private val booleanFields = listOf("meshSuppressBoundaryDiagonals", "meshOnly", "generateDeformers", "featureDisplacementEnabled", "mouthOutlineEnabled",
        "exportMotions", "motionBasic", "motionIdle", "motionBlink", "motionNod", "motionShake", "motionSkeleton", "generatePhysics",
        "physicsFrontHair", "physicsBackHair", "physicsEyeJelly", "hairSimulationFront", "hairSimulationBack", "exportCmo3", "exportMoc3", "exportJson",
        "exportHiddenParts", "exportHiddenDrawables", "exportGuideImageParts", "exportIncludePhysics", "exportIncludeUserData", "exportIncludeDisplayInfo")
    private val settingFields = booleanFields.associateWith { s.boolean() } +
        listOf("atlasSize", "meshSpacing", "texturePadding", "alphaThreshold").associateWith { s.integer() } +
        listOf("meshOuterMargin", "meshEdgeWidth", "meshMaxEdgeDistance", "meshInteriorDensity", "headStrength", "bodyStrength", "mouthThickness").associateWith { s.number() } +
        mapOf("textureUpscale" to upscale, "meshEdgeMode" to meshFields.getValue("edgeMode"), "meshFillAlgorithm" to meshFields.getValue("fillAlgorithm"),
            "meshUnits" to s.choices(*io.github.psd2live.core.MeshUnits.entries.map { it.name }.toTypedArray()),
            "meshFillParameters" to fill, "meshOverrides" to s.dictionary(storedMesh), "drawOrderOverrides" to s.dictionary(s.number()),
            "rigTuning" to s.obj(RigTuning.fields.associate { it.id to s.number() }), "mouthShape" to s.choices("flat", "smile", "w", "custom"),
            "mouthCurve" to s.array(s.obj(mapOf("x" to s.number(), "y" to s.number()))), "mouthColor" to s.nullable(s.integer(0, 0xFFFFFF)),
            "runtimeTarget" to s.choices(*RuntimeTarget.entries.map { it.name }.toTypedArray()), "exportPixelsPerUnit" to s.nullable(s.number()),
            "meshTrace" to s.choices(*io.github.psd2live.core.MeshTrace.entries.map { it.name }.toTypedArray()),
            "meshWrap" to s.number())
    /** A canvas-traced project leaves the trace out, and one without wrap the wrap, as projects saved before them did. */
    val settings = s.obj(settingFields, settingFields.keys - "meshTrace" - "meshWrap")
}
