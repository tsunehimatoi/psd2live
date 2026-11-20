package io.github.psd2live.ui.views

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.core.PreviewBackend
import io.github.psd2live.core.RigEditOverlay
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.components.IconMouse
import io.github.psd2live.ui.components.IconPause
import io.github.psd2live.ui.components.IconPhysics
import io.github.psd2live.ui.components.IconPlay
import io.github.psd2live.ui.tutorial.TutorialTargetId
import io.github.psd2live.ui.tutorial.tutorialTarget
import io.github.psd2live.ui.views.texture.FloatingBar
import io.github.psd2live.ui.views.texture.FloatingMenu
import io.github.psd2live.ui.views.texture.FloatingMenuRadio

/**
 * Preview-tab twin of the edit left toolbar: the runtime that draws the preview, play/pause, mouse tracking,
 * physics and the project's frame rate live on the canvas instead of the docks, so the artist can reach them
 * without leaving the viewport.
 */
@Composable
internal fun BoxScope.CanvasPreviewToolbar(
	animationEnabled: Boolean,
	mouseTrackingEnabled: Boolean,
	smoothMouseTracking: Boolean,
	physicsEnabled: Boolean,
	physicsAvailable: Boolean,
	fps: Int,
	/** The runtime drawing the preview, and whether p2lrt runs in its advanced mode. */
	runtime: PreviewBackend,
	advanced: Boolean,
	/** Whether the PSD2Live runtime library is present; without it only Cubism can be picked. */
	p2lrtAvailable: Boolean,
	enabled: Boolean,
	onToggleAnimation: () -> Unit,
	onToggleMouseTracking: () -> Unit,
	onToggleSmoothTracking: () -> Unit,
	onTogglePhysics: () -> Unit,
	onSelectFps: (Int) -> Unit,
	/** Picks the runtime; [advanced] only matters for p2lrt. */
	onSelectRuntime: (backend: PreviewBackend, advanced: Boolean) -> Unit,
	modifier: Modifier = Modifier,
) {
	var fpsMenu by remember { mutableStateOf(false) }
	// An open rate menu keeps the toolbar open under it.
	CanvasOptionsRail(
		modifier = modifier.align(Alignment.TopStart).padding(start = 8.dp, top = 44.dp),
		leading = true,
		expandedWidth = 156.dp,
		pinned = fpsMenu,
	) {
		RailItem(
			label = tr(if (animationEnabled) "preview.idle.on" else "preview.idle.off"),
			selected = animationEnabled,
			enabled = enabled,
			onClick = onToggleAnimation,
			icon = { tint ->
				if (animationEnabled) IconPause(modifier = Modifier.size(14.dp), tint = tint)
				else IconPlay(modifier = Modifier.size(14.dp), tint = tint)
			},
		)
		RailItem(
			label = tr(if (mouseTrackingEnabled) "preview.mouseTracking.on" else "preview.mouseTracking.off"),
			selected = mouseTrackingEnabled,
			enabled = enabled,
			onClick = onToggleMouseTracking,
			icon = { tint -> IconMouse(active = mouseTrackingEnabled, modifier = Modifier.size(14.dp), tint = tint) },
		)
		RailItem(
			label = tr("preview.tracking.smooth"),
			selected = smoothMouseTracking,
			enabled = enabled && mouseTrackingEnabled,
			onClick = onToggleSmoothTracking,
			icon = { tint -> IconSmoothTracking(tint) },
		)
		val physicsOn = physicsEnabled && physicsAvailable
		RailItem(
			label = tr(if (physicsOn) "preview.physics.on" else "preview.physics.off"),
			selected = physicsOn,
			enabled = enabled && physicsAvailable,
			onClick = onTogglePhysics,
			icon = { tint -> IconPhysics(active = physicsOn, modifier = Modifier.size(14.dp), tint = tint) },
		)
		Box {
			RailItem(
				label = tr("preview.fps", if (fps > 0) "$fps" else tr("preview.fps.unlimited")),
				selected = false,
				enabled = enabled,
				onClick = { fpsMenu = true },
				icon = { tint ->
					Text(if (fps > 0) "$fps" else "∞", color = tint, fontSize = if (fps >= 100) 9.sp else 10.5.sp,
						fontWeight = FontWeight.Medium, maxLines = 1)
				},
			)
			FloatingMenu(expanded = fpsMenu, onDismiss = { fpsMenu = false }) {
				for (choice in (RigEditOverlay.FPS_CHOICES + fps).distinct()) {
					FloatingMenuRadio(fpsLabel(choice), selected = choice == fps, onSelect = { fpsMenu = false; onSelectFps(choice) })
				}
			}
		}
		RailDivider()
		// The three runtimes as rows of their own at the foot, the one drawing the preview lit: a choice of three is seen at a
		// glance rather than behind a menu. Without the PSD2Live runtime library only Cubism can be picked.
		val p2lrtShown = runtime == PreviewBackend.P2LRT
		for ((backend, mode) in listOf(PreviewBackend.CUBISM to false, PreviewBackend.P2LRT to false, PreviewBackend.P2LRT to true)) {
			val p2lrt = backend == PreviewBackend.P2LRT
			RailItem(
				label = runtimeLabel(backend, mode),
				selected = if (p2lrt) p2lrtShown && advanced == mode else !p2lrtShown,
				enabled = enabled && (!p2lrt || p2lrtAvailable),
				onClick = { onSelectRuntime(backend, if (p2lrt) mode else advanced) },
				icon = { tint ->
					// C for Cubism, P for p2lrt, P+ in its advanced mode.
					Text(if (!p2lrt) "C" else if (mode) "P+" else "P", color = tint,
						fontSize = if (p2lrt && mode) 9.5.sp else 10.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
				},
			)
		}
	}
}

/**
 * Preview's mode bar: the canvas mode menu alone, where the edit canvas has it with its mode's extras.
 * It is the way back to editing, so it stays where the edit canvas keeps it.
 */
@Composable
internal fun BoxScope.PreviewModeBar(editor: io.github.psd2live.ui.CanvasEditor, focus: () -> Unit) {
	FloatingBar(
		Modifier
			.align(Alignment.TopStart)
			.padding(start = 8.dp, top = 8.dp)
			.tutorialTarget(TutorialTargetId.MODE_BAR),
	) {
		CanvasModeMenu(
			keymap = editor.state.keymap,
			current = CanvasModeChoice.PREVIEW,
			modifier = Modifier
				.tutorialTarget(TutorialTargetId.EDIT_TAB)
				.tutorialTarget(TutorialTargetId.PREVIEW_TAB),
			onSelect = { choice ->
				editor.chooseCanvasMode(choice)
				focus()
			},
		)
	}
}

@Composable
private fun fpsLabel(fps: Int): String = if (fps > 0) "$fps FPS" else tr("preview.fps.unlimited")

private fun runtimeLabel(runtime: PreviewBackend, advanced: Boolean): String = tr(when {
	runtime == PreviewBackend.CUBISM -> "preview.runtime.cubism"
	advanced -> "preview.runtime.p2lrtAdvanced"
	else -> "preview.runtime.p2lrtStandard"
})

/**
 * Smooth tracking with Body Y: the pointer with the trail the gaze eases along after it, and the body's rise and sink
 * beside it.
 */
@Composable
internal fun IconSmoothTracking(tint: androidx.compose.ui.graphics.Color) =
	io.github.psd2live.ui.components.GridIcon(Modifier.size(14.dp), tint) {
		fun cursor(x: Float, y: Float) = path {
			val k = 0.72f
			fun at(px: Float, py: Float) = (x + (px - 4.5f) * k) to (y + (py - 2.5f) * k)
			at(4.5f, 2.5f).let { (u, v) -> m(u, v) }
			listOf(4.5f to 14.5f, 7.6f to 11.8f, 9.5f to 16f, 11.6f to 15.1f, 9.7f to 10.9f, 13.8f to 10.8f)
				.forEach { (px, py) -> at(px, py).let { (u, v) -> l(u, v) } }
			z()
		}
		shape(cursor(9.6f, 2.2f))
		// The trail it is followed along, thinning back to where the gaze was.
		dot(9.2f, 13.6f, 1.2f, color.copy(alpha = color.alpha * 0.75f))
		dot(7.6f, 15.4f, 0.95f, color.copy(alpha = color.alpha * 0.5f))
		dot(5.6f, 16.2f, 0.75f, color.copy(alpha = color.alpha * 0.3f))
		line(2.8f, 6.6f, 2.8f, 13.4f)
		chevron(2.8f, 5.6f, 0f, -1f, 2.2f)
		chevron(2.8f, 14.4f, 0f, 1f, 2.2f)
	}
