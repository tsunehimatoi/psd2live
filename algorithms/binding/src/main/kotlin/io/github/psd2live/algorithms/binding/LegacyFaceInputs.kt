package io.github.psd2live.algorithms.binding

import io.github.psd2live.core.modifier.ModifierAlgorithmContext
import kotlinx.serialization.json.*

/** face-legacy/1 extends the captured scaffold/5 input with source measurements in aligned pixels. */
object LegacyFaceInputs {
    const val SCHEMA = "face-legacy/1"

    data class Input(
        val measurements: LegacyFaceBinding.Measurements,
        val tuning: LegacyFaceTuning,
        val strength: Float,
        val parameterLabels: List<String>,
        val folderLabel: String,
    )

    fun capture(scaffold: ModifierAlgorithmContext, centerY: Float, radiusX: Float, radiusY: Float,
                eyeLineY: Float, tuning: LegacyFaceTuning, strength: Float, labels: List<String>, folder: String): ModifierAlgorithmContext {
        require(scaffold.values["schema"] == JsonPrimitive("scaffold/5"))
        return ModifierAlgorithmContext(buildJsonObject {
            put("schema", SCHEMA)
            put("scaffold", scaffold.values)
            put("center_y", centerY)
            put("radius_x", radiusX)
            put("radius_y", radiusY)
            put("eye_line_y", eyeLineY)
            put("strength", strength)
            put("parameter_labels", JsonArray(labels.map(::JsonPrimitive)))
            put("folder_label", folder)
            putJsonObject("tuning") {
                put("faceTurn", tuning.faceTurn)
                put("faceTurnBase", tuning.faceTurnBase)
                put("faceTilt", tuning.faceTilt)
                put("faceLift", tuning.faceLift)
                put("faceArch", tuning.faceArch)
                put("faceCorner", tuning.faceCorner)
                put("faceOutsideFollow", tuning.faceOutsideFollow)
                put("yawEase", tuning.yawEase)
                put("pitchEase", tuning.pitchEase)
                put("featurePlane", tuning.featurePlane)
                put("headShellTurn", tuning.headShellTurn)
                put("headShellCrown", tuning.headShellCrown)
                put("headShellTilt", tuning.headShellTilt)
                put("faceContour", tuning.faceContour)
                put("faceLeanDown", tuning.faceLeanDown)
                put("faceLeanUp", tuning.faceLeanUp)
                put("displacementShift", tuning.displacementShift)
                put("displacementNarrow", tuning.displacementNarrow)
                put("displacementTilt", tuning.displacementTilt)
                put("displacementTwist", tuning.displacementTwist)
                put("eyeFarShrink", tuning.eyeFarShrink)
                put("eyeNearGrow", tuning.eyeNearGrow)
                put("eyeShift", tuning.eyeShift)
                put("eyeTilt", tuning.eyeTilt)
                put("eyeLidCurve", tuning.eyeLidCurve)
                put("eyeTiltStretch", tuning.eyeTiltStretch)
                put("irisShift", tuning.irisShift)
                put("irisKeepWidth", tuning.irisKeepWidth)
                put("irisTilt", tuning.irisTilt)
                put("browFarShrink", tuning.browFarShrink)
                put("browNearGrow", tuning.browNearGrow)
                put("browShift", tuning.browShift)
                put("browTilt", tuning.browTilt)
                put("mouthShift", tuning.mouthShift)
                put("mouthCornerLag", tuning.mouthCornerLag)
                put("mouthNearLag", tuning.mouthNearLag)
                put("mouthTurnArch", tuning.mouthTurnArch)
                put("mouthTilt", tuning.mouthTilt)
                put("noseShift", tuning.noseShift)
                put("noseTilt", tuning.noseTilt)
                put("earFade", tuning.earFade)
                put("earFarShrink", tuning.earFarShrink)
                put("earShift", tuning.earShift)
                put("frontHairTurn", tuning.frontHairTurn)
                put("frontHairTilt", tuning.frontHairTilt)
                put("frontHairPerspective", tuning.frontHairPerspective)
                put("backHairTurn", tuning.backHairTurn)
                put("backHairTilt", tuning.backHairTilt)
                put("hairRollHold", tuning.hairRollHold)
            }
        }, scaffold.resources)
    }

    fun scaffold(context: ModifierAlgorithmContext): ModifierAlgorithmContext {
        require(context.values["schema"] == JsonPrimitive(SCHEMA))
        val values = context.values.getValue("scaffold").jsonObject
        require(values["schema"] == JsonPrimitive("scaffold/5") && "template" !in context.resources)
        return ModifierAlgorithmContext(values, context.resources)
    }

    fun decode(context: ModifierAlgorithmContext, checkpoint: () -> Unit = {}): Input {
        val scaffold = scaffold(context)
        val data = context.values
        val frame = ScaffoldFramesContext.decode(scaffold.values.getValue("frames").jsonObject, checkpoint).head
        fun JsonObject.number(key: String) = getValue(key).jsonPrimitive.float.also { require(it.isFinite()) }
        val measurements = LegacyFaceBinding.Measurements(frame, data.number("center_y"),
            data.number("radius_x").also { require(it >= 1f) }, data.number("radius_y").also { require(it >= 1f) },
            data.number("eye_line_y"))
        val tuning = data.getValue("tuning").jsonObject
        val labels = data.getValue("parameter_labels").jsonArray.map { it.jsonPrimitive.content }
        require(labels.size == 3)
        return Input(measurements, LegacyFaceTuning(
            faceTurn = tuning.number("faceTurn"),
            faceTurnBase = tuning.number("faceTurnBase"),
            faceTilt = tuning.number("faceTilt"),
            faceLift = tuning.number("faceLift"),
            faceArch = tuning.number("faceArch"),
            faceCorner = tuning.number("faceCorner"),
            faceOutsideFollow = tuning.number("faceOutsideFollow"),
            yawEase = tuning.number("yawEase"),
            pitchEase = tuning.number("pitchEase"),
            featurePlane = tuning.number("featurePlane"),
            headShellTurn = tuning.number("headShellTurn"),
            headShellCrown = tuning.number("headShellCrown"),
            headShellTilt = tuning.number("headShellTilt"),
            faceContour = tuning.number("faceContour"),
            faceLeanDown = tuning.number("faceLeanDown"),
            faceLeanUp = tuning.number("faceLeanUp"),
            displacementShift = tuning.number("displacementShift"),
            displacementNarrow = tuning.number("displacementNarrow"),
            displacementTilt = tuning.number("displacementTilt"),
            displacementTwist = tuning.number("displacementTwist"),
            eyeFarShrink = tuning.number("eyeFarShrink"),
            eyeNearGrow = tuning.number("eyeNearGrow"),
            eyeShift = tuning.number("eyeShift"),
            eyeTilt = tuning.number("eyeTilt"),
            eyeLidCurve = tuning.number("eyeLidCurve"),
            eyeTiltStretch = tuning.number("eyeTiltStretch"),
            irisShift = tuning.number("irisShift"),
            irisKeepWidth = tuning.number("irisKeepWidth"),
            irisTilt = tuning.number("irisTilt"),
            browFarShrink = tuning.number("browFarShrink"),
            browNearGrow = tuning.number("browNearGrow"),
            browShift = tuning.number("browShift"),
            browTilt = tuning.number("browTilt"),
            mouthShift = tuning.number("mouthShift"),
            mouthCornerLag = tuning.number("mouthCornerLag"),
            mouthNearLag = tuning.number("mouthNearLag"),
            mouthTurnArch = tuning.number("mouthTurnArch"),
            mouthTilt = tuning.number("mouthTilt"),
            noseShift = tuning.number("noseShift"),
            noseTilt = tuning.number("noseTilt"),
            earFade = tuning.number("earFade"),
            earFarShrink = tuning.number("earFarShrink"),
            earShift = tuning.number("earShift"),
            frontHairTurn = tuning.number("frontHairTurn"),
            frontHairTilt = tuning.number("frontHairTilt"),
            frontHairPerspective = tuning.number("frontHairPerspective"),
            backHairTurn = tuning.number("backHairTurn"),
            backHairTilt = tuning.number("backHairTilt"),
            hairRollHold = tuning.number("hairRollHold"),
        ).also { require(it.yawEase > 0f && it.pitchEase > 0f) },
            data.number("strength").also { require(it in 0f..4f) }, labels, data.getValue("folder_label").jsonPrimitive.content)
    }
}
