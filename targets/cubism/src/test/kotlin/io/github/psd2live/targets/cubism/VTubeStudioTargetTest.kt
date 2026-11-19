package io.github.psd2live.targets.cubism

import io.github.psd2live.format.compile.Compiler
import io.github.psd2live.format.compile.ExportOptions
import io.github.psd2live.format.compile.PngEncoder
import io.github.psd2live.format.compile.TargetSetting
import io.github.psd2live.format.model.*
import kotlinx.serialization.json.*
import javax.imageio.ImageIO
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

    @Test fun exportWiresIconAndUnassignedMotionAndExpressionHotkeys() {
        val ir = drawableRig()
        val files = export(ir)
        val config = Json.parseToJsonElement(files.getValue("hero.vtube.json").decodeToString()).jsonObject
        val references = config.getValue("FileReferences").jsonObject
        assertEquals("hero.model3.json", references.getValue("Model").jsonPrimitive.content)
        assertTrue(references.values.map { it.jsonPrimitive.content }.filter(String::isNotEmpty).all { it in files })
        assertEquals("hero.idle.motion3.json", references.getValue("IdleAnimationWhenTrackingLost").jsonPrimitive.content)
        val hotkeys = config.getValue("Hotkeys").jsonArray.map { it.jsonObject }
        assertEquals(listOf("TriggerAnimation", "ToggleExpression"), hotkeys.map { it.getValue("Action").jsonPrimitive.content })
        assertEquals(listOf("Rest", "Excited"), hotkeys.map { it.getValue("Name").jsonPrimitive.content })
        val hotkeyFiles = hotkeys.map { it.getValue("File").jsonPrimitive.content }
        assertEquals(listOf("hero.idle.motion3.json", "hero.excited.exp3.json"), hotkeyFiles)
        assertTrue(hotkeyFiles.all { it in files })
        for (hotkey in hotkeys) {
            val triggers = hotkey.getValue("Triggers").jsonObject
            assertEquals(-1, triggers.getValue("ScreenButton").jsonPrimitive.int)
            for (key in listOf("Trigger1", "Trigger2", "Trigger3")) assertEquals("", triggers.getValue(key).jsonPrimitive.content)
        }
        val iconFile = references.getValue("Icon").jsonPrimitive.content
        val icon = ImageIO.read(files.getValue(iconFile).inputStream())
        assertEquals(512, icon.width)
        assertEquals(512, icon.height)
        assertEquals(0, icon.getRGB(0, 0) ushr 24)
        assertEquals(0xFFFF0000.toInt(), icon.getRGB(256, 256))
        assertEquals(0, icon.getRGB(40, 256) ushr 24)
        assertEquals(0xFFFF0000.toInt(), icon.getRGB(256, 30))
        assertContentEquals(files.getValue(iconFile), export(ir).getValue(iconFile))
    }

    @Test fun exportOmitsIconWhenAllArtworkIsAnExcludedGuide() {
        val ir = drawableRig()
        val guide = Part("PartGuide", "Guide", listOf(ChildRef.MeshRef("ArtMeshBox")), sketch = true)
        val guideRig = ir.copy(parts = listOf(guide), rootChildren = listOf(ChildRef.PartRef(guide.id)))
        val files = export(guideRig)
        val references = Json.parseToJsonElement(files.getValue("hero.vtube.json").decodeToString()).jsonObject.getValue("FileReferences").jsonObject
        assertEquals("", references.getValue("Icon").jsonPrimitive.content)
        assertFalse(files.keys.any { it.endsWith(".icon.png") })
        assertTrue("hero.icon.png" in export(guideRig, mapOf("guide_parts" to "true")))
    }

    private fun drawableRig(): RigIR {
        val png = PngEncoder.encode(2, 2, IntArray(4) { 0xFFFF0000.toInt() })
        val idle = Clip("idle", "Rest", "Idle", "idle", 1f, 30f, loop = true,
            curves = listOf(Curve("ParamA", 0f, 0f, listOf(CurveSegment.Linear(1f, 0f)))))
        val expression = ExpressionIR("excited", "Excited", 0.2f, 0.2f, listOf(ExpressionParameter("ParamA", ExpressionBlend.ADD, 0.5f)))
        return rig("ParamA").copy(
            canvas = Canvas(200f, 150f),
            meshes = listOf(Mesh("ArtMeshBox", "Box", null, geometry = MeshGeometry(
                Floats.values(20f, 25f, 40f, 25f, 40f, 65f, 20f, 65f),
                Floats.values(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f), Ints.values(0, 1, 2, 0, 2, 3),
            ), offsets = null, page = 0)),
            textures = Textures(pages = listOf(TexturePage(2, 2, Bytes.wrap(png)))),
            clips = listOf(idle), advanced = AdvancedIR(expressions = listOf(expression)),
        )
    }

    private fun export(ir: RigIR, settings: Map<String, String> = emptyMap()): Map<String, ByteArray> = buildMap {
        Compiler.export(VTubeStudioTarget, ir, ExportOptions("hero", settings = settings)) { file, bytes -> put(file, bytes) }
    }
}
