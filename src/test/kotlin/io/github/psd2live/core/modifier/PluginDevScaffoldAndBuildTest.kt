package io.github.psd2live.core.modifier

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

class PluginDevScaffoldAndBuildTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `contracts describe host capabilities and stage SDK jars`() {
        val sdkDir = tempDir.resolve("sdk")
        PluginDevContracts.stageSdkDirectory(sdkDir)
        val env = PluginDevContracts.environmentCheck(sdkDir = sdkDir, stageSdkIfMissing = false)
        assertTrue(env.jdkOk, "Expected JDK 21+, got ${env.jdkVersion}")
        assertTrue(env.sdkReady, "Expected staged SDK jars in $sdkDir, missing=${env.missing}")
        assertTrue(env.sdkJars.any { it.contains("algorithm-api") })
        assertTrue(env.sdkJars.any { it.contains("algorithm-worker") })
        assertTrue(env.sdkJars.any { it.contains("format-model") })

        val contract = PluginDevContracts.describe(includeExample = true, sdkDir = sdkDir)
        assertEquals(PluginDevContracts.HOST_API_VERSION, contract["host_api_version"]?.jsonPrimitive?.content)
        assertTrue(contract["targets"]!!.jsonArray.any { it.jsonPrimitive.content == "mesh" })
        assertTrue(contract["config_schema_keywords"]!!.jsonArray.any { it.jsonPrimitive.content == "minimum" })
        assertNotNull(contract["minimal_example"]?.jsonObject?.get("worker_source"))
    }

    @Test
    fun `scaffold creates standalone project and refuses overwrite`() {
        val projectDir = tempDir.resolve("my-plugin")
        val result = PluginDevScaffold.scaffold(
            PluginDevScaffoldRequest(
                targetDir = projectDir,
                pluginId = "custom.opacity_fx",
                name = "Opacity FX",
                algorithmType = "custom.opacity_fx/scale",
                algorithmName = "Scale Opacity",
                baseVersion = "0.1.0",
                targets = setOf("mesh"),
            )
        )
        assertEquals(5, result.files.size)
        assertTrue(Files.isRegularFile(projectDir.resolve("settings.gradle.kts")))
        assertTrue(Files.isRegularFile(projectDir.resolve("build.gradle.kts")))
        assertTrue(Files.isRegularFile(projectDir.resolve("plugin-meta.json")))
        assertTrue(Files.isRegularFile(projectDir.resolve("src/main/kotlin/custom/opacity_fx/PluginWorker.kt")))

        assertThrows(IllegalArgumentException::class.java) {
            PluginDevScaffold.scaffold(
                PluginDevScaffoldRequest(
                    targetDir = projectDir,
                    pluginId = "custom.opacity_fx",
                    name = "Opacity FX",
                )
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            PluginDevScaffold.scaffold(
                PluginDevScaffoldRequest(
                    targetDir = tempDir.resolve("bad-builtin"),
                    pluginId = "builtin.fake",
                    name = "Fake",
                )
            )
        }
    }

    @Test
    fun `source store registers lists updates and detaches`() {
        val storeFile = tempDir.resolve("dev-sources.json")
        val store = PluginDevSourceStore(storeFile)
        val projectDir = tempDir.resolve("src-proj").also { Files.createDirectories(it) }

        val source = store.register(projectDir, PluginDevSourceMode.MANAGED_BUILD)
        assertEquals(PluginDevContracts.CUSTOM_PLUGIN_PROFILE, source.profileId)
        assertEquals(1, store.list().size)

        val updated = store.updatePackageIds(source.sourceId, listOf("custom.demo"), status = "built")
        assertEquals(listOf("custom.demo"), updated.packageIds)
        assertEquals("built", updated.status)

        assertTrue(store.detach(source.sourceId))
        assertTrue(store.list().isEmpty())

        val missingEx = assertThrows(PluginDevException::class.java) {
            store.register(tempDir.resolve("does-not-exist"), PluginDevSourceMode.MANAGED_BUILD)
        }
        assertEquals("dev_source_missing", missingEx.code)
    }

    @Test
    fun `builder builds prebuilt package zip with +dev buildId and detects unchanged`() {
        val zipPath = createSyntheticPluginZip(
            workDir = tempDir,
            id = "custom.prebuilt",
            algorithmType = "custom.prebuilt/op",
            algorithmVersion = 1,
            protocol = 2,
        )
        val store = PluginDevSourceStore(tempDir.resolve("dev-sources.json"))
        val source = store.register(zipPath, PluginDevSourceMode.BUILT_PACKAGE)

        val sealed = ConcurrentHashMap<String, MutableList<PluginDevArtifact>>()
        fun seal(stagedDir: Path, srcId: String, depLock: List<ModifierPluginReference>, ttl: Long): PluginDevArtifact {
            val manifest = PluginDevBuilder.validateStagedPackage(stagedDir)
            val digest = ModifierPluginStore.hash(Files.readAllBytes(stagedDir.resolve("plugin.json")))
            val ref = ModifierPluginReference(manifest.id, manifest.version, digest)
            val dest = tempDir.resolve("artifacts").resolve(digest)
            if (!Files.exists(dest)) {
                Files.createDirectories(dest.parent)
                Files.walk(stagedDir).use { stream ->
                    stream.forEach { p ->
                        val rel = stagedDir.relativize(p)
                        val target = dest.resolve(rel)
                        if (Files.isDirectory(p)) Files.createDirectories(target)
                        else {
                            Files.createDirectories(target.parent)
                            Files.copy(p, target)
                        }
                    }
                }
            }
            val artifact = PluginDevArtifact(
                artifactId = "art-${digest.take(12)}",
                sourceId = srcId,
                buildDigest = digest,
                packages = listOf(ref),
                manifest = manifest,
                directory = dest,
                retention = PluginDevRetention.CANDIDATE,
                expiresAt = System.currentTimeMillis() + ttl,
            )
            sealed.computeIfAbsent(srcId) { mutableListOf() }.add(artifact)
            return artifact
        }

        val first = PluginDevBuilder.build(
            sourceId = source.sourceId,
            sourceStore = store,
            sealArtifact = ::seal,
            listArtifacts = { sealed[it].orEmpty() },
        )
        assertFalse(first.unchanged)
        assertTrue(first.artifact.manifest.version.startsWith("1.0.0+dev."))
        assertEquals("custom.prebuilt", first.artifact.manifest.id)

        val second = PluginDevBuilder.build(
            sourceId = source.sourceId,
            sourceStore = store,
            sealArtifact = ::seal,
            listArtifacts = { sealed[it].orEmpty() },
        )
        assertTrue(second.unchanged)
        assertEquals(first.artifact.buildDigest, second.artifact.buildDigest)
    }

    @Test
    fun `managed build compiles scaffolded project and reports compiler diagnostics on error`() {
        val sdkDir = tempDir.resolve("sdk")
        PluginDevContracts.stageSdkDirectory(sdkDir)
        val logDir = tempDir.resolve("logs")
        val projectDir = tempDir.resolve("managed-plugin")
        PluginDevScaffold.scaffold(
            PluginDevScaffoldRequest(
                targetDir = projectDir,
                pluginId = "custom.managed",
                name = "Managed Plugin",
                algorithmType = "custom.managed/scale",
                baseVersion = "0.1.0",
            )
        )

        val store = PluginDevSourceStore(tempDir.resolve("dev-sources.json"))
        val source = store.register(projectDir, PluginDevSourceMode.MANAGED_BUILD)
        val sealed = ConcurrentHashMap<String, MutableList<PluginDevArtifact>>()

        fun seal(stagedDir: Path, srcId: String, depLock: List<ModifierPluginReference>, ttl: Long): PluginDevArtifact {
            val manifest = PluginDevBuilder.validateStagedPackage(stagedDir)
            val digest = ModifierPluginStore.hash(Files.readAllBytes(stagedDir.resolve("plugin.json")))
            val ref = ModifierPluginReference(manifest.id, manifest.version, digest)
            val dest = tempDir.resolve("artifacts").resolve(digest)
            if (!Files.exists(dest)) {
                Files.createDirectories(dest.parent)
                Files.walk(stagedDir).use { stream ->
                    stream.forEach { p ->
                        val rel = stagedDir.relativize(p)
                        val target = dest.resolve(rel)
                        if (Files.isDirectory(p)) Files.createDirectories(target)
                        else {
                            Files.createDirectories(target.parent)
                            Files.copy(p, target)
                        }
                    }
                }
            }
            val artifact = PluginDevArtifact(
                artifactId = "art-${digest.take(12)}",
                sourceId = srcId,
                buildDigest = digest,
                packages = listOf(ref),
                manifest = manifest,
                directory = dest,
                retention = PluginDevRetention.CANDIDATE,
                expiresAt = System.currentTimeMillis() + ttl,
            )
            sealed.computeIfAbsent(srcId) { mutableListOf() }.add(artifact)
            return artifact
        }

        val first = PluginDevBuilder.build(
            sourceId = source.sourceId,
            sourceStore = store,
            sdkDir = sdkDir,
            logDir = logDir,
            sealArtifact = ::seal,
            listArtifacts = { sealed[it].orEmpty() },
        )
        assertFalse(first.unchanged)
        assertTrue(first.artifact.manifest.version.startsWith("0.1.0+dev."))
        assertNotNull(first.logRef)

        val second = PluginDevBuilder.build(
            sourceId = source.sourceId,
            sourceStore = store,
            sdkDir = sdkDir,
            logDir = logDir,
            sealArtifact = ::seal,
            listArtifacts = { sealed[it].orEmpty() },
        )
        assertTrue(second.unchanged)

        // Introduce a compile error and verify file/line diagnostics + log_ref
        val workerFile = projectDir.resolve("src/main/kotlin/custom/managed/PluginWorker.kt")
        val originalSource = Files.readString(workerFile)
        Files.writeString(workerFile, originalSource.replace("mesh.opacity * factor", "undefinedSymbolHere"))

        val failed = assertThrows(PluginDevException::class.java) {
            PluginDevBuilder.build(
                sourceId = source.sourceId,
                sourceStore = store,
                sdkDir = sdkDir,
                logDir = logDir,
                sealArtifact = ::seal,
                listArtifacts = { sealed[it].orEmpty() },
            )
        }
        assertEquals("build_failed", failed.code)
        assertNotNull(failed.logRef)
        assertTrue(Files.isRegularFile(Path.of(failed.logRef!!)))
        assertTrue(failed.diagnostics.isNotEmpty(), "Expected compiler diagnostics, log=${Files.readString(Path.of(failed.logRef!!))}")
        assertTrue(failed.diagnostics.any { it.details["file"]?.jsonPrimitive?.content?.contains("PluginWorker.kt") == true })

        // Fix and rebuild with a real code change
        Files.writeString(workerFile, originalSource.replace("mesh.opacity * factor", "mesh.opacity * factor * 0.5f"))
        val third = PluginDevBuilder.build(
            sourceId = source.sourceId,
            sourceStore = store,
            sdkDir = sdkDir,
            logDir = logDir,
            sealArtifact = ::seal,
            listArtifacts = { sealed[it].orEmpty() },
        )
        assertFalse(third.unchanged)
        assertNotEquals(first.artifact.manifest.version, third.artifact.manifest.version)
    }

    @Test
    fun `parses kotlin compiler diagnostics for unix and windows paths`() {
        val log = """
            > Task :compileKotlin FAILED
            e: file:///C:/tmp/PluginWorker.kt:18:25 Unresolved reference 'badSymbol'.
            e: /tmp/PluginWorker.kt: (22, 9): Type mismatch.
        """.trimIndent()
        val parsed = PluginDevBuilder.parseCompilerDiagnostics(log)
        assertEquals(2, parsed.size)
        assertEquals(18, parsed[0].details["line"]?.jsonPrimitive?.content?.toInt())
        assertEquals(25, parsed[0].details["column"]?.jsonPrimitive?.content?.toInt())
        assertEquals(22, parsed[1].details["line"]?.jsonPrimitive?.content?.toInt())
        assertEquals(9, parsed[1].details["column"]?.jsonPrimitive?.content?.toInt())
    }
}
