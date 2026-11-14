package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.core.modifier.*
import io.github.psd2live.core.sim.*
import kotlinx.serialization.json.*

/** Captured insertion: resolve dependencies without changing or silently sorting existing entries. */
internal object WorkspaceModifierPlan {
    val sourceTypes get() = listOf(RigSourceModifiers.SCAFFOLD) + RigSourceModifiers.PART_TYPES + RigLegacyBindings.specs.map { it.type }.filterNot { it == "face.depth-turn" }
    const val NATURAL_MOTION_PREFIX = "natural:"
    val motionPresetIds = MotionClips.BUILTIN_NAMES + MotionClips.BASIC_NAMES.map { NATURAL_MOTION_PREFIX + it }
    val nativeTypes = listOf("swing", "physics", "motion", "simulation")
    val types get() = (sourceTypes + nativeTypes).filter { ModifierPluginStore.shared.builtinsEnabled() } + ModifierPluginStore.shared.runnablePackages().filter { ModifierPluginStore.shared.resolution(it.reference).available }.flatMap { it.manifest.algorithms.map { it.type } }.distinct()
    const val RECOMMENDED_GROUP = "modern-standard"
    data class Group(val id: String, val algorithm: String, val richness: String, val types: List<String>)
    val groupCatalog: List<Group> = listOf("legacy", "modern").flatMap { algorithm ->
        listOf("minimal", "standard", "full").map { richness ->
            val bases = if (algorithm == "legacy") listOf("eye/legacy", "brow/legacy", "mouth/legacy", "body/legacy", "face/legacy", "hair/legacy")
                else listOf("eye/fitted", "brow/fitted", "mouth/fitted", "body/legacy", RigLegacyBindings.FITTED_FACE, "hair/chains")
            val tiers = when (richness) {
                "minimal" -> setOf(io.github.psd2live.core.recipe.ModuleTier.CORE)
                "standard" -> setOf(io.github.psd2live.core.recipe.ModuleTier.CORE, io.github.psd2live.core.recipe.ModuleTier.MAJOR)
                else -> io.github.psd2live.core.recipe.ModuleTier.entries.toSet()
            }
            val modules = RigLegacyBindings.specs.mapNotNull { it.module }.filter { module ->
                module.id != "face.depth-turn" && module.tier in tiers &&
                    (richness == "full" || module.setting == null) &&
                    (module.base == null || module.base in bases) &&
                    (algorithm == "modern" || RigLegacyBindings.spec(module.id).related.none { '/' in it && it !in bases })
            }.map { it.id }
            Group("$algorithm-$richness", algorithm, richness, bases + modules)
        }
    }
    // Keep the old plan identifier readable; new interfaces expose the explicit algorithm/richness choices.
    val groups = groupCatalog.associate { it.id to it.types } + ("fitted" to groupCatalog.single { it.id == RECOMMENDED_GROUP }.types)


    fun prepare(capture: WorkspaceCapture<RigPreviewModel>, request: JsonObject): JsonObject {
        val stack = RigModifierStack.fromJson(requireNotNull(capture.document.rigEdits.modifierStack) { "Convert legacy presets before adding modifiers" })
        val (_, plan) = plan(capture.model, stack, request)
        return JsonObject(plan + buildJsonObject {
            put("state", capture.state); put("project_id", capture.projectId)
            put("history_node_id", capture.historyHead); put("revision", capture.revision)
        })
    }

    internal fun plan(model: RigPreviewModel, stack: RigModifierStack, request: JsonObject,
                      requestedTypes: List<String>? = null): Pair<RigModifierStack, JsonObject> {
        val requestedTargets = request.getValue("targets").jsonArray.map { raw -> raw.jsonObject.let {
            ModifierTarget(it.getValue("kind").jsonPrimitive.content, it.getValue("id").jsonPrimitive.content)
        } }
        require(requestedTargets.isNotEmpty() && requestedTargets.distinct().size == requestedTargets.size) { "Insertion needs distinct captured targets" }
        val type = request["type"]?.jsonPrimitive?.content
        val registry = RigModifierAlgorithms.registry
        val stored = type?.let(registry::storedType)
        val depthTurn = stored == "face.depth-turn"
        val covered = if (stored in sourceTypes || depthTurn) (stack.instances.filter { it.type == stored }.flatMap { it.targets } +
            stack.appliedCapabilities.filter { it.type == stored }.flatMap { it.targets }).toSet() else emptySet()
        val targets = requestedTargets.filterNot(covered::contains)
        require(targets.isNotEmpty()) { "This modifier already exists on every captured target; edit or enable the existing instance" }
        val puppet = model.rig.puppet
        require(targets.all { target -> when (target.kind) {
            "mesh" -> puppet.drawables.any { it.id.raw == target.id && it.mesh != null }
            "deformer" -> puppet.deformers.any { it.id.raw == target.id && (it is org.umamo.runtime.model.Deformer.Warp || ModifierPluginCatalog.algorithm(type.orEmpty()) != null) }
            "part" -> ModifierPluginCatalog.algorithm(type.orEmpty()) != null && puppet.parts.any { it.id.raw == target.id }
            "layer" -> ModifierPluginCatalog.algorithm(type.orEmpty()) != null && model.analysis.layers.any { it.source.id.raw == target.id }
            else -> false
        } }) { "An insertion target no longer exists" }
        val group = request["group"]?.jsonPrimitive?.content
        if (type == "simulation" && request["preset"] != null)
            return simulationPreset(model, stack, targets, request.getValue("preset").jsonPrimitive.content)
        require((type == null) != (group == null)) { "Choose a modifier or a preset group" }
        val requested = requestedTypes ?: if (type != null) listOf(type) else requireNotNull(groups[group]) { "Unknown source preset group: $group" }
        val availableTypes = types.toHashSet()
        require(requested.all(availableTypes::contains)) { "Unknown modifier" }
        require(request["preset"] == null || type in setOf("motion", "physics") && request["config"] == null) { "A preset and an explicit configuration are alternative inputs" }
        require(type in nativeTypes || !depthTurn && ModifierPluginCatalog.algorithm(type.orEmpty()) != null || request["config"] == null) {
            "Source binding configuration is resolved from its captured dependencies"
        }
        require(type == "swing" || ModifierPluginCatalog.algorithm(type.orEmpty())?.second?.targets?.let { kinds -> targets.all { it.kind in kinds } } == true || targets.all { it.kind == "mesh" }) { "This modifier needs captured mesh targets" }
        val available = activeCapabilities(model, stack).toMutableList()
        var candidate = stack
        val entries = ArrayList<JsonObject>()
        val planned = LinkedHashMap<String, JsonObject>()
        fun describe(capability: ModifierCapability, disposition: String) = buildJsonObject {
            put("id", requireNotNull(capability.id)); put("type", capability.type); put("version", capability.version); put("disposition", disposition)
            put("targets", JsonArray(capability.targets.map { buildJsonObject { put("kind", it.kind); put("id", it.id) } }))
        }
        fun ensure(requestedType: String, reuse: Boolean = true, scaffold: String? = null, equivalent: Boolean = true): ModifierCapability {
            val wantsModernFace = !depthTurn && (group?.startsWith("modern-") == true || group == "fitted" ||
                group == null && type != "face/legacy" && available.none { cap ->
                    cap.type == "face/legacy" && targets.all { it in cap.targets }
                })
            val wanted = if (wantsModernFace && requestedType == "face/legacy")
                RigLegacyBindings.FITTED_FACE else requestedType
            if (reuse) available.firstOrNull { cap -> (cap.type == wanted || equivalent && registry.provides(cap, wanted)) && cap.id != null && targets.all { it in cap.targets } &&
                (scaffold == null || cap.config["scaffold"]?.jsonPrimitive?.content == scaffold) }?.let { cap ->
                planned.putIfAbsent(cap.id!!, describe(cap, if (stack.instances.any { it.id == cap.id }) "reused" else if (cap.id in stack.retiredIds) "applied" else "added"))
                return cap
            }
            val storedType = registry.storedType(wanted)
            val definition = requireNotNull(registry.defaultDefinition(wanted)) { "Unknown modifier algorithm: $wanted" }
            val version = definition.version
            val packaged = ModifierPluginCatalog.algorithm(wanted, version)
            val spec = RigLegacyBindings.find(storedType)
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
            else if (storedType == wanted && packaged != null) {
                val algorithm = packaged.second
                algorithm.requires.forEach { ensure(it) }
                request["config"]?.jsonObject ?: algorithm.defaults
            } else if (wanted in RigSourceModifiers.PART_TYPES) {
                val structure = scaffold ?: requireNotNull(ensure(RigSourceModifiers.SCAFFOLD).id)
                for (required in definition.requires - RigSourceModifiers.SCAFFOLD) {
                    ensure(required, scaffold = structure, equivalent = true)
                }
                buildJsonObject { put("scaffold", structure) }
            } else if (spec == null) JsonObject(emptyMap()) else {
                val structure = scaffold ?: requireNotNull(ensure(RigSourceModifiers.SCAFFOLD).id)
                // The selected version may require additional preceding capabilities without changing the legacy config shape.
                for (required in definition.requires - spec.requires) ensure(required, scaffold = structure, equivalent = !depthTurn)
                val base = spec.module?.base?.let { ensure(it, scaffold = structure) }
                val related = spec.related.associateWith { ensure(it, scaffold = structure, equivalent = !depthTurn) }
                buildJsonObject {
                    put("scaffold", structure)
                    base?.id?.let { put("base", it) }
                    if (related.isNotEmpty()) put("dependencies", JsonObject(related.mapValues { JsonPrimitive(requireNotNull(it.value.id)) }))
                }
            }
            candidate = candidate.add(storedType, version, targets, configuration, group, plugin = packaged?.first?.reference)
            val instance = candidate.instances.last()
            val cap = instance.capability()
            available += cap
            entries += buildJsonObject {
                instance.plugin?.let { put("plugin", Json.encodeToJsonElement(ModifierPluginReference.serializer(), it)) }
                put("id", instance.id); put("type", storedType); put("version", instance.version); put("config", configuration)
                put("targets", JsonArray(targets.map { buildJsonObject { put("kind", it.kind); put("id", it.id) } }))
            }
            planned[instance.id] = describe(cap, "added")
            return cap
        }
        val subject = io.github.psd2live.core.recipe.RecipeSubject.of(model.analysis)
        requested.filter { group == null || RigLegacyBindings.find(it)?.module?.applies?.invoke(subject) != false }
            .forEach { ensure(it, reuse = it in sourceTypes, equivalent = false) }
        RigModifierAlgorithms.evaluator().validateOrder(candidate)
        return candidate to buildJsonObject {
            put("entries", JsonArray(entries)); put("plan", JsonArray(planned.values.toList()))
            put("group", group ?: "custom")
        }
    }

    private fun activeCapabilities(model: RigPreviewModel, stack: RigModifierStack): List<ModifierCapability> {
        val active = model.rig.modifierReport?.getValue("entries")?.jsonArray.orEmpty().filter {
            it.jsonObject.getValue("status").jsonPrimitive.content == "active"
        }.mapTo(HashSet()) { it.jsonObject.getValue("id").jsonPrimitive.content }
        return stack.appliedCapabilities + stack.instances.filter { it.id in active }.map { it.capability() }
    }

    fun physicsPresets(): List<RigPhysicsEdit> = PhysicsGenerator.presetRules(PhysicsGenerator.Presets.All)

    internal fun simulationPreset(model: RigPreviewModel, stack: RigModifierStack, targets: List<ModifierTarget>, preset: String,
                                  allowNoLooseClothing: Boolean = false): Pair<RigModifierStack, JsonObject> {
        require(targets.all { it.kind == "mesh" }) { "Simulation presets need mesh targets" }
        val layers = targets.map { requireNotNull(model.rig.layerIdByDrawableId[it.id]) { "Target has no source layer: ${it.id}" } }.toSet()
        val existing = stack.instances.filter { it.type == "simulation" }.map { RigSimEdit.fromJson(it.config).copy(id = it.id) }
        val applied = ModelPresets.apply(RigEditOverlay(simEdits = existing), model.rig.puppet,
            model.analysis, model.rig.layerIdByDrawableId, ModelPresets.Preset.parse(preset), layers, model.config.alphaThreshold, allowNoLooseClothing)
        var next = stack
        val entries = applied.overlay.simEdits.filter { it.id in applied.simulationIds }.map { simulation ->
            require(simulation.inputs.isNotEmpty()) { "Add body or head movement before adding a simulation" }
            val weights = applied.overlay.authoringJournal.filter { command ->
                command["target"]?.jsonPrimitive?.content?.removePrefix("mesh:") in simulation.targets
            }
            val config = JsonObject(simulation.toJson() + mapOf("automatic_weights" to JsonArray(weights)))
            next = next.add("simulation", 1, simulation.targets.map { ModifierTarget("mesh", it) }, config)
            val instance = next.instances.last()
            buildJsonObject {
                put("id", instance.id); put("type", instance.type); put("version", instance.version); put("config", config)
                put("targets", JsonArray(instance.targets.map { buildJsonObject { put("kind", it.kind); put("id", it.id) } }))
            }
        }
        return next to buildJsonObject {
            put("group", "custom"); put("entries", JsonArray(entries))
            put("plan", JsonArray(entries.map { JsonObject((it - "config") + ("disposition" to JsonPrimitive("added"))) }))
        }
    }

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
