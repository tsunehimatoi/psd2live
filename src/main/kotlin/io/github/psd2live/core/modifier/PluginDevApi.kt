package io.github.psd2live.core.modifier

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import java.nio.file.Path

internal object PluginDevErrorCodes {
    const val DEV_SOURCE_MISSING = "dev_source_missing"
    const val BUILD_FAILED = "build_failed"
    const val BUILD_TIMEOUT = "build_timeout"
    const val ARTIFACT_CHANGED = "artifact_changed"
    const val ARTIFACT_MISSING = "artifact_missing"
    const val PLUGIN_API_INCOMPATIBLE = "plugin_api_incompatible"
    const val PLUGIN_CONFIG_INCOMPATIBLE = "plugin_config_incompatible"
    const val PLUGIN_DEPENDENCY_INCOMPATIBLE = "plugin_dependency_incompatible"
    const val PLAN_EXPIRED = "plan_expired"
    const val STATE_CONFLICT = "state_conflict"
    const val REQUEST_ID_REUSED = "request_id_reused"
    const val PLUGIN_WORKER_FAILED = "plugin_worker_failed"
    const val PLUGIN_WORKER_TIMEOUT = "plugin_worker_timeout"
}

internal enum class PluginDevRetention {
    CANDIDATE,
    ADOPTED,
}

internal enum class PluginDevSourceMode {
    MANAGED_BUILD,
    BUILT_PACKAGE,
}

@Serializable
internal data class PluginDevDiagnostic(
    val code: String,
    val severity: String,
    val message: String,
    val instanceIds: List<String> = emptyList(),
    val packageIds: List<String> = emptyList(),
    val details: JsonObject = JsonObject(emptyMap()),
)

internal data class PluginDevArtifact(
    val artifactId: String,
    val sourceId: String,
    val buildDigest: String,
    val packages: List<ModifierPluginReference>,
    val manifest: ModifierPluginManifest,
    val directory: Path,
    val retention: PluginDevRetention,
    val expiresAt: Long?,
)

internal data class PluginDevSource(
    val sourceId: String,
    val source: Path,
    val mode: PluginDevSourceMode,
    val profileId: String?,
    val status: String,
    val packageIds: List<String>,
)

internal data class PluginDevBuildResult(
    val artifact: PluginDevArtifact,
    val unchanged: Boolean,
    val diagnostics: List<PluginDevDiagnostic>,
    val logRef: String?,
)

internal class PluginDevException(
    val code: String,
    override val message: String,
    val diagnostics: List<PluginDevDiagnostic> = emptyList(),
    val logRef: String? = null,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
