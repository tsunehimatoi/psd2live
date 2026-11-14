package io.github.psd2live.targets.cubism

import io.github.psd2live.bridge.PuppetIr

import io.github.psd2live.format.compile.*
import io.github.psd2live.format.model.AlphaBlend
import io.github.psd2live.format.model.Clip
import io.github.psd2live.format.model.CurveSegment
import io.github.psd2live.format.model.ColorBlend
import io.github.psd2live.format.model.GroupMode
import io.github.psd2live.format.model.ExpressionIR
import io.github.psd2live.format.model.Physics
import io.github.psd2live.format.model.RigIR
import org.umamo.format.moc3.Moc3
import org.umamo.format.moc3.json.FileReferences
import org.umamo.format.moc3.json.Model3Expression
import org.umamo.format.moc3.json.Model3Group
import org.umamo.format.moc3.json.Model3HitArea
import org.umamo.format.moc3.json.Model3Json
import org.umamo.format.moc3.json.Model3Motion
import org.umamo.interop.ExportNotice
import org.umamo.interop.moc3.Moc3ExportOptions
import org.umamo.interop.moc3.Moc3Sidecars
import org.umamo.render.canvasToParentSpaceFor
import org.umamo.render.restMeshesToCanvasSpace
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.RuntimeFeature

/**
 * The Cubism runtime family: `.moc3` with its model3, physics3, motion3, exp3, pose3, cdi3, userdata3 and texture files.
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
		masks = MaskSupport.TEXTURE_ALPHA, keyedDrawOrder = true, glue = true, hitAreas = true,
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
		val texts = sidecarTexts(SidecarKey(ir.physics, ir.clips, ir.advanced.expressions, parameterIds, baseName))
		val motions = texts.motions
		val expressions = texts.expressions
		val sidecars = buildList {
			if (moc3Options.includePhysics) texts.physics?.let {
				add(Moc3Sidecars.PassThroughSidecar(Moc3Sidecars.SidecarKind.Physics, "$baseName.physics3.json", it))
			}
			for ((_, file, json) in motions) add(Moc3Sidecars.PassThroughSidecar(Moc3Sidecars.SidecarKind.Motion, file, json))
			for ((_, file, json) in expressions) add(Moc3Sidecars.PassThroughSidecar(Moc3Sidecars.SidecarKind.Expression, file, json))
			ir.advanced.pose?.let { Cubism3Json.pose3(it, ir.parts.mapTo(HashSet()) { part -> part.id }) }?.let {
				add(Moc3Sidecars.PassThroughSidecar(Moc3Sidecars.SidecarKind.Pose, "$baseName.pose3.json", Cubism3Json.normalize(it)))
			}
		}
		val manifest = Model3Json(
			version = 3,
			fileReferences = FileReferences(moc = "", textures = emptyList(),
				expressions = expressions.takeIf { it.isNotEmpty() }?.map { (name, file, _) -> Model3Expression(name = name, file = file) },
				motions = motions.takeIf { it.isNotEmpty() }?.groupBy({ it.first }, { Model3Motion(file = it.second) })),
			// Cubism knows these two groups; the other roles are for runtimes that drive them themselves.
			groups = ir.parameterRoles.filter { it.role == "EyeBlink" || it.role == "LipSync" }.mapNotNull { role ->
				role.parameters.filter(parameterIds::contains).takeIf { it.isNotEmpty() }?.let { Model3Group("Parameter", role.role, it) }
			},
			hitAreas = hitAreas(ir),
		)
		return Moc3Sidecars.bundle(exportPuppet, baseName, pages = pages, sidecars = sidecars, source = manifest,
			canvasToParentSpace = canvasToParentSpaceFor(exportPuppet), options = moc3Options)
	}

	/** What the physics3, motion3 and exp3 texts of a bundle depend on. */
	private data class SidecarKey(val physics: Physics, val clips: List<Clip>, val expressions: List<ExpressionIR>,
		val parameterIds: Set<String>, val baseName: String)

	/**
	 * The document's hit areas as model3 names them: one ArtMesh per entry, so an area of several meshes is one entry
	 * per mesh under the area's name, in test order. Null without any, so such a manifest stays as it was.
	 */
	private fun hitAreas(ir: RigIR): List<Model3HitArea>? {
		val meshes = ir.meshes.mapTo(HashSet()) { it.id }
		return ir.hitAreas.flatMap { area -> area.meshes.filter(meshes::contains).map { Model3HitArea(area.name, it) } }.takeIf { it.isNotEmpty() }
	}

	/** What model3 cannot say about the document's hit areas: their own IDs, and more than one mesh each. */
	public fun hitAreaLosses(ir: RigIR): List<LossEntry> {
		val meshes = ir.meshes.mapTo(HashSet()) { it.id }
		return ir.hitAreas.mapNotNull { area -> area.meshes.count(meshes::contains).takeIf { it > 1 }?.let { count ->
			LossEntry(area.id, Feature.STRUCTURE, Handling.APPROXIMATED,
				note = "model3 hit areas name one ArtMesh each: $count entries named ${area.name}, which a host testing only the first entry by name reads as its first mesh")
		} }
	}

	/**
	 * The normalized physics3 text (checked by reading it back), each motion's group, file and normalized text, and
	 * each expression's manifest name, file and normalized exp3 text in the IR's order. An exp3 file is named by the
	 * expression's ID, which the document keeps unique and file-safe.
	 */
	private class SidecarTexts(physicsText: String?, val motions: List<Triple<String, String, String>>,
		val expressions: List<Triple<String, String, String>>) {
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
		val expressions = key.expressions.map { expression ->
			Triple(expression.name, "${key.baseName}.${expression.id}.exp3.json", Cubism3Json.normalize(Cubism3Json.exp3(expression, key.parameterIds)))
		}
		return SidecarTexts(physics, motions, expressions).also { synchronized(sidecarCache) { sidecarCache[key] = it } }
	}

	override fun plan(ir: RigIR, options: ExportOptions): LoweredExport {
		val bundle = bundle(ir, options)
		val losses = CapabilityScan.scan(ir, capabilities, options) + losses(ir, options, bundle.report.notices) +
			clampedMotions(ir, bundle.report.notices) + hitAreaLosses(ir)
		return object : LoweredExport {
			override val losses: List<LossEntry> = losses
			override fun write(sink: OutputSink) = bundle.files.forEach { sink.write(it.name, it.bytes) }
		}
	}

	/**
	 * The lowering's [notices] as loss entries. Stripped Cubism 5.3 blend modes are not dropped - the strip rewrites
	 * each to the nearest mode the runtime has - so they become one approximated entry per mesh or isolated part of
	 * [ir], by ID, naming the mode it becomes; [options] may hand the feature another handling, as for every scan.
	 */
	public fun losses(ir: RigIR, options: ExportOptions, notices: List<ExportNotice>): List<LossEntry> = notices.flatMap { notice ->
		if (notice !is ExportNotice.FeatureStripped || notice.feature != RuntimeFeature.ExtendedBlendModes) return@flatMap listOf(loss(notice))
		val handling = options.handling[Feature.BLEND_MODE] ?: Handling.APPROXIMATED
		fun entry(id: String, blend: ColorBlend, alpha: AlphaBlend): LossEntry? {
			val legacy = blend == ColorBlend.NORMAL || blend == ColorBlend.ADD_PREMULTIPLIED || blend == ColorBlend.MULTIPLY_PREMULTIPLIED
			if (legacy && alpha == AlphaBlend.OVER) return null
			val nearest = when (blend) {
				ColorBlend.ADD_PREMULTIPLIED, ColorBlend.ADD, ColorBlend.ADD_GLOW, ColorBlend.SCREEN -> "add"
				ColorBlend.MULTIPLY_PREMULTIPLIED, ColorBlend.MULTIPLY -> "multiply"
				else -> "normal"
			}
			val color = if (legacy) "" else "${blend.name.lowercase()} becomes $nearest"
			val alphaNote = if (alpha == AlphaBlend.OVER) "" else "${alpha.name.lowercase()} alpha becomes over"
			return LossEntry(id, Feature.BLEND_MODE, handling,
				note = "Before Cubism 5.3: " + listOf(color, alphaNote).filter { it.isNotEmpty() }.joinToString("; "))
		}
		ir.meshes.mapNotNull { entry(it.id, it.blend, it.alphaBlend) } +
			ir.parts.filter { it.groupMode == GroupMode.ISOLATED }.mapNotNull { entry(it.id, it.composite.blend, it.composite.alphaBlend) }
	}

	/**
	 * Each motion curve keyed past the range of a parameter whose repeat the target version stripped: the editor wraps
	 * it a turn round, the runtime clamps it at the end of the range.
	 */
	public fun clampedMotions(ir: RigIR, notices: List<ExportNotice>): List<LossEntry> {
		val stripped = notices.filterIsInstance<ExportNotice.FeatureStripped>()
			.filter { it.feature == RuntimeFeature.ParameterRepeat }.flatMapTo(HashSet()) { it.subjects }
		if (stripped.isEmpty()) return emptyList()
		val parameters = ir.parameters.filter { it.id in stripped }.associateBy { it.id }
		return ir.clips.flatMap { clip ->
			clip.curves.mapNotNull { curve ->
				val parameter = parameters[curve.parameter] ?: return@mapNotNull null
				val values = sequenceOf(curve.startValue) + curve.segments.asSequence().flatMap { segment ->
					if (segment is CurveSegment.Bezier) sequenceOf(segment.c1Value, segment.c2Value, segment.value) else sequenceOf(segment.value)
				}
				if (values.all { it in parameter.min..parameter.max }) null
				else LossEntry("${clip.id}/${parameter.id}", Feature.TIMELINE, Handling.APPROXIMATED,
					note = "Motion ${clip.name} keys ${parameter.id} past its range; without parameter repeat the runtime clamps it where the editor preview wraps it")
			}
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
		is ExportNotice.HitAreaDropped -> LossEntry(notice.drawableId, Feature.STRUCTURE, Handling.DROPPED,
			note = "Hit area ${notice.name} names mesh ${notice.drawableId}, which the moc does not contain")
		is ExportNotice.DrawOrdersRenumbered -> LossEntry(notice.subjects.joinToString(","), Feature.STRUCTURE, Handling.APPROXIMATED,
			note = "Fractional draw orders renumbered to whole numbers; the stacking is unchanged")
	}
}
