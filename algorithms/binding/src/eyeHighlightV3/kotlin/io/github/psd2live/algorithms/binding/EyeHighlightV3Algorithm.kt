package io.github.psd2live.algorithms.binding

import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.model.*
import io.github.psd2live.targets.cubism.PuppetIr
import kotlinx.serialization.json.*
import org.umamo.runtime.eval.colorAt
import org.umamo.runtime.eval.meshGridDefaultDeltas
import org.umamo.runtime.eval.scalarAt
import org.umamo.runtime.model.FormChannel
import org.umamo.runtime.model.MeshForm
import org.umamo.runtime.model.ParameterId

/** Computes the fade and opposing head-angle translations for the existing highlight meshes. */
class EyeHighlightV3Algorithm : ReadingModifierAlgorithmPlugin {
    private data class Lowered(val mesh: Mesh, val warp: Deformer.Warp?)

    override fun changes(input: ModifierEvaluationInput, instance: RigModifierInstance, checkpoint: () -> Unit): ModifierModelChanges {
        require(instance.version == VERSION && instance.targets.all { it.kind == "mesh" })
        val context = input.context
        val captured = EyeHighlightInput.decode(context)
        val targets = instance.targets.toSet()
        require(captured.keys.all { ModifierTarget("mesh", it) in targets })
        val scaffold = instance.config.getValue("scaffold")
        val providers = mapOf("rig.scaffold" to scaffold, "eye/fitted" to instance.config.getValue("base"),
            "face/legacy" to instance.config.getValue("dependencies").jsonObject.getValue("face/legacy"))
        for ((capability, id) in providers) {
            require(input.providers(capability).any { it.instanceId == id.jsonPrimitive.content && it.targets.containsAll(targets) }) {
                "Missing eye highlight provider: $capability"
            }
        }
        if (captured.isEmpty()) return ModifierModelChanges()
        val iris = EyeSquashInputs.decode(ModifierAlgorithmContext(context.values.getValue("iris").jsonObject, context.resources), checkpoint)
        require(iris.keys == captured.keys) { "Eye highlight v3 requires the source iris topology" }
        val head = context.values["head_turn"]?.jsonObject
        val shell = head?.let {
            require(input.upstream.any { provider -> provider.capability in headProviders &&
                provider.instanceId == it.getValue("provider").jsonPrimitive.content && provider.config["scaffold"] == scaffold })
            val values = it.getValue("shell").jsonArray.map { point -> point.jsonPrimitive.float }
            require(values.size == 7 && values.all(Float::isFinite) && values[2] > 0f && values[3] > 0f)
            HeadTurn(values[0], values[1], values[2], values[3], values[4], values[5], values[6], checkpoint)
        }
        val turnName = context.values.getValue("turn_name").jsonPrimitive.content
        val parameters = input.parameters
        val parallax = parallax(instance.config)
        val additions = ArrayList<Deformer>()
        val refitted = ArrayList<ModifierObjectUpdate<Deformer>>()
        val updates = instance.targets.mapNotNull { target ->
            checkpoint()
            val source = captured[target.id] ?: return@mapNotNull null
            val measured = iris.getValue(target.id)
            require(measured.parent == source.parent)
            val prior = input.upstream.filter { it.config["scaffold"] == scaffold && target in it.targets }
            require(prior.none { it.capability in ownTypes }) { "Eye highlight v3 cannot replace an earlier highlight" }
            val opening = requireNotNull(input.parameter(source.opening)) { "Missing fitted eye opening parameter" }
            val wide = prior.any { it.capability == "eye.wide" }
            require(opening.min == 0f && opening.default == 1f && opening.max >= if (wide) 2f else 1f) {
                "Incompatible fitted eye opening parameter: ${opening.id}"
            }
            val live = requireNotNull(input.mesh(target.id))
            val branch = input.parentsOfMesh(target.id)
            val baseBranch = branch.dropWhile { it.id != source.parent }
            val carrier = if (live.parent == source.parent) null else branch.firstOrNull() as? Deformer.Warp
            require((source.parent == null || baseBranch.isNotEmpty()) &&
                (live.parent == source.parent || carrier != null && carrier.parent == source.parent &&
                    carrier.lattice?.axes.orEmpty().any { it.parameter in headAxes })) {
                "Eye highlight v3 requires the source frame or its head turn warp: ${live.id}"
            }
            val folded = carrier != null || live.offsets?.axes.orEmpty().any { it.parameter in headAxes } ||
                live.shapes.any { it.parameter in headAxes }
            require(!folded || shell != null && prior.none { it.capability == "eye.squash" }) {
                "Eye highlight v3 needs a captured head turn and an unsquashed source iris: ${live.id}"
            }
            val nativeId = head?.getValue("native_ids")?.jsonObject?.getValue(target.id)?.jsonPrimitive?.content ?: target.id
            val base = if (folded) live.copy(parent = source.parent, geometry = sourceGeometry(measured),
                offsets = jelly(measured, checkpoint), shapes = emptyList()) else live
            require(base.shapes.isEmpty()) { "Eye highlight v3 cannot replace edited highlight shapes: ${live.id}" }
            if (folded) {
                val old = lower(input.canvas, parameters, baseBranch, base, nativeId, measured, source,
                    shell, null, turnName, checkpoint)
                require(old.mesh.copy(parent = live.parent) == live &&
                    if (old.warp == null) carrier == null else carrier != null &&
                        old.warp.copy(id = carrier.id, part = carrier.part) == carrier) {
                    "Eye highlight v3 cannot reproduce the preceding highlight turn: ${live.id}"
                }
            }
            val channels = if (source.automaticOpacity) fade(input, live, source, wide, checkpoint) else live.channels
            val result = lower(input.canvas, parameters, baseBranch, base.copy(channels = channels), nativeId, measured, source,
                shell.takeIf { folded }, parallax, turnName, checkpoint)
            val warp = result.warp
            val parent = when {
                warp == null -> {
                    require(carrier == null) { "Eye highlight v3 cannot retire a preceding head turn warp" }
                    result.mesh.parent
                }
                carrier != null -> {
                    val replacement = warp.copy(id = carrier.id, part = carrier.part)
                    if (replacement != carrier) {
                        require(input.meshes.none { it.id != live.id && it.parent == carrier.id } &&
                            input.deformers.none { it.parent == carrier.id }) { "Eye highlight v3 cannot refit a shared head turn warp" }
                        refitted += ModifierObjectUpdate(carrier, replacement)
                    }
                    carrier.id
                }
                else -> {
                    require(base.geometry == sourceGeometry(measured)) { "Eye highlight v3 cannot reparent edited highlight geometry" }
                    val id = ScaffoldBinding.deformerId(instance, live.id, warp.id, shared = true)
                    require(input.deformer(id) == null && additions.none { it.id == id }) { "Eye highlight v3 head turn warp already exists: $id" }
                    additions += warp.copy(id = id)
                    id
                }
            }
            val after = result.mesh.copy(parent = parent)
            ModifierObjectUpdate(live, after).takeIf { after != live }
        }
        return ModifierModelChanges(meshes = ModifierObjectChanges(updated = updates),
            deformers = ModifierObjectChanges(added = additions, updated = refitted))
    }

    private fun sourceGeometry(source: EyeSquashInputs): MeshGeometry {
        val positions = source.raw.rigPositions
        val points = FloatArray(positions.size) { i ->
            val point = positions[i]
            if (source.parent == null) point
            else if (i % 2 == 0) (point - source.frame.left) / source.frame.width.coerceAtLeast(1e-4f)
            else (point - source.frame.top) / source.frame.height.coerceAtLeast(1e-4f)
        }
        return MeshGeometry(Floats.wrap(points), Floats.wrap(source.raw.atlasUvs), Ints.wrap(source.raw.triangles))
    }

    private fun jelly(source: EyeSquashInputs, checkpoint: () -> Unit): KeyGrid<MeshOffsets> {
        val keys = floatArrayOf(-1f, 0f, 1f)
        val points = source.raw.rigPositions
        return KeyGrid(listOf(KeyAxis("ParamEyeBallForm", Floats.wrap(keys))), keys.mapIndexed { key, amount ->
            val offsets = FloatArray(points.size)
            for (i in points.indices step 2) {
                if (i % 1024 == 0) checkpoint()
                val (x, y) = ScaffoldMesh.irisPoint(points[i], points[i + 1], source.iris, amount)
                offsets[i] = (x - points[i]) / source.frame.width.coerceAtLeast(1e-4f)
                offsets[i + 1] = (y - points[i + 1]) / source.frame.height.coerceAtLeast(1e-4f)
            }
            KeyCell(Ints.values(key), MeshOffsets(Floats.wrap(offsets)))
        })
    }

    private fun parallax(config: JsonObject): Float {
        val value = config["values"]?.jsonObject?.get("parallax")?.jsonPrimitive?.float ?: 100f
        require(value.isFinite() && value in 0f..200f) { "Highlight parallax must be within 0..200" }
        return value / 100f
    }

    private fun fade(input: ModifierEvaluationInput, mesh: Mesh, source: EyeHighlightInput, wide: Boolean,
                     checkpoint: () -> Unit): Channels {
        val normal = floatArrayOf(0f, source.crease, (source.crease + 1f) / 2f, 1f)
        val keys = if (wide) normal + 2f else normal
        val desired = KeyGrid(listOf(KeyAxis(source.opening, Floats.wrap(keys))), keys.indices.map { index ->
            KeyCell<ChannelValue>(Ints.wrap(intArrayOf(index)), ChannelValue.Scalar(if (index < 2) 0f else 1f))
        })
        val live = mesh.channels.filterKeys { it == Channel.OPACITY }
        val opacity = if (live.isEmpty() && mesh.opacity == source.opacity) desired else {
            val axes = live.values.flatMap { it.axes }.map { it.parameter }.toSet() + source.opening
            val actual = Mesh("actual", "actual", null, geometry = null, offsets = null, channels = live, opacity = mesh.opacity)
            val neutral = actual.copy(id = "neutral", opacity = source.opacity, channels = emptyMap())
            val generated = neutral.copy(id = "generated", channels = mapOf(Channel.OPACITY to desired))
            val model = PuppetIr.toPuppet(RigIR(Canvas(1f, 1f), axes.mapNotNull(input::parameter), meshes = listOf(actual, neutral, generated)))
            val (a, b, c) = model.drawables
            PuppetIr.meshToIr(a.copy(channelGrids = ChannelResidual.channels(model, a, b, c, checkpoint)))
                .channels.getValue(Channel.OPACITY)
        }
        return mesh.channels + (Channel.OPACITY to opacity)
    }

    private fun lower(canvas: Canvas, parameters: List<Parameter>, branch: List<Deformer>, mesh: Mesh, nativeId: String,
                      iris: EyeSquashInputs, source: EyeHighlightInput, shell: HeadTurn?, parallax: Float?,
                      turnName: String, checkpoint: () -> Unit): Lowered {
        val model = PuppetIr.toPuppet(RigIR(canvas, parameters, deformers = branch, meshes = listOf(mesh.copy(id = nativeId))))
        val drawable = model.drawables.single()
        val rest = { id: ParameterId -> if (id.raw == "ParamEyeLOpen" || id.raw == "ParamEyeROpen") 1f else 0f }
        val order = drawable.channelGrids.scalarAt(FormChannel.DRAW_ORDER, drawable.drawOrder, rest)
        val opacity = drawable.channelGrids.scalarAt(FormChannel.OPACITY, drawable.opacity, rest)
        val multiply = drawable.channelGrids.colorAt(FormChannel.MULTIPLY_COLOR, drawable.multiplyColor, rest)
        val screen = drawable.channelGrids.colorAt(FormChannel.SCREEN_COLOR, drawable.screenColor, rest)
        val neutral = meshGridDefaultDeltas(drawable, rest)
        val drift = parallax?.let {
            EyeModules.highlightShapes(iris.raw.rigPositions, iris.frame, Bounds(0f, 0f, source.irisWidth, 0f),
                EyeOptions(highlight = true, parallax = it))
        }.orEmpty()
        val head = shell?.shapes(iris.raw.rigPositions, iris.frame, HeadShell.SKIN,
            ParameterId("ParamAngleX"), ParameterId("ParamAngleY")).orEmpty()
        val shapes = (drift + head).map { binding -> binding.copy(forms = binding.forms.map { form -> form?.let {
            val deltas = if (neutral == null) it.positionDeltas else FloatArray(neutral.size) { i -> it.positionDeltas[i] + neutral[i] }
            MeshForm(deltas, order, opacity, multiply, screen)
        } }) }
        val lowered = BlendShapeLowering({ _, name -> turnName.replace("{meshName}", name) }, checkpoint)
            .lawful(model.copy(drawables = listOf(drawable.copy(blendShapes = shapes))))
        require(lowered.parameters == model.parameters) { "Eye highlight v3 requires bound head angles" }
        val carrier = lowered.deformers.drop(model.deformers.size).singleOrNull()
        return Lowered(PuppetIr.meshToIr(lowered.drawables.single()).copy(id = mesh.id),
            carrier?.let { PuppetIr.deformerToIr(it) as Deformer.Warp })
    }

    companion object {
        const val TYPE = "eye.highlight"
        const val VERSION = 3
        const val ENTRYPOINT = "builtin.6579652e686967686c69676874.v3/highlight"
        val WRITES = setOf("mesh", "deformer")
        private val ownTypes = setOf(TYPE, ENTRYPOINT, EyeHighlightAlgorithm.ENTRYPOINT)
        private val headProviders = EyeSquashAlgorithm.HEAD_TURN
        private val headAxes = setOf("ParamAngleX", "ParamAngleY", "ParamAngleZ")
        val optionalUpstream = ownTypes + headProviders + setOf("eye.wide", "eye.squash")
    }
}
