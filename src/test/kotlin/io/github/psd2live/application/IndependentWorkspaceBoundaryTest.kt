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
