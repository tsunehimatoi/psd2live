package io.github.psd2live.tools

import io.github.psd2live.core.modifier.*
import io.github.psd2live.core.MotionClip
import io.github.psd2live.core.MotionCurve
import io.github.psd2live.core.MotionKey
import io.github.psd2live.core.MotionData
import io.github.psd2live.core.RigPhysicsEdit
import io.github.psd2live.core.PhysicsInput
import io.github.psd2live.core.PhysicsOutput
import io.github.psd2live.core.sim.*
import io.github.psd2live.format.model.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.prefs.Preferences
import kotlin.test.*
import kotlinx.serialization.json.*

/** Opt-in real installation and process evaluation of independently built bundled packages. */
class PackagedPhysicsPluginTool {
    @Test fun scaffoldArchivesPreserveBothHierarchyVersionsThroughWorkers() {
        requireTools()
        val input = testInput()
        val material = io.github.psd2live.targets.cubism.PuppetIr.toPuppet(input)
        val frame = org.umamo.runtime.model.DeformerId("frame")
        val part = org.umamo.runtime.model.PartId("body-part")
        val template = material.copy(
            parts = listOf(org.umamo.runtime.model.Part(part, "Body", material.drawables.map { org.umamo.runtime.model.OrgChild.Drawable(it.id) })),
            deformers = listOf(org.umamo.runtime.model.Deformer.Warp(frame, "Frame", null, part, 1, 1, true,
                org.umamo.runtime.model.KeyformGrid(emptyList(), listOf(org.umamo.runtime.model.KeyformCell(IntArray(0),
                    org.umamo.runtime.model.WarpLatticeForm(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f))))))),
            drawables = material.drawables.map { it.copy(parentDeformerId = frame) },
            rootChildren = listOf(org.umamo.runtime.model.OrgChild.Part(part)))
        val context = io.github.psd2live.algorithms.binding.ScaffoldAlgorithm.capture(template, material, mapOf("body" to "body"))
        val plugin = io.github.psd2live.algorithms.binding.ScaffoldAlgorithm()
        for (revision in 1..2) {
            val definition = RigModifierDefinition("rig.scaffold", revision, exclusiveFamily = "rig.scaffold",
                composableKinds = setOf("mesh", "part", "deformer", "hierarchy"),
                evaluate = plugin::evaluate, plugin = plugin, authorContext = { context })
            verifyPackage("rig.scaffold", definition, JsonObject(emptyMap()), "binding", "plugin-scaffold-$revision", input)
        }
    }

    @Test fun installEvaluateReinstall() = verifyPackage("physics", PhysicsAlgorithmPlugin.definition,
        RigPhysicsEdit("draft", "Pendulum", listOf(PhysicsInput("input")), listOf(PhysicsOutput("output"))).toJson())

    @Test fun motionArchiveReplaysWithoutApplicationClasses() = verifyPackage("motion", MotionAlgorithmPlugin.definition,
        MotionData.toJson(MotionClip("draft", "Turn", curves = listOf(MotionCurve("input", listOf(MotionKey(0f, 0f), MotionKey(1f, 1f)))),
            parameterRoles = mapOf("LipSync" to listOf("input")))))

    @Test fun swingArchiveReplaysWithoutApplicationClasses() = verifyPackage("swing", SwingAlgorithmPlugin.definition,
        io.github.psd2live.core.RigSwingEdit.single("draft", "Swing", io.github.psd2live.core.SwingKind.LATERAL,
            listOf("body"), listOf("angle")).toJson())

    @Test fun simulationArchiveWritesBakedModesAndPhysics() {
        requireTools()
        val input = testInput()
        val edit = RigSimEdit("sim", "Cloth", SimKind.CLOTH, listOf("body"), inputs = listOf(PhysicsInput("ParamAngleX")))
        val weights = buildJsonArray { addJsonObject {
            put("op", "vertex_group_put"); put("target", "mesh:body"); put("name", "pin"); put("kind", "pin")
            put("weights", JsonArray(listOf(JsonPrimitive(1f), JsonPrimitive(1f), JsonPrimitive(0f))))
        } }
        val config = JsonObject(edit.toJson() + ("automatic_weights" to weights))
        val weighted = RigSimulationWeights.apply(io.github.psd2live.targets.cubism.PuppetIr.toPuppet(input), config)
        val axis = SimBakedAxis("mode", floatArrayOf(-1f, 0f, 1f), mapOf("body" to listOf(
            floatArrayOf(0f, 0f, 0f, 0f, -2f, 0f), FloatArray(6), floatArrayOf(0f, 0f, 0f, 0f, 2f, 0f))))
        val bake = SimBakeResult(SimBake.fingerprint(weighted, edit),
            mapOf("body" to 3), emptyList(), listOf(SimBakedMode(axis, 2f, 1f)),
            RigPhysicsEdit("pendulum", "Cloth", listOf(PhysicsInput("ParamAngleX")), listOf(PhysicsOutput("mode"))))
        verifyPackage("simulation", SimulationAlgorithmPlugin.definition,
            JsonObject(edit.copy(bake = bake).toJson() + ("automatic_weights" to weights)))
    }

    @Test fun expressionArchiveReceivesSourceInputsAndSharedParameterApproval() {
        val plugin = io.github.psd2live.algorithms.binding.ExpressionAlgorithm()
        val parameters = listOf(Parameter("Expression", "Expression", 0f, 1f, 0f))
        val context = ModifierAlgorithmContext(buildJsonObject {
            put("schema", "expression/1")
            putJsonObject("layers") { putJsonObject("layer") { put("parameter", "Expression") } }
            putJsonObject("meshes") { put("body", "layer") }
            putJsonObject("opacity") { put("layer", 1f) }
            put("chain_owners", JsonArray(emptyList()))
        }, mapOf("parameters" to ModifierAlgorithmResource(io.github.psd2live.format.compile.RigIrBinary.encode(RigIR(Canvas(1f, 1f), parameters)))))
        val definition = RigModifierDefinition("expression.toggles", 1, requires = setOf("rig.scaffold"),
            exclusiveFamily = "expression.toggles", parameters = { parameters },
            composableKinds = setOf("mesh", "part", "deformer", "hierarchy", "relations", "parameter", "physics"),
            evaluate = plugin::evaluate, plugin = plugin, authorContext = { context })
        verifyPackage("expression.toggles", definition, JsonObject(emptyMap()), "binding")
    }

    @Test fun seeThroughArchivesEvaluateBothFeatureCopiesAndInvertedWindows() {
        requireTools()
        for (area in listOf("eye", "brow")) {
            val plugin = io.github.psd2live.algorithms.binding.SeeThroughAlgorithm()
            val context = ModifierAlgorithmContext(buildJsonObject {
                put("schema", "see-through/1"); put("eyes", area == "eye")
                put("name_pattern", "{meshName} (through bangs)"); put("draw_order_step", 10); put("inverted_masks", true)
                put("earlier_owners", JsonArray(emptyList()))
                putJsonArray("candidates") { addJsonObject {
                    put("original", "body"); put("eyes", area == "eye"); put("bangs", JsonArray(listOf(JsonPrimitive("bang"))))
                } }
            })
            val type = "$area.see-through"
            val definition = RigModifierDefinition(type, 1, requires = setOf("rig.scaffold"),
                composableKinds = setOf("mesh", "part", "deformer", "hierarchy", "relations", "parameter"),
                evaluate = plugin::evaluate, plugin = plugin, authorContext = { context })
            val base = testInput()
            val input = base.copy(meshes = base.meshes + base.meshes.single().copy(id = "bang", drawOrder = 600f),
                rootChildren = listOf(ChildRef.MeshRef("body"), ChildRef.MeshRef("bang")))
            verifyPackage(type, definition, JsonObject(emptyMap()), "binding", "plugin-$area", input)
        }
    }

    @Test fun skinClipArchiveAddsOnlyCapturedMasks() {
        requireTools()
        val plugin = io.github.psd2live.algorithms.binding.SkinClipAlgorithm()
        val context = ModifierAlgorithmContext(buildJsonObject {
            put("schema", "skin-clip/1")
            putJsonArray("faces") { addJsonObject { put("id", "face"); put("bounds", JsonArray(listOf(0, 0, 10, 30).map(::JsonPrimitive))) } }
            putJsonObject("overlays") { put("body", JsonArray(listOf(1, 1, 3, 3).map(::JsonPrimitive))) }
            put("already_active", JsonArray(emptyList()))
        })
        val definition = RigModifierDefinition("face.skin-clip", 1, requires = setOf("rig.scaffold"),
            composableKinds = setOf("mesh", "part", "deformer", "hierarchy", "relations", "parameter"),
            evaluate = plugin::evaluate, plugin = plugin, authorContext = { context })
        val base = testInput()
        verifyPackage("face.skin-clip", definition, JsonObject(emptyMap()), "binding", "plugin-skin",
            base.copy(meshes = base.meshes + base.meshes.single().copy(id = "face")))
    }

    private fun evaluate(definition: RigModifierDefinition, input: RigIR, instance: RigModifierInstance): RigIR {
        val plugin = definition.plugin
        return if (plugin is ContextualModifierAlgorithmPlugin) plugin.evaluate(input, instance, definition.authorContext(instance)) {}
            else plugin.evaluate(input, instance) {}
    }

    private fun testInput() = RigIR(Canvas(64f, 64f), listOf(Parameter("input", "Input", -1f, 1f, 0f),
        Parameter("output", "Output", -1f, 1f, 0f), Parameter("ParamAngleX", "Head X", -30f, 30f, 0f)),
        meshes = listOf(Mesh("body", "Body", null, geometry = MeshGeometry(
            Floats.values(0f, 0f, 10f, 0f, 0f, 30f), Floats.values(0f, 0f, 1f, 0f, 0f, 1f), Ints.values(0, 1, 2)), offsets = null)))

    private fun verifyPackage(type: String, definition: RigModifierDefinition, config: JsonObject, directory: String = type, packageDirectory: String = "plugin", input: RigIR = testInput()) {
        requireTools()
        val root = Files.createTempDirectory("packaged-algorithm-")
        val preferences = Preferences.userRoot().node("plugin-package-test/${UUID.randomUUID()}")
        val store = ModifierPluginStore(root.resolve("installed"), preferences)
        try {
            val built = Path.of("algorithms/$directory/build/$packageDirectory").toAbsolutePath()
            val source = Files.createDirectory(root.resolve("package"))
            java.util.jar.JarFile(built.resolve("algorithm.jar").toFile()).use { jar ->
                assertFalse(jar.entries().asSequence().any { entry -> (type !in setOf("swing", "simulation", "expression.toggles", "eye.see-through", "brow.see-through", "face.skin-clip", "rig.scaffold") && entry.name.startsWith("org/umamo/")) ||
                    listOf("application", "ui", "project", "history").any { entry.name.startsWith("io/github/psd2live/$it/") } })
            }
            Files.copy(built.resolve("algorithm.jar"), source.resolve("algorithm.jar"))
            val manifest = Json.parseToJsonElement(Files.readString(built.resolve("plugin.json"))).jsonObject
            Files.writeString(source.resolve("plugin.json"), JsonObject(manifest + ("version" to JsonPrimitive("0.9.0"))).toString())
            val entry = store.install(source.toString())
            val instance = RigModifierInstance("instance1", type, definition.version, listOf(ModifierTarget("mesh", "body")),
                config = config, outputAliases = mapOf(type to "retained-output"), plugin = entry.reference)
            if (type == "swing") {
                val model = io.github.psd2live.targets.cubism.PuppetIr.toPuppet(input)
                val wrapped = io.github.psd2live.core.SwingWrapping.wrap(model, io.github.psd2live.core.RigSwingEdit.fromJson(config))
                val replayed = wrapped.commands.fold(model) { current, command -> io.github.psd2live.core.RigAuthoringJournal.apply(current, command) }
                assertEquals(io.github.psd2live.targets.cubism.PuppetIr.toIr(replayed), io.github.psd2live.targets.cubism.PuppetIr.toIr(wrapped.model))
            }
            val expected = evaluate(definition, input, instance)
            if (type == "rig.scaffold") {
                assertTrue(expected.deformers.size > input.deformers.size)
                assertTrue(expected.parts.size > input.parts.size)
            }
            if (type == "face.skin-clip") {
                assertEquals(listOf("face"), expected.meshes.single { it.id == "body" }.maskedBy)
                assertEquals(input, expected.copy(meshes = expected.meshes.map { mesh ->
                    mesh.copy(maskedBy = input.meshes.single { it.id == mesh.id }.maskedBy)
                }))
            }
            if (type.endsWith(".see-through")) {
                assertEquals(input.meshes.size + 1, expected.meshes.size)
                assertTrue(expected.meshes.any { it.id.startsWith("modifier:") })
                if (type == "eye.see-through") assertTrue(expected.meshes.single { it.id == "bang" }.invertMask)
            }
            if (type == "simulation") {
                assertEquals(1, expected.physics.groups.size)
                assertTrue(expected.parameters.any { it.id == instance.outputId("simulation:mode") })
                assertTrue(expected.meshes.single().offsets != null || expected.meshes.single().shapes.isNotEmpty())
                val model = io.github.psd2live.targets.cubism.PuppetIr.toPuppet(input)
                val replayed = config.getValue("automatic_weights").jsonArray.fold(model) { current, command ->
                    io.github.psd2live.core.RigAuthoringJournal.apply(current, command.jsonObject)
                }
                assertEquals(io.github.psd2live.targets.cubism.PuppetIr.toIr(replayed),
                    io.github.psd2live.targets.cubism.PuppetIr.toIr(RigSimulationWeights.apply(model, config)))
                assertEquals(1, io.github.psd2live.targets.cubism.PuppetIr.toPuppet(expected).vertexGroups.size)
            }
            val prerequisites = definition.requires.map { RigModifierDefinition(it, 1, evaluate = { rig, _, _ -> rig }) }
            val registry = RigModifierRegistry(prerequisites + listOf(definition) + ModifierPluginCatalog.definitions(store, listOf(definition)))
            assertEquals(entry.reference, registry.definition(instance).pluginReference)
            assertSame(definition, registry.definition(instance.copy(plugin = BuiltinModifierPlugins.reference(type, definition.version))))
            if (type == "physics") {
                val profile = io.github.psd2live.core.RigBuildProfile
                val recording = profile.recording
                profile.recording = true
                fun count(key: String) = profile.snapshot()[key]?.second ?: 0L
                val evaluations = count("plugin worker evaluations")
                val hits = count("plugin read cache hits")
                try {
                    repeat(2) { assertEquals(expected, evaluate(registry.definition(instance), input, instance)) }
                    assertEquals(evaluations + 1, count("plugin worker evaluations"))
                    assertEquals(hits + 1, count("plugin read cache hits"))
                    val unrelated = input.copy(parameters = input.parameters + Parameter("unrelated", "Unrelated", 0f, 1f, 0f))
                    assertEquals(expected.copy(parameters = unrelated.parameters), evaluate(registry.definition(instance), unrelated, instance))
                    assertEquals(hits + 2, count("plugin read cache hits"))
                    val changed = input.copy(parameters = input.parameters.map { if (it.id == "input") it.copy(max = 2f) else it })
                    assertEquals(expected.copy(parameters = changed.parameters), evaluate(registry.definition(instance), changed, instance))
                    assertEquals(evaluations + 2, count("plugin worker evaluations"))
                } finally { profile.recording = recording }
            } else repeat(2) {
                assertEquals(expected, evaluate(registry.definition(instance), input, instance))
            }
            if (type == "expression.toggles" || type.endsWith(".see-through") || type == "face.skin-clip" || type == "rig.scaffold") {
                val canonical = instance.copy(type = entry.manifest.algorithms.single().type)
                assertEquals(expected, evaluate(registry.definition(canonical), input, canonical))
            }
            BundledAlgorithmArtifacts.retain(store)
            assertTrue(store.snapshot().any { it.reference == instance.plugin })
            assertTrue(store.snapshot().any { it.reference == BuiltinModifierPlugins.reference(type, definition.version) })
            ModifierPluginWorkers.stop(entry.reference)
            val reopened = ModifierPluginStore(store.root, preferences)
            val restored = RigModifierStack.fromJson(RigModifierStack(instances = listOf(instance)).toJson()).instances.single()
            val restoredRegistry = RigModifierRegistry(prerequisites + listOf(definition) + ModifierPluginCatalog.definitions(reopened, listOf(definition)))
            assertEquals(expected, evaluate(restoredRegistry.definition(restored), input, restored))
            store.uninstall(entry.reference)
            assertFailsWith<IllegalArgumentException> { store.requireAvailable(entry.reference) }
            val reinstalled = store.install(source.toString())
            assertEquals(entry.reference, reinstalled.reference)
            assertEquals(expected, ModifierPluginWorkers.evaluate(reinstalled, reinstalled.manifest.algorithms.single(), input, instance, {}, store, context = definition.authorContext(instance), declaredParameters = definition.parameters(instance)))
            store.uninstall(reinstalled.reference)
        } finally {
            store.snapshot().forEach { ModifierPluginWorkers.stop(it.reference) }
            preferences.removeNode()
            ModifierPluginStore.deleteTree(root)
        }
    }
}
