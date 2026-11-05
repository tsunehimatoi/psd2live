package io.github.psd2live.ui.views

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import io.github.psd2live.ui.views.texture.MenuGlide
import io.github.psd2live.ui.views.texture.glideItem
import io.github.psd2live.ui.views.tooloptions.GlidingColumn
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path as ComposePath
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.CanvasTool
import io.github.psd2live.ui.EditHierarchyMode
import io.github.psd2live.ui.components.CompactButton
import io.github.psd2live.ui.components.CompactCheckbox
import io.github.psd2live.ui.components.CompactDropdown
import io.github.psd2live.ui.components.CompactSlider
import io.github.psd2live.ui.components.CompactTextField
import io.github.psd2live.ui.components.CompactToggleChip
import io.github.psd2live.ui.components.GridIcon
import io.github.psd2live.ui.components.IconChevron
import io.github.psd2live.ui.components.IconAdd
import io.github.psd2live.ui.components.IconCheck
import io.github.psd2live.ui.components.IconClose
import io.github.psd2live.ui.components.IconFolder
import io.github.psd2live.ui.components.IconInfo
import io.github.psd2live.ui.components.IconPhysics
import io.github.psd2live.ui.components.IconPlay
import io.github.psd2live.ui.components.IconSkeleton
import io.github.psd2live.ui.components.document
import io.github.psd2live.ui.state.AppPage
import io.github.psd2live.ui.state.AppSettings
import io.github.psd2live.ui.state.HairMode
import io.github.psd2live.ui.state.PSD2LiveState
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.state.RecentFile
import io.github.psd2live.ui.state.RecentFileKind
import io.github.psd2live.ui.state.ShortcutAction
import io.github.psd2live.ui.state.StartPresetChoices
import io.github.psd2live.ui.state.StartQuickPreset
import io.github.psd2live.ui.state.StartSection
import io.github.psd2live.ui.state.WorkspacePreset
import io.github.psd2live.ui.state.rememberExistingRecentFiles
import io.github.psd2live.ui.state.rememberRecentFileDetails
import io.github.psd2live.ui.state.validLayoutCanvasSize
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography
import io.github.psd2live.ui.tutorial.TutorialId
import io.github.psd2live.ui.views.texture.AccentButton
import io.github.psd2live.ui.views.texture.AccentSplitButton
import io.github.psd2live.ui.views.texture.FloatingMenu
import io.github.psd2live.ui.views.texture.FloatingMenuDivider
import io.github.psd2live.ui.views.texture.FloatingMenuRadio
import io.github.psd2live.ui.views.texture.FloatingMenuRow
import io.github.psd2live.ui.views.texture.FloatingMenuSection
import io.github.psd2live.ui.theme.Motion
import io.github.psd2live.ui.theme.handCursor
import io.github.psd2live.ui.theme.selectOrOpen
import java.awt.Cursor
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/** Canvas sizes offered for a new Layout project, with the `start.layout.size.<key>` that names each. */
private val LayoutCanvasSizes = listOf(
	Triple("square1k", 1024, 1024),
	Triple("square2k", 2048, 2048),
	Triple("square4k", 4096, 4096),
	Triple("portrait", 2048, 4096),
	Triple("landscape", 4096, 2048),
)

private val DateFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())

/** The canvas size typed for a new Layout project, shared by the preview, the size list and the fields. */
@Stable
internal class LayoutDraft {
	var width by mutableStateOf("2048")
	var height by mutableStateOf("2048")
	val w: Int? get() = width.trim().toIntOrNull()
	val h: Int? get() = height.trim().toIntOrNull()
	val valid: Boolean get() = validLayoutCanvasSize(w, h)

	fun set(width: Int, height: Int) {
		this.width = width.toString()
		this.height = height.toString()
	}
}

/** What the page's lists have picked while it shows. */
@Stable
internal class StartSelection {
	/** The recent PSD the viewport previews; the generate action takes it, or asks for a file without one. */
	var psdPath by mutableStateOf<String?>(null)
	var recentPath by mutableStateOf<String?>(null)
	var recentFilter by mutableStateOf("")
	var showProjects by mutableStateOf(true)
	var showPsds by mutableStateOf(true)
	var recentGrid by mutableStateOf(true)
	val open = mutableStateMapOf<String, Boolean>()
}

/**
 * What the start page's panels share while the app runs: the canvas being typed, what the lists picked and the picture
 * sizes. The panels dock, float and redock on their own, so none of it may live in one panel's composition.
 */
@Stable
internal class StartPageModel {
	val layout = LayoutDraft()
	val selection = StartSelection()
	var tile by mutableStateOf(AppSettings.startPanelSize("tile", 104f, TILE))
	var card by mutableStateOf(AppSettings.startPanelSize("card", 168f, CARD))
	/** Where the release notes are scrolled, shared with the version list that jumps through them. */
	val updates = LazyListState()

	fun saveSizes() {
		AppSettings.setStartPanelSize("tile", tile)
		AppSettings.setStartPanelSize("card", card)
	}

	companion object {
		val TILE = 64f..200f
		val CARD = 120f..300f
	}
}

/** The files the start page's panels show, read the same way by each. */
private class StartFiles(val recent: List<RecentFile>?, selection: StartSelection) {
	val psds = recent.orEmpty().filter { it.kind == RecentFileKind.PSD }
	val selectedRecent = recent.orEmpty().firstOrNull { it.path == selection.recentPath }
	val selectedPsd = psds.firstOrNull { it.path == selection.psdPath }
}

@Composable
private fun rememberStartFiles(state: PSD2LiveState, viewModel: PSD2LiveViewModel, selection: StartSelection): StartFiles {
	val recent = rememberExistingRecentFiles(state.recentFiles)
	// The recent list opens on its newest file, so the details and the action have something to show.
	LaunchedEffect(recent) {
		// A first launch has nothing recent to show: it opens where a PSD can be dropped instead.
		if (recent != null) viewModel.settleStartLanding(recent.isNotEmpty())
		if (recent != null && recent.none { it.path == selection.recentPath }) selection.recentPath = recent.firstOrNull()?.path
		if (recent != null && recent.none { it.kind == RecentFileKind.PSD && it.path == selection.psdPath }) selection.psdPath = null
	}
	return StartFiles(recent, selection)
}

/** What the main panel's action does for the section shown; Enter does the same. */
private fun startPrimary(section: StartSection, files: StartFiles, model: StartPageModel, viewModel: PSD2LiveViewModel, actions: PageActions) {
	when (section) {
		StartSection.GENERATE -> files.selectedPsd?.let { viewModel.openRecentFile(it.path) } ?: actions.onOpenPsd?.invoke()
		StartSection.LAYOUT -> if (model.layout.valid) viewModel.createLayoutProject(model.layout.w!!, model.layout.h!!)
		StartSection.CMO3 -> actions.onOpenCmo3?.invoke()
		StartSection.RECENT -> files.selectedRecent?.let { viewModel.openRecentFile(it.path) }
		StartSection.UPDATES -> {}
	}
}

internal fun startSectionTitle(section: StartSection) = when (section) {
	StartSection.GENERATE -> tr("start.nav.generate")
	StartSection.LAYOUT -> tr("start.nav.layout")
	StartSection.CMO3 -> tr("start.nav.cmo3")
	StartSection.RECENT -> tr("start.nav.recent")
	StartSection.UPDATES -> tr("start.nav.updates")
}

/** The details panel's title, which follows the section: the presets a PSD import applies, or the picked file. */
internal fun startDetailsTitle(section: StartSection) = when (section) {
	StartSection.GENERATE -> tr("start.generate.presets")
	StartSection.LAYOUT -> tr("start.details.layout")
	StartSection.CMO3 -> tr("start.details.cmo3")
	StartSection.RECENT -> tr("start.details.file")
	StartSection.UPDATES -> tr("start.details.versions")
}

/** The quick set [choices] match, or null for a custom set. */
private fun matchedPreset(choices: StartPresetChoices) = StartQuickPreset.entries.firstOrNull { it.choices == choices }

/** The name of the preset set [choices] are: a quick set's, or "Custom". */
internal fun startPresetName(choices: StartPresetChoices) = matchedPreset(choices)?.let { tr(it.key) } ?: tr("start.quick.custom")

// ---------------------------------------------------------------------------------------------------------------------
// Panels
// ---------------------------------------------------------------------------------------------------------------------

/**
 * The start page's contents: where to begin, then where to learn. Picking a section shows it in the main panel;
 * opening a project, the tutorials, help and settings act at once.
 */
@Composable
internal fun StartNavPanel(state: PSD2LiveState, viewModel: PSD2LiveViewModel, actions: PageActions) {
	val section = viewModel.startSection
	val enabled = !state.isBusy
	val recent = rememberExistingRecentFiles(state.recentFiles)
	val order = StartSection.entries
	PageNavList(onStep = { step -> viewModel.setStartSection(order[(order.indexOf(section) + step).mod(order.size)]) }) {
		PageNavGroup(tr("start.nav.group.open"))
		PageNavItem(tr("start.nav.openProject"), false, trailing = state.keymap.labelFor(ShortcutAction.OPEN_PROJECT),
			enabled = enabled && actions.onOpenProject != null, onClick = { actions.onOpenProject?.invoke() }) {
			IconFolder(modifier = Modifier.size(13.dp), tint = it)
		}
		PageNavItem(tr("start.nav.recent"), section == StartSection.RECENT, trailing = recent?.size?.toString(),
			onClick = { viewModel.setStartSection(StartSection.RECENT) }) { WorkspacePresetIcon(WorkspacePreset.HISTORY, it, Modifier.size(13.dp)) }
		PageNavGroup(tr("start.nav.group.new"))
		PageNavItem(tr("start.nav.generate"), section == StartSection.GENERATE, trailing = state.keymap.labelFor(ShortcutAction.OPEN_PSD),
			onClick = { viewModel.setStartSection(StartSection.GENERATE) }) {
			WorkspacePresetIcon(WorkspacePreset.EDIT, it, Modifier.size(13.dp))
		}
		PageNavItem(tr("start.nav.layout"), section == StartSection.LAYOUT, onClick = { viewModel.setStartSection(StartSection.LAYOUT) }) {
			WorkspacePresetIcon(WorkspacePreset.LAYOUT, it, Modifier.size(13.dp))
		}
		PageNavItem(tr("start.nav.cmo3"), section == StartSection.CMO3, onClick = { viewModel.setStartSection(StartSection.CMO3) }) {
			IconDocument(Modifier.size(13.dp), it)
		}
		PageNavGroup(tr("start.nav.group.learn"))
		PageNavItem(tr("start.nav.updates"), section == StartSection.UPDATES, onClick = { viewModel.setStartSection(StartSection.UPDATES) }) {
			IconInfo(modifier = Modifier.size(13.dp), tint = it)
		}
		PageNavItem(tr("canvas.start.quickStart"), false, enabled = actions.onOpenTutorialCatalog != null, badge = true,
			onClick = { actions.onOpenTutorialCatalog?.invoke() }) { IconPlay(modifier = Modifier.size(12.dp), tint = it) }
		PageNavItem(tr("page.help"), false, trailing = state.keymap.labelFor(ShortcutAction.OPEN_HELP),
			onClick = { viewModel.showPage(AppPage.HELP) }) { PageIcon(AppPage.HELP, it, Modifier.size(13.dp)) }
		PageNavItem(tr("page.settings"), false, trailing = state.keymap.labelFor(ShortcutAction.OPEN_SETTINGS),
			onClick = { viewModel.showPage(AppPage.SETTINGS) }) { PageIcon(AppPage.SETTINGS, it, Modifier.size(13.dp)) }
	}
}

/**
 * The section picked in the contents, with its action in a bar beneath. Generation stays one gesture: a PSD dropped
 * anywhere on the window, or a recent PSD double-clicked, is generated with the presets the details panel shows.
 */
@Composable
internal fun StartMainPanel(state: PSD2LiveState, viewModel: PSD2LiveViewModel, actions: PageActions) {
	val model = actions.start
	val section = viewModel.startSection
	val enabled = !state.isBusy
	val files = rememberStartFiles(state, viewModel, model.selection)
	val primary = { startPrimary(section, files, model, viewModel, actions) }
	val focus = remember { FocusRequester() }
	LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
	Column(
		Modifier.fillMaxSize()
			.focusRequester(focus)
			.focusable()
			// Fields take Enter first: a typed canvas size reaches here only once its field lets it go.
			.onKeyEvent { event ->
				if (event.type == KeyEventType.KeyDown && (event.key == Key.Enter || event.key == Key.NumPadEnter) && enabled) {
					primary()
					true
				} else false
			},
	) {
		Box(Modifier.weight(1f).fillMaxWidth()) {
			when (section) {
				StartSection.GENERATE -> Column(Modifier.fillMaxSize()) {
					GenerateViewport(
						file = files.selectedPsd,
						choices = viewModel.startChoices,
						enabled = enabled && actions.onOpenPsd != null,
						dragging = actions.filesDragging,
						shortcut = state.keymap.labelFor(ShortcutAction.OPEN_PSD),
						onOpenPsd = { actions.onOpenPsd?.invoke() },
						onGenerate = primary,
						onClear = { model.selection.psdPath = null },
						modifier = Modifier.weight(1f).fillMaxWidth(),
					)
					RecentPsdStrip(
						files = files.psds,
						selectedPath = model.selection.psdPath,
						tile = model.tile,
						enabled = enabled,
						onTile = { model.tile = it; model.saveSizes() },
						onSelect = { model.selection.psdPath = it },
						actions = recentFileActions(viewModel, actions),
						onBrowse = { actions.onOpenPsd?.invoke() },
					)
				}
				StartSection.LAYOUT -> LayoutContent(model.layout, enabled) { w, h -> viewModel.createLayoutProject(w, h) }
				StartSection.CMO3 -> Cmo3Content(enabled, actions.onOpenCmo3)
				StartSection.RECENT -> RecentBrowser(files.recent, model, enabled, recentFileActions(viewModel, actions))
				StartSection.UPDATES -> WhatsNewPanel(state.currentLanguage, model.updates, enabled, actions.onStartTutorial)
			}
			// A file carried over the window lands wherever it is dropped; outside the viewport the page says so.
			if (actions.filesDragging && section != StartSection.GENERATE) DropHighlight(tr("start.drop.overlay", startPresetName(viewModel.startChoices)))
		}
		if (section != StartSection.UPDATES) StartActionBar(section, viewModel, model.layout, files, enabled, state, actions, primary)
	}
}

/** What the section's action will do, set before it runs: the import presets, the canvas's route, the picked file. */
@Composable
internal fun StartDetailsPanel(state: PSD2LiveState, viewModel: PSD2LiveViewModel, actions: PageActions) {
	val model = actions.start
	val enabled = !state.isBusy
	val files = rememberStartFiles(state, viewModel, model.selection)
	if (viewModel.startSection == StartSection.UPDATES) {
		WhatsNewVersions(state.currentLanguage, model.updates)
		return
	}
	Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
		when (viewModel.startSection) {
			StartSection.GENERATE -> PresetInspector(viewModel.startChoices, model.selection, enabled, viewModel::setStartChoices)
			StartSection.LAYOUT -> LayoutDetails()
			StartSection.CMO3 -> Cmo3Details(enabled && state.previewModel != null, actions.onReplaceCmo3)
			StartSection.RECENT -> RecentInspector(files.selectedRecent, enabled, viewModel, actions.onRevealFile)
			StartSection.UPDATES -> {}
		}
	}
}

// ---------------------------------------------------------------------------------------------------------------------
// Generate: the viewport and the recent PSD strip
// ---------------------------------------------------------------------------------------------------------------------

/**
 * The PSD about to be generated, large on a checkerboard like the canvas, or the drop target while none is picked.
 * The whole viewport takes a dropped file; while one is carried over the window it says what releasing will do.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GenerateViewport(
	file: RecentFile?,
	choices: StartPresetChoices,
	enabled: Boolean,
	dragging: Boolean,
	shortcut: String?,
	onOpenPsd: () -> Unit,
	onGenerate: () -> Unit,
	onClear: () -> Unit,
	modifier: Modifier,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val details = file?.let { rememberRecentFileDetails(it) }
	val interaction = remember { MutableInteractionSource() }
	val hovered by interaction.collectIsHoveredAsState()
	Column(modifier) {
		if (file != null) PanelToolbar {
			PanelToolbarTitle(file.name, Modifier.weight(1f, fill = false))
			if (details?.canvasWidth != null && details.canvasHeight != null) {
				Text("${details.canvasWidth} × ${details.canvasHeight}", style = typography.monoSmall.copy(fontSize = 10.sp), color = colors.textMuted)
			}
			Spacer(Modifier.weight(1f))
			PanelToolButton(tr("start.viewport.clear"), showLabel = true, onClick = onClear, enabled = true, tooltip = tr("start.viewport.clear")) {
				IconClose(Modifier.size(8.dp), colors.textMuted)
			}
		}
		BoxWithConstraints(
			Modifier.weight(1f).fillMaxWidth()
				.background(colors.inputBackground)
				.hoverable(interaction)
				// Empty, a click browses at once; holding a PSD, only a double click acts, so a click has nothing to wait for.
				.then(if (file == null) Modifier.clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onOpenPsd)
					else Modifier.selectOrOpen(enabled, onSelect = {}, onOpen = onGenerate))
				.handCursor(enabled),
			contentAlignment = Alignment.Center,
		) {
			val image = details?.thumbnail
			when {
				image != null -> {
					// The picture fitted into the viewport, the checkerboard under the picture only.
					val ratio = image.width.toFloat() / image.height.coerceAtLeast(1)
					val roomW = maxWidth - 56.dp
					val roomH = maxHeight - 64.dp
					val w = minOf(roomW, roomH * ratio).coerceAtLeast(1.dp)
					val h = (w / ratio).coerceAtLeast(1.dp)
					Box(Modifier.size(w, h).drawBehind { drawChecker(Offset.Zero, size, colors.checkerLight, colors.checkerDark, 8.dp.toPx()) }
						.border(0.5.dp, colors.border)) {
						Image(image, contentDescription = file?.name, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
					}
				}
				file != null -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
					IconDocument(Modifier.size(28.dp), colors.textMuted)
					Text(tr("start.viewport.noPreview"), style = typography.caption.copy(fontSize = 11.sp), color = colors.textMuted)
				}
				else -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
					IconDocument(Modifier.size(30.dp), if (hovered && enabled) colors.accent else colors.textMuted)
					Spacer(Modifier.height(2.dp))
					Text(tr("start.generate.drop", startPresetName(choices)), style = typography.body.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium),
						color = colors.textPrimary)
					Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
						Text(tr("start.generate.browse"), style = typography.caption.copy(fontSize = 11.sp),
							color = if (hovered && enabled) colors.accent else colors.textMuted)
						shortcut?.takeIf { it.isNotBlank() }?.let { Text(it, style = typography.monoSmall.copy(fontSize = 10.sp), color = colors.textDisabled) }
					}
				}
			}
			Text(
				tr(if (file != null) "start.viewport.hint.file" else "start.viewport.hint"),
				style = typography.caption.copy(fontSize = 10.5.sp),
				color = colors.textMuted,
				modifier = Modifier.align(Alignment.BottomStart).padding(horizontal = 10.dp, vertical = 8.dp),
			)
			if (dragging) DropHighlight(tr("start.drop.overlay", startPresetName(choices)))
		}
	}
}

/** What a file carried over the page will do on release, over the area it covers. */
@Composable
private fun DropHighlight(text: String) {
	val colors = LocalToolColors.current
	Box(
		Modifier.fillMaxSize().padding(6.dp).clip(RoundedCornerShape(4.dp))
			.background(colors.accent.copy(alpha = 0.10f))
			.drawBehind {
				val inset = 1.dp.toPx()
				drawRoundRect(colors.accent, Offset(inset, inset), Size(size.width - inset * 2, size.height - inset * 2), CornerRadius(4.dp.toPx()),
					style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(7.dp.toPx(), 4.dp.toPx()))))
			},
		contentAlignment = Alignment.Center,
	) {
		Text(text, style = LocalToolTypography.current.body.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium), color = colors.accent,
			modifier = Modifier.padding(16.dp))
	}
}

/**
 * The recent PSDs as a film strip under the generate viewport: a click previews one there, a double click generates it.
 * Its height follows the tiles, set by the slider at its end.
 */
@Composable
private fun RecentPsdStrip(
	files: List<RecentFile>,
	selectedPath: String?,
	tile: Float,
	enabled: Boolean,
	onTile: (Float) -> Unit,
	onSelect: (String) -> Unit,
	actions: RecentFileActions,
	onBrowse: () -> Unit,
) {
	val colors = LocalToolColors.current
	Column(Modifier.fillMaxWidth().background(colors.panelBackground)
		.drawBehind { drawLine(colors.divider, Offset(0f, 0f), Offset(size.width, 0f), 0.5.dp.toPx()) }) {
		Row(Modifier.fillMaxWidth().height(24.dp).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
			SubHeader(tr("start.generate.recent"), null, Modifier.weight(1f))
			CompactSlider(value = tile, onValueChange = onTile, valueRange = StartPageModel.TILE, height = 12.dp, modifier = Modifier.width(72.dp))
		}
		Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 4.dp, end = 4.dp, bottom = 4.dp)) {
			MenuGlide {
				Row {
					files.forEach { file ->
						RecentTile(file, file.path == selectedPath, tile.dp, enabled, onClick = { onSelect(file.path) }, actions)
					}
					AddTile(tile.dp, enabled, onBrowse, if (files.isEmpty()) tr("start.generate.recent.empty") else tr("start.generate.recent.add"))
				}
			}
		}
	}
}

/** The strip's last place, sized like its pictures: another PSD from the file picker. */
@Composable
private fun AddTile(size: Dp, enabled: Boolean, onClick: () -> Unit, label: String) {
	val colors = LocalToolColors.current
	val interaction = remember { MutableInteractionSource() }
	val hovered by interaction.collectIsHoveredAsState()
	val shape = RoundedCornerShape(5.dp)
	Column(
		Modifier.padding(3.dp).glideItem(enabled).clip(shape)
			.hoverable(interaction)
			.clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
			.handCursor(enabled)
			.padding(4.dp),
		horizontalAlignment = Alignment.CenterHorizontally,
	) {
		Box(Modifier.size(size, size * 0.75f).clip(RoundedCornerShape(3.dp)).background(colors.inputBackground), contentAlignment = Alignment.Center) {
			IconAdd(Modifier.size(14.dp), if (hovered && enabled) colors.accent else colors.textMuted)
		}
		Text(label, color = colors.textMuted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
			textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.width(size).padding(top = 3.dp))
	}
}

// ---------------------------------------------------------------------------------------------------------------------
// Layout
// ---------------------------------------------------------------------------------------------------------------------

/**
 * A new Layout project's canvas, set in one place: the canvas drawn to scale, and beside it the common sizes over the
 * width and height they fill in. A size picked from the list or typed shows at once in both.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LayoutContent(layout: LayoutDraft, enabled: Boolean, onCreate: (Int, Int) -> Unit) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	Row(Modifier.fillMaxSize().padding(10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
		CanvasSizePreview(layout.w, layout.h, layout.valid, Modifier.weight(1f).fillMaxHeight())
		Column(Modifier.width(240.dp).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
			SubHeader(tr("start.layout.sizes"), null)
			MenuGlide(Modifier.fillMaxWidth().border(0.5.dp, colors.divider)) { Column(Modifier.fillMaxWidth()) {
				val matched = LayoutCanvasSizes.any { (_, w, h) -> layout.w == w && layout.h == h }
				LayoutCanvasSizes.forEach { (key, w, h) ->
					SizeRow(tr("start.layout.size.$key"), "$w × $h", layout.w == w && layout.h == h, enabled,
						onClick = { layout.set(w, h) }, onDoubleClick = { layout.set(w, h); onCreate(w, h) })
				}
				// What the fields hold while it is none of the above.
				SizeRow(tr("start.layout.size.custom"), if (matched) "" else "${layout.width.trim()} × ${layout.height.trim()}", !matched,
					enabled = false, onClick = {}, onDoubleClick = {})
			} }
			Spacer(Modifier.height(4.dp))
			SubHeader(tr("start.inspector.canvas"), null)
			InspectorFormRow(tr("start.layout.width")) {
				CompactTextField(layout.width, { layout.width = it }, isMono = true, selectAllOnFocus = true, enabled = enabled,
					height = 22.dp, modifier = Modifier.fillMaxWidth())
			}
			InspectorFormRow(tr("start.layout.height")) {
				CompactTextField(layout.height, { layout.height = it }, isMono = true, selectAllOnFocus = true, enabled = enabled,
					height = 22.dp, modifier = Modifier.fillMaxWidth())
			}
			InspectorFormRow(tr("start.layout.pixels")) {
				Row(verticalAlignment = Alignment.CenterVertically) {
					val w = layout.w
					val h = layout.h
					Text(if (w != null && h != null) "%.2f M".format(w.toLong() * h / 1_000_000.0) else "—",
						style = typography.monoSmall.copy(fontSize = 11.sp), color = colors.textPrimary, modifier = Modifier.weight(1f))
					CompactButton(tr("start.layout.swap"), {
						val width = layout.width
						layout.width = layout.height
						layout.height = width
					}, enabled = enabled, height = 22.dp)
				}
			}
			if (!layout.valid) Text(tr("start.layout.invalid"), style = typography.caption.copy(fontSize = 10.5.sp), color = colors.warning)
		}
	}
}

/** A row of the canvas size list: its name, its size muted at the end, the selection behind both. */
@Composable
private fun SizeRow(label: String, size: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit, onDoubleClick: () -> Unit) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val fill by animateColorAsState(selectionFill(selected), tween(Motion.Instant))
	Row(
		Modifier.fillMaxWidth().height(24.dp)
			.glideItem(enabled)
			.background(fill)
			.selectOrOpen(enabled, onClick, onDoubleClick)
			.handCursor(enabled)
			.padding(horizontal = 8.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Text(label, style = typography.body.copy(fontSize = 11.5.sp),
			color = if (selected) colors.selectionText else colors.textPrimary, modifier = Modifier.weight(1f))
		Text(size, style = typography.monoSmall.copy(fontSize = 10.sp), color = if (selected) colors.selectionText else colors.textMuted)
	}
}

/** The way through a Layout project and what its canvas means, beside the canvas being set. */
@Composable
private fun LayoutDetails() {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
		listOf("import", "place", "generate").forEachIndexed { index, step ->
			Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
				Text("${index + 1}", style = typography.monoSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold), color = colors.accent)
				Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
					Text(tr("start.layout.step.$step"), style = typography.body.copy(fontSize = 11.5.sp, fontWeight = FontWeight.Medium), color = colors.textPrimary)
					Text(tr("start.layout.step.$step.desc"), style = typography.caption.copy(fontSize = 10.5.sp, lineHeight = 14.sp), color = colors.textMuted)
				}
			}
		}
		Box(Modifier.fillMaxWidth().height(0.5.dp).background(colors.divider))
		Text(tr("layout.new.hint"), style = typography.caption.copy(fontSize = 10.5.sp, lineHeight = 14.sp), color = colors.textMuted)
	}
}

/** The canvas drawn to scale inside the panel, its size beneath, outlined in warning colour while it is out of range. */
@Composable
private fun CanvasSizePreview(width: Int?, height: Int?, valid: Boolean, modifier: Modifier) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	Box(modifier.clip(RoundedCornerShape(3.dp)).background(colors.inputBackground).border(0.5.dp, colors.divider, RoundedCornerShape(3.dp))) {
		Box(Modifier.fillMaxSize().padding(start = 28.dp, end = 28.dp, top = 24.dp, bottom = 40.dp).drawBehind {
			val w = (width ?: 0).coerceAtLeast(1).toFloat()
			val h = (height ?: 0).coerceAtLeast(1).toFloat()
			val scale = minOf(size.width / w, size.height / h)
			val rect = Size(w * scale, h * scale)
			val origin = Offset((size.width - rect.width) / 2f, (size.height - rect.height) / 2f)
			drawChecker(origin, rect, colors.checkerLight, colors.checkerDark, 8.dp.toPx())
			drawRect(if (valid) colors.accent else colors.warning, origin, rect, style = Stroke(1.dp.toPx()))
		})
		Text(
			if (width != null && height != null) "$width × $height" else "—",
			style = typography.monoSmall.copy(fontSize = 11.sp),
			color = if (valid) colors.textPrimary else colors.warning,
			modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp),
		)
	}
}

private fun DrawScope.drawChecker(origin: Offset, area: Size, light: Color, dark: Color, cell: Float) {
	drawRect(light, origin, area)
	var y = 0f
	var row = 0
	while (y < area.height) {
		var x = if (row % 2 == 0) 0f else cell
		while (x < area.width) {
			drawRect(dark, Offset(origin.x + x, origin.y + y), Size(minOf(cell, area.width - x), minOf(cell, area.height - y)))
			x += cell * 2
		}
		y += cell
		row++
	}
}

// ---------------------------------------------------------------------------------------------------------------------
// CMO3
// ---------------------------------------------------------------------------------------------------------------------

@Composable
private fun Cmo3Content(enabled: Boolean, onOpenCmo3: (() -> Unit)?) {
	ViewportPrompt(
		title = tr("start.cmo3.choose"),
		subtitle = tr("start.cmo3.choose.desc"),
		enabled = enabled && onOpenCmo3 != null,
		onClick = { onOpenCmo3?.invoke() },
		modifier = Modifier.fillMaxSize(),
	) { IconDocument(Modifier.size(30.dp), it) }
}

/**
 * What a Cubism import brings and what it leaves out; beneath, the other way in: replacing the open model's objects
 * by ID, offered here while a model is open.
 */
@Composable
private fun Cmo3Details(canReplace: Boolean, onReplaceCmo3: (() -> Unit)?) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
		SubHeaderRow(tr("start.cmo3.keeps"))
		listOf("parameters", "deformers", "textures", "physics", "presets").forEach { key ->
			Row(Modifier.fillMaxWidth().height(22.dp).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(8.dp)) {
				if (key == "presets") IconClose(Modifier.size(8.dp), colors.textMuted) else IconCheck(Modifier.size(10.dp), colors.accent)
				Text(tr("start.cmo3.keeps.$key"), fontSize = 11.sp, color = if (key == "presets") colors.textMuted else colors.textPrimary)
			}
		}
		Spacer(Modifier.height(8.dp))
		Box(Modifier.fillMaxWidth().height(0.5.dp).background(colors.divider))
		Spacer(Modifier.height(6.dp))
		SubHeaderRow(tr("start.cmo3.replace"))
		Text(tr("start.cmo3.replace.desc"), style = typography.caption.copy(fontSize = 10.5.sp, lineHeight = 14.sp), color = colors.textMuted,
			modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
		CompactButton(tr("start.cmo3.replace.action"), { onReplaceCmo3?.invoke() }, enabled = canReplace && onReplaceCmo3 != null, height = 22.dp,
			modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
		if (!canReplace) Text(tr("start.cmo3.replace.none"), style = typography.caption.copy(fontSize = 10.sp), color = colors.textDisabled,
			modifier = Modifier.padding(horizontal = 10.dp))
	}
}

@Composable
private fun SubHeaderRow(title: String) {
	Text(title, style = LocalToolTypography.current.caption.copy(fontSize = 10.sp), color = LocalToolColors.current.textMuted,
		modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp))
}

// ---------------------------------------------------------------------------------------------------------------------
// Recent
// ---------------------------------------------------------------------------------------------------------------------

/**
 * The recent files as the materials panel shows its images: a panel toolbar with the search and the kind switches,
 * then a grid of thumbnails or a list. A click selects, a double click opens. The start page opens here.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecentBrowser(
	recent: List<RecentFile>?,
	model: StartPageModel,
	enabled: Boolean,
	actions: RecentFileActions,
) {
	val selection = model.selection
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val shown = recent.orEmpty().filter { file ->
		(if (file.kind == RecentFileKind.PROJECT) selection.showProjects else selection.showPsds) &&
			(selection.recentFilter.isBlank() || file.name.contains(selection.recentFilter.trim(), ignoreCase = true))
	}
	Column(Modifier.fillMaxSize()) {
		val kinds = listOf(tr("canvas.start.kind.project"), tr("canvas.start.kind.psd"))
		PanelToolbar(labels = kinds, iconCount = 4, search = PanelSearch(selection.recentFilter, { selection.recentFilter = it }, tr("start.recent.filter")),
			reservedWidth = 96.dp) { labelsShown ->
			PanelToolButton(kinds[0], showLabel = labelsShown > 0, onClick = { selection.showProjects = !selection.showProjects },
				enabled = true, tooltip = tr("start.recent.showProjects"), active = selection.showProjects) {
				IconFolder(Modifier.size(12.dp), if (selection.showProjects) colors.accent else colors.textMuted)
			}
			PanelToolButton(kinds[1], showLabel = labelsShown > 1, onClick = { selection.showPsds = !selection.showPsds },
				enabled = true, tooltip = tr("start.recent.showPsds"), active = selection.showPsds) {
				IconDocument(Modifier.size(12.dp), if (selection.showPsds) colors.accent else colors.textMuted)
			}
			Spacer(Modifier.weight(1f))
			if (selection.recentGrid) CompactSlider(value = model.card, onValueChange = { model.card = it; model.saveSizes() },
				valueRange = StartPageModel.CARD, height = 12.dp, modifier = Modifier.width(72.dp))
			PanelToolButton(tr("start.recent.grid"), showLabel = false, onClick = { selection.recentGrid = true }, enabled = true,
				tooltip = tr("start.recent.grid"), active = selection.recentGrid) { GridGlyph(colors.textPrimary) }
			PanelToolButton(tr("start.recent.list"), showLabel = false, onClick = { selection.recentGrid = false }, enabled = true,
				tooltip = tr("start.recent.list"), active = !selection.recentGrid) { ListGlyph(colors.textPrimary) }
		}
		when {
			// Nothing while the check runs, rather than "none" for a moment.
			recent == null -> Spacer(Modifier.weight(1f))
			shown.isEmpty() -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
				Text(tr(if (recent.isEmpty()) "canvas.start.recent.empty" else "start.recent.noMatch"),
					style = typography.caption.copy(fontSize = 11.sp), color = colors.textDisabled)
			}
			// One highlight glides to the item under the pointer, as in the mode menu; it scrolls with the items.
			selection.recentGrid -> Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(4.dp)) {
				MenuGlide(Modifier.fillMaxWidth()) {
					FlowRow(Modifier.fillMaxWidth()) {
						shown.forEach { file ->
							RecentTile(file, file.path == selection.recentPath, model.card.dp, enabled, onClick = { selection.recentPath = file.path }, actions)
						}
					}
				}
			}
			else -> Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
				MenuGlide(Modifier.fillMaxWidth()) {
					Column(Modifier.fillMaxWidth()) {
						shown.forEach { file ->
							RecentRow(file, file.path == selection.recentPath, enabled, onClick = { selection.recentPath = file.path }, actions)
						}
					}
				}
			}
		}
	}
}

/**
 * A recent file as the materials grid draws an image: its picture, its name under it, the selection behind both.
 * Hovered, the gliding highlight comes to it and its edge lights; a right click opens its menu.
 */
@Composable
private fun RecentTile(file: RecentFile, selected: Boolean, size: Dp, enabled: Boolean, onClick: () -> Unit, actions: RecentFileActions) {
	val colors = LocalToolColors.current
	val details = rememberRecentFileDetails(file)
	val interaction = remember { MutableInteractionSource() }
	val hovered by interaction.collectIsHoveredAsState()
	val shape = RoundedCornerShape(5.dp)
	val fill by animateColorAsState(selectionFill(selected), tween(Motion.Instant))
	val edge by animateColorAsState(when {
		selected -> colors.accent.copy(alpha = 0.7f)
		hovered && enabled -> colors.accent.copy(alpha = 0.35f)
		else -> Color.Transparent
	}, tween(Motion.Instant))
	RecentFileMenuHost(file, enabled, onClick, actions) {
		Column(
			Modifier.padding(3.dp).glideItem(enabled).clip(shape)
				.background(fill)
				.border(0.5.dp, edge, shape)
				.hoverable(interaction, enabled)
				.selectOrOpen(enabled, onClick) { actions.open(file) }
				.handCursor(enabled)
				.padding(4.dp),
			horizontalAlignment = Alignment.CenterHorizontally,
		) {
			Thumbnail(details?.thumbnail, file.kind, Modifier.size(size, size * 0.75f).clip(RoundedCornerShape(3.dp)))
			Text(file.name, color = if (selected) colors.selectionText else colors.textPrimary, fontSize = 10.sp, maxLines = 1,
				overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
				modifier = Modifier.width(size).padding(top = 3.dp))
		}
	}
}

/**
 * A recent file as the materials list draws an image: a small picture, the name, then its facts in muted columns.
 * Hovered, the gliding highlight comes to it; a right click opens its menu.
 */
@Composable
private fun RecentRow(file: RecentFile, selected: Boolean, enabled: Boolean, onClick: () -> Unit, actions: RecentFileActions) {
	val colors = LocalToolColors.current
	val details = rememberRecentFileDetails(file)
	val muted = if (selected) colors.selectionText.copy(alpha = 0.75f) else colors.textMuted
	val fill by animateColorAsState(selectionFill(selected), tween(Motion.Instant))
	RecentFileMenuHost(file, enabled, onClick, actions) { RecentRowContent(file, selected, details, muted,
		Modifier.fillMaxWidth().height(30.dp)
			.glideItem(enabled)
			.background(fill)
			.selectOrOpen(enabled, onClick) { actions.open(file) }
			.handCursor(enabled)
			.padding(horizontal = 6.dp)) }
}

@Composable
private fun RecentRowContent(file: RecentFile, selected: Boolean, details: io.github.psd2live.ui.state.RecentFileDetails?, muted: Color, modifier: Modifier) {
	val colors = LocalToolColors.current
	Row(
		modifier,
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(6.dp),
	) {
		Thumbnail(details?.thumbnail, file.kind, Modifier.size(24.dp).clip(RoundedCornerShape(2.dp)))
		Text(file.name, color = if (selected) colors.selectionText else colors.textPrimary, fontSize = 11.sp, maxLines = 1,
			overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(0.45f))
		Text(file.directory, color = muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(0.55f))
		Text(kindLabel(file.kind), color = muted, fontSize = 10.sp)
		details?.canvasWidth?.let { Text("$it×${details.canvasHeight}", color = muted, fontSize = 10.sp) }
		Text(details?.let { DateFormat.format(Instant.ofEpochMilli(it.modifiedMillis)) }.orEmpty(), color = muted, fontSize = 10.sp)
		Text(details?.let { formatBytes(it.sizeBytes) }.orEmpty(), color = muted, fontSize = 10.sp,
			modifier = Modifier.width(56.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
	}
}

/** A file's picture on a checkerboard, or its kind's icon while it has none. */
@Composable
private fun Thumbnail(image: ImageBitmap?, kind: RecentFileKind, modifier: Modifier) {
	val colors = LocalToolColors.current
	Box(
		modifier.drawBehind {
			drawChecker(Offset.Zero, size, colors.checkerLight, colors.checkerDark, 5.dp.toPx())
		},
		contentAlignment = Alignment.Center,
	) {
		if (image != null) Image(image, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(2.dp))
		else when (kind) {
			RecentFileKind.PROJECT -> IconFolder(modifier = Modifier.size(16.dp), tint = colors.warning.copy(alpha = 0.8f))
			RecentFileKind.PSD -> IconDocument(Modifier.size(16.dp), colors.accent.copy(alpha = 0.85f))
		}
	}
}

/** The fill behind a pickable item: the selection, or nothing; hover is the gliding highlight under it. */
@Composable
private fun selectionFill(selected: Boolean): Color = if (selected) LocalToolColors.current.selection else Color.Transparent

/** What a recent file's double click and menu do with it. */
private class RecentFileActions(
	val open: (RecentFile) -> Unit,
	val reveal: (RecentFile) -> Unit,
	val forget: (RecentFile) -> Unit,
)

private fun recentFileActions(viewModel: PSD2LiveViewModel, actions: PageActions) = RecentFileActions(
	open = { viewModel.openRecentFile(it.path) },
	reveal = { actions.onRevealFile(it.directory) },
	forget = { viewModel.forgetRecentFile(it.path) },
)

/**
 * Holds a recent file's [content] and its right-click menu: a right press selects the file through [onSelect] and
 * opens the menu at the pointer, which opens or generates it, shows its folder, or forgets it.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun RecentFileMenuHost(file: RecentFile, enabled: Boolean, onSelect: () -> Unit, actions: RecentFileActions, content: @Composable () -> Unit) {
	var menuAt by remember { mutableStateOf<Offset?>(null) }
	Box(Modifier.onPointerEvent(PointerEventType.Press) { event ->
		if (event.button == PointerButton.Secondary) {
			onSelect()
			menuAt = event.changes.firstOrNull()?.position
		}
	}) {
		content()
		val close = { menuAt = null }
		PressMenu(menuAt != null, menuAt ?: Offset.Zero, onDismissRequest = close) {
			GlidingColumn(Modifier.width(200.dp), 4, highlights = { i -> i == 1 || (i != 2 && enabled) }) { i ->
				when (i) {
					0 -> FloatingMenuRow(tr(if (file.kind == RecentFileKind.PSD) "start.action.recent.generate" else "start.action.recent.open"),
						{ close(); actions.open(file) }, enabled = enabled)
					1 -> FloatingMenuRow(tr("start.recent.reveal"), { close(); actions.reveal(file) })
					2 -> FloatingMenuDivider()
					else -> FloatingMenuRow(tr("start.recent.forget"), { close(); actions.forget(file) }, enabled = enabled)
				}
			}
		}
	}
}

private fun kindLabel(kind: RecentFileKind) = tr(when (kind) {
	RecentFileKind.PROJECT -> "canvas.start.kind.project"
	RecentFileKind.PSD -> "canvas.start.kind.psd"
})

private fun formatBytes(bytes: Long): String = when {
	bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
	bytes >= 1L shl 20 -> "%.1f MB".format(bytes / (1L shl 20).toDouble())
	bytes >= 1L shl 10 -> "%.0f KB".format(bytes / (1L shl 10).toDouble())
	else -> "$bytes B"
}

// ---------------------------------------------------------------------------------------------------------------------
// Inspector
// ---------------------------------------------------------------------------------------------------------------------

/**
 * The presets a PSD import applies: the quick set picked from a list, then every value grouped as the model presets
 * panel groups them. Changing a value away from every set shows "Custom".
 */
@Composable
private fun PresetInspector(choices: StartPresetChoices, selection: StartSelection, enabled: Boolean, onChange: (StartPresetChoices) -> Unit) {
	PresetPicker(choices, enabled, onChange)
    choices.modifiers?.let { mode ->
        InspectorNote(tr(if (mode == io.github.psd2live.application.WorkspaceInitialModifiers.EMPTY)
            "modifier.start.empty.detail" else "modifier.start.recommended.detail"))
        return
    }
	fun group(key: String, title: String, summary: String, content: @Composable ColumnScope.() -> Unit): @Composable () -> Unit = {
		val open = selection.open[key] ?: true
		PanelSectionRow(title, open, { selection.open[key] = !open }, icon = null, trailing = {
			Text(summary, fontSize = 10.sp, color = LocalToolColors.current.textMuted, maxLines = 1)
		})
		if (open) Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(3.dp), content = content)
	}
	group("rig", tr("settings.group.rigging"), "%.2f× / %.2f×".format(choices.headStrength, choices.bodyStrength)) {
		StrengthRow(tr("settings.headStrength"), choices.headStrength, enabled) { onChange(choices.copy(headStrength = it)) }
		StrengthRow(tr("settings.bodyStrength"), choices.bodyStrength, enabled) { onChange(choices.copy(bodyStrength = it)) }
		CheckRow(tr("model.deformer.featureDisplacement"), choices.featureDisplacement, enabled) { onChange(choices.copy(featureDisplacement = it)) }
		CheckRow(tr("mouth.outline"), choices.mouthOutline, enabled) { onChange(choices.copy(mouthOutline = it)) }
	}()
	group("motions", tr("settings.group.motions"), onOff(choices.motionBasic || choices.motionSkeleton)) {
		CheckRow(tr("settings.motion.basic"), choices.motionBasic, enabled) { onChange(choices.copy(motionBasic = it)) }
		CheckRow(tr("settings.motion.skeleton"), choices.motionSkeleton, enabled) { onChange(choices.copy(motionSkeleton = it)) }
	}()
	group("simulation", tr("settings.group.simulation"), hairLabel(choices.frontHair, choices.backHair)) {
		HairRow(tr("presets.frontHair"), choices.frontHair, enabled) { onChange(choices.copy(frontHair = it)) }
		HairRow(tr("presets.backHair"), choices.backHair, enabled) { onChange(choices.copy(backHair = it)) }
		CheckRow(tr("presets.clothing"), choices.clothing, enabled) { onChange(choices.copy(clothing = it)) }
		CheckRow(tr("export.physics.eyeJelly"), choices.eyeJelly, enabled) { onChange(choices.copy(eyeJelly = it)) }
	}()
	InspectorNote(tr("modifier.start.shortcut.detail"))
}

/** The quick sets as a list to pick from, on the one the values match, or on "Custom" while none does. */
@Composable
private fun PresetPicker(choices: StartPresetChoices, enabled: Boolean, onPick: (StartPresetChoices) -> Unit) {
	val matched = matchedPreset(choices)
	InspectorFormRow(tr("start.quick"), Modifier.padding(top = 8.dp, bottom = 6.dp, end = 8.dp)) {
		CompactDropdown(
			items = if (matched == null) StartQuickPreset.entries + null else StartQuickPreset.entries,
			selectedItem = matched,
			onItemSelected = { it?.let { preset -> onPick(preset.choices) } },
			itemLabel = { it?.let { preset -> tr(preset.key) } ?: tr("start.quick.custom") },
			itemEnabled = { it != null },
			enabled = enabled,
			height = 22.dp,
			modifier = Modifier.fillMaxWidth(),
		)
	}
}

private fun hairLabel(front: HairMode, back: HairMode): String {
	val mode = when {
		front == HairMode.SIMULATION || back == HairMode.SIMULATION -> HairMode.SIMULATION
		front == HairMode.CLASSIC || back == HairMode.CLASSIC -> HairMode.CLASSIC
		else -> HairMode.OFF
	}
	return tr(mode.key)
}

private fun onOff(on: Boolean) = tr(if (on) "start.on" else "start.off")

@Composable
private fun StrengthRow(label: String, value: Float, enabled: Boolean, onChange: (Float) -> Unit) {
	InspectorFormRow(label, Modifier.height(22.dp).padding(end = 8.dp)) {
		Row(verticalAlignment = Alignment.CenterVertically) {
			CompactSlider(value = value, onValueChange = { onChange((it * 20f).roundToInt() / 20f) }, valueRange = 0f..4f,
				enabled = enabled, height = 14.dp, modifier = Modifier.weight(1f))
			Text("%.2f×".format(value), style = LocalToolTypography.current.monoSmall, color = LocalToolColors.current.textPrimary,
				modifier = Modifier.width(40.dp).padding(start = 6.dp))
		}
	}
}

@Composable
private fun CheckRow(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
	InspectorFormRow(label, Modifier.height(22.dp).padding(end = 8.dp)) {
		CompactCheckbox(checked, onChange, enabled = enabled)
	}
}

@Composable
private fun HairRow(label: String, mode: HairMode, enabled: Boolean, onChange: (HairMode) -> Unit) {
	InspectorFormRow(label, Modifier.height(24.dp).padding(end = 8.dp)) {
		CompactDropdown(items = HairMode.entries, selectedItem = mode, onItemSelected = onChange, itemLabel = { tr(it.key) },
			enabled = enabled, height = 20.dp, modifier = Modifier.width(120.dp))
	}
}

@Composable
private fun RecentInspector(file: RecentFile?, enabled: Boolean, viewModel: PSD2LiveViewModel, onRevealFile: (String) -> Unit) {
	if (file == null) {
		InspectorNote(tr("start.recent.pick"))
		return
	}
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val details = rememberRecentFileDetails(file)
	Column(Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
		Thumbnail(details?.thumbnail, file.kind, Modifier.fillMaxWidth().height(180.dp).border(0.5.dp, colors.divider))
		InspectorFormRow(tr("start.recent.column.kind")) { Text(kindLabel(file.kind), fontSize = 11.sp, color = colors.textPrimary) }
		if (details?.canvasWidth != null && details.canvasHeight != null) InspectorFormRow(tr("start.recent.canvas")) {
			Text("${details.canvasWidth} × ${details.canvasHeight}", style = typography.monoSmall, color = colors.textPrimary)
		}
		InspectorFormRow(tr("start.recent.column.modified")) {
			Text(details?.let { DateFormat.format(Instant.ofEpochMilli(it.modifiedMillis)) }.orEmpty(), style = typography.monoSmall, color = colors.textPrimary)
		}
		InspectorFormRow(tr("start.recent.column.size")) {
			Text(details?.let { formatBytes(it.sizeBytes) }.orEmpty(), style = typography.monoSmall, color = colors.textPrimary)
		}
		InspectorFormRow(tr("start.recent.column.location")) {
			Text(file.directory, fontSize = 10.5.sp, color = colors.textMuted, maxLines = 3, overflow = TextOverflow.Ellipsis)
		}
		Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
			CompactButton(tr("start.recent.reveal"), { onRevealFile(file.directory) }, height = 22.dp)
			CompactButton(tr("start.recent.forget"), { viewModel.forgetRecentFile(file.path) }, enabled = enabled, height = 22.dp)
		}
	}
}

@Composable
private fun InspectorNote(text: String) {
	Text(text, style = LocalToolTypography.current.caption.copy(fontSize = 10.5.sp, lineHeight = 14.sp),
		color = LocalToolColors.current.textMuted, modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp))
}

// ---------------------------------------------------------------------------------------------------------------------
// Action bar
// ---------------------------------------------------------------------------------------------------------------------

/** The bar under the main panel: what the section's action will do on the left, the action itself, solid, on the right. */
@Composable
private fun StartActionBar(
	section: StartSection,
	viewModel: PSD2LiveViewModel,
	layout: LayoutDraft,
	files: StartFiles,
	enabled: Boolean,
	state: PSD2LiveState,
	actions: PageActions,
	primary: () -> Unit,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val preset = startPresetName(viewModel.startChoices)
	val selectedPsd = files.selectedPsd
	val selectedRecent = files.selectedRecent
	val hint = when (section) {
		StartSection.GENERATE -> selectedPsd?.let { tr("start.action.generate.file", it.name, preset) } ?: tr("start.action.generate.hint", preset)
		StartSection.LAYOUT -> if (layout.valid) tr("start.action.layout.hint", layout.w!!, layout.h!!) else tr("start.layout.invalid")
		StartSection.CMO3 -> tr("start.action.cmo3.hint")
		StartSection.RECENT -> selectedRecent?.path ?: tr("start.recent.pick")
		StartSection.UPDATES -> ""
	}
	Row(
		Modifier.fillMaxWidth().height(36.dp)
			.background(colors.panelElevated)
			.drawBehind { drawLine(colors.divider, Offset(0f, 0f), Offset(size.width, 0f), 0.5.dp.toPx()) }
			.padding(horizontal = 10.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(10.dp),
	) {
		Text(hint, style = typography.body.copy(fontSize = 11.sp), color = colors.textMuted, maxLines = 1,
			overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
		Text("Enter", style = typography.monoSmall.copy(fontSize = 10.sp), color = colors.textDisabled)
		when (section) {
			StartSection.GENERATE -> GenerateButton(state, viewModel, selectedPsd != null, enabled && (selectedPsd != null || actions.onOpenPsd != null), primary)
			StartSection.LAYOUT -> AccentButton(tr("start.action.layout"), primary, enabled = enabled && layout.valid, emphasis = true) {
				WorkspacePresetIcon(WorkspacePreset.LAYOUT, it, Modifier.size(12.dp))
			}
			StartSection.CMO3 -> AccentButton(tr("start.action.cmo3"), primary, enabled = enabled && actions.onOpenCmo3 != null, emphasis = true) {
				IconDocument(Modifier.size(12.dp), it)
			}
			StartSection.RECENT -> AccentButton(
				tr(if (selectedRecent?.kind == RecentFileKind.PSD) "start.action.recent.generate" else "start.action.recent.open"),
				primary, enabled = enabled && selectedRecent != null, emphasis = true,
			) { IconFolder(modifier = Modifier.size(12.dp), tint = it) }
			StartSection.UPDATES -> {}
		}
	}
}

/** Generate: the body generates the previewed PSD or asks for one; the menu switches presets or applies them to the open model. */
@Composable
private fun GenerateButton(state: PSD2LiveState, viewModel: PSD2LiveViewModel, hasFile: Boolean, enabled: Boolean, onGenerate: () -> Unit) {
	var menu by remember { mutableStateOf(false) }
	val matched = matchedPreset(viewModel.startChoices)
	Box {
		AccentSplitButton(
			label = tr(if (hasFile) "start.action.recent.generate" else "start.action.generate"),
			icon = { IconDocument(Modifier.size(12.dp), it) },
			onClick = onGenerate,
			open = menu,
			onMenu = { menu = !menu },
			enabled = enabled,
			menuTooltip = tr("start.action.generate.menu"),
			emphasis = true,
		)
		FloatingMenu(expanded = menu, onDismiss = { menu = false }, width = 240.dp, alignment = Alignment.BottomEnd, rise = true) {
			FloatingMenuSection(tr("start.quick"))
			StartQuickPreset.entries.forEach { preset ->
				FloatingMenuRadio(tr(preset.key), preset == matched, { viewModel.setStartChoices(preset.choices) })
			}
			if (matched == null) FloatingMenuRadio(tr("start.quick.custom"), true, {})
			FloatingMenuDivider()
			FloatingMenuRow(tr("start.action.applyCurrent"), {
				menu = false
				viewModel.applyStartChoicesToCurrent()
			}, enabled = viewModel.startPresetsApplyToCurrent(state))
		}
	}
}

// ---------------------------------------------------------------------------------------------------------------------
// Shared pieces
// ---------------------------------------------------------------------------------------------------------------------

/** An empty viewport that opens a file on click: the generate viewport's look while it has nothing to show. */
@Composable
private fun ViewportPrompt(
	title: String,
	subtitle: String,
	enabled: Boolean,
	onClick: () -> Unit,
	modifier: Modifier,
	icon: @Composable (Color) -> Unit,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val interaction = remember { MutableInteractionSource() }
	val hovered by interaction.collectIsHoveredAsState()
	Column(
		modifier
			.background(colors.inputBackground)
			.hoverable(interaction)
			.clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
			.handCursor(enabled),
		horizontalAlignment = Alignment.CenterHorizontally,
		verticalArrangement = Arrangement.Center,
	) {
		icon(if (hovered && enabled) colors.accent else colors.textMuted)
		Spacer(Modifier.height(8.dp))
		Text(title, style = typography.body.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium), color = colors.textPrimary)
		Spacer(Modifier.height(3.dp))
		Text(subtitle, style = typography.caption.copy(fontSize = 11.sp), color = if (hovered && enabled) colors.accent else colors.textMuted)
	}
}

@Composable
private fun SubHeader(title: String, hint: String?, modifier: Modifier = Modifier.fillMaxWidth()) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	Row(modifier, verticalAlignment = Alignment.CenterVertically) {
		Text(title, style = typography.body.copy(fontSize = 11.sp, fontWeight = FontWeight.Medium), color = colors.textPrimary, modifier = Modifier.weight(1f))
		hint?.let { Text(it, style = typography.caption.copy(fontSize = 10.sp), color = colors.textMuted) }
	}
}

/** What the canvas shows with nothing open: the drop hint and the way back to the start page. */
@Composable
internal fun EmptyCanvasHint(enabled: Boolean, onShowStart: () -> Unit, modifier: Modifier = Modifier) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	Column(
		modifier.padding(16.dp),
		horizontalAlignment = Alignment.CenterHorizontally,
		verticalArrangement = Arrangement.spacedBy(10.dp),
	) {
		Text(tr("start.page.emptyCanvas"), style = typography.caption.copy(fontSize = 11.sp), color = colors.textMuted)
		CompactButton(
			text = tr("start.page.goToStart"),
			onClick = onShowStart,
			enabled = enabled,
			leadingIcon = { StartIcon(tint = colors.textPrimary) },
		)
	}
}

/** A route that forks: a start and two ways on, the fork as a point. The start page's tab icon. */
@Composable
internal fun StartIcon(
	modifier: Modifier = Modifier.size(14.dp),
	tint: Color,
) = GridIcon(modifier, tint) {
	val stops = listOf(3.6f to 13f, 14.6f to 3.4f, 14.6f to 13.6f)
	val holes = ComposePath().apply { stops.forEach { (x, y) -> addPath(circlePath(x, y, 2f)) } }
	behind(holes) { stops.forEach { (x, y) -> line(9f, 6.2f, x, y) } }
	stops.forEach { (x, y) ->
		dot(x, y, 2f, soft)
		ring(x, y, 2f)
	}
	dot(9f, 6.2f, 1.7f)
}

@Composable
private fun IconDocument(
	modifier: Modifier = Modifier.size(14.dp),
	tint: Color,
) = GridIcon(modifier, tint) { document() }
