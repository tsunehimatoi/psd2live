package io.github.psd2live.core

import kotlin.test.*

class CanvasLatestQueueTest {
    @Test fun replacesOnlyTheSameViewsPendingFrame() {
        val queue = CanvasLatestQueue<String>()
        queue.put("a", "a-old")
        queue.put("b", "b-frame")
        queue.put("a", "a-new")
        assertEquals("a-new", queue.poll())
        queue.put("a", "a-next")
        assertEquals("b-frame", queue.poll())
        assertEquals("a-next", queue.poll())
        assertNull(queue.poll())
        assertTrue(queue.isEmpty())
    }

    @Test fun closingOneViewPreservesOthersAndReloadClearsAll() {
        val queue = CanvasLatestQueue<Int>()
        queue.put("a", 1)
        queue.put("b", 2)
        queue.remove("a")
        assertEquals(2, queue.poll())
        queue.put("a", 3)
        queue.put("b", 4)
        queue.clear()
        assertNull(queue.poll())
    }
}
