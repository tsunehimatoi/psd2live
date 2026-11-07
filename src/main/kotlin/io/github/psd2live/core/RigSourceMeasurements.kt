package io.github.psd2live.core

import kotlin.math.max

/** Mesh footprints shared by the generator and source-aware binding capture. */
internal object RigSourceMeasurements {
	val faceTags = setOf(
		SemanticTag.FACE,
		SemanticTag.FACE_DETAIL,
		SemanticTag.IRIDES,
		SemanticTag.EYEBROW,
		SemanticTag.EYEWHITE,
		SemanticTag.EYELASH,
		SemanticTag.EYE_CLOSE,
		SemanticTag.EYEWEAR,
		SemanticTag.EARS,
		SemanticTag.EARWEAR,
		SemanticTag.NOSE,
		SemanticTag.MOUTH,
		SemanticTag.MOUTH_OPEN,
		SemanticTag.MOUTH_CLOSE,
		SemanticTag.TOOTH_T,
		SemanticTag.TOOTH_B,
		SemanticTag.TONGUE,
	)

	/** Use the same generated triangles that will render the texture to place automatic deformers. */
	fun meshFramedAnalysis(
		analysis: PipelineAnalysis,
		config: PipelineConfig,
		meshCache: PreviewMeshCache?,
		resolution: PrimitiveResolution = PrimitiveResolution.Inactive,
	): PipelineAnalysis {
        // Binding modules share the same source geometry. Key by the actual meshing inputs,
        // rather than the complete recipe which also contains unrelated motion/physics choices.
        val stages = meshCache?.stages?.takeIf { RigStageCache.enabled }
        if (stages != null && analysis.calibration == null) {
            val key = listOf(analysis.source, analysis.layers, analysis.anchors, analysis.warnings,
                config.meshTrace, MeshResolution.unitScale(config, analysis.source),
                config.alphaThreshold, RigMeshSeed.layers(config.rigEdits), resolution.partByLayer,
                analysis.layers.map { meshSettings(it, config).first })
            return stages.get("framed analysis", 16, key) {
                framedAnalysis(analysis, config, meshCache, resolution)
            }
        }
        return framedAnalysis(analysis, config, meshCache, resolution)
    }

    private fun framedAnalysis(analysis: PipelineAnalysis, config: PipelineConfig, meshCache: PreviewMeshCache?,
                               resolution: PrimitiveResolution): PipelineAnalysis {
		val layers = framedLayers(analysis.layers, analysis, config, meshCache, resolution)
		val calibration = analysis.calibration?.let { meshFramedAnalysis(it, config, meshCache) }
		return analysis.copy(
			layers = layers,
			anchors = calibration?.anchors ?: if (layers.none { it.opaquePixels > 0 }) analysis.anchors else CharacterAnalyzer.anchorsFor(layers),
			calibration = calibration,
		)
	}

	/** [layers] with their mesh footprints as bounds and centroid; a version 2 part's footprint is its pinned mesh. */
	fun framedLayers(
		layers: List<ClassifiedLayer>, analysis: PipelineAnalysis, config: PipelineConfig, meshCache: PreviewMeshCache?,
		resolution: PrimitiveResolution,
	): List<ClassifiedLayer> {
		val unitScale = MeshResolution.unitScale(config, analysis.source)
		val seeds = RigMeshSeed.layers(config.rigEdits)
		// Generate the layers' meshes side by side first; the footprints below, and the mesh stage, read them cached.
		meshCache?.prefetch(layers.mapNotNull { layer ->
			if (layer.source.id.raw in seeds || resolution.partByLayer[layer.source.id.raw] != null || layer.opaquePixels <= 0 ||
				layer.semantic.tag in setOf(SemanticTag.TOOTH_T, SemanticTag.TOOTH_B)) null
			else MeshResolution.input(layer.source, config.meshTrace, unitScale) to meshSettings(layer, config).first
		}, config.alphaThreshold)
		return layers.map { layer ->
			val pinned = resolution.partByLayer[layer.source.id.raw] ?: seeds[layer.source.id.raw]
			val footprint = (if (pinned != null) pinnedFootprint(pinned) else meshFootprint(layer, config, meshCache, unitScale)) ?: return@map layer
			layer.copy(
				bounds = footprint.bounds,
				centroidX = footprint.centerX,
				centroidY = footprint.centerY,
			)
		}
	}

	/** The footprint of a part's recorded mesh: the canvas positions its triangles cover, centred on their area. */
	private fun pinnedFootprint(part: ResolvedPart): MeshFootprint? {
		if (part.triangles.isEmpty()) return null
		val p = part.positions
		var left = Float.POSITIVE_INFINITY; var top = Float.POSITIVE_INFINITY
		var right = Float.NEGATIVE_INFINITY; var bottom = Float.NEGATIVE_INFINITY
		for (vertex in part.triangles) {
			left = minOf(left, p[vertex * 2]); top = minOf(top, p[vertex * 2 + 1])
			right = maxOf(right, p[vertex * 2]); bottom = maxOf(bottom, p[vertex * 2 + 1])
		}
		val bounds = Bounds(left, top, right, bottom)
		var area = 0.0; var weightedX = 0.0; var weightedY = 0.0
		for (offset in part.triangles.indices step 3) {
			val a = part.triangles[offset] * 2; val b = part.triangles[offset + 1] * 2; val c = part.triangles[offset + 2] * 2
			val triangle = kotlin.math.abs((p[b] - p[a]).toDouble() * (p[c + 1] - p[a + 1]) - (p[b + 1] - p[a + 1]).toDouble() * (p[c] - p[a]))
			area += triangle
			weightedX += triangle * (p[a] + p[b] + p[c]) / 3.0
			weightedY += triangle * (p[a + 1] + p[b + 1] + p[c + 1]) / 3.0
		}
		return if (area > 1e-10) MeshFootprint(bounds, (weightedX / area).toFloat(), (weightedY / area).toFloat())
		else MeshFootprint(bounds, bounds.centerX, bounds.centerY)
	}

	private data class MeshFootprint(val bounds: Bounds, val centerX: Float, val centerY: Float)

	private fun meshFootprint(
		layer: ClassifiedLayer, config: PipelineConfig, meshCache: PreviewMeshCache?, unitScale: Float,
	): MeshFootprint? {
		if (layer.opaquePixels <= 0) return null
		val source = layer.source
		val width = source.raster.width
		val height = source.raster.height
		if (width <= 0 || height <= 0) return null
		fun rectangle(): MeshFootprint {
			val bounds = Bounds(source.bounds.left.toFloat(), source.bounds.top.toFloat(),
				(source.bounds.left + width).toFloat(), (source.bounds.top + height).toFloat())
			return MeshFootprint(bounds, bounds.centerX, bounds.centerY)
		}
		// Tooth art deliberately uses a rectangular mesh so its mouth mask can reveal every tooth.
		if (layer.semantic.tag in setOf(SemanticTag.TOOTH_T, SemanticTag.TOOTH_B)) {
			return rectangle()
		}
		val (settings, _) = meshSettings(layer, config)
		val input = MeshResolution.input(source, config.meshTrace, unitScale)
		fun generate() = adaptiveFootprint(MeshResolution.mesh(input, config.alphaThreshold, settings, meshCache), source, ::rectangle)
		val stages = meshCache?.stages?.takeIf { RigStageCache.enabled } ?: return generate()
		val key = FootprintKey(width, height, input.key, source.bounds.left, source.bounds.top,
			config.alphaThreshold, settings, unitScale)
		return stages.get(RigStageCache.FOOTPRINT, 512, key) { Footprint(generate()) }.value
	}

	/** A layer's mesh footprint inputs: its pixels, where they sit, and the mesh settings and scale they mesh at. */
	private data class FootprintKey(val width: Int, val height: Int, val input: List<Any>, val left: Int, val top: Int,
	                                val alphaThreshold: Int, val settings: MeshSettings, val unitScale: Float)

	/** A kept footprint, null for a layer whose mesh has no triangles. */
	private class Footprint(val value: MeshFootprint?)

	private fun adaptiveFootprint(adaptive: AdaptiveMeshGenerator.Result?, source: org.umamo.format.art.SourceLayer,
	                              rectangle: () -> MeshFootprint): MeshFootprint? {
		// The renderer falls back to a rectangular mesh when adaptive triangulation fails.
		if (adaptive == null) return rectangle()
		if (adaptive.indices.isEmpty()) return null
		var left = Float.POSITIVE_INFINITY
		var top = Float.POSITIVE_INFINITY
		var right = Float.NEGATIVE_INFINITY
		var bottom = Float.NEGATIVE_INFINITY
		for (vertex in adaptive.indices) {
			val x = adaptive.positions[vertex * 2] + source.bounds.left
			val y = adaptive.positions[vertex * 2 + 1] + source.bounds.top
			left = minOf(left, x); top = minOf(top, y)
			right = maxOf(right, x); bottom = maxOf(bottom, y)
		}
		val bounds = Bounds(left, top, right, bottom)
		var area = 0.0
		var weightedX = 0.0
		var weightedY = 0.0
		for (offset in adaptive.indices.indices step 3) {
			val a = adaptive.indices[offset] * 2
			val b = adaptive.indices[offset + 1] * 2
			val c = adaptive.indices[offset + 2] * 2
			val ax = adaptive.positions[a].toDouble(); val ay = adaptive.positions[a + 1].toDouble()
			val bx = adaptive.positions[b].toDouble(); val by = adaptive.positions[b + 1].toDouble()
			val cx = adaptive.positions[c].toDouble(); val cy = adaptive.positions[c + 1].toDouble()
			val triangleArea = kotlin.math.abs((bx - ax) * (cy - ay) - (by - ay) * (cx - ax))
			area += triangleArea
			weightedX += triangleArea * (ax + bx + cx) / 3.0
			weightedY += triangleArea * (ay + by + cy) / 3.0
		}
		return if (area > 1e-10) MeshFootprint(bounds,
			(source.bounds.left + weightedX / area).toFloat(),
			(source.bounds.top + weightedY / area).toFloat())
		else MeshFootprint(bounds, bounds.centerX, bounds.centerY)
	}

	internal fun meshSettings(layer: ClassifiedLayer, config: PipelineConfig): Pair<MeshSettings, Float> {
		val semanticDensity = when (layer.semantic.tag) {
			SemanticTag.FACE, SemanticTag.FRONT_HAIR, SemanticTag.BACK_HAIR, SemanticTag.TOPWEAR -> 0.65f
			SemanticTag.IRIDES, SemanticTag.EYELASH, SemanticTag.EYEWHITE, SemanticTag.EYEBROW,
			SemanticTag.MOUTH, SemanticTag.MOUTH_OPEN, SemanticTag.MOUTH_CLOSE,
			SemanticTag.TOOTH_T, SemanticTag.TOOTH_B, SemanticTag.TONGUE -> 0.45f
			else -> 1f
		}
		val override = config.meshOverrides[layer.source.id.raw]
		val outerMargin = if (config.mouthOutlineEnabled && !config.meshOnly &&
            layer.semantic.tag in setOf(SemanticTag.MOUTH, SemanticTag.MOUTH_OPEN) && layer.semantic.role == null) 0f
            else override?.outerMargin ?: config.meshOuterMargin
		// Face defaults to a dual edge band (legacy inner-margin envelope); other parts use the global mode.
		val edgeMode = override?.edgeMode
			?: if (layer.semantic.tag == SemanticTag.FACE) MeshEdgeMode.DOUBLE else config.meshEdgeMode
		val edgeWidth = override?.edgeWidth ?: config.meshEdgeWidth
		val effectiveSpacing = if (config.mouthOutlineEnabled && !config.meshOnly &&
            layer.semantic.tag in setOf(SemanticTag.MOUTH, SemanticTag.MOUTH_OPEN) && layer.semantic.role == null) {
            override?.maxEdgeDistance ?: max(6f, config.meshMaxEdgeDistance * semanticDensity)
        } else {
            override?.maxEdgeDistance ?: max(12f, config.meshMaxEdgeDistance * semanticDensity)
        }
		val effectiveInteriorDensity = override?.interiorDensity ?: max(12f, config.meshInteriorDensity * semanticDensity)
		// Fitted lids bend the lashes, white and closed line along their length into arcs: a mesh a few vertices long
		// shows its polygon edges, so these layers are meshed about four times as finely.
		val fittedLid = config.recipe.base("eye") === io.github.psd2live.core.recipe.RigModules.EYE_FITTED &&
			layer.semantic.tag in setOf(SemanticTag.EYELASH, SemanticTag.EYEWHITE, SemanticTag.EYE_CLOSE) ||
			config.recipe.base("brow") === io.github.psd2live.core.recipe.RigModules.BROW_FITTED && layer.semantic.tag == SemanticTag.EYEBROW ||
			config.recipe.base("mouth") === io.github.psd2live.core.recipe.RigModules.MOUTH_FITTED &&
				layer.semantic.tag in setOf(SemanticTag.MOUTH, SemanticTag.MOUTH_OPEN, SemanticTag.MOUTH_CLOSE, SemanticTag.TONGUE, SemanticTag.TOOTH_T, SemanticTag.TOOTH_B)
		if (fittedLid) {
			val fine = MeshSettings(outerMargin, edgeMode, edgeWidth, override?.maxEdgeDistance ?: max(4f, config.meshMaxEdgeDistance * 0.12f),
				override?.interiorDensity ?: max(4f, config.meshInteriorDensity * 0.12f), override?.fillAlgorithm ?: config.meshFillAlgorithm,
				override?.suppressBoundaryDiagonals ?: config.meshSuppressBoundaryDiagonals,
				override?.fillParameters ?: config.meshFillParameters, override?.wrap ?: config.meshWrap)
			return fine to fine.maxEdgeDistance
		}

		val settings = MeshSettings(outerMargin, edgeMode, edgeWidth, effectiveSpacing,
			effectiveInteriorDensity, override?.fillAlgorithm ?: config.meshFillAlgorithm,
			override?.suppressBoundaryDiagonals ?: config.meshSuppressBoundaryDiagonals,
			override?.fillParameters ?: config.meshFillParameters, override?.wrap ?: config.meshWrap)
		return settings to effectiveSpacing
	}

}
