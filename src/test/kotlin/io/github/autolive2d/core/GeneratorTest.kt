package io.github.autolive2d.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.umamo.format.moc3.Moc3
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class GeneratorTest {
	@Test
	fun `idle motion is valid Cubism motion JSON`() {
		val root = Json.parseToJsonElement(MotionGenerator.idle()).jsonObject
		assertEquals(3, root.getValue("Version").jsonPrimitive.content.toInt())
		assertNotNull(root["Curves"])
	}

	@Test
	fun `hair physics follows physics3 schema`() {
		assertNotNull(Moc3.readPhysics3(checkNotNull(PhysicsGenerator.generate(true, true))))
		assertEquals(null, PhysicsGenerator.generate(false, false))
	}
}
