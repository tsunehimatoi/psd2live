package io.github.psd2live.ui.views

import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.skiaCanvas
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.psd2live.application.WorkspacePreviewPhysics
import io.github.psd2live.application.WorkspacePsdGuide
import io.github.psd2live.core.*
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.SkiaRigPainter
import io.github.psd2live.ui.SourcePixelImages
import io.github.psd2live.ui.SourcePixelTextures
import io.github.psd2live.ui.components.IconMouse
import io.github.psd2live.ui.components.IconPause
import io.github.psd2live.ui.components.IconPhysics
import io.github.psd2live.ui.components.IconPlay
import io.github.psd2live.ui.state.FramePacer
import io.github.psd2live.ui.state.frameIntervalNanos
import io.github.psd2live.ui.theme.Corners
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.Motion
import io.github.psd2live.ui.theme.Spacing
import io.github.psd2live.ui.views.texture.BarChip
import io.github.psd2live.ui.views.texture.FloatingBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Canvas as SkiaCanvas
import org.umamo.render.eval.DeformedGeometry
import org.umamo.runtime.model.ParameterId
import java.awt.image.BufferedImage
import java.util.IdentityHashMap

internal data class GuideRigFrame(val preview: WorkspacePsdGuide.Preview, val geometry: DeformedGeometry)

internal fun guideRigFrame(
    preview: WorkspacePsdGuide.Preview,
    pose: Map<ParameterId, Float> = emptyMap(),
    pointer: Offset? = null,
): GuideRigFrame {
    val values = pointerPreviewPose(pose, pointer?.x ?: 0f, pointer?.y ?: 0f,
        preview.model.rig.puppet.parameters, tracking = pointer != null)
    return GuideRigFrame(preview, RigCanvasSupport.evaluateExact(preview.model, values))
}

/** All tiles can share the artwork's original pixel textures and any identical atlas pages. */
internal class GuideRigRenderers : AutoCloseable {
    private val atlases = HashMap<List<BufferedImage>, SkiaRigPainter>()
    private val sourceTextures = SourcePixelTextures()
    private data class ModelTextures(val painter: SkiaRigPainter, val layers: SourcePixelImages)
    private val models = IdentityHashMap<RigPreviewModel, ModelTextures>()

    fun paint(canvas: SkiaCanvas, frame: GuideRigFrame, viewport: CanvasViewport) {
        val model = frame.preview.model
        val (painter, layers) = models.getOrPut(model) {
            val painter = atlases.getOrPut(model.atlas.pages.map { it.image }) { SkiaRigPainter(model.atlas) }
            ModelTextures(painter, SourcePixelImages(model, sourceTextures))
        }
        painter.paint(canvas, model, frame.geometry, viewport, visibleLayerIds = frame.preview.visible, sources = layers)
    }

    override fun close() {
        models.values.forEach { it.layers.close() }
        atlases.values.forEach { it.close() }
        sourceTextures.close()
        models.clear()
        atlases.clear()
    }
}

@Composable
internal fun GuideRigCanvas(frame: GuideRigFrame?, renderers: GuideRigRenderers, modifier: Modifier = Modifier) {
    val subject = frame?.preview
    val opacity = animateFloatAsState(if (subject == null) 0f else 1f, tween(Motion.Fade, easing = LinearOutSlowInEasing))
    Canvas(modifier.graphicsLayer { alpha = opacity.value }) {
        if (frame == null || size.width <= 0f || size.height <= 0f) return@Canvas
        val bounds = frame.preview.bounds
        val scale = minOf(size.width / bounds.width.coerceAtLeast(1f), size.height / bounds.height.coerceAtLeast(1f)) * .84
        val source = frame.preview.model.analysis.source
        val viewport = CanvasViewport(scale, size.width * .5 - bounds.centerX * scale, size.height * .5 - bounds.centerY * scale,
            source.widthPx.toFloat(), source.heightPx.toFloat())
        clipRect { drawIntoCanvas { renderers.paint(it.skiaCanvas, frame, viewport) } }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
internal fun Modifier.guidePointer(enabled: Boolean, onChange: (Offset?) -> Unit): Modifier =
    composed {
        var size by remember { mutableStateOf(IntSize.Zero) }
        fun move(position: Offset?) {
            if (!enabled || position == null || size.width <= 0 || size.height <= 0) return
            onChange(Offset((position.x * 2f / size.width - 1f).coerceIn(-1f, 1f),
                (1f - position.y * 2f / size.height).coerceIn(-1f, 1f)))
        }
        this.onSizeChanged { size = it }
            .onPointerEvent(PointerEventType.Enter) { move(it.changes.firstOrNull()?.position) }
            .onPointerEvent(PointerEventType.Move) { move(it.changes.firstOrNull()?.position) }
            .onPointerEvent(PointerEventType.Exit) { onChange(null) }
    }

@Composable
internal fun LiveGuidePreview(preview: WorkspacePsdGuide.Preview?, modifier: Modifier = Modifier) {
    val colors = LocalToolColors.current
    val model = preview?.model
    val renderers = remember(model) { GuideRigRenderers() }
    DisposableEffect(renderers) { onDispose(renderers::close) }
    var tracking by remember { mutableStateOf(true) }
    var idle by remember { mutableStateOf(false) }
    var physics by remember { mutableStateOf(true) }
    var pointer by remember { mutableStateOf<Offset?>(null) }
    val physicsAvailable = remember(model) { model?.let(PhysicsCatalog::active).orEmpty().isNotEmpty() }
    val motionAvailable = model?.rig?.modifierOutput?.clips.orEmpty().any { it.group in setOf("Idle", "Blink") || it.name in setOf("Idle", "Blink") }
    val physicsOn = physics && physicsAvailable
    val idleOn = idle && motionAvailable
    val trackingActive by remember { derivedStateOf { tracking && pointer != null } }
    val physicsClock = remember(model, physicsOn) { WorkspacePreviewPhysics() }
    val physicsLock = remember(physicsClock) { Mutex() }
    var clock by remember(model) { mutableStateOf(PreviewAnimationClock()) }
    var frame by remember(preview) { mutableStateOf<GuideRigFrame?>(null) }

    LaunchedEffect(preview, trackingActive, idleOn, physicsOn) {
        if (preview == null || model == null) return@LaunchedEffect
        var lastFrame = 0L
        // Use the editor's playback clocks while active and stop requesting frames when the model settles.
        val pacer = FramePacer(frameIntervalNanos(60))
        do {
            val now = withFrameNanos { it }
            if (!pacer.due(now)) continue
            val dt = if (lastFrame == 0L) 1f / 60f else ((now - lastFrame) / 1e9f).coerceIn(.001f, .1f)
            lastFrame = now
            val follow = pointer.takeIf { tracking }
            val requested = clock.advance(dt, idleOn, model.config, null)
            frame = withContext(Dispatchers.Default) {
                val base = if (idleOn) requested.sample(model, true, false, emptyMap()) else emptyMap()
                val input = pointerPreviewPose(base, follow?.x ?: 0f, follow?.y ?: 0f, model.rig.puppet.parameters,
                    tracking = follow != null)
                val pose = if (physicsOn) {
                    val outputs = physicsLock.withLock { physicsClock.step(model, input, emptySet(), dt, idleOn) }
                    boundedPreviewPose(input + outputs, model.rig.puppet.parameters)
                } else input
                GuideRigFrame(preview, RigCanvasSupport.evaluateExact(model, pose))
            }
            clock = requested
        } while (isActive && (idleOn || trackingActive || physicsOn && !physicsClock.settled))
    }

    Box(modifier.fillMaxSize().clip(RoundedCornerShape(Corners.Panel)).background(colors.inputBackground)
        .guidePointer(tracking && model != null) { pointer = it }) {
        GuideRigCanvas(frame, renderers, Modifier.fillMaxSize())
        if (model != null) FloatingBar(Modifier.align(Alignment.TopStart).padding(Spacing.Margin)) {
            GuideTrackingChip(tracking) { tracking = !tracking; pointer = null }
            if (motionAvailable) BarChip(null, idleOn, { idle = !idle }, tooltip = tr("preview.idle.on")) { color ->
                if (idleOn) IconPause(Modifier.size(14.dp), color) else IconPlay(Modifier.size(14.dp), color)
            }
            if (physicsAvailable) BarChip(null, physicsOn, { physics = !physics }, tooltip = tr("preview.physics.on")) { color ->
                IconPhysics(active = physicsOn, modifier = Modifier.size(14.dp), tint = color)
            }
        }
    }
}

@Composable
internal fun GuideTrackingChip(tracking: Boolean, toggle: () -> Unit) {
    val label = tr("preview.mouseTracking.on")
    BarChip(label, tracking, toggle, tooltip = tr(if (tracking) "preview.mouseTracking.on" else "preview.mouseTracking.off")) { color ->
        IconMouse(active = tracking, modifier = Modifier.size(14.dp), tint = color)
    }
}
