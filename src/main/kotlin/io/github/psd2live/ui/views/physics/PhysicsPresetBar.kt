package io.github.psd2live.ui.views.physics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.core.PhysicsPresets
import io.github.psd2live.core.RigPhysicsEdit
import io.github.psd2live.application.WorkspacePhysicsPresetEntry
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.components.CompactButton
import io.github.psd2live.ui.components.CompactDropdown
import io.github.psd2live.ui.components.CompactIconButton
import io.github.psd2live.ui.components.CompactMenuDivider
import io.github.psd2live.ui.components.CompactMenuItem
import io.github.psd2live.ui.components.CompactTextField
import io.github.psd2live.ui.components.TreeContextMenu
import io.github.psd2live.ui.state.PhysicsPresetStore
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography

/**
 * A preset line as in Cubism Editor: pick a preset and apply it to the group, or keep the group's current
 * inputs (or pendulums) as a preset of your own - save as new, overwrite, rename, delete. Built-in presets
 * can be applied and saved over as a new name, not changed.
 */
@Composable
internal fun PhysicsPresetBar(kind: PhysicsPresets.Kind, groupId: String, setting: RigPhysicsEdit, onApply: (PhysicsPresets.Preset) -> Unit) {
	val colors = LocalToolColors.current
	val library by PhysicsPresetStore.state.collectAsState()
	val presets = PhysicsPresets.builtins(kind).mapIndexed { index, preset ->
		WorkspacePhysicsPresetEntry("builtin:${kind.name.lowercase()}:$index", preset)
	} + library.entries.filter { it.preset.kind == kind }
	// Each group starts from the preset its settings match, so the back hair's group shows the back hair preset
	// rather than whichever preset another group last picked.
	var chosenId by remember(kind, groupId) {
		mutableStateOf(presets.firstOrNull { PhysicsPresets.matches(it.preset, setting) }?.id)
	}
	val chosen = presets.firstOrNull { chosenId == it.id } ?: presets.first()
	// Naming: null, or whether the typed name saves a new preset (true) or renames the chosen one (false).
	var naming by remember(kind, groupId) { mutableStateOf<Boolean?>(null) }
	var draft by remember(kind, groupId) { mutableStateOf("") }
	var menu by remember { mutableStateOf(false) }
	var namingState by remember(kind, groupId) { mutableStateOf(library.state) }
	var namingId by remember(kind, groupId) { mutableStateOf(chosen.id) }
	var namingSetting by remember(kind, groupId) { mutableStateOf(setting) }
	var error by remember(kind, groupId) { mutableStateOf<String?>(null) }
	fun update(action: () -> Unit) {
		try { action(); error = null }
		catch (failure: Exception) { error = tr("physics.preset.failed", failure.message ?: failure.javaClass.simpleName) }
	}

	fun commit() {
		val name = draft.trim()
		update {
			if (name.isNotEmpty()) {
				val saved = if (naming == true) PhysicsPresetStore.save(namingState, PhysicsPresets.capture(kind, name, namingSetting))
				else PhysicsPresetStore.rename(namingState, namingId, name)
				chosenId = saved.id
			}
			naming = null
		}
	}

	Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
		FieldLabel(tr("physics.preset"))
		if (naming != null) {
			CompactTextField(draft, { draft = it }, modifier = Modifier.weight(1f), height = 20.dp, selectAllOnFocus = true,
				placeholder = tr("physics.preset.namePlaceholder"), onCommit = ::commit, onFocusLost = { if (naming != null) commit() })
		} else {
			CompactDropdown(presets, chosen, { chosenId = it.id }, modifier = Modifier.weight(1f), height = 20.dp,
				itemLabel = { if (it.preset.builtin) "${it.preset.name} · ${tr("physics.preset.builtin")}" else it.preset.name })
			CompactButton(tr("physics.preset.apply"), { onApply(chosen.preset) }, height = 20.dp)
		}
		Box {
			CompactIconButton(onClick = { menu = true }, size = 20.dp, tooltip = tr("physics.more")) {
				Text("⋯", style = LocalToolTypography.current.body.copy(fontSize = 12.sp), color = colors.textMuted)
			}
			TreeContextMenu(expanded = menu, onDismissRequest = { menu = false }) {
				CompactMenuItem(tr("physics.preset.saveAs"), { menu = false; draft = ""; namingState = library.state; namingSetting = setting; naming = true })
				CompactMenuItem(tr("physics.preset.overwrite"), {
					menu = false
					update { PhysicsPresetStore.save(library.state, PhysicsPresets.capture(kind, chosen.preset.name, setting)) }
				}, enabled = !chosen.preset.builtin)
				CompactMenuItem(tr("physics.rename"), { menu = false; draft = chosen.preset.name; namingState = library.state; namingId = chosen.id; naming = false }, enabled = !chosen.preset.builtin)
				CompactMenuDivider()
				CompactMenuItem(tr("physics.delete"), { menu = false; update { PhysicsPresetStore.delete(library.state, chosen.id); chosenId = null } },
					enabled = !chosen.preset.builtin, danger = true)
			}
		}
	}
	error?.let { Text(it, color = colors.textMuted, style = LocalToolTypography.current.body) }
}
