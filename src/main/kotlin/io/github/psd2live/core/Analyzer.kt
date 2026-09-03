package io.github.psd2live.core

import io.github.psd2live.i18n.tr
import org.umamo.format.art.SourceArt
import kotlin.math.max

object CharacterAnalyzer {
	fun analyze(source: SourceArt, config: PipelineConfig): PipelineAnalysis {
		val initiallyClassified = source.layers
			.filter { it.raster.width > 0 && it.raster.height > 0 }
			.map { layer ->
				LayerClassifier.classify(layer, config.alphaThreshold).withOverride(
					config.layerOverrides[layer.id.raw],
				)
			}
		val preliminaryFaceCenter = initiallyClassified
			.filter { it.semantic.tag == SemanticTag.FACE && it.opaquePixels > 0 }
			.maxByOrNull { it.opaquePixels }
			?.centroidX
			?: source.widthPx * 0.5f
		val layers = initiallyClassified.flatMap { original ->
			ComponentSplitter.split(original, preliminaryFaceCenter, config.alphaThreshold).map { component ->
				val directOverride = config.layerOverrides[component.source.id.raw]
				val inheritedOverride = config.layerOverrides[original.source.id.raw]
				when {
					directOverride != null -> component.withOverride(directOverride)
					inheritedOverride != null && component.source.id != original.source.id ->
						component.withOverride(inheritedOverride, preserveSide = true)
					else -> component.withOverride(inheritedOverride)
				}
			}
		}
		val warnings = mutableListOf<String>()
		val nonEmpty = layers.filter { it.opaquePixels > 0 }
		require(nonEmpty.isNotEmpty()) { tr("error.psdNoVisibleLayers") }

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
		if (recognized < 4) warnings += tr("warning.fewSemanticLayers", recognized)
		if (explicitFace.isEmpty()) warnings += tr("warning.missingFace")
		if (layers.none { it.semantic.tag in EYE_TAGS }) warnings += tr("warning.missingEyes")
		if (layers.none { it.semantic.tag in MOUTH_BASE_TAGS }) warnings += tr("warning.missingMouth")
		val duplicateBaseNames = layers.groupBy { it.semantic.normalizedName }.filterValues { it.size > 1 }.keys
		if (duplicateBaseNames.isNotEmpty()) warnings += tr("warning.duplicateLayers", duplicateBaseNames.take(6).joinToString())
		val unknown = layers.filter { it.semantic.type == LayerType.PRESET && it.semantic.tag == SemanticTag.UNKNOWN }

		return PipelineAnalysis(source, layers, anchors, warnings, PreviewRenderer.composite(source))
	}

	private fun union(bounds: List<Bounds>): Bounds = bounds.reduce(Bounds::union)

	private fun ClassifiedLayer.withOverride(
		override: LayerClassificationOverride?,
		preserveSide: Boolean = false,
	): ClassifiedLayer {
		if (override == null) return this
		return copy(
			semantic = semantic.copy(
				tag = override.tag,
				side = if (preserveSide) semantic.side else override.side,
				confidence = 1f,
				type = override.type,
				parameter = override.parameter,
				switchId = override.switchId,
			),
		)
	}

	val EYE_TAGS = setOf(SemanticTag.IRIDES, SemanticTag.EYEBROW, SemanticTag.EYEWHITE, SemanticTag.EYELASH, SemanticTag.EYE_CLOSE)
	val MOUTH_COMPONENT_TAGS = setOf(
		SemanticTag.TOOTH_T,
		SemanticTag.TOOTH_B,
		SemanticTag.TONGUE,
	)
	val MOUTH_BASE_TAGS = setOf(SemanticTag.MOUTH, SemanticTag.MOUTH_OPEN, SemanticTag.MOUTH_CLOSE)
	val MOUTH_TAGS = MOUTH_BASE_TAGS + MOUTH_COMPONENT_TAGS
}
