package io.github.psd2live.core

import io.github.psd2live.algorithms.binding.CheekAlgorithm
import io.github.psd2live.algorithms.binding.CheekSource
import io.github.psd2live.algorithms.binding.MoodBinding
import io.github.psd2live.core.modifier.ModifierPluginStore
import io.github.psd2live.core.modifier.ModifierPluginWorkers
import io.github.psd2live.core.modifier.ModifierTarget
import io.github.psd2live.core.modifier.RigModifierInstance
import io.github.psd2live.format.model.Channel
import io.github.psd2live.bridge.PuppetIr
import org.umamo.runtime.model.*
import java.nio.file.Files
import java.util.UUID
import java.util.prefs.Preferences
import kotlin.test.*

class CheekPluginParityTest {
    @Test fun aCheekAxisCanMoveAnExistingShapeFromTheMeshToACarrier() {
        val angle = ParameterId("ParamAngleX")
        val axes = listOf(
            KeyformAxis(angle, FloatArray(9) { it.toFloat() }),
            KeyformAxis(ParameterId("ParamAxisY"), FloatArray(8) { it.toFloat() }),
            KeyformAxis(ParameterId("ParamAxisZ"), floatArrayOf(0f, 1f)),
        )
        val vertices = floatArrayOf(0f, 0f, 0f, 10f, 10f, 0f)
        val cells = axes[0].keys.indices.flatMap { x ->
            axes[1].keys.indices.flatMap { y ->
                axes[2].keys.indices.map { z ->
                    KeyformCell(intArrayOf(x, y, z), MeshDeltaForm(FloatArray(vertices.size)))
                }
            }
        }
        val id = DrawableId("face")
        val face = Drawable(id, "Face", null, BlendMode.Normal, emptyList(),
            DrawableMesh(vertices, floatArrayOf(0f, 0f, 0f, 1f, 1f, 0f), intArrayOf(0, 1, 2)),
            KeyformGrid(axes, cells), blendShapes = listOf(BlendShapeBinding(angle, floatArrayOf(0f, 8f), 0,
                listOf(null, MeshForm(floatArrayOf(0f, 0f, 1f, 0f, 0f, 0f))))))
        val source = PuppetModel(
            axes.map { Parameter(it.parameterId, it.parameterId.raw, it.keys.first(), it.keys.last(), 0f) },
            emptyList(), emptyList(), listOf(face), listOf(OrgChild.Drawable(id)), null,
            canvasWidth = 100f, canvasHeight = 100f,
        )
        val context = CheekSource(true, "Cheek", "Face", mapOf(id.raw to CheekSource.Layer(CheekSource.LayerKind.FACE)))
        val cheek = context.parameter()
        val kernel = MoodBinding(carrierName = { _, _ -> "Face turn" })
        val influence = mapOf(id to MoodBinding.Influence(.06f, 0f, false))
        val input = PuppetIr.toIr(kernel.evaluate(source, emptyList(), emptyMap(), emptySet(), influence, cheek = false, gloom = false).model)
        val legacy = PuppetIr.toIr(kernel.evaluate(source, listOf(Parameter(ParameterId(cheek.id), cheek.name, 0f, 1f, 0f)),
            emptyMap(), emptySet(), influence, cheek = true, gloom = false).model)
        val instance = RigModifierInstance("cheek-test", "expression.cheek", 1, listOf(ModifierTarget("mesh", id.raw)))
        val candidate = CheekAlgorithm().changes(input, instance, context.context()) {}.apply(input)

        assertEquals(legacy.meshes.single().channels[Channel.MULTIPLY_COLOR], candidate.meshes.single().channels[Channel.MULTIPLY_COLOR])
        assertTrue(candidate.deformers.isEmpty())
        assertEquals(1, legacy.deformers.size)
        assertNotEquals(legacy.meshes.single().parent, candidate.meshes.single().parent)
        assertNotEquals(legacy.meshes.single().offsets, candidate.meshes.single().offsets)

        val packageDirectory = System.getenv("PSD2LIVE_CHEEK_PACKAGE") ?: return
        val root = Files.createTempDirectory("cheek-package-")
        val preferences = Preferences.userRoot().node("psd2live/cheek-test/${UUID.randomUUID()}")
        val store = ModifierPluginStore(root, preferences)
        try {
            val installed = store.install(packageDirectory)
            val algorithm = installed.manifest.algorithms.single()
            val transported = ModifierPluginWorkers.evaluate(installed, algorithm, input,
                instance.copy(type = algorithm.type, plugin = installed.reference), {}, store,
                context = context.context(), declaredParameters = listOf(cheek))
            assertEquals(candidate, transported)
        } finally {
            store.snapshot().forEach { ModifierPluginWorkers.stop(it.reference) }
            ModifierPluginStore.deleteTree(root)
            preferences.removeNode()
        }
    }
}
