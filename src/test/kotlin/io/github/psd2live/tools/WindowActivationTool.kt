package io.github.psd2live.tools

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.github.psd2live.application.WorkspaceCloseReason
import io.github.psd2live.application.WorkspaceRenderCoordinator
import io.github.psd2live.core.*
import io.github.psd2live.document.WorkspaceSourceArt
import io.github.psd2live.document.WorkspaceSourceLayer
import io.github.psd2live.format.eval.P2lRuntime
import io.github.psd2live.project.WorkspaceDocument
import io.github.psd2live.render.SkiaGpu
import io.github.psd2live.ui.state.CanvasMode
import io.github.psd2live.ui.state.DesktopWorkspace
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.views.LocalAwtWindow
import io.github.psd2live.ui.views.PSD2LiveApp
import io.github.psd2live.util.PresentationMeasurements
import io.github.psd2live.util.PresentationReceipt
import io.github.psd2live.util.PresentationToken
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.umamo.format.art.*
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities
import kotlin.test.Test

/** Real desktop/VM/viewport/Skiko acceptance, gated and never replaced by offscreen rendering. */
class WindowActivationTool {
    @Test fun smoke() { requireTools(); run(2, benchmark = false) }
    @Test fun measure() {
        requireTools()
        val samples = setting("PSD2LIVE_WINDOW_SAMPLES", "30").toInt()
        require(samples in 30..1000) { "Window measurement requires at least 30 activations" }
        run(samples, benchmark = true)
    }

    private fun run(samples: Int, benchmark: Boolean) = runBlocking {
        val warmups = if (benchmark) 5 else 1
        val out = output(if (benchmark) "window-activation" else "window-activation-smoke")
        val rows = ArrayList<JsonObject>()
        val raw = ConcurrentLinkedQueue<PresentationReceipt>()
        val receipts = Channel<PresentationReceipt>(Channel.UNLIMITED)
        val exit = AtomicReference<(() -> Unit)?>()
        val stopped = CompletableDeferred<Unit>()
        val windowReady = CompletableDeferred<java.awt.Window>()
        val environment = linkedMapOf("java" to System.getProperty("java.version"), "os" to System.getProperty("os.name"))
        var vm: PSD2LiveViewModel? = null
        var workspace: DesktopWorkspace? = null
        var failure: String? = null
        var phase = "native_preflight"
        var successful = 0
        var warmedUp = 0
        val timeoutMs = setting("PSD2LIVE_WINDOW_TIMEOUT_SECONDS", "30").toLong().also { require(it in 5..120) } * 1000
        val previousObserver = PresentationMeasurements.onCompleted
        check(previousObserver == null) { "Another presentation measurement is already active" }
        val audit = WindowActivationAudit()
        val durations = ArrayList<Long>()
        fun report() {
            out.resolve("report.json").writeText(buildJsonObject {
                put("metric", "model_activation_capture_to_window_gpu_completion")
                put("benchmark", benchmark); put("requestedSamples", samples); put("successfulSamples", successful)
                put("requestedWarmups", warmups); put("successfulWarmups", warmedUp)
                put("complete", failure == null && warmedUp == warmups && successful == samples)
                put("phase", phase); put("failure", failure?.let(::JsonPrimitive) ?: JsonNull)
                put("backend", "P2LRT"); put("pixelCorrectnessValidated", false); put("screenPresentationMeasured", false)
                put("workload", "four synthetic colored tiles; changing raster revision; no masks, physics or playback")
                put("cacheMode", "warm JVM/window context; new model and remounted preview view per activation")
                put("fullEditLatencyMeasured", false); put("editStartedAtNanos", JsonNull)
                put("editStartUnavailableReason", "Synthetic prepared-model installation; no end-to-end edit command is measured")
                put("timeoutMs", timeoutMs)
                putJsonObject("environment") { environment.forEach { (key, value) -> put(key, value) } }
                put("samples", JsonArray(rows))
                putJsonArray("rawReceipts") { raw.forEach { add(receiptJson(it)) } }
                if (benchmark && failure == null && successful == samples) {
                    val stats = firstFrameDistribution(durations)
                    putJsonObject("summary") { put("medianMs", stats.medianMs); put("p95Ms", stats.p95Ms); put("maxMs", stats.maxMs) }
                }
            }.toString())
        }
        try {
            // A local prepared copy avoids coupling a run to another checkout's in-progress native build.
            if (P2lRuntime.locate() == null) {
                val prepared = File("build/tools/window-activation/native/${System.mapLibraryName("p2l_runtime")}")
                if (prepared.isFile) System.setProperty("psd2live.runtime.library", prepared.absolutePath)
            }
            val library = checkNotNull(P2lRuntime.locate()) { "P2LRT native library missing; set PSD2LIVE_RUNTIME (not a skipped acceptance)" }
            environment["nativeLibrary"] = library.canonicalPath
            environment["nativeSha256"] = MessageDigest.getInstance("SHA-256").digest(library.readBytes()).joinToString("") { "%02x".format(it) }
            val runtime = checkNotNull(P2lRuntime.load(library)) { "P2LRT native library ABI is incompatible" }
            environment["nativeAbi"] = runtime.abiVersion.let { "${it ushr 16}.${it and 0xffff}" }
            environment["nativeAbiRequired"] = "${P2lRuntime.ABI_MAJOR}.${P2lRuntime.ABI_MINOR}"
            SkiaGpu.requestOpenGl()
            // These are separate runs: set PSD2LIVE_COMPOSITOR=0 for the non-composited viewport path.
            System.setProperty("psd2live.compositor", setting("PSD2LIVE_COMPOSITOR", "1").let { (it != "0").toString() })
            environment["compositorRequested"] = System.getProperty("psd2live.compositor")
            val viewModel = edt { PSD2LiveViewModel() }.also { vm = it }
            val desktop = DesktopWorkspace(viewModel, out.resolve("workspace").toPath()).also { workspace = it }
            edt { viewModel.attachWorkspace(desktop); viewModel.selectPreviewBackend(PreviewBackend.P2LRT) }
            PresentationMeasurements.onCompleted = { raw.add(it); receipts.trySend(it) }
            phase = "window_startup"
            Thread({
                try {
                    application(exitProcessOnExit = false) {
                        exit.set { exitApplication() }
                        Window(onCloseRequest = ::exitApplication,
                            state = rememberWindowState(size = DpSize(1200.dp, 800.dp)), title = "Window activation acceptance") {
                            LaunchedEffect(Unit) { windowReady.complete(window) }
                            CompositionLocalProvider(LocalAwtWindow provides window) { PSD2LiveApp(viewModel, window = window) }
                        }
                    }
                    stopped.complete(Unit)
                } catch (error: Throwable) { windowReady.completeExceptionally(error); stopped.completeExceptionally(error) }
            }, "window-activation-tool").apply { isDaemon = true; start() }
            val window = withTimeout(60_000) { windowReady.await() }
            val gpu = withTimeout(30_000) { SkiaGpu.status.first { it !is SkiaGpu.Status.Starting } }
            check(gpu is SkiaGpu.Status.Ready) { "Window has no usable OpenGL context: $gpu" }
            environment["gpu"] = gpu.description
            environment["display"] = edt { window.graphicsConfiguration.device.displayMode.let { "${it.width}x${it.height}@${it.refreshRate}" } }
            // No private method drives rendering. This reflection only reads the production activation token and
            // exercises its public coordinator rejection; changing its shape makes the tool fail closed.
            val coordinator = PSD2LiveViewModel::class.java.getDeclaredField("renderCoordinator").run {
                isAccessible = true; get(viewModel) as WorkspaceRenderCoordinator
            }
            val revisions = HashSet<String>()
            val states = HashSet<String>()
            var previousFrame: PreviewFrame? = null
            for (ordinal in 1..warmups + samples) {
                val index = ordinal - warmups
                phase = if (index <= 0) "warmup_$ordinal" else "sample_$index"
                val (document, model) = fixture(ordinal) // Preparation is outside activation timing.
                val installStarted = System.nanoTime()
                edt { viewModel.applySourceImport(viewModel.state.value, document, model, null, "window-activation-$ordinal") }
                withTimeout(60_000) { desktop.importedPsd(recoverLegacy = false) }
                val activation = edt {
                    viewModel.leaveStartPage()
                    viewModel.dismissMeshSplitBatch()
                    val canvasId = viewModel.state.value.activeCanvas.id
                    viewModel.state.value.activeWorkspace.canvases.filter { it.id != canvasId }
                        .forEach { viewModel.setCanvasMode(it.id, CanvasMode.EDIT) }
                    viewModel.setCanvasMode(canvasId, CanvasMode.PREVIEW)
                    viewModel.setAnimationEnabled(false)
                    val captured = desktop.captureTextures()
                    check(revisions.add(captured.revision) && states.add(captured.state)) { "Synthetic installation did not create a distinct revision/state" }
                    viewModel.refreshWorkspaceRenderer(model)
                    val superseded = checkNotNull(coordinator.presentation)
                    audit.supersede(superseded)
                    // Same EDT turn: queued native work may coalesce, but an obsolete activation must never complete.
                    viewModel.refreshWorkspaceRenderer(model)
                    val wanted = checkNotNull(coordinator.presentation)
                    check(wanted !== superseded)
                    val key = viewModel.canvasRenderKey(viewModel.state.value.activeCanvas.id, CanvasMode.PREVIEW)
                    val rejected = previousFrame?.let { old ->
                        val stale = old.copy(viewId = key)
                        val before = coordinator.frameFor(key).value
                        check(!coordinator.publish(stale)) { "Coordinator accepted an obsolete candidate" }
                        viewModel.acceptSdkFrame(stale)
                        check(coordinator.frameFor(key).value === before) { "VM accepted an obsolete frame" }
                        true
                    } ?: false
                    Triple(audit.expect(wanted, captured.revision, captured.state, key, installStarted), superseded.candidate, rejected)
                }
                val expected = activation.first
                val receipt = withTimeoutOrNull(timeoutMs) { receipts.receive() }
                if (receipt == null) {
                    if (index > 0) rows += row(index, expected, activation.second, activation.third, "timeout_no_window_receipt", null)
                    report()
                    error("No window GPU receipt for candidate ${expected.candidate} within ${timeoutMs}ms; status=${viewModel.state.value.sdkStatus}")
                }
                val nanos = try { audit.accept(expected, receipt) } catch (error: Throwable) {
                    rows += row(index, expected, activation.second, activation.third, "identity_rejected", receipt)
                    throw error
                }
                previousFrame = withTimeout(timeoutMs) { viewModel.sdkFrameFor(expected.viewId).first { it?.presentation?.token?.candidate == expected.candidate } }
                check(previousFrame?.backend == PreviewBackend.P2LRT) { "Requested P2LRT but another backend delivered the frame" }
                environment["gpu"] = SkiaGpu.primary?.description ?: gpu.description
                if (index > 0) {
                    check(activation.third) { "Missing stale-frame rejection evidence" }
                    rows += row(index, expected, activation.second, activation.third, "accepted", receipt)
                    durations += nanos; successful++
                    report()
                } else { warmedUp++; report() }
            }
            // Drain already delivered extras: duplicates/late obsolete callbacks cannot silently pass.
            check(receipts.tryReceive().isFailure) { "Unexpected extra window receipt" }
            phase = "complete"
        } catch (error: Throwable) {
            failure = "${error.javaClass.name}: ${error.message}"
            throw error
        } finally {
            PresentationMeasurements.onCompleted = previousObserver
            // Coroutine timeouts and event delivery replace polling/sleeps. Closing still runs if the EDT is busy.
            SwingUtilities.invokeLater {
                runCatching { vm?.close() }
                runCatching { workspace?.prepareClose(WorkspaceCloseReason.DISCARDED); workspace?.close() }
                exit.get()?.invoke()
            }
            if (exit.get() != null) withContext(NonCancellable) {
                if (withTimeoutOrNull(10_000) { stopped.await(); true } != true) failure = failure ?: "Window shutdown timed out"
            }
            if (failure == null && receipts.tryReceive().isSuccess) failure = "Unexpected late window receipt during shutdown"
            report()
        }
        check(failure == null && warmedUp == warmups && successful == samples) { failure ?: "Incomplete window acceptance" }
    }

    private suspend fun <T> edt(action: () -> T): T = withTimeout(30_000) {
        suspendCancellableCoroutine { continuation ->
            SwingUtilities.invokeLater { if (continuation.isActive) continuation.resumeWith(runCatching(action)) }
        }
    }

    private fun fixture(index: Int): Pair<WorkspaceDocument, RigPreviewModel> {
        val source = WorkspaceSourceArt(64, 64, List(4) { tile ->
            val rgba = ByteArray(24 * 24 * 4) { offset -> when (offset % 4) {
                0 -> (40 + (index * 29 + tile * 43) % 190).toByte()
                1 -> (40 + (index * 47 + tile * 17) % 190).toByte()
                2 -> (40 + (index * 13 + tile * 61) % 190).toByte()
                else -> 255.toByte()
            } }
            WorkspaceSourceLayer(LayerId("tile-$tile"), "Tile $tile", "", SourceLayerKind.Raster, true, tile,
                LayerBounds(tile % 2 * 32 + 4, tile / 2 * 32 + 4, 24, 24), 1f, false, LayerBlend.Normal,
                ChannelMask.ALL, LayerRaster(24, 24, rgba), null, null, false)
        }, emptyList())
        val model = PSD2LivePipeline().apply { deferRuntimeBundles = true }.buildRawMeshExport(source,
            PipelineConfig(meshOnly = true, generatePhysics = false, exportMotions = false, exportMoc3 = false, atlasSize = 512))
        check(model.rig.puppet.drawables.isNotEmpty())
        return WorkspaceDocument(source, emptyMap(), emptySet(), emptyMap(), emptyMap(), model.config.rigEdits,
            settings = buildJsonObject { put("meshOnly", true); put("generatePhysics", false) }) to model
    }

    private fun row(index: Int, expected: WindowActivationAudit.Expected, superseded: Long, rejected: Boolean,
                    status: String, receipt: PresentationReceipt?) = buildJsonObject {
        put("index", index); put("status", status); put("revision", expected.revision); put("state", expected.state)
        put("candidate", expected.candidate); put("viewId", expected.viewId); put("supersededCandidate", superseded)
        put("staleFrameRejected", rejected); put("syntheticInstallStartedAtNanos", expected.installStartedAtNanos)
        put("activationCapturedAtNanos", expected.capturedAtNanos); put("editStartedAtNanos", JsonNull)
        put("gpuCompletedAtNanos", receipt?.gpuCompletedAtNanos?.let(::JsonPrimitive) ?: JsonNull)
        put("activationToGpuNs", receipt?.let { JsonPrimitive(it.gpuCompletedAtNanos - expected.capturedAtNanos) } ?: JsonNull)
    }

    private fun receiptJson(receipt: PresentationReceipt) = buildJsonObject {
        put("revision", receipt.revision); put("state", receipt.state); put("candidate", receipt.candidate)
        put("viewId", receipt.viewId); put("capturedAtNanos", receipt.capturedAtNanos); put("gpuCompletedAtNanos", receipt.gpuCompletedAtNanos)
    }
}
