package io.github.psd2live.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import io.github.psd2live.core.*
import io.github.psd2live.ui.state.PSD2LiveViewModel
import org.umamo.format.art.*
import org.umamo.runtime.model.*
import java.awt.Rectangle
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LayerPaintEngineTest {

    @Test
    fun testTipIsSolidToItsCoreAndGoneByItsRadius() {
        val tip = LayerPaintEngine.Tip(radius = 20f, hardness = 0.4f)

        assertEquals(1f, tip.alphaAt(0f), "the centre of the tip is solid")
        assertEquals(1f, tip.alphaAt(tip.core), "and so is everything inside the core")
        assertEquals(0f, tip.alphaAt(tip.radius), "the fade has to reach nothing at the radius itself")
        assertEquals(0f, tip.alphaAt(tip.radius + 3f), "and nothing past it")
        val middle = tip.alphaAt((tip.core + tip.radius) / 2f)
        assertTrue(middle in 0.2f..0.8f, "the falloff has to cross the ring's middle, was $middle")
    }

    @Test
    fun testStrokeDrawsWhereTheTipWent() {
        val image = BufferedImage(100, 100, BufferedImage.TYPE_INT_ARGB)
        val stroker = Stroker(image)
        stroker.segment(50f, 50f, 50f, 50f, LayerPaintEngine.Tip(5f))

        assertEquals(java.awt.Color.RED.rgb, image.getRGB(50, 50))
        assertEquals(0, image.getRGB(58, 50), "and nowhere the tip did not reach")
    }

    @Test
    fun testPencilLeavesNoHalfCoveredPixels() {
        // The pencil is the pixel tool: it either covers a pixel or it does not. An antialiased rim
        // would be a soft edge, which is the brush's job.
        val image = BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB)
        val stroker = Stroker(image, color = Color.Blue)
        stroker.segment(12f, 20.5f, 52f, 20.5f, LayerPaintEngine.Tip(6f, hardness = 1f, antialias = false))

        val alphas = (0 until 64).flatMap { y -> (0 until 64).map { x -> alphaAt(image, x, y) } }
        assertTrue(alphas.any { it == 255 }, "the pencil has to cover something")
        assertTrue(alphas.all { it == 0 || it == 255 }, "a pencil pixel is covered or it is not")
    }

    @Test
    fun testFloodFill() {
        val img = BufferedImage(20, 20, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.color = java.awt.Color.BLACK
        g.drawRect(5, 5, 10, 10)
        g.dispose()

        LayerPaintEngine.floodFill(
            image = img,
            startX = 8, startY = 8,
            fillColor = Color.Yellow,
            tolerance = 10,
            clipRect = Rectangle(0, 0, 20, 20)
        )

        assertEquals(java.awt.Color.YELLOW.rgb, img.getRGB(8, 8))
        assertEquals(0, img.getRGB(2, 2))
    }

    @Test
    fun testDrawShapes() {
        val img = BufferedImage(50, 50, BufferedImage.TYPE_INT_ARGB)

        // Rect
        LayerPaintEngine.drawShape(
            image = img,
            x0 = 5, y0 = 5,
            x1 = 20, y1 = 20,
            shape = PaintShape.RECTANGLE,
            color = Color.Magenta,
            opacity = 1f,
            strokeWidth = 2f,
            filled = true,
            clipRect = Rectangle(0, 0, 50, 50)
        )
        assertEquals(java.awt.Color.MAGENTA.rgb, img.getRGB(10, 10))

        // Ellipse
        LayerPaintEngine.drawShape(
            image = img,
            x0 = 30, y0 = 30,
            x1 = 45, y1 = 45,
            shape = PaintShape.ELLIPSE,
            color = Color.Cyan,
            opacity = 1f,
            strokeWidth = 2f,
            filled = true,
            clipRect = Rectangle(0, 0, 50, 50)
        )
        assertEquals(java.awt.Color.CYAN.rgb, img.getRGB(37, 37))
    }

    private data class TestSourceLayer(
        override val id: LayerId = LayerId("layer_1"),
        override val name: String = "Layer 1",
        override val groupPath: String = "",
        override val order: Int = 0,
        override val bounds: LayerBounds = LayerBounds(0, 0, 64, 64),
        override val opacity: Float = 1f,
        override val clipped: Boolean = false,
        override val blend: LayerBlend = LayerBlend.Normal,
        override val raster: LayerRaster = LayerRaster(64, 64, ByteArray(64 * 64 * 4) { 255.toByte() }),
    ) : SourceLayer

    private class TestSourceArt(
        override val widthPx: Int = 64,
        override val heightPx: Int = 64,
        override val layers: List<SourceLayer> = listOf(TestSourceLayer()),
        override val groups: List<SourceGroup> = emptyList(),
    ) : SourceArt

    private fun createTestEnvironment(): Pair<PSD2LiveViewModel, CanvasEditor> {
        val vm = PSD2LiveViewModel()
        val editor = vm.canvasEditor

        val baseImg = BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB)
        val g = baseImg.createGraphics()
        g.color = java.awt.Color.WHITE
        g.fillRect(0, 0, 64, 64)
        g.dispose()

        val placement = AtlasPlacement(page = 0, x = 0, y = 0, width = 64, height = 64, scale = 1)
        val atlas = PackedAtlas(
            pages = listOf(AtlasPage(baseImg, ByteArray(0))),
            placementByLayerId = mapOf("layer_1" to placement)
        )

        val mesh = DrawableMesh(
            positions = floatArrayOf(0f, 0f, 64f, 0f, 64f, 64f),
            uvs = floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f),
            indices = intArrayOf(0, 1, 2)
        )
        val drawable = Drawable(
            id = DrawableId("artmesh_1"),
            name = "Layer 1",
            parentDeformerId = null,
            blendMode = BlendMode.Normal,
            maskedBy = emptyList(),
            mesh = mesh,
            geometryGrid = null,
            texturePage = 0
        )
        val puppet = PuppetModel(
            parameters = emptyList(),
            parts = emptyList(),
            deformers = emptyList(),
            drawables = listOf(drawable),
            rootChildren = emptyList(),
            rootPartId = null
        )
        val rig = BuiltRig(
            puppet = puppet,
            pageByDrawableId = mapOf("artmesh_1" to 0),
            sourceBoundsByDrawableId = emptyMap(),
            layerIdByDrawableId = mapOf("artmesh_1" to "layer_1"),
            faceCenterX = 32f,
            faceCenterY = 32f,
            faceRadiusX = 20f,
            faceRadiusY = 20f,
            warnings = emptyList()
        )
        val dummyBounds = Bounds(0f, 0f, 64f, 64f)
        val testLayer = TestSourceLayer(id = LayerId("layer_1"), name = "Layer 1")
        val bgLayer = TestSourceLayer(id = LayerId("layer_bg"), name = "Background")
        val classified = ClassifiedLayer(
            source = testLayer,
            semantic = LayerSemantic(tag = SemanticTag.UNKNOWN, normalizedName = "layer_1", confidence = 1f),
            bounds = dummyBounds,
            centroidX = 32f,
            centroidY = 32f,
            opaquePixels = 4096,
        )
        val classifiedBg = ClassifiedLayer(
            source = bgLayer,
            semantic = LayerSemantic(tag = SemanticTag.UNKNOWN, normalizedName = "layer_bg", confidence = 1f),
            bounds = dummyBounds,
            centroidX = 32f,
            centroidY = 32f,
            opaquePixels = 4096,
        )
        val analysis = PipelineAnalysis(
            source = TestSourceArt(layers = listOf(testLayer, bgLayer)),
            layers = listOf(classified, classifiedBg),
            anchors = RigAnchors(dummyBounds, dummyBounds, dummyBounds, 32f, 32f, 32f, 40f, 45f, 60f),
            warnings = emptyList(),
            preview = BufferedImage(10, 10, BufferedImage.TYPE_INT_ARGB)
        )
        val bundle = CubismRuntimeBundle(
            manifestPath = "preview.model3.json",
            assets = listOf(CubismRuntimeAsset("preview.model3.json", byteArrayOf()))
        )
        val previewModel = RigPreviewModel(
            analysis = analysis,
            atlas = atlas,
            rig = rig,
            config = PipelineConfig(),
            runtimeBundle = bundle,
            baseRig = rig
        )

        val historySnapshot = io.github.psd2live.agent.AgentHistorySnapshot(
            headNodeId = "node_1",
            nodes = listOf(
                io.github.psd2live.agent.AgentHistoryNodeSnapshot(
                    id = "node_1",
                    parentId = null,
                    revisionId = "rev_1",
                    summary = "Initial",
                    actor = "USER",
                    taskId = null,
                    createdAt = "2026-01-01T00:00:00Z",
                    isHead = true
                )
            )
        )
        vm.installProjectState(vm.state.value.copy(previewModel = previewModel, analysis = analysis, historySnapshot = historySnapshot))
        vm.selectLayer("layer_1")
        return vm to editor
    }

    @Test
    fun testPaintUndoRedoAndClear() {
        val (vm, editor) = createTestEnvironment()
        editor.setHierarchyMode(EditHierarchyMode.PAINT)
        editor.activateTool(CanvasTool.PAINT_BRUSH)

        // Verify paint target & layer ID resolution
        val paintT = editor.paintTarget()
        assertNotNull(paintT)
        assertEquals("artmesh_1", paintT.id)
        assertEquals("layer_1", editor.targetLayerId(paintT))

        val resolvedPlacement = editor.targetPlacement(paintT)
        assertNotNull(resolvedPlacement)
        assertEquals(0, resolvedPlacement.page)

        // Initialize Paint Session
        val session = editor.ensurePaintSession()
        assertNotNull(session)
        assertEquals("layer_1", session.layerId)
        assertEquals(64, session.docWidth)
        assertEquals(64, session.docHeight)
        assertFalse(session.canUndo())
        assertFalse(session.canRedo())

        // Simulate drawing a stroke, declaring the pixel it takes over the way a raster op does.
        session.edit(Rectangle(10, 10, 1, 1)) { it.setRGB(10, 10, java.awt.Color.RED.rgb) }
        session.recordStroke("笔画 1")
        assertTrue(editor.canUndoPaint())
        assertFalse(editor.canRedoPaint())
        assertEquals(java.awt.Color.RED.rgb, session.workingImage.getRGB(10, 10))

        // Undo
        editor.undoPaint()
        assertEquals(java.awt.Color.WHITE.rgb, session.workingImage.getRGB(10, 10))
        assertTrue(editor.canRedoPaint())

        // Redo
        editor.redoPaint()
        assertEquals(java.awt.Color.RED.rgb, session.workingImage.getRGB(10, 10))

        // Clear layer
        editor.clearCurrentLayerPaint()
        val clearedAlpha = (session.workingImage.getRGB(10, 10) ushr 24) and 0xFF
        assertEquals(0, clearedAlpha)

        // Test Commit to ViewModel
        editor.commitPaintSession(rebuildMesh = false)
        assertNotNull(editor.paintSession)
        assertFalse(editor.paintSession!!.isDirty)
        assertEquals(1, editor.paintSession!!.strokeRecords.size)
        assertFalse(editor.canUndoPaint())
        val updatedLayer = vm.state.value.analysis!!.source.layers.first { it.id.raw == "layer_1" }
        val alpha = updatedLayer.raster.rgba[3].toInt() and 0xFF
        assertEquals(0, alpha)
    }

    @Test
    fun testScreenToAtlasPixelDoesNotSnapOutsideBounds() {
        val (vm, editor) = createTestEnvironment()
        val vp = CanvasViewport(scale = 1.0, offsetX = 0.0, offsetY = 0.0, canvasWidth = 64f, canvasHeight = 64f)
        editor.viewport = vp
        val t = editor.target(layerId = "layer_1", deformerId = null)
        assertNotNull(t)

        // Inside triangle: (10, 10)
        val insidePos = editor.screenToAtlasPixel(Offset(10f, 10f), t, vp)
        assertNotNull(insidePos)
        assertEquals(10, insidePos.first)
        assertEquals(10, insidePos.second)

        // Inside layer bounds (0..64) but outside triangle: (50, 10)
        val insideLayerPos = editor.screenToAtlasPixel(Offset(50f, 10f), t, vp)
        assertNotNull(insideLayerPos)
        assertEquals(50, insideLayerPos.first)
        assertEquals(10, insideLayerPos.second)

        // Points outside layer bounds MUST return null (no vertex snapping, no border clamping)
        assertNull(editor.screenToAtlasPixel(Offset(-10f, -10f), t, vp))
        assertNull(editor.screenToAtlasPixel(Offset(100f, 100f), t, vp))
        assertNull(editor.screenToAtlasPixel(Offset(40f, -5f), t, vp))
        assertNull(editor.screenToAtlasPixel(Offset(40f, 70f), t, vp))
    }

    @Test
    fun testPaintingUpdatesWorkspaceSourceArt() {
        val (vm, editor) = createTestEnvironment()
        editor.setHierarchyMode(EditHierarchyMode.PAINT)
        val session = editor.ensurePaintSession()
        assertNotNull(session)

        val bounds = editor.activePaintLayerBounds()
        assertNotNull(bounds)
        assertEquals(64, bounds.width)
        assertEquals(64, bounds.height)

        session.edit(Rectangle(15, 15, 1, 1)) { it.setRGB(15, 15, java.awt.Color.RED.rgb) }
        session.recordStroke("Stroke 1")

        assertTrue(vm.state.value.analysis!!.source is TestSourceArt)

        editor.commitPaintSession(rebuildMesh = false)

        val updatedSource = vm.state.value.analysis!!.source
        assertTrue(updatedSource is io.github.psd2live.agent.WorkspaceSourceArt)
        val updatedLayer = updatedSource.layers.first()
        assertTrue(updatedLayer is io.github.psd2live.agent.WorkspaceSourceLayer)

        val rgba = updatedLayer.raster.rgba
        val pixelOffset = (15 * 64 + 15) * 4
        assertEquals(255.toByte(), rgba[pixelOffset])     // R
        assertEquals(0.toByte(), rgba[pixelOffset + 1])   // G
        assertEquals(0.toByte(), rgba[pixelOffset + 2])   // B
        assertEquals(255.toByte(), rgba[pixelOffset + 3]) // A
    }

    @Test
    fun testHierarchyModeSwitchDiscardsUncommitted() {
        val (vm, editor) = createTestEnvironment()
        editor.setHierarchyMode(EditHierarchyMode.PAINT)
        val session = editor.ensurePaintSession()
        assertNotNull(session)

        session.edit(Rectangle(20, 20, 1, 1)) { it.setRGB(20, 20, java.awt.Color.GREEN.rgb) }
        session.recordStroke("Stroke Green")
        assertTrue(session.isDirty)

        // Switching away from PAINT mode discards uncommitted session
        editor.setHierarchyMode(EditHierarchyMode.SELECT)
        assertNull(editor.paintSession)

        // Re-entering PAINT mode re-reads untouched baseline
        editor.setHierarchyMode(EditHierarchyMode.PAINT)
        val freshSession = editor.paintSession
        assertNotNull(freshSession)
        assertFalse(freshSession.isDirty)
        assertEquals(java.awt.Color.WHITE.rgb, freshSession.workingImage.getRGB(20, 20))
    }

    @Test
    fun testResetPaintSessionClearsState() {
        val (vm, editor) = createTestEnvironment()
        editor.setHierarchyMode(EditHierarchyMode.PAINT)
        val session = editor.ensurePaintSession()
        assertNotNull(session)
        session.recordStroke("Stroke")

        editor.resetPaintSession()
        assertNull(editor.paintSession)
        assertFalse(editor.isPainting)
    }

    @Test
    fun testPaintSessionJumpToStrokeAndBranching() {
        val baseImg = BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB)
        val g = baseImg.createGraphics()
        g.color = java.awt.Color.WHITE
        g.fillRect(0, 0, 64, 64)
        g.dispose()

        val session = PaintSession(
            layerId = "layer_1",
            layerName = "Layer 1",
            workingImage = baseImg,
            originalImageCopy = PaintSession.copyImage(baseImg)
        )

        assertEquals(1, session.strokeRecords.size)
        assertEquals(0, session.currentStrokeIndex)

        // Stroke 1: Blue
        session.edit(Rectangle(5, 5, 1, 1)) { it.setRGB(5, 5, java.awt.Color.BLUE.rgb) }
        session.recordStroke("Stroke 1 (Blue)")
        assertEquals(2, session.strokeRecords.size)
        assertEquals(1, session.currentStrokeIndex)
        assertEquals(java.awt.Color.BLUE.rgb, session.workingImage.getRGB(5, 5))

        // Stroke 2: Green
        session.edit(Rectangle(5, 5, 1, 1)) { it.setRGB(5, 5, java.awt.Color.GREEN.rgb) }
        session.recordStroke("Stroke 2 (Green)")
        assertEquals(3, session.strokeRecords.size)
        assertEquals(2, session.currentStrokeIndex)
        assertEquals(java.awt.Color.GREEN.rgb, session.workingImage.getRGB(5, 5))

        // Stroke 3: Yellow
        session.edit(Rectangle(5, 5, 1, 1)) { it.setRGB(5, 5, java.awt.Color.YELLOW.rgb) }
        session.recordStroke("Stroke 3 (Yellow)")
        assertEquals(4, session.strokeRecords.size)
        assertEquals(3, session.currentStrokeIndex)
        assertEquals(java.awt.Color.YELLOW.rgb, session.workingImage.getRGB(5, 5))

        // Jump to Stroke 1 (Blue)
        assertNotNull(session.jumpToStroke(1))
        assertEquals(1, session.currentStrokeIndex)
        assertEquals(java.awt.Color.BLUE.rgb, session.workingImage.getRGB(5, 5))
        assertTrue(session.canRedo())

        // Branch by recording a new Stroke 4 (Magenta)
        session.edit(Rectangle(5, 5, 1, 1)) { it.setRGB(5, 5, java.awt.Color.MAGENTA.rgb) }
        session.recordStroke("Stroke 4 (Magenta)")
        // Strokes 2 and 3 should be truncated
        assertEquals(3, session.strokeRecords.size)
        assertEquals(2, session.currentStrokeIndex)
        assertEquals("Stroke 4 (Magenta)", session.strokeRecords[2].name)
        assertFalse(session.canRedo())

        // Jump to initial state (index 0)
        assertNotNull(session.jumpToStroke(0))
        assertEquals(0, session.currentStrokeIndex)
        assertEquals(java.awt.Color.WHITE.rgb, session.workingImage.getRGB(5, 5))

        // Discard restores original copy
        session.discard()
        assertEquals(java.awt.Color.WHITE.rgb, session.workingImage.getRGB(5, 5))
    }

    @Test
    fun testPreviewRepublishesOnlyTheTilesAnEditTouched() {
        // What the canvas draws is published by tile, so a stroke at 4k costs what it covers instead of
        // a copy of the layer. The tiles it did not touch have to be left exactly as they were.
        val session = plainSession(width = 1024, height = 512)
        session.refreshPreview()
        val before = session.previewTiles.associateBy { it.x to it.y }
        assertEquals(2, before.size, "a 1024px layer is two preview tiles wide")

        session.edit(Rectangle(600, 10, 4, 4)) { it.setRGB(600, 10, java.awt.Color.RED.rgb) }
        session.refreshPreview()
        val after = session.previewTiles.associateBy { it.x to it.y }

        assertSame(before[0 to 0]!!.image, after[0 to 0]!!.image, "the tile the edit missed is not republished")
        assertNotSame(before[512 to 0]!!.image, after[512 to 0]!!.image, "the tile it landed in is")
        assertEquals(java.awt.Color.RED.rgb, after[512 to 0]!!.image.toPixelMap()[600 - 512, 10].toArgb())
        assertEquals(0, after[0 to 0]!!.image.toPixelMap()[100, 10].toArgb(), "and holds what it always held")
    }

    @Test
    fun testUndoRepublishesThePixelsItGaveBack() {
        val session = plainSession(width = 1024, height = 512)
        session.edit(Rectangle(600, 10, 4, 4)) { it.setRGB(600, 10, java.awt.Color.RED.rgb) }
        session.recordStroke("red dot")
        session.refreshPreview()
        assertEquals(
            java.awt.Color.RED.rgb,
            session.previewTiles.first { it.x == 512 }.image.toPixelMap()[600 - 512, 10].toArgb(),
        )

        session.undo()
        assertEquals(
            0,
            session.previewTiles.first { it.x == 512 }.image.toPixelMap()[600 - 512, 10].toArgb(),
            "an undo has to reach the canvas, not just the layer",
        )
    }

    private fun plainSession(width: Int, height: Int): PaintSession {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        return PaintSession(
            layerId = "layer_1",
            layerName = "Layer 1",
            workingImage = image,
            originalImageCopy = PaintSession.copyImage(image),
        )
    }

    @Test
    fun testUndoPutsBackExactlyWhatTheStrokeChanged() {
        // A stroke is undone from the pixels it kept, not from a copy of the layer: whatever it did not
        // touch must survive an undo untouched, however many records are walked over.
        val base = BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB)
        val g = base.createGraphics()
        g.color = java.awt.Color(0x808080)
        g.fillRect(0, 0, 64, 64)
        g.dispose()

        val session = PaintSession(
            layerId = "layer_1",
            layerName = "Layer 1",
            workingImage = base,
            originalImageCopy = PaintSession.copyImage(base),
        )
        session.edit(Rectangle(8, 8, 4, 4)) { it.setRGB(8, 8, java.awt.Color.RED.rgb) }
        session.recordStroke("first")
        session.edit(Rectangle(40, 40, 1, 1)) { it.setRGB(40, 40, java.awt.Color.BLUE.rgb) }
        session.recordStroke("second")

        session.undo()
        assertEquals(java.awt.Color.RED.rgb, session.workingImage.getRGB(8, 8), "the first stroke stays")
        assertEquals(0xFF808080.toInt(), session.workingImage.getRGB(40, 40), "the second one is gone")

        session.undo()
        assertEquals(0xFF808080.toInt(), session.workingImage.getRGB(8, 8), "and then the first one")

        session.redo()
        session.redo()
        assertEquals(java.awt.Color.RED.rgb, session.workingImage.getRGB(8, 8))
        assertEquals(java.awt.Color.BLUE.rgb, session.workingImage.getRGB(40, 40))
        assertEquals(0xFF808080.toInt(), session.workingImage.getRGB(20, 20), "and nothing else ever moved")
    }

    private fun alphaAt(image: BufferedImage, x: Int, y: Int): Int = (image.getRGB(x, y) ushr 24) and 0xFF

    /**
     * Drives a stroke the way the editor does: each segment claims the pixels it just covered, the
     * pixels the stroke started from are put back over them, and the stroke's whole coverage is landed
     * on top. The restore is what keeps a stroke that crosses over itself from painting itself twice.
     */
    private class Stroker(
        private val image: BufferedImage,
        private val color: Color = Color.Red,
        erase: Boolean = false,
    ) {
        private val baseline = PaintSession.copyImage(image)
        private val stroke = LayerPaintEngine.Stroke(image.width, image.height)
        private val erase = erase

        fun segment(
            fromX: Float, fromY: Float,
            toX: Float, toY: Float,
            tip: LayerPaintEngine.Tip,
            opacity: Float = 1f,
            color: Color = this.color,
        ) {
            val claimed = stroke.addSegment(fromX, fromY, toX, toY, tip) ?: return
            val g = image.createGraphics()
            try {
                g.composite = java.awt.AlphaComposite.Src
                g.drawImage(
                    baseline,
                    claimed.x, claimed.y, claimed.x + claimed.width, claimed.y + claimed.height,
                    claimed.x, claimed.y, claimed.x + claimed.width, claimed.y + claimed.height,
                    null,
                )
            } finally {
                g.dispose()
            }
            stroke.land(image, color, opacity, erase = erase, region = claimed)
        }
    }

    @Test
    fun testStrokeLandsAtItsOpacityWhereItCrossesItself() {
        // Doubling back over the same pixels is what a stamp-based tip cannot survive: each pass would
        // composite over the last, and a half-opacity stroke would come out opaque.
        val image = BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB)
        val stroker = Stroker(image)
        stroker.segment(12f, 32f, 52f, 32f, LayerPaintEngine.Tip(10f, hardness = 0.4f), opacity = 0.5f)
        // The same pixels a second time, and a third pass crossing them.
        stroker.segment(52f, 32f, 12f, 32f, LayerPaintEngine.Tip(10f, hardness = 0.4f), opacity = 0.5f)
        stroker.segment(32f, 12f, 32f, 52f, LayerPaintEngine.Tip(10f, hardness = 0.4f), opacity = 0.5f)

        assertEquals(128.0, alphaAt(image, 32, 32).toDouble(), 3.0, "the overlap must stay at the stroke's own opacity")
        assertEquals(0xFF0000, image.getRGB(32, 32) and 0xFFFFFF)
    }

    @Test
    fun testStrokeLooksTheSameHoweverTheDragWasChoppedUp() {
        // The anti-flicker guarantee: a stroke is decided by where the pointer went, not by how many
        // events the pointer had time to send. A live stroke that depends on its segmentation would
        // shimmer - and would change under the user's hand whenever the machine got busy.
        val once = BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB)
        val tip = LayerPaintEngine.Tip(7f, hardness = 0.5f)
        Stroker(once).segment(6f, 8f, 58f, 56f, tip, opacity = 0.6f)

        // The same line, walked in twenty collinear pieces the way a slow drag arrives.
        val stepwise = BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB)
        val many = Stroker(stepwise)
        var previous = 6f to 8f
        for (i in 1..20) {
            val t = i / 20f
            val next = (6f + 52f * t) to (8f + 48f * t)
            many.segment(previous.first, previous.second, next.first, next.second, tip, opacity = 0.6f)
            previous = next
        }

        var painted = 0
        for (y in 0 until 64) {
            for (x in 0 until 64) {
                val walked = alphaAt(stepwise, x, y)
                if (alphaAt(once, x, y) > 0) painted++
                assertTrue(
                    kotlin.math.abs(alphaAt(once, x, y) - walked) <= 2,
                    "($x, $y) came out ${alphaAt(once, x, y)} in one segment and $walked in twenty",
                )
            }
        }
        assertTrue(painted > 200, "the stroke has to actually paint something, was $painted pixels")
    }

    @Test
    fun testStrokeFadesFromItsCoreToItsRadius() {
        val image = BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB)
        val stroker = Stroker(image)
        stroker.segment(32f, 32f, 32f, 32f, LayerPaintEngine.Tip(20f, hardness = 0.4f))

        assertTrue(alphaAt(image, 32, 32) >= 253, "the core must be solid, was ${alphaAt(image, 32, 32)}")
        assertEquals(255, alphaAt(image, 32, 24), "and solid all the way out to the core's edge (8px)")
        val fade = alphaAt(image, 32, 18)
        assertTrue(fade in 60..250, "halfway through the feather it must be half covered, was $fade")
        assertEquals(0, alphaAt(image, 32, 11), "nothing may land past the radius")
    }

    @Test
    fun testStrokeKeepsItsEdgeAtACorner() {
        // The corner is where two segments meet, and where a tip drawn as two separate stamps shows a
        // seam: the antialiased rim of the second one lands on the solid middle of the first. The path
        // here turns a right angle; every pixel the path passes through has to be fully covered, and
        // every pixel it does not reach has to be untouched.
        val image = BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB)
        val stroker = Stroker(image)
        val tip = LayerPaintEngine.Tip(9f, hardness = 1f)
        stroker.segment(12f, 32f, 32f, 32f, tip)
        stroker.segment(32f, 32f, 32f, 12f, tip)

        for (y in 0 until 64) {
            for (x in 0 until 64) {
                val distance = distanceToCornerPath(x + 0.5f, y + 0.5f)
                if (distance <= tip.radius - 1f) {
                    assertEquals(255, alphaAt(image, x, y), "($x, $y) is inside the tip's reach, $distance from the path")
                }
                if (distance >= tip.radius + 1f) {
                    assertEquals(0, alphaAt(image, x, y), "($x, $y) is $distance from the path, past the tip")
                }
            }
        }
    }

    /** Distance from a point to the right-angled path the corner test draws. */
    private fun distanceToCornerPath(x: Float, y: Float): Float {
        val onLeg = Math.hypot(
            (x - x.coerceIn(12f, 32f)).toDouble(),
            (y - 32f).toDouble(),
        )
        val onUpright = Math.hypot(
            (x - 32f).toDouble(),
            (y - y.coerceIn(12f, 32f)).toDouble(),
        )
        return minOf(onLeg, onUpright).toFloat()
    }

    @Test
    fun testEraserTakesTheEdgeWithTheCore() {
        val image = BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        g.color = java.awt.Color.BLUE
        g.fillRect(0, 0, 64, 64)
        g.dispose()

        val stroker = Stroker(image, erase = true)
        stroker.segment(32f, 32f, 32f, 32f, LayerPaintEngine.Tip(10f, hardness = 0.4f))

        assertTrue(alphaAt(image, 32, 32) <= 2, "the core must be gone, was ${alphaAt(image, 32, 32)}")
        val rim = alphaAt(image, 32, 41)
        assertTrue(rim in 1..254, "the erased edge must fade too, was $rim")
        assertEquals(255, alphaAt(image, 32, 48), "and leave the rest of the layer alone")
    }

    @Test
    fun testEraserTakesItsOpacityOutOnce() {
        // A half-opacity eraser takes half the layer away - once, however many times the stroke passes
        // over the same pixels. Erasing twice as hard for going back over them is the bug this pins.
        val image = BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        g.color = java.awt.Color.BLUE
        g.fillRect(0, 0, 64, 64)
        g.dispose()

        val stroker = Stroker(image, erase = true)
        val tip = LayerPaintEngine.Tip(8f, hardness = 0.6f)
        stroker.segment(12f, 32f, 52f, 32f, tip, opacity = 0.5f)
        stroker.segment(52f, 32f, 12f, 32f, tip, opacity = 0.5f)

        assertEquals(128.0, alphaAt(image, 32, 32).toDouble(), 3.0, "the overlap must stay at half erased")
        assertEquals(255, alphaAt(image, 32, 56), "and nothing beside the stroke may be touched")
    }

    @Test
    fun testStrokeForgetsThePreviousStroke() {
        val stroke = LayerPaintEngine.Stroke(64, 64)
        val first = BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB)
        val one = stroke.addSegment(10f, 10f, 10f, 10f, LayerPaintEngine.Tip(5f))
        stroke.land(first, Color.Black, 1f, erase = false, region = one!!)
        assertEquals(255, alphaAt(first, 10, 10))

        stroke.reset()
        val second = BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB)
        val two = stroke.addSegment(40f, 40f, 40f, 40f, LayerPaintEngine.Tip(5f))
        stroke.land(second, Color.Black, 1f, erase = false, region = two!!)

        assertEquals(255, alphaAt(second, 40, 40), "the second stroke must land")
        assertEquals(0, alphaAt(second, 10, 10), "and the first one must not repeat itself")
        assertTrue(stroke.bounds!!.contains(40, 40))
        assertTrue(!stroke.bounds!!.contains(10, 10))
    }

    @Test
    fun testStrokeReportsOnlyThePixelsThatJustChanged() {
        // What a live stroke redraws is the ground the last segment claimed: retracing a path the stroke
        // already covers has nothing to redraw, which is what keeps a big brush responsive.
        val stroke = LayerPaintEngine.Stroke(64, 64)
        val tip = LayerPaintEngine.Tip(6f)
        val first = stroke.addSegment(10f, 20f, 50f, 20f, tip)
        assertNotNull(first)

        assertNull(stroke.addSegment(20f, 20f, 40f, 20f, tip), "a retrace adds no coverage")
        val forward = stroke.addSegment(50f, 20f, 54f, 20f, tip)
        assertNotNull(forward)
        // The stroke runs 4..56, so a box that starts past 40 and spans a fraction of it is the far end
        // of the stroke and the rim the new segment sharpened - not the whole thing being redrawn.
        assertTrue(forward.x > 40, "the start of the stroke is not redrawn, was $forward")
        assertTrue(forward.width < 20, "and neither is most of it, was $forward")
    }

    /** The viewport the fixture's 64x64 layer is drawn through, one screen pixel to one canvas pixel. */
    private fun unitViewport() = CanvasViewport(scale = 1.0, offsetX = 0.0, offsetY = 0.0, canvasWidth = 64f, canvasHeight = 64f)

    @Test
    fun testPaintShapeChordsPickAShapeAndTheToolWithIt() {
        // Line, rectangle and ellipse are one tool with three faces, so a chord names a face and the arm
        // follows - the same bargain the deform brush's shapes make.
        val (vm, editor) = createTestEnvironment()
        editor.setHierarchyMode(EditHierarchyMode.PAINT)
        editor.activateTool(CanvasTool.PAINT_BRUSH)

        editor.selectPaintShape(PaintShape.ELLIPSE)
        assertEquals(CanvasTool.PAINT_SHAPE, editor.tool, "picking a shape arms the shape tool")
        assertEquals(PaintShape.ELLIPSE, editor.paintShape)

        editor.cyclePaintShape()
        assertEquals(PaintShape.LINE, editor.paintShape, "the cycle wraps round the three")
        editor.cyclePaintShape()
        assertEquals(PaintShape.RECTANGLE, editor.paintShape)
        assertEquals(CanvasTool.PAINT_SHAPE, editor.tool, "and leaves the tool where it was")
    }

    @Test
    fun testTheRectangleShapeDrawsItsOutlineAndNotItsInside() {
        val (vm, editor) = createTestEnvironment()
        val vp = unitViewport()
        editor.viewport = vp
        editor.setHierarchyMode(EditHierarchyMode.PAINT)
        editor.selectPaintShape(PaintShape.RECTANGLE)
        editor.paintColor = Color.Blue
        editor.paintShapeFilled = false
        editor.paintBrushSize = 2f
        val session = editor.ensurePaintSession()
        assertNotNull(session)
        editor.clearCurrentLayerPaint()

        // From (10, 12) to (50, 40).
        editor.press(Offset(10f, 12f), vp, shift = false, alt = false)
        editor.move(Offset(50f, 40f), vp, shift = false)
        editor.release()

        assertEquals(java.awt.Color.BLUE.rgb, session.workingImage.getRGB(30, 12), "the top edge is drawn")
        // The corner is covered too - antialiased, so the test is that something landed there at all.
        assertTrue(session.workingImage.getRGB(11, 13) != 0, "so is the corner")
        assertEquals(0, session.workingImage.getRGB(30, 26), "an outline leaves the middle empty")
        assertEquals(2, session.strokeCount, "the shape is one stroke, after the clear it was drawn over")
        editor.undoPaint()
        assertEquals(0, session.workingImage.getRGB(30, 12), "and one undo takes the whole shape back")
    }

    @Test
    fun testTheEllipseShapeDrawsItsCurve() {
        val (vm, editor) = createTestEnvironment()
        val vp = unitViewport()
        editor.viewport = vp
        editor.setHierarchyMode(EditHierarchyMode.PAINT)
        editor.selectPaintShape(PaintShape.ELLIPSE)
        editor.paintColor = Color.Blue
        editor.paintShapeFilled = false
        editor.paintBrushSize = 2f
        val session = editor.ensurePaintSession()
        assertNotNull(session)
        editor.clearCurrentLayerPaint()

        editor.press(Offset(10f, 12f), vp, shift = false, alt = false)
        editor.move(Offset(50f, 40f), vp, shift = false)
        editor.release()

        assertEquals(java.awt.Color.BLUE.rgb, session.workingImage.getRGB(30, 12), "the curve reaches the top of its box")
        assertEquals(0, session.workingImage.getRGB(11, 13), "but not the box's corner, which it does not pass through")
        assertEquals(0, session.workingImage.getRGB(30, 26), "and not its middle")
    }

    @Test
    fun testTheLineShapeDrawsCornerToCorner() {
        val (vm, editor) = createTestEnvironment()
        val vp = unitViewport()
        editor.viewport = vp
        editor.setHierarchyMode(EditHierarchyMode.PAINT)
        editor.selectPaintShape(PaintShape.LINE)
        editor.paintColor = Color.Blue
        editor.paintShapeFilled = true // a line has no inside to fill, so this must not matter
        editor.paintBrushSize = 2f
        val session = editor.ensurePaintSession()
        assertNotNull(session)
        editor.clearCurrentLayerPaint()

        editor.press(Offset(10f, 12f), vp, shift = false, alt = false)
        editor.move(Offset(50f, 40f), vp, shift = false)
        editor.release()

        assertEquals(java.awt.Color.BLUE.rgb, session.workingImage.getRGB(30, 26), "the line runs between the corners")
        assertEquals(0, session.workingImage.getRGB(11, 36), "and fills none of the box around it")
        assertEquals(0, session.workingImage.getRGB(45, 16), "on either side of the diagonal")
    }

    @Test
    fun testTheSamplingRingIsThereForEveryWayAPickIsCalled() {
        // The ring is the eyedropper's whole feedback, so it has to be there for all three ways in: the
        // tool itself, Alt over any paint tool, and a pick already under way. A ring that only appears
        // once the button is down is a ring nobody ever sees.
        val (vm, editor) = createTestEnvironment()
        val vp = unitViewport()
        editor.viewport = vp
        editor.setHierarchyMode(EditHierarchyMode.PAINT)
        editor.activateTool(CanvasTool.PAINT_BRUSH)
        editor.move(Offset(20f, 20f), vp, shift = false, alt = false)
        assertNull(editor.pickCursor(), "a brush stroke is not a pick")

        editor.altHeld = true
        assertEquals(Offset(20f, 20f), editor.pickCursor(), "Alt turns the brush into the eyedropper")
        editor.altHeld = false

        // Arming a tool drops the hover: the pointer has not been anywhere since, so the first move over
        // the canvas is what puts the ring back.
        editor.activateTool(CanvasTool.PAINT_EYEDROPPER)
        assertNull(editor.pickCursor())
        editor.move(Offset(20f, 20f), vp, shift = false, alt = false)
        assertEquals(Offset(20f, 20f), editor.pickCursor(), "arming the eyedropper itself is a pick")

        editor.press(Offset(20f, 20f), vp, shift = false, alt = false)
        assertEquals(Offset(20f, 20f), editor.pickCursor(), "a pick keeps its ring while the button is down")
        editor.release()

        editor.clearHover()
        assertNull(editor.pickCursor(), "with the pointer off the canvas there is nothing to point at")
    }

    @Test
    fun testSwappingForegroundAndBackground() {
        val (vm, editor) = createTestEnvironment()
        editor.paintColor = Color.Red
        editor.paintSecondaryColor = Color.Blue

        editor.swapPaintColors()
        assertEquals(Color.Blue, editor.paintColor, "the background comes forward")
        assertEquals(Color.Red, editor.paintSecondaryColor, "and the foreground goes back")

        editor.swapPaintColors()
        assertEquals(Color.Red, editor.paintColor, "and back again")
        assertEquals(Color.Blue, editor.paintSecondaryColor)
    }

    @Test
    fun testEyedropperTakesTheColourUnderThePointer() {
        val (vm, editor) = createTestEnvironment()
        val vp = unitViewport()
        editor.viewport = vp
        editor.setHierarchyMode(EditHierarchyMode.PAINT)
        val session = editor.ensurePaintSession()
        assertNotNull(session)
        session.edit(Rectangle(20, 20, 2, 2)) { it.setRGB(20, 20, java.awt.Color.RED.rgb) }

        editor.activateTool(CanvasTool.PAINT_EYEDROPPER)
        editor.paintColor = Color.Blue
        editor.press(Offset(20f, 20f), vp, shift = false, alt = false)
        editor.release()

        assertEquals(Color.Red, editor.paintColor, "the pick takes the pixel it is over")
        assertEquals(0, session.strokeCount, "a pick is not a stroke")
    }

    @Test
    fun testEyedropperScrubsWhileTheButtonIsHeld() {
        // A colour is found by moving over the art, not by guessing once: the pick follows the pointer.
        val (vm, editor) = createTestEnvironment()
        val vp = unitViewport()
        editor.viewport = vp
        editor.setHierarchyMode(EditHierarchyMode.PAINT)
        val session = editor.ensurePaintSession()
        assertNotNull(session)
        session.edit(Rectangle(10, 10, 12, 2)) { image ->
            image.setRGB(10, 10, java.awt.Color.RED.rgb)
            image.setRGB(20, 10, java.awt.Color.BLUE.rgb)
        }

        editor.activateTool(CanvasTool.PAINT_EYEDROPPER)
        editor.press(Offset(10f, 10f), vp, shift = false, alt = false)
        assertEquals(Color.Red, editor.paintColor)
        assertTrue(editor.isSampling, "the gesture is a pick while it lasts")

        editor.move(Offset(20f, 10f), vp, shift = false)
        assertEquals(Color.Blue, editor.paintColor, "the pick moves with the pointer")

        editor.release()
        assertFalse(editor.isSampling)
        assertEquals(0, session.strokeCount)
    }

    @Test
    fun testAltTurnsAnyPaintToolIntoAPick() {
        // Alt over the artwork is the eyedropper whatever is in hand, and the layer is left alone.
        val (vm, editor) = createTestEnvironment()
        val vp = unitViewport()
        editor.viewport = vp
        editor.setHierarchyMode(EditHierarchyMode.PAINT)
        editor.activateTool(CanvasTool.PAINT_BRUSH)
        editor.paintColor = Color.Blue
        editor.paintBrushSize = 12f
        val session = editor.ensurePaintSession()
        assertNotNull(session)
        session.edit(Rectangle(20, 20, 2, 2)) { it.setRGB(20, 20, java.awt.Color.GREEN.rgb) }

        editor.press(Offset(20f, 20f), vp, shift = false, alt = true)
        assertEquals(Color.Green, editor.paintColor, "the pick takes the colour under it, into the foreground")
        // How ever far the pointer travels while the button is down, it is picking, not painting.
        editor.move(Offset(24f, 20f), vp, shift = false, alt = true)
        editor.release()

        assertEquals(java.awt.Color.GREEN.rgb, session.workingImage.getRGB(20, 20), "the layer is untouched")
        assertEquals(0, session.strokeCount, "and nothing is recorded")
    }

    @Test
    fun testATipCannotPickFromNothing() {
        // An unpainted pixel has no colour to take: the pick leaves the one in hand alone rather than
        // handing back transparent black.
        val (vm, editor) = createTestEnvironment()
        val vp = unitViewport()
        editor.viewport = vp
        editor.setHierarchyMode(EditHierarchyMode.PAINT)
        val session = editor.ensurePaintSession()
        assertNotNull(session)
        editor.clearCurrentLayerPaint()

        editor.activateTool(CanvasTool.PAINT_EYEDROPPER)
        editor.paintColor = Color.Blue
        editor.press(Offset(20f, 20f), vp, shift = false, alt = false)
        assertEquals(Color.Blue, editor.paintColor, "nothing to pick leaves the colour in hand")
        assertNull(editor.sampleColorAt(Offset(20f, 20f), vp))
        editor.release()
    }

    @Test
    fun testPaintTipAdjustGestureRetunesSizeHardnessAndOpacity() {
        val (vm, editor) = createTestEnvironment()
        editor.setHierarchyMode(EditHierarchyMode.PAINT)
        editor.activateTool(CanvasTool.PAINT_BRUSH)
        editor.paintBrushSize = 20f
        editor.paintHardness = 0.5f
        editor.paintOpacity = 0.5f

        // Horizontal travel grows the tip: 24px is two of the 1.2x steps.
        assertTrue(editor.beginBrushAdjust(Offset(100f, 100f)))
        editor.updateBrushAdjust(Offset(124f, 100f))
        assertEquals(28.8f, editor.paintBrushSize, 0.5f)
        editor.endBrushAdjust(cancel = false)

        // Vertical travel hardens or softens it, on the same 200px span the deform brush uses.
        assertTrue(editor.beginBrushAdjust(Offset(100f, 100f)))
        editor.updateBrushAdjust(Offset(100f, 150f))
        assertEquals(0.75f, editor.paintHardness, 0.02f)
        editor.endBrushAdjust(cancel = false)

        // Shift latches the third parameter, the way Photoshop pairs it with opacity.
        assertTrue(editor.beginBrushAdjust(Offset(100f, 100f), shift = true))
        editor.updateBrushAdjust(Offset(100f, 130f))
        assertEquals(0.65f, editor.paintOpacity, 0.02f)
        editor.endBrushAdjust(cancel = true)
        assertEquals(0.5f, editor.paintOpacity, 0.01f, "cancelling must put the tip back")
    }

    @Test
    fun testEraserTakesItsPixelsAwayDuringTheGesture() {
        // The eraser edits as it goes, like every paint program: what the canvas shows for the gesture
        // is the hole itself, so nothing has to be drawn over it to stand in for what happened.
        val (vm, editor) = createTestEnvironment()
        val vp = CanvasViewport(scale = 1.0, offsetX = 0.0, offsetY = 0.0, canvasWidth = 64f, canvasHeight = 64f)
        editor.viewport = vp
        editor.setHierarchyMode(EditHierarchyMode.PAINT)
        val session = editor.ensurePaintSession()
        assertNotNull(session)
        assertEquals(255, alphaAt(session.workingImage, 10, 10), "the fixture's layer starts opaque")

        editor.activateTool(CanvasTool.PAINT_ERASER)
        editor.paintEraserSize = 8f
        editor.press(Offset(10f, 10f), vp, shift = false, alt = false)
        assertEquals(0, alphaAt(session.workingImage, 10, 10), "the press erases where it lands")

        editor.move(Offset(30f, 10f), vp, shift = false)
        assertEquals(0, alphaAt(session.workingImage, 30, 10), "the drag erases the segment it covers")
        assertEquals(255, alphaAt(session.workingImage, 50, 10), "and nothing beside it")

        // Nothing was recorded yet, and abandoning the gesture gives the pixels back.
        assertEquals(1, session.strokeRecords.size)
        editor.cancel()
        assertEquals(255, alphaAt(session.workingImage, 10, 10), "cancelling restores the pixels")
        assertEquals(1, session.strokeRecords.size)

        // A finished stroke is one record, exactly like a brush stroke, and it can be undone.
        editor.press(Offset(10f, 10f), vp, shift = false, alt = false)
        editor.move(Offset(30f, 10f), vp, shift = false)
        editor.release()
        assertEquals(0, alphaAt(session.workingImage, 30, 10))
        assertEquals(2, session.strokeRecords.size)
        editor.undoPaint()
        assertEquals(255, alphaAt(session.workingImage, 30, 10), "one undo takes the whole stroke back")
    }

    @Test
    fun testPaintingOutsideOriginalBoundsExpandsLayerAndBuildsMesh() {
        val (vm, editor) = createTestEnvironment()
        val vp = CanvasViewport(scale = 1.0, offsetX = 0.0, offsetY = 0.0, canvasWidth = 64f, canvasHeight = 64f)
        editor.viewport = vp
        editor.setHierarchyMode(EditHierarchyMode.PAINT)
        editor.activateTool(CanvasTool.PAINT_BRUSH)

        val session = editor.ensurePaintSession()
        assertNotNull(session)

        val initialBounds = editor.activePaintLayerBounds()
        assertNotNull(initialBounds)
        assertEquals(0, initialBounds.left)
        assertEquals(0, initialBounds.top)
        assertEquals(64, initialBounds.width)
        assertEquals(64, initialBounds.height)

        // Clear everything so the layer only has our drawn content
        editor.clearCurrentLayerPaint()

        // User paints a stroke from (20, 20) to (55, 55). The tip paints as it goes, so the drag is
        // driven the way the canvas drives it.
        editor.paintBrushSize = 4f
        editor.paintColor = Color.Blue
        editor.press(Offset(20f, 20f), vp, shift = false, alt = false)
        editor.move(Offset(55f, 55f), vp, shift = false)
        editor.release()

        // Verify that pixels were painted on workingImage at both locations
        val c20 = session.workingImage.getRGB(20, 20)
        val c55 = session.workingImage.getRGB(55, 55)
        assertEquals(java.awt.Color.BLUE.rgb, c20)
        assertEquals(java.awt.Color.BLUE.rgb, c55)

        // Eraser can erase directly without any bounding box
        editor.activateTool(CanvasTool.PAINT_ERASER)
        editor.paintEraserSize = 6f
        editor.press(Offset(20f, 20f), vp, shift = false, alt = false)
        editor.release()

        // (20, 20) is now erased (alpha 0)
        val erasedAlpha = (session.workingImage.getRGB(20, 20) ushr 24) and 0xFF
        assertEquals(0, erasedAlpha)
        // (55, 55) remains blue
        assertEquals(java.awt.Color.BLUE.rgb, session.workingImage.getRGB(55, 55))

        // Commit with rebuildMesh = true
        editor.commitPaintSession(rebuildMesh = true)

        // Verify WorkspaceSourceArt has the newly cropped bounds enclosing (55, 55)
        val updatedSource = vm.state.value.analysis!!.source
        val updatedLayer = updatedSource.layers.first { it.id.raw == "layer_1" }
        assertTrue(updatedLayer.bounds.left <= 55 && (updatedLayer.bounds.left + updatedLayer.bounds.width) >= 55)
        assertTrue(updatedLayer.bounds.top <= 55 && (updatedLayer.bounds.top + updatedLayer.bounds.height) >= 55)

        // Verify that previewModel was rebuilt with updated atlas and mesh
        val rebuiltModel = vm.state.value.previewModel
        assertNotNull(rebuiltModel)
        assertNotNull(rebuiltModel.atlas.placementByLayerId["layer_1"])
    }

    @Test
    fun testCommitPaintPreservesDeformersAndOtherDrawables() {
        val (vm, editor) = createTestEnvironment()
        val currentModel = vm.state.value.previewModel!!

        val customWarp = Deformer.Warp(
            id = DeformerId("deform_face_custom"),
            name = "Face Custom Warp",
            parent = null,
            partId = null,
            rows = 3,
            columns = 3,
            isQuadTransform = true,
            geometryGrid = null,
        )

        val otherMesh = DrawableMesh(
            positions = floatArrayOf(10f, 10f, 30f, 10f, 30f, 30f),
            uvs = floatArrayOf(0.1f, 0.1f, 0.3f, 0.1f, 0.3f, 0.3f),
            indices = intArrayOf(0, 1, 2)
        )
        val otherDrawable = Drawable(
            id = DrawableId("artmesh_other"),
            name = "Other Layer",
            parentDeformerId = null,
            blendMode = BlendMode.Normal,
            maskedBy = emptyList(),
            mesh = otherMesh,
            geometryGrid = null,
            texturePage = 0
        )

        val targetDrawable = currentModel.rig.puppet.drawables.first { it.id.raw == "artmesh_1" }.copy(
            parentDeformerId = DeformerId("deform_face_custom")
        )

        val customPuppet = currentModel.rig.puppet.copy(
            deformers = listOf(customWarp),
            drawables = listOf(targetDrawable, otherDrawable),
        )
        val customRig = currentModel.rig.copy(
            puppet = customPuppet,
            pageByDrawableId = mapOf("artmesh_1" to 0, "artmesh_other" to 0),
            layerIdByDrawableId = mapOf("artmesh_1" to "layer_1", "artmesh_other" to "layer_bg"),
        )
        val customPreview = currentModel.copy(
            rig = customRig,
            baseRig = customRig,
        )
        vm.installProjectState(vm.state.value.copy(previewModel = customPreview))

        val vp = CanvasViewport(scale = 1.0, offsetX = 0.0, offsetY = 0.0, canvasWidth = 64f, canvasHeight = 64f)
        editor.viewport = vp
        editor.setHierarchyMode(EditHierarchyMode.PAINT)
        editor.activateTool(CanvasTool.PAINT_BRUSH)

        val session = editor.ensurePaintSession("layer_1")
        assertNotNull(session)

        // Draw a green stroke
        session.edit(Rectangle(25, 25, 1, 1)) { it.setRGB(25, 25, java.awt.Color.GREEN.rgb) }
        session.recordStroke("Stroke Green")

        // Commit with rebuildMesh = false (standard texture replacement)
        editor.commitPaintSession(rebuildMesh = false)

        val updatedModel = vm.state.value.previewModel!!
        // 1. Warp Deformer is 100% PRESERVED!
        val deformers = updatedModel.rig.puppet.deformers
        assertEquals(1, deformers.size)
        assertEquals("deform_face_custom", deformers[0].id.raw)
        assertEquals(3, (deformers[0] as Deformer.Warp).rows)

        // 2. Other drawable is 100% PRESERVED with its original positions and indices!
        val otherResult = updatedModel.rig.puppet.drawables.first { it.id.raw == "artmesh_other" }
        assertNotNull(otherResult.mesh)
        assertEquals(otherMesh.positions[0], otherResult.mesh!!.positions[0])
        assertEquals(otherMesh.indices[0], otherResult.mesh!!.indices[0])

        // 3. Target drawable keeps its parentDeformerId bound to the warp deformer!
        val targetResult = updatedModel.rig.puppet.drawables.first { it.id.raw == "artmesh_1" }
        assertEquals(DeformerId("deform_face_custom"), targetResult.parentDeformerId)

        // 4. Texture in atlas contains the painted green pixel!
        val placement = updatedModel.atlas.placementByLayerId["layer_1"]!!
        val pageImg = updatedModel.atlas.pages[placement.page].image
        val greenInAtlas = pageImg.getRGB(placement.x + 25, placement.y + 25)
        assertEquals(java.awt.Color.GREEN.rgb, greenInAtlas)
    }

    @Test
    fun testCommitPaintWithRebuildMeshPreservesParentDeformer() {
        val (vm, editor) = createTestEnvironment()
        val currentModel = vm.state.value.previewModel!!

        val customWarp = Deformer.Warp(
            id = DeformerId("warp_container"),
            name = "Container Warp",
            parent = null,
            partId = null,
            rows = 4,
            columns = 4,
            isQuadTransform = true,
            geometryGrid = null,
        )

        val targetDrawable = currentModel.rig.puppet.drawables.first { it.id.raw == "artmesh_1" }.copy(
            parentDeformerId = DeformerId("warp_container")
        )

        val customPuppet = currentModel.rig.puppet.copy(
            deformers = listOf(customWarp),
            drawables = listOf(targetDrawable),
        )
        val customRig = currentModel.rig.copy(
            puppet = customPuppet,
            pageByDrawableId = mapOf("artmesh_1" to 0),
            layerIdByDrawableId = mapOf("artmesh_1" to "layer_1"),
        )
        val customPreview = currentModel.copy(
            rig = customRig,
            baseRig = customRig,
        )
        vm.installProjectState(vm.state.value.copy(previewModel = customPreview))

        val vp = CanvasViewport(scale = 1.0, offsetX = 0.0, offsetY = 0.0, canvasWidth = 64f, canvasHeight = 64f)
        editor.viewport = vp
        editor.setHierarchyMode(EditHierarchyMode.PAINT)
        editor.activateTool(CanvasTool.PAINT_BRUSH)

        val session = editor.ensurePaintSession("layer_1")
        assertNotNull(session)

        // Clear and paint a new shape
        editor.clearCurrentLayerPaint()
        session.edit(Rectangle(10, 10, 2, 2)) { image ->
            image.setRGB(10, 10, java.awt.Color.RED.rgb)
            image.setRGB(11, 10, java.awt.Color.RED.rgb)
            image.setRGB(10, 11, java.awt.Color.RED.rgb)
            image.setRGB(11, 11, java.awt.Color.RED.rgb)
        }
        session.recordStroke("Draw Red Square")

        // Commit with rebuildMesh = true
        editor.commitPaintSession(rebuildMesh = true)

        val updatedModel = vm.state.value.previewModel!!
        // 1. Container Warp is 100% PRESERVED!
        assertEquals(1, updatedModel.rig.puppet.deformers.size)
        assertEquals("warp_container", updatedModel.rig.puppet.deformers[0].id.raw)

        // 2. Target drawable retains parentDeformerId = warp_container
        val targetResult = updatedModel.rig.puppet.drawables.first { it.id.raw == "artmesh_1" }
        assertEquals(DeformerId("warp_container"), targetResult.parentDeformerId)

        // 3. New mesh was generated
        assertNotNull(targetResult.mesh)
        assertTrue(targetResult.mesh!!.positions.isNotEmpty())

        // 4. The rebuilt mesh stays in the parent space the old one used. The analysis knows nothing
        //    about this warp, so that space is recovered from the mesh it replaces - the old mesh was
        //    authored in canvas coordinates, and the 2x2 square painted at (10, 10) has to land there.
        val rebuilt = targetResult.mesh!!
        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        for (index in rebuilt.positions.indices step 2) {
            minX = minOf(minX, rebuilt.positions[index])
            maxX = maxOf(maxX, rebuilt.positions[index])
        }
        assertTrue(minX in 8f..13f, "rebuilt mesh must stay where the painted square is, was $minX..$maxX")
        assertTrue(maxX in 10f..15f, "rebuilt mesh must stay where the painted square is, was $minX..$maxX")
    }

    @Test
    fun testSplitLayerPaintCommitDoesNotLoseContent() {
        val (vm, editor) = createTestEnvironment()
        val currentModel = vm.state.value.previewModel!!

        // Setup split layer: layer_eye split into layer_eye:l and layer_eye:r
        val sourceEye = TestSourceLayer(id = LayerId("layer_eye"), name = "Eyes")
        val classifiedEyeL = ClassifiedLayer(
            source = sourceEye.copy(id = LayerId("layer_eye:l"), name = "Eye L"),
            semantic = LayerSemantic(tag = SemanticTag.IRIDES, side = Side.LEFT, normalizedName = "eye-l", confidence = 1f),
            bounds = Bounds(10f, 10f, 20f, 20f),
            centroidX = 20f,
            centroidY = 20f,
            opaquePixels = 400,
        )
        val classifiedEyeR = ClassifiedLayer(
            source = sourceEye.copy(id = LayerId("layer_eye:r"), name = "Eye R"),
            semantic = LayerSemantic(tag = SemanticTag.IRIDES, side = Side.RIGHT, normalizedName = "eye-r", confidence = 1f),
            bounds = Bounds(35f, 10f, 20f, 20f),
            centroidX = 45f,
            centroidY = 20f,
            opaquePixels = 400,
        )

        val meshL = DrawableMesh(floatArrayOf(0f, 0f, 20f, 0f, 20f, 20f), floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f), intArrayOf(0, 1, 2))
        val meshR = DrawableMesh(floatArrayOf(0f, 0f, 20f, 0f, 20f, 20f), floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f), intArrayOf(0, 1, 2))
        val drawableL = Drawable(DrawableId("artmesh_eye_l"), "Eye L", null, BlendMode.Normal, emptyList(), meshL, null, texturePage = 0)
        val drawableR = Drawable(DrawableId("artmesh_eye_r"), "Eye R", null, BlendMode.Normal, emptyList(), meshR, null, texturePage = 0)

        val splitAnalysis = currentModel.analysis.copy(
            source = TestSourceArt(layers = listOf(sourceEye)),
            layers = listOf(classifiedEyeL, classifiedEyeR),
        )
        val splitAtlas = AtlasPacker.pack(splitAnalysis.layers, 1024, 2)
        val splitRig = currentModel.rig.copy(
            puppet = currentModel.rig.puppet.copy(drawables = listOf(drawableL, drawableR)),
            pageByDrawableId = mapOf("artmesh_eye_l" to 0, "artmesh_eye_r" to 0),
            layerIdByDrawableId = mapOf("artmesh_eye_l" to "layer_eye:l", "artmesh_eye_r" to "layer_eye:r"),
        )
        val splitPreview = currentModel.copy(
            analysis = splitAnalysis,
            atlas = splitAtlas,
            rig = splitRig,
            baseRig = splitRig,
        )
        vm.installProjectState(vm.state.value.copy(previewModel = splitPreview, analysis = splitAnalysis))

        val vp = CanvasViewport(scale = 1.0, offsetX = 0.0, offsetY = 0.0, canvasWidth = 64f, canvasHeight = 64f)
        editor.viewport = vp
        editor.setHierarchyMode(EditHierarchyMode.PAINT)
        editor.activateTool(CanvasTool.PAINT_BRUSH)

        // Select and paint on layer_eye:l
        vm.selectLayer("layer_eye:l")
        val session = editor.ensurePaintSession("layer_eye:l")
        assertNotNull(session)
        assertEquals("layer_eye:l", session.layerId)

        session.edit(Rectangle(15, 15, 1, 1)) { it.setRGB(15, 15, java.awt.Color.CYAN.rgb) }
        session.recordStroke("Paint Cyan on Left Eye")

        // Commit paint
        editor.commitPaintSession(rebuildMesh = false)

        val updatedModel = vm.state.value.previewModel!!
        // Source layer was updated
        val updatedSrc = updatedModel.analysis.source.layers.first { it.id.raw == "layer_eye" }
        assertNotNull(updatedSrc)

        // Classified layer was updated
        val updatedClL = updatedModel.analysis.layers.first { it.source.id.raw == "layer_eye:l" }
        assertNotNull(updatedClL)

        // Right eye is still present and untouched
        val rightDrawable = updatedModel.rig.puppet.drawables.first { it.id.raw == "artmesh_eye_r" }
        assertNotNull(rightDrawable.mesh)

        // Painted Cyan pixel is in atlas
        val placementL = updatedModel.atlas.placementByLayerId["layer_eye:l"]!!
        val pageImg = updatedModel.atlas.pages[placementL.page].image
        val cyanPixel = pageImg.getRGB(placementL.x + (15 - updatedClL.source.bounds.left), placementL.y + (15 - updatedClL.source.bounds.top))
        assertEquals(java.awt.Color.CYAN.rgb, cyanPixel)
    }

    @Test
    fun testCommitPaintRebuildMeshMaintainsProportionsWithoutStretching() {
        val (vm, editor) = createTestEnvironment()
        val currentModel = vm.state.value.previewModel!!

        val vp = CanvasViewport(scale = 1.0, offsetX = 0.0, offsetY = 0.0, canvasWidth = 64f, canvasHeight = 64f)
        editor.viewport = vp
        editor.setHierarchyMode(EditHierarchyMode.PAINT)
        editor.activateTool(CanvasTool.PAINT_BRUSH)

        val session = editor.ensurePaintSession("layer_1")
        assertNotNull(session)

        // Clear and paint a 20x10 rectangular patch (aspect ratio 2.0)
        editor.clearCurrentLayerPaint()
        session.edit(Rectangle(20, 20, 20, 10)) { image ->
            for (x in 20 until 40) {
                for (y in 20 until 30) {
                    image.setRGB(x, y, java.awt.Color.BLUE.rgb)
                }
            }
        }
        session.recordStroke("Draw 20x10 Blue Rectangle")

        // Commit with rebuildMesh = true
        editor.commitPaintSession(rebuildMesh = true)

        val updatedModel = vm.state.value.previewModel!!
        val targetResult = updatedModel.rig.puppet.drawables.first { it.id.raw == "artmesh_1" }
        val mesh = targetResult.mesh
        assertNotNull(mesh)

        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (i in mesh.positions.indices step 2) {
            minX = minOf(minX, mesh.positions[i])
            maxX = maxOf(maxX, mesh.positions[i])
            minY = minOf(minY, mesh.positions[i + 1])
            maxY = maxOf(maxY, mesh.positions[i + 1])
        }

        val meshW = maxX - minX
        val meshH = maxY - minY
        assertTrue(meshW > 0f, "Mesh width must be positive")
        assertTrue(meshH > 0f, "Mesh height must be positive")

        // In normalized coordinates of the parent frame (which is square in character anchors),
        // the mesh width-to-height ratio must be close to 20/10 = 2.0 (never distorted into needle or stretched)
        val ratio = meshW / meshH
        assertTrue(ratio in 1.2f..2.8f, "Aspect ratio must be approximately 2.0, was $ratio (meshW=$meshW, meshH=$meshH)")

        // UVs must also be non-empty and well-formed within [0..1]
        for (i in mesh.uvs.indices step 2) {
            assertTrue(mesh.uvs[i] in 0f..1f, "U coordinate must be in [0..1], was ${mesh.uvs[i]}")
            assertTrue(mesh.uvs[i + 1] in 0f..1f, "V coordinate must be in [0..1], was ${mesh.uvs[i + 1]}")
        }
    }

    @Test
    fun testCommitPaintWithoutRebuildMeshMaintainsGeometry() {
        val (vm, editor) = createTestEnvironment()
        val currentModel = vm.state.value.previewModel!!
        val originalTarget = currentModel.rig.puppet.drawables.first { it.id.raw == "artmesh_1" }
        val originalMesh = originalTarget.mesh!!

        val vp = CanvasViewport(scale = 1.0, offsetX = 0.0, offsetY = 0.0, canvasWidth = 64f, canvasHeight = 64f)
        editor.viewport = vp
        editor.setHierarchyMode(EditHierarchyMode.PAINT)
        editor.activateTool(CanvasTool.PAINT_BRUSH)

        val session = editor.ensurePaintSession("layer_1")
        assertNotNull(session)

        // Paint a stroke without rebuilding mesh
        session.edit(Rectangle(30, 30, 1, 1)) { it.setRGB(30, 30, java.awt.Color.MAGENTA.rgb) }
        session.recordStroke("Draw Magenta Dot")

        editor.commitPaintSession(rebuildMesh = false)

        val updatedModel = vm.state.value.previewModel!!
        val updatedTarget = updatedModel.rig.puppet.drawables.first { it.id.raw == "artmesh_1" }
        val updatedMesh = updatedTarget.mesh
        assertNotNull(updatedMesh)

        // Positions and indices must be 100% identical
        assertEquals(originalMesh.positions.size, updatedMesh.positions.size)
        assertEquals(originalMesh.indices.size, updatedMesh.indices.size)
        for (i in originalMesh.positions.indices) {
            assertEquals(originalMesh.positions[i], updatedMesh.positions[i], 1e-5f)
        }
        for (i in originalMesh.indices.indices) {
            assertEquals(originalMesh.indices[i], updatedMesh.indices[i])
        }
    }
}

