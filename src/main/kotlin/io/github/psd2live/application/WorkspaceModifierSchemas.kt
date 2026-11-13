package io.github.psd2live.application

import io.github.psd2live.core.modifier.RigModifierAlgorithms
import io.github.psd2live.core.modifier.RigLegacyBindings
import io.github.psd2live.core.RigPhysicsEdit
import io.github.psd2live.core.sim.GlueRole
import io.github.psd2live.core.sim.RigSimEdit
import kotlinx.serialization.json.*

/** Native generator configurations, shared by insertion, update and captured-stack observations. */
internal object WorkspaceModifierSchemas {
    private val s = WorkspaceResultSchema
    val target = s.obj(mapOf("kind" to s.choices("mesh", "deformer", "part", "layer"), "id" to s.handle()))
    private val range = s.obj(listOf("min", "default", "max").associateWith { s.number() })
    private val input = s.obj(mapOf("parameter" to s.handle(), "weight" to s.number(0, 100),
        "type" to s.choices("x", "angle"), "reflect" to s.boolean()), setOf("parameter", "weight", "type"))
    private val output = s.obj(mapOf("parameter" to s.handle(), "vertex" to s.integer(1), "scale" to s.number(),
        "weight" to s.number(0, 100), "type" to s.choices("x", "angle"), "reflect" to s.boolean()), setOf("parameter", "vertex", "scale"))
    private val segment = s.obj(mapOf("length" to s.number(0), "mobility" to s.number(0, 1), "delay" to s.number(0), "acceleration" to s.number(0)))
    val physics = s.obj(mapOf("id" to s.handle(), "name" to s.handle(), "inputs" to s.array(input), "outputs" to s.array(output),
        "segments" to s.array(segment, 1, RigPhysicsEdit.MAX_SEGMENTS), "normalization" to s.obj(mapOf("position" to range, "angle" to range))),
        setOf("id", "name", "inputs", "outputs", "segments"))
    private val axis = s.obj(mapOf("parameter" to s.handle(), "keys" to s.array(s.number(), 2, 256),
        "offsets" to s.dictionary(s.array(s.array(s.number())))))
    private val bakeInput = s.obj(mapOf("parameter" to s.handle(), "motion_px" to s.number(), "rigid" to s.boolean(),
        "group" to s.choices("sideways", "vertical"), "dropped" to s.constant(true), "forced" to s.constant(true)),
        setOf("parameter", "motion_px", "rigid"))
    private val bakeFields = mapOf("fingerprint" to s.handle(), "vertex_counts" to s.dictionary(s.integer(0)),
        "statics" to s.array(axis), "modes" to s.array(s.obj(mapOf("axis" to axis, "amplitude_px" to s.number(), "energy" to s.number()))),
        "physics" to physics, "extra_physics" to s.array(physics), "inputs" to s.array(bakeInput),
        "visual" to s.array(WorkspaceSimulationChecks.motion), "method" to s.handle()) +
        listOf("fit", "max_error_px", "peak", "clipped", "jerk").associateWith { s.number() }
    private val bake = s.obj(bakeFields, setOf("fingerprint", "vertex_counts", "modes", "fit", "max_error_px", "peak", "clipped", "jerk"))
    private val simFields = mapOf("automatic_weights" to s.array(s.obj(mapOf(
        "op" to s.constant("vertex_group_put"), "target" to s.handle(), "name" to s.handle(),
        "kind" to s.choices("pin", "mass", "wind"), "weights" to s.array(s.number(0, 1))))),
        "id" to s.handle(), "name" to s.handle(), "kind" to s.choices("cloth", "hair"),
        "targets" to s.array(s.handle(), 1, 4096),
        "material" to s.obj(listOf("mass", "stretch", "bend", "damping", "goal", "slack", "area", "anisotropy").associateWith { s.number() }),
        "groups" to s.dictionary(s.handle()), "glue_roles" to s.dictionary(s.choices(*GlueRole.entries.map { it.jsonName }.toTypedArray())),
        "inputs" to s.array(input), "input_ranges" to s.dictionary(s.vector(2)), "enabled" to s.boolean(),
        "modes" to s.integer(1, RigSimEdit.MAX_MODES), "vertical" to s.boolean(), "static_inputs" to s.array(s.handle()),
        "keys" to s.integer(3, 9), "blend_shapes" to s.boolean(), "auto_bake" to s.boolean(), "exaggeration" to s.number(),
        "output_names" to s.dictionary(s.handle()), "outputs" to s.dictionary(s.obj(mapOf("id" to s.handle(), "range" to s.number(), "gain" to s.number()), emptySet())),
        "obstacles" to s.array(s.obj(mapOf("mesh" to s.handle(), "a" to s.integer(0), "b" to s.integer(0), "radius" to s.number(0),
            "radius_b" to s.number(0), "friction" to s.number(0, 1)), setOf("mesh", "a", "radius"))),
        "force_inputs" to s.array(s.handle()), "training_clips" to s.dictionary(s.number(0, 1)))
    private val simRequired = setOf("id", "name", "kind", "targets", "material", "inputs")
    private val simulation = s.obj(simFields + ("bake" to bake), simRequired)
    internal fun configuration(type: String, summary: Boolean = false): JsonObject = when (type) {
        "rig.scaffold" -> s.obj(mapOf("featureDisplacement" to s.boolean(), "frontHairSway" to s.boolean(), "backHairSway" to s.boolean(), "corrections" to
            s.obj(RigModuleSchemas.stored.getValue("properties").jsonObject.filterKeys { it in setOf("moves", "eyeParts") }
                .mapValues { it.value.jsonObject }, emptySet())), emptySet())
        "physics" -> physics
        "motion" -> WorkspaceAnimationResultSchemas.motion
        "swing" -> WorkspaceInspectionResultSchemas.swing
        "simulation" -> if (summary) s.obj(simFields, simRequired) else simulation
        "eye" -> s.obj(mapOf(
            "scaffold" to s.handle(),
            "mode" to s.choices("fitted", "legacy"),
            "wide" to s.boolean(),
            "smile" to s.boolean(),
            "emotion" to s.boolean(),
            "gazeLid" to s.boolean(),
            "highlight" to s.boolean(),
            "lidFade" to s.boolean(),
            "squash" to s.boolean(),
            "aftershock" to s.boolean(),
            "tuning" to s.dictionary(s.number()),
            "values" to s.dictionary(s.number()),
        ), setOf("scaffold"))
        "face" -> s.obj(mapOf(
            "scaffold" to s.handle(),
            "mode" to s.choices("fitted", "legacy"),
            "depthTurn" to s.boolean(),
            "neck" to s.boolean(),
            "strength" to s.number(0, 4),
            "tuning" to s.dictionary(s.number()),
            "values" to s.dictionary(s.number()),
        ), setOf("scaffold"))
        "body" -> s.obj(mapOf(
            "scaffold" to s.handle(),
            "bust" to s.boolean(),
            "arms" to s.boolean(),
            "strength" to s.number(0, 4),
            "tuning" to s.dictionary(s.number()),
            "values" to s.dictionary(s.number()),
        ), setOf("scaffold"))
        "hair" -> s.obj(mapOf(
            "scaffold" to s.handle(),
            "mode" to s.choices("chains", "legacy"),
            "tuning" to s.dictionary(s.number()),
            "values" to s.dictionary(s.number()),
            "physicsEnabled" to s.boolean(),
            "physics" to s.dictionary(physics),
        ), setOf("scaffold"))
        "brow" -> s.obj(mapOf(
            "scaffold" to s.handle(),
            "mode" to s.choices("fitted", "legacy"),
            "shape" to s.boolean(),
            "spring" to s.boolean(),
            "blink" to s.boolean(),
            "lidCoupling" to s.boolean(),
            "tuning" to s.dictionary(s.number()),
            "values" to s.dictionary(s.number()),
        ), setOf("scaffold"))
        "face" -> s.obj(mapOf(
            "scaffold" to s.handle(),
            "mode" to s.choices("fitted", "legacy"),
            "depthTurn" to s.boolean(),
            "neck" to s.boolean(),
            "strength" to s.number(0, 4),
            "tuning" to s.dictionary(s.number()),
            "values" to s.dictionary(s.number()),
        ), setOf("scaffold"))
        "body" -> s.obj(mapOf(
            "scaffold" to s.handle(),
            "bust" to s.boolean(),
            "arms" to s.boolean(),
            "strength" to s.number(0, 4),
            "tuning" to s.dictionary(s.number()),
            "values" to s.dictionary(s.number()),
        ), setOf("scaffold"))
        "hair" -> s.obj(mapOf(
            "scaffold" to s.handle(),
            "mode" to s.choices("chains", "legacy"),
            "tuning" to s.dictionary(s.number()),
            "values" to s.dictionary(s.number()),
            "physicsEnabled" to s.boolean(),
            "physics" to s.dictionary(physics),
        ), setOf("scaffold"))
        else -> RigLegacyBindings.find(type)?.let { spec -> s.obj(mapOf("scaffold" to s.handle(),
            "tuning" to s.obj(spec.fields.associate { it.id to s.number(it.range.start, it.range.endInclusive) }, emptySet())) +
            (if (spec.module?.base != null) mapOf("base" to s.handle()) else emptyMap()) +
            (if (spec.related.isNotEmpty()) mapOf("dependencies" to s.obj(spec.related.associateWith { s.handle() })) else emptyMap()) +
            (if (spec.hasPhysics) mapOf("physicsEnabled" to s.boolean(), "physics" to if (spec.chainKinds.isNotEmpty()) s.dictionary(physics)
                else s.obj(spec.module!!.physicsGroups.associateWith { physics }, emptySet())) else emptyMap()) +
            (if (spec.strength) mapOf("strength" to s.number(0, 4)) else emptyMap()) +
            (if (spec.mouthOutline) mapOf("color" to s.integer(0, 0xffffff)) else emptyMap()) +
            (if (spec.knobs.isNotEmpty()) mapOf("values" to s.obj(spec.knobs.associate { it.id to s.number(it.range.start, it.range.endInclusive) }, emptySet())) else emptyMap()), setOf("scaffold") + (if (spec.module?.base != null) setOf("base") else emptySet()) + (if (spec.related.isNotEmpty()) setOf("dependencies") else emptySet())) }
            ?: error("No modifier configuration schema: $type")
    }

    val types get() = RigModifierAlgorithms.registry.definitions.filter { it.pluginReference == null }.map { it.type }.distinct()
    private fun fields(type: String, summary: Boolean = false) = linkedMapOf("type" to s.constant(type), "config" to configuration(type, summary), "plugin" to pluginIdentity)
    private fun addition(type: String) = s.obj(fields(type) + mapOf("id" to s.handle(), "version" to s.integer(1), "targets" to s.array(target, 1, 4096),
        "group" to s.handle(), "index" to s.integer(0)), setOf("type", "config", "targets"))
    private val pluginIdentity = s.obj(mapOf("id" to s.handle(), "version" to s.handle(), "digest" to s.handle(), "dependencyDigests" to s.dictionary(s.handle())), setOf("id", "version", "digest"))
    private val pluginType get() = JsonObject(s.handle() + ("pattern" to JsonPrimitive("^(?!(?:" + types.joinToString("|") { it.replace(".", "\\.") } + ")$)[a-zA-Z0-9][a-zA-Z0-9._-]*/.+$")))
    private val pluginFields get() = mapOf("type" to pluginType, "config" to s.dictionary(JsonObject(emptyMap())), "plugin" to pluginIdentity)
    private val pluginAddition get() = s.obj(pluginFields + mapOf("id" to s.handle(), "version" to s.integer(1), "targets" to s.array(target, 1, 4096), "group" to s.handle(), "index" to s.integer(0)), setOf("type", "config", "targets"))
    val addition: JsonObject get() = s.union(types.map(::addition) + pluginAddition)
    val organization = s.array(s.obj(mapOf("id" to s.handle(), "parent" to s.nullable(s.handle()), "name" to s.nullable(s.handle()))), 0, 8192)
    private val initialPreset = s.obj(
        listOf("headStrength", "bodyStrength").associateWith { s.number(0, 4) } +
            listOf("featureDisplacement", "mouthOutline", "motionBasic", "motionSkeleton", "clothing", "eyeJelly").associateWith { s.boolean() } +
            listOf("frontHair", "backHair").associateWith { s.choices("CLASSIC", "SIMULATION", "OFF") }, emptySet())
    fun request(id: String): JsonObject {
        val business = when (id) {
            "modifier_add" -> addition
            "modifier_add_group" -> s.obj(mapOf("group" to s.handle(), "entries" to s.array(addition, 1, 128)))
            "modifier_update" -> s.union(types.map { type -> s.obj(fields(type) + ("id" to s.handle()), setOf("id", "type", "config")) } + s.obj(pluginFields + ("id" to s.handle()), setOf("id", "type", "config")))
            "modifier_enable" -> s.obj(mapOf("id" to s.handle(), "enabled" to s.boolean()))
            "modifier_organize" -> s.obj(mapOf("nodes" to organization))
            "modifier_move" -> s.obj(mapOf("id" to s.handle(), "index" to s.integer(0)))
            "modifier_convert" -> s.obj(emptyMap())
            "modifier_initialize" -> s.obj(mapOf(
                "modifiers" to s.choices(*WorkspaceInitialModifiers.entries.map { it.name.lowercase() }.toTypedArray()),
                "preset" to initialPreset), setOf("modifiers"))
            "modifier_switch" -> s.obj(mapOf("group" to s.handle()))
            "modifier_remove", "modifier_apply" -> s.obj(mapOf("id" to s.handle()))
            else -> error("Unknown modifier operation $id")
        }
        fun contextual(schema: JsonObject): JsonObject {
            val properties = schema.getValue("properties").jsonObject + ("state" to s.handle())
            val required = schema["required"]?.jsonArray.orEmpty() + JsonPrimitive("state")
            return JsonObject(schema + mapOf("properties" to JsonObject(properties), "required" to JsonArray(required)))
        }
        val branches = business["oneOf"]?.jsonArray
        return if (branches == null) contextual(business) else s.union(branches.map { contextual(it.jsonObject) })
    }

    private val contribution = s.obj(mapOf("kind" to s.handle(), "id" to s.handle(), "change" to s.choices("added", "modified", "removed", "reused"), "name" to s.string(), "physics_slot" to s.handle(),
        "mask" to s.obj(mapOf("target" to s.handle(), "before" to s.array(s.handle()), "after" to s.array(s.handle()),
            "before_inverted" to s.boolean(), "after_inverted" to s.boolean()))), setOf("kind", "id", "change"))
    private val ownedKeyform = s.obj(mapOf("kind" to s.choices("warp", "mesh"), "id" to s.handle(), "parameter" to s.handle(),
        "channel" to s.handle(), "key" to s.dictionary(s.number()), "object_name" to s.string(), "parameter_name" to s.string()), setOf("kind", "id", "parameter"))
    private val overrideIssue = s.obj(mapOf("kind" to s.choices("orphaned", "conflict"), "target" to s.handle(),
        "key" to s.dictionary(s.number()), "points" to s.integer(0), "total" to s.integer(0),
        "reason" to s.nullable(s.choices("missing_keyform", "shape_mismatch", "superseded")), "channel" to s.nullable(s.handle())))
    private val observedFields = mapOf("id" to s.handle(), "version" to s.integer(1), "enabled" to s.boolean(),
        "targets" to s.array(target, 1, 4096), "status" to s.choices("active", "disabled", "missing_dependency", "missing_plugin"),
        "parameter_providers" to s.dictionary(s.handle()), "missing_dependencies" to s.array(s.handle()), "contributions" to s.array(contribution), "group" to s.handle(),
        "owned_keyforms" to s.array(ownedKeyform), "override_issues" to s.array(overrideIssue))
    /** Candidate observations, without configuration or geometry payloads, for the shared dry-run result. */
    val reportEntries: JsonObject get() = s.array(s.obj(observedFields - "group" + ("type" to s.handle())))
    val stack: JsonObject get() = s.obj(s.identity + mapOf("revision" to s.handle(), "legacy" to s.boolean(),
        "entries" to s.array(s.union(types.map { type ->
            val fields = fields(type, summary = true) + observedFields + if (type == "simulation") mapOf("bake" to WorkspaceSimulationResultSchemas.bakeSummary) else emptyMap()
            s.obj(fields, fields.keys - "group" - "bake" - "plugin")
        } + s.obj(pluginFields + observedFields, (pluginFields.keys + observedFields.keys) - "group" - "plugin"))), "retired_ids" to s.array(s.handle()), "next_id" to s.integer(1), "organization" to organization))
    val planRequest: JsonObject get() = s.union(listOf(
        s.obj(mapOf("type" to s.choices(*WorkspaceModifierPlan.sourceTypes.toTypedArray()), "targets" to s.array(target, 1, 4096))),
        s.obj(mapOf("group" to s.choices(*WorkspaceModifierPlan.groups.keys.toTypedArray()), "targets" to s.array(target, 1, 4096)))) +
        WorkspaceModifierPlan.nativeTypes.map { type -> s.obj(mapOf("type" to s.constant(type), "targets" to s.array(target, 1, 4096),
            "config" to configuration(type)), setOf("type", "targets")) } + listOf(
            s.obj(mapOf("type" to s.constant("motion"), "targets" to s.array(target, 1, 4096),
                "preset" to s.choices(*WorkspaceModifierPlan.motionPresetIds.toTypedArray()))),
            s.obj(mapOf("type" to s.constant("simulation"), "targets" to s.array(target, 1, 4096),
                "preset" to s.choices("clothing", "front_hair", "back_hair"))),
            s.obj(mapOf("type" to s.constant("physics"), "targets" to s.array(target, 1, 4096),
                "preset" to s.choices(*io.github.psd2live.core.PhysicsGenerator.presetIds.toTypedArray()))),
            s.obj(mapOf("type" to pluginType, "targets" to s.array(target, 1, 4096), "config" to s.dictionary(JsonObject(emptyMap()))), setOf("type", "targets"))))
    val plan: JsonObject get() = s.obj(s.identity + mapOf("revision" to s.handle(), "group" to s.handle(), "entries" to s.array(addition, 0, 128),
        "plan" to s.array(s.obj(mapOf("id" to s.handle(), "type" to s.handle(), "version" to s.integer(1),
            "targets" to s.array(target, 1, 4096), "disposition" to s.choices("added", "reused", "applied"))))))
    val catalog = s.obj(mapOf("baselines" to s.array(s.handle()),
        "presets" to s.array(s.obj(mapOf("id" to s.handle(), "name" to s.handle(), "name_key" to s.nullable(s.handle())))),
        "types" to s.array(s.obj(mapOf("type" to s.handle(), "version" to s.integer(1),
        "requires" to s.array(s.handle()), "provides" to s.array(s.handle()), "exclusive_family" to s.nullable(s.handle())))),
        "groups" to s.array(s.obj(mapOf("id" to s.handle(), "algorithm" to s.choices("legacy", "modern"),
            "richness" to s.choices("minimal", "standard", "full"), "types" to s.array(s.handle()))))))
}
