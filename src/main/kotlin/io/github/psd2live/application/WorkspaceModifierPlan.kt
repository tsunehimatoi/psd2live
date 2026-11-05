package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.core.modifier.*
import io.github.psd2live.core.sim.*
import kotlinx.serialization.json.*

/** Captured insertion: resolve dependencies without changing or silently sorting existing entries. */
internal object WorkspaceModifierPlan {
    val sourceTypes get() = listOf(RigSourceModifiers.SCAFFOLD) + RigLegacyBindings.specs.map { it.type }
    const val NATURAL_MOTION_PREFIX = "natural:"
    val motionPresetIds = MotionClips.BUILTIN_NAMES + MotionClips.BASIC_NAMES.map { NATURAL_MOTION_PREFIX + it }
    val nativeTypes = listOf("swing", "physics", "motion", "simulation")
    val types get() = sourceTypes + nativeTypes
    val groups = mapOf("fitted" to listOf("body/legacy", "face/legacy", "eye/fitted", "brow/fitted", "mouth/fitted", "hair/legacy", "face.depth-turn"))

    fun prepare(capture: WorkspaceCapture<RigPreviewModel>, request: JsonObject): JsonObject {
        val stack = RigModifierStack.fromJson(requireNotNull(capture.document.rigEdits.modifierStack) { "Convert legacy presets before adding modifiers" })
        val (_, plan) = plan(capture.model, stack, request)
        return JsonObject(plan + buildJsonObject {
            put("state", capture.state); put("project_id", capture.projectId)
            put("history_node_id", capture.historyHead); put("revision", capture.revision)
        })
    }

    fun initial(model: RigPreviewModel, group: String): RigModifierStack {
        val targets = model.rig.puppet.drawables.filter { it.mesh != null && it.id.raw in model.rig.ownLayerIdByDrawableId }
        if (targets.isEmpty()) return RigModifierStack()
        return plan(model, RigModifierStack(), buildJsonObject {
            put("group", group)
            putJsonArray("targets") { targets.forEach { addJsonObject { put("kind", "mesh"); put("id", it.id.raw) } } }
        }).first
    }

    private fun plan(model: RigPreviewModel, stack: RigModifierStack, request: JsonObject): Pair<RigModifierStack, JsonObject> {
        val targets = request.getValue("targets").jsonArray.map { raw -> raw.jsonObject.let {
            ModifierTarget(it.getValue("kind").jsonPrimitive.content, it.getValue("id").jsonPrimitive.content)
        } }
        require(targets.isNotEmpty() && targets.distinct().size == targets.size) { "Insertion needs distinct captured targets" }
        val puppet = model.rig.puppet
        require(targets.all { target -> when (target.kind) {
            "mesh" -> puppet.drawables.any { it.id.raw == target.id && it.mesh != null }
            "deformer" -> puppet.deformers.any { it.id.raw == target.id && it is org.umamo.runtime.model.Deformer.Warp }
            else -> false
        } }) { "An insertion target no longer exists" }
        val type = request["type"]?.jsonPrimitive?.content
        val group = request["group"]?.jsonPrimitive?.content
        require((type == null) != (group == null)) { "Choose a modifier or a preset group" }
        val requested = if (type != null) listOf(type) else requireNotNull(groups[group]) { "Unknown source preset group: $group" }
        require(requested.all { it in types }) { "Unknown modifier" }
        require(request["preset"] == null || type in setOf("motion", "physics") && request["config"] == null) { "A preset and an explicit configuration are alternative inputs" }
        require(type in nativeTypes || request["config"] == null) { "Source binding configuration is resolved from its captured dependencies" }
        require(type == "swing" || targets.all { it.kind == "mesh" }) { "This modifier needs captured mesh targets" }
        val active = model.rig.modifierReport!!.getValue("entries").jsonArray.filter {
            it.jsonObject.getValue("status").jsonPrimitive.content == "active"
        }.mapTo(HashSet()) { it.jsonObject.getValue("id").jsonPrimitive.content }
        val available = stack.appliedCapabilities.toMutableList()
        available += stack.instances.filter { it.id in active }.map { ModifierCapability(it.type, it.version, it.targets, it.id, it.config) }
        var candidate = stack
        val entries = ArrayList<JsonObject>()
        val planned = LinkedHashMap<String, JsonObject>()
        fun describe(capability: ModifierCapability, disposition: String) = buildJsonObject {
            put("id", requireNotNull(capability.id)); put("type", capability.type); put("version", capability.version); put("disposition", disposition)
            put("targets", JsonArray(capability.targets.map { buildJsonObject { put("kind", it.kind); put("id", it.id) } }))
        }
        fun ensure(wanted: String, reuse: Boolean = true, scaffold: String? = null): ModifierCapability {
            if (reuse) available.firstOrNull { cap -> cap.type == wanted && cap.id != null && targets.all { it in cap.targets } &&
                (scaffold == null || cap.config["scaffold"]?.jsonPrimitive?.content == scaffold) }?.let { cap ->
                planned.putIfAbsent(cap.id!!, describe(cap, if (stack.instances.any { it.id == cap.id }) "reused" else if (cap.id in stack.retiredIds) "applied" else "added"))
                return cap
            }
            val spec = RigLegacyBindings.find(wanted)
            val preset = request["preset"]?.jsonPrimitive?.content
            val configuration = if (wanted == "physics" && preset != null) {
                val fullRule = requireNotNull(physicsPresets().firstOrNull { it.id == preset }) { "Unknown physics preset: $preset" }
                val parameters = puppet.parameters.mapTo(HashSet()) { it.id.raw }
                val claimed = model.rig.modifierOutput?.physics?.groups.orEmpty().flatMap { it.outputs }.mapTo(HashSet()) { it.parameter }
                require(fullRule.outputs.none { it.parameter in claimed }) { "Physics preset $preset has an already driven output" }
                val missingOutput = fullRule.outputs.any { it.parameter !in parameters }
                val missingInput = fullRule.inputs.none { it.parameter in parameters }
                fun include(type: String) {
                    ensure(type)
                    parameters += RigLegacyBindings.spec(type).parameters.map { it.raw }
                }
                if (preset == PhysicsGenerator.EYE_JELLY_ID) {
                    if (missingInput || missingOutput) include("eye/fitted")
                } else {
                    if (missingInput) {
                        include("body/legacy")
                        include("face/legacy")
                    }
                    if (missingOutput) include("hair/legacy")
                }
                val rule = PhysicsGenerator.presetRules(PhysicsGenerator.Presets.All, parameters).single { it.id == preset }
                require(PhysicsCatalog.issueOf(rule, parameters) == null) { "Physics preset $preset needs its input and output parameters" }
                rule.toJson()
            } else if (wanted == "motion" && preset != null && isNaturalMotion(preset)) {
                require(preset in motionPresetIds) { "Unknown generated motion preset: $preset" }
                val parameters = puppet.parameters.toMutableList()
                val missing = motionTracks(model, preset, includeMissing = true).mapTo(HashSet()) { it.parameterId }
                missing.removeAll(parameters.map { it.id.raw }.toSet())
                for (type in listOf("body/legacy", "face/legacy", "eye/fitted", "brow/fitted", "mouth/fitted", "hair/legacy")) {
                    val provided = RigLegacyBindings.spec(type).parameters
                    if (provided.none { it.raw in missing }) continue
                    ensure(type)
                    val present = parameters.mapTo(HashSet()) { it.id }
                    parameters += StandardParameters.all.filter { it.id in provided && it.id !in present }
                    missing.removeAll(provided.map { it.raw }.toSet())
                }
                val vowel = RigLegacyBindings.spec("mouth.vowel")
                val hasLegacyMouth = available.any { it.type == "mouth/legacy" && it.targets.any(targets::contains) }
                if (!hasLegacyMouth && vowel.parameters.any { it.raw in missing } &&
                    requireNotNull(vowel.module).applies(io.github.psd2live.core.recipe.RecipeSubject.of(model.analysis))) {
                    ensure(vowel.type)
                    for (definition in vowel.module.defines) {
                        if (parameters.any { it.id.raw == definition.id }) continue
                        parameters += org.umamo.runtime.model.Parameter(org.umamo.runtime.model.ParameterId(definition.id),
                            definition.id, definition.min, definition.max, definition.default)
                    }
                }
                motionConfiguration(model, preset, parameters)
            } else if (wanted in nativeTypes) request["config"]?.jsonObject ?: nativeConfiguration(model, wanted, targets, preset)
            else if (spec == null) JsonObject(emptyMap()) else {
                val structure = scaffold ?: requireNotNull(ensure(RigSourceModifiers.SCAFFOLD).id)
                val base = spec.module?.base?.let { ensure(it, scaffold = structure) }
                val related = spec.related.associateWith { ensure(it, scaffold = structure) }
                buildJsonObject {
                    put("scaffold", structure)
                    base?.id?.let { put("base", it) }
                    if (related.isNotEmpty()) put("dependencies", JsonObject(related.mapValues { JsonPrimitive(requireNotNull(it.value.id)) }))
                }
            }
            val version = RigModifierAlgorithms.registry.definitions.filter { it.type == wanted }.maxOf { it.version }
            candidate = candidate.add(wanted, version, targets, configuration, group)
            val instance = candidate.instances.last()
            val cap = ModifierCapability(wanted, version, targets, instance.id, configuration)
            available += cap
            entries += buildJsonObject {
                put("id", instance.id); put("type", wanted); put("version", version); put("config", configuration)
                put("targets", JsonArray(targets.map { buildJsonObject { put("kind", it.kind); put("id", it.id) } }))
            }
            planned[instance.id] = describe(cap, "added")
            return cap
        }
        val subject = io.github.psd2live.core.recipe.RecipeSubject.of(model.analysis)
        requested.filter { group == null || RigLegacyBindings.find(it)?.module?.applies?.invoke(subject) != false }
            .forEach { ensure(it, reuse = group != null) }
        RigModifierAlgorithms.evaluator().validateOrder(candidate)
        return candidate to buildJsonObject {
            put("entries", JsonArray(entries)); put("plan", JsonArray(planned.values.toList()))
            put("group", group ?: "custom")
        }
    }

    fun physicsPresets(): List<RigPhysicsEdit> = PhysicsGenerator.presetRules(PhysicsGenerator.Presets.All)

    private fun isNaturalMotion(preset: String) = preset.startsWith(NATURAL_MOTION_PREFIX) || preset == "Talk"

    private fun motionTracks(model: RigPreviewModel, preset: String, includeMissing: Boolean = false): List<MotionTrack> {
        val name = preset.removePrefix(NATURAL_MOTION_PREFIX)
        val settings = MotionPresetSettings(natural = isNaturalMotion(preset))
        val available = model.rig.puppet.parameters.mapTo(HashSet()) { it.id.raw }
        val driven = if (name == "Idle") model.rig.modifierOutput?.physics?.groups.orEmpty()
            .flatMap { it.outputs }.mapTo(HashSet()) { it.parameter } else emptySet()
        return MotionClips.builtinTracks(name, model.config.rigEdits.skeleton, driven, settings)
            .filter { includeMissing || it.parameterId in available }
    }

    fun availableMotionPresets(model: RigPreviewModel): List<String> =
        motionPresetIds.filter { it != "Talk" && motionTracks(model, it).isNotEmpty() }

    fun selectableMotionPresets(model: RigPreviewModel): List<String> =
        (availableMotionPresets(model) + motionPresetIds.filter { it.startsWith(NATURAL_MOTION_PREFIX) }).distinct()

    private fun motionConfiguration(model: RigPreviewModel, preset: String, parameters: List<org.umamo.runtime.model.Parameter>): JsonObject {
        val name = preset.removePrefix(NATURAL_MOTION_PREFIX)
        val natural = isNaturalMotion(preset)
        val available = parameters.mapTo(HashSet()) { it.id.raw }
        val tracks = motionTracks(model, preset, includeMissing = true).filter { it.parameterId in available }
        require(tracks.isNotEmpty()) { "Generated motion $preset needs binding parameters that are not present" }
        val duration = MotionClips.builtinDuration(name, tracks, MotionPresetSettings(natural = natural))
        val clip = MotionClips.fromTracks(if (natural) NATURAL_MOTION_PREFIX + name else "template", name, null,
            MotionClips.isLoopBuiltin(name), tracks, duration)
        val roles = if (name == "Blink") mapOf("EyeBlink" to listOf("ParamEyeLOpen", "ParamEyeROpen").filter { it in available }) else emptyMap()
        return MotionClips.toJson(MotionClips.reconcileParameters(listOf(clip.copy(parameterRoles = roles.filterValues { it.isNotEmpty() })), parameters).single())
    }

    private fun nativeConfiguration(model: RigPreviewModel, type: String, targets: List<ModifierTarget>, preset: String?): JsonObject {
        return when (type) {
            "swing" -> RigSwingEdit.single("template", "Swing", SwingKind.LATERAL, targets.map { it.id }, listOf("output")).toJson()
            "simulation" -> RigSimEdit("template", "Simulation", SimKind.HAIR, targets.map { it.id },
                inputs = RigSimEdit.defaultInputs(model.rig.puppet.parameters.mapTo(HashSet()) { it.id.raw }, SimKind.HAIR)).toJson()
            "physics" -> {
                val parameters = model.rig.puppet.parameters.map { it.id.raw }
                val claimed = model.rig.modifierOutput?.physics?.groups.orEmpty().flatMap { it.outputs }.mapTo(HashSet()) { it.parameter }
                val output = parameters.firstOrNull { it !in claimed }
                require(output != null && parameters.any { it != output }) { "Physics needs an available output and a distinct input parameter; add parameters first" }
                RigPhysicsEdit("template", "Physics", listOf(PhysicsInput(parameters.first { it != output })), listOf(PhysicsOutput(output))).toJson()
            }
            "motion" -> {
                require(preset == null || preset in motionPresetIds) { "Unknown generated motion preset: $preset" }
                val chosen = requireNotNull(preset ?: availableMotionPresets(model).firstOrNull()) {
                    "No generated motion matches existing parameters; add binding modifiers or supply an explicit clip"
                }
                motionConfiguration(model, chosen, model.rig.puppet.parameters)
            }
            else -> error("Unknown native modifier: $type")
        }
    }
}
