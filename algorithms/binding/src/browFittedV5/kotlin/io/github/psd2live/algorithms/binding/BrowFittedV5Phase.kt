package io.github.psd2live.algorithms.binding

import io.github.psd2live.core.modifier.ModifierAlgorithmContext
import io.github.psd2live.core.modifier.ModifierAlgorithmResource
import io.github.psd2live.core.modifier.ModifierSourceInputs
import kotlinx.serialization.json.*
import org.umamo.runtime.model.*

/** Frozen source-only replay for the initial, single-eye brow/fitted v5 cohort. */
object BrowFittedV5Phase {
    data class Prepared(
        val ordinary: PuppetModel,
        val turn: DepthTurnInputs,
        /** Source parents keyed by target mesh ID, before shape allocation. */
        val parents: Map<String, ScaffoldArms.Parent>,
        /** Full canonical branch motion, before mesh shapes are allocated to carriers. */
        val turnedDeformers: List<Deformer>,
    )

    fun prepare(
        phase: JsonObject, raw: ModifierAlgorithmResource, source: ModifierSourceInputs,
        checkpoint: () -> Unit,
    ): Prepared {
        checkpoint()
        val faceContext = ModifierAlgorithmContext(phase.getValue("base").jsonObject)
        val face = LegacyFaceInputs.decode(faceContext, checkpoint)
        val structure = LegacyFaceInputs.scaffold(faceContext).values
        val frameData = structure.getValue("frames").jsonObject
        val frames = ScaffoldFramesContext.decode(frameData, checkpoint)
        require(frames.head.initialAngle == 0f) { "Fitted brow v5 requires upright source art" }
        val base = ScaffoldFramesContext.build(frameData, checkpoint)
        val branches = ScaffoldBranchesContext.build(structure.getValue("branches").jsonObject, base, checkpoint)
        val organization = ScaffoldPartsContext.decode(structure.getValue("organization").jsonObject, checkpoint)
        val topology = ScaffoldMeshContext.decodeRawMeshes(raw, checkpoint)
        val records = structure.getValue("mesh_inputs").jsonObject.getValue("materials").jsonArray
            .map { it.jsonObject }
        val geometry = records.associate { it.text("id") to it.getValue("geometry").jsonObject }
        require(geometry.size == records.size && geometry.keys == topology.keys) {
            "Fitted brow v5 source topology does not match its material measurements"
        }
        val layers = phase.getValue("layers").jsonArray.map { it.jsonObject }
        val layerIds = layers.map { it.text("id") }
        require(layerIds.size == layerIds.toSet().size && layerIds.toSet() == topology.keys) {
            "Fitted brow v5 requires the complete source mesh cohort"
        }
        require(organization.materials.mapTo(hashSetOf()) { it.id.raw } == layerIds.toSet()) {
            "Fitted brow v5 source organization does not match its material inventory"
        }
        require(layers.all { layer ->
            val tag = layer.text("tag")
            tag in allowedTags && (tag !in unroledTags || layer.optionalText("role") == null) &&
                (tag !in leftTags || layer.text("side") == Side.LEFT.name)
        } && layers.count { it.text("tag") == "FACE" } == 1) {
            "Fitted brow v5 requires one left-sided eye cohort and ordinary face, hair and body materials"
        }
        val mouths = layers.filter { it.text("tag") == "MOUTH" && it.optionalText("role") == null }
        require(mouths.size == 1 && mouths.single().text("side") == Side.NONE.name &&
            layers.none { it.text("tag") == "MOUTH_OPEN" || it.text("tag") == "MOUTH_CLOSE" }) {
            "Fitted brow v5 requires one ordinary open mouth drawing"
        }
        val primaryBrows = layers.filter { it.text("tag") == "EYEBROW" && it.optionalText("role") == null }
        require(primaryBrows.size == 1 && primaryBrows.single().text("side") == Side.LEFT.name) {
            "Fitted brow v5 requires one matching left brow"
        }
        val sourceIds = records.associate { it.text("id") to it.text("source_id") }
        val parents = layers.associate { layer ->
            val id = layer.text("id")
            val sourceId = layer.text("source_id")
            val observed = source.layerForTarget(id)
            require(sourceIds.getValue(id) == sourceId && observed.id == sourceId &&
                observed.tag == layer.text("tag") && observed.side == layer.text("side") &&
                observed.role == layer.optionalText("role")) { "Inconsistent fitted brow v5 source identity or classification" }
            id to branches.parents.getValue(sourceId)
        }

        val body = phase.getValue("body").jsonObject
        val strength = body.number("strength").also { require(it in 0f..4f) }
        val tuning = LegacyBodyInputs.tuning(body.getValue("tuning").jsonObject)
        val standing = frames.standing
        val legs = standing?.legs.orEmpty().map { leg ->
            BodyStance.Leg(Side.NONE, leg.hip.x, leg.hip.y, leg.knee.x, leg.knee.y,
                leg.ankle.x, leg.ankle.y, leg.floorY)
        }
        val stance = BodyStance(frames.body, LegacyBodyInputs.torso(body.getValue("torso")),
            legs, standing?.frame, strength, tuning)
        val bodyFrames = LegacyBodyMotion.deformers(base + branches.deformers, frames.body, stance,
            strength, frames.head.canvasPivot.y, branches.armFrames, checkpoint)

        val eyeInput = Json.decodeFromJsonElement<EyeFittedInputs>(phase.getValue("eye_input"))
        require(eyeInput.materials.isEmpty()) { "Fitted brow v5 accepts eye measurements, not bound materials" }
        val align: (Float, Float) -> Pair<Float, Float> = { x, y ->
            eyeInput.alignment?.point(x, y) ?: (x to y)
        }
        val fits = EyeFitting.fit(source, eyeInput.faceCenterX, align).fits.mapValues { (side, fit) ->
            checkpoint()
            fit.moved(eyeInput.moves[side.name].orEmpty())
        }
        require(fits.keys == setOf(Side.LEFT)) { "Fitted brow v5 requires one fitted left eye" }
        val artwork = EyeArtworkFitting.fit(source, eyeInput.artworkStamps, fits, eyeInput.corrections, align)
        val eyeValues = phase.getValue("eye_values").jsonObject
        require(eyeValues.keys.all { it in setOf("lowerShare", "roundness", "lashSquash") })
        fun knob(name: String, default: Float, range: ClosedFloatingPointRange<Float>): Float =
            (eyeValues[name]?.let(::number) ?: default).also { require(it in range) } / 100f
        val lower = knob("lowerShare", 5f, 0f..50f)
        val roundness = knob("roundness", 100f, 40f..160f)
        val squash = knob("lashSquash", 60f, 20f..100f)
        val eyes = FittedEyeMotion(fits.mapValues { (side, fit) ->
            EyeRig(fit.copy(artwork = artwork[side]), lower, roundness, squash)
        })
        val browData = phase.getValue("brow_fits").jsonObject
        require(browData.keys.isEmpty() || browData.keys == setOf(Side.LEFT.name)) {
            "Fitted brow v5 only supports its matching left brow fit"
        }
        val lift = phase.number("brow_lift").also {
            require(browData.isEmpty() || it in 20f..100f)
        } / 100f
        val brows = browData.map { (name, value) ->
            val side = Side.valueOf(name)
            val coordinates = tuple(value, 5 + BrowFit.SAMPLES)
            require(coordinates[4] > 0f)
            val fit = BrowFit(side, coordinates[0], coordinates[1], coordinates[2], coordinates[3],
                coordinates[4], coordinates.drop(5))
            val eye = eyes.rigs.getValue(side)
            side to BrowRig(fit, eye.opening * eye.fit.width * lift, BrowOptions(), checkpoint)
        }.toMap()

        val drawables = layers.map { layer ->
            checkpoint()
            val id = layer.text("id")
            val material = BrowFittedV5Meshes.Material(id, layer.text("source_id"), layer.text("name"),
                layer.text("tag"), layer.optionalText("role"), Side.valueOf(layer.text("side")),
                layer.number("draw_order"), layer.number("opacity"), geometry.getValue(id))
            BrowFittedV5Meshes.build(topology.getValue(id), parents.getValue(id), material, eyes, brows,
                eyeInput.faceCenterX, phase.number("teeth_fade"), checkpoint).copy(
                texturePage = layer.getValue("texture_page").jsonPrimitive.int,
                atlasTileId = layer.optionalText("atlas_tile")?.let(::AtlasTileId),
                isVisible = layer.getValue("visible").jsonPrimitive.boolean,
                maskedBy = layer.getValue("masked_by").jsonArray.map { DrawableId(it.jsonPrimitive.content) },
            )
        }
        val parameters = phase.getValue("parameters").jsonArray.map { value ->
            val p = value.jsonObject
            Parameter(ParameterId(p.text("id")), p.text("name"), p.number("min"), p.number("max"), p.number("default"))
        }
        require(parameters.map { it.id.raw } == nativeParameters) {
            "Fitted brow v5 requires native parameter order before shape allocation"
        }
        val byParameter = parameters.associateBy { it.id }
        val groups = phase.getValue("groups").jsonArray.map { value ->
            val group = value.jsonObject
            ParameterNode.Group(ParameterGroupId(group.text("id")), group.text("name"), true,
                group.getValue("parameters").jsonArray.map { ParameterNode.Param(ParameterId(it.jsonPrimitive.content)) })
        }
        // Keep parameters absent here: native face lean is added only after the depth field.
        // The admitted source art has no folders. Native shell projection keeps ordinary source
        // organization, not semantic scaffold parts: that distinction also affects unused-carrier
        // alias comparison in BindingMerge. Keep the raw source ordering, including stable ties.
        val rootChildren = organization.materials.sortedBy { it.sourceOrder }.map { OrgChild.Drawable(it.id) }
        val neutral = PuppetModel(emptyList(), emptyList(), bodyFrames, drawables, rootChildren, null)
            .withDerivedRenderRoot()
        val legacy = LegacyFaceBinding.bind(neutral, face.measurements, face.tuning, face.strength,
            listOf(LegacyFaceBinding.angleX, LegacyFaceBinding.angleY, LegacyFaceBinding.angleZ).map(byParameter::getValue),
            face.folderLabel, checkpoint)
        val eyeTuning = tuple(phase.getValue("eye_tuning"), 7)
        val gazeTuning = EyeLegacyMotion.Tuning(eyeTuning[0], eyeTuning[1], eyeTuning[2], eyeTuning[3],
            eyeTuning[4], eyeTuning[5], eyeTuning[6])
        val hair = tuple(phase.getValue("hair_tuning"), 4)
        val ordinaryFrames = legacy.deformers.map { node ->
            checkpoint()
            if (node !is Deformer.Warp) node else when (node.id) {
                ScaffoldHead.gazeId(Side.LEFT), ScaffoldHead.gazeId(Side.RIGHT), ScaffoldHead.gazeId(Side.NONE) ->
                    node.copy(geometryGrid = EyeLegacyMotion.gaze(gazeTuning))
                HairLegacyMotion.Plane.FRONT.sway -> node.copy(geometryGrid = HairLegacyMotion.sway(
                    HairLegacyMotion.Plane.FRONT, requireNotNull(frames.head.frontHair).bounds,
                    HairLegacyMotion.Tuning(0f, 0f, 0f, hair[0], hair[1], 0f), true, checkpoint))
                HairLegacyMotion.Plane.BACK.sway -> node.copy(geometryGrid = HairLegacyMotion.sway(
                    HairLegacyMotion.Plane.BACK, requireNotNull(frames.head.backHair).bounds,
                    HairLegacyMotion.Tuning(0f, 0f, 0f, hair[2], hair[3], 0f), true, checkpoint))
                else -> node
            }
        }
        val ordinary = legacy.copy(parameters = parameters, parameterTree = groups, parameterLinks = emptyList(),
            deformers = ordinaryFrames)
        val headTurn = FittedHeadTurnSource.decode(phase.getValue("head_turn").jsonObject)
        require(headTurn.styledTurn() == null) { "Fitted brow v5 does not yet support a styled face field" }
        val containerValues = tuple(phase.getValue("container"), 6)
        val container = DepthTurnMotion.Container(containerValues[0], containerValues[1], containerValues[2],
            containerValues[3], containerValues[4], containerValues[5])
        require(container.pivotX == headTurn.centerX) { "Inconsistent fitted brow v5 head center" }
        val parentFrames = ScaffoldHead.build(frames.head, { it }, checkpoint).frames +
            branches.parents.values.associate { it.id to it.frame }
        val mouthParent = parents.values.firstOrNull { it.id == mouthWarpId }
        val mouthRegion = frames.head.regions.firstOrNull {
            it.feature == ScaffoldHead.Feature.MOUTH && it.bounds == mouthParent?.frame
        }
        val mouthWarp = requireNotNull(mouthRegion?.let { region ->
            parentFrames[ScaffoldHead.faceId]?.let { parent ->
                val t = face.tuning
                DepthTurnMouthWarp(region.bounds, parent, t.yawEase, t.pitchEase, t.featurePlane,
                    t.mouthNearLag, t.mouthCornerLag, t.mouthShift, t.mouthTurnArch, t.mouthTilt)
            }
        }) { "Fitted brow v5 requires the ordinary shared mouth frame" }
        val turn = DepthTurnInputs(frames.head.headFrame, frames.head.faceFrame, container,
            headTurn.centerY, headTurn.radiusX, headTurn.radiusY, headTurn.chinY, headTurn.turn, headTurn.depth,
            headTurn.style, headTurn.landmarks, setOf("L"), if (brows.isEmpty()) emptySet() else setOf("L"),
            parentFrames, layers.associate { layer ->
                val id = layer.text("id")
                id to DepthTurnInputs.Layer(layer.text("tag"), layer.optionalText("role"), parents.getValue(id).frame,
                    sourceMesh = id)
            }, face.tuning.faceLeanDown, face.tuning.faceLeanUp, mouthWarp = mouthWarp, sourceMeshes = raw,
            shapeName = phase.text("shape_name"), turnName = phase.text("turn_name"))
        // This is the same fallback field as project(), without invoking mesh lowering while finding scope.
        val shell = HeadTurn(container.pivotX, turn.centerY, turn.radiusX, turn.radiusY,
            turn.shellTurn, turn.shellDepth, turn.chinY, checkpoint)
        val turned = ordinaryFrames.map { node ->
            if (node.id == mouthWarpId && node is Deformer.Warp)
                mouthWarp.place(node, shell, container.pivotX, turn.radiusX, container.strength, checkpoint)
            else node
        }
        return Prepared(ordinary, turn, parents,
            DepthTurnMotion.withFaceLean(turned, turn.leanDown, turn.leanUp))
    }

    /** Witnesses remain present until after native lowering and ordered parameter deletion. */
    fun project(prepared: Prepared, selected: Set<String>, kept: Set<ParameterId>, checkpoint: () -> Unit): PuppetModel {
        require(selected.isNotEmpty() && prepared.parents.keys.containsAll(selected))
        // The shell projection retains every canonical branch, including those with no selected mesh.
        // project() refreshes only selected branches; start with the complete native turn/lean forms so
        // its retention of unselected branches cannot revert them to the unleaned nine-pose grids.
        val ordinary = prepared.ordinary.copy(deformers = prepared.turnedDeformers,
            drawables = prepared.ordinary.drawables.filter { it.id.raw in selected })
        val projected = DepthTurnAlgorithm().project(ordinary, selected, prepared.turn, checkpoint)
        return BindingProjection.retain(projected, kept, mapOf(ParameterId("ParamMouthOpenY") to 1f), checkpoint)
    }

    private val mouthWarpId = DeformerId("DeformMouthShapeBoth")
    private val leftTags = setOf("IRIDES", "EYEWHITE", "EYELASH", "EYE_CLOSE", "EYEBROW")
    private val unroledTags = setOf("FRONT_HAIR", "BACK_HAIR", "TOPWEAR", "FACE", "MOUTH", "EYEBROW")
    private val allowedTags = leftTags + unroledTags + "NOSE"
    private val nativeParameters = listOf("ParamAngleX", "ParamAngleY", "ParamAngleZ", "ParamBodyAngleX",
        "ParamBodyAngleY", "ParamBodyAngleZ", "ParamBodyLean", "ParamProportion", "ParamEyeLOpen", "ParamEyeROpen",
        "ParamEyeBallX", "ParamEyeBallY", "ParamEyeBallForm", "ParamBrowLY", "ParamBrowRY", "ParamMouthForm",
        "ParamMouthOpenY", "ParamBreath", "ParamHairFront", "ParamHairBack")

    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content
    private fun JsonObject.optionalText(key: String) = getValue(key).jsonPrimitive.contentOrNull
    private fun JsonObject.number(key: String) = number(getValue(key))
    private fun number(value: JsonElement): Float = value.jsonPrimitive.let {
        require(!it.isString)
        it.float.also { number -> require(number.isFinite()) }
    }
    private fun tuple(value: JsonElement, size: Int): List<Float> = value.jsonArray.also {
        require(it.size == size)
    }.map(::number)
}
