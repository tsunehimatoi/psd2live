package io.github.psd2live.core

import kotlinx.serialization.json.JsonPrimitive
import org.umamo.format.moc3.Moc3
import org.umamo.format.moc3.json.EffectiveForces
import org.umamo.format.moc3.json.Physics3Json
import org.umamo.format.moc3.json.PhysicsMeta
import org.umamo.format.moc3.json.PhysicsVector2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class PhysicsSerializationTest {
	@Test
	fun testPhysics3JsonSerialization() {
		val json = """
			{
				"Version": 3,
				"Meta": {
					"PhysicsSettingCount": 0,
					"TotalInputCount": 0,
					"TotalOutputCount": 0,
					"VertexCount": 0,
					"EffectiveForces": {
						"Gravity": { "X": 0, "Y": -1 },
						"Wind": { "X": 0, "Y": 0 }
					},
					"PhysicsDictionary": []
				},
				"PhysicsSettings": []
			}
		""".trimIndent()

		val parsed = Moc3.readPhysics3(json)
		assertEquals(3, parsed.version)
		assertEquals(0, parsed.meta.physicsSettingCount)
		val written = Moc3.writePhysics3(parsed)
		assertNotNull(written)
		val reparsed = Moc3.readPhysics3(written)
		assertEquals(parsed, reparsed)
	}
}

