package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.core.modifier.*
import io.github.psd2live.core.recipe.*
import io.github.psd2live.format.compile.RigIrBinary
import io.github.psd2live.format.model.RigIR
import io.github.psd2live.i18n.tr
import io.github.psd2live.project.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.umamo.format.art.*
import java.nio.file.Files
import java.util.UUID
import java.util.prefs.Preferences
import kotlin.test.*

/** Host-only numeric capture: native positive controls, never regenerated workers or tolerance parity. */
class WorkspaceLidHeadCaptureTest {
    private val versions = linkedMapOf("rig.scaffold" to 2, "body/legacy" to 2,
        "face/fitted" to 3, "eye/fitted" to 5, "brow/fitted" to 4,
        "eye.smile" to 3, "eye.wide" to 3, "eye.gaze-lid" to 3, "eye.emotion" to 3,
        "eye.highlight" to 3, "eye.aftershock" to 4, "eye.squash" to 4,
        "brow.shape" to 1, "brow.lid-coupling" to 8)
    private val consumers = versions.filterKeys { it.startsWith("eye.") || it == "brow.lid-coupling" }

    private data class Shape(val id: String, val tag: SemanticTag, val side: Side,
                             val x: Int, val y: Int, val width: Int, val height: Int, val role: PartRole? = null)

    /** Single-eye rectangles need aligned upper/lower centers to be upright under the source estimator. */
    private fun artwork(mirror: Boolean, upright: Boolean): WorkspaceDocument {
        val base = listOf(
            Shape("iris", SemanticTag.IRIDES, Side.LEFT, 37, 19, 5, 10),
            Shape("lash", SemanticTag.EYELASH, Side.LEFT, 34, 19, 12, 2),
            Shape("close", SemanticTag.EYE_CLOSE, Side.LEFT, 34, 25, 12, 2),
            Shape("white", SemanticTag.EYEWHITE, Side.LEFT, 34, 20, 12, 8),
            Shape("brow", SemanticTag.EYEBROW, Side.LEFT, 34, 14, 12, 2),
            Shape("mouth", SemanticTag.MOUTH, Side.NONE, 25, 35, 14, 6),
            Shape("front", SemanticTag.FRONT_HAIR, Side.NONE, 14, 4, 36, 13),
            Shape("face", SemanticTag.FACE, Side.NONE, 15, 8, 34, 40),
            Shape("back", SemanticTag.BACK_HAIR, Side.NONE, 12, 5, 40, 50),
            Shape("top", SemanticTag.TOPWEAR, Side.NONE, 12, 46, 40, 32),
            Shape("lid", SemanticTag.EYELASH, Side.LEFT, 34, 18, 12, 2, PartRole.LID),
            Shape("lid-shadow", SemanticTag.EYELASH, Side.LEFT, 34, 20, 12, 2, PartRole.SHADOW),
            Shape("highlight", SemanticTag.IRIDES, Side.LEFT, 39, 20, 2, 3, PartRole.HIGHLIGHT),
            Shape("nose", SemanticTag.NOSE, Side.NONE, 29, 28, 5, 3),
        )
        val aligned = base.map {
            // The old mouth/nose center was x≈31.92 while the single upper-eye/brow center is x≈39.91.
            // Translate actual artwork, not the inferred angle or frame state. Keep the original artwork as a rolled positive case.
            if (upright && it.tag in setOf(SemanticTag.MOUTH, SemanticTag.NOSE)) it.copy(x = it.x + 8) else it
        }
        val shapes = aligned.map {
            if (!mirror) it else it.copy(x = 64 - it.x - it.width,
                side = when (it.side) { Side.LEFT -> Side.RIGHT; Side.RIGHT -> Side.LEFT; else -> it.side })
        }
        val source = WorkspaceSourceArt(64, 80, shapes.mapIndexed { index, shape ->
            WorkspaceSourceLayer(LayerId(shape.id), shape.id, "", SourceLayerKind.Raster, true, index,
                LayerBounds(shape.x, shape.y, shape.width, shape.height), 1f, false, LayerBlend.Normal, ChannelMask.ALL,
                LayerRaster(shape.width, shape.height, ByteArray(shape.width * shape.height * 4) { 255.toByte() }),
                null, null, false)
        }, emptyList())
        val config = RigModifierPipeline.ordinaryConfig(PipelineConfig(atlasSize = 128, meshSpacing = 6,
            recipe = RigRecipe.Legacy, generatePhysics = false, exportMoc3 = false))
            .copy(rigEdits = RigEditOverlay(modifierStack = RigModifierStack().toJson()))
        return WorkspaceDocument(source, emptyMap(), emptySet(), shapes.associate {
            it.id to LayerClassificationOverride(LayerType.PRESET, it.tag, it.side, role = it.role)
        }, emptyMap(), config.rigEdits, WorkspaceSettingsCodec.encode(config))
    }

    private data class Fixture(val document: WorkspaceDocument, val model: RigPreviewModel, val stack: RigModifierStack) {
        fun source(stack: RigModifierStack = this.stack) = RigModifierSource.of(model.copy(
            config = model.config.copy(rigEdits = model.config.rigEdits.copy(modifierStack = stack.toJson()))))
        fun document(stack: RigModifierStack = this.stack) = document.copy(
            rigEdits = document.rigEdits.copy(modifierStack = stack.toJson()))
    }

    private fun fixture(mirror: Boolean = false, upright: Boolean = false, tuned: Boolean = false,
                        squashVersion: Int = 4): Fixture {
        val art = artwork(mirror = mirror, upright = upright)
        val choices = WorkspaceInitialPreset(clothing = false)
        val config = choices.configure(WorkspacePresetBaseline.configure(art.config(), "modern"))
            .copy(rigEdits = RigEditOverlay(modifierStack = RigModifierStack().toJson()))
        val document = art.copy(settings = WorkspaceSettingsCodec.encode(config), rigEdits = config.rigEdits)
        val model = PSD2LivePipeline().apply { deferRuntimeBundles = true }.buildPreview(document.source, document.config())
        val targets = model.rig.puppet.drawables.filter { it.mesh != null && it.id.raw in model.rig.ownLayerIdByDrawableId }
        // Squash3 predates highlight support; brow-lid8 admits squash4, not squash3.
        val selected = (if (squashVersion == 3) versions - setOf("eye.highlight", "brow.lid-coupling") else versions) + ("eye.squash" to squashVersion)
        val planned = WorkspaceModifierPlan.plan(model, RigModifierStack(), buildJsonObject {
            put("group", "modern-standard")
            putJsonArray("targets") { targets.forEach { addJsonObject { put("kind", "mesh"); put("id", it.id.raw) } } }
        }, selected.keys.drop(1)).first
        val stack = planned.copy(instances = planned.instances.map { old ->
            val version = selected.getValue(old.type)
            val extra = if (!tuned) JsonObject(emptyMap()) else when (old.type) {
                "body/legacy" -> buildJsonObject {
                    put("strength", .8f)
                    putJsonObject("tuning") { put("breathLift", 4.25f); put("faceLeanUp", 11f) }
                }
                "face/fitted" -> buildJsonObject {
                    put("strength", .73f)
                    putJsonObject("values") { put("turn", 63f); put("look", 120f); put("depth", 135f) }
                }
                "eye/fitted" -> buildJsonObject {
                    putJsonObject("values") { put("lowerShare", 12f); put("roundness", 115f); put("lashSquash", 75f) }
                    putJsonObject("tuning") { put("gazeX", 13f) }
                }
                else -> JsonObject(emptyMap())
            }
            old.copy(version = version, config = JsonObject(old.config + extra),
                plugin = assertNotNull(BuiltinModifierPlugins.reference(old.type, version)))
        })
        assertEquals(selected, stack.instances.associate { it.type to it.version })
        return Fixture(document, model, stack)
    }

    private fun definition(source: RigModifierSource, owner: RigModifierInstance): RigModifierDefinition = when (owner.type) {
        "eye.smile" -> RigEyeSmileModifier.modernDefinition(source)
        "eye.wide" -> RigEyeWideModifier.modernDefinition(source)
        "eye.gaze-lid" -> RigEyeGazeLidModifier.definition(source, modern = true)
        "eye.emotion" -> RigEyeEmotionModifier.definition(source, modern = true)
        "eye.highlight" -> RigEyeHighlightModifier.definition(source, modern = true)
        "eye.aftershock" -> RigEyeAftershockModifier.fittedHeadDefinition(source)
        "eye.squash" -> if (owner.version == 4) RigEyeSquashModifier.highlightDefinition(source)
            else RigEyeSquashModifier.definition(source, modern = true)
        "brow.lid-coupling" -> RigBrowLidModifier.singleEyeDefinition(source)
        else -> error("Unexpected lid consumer ${owner.type}")
    }

    /** Reconstruct only each caller's native configuration; no output IR or recorded shell is a measurement input. */
    private fun native(source: RigModifierSource, owner: RigModifierInstance): Pair<RigModifierInstance, PipelineConfig> {
        val scaffold = source.provider(owner)
        val eyeId = if (owner.type == "brow.lid-coupling")
            owner.config.getValue("dependencies").jsonObject.getValue("eye/fitted").jsonPrimitive.content
            else owner.config.getValue("base").jsonPrimitive.content
        val eye = source.activeBefore(owner).single { it.id == eyeId }
        val corrections = WorkspaceSettingsCodec.decodeRecipe(scaffold.config["corrections"])
        val iris = owner.type in setOf("eye.highlight", "eye.squash")
        val recipe = if (iris) RigRecipe(true,
            bases = mapOf("eye" to BaseChoice(eye.type, eye.version, RigLegacyBindings.values(eye))),
            moves = corrections.moves, eyeParts = corrections.eyeParts)
        else RigRecipe(true, bases = mapOf("eye" to BaseChoice(eye.type, eye.version)),
            modules = RigModules.modules.associate { it.id to ModuleChoice(false) },
            moves = corrections.moves, eyeParts = corrections.eyeParts)
        var config = source.sourceConfig().copy(recipe = recipe, rigTuning = RigLegacyBindings.tuning(eye, RigTuning()))
        if (!iris) config = config.copy(headTurnStrength = 1f, bodyStrength = 1f)
        if (owner.type != "eye.squash" || owner.version == 4) config = config.copy(
            featureDisplacementEnabled = scaffold.config["featureDisplacement"]?.jsonPrimitive?.boolean ?: false,
            hairSimulationFront = !(scaffold.config["frontHairSway"]?.jsonPrimitive?.boolean ?: true),
            hairSimulationBack = !(scaffold.config["backHairSway"]?.jsonPrimitive?.boolean ?: true))
        return eye to config
    }

    private fun <T> profile(block: () -> T): Pair<T, Map<String, Pair<Double, Long>>> {
        val recording = RigBuildProfile.recording
        val cache = RigStageCache.enabled
        RigBuildProfile.reset()
        try {
            RigBuildProfile.recording = true
            RigStageCache.enabled = false
            return block() to RigBuildProfile.snapshot()
        } finally {
            RigBuildProfile.recording = recording
            RigStageCache.enabled = cache
            RigBuildProfile.reset()
        }
    }

    private fun assertContext(expected: ModifierAlgorithmContext, actual: ModifierAlgorithmContext, label: String) {
        assertEquals(expected.values, actual.values, "$label values")
        assertEquals(expected.values.toString(), actual.values.toString(), "$label literal JSON order and signed zero")
        assertEquals(expected.upstream, actual.upstream, "$label upstream")
        assertEquals(expected.resources.keys.toList(), actual.resources.keys.toList(), "$label ordered resources")
        expected.resources.forEach { (key, resource) ->
            val observed = actual.resources.getValue(key)
            assertEquals(resource.digest, observed.digest, "$label resource digest $key")
            assertContentEquals(resource.read(), observed.read(), "$label resource bytes $key")
        }
        fun headShell(values: JsonObject): JsonArray? = (values["head_turn"]
            ?: values["source"]?.jsonObject?.get("head_turn"))?.jsonObject?.getValue("shell")?.jsonArray
        val shell = headShell(expected.values) ?: return
        val observed = assertNotNull(headShell(actual.values))
        assertEquals(7, shell.size, "$label shell width")
        assertEquals(shell.map { it.jsonPrimitive.float.toRawBits() }, observed.map { it.jsonPrimitive.float.toRawBits() },
            "$label raw float bits")
    }

    private fun assertIr(expected: RigIR, actual: RigIR, label: String) {
        assertEquals(expected, actual, "$label full IR")
        assertContentEquals(RigIrBinary.encode(expected), RigIrBinary.encode(actual), "$label binary IR")
    }

    @Test fun coldNativeHelperBuildsOneAnimatedScaffoldAndMeasuredHelperBuildsNone() {
        for (mirror in listOf(false, true)) for (upright in listOf(false, true)) {
            val fixture = fixture(mirror, upright, tuned = true)
            val source = fixture.source()
            val owner = fixture.stack.instances.single { it.type == "eye.wide" }
            val scaffold = source.provider(owner)
            val (eye, native) = native(source, owner)
            val captured = RigEyeWideModifier.definition(source).authorContext(owner)
            assertTrue(captured.resources.isNotEmpty(), "Real source bytes must be present")
            val (old, oldProfile) = profile { source.legacyLidHeadSource(owner, scaffold, eye, native, captured) }
            val (next, nextProfile) = profile { source.captureLidHeadSource(owner, scaffold, eye, native, captured) }
            assertEquals(1L, oldProfile["scaffold: deformers (built)"]?.second, "Native cold positive control")
            assertEquals(0L, nextProfile["scaffold: deformers (built)"]?.second ?: 0L, "Numeric capture must build no animated scaffold")
            for (counts in listOf(oldProfile, nextProfile))
                assertEquals(1L, counts["scaffold: face rig + frames"]?.second, "Both paths must really recompute source geometry")
            assertContext(old, next, "mirror=$mirror, upright=$upright")
            captured.resources.forEach { (key, resource) ->
                assertSame(resource, next.resources.getValue(key), "Do not replace the caller's source bytes")
            }
            val angle = RigBuilder.rigContext(source.analysis, native.copy(generateDeformers = false), source.meshCache).faceRig.initialAngleZ
            if (upright) assertEquals(0f, angle) else assertNotEquals(0f, angle, "Rolled source must not silently become upright")
        }
    }

    @Test fun completeWideCaptureRemovesExactlyOneBuildAndKeepsItsNeutralSourceCapture() {
        val fixture = fixture(tuned = true)
        val source = fixture.source()
        val owner = fixture.stack.instances.single { it.type == "eye.wide" }
        val scaffold = source.provider(owner)
        val (eye, native) = native(source, owner)
        val (old, oldProfile) = profile {
            source.legacyLidHeadSource(owner, scaffold, eye, native, RigEyeWideModifier.definition(source).authorContext(owner))
        }
        val (next, nextProfile) = profile { RigEyeWideModifier.modernDefinition(source).authorContext(owner) }
        assertContext(old, next, "Whole wide author context")
        val oldBuilds = assertNotNull(oldProfile["scaffold: deformers (built)"]).second
        val newBuilds = assertNotNull(nextProfile["scaffold: deformers (built)"]).second
        assertTrue(newBuilds > 0, "The separate neutral source capture is legitimate and must still run")
        assertEquals(oldBuilds - 1, newBuilds, "Only the unused animated head scaffold is removed")
        assertEquals(oldProfile["scaffold: face rig + frames"]?.second, nextProfile["scaffold: face rig + frames"]?.second)
    }

    @Test fun everyLidConsumerPreservesContextsAndUnchangedInstalledWorkerBytes(): Unit = runBlocking {
        for ((mirror, squashVersion) in listOf(false to 4, true to 4, true to 3)) {
            val fixture = fixture(mirror = mirror, tuned = true, squashVersion = squashVersion)
            val model = WorkspacePreviewBuilder().build(fixture.document())
            val evaluation = assertNotNull(model.modifierEvaluation)
            assertTrue(evaluation.entries.all { it.status == ModifierStatus.ACTIVE }, "Every real source stage must execute")
            val source = RigModifierSource.of(model)
            val owners = fixture.stack.instances.filter { it.type in consumers }
            assertEquals(if (squashVersion == 3) consumers.keys - setOf("eye.highlight", "brow.lid-coupling") else consumers.keys,
                owners.map { it.type }.toSet())
            val root = Files.createTempDirectory("lid-head-capture-")
            val preferences = Preferences.userRoot().node("lid-head-capture/" + UUID.randomUUID())
            val store = ModifierPluginStore(root.resolve("installed"), preferences)
            try {
                val artifacts = owners.map { assertNotNull(BundledAlgorithmArtifacts.find(it.type, it.version)) }
                BundledAlgorithmArtifacts.retain(store, artifacts)
                for ((owner, artifact) in owners.zip(artifacts)) {
                    val label = "${owner.type}/${owner.version}, mirror=$mirror"
                    val definition = definition(source, owner)
                    val observed = assertNotNull(evaluation.readAlgorithms[owner.id]).context
                    // Fresh definition instances bypass the emotion/aftershock context memoization.
                    val current = definition.authorContext(owner).withUpstream(observed.upstream)
                    assertContext(observed, current, "$label pipeline")
                    val (eye, native) = native(source, owner)
                    val (legacy, counts) = profile {
                        // Gaze wraps the lid source in its own envelope; replace only that nested shell.
                        val input = if (owner.type == "eye.gaze-lid")
                            ModifierAlgorithmContext(current.values.getValue("source").jsonObject, current.resources) else current
                        val nativeHead = source.legacyLidHeadSource(owner, source.provider(owner), eye, native, input)
                        if (owner.type == "eye.gaze-lid") ModifierAlgorithmContext(
                            JsonObject(current.values + ("source" to nativeHead.values)), current.resources, observed.upstream)
                        else nativeHead.withUpstream(observed.upstream)
                    }
                    assertEquals(1L, counts["scaffold: deformers (built)"]?.second, "$label independent native shell control")
                    assertContext(legacy, current, label)
                    val index = fixture.stack.index(owner.id)
                    val before = evaluation.outputs[index - 1]
                    val expected = evaluation.outputs[index]
                    assertNotEquals(before, expected, "$label is not a no-op fixture")
                    val algorithm = definition.plugin as ReadingModifierAlgorithmPlugin
                    assertIr(expected, algorithm.evaluate(before, owner, legacy, {}), "$label legacy host")
                    assertIr(expected, algorithm.evaluate(before, owner, current, {}), "$label measured host")
                    val installed = store.snapshot().single { it.reference == artifact.reference }
                    assertEquals(owner.plugin, installed.reference, "$label unchanged package identity")
                    val worker = installed.manifest.algorithms.single()
                    for ((mode, context) in listOf("native" to legacy, "measured" to current)) {
                        assertIr(expected, ModifierPluginWorkers.evaluate(installed, worker, before, owner, {}, store,
                            context = context, declaredParameters = definition.parameters(owner)), "$label $mode installed JVM")
                    }
                }
            } finally {
                store.snapshot().forEach { ModifierPluginWorkers.stop(it.reference) }
                try { preferences.removeNode() } finally { ModifierPluginStore.deleteTree(root) }
            }
        }
    }

    @Test fun noHeadReturnsOriginalAndIncompleteHeadRejectsBeforeBuilding() {
        val fixture = fixture()
        val owner = fixture.stack.instances.single { it.type == "eye.wide" }
        val face = fixture.stack.instances.single { it.type == "face/fitted" }
        val source = fixture.source()
        val (eye, native) = native(source, owner)
        val scaffold = source.provider(owner)
        val captured = RigEyeWideModifier.definition(source).authorContext(owner)
        val absent = fixture.source(fixture.stack.copy(instances = fixture.stack.instances.filterNot { it.id == face.id }))
        val (unchanged, emptyProfile) = profile { absent.captureLidHeadSource(owner, scaffold, eye, native, captured) }
        assertSame(captured, unchanged)
        assertTrue(emptyProfile.isEmpty(), "No head must return before source work")
        val incomplete = fixture.source(fixture.stack.copy(instances = fixture.stack.instances.map {
            if (it.id == face.id) it.copy(targets = it.targets.dropLast(1)) else it
        }))
        val (failure, rejectedProfile) = profile {
            assertFailsWith<IllegalArgumentException> { incomplete.captureLidHeadSource(owner, scaffold, eye, native, captured) }
        }
        assertEquals("Lid binding requires a shared fitted head", failure.message)
        assertTrue(rejectedProfile.isEmpty(), "Reject incomplete targets before any source work")
    }

    @Test fun explicitDepthProviderRetainsItsValuesAndUsesPrecedingFaceStrength() {
        val fixture = fixture(tuned = true)
        val owner = fixture.stack.instances.single { it.type == "eye.wide" }
        val original = fixture.stack.instances.single { it.type == "face/fitted" }
        val legacyFace = original.copy(type = "face/legacy", version = 2,
            config = JsonObject(original.config - "values"), plugin = BuiltinModifierPlugins.reference("face/legacy", 2))
        val depth = RigModifierInstance("test-depth", "face.depth-turn", 1, original.targets,
            config = buildJsonObject {
                put("scaffold", original.config.getValue("scaffold"))
                putJsonObject("dependencies") { put("face/legacy", original.id) }
                putJsonObject("values") { put("turn", 54f); put("look", 110f); put("depth", 142f) }
            }, plugin = BuiltinModifierPlugins.reference("face.depth-turn", 1))
        val stack = fixture.stack.copy(instances = fixture.stack.instances.flatMap {
            if (it.id == original.id) listOf(legacyFace, depth) else listOf(it)
        })
        val source = fixture.source(stack)
        assertEquals(depth, source.activeBefore(owner).single { it.id == depth.id })
        val (eye, native) = native(source, owner)
        val captured = RigEyeWideModifier.definition(source).authorContext(owner)
        val (old, oldProfile) = profile { source.legacyLidHeadSource(owner, source.provider(owner), eye, native, captured) }
        val (next, nextProfile) = profile { source.captureLidHeadSource(owner, source.provider(owner), eye, native, captured) }
        assertEquals(1L, oldProfile["scaffold: deformers (built)"]?.second)
        assertEquals(0L, nextProfile["scaffold: deformers (built)"]?.second ?: 0L)
        assertContext(old, next, "Explicit depth provider")
        val head = next.values.getValue("head_turn").jsonObject
        assertEquals(depth.id, head.getValue("provider").jsonPrimitive.content)
        val shell = head.getValue("shell").jsonArray.map { it.jsonPrimitive.float }
        assertEquals((54f / 100f * .73f).toRawBits(), shell[4].toRawBits())
        assertEquals((142f / 100f).toRawBits(), shell[5].toRawBits())
    }
}

/** Frozen pre-optimization helper: deliberately uses the animated native context as the positive control. */
private fun RigModifierSource.legacyLidHeadSource(
    instance: RigModifierInstance, scaffold: RigModifierInstance, base: RigModifierInstance,
    native: PipelineConfig, captured: ModifierAlgorithmContext,
): ModifierAlgorithmContext {
    val preceding = activeBefore(instance).filter {
        it.config["scaffold"] == JsonPrimitive(scaffold.id) && it.targets.any(instance.targets::contains)
    }
    val head = preceding.lastOrNull { it.type in setOf("face/fitted", "face.depth-turn") } ?: return captured
    require(head.targets.containsAll(instance.targets)) { "Lid binding requires a shared fitted head" }
    val depth = RigModules.FACE_DEPTH_TURN
    val body = preceding.lastOrNull { it.type == "body/legacy" }
    val turning = native.copy(
        recipe = native.recipe.copy(modules = native.recipe.modules + (depth.id to ModuleChoice(true, depth.version, RigLegacyBindings.values(head)))),
        headTurnStrength = preceding.lastOrNull { it.type in setOf("face/fitted", "face/legacy") }
            ?.let(RigLegacyBindings::strength) ?: 1f,
        rigTuning = RigLegacyBindings.tuning(base,
            RigLegacyBindings.tuning(head, body?.let { RigLegacyBindings.tuning(it, RigTuning()) } ?: RigTuning())),
    )
    val face = RigBuilder.rigContext(analysis, turning, meshCache).faceRig
    return ModifierAlgorithmContext(JsonObject(captured.values + ("head_turn" to buildJsonObject {
        put("provider", head.id)
        put("shell", JsonArray(RigBuilder.headTurnMeasurements(face, turning).map(::JsonPrimitive)))
        put("turn_name", tr("model.deformer.meshTurn", "{meshName}"))
        put("shape_name", tr("model.deformer.meshShape", "{meshName}"))
        put("native_ids", JsonObject(instance.targets.associate { target ->
            target.id to JsonPrimitive(primaryByLayer.getValue(layersByMesh.getValue(target.id)))
        }))
    })), captured.resources)
}
