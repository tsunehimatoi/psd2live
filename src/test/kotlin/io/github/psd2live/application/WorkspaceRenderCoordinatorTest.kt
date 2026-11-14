package io.github.psd2live.application

import io.github.psd2live.core.PreviewFrame
import kotlin.test.*

class WorkspaceRenderCoordinatorTest {
    @Test fun gpuFirstObserverSeesPublishedStreamOutsideCoordinatorLock() {
        val render = WorkspaceRenderCoordinator()
        val stream = render.retain("view")
        val lock = WorkspaceRenderCoordinator::class.java.getDeclaredField("lock").run { isAccessible = true; get(render) }
        var observedLock: Boolean? = null
        var observedFrame: PreviewFrame? = null
        val token = io.github.psd2live.util.PresentationToken("r", "s", 10) {
            observedLock = Thread.holdsLock(lock)
            observedFrame = stream.value
        }
        render.invalidateModel(token)
        val stamp = assertNotNull(token.frame("view"))
        stamp.gpuCompleted(20)
        val frame = PreviewFrame(8, 8, emptyMap(), viewId = "view", presentation = stamp)
        assertTrue(render.publish(frame))
        assertEquals(false, observedLock)
        assertSame(frame, observedFrame)
        render.close()
    }

    @Test fun replacedCandidateCannotPublishAndMeasurementOffStillReloads() {
        val render = WorkspaceRenderCoordinator()
        val stream = render.retain("view")
        fun token() = io.github.psd2live.util.PresentationToken("r", "s", completed = null)
        val old = token()
        render.invalidateModel(old)
        assertTrue(render.claimModelLoad(true))
        val stale = PreviewFrame(8, 8, emptyMap(), viewId = "view", presentation = old.frame("view"))
        assertTrue(render.publish(stale))
        val next = token()
        render.invalidateModel(next)
        assertFalse(render.publish(stale))
        assertTrue(render.claimModelLoad(true))
        val fresh = stale.copy(presentation = next.frame("view"))
        assertTrue(render.publish(fresh))
        assertSame(fresh, stream.value)
        render.releaseRetained("view")
        render.retain("view")
        assertFalse(render.publish(fresh))
        assertTrue(render.publish(fresh.copy(presentation = next.frame("view"))))
        // The legacy/unidentified load path is still supported, but old identified frames are revoked.
        render.invalidateModel()
        assertFalse(next.frame("view")?.current == true)
        assertTrue(render.claimModelLoad(true))
        assertTrue(render.publish(fresh.copy(presentation = null)))
        render.close()
    }

    @Test fun sharedViewReleasesOnceAndLateFramesCannotRecreateIt() {
        val render = WorkspaceRenderCoordinator()
        val key = "1/workspace/canvas/PREVIEW"
        val flow = render.retain(key)
        render.retain(key)
        val frame = PreviewFrame(8, 8, emptyMap(), viewId = key)
        assertTrue(render.publish(frame))
        assertSame(frame, flow.value)
        assertFalse(render.releaseRetained(key))
        assertTrue(render.releaseRetained(key))
        render.remove(key)
        assertNull(flow.value)
        assertFalse(render.publish(frame))
        assertFalse(render.releaseRetained(key))
    }

    @Test fun modelLoadsOnlyForAMountedLiveViewAndFramesStayIndependent() {
        val render = WorkspaceRenderCoordinator()
        render.invalidateModel()
        assertFalse(render.claimModelLoad(true))
        val first = render.retain("a")
        val second = render.retain("b")
        assertFalse(render.claimModelLoad(false))
        assertTrue(render.claimModelLoad(true))
        assertFalse(render.claimModelLoad(true))
        render.publish(PreviewFrame(8, 8, emptyMap(), viewId = "a"))
        assertNotNull(first.value)
        assertNull(second.value)
        render.close()
        assertNull(first.value)
        assertFalse(render.contains("a"))
        assertFalse(render.claimModelLoad(true))
    }
}
