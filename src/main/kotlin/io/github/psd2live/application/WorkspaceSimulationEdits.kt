package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.core.sim.*
import io.github.psd2live.project.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*
import kotlin.coroutines.CoroutineContext

/** An adapter may observe progress and request cancellation; it does not prepare simulation edits. */
internal interface WorkspaceSimulationWork {
    fun <T> run(id: String, action: (progress: (Float) -> Unit, cancelled: () -> Boolean) -> T): T

    object Direct : WorkspaceSimulationWork {
        override fun <T> run(id: String, action: ((Float) -> Unit, () -> Boolean) -> T): T = action({}, { false })
    }
}

/** Synchronous solver loops observe the originating coroutine, even before the next suspension. */
internal fun WorkspaceSimulationWork.cancellable(context: CoroutineContext,
    progress: (String, Float) -> Unit = { id, value -> context[WorkspaceJobContext]?.progress(0.1f + 0.75f * value, "Baking simulation $id") },
): WorkspaceSimulationWork {
    val observer = this
    return object : WorkspaceSimulationWork {
        private var progressHigh = 0f
        override fun <T> run(id: String, action: ((Float) -> Unit, () -> Boolean) -> T): T = observer.run(id) { report, cancelled ->
            fun check() { context.ensureActive(); if (cancelled()) throw CancellationException("Simulation bake cancelled") }
            check()
            val result = action({ value ->
                check(); progressHigh = maxOf(progressHigh, value); progress(id, progressHigh); report(value)
            }, { check(); false })
            check()
            result
        }
    }
}

internal data class WorkspaceSimulationCandidate(val document: WorkspaceDocument, val report: JsonObject = JsonObject(emptyMap()))

/** Pure document preparation shared by single calls, GUI commands and ordered atomic batches. */
internal object WorkspaceSimulationEdits {
    private val pipeline = PSD2LivePipeline()
    val supported = setOf("simulation_put", "simulation_delete", "simulation_bake", "simulation_clear_bake", "model_apply_preset")

    /** [autoBake] overrides each simulation's own setting for the bakes a put or preset makes; false leaves them to the caller. */
    fun apply(operation: WorkspaceDocumentOperation, document: WorkspaceDocument, preview: RigPreviewModel,
              work: WorkspaceSimulationWork = WorkspaceSimulationWork.Direct, autoBake: Boolean? = null): WorkspaceSimulationCandidate {
        val request = operation.request
        return when (operation.operation) {
            "simulation_put" -> put(document, preview, request, autoBake, work)
            "simulation_delete" -> WorkspaceSimulationCandidate(remove(document, request.text("id")))
            "simulation_clear_bake" -> WorkspaceSimulationCandidate(withBakes(document, mapOf(request.text("id") to null)))
            "simulation_bake" -> {
                val id = request.text("id")
                val bake = work.run(id) { progress, cancelled -> SimAuthoring.bake(document.rigEdits, SimAuthoring.AuthoredRigs(preview::authoredPuppet), id, progress, cancelled) }
                WorkspaceSimulationCandidate(withBakes(document, mapOf(id to bake)), bake.summary())
            }
            "model_apply_preset" -> when (val name = request.text("preset")) {
                "classic_front_hair", "classic_back_hair" -> WorkspaceSimulationCandidate(classicHair(document,
                    name == "classic_front_hair", request["sway"]?.jsonPrimitive?.boolean ?: true))
                "remove_clothing" -> WorkspaceSimulationCandidate(removeClothing(document))
                else -> preset(document, preview, ModelPresets.Preset.parse(name),
                    request["layers"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet().orEmpty(), work, autoBake)
            }
            else -> throw IllegalArgumentException("Not a simulation edit: ${operation.operation}")
        }
    }

    fun put(document: WorkspaceDocument, preview: RigPreviewModel, arguments: JsonObject, autoBake: Boolean? = null,
            work: WorkspaceSimulationWork = WorkspaceSimulationWork.Direct): WorkspaceSimulationCandidate {
        val id = arguments.text("id")
        val put = SimAuthoring.put(document.rigEdits, preview.rig.puppet, arguments)
        val (overlay, failure) = work.run(id) { progress, cancelled ->
            SimAuthoring.rebaked(put, SimAuthoring.AuthoredRigs(preview::authoredPuppet), id, progress, cancelled, autoBake)
        }
        val bake = overlay.simEdits.single { it.id == id }.bake
        val report = buildJsonObject {
            if (failure != null) put("bake_error", failure)
            else if (bake !== put.simEdits.single { it.id == id }.bake && bake != null) put("bake", bake.summary())
        }
        return WorkspaceSimulationCandidate(document.copy(rigEdits = overlay, settings = physicsSettings(document.settings, bake != null)), report)
    }

    fun preset(document: WorkspaceDocument, preview: RigPreviewModel, preset: ModelPresets.Preset, layers: Set<String>,
               work: WorkspaceSimulationWork = WorkspaceSimulationWork.Direct, autoBake: Boolean? = null): WorkspaceSimulationCandidate {
        require(layers.all { id -> preview.analysis.layers.any { it.source.id.raw == id } }) { "Unknown layer in preset selection" }
        val flag = when (preset) { ModelPresets.Preset.FRONT_HAIR -> "hairSimulationFront"; ModelPresets.Preset.BACK_HAIR -> "hairSimulationBack"; else -> null }
        var next = document
        var model = preview
        if (flag != null && document.settings[flag]?.jsonPrimitive?.booleanOrNull != true) {
            next = document.copy(settings = JsonObject(document.settings + (flag to JsonPrimitive(true))))
            val config = next.config()
            val switched = preview.config.copy(hairSimulationFront = config.hairSimulationFront, hairSimulationBack = config.hairSimulationBack)
            // The legacy sway goes from the generated rig: the journal's rig merges onto the new generation and is
            // checkpointed, rather than replayed on it ([RigRegenerationCheckpoint]); the weights then address that rig.
            val checkpointed = RigRegenerationCheckpoint.checkpointed(pipeline, preview, switched, document.source)
            if (checkpointed != null) {
                next = next.copy(rigEdits = checkpointed.rigEdits)
                model = pipeline.buildPreview(document.source, checkpointed, previousAtlas = preview.atlas)
            } else model = pipeline.rebuildPreview(preview, switched)
        }
        val authored = SimAuthoring.AuthoredRigs(model::authoredPuppet)
        // Parts of a materialized split exist only after the journal: address meshes by the replayed layer map.
        val applied = ModelPresets.apply(next.rigEdits, model.rig.puppet, model.analysis, model.rig.layerIdByDrawableId, preset, layers,
            next.config().alphaThreshold)
        var overlay = applied.overlay
        val failures = LinkedHashMap<String, String>()
        for (id in applied.simulationIds) {
            val (rebaked, failure) = work.run(id) { progress, cancelled -> SimAuthoring.rebaked(overlay, authored, id, progress, cancelled, autoBake) }
            if (failure != null) failures[id] = failure
            overlay = rebaked
        }
        val report = buildJsonObject {
            applied.toJson().forEach { (key, value) -> put(key, value) }
            putJsonObject("bakes") {
                for (id in applied.simulationIds) {
                    failures[id]?.let { put(id, buildJsonObject { put("error", it) }) }
                        ?: overlay.simEdits.firstOrNull { it.id == id }?.bake?.let { put(id, it.summary()) }
                }
            }
        }
        val baked = overlay.simEdits.any { it.id in applied.simulationIds && it.bake != null }
        return WorkspaceSimulationCandidate(next.copy(rigEdits = overlay, settings = physicsSettings(next.settings, baked)), report)
    }

    fun remove(document: WorkspaceDocument, id: String) = document.copy(rigEdits = SimAuthoring.remove(document.rigEdits, id))

    fun classicHair(document: WorkspaceDocument, front: Boolean, sway: Boolean = true): WorkspaceDocument {
        val id = if (front) ModelPresets.FRONT_HAIR_SIM else ModelPresets.BACK_HAIR_SIM
        val overlay = if (document.rigEdits.simEdits.any { it.id == id }) SimAuthoring.remove(document.rigEdits, id) else document.rigEdits
        return document.copy(rigEdits = overlay, settings = JsonObject(document.settings +
            ((if (front) "hairSimulationFront" else "hairSimulationBack") to JsonPrimitive(false)) +
            ((if (front) "physicsFrontHair" else "physicsBackHair") to JsonPrimitive(sway))))
    }

    fun removeClothing(document: WorkspaceDocument): WorkspaceDocument {
        val ids = ModelPresets.CLOTHING_SIMS.values.toSet()
        return document.copy(rigEdits = document.rigEdits.simEdits.map { it.id }.filter { it in ids }
            .fold(document.rigEdits) { edits, id -> SimAuthoring.remove(edits, id) })
    }

    fun withBakes(document: WorkspaceDocument, bakes: Map<String, SimBakeResult?>): WorkspaceDocument {
        val overlay = bakes.entries.fold(document.rigEdits) { edits, (id, bake) -> SimAuthoring.withBake(edits, id, bake) }
        return document.copy(rigEdits = overlay, settings = physicsSettings(document.settings, bakes.values.any { it != null }))
    }
    private fun physicsSettings(settings: JsonObject, baked: Boolean) = if (!baked) settings else JsonObject(settings + ("generatePhysics" to JsonPrimitive(true)))
    private fun JsonObject.text(key: String) = requireNotNull(this[key]?.jsonPrimitive?.contentOrNull) { "$key is required" }
}
