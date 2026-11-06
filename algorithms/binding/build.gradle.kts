import java.util.HexFormat
import java.security.MessageDigest

// Binding kernels use the GPL engine; they are separate from the MIT API/format modules.
plugins { `java-library`; kotlin("jvm") }
group = "io.github.psd2live.algorithms"
version = "1.0.5"
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
            {"protocol":1,"id":"builtin.65787072657373696f6e2e746f67676c6573.v1","version":"1.0.5","name":"Expressions",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"builtin.65787072657373696f6e2e746f67676c6573.v1/toggles","aliases":["expression.toggles"],"version":1,"name":"Expressions","category":"expression",
               "targets":["mesh"],"requires":["rig.scaffold"],"exclusiveFamily":"expression.toggles",
               "reads":["*"],"writes":["*"],"composableKinds":["mesh","part","deformer","hierarchy","relations","parameter","physics"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}
