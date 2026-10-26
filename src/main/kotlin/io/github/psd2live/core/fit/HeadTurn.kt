package io.github.psd2live.core.fit

import io.github.psd2live.core.Bounds
import org.umamo.runtime.model.BlendShapeBinding
import org.umamo.runtime.model.MeshForm
import org.umamo.runtime.model.ParameterId
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin

/**
 * Which shell of the head a layer lies on for [HeadTurn]: how far out from the head's axis ([scale] of the face's
 * radius), whether it is on the front or the back of the head, and whether it turns as one rigid piece. An ear sits a
 * little behind the axis ([behind], radians), so the far one tucks away and the near one comes forward. Hair hanging
 * from the head ([hang]) follows less below it, and the back hair turns and nods only by half ([sway], [nod]): what
 * it would bare going the other way is the head's hidden side, which the art does not draw.
 */
data class HeadShell(val scale: Float, val back: Boolean = false, val rigid: Boolean = false, val behind: Float = 0f, val hang: Boolean = false,
                     val nod: Float = 1f, val sway: Float = 1f) {
	companion object {
		val SKIN = HeadShell(1f)
		val NOSE = HeadShell(1.12f)
		val FRONT_HAIR = HeadShell(1.1f)
		val SIDE_HAIR = HeadShell(1.04f)
		val BACK_HAIR = HeadShell(1.05f, back = true, hang = true, nod = 0.5f, sway = 0.35f)
		/** Shading on the skin, lagging a little behind it so the light seems to stay. */
		val SHADE = HeadShell(0.96f)
		val EAR = HeadShell(1f, behind = 0.3f)
		val RIGID = HeadShell(1.1f, rigid = true)
	}
}

/**
 * The head turning in depth (`face.depth-turn`): the head is a cylinder about its upright axis for Angle X and one
 * about its level axis for Angle Y, and every point of a layer lies on a shell of it ([HeadShell]) - the skin and the
 * features on the face's own surface, the nose a little proud, the front hair further out, the back hair behind, the
 * ears a little behind the axis. Turning moves each point round its shell: the middle of the face slides toward the
 * turn while its edges hold the silhouette, the far side gathers and the near side spreads, the front hair runs ahead
 * of the face and the back hair goes the other way. Art drawn already turned needs nothing of its own: its features
 * sit off the axis and gather as they reach the silhouette.
 *
 * The head's axis is at ([centerX], [centerY]) with half sizes [radiusX] and [radiusY], in the rig's space; [turn]
 * scales how far the head turns per degree and [depth] the shells' spread.
 */
class HeadTurn(
	private val centerX: Float, private val centerY: Float,
	private val radiusX: Float, private val radiusY: Float,
	private val turn: Float = 0.45f, private val depth: Float = 1f,
	/** Where the head ends below: hanging hair past it follows less, as it hangs from the head. */
	private val chinY: Float = centerY + radiusY,
) {
	private fun shellScale(shell: HeadShell) = 1f + (shell.scale - 1f) * depth

	/** How far a point at ([x], [y]) on [shell] moves with the head turned by [yaw] and [pitch] degrees. */
	fun delta(x: Float, y: Float, shell: HeadShell, yaw: Float, pitch: Float): Pair<Float, Float> {
		val s = shellScale(shell)
		val rx = radiusX * s; val ry = radiusY * s
		val u = ((x - centerX) / rx).coerceIn(-1f, 1f)
		val v = ((y - centerY) / ry).coerceIn(-1f, 1f)
		fun around(t: Float, angle: Float, extra: Float): Float {
			val front = asin(t)
			val a = if (shell.back) (PI.toFloat() - front) else front
			val e = if (t >= 0f) a + extra else a - extra
			return sin(e + angle) - sin(e)
		}
		val yawRad = yaw * PI.toFloat() / 180f; val pitchRad = pitch * PI.toFloat() / 180f
		// Hair hanging below the head turns less the further it hangs.
		val follow = if (!shell.hang || y <= chinY) 1f else (1f - (y - chinY) / (radiusY * 3f)).coerceIn(0.3f, 1f)
		// The back hair round the crown hugs the skull and barely shows going the other way; lower down it swings out more.
		val crown = if (!shell.back) 1f else ((v + 1f) / 2f).coerceIn(0.3f, 1f)
		val dx = turn * rx * around(u, yawRad, shell.behind) * follow * shell.sway * crown
		// Looking up turns the front of the head upward: Angle Y up moves it up the canvas.
		val dy = -turn * 0.8f * ry * around(-v, pitchRad, 0f) * follow * shell.nod
		// A head turning sideways leans a little into the turn, about the chin.
		val roll = ROLL * yawRad * turn / 0.45f
		val ox = x - centerX; val oy = y - chinY
		return (dx + ox * (cos(roll) - 1f) - oy * sin(roll)) to (dy + ox * sin(roll) + oy * (cos(roll) - 1f))
	}

	/**
	 * A mesh's turn shapes over Angle X and Angle Y; [positions] in the rig's space, deltas over the parent [frame]. A
	 * rigid shell moves every point as its middle does.
	 */
	fun shapes(positions: FloatArray, frame: Bounds, shell: HeadShell, angleX: ParameterId, angleY: ParameterId): List<BlendShapeBinding<MeshForm>> {
		var cx = 0f; var cy = 0f
		val n = positions.size / 2
		if (shell.rigid) { for (i in 0 until n) { cx += positions[i * 2]; cy += positions[i * 2 + 1] }; cx /= n.coerceAtLeast(1); cy /= n.coerceAtLeast(1) }
		fun form(yaw: Float, pitch: Float): MeshForm {
			val out = FloatArray(positions.size)
			for (i in 0 until n) {
				val px = if (shell.rigid) cx else positions[i * 2]; val py = if (shell.rigid) cy else positions[i * 2 + 1]
				val (dx, dy) = delta(px, py, shell, yaw, pitch)
				out[i * 2] = dx / frame.width.coerceAtLeast(1e-4f); out[i * 2 + 1] = dy / frame.height.coerceAtLeast(1e-4f)
			}
			return MeshForm(out, opacity = Float.NaN)
		}
		return listOf(
			BlendShapeBinding(angleX, YAW_KEYS, YAW_KEYS.toList().indexOf(0f), YAW_KEYS.map { if (it == 0f) null else form(it, 0f) }),
			BlendShapeBinding(angleY, PITCH_KEYS, PITCH_KEYS.toList().indexOf(0f), PITCH_KEYS.map { if (it == 0f) null else form(0f, it) }),
		)
	}

	companion object {
		val YAW_KEYS = floatArrayOf(-45f, -30f, -15f, 0f, 15f, 30f, 45f)
		val PITCH_KEYS = floatArrayOf(-30f, -15f, 0f, 15f, 30f)
		/** How much of the turn the head leans into it. */
		private const val ROLL = 0.08f
	}
}
