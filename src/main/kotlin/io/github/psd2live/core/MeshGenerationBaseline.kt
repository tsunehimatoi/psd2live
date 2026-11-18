package io.github.psd2live.core

import io.github.psd2live.project.WorkspaceSettingsCodec
import kotlinx.serialization.json.*
import org.umamo.runtime.model.PuppetModel

/** Ordered topology records require the mesh generator that preceded the first record. */
internal object MeshGenerationBaseline {
    const val OP = "mesh_generation_baseline"
    private val legacyFields = setOf("meshSpacing", "meshOuterMargin", "meshEdgeMode", "meshEdgeWidth",
        "meshMaxEdgeDistance", "meshInteriorDensity", "meshFillAlgorithm", "meshSuppressBoundaryDiagonals",
        "meshFillParameters", "meshOverrides", "alphaThreshold")
    private val unitFields = legacyFields + "meshUnits"
    /** A texture-traced baseline also records its trace; a canvas-traced one leaves it out, as before it existed. */
    private val fields = unitFields + "meshTrace"
    /** A wrapping baseline also records its wrap, under either trace. */
    private const val WRAP = "meshWrap"

    fun present(overlay: RigEditOverlay) = overlay.authoringJournal.any { it["op"]?.jsonPrimitive?.contentOrNull == OP }

    fun preserve(overlay: RigEditOverlay, config: PipelineConfig): RigEditOverlay {
        if (present(overlay)) return overlay
        val marker = buildJsonObject {
            put("op", OP)
            put("settings", JsonObject(WorkspaceSettingsCodec.encode(config).filterKeys { it in fields || it == WRAP || it == "meshAlphaCoverage" }))
        }
        return overlay.copy(authoringJournal = overlay.authoringJournal + marker)
    }

    fun restore(config: PipelineConfig): PipelineConfig {
        val generation = RigGenerationBaseline.restore(config)
        val markers = config.rigEdits.authoringJournal.filter { it["op"]?.jsonPrimitive?.contentOrNull == OP }
        if (markers.isEmpty()) return generation
        require(markers.size == 1) { "Duplicate mesh generation baseline" }
        val settings = settings(markers.single())
        val overrides = settings.getValue("meshOverrides").jsonObject.mapValues { (_, value) ->
            val mesh = value.jsonObject
            MeshSettings(mesh.getValue("outerMargin").jsonPrimitive.float,
                MeshEdgeMode.valueOf(mesh.getValue("edgeMode").jsonPrimitive.content),
                mesh.getValue("edgeWidth").jsonPrimitive.float,
                mesh.getValue("maxEdgeDistance").jsonPrimitive.float,
                mesh.getValue("interiorDensity").jsonPrimitive.float,
                MeshFillAlgorithm.valueOf(mesh.getValue("fillAlgorithm").jsonPrimitive.content),
                mesh.getValue("suppressBoundaryDiagonals").jsonPrimitive.boolean,
                WorkspaceSettingsCodec.decodeFillParameters(mesh.getValue("fillParameters")),
                WorkspaceSettingsCodec.decodeWrap(mesh["wrap"]))
        }
        val basis = if ("meshUnits" in settings) generation else generation.copy(meshUnits = MeshUnits.PIXELS)
        return WorkspaceSettingsCodec.decode(settings, basis).copy(meshOverrides = overrides)
    }

    private fun settings(command: JsonObject): JsonObject {
        require(command.keys == setOf("op", "settings")) { "Invalid mesh generation baseline" }
        return command.getValue("settings").jsonObject.also {
            val keys = it.keys - WRAP - "meshAlphaCoverage"
            require(keys == fields || keys == unitFields || (keys == legacyFields && WRAP !in it)) { "Invalid mesh generation baseline settings" }
            it["meshUnits"]?.jsonPrimitive?.content?.let { units ->
                require(MeshUnits.entries.any { value -> value.name == units }) { "Invalid baseline mesh units" }
            }
            it["meshTrace"]?.jsonPrimitive?.content?.let { trace ->
                require(MeshTrace.entries.any { value -> value.name == trace }) { "Invalid baseline mesh trace" }
            }
        }
    }

    fun replay(model: PuppetModel, command: JsonObject): PuppetModel {
        settings(command)
        return model
    }
}
