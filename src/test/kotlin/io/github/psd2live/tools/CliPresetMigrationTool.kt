package io.github.psd2live.tools

import io.github.psd2live.algorithms.binding.ScaffoldParts
import io.github.psd2live.application.WorkspaceInitialPreset
import io.github.psd2live.application.WorkspacePresetInitialization
import io.github.psd2live.application.WorkspacePreviewBuilder
import io.github.psd2live.application.WorkspaceRasterWork
import io.github.psd2live.core.*
import io.github.psd2live.core.modifier.RigModifierChains
import io.github.psd2live.core.modifier.RigModifierInstance
import io.github.psd2live.core.modifier.RigModifierStack
import io.github.psd2live.core.recipe.RecipeSubject
import io.github.psd2live.core.recipe.RigModules
import io.github.psd2live.format.model.Mesh as RigIRMesh
import io.github.psd2live.format.model.RigIR
import io.github.psd2live.project.WorkspaceDocument
import io.github.psd2live.project.WorkspaceSettingsCodec
import kotlinx.coroutines.runBlocking
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.ParameterId
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
        val sharedParts = ScaffoldParts.build(emptyList()) { it }.parts.associate { "shared/part:${it.id.raw}" to it.id.raw }
        val ids = evaluated.deformers.map { it.id } + evaluated.parts.map { it.id } + evaluated.parameters.map { it.id } +
            evaluated.meshes.map { it.id } + evaluated.physics.groups.map { it.id } + evaluated.clips.map { it.id } + evaluated.authoring.paths.map { it.id }
        val renamed = stack.copy(instances = stack.instances.map { instance ->
            val names = ids.mapNotNull(instance::localOutputName).distinct()
            val aliases = names.mapNotNull { local ->
                nativeName(instance, local, chainWarps, chainParameters, chainRules)?.let { local to it }
            }.toMap()
            val rasterAliases = if (instance.type == "mouth.outline") mouthRasters(names, layerByMesh) else emptyMap()
            val partAliases = if (instance.type == "rig.scaffold") sharedParts else emptyMap()
            val export = if (instance.type == "motion") {
                val clip = MotionClips.fromJson(instance.config)
                MotionClips.toJson(clip.copy(fileStem = MotionPresets.exportFileStem(clip.name),
                    exportGroup = MotionPresets.exportGroup(clip.name), runtimeDefaultFades = true))
            } else instance.config
            instance.copy(config = export, outputAliases = instance.outputAliases + aliases + rasterAliases + partAliases)
        })
        val candidate = WorkspacePreviewBuilder().build(initialized.copy(
            rigEdits = initialized.rigEdits.copy(modifierStack = rawPsdOrder(renamed).toJson())))
        val expected = RigIrCompiler.compile(native, tileArt = true)
        val before = RigIrCompiler.compile(independent, tileArt = true)
        val after = RigIrCompiler.compile(candidate, tileArt = true)
        // Keep the evaluated geometry and generated pixels, then try the raw export's shared atlas layout.
        val repackedAtlas = AtlasLayout.pack(candidate.analysis.layers, config)
        val repacked = RigLayerDeletion.rebind(candidate.rig, candidate.analysis, candidate.atlas,
            candidate.analysis, repackedAtlas)
        output("cli-preset-migration").resolve("report.txt").writeText(buildString {
            appendLine("Names follow the source measurement. The native build is used only as the comparison baseline.")
            compare("instance IDs", expected, before)
            compare("historical names", expected, after)
            if (repacked != null) {
                appendLine("  shared atlas placement differences = " +
                    repackedAtlas.placementByLayerId.keys.filter { repackedAtlas.placementByLayerId[it] != native.atlas.placementByLayerId[it] })
                val packed = candidate.copy(rig = repacked, atlas = repackedAtlas)
                compare("shared atlas", expected, RigIrCompiler.compile(packed, tileArt = true))
                val moduleIds = RigModules.parameters(config, RecipeSubject.of(measured)).map { ParameterId(it.id) } +
                    chains.flatMap { it.native.parameters }
                val ordered = orderExportMetadata(packed, moduleIds, config)
                compare("shared export layout", expected, RigIrCompiler.compile(ordered, tileArt = true))
            } else appendLine("Shared atlas comparison unavailable: a mesh has no matching source tile.")
        })
    }

    private fun rawPsdOrder(stack: RigModifierStack): RigModifierStack {
        val outline = stack.instances.singleOrNull { it.type == "mouth.outline" && it.version == 7 } ?: return stack
        val postponed = stack.instances.take(stack.index(outline.id)).filter {
            it.type in setOf("hair/chains", "eye.see-through", "brow.see-through")
        }
        if (postponed.isEmpty()) return stack
        return stack.copy(instances = stack.instances.filterNot { it in postponed }.toMutableList().apply {
            addAll(indexOf(outline) + 1, postponed)
        })
    }

    private fun orderExportMetadata(preview: RigPreviewModel, moduleIds: List<ParameterId>, config: PipelineConfig): RigPreviewModel {
        val rig = preview.rig
        val puppet = rig.puppet
        val parameters = puppet.parameters.associateBy { it.id }
        val modules = moduleIds.distinct().filter { it in parameters }
        val standard = StandardParameters.all.map { it.id }
        val preset = standard + modules
        val custom = parameters.keys.filterNot { it in preset }
        val output = requireNotNull(rig.modifierOutput)
        // Compare the historical evaluation order. The generated values remain the plugin outputs.
        val physicsOrder = PhysicsCatalog.groups(preview.analysis, config, parameters.keys.mapTo(HashSet()) { it.raw })
            .map { it.id }
        val ordered = puppet.copy(parameters = (preset + custom).mapNotNull(parameters::get),
            parameterTree = StandardParameters.tree(custom, modules, parameters.keys), parameterLinks = StandardParameters.links(preset.toSet()))
        return preview.copy(rig = rig.copy(puppet = ordered, modifierOutput = output.copy(
            physics = output.physics.copy(groups = PhysicsCatalog.ordered(output.physics.groups, physicsOrder) { it.id }))))
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
        local.startsWith("mouth-path:") -> local.substringAfterLast(':')
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
        fun <T> records(kind: String, reference: List<T>, observed: List<T>, id: (T) -> String) {
            val before = reference.associateBy(id)
            val after = observed.associateBy(id)
            val missing = before.keys - after.keys
            val extra = after.keys - before.keys
            val common = before.keys.intersect(after.keys)
            val changed = common.filter { before.getValue(it) != after.getValue(it) }
            val reordered = before.keys.filter { it in common } != after.keys.filter { it in common }
            appendLine("  $kind missing=${missing.size} ${missing.take(8)} extra=${extra.size} ${extra.take(8)} " +
                "changed=${changed.size} ${changed.take(12)} reordered=$reordered")
        }
        records("parameters", expected.parameters, actual.parameters) { it.id }
        records("meshes", expected.meshes, actual.meshes) { it.id }
        records("deformers", expected.deformers, actual.deformers) { it.id }
        records("parts", expected.parts, actual.parts) { it.id }
        records("physics", expected.physics.groups, actual.physics.groups) { it.id }
        records("motions", expected.clips, actual.clips) { it.id }
        records("tiles", expected.textures.tiles, actual.textures.tiles) { it.id }
        records("paths", expected.authoring.paths, actual.authoring.paths) { it.id }
        appendLine("  texture pages match = ${expected.textures.pages == actual.textures.pages}")
        appendLine("  parameter links reordered only = ${expected.parameterLinks != actual.parameterLinks &&
            expected.parameterLinks.toSet() == actual.parameterLinks.toSet()}")
        val meshes = expected.meshes.associateBy { it.id }
        fun <T> meshDifferences(kind: String, value: (RigIRMesh) -> T) {
            val changed = actual.meshes.mapNotNull { mesh ->
                val previous = meshes[mesh.id] ?: return@mapNotNull null
                mesh.id.takeIf { value(previous) != value(mesh) }
            }
            appendLine("  mesh $kind differences = $changed")
        }
        meshDifferences("indices") { it.geometry?.indices }
        meshDifferences("UVs") { it.geometry?.uvs }
        meshDifferences("positions") { it.geometry?.positions }
        meshDifferences("deformer assignment") { it.parent }
        meshDifferences("texture assignment") { Triple(it.textureSource, it.page, it.tile) }
        meshDifferences("draw order") { it.drawOrder }
        meshDifferences("mask") { it.maskedBy to it.invertMask }
        meshDifferences("keyforms") { it.offsets }
        meshDifferences("channels") { it.channels }
        meshDifferences("blend shapes") { it.shapes }
        if (label == "shared export layout") {
            for ((before, after) in expected.parameterTree.zip(actual.parameterTree)) {
                if (before != after) appendLine("  parameter folder: expected=$before actual=$after")
            }
            for (part in expected.parts) {
                val observed = actual.parts.singleOrNull { it.id == part.id } ?: continue
                if (part.children != observed.children)
                    appendLine("  part ${part.id}: expected=${part.children} actual=${observed.children}")
            }
            val paths = actual.authoring.paths.mapTo(HashSet()) { it.id }
            appendLine("  missing paths by mesh = ${expected.authoring.paths.filterNot { it.id in paths }.groupingBy { it.mesh }.eachCount()}")
        }
        val tiles = expected.textures.tileArt.associateBy { it.tile }
        val pixels = actual.textures.tileArt.filter { it.tile in tiles && tiles[it.tile] != it }.map { it.tile }
        appendLine("  tile pixel differences = $pixels")
    }
}
