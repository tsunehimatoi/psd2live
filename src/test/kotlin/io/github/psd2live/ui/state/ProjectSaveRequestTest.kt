package io.github.psd2live.ui.state

import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.components.distinctProjectName
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.*

/** Save and Save As asked for while an edit is being applied, and the name Save As suggests. */
class ProjectSaveRequestTest {
	@TempDir lateinit var temporary: Path

	private fun workspace(action: suspend (PSD2LiveViewModel) -> Unit) = runBlocking {
		val png = temporary.resolve("body.png")
		val image = BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB)
		for (y in 0 until 8) for (x in 0 until 8) image.setRGB(x, y, 0xff7799bb.toInt())
		ImageIO.write(image, "png", png.toFile())
		PSD2LiveViewModel().use { vm ->
			vm.setStateForTest(vm.state.value.copy(meshOnly = true, atlasSize = 256, generatePhysics = false, exportMoc3 = false))
			DesktopWorkspace(vm, temporary.resolve("store")).use { backend ->
				vm.attachWorkspace(backend)
				backend.createArtwork(buildJsonObject { put("width", 8); put("height", 8); putJsonArray("layers") { add(buildJsonObject {
					put("path", png.toString()); put("name", "body"); put("role", "topwear")
				}) } })
				action(vm)
			}
		}
	}

	private fun PSD2LiveViewModel.busy(busy: Boolean, projectFile: Path? = null) =
		setStateForTest(state.value.copy(canvasEditBusy = busy, projectFile = projectFile?.toString() ?: state.value.projectFile))

	@Test fun aQueuedSaveAsIsNotDowngradedByALaterSaveAndOpensItsDialog() = workspace { vm ->
		val project = temporary.resolve("open.psd2live")
		vm.busy(true, project)
		vm.requestProjectSave(saveAs = true)
		assertEquals(tr("project.saveAsQueued"), vm.state.value.statusText, "the queued request is reported")
		vm.requestProjectSave(saveAs = false)
		assertEquals(true, vm.queuedProjectSave, "Ctrl+S after Save As keeps the Save As")
		assertEquals(tr("project.saveAsQueued"), vm.state.value.statusText)
		assertFalse(vm.state.value.showProjectLocationDialog)
		vm.runQueuedProjectSave()
		assertEquals(true, vm.queuedProjectSave, "nothing runs while the edit is still being applied")
		vm.busy(false)
		vm.runQueuedProjectSave()
		assertNull(vm.queuedProjectSave)
		assertTrue(vm.state.value.showProjectLocationDialog, "the Save As dialog opens instead of a plain save to the open file")
		assertFalse(vm.state.value.projectSaving)
		assertFalse(Files.exists(project))
	}

	@Test fun aSaveAsAfterAQueuedSaveWins() = workspace { vm ->
		vm.busy(true, temporary.resolve("open.psd2live"))
		vm.requestProjectSave(saveAs = false)
		assertEquals(false, vm.queuedProjectSave)
		assertEquals(tr("project.saveQueued"), vm.state.value.statusText)
		vm.requestProjectSave(saveAs = true)
		assertEquals(true, vm.queuedProjectSave)
	}

	@Test fun aQueuedPlainSaveSavesTheOpenFileOnceTheEditFinishes() = workspace { vm ->
		val project = temporary.resolve("open.psd2live")
		vm.busy(true, project)
		vm.requestProjectSave()
		vm.busy(false)
		vm.runQueuedProjectSave()
		assertNull(vm.queuedProjectSave)
		assertFalse(vm.state.value.showProjectLocationDialog)
		withTimeout(30_000) { while (!Files.isRegularFile(project) || vm.state.value.projectSaving) delay(20) }
		assertNotEquals(tr("project.saveQueued"), vm.state.value.statusText)
	}

	@Test fun saveAsSuggestsANameThatIsNeitherTheOpenFileNorAnExistingOne() {
		val open = Files.createFile(temporary.resolve("model.psd2live"))
		assertEquals("model-2", distinctProjectName(temporary, "model", open))
		Files.createFile(temporary.resolve("model-2.psd2live"))
		assertEquals("model-3", distinctProjectName(temporary, "model", open))
		assertEquals("model-3", distinctProjectName(temporary, "model-2", temporary.resolve("model-2.psd2live")))
		// The open file counts as taken even before its first save has written it.
		assertEquals("draft-2", distinctProjectName(temporary, "draft", temporary.resolve("draft.psd2live")))
		assertEquals("fresh", distinctProjectName(temporary, "fresh", null))
		assertEquals("model-3", distinctProjectName(temporary, "model", null))
	}
}
