package io.github.psd2live.scenario

import io.github.psd2live.agent.AgentToolCatalog
import io.github.psd2live.agent.AgentToolProfile
import io.github.psd2live.application.*
import io.github.psd2live.core.MeshCoverage
import io.github.psd2live.core.RigPreviewModel
import io.github.psd2live.project.*
import io.github.psd2live.ui.state.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import org.umamo.format.art.*
import org.umamo.format.psd.PsdReader
import org.umamo.format.psd.PsdWriter
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class PsdReloadScenario {
    @TempDir lateinit var temp: Path

    private fun editor(body: suspend (PSD2LiveViewModel, DesktopWorkspace, Path) -> Unit) = runBlocking {
        PSD2LiveViewModel().use { vm ->
            vm.setStateForTest(vm.state.value.copy(atlasSize = 512, exportMoc3 = false, generatePhysics = false))
            DesktopWorkspace(vm, temp.resolve("store")).use { workspace ->
                vm.attachWorkspace(workspace)
                val psd = Characters.figurePsd(temp)
                workspace.importPsd(psd.toString(), true)
                body(vm, workspace, psd)
            }
        }
    }

    private fun read(path: Path) = PsdReader.read(Files.readAllBytes(path))
    private fun write(path: Path, art: SourceArt) { Files.write(path, PsdWriter.write(art.widthPx, art.heightPx, art.layers, art.groups)) }
    private suspend fun DesktopWorkspace.reload(psd: Path) = reloadPsd(snapshot().state, psd.toString())

    /** The layer with some of its fields changed. */
    private fun SourceLayer.with(change: WorkspaceSourceLayer.() -> WorkspaceSourceLayer) =
        (WorkspaceSourceLayer.copyOf(this, order) as WorkspaceSourceLayer).change()
    private fun SourceLayer.withPixels(change: (ByteArray) -> Unit) =
        with { copy(raster = LayerRaster(raster.width, raster.height, raster.rgba.copyOf().also(change))) }
    private fun SourceLayer.painted(y: Int) = (0 until raster.width).any { x -> raster.rgba[(y * raster.width + x) * 4 + 3].toInt() != 0 }
    /** The art with [replaced] layers swapped in by ID and [added] ones appended. */
    private fun SourceArt.with(replaced: List<SourceLayer> = emptyList(), added: List<SourceLayer> = emptyList(),
                               groups: List<SourceGroup> = this.groups) =
        WorkspaceSourceArt(widthPx, heightPx, layers.map { layer -> replaced.firstOrNull { it.id == layer.id } ?: layer } + added, groups)

    private fun recolor(art: SourceArt) = art.with(art.layers.map { layer ->
        layer.withPixels { rgba -> for (i in rgba.indices step 4) { rgba[i] = 17; rgba[i + 1] = 33; rgba[i + 2] = 49 } }
    })

    private fun assertRecolored(vm: PSD2LiveViewModel) {
        val rgba = vm.state.value.analysis!!.source.layers.first().raster.rgba
        val i = (3 until rgba.size step 4).first { rgba[it].toInt() != 0 } - 3
        assertEquals(listOf(17, 33, 49), (i..i + 2).map { rgba[it].toInt() and 255 })
    }

    private suspend fun nameTheTurn(workspace: DesktopWorkspace) = workspace.applyDocumentEdits(workspace.snapshot().state, "Name the turn",
        listOf(WorkspaceDocumentOperation("parameter_update", buildJsonObject { put("parameter_id", "ParamAngleX"); put("name", "My turn") })),
        MutationAuthor.USER)

    @Test fun recoloringOverMcpKeepsTheRigAndProjectInOneUndoableStep() = editor { vm, workspace, psd ->
        nameTheTurn(workspace)
        val archive = temp.resolve("authored.psd2live")
        vm.saveProjectNow(archive)
        val saved = Files.readAllBytes(archive)
        val before = workspace.snapshot()
        val nodes = workspace.history().nodes.size
        val oldRig = vm.state.value.previewModel!!.rig.puppet
        WorkspaceOperations(workspace).use { operations ->
            val project = AgentToolCatalog(operations.registry, workspace, AgentToolProfile.CORE).tools.getValue("project")
            suspend fun call(requestId: String, state: String = before.state) = project.handler(buildJsonObject {
                put("op", "reload_psd")
                putJsonObject("request") { put("state", state); put("path", psd.toString()); put("request_id", requestId) }
            }).structuredContent!!
            fun applied(content: JsonObject): Boolean {
                val job = content.getValue("data").jsonObject
                assertEquals("completed", job.getValue("status").jsonPrimitive.content, content.toString())
                return job.getValue("result").jsonObject.getValue("applied").jsonPrimitive.boolean
            }
            // An unchanged PSD adds no step; retrying that request ID after editing the file replays it, a fresh ID reads the edit.
            assertFalse(applied(call("first")))
            write(psd, recolor(read(psd)))
            assertFalse(applied(call("first")))
            val recolored = call("recolor")
            assertTrue(applied(recolored))
            assertEquals(recolored, call("recolor"))
            assertEquals("state_conflict", call("stale").getValue("error").jsonObject.getValue("code").jsonPrimitive.content)
            assertFalse(applied(call("again", workspace.snapshot().state)))
        }
        assertRecolored(vm)
        val afterRig = vm.state.value.previewModel!!.rig.puppet
        assertEquals(Oracles.hash(oldRig.copy(atlas = afterRig.atlas, sources = afterRig.sources)), Oracles.hash(afterRig))
        assertEquals("My turn", afterRig.parameters.single { it.id.raw == "ParamAngleX" }.name)
        assertEquals(before.projectId, workspace.snapshot().projectId)
        assertEquals(archive.toString(), vm.state.value.projectFile)
        assertTrue(vm.state.value.projectDirty)
        assertContentEquals(saved, Files.readAllBytes(archive), "a reload saves nothing")
        assertEquals(nodes + 1, workspace.history().nodes.size)
        val refreshed = workspace.snapshot().historyHeadNodeId!!
        workspace.checkoutHistory(before.historyHeadNodeId!!, MutationAuthor.USER)
        assertEquals(Oracles.hash(oldRig), Oracles.hash(vm.state.value.previewModel!!.rig.puppet))
        workspace.checkoutHistory(refreshed, MutationAuthor.USER)
        assertRecolored(vm)
    }

    @Test fun invalidOrIncompatiblePsdsLeaveTheProjectUntouched() = editor { vm, workspace, psd ->
        val source = read(psd)
        val before = workspace.snapshot()
        val preview = vm.state.value.previewModel
        WorkspaceOperations(workspace).use { operations ->
            val definition = operations.registry.definition("project_reload_psd")
            assertEquals(WorkspaceOperationKind.DOCUMENT, definition.kind)
            assertTrue(definition.jobBacked)
            val tool = assertNotNull(AgentToolCatalog(operations.registry, workspace, AgentToolProfile.FULL).tools["project_reload_psd"])
            suspend fun call(path: String, requestId: String) = tool.handler(buildJsonObject {
                putJsonObject("request") {
                    put("state", before.state); put("project_id", before.projectId); put("request_id", requestId); put("path", path)
                }
            })
            assertTrue(call("relative.psd", "relative-path").isError == true)
            val broken = temp.resolve("broken.psd")
            Files.writeString(broken, "invalid PSD")
            val pending = call(broken.toString(), "invalid-psd").structuredContent!!.getValue("data").jsonObject
            val job = operations.registry.invoke("job_wait", buildJsonObject { put("id", pending.getValue("id")) },
                WorkspaceOperationContext(MutationAuthor.AGENT)).data
            assertEquals("failed", job.getValue("status").jsonPrimitive.content)
        }
        val first = source.layers.first()
        listOf(WorkspaceSourceArt(source.widthPx + 1, source.heightPx, source.layers, source.groups),
            WorkspaceSourceArt(source.widthPx, source.heightPx, source.layers.drop(1), source.groups),
            source.with(listOf(first.with { copy(name = "renamed") }))).forEach { changed ->
            write(psd, changed)
            assertFailsWith<IllegalArgumentException> { workspace.reload(psd) }
            assertEquals(before.state, workspace.snapshot().state)
            assertSame(preview, vm.state.value.previewModel)
        }
        // A layer deleted here stays deleted while its PSD image is unchanged; a changed one is refused.
        write(psd, source)
        workspace.applyDocumentEdits(workspace.snapshot().state, "delete it",
            listOf(WorkspaceDocumentOperation("layer_delete", buildJsonObject { put("layer_id", first.id.raw) })), MutationAuthor.USER)
        val deleted = workspace.snapshot().state
        assertFalse(workspace.reload(psd).applied)
        write(psd, recolor(source))
        assertFailsWith<IllegalArgumentException> { workspace.reload(psd) }
        assertEquals(deleted, workspace.snapshot().state)
    }

    @Test fun theLinkedPsdAndReloadedColorsSurviveSaveAndReopen() = editor { vm, workspace, psd ->
        val archive = temp.resolve("linked.psd2live")
        vm.saveProjectNow(archive)
        workspace.open(archive)
        assertEquals(psd.toString(), vm.state.value.externalPsdPath)
        assertNotEquals(psd.toString(), vm.state.value.inputPath)
        write(psd, recolor(read(psd)))
        workspace.reload(psd)
        vm.saveProjectNow(archive)
        workspace.open(archive)
        assertRecolored(vm)
        assertEquals(psd.toString(), WorkspaceStateCodec.encode(vm.state.value)["externalPsdPath"]?.jsonPrimitive?.content)
    }

    @Test fun changedAlphaInsideTheMeshKeepsAllAuthoredGeometry() = editor { vm, workspace, psd ->
        val source = read(psd)
        val changed = source.layers.first().withPixels { rgba -> rgba[(3 until rgba.size step 4).first { rgba[it].toInt() != 0 }] = 64 }
        val rig = vm.state.value.previewModel!!.rig.puppet
        write(psd, source.with(listOf(changed)))
        workspace.reload(psd)
        val after = vm.state.value.previewModel!!.rig.puppet
        assertEquals(Oracles.hash(rig.copy(atlas = after.atlas, sources = after.sources)), Oracles.hash(after))
        assertContentEquals(changed.raster.rgba, vm.state.value.analysis!!.source.layers.first().raster.rgba)
    }

    @Test fun artGrowingInsideItsRectangleRebuildsOnlyThoseMeshesAndSurvivesReopening() = editor { vm, workspace, psd ->
        nameTheTurn(workspace)
        val source = read(psd)
        assertEquals(0, source.layers.first().raster.rgba[3].toInt())
        // Two layers in one reload: the second rebuild must keep the first.
        val grown = source.layers.take(2).map { layer -> layer.withPixels { it[3] = 1 } }
        write(psd, source.with(grown))
        val oldModel = vm.state.value.previewModel!!
        val nodes = workspace.history().nodes.size
        assertTrue(workspace.reload(psd).applied)
        fun check(model: RigPreviewModel) = grown.forEach {
            assertContentEquals(it.raster.rgba, model.analysis.source.layers.single { next -> next.id == it.id }.raster.rgba)
            assertEquals(0f, MeshCoverage.uncovered(model, it.id.raw), it.id.raw)
        }
        val after = vm.state.value.previewModel!!
        check(after)
        assertEquals("My turn", after.rig.puppet.parameters.single { it.id.raw == "ParamAngleX" }.name)
        oldModel.rig.layerIdByDrawableId.filterValues { id -> grown.none { it.id.raw == id } }.keys.forEach { id ->
            assertContentEquals(oldModel.rig.puppet.drawables.single { it.id.raw == id }.mesh!!.positions,
                after.rig.puppet.drawables.single { it.id.raw == id }.mesh!!.positions)
        }
        assertEquals(nodes + 1, workspace.history().nodes.size)
        assertFalse(workspace.reload(psd).applied)
        val archive = temp.resolve("grown.psd2live")
        vm.saveProjectNow(archive)
        workspace.open(archive)
        check(vm.state.value.previewModel!!)
    }

    @Test fun newRectanglesAndAnAddedLayerReloadInOneStepKeepingForms() = editor { vm, workspace, psd ->
        val source = read(psd)
        val extra = 6
        fun firstRow(l: SourceLayer) = (0 until l.raster.height).firstOrNull { l.painted(it) }
        // Layers with forms first: each grows [extra] opaque rows above its art, inside its old raster - a new, taller rectangle.
        val rig = vm.state.value.previewModel!!.rig
        fun keyed(l: SourceLayer) = rig.puppet.drawables.any { rig.layerIdByDrawableId[it.id.raw] == l.id.raw && (it.geometryGrid?.cells?.size ?: 0) > 1 }
        val grown = source.layers.filter { (firstRow(it) ?: -1) >= extra }.sortedByDescending(::keyed).take(3).map { layer ->
            val top = firstRow(layer)!! - extra
            val width = layer.raster.width
            val rgba = layer.raster.rgba.copyOfRange(top * width * 4, layer.raster.rgba.size)
            for (i in 0 until width * extra) { rgba[i * 4] = 200.toByte(); rgba[i * 4 + 3] = 255.toByte() }
            layer.with { copy(bounds = bounds.copy(top = bounds.top + top, height = raster.height - top),
                raster = LayerRaster(width, raster.height - top, rgba)) }
        }
        assertEquals(3, grown.size)
        val added = source.layers.first().with { copy(id = LayerId("lyid:9999"), name = "new accessory", order = source.layers.size) }
        write(psd, source.with(grown, listOf(added)))
        val nodes = workspace.history().nodes.size
        fun forms(model: RigPreviewModel) = model.rig.puppet.drawables
            .filter { model.rig.layerIdByDrawableId[it.id.raw] in grown.map { g -> g.id.raw } }
            .associate { it.id.raw to it.geometryGrid?.cells?.map { cell -> cell.coordinate.toList() } }
        val oldForms = forms(vm.state.value.previewModel!!)
        assertTrue(oldForms.values.any { (it?.size ?: 0) > 1 })
        assertTrue(workspace.reload(psd).applied)
        val after = vm.state.value.previewModel!!
        grown.forEach {
            assertEquals(it.bounds, after.analysis.source.layers.single { next -> next.id == it.id }.bounds)
            assertEquals(0f, MeshCoverage.uncovered(after, it.id.raw), it.id.raw)
        }
        assertEquals(oldForms, forms(after))
        assertTrue(after.rig.layerIdByDrawableId.values.contains(added.id.raw))
        assertEquals(nodes + 1, workspace.history().nodes.size)
        assertFalse(workspace.reload(psd).applied)
    }

    @Test fun movingALayerWithTheSamePixelsMovesItsMesh() = editor { vm, workspace, psd ->
        val shift = 16
        // Photoshop keeps a layer's rectangle trimmed to its art: crop one to its rows first, then move only the rectangle.
        val full = read(psd)
        fun rows(l: SourceLayer) = (0 until l.raster.height).filter { l.painted(it) }
        val original = full.layers.first { rows(it).isNotEmpty() && rows(it).last() + shift < full.heightPx - it.bounds.top }
        val top = rows(original).first(); val height = rows(original).last() - top + 1; val width = original.raster.width
        val cropped = original.with { copy(bounds = bounds.copy(top = bounds.top + top, height = height),
            raster = LayerRaster(width, height, raster.rgba.copyOfRange(top * width * 4, (top + height) * width * 4))) }
        write(psd, full.with(listOf(cropped)))
        assertTrue(workspace.reload(psd).applied)
        val moved = cropped.with { copy(bounds = bounds.copy(top = bounds.top + shift)) }
        write(psd, full.with(listOf(moved)))
        fun mesh(model: RigPreviewModel) = model.rig.puppet.drawables.first { model.rig.layerIdByDrawableId[it.id.raw] == moved.id.raw }.mesh!!.positions
        val oldMesh = mesh(vm.state.value.previewModel!!)
        assertTrue(workspace.reload(psd).applied)
        val after = vm.state.value.previewModel!!
        assertEquals(moved.bounds, after.analysis.source.layers.single { it.id == moved.id }.bounds)
        assertEquals(0f, MeshCoverage.uncovered(after, moved.id.raw))
        assertFalse(oldMesh.contentEquals(mesh(after)))
        assertFalse(workspace.reload(psd).applied)
    }

    @Test fun hiddenOrEmptyLayersShownLaterGetTheirFirstMeshKeepingTheOthers() = editor { vm, workspace, psd ->
        val source = read(psd)
        val (hidden, empty) = source.layers.take(2)
        write(psd, source.with(listOf(hidden.with { copy(visible = false) },
            empty.with { copy(raster = LayerRaster(raster.width, raster.height, ByteArray(raster.rgba.size))) })))
        workspace.importPsd(psd.toString(), true)
        val old = vm.state.value.previewModel!!
        assertFalse(old.rig.layerIdByDrawableId.values.contains(empty.id.raw))
        write(psd, source)
        workspace.reload(psd)
        val shown = vm.state.value.previewModel!!
        assertTrue(shown.rig.layerIdByDrawableId.values.containsAll(listOf(hidden.id.raw, empty.id.raw)))
        assertContentEquals(empty.raster.rgba, shown.analysis.source.layers.single { it.id == empty.id }.raster.rgba)
        old.rig.puppet.drawables.filter { it.mesh != null }.forEach { drawable ->
            val kept = shown.rig.puppet.drawables.single { it.id == drawable.id }
            assertContentEquals(drawable.mesh!!.positions, kept.mesh!!.positions)
            assertContentEquals(drawable.mesh!!.indices, kept.mesh!!.indices)
        }
        assertFalse(workspace.reload(psd).applied)
    }

    @Test fun layersMovedIntoARenamedOrHiddenFolderKeepTheirRig() = editor { vm, workspace, psd ->
        val source = read(psd)
        val moved = source.layers.take(2)
        fun foldered(name: String, visible: Boolean = true) = source.with(moved.map { it.with { copy(groupPath = name) } },
            groups = source.groups + WorkspaceSourceGroup(name, name, visible, 1f, false, LayerBlend.Normal, true))
        fun meshes() = vm.state.value.previewModel!!.rig.let { rig ->
            rig.puppet.drawables.filter { rig.layerIdByDrawableId[it.id.raw] in moved.map { m -> m.id.raw } } }
        val before = meshes().map { it.id.raw to it.mesh!!.positions.toList() }
        for (name in listOf("Arms", "Crossed arms")) {
            write(psd, foldered(name))
            assertTrue(workspace.reload(psd).applied)
            val layers = vm.state.value.previewModel!!.analysis.source.layers.filter { it.id in moved.map { m -> m.id } }
            assertEquals(listOf(name, name), layers.map { it.groupPath })
            assertEquals(before, meshes().map { it.id.raw to it.mesh!!.positions.toList() })
            assertTrue(meshes().all { it.isVisible })
        }
        // A hidden folder hides its layers, as Photoshop shows them.
        write(psd, foldered("Crossed arms", visible = false))
        assertTrue(workspace.reload(psd).applied)
        assertTrue(meshes().none { it.isVisible })
        assertFalse(workspace.reload(psd).applied)
        write(psd, foldered("Crossed arms"))
        assertTrue(workspace.reload(psd).applied)
        assertTrue(meshes().all { it.isVisible })
    }

    @Test fun theMenuLinksAProjectWithoutAPsdAndKeepsItsUnsavedEdits() = editor { vm, workspace, psd ->
        vm.setStateForTest(vm.state.value.copy(externalPsdPath = null))
        val archive = temp.resolve("legacy.psd2live")
        vm.saveProjectNow(archive)
        workspace.open(archive)
        assertNull(vm.state.value.externalPsdPath)
        val oldId = vm.state.value.projectId
        val nodes = workspace.history().nodes.size
        vm.confirmUnsavedChanges = { error("A texture refresh must not ask to discard the current project") }
        suspend fun reloadFromTheMenu(done: (PSD2LiveState) -> Boolean) {
            withContext(Dispatchers.Main) { vm.reloadPsd(psd) }
            withTimeout(20_000) { vm.state.first { done(it) || it.errorMessage != null } }
            withTimeout(20_000) { vm.state.first { !it.isAnalyzing } }
            assertNull(vm.state.value.errorMessage)
            assertEquals(oldId, vm.state.value.projectId)
        }
        // Linking unchanged pixels must still be saveable, without adding a rig history node.
        reloadFromTheMenu { it.externalPsdPath != null }
        assertTrue(vm.state.value.projectDirty)
        assertEquals(nodes, workspace.history().nodes.size)
        vm.saveProjectNow(archive)
        workspace.open(archive)
        assertEquals(psd.toString(), vm.state.value.externalPsdPath)
        write(psd, recolor(read(psd)))
        reloadFromTheMenu { it.projectDirty }
        assertRecolored(vm)
        assertEquals(archive.toString(), vm.state.value.projectFile)
    }

    @Test fun unchangedPsdPreservesLocalPaint() = editor { vm, workspace, psd ->
        val original = read(psd)
        val painted = recolor(original).layers.first()
        workspace.editTexture(workspace.snapshot().state,
            WorkspaceTextureEdit.ReplaceImage(painted.id.raw, WorkspaceTextureImage.Raster(painted.raster)))
        fun color(): List<Int> {
            val layer = vm.state.value.previewModel!!.analysis.source.layers.single { it.id == painted.id }
            val rgba = layer.raster.rgba
            val i = (3 until rgba.size step 4).first { rgba[it].toInt() != 0 } - 3
            return (i..i + 2).map { rgba[it].toInt() and 255 }
        }
        assertEquals(listOf(17, 33, 49), color())
        val reload = workspace.reload(psd)
        assertEquals(listOf(17, 33, 49), color(), "Unchanged PSD, applied=${reload.applied}")
        assertFalse(reload.applied)
    }

    @Test fun clearedPsdLayerDoesNotKeepStalePixels() = editor { vm, workspace, psd ->
        val original = read(psd)
        val layer = original.layers.first()
        val cleared = layer.with { copy(bounds = LayerBounds(bounds.left, bounds.top, 0, 0), raster = LayerRaster(0, 0, ByteArray(0))) }
        write(psd, original.with(replaced = listOf(cleared)))
        assertEquals(0, read(psd).layers.single { it.id == layer.id }.raster.width)
        assertTrue(workspace.reload(psd).applied)
        val actual = vm.state.value.previewModel!!.analysis.source.layers.single { it.id == layer.id }.raster.rgba
        assertFalse((3 until actual.size step 4).any { actual[it].toInt() != 0 }, "Reload retained old art")
        assertFalse(workspace.reload(psd).applied)
    }

    @Test fun addedLayerInTheMiddleSitsBetweenNeighboringDrawOrders() = editor { vm, workspace, psd ->
        val source = read(psd)
        val insertAt = source.layers.size / 2
        val below = source.layers[insertAt - 1]
        val above = source.layers[insertAt]
        val added = below.with { copy(id = LayerId("lyid:9998"), name = "mid accessory") }
        val stacked = source.layers.take(insertAt) + added + source.layers.drop(insertAt)
        write(psd, WorkspaceSourceArt(source.widthPx, source.heightPx, stacked, source.groups))
        assertTrue(workspace.reload(psd).applied)
        val rig = vm.state.value.previewModel!!.rig
        fun drawOrderOf(layerId: String) = rig.puppet.drawables.first { rig.layerIdByDrawableId[it.id.raw] == layerId }.drawOrder
        val belowOrder = drawOrderOf(below.id.raw)
        val addedOrder = drawOrderOf(added.id.raw)
        val aboveOrder = drawOrderOf(above.id.raw)
        assertTrue(addedOrder in minOf(belowOrder, aboveOrder)..maxOf(belowOrder, aboveOrder) &&
            addedOrder != belowOrder && addedOrder != aboveOrder,
            "Expected $addedOrder strictly between $belowOrder and $aboveOrder")
    }
}
