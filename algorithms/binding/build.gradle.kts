import java.util.HexFormat
import java.security.MessageDigest

// Binding kernels use the GPL engine; they are separate from the MIT API/format modules.
plugins { `java-library`; kotlin("jvm") }
group = "io.github.psd2live.algorithms"
version = "1.0.31"
kotlin { jvmToolchain(21) }
dependencies {
    api(project(":umamo"))
    api(project(":algorithm-api"))
    implementation(project(":format-compile"))
    implementation(project(":algorithm-worker"))
    implementation(project(":targets:cubism"))
    testImplementation(kotlin("test"))
}
tasks.withType<Test>().configureEach { useJUnitPlatform(); filter.isFailOnNoMatchingTests = false }

val workerJar by tasks.registering(Jar::class) {
    dependsOn(configurations.runtimeClasspath)
    archiveClassifier.set("worker")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest { attributes["Main-Class"] = "io.github.psd2live.algorithms.binding.ExpressionWorker" }
    from(sourceSets.main.get().output)
    from(configurations.runtimeClasspath.map { files -> files.map { if (it.isDirectory) it else zipTree(it) } })
    exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA")
}

tasks.register("pluginPackage") {
    dependsOn(workerJar)
    val output = layout.buildDirectory.dir("plugin")
    inputs.file(workerJar.flatMap { it.archiveFile })
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val jar = workerJar.get().archiveFile.get().asFile
        val payload = directory.resolve("algorithm.jar")
        jar.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"builtin.65787072657373696f6e2e746f67676c6573.v1","version":"1.0.31","name":"Expressions",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"builtin.65787072657373696f6e2e746f67676c6573.v1/toggles","aliases":["expression.toggles"],"version":1,"name":"Expressions","category":"expression",
               "targets":["mesh"],"requires":["rig.scaffold"],"exclusiveFamily":"expression.toggles",
               "reads":["*"],"writes":["*"],"composableKinds":["mesh","part","deformer","hierarchy","relations","parameter","physics"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

for (revision in 1..2) {
    tasks.register("pluginScaffold$revision") {
        dependsOn(workerJar)
        val output = layout.buildDirectory.dir("plugin-scaffold-$revision")
        inputs.file(workerJar.flatMap { it.archiveFile })
        inputs.property("packageVersion", project.version.toString())
        outputs.dir(output)
        doLast {
            val directory = output.get().asFile.apply { mkdirs() }
            val payload = directory.resolve("algorithm.jar")
            workerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
            val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
            val id = "builtin." + HexFormat.of().formatHex("rig.scaffold".toByteArray(Charsets.UTF_8)) + ".v$revision"
            directory.resolve("plugin.json").writeText("""
                {"protocol":1,"id":"$id","version":"${project.version}","name":"Binding structure",
                 "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
                 "algorithms":[{"type":"$id/structure","aliases":["rig.scaffold"],"version":$revision,"name":"Binding structure","category":"body",
                   "targets":["mesh"],"exclusiveFamily":"rig.scaffold","reads":["*"],"writes":["*"],
                   "composableKinds":["mesh","part","deformer","hierarchy"],"configSchema":{"type":"object"},"defaults":{}}]}
            """.trimIndent())
        }
    }
}

// Each stable built-in identity has its own installable package and legacy alias.
for ((type, area, taskName) in listOf(
    Triple("eye.see-through", "eye", "pluginEyeSeeThrough"),
    Triple("brow.see-through", "brow", "pluginBrowSeeThrough"),
    Triple("face.skin-clip", "skin", "pluginSkinClip"),
)) {
    tasks.register(taskName) {
        dependsOn(workerJar)
        val output = layout.buildDirectory.dir("plugin-$area")
        inputs.file(workerJar.flatMap { it.archiveFile })
        inputs.property("packageVersion", project.version.toString())
        outputs.dir(output)
        doLast {
            val directory = output.get().asFile.apply { mkdirs() }
            val payload = directory.resolve("algorithm.jar")
            workerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
            val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
            val entrypoint = if (area == "skin") "clip" else "visibility"
            val title = if (area == "skin") "Skin clipping" else "$area through bangs"
            val id = "builtin." + HexFormat.of().formatHex(type.toByteArray(Charsets.UTF_8)) + ".v1"
            directory.resolve("plugin.json").writeText("""
                {"protocol":1,"id":"$id","version":"${project.version}","name":"$title",
                 "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
                 "algorithms":[{"type":"$id/$entrypoint","aliases":["$type"],"version":1,"name":"$title","category":"$area",
                   "targets":["mesh"],"requires":["rig.scaffold"],"reads":["*"],"writes":["*"],
                   "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter"],
                   "configSchema":{"type":"object"},"defaults":{}}]}
            """.trimIndent())
        }
    }
}
