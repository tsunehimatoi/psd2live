package io.github.psd2live.application

import io.github.psd2live.core.RigBuildProfile
import io.github.psd2live.core.RigPreviewModel
import io.github.psd2live.util.WorkspaceBuildCorrelation
import io.github.psd2live.util.WorkspaceEditCorrelation
import io.github.psd2live.util.WorkspacePublishedObservation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Development opt-in. Subscribers receive immutable diagnostics outside Runtime's publication lock. */
internal object WorkspaceEditObservation {
    enum class Phase { STARTED, BUILT, PUBLISHED, NO_CHANGE, FAILED, CANCELLED, CONFLICT }
    data class Event(val editId: Long, val phase: Phase, val atNanos: Long,
                     val edit: WorkspaceEditCorrelation? = null,
                     val build: RigBuildProfile.Measurement<Unit>? = null,
                     val publication: WorkspacePublishedObservation? = null,
                     val failure: String? = null)
    private class Subscription(val listener: (Event) -> Unit, val profiling: AutoCloseable) {
        @Volatile var active = true
    }
    @Volatile private var subscription: Subscription? = null
    private val ids = AtomicLong()

    suspend fun current(): Trace? = if (subscription == null) null else currentCoroutineContext()[Trace]

    @Synchronized fun subscribe(listener: (Event) -> Unit): AutoCloseable {
        check(subscription == null) { "An edit observation subscriber is already installed" }
        val entry = Subscription(listener, RigBuildProfile.observeOperations())
        subscription = entry
        return AutoCloseable { synchronized(this) {
            if (subscription === entry) {
                entry.active = false
                subscription = null
                entry.profiling.close()
            }
        } }
    }

    suspend fun <T> observe(expectedState: String, operation: String, taskId: String? = null, block: suspend () -> T): T {
        val subscriber = subscription ?: return block()
        val existing = currentCoroutineContext()[Trace]
        if (existing != null && existing.expectedState == expectedState) return block()
        val trace = Trace(ids.incrementAndGet(), expectedState, operation, taskId, System.nanoTime())
        var failure: Throwable? = null
        try { return withContext(trace) { block() } }
        catch (error: Throwable) { failure = error; throw error }
        finally {
            val events = trace.finish(failure)
            // Runtime's publication lock is released; applyDocumentEdits also wraps its Host editMutex.
            for (event in events) if (subscriber.active) runCatching { subscriber.listener(event) }
                .onFailure { System.getLogger(javaClass.name).log(System.Logger.Level.WARNING, "Edit observer failed", it) }
        }
    }

    internal class Trace(val id: Long, val expectedState: String, private val operationName: String,
                         private val taskId: String?, private val requestedAt: Long) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<Trace>
        private var edit: WorkspaceEditCorrelation? = null
        private var lastBuild: WorkspaceBuildCorrelation? = null
        private var publication: WorkspacePublishedObservation? = null
        private var noChange = false
        private val events = ArrayList<Event>()

        fun bind(before: WorkspaceCapture<*>) {
            if (edit != null) return
            edit = WorkspaceEditCorrelation(id, taskId, operationName, before.projectId, before.loadingIdentity,
                before.state, before.revision, requestedAt)
            events += Event(id, Phase.STARTED, requestedAt, edit)
        }

        suspend fun <M> build(candidateRevision: String, previous: M, block: suspend () -> M): M {
            val correlation = WorkspaceBuildCorrelation(requireNotNull(edit), (lastBuild?.ordinal ?: 0) + 1, candidateRevision)
            lastBuild = correlation
            val model = previous as? RigPreviewModel
            val meshes = model?.rig?.puppet?.drawables.orEmpty()
            val identity = RigBuildProfile.Operation(taskId ?: "edit-$id", correlation.edit.startRevision,
                candidateRevision, operationName, meshes.size, meshes.sumOf { (it.mesh?.positions?.size ?: 0).toLong() / 2 }, correlation)
            return RigBuildProfile.observeSuspending(identity, { sample, error ->
                events += Event(id, Phase.BUILT, sample.finishedAtNanos, edit, sample, failure = error?.toString())
            }, block)
        }

        /** Only called after successful CAS validation, with the final adopted revision and new state. */
        fun published(state: String, revision: String): WorkspacePublishedObservation =
            WorkspacePublishedObservation(requireNotNull(edit), lastBuild, state, revision, System.nanoTime()).also { publication = it }

        fun unchanged() { noChange = true }

        fun finish(failure: Throwable?): List<Event> {
            val phase = when {
                publication != null -> Phase.PUBLISHED // Late cancellation/refresh errors cannot undo CAS.
                noChange -> Phase.NO_CHANGE
                failure is CancellationException -> Phase.CANCELLED
                failure is WorkspaceConflict -> Phase.CONFLICT
                else -> Phase.FAILED
            }
            events += Event(id, phase, System.nanoTime(), edit, publication = publication, failure = failure?.toString())
            return events.toList()
        }
    }
}
