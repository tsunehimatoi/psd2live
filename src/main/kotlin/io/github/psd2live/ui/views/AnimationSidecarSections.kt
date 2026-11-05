package io.github.psd2live.ui.views

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.Divider
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.core.ExpressionBlend
import io.github.psd2live.core.ExpressionEntry
import io.github.psd2live.core.ModelExpression
import io.github.psd2live.core.ModelPose
import io.github.psd2live.core.MotionClip
import io.github.psd2live.core.MotionEvent
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.components.CompactButton
import io.github.psd2live.ui.components.CompactIconButton
import io.github.psd2live.ui.components.CompactMenuDivider
import io.github.psd2live.ui.components.CompactMenuItem
import io.github.psd2live.ui.components.CompactMenuSection
import io.github.psd2live.ui.components.CompactNumberSpinner
import io.github.psd2live.ui.components.CompactTextField
import io.github.psd2live.ui.components.IconChevron
import io.github.psd2live.ui.components.IconClose
import io.github.psd2live.ui.components.IconEye
import io.github.psd2live.ui.components.TreeContextMenu
import io.github.psd2live.ui.state.PSD2LiveState
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.state.addPoseGroup
import io.github.psd2live.ui.state.captureExpression
import io.github.psd2live.ui.state.createExpression
import io.github.psd2live.ui.state.deleteExpression
import io.github.psd2live.ui.state.deleteMotionEvent
import io.github.psd2live.ui.state.deletePoseGroup
import io.github.psd2live.ui.state.duplicateExpression
import io.github.psd2live.ui.state.modelExpressions
import io.github.psd2live.ui.state.moveExpression
import io.github.psd2live.ui.state.newMotionEventValue
import io.github.psd2live.ui.state.previewExpression
import io.github.psd2live.ui.state.renameExpression
import io.github.psd2live.ui.state.resetExpressions
import io.github.psd2live.ui.state.setExpressionEntry
import io.github.psd2live.ui.state.setExpressionFades
import io.github.psd2live.ui.state.setMotionEvent
import io.github.psd2live.ui.state.setPoseFade
import io.github.psd2live.ui.state.setPoseGroup
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography
import io.github.psd2live.ui.theme.Motion
import io.github.psd2live.ui.theme.handCursor

/*
 * The animation panel's sections after the motions: the model's expressions (exp3), its pose groups (pose3) and an
 * overview of what a host receives from it - every motion's events and every ArtMesh's user data.
 */

private const val EXPRESSION_SECTION = "expressions"
private const val POSE_SECTION = "pose"
private const val EVENT_SECTION = "events"
internal val SIDECAR_SECTIONS = listOf(EXPRESSION_SECTION, POSE_SECTION, EVENT_SECTION)

private fun blendLabel(blend: ExpressionBlend) = tr("expression.blend.${blend.name.lowercase()}")

/** Whether [needle] finds an expression, a pose part or an event, so a search that finds no motion still lists them. */
internal fun sidecarsMatch(state: PSD2LiveState, needle: String): Boolean =
	state.modelExpressions().any { it.name.contains(needle, ignoreCase = true) } ||
		io.github.psd2live.core.MotionCatalog.entries(state.rigEdits).any { clip -> clip.events.any { it.value.contains(needle, ignoreCase = true) } }

/** The new menu's expression items: from the pose shown now, empty, or from a saved snapshot. */
@Composable
internal fun NewExpressionMenuItems(viewModel: PSD2LiveViewModel, state: PSD2LiveState, onDismiss: () -> Unit) {
	CompactMenuDivider()
	CompactMenuSection(tr("expression.section"))
	CompactMenuItem(text = tr("expression.newFromPose"), onClick = { onDismiss(); viewModel.createExpression(fromPose = true) })
	CompactMenuItem(text = tr("expression.newBlank"), onClick = { onDismiss(); viewModel.createExpression(fromPose = false) })
	for (snapshot in state.parameterSnapshots) {
		CompactMenuItem(
			text = tr("expression.newFromSnapshot", snapshot.name.ifBlank { tr("parameters.snapshotName", snapshot.number) }),
			onClick = { onDismiss(); viewModel.createExpression(fromPose = false, snapshotId = snapshot.id) },
		)
	}
}

internal fun LazyListScope.sidecarSections(
	viewModel: PSD2LiveViewModel,
	state: PSD2LiveState,
	needle: String,
	openSections: SnapshotStateMap<String, Boolean>,
	openSettings: SnapshotStateMap<String, Boolean>,
) {
	val puppet = state.previewModel?.rig?.puppet ?: return
	val standard = state.rigEdits.expressions == null
	val expressions = state.modelExpressions().filter { needle.isEmpty() || it.name.contains(needle, ignoreCase = true) }
	fun header(id: String, title: String, count: Int, trailing: (@Composable androidx.compose.foundation.layout.RowScope.() -> Unit)? = null): Boolean {
		val open = needle.isNotEmpty() || openSections[id] != false
		item(key = "s:$id") {
			val colors = LocalToolColors.current
			PanelSectionRow(title, open, { openSections[id] = !open }, count = count, trailing = trailing)
			Divider(color = colors.divider.copy(alpha = 0.4f), thickness = 0.5.dp)
		}
		return open
	}
	fun note(key: String, text: String) = item(key = key) {
		val colors = LocalToolColors.current
		val typography = LocalToolTypography.current
		Text(
			text = text,
			style = typography.caption.copy(fontSize = 10.5.sp),
			color = colors.textMuted,
			modifier = Modifier.fillMaxWidth().padding(start = MotionRowIndent + 4.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
		)
	}

	if (header(EXPRESSION_SECTION, tr("expression.section"), expressions.size, trailing = if (!standard) null else ({
		MotionBadge(tr("expression.standard"), LocalToolColors.current.textMuted)
	}))) {
		items(expressions, key = { "x:${it.id}" }) { expression ->
			val colors = LocalToolColors.current
			ExpressionRow(viewModel, state, expression, standard, openSettings["x:${expression.id}"] == true) {
				openSettings["x:${expression.id}"] = openSettings["x:${expression.id}"] != true
			}
			Divider(color = colors.divider.copy(alpha = 0.4f), thickness = 0.5.dp)
		}
		if (expressions.isEmpty() && needle.isEmpty()) note("e:$EXPRESSION_SECTION", tr("expression.empty"))
	}

	if (needle.isNotEmpty()) return
	val pose = state.rigEdits.pose
	if (header(POSE_SECTION, tr("pose.section"), pose?.groups?.size ?: 0)) {
		val parts = puppet.parts.associate { it.id.raw to it.name }
		val used = pose?.groups.orEmpty().flatMap { group -> group.parts.flatMap { it.ids } }.toSet()
		val free = puppet.parts.filter { it.id.raw !in used }.map { it.id.raw to it.name }
		pose?.groups.orEmpty().forEachIndexed { index, group ->
			item(key = "p:$index") {
				val colors = LocalToolColors.current
				PoseGroupRow(viewModel, index, group.parts.map { it.partId to it.links }, parts, free, openSettings["p:$index"] == true) {
					openSettings["p:$index"] = openSettings["p:$index"] != true
				}
				Divider(color = colors.divider.copy(alpha = 0.4f), thickness = 0.5.dp)
			}
		}
		item(key = "p:add") { PoseFooter(viewModel, pose, free) }
	}

	val events = io.github.psd2live.core.MotionCatalog.entries(state.rigEdits).flatMap { clip -> clip.events.map { clip to it } }
	val userData = puppet.drawables.filter { it.userData.isNotEmpty() }
	if (header(EVENT_SECTION, tr("sidecar.eventsSection"), events.size + userData.size)) {
		items(events, key = { (clip, event) -> "v:${clip.id}:${event.time}" }) { (clip, event) ->
			OverviewRow(clip.name, "%.2fs".format(event.time), event.value) {
				viewModel.openMotionInEditor(clip.id)
				viewModel.motionEditor.playhead = event.time
			}
		}
		items(userData, key = { "u:${it.id.raw}" }) { drawable ->
			OverviewRow(drawable.name.ifBlank { drawable.id.raw }, "ArtMesh", drawable.userData, null)
		}
		if (events.isEmpty() && userData.isEmpty()) note("e:$EVENT_SECTION", tr("sidecar.eventsEmpty"))
	}
}

/** A row like a motion's: a fold chevron, the title, trailing controls, a right-click menu and folded settings. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun SidecarRow(
	title: String,
	open: Boolean,
	onToggle: () -> Unit,
	onRename: ((String) -> Unit)?,
	menu: @Composable ColumnScope.(dismiss: () -> Unit, rename: () -> Unit) -> Unit,
	trailing: @Composable () -> Unit,
	settings: @Composable ColumnScope.() -> Unit,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val interaction = remember { MutableInteractionSource() }
	val hovered by interaction.collectIsHoveredAsState()
	var menuOpen by remember { mutableStateOf(false) }
	var menuOffset by remember { mutableStateOf(Offset.Zero) }
	var renaming by remember { mutableStateOf(false) }
	var draft by remember(title) { mutableStateOf(title) }
	val focus = remember { FocusRequester() }
	fun commit() {
		if (!renaming) return
		renaming = false
		val trimmed = draft.trim()
		if (trimmed.isNotEmpty() && trimmed != title) onRename?.invoke(trimmed)
	}
	LaunchedEffect(renaming) { if (renaming) runCatching { focus.requestFocus() } }
	Column(Modifier.fillMaxWidth()) {
		Box {
			Row(
				modifier = Modifier
					.fillMaxWidth()
					.height(MotionRowHeight)
					.background(if (hovered) colors.controlHover.copy(alpha = 0.35f) else Color.Transparent)
					.hoverable(interaction)
					.onPointerEvent(PointerEventType.Press) { event ->
						if (event.button == PointerButton.Secondary) {
							menuOffset = event.changes.firstOrNull()?.position ?: Offset.Zero
							menuOpen = true
							event.changes.firstOrNull()?.consume()
						}
					}
					.clickable(interactionSource = interaction, indication = null, enabled = !renaming, onClick = onToggle)
					.padding(start = 4.dp, end = 2.dp),
				verticalAlignment = Alignment.CenterVertically,
			) {
				IconChevron(expanded = open, tint = colors.textMuted, modifier = Modifier.size(10.dp))
				Spacer(Modifier.width(6.dp))
				if (renaming) {
					CompactTextField(
						value = draft,
						onValueChange = { draft = it },
						modifier = Modifier.weight(1f).focusRequester(focus).onPreviewKeyEvent { event ->
							if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) { renaming = false; true } else false
						},
						height = 20.dp,
						selectAllOnFocus = true,
						onCommit = { commit() },
						onFocusLost = { commit() },
					)
				} else {
					Text(
						text = title,
						style = typography.body.copy(fontSize = 11.sp),
						color = colors.textPrimary,
						maxLines = 1,
						overflow = TextOverflow.Ellipsis,
						modifier = Modifier.weight(1f),
					)
				}
				Spacer(Modifier.width(6.dp))
				trailing()
			}
			TreeContextMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }, clickOffset = menuOffset, minWidth = 160.dp) {
				menu({ menuOpen = false }, { menuOpen = false; draft = title; renaming = true })
			}
		}
		AnimatedVisibility(
			visible = open,
			enter = expandVertically(tween(Motion.Fold, easing = FastOutSlowInEasing)) + fadeIn(tween(Motion.Fade)),
			exit = shrinkVertically(tween(Motion.Instant, easing = FastOutLinearInEasing)) + fadeOut(tween(Motion.Instant)),
		) {
			Column(
				modifier = Modifier
					.fillMaxWidth()
					.background(colors.panelElevated.copy(alpha = 0.35f))
					.padding(start = MotionRowIndent + 4.dp, end = 6.dp, top = 4.dp, bottom = 6.dp),
				verticalArrangement = Arrangement.spacedBy(4.dp),
			) { settings() }
		}
	}
}

/** One expression: preview on the row; fades and entries folded underneath; capture, copy, order and delete in the menu. */
@Composable
private fun ExpressionRow(
	viewModel: PSD2LiveViewModel,
	state: PSD2LiveState,
	expression: ModelExpression,
	standard: Boolean,
	open: Boolean,
	onToggle: () -> Unit,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val all = state.modelExpressions()
	val index = all.indexOfFirst { it.id == expression.id }
	SidecarRow(
		title = expression.name,
		open = open,
		onToggle = onToggle,
		onRename = { viewModel.renameExpression(expression.id, it) },
		menu = { dismiss, rename ->
			CompactMenuItem(text = tr("expression.preview"), onClick = { dismiss(); viewModel.previewExpression(expression.id) })
			CompactMenuItem(text = tr("expression.capture"), onClick = { dismiss(); viewModel.captureExpression(expression.id) })
			CompactMenuItem(text = tr("animation.rename"), onClick = rename)
			CompactMenuItem(text = tr("animation.duplicate"), onClick = { dismiss(); viewModel.duplicateExpression(expression.id) })
			CompactMenuDivider()
			CompactMenuItem(text = tr("expression.moveUp"), enabled = index > 0, onClick = { dismiss(); viewModel.moveExpression(expression.id, index - 1) })
			CompactMenuItem(text = tr("expression.moveDown"), enabled = index in 0 until all.lastIndex, onClick = { dismiss(); viewModel.moveExpression(expression.id, index + 1) })
			CompactMenuDivider()
			CompactMenuItem(text = tr("expression.resetStandard"), enabled = !standard, onClick = { dismiss(); viewModel.resetExpressions() })
			CompactMenuItem(text = tr("animation.delete"), danger = true, onClick = { dismiss(); viewModel.deleteExpression(expression.id) })
		},
		trailing = {
			Text(
				text = tr("expression.entryCount", expression.entries.size),
				style = typography.monoSmall.copy(fontSize = 9.5.sp),
				color = colors.textMuted,
				maxLines = 1,
			)
			Spacer(Modifier.width(6.dp))
			CompactIconButton(onClick = { viewModel.previewExpression(expression.id) }, size = 18.dp, tooltip = tr("expression.preview")) {
				IconEye(visible = true, modifier = Modifier.size(11.dp), tint = colors.accent)
			}
		},
	) {
		Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
			SettingField(tr("animation.fadeInTime"), expression.fadeIn, 0f, ModelExpression.MAX_FADE, 0.1, 1, "", true, Modifier.weight(1f), viewModel) {
				viewModel.setExpressionFades(expression.id, fadeIn = it)
			}
			SettingField(tr("animation.fadeOutTime"), expression.fadeOut, 0f, ModelExpression.MAX_FADE, 0.1, 1, "", true, Modifier.weight(1f), viewModel) {
				viewModel.setExpressionFades(expression.id, fadeOut = it)
			}
		}
		val parameters = state.previewModel?.rig?.puppet?.parameters.orEmpty()
		val names = parameters.associate { it.id.raw to it.name.ifBlank { it.id.raw } }
		for (entry in expression.entries) ExpressionEntryRow(viewModel, expression.id, entry, names[entry.parameterId] ?: entry.parameterId)
		Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
			Box {
				var adding by remember { mutableStateOf(false) }
				CompactButton(text = tr("expression.addParameter"), onClick = { adding = true }, height = 20.dp)
				TreeContextMenu(expanded = adding, onDismissRequest = { adding = false }, minWidth = 180.dp) {
					for (parameter in parameters.filter { expression.entry(it.id.raw) == null }) {
						CompactMenuItem(text = names.getValue(parameter.id.raw), onClick = {
							adding = false
							viewModel.setExpressionEntry(expression.id, parameter.id.raw, blend = ExpressionBlend.ADD)
						})
					}
				}
			}
			CompactButton(text = tr("expression.capture"), onClick = { viewModel.captureExpression(expression.id) }, height = 20.dp)
		}
	}
}

/** An entry: its parameter, the blend (a menu of the three) and the value; the cross removes it. */
@Composable
private fun ExpressionEntryRow(viewModel: PSD2LiveViewModel, expressionId: String, entry: ExpressionEntry, name: String) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
		Text(
			text = name,
			style = typography.caption.copy(fontSize = 10.sp),
			color = colors.textPrimary,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
			modifier = Modifier.weight(1f),
		)
		Box {
			var choosing by remember { mutableStateOf(false) }
			CompactButton(text = blendLabel(entry.blend), onClick = { choosing = true }, height = 20.dp)
			TreeContextMenu(expanded = choosing, onDismissRequest = { choosing = false }, minWidth = 120.dp) {
				for (blend in ExpressionBlend.entries) {
					CompactMenuItem(text = blendLabel(blend), active = blend == entry.blend, onClick = {
						choosing = false
						if (blend != entry.blend) viewModel.setExpressionEntry(expressionId, entry.parameterId, blend = blend)
					})
				}
			}
		}
		CompactNumberSpinner(
			value = entry.value.toDouble(),
			onValueChange = { viewModel.setExpressionEntry(expressionId, entry.parameterId, value = it.toFloat()) },
			min = -1000.0,
			max = 1000.0,
			step = 0.05,
			decimals = 3,
			height = 20.dp,
			modifier = Modifier.width(64.dp),
			onEditStart = { viewModel.beginEditorField(MOTION_SETTINGS_FIELD) },
			onEditEnd = { viewModel.endEditorField(MOTION_SETTINGS_FIELD) },
		)
		CompactIconButton(onClick = { viewModel.setExpressionEntry(expressionId, entry.parameterId, remove = true) }, size = 18.dp, tooltip = tr("expression.removeEntry")) {
			IconClose(modifier = Modifier.size(8.dp), tint = colors.textMuted)
		}
	}
}

/** A pose group: its parts in order (the first shows at load) with their links, a part added from the free ones. */
@Composable
private fun PoseGroupRow(
	viewModel: PSD2LiveViewModel,
	index: Int,
	parts: List<Pair<String, List<String>>>,
	names: Map<String, String>,
	free: List<Pair<String, String>>,
	open: Boolean,
	onToggle: () -> Unit,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	fun name(id: String) = names[id]?.ifBlank { id } ?: tr("pose.missingPart", id)
	SidecarRow(
		title = tr("pose.group", index + 1) + " · " + parts.joinToString(" | ") { name(it.first) },
		open = open,
		onToggle = onToggle,
		onRename = null,
		menu = { dismiss, _ -> CompactMenuItem(text = tr("animation.delete"), danger = true, onClick = { dismiss(); viewModel.deletePoseGroup(index) }) },
		trailing = {
			Text(text = tr("pose.partCount", parts.size), style = typography.monoSmall.copy(fontSize = 9.5.sp), color = colors.textMuted, maxLines = 1)
			Spacer(Modifier.width(4.dp))
		},
	) {
		parts.forEachIndexed { position, (part, links) ->
			Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
				Text(
					text = name(part) + if (links.isEmpty()) "" else "  + " + links.joinToString(", ", transform = ::name),
					style = typography.caption.copy(fontSize = 10.sp),
					color = if (part in names) colors.textPrimary else colors.warning,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
					modifier = Modifier.weight(1f),
				)
				if (position == 0) MotionBadge(tr("pose.shownFirst"), colors.accent)
				Box {
					var linking by remember { mutableStateOf(false) }
					CompactButton(text = tr("pose.link"), onClick = { linking = true }, enabled = free.isNotEmpty() || links.isNotEmpty(), height = 20.dp)
					TreeContextMenu(expanded = linking, onDismissRequest = { linking = false }, minWidth = 180.dp) {
						for (link in links) CompactMenuItem(text = tr("pose.unlink", name(link)), onClick = {
							linking = false
							viewModel.setPoseGroup(index, parts.map { (p, l) -> p to if (p == part) l - link else l })
						})
						if (links.isNotEmpty() && free.isNotEmpty()) CompactMenuDivider()
						for ((id, partName) in free) CompactMenuItem(text = partName.ifBlank { id }, onClick = {
							linking = false
							viewModel.setPoseGroup(index, parts.map { (p, l) -> p to if (p == part) l + id else l })
						})
					}
				}
				CompactIconButton(onClick = { viewModel.setPoseGroup(index, parts.filterNot { it.first == part }) }, size = 18.dp, tooltip = tr("pose.removePart")) {
					IconClose(modifier = Modifier.size(8.dp), tint = colors.textMuted)
				}
			}
		}
		Box {
			var adding by remember { mutableStateOf(false) }
			CompactButton(text = tr("pose.addPart"), onClick = { adding = true }, enabled = free.isNotEmpty(), height = 20.dp)
			TreeContextMenu(expanded = adding, onDismissRequest = { adding = false }, minWidth = 180.dp) {
				for ((id, partName) in free) CompactMenuItem(text = partName.ifBlank { id }, onClick = {
					adding = false
					viewModel.setPoseGroup(index, parts + (id to emptyList()))
				})
			}
		}
	}
}

/** Below the groups: a new group from a free part, and the fade every switch takes. */
@Composable
private fun PoseFooter(viewModel: PSD2LiveViewModel, pose: ModelPose?, free: List<Pair<String, String>>) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	Column(Modifier.fillMaxWidth().padding(start = MotionRowIndent + 4.dp, end = 6.dp, top = 4.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
		if (pose == null) Text(text = tr("pose.empty"), style = typography.caption.copy(fontSize = 10.5.sp), color = colors.textMuted)
		Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
			Box {
				var adding by remember { mutableStateOf(false) }
				CompactButton(text = tr("pose.addGroup"), onClick = { adding = true }, enabled = free.isNotEmpty(), height = 20.dp)
				TreeContextMenu(expanded = adding, onDismissRequest = { adding = false }, minWidth = 180.dp) {
					for ((id, partName) in free) CompactMenuItem(text = partName.ifBlank { id }, onClick = { adding = false; viewModel.addPoseGroup(id) })
				}
			}
			if (pose != null) SettingField(tr("pose.fadeIn"), pose.fadeIn, 0f, ModelExpression.MAX_FADE, 0.1, 1, "", true, Modifier.weight(1f), viewModel) {
				viewModel.setPoseFade(it)
			}
		}
	}
}

/** A read-only overview row: what, where and the value a host receives; a click goes to it when it can. */
@Composable
private fun OverviewRow(title: String, place: String, value: String, onClick: (() -> Unit)?) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val interaction = remember { MutableInteractionSource() }
	val hovered by interaction.collectIsHoveredAsState()
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.height(MotionRowHeight)
			.background(if (hovered && onClick != null) colors.controlHover.copy(alpha = 0.35f) else Color.Transparent)
			.hoverable(interaction)
			.then(if (onClick != null) Modifier.clickable(interactionSource = interaction, indication = null, onClick = onClick).handCursor() else Modifier)
			.padding(start = MotionRowIndent + 4.dp, end = 6.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(6.dp),
	) {
		Text(text = title, style = typography.caption.copy(fontSize = 10.5.sp), color = colors.textPrimary, maxLines = 1,
			overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(0.4f))
		Text(text = place, style = typography.monoSmall.copy(fontSize = 9.5.sp), color = colors.textMuted, maxLines = 1)
		Text(text = value, style = typography.monoSmall.copy(fontSize = 9.5.sp), color = colors.accent, maxLines = 1,
			overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(0.6f))
	}
}

/** A user clip's events in time order: time and value editable, a new one at the playhead. */
@Composable
internal fun MotionEventSettings(viewModel: PSD2LiveViewModel, clip: MotionClip) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	Text(text = tr("animation.events"), style = typography.caption.copy(fontSize = 10.sp), color = colors.textMuted)
	for (event in clip.events) MotionEventRow(viewModel, clip, event)
	CompactButton(
		text = tr("animation.eventAdd"),
		onClick = { viewModel.setMotionEvent(clip.id, newMotionEventValue(clip.name, clip.events.size)) },
		enabled = clip.events.size < MotionEvent.MAX_PER_CLIP,
		height = 20.dp,
	)
}

@Composable
private fun MotionEventRow(viewModel: PSD2LiveViewModel, clip: MotionClip, event: MotionEvent) {
	val colors = LocalToolColors.current
	var draft by remember(event) { mutableStateOf(event.value) }
	fun commit() {
		val value = draft.take(MotionEvent.MAX_VALUE_LENGTH)
		if (value.isNotEmpty() && value != event.value) viewModel.setMotionEvent(clip.id, value, event.time, fromTime = event.time)
		else draft = event.value
	}
	Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
		CompactNumberSpinner(
			value = event.time.toDouble(),
			onValueChange = { viewModel.setMotionEvent(clip.id, event.value, it.toFloat(), fromTime = event.time) },
			min = 0.0,
			max = clip.duration.toDouble(),
			step = 1.0 / clip.fps,
			decimals = 2,
			unit = "s",
			height = 20.dp,
			modifier = Modifier.width(64.dp),
			onEditStart = { viewModel.beginEditorField(MOTION_SETTINGS_FIELD) },
			onEditEnd = { viewModel.endEditorField(MOTION_SETTINGS_FIELD) },
		)
		CompactTextField(
			value = draft,
			onValueChange = { draft = it },
			modifier = Modifier.weight(1f),
			height = 20.dp,
			placeholder = tr("animation.eventValue"),
			onCommit = { commit() },
			onFocusLost = { commit() },
		)
		CompactIconButton(onClick = { viewModel.deleteMotionEvent(clip.id, event.time) }, size = 18.dp, tooltip = tr("animation.eventDelete")) {
			IconClose(modifier = Modifier.size(8.dp), tint = colors.textMuted)
		}
	}
}
