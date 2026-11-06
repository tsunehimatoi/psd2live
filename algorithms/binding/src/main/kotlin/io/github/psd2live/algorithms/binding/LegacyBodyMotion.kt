package io.github.psd2live.algorithms.binding

import org.umamo.render.eval.DeformerWorld
import org.umamo.render.eval.RotationWorld
import org.umamo.render.eval.buildDeformerWorlds
import org.umamo.runtime.model.*
import kotlin.math.abs

/** Legacy body motion over the neutral scaffold lattice layout. */
object LegacyBodyMotion {
    val bodyX = ParameterId("ParamBodyAngleX")
    val bodyY = ParameterId("ParamBodyAngleY")
    val bodyZ = ParameterId("ParamBodyAngleZ")
    val lean = ParameterId("ParamBodyLean")
    val proportion = ParameterId("ParamProportion")
    val breath = ParameterId("ParamBreath")
    val parameters = listOf(bodyX, bodyY, bodyZ, lean, proportion, breath)

    private fun axis(id: ParameterId) = KeyformAxis(id, floatArrayOf(-10f, 0f, 10f))
    private fun <T> grid(axes: List<KeyformAxis>, checkpoint: () -> Unit, form: (FloatArray) -> T): KeyformGrid<T> {
        // The legacy generator enumerates the last axis fastest.
        return KeyformGrid(axes, BindingAxes.coordinates(axes.reversed()).map { reversed ->
            checkpoint()
            val coordinate = reversed.reversedArray()
            KeyformCell(coordinate, form(FloatArray(axes.size) { axes[it].keys[coordinate[it]] }))
        })
    }

    private fun lattice(warp: Deformer.Warp, checkpoint: () -> Unit, point: (Float, Float) -> DoubleArray): WarpLatticeForm {
        val points = FloatArray((warp.rows + 1) * (warp.columns + 1) * 2)
        var i = 0
        for (row in 0..warp.rows) {
            checkpoint()
            for (column in 0..warp.columns) {
                val p = point(column.toFloat() / warp.columns, row.toFloat() / warp.rows)
                points[i++] = p[0].toFloat()
                points[i++] = p[1].toFloat()
            }
        }
        return WarpLatticeForm(points)
    }

    fun deformers(
        neutral: List<Deformer>, frame: Bounds, stance: BodyStance, strength: Float, pivotY: Float,
        armFrames: Map<DeformerId, Bounds> = emptyMap(), checkpoint: () -> Unit = {},
    ): List<Deformer> {
        val bodyAxes = listOf(axis(bodyX), axis(bodyY))
        val leanAxes = listOf(axis(lean), axis(proportion))
        val moved = neutral.map { deformer ->
            checkpoint()
            when (deformer.id) {
                ScaffoldBody.bodyId -> (deformer as Deformer.Warp).copy(geometryGrid = grid(bodyAxes, checkpoint) { keys ->
                    lattice(deformer, checkpoint) { u, v ->
                        stance.bodyPoint((frame.left + u * frame.width).toDouble(), (frame.top + v * frame.height).toDouble(), keys[0], keys[1])
                    }
                })
                ScaffoldBody.leanId -> (deformer as Deformer.Warp).copy(geometryGrid = grid(leanAxes, checkpoint) { keys ->
                    lattice(deformer, checkpoint) { u, v ->
                        val p = stance.leanPoint((frame.left + u * frame.width).toDouble(), (frame.top + v * frame.height).toDouble(), keys[0], keys[1])
                        doubleArrayOf((p[0] - frame.left) / frame.width, (p[1] - frame.top) / frame.height)
                    }
                })
                ScaffoldBody.legsId -> {
                    val warp = deformer as Deformer.Warp
                    val legs = requireNotNull(stance.legsFrame)
                    require(warp.rows == stance.legWarpRows)
                    warp.copy(geometryGrid = grid(bodyAxes + axis(proportion), checkpoint) { keys ->
                        val solved = stance.Solved(stance.bodyPose(keys[0], keys[1]))
                        lattice(warp, checkpoint) { u, v ->
                            stance.legsAt(solved.legPoint((legs.left + u * legs.width).toDouble(), (legs.top + v * legs.height).toDouble()), keys[2])
                        }
                    })
                }
                ScaffoldHead.breathId -> (deformer as Deformer.Warp).copy(geometryGrid = grid(
                    listOf(axis(bodyZ), KeyformAxis(breath, floatArrayOf(0f, .5f, 1f))), checkpoint
                ) { keys ->
                    lattice(deformer, checkpoint) { u, v ->
                        val (x, y) = secondaryPoint(frame, stance.torso, u, v, keys[0], keys[1], strength, stance.tuning)
                        doubleArrayOf(x.toDouble(), y.toDouble())
                    }
                })
                ScaffoldHead.rotationId -> {
                    val rotation = deformer as Deformer.Rotation
                    val rest = requireNotNull(rotation.geometryGrid).cells.single().form
                    rotation.copy(geometryGrid = grid(leanAxes, checkpoint) { keys ->
                        RotationPivotForm(rest.originX, rest.originY, rest.angle, stance.leanScale(pivotY.toDouble(), keys[0], keys[1]).toFloat())
                    })
                }
                else -> deformer
            }
        }
        if (armFrames.isEmpty()) return moved
        val armAxes = listOf(axis(bodyX)) + leanAxes
        val worlds = HashMap<List<Float>, Map<DeformerId, DeformerWorld>>()
        return moved.map { deformer ->
            val arm = armFrames[deformer.id] ?: return@map deformer
            val warp = deformer as Deformer.Warp
            val rest = requireNotNull(warp.geometryGrid).cells.single().form.controlPoints
            val torso = stance.torso
            val shoulder = torso.centerX + (if (arm.centerX >= torso.centerX) 1f else -1f) * torso.halfWidth
            warp.copy(geometryGrid = grid(armAxes, checkpoint) { keys ->
                if (keys.all { it == 0f }) WarpLatticeForm(rest)
                else {
                    val world = worlds.getOrPut(keys.toList()) {
                        val pose = armAxes.indices.associate { armAxes[it].parameterId to keys[it] }
                        buildDeformerWorlds(moved, { pose[it] ?: 0f }, { 0f })
                    }.getValue(requireNotNull(warp.parent))
                    var i = 0
                    lattice(warp, checkpoint) { u, v ->
                        val target = stance.armPoint((arm.left + u * arm.width).toDouble(), (arm.top + v * arm.height).toDouble(),
                            shoulder.toDouble(), keys[1], keys[2], keys[0])
                        val point = inverse(world, target[0].toFloat(), target[1].toFloat(), floatArrayOf(rest[i], rest[i + 1]))
                        i += 2
                        doubleArrayOf(point[0].toDouble(), point[1].toDouble())
                    }
                }
            })
        }
    }

    /** Body Z shifts rows about the waist while Breath lifts and expands the chest in lean-local space. */
	fun secondaryPoint(
		character: Bounds,
		torso: TorsoFrame,
		u: Float,
		v: Float,
		bodyAngleZ: Float,
		breathValue: Float,
		strength: Float,
		tuning: BodyMotionTuning,
	): Pair<Float, Float> {
		val boundedStrength = strength.coerceIn(0f, 2f)
		val x = character.left + u * character.width
		val y = character.top + v * character.height
		val torsoLength = torso.length
		val lean = Math.toRadians(tuning.bodyZDegrees * bodyAngleZ / 10.0 * boundedStrength)
		val band = torsoLength * 0.3f
		val above = (torso.waistY - y).coerceAtLeast(0f)
		val lever = if (above < band) above * above / (2f * band) else above - band / 2f
		val shift = kotlin.math.tan(lean).toFloat() * lever
		val breath = breathValue.coerceIn(0f, 1f) * boundedStrength
		val rise = smoothstep(((torso.waistY - y) / torsoLength).coerceIn(0f, 1f))
		val lift = breath * tuning.breathLift / 100f * torsoLength * rise
		val chestY = torso.shoulderY + torsoLength * 0.3f
		val chest = rise * kotlin.math.exp(-((y - chestY) / (torsoLength * 0.45f)).let { it * it })
		val widen = (x - torso.centerX).coerceIn(-torso.halfWidth, torso.halfWidth) * breath * tuning.breathWiden / 100f * chest
		return (x + shift + widen - character.left) / character.width to (y - lift - character.top) / character.height
	}

	fun withFaceLean(deformers: List<Deformer>, tuning: BodyMotionTuning): List<Deformer> = deformers.map { deformer ->
		if (deformer !is Deformer.Warp) return@map deformer
		val grid = deformer.geometryGrid ?: return@map deformer
		val yi = grid.axes.indexOfFirst { it.parameterId == ParameterId("ParamAngleY") }
		if (yi < 0 || grid.axes[yi].keys.size < 2 || grid.axes.any { it.parameterId == lean }) return@map deformer
		val keys = grid.axes[yi].keys
		val byCoordinate = grid.cells.associateBy { it.coordinate.toList() }
		val leans = floatArrayOf(-10f, 0f, 10f)
		val shifts = floatArrayOf(tuning.faceLeanUp, 0f, -tuning.faceLeanDown)
		fun at(cell: KeyformCell<WarpLatticeForm>, index: Int) =
			byCoordinate[cell.coordinate.copyOf().also { it[yi] = index }.toList()]?.form ?: cell.form
		val cells = grid.cells.flatMap { cell ->
			leans.indices.map { l ->
				val form = if (shifts[l] == 0f) cell.form else {
					val target = (keys[cell.coordinate[yi]] + shifts[l]).coerceIn(keys.first(), keys.last())
					var j = 0
					while (j < keys.size - 2 && target > keys[j + 1]) j++
					val t = ((target - keys[j]) / (keys[j + 1] - keys[j])).coerceIn(0f, 1f)
					val a = at(cell, j).controlPoints
					val b = at(cell, j + 1).controlPoints
					WarpLatticeForm(FloatArray(a.size) { a[it] + (b[it] - a[it]) * t })
				}
				KeyformCell(cell.coordinate + l, form)
			}
		}
		deformer.copy(geometryGrid = KeyformGrid(grid.axes + KeyformAxis(lean, leans), cells))
	}

    private fun smoothstep(t: Float) = t * t * (3f - 2f * t)

	fun inverse(world: DeformerWorld, x: Float, y: Float, guess: FloatArray? = null): FloatArray = when (world) {
		is RotationWorld -> {
			val m = world.xform
			val det = m.c12 * m.c13 - m.c15 * m.c14
			val dx = x - m.ox
			val dy = y - m.oy
			floatArrayOf((m.c13 * dx - m.c15 * dy) / det, (-m.c14 * dx + m.c12 * dy) / det)
		}
		else -> invertWorld(world, x, y, guess ?: floatArrayOf(0.5f, 0.5f))
	}

	/** Newton's method on a warp: the local point whose image is ([x], [y]). */
	fun invertWorld(world: DeformerWorld, x: Float, y: Float, guess: FloatArray): FloatArray {
		var u = guess[0].toDouble()
		var v = guess[1].toDouble()
		val p = FloatArray(2)
		val pu = FloatArray(2)
		val pv = FloatArray(2)
		repeat(40) {
			world.apply(u.toFloat(), v.toFloat(), p, 0)
			val ex = x - p[0].toDouble()
			val ey = y - p[1].toDouble()
			if (ex * ex + ey * ey < 1e-8) return floatArrayOf(u.toFloat(), v.toFloat())
			val h = 1e-3
			world.apply((u + h).toFloat(), v.toFloat(), pu, 0)
			world.apply(u.toFloat(), (v + h).toFloat(), pv, 0)
			val a = (pu[0] - p[0]) / h
			val c = (pu[1] - p[1]) / h
			val b = (pv[0] - p[0]) / h
			val d = (pv[1] - p[1]) / h
			val det = a * d - b * c
			if (abs(det) < 1e-12) return floatArrayOf(u.toFloat(), v.toFloat())
			u += (d * ex - b * ey) / det
			v += (-c * ex + a * ey) / det
		}
		return floatArrayOf(u.toFloat(), v.toFloat())
	}
}
