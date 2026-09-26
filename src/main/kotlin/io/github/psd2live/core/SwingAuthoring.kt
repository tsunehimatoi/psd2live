package io.github.psd2live.core

import kotlinx.serialization.json.*
import org.umamo.runtime.model.*
import kotlin.math.abs

/** The overlay changes behind the swing dialog and the swing tools; one call is one history commit. */
internal object SwingAuthoring {
    /**
     * Adds or replaces [edit] in [overlay]. Mesh targets are wrapped in a tight Warp per shared parent,
     * recorded in the journal so the wrap replays before the swing. [model] is the current rig.
     * With [estimatePhysics] the pendulum defaults are sized from the first target.
     */
    fun put(overlay: RigEditOverlay, model: PuppetModel, edit: RigSwingEdit, estimatePhysics: Boolean = false): RigEditOverlay {
        val others = overlay.swingEdits.filterNot { it.id == edit.id }
        require(others.none { other -> other.parameterIds.any { it in edit.parameterIds } }) { "Another swing already drives these parameters" }
        val (wrapped, commands, targets) = wrap(model, edit)
        var next = edit.copy(targets = targets)
        if (estimatePhysics && next.physics != null) {
            val length = SwingGenerator.measure(wrapped, targets.first(), next.fulcrum)?.second
            next = next.copy(physics = SwingPresets.physics(next.preset, next.kind, length))
        }
        val issues = SwingGenerator.issues(wrapped, next)
        require(issues.isEmpty()) { issues.joinToString("; ") }
        val index = overlay.swingEdits.indexOfFirst { it.id == edit.id }
        val swings = if (index < 0) overlay.swingEdits + next else overlay.swingEdits.toMutableList().also { it[index] = next }
        return overlay.copy(authoringJournal = overlay.authoringJournal + commands, swingEdits = swings)
    }

    /**
     * Writes [id]'s current forms into the journal as ordinary keys, so they can be edited by hand. The
     * swing stays, baked, to keep its pendulum; without one it is removed.
     */
    fun bake(overlay: RigEditOverlay, model: PuppetModel, id: String): RigEditOverlay {
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
        // Swing parameters are created while the swing replays; baked keys need them before the journal.
        var next = overlay
        for (p in model.parameters.filter { it.id.raw in swing.parameterIds }) {
            if (next.parameterEdits.none { it.id == p.id.raw }) {
                next = next.upsert(RigParameterEdit(p.id.raw, p.name, p.min, p.max, p.default, p.kind, p.repeat, created = true))
            }
        }
        val swings = if (swing.physics == null) next.swingEdits.filterNot { it.id == id }
            else next.swingEdits.map { if (it.id == id) it.copy(baked = true) else it }
        return next.copy(authoringJournal = next.authoringJournal + commands, swingEdits = swings)
    }

    /** Removes [id]; an unbaked swing takes its generated axes and parameters with it. */
    fun remove(overlay: RigEditOverlay, id: String): RigEditOverlay {
        require(overlay.swingEdits.any { it.id == id }) { "Swing not found: $id" }
        return overlay.copy(swingEdits = overlay.swingEdits.filterNot { it.id == id })
    }

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

    private data class Wrapped(val model: PuppetModel, val commands: List<JsonObject>, val targets: List<String>)

    private fun wrap(model: PuppetModel, edit: RigSwingEdit): Wrapped {
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
