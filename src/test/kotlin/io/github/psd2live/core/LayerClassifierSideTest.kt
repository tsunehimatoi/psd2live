package io.github.psd2live.core

import kotlin.test.Test
import kotlin.test.assertEquals

class LayerClassifierSideTest {
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
}
