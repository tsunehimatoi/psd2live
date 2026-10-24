package io.github.psd2live.core.modifier

import io.github.psd2live.core.RigAuthoringJournal
import io.github.psd2live.core.RigGeometryTools
import kotlinx.serialization.json.*
import org.umamo.runtime.model.*
import kotlin.math.hypot
import kotlin.math.max

/**
 * A mesh's modifiers written as a stage of their own: a warp inserted between the mesh and its parent, keyed on the
 * modifiers' driving parameters only, as a Live2D maker adds one warp per kind of deformation. The mesh keeps its own
 * keyforms, so their axes are not multiplied by the modifiers'. The warp's lattice is fitted to the offsets the stack
 * makes at the mesh's own keys' defaults; a stack whose offsets depend on the mesh's own keys cannot be written so.
 */
internal object ModifierStage {
	const val PREFIX = "ModStage_"

	/** Lattice cells along each side. */
	private const val CELLS = 5

	fun id(mesh: String) = PREFIX + mesh

	/** Why [mesh] could not take its stack as a stage, or the stage's largest distance from the stack (canvas pixels). */
	sealed interface Result {
		data class Written(val model: PuppetModel, val error: Float) : Result
		data class Refused(val reason: String) : Result
	}

	/**
	 * Whether [axes] / [table] (what the stack adds to [mesh], over its drivers and its own axes) are better written as a
	 * stage: they bring axes the mesh does not have, and merging them would multiply its keyforms past [ratio] times
	 * and [floor] cells.
	 */
	fun preferred(model: PuppetModel, mesh: String, axes: List<ModifierValue.Keyed.Axis>, drivers: Set<String>, ratio: Int = 4, floor: Int = 256): Boolean {
		val own = model.drawables.firstOrNull { it.id.raw == mesh }?.geometryGrid
		val ownAxes = own?.axes.orEmpty().map { it.parameterId.raw }.toSet()
		if (drivers.all { it in ownAxes }) return false
		val merged = axes.fold(1L) { n, a -> n * a.keys.size }
		val cells = own?.cells?.size?.toLong() ?: 1L
		return merged > max(cells * ratio, floor.toLong())
	}

	/** [model] with [mesh] under a new warp carrying [table]'s offsets on [drivers], or why it cannot be. */
	fun insert(model: PuppetModel, mesh: String, axes: List<ModifierValue.Keyed.Axis>, table: ModifierValue, drivers: Set<String>, tolerance: Float): Result {
		val drawable = model.drawables.firstOrNull { it.id.raw == mesh } ?: return Result.Refused("mesh missing")
		val geometry = drawable.mesh ?: return Result.Refused("mesh missing")
		val defaults = model.parameters.associate { it.id.raw to it.default }
		val driverAxes = axes.filter { it.parameter in drivers }
		val ownAxes = axes.filter { it.parameter !in drivers }
		// The stage carries what the stack adds over the drivers alone: it must not change along the mesh's own axes.
		val combos = driverAxes.fold(1) { n, a -> n * a.keys.size }
		val ownCombos = ownAxes.fold(1) { n, a -> n * a.keys.size }
		val offsets = List(combos) { index ->
			val pose = pose(driverAxes, index)
			val base = table.at { pose[it] ?: defaults[it] ?: 0f }
			for (o in 0 until ownCombos) {
				val own = pose(ownAxes, o)
				val other = table.at { pose[it] ?: own[it] ?: defaults[it] ?: 0f }
				if (ModifierSampler.distance(base, other) > tolerance) return Result.Refused("depends on the mesh's own keys")
			}
			base
		}
		// The warp spans every shape the mesh's keyforms give it, with a margin, in its parent's space.
		val points = listOf(geometry.positions) + drawable.geometryGrid?.cells.orEmpty().map { cell ->
			FloatArray(geometry.positions.size) { geometry.positions[it] + cell.form.positionDeltas[it] }
		}
		val bounds = RigGeometryTools.bounds(points.reduce { a, b -> a + b })
		val id = id(mesh)
		if (model.deformers.any { it.id.raw == id }) return Result.Refused("$id exists")
		var current = RigAuthoringJournal.apply(model, buildJsonObject {
			put("op", "canvas_create_warp"); put("id", id); put("name", "${drawable.name} · modifiers")
			put("rows", CELLS); put("columns", CELLS); put("add_to", "parent_of_selected")
			putJsonArray("meshes") { add(mesh) }
			putJsonObject("bounds") {
				put("x", bounds[0] - bounds[2] * 0.05f); put("y", bounds[1] - bounds[3] * 0.05f)
				put("w", bounds[2] * 1.1f); put("h", bounds[3] * 1.1f)
			}
		})
		val warp = current.deformers.firstOrNull { it.id.raw == id } as? Deformer.Warp ?: return Result.Refused("warp not made")
		val rest = warp.geometryGrid?.cells?.firstOrNull()?.form?.controlPoints ?: return Result.Refused("warp not made")
		// The mesh now sits in the lattice's unit square: its default-pose points are where the lattice reads it.
		val moved = current.drawables.first { it.id.raw == mesh }
		val uv = ModifierSampler.local(current, moved)
		val fit = Fit(uv, warp.rows, warp.columns)
		var worst = 0f
		val cells = offsets.mapIndexed { index, offset ->
			val displacement = fit.solve(offset)
			worst = max(worst, fit.error(displacement, offset))
			val coordinate = IntArray(driverAxes.size).also { c -> var rest = index; for (a in driverAxes.indices) { c[a] = rest % driverAxes[a].keys.size; rest /= driverAxes[a].keys.size } }
			KeyformCell(coordinate, WarpLatticeForm(FloatArray(rest.size) { rest[it] + displacement[it] }))
		}
		val grid = KeyformGrid(driverAxes.map { KeyformAxis(ParameterId(it.parameter), it.keys.copyOf()) }, cells)
		current = current.copy(deformers = current.deformers.map { if (it.id.raw == id) warp.copy(geometryGrid = grid) else it })
		return Result.Written(current, worst)
	}

	private fun pose(axes: List<ModifierValue.Keyed.Axis>, index: Int): Map<String, Float> {
		var rest = index
		return axes.associate { a -> a.parameter to a.keys[rest % a.keys.size].also { rest /= a.keys.size } }
	}

	/**
	 * The least-squares lattice displacement that moves points at [uv] (in the lattice's unit square) by given offsets
	 * through bilinear cells, held smooth where no point is (a small Laplacian term toward no displacement).
	 */
	private class Fit(private val uv: FloatArray, private val rows: Int, private val columns: Int) {
		private val n = (rows + 1) * (columns + 1)
		private val count = uv.size / 2
		/** Per point its four lattice indices and weights. */
		private val index = IntArray(count * 4)
		private val weight = FloatArray(count * 4)
		private val inverse: Array<DoubleArray>

		init {
			for (i in 0 until count) {
				val gu = (uv[i * 2].coerceIn(0f, 0.9999f) * columns); val gv = (uv[i * 2 + 1].coerceIn(0f, 0.9999f) * rows)
				val cu = gu.toInt(); val cv = gv.toInt(); val fu = gu - cu; val fv = gv - cv
				val p00 = cv * (columns + 1) + cu
				index[i * 4] = p00; index[i * 4 + 1] = p00 + 1; index[i * 4 + 2] = p00 + columns + 1; index[i * 4 + 3] = p00 + columns + 2
				weight[i * 4] = (1 - fu) * (1 - fv); weight[i * 4 + 1] = fu * (1 - fv); weight[i * 4 + 2] = (1 - fu) * fv; weight[i * 4 + 3] = fu * fv
			}
			val a = Array(n) { DoubleArray(n) }
			for (i in 0 until count) for (j in 0..3) for (k in 0..3) a[index[i * 4 + j]][index[i * 4 + k]] += (weight[i * 4 + j] * weight[i * 4 + k]).toDouble()
			// Smoothness: each control point's displacement near its neighbours' mean, weighted lightly against the points.
			val lambda = 1e-3 * max(1, count) / n
			for (r in 0..rows) for (c in 0..columns) {
				val p = r * (columns + 1) + c
				val neighbours = listOfNotNull((p - 1).takeIf { c > 0 }, (p + 1).takeIf { c < columns }, (p - columns - 1).takeIf { r > 0 }, (p + columns + 1).takeIf { r < rows })
				val row = DoubleArray(n); row[p] = 1.0; for (q in neighbours) row[q] -= 1.0 / neighbours.size
				for (j in 0 until n) if (row[j] != 0.0) for (k in 0 until n) if (row[k] != 0.0) a[j][k] += lambda * row[j] * row[k]
			}
			inverse = invert(a)
		}

		fun solve(offset: FloatArray): FloatArray {
			val bx = DoubleArray(n); val by = DoubleArray(n)
			for (i in 0 until count) for (j in 0..3) {
				bx[index[i * 4 + j]] += weight[i * 4 + j] * offset[i * 2].toDouble()
				by[index[i * 4 + j]] += weight[i * 4 + j] * offset[i * 2 + 1].toDouble()
			}
			return FloatArray(n * 2).also { out ->
				for (p in 0 until n) {
					var x = 0.0; var y = 0.0
					for (q in 0 until n) { x += inverse[p][q] * bx[q]; y += inverse[p][q] * by[q] }
					out[p * 2] = x.toFloat(); out[p * 2 + 1] = y.toFloat()
				}
			}
		}

		/** The largest distance between what [displacement] moves the points by and [offset]. */
		fun error(displacement: FloatArray, offset: FloatArray): Float {
			var worst = 0f
			for (i in 0 until count) {
				var x = 0f; var y = 0f
				for (j in 0..3) { x += weight[i * 4 + j] * displacement[index[i * 4 + j] * 2]; y += weight[i * 4 + j] * displacement[index[i * 4 + j] * 2 + 1] }
				worst = max(worst, hypot(x - offset[i * 2], y - offset[i * 2 + 1]))
			}
			return worst
		}

		private fun invert(m: Array<DoubleArray>): Array<DoubleArray> {
			val size = m.size
			val a = Array(size) { r -> DoubleArray(size * 2).also { row -> m[r].copyInto(row); row[size + r] = 1.0 } }
			for (col in 0 until size) {
				val pivot = (col until size).maxBy { kotlin.math.abs(a[it][col]) }
				val t = a[pivot]; a[pivot] = a[col]; a[col] = t
				val d = a[col][col].takeIf { kotlin.math.abs(it) > 1e-12 } ?: 1e-12
				for (k in 0 until size * 2) a[col][k] /= d
				for (r in 0 until size) if (r != col) {
					val f = a[r][col]
					if (f != 0.0) for (k in 0 until size * 2) a[r][k] -= f * a[col][k]
				}
			}
			return Array(size) { r -> DoubleArray(size) { a[r][size + it] } }
		}
	}
}
