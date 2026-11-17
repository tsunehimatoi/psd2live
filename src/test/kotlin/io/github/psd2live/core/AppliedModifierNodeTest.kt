package io.github.psd2live.core

import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.model.*
import kotlinx.serialization.json.*
import kotlin.test.*

class AppliedModifierNodeTest {
    private val target = ModifierTarget("part", "body")
    private val input = RigIR(Canvas(100f, 100f), emptyList(), parts = listOf(Part("body", "Body", emptyList())))
    private fun definition(type: String, transform: (Int) -> Int) = RigModifierDefinition(type, 1,
        composableKinds = setOf("part"), plugin = ModifierAlgorithmPlugin { rig, _, _ ->
            rig.copy(parts = rig.parts.map { if (it.id == "body") it.copy(drawOrder = transform(it.drawOrder)) else it })
        })
    private val add = definition("add") { it + 10 }
    private fun instance(id: String, type: String) = RigModifierInstance(id, type, 1, listOf(target))

    @Test fun applyingTheMiddleStagePreservesOrderAndNeverRunsItsAlgorithmAgain() {
        var calls = 0
        val multiply = definition("multiply") { calls++; it * 2 }
        val engine = RigModifierEvaluator(RigModifierRegistry(listOf(add, multiply)))
        val stack = RigModifierStack(listOf(instance("upstream", "add"), instance("middle", "multiply"), instance("downstream", "add")))
        val expected = engine.evaluate(input, stack).rig
        val applied = engine.applySelected(input, stack, "middle")
        val count = calls
        val reopened = RigModifierStack.fromJson(Json.parseToJsonElement(applied.toJson().toString()).jsonObject)
        assertEquals(expected, engine.evaluate(input, reopened).rig)
        assertEquals(count, calls)
        assertEquals(listOf("upstream", "middle", "downstream"), reopened.instances.map { it.id })
        val withoutAlgorithm = RigModifierEvaluator(RigModifierRegistry(listOf(add)))
        assertEquals(expected, withoutAlgorithm.evaluate(input, reopened).rig)
        val fullyApplied = withoutAlgorithm.applyThrough(input, reopened, "downstream")
        assertEquals(expected, fullyApplied.authored)
        assertTrue(fullyApplied.stack.appliedNodes.isEmpty())
    }

    @Test fun incompatibleUpstreamEditsFailInsteadOfErasingTheNewInput() {
        val engine = RigModifierEvaluator(RigModifierRegistry(listOf(add)))
        val applied = engine.applySelected(input, RigModifierStack(listOf(instance("a", "add"))), "a")
        val changed = input.copy(parts = input.parts.map { it.copy(drawOrder = 42) })
        assertFailsWith<IllegalArgumentException> { engine.evaluate(changed, applied) }
        assertFailsWith<IllegalArgumentException> { applied.update("a") { it.copy(enabled = false) } }
    }

    @Test fun appliedPackageIsRetainedForHistoryButNotRequiredToEvaluate() {
        val reference = ModifierPluginReference("example.add", "1.0.0", "content")
        val source = instance("a", "add")
        val engine = RigModifierEvaluator(RigModifierRegistry(listOf(add)))
        val captured = engine.applySelected(input, RigModifierStack(listOf(source)), "a")
        // Reopen persisted materialized content after its package is no longer installed.
        val applied = captured.copy(instances = listOf(source.copy(plugin = reference)),
            appliedNodes = captured.appliedNodes.mapValues { (_, node) ->
                node.copy(capability = node.capability.copy(plugin = reference))
            })
        assertEquals(listOf(reference), applied.pluginReferences())
        assertTrue(applied.runtimePluginReferences().isEmpty())
        val withoutPackage = RigModifierEvaluator(RigModifierRegistry(emptyList()))
        assertEquals(engine.evaluate(input, applied).rig, withoutPackage.evaluate(input, applied).rig)
        val prefix = withoutPackage.applyThrough(input, applied, "a").stack
        assertEquals(listOf(reference), prefix.pluginReferences())
        assertTrue(prefix.runtimePluginReferences().isEmpty())
        val legacy = prefix.copy(appliedCapabilities = prefix.appliedCapabilities.map { it.copy(provides = null) })
        assertEquals(listOf(reference), legacy.runtimePluginReferences())
    }

    @Test fun arbitraryPluginClipsKeepRawChannelsAcrossRenameDisableAndCurveEditing() {
        val source = input.copy(parameters = listOf(Parameter("P", "P", 0f, 1f, 0f)))
        val curve = Curve("P", .2f, 0f, listOf(CurveSegment.Bezier(.3f, .1f, .6f, .8f, 1f, 1f),
            CurveSegment.Stepped(1.2f, .5f)), fadeIn = .2f, fadeOut = .3f)
        val clip = Clip("generated", "Generated", "Group", "generated", 2f, 30f, false,
            fadeOut = .7f, curves = listOf(curve), events = listOf(ClipEvent(.5f, "event")),
            targetCurves = listOf(TargetCurve(CurveTarget.PartOpacity("body"), 0f, 1f,
                listOf(CurveSegment.Linear(2f, .5f)))))
        val other = clip.copy(id = "other", file = "other")
        val definition = RigModifierDefinition("custom.clips", 1, plugin = ModifierAlgorithmPlugin { rig, _, _ ->
            rig.copy(clips = listOf(clip, other))
        })
        val engine = RigModifierEvaluator(RigModifierRegistry(listOf(definition)))
        val applied = engine.applySelected(source, RigModifierStack(listOf(instance("plugin", "custom.clips"))), "plugin")
        var overlay = RigEditOverlay(modifierStack = applied.toJson())
        val evaluator = RigModifierEvaluator(RigModifierRegistry(emptyList()))
        fun output() = evaluator.evaluate(source, RigModifierStack.fromJson(overlay.modifierStack!!)).rig.clips
        assertEquals(listOf(clip, other), output())
        assertEquals(2, MotionCatalog.entries(overlay).size)
        overlay = MotionCatalog.replacing(overlay, MotionCatalog.entries(overlay).map {
            if (it.id == clip.id) it.copy(name = "Renamed", enabled = false) else it
        })
        assertEquals(listOf(other), output())
        overlay = MotionCatalog.replacing(overlay, MotionCatalog.entries(overlay).map { it.copy(enabled = true) })
        assertEquals(listOf(clip.copy(name = "Renamed"), other), output())
        overlay = MotionCatalog.replacing(overlay, MotionCatalog.entries(overlay).map {
            if (it.id == clip.id) it.copy(curves = listOf(MotionCurve("P", listOf(MotionKey(0f, .25f))))) else it
        })
        val edited = output().single { it.id == clip.id }
        assertEquals(clip.targetCurves, edited.targetCurves)
        assertEquals(curve.fadeIn, edited.curves.single().fadeIn)
        assertEquals(curve.fadeOut, edited.curves.single().fadeOut)
        assertNull(edited.fadeIn)
        assertEquals(.7f, edited.fadeOut)
        assertEquals(other, output().single { it.id == other.id })
        overlay = MotionCatalog.replacing(overlay, MotionCatalog.entries(overlay).filterNot { it.id == clip.id })
        assertEquals(listOf(other), output())
        assertTrue(overlay.motionClips.isEmpty())
    }

    @Test fun ordinaryMaterializedMotionRenamesReferencesWithoutRebuildingUntouchedCurves() {
        val curve = Curve("old", .2f, .3f, listOf(CurveSegment.Bezier(.3f, .4f, .8f, .7f, 1f, 1f)), .3f, .4f)
        val clip = Clip("clip", "Clip", "Group", "clip", 1f, 30f, false, curves = listOf(curve),
            targetCurves = listOf(TargetCurve(CurveTarget.PartOpacity("body"), 0f, 1f, emptyList())))
        val record = AppliedMotionRecords.record(clip)
        var overlay = RigEditOverlay(motionClips = listOf(record), motionSources = mapOf(record.id to AppliedMotionRecords.source(record, clip)))
        overlay = ModelSidecars.withIdRenamed(overlay, "parameter", "old", "new")
        overlay = ModelSidecars.withIdRenamed(overlay, "part", "body", "renamed")
        val compiled = AppliedMotionRecords.compile(overlay.motionClips.single(), overlay.motionSources.getValue(record.id))
        assertEquals(clip.copy(curves = listOf(curve.copy(parameter = "new")),
            targetCurves = listOf(clip.targetCurves.single().copy(target = CurveTarget.PartOpacity("renamed")))), compiled)
    }

    @Test fun objectPatchPreservesUnrelatedObjectsAndRejectsChangedWrittenObjects() {
        val after = input.copy(parts = input.parts.map { it.copy(drawOrder = 100) })
        val extra = Part("other", "Other", emptyList())
        val current = input.copy(parts = input.parts + extra)
        assertEquals(after.parts + extra, AppliedModifierNode.patch(input, after, current).parts)
        assertFailsWith<IllegalArgumentException> {
            AppliedModifierNode.patch(input, after, input.copy(parts = input.parts.map { it.copy(drawOrder = 75) }))
        }
    }

    @Test fun authoredCorrectionDoesNotBecomeAnAlgorithmWriterOnCachedOrAppliedReplay() {
        val passive = RigModifierDefinition("passive", 1, plugin = ModifierAlgorithmPlugin { rig, _, _ -> rig })
        val engine = RigModifierEvaluator(RigModifierRegistry(listOf(add, passive)), applyOverrides = { rig, _, _ ->
            ModifierOverrideResult(rig.copy(parts = rig.parts.map { it.copy(drawOrder = it.drawOrder + 1) }))
        })
        val first = RigModifierStack(listOf(instance("a", "add"), instance("correction", "passive")),
            overrides = mapOf("correction" to listOf(buildJsonObject { put("op", "test_correction") })))
        val cache = RigModifierEvaluationCache()
        assertEquals(input.parts.single().drawOrder + 11, engine.evaluate(input, first, cache).rig.parts.single().drawOrder)
        val extended = first.copy(instances = first.instances + instance("c", "add"))
        val cached = engine.evaluate(input, extended, cache).rig
        assertEquals(input.parts.single().drawOrder + 21, cached.parts.single().drawOrder)
        assertEquals(cached, engine.evaluate(input, extended).rig)
        val applied = engine.applySelected(input, extended, "correction")
        assertEquals(emptyList(), applied.appliedNodes.getValue("correction").algorithmWrites)
        assertEquals(cached, engine.evaluate(input, RigModifierStack.fromJson(applied.toJson())).rig)
    }
}
