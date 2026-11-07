package io.github.psd2live.core.modifier

import io.github.psd2live.algorithms.binding.LidFadeInputs
import io.github.psd2live.format.model.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.util.UUID
import java.util.prefs.Preferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class LidFadeWorkerTest {
    @Test fun installedLidFadeRetainsAuthoredBlendOpacityRelativeToTheFade() {
        val artifact = assertNotNull(BundledAlgorithmArtifacts.find("eye.lid-fade", 3))
        val open = "ParamEyeLOpen"
        val blink = "ParamLidPose"
        val targets = listOf(ModifierTarget("mesh", "lid"))
        val config = buildJsonObject { put("scaffold", "scaffold"); put("base", "eye") }
        val instance = RigModifierInstance("fade", "eye.lid-fade", 3, targets, config = config, plugin = artifact.reference)
        val context = LidFadeInputs.encode(mapOf("lid" to LidFadeInputs(open, .2f, .5f))).withUpstream(listOf(
            ModifierUpstreamCapability("rig.scaffold", "rig.scaffold", 2, targets, "scaffold"),
            ModifierUpstreamCapability("eye/fitted", "eye/fitted", 5, targets, "eye"),
            ModifierUpstreamCapability("eye.wide", "eye.wide", 3, targets, "wide", config),
        ))
        val shape = MeshShape(Floats.values(.1f, .2f, 0f, 0f, 0f, 0f), 1f, .375f, Rgb.White, Rgb.Black)
        val binding = BlendBinding(blink, Floats.values(0f, 1f), 0, listOf(null, shape))
        val mesh = Mesh("lid", "lid", null,
            geometry = MeshGeometry(Floats.values(0f, 0f, 1f, 0f, 0f, 1f),
                Floats.values(0f, 0f, 1f, 0f, 0f, 1f), Ints.values(0, 1, 2)),
            offsets = null, opacity = .5f, shapes = listOf(binding))
        val input = RigIR(Canvas(1f, 1f), listOf(Parameter(open, open, 0f, 2f, 1f),
            Parameter(blink, blink, 0f, 1f, 0f)), meshes = listOf(mesh))
        val directory = Files.createTempDirectory("lid-fade-worker-")
        val preferences = Preferences.userRoot().node("lid-fade-worker/" + UUID.randomUUID())
        val store = ModifierPluginStore(directory.resolve("installed"), preferences)
        try {
            BundledAlgorithmArtifacts.retain(store, listOf(artifact))
            val installed = store.snapshot().single()
            val result = ModifierPluginWorkers.evaluate(installed, installed.manifest.algorithms.single(),
                input, instance, {}, store, context = context).meshes.single()
            val channel = result.channels.getValue(Channel.OPACITY)
            assertEquals(Floats.values(0f, .2f + .15f, .75f, 1f, 2f), channel.axes.single().keys)
            assertEquals(listOf(0f, 0f, 1f, 1f, 1f), channel.cells.map { (it.form as ChannelValue.Scalar).value })
            assertEquals(mesh.copy(shapes = listOf(binding.copy(shapes = listOf(null, shape.copy(opacity = .875f))))),
                result.copy(channels = emptyMap()))
        } finally {
            ModifierPluginWorkers.stop(artifact.reference)
            preferences.removeNode()
            ModifierPluginStore.deleteTree(directory)
        }
    }
}
