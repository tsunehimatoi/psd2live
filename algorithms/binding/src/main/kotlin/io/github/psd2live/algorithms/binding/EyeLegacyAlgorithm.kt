package io.github.psd2live.algorithms.binding

import io.github.psd2live.core.modifier.ModifierFieldChange
import io.github.psd2live.core.modifier.ModifierModelChanges
import io.github.psd2live.core.modifier.ModifierObjectChanges
import io.github.psd2live.core.modifier.ModifierObjectUpdate
import io.github.psd2live.format.model.RigIR
import io.github.psd2live.targets.cubism.PuppetIr
import org.umamo.runtime.model.*

/** Computes the canonical eye transition, leaving the host's binding merge to transfer author residuals. */
class EyeLegacyAlgorithm(private val inputs: EyeLegacyInputs, checkpoint: () -> Unit = {}) {
    private val gazeIds = Side.entries.mapTo(HashSet(), ScaffoldHead::gazeId)
    private val rawMeshes = ScaffoldMeshContext.decodeRawMeshes(inputs.sourceMeshes, checkpoint)

    /** Typed changes for a canonical neutral scaffold. Author-space application still needs BindingMerge. */
    fun canonicalChanges(input: RigIR, definitions: List<Parameter>, groupName: String,
                         checkpoint: () -> Unit = {}): ModifierModelChanges {
        val meshes = input.meshes.filter { it.id in inputs.layers }
        val deformers = input.deformers.filter { DeformerId(it.id) in gazeIds }
        val source = PuppetIr.toPuppet(RigIR(input.canvas, input.parameters, meshes = meshes, deformers = deformers,
            parameterTree = input.parameterTree, parameterLinks = input.parameterLinks))
        val result = project(source, definitions, groupName, checkpoint)
        val affected = PuppetIr.toIr(PuppetModel(result.parameters, emptyList(), emptyList(), emptyList(), emptyList(), null,
            parameterTree = result.parameterTree, parameterLinks = result.parameterLinks))
        val meshUpdates = result.drawables.zip(source.drawables).mapIndexedNotNull { index, (mesh, before) ->
            if (mesh === before) return@mapIndexedNotNull null
            val original = meshes[index]
            val converted = PuppetIr.meshToIr(mesh)
            val updated = original.copy(offsets = converted.offsets, channels = converted.channels, maskedBy = converted.maskedBy)
            if (updated == original) null else ModifierObjectUpdate(original, updated)
        }
        val warpUpdates = result.deformers.zip(source.deformers).mapIndexedNotNull { index, (warp, before) ->
            if (warp === before) return@mapIndexedNotNull null
            val original = deformers[index]
            val updated = PuppetIr.deformerToIr(warp)
            if (updated == original) null else ModifierObjectUpdate(original, updated)
        }
        return ModifierModelChanges(
            parameters = ModifierObjectChanges(added = affected.parameters.drop(input.parameters.size)),
            meshes = ModifierObjectChanges(updated = meshUpdates),
            deformers = ModifierObjectChanges(updated = warpUpdates),
            parameterLinks = ModifierFieldChange(input.parameterLinks, affected.parameterLinks),
            parameterTree = ModifierFieldChange(input.parameterTree, affected.parameterTree),
        )
    }

    fun before(source: PuppetModel): PuppetModel = source.copy(drawables = source.drawables.map { drawable ->
        val layer = inputs.layers[drawable.id.raw] ?: return@map drawable
        drawable.copy(maskedBy = layer.originalMasks.map(::DrawableId))
    })

    fun project(
        source: PuppetModel,
        definitions: List<Parameter>,
        groupName: String,
        checkpoint: () -> Unit = {},
    ): PuppetModel {
        require(definitions.map { it.id } == listOf(EyeLegacyMotion.leftOpen, EyeLegacyMotion.rightOpen,
            EyeLegacyMotion.gazeX, EyeLegacyMotion.gazeY, EyeLegacyMotion.irisForm)) { "Invalid legacy eye declarations" }
        require(source.parameters.none { old -> definitions.any { it.id == old.id } }) { "Expected a neutral eye scaffold" }
        val drawables = source.drawables.map { drawable ->
            checkpoint()
            val layer = inputs.layers[drawable.id.raw] ?: return@map drawable
            val positions = rawMeshes.getValue(drawable.id.raw).rigPositions
            val geometry = when {
                layer.blink != null -> EyeLegacyMotion.blink(positions, layer.frame, layer.blink, layer.side, inputs.faceCenterX, checkpoint)
                layer.iris != null -> EyeLegacyMotion.iris(positions, layer.frame, layer.iris, checkpoint)
                else -> drawable.geometryGrid
            }
            val tracks = if (!layer.closedDrawing) drawable.channelGrids else ChannelGrids(
                drawable.channelGrids.gridsByChannel + (FormChannel.OPACITY to EyeLegacyMotion.closedDrawing(layer.side)))
            val masks = layer.irisSource?.let { iris ->
                val selected = EyeOpeningMatch.select(iris, inputs.openings).mapTo(HashSet()) { it.sourceId }
                inputs.openings.filter { it.sourceId in selected }.map { DrawableId(it.id) }
            } ?: drawable.maskedBy
            drawable.copy(geometryGrid = geometry, channelGrids = tracks, maskedBy = masks)
        }
        val gaze = EyeLegacyMotion.gaze(inputs.tuning)
        val deformers = source.deformers.map { deformer ->
            checkpoint()
            if (deformer is Deformer.Warp && deformer.id in gazeIds) deformer.copy(geometryGrid = gaze) else deformer
        }
        val tree = ParameterNode.Group(ParameterGroupId("ParamGroupEyes"), groupName, true,
            definitions.map { ParameterNode.Param(it.id) })
        return source.copy(drawables = drawables, deformers = deformers, parameters = source.parameters + definitions,
            parameterTree = source.parameterTree + tree,
            parameterLinks = source.parameterLinks + ParameterLink(EyeLegacyMotion.gazeX, EyeLegacyMotion.gazeY))
    }
}
