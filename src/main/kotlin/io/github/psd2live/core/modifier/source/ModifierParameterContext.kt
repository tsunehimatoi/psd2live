package io.github.psd2live.core.modifier.source

import io.github.psd2live.core.modifier.ModifierParameterGroups
import io.github.psd2live.core.modifier.ModifierParameterI18n
import io.github.psd2live.i18n.tr

/** Host-side builder for standard parameter group and optional parameter-name localization overrides. */
internal object ModifierParameterContext {
    fun standardGroupNames(): Map<String, String> = mapOf(
        ModifierParameterGroups.EYES to tr("model.group.eyes"),
        ModifierParameterGroups.BROWS to tr("model.group.brows"),
        ModifierParameterGroups.FACE to tr("model.group.face"),
        ModifierParameterGroups.BODY to tr("model.group.body"),
        ModifierParameterGroups.MOUTH to tr("model.group.mouth"),
        ModifierParameterGroups.PHYSICS to tr("model.group.physics"),
    )

    fun i18n(parameterNames: Map<String, String> = emptyMap()): ModifierParameterI18n =
        ModifierParameterI18n(groupNames = standardGroupNames(), parameterNames = parameterNames)
}
