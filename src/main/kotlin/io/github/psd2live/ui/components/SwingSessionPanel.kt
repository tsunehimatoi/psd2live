package io.github.psd2live.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.core.RigSwingEdit
import io.github.psd2live.core.SwingFulcrum
import io.github.psd2live.core.SwingKind
import io.github.psd2live.core.SwingPhysics
import io.github.psd2live.core.SwingPreset
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography
import io.github.psd2live.ui.theme.frostedGlass

/**
 * The swing session's companion panel in the workspace or a separate window. Everything with a place on the canvas is
 * set there by its handle; this holds only the choices that have none, and the values as a readout.
 */
@Composable
internal fun SwingSessionPanel(
    viewModel: PSD2LiveViewModel,
    session: PSD2LiveViewModel.SwingSession,
    targetLabel: (String) -> String,
    selectionTargets: () -> List<String>,
    focus: () -> Unit,
    titleModifier: Modifier = Modifier,
    windowActions: @Composable () -> Unit = {},
) {
    val colors = LocalToolColors.current
    val typography = LocalToolTypography.current
    val draft = session.draft
    val existing = session.existingId != null
    val motion = session.motion.coerceIn(0, draft.motions.size - 1)
    val shown = draft.motions[motion]
    val kind = shown.kind
    var collapsed by remember(session) { mutableStateOf(false) }
    var physicsOpen by remember(session) { mutableStateOf(false) }
    fun update(change: (RigSwingEdit) -> RigSwingEdit) {
        runCatching { change(draft) }.onSuccess(viewModel::updateSwing).onFailure { session.error = it.message }
        focus()
    }
    fun act(action: () -> Unit) { action(); focus() }

    Column(
        modifier = Modifier
            .width(250.dp)
            .frostedGlass(shape = RoundedCornerShape(6.dp), isHovered = true, elevation = 6.dp, alpha = 0.94f)
            .border(BorderStroke(1.dp, colors.border.copy(alpha = 0.85f)), RoundedCornerShape(6.dp))
            .padding(horizontal = 9.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            IconWarpDeformer(tint = colors.accent, modifier = Modifier.size(13.dp))
            Text(
                text = "${tr(if (existing) "swing.title.edit" else "swing.title.create")} · ${draft.name}",
                color = colors.textPrimary,
                style = typography.body.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
                modifier = Modifier.weight(1f).then(titleModifier).clickable { collapsed = !collapsed },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            windowActions()
            CompactIconButton(onClick = { collapsed = !collapsed }, size = 18.dp, tooltip = tr(if (collapsed) "swing.expand" else "swing.collapse")) {
                IconChevron(expanded = !collapsed, tint = colors.textMuted, modifier = Modifier.size(10.dp))
            }
            CompactIconButton(onClick = { if (!session.busy) { viewModel.endSwing(); focus() } }, size = 18.dp, tooltip = viewModel.state.value.keymap.labelFor(io.github.psd2live.ui.state.ShortcutAction.CANCEL)?.let { "${tr("swing.cancel")} ($it)" } ?: tr("swing.cancel")) {
                IconClose(tint = colors.textMuted, modifier = Modifier.size(10.dp))
            }
        }
        if (collapsed) return@Column

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                draft.targets.joinToString(", ", transform = targetLabel),
                style = typography.caption.copy(fontSize = 9.5.sp), color = colors.textMuted,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            CompactButton(tr("swing.registerSelection"), {
                update { it.copy(targets = (it.targets + selectionTargets()).distinct()) }
            }, height = 20.dp)
        }
        Text(tr("swing.canvasHint"), style = typography.caption.copy(fontSize = 9.5.sp), color = colors.textMuted)

        // Both directions can be on at once; at least one stays.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Label(tr("swing.kind"))
            val kinds = draft.motions.map { it.kind }
            SwingKind.entries.forEach { k ->
                CompactToggleChip(tr("swing.kind.${k.name.lowercase()}"), k in kinds, {
                    val next = if (k in kinds) kinds - k else kinds + k
                    if (next.isNotEmpty()) act { viewModel.setSwingKinds(next) }
                }, height = 20.dp)
            }
        }
        if (draft.motions.size > 1) {
            Choice(tr("swing.editing"), draft.motions.indices.toList(), motion, { tr("swing.kind.${draft.motions[it].kind.name.lowercase()}") }) {
                act { viewModel.selectSwingMotion(it) }
            }
        }
        Choice(tr("swing.preset"), SwingPreset.entries, draft.preset, { tr("swing.preset.${it.name.lowercase()}") }) { preset ->
            act { viewModel.setSwingPreset(preset) }
        }
        Choice(tr("swing.segments"), listOf(1, 2, 3), draft.motions[motion].segments, { it.toString() }) { n ->
            act { viewModel.setSwingSegments(motion, n) }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Label(tr("swing.fulcrum"))
            CompactToggleChip(tr("swing.fulcrum.auto"), draft.fulcrum == SwingFulcrum.AUTO, { update { it.copy(fulcrum = SwingFulcrum.AUTO) } },
                showCheckWhenSelected = false)
            Text(
                if (draft.fulcrum == SwingFulcrum.AUTO) session.gizmo?.fulcrum?.let { tr("swing.fulcrum.${it.name.lowercase()}") }.orEmpty()
                else tr("swing.fulcrum.${draft.fulcrum.name.lowercase()}"),
                style = typography.caption, color = colors.textPrimary,
            )
        }
        // The rectangle turns by its ring and moves by its pinned midpoint on the canvas; these read and reset them.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Label(tr("swing.tilt"))
            Text("%+.1f°".format(draft.tilt), style = typography.monoSmall, color = colors.textPrimary, modifier = Modifier.weight(1f))
            CompactButton(tr("swing.tilt.reset"), { update { it.copy(tilt = 0f) } }, enabled = draft.tilt != 0f, height = 20.dp)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Label(tr("swing.offset"))
            Text("%+.2f, %+.2f".format(draft.offsetAlong, draft.offsetAcross), style = typography.monoSmall, color = colors.textPrimary,
                modifier = Modifier.weight(1f))
            CompactButton(tr("swing.tilt.reset"), { update { it.copy(offsetAlong = 0f, offsetAcross = 0f) } },
                enabled = draft.offsetAlong != 0f || draft.offsetAcross != 0f, height = 20.dp)
        }
        val shape = shown.shape
        Text(
            tr("swing.readout", "%.2f".format(shape.magnitude), "%+.2f".format(shape.lift), "%.2f".format(shape.softness), "%+.2f".format(shape.zoom),
                "%.2f".format(shape.parallel)) +
                if (shape.flip) " · ${tr("swing.flip")}" else "",
            style = typography.monoSmall, color = colors.textPrimary,
        )

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CompactCheckbox(draft.hasPhysics, { enabled -> act { viewModel.setSwingPhysicsEnabled(enabled) } },
                label = tr("swing.physics.enabled"), modifier = Modifier.weight(1f))
            if (draft.hasPhysics) {
                CompactIconButton(onClick = { physicsOpen = !physicsOpen }, size = 18.dp, tooltip = tr("swing.physics")) {
                    IconChevron(expanded = physicsOpen, tint = colors.textMuted, modifier = Modifier.size(10.dp))
                }
            }
        }
        val physics = shown.physics
        if (physics != null && physicsOpen) {
            // A pendulum has no shape on the canvas, so these stay sliders; they set the edited direction's.
            fun set(change: (SwingPhysics) -> SwingPhysics) = update { it.withPhysics(motion) { _, p -> p?.let(change) } }
            PhysicsRow(tr("physics.length"), physics.length, 1f..30f) { v -> set { it.copy(length = v) } }
            PhysicsRow(tr("physics.shakiness"), physics.mobility, 0f..1f) { v -> set { it.copy(mobility = v) } }
            PhysicsRow(tr("physics.reactionSpeed"), physics.delay, 0.1f..3f) { v -> set { it.copy(delay = v) } }
            PhysicsRow(tr("physics.convergenceSpeed"), physics.acceleration, 0f..5f) { v -> set { it.copy(acceleration = v) } }
            PhysicsRow(tr("physics.outputScale"), physics.outputScale, 0.1f..5f) { v -> set { it.copy(outputScale = v) } }
            Text(tr(if (kind == SwingKind.LATERAL) "swing.physics.inputs.lateral" else "swing.physics.inputs.vertical"),
                style = typography.caption.copy(fontSize = 9.sp), color = colors.textMuted)
            // A version edited in the physics panel replaces this pendulum until it is reset there.
            val groupId = io.github.psd2live.core.PhysicsGenerator.swingPhysicsId(draft, kind)
            if (viewModel.state.value.rigEdits.physicsEdits.any { it.id == groupId }) {
                Text(tr("swing.physics.overridden"), style = typography.caption.copy(fontSize = 9.sp), color = colors.warning)
            }
        }
        if (draft.baked) Text(tr("swing.bakedNote"), style = typography.caption.copy(fontSize = 9.5.sp), color = colors.textMuted)
        session.error?.let { Text(it, style = typography.caption.copy(fontSize = 9.5.sp), color = colors.error) }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            CompactButton(tr(if (session.playing) "swing.stop" else "swing.play"), { viewModel.playSwing(!session.playing); focus() }, height = 22.dp)
            Spacer(Modifier.weight(1f))
            CompactButton(tr(if (existing) "swing.apply" else "swing.create"), { viewModel.commitSwing(); focus() },
                enabled = !session.busy, isPrimary = true, height = 22.dp)
        }
        if (existing) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                if (!draft.baked) CompactButton(tr(if (io.github.psd2live.core.SwingCatalog.owner(viewModel.state.value.rigEdits, draft.id) != null) "modifier.apply" else "swing.bake"), { viewModel.deleteSwing(bake = true) }, enabled = !session.busy,
                    height = 20.dp, modifier = Modifier.weight(1f))
                CompactButton(tr("swing.delete"), { viewModel.deleteSwing(bake = false) }, enabled = !session.busy, danger = true,
                    height = 20.dp, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(text, style = LocalToolTypography.current.caption, color = LocalToolColors.current.textMuted, modifier = Modifier.width(48.dp))
}

@Composable
private fun <T> Choice(title: String, options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Label(title)
        options.forEach { option -> CompactToggleChip(label(option), option == selected, { onSelect(option) }, showCheckWhenSelected = false, height = 20.dp) }
    }
}

@Composable
private fun PhysicsRow(title: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    val colors = LocalToolColors.current
    val typography = LocalToolTypography.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title.trimEnd(':', ' '), style = typography.caption.copy(fontSize = 9.5.sp), color = colors.textMuted,
            modifier = Modifier.width(72.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        CompactSlider(value, { onChange((it * 100f).toInt() / 100f) }, modifier = Modifier.weight(1f), valueRange = range)
        Text("%.2f".format(value), style = typography.monoSmall, color = colors.textPrimary, modifier = Modifier.width(32.dp))
    }
}
