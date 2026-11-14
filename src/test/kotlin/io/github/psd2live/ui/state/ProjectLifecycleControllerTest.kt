package io.github.psd2live.ui.state

import kotlinx.coroutines.*
import java.nio.file.Path
import kotlin.test.*

class ProjectLifecycleControllerTest {
    private fun staleFailure(reopen: Boolean) = runBlocking {
        var state = PSD2LiveState(projectId = "project", projectDirty = true)
        val oldStarted = CompletableDeferred<Unit>()
        val failOld = CompletableDeferred<Unit>()
        val newStarted = CompletableDeferred<Unit>()
        val finishNew = CompletableDeferred<Unit>()
        val owner = SupervisorJob(coroutineContext[Job])
        val scope = CoroutineScope(coroutineContext + owner)
        var saves = 0
        var newCancelled = 0
        var newCompleted = 0
        val controller = ProjectLifecycleController({ state }, { state = it(state) }, { state.projectDirty }, {}, {
            if (++saves == 1) {
                oldStarted.complete(Unit)
                failOld.await()
                throw java.io.IOException("old save failed")
            }
            newStarted.complete(Unit)
            finishNew.await()
            state = state.copy(projectDirty = false)
            "saved"
        }, scope)
        try {
            controller.confirmUnsavedChanges = { 0 }
            controller.withSavedChangesOrCancel({}, {})
            controller.saveProjectTo(Path.of("old.psd2live"))
            oldStarted.await()
            val oldJob = owner.children.single()
            if (reopen) {
                controller.close()
                state = state.copy(projectId = "another", projectOpenGeneration = 1)
            }
            controller.withSavedChangesOrCancel({ newCancelled++ }, { newCompleted++ })
            controller.saveProjectTo(Path.of("new.psd2live"))
            newStarted.await()
            failOld.complete(Unit)
            oldJob.join()
            assertEquals(0, newCancelled, "The obsolete failure cancelled a newer navigation intent")
            finishNew.complete(Unit)
            owner.children.toList().joinAll()
            assertEquals(1, newCompleted)
            assertEquals(0, newCancelled)
        } finally {
            failOld.complete(Unit)
            finishNew.complete(Unit)
            controller.close()
            scope.cancel()
        }
    }

    @Test fun oldProjectSaveFailureCannotCancelNewProjectNavigation() = staleFailure(reopen = true)
    @Test fun oldSaveRequestFailureCannotCancelANewerRequestInTheSameProject() = staleFailure(reopen = false)
    @Test fun aQueuedSaveCannotCaptureTheProjectOpenedBeforeItStarts() = runBlocking {
        var state = PSD2LiveState(projectId = "old", projectDirty = true)
        val owner = SupervisorJob(coroutineContext[Job])
        val scope = CoroutineScope(coroutineContext + owner)
        var saves = 0
        val controller = ProjectLifecycleController({ state }, { state = it(state) }, { true }, {}, {
            saves++
            "saved"
        }, scope)
        try {
            controller.saveProjectTo(Path.of("old.psd2live"))
            state = state.copy(projectId = "new", projectOpenGeneration = 1)
            owner.children.toList().joinAll()
            assertEquals(0, saves)
        } finally { controller.close(); scope.cancel() }
    }

    @Test fun savesForTheSameProjectExecuteInOrderRatherThanCoalescing() = runBlocking {
        var state = PSD2LiveState(projectId = "project", projectDirty = true)
        val owner = SupervisorJob(coroutineContext[Job])
        val scope = CoroutineScope(coroutineContext + owner)
        val destinations = mutableListOf<Path?>()
        val controller = ProjectLifecycleController({ state }, { state = it(state) }, { true }, {}, { path ->
            destinations += path
            "saved"
        }, scope)
        try {
            controller.saveProjectTo(Path.of("first.psd2live"))
            controller.saveProjectTo(Path.of("second.psd2live"))
            owner.children.toList().joinAll()
            assertEquals(listOf(Path.of("first.psd2live"), Path.of("second.psd2live")), destinations)
        } finally { controller.close(); scope.cancel() }
    }

}
