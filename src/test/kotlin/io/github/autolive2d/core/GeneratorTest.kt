package io.github.autolive2d.core

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
		assertEquals(3f, front.vertices.last().position.y.content.toFloat())
		assertEquals(15f, back.vertices.last().position.y.content.toFloat())
		assertTrue(front.output.single().scale.content.toFloat() > 1f)
		assertEquals(null, PhysicsGenerator.generate(false, false))
	}
}
