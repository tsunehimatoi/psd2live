package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.core.modifier.*
import io.github.psd2live.project.*
import io.github.psd2live.targets.cubism.PuppetIr
import kotlinx.serialization.json.*

/** Pure candidates shared by GUI commands, MCP jobs and atomic document batches. */
internal object WorkspaceModifierEdits {
    val supported = setOf("modifier_add", "modifier_add_group", "modifier_update", "modifier_enable",
        "modifier_remove", "modifier_move", "modifier_organize", "modifier_apply", "modifier_convert", "modifier_switch", "modifier_initialize")

    fun apply(operation: WorkspaceDocumentOperation, document: WorkspaceDocument, model: RigPreviewModel,
              work: WorkspaceRasterWork = WorkspaceRasterWork.Direct, draftInputs: WorkspaceModifierDraftInputs? = null): WorkspaceDocument {
        if (operation.operation == "modifier_initialize") return WorkspacePresetInitialize.apply(document, operation.request, work)
        if (operation.operation == "modifier_convert") return WorkspaceModifierConversion.convert(document, model, work)
        if (operation.operation == "modifier_switch") return WorkspacePresetSwitch.apply(document,
            operation.request.getValue("group").jsonPrimitive.content, work, model)
        val stack = RigModifierStack.fromJson(requireNotNull(document.rigEdits.modifierStack) {
            "Convert legacy presets before editing a modifier stack"
        })
        val request = operation.request
        if (operation.operation == "modifier_organize") {
            val nodes = RigModifierOrganization.decode(request.getValue("nodes").jsonArray)
            RigModifierOrganization.validate(stack, nodes)
            return document.copy(rigEdits = document.rigEdits.copy(modifierStack = stack.copy(organization = nodes).toJson()))
        }
        val id = request["id"]?.jsonPrimitive?.content
        val next = when (operation.operation) {
            "modifier_add" -> add(stack, request, model)
            "modifier_add_group" -> {
                val entries = request.getValue("entries").jsonArray
                require(entries.size in 1..128) { "Add 1..128 modifiers in a group" }
                val group = request.getValue("group").jsonPrimitive.content
                require(group.isNotBlank()) { "A preset group needs a name" }
                entries.fold(stack) { candidate, entry ->
                    work.checkpoint()
                    add(candidate, JsonObject(entry.jsonObject + ("group" to JsonPrimitive(group))), null)
                }
            }
            "modifier_update" -> stack.update(requireNotNull(id)) { instance ->
                request["plugin"]?.let { require(Json.decodeFromJsonElement(ModifierPluginReference.serializer(), it) == instance.plugin) { "Modifier plugin identity does not match" } }
                request["type"]?.let { require(it.jsonPrimitive.content == instance.type) { "Modifier type does not match" } }
                val config = request.getValue("config").jsonObject
                captureParameterProviders(instance.copy(config = JsonObject(
                    instance.config.filterKeys { it == "automatic_weights" } + config)), model)
            }
            "modifier_enable" -> stack.update(requireNotNull(id)) { it.copy(enabled = request.getValue("enabled").jsonPrimitive.boolean) }
            "modifier_remove" -> stack.remove(requireNotNull(id))
            "modifier_move" -> stack.move(requireNotNull(id), request.getValue("index").jsonPrimitive.int)
            "modifier_apply" -> return applyPrefix(document, model, stack, requireNotNull(id), work)
            else -> error("Unsupported modifier edit: ${operation.operation}")
        }
        // Rebuilding every candidate also validates batches. Order checks are cheap enough to fail before rebuilding.
        RigModifierAlgorithms.evaluator().validateOrder(next)
        if (next == stack) return document
        val candidate = if (operation.operation == "modifier_move" && next != stack)
            movePhysicsOrder(document, model, next, requireNotNull(id)) else document
        val simulationPipeline = PSD2LivePipeline().apply { deferRuntimeBundles = true }
        var prepared = WorkspaceModifierSimulation.prepare(candidate, next, work,
            validatedPrefix = if (candidate == document) WorkspaceModifierSimulation.unchangedPrefix(stack, next) else 0,
            pipeline = simulationPipeline, evaluated = model)
        val preparedPrefix = simulationPipeline.modifierCache.snapshot
        if (preparedPrefix != null && preparedPrefix !== model.modifierEvaluation) draftInputs?.pipeline = simulationPipeline
        if (operation.operation == "modifier_add_group") {
            val added = prepared.instances.filter { entry -> stack.instances.none { it.id == entry.id } }
            val consumers = added.filter { it.type in setOf("physics", "motion", "simulation") }
            if (consumers.isNotEmpty()) {
                val addsProvider = added.any { it.type !in setOf("physics", "motion") }
                val evaluated = if (addsProvider) {
                    work.checkpoint()
                    val config = document.config()
                    PSD2LivePipeline().buildPreview(document.source, config.copy(rigEdits = config.rigEdits.copy(modifierStack = prepared.toJson())))
                } else model
                for (consumer in consumers) prepared = prepared.update(consumer.id) {
                    captureParameterProviders(it, evaluated, stopAtConsumer = addsProvider)
                }
                RigModifierAlgorithms.evaluator().validateOrder(prepared)
            }
        }
        return if (prepared == stack) document else candidate.copy(rigEdits = candidate.rigEdits.copy(modifierStack = prepared.toJson()))
    }

    /** Moving a producer supersedes its captured group order while keeping ordinary groups in their slots. */
    private fun movePhysicsOrder(document: WorkspaceDocument, model: RigPreviewModel, stack: RigModifierStack, moved: String): WorkspaceDocument {
        if (document.rigEdits.physicsOrder.isEmpty()) return document
        val reported = HashMap<String, String>()
        for (row in model.rig.modifierReport?.get("entries")?.jsonArray.orEmpty()) {
            val entry = row.jsonObject
            for (item in entry.getValue("contributions").jsonArray.map { it.jsonObject }) {
                if (item.getValue("kind").jsonPrimitive.content == "physics" && item.getValue("change").jsonPrimitive.content == "added")
                    reported[item.getValue("id").jsonPrimitive.content] = entry.getValue("id").jsonPrimitive.content
            }
        }
        val order = (document.rigEdits.physicsOrder + model.rig.modifierOutput?.physics?.groups.orEmpty().map { it.id }).distinct()
        val owners = order.mapNotNull { group ->
            val owner = stack.instances.firstOrNull { entry -> entry.localOutputName(group)?.let { it == "physics" || it.startsWith("physics:") } == true }?.id
                ?: reported[group]?.takeIf { id -> stack.instances.any { it.id == id } }
            owner?.let { group to it }
        }.toMap()
        if (moved !in owners.values) return document
        val positions = stack.instances.mapIndexed { index, instance -> instance.id to index }.toMap()
        val sorted = order.filter { it in owners }.sortedBy { positions.getValue(owners.getValue(it)) }.iterator()
        val reordered = order.map { if (it in owners) sorted.next() else it }
        return document.copy(rigEdits = document.rigEdits.copy(physicsOrder = reordered))
    }

    private fun add(stack: RigModifierStack, request: JsonObject, model: RigPreviewModel?): RigModifierStack {
        val targets = request.getValue("targets").jsonArray.map { value -> value.jsonObject.let {
            ModifierTarget(it.getValue("kind").jsonPrimitive.content, it.getValue("id").jsonPrimitive.content)
        } }
        val type = request.getValue("type").jsonPrimitive.content
        val registry = RigModifierAlgorithms.registry
        val stored = registry.storedType(type)
        val config = request.getValue("config").jsonObject
        val requestedVersion = request["version"]?.jsonPrimitive?.int
        val version = requestedVersion ?: registry.defaultDefinition(type)?.version
            ?: error("Unknown modifier algorithm: $type")
        val candidate = stack.add(stored, version, targets, config,
            request["group"]?.jsonPrimitive?.content, request["id"]?.jsonPrimitive?.content,
            plugin = request["plugin"]?.let { Json.decodeFromJsonElement(ModifierPluginReference.serializer(), it) } ?: ModifierPluginCatalog.algorithm(type, version)?.first?.reference)
        val instance = candidate.instances.last()
        registry.definition(instance)
        val captured = if (model == null) candidate else candidate.update(instance.id) { captureParameterProviders(it, model) }
        return request["index"]?.let { captured.move(instance.id, it.jsonPrimitive.int) } ?: captured
    }

    internal fun captureParameterProviders(instance: RigModifierInstance, model: RigPreviewModel,
                                          stopAtConsumer: Boolean = false): RigModifierInstance {
        val parameters = when (instance.type) {
            "physics" -> RigPhysicsEdit.fromJson(instance.config).parameters.toSet()
            "motion" -> MotionClips.fromJson(instance.config).curves.mapTo(HashSet()) { it.parameterId }
            "simulation" -> io.github.psd2live.core.sim.RigSimEdit.fromJson(instance.config).let {
                it.inputs.mapTo(HashSet()) { input -> input.parameter } + it.staticInputs.orEmpty()
            }
            else -> return instance
        }
        val providers = instance.parameterProviders.filterKeys { it in parameters }.toMutableMap()
        for (row in model.rig.modifierReport?.get("entries")?.jsonArray.orEmpty()) {
            val report = row.jsonObject
            val id = report.getValue("id").jsonPrimitive.content
            if (id == instance.id) {
                if (stopAtConsumer) break
                continue
            }
            for (raw in report.getValue("contributions").jsonArray) {
                val item = raw.jsonObject
                if (item.getValue("kind").jsonPrimitive.content != "parameter" ||
                    item.getValue("change").jsonPrimitive.content != "added") continue
                val parameter = item.getValue("id").jsonPrimitive.content
                if (parameter in parameters) providers.putIfAbsent(parameter, id)
            }
        }
        return instance.copy(parameterProviders = providers)
    }

    private fun applyPrefix(document: WorkspaceDocument, model: RigPreviewModel, stack: RigModifierStack,
                            id: String, work: WorkspaceRasterWork): WorkspaceDocument {
        work.progress(.1f, "Evaluating modifier prefix")
        val input = RigModifierPipeline.input(model)
        val applied = ModifierPluginWorkers.openSession().use { session ->
            RigModifierAlgorithms.evaluator(RigModifierSource.of(model), stack, workerSession = session)
                .applyThrough(input, stack, id, work::checkpoint)
        }
        val consumed = stack.instances.take(stack.index(id) + 1)
        val existingPhysics = document.rigEdits.physicsEdits
        val inputPhysics = input.physics.groups.associateBy { it.id }
        val changedPhysics = applied.authored.physics.groups.filter { inputPhysics[it.id] != it }
            .associate { it.id to Physics3Json.setting(it) }
        val addedPhysics = changedPhysics.values.filter { it.id !in inputPhysics }
        require(addedPhysics.none { new -> existingPhysics.any { it.id == new.id } }) {
            "An applied physics group collides with ordinary content"
        }
        val physics = existingPhysics.map { changedPhysics[it.id] ?: it } + addedPhysics
        val previousClips = document.rigEdits.motionClips.associateBy { it.id }
        val clips = applied.authored.clips.filter { it.id !in previousClips }.map { generated ->
            val owner = consumed.singleOrNull { it.type == "motion" && it.outputId("motion") == generated.id }
                ?: error("No materializer for generated motion ${generated.id}")
            val clip = MotionClips.fromJson(owner.config).copy(id = generated.id, builtin = null, enabled = true, fileStem = generated.file)
            require(clip.curves.map(MotionGenerator::curve) == generated.curves &&
                clip.events.map { io.github.psd2live.format.model.ClipEvent(it.time, it.value) } == generated.events) {
                "Applying motion ${generated.id} would change its curves or events"
            }
            clip.copy(capturedRanges = applied.authored.parameters.filter { parameter -> clip.curves.any { it.parameterId == parameter.id } }
                .associate { it.id to MotionParameterRange(it.min, it.max, it.repeat) })
        }
        val authored = model.authored.copy(rig = model.authored.rig.withModifierMeshes(
            PuppetIr.toPuppet(applied.authored), applied.authored.textures.tileArt).copy(
            modifierOutput = null, modifierReport = null, authoredRestPose = applied.authored.restPose,
            authoredParameterRoles = applied.authored.parameterRoles))
        val binding = model.sources.bindingKey ?: if (model.config.rigEdits.importedCmo3 != null) "cmo3-import"
            else PSD2LivePipeline().bindingKey(document.source, RigModifierPipeline.ordinaryConfig(model.config).copy(meshOnly = true))
        val checkpoint = RigCheckpoint.encode(authored, binding)
        val overlay = document.rigEdits.copy(authoringJournal = document.rigEdits.authoringJournal + checkpoint,
            modifierStack = applied.stack.toJson(), physicsEdits = physics,
            // The applied groups can already be interleaved with groups from the remaining stack.
            physicsOrder = (document.rigEdits.physicsOrder + applied.result.rig.physics.groups.map { it.id }).distinct(),
            motionClips = document.rigEdits.motionClips + clips)
        val candidate = document.copy(rigEdits = overlay)
        work.checkpoint()
        work.progress(.7f, "Verifying applied modifier content")
        val rebuilt = PSD2LivePipeline().buildPreview(candidate.source, candidate.config())
        val actual = RigIrCompiler.compile(rebuilt)
        val expected = RigIrCompiler.compile(model)
        require(actual == expected) {
            val fields = io.github.psd2live.format.model.RigIR::class.java.declaredFields
                .filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }.filter { field ->
                    field.isAccessible = true
                    field.get(actual) != field.get(expected)
                }.joinToString { it.name }
            "Applying modifiers would change the current result ($fields)"
        }
        return candidate
    }
}
