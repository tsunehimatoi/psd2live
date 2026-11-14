package io.github.psd2live.core.modifier

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.nio.file.Files
import java.nio.file.Path

internal data class PluginDevScaffoldRequest(
    val targetDir: Path,
    val pluginId: String,
    val name: String,
    val algorithmType: String = "$pluginId/custom",
    val algorithmName: String = name,
    val baseVersion: String = "0.1.0",
    val targets: Set<String> = setOf("mesh"),
    val packageName: String = PluginDevScaffold.defaultPackageName(pluginId),
)

internal data class PluginDevScaffoldResult(
    val directory: Path,
    val pluginId: String,
    val algorithmType: String,
    val baseVersion: String,
    val files: List<Path>,
)

/** Generates a standalone Gradle/Kotlin plugin project without touching the host repository. */
internal object PluginDevScaffold {
    private val pluginIdPattern = Regex("[a-zA-Z0-9][a-zA-Z0-9._-]{0,127}")
    private val packagePattern = Regex("[a-zA-Z_][a-zA-Z0-9_]*(\\.[a-zA-Z_][a-zA-Z0-9_]*)*")
    private val prettyJson = Json { prettyPrint = true; encodeDefaults = true }

    fun defaultPackageName(pluginId: String): String {
        val segments = pluginId.split('.', '-')
            .map { seg -> seg.filter { it.isLetterOrDigit() || it == '_' }.lowercase() }
            .filter { it.isNotEmpty() }
            .map { if (it.first().isDigit()) "p$it" else it }
        return if (segments.isEmpty()) "custom.plugin" else segments.joinToString(".")
    }

    fun scaffold(request: PluginDevScaffoldRequest): PluginDevScaffoldResult {
        val targetDir = request.targetDir.toAbsolutePath().normalize()
        require(pluginIdPattern.matches(request.pluginId)) { "Invalid plugin ID: ${request.pluginId}" }
        require(request.pluginId != "builtin" && !request.pluginId.startsWith("builtin.")) {
            "Plugin ID cannot use reserved 'builtin' namespace: ${request.pluginId}"
        }
        require(request.name.isNotBlank()) { "Plugin name must not be blank" }
        require(request.algorithmName.isNotBlank()) { "Algorithm name must not be blank" }
        val resolvedType = if ('/' in request.algorithmType) request.algorithmType else "${request.pluginId}/${request.algorithmType}"
        require(resolvedType.startsWith("${request.pluginId}/") && resolvedType.length > request.pluginId.length + 1) {
            "Algorithm type must belong to plugin namespace '${request.pluginId}/': $resolvedType"
        }
        require('+' !in request.baseVersion) { "Base version must not include +build metadata: ${request.baseVersion}" }
        ModifierPluginVersion.parse(request.baseVersion)
        require(request.targets.isNotEmpty() && request.targets.all { it in PluginDevContracts.supportedTargets }) {
            "Unsupported targets: ${request.targets - PluginDevContracts.supportedTargets}"
        }
        require(packagePattern.matches(request.packageName)) { "Invalid Kotlin package name: ${request.packageName}" }

        if (Files.exists(targetDir)) {
            require(Files.isDirectory(targetDir)) { "Target path exists and is not a directory: $targetDir" }
            val nonEmpty = Files.list(targetDir).use { it.findFirst().isPresent }
            require(!nonEmpty) { "Refusing to overwrite non-empty directory: $targetDir" }
        } else {
            Files.createDirectories(targetDir)
        }

        val packagePath = request.packageName.replace('.', '/')
        val workerFile = targetDir.resolve("src/main/kotlin/$packagePath/PluginWorker.kt")
        val settingsFile = targetDir.resolve("settings.gradle.kts")
        val gradlePropsFile = targetDir.resolve("gradle.properties")
        val buildFile = targetDir.resolve("build.gradle.kts")
        val metaFile = targetDir.resolve("plugin-meta.json")

        Files.createDirectories(workerFile.parent)
        Files.writeString(settingsFile, renderSettingsGradleKts(request.pluginId))
        Files.writeString(gradlePropsFile, renderGradleProperties())
        Files.writeString(buildFile, renderBuildGradleKts(request.pluginId, "${request.packageName}.PluginWorker"))
        Files.writeString(metaFile, prettyJson.encodeToString(JsonObject.serializer(), renderPluginMeta(request, resolvedType)))
        Files.writeString(workerFile, renderWorkerSource(request.packageName, resolvedType))

        return PluginDevScaffoldResult(
            directory = targetDir,
            pluginId = request.pluginId,
            algorithmType = resolvedType,
            baseVersion = request.baseVersion,
            files = listOf(settingsFile, gradlePropsFile, buildFile, metaFile, workerFile),
        )
    }

    internal fun renderSettingsGradleKts(pluginId: String): String = """
        pluginManagement {
            repositories {
                mavenCentral()
                gradlePluginPortal()
            }
        }

        rootProject.name = "${pluginId.replace("\"", "")}"
    """.trimIndent() + "\n"

    internal fun renderGradleProperties(): String = """
        org.gradle.jvmargs=-Xmx1g
        kotlin.code.style=official
    """.trimIndent() + "\n"

    internal fun renderBuildGradleKts(
        pluginId: String,
        mainClass: String = "${defaultPackageName(pluginId)}.PluginWorker",
    ): String = """
        import groovy.json.JsonOutput
        import groovy.json.JsonSlurper
        import java.security.MessageDigest
        import java.util.HexFormat

        plugins {
            kotlin("jvm") version "${PluginDevContracts.KOTLIN_PLUGIN_VERSION}"
        }

        group = "${pluginId.replace("\"", "")}"
        version = providers.gradleProperty("psd2live.pluginVersion").orNull ?: "0.1.0"

        kotlin {
            jvmToolchain(21)
        }

        repositories {
            mavenCentral()
        }

        val sdkDirPath: String = providers.gradleProperty("psd2live.sdkDir").orNull
            ?: System.getenv("PSD2LIVE_SDK_DIR")
            ?: throw GradleException("Missing -Ppsd2live.sdkDir=<dir> or PSD2LIVE_SDK_DIR")

        dependencies {
            implementation(fileTree(sdkDirPath) { include("*.jar") })
        }

        val workerJar by tasks.registering(Jar::class) {
            dependsOn(configurations.runtimeClasspath)
            archiveClassifier.set("worker")
            isPreserveFileTimestamps = false
            isReproducibleFileOrder = true
            duplicatesStrategy = DuplicatesStrategy.EXCLUDE
            manifest {
                attributes["Main-Class"] = "$mainClass"
            }
            from(sourceSets.main.get().output)
            from(configurations.runtimeClasspath.map { files ->
                files.map { if (it.isDirectory) it else zipTree(it) }
            })
            exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA")
        }

        tasks.register("pluginPackage") {
            dependsOn(workerJar)
            val metaFile = layout.projectDirectory.file("plugin-meta.json")
            val outputDir = layout.buildDirectory.dir("plugin")
            val resolvedVersion = project.version.toString()
            inputs.file(metaFile)
            inputs.file(workerJar.flatMap { it.archiveFile })
            inputs.property("pluginVersion", resolvedVersion)
            outputs.dir(outputDir)
            doLast {
                val directory = outputDir.get().asFile.apply { mkdirs() }
                val jar = workerJar.get().archiveFile.get().asFile
                val payload = directory.resolve("worker.jar")
                jar.copyTo(payload, overwrite = true)
                val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
                @Suppress("UNCHECKED_CAST")
                val meta = JsonSlurper().parse(metaFile.asFile) as MutableMap<String, Any?>
                meta["version"] = resolvedVersion
                meta["commands"] = mapOf("jvm" to listOf("\${'$'}JAVA", "-jar", "worker.jar"))
                meta["files"] = mapOf("worker.jar" to digest)
                directory.resolve("plugin.json").writeText(JsonOutput.prettyPrint(JsonOutput.toJson(meta)) + "\n")
            }
        }
    """.trimIndent() + "\n"

    internal fun renderPluginMeta(request: PluginDevScaffoldRequest, resolvedType: String): JsonObject = buildJsonObject {
        put("protocol", PluginDevContracts.HOST_PROTOCOL_MIN)
        put("id", request.pluginId)
        put("version", request.baseVersion)
        put("name", request.name)
        putJsonArray("algorithms") {
            add(buildJsonObject {
                put("type", resolvedType)
                put("version", 1)
                put("name", request.algorithmName)
                put("category", "plugins")
                putJsonArray("targets") { request.targets.sorted().forEach { add(it) } }
                putJsonArray("reads") { add("mesh") }
                putJsonArray("writes") { add("mesh") }
                putJsonArray("creates") {}
                putJsonArray("modifies") { add("mesh.opacity") }
                putJsonObject("configSchema") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("factor") {
                            put("type", "number")
                            put("title", "Opacity Factor")
                            put("description", "Multiplier applied to target mesh opacity")
                            put("minimum", 0.0)
                            put("maximum", 1.0)
                            put("default", 0.5)
                        }
                    }
                    put("additionalProperties", false)
                }
                putJsonObject("defaults") { put("factor", 0.5) }
                putJsonArray("parameters") {}
            })
        }
    }

    internal fun renderWorkerSource(packageName: String, algorithmType: String): String = """
        package $packageName

        import io.github.psd2live.algorithm.worker.AlgorithmWorker
        import io.github.psd2live.core.modifier.ChangingModifierAlgorithmPlugin
        import io.github.psd2live.core.modifier.ModifierAlgorithmContext
        import io.github.psd2live.core.modifier.ModifierModelChanges
        import io.github.psd2live.core.modifier.ModifierObjectChanges
        import io.github.psd2live.core.modifier.ModifierObjectUpdate
        import io.github.psd2live.core.modifier.RigModifierInstance
        import io.github.psd2live.format.model.RigIR
        import kotlinx.serialization.json.floatOrNull
        import kotlinx.serialization.json.jsonPrimitive

        object PluginWorker {
            @JvmStatic
            fun main(args: Array<String>) {
                val plugin = object : ChangingModifierAlgorithmPlugin {
                    override fun changes(
                        input: RigIR,
                        instance: RigModifierInstance,
                        context: ModifierAlgorithmContext,
                        checkpoint: () -> Unit,
                    ): ModifierModelChanges {
                        val factor = instance.config["factor"]?.jsonPrimitive?.floatOrNull ?: 0.5f
                        val targetIds = instance.targets.mapTo(HashSet()) { it.id }
                        val updates = input.meshes.filter { it.id in targetIds }.map { mesh ->
                            checkpoint()
                            ModifierObjectUpdate(mesh, mesh.copy(opacity = (mesh.opacity * factor).coerceIn(0f, 1f)))
                        }
                        return ModifierModelChanges(meshes = ModifierObjectChanges(updated = updates))
                    }
                }
                AlgorithmWorker.run(mapOf(("$algorithmType" to 1) to plugin))
            }
        }
    """.trimIndent() + "\n"
}
