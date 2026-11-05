package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.core.modifier.*
import io.github.psd2live.core.recipe.RecipeSubject
import io.github.psd2live.core.recipe.RigModules
import io.github.psd2live.format.model.RigIR
import io.github.psd2live.project.WorkspaceDocument
import io.github.psd2live.project.config
import kotlinx.serialization.json.*

/** Derive and verify a candidate entirely inside the document transaction. */
internal object WorkspaceModifierConversion {
    fun convert(document: WorkspaceDocument, model: RigPreviewModel, work: WorkspaceRasterWork): WorkspaceDocument {
        require(document.rigEdits.modifierStack == null) { "This project already uses a modifier stack" }
        work.checkpoint()
        val config = model.config
        val ordinaryOnly = config.meshOnly || config.rigEdits.importedCmo3 != null
        val stack = dynamics(model, if (ordinaryOnly) RigModifierStack() else bindings(model))
        val journal = document.rigEdits.authoringJournal
        val candidateJournal = if (ordinaryOnly && config.rigEdits.importedCmo3 == null) {
            val binding = model.sources.bindingKey ?: PSD2LivePipeline().bindingKey(document.source, config)
            journal + RigCheckpoint.encode(model.authored, binding)
        } else journal
        val prepared = document.copy(rigEdits = document.rigEdits.copy(
            modifierStack = stack.toJson(), authoringJournal = candidateJournal, swingEdits = emptyList(), simEdits = emptyList(),
            physicsOrder = (PhysicsCatalog.active(model).map { it.id } + document.rigEdits.physicsOrder).distinct()))
        val verifiedBakes = WorkspaceModifierSimulation.prepare(prepared, stack, work, allowRebake = false)
        val candidate = prepared.copy(rigEdits = prepared.rigEdits.copy(modifierStack = verifiedBakes.toJson()))
        work.progress(.5f, "Verifying converted model presets")
        val converted = PSD2LivePipeline().buildPreview(candidate.source, candidate.config(),
            ProgressListener { _, _ -> work.checkpoint() })
        val previous = meaningful(RigIrCompiler.compile(model, tileArt = true, simulations = true))
        val next = meaningful(RigIrCompiler.compile(converted, tileArt = true, simulations = true))
        if (previous != next) throw mismatch(previous, next)
        work.checkpoint()
        val captured = verifiedBakes.copy(instances = verifiedBakes.instances.map {
            WorkspaceModifierEdits.captureParameterProviders(it, converted, stopAtConsumer = true)
        })
        RigModifierAlgorithms.evaluator().validateOrder(captured)
        return candidate.copy(rigEdits = candidate.rigEdits.copy(modifierStack = captured.toJson()))
    }

    private fun mismatch(previous: RigIR, next: RigIR): WorkspaceModifierConversionMismatch {
        val fields = RigIR::class.java.declaredFields.filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .filter { field -> field.isAccessible = true; field.get(previous) != field.get(next) }.map { it.name }
        val objects = buildJsonObject {
            fun <T> compare(kind: String, old: List<T>, new: List<T>, id: (T) -> String) {
                if (old == new) return
                val before = old.associateBy(id)
                val after = new.associateBy(id)
                putJsonObject(kind) {
                    fun list(key: String, ids: Collection<String>) {
                        putJsonObject(key) {
                            put("count", ids.size)
                            put("ids", JsonArray(ids.take(8).map(::JsonPrimitive)))
                        }
                    }
                    list("added", after.keys - before.keys)
                    list("removed", before.keys - after.keys)
                    list("changed", before.keys.intersect(after.keys).filter { before[it] != after[it] })
                    put("order_changed", before.keys.filter { it in after } != after.keys.filter { it in before })
                }
            }
            compare("mesh", previous.meshes, next.meshes) { it.id }
            compare("deformer", previous.deformers, next.deformers) { it.id }
            compare("part", previous.parts, next.parts) { it.id }
            compare("parameter", previous.parameters, next.parameters) { it.id }
            compare("physics", previous.physics.groups, next.physics.groups) { it.id }
            compare("motion", previous.clips, next.clips) { it.id }
            compare("simulation", previous.advanced.simulations, next.advanced.simulations) { it.id }
        }
        return WorkspaceModifierConversionMismatch(fields, objects)
    }

    /** The parameter tree retains panel order. Compare axis definitions by ID and ignore references to absent axes. */
    private fun meaningful(rig: RigIR): RigIR {
        val parameters = rig.parameters.mapTo(HashSet()) { it.id }
        return rig.copy(parameters = rig.parameters.sortedBy { it.id }, restPose = rig.restPose.filterKeys { it in parameters },
            parameterRoles = rig.parameterRoles.filter { role -> role.parameters.all { it in parameters } })
    }

    internal fun dynamics(model: RigPreviewModel, stack: RigModifierStack): RigModifierStack {
        val overlay = model.config.rigEdits
        if (overlay.swingEdits.isEmpty() && overlay.simEdits.isEmpty()) return stack
        val swings = overlay.swingEdits.filterNot { it.baked }.associateBy(DocumentGenerators::swingId)
        val simulations = overlay.simEdits.associateBy(DocumentGenerators::simulationId)
        val authored = model.authored.rig.puppet
        val warpIds = authored.deformers.filterIsInstance<org.umamo.runtime.model.Deformer.Warp>().mapTo(HashSet()) { it.id.raw }
        var next = stack
        for (node in DocumentGenerators.graph(overlay, primitives = model.primitiveSkins).order) {
            val swing = swings[node.id]
            val simulation = simulations[node.id]
            when {
                swing != null -> next = next.add("swing", 1, swing.targets.map {
                    ModifierTarget(if (it in warpIds) "deformer" else "mesh", it)
                }, swing.toJson(), presetGroup = "converted", outputAliases = buildMap {
                    put("swing", swing.id)
                    swing.motions.forEach { motion -> motion.parameterIds.forEachIndexed { index, parameter ->
                        put("swing:${motion.kind.name.lowercase()}:$index", parameter)
                    } }
                })
                simulation != null -> {
                    next = next.add("simulation", 1, simulation.targets.map { ModifierTarget("mesh", it) },
                        simulation.copy(enabled = true).toJson(), presetGroup = "converted", outputAliases = buildMap {
                            put("simulation", simulation.id)
                            simulation.bake?.parameters.orEmpty().forEach { put("simulation:$it", simulation.outputId(it)) }
                            io.github.psd2live.core.sim.SimGenerator.writtenPendulums(simulation).forEachIndexed { index, group ->
                                put("physics:$index", group.id)
                            }
                        })
                    if (!simulation.enabled) next = next.update(next.instances.last().id) { it.copy(enabled = false) }
                }
            }
        }
        return next
    }

    internal fun bindings(model: RigPreviewModel): RigModifierStack {
        val config = model.config
        val targets = model.baseRig.puppet.drawables.filter { it.mesh != null && it.id.raw in model.baseRig.ownLayerIdByDrawableId }
            .filterNot { mesh -> model.baseRig.layerIdByDrawableId[mesh.id.raw]?.let(MouthLipLayer::isLipId) == true }
            .map { ModifierTarget("mesh", it.id.raw) }
        if (targets.isEmpty()) return RigModifierStack()
        val providers = LinkedHashMap<String, String>()
        fun parameterFolders(nodes: List<org.umamo.runtime.model.ParameterNode>): Map<String, String> = nodes
            .filterIsInstance<org.umamo.runtime.model.ParameterNode.Group>().flatMap { group ->
                listOf("parameter-folder:${group.id.raw}" to group.id.raw) + parameterFolders(group.children).toList()
            }.toMap()
        val chains = model.baseRig.generatedChains.map(RigModifierChains::Binding)
        val chainWarps = chains.mapTo(HashSet()) { it.native.warpId }
        val nativePhysics = PhysicsCatalog.active(model)
        val hierarchyAliases = model.baseRig.puppet.deformers.filter { it.id.raw !in chainWarps }
            .associate { "shared/deformer:${it.id.raw}" to it.id.raw } +
            chains.associate { "shared/deformer:${it.warp.raw}" to it.native.warpId } +
            model.baseRig.puppet.parts.associate { "shared/part:${it.id.raw}" to it.id.raw } +
            parameterFolders(model.baseRig.puppet.parameterTree)
        var stack = RigModifierStack()
        fun add(type: String, version: Int, config: JsonObject, outputAliases: Map<String, String> = emptyMap()) {
            stack = stack.add(type, version, targets, config, presetGroup = "converted", outputAliases = outputAliases)
            providers[type] = stack.instances.last().id
        }
        add(RigSourceModifiers.SCAFFOLD, 1, buildJsonObject {
            if (config.featureDisplacementEnabled) put("featureDisplacement", true)
            if (config.hairSimulationFront) put("frontHairSway", false)
            if (config.hairSimulationBack) put("backHairSway", false)
            val corrections = io.github.psd2live.project.WorkspaceSettingsCodec.encodeRecipe(config.recipe)
                .filterKeys { it in setOf("moves", "eyeParts") }
            if (corrections.isNotEmpty()) put("corrections", JsonObject(corrections))
        }, hierarchyAliases)
        fun capture(spec: RigLegacyBindings.Spec, version: Int, values: Map<String, Float>) {
            val missing = spec.requires - providers.keys
            require(missing.isEmpty()) { "Conversion cannot resolve ${spec.type} prerequisites: $missing" }
            add(spec.type, version, buildJsonObject {
                put("scaffold", providers.getValue(RigSourceModifiers.SCAFFOLD))
                spec.module?.base?.let { put("base", providers.getValue(it)) }
                if (spec.related.isNotEmpty()) putJsonObject("dependencies") {
                    spec.related.sorted().forEach { put(it, providers.getValue(it)) }
                }
                if (spec.hasPhysics && !config.generatePhysics) put("physicsEnabled", false)
                if (spec.strength) put("strength", if (spec.area == "body") config.bodyStrength else config.headTurnStrength)
                if (spec.mouthOutline) config.mouthColor?.let { put("color", it and 0xffffff) }
                if (spec.fields.isNotEmpty()) putJsonObject("tuning") {
                    spec.fields.forEach { put(it.id, it.get(config.rigTuning)) }
                }
                if (values.isNotEmpty()) putJsonObject("values") { values.forEach { (key, value) -> put(key, value) } }
            }, hierarchyAliases + buildMap {
                nativePhysics.filter { it.id in spec.module?.physicsGroups.orEmpty() }.forEach { put("physics:${it.id}", it.id) }
                for (chain in chains.filter { it.native.kind in RigModifierChains.kinds(spec.type) }) {
                    chain.parameters.forEach { (native, normalized) -> put("chain-parameter:${normalized.raw.removePrefix("SourceChain:")}", native) }
                    nativePhysics.filter { group -> group.outputs.isNotEmpty() && group.outputs.all { it.parameter in chain.parameters } }.forEach { group ->
                        put("physics:chain:${chain.key}:${chain.suffix(group.outputs.first().parameter)}", group.id)
                    }
                }
            })
        }
        if (config.generateDeformers) for (area in listOf("body", "face", "eye", "brow", "mouth", "hair")) {
            val base = config.recipe.base(area)
            val version = config.recipe.bases[area]?.takeIf { it.base == base.id }?.version ?: base.version
            capture(RigLegacyBindings.spec(base.id), version, base.knobs.associate { it.id to config.recipe.baseValue(area, it.id) })
        }
        val subject = RecipeSubject.of(model.analysis)
        val modules = RigModules.modules.filter { RigModules.builds(it, config, subject) }.mapNotNull { RigLegacyBindings.find(it.id) }.toMutableList()
        while (modules.isNotEmpty()) {
            val spec = requireNotNull(modules.firstOrNull { providers.keys.containsAll(it.requires) }) {
                "Conversion cannot resolve legacy module prerequisites: ${modules.map { it.type }}"
            }
            val module = requireNotNull(spec.module)
            val values = when {
                spec.seeThrough -> mapOf("opacity" to config.seeThroughOpacity * 100f)
                spec.mouthOutline -> mapOf("thickness" to config.mouthThickness)
                else -> module.knobs.associate { it.id to config.recipe.value(module.id, it.id) }
            }
            capture(spec, config.recipe.modules[module.id]?.version ?: module.version, values)
            modules.remove(spec)
        }
        val authoredPhysics = config.rigEdits.physicsEdits.mapTo(HashSet()) { it.id }
        for (group in PhysicsCatalog.active(model)) {
            if (group.id !in PhysicsGenerator.presetIds || group.id in authoredPhysics) continue
            add("physics", 1, group.toJson(), mapOf("physics" to group.id))
        }
        val output = RigIrCompiler.compile(model)
        val authored = MotionClips.reconcileParameters(config.rigEdits.motionClips, model.rig.puppet.parameters)
        val authoredIds = authored.filter { it.builtin == null }.mapTo(HashSet()) { it.id }
        val driven = if (config.exportIncludePhysics) output.physics.groups.flatMapTo(HashSet()) { group ->
            group.outputs.map { it.parameter }
        } else emptySet()
        for (clip in output.clips.filterNot { it.id in authoredIds }) {
            val edited = authored.singleOrNull { it.id == clip.id && it.builtin != null }
            val tracks = edited?.curves ?: RigIrCompiler.legacyMotionTracks(config, clip.id, if (clip.id == "Idle") driven else emptySet())
            val parameters = clip.curves.mapTo(HashSet()) { it.parameter }
            val activeTracks = tracks.filter { it.parameterId in parameters }
            require(activeTracks.map(MotionGenerator::curve) == clip.curves) { "Conversion cannot preserve the curves of ${clip.name}" }
            val captured = MotionClip(clip.id, clip.name, loop = clip.loop, duration = clip.duration, fps = clip.fps,
                fadeIn = clip.fadeIn ?: 1f, fadeOut = clip.fadeOut ?: 1f, curves = activeTracks,
                events = clip.events.map { MotionEvent(it.time, it.value) }, fileStem = clip.file,
                capturedRanges = model.rig.puppet.parameters.filter { it.id.raw in parameters }.associate {
                    it.id.raw to MotionParameterRange(it.min, it.max, it.repeat)
                },
                exportGroup = clip.group, runtimeDefaultFades = clip.fadeIn == null && clip.fadeOut == null,
                parameterRoles = if (clip.id == "Blink") output.parameterRoles.filter { it.role == "EyeBlink" }.associate { it.role to it.parameters } else emptyMap())
            add("motion", 1, MotionClips.toJson(captured), mapOf("motion" to clip.id))
        }
        return stack
    }
}


internal class WorkspaceModifierConversionMismatch(val fields: List<String>, val objects: JsonObject) :
    IllegalArgumentException("Converting presets would change the model (${fields.joinToString()})")
