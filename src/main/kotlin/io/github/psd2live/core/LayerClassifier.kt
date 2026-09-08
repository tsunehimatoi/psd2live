package io.github.psd2live.core

import org.umamo.format.art.SourceLayer
import java.text.Normalizer
import java.util.Locale

/** See-Through/Stretchy names plus Anime2.5DRig's English, Chinese and Japanese aliases. */
object LayerClassifier {
	private val aliases: Map<String, SemanticTag> = buildMap {
		fun names(tag: SemanticTag, vararg values: String) = values.forEach { put(it, tag) }
		names(SemanticTag.BACK_HAIR, "back hair", "backhair", "hair back", "hair_back", "后发", "后髪", "后脑勺", "後ろ髪", "後髪", "うしろがみ", "后头", "後頭部", "马尾", "馬尾", "ポニーテール", "ポニテ", "双马尾", "雙馬尾", "ツインテール", "ツインテ", "背发", "背髪")
		names(SemanticTag.FRONT_HAIR, "front hair", "fronthair", "hair front", "hair_front", "hair", "bangs", "前发", "前髪", "刘海", "瀏海", "まえがみ", "侧发", "側髪", "横髪", "鬓角", "もみあげ", "サイドヘア", "サイド", "呆毛", "アホ毛", "ahoge")
		names(SemanticTag.HEADWEAR, "headwear", "hat", "cap", "帽子", "头饰", "頭飾", "发饰", "髪飾り", "カチューシャ", "リボン", "发带", "蝴蝶结")
		names(SemanticTag.FACE, "face", "head", "脸", "臉", "脸部", "面部", "顔", "かお", "輪郭", "脸轮廓", "脸部轮廓", "脸蛋", "头部", "头", "頭", "あたま")
		names(SemanticTag.FACE_DETAIL, "facedetail", "face detail", "face_detail", "脸部细节", "面部细节", "腮红", "紅暈", "红晕", "ほほ", "頬", "チーク", "blush", "脸颊", "泪痕")
		names(SemanticTag.IRIDES, "irides", "iris", "pupil", "pupils", "eyes", "eye", "瞳孔", "虹膜", "眼珠", "眼睛", "目", "眼", "瞳", "ひとみ", "眼球", "眼黑", "目玉", "め", "ハイライト", "高光", "眼睛高光", "眼部高光", "瞳高光")
		names(SemanticTag.EYEBROW, "eyebrow", "eyebrows", "brow", "brows", "眉毛", "眉", "まゆ毛", "まゆげ", "まゆ")
		names(SemanticTag.EYEWHITE, "eyewhite", "eye white", "eye_white", "eyewhites", "眼白", "白眼", "白目", "目白", "巩膜")
		names(SemanticTag.EYELASH, "eyelash", "eyelashes", "lash", "lashes", "eye open", "eye_open", "睫毛", "まつ毛", "まつげ", "上睫毛", "下睫毛", "上まつ毛", "下まつ毛", "上まつげ", "下まつげ", "アイライン", "眼线", "上眼线", "下眼线", "二重")
		names(SemanticTag.EYE_CLOSE, "eye close", "eye_close", "eye c", "eye_c", "eyelash c", "eyelash_c", "closed eye", "closed_eye", "闭眼", "閉眼", "目閉じ", "閉じ目", "笑眼", "眯眼", "笑顔", "笑い目", "eye smile", "eye_smile")
		names(SemanticTag.EYEWEAR, "eyewear", "glasses", "眼镜", "眼鏡", "めがね")
		names(SemanticTag.EARS, "ears", "ear", "耳朵", "耳", "みみ")
		names(SemanticTag.EARWEAR, "earwear", "earring", "earrings", "耳环", "耳環", "耳饰", "耳飾", "イヤリング", "ピアス")
		names(SemanticTag.NOSE, "nose", "鼻子", "鼻", "はな")
		names(SemanticTag.MOUTH, "mouth", "口", "嘴", "嘴巴", "口内", "口腔", "嘴部", "くち")
		names(SemanticTag.MOUTH_OPEN, "mouth open", "mouth_open", "mouth o", "mouth_o", "open mouth", "open_mouth", "张嘴", "張嘴", "开口", "開口", "口開き", "开嘴", "開嘴", "开嘴巴")
		names(SemanticTag.MOUTH_CLOSE, "mouth close", "mouth_close", "mouth c", "mouth_c", "close mouth", "close_mouth", "闭嘴", "閉嘴", "闭口", "閉口", "口閉じ")
		names(SemanticTag.TOOTH_T, "tooth-t", "tooth_t", "tooth t", "upper tooth", "upper teeth", "上牙", "上歯", "上齿")
		names(SemanticTag.TOOTH_B, "tooth-b", "tooth_b", "tooth b", "lower tooth", "lower teeth", "下牙", "下歯", "下齿")
		names(SemanticTag.TONGUE, "tongue", "舌头", "舌頭", "舌", "ベロ")
		names(SemanticTag.NECK, "neck", "脖子", "颈部", "頸部", "首", "くび")
		names(SemanticTag.NECKWEAR, "neckwear", "collar", "scarf", "领饰", "領飾", "围巾", "マフラー")
		names(SemanticTag.TOPWEAR, "topwear", "clothes", "cloth", "shirt", "jacket", "上衣", "衣服", "服装", "服裝", "服", "身体", "身体", "体", "からだ", "胴体", "胴", "躯干", "上身")
		names(SemanticTag.HANDWEAR, "handwear", "hand", "hands", "arm", "arms", "手臂", "手", "腕", "うで", "手腕", "袖", "袖子")
		names(SemanticTag.BOTTOMWEAR, "bottomwear", "pants", "skirt", "下装", "下裝", "裤子", "褲子", "裙子", "スカート", "ズボン", "ボトムス", "下身")
		names(SemanticTag.LEGWEAR, "legwear", "leg", "legs", "腿", "大腿", "小腿")
		names(SemanticTag.FOOTWEAR, "footwear", "foot", "feet", "shoe", "shoes", "脚", "腳", "鞋", "鞋子", "靴", "くつ", "靴下", "袜子")
		names(SemanticTag.TAIL, "tail", "尾巴", "尾", "しっぽ")
		names(SemanticTag.WINGS, "wings", "wing", "翅膀", "翼", "つばさ", "羽")
		names(SemanticTag.OBJECTS, "objects", "object", "prop", "props", "道具", "物件")
	}

	private val sideSuffix = Regex(
		"(?:[\\s_-]*[（\\(\\[【](l|r|left|right|左|右)[）\\)\\]】]|[\\s_-]+(l|r|left|right|左|右)|(?<=[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}\\d])(左|右)|(?<=[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}])(l|r))$",
		RegexOption.IGNORE_CASE,
	)
	private val sidePrefix = Regex(
		"^(?:(左|右)[-_.\\s]*|(l|r|left|right)[-_.\\s]+|[（\\(\\[【](l|r|left|right|左|右)[）\\)\\]】][\\s_-]*)",
		RegexOption.IGNORE_CASE,
	)
	private val variantSuffix = Regex("(?:[-_.\\s]+|(?<=[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}a-zA-Z]))(\\d+)$")
	private val copySuffix = Regex("\\s*(?:copy|のコピー|的副本|副本)\\s*\\d*$", RegexOption.IGNORE_CASE)

	fun classify(name: String): LayerSemantic {
		var normalized = Normalizer.normalize(name, Normalizer.Form.NFKC)
			.trim()
			.lowercase(Locale.ROOT)
			.replace(copySuffix, "")
			.trim()
		var side = Side.NONE
		sidePrefix.find(normalized)?.let { match ->
			val rawSide = match.groupValues.drop(1).firstOrNull { it.isNotEmpty() }
			if (rawSide != null) {
				side = sideOf(rawSide)
				normalized = normalized.removeRange(match.range).trim()
			}
		}
		var variant: Int? = null
		while (true) {
			var changed = false
			if (side == Side.NONE) {
				sideSuffix.find(normalized)?.let { match ->
					val rawSide = match.groupValues.drop(1).firstOrNull { it.isNotEmpty() }
					if (rawSide != null) {
						side = sideOf(rawSide)
						normalized = normalized.removeRange(match.range).trim()
						changed = true
					}
				}
			}
			if (variant == null && normalized.any { it.isLetter() }) {
				variantSuffix.find(normalized)?.let { match ->
					val num = match.groupValues[1].toIntOrNull()
					if (num != null) {
						variant = num
						normalized = normalized.removeRange(match.range).trim()
						changed = true
					}
				}
			}
			if (!changed) break
		}
		val tag = aliases[normalized] ?: prefixMatch(normalized) ?: SemanticTag.UNKNOWN
		val confidence = when {
			aliases.containsKey(normalized) -> 1f
			tag != SemanticTag.UNKNOWN -> 0.85f
			else -> 0f
		}
		return LayerSemantic(tag, side, variant, normalized, confidence)
	}

	private fun prefixMatch(name: String): SemanticTag? =
		aliases.entries
			.sortedByDescending { it.key.length }
			.firstOrNull { (alias, _) ->
				name.startsWith("$alias ") || name.startsWith("$alias-") || name.startsWith("${alias}_") ||
					(alias.length >= 2 && name.startsWith(alias) && (name.removePrefix(alias).all { it.isDigit() } || name.all { it.code > 127 }))
			}
			?.value

	private fun sideOf(raw: String): Side =
		when (raw.lowercase(Locale.ROOT)) {
			"l", "left", "左" -> Side.LEFT
			else -> Side.RIGHT
		}

	fun classify(layer: SourceLayer, alphaThreshold: Int): ClassifiedLayer {
		val rgba = layer.raster.rgba
		var minX = layer.raster.width
		var minY = layer.raster.height
		var maxX = -1
		var maxY = -1
		var count = 0
		var sumX = 0L
		var sumY = 0L
		for (y in 0 until layer.raster.height) {
			for (x in 0 until layer.raster.width) {
				if ((rgba[(y * layer.raster.width + x) * 4 + 3].toInt() and 0xff) < alphaThreshold) continue
				minX = minOf(minX, x)
				minY = minOf(minY, y)
				maxX = maxOf(maxX, x)
				maxY = maxOf(maxY, y)
				count++
				sumX += x
				sumY += y
			}
		}
		val bounds = if (count == 0) {
			Bounds(
				layer.bounds.left.toFloat(),
				layer.bounds.top.toFloat(),
				(layer.bounds.left + layer.bounds.width).toFloat(),
				(layer.bounds.top + layer.bounds.height).toFloat(),
			)
		} else {
			Bounds(
				(layer.bounds.left + minX).toFloat(),
				(layer.bounds.top + minY).toFloat(),
				(layer.bounds.left + maxX + 1).toFloat(),
				(layer.bounds.top + maxY + 1).toFloat(),
			)
		}
		return ClassifiedLayer(
			source = layer,
			semantic = classify(layer.name),
			bounds = bounds,
			centroidX = if (count == 0) bounds.centerX else layer.bounds.left + sumX.toFloat() / count,
			centroidY = if (count == 0) bounds.centerY else layer.bounds.top + sumY.toFloat() / count,
			opaquePixels = count,
		)
	}
}
