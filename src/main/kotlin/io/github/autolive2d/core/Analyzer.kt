package io.github.autolive2d.core

import org.umamo.format.art.SourceArt
import kotlin.math.max

object CharacterAnalyzer {
	fun analyze(source: SourceArt, config: PipelineConfig): PipelineAnalysis {
		val initiallyClassified = source.layers
			.filter { it.raster.width > 0 && it.raster.height > 0 }
			.map { LayerClassifier.classify(it, config.alphaThreshold) }
		val preliminaryFaceCenter = initiallyClassified
			.filter { it.semantic.tag == SemanticTag.FACE && it.opaquePixels > 0 }
			.maxByOrNull { it.opaquePixels }
			?.centroidX
			?: source.widthPx * 0.5f
		val layers = initiallyClassified.flatMap { layer ->
			ComponentSplitter.split(layer, preliminaryFaceCenter, config.alphaThreshold)
		}
		val warnings = mutableListOf<String>()
		val nonEmpty = layers.filter { it.opaquePixels > 0 }
		require(nonEmpty.isNotEmpty()) { "PSD 没有可见像素图层" }

		val character = union(nonEmpty.map { it.bounds })
		val explicitFace = nonEmpty.filter { it.semantic.tag == SemanticTag.FACE }
		val headLayers = nonEmpty.filter { it.semantic.tag.group == LayerGroup.HEAD }
		val face = when {
			explicitFace.isNotEmpty() -> union(explicitFace.map { it.bounds })
			headLayers.isNotEmpty() -> union(headLayers.map { it.bounds }).let { guessed ->
				Bounds(guessed.left, guessed.top, guessed.right, minOf(guessed.bottom, character.top + character.height * 0.52f))
			}
			else -> Bounds(character.left, character.top, character.right, character.top + character.height * 0.42f)
		}
		val bodyLayers = nonEmpty.filter { it.semantic.tag.group == LayerGroup.BODY }
		val body = if (bodyLayers.isNotEmpty()) {
			union(bodyLayers.map { it.bounds })
		} else {
			Bounds(character.left, max(face.bottom, character.top + character.height * 0.35f), character.right, character.bottom)
		}
		val topwear = nonEmpty.filter { it.semantic.tag == SemanticTag.TOPWEAR }.map { it.bounds }.takeIf { it.isNotEmpty() }?.let(::union)
		val bottomwear = nonEmpty.filter { it.semantic.tag == SemanticTag.BOTTOMWEAR }.map { it.bounds }.takeIf { it.isNotEmpty() }?.let(::union)
		val anchors = RigAnchors(
			character = character.expanded(0.015f),
			face = face.expanded(0.04f),
			body = body.expanded(0.025f),
			faceCenterX = explicitFace.firstOrNull()?.centroidX ?: face.centerX,
			faceCenterY = explicitFace.firstOrNull()?.centroidY ?: face.centerY,
			chinX = explicitFace.firstOrNull()?.centroidX ?: face.centerX,
			chinY = explicitFace.maxOfOrNull { it.bounds.bottom } ?: face.bottom,
			shoulderY = topwear?.let { it.top + it.height * 0.12f } ?: max(face.bottom, body.top),
			hipY = bottomwear?.let { it.top + it.height * 0.2f } ?: body.top + body.height * 0.62f,
		)

		val recognized = layers.count { it.semantic.tag != SemanticTag.UNKNOWN }
		if (recognized < 4) warnings += "仅识别到 $recognized 个 See-Through 语义图层；未知图层会按位置归入头部或身体。"
		if (explicitFace.isEmpty()) warnings += "缺少 face 图层：面部范围由头部图层包围盒估算。"
		if (layers.none { it.semantic.tag in EYE_TAGS }) warnings += "缺少眼睛语义图层：眨眼与视线参数不会绑定到任何画元。"
		if (layers.none { it.semantic.tag in MOUTH_TAGS }) warnings += "缺少 mouth/mouth_open/mouth_close：口型参数不会绑定到任何画元。"
		val duplicateBaseNames = layers.groupBy { it.semantic.normalizedName }.filterValues { it.size > 1 }.keys
		if (duplicateBaseNames.isNotEmpty()) warnings += "检测到同名图层：${duplicateBaseNames.take(6).joinToString()}；已通过稳定图层 ID 区分。"
		val unknown = layers.filter { it.semantic.tag == SemanticTag.UNKNOWN }
		if (unknown.isNotEmpty()) warnings += "${unknown.size} 个未知图层将保留并自动归组：${unknown.take(8).joinToString { it.source.name }}"

		return PipelineAnalysis(source, layers, anchors, warnings, PreviewRenderer.composite(source))
	}

	private fun union(bounds: List<Bounds>): Bounds = bounds.reduce(Bounds::union)

	val EYE_TAGS = setOf(SemanticTag.IRIDES, SemanticTag.EYEBROW, SemanticTag.EYEWHITE, SemanticTag.EYELASH, SemanticTag.EYE_CLOSE)
	val MOUTH_TAGS = setOf(SemanticTag.MOUTH, SemanticTag.MOUTH_OPEN, SemanticTag.MOUTH_CLOSE)
}
