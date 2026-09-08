package io.github.psd2live.core

import kotlin.test.Test
import kotlin.test.assertEquals

class LayerClassifierTest {

	@Test
	fun classifiesStandardEnglishNames() {
		assertEquals(SemanticTag.FACE, LayerClassifier.classify("face").tag)
		assertEquals(SemanticTag.FRONT_HAIR, LayerClassifier.classify("front hair").tag)
		assertEquals(SemanticTag.BACK_HAIR, LayerClassifier.classify("back hair").tag)
		assertEquals(SemanticTag.IRIDES, LayerClassifier.classify("eye").tag)
		assertEquals(SemanticTag.EYEBROW, LayerClassifier.classify("eyebrow").tag)
	}

	@Test
	fun classifiesChineseNamesAndVariants() {
		// Basic Chinese names
		assertEquals(SemanticTag.FRONT_HAIR, LayerClassifier.classify("前发").tag)
		assertEquals(SemanticTag.FRONT_HAIR, LayerClassifier.classify("刘海").tag)
		assertEquals(SemanticTag.BACK_HAIR, LayerClassifier.classify("后发").tag)
		assertEquals(SemanticTag.BACK_HAIR, LayerClassifier.classify("马尾").tag)
		assertEquals(SemanticTag.BACK_HAIR, LayerClassifier.classify("双马尾").tag)
		assertEquals(SemanticTag.FACE, LayerClassifier.classify("脸").tag)
		assertEquals(SemanticTag.FACE, LayerClassifier.classify("脸部轮廓").tag)
		assertEquals(SemanticTag.FACE_DETAIL, LayerClassifier.classify("腮红").tag)
		assertEquals(SemanticTag.IRIDES, LayerClassifier.classify("眼睛").tag)
		assertEquals(SemanticTag.IRIDES, LayerClassifier.classify("眼").tag)
		assertEquals(SemanticTag.IRIDES, LayerClassifier.classify("瞳孔").tag)
		assertEquals(SemanticTag.IRIDES, LayerClassifier.classify("眼珠").tag)
		assertEquals(SemanticTag.EYEWHITE, LayerClassifier.classify("眼白").tag)
		assertEquals(SemanticTag.EYELASH, LayerClassifier.classify("睫毛").tag)
		assertEquals(SemanticTag.EYELASH, LayerClassifier.classify("上睫毛").tag)
		assertEquals(SemanticTag.EYELASH, LayerClassifier.classify("下眼线").tag)
		assertEquals(SemanticTag.EYE_CLOSE, LayerClassifier.classify("闭眼").tag)
		assertEquals(SemanticTag.EYE_CLOSE, LayerClassifier.classify("笑眼").tag)
		assertEquals(SemanticTag.EYEBROW, LayerClassifier.classify("眉毛").tag)
		assertEquals(SemanticTag.MOUTH, LayerClassifier.classify("嘴巴").tag)
		assertEquals(SemanticTag.MOUTH, LayerClassifier.classify("口内").tag)
		assertEquals(SemanticTag.MOUTH_OPEN, LayerClassifier.classify("张嘴").tag)
		assertEquals(SemanticTag.MOUTH_CLOSE, LayerClassifier.classify("闭嘴").tag)
		assertEquals(SemanticTag.TOPWEAR, LayerClassifier.classify("身体").tag)
		assertEquals(SemanticTag.TOPWEAR, LayerClassifier.classify("衣服").tag)
		assertEquals(SemanticTag.HANDWEAR, LayerClassifier.classify("手臂").tag)
		assertEquals(SemanticTag.FOOTWEAR, LayerClassifier.classify("鞋子").tag)

		// Chinese variant without delimiter
		val hair1 = LayerClassifier.classify("前发1")
		assertEquals(SemanticTag.FRONT_HAIR, hair1.tag)
		assertEquals(1, hair1.variant)

		val bangs2 = LayerClassifier.classify("刘海2")
		assertEquals(SemanticTag.FRONT_HAIR, bangs2.tag)
		assertEquals(2, bangs2.variant)

		// Chinese side prefix
		val leftEye = LayerClassifier.classify("左眼")
		assertEquals(SemanticTag.IRIDES, leftEye.tag)
		assertEquals(Side.LEFT, leftEye.side)

		// Chinese side suffix without delimiter
		val eyeRight = LayerClassifier.classify("眼睛右")
		assertEquals(SemanticTag.IRIDES, eyeRight.tag)
		assertEquals(Side.RIGHT, eyeRight.side)

		// Chinese bracketed side
		val eyeLeftBracket = LayerClassifier.classify("眼睛(左)")
		assertEquals(SemanticTag.IRIDES, eyeLeftBracket.tag)
		assertEquals(Side.LEFT, eyeLeftBracket.side)

		val eyeRightFullBracket = LayerClassifier.classify("眼睛（右）")
		assertEquals(SemanticTag.IRIDES, eyeRightFullBracket.tag)
		assertEquals(Side.RIGHT, eyeRightFullBracket.side)

		// Chinese compound side and variant
		val frontHairLeft1 = LayerClassifier.classify("前发左1")
		assertEquals(SemanticTag.FRONT_HAIR, frontHairLeft1.tag)
		assertEquals(Side.LEFT, frontHairLeft1.side)
		assertEquals(1, frontHairLeft1.variant)

		val frontHair1Left = LayerClassifier.classify("前发1左")
		assertEquals(SemanticTag.FRONT_HAIR, frontHair1Left.tag)
		assertEquals(Side.LEFT, frontHair1Left.side)
		assertEquals(1, frontHair1Left.variant)
	}

	@Test
	fun classifiesJapaneseLive2DTemplateNames() {
		// Live2D standard Japanese template parts
		assertEquals(SemanticTag.FACE, LayerClassifier.classify("輪郭").tag)
		assertEquals(SemanticTag.FACE, LayerClassifier.classify("顔").tag)
		assertEquals(SemanticTag.FACE_DETAIL, LayerClassifier.classify("チーク").tag)
		assertEquals(SemanticTag.FACE_DETAIL, LayerClassifier.classify("ほほ").tag)
		assertEquals(SemanticTag.IRIDES, LayerClassifier.classify("瞳").tag)
		assertEquals(SemanticTag.IRIDES, LayerClassifier.classify("目").tag)
		assertEquals(SemanticTag.IRIDES, LayerClassifier.classify("ハイライト").tag)
		assertEquals(SemanticTag.EYEWHITE, LayerClassifier.classify("白目").tag)
		assertEquals(SemanticTag.EYELASH, LayerClassifier.classify("まつ毛").tag)
		assertEquals(SemanticTag.EYELASH, LayerClassifier.classify("上まつ毛").tag)
		assertEquals(SemanticTag.EYELASH, LayerClassifier.classify("下まつ毛").tag)
		assertEquals(SemanticTag.EYELASH, LayerClassifier.classify("アイライン").tag)
		assertEquals(SemanticTag.EYELASH, LayerClassifier.classify("二重").tag)
		assertEquals(SemanticTag.EYE_CLOSE, LayerClassifier.classify("目閉じ").tag)
		assertEquals(SemanticTag.EYE_CLOSE, LayerClassifier.classify("笑顔").tag)
		assertEquals(SemanticTag.EYEBROW, LayerClassifier.classify("眉").tag)
		assertEquals(SemanticTag.EYEBROW, LayerClassifier.classify("まゆ毛").tag)
		assertEquals(SemanticTag.MOUTH, LayerClassifier.classify("口").tag)
		assertEquals(SemanticTag.MOUTH, LayerClassifier.classify("口内").tag)
		assertEquals(SemanticTag.MOUTH_OPEN, LayerClassifier.classify("口開き").tag)
		assertEquals(SemanticTag.MOUTH_CLOSE, LayerClassifier.classify("口閉じ").tag)
		assertEquals(SemanticTag.FRONT_HAIR, LayerClassifier.classify("前髪").tag)
		assertEquals(SemanticTag.FRONT_HAIR, LayerClassifier.classify("横髪").tag)
		assertEquals(SemanticTag.FRONT_HAIR, LayerClassifier.classify("側髪").tag)
		assertEquals(SemanticTag.FRONT_HAIR, LayerClassifier.classify("もみあげ").tag)
		assertEquals(SemanticTag.FRONT_HAIR, LayerClassifier.classify("アホ毛").tag)
		assertEquals(SemanticTag.BACK_HAIR, LayerClassifier.classify("後ろ髪").tag)
		assertEquals(SemanticTag.BACK_HAIR, LayerClassifier.classify("後髪").tag)
		assertEquals(SemanticTag.BACK_HAIR, LayerClassifier.classify("ポニーテール").tag)
		assertEquals(SemanticTag.BACK_HAIR, LayerClassifier.classify("ツインテール").tag)
		assertEquals(SemanticTag.TOPWEAR, LayerClassifier.classify("体").tag)
		assertEquals(SemanticTag.TOPWEAR, LayerClassifier.classify("服").tag)
		assertEquals(SemanticTag.HANDWEAR, LayerClassifier.classify("腕").tag)
		assertEquals(SemanticTag.FOOTWEAR, LayerClassifier.classify("靴").tag)

		// Japanese side and variant without delimiters
		val hitomiL = LayerClassifier.classify("左目")
		assertEquals(SemanticTag.IRIDES, hitomiL.tag)
		assertEquals(Side.LEFT, hitomiL.side)

		val maeGami1 = LayerClassifier.classify("前髪1")
		assertEquals(SemanticTag.FRONT_HAIR, maeGami1.tag)
		assertEquals(1, maeGami1.variant)

		val yokoGamiR = LayerClassifier.classify("横髪右")
		assertEquals(SemanticTag.FRONT_HAIR, yokoGamiR.tag)
		assertEquals(Side.RIGHT, yokoGamiR.side)

		val shiromeLeft = LayerClassifier.classify("白目(左)")
		assertEquals(SemanticTag.EYEWHITE, shiromeLeft.tag)
		assertEquals(Side.LEFT, shiromeLeft.side)
	}

	@Test
	fun classifiesEnglishPrefixAndSuffixSides() {
		val lEye = LayerClassifier.classify("l_eye")
		assertEquals(SemanticTag.IRIDES, lEye.tag)
		assertEquals(Side.LEFT, lEye.side)

		val rBrow = LayerClassifier.classify("r-brow")
		assertEquals(SemanticTag.EYEBROW, rBrow.tag)
		assertEquals(Side.RIGHT, rBrow.side)

		val leftArm = LayerClassifier.classify("left_arm")
		assertEquals(SemanticTag.HANDWEAR, leftArm.tag)
		assertEquals(Side.LEFT, leftArm.side)

		val hair1L = LayerClassifier.classify("hair_1_l")
		assertEquals(SemanticTag.FRONT_HAIR, hair1L.tag)
		assertEquals(Side.LEFT, hair1L.side)
		assertEquals(1, hair1L.variant)

		val eyeLBracket = LayerClassifier.classify("eye(l)")
		assertEquals(SemanticTag.IRIDES, eyeLBracket.tag)
		assertEquals(Side.LEFT, eyeLBracket.side)
	}
}

