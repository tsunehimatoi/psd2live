package io.github.psd2live.ui.views

import androidx.compose.foundation.layout.*
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.core.*
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.components.*
import io.github.psd2live.ui.state.PSD2LiveState
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography
import io.github.psd2live.ui.views.physics.CardSubsection
import io.github.psd2live.ui.views.physics.FieldLabel
import java.util.Locale
import org.umamo.runtime.model.PuppetModel

/**
 * A swing's settings, as its card in the modifier stack shows them: its directions and preset, where it bends from,
 * each direction's segments, shape and pendulum. Every change is one `swing_put`; sliders commit on release. The
 * canvas session ([PSD2LiveViewModel.beginSwing]) sets the same values by handles.
 */
@Composable
internal fun SwingSettings(vm: PSD2LiveViewModel, state: PSD2LiveState, puppet: PuppetModel, swing: RigSwingEdit) {
	val colors = LocalToolColors.current
	val caption = LocalToolTypography.current.caption.copy(fontSize = 9.5.sp)
	fun commit(next: RigSwingEdit) {
		if (next != swing) runCatching { vm.modifiers.batch(listOf("swing_put" to SwingAuthoring.arguments(next))) }
			.onFailure { vm.setStatusText(it.message ?: tr("swing.failed")) }
	}
	fun change(next: () -> RigSwingEdit) = runCatching(next).onSuccess(::commit).onFailure { vm.setStatusText(it.message ?: tr("swing.failed")) }
	var open by remember(swing.id) { mutableStateOf(setOf("shape0", "shape1")) }
	fun toggle(key: String) { open = if (key in open) open - key else open + key }

	Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
		FieldLabel(tr("swing.kind"))
		val kinds = swing.motions.map { it.kind }
		// Both directions can be on at once; at least one stays.
		for (k in SwingKind.entries) CompactToggleChip(tr("swing.kind.${k.name.lowercase()}"), k in kinds, {
			val next = if (k in kinds) kinds - k else kinds + k
			if (next.isNotEmpty()) change { SwingAuthoring.withKinds(puppet, state.rigEdits, swing, next) }
		}, height = 20.dp)
	}
	Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
		FieldLabel(tr("swing.preset"))
		CompactDropdown(SwingPreset.entries, swing.preset, { preset ->
			change {
				SwingAuthoring.resized(puppet, swing.copy(preset = preset, motions = swing.motions.map {
					it.copy(shape = SwingPresets.shape(preset, it.kind).copy(flip = it.shape.flip))
				}))
			}
		}, Modifier.weight(1f), itemLabel = { tr("swing.preset.${it.name.lowercase()}") }, height = 22.dp)
	}
	Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
		FieldLabel(tr("swing.fulcrum"))
		CompactDropdown(SwingFulcrum.entries, swing.fulcrum, { commit(swing.copy(fulcrum = it)) }, Modifier.weight(1f),
			itemLabel = { tr("swing.fulcrum.${it.name.lowercase()}") }, height = 22.dp)
	}
	StackSlider(tr("swing.tilt"), swing.tilt, -RigSwingEdit.MAX_TILT..RigSwingEdit.MAX_TILT, "%+.1f°") { commit(swing.copy(tilt = it)) }
	StackSlider(tr("swing.offsetAlong"), swing.offsetAlong, -RigSwingEdit.MAX_OFFSET..RigSwingEdit.MAX_OFFSET, "%+.2f") { commit(swing.copy(offsetAlong = it)) }
	StackSlider(tr("swing.offsetAcross"), swing.offsetAcross, -RigSwingEdit.MAX_OFFSET..RigSwingEdit.MAX_OFFSET, "%+.2f") { commit(swing.copy(offsetAcross = it)) }
	CompactCheckbox(swing.hasPhysics, { on ->
		change {
			val next = swing.withPhysics { _, _ -> if (on) SwingPhysics() else null }
			if (on) SwingAuthoring.resized(puppet, next) else next
		}
	}, label = tr("swing.physics.enabled"))

	swing.motions.forEachIndexed { m, motion ->
		val kind = tr("swing.kind.${motion.kind.name.lowercase()}")
		CardSubsection(if (swing.motions.size > 1) "${tr("swing.direction")} · $kind" else tr("swing.direction"), "shape$m" in open, { toggle("shape$m") }) {
			fun shape(next: SwingShape) = commit(swing.copy(motions = swing.motions.toMutableList().also { it[m] = motion.copy(shape = next) }))
			Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
				FieldLabel(tr("swing.segments"))
				for (n in 1..RigSwingEdit.MAX_SEGMENTS) CompactToggleChip("$n", motion.segments == n, {
					if (motion.segments != n) change { SwingAuthoring.withSegments(puppet, state.rigEdits, swing, m, n) }
				}, showCheckWhenSelected = false, height = 20.dp)
			}
			val s = motion.shape
			StackSlider(tr("swing.magnitude"), s.magnitude, 0f..RigSwingEdit.MAX_MAGNITUDE) { shape(s.copy(magnitude = it)) }
			StackSlider(tr("swing.lift"), s.lift, -0.5f..0.5f, "%+.2f") { shape(s.copy(lift = it)) }
			StackSlider(tr("swing.softness"), s.softness, 0f..1f) { shape(s.copy(softness = it)) }
			StackSlider(tr("swing.zoom"), s.zoom, -0.5f..0.5f, "%+.2f") { shape(s.copy(zoom = it)) }
			StackSlider(tr("swing.parallel"), s.parallel, 0f..1f) { shape(s.copy(parallel = it)) }
			CompactCheckbox(s.flip, { shape(s.copy(flip = it)) }, label = tr("swing.flip"))
			val physics = motion.physics ?: return@CardSubsection
			// A pendulum has no shape on the canvas: these set the direction's own.
			fun set(next: SwingPhysics) = commit(swing.withPhysics(m) { _, _ -> next })
			Text(tr("swing.physics"), style = caption, color = colors.textMuted)
			StackSlider(tr("physics.length"), physics.length, 1f..30f) { set(physics.copy(length = it)) }
			StackSlider(tr("physics.shakiness"), physics.mobility, 0f..1f) { set(physics.copy(mobility = it)) }
			StackSlider(tr("physics.reactionSpeed"), physics.delay, 0.1f..3f) { set(physics.copy(delay = it)) }
			StackSlider(tr("physics.convergenceSpeed"), physics.acceleration, 0f..5f) { set(physics.copy(acceleration = it)) }
			StackSlider(tr("physics.outputScale"), physics.outputScale, 0.1f..5f) { set(physics.copy(outputScale = it)) }
			Text(tr(if (motion.kind == SwingKind.LATERAL) "swing.physics.inputs.lateral" else "swing.physics.inputs.vertical"), style = caption, color = colors.textMuted)
			// A version edited in the physics panel replaces this pendulum until it is reset there.
			if (state.rigEdits.physicsEdits.any { it.id == PhysicsGenerator.swingPhysicsId(swing, motion.kind) })
				Text(tr("swing.physics.overridden"), style = caption, color = colors.warning)
		}
	}
}

/** A labelled slider moving a local draft, committed once on release, with its value beside it in [format]. */
@Composable
internal fun StackSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, format: String = "%.2f", onCommit: (Float) -> Unit) {
	val colors = LocalToolColors.current
	var draft by remember(value) { mutableStateOf(value) }
	Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
		FieldLabel(label.trimEnd(':', ' ', '：'))
		CompactSlider(draft, { draft = (it * 100f).toInt() / 100f }, onValueChangeFinished = { if (draft != value) onCommit(draft) },
			modifier = Modifier.weight(1f), valueRange = range)
		Text(String.format(Locale.US, format, draft), style = LocalToolTypography.current.monoSmall, color = colors.textPrimary, modifier = Modifier.width(40.dp))
	}
}
