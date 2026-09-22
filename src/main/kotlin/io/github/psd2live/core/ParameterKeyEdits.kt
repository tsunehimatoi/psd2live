package io.github.psd2live.core

import kotlinx.serialization.json.*
import org.umamo.edit.withParameterKeys
import org.umamo.runtime.eval.EPS_KEY
import org.umamo.runtime.eval.EPS_SPAN
import org.umamo.runtime.keyform.*
import org.umamo.runtime.model.*
import kotlin.math.abs

/** A point edit addresses exactly one owner/track/axis and preserves every other axis and track. */
internal object ParameterKeyEdits {
    fun apply(model: PuppetModel, edit: JsonObject): PuppetModel {
        if (edit["target"] == null) return applyParameterPoints(model, edit)
        val ref = RigAuthoringJournal.target(edit.getValue("target").jsonPrimitive.content)
        val owner = ref.asKeyformOwner()
        val parameter = model.parameters.single { it.id.raw == edit.getValue("parameter").jsonPrimitive.content }
        require(parameter.kind == ParameterKind.NORMAL) { "Blend shape keys require blend shape authoring" }
        val action = edit.getValue("action").jsonPrimitive.content
        val values = edit.getValue("values").jsonArray.map { it.jsonPrimitive.float }
        require(values.isNotEmpty() && values.size <= 128 && values.all { it.isFinite() && it in parameter.min..parameter.max }) { "Keys must be finite and within the parameter range" }
        fun <T> change(original: KeyformGrid<T>?, neutral: T, interpolator: FormInterpolator<T>): KeyformGrid<T> {
            val existingAxis = original?.axes?.firstOrNull { it.parameterId == parameter.id }
            if (existingAxis == null) {
                require(action == "add") { "No keys on this track" }
                val keys = values.distinct().sorted()
                require(keys.size >= 2 && keys.zipWithNext().all { (a, b) -> b - a >= EPS_SPAN }) { "Start with at least two distinct keys" }
                // Seed exact requested points, replicating the original forms along the new axis.
                val base = original ?: KeyformGrid(emptyList(), listOf(KeyformCell(intArrayOf(), neutral)))
                require(base.isDense) { "Cannot bind a sparse track" }
                require(base.cells.size.toLong() * keys.size <= 1000000) { "Too many keyform cells" }
                return KeyformGrid(base.axes + KeyformAxis(parameter.id, keys.toFloatArray()),
                    keys.indices.flatMap { index -> base.cells.map { KeyformCell(it.coordinate + index, it.form) } })
            }
            var grid = requireNotNull(original)
            require(grid.isDense) { "Cannot edit a sparse track" }
            when (action) {
                "add" -> for (value in values.sorted()) {
                    if (grid.keyIndexAt(parameter.id, value) >= 0) continue
                    require(grid.cells.size.toLong() / existingAxis.keys.size * (existingAxis.keys.size + values.size) <= 1000000) { "Too many keyform cells" }
                    val inserted = grid.withKeyInserted(parameter.id, value, interpolator)
                    require(inserted !== grid) { "Key is too close to an existing key" }
                    grid = inserted
                }
                "move" -> {
                    require(values.size == 1)
                    val from = edit.getValue("from").jsonPrimitive.float
                    require(from.isFinite())
                    val index = grid.keyIndexAt(parameter.id, from)
                    require(index >= 0) { "Key no longer exists" }
                    val to = values.single()
                    val destination = grid.keyDestinationFor(parameter.id, index, to)
                    require(destination != null && destination == to) { "Keys must remain distinct" }
                    grid = grid.withKeyMoved(parameter.id, index, to)
                }
                "delete" -> {
                    require(values.size == 1 && existingAxis.keys.size > 2) { "Keep at least two keys on a track" }
                    val index = grid.keyIndexAt(parameter.id, values.single())
                    require(index >= 0) { "Key no longer exists" }
                    grid = requireNotNull(grid.withKeyRemoved(parameter.id, index))
                }
                else -> error("Unknown key edit: $action")
            }
            return grid
        }
        val track = edit.getValue("track").jsonPrimitive.content
        if (track != "geometry") {
            val channel = FormChannel.valueOf(track)
            val grids = requireNotNull(model.channelGridsOf(owner))
            val grid = requireNotNull(grids[channel]) { "Channel track not found" }
            require(grid.axes.any { it.parameterId == parameter.id }) { "Channel is not bound to this parameter" }
            val changed = change(grid, grid.cells.first().form, ChannelValueInterpolator)
            return if (changed === grid) model else model.withReplacedChannelGrids(owner, ChannelGrids(grids.gridsByChannel + (channel to changed)))
        }
        return when (owner) {
            is KeyformOwner.Drawable -> {
                val drawable = model.drawables.single { it.id == owner.id }
                val mesh = requireNotNull(drawable.mesh) { "Create a mesh before adding geometry keys" }
                val changed = change(drawable.geometryGrid, MeshDeltaForm(FloatArray(mesh.positions.size)), MeshDeltaInterpolator)
                if (changed === drawable.geometryGrid) model else model.withReplacedGeometryGrid(owner, changed)
            }
            is KeyformOwner.Deformer -> when (val deformer = model.deformers.single { it.id == owner.id }) {
                is Deformer.Warp -> {
                    val existing = requireNotNull(deformer.geometryGrid) { "Deformer has no geometry" }
                    val changed = change(existing, existing.cells.first().form, WarpLatticeInterpolator)
                    if (changed === existing) model else model.withReplacedGeometryGrid(owner, changed)
                }
                is Deformer.Rotation -> {
                    val existing = requireNotNull(deformer.geometryGrid) { "Deformer has no geometry" }
                    val changed = change(existing, existing.cells.first().form, RotationPivotInterpolator)
                    if (changed === existing) model else model.withReplacedGeometryGrid(owner, changed)
                }
            }
            else -> error("Select a mesh or deformer")
        }
    }

    /** Points live on the parameter. Moving or deleting one follows existing shapes; adding does not create them. */
    private fun applyParameterPoints(model: PuppetModel, edit: JsonObject): PuppetModel {
        val parameter = model.parameters.single { it.id.raw == edit.getValue("parameter").jsonPrimitive.content }
        require(parameter.kind == ParameterKind.NORMAL) { "Blend shape keys require blend shape authoring" }
        val action = edit.getValue("action").jsonPrimitive.content
        val values = edit.getValue("values").jsonArray.map { it.jsonPrimitive.float }
        require(values.isNotEmpty() && values.all { it.isFinite() && it in parameter.min..parameter.max }) {
            "Keys must be finite and within the parameter range"
        }
        val baseline = parameter.keys ?: model.boundPointValues(parameter.id)
        fun same(a: Float, b: Float) = abs(a - b) < EPS_KEY
        return when (action) {
            "add" -> model.withParameterKeys(parameter.id, (baseline + values).distinct().sorted())
            "move" -> {
                require(values.size == 1)
                val from = edit.getValue("from").jsonPrimitive.float
                val to = values.single()
                require(baseline.any { same(it, from) }) { "Key no longer exists" }
                require(baseline.none { !same(it, from) && same(it, to) }) { "Keys must remain distinct" }
                model.withParameterKeys(parameter.id, baseline.map { if (same(it, from)) to else it }.sorted())
                    .retargetBoundForms(parameter.id, from, to)
            }
            "delete" -> {
                require(values.size == 1)
                val value = values.single()
                require(baseline.any { same(it, value) }) { "Key no longer exists" }
                model.withParameterKeys(parameter.id, baseline.filterNot { same(it, value) })
                    .retargetBoundForms(parameter.id, value, null)
            }
            else -> error("Unknown key edit: $action")
        }
    }

    private fun PuppetModel.boundPointValues(parameterId: ParameterId): List<Float> {
        val values = sortedSetOf<Float>()
        fun take(grid: KeyformGrid<*>?) {
            grid?.axes?.firstOrNull { it.parameterId == parameterId }?.keys?.forEach { values.add(it) }
        }
        for (drawable in drawables) {
            take(drawable.geometryGrid)
            drawable.channelGrids.gridsByChannel.values.forEach(::take)
        }
        for (deformer in deformers) {
            when (deformer) {
                is Deformer.Warp -> {
                    take(deformer.geometryGrid)
                    deformer.channelGrids.gridsByChannel.values.forEach(::take)
                }
                is Deformer.Rotation -> {
                    take(deformer.geometryGrid)
                    deformer.channelGrids.gridsByChannel.values.forEach(::take)
                }
            }
        }
        for (part in parts) part.channelGrids.gridsByChannel.values.forEach(::take)
        for (glue in glues) glue.channelGrids.gridsByChannel.values.forEach(::take)
        return values.toList()
    }

    private fun <T> KeyformGrid<T>.retargetPoint(parameterId: ParameterId, from: Float, to: Float?, delete: Boolean): KeyformGrid<T>? {
        val index = keyIndexAt(parameterId, from)
        if (index < 0) return this
        if (delete) return withKeyRemoved(parameterId, index)
        val destination = keyDestinationFor(parameterId, index, requireNotNull(to))
        require(destination != null && destination == to) { "Keys must remain distinct" }
        return withKeyMoved(parameterId, index, to)
    }

    private fun PuppetModel.retargetBoundForms(parameterId: ParameterId, from: Float, to: Float?): PuppetModel {
        val delete = to == null
        var current = this
        for (drawable in drawables) {
            val geometry = drawable.geometryGrid
            if (geometry != null) {
                val next = geometry.retargetPoint(parameterId, from, to, delete)
                if (next !== geometry) current = current.withReplacedGeometryGrid(KeyformOwner.Drawable(drawable.id), next)
            }
            current = current.retargetChannels(KeyformOwner.Drawable(drawable.id), drawable.channelGrids, parameterId, from, to, delete)
        }
        for (deformer in deformers) {
            when (deformer) {
                is Deformer.Warp -> {
                    val geometry = deformer.geometryGrid
                    if (geometry != null) {
                        val next = geometry.retargetPoint(parameterId, from, to, delete)
                        if (next !== geometry) current = current.withReplacedGeometryGrid(KeyformOwner.Deformer(deformer.id), next)
                    }
                    current = current.retargetChannels(KeyformOwner.Deformer(deformer.id), deformer.channelGrids, parameterId, from, to, delete)
                }
                is Deformer.Rotation -> {
                    val geometry = deformer.geometryGrid
                    if (geometry != null) {
                        val next = geometry.retargetPoint(parameterId, from, to, delete)
                        if (next !== geometry) current = current.withReplacedGeometryGrid(KeyformOwner.Deformer(deformer.id), next)
                    }
                    current = current.retargetChannels(KeyformOwner.Deformer(deformer.id), deformer.channelGrids, parameterId, from, to, delete)
                }
            }
        }
        for (part in parts) {
            current = current.retargetChannels(KeyformOwner.Part(part.id), part.channelGrids, parameterId, from, to, delete)
        }
        for (glue in glues) {
            current = current.retargetChannels(KeyformOwner.Glue(glue.meshA, glue.meshB), glue.channelGrids, parameterId, from, to, delete)
        }
        return current
    }

    private fun PuppetModel.retargetChannels(
        owner: KeyformOwner,
        grids: ChannelGrids,
        parameterId: ParameterId,
        from: Float,
        to: Float?,
        delete: Boolean,
    ): PuppetModel {
        var changed = false
        val next = buildMap {
            for ((channel, grid) in grids.gridsByChannel) {
                val updated = grid.retargetPoint(parameterId, from, to, delete)
                if (updated !== grid) changed = true
                if (updated != null) put(channel, updated)
            }
        }
        return if (!changed) this else withReplacedChannelGrids(owner, ChannelGrids(next))
    }
}
