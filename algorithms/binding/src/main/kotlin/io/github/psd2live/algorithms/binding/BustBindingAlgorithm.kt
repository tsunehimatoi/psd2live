package io.github.psd2live.algorithms.binding

import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.model.Authoring
import io.github.psd2live.format.model.Floats
import io.github.psd2live.format.model.Mesh
import io.github.psd2live.format.model.Parameter
import io.github.psd2live.format.model.ParameterNode
import io.github.psd2live.format.model.PhysicsGroup
import io.github.psd2live.format.model.RigIR
import io.github.psd2live.format.model.Textures
import io.github.psd2live.targets.cubism.PuppetIr
import kotlinx.serialization.json.*
import org.umamo.edit.VertexSource
import org.umamo.runtime.model.*
import kotlin.math.abs

/** Computes chest-only shapes in the neutral scaffold branch and adds their residual to the authored mesh. */
class BustBindingAlgorithm : ReadingModifierAlgorithmPlugin {
    override fun changes(input: ModifierEvaluationInput, instance: RigModifierInstance, checkpoint: () -> Unit): ModifierModelChanges {
        require(instance.version == VERSION && instance.targets.all { it.kind == "mesh" }) { "Invalid chest binding invocation" }
        val source = BustBindingInputs.decode(input.context)
        if (!source.applies) return ModifierModelChanges()
        require(instance.targets.mapTo(hashSetOf()) { it.id }.containsAll(source.targets.keys))
        val dependencies = instance.config["dependencies"]?.jsonObject.orEmpty()
        val providers = dependencies + ("rig.scaffold" to instance.config.getValue("scaffold"))
        require(providers.keys == setOf("rig.scaffold", "body/legacy", "face/legacy"))
        for ((capability, provider) in providers) {
            require(input.providers(capability).any { it.instanceId == provider.jsonPrimitive.content &&
                it.targets.containsAll(instance.targets) }) { "Missing chest binding provider: $capability" }
        }
        val meshes = readMeshes(source, checkpoint)
        val chest = rig(source, bounce(instance), checkpoint)
        val definitions = definitions(source, meshes, chest)
        val added = definitions.filter { input.parameter(it.id) == null }
        val parameters = input.parameters + added
        val updates = if (source.targets.isEmpty() || definitions.none { it.blend }) emptyList() else {
            val frames = ScaffoldFramesContext.build(source.frames, checkpoint)
            val branches = ScaffoldBranchesContext.build(source.branches, frames, checkpoint)
            val scaffold = frames + branches.deformers
            source.targets.mapNotNull { (id, canonical) ->
                checkpoint()
                val original = requireNotNull(input.mesh(id)) { "Missing chest target: $id" }
                val geometry = original.geometry ?: return@mapNotNull null
                val layer = source.layers.getValue(canonical)
                val parent = branches.parents.getValue(layer.sourceId)
                val raw = meshes.getValue(canonical)
                val shapes = chest.shapes(raw.rigPositions, parent.frame, bustY, bustX, layer.rigid)
                if (shapes.isEmpty()) return@mapNotNull null
                val (sourceMesh, rest) = ScaffoldMesh.build(raw, parent, ScaffoldMesh.Geometry.Zero, checkpoint)
                val actual = PuppetIr.toPuppet(RigIR(input.canvas, parameters, deformers = input.parentsOfMesh(id), meshes = listOf(original)))
                val drawable = actual.drawables.single()
                fun neutralized(bindings: List<BlendShapeBinding<MeshForm>>) = bindings.map { binding ->
                        binding.copy(forms = binding.forms.map { form ->
                            form?.let { MeshForm(it.positionDeltas, drawable.drawOrder, drawable.opacity, drawable.multiplyColor, drawable.screenColor) }
                        })
                }
                val generated = drawable.copy(parentDeformerId = parent.id, mesh = sourceMesh, geometryGrid = rest,
                    channelGrids = ChannelGrids.Empty, blendShapes = neutralized(shapes))
                val prior = source.previousBounce[id]?.let {
                    rig(source, it, checkpoint).shapes(raw.rigPositions, parent.frame, bustY, bustX, layer.rigid)
                }.orEmpty()
                val branch = generateSequence(parent.id) { node -> scaffold.single { it.id == node }.parent }
                    .map { node -> scaffold.single { it.id == node } }.toList()
                val before = actual.copy(deformers = branch, drawables = listOf(generated.copy(blendShapes = neutralized(prior))))
                val desired = before.copy(drawables = listOf(generated))
                val originalUV = canvasUV(raw.atlasUvs, layer.tile, source.textureSpace.textures, source.textureSpace.authoring)
                val liveUV = canvasUV(geometry.uvs.shared(), original.tile, input.textures, input.authoring)
                val vertices = correspondences(originalUV, raw.triangles, liveUV, geometry.indices.shared(), checkpoint)
                fun chestBinding(binding: BlendShapeBinding<MeshForm>) = shapes.any {
                    it.parameterId == binding.parameterId && it.keys.contentEquals(binding.keys) &&
                        it.neutralIndex == binding.neutralIndex && it.limits == binding.limits
                }
                val current = actual.copy(drawables = listOf(drawable.copy(blendShapes = drawable.blendShapes.filter(::chestBinding))))
                val merged = MeshBlendResidual.merge(current, actual, before, desired, drawable.id, vertices, vertices, checkpoint)
                val replacement = PuppetIr.meshToIr(drawable.copy(blendShapes = merged)).shapes.associateBy { it.parameter }.toMutableMap()
                val changed = original.copy(shapes = original.shapes.mapIndexedNotNull { index, shape ->
                    if (chestBinding(drawable.blendShapes[index])) replacement.remove(shape.parameter) else shape
                } + replacement.values)
                ModifierObjectUpdate(original, changed).takeIf { original != changed }
            }
        }
        val tree = input.parameterTree
        val nextTree = parameterTree(tree, input.parameters, added, instance.outputId("parameter-folder:$GROUP"), source.names.folder)
        val physics = physicsChanges(input, instance, source, parameters.mapTo(hashSetOf()) { it.id })
        return ModifierModelChanges(parameters = ModifierObjectChanges(added = added), meshes = ModifierObjectChanges(updated = updates),
            physicsGroups = physics, parameterTree = nextTree.takeIf { it != tree }?.let { ModifierFieldChange(tree, it) })
    }

    private fun physicsChanges(input: ModifierEvaluationInput, instance: RigModifierInstance, source: BustBindingInputs,
                               available: Set<String>): ModifierObjectChanges<PhysicsGroup> {
        if (instance.config["physicsEnabled"]?.jsonPrimitive?.boolean == false) return ModifierObjectChanges()
        val slots = source.physics.filter { rule -> rule.outputs.all { it.parameter in available } }
        val overrides = source.overrides.associateBy { it.id }
        require(overrides.keys.all { id -> slots.any { it.id == id } }) { "Unavailable chest physics slot" }
        val additions = mutableListOf<PhysicsGroup>()
        val reused = mutableListOf<PhysicsGroup>()
        for (rule in slots) {
            val group = (overrides[rule.id] ?: rule.copy(inputs = rule.inputs.filter { it.parameter in available }))
                .copy(id = instance.outputId("physics:${rule.id}"))
            require(group.inputs.isNotEmpty() && group.inputs.all { it.parameter in available } &&
                group.outputs.isNotEmpty() && group.outputs.all { it.parameter in available }) { "Invalid chest physics inputs or outputs" }
            val existing = input.physics.groups
            val compatible = existing.firstOrNull { it.copy(id = group.id, name = group.name) == group }
            if (compatible != null) {
                if (compatible !in reused) reused += compatible
                continue
            }
            val outputs = group.outputs.mapTo(hashSetOf()) { it.parameter }
            require((existing + additions).none { it.id == group.id || it.outputs.any { output -> output.parameter in outputs } }) {
                "Conflicting chest physics group: ${group.id}"
            }
            additions += group
        }
        return ModifierObjectChanges(added = additions, reused = reused)
    }

    private fun parameterTree(tree: List<ParameterNode>, existing: List<Parameter>, added: List<Parameter>, group: String,
                              name: String): List<ParameterNode> {
        if (added.isEmpty()) return tree
        val nodes = added.map { ParameterNode.Param(it.id) }
        val initial = tree.ifEmpty { existing.map { ParameterNode.Param(it.id) } }
        var inserted = false
        fun insert(tree: List<ParameterNode>): List<ParameterNode> = tree.map { node ->
            when {
                node !is ParameterNode.Group -> node
                node.id == group -> node.copy(children = node.children + nodes).also { inserted = true }
                else -> node.copy(children = insert(node.children))
            }
        }
        val updated = insert(initial)
        return if (inserted) updated else updated + ParameterNode.Group(group, name, true, nodes)
    }

    companion object {
        const val TYPE = "body.bust"
        const val VERSION = 2
        const val ENTRYPOINT = "builtin.626f64792e62757374.v2/bust"
        val WRITES = setOf("mesh", "parameter", "hierarchy", "physics")
        private const val GROUP = "ParamGroupPhysics"
        private val bustY = ParameterId("ParamBustY")
        private val bustX = ParameterId("ParamBustX")

        fun parameters(source: BustBindingInputs, instance: RigModifierInstance, checkpoint: () -> Unit = {}): List<Parameter> {
            if (!source.applies) return emptyList()
            return definitions(source, readMeshes(source, checkpoint), rig(source, bounce(instance), checkpoint))
        }

        private fun definitions(source: BustBindingInputs, meshes: Map<String, ScaffoldMesh.RawMesh>, rig: BustRig): List<Parameter> {
            val blend = source.layers.any { (id, layer) -> rig.shapes(meshes.getValue(id).rigPositions, Bounds(0f, 0f, 1f, 1f), bustY, bustX, layer.rigid).isNotEmpty() }
            return listOf(bustY.raw to source.names.y, bustX.raw to source.names.x).map { (id, name) ->
                Parameter(id, name, -1f, 1f, 0f, blend = blend, keys = if (blend) Floats.values(-1f, 0f, 1f) else null)
            }
        }

        private fun bounce(instance: RigModifierInstance): Float {
            val values = instance.config["values"]?.jsonObject.orEmpty()
            require(values.keys.all { it == "bounce" })
            val bounce = values["bounce"]?.jsonPrimitive?.float ?: 100f
            require(bounce.isFinite() && bounce in 0f..200f)
            return bounce
        }

        private fun readMeshes(source: BustBindingInputs, checkpoint: () -> Unit): Map<String, ScaffoldMesh.RawMesh> {
            val raw = ScaffoldMeshContext.decodeRawMeshes(source.sourceMeshes, checkpoint)
            require(raw.keys == source.layers.keys) { "Chest source geometry and layer measurements disagree" }
            return raw
        }

        private fun rig(source: BustBindingInputs, bounce: Float, checkpoint: () -> Unit) = source.torso.let {
            BustRig(it.centerX, it.shoulderY, it.waistY, it.halfWidth, bounce / 100f, checkpoint)
        }

        private fun canvasUV(uvs: FloatArray, tileId: String?, textures: Textures, authoring: Authoring): FloatArray {
            val tile = textures.tiles.single { it.id == tileId }
            val source = requireNotNull(tile.source) { "Chest vertex transfer requires source artwork" }
            val bounds = authoring.sources.single { it.id == source.source }.layers.single { it.key == source.layer }
            require(tile.width > 0 && tile.height > 0 && bounds.width > 0 && bounds.height > 0)
            val scaleX = tile.width / bounds.width.toFloat()
            val scaleY = tile.height / bounds.height.toFloat()
            val placement = tile.placement?.takeIf { textures.uvsAddressPages }
            val page = placement?.let { textures.tilePages[it.page] }
            val transform = placement?.let { AtlasPlacement(it.page, it.x, it.y, it.scaleX, it.scaleY, it.rotation) }
            return FloatArray(uvs.size).also { canvas ->
                for (index in uvs.indices step 2) {
                    val x = uvs[index]
                    val y = uvs[index + 1]
                    val raster = when {
                        placement == null -> floatArrayOf(x * tile.width, y * tile.height)
                        placement.rotation == 0f -> floatArrayOf(
                            (x * requireNotNull(page).width - placement.x) / placement.scaleX,
                            (y * requireNotNull(page).height - placement.y) / placement.scaleY)
                        else -> requireNotNull(layerPixelOf(requireNotNull(transform), x * requireNotNull(page).width,
                            y * requireNotNull(page).height)) { "Degenerate chest texture placement" }
                    }
                    canvas[index] = bounds.left + raster[0] / scaleX
                    canvas[index + 1] = bounds.top + raster[1] / scaleY
                }
            }
        }

        private fun correspondences(original: FloatArray, originalTriangles: IntArray, uvs: FloatArray, triangles: IntArray,
                                    checkpoint: () -> Unit): List<VertexSource> {
            if (original.contentEquals(uvs) && originalTriangles.contentEquals(triangles))
                return List(uvs.size / 2) { VertexSource.FromOld(it) }
            return List(uvs.size / 2) { index ->
                checkpoint()
                val x = uvs[index * 2]
                val y = uvs[index * 2 + 1]
                val nearest = if (index < original.size / 2 && original[index * 2] == x && original[index * 2 + 1] == y) index
                else (0 until original.size / 2).minBy { vertex ->
                    val dx = original[vertex * 2].toDouble() - x
                    val dy = original[vertex * 2 + 1].toDouble() - y
                    dx * dx + dy * dy
                }
                if (abs(original[nearest * 2] - x) <= 1e-6f && abs(original[nearest * 2 + 1] - y) <= 1e-6f)
                    VertexSource.FromOld(nearest)
                else {
                    val point = BindingPaths.bind(original, originalTriangles, x, y)
                    if (minOf(point.wa, point.wb, point.wc) < -1e-4f) VertexSource.FromOld(nearest)
                    else {
                        val a = point.wa.coerceAtLeast(0f)
                        val b = point.wb.coerceAtLeast(0f)
                        val c = point.wc.coerceAtLeast(0f)
                        val total = a + b + c
                        VertexSource.BarycentricOf(point.a, point.b, point.c, a / total, b / total, c / total)
                    }
                }
            }
        }
    }
}
