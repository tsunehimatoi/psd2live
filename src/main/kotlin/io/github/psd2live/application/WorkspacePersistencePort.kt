package io.github.psd2live.application

import kotlinx.coroutines.flow.Flow

/** A recovery checkpoint is separate from the user's exported project archive. */
enum class WorkspacePersistencePhase { MEMORY, SAVING, SAVED, FAILED }

data class WorkspacePersistenceState(
    val projectId: String?,
    val state: String?,
    val phase: WorkspacePersistencePhase,
    val error: String? = null,
)

interface WorkspacePersistencePort {
    /** Derived from runtime publication and destination acknowledgements, never editor guesses. */
    fun observePersistence(): Flow<WorkspacePersistenceState>
    /** Re-enqueue the current complete checkpoint without making an edit or history node. */
    fun retryPersistence()
}

internal fun WorkspacePersistenceStatus.projectState(projectId: String?, state: String?, destination: Any): WorkspacePersistenceState {
    val write = destinations[destination]
    val failure = failures[destination]
    val phase = when {
        projectId == null || state == null -> WorkspacePersistencePhase.MEMORY
        write != null && write.pending > 0 -> WorkspacePersistencePhase.SAVING
        failure != null -> WorkspacePersistencePhase.FAILED
        write?.requestedVersion == state && write.completedVersion == state -> WorkspacePersistencePhase.SAVED
        else -> WorkspacePersistencePhase.MEMORY
    }
    return WorkspacePersistenceState(projectId, state, phase, failure?.cause?.message)
}
