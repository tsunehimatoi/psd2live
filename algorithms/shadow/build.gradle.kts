// GPL algorithm: uses Umamo; not part of the MIT format/API modules.
import java.security.MessageDigest
import java.util.HexFormat

plugins {
    `java-library`
    kotlin("jvm")
    kotlin("plugin.serialization")
}

group = "io.github.psd2live.algorithms"
version = "1.0.0"
kotlin { jvmToolchain(21) }
java { withSourcesJar() }

dependencies {
    api(project(":algorithm-api"))
    api(project(":umamo"))
    implementation(project(":umamo-ir-bridge"))
    implementation(project(":algorithm-worker"))
    testImplementation(kotlin("test"))
}

val workerJar by tasks.registering(Jar::class) {
    dependsOn(configurations.runtimeClasspath)
    archiveClassifier.set("worker")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest { attributes["Main-Class"] = "io.github.psd2live.algorithms.shadow.ShadowWorker" }
    from(sourceSets.main.get().output)
    from(configurations.runtimeClasspath.map { files -> files.map { if (it.isDirectory) it else zipTree(it) } })
    exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA")
}

private data class ShadowManifestSpec(
    val slug: String,
    val alias: String,
    val entrypoint: String,
    val name: String,
    val reads: String,
    val writes: String,
    val composableKinds: String,
)

private val shadowSpecs = listOf(
    ShadowManifestSpec(
        slug = "shadow-world",
        alias = "shadow.world",
        entrypoint = "world",
        name = "World Light",
        reads = "",
        writes = "",
        composableKinds = "",
    ),
    ShadowManifestSpec(
        slug = "shadow-cast",
        alias = "shadow.cast",
        entrypoint = "cast",
        name = "Cast Shadow",
        reads = "\"*\"",
        writes = "\"mesh\",\"part\",\"deformer\",\"hierarchy\",\"parameter\",\"textures\",\"authoring\"",
        composableKinds = "\"mesh\",\"part\",\"deformer\",\"hierarchy\"",
    ),
    ShadowManifestSpec(
        slug = "shadow-form",
        alias = "shadow.form",
        entrypoint = "form",
        name = "Form Shadow",
        reads = "\"*\"",
        writes = "\"mesh\",\"part\",\"deformer\",\"hierarchy\",\"parameter\",\"textures\",\"authoring\"",
        composableKinds = "\"mesh\",\"part\",\"deformer\",\"hierarchy\"",
    ),
)

tasks.register("pluginPackages") {
    dependsOn(workerJar)
    val output = layout.buildDirectory.dir("plugin-packages")
    inputs.file(workerJar.flatMap { it.archiveFile })
    outputs.dir(output)
    doLast {
        val root = output.get().asFile.apply { mkdirs() }
        val jar = workerJar.get().archiveFile.get().asFile
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(jar.readBytes()))
        for (spec in shadowSpecs) {
            val dir = root.resolve(spec.slug).apply { mkdirs() }
            jar.copyTo(dir.resolve("algorithm.jar"), overwrite = true)
            val pkgId = "builtin." + HexFormat.of().formatHex(spec.alias.toByteArray(Charsets.UTF_8)) + ".v1"
            dir.resolve("plugin.json").writeText(
                """
                {"protocol":1,"id":"$pkgId","version":"1.0.0","name":"${spec.name}",
                 "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
                 "algorithms":[{"type":"$pkgId/${spec.entrypoint}","aliases":["${spec.alias}"],"version":1,"name":"${spec.name}","category":"shadow",
                   "targets":["mesh"],"reads":[${spec.reads}],"writes":[${spec.writes}],"composableKinds":[${spec.composableKinds}],
                   "configSchema":{"type":"object"},"defaults":{}}]}
                """.trimIndent()
            )
        }
        root.resolve("index.txt").writeText(shadowSpecs.joinToString("\n", postfix = "\n") { it.slug }, Charsets.UTF_8)
    }
}
