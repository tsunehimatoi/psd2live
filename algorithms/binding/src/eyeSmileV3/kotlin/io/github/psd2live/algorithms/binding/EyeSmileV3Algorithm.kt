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
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.MeshDeltaForm
import org.umamo.runtime.model.MeshForm
import org.umamo.runtime.model.ParameterId

/** Adds smile stops in the fitted source frame and re-lowers any preceding head turn. */
class EyeSmileV3Algorithm : ReadingModifierAlgorithmPlugin {
    private data class TurnMesh(val live: Mesh, val base: Mesh, val branch: List<Deformer>, val carrier: Deformer.Warp?,
                                val nativeId: String, val raw: ScaffoldMesh.RawMesh, val frame: Bounds,
                                val shell: HeadShell)

    override fun changes(input: ModifierEvaluationInput, instance: RigModifierInstance, checkpoint: () -> Unit): ModifierModelChanges {
        require(instance.version == VERSION)
        val context = input.context
        val legacy = instance.copy(version = EyeSmileAlgorithm.VERSION)
        val head = context.values["head_turn"]?.jsonObject
        val scaffold = instance.config.getValue("scaffold")
        val base = instance.config.getValue("base").jsonPrimitive.content
        val fitted = input.providers("eye/fitted").single { it.instanceId == base }
        val preceding = input.upstream.filter { it.config["scaffold"] == scaffold }
        require(fitted.version in setOf(2, 5)) { "Eye smile v3 requires fitted eyes v2 or v5" }
        // The locally reconstructed source-frame lids share the v2 smile kernel's fitted-eye contract.
        val measurements = ModifierAlgorithmContext(JsonObject(context.values - "head_turn"), context.resources,
            context.upstream.map { if (it == fitted) it.copy(version = 2) else it })
        val captured = EyeSmileInputs.decode(measurements, checkpoint)
        val source = captured.measurements
        if (head == null && fitted.version == 2) return EyeSmileAlgorithm().changes(input, legacy, checkpoint)
        if (head != null) require(preceding.any {
            it.instanceId == head.getValue("provider").jsonPrimitive.content && it.capability in HEAD_PROVIDERS
        }) { "Eye smile v3 requires the captured fitted head" }
        val turn = head?.getValue("shell")?.jsonArray?.map { it.jsonPrimitive.float.also { value -> require(value.isFinite()) } }?.let {
            require(it.size == 7 && it[2] > 0f && it[3] > 0f)
            HeadTurn(it[0], it[1], it[2], it[3], it[4], it[5], it[6], checkpoint)
        }
        val names = head?.getValue("native_ids")?.jsonObject.orEmpty()
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
            val layer = captured.art.layerForTarget(target.id)
            if (EyeModules.meshOf(layer.tag, layer.role) == null && layer.tag != "EYE_CLOSE") return@mapNotNull null
            val folded = mesh.offsets?.axes.orEmpty().any { it.parameter in HEAD_AXES } ||
                mesh.shapes.any { it.parameter in HEAD_AXES }
            if (mesh.parent == material.parent && !folded) return@mapNotNull null
            val raw = captured.topology.getValue(material.layer)
            require(turn != null) { "Eye smile v3 needs captured head turn measurements: ${mesh.id}" }
            val frame = material.frame.run { Bounds(left, top, right, bottom) }
            val headShell = requireNotNull(DepthTurnMotion.shell(layer.tag, layer.role))
            val branch = input.parentsOfMesh(mesh.id)
            val parent = material.parent?.let { id -> branch.singleOrNull { it.id == id } }
            val carrier = if (mesh.parent == material.parent) null else branch.firstOrNull() as? Deformer.Warp
            val nativeId = names.getValue(mesh.id).jsonPrimitive.content
            require((material.parent == null || parent != null) &&
                (mesh.parent == material.parent || carrier != null && carrier.parent == material.parent &&
                    carrier.lattice?.axes.orEmpty().any { it.parameter in HEAD_AXES })) {
                "Eye smile v3 requires the source frame or its head turn warp: ${mesh.id}"
            }
            val local = preceding.filter { target in it.targets }
            require(local.none { it.capability in UNSUPPORTED_LOWERING }) {
                "Eye smile v3 cannot migrate head turns with preceding lid couplings: ${mesh.id}"
            }
            val wide = local.lastOrNull { it.capability == "eye.wide" }
            val options = EyeOptions(wide = wide != null, wideLift = knob(wide?.config, "lift", 10f, 0f..80f))
            val eyes = fits.mapValues { (_, fit) ->
                EyeRig(fit, knob(fitted.config, "lowerShare", 5f, 0f..50f),
                    knob(fitted.config, "roundness", 100f, 40f..160f),
                    knob(fitted.config, "lashSquash", 60f, 20f..100f), .12f, .5f, options.wideLift)
            }
            val points = Floats.wrap(FloatArray(raw.rigPositions.size) { i ->
                val point = raw.rigPositions[i]
                if (material.parent == null) point
                else if (i % 2 == 0) (point - frame.left) / frame.width.coerceAtLeast(1e-4f)
                else (point - frame.top) / frame.height.coerceAtLeast(1e-4f)
            })
            val offsets = sourceOffsets(layer, raw.rigPositions, frame, eyes, options)
            val canonical = mesh.copy(parent = material.parent, geometry = MeshGeometry(points,
                Floats.wrap(raw.atlasUvs), Ints.wrap(raw.triangles)), offsets = offsets, shapes = emptyList())
            TurnMesh(mesh, canonical, branch.dropWhile { it.id != material.parent }, carrier, nativeId, raw, frame, headShell)
        }.associateBy { it.live.id }
        val parameters = input.parameters
        val branches = meshes.flatMap { input.parentsOfMesh(it.id) }.distinctBy { it.id }
        val projection = RigIR(input.canvas, parameters, meshes = meshes.map { turnMeshes[it.id]?.base ?: it }, deformers = branches, parameterTree = input.parameterTree)
        val smiled = EyeSmileAlgorithm().changes(projection, legacy, measurements, checkpoint)
        val newParameters = smiled.parameters.apply(parameters, { it.id }, checkpoint)
        val additions = ArrayList<Deformer>()
        val updates = ArrayList<ModifierObjectUpdate<Deformer>>()
        val replacements = smiled.meshes.updated.map { update ->
            val target = turnMeshes[update.before.id] ?: return@map update
            val (before, old) = lower(input.canvas, parameters, target, target.base, requireNotNull(turn), requireNotNull(head), checkpoint)
            val (after, new) = lower(input.canvas, newParameters, target, update.after, turn, head, checkpoint)
            require(before.copy(parent = target.live.parent) == target.live &&
                if (old == null) target.carrier == null else target.carrier != null &&
                    old.copy(id = target.carrier.id, part = target.carrier.part) == target.carrier) {
                "Eye smile v3 cannot reproduce the preceding head turn: ${target.live.id}"
            }
            val parent = when {
                new == null -> {
                    require(old == null) { "Eye smile v3 cannot retire a preceding head turn warp" }
                    after.parent
                }
                target.carrier != null -> {
                    val replacement = new.copy(id = target.carrier.id, part = target.carrier.part)
                    if (replacement != target.carrier) {
                        require(input.meshes.none { it.id != target.live.id && it.parent == target.carrier.id } &&
                            input.deformers.none { it.parent == target.carrier.id }) {
                            "Eye smile v3 cannot refit a shared head turn warp"
                        }
                        updates += ModifierObjectUpdate(target.carrier, replacement)
                    }
                    target.carrier.id
                }
                else -> {
                    val id = ScaffoldBinding.deformerId(instance, target.live.id, new.id, shared = true)
                    require(input.deformer(id) == null) { "Eye smile v3 head turn warp already exists: $id" }
                    additions += new.copy(id = id)
                    id
                }
            }
            ModifierObjectUpdate(target.live, after.copy(parent = parent))
        }
        return smiled.copy(meshes = ModifierObjectChanges(updated = replacements),
            deformers = ModifierObjectChanges(added = additions, updated = updates))
    }

    private fun sourceOffsets(layer: ModifierSourceLayer, positions: FloatArray, frame: Bounds,
                              eyes: Map<Side, EyeRig>, options: EyeOptions): KeyGrid<MeshOffsets> {
        val side = Side.valueOf(layer.side)
        val eye = eyes[side]
        val lid = EyeModules.meshOf(layer.tag, layer.role)
        val grid = when {
            lid != null && side == Side.NONE ->
                EyeModules.pairedLidGrid(lid, layer.id, positions, frame, FittedEyeMotion(eyes, options))
            lid != null && eye != null -> EyeModules.lidGrid(lid, side, positions, frame, eye, options, layer.id)
            else -> null
        }
        return grid?.offsets() ?: KeyGrid(emptyList(),
            listOf(KeyCell(Ints.wrap(intArrayOf()), MeshOffsets(Floats.wrap(FloatArray(positions.size))))))
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
        require(lowered.parameters == model.parameters) { "Eye smile v3 requires ordinary head turn parameters" }
        val carrier = lowered.deformers.drop(model.deformers.size).singleOrNull()
        return PuppetIr.meshToIr(lowered.drawables.single()).copy(id = mesh.id) to
            carrier?.let { PuppetIr.deformerToIr(it) as Deformer.Warp }
    }

    companion object {
        const val VERSION = 3
        const val ENTRYPOINT = "builtin.6579652e736d696c65.v3/smile"
        val WRITES = setOf("mesh", "deformer", "parameter", "hierarchy")
        private val HEAD_PROVIDERS = setOf("face/fitted", "face.depth-turn")
        private val HEAD_AXES = setOf("ParamAngleX", "ParamAngleY", "ParamAngleZ")
        private val UNSUPPORTED_LOWERING = setOf("eye.emotion", "eye.aftershock", "eye.gaze-lid", "eye.highlight",
            "brow.lid-coupling", "mouth.cheek-lid")
        val optionalUpstream = EyeSmileAlgorithm.optionalUpstream + UNSUPPORTED_LOWERING + HEAD_PROVIDERS

        private fun knob(config: JsonObject?, id: String, default: Float, range: ClosedFloatingPointRange<Float>) =
            (config?.get("values")?.jsonObject?.get(id)?.jsonPrimitive?.float ?: default)
                .also { require(it in range) { "Invalid eye value: $id" } } / 100f

        private fun KeyformGrid<MeshDeltaForm>.offsets() = KeyGrid(
            axes.map { KeyAxis(it.parameterId.raw, Floats.wrap(it.keys)) },
            cells.map { KeyCell(Ints.wrap(it.coordinate), MeshOffsets(Floats.wrap(it.form.positionDeltas))) },
        )
    }
}

object EyeSmileV3Worker {
    @JvmStatic fun main(args: Array<String>) = AlgorithmWorker.run(mapOf(
        (EyeSmileV3Algorithm.ENTRYPOINT to EyeSmileV3Algorithm.VERSION) to EyeSmileV3Algorithm(),
    ))
}
