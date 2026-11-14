package io.github.psd2live.core.modifier

import io.github.psd2live.algorithm.worker.AlgorithmWorker
import io.github.psd2live.format.model.RigIR
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

/** Host API/protocol contracts, environment checks, SDK staging, and minimal runnable examples. */
internal object PluginDevContracts {
    const val HOST_API_VERSION = "1.10.0"
    const val HOST_PROTOCOL_MIN = 1
    const val HOST_PROTOCOL_MAX = 2
    const val SUPPORTED_CONTEXT_VERSION_MIN = 1
    const val SUPPORTED_CONTEXT_VERSION_MAX = 2
    const val CUSTOM_PLUGIN_PROFILE = "custom-plugin"
    const val KOTLIN_PLUGIN_VERSION = "2.4.10"

    val supportedTargets: Set<String> = setOf("mesh", "deformer", "part", "layer")
    val supportedDerivedOutputKinds: Set<String> = setOf("mesh", "deformer", "part", "parameter", "physics", "motion", "glue")
    val supportedConfigSchemaKeywords: Set<String> = setOf(
        "type", "properties", "required", "additionalProperties", "minProperties",
        "items", "minItems", "maxItems", "enum", "const", "oneOf", "pattern",
        "minimum", "maximum", "minLength", "maxLength", "exclusiveMinimum",
        "exclusiveMaximum", "uniqueItems", "description", "examples", "title",
        "default", "\$defs", "\$ref",
    )
    val supportedUiFieldTypes: Set<String> = setOf("boolean", "number", "integer", "string")

    data class EnvironmentCheckResult(
        val jdkVersion: String,
        val jdkFeature: Int,
        val jdkHome: String,
        val jdkOk: Boolean,
        val gradleAvailable: Boolean,
        val gradleCommand: List<String>,
        val gradleSource: String,
        val sdkDir: Path,
        val sdkReady: Boolean,
        val sdkJars: List<String>,
        val missing: List<String>,
        val diagnostics: List<PluginDevDiagnostic>,
    ) {
        val ready: Boolean get() = jdkOk && gradleAvailable && sdkReady && missing.isEmpty()

        fun toJson(): JsonObject = buildJsonObject {
            put("ready", ready)
            put("jdk_version", jdkVersion)
            put("jdk_feature", jdkFeature)
            put("jdk_home", jdkHome)
            put("jdk_ok", jdkOk)
            put("gradle_available", gradleAvailable)
            putJsonArray("gradle_command") { gradleCommand.forEach { add(it) } }
            put("gradle_source", gradleSource)
            put("sdk_dir", sdkDir.toString())
            put("sdk_ready", sdkReady)
            putJsonArray("sdk_jars") { sdkJars.forEach { add(it) } }
            putJsonArray("missing") { missing.forEach { add(it) } }
            putJsonArray("diagnostics") {
                diagnostics.forEach { diag ->
                    add(buildJsonObject {
                        put("code", diag.code)
                        put("severity", diag.severity)
                        put("message", diag.message)
                        put("details", diag.details)
                    })
                }
            }
        }
    }

    fun defaultSdkDir(): Path =
        Path.of(System.getProperty("user.home"), ".psd2live", "plugin-sdk", HOST_API_VERSION)

    fun environmentCheck(
        sdkDir: Path = configuredSdkDir() ?: defaultSdkDir(),
        projectDir: Path? = null,
        stageSdkIfMissing: Boolean = false,
    ): EnvironmentCheckResult {
        val version = Runtime.version()
        val jdkFeature = version.feature()
        val jdkOk = jdkFeature >= 21
        val jdkHome = System.getProperty("java.home") ?: ""

        val (gradleCmd, gradleSource) = resolveGradleCommand(projectDir)
        val gradleOk = gradleCmd.isNotEmpty()

        if (stageSdkIfMissing && !isSdkDirPopulated(sdkDir)) {
            runCatching { stageSdkDirectory(sdkDir) }
        }
        val sdkJars = if (Files.isDirectory(sdkDir)) {
            Files.list(sdkDir).use { stream ->
                stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".jar") }
                    .map { it.fileName.toString() }
                    .sorted()
                    .toList()
            }
        } else emptyList()
        val sdkReady = isSdkDirPopulated(sdkDir)

        val missing = buildList {
            if (!jdkOk) add("jdk21+")
            if (!gradleOk) add("gradle")
            if (!sdkReady) add("psd2live_sdk")
        }
        val diagnostics = buildList {
            if (!jdkOk) {
                add(PluginDevDiagnostic(
                    code = "env_jdk_missing",
                    severity = "error",
                    message = "JDK 21 or newer is required; found $version at $jdkHome",
                ))
            }
            if (!gradleOk) {
                add(PluginDevDiagnostic(
                    code = "env_gradle_missing",
                    severity = "error",
                    message = "Gradle executable not found on PATH, GRADLE_HOME, or host wrapper",
                ))
            }
            if (!sdkReady) {
                add(PluginDevDiagnostic(
                    code = "env_sdk_missing",
                    severity = "error",
                    message = "Host plugin SDK jars are not staged in $sdkDir",
                ))
            }
        }
        return EnvironmentCheckResult(
            jdkVersion = version.toString(),
            jdkFeature = jdkFeature,
            jdkHome = jdkHome,
            jdkOk = jdkOk,
            gradleAvailable = gradleOk,
            gradleCommand = gradleCmd,
            gradleSource = gradleSource,
            sdkDir = sdkDir,
            sdkReady = sdkReady,
            sdkJars = sdkJars,
            missing = missing,
            diagnostics = diagnostics,
        )
    }

    private fun configuredSdkDir(): Path? =
        (System.getProperty("psd2live.sdkDir")?.takeIf { it.isNotBlank() }
            ?: System.getenv("PSD2LIVE_SDK_DIR")?.takeIf { it.isNotBlank() })
            ?.let { Path.of(it) }

    private fun isSdkDirPopulated(sdkDir: Path): Boolean {
        if (!Files.isDirectory(sdkDir)) return false
        val jars = Files.list(sdkDir).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".jar") }
                .map { it.fileName.toString() }
                .toList()
        }
        if (jars.isEmpty()) return false
        val hasApi = jars.any { it.contains("algorithm-api") }
        val hasWorker = jars.any { it.contains("algorithm-worker") }
        val hasModel = jars.any { it.contains("format-model") }
        return hasApi && hasWorker && hasModel
    }

    /**
     * Stages host `algorithm-api`, `format-model`, `algorithm-worker`, and serialization jars
     * from the running host classpath into [targetDir] so standalone plugin builds work offline.
     */
    fun stageSdkDirectory(targetDir: Path = configuredSdkDir() ?: defaultSdkDir()): Path {
        Files.createDirectories(targetDir)
        val anchors = linkedMapOf<String, Class<*>>(
            "algorithm-api" to ModifierAlgorithmPlugin::class.java,
            "format-model" to RigIR::class.java,
            "algorithm-worker" to AlgorithmWorker::class.java,
            "kotlinx-serialization-json" to Json::class.java,
            "kotlinx-serialization-core" to kotlinx.serialization.Serializable::class.java,
            "kotlin-stdlib" to Unit::class.java,
        )
        runCatching { Class.forName("io.github.psd2live.format.compile.FormatCompile") }
            .getOrNull()?.let { anchors["format-compile"] = it }

        for ((name, cls) in anchors) {
            val location = cls.protectionDomain?.codeSource?.location ?: continue
            val sourcePath = runCatching { Path.of(location.toURI()) }.getOrNull() ?: continue
            if (Files.isRegularFile(sourcePath) && sourcePath.toString().endsWith(".jar")) {
                val destName = if (sourcePath.fileName.toString().startsWith(name)) {
                    sourcePath.fileName.toString()
                } else {
                    "$name.jar"
                }
                val dest = targetDir.resolve(destName)
                if (!Files.exists(dest) || Files.size(dest) != Files.size(sourcePath)) {
                    Files.copy(sourcePath, dest, StandardCopyOption.REPLACE_EXISTING)
                }
            } else if (Files.isDirectory(sourcePath)) {
                val dest = targetDir.resolve("$name.jar")
                packageClassesDirToJar(sourcePath, dest)
            }
        }

        // Also sweep java.class.path for format-compile or kotlinx-serialization jars if not yet staged.
        val cpEntries = System.getProperty("java.class.path").orEmpty()
            .split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .map { Path.of(it) }
        val extraPrefixes = listOf("algorithm-api", "algorithm-worker", "format-model", "format-compile", "kotlinx-serialization-")
        for (entry in cpEntries) {
            val pathStr = entry.toString().replace('\\', '/')
            if (Files.isRegularFile(entry) && entry.fileName.toString().endsWith(".jar")) {
                val fileName = entry.fileName.toString()
                if (extraPrefixes.any { fileName.startsWith(it) }) {
                    val dest = targetDir.resolve(fileName)
                    if (!Files.exists(dest)) Files.copy(entry, dest, StandardCopyOption.REPLACE_EXISTING)
                }
            } else if (Files.isDirectory(entry) && pathStr.contains("/format-compile/")) {
                val dest = targetDir.resolve("format-compile.jar")
                if (!Files.exists(dest)) packageClassesDirToJar(entry, dest)
            }
        }
        return targetDir
    }

    private fun packageClassesDirToJar(classesDir: Path, jarOut: Path) {
        val temp = jarOut.resolveSibling("${jarOut.fileName}.tmp-${System.nanoTime()}")
        try {
            JarOutputStream(Files.newOutputStream(temp)).use { jar ->
                val seen = HashSet<String>()
                fun addTree(root: Path) {
                    if (!Files.isDirectory(root)) return
                    Files.walk(root).use { stream ->
                        stream.filter { Files.isRegularFile(it) }.sorted().forEach { file ->
                            val rel = root.relativize(file).toString().replace('\\', '/')
                            if (seen.add(rel)) {
                                jar.putNextEntry(JarEntry(rel).apply { time = 0L })
                                Files.newInputStream(file).use { it.copyTo(jar) }
                                jar.closeEntry()
                            }
                        }
                    }
                }
                addTree(classesDir)
                // Include sibling resources/main if present in standard Gradle build layout.
                val resourcesDir = classesDir.parent?.parent?.parent?.resolve("resources")?.resolve("main")
                if (resourcesDir != null) addTree(resourcesDir)
            }
            Files.move(temp, jarOut, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    fun resolveGradleCommand(projectDir: Path? = null): Pair<List<String>, String> {
        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        val wrapperName = if (isWindows) "gradlew.bat" else "gradlew"
        val gradleExeName = if (isWindows) "gradle.bat" else "gradle"

        if (projectDir != null) {
            val wrapper = projectDir.resolve(wrapperName)
            if (Files.isRegularFile(wrapper)) return listOf(wrapper.toAbsolutePath().toString()) to "project-wrapper"
        }

        val explicit = System.getProperty("psd2live.gradleBin")?.takeIf { it.isNotBlank() }
            ?: System.getenv("PSD2LIVE_GRADLE_BIN")?.takeIf { it.isNotBlank() }
        if (explicit != null && Files.isRegularFile(Path.of(explicit))) {
            return listOf(explicit) to "explicit"
        }

        val gradleHome = System.getenv("PSD2LIVE_GRADLE_HOME")?.takeIf { it.isNotBlank() }
            ?: System.getenv("GRADLE_HOME")?.takeIf { it.isNotBlank() }
        if (gradleHome != null) {
            val bin = Path.of(gradleHome, "bin", gradleExeName)
            if (Files.isRegularFile(bin)) return listOf(bin.toAbsolutePath().toString()) to "gradle-home"
        }

        // Check PATH
        val pathEnv = System.getenv("PATH").orEmpty()
        for (dir in pathEnv.split(File.pathSeparator)) {
            if (dir.isBlank()) continue
            val candidate = Path.of(dir, gradleExeName)
            if (Files.isRegularFile(candidate)) return listOf(candidate.toAbsolutePath().toString()) to "path"
            if (isWindows) {
                val cmdCandidate = Path.of(dir, "gradle.cmd")
                if (Files.isRegularFile(cmdCandidate)) return listOf(cmdCandidate.toAbsolutePath().toString()) to "path"
            }
        }

        // Fallback: check current working directory or ancestors for gradlew (useful in dev/test runs)
        var cursor: Path? = Path.of("").toAbsolutePath().normalize()
        while (cursor != null) {
            val wrapper = cursor.resolve(wrapperName)
            if (Files.isRegularFile(wrapper)) return listOf(wrapper.toAbsolutePath().toString()) to "host-wrapper"
            cursor = cursor.parent
        }

        return emptyList<String>() to "missing"
    }

    fun describe(includeExample: Boolean = true, sdkDir: Path = configuredSdkDir() ?: defaultSdkDir()): JsonObject = buildJsonObject {
        put("host_api_version", HOST_API_VERSION)
        putJsonObject("protocol") {
            put("min", HOST_PROTOCOL_MIN)
            put("max", HOST_PROTOCOL_MAX)
            put("default", HOST_PROTOCOL_MIN)
        }
        putJsonObject("context_version") {
            put("min", SUPPORTED_CONTEXT_VERSION_MIN)
            put("max", SUPPORTED_CONTEXT_VERSION_MAX)
        }
        putJsonArray("targets") { supportedTargets.sorted().forEach { add(it) } }
        putJsonArray("components") { ModifierPluginOutput.components.sorted().forEach { add(it) } }
        putJsonArray("derived_output_kinds") { supportedDerivedOutputKinds.sorted().forEach { add(it) } }
        putJsonArray("config_schema_keywords") { supportedConfigSchemaKeywords.sorted().forEach { add(it) } }
        putJsonArray("ui_field_types") { supportedUiFieldTypes.sorted().forEach { add(it) } }
        putJsonObject("creates_modifies_rules") {
            put("wildcard_forbidden", true)
            put("max_length", 128)
            put("no_iso_control", true)
        }
        putJsonObject("output_id_rules") {
            put("format", "modifier:<instanceId>:<hexUtf8(localName)>")
            put("supports_output_aliases", true)
            put("deterministic_across_replays", true)
        }
        putJsonArray("build_profiles") {
            add(buildJsonObject {
                put("id", CUSTOM_PLUGIN_PROFILE)
                put("task", "pluginPackage")
                put("sdk_property", "psd2live.sdkDir")
                put("version_property", "psd2live.pluginVersion")
            })
        }
        put("environment", environmentCheck(sdkDir = sdkDir, stageSdkIfMissing = false).toJson())
        if (includeExample) {
            put("minimal_example", minimalExample())
        }
    }

    fun minimalExample(): JsonObject {
        val pluginId = "custom.example"
        val algoType = "$pluginId/opacity_scale"
        return buildJsonObject {
            putJsonObject("plugin_meta") {
                put("protocol", 1)
                put("id", pluginId)
                put("version", "0.1.0")
                put("name", "Example Opacity Scale")
                putJsonArray("algorithms") {
                    add(buildJsonObject {
                        put("type", algoType)
                        put("version", 1)
                        put("name", "Opacity Scale")
                        put("category", "plugins")
                        putJsonArray("targets") { add("mesh") }
                        putJsonArray("reads") { add("mesh") }
                        putJsonArray("writes") { add("mesh") }
                        putJsonArray("composableKinds") { add("mesh") }
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
            put("worker_source", PluginDevScaffold.renderWorkerSource(
                packageName = "custom.example",
                algorithmType = algoType,
            ))
            put("build_gradle_kts", PluginDevScaffold.renderBuildGradleKts(pluginId))
        }
    }
}
