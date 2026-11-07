import java.util.HexFormat
import java.security.MessageDigest

// Binding kernels use the GPL engine; they are separate from the MIT API/format modules.
plugins { `java-library`; kotlin("jvm"); kotlin("plugin.serialization") }
group = "io.github.psd2live.algorithms"
version = "1.0.63"
kotlin { jvmToolchain(21) }
dependencies {
    api(project(":umamo"))
    api(project(":algorithm-api"))
    implementation(project(":format-compile"))
    api(project(":algorithms:physics"))
    implementation(project(":algorithm-worker"))
    implementation(project(":targets:cubism"))
    testImplementation(kotlin("test"))
}
tasks.withType<Test>().configureEach { useJUnitPlatform(); filter.isFailOnNoMatchingTests = false }

val neckWorkerClasses = listOf("**/NeckAlgorithm*.class", "**/NeckInputs*.class", "**/NeckWorker*.class")

val bustWorkerClasses = listOf("**/BustBindingAlgorithm*.class", "**/BustBindingInputs*.class", "**/BustBindingWorker*.class")

val hairLegacyWorkerClasses = listOf("**/HairLegacy*.class")

val lidFadeWorkerClasses = listOf("**/LidFadeAlgorithm*.class", "**/LidFadeInputs*.class", "**/LidFadeWorker*.class")

val mouthFittedCandidateClasses = listOf("**/MouthFittedCandidateWorker*.class", "**/MouthFittedCandidateAlgorithm*.class")

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
    exclude(neckWorkerClasses + bustWorkerClasses + hairLegacyWorkerClasses + lidFadeWorkerClasses + mouthFittedCandidateClasses)
}

tasks.register("pluginPackage") {
    dependsOn(workerJar)
    val output = layout.buildDirectory.dir("plugin")
    inputs.file(workerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", project.version.toString())
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val jar = workerJar.get().archiveFile.get().asFile
        val payload = directory.resolve("algorithm.jar")
        jar.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"builtin.65787072657373696f6e2e746f67676c6573.v1","version":"${project.version}","name":"Expressions",
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
                   "targets":["mesh"],"exclusiveFamily":"rig.scaffold","reads":["*"],"writes":["mesh","part","deformer","hierarchy"],
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
    Triple("mouth.outline", "mouth-outline", "pluginMouthOutline"),
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
            val entrypoint = when (area) {
                "skin" -> "clip"
                "mouth-outline" -> "outline"
                else -> "visibility"
            }
            val title = when (area) {
                "skin" -> "Skin clipping"
                "mouth-outline" -> "Mouth outline"
                else -> "$area through bangs"
            }
            val writes = when (area) {
                "skin" -> """["mesh","relations"]"""
                "mouth-outline" -> """["mesh","part","hierarchy","textures","authoring","relations"]"""
                else -> """["*"]"""
            }
            val category = if (area == "mouth-outline") "mouth" else area
            val sourceContext = if (area == "mouth-outline") """"contextVersion":2,"exclusiveFamily":"mouth.outline",""" else ""
            val id = "builtin." + HexFormat.of().formatHex(type.toByteArray(Charsets.UTF_8)) + ".v1"
            directory.resolve("plugin.json").writeText("""
                {"protocol":1,"id":"$id","version":"${project.version}","name":"$title",
                 "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
                 "algorithms":[{"type":"$id/$entrypoint","aliases":["$type"],"version":1,"name":"$title","category":"$category",
                   "targets":["mesh"],"requires":["rig.scaffold"],"reads":["*"],"writes":$writes,
                   ${sourceContext}"composableKinds":["mesh","part","deformer","hierarchy","relations","parameter"],
                   "configSchema":{"type":"object"},"defaults":{}}]}
            """.trimIndent())
        }
    }
}

// Standalone body package. The application can bundle it once the head pitch provider is available.
tasks.register("pluginBodyLegacy") {
    dependsOn(workerJar)
    val output = layout.buildDirectory.dir("plugin-body-legacy")
    inputs.file(workerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", project.version.toString())
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        workerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin." + HexFormat.of().formatHex("body/legacy".encodeToByteArray()) + ".v1"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"${project.version}","name":"Legacy body",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/motion","aliases":["body/legacy"],"version":1,"name":"Legacy body","category":"body",
               "targets":["mesh"],"requires":["rig.scaffold"],"exclusiveFamily":"base:body","contextVersion":2,
               "reads":["*"],"writes":["mesh","deformer","hierarchy","relations","parameter"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val mouthLegacyWorkerJar by tasks.registering(Jar::class) {
    dependsOn(workerJar)
    archiveClassifier.set("worker-mouth-legacy")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest { attributes["Main-Class"] = "io.github.psd2live.algorithms.binding.MouthLegacyWorker" }
    from(sourceSets.main.get().output) { include("**/MouthLegacy*.class") }
    from(workerJar.flatMap { it.archiveFile }.map { zipTree(it) }) { exclude("META-INF/MANIFEST.MF") }
}

tasks.register("pluginMouthLegacy") {
    dependsOn(mouthLegacyWorkerJar)
    val output = layout.buildDirectory.dir("plugin-mouth-legacy")
    inputs.file(mouthLegacyWorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", project.version.toString())
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        mouthLegacyWorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.6d6f7574682f6c6567616379.v2"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"${project.version}","name":"Legacy mouth",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/binding","aliases":["mouth/legacy"],"version":2,"name":"Legacy mouth","category":"mouth",
               "targets":["mesh"],"requires":["rig.scaffold"],"exclusiveFamily":"base:mouth","contextVersion":2,
               "reads":["*"],"writes":["mesh","parameter","hierarchy","rest_pose","relations"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val fittedFaceLattices = sourceSets.create("fittedFaceLattices")
kotlin.target.compilations.named("fittedFaceLattices") {
    associateWith(kotlin.target.compilations.getByName("main"))
}

val fittedFaceLatticesWorkerJar by tasks.registering(Jar::class) {
    archiveClassifier.set("worker-fitted-face-lattices")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest { attributes["Main-Class"] = "io.github.psd2live.algorithms.binding.FittedFaceLatticesWorker" }
    from(fittedFaceLattices.output)
    from(workerJar.flatMap { it.archiveFile }.map { zipTree(it) }) { exclude("META-INF/MANIFEST.MF") }
}

tasks.register("pluginFittedFaceLattices") {
    dependsOn(fittedFaceLatticesWorkerJar)
    val output = layout.buildDirectory.dir("plugin-fitted-face-lattices")
    inputs.file(fittedFaceLatticesWorkerJar.flatMap { it.archiveFile })
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        fittedFaceLatticesWorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"psd2live.fitted-face-lattices","version":"1.1.0","name":"Fitted face lattices",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"psd2live.fitted-face-lattices/styled","version":1,"name":"Fitted face lattices","category":"face",
               "contextVersion":2,"targets":["mesh"],"requires":["rig.scaffold"],"reads":["deformer"],"writes":["deformer"],
               "composableKinds":["deformer"],"configSchema":{"type":"object","additionalProperties":false},"defaults":{}},
              {"type":"psd2live.fitted-face-lattices/binding","version":1,"name":"Fitted face binding","category":"face",
               "contextVersion":2,"targets":["mesh"],"requires":["rig.scaffold"],"reads":["*"],
               "writes":["mesh","deformer","parameter","hierarchy","relations"],"composableKinds":["mesh","deformer","parameter","hierarchy","relations"],
               "configSchema":{"type":"object","additionalProperties":false},"defaults":{}}]}
        """.trimIndent())
    }
}

val fittedFaceV2 = sourceSets.create("fittedFaceV2")
kotlin.target.compilations.named("fittedFaceV2") {
    associateWith(kotlin.target.compilations.getByName("main"))
    associateWith(kotlin.target.compilations.getByName("fittedFaceLattices"))
}
val fittedFaceV2ClassesJar by tasks.registering(Jar::class) {
    archiveClassifier.set("fitted-face-v2")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    from(fittedFaceV2.output)
    from(fittedFaceLattices.output)
}
val fittedFaceV2Elements = configurations.create("fittedFaceV2Elements") {
    isCanBeConsumed = true
    isCanBeResolved = false
}
artifacts.add(fittedFaceV2Elements.name, fittedFaceV2ClassesJar)

val fittedFaceV2WorkerJar by tasks.registering(Jar::class) {
    archiveClassifier.set("worker-fitted-face-v2")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest { attributes["Main-Class"] = "io.github.psd2live.algorithms.binding.FittedFaceV2Worker" }
    from(fittedFaceV2.output)
    from(fittedFaceLatticesWorkerJar.flatMap { it.archiveFile }.map { zipTree(it) }) { exclude("META-INF/MANIFEST.MF") }
}

tasks.register("pluginFittedFaceV2") {
    dependsOn(fittedFaceV2WorkerJar)
    val output = layout.buildDirectory.dir("plugin-fitted-face-v2")
    inputs.file(fittedFaceV2WorkerJar.flatMap { it.archiveFile })
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        fittedFaceV2WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.666163652f666974746564.v2"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"1.0.0","name":"Fitted face v2",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/binding","aliases":["face/fitted"],"version":2,"contextVersion":2,"name":"Fitted face v2","category":"face",
               "targets":["mesh"],"requires":["rig.scaffold"],"provides":["face/legacy"],"exclusiveFamily":"base:face",
               "reads":["*"],"writes":["mesh","deformer","parameter","hierarchy","relations"],
               "composableKinds":["mesh","deformer","parameter","hierarchy","relations"],"configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val fittedFaceV3 = sourceSets.create("fittedFaceV3")
kotlin.target.compilations.named("fittedFaceV3") {
    associateWith(kotlin.target.compilations.getByName("main"))
    associateWith(kotlin.target.compilations.getByName("fittedFaceV2"))
}
val fittedFaceV3ClassesJar by tasks.registering(Jar::class) {
    archiveClassifier.set("fitted-face-v3")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    from(fittedFaceV3.output)
}
val fittedFaceV3Elements = configurations.create("fittedFaceV3Elements") {
    isCanBeConsumed = true
    isCanBeResolved = false
}
artifacts.add(fittedFaceV3Elements.name, fittedFaceV3ClassesJar)

val fittedFaceV3WorkerJar by tasks.registering(Jar::class) {
    archiveClassifier.set("worker-fitted-face-v3")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest { attributes["Main-Class"] = "io.github.psd2live.algorithms.binding.FittedFaceV3Worker" }
    from(fittedFaceV3.output)
    from(fittedFaceV2WorkerJar.flatMap { it.archiveFile }.map { zipTree(it) }) { exclude("META-INF/MANIFEST.MF") }
}

tasks.register("pluginFittedFaceV3") {
    dependsOn(fittedFaceV3WorkerJar)
    val output = layout.buildDirectory.dir("plugin-fitted-face-v3")
    inputs.file(fittedFaceV3WorkerJar.flatMap { it.archiveFile })
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        fittedFaceV3WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.666163652f666974746564.v3"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"1.0.0","name":"Fitted face v3",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/binding","aliases":["face/fitted"],"version":3,"contextVersion":2,"name":"Fitted face v3","category":"face",
               "targets":["mesh"],"requires":["rig.scaffold"],"provides":["face/legacy"],"exclusiveFamily":"base:face",
               "reads":["*"],"writes":["mesh","deformer","parameter","hierarchy","relations"],
               "composableKinds":["mesh","deformer","parameter","hierarchy","relations"],"configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

tasks.register("pluginDepthTurn") {
    dependsOn(workerJar)
    val output = layout.buildDirectory.dir("plugin-depth-turn")
    inputs.file(workerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", project.version.toString())
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        workerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin." + HexFormat.of().formatHex("face.depth-turn".toByteArray(Charsets.UTF_8)) + ".v1"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"${project.version}","name":"Depth turn",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/turn","aliases":["face.depth-turn"],"version":1,"name":"Depth turn","category":"face","contextVersion":2,
               "targets":["mesh"],"requires":["rig.scaffold","face/legacy"],"reads":["*"],"writes":["mesh","deformer","parameter"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter"],"configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val depthTurnV2WorkerJar = registerIsolatedBindingWorker(
    "depthTurnV2", "depth-turn-v2", "worker-depth-turn-v2", "io.github.psd2live.algorithms.binding.DepthTurnV2Worker",
)

tasks.register("pluginDepthTurnV2") {
    dependsOn(depthTurnV2WorkerJar)
    val output = layout.buildDirectory.dir("plugin-depth-turn-v2")
    inputs.file(depthTurnV2WorkerJar.flatMap { it.archiveFile })
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        depthTurnV2WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.666163652e64657074682d7475726e.v2"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"1.0.0","name":"Depth turn",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/turn","aliases":["face.depth-turn"],"version":2,"name":"Depth turn","category":"face","contextVersion":2,
               "targets":["mesh"],"requires":["rig.scaffold","face/legacy"],"reads":["*"],"writes":["mesh","deformer"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter"],"configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

tasks.register("pluginBrowFitted") {
    dependsOn(workerJar)
    val output = layout.buildDirectory.dir("plugin-brow-fitted")
    inputs.file(workerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", project.version.toString())
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        workerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin." + HexFormat.of().formatHex("brow/fitted".toByteArray(Charsets.UTF_8)) + ".v1"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"${project.version}","name":"Fitted brows",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/binding","aliases":["brow/fitted"],"version":1,"contextVersion":2,"name":"Fitted brows","category":"brow",
               "targets":["mesh"],"requires":["rig.scaffold"],"exclusiveFamily":"base:brow",
               "reads":["*"],"writes":["mesh","parameter","hierarchy","rest_pose"],"composableKinds":["mesh","parameter","hierarchy"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val mouthFittedCandidateWorkerJar by tasks.registering(Jar::class) {
    dependsOn(workerJar)
    archiveClassifier.set("mouth-fitted-candidate-worker")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest { attributes["Main-Class"] = "io.github.psd2live.algorithms.binding.MouthFittedCandidateWorker" }
    from(sourceSets.main.get().output) { include(mouthFittedCandidateClasses) }
    from(workerJar.flatMap { it.archiveFile }.map { zipTree(it) }) { exclude("META-INF/MANIFEST.MF") }
}

val pluginMouthFitted by tasks.registering {
    dependsOn(mouthFittedCandidateWorkerJar)
    val output = layout.buildDirectory.dir("plugin-mouth-fitted")
    inputs.file(mouthFittedCandidateWorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", project.version.toString())
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        mouthFittedCandidateWorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "psd2live.mouth-fitted-source"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"${project.version}","name":"Fitted mouth source",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/source","version":1,"contextVersion":2,"name":"Fitted mouth source","category":"mouth",
               "targets":["mesh"],"requires":["rig.scaffold"],"exclusiveFamily":"base:mouth",
               "reads":["*"],"writes":["mesh","parameter","part","hierarchy","textures"],"composableKinds":["mesh","parameter","part","hierarchy"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val mouthFittedV2 = sourceSets.create("mouthFittedV2")
kotlin.target.compilations.named("mouthFittedV2") {
    associateWith(kotlin.target.compilations.getByName("main"))
}
val mouthFittedV2ClassesJar by tasks.registering(Jar::class) {
    archiveClassifier.set("mouth-fitted-v2")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    from(mouthFittedV2.output)
}
val mouthFittedV2Elements = configurations.create("mouthFittedV2Elements") {
    isCanBeConsumed = true
    isCanBeResolved = false
}
artifacts.add(mouthFittedV2Elements.name, mouthFittedV2ClassesJar)

val mouthFittedV2WorkerJar by tasks.registering(Jar::class) {
    dependsOn(workerJar)
    archiveClassifier.set("worker-mouth-fitted-v2")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest { attributes["Main-Class"] = "io.github.psd2live.algorithms.binding.MouthFittedV2Worker" }
    from(mouthFittedV2.output)
    from(workerJar.flatMap { it.archiveFile }.map { zipTree(it) }) { exclude("META-INF/MANIFEST.MF") }
}

tasks.register("pluginMouthFittedV2") {
    dependsOn(mouthFittedV2WorkerJar)
    val output = layout.buildDirectory.dir("plugin-mouth-fitted-v2")
    inputs.file(mouthFittedV2WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", project.version.toString())
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        mouthFittedV2WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.6d6f7574682f666974746564.v2"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"${project.version}","name":"Fitted mouth v2",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/binding","aliases":["mouth/fitted"],"version":2,"contextVersion":2,"name":"Fitted mouth v2","category":"mouth",
               "targets":["mesh"],"requires":["rig.scaffold"],"exclusiveFamily":"base:mouth",
               "reads":["*"],"writes":["mesh","parameter","hierarchy","rest_pose"],"composableKinds":["mesh","parameter","hierarchy"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

tasks.register<Test>("testMouthFittedPackage") {
    dependsOn(pluginMouthFitted)
    inputs.files(pluginMouthFitted.map { it.outputs.files })
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter.includeTestsMatching("example.MouthFittedAlgorithmTest")
    systemProperty("psd2live.mouthFittedPackage", layout.buildDirectory.dir("plugin-mouth-fitted").get().asFile.absolutePath)
}

tasks.register("pluginCheek") {
    dependsOn(workerJar)
    val output = layout.buildDirectory.dir("plugin-cheek")
    inputs.file(workerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", project.version.toString())
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        workerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin." + HexFormat.of().formatHex("expression.cheek".toByteArray(Charsets.UTF_8)) + ".v1"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"${project.version}","name":"Cheek",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/color","aliases":["expression.cheek"],"version":1,"name":"Cheek","category":"expression",
               "targets":["mesh"],"requires":["rig.scaffold"],"reads":["mesh","parameter","hierarchy"],
               "writes":["mesh","parameter","hierarchy"],"contextVersion":2,
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

tasks.register("pluginGloom") {
    dependsOn(workerJar)
    val output = layout.buildDirectory.dir("plugin-gloom")
    val packageVersion = project.version.toString()
    inputs.file(workerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", packageVersion)
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        workerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.65787072657373696f6e2e676c6f6f6d.v1"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"$packageVersion","name":"Gloom",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/gloom","aliases":["expression.gloom"],"version":1,"name":"Gloom","category":"expression",
               "targets":["mesh"],"requires":["rig.scaffold"],"reads":["*"],"writes":["mesh","parameter","hierarchy"],
               "composableKinds":["mesh","parameter","hierarchy"],"configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

// Separate entrypoint and package identity for the independent hair binding.
val hairChainsWorkerJar by tasks.registering(Jar::class) {
    dependsOn(configurations.runtimeClasspath)
    archiveClassifier.set("hair-chains-worker")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest { attributes["Main-Class"] = "io.github.psd2live.algorithms.binding.HairChainsWorker" }
    from(sourceSets.main.get().output)
    from(configurations.runtimeClasspath.map { files -> files.map { if (it.isDirectory) it else zipTree(it) } })
    exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA")
    exclude(neckWorkerClasses + bustWorkerClasses + hairLegacyWorkerClasses + lidFadeWorkerClasses + mouthFittedCandidateClasses)
}

tasks.register("pluginHairChains") {
    dependsOn(hairChainsWorkerJar)
    val output = layout.buildDirectory.dir("plugin-hair-chains")
    inputs.file(hairChainsWorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", project.version.toString())
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        hairChainsWorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"builtin.686169722f636861696e73.v2","version":"${project.version}","name":"Hair chains",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"builtin.686169722f636861696e73.v2/chains","aliases":["hair/chains"],
               "version":2,"name":"Hair chains","category":"hair","contextVersion":2,"targets":["mesh"],
               "requires":["rig.scaffold","body/legacy","face/legacy"],"exclusiveFamily":"base:hair",
               "reads":["*"],"writes":["mesh","deformer","hierarchy","parameter","physics"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter","physics"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val neckWorkerJar by tasks.registering(Jar::class) {
    dependsOn(workerJar)
    archiveClassifier.set("worker-face-neck")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest { attributes["Main-Class"] = "io.github.psd2live.algorithms.binding.NeckWorker" }
    from(sourceSets.main.get().output) { include(neckWorkerClasses) }
    from(workerJar.flatMap { it.archiveFile }.map { zipTree(it) }) { exclude("META-INF/MANIFEST.MF") }
}

tasks.register("pluginFaceNeck") {
    dependsOn(neckWorkerJar)
    val output = layout.buildDirectory.dir("plugin-face-neck")
    val packageVersion = project.version.toString()
    inputs.file(neckWorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", packageVersion)
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        neckWorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.666163652e6e65636b.v2"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"$packageVersion","name":"Neck tilt",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/tilt","aliases":["face.neck"],"version":2,"name":"Neck tilt","category":"face",
               "targets":["mesh"],"requires":["rig.scaffold","face/legacy"],"contextVersion":2,
               "reads":["mesh","deformer","parameter"],"writes":["mesh","relations"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val bustWorkerJar by tasks.registering(Jar::class) {
    dependsOn(workerJar)
    archiveClassifier.set("worker-body-bust")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest { attributes["Main-Class"] = "io.github.psd2live.algorithms.binding.BustBindingWorker" }
    from(sourceSets.main.get().output) { include(bustWorkerClasses) }
    from(workerJar.flatMap { it.archiveFile }.map { zipTree(it) }) { exclude("META-INF/MANIFEST.MF") }
}

tasks.register("pluginBust") {
    dependsOn(bustWorkerJar)
    val output = layout.buildDirectory.dir("plugin-bust")
    val packageVersion = project.version.toString()
    inputs.file(bustWorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", packageVersion)
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        bustWorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.626f64792e62757374.v2"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"$packageVersion","name":"Chest bounce",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/bust","aliases":["body.bust"],"version":2,"name":"Chest bounce","category":"body",
               "targets":["mesh"],"requires":["rig.scaffold","body/legacy","face/legacy"],"contextVersion":2,
               "reads":["mesh","deformer","parameter","hierarchy","physics","textures"],"writes":["mesh","parameter","hierarchy","physics"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter","physics"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val hairLegacyWorkerJar by tasks.registering(Jar::class) {
    dependsOn(workerJar)
    archiveClassifier.set("hair-legacy-worker")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest { attributes["Main-Class"] = "io.github.psd2live.algorithms.binding.HairLegacyWorker" }
    from(sourceSets.main.get().output) { include(hairLegacyWorkerClasses) }
    from(workerJar.flatMap { it.archiveFile }.map { zipTree(it) }) { exclude("META-INF/MANIFEST.MF") }
}

tasks.register("pluginHairLegacy") {
    dependsOn(hairLegacyWorkerJar)
    val output = layout.buildDirectory.dir("plugin-hair-legacy")
    inputs.file(hairLegacyWorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", project.version.toString())
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        hairLegacyWorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"builtin.686169722f6c6567616379.v2","version":"${project.version}","name":"Legacy hair",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"builtin.686169722f6c6567616379.v2/legacy","aliases":["hair/legacy"],
               "version":2,"name":"Legacy hair","category":"hair","contextVersion":1,"targets":["mesh"],
               "requires":["rig.scaffold"],"exclusiveFamily":"base:hair",
               "reads":["*"],"writes":["mesh","deformer","hierarchy","parameter"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val lidFadeWorkerJar by tasks.registering(Jar::class) {
    dependsOn(workerJar)
    archiveClassifier.set("worker-eye-lid-fade")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest { attributes["Main-Class"] = "io.github.psd2live.algorithms.binding.LidFadeWorker" }
    from(sourceSets.main.get().output) { include(lidFadeWorkerClasses) }
    from(workerJar.flatMap { it.archiveFile }.map { zipTree(it) }) { exclude("META-INF/MANIFEST.MF") }
}

tasks.register("pluginEyeLidFade") {
    dependsOn(lidFadeWorkerJar)
    val output = layout.buildDirectory.dir("plugin-eye-lid-fade")
    val packageVersion = project.version.toString()
    inputs.file(lidFadeWorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", packageVersion)
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        lidFadeWorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.6579652e6c69642d66616465.v2"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"$packageVersion","name":"Lid lines fade early",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/fade","aliases":["eye.lid-fade"],"version":2,"name":"Lid lines fade early","category":"eye",
               "targets":["mesh"],"requires":["rig.scaffold","eye/fitted"],"contextVersion":2,
               "reads":["mesh","parameter"],"writes":["mesh"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val eyeFitted = sourceSets.create("eyeFitted")
kotlin.target.compilations.named("eyeFitted") {
    associateWith(kotlin.target.compilations.getByName("main"))
}
val eyeFittedClassesJar by tasks.registering(Jar::class) {
    archiveClassifier.set("eye-fitted")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    from(eyeFitted.output)
}
val eyeFittedElements = configurations.create("eyeFittedElements") {
    isCanBeConsumed = true
    isCanBeResolved = false
}
artifacts.add(eyeFittedElements.name, eyeFittedClassesJar)

val eyeFittedWorkerJar by tasks.registering(Jar::class) {
    dependsOn(workerJar)
    archiveClassifier.set("worker-eye-fitted")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest { attributes["Main-Class"] = "io.github.psd2live.algorithms.binding.EyeFittedWorker" }
    from(eyeFitted.output)
    from(workerJar.flatMap { it.archiveFile }.map { zipTree(it) }) { exclude("META-INF/MANIFEST.MF") }
}

tasks.register("pluginEyeFitted") {
    dependsOn(eyeFittedWorkerJar)
    val output = layout.buildDirectory.dir("plugin-eye-fitted")
    val packageVersion = project.version.toString()
    inputs.file(eyeFittedWorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", packageVersion)
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        eyeFittedWorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.6579652f666974746564.v3"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"$packageVersion","name":"Fitted eyes (source)",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/fitted","aliases":["eye/fitted"],"version":3,"name":"Fitted eyes (source)","category":"eye",
               "targets":["mesh"],"requires":["rig.scaffold"],"contextVersion":2,
               "exclusiveFamily":"base:eye","reads":["*"],"writes":["mesh","deformer","parameter","relations","hierarchy"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val mouthOutline = sourceSets.create("mouthOutline")
kotlin.target.compilations.named("mouthOutline") {
    associateWith(kotlin.target.compilations.getByName("main"))
}
val mouthOutlineClassesJar by tasks.registering(Jar::class) {
    archiveClassifier.set("mouth-outline")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    from(mouthOutline.output)
}
val mouthOutlineElements = configurations.create("mouthOutlineElements") {
    isCanBeConsumed = true
    isCanBeResolved = false
}
artifacts.add(mouthOutlineElements.name, mouthOutlineClassesJar)

val mouthOutlineWorkerJar = tasks.register<Jar>("mouthOutlineWorkerJar") {
    dependsOn(workerJar)
    archiveClassifier.set("worker-mouth-outline")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest { attributes["Main-Class"] = "io.github.psd2live.algorithms.binding.MouthOutlineLegacyWorker" }
    from(mouthOutline.output)
    from(workerJar.flatMap { it.archiveFile }.map { zipTree(it) }) { exclude("META-INF/MANIFEST.MF") }
}

tasks.register("pluginMouthOutlineSource") {
    dependsOn(mouthOutlineWorkerJar)
    val output = layout.buildDirectory.dir("plugin-mouth-outline-source")
    inputs.file(mouthOutlineWorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", project.version.toString())
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        mouthOutlineWorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "psd2live.mouth-outline-source"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"${project.version}","name":"Legacy mouth outline source",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/outline","version":1,"contextVersion":2,"name":"Legacy mouth outline source","category":"mouth",
               "targets":["mesh"],"requires":["mouth/legacy"],"exclusiveFamily":"mouth.outline",
               "reads":["*"],"writes":["mesh","part","hierarchy","textures","authoring"],
               "composableKinds":["mesh","part","hierarchy"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

/** Isolate new entry points from the shared worker so adding one never changes its published bytes. */
fun registerIsolatedBindingWorker(
    compilation: String,
    hostClassifier: String,
    workerClassifier: String,
    mainClass: String,
): TaskProvider<Jar> {
    val sources = sourceSets.create(compilation)
    kotlin.target.compilations.named(compilation) {
        associateWith(kotlin.target.compilations.getByName("main"))
    }
    val classesJar = tasks.register<Jar>("${compilation}ClassesJar") {
        archiveClassifier.set(hostClassifier)
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
        from(sources.output)
    }
    val elements = configurations.create("${compilation}Elements") {
        isCanBeConsumed = true
        isCanBeResolved = false
    }
    artifacts.add(elements.name, classesJar)

    return tasks.register<Jar>("${compilation}WorkerJar") {
        dependsOn(workerJar)
        archiveClassifier.set(workerClassifier)
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        manifest { attributes["Main-Class"] = mainClass }
        from(sources.output)
        from(workerJar.flatMap { it.archiveFile }.map { zipTree(it) }) { exclude("META-INF/MANIFEST.MF") }
    }
}

val eyeSquashWorkerJar = registerIsolatedBindingWorker(
    "eyeSquash", "eye-squash", "worker-eye-squash", "io.github.psd2live.algorithms.binding.EyeSquashWorker",
)

val browShapeWorkerJar = registerIsolatedBindingWorker(
    "browShape", "brow-shape", "worker-brow-shape", "io.github.psd2live.algorithms.binding.BrowShapeWorker",
)

tasks.register("pluginBrowShape") {
    dependsOn(browShapeWorkerJar)
    val output = layout.buildDirectory.dir("plugin-brow-shape")
    val packageVersion = project.version.toString()
    inputs.file(browShapeWorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", packageVersion)
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        browShapeWorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.62726f772e7368617065.v2"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"$packageVersion","name":"Brow shapes",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/shape","aliases":["brow.shape"],"version":2,"name":"Brow shapes","category":"brow",
               "targets":["mesh"],"requires":["rig.scaffold","brow/fitted"],"contextVersion":2,
               "reads":["mesh","parameter","hierarchy"],"writes":["mesh","parameter","hierarchy"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val browBlinkWorkerJar = registerIsolatedBindingWorker(
    "browBlink", "brow-blink", "worker-brow-blink", "io.github.psd2live.algorithms.binding.BrowBlinkWorker",
)
kotlin.target.compilations.named("browBlink") {
    associateWith(kotlin.target.compilations.getByName("browShape"))
}
browBlinkWorkerJar.configure {
    from(sourceSets["browShape"].output) { include("**/BrowShapeInputs*.class") }
}

tasks.register("pluginBrowBlink") {
    dependsOn(browBlinkWorkerJar)
    val output = layout.buildDirectory.dir("plugin-brow-blink")
    val packageVersion = project.version.toString()
    inputs.file(browBlinkWorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", packageVersion)
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        browBlinkWorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.62726f772e626c696e6b.v2"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"$packageVersion","name":"Brow blink",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/blink","aliases":["brow.blink"],"version":2,"name":"Brow blink","category":"brow",
               "targets":["mesh"],"requires":["rig.scaffold","brow/fitted"],"contextVersion":2,
               "reads":["mesh","parameter","deformer"],"writes":["mesh","deformer"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

tasks.register("pluginEyeSquash") {
    dependsOn(eyeSquashWorkerJar)
    val output = layout.buildDirectory.dir("plugin-eye-squash")
    val packageVersion = project.version.toString()
    inputs.file(eyeSquashWorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", packageVersion)
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        eyeSquashWorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.6579652e737175617368.v2"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"$packageVersion","name":"Iris squash",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/squash","aliases":["eye.squash"],"version":2,"name":"Iris squash","category":"eye",
               "targets":["mesh"],"requires":["rig.scaffold","eye/fitted"],"contextVersion":2,
               "reads":["mesh","deformer","parameter"],"writes":["mesh"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val bodyLegacyV2WorkerJar = registerIsolatedBindingWorker(
    "bodyLegacyV2", "body-legacy-v2", "worker-body-legacy-v2", "io.github.psd2live.algorithms.binding.LegacyBodyV2Worker",
)

tasks.register("pluginBodyLegacyV2") {
    dependsOn(bodyLegacyV2WorkerJar)
    val output = layout.buildDirectory.dir("plugin-body-legacy-v2")
    val packageVersion = project.version.toString()
    inputs.file(bodyLegacyV2WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", packageVersion)
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        bodyLegacyV2WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.626f64792f6c6567616379.v2"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"$packageVersion","name":"Legacy body binding",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/motion","aliases":["body/legacy"],"version":2,"name":"Legacy body binding","category":"body",
               "targets":["mesh"],"requires":["rig.scaffold"],"contextVersion":2,"exclusiveFamily":"base:body",
               "reads":["*"],"writes":["mesh","deformer","hierarchy","relations","parameter"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val mouthOutlineV2 = sourceSets.create("mouthOutlineV2")
kotlin.target.compilations.named("mouthOutlineV2") {
    associateWith(kotlin.target.compilations.getByName("main"))
    associateWith(kotlin.target.compilations.getByName("mouthOutline"))
}
val mouthOutlineV2ClassesJar by tasks.registering(Jar::class) {
    archiveClassifier.set("mouth-outline-v2")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    from(mouthOutlineV2.output)
}
val mouthOutlineV2Elements = configurations.create("mouthOutlineV2Elements") {
    isCanBeConsumed = true
    isCanBeResolved = false
}
artifacts.add(mouthOutlineV2Elements.name, mouthOutlineV2ClassesJar)

val mouthOutlineV2WorkerJar = tasks.register<Jar>("mouthOutlineV2WorkerJar") {
    archiveClassifier.set("worker-mouth-outline-v2")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest { attributes["Main-Class"] = "io.github.psd2live.algorithms.binding.MouthOutlineV2Worker" }
    from(mouthOutlineV2.output)
    from(mouthOutlineWorkerJar.flatMap { it.archiveFile }.map { zipTree(it) }) { exclude("META-INF/MANIFEST.MF") }
}

tasks.register("pluginMouthOutlineV2") {
    dependsOn(mouthOutlineV2WorkerJar)
    val output = layout.buildDirectory.dir("plugin-mouth-outline-v2")
    inputs.file(mouthOutlineV2WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", project.version.toString())
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        mouthOutlineV2WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.6d6f7574682e6f75746c696e65.v2"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"${project.version}","name":"Mouth outline v2",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/outline","aliases":["mouth.outline"],"version":2,"contextVersion":2,"name":"Mouth outline v2","category":"mouth",
               "targets":["mesh"],"requires":["rig.scaffold","mouth/legacy"],"exclusiveFamily":"mouth.outline",
               "reads":["*"],"writes":["mesh","part","hierarchy","textures","authoring"],
               "composableKinds":["mesh","part","hierarchy"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val browFittedWorkerJar = registerIsolatedBindingWorker(
    "browFitted", "brow-fitted", "worker-brow-fitted-v2", "io.github.psd2live.algorithms.binding.BrowFittedWorker",
)

tasks.register("pluginBrowFittedV2") {
    dependsOn(browFittedWorkerJar)
    val output = layout.buildDirectory.dir("plugin-brow-fitted-v2")
    val packageVersion = project.version.toString()
    inputs.file(browFittedWorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", packageVersion)
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        browFittedWorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.62726f772f666974746564.v2"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"$packageVersion","name":"Fitted brows",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/binding","aliases":["brow/fitted"],"version":2,"name":"Fitted brows","category":"brow",
               "targets":["mesh"],"requires":["rig.scaffold"],"exclusiveFamily":"base:brow","contextVersion":2,
               "reads":["*"],"writes":["mesh","deformer","parameter","hierarchy"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val browFittedV3WorkerJar = registerIsolatedBindingWorker(
    "browFittedV3", "brow-fitted-v3", "worker-brow-fitted-v3", "io.github.psd2live.algorithms.binding.BrowFittedV3Worker",
)
kotlin.target.compilations.named("browFittedV3") {
    associateWith(kotlin.target.compilations.getByName("browFitted"))
}
browFittedV3WorkerJar.configure {
    from(sourceSets.named("browFitted").map { it.output })
}
tasks.register("pluginBrowFittedV3") {
    dependsOn(browFittedV3WorkerJar)
    val output = layout.buildDirectory.dir("plugin-brow-fitted-v3")
    val packageVersion = project.version.toString()
    inputs.file(browFittedV3WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", packageVersion)
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        browFittedV3WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.62726f772f666974746564.v3"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"$packageVersion","name":"Fitted brows",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/binding","aliases":["brow/fitted"],"version":3,"name":"Fitted brows","category":"brow",
               "targets":["mesh"],"requires":["rig.scaffold"],"exclusiveFamily":"base:brow","contextVersion":2,
               "reads":["*"],"writes":["mesh","deformer","parameter","hierarchy"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val eyeLegacyWorkerJar = registerIsolatedBindingWorker(
    "eyeLegacy", "eye-legacy", "worker-eye-legacy", "io.github.psd2live.algorithms.binding.EyeLegacyWorker",
)

tasks.register("pluginEyeLegacy") {
    dependsOn(eyeLegacyWorkerJar)
    val output = layout.buildDirectory.dir("plugin-eye-legacy")
    val packageVersion = project.version.toString()
    inputs.file(eyeLegacyWorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", packageVersion)
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        eyeLegacyWorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.6579652f6c6567616379.v2"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"$packageVersion","name":"Legacy eyes (source)",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/legacy","aliases":["eye/legacy"],"version":2,"name":"Legacy eyes (source)","category":"eye",
               "targets":["mesh"],"requires":["rig.scaffold"],"contextVersion":2,
               "exclusiveFamily":"base:eye","reads":["*"],"writes":["mesh","deformer","parameter","relations","hierarchy"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val faceLegacyV2WorkerJar = registerIsolatedBindingWorker(
    "faceLegacyV2", "face-legacy-v2", "worker-face-legacy-v2", "io.github.psd2live.algorithms.binding.LegacyFaceV2Worker",
)

tasks.register("pluginFaceLegacyV2") {
    dependsOn(faceLegacyV2WorkerJar)
    val output = layout.buildDirectory.dir("plugin-face-legacy-v2")
    val packageVersion = project.version.toString()
    inputs.file(faceLegacyV2WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", packageVersion)
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        faceLegacyV2WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.666163652f6c6567616379.v2"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"$packageVersion","name":"Legacy face binding",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/binding","aliases":["face/legacy"],"version":2,"name":"Legacy face binding","category":"face",
               "targets":["mesh"],"requires":["rig.scaffold"],"contextVersion":2,"exclusiveFamily":"base:face",
               "reads":["*"],"writes":["mesh","deformer","hierarchy","relations","parameter"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val browLegacyV2WorkerJar = registerIsolatedBindingWorker(
    "browLegacyV2", "brow-legacy-v2", "worker-brow-legacy-v2", "io.github.psd2live.algorithms.binding.BrowLegacyV2Worker",
)

tasks.register("pluginBrowLegacyV2") {
    dependsOn(browLegacyV2WorkerJar)
    val output = layout.buildDirectory.dir("plugin-brow-legacy-v2")
    val packageVersion = project.version.toString()
    inputs.file(browLegacyV2WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", packageVersion)
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        browLegacyV2WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.62726f772f6c6567616379.v2"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"$packageVersion","name":"Legacy brow binding",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/binding","aliases":["brow/legacy"],"version":2,"name":"Legacy brow binding","category":"brow",
               "targets":["mesh"],"requires":["rig.scaffold"],"contextVersion":2,"exclusiveFamily":"base:brow",
               "reads":["mesh","deformer","parameter","hierarchy"],"writes":["mesh","parameter","hierarchy"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val eyeWideWorkerJar = registerIsolatedBindingWorker(
    "eyeWide", "eye-wide", "worker-eye-wide", "io.github.psd2live.algorithms.binding.EyeWideWorker",
)

tasks.register("pluginEyeWide") {
    dependsOn(eyeWideWorkerJar)
    val output = layout.buildDirectory.dir("plugin-eye-wide")
    val packageVersion = project.version.toString()
    inputs.file(eyeWideWorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", packageVersion)
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        eyeWideWorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.6579652e77696465.v2"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"$packageVersion","name":"Eye widening",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/wide","aliases":["eye.wide"],"version":2,"name":"Eye widening","category":"eye",
               "targets":["mesh"],"requires":["rig.scaffold","eye/fitted"],"contextVersion":2,
               "reads":["mesh","deformer","parameter","canvas"],"writes":["mesh","parameter"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val eyeWideV3WorkerJar = registerIsolatedBindingWorker(
    "eyeWideV3", "eye-wide-v3", "worker-eye-wide-v3", "io.github.psd2live.algorithms.binding.EyeWideV3Worker",
)

kotlin.target.compilations.named("eyeWideV3") {
    associateWith(kotlin.target.compilations.getByName("eyeWide"))
}
eyeWideV3WorkerJar.configure { from(sourceSets["eyeWide"].output) }
tasks.register("pluginEyeWideV3") {
    dependsOn(eyeWideV3WorkerJar)
    val output = layout.buildDirectory.dir("plugin-eye-wide-v3")
    val packageVersion = project.version.toString()
    inputs.file(eyeWideV3WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", packageVersion)
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        eyeWideV3WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.6579652e77696465.v3"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"$packageVersion","name":"Eye widening",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/wide","aliases":["eye.wide"],"version":3,"name":"Eye widening","category":"eye",
               "targets":["mesh"],"requires":["rig.scaffold","eye/fitted"],"contextVersion":2,
               "reads":["mesh","deformer","parameter","canvas"],"writes":["mesh","deformer","parameter"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val eyeSmileWorkerJar = registerIsolatedBindingWorker(
    "eyeSmile", "eye-smile", "worker-eye-smile", "io.github.psd2live.algorithms.binding.EyeSmileWorker",
)

tasks.register("pluginEyeSmile") {
    dependsOn(eyeSmileWorkerJar)
    val output = layout.buildDirectory.dir("plugin-eye-smile")
    val packageVersion = project.version.toString()
    inputs.file(eyeSmileWorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", packageVersion)
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        eyeSmileWorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.6579652e736d696c65.v2"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"$packageVersion","name":"Eye smile",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/smile","aliases":["eye.smile"],"version":2,"name":"Eye smile","category":"eye",
               "targets":["mesh"],"requires":["rig.scaffold","eye/fitted"],"contextVersion":2,
               "reads":["mesh","parameter","hierarchy","canvas"],"writes":["mesh","parameter","hierarchy"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val eyeSmileV3WorkerJar = registerIsolatedBindingWorker(
    "eyeSmileV3", "eye-smile-v3", "worker-eye-smile-v3", "io.github.psd2live.algorithms.binding.EyeSmileV3Worker",
)

kotlin.target.compilations.named("eyeSmileV3") {
    associateWith(kotlin.target.compilations.getByName("eyeSmile"))
}
eyeSmileV3WorkerJar.configure { from(sourceSets["eyeSmile"].output) }
tasks.register("pluginEyeSmileV3") {
    dependsOn(eyeSmileV3WorkerJar)
    val output = layout.buildDirectory.dir("plugin-eye-smile-v3")
    val packageVersion = project.version.toString()
    inputs.file(eyeSmileV3WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", packageVersion)
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        eyeSmileV3WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.6579652e736d696c65.v3"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"$packageVersion","name":"Eye smile",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/smile","aliases":["eye.smile"],"version":3,"name":"Eye smile","category":"eye",
               "targets":["mesh"],"requires":["rig.scaffold","eye/fitted"],"contextVersion":2,
               "reads":["mesh","deformer","parameter","hierarchy","canvas"],"writes":["mesh","deformer","parameter","hierarchy"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val eyeFittedV4WorkerJar = registerIsolatedBindingWorker(
    "eyeFittedV4", "eye-fitted-v4", "worker-eye-fitted-v4", "io.github.psd2live.algorithms.binding.EyeFittedV4Worker",
)
kotlin.target.compilations.named("eyeFittedV4") {
    associateWith(kotlin.target.compilations.getByName("eyeFitted"))
}
eyeFittedV4WorkerJar.configure { from(eyeFitted.output) }

tasks.register("pluginEyeFittedV4") {
    dependsOn(eyeFittedV4WorkerJar)
    val output = layout.buildDirectory.dir("plugin-eye-fitted-v4")
    val packageVersion = project.version.toString()
    inputs.file(eyeFittedV4WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", packageVersion)
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        eyeFittedV4WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.6579652f666974746564.v4"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"$packageVersion","name":"Fitted eyes with head turn",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/fitted","aliases":["eye/fitted"],"version":4,"name":"Fitted eyes with head turn","category":"eye",
               "targets":["mesh"],"requires":["rig.scaffold"],"contextVersion":2,
               "exclusiveFamily":"base:eye","reads":["*"],"writes":["mesh","deformer","parameter","relations","hierarchy"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val eyeFittedV5WorkerJar = registerIsolatedBindingWorker(
    "eyeFittedV5", "eye-fitted-v5", "worker-eye-fitted-v5", "io.github.psd2live.algorithms.binding.EyeFittedV5Worker",
)
kotlin.target.compilations.named("eyeFittedV5") {
    associateWith(kotlin.target.compilations.getByName("eyeFitted"))
    associateWith(kotlin.target.compilations.getByName("eyeFittedV4"))
}
eyeFittedV5WorkerJar.configure {
    from(eyeFitted.output)
    from(sourceSets.getByName("eyeFittedV4").output)
}

tasks.register("pluginEyeFittedV5") {
    dependsOn(eyeFittedV5WorkerJar)
    val output = layout.buildDirectory.dir("plugin-eye-fitted-v5")
    val packageVersion = project.version.toString()
    inputs.file(eyeFittedV5WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", packageVersion)
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        eyeFittedV5WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.6579652f666974746564.v5"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"$packageVersion","name":"Fitted eyes with head turn",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/fitted","aliases":["eye/fitted"],"version":5,"name":"Fitted eyes with head turn","category":"eye",
               "targets":["mesh"],"requires":["rig.scaffold","body/legacy","face/fitted"],"contextVersion":2,
               "exclusiveFamily":"base:eye","reads":["*"],"writes":["mesh","deformer","parameter","relations","hierarchy"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val mouthSpringWorkerJar = registerIsolatedBindingWorker(
    "mouthSpring", "mouth-spring", "worker-mouth-spring", "io.github.psd2live.algorithms.binding.MouthSpringWorker",
)

tasks.register("pluginMouthSpring") {
    dependsOn(mouthSpringWorkerJar)
    val output = layout.buildDirectory.dir("plugin-mouth-spring")
    val packageVersion = project.version.toString()
    inputs.file(mouthSpringWorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", packageVersion)
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        mouthSpringWorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.6d6f7574682e737072696e67.v2"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"$packageVersion","name":"Mouth spring",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/spring","aliases":["mouth.spring"],"version":2,"name":"Mouth spring","category":"mouth",
               "targets":["mesh"],"requires":["rig.scaffold","mouth/fitted","mouth.jaw"],"contextVersion":2,
               "reads":["mesh","parameter","hierarchy","physics"],"writes":["mesh","parameter","hierarchy","physics"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val mouthSpringV3WorkerJar = registerIsolatedBindingWorker(
    "mouthSpringV3", "mouth-spring-v3", "worker-mouth-spring-v3", "io.github.psd2live.algorithms.binding.MouthSpringV3Worker",
)

tasks.register("pluginMouthSpringV3") {
    dependsOn(mouthSpringV3WorkerJar)
    val output = layout.buildDirectory.dir("plugin-mouth-spring-v3")
    val packageVersion = project.version.toString()
    inputs.file(mouthSpringV3WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", packageVersion)
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        mouthSpringV3WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.6d6f7574682e737072696e67.v3"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"$packageVersion","name":"Mouth spring",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/spring","aliases":["mouth.spring"],"version":3,"name":"Mouth spring","category":"mouth",
               "targets":["mesh"],"requires":["rig.scaffold","mouth/fitted","mouth.jaw"],"contextVersion":2,
               "reads":["mesh","deformer","parameter","hierarchy","physics"],"writes":["mesh","parameter","hierarchy","physics"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val mouthSpringV4WorkerJar = registerIsolatedBindingWorker(
    "mouthSpringV4", "mouth-spring-v4", "worker-mouth-spring-v4", "io.github.psd2live.algorithms.binding.MouthSpringV4Worker",
)

tasks.register("pluginMouthSpringV4") {
    dependsOn(mouthSpringV4WorkerJar)
    val output = layout.buildDirectory.dir("plugin-mouth-spring-v4")
    val packageVersion = project.version.toString()
    inputs.file(mouthSpringV4WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", packageVersion)
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        mouthSpringV4WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.6d6f7574682e737072696e67.v4"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"$packageVersion","name":"Mouth spring",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/spring","aliases":["mouth.spring"],"version":4,"name":"Mouth spring","category":"mouth",
               "targets":["mesh"],"requires":["rig.scaffold","mouth/fitted","mouth.jaw"],"contextVersion":2,
               "reads":["mesh","deformer","parameter","hierarchy","physics"],"writes":["mesh","parameter","hierarchy","physics"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val browSpringWorkerJar = registerIsolatedBindingWorker(
    "browSpring", "brow-spring", "worker-brow-spring", "io.github.psd2live.algorithms.binding.BrowSpringWorker",
)

tasks.register("pluginBrowSpring") {
    dependsOn(browSpringWorkerJar)
    val output = layout.buildDirectory.dir("plugin-brow-spring")
    val packageVersion = project.version.toString()
    inputs.file(browSpringWorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", packageVersion)
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        browSpringWorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.62726f772e737072696e67.v2"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"$packageVersion","name":"Brow spring",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/spring","aliases":["brow.spring"],"version":2,"name":"Brow spring","category":"brow",
               "targets":["mesh"],"requires":["rig.scaffold","brow/fitted"],"contextVersion":2,
               "reads":["mesh","deformer","parameter","hierarchy","physics"],"writes":["mesh","parameter","hierarchy","physics"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val browSpringV3WorkerJar = registerIsolatedBindingWorker(
    "browSpringV3", "brow-spring-v3", "worker-brow-spring-v3", "io.github.psd2live.algorithms.binding.BrowSpringV3Worker",
)

kotlin.target.compilations.named("browSpringV3") {
    associateWith(kotlin.target.compilations.getByName("browSpring"))
}
browSpringV3WorkerJar.configure {
    from(sourceSets["browSpring"].output) { exclude("**/BrowSpringWorker*.class") }
}

tasks.register("pluginBrowSpringV3") {
    dependsOn(browSpringV3WorkerJar)
    val output = layout.buildDirectory.dir("plugin-brow-spring-v3")
    val packageVersion = project.version.toString()
    inputs.file(browSpringV3WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", packageVersion)
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        browSpringV3WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.62726f772e737072696e67.v3"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"$packageVersion","name":"Brow spring",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/spring","aliases":["brow.spring"],"version":3,"name":"Brow spring","category":"brow",
               "targets":["mesh"],"requires":["rig.scaffold","brow/fitted"],"contextVersion":2,
               "reads":["canvas","mesh","deformer","parameter","hierarchy","physics"],"writes":["mesh","parameter","hierarchy","physics"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val eyeAftershockWorkerJar = registerIsolatedBindingWorker(
    "eyeAftershock", "eye-aftershock", "worker-eye-aftershock", "io.github.psd2live.algorithms.binding.EyeAftershockWorker",
)

kotlin.target.compilations.named("eyeAftershock") {
    associateWith(kotlin.target.compilations.getByName("eyeSmile"))
}
eyeAftershockWorkerJar.configure {
    from(sourceSets["eyeSmile"].output) { include("**/EyeSmileInputs*.class") }
}

tasks.register("pluginEyeAftershock") {
    dependsOn(eyeAftershockWorkerJar)
    val output = layout.buildDirectory.dir("plugin-eye-aftershock")
    val packageVersion = project.version.toString()
    inputs.file(eyeAftershockWorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", packageVersion)
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        eyeAftershockWorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.6579652e616674657273686f636b.v2"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"$packageVersion","name":"Blink aftershock",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/aftershock","aliases":["eye.aftershock"],"version":2,"name":"Blink aftershock","category":"eye",
               "targets":["mesh"],"requires":["rig.scaffold","eye/fitted"],"contextVersion":2,
               "reads":["mesh","parameter","hierarchy","canvas","deformer","physics"],"writes":["mesh","parameter","hierarchy","physics"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter","physics"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val skirtWorkerJar = registerIsolatedBindingWorker(
    "skirt", "outfit-skirt", "worker-outfit-skirt", "io.github.psd2live.algorithms.binding.SkirtWorker",
)

tasks.register("pluginSkirt") {
    dependsOn(skirtWorkerJar)
    val output = layout.buildDirectory.dir("plugin-outfit-skirt")
    inputs.file(skirtWorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", project.version.toString())
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        skirtWorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"builtin.6f75746669742e736b697274.v2","version":"${project.version}","name":"Skirt sway",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"builtin.6f75746669742e736b697274.v2/skirt","aliases":["outfit.skirt"],
               "version":2,"name":"Skirt sway","category":"outfit","contextVersion":2,"targets":["mesh"],
               "requires":["rig.scaffold","body/legacy","face/legacy"],"exclusiveFamily":"chain:outfit.skirt",
               "reads":["*"],"writes":["mesh","deformer","hierarchy","parameter","physics"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter","physics"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val armSwingWorkerJar = registerIsolatedBindingWorker(
    "armSwing", "body-arms", "worker-body-arms", "io.github.psd2live.algorithms.binding.ArmSwingWorker",
)

tasks.register("pluginArmSwing") {
    dependsOn(armSwingWorkerJar)
    val output = layout.buildDirectory.dir("plugin-arm-swing")
    inputs.file(armSwingWorkerJar.flatMap { it.archiveFile })
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        armSwingWorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.626f64792e61726d73.v2"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"1.0.0","name":"Arm swing",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/swing","aliases":["body.arms"],"version":2,"name":"Arm swing","category":"body",
               "targets":["mesh"],"requires":["rig.scaffold","body/legacy","face/legacy"],"contextVersion":2,
               "reads":["mesh","deformer","parameter","hierarchy","physics"],"writes":["deformer","parameter","hierarchy","physics"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter","physics"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val armSwingV3WorkerJar = registerIsolatedBindingWorker(
    "armSwingV3", "body-arms-v3", "worker-body-arms-v3", "io.github.psd2live.algorithms.binding.ArmSwingV3Worker",
)

kotlin.target.compilations.named("armSwingV3") {
    associateWith(kotlin.target.compilations.getByName("armSwing"))
}
armSwingV3WorkerJar.configure { from(sourceSets["armSwing"].output) }
tasks.register("pluginArmSwingV3") {
    dependsOn(armSwingV3WorkerJar)
    val output = layout.buildDirectory.dir("plugin-arm-swing-v3")
    inputs.file(armSwingV3WorkerJar.flatMap { it.archiveFile })
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        armSwingV3WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.626f64792e61726d73.v3"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"1.0.0","name":"Arm swing",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/swing","aliases":["body.arms"],"version":3,"name":"Arm swing","category":"body",
               "targets":["mesh"],"requires":["rig.scaffold","body/legacy","face/legacy"],"contextVersion":2,
               "reads":["mesh","deformer","parameter","hierarchy","physics"],"writes":["mesh","deformer","parameter","hierarchy","physics"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter","physics"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val browLidWorkerJar = registerIsolatedBindingWorker(
    "browLid", "brow-lid", "worker-brow-lid", "io.github.psd2live.algorithms.binding.BrowLidWorker",
)

kotlin.target.compilations.named("browLid") {
    associateWith(kotlin.target.compilations.getByName("eyeWide"))
}
browLidWorkerJar.configure { from(sourceSets["eyeWide"].output) { include("**/EyeWideInputs*.class") } }

tasks.register("pluginBrowLid") {
    dependsOn(browLidWorkerJar)
    val output = layout.buildDirectory.dir("plugin-brow-lid")
    val packageVersion = project.version.toString()
    inputs.file(browLidWorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", packageVersion)
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        browLidWorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.62726f772e6c69642d636f75706c696e67.v2"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"$packageVersion","name":"Brow and lid together",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/coupling","aliases":["brow.lid-coupling"],"version":2,"name":"Brow and lid together","category":"brow",
               "targets":["mesh"],"requires":["rig.scaffold","brow/fitted","eye/fitted"],"contextVersion":2,
               "reads":["mesh","parameter","deformer"],"writes":["mesh","deformer"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val browLidV3WorkerJar = registerIsolatedBindingWorker(
    "browLidV3", "brow-lid-v3", "worker-brow-lid-v3", "io.github.psd2live.algorithms.binding.BrowLidV3Worker",
)

kotlin.target.compilations.named("browLidV3") {
    associateWith(kotlin.target.compilations.getByName("eyeWide"))
}
browLidV3WorkerJar.configure {
    from(sourceSets["eyeWide"].output) { include("**/EyeWideInputs*.class") }
}

tasks.register("pluginBrowLidV3") {
    dependsOn(browLidV3WorkerJar)
    val output = layout.buildDirectory.dir("plugin-brow-lid-v3")
    val packageVersion = project.version.toString()
    inputs.file(browLidV3WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", packageVersion)
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        browLidV3WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.62726f772e6c69642d636f75706c696e67.v3"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"$packageVersion","name":"Brow and lid together",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/coupling","aliases":["brow.lid-coupling"],"version":3,"name":"Brow and lid together","category":"brow",
               "targets":["mesh"],"requires":["rig.scaffold","brow/fitted","eye/fitted"],"contextVersion":2,
               "reads":["mesh","parameter","canvas","deformer"],"writes":["mesh","deformer"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val eyeGazeLidWorkerJar = registerIsolatedBindingWorker(
    "eyeGazeLid", "eye-gaze-lid", "worker-eye-gaze-lid", "io.github.psd2live.algorithms.binding.EyeGazeLidWorker",
)

kotlin.target.compilations.named("eyeGazeLid") {
    associateWith(kotlin.target.compilations.getByName("eyeSmile"))
}
eyeGazeLidWorkerJar.configure {
    from(sourceSets["eyeSmile"].output) { include("**/EyeSmileInputs*.class") }
}

tasks.register("pluginEyeGazeLid") {
    dependsOn(eyeGazeLidWorkerJar)
    val output = layout.buildDirectory.dir("plugin-eye-gaze-lid")
    val packageVersion = project.version.toString()
    inputs.file(eyeGazeLidWorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", packageVersion)
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        eyeGazeLidWorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.6579652e67617a652d6c6964.v2"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"$packageVersion","name":"Lids follow gaze",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/gaze","aliases":["eye.gaze-lid"],"version":2,"name":"Lids follow gaze","category":"eye",
               "targets":["mesh"],"requires":["rig.scaffold","eye/fitted"],"contextVersion":2,
               "reads":["mesh","parameter","hierarchy","canvas","deformer","relations"],"writes":["mesh","deformer","parameter","hierarchy","relations"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter"],
               "parameters":[
                 {"id":"ParamEyeBallLX","name":"Left gaze X","min":-1,"max":1,"default":0},
                 {"id":"ParamEyeBallLY","name":"Left gaze Y","min":-1,"max":1,"default":0},
                 {"id":"ParamEyeBallRX","name":"Right gaze X","min":-1,"max":1,"default":0},
                 {"id":"ParamEyeBallRY","name":"Right gaze Y","min":-1,"max":1,"default":0}],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val mouthVowelWorkerJar = registerIsolatedBindingWorker(
    "mouthVowel", "mouth-vowel", "worker-mouth-vowel", "io.github.psd2live.algorithms.binding.MouthVowelWorker",
)

tasks.register("pluginMouthVowel") {
    dependsOn(mouthVowelWorkerJar)
    val output = layout.buildDirectory.dir("plugin-mouth-vowel")
    val packageVersion = project.version.toString()
    inputs.file(mouthVowelWorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", packageVersion)
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        mouthVowelWorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.6d6f7574682e766f77656c.v2"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"$packageVersion","name":"Mouth vowels",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/vowel","aliases":["mouth.vowel"],"version":2,"name":"Mouth vowels","category":"mouth",
               "targets":["mesh"],"requires":["rig.scaffold","mouth/fitted"],"contextVersion":2,
               "reads":["mesh","parameter","hierarchy"],"writes":["mesh","parameter","hierarchy"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val mouthVowelV3WorkerJar = registerIsolatedBindingWorker(
    "mouthVowelV3", "mouth-vowel-v3", "worker-mouth-vowel-v3", "io.github.psd2live.algorithms.binding.MouthVowelV3Worker",
)
kotlin.target.compilations.named("mouthVowelV3") {
    associateWith(kotlin.target.compilations.getByName("mouthVowel"))
}
mouthVowelV3WorkerJar.configure {
    from(sourceSets["mouthVowel"].output)
}

tasks.register("pluginMouthVowelV3") {
    dependsOn(mouthVowelV3WorkerJar)
    val output = layout.buildDirectory.dir("plugin-mouth-vowel-v3")
    val packageVersion = project.version.toString()
    inputs.file(mouthVowelV3WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", packageVersion)
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        mouthVowelV3WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.6d6f7574682e766f77656c.v3"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"$packageVersion","name":"Mouth vowels with fitted outline",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/vowel","aliases":["mouth.vowel"],"version":3,"name":"Mouth vowels with fitted outline","category":"mouth",
               "targets":["mesh"],"requires":["rig.scaffold","mouth/fitted"],"contextVersion":2,
               "reads":["mesh","parameter","hierarchy"],"writes":["mesh","parameter","hierarchy"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val mouthJawWorkerJar = registerIsolatedBindingWorker(
    "mouthJaw", "mouth-jaw", "worker-mouth-jaw", "io.github.psd2live.algorithms.binding.MouthJawWorker",
)

tasks.register("pluginMouthJaw") {
    dependsOn(mouthJawWorkerJar)
    val output = layout.buildDirectory.dir("plugin-mouth-jaw")
    val packageVersion = project.version.toString()
    inputs.file(mouthJawWorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", packageVersion)
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        mouthJawWorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.6d6f7574682e6a6177.v2"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"$packageVersion","name":"Mouth jaw",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/jaw","aliases":["mouth.jaw"],"version":2,"name":"Mouth jaw","category":"mouth",
               "targets":["mesh"],"requires":["rig.scaffold","mouth/fitted"],"contextVersion":2,
               "reads":["mesh","parameter"],"writes":["mesh"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val mouthJawV3WorkerJar = registerIsolatedBindingWorker(
    "mouthJawV3", "mouth-jaw-v3", "worker-mouth-jaw-v3", "io.github.psd2live.algorithms.binding.MouthJawV3Worker",
)

tasks.register("pluginMouthJawV3") {
    dependsOn(mouthJawV3WorkerJar)
    val output = layout.buildDirectory.dir("plugin-mouth-jaw-v3")
    val packageVersion = project.version.toString()
    inputs.file(mouthJawV3WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", packageVersion)
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        mouthJawV3WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.6d6f7574682e6a6177.v3"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"$packageVersion","name":"Mouth jaw for fitted source",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/jaw","aliases":["mouth.jaw"],"version":3,"name":"Mouth jaw for fitted source","category":"mouth",
               "targets":["mesh"],"requires":["rig.scaffold","mouth/fitted"],"contextVersion":2,
               "reads":["mesh","parameter"],"writes":["mesh"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val ribbonsWorkerJar = registerIsolatedBindingWorker(
    "ribbons", "outfit-ribbons", "worker-outfit-ribbons", "io.github.psd2live.algorithms.binding.RibbonsWorker",
)

tasks.register("pluginRibbons") {
    dependsOn(ribbonsWorkerJar)
    val output = layout.buildDirectory.dir("plugin-outfit-ribbons")
    inputs.file(ribbonsWorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", project.version.toString())
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        ribbonsWorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"builtin.6f75746669742e726962626f6e73.v2","version":"${project.version}","name":"Ribbon and pendant sway",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"builtin.6f75746669742e726962626f6e73.v2/ribbons","aliases":["outfit.ribbons"],
               "version":2,"name":"Ribbon and pendant sway","category":"outfit","contextVersion":2,"targets":["mesh"],
               "requires":["rig.scaffold","body/legacy","face/legacy"],"exclusiveFamily":"chain:outfit.ribbons",
               "reads":["*"],"writes":["mesh","part","deformer","hierarchy","parameter","physics"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter","physics"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val eyeEmotionWorkerJar = registerIsolatedBindingWorker(
    "eyeEmotion", "eye-emotion", "worker-eye-emotion", "io.github.psd2live.algorithms.binding.EyeEmotionWorker",
)

kotlin.target.compilations.named("eyeEmotion") {
    associateWith(kotlin.target.compilations.getByName("eyeSmile"))
}
eyeEmotionWorkerJar.configure {
    from(sourceSets["eyeSmile"].output) { include("**/EyeSmileInputs*.class") }
}

tasks.register("pluginEyeEmotion") {
    dependsOn(eyeEmotionWorkerJar)
    val output = layout.buildDirectory.dir("plugin-eye-emotion")
    val packageVersion = project.version.toString()
    inputs.file(eyeEmotionWorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", packageVersion)
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        eyeEmotionWorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.6579652e656d6f74696f6e.v2"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"$packageVersion","name":"Emotion eye shapes",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/emotion","aliases":["eye.emotion"],"version":2,"name":"Emotion eye shapes","category":"eye",
               "targets":["mesh"],"requires":["rig.scaffold","eye/fitted"],"contextVersion":2,
               "reads":["mesh","parameter","hierarchy","canvas","deformer"],"writes":["mesh","parameter","hierarchy"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val creatureWorkerJar = registerIsolatedBindingWorker(
    "creature", "outfit-creature", "worker-outfit-creature", "io.github.psd2live.algorithms.binding.CreatureWorker",
)

tasks.register("pluginCreature") {
    dependsOn(creatureWorkerJar)
    val output = layout.buildDirectory.dir("plugin-outfit-creature")
    inputs.file(creatureWorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", project.version.toString())
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        creatureWorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"builtin.6f75746669742e6372656174757265.v2","version":"${project.version}","name":"Animal ears, tails and wings",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"builtin.6f75746669742e6372656174757265.v2/creature","aliases":["outfit.creature"],
               "version":2,"name":"Animal ears, tails and wings","category":"outfit","contextVersion":2,"targets":["mesh"],
               "requires":["rig.scaffold","body/legacy","face/legacy"],"exclusiveFamily":"chain:outfit.creature",
               "reads":["*"],"writes":["mesh","part","deformer","hierarchy","parameter","physics"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter","physics"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val mouthCaptureWorkerJar = registerIsolatedBindingWorker(
    "mouthCapture", "mouth-capture", "worker-mouth-capture", "io.github.psd2live.algorithms.binding.MouthCaptureWorker",
)

tasks.register("pluginMouthCapture") {
    dependsOn(mouthCaptureWorkerJar)
    val output = layout.buildDirectory.dir("plugin-mouth-capture")
    val packageVersion = project.version.toString()
    inputs.file(mouthCaptureWorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", packageVersion)
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        mouthCaptureWorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.6d6f7574682e63617074757265.v2"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"$packageVersion","name":"Mouth capture",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/capture","aliases":["mouth.capture"],"version":2,"name":"Mouth capture","category":"mouth",
               "targets":["mesh"],"requires":["rig.scaffold","mouth/fitted"],"contextVersion":2,
               "reads":["mesh","parameter","hierarchy"],"writes":["mesh","parameter","hierarchy"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val eyeHighlightWorkerJar = registerIsolatedBindingWorker(
    "eyeHighlight", "eye-highlight", "worker-eye-highlight", "io.github.psd2live.algorithms.binding.EyeHighlightWorker",
)

tasks.register("pluginEyeHighlight") {
    dependsOn(eyeHighlightWorkerJar)
    val output = layout.buildDirectory.dir("plugin-eye-highlight")
    val packageVersion = project.version.toString()
    inputs.file(eyeHighlightWorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", packageVersion)
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        eyeHighlightWorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.6579652e686967686c69676874.v2"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"$packageVersion","name":"Highlight parallax",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/highlight","aliases":["eye.highlight"],"version":2,"name":"Highlight parallax","category":"eye",
               "targets":["mesh"],"requires":["rig.scaffold","eye/fitted","face/legacy"],"contextVersion":2,
               "reads":["mesh","parameter","hierarchy","canvas","deformer"],"writes":["mesh"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val mouthTongueWorkerJar = registerIsolatedBindingWorker(
    "mouthTongue", "mouth-tongue", "worker-mouth-tongue", "io.github.psd2live.algorithms.binding.MouthTongueWorker",
)

tasks.register("pluginMouthTongue") {
    dependsOn(mouthTongueWorkerJar)
    val output = layout.buildDirectory.dir("plugin-mouth-tongue")
    val packageVersion = project.version.toString()
    inputs.file(mouthTongueWorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", packageVersion)
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        mouthTongueWorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.6d6f7574682e746f6e677565.v2"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"$packageVersion","name":"Mouth tongue",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/tongue","aliases":["mouth.tongue"],"version":2,"name":"Mouth tongue","category":"mouth",
               "targets":["mesh"],"requires":["rig.scaffold","mouth/fitted","mouth.capture"],"contextVersion":2,
               "reads":["mesh","parameter","hierarchy","canvas"],"writes":["mesh","parameter","hierarchy"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val mouthCheekLidWorkerJar = registerIsolatedBindingWorker(
    "mouthCheekLid", "mouth-cheek-lid", "worker-mouth-cheek-lid", "io.github.psd2live.algorithms.binding.MouthCheekLidWorker",
)

kotlin.target.compilations.named("mouthCheekLid") {
    associateWith(kotlin.target.compilations.getByName("eyeSmile"))
}
mouthCheekLidWorkerJar.configure {
    from(sourceSets["eyeSmile"].output) { include("**/EyeSmileInputs*.class") }
}

tasks.register("pluginMouthCheekLid") {
    dependsOn(mouthCheekLidWorkerJar)
    val output = layout.buildDirectory.dir("plugin-mouth-cheek-lid")
    val packageVersion = project.version.toString()
    inputs.file(mouthCheekLidWorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", packageVersion)
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        mouthCheekLidWorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.6d6f7574682e636865656b2d6c6964.v2"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"$packageVersion","name":"Cheeks lift lids",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/coupling","aliases":["mouth.cheek-lid"],"version":2,"name":"Cheeks lift lids","category":"mouth",
               "targets":["mesh"],"requires":["rig.scaffold","mouth/fitted","eye/fitted"],"contextVersion":2,
               "reads":["mesh","parameter","canvas","deformer"],"writes":["mesh","deformer"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val mouthCheekLidV3WorkerJar = registerIsolatedBindingWorker(
    "mouthCheekLidV3", "mouth-cheek-lid-v3", "worker-mouth-cheek-lid-v3", "io.github.psd2live.algorithms.binding.MouthCheekLidV3Worker",
)

kotlin.target.compilations.named("mouthCheekLidV3") {
    associateWith(kotlin.target.compilations.getByName("eyeSmile"))
}
mouthCheekLidV3WorkerJar.configure {
    from(sourceSets["eyeSmile"].output) { include("**/EyeSmileInputs*.class") }
}

tasks.register("pluginMouthCheekLidV3") {
    dependsOn(mouthCheekLidV3WorkerJar)
    val output = layout.buildDirectory.dir("plugin-mouth-cheek-lid-v3")
    val packageVersion = project.version.toString()
    inputs.file(mouthCheekLidV3WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", packageVersion)
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        mouthCheekLidV3WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.6d6f7574682e636865656b2d6c6964.v3"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"$packageVersion","name":"Cheeks lift lids",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/coupling","aliases":["mouth.cheek-lid"],"version":3,"name":"Cheeks lift lids","category":"mouth",
               "targets":["mesh"],"requires":["rig.scaffold","mouth/fitted","eye/fitted"],"contextVersion":2,
               "reads":["mesh","parameter","canvas","deformer"],"writes":["mesh","deformer"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val mouthDepthWorkerJar = registerIsolatedBindingWorker(
    "mouthDepth", "mouth-depth", "worker-mouth-depth", "io.github.psd2live.algorithms.binding.MouthDepthWorker",
)

tasks.register("pluginMouthDepth") {
    dependsOn(mouthDepthWorkerJar)
    val output = layout.buildDirectory.dir("plugin-mouth-depth")
    val packageVersion = project.version.toString()
    inputs.file(mouthDepthWorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", packageVersion)
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        mouthDepthWorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.6d6f7574682e696e746572696f722d6465707468.v2"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"$packageVersion","name":"Mouth interior depth",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/depth","aliases":["mouth.interior-depth"],"version":2,"name":"Mouth interior depth","category":"mouth",
               "targets":["mesh"],"requires":["rig.scaffold","mouth/fitted","face/legacy"],"contextVersion":2,
               "reads":["mesh","parameter","hierarchy","canvas","deformer"],"writes":["mesh","deformer"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val mouthDepthV3WorkerJar = registerIsolatedBindingWorker(
    "mouthDepthV3", "mouth-depth-v3", "worker-mouth-depth-v3", "io.github.psd2live.algorithms.binding.MouthDepthV3Worker",
)

tasks.register("pluginMouthDepthV3") {
    dependsOn(mouthDepthV3WorkerJar)
    val output = layout.buildDirectory.dir("plugin-mouth-depth-v3")
    val packageVersion = project.version.toString()
    inputs.file(mouthDepthV3WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", packageVersion)
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        mouthDepthV3WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.6d6f7574682e696e746572696f722d6465707468.v3"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"$packageVersion","name":"Mouth interior depth",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/depth","aliases":["mouth.interior-depth"],"version":3,"name":"Mouth interior depth","category":"mouth",
               "targets":["mesh"],"requires":["rig.scaffold","mouth/fitted","face/legacy"],"contextVersion":2,
               "reads":["mesh","parameter","hierarchy","canvas","deformer"],"writes":["mesh","deformer"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val cheekWorkerJar = registerIsolatedBindingWorker(
    "cheek", "cheek", "worker-cheek", "io.github.psd2live.algorithms.binding.CheekWorker",
)

tasks.register("pluginCheekV2") {
    dependsOn(cheekWorkerJar)
    val output = layout.buildDirectory.dir("plugin-cheek-v2")
    val packageVersion = project.version.toString()
    inputs.file(cheekWorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", packageVersion)
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        cheekWorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.65787072657373696f6e2e636865656b.v2"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"$packageVersion","name":"Cheek",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/color","aliases":["expression.cheek"],"version":2,"name":"Cheek","category":"expression",
               "targets":["mesh"],"requires":["rig.scaffold"],"contextVersion":2,
               "reads":["mesh","parameter","hierarchy"],"writes":["mesh","parameter","hierarchy"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val gloomV2WorkerJar = registerIsolatedBindingWorker(
    "gloomV2", "gloom-v2", "worker-gloom-v2", "io.github.psd2live.algorithms.binding.GloomWorker",
)

tasks.register("pluginGloomV2") {
    dependsOn(gloomV2WorkerJar)
    val output = layout.buildDirectory.dir("plugin-gloom-v2")
    val packageVersion = project.version.toString()
    inputs.file(gloomV2WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", packageVersion)
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        gloomV2WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.65787072657373696f6e2e676c6f6f6d.v2"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"$packageVersion","name":"Gloom",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/gloom","aliases":["expression.gloom"],"version":2,"name":"Gloom","category":"expression",
               "targets":["mesh"],"requires":["rig.scaffold"],"exclusiveFamily":"expression.gloom","contextVersion":2,
               "reads":["mesh","parameter","hierarchy"],"writes":["mesh","parameter","hierarchy"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val gloomV3WorkerJar = registerIsolatedBindingWorker(
    "gloomV3", "gloom-v3", "worker-gloom-v3", "io.github.psd2live.algorithms.binding.GloomV3Worker",
)

tasks.register("pluginGloomV3") {
    dependsOn(gloomV3WorkerJar)
    val output = layout.buildDirectory.dir("plugin-gloom-v3")
    val packageVersion = project.version.toString()
    inputs.file(gloomV3WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", packageVersion)
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        gloomV3WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.65787072657373696f6e2e676c6f6f6d.v3"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"$packageVersion","name":"Gloom",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/gloom","aliases":["expression.gloom"],"version":3,"name":"Gloom","category":"expression",
               "targets":["mesh"],"requires":["rig.scaffold"],"exclusiveFamily":"expression.gloom","contextVersion":2,
               "reads":["canvas","mesh","parameter","deformer","hierarchy"],"writes":["mesh","parameter","deformer","hierarchy"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter"],
               "parameters":[{"id":"ParamGloom","name":"Gloom","min":0,"max":1,"default":0}],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val mouthOutlineV3WorkerJar = registerIsolatedBindingWorker(
    "mouthOutlineV3", "mouth-outline-v3", "worker-mouth-outline-v3", "io.github.psd2live.algorithms.binding.MouthOutlineV3Worker",
)

tasks.register("pluginMouthOutlineV3") {
    dependsOn(mouthOutlineV3WorkerJar)
    val output = layout.buildDirectory.dir("plugin-mouth-outline-v3")
    val packageVersion = project.version.toString()
    inputs.file(mouthOutlineV3WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", packageVersion)
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        mouthOutlineV3WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.6d6f7574682e6f75746c696e65.v3"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"$packageVersion","name":"Fitted mouth outline",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/outline","aliases":["mouth.outline"],"version":3,"contextVersion":2,"name":"Fitted mouth outline","category":"mouth",
               "targets":["mesh"],"requires":["rig.scaffold","mouth/fitted"],"exclusiveFamily":"mouth.outline",
               "reads":["*"],"writes":["mesh","deformer","part","hierarchy","textures","authoring"],
               "composableKinds":["mesh","deformer","part","hierarchy"],"configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val mouthFittedV3WorkerJar = registerIsolatedBindingWorker(
    "mouthFittedV3", "mouth-fitted-v3", "worker-mouth-fitted-v3", "io.github.psd2live.algorithms.binding.MouthFittedV3Worker",
)

tasks.register("pluginMouthFittedV3") {
    dependsOn(mouthFittedV3WorkerJar)
    val output = layout.buildDirectory.dir("plugin-mouth-fitted-v3")
    inputs.file(mouthFittedV3WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", project.version.toString())
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        mouthFittedV3WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.6d6f7574682f666974746564.v3"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"${project.version}","name":"Fitted mouth v3",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/binding","aliases":["mouth/fitted"],"version":3,"contextVersion":2,"name":"Fitted mouth v3","category":"mouth",
               "targets":["mesh"],"requires":["rig.scaffold","face/fitted"],"exclusiveFamily":"base:mouth",
               "reads":["*"],"writes":["mesh","parameter","deformer","hierarchy","relations","rest_pose"],"composableKinds":["mesh","parameter","deformer","hierarchy","relations"],
               "parameters":[{"id":"ParamMouthForm","name":"Mouth Form","min":-1,"max":1,"default":0},{"id":"ParamMouthOpenY","name":"Mouth Open","min":0,"max":1,"default":0}],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val mouthVowelV4WorkerJar = registerIsolatedBindingWorker(
    "mouthVowelV4", "mouth-vowel-v4", "worker-mouth-vowel-v4", "io.github.psd2live.algorithms.binding.MouthVowelV4Worker",
)
kotlin.target.compilations.named("mouthVowelV4") {
    associateWith(kotlin.target.compilations.getByName("mouthVowel"))
}
mouthVowelV4WorkerJar.configure {
    from(sourceSets["mouthVowel"].output)
}

tasks.register("pluginMouthVowelV4") {
    dependsOn(mouthVowelV4WorkerJar)
    val output = layout.buildDirectory.dir("plugin-mouth-vowel-v4")
    val packageVersion = project.version.toString()
    inputs.file(mouthVowelV4WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", packageVersion)
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        mouthVowelV4WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.6d6f7574682e766f77656c.v4"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"$packageVersion","name":"Mouth vowels with fitted source",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/vowel","aliases":["mouth.vowel"],"version":4,"name":"Mouth vowels with fitted source","category":"mouth",
               "targets":["mesh"],"requires":["rig.scaffold","mouth/fitted"],"contextVersion":2,
               "reads":["canvas","mesh","parameter","hierarchy"],"writes":["mesh","parameter","hierarchy"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val mouthCaptureV3WorkerJar = registerIsolatedBindingWorker(
    "mouthCaptureV3", "mouth-capture-v3", "worker-mouth-capture-v3", "io.github.psd2live.algorithms.binding.MouthCaptureV3Worker",
)

kotlin.target.compilations.named("mouthCaptureV3") {
    associateWith(kotlin.target.compilations.getByName("mouthCapture"))
}
mouthCaptureV3WorkerJar.configure {
    from(sourceSets["mouthCapture"].output)
}

tasks.register("pluginMouthCaptureV3") {
    dependsOn(mouthCaptureV3WorkerJar)
    val output = layout.buildDirectory.dir("plugin-mouth-capture-v3")
    val packageVersion = project.version.toString()
    inputs.file(mouthCaptureV3WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", packageVersion)
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        mouthCaptureV3WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.6d6f7574682e63617074757265.v3"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"$packageVersion","name":"Mouth capture",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/capture","aliases":["mouth.capture"],"version":3,"name":"Mouth capture","category":"mouth",
               "targets":["mesh"],"requires":["rig.scaffold","mouth/fitted"],"contextVersion":2,
               "reads":["canvas","mesh","parameter","hierarchy"],"writes":["mesh","parameter","hierarchy"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val mouthCheekLidV4WorkerJar = registerIsolatedBindingWorker(
    "mouthCheekLidV4", "mouth-cheek-lid-v4", "worker-mouth-cheek-lid-v4", "io.github.psd2live.algorithms.binding.MouthCheekLidV4Worker",
)

kotlin.target.compilations.named("mouthCheekLidV4") {
    associateWith(kotlin.target.compilations.getByName("eyeSmile"))
}
mouthCheekLidV4WorkerJar.configure {
    from(sourceSets["eyeSmile"].output) { include("**/EyeSmileInputs*.class") }
}

tasks.register("pluginMouthCheekLidV4") {
    dependsOn(mouthCheekLidV4WorkerJar)
    val output = layout.buildDirectory.dir("plugin-mouth-cheek-lid-v4")
    val packageVersion = project.version.toString()
    inputs.file(mouthCheekLidV4WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", packageVersion)
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        mouthCheekLidV4WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.6d6f7574682e636865656b2d6c6964.v4"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"$packageVersion","name":"Cheeks lift lids",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/coupling","aliases":["mouth.cheek-lid"],"version":4,"name":"Cheeks lift lids","category":"mouth",
               "targets":["mesh"],"requires":["rig.scaffold","mouth/fitted","eye/fitted"],"contextVersion":2,
               "reads":["mesh","parameter","canvas","deformer"],"writes":["mesh","deformer"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val mouthTongueV3WorkerJar = registerIsolatedBindingWorker(
    "mouthTongueV3", "mouth-tongue-v3", "worker-mouth-tongue-v3", "io.github.psd2live.algorithms.binding.MouthTongueV3Worker",
)

kotlin.target.compilations.named("mouthTongueV3") {
    associateWith(kotlin.target.compilations.getByName("mouthTongue"))
}
mouthTongueV3WorkerJar.configure {
    from(sourceSets["mouthTongue"].output)
}

tasks.register("pluginMouthTongueV3") {
    dependsOn(mouthTongueV3WorkerJar)
    val output = layout.buildDirectory.dir("plugin-mouth-tongue-v3")
    val packageVersion = project.version.toString()
    inputs.file(mouthTongueV3WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", packageVersion)
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        mouthTongueV3WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.6d6f7574682e746f6e677565.v3"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"$packageVersion","name":"Mouth tongue",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/tongue","aliases":["mouth.tongue"],"version":3,"name":"Mouth tongue","category":"mouth",
               "targets":["mesh"],"requires":["rig.scaffold","mouth/fitted","mouth.capture"],"contextVersion":2,
               "reads":["mesh","parameter","hierarchy","canvas"],"writes":["mesh","parameter","hierarchy"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}
