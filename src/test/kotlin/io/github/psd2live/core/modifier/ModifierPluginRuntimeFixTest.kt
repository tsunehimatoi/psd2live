package io.github.psd2live.core.modifier

import io.github.psd2live.format.model.*
import kotlinx.serialization.json.buildJsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.prefs.Preferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModifierPluginRuntimeFixTest {
    @Test
    fun thirdPartyPluginVersionAboveOneIsNotNormalizedToOne() {
        val thirdPartyRef = ModifierPluginReference("thirdparty.eye", "1.0.0", "digest-tp")
        assertEquals(2, BuiltinModifierPlugins.resolveVersion("eye/fitted", 2, thirdPartyRef))
        assertFalse(BuiltinModifierPlugins.isHistoricalBuiltinReference("eye/fitted", 2, thirdPartyRef))
        assertEquals(1, BuiltinModifierPlugins.resolveVersion("eye/fitted", 2, ModifierPluginReference("builtin", "1.0.0", "builtin")))
        assertEquals(1, BuiltinModifierPlugins.resolveVersion("eye/fitted", 2, null))

        // Verify installed third-party plugin with protocol 2 + version 2 works via ModifierPluginCatalog & Workers.
        val dir = Files.createTempDirectory("plugin-ver-test-")
        val prefs = Preferences.userRoot().node("plugin-ver-test/" + UUID.randomUUID())
        val store = ModifierPluginStore(dir.resolve("installed"), prefs)
        var installedRef: ModifierPluginReference? = null
        try {
            val zip = createSyntheticPluginZip(dir, "thirdparty.eye", "thirdparty.eye/fitted", 2, protocol = 2)
            val installed = store.install(zip.toString())
            installedRef = installed.reference
            val instance = RigModifierInstance(
                id = "inst-1",
                type = "thirdparty.eye/fitted",
                version = 2,
                targets = listOf(ModifierTarget("mesh", "m1")),
                config = buildJsonObject { },
                plugin = installed.reference,
            )
            assertTrue(store.resolution(installed.reference).available)
            val result = ModifierPluginWorkers.evaluate(
                installed,
                installed.manifest.algorithms.single(),
                syntheticWorkerRig(),
                instance,
                {},
                store,
            )
            assertEquals(0.5f, result.meshes.single().opacity)
        } finally {
            installedRef?.let(ModifierPluginWorkers::stop)
            prefs.removeNode()
            ModifierPluginStore.deleteTree(dir)
        }
    }

    @Test
    fun sharedWorkerUsesRefcountingAndProtocol2AvoidsTempFiles() {
        val dir = Files.createTempDirectory("plugin-shared-worker-")
        val prefs = Preferences.userRoot().node("plugin-shared-worker/" + UUID.randomUUID())
        val store = ModifierPluginStore(dir.resolve("installed"), prefs)
        var ref1: ModifierPluginReference? = null
        var ref2: ModifierPluginReference? = null
        try {
            val installed1 = store.install(createSyntheticPluginZip(dir, "shared.one", "shared.one/op", 1, protocol = 2).toString())
            val installed2 = store.install(createSyntheticPluginZip(dir, "shared.two", "shared.two/op", 1, protocol = 2).toString())
            ref1 = installed1.reference
            ref2 = installed2.reference
            val rig = syntheticWorkerRig()
            val inst1 = RigModifierInstance("i1", "shared.one/op", 1, listOf(ModifierTarget("mesh", "m1")), plugin = installed1.reference)
            val inst2 = RigModifierInstance("i2", "shared.two/op", 1, listOf(ModifierTarget("mesh", "m1")), plugin = installed2.reference)

            val out1 = ModifierPluginWorkers.evaluate(installed1, installed1.manifest.algorithms.single(), rig, inst1, {}, store)
            val out2 = ModifierPluginWorkers.evaluate(installed2, installed2.manifest.algorithms.single(), rig, inst2, {}, store)
            assertEquals(0.25f, out1.meshes.single().opacity)
            assertEquals(0.25f, out2.meshes.single().opacity)

            // Uninstalling package 1 stops only ref1; pooled worker stays alive for ref2 and pkg1 directory can be deleted.
            store.uninstall(installed1.reference)
            assertFalse(Files.exists(installed1.directory))
            val out2After = ModifierPluginWorkers.evaluate(installed2, installed2.manifest.algorithms.single(), rig, inst2, {}, store)
            assertEquals(0.25f, out2After.meshes.single().opacity)

            store.uninstall(installed2.reference)
            assertFalse(Files.exists(installed2.directory))
        } finally {
            ref1?.let(ModifierPluginWorkers::stop)
            ref2?.let(ModifierPluginWorkers::stop)
            prefs.removeNode()
            ModifierPluginStore.deleteTree(dir)
        }
    }
}
