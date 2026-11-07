package io.github.psd2live.core

import io.github.psd2live.algorithms.binding.BindingProjection
import io.github.psd2live.algorithms.binding.EyeFittedAlgorithm
import io.github.psd2live.algorithms.binding.EyeFittedSourceAlgorithm
import io.github.psd2live.algorithms.binding.EyeFittedV4Algorithm
import io.github.psd2live.algorithms.binding.EyeScaffoldTransition
import io.github.psd2live.algorithms.binding.ScaffoldAlgorithm
import io.github.psd2live.algorithms.binding.ScaffoldPartsContext
import io.github.psd2live.algorithms.binding.ScaffoldParts
import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.model.Floats
import io.github.psd2live.format.model.VertexGroup
import io.github.psd2live.core.recipe.BaseChoice
import io.github.psd2live.core.recipe.ModuleChoice
import io.github.psd2live.core.recipe.RigModules
import io.github.psd2live.core.recipe.RigRecipe
import io.github.psd2live.project.rawConfig
import io.github.psd2live.scenario.Characters
import io.github.psd2live.targets.cubism.PuppetIr
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.util.UUID
import java.util.jar.JarFile
import java.util.prefs.Preferences
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FittedEyePluginParityTest {
    @Test fun fittedBlinkHandoverIrisAndGazeMatchTheNativeBase() {
        compare(Characters.eyed())
    }

    @Test fun missingEyeWhitesRetainTheLegacyBlink() {
        val document = Characters.eyed()
        val config = document.rawConfig()
        val analysis = CharacterAnalyzer.analyze(document.source, config)
        compare(document, analysis.copy(layers = analysis.layers.filter { it.semantic.tag != SemanticTag.EYEWHITE }))
    }

    private fun compare(document: io.github.psd2live.project.WorkspaceDocument, source: PipelineAnalysis? = null) {
        val neutralConfig = document.rawConfig().copy(generateDeformers = true, mouthOutlineEnabled = false, recipe = RigRecipe.Legacy)
        val oldConfig = neutralConfig.copy(recipe = RigRecipe(true, bases = mapOf("eye" to BaseChoice("eye/fitted", 2)),
            modules = RigModules.modules.associate { it.id to ModuleChoice(false) }))
        val analysis = source ?: CharacterAnalyzer.analyze(document.source, neutralConfig)
        val cache = PreviewMeshCache()
        val atlas = AtlasLayout.pack(analysis.layers, neutralConfig)
        val scaffold = RigBuilder.build(analysis, atlas, neutralConfig, cache)
        val native = RigBuilder.build(analysis, atlas, oldConfig, cache).puppet
        val expected = PuppetIr.toIr(native)
        val layers = analysis.layers.associateBy { it.source.id.raw }
        val eyeIds = scaffold.layerIdByDrawableId.filterValues { layers[it]?.semantic?.tag in
            setOf(SemanticTag.EYEWHITE, SemanticTag.EYELASH, SemanticTag.IRIDES, SemanticTag.EYE_CLOSE) }.keys
        assertTrue(eyeIds.isNotEmpty())
        val targets = scaffold.layerIdByDrawableId.keys.map { ModifierTarget("mesh", it) }
        val provider = RigModifierInstance("structure", "rig.scaffold", 2, targets,
            outputAliases = scaffold.puppet.deformers.associate { "shared/deformer:${it.id.raw}" to it.id.raw } +
                scaffold.puppet.parts.associate { "shared/part:${it.id.raw}" to it.id.raw })
        val instance = RigModifierInstance("fitted", "eye/fitted", EyeFittedAlgorithm.VERSION, targets,
            config = buildJsonObject { put("scaffold", provider.id) })
        val primary = scaffold.layerIdByDrawableId.entries.associate { it.value to it.key }
        val sourceMeasurements = RigBuilder.scaffoldSourceInputs(analysis, oldConfig, cache)
        val organization = ScaffoldPartsContext.capture(analysis.layers.mapNotNull { layer ->
            val id = primary[layer.source.id.raw] ?: return@mapNotNull null
            ScaffoldParts.Material(org.umamo.runtime.model.DrawableId(id), RigBuilder.scaffoldSlot(layer, analysis.anchors), layer.source.order)
        }, label = { it })
        val materialConfig = RigModifierPipeline.ordinaryConfig(neutralConfig).copy(meshOnly = true)
        val materials = RigOrdinaryMaterials.of(RigBuilder.build(analysis, atlas, materialConfig, cache), analysis)
        val captured = ScaffoldAlgorithm.captureSource(materials.puppet, targets.associate { it.id to it.id }, organization,
            sourceMeasurements.frames, sourceMeasurements.branches, RigBuilder.scaffoldMeshInputs(analysis, atlas, oldConfig, cache, primary))
        val production = RigModifierSource(analysis, atlas, materialConfig, materials.layerIdByDrawableId,
            materialBase = materials.puppet, meshCache = cache)
        val ordinary = production.scaffoldContext(provider)
        val context = RigFittedEyeSource.capture(instance, provider, analysis, neutralConfig, captured, requireNotNull(sourceMeasurements.eyes),
            scaffold.layerIdByDrawableId, primary)
        val sourceScaffold = ScaffoldAlgorithm().buildSource(captured)
        val staticNative = BindingProjection.retain(native, emptySet())
        val sourceDeformers = sourceScaffold.deformers.associateBy { it.id }
        val nativeDeformers = staticNative.deformers.associateBy { it.id }
        val eyeParents = staticNative.drawables.filter { it.id.raw in eyeIds }.flatMap { drawable ->
            generateSequence(drawable.parentDeformerId) { nativeDeformers.getValue(it).parent }.toList()
        }.toSet()
        assertTrue(eyeParents.isNotEmpty())
        fun deformerFrames(model: Map<DeformerId, Deformer>) =
            eyeParents.map { PuppetIr.deformerToIr(model.getValue(it)) }
        assertEquals(deformerFrames(nativeDeformers), deformerFrames(sourceDeformers))
        if (source == null) {
            val staticLegacy = BindingProjection.retain(scaffold.puppet, emptySet())
            assertNotEquals(deformerFrames(nativeDeformers), deformerFrames(staticLegacy.deformers.associateBy { it.id }))
        }
        val allBefore = ScaffoldAlgorithm().evaluate(PuppetIr.toIr(materials.puppet), provider, ordinary) {}
        val planned = EyeScaffoldTransition.plan(allBefore, provider, eyeIds, ordinary, captured)
        val transition = assertIs<EyeScaffoldTransition.Result.Ready>(planned, planned.toString())
        val before = transition.changes.apply(allBefore)
        assertEquals(deformerFrames(sourceDeformers), deformerFrames(PuppetIr.toPuppet(before).deformers.associateBy { it.id }))
        assertEquals(allBefore.meshes.filter { it.id !in eyeIds }, before.meshes.filter { it.id !in eyeIds })
        if (source == null) {
            val changedFrame = transition.changes.deformers.updated.first().before.id
            val changedMesh = transition.changes.meshes.updated.first().before
            val dependent = allBefore.copy(meshes = allBefore.meshes + changedMesh.copy(id = "SharedDependent", parent = changedFrame))
            val shared = assertIs<EyeScaffoldTransition.Result.Blocked>(
                EyeScaffoldTransition.plan(dependent, provider, eyeIds, ordinary, captured))
            assertEquals(EyeScaffoldTransition.Reason.SHARED_FRAME, shared.reason)
            assertTrue(changedFrame in shared.ids)
            val geometry = requireNotNull(changedMesh.geometry)
            val pins = VertexGroup("TransitionPins", changedMesh.id, "pin", Floats.wrap(FloatArray(geometry.positions.size / 2) { 1f }))
            val editedUvs = Floats.wrap(geometry.uvs.shared().copyOf().also { it[0] += 0.0001f })
            val edited = allBefore.copy(meshes = allBefore.meshes.map {
                if (it.id == changedMesh.id) it.copy(geometry = geometry.copy(uvs = editedUvs)) else it
            })
            assertEquals(EyeScaffoldTransition.Result.Blocked(EyeScaffoldTransition.Reason.AUTHORED_MESH, setOf(changedMesh.id)),
                EyeScaffoldTransition.plan(edited, provider, eyeIds, ordinary, captured))
            val indexed = allBefore.copy(authoring = allBefore.authoring.copy(vertexGroups = listOf(pins)))
            val blocked = assertIs<EyeScaffoldTransition.Result.Blocked>(
                EyeScaffoldTransition.plan(indexed, provider, eyeIds, ordinary, captured))
            assertEquals(EyeScaffoldTransition.Result.Blocked(EyeScaffoldTransition.Reason.INDEXED_MESH, setOf(changedMesh.id)), blocked)
        }
        val changes = EyeFittedAlgorithm().changes(before, instance.copy(targets = targets.filter { it.id in eyeIds }), context) {}
        val actual = changes.apply(before)
        val eyeNames = RigFittedEyeSource.parameterNames().keys
        assertEquals(expected.parameters.filter { it.id in eyeNames }, actual.parameters)
        assertEquals(expected.parameterLinks.filter { it.horizontal in eyeNames }, actual.parameterLinks)
        val expectedEyes = expected.meshes.filter { it.id in eyeIds }
        val actualEyes = actual.meshes.filter { it.id in eyeIds }
        assertEquals(expectedEyes.map { it.id }, actualEyes.map { it.id })
        for ((mesh, actualMesh) in expectedEyes.zip(actualEyes)) assertEquals(mesh, actualMesh, mesh.id)
        assertEquals(expected.deformers.filter { it.id.startsWith("DeformEyeGaze") },
            actual.deformers.filter { it.id.startsWith("DeformEyeGaze") })
        assertTrue(changes.meshes.updated.all { it.before.id in eyeIds && it.before.geometry == it.after.geometry })
        if (source == null) {
            val author = PuppetIr.toIr(materials.puppet)
            fun owner(stack: RigModifierStack) = RigModifierSource(analysis, atlas,
                materialConfig.copy(rigEdits = materialConfig.rigEdits.copy(modifierStack = stack.toJson())),
                materials.layerIdByDrawableId, materialBase = materials.puppet, meshCache = cache)
            val stack = RigModifierStack(instances = listOf(provider, instance))
            val adapter = owner(stack)
            val live = RigModifierAlgorithms.evaluator(adapter).evaluate(author, stack).rig
            assertEquals(actual, live)
            val smile = RigModifierInstance("smile", "eye.smile", RigModules.EYE_SMILE.version, targets,
                config = buildJsonObject { put("scaffold", provider.id); put("base", instance.id) })
            val smiling = stack.copy(instances = stack.instances + smile)
            val composed = RigModifierAlgorithms.evaluator(owner(smiling)).evaluate(author, smiling).rig
            val nativeSmile = RigBuilder.build(analysis, atlas, oldConfig.copy(recipe = oldConfig.recipe.copy(
                modules = oldConfig.recipe.modules + (RigModules.EYE_SMILE.id to ModuleChoice(true)))), cache)
            val smilingEyes = PuppetIr.toIr(nativeSmile.puppet).meshes.filter { it.id in eyeIds }
            assertTrue(smilingEyes.any { mesh -> mesh.offsets?.axes.orEmpty().any { it.parameter.endsWith("Smile") } })
            val composedEyes = composed.meshes.filter { it.id in eyeIds }
            for ((expectedEye, actualEye) in smilingEyes.zip(composedEyes)) assertEquals(expectedEye, actualEye, expectedEye.id)
            val previous = RigModifierAlgorithms.registry.definition(instance.copy(version = 2))
            assertEquals(2, previous.version)
            assertFalse(previous.plugin is EyeFittedSourceAlgorithm)
            verifyWorker(adapter, author, live, stack)
            val body = RigModifierInstance("body", "body/legacy", 1, targets, config = instance.config)
            val head = RigModifierInstance("head", "face/fitted", 1, targets, config = instance.config)
            val mixed = stack.copy(instances = listOf(provider, body, head, instance))
            val prefix = mixed.copy(instances = mixed.instances.dropLast(1))
            val coarseTurn = RigModifierAlgorithms.evaluator(owner(prefix)).evaluate(author, prefix).rig
            val turn = RigModules.FACE_DEPTH_TURN.id
            val nativeTurn = RigBuilder.build(analysis, atlas, oldConfig.copy(recipe = oldConfig.recipe.copy(
                modules = oldConfig.recipe.modules + (turn to ModuleChoice(true)))), cache)
            val fittedTurn = PuppetIr.toIr(nativeTurn.puppet)
            val eyeTurnIds = eyeParents.mapTo(hashSetOf()) { it.raw }
            fun turnGrids(rig: io.github.psd2live.format.model.RigIR) = rig.deformers
                .filterIsInstance<io.github.psd2live.format.model.Deformer.Warp>()
                .filter { warp -> warp.id in eyeTurnIds && warp.lattice?.axes.orEmpty().any { it.parameter == "ParamAngleX" } }
                .associate { it.id to it.lattice }
            assertTrue(turnGrids(fittedTurn).isNotEmpty())
            assertEquals(turnGrids(fittedTurn).keys, turnGrids(coarseTurn).keys)
            assertNotEquals(turnGrids(fittedTurn), turnGrids(coarseTurn), "Coarse eye turn differs after the fitted face prefix")
            val blockedHead = assertFailsWith<IllegalArgumentException> {
                RigModifierAlgorithms.evaluator(owner(mixed)).evaluate(author, mixed)
            }
            assertEquals("Fitted eye v3 requires head turn to be built from the fitted source scaffold", blockedHead.message)

            val headV4 = instance.copy(version = EyeFittedV4Algorithm.VERSION)
            val legacyHead = mixed.copy(instances = prefix.instances + instance.copy(version = 2))
            val v4Stack = mixed.copy(instances = prefix.instances + headV4)
            val mixedOwner = owner(v4Stack)
            val legacyResult = RigModifierAlgorithms.evaluator(owner(legacyHead)).evaluate(author, legacyHead).rig
            val fittedHeadResult = RigModifierAlgorithms.evaluator(mixedOwner).evaluate(author, v4Stack).rig
            assertTrue(legacyResult == fittedHeadResult, "Fitted eye v4 differs from the mixed v2 result")
            val preceding = listOf(
                ModifierUpstreamCapability("face/legacy", head.type, head.version, targets, head.id, head.config),
                ModifierUpstreamCapability("body/legacy", body.type, body.version, targets, body.id, body.config),
            )
            verifyWorker(mixedOwner, author, fittedHeadResult, v4Stack, EyeFittedV4Algorithm.ENTRYPOINT)
            val eyeMesh = coarseTurn.meshes.first { it.id in eyeIds }
            val sharedHead = coarseTurn.copy(meshes = coarseTurn.meshes + eyeMesh.copy(id = "AuthoredHeadMesh"))
            val capture = requireNotNull(RigFittedEyeHeadSource.capture(mixedOwner, headV4))
            val sharedFailure = assertFailsWith<IllegalArgumentException> {
                EyeFittedV4Algorithm().changes(sharedHead, headV4, capture.withUpstream(capture.upstream + preceding)) {}
            }
            assertEquals("Fitted eye v4 cannot move a head frame shared with additional authored objects", sharedFailure.message)

            val (lash, indexedLash) = before.meshes.filter { layers[scaffold.layerIdByDrawableId[it.id]]?.semantic?.tag == SemanticTag.EYELASH }
            val geometry = assertNotNull(lash.geometry)
            val editedUvs = geometry.uvs.shared().copyOf().also { it[0] += 0.0001f }
            val authored = geometry.copy(uvs = Floats.wrap(editedUvs))
            val indexed = assertNotNull(indexedLash.geometry)
            val group = VertexGroup("Pins", indexedLash.id, "pin", Floats.wrap(FloatArray(indexed.positions.size / 2) { 1f }))
            val authoredInput = before.copy(
                meshes = before.meshes.map { if (it.id == lash.id) it.copy(geometry = authored) else it },
                authoring = before.authoring.copy(vertexGroups = listOf(group)),
            )
            val rebound = EyeFittedAlgorithm().changes(authoredInput, instance.copy(targets = targets.filter { it.id in eyeIds }), context) {}.apply(authoredInput)
            val reboundLash = rebound.meshes.single { it.id == lash.id }
            assertEquals(authored, reboundLash.geometry)
            assertTrue(reboundLash.offsets?.axes.orEmpty().isNotEmpty())
            assertEquals(indexed, rebound.meshes.single { it.id == indexedLash.id }.geometry)
            assertEquals(listOf(group), rebound.authoring.vertexGroups)
        }
    }

    private fun verifyWorker(
        owner: RigModifierSource, input: io.github.psd2live.format.model.RigIR, expected: io.github.psd2live.format.model.RigIR,
        stack: RigModifierStack,
        entrypoint: String = EyeFittedSourceAlgorithm.ENTRYPOINT,
    ) {
        val directory = Files.createTempDirectory("fitted-eye-package-")
        val preferences = Preferences.userRoot().node("fitted-eye-package/${UUID.randomUUID()}")
        val store = ModifierPluginStore(directory.resolve("installed"), preferences)
        val instance = stack.instances.last()
        val artifact = assertNotNull(BundledAlgorithmArtifacts.find(instance.type, instance.version))
        try {
            val staging = Files.createDirectory(directory.resolve("package"))
            val manifest = Json.parseToJsonElement(artifact.bytes.decodeToString()).jsonObject
            // Use a different package revision to exercise the installed worker rather than the bundled implementation.
            Files.writeString(staging.resolve("plugin.json"), JsonObject(manifest + ("version" to JsonPrimitive("0.9.0"))).toString())
            artifact.manifest.files.keys.forEach { name ->
                assertNotNull(javaClass.getResourceAsStream(artifact.resource + name)).use { Files.copy(it, staging.resolve(name)) }
            }
            JarFile(staging.resolve("algorithm.jar").toFile()).use { jar ->
                assertNotNull(jar.getJarEntry("rig/eye-opening.bin"))
                assertFalse(jar.entries().asSequence().any { it.name.startsWith("io/github/psd2live/core/RigBuilder") })
            }
            val definitions = BuiltinModifierPlugins.definitions(owner)
            repeat(2) {
                val entry = store.install(staging.toString())
                val worker = ModifierPluginCatalog.definitions(store, captured = definitions)
                    .single { it.type == entrypoint }
                // Resolve upstream aliases through the same ordered evaluator used by the application.
                val installed = definitions.map {
                    if (it.type == instance.type && it.version == instance.version)
                        worker.copy(type = it.type, pluginReference = null)
                    else it
                }
                val evaluator = RigModifierEvaluator(RigModifierRegistry(installed))
                assertEquals(expected, evaluator.evaluate(input, stack).rig)
                ModifierPluginWorkers.stop(entry.reference)
                store.uninstall(entry.reference)
            }
        } finally {
            store.snapshot().forEach { ModifierPluginWorkers.stop(it.reference) }
            preferences.removeNode()
            ModifierPluginStore.deleteTree(directory)
        }
    }

}
