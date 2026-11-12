package io.github.psd2live.application

import io.github.psd2live.algorithms.binding.BrowFittedV5Binding
import io.github.psd2live.core.*
import io.github.psd2live.core.modifier.*
import io.github.psd2live.core.recipe.RigRecipe
import io.github.psd2live.format.compile.RigIrBinary
import io.github.psd2live.format.model.Deformer
import io.github.psd2live.format.model.Floats
import io.github.psd2live.format.model.Ints
import io.github.psd2live.format.model.RigIR
import io.github.psd2live.project.*
import io.github.psd2live.targets.cubism.PuppetIr
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.umamo.format.art.*
import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.runtime.model.ParameterId
import java.nio.file.Files
import java.util.UUID
import java.util.prefs.Preferences
import kotlin.test.*

/** Only the explicit upright LEFT/no-interior prefix is acceptance coverage. No downstream brow4 consumer is upgraded. */
class WorkspaceBrowFittedV5SourceTest {
    private val versions = linkedMapOf("rig.scaffold" to 2, "body/legacy" to 2,
        "face/fitted" to 3, "eye/fitted" to 5, "brow/fitted" to 5)

    private data class Shape(val id: String, val tag: SemanticTag, val side: Side,
                             val x: Int, val y: Int, val width: Int, val height: Int, val role: PartRole? = null)

    /** Single-eye rectangles need aligned upper/lower centers to be upright under the source estimator. */
    private fun artwork(mirror: Boolean = false, interiors: Int = 0, bilateral: Boolean = false,
                        upright: Boolean = true): WorkspaceDocument {
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
        val teeth = if (interiors == 0) emptyList() else listOf(
            Shape("teeth-top", SemanticTag.TOOTH_T, Side.NONE, 27, 35, 10, 2),
            Shape("teeth-bottom", SemanticTag.TOOTH_B, Side.NONE, 28, 39, 8, 2))
        val tongue = if (interiors < 2) emptyList() else listOf(Shape("tongue", SemanticTag.TONGUE, Side.NONE, 29, 38, 6, 3))
        val paired = if (!bilateral) emptyList() else base.filter { it.side == Side.LEFT }.map {
            it.copy(id = "${it.id}-right", side = Side.RIGHT, x = 64 - it.x - it.width)
        }
        val aligned = (base + teeth + tongue + paired).map {
            // The old mouth/nose center was x≈31.92 while the single upper-eye/brow center is x≈39.91.
            // Translate actual artwork, not the inferred angle or frame state. Keep the old art as a rejection case.
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

    private data class Fixture(val document: WorkspaceDocument, val sourceModel: RigPreviewModel, val stack: RigModifierStack) {
        val brow get() = stack.instances.last()
        fun document(stack: RigModifierStack = this.stack) = document.copy(rigEdits = document.rigEdits.copy(modifierStack = stack.toJson()))
        fun source(stack: RigModifierStack = this.stack): RigModifierSource = RigModifierSource.of(sourceModel.copy(
            config = sourceModel.config.copy(rigEdits = sourceModel.config.rigEdits.copy(modifierStack = stack.toJson()))))
        fun definition(stack: RigModifierStack = this.stack) = RigBrowFittedV5Modifier.definition(source(stack))
        fun context(stack: RigModifierStack = this.stack): ModifierAlgorithmContext =
            definition(stack).authorContext(stack.instances.last()).withUpstream(stack.instances.dropLast(1).map {
                ModifierUpstreamCapability(it.type, it.type, it.version, it.targets, it.id, it.config, it.outputAliases, it.plugin)
            })
        fun version(version: Int) = stack.copy(instances = stack.instances.dropLast(1) + brow.copy(
            version = version, plugin = assertNotNull(BuiltinModifierPlugins.reference(brow.type, version))))
    }

    private fun fixture(art: WorkspaceDocument = artwork(), tuned: Boolean = false): Fixture {
        val choices = WorkspaceInitialPreset(clothing = false)
        val config = choices.configure(WorkspacePresetBaseline.configure(art.config(), "modern"))
            .copy(rigEdits = RigEditOverlay(modifierStack = RigModifierStack().toJson()))
        val document = art.copy(settings = WorkspaceSettingsCodec.encode(config), rigEdits = config.rigEdits)
        val source = PSD2LivePipeline().apply { deferRuntimeBundles = true }.buildPreview(document.source, document.config())
        val targets = source.rig.puppet.drawables.filter { it.mesh != null && it.id.raw in source.rig.ownLayerIdByDrawableId }
        val planned = WorkspaceModifierPlan.plan(source, RigModifierStack(), buildJsonObject {
            put("group", "modern-standard")
            putJsonArray("targets") { targets.forEach { addJsonObject { put("kind", "mesh"); put("id", it.id.raw) } } }
        }, versions.keys.drop(1)).first
        val stack = planned.copy(instances = planned.instances.map { entry ->
            val version = versions.getValue(entry.type)
            val extra = if (!tuned) JsonObject(emptyMap()) else when (entry.type) {
                "body/legacy" -> buildJsonObject {
                    put("strength", .8f)
                    putJsonObject("tuning") {
                        put("breathLift", 4.25f); put("bodyZDegrees", 4.5f)
                        put("faceLeanUp", 11f); put("faceLeanDown", 7f)
                    }
                }
                "face/fitted" -> buildJsonObject {
                    put("strength", .75f)
                }
                "eye/fitted" -> buildJsonObject { putJsonObject("values") { put("lowerShare", 17f); put("roundness", 115f) } }
                "brow/fitted" -> buildJsonObject { putJsonObject("values") { put("lift", 67f) } }
                else -> JsonObject(emptyMap())
            }
            entry.copy(version = version, config = JsonObject(entry.config + extra),
                plugin = assertNotNull(BuiltinModifierPlugins.reference(entry.type, version)))
        })
        assertEquals(versions.toList(), stack.instances.map { it.type to it.version }, "Only the complete four-provider prefix may precede brow5")
        return Fixture(document, source, stack)
    }

    private fun assertRig(expected: RigIR, actual: RigIR, label: String) {
        assertEquals(expected.deformers.map { it.id }, actual.deformers.map { it.id }, "$label: deformer inventory")
        expected.deformers.zip(actual.deformers).forEach { (a, b) -> assertEquals(a, b, "$label: ${a.id}") }
        assertEquals(expected.meshes.map { it.id }, actual.meshes.map { it.id }, "$label: mesh inventory")
        expected.meshes.zip(actual.meshes).forEach { (a, b) -> assertEquals(a, b, "$label: ${a.name}") }
        assertEquals(expected, actual, "$label: full IR, including hierarchy, parameters, material and texture fields")
    }

    private fun assertPreview(expected: RigPreviewModel, actual: RigPreviewModel, label: String) {
        val left = assertNotNull(expected.modifierEvaluation)
        val right = assertNotNull(actual.modifierEvaluation)
        assertEquals(5, right.outputs.size)
        assertTrue(right.entries.all { it.status == ModifierStatus.ACTIVE }, "$label: every prefix entry must execute")
        left.outputs.zip(right.outputs).forEachIndexed { index, (a, b) -> assertRig(a, b, "$label stage $index") }
        assertEquals(expected.rig.modifierOutput, actual.rig.modifierOutput, "$label: raw modifier output")
        assertEquals(PuppetIr.toIr(expected.rig.puppet), PuppetIr.toIr(actual.rig.puppet), "$label: raw runtime semantics")
        assertEquals(expected.rig.modifierRasterAssets, actual.rig.modifierRasterAssets)
        assertRig(RigIrCompiler.compile(expected, tileArt = true), RigIrCompiler.compile(actual, tileArt = true), "$label: export")
        val id = right.stack.instances.first().outputId("shared/deformer:DeformHeadRotation")
        val a = assertIs<Deformer.Rotation>(left.outputs.last().deformers.single { it.id == id })
        val b = assertIs<Deformer.Rotation>(right.outputs.last().deformers.single { it.id == id })
        assertEquals(a.baseAngle, b.baseAngle, "$label: DeformHeadRotation.baseAngle is not normalized away")
        assertEquals(a.pivot, b.pivot, "$label: exact head-frame pivot keyforms")
    }

    private fun nativeSourceAngles(f: Fixture): List<Float> {
        val source = f.source()
        val scaffold = f.stack.instances.first()
        val prefix = f.stack.instances.drop(1).dropLast(1)
        val historicalBrow = f.version(4).instances.last()
        return listOf(prefix, prefix + historicalBrow).map { preceding ->
            // Independent native measurement path, not the candidate's no-deformer capture or its JSON values.
            val (_, measured) = RigFittedMouthModuleSource.context(source, scaffold, preceding, null)
            assertTrue(measured.deformersEnabled && measured.deformers.isNotEmpty())
            measured.faceRig.initialAngleZ
        }
    }

    private fun assertCapturedUpright(context: ModifierAlgorithmContext) {
        for (phase in listOf("before", "after")) {
            val values = context.values.getValue(phase).jsonObject
            val head = values.getValue("base").jsonObject.getValue("scaffold").jsonObject
                .getValue("frames").jsonObject.getValue("head").jsonObject
            assertEquals(0f, head.getValue("initial_angle").jsonPrimitive.float, "$phase captured source head angle")
            assertEquals(0f, values.getValue("head_alignment").jsonArray[0].jsonPrimitive.float, "$phase explicit source alignment")
        }
    }

    @Test fun minimalLeftPrefixMatchesActualBrow4AndHistoricalBrow1(): Unit = runBlocking {
        val f = fixture()
        assertEquals(listOf(0f, 0f), nativeSourceAngles(f), "Both actual native phases must be upright before parity is attempted")
        assertCapturedUpright(f.context())
        assertTrue(f.definition().explicitVersion, "Brow5 must remain explicit rather than a default migration")
        assertNotEquals(5, assertNotNull(RigModifierAlgorithms.registry.defaultDefinition("brow/fitted")).version)
        val historical = WorkspacePreviewBuilder().build(f.document(f.version(1)))
        val projected = WorkspacePreviewBuilder().build(f.document(f.version(4)))
        val actual = WorkspacePreviewBuilder().build(f.document())
        assertPreview(historical, projected, "real brow4 reference")
        assertPreview(projected, actual, "brow5 source implementation")
        val output = assertNotNull(actual.modifierEvaluation).outputs
        val before = output[3]
        val after = output[4]
        val changed = before.meshes.zip(after.meshes).filter { it.first != it.second }.map { it.first.name }.toSet()
        assertEquals(setOf("brow"), changed,
            "This upright cohort proves brow-local motion only; non-brow/shared-head-changing source remains unproven")
        val head = f.stack.instances.first().outputId("shared/deformer:DeformHeadRotation")
        assertEquals(before.deformers.single { it.id == head }, after.deformers.single { it.id == head },
            "This upright source preserves the complete shared head frame, including its static base angle")
        val poses = listOf(emptyMap(), mapOf(ParameterId("ParamAngleX") to 25f, ParameterId("ParamAngleY") to -20f,
            ParameterId("ParamAngleZ") to 15f, ParameterId("ParamBrowLY") to 1f,
            ParameterId("ParamEyeLOpen") to .4f, ParameterId("ParamBreath") to .8f))
        for (pose in poses) {
            val expected = CpuDeformationEvaluator().evaluate(projected.rig.puppet, pose)
            val observed = CpuDeformationEvaluator().evaluate(actual.rig.puppet, pose)
            assertEquals(expected.worldPositions.keys, observed.worldPositions.keys)
            expected.worldPositions.forEach { (id, points) -> assertContentEquals(points, observed.worldPositions.getValue(id), "$id at $pose") }
        }
    }

    @Test fun tunedAliasedPrefixMatchesBothReferencesAndColdReplay(): Unit = runBlocking {
        val plain = fixture(tuned = true)
        assertEquals(listOf(0f, 0f), nativeSourceAngles(plain), "Tuned native source phases must remain upright")
        assertCapturedUpright(plain.context())
        val reference = WorkspacePreviewBuilder().build(plain.document(plain.version(4)))
        val owned = assertNotNull(reference.modifierEvaluation).outputs.last()
        val aliased = plain.stack.copy(instances = plain.stack.instances.map { entry ->
            // Deformer aliases are proven separately from parameter-folder alias layouts.
            val locals = owned.deformers.map { it.id }.mapNotNull(entry::localOutputName)
            entry.copy(outputAliases = locals.withIndex().associate { (index, local) -> local to "brow5-test-${entry.id}-$index" })
        })
        assertTrue(aliased.instances.any { it.outputAliases.isNotEmpty() })
        val f = plain.copy(stack = aliased)
        val historical = WorkspacePreviewBuilder().build(f.document(f.version(1)))
        val projected = WorkspacePreviewBuilder().build(f.document(f.version(4)))
        val actual = WorkspacePreviewBuilder().build(f.document())
        assertPreview(historical, projected, "tuned aliases brow4")
        assertPreview(projected, actual, "tuned aliases brow5")
        val enabled = RigStageCache.enabled
        try {
            RigStageCache.enabled = false
            assertPreview(projected, WorkspacePreviewBuilder().build(f.document()), "cold tuned aliases brow5")
        } finally { RigStageCache.enabled = enabled }
    }

    @Test fun captureContainsOnlyRawSourceAndOwnLiftKeepsGeometryResources() {
        val f = fixture()
        val recording = RigBuildProfile.recording
        val enabled = RigStageCache.enabled
        RigBuildProfile.reset()
        try {
            RigBuildProfile.recording = true
            RigStageCache.enabled = false
            val old = f.version(4)
            RigBrowFittedModifier.sharedFrameDefinition(f.source(old)).authorContext(old.instances.last())
        } finally { RigBuildProfile.recording = recording; RigStageCache.enabled = enabled }
        val projectedProfile = RigBuildProfile.snapshot()
        assertTrue((projectedProfile["scaffold: deformers (built)"]?.second ?: 0L) > 0,
            "Cold real brow4 capture is the positive control for native generator profiling: $projectedProfile")
        RigBuildProfile.reset()
        val context = try {
            RigBuildProfile.recording = true
            RigStageCache.enabled = false
            f.context()
        } finally { RigBuildProfile.recording = recording; RigStageCache.enabled = enabled }
        val profile = RigBuildProfile.snapshot()
        RigBuildProfile.reset()
        assertEquals(1L, profile["modifier brow v5 source measurements"]?.second)
        val forbidden = setOf("modifier attachment source builds", "modifier legacy rig builds", "modifier legacy template requests",
            "scaffold: deformers (built)", "scaffold: face turn (built)", "mesh: layer mesh + keyforms (built)",
            "mesh: mouth lips (built)", "build: total", "build: context")
        // This proves absence of instrumented generators. Uninstrumented lowering also requires adapter review.
        assertTrue(profile.keys.none { it in forbidden ||
            it.startsWith("modifier legacy template:") }, "Source capture ran a generated phase: $profile")
        assertEquals(setOf("schema", "providers", "provider_packages", "source", "before", "after"), context.values.keys)
        assertEquals(JsonPrimitive("brow-fitted-source/5"), context.values["schema"])
        assertCapturedUpright(context)
        assertTrue(context.resources.keys.containsAll(listOf("before_meshes", "after_meshes")))
        assertTrue(context.resources.keys.none { it in setOf("before", "after", "source_branch", "skin_forms", "neutral_channels") })
        for (name in listOf("before_meshes", "after_meshes")) {
            val raw = RigIrBinary.decode(context.resources.getValue(name).read())
            assertTrue(raw.deformers.isEmpty() && raw.parameters.isEmpty() && raw.parts.isEmpty())
            assertEquals(f.brow.targets.map { it.id }.toSet(), raw.meshes.map { it.id }.toSet())
            assertTrue(raw.meshes.all { it.parent == null && it.offsets == null && it.shapes.isEmpty() && it.channels.isEmpty() && it.geometry != null })
        }
        val changedBrow = f.brow.copy(config = JsonObject(f.brow.config + ("values" to buildJsonObject { put("lift", 79f) })))
        val changed = f.stack.copy(instances = f.stack.instances.dropLast(1) + changedBrow)
        val recaptured = f.context(changed)
        assertEquals(context.resources.keys, recaptured.resources.keys)
        context.resources.forEach { (name, value) -> assertContentEquals(value.read(), recaptured.resources.getValue(name).read(), name) }
        assertNotEquals(context.values, recaptured.values, "The worker still needs the changed explicit lift input")
        val body = f.stack.instances.single { it.type == "body/legacy" }
        val changedBody = body.copy(config = JsonObject(body.config + ("strength" to JsonPrimitive(.73f))))
        val providerChange = f.context(f.stack.copy(instances = f.stack.instances.map { if (it.id == body.id) changedBody else it }))
        assertNotEquals(context, providerChange, "Provider tuning must invalidate captured input")
        val original = artwork()
        val art = original.source as WorkspaceSourceArt
        val recolored = original.copy(source = art.copy(layers = art.layers.map { layer ->
            if (layer.id.raw != "brow") layer else (layer as WorkspaceSourceLayer).copy(raster = LayerRaster(
                layer.raster.width, layer.raster.height, layer.raster.rgba.copyOf().also { it[0] = 42 }))
        }))
        val changedPixels = fixture(recolored).context()
        assertNotEquals(context.resources, changedPixels.resources, "Source pixels must invalidate source resources")
        for (name in listOf("before_meshes", "after_meshes")) assertContentEquals(
            context.resources.getValue(name).read(), changedPixels.resources.getValue(name).read(),
            "RGB-only source edits must not masquerade as generated geometry edits")
    }

    @Test fun missingWrongDisabledAndAmbiguousProvidersRejectBeforeMeasurements() {
        val f = fixture()
        for (provider in f.stack.instances.dropLast(1)) {
            val oldVersion = if (provider.type == "eye/fitted") 2 else 1
            val wrong = provider.copy(version = oldVersion, plugin = assertNotNull(BuiltinModifierPlugins.reference(provider.type, oldVersion)))
            val variants = listOf(
                f.stack.copy(instances = f.stack.instances.filterNot { it.id == provider.id }),
                f.stack.copy(instances = f.stack.instances.map { if (it.id == provider.id) it.copy(enabled = false) else it }),
                f.stack.copy(instances = f.stack.instances.map { if (it.id == provider.id) wrong else it }),
            ) + if (provider.type == "rig.scaffold") emptyList() else listOf(
                f.stack.copy(instances = f.stack.instances.dropLast(1) + provider.copy(id = "duplicate-${provider.id}") + f.brow))
            for ((case, stack) in variants.withIndex()) {
                val recording = RigBuildProfile.recording
                RigBuildProfile.reset()
                try {
                    RigBuildProfile.recording = true
                    assertFailsWith<IllegalArgumentException>("${provider.type}, variant $case") { f.definition(stack).authorContext(f.brow) }
                } finally { RigBuildProfile.recording = recording }
                val profile = RigBuildProfile.snapshot()
                RigBuildProfile.reset()
                assertTrue(profile.keys.none { it.startsWith("context:") || it.startsWith("source fitting:") ||
                    it.startsWith("scaffold:") || it.startsWith("mesh:") }, "Rejected provider reached geometry capture: $profile")
            }
        }
    }

    private fun assertOwnerAdmissionRejectsBeforeGeometry(f: Fixture, stack: RigModifierStack, label: String) {
        val recording = RigBuildProfile.recording
        RigBuildProfile.reset()
        try {
            RigBuildProfile.recording = true
            // Each entry point receives a newly constructed source for this exact changed stack.
            // Reusing the fixture's original definition would silently test the wrong prefix.
            assertFailsWith<IllegalArgumentException>("$label: dependency admission") {
                f.definition(stack).providers(f.brow)
            }
            assertFailsWith<IllegalArgumentException>("$label: direct source capture") {
                f.definition(stack).authorContext(f.brow)
            }
        } finally { RigBuildProfile.recording = recording }
        val profile = RigBuildProfile.snapshot()
        RigBuildProfile.reset()
        assertTrue(profile.keys.none { it.startsWith("context:") || it.startsWith("source fitting:") ||
            it.startsWith("scaffold:") || it.startsWith("mesh:") }, "$label reached geometry capture: $profile")
    }

    @Test fun anonymousAppliedSourceOwnersRejectBeforeCapture() {
        val f = fixture()
        val overlap = listOf(f.brow.targets.first())
        val unknown = ModifierCapability("external.producer", 1, overlap)
        val face = f.stack.instances.single { it.type == "face/fitted" }
        val anonymous = listOf(
            unknown.copy(provides = emptySet()),
            unknown.copy(provides = setOf("unrecognized.capability")),
            unknown.copy(provides = setOf("face/legacy")),
            face.capability(provides = setOf("face/legacy")).copy(id = null, targets = overlap),
        )
        for ((index, owner) in anonymous.withIndex()) {
            assertNull(owner.id)
            assertTrue(owner.targets.any(f.brow.targets::contains))
            val stack = f.stack.copy(appliedCapabilities = listOf(owner))
            assertOwnerAdmissionRejectsBeforeGeometry(f, stack, "anonymous applied producer $index")
        }
    }

    @Test fun namedAppliedOwnersCannotHideAbsentOrForeignScaffolds() {
        val f = fixture()
        val face = f.stack.instances.single { it.type == "face/fitted" }
        val owners = listOf(face.capability().copy(id = "outside-face-owner"),
            ModifierCapability("external.producer", 1, f.brow.targets, id = "outside-unknown-owner"))
        for (owner in owners) {
            assertEquals(f.brow.targets, owner.targets, "Scaffold rejection must not depend on partial target scope")
            val configs = listOf(JsonObject(owner.config - "scaffold"),
                JsonObject(owner.config + ("scaffold" to JsonPrimitive("foreign-scaffold"))))
            for ((index, config) in configs.withIndex()) {
                // The known producer keeps its genuine locked version/package and complete targets.
                // The unknown producer must also be observed rather than hidden by scaffold filtering.
                val hidden = owner.copy(config = config)
                if (owner.type == face.type) assertEquals(face.plugin, hidden.plugin)
                val stack = f.stack.copy(appliedCapabilities = listOf(hidden))
                assertOwnerAdmissionRejectsBeforeGeometry(f, stack, "named ${owner.type} applied owner $index")
            }
        }
    }

    @Test fun disjointAnonymousAndKnownAppliedCapabilityLabelsKeepCapture() {
        val f = fixture()
        val expectedProviders = f.definition().providers(f.brow)
        val expectedCapture = f.definition().authorContext(f.brow)
        for (provides in listOf(emptySet(), setOf("unrecognized.capability"), setOf("face/legacy"))) {
            val disjoint = ModifierCapability("external.producer", 1,
                listOf(ModifierTarget("mesh", "unrelated-source-mesh")), provides = provides)
            assertTrue(disjoint.targets.none(f.brow.targets::contains))
            val stack = f.stack.copy(appliedCapabilities = listOf(disjoint))
            assertEquals(expectedProviders, f.definition(stack).providers(f.brow), "Disjoint anonymous labels: $provides")
            assertEquals(expectedCapture, f.definition(stack).authorContext(f.brow), "Disjoint anonymous labels: $provides")
        }
        // A real applied scaffold/body/head prefix may retain additional capability labels.
        // Its identity, ordering, package, targets and scaffold references remain exactly the captured ones.
        val applied = f.stack.instances.take(3)
        val stack = f.stack.copy(instances = f.stack.instances.drop(3),
            retiredIds = applied.mapTo(linkedSetOf()) { it.id },
            appliedCapabilities = applied.map { owner -> owner.capability(provides =
                if (owner.type == "face/fitted") setOf("face/legacy", "additional.face.label") else emptySet()) })
        assertEquals(expectedProviders, f.definition(stack).providers(f.brow), "Known applied owners retain their extra labels")
        assertEquals(expectedCapture, f.definition(stack).authorContext(f.brow), "Known applied owners retain identical source capture")
    }

    @Test fun otherFiveSingleEyeProfilesAndBilateralSourceRejectAtCandidateBoundary() {
        for (mirror in listOf(false, true)) for (interiors in 0..2) {
            if (!mirror && interiors == 0) continue
            val f = fixture(artwork(mirror, interiors))
            val failure = assertFailsWith<IllegalArgumentException>("mirror=$mirror, interiors=$interiors") { f.context() }
            assertContains(failure.message.orEmpty(), "one left eye",
                message = "Must reject at brow5's source guard, not an unrelated prior binding")
        }
        // This is an explicit bilateral boundary test, not a substitute for running the private DS artwork.
        val bilateral = fixture(artwork(bilateral = true))
        val failure = assertFailsWith<IllegalArgumentException> { bilateral.context() }
        assertContains(failure.message.orEmpty(), "one left eye")
    }

    @Test fun originalTiltedSingleEyeSourceIsExplicitlyUnsupported() {
        val f = fixture(artwork(upright = false))
        val nativeAngles = nativeSourceAngles(f)
        assertTrue(nativeAngles.all { it > 1f }, "The retained original art must truly infer substantial source roll: $nativeAngles")
        val failure = assertFailsWith<IllegalArgumentException> { f.context() }
        assertContains(failure.message.orEmpty(), "upright source measurements")
    }

    @Test fun installedJvmWorkerMatchesRealBrow4AfterUninstallAndColdReinstall(): Unit = runBlocking {
        val f = fixture(tuned = true)
        val expected = WorkspacePreviewBuilder().build(f.document(f.version(4)))
        val outputs = assertNotNull(expected.modifierEvaluation).outputs
        val context = f.context()
        val artifact = assertNotNull(BundledAlgorithmArtifacts.find("brow/fitted", 5))
        assertEquals(artifact.reference, f.brow.plugin)
        val directory = Files.createTempDirectory("brow-fitted-v5-source-")
        val preferences = Preferences.userRoot().node("brow-fitted-v5-source/${UUID.randomUUID()}")
        val store = ModifierPluginStore(directory.resolve("installed"), preferences)
        try {
            val staging = Files.createDirectory(directory.resolve("package"))
            Files.write(staging.resolve("plugin.json"), artifact.bytes)
            artifact.manifest.files.keys.forEach { name -> assertNotNull(artifact.open(name)).use { Files.copy(it, staging.resolve(name)) } }
            repeat(2) {
                val installed = store.install(staging.toString())
                assertEquals(artifact.reference, installed.reference)
                assertRig(outputs[4], ModifierPluginWorkers.evaluate(installed, installed.manifest.algorithms.single(),
                    outputs[3], f.brow, {}, store, context = context, declaredParameters = f.definition().parameters(f.brow)), "installed JVM run $it")
                ModifierPluginWorkers.stop(installed.reference)
                store.uninstall(installed.reference)
            }
        } finally {
            store.snapshot().forEach { ModifierPluginWorkers.stop(it.reference) }
            preferences.removeNode()
            ModifierPluginStore.deleteTree(directory)
        }
    }

    @Test fun savedAndAppliedPrefixKeepsExplicitVersionAndColdExport(): Unit = runBlocking {
        val f = fixture()
        val builder = WorkspacePreviewBuilder()
        val document = f.document()
        val runtime = WorkspaceRuntime<RigPreviewModel>({ builder.build(it) },
            rebuildFrom = { next, previous -> builder.build(next, previous) })
        val model = builder.build(document)
        val expected = RigIrCompiler.compile(model, tileArt = true)
        runtime.install(runtime.state.value.state, "source", document, model)
        val directory = Files.createTempDirectory("brow-fitted-v5-replay-")
        try {
            val repository = ProjectRepository(writeHeadCache = false)
            suspend fun saveAndReopen(name: String, applied: Boolean) {
                val capture = runtime.capture()
                val store = WorkspaceStore(directory.resolve("$name-store"))
                store.persistHistory(capture.projectId, runtime.history())
                val archive = directory.resolve("$name.psd2live")
                repository.save(ProjectSaveCapture(capture.projectId, runtime.history(), JsonObject(emptyMap()), null, store), archive)
                repository.open(archive).use { opened ->
                    val restored = opened.history.head().snapshot
                    val stack = RigModifierStack.fromJson(assertNotNull(restored.rigEdits.modifierStack))
                    if (applied) {
                        assertTrue(stack.instances.none { it.id == f.brow.id })
                        val consumed = stack.appliedCapabilities.single { it.id == f.brow.id }
                        assertEquals(5, consumed.version)
                        assertEquals(f.brow.plugin, consumed.plugin)
                    } else assertEquals(f.stack, stack)
                    val enabled = RigStageCache.enabled
                    try {
                        RigStageCache.enabled = false
                        assertRig(expected, RigIrCompiler.compile(builder.build(restored), tileArt = true), name)
                    } finally { RigStageCache.enabled = enabled }
                }
            }
            saveAndReopen("explicit", false)
            val current = runtime.capture()
            WorkspaceDocumentCommands(runtime).execute(current.projectId, current.state, "modifier_apply",
                listOf(WorkspaceDocumentOperation("modifier_apply", buildJsonObject { put("id", f.brow.id) })), MutationAuthor.USER)
            assertRig(expected, RigIrCompiler.compile(runtime.capture().model, tileArt = true), "applied live output")
            saveAndReopen("applied", true)
        } finally { ModifierPluginStore.deleteTree(directory) }
    }

    @Test fun authoredTopologyUvAndHeadFrameEditsMatchActualBrow4(): Unit = runBlocking {
        val f = fixture()
        val legacyStack = f.version(4)
        val reference = WorkspacePreviewBuilder().build(f.document(legacyStack))
        val before = assertNotNull(reference.modifierEvaluation).outputs[3]
        val context = f.context()
        val definition = RigBrowFittedModifier.sharedFrameDefinition(f.source(legacyStack))
        val projectedContext = definition.authorContext(legacyStack.instances.last()).withUpstream(context.upstream)
        val projected = definition.plugin as ChangingModifierAlgorithmPlugin
        val brow = before.meshes.single { it.name == "brow" }
        val geometry = assertNotNull(brow.geometry)
        val headId = f.stack.instances.first().outputId("shared/deformer:DeformHeadRotation")
        val head = assertIs<Deformer.Rotation>(before.deformers.single { it.id == headId })
        val edits = listOf(
            before.copy(meshes = before.meshes.map { if (it.id != brow.id) it else it.copy(geometry = geometry.copy(
                positions = Floats.wrap(geometry.positions.toArray().also { values -> values[0] += .015f }))) }),
            before.copy(meshes = before.meshes.map { if (it.id != brow.id) it else it.copy(geometry = geometry.copy(
                uvs = Floats.wrap(geometry.uvs.toArray().also { values -> values[0] += .01f }))) }),
            before.copy(meshes = before.meshes.map { if (it.id != brow.id) it else it.copy(geometry = geometry.copy(
                indices = Ints.wrap(geometry.indices.toArray().reversedArray()))) }),
            before.copy(deformers = before.deformers.map { if (it.id == head.id) head.copy(baseAngle = .01f) else it }),
        )
        for ((index, edited) in edits.withIndex()) {
            // These are authored live edits, not permission to capture non-upright SOURCE measurements.
            val expected = projected.evaluate(edited, legacyStack.instances.last(), projectedContext, {})
            assertRig(expected, BrowFittedV5Binding.evaluate(edited, f.brow, context, {}), "authored edit $index")
        }
    }

    @Test fun providersForeignDescendantsAndAliasCollisionsRejectWithoutPublishing(): Unit = runBlocking {
        val f = fixture()
        val reference = WorkspacePreviewBuilder().build(f.document(f.version(4)))
        val outputs = assertNotNull(reference.modifierEvaluation).outputs
        val before = outputs[3]
        val context = f.context()
        val algorithm = BrowFittedV5Binding
        assertRig(outputs[4], algorithm.evaluate(before, f.brow, context, {}), "guard fixture control")
        val brow = before.meshes.single { it.name == "brow" }
        fun reject(input: RigIR = before, captured: ModifierAlgorithmContext = context, reason: String? = null) {
            val failure = assertFailsWith<IllegalArgumentException> { algorithm.evaluate(input, f.brow, captured, {}) }
            if (reason != null) assertContains(failure.message.orEmpty(), reason)
            assertEquals(before, outputs[3], "Rejected changes must not publish partial prefix output")
        }
        val headId = f.stack.instances.first().outputId("shared/deformer:DeformHeadRotation")
        val head = assertIs<Deformer.Rotation>(before.deformers.single { it.id == headId })
        for (provider in context.upstream) {
            reject(captured = context.withUpstream(context.upstream.filterNot { it == provider }))
            val changed = provider.copy(outputAliases = provider.outputAliases + ("shared/deformer:foreign" to "foreign-provider-output"))
            reject(captured = context.withUpstream(context.upstream.map { if (it == provider) changed else it }))
        }
        val changes = algorithm.changes(before, f.brow, context, {})
        val carrier = assertNotNull(changes.deformers.updated.firstOrNull(), "The guard fixture must change an existing brow-local carrier").before
        assertNotEquals(head.id, carrier.id, "This is not evidence of a shared-head frame change")
        val foreign = brow.copy(id = "foreign-mesh", name = "Foreign child", parent = carrier.id)
        reject(before.copy(meshes = before.meshes + foreign), reason = "uncaptured child")
        val foreignFrame = Deformer.Rotation("foreign-frame", "Foreign frame", carrier.id, null, 0f, null)
        reject(before.copy(deformers = before.deformers + foreignFrame), reason = "uncaptured deformer")

        // No new carrier is introduced by this cohort. Instead collide two genuine existing
        // canonical outputs in the provider snapshot and verify the worker rejects the stale live input.
        val scaffold = f.stack.instances.first()
        val other = before.deformers.first { it.id != head.id &&
            scaffold.localOutputName(it.id)?.startsWith("shared/deformer:") == true }
        val colliding = scaffold.copy(outputAliases = mapOf("shared/deformer:DeformHeadRotation" to other.id))
        val collisionStack = f.stack.copy(instances = listOf(colliding) + f.stack.instances.drop(1))
        reject(captured = f.context(collisionStack), reason = "cannot alias distinct canonical carriers to one output")
    }

    @Test fun alternateProviderAliasCannotDuplicateUnchangedCanonicalHead(): Unit = runBlocking {
        for (aliased in listOf(false, true)) {
            val plain = fixture()
            val canonical = "shared/deformer:DeformHeadRotation"
            val selected = if (!aliased) plain.stack else plain.stack.copy(instances = plain.stack.instances.map { entry ->
                if (entry.type != "rig.scaffold") entry else entry.copy(outputAliases = mapOf(canonical to "brow5-owned-head"))
            })
            val f = plain.copy(stack = selected)
            val reference = WorkspacePreviewBuilder().build(f.document(f.version(4)))
            val outputs = assertNotNull(reference.modifierEvaluation).outputs
            val before = outputs[3]
            val context = f.context()
            val scaffold = f.stack.instances.single { it.type == "rig.scaffold" }
            val face = f.stack.instances.single { it.type == "face/fitted" }
            val head = assertIs<Deformer.Rotation>(before.deformers.single { it.id == scaffold.outputId(canonical) })
            val changes = BrowFittedV5Binding.changes(before, f.brow, context, {})
            assertTrue(changes.deformers.updated.none { it.before.id == head.id },
                "This upright control preserves the head; canonical ownership is unique regardless of motion")
            assertRig(outputs[4], BrowFittedV5Binding.evaluate(before, f.brow, context, {}),
                "single actual owner remains accepted; aliased=$aliased")

            // This spelling was formerly considered recognized even though BindingMerge chose the
            // existing scaffold-owned head first. Even an unchanged head cannot have two live owners.
            val foreign = Deformer.Rotation(face.outputId(canonical), "Alternate-owner impostor", head.id, null, 0f, null)
            assertNotEquals(head.id, foreign.id)
            assertTrue(before.deformers.none { it.id == foreign.id })
            val forged = before.copy(deformers = before.deformers + foreign)
            val failure = assertFailsWith<IllegalArgumentException>("Two live provider aliases for one canonical frame; aliased=$aliased") {
                BrowFittedV5Binding.evaluate(forged, f.brow, context, {})
            }
            assertContains(failure.message.orEmpty(), "multiple live aliases for canonical carrier: DeformHeadRotation")
            assertEquals(before, outputs[3], "Rejected alternate-owner frame must not publish a partial prefix")
            assertEquals(foreign, forged.deformers.last(), "Rejected evaluation must not consume the foreign frame")
        }
    }
}
