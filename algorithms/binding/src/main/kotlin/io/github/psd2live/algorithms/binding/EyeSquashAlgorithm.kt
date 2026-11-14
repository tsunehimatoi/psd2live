package io.github.psd2live.algorithms.binding

import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.model.*
import io.github.psd2live.bridge.PuppetIr
import kotlinx.serialization.json.jsonPrimitive
import org.umamo.edit.VertexSource

/** Adds the fitted iris's closing motion to the jelly grid without changing its topology. */
class EyeSquashAlgorithm : ReadingModifierAlgorithmPlugin {
    override fun changes(input: ModifierEvaluationInput, instance: RigModifierInstance, checkpoint: () -> Unit): ModifierModelChanges {
        require(instance.version == VERSION && instance.targets.all { it.kind == "mesh" })
        val source = EyeSquashInputs.decode(input.context, checkpoint)
        val scaffold = instance.config.getValue("scaffold")
        val base = instance.config.getValue("base")
        require(source.keys.all { id -> instance.targets.any { it.id == id } })
        require(input.providers("rig.scaffold").any {
            it.instanceId == scaffold.jsonPrimitive.content && it.targets.containsAll(instance.targets)
        }) { "Missing eye squash scaffold provider" }
        val updates = instance.targets.mapNotNull { target ->
            checkpoint()
            require(input.providers("eye/fitted").any { it.instanceId == base.jsonPrimitive.content && target in it.targets }) {
                "Missing fitted eye provider"
            }
            val measured = source[target.id] ?: return@mapNotNull null
            fun applies(provider: ModifierUpstreamCapability) =
                target in provider.targets && provider.config["scaffold"] == scaffold && provider.config["base"] == base
            if ((input.providers(TYPE) + input.providers(ENTRYPOINT)).any(::applies)) return@mapNotNull null
            val wide = input.providers("eye.wide").any(::applies)
            val mesh = requireNotNull(input.mesh(target.id))
            require(HEAD_TURN.none { capability ->
                input.providers(capability).any { target in it.targets && it.config["scaffold"] == scaffold }
            } && mesh.offsets?.axes.orEmpty().none { it.parameter in HEAD_AXES }) {
                "Eye squash v2 cannot lower fitted head-turn carriers"
            }
            val geometry = requireNotNull(mesh.geometry)
            require(geometry.uvs == Floats.wrap(measured.raw.atlasUvs) && geometry.indices == Ints.wrap(measured.raw.triangles)) {
                "Eye squash requires the captured iris topology"
            }
            val opening = EyeModules.open(measured.side).raw
            val jelly = "ParamEyeBallForm"
            require(input.parameter(opening) != null && input.parameter(jelly) != null)
            val before = grid(measured, jelly, opening, null, checkpoint)
            val after = grid(measured, jelly, opening,
                if (wide) floatArrayOf(0f, measured.crease, 1f, 2f) else floatArrayOf(0f, measured.crease, 1f), checkpoint)
            val offsets = residual(input, mesh, measured, before, after, checkpoint)
            val updated = mesh.copy(offsets = offsets)
            ModifierObjectUpdate(mesh, updated).takeIf { updated != mesh }
        }
        return ModifierModelChanges(meshes = ModifierObjectChanges(updated = updates))
    }

    private fun grid(source: EyeSquashInputs, jelly: String, opening: String, opens: FloatArray?,
                     checkpoint: () -> Unit): KeyGrid<MeshOffsets> {
        val jellies = floatArrayOf(-1f, 0f, 1f)
        val axes = listOf(KeyAxis(jelly, Floats.wrap(jellies))) + opens.orEmptyAxes(opening)
        val positions = source.raw.rigPositions
        val bottom = positions.indices.filter { it % 2 == 1 }.maxOf { positions[it] }
        val cells = buildList {
            for ((o, open) in (opens ?: floatArrayOf(1f)).withIndex()) for ((j, amount) in jellies.withIndex()) {
                checkpoint()
                val scale = if (open < 1f) .82f + .18f * (open / source.crease).coerceIn(0f, 1f) else 1f
                val delta = FloatArray(positions.size)
                for (i in positions.indices step 2) {
                    if (i % 1024 == 0) checkpoint()
                    val (x, y) = ScaffoldMesh.irisPoint(positions[i], positions[i + 1], source.iris, amount)
                    val posedY = if (opens == null) y else bottom + (y - bottom) * scale
                    delta[i] = (x - positions[i]) / source.frame.width.coerceAtLeast(1e-4f)
                    delta[i + 1] = (posedY - positions[i + 1]) / source.frame.height.coerceAtLeast(1e-4f)
                }
                add(KeyCell(if (opens == null) Ints.values(j) else Ints.values(j, o), MeshOffsets(Floats.wrap(delta))))
            }
        }
        return KeyGrid(axes, cells)
    }

    private fun FloatArray?.orEmptyAxes(opening: String) =
        if (this == null) emptyList() else listOf(KeyAxis(opening, Floats.wrap(this)))

    private fun residual(input: ModifierEvaluationInput, mesh: Mesh, source: EyeSquashInputs,
                         before: KeyGrid<MeshOffsets>, after: KeyGrid<MeshOffsets>, checkpoint: () -> Unit): KeyGrid<MeshOffsets>? {
        val geometry = requireNotNull(mesh.geometry)
        val original = FloatArray(source.raw.rigPositions.size) { i ->
            val value = source.raw.rigPositions[i]
            if (source.parent == null) value
            else if (i % 2 == 0) (value - source.frame.left) / source.frame.width.coerceAtLeast(1e-4f)
            else (value - source.frame.top) / source.frame.height.coerceAtLeast(1e-4f)
        }
        val positions = Floats.wrap(original)
        if (mesh.parent == source.parent && geometry.positions == positions && mesh.offsets == before && mesh.shapes.isEmpty())
            return after
        val liveBranch = input.parentsOfMesh(mesh.id)
        val canonicalBranch = buildList {
            val seen = hashSetOf<String>()
            var parent = source.parent
            while (parent != null) {
                checkpoint()
                require(seen.add(parent)) { "Cyclic canonical iris parent chain" }
                val deformer = requireNotNull(input.deformer(parent)) { "Missing canonical iris parent: $parent" }
                add(deformer)
                parent = deformer.parent
            }
        }
        val parameters = input.parameters
        val current = PuppetIr.toPuppet(RigIR(input.canvas, parameters, deformers = liveBranch, meshes = listOf(mesh)))
        val canonical = mesh.copy(parent = source.parent, geometry = geometry.copy(positions = positions),
            offsets = null, shapes = emptyList(), channels = emptyMap())
        val stationary = BindingProjection.retain(PuppetIr.toPuppet(RigIR(input.canvas, parameters,
            deformers = canonicalBranch, meshes = listOf(canonical))), emptySet(), checkpoint = checkpoint)
        fun projection(offsets: KeyGrid<MeshOffsets>) = stationary.copy(parameters = current.parameters, drawables =
            PuppetIr.toPuppet(RigIR(input.canvas, parameters, meshes = listOf(canonical.copy(offsets = offsets)))).drawables)
        val drawable = current.drawables.single()
        val vertices = List(original.size / 2) { VertexSource.FromOld(it) }
        val offsets = GeometryResidual.geometry(current, projection(before), projection(after), drawable.id, vertices, vertices, checkpoint)
        return PuppetIr.meshToIr(drawable.copy(geometryGrid = offsets)).offsets
    }

    companion object {
        const val TYPE = "eye.squash"
        const val VERSION = 2
        const val ENTRYPOINT = "builtin.6579652e737175617368.v2/squash"
        val HEAD_TURN = setOf("face/fitted", "face.depth-turn")
        private val HEAD_AXES = setOf("ParamAngleX", "ParamAngleY", "ParamAngleZ")
    }
}
