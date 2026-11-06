// GPL algorithm: uses Umamo; not part of the MIT format/API modules.
import java.util.HexFormat
import java.security.MessageDigest

plugins {
    `java-library`
    kotlin("jvm")
}

group = "io.github.psd2live.algorithms"
version = "1.0.10"
kotlin { jvmToolchain(21) }
java { withSourcesJar() }

dependencies {
    api(project(":algorithm-api"))
    api(project(":umamo"))
    implementation(project(":targets:cubism"))
    implementation(project(":algorithms:physics"))
    implementation(project(":algorithm-worker"))
    testImplementation(kotlin("test"))
}

val workerJar by tasks.registering(Jar::class) {
    dependsOn(configurations.runtimeClasspath)
    archiveClassifier.set("worker")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest { attributes["Main-Class"] = "io.github.psd2live.algorithms.swing.SwingWorker" }
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
            {"protocol":1,"id":"builtin.7377696e67.v1","version":"1.0.10","name":"Swing",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"builtin.7377696e67.v1/sway","aliases":["swing"],"version":1,"name":"Swing","category":"physics",
               "targets":["mesh","deformer"],"reads":["*"],"writes":["*"],"composableKinds":["deformer","mesh","hierarchy"],
               "derivedOutputs":[{"kind":"deformer","localName":"swing","prefix":"Warp_Swing_","numbered":true},
                 {"kind":"physics","localName":"swing","prefix":"PhysicsSwing_","suffixes":["","_X","_Y"]}],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    filter.isFailOnNoMatchingTests = false
}
