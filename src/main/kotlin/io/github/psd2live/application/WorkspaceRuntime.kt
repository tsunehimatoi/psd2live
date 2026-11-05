package io.github.psd2live.application

import io.github.psd2live.history.WorkspaceHistoryState
import io.github.psd2live.history.WorkspaceHistoryTree
import io.github.psd2live.project.MutationAuthor
import io.github.psd2live.project.WorkspaceDocument
import io.github.psd2live.project.WorkspaceRevisions
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonObject
import java.util.UUID

/** A conflict is recoverable by inspecting the current workspace, never by overwriting it. */
internal class WorkspaceConflict(val expectedState: String, val actualState: String) :
    IllegalStateException("Workspace changed: expected $expectedState, actual $actualState")

/** Model and document belong to one captured version; queries never assemble these independently. */
internal data class WorkspaceCapture<M>(
    val projectId: String,
    val state: String,
    val historyHead: String,
    val revision: String,
    val document: WorkspaceDocument,
    val model: M,
    val auxiliary: JsonObject,
    val dirty: Boolean,
    val loadingIdentity: String,
)

internal data class WorkspaceRuntimeState<M>(
    val state: String,
    val capture: WorkspaceCapture<M>? = null,
)

/** A read keeps the history tree and committed aggregate from one instant, including unloaded state. */
internal data class WorkspaceReadCapture<M>(
    val runtime: WorkspaceRuntimeState<M>,
    val history: WorkspaceHistoryState<WorkspaceDocument>?,
)

/** An edit only constructs a candidate document. It cannot publish history or change the live model. */
internal fun interface WorkspaceDocumentEdit<M> {
    suspend fun apply(document: WorkspaceDocument, model: M): WorkspaceDocument
}

/** A private candidate of everything one edit may change: the document and the auxiliary data beside it. */
internal data class WorkspaceDraft(val document: WorkspaceDocument, val auxiliary: JsonObject)

/** Like [WorkspaceDocumentEdit], for edits that also move auxiliary data such as authored poses. */
internal fun interface WorkspaceDraftEdit<M> {
    suspend fun apply(draft: WorkspaceDraft, model: M): WorkspaceDraft
}

internal data class WorkspaceCommit<M>(val capture: WorkspaceCapture<M>, val applied: Boolean, val geometryDiagnostics: JsonObject? = null)

/** A rebuilt private candidate; preparation has no history, projection or persistence effects. */
internal data class WorkspacePreparedDraft<M>(val before: WorkspaceCapture<M>, val draft: WorkspaceDraft, val model: M)

/** A validated, immutable installation candidate. Preparing it never touches the live runtime. */
internal class WorkspaceInstallation private constructor(
    val history: WorkspaceHistoryState<WorkspaceDocument>,
) {
    companion object {
        fun prepare(document: WorkspaceDocument, history: WorkspaceHistoryState<WorkspaceDocument>? = null): WorkspaceInstallation {
            val revision = WorkspaceRevisions.of(document)
            val replacement = history?.let { WorkspaceHistoryTree.restore(it.selections, it.headNodeId) }
                ?: WorkspaceHistoryTree(document, revision, revision)
            require(WorkspaceRevisions.of(replacement.head().snapshot) == revision) {
                "Loaded document does not match history HEAD"
            }
            return WorkspaceInstallation(replacement.state())
        }
    }
}

/**
 * Authoritative document/model/history owner, usable without Compose or MCP.
 * Expensive preparation runs outside the state lock. Both entry and commit check the same opaque
 * state token, including a load generation, so even reopening the same project rejects old work.
 */
internal class WorkspaceRuntime<M>(
    private val rebuild: suspend (WorkspaceDocument) -> M,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    /**
     * Builds a draft's next candidate from the candidate before it, when the builder can use it: entries added to the
     * journal act on its rig, and a regeneration merges onto it. [rebuild] otherwise.
     */
    private val rebuildFrom: (suspend (WorkspaceDocument, M) -> M)? = null,
    private val onCommitted: (WorkspaceReadCapture<M>) -> Unit = {},
) {
    private val lock = Any()
    private val instanceId = newId()
    private var generation = 0L
    private var version = 0L
    private var tree: WorkspaceHistoryTree<WorkspaceDocument>? = null
    private val mutableState = MutableStateFlow(WorkspaceRuntimeState<M>(token()))
    /** For tests: runs between a commit's [commitPrepared] beforeCommit and its publication. */
    @Volatile internal var beforePublish: (() -> Unit)? = null
    @Volatile private var publishedRead = WorkspaceReadCapture(mutableState.value, null)
    val state: StateFlow<WorkspaceRuntimeState<M>> = mutableState.asStateFlow()

    fun capture(): WorkspaceCapture<M> = mutableState.value.capture
        ?: throw IllegalStateException("No workspace is loaded")

    /** Sessions can survive auxiliary pose changes while rejecting even the same archive reopened. */
    fun sameLoadGeneration(left: WorkspaceCapture<M>, right: WorkspaceCapture<M>): Boolean =
        left.loadingIdentity == right.loadingIdentity

    /** Freeze auxiliary resources under the same lock that publishes their catalog. */
    fun <T> withCapture(read: (WorkspaceCapture<M>) -> T): T = synchronized(lock) { read(capture()) }

    /** One immutable publication: UI notifications can read it without reversing the commit lock order. */
    fun read(): WorkspaceReadCapture<M> = publishedRead

    fun history(): WorkspaceHistoryState<WorkspaceDocument> = synchronized(lock) {
        requireNotNull(tree) { "No workspace is loaded" }.state()
    }

    /** Saving must not pair one version's model/document with a newer history HEAD. */
    fun history(expectedState: String): WorkspaceHistoryState<WorkspaceDocument> = synchronized(lock) {
        checkState(expectedState)
        requireNotNull(tree) { "No workspace is loaded" }.state()
    }

    /** Restore existing IDs and revisions; installation never synthesizes a replacement history. */
    fun install(
        expectedState: String,
        projectId: String,
        document: WorkspaceDocument,
        model: M,
        history: WorkspaceHistoryState<WorkspaceDocument>? = null,
        auxiliary: JsonObject = JsonObject(emptyMap()),
        discardUnsaved: Boolean = false,
        dirty: Boolean = false,
        beforeInstall: () -> Unit = {},
    ): WorkspaceCapture<M> = commitModel(model) {
        checkState(expectedState)
        require(projectId.isNotBlank()) { "Project ID must not be blank" }
        if (mutableState.value.capture?.dirty == true && !discardUnsaved) throw WorkspaceUnsavedChanges()
        val replacement = history?.let { WorkspaceHistoryTree.restore(it.selections, it.headNodeId) }
            ?: WorkspaceRevisions.of(document).let { WorkspaceHistoryTree(document, it, it) }
        val head = replacement.head()
        require(WorkspaceRevisions.of(head.snapshot) == WorkspaceRevisions.of(document)) {
            "Loaded document does not match history HEAD"
        }
        beforeInstall()
        generation++
        version = 0
        tree = replacement
        publish(WorkspaceCapture(projectId, token(), head.node.id, head.node.revisionId,
            head.snapshot, model, auxiliary, dirty, "$instanceId:$generation"))
    }

    /** Only publication and projection run on the caller's thread; hashing was done by [WorkspaceInstallation.prepare]. */
    fun installPrepared(
        expectedState: String,
        projectId: String,
        installation: WorkspaceInstallation,
        model: M,
        auxiliary: JsonObject = JsonObject(emptyMap()),
        discardUnsaved: Boolean = false,
        dirty: Boolean = false,
        beforeInstall: () -> Unit = {},
    ): WorkspaceCapture<M> {
        // Each installation owns its mutable tree, even if a prepared candidate is used twice.
        val replacement = WorkspaceHistoryTree.restore(installation.history.selections, installation.history.headNodeId)
        val head = replacement.head()
        return commitModel(model) {
            checkState(expectedState)
            require(projectId.isNotBlank()) { "Project ID must not be blank" }
            if (mutableState.value.capture?.dirty == true && !discardUnsaved) throw WorkspaceUnsavedChanges()
            beforeInstall()
            generation++
            version = 0
            tree = replacement
            publish(WorkspaceCapture(projectId, token(), head.node.id, head.node.revisionId,
                head.snapshot, model, auxiliary, dirty, "$instanceId:$generation"))
        }
    }

    fun close(expectedState: String, discardUnsaved: Boolean = false) = synchronized(lock) {
        checkState(expectedState)
        if (mutableState.value.capture?.dirty == true && !discardUnsaved) throw WorkspaceUnsavedChanges()
        generation++
        version = 0
        tree = null
        val unloaded = WorkspaceRuntimeState<M>(token())
        publishedRead = WorkspaceReadCapture(unloaded, null)
        mutableState.value = unloaded
    }

    /** One isolated draft, one successful commit. A failed edit or rebuild never publishes a prefix. */
    suspend fun execute(
        projectId: String,
        expectedState: String,
        summary: String,
        author: MutationAuthor,
        edits: List<WorkspaceDocumentEdit<M>>,
        taskId: String? = null,
        editFailure: (Int, Exception) -> Exception = { _, failure -> failure },
        beforeCommit: (WorkspaceCapture<M>, WorkspaceDocument, M) -> Unit = { _, _, _ -> },
    ): WorkspaceCommit<M> = executeDraft(projectId, expectedState, summary, author,
        edits.map { edit -> WorkspaceDraftEdit { draft, model -> draft.copy(document = edit.apply(draft.document, model)) } },
        taskId, editFailure) { capture, draft, model -> beforeCommit(capture, draft.document, model) }

    /**
     * Document and auxiliary data move together: each edit sees the previous candidate of both and one CAS
     * publishes them, so a document is never committed ahead of the poses that belong to it.
     */
    suspend fun executeDraft(
        projectId: String,
        expectedState: String,
        summary: String,
        author: MutationAuthor,
        edits: List<WorkspaceDraftEdit<M>>,
        taskId: String? = null,
        editFailure: (Int, Exception) -> Exception = { _, failure -> failure },
        beforeCommit: (WorkspaceCapture<M>, WorkspaceDraft, M) -> Unit = { _, _, _ -> },
    ): WorkspaceCommit<M> {
        require(summary.isNotBlank()) { "History summary must not be blank" }
        val prepared = prepareDraft(projectId, expectedState, edits, editFailure)
        val changed = prepared.draft.auxiliary.takeIf { it != prepared.before.auxiliary }
        return commitPrepared(projectId, expectedState, summary, author, prepared.draft.document, prepared.model, taskId, auxiliary = changed) { capture, next, committed ->
            beforeCommit(capture, WorkspaceDraft(next, changed ?: capture.auxiliary), committed)
        }
    }

    suspend fun prepareDraft(
        projectId: String, expectedState: String, edits: List<WorkspaceDraftEdit<M>>,
        editFailure: (Int, Exception) -> Exception = { _, failure -> failure },
    ): WorkspacePreparedDraft<M> {
        require(edits.size in 1..128) { "Use 1..128 edits" }
        val before = synchronized(lock) {
            checkState(expectedState)
            capture().also { require(it.projectId == projectId) { "Operation targets another project" } }
        }
        var document = before.document
        var auxiliary = before.auxiliary
        var model = before.model
        var revision = WorkspaceRevisions.of(document)
        for ((index, edit) in edits.withIndex()) {
            currentCoroutineContext().ensureActive()
            try {
                val candidate = edit.apply(WorkspaceDraft(document, auxiliary), model)
                val nextRevision = WorkspaceRevisions.of(candidate.document)
                val previousModel = model
                if (nextRevision != revision) {
                    // Later commands must observe the rig produced by earlier commands in this draft. Reference images
                    // are view data no build reads: moving one keeps the model.
                    model = if (onlyReferenceImagesChanged(document, candidate.document)) model
                        else rebuildFrom?.invoke(candidate.document, model) ?: rebuild(candidate.document)
                    document = adopted(candidate.document, model)
                    revision = WorkspaceRevisions.of(document)
                }
                auxiliary = adoptedAuxiliary(previousModel, model, candidate.auxiliary)
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (failure: Exception) { throw editFailure(index, failure) }
        }
        currentCoroutineContext().ensureActive()
        return WorkspacePreparedDraft(before, WorkspaceDraft(document, auxiliary), model)
    }

    /** The desktop adapter may prepare a model with incremental algorithms before entering this CAS. */
    fun commitPrepared(
        projectId: String, expectedState: String, summary: String, author: MutationAuthor,
        document: WorkspaceDocument, model: M, taskId: String? = null, checkpoint: Boolean = false,
        auxiliary: JsonObject? = null,
        beforeCommit: (WorkspaceCapture<M>, WorkspaceDocument, M) -> Unit = { _, _, _ -> },
    ): WorkspaceCommit<M> {
        require(summary.isNotBlank()) { "History summary must not be blank" }
        val before = synchronized(lock) { checkState(expectedState); capture() }
        require(before.projectId == projectId) { "Operation targets another project" }
        val document = adopted(document, model)
        // Prepared drafts already migrated after each member; doing it again would reset a newly created
        // axis to the final default instead of retaining the value the earlier member introduced.
        val auxiliary = auxiliary ?: adoptedAuxiliary(before.model, model, before.auxiliary)
        val revision = WorkspaceRevisions.of(document)
        val documentChanged = checkpoint || revision != before.revision
        return commitModel(model) {
            checkState(expectedState)
            if (!documentChanged && auxiliary == before.auxiliary) return@commitModel WorkspaceCommit(before, false)
            // Projection validation is atomic and must fail before touching the authoritative history.
            beforeCommit(before, document, model)
            beforePublish?.invoke()
            val head = if (documentChanged) requireNotNull(tree).commit(before.historyHead, document, revision, revision, summary, author.historyActor, taskId)
                else requireNotNull(tree).head()
            version++
            WorkspaceCommit(publish(before.copy(state = token(), historyHead = head.node.id, revision = head.node.revisionId,
                document = document, model = model, auxiliary = auxiliary, dirty = true)), true).also { notifyCommitted() }
        }
    }

    suspend fun checkout(projectId: String, expectedState: String, nodeId: String,
                         beforeCommit: (WorkspaceCapture<M>, WorkspaceDocument, M) -> Unit = { _, _, _ -> }): WorkspaceCapture<M> {
        val (before, selection) = synchronized(lock) {
            checkState(expectedState)
            val capture = capture()
            require(capture.projectId == projectId) { "Operation targets another project" }
            capture to requireNotNull(tree).selectionAt(nodeId)
        }
        if (nodeId == before.historyHead) return before
        val model = rebuild(selection.snapshot)
        currentCoroutineContext().ensureActive()
        return commitModel(model) {
            checkState(expectedState)
            beforeCommit(before, selection.snapshot, model)
            requireNotNull(tree).checkout(nodeId)
            version++
            publish(before.copy(state = token(), historyHead = nodeId, revision = selection.node.revisionId,
                document = selection.snapshot, model = model,
                auxiliary = adoptedAuxiliary(before.model, model, before.auxiliary), dirty = true)).also { notifyCommitted() }
        }
    }

    /** Snapshots and history annotations are durable auxiliary data, not rig journal commands. */
    /** Re-evaluate process dependencies without rewriting the document, dirty bit or history. */
    suspend fun refreshModel(expectedState: String, build: suspend (WorkspaceDocument) -> M,
                             beforeCommit: (WorkspaceCapture<M>, M) -> Unit = { _, _ -> }): WorkspaceCapture<M> {
        val before = synchronized(lock) { checkState(expectedState); capture() }
        val model = build(before.document)
        currentCoroutineContext().ensureActive()
        return commitModel(model) {
            checkState(expectedState)
            beforeCommit(before, model)
            version++
            publish(before.copy(state = token(), model = model))
        }
    }

    fun updateAuxiliary(projectId: String, expectedState: String, auxiliary: JsonObject,
                        projectUnchanged: Boolean = false,
                        beforeCommit: (WorkspaceCapture<M>, JsonObject) -> Unit = { _, _ -> }): WorkspaceCapture<M> = synchronized(lock) {
        checkState(expectedState)
        val before = capture()
        require(before.projectId == projectId) { "Operation targets another project" }
        if (before.auxiliary == auxiliary) {
            if (projectUnchanged) beforeCommit(before, auxiliary)
            return@synchronized before
        }
        beforeCommit(before, auxiliary)
        version++
        publish(before.copy(state = token(), auxiliary = auxiliary, dirty = true)).also { notifyCommitted() }
    }

    private fun notifyCommitted() {
        // A failed host notification cannot roll back the authoritative commit.
        try { onCommitted(publishedRead) }
        catch (failure: Exception) {
            System.getLogger(WorkspaceRuntime::class.java.name).log(System.Logger.Level.WARNING, "Unable to schedule committed workspace persistence", failure)
        }
    }

    /** A save completing on an old capture cannot mark newer edits (or another project) clean. */
    fun saved(capturedState: String): Boolean = synchronized(lock) {
        val current = mutableState.value
        if (current.state != capturedState || current.capture == null) return@synchronized false
        publish(current.capture.copy(dirty = false))
        true
    }

    private fun checkState(expected: String) {
        val actual = mutableState.value.state
        if (expected != actual) throw WorkspaceConflict(expected, actual)
    }

    /**
     * [document] with the regeneration checkpoints its rebuilt [model] inserted into the journal
     * ([io.github.psd2live.core.RigRegenerationCheckpoint]): they are part of the edit that produced the model.
     */
    private fun adopted(document: WorkspaceDocument, model: M): WorkspaceDocument {
        val rebuilt = (model as? io.github.psd2live.core.RigPreviewModel)?.config?.rigEdits?.authoringJournal ?: return document
        val journal = document.rigEdits.authoringJournal
        if (rebuilt === journal || rebuilt.size <= journal.size) return document
        val plain = rebuilt.filterNot(io.github.psd2live.core.RigCheckpoint::isRecord)
        if (plain.size != journal.filterNot(io.github.psd2live.core.RigCheckpoint::isRecord).size ||
            plain != journal.filterNot(io.github.psd2live.core.RigCheckpoint::isRecord)) return document
        return document.copy(rigEdits = document.rigEdits.copy(authoringJournal = rebuilt))
    }

    private fun adoptedAuxiliary(previous: M, next: M, auxiliary: JsonObject): JsonObject {
        val before = previous as? io.github.psd2live.core.RigPreviewModel ?: return auxiliary
        val after = next as? io.github.psd2live.core.RigPreviewModel ?: return auxiliary
        val parameters = before.rig.puppet.parameters
        // Only a version that names other parameters can hold a conversion the editor data must follow.
        val renamed = if (parameters.map { it.id } == after.rig.puppet.parameters.map { it.id }) emptyMap()
            else WorkspaceConvertedIds.parameters(before.config.rigEdits.authoringJournal, after.config.rigEdits.authoringJournal)
        return PreviewSessions.migrate(parameters, after.rig.puppet.parameters, WorkspaceConvertedIds.auxiliary(auxiliary, renamed), renamed)
    }

    private fun <T> commitModel(model: M, action: () -> T): T = synchronized(lock) {
        val evaluation = (model as? io.github.psd2live.core.RigPreviewModel)?.modifierEvaluation
        if (evaluation == null) action() else io.github.psd2live.core.modifier.ModifierPluginStore.shared.withRevision(evaluation.plugins, action)
    }

    private fun token(): String = "$instanceId:$generation:$version"
    private fun publish(capture: WorkspaceCapture<M>): WorkspaceCapture<M> = capture.also {
        val next = WorkspaceRuntimeState(it.state, it)
        publishedRead = WorkspaceReadCapture(next, tree?.state())
        mutableState.value = next
    }
}

/** Whether [after] differs from [before] only in its reference images, which no build of the model reads. */
internal fun onlyReferenceImagesChanged(before: WorkspaceDocument, after: WorkspaceDocument): Boolean =
    before.layout.references != after.layout.references &&
        before.copy(layout = before.layout.copy(references = after.layout.references)) == after
