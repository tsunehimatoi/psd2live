package io.github.psd2live.core.sim

import io.github.psd2live.core.PhysicsSourceType
import io.github.psd2live.core.PrimitiveSkins
import io.github.psd2live.core.RigEditOverlay
import kotlinx.serialization.json.*
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel
import kotlin.math.hypot
import kotlin.math.max

/** Simulation edits on the overlay, shared by the GUI and MCP. */
object SimAuthoring {
    /**
     * [arguments] laid over the simulation with their `id`, or a new one, which starts from the
     * [RigSimEdit.defaultInputs] [model] has unless `inputs` is given; validated against [model].
     */
    fun put(overlay: RigEditOverlay, model: PuppetModel, arguments: JsonObject): RigEditOverlay {
        val id = requireNotNull(arguments["id"]?.jsonPrimitive?.contentOrNull) { "id is required" }
        val existing = overlay.simEdits.firstOrNull { it.id == id }
        val edit = existing?.patched(arguments) ?: RigSimEdit.fromJson(arguments).let { created ->
            if ("inputs" in arguments) created else created.copy(inputs = RigSimEdit.defaultInputs(model.parameters.mapTo(HashSet()) { it.id.raw }, created.kind))
        }
        return put(overlay, model, edit)
    }

    /**
     * Inputs kept from before whose parameter is gone drop out; a new one must exist. Training ranges go
     * with their inputs. An output ID may not be a parameter the model has for something else.
     */
    fun put(overlay: RigEditOverlay, model: PuppetModel, edit: RigSimEdit): RigEditOverlay {
        val parameters = model.parameters.mapTo(HashSet()) { it.id.raw }
        val previous = overlay.simEdits.firstOrNull { it.id == edit.id }
        val before = previous?.inputs.orEmpty().toSet()
        val inputs = edit.inputs.filter { it.parameter in parameters || it !in before }
        val kept = edit.withInputs(inputs)
        validate(model, kept)
        // The model at hand carries the previous bake's parameters; those are this body's to rename.
        val own = previous?.outputParameters.orEmpty().toSet() + kept.bake?.parameters.orEmpty()
        val others = overlay.simEdits.filter { it.id != kept.id }.flatMap { it.outputParameters }.toSet()
        for ((baked, output) in kept.outputs) {
            val id = output.id ?: continue
            require(id !in others && (id !in parameters || id in own)) { "Parameter $id already exists; pick another output ID for $baked" }
            require(kept.inputs.none { it.parameter == id }) { "$id is an input of ${kept.id}; an output cannot drive its own input" }
        }
        val index = overlay.simEdits.indexOfFirst { it.id == kept.id }
        val next = if (index < 0) overlay.simEdits + kept else overlay.simEdits.toMutableList().also { it[index] = kept }
        return rebased(overlay, overlay.copy(simEdits = next))
    }

    /** Removes simulation [id], with the panel's overrides of its pendulums. */
    fun remove(overlay: RigEditOverlay, id: String): RigEditOverlay {
        require(overlay.simEdits.any { it.id == id }) { "Simulation not found: $id" }
        return rebased(overlay, overlay.copy(simEdits = overlay.simEdits.filterNot { it.id == id }))
    }

    /**
     * [after] with the physics panel's overrides of simulation pendulums carried over from [before]: where a
     * pendulum is now written differently - baked again, or its outputs renamed or re-ranged - each override
     * keeps only what the user changed and takes the rest from the new pendulum ([merged]); one the same as
     * the new pendulum is dropped, and a pendulum that is no longer written takes its overrides, switch and
     * place in the order with it.
     */
    internal fun rebased(before: RigEditOverlay, after: RigEditOverlay): RigEditOverlay {
        val old = before.simEdits.flatMap(SimGenerator::writtenPendulums).associateBy { it.id }
        val new = after.simEdits.flatMap(SimGenerator::writtenPendulums).associateBy { it.id }
        if (old == new) return after
        val gone = old.keys - new.keys
        val edits = after.physicsEdits.mapNotNull { mine ->
            val base = old[mine.id] ?: return@mapNotNull mine
            val theirs = new[mine.id] ?: return@mapNotNull null
            if (base == theirs) mine else merged(base, mine, theirs).takeIf { it != theirs }
        }
        return io.github.psd2live.core.PhysicsAuthoring.forget(after.copy(physicsEdits = edits), gone)
    }

    /**
     * A three-way merge of an override: what [mine] changed from [base], laid over [theirs]. Each field the
     * user left alone follows [theirs]; a changed one stays as set, except an output's scale, which keeps
     * its ratio to the new one. Outputs pair with [base]'s by parameter and with [theirs] by place, so they
     * follow a renamed parameter; outputs the user added stay, and ones the user removed stay removed.
     */
    internal fun merged(base: io.github.psd2live.core.RigPhysicsEdit, mine: io.github.psd2live.core.RigPhysicsEdit,
                        theirs: io.github.psd2live.core.RigPhysicsEdit): io.github.psd2live.core.RigPhysicsEdit {
        fun <T> pick(b: T, m: T, t: T) = if (m == b) t else m
        val segments = when {
            mine.segments == base.segments -> theirs.segments
            mine.segments.size == base.segments.size && base.segments.size == theirs.segments.size -> theirs.segments.indices.map { i ->
                val b = base.segments[i]; val m = mine.segments[i]; val t = theirs.segments[i]
                io.github.psd2live.core.PhysicsSegment(pick(b.length, m.length, t.length), pick(b.mobility, m.mobility, t.mobility),
                    pick(b.delay, m.delay, t.delay), pick(b.acceleration, m.acceleration, t.acceleration))
            }
            else -> mine.segments
        }
        val outputs = if (mine.outputs == base.outputs) theirs.outputs else {
            val paired = mine.outputs.mapNotNull { m ->
                val i = base.outputs.indexOfFirst { it.parameter == m.parameter }
                if (i < 0) return@mapNotNull m
                val b = base.outputs[i]
                val t = theirs.outputs.getOrNull(i) ?: return@mapNotNull null
                t.copy(vertex = pick(b.vertex, m.vertex, t.vertex),
                    scale = when {
                        m.scale == b.scale -> t.scale
                        b.scale == 0f -> m.scale
                        else -> t.scale * m.scale / b.scale
                    },
                    weight = pick(b.weight, m.weight, t.weight), type = pick(b.type, m.type, t.type), reflect = pick(b.reflect, m.reflect, t.reflect))
            }
            paired + theirs.outputs.drop(base.outputs.size)
        }.map { it.copy(vertex = it.vertex.coerceAtMost(segments.size)) }.distinctBy { it.parameter }
        return mine.copy(name = theirs.name, inputs = pick(base.inputs, mine.inputs, theirs.inputs), outputs = outputs,
            segments = segments, normalization = pick(base.normalization, mine.normalization, theirs.normalization))
    }

    /** A fresh ID for a simulation on [targets]: `sim` and the first mesh's name, numbered from 2 when taken. */
    fun nextId(overlay: RigEditOverlay, targets: List<String>): String {
        val used = overlay.simEdits.mapTo(HashSet()) { it.id }
        val stem = "sim" + (targets.firstOrNull()?.removePrefix("ArtMesh")?.ifBlank { null } ?: "")
        if (stem != "sim" && stem !in used) return stem
        return generateSequence(2) { it + 1 }.map { "$stem$it" }.first { it !in used }
    }

    /** Throws when [edit] cannot run on [model]: missing meshes, parameters or glue keys. */
    fun validate(model: PuppetModel, edit: RigSimEdit) {
        val parameters = model.parameters.mapTo(HashSet()) { it.id.raw }
        edit.inputs.forEach { require(it.parameter in parameters) { "Parameter ${it.parameter} does not exist" } }
        val glues = model.glues.mapTo(HashSet(), ::glueKey)
        edit.glueRoles.keys.forEach { require(it in glues) { "No glue $it; glue keys are meshA|meshB as inspect lists them" } }
        SimScene.build(model, edit)
    }

    /**
     * Runs [edit] on [model] and reports how it behaves: settles at the default pose, then for each input
     * holds it at its maximum for [hold] seconds and releases it, and finally applies [wind] (px/s², world)
     * if given. Read-only; nothing is baked.
     */
    fun report(model: PuppetModel, edit: RigSimEdit, hold: Float = 0.5f, release: Float = 1.5f, wind: Pair<Float, Float>? = null,
               progress: (Float) -> Unit = {}, cancelled: () -> Boolean = { false }): JsonObject {
        require(hold.isFinite() && hold in 0f..20f && release.isFinite() && release in 0f..20f) { "Hold and release must be within 0..20 seconds" }
        fun check() { if (cancelled()) throw java.util.concurrent.CancellationException("Simulation sampling cancelled") }
        check(); progress(0f)
        val scene = SimScene.build(model, edit)
        val fps = 60
        val dt = 1f / fps
        val residual = scene.calibrate(model, progress = { progress(0.2f * it) }, cancelled = cancelled)
        val phaseFrames = (hold * fps).toInt().coerceAtLeast(1) + (release * fps).toInt().coerceAtLeast(1)
        val total = ((edit.inputs.size + if (wind == null) 0 else 1) * phaseFrames + fps).coerceAtLeast(1)
        var completed = 0
        fun frame() { check(); progress(0.2f + 0.8f * ++completed / total) }
        val rest = scene.state.positions()
        var worstStretch = 0f
        fun runPhase(pose: Map<ParameterId, Float>, seconds: Float): Pair<Float, Float> {
            var peak = 0f
            repeat((seconds * fps).toInt().coerceAtLeast(1)) {
                check()
                scene.drive(model, pose, dt)
                worstStretch = max(worstStretch, scene.solver.maxStretch())
                // Motion relative to the rig: the goal is where the rig alone would put each vertex.
                for (i in 0 until scene.state.count) peak = max(peak, hypot(scene.state.x[i] - scene.state.goalX[i], scene.state.y[i] - scene.state.goalY[i]))
                frame()
            }
            var last = 0f
            for (i in 0 until scene.state.count) last = max(last, hypot(scene.state.x[i] - scene.state.goalX[i], scene.state.y[i] - scene.state.goalY[i]))
            return peak to last
        }
        val phases = buildJsonArray {
            for (input in edit.inputs) {
                val parameter = model.parameters.first { it.id.raw == input.parameter }
                val high = mapOf(parameter.id to if (input.reflect) parameter.min else parameter.max)
                scene.reset(model, emptyMap())
                val (heldPeak, _) = runPhase(high, hold)
                val (releasePeak, final) = runPhase(emptyMap(), release)
                add(buildJsonObject {
                    put("input", input.parameter); put("type", if (input.type == PhysicsSourceType.X) "x" else "angle")
                    put("peak_px", round(max(heldPeak, releasePeak))); put("after_release_px", round(final))
                })
            }
            if (wind != null) {
                scene.reset(model, emptyMap())
                scene.solver.settings = scene.solver.settings.copy(windX = wind.first, windY = wind.second)
                val (peak, _) = runPhase(emptyMap(), hold)
                scene.solver.settings = scene.solver.settings.copy(windX = 0f, windY = 0f)
                val (after, final) = runPhase(emptyMap(), release)
                add(buildJsonObject { put("input", "wind"); put("peak_px", round(max(peak, after))); put("after_release_px", round(final)) })
            }
        }
        var restDrift = 0f
        scene.reset(model, emptyMap())
        repeat(fps) { check(); scene.drive(model, emptyMap(), dt); frame() }
        check()
        val settled = scene.state.positions()
        for (i in 0 until scene.state.count) restDrift = max(restDrift, hypot(settled[i * 2] - rest[i * 2], settled[i * 2 + 1] - rest[i * 2 + 1]))
        return buildJsonObject {
            put("id", edit.id); put("particles", scene.state.count)
            put("pinned", (0 until scene.state.count).count { scene.solver.pinWeight[it] > 0f })
            put("calibration_residual_px", round(residual)); put("rest_drift_px", round(restDrift))
            put("max_stretch_percent", round(worstStretch * 100f))
            put("phases", phases)
            if (scene.notes.isNotEmpty()) putJsonArray("notes") { scene.notes.forEach { add(it) } }
        }
    }

    /**
     * The authored rig ([RigEditOverlay.replayAuthored]) of an overlay, before its swings and simulations write their
     * keyforms: what a simulation bakes on once the overlay finishes it. A preview gives it from its own authored rig
     * ([io.github.psd2live.core.RigPreviewModel.authoredPuppet]) without generating the base.
     */
    fun interface AuthoredRigs {
        fun of(overlay: RigEditOverlay): PuppetModel

        companion object {
            /** Each overlay replayed on [base], whose skeleton skinned [skins]. */
            fun replayedOn(base: PuppetModel, skins: PrimitiveSkins = PrimitiveSkins.None) = AuthoredRigs { it.replayAuthored(base, skins).model }
        }
    }

    /**
     * The rig [overlay] finishes without simulation [id]'s bake, the rig a bake of it must read: baking over its own
     * keys would count them twice.
     */
    fun unbakedModel(overlay: RigEditOverlay, authored: AuthoredRigs, id: String): PuppetModel {
        val unbaked = overlay.copy(simEdits = overlay.simEdits.map { if (it.id == id) it.copy(bake = null) else it })
        return unbaked.finish(authored.of(unbaked)).model
    }

    /** [unbakedModel] with the overlay replayed on [base]. */
    fun unbakedModel(overlay: RigEditOverlay, base: PuppetModel, id: String, skins: PrimitiveSkins = PrimitiveSkins.None): PuppetModel =
        unbakedModel(overlay, AuthoredRigs.replayedOn(base, skins), id)

    /** [bake] with the overlay replayed on [base]. */
    fun bake(
        overlay: RigEditOverlay,
        base: PuppetModel,
        id: String,
        progress: (Float) -> Unit = {},
        cancelled: () -> Boolean = { false },
        skins: PrimitiveSkins = PrimitiveSkins.None,
    ): SimBakeResult = bake(overlay, AuthoredRigs.replayedOn(base, skins), id, progress, cancelled)

    /**
     * Bakes simulation [id] of [overlay] on its finished [authored] rig, its pendulum fitted at [overlay]'s physics
     * rate; takes a second or so, so call it off the frame thread.
     */
    fun bake(
        overlay: RigEditOverlay,
        authored: AuthoredRigs,
        id: String,
        progress: (Float) -> Unit = {},
        cancelled: () -> Boolean = { false },
    ): SimBakeResult {
        val edit = requireNotNull(overlay.simEdits.firstOrNull { it.id == id }) { "Simulation not found: $id" }
        val model = unbakedModel(overlay, authored, id)
        return SimBaker.bake(model, edit,
            SimBaker.Options(physicsFps = overlay.physicsFps, progress = progress, cancelled = cancelled, trainingMotions = trainingMotions(overlay, edit, model)))
    }

    /**
     * [edit]'s training motions ([RigSimEdit.trainingClips]) found in [overlay] and sampled at the bake's rate; a
     * motion no longer there is left out (its name stays listed until the user removes it).
     */
    fun trainingMotions(overlay: RigEditOverlay, edit: RigSimEdit, model: PuppetModel): List<SimBaker.TrainingMotion> {
        val defaults = model.parameters.associate { it.id.raw to it.default }
        return edit.trainingClips.mapNotNull { (name, weight) ->
            val clip = SimMotions.resolve(overlay, name) ?: return@mapNotNull null
            SimBaker.TrainingMotion(name, SimMotions.sample(clip, SimBaker.Options().fps) { defaults[it] ?: 0f }, weight)
        }
    }

    /**
     * [overlay] with simulation [id] baked again when it bakes on its own ([autoBake], or else
     * [RigSimEdit.autoBake]) and its bake is missing or stale; the old bake's pendulum is where the fit starts, which is quicker. When the bake
     * fails or is [cancelled] the old bake stays, stale, and the second value says why.
     */
    fun rebaked(
        overlay: RigEditOverlay,
        base: PuppetModel,
        id: String,
        progress: (Float) -> Unit = {},
        cancelled: () -> Boolean = { false },
        autoBake: Boolean? = null,
        skins: PrimitiveSkins = PrimitiveSkins.None,
    ): Pair<RigEditOverlay, String?> = rebaked(overlay, AuthoredRigs.replayedOn(base, skins), id, progress, cancelled, autoBake)

    /** [rebaked] on the overlay's finished [authored] rig. */
    fun rebaked(
        overlay: RigEditOverlay,
        authored: AuthoredRigs,
        id: String,
        progress: (Float) -> Unit = {},
        cancelled: () -> Boolean = { false },
        autoBake: Boolean? = null,
    ): Pair<RigEditOverlay, String?> {
        val edit = overlay.simEdits.firstOrNull { it.id == id } ?: return overlay to null
        if (!(autoBake ?: edit.autoBake) || !edit.enabled) return overlay to null
        val model = unbakedModel(overlay, authored, id)
        if (edit.bake != null && edit.bake.fingerprint == SimBake.fingerprint(model, edit)) return overlay to null
        return try {
            withBake(overlay, id, SimBaker.bake(model, edit, SimBaker.Options(physicsFps = overlay.physicsFps, previous = edit.bake?.physics, previousExtra = edit.bake?.extraPhysics.orEmpty(),
                progress = progress, cancelled = cancelled, trainingMotions = trainingMotions(overlay, edit, model)))) to null
        } catch (failure: java.util.concurrent.CancellationException) {
            overlay to "Bake cancelled"
        } catch (failure: IllegalArgumentException) {
            overlay to (failure.message ?: "Bake failed")
        }
    }

    /** [overlay] with [bake] as simulation [id]'s bake, the panel's overrides of its pendulums carried over; null clears it, and them. */
    fun withBake(overlay: RigEditOverlay, id: String, bake: SimBakeResult?): RigEditOverlay {
        require(overlay.simEdits.any { it.id == id }) { "Simulation not found: $id" }
        // Replay must use the same six-significant-digit offsets as the v1 archive. Keeping the solver's
        // extra precision only in memory changes keyforms (and rendered edge pixels) after reopening.
        val persisted = bake?.let { SimBakeResult.fromJson(it.toJson()) }
        return rebased(overlay, overlay.copy(simEdits = overlay.simEdits.map { if (it.id == id) it.copy(bake = persisted) else it }))
    }

    /** The simulated vertices of every target of [scene], world space, keyed by mesh. */
    fun positions(scene: SimScene): Map<DrawableId, FloatArray> = scene.offsets.keys.associateWith { requireNotNull(scene.positions(it)) }

    private fun round(value: Float) = kotlin.math.round(value * 100f) / 100f
}
