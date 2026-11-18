package io.github.psd2live.core

import io.github.psd2live.project.WorkspaceSourceArt
import io.github.psd2live.project.WorkspaceSourceLayer
import org.umamo.format.art.ChannelMask
import org.umamo.format.art.LayerBlend
import org.umamo.format.art.LayerBounds
import org.umamo.format.art.LayerId
import org.umamo.format.art.LayerRaster
import org.umamo.format.art.SourceLayerKind
import kotlin.test.Test
import kotlin.test.assertEquals

class LayerClassifierSideTest {
	@Test fun mouthPosePrefixesDoNotTurnNamedComponentsIntoApertures() {
		for (name in listOf("open mouth tongue", "mouth_open_tongue", "开口舌头", "開嘴舌頭")) {
			assertEquals(SemanticTag.TONGUE, LayerClassifier.classify(name).tag, name)
		}
		assertEquals(SemanticTag.TOOTH_T, LayerClassifier.classify("mouth open upper teeth").tag)
		assertEquals(SemanticTag.MOUTH_OPEN, LayerClassifier.classify("mouth open").tag)
	}
	@Test fun closedDescriptorsIdentifyAlternateMouthDrawingsWithoutChangingOtherParts() {
		for (name in listOf("mouth closed line", "closed mouth", "闭合嘴线", "嘴唇閉合", "嘴巴 合拢")) {
			assertEquals(SemanticTag.MOUTH_CLOSE, LayerClassifier.classify(name).tag, name)
		}
		val sided = LayerClassifier.classify("mouth closed line_L_2")
		assertEquals(SemanticTag.MOUTH_CLOSE, sided.tag)
		assertEquals(Side.LEFT, sided.side)
		assertEquals(2, sided.variant)
		assertEquals(SemanticTag.MOUTH, LayerClassifier.classify("upper lip line").tag)
		assertEquals(SemanticTag.MOUTH, LayerClassifier.classify("口腔").tag)
		assertEquals(SemanticTag.HANDWEAR, LayerClassifier.classify("袖子闭合").tag)
	}
	@Test fun posePrefixedMouthNamesAndLipRolesClassifyWithoutCollapsing() {
		for (name in listOf("微张嘴", "大张嘴", "小张嘴", "露齿")) {
			assertEquals(SemanticTag.MOUTH_OPEN, LayerClassifier.classify(name).tag, name)
		}
		for (name in listOf("自然闭嘴", "抿嘴")) {
			assertEquals(SemanticTag.MOUTH_CLOSE, LayerClassifier.classify(name).tag, name)
		}
		assertEquals(SemanticTag.TOOTH_T, LayerClassifier.classify("牙齿").tag)
		assertEquals(SemanticTag.MOUTH, LayerClassifier.classify("闭嘴唇底色").tag)
		assertEquals(PartRole.HIGHLIGHT, PartRoles.fromName("下唇角高光", "", SemanticTag.MOUTH))
		assertEquals(PartRole.SHADOW, PartRoles.fromName("下唇阴影", "", SemanticTag.MOUTH))
		assertEquals(PartRole.CLOSED_LINE, PartRoles.fromName("嘴唇中线", "", SemanticTag.MOUTH))
		assertEquals(PartRole.CLOSED_LINE, PartRoles.fromName("微笑嘴线", "", SemanticTag.MOUTH))
		assertEquals(SemanticTag.FACE_DETAIL, LayerClassifier.classify("嘟嘴嘴角腮红_L").tag)
		assertEquals(SemanticTag.UNKNOWN, LayerClassifier.classify("嘟嘴嘴角线_R").tag)
	}
	@Test fun specificFoldersSupplyMissingArtworkNamesWithoutOverridingNamedParts() {
		assertEquals(null, LayerClassifier.classifyArtwork("Layer 9", "mouth").variant)
		assertEquals(2, LayerClassifier.classifyArtwork("Layer 9", "mouth2").variant)
		assertEquals(SemanticTag.MOUTH, LayerClassifier.classifyArtwork("Layer 9", "mouth/Group 7").tag)
		assertEquals(SemanticTag.MOUTH, LayerClassifier.classifyArtwork("Layer 9", "lips").tag)
		assertEquals(SemanticTag.FACE, LayerClassifier.classifyArtwork("Layer 9", "face").tag)
		assertEquals(SemanticTag.TOOTH_T, LayerClassifier.classifyArtwork("上牙", "mouth").tag)
		assertEquals(SemanticTag.UNKNOWN, LayerClassifier.classifyArtwork("Layer 9", "face/left eye").tag)
		assertEquals(SemanticTag.UNKNOWN, LayerClassifier.classifyArtwork("Layer 9", "body").tag)
		assertEquals(SemanticTag.MOUTH, LayerClassifier.classify("上嘴线").tag)
		assertEquals(PartRole.UPPER_LINE, PartRoles.fromName("上嘴线", "", SemanticTag.MOUTH))
		assertEquals(PartRole.LOWER_LINE, PartRoles.fromName("下嘴线", "", SemanticTag.MOUTH))
	}

	@Test fun faceHairProjectionsUseTheHairFieldAndKeepFacialShadingOnTheFace() {
		val direct = LayerClassifier.classifyArtwork("发阴影 2", "character/face")
		val grouped = LayerClassifier.classifyArtwork("Layer 9", "character/face/fringe cast shadows")
		assertEquals(SemanticTag.FRONT_HAIR, direct.tag)
		assertEquals(PartRole.SHADOW, direct.role)
		assertEquals(SemanticTag.FRONT_HAIR, grouped.tag)
		assertEquals(PartRole.SHADOW, PartRoles.fromName("Layer 9", "face/fringe cast shadows", grouped.tag))
		assertEquals(SemanticTag.FACE, LayerClassifier.classifyArtwork("皮肤阴影", "character/face").tag)
		assertEquals(SemanticTag.UNKNOWN, LayerClassifier.classifyArtwork("发阴影软边", "character/clothing").tag)
		assertEquals(SemanticTag.EYEBROW, LayerClassifier.classifyArtwork("eyebrow lower-r", "character/face/fringe cast shadows").tag)
	}
	private fun parse(name: String) = LayerClassifier.classify(name).let { it.tag to it.side }

	@Test fun arrowMarksNameTheSideLikeKoreanOnes() {
		// ">" is the character's left (drawn on the screen right), "<" the character's right.
		assertEquals(parse("눈썹_좌"), parse("눈썹_>"))
		assertEquals(parse("눈썹_우"), parse("눈썹_<"))
		assertEquals(SemanticTag.EYEBROW to Side.LEFT, parse("눈썹_>"))
		assertEquals(SemanticTag.EYEBROW to Side.RIGHT, parse("눈썹_<"))
		assertEquals(SemanticTag.EYEWHITE to Side.LEFT, parse("흰자 >"))
		assertEquals(SemanticTag.EYELASH to Side.LEFT, parse("속눈썹 >"))
		assertEquals(SemanticTag.EYELASH to Side.RIGHT, parse("속눈썹 <"))
		assertEquals(SemanticTag.IRIDES to Side.RIGHT, parse("눈동자-<"))
		assertEquals(SemanticTag.EYELASH to Side.LEFT, parse("속눈썹(>)"))
		assertEquals(SemanticTag.EYEWHITE to Side.RIGHT, parse("흰자(<)"))
		assertEquals(SemanticTag.EYELASH to Side.LEFT, parse("> 속눈썹"))
		assertEquals(SemanticTag.EYELASH to Side.RIGHT, parse("< 속눈썹"))
		// Full-width marks normalize to the same.
		assertEquals(SemanticTag.EYEBROW to Side.LEFT, parse("눈썹_＞"))
	}

	@Test fun arrowMarksKeepVariantsAndCopySuffixes() {
		val semantic = LayerClassifier.classify("흰자_> 2")
		assertEquals(SemanticTag.EYEWHITE to Side.LEFT, semantic.tag to semantic.side)
		assertEquals(2, semantic.variant)
		assertEquals(SemanticTag.TOOTH_T to Side.RIGHT, parse("윗니_< 복사"))
	}

	@Test fun existingSideNamesStillParse() {
		assertEquals(SemanticTag.EYEBROW to Side.LEFT, parse("눈썹_좌"))
		assertEquals(SemanticTag.EYEBROW to Side.RIGHT, parse("눈썹(우)"))
		assertEquals(SemanticTag.EYELASH to Side.LEFT, parse("왼쪽 속눈썹"))
		assertEquals(SemanticTag.EYELASH to Side.RIGHT, parse("eyelash-r"))
		assertEquals(SemanticTag.EYEBROW to Side.NONE, parse("눈썹"))
	}

	@Test fun eyelidPaintFollowsItsNamedEyeWithoutBecomingCheekBlushOrLashInk() {
		fun eyeDetail(name: String, expectedSide: Side) {
			assertEquals(SemanticTag.EYELASH to expectedSide, parse(name))
			assertEquals(PartRole.EYE_DETAIL, PartRoles.fromName(name, "", SemanticTag.EYELASH))
		}
		eyeDetail("左上眼瞼紅暈", Side.LEFT)
		eyeDetail("右下眼角红晕", Side.RIGHT)
		eyeDetail("右眼下眼睑", Side.RIGHT)
		eyeDetail("右眼上眼线高光", Side.RIGHT)
		eyeDetail("左眼上眼线遮盖", Side.LEFT)
		eyeDetail("左眼眼眶阴影遮盖", Side.LEFT)
		eyeDetail("eyelid skin-l", Side.LEFT)
		assertEquals(SemanticTag.EYELASH to Side.RIGHT, parse("右眼双眼皮线"))
		assertEquals(PartRole.LID, PartRoles.fromName("右眼双眼皮线", "", SemanticTag.EYELASH))
		assertEquals(SemanticTag.FACE_DETAIL to Side.RIGHT, parse("右脸颊红晕"))
		assertEquals(PartRole.BLUSH, PartRoles.fromName("右脸颊红晕", "", SemanticTag.FACE_DETAIL))
	}

	@Test fun browShadingAndExplicitCoverStayDistinguishableFromOrdinaryStrokes() {
		val shadow = "左眉前阴影"
		assertEquals(SemanticTag.EYEBROW to Side.LEFT, parse(shadow))
		assertEquals(PartRole.BROW_DETAIL, PartRoles.fromName(shadow, "", SemanticTag.EYEBROW))
		assertEquals(false, PartRoles.isAuthoredBrowCover(shadow))
		assertEquals(true, PartRoles.isAuthoredBrowCover("eyebrow front-l"))
		assertEquals(null, PartRoles.fromName("eyebrow front-l", "", SemanticTag.EYEBROW))
		assertEquals(true, PartRoles.isAuthoredBrowCover("右眉毛遮盖"))
		assertEquals(PartRole.BROW_DETAIL, PartRoles.fromName("右眉毛遮盖", "", SemanticTag.EYEBROW))
	}

	@Test fun eyelidPaintUsesTheSideOfItsOpeningWithoutChangingExplicitOrBilateralLayers() {
		fun layer(id: String, name: String, left: Int, width: Int) =
			WorkspaceSourceLayer(LayerId(id), name, "", SourceLayerKind.Raster, true, 0,
				LayerBounds(left, 10, width, 8), 1f, false, LayerBlend.Normal, ChannelMask.ALL,
				LayerRaster(width, 8, ByteArray(width * 8 * 4) { -1 }), null, null, false)
		val right = layer("right", "eye white-r", 12, 24)
		val left = layer("left", "eye white-l", 64, 24)
		val accents = listOf(
			layer("tint", "left eyelid tint", 10, 22),
			layer("cover", "right eyeliner cover", 63, 22),
			layer("both", "eyelid tint", 10, 78),
			layer("explicit", "left eyelid tint", 10, 22),
		)
		fun calculate(whites: List<WorkspaceSourceLayer>): Map<String, Side> {
			val source = WorkspaceSourceArt(100, 60, whites + accents, emptyList())
			val config = PipelineConfig(layerOverrides = mapOf("explicit" to
				LayerClassificationOverride(SemanticTag.EYELASH, Side.LEFT)))
			return CharacterAnalyzer.analyze(source, config).layers.associate { it.source.id.raw to it.semantic.side }
		}
		val withBothEyes = calculate(listOf(right, left))
		assertEquals(Side.RIGHT, withBothEyes["tint"])
		assertEquals(Side.LEFT, withBothEyes["cover"])
		assertEquals(Side.NONE, withBothEyes["both"])
		assertEquals(Side.LEFT, withBothEyes["explicit"])
		assertEquals(Side.LEFT, calculate(listOf(left))["tint"])
		assertEquals(Side.LEFT, calculate(listOf(right.copy(name = "eye white-l"), left.copy(name = "eye white-r")))["tint"])
	}
}
