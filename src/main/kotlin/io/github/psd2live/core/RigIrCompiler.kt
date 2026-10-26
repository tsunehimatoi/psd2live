package io.github.psd2live.core

import io.github.psd2live.format.model.AdvancedIR
import io.github.psd2live.format.model.Bytes
import io.github.psd2live.format.model.Clip
import io.github.psd2live.format.model.ClipEvent
import io.github.psd2live.format.model.ParameterRole
import io.github.psd2live.format.model.Physics
import io.github.psd2live.format.model.RigIR
import io.github.psd2live.format.model.TexturePage
import io.github.psd2live.format.model.TileArt
import io.github.psd2live.targets.cubism.PuppetIr

/**
 * Compiles an evaluated rig into the neutral [RigIR] every exporter reads: the rig itself, its packed
 * texture pages, the active physics and the motions the export settings select, already compiled from
 * their presets into curves.
 */
internal object RigIrCompiler {
	fun compile(preview: RigPreviewModel, tileArt: Boolean = false, simulations: Boolean = false): RigIR =
		compile(preview.analysis, preview.atlas, preview.rig, preview.config, tileArt, simulations)

	/**
	 * [tileArt] adds every tile's unpacked source pixels, which only formats with editable source layers need;
	 * [simulations] the baked simulations as the runtime's advanced mode plays them, each calibrated anew (a second
	 * or so each), which only the runtime's own targets read.
	 */
	fun compile(analysis: PipelineAnalysis, atlas: PackedAtlas, rig: BuiltRig, config: PipelineConfig, tileArt: Boolean = false,
				simulations: Boolean = false): RigIR {
		val physicsGroups = PhysicsCatalog.active(analysis, config, rig.puppet.parameters.mapTo(linkedSetOf()) { it.id.raw }, rig.puppet)
		val puppet = io.github.psd2live.core.interior.InteriorLifecycle.exportModel(rig.puppet, config.rigEdits, physicsGroups)
		val exportedRig = rig.copy(puppet = puppet)
		val parameterIds = puppet.parameters.mapTo(linkedSetOf()) { it.id.raw }
		// A standard parameter an ID conversion renamed keeps its role under its new ID.
		val renames = config.rigEdits.idRenames.parameters
		fun standard(id: String) = renames[id] ?: id
		val base = PuppetIr.toIr(
			puppet,
			physics = Physics(physicsGroups.map(Physics3Json::group), config.rigEdits.physicsFps.toFloat()),
			clips = clips(exportedRig, config, physicsGroups, parameterIds),
			parameterRoles = buildList {
				if (config.motionBasic && config.motionBlink && !config.meshOnly) add(ParameterRole("EyeBlink", listOf(standard("ParamEyeLOpen"), standard("ParamEyeROpen"))))
				add(ParameterRole("LipSync", listOf(standard("ParamMouthOpenY"))))
				// What a runtime's breathing and gaze drive, when the rig has them.
				for ((role, id) in listOf("Breath" to "ParamBreath", "AngleX" to "ParamAngleX", "AngleY" to "ParamAngleY", "AngleZ" to "ParamAngleZ",
						"BodyAngleX" to "ParamBodyAngleX", "EyeBallX" to "ParamEyeBallX", "EyeBallY" to "ParamEyeBallY")) {
					if (standard(id) in parameterIds) add(ParameterRole(role, listOf(standard(id))))
				}
			},
		)
		return base.copy(
			textures = base.textures.copy(
				// Pages not encoded yet encode in parallel.
				pages = atlas.pages.parallelStream().map { TexturePage(it.image.width, it.image.height, Bytes.wrap(it.png)) }.toList(),
				bindings = rig.pageByDrawableId,
				tileArt = if (!tileArt) emptyList() else PuppetSourceAtlas.rastersByTile(analysis).map { (tile, raster) ->
				TileArt(tile.raw, raster.width, raster.height, Bytes.wrap(raster.rgba))
			}),
			// The open mouth keeps its texture coordinates over the whole artwork in a canvas-space base mesh.
			restPose = if (config.rigEdits.importedCmo3 != null) emptyMap() else mapOf(standard(StandardParameters.MOUTH_OPEN.raw) to 1f),
			advanced = advanced(exportedRig, config, base, simulations).copy(
				expressions = if (config.meshOnly) emptyList() else RuntimeExtras.expressions(config.rigEdits, parameterIds),
				hitAreas = RuntimeExtras.hitAreas(puppet, analysis, rig.layerIdByDrawableId),
				pose = if (config.meshOnly) null else RuntimeExtras.pose(config.rigEdits, puppet.parts.mapTo(HashSet()) { it.id.raw }),
			),
			hitAreas = RigHitAreas.toIr(config.rigEdits.hitAreas),
		)
	}

	/**
	 * What the runtime's advanced mode plays live that the rig bakes: the skeleton's folded bones and, with
	 * [simulations], the baked simulations and their colliders.
	 */
	private fun advanced(rig: BuiltRig, config: PipelineConfig, base: RigIR, simulations: Boolean): AdvancedIR {
		val model = rig.puppet
		val bones = config.rigEdits.skeleton?.let { spec ->
			val frame = Bounds(0f, 0f, model.canvasWidth.coerceAtLeast(1f), model.canvasHeight.coerceAtLeast(1f))
			runCatching { SkeletonRig.virtualBones(model, spec, frame) }.getOrElse { emptyList() }
		}.orEmpty()
		val sims = if (!simulations) null else io.github.psd2live.core.sim.SimExport.export(model, config.rigEdits.simEdits,
			base.parameters.mapTo(HashSet()) { it.id }, base.physics.groups.mapTo(HashSet()) { it.id })
		return AdvancedIR(
			virtualBones = bones.map { PuppetIr.deformerToIr(it) as io.github.psd2live.format.model.Deformer.Rotation },
			simulations = sims?.simulations.orEmpty(),
			colliders = sims?.colliders.orEmpty(),
		)
	}

	/**
	 * The motions an export carries, in the order the runtime manifest lists them: the basic presets, the
	 * skeleton presets, then the user's own clips. An edited preset exports its clip in place of the generated
	 * one; a deleted one is left out, as is any clip with no curve on an existing parameter.
	 */
	private fun clips(rig: BuiltRig, config: PipelineConfig, physicsGroups: List<RigPhysicsEdit>, parameterIds: Set<String>): List<Clip> {
		if (!config.exportMotions || config.meshOnly) return emptyList()
		val physicsDriven = if (config.exportIncludePhysics) physicsGroups.flatMapTo(HashSet()) { it.outputParameters } else emptySet()
		val key = ClipsKey(config.rigEdits.motionClips, rig.puppet.parameters, config.rigEdits.skeleton, config.rigEdits.motionPresets,
			listOf(config.motionBasic, config.motionIdle, config.motionBlink, config.motionNod, config.motionShake, config.motionSkeleton), physicsDriven,
			config.rigEdits.idRenames.parameters)
		synchronized(clipCache) { clipCache[key] }?.let { return it }
		return compileClips(rig, config, physicsDriven, parameterIds).also { synchronized(clipCache) { clipCache[key] = it } }
	}

	/** Everything [compileClips] reads besides the export switches checked before it. */
	private data class ClipsKey(
		val clips: List<MotionClip>, val parameters: List<org.umamo.runtime.model.Parameter>, val skeleton: SkeletonSpec?,
		val presets: Map<String, MotionPresetSettings>, val switches: List<Boolean>, val physicsDriven: Set<String>,
		val renames: Map<String, String>,
	)

	/**
	 * The last few compiled clip lists by their inputs. Every rebuild compiles them, and hashing the skeleton for
	 * each preset's generated-track lookup costs more than the rest of the IR; a geometry edit changes none of
	 * the inputs, and the clips are a pure function of the key.
	 */
	private val clipCache = object : LinkedHashMap<ClipsKey, List<Clip>>(8, 0.75f, true) {
		override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ClipsKey, List<Clip>>?) = size > 4
	}

	private fun compileClips(rig: BuiltRig, config: PipelineConfig, physicsDriven: Set<String>, parameterIds: Set<String>): List<Clip> {
		val clips = MotionClips.reconcileParameters(config.rigEdits.motionClips, rig.puppet.parameters)
		val skeleton = config.rigEdits.skeleton
		val renames = config.rigEdits.idRenames.parameters
		val original = renames.entries.associate { it.value to it.key }
		val result = ArrayList<Clip>()
		fun add(clip: Clip?) {
			if (clip != null && clip.curves.any { it.parameter in parameterIds }) result += clip.copy(curves = clip.curves.filter { it.parameter in parameterIds })
		}
		fun authored(group: String, file: String, clip: MotionClip) = Clip(clip.id, clip.name, group, file, clip.duration, clip.fps, clip.loop,
			clip.fadeIn, clip.fadeOut, clip.curves.map(MotionGenerator::curve), clip.events.map { ClipEvent(it.time, it.value) })
		fun builtin(group: String, name: String, exclude: Set<String> = emptySet()) {
			val settings = MotionPresetSettings.of(config, name)
			if (settings.deleted) return
			val file = name.replaceFirstChar(Char::lowercase)
			val override = MotionClips.overrideOf(clips, name)
			if (override != null) return add(authored(group, file, override))
			// Generated tracks name the standard parameters by their standard IDs.
			val tracks = MotionPresets.renamed(MotionPresets.tracks(name, skeleton, settings, exclude.mapTo(HashSet()) { original[it] ?: it }), renames)
			if (tracks.isEmpty()) return
			add(Clip(name, name, group, file, MotionPresets.duration(name, tracks, settings), 30f, MotionPresets.loops(name),
				curves = tracks.map(MotionGenerator::curve)))
		}
		if (config.motionBasic) {
			// Parameters the exported physics drives stay out of the idle.
			if (config.motionIdle) builtin("Idle", "Idle", physicsDriven)
			if (config.motionBlink) builtin("Blink", "Blink")
			if (config.motionNod) builtin("Nod", "Nod")
			if (config.motionShake) builtin("Shake", "Shake")
			// Speech, for hosts and players without lip sync; only the `motion/natural` way makes it.
			if (MotionPresetSettings.natural(config)) builtin("Talk", "Talk")
		}
		if (config.motionSkeleton) {
			for (preset in SkeletonMotions.presets) {
				if (config.rigEdits.motionPresets[preset.name]?.disabled == true) continue
				// A looping preset is another idle, played from the idle group beside the plain one.
				builtin(if (preset.loop) "Idle" else preset.name, preset.name)
			}
		}
		// The user's own motions: a loop joins the idles, a one-shot is its own group under its name.
		val stems = MotionClips.exportStems(clips)
		for (clip in clips.filter { it.builtin == null && it.enabled })
			add(authored(if (clip.loop) "Idle" else clip.name, stems.getValue(clip.id), clip))
		return result
	}
}
