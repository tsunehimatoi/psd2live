package io.github.psd2live.algorithms.binding


import org.umamo.edit.VertexSource
import org.umamo.render.eval.drawableSpaceMapping
import org.umamo.runtime.eval.*
import org.umamo.runtime.model.*

/** Mesh corrections stay local; automatic and authored ancestor interpolation remains in the hierarchy. */
object GeometryResidual {
    fun geometry(current: PuppetModel, previous: PuppetModel, desired: PuppetModel, id: DrawableId,
                 previousSources: List<VertexSource>, desiredSources: List<VertexSource>, checkpoint: () -> Unit): KeyformGrid<MeshDeltaForm>? {
        val actual = current.drawables.single { it.id == id }
        val old = previous.drawables.single { it.id == id }; val next = desired.drawables.single { it.id == id }
        val mesh = requireNotNull(actual.mesh)
        val live = current.parameters.associate { it.id to it.default }
        val oldBranch = branch(previous, old)
        val nextBranch = branch(desired, next)
        val axes = unionAxes((actual.geometryGrid?.axes.orEmpty() + geometryAxes(old, oldBranch) + geometryAxes(next, nextBranch))
            .filter { it.parameterId in live })
        val oldProjection = previous.copy(drawables = listOf(old), deformers = oldBranch.asReversed())
        val nextProjection = desired.copy(drawables = listOf(next), deformers = nextBranch.asReversed())
        val oldDefaults = previous.parameters.associate { it.id to it.default }
        val nextDefaults = desired.parameters.associate { it.id to it.default }
        val mapping = requireNotNull(drawableSpaceMapping(current, emptyMap(), id))
        val indices = (0 until mesh.vertexCount).toSet()
        val coordinates = coordinates(axes, mesh.positions.size)
        val cells = coordinates.map { coordinate ->
            checkpoint()
            val pose = live + axes.indices.associate { axes[it].parameterId to axes[it].keys[coordinate[it]] }
            val before = transfer(world(oldProjection, old, pose, oldDefaults), previousSources)
            val after = transfer(world(nextProjection, next, pose, nextDefaults), desiredSources)
            val authored = local(actual, pose, live)
            val oldLocal = mapping.worldToLocalLinearized(before, mesh.positions, before, indices)
            val newLocal = mapping.worldToLocalLinearized(after, mesh.positions, after, indices)
            val reconstructed = mapping.localToWorld(newLocal)
            require(reconstructed.indices.all { kotlin.math.abs(reconstructed[it] - after[it]) <= 0.05f }) {
                "Generation migration cannot invert the authored neutral frame"
            }
            val local = FloatArray(authored.size) { authored[it] - oldLocal[it] + newLocal[it] }
            KeyformCell(coordinate, MeshDeltaForm(FloatArray(local.size) { local[it] - mesh.positions[it] }))
        }
        if (axes.isEmpty() && cells.single().form.positionDeltas.all { it == 0f }) return null
        return KeyformGrid(axes, cells)
    }

    fun channels(current: PuppetModel, actual: Drawable, previous: Drawable, desired: Drawable,
                 checkpoint: () -> Unit): ChannelGrids =
        io.github.psd2live.algorithms.binding.ChannelResidual.channels(current, actual, previous, desired, checkpoint)

    /**
     * What the user changed on drawable [id]'s geometry: per cell, the [authored] local positions minus the [generated]
     * drawable's world positions brought into the authored local frame, over the union of the authored grid's axes and
     * the generated drawable's own and ancestor axes (parameters live in [authored]). [sources] moves the residual
     * onto another vertex inventory (one entry per target vertex, [VertexSource.FromOld] or
     * [VertexSource.BarycentricOf] into this drawable); null keeps this drawable's vertices. Null when nothing differs.
     * Adding the result to a generated grid over the same frame gives back the authored shape.
     */
    fun geometryDelta(authored: PuppetModel, generated: PuppetModel, id: DrawableId, sources: List<VertexSource>? = null,
                      checkpoint: () -> Unit = {}): KeyformGrid<MeshDeltaForm>? {
        val actual = authored.drawables.single { it.id == id }
        val base = generated.drawables.single { it.id == id }
        val mesh = requireNotNull(actual.mesh)
        require(base.mesh?.vertexCount == mesh.vertexCount) { "Generation residual needs the same vertices on both drawables" }
        val live = authored.parameters.associate { it.id to it.default }
        val branch = branch(generated, base)
        val axes = unionAxes((actual.geometryGrid?.axes.orEmpty() + geometryAxes(base, branch)).filter { it.parameterId in live })
        val projection = generated.copy(drawables = listOf(base), deformers = branch.asReversed())
        val defaults = generated.parameters.associate { it.id to it.default }
        val mapping = requireNotNull(drawableSpaceMapping(authored, emptyMap(), id))
        val indices = (0 until mesh.vertexCount).toSet()
        val count = sources?.size ?: mesh.vertexCount
        var changed = false
        val cells = coordinates(axes, count * 2).map { coordinate ->
            checkpoint()
            val pose = live + axes.indices.associate { axes[it].parameterId to axes[it].keys[coordinate[it]] }
            val before = world(projection, base, pose, defaults)
            val generatedLocal = mapping.worldToLocalLinearized(before, mesh.positions, before, indices)
            val own = local(actual, pose, live)
            val delta = FloatArray(own.size) { own[it] - generatedLocal[it] }
            val moved = if (sources == null) delta else transfer(delta, sources)
            if (moved.any { kotlin.math.abs(it) > 1e-6f }) changed = true
            KeyformCell(coordinate, MeshDeltaForm(moved))
        }
        return if (changed) KeyformGrid(axes, cells) else null
    }

    /**
     * What the user changed on a drawable's channels: per channel and cell, [actual] minus [generated] (scalars and
     * colours as differences; flags as `true` where the authored flag differs from the generated one, applied as an
     * exclusive or). Axes are the union of both drawables' channel axes that are parameters of [current]. Adding the
     * result to a generated drawable's channels over the same axes gives back the authored values.
     */
    fun channelsDelta(current: PuppetModel, actual: Drawable, generated: Drawable, checkpoint: () -> Unit = {}): ChannelGrids {
        val live = current.parameters.associateBy { it.id }
        val channelIds = actual.channelGrids.gridsByChannel.keys + generated.channelGrids.gridsByChannel.keys
        return ChannelGrids(channelIds.associateWith { channel ->
            val axes = unionAxes((actual.channelGrids[channel]?.axes.orEmpty() + generated.channelGrids[channel]?.axes.orEmpty())
                .filter { it.parameterId in live })
            val cells = coordinates(axes, 4).map { coordinate ->
                checkpoint()
                val pose = axes.indices.associate { axes[it].parameterId to axes[it].keys[coordinate[it]] }
                fun parameter(id: ParameterId) = pose[id] ?: live[id]?.default ?: 0f
                fun scalar(d: Drawable) = d.channelGrids.scalarAt(channel,
                    if (channel == FormChannel.DRAW_ORDER) d.drawOrder else d.opacity, paramValue = ::parameter)
                fun color(d: Drawable) = d.channelGrids.colorAt(channel,
                    if (channel == FormChannel.MULTIPLY_COLOR) d.multiplyColor else d.screenColor, paramValue = ::parameter)
                val value = when (channel.valueKind) {
                    ChannelValueKind.SCALAR -> ChannelValue.Scalar(scalar(actual) - scalar(generated))
                    ChannelValueKind.COLOR -> {
                        val a = color(actual); val b = color(generated)
                        ChannelValue.Color(ColorRgb(a.red - b.red, a.green - b.green, a.blue - b.blue))
                    }
                    ChannelValueKind.FLAG -> ChannelValue.Flag(actual.channelGrids.flagAt(channel, false, paramValue = ::parameter) !=
                        generated.channelGrids.flagAt(channel, false, paramValue = ::parameter))
                }
                KeyformCell(coordinate, value)
            }
            KeyformGrid(axes, cells)
        })
    }

    /** All current knot values survive; generated near-duplicates use the existing knot. */
    private fun unionAxes(input: List<KeyformAxis>): List<KeyformAxis> = input.groupBy { it.parameterId }.map { (id, axes) ->
        val values = mutableListOf<Float>()
        axes.forEach { axis -> axis.keys.forEach { value -> if (values.none { kotlin.math.abs(it - value) < EPS_KEY }) values += value } }
        KeyformAxis(id, values.sorted().toFloatArray())
    }

    private fun coordinates(axes: List<KeyformAxis>, components: Int): List<IntArray> {
        var count = 1L
        for (axis in axes) {
            require(axis.keys.isNotEmpty()) { "Generation migration has an empty axis" }
            count = Math.multiplyExact(count, axis.keys.size.toLong())
            require(count <= 262144 && count * components <= 16777216) { "Generation migration exceeds the keyform allocation limit" }
        }
        return (0 until count.toInt()).map { index ->
            var value = index
            IntArray(axes.size) { axis -> (value % axes[axis].keys.size).also { value /= axes[axis].keys.size } }
        }
    }

    /** Only this chain can affect a drawable's space at any of the sampled poses. */
    private fun branch(model: PuppetModel, drawable: Drawable): List<Deformer> = buildList {
        var parent = drawable.parentDeformerId
        val seen = mutableSetOf<DeformerId>()
        while (parent != null) {
            require(seen.add(parent)) { "Generation migration parent hierarchy contains a cycle" }
            val deformer = requireNotNull(model.deformers.singleOrNull { it.id == parent }) { "Generation migration parent is missing" }
            add(deformer)
            parent = deformer.parent
        }
    }

    private fun geometryAxes(drawable: Drawable, branch: List<Deformer>): List<KeyformAxis> =
        drawable.geometryGrid?.axes.orEmpty() + branch.flatMap { deformer ->
            when (deformer) {
                is Deformer.Warp -> deformer.geometryGrid?.axes.orEmpty()
                is Deformer.Rotation -> deformer.geometryGrid?.axes.orEmpty()
            }
        }

    private fun world(model: PuppetModel, drawable: Drawable, pose: Map<ParameterId, Float>, defaults: Map<ParameterId, Float>): FloatArray =
        requireNotNull(drawableSpaceMapping(model, pose, drawable.id)) { "Generation migration parent cannot be evaluated" }
            .localToWorld(local(drawable, pose, defaults))

    private fun local(drawable: Drawable, pose: Map<ParameterId, Float>, defaults: Map<ParameterId, Float>): FloatArray {
        val mesh = requireNotNull(drawable.mesh)
        val local = mesh.positions.copyOf()
        val grid = drawable.geometryGrid
        if (grid != null) {
            val corners = requireNotNull(gridCorners(grid) { id ->
                val axis = grid.axes.single { it.parameterId == id }
                (pose[id] ?: defaults[id] ?: 0f).coerceIn(axis.keys.first(), axis.keys.last())
            })
            for (corner in corners) {
                val values = grid.cellsByLinearIndex[corner.linearIndex]?.form?.positionDeltas ?: continue
                require(values.size == local.size) { "Generation migration has stale keyform dimensions" }
                for (index in values.indices) local[index] += values[index] * corner.weight
            }
        }
        return local
    }

    private fun transfer(values: FloatArray, sources: List<VertexSource>): FloatArray = FloatArray(sources.size * 2) { index ->
        val component = index % 2
        when (val source = sources[index / 2]) {
            is VertexSource.FromOld -> values[source.oldIndex * 2 + component]
            is VertexSource.BarycentricOf -> values[source.oldA * 2 + component] * source.wa + values[source.oldB * 2 + component] * source.wb +
                values[source.oldC * 2 + component] * source.wc
            else -> error("Unsupported generation migration vertex source")
        }
    }
}
