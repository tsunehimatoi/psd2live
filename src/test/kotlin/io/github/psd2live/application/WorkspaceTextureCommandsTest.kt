package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.project.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import org.umamo.format.art.*
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.*

/** Texture operations through the real registry, jobs, candidate commands and rebuilds, without a GUI. */
class WorkspaceTextureCommandsTest {
    @TempDir lateinit var temporary: Path
    private val builder = WorkspacePreviewBuilder()
    private val agent = WorkspaceOperationContext(MutationAuthor.AGENT)

    private class Host(val runtime: WorkspaceRuntime<RigPreviewModel>) : WorkspaceBackendStub() {
        val textures = WorkspaceTextureCommands(runtime)
        val documents = WorkspaceDocumentCommands(runtime)
        override fun snapshot() = WorkspaceReadSession(runtime.read()).snapshot()
        override fun captureTextures() = WorkspaceTextureView(runtime.capture())
        override suspend fun editTexture(state: String, edit: WorkspaceTextureEdit, author: MutationAuthor) =
            textures.execute(runtime.capture().projectId, state, edit, author).result
        override suspend fun applyDocumentEdits(state: String, summary: String, edits: List<WorkspaceDocumentOperation>, author: MutationAuthor): WorkspaceMutationResult {
            val before = runtime.capture()
            return WorkspaceDocumentCommands.mutationResult(before, documents.execute(before.projectId, state, summary, edits, author), summary, edits)
        }
    }

    private suspend fun fixture(): WorkspaceRuntime<RigPreviewModel> {
        val body = LayerRaster(40, 56, ByteArray(40 * 56 * 4) { if (it % 4 == 3) -1 else 120 })
        val source = WorkspaceSourceArt(128, 96, listOf(
            sourceLayer("body", 0, LayerBounds(10, 20, 40, 56), body),
            sourceLayer("pupil", 1, LayerBounds(70, 30, 32, 32), discRaster(32)),
        ), emptyList())
        val config = PipelineConfig(atlasSize = 2048, meshSpacing = 8, meshOnly = true, exportMoc3 = false)
        val document = WorkspaceDocument(source, emptyMap(), emptySet(), emptyMap(), emptyMap(), config.rigEdits, WorkspaceSettingsCodec.encode(config))
        val runtime = WorkspaceRuntime<RigPreviewModel>({ builder.build(it) })
        runtime.install(runtime.state.value.state, "textures", document, builder.build(document))
        return runtime
    }

    private fun png(raster: LayerRaster): ByteArray {
        val image = BufferedImage(raster.width, raster.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until raster.height) for (x in 0 until raster.width) {
            val o = (y * raster.width + x) * 4
            fun c(i: Int) = raster.rgba[o + i].toInt() and 255
            image.setRGB(x, y, (c(3) shl 24) or (c(0) shl 16) or (c(1) shl 8) or c(2))
        }
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }

    private fun upscaled(raster: LayerRaster, factor: Int) = LayerRaster(raster.width * factor, raster.height * factor,
        ByteArray(raster.width * factor * raster.height * factor * 4).also { out ->
            for (y in 0 until raster.height * factor) for (x in 0 until raster.width * factor)
                System.arraycopy(raster.rgba, ((y / factor) * raster.width + x / factor) * 4, out, (y * raster.width * factor + x) * 4, 4)
        })

    private fun input(runtime: WorkspaceRuntime<RigPreviewModel>, request: String, fields: JsonObject) = JsonObject(fields + buildJsonObject {
        val capture = runtime.capture(); put("project_id", capture.projectId); put("state", capture.state); put("request_id", request)
    })
    private suspend fun WorkspaceOperations.call(id: String, input: JsonObject) = registry.invoke(id, input, agent)
    private suspend fun WorkspaceOperations.job(id: String, input: JsonObject): JsonObject {
        val job = call(id, input).data
        val terminal = call("job_wait", buildJsonObject { put("id", job.getValue("id")) }).data
        return terminal
    }
    private suspend fun WorkspaceOperations.completed(id: String, input: JsonObject): JsonObject {
        val terminal = job(id, input)
        assertEquals("completed", terminal.getValue("status").jsonPrimitive.content, terminal.toString())
        return terminal.getValue("result").jsonObject
    }
    private fun pupil(model: RigPreviewModel) = model.rig.puppet.drawables.single { model.rig.layerIdByDrawableId[it.id.raw] == "pupil" }

    @Test fun replacingA32UnitPupilWithA1024PixelImageKeepsGeometryAndUndoes() = runBlocking<Unit> {
        val runtime = fixture(); val before = runtime.capture(); val nodes = runtime.history().selections.size
        WorkspaceOperations(Host(runtime)).use { operations ->
            val replacement = upscaled(discRaster(32), 32)
            val result = operations.completed("layer_replace_image", input(runtime, "replace", buildJsonObject {
                put("layer_id", "pupil"); put("png_base64", Base64.getEncoder().encodeToString(png(replacement)))
            }))
            assertTrue(result.getValue("applied").jsonPrimitive.boolean)
            assertEquals(listOf("pupil"), result.getValue("layers").jsonArray.map { it.jsonPrimitive.content })
            val after = runtime.capture()
            assertEquals(nodes + 1, runtime.history().selections.size)
            assertEquals(before.model.rig.puppet.drawables.map { it.id }, after.model.rig.puppet.drawables.map { it.id })
            for ((a, b) in before.model.rig.puppet.drawables.zip(after.model.rig.puppet.drawables)) {
                assertContentEquals(a.mesh!!.positions, b.mesh!!.positions); assertContentEquals(a.mesh!!.indices, b.mesh!!.indices)
            }
            val fit = after.model.atlas.fit
            val texture = operations.call("layer_get_texture", buildJsonObject { put("layer_id", "pupil") }).data
            assertEquals(1024, texture.getValue("raster").jsonObject.getValue("width").jsonPrimitive.int)
            assertEquals(32f, texture.getValue("native_density").jsonObject.getValue("x").jsonPrimitive.float)
            assertEquals(32f, texture.getValue("canvas_rect").jsonObject.getValue("width").jsonPrimitive.float)
            val tile = texture.getValue("tile").jsonObject
            assertEquals((1024 * fit).roundToInt(), tile.getValue("width").jsonPrimitive.int)
            assertEquals(after.revision, texture.getValue("revision").jsonPrimitive.content)

            // The same pixels again change nothing and add no node.
            val again = operations.completed("layer_replace_image", input(runtime, "again", buildJsonObject {
                put("layer_id", "pupil"); put("png_base64", Base64.getEncoder().encodeToString(png(replacement)))
            }))
            assertFalse(again.getValue("applied").jsonPrimitive.boolean)
            assertEquals(nodes + 1, runtime.history().selections.size)

            runtime.checkout(after.projectId, after.state, before.historyHead)
            val undone = runtime.capture()
            assertEquals(before.revision, undone.revision)
            assertEquals(32, undone.model.atlas.placementByLayerId.getValue("pupil").width)
        }
    }

    @Test fun aFileReplacementIsReadAfterTheStateCheckAndContainKeepsTheAspect() = runBlocking<Unit> {
        val runtime = fixture()
        val file = temporary.resolve("wide.png"); Files.write(file, png(LayerRaster(64, 32, ByteArray(64 * 32 * 4) { -1 })))
        WorkspaceOperations(Host(runtime)).use { operations ->
            val stale = buildJsonObject { put("layer_id", "pupil"); put("path", file.toString()) }
            assertFailsWith<WorkspaceConflict> { operations.job("layer_replace_image", JsonObject(input(runtime, "stale", stale) + ("state" to JsonPrimitive("stale")))) }
            operations.completed("layer_replace_image", input(runtime, "file", JsonObject(stale + ("fit" to JsonPrimitive("contain")))))
            val layer = runtime.capture().document.source.layers.single { it.id.raw == "pupil" }
            assertEquals(64, layer.raster.width); assertEquals(64, layer.raster.height)
            assertEquals(0, layer.raster.rgba[3].toInt())
            assertEquals(LayerBounds(70, 30, 32, 32), layer.bounds)
        }
    }

    @Test fun densityBudgetPinsAndPackChangeOnlyTheAtlas() = runBlocking<Unit> {
        val runtime = fixture(); val before = runtime.capture()
        WorkspaceOperations(Host(runtime)).use { operations ->
            val dense = operations.completed("layer_set_pixel_density", input(runtime, "density", buildJsonObject {
                putJsonArray("layer_ids") { add("pupil") }; put("density", 2); put("lock", true)
            }))
            assertEquals(listOf("pupil"), dense.getValue("layers").jsonArray.map { it.jsonPrimitive.content })
            val doubled = runtime.capture()
            assertEquals(64, doubled.model.atlas.placementByLayerId.getValue("pupil").width)
            assertEquals(TextureOverride(2f, true), doubled.document.textureOverrides["pupil"])
            for ((a, b) in before.model.rig.puppet.drawables.zip(doubled.model.rig.puppet.drawables)) assertContentEquals(a.mesh!!.positions, b.mesh!!.positions)
            val repeat = operations.completed("layer_set_pixel_density", input(runtime, "density-again", buildJsonObject {
                putJsonArray("layer_ids") { add("pupil") }; put("density", 2)
            }))
            assertFalse(repeat.getValue("applied").jsonPrimitive.boolean)

            operations.completed("atlas_set_tile", input(runtime, "pin", buildJsonObject {
                put("layer_id", "body"); putJsonObject("pin") { put("page", 0); put("x", 300); put("y", 400) }
            }))
            val pinned = runtime.capture().model.atlas.placementByLayerId.getValue("body")
            assertEquals(300 to 400, pinned.x to pinned.y)
            // Moving a tile keeps the layout from then on: the other tiles stay where they were.
            val atlas = operations.call("atlas_get", JsonObject(emptyMap())).data
            assertFalse(atlas.getValue("auto").jsonPrimitive.boolean)
            for ((id, at) in doubled.model.atlas.placementByLayerId) if (id != "body")
                assertEquals(at.x to at.y, runtime.capture().model.atlas.placementByLayerId.getValue(id).let { it.x to it.y }, id)
            assertNull(runtime.capture().document.textureOverrides["body"])

            val small = operations.completed("atlas_set_budget", input(runtime, "budget", buildJsonObject { put("page_size", 256); put("max_pages", 1) }))
            assertEquals(256, runtime.capture().model.atlas.pages.first().image.width)

            val packed = operations.completed("atlas_pack", input(runtime, "pack", JsonObject(emptyMap())))
            assertTrue(packed.getValue("applied").jsonPrimitive.boolean)
            assertTrue("body" in packed.getValue("layers").jsonArray.map { it.jsonPrimitive.content })
            assertEquals(TextureOverride(2f, true), runtime.capture().document.textureOverrides["pupil"])
            val shaped = operations.call("atlas_get", JsonObject(emptyMap())).data.getValue("tiles").jsonArray
            assertTrue(shaped.all { it.jsonObject.getValue("shaped").jsonPrimitive.boolean })
            assertFalse(operations.completed("atlas_pack", input(runtime, "pack-again", JsonObject(emptyMap()))).getValue("applied").jsonPrimitive.boolean)
            // Back to the automatic layout: no stored spots, no shapes.
            operations.completed("atlas_set_budget", input(runtime, "auto", buildJsonObject { put("auto", true) }))
            assertNull(AtlasArrangementCodec.decode(runtime.capture().document.settings))
            assertTrue(operations.call("atlas_get", JsonObject(emptyMap())).data.getValue("auto").jsonPrimitive.boolean)
        }
    }

    /** A denser image outgrows an arranged atlas: every result says so, and set_budget plus pack in one batch fixes it. */
    @Test fun pagesPastTheBudgetAreReportedWhereverTheAtlasChanges() = runBlocking<Unit> {
        val runtime = fixture()
        WorkspaceOperations(Host(runtime)).use { operations ->
            operations.completed("atlas_set_budget", input(runtime, "small", buildJsonObject { put("page_size", 256); put("max_pages", 1) }))
            operations.completed("atlas_pack", input(runtime, "arrange", JsonObject(emptyMap())))
            val pupil = runtime.capture().document.source.layers.single { it.id.raw == "pupil" }.raster
            fun notices(result: JsonObject) = result.getValue("notices").jsonArray.map { it.jsonPrimitive.content }
            suspend fun replace(request: String, factor: Int) = operations.completed("layer_replace_image", input(runtime, request, buildJsonObject {
                put("layer_id", "pupil"); put("png_base64", Base64.getEncoder().encodeToString(png(upscaled(pupil, factor))))
            }))
            // 7x (224 px) still fits a page alone, beside the body no longer: a second page, past the budget of one.
            val paged = replace("dense", 7)
            assertEquals(2, runtime.capture().model.atlas.pages.size)
            assertTrue(notices(paged).any { "more than the budget" in it } && notices(paged).any { "atlas_pack" in it }, notices(paged).toString())
            assertTrue(notices(operations.call("atlas_get", JsonObject(emptyMap())).data).any { "more than the budget" in it })
            // 9x (288 px) fits no page: it is stored shrunk, which the fit of 1 does not show.
            val shrunk = replace("denser", 9)
            assertEquals(1.0, shrunk.getValue("atlas_fit").jsonPrimitive.double)
            assertTrue(notices(shrunk).any { "pupil" in it && "below the density" in it }, notices(shrunk).toString())

            fun edit(id: String, fields: JsonObject) = buildJsonObject { put("operation", id); put("request", fields) }
            val fixed = operations.completed("workspace_apply_edits", input(runtime, "repack", buildJsonObject {
                putJsonArray("edits") {
                    add(edit("atlas_set_budget", buildJsonObject { put("page_size", 1024) }))
                    add(edit("atlas_pack", JsonObject(emptyMap())))
                }
            }))
            assertEquals(1, runtime.capture().model.atlas.pages.size)
            assertEquals(emptyList(), notices(fixed))
            assertTrue(fixed.getValue("atlas_fit").jsonPrimitive.float > 0f)
        }
    }

    @Test fun aBatchCommitsTextureMembersAsOneNodeAndRejectsFilePaths() = runBlocking<Unit> {
        val runtime = fixture(); val nodes = runtime.history().selections.size; val before = runtime.capture()
        WorkspaceOperations(Host(runtime)).use { operations ->
            listOf("layer_set_canvas_rect", "layer_replace_image", "layer_set_pixel_density", "atlas_set_tile", "atlas_set_budget", "atlas_pack")
                .forEach { assertTrue(operations.registry.definition(it).let { d -> d.batchable && d.jobBacked }, it) }
            fun edit(id: String, fields: JsonObject) = buildJsonObject { put("operation", id); put("request", fields) }
            val batch = operations.completed("workspace_apply_edits", input(runtime, "batch", buildJsonObject {
                putJsonArray("edits") {
                    add(edit("layer_replace_image", buildJsonObject { put("layer_id", "pupil"); put("png_base64", Base64.getEncoder().encodeToString(png(discRaster(64)))) }))
                    add(edit("layer_set_pixel_density", buildJsonObject { putJsonArray("layer_ids") { add("pupil") }; put("density", 0.5) }))
                    add(edit("atlas_set_budget", buildJsonObject { put("padding", 4) }))
                }
            }))
            assertEquals(3, batch.getValue("edit_count").jsonPrimitive.int)
            assertEquals(nodes + 1, runtime.history().selections.size)
            val after = runtime.capture()
            assertEquals(32, after.model.atlas.placementByLayerId.getValue("pupil").width)
            assertEquals(4, after.document.config().effectiveAtlasBudget().padding)

            val file = temporary.resolve("pupil.png"); Files.write(file, png(discRaster(32)))
            val rejected = operations.job("workspace_apply_edits", input(runtime, "bad", buildJsonObject {
                putJsonArray("edits") { add(edit("layer_replace_image", buildJsonObject { put("layer_id", "pupil"); put("path", file.toString()) })) }
            }))
            assertEquals("invalid_edit", rejected.getValue("error").jsonObject.getValue("code").jsonPrimitive.content)
            assertEquals(after, runtime.capture())
            assertNotEquals(before.revision, after.revision)
        }
    }

    /**
     * MCP places tiles by the rule the atlas view drags by: atlas_check_placement answers it, atlas_set_tile and
     * layer_set_pixel_density refuse with tile_collides what would leave the page or meet another tile's meshes - nothing
     * changes, no history node - and a batch that moves and grows tiles together is judged by its final layout only.
     */
    @Test fun tilePlacementsAreRefusedWhereTheyWouldNotLand() = runBlocking<Unit> {
        val runtime = fixture()
        WorkspaceOperations(Host(runtime)).use { operations ->
            fun pin(id: String, x: Int, y: Int, rotation: Float? = null) = input(runtime, "pin-$id-$x-$y-$rotation", buildJsonObject {
                put("layer_id", id); putJsonObject("pin") { put("page", 0); put("x", x); put("y", y); rotation?.let { put("rotation", it) } }
            })
            operations.completed("atlas_set_tile", pin("body", 200, 200))
            operations.completed("atlas_set_tile", pin("pupil", 150, 200))
            val placed = runtime.capture().model.atlas.placementByLayerId
            assertEquals(150 to 200, placed.getValue("pupil").let { it.x to it.y })
            suspend fun check(vararg entries: JsonObject) = operations.call("atlas_check_placement", buildJsonObject {
                putJsonArray("placements") { entries.forEach { add(it) } }
            }).data
            fun entry(id: String, x: Int? = null, y: Int? = null, rotation: Float? = null, density: Float? = null) = buildJsonObject {
                put("layer_id", id); x?.let { put("x", it) }; y?.let { put("y", it) }; rotation?.let { put("rotation", it) }; density?.let { put("density", it) }
            }
            fun tile(answer: JsonObject, id: String) = answer.getValue("tiles").jsonArray.map { it.jsonObject }.single { it.getValue("layer_id").jsonPrimitive.content == id }

            // Onto the body: the meshes meet. Into free space: clear.
            val onto = check(entry("pupil", 204, 210))
            assertFalse(onto.getValue("clear").jsonPrimitive.boolean)
            assertEquals(listOf("body"), tile(onto, "pupil").getValue("overlaps").jsonArray.map { it.jsonPrimitive.content })
            assertTrue(check(entry("pupil", 600, 600)).getValue("clear").jsonPrimitive.boolean)
            // Turned near the page edge, the corners leave the page.
            val edge = runtime.capture().model.atlas.pages[0].image.width - 34
            val turned = tile(check(entry("pupil", edge, 600, rotation = 45f)), "pupil")
            assertTrue(turned.getValue("outside_page").jsonPrimitive.boolean)
            // Doubled in place, the pupil's disc reaches into the body; the answer gives the size it would have.
            val grown = check(entry("pupil", density = 2f))
            assertFalse(grown.getValue("clear").jsonPrimitive.boolean)
            assertEquals(64, tile(grown, "pupil").getValue("width").jsonPrimitive.int)

            // The commands refuse the same placements and change nothing.
            val before = runtime.capture(); val nodes = runtime.history().selections.size
            for ((id, request) in listOf("atlas_set_tile" to pin("pupil", 204, 210), "atlas_set_tile" to pin("pupil", edge, 600, 45f),
                "layer_set_pixel_density" to input(runtime, "grow", buildJsonObject { putJsonArray("layer_ids") { add("pupil") }; put("density", 2) }))) {
                val failed = operations.job(id, request)
                val error = failed.getValue("error").jsonObject
                assertEquals("tile_collides", error.getValue("code").jsonPrimitive.content, failed.toString())
                // The tiles that would be pushed: the moved pupil, or the body a grown pupil would take the room of.
                val pushed = error.getValue("layer_ids").jsonArray.map { it.jsonPrimitive.content }
                assertTrue(pushed.isNotEmpty() && setOf("pupil", "body").containsAll(pushed), pushed.toString())
                assertEquals(before.revision, runtime.capture().revision)
                assertEquals(nodes, runtime.history().selections.size)
            }

            // Moved clear and doubled in one batch: only the final layout counts, and it lands exactly.
            fun op(id: String, request: JsonObject) = buildJsonObject { put("operation", id); put("request", request) }
            operations.completed("workspace_apply_edits", input(runtime, "batch", buildJsonObject {
                putJsonArray("edits") {
                    add(op("layer_set_pixel_density", buildJsonObject { putJsonArray("layer_ids") { add("pupil") }; put("density", 2) }))
                    add(op("atlas_set_tile", buildJsonObject { put("layer_id", "pupil"); putJsonObject("pin") { put("page", 0); put("x", 600); put("y", 600); put("rotation", 30) } }))
                }
            }))
            val landed = runtime.capture().model.atlas.placementByLayerId.getValue("pupil")
            assertEquals(listOf(600, 600, 64), listOf(landed.x, landed.y, landed.width))
            assertEquals(30f, landed.rotation)
            assertEquals(nodes + 1, runtime.history().selections.size)
        }
    }

    /**
     * A tile turned on its page: its pixels are written turned, its meshes' texture coordinates follow the turn so
     * each vertex samples the raster point it did upright, the stored arrangement keeps the angle, and atlas_get reports it.
     */
    @Test fun aTurnedTileKeepsItsMeshesOnTheirPixels() = runBlocking<Unit> {
        val runtime = fixture(); val before = runtime.capture()
        WorkspaceOperations(Host(runtime)).use { operations ->
            operations.completed("atlas_set_tile", input(runtime, "turn", buildJsonObject {
                put("layer_id", "pupil"); putJsonObject("pin") { put("page", 0); put("x", 300); put("y", 400); put("rotation", 90) }
            }))
            val after = runtime.capture()
            val placed = after.model.atlas.placementByLayerId.getValue("pupil")
            assertEquals(Triple(300, 400, 90f), Triple(placed.x, placed.y, placed.rotation))
            assertEquals(90f, AtlasArrangementCodec.decode(after.document.settings)!!.tiles.getValue("pupil").rotation)
            val tile = operations.call("atlas_get", JsonObject(emptyMap())).data.getValue("tiles").jsonArray
                .map { it.jsonObject }.single { it.getValue("layer_id").jsonPrimitive.content == "pupil" }
            assertEquals(90f, tile.getValue("rotation").jsonPrimitive.float)

            // Every vertex samples the same raster point as before the turn.
            val was = before.model.atlas.placementByLayerId.getValue("pupil")
            val pageBefore = before.model.atlas.pages[was.page].image; val pageAfter = after.model.atlas.pages[placed.page].image
            val uvBefore = pupil(before.model).mesh!!.uvs; val uvAfter = pupil(after.model).mesh!!.uvs
            assertEquals(uvBefore.size, uvAfter.size)
            for (v in 0 until uvBefore.size / 2) {
                val a = was.toRaster(uvBefore[v * 2] * pageBefore.width, uvBefore[v * 2 + 1] * pageBefore.height)
                val b = placed.toRaster(uvAfter[v * 2] * pageAfter.width, uvAfter[v * 2 + 1] * pageAfter.height)
                assertTrue(abs(a[0] - b[0]) < 0.01f && abs(a[1] - b[1]) < 0.01f, "vertex $v: ${a.toList()} vs ${b.toList()}")
            }
            // And the turned page holds the raster's pixels there: the disc's colour by position survives the turn.
            val raster = discRaster(32)
            for ((rx, ry) in listOf(16 to 16, 10 to 14, 20 to 9, 13 to 22)) {
                val p = placed.toPage(rx + 0.5f, ry + 0.5f)
                val c = pageAfter.getRGB(p[0].toInt(), p[1].toInt())
                val o = (ry * 32 + rx) * 4
                val expected = listOf(raster.rgba[o].toInt() and 255, raster.rgba[o + 1].toInt() and 255, raster.rgba[o + 2].toInt() and 255)
                val actual = listOf(c ushr 16 and 255, c ushr 8 and 255, c and 255)
                assertTrue(expected.zip(actual).all { (e, a) -> abs(e - a) <= 12 }, "raster ($rx, $ry): $expected vs $actual")
            }
            // Turned back upright, the tile is axis aligned again.
            operations.completed("atlas_set_tile", input(runtime, "upright", buildJsonObject {
                put("layer_id", "pupil"); putJsonObject("pin") { put("page", 0); put("x", 300); put("y", 400) }
            }))
            assertEquals(0f, runtime.capture().model.atlas.placementByLayerId.getValue("pupil").rotation)
        }
    }

    /**
     * layer_set_canvas_rect moves a layer as a whole (a layer_transform): its meshes move, its pixels, texture tile and
     * integer frame stay, the texture reports where it now shows, and a layer with keyforms moves with them.
     */
    @Test fun movingALayerMovesItsMeshesAndKeepsItsPixels() = runBlocking<Unit> {
        val runtime = fixture(); val before = runtime.capture()
        fun centre(model: RigPreviewModel): Pair<Float, Float> {
            val points = pupil(model).mesh!!.positions
            return points.filterIndexed { i, _ -> i % 2 == 0 }.average().toFloat() to points.filterIndexed { i, _ -> i % 2 == 1 }.average().toFloat()
        }
        WorkspaceOperations(Host(runtime)).use { operations ->
            operations.completed("layer_set_canvas_rect", input(runtime, "move", buildJsonObject {
                put("layer_id", "pupil"); putJsonObject("rect") { put("left", 80); put("top", 40); put("width", 32); put("height", 32) }
            }))
            val moved = runtime.capture()
            val (x0, y0) = centre(before.model); val (x1, y1) = centre(moved.model)
            assertTrue(abs(x1 - x0 - 10f) < 1f && abs(y1 - y0 - 10f) < 1f, "Moved by ${x1 - x0}, ${y1 - y0}")
            val layerBefore = before.document.source.layers.single { it.id.raw == "pupil" }
            val layerAfter = moved.document.source.layers.single { it.id.raw == "pupil" }
            assertEquals(layerBefore.bounds, layerAfter.bounds, "the layer's own frame stays")
            assertContentEquals(layerBefore.raster.rgba, layerAfter.raster.rgba)
            assertEquals(before.model.atlas.placementByLayerId.getValue("pupil"), moved.model.atlas.placementByLayerId.getValue("pupil"))
            assertEquals(before.document.generationSource, moved.document.generationSource)

            // A fractional rectangle: the texture reports where the layer shows.
            operations.completed("layer_set_canvas_rect", input(runtime, "fraction", buildJsonObject {
                put("layer_id", "pupil"); putJsonObject("rect") { put("left", 80.5); put("top", 40); put("width", 31.25); put("height", 32) }
            }))
            val texture = operations.call("layer_get_texture", buildJsonObject { put("layer_id", "pupil") }).data
            assertEquals(80.5f, texture.getValue("canvas_rect").jsonObject.getValue("left").jsonPrimitive.float, 1e-3f)
            assertEquals(31.25f, texture.getValue("canvas_rect").jsonObject.getValue("width").jsonPrimitive.float, 1e-3f)

            // A layer with keyforms moves with them.
            val mesh = pupil(runtime.capture().model)
            operations.completed("workspace_apply_edits", input(runtime, "author", buildJsonObject {
                putJsonArray("edits") {
                    add(buildJsonObject { put("operation", "parameter_create"); putJsonObject("request") { put("parameter_id", "Look"); put("name", "Look") } })
                    add(buildJsonObject { put("operation", "keyform_apply"); putJsonObject("request") { putJsonArray("changes") { add(buildJsonObject {
                        put("op", "set"); put("target", "mesh:${mesh.id.raw}"); putJsonObject("key") { put("Look", 1) }
                        putJsonObject("channels") { put("opacity", 0.5) }
                    }) } } })
                }
            }))
            val authored = runtime.capture()
            operations.completed("layer_set_canvas_rect", input(runtime, "authored", buildJsonObject {
                put("layer_id", "pupil"); putJsonObject("rect") { put("left", 0); put("top", 0); put("width", 31.25); put("height", 32) }
            }))
            val again = runtime.capture()
            assertTrue(centre(again.model).first < centre(authored.model).first - 70f)
            assertEquals(pupil(authored.model).channelGrids, pupil(again.model).channelGrids)
        }
    }

    @Test fun anAtlasPageRendersFromTheCapturedVersion() = runBlocking<Unit> {
        val runtime = fixture(); val before = runtime.capture()
        WorkspaceOperations(Host(runtime)).use { operations ->
            val job = operations.call("atlas_render_page", input(runtime, "render", buildJsonObject { put("page", 0); put("max_size", 512) })).data
            val terminal = operations.call("job_wait", buildJsonObject { put("id", job.getValue("id")) })
            val result = terminal.data.getValue("result").jsonObject
            assertEquals(before.revision, result.getValue("revision").jsonPrimitive.content)
            assertEquals(2048, result.getValue("width").jsonPrimitive.int)
            assertEquals(512, result.getValue("rendered_width").jsonPrimitive.int)
            assertEquals(setOf("body", "pupil"), result.getValue("layers").jsonArray.map { it.jsonPrimitive.content }.toSet())
            val image = ImageIO.read(terminal.images.single().inputStream())
            assertEquals(512, image.width)
            assertFailsWith<IllegalArgumentException> { operations.call("atlas_render_page", input(runtime, "missing", buildJsonObject { put("page", 5) })) }
        }
    }
}
