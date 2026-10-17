package io.github.psd2live.core.interior

import io.github.psd2live.core.*
import kotlin.math.*
import kotlinx.serialization.json.*
import org.umamo.runtime.model.*

/** Optional parameter-time layer. The approved static surface and its bake never change. */
internal data class InteriorMotionSettings(
	val enabled: Boolean = true,
	val sensitivityX: Float = .65f,
	val sensitivityY: Float = .65f,
	val responseSeconds: Float = .10f,
	val transmissionSeconds: Float = .16f,
	val transmissionStrength: Float = .55f,
	val from: InteriorPoint = InteriorPoint(.5f, .05f),
	val to: InteriorPoint = InteriorPoint(.5f, .95f),
) {
	init {
		require(
			sensitivityX.isFinite() &&
				sensitivityX in .1f..1f &&
				sensitivityY.isFinite() &&
				sensitivityY in .1f..1f
		)
		require(responseSeconds.isFinite() && responseSeconds in 0f..0.6f)
		require(transmissionSeconds.isFinite() && transmissionSeconds in 0f..0.5f)
		require(transmissionStrength.isFinite() && transmissionStrength in 0f..1f)
		require(listOf(from.x, from.y, to.x, to.y).all { it in 0f..1f })
		require(hypot((to.x - from.x).toDouble(), (to.y - from.y).toDouble()) >= .05)
	}

	/**
	 * Mouse preview gain only. Explicit poses bypass this; the saved maximum shape is unchanged.
	 */
	fun pointer(x: Float, y: Float): Pair<Float, Float> {
		require(x.isFinite() && y.isFinite())
		fun map(v: Float, s: Float) = v.coerceIn(-1f, 1f) * s
		return map(x, sensitivityX) to map(y, sensitivityY)
	}

	fun toJson() = buildJsonObject {
		put("schema", 1)
		put("enabled", enabled)
		put("sensitivityX", sensitivityX)
		put("sensitivityY", sensitivityY)
		put("responseSeconds", responseSeconds)
		put("transmissionSeconds", transmissionSeconds)
		put("transmissionStrength", transmissionStrength)
		put("from", JsonArray(listOf(JsonPrimitive(from.x), JsonPrimitive(from.y))))
		put("to", JsonArray(listOf(JsonPrimitive(to.x), JsonPrimitive(to.y))))
	}

	companion object {
		fun fromJson(o: JsonObject): InteriorMotionSettings {
			require(o.getValue("schema").jsonPrimitive.int == 1)
			fun point(name: String): InteriorPoint {
				val a = o.getValue(name).jsonArray
				require(a.size == 2)
				return InteriorPoint(a[0].jsonPrimitive.float, a[1].jsonPrimitive.float)
			}
			return InteriorMotionSettings(
				o.getValue("enabled").jsonPrimitive.boolean,
				o.getValue("sensitivityX").jsonPrimitive.float,
				o.getValue("sensitivityY").jsonPrimitive.float,
				o.getValue("responseSeconds").jsonPrimitive.float,
				o.getValue("transmissionSeconds").jsonPrimitive.float,
				o.getValue("transmissionStrength").jsonPrimitive.float,
				point("from"),
				point("to"),
			)
		}
	}
}

/**
 * Each output has one owner. All temporal behaviour is ordinary project physics3, not a browser
 * solver.
 */
internal class InteriorMotionPlan
internal constructor(
	val model: PuppetModel,
	val inputs: List<Parameter>,
	val stageParameters: List<List<ParameterId>>,
	val weights: List<FloatArray>,
	val groups: List<RigPhysicsEdit>,
	val settings: InteriorMotionSettings,
	val physicsFps: Int = RigEditOverlay.DEFAULT_PHYSICS_FPS,
) {
	val rawBytes: Long
		get() =
			model.drawables.sumOf { d ->
				d.blendShapes.sumOf { b ->
					b.forms.filterNotNull().sumOf { it.positionDeltas.size.toLong() * 4 }
				}
			}

	fun controller() = Controller(this)

	class Controller internal constructor(private val plan: InteriorMotionPlan) {
		private val engine =
			PhysicsEngine(
				plan.groups,
				PhysicsEngine.ranges(plan.model.parameters),
				plan.physicsFps.toFloat(),
			)
		private var initialized = false
		private var last = emptyMap<String, Float>()

		fun carryOver(previous: Controller) {
			val previousIds = previous.plan.groups.map { it.id }.toSet()
			if (plan.groups.any { it.id !in previousIds }) return
			engine.carryOver(previous.engine)
			initialized = previous.initialized
			val outputs = plan.groups.flatMap { it.outputParameters }.toSet()
			last = previous.last.filterKeys { it in outputs }
		}

		fun sample(x: Float, y: Float, dt: Float, reset: Boolean = false): Map<ParameterId, Float> {
			require(
				x.isFinite() &&
					y.isFinite() &&
					x in -1f..1f &&
					y in -1f..1f &&
					dt.isFinite() &&
					dt >= 0
			)
			fun input(p: Parameter, v: Float) = if (v < 0) -v * p.min else v * p.max
			val values =
				mapOf(
					plan.inputs[0].id.raw to input(plan.inputs[0], x),
					plan.inputs[1].id.raw to input(plan.inputs[1], y),
				)
			if (!initialized || reset || dt > .25f) {
				engine.reset()
				last =
					engine.settle(
						values,
						max(
							1f,
							(plan.settings.responseSeconds + plan.settings.transmissionSeconds) *
								12,
						),
					)
				initialized = true
			}
			val output = engine.step(values, if (dt > .25f) 0f else dt)
			if (output.isNotEmpty()) last = output
			return (values + last).mapKeys { ParameterId(it.key) }
		}
	}
}

internal object InteriorMotionGenerator {
	fun prepare(
		base: PuppetModel,
		target: DrawableId,
		inputs: List<Parameter>,
		settings: InteriorMotionSettings,
		owner: String = "InteriorMotion",
		physicsFps: Int = RigEditOverlay.DEFAULT_PHYSICS_FPS,
	): InteriorMotionPlan {
		require(physicsFps > 0) {
			"Use the project's positive fixed physics FPS; never retime other groups"
		}
		require(inputs.size == 2 && inputs.map { it.id }.distinct().size == 2)
		require(
			inputs.all {
				it.default == 0f &&
					it.min < 0 &&
					it.max > 0 &&
					abs(it.min + it.max) < 1e-5f &&
					base.parameters.single { p -> p.id == it.id } == it
			}
		) {
			"Motion prototype needs explicit zero-neutral symmetric source ranges"
		}
		val d = base.drawables.single { it.id == target }
		val mesh = requireNotNull(d.mesh)
		val grid = d.geometryGrid
		require(
			(grid == null ||
				(grid.axes.isEmpty() &&
					grid.cells.size == 1 &&
					grid.cells.single().form.positionDeltas.all { it == 0f })) &&
				d.blendShapes.isNotEmpty()
		) {
			"Motion prototype requires its own static surface without animated geometry axes"
		}
		require(
			d.blendShapes.all { b ->
				b.parameterId in inputs.map { it.id } &&
					b.limits.all { it.parameterId in inputs.map { p -> p.id } }
			}
		)
		if (
			!settings.enabled ||
				(settings.responseSeconds == 0f &&
					(settings.transmissionSeconds == 0f || settings.transmissionStrength == 0f))
		)
			return InteriorMotionPlan(
				base,
				inputs,
				listOf(inputs.map { it.id }),
				listOf(FloatArray(mesh.vertexCount) { 1f }),
				emptyList(),
				settings,
				physicsFps,
			)
		val split = settings.transmissionSeconds > 0 && settings.transmissionStrength > 0
		val stages = if (split) 2 else 1
		val xy = mesh.positions
		val x0 = (xy.indices step 2).minOf { xy[it] }
		val x1 = (xy.indices step 2).maxOf { xy[it] }
		val y0 = (1 until xy.size step 2).minOf { xy[it] }
		val y1 = (1 until xy.size step 2).maxOf { xy[it] }
		val ax = x0 + settings.from.x * (x1 - x0)
		val ay = y0 + settings.from.y * (y1 - y0)
		val dx = (settings.to.x - settings.from.x) * (x1 - x0)
		val dy = (settings.to.y - settings.from.y) * (y1 - y0)
		val length = dx * dx + dy * dy
		require(length > 0)
		val late =
			FloatArray(mesh.vertexCount) { i ->
				val t =
					(((xy[i * 2] - ax) * dx + (xy[i * 2 + 1] - ay) * dy) / length).coerceIn(0f, 1f)
				t * t * (3 - 2 * t) * settings.transmissionStrength
			}
		val weights =
			if (split) listOf(FloatArray(mesh.vertexCount) { 1f - late[it] }, late)
			else listOf(FloatArray(mesh.vertexCount) { 1f })
		val additional = mutableListOf<Parameter>()
		val drivers =
			(0 until stages).map { stage ->
				inputs.indices.map { axis ->
					if (stage == 0 && settings.responseSeconds == 0f) inputs[axis].id
					else {
						val id =
							ParameterId(
								"${owner}_${target.raw}_${stage}_${if(axis==0)"X" else "Y"}"
							)
						require(base.parameters.none { it.id == id }) {
							"Interior motion output already owned: ${id.raw}"
						}
						additional +=
							inputs[axis].copy(
								id = id,
								name = "Internal ${stage+1} ${if(axis==0)"X" else "Y"}",
								kind = ParameterKind.BLEND_SHAPE,
							)
						id
					}
				}
			}
		val groups = mutableListOf<RigPhysicsEdit>()
		for (stage in 0 until stages) for (axis in inputs.indices) {
			if (stage == 0 && settings.responseSeconds == 0f) continue
			val seconds = if (stage == 0) settings.responseSeconds else settings.transmissionSeconds
			val mobility = .12f
			val delay = sqrt((1 - mobility) / ((900f / physicsFps) * seconds.coerceAtLeast(.035f)))
			groups +=
				RigPhysicsEdit(
					"${owner}_${target.raw}_${stage}_$axis",
					"Internal follow ${stage+1}",
					listOf(
						PhysicsInput(
							(if (stage == 0) inputs[axis].id else drivers[stage - 1][axis]).raw,
							type = PhysicsSourceType.ANGLE,
							reflect = true,
						)
					),
					listOf(
						PhysicsOutput(
							drivers[stage][axis].raw,
							scale = (180 / PI).toFloat(),
							reflect = true,
						)
					),
					listOf(PhysicsSegment(1f, mobility, delay, 1f)),
					// These groups have no translation input. Keep its unused span small so the
					// native
					// movement threshold does not freeze small angle responses or the following
					// stage.
					PhysicsNormalization(
						positionMin = -.001f,
						positionMax = .001f,
						angleMin = inputs[axis].min,
						angleMax = inputs[axis].max,
					),
				)
		}
		val bindings =
			(0 until stages).flatMap { stage ->
				d.blendShapes.map { b ->
					fun remap(id: ParameterId) =
						drivers[stage][
							inputs.indexOfFirst { it.id == id }.also { require(it >= 0) }]
					b.copy(
						parameterId = remap(b.parameterId),
						keys = b.keys.copyOf(),
						limits = b.limits.map { it.copy(parameterId = remap(it.parameterId)) },
						forms =
							b.forms.map { f ->
								f?.let {
									MeshForm(
										FloatArray(f.positionDeltas.size) { i ->
											f.positionDeltas[i] * weights[stage][i / 2]
										},
										f.drawOrder,
										f.opacity,
										f.multiplyColor,
										f.screenColor,
									)
								}
							},
					)
				}
			}
		val next =
			base.copy(
				parameters = base.parameters + additional,
				drawables =
					base.drawables.map {
						if (it.id == target) it.copy(blendShapes = bindings) else it
					},
			)
		return InteriorMotionPlan(next, inputs, drivers, weights, groups, settings, physicsFps)
	}
}
