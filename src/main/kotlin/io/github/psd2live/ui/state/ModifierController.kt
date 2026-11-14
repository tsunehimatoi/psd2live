package io.github.psd2live.ui.state

import androidx.compose.runtime.*
import io.github.psd2live.application.WorkspaceReadPort
import io.github.psd2live.application.WorkspaceDocumentOperation
import io.github.psd2live.i18n.tr
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/** UI-only modifier session. Published documents and all commits remain owned by WorkspaceRuntime. */
internal class ModifierController(
    private val state: () -> PSD2LiveState,
    private val readPort: () -> WorkspaceReadPort?,
    private val stateToken: () -> String?,
    private val selectedObjectTargets: () -> List<String>,
    private val scope: CoroutineScope,
    private val submit: (String, String, List<WorkspaceDocumentOperation>, (String?) -> Unit) -> Unit,
    private val reportError: (String?) -> Unit,
) : AutoCloseable {
    var modifierAddition by mutableStateOf<ModifierAddition?>(null)
        private set
    private var modifierPreviewJob: kotlinx.coroutines.Job? = null
    private var modifierPreviewSequence = 0
    private var session = 0L

    fun openModifierAddition(group: Boolean = false) {
        dismissModifierAddition()
        val model = state().previewModel ?: return
        val expected = stateToken() ?: return
        val objects = selectedObjectTargets()
        val selected = objects.filter { it.startsWith("mesh:") }.map { it.substringAfter(':') }
        val warp = if (!group && selected.isEmpty()) objects.firstOrNull { it.startsWith("warp:") }?.substringAfter(':') else null
        val authoredIds = model.authored.rig.puppet.drawables.mapTo(HashSet()) { it.id }
        val ids = selected.ifEmpty { model.rig.puppet.drawables.filter { it.mesh != null && it.id in authoredIds }.map { it.id.raw } }
        if (ids.isEmpty()) return
        modifierAddition = ModifierAddition(expected, if (warp != null) listOf(io.github.psd2live.core.modifier.ModifierTarget("deformer", warp))
            else ids.map { io.github.psd2live.core.modifier.ModifierTarget("mesh", it) })
        val dialog = requireNotNull(modifierAddition)
        val first = io.github.psd2live.core.modifier.RigLegacyBindings.specs
            .sortedBy { it.type.endsWith("/legacy") && it.type != "body/legacy" }
            .firstOrNull { it.type != "face.depth-turn" && !dialog.availability(model, it.type).hidden }?.type
        modifierAddition = modifierAddition?.copy(type = if (group) null else if (warp != null) "swing" else first,
            group = if (group) io.github.psd2live.application.WorkspaceModifierPlan.RECOMMENDED_GROUP else null)
    }

    fun dismissModifierAddition() { session++; modifierPreviewSequence++; modifierPreviewJob?.cancel(); modifierAddition = null }

    fun previewModifierAddition(type: String?, group: String?, configuration: kotlinx.serialization.json.JsonObject? = null, preset: String? = null) {
        val dialog = modifierAddition ?: return
        if (dialog.committing) return
        val workspace = readPort() ?: return
        modifierPreviewJob?.cancel()
        val sequence = ++modifierPreviewSequence
        modifierAddition = dialog.copy(type = type, group = group, configuration = configuration, plan = null, preview = null, loading = true, error = null)
        modifierPreviewJob = scope.launch {
            try {
                val (plan, preview) = withContext(Dispatchers.Default) {
                    val editedPlan = if (configuration != null && preset == null && group == null && type == dialog.type) {
                        dialog.plan?.let { previous ->
                            val entries = previous.getValue("entries").jsonArray
                            val entry = entries.lastOrNull()?.jsonObject
                            if (entry == null || entry["type"]?.jsonPrimitive?.content != type) null
                            else kotlinx.serialization.json.JsonObject(previous + ("entries" to kotlinx.serialization.json.JsonArray(
                                entries.dropLast(1) + kotlinx.serialization.json.JsonObject(entry + ("config" to configuration)))))
                        }
                    } else null
                    val plan = editedPlan ?: workspace.captureQueries().modifierPlan(kotlinx.serialization.json.buildJsonObject {
                        type?.let { put("type", it) }; group?.let { put("group", it) }; configuration?.let { put("config", it) }; preset?.let { put("preset", it) }
                        put("targets", kotlinx.serialization.json.JsonArray(dialog.targets.map { target -> kotlinx.serialization.json.buildJsonObject {
                            put("kind", target.kind); put("id", target.id)
                        } }))
                    })
                    if (plan.getValue("state").jsonPrimitive.content != dialog.state) throw io.github.psd2live.application.WorkspaceConflict(dialog.state, plan.getValue("state").jsonPrimitive.content)
                    withContext(Dispatchers.Main) {
                        if (sequence == modifierPreviewSequence) modifierAddition = modifierAddition?.copy(plan = plan)
                    }
                    val preview: kotlinx.serialization.json.JsonObject? = null
                    plan to preview
                }
                if (sequence == modifierPreviewSequence) modifierAddition = modifierAddition?.copy(plan = plan, preview = preview, loading = false)
            } catch (failure: Exception) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                if (sequence == modifierPreviewSequence) modifierAddition = modifierAddition?.copy(loading = false, error = failure.message)
            }
        }
    }

    fun switchModifierPreset(group: String) {
        val dialog = modifierAddition ?: return
        if (dialog.committing) return
        val owner = session
        modifierPreviewSequence++
        modifierPreviewJob?.cancel()
        modifierAddition = dialog.copy(loading = true, committing = true)
        submit(dialog.state, tr("modifier.switch"), listOf(io.github.psd2live.application.WorkspaceDocumentOperation(
            "modifier_switch", kotlinx.serialization.json.buildJsonObject { put("group", group) }))) { failure ->
            if (owner != session) return@submit
            if (failure == null) dismissModifierAddition()
            else modifierAddition = modifierAddition?.copy(loading = false, committing = false, error = failure)
        }
    }

    fun confirmModifierAddition() {
        val dialog = modifierAddition ?: return
        val plan = dialog.plan ?: return
        if (dialog.loading || plan.getValue("entries").jsonArray.isEmpty()) return
        val owner = session
        modifierPreviewSequence++
        modifierPreviewJob?.cancel()
        modifierAddition = dialog.copy(loading = true, committing = true)
        submit(dialog.state, tr("modifier.history.add"), listOf(io.github.psd2live.application.WorkspaceDocumentOperation("modifier_add_group",
            kotlinx.serialization.json.buildJsonObject { put("group", plan.getValue("group")); put("entries", plan.getValue("entries")) }))) { failure ->
            if (owner != session) return@submit
            if (failure == null) dismissModifierAddition() else modifierAddition = modifierAddition?.copy(loading = false, committing = false, error = failure)
        }
    }

    /** Reject a field captured from a projection that has not caught up with the authoritative stack. */
    fun modifierEditState(): String? {
        val shown = state().rigEdits.modifierStack?.let(io.github.psd2live.core.modifier.RigModifierStack::fromJson)
        val observed = readPort()?.captureQueries()?.modifierStack() ?: return null
        val rows = observed.getValue("entries").jsonArray
        val entries = shown?.instances.orEmpty()
        val matches = state().historySnapshot?.headNodeId == observed.getValue("history_node_id").jsonPrimitive.content &&
            observed.getValue("legacy").jsonPrimitive.boolean == (shown == null) &&
            rows.size == entries.size && entries.zip(rows).all { (instance, raw) ->
            val row = raw.jsonObject
            row.getValue("id").jsonPrimitive.content == instance.id && row.getValue("config") ==
                (if (instance.type == "simulation") kotlinx.serialization.json.JsonObject(instance.config - "bake") else instance.config) &&
                row.getValue("enabled").jsonPrimitive.boolean == instance.enabled
        }
        if (!matches) { reportError(tr("modifier.stale")); return null }
        return observed.getValue("state").jsonPrimitive.content
    }

    fun editModifier(operation: String, request: kotlinx.serialization.json.JsonObject, expectedState: String? = modifierEditState(),
                              onComplete: (String?) -> Unit = {}) {
        val expected = expectedState ?: run { onComplete(tr("modifier.stale")); return }
        submit(expected, tr("modifier.history.edit"), listOf(io.github.psd2live.application.WorkspaceDocumentOperation(operation, request))) { failure ->
            if (failure != null) reportError(failure)
            onComplete(failure)
        }
    }

    override fun close() = dismissModifierAddition()
}
