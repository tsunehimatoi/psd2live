package io.github.psd2live.core

import kotlinx.serialization.json.*

/** Where a physics group comes from. Every generated group can be replaced or turned off. */
enum class PhysicsOrigin { PRESET, SKELETON, SWING, SIMULATION, INTERIOR, CUSTOM, MODIFIER }

/** Why a group does not reach the model. */
data class PhysicsIssue(val code: Code, val parameter: String? = null, val group: String? = null) {
    enum class Code { MISSING_PARAMETER, NO_INPUT, NO_OUTPUT, FEEDBACK, OUTPUT_TAKEN }

    val message: String get() = when (code) {
        Code.MISSING_PARAMETER -> "Parameter $parameter does not exist"
        Code.NO_INPUT -> "No input parameter"
        Code.NO_OUTPUT -> "No output parameter"
        Code.FEEDBACK -> "Parameter $parameter is both input and output"
        Code.OUTPUT_TAKEN -> "Output $parameter is already driven by $group"
    }
}

/**
 * One row of the physics catalog. [setting] is what exports: the user's replacement when there is one,
 * else [generated]. [shadowedBy] names the user group that drives this generated group's outputs instead.
 */
data class PhysicsGroup(
    val setting: RigPhysicsEdit,
    val origin: PhysicsOrigin,
    val generated: RigPhysicsEdit?,
    val enabled: Boolean,
    val issue: PhysicsIssue? = null,
    val shadowedBy: String? = null,
) {
    val id: String get() = setting.id
    /** A generated group the user replaced. */
    val overridden: Boolean get() = origin != PhysicsOrigin.CUSTOM && generated != null && generated != setting
    val active: Boolean get() = enabled && issue == null && shadowedBy == null

    fun toJson() = buildJsonObject {
        put("origin", origin.name.lowercase())
        put("enabled", enabled)
        if (overridden) put("overridden", true)
        put("active", active)
        issue?.let { put("issue", it.message) }
        shadowedBy?.let { put("replaced_by", it) }
        setting.toJson().forEach { (k, v) -> put(k, v) }
    }
}
