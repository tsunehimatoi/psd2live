package io.github.psd2live.util

/** Process-only observation identities. Never serialize these into documents, history or cache keys. */
data class WorkspaceEditCorrelation(
    val editId: Long,
    val taskId: String?,
    val operation: String,
    val projectId: String,
    val loadingIdentity: String,
    val startState: String,
    val startRevision: String,
    val requestedAtNanos: Long,
)

data class WorkspaceBuildCorrelation(val edit: WorkspaceEditCorrelation, val ordinal: Int, val candidateRevision: String)

/** Captured atomically with successful publication; renderer activation keeps its own distinct candidate ID. */
data class WorkspacePublishedObservation(
    val edit: WorkspaceEditCorrelation,
    val build: WorkspaceBuildCorrelation?,
    val state: String,
    val revision: String,
    val publishedAtNanos: Long,
)
