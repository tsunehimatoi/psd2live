package io.github.psd2live.project

import io.github.psd2live.core.RigEditOverlay
import io.github.psd2live.history.WorkspaceHistoryTree
import kotlinx.serialization.json.JsonObject
import org.umamo.format.art.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.random.Random

/**
 * A generated project for archive tests and the save benchmark: [layers] raster layers of [size]² pixels, half
 * of them noise (incompressible, as painted art in PNG mostly is) and half flat colour, and a linear history of
 * [revisions] revisions, each hiding one layer and every fourth one repainting one. The live working store is
 * persisted as a running workspace keeps it, and the source PSD is a file, as an imported project has.
 */
internal class SyntheticProject(val directory: Path, layers: Int, size: Int, revisions: Int, seed: Int = 7) {
	val store = WorkspaceStore(directory.resolve("live"))
	val source: Path = directory.resolve("source.psd")
	val tree: WorkspaceHistoryTree<WorkspaceDocument>

	init {
		val random = Random(seed)
		fun raster(index: Int, variant: Int): LayerRaster {
			val bytes = ByteArray(size * size * 4)
			if ((index + variant) % 2 == 0) random.nextBytes(bytes)
			else { val colour = random.nextInt(); for (i in bytes.indices step 4) { bytes[i] = colour.toByte(); bytes[i + 1] = (colour shr 8).toByte(); bytes[i + 2] = (colour shr 16).toByte() } }
			for (i in 3 until bytes.size step 4) bytes[i] = 255.toByte()
			return LayerRaster(size, size, bytes)
		}
		fun layer(index: Int, raster: LayerRaster) = WorkspaceSourceLayer(LayerId("layer-$index"), "Layer $index", "", SourceLayerKind.Raster, true,
			index, LayerBounds(0, 0, size, size), 1f, false, LayerBlend.Normal, ChannelMask.ALL, raster, null, null, false)
		val art = WorkspaceSourceArt(size, size, List(layers) { layer(it, raster(it, 0)) }, emptyList())
		val root = WorkspaceDocument(art, emptyMap(), emptySet(), emptyMap(), emptyMap(), RigEditOverlay.Empty)
		val rootRevision = WorkspaceRevisions.of(root)
		tree = WorkspaceHistoryTree(root, rootRevision, rootRevision)
		var document = root
		for (step in 1 until revisions) {
			val repainted = if (step % 4 == 0) {
				val index = step % layers
				document.source.layers.mapIndexed { i, it -> if (i == index) layer(i, raster(i, step)) else it }
			} else document.source.layers
			document = document.copy(source = WorkspaceSourceArt(size, size, repainted, emptyList()),
				layerVisibility = document.layerVisibility + ("layer-${step % layers}" to (step % 2 == 0)))
			val revision = WorkspaceRevisions.of(document)
			tree.commit(tree.head().node.id, document, revision, revision, "Edit $step", "user")
		}
		store.persistHistory(PROJECT, tree.state())
		Files.write(source, org.umamo.format.psd.PsdWriter.write(art))
	}

	fun capture() = ProjectSaveCapture(PROJECT, tree.state(), JsonObject(emptyMap()), source, store)

	companion object { const val PROJECT = "synthetic" }
}
