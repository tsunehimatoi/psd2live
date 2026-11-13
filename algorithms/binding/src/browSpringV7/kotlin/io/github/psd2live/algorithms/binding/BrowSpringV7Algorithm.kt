package io.github.psd2live.algorithms.binding

import io.github.psd2live.algorithm.worker.AlgorithmWorker
import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.compile.RigIrBinary
import io.github.psd2live.format.model.*
import io.github.psd2live.targets.cubism.PuppetIr
import kotlinx.serialization.json.*
import org.umamo.runtime.eval.meshGridDefaultDeltas
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.MeshDeltaForm
import org.umamo.runtime.model.MeshForm
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PartId

/** Adds only the source brow's spring shape. Existing lowered carriers are witnessed, never written. */
class BrowSpringV7Algorithm : ReadingModifierAlgorithmPlugin {
    override fun changes(input: ModifierEvaluationInput, instance: RigModifierInstance, checkpoint: () -> Unit): ModifierModelChanges {
        require(instance.version == VERSION && instance.targets.isNotEmpty() &&
            instance.targets.all { it.kind == "mesh" } && instance.targets.distinct().size == instance.targets.size)
        val context = input.context
        val captured = context.values.getValue("providers").jsonArray.map {
            Json.decodeFromJsonElement(ModifierUpstreamCapability.serializer(), it)
        }
        require(captured.map { it.instanceId }.distinct().size == captured.size && captured.all { it.instanceId != null })
        val scaffoldId = instance.config.getValue("scaffold")
        val preceding = input.upstream.filter { it.capability in observed }.distinct()
        // Match complete identities, including persisted output aliases and package references, not just a version.
        require(preceding.map { it.copy(capability = it.type) }.distinct().toSet() == captured.toSet()) {
            "Brow spring v7 requires its exact captured providers, aliases and packages"
        }
        fun provider(type: String, vararg versions: Int): ModifierUpstreamCapability = requireNotNull(captured.singleOrNull {
            it.type == type && it.capability == type && it.version in versions && it.targets.containsAll(instance.targets) &&
                (type == "rig.scaffold" || it.config["scaffold"] == scaffoldId)
        }) { "Brow spring v7 requires one $type@${versions.joinToString("/")} provider on its full target scope" }
        val scaffold = provider("rig.scaffold", 2)
        val fitted = provider("brow/fitted", 4)
        val face = provider("face/fitted", 3)
        val eye = provider("eye/fitted", 5)
        val shape = provider("brow.shape", 6)
        val lid = provider("brow.lid-coupling", 8)
        // Both blink versions must reconstruct to the same exact prior mesh and ancestor contents below.
        val blink = provider("brow.blink", 1, 6)
        val base = instance.config.getValue("base")
        require(scaffold.instanceId == scaffoldId.jsonPrimitive.content && scaffold.targets.toSet() == instance.targets.toSet() &&
            fitted.instanceId == base.jsonPrimitive.content && listOf(shape, lid, blink).all { it.config["base"] == base } &&
            lid.config["dependencies"]?.jsonObject?.get("eye/fitted") == JsonPrimitive(eye.instanceId)) {
            "Brow spring v7 requires the captured fitted brow, eye and shared scaffold"
        }
        require(captured.none { it.type in unsupported }) { "Brow spring v7 does not support preceding spring, cheek or separate depth turn" }
        for ((type, version) in eyeAddons) {
            val providers = captured.filter { it.type == type }
            require(providers.size <= 1 && providers.all { it.version == version && it.config["base"] == JsonPrimitive(eye.instanceId) &&
                it.config["scaffold"] == scaffoldId && it.targets.containsAll(instance.targets) }) {
                "Brow spring v7 requires one direct $type@$version provider"
            }
        }
        val source = source(context, checkpoint)
        require(source.targets.size == 1 && source.eyeProvider == eye.instanceId &&
            source.targets.keys.all { ModifierTarget("mesh", it) in instance.targets } && source.targets.values.single().eye != null)
        val head = context.values.getValue("head_turn").jsonObject
        require(head.getValue("provider") == JsonPrimitive(face.instanceId))
        val shell = head.getValue("shell").jsonArray.map { it.jsonPrimitive.float.also { f -> require(f.isFinite()) } }
        require(shell.size == 7 && shell[2] > 0f && shell[3] > 0f)
        val turn = HeadTurn(shell[0], shell[1], shell[2], shell[3], shell[4], shell[5], shell[6], checkpoint)
        val branch = RigIrBinary.decode(context.resources.getValue("source_branch").read())
        require(branch.meshes.isEmpty() && branch.parameters.isEmpty())
        val nativeIds = context.values.getValue("native_ids").jsonObject
        val names = context.values.getValue("mesh_names").jsonObject
        require(nativeIds.keys == source.targets.keys && names.keys == source.targets.keys)
        val turnName = context.values.getValue("turn_name").jsonPrimitive.content
        val shapeName = context.values.getValue("shape_name").jsonPrimitive.content
        val parameters = input.parameters
        val declared = BrowSpringV7Algorithm.parameters(context)
        val reused = declared.mapNotNull { parameter -> input.parameter(parameter.id)?.also {
            require(it.copy(name = parameter.name) == parameter) { "Incompatible brow spring parameter ${parameter.id}" }
        } }
        val added = declared.filterNot { parameter -> reused.any { it.id == parameter.id } }
        val controls = parameters + added
        val lift = knob(fitted.config, "lift", 45f, .2f..1f)
        val options = BrowOptions(shape = true, shapeStrength = knob(shape.config, "strength", 100f, 0f..2f),
            blink = true, blinkDip = knob(blink.config, "dip", 100f, 0f..2f))
        val wide = captured.any { it.type == "eye.wide" }
        val smile = captured.any { it.type == "eye.smile" }
        val owners = captured.map { owner -> RigModifierInstance(requireNotNull(owner.instanceId), owner.type, owner.version,
            owner.targets, config = owner.config, outputAliases = owner.outputAliases, plugin = owner.plugin) }
        // Real ordinary eye/brow axes prevent local lowering from retyping an upstream parameter.
        // Their positions are irrelevant to the brow; detached copies are read-only axis witnesses.
        val ordinaryAxes = setOf("ParamEyeLOpen", "ParamEyeROpen", "ParamEyeBallX", "ParamEyeBallY",
            "ParamBrowLY", "ParamBrowRY", "ParamBrowLAngle", "ParamBrowRAngle")
        val witnesses = instance.targets.map { requireNotNull(input.mesh(it.id)) }
        val ordinaryMeshes = witnesses.filter { mesh -> mesh.id !in source.targets &&
            mesh.offsets?.axes.orEmpty().any { it.parameter in ordinaryAxes } }.map { it.copy(parent = null, shapes = emptyList()) }
        val ordinaryWarps = instance.targets.flatMap { input.parentsOfMesh(it.id) }.distinctBy { it.id }
            .filterIsInstance<Deformer.Warp>().filter { warp -> warp.lattice?.axes.orEmpty().any { it.parameter in ordinaryAxes } &&
                branch.deformers.none { it.id == warp.id } }.map { it.copy(parent = null, shapes = emptyList()) }
        val updated = source.targets.map { (id, target) ->
            checkpoint()
            val live = requireNotNull(input.mesh(id))
            val ancestry = input.parentsOfMesh(id)
            val existing = ancestry.associateBy { it.id }
            val side = if (target.fit.side == Side.LEFT) "L" else "R"
            for (suffix in listOf("Y", "Angle")) require(input.parameter("ParamBrow$side$suffix")?.let {
                !it.blend && it.min == -1f && it.max == 1f && it.default == 0f
            } == true) { "Brow spring v7 requires ordinary fitted brow height and angle" }
            val raw = target.raw
            val positions = FloatArray(raw.rigPositions.size) { i ->
                if (i % 1024 == 0) checkpoint()
                if (target.parent == null) raw.rigPositions[i]
                else if (i % 2 == 0) (raw.rigPositions[i] - target.frame.left) / target.frame.width.coerceAtLeast(1e-4f)
                else (raw.rigPositions[i] - target.frame.top) / target.frame.height.coerceAtLeast(1e-4f)
            }
            val travel = requireNotNull(target.eye).let { it.opening * it.width * lift }
            fun lower(spring: Boolean): RigIR {
                val rig = BrowRig(target.fit, travel, options.copy(spring = spring), checkpoint)
                val grid = rig.grid(raw.rigPositions, target.frame, ParameterId("ParamBrow${side}Y"), ParameterId("ParamBrow${side}Angle"))
                val shapes = rig.shapes(raw.rigPositions, target.frame, ParameterId("ParamBrow${side}X"), ParameterId("ParamBrow${side}Form"),
                    EyeModules.open(target.fit.side), wide, if (smile) ParameterId("ParamEye${side}Smile") else null,
                    if (spring) ParameterId("ParamBrow${side}Spring") else null) +
                    turn.shapes(raw.rigPositions, target.frame, requireNotNull(DepthTurnMotion.shell("EYEBROW", null)),
                        ParameterId("ParamAngleX"), ParameterId("ParamAngleY"))
                val native = live.copy(id = nativeIds.getValue(id).jsonPrimitive.content, name = names.getValue(id).jsonPrimitive.content,
                    parent = target.parent, geometry = MeshGeometry(Floats.wrap(positions), Floats.wrap(raw.atlasUvs), Ints.wrap(raw.triangles)),
                    offsets = offsets(grid), shapes = emptyList(), channels = emptyMap())
                val model = PuppetIr.toPuppet(RigIR(input.canvas, controls, meshes = listOf(native) + ordinaryMeshes,
                    deformers = branch.deformers + ordinaryWarps))
                val drawable = model.drawables.first()
                val rest = meshGridDefaultDeltas(drawable) { parameter ->
                    if (parameter.raw in setOf("ParamEyeLOpen", "ParamEyeROpen")) 1f else 0f
                }
                val lowered = BlendShapeLowering({ headTurn, name -> (if (headTurn) turnName else shapeName).replace("{meshName}", name) }, checkpoint)
                    .lawful(model.copy(drawables = listOf(drawable.copy(blendShapes = shapes.map { binding -> binding.copy(forms = binding.forms.map { form ->
                        form?.let {
                            val base = rest?.takeIf { deltas -> deltas.size == it.positionDeltas.size &&
                                binding.parameterId.raw in setOf("ParamAngleX", "ParamAngleY", "ParamAngleZ") }
                            val deltas = if (base == null) it.positionDeltas else FloatArray(base.size) { i -> it.positionDeltas[i] + base[i] }
                            MeshForm(deltas, drawable.drawOrder, drawable.opacity, drawable.multiplyColor, drawable.screenColor)
                        }
                    }) })) + model.drawables.drop(1)))
                require(lowered.parameters == model.parameters) { "Brow spring v7 cannot change upstream parameter kinds" }
                val result = PuppetIr.toIr(lowered)
                val mesh = result.meshes.first().copy(id = id)
                val nodes = result.deformers.associateBy { it.id }
                val ancestors = generateSequence(mesh.parent) { nodes.getValue(it).parent }.map(nodes::getValue).toList()
                return RigIR(result.canvas, result.parameters, meshes = listOf(mesh), deformers = ancestors)
            }
            val before = lower(false)
            val after = lower(true)
            require(before.deformers == after.deformers) { "Brow spring v7 cannot change existing carriers" }
            val previous = before.meshes.single()
            val next = after.meshes.single()
            val springId = "ParamBrow${side}Spring"
            require(previous.parent == next.parent && previous.geometry == next.geometry && previous.offsets == next.offsets &&
                previous.channels == next.channels && next.shapes.count { it.parameter == springId } == 1 &&
                next.shapes.filterNot { it.parameter == springId } == previous.shapes && previous.shapes.none { it.parameter == springId }) {
                "Brow spring v7 may add exactly its spring shape without changing ordinary motion"
            }
            val aliases = before.deformers.associate { carrier ->
                val matches = owners.filter { ModifierTarget("mesh", id) in it.targets }.map {
                    ScaffoldBinding.deformerId(it, id, carrier.id, shared = true)
                }.distinct().filter { it in existing }
                require(matches.size == 1) { "Brow spring v7 cannot resolve one upstream owner for ${carrier.id}" }
                DeformerId(carrier.id) to DeformerId(matches.single())
            }
            require(aliases.values.map { it.raw }.toSet() == existing.keys && aliases.values.distinct().size == aliases.size) {
                "Brow spring v7 requires the exact complete preceding brow branch"
            }
            val parts = before.deformers.mapNotNull { carrier ->
                val part = existing.getValue(aliases.getValue(DeformerId(carrier.id)).raw).part
                carrier.part?.let { PartId(it) to PartId(requireNotNull(part)) }
            }.toMap()
            val remap = ModelIdRemap(deformers = aliases, parts = parts)
            for (carrier in PuppetIr.toPuppet(before).deformers) {
                val expected = PuppetIr.deformerToIr(remap.deformer(carrier))
                require(existing.getValue(expected.id) == expected) { "Brow spring v7 requires the exact preceding brow carrier: ${expected.id}" }
            }
            val expected = PuppetIr.meshToIr(remap.drawable(PuppetIr.toPuppet(before).drawables.single()))
            require(live.parent == expected.parent && live.geometry == expected.geometry && live.offsets == expected.offsets &&
                live.channels == expected.channels && live.shapes == expected.shapes) {
                "Brow spring v7 requires the exact preceding brow topology, grid and shapes: $id"
            }
            val result = PuppetIr.meshToIr(remap.drawable(PuppetIr.toPuppet(after).drawables.single()))
            ModifierObjectUpdate(live, live.copy(shapes = result.shapes))
        }
        val tree = if (added.isEmpty()) null else {
            val before = input.parameterTree
            val roots = before.ifEmpty { parameters.map { ParameterNode.Param(it.id) } }
            val id = instance.outputId("parameter-folder:ParamGroupPhysics")
            val children = added.map { ParameterNode.Param(it.id) }
            val next = append(roots, id, children) ?: (roots + ParameterNode.Group(id,
                context.values.getValue("physics_group").jsonPrimitive.content, true, children))
            ModifierFieldChange(before, next)
        }
        val physics = if (instance.config["physicsEnabled"]?.jsonPrimitive?.boolean == false) ModifierObjectChanges()
        else BrowSpringPhysics.changes(input.physics, instance, controls.mapTo(hashSetOf()) { it.id },
            context.values.getValue("physics_names").jsonObject.mapKeys { Side.valueOf(it.key) }.mapValues { it.value.jsonPrimitive.content })
        return ModifierModelChanges(parameters = ModifierObjectChanges(added = added, reused = reused),
            meshes = ModifierObjectChanges(updated = updated), parameterTree = tree, physicsGroups = physics)
    }

    private fun offsets(grid: KeyformGrid<MeshDeltaForm>) = KeyGrid(
        grid.axes.map { KeyAxis(it.parameterId.raw, Floats.wrap(it.keys)) },
        grid.cells.map { KeyCell(Ints.wrap(it.coordinate), MeshOffsets(Floats.wrap(it.form.positionDeltas))) })

    private fun append(nodes: List<ParameterNode>, id: String, children: List<ParameterNode>): List<ParameterNode>? {
        for ((index, node) in nodes.withIndex()) {
            if (node !is ParameterNode.Group) continue
            val next = if (node.id == id) node.children + children else append(node.children, id, children) ?: continue
            return nodes.toMutableList().apply { set(index, node.copy(children = next)) }
        }
        return null
    }

    private fun knob(config: JsonObject, key: String, default: Float, range: ClosedFloatingPointRange<Float>) =
        ((config["values"]?.jsonObject?.get(key)?.jsonPrimitive?.float ?: default) / 100f).also { require(it in range) }

    companion object {
        const val TYPE = "brow.spring"
        const val VERSION = 7
        const val ENTRYPOINT = "builtin.62726f772e737072696e67.v7/spring"
        val requires = setOf("rig.scaffold", "face/fitted", "eye/fitted", "brow/fitted", "brow.shape", "brow.lid-coupling", "brow.blink")
        val eyeAddons = mapOf("eye.smile" to 3, "eye.wide" to 3, "eye.gaze-lid" to 3, "eye.emotion" to 3,
            "eye.highlight" to 3, "eye.aftershock" to 4, "eye.squash" to 4, "eye.lid-fade" to 3)
        val unsupported = setOf(TYPE, "face.depth-turn", "mouth.cheek-lid")
        val observed = requires + eyeAddons.keys + unsupported + setOf("body/legacy", "face/legacy", "eye/legacy", "brow/legacy",
            "mouth/legacy", "mouth/fitted", "hair/legacy", "mouth.outline", "mouth.vowel", "mouth.capture", "mouth.tongue", "mouth.jaw")
        val writes = setOf("mesh", "parameter", "hierarchy", "physics")
        private val extra = setOf("providers", "head_turn", "native_ids", "mesh_names", "turn_name", "shape_name",
            "spring_names", "physics_names", "physics_group")
        fun source(context: ModifierAlgorithmContext, checkpoint: () -> Unit = {}) = BrowShapeInputs.decode(
            ModifierAlgorithmContext(JsonObject(context.values - extra), context.resources - "source_branch"), checkpoint)
        fun parameters(context: ModifierAlgorithmContext): List<Parameter> {
            val source = source(context)
            val sides = source.targets.values.mapTo(hashSetOf()) { it.fit.side }
            val names = context.values.getValue("spring_names").jsonObject
            return listOf(Side.LEFT, Side.RIGHT).map { side ->
                val blend = side in sides
                Parameter("ParamBrow${if (side == Side.LEFT) "L" else "R"}Spring", names.getValue(side.name).jsonPrimitive.content,
                    -1f, 1f, 0f, blend = blend, keys = if (blend) Floats.values(-1f, 0f, 1f) else null)
            }
        }
    }
}

object BrowSpringV7Worker {
    @JvmStatic fun main(args: Array<String>) = AlgorithmWorker.run(mapOf(
        (BrowSpringV7Algorithm.ENTRYPOINT to BrowSpringV7Algorithm.VERSION) to BrowSpringV7Algorithm()))
}
