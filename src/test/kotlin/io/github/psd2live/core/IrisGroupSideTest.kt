package io.github.psd2live.core

import io.github.psd2live.project.WorkspaceSourceLayer
import org.umamo.format.art.*
import kotlin.test.Test
import kotlin.test.assertEquals

class IrisGroupSideTest {
    private fun layer(name: String = "iris", path: String = "Face/Eye_L/Details", left: Int = 0,
                      islands: Int = 1, alpha: Int = 255): WorkspaceSourceLayer {
        val width = 12
        val height = 6
        val rgba = ByteArray(width * height * 4)
        for (y in 1..4) for (x in 1..3) rgba[(y * width + x) * 4 + 3] = alpha.toByte()
        if (islands == 2) for (y in 1..4) for (x in 8..10) rgba[(y * width + x) * 4 + 3] = alpha.toByte()
        return WorkspaceSourceLayer(LayerId("iris"), name, path, SourceLayerKind.Raster, true, 0,
            LayerBounds(left, 0, width, height), 1f, false, LayerBlend.Normal, ChannelMask.ALL,
            LayerRaster(width, height, rgba), null, null, false)
    }

    private fun side(name: String = "iris", path: String = "Face/Eye_L/Details", left: Int = 0, islands: Int = 1) =
        LayerClassifier.classify(layer(name, path, left, islands), 96).semantic.side

    @Test fun standaloneIrisInheritsClearlyNamedEyeAncestorsWithoutLookingAtPosition() {
        assertEquals(Side.LEFT, side())
        assertEquals(Side.LEFT, side(name = "iris_body", path = "EYE L"))
        assertEquals(Side.RIGHT, side(name = "iris_body back", path = "EYE R"))
        assertEquals(Side.LEFT, side(left = -500))
        assertEquals(Side.LEFT, side(left = 500))
        assertEquals(Side.RIGHT, side(path = "Face/Right eye/Details/Color"))
        assertEquals(Side.RIGHT, side(path = "Face/右目/Details"))
        assertEquals(Side.LEFT, side(path = "Face/눈동자_＞/Details"))
        assertEquals(Side.LEFT, side(path = "Face/eye_L/iris_L/Color"))
        assertEquals(Side.RIGHT, side(path = "Face/eyes/iris_R/Color"))
        assertEquals(Side.NONE, LayerClassifier.classify("iris").side)
    }

    @Test fun ambiguousOrConflictingGroupsDoNotSupplyASide() {
        for (path in listOf("", "Face/Details", "Body_L/Details", "Left/Color", "Face/eyes/Details",
            "Eye_L/Eye_R/Color", "Eye_R/Eye_L/Color", "Eye_L/iris decoration_R",
            "Eye_L/Both eyes", "Eye_L/左右目", "Eye_L/left and right eyes", "Left eye_R"))
            assertEquals(Side.NONE, side(path = path), path)
        assertEquals(Side.NONE, side(name = "iris both", path = "Eye_L"))
        assertEquals(Side.NONE, side(name = "eyewhite", path = "Eye_L"))
        assertEquals(Side.NONE, side(name = "unknown", path = "Eye_L"))
    }

    @Test fun bilateralAndEmptyRastersRemainUnsided() {
        assertEquals(Side.NONE, side(islands = 2))
        assertEquals(Side.NONE, LayerClassifier.classify(layer(alpha = 0), 96).semantic.side)
        val faint = layer(alpha = 95)
        assertEquals(Side.LEFT, LayerClassifier.classify(faint, 90).semantic.side)
        assertEquals(Side.NONE, LayerClassifier.classify(faint, 96).semantic.side)
        assertEquals(Side.LEFT, LayerClassifier.classify(faint, 90).semantic.side)
    }

    @Test fun explicitLayerSidesAndClassificationOverridesAlwaysWin() {
        assertEquals(Side.RIGHT, side(name = "iris_R", path = "Eye_L"))
        assertEquals(Side.LEFT, side(name = "iris_L", path = "Eye_R", islands = 2))
        val source = layer()
        for (chosen in Side.entries) {
            val override = LayerClassificationOverride(LayerType.PRESET, SemanticTag.IRIDES, chosen)
            val classified = CharacterAnalyzer.classify(source, PipelineConfig(layerOverrides = mapOf("iris" to override)))
            assertEquals(chosen, classified.semantic.side)
        }
        val split = layer(name = "Iris RIGHT", path = "Eye_L")
        val override = LayerClassificationOverride(LayerType.PRESET, SemanticTag.IRIDES, Side.RIGHT)
        assertEquals(Side.RIGHT, CharacterAnalyzer.classify(split,
            PipelineConfig(layerOverrides = mapOf("iris" to override))).semantic.side)
    }
}
