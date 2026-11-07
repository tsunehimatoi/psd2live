package io.github.psd2live.algorithms.binding

import io.github.psd2live.algorithm.worker.AlgorithmWorker
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

/** Adds the source iris squash, retaining the preceding head turn and highlight drift. */
class EyeSquashV4Algorithm : ReadingModifierAlgorithmPlugin {
    private data class TurnIris(val live: Mesh, val base: Mesh, val branch: List<Deformer>, val carrier: Deformer.Warp?,
                                val nativeId: String, val source: EyeSquashInputs, val highlightReach: Pair<Float, Float>?)

    override fun changes(input: ModifierEvaluationInput, instance: RigModifierInstance, checkpoint: () -> Unit): ModifierModelChanges {
        require(instance.version == VERSION && instance.targets.all { it.kind == "mesh" })
        val context = input.context
        val legacy = instance.copy(version = EyeSquashAlgorithm.VERSION)
        val head = context.values["head_turn"]?.jsonObject
        val highlights = context.values["highlights"]?.jsonObject.orEmpty()
        if (head == null && highlights.isEmpty()) return EyeSquashAlgorithm().changes(input, legacy, checkpoint)
        // The v2 kernel receives only the source-frame iris. This adapter handles its head turn separately.
        val sourceContext = ModifierAlgorithmContext(JsonObject(context.values - setOf("head_turn", "highlights", "names")), context.resources,
            context.upstream.filterNot { it.capability in EyeSquashAlgorithm.HEAD_TURN })
        val sources = EyeSquashInputs.decode(sourceContext, checkpoint)
        val scaffold = instance.config.getValue("scaffold")
        val turn = head?.let {
            require(input.upstream.any { provider ->
                provider.capability in EyeSquashAlgorithm.HEAD_TURN && provider.config["scaffold"] == scaffold &&
                    provider.instanceId == it.getValue("provider").jsonPrimitive.content
            }) { "Eye squash v4 requires the captured head turn provider" }
            val shell = it.getValue("shell").jsonArray.map { point -> point.jsonPrimitive.float }
            require(shell.size == 7 && shell.all(Float::isFinite) && shell[2] > 0f && shell[3] > 0f)
            HeadTurn(shell[0], shell[1], shell[2], shell[3], shell[4], shell[5], shell[6], checkpoint)
        }
        val live = sources.keys.associateWith { requireNotNull(input.mesh(it)) }
        val branches = sources.keys.associateWith(input::parentsOfMesh)
        val lowered = sources.mapNotNull { (id, source) ->
            checkpoint()
            val mesh = live.getValue(id)
            val headKeys = mesh.offsets?.axes.orEmpty().any { it.parameter in headAxes } ||
                mesh.shapes.any { it.parameter in headAxes }
            if (mesh.parent == source.parent && !headKeys) return@mapNotNull null
            val branch = branches.getValue(id)
            val baseBranch = branch.dropWhile { it.id != source.parent }
            val carrier = if (mesh.parent == source.parent) null else branch.firstOrNull() as? Deformer.Warp
            require((source.parent == null || baseBranch.isNotEmpty()) &&
                (mesh.parent == source.parent || carrier != null && carrier.parent == source.parent &&
                    carrier.lattice?.axes.orEmpty().any { it.parameter in headAxes })) {
                "Eye squash v4 requires the source frame or its head turn warp: $id"
            }
            val positions = source.raw.rigPositions
            val points = Floats.wrap(FloatArray(positions.size) { i ->
                val point = positions[i]
                if (source.parent == null) point
                else if (i % 2 == 0) (point - source.frame.left) / source.frame.width.coerceAtLeast(1e-4f)
                else (point - source.frame.top) / source.frame.height.coerceAtLeast(1e-4f)
            })
            val canonical = mesh.copy(parent = source.parent,
                geometry = MeshGeometry(points, Floats.wrap(source.raw.atlasUvs), Ints.wrap(source.raw.triangles)),
                offsets = jelly(source, checkpoint), shapes = emptyList())
            val nativeId = head?.getValue("native_ids")?.jsonObject?.getValue(id)?.jsonPrimitive?.content ?: id
            val highlightReach = highlights[id]?.jsonObject?.let {
                val provider = requireNotNull(input.providers(HIGHLIGHT).singleOrNull { provider ->
                    provider.instanceId == it.getValue("provider").jsonPrimitive.content &&
                        provider.config["scaffold"] == scaffold && ModifierTarget("mesh", id) in provider.targets
                }) { "Eye squash v4 requires the captured highlight provider" }
                val amount = provider.config["values"]?.jsonObject?.get("parallax")?.jsonPrimitive?.float ?: 100f
                val width = it.getValue("irisWidth").jsonPrimitive.float
                require(amount.isFinite() && amount in 0f..200f && width.isFinite() && width >= 0f)
                width to amount / 100f
            }
            id to TurnIris(mesh, canonical, baseBranch, carrier, nativeId, source, highlightReach)
        }.toMap()
        val names = head ?: context.values.getValue("names").jsonObject
        val parameters = input.parameters
        val frameIds = branches.values.flatten().associateBy { it.id }.toMutableMap()
        // The authored-geometry residual also needs the source parent when it is off the live branch.
        for (source in sources.values) {
            var parent = source.parent
            while (parent != null && parent !in frameIds) {
                val frame = requireNotNull(input.deformer(parent)) { "Missing captured iris source frame: $parent" }
                frameIds[parent] = frame
                parent = frame.parent
            }
        }
        val projection = RigIR(input.canvas, parameters, meshes = live.values.map { lowered[it.id]?.base ?: it },
            deformers = frameIds.values.toList())
        val squash = EyeSquashAlgorithm().changes(projection, legacy, sourceContext, checkpoint)
        val added = ArrayList<Deformer>()
        val changed = ArrayList<ModifierObjectUpdate<Deformer>>()
        val meshes = squash.meshes.updated.map { update ->
            val iris = lowered[update.before.id] ?: return@map update
            fun matches(candidate: Pair<Mesh, Deformer.Warp?>): Boolean {
                val (before, warp) = candidate
                return before.copy(parent = iris.live.parent) == iris.live &&
                    if (warp == null) iris.carrier == null else iris.carrier != null &&
                        warp.copy(id = iris.carrier.id, part = iris.carrier.part) == iris.carrier
            }
            val turned = lower(input.canvas, parameters, iris, iris.base, turn, names, checkpoint)
            // A highlight can carry the angle keys by itself when the head provider contributed no mesh turn.
            val precedingTurn = if (matches(turned)) turn else {
                require(turn != null && iris.highlightReach != null &&
                    matches(lower(input.canvas, parameters, iris, iris.base, null, names, checkpoint))) {
                    "Eye squash v4 cannot reproduce preceding iris turn: ${iris.live.id}"
                }
                null
            }
            val (after, next) = lower(input.canvas, parameters, iris, update.after, precedingTurn, names, checkpoint)
            val parent = when {
                next == null -> {
                    require(iris.carrier == null) { "Eye squash v4 cannot retire a preceding head turn warp" }
                    after.parent
                }
                iris.carrier != null -> {
                    val replacement = next.copy(id = iris.carrier.id, part = iris.carrier.part)
                    if (replacement != iris.carrier) {
                        require(input.meshes.none { it.id != iris.live.id && it.parent == iris.carrier.id } &&
                            input.deformers.none { it.parent == iris.carrier.id }) { "Eye squash v4 cannot refit a shared head turn warp" }
                        changed += ModifierObjectUpdate(iris.carrier, replacement)
                    }
                    iris.carrier.id
                }
                else -> {
                    val id = ScaffoldBinding.deformerId(instance, iris.live.id, next.id, shared = true)
                    require(input.deformer(id) == null && added.none { it.id == id }) { "Eye squash v4 head turn warp already exists: $id" }
                    added += next.copy(id = id)
                    id
                }
            }
            ModifierObjectUpdate(iris.live, after.copy(parent = parent))
        }
        return ModifierModelChanges(meshes = ModifierObjectChanges(updated = meshes),
            deformers = ModifierObjectChanges(added = added, updated = changed))
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

    private fun lower(canvas: Canvas, parameters: List<Parameter>, iris: TurnIris, mesh: Mesh, turn: HeadTurn?,
                      names: JsonObject, checkpoint: () -> Unit): Pair<Mesh, Deformer.Warp?> {
        val model = PuppetIr.toPuppet(RigIR(canvas, parameters, meshes = listOf(mesh.copy(id = iris.nativeId)), deformers = iris.branch))
        val drawable = model.drawables.single()
        val defaults = model.parameters.associate { it.id to it.default }
        val pose = { id: ParameterId -> defaults[id] ?: 0f }
        val rest = meshGridDefaultDeltas(drawable, pose)
        val opacity = drawable.channelGrids.scalarAt(FormChannel.OPACITY, drawable.opacity, pose)
        val order = drawable.channelGrids.scalarAt(FormChannel.DRAW_ORDER, drawable.drawOrder, pose)
        val multiply = drawable.channelGrids.colorAt(FormChannel.MULTIPLY_COLOR, drawable.multiplyColor, pose)
        val screen = drawable.channelGrids.colorAt(FormChannel.SCREEN_COLOR, drawable.screenColor, pose)
        val source = iris.source
        val drift = iris.highlightReach?.let { (width, amount) ->
            EyeModules.highlightShapes(source.raw.rigPositions, source.frame, Bounds(0f, 0f, width, 0f),
                EyeOptions(highlight = true, parallax = amount))
        }.orEmpty()
        val head = turn?.shapes(source.raw.rigPositions, source.frame, HeadShell.SKIN,
            ParameterId("ParamAngleX"), ParameterId("ParamAngleY")).orEmpty()
        val shapes = (drift + head).map { binding -> binding.copy(forms = binding.forms.map { form -> form?.let {
                val values = it.positionDeltas
                MeshForm(if (rest == null) values else FloatArray(values.size) { i -> values[i] + rest[i] },
                    order, opacity, multiply, screen)
            } }) }
        val lowered = BlendShapeLowering({ head, label ->
            names.getValue(if (head) "turn_name" else "shape_name").jsonPrimitive.content.replace("{meshName}", label)
        }, checkpoint).lawful(model.copy(drawables = listOf(drawable.copy(blendShapes = shapes))))
        require(lowered.parameters == model.parameters) { "Eye squash v4 requires ordinary head turn parameters" }
        val carrier = lowered.deformers.drop(model.deformers.size).singleOrNull()
        return PuppetIr.meshToIr(lowered.drawables.single()).copy(id = mesh.id) to
            carrier?.let { PuppetIr.deformerToIr(it) as Deformer.Warp }
    }

    companion object {
        const val HIGHLIGHT = "eye.highlight"
        const val VERSION = 4
        const val ENTRYPOINT = "builtin.6579652e737175617368.v4/squash"
        val WRITES = setOf("mesh", "deformer")
        private val headAxes = setOf("ParamAngleX", "ParamAngleY", "ParamAngleZ")
    }
}

object EyeSquashV4Worker {
    @JvmStatic fun main(args: Array<String>) = AlgorithmWorker.run(mapOf(
        (EyeSquashV4Algorithm.ENTRYPOINT to EyeSquashV4Algorithm.VERSION) to EyeSquashV4Algorithm(),
    ))
}
