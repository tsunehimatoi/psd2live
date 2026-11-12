package io.github.psd2live.algorithms.binding

import io.github.psd2live.algorithm.worker.AlgorithmWorker
import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.model.Parameter
import io.github.psd2live.format.model.RigIR
import io.github.psd2live.targets.cubism.PuppetIr
import kotlinx.serialization.json.*
import org.umamo.edit.withParametersSyncedFromTree
import org.umamo.runtime.model.*

/** Reconstructs both source phases independently, then migrates changed shared-frame descendants. */
object BrowFittedV5Binding : ChangingModifierAlgorithmPlugin {
    const val VERSION = 5
    const val ENTRYPOINT = "builtin.62726f772f666974746564.v5/binding"

    override fun changes(input: RigIR, instance: RigModifierInstance, context: ModifierAlgorithmContext,
                         checkpoint: () -> Unit): ModifierModelChanges {
        require(instance.version == VERSION && instance.targets.isNotEmpty() &&
            instance.targets.all { it.kind == "mesh" } && instance.targets.distinct().size == instance.targets.size)
        require(context.values.keys == fields && context.values.getValue("schema") == JsonPrimitive(SCHEMA)) {
            "Fitted brow v5 accepts source observations, not generated binding projections"
        }
        val captured = context.values.getValue("providers").jsonArray.map {
            Json.decodeFromJsonElement(ModifierUpstreamCapability.serializer(), it)
        }
        val actual = context.upstream.map { it.copy(capability = it.type) }.distinct()
        require(actual.toSet() == captured.toSet()) {
            "Fitted brow v5 requires its exact captured providers, aliases and packages"
        }
        validateProviders(captured, instance)
        val packages = context.values.getValue("provider_packages").jsonObject.mapValues {
            Json.decodeFromJsonElement(ModifierPluginReference.serializer(), it.value)
        }
        require(packages.keys == captured.mapTo(hashSetOf()) { requireNotNull(it.instanceId) })
        for (owner in captured) {
            val expected = packages.getValue(requireNotNull(owner.instanceId))
            val namespace = "builtin." + java.util.HexFormat.of().formatHex(owner.type.encodeToByteArray()) + ".v${owner.version}"
            require(expected.id == namespace && owner.plugin == expected) {
                "Fitted brow v5 requires its captured built-in package contract"
            }
        }
        val upstream = captured.map { owner -> RigModifierInstance(requireNotNull(owner.instanceId), owner.type,
            owner.version, owner.targets, config = owner.config, outputAliases = owner.outputAliases, plugin = owner.plugin) }
        val scaffoldId = instance.config.getValue("scaffold").jsonPrimitive.content
        val scaffold = upstream.single { it.id == scaffoldId }
        val sourceResources = context.resources - setOf("before_meshes", "after_meshes")
        val source = ModifierSourceInputs.from(ModifierAlgorithmContext(context.values.getValue("source").jsonObject, sourceResources))
        require(source.targets.keys == instance.targets.mapTo(hashSetOf()) { it.id } &&
            source.targets.values.toSet().size == source.targets.size &&
            source.targets.values.toSet() == source.layers.mapTo(hashSetOf()) { it.id })
        fun phase(name: String): Pair<JsonObject, BrowFittedV5Phase.Prepared> {
            val values = context.values.getValue(name).jsonObject
            require(values.keys == phaseFields) { "Unknown fitted brow v5 phase fields" }
            val observations = ModifierSourceInputs.from(ModifierAlgorithmContext(values.getValue("source").jsonObject, sourceResources))
            require(observations.targets == source.targets && observations.toModel == source.toModel &&
                observations.layers.size == source.layers.size && observations.layers.zip(source.layers).all { (measured, original) ->
                    measured.copy(bounds = original.bounds, centroidX = original.centroidX, centroidY = original.centroidY) == original
                }) { "Fitted brow v5 phase measurements changed their source raster identity" }
            return values to BrowFittedV5Phase.prepare(values, context.resources.getValue("${name}_meshes"), observations, checkpoint)
        }
        val (old, before) = phase("before")
        val (next, after) = phase("after")
        require(old.getValue("brow_fits").jsonObject.isEmpty() && next.getValue("brow_fits").jsonObject.keys == setOf("LEFT"))
        val values = instance.config["values"]?.jsonObject.orEmpty()
        require(values.keys.all { it == "lift" })
        val lift = (values["lift"]?.jsonPrimitive?.float ?: 45f).also { require(it.isFinite() && it in 20f..100f) }
        require(old.getValue("brow_lift") == JsonPrimitive(lift) && next.getValue("brow_lift") == JsonPrimitive(lift)) {
            "Fitted brow v5 source capture does not match its lift"
        }
        val expectedAxes = setOf("ParamAngleX", "ParamAngleY", "ParamAngleZ", "ParamBodyAngleX", "ParamBodyAngleY",
            "ParamBodyAngleZ", "ParamBodyLean", "ParamProportion", "ParamBreath", "ParamEyeLOpen", "ParamEyeROpen",
            "ParamEyeBallX", "ParamEyeBallY", "ParamEyeBallForm")
        fun axes(values: JsonObject) = values.getValue("axes").jsonArray.mapTo(linkedSetOf()) { it.jsonPrimitive.content }
        require(axes(old) == expectedAxes && axes(next) == expectedAxes + setOf("ParamBrowLY", "ParamBrowRY"))
        val beforeBranches = before.turnedDeformers.associateBy { it.id }
        val afterBranches = after.turnedDeformers.associateBy { it.id }
        val changed = (beforeBranches.keys + afterBranches.keys).filterTo(hashSetOf()) { id ->
            beforeBranches[id]?.let(PuppetIr::deformerToIr) != afterBranches[id]?.let(PuppetIr::deformerToIr)
        }
        val oldLayers = old.getValue("layers").jsonArray.map { it.jsonObject }.associateBy { it.getValue("id").jsonPrimitive.content }
        val nextLayers = next.getValue("layers").jsonArray.map { it.jsonObject }.associateBy { it.getValue("id").jsonPrimitive.content }
        require(before.parents.keys == source.targets.keys && after.parents.keys == source.targets.keys && oldLayers.keys == nextLayers.keys)
        val ids = source.targets.keys.filterTo(linkedSetOf()) { id ->
            val layer = oldLayers.getValue(id)
            layer.getValue("tag") == JsonPrimitive("EYEBROW") || before.parents[id] != after.parents[id] ||
                layer.getValue("aligned") != nextLayers.getValue(id).getValue("aligned") ||
                layer.getValue("aligned") == JsonPrimitive(true) && old.getValue("head_alignment") != next.getValue("head_alignment") ||
                generateSequence(before.parents.getValue(id).id) { beforeBranches[it]?.parent }.any { it in changed }
        }
        require(ids.isNotEmpty()) { "Fitted brow v5 requires a source brow transition" }
        val witnesses = source.targets.filterValues { sourceId -> source.layers.single { it.id == sourceId }.tag in eyeTags }.keys
        val selected = ids + witnesses
        fun projected(values: JsonObject, prepared: BrowFittedV5Phase.Prepared) =
            BrowFittedV5Phase.project(prepared, selected, axes(values).mapTo(hashSetOf(), ::ParameterId), checkpoint)
                .let { it.copy(drawables = it.drawables.filter { drawable -> drawable.id.raw in ids }) }
        val prior = projected(old, before)
        val desired = projected(next, after)
        val live = PuppetIr.toPuppet(input)
        val liveDeformers = live.deformers.mapTo(hashSetOf()) { it.id }
        val liveParts = live.parts.mapTo(hashSetOf()) { it.id }
        val owners = listOf(scaffold) + upstream.filterNot { it.id == scaffoldId }
        val canonicalDeformers = (prior.deformers + desired.deformers).map { it.id }.distinct()
        // All providers cover the complete shared target inventory, so each canonical carrier
        // has one target-independent live identity. A possible ID is not evidence of ownership.
        val sharedTarget = instance.targets.first().id
        val liveByCanonical = canonicalDeformers.associateWith { canonical ->
            val candidates = owners.map { owner ->
                DeformerId(ScaffoldBinding.deformerId(owner, sharedTarget, canonical.raw, shared = true))
            }.distinct().filter { it in liveDeformers }
            require(candidates.size <= 1) {
                "Fitted brow v5 has multiple live aliases for canonical carrier: ${canonical.raw}"
            }
            candidates.singleOrNull()
        }
        val resolvedDeformers = canonicalDeformers.associateWith { canonical ->
            liveByCanonical.getValue(canonical) ?: DeformerId(
                ScaffoldBinding.deformerId(instance, sharedTarget, canonical.raw, shared = true),
            ).also { require(it !in liveDeformers) {
                "Fitted brow v5 has an unowned live carrier ID collision: ${it.raw}"
            } }
        }
        require(resolvedDeformers.values.distinct().size == resolvedDeformers.size) {
            "Fitted brow v5 cannot alias distinct canonical carriers to one output"
        }
        val comparison = BindingComparison()
        val mergeSource = object : BindingMerge.Source {
            override fun provider(instance: RigModifierInstance) = scaffold
            override fun transition(instance: RigModifierInstance, target: ModifierTarget, checkpoint: () -> Unit) = prior to desired
            override fun resolveParameter(instance: RigModifierInstance, before: Parameter, declared: Parameter): Parameter? = null
            override fun static(model: PuppetModel) = BindingProjection.retain(model, emptySet(), mapOf(ParameterId("ParamMouthOpenY") to 1f))
            override fun onlyMasksChange(before: PuppetModel, after: PuppetModel, target: ModifierTarget, branch: Set<DeformerId>) =
                comparison.onlyMasksChange(before, after, DrawableId(target.id), branch)
            override fun deformerIds(instance: RigModifierInstance, target: ModifierTarget, checkpoint: () -> Unit) =
                resolvedDeformers
            override fun partIds(instance: RigModifierInstance, target: ModifierTarget, checkpoint: () -> Unit) =
                (prior.deformers + desired.deformers).mapNotNull { it.partId }.distinct().associateWith { canonical ->
                    owners.asSequence().map { PartId(ScaffoldBinding.partId(it, target.id, canonical.raw, shared = true)) }
                        .first { it in liveParts }
                }
            override fun requiredParts(model: PuppetModel, target: ModifierTarget) = emptySet<PartId>()
            override fun partId(instance: RigModifierInstance, mesh: String, canonical: String, shared: Boolean) =
                ScaffoldBinding.partId(instance, mesh, canonical, shared)
            override fun vertexSources(from: PuppetModel, original: Drawable, to: PuppetModel, target: Drawable, checkpoint: () -> Unit) =
                BodyVertexSources.match(to, original, target, checkpoint)
        }
        val changedActual = changed.mapNotNullTo(hashSetOf()) { liveByCanonical[it] }
        val liveParents = live.deformers.associate { it.id to it.parent }
        fun ancestors(parent: DeformerId?): Set<DeformerId> = buildSet {
            var cursor = parent
            while (cursor != null) {
                require(cursor in liveParents && add(cursor)) { "Invalid fitted brow v5 input hierarchy" }
                cursor = liveParents[cursor]
            }
        }
        for (mesh in live.drawables) {
            if (mesh.id.raw in ids) continue
            require(ancestors(mesh.parentDeformerId).none { it in changedActual }) {
                "Fitted brow v5 cannot move a shared frame with an uncaptured child: ${mesh.id.raw}"
            }
        }
        val recognized = liveByCanonical.values.filterNotNull().toSet()
        for (deformer in live.deformers) {
            if (deformer.id in recognized) continue
            require(ancestors(deformer.parent).none { it in changedActual }) {
                "Fitted brow v5 cannot move a shared frame with an uncaptured deformer: ${deformer.id.raw}"
            }
        }
        val scoped = instance.copy(targets = instance.targets.filter { it.id in ids })
        val bound = PuppetIr.toIr(BindingMerge.apply(live, scoped, mergeSource, checkpoint).withParametersSyncedFromTree())
        fun <T> objects(before: List<T>, after: List<T>, id: (T) -> String): ModifierObjectChanges<T> {
            val old = before.associateBy(id)
            val next = after.associateBy(id)
            val added = after.filter { id(it) !in old }
            require(after.map(id) == before.map(id).filter { it in next } + added.map(id)) {
                "Fitted brow v5 cannot reorder existing objects"
            }
            return ModifierObjectChanges(added = added, removed = before.filter { id(it) !in next },
                updated = after.mapNotNull { value -> old[id(value)]?.takeIf { it != value }?.let { ModifierObjectUpdate(it, value) } })
        }
        fun <T> field(before: T, after: T) = if (before == after) null else ModifierFieldChange(before, after)
        return ModifierModelChanges(parameters = objects(input.parameters, bound.parameters) { it.id },
            meshes = objects(input.meshes, bound.meshes) { it.id }, deformers = objects(input.deformers, bound.deformers) { it.id },
            parts = objects(input.parts, bound.parts) { it.id }, parameterTree = field(input.parameterTree, bound.parameterTree),
            rootChildren = field(input.rootChildren, bound.rootChildren), renderRoot = field(input.renderRoot, bound.renderRoot))
    }

    const val SCHEMA = "brow-fitted-source/5"
    val requiredVersions = linkedMapOf("rig.scaffold" to 2, "body/legacy" to 2, "face/fitted" to 3, "eye/fitted" to 5)
    private val eyeTags = setOf("IRIDES", "EYEWHITE", "EYELASH", "EYE_CLOSE")
    private val fields = setOf("schema", "providers", "provider_packages", "source", "before", "after")
    private val phaseFields = setOf("base", "source", "body", "head_turn", "head_alignment", "container", "eye_input",
        "eye_values", "eye_tuning", "hair_tuning", "brow_fits", "brow_lift", "teeth_fade", "shape_name", "turn_name",
        "layers", "parameters", "groups", "axes")

    fun validateProviders(providers: List<ModifierUpstreamCapability>, instance: RigModifierInstance) {
        require(providers.map { it.type } == requiredVersions.keys.toList() &&
            providers.map { it.instanceId }.distinct().size == providers.size) {
            "Fitted brow v5 requires an ordered, unique source prefix"
        }
        val scaffold = instance.config.getValue("scaffold")
        for (owner in providers) require(owner.instanceId != null && owner.capability == owner.type &&
            owner.version == requiredVersions.getValue(owner.type) && owner.targets.toSet() == instance.targets.toSet() &&
            (if (owner.type == "rig.scaffold") JsonPrimitive(owner.instanceId) == scaffold else owner.config["scaffold"] == scaffold)) {
            "Fitted brow v5 requires complete scaffold2, body2, face3 and eye5 source providers"
        }
    }

}

object BrowFittedV5Worker {
    @JvmStatic fun main(args: Array<String>) = AlgorithmWorker.run(mapOf(
        (BrowFittedV5Binding.ENTRYPOINT to BrowFittedV5Binding.VERSION) to BrowFittedV5Binding,
    ))
}
