package io.github.psd2live.ui.views

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Divider
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.psd2live.core.RigPreviewModel
import io.github.psd2live.core.modifier.BuiltinModifierPlugins
import io.github.psd2live.core.modifier.ModifierPluginReference
import io.github.psd2live.core.modifier.ModifierPluginStore
import io.github.psd2live.core.modifier.ModifierStatus
import io.github.psd2live.core.modifier.RigModifierInstance
import io.github.psd2live.core.modifier.RigModifierStack
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography
import io.github.psd2live.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

internal data class PresetModifierRow(val instance: RigModifierInstance, val status: ModifierStatus?)

/** Keep even inactive modifiers and group only identical captured package references. */
internal fun groupPresetModifiers(
    stack: RigModifierStack,
    statuses: Map<String, ModifierStatus>,
): Map<ModifierPluginReference?, List<PresetModifierRow>> =
    stack.instances.map { PresetModifierRow(it, statuses[it.id]) }.groupBy { (instance) ->
        instance.plugin?.takeUnless {
            it.id == "builtin" || BuiltinModifierPlugins.isHistoricalBuiltinReference(instance.type, instance.version, it)
        } ?: BuiltinModifierPlugins.reference(instance.type, instance.version)
    }

@Composable
internal fun ModifierPresetContents(model: RigPreviewModel) {
    val evaluation = model.modifierEvaluation
    val groups = remember(model, evaluation) {
        val stack = evaluation?.stack ?: model.config.rigEdits.modifierStack?.let(RigModifierStack::fromJson) ?: RigModifierStack()
        groupPresetModifiers(stack, evaluation?.entries.orEmpty().associate { it.instance.id to it.status })
    }
    val count = groups.values.sumOf { it.size }
    val active = groups.values.sumOf { rows -> rows.count { it.status == ModifierStatus.ACTIVE } }
    val colors = LocalToolColors.current
    val typography = LocalToolTypography.current
    Text(tr("modifier.preset.contents.counts", groups.keys.count { it != null }, count, active),
        color = colors.textPrimary, style = typography.caption)

    if (count == 0) return
    RememberedModifierSection("psd-guide-preset-plugins", tr("modifier.preset.contents.list"), defaultExpanded = false) {
        val packageNames by produceState(emptyMap<ModifierPluginReference, String>(), groups.keys) {
            if (groups.keys.any { it != null && !it.id.startsWith("builtin.") }) {
                value = withContext(Dispatchers.IO) {
                    ModifierPluginStore.shared.packages().associate { it.reference to it.manifest.name }
                }
            }
        }
        groups.entries.forEachIndexed { index, (reference, rows) ->
            if (index > 0) Divider(color = colors.divider, thickness = 0.5.dp)
            val builtin = reference?.id?.startsWith("builtin.") == true
            val standalone = rows.singleOrNull().takeIf { builtin }
            val title = when {
                standalone != null -> standalone.label()
                reference == null -> tr("modifier.preset.contents.unresolved")
                builtin -> modifierName(rows.first().instance.type)
                else -> packageNames[reference] ?: reference.id
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.Gap),
                verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), color = colors.textPrimary,
                    style = typography.caption.copy(fontWeight = FontWeight.SemiBold), maxLines = 2,
                    overflow = TextOverflow.Ellipsis)
                reference?.let { Text(it.version, color = colors.textMuted, style = typography.caption) }
                standalone?.let { ModifierStatusBadge(it.status?.name?.lowercase() ?: "loading") }
            }
            if (standalone != null) return@forEachIndexed
            rows.forEach { row ->
                Row(Modifier.fillMaxWidth().padding(start = Spacing.Margin),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.Gap), verticalAlignment = Alignment.CenterVertically) {
                    Text(row.label(), Modifier.weight(1f), color = colors.textPrimary, style = typography.caption,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    ModifierStatusBadge(row.status?.name?.lowercase() ?: "loading")
                }
            }
        }
    }
}

private fun PresetModifierRow.label(): String =
    instance.config["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: modifierName(instance.type)
