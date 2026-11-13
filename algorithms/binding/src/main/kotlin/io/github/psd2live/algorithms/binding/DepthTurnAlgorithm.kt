package io.github.psd2live.algorithms.binding

import io.github.psd2live.core.fit.FaceTurn
import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.model.RigIR
import io.github.psd2live.targets.cubism.PuppetIr
import org.umamo.runtime.eval.colorAt
import org.umamo.runtime.eval.meshGridDefaultDeltas
import org.umamo.runtime.eval.scalarAt
import org.umamo.runtime.model.*

/** Computes the depth-turn addition on the canonical output of the preceding native bindings. */
class DepthTurnAlgorithm : ChangingModifierAlgorithmPlugin {
    override fun changes(input: RigIR, instance: RigModifierInstance, context: ModifierAlgorithmContext,
                         checkpoint: () -> Unit): ModifierModelChanges {
        require(instance.version >= 1 && instance.targets.all { it.kind == "mesh" }) { "Invalid depth-turn invocation" }
        val scaffold = requireNotNull(context.upstream.singleOrNull { it.capability == "rig.scaffold" }) {
            "Depth turn requires an explicit scaffold provider"
        }
        require(context.upstream.count { it.capability == "face/legacy" } == 1) {
            "Depth turn requires an explicit base face provider"
        }
        val source = PuppetIr.toPuppet(input)
        val result = project(source, instance.targets.mapTo(linkedSetOf()) { it.id }, DepthTurnInputs.decode(context), checkpoint)
        val beforeMeshes = input.meshes.associateBy { it.id }
        val beforeDrawables = source.drawables.associateBy { it.id }
        val beforeDeformers = input.deformers.associateBy { it.id }
        val beforeParameters = input.parameters.associateBy { it.id }
        val newIds = result.deformers.filter { it.id.raw !in beforeDeformers }.associateBy { it.id }
        val shared = scaffold.version >= 2
        val outputs = LinkedHashMap<DeformerId, DeformerId>()
        for (drawable in result.drawables) {
            var parent = drawable.parentDeformerId
            while (parent != null && parent in newIds) {
                val id = DeformerId(ScaffoldBinding.deformerId(instance, drawable.id.raw, parent.raw, shared))
                val existing = outputs.putIfAbsent(parent, id)
                require(existing == null || existing == id) { "Incompatible depth turn carrier ownership" }
                parent = newIds.getValue(parent).parent
            }
        }
        require(outputs.keys == newIds.keys) { "Depth turn emitted an unreferenced carrier" }
        val remap = ModelIdRemap(deformers = outputs)
        return ModifierModelChanges(
            meshes = ModifierObjectChanges(updated = result.drawables.mapNotNull { drawable ->
                if (beforeDrawables[drawable.id] === drawable) null
                else {
                    checkpoint()
                    val before = beforeMeshes.getValue(drawable.id.raw)
                    val after = PuppetIr.meshToIr(remap.drawable(drawable))
                    update(before, before.copy(parent = after.parent, geometry = after.geometry, offsets = after.offsets,
                        channels = after.channels, shapes = after.shapes))
                }
            }),
            deformers = ModifierObjectChanges(
                added = newIds.values.map { PuppetIr.deformerToIr(remap.deformer(it)) },
                updated = result.deformers.mapNotNull { deformer ->
                    beforeDeformers[deformer.id.raw]?.let { update(it, PuppetIr.deformerToIr(deformer)) }
                },
            ),
            parameters = ModifierObjectChanges(updated = PuppetIr.parametersToIr(result).mapNotNull {
                update(beforeParameters.getValue(it.id), it)
            }),
        )
    }

    fun project(
        source: PuppetModel, targets: Set<String>, inputs: DepthTurnInputs, checkpoint: () -> Unit = {},
    ): PuppetModel {
        checkpoint()
        require(targets.all { id -> id in inputs.layers && source.drawables.any { it.id.raw == id } }) {
            "Missing depth turn mesh or layer measurements"
        }
        if (targets.isEmpty() || inputs.layers.values.none { it.tag == "FACE" }) return source
        require(source.parameters.any { it.id == yaw } && source.parameters.any { it.id == pitch }) {
            "Depth turn requires the base face Angle X and Angle Y parameters"
        }
        val shell = HeadTurn(inputs.container.pivotX, inputs.centerY, inputs.radiusX, inputs.radiusY,
            inputs.shellTurn, inputs.shellDepth, inputs.chinY, checkpoint)
        val style = FaceTurn(inputs.landmarks, inputs.style).takeIf { it.usable }
        val parents = source.deformers.associate { it.id to it.parent }
        fun ancestors(parent: DeformerId?) = buildSet {
            var id = parent
            while (id != null) {
                require(id in parents && add(id)) { "Invalid depth turn branch" }
                id = parents[id]
            }
        }
        val selectedBranches = source.drawables.filter { it.id.raw in targets }
            .flatMapTo(hashSetOf()) { ancestors(it.parentDeformerId) }
        val underFace = if (style == null) emptySet() else parents.keys.filterTo(hashSetOf()) {
            face in ancestors(parents[it])
        }
        // In the native pipeline face lean is added after the styled field and after mouth warp sampling.
        val unleaned = source.deformers.map { deformer ->
            if (deformer.id !in selectedBranches || deformer !is Deformer.Warp) deformer
            else if (style != null && (deformer.id == face || deformer.id == contour || deformer.id in underFace ||
                        deformer.id.raw in hairFollow) || deformer.id == mouthWarp && inputs.mouthWarp != null) {
                withoutLean(deformer)
            } else deformer
        }
        val posed = if (style != null) DepthTurnMotion.deformers(unleaned, style, inputs.head, inputs.face, inputs.container,
            inputs.frames, underFace, inputs.fittedEyes, inputs.fittedBrows, inputs.landmarks, checkpoint)
        else unleaned.map { deformer ->
            if (deformer.id == mouthWarp && deformer.id in selectedBranches && deformer is Deformer.Warp)
                inputs.mouthWarp?.place(deformer, shell, inputs.container.pivotX, inputs.radiusX, inputs.container.strength, checkpoint)
                    ?: deformer
            else deformer
        }
        val deformers = DepthTurnMotion.withFaceLean(posed, inputs.leanDown, inputs.leanUp).mapIndexed { i, deformer ->
            if (deformer.id in selectedBranches) deformer else source.deformers[i]
        }
        val raw = inputs.sourceMeshes?.let { ScaffoldMeshContext.decodeRawMeshes(it, checkpoint) }.orEmpty()
        val mouth = inputs.mouth?.rig()
        val faceOrder = source.drawables.filter { inputs.layers[it.id.raw]?.tag == "FACE" }.minOfOrNull { it.drawOrder }
        val meshes = source.drawables.map { drawable ->
            checkpoint()
            if (drawable.id.raw !in targets) return@map drawable
            val layer = inputs.layers.getValue(drawable.id.raw)
            val geometry = drawable.mesh ?: return@map drawable
            val parent = drawable.parentDeformerId
            val onFace = parent == contour || parent in underFace
            val shellOfLayer = DepthTurnMotion.shell(layer.tag, layer.role)
            val shaped = when {
                style != null && styled(layer) -> DepthTurnMotion.styledShapes(layer.tag,
                    positions(geometry, layer, raw), layer.frame, inputs.head, inputs.container, style, checkpoint)
                shellOfLayer == null || parent?.raw?.startsWith("DeformMouthShape") == true || style != null && onFace -> emptyList()
                else -> {
                    val rest = positions(geometry, layer, raw)
                    val closed = when {
                        mouth != null && layer.closesFace -> mouth.atRestFace(rest, inputs.mouthChin)
                        mouth != null && layer.follow != null -> mouth.atRest(rest, layer.follow, inputs.mouthChin)
                        else -> rest
                    }
                    shell.shapes(closed, layer.frame, shellOfLayer, yaw, pitch)
                }
            }
            val bound = if (shaped.isEmpty()) drawable else {
                val additions = neutralize(drawable, shaped)
                val index = drawable.blendShapes.indexOfFirst { it.parameterId.raw in lateShapes }
                    .takeIf { it >= 0 } ?: drawable.blendShapes.size
                drawable.copy(blendShapes = drawable.blendShapes.take(index) + additions + drawable.blendShapes.drop(index))
            }
            if (faceOrder != null && layer.tag == "FRONT_HAIR" && layer.role == "SIDE")
                DepthTurnMotion.sideLock(bound, faceOrder, layer.farRight)
            else bound
        }
        val native = source.copy(deformers = deformers, drawables = meshes)
        val lowered = BlendShapeLowering({ head, name ->
            (if (head) inputs.turnName else inputs.shapeName).replace("{meshName}", name)
        }, checkpoint).lawful(native)
        val retained = source.drawables.associateBy { it.id }
        val byId = lowered.deformers.associateBy { it.id }
        val emitted = lowered.drawables.filter { it.id.raw in targets }.flatMapTo(hashSetOf()) { mesh ->
            buildList {
                var parent = mesh.parentDeformerId
                while (parent != null && parent !in parents) {
                    add(parent)
                    parent = byId.getValue(parent).parent
                }
            }
        }
        return lowered.copy(drawables = lowered.drawables.map {
            if (it.id.raw in targets) it else retained.getValue(it.id)
        }, deformers = lowered.deformers.filter { it.id in parents || it.id in emitted })
    }

    private fun positions(geometry: DrawableMesh, layer: DepthTurnInputs.Layer, raw: Map<String, ScaffoldMesh.RawMesh>): FloatArray {
        layer.sourceMesh?.let { id ->
            val source = raw.getValue(id)
            val frame = layer.frame
            if (source.rigPositions.size == geometry.positions.size && source.triangles.contentEquals(geometry.indices) &&
                source.rigPositions.indices.all { i ->
                    val local = if (i % 2 == 0) (source.rigPositions[i] - frame.left) / frame.width.coerceAtLeast(1e-4f)
                        else (source.rigPositions[i] - frame.top) / frame.height.coerceAtLeast(1e-4f)
                    local.toRawBits() == geometry.positions[i].toRawBits()
                }) return source.rigPositions
        }
        return FloatArray(geometry.positions.size) { i ->
            if (i % 2 == 0) layer.frame.left + geometry.positions[i] * layer.frame.width
            else layer.frame.top + geometry.positions[i] * layer.frame.height
        }
    }

    private fun styled(layer: DepthTurnInputs.Layer): Boolean = when (layer.tag) {
        "FRONT_HAIR", "BACK_HAIR", "EARWEAR" -> true
        "HEADWEAR" -> layer.role !in setOf("RIBBON", "PENDANT", "ANIMAL")
        else -> false
    }

    private fun withoutLean(warp: Deformer.Warp): Deformer.Warp {
        val grid = warp.geometryGrid ?: return warp
        val i = grid.axes.indexOfFirst { it.parameterId == lean }
        if (i < 0) return warp
        val neutral = grid.axes[i].keys.indexOfFirst { it == 0f }
        require(neutral >= 0) { "Depth turn needs the neutral body lean key" }
        return warp.copy(geometryGrid = KeyformGrid(grid.axes.filterIndexed { j, _ -> j != i },
            grid.cells.filter { it.coordinate[i] == neutral }.map { cell ->
                KeyformCell(cell.coordinate.filterIndexed { j, _ -> j != i }.toIntArray(), cell.form)
            }))
    }

    private fun neutralize(drawable: Drawable, shapes: List<BlendShapeBinding<MeshForm>>): List<BlendShapeBinding<MeshForm>> {
        val pose = { id: ParameterId -> if (id.raw == "ParamEyeLOpen" || id.raw == "ParamEyeROpen") 1f else 0f }
        val channels = drawable.channelGrids
        val rest = meshGridDefaultDeltas(drawable, pose)
        val opacity = channels.scalarAt(FormChannel.OPACITY, drawable.opacity, pose)
        val order = channels.scalarAt(FormChannel.DRAW_ORDER, drawable.drawOrder, pose)
        val multiply = channels.colorAt(FormChannel.MULTIPLY_COLOR, drawable.multiplyColor, pose)
        val screen = channels.colorAt(FormChannel.SCREEN_COLOR, drawable.screenColor, pose)
        return shapes.map { binding -> binding.copy(forms = binding.forms.map { form ->
            form?.let {
                val delta = if (rest?.size == it.positionDeltas.size)
                    FloatArray(it.positionDeltas.size) { i -> it.positionDeltas[i] + rest[i] } else it.positionDeltas
                MeshForm(delta, order, opacity, multiply, screen)
            }
        }) }
    }

    private fun <T> update(before: T, after: T) = if (before == after) null else ModifierObjectUpdate(before, after)

    companion object {
        private val yaw = ParameterId("ParamAngleX")
        private val pitch = ParameterId("ParamAngleY")
        private val lean = ParameterId("ParamBodyLean")
        private val face = DeformerId("DeformFaceNinePose")
        private val contour = DeformerId("DeformFaceContour")
        private val mouthWarp = DeformerId("DeformMouthShapeBoth")
        private val hairFollow = setOf("DeformHairFrontFollow", "DeformHairBackFollow")
        private val lateShapes = setOf("ParamBustX", "ParamBustY", "ParamAngleZ")
    }
}
