package io.github.psd2live.ui.views

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
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
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.AppVersion
import io.github.psd2live.application.WorkspaceInitialModifiers
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.components.CompactButton
import io.github.psd2live.ui.components.CompactDropdown
import io.github.psd2live.ui.components.CompactSlider
import io.github.psd2live.ui.components.CompactTextField
import io.github.psd2live.ui.components.GridIcon
import io.github.psd2live.ui.components.HelpNav
import io.github.psd2live.ui.components.HelpPanel
import io.github.psd2live.ui.components.IconAdd
import io.github.psd2live.ui.components.IconCheck
import io.github.psd2live.ui.components.IconClose
import io.github.psd2live.ui.components.IconFolder
import io.github.psd2live.ui.components.IconInfo
import io.github.psd2live.ui.components.SettingsNav
import io.github.psd2live.ui.components.SettingsPanel
import io.github.psd2live.ui.components.document
import io.github.psd2live.ui.components.resetAllSettings
import io.github.psd2live.ui.state.AppPage
import io.github.psd2live.ui.state.AppSettings
import io.github.psd2live.ui.state.PSD2LiveState
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.state.RecentFile
import io.github.psd2live.ui.state.RecentFileDetails
import io.github.psd2live.ui.state.RecentFileKind
import io.github.psd2live.ui.state.ShortcutAction
import io.github.psd2live.ui.state.StartSection
import io.github.psd2live.ui.state.WorkspacePreset
import io.github.psd2live.ui.state.rememberExistingRecentFiles
import io.github.psd2live.ui.state.rememberRecentFileDetails
import io.github.psd2live.ui.state.validLayoutCanvasSize
import io.github.psd2live.ui.theme.Corners
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography
import io.github.psd2live.ui.theme.Motion
import io.github.psd2live.ui.theme.Spacing
import io.github.psd2live.ui.theme.handCursor
import io.github.psd2live.ui.theme.selectOrOpen
import io.github.psd2live.ui.views.texture.AccentButton
import io.github.psd2live.ui.views.texture.BarTooltip
import io.github.psd2live.ui.views.texture.FloatingMenu
import io.github.psd2live.ui.views.texture.FloatingMenuDivider
import io.github.psd2live.ui.views.texture.FloatingMenuRow
import io.github.psd2live.ui.views.texture.MenuGlide
import io.github.psd2live.ui.views.texture.glideItem
import io.github.psd2live.ui.views.tooloptions.GlidingColumn
import java.io.File
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
private val ShortDateFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault())

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
		StartSection.UPDATES, StartSection.HELP, StartSection.SETTINGS -> {}
	}
}

internal fun startSectionTitle(section: StartSection) = when (section) {
	StartSection.GENERATE -> tr("psdGuide.title")
	StartSection.LAYOUT -> tr("start.nav.layout")
	StartSection.CMO3 -> tr("start.nav.cmo3")
	StartSection.RECENT -> tr("start.nav.recent")
	StartSection.UPDATES -> tr("start.nav.updates")
	StartSection.HELP -> tr("page.help")
	StartSection.SETTINGS -> tr("page.settings")
}

/** The details panel follows the selected section or file. */
internal fun startDetailsTitle(section: StartSection) = when (section) {
	StartSection.GENERATE -> tr("start.details.file")
	StartSection.LAYOUT -> tr("start.details.layout")
	StartSection.CMO3 -> tr("start.details.cmo3")
	StartSection.RECENT -> tr("start.details.file")
	StartSection.UPDATES -> tr("start.details.versions")
	StartSection.HELP -> tr("page.help")
	StartSection.SETTINGS -> tr("page.settings")
}

// ---------------------------------------------------------------------------------------------------------------------
// Panels
// ---------------------------------------------------------------------------------------------------------------------

/**
 * The start page's compact command & status rail: app version strip, active session return bar when a model is open,
 * grouped workflow commands with keycap shortcuts, and a persistent environment summary footer. When [compact] is true
 * (for Help or Settings, which bring their own secondary navigation), it collapses to an icon rail.
 */
@Composable
internal fun StartNavPanel(state: PSD2LiveState, viewModel: PSD2LiveViewModel, actions: PageActions, compact: Boolean = false) {
	val section = viewModel.startSection
	val enabled = !state.isBusy
	val recent = rememberExistingRecentFiles(state.recentFiles)
	val order = StartSection.entries
	val openShortcut = state.keymap.labelFor(ShortcutAction.OPEN_PROJECT)
	val psdShortcut = state.keymap.labelFor(ShortcutAction.OPEN_PSD)
	val helpShortcut = state.keymap.labelFor(ShortcutAction.OPEN_HELP)
	val settingsShortcut = state.keymap.labelFor(ShortcutAction.OPEN_SETTINGS)

	Column(Modifier.fillMaxSize()) {
		StartBrandStrip(compact)
		if (state.previewModel != null) {
			ActiveSessionStrip(state, viewModel, compact)
		}
		Box(Modifier.weight(1f).fillMaxWidth()) {
			PageNavList(
				onStep = { step -> viewModel.setStartSection(order[(order.indexOf(section) + step).mod(order.size)]) },
			) {
				StartNavGroup(tr("start.nav.group.open"), compact)
				StartNavEntry(
					label = tr("start.nav.openProject"),
					selected = false,
					enabled = enabled && actions.onOpenProject != null,
					compact = compact,
					shortcut = openShortcut,
					onClick = { actions.onOpenProject?.invoke() },
				) { IconFolder(modifier = Modifier.size(13.dp), tint = it) }
				StartNavEntry(
					label = tr("start.nav.recent"),
					selected = section == StartSection.RECENT,
					trailing = recent?.size?.toString(),
					compact = compact,
					onClick = { viewModel.setStartSection(StartSection.RECENT) },
				) { WorkspacePresetIcon(WorkspacePreset.HISTORY, it, Modifier.size(13.dp)) }

				StartNavGroup(tr("start.nav.group.new"), compact)
				StartNavEntry(
					label = tr("psdGuide.title"),
					selected = section == StartSection.GENERATE,
					compact = compact,
					shortcut = psdShortcut,
					onClick = { viewModel.setStartSection(StartSection.GENERATE) },
				) { WorkspacePresetIcon(WorkspacePreset.EDIT, it, Modifier.size(13.dp)) }
				StartNavEntry(
					label = tr("start.nav.layout"),
					selected = section == StartSection.LAYOUT,
					compact = compact,
					onClick = { viewModel.setStartSection(StartSection.LAYOUT) },
				) { WorkspacePresetIcon(WorkspacePreset.LAYOUT, it, Modifier.size(13.dp)) }
				StartNavEntry(
					label = tr("start.nav.cmo3"),
					selected = section == StartSection.CMO3,
					compact = compact,
					onClick = { viewModel.setStartSection(StartSection.CMO3) },
				) { IconDocument(Modifier.size(13.dp), it) }

				StartNavGroup(tr("start.nav.group.learn"), compact)
				StartNavEntry(
					label = tr("start.nav.updates"),
					selected = section == StartSection.UPDATES,
					compact = compact,
					onClick = { viewModel.setStartSection(StartSection.UPDATES) },
				) { UpdatesIcon(Modifier.size(13.dp), it) }
				StartNavEntry(
					label = tr("page.help"),
					selected = section == StartSection.HELP,
					compact = compact,
					shortcut = helpShortcut,
					onClick = { viewModel.setStartSection(StartSection.HELP) },
				) { IconInfo(modifier = Modifier.size(13.dp), tint = it) }
				StartNavEntry(
					label = tr("page.settings"),
					selected = section == StartSection.SETTINGS,
					compact = compact,
					shortcut = settingsShortcut,
					onClick = { viewModel.setStartSection(StartSection.SETTINGS) },
				) { SettingsIcon(it, Modifier.size(13.dp)) }
			}
		}
		StartEnvironmentFooter(state, compact)
	}
}

@Composable
private fun StartNavGroup(title: String, compact: Boolean) {
	if (!compact) {
		PageNavGroup(title)
	} else {
		Box(
			Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 5.dp)
				.height(0.5.dp).background(LocalToolColors.current.divider),
		)
	}
}

@Composable
private fun StartNavEntry(
	label: String,
	selected: Boolean,
	compact: Boolean,
	enabled: Boolean = true,
	trailing: String? = null,
	shortcut: String? = null,
	onClick: () -> Unit,
	icon: @Composable (Color) -> Unit,
) {
	if (!compact) {
		PageNavItem(
			label = label,
			selected = selected,
			trailing = trailing,
			enabled = enabled,
			onClick = onClick,
			trailingContent = shortcut?.takeIf { it.isNotBlank() }?.let { { KeycapBadge(it) } },
			icon = icon,
		)
		return
	}
	val colors = LocalToolColors.current
	val interaction = remember { MutableInteractionSource() }
	val hovered by interaction.collectIsHoveredAsState()
	val tip = buildString {
		append(label)
		if (!shortcut.isNullOrBlank()) append("  ($shortcut)")
	}
	val iconTint = if (selected) colors.selectionText else if (enabled) colors.textMuted else colors.textDisabled
	BarTooltip(tip) {
		Box(
			Modifier.fillMaxWidth().height(28.dp).padding(horizontal = 5.dp, vertical = 1.dp)
				.clip(RoundedCornerShape(Corners.Control))
				.background(when {
					selected -> colors.selection
					hovered && enabled -> colors.controlHover.copy(alpha = 0.45f)
					else -> Color.Transparent
				})
				.hoverable(interaction)
				.clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
				.handCursor(enabled),
			contentAlignment = Alignment.Center,
		) {
			if (selected) {
				Box(
					Modifier.align(Alignment.CenterStart).padding(start = 1.dp)
						.width(2.dp).height(14.dp).clip(RoundedCornerShape(1.dp)).background(colors.accent),
				)
			}
			icon(iconTint)
		}
	}
}

@Composable
private fun UpdatesIcon(modifier: Modifier, tint: Color) {
	GridIcon(modifier, tint) {
		// Spark / release star: four-pointed star with a small accent dot.
		line(9f, 2.5f, 9f, 15.5f)
		line(2.5f, 9f, 15.5f, 9f)
		line(4.8f, 4.8f, 13.2f, 13.2f)
		line(13.2f, 4.8f, 4.8f, 13.2f)
	}
}

/** Flat edge-to-edge software identity strip at the top of the navigation rail. */
@Composable
private fun StartBrandStrip(compact: Boolean) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	if (compact) {
		BarTooltip("PSD2Live v${AppVersion.value}") {
			Box(
				Modifier.fillMaxWidth().height(28.dp)
					.background(colors.panelElevated)
					.drawBehind { drawLine(colors.divider, Offset(0f, size.height), Offset(size.width, size.height), 0.5.dp.toPx()) },
				contentAlignment = Alignment.Center,
			) {
				StartIcon(Modifier.size(14.dp), colors.accent)
			}
		}
		return
	}
	Row(
		Modifier.fillMaxWidth().height(28.dp)
			.background(colors.panelElevated)
			.drawBehind { drawLine(colors.divider, Offset(0f, size.height), Offset(size.width, size.height), 0.5.dp.toPx()) }
			.padding(horizontal = 10.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(6.dp),
	) {
		StartIcon(Modifier.size(13.dp), colors.accent)
		Text("PSD2Live", style = typography.body.copy(fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold), color = colors.textPrimary)
		Spacer(Modifier.weight(1f))
		StatusBadge("v${AppVersion.value}", colors.textMuted, colors.controlBackground)
	}
}

/** Single-row active session return strip when a model is open in the background. */
@Composable
private fun ActiveSessionStrip(state: PSD2LiveState, viewModel: PSD2LiveViewModel, compact: Boolean) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val name = state.projectFile?.let { File(it).name } ?: state.projectSourceName ?: tr("start.session.unsaved")
	if (compact) {
		val interaction = remember { MutableInteractionSource() }
		val hovered by interaction.collectIsHoveredAsState()
		BarTooltip("${tr("start.session.return")}: $name") {
			Box(
				Modifier.fillMaxWidth().height(28.dp)
					.background(if (hovered) colors.highlightContainer.copy(alpha = 0.7f) else colors.highlightContainer.copy(alpha = 0.45f))
					.drawBehind { drawLine(colors.divider, Offset(0f, size.height), Offset(size.width, size.height), 0.5.dp.toPx()) }
					.hoverable(interaction)
					.clickable(interactionSource = interaction, indication = null, onClick = { viewModel.leaveStartPage() })
					.handCursor(),
				contentAlignment = Alignment.Center,
			) {
				Box(Modifier.size(8.dp).clip(CircleShape).background(colors.highlight))
			}
		}
		return
	}
	Row(
		Modifier.fillMaxWidth().height(28.dp)
			.background(colors.highlightContainer.copy(alpha = 0.45f))
			.drawBehind { drawLine(colors.divider, Offset(0f, size.height), Offset(size.width, size.height), 0.5.dp.toPx()) }
			.padding(horizontal = 8.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(6.dp),
	) {
		Box(Modifier.size(6.dp).clip(CircleShape).background(colors.highlight))
		Text(name, style = typography.body.copy(fontSize = 10.5.sp, fontWeight = FontWeight.Medium), color = colors.textPrimary,
			maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
		CompactButton(tr("start.session.return"), { viewModel.leaveStartPage() }, height = 20.dp)
	}
}

/** Compact environment readout at the bottom of the navigation rail. */
@Composable
private fun StartEnvironmentFooter(state: PSD2LiveState, compact: Boolean) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	if (compact) {
		BarTooltip("${tr("start.env.atlas")}: ${state.atlasSize}px") {
			Box(
				Modifier.fillMaxWidth()
					.background(colors.panelElevated)
					.drawBehind { drawLine(colors.divider, Offset(0f, 0f), Offset(size.width, 0f), 0.5.dp.toPx()) }
					.padding(vertical = 6.dp),
				contentAlignment = Alignment.Center,
			) {
				Text("${state.atlasSize}", style = typography.monoSmall.copy(fontSize = 9.sp), color = colors.textMuted)
			}
		}
		return
	}
	Column(
		Modifier.fillMaxWidth()
			.background(colors.panelElevated)
			.drawBehind { drawLine(colors.divider, Offset(0f, 0f), Offset(size.width, 0f), 0.5.dp.toPx()) }
			.padding(horizontal = 10.dp, vertical = 6.dp),
		verticalArrangement = Arrangement.spacedBy(2.dp),
	) {
		Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
			Text(tr("start.env.atlas"), style = typography.caption.copy(fontSize = 10.sp), color = colors.textMuted, modifier = Modifier.weight(1f))
			Text("${state.atlasSize}px", style = typography.monoSmall.copy(fontSize = 10.sp), color = colors.textPrimary)
		}
	}
}

/**
 * Dual-pane Help or Settings content embedded inside the start hub when [StartSection.HELP] or
 * [StartSection.SETTINGS] is active.
 */
@Composable
internal fun StartHubSplitContent(state: PSD2LiveState, viewModel: PSD2LiveViewModel, actions: PageActions, modifier: Modifier = Modifier) {
	val section = viewModel.startSection
	val navTitle = tr(if (section == StartSection.HELP) "page.help" else "page.settings")
	val bodyTitle = if (section == StartSection.HELP) helpContentTitle(viewModel) else tr(viewModel.settingsTopic.labelKey)
	Row(modifier.fillMaxSize()) {
		StartHubPanelFrame(navTitle, Modifier.weight(0.22f).fillMaxHeight()) {
			if (section == StartSection.HELP) {
				HelpNav(viewModel.helpTab, viewModel.helpPage, viewModel::setHelpTab, viewModel::showHelpPage)
			} else {
				SettingsNav(viewModel.settingsTopic, viewModel::setSettingsTopic, onResetAll = { resetAllSettings(viewModel) })
			}
		}
		Spacer(Modifier.width(Spacing.Gap))
		StartHubPanelFrame(bodyTitle, Modifier.weight(0.78f).fillMaxHeight()) {
			if (section == StartSection.HELP) {
				HelpPanel(
					viewModel.helpTab,
					viewModel.helpPage,
					state.keymap,
					onOpenPage = viewModel::showHelpPage,
					onStartInteractiveTutorial = actions.onStartTutorialPath,
					operationDocs = viewModel.operationDocs,
				)
			} else {
				SettingsPanel(viewModel.settingsTopic, state, viewModel)
			}
		}
	}
}

@Composable
internal fun StartHubPanelFrame(title: String?, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val shape = RoundedCornerShape(Corners.Panel)
	Column(modifier.clip(shape).background(colors.panelBackground).border(1.dp, colors.divider, shape)) {
		if (title != null) {
			Row(
				Modifier.fillMaxWidth().height(22.dp)
					.background(colors.panelElevated)
					.drawBehind { drawLine(colors.divider, Offset(0f, size.height), Offset(size.width, size.height), 0.5.dp.toPx()) }
					.padding(horizontal = 8.dp),
				verticalAlignment = Alignment.CenterVertically,
			) {
				Text(title, style = typography.caption.copy(fontSize = 10.5.sp, fontWeight = FontWeight.Medium), color = colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
			}
		}
		Box(Modifier.weight(1f).fillMaxWidth()) { content() }
	}
}

/**
 * The main workbench: in RECENT mode, the full-bleed recent table/grid (or input-routing table when empty).
 * PSD, Layout and CMO3 each have a dedicated viewport.
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
				StartSection.LAYOUT -> Column(Modifier.fillMaxSize()) {
					PipelineStepsBar(
						steps = listOf("01" to tr("start.pipeline.layout.1"), "02" to tr("start.pipeline.layout.2"), "03" to tr("start.pipeline.layout.3")),
						activeIndex = 0,
					)
					LayoutContent(model.layout, enabled, Modifier.weight(1f).fillMaxWidth()) { w, h -> viewModel.createLayoutProject(w, h) }
				}
				StartSection.CMO3 -> Column(Modifier.fillMaxSize()) {
					PipelineStepsBar(
						steps = listOf("01" to tr("start.pipeline.cmo3.1"), "02" to tr("start.pipeline.cmo3.2"), "03" to tr("start.pipeline.cmo3.3")),
						activeIndex = 0,
					)
					Cmo3Content(enabled, actions.onOpenCmo3, Modifier.weight(1f).fillMaxWidth())
				}
				StartSection.RECENT -> RecentBrowser(
					recent = files.recent,
					model = model,
					enabled = enabled,
					actions = recentFileActions(viewModel, actions),
					onGenerate = { viewModel.setStartSection(StartSection.GENERATE) },
					onLayout = { viewModel.setStartSection(StartSection.LAYOUT) },
					onCmo3 = { viewModel.setStartSection(StartSection.CMO3) },
					modifier = Modifier.fillMaxSize(),
				)
				StartSection.UPDATES -> WhatsNewPanel(state.currentLanguage, model.updates, enabled, actions.onStartTutorial)
				StartSection.HELP, StartSection.SETTINGS -> {}
			}
			// A file carried over the window lands wherever it is dropped; outside the viewport the page says so.
			if (actions.filesDragging && section != StartSection.GENERATE) DropHighlight(tr("psdGuide.start.drop"))
		}
		if (section != StartSection.UPDATES && section != StartSection.HELP && section != StartSection.SETTINGS) {
			StartActionBar(section, model.layout, files, enabled, actions, primary)
		}
	}
}

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
			StartSection.GENERATE -> if (files.selectedPsd == null) InspectorNote(tr("psdGuide.start.details"))
                else RecentInspector(files.selectedPsd, enabled, viewModel, actions.onRevealFile)
			StartSection.LAYOUT -> LayoutDetails()
			StartSection.CMO3 -> Cmo3Details(enabled && state.previewModel != null, actions.onReplaceCmo3)
			StartSection.RECENT -> RecentInspector(
				file = files.selectedRecent,
				enabled = enabled,
				viewModel = viewModel,
				onRevealFile = actions.onRevealFile,
			)
			StartSection.UPDATES, StartSection.HELP, StartSection.SETTINGS -> {}
		}
	}
}

// ---------------------------------------------------------------------------------------------------------------------
// Generate: the viewport and the recent PSD strip
// ---------------------------------------------------------------------------------------------------------------------

/**
 * The PSD about to be generated, large on a checkerboard like the canvas, or the drop target while none is picked.
 * Selected PSD metadata sits as a corner HUD overlay directly over the viewport.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GenerateViewport(
	file: RecentFile?,
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
	BoxWithConstraints(
		modifier
			.background(colors.inputBackground)
			.hoverable(interaction)
			.drawBehind { drawViewportRegistrationMarks(colors.borderHover.copy(alpha = if (hovered && enabled) 0.85f else 0.45f)) }
			// Empty, a click browses at once; holding a PSD, only a double click acts, so a click has nothing to wait for.
			.then(if (file == null) Modifier.clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onOpenPsd)
				else Modifier.selectOrOpen(enabled, onSelect = {}, onOpen = onGenerate))
			.handCursor(enabled),
		contentAlignment = Alignment.Center,
	) {
		val image = details?.thumbnail
		when {
			image != null -> {
				val ratio = image.width.toFloat() / image.height.coerceAtLeast(1)
				val roomW = maxWidth - 56.dp
				val roomH = maxHeight - 72.dp
				val w = minOf(roomW, roomH * ratio).coerceAtLeast(1.dp)
				val h = (w / ratio).coerceAtLeast(1.dp)
				Box(Modifier.size(w, h)) {
					Image(image, contentDescription = file.name, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
				}
			}
			file != null -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
				IconDocument(Modifier.size(28.dp), colors.textMuted)
				Text(tr("start.viewport.noPreview"), style = typography.caption.copy(fontSize = 11.sp), color = colors.textMuted)
			}
			else -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
				IconDocument(Modifier.size(28.dp), if (hovered && enabled) colors.accent else colors.textMuted)
				Text(tr("psdGuide.start.hint"),
					style = typography.body.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium), color = colors.textPrimary)
				Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
					Text(tr("start.generate.browse"), style = typography.caption.copy(fontSize = 11.sp),
						color = if (hovered && enabled) colors.accent else colors.textMuted)
					shortcut?.takeIf { it.isNotBlank() }?.let { KeycapBadge(it) }
				}
			}
		}
		if (file != null) {
			// Corner HUD overlay for the active PSD
			Row(
				Modifier.align(Alignment.TopStart).padding(10.dp)
					.background(colors.windowBackground.copy(alpha = 0.85f))
					.border(0.5.dp, colors.border)
					.padding(horizontal = 8.dp, vertical = 4.dp),
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(8.dp),
			) {
				Text(file.name, style = typography.body.copy(fontSize = 11.sp, fontWeight = FontWeight.Medium), color = colors.textPrimary)
				if (details?.canvasWidth != null && details.canvasHeight != null) {
					Text("${details.canvasWidth}×${details.canvasHeight}", style = typography.monoSmall.copy(fontSize = 10.sp), color = colors.textMuted)
				}
				details?.let { Text(formatBytes(it.sizeBytes), style = typography.monoSmall.copy(fontSize = 10.sp), color = colors.textMuted) }
			}
			Row(Modifier.align(Alignment.TopEnd).padding(10.dp)) {
				CompactButton(tr("start.viewport.clear"), onClear, height = 22.dp)
			}
		}
		Text(
			tr(if (file != null) "psdGuide.start.details" else "start.viewport.hint"),
			style = typography.caption.copy(fontSize = 10.5.sp),
			color = colors.textMuted,
			modifier = Modifier.align(Alignment.BottomStart).padding(horizontal = 12.dp, vertical = 8.dp),
		)
		if (dragging) DropHighlight(tr("psdGuide.start.drop"))
	}
}

/** What a file carried over the page will do on release, over the area it covers. */
@Composable
private fun DropHighlight(text: String) {
	val colors = LocalToolColors.current
	Box(
		Modifier.fillMaxSize().padding(6.dp)
			.background(colors.accent.copy(alpha = 0.10f))
			.drawBehind {
				val inset = 1.dp.toPx()
				drawRoundRect(colors.accent, Offset(inset, inset), Size(size.width - inset * 2, size.height - inset * 2), CornerRadius(2.dp.toPx()),
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
			SubHeader(tr("start.generate.recent"), if (files.isNotEmpty()) "${files.size}" else null, Modifier.weight(1f))
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
	Column(
		Modifier.padding(3.dp).glideItem(enabled)
			.hoverable(interaction)
			.clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
			.handCursor(enabled)
			.padding(4.dp),
		horizontalAlignment = Alignment.CenterHorizontally,
	) {
		Box(Modifier.size(size, size * 0.75f).background(colors.inputBackground)
			.border(0.5.dp, if (hovered && enabled) colors.accent.copy(alpha = 0.6f) else colors.divider),
			contentAlignment = Alignment.Center) {
			IconAdd(Modifier.size(14.dp), if (hovered && enabled) colors.accent else colors.textMuted)
		}
		Text(label, color = colors.textMuted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
			textAlign = TextAlign.Center, modifier = Modifier.width(size).padding(top = 3.dp))
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
private fun LayoutContent(layout: LayoutDraft, enabled: Boolean, modifier: Modifier = Modifier, onCreate: (Int, Int) -> Unit) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	Row(modifier.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
		CanvasSizePreview(layout.w, layout.h, layout.valid, Modifier.weight(1f).fillMaxHeight())
		Column(Modifier.width(256.dp).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
			SubHeader(tr("start.layout.sizes"), null)
			MenuGlide(Modifier.fillMaxWidth().border(0.5.dp, colors.divider)) { Column(Modifier.fillMaxWidth()) {
				val matched = LayoutCanvasSizes.any { (_, w, h) -> layout.w == w && layout.h == h }
				LayoutCanvasSizes.forEach { (key, w, h) ->
					SizeRow(tr("start.layout.size.$key"), formatAspectRatio(w, h), "$w × $h", layout.w == w && layout.h == h, enabled,
						onClick = { layout.set(w, h) }, onDoubleClick = { layout.set(w, h); onCreate(w, h) })
				}
				// What the fields hold while it is none of the above.
				val customRatio = if (layout.w != null && layout.h != null && layout.w!! > 0 && layout.h!! > 0) formatAspectRatio(layout.w!!, layout.h!!) else ""
				SizeRow(tr("start.layout.size.custom"), if (matched) "" else customRatio,
					if (matched) "" else "${layout.width.trim()} × ${layout.height.trim()}", !matched,
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

/** A row of the canvas size list: its name, aspect ratio, and pixel size. */
@Composable
private fun SizeRow(label: String, ratio: String, size: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit, onDoubleClick: () -> Unit) {
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
		horizontalArrangement = Arrangement.spacedBy(8.dp),
	) {
		Text(label, style = typography.body.copy(fontSize = 11.5.sp),
			color = if (selected) colors.selectionText else colors.textPrimary, modifier = Modifier.weight(1f))
		if (ratio.isNotEmpty()) {
			Text(ratio, style = typography.monoSmall.copy(fontSize = 9.5.sp), color = if (selected) colors.selectionText.copy(alpha = 0.75f) else colors.textDisabled)
		}
		Text(size, style = typography.monoSmall.copy(fontSize = 10.sp), color = if (selected) colors.selectionText else colors.textMuted)
	}
}

/** The way through a Layout project and what its canvas means, beside the canvas being set. */
@Composable
private fun LayoutDetails() {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	Column(Modifier.fillMaxWidth()) {
		listOf("import", "place", "generate").forEachIndexed { index, step ->
			Row(
				Modifier.fillMaxWidth()
					.drawBehind { drawLine(colors.divider, Offset(0f, size.height), Offset(size.width, size.height), 0.5.dp.toPx()) }
					.padding(horizontal = 10.dp, vertical = 8.dp),
				horizontalArrangement = Arrangement.spacedBy(8.dp),
			) {
				StatusBadge("0${index + 1}", colors.accent, colors.accent.copy(alpha = 0.14f))
				Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
					Text(tr("start.layout.step.$step"), style = typography.body.copy(fontSize = 11.5.sp, fontWeight = FontWeight.Medium), color = colors.textPrimary)
					Text(tr("start.layout.step.$step.desc"), style = typography.caption.copy(fontSize = 10.5.sp, lineHeight = 14.sp), color = colors.textMuted)
				}
			}
		}
		Text(tr("layout.new.hint"), style = typography.caption.copy(fontSize = 10.5.sp, lineHeight = 14.sp), color = colors.textMuted,
			modifier = Modifier.padding(10.dp))
	}
}

/** The canvas drawn to scale inside the panel with rule-of-thirds blueprint lines and aspect-ratio badge. */
@Composable
private fun CanvasSizePreview(width: Int?, height: Int?, valid: Boolean, modifier: Modifier) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val aspect = if (width != null && height != null && width > 0 && height > 0) formatAspectRatio(width, height) else null
	Box(modifier.background(colors.inputBackground).border(0.5.dp, colors.divider)) {
		Box(Modifier.fillMaxSize().padding(start = 28.dp, end = 28.dp, top = 24.dp, bottom = 44.dp).drawBehind {
			val w = (width ?: 0).coerceAtLeast(1).toFloat()
			val h = (height ?: 0).coerceAtLeast(1).toFloat()
			val scale = minOf(size.width / w, size.height / h)
			val rect = Size(w * scale, h * scale)
			val origin = Offset((size.width - rect.width) / 2f, (size.height - rect.height) / 2f)
			drawChecker(origin, rect, colors.checkerLight, colors.checkerDark, 8.dp.toPx())
			val guideColor = (if (valid) colors.accent else colors.warning).copy(alpha = 0.28f)
			val stroke = 0.75.dp.toPx()
			for (i in 1..2) {
				val gx = origin.x + rect.width * (i / 3f)
				val gy = origin.y + rect.height * (i / 3f)
				drawLine(guideColor, Offset(gx, origin.y), Offset(gx, origin.y + rect.height), stroke)
				drawLine(guideColor, Offset(origin.x, gy), Offset(origin.x + rect.width, gy), stroke)
			}
			drawRect(if (valid) colors.accent else colors.warning, origin, rect, style = Stroke(1.dp.toPx()))
		})
		Row(
			Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(8.dp),
		) {
			Text(
				if (width != null && height != null) "$width × $height" else "—",
				style = typography.monoSmall.copy(fontSize = 11.sp),
				color = if (valid) colors.textPrimary else colors.warning,
			)
			aspect?.let { StatusBadge(it, colors.textMuted, colors.controlBackground) }
		}
	}
}

private fun formatAspectRatio(w: Int, h: Int): String {
	var a = w
	var b = h
	while (b != 0) {
		val t = a % b
		a = b
		b = t
	}
	return "${w / a}:${h / a}"
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

/** Draws subtle L-shaped registration marks in the four corners of a CCD viewport. */
private fun DrawScope.drawViewportRegistrationMarks(color: Color) {
	val pad = 12.dp.toPx()
	val len = 10.dp.toPx()
	val stroke = 1.dp.toPx()
	if (size.width <= pad * 2 + len * 2 || size.height <= pad * 2 + len * 2) return
	drawLine(color, Offset(pad, pad), Offset(pad + len, pad), stroke)
	drawLine(color, Offset(pad, pad), Offset(pad, pad + len), stroke)
	drawLine(color, Offset(size.width - pad, pad), Offset(size.width - pad - len, pad), stroke)
	drawLine(color, Offset(size.width - pad, pad), Offset(size.width - pad, pad + len), stroke)
	drawLine(color, Offset(pad, size.height - pad), Offset(pad + len, size.height - pad), stroke)
	drawLine(color, Offset(pad, size.height - pad), Offset(pad, size.height - pad - len), stroke)
	drawLine(color, Offset(size.width - pad, size.height - pad), Offset(size.width - pad - len, size.height - pad), stroke)
	drawLine(color, Offset(size.width - pad, size.height - pad), Offset(size.width - pad, size.height - pad - len), stroke)
}

// ---------------------------------------------------------------------------------------------------------------------
// CMO3
// ---------------------------------------------------------------------------------------------------------------------

@Composable
private fun Cmo3Content(enabled: Boolean, onOpenCmo3: (() -> Unit)?, modifier: Modifier = Modifier) {
	ViewportPrompt(
		title = tr("start.cmo3.choose"),
		subtitle = tr("start.cmo3.choose.desc"),
		enabled = enabled && onOpenCmo3 != null,
		onClick = { onOpenCmo3?.invoke() },
		modifier = modifier,
	) { IconDocument(Modifier.size(28.dp), it) }
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
 * The recent files browser: filter/kind/view toolbar over a thumbnail grid or structured data table.
 * When no recent files exist, displays a flat input-routing table so beginners can pick their starting path directly.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecentBrowser(
	recent: List<RecentFile>?,
	model: StartPageModel,
	enabled: Boolean,
	actions: RecentFileActions,
	onGenerate: () -> Unit,
	onLayout: () -> Unit,
	onCmo3: () -> Unit,
	modifier: Modifier = Modifier,
) {
	val selection = model.selection
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val shown = recent.orEmpty().filter { file ->
		(if (file.kind == RecentFileKind.PROJECT) selection.showProjects else selection.showPsds) &&
			(selection.recentFilter.isBlank() || file.name.contains(selection.recentFilter.trim(), ignoreCase = true))
	}
	Column(modifier) {
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
			recent.isEmpty() -> EmptyInputRoutingTable(enabled, onGenerate, onLayout, onCmo3, Modifier.weight(1f).fillMaxWidth())
			shown.isEmpty() -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
				Text(tr("start.recent.noMatch"), style = typography.caption.copy(fontSize = 11.sp), color = colors.textDisabled)
			}
			selection.recentGrid -> Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(4.dp)) {
				MenuGlide(Modifier.fillMaxWidth()) {
					FlowRow(Modifier.fillMaxWidth()) {
						shown.forEach { file ->
							RecentTile(file, file.path == selection.recentPath, model.card.dp, enabled, onClick = { selection.recentPath = file.path }, actions)
						}
					}
				}
			}
			else -> Column(Modifier.weight(1f).fillMaxWidth()) {
				RecentTableHeader()
				Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
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
}

/** Flat hairline-divided input-routing table when no recent files exist, avoiding cards or prose walls. */
@Composable
private fun EmptyInputRoutingTable(
	enabled: Boolean,
	onGenerate: () -> Unit,
	onLayout: () -> Unit,
	onCmo3: () -> Unit,
	modifier: Modifier = Modifier,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	Column(modifier.fillMaxWidth()) {
		Box(
			Modifier.fillMaxWidth().height(24.dp)
				.background(colors.panelElevated)
				.drawBehind { drawLine(colors.divider, Offset(0f, size.height), Offset(size.width, size.height), 0.5.dp.toPx()) }
				.padding(horizontal = 10.dp),
			contentAlignment = Alignment.CenterStart,
		) {
			Text(tr("canvas.start.recent.empty"), style = typography.caption.copy(fontSize = 10.5.sp), color = colors.textMuted)
		}
		InputRouteRow("01", tr("start.empty.psd"), tr("psdGuide.start.details"), tr("psdGuide.title"), enabled, onGenerate)
		InputRouteRow("02", tr("start.empty.layout"), tr("start.launcher.layout.desc"), tr("start.nav.layout"), enabled, onLayout)
		InputRouteRow("03", tr("start.empty.cmo3"), tr("start.launcher.cmo3.desc"), tr("start.nav.cmo3"), enabled, onCmo3)
	}
}

@Composable
private fun InputRouteRow(
	index: String,
	inputKind: String,
	outcome: String,
	actionLabel: String,
	enabled: Boolean,
	onClick: () -> Unit,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	Row(
		Modifier.fillMaxWidth().height(36.dp)
			.drawBehind { drawLine(colors.divider, Offset(0f, size.height), Offset(size.width, size.height), 0.5.dp.toPx()) }
			.clickable(enabled = enabled, onClick = onClick)
			.handCursor(enabled)
			.padding(horizontal = 10.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(10.dp),
	) {
		StatusBadge(index, colors.accent, colors.accent.copy(alpha = 0.14f))
		Text(inputKind, style = typography.body.copy(fontSize = 11.5.sp, fontWeight = FontWeight.Medium), color = colors.textPrimary,
			modifier = Modifier.width(170.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
		Text(outcome, style = typography.caption.copy(fontSize = 11.sp), color = colors.textMuted,
			modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
		CompactButton(actionLabel, onClick, enabled = enabled, height = 22.dp)
	}
}

/** Column header for the Recent list table view. */
@Composable
private fun RecentTableHeader() {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	Row(
		Modifier.fillMaxWidth().height(22.dp)
			.background(colors.panelElevated)
			.drawBehind { drawLine(colors.divider, Offset(0f, size.height), Offset(size.width, size.height), 0.5.dp.toPx()) }
			.padding(horizontal = 6.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(6.dp),
	) {
		Spacer(Modifier.width(22.dp))
		Text(tr("start.recent.column.name"), style = typography.caption.copy(fontSize = 10.sp), color = colors.textMuted, modifier = Modifier.weight(0.45f))
		Text(tr("start.recent.column.location"), style = typography.caption.copy(fontSize = 10.sp), color = colors.textMuted, modifier = Modifier.weight(0.55f))
		Text(tr("start.recent.column.kind"), style = typography.caption.copy(fontSize = 10.sp), color = colors.textMuted, modifier = Modifier.width(36.dp))
		Text(tr("start.recent.canvas"), style = typography.caption.copy(fontSize = 10.sp), color = colors.textMuted, modifier = Modifier.width(68.dp))
		Text(tr("start.recent.column.modified"), style = typography.caption.copy(fontSize = 10.sp), color = colors.textMuted, modifier = Modifier.width(96.dp))
		Text(tr("start.recent.column.size"), style = typography.caption.copy(fontSize = 10.sp), color = colors.textMuted,
			modifier = Modifier.width(56.dp), textAlign = TextAlign.End)
	}
}

/**
 * A recent file tile in grid mode: checkerboard thumbnail with a corner type badge, filename, and compact technical
 * metadata underneath.
 */
@Composable
private fun RecentTile(file: RecentFile, selected: Boolean, size: Dp, enabled: Boolean, onClick: () -> Unit, actions: RecentFileActions) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val details = rememberRecentFileDetails(file)
	val interaction = remember { MutableInteractionSource() }
	val hovered by interaction.collectIsHoveredAsState()
	val fill by animateColorAsState(selectionFill(selected), tween(Motion.Instant))
	val edge by animateColorAsState(when {
		selected -> colors.accent.copy(alpha = 0.7f)
		hovered && enabled -> colors.accent.copy(alpha = 0.35f)
		else -> Color.Transparent
	}, tween(Motion.Instant))
	RecentFileMenuHost(file, enabled, onClick, actions) {
		Column(
			Modifier.padding(3.dp).glideItem(enabled)
				.background(fill)
				.border(0.5.dp, edge)
				.hoverable(interaction, enabled)
				.selectOrOpen(enabled, onClick) { actions.open(file) }
				.handCursor(enabled)
				.padding(4.dp),
			horizontalAlignment = Alignment.CenterHorizontally,
		) {
			Box(Modifier.size(size, size * 0.75f)) {
				Thumbnail(details?.thumbnail, file.kind, Modifier.fillMaxSize())
				val kindTag = if (file.kind == RecentFileKind.PSD) "PSD" else "PROJ"
				val tagColor = if (file.kind == RecentFileKind.PSD) colors.accent else colors.warning
				Box(Modifier.align(Alignment.TopEnd).padding(3.dp)) {
					StatusBadge(kindTag, tagColor, colors.windowBackground.copy(alpha = 0.85f))
				}
			}
			Text(file.name, color = if (selected) colors.selectionText else colors.textPrimary, fontSize = 10.5.sp,
				fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal, maxLines = 1,
				overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
				modifier = Modifier.width(size).padding(top = 3.dp))
			val meta = buildTileMeta(details)
			if (meta.isNotEmpty()) {
				Text(meta, style = typography.monoSmall.copy(fontSize = 9.5.sp),
					color = if (selected) colors.selectionText.copy(alpha = 0.75f) else colors.textMuted,
					maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
					modifier = Modifier.width(size).padding(top = 1.dp))
			}
		}
	}
}

private fun buildTileMeta(details: RecentFileDetails?): String {
	if (details == null) return ""
	val dim = if (details.canvasWidth != null && details.canvasHeight != null) "${details.canvasWidth}×${details.canvasHeight}" else formatBytes(details.sizeBytes)
	val date = ShortDateFormat.format(Instant.ofEpochMilli(details.modifiedMillis))
	return "$dim · $date"
}

/**
 * A recent file row in list mode: small thumbnail, name, directory, kind, canvas, modified date, and size.
 */
@Composable
private fun RecentRow(file: RecentFile, selected: Boolean, enabled: Boolean, onClick: () -> Unit, actions: RecentFileActions) {
	val colors = LocalToolColors.current
	val details = rememberRecentFileDetails(file)
	val muted = if (selected) colors.selectionText.copy(alpha = 0.75f) else colors.textMuted
	val fill by animateColorAsState(selectionFill(selected), tween(Motion.Instant))
	RecentFileMenuHost(file, enabled, onClick, actions) { RecentRowContent(file, selected, details, muted,
		Modifier.fillMaxWidth().height(26.dp)
			.glideItem(enabled)
			.background(fill)
			.drawBehind { drawLine(colors.divider.copy(alpha = 0.45f), Offset(0f, size.height), Offset(size.width, size.height), 0.5.dp.toPx()) }
			.selectOrOpen(enabled, onClick) { actions.open(file) }
			.handCursor(enabled)
			.padding(horizontal = 6.dp)) }
}

@Composable
private fun RecentRowContent(file: RecentFile, selected: Boolean, details: RecentFileDetails?, muted: Color, modifier: Modifier) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	Row(
		modifier,
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(6.dp),
	) {
		Thumbnail(details?.thumbnail, file.kind, Modifier.size(22.dp))
		Text(file.name, color = if (selected) colors.selectionText else colors.textPrimary, fontSize = 11.sp, maxLines = 1,
			overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(0.45f))
		Text(file.directory, color = muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(0.55f))
		Text(kindLabel(file.kind), color = muted, fontSize = 10.sp, modifier = Modifier.width(36.dp), maxLines = 1)
		Text(details?.canvasWidth?.let { "$it×${details.canvasHeight}" } ?: "—", style = typography.monoSmall.copy(fontSize = 10.sp),
			color = muted, modifier = Modifier.width(68.dp), maxLines = 1)
		Text(details?.let { DateFormat.format(Instant.ofEpochMilli(it.modifiedMillis)) }.orEmpty(), style = typography.monoSmall.copy(fontSize = 10.sp),
			color = muted, modifier = Modifier.width(96.dp), maxLines = 1)
		Text(details?.let { formatBytes(it.sizeBytes) }.orEmpty(), style = typography.monoSmall.copy(fontSize = 10.sp),
			color = muted, modifier = Modifier.width(56.dp), textAlign = TextAlign.End)
	}
}

/** A file's picture (without background for PSDs), or its kind's icon while it has none. */
@Composable
private fun Thumbnail(image: ImageBitmap?, kind: RecentFileKind, modifier: Modifier) {
	val colors = LocalToolColors.current
	Box(
		if (kind == RecentFileKind.PSD) modifier
		else modifier.drawBehind {
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
					0 -> FloatingMenuRow(tr(if (file.kind == RecentFileKind.PSD) "psdGuide.start.open" else "start.action.recent.open"),
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
 * The presets a PSD import applies: a segmented preset strip at top, then live collapsible parameter rows directly below.
 */
@Composable
private fun RecentInspector(
	file: RecentFile?,
	enabled: Boolean,
	viewModel: PSD2LiveViewModel,
	onRevealFile: (String) -> Unit,
) {
	if (file == null) {
		InspectorNote(tr("start.recent.pick"))
		return
	}
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val details = rememberRecentFileDetails(file)
	val previewHeight = if (file.kind == RecentFileKind.PSD) 128.dp else 172.dp
	Column(Modifier.fillMaxWidth()) {
		Column(Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
			Thumbnail(details?.thumbnail, file.kind, Modifier.fillMaxWidth().height(previewHeight).border(0.5.dp, colors.divider))
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
				Text(file.directory, fontSize = 10.5.sp, color = colors.textMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
			}
			Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
				CompactButton(tr("start.recent.reveal"), { onRevealFile(file.directory) }, height = 22.dp)
				CompactButton(tr("start.recent.forget"), { viewModel.forgetRecentFile(file.path) }, enabled = enabled, height = 22.dp)
			}
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
	layout: LayoutDraft,
	files: StartFiles,
	enabled: Boolean,
	actions: PageActions,
	primary: () -> Unit,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val selectedPsd = files.selectedPsd
	val selectedRecent = files.selectedRecent
	val hint = when (section) {
		StartSection.GENERATE -> selectedPsd?.path ?: tr("psdGuide.start.hint")
		StartSection.LAYOUT -> if (layout.valid) tr("start.action.layout.hint", layout.w!!, layout.h!!) else tr("start.layout.invalid")
		StartSection.CMO3 -> tr("start.action.cmo3.hint")
		StartSection.RECENT -> selectedRecent?.path ?: tr("start.recent.pick")
		StartSection.UPDATES, StartSection.HELP, StartSection.SETTINGS -> ""
	}
	Row(
		Modifier.fillMaxWidth().height(36.dp)
			.background(colors.panelElevated)
			.drawBehind { drawLine(colors.divider, Offset(0f, 0f), Offset(size.width, 0f), 0.5.dp.toPx()) }
			.padding(horizontal = 10.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(8.dp),
	) {
		Text(hint, style = typography.body.copy(fontSize = 11.sp), color = colors.textMuted, maxLines = 1,
			overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
		KeycapBadge("Enter")
		when (section) {
			StartSection.GENERATE -> AccentButton(
                tr(if (selectedPsd != null) "psdGuide.start.open" else "psdGuide.start.browse"),
                primary, enabled = enabled && (selectedPsd != null || actions.onOpenPsd != null), emphasis = true
            ) { IconDocument(Modifier.size(12.dp), it) }
			StartSection.LAYOUT -> AccentButton(tr("start.action.layout"), primary, enabled = enabled && layout.valid, emphasis = true) {
				WorkspacePresetIcon(WorkspacePreset.LAYOUT, it, Modifier.size(12.dp))
			}
			StartSection.CMO3 -> AccentButton(tr("start.action.cmo3"), primary, enabled = enabled && actions.onOpenCmo3 != null, emphasis = true) {
				IconDocument(Modifier.size(12.dp), it)
			}
			StartSection.RECENT -> AccentButton(
				tr(if (selectedRecent?.kind == RecentFileKind.PSD) "psdGuide.start.open" else "start.action.recent.open"),
				primary, enabled = enabled && selectedRecent != null, emphasis = true,
			) { IconFolder(modifier = Modifier.size(12.dp), tint = it) }
			StartSection.UPDATES, StartSection.HELP, StartSection.SETTINGS -> {}
		}
	}
}

// ---------------------------------------------------------------------------------------------------------------------
// Shared pieces
// ---------------------------------------------------------------------------------------------------------------------

/** Compact CCD pipeline breadcrumb bar across the top of creation workbenches. */
@Composable
private fun PipelineStepsBar(steps: List<Pair<String, String>>, activeIndex: Int) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	Row(
		Modifier.fillMaxWidth().height(26.dp)
			.background(colors.panelBackground)
			.drawBehind { drawLine(colors.divider, Offset(0f, size.height), Offset(size.width, size.height), 0.5.dp.toPx()) }
			.padding(horizontal = 10.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(8.dp),
	) {
		steps.forEachIndexed { index, (num, label) ->
			val active = index == activeIndex
			Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
				StatusBadge(
					text = num,
					color = if (active) colors.accentText else colors.textMuted,
					background = if (active) colors.accent else colors.controlBackground,
				)
				Text(
					label,
					style = typography.caption.copy(fontSize = 10.5.sp, fontWeight = if (active) FontWeight.Medium else FontWeight.Normal),
					color = if (active) colors.textPrimary else colors.textMuted,
					maxLines = 1,
				)
			}
			if (index < steps.lastIndex) {
				Text("→", style = typography.monoSmall.copy(fontSize = 10.sp), color = colors.textDisabled)
			}
		}
	}
}

/** Styled keyboard shortcut keycap pill. */
@Composable
private fun KeycapBadge(text: String) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	Box(
		Modifier.background(colors.panelElevated)
			.border(0.5.dp, colors.border)
			.padding(horizontal = 4.dp, vertical = 1.dp),
	) {
		Text(text, style = typography.monoSmall.copy(fontSize = 9.5.sp), color = colors.textMuted, maxLines = 1)
	}
}

/** Technical status / type / version pill. */
@Composable
private fun StatusBadge(text: String, color: Color, background: Color) {
	val typography = LocalToolTypography.current
	Box(
		Modifier.background(background)
			.padding(horizontal = 5.dp, vertical = 1.dp),
	) {
		Text(text, style = typography.monoSmall.copy(fontSize = 9.5.sp, fontWeight = FontWeight.Medium), color = color, maxLines = 1)
	}
}

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
			.drawBehind { drawViewportRegistrationMarks(colors.borderHover.copy(alpha = if (hovered && enabled) 0.85f else 0.45f)) }
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
		hint?.let { Text(it, style = typography.monoSmall.copy(fontSize = 10.sp), color = colors.textMuted) }
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
