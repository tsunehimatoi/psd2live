package io.github.psd2live.core

import io.github.psd2live.i18n.tr
import io.github.psd2live.core.quality.*
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.umamo.format.art.SourceArt
import kotlin.math.max

object CharacterAnalyzer {
	internal fun classify(layer: org.umamo.format.art.SourceLayer, config: PipelineConfig): ClassifiedLayer =
		LayerClassifier.classify(layer, config.alphaThreshold).withOverride(config.layerOverrides[layer.id.raw])

	/** Recreate retained legacy component identities without building a model or discarding deleted pixels. */
	internal fun expandLayer(original: ClassifiedLayer, config: PipelineConfig, unitScale: Float = 1f): List<ClassifiedLayer> {
		if (!preserveLegacySplit(original.source.id.raw, config)) return listOf(original)
		return ComponentSplitter.split(original, config.meshSpacing.toFloat(), config.alphaThreshold, unitScale).map { component ->
			val override = config.layerOverrides[component.source.id.raw] ?: config.layerOverrides[original.source.id.raw]
			component.withOverride(override, preserveSide = component.source.id != original.source.id)
		}
	}

	fun analyze(source: SourceArt, config: PipelineConfig): PipelineAnalysis {
		// A depth copy retains its original texture rectangle even when completely erased: its
		// welded mesh must sample transparent pixels, never a neighbour's tile after a repack.
		val depthLayerIds = config.rigEdits.authoringJournal.filter {
			it["op"]?.jsonPrimitive?.contentOrNull == DepthSplit.OP
		}.mapNotNullTo(HashSet()) { it["layer_id"]?.jsonPrimitive?.contentOrNull }
		val initiallyClassified = source.layers
			.filter { it.raster.width > 0 && it.raster.height > 0 && it.id.raw !in config.deletedLayerIds }
			.map { layer ->
				classify(layer, config)
			}.map { if (it.source.id.raw in depthLayerIds && it.opaquePixels == 0) it.copy(opaquePixels = 1) else it }
		// Fresh layers stay intact until the UI offers a named split. Old projects may still
		// reference generated :r/:l IDs, so retain those identities when they carry edits.
		val unitScale = MeshResolution.unitScale(config, source)
		val layers = initiallyClassified.flatMap { expandLayer(it, config, unitScale) }
			.filter { it.source.id.raw !in config.deletedLayerIds }
		val findings = source.warnings.map { QualityFinding.message(QualityRule.SOURCE_IMPORT_NOTICE, "source", it) }.toMutableList()
		val nonEmpty = layers.filter { it.opaquePixels > 0 }
		require(nonEmpty.isNotEmpty()) { tr("error.psdNoVisibleLayers") }

		val anchors = anchorsFor(nonEmpty)

		val recognized = layers.count { it.semantic.tag != SemanticTag.UNKNOWN }
		if (recognized < 4) findings += QualityFinding.message(QualityRule.GENERATION_FEW_SEMANTIC_LAYERS, "source", tr("warning.fewSemanticLayers", recognized))
		if (nonEmpty.none { it.semantic.tag == SemanticTag.FACE }) findings += QualityFinding.message(QualityRule.GENERATION_MISSING_FACE, "source", tr("warning.missingFace"))
		if (layers.none { it.semantic.tag in EYE_TAGS }) findings += QualityFinding.message(QualityRule.GENERATION_MISSING_EYES, "source", tr("warning.missingEyes"))
		if (layers.none { it.semantic.tag in MOUTH_BASE_TAGS }) findings += QualityFinding.message(QualityRule.GENERATION_MISSING_MOUTH, "source", tr("warning.missingMouth"))
		val duplicateBaseNames = layers.groupBy { it.semantic.normalizedName }.filterValues { it.size > 1 }.keys
		if (duplicateBaseNames.isNotEmpty()) findings += QualityFinding.message(QualityRule.GENERATION_DUPLICATE_NAMES, "source", tr("warning.duplicateLayers", duplicateBaseNames.take(6).joinToString()))
		val unknown = layers.filter { it.semantic.type == LayerType.PRESET && it.semantic.tag == SemanticTag.UNKNOWN }

		val calibrationIds = config.rigEdits.calibrationLayerIds
        val calibration = if (calibrationIds.isEmpty()) null else {
            val baseline = object : SourceArt {
                override val widthPx = source.widthPx
                override val heightPx = source.heightPx
                override val groups = source.groups
                override val layers = source.layers.filter { it.id.raw in calibrationIds }
            }
            require(baseline.layers.size == calibrationIds.size) { "Registration calibration source layers are missing" }
            analyze(baseline, config.copy(deletedLayerIds = emptySet(), rigEdits = config.rigEdits.copy(calibrationLayerIds = emptySet())))
        }
        return PipelineAnalysis(source, layers, calibration?.anchors ?: anchors, findings.filter { it.rule.severity != QualitySeverity.INFO }.map { it.message }, PreviewRenderer.composite(source), calibration, findings)
	}

	/** Refit rig anchors after the actual renderable mesh footprints replace pixel alpha boxes. */
	internal fun anchorsFor(layers: List<ClassifiedLayer>): RigAnchors {
		val nonEmpty = layers.filter { it.opaquePixels > 0 }
		require(nonEmpty.isNotEmpty())
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
		return RigAnchors(
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

	}

	private fun union(bounds: List<Bounds>): Bounds = bounds.reduce(Bounds::union)

	private fun preserveLegacySplit(sourceId: String, config: PipelineConfig): Boolean {
		val ids = setOf("$sourceId:r", "$sourceId:l")
		val edits = config.rigEdits
		val tracked = config.layerOverrides.keys + config.parentOverrides.keys +
			config.drawOrderOverrides.keys + config.meshOverrides.keys + config.deletedLayerIds +
			config.layerVisibility.keys + edits.assetLayers.keys + edits.calibrationLayerIds
		if (ids.any { it in tracked }) return true
		if (edits.keyformSetEdits.any { it.target.id in ids } ||
			edits.keyformDeleteEdits.any { it.target.id in ids } ||
			edits.keyformCopyEdits.any { it.sourceTarget.id in ids || it.destinationTarget.id in ids } ||
			edits.warpEdits.any { warp -> warp.meshIds.any { it in ids } }) return true
		return (edits.structureEdits + edits.authoringJournal).any { command ->
			ids.any { id -> command.toString().contains("\"$id\"") }
		}
	}

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
