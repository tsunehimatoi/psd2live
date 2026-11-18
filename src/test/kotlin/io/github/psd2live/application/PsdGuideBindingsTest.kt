package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.project.config
import io.github.psd2live.scenario.Characters
import org.umamo.format.art.SourceLayer
import kotlin.test.*

class PsdGuideBindingsTest {
    private val artwork = Characters.eyed()
    private val raw = CharacterAnalyzer.analyze(artwork.source, artwork.config())
    private val eyeIds = setOf("white_r", "white_l", "iris_r", "iris_l")

    private fun renamed(layer: SourceLayer, name: String): SourceLayer = object : SourceLayer by layer {
        override val name = name
    }

    private fun unnamedEyes() = raw.copy(layers = raw.layers.map {
        if (it.source.id.raw in eyeIds) it.copy(semantic = it.semantic.copy(side = Side.NONE),
            source = renamed(it.source, it.source.name.substringBeforeLast('_')))
        else it
    })

    @Test fun coLocatedEyePartsFollowAnUnambiguousPair() {
        val result = PsdGuideBindings.inspect(unnamedEyes(), emptyMap())
        assertEquals(Side.RIGHT, result.automatic.getValue("white_r").side)
        assertEquals(Side.LEFT, result.automatic.getValue("white_l").side)
        assertEquals(Side.RIGHT, result.automatic.getValue("iris_r").side)
        assertEquals(Side.LEFT, result.automatic.getValue("iris_l").side)
        assertFalse(result.findings.any { it.layers.any(eyeIds::contains) })
    }

    @Test fun conflictingNamesAndBilateralLayersRemainReviewable() {
        val analysis = unnamedEyes().let { art -> art.copy(layers = art.layers.map { layer ->
            when (layer.source.id.raw) {
                "white_r" -> layer.copy(source = renamed(layer.source, "eyewhite_l"))
                "iris_r" -> layer.copy(bounds = Bounds(184f, 59f, 236f, 72f))
                else -> layer
            }
        }) }
        val result = PsdGuideBindings.inspect(analysis, emptyMap())
        assertTrue(result.automatic.isEmpty())
        assertTrue(result.findings.any { it.kind == PsdGuideBindings.Kind.CONFLICT && "white_r" in it.layers })
        assertTrue(result.findings.any { it.kind == PsdGuideBindings.Kind.SIDE && "white_l" in it.layers })
        val manual = LayerClassificationOverride(SemanticTag.EYEWHITE, Side.LEFT)
        val corrected = PsdGuideBindings.inspect(analysis, mapOf("white_r" to manual))
        assertEquals(manual, corrected.overrides["white_r"])
        assertFalse(corrected.findings.any { it.kind == PsdGuideBindings.Kind.CONFLICT && "white_r" in it.layers })
    }
}
