package io.github.psd2live.algorithms.binding

import io.github.psd2live.algorithms.physics.PhysicsGroups
import io.github.psd2live.core.PhysicsInput
import io.github.psd2live.core.PhysicsNormalization
import io.github.psd2live.core.PhysicsOutput
import io.github.psd2live.core.PhysicsSegment
import io.github.psd2live.core.PhysicsSourceType
import io.github.psd2live.core.RigPhysicsEdit
import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.model.*
import io.github.psd2live.bridge.PuppetIr
import kotlinx.serialization.json.*
import org.umamo.runtime.eval.colorAt
import org.umamo.runtime.eval.scalarAt
import org.umamo.runtime.model.FormChannel
import org.umamo.runtime.model.MeshForm
import org.umamo.runtime.model.ParameterGroupId
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.ParameterNode as PuppetParameterNode

/** Binds the jaw's overshoot and drives it with a local pendulum. */
class MouthSpringAlgorithm : ReadingModifierAlgorithmPlugin {
    override fun changes(input: ModifierEvaluationInput, instance: RigModifierInstance, checkpoint: () -> Unit): ModifierModelChanges {
        require(instance.version == VERSION && instance.targets.all { it.kind == "mesh" })
        val scaffoldId = instance.config.getValue("scaffold")
        val baseId = instance.config.getValue("base")
        val jawId = instance.config.getValue("dependencies").jsonObject.getValue("mouth.jaw").jsonPrimitive.content
        val scaffold = requireNotNull(input.providers("rig.scaffold").singleOrNull {
            it.instanceId == scaffoldId.jsonPrimitive.content && it.version == 2 && it.targets.containsAll(instance.targets)
        }) { "Mouth spring v2 requires a shared v2 scaffold" }
        require(input.providers("mouth/fitted").any {
            it.instanceId == baseId.jsonPrimitive.content && it.version in 1..2 && it.targets.containsAll(instance.targets) &&
                it.config["scaffold"] == scaffoldId
        }) { "Mouth spring v2 requires its fitted mouth" }
        require(input.providers("mouth.jaw").any {
            it.instanceId == jawId && it.version in 1..2 && it.targets.containsAll(instance.targets) &&
                it.config["scaffold"] == scaffoldId && it.config["base"] == baseId
        }) { "Mouth spring v2 requires its jaw binding" }
        val structure = RigModifierInstance(requireNotNull(scaffold.instanceId), scaffold.type, scaffold.version, scaffold.targets,
            config = scaffold.config, outputAliases = scaffold.outputAliases, plugin = scaffold.plugin)
        val context = input.context
        val captured = MouthFittedInputs.decode(ModifierAlgorithmContext(context.values.getValue("source").jsonObject, context.resources))
        val source = requireNotNull(captured.source)
        val mouth = source.rig()
        require(mouth.options.jaw && mouth.options.spring && !mouth.options.tongue)
        val vertices = requireNotNull(captured.sourceVertices)
        require(captured.layers.keys == vertices.targets.keys && captured.layers.keys.all { ModifierTarget("mesh", it) in instance.targets })
        val raw = ScaffoldMeshContext.decodeRawMeshes(vertices.resource, checkpoint)
        val lippedSkin = context.values.getValue("lippedSkin").jsonArray.mapTo(hashSetOf()) { it.jsonPrimitive.content }
        require(captured.layers.keys.containsAll(lippedSkin))
        val declared = parameters(context).single()
        val live = input.parameters
        val defaults = live.associate { it.id to it.default }
        require(setOf(OPEN, FORM).all { axis -> live.any { it.id == axis && !it.blend && it.default == 0f } })
        val existing = live.singleOrNull { it.id == SPRING }
        require(existing == null || existing.copy(name = declared.name) == declared) { "Incompatible mouth spring parameter" }
        val additions = if (existing == null) listOf(declared) else emptyList()
        val tree = input.parameterTree
        val meshes = captured.layers.keys.map { requireNotNull(input.mesh(it)) }
        val model = PuppetIr.toPuppet(RigIR(input.canvas, live, meshes = meshes, parameterTree = tree))
        val updates = meshes.zip(model.drawables).map { (mesh, drawable) ->
            checkpoint()
            val layer = captured.layers.getValue(mesh.id)
            val target = vertices.targets.getValue(mesh.id)
            val material = raw.getValue(target.canonical)
            val points = material.rigPositions
            val frame = layer.frame
            val positions = FloatArray(points.size) { i ->
                if (i % 1024 == 0) checkpoint()
                if (target.parent == null) points[i]
                else if (i % 2 == 0) (points[i] - frame.left) / frame.width.coerceAtLeast(1e-4f)
                else (points[i] - frame.top) / frame.height.coerceAtLeast(1e-4f)
            }
            val parent = target.parent?.let { ScaffoldBinding.deformerId(structure, mesh.id, it) }
            require(mesh.parent == parent &&
                mesh.geometry == MeshGeometry(Floats.wrap(positions), Floats.wrap(material.atlasUvs), Ints.wrap(material.triangles)) &&
                mesh.shapes.all { it.parameter in headAxes || it.parameter in mouthAxes } &&
                mesh.channels.values.none { track -> track.axes.any { it.parameter == SPRING } }) {
                "Mouth spring v2 requires the original fitted mesh and frame. Use v1 with edited or lowered artwork."
            }
            val lipped = mesh.id in lippedSkin
            val shapes = mouth.faceShapes(points, frame, captured.chin, layer.tag == "NOSE", ParameterId(OPEN), MouthRig.SPRING,
                lippedSkin = lipped)
            val shape = shapes.single { it.parameterId == MouthRig.SPRING }
            val rest = { parameter: ParameterId -> if (parameter.raw in eyeOpen) 1f else 0f }
            val opacity = drawable.channelGrids.scalarAt(FormChannel.OPACITY, drawable.opacity, rest)
            val order = drawable.channelGrids.scalarAt(FormChannel.DRAW_ORDER, drawable.drawOrder, { defaults[it.raw] ?: 0f })
            val multiply = drawable.channelGrids.colorAt(FormChannel.MULTIPLY_COLOR, drawable.multiplyColor, rest)
            val screen = drawable.channelGrids.colorAt(FormChannel.SCREEN_COLOR, drawable.screenColor, rest)
            val neutralized = shape.copy(forms = shape.forms.map { form ->
                form?.let { MeshForm(it.positionDeltas, order, opacity, multiply, screen) }
            })
            val next = PuppetIr.meshToIr(drawable.copy(blendShapes = listOf(neutralized))).shapes.single()
            // Skin following the lip rim emits its capture shapes before spring. A plain jaw emits
            // spring before the capture jaw and cheek tracks. Head shapes follow the mouth in both cases.
            val split = if (lipped) mesh.shapes.indexOfFirst { it.parameter in headAxes }.let {
                if (it < 0) mesh.shapes.size else it
            } else 0
            ModifierObjectUpdate(mesh, mesh.copy(shapes = mesh.shapes.take(split) + next + mesh.shapes.drop(split)))
        }
        val folder = PuppetParameterNode.Group(ParameterGroupId(instance.outputId("parameter-folder:ParamGroupPhysics")),
            context.values.getValue("folder").jsonPrimitive.content, true, listOf(PuppetParameterNode.Param(MouthRig.SPRING)))
        val updatedTree = BindingMerge.appendTree(model, listOf(folder), additions.mapTo(hashSetOf()) { ParameterId(it.id) },
            instance.outputAliases.keys.any { it.startsWith("parameter-folder:") })
        val convertedTree = PuppetIr.toIr(model.copy(parameterTree = updatedTree)).parameterTree
        val physics = physics(input, instance, (live + additions).mapTo(hashSetOf()) { it.id }, context)
        return ModifierModelChanges(parameters = ModifierObjectChanges(added = additions, reused = listOfNotNull(existing)),
            meshes = ModifierObjectChanges(updated = updates), parameterTree = convertedTree.takeIf { it != tree }?.let { ModifierFieldChange(tree, it) },
            physicsGroups = physics)
    }

    private fun physics(input: ModifierEvaluationInput, instance: RigModifierInstance, available: Set<String>,
                        context: ModifierAlgorithmContext): ModifierObjectChanges<PhysicsGroup> {
        if (instance.config["physicsEnabled"]?.jsonPrimitive?.boolean == false) return ModifierObjectChanges()
        val overrides = instance.config["physics"]?.jsonObject.orEmpty()
        require(overrides.keys.all { it == PHYSICS }) { "${instance.id}: unknown module physics group" }
        val edit = overrides[PHYSICS]?.jsonObject?.let(RigPhysicsEdit::fromJson) ?: RigPhysicsEdit(
            id = PHYSICS, name = context.values.getValue("physics").jsonPrimitive.content,
            inputs = listOf(PhysicsInput(OPEN, 80f, PhysicsSourceType.X), PhysicsInput(FORM, 20f, PhysicsSourceType.X)),
            outputs = listOf(PhysicsOutput(SPRING, 2, 1.2f)),
            segments = listOf(PhysicsSegment(1f, 0.9f, 0.5f, 1.6f), PhysicsSegment(1.1f, 0.85f, 0.4f, 1.8f)),
            normalization = PhysicsNormalization(-1f, 0f, 1f, -10f, 0f, 10f))
        require(edit.parameters.all { it in available } && edit.inputs.isNotEmpty() && edit.outputs.isNotEmpty() &&
            edit.inputs.none { source -> edit.outputs.any { it.parameter == source.parameter } }) { "${instance.id}: invalid mouth spring physics" }
        val requested = PhysicsGroups.fromSetting(edit.copy(id = instance.outputId("physics:$PHYSICS")))
        val before = input.physics
        val compatible = before.groups.firstOrNull { it.copy(id = requested.id, name = requested.name) == requested }
        if (compatible != null) return ModifierObjectChanges(reused = listOf(compatible))
        PhysicsGroups.validateAppend(before, listOf(requested))
        return ModifierObjectChanges(added = listOf(requested))
    }

    companion object {
        const val TYPE = "mouth.spring"
        const val VERSION = 2
        const val ENTRYPOINT = "builtin.6d6f7574682e737072696e67.v2/spring"
        private const val SPRING = "ParamMouthSpring"
        private const val OPEN = "ParamMouthOpenY"
        private const val FORM = "ParamMouthForm"
        private const val PHYSICS = "PhysicsMouthSpring"
        val WRITES = setOf("mesh", "parameter", "hierarchy", "physics")
        val optionalUpstream = setOf("body/legacy", "face/legacy", "face/fitted", "face.depth-turn", "eye/fitted",
            "mouth.outline", "mouth.vowel", "mouth.capture")
        private val eyeOpen = setOf("ParamEyeLOpen", "ParamEyeROpen")
        private val headAxes = setOf("ParamAngleX", "ParamAngleY", "ParamAngleZ")
        private val mouthAxes = (MouthRig.VOWELS.map { it.second } + listOf(
            MouthRig.MOUTH_X, MouthRig.CORNER_L, MouthRig.CORNER_R, MouthRig.PUCKER_WIDEN,
            MouthRig.PUCKER_WIDEN_L, MouthRig.PUCKER_WIDEN_R, MouthRig.SHRUG, MouthRig.FUNNEL,
            MouthRig.PRESS_OPEN, MouthRig.CHEEK_PUFF, MouthRig.JAW_OPEN)).mapTo(hashSetOf()) { it.raw }

        fun context(source: ModifierAlgorithmContext, lippedSkin: Set<String>, name: String, folder: String, physics: String) =
            ModifierAlgorithmContext(buildJsonObject {
                put("schema", "mouth-spring/1")
                put("source", source.values)
                put("lippedSkin", JsonArray(lippedSkin.map(::JsonPrimitive)))
                put("name", name)
                put("folder", folder)
                put("physics", physics)
            }, source.resources)

        fun parameters(context: ModifierAlgorithmContext): List<Parameter> {
            require(context.values.keys == setOf("schema", "source", "lippedSkin", "name", "folder", "physics") &&
                context.values.getValue("schema").jsonPrimitive.content == "mouth-spring/1")
            return listOf(Parameter(SPRING, context.values.getValue("name").jsonPrimitive.content, -1f, 1f, 0f,
                blend = true, keys = Floats.values(-1f, 0f, 1f)))
        }
    }
}
