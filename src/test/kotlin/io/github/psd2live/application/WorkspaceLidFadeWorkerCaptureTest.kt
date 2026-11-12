package io.github.psd2live.application

import io.github.psd2live.algorithms.binding.LidFadeInputs
import io.github.psd2live.core.*
import io.github.psd2live.core.fit.EyeModules
import io.github.psd2live.core.modifier.*
import io.github.psd2live.core.recipe.*
import io.github.psd2live.format.compile.RigIrBinary
import io.github.psd2live.format.model.*
import io.github.psd2live.project.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.umamo.format.art.*
import java.nio.file.Files
import java.util.UUID
import java.util.prefs.Preferences
import kotlin.test.*

/** Compare frozen full-scaffold capture and narrow source capture through unchanged packaged workers. */
class WorkspaceLidFadeWorkerCaptureTest {
    private data class Shape(val id: String, val tag: SemanticTag, val side: Side,
                             val x: Int, val y: Int, val width: Int, val height: Int, val role: PartRole? = null)

    /** Single-eye rectangles need aligned upper/lower centers to be upright under the source estimator. */
    private fun artwork(mirror: Boolean = false, upright: Boolean = true): WorkspaceDocument {
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
                LayerBounds(shape.x, shape.y, shape.width, shape.height), if (shape.role == PartRole.LID) .6f else if (shape.role == PartRole.SHADOW) .4f else 1f, false, LayerBlend.Normal, ChannelMask.ALL,
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


    private fun document(version: Int, wide: Boolean): WorkspaceDocument {
        val art = artwork(mirror = version == 3, upright = !wide)
        val config = WorkspaceInitialPreset(clothing = false)
            .configure(WorkspacePresetBaseline.configure(art.config(), "modern"))
            .copy(rigEdits = RigEditOverlay(modifierStack = RigModifierStack().toJson()))
        val document = art.copy(settings = WorkspaceSettingsCodec.encode(config), rigEdits = config.rigEdits)
        val model = PSD2LivePipeline().apply { deferRuntimeBundles = true }.buildPreview(document.source, document.config())
        val targets = model.rig.puppet.drawables.filter { it.mesh != null && it.id.raw in model.rig.ownLayerIdByDrawableId }
        val versions = linkedMapOf("rig.scaffold" to 2, "body/legacy" to 2, "face/fitted" to 3, "eye/fitted" to 5)
        if (wide) versions["eye.wide"] = 3
        versions["eye.lid-fade"] = version
        val planned = WorkspaceModifierPlan.plan(model, RigModifierStack(), buildJsonObject {
            put("group", "modern-standard")
            putJsonArray("targets") { targets.forEach { addJsonObject { put("kind", "mesh"); put("id", it.id.raw) } } }
        }, versions.keys.drop(1)).first
        val stack = planned.copy(instances = planned.instances.map { old ->
            val pinned = versions.getValue(old.type)
            val extra = when (old.type) {
                "rig.scaffold" -> buildJsonObject {
                    val corrections = WorkspaceSettingsCodec.decodeRecipe(old.config["corrections"])
                    put("corrections", WorkspaceSettingsCodec.encodeRecipe(corrections.copy(modern = true, moves = corrections.moves + mapOf(
                        "eye.L" to mapOf("upper" to FeatureMove(.25f, -.4f)),
                        "eye.R" to mapOf("upper" to FeatureMove(.25f, -.4f))))))
                }
                "eye/fitted" -> buildJsonObject {
                    putJsonObject("values") { put("lowerShare", 12f); put("roundness", 115f); put("lashSquash", 75f) }
                    putJsonObject("tuning") { put("gazeX", 13f) }
                }
                else -> JsonObject(emptyMap())
            }
            old.copy(version = pinned, config = JsonObject(old.config + extra),
                plugin = assertNotNull(BuiltinModifierPlugins.reference(old.type, pinned)))
        })
        assertEquals(versions, stack.instances.associate { it.type to it.version })
        val corrections = WorkspaceSettingsCodec.decodeRecipe(stack.instances.single { it.type == "rig.scaffold" }.config["corrections"])
        assertEquals(FeatureMove(.25f, -.4f), corrections.moves.getValue(if (version == 3) "eye.R" else "eye.L").getValue("upper"))
        val eye = stack.instances.single { it.type == "eye/fitted" }
        // Fitted eyes replace blinkDepth; only the provider's exposed tuning fields may be authored.
        assertEquals(13f, RigLegacyBindings.tuning(eye, RigTuning()).gazeX)
        assertEquals(mapOf("lowerShare" to 12f, "roundness" to 115f, "lashSquash" to 75f), RigLegacyBindings.values(eye))
        return document.copy(rigEdits = document.rigEdits.copy(modifierStack = stack.toJson()))
    }

    private fun assertContext(expected: ModifierAlgorithmContext, actual: ModifierAlgorithmContext, label: String) {
        assertEquals(expected.values, actual.values, "$label values")
        assertEquals(expected.values.toString(), actual.values.toString(), "$label literal JSON and order")
        assertEquals(expected.upstream, actual.upstream, "$label upstream")
        assertTrue(expected.resources.isEmpty(), "$label frozen capture has no resources")
        assertTrue(actual.resources.isEmpty(), "$label narrow capture has no resources")
        val frozenInputs = LidFadeInputs.decode(expected)
        val narrowInputs = LidFadeInputs.decode(actual)
        assertEquals(frozenInputs.keys.toList(), narrowInputs.keys.toList(), "$label target order")
        frozenInputs.forEach { (id, frozen) ->
            val narrow = narrowInputs.getValue(id)
            assertEquals(frozen.crease.toRawBits(), narrow.crease.toRawBits(), "$label crease raw bits/$id")
            assertEquals(frozen.opacity.toRawBits(), narrow.opacity.toRawBits(), "$label opacity raw bits/$id")
        }
        assertEquals(expected.resources.keys.toList(), actual.resources.keys.toList(), "$label resource order")
        expected.resources.forEach { (key, resource) ->
            assertEquals(resource.digest, actual.resources.getValue(key).digest, "$label digest/$key")
            assertContentEquals(resource.read(), actual.resources.getValue(key).read(), "$label bytes/$key")
        }
    }

    /** Same-input capture/worker comparisons retain every transport byte, including map order. */
    private fun assertIr(expected: RigIR, actual: RigIR, label: String) {
        val differences = irDifferences(expected, actual, compareMapOrder = true)
        assertTrue(differences.isEmpty(), "$label full IR: ${differences.joinToString()}")
        val before = RigIrBinary.encode(expected)
        val after = RigIrBinary.encode(actual)
        assertTrue(before.contentEquals(after), "$label full binary IR: ${byteDifference(before, after)}")
    }

    /** Applying a prefix rebuilds the texture-binding map, not the data or ordering of any IR list. */
    private fun assertAppliedIr(expected: RigIR, actual: RigIR, label: String) {
        val values = irDifferences(expected, actual, compareMapOrder = false)
        assertTrue(values.isEmpty(), "$label exact fields and raw bits: ${values.joinToString()}")
        val before = RigIrBinary.encode(expected)
        val after = RigIrBinary.encode(actual)
        if (before.contentEquals(after)) return
        val order = irDifferences(expected, actual, compareMapOrder = true)
        assertTrue(order.size == 1 && order.single().startsWith("map order IR.textures.bindings:"),
            "$label unexplained binary difference: ${byteDifference(before, after)}; ${order.joinToString()}")
        // Captured pre/post-apply bytes differ only in this map's order. Reordering just that map must
        // recover the entire original binary, so no second serialization difference can be hidden.
        val reordered = actual.copy(textures = actual.textures.copy(bindings =
            expected.textures.bindings.keys.associateWith { actual.textures.bindings.getValue(it) }))
        assertTrue(before.contentEquals(RigIrBinary.encode(reordered)),
            "$label contains a binary difference beyond texture-binding insertion order")
        println("$label: ${order.single()}")
    }

    private fun byteDifference(before: ByteArray, after: ByteArray): String {
        val first = (0 until minOf(before.size, after.size)).firstOrNull { before[it] != after[it] }
        return if (first == null) "lengths ${before.size}/${after.size}" else
            "first differing byte $first=${before[first].toInt() and 255}/${after[first].toInt() and 255}, lengths ${before.size}/${after.size}"
    }

    /** Bounded diagnostics never print whole models or arrays, and never approximate numeric data. */
    private fun irDifferences(expected: RigIR, actual: RigIR, compareMapOrder: Boolean): List<String> {
        val differences = mutableListOf<String>()
        fun visit(path: String, left: Any?, right: Any?) {
            if (differences.size >= 12 || left === right) return
            if (left == null || right == null) { if (left != right) differences += path; return }
            when {
                left is Float && right is Float -> if (left.toRawBits() != right.toRawBits()) differences += "$path float bits"
                left is Double && right is Double -> if (left.toRawBits() != right.toRawBits()) differences += "$path double bits"
                left is FloatArray && right is FloatArray -> {
                    if (left.size != right.size) differences += "$path size"
                    else if (left.indices.any { left[it].toRawBits() != right[it].toRawBits() }) differences += "$path float bits"
                }
                left is IntArray && right is IntArray -> if (!left.contentEquals(right)) differences += path
                left is ByteArray && right is ByteArray -> if (!left.contentEquals(right)) differences += path
                left is Map<*, *> && right is Map<*, *> -> {
                    if (left.keys != right.keys) { differences += "$path keys"; return }
                    if (compareMapOrder && left.keys.toList() != right.keys.toList())
                        differences += "map order $path: ${left.keys.take(8)} -> ${right.keys.take(8)}"
                    for (key in left.keys) visit("$path[$key]", left[key], right[key])
                }
                left is List<*> && right is List<*> -> {
                    if (left.size != right.size) { differences += "$path size"; return }
                    for (index in left.indices) visit("$path[$index]", left[index], right[index])
                }
                left.javaClass != right.javaClass -> differences += "$path type"
                left.javaClass.name.startsWith("io.github.psd2live.format.model.") && left !is Enum<*> -> {
                    for (field in left.javaClass.declaredFields.filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }) {
                        field.isAccessible = true
                        visit("$path.${field.name}", field.get(left), field.get(right))
                    }
                }
                left != right -> differences += path
            }
        }
        visit("IR", expected, actual)
        return differences
    }

    @Test fun appliedComparisonPermitsOnlyProvenTextureBindingOrder() {
        val expected = RigIR(Canvas(1f, 1f, originX = 0f),
            parameters = listOf(Parameter("ParamA", "A", 0f, 1f, 0f), Parameter("ParamB", "B", 0f, 1f, 0f)),
            textures = Textures(bindings = linkedMapOf("mesh-a" to 0, "mesh-b" to 1)),
            restPose = linkedMapOf("ParamA" to 0f, "ParamB" to 1f))
        val reordered = expected.copy(textures = expected.textures.copy(bindings = linkedMapOf("mesh-b" to 1, "mesh-a" to 0)))
        assertAppliedIr(expected, reordered, "Allowed texture map order")
        assertFailsWith<AssertionError> { assertIr(expected, reordered, "Same-input binary order remains strict") }
        for (changed in listOf(
            expected.copy(canvas = expected.canvas.copy(originX = -0f)),
            expected.copy(parameters = expected.parameters.reversed()),
            expected.copy(textures = expected.textures.copy(bindings = linkedMapOf("mesh-a" to 1, "mesh-b" to 1))),
            expected.copy(restPose = linkedMapOf("ParamB" to 1f, "ParamA" to 0f)),
        )) assertFailsWith<AssertionError> { assertAppliedIr(expected, changed, "Unapproved difference") }
    }

    @Test fun bothVersionsPreserveInstalledWorkerOutputsForSourceAndAuthoredOpacity(): Unit = runBlocking {
        for (version in listOf(2, 3)) for (wide in listOf(false, true)) {
            val document = document(version, wide)
            val model = WorkspacePreviewBuilder().build(document)
            val evaluation = assertNotNull(model.modifierEvaluation)
            assertTrue(evaluation.entries.all { it.status == ModifierStatus.ACTIVE }, "All providers execute/$version/$wide")
            val stack = RigModifierStack.fromJson(assertNotNull(document.rigEdits.modifierStack))
            val owner = stack.instances.single { it.type == "eye.lid-fade" }
            val source = RigModifierSource.of(model)
            val definition = RigLidFadeModifier.definition(source, modern = version == 3)
            val observed = assertNotNull(evaluation.readAlgorithms[owner.id]).context
            val old = frozenContext(source, owner).withUpstream(observed.upstream)
            val next = definition.authorContext(owner).withUpstream(observed.upstream)
            assertContext(old, next, "capture/$version/$wide")
            assertContext(observed, next, "pipeline/$version/$wide")
            val measured = LidFadeInputs.decode(old)
            assertEquals(setOf(.6f, .4f), measured.values.map { it.opacity }.toSet(), "Exercise both lid and shadow")
            assertEquals(wide, next.upstream.any { it.type == "eye.wide" })
            val index = stack.index(owner.id)
            val before = evaluation.outputs[index - 1]
            val pipeline = evaluation.outputs[index]
            assertNotEquals(before, pipeline, "Real fade must change the IR")
            val host = definition.plugin as ReadingModifierAlgorithmPlugin
            val root = Files.createTempDirectory("lid-fade-capture-$version-")
            val preferences = Preferences.userRoot().node("lid-fade-capture/" + UUID.randomUUID())
            val store = ModifierPluginStore(root.resolve("installed"), preferences)
            try {
                val artifact = assertNotNull(BundledAlgorithmArtifacts.find(owner.type, version))
                BundledAlgorithmArtifacts.retain(store, listOf(artifact))
                val installed = store.snapshot().single()
                assertEquals(owner.plugin, installed.reference, "Original package identity")
                fun compare(input: RigIR, label: String): RigIR {
                    val expected = host.evaluate(input, owner, old, {})
                    assertIr(expected, host.evaluate(input, owner, next, {}), "$label host")
                    for ((mode, context) in listOf("frozen" to old, "narrow" to next)) {
                        val result = ModifierPluginWorkers.evaluate(installed, installed.manifest.algorithms.single(),
                            input, owner, {}, store, context = context, declaredParameters = definition.parameters(owner))
                        assertIr(expected, result, "$label $mode installed/$version/$wide")
                    }
                    return expected
                }
                assertIr(pipeline, compare(before, "original predecessor"), "Pipeline output")
                val edited = before.copy(meshes = before.meshes.map { mesh ->
                    if (mesh.id !in measured) mesh else mesh.copy(opacity = .31f,
                        channels = mesh.channels + (Channel.OPACITY to KeyGrid<ChannelValue>(
                            listOf(KeyAxis(measured.getValue(mesh.id).parameter, Floats.values(0f, 1f))),
                            listOf(KeyCell(Ints.values(0), ChannelValue.Scalar(.12f)),
                                KeyCell(Ints.values(1), ChannelValue.Scalar(.31f))))))
                })
                assertNotEquals(before, edited)
                compare(edited, "authored base and channel opacity")
                val morphParameter = "ParamAuthoredLidOpacity"
                val morphed = before.copy(parameters = before.parameters + Parameter(morphParameter, morphParameter, 0f, 1f, 0f),
                    meshes = before.meshes.map { mesh ->
                        if (mesh.id !in measured) mesh else mesh.copy(shapes = mesh.shapes + BlendBinding(
                            morphParameter, Floats.values(0f, 1f), 0, listOf(null,
                                MeshShape(Floats.of(FloatArray(assertNotNull(mesh.geometry).positions.size)),
                                    mesh.drawOrder.toFloat(), .23f, Rgb.White, Rgb.Black))))
                    })
                val morphResult = compare(morphed, "existing morph opacity")
                assertTrue(morphResult.meshes.filter { it.id in measured }.all { it.shapes.isNotEmpty() })
                val repeated = owner.copy(id = "repeated-fade")
                val upstream = next.upstream + ModifierUpstreamCapability(owner.type, owner.type, version,
                    owner.targets, owner.id, owner.config)
                for (context in listOf(old, next)) {
                    val repeatedContext = context.withUpstream(upstream)
                    assertIr(pipeline, host.evaluate(pipeline, repeated, repeatedContext, {}), "Repeated host is a no-op")
                    assertIr(pipeline, ModifierPluginWorkers.evaluate(installed, installed.manifest.algorithms.single(),
                        pipeline, repeated, {}, store, context = repeatedContext,
                        declaredParameters = definition.parameters(repeated)), "Repeated installed fade is a no-op")
                }
            } finally {
                store.snapshot().forEach { ModifierPluginWorkers.stop(it.reference) }
                try { preferences.removeNode() } finally { ModifierPluginStore.deleteTree(root) }
            }
        }
    }

    @Test fun applyingWidePrefixReconstructsIdenticalFadeContextAndOutput(): Unit = runBlocking {
        for (version in listOf(2, 3)) {
            val original = document(version, wide = true)
            val model = WorkspacePreviewBuilder().build(original)
            val stack = RigModifierStack.fromJson(assertNotNull(original.rigEdits.modifierStack))
            val wide = stack.instances.single { it.type == "eye.wide" }
            val owner = stack.instances.single { it.type == "eye.lid-fade" }
            val expected = RigIrCompiler.compile(model)
            val applied = WorkspaceModifierEdits.apply(WorkspaceDocumentOperation("modifier_apply",
                buildJsonObject { put("id", wide.id) }), original, model)
            val rebuilt = WorkspacePreviewBuilder().build(applied)
            assertAppliedIr(expected, RigIrCompiler.compile(rebuilt), "Applied wide prefix/$version")
            val evaluation = assertNotNull(rebuilt.modifierEvaluation)
            assertTrue(evaluation.entries.all { it.status == ModifierStatus.ACTIVE })
            val source = RigModifierSource.of(rebuilt)
            val observed = assertNotNull(evaluation.readAlgorithms[owner.id]).context
            val frozen = frozenContext(source, owner).withUpstream(observed.upstream)
            val next = RigLidFadeModifier.definition(source, modern = version == 3).authorContext(owner)
                .withUpstream(observed.upstream)
            assertContext(frozen, next, "Applied prefix frozen capture/$version")
            assertContext(observed, next, "Applied prefix pipeline/$version")
            assertTrue(next.upstream.any { it.type == "eye.wide" }, "Applied widening remains a provider")
            val remaining = RigModifierStack.fromJson(assertNotNull(applied.rigEdits.modifierStack))
            val index = remaining.index(owner.id)
            val before = if (index == 0) RigModifierPipeline.input(rebuilt) else evaluation.outputs[index - 1]
            val output = evaluation.outputs[index]
            val definition = RigLidFadeModifier.definition(source, modern = version == 3)
            val host = definition.plugin as ReadingModifierAlgorithmPlugin
            val root = Files.createTempDirectory("lid-fade-applied-$version-")
            val preferences = Preferences.userRoot().node("lid-fade-applied/" + UUID.randomUUID())
            val store = ModifierPluginStore(root.resolve("installed"), preferences)
            try {
                val artifact = assertNotNull(BundledAlgorithmArtifacts.find(owner.type, version))
                BundledAlgorithmArtifacts.retain(store, listOf(artifact))
                val installed = store.snapshot().single()
                assertEquals(owner.plugin, installed.reference)
                for (context in listOf(frozen, next)) {
                    assertIr(output, host.evaluate(before, owner, context, {}), "Applied prefix host/$version")
                    assertIr(output, ModifierPluginWorkers.evaluate(installed, installed.manifest.algorithms.single(),
                        before, owner, {}, store, context = context, declaredParameters = definition.parameters(owner)),
                        "Applied prefix installed/$version")
                }
            } finally {
                store.snapshot().forEach { ModifierPluginWorkers.stop(it.reference) }
                try { preferences.removeNode() } finally { ModifierPluginStore.deleteTree(root) }
            }
        }
    }

    /** Frozen original host capture and target filtering, independent of lidFadeSourceInputs. */
    private fun frozenContext(source: RigModifierSource, owner: RigModifierInstance): ModifierAlgorithmContext {
        val scaffold = source.provider(owner)
        val baseId = owner.config.getValue("base").jsonPrimitive.content
        val base = source.activeBefore(owner).single { it.id == baseId && it.type == "eye/fitted" }
        val corrections = WorkspaceSettingsCodec.decodeRecipe(scaffold.config["corrections"])
        val recipe = RigRecipe(true,
            bases = mapOf("eye" to BaseChoice(base.type, base.version, RigLegacyBindings.values(base))),
            moves = corrections.moves, eyeParts = corrections.eyeParts)
        val native = source.sourceConfig().copy(recipe = recipe,
            rigTuning = RigLegacyBindings.tuning(base, RigTuning()), headTurnStrength = 1f)
        val context = RigBuilder.rigContext(source.analysis, native, source.meshCache)
        val eyes = context.fittedEyes(native)?.rigs.orEmpty()
        val layers = context.analysis.layers.associateBy { it.source.id.raw }
        val targets = owner.targets.mapNotNull { target ->
            val layerId = source.layersByMesh.getValue(target.id)
            val layer = layers[layerId] ?: return@mapNotNull null
            val semantic = layer.semantic
            if (semantic.tag != SemanticTag.EYELASH || (semantic.role != PartRole.LID && semantic.role != PartRole.SHADOW))
                return@mapNotNull null
            val override = native.layerOverrides[layerId]
            if ((override?.type ?: semantic.type) != LayerType.PRESET &&
                (override?.parameter ?: semantic.parameter).isNotBlank()) return@mapNotNull null
            val eye = eyes[semantic.side] ?: return@mapNotNull null
            target.id to LidFadeInputs(EyeModules.open(semantic.side).raw, eye.crease, layer.source.opacity.coerceIn(0f, 1f))
        }.toMap()
        return LidFadeInputs.encode(targets)
    }
}
