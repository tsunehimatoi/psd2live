package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.model.Floats
import io.github.psd2live.format.model.Ints
import io.github.psd2live.project.*
import io.github.psd2live.targets.cubism.PuppetIr
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.*

class WorkspaceBuildPlanIntegrationTest {
    @TempDir lateinit var temp: Path
    private val owners = mutableSetOf<ModifierBuildCacheOwner>()
    private fun retain(model: RigPreviewModel) = model.also { it.modifierBuildOwner?.let(owners::add) }
    private suspend fun measure(builder: WorkspacePreviewBuilder, document: WorkspaceDocument, current: RigPreviewModel) =
        RigBuildProfile.measureSuspending(RigBuildProfile.Operation("build-plan-test", "base", "candidate", "split", 2, 12)) {
            retain(builder.build(document, current))
        }

    private data class Fixture(val document: WorkspaceDocument, val model: RigPreviewModel, val split: RigModifierInstance)
    private suspend fun fixture(builder: WorkspacePreviewBuilder): Fixture {
        val source = SyntheticProject(temp.resolve("source"), 2, 12, 1).tree.head().snapshot
        val config = PipelineConfig(atlasSize = 64, meshSpacing = 8,
            rigEdits = RigEditOverlay(modifierStack = RigModifierStack().toJson()))
        var document = source.copy(settings = WorkspaceSettingsCodec.encode(config), rigEdits = config.rigEdits)
        val initial = retain(builder.build(document))
        val ir = RigIrCompiler.compile(initial)
        val first = ir.meshes.first()
        val geometry = first.geometry!!.copy(
            positions = Floats.values(0f, 0f, 3f, 0f, 0f, 3f, 5f, 5f, 8f, 5f, 5f, 8f),
            uvs = Floats.values(0f, 0f, .3f, 0f, 0f, .3f, .5f, .5f, .8f, .5f, .5f, .8f),
            indices = Ints.values(0, 1, 2, 3, 4, 5))
        val puppet = PuppetIr.toPuppet(ir.copy(meshes = listOf(first.copy(geometry = geometry, offsets = null, shapes = emptyList())) + ir.meshes.drop(1)))
        val authored = initial.authored.copy(rig = initial.authored.rig.copy(puppet = puppet))
        val prefix = RigModifierInstance("prefix", "mesh.split", 1, listOf(ModifierTarget("mesh", ir.meshes.last().id)))
        document = document.copy(rigEdits = document.rigEdits.copy(
            authoringJournal = listOf(RigCheckpoint.encode(authored, initial.sources.bindingKey!!)),
            modifierStack = RigModifierStack(listOf(prefix)).toJson()))
        return Fixture(document, retain(builder.build(document)),
            RigModifierInstance("split", "mesh.split", 1, listOf(ModifierTarget("mesh", first.id))))
    }

    private fun appended(f: Fixture) = f.document.copy(rigEdits = f.document.rigEdits.copy(
        modifierStack = RigModifierStack.fromJson(f.document.rigEdits.modifierStack!!).let {
            it.copy(instances = it.instances + f.split)
        }.toJson()))

    private fun assertEquivalent(expected: RigPreviewModel, actual: RigPreviewModel) {
        assertEquals(RigIrCompiler.compile(expected), RigIrCompiler.compile(actual))
        assertEquals(expected.rig.modifierReport, actual.rig.modifierReport)
        assertEquals(expected.rig.layerIdByDrawableId, actual.rig.layerIdByDrawableId)
        assertEquals(expected.rig.pageByDrawableId, actual.rig.pageByDrawableId)
        assertEquals(expected.rig.sourceBoundsByDrawableId, actual.rig.sourceBoundsByDrawableId)
        assertEquals(expected.atlas.placementByLayerId, actual.atlas.placementByLayerId)
    }

    @Test fun actualSplitReusesOnlyTheApprovedPrefixAndMatchesDisabledCaches() = runBlocking {
        val plans = mutableListOf<WorkspaceBuildPlan>()
        val enabled = WorkspacePreviewBuilder(observeBuildPlan = plans::add)
        val disabled = WorkspacePreviewBuilder(modifierCachesEnabled = false)
        val recording = RigBuildProfile.recording
        RigBuildProfile.recording = true
        try {
            val f = fixture(enabled)
            val snapshot = f.model.modifierEvaluation
            val document = appended(f)
            val hot = measure(enabled, document, f.model)
            assertEquals(WorkspaceBuildPilot.APPEND_MESH_SPLIT, plans.last().pilot)
            assertEquals(1L, hot.stages["modifier author input cache hits"]?.second)
            assertEquals(1L, hot.stages["modifier cache hits"]?.second)
            assertEquals(1L, hot.gauges["build plan invalidated modifiers"])
            val cold = measure(disabled, document, f.model)
            assertNull(cold.stages["modifier author input cache hits"])
            assertNull(cold.stages["modifier cache hits"])
            assertEquivalent(cold.value, hot.value)
            val result = RigIrCompiler.compile(hot.value)
            assertFalse(result.meshes.any { it.id == f.split.targets.single().id })
            assertTrue((0..1).all { n -> result.meshes.any { it.id == f.split.outputId("split:${f.split.targets.single().id}:$n") } })
            assertSame(snapshot, f.model.modifierEvaluation)
            assertSame(f.model.modifierBuildOwner, hot.value.modifierBuildOwner)
            assertNotSame(f.model.modifierBuildOwner, cold.value.modifierBuildOwner)
            val repeated = measure(disabled, document, cold.value)
            assertNull(repeated.stages["modifier cache hits"])
            assertNotSame(cold.value.modifierBuildOwner, repeated.value.modifierBuildOwner)
            assertEquivalent(cold.value, repeated.value)
        } finally { RigBuildProfile.recording = recording; owners.forEach { it.close() } }
    }

    @Test fun nonPilotAndUnstampedModelsKeepExistingSemanticCacheChecks() = runBlocking {
        val plans = mutableListOf<WorkspaceBuildPlan>()
        val builder = WorkspacePreviewBuilder(observeBuildPlan = plans::add)
        val recording = RigBuildProfile.recording
        RigBuildProfile.recording = true
        try {
            val f = fixture(builder)
            val changed = appended(f).let { it.copy(rigEdits = it.rigEdits.copy(
                parameterEdits = listOf(RigParameterEdit("ordinary", "Ordinary", -1f, 1f, 0f, created = true)))) }
            val rebuilt = measure(builder, changed, f.model)
            assertTrue(plans.last().fullyInvalidated)
            assertNull(rebuilt.stages["modifier author input cache hits"])
            assertNull(rebuilt.stages["modifier cache hits"])
            assertSame(f.model.modifierBuildOwner, rebuilt.value.modifierBuildOwner)
            assertEquals(WorkspaceBuildRoute.EXISTING_PIPELINE, plans.last().route)
            assertNull(rebuilt.gauges["build plan invalidated modifiers"])
            assertEquivalent(retain(WorkspacePreviewBuilder(modifierCachesEnabled = false).build(changed)), rebuilt.value)
            val unstamped = f.model.copy().retainModifierCachesFrom(f.model).also {
                it.modifierEvaluation = it.modifierEvaluation?.copy(buildInputs = null)
            }
            val replay = measure(builder, appended(f), unstamped)
            assertTrue(plans.last().stages.all { it.reason == WorkspaceBuildReason.FIRST_BUILD })
            assertEquals(WorkspaceBuildRoute.EXISTING_PIPELINE, plans.last().route)
            assertEquals(1L, replay.stages["modifier author input cache hits"]?.second)
            assertEquivalent(retain(WorkspacePreviewBuilder(modifierCachesEnabled = false).build(appended(f))), replay.value)
        } finally { RigBuildProfile.recording = recording; owners.forEach { it.close() } }
    }

    @Test fun failedSplitDoesNotReplacePublishedSnapshotOrPoisonTheNextCandidate() = runBlocking {
        val builder = WorkspacePreviewBuilder()
        try {
            val f = fixture(builder)
            val runtime = WorkspaceRuntime<RigPreviewModel>({ builder.build(it) })
            runtime.install(runtime.state.value.state, "split", f.document, f.model)
            val captured = runtime.capture()
            val snapshot = f.model.modifierEvaluation
            val other = RigIrCompiler.compile(f.model).meshes.last().id
            val badSplit = f.split.copy(config = buildJsonObject { putJsonArray("pieceIds") { add(other); add("collision-piece") } })
            val bad = appended(f).copy(rigEdits = f.document.rigEdits.copy(modifierStack =
                RigModifierStack.fromJson(f.document.rigEdits.modifierStack!!).let {
                    it.copy(instances = it.instances + badSplit)
                }.toJson()))
            assertFailsWith<IllegalArgumentException> { builder.build(bad, f.model) }
            assertEquals(captured, runtime.capture())
            assertSame(snapshot, f.model.modifierEvaluation)
            assertFalse(f.model.modifierBuildOwner!!.isClosed)
            val valid = retain(builder.build(appended(f), f.model))
            assertEquivalent(retain(WorkspacePreviewBuilder(modifierCachesEnabled = false).build(appended(f))), valid)
        } finally { owners.forEach { it.close() } }
    }

    @Test fun emptyStackParameterRenameKeepsExistingGeometryCachesAndMatchesColdBuild() = runBlocking {
        val plans = mutableListOf<WorkspaceBuildPlan>()
        val builder = WorkspacePreviewBuilder(observeBuildPlan = plans::add)
        val recording = RigBuildProfile.recording
        RigBuildProfile.recording = true
        try {
            val source = SyntheticProject(temp.resolve("rename"), 1, 12, 1).tree.head().snapshot
            val parameter = RigParameterEdit("ordinary", "Before", -1f, 1f, 0f, created = true)
            val config = PipelineConfig(atlasSize = 64, meshSpacing = 8, rigEdits = RigEditOverlay(
                parameterEdits = listOf(parameter), modifierStack = RigModifierStack().toJson()))
            val document = source.copy(settings = WorkspaceSettingsCodec.encode(config), rigEdits = config.rigEdits)
            val before = retain(builder.build(document))
            val stages = before.modifierBuildOwner!!.meshCache().stages
            val hits = stages.hits(RigStageCache.MESH)
            val renamed = document.copy(rigEdits = document.rigEdits.copy(parameterEdits = listOf(parameter.copy(name = "After"))))
            val hot = measure(builder, renamed, before)
            assertEquals(WorkspaceBuildRoute.EXISTING_PIPELINE, plans.last().route)
            assertEquals(1L, hot.stages["build plan route: existing_pipeline"]?.second)
            assertNull(hot.gauges["build plan invalidated modifiers"])
            assertSame(before.modifierBuildOwner, hot.value.modifierBuildOwner)
            assertTrue(stages.hits(RigStageCache.MESH) > hits)
            assertNull(hot.stages["mesh cache: adaptive mesh (miss)"])
            val cold = measure(WorkspacePreviewBuilder(modifierCachesEnabled = false), renamed, before)
            assertNotNull(cold.stages["mesh cache: adaptive mesh (miss)"])
            assertEquivalent(cold.value, hot.value)
            assertEquals("After", RigIrCompiler.compile(hot.value).parameters.single { it.id == "ordinary" }.name)
        } finally { RigBuildProfile.recording = recording; owners.forEach { it.close() } }
    }
}
