package example

import io.github.psd2live.algorithms.binding.*
import io.github.psd2live.core.modifier.*
import io.github.psd2live.bridge.PuppetIr
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.umamo.runtime.model.*
import kotlin.test.*

class LegacyBodyAlgorithmTest {
    private val body = Bounds(12f, 15f, 212f, 330f)
    private val head = ScaffoldHead.Input(body, Bounds(50f, 20f, 150f, 130f), Bounds(65f, 30f, 135f, 120f),
        ScaffoldHead.Point(100f, 94f), ScaffoldHead.Point(100f, 94f), 0f, false, emptyList())
    private val target = ModifierTarget("mesh", "art")
    private val label = { key: String -> key }

    @Test fun bodyUpdatesScaffoldAliasesAndKeepsUnrelatedMaterial() {
        for (version in 1..2) {
            val (input, instance, context) = fixture(version, false)
            val changes = LegacyBodyAlgorithm().changes(input, instance, context) {}
            val output = changes.apply(input)
            assertEquals(LegacyBodyMotion.parameters.map { it.raw }, changes.parameters.added.map { it.id })
            assertEquals(input.deformers.map { it.id }, output.deformers.map { it.id })
            assertEquals(3, changes.deformers.updated.size)
            assertTrue(changes.deformers.added.isEmpty())
            assertSame(input.meshes.last(), output.meshes.last())
            assertEquals(input.meshes.first(), output.meshes.first())
            assertEquals(changes.apply(input), LegacyBodyAlgorithm().evaluate(input, instance, context) {})
        }
    }

    @Test fun rejectsMissingHeadPitchAndUnresolvedScaffold() {
        val (input, instance, context) = fixture(2, true)
        val error = assertFailsWith<IllegalArgumentException> { LegacyBodyAlgorithm().changes(input, instance, context) {} }
        assertTrue(error.message.orEmpty().contains("head pitch grids"))
        assertFailsWith<IllegalArgumentException> {
            LegacyBodyAlgorithm().changes(input, instance, context.withUpstream(emptyList())) {}
        }
    }

    @Test fun sharedBodyCannotAffectAnUnselectedScaffoldSibling() {
        val (input, instance, context) = fixture(2, false)
        val provider = context.upstream.single()
        val expanded = provider.copy(targets = provider.targets + ModifierTarget("mesh", "unrelated"))
        assertFailsWith<IllegalArgumentException> {
            LegacyBodyAlgorithm().changes(input, instance, context.withUpstream(listOf(expanded))) {}
        }
    }

    private fun fixture(version: Int, face: Boolean): Triple<io.github.psd2live.format.model.RigIR, RigModifierInstance, ModifierAlgorithmContext> {
        val id = DrawableId(target.id)
        val slot = if (face) ScaffoldParts.Slot.FACE else ScaffoldParts.Slot.BODY
        val tag = if (face) "FACE" else "TOPWEAR"
        val frame = if (face) head.faceFrame else body
        val raw = ScaffoldMesh.RawMesh(floatArrayOf(80f, 80f, 120f, 85f, 90f, 110f), floatArrayOf(.1f, .1f, .9f, .1f, .1f, .9f), intArrayOf(0, 1, 2))
        val art = Drawable(id, id.raw, null, BlendMode.Normal, emptyList(), DrawableMesh(raw.rigPositions, raw.atlasUvs, raw.triangles), null)
        val other = art.copy(id = DrawableId("unrelated"))
        val ordinary = PuppetModel(emptyList(), emptyList(), emptyList(), listOf(art, other),
            listOf(OrgChild.Drawable(art.id), OrgChild.Drawable(other.id)), null).withDerivedRenderRoot()
        val scaffold = ScaffoldAlgorithm.captureSource(ordinary, mapOf(id.raw to id.raw),
            ScaffoldPartsContext.capture(listOf(ScaffoldParts.Material(id, slot, 0)), label),
            ScaffoldFramesContext.capture(body, null, head, label),
            ScaffoldBranchesContext.capture(listOf(ScaffoldBranchesContext.Layer(id.raw, id.raw, tag, Side.NONE, frame, slot)),
                body, null, head, label, label),
            ScaffoldMeshContext.capture(listOf(ScaffoldMeshContext.Material(id.raw, id.raw, raw, ScaffoldMesh.Geometry.Zero))))
        val provider = RigModifierInstance("structure", "rig.scaffold", version, listOf(target))
        val input = ScaffoldAlgorithm().evaluate(PuppetIr.toIr(ordinary), provider, scaffold) {}
        val bodyContext = LegacyBodyInputs.capture(scaffold, TorsoFrame(100f, 121f, 230f, 52f), BodyMotionTuning(),
            LegacyBodyMotion.parameters.associate { it.raw to it.raw }, mapOf("ParamGroupBody" to "Body"))
            .withUpstream(listOf(ModifierUpstreamCapability("rig.scaffold", "rig.scaffold", version, listOf(target), provider.id)))
        val instance = RigModifierInstance("body", "body/legacy", 1, listOf(target),
            config = buildJsonObject { put("scaffold", provider.id); put("strength", .8f) })
        return Triple(input, instance, bodyContext)
    }
}
