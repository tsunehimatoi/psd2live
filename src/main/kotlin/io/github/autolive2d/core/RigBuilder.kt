package io.github.autolive2d.core

import org.umamo.format.art.LayerBlend
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.ChannelGrids
import org.umamo.runtime.model.ChannelValue
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.FormChannel
import org.umamo.runtime.model.KeyformAxis
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.MeshDeltaForm
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterGroupId
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.ParameterLink
import org.umamo.runtime.model.ParameterNode
import org.umamo.runtime.model.Part
import org.umamo.runtime.model.PartGroupMode
import org.umamo.runtime.model.PartId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.RotationPivotForm
import org.umamo.runtime.model.RuntimeTarget
import org.umamo.runtime.model.WarpLatticeForm
import org.umamo.runtime.model.withDerivedRenderRoot
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

object StandardParameters {
	val ANGLE_X = ParameterId("ParamAngleX")
	val ANGLE_Y = ParameterId("ParamAngleY")
	val ANGLE_Z = ParameterId("ParamAngleZ")
	val BODY_X = ParameterId("ParamBodyAngleX")
	val BODY_Y = ParameterId("ParamBodyAngleY")
	val BODY_Z = ParameterId("ParamBodyAngleZ")
	val EYE_L_OPEN = ParameterId("ParamEyeLOpen")
	val EYE_R_OPEN = ParameterId("ParamEyeROpen")
	val EYE_BALL_X = ParameterId("ParamEyeBallX")
	val EYE_BALL_Y = ParameterId("ParamEyeBallY")
	val BROW_L_Y = ParameterId("ParamBrowLY")
	val BROW_R_Y = ParameterId("ParamBrowRY")
	val MOUTH_FORM = ParameterId("ParamMouthForm")
	val MOUTH_OPEN = ParameterId("ParamMouthOpenY")
	val BREATH = ParameterId("ParamBreath")
	val HAIR_FRONT = ParameterId("ParamHairFront")
	val HAIR_BACK = ParameterId("ParamHairBack")

	val all = listOf(
		Parameter(ANGLE_X, "角度 X", -30f, 30f, 0f),
		Parameter(ANGLE_Y, "角度 Y", -30f, 30f, 0f),
		Parameter(ANGLE_Z, "角度 Z", -30f, 30f, 0f),
		Parameter(BODY_X, "身体角度 X", -10f, 10f, 0f),
		Parameter(BODY_Y, "身体角度 Y", -10f, 10f, 0f),
		Parameter(BODY_Z, "身体角度 Z", -10f, 10f, 0f),
		Parameter(EYE_L_OPEN, "左眼开合", 0f, 1f, 1f),
		Parameter(EYE_R_OPEN, "右眼开合", 0f, 1f, 1f),
		Parameter(EYE_BALL_X, "眼球 X", -1f, 1f, 0f),
		Parameter(EYE_BALL_Y, "眼球 Y", -1f, 1f, 0f),
		Parameter(BROW_L_Y, "左眉 Y", -1f, 1f, 0f),
		Parameter(BROW_R_Y, "右眉 Y", -1f, 1f, 0f),
		Parameter(MOUTH_FORM, "嘴型", -1f, 1f, 0f),
		Parameter(MOUTH_OPEN, "嘴巴开合", 0f, 1f, 0f),
		Parameter(BREATH, "呼吸", 0f, 1f, 0f),
		Parameter(HAIR_FRONT, "前发摆动", -1f, 1f, 0f),
		Parameter(HAIR_BACK, "后发摆动", -1f, 1f, 0f),
	)
}

data class BuiltRig(
	val puppet: PuppetModel,
	val pageByDrawableId: Map<String, Int>,
	val sourceBoundsByDrawableId: Map<String, Bounds>,
	val warnings: List<String>,
)

object RigBuilder {
	private val bodyWarpId = DeformerId("DeformBodyXY")
	private val breathWarpId = DeformerId("DeformBodyZBreath")
	private val headRotationId = DeformerId("DeformHeadRotation")
	private val faceWarpId = DeformerId("DeformFace3D")
	private val gazeWarpId = DeformerId("DeformEyeGaze")
	private val frontHairWarpId = DeformerId("DeformHairFront")
	private val backHairWarpId = DeformerId("DeformHairBack")

	private data class MeshData(
		val mesh: DrawableMesh,
		val canvasPositions: FloatArray,
	)

	fun build(analysis: PipelineAnalysis, atlas: PackedAtlas, config: PipelineConfig): BuiltRig {
		val warnings = mutableListOf<String>()
		val characterFrame = analysis.anchors.character
		val headCandidates = analysis.layers.filter { inferredGroup(it, analysis.anchors) == LayerGroup.HEAD && it.opaquePixels > 0 }
		val headFrame = if (headCandidates.isEmpty()) analysis.anchors.face else headCandidates.map { it.bounds }.reduce(Bounds::union).expanded(0.025f)
		val irisCandidates = analysis.layers.filter { it.semantic.tag == SemanticTag.IRIDES && it.opaquePixels > 0 }
		val gazeFrame = if (irisCandidates.isEmpty()) analysis.anchors.face else irisCandidates.map { it.bounds }.reduce(Bounds::union).expanded(0.18f)
		val frontHairCandidates = analysis.layers.filter { it.semantic.tag == SemanticTag.FRONT_HAIR && it.opaquePixels > 0 }
		val backHairCandidates = analysis.layers.filter { it.semantic.tag == SemanticTag.BACK_HAIR && it.opaquePixels > 0 }
		val frontHairFrame = frontHairCandidates.map { it.bounds }.takeIf { it.isNotEmpty() }?.reduce(Bounds::union)?.expanded(0.04f)
		val backHairFrame = backHairCandidates.map { it.bounds }.takeIf { it.isNotEmpty() }?.reduce(Bounds::union)?.expanded(0.04f)

		val headPartId = PartId("PartHead")
		val bodyPartId = PartId("PartBody")
		val extraPartId = PartId("PartExtra")
		val deformers = buildDeformers(
			analysis,
			characterFrame,
			headFrame,
			gazeFrame,
			frontHairFrame,
			backHairFrame,
			headPartId,
			bodyPartId,
			config,
		)

		val idCounts = mutableMapOf<String, Int>()
		val drawables = mutableListOf<Drawable>()
		val pageByDrawable = linkedMapOf<String, Int>()
		val sourceBoundsByDrawable = linkedMapOf<String, Bounds>()
		val classifiedByDrawable = mutableMapOf<DrawableId, ClassifiedLayer>()
		val orderedLayers = analysis.layers.sortedBy { it.source.order }
		for ((drawIndex, layer) in orderedLayers.withIndex()) {
			val placement = atlas.placementByLayerId[layer.source.id.raw]
			if (placement == null || layer.opaquePixels == 0) {
				warnings += "跳过空图层：${layer.source.name}"
				continue
			}
			val parentAndFrame = parentAndFrame(layer, analysis.anchors, characterFrame, headFrame, gazeFrame, frontHairFrame, backHairFrame)
			val id = uniqueDrawableId(layer, idCounts)
			val meshData = buildGridMesh(
				layer,
				parentAndFrame.second,
				placement,
				atlas.pages[placement.page].image.width,
				config.meshSpacing,
				config.alphaThreshold,
			)
			val geometryGrid = buildDrawableGeometry(layer, meshData, parentAndFrame.second, analysis.anchors)
			val drawable = Drawable(
				id = id,
				name = layer.source.name,
				parentDeformerId = parentAndFrame.first,
				blendMode = blendMode(layer.source.blend),
				maskedBy = emptyList(),
				mesh = meshData.mesh,
				geometryGrid = geometryGrid,
				channelGrids = buildChannels(layer),
				// Cubism Editor stores draw order as an integer. Keeping this integral also makes
				// fresh CMO3 conversion lossless instead of reporting one advisory per drawable.
				drawOrder = (orderedLayers.size - drawIndex).coerceAtMost(1000).toFloat(),
				opacity = layer.source.opacity,
				isVisible = layer.source.visible,
				texturePage = placement.page,
			)
			drawables += drawable
			classifiedByDrawable[id] = layer
			pageByDrawable[id.raw] = placement.page
			sourceBoundsByDrawable[id.raw] = layer.bounds
		}

		val drawableByTagSide = drawables.groupBy { drawable ->
			val semantic = classifiedByDrawable.getValue(drawable.id).semantic
			semantic.tag to semantic.side
		}
		val maskedDrawables = drawables.map { drawable ->
			val semantic = classifiedByDrawable.getValue(drawable.id).semantic
			if (semantic.tag != SemanticTag.IRIDES) return@map drawable
			val exact = drawableByTagSide[SemanticTag.EYEWHITE to semantic.side].orEmpty()
			val fallback = drawableByTagSide[SemanticTag.EYEWHITE to Side.NONE].orEmpty()
			drawable.copy(maskedBy = (exact.ifEmpty { fallback }).map { it.id })
		}

		fun childrenFor(group: LayerGroup): List<OrgChild> =
			maskedDrawables
				.filter { inferredGroup(classifiedByDrawable.getValue(it.id), analysis.anchors) == group }
				.sortedBy { classifiedByDrawable.getValue(it.id).source.order }
				.map { OrgChild.Drawable(it.id) }
		val parts = listOf(
			Part(headPartId, "头部", childrenFor(LayerGroup.HEAD), groupMode = PartGroupMode.PassThrough),
			Part(extraPartId, "附加物", childrenFor(LayerGroup.EXTRA), groupMode = PartGroupMode.PassThrough),
			Part(bodyPartId, "身体", childrenFor(LayerGroup.BODY) + childrenFor(LayerGroup.UNKNOWN), groupMode = PartGroupMode.PassThrough),
		)
		val parameterTree = parameterTree()
		val puppet = PuppetModel(
			parameters = StandardParameters.all,
			parts = parts,
			deformers = deformers,
			drawables = maskedDrawables,
			rootChildren = listOf(OrgChild.Part(headPartId), OrgChild.Part(extraPartId), OrgChild.Part(bodyPartId)),
			rootPartId = null,
			parameterLinks = listOf(
				ParameterLink(StandardParameters.ANGLE_X, StandardParameters.ANGLE_Y),
				ParameterLink(StandardParameters.BODY_X, StandardParameters.BODY_Y),
				ParameterLink(StandardParameters.EYE_BALL_X, StandardParameters.EYE_BALL_Y),
			),
			parameterTree = parameterTree,
			canvasWidth = analysis.source.widthPx.toFloat(),
			canvasHeight = analysis.source.heightPx.toFloat(),
			worldOriginX = analysis.source.widthPx * 0.5f,
			worldOriginY = -analysis.source.heightPx * 0.5f,
			// Cubism 5.0/MOC v5 is the compatibility baseline.  The generated rig does not use any
			// 5.3-only feature, and targeting v5 keeps it readable by both current Viewer releases and
			// older Cubism 5 runtimes without relying on v6-only container fields.
			runtimeTarget = RuntimeTarget.Cubism50,
		).withDerivedRenderRoot()
		return BuiltRig(puppet, pageByDrawable, sourceBoundsByDrawable, warnings)
	}

	private fun buildDeformers(
		analysis: PipelineAnalysis,
		character: Bounds,
		head: Bounds,
		gaze: Bounds,
		frontHair: Bounds?,
		backHair: Bounds?,
		headPartId: PartId,
		bodyPartId: PartId,
		config: PipelineConfig,
	): List<Deformer> {
		val bodyGrid = warpGrid(
			listOf(axis(StandardParameters.BODY_X, -10f, 0f, 10f), axis(StandardParameters.BODY_Y, -10f, 0f, 10f)),
			columns = 4,
			rows = 6,
		) { u, v, values ->
			val kx = values[0] / 10f * config.bodyStrength
			val ky = values[1] / 10f * config.bodyStrength
			val bell = sin(PI * v).toFloat()
			val perspective = (u - 0.5f) * kx * character.width * 0.025f
			val x = character.left + u * character.width + kx * character.width * 0.035f * bell + perspective
			val y = character.top + v * character.height + ky * character.height * 0.018f * (0.5f - v)
			x to y
		}
		val body = Deformer.Warp(bodyWarpId, "身体 XY", null, bodyPartId, 6, 4, true, bodyGrid)

		val breathGrid = warpGrid(
			listOf(axis(StandardParameters.BODY_Z, -10f, 0f, 10f), axis(StandardParameters.BREATH, 0f, 0.5f, 1f)),
			columns = 4,
			rows = 6,
		) { u, v, values ->
			val z = values[0] / 10f * config.bodyStrength
			val breath = values[1] * config.bodyStrength
			val chest = kotlin.math.exp(-((v - 0.42f) * (v - 0.42f)) / 0.035f)
			val x = u + z * 0.018f * sin(PI * v).toFloat() + (u - 0.5f) * breath * chest * 0.025f
			val y = v - breath * chest * 0.012f
			x to y
		}
		val breath = Deformer.Warp(breathWarpId, "身体 Z / 呼吸", bodyWarpId, bodyPartId, 6, 4, true, breathGrid)

		val chinLocalX = normalizeX(analysis.anchors.chinX, character)
		val chinLocalY = normalizeY(analysis.anchors.chinY, character)
		val rotationGrid = oneDimGrid(StandardParameters.ANGLE_Z, floatArrayOf(-30f, 0f, 30f)) { value ->
			RotationPivotForm(chinLocalX, chinLocalY, value, 1f)
		}
		val rotation = Deformer.Rotation(headRotationId, "头部 Z 旋转", breathWarpId, headPartId, 0f, rotationGrid)

		val faceGrid = warpGrid(
			listOf(axis(StandardParameters.ANGLE_X, -30f, 0f, 30f), axis(StandardParameters.ANGLE_Y, -30f, 0f, 30f)),
			columns = 8,
			rows = 8,
		) { u, v, values ->
			val canvasX = head.left + u * head.width
			val canvasY = head.top + v * head.height
			val projected = cylindricalFaceProject(canvasX, canvasY, analysis.anchors.face, values[0], values[1], config.headTurnStrength)
			// A rotation deformer's children are pixel-sized offsets from its pivot (scale=1), while the
			// pivot itself is expressed in its parent warp's normalized coordinates.  Mixing those two
			// spaces made a 450 px head only ~0.2 units wide and therefore effectively invisible.
			(projected.first - analysis.anchors.chinX) to (projected.second - analysis.anchors.chinY)
		}
		val face = Deformer.Warp(faceWarpId, "面部 3D", headRotationId, headPartId, 8, 8, true, faceGrid)

		val deformers = mutableListOf<Deformer>(body, breath, rotation, face)
		if (analysis.layers.any { it.semantic.tag == SemanticTag.IRIDES }) {
			val gazeInHead = mapBounds(gaze, head)
			val gazeGrid = warpGrid(
				listOf(axis(StandardParameters.EYE_BALL_X, -1f, 0f, 1f), axis(StandardParameters.EYE_BALL_Y, -1f, 0f, 1f)),
				2,
				2,
			) { u, v, values ->
				(gazeInHead.left + u * gazeInHead.width + values[0] * gazeInHead.width * 0.09f) to
					(gazeInHead.top + v * gazeInHead.height - values[1] * gazeInHead.height * 0.075f)
			}
			deformers += Deformer.Warp(gazeWarpId, "视线", faceWarpId, headPartId, 2, 2, true, gazeGrid)
		}
		frontHair?.let { frame -> deformers += hairWarp(frontHairWarpId, "前发物理", StandardParameters.HAIR_FRONT, frame, head, headPartId, 0.055f) }
		backHair?.let { frame -> deformers += hairWarp(backHairWarpId, "后发物理", StandardParameters.HAIR_BACK, frame, head, headPartId, 0.075f) }
		return deformers
	}

	private fun hairWarp(id: DeformerId, name: String, parameter: ParameterId, frame: Bounds, head: Bounds, part: PartId, strength: Float): Deformer.Warp {
		val inHead = mapBounds(frame, head)
		val grid = warpGrid(listOf(axis(parameter, -1f, 0f, 1f)), 3, 5) { u, v, values ->
			val weight = v * v
			val swing = values[0]
			(inHead.left + u * inHead.width + swing * strength * weight) to
				(inHead.top + v * inHead.height + kotlin.math.abs(swing) * strength * 0.18f * weight)
		}
		return Deformer.Warp(id, name, faceWarpId, part, 5, 3, true, grid)
	}

	private fun parentAndFrame(
		layer: ClassifiedLayer,
		anchors: RigAnchors,
		character: Bounds,
		head: Bounds,
		gaze: Bounds,
		frontHair: Bounds?,
		backHair: Bounds?,
	): Pair<DeformerId, Bounds> = when (layer.semantic.tag) {
		SemanticTag.IRIDES -> gazeWarpId to gaze
		SemanticTag.FRONT_HAIR -> frontHair?.let { frontHairWarpId to it } ?: (faceWarpId to head)
		SemanticTag.BACK_HAIR -> backHair?.let { backHairWarpId to it } ?: (faceWarpId to head)
		else -> if (inferredGroup(layer, anchors) == LayerGroup.HEAD) faceWarpId to head else breathWarpId to character
	}

	private fun buildGridMesh(
		layer: ClassifiedLayer,
		parentFrame: Bounds,
		placement: AtlasPlacement,
		atlasSize: Int,
		spacing: Int,
		alphaThreshold: Int,
	): MeshData {
		val width = max(1, layer.source.raster.width)
		val height = max(1, layer.source.raster.height)
		val semanticDensity = when (layer.semantic.tag) {
			SemanticTag.FACE, SemanticTag.FRONT_HAIR, SemanticTag.BACK_HAIR, SemanticTag.TOPWEAR -> 0.65f
			SemanticTag.IRIDES, SemanticTag.EYELASH, SemanticTag.EYEWHITE, SemanticTag.EYEBROW, SemanticTag.MOUTH, SemanticTag.MOUTH_OPEN, SemanticTag.MOUTH_CLOSE -> 0.45f
			else -> 1f
		}
		val effectiveSpacing = max(12f, spacing * semanticDensity)
		val adaptive = AdaptiveMeshGenerator.generate(
			width,
			height,
			layer.source.raster.rgba,
			alphaThreshold,
			effectiveSpacing,
		)
		if (adaptive != null) {
			val positions = FloatArray(adaptive.positions.size)
			val canvas = FloatArray(adaptive.positions.size)
			val uvs = FloatArray(adaptive.positions.size)
			for (index in adaptive.positions.indices step 2) {
				val localX = adaptive.positions[index].coerceIn(0f, width.toFloat())
				val localY = adaptive.positions[index + 1].coerceIn(0f, height.toFloat())
				val canvasX = layer.source.bounds.left + localX
				val canvasY = layer.source.bounds.top + localY
				positions[index] = normalizeX(canvasX, parentFrame)
				positions[index + 1] = normalizeY(canvasY, parentFrame)
				canvas[index] = canvasX
				canvas[index + 1] = canvasY
				uvs[index] = (placement.x + localX) / atlasSize
				uvs[index + 1] = (placement.y + localY) / atlasSize
			}
			return MeshData(DrawableMesh(positions, uvs, adaptive.indices), canvas)
		}
		return buildRectangularFallbackMesh(layer, parentFrame, placement, atlasSize, effectiveSpacing)
	}

	/** Conservative fallback for pathological alpha masks or degenerate one-pixel slivers. */
	private fun buildRectangularFallbackMesh(
		layer: ClassifiedLayer,
		parentFrame: Bounds,
		placement: AtlasPlacement,
		atlasSize: Int,
		effectiveSpacing: Float,
	): MeshData {
		val width = max(1, layer.source.raster.width)
		val height = max(1, layer.source.raster.height)
		val columns = ceil(width / effectiveSpacing).toInt().coerceIn(1, 18)
		val rows = ceil(height / effectiveSpacing).toInt().coerceIn(1, 24)
		val count = (columns + 1) * (rows + 1)
		val positions = FloatArray(count * 2)
		val canvas = FloatArray(count * 2)
		val uvs = FloatArray(count * 2)
		var vertex = 0
		for (row in 0..rows) {
			val v = row.toFloat() / rows
			for (column in 0..columns) {
				val u = column.toFloat() / columns
				val canvasX = layer.source.bounds.left + u * width
				val canvasY = layer.source.bounds.top + v * height
				positions[vertex * 2] = normalizeX(canvasX, parentFrame)
				positions[vertex * 2 + 1] = normalizeY(canvasY, parentFrame)
				canvas[vertex * 2] = canvasX
				canvas[vertex * 2 + 1] = canvasY
				uvs[vertex * 2] = (placement.x + u * width) / atlasSize
				uvs[vertex * 2 + 1] = (placement.y + v * height) / atlasSize
				vertex++
			}
		}
		val indices = IntArray(columns * rows * 6)
		var index = 0
		for (row in 0 until rows) for (column in 0 until columns) {
			val a = row * (columns + 1) + column
			val b = a + 1
			val c = a + columns + 1
			val d = c + 1
			indices[index++] = a
			indices[index++] = c
			indices[index++] = b
			indices[index++] = b
			indices[index++] = c
			indices[index++] = d
		}
		return MeshData(DrawableMesh(positions, uvs, indices), canvas)
	}

	private fun buildDrawableGeometry(layer: ClassifiedLayer, data: MeshData, parentFrame: Bounds, anchors: RigAnchors): KeyformGrid<MeshDeltaForm> {
		val tag = layer.semantic.tag
		return when (tag) {
			SemanticTag.EYEWHITE, SemanticTag.EYELASH -> eyeClosureGrid(layer, data, parentFrame, anchors)
			SemanticTag.IRIDES -> eyeClosureGrid(layer, data, parentFrame, anchors)
			SemanticTag.EYEBROW -> eyebrowGrid(layer, data, parentFrame)
			SemanticTag.MOUTH, SemanticTag.MOUTH_OPEN, SemanticTag.MOUTH_CLOSE -> mouthGrid(data)
			else -> zeroMeshGrid(data.mesh.positions.size)
		}
	}

	private fun eyeClosureGrid(layer: ClassifiedLayer, data: MeshData, frame: Bounds, anchors: RigAnchors): KeyformGrid<MeshDeltaForm> {
		val parameter = if (layer.semantic.side == Side.LEFT) StandardParameters.EYE_L_OPEN else StandardParameters.EYE_R_OPEN
		val axes = if (layer.semantic.side == Side.NONE) {
			listOf(axis(StandardParameters.EYE_L_OPEN, 0f, 1f), axis(StandardParameters.EYE_R_OPEN, 0f, 1f))
		} else listOf(axis(parameter, 0f, 1f))
		return grid(axes) { values ->
			val delta = FloatArray(data.mesh.positions.size)
			val closeCanvasY = layer.bounds.top + layer.bounds.height * 0.55f
			val closeLocalY = normalizeY(closeCanvasY, frame)
			for (vertex in data.mesh.positions.indices step 2) {
				val canvasX = data.canvasPositions[vertex]
				val openness = when (layer.semantic.side) {
					Side.LEFT -> values[0]
					Side.RIGHT -> values[0]
					Side.NONE -> if (canvasX >= anchors.faceCenterX) values[0] else values[1]
				}
				val currentY = data.mesh.positions[vertex + 1]
				val targetY = if (layer.semantic.tag == SemanticTag.EYELASH && currentY > closeLocalY) currentY else closeLocalY
				delta[vertex + 1] = (targetY - currentY) * (1f - openness)
			}
			MeshDeltaForm(delta)
		}
	}

	private fun eyebrowGrid(layer: ClassifiedLayer, data: MeshData, frame: Bounds): KeyformGrid<MeshDeltaForm> {
		val parameter = if (layer.semantic.side == Side.LEFT) StandardParameters.BROW_L_Y else StandardParameters.BROW_R_Y
		return oneDimGrid(parameter, floatArrayOf(-1f, 0f, 1f)) { value ->
			val delta = FloatArray(data.mesh.positions.size)
			val dy = -value * layer.bounds.height * 0.12f / frame.height
			for (index in 1 until delta.size step 2) delta[index] = dy
			MeshDeltaForm(delta)
		}
	}

	private fun mouthGrid(data: MeshData): KeyformGrid<MeshDeltaForm> =
		grid(
			listOf(
				axis(StandardParameters.MOUTH_FORM, -1f, 0f, 1f),
				axis(StandardParameters.MOUTH_OPEN, 0f, 0.5f, 1f),
			),
		) { values ->
			val delta = FloatArray(data.mesh.positions.size)
			val xs = data.mesh.positions.filterIndexed { index, _ -> index % 2 == 0 }
			val ys = data.mesh.positions.filterIndexed { index, _ -> index % 2 == 1 }
			val left = xs.minOrNull() ?: 0f
			val right = xs.maxOrNull() ?: 1f
			val top = ys.minOrNull() ?: 0f
			val bottom = ys.maxOrNull() ?: 1f
			val centerX = (left + right) * 0.5f
			val width = (right - left).coerceAtLeast(1e-4f)
			val height = (bottom - top).coerceAtLeast(1e-4f)
			val form = values[0]
			val open = values[1]
			for (index in data.mesh.positions.indices step 2) {
				val x = data.mesh.positions[index]
				val y = data.mesh.positions[index + 1]
				val fromCenter = (x - centerX) / (width * 0.5f)
				val vertical = (y - top) / height
				delta[index] = fromCenter * width * form * 0.055f
				delta[index + 1] = (y - top) * open * open * 0.55f - kotlin.math.abs(fromCenter) * height * form * 0.10f * (1f - vertical * 0.35f)
			}
			MeshDeltaForm(delta)
		}

	internal fun zeroMeshGrid(size: Int): KeyformGrid<MeshDeltaForm> =
		KeyformGrid(emptyList(), listOf(KeyformCell(IntArray(0), MeshDeltaForm(FloatArray(size)))))

	private fun buildChannels(layer: ClassifiedLayer): ChannelGrids {
		val opacityGrid = when (layer.semantic.tag) {
			SemanticTag.EYE_CLOSE -> {
				val parameter = if (layer.semantic.side == Side.LEFT) StandardParameters.EYE_L_OPEN else StandardParameters.EYE_R_OPEN
				scalarGrid(parameter, floatArrayOf(0f, 1f)) { open -> 1f - open }
			}
			SemanticTag.MOUTH_OPEN -> scalarGrid(StandardParameters.MOUTH_OPEN, floatArrayOf(0f, 1f)) { it }
			SemanticTag.MOUTH_CLOSE -> scalarGrid(StandardParameters.MOUTH_OPEN, floatArrayOf(0f, 1f)) { 1f - it }
			else -> null
		}
		return opacityGrid?.let { ChannelGrids(mapOf(FormChannel.OPACITY to it)) } ?: ChannelGrids.Empty
	}

	private fun scalarGrid(parameter: ParameterId, keys: FloatArray, value: (Float) -> Float): KeyformGrid<ChannelValue> =
		oneDimGrid(parameter, keys) { key -> ChannelValue.Scalar(value(key)) }

	private fun parameterTree(): List<ParameterNode> {
		fun group(id: String, name: String, parameters: List<ParameterId>) = ParameterNode.Group(
			ParameterGroupId(id), name, true, parameters.map { ParameterNode.Param(it) },
		)
		return listOf(
			group("ParamGroupFace", "面部", listOf(StandardParameters.ANGLE_X, StandardParameters.ANGLE_Y, StandardParameters.ANGLE_Z)),
			group("ParamGroupEyes", "眼睛", listOf(StandardParameters.EYE_L_OPEN, StandardParameters.EYE_R_OPEN, StandardParameters.EYE_BALL_X, StandardParameters.EYE_BALL_Y)),
			group("ParamGroupBrows", "眉毛", listOf(StandardParameters.BROW_L_Y, StandardParameters.BROW_R_Y)),
			group("ParamGroupMouth", "嘴巴", listOf(StandardParameters.MOUTH_FORM, StandardParameters.MOUTH_OPEN)),
			group("ParamGroupBody", "身体", listOf(StandardParameters.BODY_X, StandardParameters.BODY_Y, StandardParameters.BODY_Z, StandardParameters.BREATH)),
			group("ParamGroupPhysics", "物理", listOf(StandardParameters.HAIR_FRONT, StandardParameters.HAIR_BACK)),
		)
	}

	private fun uniqueDrawableId(layer: ClassifiedLayer, counts: MutableMap<String, Int>): DrawableId {
		val side = when (layer.semantic.side) { Side.LEFT -> "L"; Side.RIGHT -> "R"; Side.NONE -> "" }
		val rawBase = if (layer.semantic.tag == SemanticTag.UNKNOWN) "Layer" else layer.semantic.tag.name.lowercase().replace('_', ' ')
		val base = rawBase.split(' ').joinToString("") { word -> word.replaceFirstChar(Char::uppercaseChar) }
		val key = "ArtMesh$base$side"
		val ordinal = counts.merge(key, 1, Int::plus) ?: 1
		return DrawableId(if (ordinal == 1) key else "$key$ordinal")
	}

	private fun blendMode(blend: LayerBlend): BlendMode = when (blend) {
		LayerBlend.Add, LayerBlend.AddGlow, LayerBlend.LinearLight -> BlendMode.AdditivePremultiplied
		LayerBlend.Multiply, LayerBlend.LinearBurn, LayerBlend.ColorBurn -> BlendMode.MultiplyPremultiplied
		else -> BlendMode.Normal
	}

	private fun inferredGroup(layer: ClassifiedLayer, anchors: RigAnchors): LayerGroup =
		if (layer.semantic.tag.group != LayerGroup.UNKNOWN) layer.semantic.tag.group
		else if (layer.bounds.centerY <= anchors.face.bottom) LayerGroup.HEAD else LayerGroup.BODY

	private fun mapBounds(child: Bounds, parent: Bounds): Bounds = Bounds(
		normalizeX(child.left, parent), normalizeY(child.top, parent),
		normalizeX(child.right, parent), normalizeY(child.bottom, parent),
	)

	private fun normalizeX(x: Float, frame: Bounds): Float = (x - frame.left) / frame.width.coerceAtLeast(1e-4f)
	private fun normalizeY(y: Float, frame: Bounds): Float = (y - frame.top) / frame.height.coerceAtLeast(1e-4f)

	internal fun cylindricalFaceProject(
		canvasX: Float,
		canvasY: Float,
		face: Bounds,
		angleX: Float,
		angleY: Float,
		strength: Float,
	): Pair<Float, Float> {
		// Stretchy's later face-parallax iterations found that a full ellipsoid plus perspective
		// over-compresses the forehead/chin under AngleY.  A cylindrical dome (Z depends only on X)
		// keeps horizontal facial features curved while preserving their vertical spacing.
		if (angleX == 0f && angleY == 0f) return canvasX to canvasY
		val radiusX = max(1f, face.width * 0.5f)
		val radiusY = max(1f, face.height * 0.5f)
		val x = (canvasX - face.centerX) / radiusX
		val y = (canvasY - face.centerY) / radiusY
		val clampedX = x.coerceIn(-1f, 1f)
		val dome = sqrt(max(0f, 1f - clampedX * clampedX))
		val z = 0.30f + (0.80f - 0.30f) * dome
		val safeStrength = strength.coerceIn(0f, 2f)
		val yaw = (angleX / 30f) * 15f * safeStrength * (PI / 180.0).toFloat()
		// Positive Cubism AngleY means looking upward in the PSD/canvas Y-down coordinate system.
		// The old implementation negated this angle, reversing the direction.
		val pitch = (angleY / 30f) * 8f * safeStrength * (PI / 180.0).toFloat()
		val turnedX = x * cos(yaw) + z * sin(yaw)
		val turnedZ = -x * sin(yaw) + z * cos(yaw)
		val turnedY = y * cos(pitch) - turnedZ * sin(pitch)
		return (face.centerX + turnedX * radiusX) to (face.centerY + turnedY * radiusY)
	}

	private fun axis(parameter: ParameterId, vararg keys: Float) = KeyformAxis(parameter, keys)

	private fun <T> oneDimGrid(parameter: ParameterId, keys: FloatArray, form: (Float) -> T): KeyformGrid<T> =
		KeyformGrid(listOf(KeyformAxis(parameter, keys)), keys.indices.map { index -> KeyformCell(intArrayOf(index), form(keys[index])) })

	private fun <T> grid(axes: List<KeyformAxis>, form: (FloatArray) -> T): KeyformGrid<T> {
		val cells = mutableListOf<KeyformCell<T>>()
		fun visit(axisIndex: Int, coordinate: IntArray, values: FloatArray) {
			if (axisIndex == axes.size) {
				cells += KeyformCell(coordinate.copyOf(), form(values.copyOf()))
				return
			}
			for (keyIndex in axes[axisIndex].keys.indices) {
				coordinate[axisIndex] = keyIndex
				values[axisIndex] = axes[axisIndex].keys[keyIndex]
				visit(axisIndex + 1, coordinate, values)
			}
		}
		visit(0, IntArray(axes.size), FloatArray(axes.size))
		return KeyformGrid(axes, cells)
	}

	private fun warpGrid(
		axes: List<KeyformAxis>,
		columns: Int,
		rows: Int,
		point: (u: Float, v: Float, values: FloatArray) -> Pair<Float, Float>,
	): KeyformGrid<WarpLatticeForm> = grid(axes) { values ->
		val controlPoints = FloatArray((columns + 1) * (rows + 1) * 2)
		var index = 0
		for (row in 0..rows) for (column in 0..columns) {
			val result = point(column.toFloat() / columns, row.toFloat() / rows, values)
			controlPoints[index++] = result.first
			controlPoints[index++] = result.second
		}
		WarpLatticeForm(controlPoints)
	}
}
