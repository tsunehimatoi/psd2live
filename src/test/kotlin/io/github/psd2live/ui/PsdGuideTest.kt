package io.github.psd2live.ui

import io.github.psd2live.application.PsdGuideBindings
import io.github.psd2live.application.WorkspacePsdGuide
import io.github.psd2live.core.DownloadState
import io.github.psd2live.core.MeshComponentSplit
import io.github.psd2live.core.SemanticTag
import io.github.psd2live.core.Side
import io.github.psd2live.core.StandardParameters
import io.github.psd2live.core.modifier.RigModifierStack
import io.github.psd2live.document.WorkspaceSourceLayer
import io.github.psd2live.project.ProjectRepository
import io.github.psd2live.i18n.AppLanguage
import io.github.psd2live.i18n.I18n
import io.github.psd2live.i18n.tr
import io.github.psd2live.tools.output
import io.github.psd2live.tools.renderPng
import io.github.psd2live.ui.theme.ToolColors
import io.github.psd2live.ui.views.PsdGuidePage
import io.github.psd2live.ui.views.FaceGrid
import io.github.psd2live.ui.views.GuideRigRenderers
import io.github.psd2live.ui.views.guideRigFrame
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import io.github.psd2live.ui.views.GuideRigCanvas
import io.github.psd2live.ui.views.guidePointer
import io.github.psd2live.scenario.Characters
import io.github.psd2live.ui.state.*
import io.github.psd2live.ui.views.ModifierCountTier
import io.github.psd2live.ui.views.ModifierPresetCategory
import io.github.psd2live.ui.views.ModifierPresetSelection
import io.github.psd2live.ui.views.ModifierSimulationMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.io.TempDir
import org.umamo.format.art.ChannelMask
import org.umamo.format.art.LayerBlend
import org.umamo.format.art.LayerBounds
import org.umamo.format.art.LayerId
import org.umamo.format.art.LayerRaster
import org.umamo.format.art.SourceLayerKind
import org.umamo.format.moc3.Moc3
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.test.*

class PsdGuideTest {
    @TempDir lateinit var temp: Path

    @Test fun recentAndDropOpenAnIsolatedGuideAndDiscardKeepsTheOriginal() = editor { vm, workspace ->
        val first = Characters.headPsd(temp)
        val second = Characters.eyesPsd(temp)
        workspace.host.importPsd(first.toString(), discardUnsaved = false, modifiers = io.github.psd2live.application.WorkspaceInitialModifiers.EMPTY)
        val original = workspace.snapshot()
        fun assertOriginal() {
            val current = workspace.snapshot()
            assertEquals(original.projectId, current.projectId)
            assertEquals(original.state, current.state)
            assertEquals(original.historyHeadNodeId, current.historyHeadNodeId)
            assertEquals(original.projectDirty, current.projectDirty)
        }
        var confirmations = 0
        vm.confirmUnsavedChanges = { confirmations++; 2 }

        vm.openRecentFile(second.toString())
        val guide = vm.psdGuide
        assertEquals(TaskProgress(tr("psdGuide.reading")),
            taskProgress(vm.state.value, null, DownloadState.Idle, guide = guide.taskProgress))
        settled(guide)
        assertEquals(AppPage.PSD_GUIDE, vm.activePage)
        assertOriginal()
        assertEquals(0, confirmations)
        guide.go(PsdGuideStep.BINDING)
        vm.openSettings()
        assertEquals(AppPage.START, vm.activePage)
        assertEquals(StartSection.SETTINGS, vm.startSection)
        vm.showPage(AppPage.PSD_GUIDE)
        assertEquals(PsdGuideStep.BINDING, guide.step)
        vm.closePage(AppPage.PSD_GUIDE)
        assertTrue(guide.confirmDiscard)
        vm.discardPsdGuide()
        assertNull(vm.activePage)
        assertOriginal()

        vm.dropFiles(listOf(second.toFile()), null, null)
        settled(guide)
        assertEquals(second, guide.path)
        assertOriginal()
        assertEquals(0, confirmations)
    }

    @Test fun splitPresetAndAppliedStyleInstallInOneNodeAndSurviveArchiveAndExport() = editor { vm, workspace ->
        val path = Characters.eyesPsd(temp)
        vm.openExternalFile(path.toString())
        val guide = vm.psdGuide
        settled(guide)
        if (!guide.splitScanned) { guide.scan(); settled(guide) }
        captureGuide(vm, "split")
        val original = assertNotNull(guide.splitItems.singleOrNull())
        guide.showSplitPreview(original.offer.layerId)
        captureGuide(vm, "split-focused")
        guide.showSplitPreview(null)
        guide.split(listOf(original))
        settled(guide)
        assertNull(workspace.snapshot().projectId)
        guide.go(PsdGuideStep.BINDING)
        captureGuide(vm, "binding")
        val report = assertNotNull(guide.bindings)
        assertEquals(setOf(Side.LEFT, Side.RIGHT),
            report.layers.filter { it.source.name.startsWith(original.offer.layerName + "-") }.map { it.semantic.side }.toSet())
        val layer = report.layers.single { it.source.name == "top" }
        val correction = PsdGuideBindings.classification(layer).copy(tag = SemanticTag.TOPWEAR)
        guide.classify(layer.source.id.raw, correction)
        settled(guide)
        guide.go(PsdGuideStep.SPLIT)
        guide.go(PsdGuideStep.BINDING)
        assertEquals(correction, guide.bindings?.manual?.get(layer.source.id.raw))
        guide.go(PsdGuideStep.PRESET)
        settled(guide)
        guide.selectPreset(ModifierPresetSelection(ModifierPresetCategory.MODERN, ModifierSimulationMode.NONE,
            ModifierCountTier.STANDARD, null))
        settled(guide)
        assertNotNull(guide.preset)
        captureGuide(vm, "preset")
        guide.go(PsdGuideStep.FACE)
        captureGuide(vm, "face")
        val draft = assertNotNull(guide.guide)
        val candidate = assertNotNull(guide.preset)
        val turn = assertNotNull(draft.turn(candidate))
        assertEquals(9, guide.batch.size)
        val previousLoader = Any()
        val loader = Any()
        guide.trackStyleProgress(previousLoader, guide.batch, 0)
        guide.trackStyleProgress(loader, guide.batch, 3)
        guide.clearStyleProgress(previousLoader)
        assertEquals(3f / 9f, taskProgress(vm.state.value, null, DownloadState.Idle, guide = guide.taskProgress)?.fraction)
        guide.trackStyleProgress(loader, guide.batch, 9)
        assertNull(guide.taskProgress)
        val option = guide.batch.first()
        val previews = coroutineScope {
            guide.batch.map { style -> async { draft.prepareStyle(candidate, turn, style.values) } }.awaitAll()
        }
        verifyTrackingOnCanvas(previews.first(), turn.x.id, turn.x.min)
        if (System.getenv("PSD2LIVE_TOOLS") == "1") {
            val frames = previews.mapIndexed { index, preview ->
                index to guideRigFrame(preview, mapOf(turn.x.id to turn.x.max))
            }.toMap()
            GuideRigRenderers().use { renderers ->
                renderPng(output("psd-guide").resolve("face-grid-live.png"), 1080, 660, frames = 0) {
                    FaceGrid(guide.batch, frames, emptySet(), option, true, renderers, false, {}, {})
                }
            }
        }
        guide.selectedStyle = option
        guide.applyStyle()
        settled(guide)
        assertEquals(option, guide.appliedStyle)
        assertNull(workspace.snapshot().projectId)

        vm.createPsdGuideProject()
        settled(guide)
        assertEquals(1, workspace.host.history().nodes.size)
        assertEquals(WorkspacePreset.EDIT, vm.state.value.activeWorkspace.preset)
        assertTrue(path.toString() in vm.state.value.recentFiles)
        val parsedStack = RigModifierStack.fromJson(assertNotNull(vm.state.value.rigEdits.modifierStack))
        assertTrue(parsedStack.instances.all { it.presetGroup == "modern-light" })
        val config = parsedStack.instances.single { it.id == turn.instance.id }.config
        val generatedValues = config.getValue("values").jsonObject
        assertEquals(option.values, option.values.keys.associateWith { generatedValues.getValue(it).jsonPrimitive.float })

        val saved = temp.resolve("guided.psd2live")
        vm.saveProjectNow(saved)
        ProjectRepository().open(saved).use { reopened ->
            val document = reopened.history.head().snapshot
            assertEquals(vm.state.value.rigEdits.modifierStack, document.rigEdits.modifierStack)
            assertEquals(vm.state.value.layerOverrides, document.layerOverrides)
            assertTrue(original.offer.layerId in document.deletedLayerIds)
            assertEquals(correction, document.layerOverrides[layer.source.id.raw])
        }
        val output = Files.createDirectory(temp.resolve("export"))
        workspace.host.exportModel(assertNotNull(workspace.host.stateToken()), output.toString())
        Files.walk(output).use { files ->
            val binary = files.filter { it.extension == "moc3" }.findFirst().orElseThrow()
            val exported = org.umamo.interop.moc3.import.Moc3Import.fromMocDocument(Moc3.read(Files.readAllBytes(binary)), null)
            assertTrue(exported.parameters.any { it.id == StandardParameters.ANGLE_X })
        }
    }

    @Test fun defaultSplitMatchesHorizontalVerticalAndMultiPartNamingRules() {
        fun planFor(vararg centers: Pair<Float, Float>): MeshComponentSplit.Plan {
            val width = centers.size
            val rgba = ByteArray(width * 4) { index -> if (index % 4 == 3) (-1).toByte() else 0 }
            val source = WorkspaceSourceLayer(
                id = LayerId("layer"),
                name = "layer",
                groupPath = "",
                kind = SourceLayerKind.Raster,
                visible = true,
                order = 0,
                bounds = LayerBounds(0, 0, width, 1),
                opacity = 1f,
                clipped = false,
                blend = LayerBlend.Normal,
                channelMask = ChannelMask.ALL,
                raster = LayerRaster(width, 1, rgba),
                sourceAssetId = null,
                sourceSpatialReferenceId = null,
                derived = false,
            )
            val components = centers.map { (x, y) -> MeshComponentSplit.Component(x, y) }
            return MeshComponentSplit.Plan(components, IntArray(width) { it }, source)
        }

        val horizontal = WorkspacePsdGuide.defaultSplit("eyes-id", "eyes", planFor(90f to 20f, 10f to 25f))
        assertEquals("eyes-id", horizontal.id)
        assertEquals(listOf("eyes-l", "eyes-r"), horizontal.names)
        assertEquals(listOf(Side.LEFT, Side.RIGHT), horizontal.sides)

        val vertical = WorkspacePsdGuide.defaultSplit("mouth-id", "mouth", planFor(50f to 80f, 52f to 10f))
        assertEquals("mouth-id", vertical.id)
        assertEquals(listOf("mouth-b", "mouth-t"), vertical.names)
        assertEquals(listOf(Side.NONE, Side.NONE), vertical.sides)

        val threePart = WorkspacePsdGuide.defaultSplit("hair-id", "hair", planFor(90f to 10f, 10f to 20f, 50f to 30f))
        assertEquals("hair-id", threePart.id)
        assertEquals(listOf("hair-1", "hair-2", "hair-3"), threePart.names)
        assertEquals(listOf(Side.NONE, Side.NONE, Side.NONE), threePart.sides)
    }

    @Test fun skipPsdGuideOnceAppliesDefaultsAndKeepsGuideEnabled() = editor { vm, workspace ->
        val path = Characters.eyesPsd(temp)
        vm.openExternalFile(path.toString())
        val guide = vm.psdGuide
        settled(guide)
        assertEquals(AppPage.PSD_GUIDE, vm.activePage)
        val eyesLayerId = assertNotNull(guide.source).model.analysis.layers.single { it.source.name == "eyes" }.source.id.raw

        vm.skipPsdGuideOnce()
        settled(guide)
        assertFalse(guide.saving)
        assertNull(vm.activePage)
        assertFalse(AppPage.PSD_GUIDE in vm.openPages)
        assertTrue(vm.state.value.showPsdImportGuide)
        assertEquals(WorkspacePreset.EDIT, vm.state.value.activeWorkspace.preset)
        assertEquals(1, workspace.host.history().nodes.size)
        val layers = assertNotNull(vm.state.value.analysis).source.layers
        val splitLayers = layers.filter { it.name.startsWith("eyes-") }
        assertEquals(setOf("eyes-r", "eyes-l"), splitLayers.map { it.name }.toSet())
        assertEquals(
            mapOf("eyes-r" to Side.RIGHT, "eyes-l" to Side.LEFT),
            splitLayers.associate { it.name to vm.state.value.layerOverrides[it.id.raw]?.side },
        )
        assertTrue(eyesLayerId in vm.state.value.deletedLayerIds)
        val stack = RigModifierStack.fromJson(assertNotNull(vm.state.value.rigEdits.modifierStack))
        assertTrue(stack.instances.isNotEmpty())
        assertTrue(stack.instances.all { it.presetGroup == "modern" })
    }

    @Test fun disablePsdGuideAndImportBypassesGuideOnSubsequentOpens() = editor { vm, workspace ->
        val previousShowGuide = AppSettings.showPsdImportGuide
        try {
            val first = Characters.eyesPsd(Files.createDirectory(temp.resolve("first"))).toAbsolutePath().normalize()
            val second = Characters.eyesPsd(Files.createDirectory(temp.resolve("second"))).toAbsolutePath().normalize()
            vm.confirmUnsavedChanges = { 1 }

            vm.openExternalFile(first.toString())
            val guide = vm.psdGuide
            settled(guide)
            assertEquals(AppPage.PSD_GUIDE, vm.activePage)

            vm.disablePsdGuideAndImport()
            settled(guide)
            assertFalse(guide.saving)
            assertFalse(vm.state.value.showPsdImportGuide)
            assertTrue(AppPrompt.PSD_IMPORT_GUIDE in vm.state.value.mutedPrompts)
            assertFalse(AppSettings.showPsdImportGuide)
            assertNull(vm.activePage)
            assertFalse(AppPage.PSD_GUIDE in vm.openPages)
            assertEquals(WorkspacePreset.EDIT, vm.state.value.activeWorkspace.preset)
            assertEquals(1, workspace.host.history().nodes.size)
            assertEquals(first.toString(), vm.state.value.inputPath)
            val firstSplitLayers = assertNotNull(vm.state.value.analysis).source.layers.filter { it.name.startsWith("eyes-") }
            assertEquals(setOf("eyes-r", "eyes-l"), firstSplitLayers.map { it.name }.toSet())
            val firstStack = RigModifierStack.fromJson(assertNotNull(vm.state.value.rigEdits.modifierStack))
            assertTrue(firstStack.instances.isNotEmpty())
            assertTrue(firstStack.instances.all { it.presetGroup == "modern" })

            vm.openExternalFile(second.toString())
            settled(guide)
            assertFalse(guide.saving)
            assertNull(guide.path)
            assertNull(vm.activePage)
            assertFalse(AppPage.PSD_GUIDE in vm.openPages)
            assertEquals(second.toString(), vm.state.value.inputPath)
            assertEquals(1, workspace.host.history().nodes.size)
            val secondSplitLayers = assertNotNull(vm.state.value.analysis).source.layers.filter { it.name.startsWith("eyes-") }
            assertEquals(setOf("eyes-r", "eyes-l"), secondSplitLayers.map { it.name }.toSet())
            val secondStack = RigModifierStack.fromJson(assertNotNull(vm.state.value.rigEdits.modifierStack))
            assertTrue(secondStack.instances.isNotEmpty())
            assertTrue(secondStack.instances.all { it.presetGroup == "modern" })

            vm.openRecentFile(first.toString())
            settled(guide)
            assertFalse(guide.saving)
            assertNull(guide.path)
            assertNull(vm.activePage)
            assertFalse(AppPage.PSD_GUIDE in vm.openPages)
            assertEquals(first.toString(), vm.state.value.inputPath)
            assertEquals(1, workspace.host.history().nodes.size)
            val recentSplitLayers = assertNotNull(vm.state.value.analysis).source.layers.filter { it.name.startsWith("eyes-") }
            assertEquals(setOf("eyes-r", "eyes-l"), recentSplitLayers.map { it.name }.toSet())
        } finally {
            AppSettings.setPromptEnabled(AppPrompt.PSD_IMPORT_GUIDE, previousShowGuide)
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private suspend fun verifyTrackingOnCanvas(preview: io.github.psd2live.application.WorkspacePsdGuide.Preview,
                                                axis: org.umamo.runtime.model.ParameterId, padValue: Float) {
        val pose = mapOf(axis to padValue)
        var cursor by mutableStateOf<Offset?>(null)
        GuideRigRenderers().use { renderers ->
            val scene = ImageComposeScene(320, 220, density = Density(1f), coroutineContext = currentCoroutineContext()) {
                GuideRigCanvas(guideRigFrame(preview, pose, cursor), renderers,
                    Modifier.fillMaxSize().guidePointer(true) { cursor = it })
            }
            try {
                fun snapshot(time: Long): ByteArray = scene.render(time).use { image ->
                    requireNotNull(image.encodeToData()).use { it.bytes }
                }
                val base = snapshot(0)
                scene.sendPointerEvent(PointerEventType.Move, Offset(300f, 110f))
                val tracked = snapshot(16_000_000)
                assertNotNull(cursor)
                assertFalse(base.contentEquals(tracked), "Pointer tracking must move the rendered rig")
                scene.sendPointerEvent(PointerEventType.Exit, Offset(321f, 110f))
                val restored = snapshot(32_000_000)
                assertNull(cursor)
                assertContentEquals(base, restored, "Leaving the canvas must restore the pad's pose")
            } finally {
                scene.close()
            }
        }
    }

    private fun editor(body: suspend (PSD2LiveViewModel, DesktopWorkspace) -> Unit) = runBlocking<Unit> {
        withContext(Dispatchers.Main) {
            PSD2LiveViewModel().use { vm ->
                vm.setStateForTest(
                    vm.state.value.copy(
                        atlasSize = 512,
                        exportCmo3 = false,
                        exportMoc3 = true,
                        mutedPrompts = vm.state.value.mutedPrompts - AppPrompt.PSD_IMPORT_GUIDE - AppPrompt.MESH_SPLITS_ON_IMPORT,
                    ),
                )
                DesktopWorkspace(vm, temp.resolve("store")).use { workspace ->
                    vm.attachWorkspace(workspace)
                    body(vm, workspace)
                }
            }
        }
    }

    private fun captureGuide(vm: PSD2LiveViewModel, step: String) {
        if (System.getenv("PSD2LIVE_TOOLS") != "1") return
        val language = I18n.currentLanguage
        val directory = output("psd-guide")
        try {
            AppLanguage.entries.forEachIndexed { index, locale ->
                val narrow = index % 2 == 1
                I18n.setLanguage(locale, persist = false)
                renderPng(directory.resolve("$step-${locale.tag}.png"), if (narrow) 620 else 1120,
                    630, colors = if (narrow) ToolColors.Light else ToolColors.Dark,
                    fontScale = if (narrow) 1.2f else 1f, frames = 0) { PsdGuidePage(vm) }
            }
        } finally {
            I18n.setLanguage(language, persist = false)
        }
    }

    private suspend fun settled(guide: PsdGuideState) {
        withTimeout(50_000) { guide.awaitIdle() }
        assertFalse(guide.busy)
        assertNull(guide.taskProgress)
        assertNull(guide.error)
    }
}
