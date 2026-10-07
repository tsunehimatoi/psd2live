package io.github.psd2live.core

import io.github.psd2live.i18n.tr
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.umamo.edit.withDeformerDeleted
import org.umamo.edit.withParameterDeleted
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
import org.umamo.runtime.model.DeformPath
import org.umamo.runtime.model.DeformPathPoint
import org.umamo.runtime.model.WarpLatticeForm
import org.umamo.runtime.model.withDerivedRenderRoot
import org.umamo.render.eval.buildDeformerWorlds
import java.text.Normalizer
import java.util.Locale
import java.util.UUID
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
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
	val BODY_LEAN = ParameterId("ParamBodyLean")
	val PROPORTION = ParameterId("ParamProportion")
	val EYE_L_OPEN = ParameterId("ParamEyeLOpen")
	val EYE_R_OPEN = ParameterId("ParamEyeROpen")
	val EYE_BALL_X = ParameterId("ParamEyeBallX")
	val EYE_BALL_Y = ParameterId("ParamEyeBallY")
	val EYE_BALL_FORM = ParameterId("ParamEyeBallForm")
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
			Parameter(BODY_LEAN, tr("model.parameter.bodyLean"), -10f, 10f, 0f),
			Parameter(PROPORTION, tr("model.parameter.proportion"), -10f, 10f, 0f),
			Parameter(EYE_L_OPEN, tr("model.parameter.eyeLOpen"), 0f, 1f, 1f),
			Parameter(EYE_R_OPEN, tr("model.parameter.eyeROpen"), 0f, 1f, 1f),
			Parameter(EYE_BALL_X, tr("model.parameter.eyeBallX"), -1f, 1f, 0f),
			Parameter(EYE_BALL_Y, tr("model.parameter.eyeBallY"), -1f, 1f, 0f),
			Parameter(EYE_BALL_FORM, tr("model.parameter.eyeBallForm"), -1f, 1f, 0f),
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
	val initialHeadAngleZ: Float = 0f,
	/**
	 * The generator's output before [UvBinding] and the skeleton: texture coordinates are layer offsets
	 * and the atlas has no placements, so it - and a content hash of it - does not change when the same
	 * art is packed differently. Null for a rig that was not generated (an imported model).
	 */
	val unbound: PuppetModel? = null,
	/** What the document's generated overrides could not apply as recorded, from the last replay. */
	val overrideIssues: List<GeneratedOverrideIssue> = emptyList(),
	/** Journal entries before a version 2 split that the last replay skipped because they address only what it supersedes. */
	val supersededEntryNotes: List<SupersededEntryNote> = emptyList(),
	/** Split parts the skeleton skinned with this base rig, which their journal records place (see [PrimitiveSkins]). */
	val primitiveSkins: PrimitiveSkins = PrimitiveSkins.None,
) {
	/**
	 * The puppet with v2 stubs removed and side-channel parts spliced in: what the document shows once every
	 * version 2 `art_primitive` record ([ArtPrimitiveV2]) has placed its parts, without the journal's authored
	 * layers. Migration builds compare it in place of [puppet]. Equal to [puppet] when [primitiveSkins] holds no
	 * stubs - every document without v2 records.
	 */
	fun resolvedPuppet(): PuppetModel {
		val skins = primitiveSkins
		if (skins.stubs.isEmpty() && skins.partLayers.isEmpty()) return puppet
		val stubs = skins.stubs
		// Parts textured through this puppet's atlas, as their records place them; a part without a tile keeps canvas units.
		val parts = skins.parts.filter { it.id !in stubs }.map { part ->
			val tile = puppet.atlas.tiles.firstOrNull { it.id == part.atlasTileId }
			val mesh = part.mesh
			if (tile == null || mesh == null) part else {
				val placed = part.copy(texturePage = tile.placement?.pageIndex ?: part.texturePage)
				runCatching { placed.copy(mesh = DrawableMesh(mesh.positions, RasterMeshJournal.TextureCoordinates(puppet, placed).toUvs(mesh.uvs), mesh.indices)) }
					.getOrDefault(placed)
			}
		}
		val partIds = parts.mapTo(HashSet()) { it.id }
		val kept = puppet.drawables.filterNot { it.id in stubs }.map { drawable ->
			skins.generatedMasks[drawable.id]?.let { masks -> drawable.copy(maskedBy = masks.filter { it !in stubs }) } ?: drawable
		}
		val drawables = kept + parts
		val present = drawables.mapTo(HashSet()) { it.id }
		// Each part takes its passenger's place where they share a slot, otherwise it joins its own slot's end.
		val placedParts = HashSet<DrawableId>()
		val tree = puppet.parts.map { owner ->
			val children = owner.children.flatMap { child ->
				val id = (child as? OrgChild.Drawable)?.id ?: return@flatMap listOf(child)
				if (id !in stubs) return@flatMap listOf(child)
				parts.filter { part -> skins.partSlots[part.id] == owner.id && part.id !in placedParts &&
					replaced(part.id, id) }
					.onEach { placedParts += it.id }.map { OrgChild.Drawable(it.id) }
			}
			owner.copy(children = children)
		}.map { owner ->
			val rest = parts.filter { skins.partSlots[it.id] == owner.id && it.id !in placedParts }
			if (rest.isEmpty()) owner else owner.copy(children = owner.children + rest.map { OrgChild.Drawable(it.id) }).also { placedParts += rest.map { it.id } }
		}
		val glues = puppet.glues.filterNot { it.meshA in stubs || it.meshB in stubs } +
			skins.glues.filter { (it.meshA in partIds || it.meshB in partIds) && it.meshA in present && it.meshB in present }
		return puppet.copy(drawables = drawables, parts = tree, glues = glues,
			deformPaths = puppet.deformPaths.filterNot { it.drawableId in stubs } + skins.paths.filter { it.drawableId in partIds },
			vertexGroups = puppet.vertexGroups.filterNot { it.drawableId in stubs }).withDerivedRenderRoot()
	}

	/** Whether v2 part [part] replaces [stub], directly or through records in between. */
	private fun replaced(part: DrawableId, stub: DrawableId): Boolean {
		val resolution = primitiveSkins.replaces
		var current = part
		val seen = HashSet<DrawableId>()
		while (seen.add(current)) { current = resolution[current] ?: return false; if (current == stub) return true }
		return false
	}
}

internal data class MeshData(
	val mesh: DrawableMesh,
	/** Source points expressed in the coordinate system of their parent frame. */
	val rigPositions: FloatArray,
)

object RigBuilder {
	private val bodyWarpId = DeformerId("DeformBodyXY")
	private val breathWarpId = DeformerId("DeformBodyZBreath")
	private val headRotationId = DeformerId("DeformHeadRotation")
	private val headWarpId = DeformerId("DeformHeadContainer")
	private val faceWarpId = DeformerId("DeformFaceNinePose")
	private val faceContourId = DeformerId("DeformFaceContour")
	private val featureDisplacementId = DeformerId("DeformFeatureDisplacement")
	private val frontHairFollowWarpId = DeformerId("DeformHairFrontFollow")
	private val frontHairPhysicsWarpId = DeformerId("DeformHairFrontPhysics")
	private val backHairFollowWarpId = DeformerId("DeformHairBackFollow")
	private val backHairPhysicsWarpId = DeformerId("DeformHairBackPhysics")
	private val eyesWarpId = DeformerId("DeformEyes")
	private val browsWarpId = DeformerId("DeformBrows")
	private val earsWarpId = DeformerId("DeformEars")

	private data class DeformerBuildResult(
		val deformers: List<Deformer>,
		val pairFrames: Map<String, Bounds>,
		val pairedParentByLayerId: Map<String, Pair<DeformerId, Bounds>>,
	)

	/** Everything one layer contributes to the rig: its stored mesh, keyforms and mouth outline. */
	private class LayerMeshParts(
		/** The mesh as it is stored on the drawable: parent-local, or canvas positions with no parent. */
		val mesh: DrawableMesh,
		/** Un-normalized source geometry, still carrying the canvas position of every vertex. */
		val data: MeshData,
		val mouthAperture: Bounds?,
		val mouthPaths: List<DeformPath>,
		val geometryGrid: KeyformGrid<MeshDeltaForm>,
		/** The space the layer's geometry was aligned into, or null when it was left in canvas space. */
		val headSpace: HeadCoordinateSpace?,
		val neutralBounds: Bounds,
	)

	/**
	 * Everything a rig derives from its analysis before a single mesh is built: the frames the
	 * deformers were fitted to, the face rig, the head space and the deformers themselves.
	 *
	 * The frames are the reason this is derived once and kept. Every mesh vertex is stored as a 0..1
	 * position inside its parent deformer's frame, so a rig stays coherent only while all of its
	 * meshes are normalized against the same frames its deformers were laid out on. Deriving a second
	 * context from an edited analysis moves the head, face or hair frame as soon as one painted layer's
	 * bounds change, and every mesh built from the moved frame is silently rescaled against the
	 * deformers that stayed put.
	 */
	internal class RigContext internal constructor(
		/** The rigged analysis with the generated mouth-lip layers stripped, exactly as [build] sees it. */
		val analysis: PipelineAnalysis,
		val character: Bounds,
		val head: Bounds,
		val face: Bounds,
		val frontHair: Bounds?,
		val backHair: Bounds?,
		val headSpace: HeadCoordinateSpace,
		val faceRig: NinePoseFaceRig,
		val eyeWhiteLayers: List<ClassifiedLayer>,
		private val frameByDeformer: Map<String, Bounds>,
		private val pairedParentByLayerId: Map<String, Pair<DeformerId, Bounds>>,
		private val skeletonEnabled: Boolean,
		/** False when the config built no deformers, which leaves every mesh in canvas space. */
		val deformersEnabled: Boolean,
		val deformers: List<Deformer>,
		/** How the figure stands, which places the body, lean and legs warps. */
		val stance: BodyStance,
		/** The frame the body warps span: the body's own parts and the neck. */
		val bodyFrame: Bounds,
	) {
		/** Replace only artwork while keeping every existing deformer and normalization frame. */
		fun withArtwork(input: PipelineAnalysis): RigContext = RigContext(
			analysis = input.copy(
				layers = input.layers.filter { it.source !is MouthLipLayer },
				anchors = analysis.anchors,
				calibration = analysis.calibration,
			),
			character = character,
			head = head,
			face = face,
			frontHair = frontHair,
			backHair = backHair,
			headSpace = headSpace,
			faceRig = faceRig,
			eyeWhiteLayers = eyeWhiteLayers,
			frameByDeformer = frameByDeformer,
			pairedParentByLayerId = pairedParentByLayerId,
			skeletonEnabled = skeletonEnabled,
			deformersEnabled = deformersEnabled,
			deformers = deformers,
			stance = stance,
			bodyFrame = bodyFrame,
		)
		/** The layer expressed in the coordinate system its mesh and its keyforms are authored in. */
		fun rigLayer(layer: ClassifiedLayer): ClassifiedLayer =
			if (skeletonEnabled && layer.semantic.tag in SkeletonAutoBuilder.limbTags) layer
			else layer.riggedIn(analysis.anchors, headSpace)

		/** The space head layers are aligned into, or null when the layer stays in canvas space. */
		fun headSpaceFor(layer: ClassifiedLayer): HeadCoordinateSpace? =
			if (deformersEnabled && !(skeletonEnabled && layer.semantic.tag in SkeletonAutoBuilder.limbTags) &&
				inferredGroup(layer, analysis.anchors) == LayerGroup.HEAD) headSpace else null

		/**
		 * The deformer [layer] hangs under and the frame that deformer was fitted to. An override
		 * re-parents the layer and may name an ancestor warp the user created; everything else takes
		 * the automatically paired deformer, or the parent its semantic tag implies.
		 */
		fun parentAndFrame(layer: ClassifiedLayer, config: PipelineConfig): Pair<DeformerId?, Bounds> {
			val paired = pairedParentByLayerId[layer.source.id.raw] ?: inferredParentAndFrame(layer, config)
			if (!config.parentOverrides.containsKey(layer.source.id.raw)) return paired
			val parentId = config.parentOverrides[layer.source.id.raw]
				?.takeIf { it.isNotBlank() && !it.equals("root", true) }
				?.let(::DeformerId)
				?: return null to character
			return parentId to (resolveFrame(parentId, config) ?: error("Unknown parent coordinate frame: ${parentId.raw}"))
		}

		/**
		 * The frame a deformer normalizes its children against, or null when this rig has none for it.
		 * A deformer the user created has no frame of its own here, so its children inherit the nearest
		 * ancestor that does; a deformer from somewhere other than [build] - an imported rig's - inherits
		 * nothing, and the caller has to recover the frame from the geometry it is replacing.
		 */
		fun resolveFrame(parentId: DeformerId, config: PipelineConfig): Bounds? {
			var id = parentId.raw
			val seen = mutableSetOf<String>()
			while (id !in frameByDeformer && seen.add(id)) {
				// The torso bend is an identity lattice over the breath warp; a bone's rotation takes the canvas.
				if (id.startsWith("DeformSkelTorso")) return bodyFrame
				if (id.startsWith("DeformSkel") || config.rigEdits.skeleton?.bones?.any { it.deformerId == id } == true) {
					return character
				}
				id = config.rigEdits.warpEdits.firstOrNull { it.id == id }?.parentId ?: return null
			}
			return frameByDeformer[id]
		}

		private fun inferredParentAndFrame(layer: ClassifiedLayer, config: PipelineConfig): Pair<DeformerId, Bounds> =
			defaultParentAndFrame(layer, faceRig, analysis.anchors, character, head, face, frontHair, backHair,
				config.rigEdits.skeleton?.enabled == true, stance.legsFrame.takeIf { deformersEnabled }, bodyFrame)
	}

	/**
	 * Derives the rig context of [inputAnalysis].
	 *
	 * Call it once per rig build and keep the result: two contexts derived from different analyses
	 * describe the same deformers' frames only while every layer bound that feeds them is unchanged.
	 */
	internal fun rigContext(inputAnalysis: PipelineAnalysis, config: PipelineConfig, meshCache: PreviewMeshCache? = null): RigContext {
		val splitBaselineIds = config.rigEdits.splitBaselineLayerIds
		// Version 2 records: the aggregates read the resolved set - no superseded layer, parts on their pinned meshes.
		val resolution = PrimitiveResolution.of(config.rigEdits)
		if (splitBaselineIds.isNotEmpty()) {
			// The baseline's frames come from the layers it had when it was frozen; version 2 parts are generated layers
			// of the resolved set like those, and the layers they supersede drop out in the baseline's own context.
			val neededIds = splitBaselineIds + config.rigEdits.calibrationLayerIds + resolution.partByLayer.keys
			val originalLayers = inputAnalysis.source.layers.filter { it.id.raw in neededIds }
			if (originalLayers.isNotEmpty()) {
				val baselineSource = object : org.umamo.format.art.SourceArt {
					override val widthPx = inputAnalysis.source.widthPx
					override val heightPx = inputAnalysis.source.heightPx
					override val groups = inputAnalysis.source.groups
					override val layers = originalLayers
				}
				val baselineConfig = config.copy(
					deletedLayerIds = config.deletedLayerIds - splitBaselineIds,
					rigEdits = config.rigEdits.copy(splitBaselineLayerIds = emptySet()),
				)
				val baselineAnalysis = RigBuildProfile.stage("context: split baseline analyze") { CharacterAnalyzer.analyze(baselineSource, baselineConfig) }
					// Parts as the resolved geometry analysis has them: classified, and never empty (a part a later record
					// superseded is a transparent stand-in whose pinned mesh still frames it).
					.let { analyzed -> if (!resolution.active) analyzed else analyzed.copy(layers = analyzed.layers.map { layer ->
						if (!resolution.isPartLayer(layer.source.id.raw)) layer
						else resolution.classify(layer, baselineConfig).let { it.copy(opaquePixels = it.opaquePixels.coerceAtLeast(1)) }
					}) }
				return rigContext(baselineAnalysis, baselineConfig, meshCache).withArtwork(resolvedArtwork(inputAnalysis, config, meshCache))
			}
		}
		val analysis = RigBuildProfile.stage("context: mesh footprints + anchors") { meshFramedAnalysis(inputAnalysis.copy(
			layers = resolution.aggregates(inputAnalysis.layers.filter { it.source !is MouthLipLayer }),
		), config, meshCache, resolution) }
		val layout = analysis.calibration ?: analysis
		val scaffold = scaffold(analysis, config, stagesOf(meshCache))
		// The eye whites carry their layers, which a reused scaffold must not hand out from an older analysis.
		val eyeWhiteLayers = layout.layers.map { it.riggedIn(layout.anchors, scaffold.faceRig.coordinateSpace) }
			.filter { it.semantic.tag == SemanticTag.EYEWHITE && it.opaquePixels > 0 }
		return RigContext(
			analysis,
			scaffold.character,
			scaffold.head,
			scaffold.face,
			scaffold.frontHair,
			scaffold.backHair,
			scaffold.faceRig.coordinateSpace,
			scaffold.faceRig,
			eyeWhiteLayers,
			scaffold.frameByDeformer,
			scaffold.deformers.pairedParentByLayerId,
			config.rigEdits.skeleton?.enabled == true,
			scaffold.deformersEnabled,
			scaffold.deformers.deformers,
			scaffold.stance,
			scaffold.bodyFrame,
		)
	}

	/**
	 * What a rig derives from its mesh-framed analysis before any mesh: the frames, the face rig, the body's
	 * stance and frame and the deformers. It reads only the layers' metadata (roles, names, order, footprints),
	 * the anchors and the settings, never their pixels. The frames are cheap and derived every build; the
	 * deformers are a stage of their own ([DeformerKey]).
	 */
	private class Scaffold(
		val character: Bounds,
		val head: Bounds,
		val face: Bounds,
		val frontHair: Bounds?,
		val backHair: Bounds?,
		val faceRig: NinePoseFaceRig,
		val deformersEnabled: Boolean,
		val stance: BodyStance,
		val bodyFrame: Bounds,
		val deformers: DeformerBuildResult,
		val frameByDeformer: Map<String, Bounds>,
	)

	private fun scaffold(analysis: PipelineAnalysis, config: PipelineConfig, stages: RigStageCache?): Scaffold {
		val faceStart = System.nanoTime()
		val character = analysis.anchors.character
		val layout = analysis.calibration ?: analysis
		val faceRig = NinePoseFaceRig.from(layout, config.rigTuning)
		val headSpace = faceRig.coordinateSpace
		val rigLayerById = analysis.layers.associate { layer ->
			layer.source.id.raw to layer.riggedIn(analysis.anchors, headSpace)
		}
		val layoutRigLayers = layout.layers.map { it.riggedIn(layout.anchors, headSpace) }
		val headCandidates = layout.layers
			.filter { inferredGroup(it, analysis.anchors) == LayerGroup.HEAD && it.opaquePixels > 0 }
			.map { it.inHeadSpace(headSpace) }
		val head = if (headCandidates.isEmpty()) faceRig.face else headCandidates.map { it.bounds }.reduce(Bounds::union).expanded(0.025f)
		val faceCandidates = layoutRigLayers.filter { it.semantic.tag in faceTags && it.opaquePixels > 0 }
		val face = (faceCandidates.map { it.bounds } + faceRig.face)
			.reduce(Bounds::union)
			.expanded(0.025f)
		val frontHairCandidates = layoutRigLayers.filter { it.semantic.tag == SemanticTag.FRONT_HAIR && it.opaquePixels > 0 }
		val backHairCandidates = layoutRigLayers.filter { it.semantic.tag == SemanticTag.BACK_HAIR && it.opaquePixels > 0 }
		val frontHair = frontHairCandidates.map { it.bounds }.takeIf { it.isNotEmpty() }?.reduce(Bounds::union)?.expanded(0.04f)
		val backHair = backHairCandidates.map { it.bounds }.takeIf { it.isNotEmpty() }?.reduce(Bounds::union)?.expanded(0.04f)

		if (RigBuildProfile.recording) RigBuildProfile.add("scaffold: face rig + frames", System.nanoTime() - faceStart)
		val bodyStart = System.nanoTime()
		val deformersEnabled = !config.meshOnly && config.generateDeformers
		val stance = BodyStance.of(analysis, character, config.rigEdits.skeleton, config.bodyStrength, config.rigTuning)
		val bodyFrame = bodyFrame(analysis, stance, faceRig, character, head, face, frontHair, backHair, config.rigEdits.skeleton?.enabled == true)
		if (RigBuildProfile.recording) RigBuildProfile.add("scaffold: stance + body frame", System.nanoTime() - bodyStart)
		val deformerResult = if (deformersEnabled) stages?.get(RigStageCache.DEFORMERS, 8,
			deformerKey(analysis, rigLayerById, stance, bodyFrame, faceRig, character, head, face, frontHair, backHair, config)) {
			deformerStage(analysis, stance, bodyFrame, rigLayerById, faceRig, character, head, face, frontHair, backHair, config)
		} ?: deformerStage(analysis, stance, bodyFrame, rigLayerById, faceRig, character, head, face, frontHair, backHair, config) else {
			DeformerBuildResult(emptyList(), emptyMap(), emptyMap())
		}

		val frameByDeformer = mutableMapOf<String, Bounds>()
		frameByDeformer[bodyWarpId.raw] = bodyFrame
		frameByDeformer[BodyStance.leanWarpId.raw] = bodyFrame
		stance.legsFrame?.let { frameByDeformer[BodyStance.legsWarpId.raw] = it }
		frameByDeformer[breathWarpId.raw] = bodyFrame
		frameByDeformer[headRotationId.raw] = character
		frameByDeformer[headWarpId.raw] = head
		frameByDeformer[faceWarpId.raw] = face
		frameByDeformer[faceContourId.raw] = face
		frameByDeformer[featureDisplacementId.raw] = face
		for (region in faceRig.regions) {
			frameByDeformer[featureWarpId(region).raw] = region.bounds
			if (region.feature == FaceFeature.IRIS) {
				frameByDeformer[gazeWarpId(region).raw] = region.bounds
			}
		}
		frontHair?.let {
			frameByDeformer[frontHairFollowWarpId.raw] = it
			frameByDeformer[frontHairPhysicsWarpId.raw] = it
		}
		backHair?.let {
			frameByDeformer[backHairFollowWarpId.raw] = it
			frameByDeformer[backHairPhysicsWarpId.raw] = it
		}
		frameByDeformer.putAll(deformerResult.pairFrames)
		return Scaffold(character, head, face, frontHair, backHair, faceRig, deformersEnabled, stance, bodyFrame, deformerResult, frameByDeformer)
	}

	/** A layer's metadata: everything about it the scaffold and the mesh stages read except its pixels. */
	private data class LayerMeta(
		val id: String, val name: String, val groupPath: String, val order: Int, val sourceBounds: org.umamo.format.art.LayerBounds,
		val semantic: LayerSemantic, val bounds: Bounds, val centroidX: Float, val centroidY: Float, val opaquePixels: Int,
	)

	private fun ClassifiedLayer.meta() = LayerMeta(source.id.raw, source.name, source.groupPath, source.order, source.bounds,
		semantic, bounds, centroidX, centroidY, opaquePixels)

	/**
	 * The settings a generation stage may read, and only those: the mesh, mouth, rig strength and tuning settings,
	 * the generation mode, the runtime target and (for the scaffold and the deformers) the skeleton. Everything
	 * else - the journal and static edits, per-layer maps (a stage that reads one layer's entry keys it itself),
	 * the generation inputs, visibility, draw orders, texture, physics, motion and export settings - is left at
	 * its default, so changing it keeps the stages' outputs.
	 */
	private fun stageConfig(config: PipelineConfig, skeleton: Boolean): PipelineConfig = PipelineConfig(
		meshOuterMargin = config.meshOuterMargin, meshEdgeMode = config.meshEdgeMode, meshEdgeWidth = config.meshEdgeWidth,
		meshMaxEdgeDistance = config.meshMaxEdgeDistance, meshInteriorDensity = config.meshInteriorDensity,
		meshFillAlgorithm = config.meshFillAlgorithm, meshSuppressBoundaryDiagonals = config.meshSuppressBoundaryDiagonals,
		meshFillParameters = config.meshFillParameters, meshUnits = config.meshUnits, alphaThreshold = config.alphaThreshold,
		headTurnStrength = config.headTurnStrength, bodyStrength = config.bodyStrength, rigTuning = config.rigTuning,
		meshOnly = config.meshOnly, generateDeformers = config.generateDeformers, featureDisplacementEnabled = config.featureDisplacementEnabled,
		mouthOutlineEnabled = config.mouthOutlineEnabled, mouthShape = config.mouthShape, mouthCurve = config.mouthCurve,
		mouthColor = config.mouthColor, mouthThickness = config.mouthThickness,
		hairSimulationFront = config.hairSimulationFront, hairSimulationBack = config.hairSimulationBack, runtimeTarget = config.runtimeTarget,
		rigEdits = if (skeleton) RigEditOverlay.Empty.copy(skeleton = config.rigEdits.skeleton) else RigEditOverlay.Empty,
	)

	private fun deformerStage(
		analysis: PipelineAnalysis, stance: BodyStance, bodyFrame: Bounds, rigLayerById: Map<String, ClassifiedLayer>,
		faceRig: NinePoseFaceRig, character: Bounds, head: Bounds, face: Bounds, frontHair: Bounds?, backHair: Bounds?, config: PipelineConfig,
	): DeformerBuildResult = RigBuildProfile.stage("scaffold: deformers (built)") {
		buildDeformers(analysis, stance, bodyFrame, rigLayerById, faceRig, character, head, face, frontHair, backHair,
			PartId("PartHead"), PartId("PartFace"), PartId("PartHairFront"), PartId("PartHairBack"), PartId("PartHeadAccessories"),
			PartId("PartBody"), PartId("PartExtra"), config)
	}

	/**
	 * What [buildDeformers] reads: the stance, the frames and the face rig by value, the anchors (a layer's
	 * group and default parent), every sided layer (the paired and arm warps: its metadata and rig-space
	 * bounds, in analysis order), the topwear (the torso) and the settings. A layer without a side and not
	 * topwear reaches the deformers only through the frames, so its role or mesh can change and the deformers
	 * stay the same instances.
	 */
	private data class DeformerKey(
		val stance: String, val bodyFrame: Bounds, val character: Bounds, val head: Bounds, val face: Bounds,
		val frontHair: Bounds?, val backHair: Bounds?, val faceRig: List<Any?>, val anchors: RigAnchors,
		val sided: List<Pair<LayerMeta, Bounds>>, val topwear: List<Bounds>, val config: PipelineConfig, val language: String,
	)

	private fun deformerKey(
		analysis: PipelineAnalysis, rigLayerById: Map<String, ClassifiedLayer>, stance: BodyStance, bodyFrame: Bounds,
		faceRig: NinePoseFaceRig, character: Bounds, head: Bounds, face: Bounds, frontHair: Bounds?, backHair: Bounds?, config: PipelineConfig,
	) = DeformerKey(stance.contentKey, bodyFrame, character, head, face, frontHair, backHair, faceSignature(faceRig), analysis.anchors,
		analysis.layers.filter { it.semantic.side == Side.LEFT || it.semantic.side == Side.RIGHT }
			.map { it.meta() to rigLayerById.getValue(it.source.id.raw).bounds },
		analysis.layers.filter { it.semantic.tag == SemanticTag.TOPWEAR && it.opaquePixels > 0 }.map { it.bounds },
		stageConfig(config, skeleton = true), io.github.psd2live.i18n.I18n.currentLanguage.tag)

	/** Use the same generated triangles that will render the texture to place automatic deformers. */
	private fun meshFramedAnalysis(
		analysis: PipelineAnalysis,
		config: PipelineConfig,
		meshCache: PreviewMeshCache?,
		resolution: PrimitiveResolution = PrimitiveResolution.Inactive,
	): PipelineAnalysis {
		val layers = framedLayers(analysis.layers, analysis, config, meshCache, resolution)
		val calibration = analysis.calibration?.let { meshFramedAnalysis(it, config, meshCache) }
		return analysis.copy(
			layers = layers,
			anchors = calibration?.anchors ?: CharacterAnalyzer.anchorsFor(layers),
			calibration = calibration,
		)
	}

	/** [layers] with their mesh footprints as bounds and centroid; a version 2 part's footprint is its pinned mesh. */
	private fun framedLayers(
		layers: List<ClassifiedLayer>, analysis: PipelineAnalysis, config: PipelineConfig, meshCache: PreviewMeshCache?,
		resolution: PrimitiveResolution,
	): List<ClassifiedLayer> {
		val unitScale = MeshResolution.unitScale(config, analysis.source)
		return layers.map { layer ->
			val pinned = resolution.partByLayer[layer.source.id.raw]
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
		fun generate() = adaptiveFootprint(if (meshCache != null) meshCache.generate(width, height, source.raster.rgba, config.alphaThreshold, settings, unitScale)
			else AdaptiveMeshGenerator.generate(width, height, source.raster.rgba, config.alphaThreshold, settings, unitScale), source, ::rectangle)
		val stages = stagesOf(meshCache) ?: return generate()
		val key = FootprintKey(width, height, RasterDigest.of(source.raster.rgba), source.bounds.left, source.bounds.top,
			config.alphaThreshold, settings, unitScale)
		return stages.get(RigStageCache.FOOTPRINT, 512, key) { Footprint(generate()) }.value
	}

	/** The pipeline's stage cache, unless stage caching is off ([RigStageCache.enabled]). */
	private fun stagesOf(meshCache: PreviewMeshCache?): RigStageCache? = meshCache?.stages?.takeIf { RigStageCache.enabled }

	/** A layer's mesh footprint inputs: its pixels, where they sit, and the mesh settings and scale they mesh at. */
	private data class FootprintKey(val width: Int, val height: Int, val digest: String, val left: Int, val top: Int,
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

	private fun ClassifiedLayer.riggedIn(anchors: RigAnchors, headSpace: HeadCoordinateSpace): ClassifiedLayer =
		if (inferredGroup(this, anchors) == LayerGroup.HEAD) inHeadSpace(headSpace) else this

	/** The generated mouth-outline layers of [analysis], keyed by the id their drawable is built from. */
	internal fun generatedMouthLips(analysis: PipelineAnalysis): Map<String, ClassifiedLayer> =
		analysis.layers.filter { it.source is MouthLipLayer }.associateBy { it.source.id.raw }

	/**
	 * The ribbons that outline [layer]'s mouth, rasterized into their own generated layers.
	 *
	 * They are derived geometry: [mouthOutline] walks the same contour the fill mesh is built on, so a
	 * paint commit that repaints the mouth has to rebuild them with it, or the ribbons keep the shape
	 * of the mouth they were drawn for and their texture coordinates leave the slice their regenerated
	 * layer was packed into.
	 */
	private fun mouthLips(
		owner: Drawable,
		layer: ClassifiedLayer,
		data: MeshData,
		parentFrame: Bounds,
		aperture: Bounds?,
		headSpace: HeadCoordinateSpace?,
		atlas: PackedAtlas,
		generatedLips: Map<String, ClassifiedLayer>,
		config: PipelineConfig,
	): List<MouthLip> {
		if (!config.mouthOutlineEnabled || config.meshOnly || aperture == null) return emptyList()
		return (0..1).mapNotNull { side ->
			val lipLayer = generatedLips[MouthLipLayer.idFor(layer.source.id.raw, side)] ?: return@mapNotNull null
			// Only a ribbon whose layer has texture is built; where it landed is the binding's business.
			if (lipLayer.source.id.raw !in atlas.placementByLayerId) return@mapNotNull null
			val (lip, path) = mouthOutline(
				owner,
				data,
				parentFrame,
				aperture,
				config,
				side,
				lipLayer,
				headSpace,
			)
			MouthLip(lip, owner.id, lipLayer, path, neutralLipBounds(data, aperture, config, side, headSpace, lipLayer.bounds))
		}
	}

	/** The deformer a layer's semantic tag implies, and the frame that deformer was fitted to. */
	private fun defaultParentAndFrame(
		layer: ClassifiedLayer,
		faceRig: NinePoseFaceRig,
		anchors: RigAnchors,
		character: Bounds,
		head: Bounds,
		faceFrame: Bounds,
		frontHair: Bounds?,
		backHair: Bounds?,
		skeletonEnabled: Boolean = false,
		legsFrame: Bounds? = null,
		bodyFrame: Bounds = character,
	): Pair<DeformerId, Bounds> = when (layer.semantic.tag) {
		SemanticTag.FACE -> faceContourId to faceFrame
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
		// The legs stand on the floor apart from the body: nothing that moves the body reaches the feet.
		SemanticTag.LEGWEAR, SemanticTag.FOOTWEAR -> legsFrame?.let { BodyStance.legsWarpId to it } ?: (breathWarpId to bodyFrame)
		else -> when {
			layer.semantic.tag in faceTags -> faceWarpId to faceFrame
			inferredGroup(layer, anchors) == LayerGroup.HEAD && !(skeletonEnabled && layer.semantic.tag in SkeletonAutoBuilder.limbTags) ->
				headWarpId to head
			else -> breathWarpId to bodyFrame
		}
	}

	/**
	 * The frame the body warps span: every part that hangs on the breath warp - the torso and its clothes,
	 * the arms, a skirt, a tail - with the torso itself and the neck the head turns on, never the head, its
	 * hair or the legs. A rotation passes on only its pivot, so the head needs nothing of it but the neck.
	 */
	private fun bodyFrame(
		analysis: PipelineAnalysis,
		stance: BodyStance,
		faceRig: NinePoseFaceRig,
		character: Bounds,
		head: Bounds,
		face: Bounds,
		frontHair: Bounds?,
		backHair: Bounds?,
		skeletonEnabled: Boolean,
	): Bounds {
		val torso = stance.torso
		val parts = analysis.layers.filter { layer ->
			layer.opaquePixels > 0 && defaultParentAndFrame(layer, faceRig, analysis.anchors, character, head, face, frontHair, backHair,
				skeletonEnabled, stance.legsFrame).first == breathWarpId
		}.map { it.bounds }
		val pivotY = faceRig.coordinateSpace.toCanvas(faceRig.centerX, faceRig.mouthLineY).second
		val neck = Bounds(torso.centerX - torso.halfWidth, minOf(pivotY, torso.shoulderY) - torso.length * 0.1f,
			torso.centerX + torso.halfWidth, torso.waistY)
		val union = (parts + neck).reduce(Bounds::union)
		val padX = union.width * 0.04f
		val padY = union.height * 0.03f
		return Bounds(union.left - padX, union.top - padY, union.right + padX, union.bottom + padY)
	}

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

	fun build(inputAnalysis: PipelineAnalysis, atlas: PackedAtlas, config: PipelineConfig, meshCache: PreviewMeshCache? = null): BuiltRig =
		RigBuildProfile.stage("build: total") {
			val context = RigBuildProfile.stage("build: context") { rigContext(inputAnalysis, config, meshCache) }
			val ids = RigBuildProfile.stage("build: split ids") { splitStableDrawableIds(inputAnalysis, config) }
			buildWithContext(inputAnalysis, atlas, config, meshCache, context, ids)
		}

	/**
	 * The drawable ids a document split with the layer splitter has to keep.
	 *
	 * The split's live preview keeps every drawable the baseline had under its old id and names each new
	 * piece `ArtMeshSplit<hash of its layer id>`, and everything authored afterwards - geometry, topology,
	 * glue - is journalled against those ids. A plain build would instead number every layer by tag, so
	 * pieces would be renamed and same-tag layers renumbered: an export or a reopen then replays the
	 * journal onto different meshes. So a build of a split document re-derives the baseline's ids the way
	 * the pre-split build assigned them - same layers, same order, same skip rule - and leaves the rest
	 * to the split naming.
	 *
	 * Only when the edits were authored against that naming, though. A split project that was reopened
	 * before this existed got plain names on the rebuild, and anything authored since names those; the
	 * journal itself says which naming it speaks. Empty when the document was never split.
	 */
	private fun splitStableDrawableIds(inputAnalysis: PipelineAnalysis, config: PipelineConfig): Map<String, DrawableId> {
		if (config.rigEdits.splitDrawableIds.isNotEmpty()) {
			return config.rigEdits.splitDrawableIds.mapValues { DrawableId(it.value) }
		}
		val splitBaselineIds = config.rigEdits.splitBaselineLayerIds
		val splitNamed = config.rigEdits.referencesSplitDrawable() ||
			config.drawOrderOverrides.keys.any { it.startsWith(SPLIT_DRAWABLE_PREFIX) }
		if (splitBaselineIds.isEmpty() || !splitNamed) return emptyMap()
		val neededIds = splitBaselineIds + config.rigEdits.calibrationLayerIds
		val originalLayers = inputAnalysis.source.layers.filter { it.id.raw in neededIds }
		if (originalLayers.isEmpty()) return emptyMap()
		val baselineSource = object : org.umamo.format.art.SourceArt {
			override val widthPx = inputAnalysis.source.widthPx
			override val heightPx = inputAnalysis.source.heightPx
			override val groups = inputAnalysis.source.groups
			override val layers = originalLayers
		}
		val baselineConfig = config.copy(
			deletedLayerIds = config.deletedLayerIds - splitBaselineIds,
			rigEdits = config.rigEdits.copy(splitBaselineLayerIds = emptySet()),
		)
		val baseline = MouthLipLayers.prepare(CharacterAnalyzer.analyze(baselineSource, baselineConfig), baselineConfig)
		val counts = mutableMapOf<String, Int>()
		val ids = LinkedHashMap<String, DrawableId>()
		for (layer in orderMouthLayers(baseline.layers.sortedBy { it.source.order })) {
			// The atlas places exactly these layers, and the build names only placed layers.
			if (layer.source.raster.width <= 0 || layer.source.raster.height <= 0 || layer.opaquePixels == 0) continue
			ids[layer.source.id.raw] = uniqueDrawableId(layer, counts)
		}
		return ids
	}

	internal fun buildPreservingDeformers(
		inputAnalysis: PipelineAnalysis,
		atlas: PackedAtlas,
		config: PipelineConfig,
		meshCache: PreviewMeshCache?,
		previousAnalysis: PipelineAnalysis,
		previousConfig: PipelineConfig,
		stableDrawableIds: Map<String, DrawableId>,
	): BuiltRig = buildWithContext(
		inputAnalysis, atlas, config, meshCache,
		rigContext(previousAnalysis, previousConfig, meshCache).withArtwork(resolvedArtwork(inputAnalysis, config, meshCache)), stableDrawableIds,
	)

	/**
	 * [inputAnalysis] as the artwork of a kept context: without the generated mouth lips and - with version 2
	 * records - without the layers they supersede (the drawable loop builds those as passengers), parts framed by
	 * their pinned meshes. Without version 2 records, the mesh-framed layers as they always were.
	 */
	private fun resolvedArtwork(inputAnalysis: PipelineAnalysis, config: PipelineConfig, meshCache: PreviewMeshCache?): PipelineAnalysis {
		val resolution = PrimitiveResolution.of(config.rigEdits)
		return meshFramedAnalysis(inputAnalysis.copy(
			layers = resolution.aggregates(inputAnalysis.layers.filter { it.source !is MouthLipLayer }),
		), config, meshCache, resolution)
	}

	/** Allocate final semantic names before any split mesh can be referenced by an edit. */
	internal fun assignSplitDrawableIds(analysis: PipelineAnalysis, existing: Map<String, DrawableId>): Map<String, DrawableId> {
		val ids = existing.toMutableMap()
		val reserved = existing.values.toMutableSet()
		val counts = mutableMapOf<String, Int>()
		for (layer in orderMouthLayers(analysis.layers.sortedBy { it.source.order })) {
			if (layer.source.id.raw in ids || layer.opaquePixels == 0) continue
			var id = uniqueDrawableId(layer, counts)
			while (id in reserved) id = uniqueDrawableId(layer, counts)
			ids[layer.source.id.raw] = id
			reserved += id
		}
		return ids
	}

	private fun buildWithContext(
		inputAnalysis: PipelineAnalysis,
		atlas: PackedAtlas,
		config: PipelineConfig,
		meshCache: PreviewMeshCache?,
		context: RigContext,
		stableDrawableIds: Map<String, DrawableId>,
	): BuiltRig {
        val generatedLips = generatedMouthLips(inputAnalysis)
		val analysis = context.analysis
		val faceRig = context.faceRig
		val shouldBuildDeformers = context.deformersEnabled
		val warnings = mutableListOf<String>()
		val headPartId = PartId("PartHead")
		val facePartId = PartId("PartFace")
		val frontHairPartId = PartId("PartHairFront")
		val backHairPartId = PartId("PartHairBack")
		val headAccessoryPartId = PartId("PartHeadAccessories")
		val bodyPartId = PartId("PartBody")
		val extraPartId = PartId("PartExtra")
		val rawDeformers = context.deformers
		val stages = stagesOf(meshCache)
		// Version 2 art primitives (see [PrimitiveResolution]): the drawable loop builds the layers the journal
		// before each record addresses - the superseded layers in their slots as passengers, never the parts - so
		// ids and default draw orders are those of a base without the split; the parts are built after it on
		// their pinned meshes and parked for their records. Inactive, every list below is the one it always was.
		val resolution = PrimitiveResolution.of(config.rigEdits)
		val v2 = resolution.active
		val passengers = if (!v2) emptyList() else framedLayers(inputAnalysis.layers.filter {
			it.source !is MouthLipLayer && resolution.isStubLayer(it.source.id.raw)
		}, inputAnalysis, config, meshCache, resolution)
		val partLayers = if (!v2) emptyMap() else (analysis.layers + passengers).filter { resolution.isPartLayer(it.source.id.raw) }
			.associateBy { it.source.id.raw }
		val baseLayers = if (!v2) analysis.layers
			else analysis.layers.filterNot { resolution.isPartLayer(it.source.id.raw) } + passengers.filterNot { resolution.isPartLayer(it.source.id.raw) }
		// The face lean rewrites each warp's own grid and nothing else, so it commutes with the re-parenting below.
		val leaned = stages?.get(RigStageCache.LEAN, 8, Identity(rawDeformers, config.rigTuning)) { withFaceLean(rawDeformers, config.rigTuning) }
			?: withFaceLean(rawDeformers, config.rigTuning)

		val deformers = if (config.parentOverrides.isEmpty()) leaned else {
			val deformerById = rawDeformers.associateBy { it.id.raw }
			leaned.map { deformer ->
				if (config.parentOverrides.containsKey(deformer.id.raw)) {
					val targetParentRaw = config.parentOverrides[deformer.id.raw]
					val targetParentId = targetParentRaw?.takeIf { it.isNotBlank() && !it.equals("root", true) }?.let(::DeformerId)
					if (targetParentId != null && wouldCreateCycle(deformer.id.raw, targetParentId.raw, deformerById, config.parentOverrides)) {
						deformer
					} else {
						deformer.withParent(targetParentId)
					}
				} else deformer
			}
		}

		val idCounts = mutableMapOf<String, Int>()
		val drawables = mutableListOf<Drawable>()
		val pageByDrawable = linkedMapOf<String, Int>()
		val sourceBoundsByDrawable = linkedMapOf<String, Bounds>()
		val layerIdByDrawable = linkedMapOf<String, String>()
        val lipOwnerById = mutableMapOf<DrawableId, DrawableId>()
		val classifiedByDrawable = mutableMapOf<DrawableId, ClassifiedLayer>()
		// Discover custom toggle and switch parameters from overrides and layers
		val customParams = mutableListOf<Parameter>()
		val switchParamKeys = mutableMapOf<String, FloatArray>()

		// 1. Toggles
		val toggleParamNames = analysis.layers.mapNotNull { layer ->
			val override = config.layerOverrides[layer.source.id.raw]
			val type = override?.type ?: layer.semantic.type
			val param = (override?.parameter ?: layer.semantic.parameter).trim()
			if (type == LayerType.TOGGLE && param.isNotBlank()) param else null
		}.distinct().sorted()

		for (paramName in toggleParamNames) {
			customParams += Parameter(
				id = ParameterId(paramName),
				name = paramName,
				min = 0f,
				max = 1f,
				default = 0f,
			)
		}

		// 2. Switches
		val switchLayers = analysis.layers.filter { layer ->
			val override = config.layerOverrides[layer.source.id.raw]
			val type = override?.type ?: layer.semantic.type
			val param = (override?.parameter ?: layer.semantic.parameter).trim()
			type == LayerType.SWITCH && param.isNotBlank()
		}
		val switchLayersByParam = switchLayers.groupBy { layer ->
			val override = config.layerOverrides[layer.source.id.raw]
			(override?.parameter ?: layer.semantic.parameter).trim()
		}

		for ((paramName, layers) in switchLayersByParam) {
			val ids = layers.map { layer ->
				val override = config.layerOverrides[layer.source.id.raw]
				override?.switchId ?: layer.semantic.switchId
			}.distinct().sorted()

			val minId = ids.minOrNull() ?: 0
			val maxId = ids.maxOrNull() ?: 0
			val keys = if (minId == maxId) {
				floatArrayOf(minId.toFloat(), (minId + 1).toFloat())
			} else {
				(minId..maxId).map { it.toFloat() }.toFloatArray()
			}
			switchParamKeys[paramName] = keys
			customParams += Parameter(
				id = ParameterId(paramName),
				name = paramName,
				min = keys.first(),
				max = keys.last(),
				default = keys.first(),
			)
		}

		val builtDeformPaths = mutableListOf<DeformPath>()
		val meshConfig = stageConfig(config, skeleton = false)
		val reservedDrawableIds = stableDrawableIds.values.toMutableSet()
		val orderedLayers = orderMouthLayers(baseLayers.sortedBy { it.source.order })
		// A passenger keys a toggle or switch only while the resolved layers still need its parameter.
		val knownParameters = if (!v2) emptySet() else (StandardParameters.all + customParams).mapTo(HashSet()) { it.id }
		val ghostIds = LinkedHashSet<DrawableId>()
		for ((drawIndex, layer) in orderedLayers.withIndex()) {
			val placement = atlas.placementByLayerId[layer.source.id.raw]
			if (placement == null || layer.opaquePixels == 0) {
				warnings += tr("warning.emptyLayerSkipped", layer.source.name)
				continue
			}
			val rigLayer = context.rigLayer(layer)
			val (parentId, parentFrame) = context.parentAndFrame(layer, config)
			val id = stableDrawableIds[layer.source.id.raw]
				?: if (stableDrawableIds.isEmpty()) uniqueDrawableId(layer, idCounts)
				else if (config.rigEdits.splitDrawableIds.isNotEmpty()) {
					var candidate = uniqueDrawableId(layer, idCounts)
					while (candidate in reservedDrawableIds) candidate = uniqueDrawableId(layer, idCounts)
					candidate
				}
				else stableSplitDrawableId(layer.source.id.raw, stableDrawableIds.values)
			reservedDrawableIds += id
			val parts = meshStage(
				layer,
				rigLayer,
				context,
				// Without deformers there is nothing for the mesh to be local to, so it stays in canvas
				// space and keeps its raw rig positions.
				parentId = if (shouldBuildDeformers) parentId else null,
				parentFrame = parentFrame,
				headSpace = context.headSpaceFor(layer),
				config = config,
				meshConfig = meshConfig,
				meshCache = meshCache,
			)
			builtDeformPaths.addAll(parts.mouthPaths)
			val override = config.layerOverrides[layer.source.id.raw]
			val classification = override?.type ?: layer.semantic.type
			val channelGrids = if (config.meshOnly && classification == LayerType.PRESET) ChannelGrids.Empty
				else channelStage(layer, override, switchParamKeys, config.rigTuning, stages).let { grids ->
					if (v2 && resolution.isStubLayer(layer.source.id.raw) &&
						grids.gridsByChannel.values.any { grid -> grid.axes.any { it.parameterId !in knownParameters } }) ChannelGrids.Empty else grids
				}
			if (v2 && resolution.isStubLayer(layer.source.id.raw)) ghostIds += id
			val drawable = Drawable(
				id = id,
				name = layer.source.name,
				parentDeformerId = if (shouldBuildDeformers) parentId else null,
				blendMode = blendMode(layer.source.blend),
				maskedBy = emptyList(),
				mesh = parts.mesh,
				geometryGrid = parts.geometryGrid,
				channelGrids = channelGrids,
				// Cubism Editor stores draw order as an integer. Keeping this integral also makes
				// fresh CMO3 conversion lossless instead of reporting one advisory per drawable.
				drawOrder = (config.drawOrderOverrides[layer.source.id.raw]
					?: config.drawOrderOverrides[id.raw]
					?: (orderedLayers.size - drawIndex).toFloat()).coerceIn(0f, 1000f),
				opacity = layer.source.opacity.coerceIn(0f, 1f),
				isVisible = layerVisibility(config, layer.source.id.raw, layer.source.visible),
				// Unbound: UvBinding names the page with the uvs.
				texturePage = 0,
				atlasTileId = PuppetSourceAtlas.tileIdFor(layer.source.id.raw),
			)
			drawables += drawable
			classifiedByDrawable[id] = layer
			pageByDrawable[id.raw] = placement.page
			sourceBoundsByDrawable[id.raw] = parts.neutralBounds
			for (lip in lipsStage(drawable, layer, parts, parentFrame, atlas, generatedLips, config, meshConfig, stages)) {
				drawables += lip.drawable
				lip.path?.let { builtDeformPaths += it }
				classifiedByDrawable[lip.drawable.id] = lip.layer
				lipOwnerById[lip.drawable.id] = drawable.id
				pageByDrawable[lip.drawable.id.raw] = atlas.placementByLayerId.getValue(lip.layer.source.id.raw).page
				layerIdByDrawable[lip.drawable.id.raw] = lip.layer.source.id.raw
				sourceBoundsByDrawable[lip.drawable.id.raw] = lip.neutralBounds
			}
			layerIdByDrawable[id.raw] = layer.source.id.raw
		}

		// The parts, after every layer of the base: ids from their records, draw order from the drawable they replace.
		val partDrawables = ArrayList<Drawable>()
		val deferredParts = LinkedHashSet<DrawableId>()
		val partLayerById = LinkedHashMap<DrawableId, String>()
		val partNeutralBounds = LinkedHashMap<DrawableId, Bounds>()
		// The mouth parts of one split open and smile as the one mouth they were: each takes the aperture of all of
		// them, not its own bounds (a half would otherwise shape itself as a whole mouth and tear at the cut).
		val mouthTags = setOf(SemanticTag.MOUTH, SemanticTag.MOUTH_OPEN)
		val mouthApertures = if (!v2) emptyMap() else resolution.parts.mapNotNull { part ->
			if (part.drawableId in resolution.ribbonParts) return@mapNotNull null
			partLayers[part.layerId]?.let { context.rigLayer(it) }?.takeIf { it.semantic.tag in mouthTags }?.let { part.recordIndex to it.bounds }
		}.groupBy({ it.first }, { it.second }).mapValues { (_, bounds) ->
			Bounds(bounds.minOf { it.left }, bounds.minOf { it.top }, bounds.maxOf { it.right }, bounds.maxOf { it.bottom })
		}
		if (v2) for (part in resolution.parts) {
			val layer = partLayers[part.layerId] ?: continue
			if (atlas.placementByLayerId[part.layerId] == null || partDrawables.any { it.id == part.drawableId } ||
				drawables.any { it.id == part.drawableId }) continue
			val baseIds = drawables.mapTo(HashSet()) { it.id }
			val standIn = resolution.baseStandIn(part.drawableId, baseIds)
			val order = config.drawOrderOverrides[part.layerId] ?: config.drawOrderOverrides[part.drawableId.raw]
				?: (drawables.firstOrNull { it.id == standIn } ?: partDrawables.firstOrNull { it.id == standIn })?.drawOrder
				?: org.umamo.runtime.model.DEFAULT_DRAW_ORDER.toFloat()
			val built = pinnedPart(part, layer, context, config, shouldBuildDeformers, switchParamKeys, stages, order,
				atlas.placementByLayerId.getValue(part.layerId).page, mouthApertures[part.recordIndex], part.drawableId in resolution.ribbonParts)
			partDrawables += built.first
			if (part.parent != null) deferredParts += part.drawableId
			classifiedByDrawable[part.drawableId] = layer
			partLayerById[part.drawableId] = part.layerId
			partNeutralBounds[part.drawableId] = built.second
		}

		val assemblyStart = System.nanoTime()
		// Masks are generated among the resolved drawables: a passenger never masks, a part does.
		val maskSources = if (!v2) drawables else drawables.filterNot { it.id in ghostIds } + partDrawables
		val maskTargets = if (!v2) drawables else drawables + partDrawables
		val drawableByTagSide = maskSources.groupBy { drawable ->
			val semantic = classifiedByDrawable.getValue(drawable.id).semantic
			semantic.tag to semantic.side
		}
		val mouthMasks = maskSources.filter { drawable ->
            drawable.id !in lipOwnerById &&
			classifiedByDrawable.getValue(drawable.id).semantic.tag in setOf(SemanticTag.MOUTH, SemanticTag.MOUTH_OPEN)
		}
		val maskedAll = maskTargets.map { drawable ->
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
        }.let { masked ->
            masked.map { drawable ->
                val owner = lipOwnerById[drawable.id]
                if (owner == null) drawable else {
                    val frontOrder = masked.filter { it.id == owner || owner in it.maskedBy }
                        .maxOfOrNull { it.drawOrder } ?: drawable.drawOrder
                    drawable.copy(drawOrder = (config.drawOrderOverrides[classifiedByDrawable.getValue(drawable.id).source.id.raw]
                        ?: config.drawOrderOverrides[drawable.id.raw] ?: (frontOrder + 1f)).coerceIn(0f, 1000f))
                }
            }

		}

		// The base is the world before the records: a mask naming a part names the drawable it replaces there, and
		// the generated list goes to the side channel for the record to restore.
		val generatedMasks = LinkedHashMap<DrawableId, List<DrawableId>>()
		val baseDrawableIds = if (!v2) emptySet() else drawables.mapTo(HashSet()) { it.id }
		val maskedDrawables = if (!v2) maskedAll else maskedAll.filter { it.id in baseDrawableIds }.map { drawable ->
			val rewritten = drawable.maskedBy.map { resolution.baseStandIn(it, baseDrawableIds) }.distinct()
			if (rewritten == drawable.maskedBy) drawable else {
				generatedMasks[drawable.id] = drawable.maskedBy
				drawable.copy(maskedBy = rewritten)
			}
		}
		val maskedParts = if (!v2) emptyList() else maskedAll.filter { it.id in partLayerById }

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
		// Each part's slot in the part tree, as [childrenFor] and [headChildrenFor] would give it.
		val partSlots = LinkedHashMap<DrawableId, PartId>()
		for (part in maskedParts) {
			val layer = classifiedByDrawable.getValue(part.id)
			partSlots[part.id] = when (inferredGroup(layer, analysis.anchors)) {
				LayerGroup.HEAD -> when {
					layer.semantic.tag == SemanticTag.BACK_HAIR -> backHairPartId
					layer.semantic.tag in faceTags -> facePartId
					layer.semantic.tag == SemanticTag.FRONT_HAIR -> frontHairPartId
					else -> headAccessoryPartId
				}
				LayerGroup.EXTRA -> extraPartId
				else -> bodyPartId
			}
		}
		val standardIds = StandardParameters.all.map { it.id }.toSet()
		val uniqueCustomParams = customParams.filter { it.id !in standardIds }
		val parameterTree = parameterTree(uniqueCustomParams)
		// Geometry is generated against the art alone: tiles without placements, offsets for uvs.
		val (unboundAtlas, artSources) = UvBinding.unboundAtlas(inputAnalysis,
			inputAnalysis.layers.map { it.source.id.raw }.filter { it in atlas.placementByLayerId })
		val assembled = PuppetModel(
			parameters = StandardParameters.all + uniqueCustomParams,
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
			// Compatibility baseline comes from the export dialog's SDK target.
			runtimeTarget = config.runtimeTarget,
			atlas = unboundAtlas,
			sources = artSources,
			deformPaths = builtDeformPaths,
		)
		if (RigBuildProfile.recording) RigBuildProfile.add("assembly: masks, parts, parameters, model", System.nanoTime() - assemblyStart)
		val skeleton = config.rigEdits.skeleton?.takeIf { it.enabled && shouldBuildDeformers }?.let { withoutBindings(it, journalPlaced(it, config.rigEdits.authoringJournal)) }
		val faceCenterCanvas = faceRig.coordinateSpace.toCanvas(faceRig.centerX, faceRig.centerY)
		var skeletonKey: String? = null
		fun finish(): BuiltRig {
			val unbound = assembled.withDerivedRenderRoot().let { withoutLegacyHairSway(it, config) }
			// The skeleton bakes the unbound rig: the joint rows it inserts interpolate layer offsets, which the
			// binding then turns into page uvs like every other vertex. So the bake never reads the atlas, and
			// moving, scaling or repacking tiles reuses it. Split parts its bones bind are skinned with it and handed
			// to their journal records (see [PrimitiveSkins]); they texture in canvas units, which interpolate alike.
			val parts = skeleton?.let { skinnablePrimitives(unbound, it, config) }.orEmpty()
			if (v2) {
				// Version 2 parts bake with the base - a deferred-parent part has no skin - and passengers never bind.
				val baked = maskedParts.filter { it.id !in deferredParts }
				val locked = handEditedTopology(config) + resolution.parts.filter { it.fixedTopology }.map { it.drawableId.raw }
				val spec = skeleton?.let { withoutBindings(it, ghostIds) }
				val withParts = unbound.copy(drawables = unbound.drawables + parts + baked)
				val skeletal = spec?.let { RigBuildProfile.stage("skeleton") {
					SkeletonRig.takeLastKey()
					SkeletonRig.generate(withParts, it, context.bodyFrame, locked, context.stance).also { skeletonKey = SkeletonRig.takeLastKey() }
				} } ?: withParts
				val (skinnedBase, skins) = parked(skeletal, parts.mapTo(HashSet()) { it.id }, maskedParts, deferredParts, resolution,
					generatedMasks, partSlots, partLayerById, partNeutralBounds)
				val skeletonPuppet = RigBuildProfile.stage("binding: UvBinding.bind") {
					UvBinding.bind(skinnedBase, inputAnalysis, atlas) { classifiedByDrawable[it.id] }.puppet
				}
				return BuiltRig(skeletonPuppet, pageByDrawable, sourceBoundsByDrawable, layerIdByDrawable, faceCenterCanvas.first,
					faceCenterCanvas.second, faceRig.radiusX, faceRig.radiusY, warnings, faceRig.initialAngleZ, unbound, primitiveSkins = skins)
			}
			val skeletal = skeleton
				?.let { RigBuildProfile.stage("skeleton") {
					SkeletonRig.takeLastKey()
					SkeletonRig.generate(unbound.copy(drawables = unbound.drawables + parts), it, context.bodyFrame, handEditedTopology(config),
						context.stance).also { skeletonKey = SkeletonRig.takeLastKey() }
				} } ?: unbound
			val (skinnedBase, skins) = withoutSkinnedPrimitives(skeletal, parts.mapTo(HashSet()) { it.id })
			val skeletonPuppet = RigBuildProfile.stage("binding: UvBinding.bind") {
				UvBinding.bind(skinnedBase, inputAnalysis, atlas) { classifiedByDrawable[it.id] }.puppet
			}
			return BuiltRig(
				skeletonPuppet,
				pageByDrawable,
				sourceBoundsByDrawable,
				layerIdByDrawable,
				faceCenterCanvas.first,
				faceCenterCanvas.second,
				faceRig.radiusX,
				faceRig.radiusY,
				warnings,
				faceRig.initialAngleZ,
				unbound,
				primitiveSkins = skins,
			)
		}
		if (stages == null) return finish()
		// The assembled rig, the binding's and the skeleton's inputs and the rest of the result: equal to an earlier
		// build's, that build's very instance comes back, so replay checkpoints keyed by it keep hitting.
		val key = BoundKey(assembled, config.hairSimulationFront, config.hairSimulationBack, atlas.placementByLayerId,
			atlas.pages.map { it.image.width to it.image.height }, inputAnalysis.layers.map(::bindingMeta),
			classifiedByDrawable.entries.associate { it.key.raw to it.value.source.id.raw },
			skeleton?.let { listOf(it, handEditedTopology(config), context.bodyFrame, context.stance.contentKey, SkeletonRig.clears,
				skinnedRecords(it, config)) },
			listOf(pageByDrawable, sourceBoundsByDrawable, layerIdByDrawable, warnings.toList(), faceCenterCanvas,
				faceRig.radiusX, faceRig.radiusY, faceRig.initialAngleZ),
			io.github.psd2live.i18n.I18n.currentLanguage.tag,
			if (!v2) null else listOf(resolution.resolved.contentKey, maskedParts, deferredParts, generatedMasks, partSlots,
				partNeutralBounds, ghostIds))
		val bound = stages.get(RigStageCache.BOUND, 4, key) { finish() to skeletonKey }
		// Reusing the rig reuses its skeleton bake: keep that bake the most recent, as asking the skeleton cache would.
		bound.second?.let(SkeletonRig::touch)
		return bound.first
	}

	/** Everything [UvBinding.bind] reads of a layer: its source inventory entry and the space its offsets bind in. */
	private fun bindingMeta(layer: ClassifiedLayer): List<Any?> {
		val source = layer.source
		return listOf(source.id.raw, source.name, source.groupPath, source.bounds, source.visible, source.idIsStable,
			LayerTexture.generatorSpace(layer), source.textureLayer.raster.width, source.textureLayer.raster.height)
	}

	/** The inputs of the binding and the skeleton, and the assembled rig they run on; see [buildWithContext]. */
	private data class BoundKey(
		val assembled: PuppetModel, val hairFront: Boolean, val hairBack: Boolean, val placements: Map<String, AtlasPlacement>,
		val pages: List<Pair<Int, Int>>, val layers: List<List<Any?>>, val layerOfDrawable: Map<String, String>,
		val skeleton: List<Any?>?, val result: List<Any?>, val language: String,
		/** Version 2 parts and what is parked with them; null without v2 records. */
		val primitives: List<Any?>? = null,
	)

	/** A key part compared by identity: a stage output that later stages reuse while it is the same instance. */
	private class Identity(val value: Any, val extra: Any? = null) {
		override fun equals(other: Any?) = other is Identity && value === other.value && extra == other.extra
		override fun hashCode() = System.identityHashCode(value) * 31 + (extra?.hashCode() ?: 0)
	}

	/** [buildChannels] under a key of what it reads. */
	private fun channelStage(layer: ClassifiedLayer, override: LayerClassificationOverride?, switchParamKeys: Map<String, FloatArray>,
	                         tuning: RigTuning, stages: RigStageCache?): ChannelGrids {
		if (stages == null) return buildChannels(layer, override, switchParamKeys, tuning)
		val parameter = (override?.parameter ?: layer.semantic.parameter).trim()
		val key = listOf(override?.type ?: layer.semantic.type, parameter, override?.switchId ?: layer.semantic.switchId,
			switchParamKeys[parameter]?.toList(), layer.semantic.tag, layer.semantic.side, tuning.teethFade)
		return stages.get(RigStageCache.CHANNELS, 256, key) { buildChannels(layer, override, switchParamKeys, tuning) }
	}

	/**
	 * [mouthLips] under a key of what it reads: the owner drawable and the mesh stage output it outlines (by
	 * instance), the frame, the lip layers' metadata, pixels and whether they are packed, and the settings.
	 */
	private fun lipsStage(
		owner: Drawable, layer: ClassifiedLayer, parts: LayerMeshParts, parentFrame: Bounds, atlas: PackedAtlas,
		generatedLips: Map<String, ClassifiedLayer>, config: PipelineConfig, meshConfig: PipelineConfig, stages: RigStageCache?,
	): List<MouthLip> {
		val build = { RigBuildProfile.stage("mesh: mouth lips (built)") {
			mouthLips(owner, layer, parts.data, parentFrame, parts.mouthAperture, parts.headSpace, atlas, generatedLips, config)
		} }
		if (stages == null) return build()
		val lips = (0..1).map { side -> generatedLips[MouthLipLayer.idFor(layer.source.id.raw, side)]?.let { lip ->
			listOf(lip.meta(), RasterDigest.of(lip.source.raster.rgba), lip.source.raster.width, lip.source.raster.height,
				lip.source.id.raw in atlas.placementByLayerId, config.drawOrderOverrides[lip.source.id.raw],
				layerVisibility(config, lip.source.id.raw, lip.source.visible))
		} }
		val key = listOf(owner, layer.source.id.raw, Identity(parts), parentFrame, lips, meshConfig, io.github.psd2live.i18n.I18n.currentLanguage.tag)
		// A kept lip carries the layer of the build that made it; hand out this build's layer of the same content.
		return stages.get(RigStageCache.LIPS, 64, key, build).map { lip ->
			val current = generatedLips[lip.layer.source.id.raw] ?: lip.layer
			if (current === lip.layer) lip else MouthLip(lip.drawable, lip.ownerId, current, lip.path, lip.neutralBounds)
		}
	}


	/**
	 * A hair kind the model preset simulates drops its legacy sway: the physics warp unwraps into the follow
	 * warp, which has the same frame, and its parameter goes, so the simulation bake is the only motion. The
	 * parameter goes first: that collapses the warp to its rest lattice, which the unwrap then maps through.
	 */
	private fun withoutLegacyHairSway(puppet: PuppetModel, config: PipelineConfig): PuppetModel {
		var model = puppet
		if (config.hairSimulationFront) {
			model = model.withParameterDeleted(StandardParameters.HAIR_FRONT).withDeformerDeleted(frontHairPhysicsWarpId)
		}
		if (config.hairSimulationBack) {
			model = model.withParameterDeleted(StandardParameters.HAIR_BACK).withDeformerDeleted(backHairPhysicsWarpId)
		}
		return model
	}

	/** Drawables whose topology the user edited by hand; the skeleton must not renumber their vertices. */
	/** The `art_primitive` records with a part a bone of [skeleton] binds: what [skinnablePrimitives] reads of the journal. */
	private fun skinnedRecords(skeleton: SkeletonSpec, config: PipelineConfig): List<kotlinx.serialization.json.JsonObject> {
		val bound = skeleton.bones.flatMapTo(HashSet()) { it.drawableIds }
		return ArtPrimitiveJournal.commands(config.rigEdits).filter { command ->
			ArtPrimitiveJournal.primitives(command).any { it["id"]?.jsonPrimitive?.contentOrNull in bound }
		}
	}

	/**
	 * The split parts [skeleton] binds that are not in the base rig [model]: a split makes its parts in the journal,
	 * after the bake, so they are decoded from their records for the bake to skin.
	 */
	private fun skinnablePrimitives(model: PuppetModel, skeleton: SkeletonSpec, config: PipelineConfig): List<Drawable> {
		val bound = skeleton.bones.flatMapTo(HashSet()) { it.drawableIds }
		val parts = ArrayList<Drawable>()
		val records = ArtPrimitiveJournal.commands(config.rigEdits)
		for (command in skinnedRecords(skeleton, config)) {
			// The records before this one place a part whose recorded parent the base no longer generates.
			val earlier = records.subList(0, records.indexOfFirst { it === command }.coerceAtLeast(0))
			for (part in ArtPrimitiveJournal.skinnable(model, command, bound, earlier)) if (parts.none { it.id == part.id }) parts += part
		}
		return parts
	}

	/**
	 * Meshes the journal places under a deformer of its own choosing: a structure `bind` to a deformer that is not
	 * a bone of [skeleton], or a `warp` edit wrapping them. Those edits keep the mesh's local geometry, which the
	 * bake would have moved into a bone's space, so the journal's placement wins and no bone binds them.
	 */
	internal fun journalPlaced(skeleton: SkeletonSpec, journal: List<kotlinx.serialization.json.JsonObject>): Set<DrawableId> {
		val bones = skeleton.bones.mapTo(HashSet()) { it.deformerId }
		val placed = HashSet<DrawableId>()
		for (command in journal) when (command["op"]?.jsonPrimitive?.contentOrNull) {
			"structure" -> for (edit in (command["edits"] as? kotlinx.serialization.json.JsonArray).orEmpty()) {
				val e = edit as? kotlinx.serialization.json.JsonObject ?: continue
				if (e["action"]?.jsonPrimitive?.contentOrNull != "bind" || e["kind"]?.jsonPrimitive?.contentOrNull != "mesh") continue
				if (e["parent_id"]?.jsonPrimitive?.contentOrNull in bones) continue
				e["id"]?.jsonPrimitive?.contentOrNull?.let { placed += DrawableId(it) }
			}
			"warp" -> (command["warp"] as? kotlinx.serialization.json.JsonObject)?.get("mesh_ids")?.let { ids ->
				(ids as? kotlinx.serialization.json.JsonArray).orEmpty().forEach { placed += DrawableId(it.jsonPrimitive.content) }
			}
		}
		return placed
	}

	/** [spec] without bindings of [drawables]: a passenger never binds to a bone. */
	private fun withoutBindings(spec: SkeletonSpec, drawables: Set<DrawableId>): SkeletonSpec {
		if (drawables.isEmpty() || spec.bones.none { bone -> bone.drawableIds.any { DrawableId(it) in drawables } }) return spec
		return spec.copy(bones = spec.bones.map { bone -> bone.copy(drawableIds = bone.drawableIds.filterNot { DrawableId(it) in drawables }) })
	}

	/**
	 * [model] - baked with the version 2 [parts] (and the version 1 [legacy] skinned parts) - without them, and the
	 * side channel that holds them: each part whole with the welds touching it and the paths on it, its generated
	 * keyform axes and who owns them (its mesh stage, or the skeleton for axes the bake added), the generated mask
	 * lists the base names passengers in, each part's slot in the part tree, and the stubs.
	 */
	private fun parked(
		model: PuppetModel, legacy: Set<DrawableId>, parts: List<Drawable>, deferred: Set<DrawableId>, resolution: PrimitiveResolution,
		generatedMasks: Map<DrawableId, List<DrawableId>>, slots: Map<DrawableId, PartId>, layers: Map<DrawableId, String>,
		neutral: Map<DrawableId, Bounds>,
	): Pair<PuppetModel, PrimitiveSkins> {
		val ids = legacy + parts.map { it.id }
		val (welds, glues) = model.glues.partition { it.meshA in ids || it.meshB in ids }
		val byId = model.drawables.associateBy { it.id }
		val held = LinkedHashMap<DrawableId, Drawable>()
		for (id in legacy) byId[id]?.let { held[id] = it }
		val axes = LinkedHashMap<DrawableId, Set<ParameterId>>()
		val ownership = LinkedHashMap<DrawableId, Map<ParameterId, String>>()
		for (part in parts) {
			val baked = byId[part.id] ?: part
			held[part.id] = baked
			fun axesOf(drawable: Drawable) = (drawable.geometryGrid?.axes.orEmpty() + drawable.channelGrids.gridsByChannel.values.flatMap { it.axes })
				.mapTo(LinkedHashSet()) { it.parameterId }
			val generated = axesOf(part)
			val all = axesOf(baked)
			axes[part.id] = all
			val node = DocumentGenerators.meshId(layers.getValue(part.id))
			ownership[part.id] = all.associateWithTo(LinkedHashMap()) { if (it in generated) node else DocumentGenerators.SKELETON }
		}
		val skins = PrimitiveSkins(held, welds, model.deformPaths.filter { it.drawableId in ids }, axes, resolution.resolved.stubDrawables,
			generatedMasks = generatedMasks, partSlots = slots, ownership = ownership, deferredParents = deferred,
			partLayers = layers, neutralBounds = neutral, replaces = resolution.replaces, resolved = resolution.resolved)
		return model.copy(drawables = model.drawables.filterNot { it.id in ids }, glues = glues,
			deformPaths = model.deformPaths.filterNot { it.drawableId in ids },
			vertexGroups = model.vertexGroups.filterNot { it.drawableId in ids }).withDerivedRenderRoot() to skins
	}

	/** [model] without the skinned split [parts], and those parts with the welds the bake gave them. */
	private fun withoutSkinnedPrimitives(model: PuppetModel, parts: Set<DrawableId>): Pair<PuppetModel, PrimitiveSkins> {
		if (parts.isEmpty()) return model to PrimitiveSkins.None
		val (welds, glues) = model.glues.partition { it.meshA in parts || it.meshB in parts }
		val skins = PrimitiveSkins(model.drawables.filter { it.id in parts }.associateBy { it.id }, welds)
		return model.copy(drawables = model.drawables.filterNot { it.id in parts }, glues = glues,
			deformPaths = model.deformPaths.filterNot { it.drawableId in parts },
			vertexGroups = model.vertexGroups.filterNot { it.drawableId in parts }).withDerivedRenderRoot() to skins
	}

	private fun handEditedTopology(config: PipelineConfig): Set<String> =
		config.rigEdits.authoringJournal
			.filter { it["op"]?.jsonPrimitive?.contentOrNull == "canvas_topology" }
			.mapNotNullTo(HashSet()) { it["id"]?.jsonPrimitive?.contentOrNull }

	/**
	 * The id of a deform path the builder generates on [drawable]: derived from the mesh and the path's role,
	 * so every build of the same rig names it the same and a content hash of the rig is stable.
	 */
	internal fun generatedPathId(drawable: DrawableId, role: String): String =
		UUID.nameUUIDFromBytes("psd2live:generated-path:${drawable.raw}:$role".encodeToByteArray()).toString()

	/** A mesh a paint commit replaces, with the atlas slice its texture coordinates were sampled from. */
	internal class ReplacedMesh internal constructor(
		val mesh: DrawableMesh,
		/** The texture the mesh's stored uvs address, or null when the atlas holds no slice for it. */
		val texture: LayerTexture?,
	)

	/**
	 * Rebuilds one drawable's mesh and keyforms after its layer's raster changed.
	 *
	 * [context] must be the context of the rig the drawable lives in - [rigContext] of the analysis
	 * that rig was built from - so the rebuilt mesh is normalized against the very frames its parent
	 * deformer was laid out on. Deriving a context from the edited analysis instead moves those frames
	 * as soon as the painted layer's bounds change, and the drawable is then rescaled against every
	 * sibling that kept the old frames.
	 *
	 * @param DeformerId?  parentId  The deformer the mesh hangs under, or null for a drawable that hangs
	 *                               under none and therefore stays in canvas space.
	 * @param ReplacedMesh? previous The mesh being replaced. It is the fallback frame source for a
	 *                               deformer [context] cannot resolve, which is what an imported or
	 *                               hand-authored rig parents its drawables to.
	 */
	internal fun rebuildDrawableMesh(
		layer: ClassifiedLayer,
		context: RigContext,
		placement: AtlasPlacement,
		pageWidth: Int,
		pageHeight: Int,
		config: PipelineConfig,
		parentId: DeformerId?,
		owner: Drawable,
		atlas: PackedAtlas,
		generatedLips: Map<String, ClassifiedLayer>,
		previous: ReplacedMesh? = null,
	): RebuiltDrawable {
		val contextFrame = parentId?.let { context.resolveFrame(it, config) }
		val canvasFrame = if (contextFrame == null) previous?.let(::meshNormalizationFrame) else null
		val parentFrame = contextFrame ?: canvasFrame ?: context.character
		// A frame read off the mesh is already in canvas space, so the layer must not be head-aligned
		// on top of it; the rig's own frames only make sense together with the rig's own alignment.
		val headSpace = if (canvasFrame == null) context.headSpaceFor(layer) else null
		val parts = buildDrawableMesh(
			layer,
			context.rigLayer(layer),
			context,
			parentId,
			parentFrame,
			headSpace,
			config,
			meshCache = null,
		)
		val unboundLips = mouthLips(
			owner,
			layer,
			parts.data,
			parentFrame,
			parts.mouthAperture,
			headSpace,
			atlas,
			generatedLips,
			config,
		)
		// The rebuilt pieces come out unbound; the commit splices them into a bound rig.
		val lips = unboundLips.map { lip ->
			val lipPlacement = atlas.placementByLayerId.getValue(lip.layer.source.id.raw)
			val lipPage = atlas.pages[lipPlacement.page].image
			val texture = LayerTexture.packed(lip.layer, lipPlacement, lipPage.width, lipPage.height)
			val unbound = requireNotNull(lip.drawable.mesh)
			val mesh = DrawableMesh(unbound.positions, texture.bind(unbound.uvs), unbound.indices)
			MouthLip(lip.drawable.copy(mesh = mesh, texturePage = lipPlacement.page), lip.ownerId, lip.layer, lip.path, lip.neutralBounds,
				texture.toCanvas(mesh.uvs))
		}
		val texture = LayerTexture.packed(layer, placement, pageWidth, pageHeight)
		val mesh = DrawableMesh(parts.mesh.positions, texture.bind(parts.mesh.uvs), parts.mesh.indices)
		return RebuiltDrawable(mesh, parts.geometryGrid, lips, texture.toCanvas(mesh.uvs))
	}

	/**
	 * What a paint commit replaces on one layer: the drawable's own geometry, and the mouth-outline
	 * ribbons that are drawn from the same contour.
	 */
	internal class RebuiltDrawable internal constructor(
		val mesh: DrawableMesh,
		val geometryGrid: KeyformGrid<MeshDeltaForm>,
		/** Ribbons to swap in, empty for a layer that has none. */
		val mouthLips: List<MouthLip>,
		/** Where each vertex's texel lies on the canvas: where the vertex belongs at rest, whatever its parent. */
		val canvas: FloatArray,
	)

	/** One generated mouth-outline ribbon: the drawable, the layer it samples, and its deform path. */
	internal class MouthLip internal constructor(
		val drawable: Drawable,
		/** The mouth drawable this ribbon outlines. */
		val ownerId: DrawableId,
		val layer: ClassifiedLayer,
		val path: DeformPath?,
		val neutralBounds: Bounds,
		/** A rebuilt ribbon's texels on the canvas (see [RebuiltDrawable.canvas]); null for a generated one. */
		val canvas: FloatArray? = null,
	)

	/**
	 * The frame that reproduces the affine [previous] is already normalized by.
	 *
	 * Every vertex carries the source pixel its texture coordinate was sampled from, so the affine the
	 * mesh was built with - `local = scale * canvas + offset`, per axis - is recoverable from the mesh
	 * itself, and that affine is what its parent deformer expects. It is the only thing left to go on
	 * when the parent is a deformer the analysis cannot describe, such as an imported rig's; the
	 * alternative, normalizing against some default frame, moves and rescales the painted layer.
	 *
	 * @return The frame, or null when the mesh cannot pin one down. A mirrored parent space negates the
	 *         slope and is reported as unresolved rather than as an inverted frame.
	 */
	private fun meshNormalizationFrame(previous: ReplacedMesh): Bounds? {
		val texture = previous.texture ?: return null
		val vertices = previous.mesh.positions.size / 2
		if (vertices < 3 || previous.mesh.uvs.size < vertices * 2) return null
		val canvas = texture.toCanvas(previous.mesh.uvs)
		val canvasX = DoubleArray(vertices)
		val canvasY = DoubleArray(vertices)
		val localX = DoubleArray(vertices)
		val localY = DoubleArray(vertices)
		for (vertex in 0 until vertices) {
			canvasX[vertex] = canvas[vertex * 2].toDouble()
			canvasY[vertex] = canvas[vertex * 2 + 1].toDouble()
			localX[vertex] = previous.mesh.positions[vertex * 2].toDouble()
			localY[vertex] = previous.mesh.positions[vertex * 2 + 1].toDouble()
		}
		val (scaleX, offsetX) = fitAffine(canvasX, localX) ?: return null
		val (scaleY, offsetY) = fitAffine(canvasY, localY) ?: return null
		if (scaleX <= 1e-6 || scaleY <= 1e-6) return null
		val left = (-offsetX / scaleX).toFloat()
		val top = (-offsetY / scaleY).toFloat()
		return Bounds(left, top, left + (1.0 / scaleX).toFloat(), top + (1.0 / scaleY).toFloat())
			.takeIf { it.width.isFinite() && it.height.isFinite() && it.width > 1e-3f && it.height > 1e-3f }
	}

	/** Least-squares fit of `local = scale * canvas + offset`; null when [canvas] pins no slope. */
	private fun fitAffine(canvas: DoubleArray, local: DoubleArray): Pair<Double, Double>? {
		val count = canvas.size.toDouble()
		var canvasSum = 0.0
		var localSum = 0.0
		var canvasSquareSum = 0.0
		var productSum = 0.0
		for (index in canvas.indices) {
			canvasSum += canvas[index]
			localSum += local[index]
			canvasSquareSum += canvas[index] * canvas[index]
			productSum += canvas[index] * local[index]
		}
		val determinant = count * canvasSquareSum - canvasSum * canvasSum
		if (abs(determinant) < 1e-9) return null
		val scale = (count * productSum - canvasSum * localSum) / determinant
		val offset = (localSum - scale * canvasSum) / count
		return if (scale.isFinite() && offset.isFinite()) scale to offset else null
	}

	/**
	 * Stage `mesh:<layer>`: [buildDrawableMesh] through the pipeline's [RigStageCache], under a key of exactly what
	 * it reads - the layer's metadata and pixels, its rig-space footprint, its parent and that parent's frame, the
	 * head space, the eye whites it closes against, the face rig for an eye layer, the mesh scale, the layer's own
	 * mesh override and the settings [meshConfig] keeps.
	 */
	private fun meshStage(
		layer: ClassifiedLayer,
		rigLayer: ClassifiedLayer,
		context: RigContext,
		parentId: DeformerId?,
		parentFrame: Bounds,
		headSpace: HeadCoordinateSpace?,
		config: PipelineConfig,
		meshConfig: PipelineConfig,
		meshCache: PreviewMeshCache?,
	): LayerMeshParts {
		val build = {
			RigBuildProfile.stage("mesh: layer mesh + keyforms (built)") {
				buildDrawableMesh(layer, rigLayer, context, parentId, parentFrame, headSpace, config, meshCache)
			}
		}
		val stages = stagesOf(meshCache) ?: return build()
		val eyes = rigLayer.semantic.tag == SemanticTag.EYEWHITE || rigLayer.semantic.tag == SemanticTag.EYELASH
		val key = MeshKey(layer.meta(), layer.source.raster.width, layer.source.raster.height, RasterDigest.of(layer.source.raster.rgba),
			rigLayer.bounds, rigLayer.centroidX, rigLayer.centroidY, parentId, parentFrame, headSpace,
			if (eyes) faceSignature(context.faceRig) else null, matchingEyeWhiteBounds(rigLayer, context.eyeWhiteLayers),
			MeshResolution.unitScale(config, context.analysis.source), meshConfig, config.meshOverrides[layer.source.id.raw],
			io.github.psd2live.i18n.I18n.currentLanguage.tag)
		return stages.get(RigStageCache.MESH, 1024, key, build)
	}

	/**
	 * One version 2 part built on its recorded mesh (Rule A): positions are the record's rest canvas positions,
	 * expressed in the generated parent's frame like any layer's - never re-meshed - with texture coordinates in
	 * canvas units (its record places it through the current atlas). Keyforms, channels and neutral bounds are
	 * generated as for a layer of the part's role, against the resolved scaffold; a mouth part gets the whole-mouth
	 * keyforms but no contour mesh or paths. A generator axis the record froze leaves the geometry to the record.
	 * A part whose record names a parent keeps canvas positions under it and gets no keyforms.
	 */
	private fun pinnedPart(
		part: ResolvedPart, layer: ClassifiedLayer, context: RigContext, config: PipelineConfig, deformersEnabled: Boolean,
		switchParamKeys: Map<String, FloatArray>, stages: RigStageCache?, drawOrder: Float, page: Int, mouthAperture: Bounds? = null,
		ribbon: Boolean = false,
	): Pair<Drawable, Bounds> {
		val rigLayer = context.rigLayer(layer)
		val deferred = part.parent != null
		val (generatedParent, parentFrame) = if (deferred) null to context.character else context.parentAndFrame(layer, config)
		val headSpace = if (deferred) null else context.headSpaceFor(layer)
		val canvas = part.positions
		val rig = FloatArray(canvas.size)
		val local = FloatArray(canvas.size)
		for (index in canvas.indices step 2) {
			val point = headSpace?.toAligned(canvas[index], canvas[index + 1]) ?: (canvas[index] to canvas[index + 1])
			rig[index] = point.first; rig[index + 1] = point.second
			local[index] = normalizeX(point.first, parentFrame); local[index + 1] = normalizeY(point.second, parentFrame)
		}
		val uvs = part.canvasUvs.copyOf()
		val data = MeshData(DrawableMesh(local, uvs, part.triangles.copyOf()), rig)
		val parentId = if (deferred) part.parent else if (deformersEnabled) generatedParent else null
		val mesh = when {
			deferred -> DrawableMesh(canvas.copyOf(), uvs, part.triangles.copyOf())
			parentId != null -> data.mesh
			else -> DrawableMesh(rig, uvs, part.triangles.copyOf())
		}
		val aperture = mouthApertureFor(rigLayer)?.let { mouthAperture ?: it }
		// A part of a lip ribbon keeps a zero grid: its record carries the ribbon's motion as residual.
		val generated = if (config.meshOnly || deferred || ribbon) zeroMeshGrid(canvas.size)
			else buildDrawableGeometry(rigLayer, data, parentFrame, context.faceRig, matchingEyeWhiteBounds(rigLayer, context.eyeWhiteLayers),
				aperture, config, emptyList())
		val geometry = if (generated.axes.any { it.parameterId in part.frozenAxes }) zeroMeshGrid(canvas.size) else generated
		val override = config.layerOverrides[layer.source.id.raw]
		val classification = override?.type ?: layer.semantic.type
		val channels = if (deferred || config.meshOnly && classification == LayerType.PRESET) ChannelGrids.Empty
			else channelStage(layer, override, switchParamKeys, config.rigTuning, stages)
		val drawable = Drawable(
			id = part.drawableId,
			name = part.name,
			parentDeformerId = parentId,
			blendMode = blendMode(layer.source.blend),
			maskedBy = emptyList(),
			mesh = mesh,
			geometryGrid = geometry,
			channelGrids = channels,
			drawOrder = drawOrder.coerceIn(0f, 1000f),
			opacity = layer.source.opacity.coerceIn(0f, 1f),
			isVisible = layerVisibility(config, layer.source.id.raw, layer.source.visible),
			texturePage = page,
			atlasTileId = PuppetSourceAtlas.tileIdFor(part.layerId, part.sourceId),
		)
		return drawable to neutralValidationBounds(layer, data, aperture, headSpace, config.meshOnly, config)
	}

	private data class MeshKey(
		val layer: LayerMeta, val width: Int, val height: Int, val digest: String,
		val rigBounds: Bounds, val rigCentroidX: Float, val rigCentroidY: Float,
		val parent: DeformerId?, val parentFrame: Bounds, val headSpace: HeadCoordinateSpace?,
		val face: List<Any?>?, val eyeWhites: List<Bounds>, val unitScale: Float,
		val config: PipelineConfig, val override: MeshSettings?, val language: String,
	)

	/** Every value of [rig], which has no equality of its own. */
	private fun faceSignature(rig: NinePoseFaceRig): List<Any?> = listOf(rig.face, rig.centerX, rig.centerY, rig.radiusX, rig.radiusY,
		rig.eyeLineY, rig.noseLineY, rig.mouthLineY, rig.chinX, rig.chinY, rig.regions, rig.coordinateSpace, rig.tuning)

	/**
	 * Builds every piece of one layer's rig geometry: the stored mesh, its mouth outline and the
	 * keyforms that move it. [layer] carries the pixels and bounds; [context] supplies the frames,
	 * the face rig and the eye whites that the resulting geometry is expressed in.
	 *
	 * A null [parentId] leaves the mesh in canvas space, which is what the runtime expects from a
	 * drawable that hangs under no deformer at all.
	 */
	private fun buildDrawableMesh(
		layer: ClassifiedLayer,
		rigLayer: ClassifiedLayer,
		context: RigContext,
		parentId: DeformerId?,
		parentFrame: Bounds,
		headSpace: HeadCoordinateSpace?,
		config: PipelineConfig,
		meshCache: PreviewMeshCache?,
	): LayerMeshParts {
		val originalMeshData = buildGridMesh(
			layer,
			parentFrame,
			headSpace,
			config,
			meshCache,
			MeshResolution.unitScale(config, context.analysis.source),
		)
		val outlineMouth = config.mouthOutlineEnabled && !config.meshOnly &&
			layer.semantic.tag in setOf(SemanticTag.MOUTH, SemanticTag.MOUTH_OPEN)
		val meshData = if (outlineMouth) {
			mouthContourMesh(originalMeshData, layer, parentFrame, headSpace)
		} else originalMeshData
		val mesh = if (parentId != null) {
			meshData.mesh
		} else {
			DrawableMesh(meshData.rigPositions, meshData.mesh.uvs, meshData.mesh.indices)
		}
		val mouthAperture = mouthApertureFor(rigLayer)
		val mouthPaths = if (outlineMouth) {
			createMouthDeformPaths(DrawableId(layer.source.id.raw), meshData, parentFrame)
		} else emptyList()
		val geometryGrid = if (config.meshOnly) {
			zeroMeshGrid(mesh.positions.size)
		} else {
			buildDrawableGeometry(
				rigLayer,
				meshData,
				parentFrame,
				context.faceRig,
				matchingEyeWhiteBounds(rigLayer, context.eyeWhiteLayers),
				mouthAperture,
				config,
				mouthPaths,
			)
		}
		return LayerMeshParts(
			mesh,
			meshData,
			mouthAperture,
			mouthPaths,
			geometryGrid,
			headSpace,
			neutralValidationBounds(layer, meshData, mouthAperture, headSpace, config.meshOnly, config),
		)
	}

	/**
	 * The body's warps (see [BodyStance]): the body over [frame] at the root on Body X and Body Y, the lean
	 * and the proportions under it on their own parameters, and for a standing figure the legs, a second root
	 * beside the body, on Body X, Body Y and the proportions.
	 */
	internal fun bodyWarps(stance: BodyStance, frame: Bounds, bodyPartId: PartId?): List<Deformer.Warp> {
		val bodyAxes = listOf(axis(StandardParameters.BODY_X, -10f, 0f, 10f), axis(StandardParameters.BODY_Y, -10f, 0f, 10f))
		fun canvasOf(u: Float, v: Float) = (frame.left + u * frame.width).toDouble() to (frame.top + v * frame.height).toDouble()
		val legs = if (!stance.standing) emptyList() else {
			val legsFrame = stance.legsFrame!!
			val legRows = stance.legWarpRows
			// The legs shorten and lengthen with the proportions too, the body coming down with them.
			val legsGrid = grid(bodyAxes + axis(StandardParameters.PROPORTION, -10f, 0f, 10f)) { values ->
				val solved = stance.Solved(stance.bodyPose(values[0], values[1]))
				lattice(LEG_COLUMNS, legRows) { u, v ->
					stance.legsAt(solved.legPoint((legsFrame.left + u * legsFrame.width).toDouble(), (legsFrame.top + v * legsFrame.height).toDouble()), values[2])
				}
			}
			listOf(Deformer.Warp(BodyStance.legsWarpId, tr("model.deformer.legs"), null, bodyPartId, legRows, LEG_COLUMNS, true, legsGrid))
		}
		// At the root the lattice is canvas pixels.
		val bodyGrid = grid(bodyAxes) { values ->
			lattice(BODY_COLUMNS, BODY_ROWS) { u, v ->
				val (x, y) = canvasOf(u, v)
				stance.bodyPoint(x, y, values[0], values[1])
			}
		}
		val body = Deformer.Warp(bodyWarpId, tr("model.deformer.body"), null, bodyPartId, BODY_ROWS, BODY_COLUMNS, true, bodyGrid)
		// Under the body the lattice is in its space over the same frame.
		val leanGrid = grid(leanAxes()) { values ->
			lattice(LEAN_COLUMNS, LEAN_ROWS) { u, v ->
				val (x, y) = canvasOf(u, v)
				val p = stance.leanPoint(x, y, values[0], values[1])
				doubleArrayOf((p[0] - frame.left) / frame.width, (p[1] - frame.top) / frame.height)
			}
		}
		val lean = Deformer.Warp(BodyStance.leanWarpId, tr("model.deformer.bodyLean"), bodyWarpId, bodyPartId, LEAN_ROWS, LEAN_COLUMNS, true, leanGrid)
		return legs + body + lean
	}

	/**
	 * Where the torso is drawn, canvas pixels: its centre line, the shoulder and waist lines, and half its
	 * width at the chest. The breath and the body's lean are placed by it.
	 */
	internal class TorsoFrame(val centerX: Float, val shoulderY: Float, val waistY: Float, val halfWidth: Float) {
		val length: Float get() = waistY - shoulderY
	}

	/**
	 * The torso of [analysis]: the skeleton's upper body bone when there is one - it runs from the waist up
	 * to the shoulders - else the shoulder and hip lines the layers give, across the topwear.
	 */
	internal fun torsoFrame(analysis: PipelineAnalysis, character: Bounds, skeleton: SkeletonSpec?): TorsoFrame {
		val anchors = analysis.anchors
		val topwear = analysis.layers.filter { it.semantic.tag == SemanticTag.TOPWEAR && it.opaquePixels > 0 }
			.map { it.bounds }.reduceOrNull(Bounds::union)
		val torsoBone = skeleton?.takeIf { it.enabled }?.bones?.firstOrNull { it.role == BoneRole.UPPER_BODY && it.length >= 1f }
		val centerX = torsoBone?.headX ?: topwear?.centerX ?: anchors.chinX
		val shoulderY = (torsoBone?.let { minOf(it.headY, it.tailY) } ?: anchors.shoulderY).coerceIn(character.top, character.bottom)
		val minimum = character.height * 0.05f
		val waistY = (torsoBone?.let { maxOf(it.headY, it.tailY) } ?: anchors.hipY)
			.coerceIn(shoulderY + minimum, maxOf(character.bottom, shoulderY + minimum))
		val halfWidth = (topwear?.width?.times(0.4f) ?: (anchors.face.width * 0.45f)).coerceAtLeast(1f)
		return TorsoFrame(centerX, shoulderY, waistY, halfWidth)
	}

	/**
	 * The body's second warp, in the lean warp's normalized space over [character], the body warps' frame.
	 *
	 * How far each goes is [tuning]'s Body Z and breath values.
	 *
	 * - Body Z leans the upper body about the waist: each row above it shifts sideways by its height over
	 *   the waist, easing in across a band so the bend has no crease, and the hips and legs stay put. Rows
	 *   only shift, so a lean either way keeps every row its width.
	 * - Breath lifts the shoulders and everything they carry - the head, the arms - with the chest lifting
	 *   less and the waist not at all, and widens the chest a little about the body's centre line. Past the
	 *   torso's sides the widening stops growing, so an arm or a lock of hair beside it moves bodily.
	 */
	internal fun bodySecondaryWarpPoint(
		character: Bounds,
		torso: TorsoFrame,
		u: Float,
		v: Float,
		bodyAngleZ: Float,
		breathValue: Float,
		strength: Float,
		tuning: RigTuning = RigTuning(),
	): Pair<Float, Float> {
		val boundedStrength = strength.coerceIn(0f, 2f)
		val x = character.left + u * character.width
		val y = character.top + v * character.height
		val torsoLength = torso.length
		val lean = Math.toRadians(tuning.bodyZDegrees * bodyAngleZ / 10.0 * boundedStrength)
		val band = torsoLength * 0.3f
		val above = (torso.waistY - y).coerceAtLeast(0f)
		val lever = if (above < band) above * above / (2f * band) else above - band / 2f
		val shift = kotlin.math.tan(lean).toFloat() * lever
		val breath = breathValue.coerceIn(0f, 1f) * boundedStrength
		val rise = smoothstep(((torso.waistY - y) / torsoLength).coerceIn(0f, 1f))
		val lift = breath * tuning.breathLift / 100f * torsoLength * rise
		val chestY = torso.shoulderY + torsoLength * 0.3f
		val chest = rise * kotlin.math.exp(-((y - chestY) / (torsoLength * 0.45f)).let { it * it })
		val widen = (x - torso.centerX).coerceIn(-torso.halfWidth, torso.halfWidth) * breath * tuning.breathWiden / 100f * chest
		return (x + shift + widen - character.left) / character.width to (y - lift - character.top) / character.height
	}

	private fun smoothstep(t: Float) = t * t * (3f - 2f * t)

	internal fun headContainerPoint(
		head: Bounds,
		originX: Float,
		originY: Float,
		u: Float,
		v: Float,
		angleX: Float,
		angleY: Float,
		strength: Float,
		tuning: RigTuning = RigTuning(),
	): Pair<Float, Float> {
		val boundedStrength = strength.coerceIn(0f, 2f)
		val canvasX = head.left + u * head.width
		val canvasY = head.top + v * head.height
		val yaw = angleX / 45f * boundedStrength
		val pitch = angleY / 30f * boundedStrength
		val crownArch = sin(PI * u).toFloat().coerceAtLeast(0f)
		val shellX = yaw * head.width * (tuning.headShellTurn + crownArch * tuning.headShellCrown) / 100f
		val shellY = -pitch * head.height * tuning.headShellTilt / 100f
		return (canvasX - originX + shellX) to (canvasY - originY + shellY)
	}

	internal fun gazePoint(u: Float, v: Float, eyeX: Float, eyeY: Float, tuning: RigTuning = RigTuning()): Pair<Float, Float> =
		(u + eyeX * tuning.gazeX / 100f) to (v - eyeY * tuning.gazeY / 100f)

	internal fun hairFollowPoint(
		inHead: Bounds,
		u: Float,
		v: Float,
		angleX: Float,
		angleY: Float,
		yawParallax: Float,
		pitchParallax: Float,
		yawPerspective: Float = 0f,
	): Pair<Float, Float> {
		val yaw = angleX / 45f
		val pitch = angleY / 30f
		val yawDepthWeight = 0.62f + v * 0.38f
		// Both endpoints receive the same vertical offset. The middle retains a depth bulge without
		// turning signed pitch into a global height scale.
		val pitchDepthWeight = 0.78f + sin(PI * v).toFloat().coerceAtLeast(0f) * 0.22f
		val uPerspective = u + yaw * yawPerspective * 4f * u * (1f - u)
		return (inHead.left + uPerspective * inHead.width + yaw * yawParallax * yawDepthWeight) to
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
		analysis: PipelineAnalysis,
		stance: BodyStance,
		bodyFrame: Bounds,
		rigLayerById: Map<String, ClassifiedLayer>,
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
		headAccessoryPartId: PartId,
		bodyPartId: PartId,
		extraPartId: PartId,
		config: PipelineConfig,
	): DeformerBuildResult {
		val bodyWarps = bodyWarps(stance, bodyFrame, bodyPartId)

		val torso = torsoFrame(analysis, character, config.rigEdits.skeleton)
		val breathGrid = warpGrid(
			listOf(axis(StandardParameters.BODY_Z, -10f, 0f, 10f), axis(StandardParameters.BREATH, 0f, 0.5f, 1f)),
			columns = 4,
			rows = 6,
		) { u, v, values ->
			bodySecondaryWarpPoint(bodyFrame, torso, u, v, values[0], values[1], config.bodyStrength, config.rigTuning)
		}
		val breath = Deformer.Warp(breathWarpId, tr("model.deformer.breath"), BodyStance.leanWarpId, bodyPartId, 6, 4, true, breathGrid)

		val headPivotX = faceRig.centerX
		val headPivotY = faceRig.mouthLineY
		val headPivotCanvas = faceRig.coordinateSpace.toCanvas(headPivotX, headPivotY)
		val headPivotLocalX = normalizeX(headPivotCanvas.first, bodyFrame)
		val headPivotLocalY = normalizeY(headPivotCanvas.second, bodyFrame)
		// The head grows and shrinks with the lean and the proportions, which a warp cannot pass on to a rotation.
		val rotationGrid = grid(listOf(axis(StandardParameters.ANGLE_Z, -30f, 0f, 30f)) + leanAxes()) { values ->
			val scale = stance.leanScale(headPivotCanvas.second.toDouble(), values[1], values[2]).toFloat()
			RotationPivotForm(headPivotLocalX, headPivotLocalY, values[0], scale)
		}
		val rotation = Deformer.Rotation(
			headRotationId,
			tr("model.deformer.headRotation"),
			breathWarpId,
			headPartId,
			faceRig.initialAngleZ,
			rotationGrid,
		)

		// A real head container separates skull-following content from the facial surface.  It is the
		// sole pixel-space child of the rotation deformer; all descendants use ordinary normalized
		// warp coordinates.  Face, front hair and back hair are siblings below this node.
		val headGrid = warpGrid(ninePoseAxes(), columns = 4, rows = 5) { u, v, values ->
			headContainerPoint(head, headPivotX, headPivotY, u, v, values[0], values[1], config.headTurnStrength, config.rigTuning)
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

		// Identity at neutral, in the face's normalized space: the parent surface is inherited
		// exactly once. Both directional bows affect every row/column, not just the center knot.
		val displacementGrid = warpGrid(ninePoseAxes(), columns = 8, rows = 8) { u, v, values ->
			featureDisplacementPoint(u, v, values[0], values[1], config.headTurnStrength, config.rigTuning,
				faceFrame.width / faceFrame.height.coerceAtLeast(1e-4f))
		}
		val displacement = Deformer.Warp(featureDisplacementId, tr("model.deformer.featureDisplacement"),
			faceWarpId, facePartId, 8, 8, true, displacementGrid)
		val socketY = normalizeY(faceRig.eyeLineY, faceFrame).coerceIn(0.05f, 0.95f)
		val contourGrid = warpGrid(
			listOf(axis(StandardParameters.ANGLE_X, *NinePoseFaceRig.angleXKeys)), columns = 8, rows = 16,
		) { u, v, values -> faceContourPoint(u, v, values[0], config.headTurnStrength, socketY, config.rigTuning) }
		val contour = Deformer.Warp(faceContourId, tr("model.deformer.faceContour"),
			faceWarpId, facePartId, 16, 8, true, contourGrid)
		val deformers = mutableListOf<Deformer>()
		deformers += bodyWarps
		deformers += listOf(breath, rotation, headContainer, face, contour)
		if (config.featureDisplacementEnabled) deformers += displacement
		val pairFrames = mutableMapOf<String, Bounds>()
		val pairedParentByLayerId = mutableMapOf<String, Pair<DeformerId, Bounds>>()

		val leftEye = faceRig.regions.firstOrNull { it.feature == FaceFeature.EYE && it.side == Side.LEFT }
		val rightEye = faceRig.regions.firstOrNull { it.feature == FaceFeature.EYE && it.side == Side.RIGHT }
		val eyesBounds = if (leftEye != null && rightEye != null) {
			leftEye.bounds.union(rightEye.bounds).expanded(0.04f)
		} else null
		if (eyesBounds != null) {
			val eyesParent = if (config.featureDisplacementEnabled) featureDisplacementId else faceWarpId
			deformers += identityWarp(eyesWarpId, tr("model.deformer.eyes"), eyesParent, eyesBounds, faceFrame, facePartId, rows = 3, columns = 4)
			pairFrames[eyesWarpId.raw] = eyesBounds
		}

		val leftBrow = faceRig.regions.firstOrNull { it.feature == FaceFeature.BROW && it.side == Side.LEFT }
		val rightBrow = faceRig.regions.firstOrNull { it.feature == FaceFeature.BROW && it.side == Side.RIGHT }
		val browsBounds = if (leftBrow != null && rightBrow != null) {
			leftBrow.bounds.union(rightBrow.bounds).expanded(0.04f)
		} else null
		if (browsBounds != null) {
			val browsParent = if (config.featureDisplacementEnabled) featureDisplacementId else faceWarpId
			deformers += identityWarp(browsWarpId, tr("model.deformer.brows"), browsParent, browsBounds, faceFrame, facePartId, rows = 3, columns = 4)
			pairFrames[browsWarpId.raw] = browsBounds
		}

		val leftEar = faceRig.regions.firstOrNull { it.feature == FaceFeature.EAR && it.side == Side.LEFT }
		val rightEar = faceRig.regions.firstOrNull { it.feature == FaceFeature.EAR && it.side == Side.RIGHT }
		val earsBounds = if (leftEar != null && rightEar != null) {
			leftEar.bounds.union(rightEar.bounds).expanded(0.04f)
		} else null
		if (earsBounds != null) {
			deformers += identityWarp(earsWarpId, tr("model.deformer.ears"), faceWarpId, earsBounds, faceFrame, facePartId, rows = 3, columns = 4)
			pairFrames[earsWarpId.raw] = earsBounds
		}

		val primaryRegions = faceRig.regions.filter { it.feature != FaceFeature.IRIS }
		for (region in primaryRegions) {
			val (parent, parentFrame) = when (region.feature) {
				FaceFeature.EYE -> if (eyesBounds != null) eyesWarpId to eyesBounds else {
					(if (config.featureDisplacementEnabled) featureDisplacementId else faceWarpId) to faceFrame
				}
				FaceFeature.BROW -> if (browsBounds != null) browsWarpId to browsBounds else {
					(if (config.featureDisplacementEnabled) featureDisplacementId else faceWarpId) to faceFrame
				}
				FaceFeature.EAR -> if (earsBounds != null) earsWarpId to earsBounds else {
					faceWarpId to faceFrame
				}
				else -> {
					val p = if (config.featureDisplacementEnabled && region.feature in setOf(FaceFeature.EYE, FaceFeature.BROW, FaceFeature.MOUTH))
						featureDisplacementId else faceWarpId
					p to faceFrame
				}
			}
			deformers += featureWarp(faceRig, region, parent, parentFrame, facePartId, config)
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
			deformers += gazeWarp(irisRegion, irisShape.id, facePartId, config.rigTuning)
		}
		frontHair?.let { frame ->
			deformers += hairFollowWarp(
				frontHairFollowWarpId,
				tr("model.deformer.frontHairFollow"),
				frame,
				head,
				frontHairPartId,
				-config.rigTuning.frontHairTurn / 100f,
				-config.rigTuning.frontHairTilt / 100f,
				yawPerspective = config.rigTuning.frontHairPerspective / 100f,
			)
			deformers += hairPhysicsWarp(
				frontHairPhysicsWarpId,
				tr("model.deformer.frontHairPhysics"),
				StandardParameters.HAIR_FRONT,
				frontHairFollowWarpId,
				frame,
				frontHairPartId,
				rows = 4,
				swayRatio = config.rigTuning.frontHairSway / 100f,
				curlRatio = config.rigTuning.frontHairCurl / 100f,
			)
		}
		backHair?.let { frame ->
			deformers += hairFollowWarp(backHairFollowWarpId, tr("model.deformer.backHairFollow"), frame, head, backHairPartId,
				-config.rigTuning.backHairTurn / 100f, -config.rigTuning.backHairTilt / 100f)
			deformers += hairPhysicsWarp(
				backHairPhysicsWarpId,
				tr("model.deformer.backHairPhysics"),
				StandardParameters.HAIR_BACK,
				backHairFollowWarpId,
				frame,
				backHairPartId,
				rows = 6,
				swayRatio = config.rigTuning.backHairSway / 100f,
				curlRatio = config.rigTuning.backHairCurl / 100f,
			)
		}

		val usedDeformerIds = deformers.map { it.id.raw }.toMutableSet()
		val candidateLayers = analysis.layers.filter { layer ->
			layer.opaquePixels > 0 &&
				(layer.semantic.side == Side.LEFT || layer.semantic.side == Side.RIGHT) &&
				!(config.rigEdits.skeleton?.enabled == true && layer.semantic.tag in SkeletonAutoBuilder.limbTags) &&
				!isHandledByFaceRegion(layer, faceRig)
		}
		val grouped = candidateLayers.groupBy { layer ->
			val (defaultParentId, _) = defaultParentAndFrame(layer, faceRig, analysis.anchors, character, head, faceFrame, frontHair, backHair,
				config.rigEdits.skeleton?.enabled == true, stance.legsFrame, bodyFrame)
			val baseName = pairBaseName(layer.source.name)
			defaultParentId to baseName.lowercase(Locale.ROOT).trim()
		}
		for ((key, pairLayers) in grouped) {
			val hasLeft = pairLayers.any { it.semantic.side == Side.LEFT }
			val hasRight = pairLayers.any { it.semantic.side == Side.RIGHT }
			if (!hasLeft || !hasRight) continue

			val (defaultParentId, defaultParentFrame) =
				defaultParentAndFrame(pairLayers.first(), faceRig, analysis.anchors, character, head, faceFrame, frontHair, backHair,
					config.rigEdits.skeleton?.enabled == true, stance.legsFrame, bodyFrame)
			val cleanBaseName = pairBaseName(pairLayers.first().source.name)
			val pairId = uniquePairDeformerId(cleanBaseName, pairLayers.first().semantic.tag, usedDeformerIds)
			val pairName = tr("model.deformer.pair", cleanBaseName)

			val unionBounds = pairLayers.map { rigLayerById.getValue(it.source.id.raw).bounds }.reduce(Bounds::union)
			val padX = maxOf(unionBounds.width * 0.04f, 4f)
			val padY = maxOf(unionBounds.height * 0.04f, 4f)
			val pairBounds = Bounds(unionBounds.left - padX, unionBounds.top - padY, unionBounds.right + padX, unionBounds.bottom + padY)

			val firstLayer = pairLayers.first()
			val partId = when {
				firstLayer.semantic.tag == SemanticTag.FRONT_HAIR -> frontHairPartId
				firstLayer.semantic.tag == SemanticTag.BACK_HAIR -> backHairPartId
				firstLayer.semantic.tag in faceTags -> facePartId
				inferredGroup(firstLayer, analysis.anchors) == LayerGroup.HEAD -> headAccessoryPartId
				inferredGroup(firstLayer, analysis.anchors) == LayerGroup.EXTRA -> extraPartId
				else -> bodyPartId
			}

			val pairWarp = identityWarp(
				id = pairId,
				name = pairName,
				parent = defaultParentId,
				frame = pairBounds,
				parentFrame = defaultParentFrame,
				partId = partId,
				rows = 3,
				columns = 3,
			)
			deformers += pairWarp
			pairFrames[pairId.raw] = pairBounds
			for (layer in pairLayers) {
				pairedParentByLayerId[layer.source.id.raw] = pairId to pairBounds
			}
		}

		if (config.rigEdits.skeleton?.enabled != true) {
			deformers += armHangWarps(analysis, stance, rigLayerById, deformers, pairedParentByLayerId, pairFrames, bodyPartId) { layer ->
				defaultParentAndFrame(layer, faceRig, analysis.anchors, character, head, faceFrame, frontHair, backHair, false, stance.legsFrame, bodyFrame)
			}
		}

		return DeformerBuildResult(deformers, pairFrames, pairedParentByLayerId)
	}

	/**
	 * A warp for each arm on Body X and the body's lean and proportions, as artists give each arm a warp of its
	 * own: an arm hangs straight down from its shoulder whatever the body does ([BodyStance.armPoint]), but
	 * drawn beside the torso and the skirt it would bend with the body in the body and lean warps - drawn
	 * larger and lower on the side the torso turns toward. At each key its lattice holds every point where
	 * the arm puts the point it covers at rest, read back through its parent as the body leans there. The
	 * arm's layers move under it: [parents] and [frames] receive it.
	 */
	private fun armHangWarps(
		analysis: PipelineAnalysis,
		stance: BodyStance,
		rigLayerById: Map<String, ClassifiedLayer>,
		deformers: List<Deformer>,
		parents: MutableMap<String, Pair<DeformerId, Bounds>>,
		frames: MutableMap<String, Bounds>,
		partId: PartId,
		defaultParent: (ClassifiedLayer) -> Pair<DeformerId, Bounds>,
	): List<Deformer.Warp> {
		val axes = listOf(axis(StandardParameters.BODY_X, -10f, 0f, 10f)) + leanAxes()
		val combos = axes[0].keys.indices.flatMap { a ->
			axes[1].keys.indices.flatMap { b -> axes[2].keys.indices.map { c -> intArrayOf(a, b, c) } }
		}
		val worlds = combos.map { c ->
			val values = axes.indices.associate { axes[it].parameterId to axes[it].keys[c[it]] }
			buildDeformerWorlds(deformers, { values[it] ?: 0f }, { 0f })
		}
		val torso = stance.torso
		val warps = ArrayList<Deformer.Warp>()
		for (side in listOf(Side.LEFT, Side.RIGHT)) {
			val arms = analysis.layers.filter { it.semantic.tag == SemanticTag.HANDWEAR && it.semantic.side == side && it.opaquePixels > 0 }
			if (arms.isEmpty()) continue
			val hosts = arms.map { parents[it.source.id.raw] ?: defaultParent(it) }.distinct()
			val (parentId, parentFrame) = hosts.singleOrNull() ?: continue
			if (worlds.any { it[parentId] == null }) continue
			val union = arms.map { rigLayerById.getValue(it.source.id.raw).bounds }.reduce(Bounds::union)
			val frame = union.expanded(0.06f)
			val shoulderX = torso.centerX + (if (frame.centerX >= torso.centerX) 1f else -1f) * torso.halfWidth
			val rest = mapBounds(frame, parentFrame)
			val cells = combos.mapIndexed { n, c ->
				val bodyX = axes[0].keys[c[0]]
				val lean = axes[1].keys[c[1]]
				val size = axes[2].keys[c[2]]
				val world = worlds[n].getValue(parentId)
				val points = FloatArray((ARM_HANG_COLUMNS + 1) * (ARM_HANG_ROWS + 1) * 2)
				for (row in 0..ARM_HANG_ROWS) for (column in 0..ARM_HANG_COLUMNS) {
					val i = (row * (ARM_HANG_COLUMNS + 1) + column) * 2
					val u = column.toFloat() / ARM_HANG_COLUMNS
					val v = row.toFloat() / ARM_HANG_ROWS
					val local = floatArrayOf(rest.left + u * rest.width, rest.top + v * rest.height)
					if (bodyX == 0f && lean == 0f && size == 0f) { points[i] = local[0]; points[i + 1] = local[1]; continue }
					val target = stance.armPoint((frame.left + u * frame.width).toDouble(), (frame.top + v * frame.height).toDouble(),
						shoulderX.toDouble(), lean, size, bodyX)
					val back = SkeletonRig.inverse(world, target[0].toFloat(), target[1].toFloat(), local)
					points[i] = back[0]
					points[i + 1] = back[1]
				}
				KeyformCell(c, WarpLatticeForm(points))
			}
			val id = DeformerId("DeformArmHang_" + (if (side == Side.LEFT) "L" else "R"))
			warps += Deformer.Warp(id, tr("model.deformer.armHang", arms.first().source.name),
				parentId, partId, ARM_HANG_ROWS, ARM_HANG_COLUMNS, true, KeyformGrid(axes, cells))
			frames[id.raw] = frame
			for (layer in arms) parents[layer.source.id.raw] = id to frame
		}
		return warps
	}

	/** An arm's hang warp: columns across the arm and rows down it. */
	private const val ARM_HANG_COLUMNS = 3
	private const val ARM_HANG_ROWS = 6

	private fun identityWarp(
		id: DeformerId,
		name: String,
		parent: DeformerId,
		frame: Bounds,
		parentFrame: Bounds,
		partId: PartId,
		rows: Int = 3,
		columns: Int = 3,
	): Deformer.Warp {
		val inParent = mapBounds(frame, parentFrame)
		val geometry = warpGrid(emptyList(), columns = columns, rows = rows) { u, v, _ ->
			(inParent.left + u * inParent.width) to (inParent.top + v * inParent.height)
		}
		return Deformer.Warp(id, name, parent, partId, rows, columns, true, geometry)
	}

	private val sideSuffixRegex = Regex("(?:[-_.\\s]+)(l|r|left|right|左|右)$", RegexOption.IGNORE_CASE)
	private val sidePrefixRegex = Regex("^(左|右)(?:[-_.\\s]+)?", RegexOption.IGNORE_CASE)

	internal fun pairBaseName(name: String): String {
		var s = Normalizer.normalize(name, Normalizer.Form.NFKC).trim()
		sideSuffixRegex.find(s)?.let {
			s = s.removeRange(it.range).trim()
		} ?: sidePrefixRegex.find(s)?.let {
			s = s.removeRange(it.range).trim()
		}
		return if (s.isNotBlank()) s else name.trim()
	}

	private fun uniquePairDeformerId(
		baseName: String,
		tag: SemanticTag,
		usedIds: MutableSet<String>,
	): DeformerId {
		val words = baseName.split(Regex("[^a-zA-Z0-9]+")).filter { it.isNotBlank() }
		val pascal = words.joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }
		val baseId = when {
			pascal.isNotBlank() -> "DeformPair_$pascal"
			tag != SemanticTag.UNKNOWN -> {
				val tagWords = tag.name.lowercase(Locale.ROOT).split('_').joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }
				"DeformPair_$tagWords"
			}
			else -> "DeformPair_Part"
		}
		var id = baseId
		var counter = 2
		while (!usedIds.add(id)) {
			id = "${baseId}_$counter"
			counter++
		}
		return DeformerId(id)
	}

	private fun isHandledByFaceRegion(layer: ClassifiedLayer, faceRig: NinePoseFaceRig): Boolean = when (layer.semantic.tag) {
		SemanticTag.IRIDES -> faceRig.regionFor(FaceFeature.IRIS, layer.semantic.side) != null
		SemanticTag.EYEWHITE, SemanticTag.EYELASH, SemanticTag.EYE_CLOSE ->
			faceRig.regionFor(FaceFeature.EYE, layer.semantic.side) != null
		SemanticTag.EYEBROW -> faceRig.regionFor(FaceFeature.BROW, layer.semantic.side) != null
		SemanticTag.EARS, SemanticTag.EARWEAR -> faceRig.regionFor(FaceFeature.EAR, layer.semantic.side) != null
		SemanticTag.NOSE -> faceRig.regionFor(FaceFeature.NOSE, layer.semantic.side) != null
		SemanticTag.MOUTH, SemanticTag.MOUTH_OPEN, SemanticTag.MOUTH_CLOSE,
		SemanticTag.TOOTH_T, SemanticTag.TOOTH_B, SemanticTag.TONGUE ->
			faceRig.regionFor(FaceFeature.MOUTH, layer.semantic.side) != null
		else -> false
	}

	/** A local inward socket on the left silhouette, inherited by skin only. */
	internal fun faceContourPoint(
		u: Float, v: Float, angleX: Float, strength: Float, socketY: Float, tuning: RigTuning = RigTuning(),
	): Pair<Float, Float> {
		val turn = (-angleX / 45f * strength).coerceIn(0f, 1f)
		val distance = (abs(v - socketY) / 0.18f).coerceIn(0f, 1f)
		val horizontal = (u / 0.35f).coerceIn(0f, 1f)
		// Two joined cubic Bezier segments have zero tangent at the socket and support edges.
		val socket = BezierWarp.cubic(1f, 1f, 0f, 0f, distance)
		val edge = BezierWarp.cubic(1f, 1f, 0f, 0f, horizontal)
		return (u + turn * tuning.faceContour / 100f * socket * edge) to v
	}

	internal fun featureDisplacementPoint(
		u: Float, v: Float, angleX: Float, angleY: Float, strength: Float,
		tuning: RigTuning = RigTuning(),
		aspectRatio: Float = 1f,
	): Pair<Float, Float> {
		val yaw = (angleX / 45f * strength).coerceIn(-1f, 1f)
		val pitch = (angleY / 30f * strength).coerceIn(-1f, 1f)
		// Cubic Bezier with endpoints 0 and handles 4/3 peaks at 1 at t=1/2.
		fun bow(t: Float): Float = BezierWarp.cubic(0f, 4f / 3f, 4f / 3f, 0f, t)
		val x = 0.5f + (u - 0.5f) * (1f - tuning.displacementNarrow / 100f * abs(yaw)) +
			yaw * (0.025f + tuning.displacementShift / 100f * bow(v))
		// Up: compress the whole height down toward the bottom, with extra compression
		// in the upper half. Down: compress only the lower half up toward the middle.
		// The squared half profiles are cubic Beziers with zero slope at their join.
		fun compressedV(value: Float): Float {
			fun halfCompression(t: Float) = BezierWarp.cubic(0f, 0f, 1f / 3f, 1f, t)
			return if (pitch > 0f) {
				value + pitch * (0.08f * (1f - value) +
					0.10f * halfCompression((1f - 2f * value).coerceAtLeast(0f)))
			} else {
				value + pitch * 0.10f * halfCompression((2f * value - 1f).coerceAtLeast(0f))
			}
		}
		// Canvas Y grows downwards; negative AngleY is a downward look (U-shaped rows).
		val y = compressedV(v) - pitch * (0.020f + tuning.displacementTilt / 100f * bow(u))
		// In canvas coordinates positive rotation is clockwise. Upper-left/lower-right
		// have yaw*pitch < 0. Rotate the entire curved surface about its displaced center;
		// pure horizontal/vertical poses stay unchanged. Correct for non-square face frames.
		val radians = -yaw * pitch * (tuning.displacementTwist * PI.toFloat() / 180f)
		val centerX = 0.5f + yaw * 0.080f
		val centerY = compressedV(0.5f) - pitch * 0.070f
		val dx = (x - centerX) * aspectRatio
		val dy = y - centerY
		val cosine = cos(radians)
		val sine = sin(radians)
		return (centerX + (dx * cosine - dy * sine) / aspectRatio) to
			(centerY + dx * sine + dy * cosine)
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

	private fun gazeWarp(region: FaceRegion, parent: DeformerId, part: PartId, tuning: RigTuning): Deformer.Warp {
		val geometry = warpGrid(
			listOf(axis(StandardParameters.EYE_BALL_X, -1f, 0f, 1f), axis(StandardParameters.EYE_BALL_Y, -1f, 0f, 1f)),
			2,
			2,
		) { u, v, values ->
			gazePoint(u, v, values[0], values[1], tuning)
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
		yawPerspective: Float = 0f,
	): Deformer.Warp {
		val inHead = mapBounds(frame, head)
		val grid = warpGrid(
			ninePoseAxes(),
			3,
			4,
		) { u, v, values ->
			hairFollowPoint(inHead, u, v, values[0], values[1], yawParallax, pitchParallax, yawPerspective)
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

	private fun buildGridMesh(
		layer: ClassifiedLayer,
		parentFrame: Bounds,
		headSpace: HeadCoordinateSpace?,
		config: PipelineConfig,
		meshCache: PreviewMeshCache?,
		unitScale: Float,
	): MeshData {
		val width = max(1, layer.source.raster.width)
		val height = max(1, layer.source.raster.height)
		val (settings, unitSpacing) = meshSettings(layer, config)
		val effectiveSpacing = unitSpacing * unitScale

		// Authored tooth layers may contain several disconnected teeth. Keep their complete texture;
		// the mouth clipping id supplies the visible boundary.
		if (layer.semantic.tag in setOf(SemanticTag.TOOTH_T, SemanticTag.TOOTH_B)) {
			return buildRectangularFallbackMesh(layer, parentFrame, headSpace, effectiveSpacing)
		}
		val adaptive = if (meshCache != null) meshCache.generate(width, height, layer.source.raster.rgba, config.alphaThreshold, settings, unitScale)
		else AdaptiveMeshGenerator.generate(width, height, layer.source.raster.rgba, config.alphaThreshold, settings, unitScale)
		if (adaptive != null) {
			val positions = FloatArray(adaptive.positions.size)
			val canvas = FloatArray(adaptive.positions.size)
			val uvs = FloatArray(adaptive.positions.size)
			for (index in adaptive.positions.indices step 2) {
				// Edge rows may overhang the raster, like manually placed vertices; UVs extrapolate linearly.
				val localX = adaptive.positions[index]
				val localY = adaptive.positions[index + 1]
				val canvasX = layer.source.bounds.left + localX
				val canvasY = layer.source.bounds.top + localY
				val rigPoint = headSpace?.toAligned(canvasX, canvasY) ?: (canvasX to canvasY)
				positions[index] = normalizeX(rigPoint.first, parentFrame)
				positions[index + 1] = normalizeY(rigPoint.second, parentFrame)
				canvas[index] = rigPoint.first
				canvas[index + 1] = rigPoint.second
				// Layer offsets, bound to the atlas by UvBinding.
				uvs[index] = localX
				uvs[index + 1] = localY
			}
			return MeshData(DrawableMesh(positions, uvs, adaptive.indices), canvas)
		}
		return buildRectangularFallbackMesh(layer, parentFrame, headSpace, effectiveSpacing)
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
            layer.semantic.tag in setOf(SemanticTag.MOUTH, SemanticTag.MOUTH_OPEN)) 0f
            else override?.outerMargin ?: config.meshOuterMargin
		// Face defaults to a dual edge band (legacy inner-margin envelope); other parts use the global mode.
		val edgeMode = override?.edgeMode
			?: if (layer.semantic.tag == SemanticTag.FACE) MeshEdgeMode.DOUBLE else config.meshEdgeMode
		val edgeWidth = override?.edgeWidth ?: config.meshEdgeWidth
		val effectiveSpacing = if (config.mouthOutlineEnabled && !config.meshOnly &&
            layer.semantic.tag in setOf(SemanticTag.MOUTH, SemanticTag.MOUTH_OPEN)) {
            override?.maxEdgeDistance ?: max(6f, config.meshMaxEdgeDistance * semanticDensity)
        } else {
            override?.maxEdgeDistance ?: max(12f, config.meshMaxEdgeDistance * semanticDensity)
        }
		val effectiveInteriorDensity = override?.interiorDensity ?: max(12f, config.meshInteriorDensity * semanticDensity)

		val settings = MeshSettings(outerMargin, edgeMode, edgeWidth, effectiveSpacing,
			effectiveInteriorDensity, override?.fillAlgorithm ?: config.meshFillAlgorithm,
			override?.suppressBoundaryDiagonals ?: config.meshSuppressBoundaryDiagonals,
			override?.fillParameters ?: config.meshFillParameters)
		return settings to effectiveSpacing
	}

	/** Conservative fallback for pathological alpha masks or degenerate one-pixel slivers. */
	private fun buildRectangularFallbackMesh(
		layer: ClassifiedLayer,
		parentFrame: Bounds,
		headSpace: HeadCoordinateSpace?,
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
				val rigPoint = headSpace?.toAligned(canvasX, canvasY) ?: (canvasX to canvasY)
				positions[vertex * 2] = normalizeX(rigPoint.first, parentFrame)
				positions[vertex * 2 + 1] = normalizeY(rigPoint.second, parentFrame)
				canvas[vertex * 2] = rigPoint.first
				canvas[vertex * 2 + 1] = rigPoint.second
				uvs[vertex * 2] = u * width
				uvs[vertex * 2 + 1] = v * height
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
		eyeWhiteBounds: List<Bounds>,
		mouthAperture: Bounds?,
        config: PipelineConfig,
        mouthPaths: List<DeformPath> = emptyList(),
	): KeyformGrid<MeshDeltaForm> {
		val tag = layer.semantic.tag
		return when (tag) {
			SemanticTag.EYEWHITE, SemanticTag.EYELASH ->
				eyeClosureGrid(layer, data, parentFrame, faceRig, eyeWhiteBounds, config.rigTuning)
			// Blink does not key the iris directly. The independent physics output supplies a small,
			// delayed squash/stretch while eye-white clipping removes it as the lid closes.
			SemanticTag.IRIDES -> irisJellyGrid(layer, data, parentFrame, config.rigTuning)
			SemanticTag.EYEBROW -> eyebrowGrid(layer, data, parentFrame, config.rigTuning)
			SemanticTag.MOUTH, SemanticTag.MOUTH_OPEN -> mouthWholeGrid(data, parentFrame, mouthAperture ?: layer.bounds, config, mouthPaths)
			SemanticTag.MOUTH_CLOSE, SemanticTag.TOOTH_T, SemanticTag.TOOTH_B, SemanticTag.TONGUE ->
				zeroMeshGrid(data.mesh.positions.size)
			else -> zeroMeshGrid(data.mesh.positions.size)
		}
	}

	private fun eyeClosureGrid(
		layer: ClassifiedLayer,
		data: MeshData,
		frame: Bounds,
		faceRig: NinePoseFaceRig,
		eyeWhiteBounds: List<Bounds>,
		tuning: RigTuning,
	): KeyformGrid<MeshDeltaForm> {
		val parameter = if (layer.semantic.side == Side.LEFT) StandardParameters.EYE_L_OPEN else StandardParameters.EYE_R_OPEN
		// Column sampling is defined in the source raster's canvas X axis.  Once the head has been
		// aligned, the alpha-weighted centroid remains correct while that column function no longer is.
		val eyelashCenterline = if (layer.semantic.tag == SemanticTag.EYELASH && faceRig.initialAngleZ == 0f) {
			eyelashCenterline(layer)
		} else null
		val axes = if (layer.semantic.side == Side.NONE) {
			listOf(axis(StandardParameters.EYE_L_OPEN, 0f, 1f), axis(StandardParameters.EYE_R_OPEN, 0f, 1f))
		} else listOf(axis(parameter, 0f, 1f))
		return grid(axes) { values ->
			val delta = FloatArray(data.mesh.positions.size)
			for (vertex in data.mesh.positions.indices step 2) {
				val canvasX = data.rigPositions[vertex]
				val canvasY = data.rigPositions[vertex + 1]
				val openness = when (layer.semantic.side) {
					Side.LEFT -> values[0]
					Side.RIGHT -> values[0]
					Side.NONE -> if (canvasX >= faceRig.centerX) values[0] else values[1]
				}
				val whiteBounds = eyeWhiteBounds.minByOrNull { abs(it.centerX - canvasX) } ?: layer.bounds
				val closed = eyeClosurePoint(
					canvasX,
					canvasY,
					layer.bounds,
					whiteBounds,
					layer.semantic.tag,
					eyelashCenterline?.let { sampleCenterline(it, layer, canvasX) } ?: layer.centroidY,
					tuning,
				)
				delta[vertex] = (closed.first - canvasX) / frame.width.coerceAtLeast(1e-4f) * (1f - openness)
				delta[vertex + 1] = (closed.second - canvasY) / frame.height.coerceAtLeast(1e-4f) * (1f - openness)
			}
			MeshDeltaForm(delta)
		}
	}

	/**
	 * Closed eyes share one curve derived from the eye-white bounds.  The common centre line is what
	 * keeps the shrunken white behind the lash. Every eyelash vertex in the same vertical slice is
	 * measured from the alpha-weighted source centreline, so the texture follows the target curve
	 * instead of adding its authored curvature on top of it.
	 */
	internal fun eyeClosurePoint(
		sourceX: Float,
		sourceY: Float,
		layerBounds: Bounds,
		eyeWhiteBounds: Bounds,
		tag: SemanticTag,
		sourceAnchorY: Float = layerBounds.centerY,
		tuning: RigTuning = RigTuning(),
	): Pair<Float, Float> {
		val halfWidth = (eyeWhiteBounds.width * 0.5f).coerceAtLeast(1e-4f)
		val normalizedX = ((sourceX - eyeWhiteBounds.centerX) / halfWidth).coerceIn(-1f, 1f)
		val arch = max(0f, 1f - normalizedX * normalizedX)
		// By default the trough sits at 72% of the eye-white height and the endpoints at 34%: a deep U
		// whose corners move little.
		val edgeY = eyeWhiteBounds.top + eyeWhiteBounds.height * tuning.blinkCorner / 100f
		val curveY = edgeY + max(1.5f, eyeWhiteBounds.height * tuning.blinkDepth / 100f) * arch
		val layerHeight = layerBounds.height.coerceAtLeast(1f)
		val verticalScale = when (tag) {
			SemanticTag.EYELASH -> tuning.eyelashSquash / 100f
			SemanticTag.EYEWHITE -> (1.2f / layerHeight).coerceIn(0.015f, 0.55f)
			else -> (1.2f / layerHeight).coerceIn(0.015f, 0.55f)
		}
		return sourceX to curveY + (sourceY - sourceAnchorY) * verticalScale
	}

	/** Alpha-weighted centre of every source column, with transparent gaps linearly bridged. */
	private fun eyelashCenterline(layer: ClassifiedLayer): FloatArray? {
		val width = layer.source.raster.width
		val height = layer.source.raster.height
		if (width <= 0 || height <= 0) return null
		val rgba = layer.source.raster.rgba
		val result = FloatArray(width) { Float.NaN }
		for (x in 0 until width) {
			var weightSum = 0f
			var weightedY = 0f
			for (y in 0 until height) {
				val alpha = (rgba[(y * width + x) * 4 + 3].toInt() and 0xff).toFloat()
				if (alpha == 0f) continue
				weightSum += alpha
				weightedY += (y + 0.5f) * alpha
			}
			if (weightSum > 0f) result[x] = layer.source.bounds.top + weightedY / weightSum
		}
		val first = result.indexOfFirst { !it.isNaN() }
		if (first < 0) return null
		for (x in 0 until first) result[x] = result[first]
		var previous = first
		for (x in first + 1 until width) {
			if (result[x].isNaN()) continue
			val gap = x - previous
			if (gap > 1) {
				val start = result[previous]
				val end = result[x]
				for (step in 1 until gap) result[previous + step] = start + (end - start) * step / gap
			}
			previous = x
		}
		for (x in previous + 1 until width) result[x] = result[previous]
		return result
	}

	private fun sampleCenterline(centerline: FloatArray, layer: ClassifiedLayer, canvasX: Float): Float {
		val sourceX = (canvasX - layer.source.bounds.left).coerceIn(0f, (centerline.size - 1).toFloat())
		val left = sourceX.toInt()
		val right = (left + 1).coerceAtMost(centerline.lastIndex)
		return centerline[left] + (centerline[right] - centerline[left]) * (sourceX - left)
	}

	private fun matchingEyeWhiteBounds(
		layer: ClassifiedLayer,
		eyeWhites: List<ClassifiedLayer>,
	): List<Bounds> {
		if (eyeWhites.isEmpty()) return listOf(layer.bounds)
		val sameVariantAndSide = eyeWhites.filter {
			it.semantic.side == layer.semantic.side && it.semantic.variant == layer.semantic.variant
		}
		val sameSide = eyeWhites.filter { it.semantic.side == layer.semantic.side }
		val unspecified = eyeWhites.filter { it.semantic.side == Side.NONE }
		val candidates = when {
			sameVariantAndSide.isNotEmpty() -> sameVariantAndSide
			sameSide.isNotEmpty() -> sameSide
			layer.semantic.side == Side.NONE -> eyeWhites
			unspecified.isNotEmpty() -> unspecified
			else -> eyeWhites
		}
		if (layer.semantic.side == Side.NONE && candidates.size > 1) return candidates.map { it.bounds }
		return listOfNotNull(nearestLayer(layer, candidates)?.bounds)
	}

	private fun eyebrowGrid(layer: ClassifiedLayer, data: MeshData, frame: Bounds, tuning: RigTuning): KeyformGrid<MeshDeltaForm> {
		val parameter = if (layer.semantic.side == Side.LEFT) StandardParameters.BROW_L_Y else StandardParameters.BROW_R_Y
		return oneDimGrid(parameter, floatArrayOf(-1f, 0f, 1f)) { value ->
			val delta = FloatArray(data.mesh.positions.size)
			val dy = -value * layer.bounds.height * tuning.browLift / 100f / frame.height
			for (index in 1 until delta.size step 2) delta[index] = dy
			MeshDeltaForm(delta)
		}
	}

	private fun irisJellyGrid(layer: ClassifiedLayer, data: MeshData, frame: Bounds, tuning: RigTuning): KeyformGrid<MeshDeltaForm> =
		oneDimGrid(StandardParameters.EYE_BALL_FORM, floatArrayOf(-1f, 0f, 1f)) { value ->
			val delta = FloatArray(data.mesh.positions.size)
			for (index in data.rigPositions.indices step 2) {
				val sourceX = data.rigPositions[index]
				val sourceY = data.rigPositions[index + 1]
				val target = irisJellyPoint(sourceX, sourceY, layer.centroidX, layer.centroidY, value, tuning)
				delta[index] = (target.first - sourceX) / frame.width.coerceAtLeast(1e-4f)
				delta[index + 1] = (target.second - sourceY) / frame.height.coerceAtLeast(1e-4f)
			}
			MeshDeltaForm(delta)
		}

	/** A restrained squash/stretch: vertical rebound is stronger than horizontal compensation. */
	internal fun irisJellyPoint(
		sourceX: Float,
		sourceY: Float,
		pivotX: Float,
		pivotY: Float,
		jelly: Float,
		tuning: RigTuning = RigTuning(),
	): Pair<Float, Float> {
		val amount = jelly.coerceIn(-1f, 1f)
		val scaleX = 1f - amount * tuning.irisJellySquash / 100f
		val scaleY = 1f + amount * tuning.irisJellyStretch / 100f
		return pivotX + (sourceX - pivotX) * scaleX to pivotY + (sourceY - pivotY) * scaleY
	}

	/**
	 * The mouth bitmap is authored fully open. ParamMouthOpenY=1 preserves it exactly; zero compresses
	 * the complete drawable to a seam (zero height when independent lips are enabled). Optional teeth and tongue are intentionally
	 * not morphed: the animated mouth drawable clips them and their opacity fades near the closed key.
	 */
	/**
	 * The mouth bitmap is authored fully open. ParamMouthOpenY=1 preserves it exactly; zero compresses
	 * the complete drawable to a seam (zero height when independent lips are enabled). Optional teeth and tongue are intentionally
	 * not morphed: the animated mouth drawable clips them and their opacity fades near the closed key.
     * When deform paths are available, they drive the mouth mesh deformation via MLS (DeformPathTools.deformAll).
	 */
	private fun mouthWholeGrid(
        data: MeshData,
        parentFrame: Bounds,
        aperture: Bounds,
        config: PipelineConfig,
        mouthPaths: List<DeformPath> = emptyList(),
    ): KeyformGrid<MeshDeltaForm> =
		grid(
			mouthAxes(),
		) { values ->
			val delta = FloatArray(data.mesh.positions.size)
			for (index in data.rigPositions.indices step 2) {
				val sourceX = data.rigPositions[index]
				val sourceY = data.rigPositions[index + 1]
				val target = mouthWholePoint(sourceX, sourceY, aperture, values[0], values[1], config.mouthShape, config.mouthOutlineEnabled, config.mouthCurve, config.rigTuning)
				delta[index] = (target.first - sourceX) / parentFrame.width.coerceAtLeast(1e-4f)
				delta[index + 1] = (target.second - sourceY) / parentFrame.height.coerceAtLeast(1e-4f)
			}
			MeshDeltaForm(delta)
		}

    private fun createMouthDeformPaths(drawableId: DrawableId, data: MeshData, frame: Bounds): List<DeformPath> {
        val colCount = MouthContour.DEFAULT_SEGMENTS + 1
        if (data.mesh.positions.size < colCount * 6) return emptyList()
        val count = 9
        val sampleCols = (0 until count).map { i -> (i * (colCount - 1) + (count - 1) / 2) / (count - 1) }
        val upperPoints = sampleCols.mapNotNull { c ->
            val v = c * 3
            val px = data.mesh.positions[v * 2]
            val py = data.mesh.positions[v * 2 + 1]
            try {
                DeformPathTools.bind(data.mesh.positions, data.mesh.indices, px, py, corner = (c == 0 || c == colCount - 1))
            } catch (_: Throwable) {
                null
            }
        }
        val lowerPoints = sampleCols.mapNotNull { c ->
            val v = c * 3 + 2
            val px = data.mesh.positions[v * 2]
            val py = data.mesh.positions[v * 2 + 1]
            try {
                DeformPathTools.bind(data.mesh.positions, data.mesh.indices, px, py, corner = (c == 0 || c == colCount - 1))
            } catch (_: Throwable) {
                null
            }
        }
        if (upperPoints.size < 2 || lowerPoints.size < 2) return emptyList()
        return listOf(
            DeformPath(
                id = generatedPathId(drawableId, "upper"),
                drawableId = drawableId,
                points = upperPoints,
                width = DeformPath.DEFAULT_WIDTH,
                hardness = DeformPath.DEFAULT_HARDNESS,
                closed = false,
                editLevel = 2,
            ),
            DeformPath(
                id = generatedPathId(drawableId, "lower"),
                drawableId = drawableId,
                points = lowerPoints,
                width = DeformPath.DEFAULT_WIDTH,
                hardness = DeformPath.DEFAULT_HARDNESS,
                closed = false,
                editLevel = 2,
            ),
        )
    }

    // Shared columns guarantee that the fill and both lip ribbons interpolate identical curves.
    private fun mouthContourMesh(data: MeshData, layer: ClassifiedLayer, frame: Bounds,
                                 space: HeadCoordinateSpace?): MeshData {
        val columns = MouthContour.uniformColumns(data, MouthContour.DEFAULT_SEGMENTS)
        if (columns.size < 2) return data
        val positions = FloatArray(columns.size * 6)
        val rig = FloatArray(positions.size)
        val uvs = FloatArray(positions.size)
        val width = max(1, layer.source.raster.width).toFloat()
        val height = max(1, layer.source.raster.height).toFloat()
        for ((i, col) in columns.withIndex()) {
            val topY = if (col.bottomY - col.topY < 0.5f) (col.topY + col.bottomY) * 0.5f - 0.25f else col.topY
            val botY = if (col.bottomY - col.topY < 0.5f) (col.topY + col.bottomY) * 0.5f + 0.25f else col.bottomY
            for (row in 0..2) {
                val j = i * 6 + row * 2
                val y = topY + (botY - topY) * row / 2f
                rig[j] = col.x; rig[j + 1] = y
                positions[j] = normalizeX(col.x, frame); positions[j + 1] = normalizeY(y, frame)
                val canvas = space?.toCanvas(col.x, y) ?: (col.x to y)
                val localX = (canvas.first - layer.source.bounds.left).coerceIn(0f, width)
                val localY = (canvas.second - layer.source.bounds.top).coerceIn(0f, height)
                uvs[j] = localX
                uvs[j + 1] = localY
            }
        }
        val indices = (0 until columns.lastIndex).flatMap { i -> (0..1).flatMap { row ->
            val a = i * 3 + row; listOf(a, a + 1, a + 3, a + 1, a + 4, a + 3)
        }}.toIntArray()
        return MeshData(DrawableMesh(positions, uvs, indices), rig)
    }

    private fun mouthAxes(): List<KeyformAxis> = listOf(
        axis(StandardParameters.MOUTH_FORM, -1f, 0f, 1f),
        axis(StandardParameters.MOUTH_OPEN, 0f, 0.5f, 1f),
    )

    private fun mouthOutline(
        owner: Drawable,
        data: MeshData,
        frame: Bounds,
        aperture: Bounds,
        config: PipelineConfig,
        side: Int,
        layer: ClassifiedLayer,
        space: HeadCoordinateSpace?,
    ): Pair<Drawable, DeformPath?> {
        val columns = MouthContour.uniformColumns(data, MouthContour.DEFAULT_SEGMENTS)
        val path = MouthContour.crossedPath(columns, side)
        val overlap = MouthContour.overlapCount(columns.size)
        val joins = listOf(overlap, overlap + columns.lastIndex)
        val radius = config.mouthThickness.coerceIn(0.5f, 8f) * 0.5f
        fun normalized(points: FloatArray): FloatArray = FloatArray(points.size) { i ->
            if (i % 2 == 0) normalizeX(points[i], frame) else normalizeY(points[i], frame)
        }
        val rawPositions = MouthStrokeMesh.positions(path, radius, joins)
        val positions = normalized(rawPositions)
        val uvs = FloatArray(rawPositions.size)
        val texWidth = layer.source.raster.width.toFloat()
        val texHeight = layer.source.raster.height.toFloat()
        for (i in 0 until rawPositions.size step 2) {
            val rx = rawPositions[i]
            val ry = rawPositions[i + 1]
            val canvas = space?.toCanvas(rx, ry) ?: (rx to ry)
            val localX = (canvas.first - layer.source.bounds.left).coerceIn(0f, texWidth)
            val localY = (canvas.second - layer.source.bounds.top).coerceIn(0f, texHeight)
            // Layer offsets: UvBinding addresses them to the ribbon's atlas slice.
            uvs[i] = localX
            uvs[i + 1] = localY
        }
        val geometry = grid(mouthAxes()) { values ->
            val transformed = path.map { p ->
                mouthWholePoint(p.first, p.second, aperture, values[0], values[1], config.mouthShape, true, config.mouthCurve, config.rigTuning)
            }
            val target = normalized(MouthStrokeMesh.positions(transformed, radius, joins))
            MeshDeltaForm(FloatArray(positions.size) { target[it] - positions[it] })
        }
        val indices = MouthStrokeMesh.indices(path.size, joins)
        val lipDrawable = owner.copy(
            id = DrawableId(owner.id.raw + "_lip_" + side),
            name = layer.source.name,
            mesh = DrawableMesh(positions, uvs, indices),
            geometryGrid = geometry,
            texturePage = 0,
            atlasTileId = PuppetSourceAtlas.tileIdFor(layer.source.id.raw),
            blendMode = BlendMode.Normal,
            isVisible = layerVisibility(config, layer.source.id.raw, layer.source.visible),
            drawOrder = (config.drawOrderOverrides[layer.source.id.raw] ?: (owner.drawOrder + 1f)).coerceIn(0f, 1000f),
        )
        val deformPath = if (columns.size >= 5) {
            val startIdx = overlap
            val endIdx = overlap + columns.lastIndex
            val count = 9
            val span = endIdx - startIdx
            val sampleIdxs = (0 until count).map { i -> startIdx + (i * span + (count - 1) / 2) / (count - 1) }
            val points = sampleIdxs.mapNotNull { idx ->
                val p = path[idx]
                val nx = normalizeX(p.first, frame)
                val ny = normalizeY(p.second, frame)
                try {
                    DeformPathTools.bind(positions, indices, nx, ny, corner = (idx == startIdx || idx == endIdx))
                } catch (_: Throwable) {
                    null
                }
            }
            if (points.size >= 2) {
                DeformPath(
                    id = generatedPathId(lipDrawable.id, "lip"),
                    drawableId = lipDrawable.id,
                    points = points,
                    width = DeformPath.DEFAULT_WIDTH,
                    hardness = DeformPath.DEFAULT_HARDNESS,
                    closed = false,
                    editLevel = 2,
                )
            } else null
        } else null
        return lipDrawable to deformPath
    }

	internal fun mouthWholePoint(
		sourceX: Float,
		sourceY: Float,
		aperture: Bounds,
		mouthForm: Float,
		mouthOpen: Float,
        shape: String = "smile",
        exactClose: Boolean = false,
        curve: MouthCurve = MouthCurve.preset("smile"),
		tuning: RigTuning = RigTuning(),
	): Pair<Float, Float> {
		val open = mouthOpen.coerceIn(0f, 1f)
		val easedOpen = open * open * (3f - 2f * open)
		val form = mouthForm.coerceIn(-1f, 1f)
		val halfWidth = (aperture.width * 0.5f).coerceAtLeast(1e-4f)
		val normalizedX = ((sourceX - aperture.centerX) / halfWidth).coerceIn(-1.25f, 1.25f)
		val narrow = tuning.mouthClosedNarrow / 100f
		val horizontalScale = 1f - narrow + easedOpen * narrow + form * tuning.mouthFormWiden / 100f
		val targetX = aperture.centerX + (sourceX - aperture.centerX) * horizontalScale
		val seamY = aperture.top + aperture.height * tuning.mouthSeam / 100f
		val closedScale = if (exactClose) 0f else (1.25f / aperture.height.coerceAtLeast(1f)).coerceIn(0.018f, 0.12f)
		val verticalScale = closedScale + easedOpen * (1f - closedScale)
		val cornerWeight = abs(normalizedX).toDouble().pow(1.55).toFloat().coerceAtMost(1.35f)
		val expressionY = -form * aperture.height * (tuning.mouthSmileBase + cornerWeight * tuning.mouthSmile) / 100f * (0.72f + easedOpen * 0.28f)
        val effectiveCurve = if (shape == "custom") curve else MouthCurve.preset(shape)
        val presetY = effectiveCurve.yAt((normalizedX + 1f) * 0.5f) * aperture.height * (1f - easedOpen)
        val targetY = seamY + (sourceY - seamY) * verticalScale + expressionY + presetY
		return targetX to targetY
	}

	internal fun zeroMeshGrid(size: Int): KeyformGrid<MeshDeltaForm> =
		KeyformGrid(emptyList(), listOf(KeyformCell(IntArray(0), MeshDeltaForm(FloatArray(size)))))

	private fun buildChannels(
		layer: ClassifiedLayer,
		override: LayerClassificationOverride?,
		switchParamKeys: Map<String, FloatArray>,
		tuning: RigTuning,
	): ChannelGrids {
		val type = override?.type ?: layer.semantic.type
		val opacityGrid = when (type) {
			LayerType.TOGGLE -> {
				val paramName = (override?.parameter ?: layer.semantic.parameter).trim()
				if (paramName.isNotBlank()) {
					val parameter = ParameterId(paramName)
					scalarGrid(parameter, floatArrayOf(0f, 1f)) { value -> value }
				} else null
			}
			LayerType.SWITCH -> {
				val paramName = (override?.parameter ?: layer.semantic.parameter).trim()
				val switchId = override?.switchId ?: layer.semantic.switchId
				val keys = switchParamKeys[paramName]
				if (paramName.isNotBlank() && keys != null && keys.isNotEmpty()) {
					val parameter = ParameterId(paramName)
					scalarGrid(parameter, keys) { key ->
						if (key.toInt() == switchId) 1f else 0f
					}
				} else null
			}
			LayerType.PRESET -> {
				when (layer.semantic.tag) {
					SemanticTag.EYE_CLOSE -> {
						val parameter = if (layer.semantic.side == Side.LEFT) StandardParameters.EYE_L_OPEN else StandardParameters.EYE_R_OPEN
						scalarGrid(parameter, floatArrayOf(0f, 1f)) { open -> 1f - open }
					}
					SemanticTag.MOUTH_CLOSE -> scalarGrid(StandardParameters.MOUTH_OPEN, floatArrayOf(0f, 1f)) { 1f - it }
					SemanticTag.TONGUE, SemanticTag.TOOTH_T, SemanticTag.TOOTH_B ->
						scalarGrid(StandardParameters.MOUTH_OPEN, floatArrayOf(0f, tuning.teethFade / 100f, 1f)) { open ->
							(open / (tuning.teethFade / 100f)).coerceIn(0f, 1f)
						}
					else -> null
				}
			}
		}
		return opacityGrid?.let { ChannelGrids(mapOf(FormChannel.OPACITY to it)) } ?: ChannelGrids.Empty
	}

	private fun scalarGrid(parameter: ParameterId, keys: FloatArray, value: (Float) -> Float): KeyformGrid<ChannelValue> =
		oneDimGrid(parameter, keys) { key -> ChannelValue.Scalar(value(key)) }

	private fun parameterTree(customParameters: List<Parameter> = emptyList()): List<ParameterNode> {
		fun group(id: String, name: String, parameters: List<ParameterId>) = ParameterNode.Group(
			ParameterGroupId(id), name, true, parameters.map { ParameterNode.Param(it) },
		)
		val base = listOf(
			group("ParamGroupFace", tr("model.group.face"), listOf(StandardParameters.ANGLE_X, StandardParameters.ANGLE_Y, StandardParameters.ANGLE_Z)),
			group("ParamGroupEyes", tr("model.group.eyes"), listOf(StandardParameters.EYE_L_OPEN, StandardParameters.EYE_R_OPEN, StandardParameters.EYE_BALL_X, StandardParameters.EYE_BALL_Y, StandardParameters.EYE_BALL_FORM)),
			group("ParamGroupBrows", tr("model.group.brows"), listOf(StandardParameters.BROW_L_Y, StandardParameters.BROW_R_Y)),
			group("ParamGroupMouth", tr("model.group.mouth"), listOf(StandardParameters.MOUTH_FORM, StandardParameters.MOUTH_OPEN)),
			group("ParamGroupBody", tr("model.group.body"), listOf(StandardParameters.BODY_X, StandardParameters.BODY_Y, StandardParameters.BODY_Z, StandardParameters.BODY_LEAN, StandardParameters.PROPORTION, StandardParameters.BREATH)),
			group("ParamGroupPhysics", tr("model.group.physics"), listOf(StandardParameters.HAIR_FRONT, StandardParameters.HAIR_BACK)),
		)
		return if (customParameters.isNotEmpty()) {
			base + group("ParamGroupCustom", tr("model.group.custom"), customParameters.map { it.id })
		} else {
			base
		}
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
		return if (region.side == Side.NONE) feature else tr("model.feature.name", sideDisplay(region.side), feature)
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

	/** Whether any edit names a drawable by its split id, i.e. was authored against the split naming. */
	private fun RigEditOverlay.referencesSplitDrawable(): Boolean {
		fun split(id: String) = id.startsWith(SPLIT_DRAWABLE_PREFIX)
		val quoted = "\"$SPLIT_DRAWABLE_PREFIX"
		return keyformSetEdits.any { split(it.target.id) } ||
			keyformDeleteEdits.any { split(it.target.id) } ||
			keyformCopyEdits.any { split(it.sourceTarget.id) || split(it.destinationTarget.id) } ||
			warpEdits.any { warp -> warp.meshIds.any(::split) } ||
			skeleton?.bones.orEmpty().any { bone -> bone.drawableIds.any(::split) } ||
			authoringJournal.any { quoted in it.toString() } ||
			structureEdits.any { quoted in it.toString() }
	}

	private const val SPLIT_DRAWABLE_PREFIX = "ArtMeshSplit"

	private fun stableSplitDrawableId(layerId: String, reserved: Collection<DrawableId>): DrawableId {
		val hash = UUID.nameUUIDFromBytes(layerId.toByteArray(Charsets.UTF_8))
			.toString().replace("-", "")
		val base = "$SPLIT_DRAWABLE_PREFIX$hash"
		var candidate = base
		var suffix = 2
		while (reserved.any { it.raw == candidate }) candidate = "$base${suffix++}"
		return DrawableId(candidate)
	}

	private fun blendMode(blend: LayerBlend): BlendMode = when (blend) {
		LayerBlend.Add, LayerBlend.AddGlow, LayerBlend.LinearLight -> BlendMode.AdditivePremultiplied
		LayerBlend.Multiply, LayerBlend.LinearBurn, LayerBlend.ColorBurn -> BlendMode.MultiplyPremultiplied
		else -> BlendMode.Normal
	}

	private fun inferredGroup(layer: ClassifiedLayer, anchors: RigAnchors): LayerGroup =
		if (layer.semantic.tag.group != LayerGroup.UNKNOWN) layer.semantic.tag.group
		else if (layer.bounds.centerY <= anchors.face.bottom) LayerGroup.HEAD else LayerGroup.BODY

	private fun ClassifiedLayer.inHeadSpace(space: HeadCoordinateSpace): ClassifiedLayer {
		val alignedCenter = space.toAligned(centroidX, centroidY)
		return copy(
			bounds = space.boundsToAligned(bounds),
			centroidX = alignedCenter.first,
			centroidY = alignedCenter.second,
		)
	}

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

	private fun neutralValidationBounds(
		layer: ClassifiedLayer,
		data: MeshData,
		mouthAperture: Bounds?,
		headSpace: HeadCoordinateSpace?,
		meshOnly: Boolean = false,
        config: PipelineConfig = PipelineConfig(),
	): Bounds {
		var left = Float.POSITIVE_INFINITY
		var top = Float.POSITIVE_INFINITY
		var right = Float.NEGATIVE_INFINITY
		var bottom = Float.NEGATIVE_INFINITY
		val isMouth = !meshOnly && layer.semantic.tag in setOf(SemanticTag.MOUTH, SemanticTag.MOUTH_OPEN) && mouthAperture != null
		for (index in data.rigPositions.indices step 2) {
			val rigPoint = if (isMouth) {
				mouthWholePoint(
					data.rigPositions[index],
					data.rigPositions[index + 1],
					mouthAperture,
					mouthForm = 0f,
					mouthOpen = 0f,
					shape = config.mouthShape,
					exactClose = config.mouthOutlineEnabled,
					curve = config.mouthCurve,
					tuning = config.rigTuning,
				)
			} else {
				data.rigPositions[index] to data.rigPositions[index + 1]
			}
			val point = headSpace?.toCanvas(rigPoint.first, rigPoint.second) ?: rigPoint
			left = minOf(left, point.first)
			top = minOf(top, point.second)
			right = maxOf(right, point.first)
			bottom = maxOf(bottom, point.second)
		}
		return if (left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite()) {
			Bounds(left, top, right, bottom)
		} else {
			layer.bounds
		}
	}

	private fun neutralLipBounds(
		data: MeshData,
		aperture: Bounds,
		config: PipelineConfig,
		side: Int,
		space: HeadCoordinateSpace?,
		fallback: Bounds,
	): Bounds {
		val columns = MouthContour.uniformColumns(data, MouthContour.DEFAULT_SEGMENTS)
		if (columns.size < 2) return fallback
		val path = MouthContour.crossedPath(columns, side)
		val overlap = MouthContour.overlapCount(columns.size)
		val joins = listOf(overlap, overlap + columns.lastIndex)
		val radius = config.mouthThickness.coerceIn(0.5f, 8f) * 0.5f
		val transformed = path.map { p ->
			mouthWholePoint(p.first, p.second, aperture, 0f, 0f, config.mouthShape, true, config.mouthCurve, config.rigTuning)
		}
		val rawPositions = MouthStrokeMesh.positions(transformed, radius, joins)
		var left = Float.POSITIVE_INFINITY
		var top = Float.POSITIVE_INFINITY
		var right = Float.NEGATIVE_INFINITY
		var bottom = Float.NEGATIVE_INFINITY
		for (i in rawPositions.indices step 2) {
			val canvas = space?.toCanvas(rawPositions[i], rawPositions[i + 1]) ?: (rawPositions[i] to rawPositions[i + 1])
			left = minOf(left, canvas.first)
			top = minOf(top, canvas.second)
			right = maxOf(right, canvas.first)
			bottom = maxOf(bottom, canvas.second)
		}
		return if (left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite()) {
			Bounds(left, top, right, bottom)
		} else {
			fallback
		}
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

	/**
	 * The head's warps keyed on Angle Y with the lean joining their axes, so the face bows with the body:
	 * leaning in it turns down by [RigTuning.faceLeanDown] degrees of Angle Y, leaning back up by [RigTuning.faceLeanUp].
	 * At each lean key a cell holds the warp's own lattice at Angle Y moved so, read between its keys as
	 * the runtime would and held at its ends; every such warp moves alike, so the face turns as one.
	 */
	internal fun withFaceLean(deformers: List<Deformer>, tuning: RigTuning = RigTuning()): List<Deformer> = deformers.map { deformer ->
		if (deformer !is Deformer.Warp) return@map deformer
		val grid = deformer.geometryGrid ?: return@map deformer
		val yi = grid.axes.indexOfFirst { it.parameterId == StandardParameters.ANGLE_Y }
		if (yi < 0 || grid.axes[yi].keys.size < 2 || grid.axes.any { it.parameterId == StandardParameters.BODY_LEAN }) return@map deformer
		val keys = grid.axes[yi].keys
		val byCoordinate = grid.cells.associateBy { it.coordinate.toList() }
		val leans = floatArrayOf(-10f, 0f, 10f)
		val shifts = floatArrayOf(tuning.faceLeanUp, 0f, -tuning.faceLeanDown)
		fun at(cell: KeyformCell<WarpLatticeForm>, index: Int) =
			byCoordinate[cell.coordinate.copyOf().also { it[yi] = index }.toList()]?.form ?: cell.form
		val cells = grid.cells.flatMap { cell ->
			leans.indices.map { l ->
				val form = if (shifts[l] == 0f) cell.form else {
					val target = (keys[cell.coordinate[yi]] + shifts[l]).coerceIn(keys.first(), keys.last())
					var j = 0
					while (j < keys.size - 2 && target > keys[j + 1]) j++
					val t = ((target - keys[j]) / (keys[j + 1] - keys[j])).coerceIn(0f, 1f)
					val a = at(cell, j).controlPoints
					val b = at(cell, j + 1).controlPoints
					WarpLatticeForm(FloatArray(a.size) { a[it] + (b[it] - a[it]) * t })
				}
				KeyformCell(cell.coordinate + l, form)
			}
		}
		deformer.copy(geometryGrid = KeyformGrid(grid.axes + KeyformAxis(StandardParameters.BODY_LEAN, leans), cells))
	}

	/** The lean warp's axes: the lean and the proportions, three keys each. */
	internal fun leanAxes(): List<KeyformAxis> =
		listOf(axis(StandardParameters.BODY_LEAN, -10f, 0f, 10f), axis(StandardParameters.PROPORTION, -10f, 0f, 10f))

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

	/** A lattice of [columns] by [rows] cells with each point placed by [point] from its (u, v). */
	private fun lattice(columns: Int, rows: Int, point: (u: Float, v: Float) -> DoubleArray): WarpLatticeForm {
		val controlPoints = FloatArray((columns + 1) * (rows + 1) * 2)
		var index = 0
		for (row in 0..rows) for (column in 0..columns) {
			val p = point(column.toFloat() / columns, row.toFloat() / rows)
			controlPoints[index++] = p[0].toFloat()
			controlPoints[index++] = p[1].toFloat()
		}
		return WarpLatticeForm(controlPoints)
	}

	/** Lattice sizes of the body warps: the body turns across its width, the lean only pitches about the waist. */
	private const val BODY_COLUMNS = 12
	private const val BODY_ROWS = 14
	private const val LEAN_COLUMNS = 8
	private const val LEAN_ROWS = 12

	/** Columns of the legs warp, a few per leg. */
	private const val LEG_COLUMNS = 8

	private fun Deformer.withParent(newParent: DeformerId?): Deformer = when (this) {
		is Deformer.Warp -> copy(parent = newParent)
		is Deformer.Rotation -> copy(parent = newParent)
	}

	internal fun wouldCreateCycle(
		sourceId: String,
		targetId: String,
		deformerById: Map<String, Deformer>,
		parentOverrides: Map<String, String?>,
	): Boolean {
		if (sourceId == targetId) return true
		var current: String? = targetId
		val visited = mutableSetOf(sourceId)
		while (current != null) {
			if (!visited.add(current)) return true
			val override = if (parentOverrides.containsKey(current)) {
				parentOverrides[current]?.takeIf { it.isNotBlank() && !it.equals("root", true) }
			} else {
				deformerById[current]?.parent?.raw
			}
			current = override
		}
		return false
	}
}
