package io.github.psd2live.scenario

import io.github.psd2live.application.WorkspaceInitialModifiers
import io.github.psd2live.application.WorkspaceSourceImporter
import io.github.psd2live.core.PipelineConfig
import io.github.psd2live.core.RigGeometryTools
import io.github.psd2live.core.RigObjectPaste
import io.github.psd2live.core.modifier.ModifierStatus
import io.github.psd2live.project.ReferencePins
import io.github.psd2live.ui.EditHierarchyMode
import io.github.psd2live.ui.canvasGeometryCommand
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.io.TempDir
import org.umamo.format.cmo3.Cmo3
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.interop.cmo3.Cmo3Import
import org.umamo.interop.cmo3.cmo3AtlasPages
import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.runtime.model.DrawableId
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.*

class IdReferenceMigrationTest {
    @TempDir lateinit var temp: Path

    private fun rename(kind: String, from: String, to: String) =
        req("renames" to listOf(mapOf("target" to "$kind:$from", "id" to to)))

    private fun Studio.recommended() {
        step("import with live modifiers") {
            WorkspaceSourceImporter(runtime).importPsd(Characters.headPsd(temp), null, runtime.state.value.state,
                initialConfig = PipelineConfig(atlasSize = 256, exportMoc3 = false), modifiers = WorkspaceInitialModifiers.RECOMMENDED)
        }
    }

    private fun Studio.sourceLayer(name: String) = now.document.source.layers.single { it.name == name }.id.raw

    private fun Studio.scaffold() = now.model.modifierEvaluation!!.entries.single { it.instance.type == "rig.scaffold" }

    private fun Studio.splitFace(layer: String) {
        edit("split face", "source_split_polygon", req("layer_id" to layer, "names" to listOf("Upper", "Lower"),
            "piece_ids" to listOf("upper", "lower"), "polygon" to listOf(listOf(0, 0), listOf(64, 0), listOf(64, 24), listOf(0, 24))))
    }

    @Test fun renamingASourceMeshKeepsTheLiveBinding() = Studio.run(temp) {
        recommended()
        assertEquals(ModifierStatus.ACTIVE, scaffold().status)
        val hair = sourceLayer("hair")
        val old = mesh(hair)
        edit("convert hair ID", "object_convert_id", rename("mesh", old, "ArtMeshRenamedHair"))
        assertEquals("ArtMeshRenamedHair", mesh(hair))
        assertEquals(ModifierStatus.ACTIVE, scaffold().status, scaffold().error)
        assertTrue(scaffold().instance.targets.any { it.id == "ArtMeshRenamedHair" })
        assertFalse(scaffold().instance.targets.any { it.id == old })
    }

    @Test fun polygonSplitKeepsTheLiveBindingOnBothPieces() = Studio.run(temp) {
        recommended()
        assertEquals(ModifierStatus.ACTIVE, scaffold().status)
        val active = now.model.modifierEvaluation!!.entries.filter { it.status == ModifierStatus.ACTIVE }.map { it.instance.id }.toSet()
        val face = sourceLayer("face")
        val old = mesh(face)
        splitFace(face)
        assertEquals(ModifierStatus.ACTIVE, scaffold().status, scaffold().errorDetails)
        assertTrue(scaffold().instance.targets.map { it.id }.containsAll(listOf(mesh("upper"), mesh("lower"))))
        assertFalse(scaffold().instance.targets.any { it.id == old })
        assertTrue(now.model.modifierEvaluation!!.entries.filter { it.instance.id in active }.all { it.status == ModifierStatus.ACTIVE })
    }

    private fun Studio.pasteFace(): String {
        edit("paste the face", "object_paste", req("sources" to listOf("mesh:${mesh("face")}")))
        return now.document.rigEdits.authoringJournal.filter { it["op"]?.jsonPrimitive?.content == RigObjectPaste.OP }
            .flatMap(RigObjectPaste::copies).single().newId
    }

    @Test fun renamingAPastedMeshKeepsItsOwnDrawOrder() = Studio.run(temp, Characters.head(physics = false)) {
        val copy = pasteFace()
        edit("set the copy's draw order", "layer_draw_order", req("target" to "mesh:$copy", "order" to 900))
        edit("convert the copy", "object_convert_id", rename("mesh", copy, "ArtMeshCopyRenamed"))
        assertEquals(900f, puppet.drawables.single { it.id.raw == "ArtMeshCopyRenamed" }.drawOrder)
        assertFalse(copy in now.model.config.drawOrderOverrides)
    }

    @Test fun aLiveMeshCanUseTheFormerIdOfASplitMesh() = Studio.run(temp, Characters.head(physics = false)) {
        val face = mesh("face")
        edit("convert the face", "object_convert_id", rename("mesh", face, "ArtMeshConvertedFace"))
        edit("reuse the face ID", "object_convert_id", rename("mesh", mesh("hair"), face))
        splitFace("face")
        assertEquals(face, mesh("hair"))
        edit("order the hair mesh", "layer_draw_order", req("target" to "mesh:$face", "order" to 900))
        assertEquals(900f, puppet.drawables.single { it.id.raw == face }.drawOrder)
    }

    @Test fun referencePinsFollowRenamedParametersThroughReopen() {
        val document = Characters.head(physics = false).let { base ->
            base.copy(layout = base.layout.copy(referencePins = mapOf("face" to listOf(mapOf("ParamAngleX" to 20f)))))
        }
        Studio.run(temp, document) {
            assertContains(ReferencePins.hidden(now.document.layout.referencePins, puppet.parameters) { null }, "face")
            edit("convert the pinned angle", "object_convert_id", rename("parameter", "ParamAngleX", "ParamAngleRenamed"))
            reopen()
            assertEquals(setOf("ParamAngleRenamed"), now.document.layout.referencePins.getValue("face").single().keys)
            assertContains(ReferencePins.hidden(now.document.layout.referencePins, puppet.parameters) { null }, "face")
            assertTrue(ReferencePins.hidden(now.document.layout.referencePins, puppet.parameters) { 20f }.isEmpty())
        }
    }

    @Test fun depthSplitKeepsTheTextureOfAPastedMeshThroughReopen() = Studio.run(temp, Characters.head(physics = false)) {
        val face = mesh("face")
        val copy = pasteFace()
        fun sampledPixels(): List<Int> {
            val drawable = puppet.drawables.single { it.id.raw == copy }
            val mesh = drawable.mesh!!
            val page = now.model.atlas.pages[drawable.texturePage].image
            return mesh.indices.toList().chunked(3).map { triangle ->
                val x = (triangle.map { mesh.uvs[it * 2] }.average() * page.width).toInt().coerceIn(0, page.width - 1)
                val y = (triangle.map { mesh.uvs[it * 2 + 1] }.average() * page.height).toInt().coerceIn(0, page.height - 1)
                page.getRGB(x, y)
            }
        }
        val before = sampledPixels()
        assertTrue(before.any { it ushr 24 > 0 })
        edit("split the original face", "source_split_depth", req("source_id" to face, "middle_ids" to listOf(mesh("hair"))))
        assertEquals(before, sampledPixels())
        reopen()
        assertEquals(before, sampledPixels())
        val path = export("cmo3").single { it.toString().endsWith(".cmo3") }
        val parsed = Cmo3.read(Files.readAllBytes(path))
        val source = parsed.root as CModelSource
        val drawable = Cmo3Import.fromModelSource(source).drawables.single { it.id.raw == copy }
        val atlas = cmo3AtlasPages(source, parsed::extractLayerPng)
        val page = ImageIO.read(atlas.pageBytes[atlas.atlasIndexByDrawableId.getValue(copy)].inputStream())
        val mesh = requireNotNull(drawable.mesh)
        assertTrue(mesh.indices.toList().chunked(3).any { triangle ->
            val x = (triangle.map { mesh.uvs[it * 2] }.average() * page.width).toInt().coerceIn(0, page.width - 1)
            val y = (triangle.map { mesh.uvs[it * 2 + 1] }.average() * page.height).toInt().coerceIn(0, page.height - 1)
            page.getRGB(x, y) ushr 24 > 0
        })
    }

    @Test fun depthSplitKeepsHandEditedMeshPositions() = Studio.run(temp) {
        recommended()
        val hair = mesh(sourceLayer("hair"))
        val face = mesh(sourceLayer("face"))
        val point = RigGeometryTools.geometry(puppet, "mesh", hair, emptyMap()).points.copyOf()
        point[0] += .003f
        journal("move a generated vertex", JsonArray(listOf(canvasGeometryCommand(EditHierarchyMode.EDIT, "mesh", hair, emptyMap(), point))))
        val before = CpuDeformationEvaluator().evaluate(puppet, emptyMap()).worldPositions.getValue(DrawableId(hair))
        edit("split the adjusted hair", "source_split_depth", req("source_id" to hair, "middle_ids" to listOf(face),
            "back_layer_id" to "depth:back", "front_layer_id" to "depth:front"))
        val after = CpuDeformationEvaluator().evaluate(puppet, emptyMap()).worldPositions
        for (layer in listOf("depth:back", "depth:front")) {
            val positions = after.getValue(DrawableId(mesh(layer)))
            assertEquals(before.size, positions.size)
            before.indices.forEach { assertEquals(before[it], positions[it], .001f) }
        }
    }
}
