package io.github.psd2live.application

import io.github.psd2live.core.CanvasViewport
import io.github.psd2live.core.RigCanvasSupport
import io.github.psd2live.core.RigPreviewModel
import io.github.psd2live.core.StandardParameters
import io.github.psd2live.core.boundedPreviewPose
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import java.awt.RenderingHints
import java.awt.image.BufferedImage

/** A fixed canvas pose and framing shared by every candidate in one exploration. */
internal class WorkspaceTurnStylePreview(
    private val capture: WorkspaceCapture<RigPreviewModel>,
    private val commands: WorkspaceDocumentCommands,
    private val pose: Map<ParameterId, Float>,
    private val visibleLayers: Set<String>,
) {
    data class Thumbnail(val current: BufferedImage, val turned: BufferedImage)
    data class AngleFrame(val x: Float, val y: Float, val image: BufferedImage)

    val state get() = capture.state

    companion object {
        const val ANGLE_COUNT = 9
        private const val WIDTH = 224
        private const val HEIGHT = 168
        private val parallelism = Runtime.getRuntime().availableProcessors().coerceIn(1, 3)
        // Hold one slot through rendering to bound the number of rebuilt models in memory.
        internal val modelSlots = Semaphore(parallelism)
        private val rasterDispatcher = Dispatchers.Default.limitedParallelism(parallelism)

        internal fun renderFace(model: RigPreviewModel, analysis: io.github.psd2live.core.PipelineAnalysis,
                                pose: Map<ParameterId, Float>, visibleLayers: Set<String>): BufferedImage {
            val face = analysis.anchors.face
            val scale = minOf(WIDTH / (face.width.coerceAtLeast(1f) * 1.65), HEIGHT / (face.height.coerceAtLeast(1f) * 1.48))
            val viewport = CanvasViewport(scale, WIDTH / 2.0 - face.centerX * scale, HEIGHT / 2.0 - face.centerY * scale,
                analysis.source.widthPx.toFloat(), analysis.source.heightPx.toFloat())
            val image = BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB)
            val graphics = image.createGraphics()
            try {
                graphics.clipRect(0, 0, WIDTH, HEIGHT)
                graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                RigCanvasSupport.paintTexturedRig(graphics, model, RigCanvasSupport.evaluateExact(model, pose), viewport,
                    visibleLayerIds = visibleLayers)
            } finally {
                graphics.dispose()
            }
            return image
        }
    }

    private val renames = WorkspaceConvertedIds.parameters(emptyList(), capture.document.rigEdits.authoringJournal)

    suspend fun thumbnail(operation: WorkspaceDocumentOperation): Thumbnail = modelSlots.withPermit {
        val context = currentCoroutineContext()
        commands.renderTurnStyle(capture.projectId, state, operation) { model ->
            val parameters = model.rig.puppet.parameters
            val base = boundedPreviewPose(pose, parameters)
            val x = axis(parameters, StandardParameters.ANGLE_X)
            val y = axis(parameters, StandardParameters.ANGLE_Y)
            val turned = base + mapOf(x.id to (x.default + (x.max - x.default) * 0.75f),
                y.id to (y.default + (y.max - y.default) * 0.4f))
            runInterruptible(rasterDispatcher) {
                val current = render(model, base)
                context.ensureActive()
                Thumbnail(current, render(model, turned))
            }
        }
    }

    suspend fun angles(
        operation: WorkspaceDocumentOperation,
        onFrame: suspend (Int, AngleFrame) -> Unit = { _, _ -> },
    ): List<AngleFrame> = modelSlots.withPermit {
        commands.renderTurnStyle(capture.projectId, state, operation) { model ->
            val parameters = model.rig.puppet.parameters
            val x = axis(parameters, StandardParameters.ANGLE_X)
            val y = axis(parameters, StandardParameters.ANGLE_Y)
            val base = boundedPreviewPose(pose, parameters)
            val poses = listOf(y.max, y.default, y.min).flatMap { pitch ->
                listOf(x.min, x.default, x.max).map { yaw -> yaw to pitch }
            }
            coroutineScope {
                poses.mapIndexed { index, (yaw, pitch) ->
                    async {
                        val frame = runInterruptible(rasterDispatcher) {
                            AngleFrame(yaw, pitch, render(model, base + mapOf(x.id to yaw, y.id to pitch)))
                        }
                        onFrame(index, frame)
                        frame
                    }
                }.awaitAll()
            }
        }
    }

    private fun axis(parameters: List<Parameter>, id: ParameterId): Parameter {
        val resolved = renames[id.raw]?.let(::ParameterId) ?: id
        return parameters.firstOrNull { it.id == resolved }
            ?: error("Missing head angle parameter ${resolved.raw}")
    }

    private fun render(model: RigPreviewModel, pose: Map<ParameterId, Float>) =
        renderFace(model, capture.model.analysis, pose, visibleLayers)
}
