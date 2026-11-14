package io.github.psd2live.algorithms.binding

import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.model.*
import io.github.psd2live.targets.cubism.PuppetIr
import kotlinx.serialization.json.*
import org.umamo.edit.VertexSource
import org.umamo.runtime.model.FormChannel
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.MeshDeltaForm
import org.umamo.runtime.model.ChannelValue as PuppetChannelValue

/** Extends the fitted eye's opening in the current binding's source frame. */
class EyeWideAlgorithm : ReadingModifierAlgorithmPlugin {
    override fun changes(input: ModifierEvaluationInput, instance: RigModifierInstance, checkpoint: () -> Unit): ModifierModelChanges {
        require(instance.version == VERSION && instance.targets.all { it.kind == "mesh" })
        val scaffold = instance.config.getValue("scaffold")
        val base = instance.config.getValue("base")
        val captured = EyeWideInputs.decode(input.context, checkpoint)
        val source = captured.measurements
        val targets = instance.targets.toSet()
        require(source.targets.keys.all { ModifierTarget("mesh", it) in targets })
        require(input.providers("rig.scaffold").any { it.instanceId == scaffold.jsonPrimitive.content && it.targets.containsAll(targets) }) {
            "Missing eye widening scaffold provider"
        }
        val fitted = requireNotNull(input.providers("eye/fitted").singleOrNull {
            it.instanceId == base.jsonPrimitive.content && it.targets.containsAll(targets)
        }) { "Missing fitted eye provider" }
        val preceding = input.upstream.filter { provider -> provider.config["scaffold"] == scaffold && provider.targets.any { it in targets } }
            .groupBy { it.capability }
        require(preceding[TYPE].isNullOrEmpty()) { "Eye widening v2 cannot replace an earlier widening on the same mesh" }
        require(coupled.none { it in preceding }) { "Eye widening v2 cannot lower coupled lid, highlight or brow bindings" }
        val options = fitted.config["values"]?.jsonObject.orEmpty()
        val lift = knob(instance.config["values"]?.jsonObject.orEmpty(), "lift", 10f, 0f..80f)
        val lower = knob(options, "lowerShare", 5f, 0f..50f)
        val roundness = knob(options, "roundness", 100f, 40f..160f)
        val squash = knob(options, "lashSquash", 60f, 20f..100f)
        val align = { x: Float, y: Float -> source.alignment?.point(x, y) ?: (x to y) }
        val fits = EyeFitting.fit(captured.art, source.faceCenterX, align).fits.mapValues { (side, fit) ->
            checkpoint()
            fit.moved(source.moves[side.name].orEmpty())
        }
        if (fits.isEmpty() || captured.art.layers.none { it.tag == "EYELASH" && it.opaquePixels > 0 }) return ModifierModelChanges()
        val artwork = EyeArtworkFitting.fit(captured.art, source.artworkStamps, fits, source.corrections, align)
        val parameters = openings.mapNotNull { id ->
            val previous = requireNotNull(input.parameter(id)) { "Missing fitted eye opening parameter: $id" }
            require(previous.min == 0f && previous.default == 1f && previous.max >= 1f &&
                !previous.blend && !previous.repeat && previous.keys == null) { "Incompatible eye opening parameter: $id" }
            ModifierObjectUpdate(previous, previous.copy(max = maxOf(previous.max, 2f))).takeIf { it.before != it.after }
        }
        val rigsBySmile = HashMap<Pair<Float, Float>, Map<Side, EyeRig>>()
        val updates = instance.targets.mapNotNull { target ->
            checkpoint()
            val material = source.targets[target.id] ?: return@mapNotNull null
            fun providers(capability: String) = preceding[capability].orEmpty().filter { target in it.targets }
            val smile = providers("eye.smile").lastOrNull()?.config?.get("values")?.jsonObject.orEmpty()
            val prior = EyeOptions(smile = providers("eye.smile").isNotEmpty(),
                smileArc = .12f * knob(smile, "arch", 100f, 0f..200f),
                smilePush = knob(smile, "push", 50f, 0f..100f),
                lidFade = providers("eye.lid-fade").isNotEmpty(),
                squash = providers("eye.squash").isNotEmpty())
            val widened = prior.copy(wide = true, wideLift = lift)
            val eyes = rigsBySmile.getOrPut(prior.smileArc to prior.smilePush) {
                fits.mapValues { (side, fit) ->
                    EyeRig(fit.copy(artwork = artwork[side]), lower, roundness, squash, prior.smileArc, prior.smilePush, lift)
                }
            }
            val layer = captured.art.layerForTarget(target.id)
            val raw = captured.topology.getValue(material.layer)
            val mesh = requireNotNull(input.mesh(target.id))
            val before = geometry(layer, material, raw.rigPositions, eyes, prior, source)
            val after = geometry(layer, material, raw.rigPositions, eyes, widened, source)
            val opacityBefore = opacity(layer, material, eyes, prior)
            val opacityAfter = opacity(layer, material, eyes, widened)
            if (before == after && opacityBefore == opacityAfter) return@mapNotNull null
            if (headTurn.any { providers(it).isNotEmpty() }) {
                require(mesh.parent == material.parent && mesh.offsets?.axes.orEmpty().none { it.parameter in headAxes } &&
                    mesh.shapes.none { it.parameter in headAxes }) {
                    "Eye widening v2 cannot lower fitted head-turn carriers"
                }
            }
            val offsets = if (before == after) mesh.offsets else
                widenGeometry(input, mesh, material, raw, requireNotNull(before), requireNotNull(after), checkpoint)
            val channels = if (opacityBefore == opacityAfter) mesh.channels else
                mesh.channels + (Channel.OPACITY to widenOpacity(input, mesh, material, opacityBefore, requireNotNull(opacityAfter), checkpoint))
            val updated = mesh.copy(offsets = offsets, channels = channels)
            ModifierObjectUpdate(mesh, updated).takeIf { updated != mesh }
        }
        return ModifierModelChanges(parameters = ModifierObjectChanges(updated = parameters), meshes = ModifierObjectChanges(updated = updates))
    }

    private fun geometry(layer: ModifierSourceLayer, material: EyeWideInputs.Target, positions: FloatArray,
                         eyes: Map<Side, EyeRig>, options: EyeOptions, source: EyeWideInputs): KeyGrid<MeshOffsets>? {
        val side = Side.valueOf(layer.side)
        val frame = material.frame.bounds()
        val eye = eyes[side]
        val lid = EyeModules.meshOf(layer.tag, layer.role)
        val generated = when {
            lid != null && side == Side.NONE -> EyeModules.pairedLidGrid(lid, layer.id, positions, frame, FittedEyeMotion(eyes, options))
            lid != null && eye != null -> EyeModules.lidGrid(lid, side, positions, frame, eye, options, layer.id)
            layer.tag == "IRIDES" && eye != null && options.squash -> {
                val bottom = positions.indices.filter { it % 2 == 1 }.maxOf { positions[it] }
                val iris = ScaffoldMesh.Geometry.Iris(material.centroidX, material.centroidY, source.jellySquash, source.jellyStretch)
                EyeModules.irisGrid(side, positions, frame, Bounds(0f, bottom, 0f, bottom), eye, options) { x, y, value ->
                    ScaffoldMesh.irisPoint(x, y, iris, value)
                }
            }
            else -> null
        }
        return generated?.offsets()
    }

    private fun opacity(layer: ModifierSourceLayer, material: EyeWideInputs.Target, eyes: Map<Side, EyeRig>,
                        options: EyeOptions): KeyGrid<ChannelValue>? {
        if (!material.automaticOpacity) return null
        val side = Side.valueOf(layer.side)
        val eye = eyes[side]
        val swap = eye?.fit?.closed != null
        val max = if (options.wide) 2f else 1f
        val drawing = when {
            material.preset && layer.tag == "EYE_CLOSE" -> EyeModules.drawingOpacity(side, true, swap, max)
            material.preset && layer.tag == "EYELASH" && swap && (layer.role == null || layer.role == "LOWER") ->
                EyeModules.drawingOpacity(side, false, true, max)
            else -> null
        }
        val generated = drawing ?: eye?.let { EyeModules.opacity(layer.tag, layer.role, side, it, options) }
        return generated?.let { grid ->
            KeyGrid(grid.axes.map { KeyAxis(it.parameterId.raw, Floats.wrap(it.keys)) },
                grid.cells.map { KeyCell<ChannelValue>(Ints.wrap(it.coordinate), ChannelValue.Scalar((it.form as PuppetChannelValue.Scalar).value)) })
        }
    }

    private fun widenGeometry(input: ModifierEvaluationInput, mesh: Mesh, material: EyeWideInputs.Target,
                              raw: ScaffoldMesh.RawMesh, before: KeyGrid<MeshOffsets>, after: KeyGrid<MeshOffsets>,
                              checkpoint: () -> Unit): KeyGrid<MeshOffsets>? {
        val geometry = requireNotNull(mesh.geometry)
        require(geometry.uvs == Floats.wrap(raw.atlasUvs) && geometry.indices == Ints.wrap(raw.triangles)) {
            "Eye widening v2 requires the captured eye topology"
        }
        require(mesh.parent == material.parent) { "Eye widening v2 requires the fitted eye's parent frame" }
        val frame = material.frame.bounds()
        val original = Floats.wrap(FloatArray(raw.rigPositions.size) { i ->
            val value = raw.rigPositions[i]
            if (material.parent == null) value
            else if (i % 2 == 0) (value - frame.left) / frame.width.coerceAtLeast(1e-4f)
            else (value - frame.top) / frame.height.coerceAtLeast(1e-4f)
        })
        if (geometry.positions == original && mesh.offsets == before) return after
        val parameters = (mesh.offsets?.axes.orEmpty() + after.axes).map { it.parameter }.distinct().mapNotNull(input::parameter)
        val branch = input.parentsOfMesh(mesh.id)
        val current = PuppetIr.toPuppet(RigIR(input.canvas, parameters, deformers = branch, meshes = listOf(mesh)))
        val canonical = mesh.copy(geometry = geometry.copy(positions = original), offsets = null, shapes = emptyList(), channels = emptyMap())
        val stationary = BindingProjection.retain(PuppetIr.toPuppet(RigIR(input.canvas, input.parameters,
            deformers = branch, meshes = listOf(canonical))), emptySet(), checkpoint = checkpoint)
        fun projection(offsets: KeyGrid<MeshOffsets>) = stationary.copy(parameters = current.parameters, drawables =
            PuppetIr.toPuppet(RigIR(input.canvas, parameters, meshes = listOf(canonical.copy(offsets = offsets)))).drawables)
        val drawable = current.drawables.single()
        val vertices = List(original.size / 2) { VertexSource.FromOld(it) }
        val offsets = GeometryResidual.geometry(current, projection(before), projection(after), drawable.id, vertices, vertices, checkpoint)
        return offsets?.offsets()
    }

    private fun widenOpacity(input: ModifierEvaluationInput, mesh: Mesh, material: EyeWideInputs.Target,
                             before: KeyGrid<ChannelValue>?, after: KeyGrid<ChannelValue>,
                             checkpoint: () -> Unit): KeyGrid<ChannelValue> {
        val actual = mesh.channels[Channel.OPACITY]
        if (actual == before && mesh.opacity == material.opacity) return after
        val axes = (actual?.axes.orEmpty() + after.axes).map { it.parameter }.distinct()
        val live = Mesh("live", "live", null, geometry = null, offsets = null,
            channels = actual?.let { mapOf(Channel.OPACITY to it) }.orEmpty(), opacity = mesh.opacity)
        val previous = live.copy(id = "previous", opacity = material.opacity,
            channels = before?.let { mapOf(Channel.OPACITY to it) }.orEmpty())
        val desired = previous.copy(id = "desired", channels = mapOf(Channel.OPACITY to after))
        val model = PuppetIr.toPuppet(RigIR(Canvas(1f, 1f), axes.mapNotNull(input::parameter), meshes = listOf(live, previous, desired)))
        val (drawable, old, next) = model.drawables
        val channels = ChannelResidual.channels(model, drawable, old, next, checkpoint)
        return PuppetIr.meshToIr(drawable.copy(channelGrids = channels)).channels.getValue(Channel.OPACITY)
    }

    companion object {
        const val TYPE = "eye.wide"
        const val VERSION = 2
        const val ENTRYPOINT = "builtin.6579652e77696465.v2/wide"
        val openings = listOf(EyeModules.open(Side.LEFT).raw, EyeModules.open(Side.RIGHT).raw)
        private val headTurn = setOf("face/fitted", "face.depth-turn")
        private val coupled = setOf("eye.gaze-lid", "eye.highlight", "brow.blink", "brow.lid-coupling", "mouth.cheek-lid")
        val optionalUpstream = headTurn + coupled + setOf(TYPE, "eye.smile", "eye.squash", "eye.lid-fade")
        private val headAxes = setOf("ParamAngleX", "ParamAngleY", "ParamAngleZ")

        private fun ModifierSourceBounds.bounds() = Bounds(left, top, right, bottom)
        private fun knob(values: Map<String, JsonElement>, id: String, default: Float, range: ClosedFloatingPointRange<Float>) =
            (values[id]?.jsonPrimitive?.float ?: default).also { require(it in range) { "Invalid eye value: $id" } } / 100f
        private fun KeyformGrid<MeshDeltaForm>.offsets() = KeyGrid(
            axes.map { KeyAxis(it.parameterId.raw, Floats.wrap(it.keys)) },
            cells.map { KeyCell(Ints.wrap(it.coordinate), MeshOffsets(Floats.wrap(it.form.positionDeltas))) },
        )
    }
}
