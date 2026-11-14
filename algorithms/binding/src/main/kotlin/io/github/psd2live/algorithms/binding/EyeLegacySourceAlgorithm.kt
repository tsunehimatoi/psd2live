package io.github.psd2live.algorithms.binding

import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.model.Deformer as IrDeformer
import io.github.psd2live.format.model.ParameterLink as IrLink
import io.github.psd2live.format.model.RigIR
import io.github.psd2live.bridge.PuppetIr
import kotlinx.serialization.json.*
import org.umamo.edit.VertexSource
import org.umamo.runtime.model.*

/** Binds source blink and gaze in the eye scaffold, preserving motion on its ancestors. */
class EyeLegacySourceAlgorithm : ReadingModifierAlgorithmPlugin {
    override fun changes(input: ModifierEvaluationInput, instance: RigModifierInstance, checkpoint: () -> Unit): ModifierModelChanges {
        require(instance.version == VERSION && instance.targets.all { it.kind == "mesh" })
        val scaffold = instance.config.getValue("scaffold").jsonPrimitive.content
        val provider = input.providers("rig.scaffold").singleOrNull { it.instanceId == scaffold }
        require(provider != null && provider.version == 2 && provider.targets.toSet() == instance.targets.toSet()) {
            "Legacy eyes v2 require the complete version 2 scaffold scope"
        }
        require(input.upstream.none { it.type in STYLED_HEAD && it.targets.any(instance.targets::contains) }) {
            "Legacy eyes v2 require ordinary head-turn frames"
        }
        val context = input.context
        val source = context.values.getValue("scaffold").jsonObject
        require(source["schema"] == JsonPrimitive("scaffold/5") && "template" !in context.resources)
        val measurement = EyeLegacyInputs.decode(ModifierAlgorithmContext(context.values.getValue("eye").jsonObject,
            mapOf("source_meshes" to context.resources.getValue("source_meshes"))))
        val structure = ScaffoldAlgorithm().buildSource(ModifierAlgorithmContext(source, context.resources), checkpoint)
        val canonical = source.getValue("canonical_targets").jsonObject
        val parameterNames = context.values.getValue("parameter_names").jsonObject.mapValues { it.value.jsonPrimitive.content }
        val opacity = context.values.getValue("opacity").jsonObject
        val definitions = EyeFittedAlgorithm.parameters(parameterNames)
        val groupName = context.values.getValue("group_name").jsonPrimitive.content
        val algorithm = EyeLegacyAlgorithm(measurement, checkpoint)
        val neutral = algorithm.before(structure)
        val bound = algorithm.project(neutral, PuppetIr.toPuppet(RigIR(input.canvas, definitions)).parameters, groupName, checkpoint)
        val authoredParameters = input.parameters
        val existing = authoredParameters.associateBy { it.id }
        val additions = definitions.filter { it.id !in existing }
        val liveParameters = authoredParameters + additions
        val defaults = liveParameters.associate { ParameterId(it.id) to it.default }
        val idSource = RigModifierInstance(scaffold, provider.type, provider.version, provider.targets,
            config = provider.config, outputAliases = provider.outputAliases, plugin = provider.plugin)
        fun mapped(target: String, id: DeformerId) = ScaffoldBinding.deformerId(idSource, target, id.raw)
        val neutralMeshes = neutral.drawables.associateBy { it.id.raw }
        val generatedMeshes = bound.drawables.associateBy { it.id.raw }
        val updates = instance.targets.mapNotNull { target ->
            checkpoint()
            val canonicalId = canonical.getValue(target.id).jsonPrimitive.content
            if (canonicalId !in measurement.layers) return@mapNotNull null
            val before = requireNotNull(input.mesh(target.id))
            val old = neutralMeshes.getValue(canonicalId).copy(id = DrawableId(target.id), opacity = opacity.getValue(canonicalId).jsonPrimitive.float)
            val desired = generatedMeshes.getValue(canonicalId).copy(id = old.id, opacity = old.opacity)
            val base = PuppetIr.meshToIr(old)
            val next = PuppetIr.meshToIr(desired)
            val authoredFrame = old.parentDeformerId?.let { mapped(target.id, it) }
            val offsets = when {
                old.geometryGrid === desired.geometryGrid -> before.offsets
                before.parent == authoredFrame && before.geometry == base.geometry && before.offsets == base.offsets -> next.offsets
                else -> {
                    require(before.geometry?.uvs == base.geometry?.uvs && before.geometry?.indices == base.geometry?.indices) {
                        "Legacy eyes v2 require the captured eye topology"
                    }
                    val branch = input.parentsOfMesh(target.id)
                    val live = PuppetIr.toPuppet(RigIR(input.canvas, liveParameters, deformers = branch, meshes = listOf(before)))
                    val original = neutral.copy(drawables = listOf(old), parameters = bound.parameters,
                        canvasWidth = live.canvasWidth, canvasHeight = live.canvasHeight,
                        worldOriginX = live.worldOriginX, worldOriginY = live.worldOriginY)
                    val vertices = List(requireNotNull(old.mesh).vertexCount) { VertexSource.FromOld(it) }
                    val grid = GeometryResidual.geometry(live, original, original.copy(drawables = listOf(desired)), old.id,
                        vertices, vertices, checkpoint)
                    PuppetIr.meshToIr(live.drawables.single().copy(geometryGrid = grid)).offsets
                }
            }
            val channels = if (old.channelGrids === desired.channelGrids) before.channels else {
                val live = PuppetIr.toPuppet(RigIR(input.canvas, liveParameters, meshes = listOf(before)))
                val actual = live.drawables.single()
                val tracks = if (before.channels.isEmpty() && before.opacity == old.opacity) desired.channelGrids else
                    GeometryResidual.channels(live, actual, old, desired, checkpoint)
                PuppetIr.meshToIr(actual.copy(channelGrids = tracks)).channels
            }
            val masks = if (base.maskedBy == next.maskedBy) before.maskedBy else {
                require(!before.invertMask && (before.maskedBy == base.maskedBy || before.maskedBy == next.maskedBy)) {
                    "${instance.id}: incompatible authored masks on ${target.id}"
                }
                next.maskedBy
            }
            val updated = before.copy(offsets = offsets, channels = channels, maskedBy = masks)
            ModifierObjectUpdate(before, updated).takeIf { before != updated }
        }
        val gazes = neutral.deformers.zip(bound.deformers).mapNotNull { (before, after) ->
            checkpoint()
            if (before === after) return@mapNotNull null
            require(before is Deformer.Warp && after is Deformer.Warp)
            val id = mapped(instance.targets.first().id, before.id)
            val warp = requireNotNull(input.deformer(id) as? IrDeformer.Warp) { "Missing eye gaze scaffold: $id" }
            require(warp.rows == before.rows && warp.columns == before.columns &&
                warp.parent == before.parent?.let { mapped(instance.targets.first().id, it) }) { "Incompatible eye gaze scaffold: $id" }
            val actual = PuppetIr.toPuppet(RigIR(input.canvas, liveParameters, deformers = listOf(warp))).deformers.single() as Deformer.Warp
            val grid = BindingMerge.warpDelta(actual.geometryGrid, before.geometryGrid, after.geometryGrid, defaults, checkpoint)
            val converted = PuppetIr.deformerToIr(actual.copy(geometryGrid = grid)) as IrDeformer.Warp
            val updated = warp.copy(lattice = converted.lattice)
            ModifierObjectUpdate<IrDeformer>(warp, updated).takeIf { warp != updated }
        }
        val tree = input.parameterTree
        val treeModel = PuppetIr.toPuppet(RigIR(input.canvas, liveParameters, parameterTree = tree))
        val folder = (bound.parameterTree.single() as ParameterNode.Group).copy(
            id = ParameterGroupId(instance.outputId("parameter-folder:ParamGroupEyes")))
        val updatedTree = BindingMerge.appendTree(treeModel, listOf(folder), additions.mapTo(hashSetOf()) { ParameterId(it.id) },
            instance.outputAliases.containsKey("parameter-folder:ParamGroupEyes"))
        val convertedTree = PuppetIr.toIr(treeModel.copy(parameterTree = updatedTree)).parameterTree
        val links = input.parameterLinks
        val link = IrLink(EyeLegacyMotion.gazeX.raw, EyeLegacyMotion.gazeY.raw)
        require(links.none { it != link && (it.horizontal in setOf(link.horizontal, link.vertical) ||
            it.vertical in setOf(link.horizontal, link.vertical)) }) { "Incompatible eye gaze parameter link" }
        return ModifierModelChanges(parameters = ModifierObjectChanges(added = additions),
            meshes = ModifierObjectChanges(updated = updates), deformers = ModifierObjectChanges(updated = gazes),
            parameterLinks = if (link in links) null else ModifierFieldChange(links, links + link),
            parameterTree = convertedTree.takeIf { it != tree }?.let { ModifierFieldChange(tree, it) })
    }

    companion object {
        const val TYPE = "eye/legacy"
        const val VERSION = 2
        const val ENTRYPOINT = "builtin.6579652f6c6567616379.v2/legacy"
        val STYLED_HEAD = setOf("face/fitted", "face.depth-turn")
        val WRITES = setOf("mesh", "deformer", "parameter", "relations", "hierarchy")
    }
}
