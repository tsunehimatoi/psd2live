package io.github.psd2live.ui

import io.github.psd2live.application.PsdGuideBindings
import io.github.psd2live.core.Side
import io.github.psd2live.scenario.Characters
import io.github.psd2live.tools.output
import io.github.psd2live.tools.renderPng
import io.github.psd2live.ui.views.PsdGuidePage
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
                vm.setStateForTest(vm.state.value.copy(atlasSize = 512))
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
