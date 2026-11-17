package example

import io.github.psd2live.algorithms.binding.*
import io.github.psd2live.bridge.PuppetIr
import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.model.*
import kotlinx.serialization.json.*
import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.ParameterId
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.*

class MouthClosedDrawingModulesTest {
    @Test fun vowelsAndCaptureAnimateTheOpeningAndKeepClosedDrawingSwaps() {
        val frame = Bounds(0f, 0f, 1f, 1f)
        fun raw(top: Float, bottom: Float) = ScaffoldMesh.RawMesh(
            floatArrayOf(.1f, top, .9f, top, .1f, bottom, .9f, bottom),
            floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f), intArrayOf(0, 1, 2, 1, 3, 2))
        val raws = linkedMapOf("opening" to raw(.25f, .65f), "closed" to raw(.43f, .47f), "tongue" to raw(.4f, .6f))
        val meshes = raws.map { (id, r) -> Mesh(id, id, null,
            geometry = MeshGeometry(Floats.wrap(r.rigPositions), Floats.wrap(r.atlasUvs), Ints.wrap(r.triangles)),
            offsets = null, drawOrder = if (id == "closed") 200f else 100f) }
        val input = RigIR(Canvas(1f, 1f), listOf(
            Parameter("ParamMouthForm", "Form", -1f, 1f, 0f),
            Parameter("ParamMouthOpenY", "Open", 0f, 1f, 0f)), meshes = meshes)
        val upper = List(MouthFit.SAMPLES) { -.2f * sin(Math.PI.toFloat() * MouthFit.u(it)) }
        val lower = upper.map { -it }
        val fit = MouthFit(.1f, .45f, 1f, 0f, .8f, upper, lower)
        val resource = ScaffoldMeshContext.capture(raws.map { (id, r) ->
            ScaffoldMeshContext.Material(id, id, r, ScaffoldMesh.Geometry.Zero)
        }).resources.getValue("source_meshes")
        val source = MouthFittedInputs(MouthSourceContext(fit, MouthOptions(), null), .9f, null,
            closedMouth = true, separateInside = true, teethFade = 20f,
            layers = raws.mapValues { (id, _) -> MouthFittedInputs.Layer(
                when (id) { "closed" -> "MOUTH_CLOSE"; "tongue" -> "TONGUE"; else -> "MOUTH" }, null, "NONE", null,
                frame, frame, .5f, .45f, opaque = true, clipped = false, preset = true) },
            maskSources = listOf("opening"),
            sourceVertices = MouthFittedInputs.SourceVertices(resource,
                raws.mapValues { (id, _) -> MouthFittedInputs.SourceVertexTarget(id, null) }))
        val base = PuppetIr.toIr(MouthFittedAlgorithm().project(PuppetIr.toPuppet(input), raws.keys, source))
        val targets = raws.keys.map { ModifierTarget("mesh", it) }
        val config = buildJsonObject { put("scaffold", "scaffold"); put("base", "base") }
        val providers = listOf(
            ModifierUpstreamCapability("rig.scaffold", "rig.scaffold", 2, targets, "scaffold"),
            ModifierUpstreamCapability("mouth/fitted", "mouth/fitted", 2, targets, "base", config))
        val follows = raws.mapValues { (id, _) -> if (id == "tongue") MouthRig.Follow.TONGUE else MouthRig.Follow.WHOLE_TIGHT }
        val vowelSource = source.copy(source = MouthSourceContext(fit, MouthOptions(vowels = true), null))
        val vowelContext = MouthVowelAlgorithm.context(vowelSource.encode(), follows,
            MouthRig.VOWELS.associate { it.second.raw to it.first.toString() }, "Mouth").withUpstream(providers)
        val vowel = MouthVowelAlgorithm().changes(ModifierEvaluationInput(base, vowelContext),
            RigModifierInstance("vowels", "mouth.vowel", 2, targets, config = config)) {}.apply(base)

        val captureNames = listOf(MouthRig.MOUTH_X, MouthRig.PUCKER_WIDEN, MouthRig.SHRUG,
            MouthRig.PRESS_OPEN, MouthRig.FUNNEL, MouthRig.CORNER_L, MouthRig.CORNER_R,
            MouthRig.PUCKER_WIDEN_L, MouthRig.PUCKER_WIDEN_R, MouthRig.CHEEK_PUFF, MouthRig.JAW_OPEN)
            .associate { it.raw to it.raw }
        val captureSource = source.copy(source = MouthSourceContext(fit, MouthOptions(vowels = true, capture = true), null))
        val captureContext = MouthCaptureAlgorithm.context(captureSource.encode(), follows, emptySet(), captureNames, "Mouth")
            .withUpstream(providers)
        val capture = MouthCaptureAlgorithm().changes(ModifierEvaluationInput(vowel, captureContext),
            RigModifierInstance("capture", "mouth.capture", 2, targets, config = config)) {}.apply(vowel)
        val tongueConfig = JsonObject(config + ("dependencies" to buildJsonObject { put("mouth.capture", "capture") }))
        val tongueSource = source.copy(source = MouthSourceContext(fit, MouthOptions(vowels = true, capture = true, tongue = true), null))
        val tongueContext = MouthTongueAlgorithm.context(tongueSource.encode(), follows, "Tongue", "Mouth")
            .withUpstream(providers + ModifierUpstreamCapability("mouth.capture", "mouth.capture", 2, targets, "capture", config))
        val captured = MouthTongueAlgorithm().changes(ModifierEvaluationInput(capture, tongueContext),
            RigModifierInstance("tongue", "mouth.tongue", 2, targets, config = tongueConfig)) {}.apply(capture)
        val evaluator = CpuDeformationEvaluator()
        fun pose(model: RigIR, vararg parameters: Pair<String, Float>) = evaluator.evaluate(PuppetIr.toPuppet(model),
            parameters.associate { ParameterId(it.first) to it.second })
        val rest = pose(captured)
        assertEquals(1f, rest.opacity.getValue(DrawableId("closed")), 1e-5f)
        assertEquals(0f, rest.opacity.getValue(DrawableId("opening")), 1e-5f)
        for (axis in listOf("ParamA", "ParamMouthFunnel", "ParamMouthPressLipOpen", "ParamTongueOut")) {
            val opened = pose(captured, axis to 1f)
            assertEquals(0f, opened.opacity.getValue(DrawableId("closed")), 1e-5f, axis)
            assertTrue(opened.opacity.getValue(DrawableId("opening")) > .9f, axis)
            val before = rest.worldPositions.getValue(DrawableId("opening"))
            val after = opened.worldPositions.getValue(DrawableId("opening"))
            assertTrue(before.indices.any { abs(before[it] - after[it]) > .01f }, "$axis must actually open the mouth")
        }
        for (open in listOf(0f, .5f, 1f)) {
            val before = pose(vowel, "ParamMouthOpenY" to open)
            val after = pose(captured, "ParamMouthOpenY" to open)
            for (id in raws.keys.map(::DrawableId)) {
                before.worldPositions.getValue(id).indices.forEach { i ->
                    assertEquals(before.worldPositions.getValue(id)[i], after.worldPositions.getValue(id)[i], 1e-5f)
                }
                assertEquals(before.opacity.getValue(id), after.opacity.getValue(id), 1e-5f)
            }
        }
        val shifted = pose(captured, "ParamMouthX" to 1f)
        assertTrue(rest.worldPositions.getValue(DrawableId("closed")).indices.any { i ->
            abs(rest.worldPositions.getValue(DrawableId("closed"))[i] - shifted.worldPositions.getValue(DrawableId("closed"))[i]) > .01f
        }, "Closed artwork follows capture while visible")
        val extended = pose(captured, "ParamTongueOut" to 1f)
        assertTrue(extended.opacity.getValue(DrawableId("tongue")) > .9f)
        assertTrue(rest.worldPositions.getValue(DrawableId("tongue")).indices.any { i ->
            abs(rest.worldPositions.getValue(DrawableId("tongue"))[i] - extended.worldPositions.getValue(DrawableId("tongue"))[i]) > .01f
        }, "Tongue artwork must move when extended")
    }

    @Test fun layeredMouthPlacesTongueCompanionInHierarchyAndPreservesZeroOpacityLayers() {
        val frame = Bounds(0f, 0f, 1f, 1f)
        fun raw(top: Float, bottom: Float) = ScaffoldMesh.RawMesh(
            floatArrayOf(.1f, top, .9f, top, .1f, bottom, .9f, bottom),
            floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f), intArrayOf(0, 1, 2, 1, 3, 2))
        val raws = linkedMapOf(
            "upperLine" to raw(.35f, .45f),
            "lowerLine" to raw(.45f, .55f),
            "upperLip" to raw(.25f, .45f),
            "lowerLip" to raw(.45f, .65f),
            "cavity" to raw(.35f, .55f),
            "tongue" to raw(.4f, .6f),
            "hiddenTongue" to raw(.4f, .6f))
        val meshes = raws.map { (id, r) -> Mesh(id, id, null,
            geometry = MeshGeometry(Floats.wrap(r.rigPositions), Floats.wrap(r.atlasUvs), Ints.wrap(r.triangles)),
            offsets = null, opacity = if (id == "hiddenTongue") 0f else 1f, drawOrder = 100f) }
        val mouthPart = Part("part:mouth", "Mouth", raws.keys.map(ChildRef::MeshRef))
        val input = RigIR(Canvas(1f, 1f), listOf(
            Parameter("ParamMouthForm", "Form", -1f, 1f, 0f),
            Parameter("ParamMouthOpenY", "Open", 0f, 1f, 0f)),
            parts = listOf(mouthPart),
            rootChildren = listOf(ChildRef.PartRef(mouthPart.id)),
            renderRoot = RenderGroup(null, RigIR.DEFAULT_DRAW_ORDER, raws.keys.map(::RenderMesh)),
            meshes = meshes)
        val upper = List(MouthFit.SAMPLES) { -.2f * sin(Math.PI.toFloat() * MouthFit.u(it)) }
        val lower = upper.map { -it }
        val upperLocal65 = FloatArray(65 * 2)
        val lowerLocal65 = FloatArray(65 * 2)
        val upperWorld65 = FloatArray(65 * 2)
        val lowerWorld65 = FloatArray(65 * 2)
        for (i in 0 until 65) {
            val u = i / 64f
            val lx = (u - .5f) * .8f
            val uy = -.2f * sin(Math.PI.toFloat() * u)
            upperLocal65[i * 2] = lx; upperLocal65[i * 2 + 1] = uy
            lowerLocal65[i * 2] = lx; lowerLocal65[i * 2 + 1] = -uy
            upperWorld65[i * 2] = .5f + lx; upperWorld65[i * 2 + 1] = .45f + uy
            lowerWorld65[i * 2] = .5f + lx; lowerWorld65[i * 2 + 1] = .45f - uy
        }
        val layeredFrame = LayeredMouthFrame(
            hasLines = true, centerX = .5f, centerY = .45f, exX = 1f, exY = 0f, frameWidth = .8f,
            upperWorld65 = upperWorld65, lowerWorld65 = lowerWorld65,
            upperLocal65 = upperLocal65, lowerLocal65 = lowerLocal65, lineNorm = .02f,
            openColsX = floatArrayOf(-.2f, .2f), openTopY = floatArrayOf(-.18f, -.18f), openBotY = floatArrayOf(.18f, .18f),
            upperLipBoundsLocal = Bounds(-.4f, -.2f, .4f, 0f),
            lowerLipBoundsLocal = Bounds(-.4f, 0f, .4f, .2f),
            tongueBoundsLocal = Bounds(-.3f, -.05f, .3f, .15f),
            hUtVis0 = 0f, utSideReserve = 0f, hLtVis0 = 0f)
        val fit = MouthFit(.1f, .45f, 1f, 0f, .8f, upper, lower, layered = layeredFrame)
        val resource = ScaffoldMeshContext.capture(raws.map { (id, r) ->
            ScaffoldMeshContext.Material(id, id, r, ScaffoldMesh.Geometry.Zero)
        }).resources.getValue("source_meshes")
        fun layerTag(id: String) = when (id) {
            "tongue", "hiddenTongue" -> "TONGUE"
            else -> "MOUTH"
        }
        fun layerRole(id: String) = when (id) {
            "upperLine" -> "UPPER_LINE"
            "lowerLine" -> "LOWER_LINE"
            "upperLip" -> "UPPER_LIP"
            "lowerLip" -> "LOWER_LIP"
            else -> null
        }
        val source = MouthFittedInputs(MouthSourceContext(fit, MouthOptions(), null), .9f, null,
            closedMouth = false, separateInside = true, teethFade = 20f,
            layers = raws.mapValues { (id, _) -> MouthFittedInputs.Layer(layerTag(id), layerRole(id), "NONE", null,
                frame, frame, .5f, .45f, opaque = id != "hiddenTongue", clipped = false, preset = true) },
            maskSources = listOf("cavity"),
            sourceVertices = MouthFittedInputs.SourceVertices(resource,
                raws.mapValues { (id, _) -> MouthFittedInputs.SourceVertexTarget(id, null) }))
        val base = PuppetIr.toIr(MouthFittedAlgorithm().project(PuppetIr.toPuppet(input), raws.keys, source))
        val targets = raws.keys.map { ModifierTarget("mesh", it) }
        val config = buildJsonObject { put("scaffold", "scaffold"); put("base", "base") }
        val providers = listOf(
            ModifierUpstreamCapability("rig.scaffold", "rig.scaffold", 2, targets, "scaffold"),
            ModifierUpstreamCapability("mouth/fitted", "mouth/fitted", 2, targets, "base", config))
        val follows = raws.mapValues { (id, _) -> when (id) {
            "upperLine" -> MouthRig.Follow.UPPER_LINE
            "lowerLine" -> MouthRig.Follow.LOWER_LINE
            "upperLip" -> MouthRig.Follow.UPPER_LIP
            "lowerLip" -> MouthRig.Follow.LOWER_LIP
            "tongue", "hiddenTongue" -> MouthRig.Follow.TONGUE
            else -> MouthRig.Follow.INSIDE
        } }
        val vowel = MouthVowelAlgorithm().changes(ModifierEvaluationInput(base, MouthVowelAlgorithm.context(
            source.copy(source = MouthSourceContext(fit, MouthOptions(vowels = true), null)).encode(),
            follows, MouthRig.VOWELS.associate { it.second.raw to it.first.toString() }, "Mouth").withUpstream(providers)),
            RigModifierInstance("vowels", "mouth.vowel", 2, targets, config = config)) {}.apply(base)
        val captureNames = listOf(MouthRig.MOUTH_X, MouthRig.PUCKER_WIDEN, MouthRig.SHRUG,
            MouthRig.PRESS_OPEN, MouthRig.FUNNEL, MouthRig.CORNER_L, MouthRig.CORNER_R,
            MouthRig.PUCKER_WIDEN_L, MouthRig.PUCKER_WIDEN_R, MouthRig.CHEEK_PUFF, MouthRig.JAW_OPEN)
            .associate { it.raw to it.raw }
        val capture = MouthCaptureAlgorithm().changes(ModifierEvaluationInput(vowel, MouthCaptureAlgorithm.context(
            source.copy(source = MouthSourceContext(fit, MouthOptions(vowels = true, capture = true), null)).encode(),
            follows, setOf("upperLip", "lowerLip"), captureNames, "Mouth").withUpstream(providers)),
            RigModifierInstance("capture", "mouth.capture", 2, targets, config = config)) {}.apply(vowel)
        val tongueConfig = JsonObject(config + ("dependencies" to buildJsonObject { put("mouth.capture", "capture") }))
        val tongue = MouthTongueAlgorithm().changes(ModifierEvaluationInput(capture, MouthTongueAlgorithm.context(
            source.copy(source = MouthSourceContext(fit, MouthOptions(vowels = true, capture = true, tongue = true), null)).encode(),
            follows, "Tongue", "Mouth").withUpstream(providers + ModifierUpstreamCapability("mouth.capture", "mouth.capture", 2, targets, "capture", config))),
            RigModifierInstance("tongue", "mouth.tongue", 2, targets, config = tongueConfig)) {}.apply(capture)

        val copyId = tongue.meshes.singleOrNull { it.id !in raws.keys }?.id
        assertNotNull(copyId, "Visible tongue should emit protruding companion")
        assertTrue(tongue.meshes.none { it.id.contains("hiddenTongue") && it.id != "hiddenTongue" },
            "Zero-opacity tongue layer must not emit a protruding companion")
        assertTrue(tongue.parts.single { it.id == "part:mouth" }.children.any { it == ChildRef.MeshRef(copyId) })
        assertTrue(tongue.renderRoot.children.any { (it as? RenderMesh)?.id == copyId })

        val evaluator = CpuDeformationEvaluator()
        for (axis in listOf("ParamMouthOpenY" to 1f, "ParamA" to 1f, "ParamMouthFunnel" to 1f, "ParamTongueOut" to 1f)) {
            val evaluated = evaluator.evaluate(PuppetIr.toPuppet(tongue), mapOf(ParameterId(axis.first) to axis.second))
            assertEquals(0f, evaluated.opacity.getValue(DrawableId("hiddenTongue")), 1e-6f,
                "Zero-opacity layer must remain invisible for ${axis.first}")
        }
    }
}
