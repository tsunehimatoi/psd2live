package io.github.psd2live.ui.state

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The bracket that turns a field edit into one history node.
 *
 * Both failure directions are covered: committing too early is the per-keystroke history this exists to
 * remove, and committing too late is a lost edit.
 */
class EditorFieldSessionsTest {

    @Test
    fun aFieldSessionCommitsOnceWhenItCloses() {
        var commits = 0
        val sessions = EditorFieldSessions { commits++ }

        sessions.begin("name")
        assertTrue(sessions.anyOpen)
        // Every keystroke re-opens the same token; nothing may commit while the user is still typing.
        sessions.begin("name")
        sessions.begin("name")
        assertEquals(0, commits)

        sessions.end("name")
        assertEquals(1, commits, "the whole edit is one commit")
    }

    /** The reason this is a set of tokens and not a boolean. */
    @Test
    fun aSliderAndAFieldInProgressDoNotEndEachOther() {
        var commits = 0
        val sessions = EditorFieldSessions { commits++ }

        sessions.begin("slider")
        sessions.begin("name")
        sessions.end("slider")
        assertEquals(0, commits, "the field session is still open, so nothing may commit yet")

        sessions.end("name")
        assertEquals(1, commits)
    }

    @Test
    fun endingATokenThatWasNeverOpenIsNotACommit() {
        var commits = 0
        val sessions = EditorFieldSessions { commits++ }
        sessions.end("stray")
        assertEquals(0, commits)
        assertFalse(sessions.anyOpen)
    }

    @Test
    fun flushClosesEverythingAtOnce() {
        var commits = 0
        val sessions = EditorFieldSessions { commits++ }

        sessions.begin("slider")
        sessions.begin("name")
        sessions.flush()
        assertEquals(1, commits)
        assertFalse(sessions.anyOpen)

        // Flushing with nothing open must not invent a commit — a save with no edit in flight is not an edit.
        sessions.flush()
        assertEquals(1, commits)
    }
}
