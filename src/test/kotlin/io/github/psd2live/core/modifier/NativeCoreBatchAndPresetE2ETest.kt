package io.github.psd2live.core.modifier

import io.github.psd2live.algorithms.binding.SkinClipAlgorithm
import io.github.psd2live.algorithms.motion.MotionAlgorithm
import io.github.psd2live.algorithms.physics.PendulumAlgorithm
import io.github.psd2live.application.WorkspaceInitialPreset
import io.github.psd2live.application.WorkspacePresetInitialization
import io.github.psd2live.application.WorkspaceRasterWork
import io.github.psd2live.core.*
import io.github.psd2live.core.sim.*
import io.github.psd2live.format.model.*
import io.github.psd2live.targets.cubism.PuppetIr
import io.github.psd2live.project.WorkspaceDocument
import io.github.psd2live.project.WorkspaceSettingsCodec
import io.github.psd2live.project.rawConfig
import io.github.psd2live.scenario.Characters
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.prefs.Preferences
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.system.measureNanoTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NativeCoreBatchAndPresetE2ETest {
    private val json = Json { encodeDefaults = true }

    private fun packRuntimeDep(workDir: Path, version: String): Path {
        val cfgBytes = """{"runtimeVersion":"$version"}""".encodeToByteArray()
        val manifest = ModifierPluginManifest(
            protocol = 2,
            id = "psd2live.native.runtime",
            name = "Shared Native Runtime",
            version = version,
            commands = mapOf(ModifierPluginStore.platform to listOf("runtime-config.json")),
            files = mapOf("runtime-config.json" to ModifierPluginStore.hash(cfgBytes)),
            algorithms = listOf(
                ModifierPluginAlgorithm(
                    type = "psd2live.native.runtime/noop",
                    version = 1,
                    name = "Noop",
                    targets = setOf("mesh"),
                    reads = setOf("mesh", "relations"),
                    writes = setOf("mesh", "relations"),
                    configSchema = buildJsonObject { },
                    defaults = buildJsonObject { },
                )
            ),
        )
        val zipPath = workDir.resolve("psd2live-native-runtime-$version.zip")
        ZipOutputStream(Files.newOutputStream(zipPath)).use { zip ->
            zip.putNextEntry(ZipEntry("plugin.json"))
            zip.write(json.encodeToString(manifest).encodeToByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("runtime-config.json"))
            zip.write(cfgBytes)
            zip.closeEntry()
        }
        return zipPath
    }

    private fun packNativeCore(workDir: Path, exePath: Path, withDep: Boolean = true, version: String = "1.0.0"): Path {
        val exeBytes = Files.readAllBytes(exePath)
        val exeName = exePath.fileName.toString()
        val manifest = ModifierPluginManifest(
            protocol = 2,
            id = "psd2live.native.core",
            name = "Core Native Modifiers (Rust)",
            version = version,
            dependencies = if (withDep) listOf(ModifierPluginDependency("psd2live.native.runtime")) else emptyList(),
            commands = mapOf(ModifierPluginStore.platform to listOf(exeName)),
            files = mapOf(exeName to ModifierPluginStore.hash(exeBytes)),
            algorithms = listOf(
                ModifierPluginAlgorithm(
                    type = "psd2live.native.core/physics",
                    version = 1,
                    name = "Pendulum Physics (Native)",
                    category = "physics",
                    targets = setOf("mesh", "deformer", "part", "layer"),
                    reads = setOf("parameter", "physics"),
                    writes = setOf("physics"),
                    configSchema = buildJsonObject { },
                    defaults = buildJsonObject { },
                ),
                ModifierPluginAlgorithm(
                    type = "psd2live.native.core/motion",
                    version = 1,
                    name = "Motion Clip (Native)",
                    category = "motion",
                    targets = setOf("mesh", "deformer", "part", "layer"),
                    reads = setOf("parameter", "motion", "relations"),
                    writes = setOf("motion", "relations"),
                    configSchema = buildJsonObject { },
                    defaults = buildJsonObject { },
                ),
                ModifierPluginAlgorithm(
                    type = "psd2live.native.core/skin-clip",
                    authorContext = "face.skin-clip",
                    version = 1,
                    name = "Skin Clipping (Native)",
                    category = "face",
                    targets = setOf("mesh"),
                    requires = setOf("rig.scaffold"),
                    contextVersion = 2,
                    reads = setOf("mesh", "relations"),
                    writes = setOf("mesh", "relations"),
                    composableKinds = setOf("mesh", "part", "deformer", "hierarchy", "relations", "parameter"),
                    configSchema = buildJsonObject { put("type", "object") },
                    defaults = buildJsonObject { },
                ),
            ),
        )
        val zipPath = workDir.resolve("psd2live-native-core.zip")
        ZipOutputStream(Files.newOutputStream(zipPath)).use { zip ->
            zip.putNextEntry(ZipEntry("plugin.json"))
            zip.write(json.encodeToString(manifest).encodeToByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry(exeName))
            zip.write(exeBytes)
            zip.closeEntry()
        }
        return zipPath
    }

    private fun sampleRig(): RigIR {
        val face = Mesh(
            id = "face",
            name = "face",
            parent = null,
            geometry = MeshGeometry(
                Floats.values(0f, 0f, 10f, 0f, 0f, 10f),
                Floats.values(0f, 0f, 1f, 0f, 0f, 1f),
                Ints.values(0, 1, 2),
            ),
            offsets = null,
            opacity = 1f,
        )
        val blush = face.copy(id = "blush", name = "blush")
        return RigIR(
            canvas = Canvas(100f, 100f),
            parameters = listOf(
                Parameter("ParamAngleX", "Angle X", -30f, 30f, 0f),
                Parameter("ParamHairFront", "Hair Front", -10f, 10f, 0f),
                Parameter("ParamEyeLOpen", "Eye L Open", 0f, 1f, 1f),
            ),
            meshes = listOf(face, blush),
        )
    }

    @Test
    fun batchNativeCoreParityBenchmarkAndExtensibleDeps() {
        val exeName = if (ModifierPluginStore.platform.startsWith("windows")) "psd2live-native-core.exe" else "psd2live-native-core"
        val exePath = Path.of("plugins/native-core/target/release/$exeName").toAbsolutePath().normalize()
        assumeTrue(Files.isRegularFile(exePath), "Native core Rust binary not built at $exePath")

        val jvmPhysArt = assertNotNull(BundledAlgorithmArtifacts.find("physics", 1))
        val jvmMotArt = assertNotNull(BundledAlgorithmArtifacts.find("motion", 1))

        val dir = Files.createTempDirectory("native-core-batch-")
        val prefs = Preferences.userRoot().node("native-core-batch/" + UUID.randomUUID())
        val store = ModifierPluginStore(dir.resolve("installed"), prefs)
        var depV1Ref: ModifierPluginReference? = null
        var coreRef: ModifierPluginReference? = null
        try {
            BundledAlgorithmArtifacts.retain(store, listOf(jvmPhysArt, jvmMotArt))
            val jvmPhysPkg = store.snapshot().single { it.reference == jvmPhysArt.reference }
            val jvmMotPkg = store.snapshot().single { it.reference == jvmMotArt.reference }

            val depV1 = store.install(packRuntimeDep(dir, "1.0.0").toString())
            depV1Ref = depV1.reference
            val corePkg = store.install(packNativeCore(dir, exePath, withDep = true).toString())
            coreRef = corePkg.reference
            assertEquals(listOf(depV1.reference), store.resolution(corePkg.reference).dependencies)

            val rig = sampleRig()
            val physAlg = corePkg.manifest.algorithms.single { it.type == "psd2live.native.core/physics" }
            val motAlg = corePkg.manifest.algorithms.single { it.type == "psd2live.native.core/motion" }
            val clipAlg = corePkg.manifest.algorithms.single { it.type == "psd2live.native.core/skin-clip" }

            val physCfg = RigPhysicsEdit(
                id = "ignored",
                name = "Hair Sway",
                inputs = listOf(io.github.psd2live.core.PhysicsInput("ParamAngleX", 100f, PhysicsSourceType.ANGLE)),
                outputs = listOf(io.github.psd2live.core.PhysicsOutput("ParamHairFront", 1, 1f, 100f, PhysicsSourceType.ANGLE)),
            ).toJson()
            val motCfg = MotionClips.toJson(
                MotionClip(
                    id = "ignored",
                    name = "Idle",
                    duration = 2f,
                    curves = listOf(
                        MotionCurve("ParamAngleX", listOf(MotionKey(0f, 0f), MotionKey(2f, 10f)))
                    ),
                    parameterRoles = mapOf("EyeBlink" to listOf("ParamEyeLOpen")),
                )
            )
            val clipCtx = ModifierAlgorithmContext(
                values = buildJsonObject {
                    put("schema", "skin-clip/1")
                    putJsonArray("faces") {
                        addJsonObject {
                            put("id", "face")
                            putJsonArray("bounds") { add(0f); add(0f); add(100f); add(100f) }
                        }
                    }
                    putJsonObject("overlays") {
                        putJsonArray("blush") { add(10f); add(10f); add(50f); add(50f) }
                    }
                    putJsonArray("already_active") { }
                }
            )

            val physInst = RigModifierInstance("p1", "physics", 1, listOf(ModifierTarget("mesh", "face")), config = physCfg, plugin = jvmPhysPkg.reference)
            val motInst = RigModifierInstance("m1", "motion", 1, listOf(ModifierTarget("mesh", "face")), config = motCfg, plugin = jvmMotPkg.reference)
            val clipInst = RigModifierInstance("sc1", "psd2live.native.core/skin-clip", 1, listOf(ModifierTarget("mesh", "blush")), plugin = corePkg.reference)

            // Cold start + parity for physics, motion, skin.clip
            lateinit var jvmPhysOut: RigIR
            val jvmPhysCold = measureNanoTime {
                jvmPhysOut = ModifierPluginWorkers.evaluate(jvmPhysPkg, jvmPhysPkg.manifest.algorithms.single(), rig, physInst, {}, store)
            }
            lateinit var jvmMotOut: RigIR
            val jvmMotCold = measureNanoTime {
                jvmMotOut = ModifierPluginWorkers.evaluate(jvmMotPkg, jvmMotPkg.manifest.algorithms.single(), rig, motInst, {}, store)
            }

            lateinit var natPhysOut: RigIR
            val natCold = measureNanoTime {
                natPhysOut = ModifierPluginWorkers.evaluate(corePkg, physAlg, rig, physInst.copy(type = physAlg.type, plugin = corePkg.reference), {}, store)
            }
            val natMotOut = ModifierPluginWorkers.evaluate(corePkg, motAlg, rig, motInst.copy(type = motAlg.type, plugin = corePkg.reference), {}, store)
            val natClipOut = ModifierPluginWorkers.evaluate(corePkg, clipAlg, rig, clipInst, {}, store, context = clipCtx)

            assertEquals(jvmPhysOut.physics, natPhysOut.physics)
            assertEquals(jvmMotOut.clips, natMotOut.clips)
            assertEquals(jvmMotOut.parameterRoles, natMotOut.parameterRoles)
            val directClipOut = SkinClipAlgorithm().evaluate(rig, clipInst, clipCtx) {}
            assertEquals(directClipOut.meshes, natClipOut.meshes)

            // Equal warmup before 100x benchmarks across all 3 migrated algorithms
            repeat(5) { i ->
                ModifierPluginWorkers.evaluate(jvmPhysPkg, jvmPhysPkg.manifest.algorithms.single(), rig, physInst.copy(id = "wjp_$i"), {}, store)
                ModifierPluginWorkers.evaluate(jvmMotPkg, jvmMotPkg.manifest.algorithms.single(), rig, motInst.copy(id = "wjm_$i"), {}, store)
                ModifierPluginWorkers.evaluate(corePkg, physAlg, rig, physInst.copy(id = "wnp_$i", type = physAlg.type, plugin = corePkg.reference), {}, store)
                ModifierPluginWorkers.evaluate(corePkg, motAlg, rig, motInst.copy(id = "wnm_$i", type = motAlg.type, plugin = corePkg.reference), {}, store)
                ModifierPluginWorkers.evaluate(corePkg, clipAlg, rig, clipInst.copy(id = "wnc_$i"), {}, store, context = clipCtx)
                PendulumAlgorithm().changes(ModifierEvaluationInput(rig, ModifierAlgorithmContext.Empty), physInst.copy(id = "wdp_$i")) {}
                MotionAlgorithm().changes(ModifierEvaluationInput(rig, ModifierAlgorithmContext.Empty), motInst.copy(id = "wdm_$i")) {}
                SkinClipAlgorithm().changes(ModifierEvaluationInput(rig, clipCtx), clipInst.copy(id = "wdc_$i")) {}
            }
            val iters = 100
            val jvmPhysWarm = measureNanoTime {
                repeat(iters) { i ->
                    ModifierPluginWorkers.evaluate(jvmPhysPkg, jvmPhysPkg.manifest.algorithms.single(), rig, physInst.copy(id = "jp_$i"), {}, store)
                }
            }
            val jvmMotWarm = measureNanoTime {
                repeat(iters) { i ->
                    ModifierPluginWorkers.evaluate(jvmMotPkg, jvmMotPkg.manifest.algorithms.single(), rig, motInst.copy(id = "jm_$i"), {}, store)
                }
            }
            val natPhysWarm = measureNanoTime {
                repeat(iters) { i ->
                    ModifierPluginWorkers.evaluate(corePkg, physAlg, rig, physInst.copy(id = "np_$i", type = physAlg.type, plugin = corePkg.reference), {}, store)
                }
            }
            val natMotWarm = measureNanoTime {
                repeat(iters) { i ->
                    ModifierPluginWorkers.evaluate(corePkg, motAlg, rig, motInst.copy(id = "nm_$i", type = motAlg.type, plugin = corePkg.reference), {}, store)
                }
            }
            val natClipWarm = measureNanoTime {
                repeat(iters) { i ->
                    ModifierPluginWorkers.evaluate(corePkg, clipAlg, rig, clipInst.copy(id = "nc_$i"), {}, store, context = clipCtx)
                }
            }
            val directAllWarm = measureNanoTime {
                repeat(iters) { i ->
                    PendulumAlgorithm().changes(ModifierEvaluationInput(rig, ModifierAlgorithmContext.Empty), physInst.copy(id = "dp_$i")) {}
                    MotionAlgorithm().changes(ModifierEvaluationInput(rig, ModifierAlgorithmContext.Empty), motInst.copy(id = "dm_$i")) {}
                    SkinClipAlgorithm().changes(ModifierEvaluationInput(rig, clipCtx), clipInst.copy(id = "dc_$i")) {}
                }
            }

            // Verify dependency can be upgraded/added in store without touching host app:
            // existing corePkg stays pinned to its locked depV1, while a fresh install picks depV2 (1.1.0).
            val depV2 = store.install(packRuntimeDep(dir, "1.1.0").toString())
            assertTrue(store.resolution(corePkg.reference).available)
            assertEquals(depV1.reference, store.resolution(corePkg.reference).dependencies.single())
            val coreWithV2 = store.install(packNativeCore(dir, exePath, withDep = true, version = "1.0.1").toString())
            assertEquals(depV2.reference, store.resolution(coreWithV2.reference).dependencies.single())
            ModifierPluginWorkers.stop(coreWithV2.reference)
            ModifierPluginWorkers.stop(depV2.reference)

            println(
                "[BENCHMARK batch-core] " +
                    "Rust(proto2) cold=${natCold / 1e6}ms | " +
                    "Warm100(ms): physics[JVM=${jvmPhysWarm / 1e6}, Rust=${natPhysWarm / 1e6}], " +
                    "motion[JVM=${jvmMotWarm / 1e6}, Rust=${natMotWarm / 1e6}], " +
                    "skin-clip[Rust=${natClipWarm / 1e6}], " +
                    "DirectAll3=${directAllWarm / 1e6}ms | " +
                    "JVMcold(phys=${jvmPhysCold / 1e6}ms, mot=${jvmMotCold / 1e6}ms)"
            )
        } finally {
            ModifierPluginWorkers.stop(jvmPhysArt.reference)
            ModifierPluginWorkers.stop(jvmMotArt.reference)
            coreRef?.let(ModifierPluginWorkers::stop)
            depV1Ref?.let(ModifierPluginWorkers::stop)
            prefs.removeNode()
            ModifierPluginStore.deleteTree(dir)
        }
    }

    @Test
    fun realPresetExecutesPhysicsMotionAndSkinClipThroughNativeCorePluginWithFullParity() {
        val exeName = if (ModifierPluginStore.platform.startsWith("windows")) "psd2live-native-core.exe" else "psd2live-native-core"
        val exePath = Path.of("plugins/native-core/target/release/$exeName").toAbsolutePath().normalize()
        assumeTrue(Files.isRegularFile(exePath), "Native core Rust binary not built at $exePath")

        val dir = Files.createTempDirectory("native-preset-e2e-")
        val store = ModifierPluginStore.shared
        var coreRef: ModifierPluginReference? = null
        try {
            val packagedZip = Path.of("plugins/native-core/dist/psd2live.native.core-1.0.0.zip").toAbsolutePath().normalize()
            val zipToInstall = if (Files.isRegularFile(packagedZip)) packagedZip else packNativeCore(dir, exePath, withDep = false)
            val installed = store.install(zipToInstall.toString())
            coreRef = installed.reference
            // Bare legacy alias must remain owned by builtin/in-process, never hijacked by standalone package.
            val legacyClipDef = RigModifierAlgorithms.registry
                .definition(RigModifierInstance("probe", "face.skin-clip", 1, listOf(ModifierTarget("mesh", "probe"))))
            assertTrue(legacyClipDef.pluginReference.let { it == null || it.id.startsWith("builtin.") })

            val artwork = Characters.head(physics = true)
            val baseDoc = WorkspaceDocument(
                source = artwork.source,
                layerVisibility = emptyMap(),
                deletedLayerIds = emptySet(),
                layerOverrides = emptyMap(),
                parentOverrides = emptyMap(),
                rigEdits = RigEditOverlay(modifierStack = RigModifierStack().toJson()),
                settings = WorkspaceSettingsCodec.encode(artwork.rawConfig()),
            )
            val overrides = mapOf(
                "physics" to ("psd2live.native.core/physics" to installed.reference),
                "motion" to ("psd2live.native.core/motion" to installed.reference),
                "face.skin-clip" to ("psd2live.native.core/skin-clip" to installed.reference),
                "eye.see-through" to ("psd2live.native.core/eye-see-through" to installed.reference),
                "brow.see-through" to ("psd2live.native.core/brow-see-through" to installed.reference),
                "expression.toggles" to ("psd2live.native.core/expression-toggles" to installed.reference),
                "expression.cheek" to ("psd2live.native.core/expression-cheek" to installed.reference),
                "expression.gloom" to ("psd2live.native.core/expression-gloom" to installed.reference),
                "eye.lid-fade" to ("psd2live.native.core/eye-lid-fade" to installed.reference),
                "eye.squash" to ("psd2live.native.core/eye-squash" to installed.reference),
                "brow.shape" to ("psd2live.native.core/brow-shape" to installed.reference),
                "brow.spring" to ("psd2live.native.core/brow-spring" to installed.reference),
                "eye.wide" to ("psd2live.native.core/eye-wide" to installed.reference),
                "eye.smile" to ("psd2live.native.core/eye-smile" to installed.reference),
                "eye.emotion" to ("psd2live.native.core/eye-emotion" to installed.reference),
                "eye.aftershock" to ("psd2live.native.core/eye-aftershock" to installed.reference),
                "eye.gaze-lid" to ("psd2live.native.core/eye-gaze-lid" to installed.reference),
                "brow.lid-coupling" to ("psd2live.native.core/brow-lid-coupling" to installed.reference),
                "brow.blink" to ("psd2live.native.core/brow-blink" to installed.reference),
            )
            // Equal warmup for both paths before timing
            WorkspacePresetInitialization.prepare(
                baseDoc,
                WorkspaceRasterWork.Direct,
                WorkspaceInitialPreset(clothing = false),
            )
            WorkspacePresetInitialization.prepare(
                baseDoc,
                WorkspaceRasterWork.Direct,
                WorkspaceInitialPreset(clothing = false),
                pluginOverrides = overrides,
            )

            lateinit var baselinePair: Pair<WorkspaceDocument, RigPreviewModel>
            val baselineNs = measureNanoTime {
                baselinePair = WorkspacePresetInitialization.prepare(
                    baseDoc,
                    WorkspaceRasterWork.Direct,
                    WorkspaceInitialPreset(clothing = false),
                )
            }
            lateinit var nativePair: Pair<WorkspaceDocument, RigPreviewModel>
            val nativeNs = measureNanoTime {
                nativePair = WorkspacePresetInitialization.prepare(
                    baseDoc,
                    WorkspaceRasterWork.Direct,
                    WorkspaceInitialPreset(clothing = false),
                    pluginOverrides = overrides,
                )
            }

            val baselineStack = RigModifierStack.fromJson(baselinePair.first.rigEdits.modifierStack!!)
            val nativeStack = RigModifierStack.fromJson(nativePair.first.rigEdits.modifierStack!!)
            assertTrue(nativeStack.instances.any { it.type == "psd2live.native.core/physics" && it.plugin == installed.reference })
            assertTrue(nativeStack.instances.any { it.type == "psd2live.native.core/motion" && it.plugin == installed.reference })
            if (baselineStack.instances.any { it.type == "face.skin-clip" }) {
                assertTrue(nativeStack.instances.any { it.type == "psd2live.native.core/skin-clip" && it.plugin == installed.reference })
            }
            if (baselineStack.instances.any { it.type == "eye.see-through" }) {
                assertTrue(nativeStack.instances.any { it.type == "psd2live.native.core/eye-see-through" && it.plugin == installed.reference })
            }
            if (baselineStack.instances.any { it.type == "brow.see-through" }) {
                assertTrue(nativeStack.instances.any { it.type == "psd2live.native.core/brow-see-through" && it.plugin == installed.reference })
            }
            assertTrue(baselineStack.instances.any { it.type == "eye" })
            assertTrue(baselineStack.instances.any { it.type == "brow" })
            for ((legacyType, nativeType) in overrides.mapValues { it.value.first }) {
                if (nativeStack.instances.any { it.type == nativeType }) {
                    assertTrue(
                        nativeStack.instances.any { it.type == nativeType && it.plugin == installed.reference },
                        "Expected $nativeType in native stack for $legacyType"
                    )
                }
            }
            assertTrue(baselineStack.instances.size <= nativeStack.instances.size)

            val baseIr = RigIrCompiler.compile(baselinePair.second)
            val natIr = RigIrCompiler.compile(nativePair.second)
            assertEquals(baseIr.parameters, natIr.parameters)
            assertEquals(baseIr.parameterTree, natIr.parameterTree)
            assertEquals(baseIr.physics, natIr.physics)
            assertEquals(baseIr.clips, natIr.clips)
            assertEquals(baseIr.parameterRoles, natIr.parameterRoles)
            assertEquals(baseIr.meshes, natIr.meshes)
            assertEquals(baseIr.parts, natIr.parts)
            assertEquals(baseIr.rootChildren, natIr.rootChildren)
            assertEquals(baseIr.renderRoot, natIr.renderRoot)

            // Verify standalone Rust swing via protocol 2 RigIrObjects delta against builtin swing
            val swingMesh = natIr.meshes.first { it.geometry != null }
            val swingCfg = buildJsonObject {
                put("id", "hair_swing")
                put("name", "Hair Swing")
                put("kind", "LATERAL")
                putJsonArray("targets") { add(swingMesh.id) }
                putJsonArray("parameters") { add("ParamSwingTest1"); add("ParamSwingTest2") }
            }
            val swingInput = natIr
            val swingTargets = listOf(ModifierTarget("mesh", swingMesh.id))
            val swingInstBuiltin = RigModifierInstance("sw1", "swing", 1, swingTargets, config = swingCfg)
            val swingInstNative = RigModifierInstance("sw1", "psd2live.native.core/swing", 1, swingTargets, config = swingCfg, plugin = installed.reference)
            val swingBuiltin = RigModifierAlgorithms.registry.definition(swingInstBuiltin).plugin.evaluate(swingInput, swingInstBuiltin) {}
            val swingNative = RigModifierAlgorithms.registry.definition(swingInstNative).plugin.evaluate(swingInput, swingInstNative) {}
            assertEquals(swingBuiltin.parameters, swingNative.parameters)
            assertEquals(swingBuiltin.deformers, swingNative.deformers)
            assertEquals(swingBuiltin.meshes, swingNative.meshes)
            assertEquals(swingBuiltin.physics, swingNative.physics)

            // Also verify `simulation` via RigIrObjects (keyform-axis mode + physics group) on full natIr (with clips)
            val simVerts = swingMesh.geometry!!.positions.size / 2
            val zeroForm = FloatArray(simVerts * 2)
            val posForm = FloatArray(simVerts * 2) { if (it % 2 == 0) 1.5f else -0.5f }
            val negForm = FloatArray(simVerts * 2) { if (it % 2 == 0) -1.5f else 0.5f }
            val simModeParam = "ParamSimcloth_1"
            val simDraft = RigSimEdit(id = "cloth", name = "Cloth Sim", kind = SimKind.CLOTH, targets = listOf(swingMesh.id), blendShapes = false)
            val simFp = SimBake.fingerprint(PuppetIr.toPuppet(natIr), simDraft)
            val simModeAxis = SimBakedAxis(simModeParam, floatArrayOf(-30f, 0f, 30f), mapOf(swingMesh.id to listOf(negForm, zeroForm, posForm)))
            val simPhys = RigPhysicsEdit(
                id = "PhysicsSim_cloth",
                name = "Cloth Sim",
                inputs = listOf(io.github.psd2live.core.PhysicsInput("ParamAngleX", 60f, PhysicsSourceType.X)),
                outputs = listOf(io.github.psd2live.core.PhysicsOutput(simModeParam, 1, 1f, 100f, PhysicsSourceType.ANGLE)),
                segments = listOf(io.github.psd2live.core.PhysicsSegment(15f, 0.9f, 0.8f, 1f)),
            )
            val simBake = SimBakeResult(
                fingerprint = simFp,
                vertexCounts = mapOf(swingMesh.id to simVerts),
                statics = emptyList(),
                modes = listOf(SimBakedMode(simModeAxis, 1.5f, 1f)),
                physics = simPhys,
            )
            val simCfg = simDraft.copy(bake = simBake).toJson()
            val simTargets = listOf(ModifierTarget("mesh", swingMesh.id))
            val simInstBuiltin = RigModifierInstance("sim1", "simulation", 1, simTargets, config = simCfg)
            val simInstNative = RigModifierInstance("sim1", "psd2live.native.core/simulation", 1, simTargets, config = simCfg, plugin = installed.reference)
            val simBuiltin = RigModifierAlgorithms.registry.definition(simInstBuiltin).plugin.evaluate(natIr, simInstBuiltin) {}
            val simNative = RigModifierAlgorithms.registry.definition(simInstNative).plugin.evaluate(natIr, simInstNative) {}
            assertEquals(simBuiltin.parameters, simNative.parameters)
            assertEquals(simBuiltin.parameterTree, simNative.parameterTree)
            assertEquals(simBuiltin.meshes, simNative.meshes)
            assertEquals(simBuiltin.physics, simNative.physics)

            // eye.lid-fade parity (including eye.wide + authored MeshShape.opacity preservation)
            val lidTargets = listOf(ModifierTarget("mesh", "lid"))
            val lidCfg = buildJsonObject { put("scaffold", "scaffold"); put("base", "eye") }
            val lidCtx = io.github.psd2live.algorithms.binding.LidFadeInputs.encode(
                mapOf("lid" to io.github.psd2live.algorithms.binding.LidFadeInputs("ParamEyeLOpen", .2f, .5f)),
            ).withUpstream(listOf(
                ModifierUpstreamCapability("rig.scaffold", "rig.scaffold", 2, lidTargets, "scaffold"),
                ModifierUpstreamCapability("eye/fitted", "eye/fitted", 5, lidTargets, "eye"),
                ModifierUpstreamCapability("eye.wide", "eye.wide", 3, lidTargets, "wide", lidCfg),
            ))
            val lidShape = MeshShape(Floats.values(.1f, .2f, 0f, 0f, 0f, 0f), 1f, .375f, Rgb.White, Rgb.Black)
            val lidBinding = BlendBinding("ParamLidPose", Floats.values(0f, 1f), 0, listOf(null, lidShape))
            val lidMesh = Mesh(
                "lid", "lid", null,
                geometry = MeshGeometry(Floats.values(0f, 0f, 1f, 0f, 0f, 1f), Floats.values(0f, 0f, 1f, 0f, 0f, 1f), Ints.values(0, 1, 2)),
                offsets = null, opacity = .5f, shapes = listOf(lidBinding),
            )
            val lidIr = RigIR(
                Canvas(1f, 1f),
                listOf(Parameter("ParamEyeLOpen", "EyeLOpen", 0f, 2f, 1f), Parameter("ParamLidPose", "LidPose", 0f, 1f, 0f)),
                meshes = listOf(lidMesh),
            )
            val lidBuiltin = io.github.psd2live.algorithms.binding.LidFadeAlgorithm().changes(
                ModifierEvaluationInput(lidIr, lidCtx),
                RigModifierInstance("fade", "eye.lid-fade", 2, lidTargets, config = lidCfg),
            ) {}.apply(lidIr)
            val lidNativeAlgo = installed.manifest.algorithms.single { it.type == "psd2live.native.core/eye-lid-fade" }
            val lidNative = ModifierPluginWorkers.evaluate(
                installed, lidNativeAlgo, lidIr,
                RigModifierInstance("fade", "psd2live.native.core/eye-lid-fade", 1, lidTargets, config = lidCfg, plugin = installed.reference),
                {}, store, context = lidCtx,
            )
            assertEquals(lidBuiltin.meshes, lidNative.meshes)

            println(
                "[BENCHMARK real-preset] " +
                    "baseline(in-proc, warmed)=${baselineNs / 1e6}ms | " +
                    "native(physics+motion+skin-clip+see-through via Rust proto2, warmed)=${nativeNs / 1e6}ms"
            )
        } finally {
            coreRef?.let {
                runCatching { store.uninstall(it) }
                ModifierPluginWorkers.stop(it)
            }
            ModifierPluginStore.deleteTree(dir)
        }
    }
}
