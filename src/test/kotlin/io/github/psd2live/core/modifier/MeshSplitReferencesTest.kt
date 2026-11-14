package io.github.psd2live.core.modifier

import io.github.psd2live.algorithm.worker.AlgorithmResultValidation
import io.github.psd2live.algorithms.binding.MeshSplitAlgorithm
import io.github.psd2live.algorithms.binding.MeshSplitRejected
import io.github.psd2live.core.*
import io.github.psd2live.format.compile.RigIrBinary
import io.github.psd2live.format.model.*
import io.github.psd2live.project.*
import io.github.psd2live.bridge.PuppetIr
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.runtime.model.DrawableId
import java.nio.file.Path
import java.util.UUID
import java.util.prefs.Preferences
import kotlin.test.*

class MeshSplitReferencesTest {
    @TempDir lateinit var temp: Path

    private fun instance(vararg targets: String) = RigModifierInstance("split", "mesh.split", 1,
        targets.map { ModifierTarget("mesh", it) })
    private fun evaluator() = RigModifierEvaluator(RigModifierRegistry(listOf(MeshSplitAlgorithmPlugin.definition)))
    private fun split(input: RigIR, instance: RigModifierInstance = instance("islands")): RigIR =
        evaluator().evaluate(input, RigModifierStack(listOf(instance))).rig.also(AlgorithmResultValidation::validate)

    private fun built(rig: RigIR) = BuiltRig(PuppetIr.toPuppet(rig), mapOf("islands" to 1, "consumer" to 0),
        mapOf("islands" to Bounds(0f, 0f, 2f, 7f)), mapOf("islands" to "layer-0", "consumer" to "layer-0"),
        0f, 0f, 1f, 1f, emptyList())

    private fun fixture(): RigIR = meshSplitFixture()

    @Test fun masksTexturesPathsAndWeightsFollowTheSameVertexMapping() {
        val input = fixture()
        val result = split(input)
        val top = instance("islands").outputId("split:islands:0")
        val bottom = instance("islands").outputId("split:islands:1")
        val pieces = listOf(top, bottom)
        val consumer = result.meshes.single { it.id == "consumer" }
        assertEquals(pieces, consumer.maskedBy)
        assertTrue(consumer.invertMask)
        assertEquals(top, consumer.textureSource)
        assertEquals(pieces, result.parts.single().composite.maskedBy)
        assertEquals(pieces, (result.renderRoot.children.last() as RenderGroup).composite!!.maskedBy)
        assertEquals(mapOf(top to 1, bottom to 1, "consumer" to 0), result.textures.bindings)
        assertEquals(input.textures.tiles, result.textures.tiles)
        fun sampledPage(rig: RigIR, mesh: Mesh): Int {
            val source = rig.meshes.single { it.id == (mesh.textureSource ?: mesh.id) }
            return rig.textures.bindings[source.id] ?: source.page
        }
        assertEquals(sampledPage(input, input.meshes.last()), sampledPage(result, consumer))
        assertEquals(input.meshes.last().geometry, consumer.geometry)
        result.meshes.filter { it.id in pieces }.forEach {
            assertEquals("art", it.tile)
            assertEquals(input.meshes.first().channels, it.channels)
            assertEquals("authored", it.userData)
        }
        val path = result.authoring.paths.single()
        assertEquals(input.authoring.paths.single().copy(mesh = top,
            points = listOf(PathPoint(0, 1, 2, .2f, .3f, .5f, true),
                PathPoint(0, 1, 2, .3f, .5f, .2f), PathPoint(0, 1, 2, .5f, .2f, .3f))), path)
        assertEquals(listOf(top, bottom), result.authoring.vertexGroups.map { it.mesh })
        assertEquals(listOf(Floats.values(.4f, .5f, .6f), Floats.values(.1f, .2f, .3f)),
            result.authoring.vertexGroups.map { it.weights })
        assertEquals(pieces + "consumer", result.hitAreas.single().meshes)
        assertEquals(pieces, result.advanced.hitAreas.single().meshes)
        assertEquals(result, RigIrBinary.decode(RigIrBinary.encode(result)))
    }

    @Test fun glueRunsPreservePairOrderWeightsChannelsAndEvaluatedPositions() {
        val input = fixture()
        val result = split(input)
        assertEquals(3, result.glues.size)
        assertEquals("weld", result.glues.first().id)
        assertEquals(listOf(0, 0, 1), result.glues.flatMap { it.pairs }.map { it.a })
        assertEquals(input.glues.single().pairs.map { it.weightA }, result.glues.flatMap { it.pairs }.map { it.weightA })
        result.glues.forEach {
            assertEquals(input.glues.single().channels, it.channels)
            assertEquals(input.glues.single().intensity, it.intensity)
        }
        for (blend in listOf(0f, .5f, 1f)) {
            val values = mapOf(org.umamo.runtime.model.ParameterId("blend") to blend)
            val before = CpuDeformationEvaluator().evaluate(PuppetIr.toPuppet(input), values).worldPositions
            val after = CpuDeformationEvaluator().evaluate(PuppetIr.toPuppet(result), values).worldPositions
            assertContentEquals(before.getValue(DrawableId("consumer")), after.getValue(DrawableId("consumer")))
            val old = before.getValue(DrawableId("islands"))
            listOf(3..5, 0..2).forEachIndexed { piece, vertices ->
                val expected = vertices.flatMap { listOf(old[it * 2], old[it * 2 + 1]) }.toFloatArray()
                assertContentEquals(expected, after.getValue(DrawableId(instance("islands").outputId("split:islands:$piece"))))
            }
        }
    }

    @Test fun splittingBothGlueEndpointsAlsoMigratesMasksOnNewPiecesAndAnonymousGlue() {
        val input = fixture().let { it.copy(glues = it.glues.map { glue -> glue.copy(id = null) }) }
        val result = split(input, instance("islands", "consumer"))
        assertEquals(4, result.meshes.size)
        assertEquals(3, result.glues.size)
        assertTrue(result.glues.all { it.id != null })
        val sourceIds = (0..1).map { instance("islands").outputId("split:islands:$it") }
        result.meshes.filter { it.name.startsWith("Consumer") }.forEach {
            assertEquals(sourceIds, it.maskedBy)
            assertEquals(sourceIds.first(), it.textureSource)
        }
    }

    @Test fun consecutiveSplitModifiersCanMigrateTheSameGlueAndMaskRelations() {
        val first = instance("islands")
        val second = instance("consumer").copy(id = "split-consumer")
        val result = evaluator().evaluate(fixture(), RigModifierStack(listOf(first, second))).rig
        AlgorithmResultValidation.validate(result)
        assertEquals(4, result.meshes.size)
        val masks = (0..1).map { first.outputId("split:islands:$it") }
        result.meshes.filter { it.name.startsWith("Consumer") }.forEach {
            assertEquals(masks, it.maskedBy)
            assertEquals(masks.first(), it.textureSource)
        }
        assertEquals(3, result.glues.size)
        val consumers = (0..1).map { second.outputId("split:consumer:$it") }
        assertTrue(result.glues.all { it.meshB in consumers })
    }

    @Test fun invalidReferencesRejectTheEntireMultiTargetCandidateWithoutMutatingInput() {
        val base = fixture()
        val path = base.authoring.paths.single()
        val crossing = base.copy(authoring = base.authoring.copy(paths = listOf(path.copy(points = path.points +
            PathPoint(0, 1, 2, .2f, .3f, .5f)))))
        val bytes = RigIrBinary.encode(crossing)
        val failure = assertFailsWith<MeshSplitRejected> { split(crossing, instance("consumer", "islands")) }
        assertEquals("mesh.split.path_crosses_islands", failure.code)
        assertTrue(failure.subject.contains("path:path"))
        assertContentEquals(bytes, RigIrBinary.encode(crossing))

        val invalidGlue = base.copy(glues = listOf(base.glues.single().copy(pairs = listOf(GluePair(99, 0, .5f, .5f)))))
        val glueFailure = assertFailsWith<MeshSplitRejected> { split(invalidGlue) }
        assertEquals("mesh.split.glue_vertex_unmapped", glueFailure.code)
        assertTrue(glueFailure.subject.contains("glue:weld"))
        assertTrue(glueFailure.subject.contains("vertex:99"))
        val collider = base.copy(advanced = base.advanced.copy(colliders = listOf(ColliderIR("pin", false,
            mesh = "islands", radiusA = 1f))))
        assertEquals("mesh.split.collider_reference", assertFailsWith<MeshSplitRejected> { split(collider) }.code)
    }

    @Test fun collidingOutputIdsAndUnusedVerticesAreRejectedBeforeChangesAreReturned() {
        val input = fixture()
        val collision = instance("islands").copy(config = buildJsonObject {
            putJsonArray("pieceIds") { add("consumer"); add("other") }
        })
        assertEquals("mesh.split.mesh_id_collision", assertFailsWith<MeshSplitRejected> {
            MeshSplitAlgorithm().changes(input, collision, ModifierAlgorithmContext()) {}
        }.code)
        val unused = input.copy(meshes = input.meshes.map { mesh ->
            val geometry = mesh.geometry!!
            if (mesh.id != "islands") mesh else mesh.copy(offsets = null, shapes = emptyList(), geometry = geometry.copy(
                positions = Floats.of(geometry.positions.shared() + floatArrayOf(8f, 8f)),
                uvs = Floats.of(geometry.uvs.shared() + floatArrayOf(.8f, .8f))))
        }, authoring = Authoring())
        assertEquals("mesh.split.unassigned_vertex", assertFailsWith<MeshSplitRejected> { split(unused) }.code)
        val attached = unused.copy(glues = listOf(unused.glues.single().copy(pairs = listOf(GluePair(6, 0, .5f, .5f)))))
        assertEquals("mesh.split.glue_vertex_unmapped", assertFailsWith<MeshSplitRejected> { split(attached) }.code)
    }

    @Test fun generatedGeometryOverrideKeepsItsDeterministicPieceAcrossColdReplay() {
        val input = fixture()
        val output = split(input)
        val piece = output.meshes.single { it.id == instance("islands").outputId("split:islands:0") }
        val base = piece.offsets!!.cells.single().form.deltas.shared()
        val edited = base.copyOf().also { it[0] += 1f }
        val override = buildJsonObject {
            put("op", "generated_override"); put("generator", "modifier:split"); put("target", "mesh:${piece.id}")
            putJsonObject("key") {}
            put("base", JsonArray(base.map(::JsonPrimitive))); put("points", JsonArray(edited.map(::JsonPrimitive)))
        }
        repeat(2) {
            val replayed = GeneratedOverrides.apply(PuppetIr.toPuppet(split(RigIrBinary.decode(RigIrBinary.encode(input)))),
                Json.parseToJsonElement(override.toString()).jsonObject)
            assertTrue(replayed.issues.isEmpty())
            val mesh = replayed.model.drawables.single { it.id.raw == piece.id }
            assertContentEquals(edited, mesh.geometryGrid!!.cells.single().form.positionDeltas)
            assertEquals(output.glues, PuppetIr.toIr(replayed.model).glues)
        }
    }

    @Test fun advancedSimulationReferencesAreRejectedEvenWhenOnlyAnchorsUseTheSplitMesh() {
        val empty = Floats.Empty
        val zeros = Floats.wrap(FloatArray(6))
        val sim = SimulationIR("cloth", 30f, 1, 0f, 0f, 0f, 0f, 0f, listOf(SimTarget("consumer", 6)),
            SimParticles(Floats.wrap(FloatArray(6) { 1f }), zeros, zeros, zeros, zeros, zeros, zeros,
                listOf("islands", "", "", "", "", ""), Ints.wrap(IntArray(6))),
            SimStretch(Ints.Empty, Ints.Empty, empty, empty, empty),
            SimTriangles(Ints.Empty, Ints.Empty, Ints.Empty, empty), SimBends(Ints.Empty, Ints.Empty, empty),
            SimWelds(Ints.Empty, Ints.Empty, empty, empty, empty), SimLongRange(Ints.Empty, Ints.Empty, empty),
            emptyList(), emptyList(), emptyList())
        val input = fixture().copy(advanced = AdvancedIR(simulations = listOf(sim)))
        val failure = assertFailsWith<MeshSplitRejected> { split(input) }
        assertEquals("mesh.split.simulation_reference", failure.code)
        assertEquals("simulation:cloth", failure.subject)
    }

    @Test fun installedWorkerMatchesInProcessIncludingAllDeclaredReferenceWrites() {
        val artifact = assertNotNull(BundledAlgorithmArtifacts.find("mesh.split", 1))
        val preferences = Preferences.userRoot().node("mesh-split-test/${UUID.randomUUID()}")
        val store = ModifierPluginStore(temp.resolve("plugins"), preferences)
        try {
            BundledAlgorithmArtifacts.retain(store, listOf(artifact))
            val installed = store.snapshot().single()
            val input = fixture()
            val instance = instance("islands").copy(plugin = artifact.reference)
            val expected = split(input)
            repeat(2) {
                val actual = ModifierPluginWorkers.evaluate(installed, installed.manifest.algorithms.single(),
                    input, instance, {}, store)
                assertEquals(expected, actual)
                AlgorithmResultValidation.validate(actual)
            }
        } finally {
            ModifierPluginWorkers.stop(artifact.reference)
            preferences.removeNode()
        }
    }

    @Test fun ambiguousHostSourceBindingsRejectWithoutPublishingPartialMappings() {
        val input = fixture()
        val original = built(input)
        val result = split(input, instance("islands", "consumer"))
        val error = assertFailsWith<IllegalArgumentException> { original.withModifierMeshes(PuppetIr.toPuppet(result)) }
        assertTrue(error.message!!.startsWith("mesh.split.source_binding_ambiguous:"))
        assertEquals(setOf("islands", "consumer"), original.layerIdByDrawableId.keys)
        assertEquals(2, original.puppet.drawables.size)
    }

    @Test fun savedHistoryReplaysTheStackAndAppliedCheckpointRetainsSplitSourceMappings() = runBlocking<Unit> {
        val project = SyntheticProject(temp.resolve("history"), 1, 8, 1)
        val input = fixture()
        fun record(rig: BuiltRig) = RigCheckpoint.encode(AuthoredRig(rig, emptyList()), "synthetic")
        val original = built(input)
        val stack = RigModifierStack(listOf(instance("islands")))
        val before = project.tree.head().snapshot.copy(rigEdits = RigEditOverlay(
            authoringJournal = listOf(record(original)), modifierStack = RigModifierStack().toJson()))
        fun commit(document: WorkspaceDocument) {
            val revision = WorkspaceRevisions.of(document)
            project.tree.commit(project.tree.head().node.id, document, revision, revision, "Split", "user")
        }
        commit(before)
        val edited = before.copy(rigEdits = before.rigEdits.copy(modifierStack = stack.toJson()))
        commit(edited)
        val applied = evaluator().applyThrough(input, stack, "split")
        val mapped = original.withModifierMeshes(PuppetIr.toPuppet(applied.authored))
        val pieces = applied.authored.meshes.filter { it.id != "consumer" }
        pieces.forEach {
            assertEquals("layer-0", mapped.layerIdByDrawableId[it.id])
            assertEquals(1, mapped.pageByDrawableId[it.id])
            assertEquals(original.sourceBoundsByDrawableId["islands"], mapped.sourceBoundsByDrawableId[it.id])
        }
        commit(edited.copy(rigEdits = edited.rigEdits.copy(authoringJournal = listOf(record(mapped)),
            modifierStack = applied.stack.toJson())))
        project.store.persistHistory(SyntheticProject.PROJECT, project.tree.state())
        val archive = temp.resolve("split.psd2live")
        ProjectRepository(writeHeadCache = false).save(project.capture(), archive)
        ProjectRepository().open(archive).use { opened ->
            val snapshots = opened.history.selections().map { it.snapshot }.filter { it.rigEdits.authoringJournal.isNotEmpty() }
            assertEquals(3, snapshots.size)
            val expectedBase = PuppetIr.toIr(original.puppet)
            val expectedSplit = split(expectedBase)
            for (snapshot in snapshots) {
                val stored = RigCheckpoint.decode(snapshot.rigEdits.authoringJournal.single()).authored.rig
                val loadedStack = RigModifierStack.fromJson(snapshot.rigEdits.modifierStack!!)
                val replayed = evaluator().evaluate(PuppetIr.toIr(stored.puppet), loadedStack).rig
                AlgorithmResultValidation.validate(replayed)
                assertEquals(if (stored.puppet.drawables.any { it.id.raw == "islands" } && loadedStack.instances.isEmpty())
                    expectedBase else expectedSplit, replayed)
            }
            val stored = RigCheckpoint.decode(opened.history.head().snapshot.rigEdits.authoringJournal.single()).authored.rig
            pieces.forEach { assertEquals("layer-0", stored.layerIdByDrawableId[it.id]) }
        }
    }
}

internal fun meshSplitFixture(): RigIR {
    // Original indices deliberately run bottom-to-top, opposite the output island order.
    val geometry = MeshGeometry(Floats.values(0f, 5f, 2f, 5f, 0f, 7f, 0f, 0f, 2f, 0f, 0f, 2f),
        Floats.values(0f, .5f, .2f, .5f, 0f, .7f, 0f, 0f, .2f, 0f, 0f, .2f), Ints.values(0, 1, 2, 3, 4, 5))
    val channel = mapOf(Channel.OPACITY to KeyGrid.single<ChannelValue>(ChannelValue.Scalar(.6f)))
    val source = Mesh("islands", "Islands", null, geometry = geometry,
        offsets = KeyGrid.single(MeshOffsets(Floats.wrap(FloatArray(12) { it / 100f }))),
        channels = channel, page = 0, tile = "art", userData = "authored",
        shapes = listOf(BlendBinding("blend", Floats.values(0f, 1f), 0, listOf(null,
            MeshShape(Floats.wrap(FloatArray(12) { it / 10f }), 2f, .2f, Rgb.White, Rgb.Black)))))
    val consumer = source.copy(id = "consumer", name = "Consumer", maskedBy = listOf("islands"),
        invertMask = true, textureSource = "islands")
    val composite = Composite(maskedBy = listOf("islands"), invertMask = true, opacity = .7f)
    val part = Part("group", "Group", listOf(ChildRef.MeshRef("consumer")), groupMode = GroupMode.ISOLATED,
        composite = composite)
    return RigIR(Canvas(10f, 10f), listOf(Parameter("blend", "Blend", 0f, 1f, 0f, blend = true)),
        meshes = listOf(source, consumer), parts = listOf(part),
        rootChildren = listOf(ChildRef.MeshRef(source.id), ChildRef.PartRef(part.id)),
        renderRoot = RenderGroup(null, 500, listOf(RenderMesh(source.id),
            RenderGroup(part.id, 500, listOf(RenderMesh(consumer.id)), composite = composite))),
        glues = listOf(Glue("weld", source.id, consumer.id,
            listOf(GluePair(0, 0, .2f, .8f), GluePair(3, 0, .4f, .6f), GluePair(1, 0, .3f, .7f)),
            channels = mapOf(Channel.GLUE_INTENSITY to KeyGrid.single<ChannelValue>(ChannelValue.Scalar(.65f))), intensity = .8f)),
        textures = Textures(pages = listOf(TexturePage(16, 16), TexturePage(32, 32)),
            bindings = mapOf(source.id to 1, consumer.id to 0),
            tiles = listOf(TextureTile("art", "Art", 10, 10))),
        authoring = Authoring(paths = listOf(DeformPath("path", source.id,
            listOf(PathPoint(3, 4, 5, .2f, .3f, .5f, true),
                PathPoint(3, 4, 5, .3f, .5f, .2f), PathPoint(3, 4, 5, .5f, .2f, .3f)), 2f, .7f, true, 2)),
            vertexGroups = listOf(VertexGroup("pins", source.id, "pin", Floats.values(.1f, .2f, .3f, .4f, .5f, .6f)))),
        hitAreas = listOf(HitAreaIR("head", "Head", listOf(source.id, consumer.id))),
        advanced = AdvancedIR(hitAreas = listOf(HitAreaIR("auto", "Auto", listOf(source.id)))))
}
