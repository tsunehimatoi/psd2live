package io.github.psd2live.algorithms.binding

import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.model.*
import kotlinx.serialization.json.*
import io.github.psd2live.targets.cubism.PuppetIr
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.MeshDeltaForm
import org.umamo.runtime.model.ParameterId

/** Adds the fitted brow's own overshoot shapes and pendulums to the live binding. */
class BrowSpringV4Algorithm : ReadingModifierAlgorithmPlugin {
    override fun changes(input: ModifierEvaluationInput, instance: RigModifierInstance, checkpoint: () -> Unit): ModifierModelChanges {
        require(instance.version == VERSION && instance.targets.all { it.kind == "mesh" })
        val context = input.context
        val carrierNames = context.values.getValue("carrier_names").jsonObject
        val source = BrowSpringInputs.decode(ModifierAlgorithmContext(JsonObject(context.values - "carrier_names"), context.resources), checkpoint)
        require(source.targets.keys.all { ModifierTarget("mesh", it) in instance.targets })
        val scaffold = instance.config.getValue("scaffold")
        val base = instance.config.getValue("base").jsonPrimitive.content
        require(input.providers("rig.scaffold").any {
            it.instanceId == scaffold.jsonPrimitive.content && it.version == 2 && it.targets.containsAll(instance.targets)
        }) { "Brow spring v4 requires a shared v2 scaffold" }
        val fitted = requireNotNull(input.providers("brow/fitted").singleOrNull {
            it.instanceId == base && it.type == "brow/fitted" && it.version in 1..3 && it.targets.containsAll(instance.targets) && it.config["scaffold"] == scaffold
        }) { "Brow spring v4 requires a compatible fitted brow provider" }
        if (source.eyeProvider != null) require(input.providers("eye/fitted").any {
            it.instanceId == source.eyeProvider && it.type == "eye/fitted" && it.version in 2..5 && it.config["scaffold"] == scaffold &&
                it.targets.containsAll(source.targets.filterValues { it.eye != null }.keys.map { ModifierTarget("mesh", it) })
        }) { "Brow spring v4 requires its captured fitted eyes" }
        val shape = source.shapeProvider?.let { provider ->
            requireNotNull((input.providers(SHAPE) + input.providers(SHAPE_ENTRYPOINT)).singleOrNull {
                it.instanceId == provider && it.version in 1..2 && it.config["scaffold"] == scaffold &&
                    it.config["base"] == JsonPrimitive(base) && it.targets.containsAll(instance.targets)
            }) { "Brow spring v4 requires its captured brow shape" }
        }
        val declared = BrowSpringAlgorithm.parameters(source)
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
            ModifierObjectUpdate(mesh, bind(input, mesh, target, lift, options, scaffold, base,
                carrierNames.getValue(id).jsonPrimitive.content, checkpoint))
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
        options: BrowOptions, scaffold: JsonElement, base: String, carrierName: String, checkpoint: () -> Unit,
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
        val branch = input.parentsOfMesh(mesh.id)
        val carrierCount = branch.size - target.branch.size
        require(carrierCount >= 0 && branch.drop(carrierCount).map(BrowSpringInputs::fingerprint) == target.branch) {
            "Brow spring v4 requires the native fitted brow parent branch: ${mesh.id}"
        }
        val parent = branch.getOrNull(carrierCount)
        val providers = input.upstream.filter { it.config["scaffold"] == scaffold && ModifierTarget("mesh", mesh.id) in it.targets }
        val blink = providers.lastOrNull { it.capability == BLINK && it.config["base"] == JsonPrimitive(base) }
        val eye = providers.lastOrNull { it.capability == "eye/fitted" }
        val open = EyeModules.open(target.fit.side)
        val smileProvider = providers.lastOrNull { it.capability == "eye.smile" }
        val smile = EyeModules.smile(target.fit.side).takeIf { target.eye != null && smileProvider != null }
        val blinkActive = blink != null && (eye != null || providers.any { it.capability == "eye/legacy" })
        val wide = eye != null && providers.any { it.capability == "eye.wide" }
        val dip = blink?.let { value(it.config, "dip", 100f, 0f..2f) } ?: 1f
        val travel = target.eye?.let { it.opening * it.width * lift } ?: (target.fit.length * .25f * lift / .45f)
        val rig = BrowRig(target.fit, travel, options.copy(blink = blinkActive, blinkDip = dip), checkpoint)
        val spring = ParameterId("ParamBrow${side}Spring")
        val shapes = rig.shapes(raw.rigPositions, frame, ParameterId("ParamBrow${side}X"), ParameterId("ParamBrow${side}Form"),
            open, wide, smile, spring).map { shape ->
            BlendBinding(shape.parameterId.raw, Floats.wrap(shape.keys), shape.neutralIndex, shape.forms.map { form ->
                form?.let { MeshShape(Floats.wrap(it.positionDeltas), mesh.drawOrder, target.opacity, Rgb.White, Rgb.Black) }
            })
        }
        val source = mesh.copy(parent = parent?.id,
            geometry = MeshGeometry(Floats.wrap(positions), Floats.wrap(raw.atlasUvs), Ints.wrap(raw.triangles)),
            offsets = offsets(rig.grid(raw.rigPositions, frame, height, angle)), shapes = shapes, channels = emptyMap())
        val result = if (!blinkActive) {
            require(carrierCount == 0)
            source
        } else {
            val lid = smile?.let { parameter ->
                requireNotNull(requireNotNull(smileProvider).targets.asSequence().mapNotNull { input.mesh(it.id) }
                    .firstOrNull { other -> other.offsets?.axes?.any { it.parameter == parameter.raw } == true }) {
                    "Brow spring v4 requires an upstream lid grid keyed by ${parameter.raw}"
                }
            }
            val params = input.parameters.let { parameters ->
                if (parameters.any { it.id == spring.raw }) parameters
                else parameters + Parameter(spring.raw, spring.raw, -1f, 1f, 0f, blend = true)
            }
            val model = PuppetIr.toPuppet(RigIR(input.canvas, params, meshes = listOfNotNull(source, lid?.copy(shapes = emptyList())),
                deformers = listOfNotNull(parent?.withoutShapes())))
            val lowered = BlendShapeLowering({ _, _ -> carrierName }, checkpoint).lawful(model)
            val carriers = lowered.deformers.drop(model.deformers.size)
            require(carriers.size == carrierCount) { "Brow spring v4 requires compatible blink carriers: ${mesh.id}" }
            val owner = requireNotNull(blink).let { RigModifierInstance(requireNotNull(it.instanceId), it.type, it.version, it.targets,
                config = it.config, outputAliases = it.outputAliases, plugin = it.plugin) }
            val remap = ModelIdRemap(deformers = carriers.associate {
                it.id to org.umamo.runtime.model.DeformerId(ScaffoldBinding.deformerId(owner, mesh.id, it.id.raw, shared = true))
            })
            val expected = carriers.map { BrowSpringInputs.fingerprint(PuppetIr.deformerToIr(remap.deformer(it))) }.reversed()
            require(expected == branch.take(carrierCount).map(BrowSpringInputs::fingerprint)) {
                "Brow spring v4 requires the native blink carrier geometry: ${mesh.id}"
            }
            PuppetIr.meshToIr(lowered.drawables.first().let { it.copy(parentDeformerId = it.parentDeformerId?.let(remap::deformer)) })
        }
        require(mesh.geometry == result.geometry && mesh.offsets == result.offsets && mesh.channels == result.channels &&
            mesh.shapes == result.shapes.drop(1)) {
            "Brow spring v4 requires the native height, shape and blink tracks: ${mesh.id}"
        }
        return mesh.copy(shapes = result.shapes)
    }

    private fun Deformer.withoutShapes(): Deformer = when (this) {
        is Deformer.Warp -> copy(shapes = emptyList())
        is Deformer.Rotation -> copy(shapes = emptyList())
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
        const val VERSION = 4
        const val ENTRYPOINT = "builtin.62726f772e737072696e67.v4/spring"
        const val SHAPE = "brow.shape"
        const val SHAPE_ENTRYPOINT = "builtin.62726f772e7368617065.v2/shape"
        private const val BLINK = "brow.blink"
        val optionalUpstream = setOf("eye/fitted", "eye/legacy", "eye.wide", "eye.smile", BLINK, SHAPE, SHAPE_ENTRYPOINT)
        val writes = setOf("mesh", "parameter", "hierarchy", "physics")

        private fun suffix(side: Side) = if (side == Side.LEFT) "L" else "R"
    }
}
