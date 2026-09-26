package io.github.psd2live.core

import org.umamo.edit.MeshRefinementOps
import org.umamo.edit.MeshTopologyEdit
import org.umamo.edit.withMeshTopologyEdit
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.PuppetModel
import kotlin.math.hypot

/**
 * A joint band in canvas pixels: the joint, the unit axis across it and its half width.
 */
internal class JointBand(val x: Double, val y: Double, val nx: Double, val ny: Double, val half: Double)

/**
 * Gives a mesh enough vertices across each joint band to bend smoothly.
 *
 * A mesh bends only where it has vertices, and a generated mesh is as coarse as its outline allows, so
 * an elbow often falls inside one long triangle that can only fold along an edge. This inserts five
 * rows of vertices across every band - at both edges, the quarter points and the joint - each row as
 * wide as the mesh is there. The inserted vertices are affine combinations of the old ones, so the
 * picture does not move and every existing keyform follows through [withMeshTopologyEdit].
 *
 * Rows that already have a vertex nearby are skipped, so refining a refined mesh changes nothing.
 */
internal object SkeletonMeshRefine {
	private val rowOffsets = doubleArrayOf(-1.0, -0.5, 0.0, 0.5, 1.0)

	/** Largest number of points one row may add, however wide the mesh is. */
	private const val MAX_ROW_POINTS = 24

	/**
	 * Refines [drawableId] of [model] across [bands]. [canvas] is its rest vertices in canvas pixels, the
	 * space the bands are in. Returns the model and the refined mesh's rest vertices in the same space, or
	 * the inputs unchanged when nothing needed adding.
	 */
	fun refine(model: PuppetModel, drawableId: DrawableId, canvas: FloatArray, bands: List<JointBand>): Pair<PuppetModel, FloatArray> {
		val mesh = model.drawables.firstOrNull { it.id == drawableId }?.mesh ?: return model to canvas
		if (canvas.size != mesh.positions.size || bands.isEmpty()) return model to canvas
		val points = bands.flatMap { rowPoints(canvas, mesh.indices, it) }
		if (points.isEmpty()) return model to canvas
		val inserted = MeshRefinementOps.insertPoints(mesh, canvas, points, extend = false, edgeSnap = 0.5f) ?: return model to canvas
		val edit = inserted.result.edit
		val created = (mesh.vertexCount until edit.newMesh.vertexCount).toSet()
		if (created.isEmpty()) return model to canvas
		val flipped = MeshRefinementOps.flipTowardDelaunay(edit.newMesh, inserted.frame, created)
		val tidy = MeshTopologyEdit(DrawableMesh(edit.newMesh.positions, edit.newMesh.uvs, flipped), edit.vertexSources)
		return model.withMeshTopologyEdit(drawableId, tidy) to inserted.frame
	}

	/**
	 * The points one band asks for: [rowOffsets] rows across the band. Each row gets a vertex wherever it
	 * crosses the outline, so the silhouette bends at the row instead of running straight across the
	 * joint, and interior vertices between those crossings at most a third of the limb's width apart.
	 * Spots an existing vertex already covers are skipped.
	 */
	private fun rowPoints(canvas: FloatArray, indices: IntArray, band: JointBand): List<Pair<Float, Float>> {
		val tx = -band.ny
		val ty = band.nx
		val count = canvas.size / 2
		val s = DoubleArray(count) { (canvas[it * 2] - band.x) * band.nx + (canvas[it * 2 + 1] - band.y) * band.ny }
		val t = DoubleArray(count) { (canvas[it * 2] - band.x) * tx + (canvas[it * 2 + 1] - band.y) * ty }
		// The outline: edges used by a single triangle. Its crossings do not move when vertices are added
		// along the row, which is what makes a second pass find every spot already covered.
		val uses = HashMap<Long, Int>()
		for (i in indices.indices step 3) for (k in 0..2) {
			val a = indices[i + k]
			val b = indices[i + (k + 1) % 3]
			val key = minOf(a, b).toLong() shl 32 or maxOf(a, b).toLong()
			uses[key] = (uses[key] ?: 0) + 1
		}
		val edges = uses.filterValues { it == 1 }.keys
		val out = ArrayList<Pair<Float, Float>>()
		fun add(depth: Double, across: Double, clearance: Double) {
			val x = band.x + band.nx * depth + tx * across
			val y = band.y + band.ny * depth + ty * across
			if (covered(canvas, x, y, clearance) || out.any { hypot(it.first - x, it.second - y) < clearance }) return
			out += x.toFloat() to y.toFloat()
		}
		for (offset in rowOffsets) {
			val depth = offset * band.half
			val crossings = ArrayList<Double>()
			for (edge in edges) {
				val a = (edge ushr 32).toInt()
				val b = (edge and 0xffffffffL).toInt()
				val sa = s[a] - depth
				val sb = s[b] - depth
				if (sa == sb || sa * sb > 0.0) continue
				crossings += t[a] + (t[b] - t[a]) * (sa / (sa - sb))
			}
			if (crossings.size < 2) continue
			crossings.sort()
			val width = crossings.last() - crossings.first()
			val spacing = maxOf(3.0, minOf(band.half * 0.5, width / 3.0), width / MAX_ROW_POINTS)
			val clearance = minOf(spacing * 0.45, band.half * 0.2)
			for (across in crossings) add(depth, across, clearance)
			// Crossings pair up as the row enters and leaves the silhouette; fill only inside each span.
			for (k in 0 until crossings.size - 1 step 2) {
				val gap = crossings[k + 1] - crossings[k]
				val steps = (gap / spacing).toInt()
				for (j in 1 until steps + 1) {
					val across = crossings[k] + gap * j / (steps + 1)
					add(depth, across, clearance)
				}
			}
		}
		return out
	}

	private fun covered(canvas: FloatArray, x: Double, y: Double, clearance: Double): Boolean {
		for (i in 0 until canvas.size / 2) {
			if (hypot(canvas[i * 2] - x, canvas[i * 2 + 1] - y) < clearance) return true
		}
		return false
	}
}
