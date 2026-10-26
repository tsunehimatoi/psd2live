package io.github.psd2live.core

import org.umamo.format.art.LayerBlend
import java.text.Normalizer
import java.util.Locale

/**
 * What a layer is within its part, finer than its [SemanticTag]: the iris layer that is a highlight, the lash layer
 * that is the lower lash or the double-eyelid line, the front-hair layer that is a side lock. The rig's modules read it
 * to give each piece its own motion - a highlight drifts against the iris, a lid line fades before the lid closes.
 *
 * Each role has one tag [word] (the layer's tags hold it beside its part's words, [LayerTags]) and the [tags] it can
 * refine; a role on another part means nothing and is dropped. [PLAIN] is the user's word that a layer has no role,
 * so the analysis no longer guesses one; it has no tag word.
 */
enum class PartRole(val word: String, val tags: Set<SemanticTag>) {
	HIGHLIGHT("highlight", setOf(SemanticTag.IRIDES, SemanticTag.FRONT_HAIR, SemanticTag.BACK_HAIR, SemanticTag.FACE_DETAIL)),
	PUPIL("pupil", setOf(SemanticTag.IRIDES)),
	LOWER("lower", setOf(SemanticTag.EYELASH)),
	LID("lid", setOf(SemanticTag.EYELASH)),
	SHADOW("shadow", setOf(SemanticTag.EYELASH, SemanticTag.FACE_DETAIL, SemanticTag.FRONT_HAIR, SemanticTag.BACK_HAIR, SemanticTag.NECK,
		SemanticTag.TOPWEAR, SemanticTag.BOTTOMWEAR)),
	BLUSH("blush", setOf(SemanticTag.FACE_DETAIL)),
	SIDE("side", setOf(SemanticTag.FRONT_HAIR, SemanticTag.BACK_HAIR)),
	AHOGE("ahoge", setOf(SemanticTag.FRONT_HAIR, SemanticTag.BACK_HAIR)),
	TAIL("ponytail", setOf(SemanticTag.BACK_HAIR)),
	BRAID("braid", setOf(SemanticTag.FRONT_HAIR, SemanticTag.BACK_HAIR)),
	ANIMAL("animal", setOf(SemanticTag.HEADWEAR, SemanticTag.EARS)),
	RIBBON("ribbon", setOf(SemanticTag.HEADWEAR, SemanticTag.NECKWEAR, SemanticTag.TOPWEAR, SemanticTag.BOTTOMWEAR, SemanticTag.BACK_HAIR)),
	PENDANT("pendant", setOf(SemanticTag.HEADWEAR, SemanticTag.EARWEAR, SemanticTag.NECKWEAR, SemanticTag.TOPWEAR, SemanticTag.BOTTOMWEAR)),
	RIGID("rigid", setOf(SemanticTag.HEADWEAR, SemanticTag.NECKWEAR, SemanticTag.TOPWEAR, SemanticTag.BOTTOMWEAR, SemanticTag.HANDWEAR)),
	SLEEVE("sleeve", setOf(SemanticTag.TOPWEAR, SemanticTag.HANDWEAR)),
	SKIRT("skirt", setOf(SemanticTag.BOTTOMWEAR, SemanticTag.TOPWEAR)),
	PLAIN("", SemanticTag.entries.toSet());

	/** Whether this role can refine a layer of [tag]. */
	fun fits(tag: SemanticTag): Boolean = tag in tags

	companion object {
		/** The roles that are tag words, every one but [PLAIN]. */
		val worded: List<PartRole> = entries.filter { it != PLAIN }
		private val byWord = worded.associateBy { it.word }

		fun ofWord(word: String): PartRole? = byWord[word]
	}
}

/**
 * Guesses each layer's [PartRole] from what the art shows: first its name and folder (Chinese, Japanese, English and
 * Korean words), then where it sits among the layers of its part and how it blends - a small screen or bright layer
 * inside the iris is a highlight, a lash layer below the eye's middle the lower lash. A role the user set wins, and
 * [PartRole.PLAIN] keeps the guess away. Only the rig's modules read roles; a legacy rig ignores them.
 */
object PartRoles {
	private val names: List<Pair<PartRole, List<String>>> = listOf(
		PartRole.PUPIL to listOf("瞳孔", "pupil", "동공"),
		PartRole.HIGHLIGHT to listOf("高光", "高亮", "反光", "光点", "ハイライト", "ﾊｲﾗｲﾄ", "highlight", "shine", "gloss", "sparkle", "glint", "하이라이트", "반사광"),
		PartRole.LOWER to listOf("下睫毛", "下まつ毛", "下まつげ", "下睫", "下眼线", "下まぶた", "lower lash", "lowerlash", "under lash", "bottom lash", "lower eyelash",
			"아랫속눈썹", "아래 속눈썹", "하안검"),
		PartRole.LID to listOf("双眼皮", "雙眼皮", "二重", "眼皮线", "眼睑线", "まぶた線", "double eyelid", "eyelid line", "lid line", "쌍꺼풀", "쌍커풀"),
		PartRole.BLUSH to listOf("腮红", "腮紅", "红晕", "紅暈", "脸红", "頬", "ほほ", "ほお", "チーク", "照れ", "赤面", "blush", "cheek", "볼터치", "홍조", "블러셔"),
		PartRole.SHADOW to listOf("阴影", "陰影", "暗部", "投影", "影", "かげ", "シャドウ", "shadow", "shade", "그림자", "음영"),
		PartRole.AHOGE to listOf("呆毛", "アホ毛", "あほ毛", "あほげ", "ahoge", "cowlick", "바보털", "더듬이"),
		PartRole.SIDE to listOf("侧发", "側髪", "侧髮", "鬓发", "鬓角", "鬢", "横髪", "もみあげ", "サイド", "side hair", "sidehair", "side lock", "옆머리", "구레나룻"),
		PartRole.TAIL to listOf("马尾", "馬尾", "双马尾", "雙馬尾", "ポニーテール", "ポニテ", "ツインテール", "ツインテ", "ponytail", "twintail", "twin tail", "포니테일", "트윈테일", "양갈래"),
		PartRole.BRAID to listOf("辫", "辮", "三つ編み", "みつあみ", "おさげ", "braid", "plait", "땋은", "땋은머리"),
		PartRole.ANIMAL to listOf("猫耳", "兽耳", "獸耳", "獣耳", "狐耳", "兔耳", "うさ耳", "犬耳", "ケモミミ", "けもみみ", "animal ear", "cat ear", "fox ear", "bunny ear",
			"kemomimi", "고양이귀", "동물귀", "여우귀", "토끼귀"),
		PartRole.RIBBON to listOf("蝴蝶结", "蝴蝶結", "丝带", "絲帶", "缎带", "緞帶", "领结", "領結", "飘带", "飄帶", "リボン", "ribbon", "bow", "리본", "나비넥타이"),
		PartRole.PENDANT to listOf("吊坠", "吊墜", "挂件", "掛件", "坠子", "耳坠", "流苏", "流蘇", "铃铛", "鈴鐺", "鈴", "ペンダント", "チャーム", "タッセル", "pendant",
			"charm", "tassel", "bell", "dangle", "펜던트", "방울", "술장식"),
		PartRole.RIGID to listOf("纽扣", "鈕扣", "扣子", "徽章", "胸针", "胸針", "ボタン", "バッジ", "ブローチ", "button", "badge", "brooch", "단추", "배지", "브로치"),
		PartRole.SLEEVE to listOf("袖子", "袖口", "袖", "そで", "sleeve", "cuff", "소매"),
		PartRole.SKIRT to listOf("裙", "スカート", "skirt", "치마", "스커트"),
	)

	private fun normalize(text: String) = Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase(Locale.ROOT)

	/** The role a layer's [name] and its folder [groupPath] name, if one fits [tag]; the layer's own name wins over its folder's. */
	fun fromName(name: String, groupPath: String, tag: SemanticTag): PartRole? {
		fun find(text: String): PartRole? {
			val value = normalize(text)
			return names.firstOrNull { (role, words) -> role.fits(tag) && words.any { it in value } }?.first
		}
		return find(name) ?: groupPath.split('/').asReversed().firstNotNullOfOrNull { if (it.isEmpty()) null else find(it) }
	}

	/**
	 * [layers] with their roles: the user's ([overrides], by source layer ID) where set, else a guess from the name,
	 * else one from the layer's place among its part's layers. Layers whose role does not change keep their instance.
	 */
	fun infer(layers: List<ClassifiedLayer>, overrides: Map<String, LayerClassificationOverride>): List<ClassifiedLayer> {
		val roles = HashMap<ClassifiedLayer, PartRole?>()
		for (layer in layers) {
			val set = overrides[layer.source.id.raw]?.role
			roles[layer] = when {
				set == PartRole.PLAIN -> null
				set != null -> set.takeIf { it.fits(layer.semantic.tag) }
				else -> fromName(layer.source.name, layer.source.groupPath, layer.semantic.tag)
			}
		}
		val unset = layers.filter { overrides[it.source.id.raw]?.role == null && roles[it] == null && it.opaquePixels > 0 }
		for (layer in unset) roles[layer] = fromPlace(layer, layers.filter { it.opaquePixels > 0 }, roles)
		return layers.map { layer ->
			val role = roles[layer]
			if (role == layer.semantic.role) layer else layer.copy(semantic = layer.semantic.copy(role = role))
		}
	}

	/** A role guessed from where [layer] sits among [layers] and how it blends; null when nothing marks it. */
	private fun fromPlace(layer: ClassifiedLayer, layers: List<ClassifiedLayer>, roles: Map<ClassifiedLayer, PartRole?>): PartRole? {
		val tag = layer.semantic.tag
		val blend = layer.source.blend
		val lightening = blend == LayerBlend.Screen || blend == LayerBlend.ColorDodge || blend == LayerBlend.GlowDodge ||
			blend == LayerBlend.Lighten || blend == LayerBlend.Add || blend == LayerBlend.AddGlow || blend == LayerBlend.LighterColor
		val darkening = blend == LayerBlend.Multiply || blend == LayerBlend.LinearBurn || blend == LayerBlend.ColorBurn || blend == LayerBlend.Darken
		fun sameSide(other: ClassifiedLayer) = other !== layer && other.semantic.side == layer.semantic.side
		fun area(bounds: Bounds) = bounds.width.coerceAtLeast(0f) * bounds.height.coerceAtLeast(0f)
		fun inside(inner: Bounds, outer: Bounds, slack: Float) = inner.left >= outer.left - slack && inner.right <= outer.right + slack &&
			inner.top >= outer.top - slack && inner.bottom <= outer.bottom + slack
		return when (tag) {
			SemanticTag.IRIDES -> {
				// The iris is the largest iris layer of its side; a smaller one inside it that lightens or is bright is a highlight.
				val iris = layers.filter { (it === layer || sameSide(it)) && it.semantic.tag == SemanticTag.IRIDES && roles[it] == null }
					.maxByOrNull { area(it.bounds) } ?: return null
				if (iris === layer) return null
				val small = area(layer.bounds) < area(iris.bounds) * 0.45f
				val within = inside(layer.bounds, iris.bounds, iris.bounds.width * 0.15f)
				if (!small || !within) null
				else if (lightening || meanLuminance(layer) > 0.82f) PartRole.HIGHLIGHT
				else if (meanLuminance(layer) < 0.18f && area(layer.bounds) < area(iris.bounds) * 0.3f) PartRole.PUPIL
				else null
			}
			SemanticTag.EYELASH -> {
				if (darkening) return PartRole.SHADOW
				val white = layers.filter { (it.semantic.side == layer.semantic.side) && it.semantic.tag == SemanticTag.EYEWHITE }
					.maxByOrNull { area(it.bounds) }?.bounds
				val lashes = layers.filter { (it === layer || sameSide(it)) && it.semantic.tag == SemanticTag.EYELASH && roles[it] == null }
				val upper = lashes.maxByOrNull { area(it.bounds) } ?: return null
				if (upper === layer) return null
				val eye = white ?: upper.bounds
				when {
					// Below the eye's middle: the lower lash.
					layer.centroidY > eye.centerY + eye.height * 0.1f -> PartRole.LOWER
					// A thin line wholly above the upper lash's top edge: the double-eyelid line.
					layer.bounds.bottom <= upper.bounds.top + upper.bounds.height * 0.35f && layer.bounds.height < upper.bounds.height * 0.6f -> PartRole.LID
					else -> null
				}
			}
			SemanticTag.FACE_DETAIL -> when {
				darkening -> PartRole.SHADOW
				lightening -> PartRole.HIGHLIGHT
				else -> null
			}
			SemanticTag.FRONT_HAIR, SemanticTag.BACK_HAIR -> when {
				darkening -> PartRole.SHADOW
				lightening -> PartRole.HIGHLIGHT
				tag == SemanticTag.FRONT_HAIR -> {
					// Front hair hanging beside the face, well past its middle, is a side lock.
					val face = layers.filter { it.semantic.tag == SemanticTag.FACE }.map { it.bounds }.reduceOrNull(Bounds::union) ?: return null
					val outside = layer.bounds.right < face.left + face.width * 0.3f || layer.bounds.left > face.right - face.width * 0.3f
					if (outside && layer.bounds.bottom > face.top + face.height * 0.7f && layer.bounds.width < face.width * 0.45f) PartRole.SIDE else null
				}
				else -> null
			}
			SemanticTag.BOTTOMWEAR -> if (darkening) PartRole.SHADOW else if (layer.semantic.side == io.github.psd2live.core.Side.NONE && skirtShaped(layer)) PartRole.SKIRT else null
			SemanticTag.NECK, SemanticTag.TOPWEAR -> if (darkening) PartRole.SHADOW else null
			else -> null
		}
	}

	/**
	 * Whether a bottom is a skirt: its hem at least about as wide as its waist and whole across its middle, where
	 * trousers part between the legs.
	 */
	private fun skirtShaped(layer: ClassifiedLayer): Boolean {
		val raster = layer.source.raster
		if (raster.width < 8 || raster.height < 8) return false
		fun opaque(x: Int, y: Int) = (raster.rgba[(y * raster.width + x) * 4 + 3].toInt() and 0xff) >= 128
		fun band(from: Float, to: Float): Pair<Float, Float> {
			var widths = 0f; var rows = 0; var middle = 0; var middleRows = 0
			for (y in (raster.height * from).toInt() until (raster.height * to).toInt()) {
				val xs = (0 until raster.width).filter { opaque(it, y) }
				if (xs.isEmpty()) continue
				widths += (xs.last() - xs.first()).toFloat(); rows++
				val mid = (xs.first() + xs.last()) / 2
				val reach = ((xs.last() - xs.first()) / 6).coerceAtLeast(1)
				middle += (mid - reach..mid + reach).count { opaque(it.coerceIn(0, raster.width - 1), y) }; middleRows += 2 * reach + 1
			}
			return (if (rows == 0) 0f else widths / rows) to (if (middleRows == 0) 0f else middle.toFloat() / middleRows)
		}
		val (waist, _) = band(0.05f, 0.15f)
		val (hem, whole) = band(0.8f, 0.9f)
		return waist > 0f && hem >= waist * 0.9f && whole > 0.7f
	}

	/** The mean luminance 0..1 of a layer's opaque pixels, sampled on a coarse grid. */
	private fun meanLuminance(layer: ClassifiedLayer): Float {
		val raster = layer.source.raster
		val rgba = raster.rgba
		val step = maxOf(1, maxOf(raster.width, raster.height) / 48)
		var sum = 0.0
		var count = 0
		for (y in 0 until raster.height step step) for (x in 0 until raster.width step step) {
			val i = (y * raster.width + x) * 4
			if ((rgba[i + 3].toInt() and 0xff) < 128) continue
			sum += (0.2126 * (rgba[i].toInt() and 0xff) + 0.7152 * (rgba[i + 1].toInt() and 0xff) + 0.0722 * (rgba[i + 2].toInt() and 0xff)) / 255.0
			count++
		}
		return if (count == 0) 0f else (sum / count).toFloat()
	}
}
