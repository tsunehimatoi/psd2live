package io.github.psd2live.ui.state

import io.github.psd2live.core.modifier.ModifierTarget
import kotlinx.serialization.json.JsonObject

/** Process-only dialog state. The input state and target references are captured once, before preview. */
internal data class ModifierAddition(
    val state: String,
    val targets: List<ModifierTarget>,
    val type: String? = null,
    val group: String? = null,
    val configuration: JsonObject? = null,
    val plan: JsonObject? = null,
    val preview: JsonObject? = null,
    val loading: Boolean = false,
    val committing: Boolean = false,
    val error: String? = null,
)
