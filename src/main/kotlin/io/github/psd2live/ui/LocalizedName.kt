package io.github.psd2live.ui

import io.github.psd2live.core.LayerType
import io.github.psd2live.core.SemanticTag
import io.github.psd2live.core.Side
import io.github.psd2live.i18n.tr

/** Localized display names shared by the layer inspector and part-preset controls. */
internal fun SemanticTag.localizedName(): String = tr("semantic.${name.lowercase()}")

internal fun Side.localizedName(): String = tr("side.${name.lowercase()}")

internal fun LayerType.localizedName(): String = when (this) {
	LayerType.PRESET -> tr("layers.type.preset")
	LayerType.TOGGLE -> tr("layers.type.toggle")
	LayerType.SWITCH -> tr("layers.type.switch")
}
