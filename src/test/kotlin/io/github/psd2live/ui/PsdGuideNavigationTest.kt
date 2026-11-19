package io.github.psd2live.ui

import io.github.psd2live.application.PsdGuideBindings
import io.github.psd2live.core.Side
import io.github.psd2live.scenario.Characters
import io.github.psd2live.tools.output
import io.github.psd2live.tools.renderPng
import io.github.psd2live.ui.views.PsdGuidePage
import io.github.psd2live.ui.components.SplitNamesMode
import io.github.psd2live.ui.state.AppPrompt
import io.github.psd2live.ui.state.DesktopWorkspace
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.state.PsdGuideState
import io.github.psd2live.ui.state.PsdGuideStep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.io.TempDir
import org.umamo.format.art.LayerId
import org.umamo.format.art.SourceArt
import org.umamo.format.art.SourceLayer
import org.umamo.format.psd.PsdReader
import org.umamo.format.psd.PsdWriter
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class PsdGuideNavigationTest {
    @TempDir lateinit var temp: Path

    @Test fun guideSkipsAnAutomaticallyCorrectedBindingAndKeepsTheCorrections() {
        val source = Characters.eyed().source
        val path = eyeWhitePsd(source, setOf("white_r", "white_l"))
        withGuide(path) { guide, _ ->
            val report = assertNotNull(guide.bindings)
            assertEquals(setOf(Side.LEFT, Side.RIGHT), report.automatic.values.map { it.side }.toSet())
            assertTrue(report.findings.all { it.kind == PsdGuideBindings.Kind.MISSING })
            assertFalse(PsdGuideStep.BINDING in guide.steps)
            assertEquals(PsdGuideStep.PRESET, guide.nextStep)
            guide.go(assertNotNull(guide.nextStep))
            settled(guide)
            assertEquals(PsdGuideStep.PRESET, guide.step)
            val overrides = assertNotNull(guide.preset).document.layerOverrides
            report.automatic.forEach { (id, correction) -> assertEquals(correction, overrides[id]) }
            assertEquals(PsdGuideStep.SPLIT, guide.previousStep)
            guide.go(assertNotNull(guide.previousStep))
            assertEquals(PsdGuideStep.SPLIT, guide.step)
            guide.go(PsdGuideStep.PRESET)
            guide.reviewAutomaticBindings()
            assertEquals(PsdGuideStep.BINDING, guide.step)
            assertEquals(PsdGuideStep.PRESET, guide.nextStep)
        }
    }

    @Test fun guideRechecksTheRouteWhenSplittingRemovesAnAmbiguousBinding() {
        val source = PsdReader.read(Files.readAllBytes(Characters.eyesPsd(temp)))
        val path = eyeWhitePsd(source, setOf("eyes"))
        withGuide(path) { guide, _ ->
            assertEquals(PsdGuideStep.BINDING, guide.nextStep)
            guide.go(assertNotNull(guide.nextStep))
            assertEquals(PsdGuideStep.BINDING, guide.step)
            guide.go(assertNotNull(guide.previousStep))
            if (!guide.splitScanned) { guide.scan(); settled(guide) }
            val item = guide.splitItems.single()
            assertNull(guide.splitFocus)
            guide.showSplitPreview(item.offer.layerId)
            assertEquals(item.offer.layerId, guide.splitFocus)
            guide.showSplitPreview(null)
            assertNull(guide.splitFocus)
            assertTrue(item.isSelected)
            guide.showSplitPreview(item.offer.layerId)
            guide.split(listOf(item))
            settled(guide)
            assertNull(guide.splitFocus)
            assertEquals(PsdGuideStep.PRESET, guide.nextStep)

            guide.resetSplits()
            settled(guide)
            assertEquals(PsdGuideStep.BINDING, guide.nextStep)
        }
    }

    @Test fun focusingAnotherSplitKeepsBothCandidatesAndTheirIndependentSettings() {
        val art = PsdReader.read(Files.readAllBytes(Characters.eyesPsd(temp)))
        val first = art.layers.single { it.name == "eyes" }
        val second = object : SourceLayer by first {
            override val id = LayerId("lyid:999")
            override val name = "highlights"
            override val bounds = first.bounds.copy(top = first.bounds.top + 35)
        }
        val path = Files.write(temp.resolve("two-splits.psd"),
            PsdWriter.write(art.widthPx, art.heightPx, art.layers + second, art.groups))
        withGuide(path) { guide, viewModel ->
            if (!guide.splitScanned) { guide.scan(); settled(guide) }
            val ordered = guide.splitItems
            assertEquals(setOf("eyes", "highlights"), ordered.map { it.offer.layerName }.toSet())
            ordered.first().isSelected = false
            guide.showSplitPreview(ordered.last().offer.layerId)
            assertEquals(ordered.last().offer.layerId, guide.splitFocus)
            assertEquals(ordered, guide.splitItems)
            assertEquals(listOf(false, true), guide.splitItems.map { it.isSelected })
            if (System.getenv("PSD2LIVE_TOOLS") == "1") {
                renderPng(output("psd-guide").resolve("split-rows.png"), 1120, 630, frames = 0) {
                    PsdGuidePage(viewModel)
                }
            }
        }
    }

    @Test fun advancingFromSplitAppliesDraftAndPreservesSelectionsOnReturn() {
        val source = PsdReader.read(Files.readAllBytes(Characters.eyesPsd(temp)))
        val path = eyeWhitePsd(source, setOf("eyes"))
        withGuide(path) { guide, _ ->
            if (!guide.splitScanned) { guide.scan(); settled(guide) }
            val item = guide.splitItems.single()
            assertTrue(item.isSelected)

            // Custom mode keeps initial left/right sides and blocks invalid names.
            item.mode = SplitNamesMode.CUSTOM
            val (_, customSides) = item.generatedNamesAndSides()
            assertEquals(setOf(Side.LEFT, Side.RIGHT), customSides.toSet())
            item.customNames = listOf("eye_a", "eye_a")
            assertFalse(guide.splitDraftValid)
            guide.advanceFromSplit()
            assertEquals(PsdGuideStep.SPLIT, guide.step)

            item.mode = SplitNamesMode.LR
            assertTrue(guide.splitDraftValid)
            guide.advanceFromSplit()
            settled(guide)
            assertEquals(PsdGuideStep.PRESET, guide.step)
            assertEquals(1, guide.splitItems.size)
            assertTrue(guide.splitItems.single().isSelected)

            // Returning to step 1 preserves selections and lets the user uncheck cleanly.
            guide.go(PsdGuideStep.SPLIT)
            assertEquals(PsdGuideStep.SPLIT, guide.step)
            guide.splitItems.single().isSelected = false
            guide.advanceFromSplit()
            settled(guide)
            assertEquals(PsdGuideStep.BINDING, guide.step)
            assertFalse(guide.splitItems.single().isSelected)
        }
    }

    @Test fun faceStepStartsWithDefaultAtSlotOneAndKeepsAnchorSlotOnNearbyShuffle() {
        val path = Characters.eyesPsd(temp)
        withGuide(path) { guide, _ ->
            if (!guide.splitScanned) { guide.scan(); settled(guide) }
            guide.advanceFromSplit()
            settled(guide)
            if (guide.step == PsdGuideStep.BINDING) {
                guide.go(PsdGuideStep.PRESET)
                settled(guide)
            }
            assertEquals(PsdGuideStep.PRESET, guide.step)
            guide.go(PsdGuideStep.FACE)
            assertEquals(PsdGuideStep.FACE, guide.step)

            val draft = assertNotNull(guide.guide)
            val preset = assertNotNull(guide.preset)
            val turn = assertNotNull(draft.turn(preset))
            val expectedDefaults = draft.turnValues(turn)

            assertEquals(9, guide.batch.size)
            assertEquals(expectedDefaults, guide.batch.first().values)
            assertEquals(guide.batch.first(), guide.selectedStyle)
            assertEquals(0, guide.faceFlipGeneration)
            assertNull(guide.faceFlipKeepIndex)

            // Select slot #3 (index 2) and shuffle nearby: slot 2 must remain in place.
            val anchor = guide.batch[2]
            guide.selectedStyle = anchor
            guide.newStyles(nearby = true)
            assertEquals(1, guide.faceFlipGeneration)
            assertEquals(2, guide.faceFlipKeepIndex)
            assertEquals(anchor.values, guide.batch[2].values)
            assertEquals(guide.batch[2], guide.selectedStyle)
            assertTrue((0 until 9).filter { it != 2 }.any { guide.batch[it].values != anchor.values })

            // Editing face modifier parameters updates the selected slot in place without flipping.
            guide.selectedStyle = guide.batch[2]
            val flipBeforeEdit = guide.faceFlipGeneration
            guide.updateFaceStyleValue("turn", 72f)
            assertEquals(flipBeforeEdit, guide.faceFlipGeneration)
            assertEquals(72f, guide.batch[2].values["turn"])
            assertEquals(guide.batch[2], guide.selectedStyle)

            guide.updateFaceStylePreset(1)
            assertEquals(flipBeforeEdit, guide.faceFlipGeneration)
            assertEquals(1f, guide.batch[2].values["style"])
            assertEquals(guide.batch[2], guide.selectedStyle)

            // Full shuffle flips all slots.
            guide.newStyles(nearby = false)
            assertEquals(2, guide.faceFlipGeneration)
            assertNull(guide.faceFlipKeepIndex)
            assertNull(guide.selectedStyle)
        }
    }

    private fun eyeWhitePsd(source: SourceArt, names: Set<String>): Path {
        val layers = source.layers.mapNotNull { layer ->
            when (layer.name) {
                "face" -> layer
                in names -> object : SourceLayer by layer { override val name = "eyewhite" }
                else -> null
            }
        }
        return Files.write(temp.resolve("eye-white.psd"), PsdWriter.write(source.widthPx, source.heightPx, layers, source.groups))
    }

    private fun withGuide(path: Path, test: suspend (PsdGuideState, PSD2LiveViewModel) -> Unit) = runBlocking {
        withContext(Dispatchers.Main) {
            PSD2LiveViewModel().use { vm ->
                vm.setStateForTest(
                    vm.state.value.copy(
                        atlasSize = 512,
                        mutedPrompts = vm.state.value.mutedPrompts - AppPrompt.PSD_IMPORT_GUIDE - AppPrompt.MESH_SPLITS_ON_IMPORT,
                    ),
                )
                DesktopWorkspace(vm, temp.resolve("store")).use { workspace ->
                    vm.attachWorkspace(workspace)
                    vm.openPsdGuide(path)
                    settled(vm.psdGuide)
                    test(vm.psdGuide, vm)
                }
            }
        }
    }

    private suspend fun settled(guide: PsdGuideState) {
        withTimeout(30_000) { guide.awaitIdle() }
        assertNull(guide.error)
    }
}
