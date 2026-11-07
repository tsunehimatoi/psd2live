package io.github.psd2live.core.modifier

import io.github.psd2live.algorithms.binding.NeckAlgorithm
import io.github.psd2live.algorithms.binding.NeckV3Algorithm
import io.github.psd2live.core.PartRole
import io.github.psd2live.core.SemanticTag
import io.github.psd2live.format.compile.RigIrBinary
import io.github.psd2live.format.model.Canvas
import io.github.psd2live.format.model.RigIR
import io.github.psd2live.targets.cubism.PuppetIr
import org.umamo.runtime.model.DrawableId
import io.github.psd2live.core.RigBuilder
import io.github.psd2live.core.RigTuning
import io.github.psd2live.core.recipe.RigRecipe
import io.github.psd2live.project.WorkspaceSettingsCodec
import kotlinx.serialization.json.*

internal object RigNeckModifier {
    private val algorithm = NeckAlgorithm()

    fun definition(source: RigModifierSource?): RigModifierDefinition =
        RigLegacyBindings.definition(RigLegacyBindings.spec(NeckAlgorithm.TYPE), source).copy(
            version = NeckAlgorithm.VERSION,
            parameters = { emptyList() },
            plugin = algorithm,
            changeWrites = setOf("mesh", "relations"),
            upstreamInputs = true,
            authorContext = { requireNotNull(source).neckContext(it) },
        )

    private val foldedAlgorithm = object : ReadingModifierAlgorithmPlugin by NeckV3Algorithm(),
        ModifierOptionalUpstream, ModifierVersionSelection {
        override val canonicalOptInType = NeckAlgorithm.TYPE
        override val optionalUpstream = RigLegacyBindings.specs.filter { !it.expressionToggles && !it.seeThrough }
            .mapTo(linkedSetOf()) { it.type } - NeckAlgorithm.TYPE
    }

    fun foldedDefinition(source: RigModifierSource?): RigModifierDefinition = definition(source).copy(
        version = NeckV3Algorithm.VERSION, plugin = foldedAlgorithm,
        authorContext = { requireNotNull(source).captureFolded(it) },
    )

    private fun RigModifierSource.captureFolded(instance: RigModifierInstance): ModifierAlgorithmContext {
        val scaffold = provider(instance)
        val prefix = activeBefore(instance).filter { entry ->
            entry.config["scaffold"] == JsonPrimitive(scaffold.id) && entry.targets.any(instance.targets::contains) &&
                RigLegacyBindings.find(entry.type)?.let { !it.expressionToggles && !it.seeThrough } == true
        }
        val neckLayers = analysis.layers.filter { it.semantic.tag == SemanticTag.NECK && it.opaquePixels > 0 }
        val layers = neckLayers.associateBy { it.source.id.raw }
        val hasFace = analysis.layers.any { it.semantic.tag == SemanticTag.FACE && it.opaquePixels > 0 }
        val selected = instance.targets.filter { hasFace && layersByMesh.getValue(it.id) in layers &&
            layersByMesh.getValue(it.id) in atlas.placementByLayerId &&
            prefix.none { entry -> entry.type == NeckAlgorithm.TYPE && it in entry.targets } }
        val canonical = selected.associate { target ->
            val layer = layersByMesh.getValue(target.id)
            layer to DrawableId(primaryByLayer.getValue(layer))
        }
        fun phase(entries: List<RigModifierInstance>): ModifierAlgorithmResource {
            val (native, _) = RigFittedMouthModuleSource.context(this, scaffold, entries, null)
            val (model, _) = RigBuilder.attachmentBindingSource(analysis, atlas, native, meshCache, canonical)
            val meshes = model.drawables.associateBy { it.id }
            val byId = model.deformers.associateBy { it.id }
            val branch = model.drawables.flatMap { mesh ->
                generateSequence(mesh.parentDeformerId) { byId.getValue(it).parent }.toList()
            }.toSet()
            return ModifierAlgorithmResource(RigIrBinary.encode(RigIR(Canvas(1f, 1f), emptyList(),
                meshes = selected.map { target ->
                    val mesh = meshes.getValue(canonical.getValue(layersByMesh.getValue(target.id)))
                    PuppetIr.meshToIr(mesh.copy(id = DrawableId(target.id)))
                },
                deformers = model.deformers.filter { it.id in branch }.map(PuppetIr::deformerToIr))))
        }
        return ModifierAlgorithmContext(buildJsonObject {
            putJsonArray("masks") {
                neckLayers.filter { it.semantic.role == null && it.source.id.raw in atlas.placementByLayerId }
                    .sortedBy { it.source.order }.forEach { add(primaryByLayer.getValue(it.source.id.raw)) }
            }
            putJsonArray("shadows") {
                selected.filter { layers.getValue(layersByMesh.getValue(it.id)).semantic.role == PartRole.SHADOW }.forEach { add(it.id) }
            }
        }, mapOf("before" to phase(prefix), "after" to phase(prefix + instance)))
    }

    private fun RigModifierSource.neckContext(instance: RigModifierInstance): ModifierAlgorithmContext {
        val scaffold = provider(instance)
        val corrections = WorkspaceSettingsCodec.decodeRecipe(scaffold.config["corrections"])
        val native = sourceConfig().copy(recipe = RigRecipe.Legacy.copy(moves = corrections.moves, eyeParts = corrections.eyeParts),
            rigTuning = RigTuning(), bodyStrength = 1f, headTurnStrength = 1f)
        val earlierNeckTargets = activeBefore(instance).asSequence()
            .filter { (it.type == NeckAlgorithm.TYPE || it.type == NeckAlgorithm.ENTRYPOINT) &&
                it.config["scaffold"]?.jsonPrimitive?.content == scaffold.id }
            .flatMap { it.targets.asSequence() }.toSet()
        val targets = instance.targets.filterNot(earlierNeckTargets::contains).associate { it.id to layersByMesh.getValue(it.id) }
        return RigBuilder.neckSourceInputs(analysis, atlas, native, meshCache, targets, primaryByLayer) { mesh, parent ->
            RigSourceModifiers.deformerId(scaffold, mesh, parent)
        }
    }
}
