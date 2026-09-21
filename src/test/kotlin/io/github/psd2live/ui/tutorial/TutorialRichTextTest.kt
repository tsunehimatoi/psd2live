package io.github.psd2live.ui.tutorial

import io.github.psd2live.ui.state.ShortcutAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TutorialRichTextTest {
	@Test
	fun parsesKeyAndKbdTokens() {
		val spans = parseTutorialMarkup("Press {key:UNDO} or {kbd:Shift} add")
		assertEquals(5, spans.size)
		assertEquals(TutorialSpan.Text("Press "), spans[0])
		assertEquals(TutorialSpan.Action(ShortcutAction.UNDO), spans[1])
		assertEquals(TutorialSpan.Text(" or "), spans[2])
		assertEquals(TutorialSpan.LiteralKey("Shift"), spans[3])
		assertEquals(TutorialSpan.Text(" add"), spans[4])
	}

	@Test
	fun keepsUnknownKeyTokenAsText() {
		val spans = parseTutorialMarkup("{key:NOT_A_REAL_ACTION}")
		assertTrue(spans.single() is TutorialSpan.Text)
	}

	@Test
	fun parsesChordLiteral() {
		val spans = parseTutorialMarkup("{kbd:Ctrl+Click}")
		assertEquals(TutorialSpan.LiteralKey("Ctrl+Click"), spans.single())
	}
}
