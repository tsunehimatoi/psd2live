package io.github.psd2live.algorithms.binding

import io.github.psd2live.algorithm.worker.AlgorithmWorker
import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.compile.RigIrBinary
import io.github.psd2live.format.model.*
import io.github.psd2live.targets.cubism.PuppetIr
import kotlinx.serialization.json.*
import org.umamo.edit.VertexSource
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PartId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.MeshForm
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.MeshDeltaForm

object BrowShapeV6Worker {
    @JvmStatic fun main(args: Array<String>) = AlgorithmWorker.run(mapOf(
        (BrowShapeV6Algorithm.ENTRYPOINT to BrowShapeV6Algorithm.VERSION) to BrowShapeV6Algorithm(),
    ))
}

/** Applies the fitted brow shape in source space and lowers it with the witnessed head turn. */
class BrowShapeV6Algorithm : ReadingModifierAlgorithmPlugin {

    override fun changes(input: ModifierEvaluationInput, instance: RigModifierInstance, checkpoint: () -> Unit): ModifierModelChanges {
        require(instance.version == VERSION && instance.targets.all { it.kind == "mesh" })
        val scaffoldId = instance.config.getValue("scaffold")
        fun provider(type: String, version: Int, id: JsonElement): ModifierUpstreamCapability =
            requireNotNull(input.providers(type).distinctBy { it.instanceId }.singleOrNull {
                it.instanceId == id.jsonPrimitive.content && it.type == type && it.version == version &&
                    it.targets.containsAll(instance.targets) && (type == "rig.scaffold" || it.config["scaffold"] == scaffoldId)
            }) { "Brow shape v6 requires its captured $type@$version provider" }
        provider("rig.scaffold", 2, scaffoldId)
        val fitted = provider("brow/fitted", 4, instance.config.getValue("base"))
        provider("face/fitted", 3, input.context.values.getValue("face"))
        val eye = provider("eye/fitted", 5, input.context.values.getValue("eye"))
        require(input.upstream.none { it.config["scaffold"] == scaffoldId && it.targets.any(instance.targets::contains) &&
            it.capability in setOf(TYPE, "brow.spring", "brow.blink", "brow.lid-coupling", "face.depth-turn") }) {
            "Brow shape v6 must precede other brow motion modifiers and cannot use a separate depth turn"
        }
        val context = input.context
        val turn = RigIrBinary.decode(context.resources.getValue("brow_turn").read())
        val nativeIds = context.values.getValue("native_ids").jsonObject
        val turnName = context.values.getValue("turn_name").jsonPrimitive.content
        val fittedContext = ModifierAlgorithmContext(
            JsonObject(context.values - setOf("native_ids", "turn_name", "face", "eye")), context.resources - "brow_turn", input.upstream)
        val source = BrowShapeInputs.decode(fittedContext, checkpoint)
        require(source.targets.size == 1 && source.eyeProvider == eye.instanceId && source.targets.values.single().eye != null) {
            "Brow shape v6 requires one fitted brow paired with its captured eye"
        }
        val measured = turn.meshes.associateBy { it.id }
        require(turn.meshes.all { mesh -> mesh.shapes.isNotEmpty() && mesh.shapes.all {
            it.parameter in setOf("ParamAngleX", "ParamAngleY", "ParamAngleZ")
        } }) { "Brow shape v6 requires the captured fallback head turn" }
        require(source.targets.keys == measured.keys && measured.keys == nativeIds.keys &&
            source.targets.keys.all { ModifierTarget("mesh", it) in instance.targets })
        val parameters = input.parameters
        val brows = source.targets.map { (id, target) ->
            val live = requireNotNull(input.mesh(id))
            val raw = measured.getValue(id)
            live.copy(parent = target.parent, geometry = raw.geometry, offsets = raw.offsets, shapes = emptyList())
        }
        val lift = value(fitted.config, "lift", 45f, .2f..1f)
        val strength = value(instance.config, "strength", 100f, 0f..2f)
        val controls = BrowShapeV6Algorithm.parameters(source)
        val reused = controls.mapNotNull { parameter -> input.parameter(parameter.id)?.also {
            require(it.copy(name = parameter.name) == parameter) { "Incompatible brow shape parameter ${parameter.id}" }
        } }
        val additions = controls.filter { next -> reused.none { it.id == next.id } }
        val shaped = brows.map { mesh -> ModifierObjectUpdate(mesh,
            bind(input, mesh, source.targets.getValue(mesh.id), lift, strength, checkpoint)) }
        val tree = if (additions.isEmpty()) null else {
            val id = instance.outputId("parameter-folder:ParamGroupBrows")
            val children = additions.map { ParameterNode.Param(it.id) }
            val root = input.parameterTree.ifEmpty { parameters.map { ParameterNode.Param(it.id) } }
            val (next, found) = append(root, id, children)
            ModifierFieldChange(input.parameterTree, if (found) next else next + ParameterNode.Group(id, source.groupName, true, children))
        }
        val shapeChanges = ModifierModelChanges(parameters = ModifierObjectChanges(added = additions, reused = reused),
            meshes = ModifierObjectChanges(updated = shaped), parameterTree = tree)
        val afterParameters = parameters + shapeChanges.parameters.added
        val owners = input.upstream.distinctBy { it.instanceId }.mapNotNull { provider ->
            provider.instanceId?.let { id ->
                RigModifierInstance(id, provider.type, provider.version, provider.targets,
                    config = provider.config, outputAliases = provider.outputAliases, plugin = provider.plugin)
            }
        }
        val scaffold = owners.single { it.type == "rig.scaffold" }
        val added = mutableListOf<Deformer>()
        val updated = mutableListOf<ModifierObjectUpdate<Deformer>>()
        val meshes = shapeChanges.meshes.updated.mapNotNull { (before, after) ->
            checkpoint()
            val live = requireNotNull(input.mesh(before.id))
            val ancestry = input.parentsOfMesh(live.id)
            val native = measured.getValue(live.id)
            val nativeDeformers = turn.deformers.associateBy { it.id }
            val branch = generateSequence(native.parent) { nativeDeformers.getValue(it).parent }.map(nativeDeformers::getValue).toList()
            val canonical = nativeIds.getValue(live.id).jsonPrimitive.content
            fun lower(mesh: Mesh, controls: List<Parameter>): PuppetModel {
                val withTurn = mesh.copy(id = canonical, parent = native.parent, shapes = mesh.shapes + native.shapes)
                val model = PuppetIr.toPuppet(RigIR(input.canvas, controls, deformers = branch, meshes = listOf(withTurn)))
                val drawable = model.drawables.single()
                val neutral = drawable.copy(blendShapes = drawable.blendShapes.map { binding ->
                    binding.copy(forms = binding.forms.map { form ->
                        form?.let { MeshForm(it.positionDeltas, live.drawOrder, live.opacity, drawable.multiplyColor, drawable.screenColor) }
                    })
                })
                val lowered = BlendShapeLowering({ head, name -> require(head); turnName.replace("{meshName}", name) },
                    checkpoint).lawful(model.copy(drawables = listOf(neutral)))
                require(lowered.parameters == model.parameters)
                return lowered.copy(drawables = listOf(lowered.drawables.single().copy(id = org.umamo.runtime.model.DrawableId(live.id))))
            }
            val prior = lower(before, parameters)
            val next = lower(after, afterParameters)
            val target = ModifierTarget("mesh", live.id)
            val existing = ancestry.associateBy { it.id }
            val aliases = linkedMapOf<DeformerId, DeformerId>()
            for (carrier in prior.deformers) {
                val id = owners.asSequence().filter { target in it.targets }
                    .map { ScaffoldBinding.deformerId(it, live.id, carrier.id.raw, shared = true) }.distinct()
                    .singleOrNull { it in existing }
                aliases[carrier.id] = DeformerId(requireNotNull(id) { "Cannot resolve preceding brow frame: ${carrier.id.raw}" })
            }
            for (carrier in next.deformers) {
                aliases.putIfAbsent(carrier.id, DeformerId(ScaffoldBinding.deformerId(instance, live.id, carrier.id.raw, shared = true)))
            }
            require(prior.drawables.single().parentDeformerId?.let(aliases::get)?.raw == live.parent)
            val parts = prior.deformers.mapNotNull { carrier ->
                val livePart = existing[aliases.getValue(carrier.id).raw]?.part ?: return@mapNotNull null
                carrier.partId?.let { it to PartId(livePart) }
            }.toMap()
            val remap = ModelIdRemap(deformers = aliases, parts = parts)
            val expectedMesh = PuppetIr.meshToIr(remap.drawable(prior.drawables.single()))
            require(live.parent == expectedMesh.parent && live.geometry == expectedMesh.geometry &&
                live.offsets == expectedMesh.offsets && live.shapes == expectedMesh.shapes && live.channels.isEmpty()) {
                "Brow shape v6 requires the exact preceding brow topology and height grid: ${live.id}"
            }
            for (carrier in prior.deformers) {
                val expected = PuppetIr.deformerToIr(remap.deformer(carrier))
                require(existing[expected.id] == expected) {
                    "Brow shape v6 requires the exact preceding brow frame: ${expected.id}"
                }
            }
            val liveParts = parts.values.distinct().map { requireNotNull(input.part(it.raw)) }
            val current = PuppetIr.toPuppet(RigIR(input.canvas, afterParameters, parts = liveParts, deformers = ancestry, meshes = listOf(live),
                textures = input.textures, authoring = input.authoring))
            val transfer = object : BindingMerge.Source {
                override fun provider(instance: RigModifierInstance) = scaffold
                override fun transition(instance: RigModifierInstance, target: ModifierTarget, checkpoint: () -> Unit) = prior to next
                override fun resolveParameter(instance: RigModifierInstance, before: Parameter, declared: Parameter): Parameter? = null
                override fun static(model: PuppetModel) = BindingProjection.retain(model, emptySet(), mapOf(ParameterId("ParamMouthOpenY") to 1f))
                override fun onlyMasksChange(before: PuppetModel, after: PuppetModel, target: ModifierTarget, branch: Set<DeformerId>) = false
                override fun deformerIds(instance: RigModifierInstance, target: ModifierTarget, checkpoint: () -> Unit) = aliases
                override fun partIds(instance: RigModifierInstance, target: ModifierTarget, checkpoint: () -> Unit) = parts
                override fun requiredParts(model: PuppetModel, target: ModifierTarget) = emptySet<PartId>()
                override fun partId(instance: RigModifierInstance, mesh: String, canonical: String, shared: Boolean) =
                    requireNotNull(parts[PartId(canonical)]).raw
                override fun vertexSources(from: PuppetModel, original: Drawable, to: PuppetModel, target: Drawable,
                                           checkpoint: () -> Unit): List<VertexSource> =
                    BodyVertexSources.match(to, original, target, checkpoint)
            }
            val geometryOnly = instance.copy(targets = listOf(target), outputAliases = instance.outputAliases.filterKeys {
                !it.startsWith("parameter-folder:")
            })
            val merged = BindingMerge.apply(current, geometryOnly, transfer, checkpoint)
            require(merged.deformers.map { it.id.raw }.containsAll(existing.keys))
            for (deformer in merged.deformers.map(PuppetIr::deformerToIr)) {
                val original = existing[deformer.id]
                if (original == null) added += deformer
                else if (original != deformer) {
                    val update = ModifierObjectUpdate(original, deformer)
                    BrowShapeCarrierScope.check(input, listOf(update), live.id, existing.keys, checkpoint)
                    updated += update
                }
            }
            val result = PuppetIr.meshToIr(merged.drawables.single())
            ModifierObjectUpdate(live, result).takeIf { live != result }
        }
        return shapeChanges.copy(meshes = ModifierObjectChanges(updated = meshes),
            deformers = ModifierObjectChanges(added = added, updated = updated))
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
        require(mesh.parent == target.parent && geometry == MeshGeometry(Floats.wrap(positions),
            Floats.wrap(raw.atlasUvs), Ints.wrap(raw.triangles))) { "Brow shape v6 requires the fitted source mesh and parent frame" }

        val travel = target.eye?.let { it.opening * it.width * lift } ?: (target.fit.length * .25f * lift / .45f)
        val prior = BrowRig(target.fit, travel, BrowOptions(), checkpoint).grid(raw.rigPositions, target.frame, height, angle)
        require(mesh.offsets == offsets(prior) && mesh.shapes.isEmpty() && mesh.channels.isEmpty()) {
            "Brow shape v6 requires the unedited fitted height grid, without earlier mesh motion or drawing tracks"
        }
        val rig = BrowRig(target.fit, travel, BrowOptions(shape = true, shapeStrength = strength), checkpoint)
        val grid = rig.grid(raw.rigPositions, target.frame, height, angle)
        val shapes = rig.shapes(raw.rigPositions, target.frame, ParameterId("ParamBrow${side}X"),
            ParameterId("ParamBrow${side}Form"), EyeModules.open(target.fit.side), wide = false).map { shape ->
            BlendBinding(shape.parameterId.raw, Floats.wrap(shape.keys), shape.neutralIndex, shape.forms.map { form ->
                form?.let { MeshShape(Floats.wrap(it.positionDeltas), mesh.drawOrder, mesh.opacity, mesh.multiply, mesh.screen) }
            })
        }
        return mesh.copy(offsets = offsets(grid), shapes = shapes)
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
        const val VERSION = 6
        const val ENTRYPOINT = "builtin.62726f772e7368617065.v6/shape"

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
