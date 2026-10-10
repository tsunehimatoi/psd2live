package io.github.psd2live.application

import io.github.psd2live.core.*
import kotlinx.serialization.json.*
import org.umamo.runtime.model.ParameterId

/** Process-owned playback clocks and tracking inputs; evaluated frames are never durable poses. */
internal class WorkspacePlaybackSessions(private val runtime: WorkspaceRuntime<RigPreviewModel>) {
    /** The [sequences] value of each workspace's session. */
    private val sequenceByWorkspace = HashMap<String, Long>()

    private data class Session(val projectId: String, val generation: String, val clipId: String? = null, val time: Float = 0f,
                               val playing: Boolean = false, val tracking: Boolean = false,
                               val smoothTracking: Boolean = false, val trackingClock: PreviewAnimationClock = PreviewAnimationClock(),
                               val pointer: Pair<Float, Float>? = null, val clockNanos: Long = System.nanoTime(),
                               val animation: Boolean = false, val animationClock: PreviewAnimationClock = PreviewAnimationClock(),
                               val motionId: String? = null, val motionTime: Float = 0f, val activeMotion: String? = null,
                               val physics: WorkspacePreviewPhysics = WorkspacePreviewPhysics())
    private val sessions = mutableMapOf<String, Session>()

    @Synchronized fun physics(projectId: String, state: String, workspaceId: String, request: JsonObject): JsonObject {
        val capture = runtime.capture()
        if (capture.state != state) throw WorkspaceConflict(state, capture.state)
        require(capture.projectId == projectId) { "Operation targets another project" }
        require(workspaceId.isNotBlank()) { "Workspace ID must be nonempty" }
        val generation = state.substringBeforeLast(':')
        val session = sessions[workspaceId]?.takeIf { it.projectId == projectId && it.generation == generation } ?: Session(projectId, generation)
        val pose = PreviewSessions.read(capture.model.rig.puppet.parameters, capture.auxiliary, workspaceId)
        val values = request["values"]?.jsonObject?.mapValues { it.value.jsonPrimitive.float }
        val inputs = if (values == null) pose.values else PreviewSessions.edit(capture.model.rig.puppet.parameters, pose, values, emptyMap()).values
        val dt = request["dt"]?.jsonPrimitive?.float ?: (1f / 60f)
        require(dt.isFinite() && dt in 0f..1f) { "Physics delta must lie within 0..1 seconds" }
        if (request["reset"]?.jsonPrimitive?.boolean == true) session.physics.reset()
        val output = session.physics.step(capture.model, inputs, pose.locked, dt, request["playing"]?.jsonPrimitive?.boolean ?: false)
        sessions[workspaceId] = session
        return buildJsonObject {
            put("project_id", capture.projectId); put("state", capture.state); put("workspace_id", workspaceId)
            put("settled", session.physics.settled)
            putJsonObject("outputs") { output.forEach { (id, value) -> put(id.raw, value) } }
            putJsonObject("values") { boundedPreviewPose(inputs + output, capture.model.rig.puppet.parameters).forEach { (id, value) -> put(id.raw, value) } }
        }
    }

    @Synchronized fun configure(projectId: String, state: String, workspaceId: String, request: JsonObject,
                                initialTracking: Boolean = false, initialSmoothTracking: Boolean = false): JsonObject {
        val capture = runtime.capture()
        if (capture.state != state) throw WorkspaceConflict(state, capture.state)
        require(capture.projectId == projectId) { "Operation targets another project" }
        require(workspaceId.isNotBlank()) { "Workspace ID must be nonempty" }
        val generation = capture.state.substringBeforeLast(':')
        val current = sessions[workspaceId]?.takeIf { it.projectId == projectId && it.generation == generation }
            ?: Session(projectId, generation, tracking = initialTracking, smoothTracking = initialSmoothTracking)
        val mode = request.getValue("mode").jsonPrimitive.content
        val next = when (mode) {
            "start" -> {
                val id = request.getValue("clip_id").jsonPrimitive.content
                val clip = requireNotNull(clip(capture, id)) { "Motion not found: $id" }
                val time = request["time"]?.jsonPrimitive?.float ?: 0f
                require(time.isFinite() && time in 0f..clip.duration) { "Time outside motion" }
                current.copy(clipId = id, time = time, playing = true, animation = false, motionId = null, activeMotion = null)
            }
            "seek" -> {
                val id = request["clip_id"]?.jsonPrimitive?.content ?: requireNotNull(current.clipId) { "Select a motion first" }
                val clip = requireNotNull(clip(capture, id)) { "Motion not found: $id" }
                val time = request.getValue("time").jsonPrimitive.float
                require(time.isFinite() && time in 0f..clip.duration) { "Time outside motion" }
                current.copy(clipId = id, time = time, playing = false, animation = false, motionId = null, activeMotion = null)
            }
            "pause" -> current.copy(playing = false)
            "stop" -> current.copy(time = 0f, playing = false)
            "animation" -> current.copy(animation = request.getValue("enabled").jsonPrimitive.boolean, clipId = null, playing = false)
            "trigger" -> {
                val name = request.getValue("name").jsonPrimitive.content
                if (name.equals("Idle", true)) current.copy(animation = true, playing = false, clipId = null,
                    motionId = null, activeMotion = null, animationClock = PreviewAnimationClock())
                else {
                    val id = motionId(capture, name)
                    require(clip(capture, id) != null) { "Motion not found: $name" }
                    current.copy(animation = true, playing = false, clipId = null, motionId = id, motionTime = 0f, activeMotion = name.lowercase())
                }
            }
            "stop_motion" -> if (request["name"] == null || request["name"]?.jsonPrimitive?.content?.equals(current.activeMotion, true) == true)
                current.copy(motionId = null, motionTime = 0f, activeMotion = null) else current
            "reset" -> Session(projectId, generation)
            "tracking" -> {
                val enabled = request.getValue("enabled").jsonPrimitive.boolean
                val point = request["pointer"]?.jsonArray?.let {
                    require(it.size == 2) { "Pointer must contain two coordinates" }
                    it[0].jsonPrimitive.float to it[1].jsonPrimitive.float
                } ?: current.pointer
                require(point == null || (point.first.isFinite() && point.second.isFinite() && point.first in -1f..1f && point.second in -1f..1f)) { "Pointer must be normalized to -1..1" }
                val smooth = request["smooth"]?.jsonPrimitive?.boolean ?: current.smoothTracking
                current.copy(tracking = enabled, pointer = point.takeIf { enabled }, smoothTracking = smooth,
                    trackingClock = if (enabled != current.tracking || smooth != current.smoothTracking) PreviewAnimationClock() else current.trackingClock)
            }
            else -> error("Unknown playback mode: $mode")
        }
        sessions[workspaceId] = next.copy(clockNanos = System.nanoTime())
        sequenceByWorkspace[workspaceId] = sequences.incrementAndGet()
        return result(capture, workspaceId, next)
    }

    /**
     * Moves the tracked pointer without composing a frame; the next clock frame evaluates it. A pointer
     * move per mouse event must not pay for a pose sample and a GUI projection each time.
     */
    @Synchronized fun pointer(workspaceId: String, pointer: Pair<Float, Float>?) {
        require(pointer == null || (pointer.first.isFinite() && pointer.second.isFinite() &&
            pointer.first in -1f..1f && pointer.second in -1f..1f)) { "Pointer must be normalized to -1..1" }
        val capture = runtime.capture()
        val generation = capture.state.substringBeforeLast(':')
        val session = sessions[workspaceId]?.takeIf { it.projectId == capture.projectId && it.generation == generation } ?: return
        if (session.tracking && session.pointer != pointer) sessions[workspaceId] = session.copy(pointer = pointer)
    }

    /**
     * An authored change stops the clocks and restarts them from rest at the new pose. The tracking switch
     * and pointer stay, and an open motion stays posed at its playhead, so the views keep showing its curves.
     */
    @Synchronized fun restart(projectId: String, state: String, workspaceId: String, initialTracking: Boolean = false,
                              initialSmoothTracking: Boolean = false): JsonObject {
        val capture = runtime.capture()
        if (capture.state != state) throw WorkspaceConflict(state, capture.state)
        require(capture.projectId == projectId) { "Operation targets another project" }
        val generation = state.substringBeforeLast(':')
        val current = sessions[workspaceId]?.takeIf { it.projectId == projectId && it.generation == generation }
            ?: Session(projectId, generation, tracking = initialTracking, smoothTracking = initialSmoothTracking)
        val clip = current.clipId?.let { clip(capture, it) }
        val next = Session(projectId, generation, clipId = current.clipId.takeIf { clip != null }, time = clip?.let { current.time.coerceAtMost(it.duration) } ?: 0f,
            tracking = current.tracking, pointer = current.pointer, smoothTracking = current.smoothTracking)
        sessions[workspaceId] = next
        sequenceByWorkspace[workspaceId] = sequences.incrementAndGet()
        return result(capture, workspaceId, next)
    }

    @Synchronized fun frame(workspaceId: String, dt: Float? = null): JsonObject {
        require(dt == null || (dt.isFinite() && dt in 0f..1f)) { "Frame delta must lie within 0..1 seconds" }
        val capture = runtime.capture()
        val generation = capture.state.substringBeforeLast(':')
        var session = sessions[workspaceId]?.takeIf { it.projectId == capture.projectId && it.generation == generation } ?: Session(capture.projectId, generation)
        val now = System.nanoTime()
        val delta = dt ?: ((now - session.clockNanos).coerceAtLeast(0L) / 1_000_000_000.0).toFloat()
        val clip = session.clipId?.let { clip(capture, it) }
        if (clip == null && session.clipId != null) session = session.copy(clipId = null, playing = false, time = 0f)
        if (session.playing && clip != null) {
            val nextTime = session.time + delta
            session = if (nextTime >= clip.duration) {
                if (clip.loop) session.copy(time = nextTime % clip.duration)
                else session.copy(time = clip.duration, playing = false)
            } else session.copy(time = nextTime)
        }
        val oneShot = session.motionId?.let { clip(capture, it) }
        if (session.motionId != null && oneShot == null) session = session.copy(motionId = null, activeMotion = null, motionTime = 0f)
        if (session.animation) {
            val time = session.motionTime + delta
            session = if (oneShot != null && time > oneShot.duration) session.copy(motionId = null, activeMotion = null, motionTime = 0f)
                else session.copy(motionTime = time)
        }
        session = session.copy(animationClock = session.animationClock.advance(delta, session.animation, capture.model.config, null),
            trackingClock = session.trackingClock.advanceTracking(delta,
                session.pointer.takeIf { session.tracking && session.smoothTracking }))
        sessions[workspaceId] = session.copy(clockNanos = now)
        return result(capture, workspaceId, session)
    }

    private fun result(capture: WorkspaceCapture<RigPreviewModel>, workspaceId: String, session: Session) = buildJsonObject {
        val pose = PreviewSessions.read(capture.model.rig.puppet.parameters, capture.auxiliary, workspaceId)
        val clip = session.clipId?.let { clip(capture, it) }
        val base = if (session.animation && !capture.model.config.meshOnly) {
            val oneShot = session.motionId?.let { clip(capture, it) }?.let { MotionClips.sampleAll(it, session.motionTime.toDouble(), false) }.orEmpty()
            val generated = session.animationClock.sample(capture.model, true, false, oneShot)
            boundedPreviewPose(pose.values + generated.filterKeys { it !in pose.locked }, capture.model.rig.puppet.parameters)
        } else sample(capture.model, pose, clip, session.time)
        val values = pointerPreviewPose(base, session.pointer?.first ?: 0f, session.pointer?.second ?: 0f,
            capture.model.rig.puppet.parameters, session.tracking && session.pointer != null, pose.locked,
            session.trackingClock.takeIf { session.smoothTracking })
        put("project_id", capture.projectId); put("state", capture.state); put("workspace_id", workspaceId)
        session.clipId?.let { put("clip_id", it) }
        put("time", session.time); put("playing", session.playing); put("tracking", session.tracking)
        put("animation", session.animation); put("elapsed", session.animationClock.elapsed)
        put("smooth_tracking", session.smoothTracking)
        put("sequence", sequenceByWorkspace[workspaceId] ?: 0L)
        session.activeMotion?.let { put("active_motion", it) }
        put("pointer_active", session.pointer != null)
        putJsonObject("values") { values.forEach { (id, value) -> put(id.raw, value) } }
    }

    private fun clip(capture: WorkspaceCapture<RigPreviewModel>, id: String): MotionClip? {
        val document = capture.document
        return document.rigEdits.motionClips.firstOrNull { it.id == id && (it.builtin == null || enabled(capture, it.builtin)) }
            ?: id.takeIf { it.startsWith("preset:") }?.removePrefix("preset:")?.let { name ->
                if (name !in MotionClips.BUILTIN_NAMES || !enabled(capture, name)) null
                else MotionClips.overrideOf(document.rigEdits.motionClips, name) ?: MotionPresets.clip(id, name,
                    document.rigEdits.skeleton, document.rigEdits.motionPresets[name] ?: MotionPresetSettings())
            }
    }

    private fun enabled(capture: WorkspaceCapture<RigPreviewModel>, name: String): Boolean {
        val config = capture.model.config
        val enabled = if (MotionClips.isSkeletonPreset(name)) config.motionSkeleton else config.motionBasic && when (name) {
            "Idle" -> config.motionIdle; "Blink" -> config.motionBlink; "Nod" -> config.motionNod; "Shake" -> config.motionShake; else -> true
        }
        return enabled && capture.document.rigEdits.motionPresets[name]?.let { !it.deleted && !it.disabled } != false
    }

    private fun motionId(capture: WorkspaceCapture<RigPreviewModel>, name: String): String {
        val clips = capture.document.rigEdits.motionClips
        return OneShotMotionPlayer.clipOf(name, capture.document.rigEdits.skeleton, clips, capture.document.rigEdits.motionPresets)
            ?.takeIf { resolved -> clips.any { it.id == resolved.id } }?.id
            ?: clips.firstOrNull { it.id == name }?.id
            ?: MotionClips.BUILTIN_NAMES.firstOrNull { it.equals(name, true) }?.let { "preset:$it" }
            ?: throw IllegalArgumentException("Motion not found: $name")
    }

    companion object {
        /**
         * Rises with every change a command or restart makes to a session, across every session in the process. A
         * result carries the value its session had, so whoever shows them can drop one that arrives after a newer one.
         */
        private val sequences = java.util.concurrent.atomic.AtomicLong()

        /** GUI scrubbing and process sessions use the same bounded, lock-aware frame composition. */
        fun sample(model: RigPreviewModel, pose: WorkspacePose, clip: MotionClip?, time: Float,
                   tracking: Boolean = false, pointer: Pair<Float, Float>? = null): Map<ParameterId, Float> {
            require(time.isFinite() && time >= 0f)
            val motion = clip?.let { MotionClips.sampleAll(it, time.toDouble(), loop = false) }.orEmpty()
                .filterKeys { it !in pose.locked }
            return pointerPreviewPose(pose.values + motion, pointer?.first ?: 0f, pointer?.second ?: 0f,
                model.rig.puppet.parameters, tracking && pointer != null, pose.locked)
        }
    }
}
