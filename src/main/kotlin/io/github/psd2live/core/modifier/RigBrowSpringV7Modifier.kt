package io.github.psd2live.core.modifier

import io.github.psd2live.algorithms.binding.BrowSpringV7Algorithm
import io.github.psd2live.algorithms.binding.Side
import io.github.psd2live.core.RigBuilder
import io.github.psd2live.core.SemanticTag
import io.github.psd2live.core.recipe.RigModules
import io.github.psd2live.format.compile.RigIrBinary
import io.github.psd2live.format.model.Canvas
import io.github.psd2live.format.model.RigIR
import io.github.psd2live.i18n.tr
import io.github.psd2live.targets.cubism.PuppetIr
import kotlinx.serialization.json.*
import org.umamo.runtime.model.DeformerId

/** Source measurements only: no generated brow motion, lowering, or before/after transition. */
internal object RigBrowSpringV7Modifier {
    private val algorithm = object : ReadingModifierAlgorithmPlugin by BrowSpringV7Algorithm(),
        ModifierOptionalUpstream, ModifierVersionSelection {
        override val optionalUpstream = BrowSpringV7Algorithm.observed - BrowSpringV7Algorithm.requires
        override val canonicalOptInType = BrowSpringV7Algorithm.TYPE
    }

    fun definition(source: RigModifierSource?): RigModifierDefinition {
        val captures = HashMap<RigModifierInstance, ModifierAlgorithmContext>()
        fun capture(instance: RigModifierInstance) = captures.getOrPut(instance) { requireNotNull(source).capture(instance) }
        return RigLegacyBindings.definition(RigLegacyBindings.spec(BrowSpringV7Algorithm.TYPE), source).copy(
            version = BrowSpringV7Algorithm.VERSION, plugin = algorithm, requires = BrowSpringV7Algorithm.requires,
            providers = { source?.providers(it) ?: RigLegacyBindings.providers(it) },
            parameters = { BrowSpringV7Algorithm.parameters(capture(it)) }, resolveParameter = { _, _, _ -> null },
            changeWrites = BrowSpringV7Algorithm.writes, upstreamInputs = true, authorContext = ::capture,
        )
    }

    /** Resolve required versions before the evaluator invokes either capture or parameter declarations. */
    private fun RigModifierSource.providers(instance: RigModifierInstance): Map<String, String> {
        val prefix = activeBefore(instance)
        val selected = RigLegacyBindings.providers(instance)
        val scaffold = instance.config.getValue("scaffold")
        val base = instance.config.getValue("base")
        val versions = mapOf("rig.scaffold" to setOf(2), "face/fitted" to setOf(3), "eye/fitted" to setOf(5),
            "brow/fitted" to setOf(4), "brow.shape" to setOf(6), "brow.lid-coupling" to setOf(8), "brow.blink" to setOf(1, 6))
        val resolved = versions.mapValues { (type, supportedVersions) ->
            prefix.filter { it.type == type && it.targets.any(instance.targets::contains) &&
                (type == "rig.scaffold" || it.config["scaffold"] == scaffold) }.singleOrNull()?.takeIf {
                it.version in supportedVersions && it.targets.containsAll(instance.targets) &&
                    (selected[type] == null || it.id == selected[type]) &&
                    (type !in setOf("brow.shape", "brow.lid-coupling", "brow.blink") || it.config["base"] == base)
            }
        }.toMutableMap()
        if (resolved["brow.lid-coupling"]?.config?.get("dependencies")?.jsonObject?.get("eye/fitted") !=
            resolved["eye/fitted"]?.id?.let(::JsonPrimitive)) resolved["brow.lid-coupling"] = null
        return resolved.mapValues { (type, provider) -> provider?.id ?: generateSequence("${instance.id}/missing:$type") { "$it?" }
            .first { unavailable -> prefix.none { it.id == unavailable } } }
    }

    private fun RigModifierSource.capture(instance: RigModifierInstance): ModifierAlgorithmContext {
        val scaffold = provider(instance)
        require(scaffold.version == 2 && scaffold.targets.toSet() == instance.targets.toSet()) {
            "Brow spring v7 requires the complete shared v2 scaffold"
        }
        val prefix = activeBefore(instance).filter { entry ->
            entry.config["scaffold"] == JsonPrimitive(scaffold.id) && entry.targets.any(instance.targets::contains) &&
                entry.type in BrowSpringV7Algorithm.observed
        }
        require(prefix.none { it.type in BrowSpringV7Algorithm.unsupported }) {
            "Brow spring v7 does not support preceding spring, cheek or separate depth turn"
        }
        fun required(type: String, vararg versions: Int) = requireNotNull(prefix.singleOrNull { it.type == type }) {
            "Brow spring v7 requires one $type@${versions.joinToString("/")} provider"
        }.also {
            require(it.version in versions && it.targets.containsAll(instance.targets)) {
                "Brow spring v7 requires $type@${versions.joinToString("/")} on the same full target scope"
            }
        }
        val face = required("face/fitted", 3)
        val eye = required("eye/fitted", 5)
        val brow = required("brow/fitted", 4)
        val shape = required("brow.shape", 6)
        val lid = required("brow.lid-coupling", 8)
        val blink = required("brow.blink", 1, 6)
        val base = instance.config.getValue("base")
        require(base == JsonPrimitive(brow.id) && listOf(shape, lid, blink).all { it.config["base"] == base } &&
            lid.config["dependencies"]?.jsonObject?.get("eye/fitted") == JsonPrimitive(eye.id)) {
            "Brow spring v7 requires its configured fitted brow and eye"
        }
        for ((type, version) in BrowSpringV7Algorithm.eyeAddons) {
            val candidates = prefix.filter { it.type == type }
            require(candidates.size <= 1 && candidates.all { it.version == version && it.config["base"] == JsonPrimitive(eye.id) &&
                it.targets.containsAll(instance.targets) }) { "Brow spring v7 requires one direct $type@$version provider" }
        }
        // The candidate and its physics settings never enter the measurement configuration.
        val (native, measured) = RigFittedMouthModuleSource.context(this, scaffold, prefix, null)
        require(measured.analysis.layers.none { it.semantic.tag == SemanticTag.MOUTH_CLOSE && it.opaquePixels > 0 }) {
            "Brow spring v7 does not support a separate closed-mouth drawing"
        }
        require(measured.analysis.layers.any { it.semantic.tag in setOf(SemanticTag.MOUTH, SemanticTag.MOUTH_OPEN) &&
            it.semantic.role == null && it.opaquePixels > 0 }) { "Brow spring v7 requires whole open-mouth artwork" }
        val eyes = measured.fittedEyes(native)?.rigs.orEmpty()
        require(eyes.size == 1 && measured.fittedBrowMeasurements(native).first.keys == eyes.keys &&
            measured.analysis.layers.any { it.semantic.tag == SemanticTag.EYELASH && it.semantic.side == eyes.keys.single() && it.opaquePixels > 0 }) {
            "Brow spring v7 requires exactly one fitted eye with a same-side brow and eyelash"
        }
        val measurements = RigBuilder.browShapeSourceInputs(analysis, atlas, native, meshCache,
            instance.targets.associate { it.id to layersByMesh.getValue(it.id) }, eye.id) { _, parent -> parent }
        require(measurements.targets.size == 1 && measurements.targets.values.single().fit.side == eyes.keys.single() &&
            measurements.targets.values.single().eye != null) { "Brow spring v7 requires one paired source brow" }
        val branch = RigBuilder.browTurnSourceBranch(measured, native, meshCache,
            measurements.targets.values.mapNotNullTo(hashSetOf()) { it.parent?.let(::DeformerId) }).map(PuppetIr::deformerToIr)
        val layers = measured.analysis.layers.associateBy { it.source.id.raw }
        val source = measurements.encode()
        val owners = listOf(scaffold) + prefix
        val names = listOf(Side.LEFT, Side.RIGHT).zip(RigModules.BROW_SPRING.defines.map { tr(it.nameKey) }).toMap()
        return ModifierAlgorithmContext(JsonObject(source.values + buildJsonObject {
            put("providers", JsonArray(owners.map { owner -> Json.encodeToJsonElement(ModifierUpstreamCapability.serializer(),
                ModifierUpstreamCapability(owner.type, owner.type, owner.version, owner.targets, owner.id, owner.config, owner.outputAliases, owner.plugin)) }))
            putJsonObject("head_turn") {
                put("provider", face.id)
                put("shell", JsonArray(RigBuilder.headTurnMeasurements(measured.faceRig, native).map(::JsonPrimitive)))
            }
            put("native_ids", JsonObject(measurements.targets.keys.associateWith { JsonPrimitive(primaryByLayer.getValue(layersByMesh.getValue(it))) }))
            put("mesh_names", JsonObject(measurements.targets.keys.associateWith { JsonPrimitive(layers.getValue(layersByMesh.getValue(it)).source.name) }))
            put("turn_name", tr("model.deformer.meshTurn", "{meshName}"))
            put("shape_name", tr("model.deformer.meshShape", "{meshName}"))
            put("spring_names", JsonObject(names.mapKeys { it.key.name }.mapValues { JsonPrimitive(it.value) }))
            put("physics_names", JsonObject(listOf(Side.LEFT to "L", Side.RIGHT to "R").associate { (side, suffix) ->
                side.name to JsonPrimitive(tr("model.physics.browSpring", suffix)) }))
            put("physics_group", tr("model.group.physics"))
        }), source.resources + ("source_branch" to ModifierAlgorithmResource(RigIrBinary.encode(
            RigIR(Canvas(1f, 1f), emptyList(), deformers = branch)))))
    }
}
