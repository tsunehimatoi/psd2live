package io.github.psd2live.ui.state

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key

/**
 * A single keyboard binding: one key plus an exact modifier combination.
 *
 * The serialized form is also the display form (`"Ctrl+Shift+Z"`, `"["`, `"Shift+["`, `"NumPad0"`),
 * so there is exactly one printer to keep in sync with the parser.
 *
 * Match is *exact*: extra modifiers do not match. A binding of `Ctrl+W` is not triggered by
 * `Ctrl+Shift+W`.
 */
data class KeyBinding(
    val key: Key,
    val ctrl: Boolean = false,
    val shift: Boolean = false,
    val alt: Boolean = false,
) {
    /**
     * Canonical text form. Modifier order is fixed so that two equal bindings always format
     * identically — the conflict index relies on this being a stable map key.
     */
    fun format(): String = buildString {
        if (ctrl) append("Ctrl+")
        if (shift) append("Shift+")
        if (alt) append("Alt+")
        append(nameOf(key))
    }

    /** Exact-modifier match against a Compose key event. */
    fun matches(event: KeyEvent): Boolean = keyBindingOf(event) == this
}

/**
 * The binding a key event represents, ignoring nothing. Routing both the dispatchers and the
 * capture flow through this one function is what keeps "what fires" and "what the conflict index
 * sees" from drifting apart.
 */
internal fun keyBindingOf(event: KeyEvent): KeyBinding = KeyBinding(
    key = event.key,
    // One binding table serves both platforms: on macOS the Command key satisfies "Ctrl".
    ctrl = if (IS_MAC) event.isCtrlPressed || event.isMetaPressed else event.isCtrlPressed,
    shift = event.isShiftPressed,
    alt = event.isAltPressed,
)

/** Parses the canonical form produced by [KeyBinding.format]. Returns null on anything malformed. */
internal fun parseKeyBinding(text: String): KeyBinding? {
    val tokens = text.split('+')
    if (tokens.size < 2 && tokens.firstOrNull().isNullOrEmpty()) return null
    val key = NAME_TO_KEY[tokens.last()] ?: return null
    var ctrl = false
    var shift = false
    var alt = false
    for (token in tokens.dropLast(1)) {
        when (token) {
            "Ctrl" -> ctrl = true
            "Shift" -> shift = true
            "Alt" -> alt = true
            else -> return null
        }
    }
    return KeyBinding(key, ctrl, shift, alt)
}

/**
 * Keys the user may bind. This doubles as the recorder's whitelist: capture can only produce a
 * binding that is in this table, which is exactly the set the dispatchers know how to match.
 *
 * No name may contain '+' — that is what makes [parseKeyBinding]'s split unambiguous, and why
 * `Key.Plus` is spelled `"Plus"` rather than `"+"`.
 */
private val NAME_TO_KEY: Map<String, Key> = buildMap {
    put("A", Key.A); put("B", Key.B); put("C", Key.C); put("D", Key.D); put("E", Key.E); put("F", Key.F)
    put("G", Key.G); put("H", Key.H); put("I", Key.I); put("J", Key.J); put("K", Key.K); put("L", Key.L)
    put("M", Key.M); put("N", Key.N); put("O", Key.O); put("P", Key.P); put("Q", Key.Q); put("R", Key.R)
    put("S", Key.S); put("T", Key.T); put("U", Key.U); put("V", Key.V); put("W", Key.W); put("X", Key.X)
    put("Y", Key.Y); put("Z", Key.Z)
    put("0", Key.Zero); put("1", Key.One); put("2", Key.Two); put("3", Key.Three); put("4", Key.Four)
    put("5", Key.Five); put("6", Key.Six); put("7", Key.Seven); put("8", Key.Eight); put("9", Key.Nine)
    put("F1", Key.F1); put("F2", Key.F2); put("F3", Key.F3); put("F4", Key.F4); put("F5", Key.F5)
    put("F6", Key.F6); put("F7", Key.F7); put("F8", Key.F8); put("F9", Key.F9); put("F10", Key.F10)
    put("F11", Key.F11); put("F12", Key.F12)
    put("Tab", Key.Tab)
    put("Enter", Key.Enter)
    put("Esc", Key.Escape)
    put("Del", Key.Delete)
    put("Backspace", Key.Backspace)
    put("Space", Key.Spacebar)
    put("Home", Key.MoveHome)
    put("End", Key.MoveEnd)
    put("PageUp", Key.PageUp)
    put("PageDown", Key.PageDown)
    put("Insert", Key.Insert)
    put("[", Key.LeftBracket)
    put("]", Key.RightBracket)
    put("-", Key.Minus)
    put("=", Key.Equals)
    put("Plus", Key.Plus)
    put(",", Key.Comma)
    put(".", Key.Period)
    put("/", Key.Slash)
    put("\\", Key.Backslash)
    put(";", Key.Semicolon)
    put("'", Key.Apostrophe)
    put("`", Key.Grave)
    put("Left", Key.DirectionLeft)
    put("Right", Key.DirectionRight)
    put("Up", Key.DirectionUp)
    put("Down", Key.DirectionDown)
    put("NumPad0", Key.NumPad0); put("NumPad1", Key.NumPad1); put("NumPad2", Key.NumPad2)
    put("NumPad3", Key.NumPad3); put("NumPad4", Key.NumPad4); put("NumPad5", Key.NumPad5)
    put("NumPad6", Key.NumPad6); put("NumPad7", Key.NumPad7); put("NumPad8", Key.NumPad8)
    put("NumPad9", Key.NumPad9)
    put("NumPadAdd", Key.NumPadAdd)
    put("NumPadSubtract", Key.NumPadSubtract)
    put("NumPadMultiply", Key.NumPadMultiply)
    put("NumPadDivide", Key.NumPadDivide)
    put("NumPadDot", Key.NumPadDot)
}

private val KEY_TO_NAME: Map<Key, String> = NAME_TO_KEY.entries.associate { (name, key) -> key to name }

internal fun nameOf(key: Key): String = KEY_TO_NAME[key] ?: "?"

/** True for a key the recorder cannot produce a name for, i.e. anything outside [NAME_TO_KEY]. */
internal fun isBindableKey(key: Key): Boolean = key in KEY_TO_NAME

/** Bare modifier presses carry no meaning on their own and are never valid as a binding key. */
internal fun isModifierKey(key: Key): Boolean = when (key) {
    Key.ShiftLeft, Key.ShiftRight,
    Key.CtrlLeft, Key.CtrlRight,
    Key.AltLeft, Key.AltRight,
    Key.MetaLeft, Key.MetaRight,
    -> true
    else -> false
}

/**
 * Bindings the registry refuses.
 *
 * `Esc` is how a capture is abandoned, so no Escape chord can be recorded. A bare `Space` is a held
 * latch in the canvas (press starts panning, release stops) rather than a discrete event, and
 * letting it be swallowed by a recorder would leave the latch stuck on; `Ctrl+Space` and friends
 * stay free.
 */
internal fun isReservedBinding(binding: KeyBinding): Boolean {
    if (isModifierKey(binding.key)) return true
    if (binding.key == Key.Escape) return true
    if (binding.key == Key.Spacebar && !binding.ctrl && !binding.shift && !binding.alt) return true
    return false
}

private val IS_MAC: Boolean =
    System.getProperty("os.name").orEmpty().lowercase().contains("mac")
