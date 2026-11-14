package io.github.psd2live.algorithms.binding

import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.model.*
import io.github.psd2live.targets.cubism.PuppetIr
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.umamo.edit.VertexSource
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.MeshDeltaForm
import org.umamo.runtime.model.ParameterId

/** Binds the angle sweep, sideways motion and arch to the fitted brow's source vertices. */
class BrowShapeAlgorithm : ReadingModifierAlgorithmPlugin {
    override fun changes(input: ModifierEvaluationInput, instance: RigModifierInstance, checkpoint: () -> Unit): ModifierModelChanges {
        require(instance.version == VERSION && instance.targets.all { it.kind == "mesh" })
        val source = BrowShapeInputs.decode(input.context, checkpoint)
        val scaffold = instance.config.getValue("scaffold").jsonPrimitive.content
        val base = instance.config.getValue("base").jsonPrimitive.content
        require(input.providers("rig.scaffold").any {
            it.instanceId == scaffold && it.version == 2 && it.targets.containsAll(instance.targets)
        }) { "Brow shape v2 requires a shared v2 scaffold" }
        val fitted = requireNotNull(input.providers("brow/fitted").singleOrNull {
            it.instanceId == base && it.version in 1..2 && it.targets.containsAll(instance.targets) &&
                it.config["scaffold"] == instance.config["scaffold"]
        }) { "Brow shape v2 requires a compatible fitted brow provider" }
        require(source.targets.keys.all { ModifierTarget("mesh", it) in instance.targets })
        if (source.eyeProvider != null) require(input.providers("eye/fitted").any {
            it.instanceId == source.eyeProvider && it.version >= 1 && it.config["scaffold"] == instance.config["scaffold"]
        }) { "Brow shape v2 requires its captured fitted eyes" }
        if (source.names.isEmpty()) return ModifierModelChanges()

        val lift = value(fitted.config, "lift", 45f, .2f..1f)
        val strength = value(instance.config, "strength", 100f, 0f..2f)
        val additions = parameters(source)
        val existing = additions.mapNotNull { parameter ->
            input.parameter(parameter.id)?.also {
                require(it.copy(name = parameter.name) == parameter) { "Incompatible brow shape parameter ${parameter.id}" }
            }
        }
        val added = additions.filter { next -> existing.none { it.id == next.id } }
        val updated = source.targets.mapNotNull { (id, target) ->
            checkpoint()
            val before = requireNotNull(input.mesh(id))
            val next = bind(input, before, target, lift, strength, checkpoint)
            ModifierObjectUpdate(before, next).takeIf { before != next }
        }
        val tree = if (added.isEmpty()) null else {
            val id = instance.outputId("parameter-folder:ParamGroupBrows")
            val children = added.map { ParameterNode.Param(it.id) }
            val root = input.parameterTree.ifEmpty { input.parameters.map { ParameterNode.Param(it.id) } }
            val (next, found) = append(root, id, children)
            ModifierFieldChange(input.parameterTree, if (found) next else next + ParameterNode.Group(id, source.groupName, true, children))
        }
        return ModifierModelChanges(parameters = ModifierObjectChanges(added = added, reused = existing),
            meshes = ModifierObjectChanges(updated = updated), parameterTree = tree)
    }

    private fun bind(
        input: ModifierEvaluationInput, mesh: Mesh, target: BrowShapeInputs.Target, lift: Float, strength: Float,
        checkpoint: () -> Unit,
    ): Mesh {
        val side = if (target.fit.side == Side.LEFT) "L" else "R"
        val height = ParameterId("ParamBrow${side}Y")
        val angle = ParameterId("ParamBrow${side}Angle")
        require(input.parameter(height.raw)?.let { it.min == -1f && it.max == 1f && it.default == 0f && !it.blend } == true)
        val raw = target.raw
        val geometry = requireNotNull(mesh.geometry)
        val positions = FloatArray(raw.rigPositions.size) { i ->
            if (i % 1024 == 0) checkpoint()
            val value = raw.rigPositions[i]
            if (target.parent == null) value
            else if (i % 2 == 0) (value - target.frame.left) / target.frame.width.coerceAtLeast(1e-4f)
            else (value - target.frame.top) / target.frame.height.coerceAtLeast(1e-4f)
        }
        val parentOk = mesh.parent == target.parent
        val sizeOk = geometry.positions.size == positions.size
        val posOk = sizeOk && (0 until positions.size).all { kotlin.math.abs(geometry.positions[it] - positions[it]) <= 1e-4f }
        val uvOk = geometry.uvs == Floats.wrap(raw.atlasUvs)
        val idxOk = geometry.indices == Ints.wrap(raw.triangles)
        require(parentOk && posOk && uvOk && idxOk) { "Brow shape v2 requires the fitted source mesh and parent frame" }

        val travel = target.eye?.let { it.opening * it.width * lift } ?: (target.fit.length * .25f * lift / .45f)
        val prior = BrowRig(target.fit, travel, BrowOptions(), checkpoint).grid(raw.rigPositions, target.frame, height, angle)
        val expectedOffsets = offsets(prior)
        val extraAxes = mesh.offsets != expectedOffsets &&
            expectedOffsets.axes.all { axis -> mesh.offsets?.axes?.any { it.parameter == axis.parameter } == true }
        require((mesh.offsets == expectedOffsets || extraAxes) && mesh.shapes.isEmpty() && mesh.channels.isEmpty()) {
            "Brow shape v2 requires the unedited fitted height grid, without earlier mesh motion or drawing tracks"
        }
        val rig = BrowRig(target.fit, travel, BrowOptions(shape = true, shapeStrength = strength), checkpoint)
        val grid = rig.grid(raw.rigPositions, target.frame, height, angle)
        val afterOffsets = offsets(grid)
        val mergedOffsets = if (!extraAxes) afterOffsets else {
            val parameters = (mesh.offsets?.axes.orEmpty() + afterOffsets.axes).map { it.parameter }.distinct().mapNotNull(input::parameter)
            val branch = input.parentsOfMesh(mesh.id)
            val current = PuppetIr.toPuppet(RigIR(input.canvas, parameters, deformers = branch, meshes = listOf(mesh)))
            val canonical = mesh.copy(geometry = geometry.copy(positions = Floats.wrap(positions)), offsets = null, shapes = emptyList(), channels = emptyMap())
            val stationary = BindingProjection.retain(PuppetIr.toPuppet(RigIR(input.canvas, input.parameters,
                deformers = branch, meshes = listOf(canonical))), emptySet(), checkpoint = checkpoint)
            fun projection(offsets: KeyGrid<MeshOffsets>) = stationary.copy(parameters = current.parameters, drawables =
                PuppetIr.toPuppet(RigIR(input.canvas, parameters, meshes = listOf(canonical.copy(offsets = offsets)))).drawables)
            val drawable = current.drawables.single()
            val vertices = List(positions.size / 2) { VertexSource.FromOld(it) }
            GeometryResidual.geometry(current, projection(expectedOffsets), projection(afterOffsets), drawable.id, vertices, vertices, checkpoint)?.let(::offsets)
        }
        val shapes = rig.shapes(raw.rigPositions, target.frame, ParameterId("ParamBrow${side}X"),
            ParameterId("ParamBrow${side}Form"), EyeModules.open(target.fit.side), wide = false).map { shape ->
            BlendBinding(shape.parameterId.raw, Floats.wrap(shape.keys), shape.neutralIndex, shape.forms.map { form ->
                form?.let { MeshShape(Floats.wrap(it.positionDeltas), mesh.drawOrder, mesh.opacity, mesh.multiply, mesh.screen) }
            })
        }
        return mesh.copy(offsets = mergedOffsets, shapes = shapes)
    }

    private fun offsets(grid: KeyformGrid<MeshDeltaForm>) = KeyGrid(
        grid.axes.map { KeyAxis(it.parameterId.raw, Floats.wrap(it.keys)) },
        grid.cells.map { KeyCell(Ints.wrap(it.coordinate), MeshOffsets(Floats.wrap(it.form.positionDeltas))) },
    )

    private fun append(nodes: List<ParameterNode>, id: String, children: List<ParameterNode>): Pair<List<ParameterNode>, Boolean> {
        var found = false
        val result = nodes.map { node ->
            if (node !is ParameterNode.Group) node
            else if (node.id == id) {
                found = true
                node.copy(children = node.children + children)
            } else {
                val (nested, matched) = append(node.children, id, children)
                found = found || matched
                if (matched) node.copy(children = nested) else node
            }
        }
        return result to found
    }

    private fun value(config: JsonObject, key: String, default: Float, range: ClosedFloatingPointRange<Float>): Float =
        ((config["values"]?.jsonObject?.get(key)?.jsonPrimitive?.float ?: default) / 100f).also {
            require(it in range) { "Invalid brow shape $key" }
        }

    companion object {
        const val TYPE = "brow.shape"
        const val VERSION = 2
        const val ENTRYPOINT = "builtin.62726f772e7368617065.v2/shape"

        fun parameters(source: BrowShapeInputs): List<Parameter> {
            val sides = source.targets.values.mapTo(hashSetOf()) { it.fit.side }
            val suffixes = listOf("X", "Angle", "Form")
            return source.names.mapIndexed { index, name ->
                val left = index % 2 == 0
                val suffix = suffixes[index / 2]
                val blend = suffix != "Angle" && (if (left) Side.LEFT else Side.RIGHT) in sides
                Parameter("ParamBrow${if (left) "L" else "R"}$suffix", name, -1f, 1f, 0f, blend = blend,
                    keys = if (blend) Floats.values(-1f, 0f, 1f) else null)
            }
        }
    }
}
