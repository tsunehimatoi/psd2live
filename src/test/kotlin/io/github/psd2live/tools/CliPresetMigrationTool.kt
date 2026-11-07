package io.github.psd2live.tools

import io.github.psd2live.application.WorkspaceInitialPreset
import io.github.psd2live.application.WorkspacePresetInitialization
import io.github.psd2live.application.WorkspacePreviewBuilder
import io.github.psd2live.application.WorkspaceRasterWork
import io.github.psd2live.core.*
import io.github.psd2live.core.modifier.RigModifierChains
import io.github.psd2live.core.modifier.RigModifierInstance
import io.github.psd2live.core.modifier.RigModifierStack
import io.github.psd2live.format.model.RigIR
import io.github.psd2live.project.WorkspaceDocument
import io.github.psd2live.project.WorkspaceSettingsCodec
import kotlinx.coroutines.runBlocking
import org.umamo.runtime.model.DrawableId
import kotlin.test.Test

/** Compare a raw PSD export with a stack that uses names derived from the same source. */
class CliPresetMigrationTool {
    @Test fun compareRawPreset() = runBlocking {
        requireTools()
        val sample = Sample.fromEnvironment()
        val config = PipelineConfig(meshSpacing = 64,
            generatePhysics = setting("PSD2LIVE_CLI_PHYSICS", "1") == "1",
            exportMotions = setting("PSD2LIVE_CLI_MOTIONS", "1") == "1")
        val pipeline = PSD2LivePipeline().apply { deferRuntimeBundles = true }
        val native = pipeline.buildPreview(sample.path, config)
        val source = native.analysis.source
        val overlay = config.rigEdits.copy(modifierStack = RigModifierStack().toJson())
        val document = WorkspaceDocument(source, emptyMap(), emptySet(), emptyMap(), emptyMap(), overlay,
            WorkspaceSettingsCodec.encode(config.copy(rigEdits = overlay)))
        val (initialized, independent) = WorkspacePresetInitialization.prepare(document, WorkspaceRasterWork.Direct,
            WorkspaceInitialPreset(clothing = false))
        val stack = RigModifierStack.fromJson(requireNotNull(initialized.rigEdits.modifierStack))
        val evaluated = requireNotNull(independent.rig.modifierOutput)
        val measured = RigGenerationSource.prepare(RigGenerationSource.analyze(source, config), config).geometry
        val context = RigBuilder.rigContext(measured, config.copy(generateDeformers = false))
        val chains = RigBuilder.hairChains(context.analysis, context.faceRig, config).map(RigModifierChains::Binding)
        val chainWarps = chains.associate { it.warp.raw to it.native.warpId }
        val chainParameters = chains.flatMap { it.parameters.entries }.associate { (nativeId, normalized) ->
            "chain-parameter:${normalized.raw.removePrefix("SourceChain:")}" to nativeId
        }
        val available = StandardParameters.all.mapTo(hashSetOf()) { it.id.raw } + chainParameters.values
        val chainRules = PhysicsGenerator.moduleRules(available).flatMap { rule ->
            chains.filter { chain -> rule.outputs.isNotEmpty() && rule.outputs.all { it.parameter in chain.parameters } }
                .map { chain -> "physics:chain:${chain.key}:${chain.suffix(rule.outputs.first().parameter)}" to rule.id }
        }.toMap()
        val layerByMesh = independent.baseRig.layerIdByDrawableId
        val ids = evaluated.deformers.map { it.id } + evaluated.parts.map { it.id } + evaluated.parameters.map { it.id } +
            evaluated.meshes.map { it.id } + evaluated.physics.groups.map { it.id } + evaluated.clips.map { it.id }
        val renamed = stack.copy(instances = stack.instances.map { instance ->
            val names = ids.mapNotNull(instance::localOutputName).distinct()
            val aliases = names.mapNotNull { local ->
                nativeName(instance, local, chainWarps, chainParameters, chainRules)?.let { local to it }
            }.toMap()
            val rasterAliases = if (instance.type == "mouth.outline") mouthRasters(names, layerByMesh) else emptyMap()
            instance.copy(outputAliases = instance.outputAliases + aliases + rasterAliases)
        })
        val candidate = WorkspacePreviewBuilder().build(initialized.copy(
            rigEdits = initialized.rigEdits.copy(modifierStack = renamed.toJson())))
        val expected = RigIrCompiler.compile(native, tileArt = true)
        val before = RigIrCompiler.compile(independent, tileArt = true)
        val after = RigIrCompiler.compile(candidate, tileArt = true)
        output("cli-preset-migration").resolve("report.txt").writeText(buildString {
            appendLine("Names follow the source measurement. The native build is used only as the comparison baseline.")
            compare("instance IDs", expected, before)
            compare("historical names", expected, after)
        })
    }

    private fun nativeName(instance: RigModifierInstance, local: String, chainWarps: Map<String, String>,
                           chainParameters: Map<String, String>, chainRules: Map<String, String>): String? = when {
        local.startsWith("shared/deformer:") -> local.removePrefix("shared/deformer:").let {
            if (it.startsWith("SourceChain:")) chainWarps[it] else it
        }
        local.startsWith("shared/part:") -> local.removePrefix("shared/part:")
        local.startsWith("chain-parameter:") -> chainParameters[local]
        local.startsWith("physics:chain:") -> chainRules[local]
        local.startsWith("physics:") -> local.removePrefix("physics:")
        local == "physics" && instance.type == "physics" -> RigPhysicsEdit.fromJson(instance.config).id
        local == "motion" && instance.type == "motion" -> MotionClips.fromJson(instance.config).name
        local.startsWith("see-through:") -> SeeThroughLayers.copyId(DrawableId(local.removePrefix("see-through:"))).raw
        local.startsWith("mouth-lip:") -> local.removePrefix("mouth-lip:").let {
            "${it.substringBeforeLast(':')}_lip_${it.substringAfterLast(':')}"
        }
        else -> null
    }

    private fun mouthRasters(names: List<String>, layerByMesh: Map<String, String>): Map<String, String> =
        names.filter { it.startsWith("mouth-lip:") }.mapNotNull { local ->
            val target = local.removePrefix("mouth-lip:")
            val layer = layerByMesh[target.substringBeforeLast(':')] ?: return@mapNotNull null
            val side = target.substringAfterLast(':').toInt()
            "mouth-art:$target" to MouthLipLayer.idFor(layer, side)
        }.toMap()

    private fun StringBuilder.compare(label: String, expected: RigIR, actual: RigIR) {
        val fields = RigIR::class.java.declaredFields.filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .filter { field -> field.isAccessible = true; field.get(expected) != field.get(actual) }.map { it.name }
        appendLine("$label IR differences = $fields")
        fun ids(kind: String, reference: List<String>, observed: List<String>) {
            val missing = reference - observed.toSet()
            val extra = observed - reference.toSet()
            appendLine("  $kind missing=${missing.size} ${missing.take(8)} extra=${extra.size} ${extra.take(8)}")
        }
        ids("parameters", expected.parameters.map { it.id }, actual.parameters.map { it.id })
        ids("meshes", expected.meshes.map { it.id }, actual.meshes.map { it.id })
        ids("deformers", expected.deformers.map { it.id }, actual.deformers.map { it.id })
        ids("parts", expected.parts.map { it.id }, actual.parts.map { it.id })
        ids("physics", expected.physics.groups.map { it.id }, actual.physics.groups.map { it.id })
        ids("motions", expected.clips.map { it.id }, actual.clips.map { it.id })
        val meshes = expected.meshes.associateBy { it.id }
        appendLine("  topology/UV differences = ${actual.meshes.mapNotNull { mesh ->
            val previous = meshes[mesh.id] ?: return@mapNotNull null
            mesh.id.takeIf { previous.geometry?.indices != mesh.geometry?.indices || previous.geometry?.uvs != mesh.geometry?.uvs }
        }}")
    }
}
