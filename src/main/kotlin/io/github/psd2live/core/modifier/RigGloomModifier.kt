package io.github.psd2live.core.modifier

import io.github.psd2live.algorithms.binding.EyeHighlightAlgorithm
import io.github.psd2live.algorithms.binding.GloomBindingAlgorithm
import io.github.psd2live.algorithms.binding.GloomCarrierAlgorithm
import io.github.psd2live.algorithms.binding.ScaffoldBinding
import io.github.psd2live.core.RigBuilder
import io.github.psd2live.format.compile.RigIrBinary
import io.github.psd2live.format.model.Canvas
import io.github.psd2live.format.model.RigIR
import io.github.psd2live.i18n.tr
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.umamo.runtime.model.Drawable
import io.github.psd2live.core.PartRole
import io.github.psd2live.core.SemanticTag
import io.github.psd2live.core.RigTuning
import io.github.psd2live.core.recipe.RecipeSubject
import io.github.psd2live.core.recipe.RigModules
import io.github.psd2live.targets.cubism.PuppetIr
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

internal object RigGloomModifier {
    private val algorithm = object : ReadingModifierAlgorithmPlugin by GloomBindingAlgorithm(), ModifierVersionSelection {
        override val canonicalOptInType = GloomBindingAlgorithm.TYPE
    }

    fun definition(source: RigModifierSource?): RigModifierDefinition =
        RigLegacyBindings.definition(RigLegacyBindings.spec(GloomBindingAlgorithm.TYPE), source).copy(
            version = GloomBindingAlgorithm.VERSION,
            exclusiveFamily = GloomBindingAlgorithm.TYPE,
            parameters = { emptyList() },
            resolveParameter = { _, _, _ -> null },
            validate = { _, instance ->
                require(instance.config.keys.all { it in setOf("scaffold", "values", "tuning") } && "scaffold" in instance.config) {
                    "Specify Gloom's scaffold and editable values"
                }
                RigLegacyBindings.values(instance)
                RigLegacyBindings.tuning(instance, RigTuning())
                require(instance.targets.all { it.kind == "mesh" && source?.hasMesh(it.id) == true }) {
                    "Gloom targets must have captured source layers"
                }
            },
            plugin = algorithm,
            changeWrites = GloomBindingAlgorithm.WRITES,
            upstreamInputs = true,
            authorContext = { requireNotNull(source).gloomContext(it) },
        )

    fun carrierDefinition(source: RigModifierSource?): RigModifierDefinition {
        val carrier = object : ReadingModifierAlgorithmPlugin by GloomCarrierAlgorithm(),
            ModifierVersionSelection, ModifierOptionalUpstream {
            override val canonicalOptInType = GloomBindingAlgorithm.TYPE
            override val optionalUpstream = RigLegacyBindings.specs.mapTo(linkedSetOf()) { it.type } -
                setOf(GloomBindingAlgorithm.TYPE, "expression.toggles")
        }
        return definition(source).copy(version = GloomCarrierAlgorithm.VERSION, plugin = carrier,
            changeWrites = GloomCarrierAlgorithm.WRITES, authorContext = { requireNotNull(source).gloomContext(it, true) })
    }

    private fun RigModifierSource.gloomContext(instance: RigModifierInstance, captureCarriers: Boolean = false): ModifierAlgorithmContext {
        // The scaffold may have a shared hierarchy, but Gloom only writes channels on its own targets.
        val scaffoldId = instance.config.getValue("scaffold").jsonPrimitive.content
        val preceding = activeBefore(instance)
        val scaffold = preceding.single { it.id == scaffoldId && it.type == RigSourceModifiers.SCAFFOLD }
        val layers = analysis.layers.associateBy { it.source.id.raw }
        val targets = instance.targets.associate { it.id to layers.getValue(layersByMesh.getValue(it.id)) }
        val scaffoldMeshes = instance.targets.associate { target ->
            val model = scaffoldFor(scaffold, target) {
                if (Thread.currentThread().isInterrupted) throw java.util.concurrent.CancellationException()
            }
            target.id to model.drawables.single { it.id.raw == target.id }
        }
        val previous = preceding.filter { it.config["scaffold"]?.jsonPrimitive?.content == scaffold.id }
        fun contributions(type: String): Map<String, ModifierUpstreamCapability> = buildMap {
            for (entry in previous.filter { it.type == type || it.type == GloomBindingAlgorithm.ENTRYPOINT && type == GloomBindingAlgorithm.TYPE }) {
                val capability = ModifierUpstreamCapability(type, type, entry.version, entry.targets, entry.id, entry.config,
                    entry.outputAliases, entry.plugin)
                for (target in entry.targets) if (target.id in targets) put(target.id, capability)
            }
        }
        val context = RigGloomSource.capture(instance, RigModules.EXPRESSION_GLOOM.applies(RecipeSubject.of(analysis)), targets, scaffoldMeshes.mapValues { PuppetIr.meshToIr(it.value) },
            contributions("expression.cheek"), contributions(GloomBindingAlgorithm.TYPE))
        val eyeHighlights = previous.filter { it.type in setOf(EyeHighlightAlgorithm.TYPE, EyeHighlightAlgorithm.ENTRYPOINT) }
            .flatMap { it.targets }.distinct().mapNotNull { target ->
                val semantic = targets[target.id]?.semantic ?: return@mapNotNull null
                target.id.takeIf { semantic.tag == SemanticTag.IRIDES && semantic.role == PartRole.HIGHLIGHT }
            }
        val base = ModifierAlgorithmContext(JsonObject(context.values +
            ("eye_highlights" to JsonArray(eyeHighlights.map(::JsonPrimitive)))), context.resources)
        return if (captureCarriers) skinContext(instance, scaffold, previous, scaffoldMeshes, base) else base
    }

    private fun RigModifierSource.skinContext(instance: RigModifierInstance, scaffold: RigModifierInstance,
                                              previous: List<RigModifierInstance>, scaffoldMeshes: Map<String, Drawable>,
                                              base: ModifierAlgorithmContext): ModifierAlgorithmContext {
        require(scaffold.version == 2) { "Gloom v3 requires a shared scaffold" }
        val sourceLayers = analysis.layers.associateBy { it.source.id.raw }
        val contexts = HashMap<List<RigModifierInstance>, Pair<io.github.psd2live.core.PipelineConfig, RigBuilder.RigContext>>()
        val source = ArrayList<io.github.psd2live.format.model.Mesh>()
        val measurements = buildJsonObject {
            for (target in instance.targets) {
                val layer = sourceLayers.getValue(layersByMesh.getValue(target.id))
                if (layer.semantic.tag !in setOf(SemanticTag.FACE, SemanticTag.NOSE)) continue
                val preceding = previous.filter {
                    target in it.targets && RigLegacyBindings.find(it.type)?.let { spec -> !spec.expressionToggles && !spec.seeThrough } == true
                }
                require(preceding.none { it.type == GloomBindingAlgorithm.TYPE }) { "Gloom v3 needs an unshaded skin binding" }
                val (native, rig) = contexts.getOrPut(preceding) {
                    RigFittedMouthModuleSource.context(this@skinContext, scaffold, preceding, null)
                }
                val alignedLayer = rig.analysis.layers.single { it.source.id == layer.source.id }
                val skin = RigBuilder.skinLoweringSource(alignedLayer, rig, native, atlas, meshCache, scaffoldMeshes.getValue(target.id))
                val parent = requireNotNull(skin.parentDeformerId) { "Gloom v3 requires a bound skin parent" }
                source += PuppetIr.meshToIr(skin)
                putJsonObject(target.id) {
                    put("providers", JsonArray(preceding.map { JsonPrimitive(it.id) }))
                    put("parents", JsonArray((listOf(scaffold) + preceding).map {
                        JsonPrimitive(ScaffoldBinding.deformerId(it, target.id, parent.raw, shared = true))
                    }.distinct()))
                }
            }
        }
        return ModifierAlgorithmContext(JsonObject(base.values + mapOf(
            "skin" to measurements,
            "turn_name" to JsonPrimitive(tr("model.deformer.meshTurn", "{meshName}")),
            "shape_name" to JsonPrimitive(tr("model.deformer.meshShape", "{meshName}")),
        )), base.resources + ("skin_forms" to ModifierAlgorithmResource(RigIrBinary.encode(
            RigIR(Canvas(1f, 1f), emptyList(), meshes = source)))))
    }
}
