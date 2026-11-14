package io.github.psd2live.algorithms.binding

import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.model.*
import kotlinx.serialization.json.*
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.MeshDeltaForm
import org.umamo.runtime.model.ParameterId

/** Adds the fitted brow's own overshoot shapes and pendulums to the live binding. */
class BrowSpringAlgorithm : ReadingModifierAlgorithmPlugin {
    override fun changes(input: ModifierEvaluationInput, instance: RigModifierInstance, checkpoint: () -> Unit): ModifierModelChanges {
        require(instance.version == VERSION && instance.targets.all { it.kind == "mesh" })
        val source = BrowSpringInputs.decode(input.context, checkpoint)
        require(source.targets.keys.all { ModifierTarget("mesh", it) in instance.targets })
        val scaffold = instance.config.getValue("scaffold")
        val base = instance.config.getValue("base").jsonPrimitive.content
        require(input.providers("rig.scaffold").any {
            it.instanceId == scaffold.jsonPrimitive.content && it.version == 2 && it.targets.containsAll(instance.targets)
        }) { "Brow spring v2 requires a shared v2 scaffold" }
        val fitted = requireNotNull(input.providers("brow/fitted").singleOrNull {
            it.instanceId == base && it.version in 1..2 && it.targets.containsAll(instance.targets) && it.config["scaffold"] == scaffold
        }) { "Brow spring v2 requires a compatible fitted brow provider" }
        if (source.eyeProvider != null) require(input.providers("eye/fitted").any {
            it.instanceId == source.eyeProvider && it.version >= 1 && it.config["scaffold"] == scaffold
        }) { "Brow spring v2 requires its captured fitted eyes" }
        val shape = source.shapeProvider?.let { provider ->
            requireNotNull((input.providers(SHAPE) + input.providers(SHAPE_ENTRYPOINT)).singleOrNull {
                it.instanceId == provider && it.version in 1..2 && it.config["scaffold"] == scaffold &&
                    it.config["base"] == JsonPrimitive(base) && it.targets.containsAll(instance.targets)
            }) { "Brow spring v2 requires its captured brow shape" }
        }
        val declared = parameters(source)
        val reused = declared.mapNotNull { parameter ->
            input.parameter(parameter.id)?.also {
                require(it.copy(name = parameter.name) == parameter) { "Incompatible brow spring parameter ${parameter.id}" }
            }
        }
        val added = declared.filter { next -> reused.none { it.id == next.id } }
        val lift = value(fitted.config, "lift", 45f, .2f..1f)
        val strength = shape?.let { value(it.config, "strength", 100f, 0f..2f) } ?: 1f
        val options = BrowOptions(shape = shape != null, shapeStrength = strength, spring = true)
        val updates = source.targets.map { (id, target) ->
            checkpoint()
            val mesh = requireNotNull(input.mesh(id))
            ModifierObjectUpdate(mesh, bind(input, mesh, target, lift, options, checkpoint))
        }
        val tree = if (added.isEmpty()) null else {
            val before = input.parameterTree
            val roots = before.ifEmpty { input.parameters.map { ParameterNode.Param(it.id) } }
            val id = instance.outputId("parameter-folder:ParamGroupPhysics")
            val children = added.map { ParameterNode.Param(it.id) }
            val next = append(roots, id, children) ?: (roots + ParameterNode.Group(id, source.group, true, children))
            ModifierFieldChange(before, next)
        }
        val physics = if (instance.config["physicsEnabled"]?.jsonPrimitive?.boolean == false) ModifierObjectChanges()
        else BrowSpringPhysics.changes(input.physics, instance, (input.parameters + added).mapTo(hashSetOf()) { it.id }, source.physicsNames)
        return ModifierModelChanges(parameters = ModifierObjectChanges(added = added, reused = reused),
            meshes = ModifierObjectChanges(updated = updates), parameterTree = tree, physicsGroups = physics)
    }

    private fun bind(
        input: ModifierEvaluationInput, mesh: Mesh, target: BrowSpringInputs.Target, lift: Float,
        options: BrowOptions, checkpoint: () -> Unit,
    ): Mesh {
        val side = suffix(target.fit.side)
        val height = ParameterId("ParamBrow${side}Y")
        val angle = ParameterId("ParamBrow${side}Angle")
        require(input.parameter(height.raw)?.let { it.min == -1f && it.max == 1f && it.default == 0f && !it.blend } == true)
        if (options.shape) require(input.parameter(angle.raw)?.let { it.min == -1f && it.max == 1f && it.default == 0f && !it.blend } == true)
        val raw = target.raw
        val frame = target.frame
        val positions = FloatArray(raw.rigPositions.size) { index ->
            if (index % 1024 == 0) checkpoint()
            val coordinate = raw.rigPositions[index]
            if (target.branch.isEmpty()) coordinate
            else if (index % 2 == 0) (coordinate - frame.left) / frame.width.coerceAtLeast(1e-4f)
            else (coordinate - frame.top) / frame.height.coerceAtLeast(1e-4f)
        }
        val geometry = requireNotNull(mesh.geometry)
        val actualBranch = input.parentsOfMesh(mesh.id).map(BrowSpringInputs::fingerprint)
        val posOk = geometry.positions.size == positions.size &&
            (0 until positions.size).all { kotlin.math.abs(geometry.positions[it] - positions[it]) <= 1e-4f }
        val uvOk = geometry.uvs == Floats.wrap(raw.atlasUvs)
        val idxOk = geometry.indices == Ints.wrap(raw.triangles)
        val branchOk = actualBranch == target.branch
        require(posOk && uvOk && idxOk && branchOk) {
            "Brow spring v2 requires the native fitted brow mesh and parent branch. Use v1 with authored or lowered transforms."
        }
        val travel = target.eye?.let { it.opening * it.width * lift } ?: (target.fit.length * .25f * lift / .45f)
        val rig = BrowRig(target.fit, travel, options, checkpoint)
        val spring = ParameterId("ParamBrow${side}Spring")
        val shapes = rig.shapes(raw.rigPositions, frame, ParameterId("ParamBrow${side}X"), ParameterId("ParamBrow${side}Form"),
            EyeModules.open(target.fit.side), wide = false, spring = spring).map { shape ->
            BlendBinding(shape.parameterId.raw, Floats.wrap(shape.keys), shape.neutralIndex, shape.forms.map { form ->
                form?.let { MeshShape(Floats.wrap(it.positionDeltas), target.order, target.opacity, Rgb.White, Rgb.Black) }
            })
        }
        val expectedOffsets = offsets(rig.grid(raw.rigPositions, frame, height, angle))
        require((mesh.offsets == expectedOffsets ||
            mesh.offsets?.axes?.any { it.parameter == height.raw } == true) &&
            mesh.shapes.none { it.parameter == spring.raw } &&
            shapes.drop(1).all { expected -> mesh.shapes.any { it.parameter == expected.parameter } } &&
            mesh.channels.isEmpty()) {
            "Brow spring v2 requires the native height and brow shape tracks. Use v1 with edited or lowered tracks."
        }
        return mesh.copy(shapes = listOf(shapes.first()) + mesh.shapes)
    }

    private fun offsets(grid: KeyformGrid<MeshDeltaForm>) = KeyGrid(
        grid.axes.map { KeyAxis(it.parameterId.raw, Floats.wrap(it.keys)) },
        grid.cells.map { KeyCell(Ints.wrap(it.coordinate), MeshOffsets(Floats.wrap(it.form.positionDeltas))) },
    )

    private fun append(nodes: List<ParameterNode>, id: String, children: List<ParameterNode>): List<ParameterNode>? {
        for ((index, node) in nodes.withIndex()) {
            if (node !is ParameterNode.Group) continue
            val next = if (node.id == id) node.children + children else append(node.children, id, children) ?: continue
            return nodes.toMutableList().apply { set(index, node.copy(children = next)) }
        }
        return null
    }

    private fun value(config: JsonObject, key: String, default: Float, range: ClosedFloatingPointRange<Float>): Float =
        ((config["values"]?.jsonObject?.get(key)?.jsonPrimitive?.float ?: default) / 100f).also {
            require(it in range) { "Invalid fitted brow $key" }
        }

    companion object {
        const val TYPE = "brow.spring"
        const val VERSION = 2
        const val ENTRYPOINT = "builtin.62726f772e737072696e67.v2/spring"
        const val SHAPE = "brow.shape"
        const val SHAPE_ENTRYPOINT = "builtin.62726f772e7368617065.v2/shape"
        val optionalUpstream = setOf("eye/fitted", SHAPE, SHAPE_ENTRYPOINT)
        val writes = setOf("mesh", "parameter", "hierarchy", "physics")

        fun parameters(source: BrowSpringInputs): List<Parameter> {
            val sides = source.targets.values.mapTo(hashSetOf()) { it.fit.side }
            return listOf(Side.LEFT, Side.RIGHT).mapNotNull { side ->
                val name = source.names[side] ?: return@mapNotNull null
                val blend = side in sides
                Parameter("ParamBrow${suffix(side)}Spring", name, -1f, 1f, 0f, blend = blend,
                    keys = if (blend) Floats.values(-1f, 0f, 1f) else null)
            }
        }

        private fun suffix(side: Side) = if (side == Side.LEFT) "L" else "R"
    }
}
