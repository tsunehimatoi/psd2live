package io.github.psd2live.application

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import java.io.IOException
import java.nio.file.AccessDeniedException

internal class WorkspaceUnsavedChanges : IllegalStateException("Current workspace has unsaved changes; save first or explicitly discard them")
internal class WorkspaceBusy : IllegalStateException("Workspace is busy")
internal class WorkspaceOutputContractFailure(val operation: String, val validation: WorkspaceValidationException) :
    IllegalStateException("Operation $operation returned an invalid result: ${validation.message}", validation)

/** The same machine-readable failure is retained by jobs and returned by transport adapters. */
@ConsistentCopyVisibility
internal data class WorkspaceFailure private constructor(
    val code: String,
    val message: String,
    val details: JsonObject,
) {
    fun toJson(): JsonObject = JsonObject(details + mapOf("code" to JsonPrimitive(code), "message" to JsonPrimitive(message)))

    companion object {
        fun from(failure: Exception): WorkspaceFailure {
            require(failure !is CancellationException) { "Cancellation is a terminal job state, not a failure" }
            val code = when (failure) {
                is io.github.psd2live.core.quality.QualityFenceRejectedException ->
                    if (failure.report.fence == io.github.psd2live.core.quality.QualityFence.AUTHORING_COMMIT &&
                        failure.report.findings.all { it.rule.domain == io.github.psd2live.core.quality.QualityDomain.GEOMETRY }) "geometry_unsafe" else "quality_rejected"
                is WorkspaceBatchEditException -> "invalid_edit"
                is WorkspaceValidationException -> "invalid_request"
                is WorkspaceOutputContractFailure -> "output_contract"
                is WorkspaceConflict -> "state_conflict"
                is WorkspaceProjectConflict -> "project_conflict"
                is WorkspaceRequestReuse -> "request_id_reused"
                is WorkspaceUnsavedChanges -> "unsaved_changes"
                is WorkspaceBusy -> "workspace_busy"
                is AccessDeniedException, is SecurityException -> "permission_denied"
                is IOException -> "io_error"
                is IllegalArgumentException -> "invalid_argument"
                is IllegalStateException -> "invalid_state"
                else -> "operation_failed"
            }
            val details = buildJsonObject {
                when (failure) {
                    is io.github.psd2live.core.quality.QualityFenceRejectedException -> put("diagnostics", failure.diagnostics)
                    is WorkspaceValidationException -> put("field", failure.fieldPath)
                    is WorkspaceOutputContractFailure -> {
                        put("field", failure.validation.fieldPath); put("operation", failure.operation)
                    }
                    is WorkspaceBatchEditException -> {
                        put("edit_index", failure.index); put("edit_operation", failure.editOperation)
                    }
                    is WorkspaceProjectConflict -> {
                        put("expected_project", failure.expectedProject?.let(::JsonPrimitive) ?: JsonNull)
                        put("actual_project", failure.actualProject?.let(::JsonPrimitive) ?: JsonNull)
                    }
                    is WorkspaceConflict -> {
                        put("expected_state", failure.expectedState); put("actual_state", failure.actualState)
                    }
                }
            }
            return WorkspaceFailure(code, failure.message ?: "Operation failed", details)
        }
    }
}
