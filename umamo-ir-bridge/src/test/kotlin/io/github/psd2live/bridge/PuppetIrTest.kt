package io.github.psd2live.bridge

import io.github.psd2live.format.model.*
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.RuntimeTarget
import java.lang.reflect.Modifier
import kotlin.test.*

class PuppetIrTest {
    private val multiply = Rgb(.2f, .4f, .6f)
    private val screen = Rgb(.1f, .3f, .5f)
    private val coordinates = Floats.of(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f))
    private val axes = listOf(KeyAxis("x", Floats.of(floatArrayOf(-1f, 1f))))
    private fun <T> grid(first: T, second: T) = KeyGrid(axes,
        listOf(KeyCell(Ints.of(intArrayOf(0)), first), KeyCell(Ints.of(intArrayOf(1)), second)))
    private val channels: Channels = mapOf(
        Channel.OPACITY to grid(ChannelValue.Scalar(.3f), ChannelValue.Scalar(.8f)),
        Channel.DRAW_ORDER to KeyGrid.single(ChannelValue.Scalar(17f)),
        Channel.MULTIPLY_COLOR to KeyGrid.single(ChannelValue.Color(multiply)),
        Channel.SCREEN_COLOR to KeyGrid.single(ChannelValue.Color(screen)),
        Channel.FLIP_X to grid(ChannelValue.Flag(false), ChannelValue.Flag(true)),
        Channel.FLIP_Y to KeyGrid.single(ChannelValue.Flag(true)),
        Channel.GLUE_INTENSITY to KeyGrid.single(ChannelValue.Scalar(.6f)),
    )
    private fun <T> blend(shape: T) = listOf(BlendBinding("blend", Floats.of(floatArrayOf(0f, 1f)), 0,
        listOf(null, shape), listOf(BlendLimit("x", listOf(BlendLimitPoint(-1f, .2f), BlendLimitPoint(1f, .9f))))))

    private fun fixture(): RigIR {
        val composite = Composite(ColorBlend.COLOR_BURN, AlphaBlend.ATOP, listOf("mask"), listOf("mask-part"),
            true, .7f, multiply, screen)
        val mesh = Mesh("mesh", "Mesh", "rotation", ColorBlend.OVERLAY, AlphaBlend.OUT, listOf("mask"), true,
            MeshGeometry(coordinates, coordinates, Ints.of(intArrayOf(0, 1, 2))),
            grid(MeshOffsets(coordinates), MeshOffsets(Floats.of(floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f)))),
            channels, 73f, .8f, multiply, screen, true, false, false, "mask", 0, "tile",
            blend(MeshShape(coordinates, 81f, .6f, screen, multiply)), "mesh-user-data")
        return RigIR(
            canvas = Canvas(128f, 96f, 11f, -23f, 64f),
            parameters = listOf(Parameter("x", "Horizontal", -1f, 1f, .25f, repeat = true, keys = Floats.of(floatArrayOf(-1f, 0f, 1f))),
                Parameter("blend", "Expression", 0f, 1f, .1f, blend = true)),
            parameterLinks = listOf(ParameterLink("x", "blend")),
            parameterTree = listOf(ParameterNode.Group("custom", "Custom", false, listOf(
                ParameterNode.Param("blend"), ParameterNode.Group("preset", "Preset", true,
                    listOf(ParameterNode.Group("plain", "Plain", false, listOf(ParameterNode.Param("x")))), GroupLabel.Preset("BLUE"))),
                GroupLabel.Custom(0xff345678.toInt()))),
            parameterRoles = listOf(ParameterRole("EyeBlink", listOf("x"))),
            parts = listOf(Part("part", "Part", listOf(ChildRef.MeshRef("mesh"), ChildRef.PartRef("mask-part")),
                false, true, false, GroupMode.ISOLATED, 91, channels, composite,
                blend(PartShape(61f, .4f, multiply, screen))),
                Part("mask-part", "Mask", listOf(ChildRef.MeshRef("mask")), groupMode = GroupMode.GROUPED)),
            rootChildren = listOf(ChildRef.PartRef("part")), rootPart = "part",
            deformers = listOf(
                Deformer.Warp("warp", "Warp", null, "part", 1, 1, true,
                    KeyGrid.single(LatticePoints(Floats.of(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)))),
                    channels, .2f, multiply, screen, false, false, false,
                    blend(LatticeShape(Floats.of(floatArrayOf(0f, 0f, 2f, 0f, 0f, 2f, 2f, 2f)), .3f, screen, multiply))),
                Deformer.Rotation("rotation", "Rotation", "warp", "part", 37f,
                    grid(Pivot(2f, 3f, -15f, .8f), Pivot(4f, 5f, 20f, 1.2f)), channels, .6f, multiply, screen,
                    true, true, false, false, false,
                    blend(PivotShape(6f, 7f, 45f, 1.5f, true, true, .4f, screen, multiply)), 19f)),
            meshes = listOf(mesh, Mesh("mask", "Mask", null, geometry = null, offsets = null)),
            glues = listOf(Glue("seam", "mesh", "mask", listOf(GluePair(1, 2, .4f, .6f)), channels, .7f)),
            renderRoot = RenderGroup(null, 31, listOf(RenderMesh("mask"), RenderGroup("part", 29,
                listOf(RenderMesh("mesh")), channels, composite))),
            textures = Textures(listOf(TexturePage(64, 64, Bytes.of(byteArrayOf(1, 3, 5)))), mapOf("mesh" to 0),
                listOf(PageSize(64, 64)), listOf(TextureTile("tile", "Tile", 16, 8,
                    TilePlacement(0, 2f, 3f, .5f, .75f, 90f), SourceRef("art", "layer", false), true, "old-tile"),
                    TextureTile("old-tile", "Old", 8, 8)), false, 7, 5),
            physics = Physics(listOf(PhysicsGroup("physics", "Physics", listOf(PhysicsInput("x", .7f, PhysicsSource.X, true)),
                listOf(PhysicsOutput("blend", 1, .5f, .6f, PhysicsSource.ANGLE, true)), listOf(PhysicsSegment(3f, .4f, .5f, .6f)),
                PhysicsNormalization(-2f, 0f, 2f, -30f, 0f, 30f))), 60f),
            clips = listOf(Clip("clip", "Clip", "Idle", "clip.motion3.json", 2f, 30f, true, .2f, .4f,
                listOf(Curve("x", 0f, .1f, listOf(CurveSegment.Linear(2f, .9f)))), listOf(ClipEvent(1f, "event")))),
            authoring = Authoring("NoTarget", true,
                listOf(SourceFile("art", "Art", "synthetic.psd", "psd", listOf(SourceLayer("layer", "Layer", "Group",
                    2, 3, 16, 8, false, false, "layer-hash", true, true, true)), "source-hash", 123L)),
                listOf(DeformPath("path", "mesh", listOf(PathPoint(0, 1, 2, .2f, .3f, .5f, true),
                    PathPoint(0, 1, 2, .6f, .1f, .3f), PathPoint(0, 1, 2, .1f, .7f, .2f)), 3f, 70f, true, 3)),
                listOf(VertexGroup("weights", "mesh", "pin", Floats.of(floatArrayOf(.2f, .4f, .8f))))),
        )
    }

    @Test fun everyMappedFieldAndSidecarSurvivesTheIrRoundTrip() {
        val ir = fixture()
        val model = PuppetIr.toPuppet(ir)
        assertEquals(ir, PuppetIr.toIr(model, ir.textures.pages, ir.textures.bindings, ir.physics, ir.clips, ir.parameterRoles))
        assertEquals(ir.parameters, PuppetIr.parametersToIr(model))
        assertEquals(ir.meshes, model.drawables.map(PuppetIr::meshToIr))
        assertEquals(ir.deformers, model.deformers.map(PuppetIr::deformerToIr))
    }

    @Test fun engineFieldsSurviveBothConversionsWithoutArrayIdentityAssumptions() {
        val model = PuppetIr.toPuppet(fixture())
        assertDeepEquals(model, PuppetIr.toPuppet(PuppetIr.toIr(model)), "puppet")
        val fields = PuppetModel::class.java.declaredFields.filter { !Modifier.isStatic(it.modifiers) && !it.name.endsWith("\$delegate") }
        assertEquals(setOf("parameters", "parts", "deformers", "drawables", "rootChildren", "rootPartId", "glues", "renderRoot",
            "parameterLinks", "parameterTree", "canvasWidth", "canvasHeight", "worldOriginX", "worldOriginY", "pixelsPerUnit",
            "runtimeTarget", "rendersFromSourceLayers", "atlas", "sources", "deformPaths", "vertexGroups"), fields.map { it.name }.toSet(),
            "Add every new engine field to the fixture and converter")
    }

    @Test fun allBlendAndRuntimeEnumsRoundTrip() {
        val source = fixture()
        for (color in ColorBlend.entries) for (alpha in AlphaBlend.entries) {
            val mesh = source.meshes.first().copy(blend = color, alphaBlend = alpha)
            val ir = source.copy(meshes = listOf(mesh))
            assertEquals(mesh, PuppetIr.toIr(PuppetIr.toPuppet(ir)).meshes.single())
        }
        for (runtime in RuntimeTarget.entries) {
            val model = PuppetIr.toPuppet(source).copy(runtimeTarget = runtime)
            assertEquals(runtime, PuppetIr.toPuppet(PuppetIr.toIr(model)).runtimeTarget)
        }
    }

    private fun assertDeepEquals(expected: Any?, actual: Any?, path: String) {
        if (expected == null || actual == null) { assertEquals(expected, actual, path); return }
        assertEquals(expected.javaClass, actual.javaClass, path)
        when {
            expected.javaClass.isArray -> {
                val count = java.lang.reflect.Array.getLength(expected)
                assertEquals(count, java.lang.reflect.Array.getLength(actual), path)
                repeat(count) { assertDeepEquals(java.lang.reflect.Array.get(expected, it), java.lang.reflect.Array.get(actual, it), "$path[$it]") }
            }
            expected is List<*> -> {
                actual as List<*>
                assertEquals(expected.size, actual.size, path)
                expected.indices.forEach { assertDeepEquals(expected[it], actual[it], "$path[$it]") }
            }
            expected is Map<*, *> -> {
                actual as Map<*, *>
                assertEquals(expected.keys, actual.keys, path)
                expected.forEach { (key, value) -> assertDeepEquals(value, actual[key], "$path[$key]") }
            }
            expected is Enum<*> -> assertEquals(expected, actual, path)
            expected.javaClass.name.startsWith("org.umamo.") -> expected.javaClass.declaredFields
                .filter { !Modifier.isStatic(it.modifiers) && !it.name.endsWith("\$delegate") }
                .forEach { it.isAccessible = true; assertDeepEquals(it.get(expected), it.get(actual), "$path.${it.name}") }
            else -> assertEquals(expected, actual, path)
        }
    }
}
