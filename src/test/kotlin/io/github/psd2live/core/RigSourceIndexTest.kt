package io.github.psd2live.core

import io.github.psd2live.core.modifier.RigModifierInstance
import io.github.psd2live.core.modifier.RigModifierSource
import io.github.psd2live.core.modifier.RigModifierStack
import io.github.psd2live.core.modifier.ModifierTarget
import io.github.psd2live.format.model.Canvas
import io.github.psd2live.format.model.RigIR
import io.github.psd2live.project.rawConfig
import io.github.psd2live.scenario.Characters
import io.github.psd2live.targets.cubism.PuppetIr
import kotlinx.serialization.json.*
import org.umamo.format.art.SourceArt
import kotlin.test.*

class RigSourceIndexTest {
    @Test fun layerMembershipReusesCaptureIndexAndNewCapturesStayIndependent() {
        val document = Characters.head(physics = false)
        val config = document.rawConfig()
        val analysis = CharacterAnalyzer.analyze(document.source, config)
        val atlas = AtlasLayout.pack(analysis.layers, config)
        val base = PuppetIr.toPuppet(RigIR(Canvas(1f, 1f), emptyList()))
        var reads = 0
        val counted = object : AbstractList<ClassifiedLayer>() {
            override val size get() = analysis.layers.size
            override fun get(index: Int): ClassifiedLayer {
                reads++
                return analysis.layers[index]
            }
        }
        val source = RigModifierSource(analysis.copy(layers = counted), atlas, config, emptyMap(), materialBase = base)
        val id = analysis.layers.last().source.id.raw
        assertTrue(source.hasLayer(id))
        val initialReads = reads
        assertTrue(initialReads > 0)
        repeat(5) {
            assertTrue(source.hasLayer(id))
            assertFalse(source.hasLayer("missing-layer"))
        }
        assertEquals(initialReads, reads, "Membership queries should not traverse the captured layers again")
        val changed = RigModifierSource(analysis.copy(layers = analysis.layers.filterNot { it.source.id.raw == id }),
            atlas, config, emptyMap(), materialBase = base)
        assertFalse(changed.hasLayer(id))
        assertTrue(source.hasLayer(id))
    }

    @Test fun repeatedSkinClipContextsReuseSourceClippingAndKeepLastLayerAndPrimaryMesh() {
        val document = Characters.head(physics = false)
        val config = document.rawConfig()
        val analysis = CharacterAnalyzer.analyze(document.source, config)
        val atlas = AtlasLayout.pack(analysis.layers, config)
        val base = PuppetIr.toPuppet(RigIR(Canvas(1f, 1f), emptyList()))
        val face = analysis.layers.first { it.semantic.tag == SemanticTag.FACE }
        val replacement = face.copy(semantic = face.semantic.copy(tag = SemanticTag.FACE_DETAIL))
        val id = face.source.id.raw
        var sourceReads = 0
        val countedSource = object : SourceArt by analysis.source {
            override val layers get() = analysis.source.layers.also { sourceReads++ }
        }
        val targets = listOf(ModifierTarget("mesh", "primary"))
        val scaffold = RigModifierInstance("structure", "rig.scaffold", 2, targets)
        val instance = RigModifierInstance("skin", "face.skin-clip", 1, targets,
            config = buildJsonObject { put("scaffold", scaffold.id) })
        val captured = config.copy(rigEdits = config.rigEdits.copy(modifierStack = RigModifierStack(instances = listOf(scaffold, instance)).toJson()))
        val source = RigModifierSource(analysis.copy(source = countedSource, layers = analysis.layers + replacement),
            atlas, captured, linkedMapOf("copy" to id, "primary" to id), copiedMeshes = setOf("copy"), materialBase = base)
        val first = source.skinClipContext(instance).values
        val initialReads = sourceReads
        assertTrue(initialReads > 0)
        assertEquals("primary", source.primaryByLayer[id])
        assertEquals(listOf("primary"), first.getValue("faces").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content })
        assertEquals(setOf("copy", "primary"), first.getValue("overlays").jsonObject.keys)
        repeat(3) { assertEquals(first, source.skinClipContext(instance).values) }
        assertEquals(initialReads, sourceReads, "Clipping should be computed once per capture")
    }
}
