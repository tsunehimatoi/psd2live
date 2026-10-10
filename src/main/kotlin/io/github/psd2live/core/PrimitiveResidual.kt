package io.github.psd2live.core

import kotlinx.serialization.json.*
import org.umamo.edit.VertexSource
import org.umamo.render.eval.drawableSpaceMapping
import org.umamo.runtime.eval.EPS_KEY
import org.umamo.runtime.eval.colorAt
import org.umamo.runtime.eval.flagAt
import org.umamo.runtime.eval.gridCorners
import org.umamo.runtime.eval.scalarAt
import org.umamo.runtime.model.*
import kotlin.math.abs
import io.github.psd2live.core.legacy.RigGenerationResidual

/**
 * Rule B of version 2 `art_primitive` records ([ArtPrimitiveV2]): what the user authored on a split part is kept
 * as the difference from what the generators made, so the part follows later regeneration.
 *
 * At capture, A is the superseded drawable's authored state carried onto a part, G the generators' output for it
 * carried the same way, and P the part the base generated on the pinned mesh. The residual A - G of a keyform
 * cell is converted into P's parent space. Cells on the user-axis plane - every generator axis (an axis of P's
 * grid) at its default - become the record's [ArtPrimitiveV2.GEOMETRY_RESIDUAL]; a cell with a generator axis
 * away from its default becomes a `generated_override` (base P, points P + residual) the three-way merge applies
 * after every generator. Channels carry their residual on every cell ([ArtPrimitiveV2.CHANNELS_RESIDUAL]).
 *
 * At replay the part's grid spans the generated axes and the residual's axes; a plane cell is the generated value
 * plus the residual, any other cell the generated value (an override merges in later). For an unchanged generator
 * this gives back A exactly.
 */
internal object PrimitiveResidual {
	/** Below this every component of a residual is treated as zero. */
	const val EPS = 1e-6f

	/** [grid]'s form at the pose [value] (a parameter not on an axis reads its default), as deltas of [size] floats. */
	fun sample(grid: KeyformGrid<MeshDeltaForm>?, size: Int, value: (ParameterId) -> Float): FloatArray {
		val result = FloatArray(size)
		if (grid == null) return result
		val corners = gridCorners(grid) { id ->
			val axis = grid.axes.single { it.parameterId == id }
			value(id).coerceIn(axis.keys.first(), axis.keys.last())
		} ?: return result
		for (corner in corners) {
			val deltas = grid.cellsByLinearIndex[corner.linearIndex]?.form?.positionDeltas ?: continue
			require(deltas.size == size) { "Art primitive keyform does not match its mesh" }
			for (index in deltas.indices) result[index] += deltas[index] * corner.weight
		}
		return result
	}

	/** Axes with every knot of [input] per parameter, in first-seen order; near-duplicate knots merge. */
	fun unionAxes(input: List<KeyformAxis>): List<KeyformAxis> = input.groupBy { it.parameterId }.map { (id, axes) ->
		val values = ArrayList<Float>()
		axes.forEach { axis -> axis.keys.forEach { value -> if (values.none { abs(it - value) < EPS_KEY }) values += value } }
		KeyformAxis(id, values.sorted().toFloatArray())
	}

	/** Every coordinate of a grid over [axes], first axis fastest. */
	fun coordinates(axes: List<KeyformAxis>): List<IntArray> {
		var count = 1L
		for (axis in axes) {
			require(axis.keys.isNotEmpty()) { "Art primitive keyform axis is empty" }
			count = Math.multiplyExact(count, axis.keys.size.toLong())
			require(count <= 262144) { "Art primitive keyforms exceed the allocation limit" }
		}
		return (0 until count.toInt()).map { index ->
			var value = index
			IntArray(axes.size) { axis -> (value % axes[axis].keys.size).also { value /= axes[axis].keys.size } }
		}
	}

	fun key(axes: List<KeyformAxis>, coordinate: IntArray): Map<ParameterId, Float> =
		axes.indices.associate { axes[it].parameterId to axes[it].keys[coordinate[it]] }

	/** [values] (x/y per vertex) on the vertices [sources] derive from them. */
	fun transfer(values: FloatArray, sources: List<VertexSource>): FloatArray = FloatArray(sources.size * 2) { index ->
		val component = index % 2
		when (val source = sources[index / 2]) {
			is VertexSource.FromOld -> values[source.oldIndex * 2 + component]
			is VertexSource.BarycentricOf -> values[source.oldA * 2 + component] * source.wa +
				values[source.oldB * 2 + component] * source.wb + values[source.oldC * 2 + component] * source.wc
			else -> error("Unsupported art primitive vertex source")
		}
	}

	/** A per-vertex scalar array (vertex group weights) on the vertices [sources] derive from them. */
	fun transferScalars(values: FloatArray, sources: List<VertexSource>): FloatArray = FloatArray(sources.size) { index ->
		when (val source = sources[index]) {
			is VertexSource.FromOld -> values[source.oldIndex]
			is VertexSource.BarycentricOf -> values[source.oldA] * source.wa + values[source.oldB] * source.wb + values[source.oldC] * source.wc
			else -> error("Unsupported art primitive vertex source")
		}
	}

	private fun isZero(values: FloatArray) = values.all { abs(it) <= EPS }

	/**
	 * Maps keyform deltas of a part from its authored parent space into the generated part's parent space, at the
	 * default pose: rest positions plus a delta go to world through the authored parent and back through the
	 * generated one. The identity when both parents are the same deformer in the same state.
	 */
	class ParentSpace(authoredModel: PuppetModel, authored: Drawable, parkedModel: PuppetModel, parked: Drawable, seed: FloatArray) {
		private val rest = requireNotNull(authored.mesh).positions
		private val identity: Boolean
		private val from = drawableSpaceMapping(authoredModel, emptyMap(), authored.id)
		private val to = drawableSpaceMapping(parkedModel, emptyMap(), parked.id)
		private val seed = seed
		private val worldRest: FloatArray
		private val localRest: FloatArray
		private val indices = (0 until rest.size / 2).toSet()

		init {
			val a = authored.parentDeformerId?.let { id -> authoredModel.deformers.firstOrNull { it.id == id } }
			val b = parked.parentDeformerId?.let { id -> parkedModel.deformers.firstOrNull { it.id == id } }
			identity = authored.parentDeformerId == parked.parentDeformerId && (a === b || a == b) &&
				sameChain(authoredModel, parkedModel, a?.parent)
			require(identity || (from != null && to != null)) { "Art primitive parent cannot be evaluated" }
			worldRest = if (identity) rest else from!!.localToWorld(rest)
			localRest = if (identity) rest else to!!.worldToLocalLinearized(worldRest, seed, worldRest, indices)
		}

		private fun sameChain(a: PuppetModel, b: PuppetModel, start: DeformerId?): Boolean {
			var id = start
			val seen = HashSet<DeformerId>()
			while (id != null && seen.add(id)) {
				val x = a.deformers.firstOrNull { it.id == id }; val y = b.deformers.firstOrNull { it.id == id }
				if (x == null || y == null || (x !== y && x != y)) return false
				id = x.parent
			}
			return true
		}

		/** [delta] (authored parent space) as a delta in the generated part's parent space. */
		fun convert(delta: FloatArray): FloatArray {
			if (identity || isZero(delta)) return delta
			val world = from!!.localToWorld(FloatArray(rest.size) { rest[it] + delta[it] })
			val local = to!!.worldToLocalLinearized(world, seed, worldRest, indices)
			return FloatArray(local.size) { local[it] - localRest[it] }
		}
	}

	/**
	 * What the user changed on [authored]'s own keyforms over [generated] (the same drawable in the base, same
	 * vertices): per cell of the union of their own axes, the authored deltas minus the generated ones, the latter
	 * moved into the authored parent space at the default pose when a journal edit reparented the drawable. Moved
	 * onto other vertices through [sources] when given; null when nothing changed.
	 *
	 * Unlike [RigGenerationResidual.geometryDelta] this compares local keyforms only: an ancestor deformer's axes
	 * move both alike and never enter the residual.
	 */
	fun delta(authoredModel: PuppetModel, authored: Drawable, generatedModel: PuppetModel, generated: Drawable,
	          sources: List<VertexSource>? = null, checkpoint: () -> Unit = {}): KeyformGrid<MeshDeltaForm>? {
		val rest = requireNotNull(authored.mesh).positions
		val generatedRest = requireNotNull(generated.mesh).positions
		require(rest.size == generatedRest.size) { "Art primitive generated original does not match its mesh" }
		val parameters = authoredModel.parameters.associateBy { it.id }
		fun default(id: ParameterId) = parameters[id]?.default ?: 0f
		val axes = unionAxes((authored.geometryGrid?.axes.orEmpty() + generated.geometryGrid?.axes.orEmpty()).filter { it.parameterId in parameters })
		val space = ParentSpace(generatedModel, generated, authoredModel, authored, rest)
		var changed = false
		val cells = coordinates(axes).map { coordinate ->
			checkpoint()
			val key = key(axes, coordinate)
			val value = { id: ParameterId -> key[id] ?: default(id) }
			val a = sample(authored.geometryGrid, rest.size, value)
			val g = space.convert(sample(generated.geometryGrid, rest.size, value))
			val local = FloatArray(rest.size) { a[it] - g[it] }
			val moved = sources?.let { transfer(local, it) } ?: local
			if (!isZero(moved)) changed = true
			KeyformCell(coordinate, MeshDeltaForm(moved))
		}
		return if (changed) KeyformGrid(axes, cells) else null
	}

	/** The geometry residual of one part and the overrides for its generator cells. */
	class Geometry(val residual: KeyformGrid<MeshDeltaForm>?, val overrides: List<JsonObject>)

	/**
	 * The residual [delta] - authored minus generated per cell on the recorded vertices ([size] floats), as
	 * [RigGenerationResidual.geometryDelta] gives it - split by [parked]'s generator axes into the plane residual and
	 * overrides. [convert] maps a delta into the parked part's parent space; [toParked] carries a recorded-vertex
	 * array onto the parked mesh's vertices (identity when the topologies agree); [owner] names a parameter's
	 * generator node.
	 */
	fun geometry(
		parameters: Map<ParameterId, Parameter>,
		delta: KeyformGrid<MeshDeltaForm>?,
		size: Int,
		parked: Drawable,
		convert: (FloatArray) -> FloatArray,
		toParked: (FloatArray) -> FloatArray,
		owner: (ParameterId) -> String?,
		checkpoint: () -> Unit = {},
	): Geometry {
		val parkedSize = requireNotNull(parked.mesh).positions.size
		val generators = parked.geometryGrid?.axes.orEmpty().mapTo(LinkedHashSet()) { it.parameterId }
		fun default(id: ParameterId) = parameters[id]?.default ?: 0f
		if (delta == null) return Geometry(null, emptyList())
		// Every residual knot, plus each generator axis's default so the plane has a cell.
		val residualAxes = unionAxes(delta.axes + delta.axes.filter { it.parameterId in generators }
			.map { KeyformAxis(it.parameterId, floatArrayOf(default(it.parameterId))) })
		fun difference(key: Map<ParameterId, Float>): FloatArray = convert(sample(delta, size) { id -> key[id] ?: default(id) })
		fun onPlane(key: Map<ParameterId, Float>) = key.all { (id, value) -> id !in generators || abs(value - default(id)) < EPS_KEY }
		var nonZero = false
		val cells = ArrayList<KeyformCell<MeshDeltaForm>>()
		for (coordinate in coordinates(residualAxes)) {
			checkpoint()
			val key = key(residualAxes, coordinate)
			if (!onPlane(key)) continue
			val delta = difference(key)
			if (!isZero(delta)) nonZero = true
			cells += KeyformCell(coordinate, MeshDeltaForm(delta))
		}
		// The replayed grid spans the generated axes and the residual's: overrides key cells of exactly that grid.
		val parkedAxes = parked.geometryGrid?.axes.orEmpty()
		val finalAxes = unionAxes(parkedAxes + residualAxes)
		val changesAxes = finalAxes.map { it.parameterId to it.keys.toList() } != parkedAxes.map { it.parameterId to it.keys.toList() }
		val residual = if (residualAxes.isNotEmpty() && (nonZero || changesAxes)) KeyformGrid(residualAxes, cells) else null
		val overrides = ArrayList<JsonObject>()
		for (coordinate in coordinates(finalAxes)) {
			checkpoint()
			val key = key(finalAxes, coordinate)
			if (onPlane(key)) continue
			val delta = difference(key)
			if (isZero(delta)) continue
			val base = sample(parked.geometryGrid, parkedSize, { id -> key[id] ?: default(id) })
			val carried = toParked(delta)
			val generator = key.entries.firstNotNullOfOrNull { (id, value) ->
				if (id in generators && abs(value - default(id)) >= EPS_KEY) owner(id) else null
			} ?: DocumentGenerators.RIG_MESHES
			overrides += buildJsonObject {
				put("op", GeneratedOverrides.OP); put("generator", generator); put("target", "mesh:${parked.id.raw}")
				put("key", JsonObject(key.entries.associate { it.key.raw to JsonPrimitive(it.value) }))
				put("base", JsonArray(base.map(::JsonPrimitive)))
				put("points", JsonArray(FloatArray(base.size) { base[it] + carried[it] }.map(::JsonPrimitive)))
			}
		}
		return Geometry(residual, overrides)
	}

	/** The part's replayed geometry: [parked]'s generated grid plus [residual] on the plane of its generator axes. */
	fun compose(parameters: Map<ParameterId, Parameter>, parked: KeyformGrid<MeshDeltaForm>?, residual: KeyformGrid<MeshDeltaForm>?,
	            size: Int): KeyformGrid<MeshDeltaForm>? {
		if (residual == null) return parked
		val generators = parked?.axes.orEmpty().mapTo(HashSet()) { it.parameterId }
		fun default(id: ParameterId) = parameters[id]?.default ?: 0f
		val axes = unionAxes(parked?.axes.orEmpty() + residual.axes)
		val cells = coordinates(axes).map { coordinate ->
			val key = key(axes, coordinate)
			val value = sample(parked, size) { id -> key[id] ?: default(id) }
			val plane = key.all { (id, v) -> id !in generators || abs(v - default(id)) < EPS_KEY }
			if (plane) exact(residual, key)?.let { extra -> for (index in value.indices) value[index] += extra[index] }
			KeyformCell(coordinate, MeshDeltaForm(value))
		}
		return KeyformGrid(axes, cells)
	}

	/** The residual's form at exactly [key] (every residual axis at one of its knots), or null. */
	private fun exact(grid: KeyformGrid<MeshDeltaForm>, key: Map<ParameterId, Float>): FloatArray? {
		val coordinate = IntArray(grid.axes.size) { index ->
			val axis = grid.axes[index]
			val value = key[axis.parameterId] ?: return null
			axis.keys.indexOfFirst { abs(it - value) < EPS_KEY }.takeIf { it >= 0 } ?: return null
		}
		return grid.cells.firstOrNull { it.coordinate.contentEquals(coordinate) }?.form?.positionDeltas
	}

	/** [grid] with every cell's deltas carried onto other vertices; null stays null. */
	fun carry(grid: KeyformGrid<MeshDeltaForm>?, map: (FloatArray) -> FloatArray): KeyformGrid<MeshDeltaForm>? =
		grid?.let { KeyformGrid(it.axes, it.cells.map { cell -> KeyformCell(cell.coordinate, MeshDeltaForm(map(cell.form.positionDeltas))) }) }

	private fun scalarStatic(drawable: Drawable, channel: FormChannel) = if (channel == FormChannel.DRAW_ORDER) drawable.drawOrder else drawable.opacity
	private fun colorStatic(drawable: Drawable, channel: FormChannel) = if (channel == FormChannel.MULTIPLY_COLOR) drawable.multiplyColor else drawable.screenColor

	/**
	 * The channel residual of [authored] over [generated] ([RigGenerationResidual.channelsDelta]), keeping only
	 * channels with a change: a channel without one stays exactly as generated.
	 */
	fun channels(model: PuppetModel, authored: Drawable, generated: Drawable, checkpoint: () -> Unit = {}): ChannelGrids =
		ChannelGrids(RigGenerationResidual.channelsDelta(model, authored, generated, checkpoint).gridsByChannel.filterValues { grid ->
			grid.cells.any { cell -> when (val form = cell.form) {
				is ChannelValue.Scalar -> abs(form.value) > EPS
				is ChannelValue.Color -> listOf(form.color.red, form.color.green, form.color.blue).any { abs(it) > EPS }
				is ChannelValue.Flag -> form.flag
			} }
		})

	/** [parked]'s channels plus [residual], cell by cell over the union of their axes. */
	fun composeChannels(parameters: Map<ParameterId, Parameter>, parked: Drawable, residual: ChannelGrids): ChannelGrids {
		if (residual.isEmpty) return parked.channelGrids
		fun default(id: ParameterId) = parameters[id]?.default ?: 0f
		val result = LinkedHashMap(parked.channelGrids.gridsByChannel)
		for ((channel, extra) in residual.gridsByChannel) {
			val generated = parked.channelGrids[channel]
			val axes = unionAxes(generated?.axes.orEmpty() + extra.axes)
			result[channel] = KeyformGrid(axes, coordinates(axes).map { coordinate ->
				val key = key(axes, coordinate)
				val value = { id: ParameterId -> key[id] ?: default(id) }
				val form: ChannelValue = when (channel.valueKind) {
					ChannelValueKind.SCALAR -> ChannelValue.Scalar(parked.channelGrids.scalarAt(channel, scalarStatic(parked, channel), value) +
						residual.scalarAt(channel, 0f, value))
					ChannelValueKind.COLOR -> {
						val x = parked.channelGrids.colorAt(channel, colorStatic(parked, channel), value)
						val y = residual.colorAt(channel, ColorRgb(0f, 0f, 0f), value)
						ChannelValue.Color(ColorRgb(x.red + y.red, x.green + y.green, x.blue + y.blue))
					}
					// Flags carry the authored change as an exclusive or.
					ChannelValueKind.FLAG -> ChannelValue.Flag(parked.channelGrids.flagAt(channel, false, paramValue = value) !=
						residual.flagAt(channel, false, paramValue = value))
				}
				KeyformCell(coordinate, form)
			})
		}
		return ChannelGrids(result)
	}
}
