package io.github.psd2live.application

import io.github.psd2live.core.RigPreviewModel
import kotlinx.serialization.json.*

enum class CanvasViewMode { EDIT, PREVIEW; val key: String get() = name.lowercase() }

/** Canvas IDs repeat across workspaces, and each canvas keeps separate edit and preview sessions. */
data class CanvasAddress(val workspaceId: String, val canvasId: String, val mode: CanvasViewMode) {
    init { require(workspaceId.isNotBlank() && canvasId.isNotBlank()) { "Canvas address must be nonempty" } }
}

/**
 * One canvas session's v1 presentation fields. They filter what that canvas draws; the document's own
 * layer visibility, and therefore export, never reads them.
 */
data class CanvasVisibility(
    val layers: Map<String, Boolean> = emptyMap(),
    val deformers: Map<String, Boolean> = emptyMap(),
    val isolatedLayerId: String? = null,
    val isolationSnapshot: Map<String, Boolean>? = null,
) {
    /** Same fallback as the hierarchy eye: a mirrored half inherits its source layer's entry. */
    fun layerVisible(id: String, defaultVisible: Boolean): Boolean = layers[id]
        ?: (if (id.endsWith(":l") || id.endsWith(":r")) layers[id.dropLast(2)] else null)
        ?: defaultVisible
}

data class CanvasLayer(val id: String, val sourceVisible: Boolean)

/** What a canvas can name. [layers] drives whole-canvas actions; IDs also accept drawable-mapped layers. */
data class CanvasVisibilityScope(val layers: List<CanvasLayer>, val layerIds: Set<String>, val deformerIds: Set<String>) {
    companion object {
        fun of(model: RigPreviewModel): CanvasVisibilityScope {
            val layers = model.analysis.layers.map { CanvasLayer(it.source.id.raw, it.source.visible) }
            return CanvasVisibilityScope(layers, layers.mapTo(HashSet()) { it.id } + model.rig.layerIdByDrawableId.values,
                model.rig.puppet.deformers.mapTo(HashSet()) { it.id.raw })
        }
    }
}

sealed interface CanvasVisibilityIntent {
    /** Restore a temporary view operation without changing document visibility. */
    data class Restore(val value: CanvasVisibility) : CanvasVisibilityIntent
    data class Layers(val visibility: Map<String, Boolean>) : CanvasVisibilityIntent
    data class Deformers(val visibility: Map<String, Boolean>) : CanvasVisibilityIntent
    data class AllLayers(val visible: Boolean) : CanvasVisibilityIntent
    data object InvertLayers : CanvasVisibilityIntent
    data class Solo(val layerId: String) : CanvasVisibilityIntent
    data object Unsolo : CanvasVisibilityIntent
    /** The hierarchy menu item: the soloed layer restores the canvas, any other layer becomes the solo. */
    data class ToggleSolo(val layerId: String) : CanvasVisibilityIntent
}

/** Pure candidate step shared by the desktop hierarchy and external agents. */
object CanvasVisibilityProcessor {
    /** A null [scope] means no model is loaded yet; only per-ID edits are possible then. */
    fun apply(current: CanvasVisibility, intent: CanvasVisibilityIntent, scope: CanvasVisibilityScope?): CanvasVisibility {
        fun layers() = requireNotNull(scope) { "No model is loaded" }.layers
        fun requireLayer(id: String) = require(scope == null || id in scope.layerIds) { "Layer not found: $id" }
        return when (intent) {
            is CanvasVisibilityIntent.Restore -> {
                val value=intent.value
                require(scope==null || (value.layers.keys.all { it in scope.layerIds } && value.deformers.keys.all { it in scope.deformerIds }))
                value
            }
            // Any explicit layer choice ends a solo without restoring it, as the hierarchy always has.
            is CanvasVisibilityIntent.Layers -> {
                require(intent.visibility.isNotEmpty()) { "Provide at least one layer" }
                intent.visibility.keys.forEach(::requireLayer)
                CanvasVisibility(current.layers + intent.visibility, current.deformers)
            }
            is CanvasVisibilityIntent.Deformers -> {
                require(intent.visibility.isNotEmpty()) { "Provide at least one deformer" }
                intent.visibility.keys.forEach { require(scope == null || it in scope.deformerIds) { "Deformer not found: $it" } }
                current.copy(deformers = current.deformers + intent.visibility)
            }
            is CanvasVisibilityIntent.AllLayers -> CanvasVisibility(layers().associate { it.id to intent.visible }, current.deformers)
            CanvasVisibilityIntent.InvertLayers -> CanvasVisibility(
                layers().associate { it.id to !current.layerVisible(it.id, it.sourceVisible) }, current.deformers)
            is CanvasVisibilityIntent.Solo -> {
                requireLayer(intent.layerId)
                // Moving a solo keeps the visibility from before the first solo, so one restore undoes it.
                val snapshot = current.isolationSnapshot.takeIf { current.isolatedLayerId != null } ?: current.layers
                current.copy(layers = layers().associate { it.id to (it.id == intent.layerId) },
                    isolatedLayerId = intent.layerId, isolationSnapshot = snapshot)
            }
            CanvasVisibilityIntent.Unsolo -> if (current.isolatedLayerId == null) current
                else current.copy(layers = current.isolationSnapshot.orEmpty(), isolatedLayerId = null, isolationSnapshot = null)
            is CanvasVisibilityIntent.ToggleSolo ->
                if (current.isolatedLayerId == intent.layerId && current.isolationSnapshot != null) apply(current, CanvasVisibilityIntent.Unsolo, scope)
                else apply(current, CanvasVisibilityIntent.Solo(intent.layerId), scope)
        }
    }

    /**
     * Replacing the source ends every solo without restoring it: the snapshot names layers of the old model,
     * so each canvas keeps showing what it showed and the solo is simply forgotten.
     */
    fun endSolo(value: CanvasVisibility): CanvasVisibility =
        if (value.isolatedLayerId == null && value.isolationSnapshot == null) value
        else value.copy(isolatedLayerId = null, isolationSnapshot = null)

    /** Applies [endSolo] to every stored canvas; untouched auxiliary data keeps its exact bytes. */
    fun endSolos(auxiliary: JsonObject): JsonObject {
        val records = CanvasVisibilityCodec.decode(auxiliary)
        val ended = records.mapValues { endSolo(it.value) }
        return if (ended == records) auxiliary else CanvasVisibilityCodec.withRecords(auxiliary, ended)
    }

    /** A solo whose layer no longer exists reads as no solo; the stored record is not rewritten. */
    fun normalize(value: CanvasVisibility, scope: CanvasVisibilityScope): CanvasVisibility =
        if (value.isolatedLayerId == null || value.isolatedLayerId in scope.layerIds) value
        else value.copy(isolatedLayerId = null, isolationSnapshot = null)

    fun hiddenLayerIds(value: CanvasVisibility, scope: CanvasVisibilityScope): List<String> =
        scope.layers.filterNot { value.layerVisible(it.id, it.sourceVisible) }.map { it.id }
}

/** Auxiliary copy of the canvas presentations; the GUI keeps saving them at their v1 location. */
object CanvasVisibilityCodec {
    const val KEY = "canvasVisibility"

    fun encodeRecord(value: CanvasVisibility): JsonObject = buildJsonObject {
        putJsonObject("layerVisibility") { value.layers.toSortedMap().forEach { (id, visible) -> put(id, visible) } }
        putJsonObject("deformerVisibility") { value.deformers.toSortedMap().forEach { (id, visible) -> put(id, visible) } }
        put("isolatedLayerId", value.isolatedLayerId)
        value.isolationSnapshot?.let { snapshot -> putJsonObject("isolationSnapshot") {
            snapshot.toSortedMap().forEach { (id, visible) -> put(id, visible) } } }
    }

    /** Lenient like the v1 presentation reader: malformed entries are skipped, not fatal. */
    fun decodeRecord(value: JsonObject): CanvasVisibility {
        fun flags(key: String) = (value[key] as? JsonObject)?.mapNotNull { (id, flag) ->
            (flag as? JsonPrimitive)?.booleanOrNull?.let { id to it } }?.toMap()
        return CanvasVisibility(flags("layerVisibility").orEmpty(), flags("deformerVisibility").orEmpty(),
            (value["isolatedLayerId"] as? JsonPrimitive)?.contentOrNull, flags("isolationSnapshot"))
    }

    /** Default records are omitted, so equal visibility always encodes to equal auxiliary data. */
    fun encode(records: Map<CanvasAddress, CanvasVisibility>): JsonObject {
        val present = records.filterValues { it != CanvasVisibility() }
        return buildJsonObject {
            present.keys.groupBy { it.workspaceId }.toSortedMap().forEach { (workspaceId, addresses) ->
                putJsonObject(workspaceId) {
                    addresses.groupBy { it.canvasId }.toSortedMap().forEach { (canvasId, modes) ->
                        putJsonObject(canvasId) { modes.sortedBy { it.mode }.forEach { put(it.mode.key, encodeRecord(present.getValue(it))) } }
                    }
                }
            }
        }
    }

    fun decode(auxiliary: JsonObject): Map<CanvasAddress, CanvasVisibility> = buildMap {
        (auxiliary[KEY] as? JsonObject)?.forEach { (workspaceId, canvases) ->
            (canvases as? JsonObject)?.forEach { (canvasId, modes) ->
                (modes as? JsonObject)?.forEach { (mode, record) ->
                    val viewMode = CanvasViewMode.entries.firstOrNull { it.key == mode }
                    if (viewMode != null && record is JsonObject && workspaceId.isNotBlank() && canvasId.isNotBlank())
                        put(CanvasAddress(workspaceId, canvasId, viewMode), decodeRecord(record))
                }
            }
        }
    }

    fun withRecords(auxiliary: JsonObject, records: Map<CanvasAddress, CanvasVisibility>): JsonObject {
        val encoded = encode(records)
        return JsonObject(if (encoded.isEmpty()) auxiliary - KEY else auxiliary + (KEY to encoded))
    }

    /**
     * Reads the v1 location `workspaces[].canvases[].editSession|previewSession.presentation`, including
     * canvases saved before separate mode sessions, where the canvas object was its current mode's session.
     */
    fun fromPresentation(ui: JsonObject): Map<CanvasAddress, CanvasVisibility> = buildMap {
        val workspaces = (ui["workspaces"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            .filter { (it["id"] as? JsonPrimitive)?.contentOrNull?.isNotBlank() == true }
            .distinctBy { it.getValue("id").jsonPrimitive.content }
        for (workspace in workspaces) {
            val workspaceId = workspace.getValue("id").jsonPrimitive.content
            val canvases = (workspace["canvases"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
                .filter { (it["id"] as? JsonPrimitive)?.contentOrNull?.isNotBlank() == true }
                .distinctBy { it.getValue("id").jsonPrimitive.content }
            for (canvas in canvases) {
                val canvasId = canvas.getValue("id").jsonPrimitive.content
                val current = (canvas["mode"] as? JsonPrimitive)?.contentOrNull
                    ?.let { name -> CanvasViewMode.entries.firstOrNull { it.name == name } } ?: CanvasViewMode.EDIT
                for (mode in CanvasViewMode.entries) {
                    val session = canvas[if (mode == CanvasViewMode.EDIT) "editSession" else "previewSession"]
                        ?: canvas.takeIf { mode == current }
                    val presentation = (session as? JsonObject)?.get("presentation") as? JsonObject ?: continue
                    val record = decodeRecord(presentation)
                    if (record != CanvasVisibility()) put(CanvasAddress(workspaceId, canvasId, mode), record)
                }
            }
        }
    }
}

data class WorkspaceCanvasVisibilitySnapshot(
    val projectId: String,
    val state: String,
    val historyNodeId: String,
    val canvases: Map<CanvasAddress, CanvasVisibility>,
    val scope: CanvasVisibilityScope,
)

/** Local visibility uses auxiliary CAS: it never rebuilds the model or adds a history node. */
internal class WorkspaceCanvasVisibilityCommands(private val runtime: WorkspaceRuntime<RigPreviewModel>) {
    /** [addresses] are the host's live canvases; a canvas with no record reads as the default. */
    fun snapshot(capture: WorkspaceCapture<RigPreviewModel>, addresses: Collection<CanvasAddress>): WorkspaceCanvasVisibilitySnapshot {
        val scope = CanvasVisibilityScope.of(capture.model)
        val records = CanvasVisibilityCodec.decode(capture.auxiliary)
        return WorkspaceCanvasVisibilitySnapshot(capture.projectId, capture.state, capture.historyHead,
            addresses.associateWith { CanvasVisibilityProcessor.normalize(records[it] ?: CanvasVisibility(), scope) }, scope)
    }

    fun edit(projectId: String, state: String, address: CanvasAddress, addresses: Collection<CanvasAddress>,
             intent: CanvasVisibilityIntent,
             project: (WorkspaceCapture<RigPreviewModel>, CanvasVisibility, Boolean) -> Unit = { _, _, _ -> }): WorkspaceCanvasVisibilitySnapshot {
        val before = runtime.capture()
        if (state != before.state) throw WorkspaceConflict(state, before.state)
        require(projectId == before.projectId) { "Operation targets another project" }
        require(address in addresses) { "Canvas not found: ${address.workspaceId}/${address.canvasId}/${address.mode.key}" }
        val scope = CanvasVisibilityScope.of(before.model)
        val records = CanvasVisibilityCodec.decode(before.auxiliary)
        val current = CanvasVisibilityProcessor.normalize(records[address] ?: CanvasVisibility(), scope)
        val next = CanvasVisibilityProcessor.apply(current, intent, scope)
        val changed = next != current
        // A logical no-op keeps the exact auxiliary bytes, so the state token and dirty flag stay put.
        val auxiliary = if (!changed) before.auxiliary
            else CanvasVisibilityCodec.withRecords(before.auxiliary, records + (address to next))
        val committed = runtime.updateAuxiliary(before.projectId, before.state, auxiliary, projectUnchanged = true) { captured, _ ->
            project(captured, next, changed)
        }
        return snapshot(committed, addresses)
    }
}
