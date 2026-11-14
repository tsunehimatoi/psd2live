package io.github.psd2live.project

import io.github.psd2live.document.AtlasArrangement
import io.github.psd2live.document.ArrangedTile
import io.github.psd2live.document.TextureFootprint
import io.github.psd2live.document.TextureOverride
import io.github.psd2live.document.TexturePin
import io.github.psd2live.history.WorkspaceHistoryTree
import io.github.psd2live.scenario.Characters
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.BitSet
import kotlin.test.*

class DocumentContractsTest {
    @TempDir lateinit var directory: Path

    @Test fun extractedValuesKeepStoredJsonAndRevisionIdentity() {
        val arrangement = AtlasArrangement(2048, mapOf("face" to ArrangedTile(0, 3, 4,
            TextureFootprint(2, 2, 2, BitSet.valueOf(byteArrayOf(5))), 90f)))
        val texture = TextureOverride(.5f, true, TexturePin(0, 7, 8, -90f))
        assertEquals("{\"density\":0.5,\"lock\":true,\"pin\":{\"page\":0,\"x\":7,\"y\":8,\"rotation\":-90.0}}",
            TextureOverrideCodec.encode(texture).toString())
        assertEquals(texture, TextureOverrideCodec.decode(TextureOverrideCodec.encode(texture)))
        assertEquals(arrangement, AtlasArrangementCodec.decode(AtlasArrangementCodec.with(JsonObject(emptyMap()), arrangement)))

        val original = Characters.head(physics = false)
        val layers = original.source.layers.map { layer ->
            (WorkspaceSourceLayer.copyOf(layer, layer.order) as WorkspaceSourceLayer).copy(
                layerTransform = LayerTransform(1.25f, 0f, 0f, .75f, 3f, -2f), sourceAssetId = "asset", derived = true)
        }
        val document = original.copy(source = WorkspaceSourceArt(64, 64, layers, original.source.groups),
            textureOverrides = mapOf("face" to texture), settings = AtlasArrangementCodec.with(original.settings, arrangement))
        val revision = WorkspaceRevisions.of(document)
        val tree = WorkspaceHistoryTree(document, revision, revision)
        val store = WorkspaceStore(directory)
        store.persistHistory("contracts", tree.state())
        val restored = WorkspaceStore(directory).loadHistory("contracts")!!.head().snapshot
        assertEquals(revision, WorkspaceRevisions.of(restored))
        assertEquals(document.textureOverrides, restored.textureOverrides)
        assertEquals(arrangement, AtlasArrangementCodec.decode(restored.settings))
        for ((before, after) in document.source.layers.zip(restored.source.layers)) {
            assertEquals(before.id, after.id)
            assertEquals(before.transform, after.transform)
            assertEquals(before.storedCanvasRect, after.storedCanvasRect)
            assertContentEquals(before.raster.rgba, after.raster.rgba)
        }
    }
}
