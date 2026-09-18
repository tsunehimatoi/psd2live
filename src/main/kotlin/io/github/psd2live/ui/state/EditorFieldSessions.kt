package io.github.psd2live.ui.state

/**
 * The editor field sessions that are open right now, by token.
 *
 * While any session is open the view model marks the project dirty instead of committing, so one field
 * edit becomes one history node rather than one per keystroke. This is the same bracket the sliders
 * already used; it is a class rather than a flag so it can be tested without standing up a view model,
 * and so overlapping sessions cannot end each other.
 *
 * That overlap is the reason it is a set and not a boolean. A slider drag and a half-typed text field
 * can both be open, and with a boolean whichever finished first re-enabled committing for the other —
 * which is exactly the per-keystroke history the bracket exists to prevent.
 *
 * @param onAllClosed Runs when the last session closes. Never runs while one is still open, so a commit
 *   always sees the final value of every field the user had been typing into.
 */
internal class EditorFieldSessions(private val onAllClosed: () -> Unit) {
    private val open = mutableSetOf<String>()

    val anyOpen: Boolean get() = open.isNotEmpty()

    /** Idempotent: re-opening an already-open token keeps the session going rather than restarting it. */
    fun begin(token: String) {
        open += token
    }

    /** Idempotent: closing a token that is not open does nothing, so a stray blur cannot commit early. */
    fun end(token: String) {
        if (open.remove(token) && open.isEmpty()) onAllClosed()
    }

    /**
     * Closes every open session at once. For the moments where a commit has to happen regardless of
     * focus — saving, closing the window — so the capture sees the value the user just typed.
     */
    fun flush() {
        if (open.isEmpty()) return
        open.clear()
        onAllClosed()
    }
}
