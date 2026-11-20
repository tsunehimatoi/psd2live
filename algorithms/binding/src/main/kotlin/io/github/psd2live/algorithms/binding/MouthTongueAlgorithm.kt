package io.github.psd2live.algorithms.binding

import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.model.*
import io.github.psd2live.bridge.PuppetIr
import kotlinx.serialization.json.*
import org.umamo.runtime.eval.colorAt
import org.umamo.runtime.eval.scalarAt
import org.umamo.runtime.model.ChannelGrids
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.FormChannel
import org.umamo.runtime.model.MeshForm
import org.umamo.runtime.model.ParameterGroupId
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.ParameterNode as PuppetParameterNode

/** Adds tongue-out forms to the captured fitted mouth, using original source vertices. */
class MouthTongueAlgorithm : ReadingModifierAlgorithmPlugin {
    override fun changes(input: ModifierEvaluationInput, instance: RigModifierInstance, checkpoint: () -> Unit): ModifierModelChanges {
        require(instance.version == VERSION && instance.targets.all { it.kind == "mesh" })
        val scaffold = instance.config.getValue("scaffold").jsonPrimitive.content
        val base = instance.config.getValue("base").jsonPrimitive.content
        val capture = instance.config.getValue("dependencies").jsonObject.getValue("mouth.capture").jsonPrimitive.content
        val structure = requireNotNull(input.providers("rig.scaffold").singleOrNull {
            it.instanceId == scaffold && it.version == 2 && it.targets.containsAll(instance.targets)
        }) { "Mouth tongue v2 requires a shared v2 scaffold" }
        fun compatible(capability: String, id: String) = input.providers(capability).any {
            it.instanceId == id && it.version in 1..2 && it.targets.containsAll(instance.targets) &&
                it.config["scaffold"] == JsonPrimitive(scaffold) &&
                (capability != "mouth.capture" || it.config["base"] == JsonPrimitive(base))
        }
        require(compatible("mouth/fitted", base) && compatible("mouth.capture", capture)) {
            "Mouth tongue v2 requires compatible fitted mouth and capture providers"
        }
        val scaffoldInstance = RigModifierInstance(scaffold, structure.type, structure.version, structure.targets,
            config = structure.config, outputAliases = structure.outputAliases, plugin = structure.plugin)
        val context = input.context
        val declared = parameter(context)
        val captured = MouthFittedInputs.decode(ModifierAlgorithmContext(context.values.getValue("source").jsonObject,
            context.resources, context.upstream))
        val mouth = (captured.source ?: return mouthParameterOnlyChanges(input, instance, listOf(declared),
            context.values.getValue("folder").jsonPrimitive.content)).rig()
        require(mouth.options.capture && mouth.options.tongue) { "Mouth tongue v2 requires fitted capture and tongue motion" }
        val vertices = requireNotNull(captured.sourceVertices)
        val raw = ScaffoldMeshContext.decodeRawMeshes(vertices.resource, checkpoint)
        val follows = context.values.getValue("follows").jsonObject.mapValues { MouthRig.Follow.valueOf(it.value.jsonPrimitive.content) }
        require(captured.layers.keys == vertices.targets.keys && captured.layers.keys == follows.keys &&
            MouthRig.Follow.TONGUE in follows.values && follows.keys.all { ModifierTarget("mesh", it) in instance.targets })

        val live = input.parameters
        val existing = live.singleOrNull { it.id == declared.id }
        require(existing == null || existing.copy(name = declared.name) == declared) { "Incompatible tongue-out parameter" }
        require(live.any { it.id == "ParamMouthOpenY" && !it.blend && it.default == 0f } &&
            live.any { it.id == MouthRig.MOUTH_X.raw && it.blend }) { "Missing fitted mouth capture axes" }
        val additions = if (existing == null) listOf(declared) else emptyList()
        val meshes = captured.layers.keys.map { requireNotNull(input.mesh(it)) }
        val tree = input.parameterTree
        val model = PuppetIr.toPuppet(RigIR(input.canvas, live, meshes = meshes, parameterTree = tree))
        val defaults = live.associate { ParameterId(it.id) to it.default }
        val rest = { id: ParameterId -> defaults[id] ?: if (id.raw in eyeOpen) 1f else 0f }
        val activeIds = (live + additions).mapTo(hashSetOf()) { ParameterId(it.id) }
        val copies = ArrayList<Mesh>()
        val ownerToCopy = LinkedHashMap<String, String>()
        val lowerOrder = meshes.filter { captured.layers.getValue(it.id).role in setOf("LOWER_LIP", "LOWER_LINE") }
            .maxOfOrNull { it.drawOrder }
        // Overlapping rear artwork stays inside; the foremost tongue supplies the external tip.
        val tongues = meshes.filter { follows[it.id] == MouthRig.Follow.TONGUE }
        val tips = tongues.filter { candidate ->
            val a = captured.layers.getValue(candidate.id)
            tongues.none { other ->
                val b = captured.layers.getValue(other.id)
                val w = (minOf(a.bounds.right, b.bounds.right) - maxOf(a.bounds.left, b.bounds.left)).coerceAtLeast(0f)
                val h = (minOf(a.bounds.bottom, b.bounds.bottom) - maxOf(a.bounds.top, b.bounds.top)).coerceAtLeast(0f)
                other.drawOrder > candidate.drawOrder && a.side == b.side && a.variant == b.variant &&
                    w * h > .5f * minOf(a.bounds.width * a.bounds.height, b.bounds.width * b.bounds.height)
            }
        }.mapTo(hashSetOf()) { it.id }
        val updates = meshes.zip(model.drawables).map { (mesh, drawable) ->
            checkpoint()
            val id = mesh.id
            val layer = captured.layers.getValue(id)
            val target = vertices.targets.getValue(id)
            val material = raw.getValue(target.canonical)
            val frame = layer.frame
            val geometry = requireNotNull(mesh.geometry)
            val rawPositions = mouthLayerPositions(material.rigPositions, frame, target.parent, checkpoint)
            val parent = target.parent?.let { ScaffoldBinding.deformerId(scaffoldInstance, id, it) }
            val matchesSource = mesh.parent == parent &&
                geometry == MeshGeometry(Floats.wrap(rawPositions), Floats.wrap(material.atlasUvs), Ints.wrap(material.triangles))
            require(drawable.blendShapes.none { it.parameterId == MouthRig.TONGUE_OUT } &&
                drawable.blendShapes.any { it.parameterId == MouthRig.MOUTH_X }) {
                "Mouth tongue v2 requires the captured mesh without existing tongue tracks: $id"
            }
            val points = when {
                matchesSource -> material.rigPositions
                mesh.parent == parent && geometry.uvs == Floats.wrap(material.atlasUvs) ->
                    mouthRigPointsFromLocal(geometry.positions, frame, target.parent != null, checkpoint)
                else -> mouthSourcePoints(material, geometry, checkpoint)
            }
            val positions = if (matchesSource) rawPositions else mouthLayerPositions(points, frame, target.parent, checkpoint)
            val role = follows.getValue(id)
            if (mouth.fit.layered != null && role == MouthRig.Follow.TONGUE && id in tips && lowerOrder != null && drawable.opacity > 0f) {
                // Reuse the layered-mouth companion: clipped inside at rest, with an
                // independent transparent copy above the lower lip when protruding.
                val copyId = instance.outputId("tongue-out:$id")
                require(input.mesh(copyId) == null) { "Generated tongue ID already exists: $copyId" }
                ownerToCopy[id] = copyId
                val copyOrder = lowerOrder + 1f
                val multiply = drawable.channelGrids.colorAt(FormChannel.MULTIPLY_COLOR, drawable.multiplyColor, rest)
                val screen = drawable.channelGrids.colorAt(FormChannel.SCREEN_COLOR, drawable.screenColor, rest)
                val bindings = mouth.protrudingTongueShapes(points, frame, drawable.opacity, chin = captured.chin)
                    .filter { it.parameterId in activeIds }
                    .map { shape ->
                        shape.copy(forms = shape.forms.map { form -> form?.let {
                            MeshForm(it.positionDeltas, copyOrder,
                                if (shape.parameterId == MouthRig.TONGUE_OUT) it.opacity else 0f, multiply, screen)
                        } })
                    }
                val copyParent = if (mesh.parent == parent) drawable.parentDeformerId
                    else parent?.takeIf { input.deformer(it) != null }?.let { org.umamo.runtime.model.DeformerId(it) }
                val copy = drawable.copy(id = DrawableId(copyId), parentDeformerId = copyParent,
                    textureSourceId = drawable.textureSourceId ?: drawable.id,
                    maskedBy = emptyList(), channelGrids = ChannelGrids.Empty, opacity = 0f, drawOrder = copyOrder,
                    geometryGrid = mouth.grid(points, frame, ParameterId("ParamMouthForm"), ParameterId("ParamMouthOpenY"),
                        MouthRig.Follow.PROTRUDING_TONGUE, captured.chin), blendShapes = bindings)
                copies += PuppetIr.meshToIr(copy).let {
                    if (mesh.parent == parent) it
                    else it.copy(geometry = geometry.copy(positions = Floats.wrap(if (copyParent != null) positions else points)))
                }
            }
            val closed = layer.tag == "MOUTH_CLOSE"
            val mouthShapes = mouth.shapes(points, frame, closed,
                { opens -> if (!opens) Float.NaN else if (closed) 0f else 1f },
                layer.tag == "TONGUE", role, chin = captured.chin)
            val tongue = mouthShapes.single { it.parameterId == MouthRig.TONGUE_OUT }
            val precedingShapes = mouthShapes.takeWhile { it !== tongue }.mapTo(hashSetOf()) { it.parameterId }
            val order = drawable.channelGrids.scalarAt(FormChannel.DRAW_ORDER, drawable.drawOrder, { defaults[it] ?: 0f })
            val multiply = drawable.channelGrids.colorAt(FormChannel.MULTIPLY_COLOR, drawable.multiplyColor, rest)
            val screen = drawable.channelGrids.colorAt(FormChannel.SCREEN_COLOR, drawable.screenColor, rest)
            val binding = tongue.copy(forms = tongue.forms.map { form ->
                form?.let {
                    MeshForm(it.positionDeltas, order,
                        if (drawable.opacity <= 0f) 0f else it.opacity * drawable.opacity, multiply, screen)
                }
            })
            val projected = if (mesh.parent == parent) binding else {
                val sourceGrid = if (closed) mouth.closedGrid(points, frame, ParameterId("ParamMouthForm"))
                    else mouth.grid(points, frame, ParameterId("ParamMouthForm"), ParameterId("ParamMouthOpenY"), role, captured.chin)
                mouthProjectShapes(input, live, mesh, drawable, target.parent, parent,
                    points, positions, frame, sourceGrid, listOf(binding), checkpoint).single()
            }
            val shapes = drawable.blendShapes.toMutableList()
            val index = shapes.indexOfFirst { it.parameterId !in precedingShapes }.takeIf { it >= 0 } ?: shapes.size
            shapes.add(index, projected)
            ModifierObjectUpdate(mesh, mesh.copy(shapes = PuppetIr.meshToIr(drawable.copy(blendShapes = shapes)).shapes))
        }
        val ribbonUpdates = mouthOutlineRibbonUpdates(input, live, context, mouth, setOf(MouthRig.TONGUE_OUT), rest, checkpoint)
        val (partUpdates, rootChildrenChange, renderRootChange) = placeTongueCopies(input, ownerToCopy)
        val folder = PuppetParameterNode.Group(ParameterGroupId(ModifierParameterGroups.resolveId(instance, ModifierParameterGroups.MOUTH)),
            context.values.getValue("folder").jsonPrimitive.content, true, listOf(PuppetParameterNode.Param(MouthRig.TONGUE_OUT)))
        val updatedTree = BindingMerge.appendTree(model, listOf(folder), additions.mapTo(hashSetOf()) { ParameterId(it.id) },
            instance.outputAliases.keys.any { it.startsWith("parameter-folder:") })
        val convertedTree = PuppetIr.toIr(model.copy(parameterTree = updatedTree)).parameterTree
        return ModifierModelChanges(parameters = ModifierObjectChanges(added = additions),
            parts = ModifierObjectChanges(updated = partUpdates),
            meshes = ModifierObjectChanges(added = copies, updated = updates + ribbonUpdates),
            parameterTree = convertedTree.takeIf { it != tree }?.let { ModifierFieldChange(tree, it) },
            rootChildren = rootChildrenChange,
            renderRoot = renderRootChange)
    }

    private fun placeTongueCopies(
        input: ModifierEvaluationInput,
        ownerToCopy: Map<String, String>,
    ): Triple<List<ModifierObjectUpdate<Part>>, ModifierFieldChange<List<ChildRef>>?, ModifierFieldChange<RenderGroup>?> {
        if (ownerToCopy.isEmpty()) return Triple(emptyList(), null, null)
        val placedInHierarchy = HashSet<String>()
        fun insertChildren(children: List<ChildRef>): List<ChildRef> = children.flatMap { child ->
            val copy = (child as? ChildRef.MeshRef)?.let { ownerToCopy[it.id] }
            if (copy == null) listOf(child)
            else {
                placedInHierarchy += copy
                listOf(ChildRef.MeshRef(copy), child)
            }
        }
        val partUpdates = input.parts.mapNotNull { part ->
            val next = insertChildren(part.children)
            if (next == part.children) null else ModifierObjectUpdate(part, part.copy(children = next))
        }
        val oldRoot = input.rootChildren
        var nextRoot = insertChildren(oldRoot)
        val unplaced = ownerToCopy.values.filter { it !in placedInHierarchy }
        if (unplaced.isNotEmpty() && (input.parts.isNotEmpty() || oldRoot.isNotEmpty())) {
            nextRoot = nextRoot + unplaced.map(ChildRef::MeshRef)
        }
        val rootChange = if (nextRoot == oldRoot) null else ModifierFieldChange(oldRoot, nextRoot)

        val oldRender = input.renderRoot
        val renderChange = if (oldRender.children.isEmpty()) null else {
            val placedInRender = HashSet<String>()
            fun insertRender(group: RenderGroup): RenderGroup = group.copy(children = group.children.flatMap { child ->
                when (child) {
                    is RenderMesh -> {
                        val copy = ownerToCopy[child.id]
                        if (copy == null) listOf(child)
                        else {
                            placedInRender += copy
                            listOf(child, RenderMesh(copy))
                        }
                    }
                    is RenderGroup -> listOf(insertRender(child))
                }
            })
            val updated = insertRender(oldRender)
            val missing = ownerToCopy.values.filter { it !in placedInRender }
            val finalRender = if (missing.isEmpty()) updated else updated.copy(children = updated.children + missing.map(::RenderMesh))
            if (finalRender == oldRender) null else ModifierFieldChange(oldRender, finalRender)
        }
        return Triple(partUpdates, rootChange, renderChange)
    }

    companion object {
        const val TYPE = "mouth.tongue"
        const val VERSION = 2
        const val ENTRYPOINT = "builtin.6d6f7574682e746f6e677565.v2/tongue"
        val WRITES = setOf("mesh", "part", "parameter", "hierarchy")
        val optionalUpstream = setOf("face/fitted", "face.depth-turn", "mouth.outline", "mouth.vowel")
        private val eyeOpen = setOf("ParamEyeLOpen", "ParamEyeROpen")

        fun context(source: ModifierAlgorithmContext, follows: Map<String, MouthRig.Follow>, name: String,
                    folder: String, outlineRibbons: JsonArray? = null) =
            ModifierAlgorithmContext(buildJsonObject {
                put("schema", "mouth-tongue/1")
                put("source", source.values)
                put("follows", JsonObject(follows.mapValues { JsonPrimitive(it.value.name) }))
                put("name", name)
                put("folder", folder)
                if (outlineRibbons != null && outlineRibbons.isNotEmpty()) put("outlineRibbons", outlineRibbons)
            }, source.resources)

        fun parameter(context: ModifierAlgorithmContext): Parameter {
            require((context.values.keys - "outlineRibbons") == setOf("schema", "source", "follows", "name", "folder") &&
                context.values.getValue("schema").jsonPrimitive.content == "mouth-tongue/1")
            return Parameter(MouthRig.TONGUE_OUT.raw, context.values.getValue("name").jsonPrimitive.content,
                0f, 1f, 0f, blend = true, keys = Floats.wrap(floatArrayOf(0f, 1f)))
        }
    }
}
