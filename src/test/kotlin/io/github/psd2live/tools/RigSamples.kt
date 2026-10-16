package io.github.psd2live.tools

import io.github.psd2live.core.AtlasBudget
import io.github.psd2live.core.Cmo3ImportMode
import io.github.psd2live.core.Cmo3ModelImport
import io.github.psd2live.core.PSD2LivePipeline
import io.github.psd2live.core.PipelineConfig
import io.github.psd2live.core.RigIrCompiler
import io.github.psd2live.format.compile.PoseGeometry
import io.github.psd2live.format.compile.render.IrColors
import io.github.psd2live.format.model.*
import io.github.psd2live.targets.cubism.PuppetIr
import org.umamo.format.moc3.Moc3
import org.umamo.interop.moc3.import.Moc3Import
import java.io.ByteArrayInputStream
import java.io.File
import java.util.IdentityHashMap
import javax.imageio.ImageIO
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

// Downloaded Live2D models (model3.json or cmo3) as IR rigs with decoded texture pages, for the dataset tools.

/**
 * Each mesh's position in the order the render tree draws (back to front). With a [pose] and its [parts], draw
 * orders are the posed ones, as the rasterizer sorts them; without, the static ones.
 */
internal fun renderOrder(ir: RigIR, pose: PoseGeometry? = null, parts: Map<String, IrColors.PartComposite> = emptyMap()): Map<String, Int> {
	val order = HashMap<String, Int>()
	val meshes = ir.meshes.associateBy { it.id }
	fun visit(node: RenderNode) {
		when (node) {
			is RenderMesh -> order[node.id] = order.size
			is RenderGroup -> node.children.sortedBy { child ->
				when (child) {
					is RenderMesh -> pose?.drawOrder?.get(child.id) ?: meshes[child.id]?.drawOrder ?: 0f
					is RenderGroup -> child.part?.let { parts[it]?.drawOrder } ?: child.drawOrder.toFloat()
				}
			}.forEach(::visit)
		}
	}
	visit(ir.renderRoot)
	return order
}

internal class LoadedRig(val ir: RigIR, val pixels: IdentityHashMap<ByteArray, IntArray>)

internal fun loadRig(file: File): LoadedRig = if (file.name.endsWith(".cmo3", true)) loadCmo3(file) else loadModel3(file)

/** A cmo3 through the editor's own import, packed into a budget large enough that no layer is scaled down. */
private fun loadCmo3(file: File): LoadedRig {
	val base = PipelineConfig(atlasBudget = AtlasBudget(pageSize = 8192, maxPages = 32), exportJson = false)
	val (source, config) = Cmo3ModelImport.prepare(file.readBytes(), Cmo3ImportMode.NEW, null, base)
	// A cmo3 keeps its layers at full size in its own atlas, so the pages it brings are already the authored art.
	val ir = RigIrCompiler.compile(PSD2LivePipeline().buildPreview(source, config))
	val pixels = IdentityHashMap<ByteArray, IntArray>()
	for (page in ir.textures.pages) {
		val bytes = page.png.shared()
		val image = requireNotNull(ImageIO.read(ByteArrayInputStream(bytes))) { "atlas page is not readable" }
		pixels[bytes] = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
	}
	return LoadedRig(ir, pixels)
}

private fun loadModel3(model3: File): LoadedRig {
	val dir = model3.parentFile
	val manifest = Moc3.readModel3(model3.readText())
	val doc = Moc3.read(File(dir, manifest.fileReferences.moc).readBytes())
	val cdi = manifest.fileReferences.displayInfo?.let { File(dir, it) }?.takeIf { it.isFile }?.let { Moc3.readCdi3(it.readText()) }
	// Rendering needs no compacted channels, and compaction can take hours on some models.
	val puppet = Moc3Import.fromMocDocument(doc, cdi, compactChannels = false)
	val pixels = IdentityHashMap<ByteArray, IntArray>()
	val pages = manifest.fileReferences.textures.map { name ->
		val bytes = File(dir, name).readBytes()
		val image = requireNotNull(ImageIO.read(ByteArrayInputStream(bytes))) { "texture $name is not readable" }
		pixels[bytes] = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
		TexturePage(image.width, image.height, Bytes.wrap(bytes))
	}
	return LoadedRig(PuppetIr.toIr(puppet, pages), pixels)
}

/** Mean vertex displacement of each mesh between two poses, in canvas pixels. */
internal fun moved(a: PoseGeometry, b: PoseGeometry): Map<String, Float> = a.positions.mapValues { (id, p) ->
	val q = b.positions[id] ?: return@mapValues 0f
	var sum = 0.0
	for (i in 0 until min(p.size, q.size) / 2) sum += hypot((p[i * 2] - q[i * 2]).toDouble(), (p[i * 2 + 1] - q[i * 2 + 1]).toDouble())
	(sum / max(1, p.size / 2)).toFloat()
}
