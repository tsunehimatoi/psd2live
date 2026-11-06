package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.project.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*
import org.umamo.runtime.model.ParameterId
import kotlin.math.abs

/** An authored gesture commits its pose and optional timeline recording in the same CAS. */
internal class WorkspacePoseCommands(private val runtime: WorkspaceRuntime<RigPreviewModel>) {
    private val previews = WorkspacePreviewBuilder()

    suspend fun execute(projectId: String, state: String, workspaceId: String, request: JsonObject,
                        author: MutationAuthor,
                        project: (WorkspaceCapture<RigPreviewModel>, WorkspaceDocument, RigPreviewModel, WorkspacePose) -> Unit = { _, _, _, _ -> }): JsonObject {
        val before = runtime.capture()
        if (state != before.state) throw WorkspaceConflict(state, before.state)
        require(projectId == before.projectId) { "Operation targets another project" }
        require(workspaceId.isNotBlank()) { "Workspace ID must be nonempty" }
        val puppet = before.model.rig.puppet
        val context = currentCoroutineContext()
        context.ensureActive(); context[WorkspaceJobContext]?.progress(0.1f, "Preparing authored pose")
        val current = PreviewSessions.read(puppet.parameters, before.auxiliary, workspaceId)
        val values = request["values"]?.jsonObject.orEmpty().mapValues { it.value.jsonPrimitive.float }
        val locks = request["locks"]?.jsonObject.orEmpty().mapValues { it.value.jsonPrimitive.boolean }
        require(values.isNotEmpty() || locks.isNotEmpty() || request["bone_id"] != null || request["ik_target"] != null || request["bone_ik"] != null) { "Provide pose values, locks or a bone target" }
        var document = before.document
        request["ik_target"]?.jsonObject?.let { target ->
            val spec = requireNotNull(document.rigEdits.skeleton) { "Create a skeleton first" }
            val id = target.getValue("bone_id").jsonPrimitive.content
            require(spec.bone(id) != null) { "Bone not found: $id" }
            val point = target.getValue("point").takeUnless { it == JsonNull }?.jsonArray
            require(point == null || point.size == 2) { "IK target must contain two coordinates" }
            val next = point?.let { SkeletonIkTarget(it[0].jsonPrimitive.float, it[1].jsonPrimitive.float,
                target["enabled"]?.jsonPrimitive?.boolean ?: true) }
            document = document.copy(rigEdits = document.rigEdits.copy(skeleton = spec.withIkTarget(id, next)))
        }
        request["bone_ik"]?.jsonObject?.let { input ->
            val spec = requireNotNull(document.rigEdits.skeleton) { "Create a skeleton first" }
            val bone = requireNotNull(spec.bone(input.getValue("bone_id").jsonPrimitive.content)) { "Bone not found" }
            val settings = SkeletonIkSettings.fromJson(input.getValue("settings").jsonObject)
            document = document.copy(rigEdits = document.rigEdits.copy(skeleton = spec.withBone(bone.copy(ik = settings))))
        }
        var pose = if (values.isEmpty() && locks.isEmpty()) current else PreviewSessions.edit(puppet.parameters, current, values, locks)
        request["bone_id"]?.let { boneId ->
            val spec = requireNotNull(document.rigEdits.skeleton?.takeIf { it.enabled }) { "Create and enable a skeleton first" }
            require(spec.bone(boneId.jsonPrimitive.content) != null) { "Bone not found" }
            val point = request.getValue("target").jsonArray
            require(point.size == 2) { "Target must contain two coordinates" }
            val x = point[0].jsonPrimitive.float; val y = point[1].jsonPrimitive.float
            require(x.isFinite() && y.isFinite()) { "Target must be finite" }
            val ik = request["ik"]?.jsonPrimitive?.boolean ?: true
            val solved = SkeletonPoseSolver.drag(spec, SkeletonPoseSolver.posed(puppet, spec, pose.values),
                BoneHit(boneId.jsonPrimitive.content, ik), x, y, pose.values, ik)
            pose = PreviewSessions.normalize(puppet.parameters, pose.copy(values = pose.values + solved))
        }
        val constraints = SkeletonPoseSolver.solveTargets(puppet, document.rigEdits.skeleton, pose.values)
        pose = PreviewSessions.normalize(puppet.parameters, pose.copy(values = pose.values + constraints))
        val keyed = linkedSetOf<MotionKeyRef>()
        request["auto_key"]?.jsonObject?.let { record ->
            val id = record.getValue("clip_id").jsonPrimitive.content
            val clip = requireNotNull(MotionCatalog.entries(document.rigEdits).firstOrNull { it.id == id }
                ?: id.takeIf { document.rigEdits.modifierStack == null && it.startsWith("preset:") }?.removePrefix("preset:")?.let { builtin ->
                    require(builtin in MotionClips.BUILTIN_NAMES) { "Unknown generated motion" }
                    MotionClips.overrideOf(document.rigEdits.motionClips, builtin) ?: MotionPresets.clip(id, builtin,
                        document.rigEdits.skeleton, document.rigEdits.motionPresets[builtin] ?: MotionPresetSettings(), document.rigEdits.idRenames.parameters)
                }) { "Motion not found: $id" }
            val requestedTime = record.getValue("time").jsonPrimitive.float
            require(requestedTime.isFinite() && requestedTime in 0f..clip.duration) { "Time must lie within the clip" }
            val time = if (record["snap"]?.jsonPrimitive?.boolean == true) MotionKeyEdits.snap(requestedTime, clip.fps).coerceIn(0f, clip.duration) else requestedTime
            val aids = io.github.psd2live.project.TimelineAidsCodec.decode(before.auxiliary[io.github.psd2live.project.TimelineAidsCodec.KEY])
            // A track the motion editor froze takes no keys.
            val frozen = aids.clip(io.github.psd2live.project.TimelineAids.keyOf(clip.id, clip.builtin)).frozen.keys
            val definitions = puppet.parameters.associateBy { it.id.raw }
            // A repeating axis keys the turn nearest the curve's own value, not the wrapped value the pose holds.
            val changes = pose.values.filter { (parameter, value) -> abs(value - current.values.getValue(parameter)) >= 1e-5f }
                .mapKeys { it.key.raw }.filterKeys { it !in frozen }
                .mapValues { (id, value) -> io.github.psd2live.core.MotionLoops.unwrapped(value, clip.curve(id)?.let { MotionClips.sample(it, time) }, definitions[id]) }
            val (next, references) = MotionKeyEdits.autoKeyMultiple(clip, changes, current.values.mapKeys { it.key.raw }, time)
            keyed += references
            if (next != clip) {
                val materialized = if (id.startsWith("preset:") && document.rigEdits.motionClips.none { it.id == clip.id })
                    next.copy(id = MotionClips.newId(document.rigEdits.motionClips, aids.clips.keys)) else next
                val clips = MotionCatalog.entries(document.rigEdits).filterNot { it.id == materialized.id } + materialized
                WorkspaceSkeletonMotionEdits.validated(clips, io.github.psd2live.core.MotionLoops.keyLimits(puppet.parameters),
                    reserveBuiltinNames = document.rigEdits.modifierStack == null)
                document = document.copy(rigEdits = MotionCatalog.replacing(document.rigEdits, clips))
            }
        }
        val auxiliary = if (pose == current) before.auxiliary else JsonObject(before.auxiliary + ("posesByWorkspace" to
            JsonObject(before.auxiliary["posesByWorkspace"]?.jsonObject.orEmpty() + (workspaceId to PreviewSessions.encode(pose)))))
        val model = if (document == before.document) before.model else previews.build(document, before.model)
        context[WorkspaceJobContext]?.progress(0.9f, "Committing authored pose")
        currentCoroutineContext().ensureActive()
        val result = runtime.commitPrepared(projectId, state, "Author pose", author, document, model, auxiliary = auxiliary) { captured, next, prepared ->
            project(captured, next, prepared, pose)
        }
        val output = buildJsonObject {
            put("project_id", result.capture.projectId); put("state", result.capture.state); put("history_node_id", result.capture.historyHead)
            put("values", PreviewSessions.encode(pose).getValue("values")); put("locked", PreviewSessions.encode(pose).getValue("locked"))
            putJsonArray("keyed") { keyed.forEach { key -> add(buildJsonObject { put("parameter", key.parameterId); put("time", key.time) }) } }
        }
        currentCoroutineContext()[WorkspacePoseJobExecution]?.committed(output)
        return output
    }
}

internal class WorkspacePoseJobExecution(private val completion: WorkspaceJobCompletion) :
    kotlin.coroutines.AbstractCoroutineContextElement(Key) {
    companion object Key : kotlin.coroutines.CoroutineContext.Key<WorkspacePoseJobExecution>
    fun committed(result: JsonObject) = completion.committed(WorkspaceOperationOutput(result))
}
