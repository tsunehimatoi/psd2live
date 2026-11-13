package io.github.psd2live.core.modifier

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PartPluginsAndSimulationTest {
    @Test
    fun `face body and hair part plugins ship bundled manifests with explicit creates and modifies`() {
        val face = assertNotNull(BundledAlgorithmArtifacts.find("face", 1))
        val faceAlg = face.manifest.algorithms.single()
        assertEquals("base:face", faceAlg.exclusiveFamily)
        assertTrue(setOf("face/fitted", "face/legacy").all { it in faceAlg.provides })
        assertTrue("*" !in faceAlg.creates && "*" !in faceAlg.modifies)
        assertTrue(faceAlg.creates.containsAll(listOf("parameters", "warpDeformers", "rotationDeformers")))

        val body = assertNotNull(BundledAlgorithmArtifacts.find("body", 1))
        val bodyAlg = body.manifest.algorithms.single()
        assertEquals("base:body", bodyAlg.exclusiveFamily)
        assertTrue("body/legacy" in bodyAlg.provides)
        assertTrue("*" !in bodyAlg.creates && "*" !in bodyAlg.modifies)
        assertTrue(bodyAlg.creates.containsAll(listOf("parameters", "warpDeformers", "physicsGroups")))

        val hair = assertNotNull(BundledAlgorithmArtifacts.find("hair", 1))
        val hairAlg = hair.manifest.algorithms.single()
        assertEquals("base:hair", hairAlg.exclusiveFamily)
        assertTrue(setOf("hair/chains", "hair/legacy").all { it in hairAlg.provides })
        assertTrue("*" !in hairAlg.creates && "*" !in hairAlg.modifies)
        assertTrue(hairAlg.creates.containsAll(listOf("parameters", "warpDeformers", "physicsGroups")))
    }

    @Test
    fun `legacy face body and hair stacks fold non-destructively into unified part instances`() {
        val targets = listOf(ModifierTarget("mesh", "mesh.head"))
        val instances = listOf(
            RigModifierInstance("scaffold-1", "rig.scaffold", 2, targets),
            RigModifierInstance("face-1", "face/fitted", 1, targets, config = buildJsonObject { put("scaffold", "scaffold-1") }),
            RigModifierInstance("neck-1", "face.neck", 1, targets, config = buildJsonObject {
                put("scaffold", "scaffold-1")
                putJsonObject("dependencies") { put("face/legacy", "face-1") }
            }),
            RigModifierInstance("body-1", "body/legacy", 1, targets, config = buildJsonObject { put("scaffold", "scaffold-1") }),
            RigModifierInstance("bust-1", "body.bust", 2, targets, config = buildJsonObject {
                put("scaffold", "scaffold-1")
                putJsonObject("dependencies") { put("body/legacy", "body-1") }
            }),
            RigModifierInstance("hair-1", "hair/chains", 1, targets, config = buildJsonObject { put("scaffold", "scaffold-1") }),
        )

        val face = RigPartFolding.foldFace(instances).single()
        assertEquals("face", face.folded.type)
        assertEquals("fitted", face.folded.config.getValue("mode").jsonPrimitive.content)
        assertEquals("true", face.folded.config.getValue("neck").jsonPrimitive.content)
        assertEquals(setOf("face-1", "neck-1"), face.sourceIds)

        val body = RigPartFolding.foldBody(instances).single()
        assertEquals("body", body.folded.type)
        assertEquals("legacy", body.folded.config.getValue("mode").jsonPrimitive.content)
        assertEquals("true", body.folded.config.getValue("bust").jsonPrimitive.content)
        assertEquals(setOf("body-1", "bust-1"), body.sourceIds)

        val hair = RigPartFolding.foldHair(instances).single()
        assertEquals("hair", hair.folded.type)
        assertEquals("chains", hair.folded.config.getValue("mode").jsonPrimitive.content)
        assertEquals(setOf("hair-1"), hair.sourceIds)
    }
}
