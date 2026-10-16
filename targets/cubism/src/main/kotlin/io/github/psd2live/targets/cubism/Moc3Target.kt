package io.github.psd2live.targets.cubism

import io.github.psd2live.format.compile.*
import io.github.psd2live.format.model.Clip
import io.github.psd2live.format.model.ColorBlend
import io.github.psd2live.format.model.Physics
import io.github.psd2live.format.model.RigIR
import org.umamo.format.moc3.Moc3
import org.umamo.format.moc3.json.FileReferences
import org.umamo.format.moc3.json.Model3Group
import org.umamo.format.moc3.json.Model3Json
import org.umamo.format.moc3.json.Model3Motion
import org.umamo.interop.ExportNotice
import org.umamo.interop.moc3.Moc3ExportOptions
import org.umamo.interop.moc3.Moc3Sidecars
import org.umamo.render.canvasToParentSpaceFor
import org.umamo.render.restMeshesToCanvasSpace
import org.umamo.runtime.model.ParameterId

/**
 * The Cubism runtime family: `.moc3` with its model3, physics3, motion3, cdi3 and texture files.
 *
 * Settings (all optional): `physics`, `user_data`, `display_info`, `hidden_parts`, `hidden_meshes`,
 * `guide_parts` (booleans) and `pixels_per_unit` (a positive number overriding the bake scale).
 */
public object Moc3Target : ExportTarget {
	override val id: String = "moc3"
	override val family: TargetFamily = TargetFamily.RIG
	override val description: String = "Cubism runtime (.moc3 + model3.json)"
	override val capabilities: CapabilityProfile = CapabilityProfile(
		warpLattice = true, parameterGrid = 3, blendShapes = true, timeline = true,
		physics = PhysicsSupport.PARAMETER_PENDULUM, blendModes = ColorBlend.entries.toSet(),
		masks = MaskSupport.TEXTURE_ALPHA, keyedDrawOrder = true, glue = true,
	)
	override val settings: List<TargetSetting> = listOf(
		TargetSetting.Flag("physics", true), TargetSetting.Flag("user_data", true), TargetSetting.Flag("display_info", true),
		TargetSetting.Flag("hidden_parts", true), TargetSetting.Flag("hidden_meshes", true), TargetSetting.Flag("guide_parts", false),
		TargetSetting.Number("pixels_per_unit", null, 1.0, 100000.0, 1.0, 0),
	)

	public fun options(options: ExportOptions): Moc3ExportOptions = Moc3ExportOptions(
		exportHiddenParts = options.flag("hidden_parts", true),
		exportHiddenDrawables = options.flag("hidden_meshes", true),
		exportGuideImageParts = options.flag("guide_parts", false),
		includePhysics = options.flag("physics", true),
		includeUserData = options.flag("user_data", true),
		includeDisplayInfo = options.flag("display_info", true),
		pixelsPerUnitOverride = options.setting("pixels_per_unit")?.toFloatOrNull()?.takeIf { it > 0f },
	)

	/** The bundle and the lowering's notices, for hosts that keep the files in memory. */
	public fun bundle(ir: RigIR, options: ExportOptions): Moc3Sidecars.Bundle {
		val baseName = options.baseName
		val moc3Options = options(options)
		val puppet = PuppetIr.toPuppet(ir)
		val exportPuppet = restMeshesToCanvasSpace(puppet, ir.restPose.mapKeys { ParameterId(it.key) })
		val parameterIds = ir.parameters.mapTo(HashSet()) { it.id }
		val textureFolder = "$baseName.${ir.textures.pages.firstOrNull()?.width ?: 0}"
		val pages = ir.textures.pages.mapIndexed { index, page ->
			require(page.png.size > 0) { "Texture page $index has no pixels" }
			Moc3Sidecars.AtlasPage("$textureFolder/texture_${index.toString().padStart(2, '0')}.png", page.png.shared())
		}
		val texts = sidecarTexts(SidecarKey(ir.physics, ir.clips, parameterIds, baseName))
		val motions = texts.motions
		val sidecars = buildList {
			if (moc3Options.includePhysics) texts.physics?.let {
				add(Moc3Sidecars.PassThroughSidecar(Moc3Sidecars.SidecarKind.Physics, "$baseName.physics3.json", it))
			}
			for ((_, file, json) in motions) add(Moc3Sidecars.PassThroughSidecar(Moc3Sidecars.SidecarKind.Motion, file, json))
		}
		val manifest = Model3Json(
			version = 3,
			fileReferences = FileReferences(moc = "", textures = emptyList(),
				motions = motions.takeIf { it.isNotEmpty() }?.groupBy({ it.first }, { Model3Motion(file = it.second) })),
			// Cubism knows these two groups; the other roles are for runtimes that drive them themselves.
			groups = ir.parameterRoles.filter { it.role == "EyeBlink" || it.role == "LipSync" }.mapNotNull { role ->
				role.parameters.filter(parameterIds::contains).takeIf { it.isNotEmpty() }?.let { Model3Group("Parameter", role.role, it) }
			},
		)
		return Moc3Sidecars.bundle(exportPuppet, baseName, pages = pages, sidecars = sidecars, source = manifest,
			canvasToParentSpace = canvasToParentSpaceFor(exportPuppet), options = moc3Options)
	}

	/** What the physics3 and motion3 texts of a bundle depend on. */
	private data class SidecarKey(val physics: Physics, val clips: List<Clip>, val parameterIds: Set<String>, val baseName: String)

	/** The normalized physics3 text (checked by reading it back) and each motion's group, file and normalized text. */
	private class SidecarTexts(physicsText: String?, val motions: List<Triple<String, String, String>>) {
		/** Read back once, when a bundle first carries it. */
		val physics: String? by lazy { physicsText?.also { Moc3.readPhysics3(it) } }
	}

	/**
	 * The last few sidecar texts by their inputs. An editor rebuilds the preview bundle on every geometry edit
	 * while physics and motions stay the same, and writing and normalizing them costs more than lowering the moc;
	 * the texts are pure functions of the key, so a hit is byte-identical.
	 */
	private val sidecarCache = object : LinkedHashMap<SidecarKey, SidecarTexts>(8, 0.75f, true) {
		override fun removeEldestEntry(eldest: MutableMap.MutableEntry<SidecarKey, SidecarTexts>?) = size > 4
	}

	private fun sidecarTexts(key: SidecarKey): SidecarTexts {
		synchronized(sidecarCache) { sidecarCache[key] }?.let { return it }
		val physics = Cubism3Json.physics3(key.physics.groups, key.physics.fps?.toInt() ?: 0)?.let(Cubism3Json::normalize)
		val motions = key.clips.mapNotNull { clip ->
			val json = Cubism3Json.motion3(clip, key.parameterIds) ?: return@mapNotNull null
			Triple(clip.group, "${key.baseName}.${clip.file}.motion3.json", Cubism3Json.normalize(json))
		}
		return SidecarTexts(physics, motions).also { synchronized(sidecarCache) { sidecarCache[key] = it } }
	}

	override fun plan(ir: RigIR, options: ExportOptions): LoweredExport {
		val bundle = bundle(ir, options)
		val losses = CapabilityScan.scan(ir, capabilities, options) + bundle.report.notices.map(::loss)
		return object : LoweredExport {
			override val losses: List<LossEntry> = losses
			override fun write(sink: OutputSink) = bundle.files.forEach { sink.write(it.name, it.bytes) }
		}
	}

	/** An engine lowering notice as a loss entry. */
	public fun loss(notice: ExportNotice): LossEntry = when (notice) {
		is ExportNotice.FeatureStripped -> LossEntry(notice.subjects.joinToString(",").ifEmpty { "*" }, Feature.STRUCTURE, Handling.DROPPED,
			note = "${notice.feature} is not supported by the target runtime version")
		is ExportNotice.UnsupportedChange -> LossEntry(notice.subject ?: "*", Feature.STRUCTURE, Handling.DROPPED,
			note = "${notice.category} change not representable: ${notice.reason}")
		is ExportNotice.WeldDivergence -> LossEntry(notice.drawableNames.joinToString(","), Feature.GLUE, Handling.APPROXIMATED,
			note = "Welded meshes diverge after lowering")
		is ExportNotice.MissingSourceArt -> LossEntry("*", Feature.TEXTURE_SIZE, Handling.APPROXIMATED,
			note = "${notice.pageCount} texture pages rebuilt without source art")
		is ExportNotice.SharedAtlasSlotKept -> LossEntry(notice.drawableNames.joinToString(","), Feature.TEXTURE_SIZE, Handling.APPROXIMATED,
			note = "Meshes keep a shared atlas slot")
		is ExportNotice.DrawOrdersRenumbered -> LossEntry(notice.subjects.joinToString(","), Feature.STRUCTURE, Handling.APPROXIMATED,
			note = "Fractional draw orders renumbered to whole numbers; the stacking is unchanged")
	}
}
