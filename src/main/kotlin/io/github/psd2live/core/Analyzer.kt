package io.github.psd2live.core

import io.github.psd2live.i18n.tr
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.umamo.format.art.SourceArt
import kotlin.math.max

object CharacterAnalyzer {
	/** [layer] classified at canvas resolution ([CanvasDensity]): a dense layer is analysed through its canvas view. */
	internal fun classify(layer: org.umamo.format.art.SourceLayer, config: PipelineConfig): ClassifiedLayer =
		LayerClassifier.classify(CanvasDensity.canvasLayer(layer), config.alphaThreshold).withOverride(config.layerOverrides[layer.id.raw])

	/** Recreate retained legacy component identities without building a model or discarding deleted pixels. */
	internal fun expandLayer(original: ClassifiedLayer, config: PipelineConfig, unitScale: Float = 1f,
	                         references: LegacySplitReferences = LegacySplitReferences(config)): List<ClassifiedLayer> {
		if (!references.preserves(original.source.id.raw)) return listOf(original)
		return ComponentSplitter.split(original, config.meshSpacing.toFloat(), config.alphaThreshold, unitScale).map { component ->
			val override = config.layerOverrides[component.source.id.raw] ?: config.layerOverrides[original.source.id.raw]
			component.withOverride(override, preserveSide = component.source.id != original.source.id)
		}
	}

	/**
	 * [source] classified and anchored under [config]. [preview] makes the analysis' composite; a caller that
	 * replaces it (an analysis of a generation input that stands for the current art) passes its own.
	 */
	fun analyze(source: SourceArt, config: PipelineConfig,
	            preview: () -> java.awt.image.BufferedImage = { PreviewRenderer.composite(source) }): PipelineAnalysis {
		// A depth copy retains its original texture rectangle even when completely erased: its
		// welded mesh must sample transparent pixels, never a neighbour's tile after a repack.
		val depthLayerIds = config.rigEdits.authoringJournal.filter {
			it["op"]?.jsonPrimitive?.contentOrNull == DepthSplit.OP
		}.mapNotNullTo(HashSet()) { it["layer_id"]?.jsonPrimitive?.contentOrNull }
		val classifyStart = System.nanoTime()
		// Layers classify independently (dense ones resample to canvas resolution first); the list keeps source order.
		val initiallyClassified = source.layers
			.filter { it.raster.width > 0 && it.raster.height > 0 && it.id.raw !in config.deletedLayerIds }
			.parallelStream().map { layer -> classify(layer, config) }.toList()
			.map { if (it.source.id.raw in depthLayerIds && it.opaquePixels == 0) it.copy(opaquePixels = 1) else it }
		// Fresh layers stay intact until the UI offers a named split. Old projects may still
		// reference generated :r/:l IDs, so retain those identities when they carry edits.
		val unitScale = MeshResolution.unitScale(config, source)
		val references = LegacySplitReferences(config)
		val layers = PartRoles.infer(initiallyClassified.flatMap { expandLayer(it, config, unitScale, references) }
			.filter { it.source.id.raw !in config.deletedLayerIds }, config.layerOverrides)
		if (RigBuildProfile.recording) RigBuildProfile.add("analyze: classify layers", System.nanoTime() - classifyStart)
		val warnings = source.warnings.toMutableList()
		val nonEmpty = layers.filter { it.opaquePixels > 0 }
		require(nonEmpty.isNotEmpty() || config.allowEmptyArt) { tr("error.psdNoVisibleLayers") }

		val anchors = if (nonEmpty.isEmpty()) canvasAnchors(source) else anchorsFor(nonEmpty)

		val recognized = layers.count { it.semantic.tag != SemanticTag.UNKNOWN }
		if (recognized < 4) warnings += tr("warning.fewSemanticLayers", recognized)
		if (nonEmpty.none { it.semantic.tag == SemanticTag.FACE }) warnings += tr("warning.missingFace")
		if (layers.none { it.semantic.tag in EYE_TAGS }) warnings += tr("warning.missingEyes")
		if (layers.none { it.semantic.tag in MOUTH_BASE_TAGS }) warnings += tr("warning.missingMouth")
		val duplicateBaseNames = layers.groupBy { it.semantic.normalizedName }.filterValues { it.size > 1 }.keys
		if (duplicateBaseNames.isNotEmpty()) warnings += tr("warning.duplicateLayers", duplicateBaseNames.take(6).joinToString())
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
        return PipelineAnalysis(source, layers, calibration?.anchors ?: anchors, warnings,
            RigBuildProfile.stage("analyze: composite preview") { preview() }, calibration)
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

	/** Anchors of an empty canvas, for art without a visible pixel: the face above, the body below its middle. */
	private fun canvasAnchors(source: SourceArt): RigAnchors {
		val w = source.widthPx.toFloat().coerceAtLeast(1f); val h = source.heightPx.toFloat().coerceAtLeast(1f)
		val character = Bounds(0f, 0f, w, h)
		val face = Bounds(w * 0.3f, 0f, w * 0.7f, h * 0.42f)
		return RigAnchors(character, face, Bounds(0f, h * 0.35f, w, h), face.centerX, face.centerY, face.centerX, face.bottom,
			h * 0.45f, h * 0.62f)
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

/**
 * Which generated `:r`/`:l` component IDs of old projects [config] still references, so their split is kept: as
 * tracked overrides, as targets of static edits, or quoted (`"<id>"`) anywhere in the structure edits or the
 * authoring journal. Built once per analysis; each journal entry's quoted strings are read once per entry object.
 */
internal class LegacySplitReferences(config: PipelineConfig) {
	private val edits = config.rigEdits
	private val tracked = config.layerOverrides.keys + config.parentOverrides.keys +
		config.drawOrderOverrides.keys + config.meshOverrides.keys + config.deletedLayerIds +
		config.layerVisibility.keys + edits.assetLayers.keys + edits.calibrationLayerIds
	private val commands = edits.structureEdits + edits.authoringJournal
	/** Every text between two adjacent quotes in the commands that names a component (ends in `:r` or `:l`). */
	private val quoted: Set<String> by lazy { commands.flatMapTo(HashSet()) { quotedComponents(it) } }

	fun preserves(sourceId: String): Boolean {
		val ids = setOf("$sourceId:r", "$sourceId:l")
		if (ids.any { it in tracked }) return true
		if (edits.keyformSetEdits.any { it.target.id in ids } ||
			edits.keyformDeleteEdits.any { it.target.id in ids } ||
			edits.keyformCopyEdits.any { it.sourceTarget.id in ids || it.destinationTarget.id in ids } ||
			edits.warpEdits.any { warp -> warp.meshIds.any { it in ids } }) return true
		return ids.any { id ->
			// A command's text holds "<id>" exactly when some quote is followed by the id and the next quote, so an id
			// without quotes of its own is among the texts between adjacent quotes.
			if ('"' !in id) id in quoted else commands.any { it.toString().contains("\"$id\"") }
		}
	}

	private companion object {
		private val cache = io.github.psd2live.project.IdentityWeakCache<kotlinx.serialization.json.JsonObject, Set<String>>()

		fun quotedComponents(command: kotlinx.serialization.json.JsonObject): Set<String> = cache.getOrPut(command) {
			val text = command.toString()
			val found = HashSet<String>()
			var open = text.indexOf('"')
			while (open >= 0) {
				val close = text.indexOf('"', open + 1)
				if (close < 0) break
				if (close - open > 2 && text[close - 2] == ':' && (text[close - 1] == 'r' || text[close - 1] == 'l')) found += text.substring(open + 1, close)
				open = close
			}
			found
		}
	}
}
