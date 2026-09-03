package io.github.psd2live.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.umamo.format.moc3.Moc3
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GeneratorTest {
	@Test
	fun `idle motion is valid Cubism motion JSON`() {
		val root = Json.parseToJsonElement(MotionGenerator.idle()).jsonObject
		assertEquals(3, root.getValue("Version").jsonPrimitive.content.toInt())
		assertNotNull(root["Curves"])
	}

	@Test
	fun `sub-motions blink, nod, shake generate valid motion JSON`() {
		val blinkRoot = Json.parseToJsonElement(MotionGenerator.blink()).jsonObject
		assertEquals(3, blinkRoot.getValue("Version").jsonPrimitive.content.toInt())
		assertEquals("false", blinkRoot.getValue("Meta").jsonObject.getValue("Loop").jsonPrimitive.content)

		val nodRoot = Json.parseToJsonElement(MotionGenerator.nod()).jsonObject
		assertEquals(3, nodRoot.getValue("Version").jsonPrimitive.content.toInt())

		val shakeRoot = Json.parseToJsonElement(MotionGenerator.shake()).jsonObject
		assertEquals(3, shakeRoot.getValue("Version").jsonPrimitive.content.toInt())
	}

	@Test
	fun `hair physics follows physics3 schema`() {
		val physics = assertNotNull(Moc3.readPhysics3(checkNotNull(PhysicsGenerator.generate(true, true))))
		assertEquals(2, physics.meta.physicsSettingCount)
		assertEquals(8, physics.meta.totalInputCount)
		assertEquals(4, physics.meta.vertexCount)
		val front = physics.physicsSettings.single { it.id == "PhysicsHairFront" }
		val back = physics.physicsSettings.single { it.id == "PhysicsHairBack" }
		assertEquals(listOf("X", "Angle", "X", "Angle"), front.input.map { it.type })
		assertEquals(1.522f, front.output.single().scale.content.toFloat())
		assertEquals(2.061f, back.output.single().scale.content.toFloat())
		assertEquals(7.9f, front.vertices.last().position.y.content.toFloat())
		assertEquals(0.77f, front.vertices.last().mobility.content.toFloat())
		assertEquals(1.45f, front.vertices.last().delay.content.toFloat())
		assertEquals(0.8f, front.vertices.last().acceleration.content.toFloat())
		assertEquals(7.9f, front.vertices.last().radius.content.toFloat())
		assertEquals(15f, back.vertices.last().position.y.content.toFloat())
		assertEquals(null, PhysicsGenerator.generate(false, false))
	}

	@Test
	fun `eye jelly physics uses blink inputs and a quick two-stage pendulum`() {
		val physics = assertNotNull(Moc3.readPhysics3(checkNotNull(PhysicsGenerator.generate(false, false, true))))
		assertEquals(1, physics.meta.physicsSettingCount)
		assertEquals(2, physics.meta.totalInputCount)
		assertEquals(3, physics.meta.vertexCount)
		val eye = physics.physicsSettings.single { it.id == "PhysicsEyeJelly" }
		assertEquals(listOf("ParamEyeLOpen", "ParamEyeROpen"), eye.input.map { it.source.id })
		assertEquals(listOf("X", "X"), eye.input.map { it.type })
		assertEquals("ParamEyeBallForm", eye.output.single().destination.id)
		assertEquals(2, eye.output.single().vertexIndex)
		assertEquals(0.32f, eye.output.single().scale.content.toFloat())
		assertTrue(eye.vertices[1].delay.content.toFloat() < eye.vertices[2].delay.content.toFloat())
	}

	@Test
	fun `warp points scale proportionally with strength up to 4x`() {
		val character = Bounds(0f, 0f, 1000f, 2000f)
		val p1 = RigBuilder.bodyWarpPoint(character, 0.5f, 0.5f, 10f, 0f, 1f)
		val p2 = RigBuilder.bodyWarpPoint(character, 0.5f, 0.5f, 10f, 0f, 2f)
		val p4 = RigBuilder.bodyWarpPoint(character, 0.5f, 0.5f, 10f, 0f, 4f)

		val shift1 = p1.first - (character.left + 0.5f * character.width)
		val shift2 = p2.first - (character.left + 0.5f * character.width)
		val shift4 = p4.first - (character.left + 0.5f * character.width)

		assertTrue(shift1 > 0f)
		assertEquals(shift1 * 2f, shift2, 1e-4f)
		assertEquals(shift1 * 4f, shift4, 1e-4f)
	}
}
