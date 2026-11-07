package io.github.psd2live.algorithms.binding

import io.github.psd2live.algorithm.worker.AlgorithmWorker
import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.model.Floats
import io.github.psd2live.format.model.Ints
import io.github.psd2live.format.model.KeyCell
import io.github.psd2live.format.model.KeyGrid
import io.github.psd2live.format.model.Mesh
import io.github.psd2live.format.model.MeshOffsets
import kotlinx.serialization.json.*

object BrowLegacyV2Worker {
    @JvmStatic fun main(args: Array<String>) = AlgorithmWorker.run(
        mapOf((BrowLegacyV2Algorithm.ENTRYPOINT to BrowLegacyV2Algorithm.VERSION) to BrowLegacyV2Algorithm),
    )
}

object BrowLegacyV2Algorithm : ReadingModifierAlgorithmPlugin {
    const val TYPE = "brow/legacy"
    const val VERSION = 2
    const val ENTRYPOINT = "builtin.62726f772f6c6567616379.v2/binding"
    val HEAD_TURN = setOf("face/legacy", "face/fitted", "face.depth-turn")
    val WRITES = setOf("mesh", "parameter", "hierarchy")
    private val kernel = BrowLegacyAlgorithm()
    private val headAxes = setOf("ParamAngleX", "ParamAngleY")
    private val face = LegacyFaceTuning()
    private val turnDefaults = mapOf("browFarShrink" to face.browFarShrink, "browNearGrow" to face.browNearGrow,
        "browShift" to face.browShift, "browTilt" to face.browTilt)

    fun validateConfig(instance: RigModifierInstance) {
        require(instance.type in setOf(TYPE, ENTRYPOINT) && instance.version == VERSION &&
            instance.targets.all { it.kind == "mesh" }) { "Invalid legacy brow v2 invocation" }
        require(instance.config.keys.all { it == "scaffold" || it == "tuning" })
        require(!instance.config["scaffold"]?.jsonPrimitive?.contentOrNull.isNullOrBlank()) {
            "Legacy brow v2 requires a scaffold ID"
        }
        val tuning = instance.config["tuning"]?.jsonObject.orEmpty()
        require(tuning.keys.all { it == "browLift" || it in turnDefaults }) { "Unknown legacy brow tuning" }
        for ((name, default) in turnDefaults) {
            require(tuning[name]?.let { it.jsonPrimitive.float == default } != false) {
                "Legacy brow v2 requires default brow head-turn tuning. Use v1 to adjust $name."
            }
        }
        require(tuning["browLift"]?.let { it.jsonPrimitive.float in 0f..40f } != false) {
            "Legacy brow lift must be within 0..40"
        }
    }

    fun requireScaffold(provider: RigModifierInstance, instance: RigModifierInstance) {
        require(provider.type == "rig.scaffold" && provider.version in 1..2 &&
            provider.id == instance.config.getValue("scaffold").jsonPrimitive.content &&
            provider.targets.containsAll(instance.targets) &&
            (provider.version == 1 || provider.targets.toSet() == instance.targets.toSet())) {
            "Legacy brow v2 requires its source scaffold and complete shared target scope"
        }
        provider.plugin?.let { pinned ->
            require(pinned.id == "builtin.7269672e73636166666f6c64.v${provider.version}" &&
                ModifierPluginVersion.parse(pinned.version, legacy = true) >= ModifierPluginVersion.parse("1.0.41")) {
                "Legacy brow v2 requires a source-only scaffold package"
            }
        }
    }

    override fun changes(input: ModifierEvaluationInput, instance: RigModifierInstance, checkpoint: () -> Unit): ModifierModelChanges {
        validateConfig(instance)
        val resolved = requireNotNull(input.providers("rig.scaffold").singleOrNull()) {
            "Legacy brow v2 requires its actual scaffold provider"
        }
        val provider = RigModifierInstance(requireNotNull(resolved.instanceId), resolved.type, resolved.version,
            resolved.targets, config = resolved.config, outputAliases = resolved.outputAliases, plugin = resolved.plugin)
        requireScaffold(provider, instance)
        val source = BrowLegacyInputs.decode(input.context)
        val targets = instance.targets.mapTo(hashSetOf()) { it.id }
        require(source.targets.keys.all { it in targets }) { "Unexpected legacy brow source mesh" }
        val head = input.upstream.filter { it.capability in HEAD_TURN }
        require(head.all {
            it.instanceId != null && it.type in setOf("face/legacy", "face/fitted") && it.version in 1..2 &&
                it.config["scaffold"] == instance.config["scaffold"]
        }) { "Legacy brow v2 requires a compatible preceding head binding" }
        val fittedTargets = head.filter { it.type == "face/fitted" }.flatMapTo(hashSetOf()) { it.targets }
        val folded = LinkedHashMap<String, ModifierObjectUpdate<Mesh>>()
        val lift = instance.config["tuning"]?.jsonObject?.get("browLift")?.jsonPrimitive?.float ?: source.lift
        for ((id, brow) in source.targets) {
            checkpoint()
            val mesh = requireNotNull(input.mesh(id)) { "Missing legacy brow mesh: $id" }
            val parent = brow.parent
            val geometry = requireNotNull(mesh.geometry) { "Legacy brow v2 requires geometry on $id" }
            require(parent != null && mesh.parent == parent && input.parentsOfMesh(id).all { ancestor ->
                val local = provider.localOutputName(ancestor.id)
                local != null && (local.startsWith("shared/deformer:") || local.startsWith("$id/deformer:"))
            }) {
                "Legacy brow v2 requires the source parent branch on $id. Use v1 after reparenting."
            }
            val offsets = mesh.offsets
            if (offsets != null && offsets.axes.isNotEmpty() && ModifierTarget("mesh", id) in fittedTargets) {
                val components = geometry.positions.size
                require(offsets.axes.all { it.parameter in headAxes } && mesh.shapes.isEmpty() &&
                    mesh.channels.values.all { channel -> channel.axes.all { it.parameter in headAxes } }) {
                    "Legacy brow v2 requires the fitted head grid before other brow motion. Use v1 on $id."
                }
                val height = BrowLegacyAlgorithm.grid(brow, lift, components)
                folded[id] = ModifierObjectUpdate(mesh, mesh.copy(offsets = foldHead(height, offsets, components, checkpoint)))
            }
        }
        val plainTargets = instance.targets.filterNot { it.id in folded }
        val plain = instance.copy(version = 1, targets = plainTargets.ifEmpty { instance.targets })
        val changes = kernel.changes(input, plain, checkpoint)
        if (folded.isEmpty()) return changes
        return changes.copy(meshes = ModifierObjectChanges(updated =
            changes.meshes.updated.filterNot { it.before.id in folded } + folded.values))
    }

    /** The native lowering adds the already folded head offsets to the base height grid. */
    private fun foldHead(
        height: KeyGrid<MeshOffsets>,
        head: KeyGrid<MeshOffsets>,
        components: Int,
        checkpoint: () -> Unit,
    ): KeyGrid<MeshOffsets> {
        val cells = head.cells.size.toLong() * height.cells.size
        // Match BlendShapeLowering's mesh budgets. A combination exceeding them needs a turn carrier.
        require(head.cells.size.toLong() == head.axes.fold(1L) { size, axis -> size * axis.keys.size } &&
            cells <= 256 && cells * components <= 120_000) {
            "Legacy brow v2 cannot preserve the fitted head grid within the mesh budget. Use v1."
        }
        return KeyGrid(height.axes + head.axes, head.cells.flatMap { turned ->
            checkpoint()
            require(turned.form.deltas.size == components)
            height.cells.map { raised ->
                val coordinate = Ints.of(raised.coordinate.toArray() + turned.coordinate.toArray())
                val deltas = Floats.wrap(FloatArray(components) { raised.form.deltas[it] + turned.form.deltas[it] })
                KeyCell(coordinate, MeshOffsets(deltas))
            }
        })
    }
}
