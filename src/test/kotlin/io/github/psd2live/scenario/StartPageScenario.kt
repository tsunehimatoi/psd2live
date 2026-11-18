package io.github.psd2live.scenario

import io.github.psd2live.application.ImageLayerInsertion
import io.github.psd2live.core.Side
import io.github.psd2live.core.modifier.RigModifierStack
import io.github.psd2live.ui.state.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.*

/** The start page: it opens the app, a project opened or started from it takes its place, and it comes back on request. */
class StartPageScenario {
	@TempDir lateinit var temp: Path

	/** The start page's presets are the user's preferences; each test puts back what it found. */
	private fun editor(body: suspend (PSD2LiveViewModel) -> Unit) = runBlocking {
		val choices = AppSettings.startChoices
		val splitPrompt = AppSettings.autoDetectMeshSplitsOnImport
		try {
			PSD2LiveViewModel().use { vm ->
				vm.setStateForTest(vm.state.value.copy(atlasSize = 512, exportMoc3 = false, generatePhysics = false))
				DesktopWorkspace(vm, temp.resolve("store")).use { workspace ->
					vm.attachWorkspace(workspace)
					body(vm)
				}
			}
		} finally {
			AppSettings.startChoices = choices
			AppSettings.autoDetectMeshSplitsOnImport = splitPrompt
		}
	}

	private suspend fun until(what: String, condition: () -> Boolean) {
		withTimeoutOrNull(120_000) { while (!condition()) delay(20) } ?: fail("Timed out waiting for $what")
	}

	private suspend fun settle(vm: PSD2LiveViewModel) {
		withTimeout(60_000) { vm.state.first { !it.workspaceEditBusy && !it.editorDraftBusy && !it.isAnalyzing && !it.isGenerating } }
	}

	/** Imports the figure with [choices] picked, and waits until the model has them. */
	private suspend fun importFigure(vm: PSD2LiveViewModel, choices: StartPresetChoices) {
		vm.setStartChoices(choices)
		vm.setInputPath(Characters.figurePsd(temp).toString())
		vm.analyze(discardUnsaved = true)
		until("the import") { vm.state.value.errorMessage != null || vm.state.value.previewModel != null && !vm.startPageShown }
        assertNull(vm.state.value.errorMessage, "Import failed")
        // Import first opens an unbound preview for the mesh split scan. The selected
        // preset is applied asynchronously after that scan finds no split candidates.
        until("the import preset after the split scan") {
            vm.state.value.errorMessage != null || vm.state.value.rigEdits.modifierStack?.let {
                RigModifierStack.fromJson(it).instances.isNotEmpty()
            } == true
        }
        assertNull(vm.state.value.errorMessage, "Preset initialization failed")
        val stack = RigModifierStack.fromJson(assertNotNull(vm.state.value.rigEdits.modifierStack))
        assertTrue(stack.instances.isNotEmpty())
        assertTrue(stack.instances.none { it.type == "motion" || it.type == "mouth.outline" || it.type == "simulation" })
		settle(vm)
	}

	@Test fun aPsdImportAppliesTheStartPresetsAndOpensTheEditWorkspace() = editor { vm ->
		assertTrue(vm.startPageShown, "The app opens on the start page")
		assertEquals(StartSection.RECENT, vm.startSection, "It opens on the recent files")
		importFigure(vm, StartQuickPreset.MINIMAL.choices)
		assertEquals(WorkspacePreset.EDIT, vm.state.value.activeWorkspace.preset)
		assertFalse(vm.state.value.mouthOutlineEnabled)
		assertNull(vm.pendingMeshSplitBatch, "The single-island fixture needs no split prompt")
		assertNull(vm.pendingMeshSplit)
	}

    @Test fun aPsdImportOffersMeshSplitsBeforeApplyingItsPreset() = editor { vm ->
        vm.setAutoDetectMeshSplitsOnImport(true)
        vm.setStartChoices(StartQuickPreset.MINIMAL.choices)
        vm.setInputPath(Characters.eyesPsd(temp).toString())
        vm.analyze(discardUnsaved = true)

        until("the import split offer") { vm.pendingMeshSplit != null || vm.state.value.errorMessage != null }
        assertNull(vm.state.value.errorMessage)
        val offer = assertNotNull(vm.pendingMeshSplit)
        assertTrue(offer.fromImport)
        assertEquals(2, offer.plan.components.size)
        assertTrue(vm.state.value.rigEdits.modifierStack?.let {
            RigModifierStack.fromJson(it).instances.isEmpty()
        } ?: true)
        vm.dismissMeshSplit()
        until("the preset after keeping the layer") {
            vm.state.value.rigEdits.modifierStack?.let {
                RigModifierStack.fromJson(it).instances.isNotEmpty()
            } == true
        }
        assertNull(vm.pendingMeshSplit)
        assertNotNull(vm.state.value.analysis?.source?.layers?.firstOrNull { it.id.raw == offer.layerId })
    }

    @Test fun mutedImportPromptStillAllowsManualMeshSplitChecks() = editor { vm ->
        vm.setAutoDetectMeshSplitsOnImport(false)
        vm.setStartChoices(StartQuickPreset.MINIMAL.choices)
        vm.setInputPath(Characters.eyesPsd(temp).toString())
        vm.analyze(discardUnsaved = true)

        until("the preset with import checks disabled") {
            vm.state.value.rigEdits.modifierStack?.let {
                RigModifierStack.fromJson(it).instances.isNotEmpty()
            } == true
        }
        assertNull(vm.pendingMeshSplit)
        assertNull(vm.pendingMeshSplitBatch)
        vm.requestMeshSplitScan()
        until("the manual split offer") { vm.pendingMeshSplit != null || vm.state.value.errorMessage != null }
        assertNull(vm.state.value.errorMessage)
        assertFalse(assertNotNull(vm.pendingMeshSplit).fromImport)
    }

    @Test fun aSingleImageRespectsTheImportPromptSetting() = editor { vm ->
        vm.setAutoDetectMeshSplitsOnImport(false)
        importFigure(vm, StartQuickPreset.MINIMAL.choices)
        val image = BufferedImage(120, 48, BufferedImage.TYPE_INT_ARGB)
        image.createGraphics().let { g ->
            try {
                g.fillOval(4, 4, 36, 36)
                g.fillOval(80, 4, 36, 36)
            } finally {
                g.dispose()
            }
        }
        val file = temp.resolve("accessory.png").toFile()
        ImageIO.write(image, "png", file)
        vm.importLayersFromFiles(listOf(file), ImageLayerInsertion())

        until("the imported accessory") {
            vm.state.value.analysis?.source?.layers?.any { it.name.startsWith("accessory") } == true
        }
        withContext(Dispatchers.Main) {
            val source = vm.state.value.analysis!!.source.layers.single { it.name.startsWith("accessory") }
            assertNull(vm.pendingMeshSplit)
            vm.requestMeshSplit(source.id.raw)
        }
        until("the accessory's manual split offer") { vm.pendingMeshSplit != null || vm.state.value.errorMessage != null }
        assertNull(vm.state.value.errorMessage)
        assertFalse(assertNotNull(vm.pendingMeshSplit).fromImport)
        withContext(Dispatchers.Main) { vm.confirmMeshSplit(listOf("pin", "badge"), listOf(Side.NONE, Side.NONE)) }
        until("both new layers") {
            val names = vm.state.value.analysis?.source?.layers.orEmpty().map { it.name }
            "pin" in names && "badge" in names
        }
        assertNull(vm.pendingMeshSplit)
    }

	@Test fun theStartPageComesBackOnRequestAndGoesWithAnyWorkspace() = editor { vm ->
		vm.leaveStartPage()
		vm.showStartPage(StartSection.LAYOUT)
		assertTrue(vm.startPageShown)
		assertEquals(StartSection.LAYOUT, vm.startSection)
		// The workspace already active counts too: its tab is how the user goes back to it.
		vm.setActiveWorkspace(vm.state.value.activeWorkspace.id)
		assertFalse(vm.startPageShown)
		vm.showStartPage()
		vm.addCanvas(CanvasMode.PREVIEW)
		assertFalse(vm.startPageShown)
	}

}
