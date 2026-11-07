package io.github.psd2live.algorithms.binding

import io.github.psd2live.algorithm.worker.AlgorithmWorker
import io.github.psd2live.algorithms.physics.PhysicsGroups
import io.github.psd2live.core.PhysicsInput
import io.github.psd2live.core.PhysicsNormalization
import io.github.psd2live.core.PhysicsOutput
import io.github.psd2live.core.PhysicsSegment
import io.github.psd2live.core.PhysicsSourceType
import io.github.psd2live.core.RigPhysicsEdit
import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.model.*
import io.github.psd2live.format.model.Parameter
import io.github.psd2live.format.model.PhysicsGroup
import io.github.psd2live.targets.cubism.PuppetIr
import kotlinx.serialization.json.*
import org.umamo.render.eval.drawableSpaceMapping
import org.umamo.runtime.eval.colorAt
import org.umamo.runtime.eval.meshGridDefaultDeltas
import org.umamo.runtime.eval.scalarAt
import org.umamo.runtime.model.BlendShapeBinding
import org.umamo.runtime.model.FormChannel
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.MeshForm
import org.umamo.runtime.model.ParameterGroupId
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.ParameterNode as PuppetParameterNode

object EyeAftershockV4Worker {
    @JvmStatic fun main(args: Array<String>) = AlgorithmWorker.run(mapOf(
        (EyeAftershockV4Algorithm.ENTRYPOINT to EyeAftershockV4Algorithm.VERSION) to EyeAftershockV4Algorithm(),
    ))
}

/** Replays fitted head and lid lowering before adding blink spring shapes and physics. */
class EyeAftershockV4Algorithm : ReadingModifierAlgorithmPlugin {
    override fun changes(input: ModifierEvaluationInput, instance: RigModifierInstance, checkpoint: () -> Unit): ModifierModelChanges {
        require(instance.version == VERSION && instance.targets.all { it.kind == "mesh" })
        val captured = EyeSmileInputs.decode(sourceContext(input.context), checkpoint)
        val source = captured.measurements
        val scaffold = instance.config.getValue("scaffold")
        val targets = instance.targets.toSet()
        require(source.targets.keys.all { ModifierTarget("mesh", it) in targets })
        require(input.providers("rig.scaffold").any {
            it.instanceId == scaffold.jsonPrimitive.content && it.version == 2 && it.targets.containsAll(targets)
        }) { "Blink aftershock v4 requires a version 2 source scaffold" }
        val fitted = requireNotNull(input.providers("eye/fitted").singleOrNull {
            it.instanceId == instance.config.getValue("base").jsonPrimitive.content && it.targets.containsAll(targets)
        }) { "Missing fitted eye provider" }
        require(fitted.type == "eye/fitted" && fitted.config["scaffold"] == scaffold && fitted.version in setOf(2, 4, 5)) {
            "Blink aftershock v4 requires compatible fitted eyes on the same scaffold"
        }
        val preceding = input.upstream.filter { it.config["scaffold"] == scaffold && it.targets.any(targets::contains) }
        require(preceding.none { it.capability == TYPE }) {
            "Blink aftershock v4 cannot replace an earlier aftershock on the same mesh"
        }
        require(preceding.none { it.capability in coupled }) {
            "Blink aftershock v4 cannot compose with preceding coupled lid bindings"
        }
        val head = input.context.values["head_turn"]?.jsonObject
        val turn = head?.let { values ->
            require(preceding.any { it.capability in headProviders && it.instanceId == values.getValue("provider").jsonPrimitive.content }) {
                "Blink aftershock v4 requires its captured head turn provider"
            }
            val shell = values.getValue("shell").jsonArray.map { it.jsonPrimitive.float }
            require(shell.size == 7 && shell.all(Float::isFinite) && shell[2] > 0f && shell[3] > 0f)
            HeadTurn(shell[0], shell[1], shell[2], shell[3], shell[4], shell[5], shell[6], checkpoint)
        }

        val fits = fits(captured, checkpoint)
        require(fits.isNotEmpty() && captured.art.layers.any { it.tag == "EYELASH" }) {
            "Blink aftershock v4 needs readable fitted eye artwork"
        }
        val values = fitted.config["values"]?.jsonObject.orEmpty()
        val softness = knob(instance.config["values"]?.jsonObject.orEmpty(), "softness", 100f, 0f..200f)
        val carriers = input.context.values.getValue("carriers").jsonObject
        val artwork = EyeArtworkFitting.fit(captured.art, source.artworkStamps, fits, source.corrections) { x, y ->
            source.alignment?.point(x, y) ?: (x to y)
        }
        val motions = HashMap<EyeOptions, FittedEyeMotion>()
        fun motion(options: EyeOptions) = motions.getOrPut(options) {
            FittedEyeMotion(fits.mapValues { (side, fit) ->
                EyeRig(fit.copy(artwork = artwork[side]), knob(values, "lowerShare", 5f, 0f..50f),
                    knob(values, "roundness", 100f, 40f..160f), knob(values, "lashSquash", 60f, 20f..100f),
                    options.smileArc, options.smilePush, options.wideLift)
            }, options)
        }

        val declared = parameters(source, fits.keys)
        val live = input.parameters
        val additions = declared.filter { parameter ->
            val existing = input.parameter(parameter.id)
            require(existing == null || existing.copy(name = parameter.name) == parameter) {
                "Incompatible blink aftershock parameter: ${parameter.id}"
            }
            existing == null
        }
        val ordinaryAxes = setOf("ParamEyeBallX", "ParamEyeBallY")
        val ordinaryMeshes by lazy {
            targets.mapNotNull { input.mesh(it.id) }.filter { mesh ->
                mesh.offsets?.axes.orEmpty().any { it.parameter in ordinaryAxes }
            }.map { it.copy(shapes = emptyList()) }
        }
        val ordinaryWarps by lazy {
            targets.flatMap { input.parentsOfMesh(it.id) }.distinctBy { it.id }
                .filterIsInstance<Deformer.Warp>().filter { warp ->
                    warp.lattice?.axes.orEmpty().any { it.parameter in ordinaryAxes }
                }.map { it.copy(shapes = emptyList()) }
        }
        val updates = instance.targets.mapNotNull { target ->
            checkpoint()
            val material = source.targets[target.id] ?: return@mapNotNull null
            val layer = captured.art.layerForTarget(target.id)
            val lid = EyeModules.meshOf(layer.tag, layer.role) ?: return@mapNotNull null
            val raw = captured.topology.getValue(material.layer)
            val positions = raw.rigPositions
            val frame = material.frame.let { Bounds(it.left, it.top, it.right, it.bottom) }
            val side = Side.valueOf(layer.side)
            if (side != Side.NONE && side !in fits) return@mapNotNull null
            fun previous(type: String) = preceding.lastOrNull { it.capability == type && target in it.targets }
            fun values(type: String) = previous(type)?.config?.get("values")?.jsonObject.orEmpty()
            val options = EyeOptions(aftershock = true, softness = softness,
                wide = previous("eye.wide") != null, wideLift = knob(values("eye.wide"), "lift", 10f, 0f..80f),
                smile = previous("eye.smile") != null, smileArc = .12f * knob(values("eye.smile"), "arch", 100f, 0f..200f),
                smilePush = knob(values("eye.smile"), "push", 50f, 0f..100f),
                gaze = previous("eye.gaze-lid") != null, gazeFollow = knob(values("eye.gaze-lid"), "follow", 100f, 0f..200f),
                emotion = previous("eye.emotion") != null, emotionStrength = knob(values("eye.emotion"), "strength", 100f, 0f..200f))
            val motion = motion(options)
            val vertexOwners = if (side == Side.NONE) List(positions.size / 2) { i ->
                motion.sideAt(layer.id, positions[i * 2], positions[i * 2 + 1])
            } else emptyList()
            fun shapes(settings: EyeOptions) = motion.rigs.filterKeys { side == Side.NONE || side == it }.flatMap { (owner, eye) ->
                val generated = EyeModules.lidShapes(lid, owner, positions, frame, eye, settings, layer.id)
                if (side != Side.NONE) generated else generated.map { shape -> shape.copy(forms = shape.forms.map { form ->
                    form?.let { MeshForm(FloatArray(positions.size) { i -> if (vertexOwners[i / 2] == owner) it.positionDeltas[i] else 0f }) }
                }) }
            }
            val mesh = requireNotNull(input.mesh(target.id))
            val points = Floats.wrap(FloatArray(positions.size) { i ->
                val point = positions[i]
                if (material.parent == null) point
                else if (i % 2 == 0) (point - frame.left) / frame.width.coerceAtLeast(1e-4f)
                else (point - frame.top) / frame.height.coerceAtLeast(1e-4f)
            })
            val grid = if (side == Side.NONE) EyeModules.pairedLidGrid(lid, layer.id, positions, frame, motion)
            else EyeModules.lidGrid(lid, side, positions, frame, motion.rigs.getValue(side), options, layer.id)
            val offsets = grid?.let {
                KeyGrid(it.axes.map { axis -> KeyAxis(axis.parameterId.raw, Floats.wrap(axis.keys)) },
                    it.cells.map { cell -> KeyCell(Ints.wrap(cell.coordinate), MeshOffsets(Floats.wrap(cell.form.positionDeltas))) })
            }
            val carrier = carriers.getValue(target.id).jsonObject
            val branch = input.parentsOfMesh(target.id)
            val baseBranch = if (material.parent == null) emptyList() else branch.dropWhile { it.id != material.parent }
            require(material.parent == null || baseBranch.isNotEmpty()) { "Missing captured aftershock lid frame: ${target.id}" }
            val upstreamWarps = branch.take(branch.size - baseBranch.size).asReversed()
            val headFolded = mesh.offsets?.axes.orEmpty().any { it.parameter in headAxes } ||
                mesh.shapes.any { it.parameter in headAxes } ||
                upstreamWarps.filterIsInstance<Deformer.Warp>().any { warp -> warp.lattice?.axes.orEmpty().any { it.parameter in headAxes } }
            val base = mesh.copy(id = carrier.getValue("id").jsonPrimitive.content, parent = material.parent,
                geometry = MeshGeometry(points, Floats.wrap(raw.atlasUvs), Ints.wrap(raw.triangles)),
                offsets = offsets, shapes = emptyList())
            val model = PuppetIr.toPuppet(RigIR(input.canvas, live + additions,
                meshes = listOf(base) + ordinaryMeshes.filter { it.id != mesh.id },
                deformers = baseBranch + ordinaryWarps.filter { warp -> branch.none { it.id == warp.id } }))
            val drawable = model.drawables.first()
            val mapping = requireNotNull(drawableSpaceMapping(model, emptyMap(), drawable.id))
            val world = mapping.localToWorld(points.shared())
            require((world.indices step 2).all { i ->
                val x = positions[i]
                val y = positions[i + 1]
                val canvas = source.alignment?.canvas(x, y) ?: (x to y)
                kotlin.math.abs(world[i] - canvas.first) <= .005f && kotlin.math.abs(-world[i + 1] - canvas.second) <= .005f
            }) { "Blink aftershock v4 cannot migrate an edited neutral lid frame: ${target.id}" }
            val name = carrier.getValue("name").jsonPrimitive.content
            val headShapes = if (!headFolded) emptyList() else {
                requireNotNull(turn) { "Blink aftershock v4 needs captured head turn measurements: ${target.id}" }
                val shell = requireNotNull(DepthTurnMotion.shell(layer.tag, layer.role))
                val rest = meshGridDefaultDeltas(drawable) { id -> if (id.raw in openIds) 1f else 0f }
                val turns = turn.shapes(positions, frame, shell, ParameterId("ParamAngleX"), ParameterId("ParamAngleY"))
                neutralized(turns, drawable, live).map { binding ->
                    binding.copy(forms = binding.forms.map { form -> form?.let {
                        if (rest == null) it else MeshForm(FloatArray(rest.size) { i -> it.positionDeltas[i] + rest[i] },
                            it.drawOrder, it.opacity, it.multiplyColor, it.screenColor)
                    } })
                }
            }
            fun lower(settings: EyeOptions) = BlendShapeLowering({ isHead, meshName ->
                if (isHead) requireNotNull(head).getValue("turn_name").jsonPrimitive.content.replace("{meshName}", meshName)
                else name
            }, checkpoint).lawful(model.copy(drawables = listOf(drawable.copy(
                blendShapes = neutralized(shapes(settings), drawable, live) + headShapes)) + model.drawables.drop(1)))
            val before = lower(options.copy(aftershock = false))
            val after = lower(options)
            val oldCarriers = before.deformers.drop(model.deformers.size)
            val priorDeformers = before.deformers.map(PuppetIr::deformerToIr)
            require(before.parameters == model.parameters && after.parameters == model.parameters &&
                priorDeformers.take(model.deformers.size) == model.deformers.map(PuppetIr::deformerToIr) &&
                priorDeformers == after.deformers.map(PuppetIr::deformerToIr)) {
                "Blink aftershock v4 cannot change existing lid carriers: ${target.id}"
            }
            require(oldCarriers.size == upstreamWarps.size) { "Cannot reproduce preceding aftershock lid warp slots: ${target.id}" }
            val previousIds = oldCarriers.zip(upstreamWarps).associate { (native, current) -> native.id to DeformerId(current.id) }
            val remap = ModelIdRemap(deformers = previousIds)
            val prior = PuppetIr.meshToIr(remap.drawable(before.drawables.first()))
            require(prior.parent == mesh.parent && prior.geometry == mesh.geometry && prior.offsets == mesh.offsets &&
                prior.shapes == mesh.shapes && prior.channels == mesh.channels) {
                "Cannot reproduce preceding fitted lid geometry or channels for aftershock: ${target.id}"
            }
            for (item in oldCarriers) {
                val expected = PuppetIr.deformerToIr(remap.deformer(item))
                val actual = branch.single { it.id == expected.id }
                require(actual is Deformer.Warp && expected is Deformer.Warp && actual == expected.copy(part = actual.part)) {
                    "Cannot reproduce preceding aftershock lid carrier: ${actual.id}"
                }
            }
            val result = PuppetIr.meshToIr(remap.drawable(after.drawables.first()))
            require(result.parent == prior.parent && result.geometry == prior.geometry && result.offsets == prior.offsets &&
                result.channels == prior.channels)
            ModifierObjectUpdate(mesh, mesh.copy(shapes = result.shapes))
        }

        val tree = input.parameterTree
        val treeModel = PuppetIr.toPuppet(RigIR(input.canvas, live, parameterTree = tree))
        val folder = PuppetParameterNode.Group(ParameterGroupId(instance.outputId("parameter-folder:ParamGroupPhysics")),
            input.context.values.getValue("folder").jsonPrimitive.content, true,
            declared.map { PuppetParameterNode.Param(ParameterId(it.id)) })
        val updatedTree = BindingMerge.appendTree(treeModel, listOf(folder), additions.mapTo(hashSetOf()) { ParameterId(it.id) },
            instance.outputAliases.keys.any { it.startsWith("parameter-folder:") })
        val convertedTree = PuppetIr.toIr(treeModel.copy(parameterTree = updatedTree)).parameterTree
        val physics = physicsChanges(input, instance, (live + additions).mapTo(hashSetOf()) { it.id })
        return ModifierModelChanges(parameters = ModifierObjectChanges(added = additions),
            meshes = ModifierObjectChanges(updated = updates), physicsGroups = physics,
            parameterTree = convertedTree.takeIf { it != tree }?.let { ModifierFieldChange(tree, it) })
    }

    private fun neutralized(shapes: List<BlendShapeBinding<MeshForm>>, drawable: Drawable,
                            parameters: List<Parameter>): List<BlendShapeBinding<MeshForm>> {
        val rest = { id: ParameterId -> if (id.raw in openIds) 1f else 0f }
        val defaults = parameters.associate { ParameterId(it.id) to it.default }
        val order = drawable.channelGrids.scalarAt(FormChannel.DRAW_ORDER, drawable.drawOrder, { defaults[it] ?: 0f })
        val opacity = drawable.channelGrids.scalarAt(FormChannel.OPACITY, drawable.opacity, rest)
        val multiply = drawable.channelGrids.colorAt(FormChannel.MULTIPLY_COLOR, drawable.multiplyColor, rest)
        val screen = drawable.channelGrids.colorAt(FormChannel.SCREEN_COLOR, drawable.screenColor, rest)
        return shapes.map { binding -> binding.copy(forms = binding.forms.map { form ->
            form?.let { MeshForm(it.positionDeltas, order, opacity, multiply, screen) }
        }) }
    }

    private fun physicsChanges(input: ModifierEvaluationInput, instance: RigModifierInstance,
                               available: Set<String>): ModifierObjectChanges<PhysicsGroup> {
        if (instance.config["physicsEnabled"]?.jsonPrimitive?.boolean == false) return ModifierObjectChanges()
        val names = input.context.values.getValue("physicsNames").jsonArray.map { it.jsonPrimitive.content }
        val overrides = instance.config["physics"]?.jsonObject.orEmpty()
        val settings = settings(names).filter { rule -> rule.parameters.all { it in available } }
        require(overrides.keys.all { id -> settings.any { it.id == id } }) { "Unavailable blink aftershock physics slot" }
        if (settings.isEmpty()) return ModifierObjectChanges()
        val existing = input.physics.groups
        val additions = mutableListOf<PhysicsGroup>()
        val reused = mutableListOf<PhysicsGroup>()
        for (rule in settings) {
            val setting = overrides[rule.id]?.jsonObject?.let(RigPhysicsEdit::fromJson) ?: rule
            require(setting.inputs.isNotEmpty() && setting.outputs.isNotEmpty() && setting.parameters.all { it in available } &&
                setting.inputs.none { it.parameter in setting.outputParameters }) { "Invalid blink aftershock physics inputs or outputs" }
            val group = PhysicsGroups.fromSetting(setting.copy(id = instance.outputId("physics:${rule.id}")))
            val compatible = (existing + additions).firstOrNull { it.copy(id = group.id, name = group.name) == group }
            if (compatible != null) {
                if (compatible in existing && compatible !in reused) reused += compatible
                continue
            }
            val outputs = group.outputs.mapTo(hashSetOf()) { it.parameter }
            require((existing + additions).none { it.id == group.id || it.outputs.any { output -> output.parameter in outputs } }) {
                "Conflicting blink aftershock physics group: ${group.id}"
            }
            additions += group
        }
        return ModifierObjectChanges(added = additions, reused = reused)
    }

    companion object {
        const val TYPE = "eye.aftershock"
        const val VERSION = 4
        const val ENTRYPOINT = "builtin.6579652e616674657273686f636b.v4/aftershock"
        val WRITES = setOf("mesh", "parameter", "hierarchy", "physics")
        private val coupled = setOf("brow.lid-coupling", "mouth.cheek-lid")
        private val headProviders = setOf("face/fitted", "face.depth-turn")
        private val headAxes = setOf("ParamAngleX", "ParamAngleY", "ParamAngleZ")
        val optionalUpstream = coupled + headProviders + setOf(TYPE, "eye.wide", "eye.smile", "eye.gaze-lid", "eye.emotion")
        private val openIds = setOf("ParamEyeLOpen", "ParamEyeROpen")

        fun parameters(context: ModifierAlgorithmContext): List<Parameter> {
            val captured = EyeSmileInputs.decode(sourceContext(context)) {}
            if (captured.art.layers.none { it.tag == "EYELASH" }) return emptyList()
            val fits = fits(captured) {}
            return if (fits.isEmpty()) emptyList() else parameters(captured.measurements, fits.keys)
        }

        private fun sourceContext(context: ModifierAlgorithmContext): ModifierAlgorithmContext {
            require(context.values.keys - "head_turn" == setOf("schema", "source", "folder", "physicsNames", "carriers") &&
                context.values.getValue("schema").jsonPrimitive.content == "eye-aftershock/1") { "Unsupported blink aftershock input schema" }
            return ModifierAlgorithmContext(context.values.getValue("source").jsonObject, context.resources)
        }

        private fun fits(captured: EyeSmileInputs.Companion.Decoded, checkpoint: () -> Unit): Map<Side, EyeFit> {
            val source = captured.measurements
            return EyeFitting.fit(captured.art, source.faceCenterX) { x, y ->
                source.alignment?.point(x, y) ?: (x to y)
            }.fits.mapValues { (side, fit) ->
                checkpoint()
                fit.moved(source.moves[side.name].orEmpty())
            }
        }

        private fun parameters(source: EyeSmileInputs, fitted: Set<Side>) = listOf(Side.LEFT to "L", Side.RIGHT to "R").map { (side, code) ->
            val id = "ParamEye${code}Bounce"
            val blend = side in fitted
            Parameter(id, source.parameterNames.getValue(id), -1f, 1f, 0f, blend = blend,
                keys = if (blend) Floats.wrap(floatArrayOf(-1f, 0f, 1f)) else null)
        }

        private fun settings(names: List<String>) = listOf("L", "R").mapIndexed { index, side ->
            RigPhysicsEdit(id = "PhysicsEye${side}Bounce", name = names[index],
                inputs = listOf(PhysicsInput("ParamEye${side}Open", 100f, PhysicsSourceType.X)),
                outputs = listOf(PhysicsOutput("ParamEye${side}Bounce", 2, 1.4f)),
                segments = listOf(PhysicsSegment(1f, 0.9f, 0.6f, 1.6f), PhysicsSegment(1.2f, 0.85f, 0.45f, 1.8f)),
                normalization = PhysicsNormalization(-1f, 0f, 1f, -10f, 0f, 10f))
        }

        private fun knob(values: Map<String, JsonElement>, id: String, default: Float, range: ClosedFloatingPointRange<Float>) =
            (values[id]?.jsonPrimitive?.float ?: default).also { require(it in range) { "Invalid eye value: $id" } } / 100f
    }
}
