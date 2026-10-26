package io.github.psd2live.core

import io.github.psd2live.project.WorkspaceSourceLayer
import org.umamo.format.art.ChannelMask
import org.umamo.format.art.LayerBlend
import org.umamo.format.art.LayerBounds
import org.umamo.format.art.LayerId
import org.umamo.format.art.LayerRaster
import org.umamo.format.art.SourceLayerKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What a layer is within its part ([PartRole]): read off its name, its place among its part's layers, and set by the user's tags. */
class PartRolesTest {
	private fun layer(id: String, tag: SemanticTag, side: Side, x0: Int, y0: Int, x1: Int, y1: Int, grey: Int = 120,
	                  blend: LayerBlend = LayerBlend.Normal, name: String = id): ClassifiedLayer {
		val width = 64; val height = 64
		val rgba = ByteArray(width * height * 4)
		for (y in y0 until y1) for (x in x0 until x1) {
			val o = (y * width + x) * 4
			rgba[o] = grey.toByte(); rgba[o + 1] = grey.toByte(); rgba[o + 2] = grey.toByte(); rgba[o + 3] = -1
		}
		val source = WorkspaceSourceLayer(LayerId(id), name, "", SourceLayerKind.Raster, true, 0, LayerBounds(0, 0, width, height), 1f,
			false, blend, ChannelMask.ALL, LayerRaster(width, height, rgba), null, null, false)
		val bounds = Bounds(x0.toFloat(), y0.toFloat(), x1.toFloat(), y1.toFloat())
		return ClassifiedLayer(source, LayerSemantic(tag, side, normalizedName = name, confidence = 1f), bounds, bounds.centerX, bounds.centerY,
			(x1 - x0) * (y1 - y0))
	}

	@Test fun namesAndFoldersNameTheRoleTheirPartCanHave() {
		assertEquals(PartRole.HIGHLIGHT, PartRoles.fromName("右目ハイライト", "", SemanticTag.IRIDES))
		assertEquals(PartRole.LOWER, PartRoles.fromName("下睫毛 左", "", SemanticTag.EYELASH))
		assertEquals(PartRole.SHADOW, PartRoles.fromName("眼影", "", SemanticTag.EYELASH))
		assertEquals(PartRole.BLUSH, PartRoles.fromName("腮红", "", SemanticTag.FACE_DETAIL))
		assertEquals(PartRole.SIDE, PartRoles.fromName("横髪R", "", SemanticTag.FRONT_HAIR))
		assertEquals(PartRole.HIGHLIGHT, PartRoles.fromName("layer 3", "head/eye/高光", SemanticTag.IRIDES), "a folder names its layers' role")
		assertNull(PartRoles.fromName("高光", "", SemanticTag.NOSE), "a nose has no highlight role")
	}

	@Test fun aLayersPlaceAmongItsPartNamesWhatItsNameDoesNot() {
		val iris = layer("iris", SemanticTag.IRIDES, Side.LEFT, 10, 10, 30, 34)
		val glint = layer("glint", SemanticTag.IRIDES, Side.LEFT, 14, 13, 19, 18, grey = 250)
		val pupil = layer("pupil", SemanticTag.IRIDES, Side.LEFT, 17, 19, 23, 25, grey = 10)
		val white = layer("white", SemanticTag.EYEWHITE, Side.LEFT, 6, 12, 34, 32)
		val lash = layer("lash", SemanticTag.EYELASH, Side.LEFT, 4, 8, 36, 14)
		val lower = layer("lower", SemanticTag.EYELASH, Side.LEFT, 10, 30, 30, 32)
		val lid = layer("lid", SemanticTag.EYELASH, Side.LEFT, 8, 4, 32, 6)
		val shade = layer("shade", SemanticTag.FACE_DETAIL, Side.NONE, 0, 40, 20, 50, blend = LayerBlend.Multiply)
		val roles = PartRoles.infer(listOf(iris, glint, pupil, white, lash, lower, lid, shade), emptyMap()).associate { it.source.id.raw to it.semantic.role }
		assertEquals(mapOf("iris" to null, "glint" to PartRole.HIGHLIGHT, "pupil" to PartRole.PUPIL, "white" to null, "lash" to null,
			"lower" to PartRole.LOWER, "lid" to PartRole.LID, "shade" to PartRole.SHADOW), roles)
	}

	@Test fun theUsersRoleWinsAndPlainKeepsTheGuessAway() {
		val iris = layer("iris", SemanticTag.IRIDES, Side.LEFT, 10, 10, 30, 34)
		val glint = layer("glint", SemanticTag.IRIDES, Side.LEFT, 14, 13, 19, 18, grey = 250, name = "高光")
		fun role(override: LayerClassificationOverride?) = PartRoles.infer(listOf(iris, glint), listOfNotNull(override?.let { "glint" to it }).toMap())
			.single { it.source.id.raw == "glint" }.semantic.role
		assertEquals(PartRole.HIGHLIGHT, role(null))
		assertNull(role(LayerClassificationOverride(LayerType.PRESET, SemanticTag.IRIDES, Side.LEFT, role = PartRole.PLAIN)))
		assertEquals(PartRole.PUPIL, role(LayerClassificationOverride(LayerType.PRESET, SemanticTag.IRIDES, Side.LEFT, role = PartRole.PUPIL)))
		assertNull(role(LayerClassificationOverride(LayerType.PRESET, SemanticTag.IRIDES, Side.LEFT, role = PartRole.SKIRT)), "a role the part cannot have")
	}

	@Test fun roleWordsAreTagsTheGeneratorsRead() {
		val resolution = LayerTags.resolve(setOf("eye", "iris", "left", "highlight", "外套"))
		assertEquals(Triple(SemanticTag.IRIDES, Side.LEFT, PartRole.HIGHLIGHT), Triple(resolution.tag, resolution.side, resolution.role))
		assertEquals(setOf("外套"), resolution.user)
		assertTrue("highlight" in LayerTags.of(SemanticTag.IRIDES, Side.LEFT, PartRole.HIGHLIGHT))
		assertEquals(LayerTags.of(SemanticTag.IRIDES, Side.LEFT), LayerTags.of(SemanticTag.IRIDES, Side.LEFT, PartRole.PLAIN))
		assertEquals(PartRole.PLAIN, LayerClassificationOverride.of(setOf("eye", "iris"), LayerVariant.NONE, cleared = true).role)
		assertNull(LayerClassificationOverride.of(setOf("eye", "iris"), LayerVariant.NONE).role)
		// An override without a role reads into revision identities as it did before roles.
		assertEquals("LayerClassificationOverride(type=PRESET, tag=FACE, side=NONE, parameter=, switchId=0)",
			LayerClassificationOverride(SemanticTag.FACE, Side.NONE).toString())
	}
}
