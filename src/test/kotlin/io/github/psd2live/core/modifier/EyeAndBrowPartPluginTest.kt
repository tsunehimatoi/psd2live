package io.github.psd2live.core.modifier

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EyeAndBrowPartPluginTest {
    @Test
    fun `eye and brow part plugins ship bundled manifests with explicit creates and modifies`() {
        val eye = assertNotNull(BundledAlgorithmArtifacts.find("eye", 1))
        val eyeAlg = eye.manifest.algorithms.single()
        assertEquals("base:eye", eyeAlg.exclusiveFamily)
        assertTrue(setOf("eye/fitted", "eye/legacy").all { it in eyeAlg.provides })
        assertTrue("*" !in eyeAlg.creates && "*" !in eyeAlg.modifies)
        assertTrue(eyeAlg.creates.containsAll(listOf("parameters", "warpDeformers", "physicsGroups")))
        assertTrue(eyeAlg.modifies.containsAll(listOf("mesh.offsets", "mesh.opacity")))

        val brow = assertNotNull(BundledAlgorithmArtifacts.find("brow", 1))
        val browAlg = brow.manifest.algorithms.single()
        assertEquals("base:brow", browAlg.exclusiveFamily)
        assertTrue(setOf("brow/fitted", "brow/legacy").all { it in browAlg.provides })
        assertTrue("*" !in browAlg.creates && "*" !in browAlg.modifies)
        assertTrue(browAlg.creates.containsAll(listOf("parameters", "warpDeformers", "physicsGroups")))
        assertTrue(browAlg.modifies.containsAll(listOf("mesh.offsets", "deformer.keyforms")))
    }

    @Test
    fun `legacy eye and brow stacks fold non-destructively into unified part instances`() {
        val targets = listOf(ModifierTarget("mesh", "mesh.eye.l"))
        val browTargets = listOf(ModifierTarget("mesh", "mesh.brow.l"))
        val instances = listOf(
            RigModifierInstance("scaffold-1", "rig.scaffold", 2, targets + browTargets),
            RigModifierInstance(
                "eye-1",
                "eye/fitted",
                3,
                targets,
                config = buildJsonObject { put("scaffold", "scaffold-1") },
            ),
            RigModifierInstance(
                "eye-wide-1",
                "eye.wide",
                2,
                targets,
                config = buildJsonObject {
                    put("scaffold", "scaffold-1")
                    put("base", "eye-1")
                },
            ),
            RigModifierInstance(
                "eye-smile-1",
                "eye.smile",
                2,
                targets,
                config = buildJsonObject {
                    put("scaffold", "scaffold-1")
                    put("base", "eye-1")
                },
            ),
            RigModifierInstance(
                "brow-1",
                "brow/fitted",
                1,
                browTargets,
                config = buildJsonObject { put("scaffold", "scaffold-1") },
            ),
            RigModifierInstance(
                "brow-shape-1",
                "brow.shape",
                2,
                browTargets,
                config = buildJsonObject {
                    put("scaffold", "scaffold-1")
                    put("base", "brow-1")
                },
            ),
        )

        val foldedEyes = RigPartFolding.foldEye(instances)
        assertEquals(1, foldedEyes.size)
        val eye = foldedEyes.single()
        assertEquals("eye", eye.folded.type)
        assertEquals("fitted", eye.folded.config.getValue("mode").jsonPrimitive.content)
        assertEquals("true", eye.folded.config.getValue("wide").jsonPrimitive.content)
        assertEquals("true", eye.folded.config.getValue("smile").jsonPrimitive.content)
        assertFalse("emotion" in eye.folded.config)
        assertEquals(setOf("eye-1", "eye-wide-1", "eye-smile-1"), eye.sourceIds)

        val foldedBrows = RigPartFolding.foldBrow(instances)
        assertEquals(1, foldedBrows.size)
        val brow = foldedBrows.single()
        assertEquals("brow", brow.folded.type)
        assertEquals("fitted", brow.folded.config.getValue("mode").jsonPrimitive.content)
        assertEquals("true", brow.folded.config.getValue("shape").jsonPrimitive.content)
        assertEquals(setOf("brow-1", "brow-shape-1"), brow.sourceIds)
    }
}
