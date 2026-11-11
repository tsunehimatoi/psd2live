package io.github.psd2live.ui.state

/** One new import's preset, held while its mesh pieces are inspected and named. Never restored for an old project. */
internal class ImportPresetContinuation {
    private var pending: Pair<Long, StartPresetChoices>? = null

    val waiting get() = pending != null

    fun start(generation: Long, choices: StartPresetChoices) { pending = generation to choices }
    fun clear() { pending = null }

    /** Consume once, only in the same document and after all split offers/operations have finished. */
    fun take(generation: Long, blocked: Boolean): StartPresetChoices? {
        val (owner, choices) = pending ?: return null
        if (owner != generation) { clear(); return null }
        if (blocked) return null
        clear()
        return choices
    }
}
