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
import org.umamo.edit.VertexSource
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
        val source = captured.source ?: return ModifierModelChanges()
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
            val geometry = requireNotNull(mesh.geometry)
            val points = mouthSourcePoints(material, geometry, checkpoint)
            val frame = layer.frame
            val positions = mouthLayerPositions(points, frame, target.parent, checkpoint)
            val capturedParent = target.parent?.let { ScaffoldBinding.deformerId(structure, mesh.id, it) }
            val parent = capturedParent?.takeIf { input.deformer(it) != null }
            require(mesh.shapes.none { it.parameter == SPRING } &&
                mesh.offsets?.axes.orEmpty().none { it.parameter == SPRING } &&
                mesh.channels.values.none { track -> track.axes.any { it.parameter == SPRING } }) {
                "Mouth spring already has a track: ${mesh.id}"
            }
            val lipped = mesh.id in lippedSkin
            val shapes = mouth.faceShapes(points, frame, captured.chin, layer.tag == "NOSE", ParameterId(OPEN), MouthRig.SPRING,
                lippedSkin = lipped)
            val shape = shapes.single { it.parameterId == MouthRig.SPRING }
            val rest = { parameter: ParameterId -> defaults[parameter.raw] ?: 0f }
            val opacity = drawable.channelGrids.scalarAt(FormChannel.OPACITY, drawable.opacity, rest)
            val order = drawable.channelGrids.scalarAt(FormChannel.DRAW_ORDER, drawable.drawOrder, { defaults[it.raw] ?: 0f })
            val multiply = drawable.channelGrids.colorAt(FormChannel.MULTIPLY_COLOR, drawable.multiplyColor, rest)
            val screen = drawable.channelGrids.colorAt(FormChannel.SCREEN_COLOR, drawable.screenColor, rest)
            val sourceGrid = if (!lipped || layer.tag == "NOSE") null else mouth.faceGrid(points, frame, ParameterId(FORM), ParameterId(OPEN), captured.chin)
            val neutralized = shape.copy(forms = shape.forms.map { form -> form?.let {
                MeshForm(it.positionDeltas, order, opacity, multiply, screen)
            } })
            // Capture can add a press axis and jaw lowering can replace the local parent frame.
            // Project only spring's new contribution, keeping every existing grid and shape untouched.
            val merged = mouthProjectShapes(input, live, mesh, drawable, target.parent, capturedParent,
                points, positions, frame, sourceGrid, listOf(neutralized), checkpoint)
            val next = PuppetIr.meshToIr(drawable.copy(blendShapes = merged)).shapes.single()
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
        private val headAxes = setOf("ParamAngleX", "ParamAngleY", "ParamAngleZ")

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

/** Recovers source rig coordinates for [geometry], interpolating by UVs if topology was edited. */
internal fun mouthSourcePoints(raw: ScaffoldMesh.RawMesh, geometry: MeshGeometry, checkpoint: () -> Unit): FloatArray {
    if (geometry.uvs == Floats.wrap(raw.atlasUvs)) return raw.rigPositions
    require(raw.rigPositions.isNotEmpty()) { "Missing source vertices for mouth modifier" }
    val hasArea = (raw.triangles.indices step 3).any { i ->
        val a = raw.triangles[i] * 2; val b = raw.triangles[i + 1] * 2; val c = raw.triangles[i + 2] * 2
        kotlin.math.abs((raw.atlasUvs[b + 1] - raw.atlasUvs[c + 1]) * (raw.atlasUvs[a] - raw.atlasUvs[c]) +
            (raw.atlasUvs[c] - raw.atlasUvs[b]) * (raw.atlasUvs[a + 1] - raw.atlasUvs[c + 1])) >= 1e-12f
    }
    val sources = List(geometry.uvs.size / 2) { vertex ->
        checkpoint()
        val x = geometry.uvs[vertex * 2]; val y = geometry.uvs[vertex * 2 + 1]
        val nearest = (raw.atlasUvs.indices step 2).minBy { i ->
            val dx = raw.atlasUvs[i].toDouble() - x; val dy = raw.atlasUvs[i + 1].toDouble() - y
            dx * dx + dy * dy
        } / 2
        if (!hasArea || kotlin.math.abs(raw.atlasUvs[nearest * 2] - x) <= 1e-6f &&
            kotlin.math.abs(raw.atlasUvs[nearest * 2 + 1] - y) <= 1e-6f) VertexSource.FromOld(nearest)
        else {
            val bound = BindingPaths.bind(raw.atlasUvs, raw.triangles, x, y)
            if (minOf(bound.wa, bound.wb, bound.wc) < -1e-4f) VertexSource.FromOld(nearest)
            else {
                val a = bound.wa.coerceAtLeast(0f); val b = bound.wb.coerceAtLeast(0f); val c = bound.wc.coerceAtLeast(0f)
                val total = a + b + c
                VertexSource.BarycentricOf(bound.a, bound.b, bound.c, a / total, b / total, c / total)
            }
        }
    }
    return BindingVertices.transfer(raw.rigPositions, sources)
}

/** Normalizes rig coordinates into [frame] when parented under a deformer. */
internal fun mouthLayerPositions(points: FloatArray, frame: Bounds, parent: String?, checkpoint: () -> Unit): FloatArray =
    FloatArray(points.size) { i ->
        if (i % 1024 == 0) checkpoint()
        if (parent == null) points[i]
        else if (i % 2 == 0) (points[i] - frame.left) / frame.width.coerceAtLeast(1e-4f)
        else (points[i] - frame.top) / frame.height.coerceAtLeast(1e-4f)
    }

/** Recovers rig coordinates from a mesh's parented local positions. */
internal fun mouthRigPointsFromLocal(positions: Floats, frame: Bounds, parented: Boolean, checkpoint: () -> Unit): FloatArray =
    FloatArray(positions.size) { i ->
        if (i % 1024 == 0) checkpoint()
        if (!parented) positions[i]
        else if (i % 2 == 0) frame.left + positions[i] * frame.width.coerceAtLeast(1e-4f)
        else frame.top + positions[i] * frame.height.coerceAtLeast(1e-4f)
    }

/** Projects [shapes] computed in [capturedParent] source coordinates onto [mesh]'s live parent chain and topology. */
internal fun mouthProjectShapes(
    input: ModifierEvaluationInput,
    live: List<Parameter>,
    mesh: Mesh,
    drawable: org.umamo.runtime.model.Drawable,
    targetParent: String?,
    capturedParent: String?,
    points: FloatArray,
    positions: FloatArray,
    frame: Bounds,
    sourceGrid: org.umamo.runtime.model.KeyformGrid<org.umamo.runtime.model.MeshDeltaForm>?,
    shapes: List<org.umamo.runtime.model.BlendShapeBinding<MeshForm>>,
    checkpoint: () -> Unit,
): List<org.umamo.runtime.model.BlendShapeBinding<MeshForm>> {
    if (shapes.isEmpty()) return emptyList()
    val parent = capturedParent?.takeIf { input.deformer(it) != null }
    fun sourceDelta(delta: FloatArray) = if (parent != null || targetParent == null) delta else
        FloatArray(delta.size) { i -> delta[i] * if (i % 2 == 0) frame.width else frame.height }
    val sourceOffsets = sourceGrid?.let { grid -> KeyGrid(
        grid.axes.map { KeyAxis(it.parameterId.raw, Floats.wrap(it.keys)) },
        grid.cells.map { KeyCell(Ints.wrap(it.coordinate), MeshOffsets(Floats.wrap(sourceDelta(it.form.positionDeltas)))) }) }
    val geometry = requireNotNull(mesh.geometry)
    val sourceMesh = mesh.copy(parent = parent,
        geometry = geometry.copy(positions = Floats.wrap(if (parent != null) positions else points)),
        offsets = sourceOffsets, shapes = emptyList())
    val sourceBranch = generateSequence(parent?.let(input::deformer)) { it.parent?.let(input::deformer) }.toList()
    val previous = PuppetIr.toPuppet(RigIR(input.canvas, live, meshes = listOf(sourceMesh), deformers = sourceBranch))
    val desiredShapes = shapes.map { shape ->
        shape.copy(forms = shape.forms.map { form ->
            form?.let { MeshForm(sourceDelta(it.positionDeltas), it.drawOrder, it.opacity, it.multiplyColor, it.screenColor) }
        })
    }
    val desired = previous.copy(drawables = listOf(previous.drawables.single().copy(blendShapes = desiredShapes)))
    val branch = input.parentsOfMesh(mesh.id)
    val current = PuppetIr.toPuppet(RigIR(input.canvas, live, meshes = listOf(mesh), deformers = branch))
    val contribution = current.copy(drawables = listOf(current.drawables.single().copy(blendShapes = emptyList())))
    val correspondence = List(points.size / 2) { VertexSource.FromOld(it) }
    return MeshBlendResidual.merge(contribution, contribution, previous, desired, drawable.id,
        correspondence, correspondence, checkpoint)
}
