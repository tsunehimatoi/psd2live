package io.github.psd2live.core.modifier

import io.github.psd2live.core.RigTuning
import kotlinx.serialization.json.*
import kotlin.test.*

class RigSourceBindingStateTest {
    private val target = ModifierTarget("mesh", "mesh")

    private fun instance(id: String, type: String, version: Int = 1, config: JsonObject = JsonObject(emptyMap())) =
        RigModifierInstance(id, type, version, listOf(target), config = config)

    private fun config(strength: Float? = null, tuningId: String? = null, tuningValue: Float = 0f) = buildJsonObject {
        strength?.let { put("strength", it) }
        tuningId?.let { id -> putJsonObject("tuning") { put(id, tuningValue) } }
    }

    @Test fun duplicateTypesAndAreasKeepTheLastInstance() {
        val first = instance("body-first", "body/legacy", config = config(strength = 0.5f))
        val second = instance("body-second", "body/legacy", config = config(strength = 2f))
        val legacyFace = instance("face-legacy", "face/legacy")
        val fittedFace = instance("face-fitted", RigLegacyBindings.FITTED_FACE)
        val state = RigSourceBindingState(listOf(first, second, legacyFace, fittedFace))

        assertSame(second, state.byType["body/legacy"])
        assertSame(second, state.byArea["body"])
        assertSame(fittedFace, state.byArea["face"])
        assertEquals(2f, state.bodyStrength)
    }

    @Test fun tuningFollowsSpecsOrderInsteadOfInputOrder() {
        // face/fitted follows face/legacy in RigLegacyBindings.specs, so it wins even when listed first.
        val fitted = instance("face-fitted", RigLegacyBindings.FITTED_FACE, config = config(tuningId = "headShellTurn", tuningValue = 4f))
        val legacy = instance("face-legacy", "face/legacy", config = config(tuningId = "headShellTurn", tuningValue = 1f))

        assertEquals(4f, RigSourceBindingState(listOf(fitted, legacy)).tuning.headShellTurn)
        assertEquals(4f, RigSourceBindingState(listOf(legacy, fitted)).tuning.headShellTurn)
    }

    @Test fun fittedFaceStrengthTakesPriorityAndMissingValuesDefault() {
        val legacy = instance("face-legacy", "face/legacy", config = config(strength = 0.25f))
        val fitted = instance("face-fitted", RigLegacyBindings.FITTED_FACE, config = config(strength = 2f))

        assertEquals(2f, RigSourceBindingState(listOf(legacy, fitted)).headStrength)
        assertEquals(2f, RigSourceBindingState(listOf(fitted, legacy)).headStrength)
        assertEquals(0.25f, RigSourceBindingState(listOf(legacy)).headStrength)

        val empty = RigSourceBindingState(emptyList())
        assertEquals(1f, empty.bodyStrength)
        assertEquals(1f, empty.headStrength)
        assertEquals(RigTuning(), empty.tuning)
        assertEquals(emptyMap(), empty.byType)
        assertEquals(emptyMap(), empty.byArea)
    }

    @Test fun templateKeyIndexDoesNotResolveTuningOrStrengthEarly() {
        val invalid = instance("body", "body/legacy", config = config(strength = 5f, tuningId = "unknown"))
        val state = RigSourceBindingState(listOf(invalid))
        assertSame(invalid, state.byType["body/legacy"])
        assertSame(invalid, state.byArea["body"])
        assertFailsWith<IllegalArgumentException> { state.tuning }
        assertFailsWith<IllegalArgumentException> { state.bodyStrength }
    }

    @Test fun sharedStateMatchesLegacyInlineResolution() {
        val preceding = listOf(
            instance("body", "body/legacy", config = config(strength = 0.75f, tuningId = "turnDegrees", tuningValue = 7f)),
            instance("face", RigLegacyBindings.FITTED_FACE, config = config(strength = 1.25f, tuningId = "headShellTurn", tuningValue = 3f)),
            instance("eye", "eye/fitted", version = 5, config = config(tuningId = "gazeX", tuningValue = 9f)),
            instance("smile", "eye.smile", version = 3),
            instance("outline", "mouth.outline", version = 7),
        )
        val state = RigSourceBindingState(preceding)

        val byType = preceding.associateBy { it.type }
        val expectedTuning = RigLegacyBindings.specs.fold(RigTuning()) { current, spec ->
            byType[spec.type]?.let { RigLegacyBindings.tuning(it, current) } ?: current
        }
        assertEquals(expectedTuning, state.tuning)
        assertEquals(byType, state.byType)
        assertEquals(setOf("body", "face", "eye"), state.byArea.keys)
        assertSame(byType[RigLegacyBindings.FITTED_FACE], state.byArea["face"])
        assertEquals(0.75f, state.bodyStrength)
        assertEquals(1.25f, state.headStrength)
    }
}
