package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.core.modifier.*
import io.github.psd2live.core.sim.*
import io.github.psd2live.project.WorkspaceDocument
import io.github.psd2live.project.config
import kotlinx.serialization.json.*

/** Resolve each simulation against its actual upstream prefix, inside the isolated document candidate. */
internal object WorkspaceModifierSimulation {
    fun prepare(document: WorkspaceDocument, work: WorkspaceRasterWork): WorkspaceDocument {
        val raw = document.rigEdits.modifierStack ?: return document
        val stack = RigModifierStack.fromJson(raw)
        if (stack.instances.none { it.type == "simulation" && it.enabled }) return document
        val next = prepare(document, stack, work)
        return if (next == stack) document else document.copy(rigEdits = document.rigEdits.copy(modifierStack = next.toJson()))
    }

    fun prepare(document: WorkspaceDocument, stack: RigModifierStack, work: WorkspaceRasterWork,
                allowRebake: Boolean = true): RigModifierStack {
        var next = stack
        for (index in stack.instances.indices) {
            work.checkpoint()
            val instance = next.instances[index]
            if (!instance.enabled || instance.type != "simulation") continue
            val source = RigSimEdit.fromJson(instance.config)
            require(source.enabled && source.targets == instance.targets.map { it.id } && instance.targets.all { it.kind == "mesh" }) {
                "${instance.id}: simulation configuration must match its captured mesh targets"
            }
            val prefix = next.copy(instances = next.instances.take(index))
            val overlay = document.rigEdits.copy(modifierStack = prefix.toJson())
            work.progress(0f, "Evaluating simulation inputs: ${instance.id}")
            val model = PSD2LivePipeline().buildPreview(document.source, document.config().copy(rigEdits = overlay))
            work.checkpoint()
            val activeProviders = model.rig.modifierReport?.get("entries")?.jsonArray.orEmpty().map { it.jsonObject }
                .filter { it.getValue("status").jsonPrimitive.content == "active" }.mapTo(HashSet()) { it.getValue("id").jsonPrimitive.content }
            activeProviders += prefix.appliedCapabilities.mapNotNull { it.id }
            val parameters = model.rig.puppet.parameters.mapTo(HashSet()) { it.id.raw }
            if (instance.parameterProviders.any { (parameter, provider) -> provider !in activeProviders || parameter !in parameters }) continue
            val fingerprint = RigModifierInput.fingerprint(requireNotNull(model.rig.modifierOutput))
            val availableMotions = requireNotNull(model.rig.modifierOutput).clips.mapTo(HashSet()) { it.id }
            if (source.trainingClips.keys.any { it !in availableMotions }) continue
            if (source.bake != null && !SimBake.stale(model.rig.puppet, source) &&
                (instance.inputFingerprint == null || instance.inputFingerprint == fingerprint)) {
                if (instance.inputFingerprint == null) next = next.update(instance.id) { it.copy(inputFingerprint = fingerprint) }
                continue
            }
            require(allowRebake) { "${instance.id}: conversion cannot preserve the existing simulation bake on the rebuilt inputs" }
            require(source.autoBake) { "${instance.id}: simulation needs a valid bake; enable automatic baking before updating it" }
            SimAuthoring.validate(model.rig.puppet, source)
            require(source.inputs.isNotEmpty()) { "${instance.id}: simulation needs existing input parameters before baking" }
            val motions = prefix.instances.filter { it.enabled && it.type == "motion" }.map {
                MotionClips.fromJson(it.config).copy(id = it.outputId("motion"), builtin = null)
            }
            val trainingOverlay = overlay.copy(motionClips = overlay.motionClips + motions)
            val bake = SimBaker.bake(model.rig.puppet, source.copy(bake = null), SimBaker.Options(
                physicsFps = overlay.physicsFps,
                trainingMotions = SimAuthoring.trainingMotions(trainingOverlay, source, model.rig.puppet),
                progress = { work.progress(it, "Baking simulation: ${instance.id}") },
                cancelled = { work.checkpoint(); false },
            ))
            work.checkpoint()
            next = next.update(instance.id) { it.copy(config = source.copy(bake = bake).toJson(), inputFingerprint = fingerprint) }
        }
        return next
    }
}
