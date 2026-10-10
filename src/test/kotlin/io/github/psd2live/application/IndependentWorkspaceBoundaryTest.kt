package io.github.psd2live.application

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IndependentWorkspaceBoundaryTest {
    @Test fun workspaceRuntimeAndStorageContractsDoNotImportUiOrMcpAdapters() {
        val root = Path.of("src/main/kotlin/io/github/psd2live")
        val sources = listOf("application", "project").flatMap { directory ->
            Files.walk(root.resolve(directory)).use { files ->
                files.filter { file -> file.toString().endsWith(".kt") }.toList()
            }
        }
        val forbidden = Regex("(?m)^import (?:io\\.github\\.psd2live\\.(?:ui|agent)\\.|androidx\\.compose\\.|io\\.modelcontextprotocol\\.|io\\.ktor\\.)")
        sources.forEach { file ->
            val source = Files.readString(file)
            assertFalse(forbidden.containsMatchIn(source), "$file imports a presentation or transport dependency")
            assertFalse(Regex("io\\.github\\.psd2live\\.(?:ui|agent)\\.").containsMatchIn(source),
                "$file accesses a presentation or transport class by fully qualified name")
            if (file.startsWith(root.resolve("project"))) {
                assertFalse(Regex("io\\.github\\.psd2live\\.application\\.").containsMatchIn(source),
                    "$file depends on application behavior rather than storage data")
            }
        }
        for (name in listOf("RigCanvasSupport", "RigInformationOverlay", "RotationGuideFrame", "RasterPaintEngine", "RasterPaintCommit", "RasterMeshJournal", "RigGenerationSource",
            "CanvasBrushGeometry", "CanvasWeightAuthoring", "MotionKeyEdits", "ParameterKeyPose", "PreviewAnimationClock", "legacy/RigGenerationFrames", "legacy/RigGenerationTextures")) {
            val source = Files.readString(root.resolve("core/$name.kt"))
            assertFalse(source.contains("androidx.compose"), "$name must be usable without Compose")
        }
    }

    @Test fun applicationCapabilityContractsRequireConcreteImplementations() {
        assertTrue(WorkspaceBackend::class.java.declaredMethods.isEmpty(), "The host boundary only composes capabilities")
        val pending = ArrayDeque<Class<*>>().apply { add(WorkspaceBackend::class.java) }
        val seen = mutableSetOf<Class<*>>()
        while (pending.isNotEmpty()) {
            val capability = pending.removeFirst()
            if (!seen.add(capability)) continue
            assertTrue(capability.name.startsWith("io.github.psd2live.application."), capability.name)
            capability.declaredMethods.filterNot { it.isSynthetic }.forEach { method ->
                assertTrue(java.lang.reflect.Modifier.isAbstract(method.modifiers), "${capability.simpleName}.${method.name} has a fallback implementation")
            }
            val defaults = runCatching { Class.forName("${capability.name}\$DefaultImpls") }.getOrNull()
            defaults?.declaredMethods?.forEach { method ->
                assertTrue(method.name.endsWith("\$default"), "${capability.simpleName}.${method.name} has a fallback implementation")
            }
            pending.addAll(capability.interfaces)
        }
        assertTrue(WorkspaceDocumentPort::class.java in seen)
        assertTrue(WorkspaceAuxiliaryPort::class.java in seen)
        assertTrue(WorkspaceProjectLifecycle::class.java in seen)
    }
}

/** The replay of what only older builds wrote stays in core.legacy, reached from a fixed set of dispatch points. */
class LegacyReplayBoundaryTest {
    @Test fun onlyTheKnownDispatchPointsReachTheLegacyReplay() {
        val root = Path.of("src/main/kotlin/io/github/psd2live")
        val users = Files.walk(root).use { files ->
            files.filter { it.toString().endsWith(".kt") && !it.startsWith(root.resolve("core/legacy")) }.toList()
        }.filter { Files.readString(it).contains("io.github.psd2live.core.legacy") }.map { root.relativize(it).toString().replace('\\', '/') }.toSet()
        val allowed = setOf(
            // Imported models, which have no generated rig to merge onto, still migrate.
            "application/WorkspaceGenerationCommands.kt", "application/WorkspacePorts.kt", "application/WorkspacePreviewBuilder.kt",
            "application/WorkspaceReadSession.kt", "application/WorkspaceTextureEdits.kt", "core/Cmo3ModelImport.kt", "core/PSD2LivePipeline.kt",
            "core/RigGenerationSource.kt", "core/RasterMeshJournal.kt",
            // Journals without a checkpoint, and the records the journal replays only before one.
            "core/RigEditOverlay.kt", "core/RigAuthoringJournal.kt", "core/RigBuilder.kt", "core/GeneratedOverrides.kt",
            "core/quality/GeneratedOverrideQuality.kt", "core/MaterializedRigCodec.kt",
            // A regeneration's G and G' place the parts of older split records the same way.
            "core/RigRegenerationCheckpoint.kt", "core/PrimitiveResidual.kt",
        )
        assertTrue(users.all { it in allowed }, "New references to core.legacy: ${users - allowed}")
    }
}
