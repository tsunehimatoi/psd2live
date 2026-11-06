package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.project.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel
import java.util.UUID
import kotlin.math.PI
import kotlin.math.sin

data class WorkspaceSwingPreview(val report: JsonObject, val draft: RigSwingEdit, val existingId: String?,
                                 val model: RigPreviewModel, val prepared: RigSwingEdit, val motion: Int,
                                 val playing: Boolean, val values: Map<ParameterId, Float>)

/** Audition drafts own their rig and clock; the committed workspace remains the only save/query baseline. */
internal class WorkspaceSwingSessions(private val runtime: WorkspaceRuntime<RigPreviewModel>) {
    private data class Session(val id: String, val workspace: String, val capture: WorkspaceCapture<RigPreviewModel>,
        val draft: RigSwingEdit, val existingId: String?, val model: RigPreviewModel, val prepared: RigSwingEdit,
        val motion: Int = 0, val playing: Boolean = false, val started: Long = System.nanoTime(),
        val elapsed: Float = 0f, val status: String = "active")
    private val gate = Mutex()
    private val lock = Any()
    private val sessions = linkedMapOf<String, Session>()
    private val commands = WorkspaceDocumentCommands(runtime)

    suspend fun execute(projectId: String, state: String, workspaceId: String, request: JsonObject,
                        author: MutationAuthor = MutationAuthor.AGENT,
                        project: (WorkspaceCapture<RigPreviewModel>, WorkspaceDocument, RigPreviewModel) -> Unit = { _, _, _ -> }): WorkspaceSwingPreview = gate.withLock {
        val capture = runtime.capture()
        if (capture.state != state) throw WorkspaceConflict(state, capture.state)
        require(projectId == capture.projectId) { "Operation targets another project" }
        require(workspaceId.isNotBlank()) { "Workspace ID must be nonempty" }
        currentCoroutineContext().ensureActive()
        val mode = request.getValue("mode").jsonPrimitive.content
        var session = if (mode == "begin") {
            val targets = request.getValue("targets").jsonArray.map { it.jsonPrimitive.content }
            require(targets.size in 1..128 && targets.distinct().size == targets.size) { "Give distinct swing targets" }
            val puppet = capture.model.rig.puppet
            val existing = SwingCatalog.find(capture.document.rigEdits, puppet, targets)
            val preset = request["preset"]?.jsonPrimitive?.content?.let { SwingPreset.valueOf(it.uppercase()) } ?: SwingPreset.HAIR
            val draft = existing ?: defaults(capture, targets, preset, request["name"]?.jsonPrimitive?.content)
            val (model, prepared) = prepare(capture, draft)
            Session(UUID.randomUUID().toString(), workspaceId, capture, draft, existing?.id, model, prepared)
        } else synchronized(lock) { requireNotNull(sessions[request.getValue("session_id").jsonPrimitive.content]) { "Swing preview not found" } }.also {
            require(it.workspace == workspaceId && it.capture.projectId == projectId) { "Swing preview belongs to another workspace" }
            if (mode != "cancel" && it.capture.state != state) throw WorkspaceConflict(it.capture.state, state)
            require(it.status == "active" || mode == "cancel") { "Swing preview is terminal" }
        }
        val puppet = session.capture.model.rig.puppet
        val overlay = session.capture.document.rigEdits
        var committedPreview: WorkspaceSwingPreview? = null
        val draft = when (mode) {
            "begin", "select", "play", "cancel", "commit" -> session.draft
            "update" -> RigSwingEdit.fromJson(request.getValue("draft").jsonObject).also {
                require(it.id == session.draft.id && it.targets.size <= 128 &&
                    it.targets.take(session.draft.targets.size) == session.draft.targets) {
                    "A draft keeps its captured ID and can only append targets (up to 128)"
                }
            }
            "kinds" -> SwingAuthoring.withKinds(puppet, overlay, session.draft,
                request.getValue("kinds").jsonArray.map { SwingKind.valueOf(it.jsonPrimitive.content.uppercase()) })
            "segments" -> {
                val motion = request.getValue("motion").jsonPrimitive.int; val segments = request.getValue("segments").jsonPrimitive.int
                require(motion in session.draft.motions.indices && segments in 1..RigSwingEdit.MAX_SEGMENTS)
                SwingAuthoring.withSegments(puppet, overlay, session.draft, motion, segments)
            }
            "preset" -> {
                val preset = SwingPreset.valueOf(request.getValue("preset").jsonPrimitive.content.uppercase())
                SwingAuthoring.resized(puppet, session.draft.copy(preset = preset, motions = session.draft.motions.map {
                    it.copy(shape = SwingPresets.shape(preset, it.kind).copy(flip = it.shape.flip))
                }))
            }
            "physics" -> {
                val enabled = request.getValue("enabled").jsonPrimitive.boolean
                val next = session.draft.withPhysics { _, _ -> if (enabled) SwingPhysics() else null }
                if (enabled) SwingAuthoring.resized(puppet, next) else next
            }
            "handle" -> {
                val point = request.getValue("point").jsonArray
                require(point.size == 2) { "Handle point must contain two coordinates" }
                val x = point[0].jsonPrimitive.float; val y = point[1].jsonPrimitive.float
                require(x.isFinite() && y.isFinite())
                val gizmo = requireNotNull(SwingGizmo.of(session.model.rig.puppet, session.prepared, values = values(session), motion = session.motion))
                val moved = gizmo.drag(SwingGizmo.Handle.valueOf(request.getValue("handle").jsonPrimitive.content.uppercase()), x to y)
                moved.copy(id = session.draft.id, name = session.draft.name, targets = session.draft.targets)
            }
            else -> error("Unknown swing preview mode: $mode")
        }
        if (draft != session.draft) {
            val (model, prepared) = prepare(session.capture, draft)
            session = session.copy(draft = draft, model = model, prepared = prepared, motion = session.motion.coerceAtMost(draft.motions.lastIndex))
        }
        when (mode) {
            "select" -> {
                val motion = request.getValue("motion").jsonPrimitive.int
                require(motion in session.draft.motions.indices)
                session = session.copy(motion = motion)
            }
            "play" -> session = session.copy(playing = request.getValue("enabled").jsonPrimitive.boolean, elapsed = 0f, started = System.nanoTime())
            "cancel" -> session = session.copy(playing = false, elapsed = 0f, status = "cancelled")
            "commit" -> {
                val frozen = session
                // Complete all fallible audition/gizmo work before the document CAS.
                val terminal = preview(frozen.copy(playing = false, elapsed = 0f, status = "committed"))
                val completion = currentCoroutineContext()[WorkspaceSwingJobExecution]
                val result = commands.executeCandidate(projectId, state, "Set swing ${draft.id}", author,
                    mutation = { document, model -> WorkspaceDocumentEdits.swing(document, model.rig.puppet, frozen.draft, false) }, beforeCommit = project)
                session = session.copy(capture = result.capture, playing = false, elapsed = 0f, status = "committed", model = result.capture.model)
                committedPreview = terminal.copy(model = result.capture.model, report = JsonObject(terminal.report + buildJsonObject {
                    put("state", result.capture.state); put("history_node_id", result.capture.historyHead); put("stale", false)
                }))
                // No suspension or gizmo construction between CAS success and retaining its public terminal result.
                completion?.committed(committedPreview!!.report)
            }
        }
        if (mode != "commit") currentCoroutineContext().ensureActive()
        // A prepare may have suspended while another document or auxiliary writer advanced the state.
        if (mode != "commit" && runtime.capture().state != state) throw WorkspaceConflict(state, runtime.capture().state)
        synchronized(lock) {
            if (sessions.size >= 32 && session.id !in sessions) {
                val terminal = sessions.values.firstOrNull { it.status != "active" || it.capture.state != state }
                require(terminal != null) { "Close a swing preview before creating another" }
                sessions.remove(terminal.id)
            }
            sessions[session.id] = session
        }
        committedPreview ?: preview(session).also { currentCoroutineContext()[WorkspaceSwingJobExecution]?.committed(it.report) }
    }

    fun get(workspaceId: String, id: String, time: Float? = null): WorkspaceSwingPreview {
        require(time == null || (time.isFinite() && time in 0f..3600f)) { "Audition sample time must lie within 0..3600 seconds" }
        val session = synchronized(lock) { requireNotNull(sessions[id]) { "Swing preview not found" } }
        require(session.workspace == workspaceId && session.capture.projectId == runtime.capture().projectId) { "Swing preview belongs to another workspace" }
        return preview(session, time)
    }

    private fun values(session: Session, time: Float? = null): Map<ParameterId, Float> {
        val pose = PreviewSessions.read(session.model.rig.puppet.parameters, session.capture.auxiliary, session.workspace)
        val elapsed = time ?: if (session.playing) (System.nanoTime() - session.started) / 1e9f else 0f
        val motion = session.prepared.motions.withIndex().flatMap { (m, entry) -> entry.parameterIds.withIndex().map { (k, id) ->
            ParameterId(id) to if (session.playing || time != null) sin(2f * PI.toFloat() * elapsed / (if (m == 0) 1.6f else 1.3f) - k * 0.7f - m * 1.3f) else 0f
        } }.toMap().filterKeys { it !in pose.locked }
        return boundedPreviewPose(pose.values + motion, session.model.rig.puppet.parameters)
    }

    private fun preview(session: Session, time: Float? = null): WorkspaceSwingPreview {
        val values = values(session, time)
        val gizmo = SwingGizmo.of(session.model.rig.puppet, session.prepared, values = values, motion = session.motion)
        val report = buildJsonObject {
            put("project_id", session.capture.projectId); put("state", session.capture.state); put("history_node_id", session.capture.historyHead)
            put("workspace_id", session.workspace); put("session_id", session.id); put("status", session.status)
            put("stale", runtime.capture().state != session.capture.state); put("draft", session.draft.toJson()); put("motion", session.motion); put("playing", session.playing)
            session.existingId?.let { put("existing_id", it) }
            putJsonObject("values") { values.forEach { (id, value) -> put(id.raw, value) } }
            putJsonObject("handles") { gizmo?.handles()?.forEach { (handle, point) -> put(handle.name, JsonArray(listOf(JsonPrimitive(point.first), JsonPrimitive(point.second)))) } }
        }
        return WorkspaceSwingPreview(report, session.draft, session.existingId, session.model, session.prepared, session.motion, session.playing, values)
    }

    private suspend fun prepare(capture: WorkspaceCapture<RigPreviewModel>, edit: RigSwingEdit): Pair<RigPreviewModel, RigSwingEdit> = withContext(Dispatchers.Default) {
        val before = capture.document.rigEdits
        if (before.modifierStack != null) {
            var document = WorkspaceDocumentEdits.swing(capture.document, capture.model.rig.puppet, edit, false)
            val stack = io.github.psd2live.core.modifier.RigModifierStack.fromJson(requireNotNull(document.rigEdits.modifierStack))
            document = document.copy(rigEdits = document.rigEdits.copy(modifierStack = stack.update(edit.id) { it.copy(enabled = true) }.toJson()))
            document = WorkspaceModifierSimulation.prepare(document, WorkspaceRasterWork.Direct.cancellable(currentCoroutineContext()))
            val model = WorkspacePreviewBuilder().build(document, capture.model)
            val owner = requireNotNull(SwingCatalog.owner(document.rigEdits, edit.id))
            val resolved = SwingCatalog.resolved(owner)
            val targets = resolved.targets.map { target ->
                model.rig.puppet.deformers.firstOrNull { it.id.raw == target }?.id?.raw
                    ?: requireNotNull(model.rig.puppet.drawables.firstOrNull { it.id.raw == target }?.parentDeformerId?.raw)
            }.distinct()
            return@withContext model to resolved.copy(targets = targets)
        }
        val puppet = before.swingEdits.firstOrNull { it.id == edit.id }?.let { SwingGenerator.strip(capture.model.rig.puppet, it) } ?: capture.model.rig.puppet
        val overlay = SwingAuthoring.put(before, puppet, edit)
        val prepared = overlay.swingEdits.single { it.id == edit.id }
        val wrapped = overlay.authoringJournal.drop(before.authoringJournal.size).fold(puppet, RigAuthoringJournal::apply)
        capture.model.copy(rig = capture.model.rig.copy(puppet = SwingGenerator.apply(wrapped, listOf(prepared)))) to prepared
    }

    private fun defaults(capture: WorkspaceCapture<RigPreviewModel>, targets: List<String>, preset: SwingPreset, name: String?): RigSwingEdit {
        val puppet = capture.model.rig.puppet; val first = targets.first()
        val targetName = puppet.deformers.firstOrNull { it.id.raw == first }?.name ?: puppet.drawables.firstOrNull { it.id.raw == first }?.name
            ?: throw IllegalArgumentException("Swing target not found: $first")
        val (id, parameters) = SwingAuthoring.freshIds(puppet, capture.document.rigEdits, first, 1)
        val length = SwingGenerator.measure(puppet, first)?.second ?: puppet.drawables.firstOrNull { it.id.raw == first }?.let { drawable ->
            val bounds = RigGeometryTools.bounds(requireNotNull(drawable.mesh).positions)
            val scale = (puppet.deformers.firstOrNull { it.id == drawable.parentDeformerId } as? Deformer.Warp)?.let { SwingGenerator.measure(puppet, it.id.raw)?.second } ?: 1f
            maxOf(bounds[2], bounds[3]) * scale
        }
        return RigSwingEdit.single(id, name ?: "Swing $targetName", SwingKind.LATERAL, targets, parameters,
            shape = SwingPresets.shape(preset, SwingKind.LATERAL), preset = preset, physics = SwingPresets.physics(preset, SwingKind.LATERAL, length))
    }
}

internal class WorkspaceSwingJobExecution(private val completion: WorkspaceJobCompletion) : kotlin.coroutines.AbstractCoroutineContextElement(Key) {
    companion object Key : kotlin.coroutines.CoroutineContext.Key<WorkspaceSwingJobExecution>
    fun committed(report: JsonObject) = completion.committed(WorkspaceOperationOutput(report))
}
