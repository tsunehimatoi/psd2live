package example

import io.github.psd2live.algorithms.motion.MotionAlgorithm
import io.github.psd2live.core.*
import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.model.*
import kotlin.test.*

class MotionAlgorithmTest {
    private val input = RigIR(Canvas(64f, 64f), listOf(Parameter("angle", "Angle", -1f, 1f, 0f)))
    private val source = MotionClip("draft", "Turn", curves = listOf(MotionCurve("angle", listOf(
        MotionKey(0f, 0f, MotionInterpolation.LINEAR),
        MotionKey(.5f, 1f, MotionInterpolation.BEZIER),
        MotionKey(1f, 0f, MotionInterpolation.STEPPED),
        MotionKey(1.5f, -1f, MotionInterpolation.INVERSE_STEPPED), MotionKey(2f, 0f)))),
        events = listOf(MotionEvent(1f, "middle")), parameterRoles = mapOf("LipSync" to listOf("angle")))
    private fun instance(clip: MotionClip = source) = RigModifierInstance("motion1", "motion", 1,
        listOf(ModifierTarget("mesh", "body")), config = MotionData.toJson(clip),
        outputAliases = mapOf("motion" to "retained-clip"))

    @Test fun evaluatesAllInterpolationsWithStableIdentityAndEvents() {
        assertEquals(source, MotionData.fromJson(MotionData.toJson(source)))
        val result = MotionAlgorithm().evaluate(input, instance()) {}
        val clip = result.clips.single()
        assertEquals("retained-clip", clip.id)
        assertEquals(listOf("Linear", "Bezier", "Stepped", "InverseStepped"),
            clip.curves.single().segments.map { it::class.simpleName })
        assertEquals(listOf(ClipEvent(1f, "middle")), clip.events)
        assertEquals(listOf(ParameterRole("LipSync", listOf("angle"))), result.parameterRoles)
        assertTrue(input.clips.isEmpty())
        assertFailsWith<IllegalArgumentException> { MotionAlgorithm().evaluate(result, instance()) {} }
    }

    @Test fun missingParametersAndCancellationRejectCandidate() {
        assertFailsWith<IllegalArgumentException> { MotionAlgorithm().evaluate(input.copy(parameters = emptyList()), instance()) {} }
        assertFailsWith<java.util.concurrent.CancellationException> {
            MotionAlgorithm().evaluate(input, instance()) { throw java.util.concurrent.CancellationException() }
        }
        assertTrue(input.clips.isEmpty())
    }

    @Test fun sparseOutputObservesReferencedParametersAndChecksCollisions() {
        val existingRole = ParameterRole("EyeBlink", listOf("angle"))
        val base = input.copy(parameterRoles = listOf(existingRole))
        val view = ModifierEvaluationInput(base)
        val plugin = MotionAlgorithm()
        val changes = plugin.changes(view, instance()) {}
        assertEquals(setOf("motion", "relations"), changes.writeKinds())
        assertEquals(listOf(ModifierObjectContribution("motion", "retained-clip", ModifierChangeAction.ADD)), changes.contributions())
        assertEquals(setOf(ModifierReadKey("parameter", "angle"), ModifierReadKey("motion"),
            ModifierReadKey("parameterRoles")), view.reads().keys)

        val unrelated = base.copy(parameters = base.parameters + Parameter("other", "Other", 0f, 1f, 0f))
        assertTrue(view.reads().matches(unrelated))
        assertSame(unrelated.parameters, changes.apply(unrelated).parameters)
        assertFalse(view.reads().matches(base.copy(parameters = emptyList())))
        val output = changes.apply(base)
        assertEquals(listOf(ParameterRole("LipSync", listOf("angle")), existingRole), output.parameterRoles)

        val filenameCollision = base.copy(clips = listOf(output.clips.single().let {
            it.copy(id = "unrelated", file = it.file.uppercase())
        }))
        assertFalse(view.reads().matches(filenameCollision))
        assertFailsWith<IllegalArgumentException> { plugin.evaluate(filenameCollision, instance()) {} }
        val roleCollision = unrelated.copy(parameterRoles = listOf(ParameterRole("LipSync", listOf("other"))))
        assertFalse(view.reads().matches(roleCollision))
        assertFailsWith<IllegalArgumentException> { plugin.evaluate(roleCollision, instance()) {} }
        assertEquals(setOf("motion"), plugin.changes(ModifierEvaluationInput(base),
            instance(source.copy(parameterRoles = emptyMap()))) {}.writeKinds())
    }
}
