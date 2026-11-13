package io.github.psd2live.algorithms.binding

import io.github.psd2live.core.modifier.ModifierAlgorithmContext
import io.github.psd2live.format.model.Deformer
import io.github.psd2live.targets.cubism.PuppetIr
import kotlinx.serialization.json.*
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.PuppetModel

/** Frozen opt-in numerical ancestry kernel. Never depends on a modifier-version envelope. */
object BrowSourceAncestryV1 {
    /** Reconstruct the native, pre-lowering ancestor chain from original source observations. */
    fun branch(
        data: JsonObject, brow: BrowShapeInputs, shell: List<Float>, nativeIds: JsonObject,
        names: JsonObject, checkpoint: () -> Unit = {},
    ): List<Deformer> {
        require(brow.targets.size == 1 && brow.targets.values.single().fit.side == Side.LEFT)
        require(data.keys == setOf("face", "body", "fallback", "layers"))
        val encodedFace = data.getValue("face").jsonObject
        require(encodedFace.keys == faceFields && encodedFace.getValue("tuning").jsonObject.keys == faceTuningFields) {
            "Source brow ancestry v1 rejects unknown face observations"
        }
        val faceContext = ModifierAlgorithmContext(encodedFace)
        val face = LegacyFaceInputs.decode(faceContext, checkpoint)
        val structure = LegacyFaceInputs.scaffold(faceContext).values
        require(structure.keys == setOf("schema", "frames", "branches"))
        val frameData = structure.getValue("frames").jsonObject
        val frames = ScaffoldFramesContext.decode(frameData, checkpoint)
        require(frames.head.initialAngle == 0f) { "Source brow ancestry v1 requires upright source art" }
        val base = ScaffoldFramesContext.build(frameData, checkpoint)
        val branchData = structure.getValue("branches").jsonObject
        val branches = ScaffoldBranchesContext.build(branchData, base, checkpoint)
        val layers = data.getValue("layers").jsonArray.map { it.jsonObject }
        val sourceIds = layers.map { it.text("source_id") }
        val ids = layers.map { it.text("id") }
        require(sourceIds.distinct().size == sourceIds.size && ids.distinct().size == ids.size &&
            sourceIds.toSet() == branches.parents.keys && layers.all { layer ->
                layer.keys == setOf("id", "source_id", "name", "tag", "side", "role") &&
                    layer.text("tag") in allowedTags &&
                    (layer.text("tag") !in leftTags || layer.text("side") == Side.LEFT.name) &&
                    (layer.text("tag") !in unroledTags || layer.getValue("role") == JsonNull)
            }) { "Source brow ancestry v1 requires ordinary unsplit source materials" }
        val encodedLayers = branchData.getValue("layers").jsonArray.map { it.jsonObject }.associateBy { it.text("id") }
        require(layers.all { layer -> encodedLayers.getValue(layer.text("source_id")).let {
            it.getValue("tag") == layer.getValue("tag") && it.getValue("side") == layer.getValue("side") &&
                it.getValue("name") == layer.getValue("name")
        } }) { "Source brow ancestry v1 source classifications must match the neutral branch inventory" }
        for (tag in listOf("FACE", "EYEBROW", "EYEWHITE", "EYELASH")) require(layers.count { it.text("tag") == tag } == 1)
        val mouths = layers.filter { it.text("tag") in setOf("MOUTH", "MOUTH_OPEN") }
        require(mouths.size == 1 && mouths.single().text("side") == Side.NONE.name && mouths.single().getValue("role") == JsonNull)
        val material = layers.single { it.text("tag") == "EYEBROW" }
        require(brow.targets.keys == setOf(material.text("id")))
        require(nativeIds.keys == brow.targets.keys && names.keys == brow.targets.keys &&
            names.getValue(material.text("id")) == material.getValue("name"))
        val target = brow.targets.values.single()
        val parent = branches.parents.getValue(material.text("source_id"))
        require(target.parent == parent.id.raw && target.frame == parent.frame) {
            "Source brow ancestry v1 source brow parent and frame must match its neutral scaffold"
        }
        val fallbackData = data.getValue("fallback").jsonObject
        require(fallbackData.keys == setOf("schema", "shell", "landmarks", "contour", "measures", "style") &&
            fallbackData.getValue("landmarks").jsonArray.all { it.jsonObject.keys == setOf("id", "part", "xy", "later") } &&
            fallbackData.getValue("style").jsonObject.keys.let { it == setOf("turn", "look", "sliders", "hair") ||
                it == setOf("turn", "look", "sliders", "hair", "preset") }) {
            "Source brow ancestry v1 rejects unknown fallback observations"
        }
        val fallback = FittedHeadTurnSource.decode(fallbackData)
        require(fallback.styledTurn() == null) { "Source brow ancestry v1 does not support styled head turn" }
        require(shell == listOf(fallback.centerX, fallback.centerY, fallback.radiusX, fallback.radiusY,
            fallback.turn, fallback.depth, fallback.chinY) && fallback.centerY == face.measurements.centerY &&
            fallback.radiusX == face.measurements.radiusX && fallback.radiusY == face.measurements.radiusY) {
            "Source brow ancestry v1 requires consistent face and fallback measurements"
        }
        val body = data.getValue("body").jsonObject
        require(body.keys == setOf("torso", "tuning", "strength") &&
            body.getValue("tuning").jsonObject.keys == bodyTuningFields)
        val strength = number(body.getValue("strength")).also { require(it in 0f..4f) }
        val tuning = LegacyBodyInputs.tuning(body.getValue("tuning").jsonObject)
        val standing = frames.standing
        val legs = standing?.legs.orEmpty().map { leg ->
            BodyStance.Leg(Side.NONE, leg.hip.x, leg.hip.y, leg.knee.x, leg.knee.y,
                leg.ankle.x, leg.ankle.y, leg.floorY)
        }
        val stance = BodyStance(frames.body, LegacyBodyInputs.torso(body.getValue("torso")), legs,
            standing?.frame, strength, tuning)
        val bodyFrames = LegacyBodyMotion.deformers(base + branches.deformers, frames.body, stance,
            strength, frames.head.canvasPivot.y, branches.armFrames, checkpoint)
        // Empty parameters prevent LegacyFaceBinding from leaning twice. Native face lean follows the turn field.
        val neutral = PuppetModel(emptyList(), emptyList(), bodyFrames, emptyList(), emptyList(), null)
        val angles = listOf(LegacyFaceBinding.angleX, LegacyFaceBinding.angleY, LegacyFaceBinding.angleZ)
            .mapIndexed { i, id -> Parameter(id, face.parameterLabels[i], if (i == 0) -45f else -30f,
                if (i == 0) 45f else 30f, 0f) }
        val legacy = LegacyFaceBinding.bind(neutral, face.measurements, face.tuning, face.strength,
            angles, face.folderLabel, checkpoint)
        // A single-eye fallback has no fitted FaceTurn field. Its brow ancestors retain legacy forms.
        val turned = DepthTurnMotion.withFaceLean(legacy.deformers, face.tuning.faceLeanDown, face.tuning.faceLeanUp)
        val byId = turned.associateBy { it.id }
        val ancestors = generateSequence(byId[parent.id]) { node -> node.parent?.let(byId::get) }.toList()
        require(ancestors.isNotEmpty() && ancestors.last().parent == null) {
            "Source brow ancestry v1 requires a complete reconstructed source branch"
        }
        checkpoint()
        return ancestors.map(PuppetIr::deformerToIr)
    }

    private val faceFields = setOf("schema", "scaffold", "center_y", "radius_x", "radius_y", "eye_line_y",
        "strength", "parameter_labels", "folder_label", "tuning")
    private val faceTuningFields = setOf(
        "faceTurn", "faceTurnBase", "faceTilt", "faceLift", "faceArch",
        "faceCorner", "faceOutsideFollow", "yawEase", "pitchEase", "featurePlane",
        "headShellTurn", "headShellCrown", "headShellTilt", "faceContour", "faceLeanDown",
        "faceLeanUp", "displacementShift", "displacementNarrow", "displacementTilt", "displacementTwist",
        "eyeFarShrink", "eyeNearGrow", "eyeShift", "eyeTilt", "eyeLidCurve",
        "eyeTiltStretch", "irisShift", "irisKeepWidth", "irisTilt", "browFarShrink",
        "browNearGrow", "browShift", "browTilt", "mouthShift", "mouthCornerLag",
        "mouthNearLag", "mouthTurnArch", "mouthTilt", "noseShift", "noseTilt",
        "earFade", "earFarShrink", "earShift", "frontHairTurn", "frontHairTilt",
        "frontHairPerspective", "backHairTurn", "backHairTilt", "hairRollHold")
    private val bodyTuningFields = setOf(
        "hipShift", "sink", "rise", "kneesIn", "kneesInRest",
        "turnDegrees", "armSwingDegrees", "turnFade", "torsoDepth", "turnCameraDistance",
        "leanCameraDistance", "openShorten", "openWiden", "standStretch", "standNarrow",
        "leanDegrees", "leanBackDegrees", "pelvisShare", "headGrow", "chestGrow",
        "bodyShrink", "legShrink", "bodyZDegrees", "breathLift", "breathWiden",
        "faceLeanDown", "faceLeanUp")
    private val leftTags = setOf("IRIDES", "EYEWHITE", "EYELASH", "EYE_CLOSE", "EYEBROW")
    private val unroledTags = setOf("FRONT_HAIR", "BACK_HAIR", "TOPWEAR", "FACE", "MOUTH", "MOUTH_OPEN", "EYEBROW", "NOSE")
    private val allowedTags = leftTags + unroledTags
    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content
    private fun number(value: JsonElement) = value.jsonPrimitive.let { p ->
        require(!p.isString)
        p.float.also { require(it.isFinite()) }
    }
}
