package org.umamo.render.pick

import org.umamo.runtime.model.DrawableId
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Minimum atlas-texel alpha (0..1) for a click to count as a hit — rejects the transparent triangle
 *  overhang that art meshes carry past the visible art edge, while keeping faint-but-real art (~10/255). */
const val PICK_ALPHA_THRESHOLD = 0.04f

// Centrality (how deep inside the opaque region a hit is) ray-march tuning, in texture-space texels.
private const val CENTRALITY_RAY_COUNT = 16
private const val CENTRALITY_MAX_RADIUS_TEXELS = 64f
private const val CENTRALITY_STEP_TEXELS = 2f

/**
 * One opaque candidate under the cursor: the drawable, its front-rank (higher = drawn more in front),
 * and its centrality (0..1, higher = the click is deeper inside the opaque art). Used to build the
 * overlap-picker popup.
 *
 * カーソル下の不透明候補。前面ランクと中心度を持つ。
 */
data class PickCandidate(val id: DrawableId, val frontRank: Float, val centrality: Float)

/**
 * Reports whether a point lies inside (or on an edge of) a triangle, using the sign of the three edge
 * cross products. Works for either winding order; a point exactly on an edge counts as inside. A
 * degenerate (zero-area) triangle accepts only points lying on its collapsed span.
 *
 * 点が三角形の内部（辺上を含む）にあるかを、3辺の外積の符号で判定する。巻き方向に依存しない。
 *
 * @param Float pointX  The test point X.
 * @param Float pointY  The test point Y.
 * @param Float firstX  First vertex X.
 * @param Float firstY  First vertex Y.
 * @param Float secondX Second vertex X.
 * @param Float secondY Second vertex Y.
 * @param Float thirdX  Third vertex X.
 * @param Float thirdY  Third vertex Y.
 * @return Boolean True when the point is inside or on the triangle.
 */
fun pointInTriangle(
	pointX: Float,
	pointY: Float,
	firstX: Float,
	firstY: Float,
	secondX: Float,
	secondY: Float,
	thirdX: Float,
	thirdY: Float,
): Boolean {
	val sideAb = edgeSide(pointX, pointY, firstX, firstY, secondX, secondY)
	val sideBc = edgeSide(pointX, pointY, secondX, secondY, thirdX, thirdY)
	val sideCa = edgeSide(pointX, pointY, thirdX, thirdY, firstX, firstY)
	val hasNegative = sideAb < 0f || sideBc < 0f || sideCa < 0f
	val hasPositive = sideAb > 0f || sideBc > 0f || sideCa > 0f
	// Inside when the point is not strictly on both sides of the edges (zero counts as on-edge).
	return !(hasNegative && hasPositive)
}

/**
 * The signed area term for the point relative to the directed edge (start to end): positive on one
 * side, negative on the other, zero on the line. The sign, not the magnitude, is what
 * [pointInTriangle] consumes.
 *
 * 点と有向辺の符号付き面積項。
 *
 * @param Float pointX The test point X.
 * @param Float pointY The test point Y.
 * @param Float startX Edge start X.
 * @param Float startY Edge start Y.
 * @param Float endX   Edge end X.
 * @param Float endY   Edge end Y.
 * @return Float The signed area term.
 */
private fun edgeSide(
	pointX: Float,
	pointY: Float,
	startX: Float,
	startY: Float,
	endX: Float,
	endY: Float,
): Float = (pointX - endX) * (startY - endY) - (startX - endX) * (pointY - endY)

/**
 * The barycentric weights (w0, w1, w2) of a point relative to a triangle, used to interpolate
 * per-vertex attributes (the UV) at a hit point. Weights sum to 1; a degenerate (zero-area) triangle
 * returns equal thirds rather than dividing by zero.
 *
 * 三角形に対する点の重心座標。頂点属性（UV）の補間に使う。退化三角形は 1/3 ずつを返す。
 *
 * @param Float pointX The point X.
 * @param Float pointY The point Y.
 * @param Float firstX  First vertex X.
 * @param Float firstY  First vertex Y.
 * @param Float secondX Second vertex X.
 * @param Float secondY Second vertex Y.
 * @param Float thirdX  Third vertex X.
 * @param Float thirdY  Third vertex Y.
 * @return FloatArray The three weights (w0, w1, w2).
 */
fun barycentricWeights(
	pointX: Float,
	pointY: Float,
	firstX: Float,
	firstY: Float,
	secondX: Float,
	secondY: Float,
	thirdX: Float,
	thirdY: Float,
): FloatArray {
	val denominator = (secondY - thirdY) * (firstX - thirdX) + (thirdX - secondX) * (firstY - thirdY)
	if (abs(denominator) < 1e-7f) {
		return floatArrayOf(1f / 3f, 1f / 3f, 1f / 3f)
	}
	val weightFirst = ((secondY - thirdY) * (pointX - thirdX) + (thirdX - secondX) * (pointY - thirdY)) / denominator
	val weightSecond = ((thirdY - firstY) * (pointX - thirdX) + (firstX - thirdX) * (pointY - thirdY)) / denominator
	val weightThird = 1f - weightFirst - weightSecond
	return floatArrayOf(weightFirst, weightSecond, weightThird)
}

/**
 * Finds the FRONT-MOST drawable whose deformed mesh contains the point AND whose texel there is opaque,
 * or null on a miss. Candidates are the drawables present in [worldPositions] (the evaluator's visible
 * set) that also have triangle [indices], [meshUvs], and a [frontRank] (so a drawable not actually drawn
 * this frame is skipped). For each, the first containing triangle's UV is barycentric-interpolated and
 * [sampleAlpha]d; a texel below [alphaThreshold] is rejected (the transparent overhang). Among the
 * opaque hits, the highest [frontRank] wins — the renderer's resolved paint order, which is correct
 * where the raw draw-order scalar (ignoring the group hierarchy) is not.
 *
 * 変形メッシュが点を含み、かつその位置のテクセルが不透明な最前面のドロウアブルを返す。前面ランクは
 * レンダラの解決済み描画順に基づく（生のドローオーダー値ではない）。
 *
 * @param Float worldX                               The point X in post-deform world space.
 * @param Float worldY                               The point Y in post-deform world space.
 * @param Map<DrawableId, FloatArray> worldPositions Interleaved (x,y) deformed vertices per drawable.
 * @param Map<DrawableId, IntArray> indices          Triangle index triples per drawable.
 * @param Map<DrawableId, FloatArray> meshUvs        Interleaved (u,v) per vertex per drawable.
 * @param Map<DrawableId, Float> frontRank           Front rank per drawn drawable (higher = more front).
 * @param Function sampleAlpha                        (id, u, v) -> texel alpha 0..1 (untextured = 1).
 * @param Float alphaThreshold                        Minimum alpha to count as a hit.
 * @return DrawableId The front-most opaque hit, or null on a miss.
 */
fun pickDrawable(
	worldX: Float,
	worldY: Float,
	worldPositions: Map<DrawableId, FloatArray>,
	indices: Map<DrawableId, IntArray>,
	meshUvs: Map<DrawableId, FloatArray>,
	frontRank: Map<DrawableId, Float>,
	sampleAlpha: (DrawableId, Float, Float) -> Float,
	alphaThreshold: Float = PICK_ALPHA_THRESHOLD,
): DrawableId? {
	var bestId: DrawableId? = null
	var bestRank = Float.NEGATIVE_INFINITY
	for ((drawableId, positions) in worldPositions) {
		val triangleIndices = indices[drawableId] ?: continue
		val uvs = meshUvs[drawableId] ?: continue
		val rank = frontRank[drawableId] ?: continue
		val uv = hitUv(worldX, worldY, positions, triangleIndices, uvs) ?: continue
		if (sampleAlpha(drawableId, uv[0], uv[1]) < alphaThreshold) {
			continue
		}
		if (bestId == null || rank > bestRank) {
			bestId = drawableId
			bestRank = rank
		}
	}
	return bestId
}

/**
 * All opaque candidates under the point, FRONT-TO-BACK (front first), each with its centrality. Uses the
 * same hit + alpha gate as [pickDrawable]; built for the overlap-picker popup. Centrality (how deep the
 * hit is inside the opaque art) is computed per survivor from its atlas size via [atlasSizeOf]; an
 * untextured drawable (null size) scores full centrality.
 *
 * 点の下の不透明候補すべてを前面順に返す（各々の中心度付き）。重なり選択ポップアップ用。
 *
 * @param Float worldX                               The point X in post-deform world space.
 * @param Float worldY                               The point Y in post-deform world space.
 * @param Map<DrawableId, FloatArray> worldPositions Interleaved (x,y) deformed vertices per drawable.
 * @param Map<DrawableId, IntArray> indices          Triangle index triples per drawable.
 * @param Map<DrawableId, FloatArray> meshUvs        Interleaved (u,v) per vertex per drawable.
 * @param Map<DrawableId, Float> frontRank           Front rank per drawn drawable (higher = more front).
 * @param Function atlasSizeOf                        (id) -> the drawable's atlas (width, height), or null if untextured.
 * @param Function sampleAlpha                        (id, u, v) -> texel alpha 0..1 (untextured = 1).
 * @param Float alphaThreshold                        Minimum alpha to count as a hit.
 * @return List the opaque candidates, front-to-back.
 */
fun pickAllDrawables(
	worldX: Float,
	worldY: Float,
	worldPositions: Map<DrawableId, FloatArray>,
	indices: Map<DrawableId, IntArray>,
	meshUvs: Map<DrawableId, FloatArray>,
	frontRank: Map<DrawableId, Float>,
	atlasSizeOf: (DrawableId) -> Pair<Int, Int>?,
	sampleAlpha: (DrawableId, Float, Float) -> Float,
	alphaThreshold: Float = PICK_ALPHA_THRESHOLD,
): List<PickCandidate> {
	val candidates = mutableListOf<PickCandidate>()
	for ((drawableId, positions) in worldPositions) {
		val triangleIndices = indices[drawableId] ?: continue
		val uvs = meshUvs[drawableId] ?: continue
		val rank = frontRank[drawableId] ?: continue
		val uv = hitUv(worldX, worldY, positions, triangleIndices, uvs) ?: continue
		if (sampleAlpha(drawableId, uv[0], uv[1]) < alphaThreshold) {
			continue
		}
		val atlasSize = atlasSizeOf(drawableId)
		val centrality =
			if (atlasSize == null) {
				1f
			} else {
				centralityAt(drawableId, uv[0], uv[1], atlasSize.first, atlasSize.second, sampleAlpha, alphaThreshold)
			}
		candidates += PickCandidate(drawableId, rank, centrality)
	}
	candidates.sortByDescending { candidate -> candidate.frontRank }
	return candidates
}

/**
 * Approximate "depth inside the opaque region" at a UV hit, in 0..1. Casts [CENTRALITY_RAY_COUNT] rays
 * outward in texture space (stepping [CENTRALITY_STEP_TEXELS]) up to [CENTRALITY_MAX_RADIUS_TEXELS],
 * finds the nearest texel where alpha drops below [alphaThreshold], and returns that min distance
 * normalised by the cap (1 = no transparent texel within the cap, i.e. deep inside). The middle of a
 * large opaque region scores high; a hit near a transparent boundary scores low. Approximate
 * (ray-sampled, not an exact distance transform), and it cannot tell apart adjacently-packed atlas art.
 *
 * UV ヒット位置が不透明領域のどれだけ奥かを近似する（0..1）。テクスチャ空間で放射状にレイを飛ばし、
 * 最も近い透明テクセルまでの距離を上限で正規化する。
 *
 * @param DrawableId id          The drawable being scored.
 * @param Float u                The hit U (full-atlas).
 * @param Float v                The hit V (full-atlas).
 * @param Int atlasWidth         The drawable's atlas page width in texels.
 * @param Int atlasHeight        The drawable's atlas page height in texels.
 * @param Function sampleAlpha   (id, u, v) -> texel alpha 0..1.
 * @param Float alphaThreshold   The transparent cutoff.
 * @return Float The centrality, 0..1.
 */
fun centralityAt(
	id: DrawableId,
	u: Float,
	v: Float,
	atlasWidth: Int,
	atlasHeight: Int,
	sampleAlpha: (DrawableId, Float, Float) -> Float,
	alphaThreshold: Float = PICK_ALPHA_THRESHOLD,
): Float {
	if (atlasWidth <= 0 || atlasHeight <= 0) {
		return 1f
	}
	var minRadius = CENTRALITY_MAX_RADIUS_TEXELS
	for (rayIndex in 0 until CENTRALITY_RAY_COUNT) {
		val angle = 2.0 * PI * rayIndex / CENTRALITY_RAY_COUNT
		val directionX = cos(angle).toFloat()
		val directionY = sin(angle).toFloat()
		var radius = CENTRALITY_STEP_TEXELS
		while (radius <= CENTRALITY_MAX_RADIUS_TEXELS) {
			val sampleU = u + directionX * radius / atlasWidth
			val sampleV = v + directionY * radius / atlasHeight
			if (sampleAlpha(id, sampleU, sampleV) < alphaThreshold) {
				if (radius < minRadius) {
					minRadius = radius
				}
				break
			}
			radius += CENTRALITY_STEP_TEXELS
		}
	}
	return (minRadius / CENTRALITY_MAX_RADIUS_TEXELS).coerceIn(0f, 1f)
}

/**
 * The barycentric-interpolated (u, v) at the first triangle of a drawable's mesh that contains the point,
 * or null when no triangle does. Index triples address vertices in the interleaved [positions] and [uvs]
 * arrays (vertex n at index 2n, 2n+1). Shared by [pickDrawable] and [pickAllDrawables].
 *
 * 点を含む最初の三角形での補間 UV を返す（無ければ null）。
 *
 * @param Float worldX             The point X.
 * @param Float worldY             The point Y.
 * @param FloatArray positions     Interleaved (x,y) deformed vertices.
 * @param IntArray triangleIndices Triangle index triples.
 * @param FloatArray uvs           Interleaved (u,v) per vertex.
 * @return FloatArray (u, v) at the hit, or null.
 */
private fun hitUv(
	worldX: Float,
	worldY: Float,
	positions: FloatArray,
	triangleIndices: IntArray,
	uvs: FloatArray,
): FloatArray? {
	var triangleStart = 0
	while (triangleStart + 2 < triangleIndices.size) {
		val firstVertex = triangleIndices[triangleStart] * 2
		val secondVertex = triangleIndices[triangleStart + 1] * 2
		val thirdVertex = triangleIndices[triangleStart + 2] * 2
		if (
			firstVertex + 1 < positions.size &&
			secondVertex + 1 < positions.size &&
			thirdVertex + 1 < positions.size &&
			firstVertex + 1 < uvs.size &&
			secondVertex + 1 < uvs.size &&
			thirdVertex + 1 < uvs.size
		) {
			val ax = positions[firstVertex]
			val ay = positions[firstVertex + 1]
			val bx = positions[secondVertex]
			val by = positions[secondVertex + 1]
			val cx = positions[thirdVertex]
			val cy = positions[thirdVertex + 1]
			if (pointInTriangle(worldX, worldY, ax, ay, bx, by, cx, cy)) {
				val weights = barycentricWeights(worldX, worldY, ax, ay, bx, by, cx, cy)
				val hitU = weights[0] * uvs[firstVertex] + weights[1] * uvs[secondVertex] + weights[2] * uvs[thirdVertex]
				val hitV = weights[0] * uvs[firstVertex + 1] + weights[1] * uvs[secondVertex + 1] + weights[2] * uvs[thirdVertex + 1]
				return floatArrayOf(hitU, hitV)
			}
		}
		triangleStart += 3
	}
	return null
}