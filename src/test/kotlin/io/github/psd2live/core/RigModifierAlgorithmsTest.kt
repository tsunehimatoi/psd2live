package io.github.psd2live.core

import io.github.psd2live.core.modifier.*
import io.github.psd2live.core.sim.*
import io.github.psd2live.format.model.*
import io.github.psd2live.format.model.Parameter as IrParameter
import io.github.psd2live.targets.cubism.PuppetIr
import kotlinx.serialization.json.JsonObject
import kotlin.test.*

class RigModifierAlgorithmsTest {
    private val warpTarget = ModifierTarget("deformer", "warp")
    private val meshTarget = ModifierTarget("mesh", "mesh")
    private val rig = RigIR(Canvas(100f, 100f),
        parameters = listOf(IrParameter("ParamAngleX", "X", -30f, 30f, 0f), IrParameter("ParamOutput", "Output", -1f, 1f, 0f)),
        deformers = listOf(Deformer.Warp("warp", "Warp", null, null, 1, 1, true,
            KeyGrid.single(LatticePoints(Floats.of(floatArrayOf(0f, 0f, 100f, 0f, 0f, 100f, 100f, 100f)))))),
        meshes = listOf(Mesh("mesh", "Mesh", null, geometry = MeshGeometry(Floats.of(floatArrayOf(0f, 0f, 100f, 0f, 0f, 100f)),
            Floats.of(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f)), Ints.of(intArrayOf(0, 1, 2))), offsets = null)),
    )
    private val evaluator = RigModifierAlgorithms.evaluator()
    private fun entry(type: String, config: JsonObject, target: ModifierTarget = warpTarget, id: String = "m1") =
        RigModifierInstance(id, type, 1, listOf(target), config = config)
    private fun physics(output: String = "ParamOutput") = RigPhysicsEdit("old", "Pendulum",
        inputs = listOf(PhysicsInput("ParamAngleX")), outputs = listOf(PhysicsOutput(output)))

    @Test fun simulationInputIdentityIncludesFormsAndSidecarsBeyondTheLegacyMeshFingerprint() {
        val edit = RigSimEdit("template", "Simulation", SimKind.CLOTH, listOf("mesh"))
        val changed = rig.copy(meshes = rig.meshes.map { it.copy(offsets = KeyGrid.single(MeshOffsets(Floats.of(floatArrayOf(0f, 0f, .25f, 0f, 0f, 0f))))) })
        assertEquals(SimBake.fingerprint(PuppetIr.toPuppet(rig), edit), SimBake.fingerprint(PuppetIr.toPuppet(changed), edit))
        assertNotEquals(RigModifierInput.fingerprint(rig), RigModifierInput.fingerprint(changed))
        val sidecar = rig.copy(physics = Physics(listOf(Physics3Json.group(physics()))))
        assertNotEquals(RigModifierInput.fingerprint(rig), RigModifierInput.fingerprint(sidecar))
        assertEquals(RigModifierInput.fingerprint(rig), RigModifierInput.fingerprint(rig.copy()))
    }

    @Test fun swingUsesTheExistingGeneratorAndStableInstanceOutputs() {
        val config = RigSwingEdit.single("old", "Swing", SwingKind.LATERAL, listOf("warp"), listOf("OldParameter")).toJson()
        val instance = entry("swing", config)
        val stack = RigModifierStack(listOf(instance))
        val evaluated = evaluator.evaluate(rig, stack)
        val parameter = instance.outputId("swing:lateral:0")
        assertTrue(evaluated.rig.parameters.any { it.id == parameter })
        assertFalse(evaluated.rig.parameters.any { it.id == "OldParameter" })
        assertTrue((evaluated.rig.deformers.single() as Deformer.Warp).lattice!!.axes.any { it.parameter == parameter })
        assertEquals(parameter, evaluated.rig.physics.groups.single().outputs.single().parameter)
        assertEquals(evaluated.rig, evaluator.evaluate(rig, stack).rig)
        assertEquals(evaluated.rig, evaluator.applyThrough(rig, stack, instance.id).authored)
    }

    @Test fun meshSwingWrapperIdentityCannotMoveToAnotherSuffixAfterAnObjectCollision() {
        val config = RigSwingEdit.single("template", "Swing", SwingKind.LATERAL, listOf("mesh"), listOf("P")).toJson()
        val instance = entry("swing", config, meshTarget)
        val reserved = "Warp_Swing_${instance.outputId("swing")}"
        val collision = (rig.deformers.single() as Deformer.Warp).copy(id = reserved)
        assertFailsWith<IllegalArgumentException> {
            evaluator.evaluate(rig.copy(deformers = rig.deformers + collision), RigModifierStack(listOf(instance)))
        }
        assertEquals(1, rig.deformers.size)
        assertNull(rig.meshes.single().parent)
    }

    @Test fun physicsIsAnOrdinaryGroupAfterApplyingAndRetainsItsOutputReferences() {
        val instance = entry("physics", physics().toJson())
        val stack = RigModifierStack(listOf(instance))
        val result = evaluator.applyThrough(rig, stack, instance.id)
        assertTrue(result.stack.instances.isEmpty())
        val group = result.authored.physics.groups.single()
        assertEquals(instance.outputId("physics"), group.id)
        assertEquals("ParamOutput", group.outputs.single().parameter)
        val encoded = io.github.psd2live.targets.cubism.Cubism3Json.physics3(listOf(group), 30)!!
        val restored = Physics3Json.read(encoded).settings.single()
        assertEquals(group, Physics3Json.group(restored))
    }

    @Test fun physicsRejectsUnknownParametersAndCompetingOutputDrivers() {
        val missing = RigModifierStack(listOf(entry("physics", physics("Missing").toJson())))
        assertFailsWith<IllegalArgumentException> { evaluator.evaluate(rig, missing) }
        val a = entry("physics", physics().toJson())
        val b = entry("physics", physics().toJson(), id = "m2")
        assertFailsWith<IllegalArgumentException> { evaluator.evaluate(rig, RigModifierStack(listOf(a, b))) }
        assertTrue(rig.physics.groups.isEmpty())
    }

    @Test fun motionsBecomeOrdinaryClipsWithCurvesAndEvents() {
        val source = MotionClip("old", "Motion", curves = listOf(MotionCurve("ParamAngleX",
            listOf(MotionKey(0f, 0f), MotionKey(1f, 20f)))), events = listOf(MotionEvent(.5f, "event")), parameterRoles = mapOf("AngleX" to listOf("ParamAngleX")))
        val instance = entry("motion", MotionClips.toJson(source))
        val stack = RigModifierStack(listOf(instance))
        val applied = evaluator.applyThrough(rig, stack, instance.id)
        val clip = applied.authored.clips.single()
        assertEquals(instance.outputId("motion"), clip.id)
        assertTrue(clip.file.matches(Regex("[A-Za-z0-9_]+")))
        assertEquals(listOf(ClipEvent(.5f, "event")), clip.events)
        assertEquals("ParamAngleX", clip.curves.single().parameter)
        assertEquals(source.parameterRoles, applied.authored.parameterRoles.associate { it.role to it.parameters })
        assertEquals(source, MotionClips.fromJson(MotionClips.toJson(source)))
        assertEquals(evaluator.evaluate(rig, stack).rig, applied.authored)
    }

    @Test fun motionRolesValidateReferencesAndReportTheirContribution() {
        val source = MotionClip("old", "Renamed motion", curves = listOf(MotionCurve("ParamAngleX", listOf(MotionKey(0f, 0f)))),
            parameterRoles = mapOf("AngleX" to listOf("ParamAngleX")))
        val instance = entry("motion", MotionClips.toJson(source))
        val result = evaluator.evaluate(rig, RigModifierStack(listOf(instance)))
        assertTrue(result.entries.single().contributions.any { it.kind == "relations" && it.id == "parameter-roles" })
        val missing = instance.copy(config = MotionClips.toJson(source.copy(parameterRoles = mapOf("AngleX" to listOf("missing")))))
        assertFailsWith<IllegalArgumentException> { evaluator.evaluate(rig, RigModifierStack(listOf(missing))) }
        val renamed = ModelSidecars.withIdRenamed(RigEditOverlay.Empty.copy(motionClips = listOf(source)),
            "parameter", "ParamAngleX", "CustomAngle").motionClips.single()
        assertEquals(listOf("CustomAngle"), renamed.parameterRoles.getValue("AngleX"))
        assertTrue(MotionClips.withoutParameter(listOf(renamed), "CustomAngle").single().parameterRoles.isEmpty())
    }

    @Test fun puppetGeneratorsPreservePreviouslyAddedMotionAndPhysicsSidecars() {
        val source = MotionClip("old", "Motion", curves = listOf(MotionCurve("ParamAngleX", listOf(MotionKey(0f, 0f), MotionKey(1f, 10f)))))
        val motion = entry("motion", MotionClips.toJson(source))
        val pendulum = entry("physics", physics().toJson(), id = "m2")
        val swing = entry("swing", RigSwingEdit.single("old", "Swing", SwingKind.LATERAL, listOf("warp"), listOf("Old")).toJson(), id = "m3")
        val result = evaluator.evaluate(rig, RigModifierStack(listOf(motion, pendulum, swing)))
        assertEquals(1, result.rig.clips.size)
        assertEquals(2, result.rig.physics.groups.size)
        assertEquals(rig.textures, result.rig.textures)
    }

    @Test fun migratedMotionAndPhysicsKeepTheirObjectIdsThroughEvaluationAndApply() {
        val source = MotionClip("old-motion", "Motion", curves = listOf(MotionCurve("ParamAngleX",
            listOf(MotionKey(0f, 0f), MotionKey(1f, 10f)))))
        val motion = entry("motion", MotionClips.toJson(source)).copy(outputAliases = mapOf("motion" to source.id))
        val physics = physics()
        val pendulum = entry("physics", physics.toJson(), id = "m2").copy(outputAliases = mapOf("physics" to physics.id))
        val stack = RigModifierStack(listOf(motion, pendulum))
        val result = evaluator.evaluate(rig, stack).rig
        assertEquals(source.id, result.clips.single().id)
        assertEquals(physics.id, result.physics.groups.single().id)
        assertEquals(result, evaluator.applyThrough(rig, stack, pendulum.id).authored)
    }

    @Test fun twoSwingsComposeOnTheSameWarpWithoutSharingTheirGeneratedAxes() {
        val config = RigSwingEdit.single("old", "Swing", SwingKind.LATERAL, listOf("warp"), listOf("Old")).toJson()
        val a = entry("swing", config)
        val b = entry("swing", config, id = "m2")
        val stack = RigModifierStack(listOf(a, b))
        val result = evaluator.evaluate(rig, stack)
        val axes = (result.rig.deformers.single() as Deformer.Warp).lattice!!.axes.map { it.parameter }
        assertEquals(setOf(a.outputId("swing:lateral:0"), b.outputId("swing:lateral:0")), axes.toSet())
        assertEquals(2, result.rig.physics.groups.size)
        assertEquals(result.rig, evaluator.applyThrough(rig, stack, b.id).authored)
    }

    private fun simulation(baked: Boolean): RigSimEdit {
        val source = RigSimEdit("body", "Body", SimKind.CLOTH, listOf("mesh"), blendShapes = false)
        if (!baked) return source
        val axis = SimBakedAxis("BakedMode", floatArrayOf(-1f, 0f, 1f), mapOf("mesh" to listOf(
            floatArrayOf(0f, 0f, 0f, 0f, -10f, 0f), FloatArray(6), floatArrayOf(0f, 0f, 0f, 0f, 10f, 0f))))
        return source.copy(bake = SimBakeResult(SimBake.fingerprint(PuppetIr.toPuppet(rig), source), mapOf("mesh" to 3), emptyList(),
            listOf(SimBakedMode(axis, 10f, 1f))))
    }

    @Test fun simulationRequiresAMatchingBakeAndAppliesGeneratedBindingsInsteadOfAPreviewFrame() {
        val source = simulation(true)
        val instance = entry("simulation", source.toJson(), meshTarget)
        val stack = RigModifierStack(listOf(instance))
        val result = evaluator.evaluate(rig, stack)
        val axis = result.rig.meshes.single().offsets!!.axes.single()
        assertEquals(instance.outputId("simulation:BakedMode"), axis.parameter)
        assertEquals(3, axis.keys.size)
        assertEquals(result.rig, evaluator.applyThrough(rig, stack, instance.id).authored)
        assertFailsWith<IllegalArgumentException> { evaluator.evaluate(rig, RigModifierStack(listOf(entry("simulation", simulation(false).toJson(), meshTarget)))) }
        val stale = source.copy(bake = SimBakeResult("stale", mapOf("mesh" to 3), emptyList(), source.bake!!.modes))
        assertFailsWith<IllegalArgumentException> { evaluator.evaluate(rig, RigModifierStack(listOf(entry("simulation", stale.toJson(), meshTarget)))) }
    }
}
