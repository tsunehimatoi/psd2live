package io.github.psd2live.targets.cubism

import io.github.psd2live.format.compile.*
import io.github.psd2live.format.model.RigIR
import kotlinx.serialization.json.*
import java.security.MessageDigest

/**
 * A model folder VTube Studio loads: the Cubism runtime files plus `<name>.vtube.json`, which maps face
 * tracking to the rig's standard parameters (head, body, eyes, gaze, brows, mouth, breathing) and adds a
 * hotkey for each clip and one toggling each expression. VTube Studio fills every setting the file leaves out with its defaults. Takes
 * the moc3 settings.
 *
 * A rig built with the rig modules (it has their parameters, such as the smiling eyes or the vowels) gets the mapping
 * tuned for it: the body follows the head with more smoothing, a beat late; the eyes shut crisply - a dead zone at the
 * bottom and full open before the tracker's top, clamped, so an eye that can widen past 1 is not widened by an
 * ordinary open eye; the mouth opens readily at a small opening and its form ignores a little noise round the middle;
 * the smiling mouth curves the eyes; and the face capture's mouth sideways and cheek puff reach their parameters.
 */
public object VTubeStudioTarget : ExportTarget {
	override val id: String = "vtube-studio"
	override val family: TargetFamily = TargetFamily.RIG
	override val description: String = "VTube Studio model (moc3 + vtube.json)"
	override val capabilities: CapabilityProfile = Moc3Target.capabilities
	override val settings: List<TargetSetting> get() = Moc3Target.settings + listOf(
		TargetSetting.Choice("vts_mouth", "standard", listOf("standard", "voice", "voice_hybrid", "vbridger")),
		TargetSetting.Choice("vts_gaze", "independent", listOf("independent", "shared", "mouse")),
		TargetSetting.Choice("vts_brows", "independent", listOf("independent", "shared")),
		TargetSetting.Flag("vts_position", false), TargetSetting.Flag("vts_angry", false),
	)

	/** One tracking input driving a parameter over its whole range. */
	private class Mapping(
		val name: String, val input: String, val parameter: String, val inputLower: Float, val inputUpper: Float,
		val blinking: Boolean = false, val breathing: Boolean = false,
		/** Output range when not the parameter's own, whether it clamps, and the smoothing when not the default. */
		val output: ClosedFloatingPointRange<Float>? = null, val clamp: Boolean = false, val smoothing: Int? = null,
	)

	/** The rig modules' parameters a tuned mapping is for: any of them marks a rig built with the modules. */
	private val moduleMarkers = setOf("ParamEyeLSmile", "ParamA", "ParamMouthX", "ParamBrowLAngle", "ParamCheek")

	private val tuned = listOf(
		Mapping("Face Left/Right Rotation", "FaceAngleX", "ParamAngleX", -30f, 30f),
		Mapping("Face Up/Down Rotation", "FaceAngleY", "ParamAngleY", -30f, 30f),
		Mapping("Face Lean Rotation", "FaceAngleZ", "ParamAngleZ", -30f, 30f),
		Mapping("Body Rotation X", "FaceAngleX", "ParamBodyAngleX", -30f, 30f, smoothing = 30),
		Mapping("Body Rotation Y", "FaceAngleY", "ParamBodyAngleY", -30f, 30f, smoothing = 30),
		Mapping("Body Rotation Z", "FaceAngleZ", "ParamBodyAngleZ", -30f, 30f, smoothing = 30),
		Mapping("Eye Open Left", "EyeOpenLeft", "ParamEyeLOpen", 0.1f, 0.85f, blinking = true, output = 0f..1f, clamp = true, smoothing = 6),
		Mapping("Eye Open Right", "EyeOpenRight", "ParamEyeROpen", 0.1f, 0.85f, blinking = true, output = 0f..1f, clamp = true, smoothing = 6),
		Mapping("Eye Smile Left", "MouthSmile", "ParamEyeLSmile", 0.6f, 1f, output = 0f..1f, clamp = true),
		Mapping("Eye Smile Right", "MouthSmile", "ParamEyeRSmile", 0.6f, 1f, output = 0f..1f, clamp = true),
		Mapping("Eye X", "EyeRightX", "ParamEyeBallX", -1f, 1f),
		Mapping("Eye Y", "EyeRightY", "ParamEyeBallY", -1f, 1f),
		Mapping("Brow Height Left", "BrowLeftY", "ParamBrowLY", 0f, 1f),
		Mapping("Brow Height Right", "BrowRightY", "ParamBrowRY", 0f, 1f),
		Mapping("Mouth Smile", "MouthSmile", "ParamMouthForm", 0.1f, 0.9f, clamp = true),
		Mapping("Mouth Open", "MouthOpen", "ParamMouthOpenY", 0f, 0.75f, output = 0f..1f, clamp = true, smoothing = 8),
		Mapping("Mouth X", "MouthX", "ParamMouthX", -1f, 1f, clamp = true),
		Mapping("Cheek Puff", "CheekPuff", "ParamCheekPuff", 0f, 1f, clamp = true),
		Mapping("Breath", "", "ParamBreath", 0f, 1f, breathing = true),
	)

	private val mappings = listOf(
		Mapping("Face Left/Right Rotation", "FaceAngleX", "ParamAngleX", -30f, 30f),
		Mapping("Face Up/Down Rotation", "FaceAngleY", "ParamAngleY", -30f, 30f),
		Mapping("Face Lean Rotation", "FaceAngleZ", "ParamAngleZ", -30f, 30f),
		Mapping("Body Rotation X", "FaceAngleX", "ParamBodyAngleX", -30f, 30f),
		Mapping("Body Rotation Y", "FaceAngleY", "ParamBodyAngleY", -30f, 30f),
		Mapping("Body Rotation Z", "FaceAngleZ", "ParamBodyAngleZ", -30f, 30f),
		Mapping("Eye Open Left", "EyeOpenLeft", "ParamEyeLOpen", 0f, 1f, blinking = true),
		Mapping("Eye Open Right", "EyeOpenRight", "ParamEyeROpen", 0f, 1f, blinking = true),
		Mapping("Eye X", "EyeRightX", "ParamEyeBallX", -1f, 1f),
		Mapping("Eye Y", "EyeRightY", "ParamEyeBallY", -1f, 1f),
		Mapping("Brow Height Left", "BrowLeftY", "ParamBrowLY", 0f, 1f),
		Mapping("Brow Height Right", "BrowRightY", "ParamBrowRY", 0f, 1f),
		Mapping("Mouth Smile", "MouthSmile", "ParamMouthForm", 0f, 1f),
		Mapping("Mouth Open", "MouthOpen", "ParamMouthOpenY", 0f, 1f),
		Mapping("Breath", "", "ParamBreath", 0f, 1f, breathing = true),
	)

	/** Official head, eye, mouth, mouse and voice inputs; alternatives are selected rather than summed. */
	public val officialInputs: Set<String> = linkedSetOf(
		"FacePositionX", "FacePositionY", "FacePositionZ", "FaceAngleX", "FaceAngleY", "FaceAngleZ",
		"MouthSmile", "MouthOpen", "Brows", "MousePositionX", "MousePositionY", "TongueOut",
		"EyeOpenLeft", "EyeOpenRight", "EyeLeftX", "EyeLeftY", "EyeRightX", "EyeRightY", "CheekPuff",
		"BrowLeftY", "BrowRightY", "VoiceFrequency", "VoiceVolume", "VoiceVolumePlusMouthOpen",
		"VoiceFrequencyPlusMouthSmile", "VoiceA", "VoiceI", "VoiceU", "VoiceE", "VoiceO", "VoiceSilence", "MouthX", "FaceAngry",
	)

	private fun selected(ir: RigIR, options: ExportOptions): List<Mapping> {
		val ids = ir.parameters.mapTo(HashSet()) { it.id }
		fun choice(key: String) = (settings.first { it.key == key } as TargetSetting.Choice).let { options.choice(key, it.default, it.values) }
		val mouth = choice("vts_mouth"); val gaze = choice("vts_gaze"); val brows = choice("vts_brows")
		val base = if (ids.any { it in moduleMarkers }) tuned else mappings
		val result = base.filterNot { it.parameter in setOf("ParamEyeBallX", "ParamEyeBallY", "ParamBrowLY", "ParamBrowRY", "ParamMouthOpenY", "ParamMouthForm") }.toMutableList()
		fun add(input: String, parameter: String, low: Float = 0f, high: Float = 1f, output: ClosedFloatingPointRange<Float>? = null) {
			result += Mapping(input, input, parameter, low, high, output = output, clamp = true)
		}
		if (gaze == "independent" && listOf("LX", "LY", "RX", "RY").all { "ParamEyeBall$it" in ids }) {
			for (side in listOf("Left" to "L", "Right" to "R")) for (axis in listOf("X", "Y"))
				add("Eye${side.first}$axis", "ParamEyeBall${side.second}$axis", -1f)
		} else for (axis in listOf("X", "Y")) add(if (gaze == "mouse") "MousePosition$axis" else "EyeRight$axis", "ParamEyeBall$axis", -1f)
		for (side in listOf("L" to "Left", "R" to "Right")) add(if (brows == "shared") "Brows" else "Brow${side.second}Y", "ParamBrow${side.first}Y")
		when (mouth) {
			"standard", "vbridger" -> result += base.filter { it.parameter == "ParamMouthOpenY" || it.parameter == "ParamMouthForm" }
			"voice", "voice_hybrid" -> {
				val vowels = listOf("A", "I", "U", "E", "O").any { "Param$it" in ids }
				if (mouth == "voice_hybrid" || !vowels) add(if (mouth == "voice_hybrid") "VoiceVolumePlusMouthOpen" else "VoiceVolume", "ParamMouthOpenY", output = 0f..1f)
				add(if (mouth == "voice_hybrid") "VoiceFrequencyPlusMouthSmile" else "VoiceFrequency", "ParamMouthForm")
				for (vowel in listOf("A", "I", "U", "E", "O")) add("Voice$vowel", "Param$vowel")
				add("VoiceSilence", "ParamVoiceSilence")
			}
		}
		add("TongueOut", "ParamTongueOut")
		if (mouth == "vbridger") {
			add("MouthPucker", "ParamMouthPuckerWiden", -1f)
			add("MouthFunnel", "ParamMouthFunnel"); add("MouthShrug", "ParamMouthShrug")
			add("MouthPressLipOpen", "ParamMouthPressLipOpen", -1.3f, 1.3f)
			add("JawOpen", "ParamJawOpen")
		}
		if (options.flag("vts_position", false)) for (axis in listOf("X", "Y", "Z")) add("FacePosition$axis", "ParamFacePosition$axis", -10f, 10f)
		if (options.flag("vts_angry", false)) {
			add("FaceAngry", "ParamEyeLForm"); add("FaceAngry", "ParamEyeRForm")
		}
		// Directly named optional inputs preserve custom rigs without creating an unbound parameter.
		for (input in officialInputs) {
			if (input.startsWith("FacePosition") && !options.flag("vts_position", false)) continue
			if (input == "FaceAngry" && !options.flag("vts_angry", false)) continue
			val range = when {
				input.startsWith("FaceAngle") -> -30f..30f
				input.startsWith("FacePosition") -> -10f..10f
				input.startsWith("MousePosition") || input in setOf("EyeLeftX", "EyeLeftY", "EyeRightX", "EyeRightY", "MouthX") -> -1f..1f
				else -> 0f..1f
			}
			if ("Param$input" in ids) add(input, "Param$input", range.start, range.endInclusive)
		}
		return result.filter { it.parameter in ids }.distinctBy { it.parameter }
	}

	/** A stable 32-digit hex id derived from [seed], so exports stay byte for byte reproducible. */
	private fun id(seed: String): String = MessageDigest.getInstance("SHA-256").digest(seed.encodeToByteArray())
		.take(16).joinToString("") { "%02x".format(it) }

	/** [expressionFiles] are each expression's name and exp3 file, in the manifest's order. */
	public fun config(ir: RigIR, baseName: String, motionFiles: List<String>, expressionFiles: List<Pair<String, String>> = emptyList(), options: ExportOptions = ExportOptions(baseName)): String {
		val parameters = ir.parameters.associateBy { it.id }
		val chosen = selected(ir, options)
		val settings = chosen.mapNotNull { m ->
			val p = parameters[m.parameter] ?: return@mapNotNull null
			buildJsonObject {
				put("Folder", ""); put("Name", m.name); put("Input", m.input)
				put("InputRangeLower", m.inputLower); put("InputRangeUpper", m.inputUpper)
				put("OutputRangeLower", m.output?.start ?: p.min); put("OutputRangeUpper", m.output?.endInclusive ?: p.max)
				put("ClampInput", false); put("ClampOutput", m.clamp)
				put("UseBlinking", m.blinking); put("UseBreathing", m.breathing)
				put("OutputLive2D", m.parameter); put("Smoothing", m.smoothing ?: if (m.blinking) 10 else 15); put("Minimized", false)
			}
		}
		fun hotkey(file: String, name: String, action: String) = buildJsonObject {
			put("HotkeyID", id("$baseName/hotkey/$file")); put("Name", name)
			put("Action", action); put("File", file); put("Folder", "")
			putJsonObject("Triggers") { put("Trigger1", ""); put("Trigger2", ""); put("Trigger3", ""); put("ScreenButton", 0) }
			put("IsGlobal", false); put("IsActive", true); put("Minimized", false)
			put("StopsOnLastFrame", false); put("DeactivateAfterKeyTypedAfterMillis", -1)
		}
		val hotkeys = motionFiles.map { file -> hotkey(file, file.removePrefix("$baseName.").removeSuffix(".motion3.json"), "TriggerAnimation") } +
			expressionFiles.map { (name, file) -> hotkey(file, name, "ToggleExpression") }
		val json = buildJsonObject {
			put("Version", 1)
			put("Name", baseName)
			put("ModelID", id("$baseName/${ir.canvas.width}x${ir.canvas.height}/${ir.parameters.joinToString(",") { it.id }}"))
			putJsonObject("FileReferences") {
				// A module rig's idle carries on when the tracker loses the face, so the model never freezes.
				val lost = if (parameters.keys.any { it in moduleMarkers }) motionFiles.firstOrNull { it == "$baseName.idle.motion3.json" }.orEmpty() else ""
				put("Icon", ""); put("Model", "$baseName.model3.json"); put("IdleAnimation", ""); put("IdleAnimationWhenTrackingLost", lost)
			}
			put("ParameterSettings", JsonArray(settings))
			put("Hotkeys", JsonArray(hotkeys))
		}
		return Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), json)
	}

	override fun plan(ir: RigIR, options: ExportOptions): LoweredExport {
		val bundle = Moc3Target.bundle(ir, options)
		val motions = bundle.files.map { it.name }.filter { it.endsWith(".motion3.json") }
		val written = bundle.files.mapTo(HashSet()) { it.name }
		val expressions = ir.advanced.expressions.map { it.name to "${options.baseName}.${it.id}.exp3.json" }.filter { it.second in written }
		val config = config(ir, options.baseName, motions, expressions, options)
		val mapped = selected(ir, options).map { it.parameter }.toSet()
		val losses = CapabilityScan.scan(ir, capabilities, options) + Moc3Target.losses(ir, options, bundle.report.notices) + Moc3Target.hitAreaLosses(ir) +
			Moc3Target.clampedMotions(ir, bundle.report.notices) +
			listOf(LossEntry("*", Feature.STRUCTURE, Handling.APPROXIMATED,
				note = "Face tracking mapping covers: ${ir.parameters.count { it.id in mapped }} of ${ir.parameters.size}"))
		return object : LoweredExport {
			override val losses: List<LossEntry> = losses
			override fun write(sink: OutputSink) {
				bundle.files.forEach { sink.write(it.name, it.bytes) }
				sink.write("${options.baseName}.vtube.json", config.encodeToByteArray())
			}
		}
	}
}
