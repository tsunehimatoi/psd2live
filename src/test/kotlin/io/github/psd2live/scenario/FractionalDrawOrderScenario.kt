package io.github.psd2live.scenario

import io.github.psd2live.core.PipelineConfig
import org.junit.jupiter.api.io.TempDir
import org.umamo.format.cmo3.Cmo3
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.format.cmo3.model.gen.CArtMeshForm
import org.umamo.interop.cmo3.Cmo3GraphIndex
import org.umamo.interop.cmo3.Cmo3Import
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test

/**
 * A fractional draw order (the ruler's or the inspector's) reaches the cmo3 as whole numbers in the same stacking,
 * on a generated lip outline that carries deform paths as well as on a plain mesh (issue #39).
 */
class FractionalDrawOrderScenario {
	@TempDir lateinit var temp: Path

	@Test fun fractionalOrdersExportAsWholeNumbersInTheSameStacking() = Studio.run(temp, checks = emptySet()) {
		importPsd(Characters.mouthPsd(temp), PipelineConfig(atlasSize = 256, generatePhysics = false, exportMoc3 = false, mouthOutlineEnabled = true))
		val lip = puppet.drawables.first { "_lip_" in it.id.raw && puppet.deformPaths.any { path -> path.drawableId == it.id } }.id.raw
		val face = puppet.drawables.first { it.id.raw != lip && puppet.drawables.none { other -> other.maskedBy.contains(it.id) } && "_lip_" !in it.id.raw }.id.raw
		edit("slip the lip outline between orders", "layer_draw_order", req("target" to "mesh:$lip", "order" to 2.5))
		edit("give another mesh a fraction", "rig_edit_structure", req("edits" to listOf(
			mapOf("action" to "static", "kind" to "mesh", "id" to face, "draw_order" to 0.5))))

		val cmo3 = export("cmo3").single { it.toString().endsWith(".cmo3") }
		val sources = Cmo3GraphIndex(Cmo3.read(Files.readAllBytes(cmo3)).root as CModelSource).drawableSources
		val written = sources.associate { source ->
			Cmo3Import.idStrOf(source.id)!! to Cmo3Import.elementsOf(source.keyforms).filterIsInstance<CArtMeshForm>().map { it.drawOrder }
		}
		expect("keyforms", written.values.all { it.isNotEmpty() }) { "meshes written without keyforms: ${written.filterValues { it.isEmpty() }.keys}" }
		val stacking = puppet.drawables.sortedBy { it.drawOrder }.map { it.id.raw }
		val exported = puppet.drawables.map { it.id.raw }.sortedBy { written.getValue(it).first() }
		expect("stacking", stacking == exported) { "the cmo3 stacks $exported, the editor $stacking (${written.mapValues { it.value.distinct() }})" }
	}
}
