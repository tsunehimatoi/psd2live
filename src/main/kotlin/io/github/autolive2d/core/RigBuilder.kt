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
import kotlin.math.max
import kotlin.math.sin

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
		Parameter(ANGLE_X, "角度 X", -45f, 45f, 0f),
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
	val layerIdByDrawableId: Map<String, String>,
	val faceCenterX: Float,
	val faceCenterY: Float,
	val faceRadiusX: Float,
	val faceRadiusY: Float,
	val warnings: List<String>,
)

object RigBuilder {
	private val bodyWarpId = DeformerId("DeformBodyXY")
	private val breathWarpId = DeformerId("DeformBodyZBreath")
	private val headRotationId = DeformerId("DeformHeadRotation")
	private val headWarpId = DeformerId("DeformHeadContainer")
	private val faceWarpId = DeformerId("DeformFaceNinePose")
	private val frontHairFollowWarpId = DeformerId("DeformHairFrontFollow")
	private val frontHairPhysicsWarpId = DeformerId("DeformHairFrontPhysics")
	private val backHairFollowWarpId = DeformerId("DeformHairBackFollow")
	private val backHairPhysicsWarpId = DeformerId("DeformHairBackPhysics")

	private val faceTags = setOf(
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
	)

	private data class MeshData(
		val mesh: DrawableMesh,
		val canvasPositions: FloatArray,
	)

	fun build(analysis: PipelineAnalysis, atlas: PackedAtlas, config: PipelineConfig): BuiltRig {
		val warnings = mutableListOf<String>()
		val characterFrame = analysis.anchors.character
		val headCandidates = analysis.layers.filter { inferredGroup(it, analysis.anchors) == LayerGroup.HEAD && it.opaquePixels > 0 }
		val headFrame = if (headCandidates.isEmpty()) analysis.anchors.face else headCandidates.map { it.bounds }.reduce(Bounds::union).expanded(0.025f)
		val faceRig = NinePoseFaceRig.from(analysis)
		val faceCandidates = analysis.layers.filter { it.semantic.tag in faceTags && it.opaquePixels > 0 }
		val faceFrame = (faceCandidates.map { it.bounds } + faceRig.face)
			.reduce(Bounds::union)
			.expanded(0.025f)
		val frontHairCandidates = analysis.layers.filter { it.semantic.tag == SemanticTag.FRONT_HAIR && it.opaquePixels > 0 }
		val backHairCandidates = analysis.layers.filter { it.semantic.tag == SemanticTag.BACK_HAIR && it.opaquePixels > 0 }
		val frontHairFrame = frontHairCandidates.map { it.bounds }.takeIf { it.isNotEmpty() }?.reduce(Bounds::union)?.expanded(0.04f)
		val backHairFrame = backHairCandidates.map { it.bounds }.takeIf { it.isNotEmpty() }?.reduce(Bounds::union)?.expanded(0.04f)

		val headPartId = PartId("PartHead")
		val facePartId = PartId("PartFace")
		val frontHairPartId = PartId("PartHairFront")
		val backHairPartId = PartId("PartHairBack")
		val headAccessoryPartId = PartId("PartHeadAccessories")
		val bodyPartId = PartId("PartBody")
		val extraPartId = PartId("PartExtra")
		val deformers = buildDeformers(
			faceRig,
			characterFrame,
			headFrame,
			faceFrame,
			frontHairFrame,
			backHairFrame,
			headPartId,
			facePartId,
			frontHairPartId,
			backHairPartId,
			bodyPartId,
			config,
		)

		val idCounts = mutableMapOf<String, Int>()
		val drawables = mutableListOf<Drawable>()
		val pageByDrawable = linkedMapOf<String, Int>()
		val sourceBoundsByDrawable = linkedMapOf<String, Bounds>()
		val layerIdByDrawable = linkedMapOf<String, String>()
		val classifiedByDrawable = mutableMapOf<DrawableId, ClassifiedLayer>()
		val orderedLayers = analysis.layers.sortedBy { it.source.order }
		for ((drawIndex, layer) in orderedLayers.withIndex()) {
			val placement = atlas.placementByLayerId[layer.source.id.raw]
			if (placement == null || layer.opaquePixels == 0) {
				warnings += "跳过空图层：${layer.source.name}"
				continue
			}
			val parentAndFrame = parentAndFrame(layer, faceRig, analysis.anchors, characterFrame, headFrame, faceFrame, frontHairFrame, backHairFrame)
			val id = uniqueDrawableId(layer, idCounts)
			val meshData = buildGridMesh(
				layer,
				parentAndFrame.second,
				placement,
				atlas.pages[placement.page].image.width,
				config.meshSpacing,
				config.alphaThreshold,
			)
			val geometryGrid = buildDrawableGeometry(layer, meshData, parentAndFrame.second, faceRig)
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
				isVisible = layerVisibility(config, layer.source.id.raw, layer.source.visible),
				texturePage = placement.page,
			)
			drawables += drawable
			classifiedByDrawable[id] = layer
			pageByDrawable[id.raw] = placement.page
			sourceBoundsByDrawable[id.raw] = layer.bounds
			layerIdByDrawable[id.raw] = layer.source.id.raw
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
		fun headChildrenFor(predicate: (SemanticTag) -> Boolean): List<OrgChild> =
			maskedDrawables
				.filter { drawable ->
					val layer = classifiedByDrawable.getValue(drawable.id)
					inferredGroup(layer, analysis.anchors) == LayerGroup.HEAD && predicate(layer.semantic.tag)
				}
				.sortedBy { classifiedByDrawable.getValue(it.id).source.order }
				.map { OrgChild.Drawable(it.id) }
		val parts = listOf(
			Part(
				headPartId,
				"头部",
				listOf(
					OrgChild.Part(backHairPartId),
					OrgChild.Part(facePartId),
					OrgChild.Part(frontHairPartId),
					OrgChild.Part(headAccessoryPartId),
				),
				groupMode = PartGroupMode.PassThrough,
			),
			Part(backHairPartId, "后发", headChildrenFor { it == SemanticTag.BACK_HAIR }, groupMode = PartGroupMode.PassThrough),
			Part(facePartId, "面部", headChildrenFor { it in faceTags }, groupMode = PartGroupMode.PassThrough),
			Part(frontHairPartId, "前发", headChildrenFor { it == SemanticTag.FRONT_HAIR }, groupMode = PartGroupMode.PassThrough),
			Part(
				headAccessoryPartId,
				"头部附件",
				headChildrenFor { it !in faceTags && it != SemanticTag.FRONT_HAIR && it != SemanticTag.BACK_HAIR },
				groupMode = PartGroupMode.PassThrough,
			),
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
		return BuiltRig(
			puppet,
			pageByDrawable,
			sourceBoundsByDrawable,
			layerIdByDrawable,
			faceRig.centerX,
			faceRig.centerY,
			faceRig.radiusX,
			faceRig.radiusY,
			warnings,
		)
	}

	/**
	 * Body yaw keeps the two silhouette endpoints on every lattice row the same distance apart.
	 * Perspective is expressed by an interior roll with zero endpoint weight, so +X and -X are
	 * exact mirrors instead of the former signed global scale (`1 + kx * 0.025`).
	 */
	internal fun bodyWarpPoint(
		character: Bounds,
		u: Float,
		v: Float,
		bodyAngleX: Float,
		bodyAngleY: Float,
		strength: Float,
	): Pair<Float, Float> {
		val boundedStrength = strength.coerceIn(0f, 2f)
		val yaw = bodyAngleX / 10f * boundedStrength
		val pitch = bodyAngleY / 10f * boundedStrength
		val torsoEnvelope = sin(PI * v).toFloat().coerceAtLeast(0f)
		val endpointSafeRoll = 4f * u * (1f - u)
		val endpointSafePitch = sin(2.0 * PI * v).toFloat()
		val rowShift = yaw * character.width * 0.035f * torsoEnvelope
		val perspectiveRoll = yaw * character.width * 0.015f * torsoEnvelope * endpointSafeRoll
		val x = character.left + u * character.width + rowShift + perspectiveRoll
		val y = character.top + v * character.height + pitch * character.height * 0.007f * endpointSafePitch
		return x to y
	}

	/** Body Z is an odd row shift; Breath is deliberately non-negative and may expand the chest. */
	internal fun bodySecondaryWarpPoint(
		u: Float,
		v: Float,
		bodyAngleZ: Float,
		breathValue: Float,
		strength: Float,
	): Pair<Float, Float> {
		val boundedStrength = strength.coerceIn(0f, 2f)
		val z = bodyAngleZ / 10f * boundedStrength
		val breath = breathValue.coerceIn(0f, 1f) * boundedStrength
		val chest = kotlin.math.exp(-((v - 0.42f) * (v - 0.42f)) / 0.035f)
		val x = u + z * 0.018f * sin(PI * v).toFloat() + (u - 0.5f) * breath * chest * 0.025f
		val y = v - breath * chest * 0.012f
		return x to y
	}

	internal fun headContainerPoint(
		head: Bounds,
		originX: Float,
		originY: Float,
		u: Float,
		v: Float,
		angleX: Float,
		angleY: Float,
		strength: Float,
	): Pair<Float, Float> {
		val boundedStrength = strength.coerceIn(0f, 2f)
		val canvasX = head.left + u * head.width
		val canvasY = head.top + v * head.height
		val yaw = angleX / 45f * boundedStrength
		val pitch = angleY / 30f * boundedStrength
		val crownArch = sin(PI * u).toFloat().coerceAtLeast(0f)
		val shellX = yaw * head.width * (0.009f + crownArch * 0.004f)
		val shellY = -pitch * head.height * 0.008f
		return (canvasX - originX + shellX) to (canvasY - originY + shellY)
	}

	internal fun gazePoint(u: Float, v: Float, eyeX: Float, eyeY: Float): Pair<Float, Float> =
		(u + eyeX * 0.10f) to (v - eyeY * 0.085f)

	internal fun hairFollowPoint(
		inHead: Bounds,
		u: Float,
		v: Float,
		angleX: Float,
		angleY: Float,
		yawParallax: Float,
		pitchParallax: Float,
	): Pair<Float, Float> {
		val yaw = angleX / 45f
		val pitch = angleY / 30f
		val yawDepthWeight = 0.62f + v * 0.38f
		// Both endpoints receive the same vertical offset. The middle retains a depth bulge without
		// turning signed pitch into a global height scale.
		val pitchDepthWeight = 0.78f + sin(PI * v).toFloat().coerceAtLeast(0f) * 0.22f
		return (inHead.left + u * inHead.width + yaw * yawParallax * yawDepthWeight) to
			(inHead.top + v * inHead.height + pitch * pitchParallax * pitchDepthWeight)
	}

	internal fun hairPhysicsPoint(
		u: Float,
		v: Float,
		swing: Float,
		normalizedSway: Float,
		normalizedCurl: Float,
	): Pair<Float, Float> {
		val tipWeight = v * v * v
		val lateral = swing * normalizedSway * tipWeight
		// A left/right pendulum has the same shortening in either direction. Squaring also keeps the
		// neutral derivative continuous, unlike abs(swing).
		val lift = swing * swing * normalizedCurl * tipWeight
		return (u + lateral) to (v - lift)
	}

	private fun buildDeformers(
		faceRig: NinePoseFaceRig,
		character: Bounds,
		head: Bounds,
		faceFrame: Bounds,
		frontHair: Bounds?,
		backHair: Bounds?,
		headPartId: PartId,
		facePartId: PartId,
		frontHairPartId: PartId,
		backHairPartId: PartId,
		bodyPartId: PartId,
		config: PipelineConfig,
	): List<Deformer> {
		val bodyGrid = warpGrid(
			listOf(axis(StandardParameters.BODY_X, -10f, 0f, 10f), axis(StandardParameters.BODY_Y, -10f, 0f, 10f)),
			columns = 4,
			rows = 6,
		) { u, v, values ->
			bodyWarpPoint(character, u, v, values[0], values[1], config.bodyStrength)
		}
		val body = Deformer.Warp(bodyWarpId, "身体 XY", null, bodyPartId, 6, 4, true, bodyGrid)

		val breathGrid = warpGrid(
			listOf(axis(StandardParameters.BODY_Z, -10f, 0f, 10f), axis(StandardParameters.BREATH, 0f, 0.5f, 1f)),
			columns = 4,
			rows = 6,
		) { u, v, values ->
			bodySecondaryWarpPoint(u, v, values[0], values[1], config.bodyStrength)
		}
		val breath = Deformer.Warp(breathWarpId, "身体 Z / 呼吸", bodyWarpId, bodyPartId, 6, 4, true, breathGrid)

		val chinLocalX = normalizeX(faceRig.chinX, character)
		val chinLocalY = normalizeY(faceRig.chinY, character)
		val rotationGrid = oneDimGrid(StandardParameters.ANGLE_Z, floatArrayOf(-30f, 0f, 30f)) { value ->
			RotationPivotForm(chinLocalX, chinLocalY, value, 1f)
		}
		val rotation = Deformer.Rotation(headRotationId, "头部 Z 旋转", breathWarpId, headPartId, 0f, rotationGrid)

		// A real head container separates skull-following content from the facial surface.  It is the
		// sole pixel-space child of the rotation deformer; all descendants use ordinary normalized
		// warp coordinates.  Face, front hair and back hair are siblings below this node.
		val headGrid = warpGrid(ninePoseAxes(), columns = 4, rows = 5) { u, v, values ->
			headContainerPoint(head, faceRig.chinX, faceRig.chinY, u, v, values[0], values[1], config.headTurnStrength)
		}
		val headContainer = Deformer.Warp(headWarpId, "头部容器", headRotationId, headPartId, 5, 4, true, headGrid)

		val faceGrid = warpGrid(
			ninePoseAxes(),
			columns = 8,
			rows = 8,
		) { u, v, values ->
			val canvasX = faceFrame.left + u * faceFrame.width
			val canvasY = faceFrame.top + v * faceFrame.height
			val projected = faceRig.surfacePoint(canvasX, canvasY, values[0], values[1], config.headTurnStrength)
			normalizeX(projected.first, head) to normalizeY(projected.second, head)
		}
		val face = Deformer.Warp(faceWarpId, "面部九轴 / 经纬网", headWarpId, facePartId, 8, 8, true, faceGrid)

		val deformers = mutableListOf<Deformer>(body, breath, rotation, headContainer, face)
		val primaryRegions = faceRig.regions.filter { it.feature != FaceFeature.IRIS }
		for (region in primaryRegions) {
			deformers += featureWarp(faceRig, region, faceWarpId, faceFrame, facePartId, config)
		}
		for (irisRegion in faceRig.regions.filter { it.feature == FaceFeature.IRIS }) {
			val eyeRegion = faceRig.regionFor(FaceFeature.EYE, irisRegion.side) ?: continue
			val irisShape = featureWarp(
				faceRig,
				irisRegion,
				featureWarpId(eyeRegion),
				eyeRegion.bounds,
				facePartId,
				config,
			)
			deformers += irisShape
			deformers += gazeWarp(irisRegion, irisShape.id, facePartId)
		}
		frontHair?.let { frame ->
			deformers += hairFollowWarp(frontHairFollowWarpId, "前发头部跟随", frame, head, frontHairPartId, 0.014f, -0.006f)
			deformers += hairPhysicsWarp(
				frontHairPhysicsWarpId,
				"前发物理摆动",
				StandardParameters.HAIR_FRONT,
				frontHairFollowWarpId,
				frame,
				frontHairPartId,
				rows = 4,
				swayRatio = 0.12f,
				curlRatio = 0.030f,
			)
		}
		backHair?.let { frame ->
			deformers += hairFollowWarp(backHairFollowWarpId, "后发头部跟随", frame, head, backHairPartId, -0.018f, 0.004f)
			deformers += hairPhysicsWarp(
				backHairPhysicsWarpId,
				"后发物理摆动",
				StandardParameters.HAIR_BACK,
				backHairFollowWarpId,
				frame,
				backHairPartId,
				rows = 6,
				swayRatio = 0.10f,
				curlRatio = 0.025f,
			)
		}
		return deformers
	}

	private fun featureWarp(
		faceRig: NinePoseFaceRig,
		region: FaceRegion,
		parent: DeformerId,
		parentFrame: Bounds,
		part: PartId,
		config: PipelineConfig,
	): Deformer.Warp {
		val inParent = mapBounds(region.bounds, parentFrame)
		val (columns, rows) = when (region.feature) {
			FaceFeature.EYE, FaceFeature.MOUTH -> 4 to 3
			FaceFeature.NOSE -> 3 to 4
			else -> 3 to 3
		}
		val geometry = warpGrid(ninePoseAxes(), columns, rows) { u, v, values ->
			val offset = faceRig.featureOffset(region.feature, region.bounds, u, v, values[0], values[1], config.headTurnStrength)
			(inParent.left + u * inParent.width + offset.first / parentFrame.width.coerceAtLeast(1e-4f)) to
				(inParent.top + v * inParent.height + offset.second / parentFrame.height.coerceAtLeast(1e-4f))
		}
		val channels = if (region.feature == FaceFeature.EAR) {
			ChannelGrids(
				mapOf(
					FormChannel.OPACITY to scalarGrid(StandardParameters.ANGLE_X, NinePoseFaceRig.angleXKeys) { angle ->
						faceRig.earOpacity(region.bounds, angle, config.headTurnStrength)
					},
				),
			)
		} else ChannelGrids.Empty
		return Deformer.Warp(
			featureWarpId(region),
			featureDisplayName(region),
			parent,
			part,
			rows,
			columns,
			true,
			geometry,
			channels,
		)
	}

	private fun gazeWarp(region: FaceRegion, parent: DeformerId, part: PartId): Deformer.Warp {
		val geometry = warpGrid(
			listOf(axis(StandardParameters.EYE_BALL_X, -1f, 0f, 1f), axis(StandardParameters.EYE_BALL_Y, -1f, 0f, 1f)),
			2,
			2,
		) { u, v, values ->
			gazePoint(u, v, values[0], values[1])
		}
		return Deformer.Warp(gazeWarpId(region), "${sideDisplay(region.side)}视线", parent, part, 2, 2, true, geometry)
	}

	/** Head-angle following for one hair depth plane; deliberately independent of the face warp. */
	private fun hairFollowWarp(
		id: DeformerId,
		name: String,
		frame: Bounds,
		head: Bounds,
		part: PartId,
		yawParallax: Float,
		pitchParallax: Float,
	): Deformer.Warp {
		val inHead = mapBounds(frame, head)
		val grid = warpGrid(
			ninePoseAxes(),
			3,
			4,
		) { u, v, values ->
			hairFollowPoint(inHead, u, v, values[0], values[1], yawParallax, pitchParallax)
		}
		return Deformer.Warp(id, name, headWarpId, part, 4, 3, true, grid)
	}

	/**
	 * StretchyStudio/Hiyori-style hair-tip warp.  The root row is pinned exactly; cubic falloff
	 * keeps the upper mass stable and concentrates the physics response at the tips.  Magnitude is
	 * based on min(width,height), preventing a short, wide fringe from floating as one skull chunk.
	 */
	private fun hairPhysicsWarp(
		id: DeformerId,
		name: String,
		parameter: ParameterId,
		parent: DeformerId,
		frame: Bounds,
		part: PartId,
		rows: Int,
		swayRatio: Float,
		curlRatio: Float,
	): Deformer.Warp {
		val scale = minOf(frame.width, frame.height).coerceAtLeast(1f)
		val normalizedSway = scale / frame.width.coerceAtLeast(1f) * swayRatio
		val normalizedCurl = scale / frame.height.coerceAtLeast(1f) * curlRatio
		val grid = warpGrid(listOf(axis(parameter, -1f, 0f, 1f)), columns = 3, rows = rows) { u, v, values ->
			hairPhysicsPoint(u, v, values[0], normalizedSway, normalizedCurl)
		}
		return Deformer.Warp(id, name, parent, part, rows, 3, true, grid)
	}

	private fun parentAndFrame(
		layer: ClassifiedLayer,
		faceRig: NinePoseFaceRig,
		anchors: RigAnchors,
		character: Bounds,
		head: Bounds,
		faceFrame: Bounds,
		frontHair: Bounds?,
		backHair: Bounds?,
	): Pair<DeformerId, Bounds> = when (layer.semantic.tag) {
		SemanticTag.IRIDES -> faceRig.regionFor(FaceFeature.IRIS, layer.semantic.side)?.let { gazeWarpId(it) to it.bounds }
			?: (faceWarpId to faceFrame)
		SemanticTag.EYEWHITE, SemanticTag.EYELASH, SemanticTag.EYE_CLOSE ->
			faceRig.regionFor(FaceFeature.EYE, layer.semantic.side)?.let { featureWarpId(it) to it.bounds } ?: (faceWarpId to faceFrame)
		SemanticTag.EYEBROW -> faceRig.regionFor(FaceFeature.BROW, layer.semantic.side)?.let { featureWarpId(it) to it.bounds }
			?: (faceWarpId to faceFrame)
		SemanticTag.NOSE -> faceRig.regionFor(FaceFeature.NOSE, layer.semantic.side)?.let { featureWarpId(it) to it.bounds }
			?: (faceWarpId to faceFrame)
		SemanticTag.MOUTH, SemanticTag.MOUTH_OPEN, SemanticTag.MOUTH_CLOSE ->
			faceRig.regionFor(FaceFeature.MOUTH, layer.semantic.side)?.let { featureWarpId(it) to it.bounds } ?: (faceWarpId to faceFrame)
		SemanticTag.EARS, SemanticTag.EARWEAR -> faceRig.regionFor(FaceFeature.EAR, layer.semantic.side)?.let { featureWarpId(it) to it.bounds }
			?: (faceWarpId to faceFrame)
		SemanticTag.FRONT_HAIR -> frontHair?.let { frontHairPhysicsWarpId to it } ?: (headWarpId to head)
		SemanticTag.BACK_HAIR -> backHair?.let { backHairPhysicsWarpId to it } ?: (headWarpId to head)
		else -> when {
			layer.semantic.tag in faceTags -> faceWarpId to faceFrame
			inferredGroup(layer, anchors) == LayerGroup.HEAD -> headWarpId to head
			else -> breathWarpId to character
		}
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

	private fun buildDrawableGeometry(layer: ClassifiedLayer, data: MeshData, parentFrame: Bounds, faceRig: NinePoseFaceRig): KeyformGrid<MeshDeltaForm> {
		val tag = layer.semantic.tag
		return when (tag) {
			SemanticTag.EYEWHITE, SemanticTag.EYELASH -> eyeClosureGrid(layer, data, parentFrame, faceRig)
			SemanticTag.IRIDES -> eyeClosureGrid(layer, data, parentFrame, faceRig)
			SemanticTag.EYEBROW -> eyebrowGrid(layer, data, parentFrame)
			SemanticTag.MOUTH, SemanticTag.MOUTH_OPEN, SemanticTag.MOUTH_CLOSE -> mouthGrid(data)
			else -> zeroMeshGrid(data.mesh.positions.size)
		}
	}

	private fun eyeClosureGrid(layer: ClassifiedLayer, data: MeshData, frame: Bounds, faceRig: NinePoseFaceRig): KeyformGrid<MeshDeltaForm> {
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
					Side.NONE -> if (canvasX >= faceRig.centerX) values[0] else values[1]
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

	private fun ninePoseAxes(): List<KeyformAxis> = listOf(
		axis(StandardParameters.ANGLE_X, *NinePoseFaceRig.angleXKeys),
		axis(StandardParameters.ANGLE_Y, *NinePoseFaceRig.angleYKeys),
	)

	private fun featureWarpId(region: FaceRegion): DeformerId {
		val feature = when (region.feature) {
			FaceFeature.EYE -> "EyeShape"
			FaceFeature.IRIS -> "IrisPreserve"
			FaceFeature.BROW -> "BrowShape"
			FaceFeature.NOSE -> "NoseShape"
			FaceFeature.MOUTH -> "MouthShape"
			FaceFeature.EAR -> "EarOcclusion"
		}
		return DeformerId("Deform$feature${sideToken(region.side)}")
	}

	private fun gazeWarpId(region: FaceRegion): DeformerId = DeformerId("DeformEyeGaze${sideToken(region.side)}")

	private fun featureDisplayName(region: FaceRegion): String {
		val feature = when (region.feature) {
			FaceFeature.EYE -> "眼形二次修正"
			FaceFeature.IRIS -> "瞳孔二维保持"
			FaceFeature.BROW -> "眉形二次修正"
			FaceFeature.NOSE -> "鼻梁 / 鼻尖深度"
			FaceFeature.MOUTH -> "嘴部圆柱曲线"
			FaceFeature.EAR -> "耳朵遮挡"
		}
		return "${sideDisplay(region.side)}$feature"
	}

	private fun sideToken(side: Side): String = when (side) {
		Side.LEFT -> "L"
		Side.RIGHT -> "R"
		Side.NONE -> "Both"
	}

	private fun sideDisplay(side: Side): String = when (side) {
		Side.LEFT -> "左"
		Side.RIGHT -> "右"
		Side.NONE -> "双侧"
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

	private fun layerVisibility(config: PipelineConfig, layerId: String, fallback: Boolean): Boolean {
		config.layerVisibility[layerId]?.let { return it }
		val parentId = when {
			layerId.endsWith(":l") || layerId.endsWith(":r") -> layerId.dropLast(2)
			else -> null
		}
		return parentId?.let(config.layerVisibility::get) ?: fallback
	}

	private fun mapBounds(child: Bounds, parent: Bounds): Bounds = Bounds(
		normalizeX(child.left, parent), normalizeY(child.top, parent),
		normalizeX(child.right, parent), normalizeY(child.bottom, parent),
	)

	private fun normalizeX(x: Float, frame: Bounds): Float = (x - frame.left) / frame.width.coerceAtLeast(1e-4f)
	private fun normalizeY(y: Float, frame: Bounds): Float = (y - frame.top) / frame.height.coerceAtLeast(1e-4f)

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
