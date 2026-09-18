package io.github.psd2live.project

import io.github.psd2live.ui.state.PSD2LiveState
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Covers the one-shot view-options migration: workspaces written before the selection-bounds default
 * flipped to off stored `true` for it regardless of what the user wanted, and the revision stamp is
 * what tells those apart from a workspace where the user has since turned it back on.
 */
class WorkspaceStateCodecTest {

    /** A workspace shaped like a real save, with the revision stamp and one view option forced. */
    private fun workspace(selectionBounds: Boolean, revision: Int?): JsonObject {
        val encoded = WorkspaceStateCodec.encode(PSD2LiveState())
        return buildJsonObject {
            encoded.forEach { (key, value) ->
                if (key != "viewOptionsRevision" && key != "workspaceTabs") put(key, value)
            }
            revision?.let { put("viewOptionsRevision", it) }
            putJsonArray("workspaceTabs") {
                encoded.getValue("workspaceTabs").jsonArray.forEach { tab ->
                    val fields = tab.jsonObject
                    add(buildJsonObject {
                        fields.forEach { (key, value) -> if (key != "view") put(key, value) }
                        putJsonObject("view") {
                            fields.getValue("view").jsonObject.forEach { (key, value) -> put(key, value) }
                            put("showSelectionBounds", selectionBounds)
                        }
                    })
                }
            }
        }
    }

    private fun decodedSelectionBounds(json: JsonObject): List<Boolean> =
        WorkspaceStateCodec.decode(json, PSD2LiveState()).workspaceTabs.map { it.view.showSelectionBounds }

    @Test
    fun selectionBoundsDefaultsOff() {
        val state = PSD2LiveState()
        assertTrue(state.workspaceTabs.isNotEmpty())
        assertTrue(state.workspaceTabs.none { it.view.showSelectionBounds })
    }

    @Test
    fun aWorkspaceWrittenBeforeTheRevisionLosesTheOldDefault() {
        val decoded = decodedSelectionBounds(workspace(selectionBounds = true, revision = null))
        assertTrue(decoded.isNotEmpty())
        assertTrue(decoded.none { it }, "the pre-revision `true` is the old default, not a choice")
    }

    @Test
    fun aWorkspaceWrittenBeforeTheRevisionKeepsAnAlreadyOffValue() {
        assertTrue(decodedSelectionBounds(workspace(selectionBounds = false, revision = null)).none { it })
    }

    @Test
    fun theChoiceSurvivesOnceTheRevisionIsStamped() {
        val decoded = decodedSelectionBounds(workspace(selectionBounds = true, revision = 1))
        assertTrue(decoded.isNotEmpty())
        assertTrue(decoded.all { it }, "after the migration ran, turning the option back on has to stick")
    }

    /** Re-saving has to stamp the revision, otherwise the migration would rerun on every load. */
    @Test
    fun encodingStampsTheRevision() {
        val stamped = WorkspaceStateCodec.encode(PSD2LiveState())["viewOptionsRevision"]?.jsonPrimitive?.int
        assertEquals(1, stamped)
    }

    /** The migration is one-shot: a decode followed by a re-encode must not re-apply it. */
    @Test
    fun theMigrationDoesNotRerunOnItsOwnOutput() {
        val migrated = WorkspaceStateCodec.decode(
            workspace(selectionBounds = true, revision = null),
            PSD2LiveState(),
        )
        val turnedBackOn = migrated.copy(
            workspaceTabs = migrated.workspaceTabs.map { it.copy(view = it.view.copy(showSelectionBounds = true)) },
        )
        val reloaded = decodedSelectionBounds(WorkspaceStateCodec.encode(turnedBackOn))
        assertTrue(reloaded.isNotEmpty())
        assertFalse(reloaded.any { !it })
    }
}
