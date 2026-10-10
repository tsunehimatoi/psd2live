package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.format.compile.document.ContentHash
import io.github.psd2live.i18n.AppLanguage
import io.github.psd2live.i18n.I18n
import io.github.psd2live.project.*
import io.github.psd2live.targets.cubism.PuppetIr
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/**
 * A project whose journal still holds records only older builds wrote replays them once: its first edit checkpoints the
 * authored rig where the journal ended, and later builds start there.
 */
class LegacyMaterializeOnceTest {
	@TempDir lateinit var temporary: Path
	private val builder = WorkspacePreviewBuilder()
	private val language = I18n.currentLanguage

	@BeforeTest fun pinLanguage() = I18n.setLanguage(AppLanguage.CHINESE, persist = false)
	@AfterTest fun restore() { I18n.setLanguage(language, persist = false); MaterializedRigStore.clear() }

	private fun hash(model: RigPreviewModel) = ContentHash.of(PuppetIr.toIr(model.rig.puppet))

	@Test fun theFirstEditOfALegacySplitProjectCheckpointsItOnce() = runBlocking<Unit> {
		val target = temporary.resolve("legacy-split.psd2live")
		LegacyMaterializeOnceTest::class.java.getResourceAsStream("/projects/legacy-split.psd2live")!!.use { Files.copy(it, target) }
		ProjectRepository().open(target).use { opened ->
			val head = opened.history.head().snapshot
			assertTrue(head.rigEdits.replaysLegacy)
			val runtime = WorkspaceRuntime<RigPreviewModel>({ builder.build(it) }, rebuildFrom = { document, previous -> builder.build(document, previous) })
			val start = builder.build(head)
			runtime.install(runtime.state.value.state, opened.projectId, head, start)
			suspend fun create(id: String): WorkspaceCapture<RigPreviewModel> {
				val before = runtime.capture()
				return WorkspaceDocumentCommands(runtime).execute(before.projectId, before.state, id, listOf(WorkspaceDocumentOperation("parameter_create",
					buildJsonObject { put("parameter_id", id); put("name", id) })), MutationAuthor.USER).capture
			}
			val first = create("First")
			val journal = first.document.rigEdits.authoringJournal
			assertEquals(head.rigEdits.authoringJournal, journal.subList(0, head.rigEdits.authoringJournal.size))
			assertTrue(RigCheckpoint.isRecord(journal[head.rigEdits.authoringJournal.size]), "the checkpoint is where the old journal ended")
			assertFalse(first.document.rigEdits.replaysLegacy)
			// The rig is what the old records replayed to, plus the new parameter.
			val replayed = builder.build(head.copy(rigEdits = head.rigEdits.copy(authoringJournal = head.rigEdits.authoringJournal + journal.last())))
			assertEquals(hash(replayed), hash(first.model))
			val second = create("Second")
			assertEquals(1, second.document.rigEdits.authoringJournal.count(RigCheckpoint::isRecord), "only once")
			MaterializedRigStore.clear()
			val cold = builder.build(second.document)
			assertFalse(cold.sources.baseKnown, "the old records are not replayed again")
			assertEquals(hash(second.model), hash(cold))
		}
	}
}
