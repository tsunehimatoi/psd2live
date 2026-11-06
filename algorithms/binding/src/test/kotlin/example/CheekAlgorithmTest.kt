package example

import io.github.psd2live.algorithms.binding.CheekAlgorithm
import io.github.psd2live.algorithms.binding.CheekSource
import io.github.psd2live.algorithms.binding.MoodBinding
import io.github.psd2live.core.modifier.ModifierTarget
import io.github.psd2live.core.modifier.RigModifierInstance
import io.github.psd2live.format.model.*
import io.github.psd2live.targets.cubism.PuppetIr
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.ParameterId
import kotlin.test.*

class CheekAlgorithmTest {
    private val cheek = Parameter("ParamCheek", "Cheek", 0f, 1f, 0f)
    private val gloom = Parameter("ParamGloom", "Gloom", 0f, 1f, 0f)
    private val color = Channel.MULTIPLY_COLOR
    private val face = Mesh("face", "Face", null, geometry = null, offsets = null)
    private val blush = face.copy(id = "blush")
    private val ear = face.copy(id = "ear")
    private val highlight = face.copy(id = "highlight")
    private val source = RigIR(Canvas(100f, 100f), listOf(gloom), meshes = listOf(face, blush, ear, highlight))
    private val instance = RigModifierInstance("cheek", "expression.cheek", 1, source.meshes.map { ModifierTarget("mesh", it.id) },
        config = buildJsonObject { putJsonObject("values") { put("strength", 150f) } },
        outputAliases = mapOf("parameter-folder:ParamGroupFace" to "legacy-face-folder"))

    @Test fun combinedTracksMatchTheLegacyUnloweredMoodPath() {
        val order = listOf(cheek, gloom).mapIndexed { i, p -> ParameterId(p.id) to i }.toMap()
        val kernel = MoodBinding(carrierName = { _, _ -> error("No geometry to lower") })
        val canonical = PuppetIr.toPuppet(source)
        val old = kernel.evaluate(canonical, emptyList(), order, emptySet(),
            legacyInfluences(0f), cheek = false, gloom = true).source
        val current = source.copy(meshes = old.drawables.map(PuppetIr::meshToIr))
        val desired = kernel.evaluate(old, PuppetIr.toPuppet(RigIR(source.canvas, listOf(cheek))).parameters,
            order, emptySet(), legacyInfluences(150f), cheek = true, gloom = true).model
        val capture = CheekSource(true, "Cheek", "Face", mapOf(
            face.id to CheekSource.Layer(CheekSource.LayerKind.FACE, priorGloom = 200f),
            blush.id to CheekSource.Layer(CheekSource.LayerKind.BLUSH, priorGloom = 200f),
            ear.id to CheekSource.Layer(CheekSource.LayerKind.EARS, priorGloom = 200f),
            highlight.id to CheekSource.Layer(CheekSource.LayerKind.OTHER, priorGloom = 200f),
        ))
        val changes = CheekAlgorithm().changes(current, instance, capture.context()) {}
        assertEquals(listOf(cheek), changes.parameters.added)
        assertEquals(listOf(face.id, blush.id, ear.id), changes.meshes.updated.map { it.before.id })
        val result = changes.apply(current)
        assertEquals(desired.drawables.map { PuppetIr.meshToIr(it).channels }, result.meshes.map { it.channels })
        assertSame(current.meshes.last(), result.meshes.last())
        val folder = result.parameterTree.filterIsInstance<ParameterNode.Group>().single()
        assertEquals("legacy-face-folder", folder.id)
        assertEquals(listOf(ParameterNode.Param(cheek.id)), folder.children)
    }

    @Test fun retainsAuthoredMultiplyResidualAndReusesCompatibleParameter() {
        val mesh = face.copy(multiply = Rgb(.8f, .9f, .7f), opacity = .4f)
        val authored = cheek.copy(name = "My blush")
        val input = source.copy(parameters = listOf(authored), meshes = listOf(mesh))
        val capture = CheekSource(true, "Cheek", "Face", mapOf(face.id to CheekSource.Layer(CheekSource.LayerKind.FACE)))
        val changes = CheekAlgorithm().changes(input, instance.copy(targets = listOf(instance.targets.first())), capture.context()) {}
        assertEquals(listOf(authored), changes.parameters.reused)
        assertNull(changes.parameterTree)
        val actual = changes.apply(input).meshes.single()
        assertEquals(mesh.copy(channels = actual.channels), actual)
        val grid = actual.channels.getValue(color)
        assertEquals(listOf(KeyAxis(cheek.id, Floats.values(0f, 1f))), grid.axes)
        val green = (grid.cells.last().form as ChannelValue.Color).color.green
        assertEquals(.81f, green, .00001f)
    }

    private fun legacyInfluences(strength: Float) = mapOf(
        DrawableId(face.id) to MoodBinding.Influence(.06f * strength / 100f, 2f, false),
        DrawableId(blush.id) to MoodBinding.Influence(.3f * strength / 100f, 2f, false),
        DrawableId(ear.id) to MoodBinding.Influence(.05f * strength / 100f, 2f, false),
        DrawableId(highlight.id) to MoodBinding.Influence(0f, 0f, true),
    )
}
