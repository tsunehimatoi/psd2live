package io.github.psd2live.core

import org.umamo.runtime.keyform.withAxisSeeded
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.KeyformAxis
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PartId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.WarpLatticeForm
import org.umamo.runtime.model.withDerivedRenderRoot
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Converts authored skeleton bones into Live2D Warp Deformers with rectangular grid regions
 * per bone segment, bending at the joints.
 */
object SkeletonConverter {
	private val bodyId = DeformerId("DeformBodyXY")

	fun apply(base: PuppetModel, spec: SkeletonSpec, frame: Bounds): PuppetModel {
		if (!spec.enabled || spec.bones.isEmpty() || base.deformers.none { it.id == bodyId }) return base
		var model = base
		val validDrawables = model.drawables.mapTo(HashSet()) { it.id.raw }
		val chains = buildChains(spec)

		// Remove any legacy skeleton deform paths
		model = model.copy(deformPaths = model.deformPaths.filterNot { it.id.startsWith("PathSkel_") })

		for (chain in chains) {
			val chainBones = chain.filter { !it.role.anchor }
			if (chainBones.isEmpty()) continue

			// Collect drawables bound to any bone in this chain
			val chainDrawableIds = chainBones.flatMap { it.drawableIds }.filter(validDrawables::contains).toSet()
			val chainDrawables = model.drawables.filter { it.id.raw in chainDrawableIds && it.mesh != null }
			if (chainDrawables.isEmpty()) continue

			val warpId = DeformerId("DeformWarpSkel_${chainBones.first().id}")
			val warp = buildChainWarp(chainBones, chainDrawables, warpId, frame)

			// Remap child drawable vertices into the Warp Deformer's [0, 1] x [0, 1] local coordinates
			val remappedDrawables = remapDrawablesToWarp(chainDrawables, warp, chainBones, frame)

			val remappedMap = remappedDrawables.associateBy { it.id }
			model = model.copy(
				deformers = model.deformers + warp,
				drawables = model.drawables.map { remappedMap[it.id] ?: it },
			)

			// Add parameters for each bone in the chain
			for (bone in chainBones) {
				model = withParameter(model, parameterFor(bone))
			}
		}

		model = addSquat(model, spec, frame)
		return model.withDerivedRenderRoot()
	}

	/** Groups non-anchor bones into connected bone chains (e.g. upper arm -> forearm -> hand). */
	internal fun buildChains(spec: SkeletonSpec): List<List<SkeletonBone>> {
		val bonesById = spec.bones.associateBy { it.id }
		val nonAnchor = spec.topological().filter { !it.role.anchor }
		val visited = mutableSetOf<String>()
		val chains = mutableListOf<List<SkeletonBone>>()

		val roots = nonAnchor.filter { b ->
			val p = b.parentId?.let(bonesById::get)
			p == null || p.role.anchor
		}

		fun traceChain(start: SkeletonBone) {
			if (!visited.add(start.id)) return
			val current = mutableListOf(start)
			var curr = start
			while (true) {
				val children = spec.children(curr.id).filter { !it.role.anchor && it.id !in visited }
				if (children.isEmpty()) break
				if (children.size == 1) {
					val next = children.single()
					visited += next.id
					current += next
					curr = next
				} else {
					for (child in children) {
						traceChain(child)
					}
					break
				}
			}
			// Chunk chain into subchains of at most 3 bones (Live2D Cubism recommendation: <= 3 params per deformer)
			for (chunk in current.chunked(3)) {
				chains += chunk
			}
		}

		for (root in roots) {
			traceChain(root)
		}
		for (b in nonAnchor) {
			if (b.id !in visited) traceChain(b)
		}
		return chains
	}

	private class JointGeometry(
		val joints: List<Pair<Float, Float>>,
		val extHead: Pair<Float, Float>,
		val extTail: Pair<Float, Float>,
		val boneNormals: List<Pair<Float, Float>>,
		val boneWidths: List<Float>,
		val jointNormals: List<Pair<Float, Float>>,
		val jointWidths: List<Float>,
	)

	private fun computeJointGeometry(
		bones: List<SkeletonBone>,
		drawables: List<org.umamo.runtime.model.Drawable>,
		frame: Bounds,
	): JointGeometry {
		val n = bones.size
		val joints = mutableListOf<Pair<Float, Float>>()
		joints += bones.first().headX to bones.first().headY
		for (b in bones) {
			joints += b.tailX to b.tailY
		}

		val boneNormals = mutableListOf<Pair<Float, Float>>()
		val boneLengths = mutableListOf<Float>()
		val boneTangents = mutableListOf<Pair<Float, Float>>()

		for (i in 0 until n) {
			val j0 = joints[i]
			val j1 = joints[i + 1]
			val dx = j1.first - j0.first
			val dy = j1.second - j0.second
			val len = max(hypot(dx, dy), 1f)
			boneLengths += len
			val tx = dx / len
			val ty = dy / len
			boneTangents += tx to ty
			boneNormals += -ty to tx
		}

		// Sample vertices in canvas space
		val canvasVerts = mutableListOf<Pair<Float, Float>>()
		for (d in drawables) {
			val mesh = d.mesh ?: continue
			val pos = mesh.positions
			val isParentBody = d.parentDeformerId == bodyId
			for (j in 0 until pos.size step 2) {
				val vx = if (isParentBody) frame.left + pos[j] * frame.width else pos[j]
				val vy = if (isParentBody) frame.top + pos[j + 1] * frame.height else pos[j + 1]
				canvasVerts += vx to vy
			}
		}

		// Calculate bone widths from enclosed vertices
		val boneWidths = MutableList(n) { 30f }
		for (i in 0 until n) {
			val j0 = joints[i]
			val t = boneTangents[i]
			val norm = boneNormals[i]
			val len = boneLengths[i]
			var maxDist = 0f
			for (v in canvasVerts) {
				val proj = (v.first - j0.first) * t.first + (v.second - j0.second) * t.second
				if (proj >= -len * 0.2f && proj <= len * 1.2f) {
					val dist = abs((v.first - j0.first) * norm.first + (v.second - j0.second) * norm.second)
					if (dist > maxDist) maxDist = dist
				}
			}
			boneWidths[i] = max(max(maxDist * 1.35f, 30f), len * 0.25f)
		}
		val maxGlobalWidth = boneWidths.maxOrNull() ?: 30f
		for (i in 0 until n) {
			boneWidths[i] = max(boneWidths[i], maxGlobalWidth * 0.65f)
		}

		// Joint normals and widths
		val jointNormals = mutableListOf<Pair<Float, Float>>()
		val jointWidths = mutableListOf<Float>()
		for (i in 0..n) {
			when (i) {
				0 -> {
					jointNormals += boneNormals[0]
					jointWidths += boneWidths[0]
				}
				n -> {
					jointNormals += boneNormals[n - 1]
					jointWidths += boneWidths[n - 1]
				}
				else -> {
					val n0 = boneNormals[i - 1]
					val n1 = boneNormals[i]
					val sx = n0.first + n1.first
					val sy = n0.second + n1.second
					val sLen = hypot(sx, sy)
					val jNorm = if (sLen > 1e-4f) (sx / sLen) to (sy / sLen) else n0
					jointNormals += jNorm
					jointWidths += max(boneWidths[i - 1], boneWidths[i])
				}
			}
		}

		// Extensions at head and tail
		val extHeadLen = min(max(boneLengths[0] * 0.2f, 20f), 50f)
		val extTailLen = min(max(boneLengths[n - 1] * 0.2f, 20f), 50f)
		val extHead = (joints[0].first - boneTangents[0].first * extHeadLen) to
			(joints[0].second - boneTangents[0].second * extHeadLen)
		val extTail = (joints[n].first + boneTangents[n - 1].first * extTailLen) to
			(joints[n].second + boneTangents[n - 1].second * extTailLen)

		return JointGeometry(joints, extHead, extTail, boneNormals, boneWidths, jointNormals, jointWidths)
	}

	private fun buildRowCentersAndNormals(
		geo: JointGeometry,
		n: Int,
		anglesRad: FloatArray,
	): List<Triple<Pair<Float, Float>, Pair<Float, Float>, Float>> {
		// Forward kinematics
		val cumRot = FloatArray(n)
		var accum = 0f
		for (i in 0 until n) {
			accum += anglesRad[i]
			cumRot[i] = accum
		}

		val posedJoints = ArrayList<Pair<Float, Float>>(n + 1)
		posedJoints += geo.joints[0]
		for (i in 0 until n) {
			val j0 = geo.joints[i]
			val j1 = geo.joints[i + 1]
			val dx = j1.first - j0.first
			val dy = j1.second - j0.second
			val rot = rotateVec(dx, dy, cumRot[i])
			val prev = posedJoints[i]
			posedJoints += (prev.first + rot.first) to (prev.second + rot.second)
		}

		val extHdx = geo.extHead.first - geo.joints[0].first
		val extHdy = geo.extHead.second - geo.joints[0].second
		val extHrot = rotateVec(extHdx, extHdy, anglesRad[0])
		val posedExtHead = (posedJoints[0].first + extHrot.first) to (posedJoints[0].second + extHrot.second)

		val extTdx = geo.extTail.first - geo.joints[n].first
		val extTdy = geo.extTail.second - geo.joints[n].second
		val extTrot = rotateVec(extTdx, extTdy, cumRot[n - 1])
		val posedExtTail = (posedJoints[n].first + extTrot.first) to (posedJoints[n].second + extTrot.second)

		val rows = 2 * n
		val result = ArrayList<Triple<Pair<Float, Float>, Pair<Float, Float>, Float>>(rows + 1)

		for (r in 0..rows) {
			val center: Pair<Float, Float>
			val norm: Pair<Float, Float>
			val width: Float
			when {
				r == 0 -> {
					center = posedExtHead
					norm = rotateVec(geo.jointNormals[0].first, geo.jointNormals[0].second, anglesRad[0])
					width = geo.jointWidths[0]
				}
				r == rows -> {
					center = posedExtTail
					norm = rotateVec(geo.jointNormals[n].first, geo.jointNormals[n].second, cumRot[n - 1])
					width = geo.jointWidths[n]
				}
				r % 2 == 0 -> {
					val i = r / 2
					center = posedJoints[i]
					val n0 = rotateVec(geo.boneNormals[i - 1].first, geo.boneNormals[i - 1].second, cumRot[i - 1])
					val n1 = rotateVec(geo.boneNormals[i].first, geo.boneNormals[i].second, cumRot[i])
					val sx = n0.first + n1.first
					val sy = n0.second + n1.second
					val sLen = hypot(sx, sy)
					norm = if (sLen > 1e-4f) (sx / sLen) to (sy / sLen) else n0
					width = geo.jointWidths[i]
				}
				else -> {
					val i = r / 2
					center = ((posedJoints[i].first + posedJoints[i + 1].first) * 0.5f) to
						((posedJoints[i].second + posedJoints[i + 1].second) * 0.5f)
					norm = rotateVec(geo.boneNormals[i].first, geo.boneNormals[i].second, cumRot[i])
					width = geo.boneWidths[i]
				}
			}
			result += Triple(center, norm, width)
		}
		return result
	}

	private fun computeControlPoints(
		rowSpecs: List<Triple<Pair<Float, Float>, Pair<Float, Float>, Float>>,
		cols: Int,
		frame: Bounds,
	): FloatArray {
		val rows = rowSpecs.size - 1
		val totalPoints = (rows + 1) * (cols + 1)
		val cp = FloatArray(totalPoints * 2)
		var at = 0
		for (r in 0..rows) {
			val (center, norm, width) = rowSpecs[r]
			for (c in 0..cols) {
				val s = c.toFloat() / cols.toFloat()
				val offset = (2f * s - 1f) * width
				val px = center.first + norm.first * offset
				val py = center.second + norm.second * offset
				cp[at++] = (px - frame.left) / frame.width.coerceAtLeast(1e-4f)
				cp[at++] = (py - frame.top) / frame.height.coerceAtLeast(1e-4f)
			}
		}
		return cp
	}

	private fun buildChainWarp(
		bones: List<SkeletonBone>,
		drawables: List<org.umamo.runtime.model.Drawable>,
		warpId: DeformerId,
		frame: Bounds,
	): Deformer.Warp {
		val n = bones.size
		val geo = computeJointGeometry(bones, drawables, frame)
		val cols = 2
		val rows = 2 * n

		val axes = bones.map { b ->
			val param = parameterFor(b)
			KeyformAxis(param.id, floatArrayOf(param.min, 0f, param.max))
		}

		fun generateLattice(coord: IntArray): WarpLatticeForm {
			val anglesRad = FloatArray(n) { i ->
				val bone = bones[i]
				val keyIndex = coord[i]
				val angleDeg = axes[i].keys[keyIndex] * bone.direction
				(angleDeg * Math.PI / 180.0).toFloat()
			}
			val rowSpecs = buildRowCentersAndNormals(geo, n, anglesRad)
			val cp = computeControlPoints(rowSpecs, cols, frame)
			return WarpLatticeForm(cp)
		}

		// Build KeyformGrid cells across all axes
		val strides = IntArray(axes.size) { 1 }
		for (i in axes.size - 2 downTo 0) {
			strides[i] = strides[i + 1] * axes[i + 1].keys.size
		}
		val totalCells = axes.fold(1) { acc, a -> acc * a.keys.size }

		val cells = (0 until totalCells).map { linearIndex ->
			val coord = IntArray(axes.size)
			var rem = linearIndex
			for (i in axes.indices) {
				coord[i] = rem / strides[i]
				rem %= strides[i]
			}
			KeyformCell(coord, generateLattice(coord))
		}

		val grid = KeyformGrid(axes, cells)
		return Deformer.Warp(
			id = warpId,
			name = bones.first().name + " Warp",
			parent = bodyId,
			partId = PartId("PartBody"),
			rows = rows,
			columns = cols,
			isQuadTransform = true,
			geometryGrid = grid,
		)
	}

	private fun remapDrawablesToWarp(
		drawables: List<org.umamo.runtime.model.Drawable>,
		warp: Deformer.Warp,
		bones: List<SkeletonBone>,
		frame: Bounds,
	): List<org.umamo.runtime.model.Drawable> {
		val neutralLattice = warp.geometryGrid?.cells?.firstOrNull { cell ->
			cell.coordinate.all { it == 1 } // neutral key at index 1 (value 0f)
		}?.form ?: warp.geometryGrid!!.cells.first().form

		val cp = neutralLattice.controlPoints
		val rows = warp.rows
		val cols = warp.columns
		val ptsPerRow = cols + 1

		// Precompute cell corner positions in parent local space
		fun getCp(r: Int, c: Int): Pair<Float, Float> {
			val idx = (r * ptsPerRow + c) * 2
			return cp[idx] to cp[idx + 1]
		}

		return drawables.map { drawable ->
			val mesh = drawable.mesh ?: return@map drawable
			val pos = mesh.positions
			val newPos = FloatArray(pos.size)
			val isParentBody = drawable.parentDeformerId == bodyId

			for (j in 0 until pos.size step 2) {
				// Canvas coordinates
				val canvasX = if (isParentBody) frame.left + pos[j] * frame.width else pos[j]
				val canvasY = if (isParentBody) frame.top + pos[j + 1] * frame.height else pos[j + 1]
				// Target in body local coordinates
				val targetX = (canvasX - frame.left) / frame.width.coerceAtLeast(1e-4f)
				val targetY = (canvasY - frame.top) / frame.height.coerceAtLeast(1e-4f)

				// Find best cell
				var bestDistSq = Float.MAX_VALUE
				var bestU = 0.5f
				var bestV = 0.5f

				for (r in 0 until rows) {
					for (c in 0 until cols) {
						val p00 = getCp(r, c)
						val p10 = getCp(r, c + 1)
						val p01 = getCp(r + 1, c)
						val p11 = getCp(r + 1, c + 1)

						val (s, t) = invertBilinear(
							p00.first, p00.second,
							p10.first, p10.second,
							p01.first, p01.second,
							p11.first, p11.second,
							targetX, targetY,
						)

						val evalX = (1f - s) * (1f - t) * p00.first + s * (1f - t) * p10.first +
							(1f - s) * t * p01.first + s * t * p11.first
						val evalY = (1f - s) * (1f - t) * p00.second + s * (1f - t) * p10.second +
							(1f - s) * t * p01.second + s * t * p11.second

						val dx = targetX - evalX
						val dy = targetY - evalY
						val distSq = dx * dx + dy * dy

						if (distSq < bestDistSq) {
							bestDistSq = distSq
							bestU = (c + s) / cols.toFloat()
							bestV = (r + t) / rows.toFloat()
						}
					}
				}

				newPos[j] = bestU.coerceIn(0.0001f, 0.9999f)
				newPos[j + 1] = bestV.coerceIn(0.0001f, 0.9999f)
			}

			drawable.copy(
				parentDeformerId = warp.id,
				mesh = DrawableMesh(positions = newPos, uvs = mesh.uvs, indices = mesh.indices),
				// Clear any path-baked deltas since deformation is now on the Warp Deformer
				geometryGrid = null,
			)
		}
	}

	internal fun invertBilinear(
		p00x: Float, p00y: Float,
		p10x: Float, p10y: Float,
		p01x: Float, p01y: Float,
		p11x: Float, p11y: Float,
		vx: Float, vy: Float,
	): Pair<Float, Float> {
		val ax = (p10x - p00x + p11x - p01x) * 0.5f
		val ay = (p10y - p00y + p11y - p01y) * 0.5f
		val bx = (p01x - p00x + p11x - p10x) * 0.5f
		val by = (p01y - p00y + p11y - p10y) * 0.5f
		val rx = vx - p00x
		val ry = vy - p00y
		val aLenSq = ax * ax + ay * ay
		val bLenSq = bx * bx + by * by
		var s = if (aLenSq > 1e-8f) ((rx * ax + ry * ay) / aLenSq).coerceIn(0f, 1f) else 0.5f
		var t = if (bLenSq > 1e-8f) ((rx * bx + ry * by) / bLenSq).coerceIn(0f, 1f) else 0.5f

		for (iter in 0 until 8) {
			val curX = (1f - s) * (1f - t) * p00x + s * (1f - t) * p10x + (1f - s) * t * p01x + s * t * p11x
			val curY = (1f - s) * (1f - t) * p00y + s * (1f - t) * p10y + (1f - s) * t * p01y + s * t * p11y
			val errX = vx - curX
			val errY = vy - curY
			if (errX * errX + errY * errY < 1e-10f) break

			val dsX = (1f - t) * (p10x - p00x) + t * (p11x - p01x)
			val dsY = (1f - t) * (p10y - p00y) + t * (p11y - p01y)
			val dtX = (1f - s) * (p01x - p00x) + s * (p11x - p10x)
			val dtY = (1f - s) * (p01y - p00y) + s * (p11y - p10y)

			val det = dsX * dtY - dsY * dtX
			if (abs(det) < 1e-8f) break

			val deltaS = (errX * dtY - errY * dtX) / det
			val deltaT = (dsX * errY - dsY * errX) / det

			s = (s + deltaS).coerceIn(-0.2f, 1.2f)
			t = (t + deltaT).coerceIn(-0.2f, 1.2f)
		}
		return s.coerceIn(0f, 1f) to t.coerceIn(0f, 1f)
	}

	private fun rotateVec(x: Float, y: Float, rad: Float): Pair<Float, Float> {
		val c = cos(rad)
		val s = sin(rad)
		return (x * c - y * s) to (x * s + y * c)
	}

	private fun addSquat(model: PuppetModel, spec: SkeletonSpec, frame: Bounds): PuppetModel {
		if (spec.bones.none { it.role == BoneRole.THIGH || it.role == BoneRole.SHIN }) return model
		val parameter = Parameter(ParameterId("ParamSquat"), "Squat", 0f, 1f, 0f)
		val drop = (frame.height * 0.018f).coerceIn(3f, 28f)
		val deformers = model.deformers.map { deformer ->
			when (deformer) {
				is Deformer.Warp -> if (deformer.id == bodyId && deformer.geometryGrid != null) {
					val grid = deformer.geometryGrid.withAxisSeeded(
						parameter,
						WarpLatticeForm(deformer.geometryGrid.cells.first().form.controlPoints)
					) ?: return@map deformer
					val axis = grid.axes.indexOfFirst { it.parameterId == parameter.id }
					deformer.copy(geometryGrid = KeyformGrid(grid.axes, grid.cells.map { cell ->
						val value = grid.axes[axis].keys[cell.coordinate[axis]]
						val points = cell.form.controlPoints.copyOf()
						for (i in 1 until points.size step 2) points[i] += value * drop
						KeyformCell(cell.coordinate, WarpLatticeForm(points))
					}))
				} else deformer
				is Deformer.Rotation -> deformer
			}
		}
		return withParameter(model.copy(deformers = deformers), parameter)
	}

	private fun parameterFor(bone: SkeletonBone): Parameter {
		val limit = bone.role.maxAngle.coerceAtLeast(1f)
		return Parameter(ParameterId(bone.parameterId), bone.name, -limit, limit, 0f)
	}

	private fun withParameter(model: PuppetModel, parameter: Parameter): PuppetModel =
		if (model.parameters.any { it.id == parameter.id }) model else model.copy(parameters = model.parameters + parameter)
}
