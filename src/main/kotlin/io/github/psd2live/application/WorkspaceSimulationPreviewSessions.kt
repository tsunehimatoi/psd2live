package io.github.psd2live.application

import io.github.psd2live.core.RigPreviewModel
import io.github.psd2live.core.sim.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import org.umamo.runtime.model.ParameterId
import java.util.UUID

data class WorkspaceSimulationPreview(val report: JsonObject, val model: RigPreviewModel,
    val frame: SimulatedFrame, val values: Map<ParameterId, Float>, val notes: List<String>)

/** Private calibrated scenes and clocks. Only immutable copied frames leave this process session. */
internal class WorkspaceSimulationPreviewSessions(private val runtime: WorkspaceRuntime<RigPreviewModel>) {
    private data class Session(val id: String, val workspace: String, val capture: WorkspaceCapture<RigPreviewModel>,
        val edit: RigSimEdit, val engine: SimPreview, val values: Map<ParameterId, Float>, val notes: List<String>,
        val frame: SimulatedFrame, val elapsed: Double = 0.0, val status: String = "running")
    private val gate = Mutex()
    private val lock = Any()
    private val sessions = linkedMapOf<String, Session>()

    suspend fun control(projectId: String, state: String, workspace: String, request: JsonObject): WorkspaceSimulationPreview = gate.withLock {
        val capture = checked(projectId, state)
        require(workspace.isNotBlank())
        val context = currentCoroutineContext()
        context.ensureActive()
        val mode = request.getValue("mode").jsonPrimitive.content
        var session = if (mode == "start") {
            val edit = requireNotNull(io.github.psd2live.core.sim.SimulationCatalog.entries(capture.document.rigEdits).firstOrNull { it.id == request.getValue("simulation_id").jsonPrimitive.content }) { "Simulation not found" }
            require(edit.enabled) { "Simulation is disabled" }
            val pose = pose(capture, workspace, edit, request)
            val engine = SimPreview()
            val notes = runInterruptible(Dispatchers.Default) {
                engine.prepare(capture.model.rig.puppet, edit, pose,
                    progress = { context.ensureActive(); context[WorkspaceJobContext]?.progress(0.05f + 0.85f * it, "Calibrating simulation") },
                    cancelled = { context.ensureActive(); false })
            }
            Session(UUID.randomUUID().toString(), workspace, capture, edit, engine, pose, notes, engine.frame())
        } else owned(projectId, workspace, request.getValue("session_id").jsonPrimitive.content).also {
            require(mode == "stop" || it.status == "running") { "Simulation preview is stopped" }
            if (mode != "stop") requireCurrent(it, capture)
        }
        val previous = if (mode == "restart") session.engine.snapshot() else null
        try { when (mode) {
            "start" -> Unit
            "stop" -> session = session.copy(status = "stopped")
            "restart" -> {
                val pose = pose(capture, workspace, session.edit, request)
                runInterruptible(Dispatchers.Default) { context.ensureActive(); session.engine.restart(session.model(), pose); context.ensureActive() }
                context[WorkspaceJobContext]?.progress(0.9f, "Restarted simulation")
                context.ensureActive(); checked(projectId, state)
                session = session.copy(capture = capture, values = pose, frame = session.engine.frame(), elapsed = 0.0)
            }
            else -> error("Unknown simulation preview control: $mode")
        }
        context.ensureActive(); checked(projectId, state)
        val result = output(session)
        publish(session, state, replaceActive = mode == "start")
        context[WorkspaceSimulationPreviewJobExecution]?.committed(result.report)
        result
        } catch (failure: Exception) {
            previous?.let(session.engine::restore)
            throw failure
        }
    }

    suspend fun step(projectId: String, state: String, workspace: String, request: JsonObject): WorkspaceSimulationPreview = gate.withLock {
        val capture = checked(projectId, state)
        val session = owned(projectId, workspace, request.getValue("session_id").jsonPrimitive.content)
        require(session.status == "running") { "Simulation preview is stopped" }
        requireCurrent(session, capture)
        val dt = request.getValue("dt").jsonPrimitive.float
        val steps = request["steps"]?.jsonPrimitive?.int ?: 1
        require(dt.isFinite() && dt > 0f && dt <= 0.1f && steps in 1..240) { "Use dt within (0,0.1] and 1..240 steps" }
        val values = pose(capture, workspace, session.edit, request)
        val context = currentCoroutineContext(); val saved = session.engine.snapshot()
        var frame = session.frame
        try {
            runInterruptible(Dispatchers.Default) {
                repeat(steps) { index ->
                    context.ensureActive()
                    session.engine.step(session.model(), session.edit, values, dt) { context.ensureActive() }?.let { frame = it }
                    context[WorkspaceJobContext]?.progress(0.05f + 0.85f * (index + 1) / steps, "Solving simulation")
                }
            }
            context.ensureActive(); checked(projectId, state)
            val next = session.copy(capture = capture, values = values, frame = frame, elapsed = session.elapsed + dt * steps)
            val result = output(next)
            publish(next, state)
            context[WorkspaceSimulationPreviewJobExecution]?.committed(result.report)
            result
        } catch (failure: Exception) { session.engine.restore(saved); throw failure }
    }

    fun get(projectId: String, workspace: String, id: String): WorkspaceSimulationPreview = output(owned(projectId, workspace, id))

    private fun checked(projectId: String, state: String): WorkspaceCapture<RigPreviewModel> = runtime.capture().also {
        require(it.projectId == projectId) { "Simulation preview belongs to another project" }
        if (it.state != state) throw WorkspaceConflict(state, it.state)
    }
    private fun owned(projectId: String, workspace: String, id: String) = synchronized(lock) {
        requireNotNull(sessions[id]) { "Simulation preview not found" }.also {
            require(it.capture.projectId == projectId && it.workspace == workspace) { "Simulation preview belongs to another workspace" }
        }
    }
    private fun stale(session: Session, current: WorkspaceCapture<RigPreviewModel>) =
        !runtime.sameLoadGeneration(session.capture, current) || session.capture.revision != current.revision
    private fun requireCurrent(session: Session, current: WorkspaceCapture<RigPreviewModel>) {
        if (stale(session, current)) throw WorkspaceConflict(session.capture.state, current.state)
    }
    private fun pose(capture: WorkspaceCapture<RigPreviewModel>, workspace: String, edit: RigSimEdit, request: JsonObject): Map<ParameterId, Float> {
        val parameters = capture.model.rig.puppet.parameters
        val authored = PreviewSessions.read(parameters, capture.auxiliary, workspace)
        val values = request["values"]?.jsonObject.orEmpty().mapValues { it.value.jsonPrimitive.float }
        val evaluated = (if (values.isEmpty()) authored.values else PreviewSessions.edit(parameters, authored, values, emptyMap()).values) +
            authored.values.filterKeys { it in authored.locked }
        return SimGenerator.withoutModes(evaluated, edit)
    }
    private fun Session.model() = capture.model.rig.puppet
    private fun publish(session: Session, expectedState: String, replaceActive: Boolean = false) = runtime.withCapture { current -> synchronized(lock) {
        if (current.state != expectedState) throw WorkspaceConflict(expectedState, current.state)
        if (sessions.size >= 32 && session.id !in sessions) {
            val old = sessions.values.firstOrNull { it.status == "stopped" || stale(it, current) }
            require(old != null) { "Stop an unused simulation preview before starting another" }
            sessions.remove(old.id)
        }
        if (replaceActive) sessions.replaceAll { _, old -> if (old.workspace == session.workspace && old.capture.projectId == session.capture.projectId)
            old.copy(status = "stopped") else old }
        sessions[session.id] = session
    } }
    private fun output(session: Session): WorkspaceSimulationPreview {
        val frame = SimulatedFrame(session.edit.id, session.frame.positions.mapValues { it.value.copyOf() }, session.frame.serial)
        require(frame.positions.values.all { vertices -> vertices.all(Float::isFinite) }) { "Simulation produced nonfinite vertices" }
        val current = runtime.capture()
        val report = buildJsonObject {
            put("project_id", session.capture.projectId); put("state", session.capture.state); put("history_node_id", session.capture.historyHead)
            put("workspace_id", session.workspace); put("session_id", session.id); put("simulation_id", session.edit.id)
            put("status", session.status); put("stale", stale(session, current)); put("serial", frame.serial); put("elapsed", session.elapsed)
            putJsonArray("notes") { session.notes.forEach { add(it) } }
            putJsonObject("values") { session.values.forEach { (id, value) -> put(id.raw, value) } }
            putJsonObject("vertex_counts") { frame.positions.forEach { (id, vertices) -> put(id.raw, vertices.size / 2) } }
            putJsonObject("positions") {}
        }
        return WorkspaceSimulationPreview(report, session.capture.model, frame, session.values.toMap(), session.notes.toList())
    }
}

internal class WorkspaceSimulationPreviewJobExecution(private val completion: WorkspaceJobCompletion) :
    kotlin.coroutines.AbstractCoroutineContextElement(Key) {
    companion object Key : kotlin.coroutines.CoroutineContext.Key<WorkspaceSimulationPreviewJobExecution>
    fun committed(report: JsonObject) = completion.committed(WorkspaceOperationOutput(report))
}
