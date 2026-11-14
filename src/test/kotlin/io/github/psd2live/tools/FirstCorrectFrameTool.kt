package io.github.psd2live.tools

import io.github.psd2live.application.WorkspaceCapture
import io.github.psd2live.application.WorkspaceRuntime
import io.github.psd2live.core.*
import io.github.psd2live.document.WorkspaceSourceArt
import io.github.psd2live.document.WorkspaceSourceLayer
import io.github.psd2live.project.WorkspaceDocument
import io.github.psd2live.render.*
import kotlinx.serialization.json.*
import org.lwjgl.glfw.GLFW.*
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL11
import org.umamo.format.art.*
import kotlin.test.Test

/**
 * Synthetic, offscreen edit-canvas first-correct-frame latency, NOT window presentation latency.
 * GPU readback must finish and every tile's known centre pixel must match the captured visibility pattern.
 * No user files, VM/controller hooks, private artwork, native runtime or proprietary SDK are used.
 *
 * PSD2LIVE_TOOLS=1 ./gradlew test --tests '*FirstCorrectFrameTool.measure' --max-workers=2 -Ppsd2live.testForks=1
 * PSD2LIVE_FIRST_FRAME_SAMPLES defaults to 30 per tier AND cache mode; values below 30 are rejected.
 * Use '*FirstCorrectFrameTool.verifyReadback' for a bounded GPU correctness check (no performance report).
 * Both entries require an exclusive graphics window; normal unit tests never initialize GLFW.
 */
class FirstCorrectFrameTool {
    @Test fun measure() {
        requireTools()
        val samples = setting("PSD2LIVE_FIRST_FRAME_SAMPLES", "30").toInt()
        require(samples in 30..65534)
        run(samples, benchmark = true)
    }

    @Test fun verifyReadback() {
        requireTools()
        run(2, benchmark = false)
    }

    private fun run(samples: Int, benchmark: Boolean) {
        val rows = mutableListOf<Row>()
        var failure: String? = null
        val metadata = linkedMapOf<String, String>()
        val out = output(if (benchmark) "first-correct-frame" else "first-correct-frame-check")
        val previousRecording = RigBuildProfile.recording
        check(!previousRecording) { "Run without another active profiler" }
        RigBuildProfile.reset()
        RigBuildProfile.recording = benchmark
        try {
            check(glfwInit()) { "GLFW could not initialize; no timing result is available" }
            try {
                glfwDefaultWindowHints()
                glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE)
                glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3)
                glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3)
                glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE)
                val window = glfwCreateWindow(SIZE, SIZE, "Synthetic first frame", 0, 0)
                check(window != 0L) { "OpenGL 3.3 context unavailable; do not count this as a sample" }
                try {
                    glfwMakeContextCurrent(window)
                    GL.createCapabilities()
                    metadata["gpu"] = GL11.glGetString(GL11.GL_RENDERER).orEmpty()
                    metadata["vendor"] = GL11.glGetString(GL11.GL_VENDOR).orEmpty()
                    metadata["gl"] = GL11.glGetString(GL11.GL_VERSION).orEmpty()
                    metadata["java"] = System.getProperty("java.version")
                    metadata["os"] = System.getProperty("os.name") + " " + System.getProperty("os.version")
                    metadata["processors"] = Runtime.getRuntime().availableProcessors().toString()
                    for ((tier, columns) in listOf("small" to 4, "medium" to 12, "large" to 24)) {
                        // Model generation and authoritative installation are explicitly outside this metric.
                        val fixture = Fixture(tier, columns)
                        for (index in 1..samples) {
                            fixture.install(index)
                            rows += sample(fixture, "renderer_cold", index, renderer = null)
                        }
                        GlCanvasRenderer(mipmaps = false).use { renderer ->
                            repeat(if (benchmark) 5 else 1) { index ->
                                fixture.install(index)
                                draw(fixture.runtime.capture(), fixture, renderer) // discarded warm-up; no timing sample
                            }
                            for (index in 1..samples) {
                                fixture.install(index)
                                rows += sample(fixture, "renderer_warm", index, renderer)
                            }
                        }
                    }
                } finally { glfwDestroyWindow(window); GL.setCapabilities(null) }
            } finally { glfwTerminate() }
        } catch (error: Throwable) {
            failure = "${error.javaClass.name}: ${error.message}"
            throw error
        } finally {
            RigBuildProfile.recording = previousRecording
            // Even a failed run leaves its completed evidence, marked incomplete; no failed sample is aggregated.
            out.resolve("report.json").writeText(report(rows, metadata, failure, benchmark, samples).toString())
        }
    }

    private fun sample(fixture: Fixture, mode: String, index: Int, renderer: GlCanvasRenderer?): Row {
        val candidate = "${fixture.tier}/$mode/$index"
        val expected = fixture.expected(index)
        val started = System.nanoTime()
        val captured = RigBuildProfile.stage("first frame: capture") { fixture.runtime.capture() }
        val probe = FirstCorrectFrameProbe("first-frame/$candidate", captured.revision, candidate, captured.model, started, expected)
        val result = if (renderer == null) {
            val fresh = RigBuildProfile.stage("first frame: renderer initialization") { GlCanvasRenderer(mipmaps = false) }
            fresh.use { draw(captured, fixture, it) }
        } else draw(captured, fixture, renderer)
        val elapsed = probe.complete(captured.revision, candidate, result.model, result.pixels, result.completedAt)
        if (RigBuildProfile.recording) RigBuildProfile.add("first frame: capture to GPU readback", elapsed)
        return Row(fixture.tier, mode, index, probe.task, captured.revision, candidate,
            captured.model.rig.puppet.drawables.size,
            captured.model.rig.puppet.drawables.sumOf { (it.mesh?.positions?.size ?: 0) / 2 }, result.drawnMeshes, elapsed)
    }

    private class Readback(val model: RigPreviewModel, val pixels: List<Int>, val drawnMeshes: Int, val completedAt: Long)

    private fun draw(captured: WorkspaceCapture<RigPreviewModel>, fixture: Fixture, renderer: GlCanvasRenderer): Readback {
        val scene = RigBuildProfile.stage("first frame: evaluate and scene") {
            val model = captured.model
            val pose = RigCanvasSupport.evaluateExact(model)
            val visible = captured.document.source.layers.filter { captured.document.layerVisibility[it.id.raw] != false }
                .mapTo(HashSet()) { it.id.raw }
            CanvasScene(SIZE, SIZE, fixture.viewport, model, pose,
                ArtworkDrawList.build(model, pose, ArtworkOptions(visibleLayerIds = visible)))
        }
        return RigBuildProfile.stage("first frame: draw and GPU readback") {
            renderer.render(VIEW, scene).use { bitmap ->
                // glReadPixels in render() has synchronized the GPU. Record the endpoint before validation/reporting.
                val completed = System.nanoTime()
                check(GL11.glGetError() == GL11.GL_NO_ERROR) { "OpenGL reported an error" }
                check(bitmap.width == SIZE && bitmap.height == SIZE)
                Readback(scene.model, fixture.centres.map { (x, y) -> bitmap.getColor(x, y) }, scene.draws.size, completed)
            }
        }
    }

    private class Fixture(val tier: String, columns: Int) {
        private val count = columns * columns
        private val canvas = columns * CELL
        private val colors = List(count) { index ->
            (255 shl 24) or ((48 + index * 17 % 160) shl 16) or ((48 + index * 31 % 160) shl 8) or (48 + index * 47 % 160)
        }
        val viewport = CanvasViewport(SIZE.toDouble() / canvas, 0.0, 0.0, canvas.toFloat(), canvas.toFloat())
        val centres = List(count) { index ->
            ((index % columns * CELL + CELL / 2) * SIZE / canvas) to ((index / columns * CELL + CELL / 2) * SIZE / canvas)
        }
        private val source = WorkspaceSourceArt(canvas, canvas, List(count) { index ->
            val argb = colors[index]
            val rgba = ByteArray(12 * 12 * 4) { offset -> when (offset % 4) {
                0 -> (argb ushr 16).toByte(); 1 -> (argb ushr 8).toByte(); 2 -> argb.toByte(); else -> 255.toByte()
            } }
            WorkspaceSourceLayer(LayerId("tile-$index"), "Tile $index", "", SourceLayerKind.Raster, true, index,
                LayerBounds(index % columns * CELL + 2, index / columns * CELL + 2, 12, 12), 1f, false,
                LayerBlend.Normal, ChannelMask.ALL, LayerRaster(12, 12, rgba), null, null, false)
        }, emptyList())
        private val config = PipelineConfig(meshOnly = true, generatePhysics = false, exportMotions = false,
            exportMoc3 = false, atlasSize = 512, meshOuterMargin = 0f)
        private val base = PSD2LivePipeline().apply { deferRuntimeBundles = true }.buildRawMeshExport(source, config)
        val runtime = WorkspaceRuntime<RigPreviewModel>({ error("Synthetic snapshots are installed explicitly") })

        private fun visible(index: Int, ordinal: Int) = index >= 16 || (ordinal and (1 shl index)) == 0
        fun expected(ordinal: Int) = colors.mapIndexed { index, color -> if (visible(index, ordinal)) color else 0 }

        fun install(ordinal: Int) {
            val visibility = source.layers.mapIndexed { index, layer -> layer.id.raw to visible(index, ordinal) }.toMap()
            val currentConfig = base.config.copy(layerVisibility = visibility)
            val model = base.copy(config = currentConfig)
            val document = WorkspaceDocument(source, visibility, emptySet(), emptyMap(), emptyMap(), currentConfig.rigEdits)
            runtime.install(runtime.state.value.state, "synthetic-$tier", document, model, discardUnsaved = true)
        }
    }

    private data class Row(val tier: String, val mode: String, val index: Int, val task: String, val revision: String,
                           val candidate: String, val meshes: Int, val vertices: Int, val drawnMeshes: Int, val nanos: Long)

    private fun report(rows: List<Row>, metadata: Map<String, String>, failure: String?, benchmark: Boolean, requested: Int) = buildJsonObject {
        put("metric", "captured_revision_to_verified_offscreen_gpu_readback")
        put("windowPresentationMeasured", false)
        put("workload", "synthetic static meshes with changing visibility; no masks, deformers, physics or playback")
        put("benchmark", benchmark)
        put("complete", failure == null && rows.size == 3 * 2 * requested)
        put("requestedSamplesPerTierAndMode", requested)
        put("warmupFramesPerTier", if (benchmark) 5 else 1)
        put("coldDefinition", "new renderer/shaders/view/texture cache; existing JVM and GL context")
        put("warmDefinition", "same renderer and view after warm-up; changing captured visibility revision")
        put("excluded", "model generation/install, context startup, Compose scheduling/compositing, swap/presentation, pixel validation")
        put("outputPixels", "$SIZE x $SIZE; mipmaps=false; offscreen non-MSAA")
        put("failure", failure?.let(::JsonPrimitive) ?: JsonNull)
        putJsonObject("environment") { metadata.forEach { (key, value) -> put(key, value) } }
        putJsonArray("samples") { rows.forEach { row -> add(buildJsonObject {
            put("scenario", row.tier); put("cacheMode", row.mode); put("index", row.index)
            put("task", row.task); put("startRevision", row.revision); put("candidate", row.candidate)
            put("meshes", row.meshes); put("vertices", row.vertices)
            put("drawnMeshes", row.drawnMeshes)
            if (benchmark) put("captureToReadbackNs", row.nanos)
        }) } }
        if (benchmark && failure == null) putJsonArray("summary") {
            rows.groupBy { it.tier to it.mode }.forEach { (key, values) ->
                val stats = firstFrameDistribution(values.map { it.nanos })
                add(buildJsonObject { put("scenario", key.first); put("cacheMode", key.second); put("samples", stats.samples)
                    put("medianMs", stats.medianMs); put("p95Ms", stats.p95Ms); put("maxMs", stats.maxMs) })
            }
        }
    }

    companion object { private const val SIZE = 512; private const val CELL = 16; private const val VIEW = "first-correct-frame" }
}
