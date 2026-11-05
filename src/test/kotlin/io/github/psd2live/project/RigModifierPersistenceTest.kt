package io.github.psd2live.project

import io.github.psd2live.core.*
import io.github.psd2live.application.WorkspacePreviewBuilder
import io.github.psd2live.format.model.Bytes
import io.github.psd2live.format.model.TileArt
import org.umamo.format.art.*
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.OrgChild
import io.github.psd2live.core.modifier.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class RigModifierPersistenceTest {
    @TempDir lateinit var temp: Path

    private fun stack() = RigModifierStack()
        .add("motion", 1, listOf(ModifierTarget("mesh", "mesh")), presetGroup = "recommended")
        .withOverrides("m1", listOf(buildJsonObject { put("op", "structure"); putJsonArray("edits") {} }))
        .remove("m1")
        .add("physics", 1, listOf(ModifierTarget("part", "body")), buildJsonObject { put("input", "ParamX") },
            outputAliases = mapOf("physics" to "existing-physics"))
        .update("m2") { it.copy(enabled = false) }
        .add("motion", 1, listOf(ModifierTarget("mesh", "mesh")))
        .update("m3") { it.copy(enabled = false, inputFingerprint = "a".repeat(64)) }
        .move("m3", 0)

    @Test fun modifierStateChangesRevisionAndSurvivesWorkingStoreAndProjectReopen() = runBlocking<Unit> {
        val project = SyntheticProject(temp.resolve("project"), 1, 8, 1)
        val before = project.tree.head().snapshot
        val document = before.copy(rigEdits = before.rigEdits.copy(modifierStack = stack().toJson()))
        val revision = WorkspaceRevisions.of(document)
        assertNotEquals(WorkspaceRevisions.of(before), revision)
        assertEquals(WorkspaceRevisions.reference(document), revision)
        project.tree.commit(project.tree.head().node.id, document, revision, revision, "Add modifiers", "user")
        project.store.persistHistory(SyntheticProject.PROJECT, project.tree.state())
        val loaded = WorkspaceStore(project.store.root).loadHistory(SyntheticProject.PROJECT)!!
        assertEquals(document.rigEdits, loaded.head().snapshot.rigEdits)
        assertEquals(revision, WorkspaceRevisions.of(loaded.head().snapshot))
        val file = temp.resolve("modifiers.psd2live")
        ProjectRepository(writeHeadCache = false).save(project.capture(), file)
        ProjectRepository().open(file).use { opened ->
            assertEquals(document.rigEdits, opened.history.head().snapshot.rigEdits)
            assertEquals(revision, WorkspaceRevisions.of(opened.history.head().snapshot))
            assertEquals(stack(), RigModifierStack.fromJson(opened.history.head().snapshot.rigEdits.modifierStack!!))
            assertEquals(WorkspaceRevisions.of(before), WorkspaceRevisions.of(opened.history.selections().first().snapshot))
        }
    }

    @Test fun independentRasterInAnAppliedCheckpointSurvivesColdProjectReopen() = runBlocking<Unit> {
        val project = SyntheticProject(temp.resolve("raster-project"), 1, 8, 1)
        val config = PipelineConfig(atlasSize = 64, rigEdits = RigEditOverlay(modifierStack = RigModifierStack().toJson()))
        val document = project.tree.head().snapshot.copy(settings = WorkspaceSettingsCodec.encode(config), rigEdits = config.rigEdits)
        val builder = WorkspacePreviewBuilder()
        val initial = builder.build(document)
        val layer = document.source.layers.single()
        val raster = layer.raster
        val assetId = "modifier-raster:lip"
        val asset = WorkspaceSourceLayer(LayerId(assetId), "Lip", "", SourceLayerKind.Raster, true, 1,
            layer.bounds, 1f, false, LayerBlend.Normal, ChannelMask.ALL, raster, null, null, true)
        val classified = CharacterAnalyzer.classify(asset, config)
        val combined = RigModifierRasterAssets.append(initial.atlas, listOf(classified), config)
        val (atlas, sources) = PuppetSourceAtlas.build(initial.analysis.copy(layers = initial.analysis.layers + classified), combined)
        val page = combined.placementByLayerId.getValue(assetId).page
        val tile = PuppetSourceAtlas.tileIdFor(assetId)
        val author = initial.authored
        val original = author.rig.puppet.drawables.single()
        val mesh = original.copy(id = DrawableId("generated-lip"), texturePage = page, atlasTileId = tile)
        val puppet = author.rig.puppet.copy(atlas = atlas, sources = sources,
            drawables = author.rig.puppet.drawables + mesh, rootChildren = author.rig.puppet.rootChildren + OrgChild.Drawable(mesh.id))
        val art = TileArt(tile.raw, raster.width, raster.height, Bytes.wrap(raster.rgba))
        val checkpoint = RigCheckpoint.encode(author.copy(rig = author.rig.copy(puppet = puppet,
            layerIdByDrawableId = author.rig.layerIdByDrawableId + (mesh.id.raw to assetId),
            pageByDrawableId = author.rig.pageByDrawableId + (mesh.id.raw to page),
            modifierRasterAssets = listOf(art))), initial.sources.bindingKey!!)
        val edited = document.copy(rigEdits = document.rigEdits.copy(authoringJournal = listOf(checkpoint)))
        val revision = WorkspaceRevisions.of(edited)
        project.tree.commit(project.tree.head().node.id, edited, revision, revision, "Apply raster", "user")
        project.store.persistHistory(SyntheticProject.PROJECT, project.tree.state())
        val file = temp.resolve("modifier-raster.psd2live")
        ProjectRepository(writeHeadCache = false).save(project.capture(), file)
        ProjectRepository().open(file).use { opened ->
            val loaded = WorkspacePreviewBuilder().build(opened.history.head().snapshot)
            assertEquals(listOf(art), loaded.authored.rig.modifierRasterAssets)
            assertEquals(combined.placementByLayerId, loaded.atlas.placementByLayerId)
            assertContentEquals(combined.pages[page].png, loaded.atlas.pages[page].png)
            assertEquals(page, loaded.rig.pageByDrawableId[mesh.id.raw])
            assertEquals(art, RigIrCompiler.compile(loaded, tileArt = true).textures.tileArt.single { it.tile == tile.raw })
        }
    }

    @Test fun modifierRigNodeRequiresANewSchemaAndSplitsWithoutDroppingAnyState() {
        val snapshot = buildJsonObject { putJsonObject("rigEdits") { put("modifierStack", stack().toJson()) } }
        val index = ProjectFormatV2.split(temp, snapshot)
        val hash = index.getValue("nodes").jsonObject.getValue("rig").jsonPrimitive.content
        val node = Json.parseToJsonElement(Files.readString(temp.resolve("document/nodes/rig/$hash.json"))).jsonObject
        assertEquals(ProjectFormatV2.NODE_SCHEMA_MODIFIERS, node.getValue("schema").jsonPrimitive.int)
        val restored = ProjectFormatV2.join(temp, index).getValue("rigEdits").jsonObject.getValue("modifierStack")
        assertEquals(stack().toJson(), restored)
        assertEquals(ProjectFormatV2.NODE_SCHEMA, ProjectFormatV2.schemaOf("rig", buildJsonObject { putJsonObject("rigEdits") {} }))
    }
}
