package io.github.psd2live.algorithms.binding

import io.github.psd2live.core.modifier.ModifierAlgorithmContext
import io.github.psd2live.core.modifier.ModifierAlgorithmResource
import kotlinx.serialization.json.*

/**
 * Captured body measurements in canvas pixels. The nested scaffold uses scaffold/5 and supplies the
 * leg joints, neutral source topology, atlas UVs and parent-local frames. The selected scaffold identity
 * and any materialized aliases arrive separately in the upstream capability snapshot.
 *
 * For a target below the head rotation, head_pitch_grids is a deformers-only RigIR resource containing
 * the upstream canonical head lattices on their native head axes before body lean is applied.
 */
object LegacyBodyInputs {
    const val SCHEMA = "body-legacy/1"
    const val HEAD_POSES = "head_pitch_grids"

    fun capture(
        scaffold: ModifierAlgorithmContext, torso: TorsoFrame, tuning: BodyMotionTuning,
        parameterNames: Map<String, String>, groupNames: Map<String, String>,
        headPitchGrids: ModifierAlgorithmResource? = null,
    ): ModifierAlgorithmContext {
        require(scaffold.values["schema"]?.jsonPrimitive?.content == "scaffold/5")
        return ModifierAlgorithmContext(buildJsonObject {
            put("schema", SCHEMA)
            put("scaffold", scaffold.values)
            put("torso", JsonArray(listOf(torso.centerX, torso.shoulderY, torso.waistY, torso.halfWidth).map(::JsonPrimitive)))
            putJsonObject("tuning") {
                put("hipShift", tuning.hipShift)
                put("sink", tuning.sink)
                put("rise", tuning.rise)
                put("kneesIn", tuning.kneesIn)
                put("kneesInRest", tuning.kneesInRest)
                put("turnDegrees", tuning.turnDegrees)
                put("armSwingDegrees", tuning.armSwingDegrees)
                put("turnFade", tuning.turnFade)
                put("torsoDepth", tuning.torsoDepth)
                put("turnCameraDistance", tuning.turnCameraDistance)
                put("leanCameraDistance", tuning.leanCameraDistance)
                put("openShorten", tuning.openShorten)
                put("openWiden", tuning.openWiden)
                put("standStretch", tuning.standStretch)
                put("standNarrow", tuning.standNarrow)
                put("leanDegrees", tuning.leanDegrees)
                put("leanBackDegrees", tuning.leanBackDegrees)
                put("pelvisShare", tuning.pelvisShare)
                put("headGrow", tuning.headGrow)
                put("chestGrow", tuning.chestGrow)
                put("bodyShrink", tuning.bodyShrink)
                put("legShrink", tuning.legShrink)
                put("bodyZDegrees", tuning.bodyZDegrees)
                put("breathLift", tuning.breathLift)
                put("breathWiden", tuning.breathWiden)
                put("faceLeanDown", tuning.faceLeanDown)
                put("faceLeanUp", tuning.faceLeanUp)
            }
            put("parameter_names", JsonObject(parameterNames.mapValues { JsonPrimitive(it.value) }))
            put("group_names", JsonObject(groupNames.mapValues { JsonPrimitive(it.value) }))
        }, scaffold.resources + listOfNotNull(headPitchGrids?.let { HEAD_POSES to it }).toMap())
    }

    internal fun torso(value: JsonElement): TorsoFrame {
        val values = value.jsonArray.map(::finite)
        require(values.size == 4 && values[2] > values[1] && values[3] > 0f) { "Invalid body torso measurements" }
        return TorsoFrame(values[0], values[1], values[2], values[3])
    }

    internal fun tuning(value: JsonObject): BodyMotionTuning {
        fun read(id: String) = finite(value.getValue(id))
        return BodyMotionTuning(
            hipShift = read("hipShift"),
            sink = read("sink"),
            rise = read("rise"),
            kneesIn = read("kneesIn"),
            kneesInRest = read("kneesInRest"),
            turnDegrees = read("turnDegrees"),
            armSwingDegrees = read("armSwingDegrees"),
            turnFade = read("turnFade"),
            torsoDepth = read("torsoDepth"),
            turnCameraDistance = read("turnCameraDistance"),
            leanCameraDistance = read("leanCameraDistance"),
            openShorten = read("openShorten"),
            openWiden = read("openWiden"),
            standStretch = read("standStretch"),
            standNarrow = read("standNarrow"),
            leanDegrees = read("leanDegrees"),
            leanBackDegrees = read("leanBackDegrees"),
            pelvisShare = read("pelvisShare"),
            headGrow = read("headGrow"),
            chestGrow = read("chestGrow"),
            bodyShrink = read("bodyShrink"),
            legShrink = read("legShrink"),
            bodyZDegrees = read("bodyZDegrees"),
            breathLift = read("breathLift"),
            breathWiden = read("breathWiden"),
            faceLeanDown = read("faceLeanDown"),
            faceLeanUp = read("faceLeanUp"),
        ).also { require(it.turnCameraDistance > 0f && it.leanCameraDistance > 0f) { "Invalid body camera distance" } }
    }

    internal fun finite(value: JsonElement) = value.jsonPrimitive.float.also { require(it.isFinite()) }
}
