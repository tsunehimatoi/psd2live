package io.github.psd2live.core.fit

import io.github.psd2live.core.Bounds
import io.github.psd2live.core.MouthCurve
import org.umamo.runtime.model.BlendShapeBinding
import org.umamo.runtime.model.BlendWeightLimit
import org.umamo.runtime.model.BlendWeightLimitPoint
import org.umamo.runtime.model.KeyformAxis
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.MeshDeltaForm
import org.umamo.runtime.model.MeshForm
import org.umamo.runtime.model.ParameterId
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Which mouth modules a project builds, with their values as fractions. */
data class MouthOptions(
	val early: Float = 0.35f, val formStrength: Float = 1f,
	val curve: MouthCurve = MouthCurve.preset("smile"), val exactClose: Boolean = true,
	val vowels: Boolean = false, val capture: Boolean = false,
	val jaw: Boolean = false, val jawShare: Float = 0.15f,
	val depth: Boolean = false, val depthAmount: Float = 1f,
	val spring: Boolean = false,
	/** Whether the art has a tongue to put out. */
	val tongue: Boolean = false,
	/**
	 * How much of its height a layered mouth's inside keeps when the lips shut, with lip layers over it to hide it: 0
	 * closes it into the lips' line, 1 leaves it as drawn behind the lips.
	 */
	val inside: Float = 0.5f,
	/** How far the opening goes, of the drawing: past 1 the mouth opens on beyond it, a shout. */
	val reach: Float = 1f,
	/** How much wider a full smile spreads the teeth, for rigs that widen them; none by default. */
	val teethWiden: Float = 0f,
	/** How much a mouth half open narrows before widening to the drawing, for mouths that open as a small o first. */
	val narrow: Float = 0f,
)

/**
 * Three-layer mouth deformation rig for [fit] (layered cel-line mouths, layered lip-skin mouths, invisible-clip mouths,
 * and single-layer whole mouths).
 *
 * Layer 1 transfers normalized 65-station lip contours onto the character's rest lip strokes or inner mouth rims with
 * exact neutral restoration (`open = 1, form = 0` -> zero delta), concentric seam closure when shut, maxilla-anchored
 * jaw rise on wide-open lip-skin artwork, Frenet stroke thickness preservation, seamless perioral skin coupling, and
 * anatomical maxilla/mandible teeth plus wry-mouth orthogonal tooth-row tilt.
 * Layer 2 spans a 25-axis principal deformation subspace (`MouthThreeLayerModel`).
 * Layer 3 drives `(Form × OpenY [× PressLipOpen])` plus vowels and bilateral/unilateral facial capture bindings.
 */
class MouthRig(val fit: MouthFit, val options: MouthOptions, lineWidth: Float? = null) {
	/**
	 * User-space pose controls evaluated through the three-layer lip model onto [fit]'s target frame.
	 */
	data class Pose(
		val open: Float,
		val form: Float = 0f,
		val press: Float = 0f,
		val mouthX: Float = 0f,
		val width: Float = 0f,
		val funnel: Float = 0f,
		val shrug: Float = 0f,
		val jaw: Float = 0f,
		val cheek: Float = 0f,
		val tongueOut: Float = 0f,
		val cornerLUp: Float = 0f,
		val cornerLDown: Float = 0f,
		val cornerRUp: Float = 0f,
		val cornerRDown: Float = 0f,
		val cornerLOut: Float = 0f,
		val cornerROut: Float = 0f,
		val puckerL: Float = 0f,
		val puckerR: Float = 0f,
	)

	/**
	 * How each part of a mouth follows the posed lip contours:
	 * - [WHOLE], [WHOLE_TIGHT]: a single-layer whole mouth (with or without generated lip ribbons).
	 * - [OPENING]: the inner-mouth opening and clipping mask, mapped cleanly onto `[u1, l1]`.
	 * - [INSIDE]: an interior highlight/fill that retains [MouthOptions.inside] of its height behind lip skin.
	 * - [UPPER_LINE], [LOWER_LINE]: cel-shaded lip strokes preserving Frenet thickness and joined corner tips.
	 * - [UPPER_LIP], [LOWER_LIP]: painted lip-skin patches with 1D rim parameter and seamless outer blend into the face.
	 * - [UPPER_TEETH], [LOWER_TEETH]: maxillary/mandibular teeth with symmetric rigid hold plus wry orthogonal tilt.
	 * - [TONGUE], [PROTRUDING_TONGUE]: clipped inner tongue and unclipped protruding tongue mesh for `ParamTongueOut`.
	 * - [DEEP]: deep back-of-mouth fill clipped to the opening.
	 */
	enum class Follow {
		WHOLE, WHOLE_TIGHT, OPENING, INSIDE,
		UPPER_LINE, LOWER_LINE, UPPER_LIP, LOWER_LIP,
		UPPER_TEETH, LOWER_TEETH, TONGUE, PROTRUDING_TONGUE, DEEP,
	}

	private val model = MouthThreeLayerModel.DEFAULT
	private val stations = model.stations
	private val uDense = model.uDense
	private val midIdx = stations / 2
	private val h = fit.opening

	/** The opening's keys: [OPEN_KEYS], and the [MouthOptions.reach] past the drawing where it goes on. */
	val openKeys: FloatArray = if (options.reach > 1.001f) OPEN_KEYS + options.reach else OPEN_KEYS
	private val band = min(0.045f, h * 0.18f)
	private val line = lineWidth ?: 0f
	private val gap = if (options.exactClose) 0f else (1.2f / fit.width).coerceIn(0.004f, 0.04f)

	private val customCurveDelta: FloatArray? = run {
		val defSmile = MouthCurve.preset("smile")
		if (options.curve == defSmile) null
		else FloatArray(stations) { i ->
			val u = uDense[i]
			(options.curve.yAt(u) - defSmile.yAt(u)) * h * 0.6f
		}
	}

	private class TargetGeom(
		val isLayeredArt: Boolean,
		val hasLines: Boolean,
		val centerX: Float,
		val centerY: Float,
		val exX: Float,
		val exY: Float,
		val widthPx: Float,
		val u0World: FloatArray,
		val l0World: FloatArray,
		val u0LocalNorm: FloatArray,
		val l0LocalNorm: FloatArray,
		val u0LocPx: FloatArray,
		val l0LocPx: FloatArray,
		val uxMono0: FloatArray,
		val lxMono0: FloatArray,
		val xiU0: FloatArray,
		val xiL0: FloatArray,
		val tU0: FloatArray,
		val nU0: FloatArray,
		val tL0: FloatArray,
		val nL0: FloatArray,
		val linePx: Float,
		val h0Px: Float,
		val ap0MidPx: Float,
		val openColsXLoc: FloatArray,
		val openTopYLoc: FloatArray,
		val openBotYLoc: FloatArray,
		val upperLipBoundsLocal: Bounds?,
		val lowerLipBoundsLocal: Bounds?,
		val tongueBoundsLocal: Bounds?,
		val hUtVis0: Float,
		val utSideReserve: Float,
		val hLtVis0: Float,
		val u0MouthU: FloatArray,
		val u0MouthV: FloatArray,
		val l0MouthU: FloatArray,
		val l0MouthV: FloatArray,
	) {
		val eyX: Float get() = -exY
		val eyY: Float get() = exX

		fun toLocalPx(rigX: Float, rigY: Float): Pair<Float, Float> {
			val dx = rigX - centerX
			val dy = rigY - centerY
			return (dx * exX + dy * exY) to (dx * eyX + dy * eyY)
		}

		fun localDeltaToRig(dxLoc: Float, dyLoc: Float): Pair<Float, Float> =
			(dxLoc * exX + dyLoc * eyX) to (dxLoc * exY + dyLoc * eyY)

		fun localPxToRig(xLoc: Float, yLoc: Float): Pair<Float, Float> =
			(centerX + xLoc * exX + yLoc * eyX) to (centerY + xLoc * exY + yLoc * eyY)
	}

	private fun drawnUpper(u: Float) = MouthFit.sample(fit.upper, u) - fit.baseline(u)
	private fun drawnLower(u: Float) = MouthFit.sample(fit.lower, u) - fit.baseline(u)

	private val target: TargetGeom = buildTargetGeom()

	// A whole mouth has no authored closed lip line. Its open contour's midpoint is not a closed-mouth drawing.
	private val closedMidlineLocal: FloatArray? = if (target.isLayeredArt) null else FloatArray(stations) { i ->
		MouthCurve.preset("smile").yAt(uDense[i]) * fit.opening * 0.6f * fit.width / target.widthPx
	}

	private fun buildTargetGeom(): TargetGeom {
		val lf = fit.layered
		if (lf != null) {
			val w = lf.frameWidth
			val u0LocPx = FloatArray(stations * 2) { lf.upperLocal65[it] * w }
			val l0LocPx = FloatArray(stations * 2) { lf.lowerLocal65[it] * w }
			val uxMono = FloatArray(stations)
			val lxMono = FloatArray(stations)
			val xiU0 = FloatArray(stations)
			val xiL0 = FloatArray(stations)
			val u0MU = FloatArray(stations)
			val u0MV = FloatArray(stations)
			val l0MU = FloatArray(stations)
			val l0MV = FloatArray(stations)
			for (i in 0 until stations) {
				val ux = u0LocPx[i * 2]
				val lx = l0LocPx[i * 2]
				uxMono[i] = if (i == 0) ux else max(uxMono[i - 1], ux)
				lxMono[i] = if (i == 0) lx else max(lxMono[i - 1], lx)
				xiU0[i] = ux / max(w, 1e-4f)
				xiL0[i] = lx / max(w, 1e-4f)
				val (umu, umv) = fit.toMouth(lf.upperWorld65[i * 2], lf.upperWorld65[i * 2 + 1])
				val (lmu, lmv) = fit.toMouth(lf.lowerWorld65[i * 2], lf.lowerWorld65[i * 2 + 1])
				u0MU[i] = umu; u0MV[i] = umv - fit.baseline(umu)
				l0MU[i] = lmu; l0MV[i] = lmv - fit.baseline(lmu)
			}
			val (tU0, nU0) = MouthThreeLayerModel.computeFrenet(lf.upperWorld65, stations)
			val (tL0, nL0) = MouthThreeLayerModel.computeFrenet(lf.lowerWorld65, stations)
			val ap0Mid = max(l0LocPx[midIdx * 2 + 1] - u0LocPx[midIdx * 2 + 1], 1e-4f)
			return TargetGeom(
				isLayeredArt = true,
				hasLines = lf.hasLines,
				centerX = lf.centerX,
				centerY = lf.centerY,
				exX = lf.exX,
				exY = lf.exY,
				widthPx = w,
				u0World = lf.upperWorld65,
				l0World = lf.lowerWorld65,
				u0LocalNorm = lf.upperLocal65,
				l0LocalNorm = lf.lowerLocal65,
				u0LocPx = u0LocPx,
				l0LocPx = l0LocPx,
				uxMono0 = uxMono,
				lxMono0 = lxMono,
				xiU0 = xiU0,
				xiL0 = xiL0,
				tU0 = tU0,
				nU0 = nU0,
				tL0 = tL0,
				nL0 = nL0,
				linePx = lf.lineNorm * w,
				h0Px = ap0Mid,
				ap0MidPx = ap0Mid,
				openColsXLoc = lf.openColsX,
				openTopYLoc = lf.openTopY,
				openBotYLoc = lf.openBotY,
				upperLipBoundsLocal = lf.upperLipBoundsLocal,
				lowerLipBoundsLocal = lf.lowerLipBoundsLocal,
				tongueBoundsLocal = lf.tongueBoundsLocal,
				hUtVis0 = lf.hUtVis0,
				utSideReserve = lf.utSideReserve,
				hLtVis0 = lf.hLtVis0,
				u0MouthU = u0MU,
				u0MouthV = u0MV,
				l0MouthU = l0MU,
				l0MouthV = l0MV,
			)
		}

		// Synthetic or whole-mouth fallback constructed directly from MouthFit + lineWidth.
		val leftPt = fit.toCanvas(0f, 0f)
		val rightPt = fit.toCanvas(1f, fit.cornerV)
		val w = hypot(rightPt.first - leftPt.first, rightPt.second - leftPt.second).coerceAtLeast(1e-4f)
		val exX = (rightPt.first - leftPt.first) / w
		val exY = (rightPt.second - leftPt.second) / w
		val eyX = -exY
		val eyY = exX
		val centerX = 0.5f * (leftPt.first + rightPt.first)
		val centerY = 0.5f * (leftPt.second + rightPt.second)
		val hasLines = line > 0f

		val u0World = FloatArray(stations * 2)
		val l0World = FloatArray(stations * 2)
		val u0LocalNorm = FloatArray(stations * 2)
		val l0LocalNorm = FloatArray(stations * 2)
		val u0LocPx = FloatArray(stations * 2)
		val l0LocPx = FloatArray(stations * 2)
		val uxMono = FloatArray(stations)
		val lxMono = FloatArray(stations)
		val xiU0 = FloatArray(stations)
		val xiL0 = FloatArray(stations)
		val u0MU = FloatArray(stations)
		val u0MV = FloatArray(stations)
		val l0MU = FloatArray(stations)
		val l0MV = FloatArray(stations)
		val openColsX = FloatArray(stations)
		val openTopY = FloatArray(stations)
		val openBotY = FloatArray(stations)

		for (i in 0 until stations) {
			val u = uDense[i]
			val baseV = fit.baseline(u)
			val uOuter = drawnUpper(u)
			val lOuter = drawnLower(u)
			val span = max(0f, lOuter - uOuter)
			val inset = if (hasLines) min(0.5f * line, 0.5f * span) else 0f
			val uv = uOuter + inset
			val lv = lOuter - inset
			val uw = fit.toCanvas(u, baseV + uv)
			val lw = fit.toCanvas(u, baseV + lv)
			u0World[i * 2] = uw.first; u0World[i * 2 + 1] = uw.second
			l0World[i * 2] = lw.first; l0World[i * 2 + 1] = lw.second

			val udx = uw.first - centerX; val udy = uw.second - centerY
			val ulx = udx * exX + udy * exY; val uly = udx * eyX + udy * eyY
			val ldx = lw.first - centerX; val ldy = lw.second - centerY
			val llx = ldx * exX + ldy * exY; val lly = ldx * eyX + ldy * eyY

			u0LocPx[i * 2] = ulx; u0LocPx[i * 2 + 1] = uly
			l0LocPx[i * 2] = llx; l0LocPx[i * 2 + 1] = lly
			u0LocalNorm[i * 2] = ulx / w; u0LocalNorm[i * 2 + 1] = uly / w
			l0LocalNorm[i * 2] = llx / w; l0LocalNorm[i * 2 + 1] = lly / w

			uxMono[i] = if (i == 0) ulx else max(uxMono[i - 1], ulx)
			lxMono[i] = if (i == 0) llx else max(lxMono[i - 1], llx)
			xiU0[i] = ulx / w
			xiL0[i] = llx / w
			u0MU[i] = u; u0MV[i] = uv
			l0MU[i] = u; l0MV[i] = lv
			openColsX[i] = 0.5f * (ulx + llx)
			openTopY[i] = uly
			openBotY[i] = lly
		}
		val (tU0, nU0) = MouthThreeLayerModel.computeFrenet(u0World, stations)
		val (tL0, nL0) = MouthThreeLayerModel.computeFrenet(l0World, stations)
		val ap0Mid = max(l0LocPx[midIdx * 2 + 1] - u0LocPx[midIdx * 2 + 1], 1e-4f)
		val linePx = line * fit.width
		return TargetGeom(
			isLayeredArt = false,
			hasLines = hasLines,
			centerX = centerX,
			centerY = centerY,
			exX = exX,
			exY = exY,
			widthPx = w,
			u0World = u0World,
			l0World = l0World,
			u0LocalNorm = u0LocalNorm,
			l0LocalNorm = l0LocalNorm,
			u0LocPx = u0LocPx,
			l0LocPx = l0LocPx,
			uxMono0 = uxMono,
			lxMono0 = lxMono,
			xiU0 = xiU0,
			xiL0 = xiL0,
			tU0 = tU0,
			nU0 = nU0,
			tL0 = tL0,
			nL0 = nL0,
			linePx = linePx,
			h0Px = ap0Mid,
			ap0MidPx = ap0Mid,
			openColsXLoc = openColsX,
			openTopYLoc = openTopY,
			openBotYLoc = openBotY,
			upperLipBoundsLocal = null,
			lowerLipBoundsLocal = null,
			tongueBoundsLocal = null,
			hUtVis0 = 0.20f * ap0Mid,
			utSideReserve = 0.12f * w,
			hLtVis0 = 0.10f * ap0Mid,
			u0MouthU = u0MU,
			u0MouthV = u0MV,
			l0MouthU = l0MU,
			l0MouthV = l0MV,
		)
	}

	private class EvaluatedPose(
		val pose: Pose,
		val u1World: FloatArray,
		val l1World: FloatArray,
		val u1LocPx: FloatArray,
		val l1LocPx: FloatArray,
		val usLocPx: FloatArray,
		val lsLocPx: FloatArray,
		val u1MouthU: FloatArray,
		val u1MouthV: FloatArray,
		val l1MouthU: FloatArray,
		val l1MouthV: FloatArray,
		val tU1: FloatArray,
		val nU1: FloatArray,
		val tL1: FloatArray,
		val nL1: FloatArray,
		val closing: FloatArray,
		val restContourWeight: Float,
		val shiftXPx: Float,
		val openRatio: Float,
		val openSym: Float,
		val duSymMid: Float,
		val dlSymMid: Float,
		val hasAsym: Boolean,
		val dyUtSymMid: Float,
		val dyLtSymMid: Float,
		val utCubic: FloatArray,
		val ltCubic: FloatArray,
		val tngAsymCubic: FloatArray,
		val axU: Float,
		val kxUt: Float,
		val sinThetaUt: Float,
		val cosThetaUt: Float,
		val yUtPivot: Float,
		val axL: Float,
		val kxLt: Float,
		val sinThetaLt: Float,
		val cosThetaLt: Float,
		val yLtPivot: Float,
		val tongueDx: Float,
		val dxxMean: Float,
	)

	// Reference maxillary shut hold computed once from neutral shut & neutral half-open
	private val maxillaShutHoldPx: Float by lazy {
		val shutCurve = model.transferToTargetLocal(
			model.evaluateNormalized(openY = 0f, form = 0f),
			target.u0LocalNorm, target.l0LocalNorm, target.hasLines,
			closedGap = gap * fit.width / target.widthPx,
			customCurveDelta = customCurveDelta,
			closedMidlineLocal = closedMidlineLocal,
		)
		val midCurve = model.transferToTargetLocal(
			model.evaluateNormalized(openY = 0.48f, form = 0f),
			target.u0LocalNorm, target.l0LocalNorm, target.hasLines,
			closedGap = gap * fit.width / target.widthPx,
			customCurveDelta = customCurveDelta,
			closedMidlineLocal = closedMidlineLocal,
		)
		val w = target.widthPx
		val u0MidY = target.u0LocPx[midIdx * 2 + 1]
		val duShut = shutCurve.upperLocal[midIdx * 2 + 1] * w - u0MidY
		val duAt048 = midCurve.upperLocal[midIdx * 2 + 1] * w - u0MidY
		min(duAt048, max(0f, duShut - 0.48f * target.hUtVis0))
	}

	private val poseCache = java.util.concurrent.ConcurrentHashMap<Pose, EvaluatedPose>()

	private fun eval(pose: Pose): EvaluatedPose = poseCache.computeIfAbsent(pose, ::computePose)

	private val shutEval: EvaluatedPose by lazy { eval(pose(0f, 0f)) }

	private fun effectiveOpen(open: Float): Float {
		if (open <= 0f || open >= 1f || abs(options.early - 0.35f) < 1e-4f) return open
		val k = (options.early / 0.35f).coerceIn(0.3f, 2.2f)
		return if (open <= 0.25f) (open * k).coerceAtMost(0.85f)
		else {
			val start = (0.25f * k).coerceAtMost(0.85f)
			start + (1f - start) * ((open - 0.25f) / 0.75f)
		}
	}

	private fun computePose(pose: Pose): EvaluatedPose {
		val openEff = effectiveOpen(pose.open)
		val extraOpen = max(0f, pose.open - 1f)
		val narrowAdd = if (options.narrow > 1e-5f) {
			-options.narrow * sin(PI.toFloat() * (pose.open / 0.7f).coerceIn(0f, 1f))
		} else 0f
		val widthEff = pose.width + narrowAdd
		val formEff = (pose.form * options.formStrength).coerceIn(-1.5f, 1.5f)
		val closedGapNorm = gap * fit.width / target.widthPx

		val srcPose = model.evaluateNormalized(
			openY = openEff,
			form = formEff,
			press = pose.press,
			mouthX = pose.mouthX,
			width = widthEff,
			funnel = pose.funnel,
			shrug = pose.shrug,
			jaw = pose.jaw,
			cornerLUp = pose.cornerLUp,
			cornerLDown = pose.cornerLDown,
			cornerRUp = pose.cornerRUp,
			cornerRDown = pose.cornerRDown,
			cornerLOut = pose.cornerLOut,
			cornerROut = pose.cornerROut,
			puckerL = pose.puckerL,
			puckerR = pose.puckerR,
		)
		val hasAsym = abs(pose.mouthX) > 1e-6f ||
			abs(pose.cornerLUp) > 1e-6f || abs(pose.cornerLDown) > 1e-6f ||
			abs(pose.cornerRUp) > 1e-6f || abs(pose.cornerRDown) > 1e-6f ||
			abs(pose.cornerLOut) > 1e-6f || abs(pose.cornerROut) > 1e-6f ||
			abs(pose.puckerL) > 1e-6f || abs(pose.puckerR) > 1e-6f

		val tr = model.transferToTargetLocal(
			srcPose, target.u0LocalNorm, target.l0LocalNorm, target.hasLines,
			extraOpen = extraOpen, closedGap = closedGapNorm, customCurveDelta = customCurveDelta,
			closedMidlineLocal = closedMidlineLocal,
		)
		val trSym = if (hasAsym) {
			val srcSym = model.evaluateNormalized(
				openY = openEff,
				form = formEff,
				press = pose.press,
				mouthX = 0f,
				width = widthEff,
				funnel = pose.funnel,
				shrug = pose.shrug,
				jaw = pose.jaw,
			)
			model.transferToTargetLocal(
				srcSym, target.u0LocalNorm, target.l0LocalNorm, target.hasLines,
				extraOpen = extraOpen, closedGap = closedGapNorm, customCurveDelta = customCurveDelta,
				closedMidlineLocal = closedMidlineLocal,
			)
		} else tr

		val w = target.widthPx
		val u1LocPx = FloatArray(stations * 2) { tr.upperLocal[it] * w }
		val l1LocPx = FloatArray(stations * 2) { tr.lowerLocal[it] * w }
		val usLocPx = if (hasAsym) FloatArray(stations * 2) { trSym.upperLocal[it] * w } else u1LocPx
		val lsLocPx = if (hasAsym) FloatArray(stations * 2) { trSym.lowerLocal[it] * w } else l1LocPx

		val u1World = FloatArray(stations * 2)
		val l1World = FloatArray(stations * 2)
		val u1MU = FloatArray(stations)
		val u1MV = FloatArray(stations)
		val l1MU = FloatArray(stations)
		val l1MV = FloatArray(stations)
		val closing = FloatArray(stations)
		for (i in 0 until stations) {
			val uw = target.localPxToRig(u1LocPx[i * 2], u1LocPx[i * 2 + 1])
			val lw = target.localPxToRig(l1LocPx[i * 2], l1LocPx[i * 2 + 1])
			u1World[i * 2] = uw.first; u1World[i * 2 + 1] = uw.second
			l1World[i * 2] = lw.first; l1World[i * 2 + 1] = lw.second
			val (umu, umv) = fit.toMouth(uw.first, uw.second)
			val (lmu, lmv) = fit.toMouth(lw.first, lw.second)
			u1MU[i] = umu; u1MV[i] = umv - fit.baseline(umu)
			l1MU[i] = lmu; l1MV[i] = lmv - fit.baseline(lmu)
			val ap1 = max(0f, l1LocPx[i * 2 + 1] - u1LocPx[i * 2 + 1])
			closing[i] = 1f - (ap1 / target.ap0MidPx).coerceIn(0f, 1f)
		}
		val (tU1, nU1) = MouthThreeLayerModel.computeFrenet(u1World, stations)
		val (tL1, nL1) = MouthThreeLayerModel.computeFrenet(l1World, stations)

		val ap1Mid = max(0f, l1LocPx[midIdx * 2 + 1] - u1LocPx[midIdx * 2 + 1])
		val openRatio = (ap1Mid / target.ap0MidPx).coerceIn(0f, 1.6f)

		val u0MidY = target.u0LocPx[midIdx * 2 + 1]
		val l0MidY = target.l0LocPx[midIdx * 2 + 1]
		val duSymMid = usLocPx[midIdx * 2 + 1] - u0MidY
		val dlSymMid = lsLocPx[midIdx * 2 + 1] - l0MidY
		val apSymMid = max(0f, lsLocPx[midIdx * 2 + 1] - usLocPx[midIdx * 2 + 1])
		val h0 = target.h0Px
		val openSym = (apSymMid / h0).coerceIn(0f, 1.6f)

		// 1. Symmetric maxillary & mandibular tooth placement:
		//    Stationary maxilla over open in 0..0.48 at form = 0, smooth settle to 0 at open = 1,
		//    bared-teeth reveal on PressLipOpen > 0, tuck in pucker/funnel, and zero gap above teeth.
		val onUt = smoothstep(0.48f, 1.0f, openEff.coerceIn(0f, 1f))
		var dyUtSymMid = maxillaShutHoldPx * (1f - onUt)
		val baredAmt = max(0f, max(pose.press, 0.7f * pose.shrug)) * (1f - openEff.coerceIn(0f, 1f))
		val baredVis = max(0f, max(pose.press, 0.7f * pose.shrug)) * 0.78f * apSymMid
		if (baredAmt > 1e-4f) {
			val targetBared = min(target.hUtVis0, baredVis)
			val dyBared = duSymMid - (target.hUtVis0 - targetBared)
			dyUtSymMid += (dyBared - dyUtSymMid) * baredAmt.coerceAtMost(1f)
		}
		val narrowSym = max(0.82f * max(0f, pose.funnel), max(0f, -widthEff))
		dyUtSymMid -= 0.12f * h0 * narrowSym
		if (formEff > 0f) {
			dyUtSymMid -= 0.04f * h0 * formEff * (1f - openEff.coerceIn(0f, 1f))
		} else if (formEff < 0f) {
			dyUtSymMid -= 0.05f * h0 * (-formEff) * (1f - 0.5f * openEff.coerceIn(0f, 1f))
		}
		dyUtSymMid = min(dyUtSymMid, duSymMid)

		val visGate = smoothstep(0.06f, 0.16f, openSym)
		val ltGate = smoothstep(0.12f, 0.45f, openSym) *
			(1f - 0.75f * max(0f, -formEff).coerceAtMost(1f)) *
			(1f - 0.70f * max(0f, pose.funnel).coerceAtMost(1f))
		val targetLtVis = min(target.hLtVis0, min(target.hLtVis0 * (0.20f + 0.80f * ltGate), 0.26f * apSymMid * visGate))
		val rawLt = dlSymMid + (target.hLtVis0 - targetLtVis)
		val dyLtSymMid = if (dlSymMid < -1e-5f) {
			rawLt.coerceIn(0.92f * dlSymMid, 0.45f * dlSymMid)
		} else rawLt

		// Top-bleed reserve guard active only when painted upper teeth have tight side margin (< 0.035 * W),
		// keeping symmetric upper teeth strictly rigid otherwise.
		val tightSideReserve = target.utSideReserve < 0.035f * w

		// 2. Shared Asymmetric / Wry Mouth Warp with orthogonal rigid tooth-row tilt
		var axU = 0f; var dxxU = 0f; var slopeULin = 0f
		var axL = 0f; var dxxL = 0f; var slopeLLin = 0f
		val dyUtCurve = FloatArray(stations)
		val dyLtCurve = FloatArray(stations)
		val dmAsymY = FloatArray(stations)

		if (hasAsym) {
			val u0X = FloatArray(stations) { target.u0LocPx[it * 2] }
			val l0X = FloatArray(stations) { target.l0LocPx[it * 2] }
			val duAsymX = FloatArray(stations) { u1LocPx[it * 2] - usLocPx[it * 2] }
			val duAsymY = FloatArray(stations) { u1LocPx[it * 2 + 1] - usLocPx[it * 2 + 1] }
			val dlAsymX = FloatArray(stations) { l1LocPx[it * 2] - lsLocPx[it * 2] }
			val dlAsymY = FloatArray(stations) { l1LocPx[it * 2 + 1] - lsLocPx[it * 2 + 1] }

			val linUx = fitLine(u0X, duAsymX); axU = linUx.first; dxxU = linUx.second
			slopeULin = fitLine(u0X, duAsymY).second
			val linLx = fitLine(l0X, dlAsymX); axL = linLx.first; dxxL = linLx.second
			slopeLLin = fitLine(l0X, dlAsymY).second

			val symUtVis = (target.hUtVis0 + dyUtSymMid - duSymMid).coerceAtLeast(0f)
			val symLtVis = (target.hLtVis0 - (dyLtSymMid - dlSymMid)).coerceAtLeast(0f)

			for (i in 0 until stations) {
				val ap1C = max(0f, l1LocPx[i * 2 + 1] - u1LocPx[i * 2 + 1])
				val apsC = max(0f, lsLocPx[i * 2 + 1] - usLocPx[i * 2 + 1])
				val locGate = smoothstep(0.05f * h0, 0.15f * h0, ap1C)
				val locBudgetUt = max(baredVis, 0.45f * ap1C * locGate)
				val pinchedTuckUt = if (ap1C < apsC) max(0f, symUtVis - locBudgetUt) else 0f
				val openedRevealUt = if (ap1C > apsC + 1f && openSym < 0.25f) {
					max(0f, min(0.32f * ap1C * locGate, 0.42f * target.hUtVis0) - symUtVis)
				} else 0f

				val duArch = (usLocPx[i * 2 + 1] - target.u0LocPx[i * 2 + 1]) - duSymMid
				val symArchU = if (tightSideReserve && duArch < 0f) 0.82f * duArch else 0f
				dyUtCurve[i] = dyUtSymMid + symArchU + duAsymY[i] - pinchedTuckUt + openedRevealUt

				val locBudgetLt = 0.22f * ap1C * smoothstep(0.10f * h0, 0.25f * h0, ap1C)
				val pinchedTuckLt = if (ap1C < apsC) max(0f, symLtVis - locBudgetLt) else 0f
				val dlArch = (lsLocPx[i * 2 + 1] - target.l0LocPx[i * 2 + 1]) - dlSymMid
				val symArchL = if (target.isLayeredArt) 0.22f * dlArch else 0f
				dyLtCurve[i] = dyLtSymMid + symArchL + dlAsymY[i] + pinchedTuckLt
				dmAsymY[i] = 0.5f * (duAsymY[i] + dlAsymY[i])
			}
		} else {
			for (i in 0 until stations) {
				val duArch = (usLocPx[i * 2 + 1] - target.u0LocPx[i * 2 + 1]) - duSymMid
				val symArchU = if (tightSideReserve && duArch < 0f) 0.82f * duArch else 0f
				dyUtCurve[i] = dyUtSymMid + symArchU

				val dlArch = (lsLocPx[i * 2 + 1] - target.l0LocPx[i * 2 + 1]) - dlSymMid
				val symArchL = if (target.isLayeredArt) 0.20f * dlArch else 0f
				dyLtCurve[i] = dyLtSymMid + symArchL
			}
		}

		val utCubic = if (hasAsym || tightSideReserve) fitCubicCoeffs(target.xiU0, dyUtCurve, 2e-3f) else floatArrayOf(dyUtSymMid, 0f, 0f, 0f)
		val ltCubic = if (hasAsym || target.isLayeredArt) fitCubicCoeffs(target.xiL0, dyLtCurve, 2e-3f) else floatArrayOf(dyLtSymMid, 0f, 0f, 0f)
		val tngCubic = if (hasAsym) fitCubicCoeffs(target.xiU0, dmAsymY, 3e-3f) else floatArrayOf(0f, 0f, 0f, 0f)

		val thetaUt = atan(slopeULin).coerceIn(-0.55f, 0.55f)
		val thetaLt = atan(slopeLLin).coerceIn(-0.55f, 0.55f)
		val kxUt = if (dxxU >= 0f) 0.72f * dxxU else 0.20f * dxxU
		val kxLt = if (dxxL >= 0f) 0.72f * dxxL else 0.20f * dxxL

		return EvaluatedPose(
			pose = pose,
			u1World = u1World,
			l1World = l1World,
			u1LocPx = u1LocPx,
			l1LocPx = l1LocPx,
			usLocPx = usLocPx,
			lsLocPx = lsLocPx,
			u1MouthU = u1MU,
			u1MouthV = u1MV,
			l1MouthU = l1MU,
			l1MouthV = l1MV,
			tU1 = tU1,
			nU1 = nU1,
			tL1 = tL1,
			nL1 = nL1,
			closing = closing,
			restContourWeight = openEff.coerceIn(0f, 1f),
			shiftXPx = tr.shiftXNorm * w,
			openRatio = openRatio,
			openSym = openSym,
			duSymMid = duSymMid,
			dlSymMid = dlSymMid,
			hasAsym = hasAsym,
			dyUtSymMid = dyUtSymMid,
			dyLtSymMid = dyLtSymMid,
			utCubic = utCubic,
			ltCubic = ltCubic,
			tngAsymCubic = tngCubic,
			axU = axU,
			kxUt = kxUt,
			sinThetaUt = sin(thetaUt),
			cosThetaUt = cos(thetaUt),
			yUtPivot = u0MidY + 0.35f * target.hUtVis0,
			axL = axL,
			kxLt = kxLt,
			sinThetaLt = sin(thetaLt),
			cosThetaLt = cos(thetaLt),
			yLtPivot = l0MidY - 0.35f * target.hLtVis0,
			tongueDx = if (hasAsym) 0.5f * (axU + axL) else 0f,
			dxxMean = 0.5f * (dxxU + dxxL),
		)
	}

	private fun rigDeltaToMouth(dxRig: Float, dyRig: Float): Pair<Float, Float> {
		val invW = 1f / fit.width.coerceAtLeast(1e-4f)
		val du = (dxRig * fit.axisX + dyRig * fit.axisY) * invW
		val dv = (dxRig * (-fit.axisY) + dyRig * fit.axisX) * invW
		return du to dv
	}

	/** The lip edges of [pose] at [u] (of the drawing), below the corners' line. */
	fun edges(pose: Pose, u: Float): Pair<Float, Float> {
		val ep = eval(pose)
		val uc = u.coerceIn(0f, 1f)
		val f = uc * (stations - 1)
		val i = f.toInt().coerceAtMost(stations - 2)
		val t = f - i
		val duV = (ep.u1MouthV[i] - target.u0MouthV[i]) * (1f - t) + (ep.u1MouthV[i + 1] - target.u0MouthV[i + 1]) * t
		val dlV = (ep.l1MouthV[i] - target.l0MouthV[i]) * (1f - t) + (ep.l1MouthV[i + 1] - target.l0MouthV[i + 1]) * t
		val weight = if (target.isLayeredArt) 1f else ep.restContourWeight
		val upperRest = target.u0MouthV[i] * (1f - t) + target.u0MouthV[i + 1] * t
		val lowerRest = target.l0MouthV[i] * (1f - t) + target.l0MouthV[i + 1] * t
		// Carry the source sampling residual only while open; otherwise it reopens a closed target at steep corners.
		var top = if (target.isLayeredArt) drawnUpper(uc) + duV else upperRest + duV + (drawnUpper(uc) - upperRest) * weight
		var bottom = if (target.isLayeredArt) drawnLower(uc) + dlV else lowerRest + dlV + (drawnLower(uc) - lowerRest) * weight
		if (bottom < top) {
			val m = 0.5f * (top + bottom)
			top = m; bottom = m
		}
		return top to bottom
	}

	/**
	 * Whole-mouth point displacement in `(u, v)` frame for single-layer mouths and generated lip ribbons.
	 */
	fun move(u: Float, v: Float, pose: Pose, tight: Boolean = false): Pair<Float, Float> {
		val ep = eval(pose)
		val uc = u.coerceIn(0f, 1f)
		val r = v - fit.baseline(u)
		val u0 = drawnUpper(uc)
		val l0 = drawnLower(uc)
		val (u1, l1) = edges(pose, uc)
		val bandEff = if (tight) 0f else band
		val tb = min(bandEff, max(0f, l0 - u0) * 0.3f)
		val ratio = ((edges(pose, 0.5f).let { it.second - it.first }) / h.coerceAtLeast(1e-4f)).coerceIn(0f, 1.5f)
		fun beyond(d: Float): Float {
			val keep = d.coerceIn(-bandEff, bandEff)
			return keep + (d - keep) * ratio
		}
		fun posedUAt(at: Float, upper: Boolean): Float {
			val f = at.coerceIn(0f, 1f) * (stations - 1)
			val idx = f.toInt().coerceAtMost(stations - 2)
			val frac = f - idx
			val arr = if (upper) ep.u1MouthU else ep.l1MouthU
			return arr[idx] * (1f - frac) + arr[idx + 1] * frac
		}
		val uLeft = 0.5f * (ep.u1MouthU.first() + ep.l1MouthU.first())
		val uRight = 0.5f * (ep.u1MouthU.last() + ep.l1MouthU.last())
		val widthScale = (uRight - uLeft).coerceAtLeast(0.2f)

		if (r < u0 || r > l0) {
			val upper = r < u0
			val (at, dx, dy) = nearestEdge(if (upper) ::drawnUpper else ::drawnLower, u, r)
			val length = hypot(dx, dy)
			val k = if (length < 1e-6f) 1f else beyond(length) / length
			val edge = edges(pose, at).let { if (upper) it.first else it.second }
			val un = posedUAt(at, upper) + dx * k * widthScale
			return (un - u) to (fit.baseline(un) + edge + dy * k - v)
		}
		val (r1, sBlend) = when {
			r <= u0 + tb -> (u1 + beyond(r - u0)) to 0f
			r >= l0 - tb -> (l1 + beyond(r - l0)) to 1f
			else -> {
				val inTop = u1 + tb
				val inBottom = max(l1 - tb, inTop)
				val t = ((r - (u0 + tb)) / ((l0 - tb) - (u0 + tb)).coerceAtLeast(1e-5f)).coerceIn(0f, 1f)
				(inTop + (inBottom - inTop) * t) to t
			}
		}
		val uTop = posedUAt(uc, true)
		val uBot = posedUAt(uc, false)
		val un = uTop * (1f - sBlend) + uBot * sBlend + (u - uc) * widthScale
		val vn = fit.baseline(un) + r1
		return (un - u) to (vn - v)
	}

	/** Where a point at ([u], [v]) of a layer that follows the lips as [follow] goes in [pose], as a change in the frame. */
	fun moveAs(follow: Follow, u: Float, v: Float, pose: Pose): Pair<Float, Float> = moveAsInternal(follow, u, v, pose, chin = 0.6f)

	private fun moveAsInternal(follow: Follow, u: Float, v: Float, pose: Pose, chin: Float, teethDepthVal: Float = 0f): Pair<Float, Float> = if (pose == Pose(open = 1f)) 0f to 0f else when (follow) {
		Follow.WHOLE -> move(u, v, pose)
		Follow.WHOLE_TIGHT -> move(u, v, pose, tight = true)
		Follow.OPENING -> openingMove(u, v, pose)
		Follow.INSIDE -> openingMove(u, v, pose).let { (du, dv) ->
			du to dv * (1f - options.inside.coerceIn(0f, 1f) * EyeRig.hump(u.coerceIn(0f, 1f)).coerceAtLeast(0f))
		}
		Follow.UPPER_LINE -> strokeMove(true, u, v, pose)
		Follow.LOWER_LINE -> strokeMove(false, u, v, pose)
		Follow.UPPER_LIP -> lipSkinMove(true, u, v, pose, chin)
		Follow.LOWER_LIP -> lipSkinMove(false, u, v, pose, chin)
		Follow.UPPER_TEETH -> teethAt(u, v, pose, upper = true, depth = teethDepthVal)
		Follow.LOWER_TEETH -> teethAt(u, v, pose, upper = false, depth = 0f)
		Follow.TONGUE -> tongueMove(u, v, pose, protruding = false)
		Follow.PROTRUDING_TONGUE -> tongueMove(u, v, pose, protruding = true)
		Follow.DEEP -> deepMove(pose)
	}

	private fun strokeRaw(upper: Boolean, xRig: Float, yRig: Float, ep: EvaluatedPose): Triple<Float, Float, Float> {
		val c0 = if (upper) target.u0World else target.l0World
		val c1 = if (upper) ep.u1World else ep.l1World
		val t0 = if (upper) target.tU0 else target.tL0
		val n0 = if (upper) target.nU0 else target.nL0
		val t1 = if (upper) ep.tU1 else ep.tL1
		val n1 = if (upper) ep.nU1 else ep.nL1

		var bestSeg = 0
		var bestT = 0f
		var bestDist2 = Float.POSITIVE_INFINITY
		for (i in 0 until stations - 1) {
			val ax = c0[i * 2]; val ay = c0[i * 2 + 1]
			val bx = c0[(i + 1) * 2]; val by = c0[(i + 1) * 2 + 1]
			val vx = bx - ax; val vy = by - ay
			val segLen2 = vx * vx + vy * vy
			val tSeg = if (segLen2 > 1e-10f) (((xRig - ax) * vx + (yRig - ay) * vy) / segLen2).coerceIn(0f, 1f) else 0f
			val px = ax + tSeg * vx; val py = ay + tSeg * vy
			val dx = xRig - px; val dy = yRig - py
			val d2 = dx * dx + dy * dy
			if (d2 < bestDist2) {
				bestDist2 = d2
				bestSeg = i
				bestT = tSeg
			}
		}

		val i0 = bestSeg; val i1 = bestSeg + 1; val s = bestT; val oms = 1f - s
		val c0x = c0[i0 * 2] * oms + c0[i1 * 2] * s
		val c0y = c0[i0 * 2 + 1] * oms + c0[i1 * 2 + 1] * s
		val c1x = c1[i0 * 2] * oms + c1[i1 * 2] * s
		val c1y = c1[i0 * 2 + 1] * oms + c1[i1 * 2 + 1] * s

		var t0x = t0[i0 * 2] * oms + t0[i1 * 2] * s
		var t0y = t0[i0 * 2 + 1] * oms + t0[i1 * 2 + 1] * s
		val l0Len = hypot(t0x, t0y).coerceAtLeast(1e-8f)
		t0x /= l0Len; t0y /= l0Len
		val n0x = -t0y; val n0y = t0x

		var t1x = t1[i0 * 2] * oms + t1[i1 * 2] * s
		var t1y = t1[i0 * 2 + 1] * oms + t1[i1 * 2 + 1] * s
		val l1Len = hypot(t1x, t1y).coerceAtLeast(1e-8f)
		t1x /= l1Len; t1y /= l1Len
		val n1x = -t1y; val n1y = t1x

		val closeVal = ep.closing[i0] * oms + ep.closing[i1] * s
		val relX = xRig - c0x; val relY = yRig - c0y
		val tau = relX * t0x + relY * t0y
		val nu = relX * n0x + relY * n0y

		var nuScale = 1f - 0.22f * max(0f, -ep.pose.press) * closeVal
		if (!upper && target.isLayeredArt) {
			nuScale *= (1f - 0.30f * closeVal)
		}
		val keepPx = max(1.6f * target.linePx, 0.045f * target.widthPx)
		val nuClamped = nu.coerceIn(-keepPx, keepPx)
		val nuEff = nuClamped * nuScale + (nu - nuClamped) * (1f - closeVal)

		val posedX = c1x + tau * t1x + nuEff * n1x
		val posedY = c1y + tau * t1y + nuEff * n1y
		val uAt = (i0 + s) / (stations - 1).toFloat()
		return Triple(posedX - xRig, posedY - yRig, uAt)
	}

	private fun strokeMove(upper: Boolean, u: Float, v: Float, pose: Pose): Pair<Float, Float> {
		val ep = eval(pose)
		val (xRig, yRig) = fit.toCanvas(u, v)
		val (ownDx, ownDy, uAt) = strokeRaw(upper, xRig, yRig, ep)
		val s = (1f - min(uAt, 1f - uAt) / CORNER_ZONE).coerceIn(0f, 1f)
		if (s <= 0f) return rigDeltaToMouth(ownDx, ownDy)
		val (otherDx, otherDy, _) = strokeRaw(!upper, xRig, yRig, ep)
		val w = 0.5f * s * s * (3f - 2f * s)
		return rigDeltaToMouth(ownDx + (otherDx - ownDx) * w, ownDy + (otherDy - ownDy) * w)
	}

	private fun openingMove(u: Float, v: Float, pose: Pose): Pair<Float, Float> {
		if (!target.isLayeredArt && target.hasLines) {
			val uc = u.coerceIn(0f, 1f)
			val r = v - fit.baseline(u)
			val lined = min(line, (drawnLower(uc) - drawnUpper(uc)).coerceAtLeast(0f) / 2f)
			val (_, ux, uy) = nearestEdge(::drawnUpper, u, r)
			val (_, lx, ly) = nearestEdge(::drawnLower, u, r)
			val toUpper = hypot(ux, uy); val toLower = hypot(lx, ly)
			val upper = strokeMove(true, u, v, pose)
			if (r <= drawnUpper(uc) || toUpper <= lined) return upper
			val lower = strokeMove(false, u, v, pose)
			if (r >= drawnLower(uc) || toLower <= lined) return lower
			val a = (toUpper - lined).let { it * it }
			val b = (toLower - lined).let { it * it }
			val t = (a / (a + b).coerceAtLeast(1e-10f)).coerceIn(0f, 1f)
			return (upper.first + (lower.first - upper.first) * t) to (upper.second + (lower.second - upper.second) * t)
		}

		val ep = eval(pose)
		val (xRig, yRig) = fit.toCanvas(u, v)
		val (xLoc, yLoc) = target.toLocalPx(xRig, yRig)
		val ox = target.openColsXLoc
		val xFirst = ox.first(); val xLast = ox.last()
		val uMap = ((xLoc - xFirst) / (xLast - xFirst).coerceAtLeast(1e-4f)).coerceIn(0f, 1f)
		val extraX = when {
			xLoc < xFirst -> xLoc - xFirst
			xLoc > xLast -> xLoc - xLast
			else -> 0f
		}
		val top0 = interp1D(xLoc, ox, target.openTopYLoc)
		val bot0 = interp1D(xLoc, ox, target.openBotYLoc)
		val span0 = max(bot0 - top0, max(1.2f, 0.015f * target.widthPx))
		val frac = (yLoc - top0) / span0
		val fracCl = frac.coerceIn(0f, 1f)

		val f = uMap * (stations - 1)
		val idx = f.toInt().coerceAtMost(stations - 2)
		val s = f - idx; val oms = 1f - s
		val ux = ep.u1LocPx[idx * 2] * oms + ep.u1LocPx[(idx + 1) * 2] * s
		val uy = ep.u1LocPx[idx * 2 + 1] * oms + ep.u1LocPx[(idx + 1) * 2 + 1] * s
		val lx = ep.l1LocPx[idx * 2] * oms + ep.l1LocPx[(idx + 1) * 2] * s
		val ly = ep.l1LocPx[idx * 2 + 1] * oms + ep.l1LocPx[(idx + 1) * 2 + 1] * s

		val newXLoc = (1f - fracCl) * ux + fracCl * lx + extraX
		val newYLoc = (1f - frac) * uy + frac * ly
		val (dxRig, dyRig) = target.localDeltaToRig(newXLoc - xLoc, newYLoc - yLoc)
		return rigDeltaToMouth(dxRig, dyRig)
	}

	private fun perioralRimFollowLocal(xLoc: Float, yLoc: Float, ep: EvaluatedPose): Pair<Float, Float> {
		val w = target.widthPx
		val uxMin = target.uxMono0.first(); val uxMax = target.uxMono0.last()
		val lxMin = target.lxMono0.first(); val lxMax = target.lxMono0.last()

		val uu = interp1D(xLoc.coerceIn(uxMin, uxMax), target.uxMono0, uDense)
		val lu = interp1D(xLoc.coerceIn(lxMin, lxMax), target.lxMono0, uDense)

		val fu = uu * (stations - 1); val iu = fu.toInt().coerceAtMost(stations - 2); val su = fu - iu; val omu = 1f - su
		val fl = lu * (stations - 1); val il = fl.toInt().coerceAtMost(stations - 2); val sl = fl - il; val oml = 1f - sl

		val uy0 = target.u0LocPx[iu * 2 + 1] * omu + target.u0LocPx[(iu + 1) * 2 + 1] * su
		val ly0 = target.l0LocPx[il * 2 + 1] * oml + target.l0LocPx[(il + 1) * 2 + 1] * sl

		val duX = (ep.u1LocPx[iu * 2] - target.u0LocPx[iu * 2]) * omu + (ep.u1LocPx[(iu + 1) * 2] - target.u0LocPx[(iu + 1) * 2]) * su
		val duY = (ep.u1LocPx[iu * 2 + 1] - target.u0LocPx[iu * 2 + 1]) * omu + (ep.u1LocPx[(iu + 1) * 2 + 1] - target.u0LocPx[(iu + 1) * 2 + 1]) * su
		val dlX = (ep.l1LocPx[il * 2] - target.l0LocPx[il * 2]) * oml + (ep.l1LocPx[(il + 1) * 2] - target.l0LocPx[(il + 1) * 2]) * sl
		val dlY = (ep.l1LocPx[il * 2 + 1] - target.l0LocPx[il * 2 + 1]) * oml + (ep.l1LocPx[(il + 1) * 2 + 1] - target.l0LocPx[(il + 1) * 2 + 1]) * sl

		val midY = 0.5f * (uy0 + ly0)
		val sUlRaw = ((yLoc - (midY - 0.12f * w)) / (0.24f * w)).coerceIn(0f, 1f)
		val sUl = sUlRaw * sUlRaw * (3f - 2f * sUlRaw)
		val dRimX = (1f - sUl) * duX + sUl * dlX
		val dRimY = (1f - sUl) * duY + sUl * dlY

		val dxOut = max(0f, max(uxMin - xLoc, xLoc - uxMax))
		val dyUp = max(0f, uy0 - yLoc)
		val dyDn = max(0f, yLoc - ly0)
		val tx = ((dxOut - 0.20f * w) / (0.68f * w)).coerceIn(0f, 1f)

		val vxN = xLoc / max(w, 1e-4f)
		val noseCol = exp(-((vxN / 0.26f) * (vxN / 0.26f)))
		val nearUp = (0.14f * noseCol + 0.22f * (1f - noseCol)) * w
		val spanUp = (0.13f * noseCol + 0.36f * (1f - noseCol)) * w
		val tyU = ((dyUp - nearUp) / spanUp.coerceAtLeast(1e-4f)).coerceIn(0f, 1f)
		val tyD = ((dyDn - 0.20f * w) / (0.58f * w)).coerceIn(0f, 1f)
		val t = hypot(tx, max(tyU, tyD)).coerceIn(0f, 1f)
		val wP = 1f - t * t * (3f - 2f * t)
		return (wP * dRimX) to (wP * dRimY)
	}

	private fun lippedFaceMoveLocal(xLoc: Float, yLoc: Float, ep: EvaluatedPose, chin: Float): Pair<Float, Float> {
		val w = target.widthPx
		val vx = xLoc / max(w, 1e-4f)
		val vy = yLoc / max(w, 1e-4f)
		var dx = 0f
		var dy = 0f
		if (options.jaw || abs(ep.pose.jaw) > 1e-6f) {
			val chinSpan = max(0.35f, chin * 2.2f)
			val tJaw = ((vy - 0.22f) / chinSpan).coerceIn(0f, 1f)
			val wJaw = tJaw * tJaw * (3f - 2f * tJaw) * exp(-0.32f * vx * vx)
			val openJawTerm = if (options.jaw && target.isLayeredArt) 0.14f * (max(0f, ep.openRatio - 0.2f) - 0.8f) else 0f
			val jawAmt = (openJawTerm + 0.24f * ep.pose.jaw) * target.h0Px
			dy += wJaw * jawAmt
			dx += -vx * 0.10f * wJaw * jawAmt
		}
		if (ep.pose.cheek > 1e-5f) {
			val across = ((abs(vx) - 0.32f) / 0.85f).coerceIn(0f, 1f)
			val down = (1f - abs(vy - 0.18f) / 0.95f).coerceIn(0f, 1f)
			val wc = sin(0.5f * PI.toFloat() * across) * down * down
			dx += (if (vx >= 0f) 1f else -1f) * 0.11f * w * ep.pose.cheek * wc
			dy += 0.02f * w * ep.pose.cheek * wc
		}
		val (rimX, rimY) = perioralRimFollowLocal(xLoc, yLoc, ep)
		return (dx + rimX) to (dy + rimY)
	}

	private fun lipSkinMove(upper: Boolean, u: Float, v: Float, pose: Pose, chin: Float): Pair<Float, Float> {
		val ep = eval(pose)
		val (xRig, yRig) = fit.toCanvas(u, v)
		val (xLoc, yLoc) = target.toLocalPx(xRig, yRig)
		val w = target.widthPx

		val cxMono = if (upper) target.uxMono0 else target.lxMono0
		val c0Loc = if (upper) target.u0LocPx else target.l0LocPx
		val c1Loc = if (upper) ep.u1LocPx else ep.l1LocPx
		val xMin = cxMono.first(); val xMax = cxMono.last()

		val uParam = interp1D(xLoc.coerceIn(xMin, xMax), cxMono, uDense)
		val f = uParam * (stations - 1); val idx = f.toInt().coerceAtMost(stations - 2); val s = f - idx; val oms = 1f - s

		val y0Loc = c0Loc[idx * 2 + 1] * oms + c0Loc[(idx + 1) * 2 + 1] * s
		val dxLeft = max(0f, xMin - xLoc)
		val dxRight = max(0f, xLoc - xMax)
		val dyOut = if (upper) max(0f, y0Loc - yLoc) else max(0f, yLoc - y0Loc)

		val vxN = xLoc / max(w, 1e-4f)
		val noseCol = exp(-((vxN / 0.26f) * (vxN / 0.26f)))
		val pb = if (upper) target.upperLipBoundsLocal else target.lowerLipBoundsLocal
		val farL: Float; val farR: Float; val farY: Float
		if (pb != null) {
			farL = max(0.22f * w, 0.88f * (xMin - pb.left))
			farR = max(0.22f * w, 0.88f * (pb.right - xMax))
			if (upper) {
				var c0MinY = Float.POSITIVE_INFINITY
				for (i in 0 until stations) c0MinY = min(c0MinY, c0Loc[i * 2 + 1])
				val farYBase = max(0.26f * w, 0.88f * (c0MinY - pb.top))
				farY = noseCol * min(0.27f * w, farYBase) + (1f - noseCol) * (0.42f * w)
			} else {
				var c0MaxY = Float.NEGATIVE_INFINITY
				for (i in 0 until stations) c0MaxY = max(c0MaxY, c0Loc[i * 2 + 1])
				farY = max(0.28f * w, 0.88f * (pb.bottom - c0MaxY))
			}
		} else {
			farL = 0.45f * w; farR = 0.45f * w; farY = 0.45f * w
		}

		val nearX = 0.14f * w
		val nearY = if (upper) (0.14f * noseCol + 0.22f * (1f - noseCol)) * w else 0.18f * w
		val tx = max(
			((dxLeft - nearX) / (farL - nearX).coerceAtLeast(1e-4f)).coerceIn(0f, 1f),
			((dxRight - nearX) / (farR - nearX).coerceAtLeast(1e-4f)).coerceIn(0f, 1f),
		)
		val ty = ((dyOut - nearY) / (farY - nearY).coerceAtLeast(1e-4f)).coerceIn(0f, 1f)
		val t = hypot(tx, ty).coerceIn(0f, 1f)
		val wLip = 1f - t * t * (3f - 2f * t)

		val dRimX = (c1Loc[idx * 2] - c0Loc[idx * 2]) * oms + (c1Loc[(idx + 1) * 2] - c0Loc[(idx + 1) * 2]) * s
		var dRimY = (c1Loc[idx * 2 + 1] - c0Loc[idx * 2 + 1]) * oms + (c1Loc[(idx + 1) * 2 + 1] - c0Loc[(idx + 1) * 2 + 1]) * s
		val closeU = ep.closing[idx] * oms + ep.closing[idx + 1] * s
		dRimY += (if (upper) 1f else -1f) * 0.008f * w * closeU
		if (pose.press < -1e-5f) {
			val rollBand = sin(PI.toFloat() * (dyOut / (0.22f * w)).coerceIn(0f, 1f))
			val rollPx = (if (upper) 1f else -1f) * (-pose.press) * 0.045f * w * rollBand * sin(PI.toFloat() * uParam)
			dRimY += rollPx * (1f - t)
		}

		val (faceX, faceY) = lippedFaceMoveLocal(xLoc, yLoc, ep, chin)
		val dxLoc = wLip * dRimX + (1f - wLip) * faceX
		val dyLoc = wLip * dRimY + (1f - wLip) * faceY
		val (dxRig, dyRig) = target.localDeltaToRig(dxLoc, dyLoc)
		return rigDeltaToMouth(dxRig, dyRig)
	}

	internal fun teeth(u: Float, pose: Pose, upper: Boolean, depth: Float = 0f): Pair<Float, Float> {
		val uc = u.coerceIn(0f, 1f)
		val vDefault = fit.baseline(u) + if (upper) drawnUpper(uc) + 0.04f else drawnLower(uc) - 0.04f
		return teethAt(u, vDefault, pose, upper, depth)
	}

	private fun teethAt(u: Float, v: Float, pose: Pose, upper: Boolean, depth: Float = 0f): Pair<Float, Float> {
		val ep = eval(pose)
		val (xRig, yRig) = fit.toCanvas(u, v)
		val (xLoc, yLoc) = target.toLocalPx(xRig, yRig)
		val w = target.widthPx
		val xi = xLoc / max(w, 1e-4f)

		var dyBase = evalCubic(if (upper) ep.utCubic else ep.ltCubic, xi).first
		val formEff = (pose.form * options.formStrength).coerceIn(-1.5f, 1.5f)
		val smile = max(0f, formEff).coerceAtMost(1.5f)

		if (upper && depth > 0f && smile > 0f) {
			val (top, bottom) = edges(pose, 0.5f)
			val capMouth = top - drawnUpper(0.5f) + SMILE_TEETH_SHOW * (bottom - top).coerceAtLeast(0f) - depth
			val capPx = capMouth * fit.width
			if (capPx < dyBase) {
				dyBase += (capPx - dyBase) * smile.coerceAtMost(1f)
			}
		}

		val widenScale = if (options.teethWiden != 0f && smile > 0f) options.teethWiden * smile else 0f
		val dxLoc: Float
		val dyLoc: Float
		if (ep.hasAsym) {
			if (upper) {
				val relY = yLoc - ep.yUtPivot
				dxLoc = ep.axU + (ep.kxUt + widenScale) * xLoc - ep.sinThetaUt * relY
				dyLoc = dyBase + (ep.cosThetaUt - 1f) * relY
			} else {
				val relY = yLoc - ep.yLtPivot
				dxLoc = ep.axL + (ep.kxLt + widenScale) * xLoc - ep.sinThetaLt * relY
				dyLoc = dyBase + (ep.cosThetaLt - 1f) * relY
			}
		} else {
			dxLoc = widenScale * xLoc
			dyLoc = dyBase
		}
		val (dxRig, dyRig) = target.localDeltaToRig(dxLoc, dyLoc)
		return rigDeltaToMouth(dxRig, dyRig)
	}

	private fun tongueMove(u: Float, v: Float, pose: Pose, protruding: Boolean): Pair<Float, Float> {
		val ep = eval(pose)
		val (xRig, yRig) = fit.toCanvas(u, v)
		val (xLoc, yLoc) = target.toLocalPx(xRig, yRig)
		val w = target.widthPx

		if (pose.tongueOut <= 0.02f) {
			val dxLoc: Float
			val dyLoc: Float
			if (!ep.hasAsym) {
				dxLoc = ep.tongueDx
				dyLoc = TONGUE_SHARE * ep.dlSymMid
			} else {
				val xi = xLoc / max(w, 1e-4f)
				val (dyAsym, dAsymDxi) = evalCubic(ep.tngAsymCubic, xi)
				val slope = dAsymDxi / max(w, 1e-4f)
				val theta = atan(slope).coerceIn(-0.50f, 0.50f)
				dxLoc = ep.tongueDx + 0.25f * ep.dxxMean * xLoc - 0.70f * sin(theta) * yLoc
				dyLoc = TONGUE_SHARE * ep.dlSymMid + dyAsym + (cos(theta) - 1f) * yLoc
			}
			val (dxRig, dyRig) = target.localDeltaToRig(dxLoc, dyLoc)
			return rigDeltaToMouth(dxRig, dyRig)
		}

		// Anatomical TongueOut: anchor root inside open mouth, extend tip downward, taper tip width inside corners.
		val tb = target.tongueBoundsLocal ?: Bounds(-0.28f * w, target.u0LocPx[midIdx * 2 + 1], 0.28f * w, target.l0LocPx[midIdx * 2 + 1])
		val dlMid = ep.l1LocPx[midIdx * 2 + 1] - target.l0LocPx[midIdx * 2 + 1]
		val u1MidY = ep.u1LocPx[midIdx * 2 + 1]
		val l1MidY = ep.l1LocPx[midIdx * 2 + 1]
		var yTopNew = tb.top + 0.45f * dlMid
		if (protruding) {
			yTopNew = max(yTopNew, u1MidY + 0.35f * target.linePx)
		}
		var yBotNew = l1MidY + (-0.02f + 0.62f * pose.tongueOut) * target.h0Px
		yBotNew = max(yBotNew, yTopNew + 0.35f * tb.height.coerceAtLeast(1f))

		val s = (yLoc - tb.top) / tb.height.coerceAtLeast(1f)
		val sCl = s.coerceIn(0f, 1f)
		var yNew = yTopNew + s * (yBotNew - yTopNew)

		var uMinX = Float.POSITIVE_INFINITY
		var uMaxX = Float.NEGATIVE_INFINITY
		for (i in 0 until stations) {
			val ux = ep.u1LocPx[i * 2]
			if (ux < uMinX) uMinX = ux
			if (ux > uMaxX) uMaxX = ux
		}
		val openSpan = max(uMaxX - uMinX, 1f)
		val targetTngW = 0.78f * openSpan
		val baseTngW = tb.width.coerceAtLeast(1f)
		val sxTip = min(1f, targetTngW / baseTngW)
		val sxRow = 1f - (1f - sxTip) * (0.25f + 0.75f * sCl) * min(1f, pose.tongueOut * 1.3f)
		val txMid = tb.centerX
		val openMidX = 0.5f * (ep.u1LocPx[midIdx * 2] + ep.l1LocPx[midIdx * 2])
		val xCenter = (1f - sCl) * (txMid + ep.tongueDx) + sCl * openMidX
		val xNew = xCenter + (xLoc - txMid) * sxRow

		if (protruding) {
			// Guarantee unclipped protruding tongue never pokes above the posed upper lip contour.
			val uAtX = ((xNew - uMinX) / openSpan).coerceIn(0f, 1f)
			val f = uAtX * (stations - 1); val idx = f.toInt().coerceAtMost(stations - 2); val frac = f - idx
			val uYAtX = ep.u1LocPx[idx * 2 + 1] * (1f - frac) + ep.u1LocPx[(idx + 1) * 2 + 1] * frac
			yNew = max(yNew, uYAtX + 0.35f * target.linePx)
		}

		val (dxRig, dyRig) = target.localDeltaToRig(xNew - xLoc, yNew - yLoc)
		return rigDeltaToMouth(dxRig, dyRig)
	}

	private fun deepMove(pose: Pose): Pair<Float, Float> {
		val ep = eval(pose)
		if (abs(ep.shiftXPx) <= 1e-6f) return 0f to 0f
		val (dxRig, dyRig) = target.localDeltaToRig(0.75f * ep.shiftXPx, 0f)
		return rigDeltaToMouth(dxRig, dyRig)
	}

	private fun nearestEdge(edge: (Float) -> Float, u: Float, r: Float): Triple<Float, Float, Float> {
		var best = 0f; var bestDistance = Float.MAX_VALUE
		for (k in 0..NEAREST_STEPS) {
			val at = k.toFloat() / NEAREST_STEPS
			val dx = u - at; val dy = r - edge(at)
			val distance = dx * dx + dy * dy
			if (distance < bestDistance) { bestDistance = distance; best = at }
		}
		return Triple(best, u - best, r - edge(best))
	}

	/** The mouth at [form] (−1 pout … 1 smile) and [open] (0 … 1). */
	fun pose(form: Float, open: Float): Pose = Pose(open = open, form = form)

	/** The vowel poses lip sync drives, each over the closed mouth (and openness-limited when combined with `ParamMouthOpenY`). */
	fun vowel(name: Char): Pose = when (name) {
		'A' -> Pose(open = 0.92f, form = 0.06f, shrug = 0.10f)
		'I' -> Pose(open = 0.32f, form = 0.75f, width = 0.55f)
		'U' -> Pose(open = 0.34f, form = -0.35f, funnel = 0.95f, width = -0.65f)
		'E' -> Pose(open = 0.54f, form = 0.35f, width = 0.30f)
		else -> Pose(open = 0.72f, form = -0.45f, funnel = 0.85f, width = -0.45f)
	}

	/**
	 * [pose] (at [open]) with the lips pressed ([press] < 0) or parted ([press] > 0).
	 */
	fun pressed(pose: Pose, press: Float, open: Float): Pose = pose.copy(open = open, press = press)

	private fun teethDepth(positions: FloatArray): Float {
		var depth = 0f
		for (i in positions.indices step 2) {
			val (u, v) = fit.toMouth(positions[i], positions[i + 1])
			if (abs(u - 0.5f) > 0.2f) continue
			depth = max(depth, v - fit.baseline(u) - drawnUpper(u.coerceIn(0f, 1f)))
		}
		return depth
	}

	private fun form(positions: FloatArray, frame: Bounds, pose: Pose, follow: Follow = Follow.WHOLE, chin: Float = 0.6f): MeshDeltaForm {
		val depth = if (follow == Follow.UPPER_TEETH) teethDepth(positions) else 0f
		return form(positions, frame) { u, v -> moveAsInternal(follow, u, v, pose, chin, depth) }
	}

	private fun form(positions: FloatArray, frame: Bounds, change: (Float, Float) -> Pair<Float, Float>): MeshDeltaForm {
		val out = FloatArray(positions.size)
		val invW = 1f / frame.width.coerceAtLeast(1e-4f)
		val invH = 1f / frame.height.coerceAtLeast(1e-4f)
		for (i in positions.indices step 2) {
			val (u, v) = fit.toMouth(positions[i], positions[i + 1])
			val (du, dv) = change(u, v)
			val (dx, dy) = fit.canvasDelta(du, dv)
			out[i] = dx * invW
			out[i + 1] = dy * invH
		}
		return MeshDeltaForm(out)
	}

	private fun faceForm(positions: FloatArray, frame: Bounds, pose: Pose, chin: Float): MeshDeltaForm {
		val ep = eval(pose)
		val out = FloatArray(positions.size)
		val invW = 1f / frame.width.coerceAtLeast(1e-4f)
		val invH = 1f / frame.height.coerceAtLeast(1e-4f)
		for (i in positions.indices step 2) {
			val (xLoc, yLoc) = target.toLocalPx(positions[i], positions[i + 1])
			val (dxLoc, dyLoc) = lippedFaceMoveLocal(xLoc, yLoc, ep, chin)
			val (dxRig, dyRig) = target.localDeltaToRig(dxLoc, dyLoc)
			out[i] = dxRig * invW
			out[i + 1] = dyRig * invH
		}
		return MeshDeltaForm(out)
	}

	/** [positions] (x, y pairs) where the mouth at rest - shut - holds them, a layer moving as [follow]. */
	fun atRest(positions: FloatArray, follow: Follow, chin: Float = 0.6f): FloatArray {
		val rest = pressed(pose(0f, 0f), 0f, 0f)
		val depth = if (follow == Follow.UPPER_TEETH) teethDepth(positions) else 0f
		return FloatArray(positions.size).also { out ->
			for (i in positions.indices step 2) {
				val (u, v) = fit.toMouth(positions[i], positions[i + 1])
				val (du, dv) = moveAsInternal(follow, u, v, rest, chin, depth)
				val (dx, dy) = fit.canvasDelta(du, dv)
				out[i] = positions[i] + dx; out[i + 1] = positions[i + 1] + dy
			}
		}
	}

	/** [positions] of a lip-skin mouth's underlying `FACE` mesh at shut rest, matching [faceGrid] seamlessly. */
	fun atRestFace(positions: FloatArray, chin: Float): FloatArray {
		val ep = shutEval
		return FloatArray(positions.size).also { out ->
			for (i in positions.indices step 2) {
				val (xLoc, yLoc) = target.toLocalPx(positions[i], positions[i + 1])
				val (dxLoc, dyLoc) = lippedFaceMoveLocal(xLoc, yLoc, ep, chin)
				val (dxRig, dyRig) = target.localDeltaToRig(dxLoc, dyLoc)
				out[i] = positions[i] + dxRig
				out[i + 1] = positions[i + 1] + dyRig
			}
		}
	}

	/** A mouth layer's grid: its form by its openness. Deltas are over the parent [frame]; the layer moves as [follow]. */
	fun grid(positions: FloatArray, frame: Bounds, mouthForm: ParameterId, mouthOpen: ParameterId, follow: Follow = Follow.WHOLE, chin: Float = 0.6f): KeyformGrid<MeshDeltaForm> =
		keyed(mouthForm, mouthOpen) { pose -> form(positions, frame, pose, follow, chin) }

	/** A lip-skin mouth's shared perioral + jaw grid on `FACE`, matching `UPPER_LIP`/`LOWER_LIP` outer borders with zero seam. */
	fun faceGrid(positions: FloatArray, frame: Bounds, mouthForm: ParameterId, mouthOpen: ParameterId, chin: Float): KeyformGrid<MeshDeltaForm> =
		keyed(mouthForm, mouthOpen) { pose -> faceForm(positions, frame, pose, chin) }

	/**
	 * A grid over the form and the openness - and with the face capture, the lips pressed or parted ([pressed]).
	 */
	fun keyed(mouthForm: ParameterId, mouthOpen: ParameterId, form: (Pose) -> MeshDeltaForm): KeyformGrid<MeshDeltaForm> {
		val cells = ArrayList<KeyformCell<MeshDeltaForm>>()
		val presses = if (options.capture) PRESS_KEYS else floatArrayOf(0f)
		for ((p, press) in presses.withIndex()) for ((o, open) in openKeys.withIndex()) for ((f, value) in FORM_KEYS.withIndex()) {
			val pose = pressed(pose(value, open), press, open)
			cells += KeyformCell(if (options.capture) intArrayOf(f, o, p) else intArrayOf(f, o), form(pose))
		}
		val axes = listOf(KeyformAxis(mouthForm, FORM_KEYS), KeyformAxis(mouthOpen, openKeys)) +
			if (options.capture) listOf(KeyformAxis(PRESS_OPEN, PRESS_KEYS)) else emptyList()
		return KeyformGrid(axes, cells)
	}

	/** Where the point ([x], [y]) of the mouth (in the rig's space) goes in [pose]. */
	fun point(x: Float, y: Float, pose: Pose): Pair<Float, Float> {
		val (u, v) = fit.toMouth(x, y)
		val (du, dv) = move(u, v, pose, tight = true)
		val (dx, dy) = fit.canvasDelta(du, dv)
		return (x + dx) to (y + dy)
	}

	/**
	 * A drawn closed mouth's grid: it bends with the form - corners up into a smile, down into a pout - narrowing or
	 * widening about its middle. It shows only while the mouth is shut.
	 */
	fun closedGrid(positions: FloatArray, frame: Bounds, mouthForm: ParameterId): KeyformGrid<MeshDeltaForm> =
		KeyformGrid(listOf(KeyformAxis(mouthForm, FORM_KEYS)), FORM_KEYS.mapIndexed { f, formVal ->
			KeyformCell(intArrayOf(f), form(positions, frame) { u, v -> closedMove(u, v, pose(formVal, 0f)) })
		})

	private fun closedMove(u: Float, v: Float, pose: Pose): Pair<Float, Float> {
		val ep = eval(pose.copy(open = 0f))
		val ref = shutEval
		val uc = u.coerceIn(0f, 1f)
		val f = uc * (stations - 1)
		val idx = f.toInt().coerceAtMost(stations - 2)
		val s = f - idx; val oms = 1f - s

		val refMidU = 0.5f * ((ref.u1MouthU[idx] + ref.l1MouthU[idx]) * oms + (ref.u1MouthU[idx + 1] + ref.l1MouthU[idx + 1]) * s)
		val refMidV = 0.5f * ((ref.u1MouthV[idx] + ref.l1MouthV[idx]) * oms + (ref.u1MouthV[idx + 1] + ref.l1MouthV[idx + 1]) * s)
		val curMidU = 0.5f * ((ep.u1MouthU[idx] + ep.l1MouthU[idx]) * oms + (ep.u1MouthU[idx + 1] + ep.l1MouthU[idx + 1]) * s)
		val curMidV = 0.5f * ((ep.u1MouthV[idx] + ep.l1MouthV[idx]) * oms + (ep.u1MouthV[idx + 1] + ep.l1MouthV[idx + 1]) * s)

		val widthRatio = ((ep.u1MouthU.last() - ep.u1MouthU.first()) / (ref.u1MouthU.last() - ref.u1MouthU.first()).coerceAtLeast(0.2f))
		val un = curMidU + (u - refMidU) * widthRatio
		val vn = v + (fit.baseline(un) - fit.baseline(u)) + (curMidV - refMidV)
		return (un - u) to (vn - v)
	}

	/**
	 * The mouth's additive shapes (vowels + bilateral & unilateral facial capture + tongue-out).
	 */
	fun shapes(
		positions: FloatArray,
		frame: Bounds,
		closedLayer: Boolean,
		visible: (Boolean) -> Float,
		tongue: Boolean = false,
		follow: Follow = Follow.WHOLE,
		mouthOpen: ParameterId = ParameterId("ParamMouthOpenY"),
		chin: Float = 0.6f,
	): List<BlendShapeBinding<MeshForm>> {
		val innerCaptureAtOpen = !closedLayer && (follow == Follow.UPPER_TEETH || follow == Follow.LOWER_TEETH ||
			follow == Follow.TONGUE || follow == Follow.PROTRUDING_TONGUE)
		val baseFn: (Pose) -> FloatArray = { pose ->
			(if (closedLayer) form(positions, frame) { u, v -> closedMove(u, v, pose) }
			else form(positions, frame, pose, follow, chin)).positionDeltas
		}
		return buildShapeBindings(baseFn, innerCaptureAtOpen, pressKeyed = !closedLayer, visible = visible, mouthOpen = mouthOpen) +
			tongueShapes(positions, frame, closedLayer, follow, visible, mouthOpen, chin) +
			if (options.capture && follow in setOf(Follow.UPPER_LIP, Follow.LOWER_LIP)) listOf(
				BlendShapeBinding(CHEEK_PUFF, floatArrayOf(0f, 1f), 0, listOf(null,
					MeshForm(baseFn(pose(0f, 0f).copy(cheek = 1f)), opacity = Float.NaN)))) else emptyList()
	}

	/**
	 * Companion unclipped protruding tongue bindings (`<tongueId>_Out`): completely transparent (`0f`) at rest and on all
	 * non-tongue bindings, ramping to [sourceOpacity] on `ParamTongueOut > 0` while keeping `ParamTongueOut` a pure
	 * `ParameterKind.BLEND_SHAPE`.
	 */
	fun protrudingTongueShapes(
		positions: FloatArray,
		frame: Bounds,
		sourceOpacity: Float,
		mouthOpen: ParameterId = ParameterId("ParamMouthOpenY"),
		chin: Float = 0.6f,
	): List<BlendShapeBinding<MeshForm>> {
		val baseFn: (Pose) -> FloatArray = { pose -> form(positions, frame, pose, Follow.PROTRUDING_TONGUE, chin).positionDeltas }
		val nonTongue = buildShapeBindings(baseFn, innerCaptureAtOpen = true, pressKeyed = true, visible = { Float.NaN }, mouthOpen = mouthOpen)
		if (!options.capture || !options.tongue) return nonTongue
		val poseEarly = Pose(open = 0.48f, width = -0.05f, tongueOut = 0.12f)
		val poseFull = Pose(open = 0.56f, width = -0.05f, tongueOut = 1f)
		val tngBinding = BlendShapeBinding(
			TONGUE_OUT,
			floatArrayOf(0f, 0.12f, 1f),
			0,
			listOf(
				null,
				MeshForm(baseFn(poseEarly), opacity = sourceOpacity),
				MeshForm(baseFn(poseFull), opacity = sourceOpacity),
			),
		)
		return nonTongue + tngBinding
	}

	private fun tongueShapes(
		positions: FloatArray,
		frame: Bounds,
		closedLayer: Boolean,
		follow: Follow,
		visible: (Boolean) -> Float,
		mouthOpen: ParameterId,
		chin: Float,
	): List<BlendShapeBinding<MeshForm>> {
		if (!options.capture || !options.tongue) return emptyList()
		val openPose = Pose(open = 0.56f, width = -0.05f, tongueOut = 1f)
		val deltas = if (closedLayer) form(positions, frame) { u, v -> closedMove(u, v, openPose) }.positionDeltas
			else form(positions, frame, openPose, follow, chin).positionDeltas
		val isTongueMesh = follow == Follow.TONGUE || follow == Follow.PROTRUDING_TONGUE
		val limits = if (isTongueMesh || closedLayer) emptyList() else listOf(
			BlendWeightLimit(mouthOpen, listOf(
				BlendWeightLimitPoint(0f, 1f),
				BlendWeightLimitPoint(0.55f, 0.25f),
				BlendWeightLimitPoint(1f, 0.08f),
			)),
		)
		return listOf(BlendShapeBinding(TONGUE_OUT, floatArrayOf(0f, 1f), 0, listOf(null, MeshForm(deltas, opacity = visible(true))), limits = limits))
	}

	/**
	 * The same shapes for a mesh whose deltas at a pose [deltas] gives (such as a generated lip ribbon).
	 */
	fun shapes(
		deltas: (Pose) -> FloatArray,
		pressKeyed: Boolean = true,
		visible: (Boolean) -> Float,
		mouthOpen: ParameterId = ParameterId("ParamMouthOpenY"),
	): List<BlendShapeBinding<MeshForm>> = buildShapeBindings(deltas, innerCaptureAtOpen = false, pressKeyed = pressKeyed, visible = visible, mouthOpen = mouthOpen)

	private fun buildShapeBindings(
		deltas: (Pose) -> FloatArray,
		innerCaptureAtOpen: Boolean,
		pressKeyed: Boolean,
		visible: (Boolean) -> Float,
		mouthOpen: ParameterId,
	): List<BlendShapeBinding<MeshForm>> = buildList {
		fun shapeOf(pose: Pose, opens: Boolean): MeshForm = MeshForm(deltas(pose), opacity = visible(opens))
		val rest = pose(0f, 0f)
		val restDeltas by lazy { deltas(rest) }
		val openRef = Pose(open = 0.30f)
		val openRefDeltas by lazy { deltas(openRef) }

		fun captureForm(poseAtZero: Pose): MeshForm {
			if (!innerCaptureAtOpen) return shapeOf(poseAtZero, false)
			val posedAtOpen = deltas(poseAtZero.copy(open = 0.30f))
			val combined = FloatArray(restDeltas.size) { i -> restDeltas[i] + (posedAtOpen[i] - openRefDeltas[i]) }
			return MeshForm(combined, opacity = visible(false))
		}

		if (options.vowels) {
			for ((name, id) in VOWELS) {
				val vLimits = listOf(BlendWeightLimit(mouthOpen, vowelLimitPoints(name)))
				add(BlendShapeBinding(id, floatArrayOf(0f, 1f), 0, listOf(null, shapeOf(vowel(name), true)), limits = vLimits))
			}
		}
		if (options.capture) {
			add(BlendShapeBinding(MOUTH_X, floatArrayOf(-1f, 0f, 1f), 1, listOf(
				captureForm(rest.copy(mouthX = -1f)), null, captureForm(rest.copy(mouthX = 1f)),
			)))
			add(BlendShapeBinding(CORNER_L, floatArrayOf(-1f, 0f, 1f), 1, listOf(
				captureForm(rest.copy(cornerLDown = 1f)), null, captureForm(rest.copy(cornerLUp = 1f)),
			)))
			add(BlendShapeBinding(CORNER_R, floatArrayOf(-1f, 0f, 1f), 1, listOf(
				captureForm(rest.copy(cornerRDown = 1f)), null, captureForm(rest.copy(cornerRUp = 1f)),
			)))
			add(BlendShapeBinding(PUCKER_WIDEN, floatArrayOf(-1f, 0f, 1f), 1, listOf(
				captureForm(rest.copy(width = -1f)), null, captureForm(rest.copy(width = 1f)),
			)))
			add(BlendShapeBinding(PUCKER_WIDEN_L, floatArrayOf(-1f, 0f, 1f), 1, listOf(
				captureForm(rest.copy(puckerL = 1f)), null, captureForm(rest.copy(cornerLOut = 1f)),
			)))
			add(BlendShapeBinding(PUCKER_WIDEN_R, floatArrayOf(-1f, 0f, 1f), 1, listOf(
				captureForm(rest.copy(puckerR = 1f)), null, captureForm(rest.copy(cornerROut = 1f)),
			)))
			add(BlendShapeBinding(SHRUG, floatArrayOf(0f, 1f), 0, listOf(
				null, captureForm(rest.copy(shrug = 1f)),
			)))
			val funnelLimits = listOf(BlendWeightLimit(mouthOpen, listOf(
				BlendWeightLimitPoint(0f, 1f),
				BlendWeightLimitPoint(0.45f, 0.65f),
				BlendWeightLimitPoint(0.95f, 0.35f),
			)))
			add(BlendShapeBinding(FUNNEL, floatArrayOf(0f, 1f), 0, listOf(
				null, shapeOf(Pose(open = 0.34f, funnel = 1f, width = -0.35f), true),
			), limits = funnelLimits))
			val jawLimits = listOf(BlendWeightLimit(mouthOpen, listOf(
				BlendWeightLimitPoint(0f, 1f),
				BlendWeightLimitPoint(0.25f, 0.17f),
				BlendWeightLimitPoint(0.55f, 0f),
			)))
			add(BlendShapeBinding(JAW_OPEN, floatArrayOf(0f, 1f), 0, listOf(
				null, shapeOf(rest.copy(jaw = 1f), false),
			), limits = jawLimits))
			if (pressKeyed) {
				val shows = visible(true)
				if (!shows.isNaN()) add(BlendShapeBinding(PRESS_OPEN, floatArrayOf(0f, 1f), 0, listOf(null, MeshForm(restDeltas, opacity = shows))))
			} else {
				add(BlendShapeBinding(PRESS_OPEN, floatArrayOf(-1f, 0f, 1f), 1, listOf(
					shapeOf(pressed(rest, -1f, 0f), false), null, shapeOf(pressed(rest, 1f, 0f), true),
				)))
			}
		}
	}

	/**
	 * Lower-face jaw, cheek-puff, and (when [lippedSkin] is true) shared perioral rim-follow shapes on `FACE`.
	 */
	fun faceShapes(
		positions: FloatArray,
		frame: Bounds,
		chin: Float,
		nose: Boolean,
		mouthOpen: ParameterId,
		spring: ParameterId?,
		lippedSkin: Boolean = false,
	): List<BlendShapeBinding<MeshForm>> {
		if (lippedSkin && !nose) {
			val baseFn: (Pose) -> FloatArray = { pose -> faceForm(positions, frame, pose, chin).positionDeltas }
			return buildList {
				addAll(buildShapeBindings(baseFn, innerCaptureAtOpen = false, pressKeyed = true, visible = { Float.NaN }, mouthOpen = mouthOpen))
				if (options.capture && options.tongue) {
					val openPose = Pose(open = 0.56f, width = -0.05f, tongueOut = 1f)
					val limits = listOf(BlendWeightLimit(mouthOpen, listOf(
						BlendWeightLimitPoint(0f, 1f),
						BlendWeightLimitPoint(0.55f, 0.25f),
						BlendWeightLimitPoint(1f, 0.08f),
					)))
					add(BlendShapeBinding(TONGUE_OUT, floatArrayOf(0f, 1f), 0, listOf(null, MeshForm(baseFn(openPose), opacity = Float.NaN)), limits = limits))
				}
				if (options.capture) {
					add(BlendShapeBinding(CHEEK_PUFF, floatArrayOf(0f, 1f), 0, listOf(
						null, MeshForm(baseFn(pose(0f, 0f).copy(cheek = 1f)), opacity = Float.NaN),
					)))
				}
				if (options.jaw && spring != null) {
					val restD = baseFn(pose(0f, 0f))
					val openD = baseFn(pose(0f, 1f))
					fun springForm(scale: Float) = MeshForm(
						FloatArray(restD.size) { i -> restD[i] + scale * (openD[i] - restD[i]) },
						opacity = Float.NaN,
					)
					add(BlendShapeBinding(spring, floatArrayOf(-1f, 0f, 1f), 1, listOf(springForm(-0.35f), null, springForm(0.35f))))
				}
			}
		}

		return buildList {
			val drop = options.jawShare * h
			fun weight(v: Float) = ((v - 0.02f) / (chin - 0.02f).coerceAtLeast(0.05f)).coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }
			fun jaw(amount: Float) = MeshForm(form(positions, frame) { u, v ->
				if (nose) 0f to amount * drop * 0.08f
				else weight(v).let { w -> -(u - 0.5f) * 0.25f * amount * drop * w to amount * drop * w }
			}.positionDeltas, opacity = Float.NaN)
			if (options.jaw) {
				add(if (options.reach > 1.001f) BlendShapeBinding(mouthOpen, floatArrayOf(0f, 1f, options.reach), 0, listOf(null, jaw(1f), jaw(options.reach)))
					else BlendShapeBinding(mouthOpen, floatArrayOf(0f, 1f), 0, listOf(null, jaw(1f))))
				if (spring != null) add(BlendShapeBinding(spring, floatArrayOf(-1f, 0f, 1f), 1, listOf(jaw(-0.35f), null, jaw(0.35f))))
			}
			if (options.capture) {
				val jawLimits = listOf(BlendWeightLimit(mouthOpen, listOf(
					BlendWeightLimitPoint(0f, 1f),
					BlendWeightLimitPoint(0.25f, 0.17f),
					BlendWeightLimitPoint(0.55f, 0f),
				)))
				add(BlendShapeBinding(JAW_OPEN, floatArrayOf(0f, 1f), 0, listOf(null, jaw(1.2f)), limits = jawLimits))
			}
			if (options.capture && !nose) add(BlendShapeBinding(CHEEK_PUFF, floatArrayOf(0f, 1f), 0, listOf(null, MeshForm(form(positions, frame) { u, v ->
				val side = u - 0.5f
				val across = ((abs(side) - 0.35f) / 0.6f).coerceIn(0f, 1f)
				val down = (1f - abs(v - 0.15f) / 0.75f).coerceIn(0f, 1f)
				val w = sin(across * PI.toFloat() / 2f) * down * down
				(if (side >= 0f) 1f else -1f) * 0.1f * w to 0.02f * w
			}.positionDeltas, opacity = Float.NaN))))
		}
	}

	/**
	 * The mouth's inside lagging behind its lips as the head turns (`mouth.interior-depth`).
	 */
	fun depthShapes(positions: FloatArray, frame: Bounds, depth: Float, angleX: ParameterId, angleY: ParameterId): List<BlendShapeBinding<MeshForm>> {
		if (!options.depth) return emptyList()
		val d = depth * options.depthAmount
		fun shift(du: Float, dv: Float) = MeshForm(form(positions, frame) { _, _ -> du to dv }.positionDeltas, opacity = Float.NaN)
		val yaw = sin(Math.toRadians(30.0)).toFloat() * d
		return listOf(
			BlendShapeBinding(angleX, floatArrayOf(-30f, 0f, 30f), 1, listOf(shift(yaw * (if (fit.axisX >= 0f) 1f else -1f), 0f), null,
				shift(-yaw * (if (fit.axisX >= 0f) 1f else -1f), 0f))),
			BlendShapeBinding(angleY, floatArrayOf(-30f, 0f, 30f), 1, listOf(shift(0f, -yaw * 0.6f), null, shift(0f, yaw * 0.6f))),
		)
	}

	companion object {
		/** The openness where the lips part and a drawn closed mouth hands over to the open one. */
		const val SWAP = 0.05f
		private const val NEAREST_STEPS = 96
		val OPEN_KEYS = floatArrayOf(0f, SWAP, 0.25f, 0.5f, 1f)
		val PRESS_KEYS = floatArrayOf(-1f, 0f, 1f)
		val FORM_KEYS = floatArrayOf(-1f, -0.5f, 0f, 0.5f, 1f)
		/** How far from a corner, of the width, the two lip lines' ends blend together. */
		private const val CORNER_ZONE = 0.10f
		/** How much of a smile's opening the upper teeth fill at most, from its top. */
		private const val SMILE_TEETH_SHOW = 0.5f
		private const val TONGUE_SHARE = 0.35f

		val VOWELS = listOf('A', 'I', 'U', 'E', 'O').map { it to ParameterId("Param$it") }
		val MOUTH_X = ParameterId("ParamMouthX")
		val CORNER_L = ParameterId("ParamMouthCornerL")
		val CORNER_R = ParameterId("ParamMouthCornerR")
		val PUCKER_WIDEN = ParameterId("ParamMouthPuckerWiden")
		val PUCKER_WIDEN_L = ParameterId("ParamMouthPuckerWidenL")
		val PUCKER_WIDEN_R = ParameterId("ParamMouthPuckerWidenR")
		val SHRUG = ParameterId("ParamMouthShrug")
		val FUNNEL = ParameterId("ParamMouthFunnel")
		val PRESS_OPEN = ParameterId("ParamMouthPressLipOpen")
		val CHEEK_PUFF = ParameterId("ParamCheekPuff")
		val JAW_OPEN = ParameterId("ParamJawOpen")
		val TONGUE_OUT = ParameterId("ParamTongueOut")
		val SPRING = ParameterId("ParamMouthSpring")
		/** The shapes that open the mouth: they show its open parts and hide a drawn closed mouth. */
		val OPENING = VOWELS.map { it.second }.toSet() + PRESS_OPEN + TONGUE_OUT + FUNNEL

		private fun vowelLimitPoints(name: Char): List<BlendWeightLimitPoint> = when (name) {
			'A' -> listOf(
				BlendWeightLimitPoint(0f, 1f),
				BlendWeightLimitPoint(0.55f, 0.45f),
				BlendWeightLimitPoint(0.95f, 0.08f),
			)
			'I' -> listOf(
				BlendWeightLimitPoint(0f, 1f),
				BlendWeightLimitPoint(0.35f, 0.55f),
				BlendWeightLimitPoint(0.85f, 0.30f),
			)
			'U' -> listOf(
				BlendWeightLimitPoint(0f, 1f),
				BlendWeightLimitPoint(0.35f, 0.60f),
				BlendWeightLimitPoint(0.85f, 0.30f),
			)
			'E' -> listOf(
				BlendWeightLimitPoint(0f, 1f),
				BlendWeightLimitPoint(0.45f, 0.50f),
				BlendWeightLimitPoint(0.85f, 0.22f),
			)
			else -> listOf(
				BlendWeightLimitPoint(0f, 1f),
				BlendWeightLimitPoint(0.45f, 0.55f),
				BlendWeightLimitPoint(0.85f, 0.28f),
			)
		}

		private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
			val t = ((x - edge0) / (edge1 - edge0).coerceAtLeast(1e-6f)).coerceIn(0f, 1f)
			return t * t * (3f - 2f * t)
		}

		private fun interp1D(x: Float, xs: FloatArray, ys: FloatArray): Float {
			if (xs.isEmpty()) return 0f
			if (x <= xs.first()) return ys.first()
			if (x >= xs.last()) return ys.last()
			var lo = 0
			var hi = xs.size - 1
			while (lo + 1 < hi) {
				val mid = (lo + hi) ushr 1
				if (xs[mid] <= x) lo = mid else hi = mid
			}
			val span = xs[hi] - xs[lo]
			val t = if (span > 1e-8f) ((x - xs[lo]) / span).coerceIn(0f, 1f) else 0f
			return ys[lo] + t * (ys[hi] - ys[lo])
		}

		private fun fitLine(xs: FloatArray, ys: FloatArray): Pair<Float, Float> {
			val n = xs.size
			if (n == 0) return 0f to 0f
			var mx = 0.0; var my = 0.0
			for (i in 0 until n) { mx += xs[i]; my += ys[i] }
			mx /= n; my /= n
			var num = 0.0; var den = 0.0
			for (i in 0 until n) {
				val dx = xs[i] - mx
				num += dx * (ys[i] - my)
				den += dx * dx
			}
			val slope = if (den > 1e-12) (num / den).toFloat() else 0f
			val intercept = (my - slope * mx).toFloat()
			return intercept to slope
		}

		private fun fitCubicCoeffs(xi: FloatArray, ys: FloatArray, ridge: Float): FloatArray {
			val a = Array(4) { DoubleArray(4) }
			val b = DoubleArray(4)
			for (i in xi.indices) {
				val x1 = xi[i].toDouble()
				val x2 = x1 * x1
				val x3 = x2 * x1
				val row = doubleArrayOf(1.0, x1, x2, x3)
				val y = ys[i].toDouble()
				for (r in 0..3) {
					b[r] += row[r] * y
					for (c in 0..3) a[r][c] += row[r] * row[c]
				}
			}
			a[1][1] += ridge.toDouble()
			a[2][2] += 2.0 * ridge
			a[3][3] += 4.0 * ridge

			for (p in 0..3) {
				var maxRow = p
				for (r in p + 1..3) if (abs(a[r][p]) > abs(a[maxRow][p])) maxRow = r
				if (maxRow != p) {
					val tmpA = a[p]; a[p] = a[maxRow]; a[maxRow] = tmpA
					val tmpB = b[p]; b[p] = b[maxRow]; b[maxRow] = tmpB
				}
				val piv = a[p][p]
				if (abs(piv) < 1e-14) continue
				for (r in p + 1..3) {
					val f = a[r][p] / piv
					b[r] -= f * b[p]
					for (c in p..3) a[r][c] -= f * a[p][c]
				}
			}
			val sol = DoubleArray(4)
			for (r in 3 downTo 0) {
				var sum = b[r]
				for (c in r + 1..3) sum -= a[r][c] * sol[c]
				sol[r] = if (abs(a[r][r]) > 1e-14) sum / a[r][r] else 0.0
			}
			return FloatArray(4) { sol[it].toFloat() }
		}

		private fun evalCubic(c: FloatArray, xi: Float): Pair<Float, Float> {
			val xc = xi.coerceIn(-0.5f, 0.5f)
			val xc2 = xc * xc
			val valC = c[0] + c[1] * xc + c[2] * xc2 + c[3] * xc2 * xc
			val dC = c[1] + 2f * c[2] * xc + 3f * c[3] * xc2
			val extra = xi - xc
			return (valC + dC * extra) to dC
		}
	}
}

/**
 * The fitted mouth of a project: [rig] when the drawn mouth could be read ([result]), and which layers it moves;
 * [chin] is how far down the face reaches below the mouth, in the mouth's frame.
 */
class FittedMouth(val result: MouthFitting.Result, val rig: MouthRig?, val chin: Float = 0.6f, layers: List<io.github.psd2live.core.ClassifiedLayer> = emptyList()) {
	private val variant = result.layers.firstOrNull()?.semantic?.variant

	/** Whether a closed mouth is drawn beside the open one: it shows at rest and hands over as the lips part. */
	val closedLine: Boolean = layers.any { it.semantic.tag == io.github.psd2live.core.SemanticTag.MOUTH_CLOSE && it.opaquePixels > 0 && covers(it) }

	/** Whether [layer] is a part of the fitted mouth: the mouth itself, its closed line, teeth, tongue or inside, its lips and lines. */
	fun covers(layer: io.github.psd2live.core.ClassifiedLayer): Boolean = rig != null && (layer.semantic.role == null || layer.semantic.role in ROLES) &&
		layer.semantic.variant == variant && layer.semantic.tag in TAGS

	private val own = layers.filter { covers(it) && it.opaquePixels > 0 }

	/** Whether the mouth is drawn in layers - its lips or lip lines apart from its inside - rather than whole. */
	val layered: Boolean = own.any { it.semantic.role in LIPS }

	/** Whether lip layers with their skin cover the mouth's inside. */
	val lipped: Boolean = own.any { it.semantic.role == io.github.psd2live.core.PartRole.UPPER_LIP || it.semantic.role == io.github.psd2live.core.PartRole.LOWER_LIP }

	/**
	 * How [layer] follows the lips ([MouthRig.Follow]).
	 */
	fun follow(layer: io.github.psd2live.core.ClassifiedLayer): MouthRig.Follow {
		val tag = layer.semantic.tag
		val whole = if (rig?.options?.exactClose == true) MouthRig.Follow.WHOLE_TIGHT else MouthRig.Follow.WHOLE
		return when (tag) {
			io.github.psd2live.core.SemanticTag.TOOTH_T -> MouthRig.Follow.UPPER_TEETH
			io.github.psd2live.core.SemanticTag.TOOTH_B -> MouthRig.Follow.LOWER_TEETH
			io.github.psd2live.core.SemanticTag.TONGUE -> MouthRig.Follow.TONGUE
			io.github.psd2live.core.SemanticTag.MOUTH_CLOSE -> whole
			else -> when (layer.semantic.role) {
				io.github.psd2live.core.PartRole.UPPER_LINE -> MouthRig.Follow.UPPER_LINE
				io.github.psd2live.core.PartRole.LOWER_LINE -> MouthRig.Follow.LOWER_LINE
				io.github.psd2live.core.PartRole.UPPER_LIP -> MouthRig.Follow.UPPER_LIP
				io.github.psd2live.core.PartRole.LOWER_LIP -> MouthRig.Follow.LOWER_LIP
				io.github.psd2live.core.PartRole.HIGHLIGHT, io.github.psd2live.core.PartRole.SHADOW -> lying(layer)
				// Clipped onto the opening, the inside's base is drawn larger than any opening and stays deep;
				// the unclipped opening itself maps onto [u1, l1] so it clips teeth and tongue cleanly on both cel-line and lip-skin rigs.
				else -> if (layer.source.clipped && layered) MouthRig.Follow.DEEP else if (!layered) whole else MouthRig.Follow.OPENING
			}
		}
	}

	/** What a highlight or shadow on the mouth lies on, from where its middle is against the drawn lip edges. */
	private fun lying(layer: io.github.psd2live.core.ClassifiedLayer): MouthRig.Follow {
		val fit = rig?.fit ?: return MouthRig.Follow.WHOLE
		val (u, v) = fit.toMouth(layer.centroidX, layer.centroidY)
		val r = v - fit.baseline(u)
		val top = MouthFit.sample(fit.upper, u) - fit.baseline(u); val bottom = MouthFit.sample(fit.lower, u) - fit.baseline(u)
		val margin = (bottom - top) * 0.2f
		return when {
			r < top + margin -> MouthRig.Follow.UPPER_LIP
			r > bottom - margin -> MouthRig.Follow.LOWER_LIP
			layer.semantic.role == io.github.psd2live.core.PartRole.SHADOW -> MouthRig.Follow.DEEP
			lipped -> MouthRig.Follow.INSIDE
			else -> MouthRig.Follow.OPENING
		}
	}

	companion object {
		/** The roles that set a layer of the mouth apart from its inside. */
		val LIPS = setOf(io.github.psd2live.core.PartRole.UPPER_LIP, io.github.psd2live.core.PartRole.LOWER_LIP,
			io.github.psd2live.core.PartRole.UPPER_LINE, io.github.psd2live.core.PartRole.LOWER_LINE)
		/** The roles a layer of the fitted mouth can have. */
		val ROLES = LIPS + io.github.psd2live.core.PartRole.HIGHLIGHT + io.github.psd2live.core.PartRole.SHADOW
		val TAGS = setOf(io.github.psd2live.core.SemanticTag.MOUTH, io.github.psd2live.core.SemanticTag.MOUTH_OPEN,
			io.github.psd2live.core.SemanticTag.MOUTH_CLOSE, io.github.psd2live.core.SemanticTag.TOOTH_T,
			io.github.psd2live.core.SemanticTag.TOOTH_B, io.github.psd2live.core.SemanticTag.TONGUE)
	}
}
