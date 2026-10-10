package io.github.psd2live.scenario

import io.github.psd2live.application.WorkspacePreviewBuilder
import io.github.psd2live.core.RigPreviewModel
import io.github.psd2live.format.compile.document.ContentHash
import io.github.psd2live.i18n.AppLanguage
import io.github.psd2live.i18n.I18n
import io.github.psd2live.project.*
import io.github.psd2live.targets.cubism.PuppetIr
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * Projects saved by earlier versions keep working: every revision of their history opens to the rig and revision
 * that version saved, without the journal being rewritten on read, and the user can carry on editing, save in the
 * current format and reopen.
 */
class ProjectFileScenario {
	@TempDir lateinit var temp: Path
	private val language = I18n.currentLanguage

	// Generated names follow the UI language; the legacy fixture was saved, and its hashes taken, in Chinese.
	@BeforeTest fun pinLanguage() = I18n.setLanguage(AppLanguage.CHINESE, persist = false)
	@AfterTest fun restoreLanguage() = I18n.setLanguage(language, persist = false)

	private fun fixture(name: String): Path = temp.resolve(name).also { target ->
		javaClass.getResourceAsStream("/projects/$name")!!.use { Files.copy(it, target) }
	}

	/** Opens [file] as a workspace at its head, the way a cold process does. */
	private fun Studio.openArchive(file: Path): OpenedProject = step("open ${file.fileName}") {
		forgetBuilds()
		val project = ProjectRepository().open(file)
		val head = project.history.head().snapshot
		runtime.install(runtime.state.value.state, project.projectId, head, builder.build(head), project.history.state(), discardUnsaved = true)
		project
	}

	/**
	 * A v1 project (a branched history with a swing override, motion settings and a staged asset, written by the v1
	 * writer): every node decodes to the revision it was saved under, and saved again in the current format and
	 * reopened it keeps its nodes, revisions and documents.
	 */
	@Test fun aVersionOneProjectKeepsItsRevisionsThroughAVersionTwoSave() = runBlocking<Unit> {
		val saved = temp.resolve("migrated.psd2live")
		val before = ProjectRepository().open(fixture("v1-sample.psd2live")).use { project ->
			val selections = project.history.selections()
			for (selection in selections) kotlin.test.assertEquals(selection.node.revisionId, WorkspaceRevisions.of(selection.snapshot),
				"node '${selection.node.summary}' decodes to another revision")
			ProjectRepository(writeHeadCache = false).save(ProjectSaveCapture(project.projectId, project.history.state(), project.presentation,
				project.source, project.store), saved)
			selections.associate { it.node.id to (it.node.revisionId to it.snapshot) }
		}
		ProjectRepository().open(saved).use { project ->
			kotlin.test.assertEquals(ProjectFormatV2.VERSION, project.formatVersion)
			val after = project.history.selections().associate { it.node.id to (it.node.revisionId to it.snapshot) }
			kotlin.test.assertEquals(before.mapValues { it.value.first }, after.mapValues { it.value.first }, "revisions changed through a v2 save")
			for ((id, value) in before) kotlin.test.assertEquals(WorkspaceRevisions.of(value.second), WorkspaceRevisions.of(after.getValue(id).second), "node $id")
		}
	}

	/**
	 * A project split before splits were materialized (a component split that soft-deleted its original, a depth
	 * split): every node replays to the rig that build replayed, a new split beside the old ones works, and the
	 * soft-deleted original can be restored.
	 */
	@Test fun aLegacySplitProjectReplaysAsSavedAndTakesNewSplits() = Studio.run(temp) {
		val hashes = mapOf(
			"history-a8855e4e-2343-4c25-ac37-5bbd5975f837" to "4a0b26852596e8ebcb3371a571667378edc016d7e515258298ecd7aabd2ff6a0",
			"history-35307cf9-8818-4ab3-9203-604da84ce587" to "b4290513e609f3c64a16989f2b247e9dc72db16f5cde4621f07c2bd6f94abb02",
			"history-9720a4c9-5233-4a5b-848c-220ad1c5d75c" to "ca99aaf7971bbbc12c7a3f3783cb3df16a93a213e60aee61a34e1cca61353af7",
			"history-0493aaf9-0a1c-4310-baf5-5896117ab92e" to "1c0d97c54f189f64a79c4f4f877a8b4e7f153658249d63b65d1f01bb8b48e7db",
		)
		// The IR's advanced block came after these hashes were taken; it is empty here and left out.
		fun legacyHash(model: RigPreviewModel): String {
			val ir = PuppetIr.toIr(model.rig.puppet)
			return ContentHash.of(ir.toString().removeSuffix(", advanced=${ir.advanced})") + ")")
		}
		openArchive(fixture("legacy-split.psd2live")).use { project ->
			for (selection in project.history.selections()) {
				val built = runBlocking { WorkspacePreviewBuilder().build(selection.snapshot) }
				expect("legacy replay", legacyHash(built) == hashes[selection.node.id]) { "node '${selection.node.summary}' replays to another rig" }
			}
		}
		edit("restore the soft-deleted original", "layer_restore", req("layer_ids" to listOf("islands")))
		expect("restored", "islands" in now.model.rig.layerIdByDrawableId.values) { "the original did not come back" }
		edit("split another layer", "source_split_polygon", req("layer_id" to "other", "names" to listOf("Left", "Right"),
			"piece_ids" to listOf("left", "right"), "polygon" to listOf(listOf(0, 0), listOf(23, 0), listOf(23, 24), listOf(0, 24))))
		reopen()
	}
}
