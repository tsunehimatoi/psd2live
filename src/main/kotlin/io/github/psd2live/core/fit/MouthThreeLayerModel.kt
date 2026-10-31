package io.github.psd2live.core.fit

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.InflaterInputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Three-layer lip contour deformation model:
 * 1. Meta-deformation transfer between normalized 65-station upper and lower lip curves and a character's rest lip
 *    contours, preserving exact drawn geometry at neutral open (`open = 1, form = 0`), exact zero-gap seam closure when
 *    shut (`open = 0`), and maxilla-anchored jaw rise on wide-open lip-skin artwork.
 * 2. A 25-axis principal deformation basis spanning coupled bilateral, unilateral, vowel, funnel, pucker, and lip-parting
 *    shapes without corner spikes or self-intersections.
 * 3. User-parameter evaluation over `(open, form, press)` plus additive articulatory and facial-capture modes.
 */
internal class MouthThreeLayerModel private constructor(
	val stations: Int,
	val axisCount: Int,
	private val orig65: FloatArray,
	private val shut65: FloatArray,
	private val axes65: FloatArray,
	private val openKeys: FloatArray,
	private val formKeys: FloatArray,
	private val pressKeys: FloatArray,
	private val gridZ: FloatArray,
	private val modesZ: Array<FloatArray>,
	private val jaw65: FloatArray,
) {
	val uDense: FloatArray = FloatArray(stations) { i -> i.toFloat() / (stations - 1) }

	/**
	 * Evaluates a normalized 65-station `[upper(x, y), lower(x, y)]` lip contour (`2 * stations * 2` floats) for the
	 * given user-space controls.
	 */
	fun evaluateNormalized(
		openY: Float = 1f,
		form: Float = 0f,
		press: Float = 0f,
		mouthX: Float = 0f,
		width: Float = 0f,
		funnel: Float = 0f,
		shrug: Float = 0f,
		jaw: Float = 0f,
		cornerLUp: Float = 0f,
		cornerLDown: Float = 0f,
		cornerRUp: Float = 0f,
		cornerRDown: Float = 0f,
		cornerLOut: Float = 0f,
		cornerROut: Float = 0f,
		puckerL: Float = 0f,
		puckerR: Float = 0f,
		vowelMode: Int = -1,
		vowelWeight: Float = 0f,
	): FloatArray {
		val openClamped = openY.coerceIn(0f, 1f)
		val z = interpolateGridZ(openClamped, form, press.coerceIn(-1f, 1f))

		fun addMode(modeIdx: Int, weight: Float) {
			if (abs(weight) <= 1e-6f) return
			val m = modesZ[modeIdx]
			for (k in 0 until axisCount) z[k] += weight * m[k]
		}

		if (mouthX < 0f) addMode(MODE_MOUTH_X_NEG, -mouthX) else if (mouthX > 0f) addMode(MODE_MOUTH_X_POS, mouthX)
		if (width < 0f) addMode(MODE_PUCKER, -width) else if (width > 0f) addMode(MODE_WIDEN, width)
		addMode(MODE_FUNNEL, max(0f, funnel))
		addMode(MODE_SHRUG, max(0f, shrug))
		addMode(MODE_CORNER_L_UP, max(0f, cornerLUp))
		addMode(MODE_CORNER_L_DOWN, max(0f, cornerLDown))
		addMode(MODE_CORNER_R_UP, max(0f, cornerRUp))
		addMode(MODE_CORNER_R_DOWN, max(0f, cornerRDown))
		addMode(MODE_CORNER_L_OUT, max(0f, cornerLOut))
		addMode(MODE_CORNER_R_OUT, max(0f, cornerROut))
		addMode(MODE_PUCKER_L, max(0f, puckerL))
		addMode(MODE_PUCKER_R, max(0f, puckerR))
		if (vowelMode in MODE_VOWEL_I..MODE_VOWEL_A && abs(vowelWeight) > 1e-6f) {
			addMode(vowelMode, vowelWeight)
		}

		val curveSize = 2 * stations * 2
		val out = orig65.copyOf()
		for (k in 0 until axisCount) {
			val zk = z[k]
			if (abs(zk) <= 1e-8f) continue
			val base = k * curveSize
			for (i in 0 until curveSize) {
				out[i] += zk * axes65[base + i]
			}
		}

		// Closed-lip jaw carry ("mmm"): decays smoothly as the mouth opens on its own.
		if (jaw > 1e-6f) {
			val jawGate = jawCarryWeight(openClamped) * jaw
			if (jawGate > 1e-6f) {
				for (i in 0 until curveSize) out[i] += jawGate * jaw65[i]
			}
		}

		// Inward lip-press roll (press < 0): subtle upward tighten and arch flatten.
		if (press < -1e-6f) {
			val rollAmt = (-press).coerceAtMost(1.5f)
			val baseShiftY = -0.024f * rollAmt * (1f - 0.5f * openClamped)
			val archScale = 0.014f * rollAmt * (1f - openClamped)
			for (s in 0 until stations) {
				val arch = archScale * sin(PI.toFloat() * uDense[s])
				out[s * 2 + 1] += baseShiftY + arch
				out[(stations + s) * 2 + 1] += baseShiftY - arch
			}
		}
		return out
	}

	/**
	 * Transfers a normalized 65-station contour onto target local rest curves (`upperLocal`, `lowerLocal`, each `65 * 2`
	 * in mouth-center width-normalized coordinates), returning `(upperTransferred, lowerTransferred, c0)`.
	 * [closedMidlineLocal] supplies the closed drawing for whole-mouth art without independently authored lips;
	 * otherwise closure retains the rest midline and transfers the template's closing displacement.
	 */
	fun transferToTargetLocal(
		srcPose: FloatArray,
		upperRestLocal: FloatArray,
		lowerRestLocal: FloatArray,
		hasLines: Boolean,
		extraOpen: Float = 0f,
		closedGap: Float = 0f,
		customCurveDelta: FloatArray? = null,
		closedMidlineLocal: FloatArray? = null,
	): TransferredCurves {
		// The authored neutral drawing is an exact key, including overlapping corner strokes.
		if (extraOpen <= 0f && srcPose.contentEquals(orig65))
			return TransferredCurves(upperRestLocal.copyOf(), lowerRestLocal.copyOf(), 1f, 0f)
		val n = stations
		val mid = n / 2
		val lowerOff = n * 2

		val aSrc0MidY = 0.5f * (orig65[lowerOff + mid * 2 + 1] - orig65[mid * 2 + 1])
		val hSrc = max(aSrc0MidY, 1e-4f)
		val aTgt0MidY = 0.5f * (lowerRestLocal[mid * 2 + 1] - upperRestLocal[mid * 2 + 1])
		val hTgt = max(aTgt0MidY, 1e-4f)
		val ky = hTgt / hSrc

		val sSrcBase0 = FloatArray(n)
		val sSrcEff1 = FloatArray(n)
		val sTgt0 = FloatArray(n)
		var denom = 0f
		var numer = 0f
		var maxS1 = 0f
		for (i in 0 until n) {
			val a0y = 0.5f * (orig65[lowerOff + i * 2 + 1] - orig65[i * 2 + 1])
			val aShutY = 0.5f * (shut65[lowerOff + i * 2 + 1] - shut65[i * 2 + 1])
			val a1y = 0.5f * (srcPose[lowerOff + i * 2 + 1] - srcPose[i * 2 + 1])
			val b0 = max(0f, (a0y - aShutY) / hSrc)
			val e1 = max(0f, (a1y - aShutY) / hSrc)
			val t0 = max(0f, a0y.let { 0.5f * (lowerRestLocal[i * 2 + 1] - upperRestLocal[i * 2 + 1]) } / hTgt)
			sSrcBase0[i] = b0
			sSrcEff1[i] = e1
			sTgt0[i] = t0
			denom += b0 * b0
			numer += e1 * b0
			if (e1 > maxS1) maxS1 = e1
		}

		val c0 = if (denom > 1e-8f) numer / denom else 0f
		val openGate = (maxS1 / 0.12f).coerceIn(0f, 1f)
		val cClose = (1f - c0).coerceIn(0f, 1f)
		val wideOpenSkin = !hasLines && hTgt > 0.26f
		val kyExpr = if (wideOpenSkin) 1f else min(ky, 1.25f)
		val extra = max(0f, extraOpen)
		val shoutScaleX = 1f - 0.06f * extra

		val uOut = FloatArray(n * 2)
		val lOut = FloatArray(n * 2)
		var shiftXSum = 0f

		for (i in 0 until n) {
			val ix = i * 2
			val iy = ix + 1
			val mSrc0X = 0.5f * (orig65[ix] + orig65[lowerOff + ix])
			val mSrc0Y = 0.5f * (orig65[iy] + orig65[lowerOff + iy])
			val mSrc1X = 0.5f * (srcPose[ix] + srcPose[lowerOff + ix])
			val mSrc1Y = 0.5f * (srcPose[iy] + srcPose[lowerOff + iy])
			val mShutY = 0.5f * (shut65[iy] + shut65[lowerOff + iy])

			val aSrc0X = 0.5f * (orig65[lowerOff + ix] - orig65[ix])
			val aSrc1X = 0.5f * (srcPose[lowerOff + ix] - srcPose[ix])

			val mTgt0X = 0.5f * (upperRestLocal[ix] + lowerRestLocal[ix])
			val mTgt0Y = 0.5f * (upperRestLocal[iy] + lowerRestLocal[iy])
			val aTgt0X = 0.5f * (lowerRestLocal[ix] - upperRestLocal[ix])
			val aTgt0Y = 0.5f * (lowerRestLocal[iy] - upperRestLocal[iy])

			val dmX = mSrc1X - mSrc0X
			shiftXSum += dmX
			val dmShutY = mShutY - mSrc0Y
			val dmExprY = (mSrc1Y - mSrc0Y) - cClose * dmShutY
			val closeDy = closedMidlineLocal?.let { it[i] - mTgt0Y }
				?: if (wideOpenSkin) -0.36f * aTgt0Y else dmShutY * ky
			val curveOff = if (customCurveDelta != null) customCurveDelta[i] * cClose else 0f
			val dmYTgt = cClose * closeDy + dmExprY * kyExpr + curveOff

			val mTgt1X = (mTgt0X + dmX) * shoutScaleX
			val mTgt1Y = mTgt0Y + dmYTgt

			val ds = sSrcEff1[i] - c0 * sSrcBase0[i]
			val sTgt1 = max(0f, c0 * sTgt0[i] + openGate * ds)
			val gapHalf = if (closedGap > 0f && cClose > 0f) 0.5f * closedGap * cClose * sin(PI.toFloat() * uDense[i]) else 0f
			val aTgt1X = (c0 * aTgt0X + openGate * (aSrc1X - aSrc0X)) * shoutScaleX
			val aTgt1Y = hTgt * sTgt1 + gapHalf

			val shoutUp = if (extra > 0f) 0.45f * extra * max(0f, aTgt0Y) else 0f
			val shoutLo = if (extra > 0f) 0.90f * extra * max(0f, aTgt0Y) else 0f

			uOut[ix] = mTgt1X - aTgt1X
			uOut[iy] = mTgt1Y - aTgt1Y - shoutUp
			lOut[ix] = mTgt1X + aTgt1X
			lOut[iy] = mTgt1Y + aTgt1Y + shoutLo
		}
		return TransferredCurves(uOut, lOut, c0, shiftXSum / n)
	}

	private fun interpolateGridZ(open: Float, form: Float, press: Float): FloatArray {
		val (oi, to) = bracket(openKeys, open)
		val fClamped = form.coerceIn(formKeys.first(), formKeys.last())
		val fExtra = form - fClamped
		val (fi, tf) = bracket(formKeys, fClamped)
		val (pi, tp) = bracket(pressKeys, press)

		val nF = formKeys.size
		val nP = pressKeys.size
		fun cellOffset(oIdx: Int, fIdx: Int, pIdx: Int): Int = ((oIdx * nF + fIdx) * nP + pIdx) * axisCount

		val out = FloatArray(axisCount)
		for (di in 0..1) {
			val wo = if (di == 0) 1f - to else to
			if (wo == 0f) continue
			for (df in 0..1) {
				val wf = wo * (if (df == 0) 1f - tf else tf)
				if (wf == 0f) continue
				for (dp in 0..1) {
					val w = wf * (if (dp == 0) 1f - tp else tp)
					if (w == 0f) continue
					val off = cellOffset(oi + di, fi + df, pi + dp)
					for (k in 0 until axisCount) out[k] += w * gridZ[off + k]
				}
			}
		}
		if (abs(fExtra) > 1e-5f) {
			// Linear slope extrapolation when formStrength > 1 pushes |form| past 1.
			val fEdge = if (fExtra > 0f) nF - 1 else 1
			val span = (formKeys[fEdge] - formKeys[fEdge - 1]).coerceAtLeast(1e-4f)
			val scale = fExtra / span
			for (di in 0..1) {
				val wo = if (di == 0) 1f - to else to
				if (wo == 0f) continue
				val off1 = cellOffset(oi + di, fEdge, 1)
				val off0 = cellOffset(oi + di, fEdge - 1, 1)
				for (k in 0 until axisCount) out[k] += wo * scale * (gridZ[off1 + k] - gridZ[off0 + k])
			}
		}
		return out
	}

	class TransferredCurves(
		val upperLocal: FloatArray,
		val lowerLocal: FloatArray,
		val c0: Float,
		val shiftXNorm: Float,
	)

	companion object {
		const val MODE_MOUTH_X_NEG = 0
		const val MODE_MOUTH_X_POS = 1
		const val MODE_PUCKER = 2
		const val MODE_WIDEN = 3
		const val MODE_FUNNEL = 4
		const val MODE_SHRUG = 5
		const val MODE_CORNER_L_UP = 6
		const val MODE_CORNER_L_DOWN = 7
		const val MODE_CORNER_R_UP = 8
		const val MODE_CORNER_R_DOWN = 9
		const val MODE_CORNER_L_OUT = 10
		const val MODE_CORNER_R_OUT = 11
		const val MODE_PUCKER_L = 12
		const val MODE_PUCKER_R = 13
		const val MODE_VOWEL_I = 14
		const val MODE_VOWEL_U = 15
		const val MODE_VOWEL_E = 16
		const val MODE_VOWEL_O = 17
		const val MODE_VOWEL_A = 18

		fun jawCarryWeight(openY: Float): Float = when {
			openY <= 0f -> 1f
			openY < 0.25f -> 1f + (0.17f - 1f) * (openY / 0.25f)
			openY < 0.55f -> 0.17f * (1f - (openY - 0.25f) / 0.30f)
			else -> 0f
		}

		fun computeFrenet(curve: FloatArray, stations: Int): Pair<FloatArray, FloatArray> {
			val tangents = FloatArray(stations * 2)
			val normals = FloatArray(stations * 2)
			for (i in 0 until stations) {
				val i0 = max(0, i - 2)
				val i1 = min(stations - 1, i + 2)
				val dx = curve[i1 * 2] - curve[i0 * 2]
				val dy = curve[i1 * 2 + 1] - curve[i0 * 2 + 1]
				val len = hypot(dx, dy).coerceAtLeast(1e-8f)
				val tx = dx / len
				val ty = dy / len
				tangents[i * 2] = tx
				tangents[i * 2 + 1] = ty
				normals[i * 2] = -ty
				normals[i * 2 + 1] = tx
			}
			return tangents to normals
		}

		private fun bracket(keys: FloatArray, x: Float): Pair<Int, Float> {
			if (x <= keys.first()) return 0 to 0f
			for (i in 0 until keys.size - 1) {
				if (x <= keys[i + 1]) {
					val span = (keys[i + 1] - keys[i]).coerceAtLeast(1e-6f)
					return i to ((x - keys[i]) / span).coerceIn(0f, 1f)
				}
			}
			return (keys.size - 2) to 1f
		}

		val DEFAULT: MouthThreeLayerModel by lazy {
			val stream = checkNotNull(MouthThreeLayerModel::class.java.getResourceAsStream("/rig/mouth-three-layer.bin")) {
				"Missing /rig/mouth-three-layer.bin resource"
			}
			val bytes = stream.use { InflaterInputStream(it).use { inf -> inf.readBytes() } }
			val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
			val magic = ByteArray(4).also { buf.get(it) }
			check(magic.contentEquals(byteArrayOf('M'.code.toByte(), '3'.code.toByte(), 'L'.code.toByte(), 'R'.code.toByte()))) {
				"Invalid mouth three-layer model header"
			}
			val version = buf.int
			check(version == 1) { "Unsupported mouth three-layer model version: $version" }
			val nStations = buf.int
			val nAxes = buf.int
			val nOpen = buf.int
			val nForm = buf.int
			val nPress = buf.int
			val nModes = buf.int
			check(nStations == 65 && nAxes == 25 && nOpen >= 2 && nForm >= 2 && nPress >= 2 && nModes == 19) {
				"Unexpected mouth three-layer model dimensions"
			}

			fun readFloats(count: Int): FloatArray = FloatArray(count) {
				buf.float.also { v -> check(v.isFinite()) { "Non-finite value in mouth three-layer model" } }
			}

			val curveLen = 2 * nStations * 2
			val orig65 = readFloats(curveLen)
			val shut65 = readFloats(curveLen)
			val axes65 = readFloats(nAxes * curveLen)
			val openKeys = readFloats(nOpen)
			val formKeys = readFloats(nForm)
			val pressKeys = readFloats(nPress)
			val gridZ = readFloats(nOpen * nForm * nPress * nAxes)
			val modesZ = Array(nModes) { readFloats(nAxes) }
			val jaw65 = readFloats(curveLen)
			check(!buf.hasRemaining()) { "Trailing bytes in mouth three-layer model" }

			MouthThreeLayerModel(
				stations = nStations,
				axisCount = nAxes,
				orig65 = orig65,
				shut65 = shut65,
				axes65 = axes65,
				openKeys = openKeys,
				formKeys = formKeys,
				pressKeys = pressKeys,
				gridZ = gridZ,
				modesZ = modesZ,
				jaw65 = jaw65,
			)
		}
	}
}
