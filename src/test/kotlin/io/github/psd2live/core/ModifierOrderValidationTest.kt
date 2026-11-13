package io.github.psd2live.core

import io.github.psd2live.core.modifier.*
import kotlinx.serialization.json.*
import kotlin.test.*

class ModifierOrderValidationTest {
    private val target = ModifierTarget("part", "body")
    private val other = ModifierTarget("part", "other")

    private fun entry(id: String, type: String, enabled: Boolean = true, targets: List<ModifierTarget> = listOf(target)) =
        RigModifierInstance(id, type, 1, targets, enabled)

    private fun definition(type: String, requires: Set<String> = emptySet(), provides: Set<String> = emptySet(),
                           providers: (RigModifierInstance) -> Map<String, String> = { emptyMap() }) =
        RigModifierDefinition(type, 1, requires, provides = provides, providers = providers,
            plugin = ModifierAlgorithmPlugin { rig, _, _ -> rig })

    private val engine = RigModifierEvaluator(RigModifierRegistry(listOf(
        definition("consumer", requires = setOf("feature")),
        definition("provider", provides = setOf("feature")),
    )))

    @Test fun disabledFutureParameterProviderIsAllowedButEnabledOneIsRejected() {
        val consumer = entry("a", "provider").copy(parameterProviders = mapOf("P" to "b"))
        val failure = assertFailsWith<IllegalArgumentException> {
            engine.validateOrder(RigModifierStack(listOf(consumer, entry("b", "provider"))))
        }
        assertEquals("a must follow b for parameter P", failure.message)
        engine.validateOrder(RigModifierStack(listOf(consumer, entry("b", "provider", enabled = false))))
    }

    @Test fun enabledMissingPluginStillOccupiesItsExplicitProviderPosition() {
        val missing = ModifierPluginReference("example.missing", "1.0.0", "digest")
        val consumer = entry("a", "provider").copy(parameterProviders = mapOf("P" to "b"))
        val stack = RigModifierStack(listOf(consumer, entry("b", "provider").copy(plugin = missing)))
        val failure = assertFailsWith<IllegalArgumentException> { engine.validateOrder(stack) }
        assertEquals("a must follow b for parameter P", failure.message)
        engine.validateOrder(RigModifierStack(listOf(consumer, entry("b", "provider", enabled = false).copy(plugin = missing))))
    }

    @Test fun overrideLookupPreservesMissingDisabledAndSelfProviderBehavior() {
        val missing = ModifierPluginReference("example.missing", "1.0.0", "digest")
        val consumer = entry("a", "provider")
        val future = entry("b", "provider").copy(plugin = missing)
        val commands = listOf(buildJsonObject { putJsonArray("providers") { add("b") } })
        val stack = RigModifierStack(listOf(consumer, future)).withOverrides("a", commands)
        assertEquals("a must follow b for its generated-content override",
            assertFailsWith<IllegalArgumentException> { engine.validateOrder(stack) }.message)
        engine.validateOrder(stack.copy(instances = listOf(consumer, future.copy(enabled = false))))
        engine.validateOrder(RigModifierStack(listOf(consumer)).withOverrides("a", commands))
        engine.validateOrder(RigModifierStack(listOf(consumer)).withOverrides("a", listOf(
            buildJsonObject { putJsonArray("providers") { add("a") } })))
    }

    @Test fun parameterProviderCannotBeTheConsumerItself() {
        val stack = RigModifierStack(listOf(entry("a", "provider").copy(parameterProviders = mapOf("P" to "a"))))
        val failure = assertFailsWith<IllegalArgumentException> { engine.validateOrder(stack) }
        assertEquals("a must follow a for parameter P", failure.message)
    }

    @Test fun parameterProviderErrorsPrecedeOverrideAndLaterInstanceErrors() {
        val commands = listOf(buildJsonObject { putJsonArray("providers") { add("c") } })
        val both = RigModifierStack(listOf(
            entry("a", "consumer").copy(parameterProviders = mapOf("P" to "c")),
            entry("b", "provider"),
            entry("c", "provider"),
        )).withOverrides("a", commands)
        assertEquals("a must follow c for parameter P",
            assertFailsWith<IllegalArgumentException> { engine.validateOrder(both) }.message)

        val overrideOnly = both.copy(instances = both.instances.map {
            if (it.id == "a") it.copy(parameterProviders = emptyMap()) else it
        })
        assertEquals("a must follow c for its generated-content override",
            assertFailsWith<IllegalArgumentException> { engine.validateOrder(overrideOnly) }.message)

        val acrossInstances = RigModifierStack(listOf(
            entry("a", "provider"),
            entry("b", "provider").copy(parameterProviders = mapOf("P" to "c")),
            entry("c", "provider"),
        )).withOverrides("a", commands)
        assertEquals("a must follow c for its generated-content override",
            assertFailsWith<IllegalArgumentException> { engine.validateOrder(acrossInstances) }.message)
    }

    @Test fun appliedCapabilitiesSatisfyRequirementChecksBeforeALaterProvider() {
        val stack = RigModifierStack(listOf(entry("a", "consumer"), entry("b", "provider")))
        assertEquals("a must follow feature (feature) on body",
            assertFailsWith<IllegalArgumentException> { engine.validateOrder(stack) }.message)
        engine.validateOrder(stack.copy(appliedCapabilities = listOf(ModifierCapability("provider", 1, listOf(target)))))
        engine.validateOrder(stack.copy(appliedCapabilities = listOf(
            ModifierCapability("retired", 1, listOf(target), provides = setOf("feature")))))
    }

    @Test fun providersResolveOncePerValidationAcrossRequirementsAndTargets() {
        var calls = 0
        val requirements = setOf("feature", "detail")
        val targets = listOf(target, other, ModifierTarget("part", "third"))
        val selected = definition("consumer", requires = requirements, providers = {
            calls++
            requirements.associateWith { "source" }
        })
        val scoped = RigModifierEvaluator(RigModifierRegistry(listOf(selected,
            definition("provider", provides = requirements))))
        val stack = RigModifierStack(listOf(
            entry("source", "provider", targets = targets),
            entry("a", "consumer", targets = targets),
        ))

        scoped.validateOrder(stack)
        assertEquals(1, calls)
        scoped.validateOrder(stack)
        assertEquals(2, calls)
    }

    @Test fun providerSelectionRefreshesForTheSameInstanceIdAfterConfigChanges() {
        var calls = 0
        val selected = definition("consumer", requires = setOf("feature"), providers = { instance ->
            calls++
            mapOf("feature" to instance.config.getValue("provider").jsonPrimitive.content)
        })
        val scoped = RigModifierEvaluator(RigModifierRegistry(listOf(selected,
            definition("provider", provides = setOf("feature")))))
        val consumer = entry("a", "consumer").copy(config = buildJsonObject { put("provider", "earlier") })
        val earlier = entry("earlier", "provider")
        val later = entry("later", "provider")
        val stack = RigModifierStack(listOf(earlier, consumer, later))

        scoped.validateOrder(stack)
        assertEquals(1, calls)
        val changed = consumer.copy(config = buildJsonObject { put("provider", "later") })
        assertEquals("a must follow later (feature) on body", assertFailsWith<IllegalArgumentException> {
            scoped.validateOrder(stack.copy(instances = listOf(earlier, changed, later)))
        }.message)
        assertEquals(2, calls)
        scoped.validateOrder(stack)
        assertEquals(3, calls)
    }

    @Test fun disabledAndRequirementFreeInstancesDoNotResolveProviders() {
        val selected = definition("consumer", requires = setOf("feature"), providers = {
            error("Disabled consumers must not resolve providers")
        })
        val provider = definition("provider", provides = setOf("feature"), providers = {
            error("Instances without requirements must not resolve providers")
        })
        val scoped = RigModifierEvaluator(RigModifierRegistry(listOf(selected, provider)))

        scoped.validateOrder(RigModifierStack(listOf(entry("a", "consumer", enabled = false), entry("source", "provider"))))
    }

    @Test fun explicitProviderAndTargetScopeRestrictCapabilityOrderChecks() {
        val selected = definition("consumer", requires = setOf("feature"), providers = { mapOf("feature" to "source") })
        val scoped = RigModifierEvaluator(RigModifierRegistry(listOf(selected, definition("provider", provides = setOf("feature")))))
        val expected = RigModifierStack(listOf(entry("a", "consumer"), entry("source", "provider")))
        assertEquals("a must follow source (feature) on body",
            assertFailsWith<IllegalArgumentException> { scoped.validateOrder(expected) }.message)
        scoped.validateOrder(RigModifierStack(listOf(entry("a", "consumer"), entry("unrelated", "provider"))))
        scoped.validateOrder(RigModifierStack(listOf(entry("a", "consumer"), entry("source", "provider", targets = listOf(other)))))
        scoped.validateOrder(RigModifierStack(listOf(entry("a", "consumer"), entry("source", "provider", enabled = false))))
        scoped.validateOrder(RigModifierStack(listOf(entry("source", "provider"), entry("a", "consumer"), entry("unrelated", "provider"))))
        assertEquals("a must follow source (feature) on body", assertFailsWith<IllegalArgumentException> {
            scoped.validateOrder(expected.copy(appliedCapabilities = listOf(
                ModifierCapability("provider", 1, listOf(other), id = "source"))))
        }.message)
        scoped.validateOrder(expected.copy(appliedCapabilities = listOf(
            ModifierCapability("provider", 1, listOf(target), id = "source"))))
    }
}
