package io.github.psd2live.core

import io.github.psd2live.i18n.tr
import org.umamo.runtime.eval.EPS_KEY
import org.umamo.runtime.eval.colorAt
import org.umamo.runtime.eval.gridCorners
import org.umamo.runtime.eval.meshGridDefaultDeltas
import org.umamo.runtime.eval.scalarAt
import org.umamo.runtime.eval.warpControlPointsAt
import org.umamo.runtime.model.BlendShapeBinding
import org.umamo.runtime.model.ChannelGrids
import org.umamo.runtime.model.ChannelValue
import org.umamo.runtime.model.ColorRgb
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.FormChannel
import org.umamo.runtime.model.KeyformAxis
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.MeshDeltaForm
import org.umamo.runtime.model.MeshForm
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.ParameterKind
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.WarpForm
import org.umamo.runtime.model.WarpLatticeForm

/**
 * The generated rig's blend shapes put the way Cubism takes them. Cubism adds a blend shape only on a parameter of the
 * blend shape kind, and such a parameter keys no keyforms: a blend shape on an ordinary parameter is not something a
 * moc3 or cmo3 can say, and the Cubism runtime draws it wrong. So each parameter the generated blend shapes ride is
 *
 * - keyed by blend shapes alone, resting at 0 with every shape's rest key there: it becomes a blend shape parameter;
 * - or also an axis of some keyform grid (the head's angles, the eyes' and mouth's openness, the gaze, the brows): its
 *   shapes become keyforms. Where the mesh's grid stays small they fold into it, as keys of their own on its axes -
 *   exactly, since a shape's weight is linear between its keys and its limit linear between its points. The rest go,
 *   as Live2D rigs carry such motions, to warps of the mesh's own between it and its parent: the head's turn in one,
 *   the other couplings in as few as keep each warp's keyforms few, each warp's lattice fitted to the shapes' moves.
 *
 * Opacity and color the shapes change fold into the mesh's channel tracks, which cost no vertices.
 */
internal object CubismBlendShapes {
	/** Most keyforms a mesh's grid takes folded shapes into, and most vertex values over all of them. */
	private const val MESH_CELLS = 256
	private const val MESH_VALUES = 120_000
	/** Most keyforms one carrying warp takes; and its lattice, cells a side. */
	private const val WARP_CELLS = 64
	private const val LATTICE = 8
	/** Most of a mesh's own keyforms whose vertices a warp is fitted at. */
	private const val SAMPLED_CELLS = 32
	private val HEAD = setOf(StandardParameters.ANGLE_X, StandardParameters.ANGLE_Y, StandardParameters.ANGLE_Z)

	fun lawful(generated: PuppetModel): PuppetModel {
		// No generated shape changes a mesh's place in the draw order; the order is settled after the shapes are made,
		// so their forms take it as it ended.
		val puppet = generated.copy(drawables = generated.drawables.map { d ->
			if (d.blendShapes.isEmpty()) d
			else {
				val order = d.channelGrids.scalarAt(FormChannel.DRAW_ORDER, d.drawOrder, { id: ParameterId -> generated.parameters.firstOrNull { it.id == id }?.default ?: 0f })
				if (d.blendShapes.all { b -> b.forms.all { it == null || it.drawOrder == order } }) d
				else d.copy(blendShapes = d.blendShapes.map { b -> b.copy(forms = b.forms.map { f -> f?.let {
					MeshForm(it.positionDeltas, order, it.opacity, it.multiplyColor, it.screenColor) } }) })
			}
		})
		val axes = gridParameters(puppet)
		val kinds = puppet.parameters.associate { it.id to it.kind }
		val defaults = puppet.parameters.associate { it.id to it.default }
		val bindings = puppet.drawables.flatMap { it.blendShapes } +
			puppet.deformers.flatMap { d -> if (d is Deformer.Warp) d.blendShapes else if (d is Deformer.Rotation) d.blendShapes else emptyList() } +
			puppet.parts.flatMap { it.blendShapes }
		val ordinary = bindings.filter { kinds[it.parameterId] == ParameterKind.NORMAL }
		if (ordinary.isEmpty()) return if (puppet.drawables.indices.all { puppet.drawables[it] === generated.drawables[it] }) generated else puppet
		// A parameter only shapes ride, resting at 0 where every shape rests: a blend shape parameter.
		val retyped = ordinary.groupBy { it.parameterId }.filter { (id, ridden) ->
			id !in axes && (defaults[id] ?: 0f) == 0f && ridden.all { b -> kotlin.math.abs(b.keys[b.neutralIndex]) < EPS_KEY }
		}.keys
		val folded = ordinary.map { it.parameterId }.toSet() - retyped
		val value = { id: ParameterId -> defaults[id] ?: 0f }
		val ranges = puppet.parameters.associate { it.id to (it.min to it.max) }
		val carriers = ArrayList<Deformer.Warp>()
		val partOf = puppet.deformers.associate { it.id to it.partId }
		val drawables = puppet.drawables.map { d ->
			if (d.blendShapes.none { it.parameterId in folded }) d
			else foldDrawable(d, folded, value, ranges, partOf[d.parentDeformerId]) { id ->
				// A warp's ID is the mesh's and its role's, numbered on where one is already taken.
				generateSequence(1) { it + 1 }.map { n -> DeformerId(if (n == 1) id else "${id}_$n") }
					.first { c -> puppet.deformers.none { it.id == c } && carriers.none { it.id == c } }
			}.also { (_, warps) -> carriers += warps }.first
		}
		return puppet.copy(
			parameters = puppet.parameters.map { p ->
				if (p.id !in retyped) p
				else p.copy(kind = ParameterKind.BLEND_SHAPE, keys = (p.keys.orEmpty() + bindings.filter { it.parameterId == p.id }.flatMap { it.keys.toList() })
					.distinct().sorted())
			},
			drawables = drawables,
			deformers = puppet.deformers.map { d -> if (d is Deformer.Warp && d.blendShapes.any { it.parameterId in folded }) foldWarp(d, folded, value, ranges) else d } + carriers,
		)
	}

	/** Every parameter some keyform grid has as an axis. */
	private fun gridParameters(puppet: PuppetModel): Set<ParameterId> = buildSet {
		fun add(grid: KeyformGrid<*>?) { grid?.axes?.forEach { add(it.parameterId) } }
		fun add(grids: ChannelGrids) { grids.gridsByChannel.values.forEach { add(it) } }
		for (d in puppet.drawables) { add(d.geometryGrid); add(d.channelGrids) }
		for (d in puppet.deformers) when (d) {
			is Deformer.Warp -> { add(d.geometryGrid); add(d.channelGrids) }
			is Deformer.Rotation -> { add(d.geometryGrid); add(d.channelGrids) }
		}
		for (p in puppet.parts) add(p.channelGrids)
		for (g in puppet.glues) add(g.channelGrids)
	}

	/** The weight each key of [binding] adds at the pose [value]: linear between its keys, held past its ends, times its limit. */
	private fun weights(binding: BlendShapeBinding<*>, value: (ParameterId) -> Float): List<Pair<Int, Float>> {
		val keys = binding.keys
		if (keys.size < 2) return emptyList()
		val limit = binding.limits.minOfOrNull { l ->
			val x = value(l.parameterId); val points = l.points
			when {
				points.isEmpty() -> 1f
				x <= points.first().value -> points.first().weight
				x >= points.last().value -> points.last().weight
				else -> points.zipWithNext().first { (_, b) -> x <= b.value }.let { (a, b) ->
					if (b.value - a.value <= 0f) b.weight else a.weight + (b.weight - a.weight) * (x - a.value) / (b.value - a.value)
				}
			}
		} ?: 1f
		if (limit <= 0f) return emptyList()
		val x = value(binding.parameterId)
		var lower = 0
		while (lower + 1 < keys.size && keys[lower + 1] <= x) lower++
		val fraction = when {
			x <= keys[0] || lower + 1 >= keys.size -> 0f
			else -> (x - keys[lower]) / (keys[lower + 1] - keys[lower]).coerceAtLeast(1e-9f)
		}
		return listOf(lower to (1f - fraction) * limit, lower + 1 to fraction * limit)
			.filter { (k, w) -> k < keys.size && k != binding.neutralIndex && w > 0f }
	}

	/** [axes] with each of [bindings]' parameters and limit parameters keyed at the shapes' keys and the limits' points. */
	private fun mergedAxes(axes: List<KeyformAxis>, bindings: List<BlendShapeBinding<*>>, ranges: Map<ParameterId, Pair<Float, Float>>): List<KeyformAxis> {
		val keys = LinkedHashMap<ParameterId, MutableList<Float>>()
		for (axis in axes) keys[axis.parameterId] = axis.keys.toMutableList()
		for (b in bindings) {
			keys.getOrPut(b.parameterId) { mutableListOf() } += b.keys.toList()
			for (l in b.limits) keys.getOrPut(l.parameterId) { mutableListOf() } += l.points.map { it.value }
		}
		return keys.map { (id, values) ->
			// Keys closer than the grid's snapping are one key; an axis's own keys win. A new axis spans the parameter's
			// range, as a grid draws nothing past its ends and shapes and limits hold past theirs.
			val own = axes.firstOrNull { it.parameterId == id }?.keys?.toList()
			val merged = ArrayList(own.orEmpty())
			if (own == null) ranges[id]?.let { (min, max) -> values += listOf(min, max) }
			for (v in values.sorted()) if (merged.none { kotlin.math.abs(it - v) < EPS_KEY * 2 }) merged += v
			KeyformAxis(id, merged.sorted().toFloatArray())
		}
	}

	private fun cellCount(axes: List<KeyformAxis>) = axes.fold(1L) { n, a -> n * a.keys.size }

	/** Every cell of a full grid over [axes], with the pose at it; parameters off the axes at their defaults. */
	private fun <T> cells(axes: List<KeyformAxis>, value: (ParameterId) -> Float, form: ((ParameterId) -> Float) -> T): List<KeyformCell<T>> {
		val count = cellCount(axes).toInt()
		return (0 until count).map { linear ->
			var rest = linear
			val coordinate = IntArray(axes.size) { i -> (rest % axes[i].keys.size).also { rest /= axes[i].keys.size } }
			val at = HashMap<ParameterId, Float>(axes.size * 2)
			for (i in axes.indices) at[axes[i].parameterId] = axes[i].keys[coordinate[i]]
			KeyformCell(coordinate, form { id -> at[id] ?: value(id) })
		}
	}

	/** What [bindings] add to [rest] at [pose]: each active key's form less the rest, by its weight. */
	private fun added(bindings: List<BlendShapeBinding<MeshForm>>, pose: (ParameterId) -> Float, part: (MeshForm) -> FloatArray, rest: FloatArray): FloatArray {
		val out = FloatArray(rest.size)
		for (b in bindings) for ((k, w) in weights(b, pose)) {
			val v = part(b.forms[k] ?: continue)
			for (i in out.indices) out[i] += w * (v.getOrElse(i) { 0f } - rest[i])
		}
		return out
	}

	/**
	 * [d] with its shapes on the [folded] parameters made keyforms: into its grid where that stays small, the rest into
	 * warps of its own, returned beside it.
	 */
	private fun foldDrawable(d: Drawable, folded: Set<ParameterId>, value: (ParameterId) -> Float, ranges: Map<ParameterId, Pair<Float, Float>>, part: org.umamo.runtime.model.PartId?,
	                         unique: (String) -> DeformerId):
		Pair<Drawable, List<Deformer.Warp>> {
		val (fold, keep) = d.blendShapes.partition { it.parameterId in folded }
		val mesh = d.mesh ?: return d.copy(blendShapes = keep) to emptyList()
		val size = mesh.positions.size
		// A shape adds the difference between its form and the drawable at the default pose.
		val restDeltas = meshGridDefaultDeltas(d, value) ?: FloatArray(size)
		// Shapes on one parameter go together, each with its limits' parameters; the head's turn is one group. Shapes add,
		// so groups sharing a limit's parameter may still go to different warps.
		val head = fold.filter { it.parameterId in HEAD }
		val groups = (fold - head.toSet()).groupBy { it.parameterId }.values.map { it.toMutableList() }
		// Into the mesh's grid while it stays small, the head's turn last; whatever is left to warps.
		val own = d.geometryGrid?.axes.orEmpty()
		// A moc3 keys a mesh's geometry and its channels on one grid, over all their axes.
		val channelAxes = d.channelGrids.gridsByChannel.values.flatMap { it.axes }.filter { a -> own.none { it.parameterId == a.parameterId } }
			.distinctBy { it.parameterId }
		val inMesh = ArrayList<BlendShapeBinding<MeshForm>>()
		val toWarps = ArrayList<List<BlendShapeBinding<MeshForm>>>()
		for (g in groups + listOf(head).filter { it.isNotEmpty() }) {
			val axes = mergedAxes(own + channelAxes, inMesh + g, ranges)
			if (cellCount(axes) <= MESH_CELLS && cellCount(axes) * size <= MESH_VALUES) inMesh += g else toWarps += g
		}
		// Warps take the groups a few at a time: the head's turn alone, the couplings packed while each warp stays small.
		val packed = ArrayList<MutableList<BlendShapeBinding<MeshForm>>>()
		for (g in toWarps) {
			val into = packed.firstOrNull { p -> p.none { it.parameterId in HEAD } && g.none { it.parameterId in HEAD } &&
				cellCount(mergedAxes(emptyList(), p + g, ranges)) <= WARP_CELLS }
			if (into != null) into += g else packed += g.toMutableList()
		}
		// The head's turn outermost, as the head carries the features it holds.
		packed.sortBy { p -> if (p.any { it.parameterId in HEAD }) 0 else 1 }
		var geometry = d.geometryGrid
		if (inMesh.isNotEmpty()) {
			val axes = mergedAxes(own, inMesh, ranges)
			geometry = KeyformGrid(axes, cells(axes, value) { pose ->
				val base = d.geometryGrid?.let { grid -> gridAt(grid, pose) { it.positionDeltas } } ?: FloatArray(size)
				val add = added(inMesh, pose, { it.positionDeltas }, restDeltas)
				MeshDeltaForm(FloatArray(size) { i -> base.getOrElse(i) { 0f } + add[i] })
			})
		}
		val channels = foldedChannels(d, fold, value, ranges)
		var drawable = d.copy(geometryGrid = geometry, channelGrids = channels, blendShapes = keep)
		if (packed.isEmpty()) return drawable to emptyList()
		return carried(drawable, packed, restDeltas, value, ranges, part, unique)
	}

	/** [d]'s channel tracks with what [fold]'s shapes do to its opacity, draw order and colors. */
	private fun foldedChannels(d: Drawable, fold: List<BlendShapeBinding<MeshForm>>, value: (ParameterId) -> Float, ranges: Map<ParameterId, Pair<Float, Float>>): ChannelGrids {
		var channels = d.channelGrids.gridsByChannel
		fun changes(part: (MeshForm) -> FloatArray, rest: FloatArray) =
			fold.any { b -> b.forms.any { f -> f != null && part(f).withIndex().any { (i, v) -> kotlin.math.abs(v - rest[i]) > 1e-3f } } }
		fun scalar(channel: FormChannel, static: Float, part: (MeshForm) -> Float) {
			val rest = floatArrayOf(d.channelGrids.scalarAt(channel, static, value))
			val parts = { f: MeshForm -> floatArrayOf(part(f)) }
			if (!changes(parts, rest)) return
			val a = mergedAxes(channels[channel]?.axes.orEmpty(), fold, ranges)
			val before = ChannelGrids(channels)
			channels = channels + (channel to KeyformGrid<ChannelValue>(a, cells(a, value) { pose ->
				ChannelValue.Scalar(before.scalarAt(channel, static, pose) + added(fold, pose, parts, rest)[0])
			}))
		}
		fun color(channel: FormChannel, static: ColorRgb, part: (MeshForm) -> ColorRgb) {
			val restColor = d.channelGrids.colorAt(channel, static, value)
			val rest = floatArrayOf(restColor.red, restColor.green, restColor.blue)
			val parts = { f: MeshForm -> part(f).let { floatArrayOf(it.red, it.green, it.blue) } }
			if (!changes(parts, rest)) return
			val a = mergedAxes(channels[channel]?.axes.orEmpty(), fold, ranges)
			val before = ChannelGrids(channels)
			channels = channels + (channel to KeyformGrid<ChannelValue>(a, cells(a, value) { pose ->
				val base = before.colorAt(channel, static, pose)
				val add = added(fold, pose, parts, rest)
				ChannelValue.Color(ColorRgb(base.red + add[0], base.green + add[1], base.blue + add[2]))
			}))
		}
		scalar(FormChannel.OPACITY, d.opacity) { it.opacity }
		scalar(FormChannel.DRAW_ORDER, d.drawOrder) { it.drawOrder }
		color(FormChannel.MULTIPLY_COLOR, d.multiplyColor) { it.multiplyColor }
		color(FormChannel.SCREEN_COLOR, d.screenColor) { it.screenColor }
		return ChannelGrids(channels)
	}

	/**
	 * [d] under a warp of its own per group of [groups], outermost first, each warp's lattice fitted to what its shapes
	 * move the mesh by. The mesh and what keys it are carried into the innermost warp's space, which maps them back to
	 * where they were at rest.
	 */
	private fun carried(d: Drawable, groups: List<List<BlendShapeBinding<MeshForm>>>, restDeltas: FloatArray, value: (ParameterId) -> Float,
	                    ranges: Map<ParameterId, Pair<Float, Float>>,
	                    part: org.umamo.runtime.model.PartId?, unique: (String) -> DeformerId): Pair<Drawable, List<Deformer.Warp>> {
		val positions = d.mesh!!.positions
		val n = positions.size / 2
		// Where the mesh's own keyforms take its vertices - a mouth opens, an eye shuts - each vertex moved by its shapes
		// wherever it is: the warps are fitted to all of them, not to the rest pose alone.
		val own = d.geometryGrid?.cells.orEmpty()
		val stride = (own.size / SAMPLED_CELLS).coerceAtLeast(1)
		val samples = listOf(restDeltas) + own.filterIndexed { i, _ -> i % stride == 0 }.map { it.form.positionDeltas }
		var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
		for (deltas in samples) for (i in 0 until n) {
			val x = positions[i * 2] + deltas[i * 2]; val y = positions[i * 2 + 1] + deltas[i * 2 + 1]
			minX = minOf(minX, x); maxX = maxOf(maxX, x); minY = minOf(minY, y); maxY = maxOf(maxY, y)
		}
		val padX = (maxX - minX).coerceAtLeast(1e-4f) * 0.15f; val padY = (maxY - minY).coerceAtLeast(1e-4f) * 0.15f
		minX -= padX; maxX += padX; minY -= padY; maxY += padY
		// The current space as an affine of the parent's: x' = (x - ox) / sx.
		var ox = 0f; var oy = 0f; var sx = 1f; var sy = 1f
		var parent = d.parentDeformerId
		val warps = ArrayList<Deformer.Warp>()
		val points = LATTICE + 1
		// Each vertex's weights on the lattice: bilinear in its cell, as the warp maps it.
		val sampleU = FloatArray(n * samples.size) { k -> val i = k % n; (positions[i * 2] + samples[k / n][i * 2] - minX) / (maxX - minX) }
		val sampleV = FloatArray(n * samples.size) { k -> val i = k % n; (positions[i * 2 + 1] + samples[k / n][i * 2 + 1] - minY) / (maxY - minY) }
		val fit = latticeFit(sampleU, sampleV)
		val solve = { move: FloatArray -> fit(FloatArray(n * samples.size) { move[it % n] }) }
		for ((index, group) in groups.withIndex()) {
			// This warp's rectangle in the current space.
			val left = (minX - ox) / sx; val top = (minY - oy) / sy
			val width = (maxX - minX) / sx; val height = (maxY - minY) / sy
			val axes = mergedAxes(emptyList(), group, ranges)
			val grid = KeyformGrid(axes, cells(axes, value) { pose ->
				val move = added(group, pose, { it.positionDeltas }, restDeltas)
				val dx = solve(FloatArray(n) { move[it * 2] / sx }); val dy = solve(FloatArray(n) { move[it * 2 + 1] / sy })
				WarpLatticeForm(FloatArray(points * points * 2) { k ->
					val j = k / 2; val column = j % points; val row = j / points
					if (k % 2 == 0) left + column.toFloat() / LATTICE * width + dx[j] else top + row.toFloat() / LATTICE * height + dy[j]
				})
			})
			val head = group.any { it.parameterId in HEAD }
			val id = unique("${d.id.raw}_${if (head) "Turn" else "Shape$index"}")
			warps += Deformer.Warp(id, tr(if (head) "model.deformer.meshTurn" else "model.deformer.meshShape", d.name), parent, part,
				LATTICE, LATTICE, true, grid)
			parent = id
			ox += left * sx; oy += top * sy; sx *= width; sy *= height
		}
		fun scaled(deltas: FloatArray) = FloatArray(deltas.size) { i -> deltas[i] / if (i % 2 == 0) sx else sy }
		val mesh = DrawableMesh(FloatArray(positions.size) { i -> if (i % 2 == 0) (positions[i] - ox) / sx else (positions[i] - oy) / sy },
			d.mesh!!.uvs, d.mesh!!.indices)
		val geometry = d.geometryGrid?.let { g -> KeyformGrid(g.axes, g.cells.map { KeyformCell(it.coordinate, MeshDeltaForm(scaled(it.form.positionDeltas))) }) }
		val shapes = d.blendShapes.map { b -> b.copy(forms = b.forms.map { f -> f?.let { MeshForm(scaled(it.positionDeltas), it.drawOrder, it.opacity, it.multiplyColor, it.screenColor) } }) }
		return d.copy(parentDeformerId = parent, mesh = mesh, geometryGrid = geometry, blendShapes = shapes) to warps
	}

	/**
	 * Least squares from per-vertex moves to the lattice's, for vertices at ([u], [v]) in the warp: bilinear weights, and
	 * a little smoothness so lattice points no vertex is near follow their neighbours. One factoring for every pose.
	 */
	private fun latticeFit(u: FloatArray, v: FloatArray): (FloatArray) -> FloatArray {
		val points = LATTICE + 1
		val m = points * points
		val a = Array(m) { DoubleArray(m) }
		val weights = Array(u.size) { i ->
			val gu = (u[i] * LATTICE).coerceIn(0f, LATTICE - 1e-4f); val gv = (v[i] * LATTICE).coerceIn(0f, LATTICE - 1e-4f)
			val c = gu.toInt(); val r = gv.toInt(); val fu = gu - c; val fv = gv - r
			val p = r * points + c
			listOf(p to (1 - fu) * (1 - fv), p + 1 to fu * (1 - fv), p + points to (1 - fu) * fv, p + points + 1 to fu * fv)
		}
		for (w in weights) for ((p, wp) in w) for ((q, wq) in w) a[p][q] += (wp * wq).toDouble()
		// Smoothness: each point near the mean of its neighbours.
		val smooth = 1e-3 * (u.size.toDouble() / m).coerceAtLeast(1.0)
		for (r in 0 until points) for (c in 0 until points) {
			val p = r * points + c
			val near = listOfNotNull(if (c > 0) p - 1 else null, if (c < LATTICE) p + 1 else null, if (r > 0) p - points else null, if (r < LATTICE) p + points else null)
			// (x_p - mean)^2 summed: the Laplacian's square, row p.
			val row = DoubleArray(m); row[p] = 1.0; for (q in near) row[q] -= 1.0 / near.size
			for (i in 0 until m) if (row[i] != 0.0) for (j in 0 until m) if (row[j] != 0.0) a[i][j] += smooth * row[i] * row[j]
		}
		// Cholesky.
		val l = Array(m) { DoubleArray(m) }
		for (i in 0 until m) for (j in 0..i) {
			var s = a[i][j]
			for (k in 0 until j) s -= l[i][k] * l[j][k]
			l[i][j] = if (i == j) kotlin.math.sqrt(s.coerceAtLeast(1e-12)) else s / l[j][j]
		}
		return { target ->
			val b = DoubleArray(m)
			for ((i, w) in weights.withIndex()) for ((p, wp) in w) b[p] += wp * target[i].toDouble()
			val y = DoubleArray(m)
			for (i in 0 until m) { var s = b[i]; for (k in 0 until i) s -= l[i][k] * y[k]; y[i] = s / l[i][i] }
			val x = DoubleArray(m)
			for (i in m - 1 downTo 0) { var s = y[i]; for (k in i + 1 until m) s -= l[k][i] * x[k]; x[i] = s / l[i][i] }
			FloatArray(m) { x[it].toFloat() }
		}
	}

	private fun foldWarp(d: Deformer.Warp, folded: Set<ParameterId>, value: (ParameterId) -> Float, ranges: Map<ParameterId, Pair<Float, Float>>): Deformer.Warp {
		val (fold, keep) = d.blendShapes.partition { it.parameterId in folded }
		val rest = warpControlPointsAt(d.geometryGrid, value) ?: return d.copy(blendShapes = keep)
		val axes = mergedAxes(d.geometryGrid?.axes.orEmpty(), fold, ranges)
		val geometry = KeyformGrid(axes, cells(axes, value) { pose ->
			val out = (warpControlPointsAt(d.geometryGrid, pose) ?: rest).copyOf()
			for (b in fold) for ((k, w) in weights(b, pose)) {
				val form: WarpForm = b.forms[k] ?: continue
				for (i in out.indices) out[i] += w * (form.controlPoints[i] - rest[i])
			}
			WarpLatticeForm(out)
		})
		return d.copy(geometryGrid = geometry, blendShapes = keep)
	}

	/** [grid]'s form at [pose], blended from its corners. */
	private fun <T> gridAt(grid: KeyformGrid<T>, pose: (ParameterId) -> Float, values: (T) -> FloatArray): FloatArray? {
		val corners = gridCorners(grid, pose) ?: return null
		val cells = grid.cellsByLinearIndex
		var out: FloatArray? = null
		for (c in corners) {
			val v = values(cells[c.linearIndex]?.form ?: continue)
			val target = out ?: FloatArray(v.size).also { out = it }
			for (i in target.indices) target[i] += c.weight * v[i]
		}
		return out
	}
}
