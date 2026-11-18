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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.umamo.format.moc3.Moc3
import org.umamo.interop.moc3.`import`.Moc3Import
import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.runtime.model.BlendShapeBinding
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
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals

/** Compares the full XPBD solver with real MOC3 and physics3 readback on the same drives. */
class DirectSimulationReviewTool {
    private val fps = 100
    private val imageStep = 4
    private val solverFps = 60
    private val rotationId = "ParamSkirtReviewRotation"

    @Test fun exportedAnimations() {
        requireTools()
        val out = File(setting("PSD2LIVE_DIRECT_REVIEW_OUT", "build/tools/direct-simulation")).also { it.mkdirs() }
        val track = setting("PSD2LIVE_DIRECT_REVIEW_TRACK", "readback")
        require(track in setOf("readback", "xpbd"))
        val fixture = setting("PSD2LIVE_DIRECT_REVIEW_FIXTURE", "preset")
        require(fixture in setOf("preset", "controlled"))
        val keys = setting("PSD2LIVE_DIRECT_REVIEW_KEYS", "5").toInt()
        require(keys in setOf(5, 9))
        val reference = System.getenv("PSD2LIVE_DIRECT_REVIEW_REFERENCE_OUT")?.takeIf { it.isNotBlank() }?.let(::File)
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
            val part = if (name == "skirt" && fixture == "controlled") {
                val scene = SimScene.build(originalPart.model, original).also { it.reset(originalPart.model, emptyMap()) }
                val pins = (0 until scene.state.count).filter { scene.solver.pinWeight[it] >= 0.5f }
                require(pins.isNotEmpty())
                val x = pins.map { scene.state.anchorX[it] }.average().toFloat()
                val y = pins.map { scene.state.anchorY[it] }.average().toFloat()
                val rotated = waistRotation(base.puppet, x, -y)
                val edit = original.copy(vertical = true, exaggeration = 1f, modes = 3, keys = keys,
                    inputs = original.inputs.filter { it.parameter != "ParamBodyAngleZ" } + PhysicsInput(rotationId, 50f, PhysicsSourceType.ANGLE))
                SimBenchPart(name, applied.overlay, rotated, base.primitiveSkins, original).with(edit)
                    .also { verifyWaistRotation(originalPart.model, it.model, edit.targets, x, y) }
            } else originalPart.with(original.copy(keys = keys))
            val directory = out.resolve(name).also { it.mkdirs() }
            if (track == "xpbd") {
                simulateAndPlay(preview, part, directory, fixture)
            } else {
                exportAndPlay(pipeline, preview, part, directory, fixture, reference?.resolve(name))
            }
        }
    }

    private fun exportAndPlay(
        pipeline: PSD2LivePipeline, preview: RigPreviewModel, part: SimBenchPart, out: File,
        fixture: String, reference: File?,
    ) {
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
        val ranges = PhysicsEngine.ranges(model.parameters)
        val physicsPlayback = PhysicsEngine(owned, ranges, physics.fps ?: -1f)
        val drivenIds = owned.flatMap { rule -> rule.outputs.map { it.parameter } }.toSet()
        if (bake.method == "direct" && setting("PSD2LIVE_DIRECT_REVIEW_AXIS_POSES", "0") == "1") {
            writeAxisPoses(renderer, part, out.resolve("axes"), drivenIds, bake.summary())
        }
        for (clip in clips(part)) {
            physicsPlayback.reset()
            play(renderer, part, out, fixture, clip, bake.method,
                reference = reference?.resolve("$clip.residuals.bin"),
                mixed = if (clip in mixedClips && setting("PSD2LIVE_DIRECT_REVIEW_MIX_ANALYSIS", "0") == "1") {
                    MixedMotionAnalysis(model, part.edit.targets.map(::DrawableId), drivenIds, fps)
                } else null,
                advance = { _, input ->
                    val response = physicsPlayback.step(input, 1f / fps)
                    val peak = drivenIds.maxOf { id ->
                        val parameter = parametersRead.getValue(id)
                        val value = response[id] ?: parameter.default
                        val range = if (value < parameter.default) parameter.default - parameter.min else parameter.max - parameter.default
                        abs(value - parameter.default) / range.coerceAtLeast(1e-6f)
                    }
                    PlaybackFrame(input + response, drive = peak)
                },
                rawPeak = { physicsPlayback.strands.maxOf { strand ->
                    strand.setting.outputs.indices.maxOf { k ->
                        strand.peakFraction(k, ranges.getValue(strand.setting.outputs[k].parameter))
                    }
                } },
            )
        }
    }

    private fun writeAxisPoses(
        renderer: RigPreviewModel, part: SimBenchPart, out: File, drivenIds: Set<String>, summary: JsonObject,
    ) {
        val model = renderer.rig.puppet
        val inspector = CpuDeformationEvaluator()
        val targets = part.edit.targets.map(::DrawableId)
        val selected = targets.mapNotNull { renderer.rig.layerIdByDrawableId[it.raw] }.toSet()
        val rect = renderer.analysis.layers.filter { it.source.id.raw in selected }.map { it.bounds }.reduce(Bounds::union)
            .expanded(if (part.name == "skirt") 0.48f else 0.72f)
        val triangles = model.drawables.filter { it.id in targets }.associate { it.id to requireNotNull(it.mesh).indices }
        val defaults = model.parameters.filter { it.id.raw in drivenIds }.associate { it.id.raw to it.default }
        val rest = inspector.evaluate(model, defaults.mapKeys { ParameterId(it.key) }).worldPositions
        val regions = regions(part, rest)
        val modeLabels = summary.getValue("modes").jsonArray.associate { item ->
            val row = item.jsonObject
            part.edit.outputId(row.getValue("parameter").jsonPrimitive.content) to row["motion"]?.jsonPrimitive?.contentOrNull
        }
        val folderLabels = drivenIds.associateWith { modeLabels[it] ?: it }
        val labelCounts = folderLabels.values.groupingBy { it }.eachCount()
        for (axis in model.parameters.filter { it.id.raw in drivenIds }) {
            val id = axis.id.raw
            val label = folderLabels.getValue(id).let { if (labelCounts[it] == 1) it else "${it}_$id" }
            val directory = out.resolve(label.replace(Regex("[^A-Za-z0-9_-]"), "_")).also { it.mkdirs() }
            val samples = (-6..6).associateWith { step ->
                val fraction = step / 10f
                val side = if (step < 0) axis.default - axis.min else axis.max - axis.default
                val value = axis.default + fraction * side
                val pose = defaults + (id to value)
                val positions = inspector.evaluate(model, pose.mapKeys { ParameterId(it.key) }).worldPositions
                val measurement = measure(targets, triangles, regions, rest, positions, null, null)
                val name = when (step) { -6 -> "negative-60"; 0 -> "neutral"; 6 -> "positive-60"; else -> null }
                if (name != null) {
                    val view = WorkspaceViewRenderer.modelComposite(renderer, "axis-review", pose, selected, emptySet(),
                        WorkspaceViewFrame.CanvasRect(rect), WorkspaceViewBackground.TRANSPARENT, WorkspaceViewOutputSpec(560))
                    directory.resolve("$name.png").writeBytes(view.png)
                }
                value to measurement
            }
            val displaced = samples.filterKeys { it != 0 }.values
            val metadata = buildJsonObject {
                put("parameter", id)
                modeLabels[id]?.let { put("motion", it) }
                put("interval_min_fraction", -0.6f)
                put("interval_max_fraction", 0.6f)
                put("interval_step_fraction", 0.1f)
                put("minimum_nonzero_peak_px", displaced.minOf { it.second.peak })
                put("minimum_nonzero_rms_px", displaced.minOf { it.second.rms })
                putJsonArray("samples") {
                    for ((step, sample) in samples) addJsonObject {
                        val (value, metric) = sample
                        put("fraction", step / 10f)
                        put("value", value)
                        put("peak_px", metric.peak)
                        put("rms_px", metric.rms)
                        put("tip_x_px", metric.tipX)
                        put("tip_y_px", metric.tipY)
                        put("turn_deg", metric.turn)
                        put("shorten_px", metric.shorten)
                        put("flipped", metric.flipped)
                    }
                }
            }
            directory.resolve("metadata.json").writeText(metadata.toString())
        }
    }

    private fun simulateAndPlay(preview: RigPreviewModel, part: SimBenchPart, out: File, fixture: String) {
        val model = part.model
        val renderer = preview.copy(rig = preview.rig.copy(puppet = model))
        val scene = SimScene.build(model, part.edit)
        // This is the comparison's full solver run. The direct bake is never called in this lane.
        scene.calibrate(model)
        val available = model.parameters.associateBy { it.id.raw }
        out.resolve("bake-summary.json").writeText("""{"method":"xpbd","solver_fps":$solverFps}""")
        for (clip in clips(part)) {
            scene.reset(model, emptyMap())
            fun world() = scene.offsets.keys.associateWith { requireNotNull(scene.positions(it)) }
            var left = world()
            var right = left
            var rightTick = 0
            play(renderer, part, out, fixture, clip, "xpbd", record = out.resolve("$clip.residuals.bin"),
                advance = { frame, input ->
                    val numerator = frame * solverFps
                    val whole = numerator / fps
                    val remainder = numerator % fps
                    val target = whole + if (remainder == 0) 0 else 1
                    while (rightTick < target) {
                        left = right
                        rightTick++
                        val pose = drive(part, clip, rightTick.toFloat() / solverFps, available, fixture)
                            .mapKeys { ParameterId(it.key) }
                        check(scene.drive(model, pose, 1f / solverFps)) { "Unplayable XPBD pose: ${part.name}/$clip/$rightTick" }
                        right = world()
                    }
                    val positions = if (remainder == 0 && whole == rightTick) right else {
                        val fraction = if (remainder == 0) 0f else remainder.toFloat() / fps
                        left.mapValues { (id, a) ->
                            val b = right.getValue(id)
                            FloatArray(a.size) { i -> a[i] + (b[i] - a[i]) * fraction }
                        }
                    }
                    PlaybackFrame(input, positions)
                },
            )
        }
    }

    private class PlaybackFrame(
        val values: Map<String, Float>,
        val positions: Map<DrawableId, FloatArray>? = null,
        val drive: Float = 0f,
    )

    private class Region(val roots: List<Int>, val tips: List<Int>)

    private class MotionSample(
        val peak: Float, val rms: Double, val rmsX: Double, val rmsY: Double,
        val tipX: Double, val tipY: Double, val turn: Double, val shorten: Float,
        val flipped: Boolean, val referenceRms: Double?,
    )

    private fun play(
        renderer: RigPreviewModel, part: SimBenchPart, out: File, fixture: String, clip: String, method: String,
        reference: File? = null, record: File? = null, mixed: MixedMotionAnalysis? = null,
        advance: (Int, Map<String, Float>) -> PlaybackFrame, rawPeak: (() -> Float)? = null,
    ) {
        val layers = part.edit.targets.mapNotNull { renderer.rig.layerIdByDrawableId[it] }.toSet()
        val layerBounds = renderer.analysis.layers.filter { it.source.id.raw in layers }.map { it.bounds }.reduce(Bounds::union)
        val rect = layerBounds.expanded(if (part.name == "skirt") 0.48f else 0.72f)
        val evaluator = CpuDeformationEvaluator()
        val available = part.model.parameters.associateBy { it.id.raw }
        val targets = part.edit.targets.map(::DrawableId)
        val triangles = renderer.rig.puppet.drawables.filter { it.id in targets }
            .associate { it.id to requireNotNull(it.mesh).indices }
        val regions = regions(part, evaluator.evaluate(part.model, emptyMap()).worldPositions)
        val frames = out.resolve(clip).also { it.mkdirs() }
        val count = 3 * fps
        val samples = ArrayList<MotionSample>(count)
        var peakDrive = 0f
        var saturated = 0
        reference?.inputStream()?.buffered()?.let(::DataInputStream).use { read ->
            record?.outputStream()?.buffered()?.let(::DataOutputStream).use { write ->
                read?.let {
                    assertEquals("psd2live-reference-1", it.readUTF())
                    assertEquals(fixture, it.readUTF())
                    assertEquals(count, it.readInt())
                }
                write?.let {
                    it.writeUTF("psd2live-reference-1")
                    it.writeUTF(fixture)
                    it.writeInt(count)
                }
                for (id in targets) {
                    val size = regions.getValue(id).first
                    if (read != null) {
                        assertEquals(id.raw, read.readUTF())
                        assertEquals(size, read.readInt())
                    }
                    if (write != null) {
                        write.writeUTF(id.raw)
                        write.writeInt(size)
                    }
                }
                for (frame in 0 until count) {
                    val input = drive(part, clip, frame.toFloat() / fps, available, fixture)
                    val played = advance(frame, input)
                    val unbaked = evaluator.evaluate(renderer.rig.puppet, input.mapKeys { ParameterId(it.key) }).worldPositions
                    val deformed = played.positions ?: evaluator.evaluate(
                        renderer.rig.puppet, played.values.mapKeys { ParameterId(it.key) },
                    ).worldPositions
                    if (read != null) assertEquals(input.hashCode(), read.readInt(), "Reference drive at frame $frame")
                    write?.writeInt(input.hashCode())
                    samples += measure(targets, triangles, regions, unbaked, deformed, read, write)
                    mixed?.add(input, played.values, unbaked, deformed)
                    peakDrive = maxOf(peakDrive, played.drive)
                    if (played.drive >= 0.9995f) saturated++
                    if (frame % imageStep != 0) continue
                    val view = WorkspaceViewRenderer.modelComposite(renderer, "physics-review", played.values, layers, emptySet(),
                        WorkspaceViewFrame.CanvasRect(rect), WorkspaceViewBackground.TRANSPARENT, WorkspaceViewOutputSpec(560),
                        worldPositions = played.positions.orEmpty())
                    frames.resolve((frame / imageStep).toString().padStart(3, '0') + ".png").writeBytes(view.png)
                }
                if (read != null) assertEquals(-1, read.read(), "Trailing XPBD reference data")
            }
        }
        mixed?.write(out, clip)
        out.resolve("$clip.csv").bufferedWriter().use { csv ->
            csv.appendLine("frame,time_s,peak_px,rms_px,rms_x_px,rms_y_px,tip_x_px,tip_y_px,turn_deg,shorten_px,flipped,reference_rms_px")
            samples.forEachIndexed { i, p ->
                csv.appendLine("$i,${i.toDouble() / fps},${p.peak},${p.rms},${p.rmsX},${p.rmsY},${p.tipX},${p.tipY},${p.turn},${p.shorten},${p.flipped},${p.referenceRms ?: ""}")
            }
        }
        val peak = samples.maxOf { it.peak }
        val shorten = samples.maxOf { it.shorten }
        val flipped = samples.count { it.flipped }
        val referenceRms = samples.first().referenceRms?.let { sqrt(samples.sumOf { (it.referenceRms ?: 0.0).let { x -> x * x } } / count) }
        val raw = rawPeak?.invoke()
        val info = buildString {
            appendLine("peak_px=$peak")
            appendLine("rms_px=${sqrt(samples.sumOf { it.rms * it.rms } / count)}")
            appendLine("p95_frame_rms_px=${samples.map { it.rms }.sorted()[(count - 1) * 95 / 100]}")
            appendLine("shorten_px=$shorten")
            appendLine("max_tip_x_px=${samples.maxOf { abs(it.tipX) }}")
            appendLine("max_tip_y_px=${samples.maxOf { abs(it.tipY) }}")
            appendLine("max_turn_deg=${samples.maxOf { abs(it.turn) }}")
            appendLine("peak_time_s=${samples.indices.maxBy { samples[it].peak }.toDouble() / fps}")
            appendLine("last_second_rms_px=${sqrt(samples.takeLast(fps).sumOf { it.rms * it.rms } / fps)}")
            appendLine("flipped_frames=$flipped")
            if (referenceRms != null) appendLine("reference_rms_px=$referenceRms")
            if (raw != null) {
                appendLine("mode_peak=$peakDrive")
                appendLine("raw_peak=$raw")
                appendLine("saturated_frames=$saturated")
            }
        }
        println("${part.name} $method $clip peak=$peak px compress=$shorten px raw=$raw saturated=$saturated/$count flipped=$flipped ref_rms=$referenceRms")
        out.resolve("$clip.txt").writeText(info)
    }

    private fun regions(part: SimBenchPart, neutral: Map<DrawableId, FloatArray>): Map<DrawableId, Pair<Int, Region>> {
        val scene = SimScene.build(part.model, part.edit)
        return scene.offsets.mapValues { (id, start) ->
            val p = neutral.getValue(id)
            val vertices = 0 until p.size / 2
            val roots = vertices.filter { scene.solver.pinWeight[start + it] >= 0.5f }.ifEmpty {
                vertices.sortedByDescending { p[it * 2 + 1] }.take(maxOf(1, vertices.count() / 10))
            }
            val x = roots.map { p[it * 2] }.average()
            val y = roots.map { p[it * 2 + 1] }.average()
            val rootSet = roots.toSet()
            val tips = vertices.filter { it !in rootSet }
                .sortedByDescending { hypot(p[it * 2] - x, p[it * 2 + 1] - y) }
                .take(maxOf(1, vertices.count() / 5)).ifEmpty { roots }
            p.size to Region(roots, tips)
        }
    }

    private fun measure(
        targets: List<DrawableId>, triangles: Map<DrawableId, IntArray>, regions: Map<DrawableId, Pair<Int, Region>>,
        rest: Map<DrawableId, FloatArray>, loaded: Map<DrawableId, FloatArray>,
        read: DataInputStream?, write: DataOutputStream?,
    ): MotionSample {
        var sumX = 0.0; var sumY = 0.0; var error = 0.0
        var tipX = 0.0; var tipY = 0.0; var cross = 0.0; var dot = 0.0
        var count = 0; var tipCount = 0
        var peak = 0f; var shortening = 0f; var flipped = false
        for (id in targets) {
            val a = rest.getValue(id)
            val b = loaded.getValue(id)
            val (size, region) = regions.getValue(id)
            check(a.size == size && b.size == size) { "Unexpected vertex count for ${id.raw}" }
            var aMinY = Float.POSITIVE_INFINITY; var aMaxY = Float.NEGATIVE_INFINITY
            var bMinY = Float.POSITIVE_INFINITY; var bMaxY = Float.NEGATIVE_INFINITY
            for (i in a.indices step 2) {
                val dx = b[i] - a[i]
                val dy = b[i + 1] - a[i + 1]
                count++
                sumX += dx.toDouble() * dx
                sumY += dy.toDouble() * dy
                peak = maxOf(peak, hypot(dx, dy))
                aMinY = minOf(aMinY, a[i + 1]); aMaxY = maxOf(aMaxY, a[i + 1])
                bMinY = minOf(bMinY, b[i + 1]); bMaxY = maxOf(bMaxY, b[i + 1])
                write?.writeFloat(dx)
                write?.writeFloat(dy)
                if (read != null) {
                    val ex = (dx - read.readFloat()).toDouble()
                    val ey = (dy - read.readFloat()).toDouble()
                    error += ex * ex + ey * ey
                }
            }
            shortening = maxOf(shortening, aMaxY - aMinY - (bMaxY - bMinY))
            val ax = region.roots.map { a[2 * it] }.average()
            val ay = region.roots.map { a[2 * it + 1] }.average()
            val bx = region.roots.map { b[2 * it] }.average()
            val by = region.roots.map { b[2 * it + 1] }.average()
            for (v in region.tips) {
                val x0 = a[2 * v] - ax
                val y0 = a[2 * v + 1] - ay
                val x1 = b[2 * v] - bx
                val y1 = b[2 * v + 1] - by
                tipX += x1 - x0; tipY += y1 - y0; tipCount++
                cross += x0 * y1 - y0 * x1
                dot += x0 * x1 + y0 * y1
            }
            if (!flipped) {
                val indices = triangles.getValue(id)
                for (i in indices.indices step 3) {
                    val v0 = indices[i] * 2
                    val v1 = indices[i + 1] * 2
                    val v2 = indices[i + 2] * 2
                    val before = signedArea(a, v0, v1, v2)
                    if (abs(before) >= 1f && signedArea(b, v0, v1, v2) * before < 0f) {
                        flipped = true
                        break
                    }
                }
            }
        }
        return MotionSample(peak, sqrt((sumX + sumY) / count), sqrt(sumX / count), sqrt(sumY / count),
            tipX / tipCount, tipY / tipCount, atan2(cross, dot) * 180.0 / PI, shortening, flipped,
            if (read == null) null else sqrt(error / count))
    }

    private val mixedClips = setOf("mixed", "head-mixed")

    private fun clips(part: SimBenchPart): List<String> {
        val standard = if (part.name == "skirt") listOf("compression", "sway", "rotation", "mixed")
        else listOf("head-flick", "head-roll", "head-lift", "head-mixed")
        return if (setting("PSD2LIVE_DIRECT_REVIEW_MIX_ONLY", "0") == "1") standard.filter { it in mixedClips } else standard
    }

    private fun drive(
        part: SimBenchPart, clip: String, seconds: Float, parameters: Map<String, Parameter>, fixture: String,
    ) = if (part.name == "skirt") skirtDrive(clip, seconds, parameters,
        if (fixture == "controlled") rotationId else "ParamBodyAngleZ") else headDrive(clip, seconds, parameters)

    private fun signedArea(p: FloatArray, a: Int, b: Int, c: Int): Float =
        (p[b] - p[a]) * (p[c + 1] - p[a + 1]) - (p[b + 1] - p[a + 1]) * (p[c] - p[a])

    private fun headDrive(clip: String, t: Float, parameters: Map<String, Parameter>): Map<String, Float> {
        fun ramp(at: Float, length: Float): Float {
            val p = ((t - at) / length).coerceIn(0f, 1f)
            return p * p * (3f - 2f * p)
        }
        val reversal = 0.92f * ramp(0.3f, 0.16f) - 1.68f * ramp(0.72f, 0.14f) + 0.76f * ramp(1.15f, 0.17f)
        val secondary = 0.62f * ramp(0.34f, 0.21f) - 1.23f * ramp(0.79f, 0.18f) + 0.61f * ramp(1.3f, 0.15f)
        val u = when (clip) {
            "head-flick" -> mapOf("ParamAngleX" to reversal)
            "head-roll" -> mapOf("ParamAngleZ" to reversal)
            "head-lift" -> mapOf("ParamAngleY" to reversal)
            else -> mapOf("ParamAngleZ" to reversal, "ParamAngleY" to 0.45f * secondary, "ParamAngleX" to -0.30f * secondary)
        }
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

    private fun skirtDrive(clip: String, seconds: Float, parameters: Map<String, Parameter>, rotation: String): Map<String, Float> {
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
            "rotation" -> mapOf(rotation to turn)
            else -> mapOf("ParamBodyAngleX" to 0.70f * swing, "ParamBodyAngleY" to 0.8f * squash, rotation to -0.66f * turn)
        }
        return motion.mapNotNull { (id, u) -> parameters[id]?.let { p ->
            id to (p.default + u * if (u < 0f) p.default - p.min else p.max - p.default)
        } }.toMap()
    }
}

/** Decomposes the motion at the same input pose in the imported MOC3 model. */
private class MixedMotionAnalysis(
    private val model: PuppetModel, private val targets: List<DrawableId>, drivenIds: Set<String>, private val fps: Int,
) {
    private val evaluator = CpuDeformationEvaluator()
    private val parameters = model.parameters.filter { it.id.raw in drivenIds }
    private val defaults = parameters.associate { it.id.raw to it.default }
    private var removedLimits = 0
    private val unlimitedModel = model.copy(
        drawables = model.drawables.map { drawable -> drawable.copy(blendShapes = removeLimits(drawable.blendShapes)) },
        deformers = model.deformers.map { deformer ->
            when (deformer) {
                is Deformer.Warp -> deformer.copy(blendShapes = removeLimits(deformer.blendShapes))
                is Deformer.Rotation -> deformer.copy(blendShapes = removeLimits(deformer.blendShapes))
            }
        },
    )
    private val actualTrack = ResidualSeries(fps)
    private val unlimitedTrack = ResidualSeries(fps)
    private val sumTrack = ResidualSeries(fps)
    private val axisTracks = parameters.map { ResidualSeries(fps) }
    private val rows = ArrayList<MixedFrame>()

    private fun <F : Any> removeLimits(bindings: List<BlendShapeBinding<F>>): List<BlendShapeBinding<F>> =
        bindings.map { binding ->
            if (binding.parameterId.raw in defaults && binding.limits.isNotEmpty()) {
                removedLimits += binding.limits.size
                binding.copy(limits = emptyList())
            } else binding
        }

    fun add(
        input: Map<String, Float>, pose: Map<String, Float>,
        rest: Map<DrawableId, FloatArray>, actual: Map<DrawableId, FloatArray>,
    ) {
        val neutral = input + defaults
        val currentRest = if (input.keys.any { it in defaults }) evaluate(model, neutral) else rest
        val applied = parameters.map { pose[it.id.raw] ?: it.default }
        val normalized = applied.zip(parameters).map { (value, parameter) ->
            val span = if (value < parameter.default) parameter.default - parameter.min else parameter.max - parameter.default
            (value - parameter.default) / span.coerceAtLeast(1e-6f)
        }
        val actualDelta = delta(actual, currentRest)
        val unlimitedDelta = if (removedLimits == 0) actualDelta else delta(evaluate(unlimitedModel, pose), currentRest)
        val sum = FloatArray(actualDelta.size)
        val axes = parameters.indices.map { i ->
            val isolated = neutral + (parameters[i].id.raw to applied[i])
            val effect = delta(evaluate(unlimitedModel, isolated), currentRest)
            for (j in sum.indices) sum[j] += effect[j]
            axisTracks[i].add(effect)
        }
        val individualRms = sqrt(axes.sumOf { it.rms * it.rms })
        val series = listOf(actualTrack.add(actualDelta), unlimitedTrack.add(unlimitedDelta), sumTrack.add(sum))
        val actualToUnlimited = ratio(series[0].rms, series[1].rms)
        val sumToIndividual = ratio(series[2].rms, individualRms)
        val unlimitedToSum = differenceRms(unlimitedDelta, sum)
        rows.add(MixedFrame(applied, normalized, axes, series, individualRms, sumToIndividual, actualToUnlimited, unlimitedToSum))
    }

    private fun evaluate(rig: PuppetModel, pose: Map<String, Float>) =
        evaluator.evaluate(rig, pose.mapKeys { ParameterId(it.key) }).worldPositions

    private fun delta(current: Map<DrawableId, FloatArray>, rest: Map<DrawableId, FloatArray>): FloatArray {
        val result = FloatArray(targets.sumOf { rest.getValue(it).size })
        var offset = 0
        for (target in targets) {
            val base = rest.getValue(target)
            val moved = current.getValue(target)
            check(base.size == moved.size)
            for (j in base.indices) result[offset++] = moved[j] - base[j]
        }
        return result
    }

    fun write(out: File, clip: String) {
        val trackNames = listOf("actual", "uncapped", "axis_sum")
        val trackColumns = listOf("rms_px", "speed_rms_px_s", "acceleration_rms_px_s2", "jerk_rms_px_s3")
        val labels = parameters.map { it.id.raw }
        val baseColumns = listOf("frame", "time_s", "independent_rms_px", "axis_sum_over_independent",
            "actual_over_uncapped_rms", "uncapped_minus_axis_sum_rms_px")
        val seriesColumns = trackNames.flatMap { name -> trackColumns.map { "${name}_$it" } }
        val axisColumns = labels.flatMap { id ->
            (listOf("value", "fraction") + trackColumns).map { "${id}_$it" }
        }
        out.resolve("$clip.mix.csv").bufferedWriter().use { csv ->
            csv.appendLine((baseColumns + seriesColumns + axisColumns).joinToString(","))
            for ((frame, row) in rows.withIndex()) {
                val base = listOf(frame, frame.toDouble() / fps, row.individualRms, row.sumToIndividual,
                    row.actualToUnlimited, row.unlimitedToSum)
                val axis = labels.indices.flatMap { i -> listOf(row.values[i], row.normalized[i]) + row.axes[i].columns() }
                csv.appendLine((base + row.series.flatMap { it.columns() } + axis).joinToString(",") { it?.toString().orEmpty() })
            }
        }
        val active = rows.filter { it.individualRms > rows.maxOf { frame -> frame.individualRms } * 0.10 }
        val summary = buildString {
            appendLine("removed_blend_limit_curves=$removedLimits")
            appendLine("active_frames=${active.size}/${rows.size}")
            appendLine("axis_sum_over_independent_active=${ratio(aggregate(active.map { it.series[2].rms }), aggregate(active.map { it.individualRms }))}")
            appendLine("actual_over_uncapped_rms_active=${ratio(aggregate(active.map { it.series[0].rms }), aggregate(active.map { it.series[1].rms }))}")
            appendLine("uncapped_minus_axis_sum_rms_px_active=${aggregate(active.map { it.unlimitedToSum })}")
            for ((i, name) in trackNames.withIndex()) appendMotion(name, rows.map { it.series[i] })
            for ((i, id) in labels.withIndex()) {
                appendLine("${id}_peak_abs_fraction=${rows.maxOf { abs(it.normalized[i]) }}")
                appendMotion(id, rows.map { it.axes[i] })
            }
        }
        out.resolve("$clip.mix.txt").writeText(summary)
    }

    private fun StringBuilder.appendMotion(name: String, samples: List<ResidualSample>) {
        appendLine("${name}_peak_rms_px=${samples.maxOf { it.rms }}")
        appendLine("${name}_p95_speed_rms_px_s=${percentile95(samples.mapNotNull { it.speed })}")
        appendLine("${name}_p95_acceleration_rms_px_s2=${percentile95(samples.mapNotNull { it.acceleration })}")
        appendLine("${name}_p95_jerk_rms_px_s3=${percentile95(samples.mapNotNull { it.jerk })}")
        val maxJerkFrame = samples.indices.maxBy { samples[it].jerk ?: 0.0 }
        appendLine("${name}_max_jerk_rms_px_s3=${samples[maxJerkFrame].jerk ?: 0.0}")
        appendLine("${name}_max_jerk_time_s=${maxJerkFrame.toDouble() / fps}")
    }

    private fun percentile95(values: List<Double>): Double =
        values.sorted().let { if (it.isEmpty()) 0.0 else it[(it.size - 1) * 95 / 100] }

    private fun aggregate(values: List<Double>): Double =
        if (values.isEmpty()) 0.0 else sqrt(values.sumOf { it * it } / values.size)

    private fun ratio(numerator: Double, denominator: Double): Double? =
        if (denominator > 1e-6) numerator / denominator else null

    private fun differenceRms(a: FloatArray, b: FloatArray): Double = sqrt(
        a.indices.sumOf { i -> (a[i] - b[i]).toDouble().let { it * it } } / (a.size / 2),
    )
}

private class MixedFrame(
    val values: List<Float>, val normalized: List<Float>, val axes: List<ResidualSample>, val series: List<ResidualSample>,
    val individualRms: Double, val sumToIndividual: Double?, val actualToUnlimited: Double?, val unlimitedToSum: Double,
)

private class ResidualSample(val rms: Double, val speed: Double?, val acceleration: Double?, val jerk: Double?) {
    fun columns() = listOf(rms, speed, acceleration, jerk)
}

private class ResidualSeries(private val fps: Int) {
    private val prior = ArrayDeque<FloatArray>(3)

    fun add(current: FloatArray): ResidualSample {
        val vertices = current.size / 2
        val norm = sqrt(current.sumOf { it.toDouble() * it } / vertices)
        fun rms(coefficients: IntArray): Double? {
            val order = coefficients.size - 1
            if (prior.size < order) return null
            val sum = current.indices.sumOf { i ->
                var component = current[i].toDouble()
                for (previous in 1..order) component += coefficients[previous] * prior[previous - 1][i].toDouble()
                component * component
            }
            return sqrt(sum / vertices) * when (order) {
                1 -> fps.toDouble()
                2 -> fps.toDouble() * fps
                else -> fps.toDouble() * fps * fps
            }
        }

        val sample = ResidualSample(norm, rms(intArrayOf(1, -1)), rms(intArrayOf(1, -2, 1)), rms(intArrayOf(1, -3, 3, -1)))
        if (prior.size == 3) prior.removeLast()
        prior.addFirst(current)
        return sample
    }
}
