package io.github.psd2live.application

import io.github.psd2live.algorithms.binding.MouthOutlineOwner
import io.github.psd2live.algorithms.binding.MouthOutlineV7Algorithm
import io.github.psd2live.algorithms.binding.ScaffoldParts
import io.github.psd2live.core.*
import io.github.psd2live.core.modifier.RigLegacyBindings
import io.github.psd2live.core.modifier.RigModifierChains
import io.github.psd2live.core.modifier.RigModifierStack
import io.github.psd2live.core.recipe.RecipeSubject
import io.github.psd2live.core.recipe.RigModules
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.ParameterId

/** Raw PSD export keeps its public IDs when a preset is evaluated as explicit modifiers. */
internal class CliPresetBindings(private val source: RigGenerationSource.Analyses, private val config: PipelineConfig) {
    private val context by lazy { RigBuilder.rigContext(source.geometry, config) }
    private val chains by lazy {
        RigBuilder.hairChains(context.analysis, context.faceRig, config).map(RigModifierChains::Binding)
    }

    val moduleParameters: List<ParameterId> get() =
        RigModules.parameters(config, RecipeSubject.of(source.geometry)).map { ParameterId(it.id) } +
            chains.flatMap { it.native.parameters }

    fun bindings(model: RigPreviewModel, stack: RigModifierStack): RigModifierStack {
        val hierarchy = RigBuilder.rigContext(source.geometry, config, neutralScaffold = true).deformers
            .associate { "shared/deformer:${it.id.raw}" to it.id.raw }
        val parts = ScaffoldParts.build(emptyList()) { it }.parts.associate { "shared/part:${it.id.raw}" to it.id.raw }
        val lips = source.geometry.layers.mapNotNull { it.source as? MouthLipLayer }.groupBy { it.ownerId }
        val layerByMesh = model.baseRig.ownLayerIdByDrawableId
        val available = StandardParameters.all.mapTo(HashSet()) { it.id.raw } + chains.flatMap { it.parameters.keys }
        val rules = PhysicsGenerator.moduleRules(available)
        val named = stack.copy(instances = stack.instances.map { instance ->
            val aliases = buildMap {
                if (instance.type == "rig.scaffold") {
                    putAll(hierarchy)
                    putAll(parts)
                }
                val spec = RigLegacyBindings.find(instance.type)
                if (spec?.mouthOutline == true) for (target in instance.targets) {
                    for (lip in lips[layerByMesh[target.id]].orEmpty()) {
                        val mesh = "${target.id}_lip_${lip.side}"
                        val slot = "${target.id}:${lip.side}"
                        val path = MouthOutlineOwner.pathId(DrawableId(mesh), "lip")
                        put("mouth-lip:$slot", mesh)
                        put("mouth-art:$slot", lip.id.raw)
                        put("mouth-path:$slot:$path", path)
                    }
                }
                if (spec?.seeThrough == true) for (target in instance.targets) {
                    put("see-through:${target.id}", SeeThroughLayers.copyId(DrawableId(target.id)).raw)
                }
                for (group in spec?.module?.physicsGroups.orEmpty()) put("physics:$group", group)
                for (chain in chains.filter { it.native.kind in RigModifierChains.kinds(instance.type) }) {
                    put("shared/deformer:${chain.warp.raw}", chain.native.warpId)
                    for ((native, normalized) in chain.parameters) {
                        put("chain-parameter:${normalized.raw.removePrefix("SourceChain:")}", native)
                    }
                    for (rule in rules.filter { it.outputs.isNotEmpty() && it.outputs.all { output -> output.parameter in chain.parameters } }) {
                        put("physics:chain:${chain.key}:${chain.suffix(rule.outputs.first().parameter)}", rule.id)
                    }
                }
            }
            instance.copy(outputAliases = instance.outputAliases + aliases)
        })
        val outline = named.instances.singleOrNull {
            it.type == RigModules.MOUTH_OUTLINE.id && it.version == MouthOutlineV7Algorithm.VERSION
        } ?: return named
        val postponed = named.instances.take(named.index(outline.id)).filter {
            it.type in setOf("hair/chains", "eye.see-through", "brow.see-through")
        }
        if (postponed.isEmpty()) return named
        return named.copy(instances = named.instances.filterNot { it in postponed }.toMutableList().apply {
            addAll(indexOf(outline) + 1, postponed)
        })
    }

    fun motion(clip: MotionClip): MotionClip = clip.copy(fileStem = MotionPresets.exportFileStem(clip.name),
        exportGroup = MotionPresets.exportGroup(clip.name), runtimeDefaultFades = true)
}
