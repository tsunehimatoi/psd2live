package io.github.psd2live.core.fit

import io.github.psd2live.core.Bounds
import io.github.psd2live.core.ClassifiedLayer
import io.github.psd2live.core.PartRole
import io.github.psd2live.core.SemanticTag
import org.umamo.runtime.model.BlendShapeBinding
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.WarpForm
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * The kinds of hanging part a chain is made for, each with how it moves: how far its segments swing, how many it has,
 * how much it bounces up and down, and its pendulum - the bangs short and light, side locks longer, the back hair long
 * and heavy and slow, an ahoge springy, a ponytail or braid a long whip; a skirt sways from the waist and its hem
 * flares out instead of bouncing, a ribbon trails, a pendant swings from its top. [prefix] and [word] name its
 * parameters.
 */
enum class HairKind(val word: String, val swing: Float, val segments: Int, val bounce: Float,
                    val length: Float, val mobility: Float, val delay: Float, val acceleration: Float, val prefix: String = "ParamHair") {
	FRONT("Front", 0.22f, 2, 0.05f, 7f, 0.9f, 0.85f, 1.4f),
	SIDE("Side", 0.3f, 3, 0.06f, 10f, 0.92f, 0.9f, 1.3f),
	BACK("Back", 0.2f, 3, 0.05f, 14f, 0.88f, 1.1f, 1.1f),
	AHOGE("Ahoge", 0.45f, 2, 0.1f, 5f, 0.96f, 0.6f, 1.8f),
	TAIL("Tail", 0.35f, 4, 0.08f, 16f, 0.9f, 1.0f, 1.2f),
	SKIRT("Skirt", 0.12f, 2, 0.12f, 12f, 0.85f, 1.2f, 0.9f, "ParamCloth"),
	RIBBON("Ribbon", 0.35f, 3, 0.06f, 8f, 0.94f, 0.8f, 1.5f, "ParamCloth"),
	PENDANT("Pendant", 0.3f, 1, 0.05f, 6f, 0.96f, 0.6f, 1.8f, "ParamCloth"),
	EAR("Ear", 0.3f, 2, 0.06f, 5f, 0.95f, 0.6f, 1.7f, "ParamPart"),
	ANIMAL_TAIL("AnimalTail", 0.4f, 3, 0.05f, 14f, 0.9f, 1.0f, 1.2f, "ParamPart"),
	WING("Wing", 0.15f, 2, 0.08f, 12f, 0.88f, 1.1f, 1.0f, "ParamPart");

	val hair: Boolean get() = prefix == "ParamHair"

	companion object {
		/** The kind of creature part [layer] is, or null: an animal ear, a tail, a wing. */
		fun partOf(layer: ClassifiedLayer): HairKind? = when {
			layer.semantic.role == PartRole.ANIMAL -> EAR
			layer.semantic.tag == SemanticTag.TAIL -> ANIMAL_TAIL
			layer.semantic.tag == SemanticTag.WINGS -> WING
			else -> null
		}

		/** The kind of clothing chain [layer] hangs in, or null: a skirt, a ribbon or a pendant. */
		fun clothOf(layer: ClassifiedLayer): HairKind? = when (layer.semantic.role) {
			PartRole.SKIRT -> SKIRT
			PartRole.RIBBON -> if (layer.semantic.tag == SemanticTag.BACK_HAIR) null else RIBBON
			PartRole.PENDANT -> PENDANT
			else -> null
		}

		fun of(layer: ClassifiedLayer): HairKind? = when {
			layer.semantic.tag != SemanticTag.FRONT_HAIR && layer.semantic.tag != SemanticTag.BACK_HAIR -> null
			layer.semantic.role == PartRole.AHOGE -> AHOGE
			layer.semantic.role == PartRole.TAIL || layer.semantic.role == PartRole.BRAID -> TAIL
			layer.semantic.role == PartRole.SIDE -> SIDE
			layer.semantic.tag == SemanticTag.FRONT_HAIR -> FRONT
			else -> BACK
		}
	}
}

/**
 * One hair layer's chain (`hair.chains`): a warp over the layer ([frame], in the rig's space) whose free part - below
 * [freeFrom], or above it for an ahoge, which stands up from its root - bends in segments like a pendulum chain. A
 * layer wide enough to hold several locks gets [regions] chains side by side (left, middle, right), each swinging on
 * its own pendulum so the locks part and meet instead of moving as one board. Every column bends about its own root,
 * as separate strands hang.
 */
data class HairChain(
	val layerId: String, val kind: HairKind, val index: Int, val frame: Bounds, val freeFrom: Float, val regions: Int,
	val strength: Float = 1f, val softness: Float = 0.5f,
) {
	val warpId: String get() = when (kind.prefix) { "ParamHair" -> "DeformHairChain"; "ParamCloth" -> "DeformClothChain"; else -> "DeformPartChain" } + index
	private val stem: String get() = "${kind.prefix}${kind.word}$index"
	private fun region(j: Int) = if (regions == 1) "" else REGION_NAMES[j]

	/** The parameter of segment [k] of region [j], root first. */
	fun segment(j: Int, k: Int) = ParameterId("$stem${region(j)}${k + 1}")
	val bounce: ParameterId get() = ParameterId("${stem}Y")
	val parameters: List<ParameterId> get() = (0 until regions).flatMap { j -> (0 until kind.segments).map { segment(j, it) } } + bounce

	private val upward: Boolean get() = kind == HairKind.AHOGE || kind == HairKind.EAR

	/** How far along the free part a point at [y] lies, 0 at its root and 1 at the layer's far edge. */
	private fun along(y: Float): Float {
		val end = if (upward) frame.top else frame.bottom
		val span = end - freeFrom
		return if (kotlin.math.abs(span) < 1e-3f) 0f else ((y - freeFrom) / span).coerceIn(0f, 1f)
	}

	/** How much of the columns at [u] (0 … 1 across the frame) region [j] moves. */
	private fun weight(j: Int, u: Float): Float {
		if (regions == 1) return 1f
		fun smooth(a: Float, b: Float, x: Float) = ((x - a) / (b - a)).coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }
		val left = 1f - smooth(0.2f, 0.45f, u); val right = smooth(0.55f, 0.8f, u)
		return when (j) { 0 -> left; 2 -> right; else -> 1f - left - right }
	}

	/**
	 * The lattice ([rest], x y pairs over (columns + 1) × (rows + 1) points, rows from the top) bent by [angles]
	 * (radians at each segment's start, adding down the chain) and stretched by [stretch] (of the free length).
	 */
	private fun bent(rest: FloatArray, rows: Int, columns: Int, angles: (Float, Float) -> Float, stretch: Float): FloatArray {
		val out = rest.copyOf()
		val length = kotlin.math.abs((if (upward) frame.top else frame.bottom) - freeFrom)
		for (c in 0..columns) {
			val u = c.toFloat() / columns
			// Down the column from its root: each step keeps its rest length, turned by the angle there.
			val order = if (upward) (rows downTo 0) else (0..rows)
			var px = 0f; var py = 0f; var prevX = 0f; var prevY = 0f; var started = false
			for (r in order) {
				val i = (r * (columns + 1) + c) * 2
				val x = rest[i]; val y = rest[i + 1]
				val t = along(y)
				if (!started || t <= 0f) {
					px = x; py = y; prevX = x; prevY = y; started = true
					continue
				}
				val mid = (along(prevY) + t) / 2f
				val theta = angles(u, mid)
				val dx = x - prevX; val dy = y - prevY
				px += dx * cos(theta) - dy * sin(theta)
				py += dx * sin(theta) + dy * cos(theta)
				prevX = x; prevY = y
				out[i] = px
				out[i + 1] = py + (if (upward) -1f else 1f) * stretch * length * t * t
			}
		}
		return out
	}

	/** A skirt's hem spread out ([amount] > 0, lifting a little as it does) or drawn in, growing toward the hem. */
	private fun flared(rest: FloatArray, rows: Int, columns: Int, amount: Float): FloatArray {
		val out = rest.copyOf()
		val cx = frame.centerX
		for (r in 0..rows) for (c in 0..columns) {
			val i = (r * (columns + 1) + c) * 2
			val t = along(rest[i + 1])
			out[i] = rest[i] + (rest[i] - cx) * amount * t * t
			out[i + 1] = rest[i + 1] - kotlin.math.abs(rest[i] - cx) * amount * 0.3f * t * t
		}
		return out
	}

	/** The chain's additive shapes on its warp: each segment of each region at ±1, the bounce at ±1. [toParent] maps rig points to the warp's parent space. */
	fun shapes(rest: FloatArray, rows: Int, columns: Int, toParent: (FloatArray) -> FloatArray): List<BlendShapeBinding<WarpForm>> = buildList {
		val n = kind.segments
		fun ramp(t: Float, k: Int) = (t * n - k).coerceIn(0f, 1f)
		// A soft strand bends mostly near its tip, a stiff one evenly.
		fun profile(t: Float) = t + (t * t - t) * softness
		val sign = if (upward) -1f else 1f
		for (j in 0 until regions) for (k in 0 until n) {
			val forms = listOf(-1f, 0f, 1f).map { value ->
				if (value == 0f) null else WarpForm(toParent(bent(rest, rows, columns, { u, t ->
					sign * value * kind.swing * strength * weight(j, u) * ramp(profile(t), k)
				}, 0f)))
			}
			add(BlendShapeBinding(segment(j, k), floatArrayOf(-1f, 0f, 1f), 1, forms))
		}
		add(BlendShapeBinding(bounce, floatArrayOf(-1f, 0f, 1f), 1, listOf(-1f, 0f, 1f).map { value ->
			if (value == 0f) null else WarpForm(toParent(if (kind == HairKind.SKIRT) flared(rest, rows, columns, value * kind.bounce * strength)
				else bent(rest, rows, columns, { _, _ -> 0f }, value * kind.bounce * strength)))
		}))
	}

	companion object {
		const val ROWS = 8
		const val COLUMNS = 6
		private val REGION_NAMES = listOf("L", "C", "R")

		private val KINDS = HairKind.entries.joinToString("|") { it.word }
		private val PARAMETER = Regex("(ParamHair|ParamCloth|ParamPart)($KINDS)(\\d+)([LCR]?)(\\d)")
		private val BOUNCE = Regex("(ParamHair|ParamCloth|ParamPart)($KINDS)(\\d+)Y")
		private fun kindOf(prefix: String, word: String) = HairKind.entries.first { it.prefix == prefix && it.word == word }
		private fun family(kind: HairKind) = when (kind.prefix) { "ParamHair" -> "Hair"; "ParamCloth" -> "Cloth"; else -> "Part" }

		/**
		 * The chains of [layers]: one per hair layer with pixels, in layer order. A layer wider than the face holds
		 * several locks and gets three regions. [freeY] gives where a kind of hair hangs free from the head (rig y).
		 */
		fun plan(layers: List<ClassifiedLayer>, faceWidth: Float, freeY: (HairKind, Bounds) -> Float, strength: Float, softness: Float,
		         kindOf: (ClassifiedLayer) -> HairKind? = HairKind::of, first: Int = 0): List<HairChain> {
			var index = first
			return layers.mapNotNull { layer ->
				val kind = kindOf(layer) ?: return@mapNotNull null
				if (layer.opaquePixels <= 0) return@mapNotNull null
				val b = layer.bounds
				val padX = max(b.width * 0.06f, 4f); val padY = max(b.height * 0.04f, 4f)
				val frame = Bounds(b.left - padX, b.top - padY, b.right + padX, b.bottom + padY)
				val regions = if (kind.hair && kind != HairKind.AHOGE && kind != HairKind.TAIL && b.width > faceWidth * 0.8f || kind == HairKind.SKIRT) 3 else 1
				HairChain(layer.source.id.raw, kind, ++index, frame, freeY(kind, frame), regions, strength, softness)
			}
		}

		/**
		 * The pendulums of the chains whose parameters [available] holds: one per region, a vertex per segment, the
		 * regions' and chains' lengths set apart a little so neighbouring locks fall out of step; and one for each
		 * chain's bounce, driven by the head's and body's nod and the breath.
		 */
		fun physics(available: Set<String>, softness: Float = 0.5f): List<io.github.psd2live.core.RigPhysicsEdit> {
			// The hair's material: stiff hair swings on short quick pendulums, soft and floaty hair on long slow ones.
			val soft = (softness - 0.5f).coerceIn(-0.5f, 0.5f)
			val swings = available.mapNotNull { PARAMETER.matchEntire(it) }.groupBy { listOf(it.groupValues[1], it.groupValues[2], it.groupValues[3], it.groupValues[4]) }
			val head = io.github.psd2live.core.PhysicsGenerator.headAndBodyInputs(available) +
				listOfNotNull(io.github.psd2live.core.PhysicsInput("ParamBreath", 8f, io.github.psd2live.core.PhysicsSourceType.X).takeIf { "ParamBreath" in available })
			val rules = swings.entries.sortedWith(compareBy({ it.key[0] }, { it.key[2].toInt() }, { it.key[3] })).mapNotNull { (key, matches) ->
				val (prefix, word, number, region) = key
				val index = number.toInt()
				val kind = kindOf(prefix, word)
				val outputs = matches.sortedBy { it.groupValues[5].toInt() }.map { it.value }
				if (head.isEmpty()) return@mapNotNull null
				val spread = 1f + 0.12f * ((index * 7 + "LCR".indexOf(region.ifEmpty { "C" }) * 3) % 5 - 2) / 2f
				val material = if (kind.hair) soft else 0f
				val segment = io.github.psd2live.core.PhysicsSegment(kind.length * spread * (1f + 0.4f * material) / outputs.size,
					(kind.mobility + 0.08f * material).coerceIn(0.5f, 0.99f), kind.delay * spread * (1f + 0.6f * material), kind.acceleration * (1f - 0.3f * material))
				// An animal ear twitches with the blink, as ears do; the bangs quiver a hair's breadth with it.
				val blink = when (kind) { HairKind.EAR -> 15f; HairKind.FRONT -> 5f; else -> 0f }
				val inputs = head + if (blink > 0f) listOf("ParamEyeLOpen", "ParamEyeROpen").filter { it in available }
					.map { io.github.psd2live.core.PhysicsInput(it, blink, io.github.psd2live.core.PhysicsSourceType.X) } else emptyList()
				io.github.psd2live.core.RigPhysicsEdit(
					id = "Physics${family(kind)}$word$index$region",
					name = io.github.psd2live.i18n.tr(when (family(kind)) { "Hair" -> "model.physics.hairChain"; "Cloth" -> "model.physics.clothChain"; else -> "model.physics.partChain" },
						"$word $index $region".trim()),
					inputs = inputs, outputs = outputs.mapIndexed { k, p -> io.github.psd2live.core.PhysicsOutput(p, k + 1, 1.4f) },
					segments = List(outputs.size) { segment },
					normalization = io.github.psd2live.core.PhysicsNormalization(angleMin = -30f, angleMax = 30f))
			}
			val bounces = available.mapNotNull { BOUNCE.matchEntire(it) }.sortedWith(compareBy({ it.groupValues[1] }, { it.groupValues[3].toInt() })).mapNotNull { match ->
				val kind = kindOf(match.groupValues[1], match.groupValues[2])
				val inputs = listOf(
					io.github.psd2live.core.PhysicsInput("ParamAngleY", 60f, io.github.psd2live.core.PhysicsSourceType.X),
					io.github.psd2live.core.PhysicsInput("ParamBodyAngleY", 30f, io.github.psd2live.core.PhysicsSourceType.X),
					io.github.psd2live.core.PhysicsInput("ParamBreath", 10f, io.github.psd2live.core.PhysicsSourceType.X),
				).filter { it.parameter in available }
				if (inputs.isEmpty()) return@mapNotNull null
				io.github.psd2live.core.RigPhysicsEdit(
					id = "Physics${family(kind)}${match.groupValues[2]}${match.groupValues[3]}Y",
					name = io.github.psd2live.i18n.tr(when (family(kind)) { "Hair" -> "model.physics.hairBounce"; "Cloth" -> "model.physics.clothBounce"; else -> "model.physics.partBounce" },
						"${match.groupValues[2]} ${match.groupValues[3]}"),
					inputs = inputs, outputs = listOf(io.github.psd2live.core.PhysicsOutput(match.value, 1, 1.2f)),
					segments = listOf(io.github.psd2live.core.PhysicsSegment(kind.length * 0.6f, kind.mobility, kind.delay, kind.acceleration)),
					normalization = io.github.psd2live.core.PhysicsNormalization(angleMin = -30f, angleMax = 30f))
			}
			return rules + bounces
		}
	}
}
