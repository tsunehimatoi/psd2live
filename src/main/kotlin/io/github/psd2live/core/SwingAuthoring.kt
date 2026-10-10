package io.github.psd2live.core

import kotlinx.serialization.json.*
import org.umamo.runtime.model.*
import kotlin.math.abs

/** The overlay changes behind the swing dialog and the swing tools; one call is one history commit. */
internal object SwingAuthoring {
    /**
     * Adds or replaces [edit] in [overlay]. Mesh targets are wrapped in a tight Warp per shared parent,
     * recorded in the journal so the wrap replays before the swing. [model] is the current rig.
     * With [estimatePhysics] every pendulum is sized from the first target.
     */
    fun put(overlay: RigEditOverlay, model: PuppetModel, edit: RigSwingEdit, estimatePhysics: Boolean = false): RigEditOverlay {
        val others = overlay.swingEdits.filterNot { it.id == edit.id }
        require(others.none { other -> other.parameterIds.any { it in edit.parameterIds } }) { "Another swing already drives these parameters" }
        val (wrapped, commands, targets) = wrap(model, edit)
        var next = edit.copy(targets = targets)
        if (estimatePhysics) next = sized(wrapped, next)
        val issues = SwingGenerator.issues(wrapped, next)
        require(issues.isEmpty()) { issues.joinToString("; ") }
        val index = overlay.swingEdits.indexOfFirst { it.id == edit.id }
        val swings = if (index < 0) overlay.swingEdits + next else overlay.swingEdits.toMutableList().also { it[index] = next }
        // A pendulum renamed by a change of directions leaves its old ID's edits behind.
        val stale = overlay.swingEdits.getOrNull(index)?.let(::physicsIds).orEmpty() - physicsIds(next)
        return PhysicsAuthoring.forget(overlay.copy(authoringJournal = overlay.authoringJournal + commands, swingEdits = swings), stale)
    }

    /** The physics group IDs [swing] generates. */
    fun physicsIds(swing: RigSwingEdit): Set<String> = swing.motions.filter { it.physics != null }
        .mapTo(HashSet()) { PhysicsGenerator.swingPhysicsId(swing, it.kind) }

    /** [edit] moving in [kinds]: a kept direction keeps its settings, a new one starts from the preset. */
    fun withKinds(model: PuppetModel, overlay: RigEditOverlay, edit: RigSwingEdit, kinds: List<SwingKind>): RigSwingEdit {
        require(kinds.isNotEmpty())
        val motions = kinds.distinct().sortedBy { it.ordinal }.map { kind ->
            edit.motions.firstOrNull { it.kind == kind } ?: run {
                // Placeholders keep the parameters distinct until they are named.
                val template = edit.motions.first()
                SwingMotion(kind, template.parameterIds.indices.map { "#$kind/$it" }, SwingPresets.shape(edit.preset, kind),
                    template.physics?.copy(outputScale = SwingPresets.physics(edit.preset, kind, null).outputScale))
            }
        }
        return named(model, overlay, edit, edit.copy(motions = motions))
    }

    /** [edit] with [segments] parameters in motion [motion]. */
    fun withSegments(model: PuppetModel, overlay: RigEditOverlay, edit: RigSwingEdit, motion: Int, segments: Int): RigSwingEdit {
        val motions = edit.motions.mapIndexed { m, entry ->
            if (m != motion) entry else entry.copy(parameterIds = (0 until segments).map { "${entry.parameterIds.first()}#$it" })
        }
        return named(model, overlay, edit, edit.copy(motions = motions))
    }

    /**
     * [next] with every parameter named for its place: `ParamSwing<stem>[_X|_Y][_<segment>]`. [old]'s own
     * parameters may be reused; any other taken ID moves the whole set to a numbered stem.
     */
    fun named(model: PuppetModel, overlay: RigEditOverlay, old: RigSwingEdit, next: RigSwingEdit): RigSwingEdit {
        val used = (model.parameters.map { it.id.raw } + overlay.swingEdits.filter { it.id != old.id }.flatMap { it.parameterIds }).toSet() - old.parameterIds.toSet()
        val base = asciiStem(next.id)
        var n = 1
        while (true) {
            val candidate = canonical(next, "ParamSwing$base" + if (n == 1) "" else "$n")
            if (candidate.parameterIds.none { it in used }) return candidate
            n++
        }
    }

    /** [edit] with its parameters named `<stem>[_X|_Y][_<segment>]`, leaving out what does not vary. */
    private fun canonical(edit: RigSwingEdit, stem: String) = edit.copy(motions = edit.motions.map { m ->
        m.copy(parameterIds = (0 until m.segments).map { j ->
            val parts = listOfNotNull(
                if (edit.motions.size > 1) if (m.kind == SwingKind.LATERAL) "X" else "Y" else null,
                if (m.segments > 1) "${j + 1}" else null,
            )
            stem + parts.joinToString("") { "_$it" }
        })
    })

    /** [edit] with every pendulum sized from the first target of [model], a rig where the targets are Warps. */
    fun sized(model: PuppetModel, edit: RigSwingEdit): RigSwingEdit {
        val length = SwingGenerator.measure(model, edit.targets.first(), edit.fulcrum)?.second
        return edit.withPhysics { kind, physics -> physics?.let { SwingPresets.physics(edit.preset, kind, length) } }
    }

    /** [edit] with every pendulum sized from its first target, measured on [model] with the mesh targets wrapped. */
    fun resized(model: PuppetModel, edit: RigSwingEdit): RigSwingEdit {
        val (wrapped, _, targets) = wrap(SwingGenerator.strip(model, edit), edit)
        return sized(wrapped, edit.copy(targets = targets)).copy(targets = edit.targets)
    }

    /** A swing request from the agent tools; [estimatePhysics] when no pendulum was given. */
    data class Request(val edit: RigSwingEdit, val estimatePhysics: Boolean)

    /**
     * Reads `swing_put` arguments: either `motions`, one entry per direction with its own fields, or the flat
     * fields of a single direction. Missing parameters are named `ParamSwing<id>[_X|_Y][_<segment>]`.
     */
    fun request(arguments: JsonObject): Request {
        val id = requireNotNull(arguments["id"]?.jsonPrimitive?.contentOrNull) { "id is required" }
        val preset = arguments["preset"]?.jsonPrimitive?.contentOrNull?.let { SwingPreset.valueOf(it.uppercase()) } ?: SwingPreset.HAIR
        val enabled = arguments["physics_enabled"]?.jsonPrimitive?.booleanOrNull ?: true
        val motionObjects = arguments["motions"]?.jsonArray?.map { it.jsonObject }
            ?: listOf(JsonObject(arguments.filterKeys { it in setOf("kind", "segments", "parameters", "magnitude", "lift", "softness", "zoom", "parallel", "flip", "physics") }))
        require(motionObjects.isNotEmpty()) { "Give at least one motion" }
        var givenPhysics = false
        var placeholders = false
        val motions = motionObjects.map { m ->
            val kind = SwingKind.valueOf(requireNotNull(m["kind"]?.jsonPrimitive?.contentOrNull) { "kind is required" }.uppercase())
            val given = m["parameters"]?.jsonArray?.map { it.jsonPrimitive.content }
            val segments = m["segments"]?.jsonPrimitive?.int ?: given?.size ?: 1
            if (given == null) placeholders = true
            val physics = m["physics"]?.takeIf { it is JsonObject }?.jsonObject
            if (physics != null) givenPhysics = true
            SwingMotion(kind, given ?: (1..segments).map { "#$kind/$it" }, SwingShape.fromJson(m, SwingPresets.shape(preset, kind)),
                if (!enabled) null else physics?.let(SwingPhysics::fromJson) ?: SwingPresets.physics(preset, kind, null))
        }
        val edit = RigSwingEdit(
            id = id,
            name = arguments["name"]?.jsonPrimitive?.contentOrNull ?: id,
            targets = requireNotNull(arguments["targets"]?.jsonArray) { "targets is required" }.map { it.jsonPrimitive.content },
            motions = motions,
            fulcrum = arguments["fulcrum"]?.jsonPrimitive?.contentOrNull?.let { SwingFulcrum.valueOf(it.uppercase()) } ?: SwingFulcrum.AUTO,
            preset = preset,
            tilt = arguments["tilt"]?.jsonPrimitive?.floatOrNull ?: 0f,
            offsetAlong = arguments["offset_along"]?.jsonPrimitive?.floatOrNull ?: 0f,
            offsetAcross = arguments["offset_across"]?.jsonPrimitive?.floatOrNull ?: 0f,
        )
        return Request(if (placeholders) canonical(edit, "ParamSwing${asciiStem(id)}") else edit, enabled && !givenPhysics)
    }

    /**
     * Writes [id]'s current forms into the journal as ordinary keys, so they can be edited by hand. The
     * swing stays, baked, to keep its pendulums; without any it is removed. [authored] is the rig before the swings,
     * whose parameters the bake leaves alone.
     */
    fun bake(overlay: RigEditOverlay, model: PuppetModel, id: String, authored: PuppetModel? = null): RigEditOverlay {
        val swing = requireNotNull(overlay.swingEdits.firstOrNull { it.id == id }) { "Swing not found: $id" }
        require(!swing.baked) { "Swing is already baked: $id" }
        // Journal keys replay before every swing, so the other swings' axes are left out of the bake.
        val foreign = overlay.swingEdits.filterNot { it.id == id }.flatMap { it.parameterIds }.toSet()
        val commands = mutableListOf<JsonObject>()
        for (target in swing.targets) {
            val warp = model.deformers.firstOrNull { it.id.raw == target } as? Deformer.Warp ?: continue
            var grid = warp.geometryGrid ?: continue
            for (p in model.parameters.filter { it.id.raw in foreign }) grid = collapse(grid, p)
            val axes = grid.axes.filter { it.parameterId.raw in swing.parameterIds }
            for (axis in axes) commands += buildJsonObject {
                put("op", "parameter_keys"); put("target", "warp:$target"); put("parameter", axis.parameterId.raw)
                put("action", "add"); put("track", "geometry")
                putJsonArray("values") { axis.keys.forEach { add(it) } }
            }
            for (cell in grid.cells) commands += buildJsonObject {
                put("op", "set"); put("target", "warp:$target")
                putJsonObject("key") { grid.axes.forEachIndexed { a, axis -> put(axis.parameterId.raw, axis.keys[cell.coordinate[a]]) } }
                putJsonObject("geometry") { putJsonArray("controlPoints") { cell.form.controlPoints.forEach { add(it) } } }
            }
        }
        // Swing parameters are created after the journal replays; the baked keys need them first, so the journal
        // creates them. A journal that replays from a checkpoint never reads the static parameter edits.
        val existing = authored?.parameters?.mapTo(HashSet()) { it.id.raw }.orEmpty() + overlay.parameterEdits.map { it.id }
        val created = model.parameters.filter { it.id.raw in swing.parameterIds && it.id.raw !in existing }.map { p ->
            buildJsonObject {
                put("op", "structure")
                putJsonArray("edits") { add(buildJsonObject {
                    put("action", "create"); put("kind", "parameter"); put("id", p.id.raw); put("name", p.name)
                    put("min", p.min); put("max", p.max); put("default", p.default)
                    put("parameter_kind", p.kind.name); put("repeat", p.repeat)
                }) }
            }
        }
        val swings = if (!swing.hasPhysics) overlay.swingEdits.filterNot { it.id == id }
            else overlay.swingEdits.map { if (it.id == id) it.copy(baked = true) else it }
        return overlay.copy(authoringJournal = overlay.authoringJournal + created + commands, swingEdits = swings)
    }

    /** Removes [id]; an unbaked swing takes its generated axes and parameters with it. */
    fun remove(overlay: RigEditOverlay, id: String): RigEditOverlay {
        val swing = requireNotNull(overlay.swingEdits.firstOrNull { it.id == id }) { "Swing not found: $id" }
        return PhysicsAuthoring.forget(overlay.copy(swingEdits = overlay.swingEdits.filterNot { it.id == id }), physicsIds(swing))
    }

    /**
     * [overlay] after its journal deletes the Warps [deleted]: a swing keeps its other targets, and one left without
     * any is removed with its pendulums, as deleting an object in Cubism takes its keyforms with it. Without this the
     * swing would stay, moving nothing, its parameters and pendulums still in the model.
     */
    fun withoutTargets(overlay: RigEditOverlay, deleted: Set<String>): RigEditOverlay {
        if (deleted.isEmpty() || overlay.swingEdits.none { swing -> swing.targets.any { it in deleted } }) return overlay
        return overlay.swingEdits.fold(overlay) { current, swing ->
            val kept = swing.targets.filterNot { it in deleted }
            when {
                kept.size == swing.targets.size -> current
                kept.isEmpty() -> remove(current, swing.id)
                else -> current.copy(swingEdits = current.swingEdits.map { if (it.id == swing.id) swing.copy(targets = kept) else it })
            }
        }
    }

    /** The deformers [journal]'s `structure` entries delete. */
    fun deletedDeformers(journal: List<JsonObject>): Set<String> = journal
        .filter { it["op"]?.jsonPrimitive?.contentOrNull == "structure" }
        .flatMap { (it["edits"] as? JsonArray).orEmpty() }
        .mapNotNull { it as? JsonObject }
        .filter { it["action"]?.jsonPrimitive?.contentOrNull == "delete" && it["kind"]?.jsonPrimitive?.contentOrNull in setOf("warp", "rotation") }
        .mapNotNullTo(HashSet()) { it["id"]?.jsonPrimitive?.contentOrNull }

    /** A fresh swing ID and parameter IDs that collide with nothing in [model] or [overlay]. */
    fun freshIds(model: PuppetModel, overlay: RigEditOverlay, base: String, segments: Int): Pair<String, List<String>> {
        val stem = asciiStem(base)
        val usedIds = overlay.swingEdits.map { it.id }.toSet()
        val usedParameters = model.parameters.map { it.id.raw }.toSet() + overlay.swingEdits.flatMap { it.parameterIds }
        var n = 1
        while (true) {
            val suffix = if (n == 1) "" else "$n"
            val id = "$stem$suffix"
            val parameters = (1..segments).map { k -> "ParamSwing$stem$suffix" + if (segments == 1) "" else "_$k" }
            if (id !in usedIds && parameters.none { it in usedParameters }) return id to parameters
            n++
        }
    }

    /** Cubism IDs stay ASCII even when the art is named in another script. */
    fun asciiStem(text: String): String = text.filter { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' }.take(24).ifEmpty { "Swing" }

    data class Wrapped(val model: PuppetModel, val commands: List<JsonObject>, val targets: List<String>)

    /** [model] with [edit]'s mesh targets wrapped, the wrap commands, and the targets as Warps. */
    fun wrap(model: PuppetModel, edit: RigSwingEdit): Wrapped {
        val deformerTargets = mutableListOf<String>()
        val meshes = mutableListOf<Drawable>()
        for (target in edit.targets) {
            when (val deformer = model.deformers.firstOrNull { it.id.raw == target }) {
                is Deformer.Warp -> deformerTargets += target
                is Deformer.Rotation -> error("Swing needs a Warp or a mesh, not rotation $target")
                null -> meshes += requireNotNull(model.drawables.firstOrNull { it.id.raw == target }) { "Swing target not found: $target" }
            }
        }
        var current = model
        val commands = mutableListOf<JsonObject>()
        val created = mutableListOf<String>()
        for ((_, group) in meshes.groupBy { it.parentDeformerId }) {
            val points = group.flatMap { d ->
                val mesh = requireNotNull(d.mesh) { "Mesh ${d.id.raw} has no geometry" }
                listOf(mesh.positions) + d.geometryGrid?.cells.orEmpty().map { cell ->
                    FloatArray(mesh.positions.size) { mesh.positions[it] + cell.form.positionDeltas[it] }
                }
            }
            val bounds = RigGeometryTools.bounds(points.reduce { a, b -> a + b })
            val tall = bounds[3] >= bounds[2]
            var id = "Warp_Swing_${edit.id}"
            var n = 2
            while (current.deformers.any { it.id.raw == id }) id = "Warp_Swing_${edit.id}_${n++}"
            val command = buildJsonObject {
                put("op", "canvas_create_warp"); put("id", id); put("name", edit.name)
                put("rows", if (tall) 6 else 3); put("columns", if (tall) 3 else 6)
                put("add_to", "parent_of_selected")
                putJsonArray("meshes") { group.forEach { add(it.id.raw) } }
                // Explicit bounds keep the lattice tight on the art, so its top edge is the art's top.
                putJsonObject("bounds") {
                    put("x", bounds[0] - bounds[2] * 0.05f); put("y", bounds[1] - bounds[3] * 0.05f)
                    put("w", bounds[2] * 1.1f); put("h", bounds[3] * 1.1f)
                }
            }
            current = RigAuthoringJournal.apply(current, command)
            commands += command
            created += id
        }
        return Wrapped(current, commands, (deformerTargets + created).distinct())
    }

    private fun collapse(grid: KeyformGrid<WarpLatticeForm>, p: Parameter): KeyformGrid<WarpLatticeForm> {
        val axis = grid.axes.indexOfFirst { it.parameterId == p.id }
        if (axis < 0) return grid
        val keep = grid.axes[axis].keys.indices.minBy { abs(grid.axes[axis].keys[it] - p.default) }
        return KeyformGrid(grid.axes.filterIndexed { i, _ -> i != axis }, grid.cells.filter { it.coordinate[axis] == keep }.map { cell ->
            KeyformCell(IntArray(cell.coordinate.size - 1) { if (it < axis) cell.coordinate[it] else cell.coordinate[it + 1] }, cell.form)
        })
    }
}
