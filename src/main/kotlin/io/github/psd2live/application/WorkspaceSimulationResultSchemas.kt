package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.core.sim.*
import kotlinx.serialization.json.*
import org.umamo.runtime.model.VertexGroupKind

/** Small simulation diagnostics, without serialized vertex arrays or materialized bake keyforms. */
internal object WorkspaceSimulationResultSchemas {
    private val s = WorkspaceResultSchema
    private val summaryFields = linkedMapOf(
        "modes" to s.array(s.obj(mapOf("parameter" to s.handle(), "amplitude_px" to s.number(), "energy" to s.number()))),
        "keys" to s.integer(2), "pendulum" to s.handle(), "segments" to s.integer(1),
        "own_pendulums" to s.array(s.handle(), 1, Int.MAX_VALUE), "static_inputs" to s.array(s.handle(), 1, Int.MAX_VALUE),
    ) + listOf("fit_r2", "error_p95_px", "parameter_peak", "clipped_frames", "jerk_ratio").associateWith { s.number() }
        .plus(linkedMapOf(
            "inputs" to s.array(s.obj(mapOf("parameter" to s.handle(), "motion_px" to s.number(), "rigid" to s.boolean(),
                "group" to s.choices(SimInputCheck.SIDEWAYS, SimInputCheck.VERTICAL), "dropped" to s.constant(true), "forced" to s.constant(true)),
                setOf("parameter", "motion_px", "rigid")), 1, Int.MAX_VALUE),
            "visual" to s.array(WorkspaceSimulationChecks.motion, 1, Int.MAX_VALUE),
            "method" to s.choices(*SimBaker.Method.entries.map { it.name.lowercase() }.toTypedArray())))
    private val summaryRequired = setOf("modes", "fit_r2", "error_p95_px", "parameter_peak", "clipped_frames", "jerk_ratio")
    val bakeSummary = s.obj(summaryFields, summaryRequired)
    private val compactFields = WorkspaceAuthoringResultSchemas.compactFields
    private val compactRequired = s.identity.keys
    private val put = s.union(listOf(
        s.obj(compactFields, compactRequired),
        s.obj(compactFields + ("bake" to bakeSummary), compactRequired + "bake"),
        s.obj(compactFields + ("bake_error" to s.string()), compactRequired + "bake_error"),
    ))
    private val bake = s.obj(compactFields + summaryFields, compactRequired + summaryRequired)
    private val garmentFields = mapOf("garment" to s.choices(*ClothFit.Wear.entries.map { it.jsonName }.toTypedArray()),
        "decided_by" to s.choices("name", "silhouette"), "waist" to s.number(), "crotch" to s.number(), "hem" to s.number(),
        "loose" to s.number(0, 1), "hang_from" to s.number(), "simulated" to s.boolean())
    private val garments = s.dictionary(s.obj(garmentFields, setOf("garment", "loose", "simulated")))
    private val preset = s.union(listOf(s.obj(compactFields, compactRequired),
        s.obj(compactFields + mapOf("simulations" to s.array(s.handle()), "garments" to garments,
            "bakes" to s.dictionary(s.union(listOf(bakeSummary, s.obj(mapOf("error" to s.string()))))), "warnings" to s.array(s.string())),
            compactRequired + setOf("simulations", "bakes"))))
    private val reportFields = mapOf("id" to s.handle(), "particles" to s.integer(0), "pinned" to s.integer(0),
        "calibration_residual_px" to s.number(), "rest_drift_px" to s.number(), "max_stretch_percent" to s.number(),
        "phases" to s.array(s.union(listOf(
            s.obj(mapOf("input" to s.handle(), "type" to s.choices("x", "angle"), "peak_px" to s.number(), "after_release_px" to s.number())),
            s.obj(mapOf("input" to s.constant("wind"), "peak_px" to s.number(), "after_release_px" to s.number())),
        ))),
        "notes" to s.array(s.string(), 1, Int.MAX_VALUE))
    private val report = s.obj(reportFields, reportFields.keys - "notes")
    private val material = s.obj(listOf("mass", "stretch", "bend", "damping", "goal", "slack", "area", "anisotropy").associateWith { s.number() })
    private val output = s.obj(mapOf("id" to s.handle(), "range" to s.number(), "gain" to s.number()), emptySet())
    private val simulationFields = linkedMapOf("id" to s.handle(), "name" to s.handle(),
        "kind" to s.choices(*SimKind.entries.map { it.jsonName }.toTypedArray()), "targets" to s.array(s.handle(), 1, Int.MAX_VALUE),
        "material" to material, "groups" to s.obj(VertexGroupKind.entries.associate { it.jsonName to s.string() }, emptySet()),
        "glue_roles" to s.dictionary(s.choices(*GlueRole.entries.map { it.jsonName }.toTypedArray())),
        "inputs" to s.array(WorkspacePhysicsResultTypes.input), "input_ranges" to s.dictionary(s.vector(2)),
        "enabled" to s.constant(false), "modes" to s.integer(1, RigSimEdit.MAX_MODES), "vertical" to s.boolean(),
        "static_inputs" to s.array(s.handle(), 0, RigSimEdit.MAX_STATIC_INPUTS), "keys" to s.integer(3, 9),
        "blend_shapes" to s.boolean(), "auto_bake" to s.constant(false), "exaggeration" to s.number(1, 2),
        "output_names" to s.dictionary(s.handle()), "outputs" to s.dictionary(output),
        "force_inputs" to s.array(s.handle(), 1, Int.MAX_VALUE), "training_clips" to s.dictionary(s.number(0, 1)))
    private val simulationRequired = setOf("id", "name", "kind", "targets", "material", "inputs")
    val inspectedSimulation = s.union(listOf(s.obj(simulationFields, simulationRequired),
        s.obj(simulationFields + mapOf("bake" to bakeSummary, "bake_stale" to s.boolean()), simulationRequired + "bake")))

    fun forOperation(id: String): JsonObject? = when (id) {
        "simulation_put" -> put
        "simulation_bake" -> bake
        "simulation_simulate" -> report
        "simulation_compare" -> s.obj(mapOf("id" to s.handle(), "motions" to s.array(WorkspaceSimulationChecks.motion, 1, Int.MAX_VALUE)))
        "model_apply_preset" -> preset
        else -> null
    }
}

/** What a viewer sees of a baked simulation against its reference over one motion ([SimVisualCheck]). */
internal object WorkspaceSimulationChecks {
    private val s = WorkspaceResultSchema
    val metrics = s.obj(listOf("dir", "sign", "amp", "reach", "lag_ms", "settle", "overshoot", "jitter", "rough", "clipped", "stalled",
        "shorten", "static_px", "static_share", "r2", "p95_px", "sim_px").associateWith { s.number() }, emptySet())
    val motion = s.obj(mapOf("motion" to s.string(), "metrics" to metrics))
}

internal object WorkspacePhysicsResultTypes {
    private val s = WorkspaceResultSchema
    private val type = s.choices(*PhysicsSourceType.entries.map { it.name.lowercase() }.toTypedArray())
    val input = s.obj(mapOf("parameter" to s.handle(), "weight" to s.number(0, 100), "type" to type, "reflect" to s.constant(true)),
        setOf("parameter", "weight", "type"))
    private val output = s.obj(mapOf("parameter" to s.handle(), "vertex" to s.integer(1), "scale" to s.number(), "weight" to s.number(0, 100),
        "type" to type, "reflect" to s.constant(true)), setOf("parameter", "vertex", "scale"))
    private val segment = s.obj(mapOf("length" to s.number(0), "mobility" to s.number(0, 1), "delay" to s.number(0), "acceleration" to s.number(0)))
    private val range = s.obj(listOf("min", "default", "max").associateWith { s.number() })
    val group = s.obj(mapOf("id" to s.handle(), "name" to s.handle(), "inputs" to s.array(input), "outputs" to s.array(output),
        "segments" to s.array(segment, 1, Int.MAX_VALUE), "normalization" to s.obj(mapOf("position" to range, "angle" to range)),
        "origin" to s.choices(*PhysicsOrigin.entries.map { it.name.lowercase() }.toTypedArray()), "enabled" to s.boolean(),
        "overridden" to s.constant(true), "active" to s.boolean(), "issue" to s.string(), "replaced_by" to s.handle()),
        setOf("id", "name", "inputs", "outputs", "segments", "origin", "enabled", "active"))
}
