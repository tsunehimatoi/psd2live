package io.github.psd2live.ui.views.physics

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Divider
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.core.PhysicsGenerator
import io.github.psd2live.core.PhysicsGroup
import io.github.psd2live.core.PhysicsIssue
import io.github.psd2live.core.PhysicsOrigin
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.components.CompactCheckbox
import io.github.psd2live.ui.components.CompactMenuDivider
import io.github.psd2live.ui.components.CompactMenuItem
import io.github.psd2live.ui.components.IconAdd
import io.github.psd2live.ui.components.TreeContextMenu
import io.github.psd2live.ui.state.PSD2LiveState
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.state.previewPanelState
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography
import io.github.psd2live.ui.utils.NativeFilePicker
import io.github.psd2live.ui.views.IconArrowVertical
import io.github.psd2live.ui.views.PanelSectionRow
import io.github.psd2live.ui.views.PanelToolButton
import io.github.psd2live.ui.views.PanelToolbar
import io.github.psd2live.ui.views.PanelSearch
import io.github.psd2live.ui.views.PanelToolbarSeparator
import io.github.psd2live.ui.views.PanelIconButton
import io.github.psd2live.ui.views.PanelShowPreviewButton
import io.github.psd2live.ui.views.PanelResetButton

/**
 * Physics: every pendulum the model exports, generated or the user's, in one list in evaluation order.
 * The selected group is edited on its pendulum - drag a vertex to set a pendulum's length, drag elsewhere
 * to shake it - with sliders for what has no place on it and the raw numbers folded under Advanced.
 */
@Composable
internal fun PhysicsPanelView(
	viewModel: PSD2LiveViewModel,
	state: PSD2LiveState,
	modifier: Modifier = Modifier,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val previewState = state.previewPanelState()
	val groups = viewModel.physicsGroups(state)
	var selectedId by remember { mutableStateOf<String?>(null) }
	val selected = groups.firstOrNull { it.id == selectedId } ?: groups.firstOrNull()
	val parameters = state.previewModel?.rig?.puppet?.parameters.orEmpty()
	var query by remember { mutableStateOf("") }
	var listOpen by remember { mutableStateOf(true) }
	val needle = query.trim()
	val shown = if (needle.isEmpty()) groups else groups.filter { it.setting.name.contains(needle, ignoreCase = true) }

	Column(modifier.fillMaxSize().background(colors.panelBackground)) {
		PhysicsToolbar(viewModel, state, previewState, groups, selected, query, { query = it }) { id -> selectedId = id }
		if (state.previewModel == null) {
			Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
				Text(tr("physics.noModel"), style = typography.caption.copy(fontSize = 11.sp), color = colors.textMuted, modifier = Modifier.padding(12.dp))
			}
			return@Column
		}
		val scroll = rememberScrollState()
		Box(Modifier.fillMaxSize()) {
			Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(end = 6.dp)) {
				PanelSectionRow(tr("physics.groups"), listOpen || needle.isNotEmpty(), { listOpen = !listOpen }, count = groups.size)
				PhysicsRowDivider()
				if (listOpen || needle.isNotEmpty()) {
					PhysicsGroupList(viewModel, state, groups, shown, selected?.id, if (state.rigEdits.modifierStack != null) groups.any { it.active } else previewState.generatePhysics && !previewState.meshOnly,
						empty = if (needle.isEmpty()) tr("physics.empty") else tr("physics.noResults"),
						onSelect = { selectedId = it })
				}
				if (selected != null) {
					PhysicsGroupEditor(viewModel, state, selected, groups, parameters) { id -> selectedId = id }
				}
				Spacer(Modifier.height(8.dp))
			}
			VerticalScrollbar(
				adapter = rememberScrollbarAdapter(scroll),
				modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(6.dp),
			)
		}
	}
}

/**
 * Search, new group and evaluation order, with a status line under it. Playback, tracking, the physics
 * switch and the frame rate stay on the preview canvas, next to the model they act on.
 */
@Composable
private fun PhysicsToolbar(
	viewModel: PSD2LiveViewModel,
	state: PSD2LiveState,
	previewState: PSD2LiveState,
	groups: List<PhysicsGroup>,
	selected: PhysicsGroup?,
	query: String,
	onQuery: (String) -> Unit,
	onCreated: (String) -> Unit,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val on = if (state.rigEdits.modifierStack != null) groups.any { it.active } else previewState.generatePhysics && !previewState.meshOnly
	var newMenuOpen by remember { mutableStateOf(false) }
	val labels = listOf(tr("physics.new"))
	PanelToolbar(
		labels = labels,
		iconCount = if (state.previewLive) 4 else 5,
		search = PanelSearch(query, onQuery, tr("physics.search")),
		secondary = {
			Row(
				Modifier.fillMaxWidth().padding(horizontal = 4.dp),
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(6.dp),
			) {
				Box(Modifier.size(6.dp).clip(CircleShape).background(if (on) colors.accent else colors.textDisabled))
				Text(
					tr(if (on) "physics.status.on" else "physics.status.off", fpsText(previewState.rigEdits.physicsFps)),
					style = typography.caption.copy(fontSize = 10.sp),
					color = if (on) colors.textPrimary else colors.textMuted,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
					modifier = Modifier.weight(1f),
				)
				Text(
					tr("physics.activeCount", groups.count { it.active }, groups.size),
					style = typography.caption.copy(fontSize = 10.sp),
					color = if (on) colors.accent else colors.textMuted,
					maxLines = 1,
				)
			}
			if (previewState.meshOnly) {
				Text(tr("physics.meshOnly"), style = typography.caption.copy(fontSize = 10.sp), color = colors.warning, modifier = Modifier.padding(horizontal = 4.dp))
			}
		},
	) { labelsShown ->
		Box {
			PanelToolButton(
				label = labels[0],
				showLabel = labelsShown > 0,
				onClick = { newMenuOpen = true },
				enabled = state.previewModel != null,
				tooltip = tr("physics.new"),
			) {
				IconAdd(modifier = Modifier.size(10.dp), tint = colors.textPrimary)
			}
			NewPhysicsMenu(viewModel, selected, newMenuOpen, { newMenuOpen = false }, onCreated)
		}
		// Cubism runs groups top to bottom; a later group reads an earlier one's outputs.
		val reorderable = groups.filter { it.origin != PhysicsOrigin.MODIFIER }
        val index = reorderable.indexOfFirst { it.id == selected?.id }
		PanelToolbarSeparator()
		PanelIconButton(
			onClick = { selected?.let { viewModel.movePhysicsGroup(it.id, -1) } },
			enabled = index > 0,
			tooltip = tr("physics.moveUp"),
		) { IconArrowVertical(up = true, tint = colors.textMuted) }
		PanelIconButton(
			onClick = { selected?.let { viewModel.movePhysicsGroup(it.id, 1) } },
			enabled = index in 0 until reorderable.size - 1,
			tooltip = tr("physics.moveDown"),
		) { IconArrowVertical(up = false, tint = colors.textMuted) }
		Spacer(Modifier.weight(1f))
		PanelShowPreviewButton(state, viewModel)
		PanelResetButton(onClick = { viewModel.resetPreviewParameters() }, enabled = state.previewModel != null, tooltip = tr("animation.resetPose"))
	}
}

@Composable
private fun fpsText(fps: Int): String = if (fps > 0) "$fps FPS" else tr("preview.fps.unlimited")

/** Blank pendulum, a copy of the selected group, an imported physics3.json, or a swing on the selection. */
@Composable
private fun NewPhysicsMenu(
	viewModel: PSD2LiveViewModel,
	selected: PhysicsGroup?,
	open: Boolean,
	onDismiss: () -> Unit,
	onCreated: (String) -> Unit,
) {
	TreeContextMenu(expanded = open, onDismissRequest = onDismiss) {
		CompactMenuItem(text = tr("physics.newBlank"), onClick = {
			onDismiss()
			viewModel.createPhysicsGroup(onCreated = onCreated)
		})
		CompactMenuItem(text = tr("physics.duplicate"), enabled = selected != null && selected.origin != PhysicsOrigin.MODIFIER, onClick = {
			onDismiss()
			selected?.let { viewModel.createPhysicsGroup(it.setting, onCreated) }
		})
		CompactMenuItem(text = tr("physics.import"), onClick = {
			onDismiss()
			NativeFilePicker.choosePhysicsFile()?.let { path -> viewModel.importPhysics(path, onCreated) }
		})
		CompactMenuDivider()
		val targets = viewModel.canvasEditor.swingTargets()
		CompactMenuItem(text = tr("physics.newSwing"), enabled = targets.isNotEmpty(), onClick = {
			onDismiss()
            if (viewModel.state.value.rigEdits.modifierStack != null) {
                viewModel.openModifierAddition(); viewModel.previewModifierAddition("swing", null)
            } else viewModel.beginSwing(targets)
		})
	}
}

/** What can be done to one group, shared by its row's right-click menu and the editor's title. */
@Composable
internal fun ColumnScope.PhysicsGroupMenuItems(
	viewModel: PSD2LiveViewModel,
	state: PSD2LiveState,
	group: PhysicsGroup,
	groups: List<PhysicsGroup>,
	onSelect: (String) -> Unit,
	onRename: (() -> Unit)?,
	dismiss: () -> Unit,
) {
	val swing = if (group.origin == PhysicsOrigin.SWING) PhysicsGenerator.swingOf(group.id, state.rigEdits.swingEdits) else null
	val reorderable = groups.filter { it.origin != PhysicsOrigin.MODIFIER }
    val index = reorderable.indexOfFirst { it.id == group.id }
	if (onRename != null && group.origin != PhysicsOrigin.MODIFIER) CompactMenuItem(tr("physics.rename"), { dismiss(); onRename() })
	CompactMenuItem(tr("physics.duplicate"), { dismiss(); viewModel.createPhysicsGroup(group.setting, onSelect) }, enabled = group.origin != PhysicsOrigin.MODIFIER)
	if (swing != null) CompactMenuItem(tr("physics.openSwing"), { dismiss(); viewModel.beginSwing(swing.targets) })
	CompactMenuDivider()
	CompactMenuItem(tr("physics.moveUp"), { dismiss(); viewModel.movePhysicsGroup(group.id, -1) }, enabled = index > 0)
	CompactMenuItem(tr("physics.moveDown"), { dismiss(); viewModel.movePhysicsGroup(group.id, 1) }, enabled = index in 0 until reorderable.size - 1)
	CompactMenuDivider()
	CompactMenuItem(tr("physics.resetDefaults"), { dismiss(); viewModel.removePhysicsGroup(group.id) }, enabled = group.overridden)
	CompactMenuItem(tr("physics.delete"), { dismiss(); viewModel.removePhysicsGroup(group.id) },
		enabled = group.origin == PhysicsOrigin.CUSTOM, danger = true)
}

@Composable
private fun PhysicsGroupList(
	viewModel: PSD2LiveViewModel,
	state: PSD2LiveState,
	groups: List<PhysicsGroup>,
	shown: List<PhysicsGroup>,
	selectedId: String?,
	globalOn: Boolean,
	empty: String,
	onSelect: (String) -> Unit,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	if (shown.isEmpty()) {
		Text(
			empty,
			style = typography.caption.copy(fontSize = 10.5.sp),
			color = colors.textMuted,
			modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
		)
		PhysicsRowDivider()
		return
	}
	for (group in shown) {
		PhysicsGroupRow(viewModel, state, group, groups, groups.indexOf(group), group.id == selectedId, globalOn, onSelect)
		PhysicsRowDivider()
	}
}

/** One group in evaluation order; the selected one is marked like a related parameter. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun PhysicsGroupRow(
	viewModel: PSD2LiveViewModel,
	state: PSD2LiveState,
	group: PhysicsGroup,
	groups: List<PhysicsGroup>,
	index: Int,
	selected: Boolean,
	globalOn: Boolean,
	onSelect: (String) -> Unit,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val interaction = remember { MutableInteractionSource() }
	val hovered by interaction.collectIsHoveredAsState()
	var menuOpen by remember { mutableStateOf(false) }
	var menuOffset by remember { mutableStateOf(Offset.Zero) }
	Box {
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.height(24.dp)
				.background(
					when {
						selected -> colors.selection.copy(alpha = 0.35f)
						hovered -> colors.controlHover.copy(alpha = 0.35f)
						else -> Color.Transparent
					},
				)
				.drawWithContent {
					drawContent()
					if (selected) drawRect(colors.accent, size = Size(2.dp.toPx(), size.height))
				}
				.hoverable(interaction)
				.onPointerEvent(PointerEventType.Press) { event ->
					if (event.button == PointerButton.Secondary) {
						menuOffset = event.changes.firstOrNull()?.position ?: Offset.Zero
						menuOpen = true
						onSelect(group.id)
						event.changes.firstOrNull()?.consume()
					}
				}
				.clickable(interactionSource = interaction, indication = null) { onSelect(group.id) }
				.padding(start = 6.dp, end = 6.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(6.dp),
		) {
			CompactCheckbox(group.enabled, { viewModel.setPhysicsGroupEnabled(group.id, it) }, enabled = group.origin != PhysicsOrigin.MODIFIER)
			Text("${index + 1}", style = typography.monoSmall, color = colors.textMuted, modifier = Modifier.width(14.dp))
			Text(
				group.setting.name,
				style = typography.body.copy(fontSize = 11.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal),
				color = when {
					!(group.active && globalOn) -> colors.textMuted
					selected -> colors.accent
					else -> colors.textPrimary
				},
				maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
			)
			when {
				group.issue != null -> StatusDot(colors.warning, tr("physics.issue.short"))
				group.shadowedBy != null -> StatusDot(colors.textDisabled, tr("physics.replaced.short"))
				group.overridden -> StatusDot(colors.accent, tr("physics.modified"))
			}
			OriginBadge(group.origin)
		}
		TreeContextMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }, clickOffset = menuOffset, minWidth = 160.dp) {
			PhysicsGroupMenuItems(viewModel, state, group, groups, onSelect, onRename = null) { menuOpen = false }
		}
	}
}

@Composable
internal fun PhysicsRowDivider() {
	Divider(color = LocalToolColors.current.divider.copy(alpha = 0.4f), thickness = 0.5.dp)
}

@Composable
internal fun StatusDot(color: Color, label: String) {
	Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
		Box(Modifier.size(6.dp).clip(CircleShape).background(color))
		Text(label, style = LocalToolTypography.current.caption.copy(fontSize = 9.sp), color = color, maxLines = 1)
	}
}

@Composable
internal fun OriginBadge(origin: PhysicsOrigin) = RowBadge(tr("physics.origin.${origin.name.lowercase()}"))

/** A small muted tag at the end of a list row. */
@Composable
internal fun RowBadge(text: String) {
	val colors = LocalToolColors.current
	Box(
		Modifier.clip(RoundedCornerShape(2.dp)).background(colors.controlHover).padding(horizontal = 4.dp, vertical = 1.dp),
	) {
		Text(text, style = LocalToolTypography.current.caption.copy(fontSize = 9.sp), color = colors.textMuted, maxLines = 1)
	}
}

internal fun issueText(issue: PhysicsIssue): String = when (issue.code) {
	PhysicsIssue.Code.MISSING_PARAMETER -> tr("physics.issue.missing", issue.parameter.orEmpty())
	PhysicsIssue.Code.NO_INPUT -> tr("physics.issue.noInput")
	PhysicsIssue.Code.NO_OUTPUT -> tr("physics.issue.noOutput")
	PhysicsIssue.Code.FEEDBACK -> tr("physics.issue.feedback", issue.parameter.orEmpty())
	PhysicsIssue.Code.OUTPUT_TAKEN -> tr("physics.issue.taken", issue.parameter.orEmpty(), issue.group.orEmpty())
}
