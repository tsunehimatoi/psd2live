package io.github.psd2live.core

import kotlin.test.Test
import kotlin.test.assertEquals

class LayerClassifierSideTest {
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
