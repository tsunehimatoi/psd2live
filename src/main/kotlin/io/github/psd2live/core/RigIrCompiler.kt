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
import io.github.psd2live.core.modifier.RigModifierAlgorithms
import io.github.psd2live.core.modifier.ModifierPluginCatalog
import io.github.psd2live.core.modifier.RigModifierStack
import io.github.psd2live.core.sim.RigSimEdit
import io.github.psd2live.core.sim.SimExport
import kotlinx.serialization.json.*

/**
 * Compiles an evaluated rig into the neutral [RigIR] every exporter reads: the rig itself, its packed
 * texture pages, the active physics and the motions the export settings select, already compiled from
 * their presets into curves.
 */
internal object RigIrCompiler {
    /** Keep standard runtime groups in the same order before and after materializing a stack prefix. */
    internal fun orderedParameterRoles(roles: List<ParameterRole>): List<ParameterRole> {
        val order = listOf("EyeBlink", "LipSync", "Breath", "AngleX", "AngleY", "AngleZ", "BodyAngleX", "EyeBallX", "EyeBallY")
        return roles.distinct().sortedBy { order.indexOf(it.role).takeIf { index -> index >= 0 } ?: order.size }
    }

	fun compile(preview: RigPreviewModel, tileArt: Boolean = false, simulations: Boolean = false): RigIR {
        preview.config.rigEdits.modifierStack?.let { ModifierPluginCatalog.requireAvailable(io.github.psd2live.core.modifier.RigModifierStack.fromJson(it)) }
        return compile(preview.analysis, preview.atlas, preview.rig, preview.config, tileArt, simulations)
    }

	/**
	 * [tileArt] adds every tile's unpacked source pixels, which only formats with editable source layers need;
	 * [simulations] the baked simulations as the runtime's advanced mode plays them, each calibrated anew (a second
	 * or so each), which only the runtime's own targets read.
	 */
	fun compile(analysis: PipelineAnalysis, atlas: PackedAtlas, rig: BuiltRig, config: PipelineConfig, tileArt: Boolean = false,
				simulations: Boolean = false): RigIR {
        rig.modifierOutput?.let { output ->
            val roles = orderedParameterRoles(authoredRoles(rig, config, parameterIds(rig)) + output.parameterRoles)
            val evaluated = PuppetIr.toIr(rig.puppet, physics = output.physics, clips = output.clips, parameterRoles = roles)
                .copy(restPose = output.restPose)
            val assembled = assemble(analysis, atlas, rig, config, evaluated, authoredMeshOnly(config), tileArt, simulations = false)
            return if (simulations) assembled.copy(advanced = modifierSimulations(rig, config, output, assembled.advanced)) else assembled
        }
        val physicsGroups = PhysicsCatalog.active(analysis, config, rig.puppet.parameters.mapTo(linkedSetOf()) { it.id.raw }, rig.puppet)
        val puppet = io.github.psd2live.core.interior.InteriorLifecycle.exportModel(rig.puppet, config.rigEdits, physicsGroups)
        val exported = rig.copy(puppet = puppet)
        val ids = parameterIds(exported)
        val physics = Physics(physicsGroups.map(Physics3Json::group), config.rigEdits.physicsFps.toFloat())
        val roles = parameterRoles(ids, config.rigEdits.idRenames.parameters, config.motionBasic && config.motionBlink && !config.meshOnly)
        val generated = PuppetIr.toIr(puppet, physics = physics, clips = clips(exported, config, physicsGroups, ids), parameterRoles = roles)
            .copy(restPose = legacyMouthRest(config))
        return assemble(analysis, atlas, exported, config, generated, config.meshOnly, tileArt, simulations)
    }

    /** Compile editable author content without consulting the legacy preset settings. */
    fun compileAuthored(analysis: PipelineAnalysis, atlas: PackedAtlas, rig: BuiltRig, config: PipelineConfig,
                        tileArt: Boolean = false): RigIR {
        val meshOnly = authoredMeshOnly(config)
        val available = parameterIds(rig)
        val claimed = HashSet<String>()
        val groups = PhysicsCatalog.ordered(config.rigEdits.physicsEdits, config.rigEdits.physicsOrder) { it.id }
            .filter { it.id !in config.rigEdits.disabledPhysicsIds && PhysicsCatalog.issueOf(it, available) == null }
            .map { group ->
                require(group.outputParameters.all { claimed.add(it) }) { "Ordinary physics groups have conflicting outputs" }
                Physics3Json.group(group)
            }
        val motions = clips(rig, config, emptyList(), available, generatePresets = false, meshOnly = meshOnly)
        val authored = PuppetIr.toIr(rig.puppet, physics = Physics(groups, config.rigEdits.physicsFps.toFloat()),
            clips = motions, parameterRoles = authoredRoles(rig, config, available)).copy(
            restPose = (if (meshOnly) legacyMouthRest(config) else emptyMap()) + rig.authoredRestPose.filterKeys { it in available },
        )
        return assemble(analysis, atlas, rig, config, authored, meshOnly, tileArt, simulations = false)
    }

    private fun authoredMeshOnly(config: PipelineConfig): Boolean {
        if (!config.meshOnly) return false
        val stack = config.rigEdits.modifierStack?.let(RigModifierStack::fromJson) ?: return false
        return stack.instances.none { it.enabled } && stack.appliedCapabilities.isEmpty()
    }

    private fun parameterIds(rig: BuiltRig): Set<String> = rig.puppet.parameters.mapTo(HashSet()) { it.id.raw }

    private fun authoredRoles(rig: BuiltRig, config: PipelineConfig, available: Set<String>): List<ParameterRole> =
        orderedParameterRoles(rig.authoredParameterRoles + parameterRoles(available, config.rigEdits.idRenames.parameters, blink = false))
            .filter { role -> role.parameters.all { it in available } }

    private fun legacyMouthRest(config: PipelineConfig): Map<String, Float> {
        if (config.rigEdits.importedCmo3 != null) return emptyMap()
        val mouth = StandardParameters.MOUTH_OPEN.raw
        return mapOf((config.rigEdits.idRenames.parameters[mouth] ?: mouth) to 1f)
    }

    private fun parameterRoles(available: Set<String>, renames: Map<String, String>, blink: Boolean): List<ParameterRole> = buildList {
        // An ID conversion changes the ID the role refers to, not the role.
        fun standard(id: String) = renames[id] ?: id
        if (blink) add(ParameterRole("EyeBlink", listOf(standard("ParamEyeLOpen"), standard("ParamEyeROpen"))))
        add(ParameterRole("LipSync", listOf(standard("ParamMouthOpenY"))))
        for ((role, id) in listOf("Breath" to "ParamBreath", "AngleX" to "ParamAngleX", "AngleY" to "ParamAngleY", "AngleZ" to "ParamAngleZ",
                "BodyAngleX" to "ParamBodyAngleX", "EyeBallX" to "ParamEyeBallX", "EyeBallY" to "ParamEyeBallY")) {
            if (standard(id) in available) add(ParameterRole(role, listOf(standard(id))))
        }
    }

    private fun assemble(analysis: PipelineAnalysis, atlas: PackedAtlas, rig: BuiltRig, config: PipelineConfig,
                         base: RigIR, meshOnly: Boolean, tileArt: Boolean, simulations: Boolean): RigIR {
        val puppet = rig.puppet
        return base.copy(
            textures = base.textures.copy(
                pages = atlas.pages.parallelStream().map { TexturePage(it.image.width, it.image.height, Bytes.wrap(it.png)) }.toList(),
                bindings = rig.pageByDrawableId,
                tileArt = if (tileArt) PuppetSourceAtlas.rastersByTile(analysis).map { (tile, raster) ->
                    TileArt(tile.raw, raster.width, raster.height, Bytes.wrap(raster.rgba))
                } else if (config.rigEdits.modifierStack != null) rig.modifierRasterAssets else emptyList(),
            ),
            advanced = advanced(rig, config, base, simulations).copy(
                expressions = if (meshOnly) emptyList() else RuntimeExtras.expressions(config.rigEdits, parameterIds(rig)),
                hitAreas = RuntimeExtras.hitAreas(puppet, analysis, rig.layerIdByDrawableId),
                pose = if (meshOnly) null else RuntimeExtras.pose(config.rigEdits, puppet.parts.mapTo(HashSet()) { it.id.raw }),
            ),
            hitAreas = RigHitAreas.toIr(config.rigEdits.hitAreas),
        )
    }

    private fun modifierSimulations(rig: BuiltRig, config: PipelineConfig, output: RigIR, advanced: AdvancedIR): AdvancedIR {
        val stack = RigModifierStack.fromJson(requireNotNull(config.rigEdits.modifierStack))
        val active = rig.modifierReport?.get("entries")?.jsonArray.orEmpty().map { it.jsonObject }
            .filter { it.getValue("status").jsonPrimitive.content == "active" }
            .mapTo(HashSet()) { it.getValue("id").jsonPrimitive.content }
        val parameters = output.parameters.mapTo(HashSet()) { it.id }
        val groups = output.physics.groups.mapTo(HashSet()) { it.id }
        val exported = stack.instances.filter { it.type == "simulation" && it.id in active }.map { instance ->
            val source = RigSimEdit.fromJson(instance.config)
            val edit = RigModifierAlgorithms.simulationOutputs(instance, source)
            val aliases = requireNotNull(source.bake).pendulums.mapIndexedNotNull { index, pendulum ->
                val outputId = instance.outputId("physics:$index")
                if (outputId in groups) pendulum.id to outputId else null
            }.toMap()
            val scene = SimExport.export(rig.puppet, listOf(edit.copy(id = instance.outputId("simulation"))), parameters, aliases.keys)
            scene.simulations.map { simulation -> simulation.copy(physicsGroups = simulation.physicsGroups.map(aliases::getValue)) } to scene.colliders
        }
        return advanced.copy(simulations = exported.flatMap { it.first }, colliders = exported.flatMap { it.second })
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
	private fun clips(rig: BuiltRig, config: PipelineConfig, physicsGroups: List<RigPhysicsEdit>, parameterIds: Set<String>,
                      generatePresets: Boolean = true, meshOnly: Boolean = config.meshOnly): List<Clip> {
		if (!config.exportMotions || meshOnly) return emptyList()
		val physicsDriven = if (generatePresets && config.exportIncludePhysics) physicsGroups.flatMapTo(HashSet()) { it.outputParameters } else emptySet()
        val switches = if (generatePresets) listOf(config.motionBasic, config.motionIdle, config.motionBlink, config.motionNod,
            config.motionShake, config.motionSkeleton) else emptyList()
		val key = ClipsKey(config.rigEdits.motionClips, rig.puppet.parameters, config.rigEdits.skeleton, config.rigEdits.motionPresets,
			switches, physicsDriven, config.rigEdits.idRenames.parameters, generatePresets && MotionPresetSettings.natural(config))
		synchronized(clipCache) { clipCache[key] }?.let { return it }
		return compileClips(rig, config, physicsDriven, parameterIds, generatePresets).also { synchronized(clipCache) { clipCache[key] = it } }
	}

	/** Everything [compileClips] reads besides the export switches checked before it. */
	private data class ClipsKey(
		val clips: List<MotionClip>, val parameters: List<org.umamo.runtime.model.Parameter>, val skeleton: SkeletonSpec?,
		val presets: Map<String, MotionPresetSettings>, val switches: List<Boolean>, val physicsDriven: Set<String>,
		val renames: Map<String, String>, val natural: Boolean,
	)

	/**
	 * The last few compiled clip lists by their inputs. Every rebuild compiles them, and hashing the skeleton for
	 * each preset's generated-track lookup costs more than the rest of the IR; a geometry edit changes none of
	 * the inputs, and the clips are a pure function of the key.
	 */
	private val clipCache = object : LinkedHashMap<ClipsKey, List<Clip>>(8, 0.75f, true) {
		override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ClipsKey, List<Clip>>?) = size > 4
	}

    internal fun legacyMotionTracks(config: PipelineConfig, name: String, excluded: Set<String>): List<MotionTrack> {
        val renames = config.rigEdits.idRenames.parameters
        val original = renames.entries.associate { it.value to it.key }
        val native = MotionPresets.tracks(name, config.rigEdits.skeleton, MotionPresetSettings.of(config, name),
            excluded.mapTo(HashSet()) { original[it] ?: it })
        return MotionPresets.renamed(native, renames)
    }

	private fun compileClips(rig: BuiltRig, config: PipelineConfig, physicsDriven: Set<String>, parameterIds: Set<String>,
                             generatePresets: Boolean): List<Clip> {
		val clips = MotionClips.reconcileParameters(config.rigEdits.motionClips, rig.puppet.parameters)
		val result = ArrayList<Clip>()
		fun add(clip: Clip?) {
			if (clip != null && clip.curves.any { it.parameter in parameterIds }) result += clip.copy(curves = clip.curves.filter { it.parameter in parameterIds })
		}
		fun authored(group: String, file: String, clip: MotionClip) = Clip(clip.id, clip.name, group, file, clip.duration, clip.fps, clip.loop,
			clip.fadeIn.takeUnless { clip.runtimeDefaultFades }, clip.fadeOut.takeUnless { clip.runtimeDefaultFades },
			clip.curves.map(MotionGenerator::curve), clip.events.map { ClipEvent(it.time, it.value) })
		fun builtin(name: String, exclude: Set<String> = emptySet()) {
			val settings = MotionPresetSettings.of(config, name)
			if (settings.deleted) return
			val group = MotionPresets.exportGroup(name)
			val file = MotionPresets.exportFileStem(name)
			val override = MotionClips.overrideOf(clips, name)
			if (override != null) return add(authored(group, file, override))
			// Generated tracks name the standard parameters by their standard IDs.
			val tracks = legacyMotionTracks(config, name, exclude)
			if (tracks.isEmpty()) return
			add(Clip(name, name, group, file, MotionPresets.duration(name, tracks, settings), 30f, MotionPresets.loops(name),
				curves = tracks.map(MotionGenerator::curve)))
		}
		if (generatePresets && config.motionBasic) {
			// Parameters the exported physics drives stay out of the idle.
			if (config.motionIdle) builtin("Idle", physicsDriven)
			if (config.motionBlink) builtin("Blink")
			if (config.motionNod) builtin("Nod")
			if (config.motionShake) builtin("Shake")
			// Speech, for hosts and players without lip sync; only the `motion/natural` way makes it.
			if (MotionPresetSettings.natural(config)) builtin("Talk")
		}
		if (generatePresets && config.motionSkeleton) {
			for (preset in SkeletonMotions.presets) {
				if (config.rigEdits.motionPresets[preset.name]?.disabled == true) continue
				// A looping preset is another idle, played from the idle group beside the plain one.
				builtin(preset.name)
			}
		}
		// The user's own motions: a loop joins the idles, a one-shot is its own group under its name.
		val stems = MotionClips.exportStems(clips, result.mapTo(HashSet()) { it.file })
		for (clip in clips.filter { it.builtin == null && it.enabled })
			add(authored(clip.exportGroup ?: if (clip.loop) "Idle" else clip.name, stems.getValue(clip.id), clip))
		return result
	}
}
