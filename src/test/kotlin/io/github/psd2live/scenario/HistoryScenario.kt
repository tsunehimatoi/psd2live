package io.github.psd2live.scenario

import io.github.psd2live.core.RigCheckpoint
import io.github.psd2live.core.RigEditOverlay
import org.junit.jupiter.api.io.TempDir
import org.umamo.runtime.model.DrawableId
import java.nio.file.Path
import kotlin.test.Test

/**
 * A long session's history: more edits than one checkpoint interval holds, undone part way, a new branch started,
 * the old branch checked out again, a topology edit undone, and the whole tree saved and reopened. Every node shows
 * the same rig whichever way it is reached, and a cold build after reopening agrees with the live one.
 */
class HistoryScenario {
	@TempDir lateinit var temp: Path

	@Test fun branchesPastCheckpointsUndoAndReopen() = Studio.run(temp, Characters.head(physics = false), checks = setOf(Studio.Check.REPLAY)) {
		repeat(RigEditOverlay.CHECKPOINT_INTERVAL + 3) { edit("axis $it", "parameter_create", req("parameter_id" to "P$it", "name" to "P$it")) }
		val journal = now.document.rigEdits.authoringJournal
		expect("checkpoints", journal.count(RigCheckpoint::isRecord) >= 2) { "a long journal was not checkpointed again" }
		val tip = now; val tipHash = hash()
		repeat(4) { undo("undo $it") }
		val fork = now
		expect("undone", puppet.parameters.none { it.id.raw == "P${RigEditOverlay.CHECKPOINT_INTERVAL}" }) { "undo past a checkpoint kept a later axis" }
		val face = DrawableId(mesh("face"))
		edit("subdivide the face's first triangle", "canvas_topology", req("id" to face.raw, "action" to "subdivide",
			"vertices" to puppet.drawables.single { it.id == face }.mesh!!.indices.take(3).toList()))
		val branch = now; val branchHash = hash()
		expect("branch", branch.historyHead != tip.historyHead && head().parentId == fork.historyHead) { "the edit after undo did not start a branch" }
		checkout("back to the first branch", tip.historyHead)
		expect("first branch", hash() == tipHash) { "the first branch's tip shows another rig" }
		Oracles.replays(this, "first branch")
		checkout("to the new branch", branch.historyHead)
		expect("new branch", hash() == branchHash) { "the new branch's tip shows another rig" }
		undo("undo the topology edit")
		expect("topology undone", hash() == Oracles.hash(fork.model.rig.puppet)) { "undoing the topology edit did not restore the mesh" }
		redo("redo it", child = branch.historyHead)
		reopen()
		expect("reopened", hash() == branchHash) { "the reopened branch tip differs" }
		checkout("first branch after reopening", tip.historyHead)
		expect("reopened", hash() == tipHash) { "the reopened first branch differs" }
		Oracles.replays(this, "first branch after reopening")
	}
}
