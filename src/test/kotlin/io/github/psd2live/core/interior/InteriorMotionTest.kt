package io.github.psd2live.core.interior

import io.github.psd2live.core.Physics3Json
import io.github.psd2live.core.deletionExport
import kotlin.math.*
import kotlin.test.*
import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.runtime.model.*

class InteriorMotionTest {
	private val inputs =
		listOf(
			Parameter(ParameterId("SourceX"), "X", -30f, 30f, 0f, ParameterKind.BLEND_SHAPE),
			Parameter(ParameterId("SourceY"), "Y", -30f, 30f, 0f, ParameterKind.BLEND_SHAPE),
		)
	private val positions = floatArrayOf(0f, 0f, 100f, 0f, 100f, 100f, 0f, 100f, 50f, 50f)
	private val mesh =
		DrawableMesh(positions, FloatArray(10), intArrayOf(0, 1, 4, 1, 2, 4, 2, 3, 4, 3, 0, 4))
	private val d =
		Drawable(
			DrawableId("fabric"),
			"Fabric",
			null,
			BlendMode.Normal,
			emptyList(),
			mesh,
			null,
			blendShapes =
				inputs.mapIndexed { axis, p ->
					BlendShapeBinding(
						p.id,
						floatArrayOf(-30f, 0f, 30f),
						1,
						listOf(-1f, 0f, 1f).mapIndexed { k, v ->
							if (k == 1) null
							else
								MeshForm(
									FloatArray(10) {
										if (it / 2 == 4 && it % 2 == axis) v * 8 else 0f
									},
									0f,
									1f,
								)
						},
					)
				},
		)
	private val base =
		PuppetModel(
			inputs,
			emptyList(),
			emptyList(),
			listOf(d),
			listOf(OrgChild.Drawable(d.id)),
			null,
		)

	private fun geometry(model: PuppetModel, values: Map<ParameterId, Float>): FloatArray =
		CpuDeformationEvaluator().evaluate(model, values).worldPositions.getValue(d.id)

	@Test
	fun staticOffIsExactAndPointerUsesUniformIndependentGain() {
		val settings = InteriorMotionSettings(enabled = false)
		val plan = InteriorMotionGenerator.prepare(base, d.id, inputs, settings)
		assertSame(base, plan.model)
		assertTrue(plan.groups.isEmpty())
		for (x in listOf(-1f, -.3f, 0f, .3f, 1f)) assertContentEquals(
			geometry(base, mapOf(inputs[0].id to x * 30)),
			geometry(plan.model, plan.controller().sample(x, 0f, 0f)),
		)
		val pointer = settings.copy(sensitivityX = .1f, sensitivityY = .8f)
		for (x in listOf(-1f, -.99f, -.8f, -.2f, 0f, .2f, .8f, .99f, 1f)) {
			assertEquals(x * .1f, pointer.pointer(x, x).first, .000001f)
			assertEquals(x * .8f, pointer.pointer(x, x).second, .000001f)
		}
		assertEquals(.1f, pointer.pointer(2f, 2f).first)
		assertEquals(-.8f, pointer.pointer(-2f, -2f).second)
		assertEquals(settings, InteriorMotionSettings.fromJson(settings.toJson()))
	}

	@Test
	fun followsDuringMovementAndSettlesToTheSameShapeAfterPhysicsRoundtrip() {
		val plan = InteriorMotionGenerator.prepare(base, d.id, inputs, InteriorMotionSettings())
		assertEquals(
			io.github.psd2live.core.RigEditOverlay.DEFAULT_PHYSICS_FPS,
			plan.physicsFps,
			"new effect must not change the project's shared physics rate",
		)
		val model = deletionExport(plan.model)
		val p = Physics3Json.read(Physics3Json.write(plan.groups, plan.physicsFps)!!)
		val copy =
			InteriorMotionPlan(
				model,
				inputs,
				plan.stageParameters,
				plan.weights,
				p.settings,
				plan.settings,
				p.fps!!.toInt(),
			)
		val a = plan.controller()
		val b = copy.controller()
		a.sample(0f, 0f, 0f)
		b.sample(0f, 0f, 0f)
		a.sample(.8f, 0f, 1f / 60)
		b.sample(.8f, 0f, 1f / 60)
		val first = a.sample(.8f, 0f, 1f / 60)
		val firstCopy = b.sample(.8f, 0f, 1f / 60)
		assertTrue(
			first.getValue(plan.stageParameters[0][0]) > 0f,
			"must start within native interpolation latency, during movement",
		)
		assertTrue(
			first.getValue(plan.stageParameters[1][0]) > 0f,
			"following area must not wait for the input to stop",
		)
		assertTrue(
			first.getValue(plan.stageParameters[0][0]) > first.getValue(plan.stageParameters[1][0])
		)
		for (i in geometry(plan.model, first).indices) assertEquals(
			geometry(plan.model, first)[i],
			geometry(model, firstCopy)[i],
			.0001f,
		)
		var last = first
		for (i in 1..420) {
			val x = if (i < 150) sin(i * .075f) * .9f else .65f
			val y = if (i < 150) cos(i * .047f) * .8f else -.4f
			last = a.sample(x, y, 1f / 60)
			val other = b.sample(x, y, 1f / 60)
			val v = geometry(plan.model, last)
			val w = geometry(model, other)
			for (j in v.indices) assertEquals(v[j], w[j], .0003f)
		}
		val static = geometry(base, mapOf(inputs[0].id to .65f * 30, inputs[1].id to -.4f * 30))
		val dynamic = geometry(plan.model, last)
		for (i in static.indices) assertEquals(
			static[i],
			dynamic[i],
			.002f,
			"steady pose must preserve approved static shape",
		)
		assertEquals(inputs, base.parameters)
		assertSame(mesh, base.drawables.single().mesh)
	}
}
