package io.github.psd2live.core

import kotlinx.serialization.json.*

/**
 * The overlay changes behind the physics panel and the `physics` tools; the panel and MCP call the same
 * functions, so a group edited in one reads back identically in the other.
 */
object PhysicsAuthoring {
    /**
     * [overlay] with [edit] as the user's version of its group. Editing a generated group back to its
     * generated values drops the replacement. Another enabled user group may not already drive an output.
     */
    fun put(overlay: RigEditOverlay, edit: RigPhysicsEdit, generated: RigPhysicsEdit? = null): RigEditOverlay {
        AppliedPhysics.edit(overlay, edit.id, setting = edit)?.let { return it }
        val others = overlay.physicsEdits.filter { it.id != edit.id && it.id !in overlay.disabledPhysicsIds }
        edit.outputParameters.firstOrNull { p -> others.any { p in it.outputParameters } }?.let { p ->
            throw IllegalArgumentException("Output $p is already driven by ${others.first { p in it.outputParameters }.id}")
        }
        val index = overlay.physicsEdits.indexOfFirst { it.id == edit.id }
        val next = when {
            edit == generated -> overlay.physicsEdits.filterNot { it.id == edit.id }
            index < 0 -> overlay.physicsEdits + edit
            else -> overlay.physicsEdits.toMutableList().also { it[index] = edit }
        }
        return overlay.copy(physicsEdits = next)
    }

    /** Turns a non-preset group on or off; the presets are switched by their own settings. */
    fun setEnabled(overlay: RigEditOverlay, id: String, enabled: Boolean): RigEditOverlay {
        AppliedPhysics.edit(overlay, id, enabled = enabled)?.let { return it }
        require(overlay.importedCmo3 != null || id !in PhysicsGenerator.presetIds) { "Presets are switched by their settings" }
        return overlay.copy(disabledPhysicsIds = if (enabled) overlay.disabledPhysicsIds - id else overlay.disabledPhysicsIds + id)
    }

    /** Deletes a user group, or returns a replaced generated group to its generated values. */
    fun remove(overlay: RigEditOverlay, id: String, generated: Boolean): RigEditOverlay {
        AppliedPhysics.edit(overlay, id, delete = true)?.let { return it }
        require(overlay.physicsEdits.any { it.id == id }) { "No user physics group $id" }
        return overlay.copy(physicsEdits = overlay.physicsEdits.filterNot { it.id == id },
            disabledPhysicsIds = if (generated) overlay.disabledPhysicsIds else overlay.disabledPhysicsIds - id,
            physicsOrder = if (generated) overlay.physicsOrder else overlay.physicsOrder - id)
    }

    /**
     * Groups evaluated in the order of [ids]; the others follow in their current order. Cubism runs groups
     * in sequence, so a group reads what an earlier one wrote in the same step.
     */
    fun order(overlay: RigEditOverlay, groups: List<PhysicsGroup>, ids: List<String>): RigEditOverlay {
        ids.firstOrNull { id -> groups.none { it.id == id } }?.let { throw IllegalArgumentException("Physics group not found: $it") }
        require(ids.distinct().size == ids.size) { "A group appears once in the order" }
        return overlay.copy(physicsOrder = ids + groups.map { it.id }.filterNot { it in ids })
    }

    /** [id] moved [by] places in the evaluation order. */
    fun move(overlay: RigEditOverlay, groups: List<PhysicsGroup>, id: String, by: Int): RigEditOverlay {
        val ids = groups.map { it.id }.toMutableList()
        val from = ids.indexOf(id).also { require(it >= 0) { "Physics group not found: $id" } }
        ids.add((from + by).coerceIn(0, ids.size - 1), ids.removeAt(from))
        return order(overlay, groups, ids)
    }

    fun setFps(overlay: RigEditOverlay, fps: Int): RigEditOverlay {
        require(RigEditOverlay.validFps(fps)) { "FPS must be ${RigEditOverlay.UNLIMITED_FPS} (unlimited) or within ${RigEditOverlay.PHYSICS_FPS_RANGE}" }
        return overlay.copy(physicsFps = fps)
    }

    /**
     * [setting] with each output's scale changed so its swing just reaches the parameter's end: [peaks]
     * maps an output index to how far it reached at the current scale (see [PhysicsEngine.Strand.peakFraction]).
     * Outputs that barely moved keep their scale.
     */
    fun fitScales(setting: RigPhysicsEdit, peaks: Map<Int, Float>, target: Float = 1f): RigPhysicsEdit =
        setting.copy(outputs = setting.outputs.mapIndexed { k, o ->
            val peak = peaks[k]?.takeIf { it > MIN_PEAK } ?: return@mapIndexed o
            o.copy(scale = kotlin.math.round(o.scale * target / peak * 1000f) / 1000f)
        })

    private const val MIN_PEAK = 0.01f

    /** What a physics3.json import did. */
    data class Imported(
        val overlay: RigEditOverlay,
        /** The imported groups, in file order; the IDs of the file, suffixed where the file repeats one. */
        val ids: List<String>,
        /** User groups switched off because an imported group drives their outputs. */
        val disabled: List<String>,
        /** Per imported group, parameters the model does not have. */
        val missing: Map<String, List<String>>,
        /** The file's `Fps` when it declares one this project can take. */
        val fps: Int?,
    )

    /**
     * The groups of a physics3.json as user groups: a group with an existing ID replaces it (a generated
     * one stays replaced until reset), and the imported groups run after the current ones in file order.
     * Other user groups on the imported outputs are switched off. The file's `Fps` becomes the project's.
     */
    fun import(overlay: RigEditOverlay, groups: List<PhysicsGroup>, text: String, available: Set<String>): Imported {
        val file = Physics3Json.read(text)
        require(file.settings.isNotEmpty()) { "The file has no physics settings" }
        val used = HashSet<String>()
        val settings = file.settings.map { s ->
            val id = generateSequence(1) { it + 1 }.map { if (it == 1) s.id else "${s.id}_$it" }.first { used.add(it) }
            s.copy(id = id)
        }
        val ids = settings.map { it.id }
        val outputs = settings.flatMapTo(HashSet()) { it.outputParameters }
        val kept = overlay.physicsEdits.filterNot { it.id in ids }
        val applied = groups.filter { AppliedPhysics.owner(overlay, it.id) != null }
        val disabled = (kept.filter { it.id !in overlay.disabledPhysicsIds && it.outputParameters.any(outputs::contains) }.map { it.id } +
            applied.filter { it.id !in ids && it.active && it.setting.outputParameters.any(outputs::contains) }.map { it.id }).distinct()
        val appliedIds = applied.mapTo(HashSet()) { it.id }
        val fps = file.fps?.let { kotlin.math.round(it).toInt() }?.takeIf { it in RigEditOverlay.PHYSICS_FPS_RANGE }
        var next = overlay.copy(
            physicsEdits = kept + settings.filterNot { it.id in appliedIds },
            disabledPhysicsIds = overlay.disabledPhysicsIds - ids.toSet() + disabled.filterNot { it in appliedIds },
            physicsOrder = groups.map { it.id }.filterNot { it in ids } + ids,
            physicsFps = fps ?: overlay.physicsFps,
        )
        settings.filter { it.id in appliedIds }.forEach { setting ->
            next = requireNotNull(AppliedPhysics.edit(next, setting.id, setting = setting, enabled = true))
        }
        disabled.filter { it in appliedIds }.forEach { id ->
            next = requireNotNull(AppliedPhysics.edit(next, id, enabled = false))
        }
        val missing = settings.associate { it.id to it.parameters.filter { p -> p !in available }.distinct() }.filterValues { it.isNotEmpty() }
        return Imported(next, ids, disabled, missing, fps)
    }

    /** Drops every edit of groups that no longer exist. */
    internal fun forget(overlay: RigEditOverlay, ids: Set<String>): RigEditOverlay =
        if (ids.isEmpty()) overlay
        else overlay.copy(physicsEdits = overlay.physicsEdits.filterNot { it.id in ids }, disabledPhysicsIds = overlay.disabledPhysicsIds - ids,
            physicsOrder = overlay.physicsOrder - ids)

    /** A fresh `PhysicsCustom<N>` that no group uses. */
    fun freshId(groups: List<PhysicsGroup>, overlay: RigEditOverlay): String {
        val used = groups.mapTo(HashSet()) { it.id } + overlay.physicsEdits.map { it.id }
        return generateSequence(1) { it + 1 }.map { "PhysicsCustom$it" }.first { it !in used }
    }

    /** A new hanging pendulum: head and body sway in, one hair-like segment, no output yet. */
    fun template(id: String, name: String, available: Set<String>, segments: Int = 1) = RigPhysicsEdit(
        id, name, PhysicsGenerator.headAndBodyInputs(available), emptyList(),
        List(segments) { PhysicsSegment(10f / segments, 0.9f, 0.9f, 1.2f) }, PhysicsNormalization(angleMin = -10f, angleMax = 10f),
    )

    /** Parameters that already have a writer other than [except]; a group may not take them as outputs. */
    fun drivenParameters(groups: List<PhysicsGroup>, except: String? = null): Map<String, String> =
        groups.filter { it.id != except && it.active }.flatMap { g -> g.setting.outputParameters.map { it to g.id } }.toMap()

    /** Everything a `physics_put` changes, resolved against the current [groups]. */
    data class Request(val edit: RigPhysicsEdit?, val generated: RigPhysicsEdit?, val enabled: Boolean?)

    private val controlKeys = setOf("id", "enabled", "state", "task_id", "mode")

    /**
     * Reads `physics_put` arguments laid over the group with that ID (or a new template), so an agent
     * sends only what changes. `enabled` alone switches a group without replacing it.
     */
    fun request(groups: List<PhysicsGroup>, arguments: JsonObject, available: Set<String>): Request {
        val id = requireNotNull(arguments["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }) { "id is required" }
        val group = groups.firstOrNull { it.id == id }
        val enabled = arguments["enabled"]?.jsonPrimitive?.booleanOrNull
        val fields = JsonObject(arguments.filterKeys { it !in controlKeys })
        if (fields.isEmpty()) {
            require(enabled != null) { "Give the fields to change, or enabled" }
            requireNotNull(group) { "Physics group not found: $id" }
            return Request(null, group.generated, enabled)
        }
        val base = group?.setting ?: template(id, id, available)
        val edit = base.patched(fields)
        edit.parameters.firstOrNull { it !in available }?.let { throw IllegalArgumentException("Parameter $it does not exist; create it first") }
        // New groups and partially edited presets remain authorable, with catalog diagnostics keeping
        // them inactive until both sides are configured. Invalid references and feedback still fail.
        PhysicsCatalog.issueOf(edit, available)?.takeUnless {
            it.code == PhysicsIssue.Code.NO_INPUT || it.code == PhysicsIssue.Code.NO_OUTPUT
        }?.let { throw IllegalArgumentException(it.message) }
        return Request(edit, group?.generated, enabled)
    }
}
