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
	override val settings: List<TargetSetting> get() = Moc3Target.settings

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

	/** A stable 32-digit hex id derived from [seed], so exports stay byte for byte reproducible. */
	private fun id(seed: String): String = MessageDigest.getInstance("SHA-256").digest(seed.encodeToByteArray())
		.take(16).joinToString("") { "%02x".format(it) }

	/** [expressionFiles] are each expression's name and exp3 file, in the manifest's order. */
	public fun config(ir: RigIR, baseName: String, motionFiles: List<String>, expressionFiles: List<Pair<String, String>> = emptyList()): String {
		val parameters = ir.parameters.associateBy { it.id }
		val chosen = if (parameters.keys.any { it in moduleMarkers }) tuned else mappings
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
				put("Icon", ""); put("Model", "$baseName.model3.json"); put("IdleAnimation", ""); put("IdleAnimationWhenTrackingLost", "")
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
		val config = config(ir, options.baseName, motions, expressions)
		val mapped = mappings.map { it.parameter }.toSet()
		val losses = CapabilityScan.scan(ir, capabilities, options) + Moc3Target.losses(ir, options, bundle.report.notices) + Moc3Target.hitAreaLosses(ir) +
			Moc3Target.clampedMotions(ir, bundle.report.notices) +
			listOf(LossEntry("*", Feature.STRUCTURE, Handling.APPROXIMATED,
				note = "Face tracking drives the standard parameters only: ${ir.parameters.count { it.id in mapped }} of ${ir.parameters.size}"))
		return object : LoweredExport {
			override val losses: List<LossEntry> = losses
			override fun write(sink: OutputSink) {
				bundle.files.forEach { sink.write(it.name, it.bytes) }
				sink.write("${options.baseName}.vtube.json", config.encodeToByteArray())
			}
		}
	}
}
