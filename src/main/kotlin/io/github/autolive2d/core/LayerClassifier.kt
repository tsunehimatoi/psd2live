package io.github.autolive2d.core

import org.umamo.format.art.SourceLayer
import java.text.Normalizer
import java.util.Locale

/** See-Through/Stretchy names plus Anime2.5DRig's English, Chinese and Japanese aliases. */
object LayerClassifier {
	private val aliases: Map<String, SemanticTag> = buildMap {
		fun names(tag: SemanticTag, vararg values: String) = values.forEach { put(it, tag) }
		names(SemanticTag.BACK_HAIR, "back hair", "backhair", "hair back", "hair_back", "后发", "后髪", "后脑勺", "後ろ髪")
		names(SemanticTag.FRONT_HAIR, "front hair", "fronthair", "hair front", "hair_front", "前发", "前髪", "刘海", "瀏海")
		names(SemanticTag.HEADWEAR, "headwear", "hat", "帽子", "头饰", "頭飾", "发饰", "髪飾り")
		names(SemanticTag.FACE, "face", "head", "脸", "臉", "脸部", "面部", "顔")
		names(SemanticTag.FACE_DETAIL, "facedetail", "face detail", "脸部细节", "面部细节", "腮红", "紅暈", "blush")
		names(SemanticTag.IRIDES, "irides", "iris", "pupil", "pupils", "eyes", "eye", "瞳孔", "虹膜", "眼珠", "眼睛", "目")
		names(SemanticTag.EYEBROW, "eyebrow", "eyebrows", "brow", "brows", "眉毛", "眉")
		names(SemanticTag.EYEWHITE, "eyewhite", "eye white", "eye_white", "eyewhites", "眼白", "白眼", "目白")
		names(SemanticTag.EYELASH, "eyelash", "eyelashes", "lash", "lashes", "eye open", "eye_open", "睫毛", "まつ毛", "まつげ")
		names(SemanticTag.EYE_CLOSE, "eye close", "eye_close", "eye c", "eye_c", "eyelash c", "eyelash_c", "closed eye", "closed_eye", "闭眼", "閉眼", "目閉じ", "閉じ目")
		names(SemanticTag.EYEWEAR, "eyewear", "glasses", "眼镜", "眼鏡")
		names(SemanticTag.EARS, "ears", "ear", "耳朵", "耳")
		names(SemanticTag.EARWEAR, "earwear", "earring", "earrings", "耳环", "耳環", "耳饰", "耳飾")
		names(SemanticTag.NOSE, "nose", "鼻子", "鼻")
		names(SemanticTag.MOUTH, "mouth", "口", "嘴", "嘴巴")
		names(SemanticTag.MOUTH_OPEN, "mouth open", "mouth_open", "mouth o", "mouth_o", "open mouth", "open_mouth", "张嘴", "張嘴", "开口", "開口", "口開き")
		names(SemanticTag.MOUTH_CLOSE, "mouth close", "mouth_close", "mouth c", "mouth_c", "close mouth", "close_mouth", "闭嘴", "閉嘴", "闭口", "口閉じ")
		names(SemanticTag.NECK, "neck", "脖子", "颈部", "頸部", "首")
		names(SemanticTag.NECKWEAR, "neckwear", "collar", "scarf", "领饰", "領飾", "围巾")
		names(SemanticTag.TOPWEAR, "topwear", "clothes", "cloth", "shirt", "jacket", "上衣", "衣服", "服装", "服裝", "服")
		names(SemanticTag.HANDWEAR, "handwear", "hand", "hands", "arm", "arms", "手臂", "手", "腕")
		names(SemanticTag.BOTTOMWEAR, "bottomwear", "pants", "skirt", "下装", "下裝", "裤子", "褲子", "裙子")
		names(SemanticTag.LEGWEAR, "legwear", "leg", "legs", "腿", "大腿")
		names(SemanticTag.FOOTWEAR, "footwear", "foot", "feet", "shoe", "shoes", "脚", "腳", "鞋")
		names(SemanticTag.TAIL, "tail", "尾巴", "尾")
		names(SemanticTag.WINGS, "wings", "wing", "翅膀", "翼")
		names(SemanticTag.OBJECTS, "objects", "object", "prop", "props", "道具", "物件")
	}

	private val sideSuffix = Regex("(?:[-_.\\s]+)(l|r|left|right|左|右)$", RegexOption.IGNORE_CASE)
	private val sidePrefix = Regex("^(左|右)(?:[-_.\\s]+)?", RegexOption.IGNORE_CASE)
	private val variantSuffix = Regex("(?:[-_\\s]+)(\\d+)$")
	private val copySuffix = Regex("\\s*(?:copy|のコピー|的副本|副本)\\s*\\d*$", RegexOption.IGNORE_CASE)

	fun classify(name: String): LayerSemantic {
		var normalized = Normalizer.normalize(name, Normalizer.Form.NFKC)
			.trim()
			.lowercase(Locale.ROOT)
			.replace(copySuffix, "")
			.trim()
		var side = Side.NONE
		sideSuffix.find(normalized)?.let { match ->
			side = sideOf(match.groupValues[1])
			normalized = normalized.removeRange(match.range).trim()
		} ?: sidePrefix.find(normalized)?.let { match ->
			side = sideOf(match.groupValues[1])
			normalized = normalized.removeRange(match.range).trim()
		}
		var variant: Int? = null
		variantSuffix.find(normalized)?.let { match ->
			variant = match.groupValues[1].toIntOrNull()
			normalized = normalized.removeRange(match.range).trim()
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
				name.startsWith("$alias ") || name.startsWith("$alias-") || name.startsWith("${alias}_")
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

