package io.github.psd2live.scenario

import io.github.psd2live.core.RigCheckpoint
import io.github.psd2live.core.RigEditOverlay
import org.junit.jupiter.api.io.TempDir
import org.umamo.format.cmo3.Cmo3
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.interop.cmo3.Cmo3Import
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test

/**
 * A model finished elsewhere comes in as a cmo3: it is imported, edited past a checkpoint interval, one of its layers
 * split, the project reopened, and exported back to cmo3. The import is never regenerated, edits replay on it, and the
 * cmo3 written reads back to what the editor shows.
 */
class Cmo3ImportScenario {
	@TempDir lateinit var temp: Path

	@Test fun importEditSplitReopenAndExportBack() {
		val source = temp.resolve("made-elsewhere")
		Files.createDirectories(source)
		val cmo3 = Studio(source, emptySet()).use { studio -> studio.open(Characters.figure()); studio.export("cmo3").single { it.toString().endsWith(".cmo3") } }
		Studio.run(temp, checks = setOf(Studio.Check.REPLAY)) {
			importCmo3(cmo3)
			val layer = now.document.rigEdits.importedLayerIds.getValue("ArtMeshBackHair")
			repeat(RigEditOverlay.CHECKPOINT_INTERVAL + 1) { edit("axis $it", "parameter_create", req("parameter_id" to "P$it", "name" to "P$it")) }
			val journal = now.document.rigEdits.authoringJournal
			expect("imported", journal.filter(RigCheckpoint::isRecord).none { "generated" in it }) { "an imported model was regenerated" }
			edit("split an imported layer", "source_split_polygon", req("layer_id" to layer, "names" to listOf("Left", "Right"),
				"polygon" to listOf(listOf(0, 0), listOf(210, 0), listOf(210, 420), listOf(0, 420))))
			reopen()
			val written = export("cmo3").single { it.toString().endsWith(".cmo3") }
			val readBack = step("read the cmo3 back") { Cmo3Import.fromModelSource(Cmo3.read(Files.readAllBytes(written)).root as CModelSource) }
			exportMatchesEditor(this, readBack, "cmo3")
		}
	}
}
