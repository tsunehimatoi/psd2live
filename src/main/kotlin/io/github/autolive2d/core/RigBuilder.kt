package io.github.autolive2d.core

import io.github.autolive2d.i18n.tr
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
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.pow
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

	val all: List<Parameter>
		get() = listOf(
			Parameter(ANGLE_X, tr("model.parameter.angleX"), -45f, 45f, 0f),
			Parameter(ANGLE_Y, tr("model.parameter.angleY"), -30f, 30f, 0f),
			Parameter(ANGLE_Z, tr("model.parameter.angleZ"), -30f, 30f, 0f),
			Parameter(BODY_X, tr("model.parameter.bodyX"), -10f, 10f, 0f),
			Parameter(BODY_Y, tr("model.parameter.bodyY"), -10f, 10f, 0f),
			Parameter(BODY_Z, tr("model.parameter.bodyZ"), -10f, 10f, 0f),
			Parameter(EYE_L_OPEN, tr("model.parameter.eyeLOpen"), 0f, 1f, 1f),
			Parameter(EYE_R_OPEN, tr("model.parameter.eyeROpen"), 0f, 1f, 1f),
			Parameter(EYE_BALL_X, tr("model.parameter.eyeBallX"), -1f, 1f, 0f),
			Parameter(EYE_BALL_Y, tr("model.parameter.eyeBallY"), -1f, 1f, 0f),
			Parameter(BROW_L_Y, tr("model.parameter.browLY"), -1f, 1f, 0f),
			Parameter(BROW_R_Y, tr("model.parameter.browRY"), -1f, 1f, 0f),
			Parameter(MOUTH_FORM, tr("model.parameter.mouthForm"), -1f, 1f, 0f),
			Parameter(MOUTH_OPEN, tr("model.parameter.mouthOpen"), 0f, 1f, 0f),
			Parameter(BREATH, tr("model.parameter.breath"), 0f, 1f, 0f),
			Parameter(HAIR_FRONT, tr("model.parameter.hairFront"), -1f, 1f, 0f),
			Parameter(HAIR_BACK, tr("model.parameter.hairBack"), -1f, 1f, 0f),
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
		SemanticTag.TOOTH_T,
		SemanticTag.TOOTH_B,
		SemanticTag.TONGUE,
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
		val orderedLayers = orderMouthLayers(analysis.layers.sortedBy { it.source.order })
		for ((drawIndex, layer) in orderedLayers.withIndex()) {
			val placement = atlas.placementByLayerId[layer.source.id.raw]
			if (placement == null || layer.opaquePixels == 0) {
				warnings += tr("warning.emptyLayerSkipped", layer.source.name)
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
			val mouthAperture = mouthApertureFor(layer)
			val geometryGrid = buildDrawableGeometry(
				layer,
				meshData,
				parentAndFrame.second,
				faceRig,
				mouthAperture,
			)
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
			sourceBoundsByDrawable[id.raw] = neutralValidationBounds(layer, meshData, mouthAperture)
			layerIdByDrawable[id.raw] = layer.source.id.raw
		}

		val drawableByTagSide = drawables.groupBy { drawable ->
			val semantic = classifiedByDrawable.getValue(drawable.id).semantic
			semantic.tag to semantic.side
		}
		val mouthMasks = drawables.filter { drawable ->
			classifiedByDrawable.getValue(drawable.id).semantic.tag in setOf(SemanticTag.MOUTH, SemanticTag.MOUTH_OPEN)
		}
		val maskedDrawables = drawables.map { drawable ->
			val semantic = classifiedByDrawable.getValue(drawable.id).semantic
			when {
				semantic.tag == SemanticTag.IRIDES -> {
					val exact = drawableByTagSide[SemanticTag.EYEWHITE to semantic.side].orEmpty()
					val fallback = drawableByTagSide[SemanticTag.EYEWHITE to Side.NONE].orEmpty()
					drawable.copy(maskedBy = (exact.ifEmpty { fallback }).map { it.id })
				}
				semantic.tag in CharacterAnalyzer.MOUTH_COMPONENT_TAGS -> {
					val layer = classifiedByDrawable.getValue(drawable.id)
					val exact = mouthMasks.filter { mask ->
						val maskSemantic = classifiedByDrawable.getValue(mask.id).semantic
						maskSemantic.side == semantic.side && maskSemantic.variant == semantic.variant
					}
					val sameSide = mouthMasks.filter { classifiedByDrawable.getValue(it.id).semantic.side == semantic.side }
					val fallback = mouthMasks.filter { classifiedByDrawable.getValue(it.id).semantic.side == Side.NONE }
					val masks = exact.ifEmpty { sameSide.ifEmpty { fallback.ifEmpty { mouthMasks } } }
					val nearest = masks.minByOrNull { mask ->
						val bounds = classifiedByDrawable.getValue(mask.id).bounds
						val dx = bounds.centerX - layer.bounds.centerX
						val dy = bounds.centerY - layer.bounds.centerY
						dx * dx + dy * dy
					}
					drawable.copy(maskedBy = listOfNotNull(nearest?.id))
				}
				else -> drawable
			}
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
				tr("model.part.head"),
				listOf(
					OrgChild.Part(backHairPartId),
					OrgChild.Part(facePartId),
					OrgChild.Part(frontHairPartId),
					OrgChild.Part(headAccessoryPartId),
				),
				groupMode = PartGroupMode.PassThrough,
			),
			Part(backHairPartId, tr("model.part.backHair"), headChildrenFor { it == SemanticTag.BACK_HAIR }, groupMode = PartGroupMode.PassThrough),
			Part(facePartId, tr("model.part.face"), headChildrenFor { it in faceTags }, groupMode = PartGroupMode.PassThrough),
			Part(frontHairPartId, tr("model.part.frontHair"), headChildrenFor { it == SemanticTag.FRONT_HAIR }, groupMode = PartGroupMode.PassThrough),
			Part(
				headAccessoryPartId,
				tr("model.part.headAccessories"),
				headChildrenFor { it !in faceTags && it != SemanticTag.FRONT_HAIR && it != SemanticTag.BACK_HAIR },
				groupMode = PartGroupMode.PassThrough,
			),
			Part(extraPartId, tr("model.part.extra"), childrenFor(LayerGroup.EXTRA), groupMode = PartGroupMode.PassThrough),
			Part(bodyPartId, tr("model.part.body"), childrenFor(LayerGroup.BODY) + childrenFor(LayerGroup.UNKNOWN), groupMode = PartGroupMode.PassThrough),
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
		val boundedStrength = strength.coerceIn(0f, 4f)
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
		val body = Deformer.Warp(bodyWarpId, tr("model.deformer.body"), null, bodyPartId, 6, 4, true, bodyGrid)

		val breathGrid = warpGrid(
			listOf(axis(StandardParameters.BODY_Z, -10f, 0f, 10f), axis(StandardParameters.BREATH, 0f, 0.5f, 1f)),
			columns = 4,
			rows = 6,
		) { u, v, values ->
			bodySecondaryWarpPoint(u, v, values[0], values[1], config.bodyStrength)
		}
		val breath = Deformer.Warp(breathWarpId, tr("model.deformer.breath"), bodyWarpId, bodyPartId, 6, 4, true, breathGrid)

		val headPivotX = faceRig.centerX
		val headPivotY = faceRig.mouthLineY
		val headPivotLocalX = normalizeX(headPivotX, character)
		val headPivotLocalY = normalizeY(headPivotY, character)
		val rotationGrid = oneDimGrid(StandardParameters.ANGLE_Z, floatArrayOf(-30f, 0f, 30f)) { value ->
			RotationPivotForm(headPivotLocalX, headPivotLocalY, value, 1f)
		}
		val rotation = Deformer.Rotation(headRotationId, tr("model.deformer.headRotation"), breathWarpId, headPartId, 0f, rotationGrid)

		// A real head container separates skull-following content from the facial surface.  It is the
		// sole pixel-space child of the rotation deformer; all descendants use ordinary normalized
		// warp coordinates.  Face, front hair and back hair are siblings below this node.
		val headGrid = warpGrid(ninePoseAxes(), columns = 4, rows = 5) { u, v, values ->
			headContainerPoint(head, headPivotX, headPivotY, u, v, values[0], values[1], config.headTurnStrength)
		}
		val headContainer = Deformer.Warp(headWarpId, tr("model.deformer.headContainer"), headRotationId, headPartId, 5, 4, true, headGrid)

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
		val face = Deformer.Warp(faceWarpId, tr("model.deformer.face"), headWarpId, facePartId, 8, 8, true, faceGrid)

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
			deformers += hairFollowWarp(frontHairFollowWarpId, tr("model.deformer.frontHairFollow"), frame, head, frontHairPartId, 0.014f, -0.006f)
			deformers += hairPhysicsWarp(
				frontHairPhysicsWarpId,
				tr("model.deformer.frontHairPhysics"),
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
			deformers += hairFollowWarp(backHairFollowWarpId, tr("model.deformer.backHairFollow"), frame, head, backHairPartId, -0.018f, 0.004f)
			deformers += hairPhysicsWarp(
				backHairPhysicsWarpId,
				tr("model.deformer.backHairPhysics"),
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
		return Deformer.Warp(gazeWarpId(region), tr("model.deformer.gaze", sideDisplay(region.side)), parent, part, 2, 2, true, geometry)
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
		SemanticTag.MOUTH, SemanticTag.MOUTH_OPEN, SemanticTag.MOUTH_CLOSE,
		SemanticTag.TOOTH_T, SemanticTag.TOOTH_B, SemanticTag.TONGUE ->
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
			SemanticTag.IRIDES, SemanticTag.EYELASH, SemanticTag.EYEWHITE, SemanticTag.EYEBROW,
			SemanticTag.MOUTH, SemanticTag.MOUTH_OPEN, SemanticTag.MOUTH_CLOSE,
			SemanticTag.TOOTH_T, SemanticTag.TOOTH_B, SemanticTag.TONGUE -> 0.45f
			else -> 1f
		}
		val effectiveSpacing = max(12f, spacing * semanticDensity)
		// Authored tooth layers may contain several disconnected teeth. Keep their complete texture;
		// the mouth clipping id supplies the visible boundary.
		if (layer.semantic.tag in setOf(SemanticTag.TOOTH_T, SemanticTag.TOOTH_B)) {
			return buildRectangularFallbackMesh(layer, parentFrame, placement, atlasSize, effectiveSpacing)
		}
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

	private fun buildDrawableGeometry(
		layer: ClassifiedLayer,
		data: MeshData,
		parentFrame: Bounds,
		faceRig: NinePoseFaceRig,
		mouthAperture: Bounds?,
	): KeyformGrid<MeshDeltaForm> {
		val tag = layer.semantic.tag
		return when (tag) {
			SemanticTag.EYEWHITE, SemanticTag.EYELASH -> eyeClosureGrid(layer, data, parentFrame, faceRig)
			SemanticTag.IRIDES -> eyeClosureGrid(layer, data, parentFrame, faceRig)
			SemanticTag.EYEBROW -> eyebrowGrid(layer, data, parentFrame)
			SemanticTag.MOUTH, SemanticTag.MOUTH_OPEN -> mouthWholeGrid(data, parentFrame, mouthAperture ?: layer.bounds)
			SemanticTag.MOUTH_CLOSE, SemanticTag.TOOTH_T, SemanticTag.TOOTH_B, SemanticTag.TONGUE ->
				zeroMeshGrid(data.mesh.positions.size)
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

	/**
	 * The mouth bitmap is authored fully open. ParamMouthOpenY=1 preserves it exactly; zero compresses
	 * the complete drawable to a roughly one-pixel seam. Optional teeth and tongue are intentionally
	 * not morphed: the animated mouth drawable clips them and their opacity fades near the closed key.
	 */
	private fun mouthWholeGrid(data: MeshData, parentFrame: Bounds, aperture: Bounds): KeyformGrid<MeshDeltaForm> =
		grid(
			listOf(
				axis(StandardParameters.MOUTH_FORM, -1f, 0f, 1f),
				axis(StandardParameters.MOUTH_OPEN, 0f, 0.5f, 1f),
			),
		) { values ->
			val delta = FloatArray(data.mesh.positions.size)
			for (index in data.canvasPositions.indices step 2) {
				val sourceX = data.canvasPositions[index]
				val sourceY = data.canvasPositions[index + 1]
				val target = mouthWholePoint(sourceX, sourceY, aperture, values[0], values[1])
				delta[index] = (target.first - sourceX) / parentFrame.width.coerceAtLeast(1e-4f)
				delta[index + 1] = (target.second - sourceY) / parentFrame.height.coerceAtLeast(1e-4f)
			}
			MeshDeltaForm(delta)
		}

	internal fun mouthWholePoint(
		sourceX: Float,
		sourceY: Float,
		aperture: Bounds,
		mouthForm: Float,
		mouthOpen: Float,
	): Pair<Float, Float> {
		val open = mouthOpen.coerceIn(0f, 1f)
		val easedOpen = open * open * (3f - 2f * open)
		val form = mouthForm.coerceIn(-1f, 1f)
		val halfWidth = (aperture.width * 0.5f).coerceAtLeast(1e-4f)
		val normalizedX = ((sourceX - aperture.centerX) / halfWidth).coerceIn(-1.25f, 1.25f)
		val horizontalScale = 0.92f + easedOpen * 0.08f + form * 0.07f
		val targetX = aperture.centerX + (sourceX - aperture.centerX) * horizontalScale
		val seamY = aperture.top + aperture.height * 0.48f
		val closedScale = (1.25f / aperture.height.coerceAtLeast(1f)).coerceIn(0.018f, 0.12f)
		val verticalScale = closedScale + easedOpen * (1f - closedScale)
		val cornerWeight = abs(normalizedX).toDouble().pow(1.55).toFloat().coerceAtMost(1.35f)
		val expressionY = -form * aperture.height * (0.018f + cornerWeight * 0.105f) * (0.72f + easedOpen * 0.28f)
		val targetY = seamY + (sourceY - seamY) * verticalScale + expressionY
		return targetX to targetY
	}

	internal fun zeroMeshGrid(size: Int): KeyformGrid<MeshDeltaForm> =
		KeyformGrid(emptyList(), listOf(KeyformCell(IntArray(0), MeshDeltaForm(FloatArray(size)))))

	private fun buildChannels(layer: ClassifiedLayer): ChannelGrids {
		val opacityGrid = when (layer.semantic.tag) {
			SemanticTag.EYE_CLOSE -> {
				val parameter = if (layer.semantic.side == Side.LEFT) StandardParameters.EYE_L_OPEN else StandardParameters.EYE_R_OPEN
				scalarGrid(parameter, floatArrayOf(0f, 1f)) { open -> 1f - open }
			}
			SemanticTag.MOUTH_CLOSE -> scalarGrid(StandardParameters.MOUTH_OPEN, floatArrayOf(0f, 1f)) { 1f - it }
			SemanticTag.TONGUE, SemanticTag.TOOTH_T, SemanticTag.TOOTH_B ->
				scalarGrid(StandardParameters.MOUTH_OPEN, floatArrayOf(0f, 0.15f, 1f)) { open ->
					(open / 0.15f).coerceIn(0f, 1f)
				}
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
			group("ParamGroupFace", tr("model.group.face"), listOf(StandardParameters.ANGLE_X, StandardParameters.ANGLE_Y, StandardParameters.ANGLE_Z)),
			group("ParamGroupEyes", tr("model.group.eyes"), listOf(StandardParameters.EYE_L_OPEN, StandardParameters.EYE_R_OPEN, StandardParameters.EYE_BALL_X, StandardParameters.EYE_BALL_Y)),
			group("ParamGroupBrows", tr("model.group.brows"), listOf(StandardParameters.BROW_L_Y, StandardParameters.BROW_R_Y)),
			group("ParamGroupMouth", tr("model.group.mouth"), listOf(StandardParameters.MOUTH_FORM, StandardParameters.MOUTH_OPEN)),
			group("ParamGroupBody", tr("model.group.body"), listOf(StandardParameters.BODY_X, StandardParameters.BODY_Y, StandardParameters.BODY_Z, StandardParameters.BREATH)),
			group("ParamGroupPhysics", tr("model.group.physics"), listOf(StandardParameters.HAIR_FRONT, StandardParameters.HAIR_BACK)),
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
			FaceFeature.EYE -> tr("model.feature.eye")
			FaceFeature.IRIS -> tr("model.feature.iris")
			FaceFeature.BROW -> tr("model.feature.brow")
			FaceFeature.NOSE -> tr("model.feature.nose")
			FaceFeature.MOUTH -> tr("model.feature.mouth")
			FaceFeature.EAR -> tr("model.feature.ear")
		}
		return tr("model.feature.name", sideDisplay(region.side), feature)
	}

	private fun sideToken(side: Side): String = when (side) {
		Side.LEFT -> "L"
		Side.RIGHT -> "R"
		Side.NONE -> "Both"
	}

	private fun sideDisplay(side: Side): String = tr("side.${side.name.lowercase()}")

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

	private fun mouthApertureFor(layer: ClassifiedLayer): Bounds? {
		if (layer.semantic.tag in setOf(SemanticTag.MOUTH, SemanticTag.MOUTH_OPEN)) return layer.bounds
		return null
	}

	private fun neutralValidationBounds(layer: ClassifiedLayer, data: MeshData, mouthAperture: Bounds?): Bounds {
		if (layer.semantic.tag !in setOf(SemanticTag.MOUTH, SemanticTag.MOUTH_OPEN) || mouthAperture == null) return layer.bounds
		var left = Float.POSITIVE_INFINITY
		var top = Float.POSITIVE_INFINITY
		var right = Float.NEGATIVE_INFINITY
		var bottom = Float.NEGATIVE_INFINITY
		for (index in data.canvasPositions.indices step 2) {
			val point = mouthWholePoint(
				data.canvasPositions[index],
				data.canvasPositions[index + 1],
				mouthAperture,
				mouthForm = 0f,
				mouthOpen = 0f,
			)
			left = minOf(left, point.first)
			top = minOf(top, point.second)
			right = maxOf(right, point.first)
			bottom = maxOf(bottom, point.second)
		}
		return Bounds(left, top, right, bottom)
	}

	/** Places explicitly named mouth internals directly above their nearest mouth, independent of PSD order. */
	private fun orderMouthLayers(layers: List<ClassifiedLayer>): List<ClassifiedLayer> {
		val mouths = layers.filter { it.semantic.tag in setOf(SemanticTag.MOUTH, SemanticTag.MOUTH_OPEN) }
		if (mouths.isEmpty()) return layers
		val assigned = linkedMapOf<String, MutableList<ClassifiedLayer>>()
		val assignedInternalIds = mutableSetOf<String>()
		for (internal in layers.filter { it.semantic.tag in CharacterAnalyzer.MOUTH_COMPONENT_TAGS }) {
			val exact = mouths.filter { mouth ->
				mouth.semantic.side == internal.semantic.side && mouth.semantic.variant == internal.semantic.variant
			}
			val sameSide = mouths.filter { it.semantic.side == internal.semantic.side }
			val fallback = mouths.filter { it.semantic.side == Side.NONE }
			val mouth = nearestLayer(internal, exact.ifEmpty { sameSide.ifEmpty { fallback.ifEmpty { mouths } } }) ?: continue
			assigned.getOrPut(mouth.source.id.raw) { mutableListOf() } += internal
			assignedInternalIds += internal.source.id.raw
		}
		return buildList {
			for (layer in layers) {
				if (layer.source.id.raw in assignedInternalIds) continue
				assigned[layer.source.id.raw]
					.orEmpty()
					.sortedWith(compareBy<ClassifiedLayer> { mouthInternalPriority(it.semantic.tag) }.thenBy { it.source.order })
					.forEach(::add)
				add(layer)
			}
		}
	}

	private fun nearestLayer(source: ClassifiedLayer, candidates: List<ClassifiedLayer>): ClassifiedLayer? =
		candidates.minByOrNull { candidate ->
			val dx = candidate.bounds.centerX - source.bounds.centerX
			val dy = candidate.bounds.centerY - source.bounds.centerY
			dx * dx + dy * dy
		}

	private fun mouthInternalPriority(tag: SemanticTag): Int = when (tag) {
		SemanticTag.TOOTH_T, SemanticTag.TOOTH_B -> 0
		SemanticTag.TONGUE -> 1
		else -> 2
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
