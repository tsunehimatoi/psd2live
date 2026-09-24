package io.github.psd2live.core

import io.github.psd2live.agent.WorkspaceSourceLayer
import io.github.psd2live.agent.WorkspaceSourceArt
import io.github.psd2live.agent.AgentWorkspaceDocument
import io.github.psd2live.agent.AgentWorkspaceStore
import io.github.psd2live.history.WorkspaceHistoryTree
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import org.umamo.format.art.ChannelMask
import org.umamo.format.art.LayerBlend
import org.umamo.format.art.LayerBounds
import org.umamo.format.art.LayerId
import org.umamo.format.art.LayerRaster
import org.umamo.format.art.SourceLayerKind
import org.umamo.runtime.model.DrawableMesh
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MeshComponentSplitTest {
    @TempDir lateinit var temp: Path

    private fun source(count: Int): WorkspaceSourceLayer {
        val width = count * 10
        val height = 10
        val rgba = ByteArray(width * height * 4)
        for (part in 0 until count) for (y in 2..6) for (x in part * 10 + 2..part * 10 + 6) {
            val offset = (y * width + x) * 4
            rgba[offset] = (40 + part).toByte()
            rgba[offset + 1] = 90
            rgba[offset + 2] = 120
            rgba[offset + 3] = -1
        }
        return WorkspaceSourceLayer(
            LayerId("test"), "Pieces", "", SourceLayerKind.Raster, true, 1,
            LayerBounds(5, 7, width, height), 1f, false, LayerBlend.Normal, ChannelMask.ALL,
            LayerRaster(width, height, rgba), null, null, true,
        )
    }

    private fun mesh(count: Int): DrawableMesh {
        val width = count * 10f
        val positions = ArrayList<Float>()
        val uvs = ArrayList<Float>()
        val indices = ArrayList<Int>()
        repeat(count) { part ->
            val x = part * 10f
            listOf(x + 2f to 2f, x + 6f to 2f, x + 4f to 6f).forEach { (vx, vy) ->
                positions += vx; positions += vy
                uvs += vx / width; uvs += vy / 10f
            }
            indices += part * 3; indices += part * 3 + 1; indices += part * 3 + 2
        }
        return DrawableMesh(positions.toFloatArray(), uvs.toFloatArray(), indices.toIntArray())
    }

    @Test fun separatesTwoOrMoreMeshIslandsWithoutLosingPixels() {
        for (count in 2..4) {
            val source = source(count)
            val plan = assertNotNull(MeshComponentSplit.detect(
                mesh(count), source, AtlasPlacement(0, 0, 0, count * 10, 10), count * 10, 10,
            ))
            assertEquals(count, plan.components.size)
            val pieces = plan.pieces((1..count).map { "Piece-$it" })
            assertEquals(count, pieces.size)
            assertTrue(pieces.all { it.raster.width == 5 && it.raster.height == 5 })
            val reconstructed = ByteArray(source.raster.rgba.size)
            for (piece in pieces) {
                for (y in 0 until piece.raster.height) for (x in 0 until piece.raster.width) {
                    val sourceX = piece.bounds.left - source.bounds.left + x
                    val sourceY = piece.bounds.top - source.bounds.top + y
                    val to = (sourceY * source.raster.width + sourceX) * 4
                    val from = (y * piece.raster.width + x) * 4
                    if (piece.raster.rgba[from + 3].toInt() != 0) {
                        piece.raster.rgba.copyInto(reconstructed, to, from, from + 4)
                    }
                }
            }
            assertTrue(source.raster.rgba.contentEquals(reconstructed))
        }
    }

    @Test fun connectedTrianglesDoNotOfferSplit() {
        val source = source(2)
        val joined = mesh(2).let {
            DrawableMesh(it.positions, it.uvs, it.indices + intArrayOf(0, 3, 4))
        }
        assertNull(MeshComponentSplit.detect(joined, source, AtlasPlacement(0, 0, 0, 20, 10), 20, 10))
    }

    @Test fun overlappingBoundsUseTriangleCoverageInsteadOfVertexDistance() {
        val size = 22
        val rgba = ByteArray(size * size * 4)
        for (y in 0 until size) for (x in 0 until size) {
            val first = x >= 2 && y >= 2 && x + y <= 13
            val second = x >= 10 && y >= 10 && x + y <= 29
            if (first || second) rgba[(y * size + x) * 4 + 3] = -1
        }
        val layer = source(2).copy(bounds = LayerBounds(0, 0, size, size),
            raster = LayerRaster(size, size, rgba))
        val coordinates = floatArrayOf(2f, 2f, 12f, 2f, 2f, 12f,
            10f, 10f, 20f, 10f, 10f, 20f)
        val uv = FloatArray(coordinates.size) { coordinates[it] / size }
        val mesh = DrawableMesh(coordinates, uv, intArrayOf(0, 1, 2, 3, 4, 5))
        val plan = assertNotNull(MeshComponentSplit.detect(mesh, layer,
            AtlasPlacement(0, 0, 0, size, size), size, size))
        val pieces = plan.pieces(listOf("first", "second"))
        for (y in 0 until size) for (x in 0 until size) {
            val first = x >= 2 && y >= 2 && x + y <= 13
            val second = x >= 10 && y >= 10 && x + y <= 29
            if (!first && !second) continue
            val actual = pieces.indices.single { index ->
                val part = pieces[index]
                val px = x - part.bounds.left
                val py = y - part.bounds.top
                px in 0 until part.raster.width && py in 0 until part.raster.height &&
                    part.raster.rgba[(py * part.raster.width + px) * 4 + 3].toInt() != 0
            }
            assertEquals(if (first) 0 else 1, actual, "wrong triangle owns ($x, $y)")
        }
    }

    @Test fun emptyMeshIslandDoesNotHideOtherSplittableIslands() {
        val original = source(3)
        val rgba = original.raster.rgba.copyOf()
        for (y in 0 until 10) for (x in 20 until 30) rgba[(y * 30 + x) * 4 + 3] = 0
        val layer = original.copy(raster = LayerRaster(30, 10, rgba))
        val plan = assertNotNull(MeshComponentSplit.detect(
            mesh(3), layer, AtlasPlacement(0, 0, 0, 30, 10), 30, 10,
        ))
        assertEquals(2, plan.components.size)
    }

    @Test fun derivedImportRemainsOneLayerUntilConfirmed() {
        val layer = source(2)
        val source = WorkspaceSourceArt(30, 20, listOf(layer), emptyList())
        val pipeline = PSD2LivePipeline()
        val preview = pipeline.buildPreview(source, PipelineConfig(meshOnly = true))
        assertEquals(1, preview.analysis.layers.size)
        val drawable = preview.rig.puppet.drawables.single()
        val placement = preview.atlas.placementByLayerId.getValue(layer.id.raw)
        val page = preview.atlas.pages[placement.page].image
        val plan = assertNotNull(drawable.mesh?.let {
            MeshComponentSplit.detect(it, layer, placement, page.width, page.height)
        })
        assertEquals(2, plan.components.size)
        val pieces = plan.pieces(listOf("Pieces-r", "Pieces-l"))
        val splitSource = WorkspaceSourceArt(30, 20, listOf(layer) + pieces, emptyList())
        val splitPreview = pipeline.buildPreview(splitSource,
            PipelineConfig(meshOnly = true, deletedLayerIds = setOf(layer.id.raw)))
        assertEquals(pieces.map { it.id.raw }.toSet(),
            splitPreview.analysis.layers.map { it.source.id.raw }.toSet())
        assertEquals(2, splitPreview.rig.puppet.drawables.size)
    }

    @Test fun psdLayerAlsoWaitsForExplicitSplit() {
        val layer = source(2).copy(name = "Eyebrow", derived = false)
        val preview = PSD2LivePipeline().buildPreview(
            WorkspaceSourceArt(30, 20, listOf(layer), emptyList()), PipelineConfig(meshOnly = true))
        assertEquals(listOf(layer.id.raw), preview.analysis.layers.map { it.source.id.raw })
        val drawable = preview.rig.puppet.drawables.single()
        val placement = preview.atlas.placementByLayerId.getValue(layer.id.raw)
        val page = preview.atlas.pages[placement.page].image
        assertEquals(2, assertNotNull(drawable.mesh?.let {
            MeshComponentSplit.detect(it, layer, placement, page.width, page.height)
        }).components.size)
    }

    @Test fun legacyEditedAutoSplitKeepsItsLayerIds() {
        val layer = source(2).copy(name = "Eyebrow", derived = false)
        val analysis = CharacterAnalyzer.analyze(
            WorkspaceSourceArt(30, 20, listOf(layer), emptyList()),
            PipelineConfig(layerOverrides = mapOf("test:r" to LayerClassificationOverride(SemanticTag.EYEBROW, Side.RIGHT))),
        )
        assertEquals(setOf("test:r", "test:l"), analysis.layers.map { it.source.id.raw }.toSet())
        val withDeletedVirtualPiece = CharacterAnalyzer.analyze(
            WorkspaceSourceArt(30, 20, listOf(layer), emptyList()),
            PipelineConfig(deletedLayerIds = setOf("test:r")),
        )
        assertEquals(listOf("test:l"), withDeletedVirtualPiece.layers.map { it.source.id.raw })
    }

    @Test fun splittingKeepsTheExistingParentWarp() {
        val layer = source(2).copy(name = "Front Hair")
        val originalSource = WorkspaceSourceArt(30, 20, listOf(layer), emptyList())
        val pipeline = PSD2LivePipeline()
        val preview = pipeline.buildPreview(originalSource)
        val oldDrawable = preview.rig.puppet.drawables.single { preview.rig.layerIdByDrawableId[it.id.raw] == layer.id.raw }
        val parentId = assertNotNull(oldDrawable.parentDeformerId)
        val oldParent = assertNotNull(preview.rig.puppet.deformers.firstOrNull { it.id == parentId })
        val placement = preview.atlas.placementByLayerId.getValue(layer.id.raw)
        val page = preview.atlas.pages[placement.page].image
        val plan = assertNotNull(oldDrawable.mesh?.let {
            MeshComponentSplit.detect(it, layer, placement, page.width, page.height)
        })
        val pieces = plan.pieces(listOf("Front Hair-r", "Front Hair-l"))
        val config = preview.config.copy(
            deletedLayerIds = setOf(layer.id.raw),
            rigEdits = preview.config.rigEdits.copy(splitBaselineLayerIds = setOf(layer.id.raw)),
            parentOverrides = pieces.associate { it.id.raw to parentId.raw },
            layerOverrides = pieces.mapIndexed { index, piece ->
                piece.id.raw to LayerClassificationOverride(SemanticTag.FRONT_HAIR,
                    if (index == 0) Side.RIGHT else Side.LEFT)
            }.toMap(),
        )
        val split = pipeline.buildPreviewAfterLayerSplit(preview,
            WorkspaceSourceArt(30, 20, listOf(layer) + pieces, emptyList()), config)
        val splitParent = assertNotNull(split.rig.puppet.deformers.firstOrNull { it.id == parentId })
        assertSame(oldParent, splitParent)
        assertEquals(parentId, split.rig.puppet.drawables.first {
            split.rig.layerIdByDrawableId[it.id.raw] == pieces[0].id.raw
        }.parentDeformerId)
        // A normal project reopen rebuilds from source/config rather than retaining the preview objects.
        val savedSource = WorkspaceSourceArt(30, 20, listOf(layer) + pieces, emptyList())
        val store = AgentWorkspaceStore(temp)
        val document = AgentWorkspaceDocument(savedSource, emptyMap(), config.deletedLayerIds,
            config.layerOverrides, config.parentOverrides, config.rigEdits)
        store.persistHistory("mesh-split", WorkspaceHistoryTree(document, "revision", "snapshot").state())
        val restored = assertNotNull(store.loadHistory("mesh-split")).head().snapshot
        assertEquals(setOf(layer.id.raw), restored.rigEdits.splitBaselineLayerIds)
        val reopened = pipeline.buildPreview(restored.source, config.copy(rigEdits = restored.rigEdits))
        val reopenedParent = assertNotNull(reopened.rig.puppet.deformers.firstOrNull { it.id == parentId })
        val oldWarp = oldParent as org.umamo.runtime.model.Deformer.Warp
        val loadedWarp = reopenedParent as org.umamo.runtime.model.Deformer.Warp
        assertEquals(oldWarp.geometryGrid?.cells?.size, loadedWarp.geometryGrid?.cells?.size)
        oldWarp.geometryGrid!!.cells.zip(loadedWarp.geometryGrid!!.cells).forEach { (before, after) ->
            assertTrue(before.form.controlPoints.contentEquals(after.form.controlPoints))
        }
    }

    @Test fun batchSplittingMultipleLayersInSinglePass() {
        val hairLayer = source(2).copy(id = LayerId("hair"), name = "Front Hair")
        val shoesLayer = source(2).copy(id = LayerId("shoes"), name = "Shoes")
        val originalSource = WorkspaceSourceArt(30, 20, listOf(hairLayer, shoesLayer), emptyList())
        val pipeline = PSD2LivePipeline()
        val preview = pipeline.buildPreview(originalSource)

        val hairPlacement = preview.atlas.placementByLayerId.getValue("hair")
        val hairPage = preview.atlas.pages[hairPlacement.page].image
        val hairDrawable = preview.rig.puppet.drawables.single { preview.rig.layerIdByDrawableId[it.id.raw] == "hair" }
        val hairPlan = assertNotNull(hairDrawable.mesh?.let {
            MeshComponentSplit.detect(it, hairLayer, hairPlacement, hairPage.width, hairPage.height)
        })
        val hairPieces = hairPlan.pieces(listOf("Front Hair-r", "Front Hair-l"))

        val shoesPlacement = preview.atlas.placementByLayerId.getValue("shoes")
        val shoesPage = preview.atlas.pages[shoesPlacement.page].image
        val shoesDrawable = preview.rig.puppet.drawables.single { preview.rig.layerIdByDrawableId[it.id.raw] == "shoes" }
        val shoesPlan = assertNotNull(shoesDrawable.mesh?.let {
            MeshComponentSplit.detect(it, shoesLayer, shoesPlacement, shoesPage.width, shoesPage.height)
        })
        val shoesPieces = shoesPlan.pieces(listOf("Shoes-r", "Shoes-l"))

        val allPieces = hairPieces + shoesPieces
        val config = preview.config.copy(
            deletedLayerIds = setOf("hair", "shoes"),
            rigEdits = preview.config.rigEdits.copy(splitBaselineLayerIds = setOf("hair", "shoes")),
            parentOverrides = hairPieces.associate { it.id.raw to hairDrawable.parentDeformerId?.raw } +
                shoesPieces.associate { it.id.raw to shoesDrawable.parentDeformerId?.raw },
            layerOverrides = (hairPieces.mapIndexed { index, piece ->
                piece.id.raw to LayerClassificationOverride(SemanticTag.FRONT_HAIR, if (index == 0) Side.RIGHT else Side.LEFT)
            } + shoesPieces.mapIndexed { index, piece ->
                piece.id.raw to LayerClassificationOverride(SemanticTag.FOOTWEAR, if (index == 0) Side.RIGHT else Side.LEFT)
            }).toMap(),
        )

        val batchSource = WorkspaceSourceArt(30, 20, listOf(hairLayer, shoesLayer) + allPieces, emptyList())
        val split = pipeline.buildPreviewAfterLayerSplit(preview, batchSource, config)

        assertEquals(4, allPieces.size)
        allPieces.forEach { piece ->
            assertTrue(split.rig.layerIdByDrawableId.containsValue(piece.id.raw), "Drawable should exist for ${piece.id.raw}")
        }
        assertTrue(!split.rig.layerIdByDrawableId.containsValue("hair"), "Original hair layer should be replaced")
        assertTrue(!split.rig.layerIdByDrawableId.containsValue("shoes"), "Original shoes layer should be replaced")
    }
}
