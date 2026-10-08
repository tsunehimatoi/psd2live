package io.github.psd2live.core

import kotlin.test.*

/** Korean layer names: the aliases, the sides spelled 좌/우/왼쪽/오른쪽, numbers and Photoshop's "복사" copies. */
class LayerClassifierKoreanTest {
	private fun tag(name: String) = LayerClassifier.classify(name).tag

	@Test
	fun koreanNamesClassifyLikeTheirEnglishAliases() {
		mapOf(
			"앞머리" to "front hair", "옆머리" to "front hair", "뒷머리" to "back hair", "포니테일" to "back hair",
			"모자" to "hat", "얼굴" to "face", "볼터치" to "blush", "눈동자" to "iris", "눈" to "eye",
			"눈썹" to "eyebrow", "흰자" to "eye white", "눈 흰자" to "eye white", "속눈썹" to "eyelash",
			"감은 눈" to "eye close", "안경" to "glasses", "귀" to "ear", "귀걸이" to "earring", "코" to "nose",
			"입" to "mouth", "벌린 입" to "open mouth", "다문 입" to "close mouth", "윗니" to "upper teeth",
			"아랫니" to "lower teeth", "혀" to "tongue", "목" to "neck", "목도리" to "scarf", "상의" to "shirt",
			"몸" to "clothes", "팔" to "arm", "손" to "hand", "치마" to "skirt", "다리" to "leg", "신발" to "shoe",
			"꼬리" to "tail", "날개" to "wing", "소품" to "prop",
		).forEach { (korean, english) ->
			assertEquals(tag(english), tag(korean), korean)
			assertEquals(1f, LayerClassifier.classify(korean).confidence, korean)
		}
	}

	@Test
	fun koreanSidesAreRead() {
		listOf("왼쪽 눈", "왼쪽눈", "왼눈", "눈 왼쪽", "눈왼쪽", "눈_좌", "눈 (좌)", "좌 눈", "눈_l", "눈l").forEach {
			val semantic = LayerClassifier.classify(it)
			assertEquals(SemanticTag.IRIDES, semantic.tag, it)
			assertEquals(Side.LEFT, semantic.side, it)
		}
		listOf("오른쪽 팔", "오른팔", "팔 오른쪽", "팔_우", "팔 [우]", "우 팔", "팔-r").forEach {
			val semantic = LayerClassifier.classify(it)
			assertEquals(SemanticTag.HANDWEAR, semantic.tag, it)
			assertEquals(Side.RIGHT, semantic.side, it)
		}
	}

	@Test
	fun singleSyllableSidesNeedASeparator() {
		// 여우 (fox) and 새우 (shrimp) end in 우, 좌 and 우 glued to a word are not read as sides.
		listOf("여우", "새우", "눈우").forEach { assertEquals(Side.NONE, LayerClassifier.classify(it).side, it) }
	}

	@Test
	fun numbersAndCopiesFollowKoreanNames() {
		LayerClassifier.classify("앞머리2").let {
			assertEquals(SemanticTag.FRONT_HAIR, it.tag)
			assertEquals(2, it.variant)
		}
		LayerClassifier.classify("왼쪽 속눈썹 3").let {
			assertEquals(SemanticTag.EYELASH, it.tag)
			assertEquals(Side.LEFT, it.side)
			assertEquals(3, it.variant)
		}
		assertEquals(SemanticTag.EYEBROW, tag("눈썹 복사"))
		assertEquals(SemanticTag.EYEBROW, tag("눈썹 복사 2"))
	}

	@Test
	fun longerKoreanNamesWinOverTheirFirstWord() {
		assertEquals(SemanticTag.EYEWHITE, tag("눈 흰자"))
		assertEquals(SemanticTag.EYE_CLOSE, tag("눈 감음"))
		assertEquals(SemanticTag.MOUTH_OPEN, tag("입 벌림"))
		assertEquals(SemanticTag.EARWEAR, tag("귀 장식"))
		assertEquals(SemanticTag.NECKWEAR, tag("목 장식"))
		assertEquals(SemanticTag.HEADWEAR, tag("머리 장식"))
	}
}
