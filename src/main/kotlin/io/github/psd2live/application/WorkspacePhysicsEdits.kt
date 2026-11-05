package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.project.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*
import kotlin.coroutines.CoroutineContext

/** Hosts observe solver work; candidates and fitted settings are prepared independently of the UI. */
internal interface WorkspacePhysicsWork {
    fun <T> run(id: String, action: (progress: (Float) -> Unit, cancelled: () -> Boolean) -> T): T

    object Direct : WorkspacePhysicsWork {
        override fun <T> run(id: String, action: ((Float) -> Unit, () -> Boolean) -> T): T = action({}, { false })
    }
}

internal fun WorkspacePhysicsWork.cancellable(context: CoroutineContext,
    progress: (String, Float) -> Unit = { id, fraction -> context[WorkspaceJobContext]?.progress(0.1f + 0.65f * fraction, "Fitting physics $id") },
): WorkspacePhysicsWork {
    val observer = this
    return object : WorkspacePhysicsWork {
        override fun <T> run(id: String, action: ((Float) -> Unit, () -> Boolean) -> T): T = observer.run(id) { report, cancelled ->
            fun check() { context.ensureActive(); if (cancelled()) throw CancellationException("Physics fitting cancelled") }
            check()
            val result = action({ value -> check(); progress(id, value); report(value) }, { check(); false })
            check()
            result
        }
    }
}

internal data class WorkspacePhysicsCandidate(val document: WorkspaceDocument, val report: JsonObject = JsonObject(emptyMap()))

internal object WorkspacePhysicsEdits {
    val supported = setOf("physics_put", "physics_delete", "physics_config", "physics_import", "physics_fit")
    const val PRESET = "physics_apply_preset"
    val batchable = supported - "physics_import" + PRESET

    fun apply(operation: WorkspaceDocumentOperation, document: WorkspaceDocument, model: RigPreviewModel,
              work: WorkspacePhysicsWork = WorkspacePhysicsWork.Direct): WorkspaceDocument = when (operation.operation) {
        "physics_put" -> WorkspaceDocumentEdits.physics(document, model, operation.request)
        "physics_delete" -> WorkspaceDocumentEdits.removePhysics(document, model, operation.request.text("id"))
        "physics_config" -> WorkspaceDocumentEdits.configurePhysics(document, model,
            operation.request["order"]?.jsonArray?.map { it.jsonPrimitive.content }, operation.request["fps"]?.jsonPrimitive?.int)
        "physics_fit" -> {
            val target = (operation.request["target"]?.jsonPrimitive?.float ?: 100f) / 100f
            val observed = operation.request["observed_peaks"]?.let(::observedPeaks)
            if (observed == null) fit(document, model, operation.request.text("id"), target, work)
            else WorkspaceDocumentEdits.physics(document, model, WorkspacePhysicsIntents.operation(document, model,
                WorkspacePhysicsIntent.FitObserved(operation.request.text("id"), observed, target)).request)
        }
        PRESET -> {
            val intent = WorkspacePhysicsIntent.Preset(operation.request.text("id"), PhysicsPresets.Preset.fromJson(operation.request.getValue("preset").jsonObject))
            val prepared = WorkspacePhysicsIntents.operation(document, model, intent)
            WorkspaceDocumentEdits.physics(document, model, prepared.request)
        }
        else -> error("Not a pure physics document operation: ${operation.operation}")
    }

    fun fit(document: WorkspaceDocument, model: RigPreviewModel, id: String, target: Float,
            work: WorkspacePhysicsWork = WorkspacePhysicsWork.Direct): WorkspaceDocument {
        require(target.isFinite() && target in 0.1f..3f) { "Target fraction must be in 0.1..3" }
        val group = WorkspaceDocumentEdits.physicsCatalog(document, model).firstOrNull { it.id == id }
            ?: throw IllegalArgumentException("Physics group not found: $id")
        require(group.origin != PhysicsOrigin.MODIFIER) { "$id belongs to a modifier; update its physics configuration on the owner" }
        val ranges = PhysicsEngine.ranges(model.rig.puppet.parameters)
        val trace = work.run(id) { progress, cancelled ->
            PhysicsResponse.trace(group.setting, ranges, document.rigEdits.physicsFps, progress = progress, cancelled = cancelled)
        }
        val fitted = PhysicsAuthoring.fitScales(group.setting, trace.reach, target)
        require(trace.reach.values.any { it > 0.01f }) { "No output of $id swings under a head sway; check its inputs" }
        return document.copy(rigEdits = PhysicsAuthoring.put(document.rigEdits, fitted, group.generated))
    }

    fun import(document: WorkspaceDocument, model: RigPreviewModel, text: String): WorkspacePhysicsCandidate {
        val imported = PhysicsAuthoring.import(document.rigEdits, WorkspaceDocumentEdits.physicsCatalog(document, model), text,
            model.rig.puppet.parameters.mapTo(HashSet()) { it.id.raw })
        if (document.rigEdits.modifierStack != null) {
            val generated = model.rig.puppet.parameters.mapTo(HashSet()) { it.id.raw } - model.authored.rig.puppet.parameters.mapTo(HashSet()) { it.id.raw }
            val owned = WorkspaceDocumentEdits.physicsCatalog(document, model).filter { it.origin == PhysicsOrigin.MODIFIER }.mapTo(HashSet()) { it.id }
            require(imported.overlay.physicsEdits.filter { it.id in imported.ids }.none { it.id in owned || it.parameters.any { parameter -> parameter in generated } }) {
                "Generated physics and parameter references must be edited through modifier operations"
            }
        }
        return WorkspacePhysicsCandidate(document.copy(rigEdits = imported.overlay,
            settings = if (document.rigEdits.modifierStack != null) document.settings else JsonObject(document.settings + ("generatePhysics" to JsonPrimitive(true)))), buildJsonObject {
            put("imported", JsonArray(imported.ids.map(::JsonPrimitive)))
            if (imported.disabled.isNotEmpty()) put("disabled", JsonArray(imported.disabled.map(::JsonPrimitive)))
            if (imported.missing.isNotEmpty()) put("missing_parameters", JsonObject(imported.missing.mapValues { (_, ids) -> JsonArray(ids.map(::JsonPrimitive)) }))
            imported.fps?.let { put("fps", it) }
        })
    }

    /** Output index to observed reach, as `physics_fit.observed_peaks` and an audition frame's `peaks` carry it. */
    fun observedPeaks(value: JsonElement): Map<Int, Float> {
        val entries = (value as? JsonObject) ?: throw IllegalArgumentException("observed_peaks must map output indexes to reaches")
        require(entries.isNotEmpty()) { "observed_peaks must name at least one output" }
        return entries.entries.associate { (key, reach) ->
            val index = requireNotNull(key.toIntOrNull()?.takeIf { it >= 0 && key == it.toString() }) { "observed_peaks key $key is not an output index" }
            val number = (reach as? JsonPrimitive)?.takeIf { !it.isString }?.floatOrNull
            index to requireNotNull(number) { "observed_peaks.$key must be a number" }
        }
    }

    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content
}
