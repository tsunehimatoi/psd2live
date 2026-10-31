package io.github.psd2live.targets.cubism

import io.github.psd2live.format.compile.ExportOptions
import io.github.psd2live.format.compile.TargetSetting
import io.github.psd2live.format.model.*
import kotlinx.serialization.json.*
import kotlin.test.*

class VTubeStudioTargetTest {
    private fun rig(vararg ids: String) = RigIR(Canvas(100f, 100f), ids.map { Parameter(it, it, -1f, 1f, 0f) })
    private fun rows(ir: RigIR, vararg settings: Pair<String, String>): List<JsonObject> =
        Json.parseToJsonElement(VTubeStudioTarget.config(ir, "hero", emptyList(), options = ExportOptions("hero", settings = mapOf(*settings))))
            .jsonObject.getValue("ParameterSettings").jsonArray.map { it.jsonObject }
    private fun mapping(rows: List<JsonObject>) = rows.associate { it.getValue("OutputLive2D").jsonPrimitive.content to it.getValue("Input").jsonPrimitive.content }

    @Test fun independentGazeFallsBackForOlderRigs() {
        val old = rig("ParamEyeBallX", "ParamEyeBallY")
        assertEquals(mapOf("ParamEyeBallX" to "EyeRightX", "ParamEyeBallY" to "EyeRightY"), mapping(rows(old)))
        val modern = old.copy(parameters = old.parameters + rig("ParamEyeBallLX", "ParamEyeBallLY", "ParamEyeBallRX", "ParamEyeBallRY").parameters)
        val mapped = mapping(rows(modern))
        assertEquals("EyeLeftX", mapped["ParamEyeBallLX"])
        assertEquals("EyeRightY", mapped["ParamEyeBallRY"])
        assertFalse("ParamEyeBallX" in mapped)
        assertEquals("MousePositionX", mapping(rows(modern, "vts_gaze" to "mouse"))["ParamEyeBallX"])
    }

    @Test fun mouthModesAreExclusiveAndVbridgerUsesPluginInputNames() {
        val ir = rig("ParamMouthOpenY", "ParamMouthForm", "ParamMouthPuckerWiden", "ParamMouthPressLipOpen", "ParamJawOpen", "ParamA", "ParamI", "ParamTongueOut")
        val voice = mapping(rows(ir, "vts_mouth" to "voice"))
        assertEquals("VoiceA", voice["ParamA"])
        assertFalse("ParamMouthOpenY" in voice)
        val hybrid = mapping(rows(ir, "vts_mouth" to "voice_hybrid"))
        assertEquals("VoiceVolumePlusMouthOpen", hybrid["ParamMouthOpenY"])
        assertEquals("VoiceFrequencyPlusMouthSmile", hybrid["ParamMouthForm"])
        val plugin = rows(ir, "vts_mouth" to "vbridger")
        assertEquals("MouthPucker", mapping(plugin)["ParamMouthPuckerWiden"])
        assertEquals("MouthPressLipOpen", mapping(plugin)["ParamMouthPressLipOpen"])
        assertEquals(plugin.size, mapping(plugin).size)
        assertFailsWith<IllegalArgumentException> { rows(ir, "vts_mouth" to "typo") }
    }

    @Test fun allOfficialInputsCanMapToExplicitParametersWithoutDuplicateOutputs() {
        assertEquals(33, VTubeStudioTarget.officialInputs.size)
        val ir = rig(*VTubeStudioTarget.officialInputs.map { "Param$it" }.toTypedArray())
        val rows = rows(ir, "vts_position" to "true", "vts_angry" to "true")
        assertEquals(VTubeStudioTarget.officialInputs, mapping(rows).values.toSet())
        assertEquals(rows.size, mapping(rows).size)
        val first = VTubeStudioTarget.config(ir, "hero", listOf("hero.idle.motion3.json"))
        assertEquals(first, VTubeStudioTarget.config(ir, "hero", listOf("hero.idle.motion3.json")))
    }

    @Test fun choiceDefinitionsRejectInvalidDefaults() {
        assertFailsWith<IllegalArgumentException> { TargetSetting.Choice("mode", "missing", listOf("one")) }
        assertFailsWith<IllegalArgumentException> { TargetSetting.Choice("mode", "one", listOf("one", "one")) }
    }
}
