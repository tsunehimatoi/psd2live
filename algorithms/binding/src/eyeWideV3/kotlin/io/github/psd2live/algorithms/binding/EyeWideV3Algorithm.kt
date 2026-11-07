package io.github.psd2live.algorithms.binding

import io.github.psd2live.algorithm.worker.AlgorithmWorker
import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.model.*
import io.github.psd2live.targets.cubism.PuppetIr
import kotlinx.serialization.json.*
import org.umamo.runtime.eval.colorAt
import org.umamo.runtime.eval.meshGridDefaultDeltas
import org.umamo.runtime.eval.scalarAt
import org.umamo.runtime.model.KeyformAxis
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.FormChannel
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.MeshDeltaForm
import org.umamo.runtime.model.MeshForm
import org.umamo.runtime.model.ParameterId

/** Widens lids in their source frame, then lowers the same head turn over the new keyforms. */
class EyeWideV3Algorithm : ReadingModifierAlgorithmPlugin {
    private data class TurnMesh(val live: Mesh, val base: Mesh, val branch: List<Deformer>, val carrier: Deformer.Warp?,
                                val nativeId: String, val raw: ScaffoldMesh.RawMesh, val frame: Bounds,
                                val shell: HeadShell)

    override fun changes(input: ModifierEvaluationInput, instance: RigModifierInstance, checkpoint: () -> Unit): ModifierModelChanges {
        require(instance.version == VERSION)
        val context = input.context
        val legacy = instance.copy(version = EyeWideAlgorithm.VERSION)
        val head = context.values["head_turn"]?.jsonObject ?: return EyeWideAlgorithm().changes(input, legacy, checkpoint)
        val measurements = ModifierAlgorithmContext(JsonObject(context.values - "head_turn"), context.resources, context.upstream)
        val captured = EyeWideInputs.decode(measurements, checkpoint)
        val source = captured.measurements
        val scaffold = instance.config.getValue("scaffold")
        val base = instance.config.getValue("base").jsonPrimitive.content
        val fitted = input.providers("eye/fitted").single { it.instanceId == base }
        val preceding = input.upstream.filter { it.config["scaffold"] == scaffold }
        require(preceding.any { it.instanceId == head.getValue("provider").jsonPrimitive.content && it.capability in HEAD_PROVIDERS })
        val shell = head.getValue("shell").jsonArray.map { it.jsonPrimitive.float.also { value -> require(value.isFinite()) } }
        require(shell.size == 7 && shell[2] > 0f && shell[3] > 0f)
        val turn = HeadTurn(shell[0], shell[1], shell[2], shell[3], shell[4], shell[5], shell[6], checkpoint)
        val names = head.getValue("native_ids").jsonObject
        val fits by lazy {
            val align = { x: Float, y: Float -> source.alignment?.point(x, y) ?: (x to y) }
            val fittedEyes = EyeFitting.fit(captured.art, source.faceCenterX, align).fits.mapValues { (side, fit) ->
                checkpoint()
                fit.moved(source.moves[side.name].orEmpty())
            }
            val artwork = EyeArtworkFitting.fit(captured.art, source.artworkStamps, fittedEyes, source.corrections, align)
            fittedEyes.mapValues { (side, fit) -> fit.copy(artwork = artwork[side]) }
        }
        val meshes = instance.targets.map { requireNotNull(input.mesh(it.id)) }
        val turnMeshes = instance.targets.zip(meshes).mapNotNull { (target, mesh) ->
            checkpoint()
            val material = source.targets[target.id] ?: return@mapNotNull null
            val folded = mesh.offsets?.axes.orEmpty().any { it.parameter in HEAD_AXES } ||
                mesh.shapes.any { it.parameter in HEAD_AXES }
            if (mesh.parent == material.parent && !folded) return@mapNotNull null
            val raw = captured.topology.getValue(material.layer)
            val layer = captured.art.layerForTarget(target.id)
            val frame = material.frame.run { Bounds(left, top, right, bottom) }
            val headShell = requireNotNull(DepthTurnMotion.shell(layer.tag, layer.role))
            val branch = input.parentsOfMesh(mesh.id)
            val parent = material.parent?.let { id -> branch.singleOrNull { it.id == id } }
            val carrier = if (mesh.parent == material.parent) null else branch.firstOrNull() as? Deformer.Warp
            val nativeId = names.getValue(mesh.id).jsonPrimitive.content
            require((material.parent == null || parent != null) &&
                (mesh.parent == material.parent || carrier != null && carrier.parent == material.parent &&
                    carrier.lattice?.axes.orEmpty().any { it.parameter in HEAD_AXES })) {
                "Eye widening v3 requires the source frame or its head turn warp: ${mesh.id}"
            }
            val local = preceding.filter { target in it.targets }
            require(local.none { it.capability in UNSUPPORTED_LOWERING }) {
                "Eye widening v3 cannot migrate head turns with preceding lid couplings: ${mesh.id}"
            }
            val smile = local.lastOrNull { it.capability == "eye.smile" }
            val options = EyeOptions(smile = smile != null,
                smileArc = .12f * knob(smile?.config, "arch", 100f, 0f..200f),
                smilePush = knob(smile?.config, "push", 50f, 0f..100f),
                squash = local.any { it.capability == "eye.squash" },
                lidFade = local.any { it.capability == "eye.lid-fade" })
            val eyes = fits.mapValues { (_, fit) ->
                EyeRig(fit, knob(fitted.config, "lowerShare", 5f, 0f..50f),
                    knob(fitted.config, "roundness", 100f, 40f..160f),
                    knob(fitted.config, "lashSquash", 60f, 20f..100f), options.smileArc, options.smilePush, .1f)
            }
            val points = Floats.wrap(FloatArray(raw.rigPositions.size) { i ->
                val point = raw.rigPositions[i]
                if (material.parent == null) point
                else if (i % 2 == 0) (point - frame.left) / frame.width.coerceAtLeast(1e-4f)
                else (point - frame.top) / frame.height.coerceAtLeast(1e-4f)
            })
            val offsets = sourceOffsets(layer, material, raw.rigPositions, frame, eyes, options, source)
            val canonical = mesh.copy(parent = material.parent, geometry = MeshGeometry(points,
                Floats.wrap(raw.atlasUvs), Ints.wrap(raw.triangles)), offsets = offsets, shapes = emptyList())
            TurnMesh(mesh, canonical, branch.dropWhile { it.id != material.parent }, carrier, nativeId, raw, frame, headShell)
        }.associateBy { it.live.id }
        val parameters = input.parameters
        val branches = meshes.flatMap { input.parentsOfMesh(it.id) }.distinctBy { it.id }
        val projection = RigIR(input.canvas, parameters, meshes = meshes.map { turnMeshes[it.id]?.base ?: it }, deformers = branches)
        val widened = EyeWideAlgorithm().changes(projection, legacy, measurements, checkpoint)
        val newParameters = widened.parameters.apply(parameters, { it.id }, checkpoint)
        val additions = ArrayList<Deformer>()
        val updates = ArrayList<ModifierObjectUpdate<Deformer>>()
        val replacements = widened.meshes.updated.map { update ->
            val target = turnMeshes[update.before.id] ?: return@map update
            val (before, old) = lower(input.canvas, parameters, target, target.base, turn, head, checkpoint)
            val (after, new) = lower(input.canvas, newParameters, target, update.after, turn, head, checkpoint)
            require(before.copy(parent = target.live.parent) == target.live &&
                if (old == null) target.carrier == null else target.carrier != null &&
                    old.copy(id = target.carrier.id, part = target.carrier.part) == target.carrier) {
                "Eye widening v3 cannot reproduce the preceding head turn: ${target.live.id}"
            }
            val parent = when {
                new == null -> {
                    require(old == null) { "Eye widening v3 cannot retire a preceding head turn warp" }
                    after.parent
                }
                target.carrier != null -> {
                    val replacement = new.copy(id = target.carrier.id, part = target.carrier.part)
                    if (replacement != target.carrier) {
                        require(input.meshes.none { it.id != target.live.id && it.parent == target.carrier.id } &&
                            input.deformers.none { it.parent == target.carrier.id }) {
                            "Eye widening v3 cannot refit a shared head turn warp"
                        }
                        updates += ModifierObjectUpdate(target.carrier, replacement)
                    }
                    target.carrier.id
                }
                else -> {
                    val id = ScaffoldBinding.deformerId(instance, target.live.id, new.id, shared = true)
                    require(input.deformer(id) == null) { "Eye widening v3 head turn warp already exists: $id" }
                    additions += new.copy(id = id)
                    id
                }
            }
            ModifierObjectUpdate(target.live, after.copy(parent = parent))
        }
        return widened.copy(meshes = ModifierObjectChanges(updated = replacements),
            deformers = ModifierObjectChanges(added = additions, updated = updates))
    }

    private fun sourceOffsets(layer: ModifierSourceLayer, material: EyeWideInputs.Target, positions: FloatArray,
                              frame: Bounds, eyes: Map<Side, EyeRig>, options: EyeOptions, source: EyeWideInputs): KeyGrid<MeshOffsets> {
        val side = Side.valueOf(layer.side)
        val eye = eyes[side]
        val lid = EyeModules.meshOf(layer.tag, layer.role)
        val grid = when {
            lid != null && side == Side.NONE ->
                EyeModules.pairedLidGrid(lid, layer.id, positions, frame, FittedEyeMotion(eyes, options))
            lid != null && eye != null -> EyeModules.lidGrid(lid, side, positions, frame, eye, options, layer.id)
            layer.tag == "EYE_CLOSE" && eye != null -> EyeModules.closedLineGrid(side, positions, frame, eye, options)
            layer.tag == "IRIDES" -> {
                val iris = ScaffoldMesh.Geometry.Iris(material.centroidX, material.centroidY, source.jellySquash, source.jellyStretch)
                val bottom = (1 until positions.size step 2).maxOf { positions[it] }
                eye?.let {
                    EyeModules.irisGrid(side, positions, frame, Bounds(0f, bottom, 0f, bottom), it, options) { x, y, value ->
                        ScaffoldMesh.irisPoint(x, y, iris, value)
                    }
                } ?: KeyformGrid(listOf(KeyformAxis(ParameterId("ParamEyeBallForm"), floatArrayOf(-1f, 0f, 1f))),
                    (-1..1).mapIndexed { index, amount ->
                        val deltas = FloatArray(positions.size)
                        for (i in positions.indices step 2) {
                            val (x, y) = ScaffoldMesh.irisPoint(positions[i], positions[i + 1], iris, amount.toFloat())
                            deltas[i] = (x - positions[i]) / frame.width.coerceAtLeast(1e-4f)
                            deltas[i + 1] = (y - positions[i + 1]) / frame.height.coerceAtLeast(1e-4f)
                        }
                        KeyformCell(intArrayOf(index), MeshDeltaForm(deltas))
                    })
            }
            else -> null
        }
        return grid?.offsets() ?: KeyGrid(emptyList(), listOf(KeyCell(Ints.wrap(intArrayOf()), MeshOffsets(Floats.wrap(FloatArray(positions.size))))))
    }

    private fun lower(canvas: Canvas, parameters: List<Parameter>, target: TurnMesh, mesh: Mesh, turn: HeadTurn,
                      names: JsonObject, checkpoint: () -> Unit): Pair<Mesh, Deformer.Warp?> {
        val model = PuppetIr.toPuppet(RigIR(canvas, parameters, meshes = listOf(mesh.copy(id = target.nativeId)),
            deformers = target.branch))
        val drawable = model.drawables.single()
        val defaults = model.parameters.associate { it.id to it.default }
        val pose = { id: ParameterId -> defaults[id] ?: 0f }
        val rest = meshGridDefaultDeltas(drawable, pose)
        val opacity = drawable.channelGrids.scalarAt(FormChannel.OPACITY, drawable.opacity, pose)
        val order = drawable.channelGrids.scalarAt(FormChannel.DRAW_ORDER, drawable.drawOrder, pose)
        val multiply = drawable.channelGrids.colorAt(FormChannel.MULTIPLY_COLOR, drawable.multiplyColor, pose)
        val screen = drawable.channelGrids.colorAt(FormChannel.SCREEN_COLOR, drawable.screenColor, pose)
        val shapes = turn.shapes(target.raw.rigPositions, target.frame, target.shell, ParameterId("ParamAngleX"), ParameterId("ParamAngleY"))
            .map { binding -> binding.copy(forms = binding.forms.map { form -> form?.let {
                val values = it.positionDeltas
                MeshForm(if (rest == null) values else FloatArray(values.size) { i -> values[i] + rest[i] },
                    order, opacity, multiply, screen)
            } }) }
        // The live head still owns the normal Angle X and Y axes, even if the immediate frame has no head grid.
        val lowered = BlendShapeLowering({ head, label ->
            names.getValue(if (head) "turn_name" else "shape_name").jsonPrimitive.content.replace("{meshName}", label)
        }, checkpoint).lawful(model.copy(drawables = listOf(drawable.copy(blendShapes = shapes))))
        require(lowered.parameters == model.parameters) { "Eye widening v3 requires ordinary head turn parameters" }
        val carrier = lowered.deformers.drop(model.deformers.size).singleOrNull()
        return PuppetIr.meshToIr(lowered.drawables.single()).copy(id = mesh.id) to
            carrier?.let { PuppetIr.deformerToIr(it) as Deformer.Warp }
    }

    companion object {
        const val VERSION = 3
        const val ENTRYPOINT = "builtin.6579652e77696465.v3/wide"
        val WRITES = setOf("mesh", "deformer", "parameter")
        private val HEAD_PROVIDERS = setOf("face/fitted", "face.depth-turn")
        private val HEAD_AXES = setOf("ParamAngleX", "ParamAngleY", "ParamAngleZ")
        private val UNSUPPORTED_LOWERING = setOf("eye.emotion", "eye.aftershock", "eye.gaze-lid", "eye.highlight",
            "brow.lid-coupling", "mouth.cheek-lid")
        val optionalUpstream = EyeWideAlgorithm.optionalUpstream + UNSUPPORTED_LOWERING

        private fun knob(config: JsonObject?, id: String, default: Float, range: ClosedFloatingPointRange<Float>) =
            (config?.get("values")?.jsonObject?.get(id)?.jsonPrimitive?.float ?: default)
                .also { require(it in range) { "Invalid eye value: $id" } } / 100f

        private fun KeyformGrid<MeshDeltaForm>.offsets() = KeyGrid(
            axes.map { KeyAxis(it.parameterId.raw, Floats.wrap(it.keys)) },
            cells.map { KeyCell(Ints.wrap(it.coordinate), MeshOffsets(Floats.wrap(it.form.positionDeltas))) },
        )
    }
}

object EyeWideV3Worker {
    @JvmStatic fun main(args: Array<String>) = AlgorithmWorker.run(mapOf(
        (EyeWideV3Algorithm.ENTRYPOINT to EyeWideV3Algorithm.VERSION) to EyeWideV3Algorithm(),
    ))
}
