package io.github.psd2live.tools

import io.github.psd2live.application.WorkspaceViewRenderer
import io.github.psd2live.core.Bounds
import io.github.psd2live.core.PSD2LivePipeline
import io.github.psd2live.core.Physics3Json
import io.github.psd2live.core.PhysicsEngine
import io.github.psd2live.core.PhysicsInput
import io.github.psd2live.core.PhysicsSourceType
import io.github.psd2live.core.PipelineConfig
import io.github.psd2live.core.RigPreviewModel
import io.github.psd2live.core.sim.ModelPresets
import io.github.psd2live.core.sim.SimAuthoring
import io.github.psd2live.core.sim.SimBakeResult
import io.github.psd2live.core.sim.SimBaker
import io.github.psd2live.core.sim.SimGenerator
import io.github.psd2live.core.sim.SimScene
import io.github.psd2live.project.WorkspaceViewBackground
import io.github.psd2live.project.WorkspaceViewFrame
import io.github.psd2live.project.WorkspaceViewOutputSpec
import org.umamo.format.moc3.Moc3
import org.umamo.interop.moc3.`import`.Moc3Import
import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.KeyformAxis
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.RotationPivotForm
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals

/** Plays the same captured parts for two bake algorithms, using the real MOC3 and physics3 readers. */
class DirectSimulationReviewTool {
    private val fps = 100
    private val imageStep = 4
    private val rotationId = "ParamSkirtReviewRotation"

    @Test fun exportedAnimations() {
        requireTools()
        val out = File(setting("PSD2LIVE_DIRECT_REVIEW_OUT", "build/tools/direct-simulation")).also { it.mkdirs() }
        val pipeline = PSD2LivePipeline()
        val preview = pipeline.buildPreview(Sample.fromEnvironment().path, PipelineConfig(meshSpacing = 17, generatePhysics = false))
        val base = preview.baseRig
        val plain = preview.config.rigEdits
        val selections = listOf(
            Triple("skirt", ModelPresets.Preset.CLOTHING, ModelPresets.SKIRT_SIM),
            Triple("front-hair", ModelPresets.Preset.FRONT_HAIR, ModelPresets.FRONT_HAIR_SIM),
            Triple("back-hair", ModelPresets.Preset.BACK_HAIR, ModelPresets.BACK_HAIR_SIM),
        )
        for ((name, preset, id) in selections) {
            val applied = ModelPresets.apply(plain, preview.rig.puppet, preview.analysis, preview.rig.layerIdByDrawableId, preset)
            val original = applied.overlay.simEdits.single { it.id == id }
            val originalPart = SimBenchPart(name, applied.overlay, base.puppet, base.primitiveSkins, original)
            val part = if (name == "skirt") {
                val scene = SimScene.build(originalPart.model, original).also { it.reset(originalPart.model, emptyMap()) }
                val pins = (0 until scene.state.count).filter { scene.solver.pinWeight[it] >= 0.5f }
                require(pins.isNotEmpty())
                val x = pins.map { scene.state.anchorX[it] }.average().toFloat()
                val y = pins.map { scene.state.anchorY[it] }.average().toFloat()
                val rotated = waistRotation(base.puppet, x, -y)
                val edit = original.copy(vertical = true, exaggeration = 1f, modes = 3,
                    inputs = original.inputs.filter { it.parameter != "ParamBodyAngleZ" } + PhysicsInput(rotationId, 50f, PhysicsSourceType.ANGLE))
                SimBenchPart(name, applied.overlay, rotated, base.primitiveSkins, original).with(edit)
                    .also { verifyWaistRotation(originalPart.model, it.model, edit.targets, x, y) }
            } else originalPart
            exportAndPlay(pipeline, preview, part, out.resolve(name).also { it.mkdirs() })
        }
    }

    private fun exportAndPlay(pipeline: PSD2LivePipeline, preview: RigPreviewModel, part: SimBenchPart, out: File) {
        val bake = SimBaker.bake(part.model, part.edit, SimBaker.Options(physicsFps = part.overlay.physicsFps))
        require(bake.modes.isNotEmpty()) { "No baked modes for ${part.name}" }
        val persisted = SimBakeResult.fromJson(bake.toJson())
        assertEquals(bake, persisted)
        out.resolve("bake-summary.json").writeText(bake.summary().toString())
        val overlay = SimAuthoring.withBake(part.overlay, part.edit.id, persisted)
        val rig = preview.rig.copy(puppet = overlay.applyTo(part.base, part.skins))
        val config = preview.config.copy(rigEdits = overlay, generatePhysics = true)
        val expected = SimGenerator.physicsRules(listOf(part.edit.copy(bake = persisted)), rig.puppet.parameters.mapTo(hashSetOf()) { it.id.raw })
        val (bundle, _) = pipeline.buildRuntimeBundle(part.name, preview.analysis, preview.atlas, rig, config, validate = true, preview = false)
        for (asset in bundle.assets) {
            val file = out.resolve("export").resolve(asset.path)
            file.parentFile.mkdirs()
            file.writeBytes(asset.bytes)
        }
        val model = Moc3Import.fromMocDocument(Moc3.read(bundle.assets.single { it.path.endsWith(".moc3") }.bytes), null)
        val physics = Physics3Json.read(bundle.assets.single { it.path.endsWith(".physics3.json") }.bytes.decodeToString())
        val owned = physics.settings.filter { group -> expected.any { it.id == group.id } }
        assertEquals(expected.flatMap { it.outputs }.map { it.parameter }, owned.flatMap { it.outputs }.map { it.parameter })
        val parametersRead = model.parameters.associateBy { it.id.raw }
        check(owned.all { group -> group.outputs.all { it.parameter in parametersRead } })
        val renderer = preview.copy(rig = rig.copy(puppet = model), config = config, runtimeBundle = bundle)
        val layers = part.edit.targets.mapNotNull { rig.layerIdByDrawableId[it] }.toSet()
        val layerBounds = preview.analysis.layers.filter { it.source.id.raw in layers }.map { it.bounds }.reduce(Bounds::union)
        val rect = layerBounds.expanded(if (part.name == "skirt") 0.48f else 0.72f)
        val evaluator = CpuDeformationEvaluator()
        val ranges = PhysicsEngine.ranges(model.parameters)
        val physicsPlayback = PhysicsEngine(owned, ranges, physics.fps ?: -1f)
        val available = model.parameters.associateBy { it.id.raw }
        val drivenIds = owned.flatMap { rule -> rule.outputs.map { it.parameter } }.toSet()
        val triangles = model.drawables.filter { it.id.raw in part.edit.targets }
            .associate { it.id to requireNotNull(it.mesh).indices }
        val clips = if (part.name == "skirt") listOf("compression", "sway", "rotation", "mixed") else listOf("head-flick", "head-roll")
        for (clip in clips) {
            physicsPlayback.reset()
            val frames = out.resolve(clip).also { it.mkdirs() }
            var peak = 0f
            var shorten = 0f
            var peakDrive = 0f
            var saturatedFrames = 0
            var flippedFrames = 0
            for (frame in 0 until 3 * fps) {
                val t = frame.toFloat() / fps
                val input = if (clip.startsWith("head-")) headDrive(clip, t, available) else drive(clip, t, available)
                val response = physicsPlayback.step(input, 1f / fps)
                val drive = drivenIds.maxOf { id ->
                    val parameter = parametersRead.getValue(id)
                    val value = response[id] ?: parameter.default
                    val range = if (value < parameter.default) parameter.default - parameter.min else parameter.max - parameter.default
                    (kotlin.math.abs(value - parameter.default) / range.coerceAtLeast(1e-6f))
                }
                peakDrive = maxOf(peakDrive, drive)
                if (drive >= 0.9995f) saturatedFrames++
                val values = input + response
                if (frame % imageStep != 0) continue
                val rest = evaluator.evaluate(model, input.mapKeys { ParameterId(it.key) }).worldPositions
                val loaded = evaluator.evaluate(model, values.mapKeys { ParameterId(it.key) }).worldPositions
                var flipped = false
                for ((id, topology) in triangles) {
                    val a = rest.getValue(id)
                    val b = loaded.getValue(id)
                    for (i in a.indices step 2) peak = maxOf(peak, hypot(b[i] - a[i], b[i + 1] - a[i + 1]))
                    if (!flipped) for (i in topology.indices step 3) {
                        val v0 = topology[i] * 2
                        val v1 = topology[i + 1] * 2
                        val v2 = topology[i + 2] * 2
                        val before = signedArea(a, v0, v1, v2)
                        if (kotlin.math.abs(before) >= 1f && signedArea(b, v0, v1, v2) * before < 0f) {
                            flipped = true
                            break
                        }
                    }
                    if (clip == "compression") {
                        val ys = 1 until a.size step 2
                        val restHeight = ys.maxOf { a[it] } - ys.minOf { a[it] }
                        val bakedHeight = ys.maxOf { b[it] } - ys.minOf { b[it] }
                        shorten = maxOf(shorten, restHeight - bakedHeight)
                    }
                }
                if (flipped) flippedFrames++
                val view = WorkspaceViewRenderer.modelComposite(renderer, "physics-review", values, layers, emptySet(),
                    WorkspaceViewFrame.CanvasRect(rect), WorkspaceViewBackground.TRANSPARENT, WorkspaceViewOutputSpec(560))
                frames.resolve((frame / imageStep).toString().padStart(3, '0') + ".png").writeBytes(view.png)
            }
            val rawPeak = physicsPlayback.strands.maxOf { strand ->
                strand.setting.outputs.indices.maxOf { k ->
                    strand.peakFraction(k, ranges.getValue(strand.setting.outputs[k].parameter))
                }
            }
            println("${part.name} ${bake.method} $clip peak=$peak px compress=$shorten px raw=$rawPeak saturated=$saturatedFrames/${3 * fps} flipped=$flippedFrames")
            out.resolve("$clip.txt").writeText("peak_px=$peak\nshorten_px=$shorten\nmode_peak=$peakDrive\nraw_peak=$rawPeak\nsaturated_frames=$saturatedFrames\nflipped_frames=$flippedFrames\n")
        }
    }

    private fun signedArea(p: FloatArray, a: Int, b: Int, c: Int): Float =
        (p[b] - p[a]) * (p[c + 1] - p[a + 1]) - (p[b + 1] - p[a + 1]) * (p[c] - p[a])

    private fun headDrive(clip: String, t: Float, parameters: Map<String, Parameter>): Map<String, Float> {
        fun ramp(at: Float, length: Float): Float {
            val p = ((t - at) / length).coerceIn(0f, 1f)
            return p * p * (3f - 2f * p)
        }
        val reversal = 0.92f * ramp(0.3f, 0.16f) - 1.68f * ramp(0.72f, 0.14f) + 0.76f * ramp(1.15f, 0.17f)
        val secondary = 0.62f * ramp(0.34f, 0.21f) - 1.23f * ramp(0.79f, 0.18f) + 0.61f * ramp(1.3f, 0.15f)
        val u = if (clip == "head-flick")
            mapOf("ParamAngleX" to reversal, "ParamAngleZ" to 0.25f * secondary)
        else mapOf("ParamAngleZ" to reversal, "ParamAngleY" to 0.45f * secondary, "ParamAngleX" to -0.30f * secondary)
        return u.mapNotNull { (id, n) -> parameters[id]?.let { p ->
            id to (p.default + n * (if (n < 0f) p.default - p.min else p.max - p.default))
        } }.toMap()
    }

    private fun waistRotation(model: PuppetModel, x: Float, y: Float): PuppetModel {
        val parameter = ParameterId(rotationId)
        val id = DeformerId("SkirtReviewWaist")
        val keys = floatArrayOf(-16f, 0f, 16f)
        val grid = KeyformGrid(listOf(KeyformAxis(parameter, keys)), keys.mapIndexed { i, angle ->
            KeyformCell(intArrayOf(i), RotationPivotForm(x, y, -angle, 1f))
        })
        val pivot = Deformer.Rotation(id, "Skirt review waist", null, null, 0f, grid)
        val localId = DeformerId("SkirtReviewWaistLocal")
        val local = Deformer.Rotation(localId, "Skirt review waist origin", id, null, 0f,
            KeyformGrid(emptyList(), listOf(KeyformCell(intArrayOf(), RotationPivotForm(-x, -y, 0f, 1f)))))
        return model.copy(
            parameters = model.parameters + Parameter(parameter, "Skirt review rotation", keys.first(), keys.last(), 0f),
            deformers = listOf(pivot, local) + model.deformers.map { deformer ->
                if (deformer.parent != null) deformer else when (deformer) {
                    is Deformer.Rotation -> deformer.copy(parent = localId)
                    is Deformer.Warp -> deformer.copy(parent = localId)
                }
            },
            drawables = model.drawables.map { mesh -> if (mesh.parentDeformerId == null) mesh.copy(parentDeformerId = localId) else mesh },
        )
    }

    private fun verifyWaistRotation(base: PuppetModel, wrapped: PuppetModel, targets: List<String>, x: Float, y: Float) {
        val evaluator = CpuDeformationEvaluator()
        val rest = evaluator.evaluate(base, emptyMap()).worldPositions
        for (angle in listOf(0f, 8f, -16f)) {
            val actual = evaluator.evaluate(wrapped, mapOf(ParameterId(rotationId) to angle)).worldPositions
            val c = cos((angle * PI / 180).toFloat())
            val s = sin((angle * PI / 180).toFloat())
            for (id in targets.map(::DrawableId)) {
                val before = rest.getValue(id)
                val after = actual.getValue(id)
                val error = before.indices.step(2).maxOf { i ->
                    val dx = before[i] - x
                    val dy = before[i + 1] - y
                    hypot(after[i] - (x + c * dx - s * dy), after[i + 1] - (y + s * dx + c * dy))
                }
                require(error < 0.05f) { "Waist fixture is not a rigid ${angle}deg rotation: max error $error px" }
            }
        }
    }

    private fun drive(clip: String, seconds: Float, parameters: Map<String, Parameter>): Map<String, Float> {
        fun step(start: Float, duration: Float): Float {
            val x = ((seconds - start) / duration).coerceIn(0f, 1f)
            return x * x * (3f - 2f * x)
        }
        fun pulse(at: Float, attack: Float, release: Float, end: Float) = step(at, attack) - step(release, end)
        fun wave(hz: Float): Float {
            val t = (seconds - 0.2f).coerceAtLeast(0f)
            return sin(2 * PI * hz * t).toFloat() * step(0.2f, 0.18f) * (1f - step(1.25f, 0.27f))
        }
        val squash = 0.9f * pulse(0.28f, 0.19f, 0.53f, 0.14f) - 0.78f * pulse(0.71f, 0.16f, 0.95f, 0.24f)
        val swing = 0.87f * wave(1.25f)
        val turn = 0.91f * pulse(0.27f, 0.21f, 0.63f, 0.13f) - 0.78f * pulse(0.78f, 0.23f, 1.15f, 0.19f)
        val motion = when (clip) {
            "compression" -> mapOf("ParamBodyAngleY" to squash)
            "sway" -> mapOf("ParamBodyAngleX" to swing)
            "rotation" -> mapOf(rotationId to turn)
            else -> mapOf("ParamBodyAngleX" to 0.70f * swing, "ParamBodyAngleY" to 0.8f * squash, rotationId to -0.66f * turn)
        }
        return motion.mapNotNull { (id, u) -> parameters[id]?.let { p ->
            id to (p.default + u * if (u < 0f) p.default - p.min else p.max - p.default)
        } }.toMap()
    }
}
