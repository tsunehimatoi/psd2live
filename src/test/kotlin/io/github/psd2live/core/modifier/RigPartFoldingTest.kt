package io.github.psd2live.core.modifier

import kotlinx.serialization.json.*
import kotlin.test.*

class RigPartFoldingTest {
    private val target = ModifierTarget("mesh", "eye")
    private val base = RigModifierInstance("base", "eye/fitted", 1, listOf(target), config = buildJsonObject { put("scaffold", "s") })
    private fun child(id: String = "wide", type: String = "eye.wide", enabled: Boolean = true) =
        RigModifierInstance(id, type, 1, listOf(target), enabled, buildJsonObject { put("scaffold", "s"); put("base", "base") })

    @Test fun repeatedTypesAndDisabledChildrenStayExpanded() {
        for (entries in listOf(listOf(base, child(), child("second")), listOf(base, child(enabled = false)))) {
            assertEquals(entries, RigPartFolding.foldAll(entries))
        }
    }

    @Test fun interleavedWritersAndReversedOrderStayExpanded() {
        val intervening = RigModifierInstance("writer", "custom/writer", 1, listOf(target))
        for (entries in listOf(listOf(base, intervening, child()), listOf(base, child("smile", "eye.smile"), child()))) {
            assertEquals(entries, RigPartFolding.foldAll(entries))
        }
    }

    @Test fun historicalVersionsAndExactPackageReferencesStayExpanded() {
        val pinned = base.copy(plugin = ModifierPluginReference("builtin.test", "1.0.0", "abc"))
        for (entries in listOf(listOf(base.copy(version = 3), child()), listOf(pinned, child()), listOf(base, child().copy(version = 2)))) {
            assertEquals(entries, RigPartFolding.foldAll(entries))
        }
    }

    @Test fun externalDependencyAndParameterProviderReferencesKeepChildrenVisible() {
        for (consumer in listOf(
            child("consumer", "custom/consumer").copy(config = buildJsonObject { putJsonObject("dependencies") { put("eye.wide", "wide") } }),
            child("consumer", "custom/consumer").copy(parameterProviders = mapOf("P" to "wide")),
        )) {
            val entries = listOf(base, child(), consumer)
            assertEquals(entries, RigPartFolding.foldAll(entries))
        }
    }

    @Test fun futureDependencyAndTargetsOutsideBaseStayExpanded() {
        for (sub in listOf(child().copy(config = buildJsonObject {
            put("scaffold", "s"); put("base", "base"); putJsonObject("dependencies") { put("face/legacy", "future") }
        }), child().copy(targets = listOf(ModifierTarget("mesh", "other"))))) {
            val entries = listOf(base, sub)
            assertEquals(entries, RigPartFolding.foldAll(entries))
        }
    }

    @Test fun ambiguousScaffoldMatchingDoesNotAbsorbMouthChildren() {
        val mouth = base.copy(type = "mouth/fitted")
        val second = mouth.copy(id = "second")
        val outline = child().copy(type = "mouth.outline", config = buildJsonObject { put("scaffold", "s") })
        val result = RigPartFolding.foldAll(listOf(mouth, second, outline))
        assertEquals(outline, result.last())
        assertTrue(result.dropLast(1).none { "outline" in it.config })
    }

    @Test fun relatedProvidersMustSupplyTheRequestedCapabilityAndTargetScope() {
        val brow = base.copy(type = "brow/fitted")
        val lid = child(type = "brow.lid-coupling").copy(config = buildJsonObject {
            put("scaffold", "s"); put("base", "base")
            putJsonObject("dependencies") { put("eye/fitted", "provider") }
        })
        val eye = base.copy(id = "provider")
        for (provider in listOf(eye.copy(type = "custom/unrelated"), eye.copy(enabled = false),
            eye.copy(targets = listOf(ModifierTarget("mesh", "elsewhere"))))) {
            assertTrue(RigPartFolding.foldBrow(listOf(provider, brow, lid)).isEmpty())
        }
        assertEquals(1, RigPartFolding.foldBrow(listOf(eye, brow, lid)).size)
    }

    @Test fun safeFoldPreservesIdentityAliasesAndEveryConfigurationField() {
        val sub = child().copy(outputAliases = mapOf("shape" to "existing-shape"), config = buildJsonObject {
            put("scaffold", "s"); put("base", "base")
            putJsonObject("values") { put("amount", 37f) }
            putJsonObject("tuning") { put("factor", 2f) }
            put("physicsEnabled", false); putJsonObject("physics") { put("rule", "retained") }
            put("color", 0x123456)
        })
        val folded = RigPartFolding.foldAll(listOf(base, sub)).single()
        val restored = Json.decodeFromString<RigModifierInstance>(Json.encodeToString(folded))
        assertEquals(sub, ModifierPartConfig.child(restored, "eye.wide"))
        assertEquals(sub, RigSourceBindingState.expandPart(restored).last())
        assertEquals(sub.outputId("shape"), ModifierPartConfig.child(restored, "eye.wide").outputId("shape"))
        assertEquals(sub.outputId("another"), ModifierPartConfig.child(restored, "eye.wide").outputId("another"))
    }

    @Test fun baseStrengthGroupAndAliasesSurviveFolding() {
        val body = base.copy(type = "body/legacy", presetGroup = "chosen", outputAliases = mapOf("body" to "kept"),
            config = buildJsonObject { put("scaffold", "s"); put("strength", .37f) })
        val folded = RigPartFolding.foldAll(listOf(body)).single()
        assertEquals(body.config["strength"], folded.config["strength"])
        assertEquals(body.presetGroup, folded.presetGroup)
        assertEquals(body.outputAliases, folded.outputAliases)
    }
}
