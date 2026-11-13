import java.util.jar.Attributes
import java.util.jar.JarFile
import java.util.jar.Manifest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import java.io.ByteArrayOutputStream
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.HexFormat
import java.security.MessageDigest

// Binding kernels use the GPL engine; they are separate from the MIT API/format modules.
plugins { `java-library`; kotlin("jvm"); kotlin("plugin.serialization") }
group = "io.github.psd2live.algorithms"
version = "1.0.64"
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

val bustV3WorkerJar = registerIsolatedBindingWorker(
    "bustV3", "body-bust-v3", "worker-body-bust-v3", "io.github.psd2live.algorithms.binding.BustBindingV3Worker",
)
bustV3WorkerJar.configure {
    from(sourceSets.main.get().output) { include(bustWorkerClasses) }
}
tasks.register("pluginBustV3") {
    dependsOn(bustV3WorkerJar)
    val output = layout.buildDirectory.dir("plugin-bust-v3")
    val packageVersion = project.version.toString()
    inputs.file(bustV3WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", packageVersion)
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        bustV3WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.626f64792e62757374.v3"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"$packageVersion","name":"Chest bounce",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/bust","aliases":["body.bust"],"version":3,"name":"Chest bounce","category":"body",
               "targets":["mesh"],"requires":["rig.scaffold","body/legacy","face/legacy"],"contextVersion":2,
               "reads":["canvas","mesh","deformer","parameter","hierarchy","physics","textures","authoring"],"writes":["mesh","parameter","hierarchy","physics"],
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

val lidFadeV3WorkerJar = registerIsolatedBindingWorker(
    "lidFadeV3", "eye-lid-fade-v3", "worker-eye-lid-fade-v3", "io.github.psd2live.algorithms.binding.LidFadeV3Worker",
)
lidFadeV3WorkerJar.configure { from(sourceSets.main.get().output) { include(lidFadeWorkerClasses) } }

tasks.register("pluginEyeLidFadeV3") {
    dependsOn(lidFadeV3WorkerJar)
    val output = layout.buildDirectory.dir("plugin-eye-lid-fade-v3")
    val packageVersion = project.version.toString()
    inputs.file(lidFadeV3WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", packageVersion)
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        lidFadeV3WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.6579652e6c69642d66616465.v3"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"$packageVersion","name":"Lid lines fade early",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/fade","aliases":["eye.lid-fade"],"version":3,"name":"Lid lines fade early","category":"eye",
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

val expressionV2WorkerJar = registerIsolatedBindingWorker(
    "expressionV2", "expression-v2", "worker-expression-v2", "io.github.psd2live.algorithms.binding.ExpressionV2Worker",
)

tasks.register("pluginExpressionV2") {
    dependsOn(expressionV2WorkerJar)
    val output = layout.buildDirectory.dir("plugin-expression-v2")
    val packageVersion = project.version.toString()
    inputs.file(expressionV2WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", packageVersion)
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        expressionV2WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.65787072657373696f6e2e746f67676c6573.v2"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"$packageVersion","name":"Expressions with upstream parameters",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/toggles","aliases":["expression.toggles"],"version":2,"name":"Expressions with upstream parameters","category":"expression",
               "targets":["mesh"],"requires":["rig.scaffold"],"exclusiveFamily":"expression.toggles",
               "reads":["*"],"writes":["mesh","parameter","hierarchy","physics"],"composableKinds":["mesh","part","deformer","hierarchy","relations","parameter","physics"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val eyeSquashWorkerJar = registerIsolatedBindingWorker(
    "eyeSquash", "eye-squash", "worker-eye-squash", "io.github.psd2live.algorithms.binding.EyeSquashWorker",
)

val eyeSquashV3WorkerJar = registerIsolatedBindingWorker(
    "eyeSquashV3", "eye-squash-v3", "worker-eye-squash-v3", "io.github.psd2live.algorithms.binding.EyeSquashV3Worker",
)
kotlin.target.compilations.named("eyeSquashV3") {
    associateWith(kotlin.target.compilations.getByName("eyeSquash"))
}
eyeSquashV3WorkerJar.configure { from(sourceSets["eyeSquash"].output) }

val eyeSquashV4WorkerJar = registerIsolatedBindingWorker(
    "eyeSquashV4", "eye-squash-v4", "worker-eye-squash-v4", "io.github.psd2live.algorithms.binding.EyeSquashV4Worker",
)
kotlin.target.compilations.named("eyeSquashV4") {
    associateWith(kotlin.target.compilations.getByName("eyeSquash"))
}
eyeSquashV4WorkerJar.configure { from(sourceSets["eyeSquash"].output) }

val eyeSquashV5WorkerJar = registerIsolatedBindingWorker(
    "eyeSquashV5", "eye-squash-v5", "worker-eye-squash-v5", "io.github.psd2live.algorithms.binding.EyeSquashV5Worker",
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

val browShapeV3WorkerJar = registerIsolatedBindingWorker(
    "browShapeV3", "brow-shape-v3", "worker-brow-shape-v3", "io.github.psd2live.algorithms.binding.BrowShapeV3Worker",
)

kotlin.target.compilations.named("browShapeV3") {
    associateWith(kotlin.target.compilations.getByName("browShape"))
}
browShapeV3WorkerJar.configure {
    from(sourceSets["browShape"].output) { include("**/BrowShapeInputs*.class") }
}

tasks.register("pluginBrowShapeV3") {
    dependsOn(browShapeV3WorkerJar)
    val output = layout.buildDirectory.dir("plugin-brow-shape-v3")
    val packageVersion = project.version.toString()
    inputs.file(browShapeV3WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", packageVersion)
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        browShapeV3WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.62726f772e7368617065.v3"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"$packageVersion","name":"Brow shapes",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/shape","aliases":["brow.shape"],"version":3,"name":"Brow shapes","category":"brow",
               "targets":["mesh"],"requires":["rig.scaffold","brow/fitted"],"contextVersion":2,
               "reads":["mesh","parameter","hierarchy"],"writes":["mesh","parameter","hierarchy"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val browShapeV6WorkerJar = registerIsolatedBindingWorker(
    "browShapeV6", "brow-shape-v6", "worker-brow-shape-v6", "io.github.psd2live.algorithms.binding.BrowShapeV6Worker",
)

kotlin.target.compilations.named("browShapeV6") {
    associateWith(kotlin.target.compilations.getByName("browShape"))
}
browShapeV6WorkerJar.configure {
    from(sourceSets["browShape"].output) { include("**/BrowShapeInputs*.class") }
}

tasks.register("pluginBrowShapeV6") {
    dependsOn(browShapeV6WorkerJar)
    val output = layout.buildDirectory.dir("plugin-brow-shape-v6")
    val packageVersion = project.version.toString()
    inputs.file(browShapeV6WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", packageVersion)
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        browShapeV6WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.62726f772e7368617065.v6"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"$packageVersion","name":"Brow shapes",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/shape","aliases":["brow.shape"],"version":6,"name":"Brow shapes","category":"brow",
               "targets":["mesh"],"requires":["rig.scaffold","face/fitted","eye/fitted","brow/fitted"],"contextVersion":2,
               "reads":["mesh","deformer","part","parameter","hierarchy","canvas","textures","authoring"],"writes":["mesh","deformer","parameter","hierarchy"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val browShapeV5WorkerJar = registerIsolatedBindingWorker(
    "browShapeV5", "brow-shape-v5", "worker-brow-shape-v5", "io.github.psd2live.algorithms.binding.BrowShapeV5Worker",
)

tasks.register("pluginBrowShapeV5") {
    dependsOn(browShapeV5WorkerJar)
    val output = layout.buildDirectory.dir("plugin-brow-shape-v5")
    val packageVersion = project.version.toString()
    inputs.file(browShapeV5WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", packageVersion)
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        browShapeV5WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.62726f772e7368617065.v5"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"$packageVersion","name":"Brow shapes",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/shape","aliases":["brow.shape"],"version":5,"name":"Brow shapes","category":"brow",
               "targets":["mesh"],"requires":["rig.scaffold","brow/fitted"],"contextVersion":2,
               "reads":["*"],"writes":["mesh","deformer","part","parameter","hierarchy"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val browShapeV4WorkerJar = registerIsolatedBindingWorker(
    "browShapeV4", "brow-shape-v4", "worker-brow-shape-v4", "io.github.psd2live.algorithms.binding.BrowShapeV4Worker",
)

kotlin.target.compilations.named("browShapeV4") {
    associateWith(kotlin.target.compilations.getByName("browShapeV3"))
}
browShapeV4WorkerJar.configure {
    from(sourceSets["browShapeV3"].output)
    from(sourceSets["browShape"].output) { include("**/BrowShapeInputs*.class") }
}

tasks.register("pluginBrowShapeV4") {
    dependsOn(browShapeV4WorkerJar)
    val output = layout.buildDirectory.dir("plugin-brow-shape-v4")
    val packageVersion = project.version.toString()
    inputs.file(browShapeV4WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", packageVersion)
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        browShapeV4WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.62726f772e7368617065.v4"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"$packageVersion","name":"Brow shapes",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/shape","aliases":["brow.shape"],"version":4,"name":"Brow shapes","category":"brow",
               "targets":["mesh"],"requires":["rig.scaffold","brow/fitted"],"contextVersion":2,
               "reads":["canvas","mesh","deformer","part","parameter","hierarchy","textures","authoring"],"writes":["mesh","deformer","parameter","hierarchy"],
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

val browBlinkV3WorkerJar = registerIsolatedBindingWorker(
    "browBlinkV3", "brow-blink-v3", "worker-brow-blink-v3", "io.github.psd2live.algorithms.binding.BrowBlinkV3Worker",
)
kotlin.target.compilations.named("browBlinkV3") {
    associateWith(kotlin.target.compilations.getByName("browShape"))
}
browBlinkV3WorkerJar.configure {
    from(sourceSets["browShape"].output) { include("**/BrowShapeInputs*.class") }
}

tasks.register("pluginBrowBlinkV3") {
    dependsOn(browBlinkV3WorkerJar)
    val output = layout.buildDirectory.dir("plugin-brow-blink-v3")
    val packageVersion = project.version.toString()
    inputs.file(browBlinkV3WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", packageVersion)
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        browBlinkV3WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.62726f772e626c696e6b.v3"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"$packageVersion","name":"Brow blink",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/blink","aliases":["brow.blink"],"version":3,"name":"Brow blink","category":"brow",
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

val browBlinkV4WorkerJar = registerIsolatedBindingWorker(
    "browBlinkV4", "brow-blink-v4", "worker-brow-blink-v4", "io.github.psd2live.algorithms.binding.BrowBlinkV4Worker",
)
tasks.register("pluginBrowBlinkV4") {
    dependsOn(browBlinkV4WorkerJar)
    val output = layout.buildDirectory.dir("plugin-brow-blink-v4")
    val packageVersion = project.version.toString()
    inputs.file(browBlinkV4WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", packageVersion)
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        browBlinkV4WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.62726f772e626c696e6b.v4"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"$packageVersion","name":"Brow blink",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/blink","aliases":["brow.blink"],"version":4,"name":"Brow blink","category":"brow",
               "targets":["mesh"],"requires":["rig.scaffold","brow/fitted"],"contextVersion":2,
               "reads":["mesh","deformer","part","parameter","canvas","textures","authoring"],"writes":["mesh","deformer"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val browBlinkV5WorkerJar = registerIsolatedBindingWorker(
    "browBlinkV5", "brow-blink-v5", "worker-brow-blink-v5", "io.github.psd2live.algorithms.binding.BrowBlinkV5Worker",
)
tasks.register("pluginBrowBlinkV5") {
    dependsOn(browBlinkV5WorkerJar)
    val output = layout.buildDirectory.dir("plugin-brow-blink-v5")
    val packageVersion = project.version.toString()
    inputs.file(browBlinkV5WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", packageVersion)
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        browBlinkV5WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.62726f772e626c696e6b.v5"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"$packageVersion","name":"Brow blink",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/blink","aliases":["brow.blink"],"version":5,"name":"Brow blink","category":"brow",
               "targets":["mesh"],"requires":["rig.scaffold","brow/fitted"],"contextVersion":2,
               "reads":["mesh","deformer","part","parameter","canvas","textures","authoring"],"writes":["mesh","deformer"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val browBlinkV6WorkerJar = registerIsolatedBindingWorker(
    "browBlinkV6", "brow-blink-v6", "worker-brow-blink-v6", "io.github.psd2live.algorithms.binding.BrowBlinkV6Worker",
)
kotlin.target.compilations.named("browBlinkV6") {
    associateWith(kotlin.target.compilations.getByName("browShape"))
    associateWith(kotlin.target.compilations.getByName("browShapeV6"))
}
browBlinkV6WorkerJar.configure {
    from(sourceSets["browShape"].output) { include("**/BrowShapeInputs*.class") }
    from(sourceSets["browShapeV6"].output) { include("**/BrowShapeCarrierScope*.class") }
}
tasks.register("pluginBrowBlinkV6") {
    dependsOn(browBlinkV6WorkerJar)
    val output = layout.buildDirectory.dir("plugin-brow-blink-v6")
    val packageVersion = project.version.toString()
    inputs.file(browBlinkV6WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", packageVersion)
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        browBlinkV6WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.62726f772e626c696e6b.v6"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"$packageVersion","name":"Brow blink",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/blink","aliases":["brow.blink"],"version":6,"name":"Brow blink","category":"brow",
               "targets":["mesh"],"requires":["rig.scaffold","face/fitted","eye/fitted","brow/fitted","brow.shape","brow.lid-coupling"],"contextVersion":2,
               "reads":["mesh","deformer","part","parameter","canvas","textures","authoring"],"writes":["mesh","deformer"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val neckV3WorkerJar = registerIsolatedBindingWorker(
    "neckV3", "face-neck-v3", "worker-face-neck-v3", "io.github.psd2live.algorithms.binding.NeckV3Worker",
)
tasks.register("pluginFaceNeckV3") {
    dependsOn(neckV3WorkerJar)
    val output = layout.buildDirectory.dir("plugin-face-neck-v3")
    val packageVersion = project.version.toString()
    inputs.file(neckV3WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", packageVersion)
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        neckV3WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.666163652e6e65636b.v3"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"$packageVersion","name":"Neck tilt",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/tilt","aliases":["face.neck"],"version":3,"name":"Neck tilt","category":"face",
               "targets":["mesh"],"requires":["rig.scaffold","face/legacy"],"contextVersion":2,
               "reads":["mesh","deformer","part","parameter","canvas","textures","authoring"],"writes":["mesh","relations"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val neckV4WorkerJar = registerIsolatedBindingWorker(
    "neckV4", "face-neck-v4", "worker-face-neck-v4", "io.github.psd2live.algorithms.binding.NeckV4Worker",
)
neckV4WorkerJar.configure {
    // The historical shared worker deliberately excludes these independently packaged kernels.
    from(sourceSets.main.get().output) { include("**/NeckAlgorithm*.class", "**/NeckInputs*.class") }
}
tasks.register("pluginFaceNeckV4") {
    dependsOn(neckV4WorkerJar)
    val output = layout.buildDirectory.dir("plugin-face-neck-v4")
    val packageVersion = project.version.toString()
    inputs.file(neckV4WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", packageVersion)
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        neckV4WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.666163652e6e65636b.v4"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"$packageVersion","name":"Source neck tilt",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/tilt","aliases":["face.neck"],"version":4,"name":"Source neck tilt","category":"face",
               "targets":["mesh"],"requires":["rig.scaffold","face/legacy"],"contextVersion":2,
               "reads":["mesh","deformer","part","parameter","canvas","textures","authoring"],"writes":["mesh","relations"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val bodyLegacyV2WorkerJar = registerIsolatedBindingWorker(
    "bodyLegacyV2", "body-legacy-v2", "worker-body-legacy-v2", "io.github.psd2live.algorithms.binding.LegacyBodyV2Worker",
)

tasks.register("pluginEyeSquashV3") {
    dependsOn(eyeSquashV3WorkerJar)
    val output = layout.buildDirectory.dir("plugin-eye-squash-v3")
    val packageVersion = project.version.toString()
    inputs.file(eyeSquashV3WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", packageVersion)
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        eyeSquashV3WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.6579652e737175617368.v3"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"$packageVersion","name":"Iris squash",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/squash","aliases":["eye.squash"],"version":3,"name":"Iris squash","category":"eye",
               "targets":["mesh"],"requires":["rig.scaffold","eye/fitted"],"contextVersion":2,
               "reads":["mesh","deformer","parameter","canvas"],"writes":["mesh","deformer"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

tasks.register("pluginEyeSquashV4") {
    dependsOn(eyeSquashV4WorkerJar)
    val output = layout.buildDirectory.dir("plugin-eye-squash-v4")
    val packageVersion = project.version.toString()
    inputs.file(eyeSquashV4WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", packageVersion)
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        eyeSquashV4WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.6579652e737175617368.v4"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"$packageVersion","name":"Iris squash",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/squash","aliases":["eye.squash"],"version":4,"name":"Iris squash","category":"eye",
               "targets":["mesh"],"requires":["rig.scaffold","eye/fitted"],"contextVersion":2,
               "reads":["mesh","deformer","parameter","canvas"],"writes":["mesh","deformer"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

tasks.register("pluginEyeSquashV5") {
    dependsOn(eyeSquashV5WorkerJar)
    val output = layout.buildDirectory.dir("plugin-eye-squash-v5")
    val packageVersion = project.version.toString()
    inputs.file(eyeSquashV5WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", packageVersion)
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        eyeSquashV5WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.6579652e737175617368.v5"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"$packageVersion","name":"Iris squash",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/squash","aliases":["eye.squash"],"version":5,"name":"Iris squash","category":"eye",
               "targets":["mesh"],"requires":["rig.scaffold","eye/fitted"],"contextVersion":2,
               "reads":["*"],"writes":["mesh","deformer","part","hierarchy"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

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

val mouthOutlineV4WorkerJar = registerIsolatedBindingWorker(
    "mouthOutlineV4", "mouth-outline-v4", "worker-mouth-outline-v4", "io.github.psd2live.algorithms.binding.MouthOutlineV4Worker",
)
kotlin.target.compilations.named("mouthOutlineV4") {
    associateWith(kotlin.target.compilations.getByName("mouthOutline"))
    associateWith(kotlin.target.compilations.getByName("mouthOutlineV2"))
}
mouthOutlineV4WorkerJar.configure {
    from(mouthOutline.output)
    from(mouthOutlineV2.output)
}

tasks.register("pluginMouthOutlineV4") {
    dependsOn(mouthOutlineV4WorkerJar)
    val output = layout.buildDirectory.dir("plugin-mouth-outline-v4")
    inputs.file(mouthOutlineV4WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", project.version.toString())
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        mouthOutlineV4WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.6d6f7574682e6f75746c696e65.v4"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"${project.version}","name":"Classic mouth outline",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/outline","aliases":["mouth.outline"],"version":4,"contextVersion":2,"name":"Classic mouth outline","category":"mouth",
               "targets":["mesh"],"requires":["rig.scaffold","mouth/legacy","face/legacy"],"exclusiveFamily":"mouth.outline",
               "reads":["*"],"writes":["mesh","deformer","part","hierarchy","textures","authoring"],
               "composableKinds":["mesh","deformer","part","hierarchy"],
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

val browFittedV4WorkerJar = registerIsolatedBindingWorker(
    "browFittedV4", "brow-fitted-v4", "worker-brow-fitted-v4", "io.github.psd2live.algorithms.binding.BrowFittedV4Worker",
)
tasks.register("pluginBrowFittedV4") {
    dependsOn(browFittedV4WorkerJar)
    val output = layout.buildDirectory.dir("plugin-brow-fitted-v4")
    val packageVersion = project.version.toString()
    inputs.file(browFittedV4WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", packageVersion)
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        browFittedV4WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.62726f772f666974746564.v4"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"$packageVersion","name":"Fitted brows",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/binding","aliases":["brow/fitted"],"version":4,"name":"Fitted brows","category":"brow",
               "targets":["mesh"],"requires":["rig.scaffold"],"exclusiveFamily":"base:brow","contextVersion":2,
               "reads":["*"],"writes":["mesh","deformer","part","parameter","hierarchy"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val browFittedV5WorkerJar = registerIsolatedBindingWorker(
    "browFittedV5", "brow-fitted-v5", "worker-brow-fitted-v5", "io.github.psd2live.algorithms.binding.BrowFittedV5Worker",
)
browFittedV5WorkerJar.configure {
    // The shared frozen runtime excludes HairLegacy*. Brow5 independently replays hair sway,
    // so include its complete numeric kernel family only in this isolated candidate package.
    from(sourceSets.main.get().output) { include("**/HairLegacyMotion*.class") }
}
tasks.register("pluginBrowFittedV5") {
    dependsOn(browFittedV5WorkerJar)
    val output = layout.buildDirectory.dir("plugin-brow-fitted-v5")
    val packageVersion = project.version.toString()
    inputs.file(browFittedV5WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", packageVersion)
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        browFittedV5WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.62726f772f666974746564.v5"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"$packageVersion","name":"Fitted brows",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/binding","aliases":["brow/fitted"],"version":5,"name":"Fitted brows","category":"brow",
               "targets":["mesh"],"requires":["rig.scaffold","body/legacy","face/fitted","eye/fitted"],"exclusiveFamily":"base:brow","contextVersion":2,
               "reads":["*"],"writes":["mesh","deformer","part","parameter","hierarchy"],
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

val eyeWideV4WorkerJar = registerIsolatedBindingWorker(
    "eyeWideV4", "eye-wide-v4", "worker-eye-wide-v4", "io.github.psd2live.algorithms.binding.EyeWideV4Worker",
)

tasks.register("pluginEyeWideV4") {
    dependsOn(eyeWideV4WorkerJar)
    val output = layout.buildDirectory.dir("plugin-eye-wide-v4")
    val packageVersion = project.version.toString()
    inputs.file(eyeWideV4WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", packageVersion)
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        eyeWideV4WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.6579652e77696465.v4"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"$packageVersion","name":"Eye widening",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/wide","aliases":["eye.wide"],"version":4,"name":"Eye widening","category":"eye",
               "targets":["mesh"],"requires":["rig.scaffold","eye/fitted"],"contextVersion":2,
               "reads":["*"],"writes":["mesh","deformer","part","parameter","hierarchy"],
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


val eyeSmileV4WorkerJar = registerIsolatedBindingWorker(
    "eyeSmileV4", "eye-smile-v4", "worker-eye-smile-v4", "io.github.psd2live.algorithms.binding.EyeSmileV4Worker",
)

tasks.register("pluginEyeSmileV4") {
    dependsOn(eyeSmileV4WorkerJar)
    val output = layout.buildDirectory.dir("plugin-eye-smile-v4")
    val packageVersion = project.version.toString()
    inputs.file(eyeSmileV4WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", packageVersion)
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        eyeSmileV4WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.6579652e736d696c65.v4"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"$packageVersion","name":"Eye smile",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/smile","aliases":["eye.smile"],"version":4,"name":"Eye smile","category":"eye",
               "targets":["mesh"],"requires":["rig.scaffold","eye/fitted"],"contextVersion":2,
               "reads":["*"],"writes":["mesh","deformer","part","parameter","hierarchy"],
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

val browSpringV4WorkerJar = registerIsolatedBindingWorker(
    "browSpringV4", "brow-spring-v4", "worker-brow-spring-v4", "io.github.psd2live.algorithms.binding.BrowSpringV4Worker",
)

kotlin.target.compilations.named("browSpringV4") {
    associateWith(kotlin.target.compilations.getByName("browSpring"))
}
browSpringV4WorkerJar.configure {
    from(sourceSets["browSpring"].output) { exclude("**/BrowSpringWorker*.class") }
}

tasks.register("pluginBrowSpringV4") {
    dependsOn(browSpringV4WorkerJar)
    val output = layout.buildDirectory.dir("plugin-brow-spring-v4")
    val packageVersion = project.version.toString()
    inputs.file(browSpringV4WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", packageVersion)
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        browSpringV4WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.62726f772e737072696e67.v4"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"$packageVersion","name":"Brow spring",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/spring","aliases":["brow.spring"],"version":4,"name":"Brow spring","category":"brow",
               "targets":["mesh"],"requires":["rig.scaffold","brow/fitted"],"contextVersion":2,
               "reads":["canvas","mesh","deformer","parameter","hierarchy","physics"],"writes":["mesh","parameter","hierarchy","physics"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val browSpringV5WorkerJar = registerIsolatedBindingWorker(
    "browSpringV5", "brow-spring-v5", "worker-brow-spring-v5", "io.github.psd2live.algorithms.binding.BrowSpringV5Worker",
)

kotlin.target.compilations.named("browSpringV5") {
    associateWith(kotlin.target.compilations.getByName("browSpring"))
}
browSpringV5WorkerJar.configure {
    from(sourceSets["browSpring"].output) { exclude("**/BrowSpringWorker*.class") }
}

tasks.register("pluginBrowSpringV5") {
    dependsOn(browSpringV5WorkerJar)
    val output = layout.buildDirectory.dir("plugin-brow-spring-v5")
    val packageVersion = project.version.toString()
    inputs.file(browSpringV5WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", packageVersion)
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        browSpringV5WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.62726f772e737072696e67.v5"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"$packageVersion","name":"Brow spring",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/spring","aliases":["brow.spring"],"version":5,"name":"Brow spring","category":"brow",
               "targets":["mesh"],"requires":["rig.scaffold","brow/fitted"],"contextVersion":2,
               "reads":["canvas","mesh","deformer","parameter","hierarchy","physics","textures","authoring"],"writes":["mesh","deformer","parameter","hierarchy","physics"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val browSpringV6WorkerJar = registerIsolatedBindingWorker(
    "browSpringV6", "brow-spring-v6", "worker-brow-spring-v6", "io.github.psd2live.algorithms.binding.BrowSpringV6Worker",
)

kotlin.target.compilations.named("browSpringV6") {
    associateWith(kotlin.target.compilations.getByName("browSpring"))
}
browSpringV6WorkerJar.configure {
    from(sourceSets["browSpring"].output) { exclude("**/BrowSpringWorker*.class") }
}

tasks.register("pluginBrowSpringV6") {
    dependsOn(browSpringV6WorkerJar)
    val output = layout.buildDirectory.dir("plugin-brow-spring-v6")
    val packageVersion = project.version.toString()
    inputs.file(browSpringV6WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", packageVersion)
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        browSpringV6WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.62726f772e737072696e67.v6"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"$packageVersion","name":"Brow spring",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/spring","aliases":["brow.spring"],"version":6,"name":"Brow spring","category":"brow",
               "targets":["mesh"],"requires":["rig.scaffold","brow/fitted"],"contextVersion":2,
               "reads":["canvas","mesh","deformer","parameter","hierarchy","physics","textures","authoring"],"writes":["mesh","deformer","parameter","hierarchy","physics"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val browSpringV7WorkerJar = registerIsolatedBindingWorker(
    "browSpringV7", "brow-spring-v7", "worker-brow-spring-v7", "io.github.psd2live.algorithms.binding.BrowSpringV7Worker",
)

kotlin.target.compilations.named("browSpringV7") {
    associateWith(kotlin.target.compilations.getByName("browSpring"))
    associateWith(kotlin.target.compilations.getByName("browShape"))
}
browSpringV7WorkerJar.configure {
    from(sourceSets["browSpring"].output) { include("**/BrowSpringPhysics*.class") }
    from(sourceSets["browShape"].output) { include("**/BrowShapeInputs*.class") }
}

tasks.register("pluginBrowSpringV7") {
    dependsOn(browSpringV7WorkerJar)
    val output = layout.buildDirectory.dir("plugin-brow-spring-v7")
    val packageVersion = project.version.toString()
    inputs.file(browSpringV7WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", packageVersion)
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        browSpringV7WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.62726f772e737072696e67.v7"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"$packageVersion","name":"Brow spring",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/spring","aliases":["brow.spring"],"version":7,"name":"Brow spring","category":"brow",
               "targets":["mesh"],"requires":["rig.scaffold","face/fitted","eye/fitted","brow/fitted","brow.shape","brow.lid-coupling","brow.blink"],"contextVersion":2,
               "reads":["canvas","mesh","deformer","parameter","hierarchy","physics"],"writes":["mesh","parameter","hierarchy","physics"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

// Frozen source ancestry is explicitly opt-in and absent from main/historical worker closures.
val browSourceAncestryV1Sources = sourceSets.create("browSourceAncestryV1")
kotlin.target.compilations.named("browSourceAncestryV1") {
    associateWith(kotlin.target.compilations.getByName("main"))
    associateWith(kotlin.target.compilations.getByName("browShapeV6"))
    associateWith(kotlin.target.compilations.getByName("browShape"))
}
val browSourceAncestryV1ClassesJar = tasks.register<Jar>("browSourceAncestryV1ClassesJar") {
    archiveClassifier.set("brow-source-ancestry-v1")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    from(browSourceAncestryV1Sources.output)
}
val browSourceAncestryV1Elements = configurations.create("browSourceAncestryV1Elements") {
    isCanBeConsumed = true
    isCanBeResolved = false
}
artifacts.add(browSourceAncestryV1Elements.name, browSourceAncestryV1ClassesJar)

val browShapeV7WorkerJar = registerIsolatedBindingWorker(
    "browShapeV7", "brow-shape-v7", "worker-brow-shape-v7", "io.github.psd2live.algorithms.binding.BrowShapeV7Worker",
)

kotlin.target.compilations.named("browShapeV7") {
    associateWith(kotlin.target.compilations.getByName("browShape"))
    associateWith(kotlin.target.compilations.getByName("browSourceAncestryV1"))
}
browShapeV7WorkerJar.configure {
    from(browSourceAncestryV1Sources.output)
    from(sourceSets["browShapeV6"].output) { include("**/BrowShapeCarrierScope*.class") }
    from(sourceSets["browShape"].output) { include("**/BrowShapeInputs*.class") }
}

tasks.register("pluginBrowShapeV7") {
    dependsOn(browShapeV7WorkerJar)
    val output = layout.buildDirectory.dir("plugin-brow-shape-v7")
    val packageVersion = project.version.toString()
    inputs.file(browShapeV7WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", packageVersion)
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        browShapeV7WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.62726f772e7368617065.v7"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"$packageVersion","name":"Brow shapes",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/shape","aliases":["brow.shape"],"version":7,"name":"Brow shapes","category":"brow",
               "targets":["mesh"],"requires":["rig.scaffold","body/legacy","face/fitted","eye/fitted","brow/fitted"],"contextVersion":2,
               "reads":["mesh","deformer","part","parameter","hierarchy","canvas","textures","authoring"],"writes":["mesh","deformer","parameter","hierarchy"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val browBlinkV7WorkerJar = registerIsolatedBindingWorker(
    "browBlinkV7", "brow-blink-v7", "worker-brow-blink-v7", "io.github.psd2live.algorithms.binding.BrowBlinkV7Worker",
)
kotlin.target.compilations.named("browBlinkV7") {
    associateWith(kotlin.target.compilations.getByName("browShape"))
    associateWith(kotlin.target.compilations.getByName("browSourceAncestryV1"))
    associateWith(kotlin.target.compilations.getByName("browBlinkV6"))
    associateWith(kotlin.target.compilations.getByName("browShapeV6"))
}
browBlinkV7WorkerJar.configure {
    from(browSourceAncestryV1Sources.output)
    from(sourceSets["browBlinkV6"].output) { include("**/BrowBlinkV6Witnesses*.class") }
    from(sourceSets["browShape"].output) { include("**/BrowShapeInputs*.class") }
    from(sourceSets["browShapeV6"].output) { include("**/BrowShapeCarrierScope*.class") }
}
tasks.register("pluginBrowBlinkV7") {
    dependsOn(browBlinkV7WorkerJar)
    val output = layout.buildDirectory.dir("plugin-brow-blink-v7")
    val packageVersion = project.version.toString()
    inputs.file(browBlinkV7WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", packageVersion)
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        browBlinkV7WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.62726f772e626c696e6b.v7"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"$packageVersion","name":"Brow blink",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/blink","aliases":["brow.blink"],"version":7,"name":"Brow blink","category":"brow",
               "targets":["mesh"],"requires":["rig.scaffold","body/legacy","face/fitted","eye/fitted","brow/fitted","brow.shape","brow.lid-coupling"],"contextVersion":2,
               "reads":["mesh","deformer","part","parameter","canvas","textures","authoring"],"writes":["mesh","deformer"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}


val browSpringV8WorkerJar = registerIsolatedBindingWorker(
    "browSpringV8", "brow-spring-v8", "worker-brow-spring-v8", "io.github.psd2live.algorithms.binding.BrowSpringV8Worker",
)

kotlin.target.compilations.named("browSpringV8") {
    associateWith(kotlin.target.compilations.getByName("browSourceAncestryV1"))
    associateWith(kotlin.target.compilations.getByName("browSpring"))
    associateWith(kotlin.target.compilations.getByName("browShape"))
}
browSpringV8WorkerJar.configure {
    from(browSourceAncestryV1Sources.output) { exclude("**/BrowSourceTransitionV1*.class") }
    from(sourceSets["browSpring"].output) { include("**/BrowSpringPhysics*.class") }
    from(sourceSets["browShape"].output) { include("**/BrowShapeInputs*.class") }
}

tasks.register("pluginBrowSpringV8") {
    dependsOn(browSpringV8WorkerJar)
    val output = layout.buildDirectory.dir("plugin-brow-spring-v8")
    val packageVersion = project.version.toString()
    inputs.file(browSpringV8WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", packageVersion)
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        browSpringV8WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.62726f772e737072696e67.v8"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"$packageVersion","name":"Brow spring",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/spring","aliases":["brow.spring"],"version":8,"name":"Brow spring","category":"brow",
               "targets":["mesh"],"requires":["rig.scaffold","body/legacy","face/fitted","eye/fitted","brow/fitted","brow.shape","brow.lid-coupling","brow.blink"],"contextVersion":2,
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

val armSwingV4WorkerJar = registerIsolatedBindingWorker(
    "armSwingV4", "body-arms-v4", "worker-body-arms-v4", "io.github.psd2live.algorithms.binding.ArmSwingV4Worker",
)

kotlin.target.compilations.named("armSwingV4") {
    associateWith(kotlin.target.compilations.getByName("armSwingV3"))
}
armSwingV4WorkerJar.configure {
    from(sourceSets["armSwing"].output)
    from(sourceSets["armSwingV3"].output)
}
tasks.register("pluginArmSwingV4") {
    dependsOn(armSwingV4WorkerJar)
    val output = layout.buildDirectory.dir("plugin-arm-swing-v4")
    inputs.file(armSwingV4WorkerJar.flatMap { it.archiveFile })
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        armSwingV4WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.626f64792e61726d73.v4"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"1.0.0","name":"Arm swing",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/swing","aliases":["body.arms"],"version":4,"name":"Arm swing","category":"body",
               "targets":["mesh"],"requires":["rig.scaffold","body/legacy","face/legacy"],"contextVersion":2,
               "reads":["canvas","mesh","deformer","parameter","hierarchy","physics","textures","authoring"],"writes":["mesh","deformer","parameter","hierarchy","physics"],
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

val browLidV4WorkerJar = registerIsolatedBindingWorker(
    "browLidV4", "brow-lid-v4", "worker-brow-lid-v4", "io.github.psd2live.algorithms.binding.BrowLidV4Worker",
)

kotlin.target.compilations.named("browLidV4") {
    associateWith(kotlin.target.compilations.getByName("eyeWide"))
}
browLidV4WorkerJar.configure {
    from(sourceSets["eyeWide"].output) { include("**/EyeWideInputs*.class") }
}

tasks.register("pluginBrowLidV4") {
    dependsOn(browLidV4WorkerJar)
    val output = layout.buildDirectory.dir("plugin-brow-lid-v4")
    val packageVersion = project.version.toString()
    inputs.file(browLidV4WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", packageVersion)
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        browLidV4WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.62726f772e6c69642d636f75706c696e67.v4"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"$packageVersion","name":"Brow and lid together",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/coupling","aliases":["brow.lid-coupling"],"version":4,"name":"Brow and lid together","category":"brow",
               "targets":["mesh"],"requires":["rig.scaffold","brow/fitted","eye/fitted"],"contextVersion":2,
               "reads":["mesh","parameter","canvas","deformer"],"writes":["mesh","deformer"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val browLidV5WorkerJar = registerIsolatedBindingWorker(
    "browLidV5", "brow-lid-v5", "worker-brow-lid-v5", "io.github.psd2live.algorithms.binding.BrowLidV5Worker",
)

kotlin.target.compilations.named("browLidV5") {
    associateWith(kotlin.target.compilations.getByName("eyeWide"))
}
browLidV5WorkerJar.configure {
    from(sourceSets["eyeWide"].output) { include("**/EyeWideInputs*.class") }
}

tasks.register("pluginBrowLidV5") {
    dependsOn(browLidV5WorkerJar)
    val output = layout.buildDirectory.dir("plugin-brow-lid-v5")
    val packageVersion = project.version.toString()
    inputs.file(browLidV5WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", packageVersion)
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        browLidV5WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.62726f772e6c69642d636f75706c696e67.v5"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"$packageVersion","name":"Brow and lid together",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/coupling","aliases":["brow.lid-coupling"],"version":5,"name":"Brow and lid together","category":"brow",
               "targets":["mesh"],"requires":["rig.scaffold","brow/fitted","eye/fitted"],"contextVersion":2,
               "reads":["mesh","parameter","canvas","deformer"],"writes":["mesh","deformer"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val browLidV6WorkerJar = registerIsolatedBindingWorker(
    "browLidV6", "brow-lid-v6", "worker-brow-lid-v6", "io.github.psd2live.algorithms.binding.BrowLidV6Worker",
)

kotlin.target.compilations.named("browLidV6") {
    associateWith(kotlin.target.compilations.getByName("eyeWide"))
}
browLidV6WorkerJar.configure {
    from(sourceSets["eyeWide"].output) { include("**/EyeWideInputs*.class") }
}

tasks.register("pluginBrowLidV6") {
    dependsOn(browLidV6WorkerJar)
    val output = layout.buildDirectory.dir("plugin-brow-lid-v6")
    val packageVersion = project.version.toString()
    inputs.file(browLidV6WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", packageVersion)
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        browLidV6WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.62726f772e6c69642d636f75706c696e67.v6"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"$packageVersion","name":"Brow and lid together",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/coupling","aliases":["brow.lid-coupling"],"version":6,"name":"Brow and lid together","category":"brow",
               "targets":["mesh"],"requires":["rig.scaffold","brow/fitted","eye/fitted"],"contextVersion":2,
               "reads":["mesh","parameter","canvas","deformer"],"writes":["mesh","deformer"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val browLidV8WorkerJar = registerIsolatedBindingWorker(
    "browLidV8", "brow-lid-v8", "worker-brow-lid-v8", "io.github.psd2live.algorithms.binding.BrowLidV8Worker",
)

kotlin.target.compilations.named("browLidV8") {
    associateWith(kotlin.target.compilations.getByName("eyeWide"))
}
browLidV8WorkerJar.configure {
    from(sourceSets["eyeWide"].output) { include("**/EyeWideInputs*.class") }
}

tasks.register("pluginBrowLidV8") {
    dependsOn(browLidV8WorkerJar)
    val output = layout.buildDirectory.dir("plugin-brow-lid-v8")
    val packageVersion = project.version.toString()
    inputs.file(browLidV8WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", packageVersion)
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        browLidV8WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.62726f772e6c69642d636f75706c696e67.v8"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"$packageVersion","name":"Brow and lid together",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/coupling","aliases":["brow.lid-coupling"],"version":8,"name":"Brow and lid together","category":"brow",
               "targets":["mesh"],"requires":["rig.scaffold","face/fitted","brow/fitted","eye/fitted","brow.shape"],"contextVersion":2,
               "reads":["mesh","parameter","canvas","deformer"],"writes":["mesh","deformer"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val browLidV9WorkerJar = registerIsolatedBindingWorker(
    "browLidV9", "brow-lid-v9", "worker-brow-lid-v9", "io.github.psd2live.algorithms.binding.BrowLidV9Worker",
)

kotlin.target.compilations.named("browLidV9") {
    associateWith(kotlin.target.compilations.getByName("eyeWide"))
    associateWith(kotlin.target.compilations.getByName("browSourceAncestryV1"))
}
browLidV9WorkerJar.configure {
    from(browSourceAncestryV1Sources.output) { include("**/BrowSourceProvidersV1*.class") }
    from(sourceSets["eyeWide"].output) { include("**/EyeWideInputs*.class") }
}

tasks.register("pluginBrowLidV9") {
    dependsOn(browLidV9WorkerJar)
    val output = layout.buildDirectory.dir("plugin-brow-lid-v9")
    val packageVersion = project.version.toString()
    inputs.file(browLidV9WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", packageVersion)
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        browLidV9WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.62726f772e6c69642d636f75706c696e67.v9"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"$packageVersion","name":"Brow and lid together",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/coupling","aliases":["brow.lid-coupling"],"version":9,"name":"Brow and lid together","category":"brow",
               "targets":["mesh"],"requires":["rig.scaffold","body/legacy","face/fitted","eye/fitted","brow/fitted","brow.shape"],"contextVersion":2,
               "reads":["mesh","parameter","canvas","deformer"],"writes":["mesh","deformer"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val browLidV7WorkerJar = registerIsolatedBindingWorker(
    "browLidV7", "brow-lid-v7", "worker-brow-lid-v7", "io.github.psd2live.algorithms.binding.BrowLidV7Worker",
)

tasks.register("pluginBrowLidV7") {
    dependsOn(browLidV7WorkerJar)
    val output = layout.buildDirectory.dir("plugin-brow-lid-v7")
    val packageVersion = project.version.toString()
    inputs.file(browLidV7WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", packageVersion)
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        browLidV7WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.62726f772e6c69642d636f75706c696e67.v7"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"$packageVersion","name":"Brow and lid together",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/coupling","aliases":["brow.lid-coupling"],"version":7,"name":"Brow and lid together","category":"brow",
               "targets":["mesh"],"requires":["rig.scaffold","brow/fitted","eye/fitted"],"contextVersion":2,
               "reads":["*"],"writes":["mesh","deformer","part","hierarchy"],
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

val eyeGazeLidV3WorkerJar = registerIsolatedBindingWorker(
    "eyeGazeLidV3", "eye-gaze-lid-v3", "worker-eye-gaze-lid-v3", "io.github.psd2live.algorithms.binding.EyeGazeLidV3Worker",
)

kotlin.target.compilations.named("eyeGazeLidV3") {
    associateWith(kotlin.target.compilations.getByName("eyeSmile"))
}
eyeGazeLidV3WorkerJar.configure {
    from(sourceSets["eyeSmile"].output) { include("**/EyeSmileInputs*.class") }
}

tasks.register("pluginEyeGazeLidV3") {
    dependsOn(eyeGazeLidV3WorkerJar)
    val output = layout.buildDirectory.dir("plugin-eye-gaze-lid-v3")
    val packageVersion = project.version.toString()
    inputs.file(eyeGazeLidV3WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", packageVersion)
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        eyeGazeLidV3WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.6579652e67617a652d6c6964.v3"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"$packageVersion","name":"Lids follow gaze",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/gaze","aliases":["eye.gaze-lid"],"version":3,"name":"Lids follow gaze","category":"eye",
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


val eyeGazeLidV4WorkerJar = registerIsolatedBindingWorker(
    "eyeGazeLidV4", "eye-gaze-lid-v4", "worker-eye-gaze-lid-v4", "io.github.psd2live.algorithms.binding.EyeGazeLidV4Worker",
)

tasks.register("pluginEyeGazeLidV4") {
    dependsOn(eyeGazeLidV4WorkerJar)
    val output = layout.buildDirectory.dir("plugin-eye-gaze-lid-v4")
    val packageVersion = project.version.toString()
    inputs.file(eyeGazeLidV4WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", packageVersion)
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        eyeGazeLidV4WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.6579652e67617a652d6c6964.v4"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"$packageVersion","name":"Lids follow gaze",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/gaze","aliases":["eye.gaze-lid"],"version":4,"name":"Lids follow gaze","category":"eye",
               "targets":["mesh"],"requires":["rig.scaffold","eye/fitted"],"contextVersion":2,
               "reads":["*"],"writes":["mesh","deformer","part","parameter","hierarchy","relations"],
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

val ribbonsV3WorkerJar = registerIsolatedBindingWorker(
    "ribbonsV3", "outfit-ribbons-v3", "worker-outfit-ribbons-v3", "io.github.psd2live.algorithms.binding.RibbonsV3Worker",
)

kotlin.target.compilations.named("ribbonsV3") {
    associateWith(kotlin.target.compilations.getByName("ribbons"))
}
ribbonsV3WorkerJar.configure { from(sourceSets["ribbons"].output) }

tasks.register("pluginRibbonsV3") {
    dependsOn(ribbonsV3WorkerJar)
    val output = layout.buildDirectory.dir("plugin-outfit-ribbons-v3")
    inputs.file(ribbonsV3WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", project.version.toString())
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        ribbonsV3WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"builtin.6f75746669742e726962626f6e73.v3","version":"${project.version}","name":"Ribbon and pendant sway",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"builtin.6f75746669742e726962626f6e73.v3/ribbons","aliases":["outfit.ribbons"],
               "version":3,"name":"Ribbon and pendant sway","category":"outfit","contextVersion":2,"targets":["mesh"],
               "requires":["rig.scaffold","body/legacy","face/legacy"],"exclusiveFamily":"chain:outfit.ribbons",
               "reads":["*"],"writes":["mesh","part","deformer","hierarchy","parameter","physics"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter","physics"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val ribbonsV4WorkerJar = registerIsolatedBindingWorker(
    "ribbonsV4", "outfit-ribbons-v4", "worker-outfit-ribbons-v4", "io.github.psd2live.algorithms.binding.RibbonsV4Worker",
)

kotlin.target.compilations.named("ribbonsV4") {
    associateWith(kotlin.target.compilations.getByName("ribbons"))
}
ribbonsV4WorkerJar.configure { from(sourceSets["ribbons"].output) }

tasks.register("pluginRibbonsV4") {
    dependsOn(ribbonsV4WorkerJar)
    val output = layout.buildDirectory.dir("plugin-outfit-ribbons-v4")
    inputs.file(ribbonsV4WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", project.version.toString())
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        ribbonsV4WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"builtin.6f75746669742e726962626f6e73.v4","version":"${project.version}","name":"Ribbon and pendant sway",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"builtin.6f75746669742e726962626f6e73.v4/ribbons","aliases":["outfit.ribbons"],
               "version":4,"name":"Ribbon and pendant sway","category":"outfit","contextVersion":2,"targets":["mesh"],
               "requires":["rig.scaffold","body/legacy","face/legacy"],"exclusiveFamily":"chain:outfit.ribbons",
               "reads":["*"],"writes":["mesh","part","deformer","hierarchy","parameter","physics"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter","physics"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val ribbonsV5WorkerJar = registerIsolatedBindingWorker(
    "ribbonsV5", "outfit-ribbons-v5", "worker-outfit-ribbons-v5", "io.github.psd2live.algorithms.binding.RibbonsV5Worker",
)

kotlin.target.compilations.named("ribbonsV5") {
    associateWith(kotlin.target.compilations.getByName("ribbons"))
}
ribbonsV5WorkerJar.configure { from(sourceSets["ribbons"].output) }

tasks.register("pluginRibbonsV5") {
    dependsOn(ribbonsV5WorkerJar)
    val output = layout.buildDirectory.dir("plugin-outfit-ribbons-v5")
    inputs.file(ribbonsV5WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", project.version.toString())
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        ribbonsV5WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"builtin.6f75746669742e726962626f6e73.v5","version":"${project.version}","name":"Ribbon and pendant sway",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"builtin.6f75746669742e726962626f6e73.v5/ribbons","aliases":["outfit.ribbons"],
               "version":5,"name":"Ribbon and pendant sway","category":"outfit","contextVersion":2,"targets":["mesh"],
               "requires":["rig.scaffold","body/legacy","face/legacy"],"exclusiveFamily":"chain:outfit.ribbons",
               "reads":["*"],"writes":["mesh","part","deformer","hierarchy","parameter","physics"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter","physics"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val ribbonsV6WorkerJar = registerIsolatedBindingWorker(
    "ribbonsV6", "outfit-ribbons-v6", "worker-outfit-ribbons-v6", "io.github.psd2live.algorithms.binding.RibbonsV6Worker",
)

kotlin.target.compilations.named("ribbonsV6") {
    associateWith(kotlin.target.compilations.getByName("ribbons"))
}
ribbonsV6WorkerJar.configure { from(sourceSets["ribbons"].output) }

tasks.register("pluginRibbonsV6") {
    dependsOn(ribbonsV6WorkerJar)
    val output = layout.buildDirectory.dir("plugin-outfit-ribbons-v6")
    inputs.file(ribbonsV6WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", project.version.toString())
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        ribbonsV6WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"builtin.6f75746669742e726962626f6e73.v6","version":"${project.version}","name":"Ribbon and pendant sway",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"builtin.6f75746669742e726962626f6e73.v6/ribbons","aliases":["outfit.ribbons"],
               "version":6,"name":"Ribbon and pendant sway","category":"outfit","contextVersion":2,"targets":["mesh"],
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

val eyeEmotionV3WorkerJar = registerIsolatedBindingWorker(
    "eyeEmotionV3", "eye-emotion-v3", "worker-eye-emotion-v3", "io.github.psd2live.algorithms.binding.EyeEmotionV3Worker",
)

kotlin.target.compilations.named("eyeEmotionV3") {
    associateWith(kotlin.target.compilations.getByName("eyeSmile"))
}
eyeEmotionV3WorkerJar.configure {
    from(sourceSets["eyeSmile"].output) { include("**/EyeSmileInputs*.class") }
}

tasks.register("pluginEyeEmotionV3") {
    dependsOn(eyeEmotionV3WorkerJar)
    val output = layout.buildDirectory.dir("plugin-eye-emotion-v3")
    val packageVersion = project.version.toString()
    inputs.file(eyeEmotionV3WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", packageVersion)
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        eyeEmotionV3WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.6579652e656d6f74696f6e.v3"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"$packageVersion","name":"Emotion eye shapes",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/emotion","aliases":["eye.emotion"],"version":3,"name":"Emotion eye shapes","category":"eye",
               "targets":["mesh"],"requires":["rig.scaffold","eye/fitted"],"contextVersion":2,
               "reads":["mesh","parameter","hierarchy","canvas","deformer"],"writes":["mesh","deformer","parameter","hierarchy"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val eyeEmotionV4WorkerJar = registerIsolatedBindingWorker(
    "eyeEmotionV4", "eye-emotion-v4", "worker-eye-emotion-v4", "io.github.psd2live.algorithms.binding.EyeEmotionV4Worker",
)

tasks.register("pluginEyeEmotionV4") {
    dependsOn(eyeEmotionV4WorkerJar)
    val output = layout.buildDirectory.dir("plugin-eye-emotion-v4")
    val packageVersion = project.version.toString()
    inputs.file(eyeEmotionV4WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", packageVersion)
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        eyeEmotionV4WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.6579652e656d6f74696f6e.v4"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"$packageVersion","name":"Emotion eye shapes",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/emotion","aliases":["eye.emotion"],"version":4,"name":"Emotion eye shapes","category":"eye",
               "targets":["mesh"],"requires":["rig.scaffold","eye/fitted"],"contextVersion":2,
               "reads":["*"],"writes":["mesh","deformer","part","parameter","hierarchy"],
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

val creatureV3WorkerJar = registerIsolatedBindingWorker(
    "creatureV3", "outfit-creature-v3", "worker-outfit-creature-v3", "io.github.psd2live.algorithms.binding.CreatureV3Worker",
)

kotlin.target.compilations.named("creatureV3") {
    associateWith(kotlin.target.compilations.getByName("creature"))
}
creatureV3WorkerJar.configure { from(sourceSets["creature"].output) }

tasks.register("pluginCreatureV3") {
    dependsOn(creatureV3WorkerJar)
    val output = layout.buildDirectory.dir("plugin-outfit-creature-v3")
    inputs.file(creatureV3WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", project.version.toString())
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        creatureV3WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"builtin.6f75746669742e6372656174757265.v3","version":"${project.version}","name":"Animal ears, tails and wings",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"builtin.6f75746669742e6372656174757265.v3/creature","aliases":["outfit.creature"],
               "version":3,"name":"Animal ears, tails and wings","category":"outfit","contextVersion":2,"targets":["mesh"],
               "requires":["rig.scaffold","body/legacy","face/legacy"],"exclusiveFamily":"chain:outfit.creature",
               "reads":["*"],"writes":["mesh","part","deformer","hierarchy","parameter","physics"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter","physics"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val creatureV4WorkerJar = registerIsolatedBindingWorker(
    "creatureV4", "outfit-creature-v4", "worker-outfit-creature-v4", "io.github.psd2live.algorithms.binding.CreatureV4Worker",
)

kotlin.target.compilations.named("creatureV4") {
    associateWith(kotlin.target.compilations.getByName("creature"))
}
creatureV4WorkerJar.configure { from(sourceSets["creature"].output) }

tasks.register("pluginCreatureV4") {
    dependsOn(creatureV4WorkerJar)
    val output = layout.buildDirectory.dir("plugin-outfit-creature-v4")
    inputs.file(creatureV4WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", project.version.toString())
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        creatureV4WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"builtin.6f75746669742e6372656174757265.v4","version":"${project.version}","name":"Animal ears, tails and wings",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"builtin.6f75746669742e6372656174757265.v4/creature","aliases":["outfit.creature"],
               "version":4,"name":"Animal ears, tails and wings","category":"outfit","contextVersion":2,"targets":["mesh"],
               "requires":["rig.scaffold","body/legacy","face/legacy"],"exclusiveFamily":"chain:outfit.creature",
               "reads":["*"],"writes":["mesh","part","deformer","hierarchy","parameter","physics"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter","physics"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val creatureV5WorkerJar = registerIsolatedBindingWorker(
    "creatureV5", "outfit-creature-v5", "worker-outfit-creature-v5", "io.github.psd2live.algorithms.binding.CreatureV5Worker",
)

kotlin.target.compilations.named("creatureV5") {
    associateWith(kotlin.target.compilations.getByName("creature"))
}
creatureV5WorkerJar.configure { from(sourceSets["creature"].output) }

tasks.register("pluginCreatureV5") {
    dependsOn(creatureV5WorkerJar)
    val output = layout.buildDirectory.dir("plugin-outfit-creature-v5")
    inputs.file(creatureV5WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", project.version.toString())
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        creatureV5WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"builtin.6f75746669742e6372656174757265.v5","version":"${project.version}","name":"Animal ears, tails and wings",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"builtin.6f75746669742e6372656174757265.v5/creature","aliases":["outfit.creature"],
               "version":5,"name":"Animal ears, tails and wings","category":"outfit","contextVersion":2,"targets":["mesh"],
               "requires":["rig.scaffold","body/legacy","face/legacy"],"exclusiveFamily":"chain:outfit.creature",
               "reads":["*"],"writes":["mesh","part","deformer","hierarchy","parameter","physics"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter","physics"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val creatureV6WorkerJar = registerIsolatedBindingWorker(
    "creatureV6", "outfit-creature-v6", "worker-outfit-creature-v6", "io.github.psd2live.algorithms.binding.CreatureV6Worker",
)

kotlin.target.compilations.named("creatureV6") {
    associateWith(kotlin.target.compilations.getByName("creature"))
}
creatureV6WorkerJar.configure { from(sourceSets["creature"].output) }

tasks.register("pluginCreatureV6") {
    dependsOn(creatureV6WorkerJar)
    val output = layout.buildDirectory.dir("plugin-outfit-creature-v6")
    inputs.file(creatureV6WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", project.version.toString())
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        creatureV6WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"builtin.6f75746669742e6372656174757265.v6","version":"${project.version}","name":"Animal ears, tails and wings",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"builtin.6f75746669742e6372656174757265.v6/creature","aliases":["outfit.creature"],
               "version":6,"name":"Animal ears, tails and wings","category":"outfit","contextVersion":2,"targets":["mesh"],
               "requires":["rig.scaffold","body/legacy","face/legacy"],"exclusiveFamily":"chain:outfit.creature",
               "reads":["*"],"writes":["mesh","part","deformer","hierarchy","parameter","physics"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter","physics"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val creatureV7WorkerJar = registerIsolatedBindingWorker(
    "creatureV7", "outfit-creature-v7", "worker-outfit-creature-v7", "io.github.psd2live.algorithms.binding.CreatureV7Worker",
)

kotlin.target.compilations.named("creatureV7") {
    associateWith(kotlin.target.compilations.getByName("creature"))
}
creatureV7WorkerJar.configure { from(sourceSets["creature"].output) }

tasks.register("pluginCreatureV7") {
    dependsOn(creatureV7WorkerJar)
    val output = layout.buildDirectory.dir("plugin-outfit-creature-v7")
    inputs.file(creatureV7WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", project.version.toString())
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        creatureV7WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"builtin.6f75746669742e6372656174757265.v7","version":"${project.version}","name":"Animal ears, tails and wings",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"builtin.6f75746669742e6372656174757265.v7/creature","aliases":["outfit.creature"],
               "version":7,"name":"Animal ears, tails and wings","category":"outfit","contextVersion":2,"targets":["mesh"],
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

val eyeHighlightV4WorkerJar = registerIsolatedBindingWorker(
    "eyeHighlightV4", "eye-highlight-v4", "worker-eye-highlight-v4", "io.github.psd2live.algorithms.binding.EyeHighlightV4Worker",
)

tasks.register("pluginEyeHighlightV4") {
    dependsOn(eyeHighlightV4WorkerJar)
    val output = layout.buildDirectory.dir("plugin-eye-highlight-v4")
    val packageVersion = project.version.toString()
    inputs.file(eyeHighlightV4WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", packageVersion)
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        eyeHighlightV4WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.6579652e686967686c69676874.v4"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"$packageVersion","name":"Eye highlight",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/highlight","aliases":["eye.highlight"],"version":4,"name":"Eye highlight","category":"eye",
               "targets":["mesh"],"requires":["rig.scaffold","eye/fitted","face/legacy"],"contextVersion":2,
               "reads":["*"],"writes":["mesh","deformer","part","hierarchy"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val eyeHighlightV3WorkerJar = registerIsolatedBindingWorker(
    "eyeHighlightV3", "eye-highlight-v3", "worker-eye-highlight-v3", "io.github.psd2live.algorithms.binding.EyeHighlightV3Worker",
)

kotlin.target.compilations.named("eyeHighlightV3") {
    associateWith(kotlin.target.compilations.getByName("eyeHighlight"))
    associateWith(kotlin.target.compilations.getByName("eyeSquash"))
}
eyeHighlightV3WorkerJar.configure {
    from(sourceSets["eyeHighlight"].output)
    from(sourceSets["eyeSquash"].output)
}
tasks.register("pluginEyeHighlightV3") {
    dependsOn(eyeHighlightV3WorkerJar)
    val output = layout.buildDirectory.dir("plugin-eye-highlight-v3")
    val packageVersion = project.version.toString()
    inputs.file(eyeHighlightV3WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", packageVersion)
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        eyeHighlightV3WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.6579652e686967686c69676874.v3"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"$packageVersion","name":"Highlight parallax",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/highlight","aliases":["eye.highlight"],"version":3,"name":"Highlight parallax","category":"eye",
               "targets":["mesh"],"requires":["rig.scaffold","eye/fitted","face/legacy"],"contextVersion":2,
               "reads":["mesh","parameter","hierarchy","canvas","deformer"],"writes":["mesh","deformer"],
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

val mouthDepthV4WorkerJar = registerIsolatedBindingWorker(
    "mouthDepthV4", "mouth-depth-v4", "worker-mouth-depth-v4", "io.github.psd2live.algorithms.binding.MouthDepthV4Worker",
)

tasks.register("pluginMouthDepthV4") {
    dependsOn(mouthDepthV4WorkerJar)
    val output = layout.buildDirectory.dir("plugin-mouth-depth-v4")
    val packageVersion = project.version.toString()
    inputs.file(mouthDepthV4WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", packageVersion)
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        mouthDepthV4WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.6d6f7574682e696e746572696f722d6465707468.v4"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"$packageVersion","name":"Mouth interior depth",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/depth","aliases":["mouth.interior-depth"],"version":4,"name":"Mouth interior depth","category":"mouth",
               "targets":["mesh"],"requires":["rig.scaffold","mouth/fitted","face/legacy"],"contextVersion":2,
               "reads":["mesh","parameter","hierarchy","canvas","deformer"],"writes":["mesh","deformer"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val mouthDepthV5WorkerJar = registerIsolatedBindingWorker(
    "mouthDepthV5", "mouth-depth-v5", "worker-mouth-depth-v5", "io.github.psd2live.algorithms.binding.MouthDepthV5Worker",
)

tasks.register("pluginMouthDepthV5") {
    dependsOn(mouthDepthV5WorkerJar)
    val output = layout.buildDirectory.dir("plugin-mouth-depth-v5")
    val packageVersion = project.version.toString()
    inputs.file(mouthDepthV5WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", packageVersion)
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        mouthDepthV5WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.6d6f7574682e696e746572696f722d6465707468.v5"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"$packageVersion","name":"Mouth interior depth",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/depth","aliases":["mouth.interior-depth"],"version":5,"name":"Mouth interior depth","category":"mouth",
               "targets":["mesh"],"requires":["rig.scaffold","mouth/fitted","face/legacy"],"contextVersion":2,
               "reads":["mesh","parameter","hierarchy","canvas","deformer"],"writes":["mesh","deformer"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}


val mouthDepthV6WorkerJar = registerIsolatedBindingWorker(
    "mouthDepthV6", "mouth-depth-v6", "worker-mouth-depth-v6", "io.github.psd2live.algorithms.binding.MouthDepthV6Worker",
)

tasks.register("pluginMouthDepthV6") {
    dependsOn(mouthDepthV6WorkerJar)
    val output = layout.buildDirectory.dir("plugin-mouth-depth-v6")
    val packageVersion = project.version.toString()
    inputs.file(mouthDepthV6WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", packageVersion)
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        mouthDepthV6WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.6d6f7574682e696e746572696f722d6465707468.v6"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"$packageVersion","name":"Mouth interior depth",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/depth","aliases":["mouth.interior-depth"],"version":6,"name":"Mouth interior depth","category":"mouth",
               "targets":["mesh"],"requires":["rig.scaffold","mouth/fitted","face/legacy"],"contextVersion":2,
               "reads":["*"],"writes":["mesh","deformer","part","hierarchy"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val mouthDepthV7WorkerJar = registerIsolatedBindingWorker(
    "mouthDepthV7", "mouth-depth-v7", "worker-mouth-depth-v7", "io.github.psd2live.algorithms.binding.MouthDepthV7Worker",
)

tasks.register("pluginMouthDepthV7") {
    dependsOn(mouthDepthV7WorkerJar)
    val output = layout.buildDirectory.dir("plugin-mouth-depth-v7")
    val packageVersion = project.version.toString()
    inputs.file(mouthDepthV7WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", packageVersion)
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        mouthDepthV7WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.6d6f7574682e696e746572696f722d6465707468.v7"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"$packageVersion","name":"Mouth interior depth",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/depth","aliases":["mouth.interior-depth"],"version":7,"name":"Mouth interior depth","category":"mouth",
               "targets":["mesh"],"requires":["rig.scaffold","mouth/fitted","face/legacy"],"contextVersion":2,
               "reads":["*"],"writes":["mesh","deformer","part","hierarchy"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val mouthDepthV8WorkerJar = registerIsolatedBindingWorker(
    "mouthDepthV8", "mouth-depth-v8", "worker-mouth-depth-v8", "io.github.psd2live.algorithms.binding.MouthDepthV8Worker",
)

tasks.register("pluginMouthDepthV8") {
    dependsOn(mouthDepthV8WorkerJar)
    val output = layout.buildDirectory.dir("plugin-mouth-depth-v8")
    val packageVersion = project.version.toString()
    inputs.file(mouthDepthV8WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", packageVersion)
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        mouthDepthV8WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.6d6f7574682e696e746572696f722d6465707468.v8"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"$packageVersion","name":"Layered mouth interior depth",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/depth","aliases":["mouth.interior-depth"],"version":8,"name":"Layered mouth interior depth","category":"mouth",
               "targets":["mesh"],"requires":["rig.scaffold","mouth/fitted","face/legacy"],"contextVersion":2,
               "reads":["*"],"writes":["mesh","deformer","part","hierarchy"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val mouthDepthV9WorkerJar = registerIsolatedBindingWorker(
    "mouthDepthV9", "mouth-depth-v9", "worker-mouth-depth-v9", "io.github.psd2live.algorithms.binding.MouthDepthV9Worker",
)

tasks.register("pluginMouthDepthV9") {
    dependsOn(mouthDepthV9WorkerJar)
    val output = layout.buildDirectory.dir("plugin-mouth-depth-v9")
    val packageVersion = project.version.toString()
    inputs.file(mouthDepthV9WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", packageVersion)
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        mouthDepthV9WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.6d6f7574682e696e746572696f722d6465707468.v9"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"$packageVersion","name":"Layered mouth teeth depth",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/depth","aliases":["mouth.interior-depth"],"version":9,"name":"Layered mouth teeth depth","category":"mouth",
               "targets":["mesh"],"requires":["rig.scaffold","mouth/fitted","face/legacy"],"contextVersion":2,
               "reads":["*"],"writes":["mesh","deformer","part","hierarchy"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val mouthDepthV10WorkerJar = registerIsolatedBindingWorker(
    "mouthDepthV10", "mouth-depth-v10", "worker-mouth-depth-v10", "io.github.psd2live.algorithms.binding.MouthDepthV10Worker",
)

tasks.register("pluginMouthDepthV10") {
    dependsOn(mouthDepthV10WorkerJar)
    val output = layout.buildDirectory.dir("plugin-mouth-depth-v10")
    val packageVersion = project.version.toString()
    inputs.file(mouthDepthV10WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", packageVersion)
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        mouthDepthV10WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.6d6f7574682e696e746572696f722d6465707468.v10"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"$packageVersion","name":"Single-eye mouth interior depth with tongue",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/depth","aliases":["mouth.interior-depth"],"version":10,"name":"Single-eye mouth interior depth with tongue","category":"mouth",
               "targets":["mesh"],"requires":["rig.scaffold","mouth/fitted","face/legacy"],"contextVersion":2,
               "reads":["*"],"writes":["mesh","deformer","part","hierarchy"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val mouthDepthV11WorkerJar = registerIsolatedBindingWorker(
    "mouthDepthV11", "mouth-depth-v11", "worker-mouth-depth-v11", "io.github.psd2live.algorithms.binding.MouthDepthV11Worker",
)

tasks.register("pluginMouthDepthV11") {
    dependsOn(mouthDepthV11WorkerJar)
    val output = layout.buildDirectory.dir("plugin-mouth-depth-v11")
    val packageVersion = project.version.toString()
    inputs.file(mouthDepthV11WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", packageVersion)
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        mouthDepthV11WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.6d6f7574682e696e746572696f722d6465707468.v11"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"$packageVersion","name":"Single-eye mouth interior depth without tongue",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/depth","aliases":["mouth.interior-depth"],"version":11,"name":"Single-eye mouth interior depth without tongue","category":"mouth",
               "targets":["mesh"],"requires":["rig.scaffold","mouth/fitted","face/legacy"],"contextVersion":2,
               "reads":["*"],"writes":["mesh","deformer","part","hierarchy"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val mouthDepthV12WorkerJar = registerIsolatedBindingWorker(
    "mouthDepthV12", "mouth-depth-v12", "worker-mouth-depth-v12", "io.github.psd2live.algorithms.binding.MouthDepthV12Worker",
)

tasks.register("pluginMouthDepthV12") {
    dependsOn(mouthDepthV12WorkerJar)
    val output = layout.buildDirectory.dir("plugin-mouth-depth-v12")
    val packageVersion = project.version.toString()
    inputs.file(mouthDepthV12WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", packageVersion)
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        mouthDepthV12WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.6d6f7574682e696e746572696f722d6465707468.v12"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"$packageVersion","name":"Single-eye mouth interior depth without tongue",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/depth","aliases":["mouth.interior-depth"],"version":12,"name":"Single-eye mouth interior depth without tongue","category":"mouth",
               "targets":["mesh"],"requires":["rig.scaffold","mouth/fitted","face/legacy"],"contextVersion":2,
               "reads":["*"],"writes":["mesh","deformer","part","hierarchy"],
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

val cheekV3WorkerJar = registerIsolatedBindingWorker(
    "cheekV3", "cheek-v3", "worker-cheek-v3", "io.github.psd2live.algorithms.binding.CheekV3Worker",
)

tasks.register("pluginCheekV3") {
    dependsOn(cheekV3WorkerJar)
    val output = layout.buildDirectory.dir("plugin-cheek-v3")
    val packageVersion = project.version.toString()
    inputs.file(cheekV3WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", packageVersion)
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        cheekV3WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.65787072657373696f6e2e636865656b.v3"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"$packageVersion","name":"Cheek",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/color","aliases":["expression.cheek"],"version":3,"name":"Cheek","category":"expression",
               "targets":["mesh"],"requires":["rig.scaffold"],"contextVersion":2,
               "reads":["canvas","mesh","parameter","deformer","hierarchy"],"writes":["mesh","parameter","deformer","hierarchy"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter"],
               "parameters":[{"id":"ParamCheek","name":"Cheek","min":0,"max":1,"default":0}],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

// Immutable replay mechanics are opt-in, never part of main or historical worker bytes.
val skinReplayV1Sources = sourceSets.create("skinReplayV1")
kotlin.target.compilations.named("skinReplayV1") {
    associateWith(kotlin.target.compilations.getByName("main"))
}
val skinReplayV1ClassesJar = tasks.register<Jar>("skinReplayV1ClassesJar") {
    archiveClassifier.set("skin-replay-v1")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    from(skinReplayV1Sources.output)
}
val skinReplayV1Elements = configurations.create("skinReplayV1Elements") {
    isCanBeConsumed = true
    isCanBeResolved = false
}
artifacts.add(skinReplayV1Elements.name, skinReplayV1ClassesJar)

val cheekV4WorkerJar = registerIsolatedBindingWorker(
    "cheekV4", "cheek-v4", "worker-cheek-v4", "io.github.psd2live.algorithms.binding.CheekV4Worker",
)
kotlin.target.compilations.named("cheekV4") {
    associateWith(kotlin.target.compilations.getByName("skinReplayV1"))
}
cheekV4WorkerJar.configure { from(skinReplayV1Sources.output) }

tasks.register("pluginCheekV4") {
    dependsOn(cheekV4WorkerJar)
    val output = layout.buildDirectory.dir("plugin-cheek-v4")
    val packageVersion = project.version.toString()
    inputs.file(cheekV4WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", packageVersion)
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        cheekV4WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.65787072657373696f6e2e636865656b.v4"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"$packageVersion","name":"Cheek",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/color","aliases":["expression.cheek"],"version":4,"name":"Cheek","category":"expression",
               "targets":["mesh"],"contextVersion":2,
               "requires":["rig.scaffold","body/legacy","face/fitted","eye/fitted","brow/fitted","mouth/fitted","mouth.outline","mouth.vowel","mouth.capture","mouth.jaw","mouth.spring"],
               "reads":["*"],"writes":["mesh","deformer","parameter","hierarchy"],
               "parameters":[{"id":"ParamCheek","name":"Cheek","min":0,"max":1,"default":0}],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val cheekV5WorkerJar = registerIsolatedBindingWorker(
    "cheekV5", "cheek-v5", "worker-cheek-v5", "io.github.psd2live.algorithms.binding.CheekV5Worker",
)
kotlin.target.compilations.named("cheekV5") {
    associateWith(kotlin.target.compilations.getByName("skinReplayV1"))
}
cheekV5WorkerJar.configure { from(skinReplayV1Sources.output) }

tasks.register("pluginCheekV5") {
    dependsOn(cheekV5WorkerJar)
    val output = layout.buildDirectory.dir("plugin-cheek-v5")
    val packageVersion = project.version.toString()
    inputs.file(cheekV5WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", packageVersion)
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        cheekV5WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.65787072657373696f6e2e636865656b.v5"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"$packageVersion","name":"Cheek",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/color","aliases":["expression.cheek"],"version":5,"name":"Cheek","category":"expression",
               "targets":["mesh"],"contextVersion":2,
               "requires":["rig.scaffold","body/legacy","face/fitted","eye/fitted","brow/fitted","mouth/fitted","mouth.outline","mouth.vowel","mouth.capture","mouth.jaw","mouth.spring"],
               "reads":["*"],"writes":["mesh","deformer","parameter","hierarchy"],
               "parameters":[{"id":"ParamCheek","name":"Cheek","min":0,"max":1,"default":0}],
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

val gloomV4WorkerJar = registerIsolatedBindingWorker(
    "gloomV4", "gloom-v4", "worker-gloom-v4", "io.github.psd2live.algorithms.binding.GloomV4Worker",
)

tasks.register("pluginGloomV4") {
    dependsOn(gloomV4WorkerJar)
    val output = layout.buildDirectory.dir("plugin-gloom-v4")
    val packageVersion = project.version.toString()
    inputs.file(gloomV4WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", packageVersion)
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        gloomV4WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.65787072657373696f6e2e676c6f6f6d.v4"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"$packageVersion","name":"Gloom",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/gloom","aliases":["expression.gloom"],"version":4,"name":"Gloom","category":"expression",
               "targets":["mesh"],"contextVersion":2,
               "requires":["rig.scaffold","body/legacy","face/fitted","eye/fitted","brow/fitted","mouth/fitted","mouth.outline","mouth.vowel","mouth.capture","mouth.jaw","mouth.spring","expression.cheek"],
               "exclusiveFamily":"expression.gloom","reads":["*"],"writes":["mesh","deformer","parameter","hierarchy"],
               "parameters":[{"id":"ParamGloom","name":"Gloom","min":0,"max":1,"default":0}],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val gloomV5WorkerJar = registerIsolatedBindingWorker(
    "gloomV5", "gloom-v5", "worker-gloom-v5", "io.github.psd2live.algorithms.binding.GloomV5Worker",
)
kotlin.target.compilations.named("gloomV5") {
    associateWith(kotlin.target.compilations.getByName("skinReplayV1"))
}
gloomV5WorkerJar.configure { from(skinReplayV1Sources.output) }

tasks.register("pluginGloomV5") {
    dependsOn(gloomV5WorkerJar)
    val output = layout.buildDirectory.dir("plugin-gloom-v5")
    val packageVersion = project.version.toString()
    inputs.file(gloomV5WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", packageVersion)
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        gloomV5WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.65787072657373696f6e2e676c6f6f6d.v5"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"$packageVersion","name":"Gloom",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/gloom","aliases":["expression.gloom"],"version":5,"name":"Gloom","category":"expression",
               "targets":["mesh"],"contextVersion":2,
               "requires":["rig.scaffold","body/legacy","face/fitted","eye/fitted","brow/fitted","mouth/fitted","mouth.outline","mouth.vowel","mouth.capture","mouth.jaw","mouth.spring","expression.cheek"],
               "exclusiveFamily":"expression.gloom","reads":["*"],"writes":["mesh","deformer","parameter","hierarchy"],
               "parameters":[{"id":"ParamGloom","name":"Gloom","min":0,"max":1,"default":0}],
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

val mouthOutlineV5WorkerJar = registerIsolatedBindingWorker(
    "mouthOutlineV5", "mouth-outline-v5", "worker-mouth-outline-v5", "io.github.psd2live.algorithms.binding.MouthOutlineV5Worker",
)
kotlin.target.compilations.named("mouthOutlineV5") {
    associateWith(kotlin.target.compilations.getByName("mouthOutlineV3"))
}
mouthOutlineV5WorkerJar.configure {
    from(sourceSets["mouthOutlineV3"].output)
}

tasks.register("pluginMouthOutlineV5") {
    dependsOn(mouthOutlineV5WorkerJar)
    val output = layout.buildDirectory.dir("plugin-mouth-outline-v5")
    inputs.file(mouthOutlineV5WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", project.version.toString())
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        mouthOutlineV5WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.6d6f7574682e6f75746c696e65.v5"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"${project.version}","name":"Fitted mouth outline with a closed drawing",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/outline","aliases":["mouth.outline"],"version":5,"contextVersion":2,"name":"Fitted mouth outline with a closed drawing","category":"mouth",
               "targets":["mesh"],"requires":["rig.scaffold","mouth/fitted"],"exclusiveFamily":"mouth.outline",
               "reads":["*"],"writes":["mesh","deformer","part","hierarchy","textures","authoring"],
               "composableKinds":["mesh","deformer","part","hierarchy"],"configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val mouthOutlineV6WorkerJar = registerIsolatedBindingWorker(
    "mouthOutlineV6", "mouth-outline-v6", "worker-mouth-outline-v6", "io.github.psd2live.algorithms.binding.MouthOutlineV6Worker",
)
kotlin.target.compilations.named("mouthOutlineV6") {
    associateWith(kotlin.target.compilations.getByName("mouthOutlineV3"))
}
mouthOutlineV6WorkerJar.configure {
    from(sourceSets["mouthOutlineV3"].output)
}

tasks.register("pluginMouthOutlineV6") {
    dependsOn(mouthOutlineV6WorkerJar)
    val output = layout.buildDirectory.dir("plugin-mouth-outline-v6")
    inputs.file(mouthOutlineV6WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", project.version.toString())
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        mouthOutlineV6WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.6d6f7574682e6f75746c696e65.v6"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"${project.version}","name":"Fitted mouth outline with a closed drawing",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/outline","aliases":["mouth.outline"],"version":6,"contextVersion":2,"name":"Fitted mouth outline with a closed drawing","category":"mouth",
               "targets":["mesh"],"requires":["rig.scaffold","mouth/fitted"],"exclusiveFamily":"mouth.outline",
               "reads":["*"],"writes":["mesh","deformer","part","hierarchy","textures","authoring"],
               "composableKinds":["mesh","deformer","part","hierarchy"],"configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val mouthOutlineV7WorkerJar = registerIsolatedBindingWorker(
    "mouthOutlineV7", "mouth-outline-v7", "worker-mouth-outline-v7", "io.github.psd2live.algorithms.binding.MouthOutlineV7Worker",
)
kotlin.target.compilations.named("mouthOutlineV7") {
    associateWith(kotlin.target.compilations.getByName("mouthOutlineV3"))
}
mouthOutlineV7WorkerJar.configure {
    from(sourceSets["mouthOutlineV3"].output)
    from(sourceSets.main.get().output) { include(neckWorkerClasses) }
}

tasks.register("pluginMouthOutlineV7") {
    dependsOn(mouthOutlineV7WorkerJar)
    val output = layout.buildDirectory.dir("plugin-mouth-outline-v7")
    inputs.file(mouthOutlineV7WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", project.version.toString())
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        mouthOutlineV7WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.6d6f7574682e6f75746c696e65.v7"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"${project.version}","name":"Fitted mouth outline",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/outline","aliases":["mouth.outline"],"version":7,"contextVersion":2,"name":"Fitted mouth outline","category":"mouth",
               "targets":["mesh"],"requires":["rig.scaffold","mouth/fitted"],"exclusiveFamily":"mouth.outline",
               "reads":["*"],"writes":["mesh","deformer","part","hierarchy","textures","authoring"],
               "composableKinds":["mesh","deformer","part","hierarchy"],"configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}


val mouthOutlineV8WorkerJar = registerIsolatedBindingWorker(
    "mouthOutlineV8", "mouth-outline-v8", "worker-mouth-outline-v8", "io.github.psd2live.algorithms.binding.MouthOutlineV8Worker",
)

tasks.register("pluginMouthOutlineV8") {
    dependsOn(mouthOutlineV8WorkerJar)
    val output = layout.buildDirectory.dir("plugin-mouth-outline-v8")
    inputs.file(mouthOutlineV8WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", project.version.toString())
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        mouthOutlineV8WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.6d6f7574682e6f75746c696e65.v8"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"${project.version}","name":"Fitted mouth outline",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/outline","aliases":["mouth.outline"],"version":8,"contextVersion":2,"name":"Fitted mouth outline","category":"mouth",
               "targets":["mesh"],"requires":["rig.scaffold","mouth/fitted"],"exclusiveFamily":"mouth.outline",
               "reads":["*"],"writes":["mesh","deformer","part","hierarchy","textures","authoring"],
               "composableKinds":["mesh","deformer","part","hierarchy"],"configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val mouthOutlineV10WorkerJar = registerIsolatedBindingWorker(
    "mouthOutlineV10", "mouth-outline-v10", "worker-mouth-outline-v10", "io.github.psd2live.algorithms.binding.MouthOutlineV10Worker",
)

tasks.register("pluginMouthOutlineV10") {
    dependsOn(mouthOutlineV10WorkerJar)
    val output = layout.buildDirectory.dir("plugin-mouth-outline-v10")
    inputs.file(mouthOutlineV10WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", project.version.toString())
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        mouthOutlineV10WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.6d6f7574682e6f75746c696e65.v10"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"${project.version}","name":"Fitted mouth outline",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/outline","aliases":["mouth.outline"],"version":10,"contextVersion":2,"name":"Fitted mouth outline","category":"mouth",
               "targets":["mesh"],"requires":["rig.scaffold","mouth/fitted"],"exclusiveFamily":"mouth.outline",
               "reads":["*"],"writes":["mesh","deformer","part","hierarchy","textures","authoring"],
               "composableKinds":["mesh","deformer","part","hierarchy"],"configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val mouthOutlineV11WorkerJar = registerIsolatedBindingWorker(
    "mouthOutlineV11", "mouth-outline-v11", "worker-mouth-outline-v11", "io.github.psd2live.algorithms.binding.MouthOutlineV11Worker",
)

tasks.register("pluginMouthOutlineV11") {
    dependsOn(mouthOutlineV11WorkerJar)
    val output = layout.buildDirectory.dir("plugin-mouth-outline-v11")
    inputs.file(mouthOutlineV11WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", project.version.toString())
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        mouthOutlineV11WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.6d6f7574682e6f75746c696e65.v11"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"${project.version}","name":"Layered mouth outline",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/outline","aliases":["mouth.outline"],"version":11,"contextVersion":2,"name":"Layered mouth outline","category":"mouth",
               "targets":["mesh"],"requires":["rig.scaffold","mouth/fitted"],"exclusiveFamily":"mouth.outline",
               "reads":["*"],"writes":["mesh","deformer","part","hierarchy"],
               "composableKinds":["mesh","deformer","part","hierarchy"],"configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val mouthOutlineV9WorkerJar = registerIsolatedBindingWorker(
    "mouthOutlineV9", "mouth-outline-v9", "worker-mouth-outline-v9", "io.github.psd2live.algorithms.binding.MouthOutlineV9Worker",
)

tasks.register("pluginMouthOutlineV9") {
    dependsOn(mouthOutlineV9WorkerJar)
    val output = layout.buildDirectory.dir("plugin-mouth-outline-v9")
    inputs.file(mouthOutlineV9WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", project.version.toString())
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        mouthOutlineV9WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.6d6f7574682e6f75746c696e65.v9"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"${project.version}","name":"Layered mouth outline",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/outline","aliases":["mouth.outline"],"version":9,"contextVersion":2,"name":"Layered mouth outline","category":"mouth",
               "targets":["mesh"],"requires":["rig.scaffold","mouth/fitted"],"exclusiveFamily":"mouth.outline",
               "reads":["*"],"writes":["mesh","deformer","part","hierarchy"],
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

val mouthFittedV4WorkerJar = registerIsolatedBindingWorker(
    "mouthFittedV4", "mouth-fitted-v4", "worker-mouth-fitted-v4", "io.github.psd2live.algorithms.binding.MouthFittedV4Worker",
)
kotlin.target.compilations.named("mouthFittedV4") {
    associateWith(kotlin.target.compilations.getByName("mouthFittedV3"))
}
mouthFittedV4WorkerJar.configure {
    from(sourceSets["mouthFittedV3"].output)
}

tasks.register("pluginMouthFittedV4") {
    dependsOn(mouthFittedV4WorkerJar)
    val output = layout.buildDirectory.dir("plugin-mouth-fitted-v4")
    inputs.file(mouthFittedV4WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", project.version.toString())
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        mouthFittedV4WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.6d6f7574682f666974746564.v4"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"${project.version}","name":"Fitted mouth with a closed drawing",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/binding","aliases":["mouth/fitted"],"version":4,"contextVersion":2,"name":"Fitted mouth with a closed drawing","category":"mouth",
               "targets":["mesh"],"requires":["rig.scaffold","face/fitted"],"exclusiveFamily":"base:mouth",
               "reads":["*"],"writes":["mesh","parameter","deformer","hierarchy","relations","rest_pose"],"composableKinds":["mesh","parameter","deformer","hierarchy","relations"],
               "parameters":[{"id":"ParamMouthForm","name":"Mouth Form","min":-1,"max":1,"default":0},{"id":"ParamMouthOpenY","name":"Mouth Open","min":0,"max":1,"default":0}],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val mouthFittedV5WorkerJar = registerIsolatedBindingWorker(
    "mouthFittedV5", "mouth-fitted-v5", "worker-mouth-fitted-v5", "io.github.psd2live.algorithms.binding.MouthFittedV5Worker",
)
kotlin.target.compilations.named("mouthFittedV5") {
    associateWith(kotlin.target.compilations.getByName("mouthFittedV4"))
}
mouthFittedV5WorkerJar.configure {
    from(sourceSets["mouthFittedV3"].output)
    from(sourceSets["mouthFittedV4"].output)
}

tasks.register("pluginMouthFittedV5") {
    dependsOn(mouthFittedV5WorkerJar)
    val output = layout.buildDirectory.dir("plugin-mouth-fitted-v5")
    inputs.file(mouthFittedV5WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", project.version.toString())
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        mouthFittedV5WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.6d6f7574682f666974746564.v5"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"${project.version}","name":"Fitted mouth with a closed drawing",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/binding","aliases":["mouth/fitted"],"version":5,"contextVersion":2,"name":"Fitted mouth with a closed drawing","category":"mouth",
               "targets":["mesh"],"requires":["rig.scaffold","face/fitted"],"exclusiveFamily":"base:mouth",
               "reads":["*"],"writes":["mesh","parameter","deformer","hierarchy","relations","rest_pose"],"composableKinds":["mesh","parameter","deformer","hierarchy","relations"],
               "parameters":[{"id":"ParamMouthForm","name":"Mouth Form","min":-1,"max":1,"default":0},{"id":"ParamMouthOpenY","name":"Mouth Open","min":0,"max":1,"default":0}],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val mouthFittedV6WorkerJar = registerIsolatedBindingWorker(
    "mouthFittedV6", "mouth-fitted-v6", "worker-mouth-fitted-v6", "io.github.psd2live.algorithms.binding.MouthFittedV6Worker",
)
tasks.register("pluginMouthFittedV6") {
    dependsOn(mouthFittedV6WorkerJar)
    val output = layout.buildDirectory.dir("plugin-mouth-fitted-v6")
    inputs.file(mouthFittedV6WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", project.version.toString())
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        mouthFittedV6WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.6d6f7574682f666974746564.v6"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"${project.version}","name":"Fitted mouth in shared head frames",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/binding","aliases":["mouth/fitted"],"version":6,"contextVersion":2,"name":"Fitted mouth in shared head frames","category":"mouth",
               "targets":["mesh"],"requires":["rig.scaffold","face/fitted"],"exclusiveFamily":"base:mouth",
               "reads":["*"],"writes":["mesh","parameter","deformer","part","hierarchy","rest_pose"],"composableKinds":["mesh","parameter","deformer","part","hierarchy","relations"],
               "parameters":[{"id":"ParamMouthForm","name":"Mouth Form","min":-1,"max":1,"default":0},{"id":"ParamMouthOpenY","name":"Mouth Open","min":0,"max":1,"default":0}],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val mouthFittedV8WorkerJar = registerIsolatedBindingWorker(
    "mouthFittedV8", "mouth-fitted-v8", "worker-mouth-fitted-v8", "io.github.psd2live.algorithms.binding.MouthFittedV8Worker",
)
tasks.register("pluginMouthFittedV8") {
    dependsOn(mouthFittedV8WorkerJar)
    val output = layout.buildDirectory.dir("plugin-mouth-fitted-v8")
    inputs.file(mouthFittedV8WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", project.version.toString())
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        mouthFittedV8WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.6d6f7574682f666974746564.v8"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"${project.version}","name":"Layered mouth in shared head frames",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/binding","aliases":["mouth/fitted"],"version":8,"contextVersion":2,"name":"Layered mouth in shared head frames","category":"mouth",
               "targets":["mesh"],"requires":["rig.scaffold","face/fitted"],"exclusiveFamily":"base:mouth",
               "reads":["*"],"writes":["mesh","parameter","deformer","part","hierarchy","rest_pose"],"composableKinds":["mesh","parameter","deformer","part","hierarchy","relations"],
               "parameters":[{"id":"ParamMouthForm","name":"Mouth Form","min":-1,"max":1,"default":0},{"id":"ParamMouthOpenY","name":"Mouth Open","min":0,"max":1,"default":0}],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val mouthFittedV7WorkerJar = registerIsolatedBindingWorker(
    "mouthFittedV7", "mouth-fitted-v7", "worker-mouth-fitted-v7", "io.github.psd2live.algorithms.binding.MouthFittedV7Worker",
)
tasks.register("pluginMouthFittedV7") {
    dependsOn(mouthFittedV7WorkerJar)
    val output = layout.buildDirectory.dir("plugin-mouth-fitted-v7")
    inputs.file(mouthFittedV7WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", project.version.toString())
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        mouthFittedV7WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.6d6f7574682f666974746564.v7"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"${project.version}","name":"Fitted mouth in fallback head frames",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/binding","aliases":["mouth/fitted"],"version":7,"contextVersion":2,"name":"Fitted mouth in fallback head frames","category":"mouth",
               "targets":["mesh"],"requires":["rig.scaffold","face/fitted"],"exclusiveFamily":"base:mouth",
               "reads":["*"],"writes":["mesh","parameter","deformer","part","hierarchy","rest_pose"],"composableKinds":["mesh","parameter","deformer","part","hierarchy","relations"],
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

val mouthVowelV5WorkerJar = registerIsolatedBindingWorker(
    "mouthVowelV5", "mouth-vowel-v5", "worker-mouth-vowel-v5", "io.github.psd2live.algorithms.binding.MouthVowelV5Worker",
)
kotlin.target.compilations.named("mouthVowelV5") {
    associateWith(kotlin.target.compilations.getByName("mouthVowel"))
}
mouthVowelV5WorkerJar.configure {
    from(sourceSets["mouthVowel"].output)
}

tasks.register("pluginMouthVowelV5") {
    dependsOn(mouthVowelV5WorkerJar)
    val output = layout.buildDirectory.dir("plugin-mouth-vowel-v5")
    inputs.file(mouthVowelV5WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", project.version.toString())
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        mouthVowelV5WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.6d6f7574682e766f77656c.v5"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"${project.version}","name":"Mouth vowels with a closed drawing",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/vowel","aliases":["mouth.vowel"],"version":5,"name":"Mouth vowels with a closed drawing","category":"mouth",
               "targets":["mesh"],"requires":["rig.scaffold","mouth/fitted"],"contextVersion":2,
               "reads":["canvas","mesh","parameter","hierarchy"],"writes":["mesh","parameter","hierarchy"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val mouthVowelV6WorkerJar = registerIsolatedBindingWorker(
    "mouthVowelV6", "mouth-vowel-v6", "worker-mouth-vowel-v6", "io.github.psd2live.algorithms.binding.MouthVowelV6Worker",
)
kotlin.target.compilations.named("mouthVowelV6") {
    associateWith(kotlin.target.compilations.getByName("mouthVowel"))
}
mouthVowelV6WorkerJar.configure {
    from(sourceSets["mouthVowel"].output)
}

tasks.register("pluginMouthVowelV6") {
    dependsOn(mouthVowelV6WorkerJar)
    val output = layout.buildDirectory.dir("plugin-mouth-vowel-v6")
    inputs.file(mouthVowelV6WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", project.version.toString())
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        mouthVowelV6WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.6d6f7574682e766f77656c.v6"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"${project.version}","name":"Mouth vowels after a fitted outline",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/vowel","aliases":["mouth.vowel"],"version":6,"name":"Mouth vowels after a fitted outline","category":"mouth",
               "targets":["mesh"],"requires":["rig.scaffold","mouth/fitted"],"contextVersion":2,
               "reads":["canvas","mesh","parameter","hierarchy"],"writes":["mesh","parameter","hierarchy"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val mouthVowelV7WorkerJar = registerIsolatedBindingWorker(
    "mouthVowelV7", "mouth-vowel-v7", "worker-mouth-vowel-v7", "io.github.psd2live.algorithms.binding.MouthVowelV7Worker",
)
tasks.register("pluginMouthVowelV7") {
    dependsOn(mouthVowelV7WorkerJar)
    val output = layout.buildDirectory.dir("plugin-mouth-vowel-v7")
    inputs.file(mouthVowelV7WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", project.version.toString())
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        mouthVowelV7WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.6d6f7574682e766f77656c.v7"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"${project.version}","name":"Mouth vowels in shared head frames",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/vowel","aliases":["mouth.vowel"],"version":7,"name":"Mouth vowels in shared head frames","category":"mouth",
               "targets":["mesh"],"requires":["rig.scaffold","mouth/fitted"],"contextVersion":2,
               "reads":["*"],"writes":["mesh","parameter","hierarchy"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val mouthVowelV9WorkerJar = registerIsolatedBindingWorker(
    "mouthVowelV9", "mouth-vowel-v9", "worker-mouth-vowel-v9", "io.github.psd2live.algorithms.binding.MouthVowelV9Worker",
)
tasks.register("pluginMouthVowelV9") {
    dependsOn(mouthVowelV9WorkerJar)
    val output = layout.buildDirectory.dir("plugin-mouth-vowel-v9")
    inputs.file(mouthVowelV9WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", project.version.toString())
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        mouthVowelV9WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.6d6f7574682e766f77656c.v9"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"${project.version}","name":"Mouth vowels in shared head frames",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/vowel","aliases":["mouth.vowel"],"version":9,"name":"Mouth vowels in shared head frames","category":"mouth",
               "targets":["mesh"],"requires":["rig.scaffold","mouth/fitted"],"contextVersion":2,
               "reads":["*"],"writes":["mesh","parameter","hierarchy"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val mouthVowelV8WorkerJar = registerIsolatedBindingWorker(
    "mouthVowelV8", "mouth-vowel-v8", "worker-mouth-vowel-v8", "io.github.psd2live.algorithms.binding.MouthVowelV8Worker",
)
tasks.register("pluginMouthVowelV8") {
    dependsOn(mouthVowelV8WorkerJar)
    val output = layout.buildDirectory.dir("plugin-mouth-vowel-v8")
    inputs.file(mouthVowelV8WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", project.version.toString())
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        mouthVowelV8WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.6d6f7574682e766f77656c.v8"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"${project.version}","name":"Layered mouth vowels",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/vowel","aliases":["mouth.vowel"],"version":8,"name":"Layered mouth vowels","category":"mouth",
               "targets":["mesh"],"requires":["rig.scaffold","mouth/fitted"],"contextVersion":2,
               "reads":["*"],"writes":["mesh","parameter","hierarchy"],
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

val mouthCaptureV4WorkerJar = registerIsolatedBindingWorker(
    "mouthCaptureV4", "mouth-capture-v4", "worker-mouth-capture-v4", "io.github.psd2live.algorithms.binding.MouthCaptureV4Worker",
)
kotlin.target.compilations.named("mouthCaptureV4") {
    associateWith(kotlin.target.compilations.getByName("mouthCapture"))
}
mouthCaptureV4WorkerJar.configure {
    from(sourceSets["mouthCapture"].output)
}

tasks.register("pluginMouthCaptureV4") {
    dependsOn(mouthCaptureV4WorkerJar)
    val output = layout.buildDirectory.dir("plugin-mouth-capture-v4")
    inputs.file(mouthCaptureV4WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", project.version.toString())
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        mouthCaptureV4WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.6d6f7574682e63617074757265.v4"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"${project.version}","name":"Mouth capture with a closed drawing",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/capture","aliases":["mouth.capture"],"version":4,"name":"Mouth capture with a closed drawing","category":"mouth",
               "targets":["mesh"],"requires":["rig.scaffold","mouth/fitted"],"contextVersion":2,
               "reads":["canvas","mesh","parameter","hierarchy"],"writes":["mesh","parameter","hierarchy"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val mouthCaptureV5WorkerJar = registerIsolatedBindingWorker(
    "mouthCaptureV5", "mouth-capture-v5", "worker-mouth-capture-v5", "io.github.psd2live.algorithms.binding.MouthCaptureV5Worker",
)
kotlin.target.compilations.named("mouthCaptureV5") {
    associateWith(kotlin.target.compilations.getByName("mouthCapture"))
}
mouthCaptureV5WorkerJar.configure {
    from(sourceSets["mouthCapture"].output)
}

tasks.register("pluginMouthCaptureV5") {
    dependsOn(mouthCaptureV5WorkerJar)
    val output = layout.buildDirectory.dir("plugin-mouth-capture-v5")
    inputs.file(mouthCaptureV5WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", project.version.toString())
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        mouthCaptureV5WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.6d6f7574682e63617074757265.v5"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"${project.version}","name":"Mouth capture",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/capture","aliases":["mouth.capture"],"version":5,"name":"Mouth capture","category":"mouth",
               "targets":["mesh"],"requires":["rig.scaffold","mouth/fitted"],"contextVersion":2,
               "reads":["canvas","mesh","parameter","hierarchy"],"writes":["mesh","parameter","hierarchy"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val mouthCaptureV6WorkerJar = registerIsolatedBindingWorker(
    "mouthCaptureV6", "mouth-capture-v6", "worker-mouth-capture-v6", "io.github.psd2live.algorithms.binding.MouthCaptureV6Worker",
)
tasks.register("pluginMouthCaptureV6") {
    dependsOn(mouthCaptureV6WorkerJar)
    val output = layout.buildDirectory.dir("plugin-mouth-capture-v6")
    inputs.file(mouthCaptureV6WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", project.version.toString())
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        mouthCaptureV6WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.6d6f7574682e63617074757265.v6"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"${project.version}","name":"Mouth capture",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/capture","aliases":["mouth.capture"],"version":6,"name":"Mouth capture","category":"mouth",
               "targets":["mesh"],"requires":["rig.scaffold","mouth/fitted"],"contextVersion":2,
               "reads":["*"],"writes":["mesh","parameter","hierarchy"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val mouthCaptureV8WorkerJar = registerIsolatedBindingWorker(
    "mouthCaptureV8", "mouth-capture-v8", "worker-mouth-capture-v8", "io.github.psd2live.algorithms.binding.MouthCaptureV8Worker",
)
tasks.register("pluginMouthCaptureV8") {
    dependsOn(mouthCaptureV8WorkerJar)
    val output = layout.buildDirectory.dir("plugin-mouth-capture-v8")
    inputs.file(mouthCaptureV8WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", project.version.toString())
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        mouthCaptureV8WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.6d6f7574682e63617074757265.v8"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"${project.version}","name":"Mouth capture",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/capture","aliases":["mouth.capture"],"version":8,"name":"Mouth capture","category":"mouth",
               "targets":["mesh"],"requires":["rig.scaffold","mouth/fitted"],"contextVersion":2,
               "reads":["*"],"writes":["mesh","parameter","hierarchy"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val mouthCaptureV7WorkerJar = registerIsolatedBindingWorker(
    "mouthCaptureV7", "mouth-capture-v7", "worker-mouth-capture-v7", "io.github.psd2live.algorithms.binding.MouthCaptureV7Worker",
)
tasks.register("pluginMouthCaptureV7") {
    dependsOn(mouthCaptureV7WorkerJar)
    val output = layout.buildDirectory.dir("plugin-mouth-capture-v7")
    inputs.file(mouthCaptureV7WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", project.version.toString())
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        mouthCaptureV7WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.6d6f7574682e63617074757265.v7"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"${project.version}","name":"Layered mouth capture",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/capture","aliases":["mouth.capture"],"version":7,"name":"Layered mouth capture","category":"mouth",
               "targets":["mesh"],"requires":["rig.scaffold","mouth/fitted"],"contextVersion":2,
               "reads":["*"],"writes":["mesh","parameter","hierarchy"],
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

val mouthCheekLidV5WorkerJar = registerIsolatedBindingWorker(
    "mouthCheekLidV5", "mouth-cheek-lid-v5", "worker-mouth-cheek-lid-v5", "io.github.psd2live.algorithms.binding.MouthCheekLidV5Worker",
)

kotlin.target.compilations.named("mouthCheekLidV5") {
    associateWith(kotlin.target.compilations.getByName("eyeSmile"))
}
mouthCheekLidV5WorkerJar.configure {
    from(sourceSets["eyeSmile"].output) { include("**/EyeSmileInputs*.class") }
}

tasks.register("pluginMouthCheekLidV5") {
    dependsOn(mouthCheekLidV5WorkerJar)
    val output = layout.buildDirectory.dir("plugin-mouth-cheek-lid-v5")
    val packageVersion = project.version.toString()
    inputs.file(mouthCheekLidV5WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", packageVersion)
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        mouthCheekLidV5WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.6d6f7574682e636865656b2d6c6964.v5"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"$packageVersion","name":"Cheeks lift lids",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/coupling","aliases":["mouth.cheek-lid"],"version":5,"name":"Cheeks lift lids","category":"mouth",
               "targets":["mesh"],"requires":["rig.scaffold","mouth/fitted","eye/fitted"],"contextVersion":2,
               "reads":["mesh","parameter","canvas","deformer"],"writes":["mesh","deformer"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val mouthCheekLidV6WorkerJar = registerIsolatedBindingWorker(
    "mouthCheekLidV6", "mouth-cheek-lid-v6", "worker-mouth-cheek-lid-v6", "io.github.psd2live.algorithms.binding.MouthCheekLidV6Worker",
)

kotlin.target.compilations.named("mouthCheekLidV6") {
    associateWith(kotlin.target.compilations.getByName("eyeSmile"))
}
mouthCheekLidV6WorkerJar.configure {
    from(sourceSets["eyeSmile"].output) { include("**/EyeSmileInputs*.class") }
}

tasks.register("pluginMouthCheekLidV6") {
    dependsOn(mouthCheekLidV6WorkerJar)
    val output = layout.buildDirectory.dir("plugin-mouth-cheek-lid-v6")
    val packageVersion = project.version.toString()
    inputs.file(mouthCheekLidV6WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", packageVersion)
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        mouthCheekLidV6WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.6d6f7574682e636865656b2d6c6964.v6"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"$packageVersion","name":"Cheeks lift lids",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/coupling","aliases":["mouth.cheek-lid"],"version":6,"name":"Cheeks lift lids","category":"mouth",
               "targets":["mesh"],"requires":["rig.scaffold","mouth/fitted","eye/fitted"],"contextVersion":2,
               "reads":["mesh","parameter","canvas","deformer"],"writes":["mesh","deformer"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val mouthCheekLidV7WorkerJar = registerIsolatedBindingWorker(
    "mouthCheekLidV7", "mouth-cheek-lid-v7", "worker-mouth-cheek-lid-v7", "io.github.psd2live.algorithms.binding.MouthCheekLidV7Worker",
)

kotlin.target.compilations.named("mouthCheekLidV7") {
    associateWith(kotlin.target.compilations.getByName("eyeSmile"))
}
mouthCheekLidV7WorkerJar.configure {
    from(sourceSets["eyeSmile"].output) { include("**/EyeSmileInputs*.class") }
}

tasks.register("pluginMouthCheekLidV7") {
    dependsOn(mouthCheekLidV7WorkerJar)
    val output = layout.buildDirectory.dir("plugin-mouth-cheek-lid-v7")
    val packageVersion = project.version.toString()
    inputs.file(mouthCheekLidV7WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", packageVersion)
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        mouthCheekLidV7WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.6d6f7574682e636865656b2d6c6964.v7"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"$packageVersion","name":"Cheeks lift lids",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/coupling","aliases":["mouth.cheek-lid"],"version":7,"name":"Cheeks lift lids","category":"mouth",
               "targets":["mesh"],"requires":["rig.scaffold","mouth/fitted","eye/fitted"],"contextVersion":2,
               "reads":["mesh","parameter","canvas","deformer"],"writes":["mesh","deformer"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val mouthCheekLidV8WorkerJar = registerIsolatedBindingWorker(
    "mouthCheekLidV8", "mouth-cheek-lid-v8", "worker-mouth-cheek-lid-v8", "io.github.psd2live.algorithms.binding.MouthCheekLidV8Worker",
)

tasks.register("pluginMouthCheekLidV8") {
    dependsOn(mouthCheekLidV8WorkerJar)
    val output = layout.buildDirectory.dir("plugin-mouth-cheek-lid-v8")
    val packageVersion = project.version.toString()
    inputs.file(mouthCheekLidV8WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", packageVersion)
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        mouthCheekLidV8WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.6d6f7574682e636865656b2d6c6964.v8"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"$packageVersion","name":"Cheeks lift lids",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/coupling","aliases":["mouth.cheek-lid"],"version":8,"name":"Cheeks lift lids","category":"mouth",
               "targets":["mesh"],"requires":["rig.scaffold","mouth/fitted","eye/fitted"],"contextVersion":2,
               "reads":["*"],"writes":["mesh","deformer","part","hierarchy"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val mouthCheekLidV11WorkerJar = registerIsolatedBindingWorker(
    "mouthCheekLidV11", "mouth-cheek-lid-v11", "worker-mouth-cheek-lid-v11", "io.github.psd2live.algorithms.binding.MouthCheekLidV11Worker",
)

tasks.register("pluginMouthCheekLidV11") {
    dependsOn(mouthCheekLidV11WorkerJar)
    val output = layout.buildDirectory.dir("plugin-mouth-cheek-lid-v11")
    val packageVersion = project.version.toString()
    inputs.file(mouthCheekLidV11WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", packageVersion)
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        mouthCheekLidV11WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.6d6f7574682e636865656b2d6c6964.v11"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"$packageVersion","name":"Cheeks lift lids",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/coupling","aliases":["mouth.cheek-lid"],"version":11,"name":"Cheeks lift lids","category":"mouth",
               "targets":["mesh"],"requires":["rig.scaffold","mouth/fitted","eye/fitted"],"contextVersion":2,
               "reads":["*"],"writes":["mesh","deformer","part","hierarchy"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val mouthCheekLidV15WorkerJar = registerIsolatedBindingWorker(
    "mouthCheekLidV15", "mouth-cheek-lid-v15", "worker-mouth-cheek-lid-v15", "io.github.psd2live.algorithms.binding.MouthCheekLidV15Worker",
)

tasks.register("pluginMouthCheekLidV15") {
    dependsOn(mouthCheekLidV15WorkerJar)
    val output = layout.buildDirectory.dir("plugin-mouth-cheek-lid-v15")
    val packageVersion = project.version.toString()
    inputs.file(mouthCheekLidV15WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", packageVersion)
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        mouthCheekLidV15WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.6d6f7574682e636865656b2d6c6964.v15"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"$packageVersion","name":"Cheeks lift lids",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/coupling","aliases":["mouth.cheek-lid"],"version":15,"name":"Cheeks lift lids","category":"mouth",
               "targets":["mesh"],"requires":["rig.scaffold","mouth/fitted","eye/fitted"],"contextVersion":2,
               "reads":["*"],"writes":["mesh","deformer","part","hierarchy"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val mouthCheekLidV9WorkerJar = registerIsolatedBindingWorker(
    "mouthCheekLidV9", "mouth-cheek-lid-v9", "worker-mouth-cheek-lid-v9", "io.github.psd2live.algorithms.binding.MouthCheekLidV9Worker",
)

kotlin.target.compilations.named("mouthCheekLidV9") {
    associateWith(kotlin.target.compilations.getByName("eyeSmile"))
}
mouthCheekLidV9WorkerJar.configure {
    from(sourceSets["eyeSmile"].output) { include("**/EyeSmileInputs*.class") }
}

tasks.register("pluginMouthCheekLidV9") {
    dependsOn(mouthCheekLidV9WorkerJar)
    val output = layout.buildDirectory.dir("plugin-mouth-cheek-lid-v9")
    val packageVersion = project.version.toString()
    inputs.file(mouthCheekLidV9WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", packageVersion)
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        mouthCheekLidV9WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.6d6f7574682e636865656b2d6c6964.v9"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"$packageVersion","name":"Cheeks lift lids",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/coupling","aliases":["mouth.cheek-lid"],"version":9,"name":"Cheeks lift lids","category":"mouth",
               "targets":["mesh"],"requires":["rig.scaffold","mouth/fitted","eye/fitted","face/fitted","brow/fitted"],"contextVersion":2,
               "reads":["mesh","parameter","canvas","deformer"],"writes":["mesh","deformer"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val mouthCheekLidV10WorkerJar = registerIsolatedBindingWorker(
    "mouthCheekLidV10", "mouth-cheek-lid-v10", "worker-mouth-cheek-lid-v10", "io.github.psd2live.algorithms.binding.MouthCheekLidV10Worker",
)

tasks.register("pluginMouthCheekLidV10") {
    dependsOn(mouthCheekLidV10WorkerJar)
    val output = layout.buildDirectory.dir("plugin-mouth-cheek-lid-v10")
    val packageVersion = project.version.toString()
    inputs.file(mouthCheekLidV10WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", packageVersion)
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        mouthCheekLidV10WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.6d6f7574682e636865656b2d6c6964.v10"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"$packageVersion","name":"Cheeks lift lids",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/coupling","aliases":["mouth.cheek-lid"],"version":10,"name":"Cheeks lift lids","category":"mouth",
               "targets":["mesh"],"requires":["rig.scaffold","mouth/fitted","eye/fitted"],"contextVersion":2,
               "reads":["*"],"writes":["mesh","deformer","part","hierarchy"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val mouthCheekLidV12WorkerJar = registerIsolatedBindingWorker(
    "mouthCheekLidV12", "mouth-cheek-lid-v12", "worker-mouth-cheek-lid-v12", "io.github.psd2live.algorithms.binding.MouthCheekLidV12Worker",
)

tasks.register("pluginMouthCheekLidV12") {
    dependsOn(mouthCheekLidV12WorkerJar)
    val output = layout.buildDirectory.dir("plugin-mouth-cheek-lid-v12")
    val packageVersion = project.version.toString()
    inputs.file(mouthCheekLidV12WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", packageVersion)
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        mouthCheekLidV12WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.6d6f7574682e636865656b2d6c6964.v12"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"$packageVersion","name":"Cheeks lift lids",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/coupling","aliases":["mouth.cheek-lid"],"version":12,"name":"Cheeks lift lids","category":"mouth",
               "targets":["mesh"],"requires":["rig.scaffold","mouth/fitted","eye/fitted"],"contextVersion":2,
               "reads":["*"],"writes":["mesh","deformer","part","hierarchy"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val mouthCheekLidV13WorkerJar = registerIsolatedBindingWorker(
    "mouthCheekLidV13", "mouth-cheek-lid-v13", "worker-mouth-cheek-lid-v13", "io.github.psd2live.algorithms.binding.MouthCheekLidV13Worker",
)

tasks.register("pluginMouthCheekLidV13") {
    dependsOn(mouthCheekLidV13WorkerJar)
    val output = layout.buildDirectory.dir("plugin-mouth-cheek-lid-v13")
    val packageVersion = project.version.toString()
    inputs.file(mouthCheekLidV13WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", packageVersion)
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        mouthCheekLidV13WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.6d6f7574682e636865656b2d6c6964.v13"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"$packageVersion","name":"Single-eye cheek and lid coupling with tongue",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/coupling","aliases":["mouth.cheek-lid"],"version":13,"name":"Single-eye cheek and lid coupling with tongue","category":"mouth",
               "targets":["mesh"],"requires":["rig.scaffold","mouth/fitted","eye/fitted"],"contextVersion":2,
               "reads":["*"],"writes":["mesh","deformer","part","hierarchy"],
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

val mouthTongueV4WorkerJar = registerIsolatedBindingWorker(
    "mouthTongueV4", "mouth-tongue-v4", "worker-mouth-tongue-v4", "io.github.psd2live.algorithms.binding.MouthTongueV4Worker",
)
kotlin.target.compilations.named("mouthTongueV4") {
    associateWith(kotlin.target.compilations.getByName("mouthTongue"))
}
mouthTongueV4WorkerJar.configure {
    from(sourceSets["mouthTongue"].output)
}

tasks.register("pluginMouthTongueV4") {
    dependsOn(mouthTongueV4WorkerJar)
    val output = layout.buildDirectory.dir("plugin-mouth-tongue-v4")
    inputs.file(mouthTongueV4WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", project.version.toString())
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        mouthTongueV4WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.6d6f7574682e746f6e677565.v4"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"${project.version}","name":"Mouth tongue with a closed drawing",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/tongue","aliases":["mouth.tongue"],"version":4,"name":"Mouth tongue with a closed drawing","category":"mouth",
               "targets":["mesh"],"requires":["rig.scaffold","mouth/fitted","mouth.capture"],"contextVersion":2,
               "reads":["mesh","parameter","hierarchy","canvas"],"writes":["mesh","parameter","hierarchy"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val mouthTongueV5WorkerJar = registerIsolatedBindingWorker(
    "mouthTongueV5", "mouth-tongue-v5", "worker-mouth-tongue-v5", "io.github.psd2live.algorithms.binding.MouthTongueV5Worker",
)
kotlin.target.compilations.named("mouthTongueV5") {
    associateWith(kotlin.target.compilations.getByName("mouthTongue"))
}
mouthTongueV5WorkerJar.configure {
    from(sourceSets["mouthTongue"].output)
}

tasks.register("pluginMouthTongueV5") {
    dependsOn(mouthTongueV5WorkerJar)
    val output = layout.buildDirectory.dir("plugin-mouth-tongue-v5")
    inputs.file(mouthTongueV5WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", project.version.toString())
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        mouthTongueV5WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.6d6f7574682e746f6e677565.v5"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"${project.version}","name":"Mouth tongue after fitted capture",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/tongue","aliases":["mouth.tongue"],"version":5,"name":"Mouth tongue after fitted capture","category":"mouth",
               "targets":["mesh"],"requires":["rig.scaffold","mouth/fitted","mouth.capture"],"contextVersion":2,
               "reads":["mesh","parameter","hierarchy","canvas"],"writes":["mesh","parameter","hierarchy"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}


val mouthTongueV6WorkerJar = registerIsolatedBindingWorker(
    "mouthTongueV6", "mouth-tongue-v6", "worker-mouth-tongue-v6", "io.github.psd2live.algorithms.binding.MouthTongueV6Worker",
)

tasks.register("pluginMouthTongueV6") {
    dependsOn(mouthTongueV6WorkerJar)
    val output = layout.buildDirectory.dir("plugin-mouth-tongue-v6")
    inputs.file(mouthTongueV6WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", project.version.toString())
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        mouthTongueV6WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.6d6f7574682e746f6e677565.v6"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"${project.version}","name":"Mouth tongue after fitted capture",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/tongue","aliases":["mouth.tongue"],"version":6,"name":"Mouth tongue after fitted capture","category":"mouth",
               "targets":["mesh"],"requires":["rig.scaffold","mouth/fitted","mouth.capture"],"contextVersion":2,
               "reads":["*"],"writes":["mesh","deformer","part","parameter","hierarchy"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val mouthTongueV7WorkerJar = registerIsolatedBindingWorker(
    "mouthTongueV7", "mouth-tongue-v7", "worker-mouth-tongue-v7", "io.github.psd2live.algorithms.binding.MouthTongueV7Worker",
)

tasks.register("pluginMouthTongueV7") {
    dependsOn(mouthTongueV7WorkerJar)
    val output = layout.buildDirectory.dir("plugin-mouth-tongue-v7")
    inputs.file(mouthTongueV7WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", project.version.toString())
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        mouthTongueV7WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.6d6f7574682e746f6e677565.v7"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"${project.version}","name":"Mouth tongue after fitted capture",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/tongue","aliases":["mouth.tongue"],"version":7,"name":"Mouth tongue after fitted capture","category":"mouth",
               "targets":["mesh"],"requires":["rig.scaffold","mouth/fitted","mouth.capture"],"contextVersion":2,
               "reads":["*"],"writes":["mesh","deformer","part","parameter","hierarchy"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val mouthTongueV8WorkerJar = registerIsolatedBindingWorker(
    "mouthTongueV8", "mouth-tongue-v8", "worker-mouth-tongue-v8", "io.github.psd2live.algorithms.binding.MouthTongueV8Worker",
)

tasks.register("pluginMouthTongueV8") {
    dependsOn(mouthTongueV8WorkerJar)
    val output = layout.buildDirectory.dir("plugin-mouth-tongue-v8")
    inputs.file(mouthTongueV8WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", project.version.toString())
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        mouthTongueV8WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.6d6f7574682e746f6e677565.v8"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"${project.version}","name":"Single-eye mouth tongue",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/tongue","aliases":["mouth.tongue"],"version":8,"name":"Single-eye mouth tongue","category":"mouth",
               "targets":["mesh"],"requires":["rig.scaffold","mouth/fitted","mouth.capture"],"contextVersion":2,
               "reads":["*"],"writes":["mesh","deformer","part","parameter","hierarchy"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val mouthTongueV9WorkerJar = registerIsolatedBindingWorker(
    "mouthTongueV9", "mouth-tongue-v9", "worker-mouth-tongue-v9", "io.github.psd2live.algorithms.binding.MouthTongueV9Worker",
)

kotlin.target.compilations.named("mouthTongueV9") {
    associateWith(kotlin.target.compilations.getByName("mouthTongueV7"))
}
mouthTongueV9WorkerJar.configure { from(sourceSets["mouthTongueV7"].output) }

tasks.register("pluginMouthTongueV9") {
    dependsOn(mouthTongueV9WorkerJar)
    val output = layout.buildDirectory.dir("plugin-mouth-tongue-v9")
    inputs.file(mouthTongueV9WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", project.version.toString())
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        mouthTongueV9WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.6d6f7574682e746f6e677565.v9"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"${project.version}","name":"Layered mouth tongue and tip",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/tongue","aliases":["mouth.tongue"],"version":9,"name":"Layered mouth tongue and tip","category":"mouth",
               "targets":["mesh"],"requires":["rig.scaffold","mouth/fitted","mouth.capture"],"contextVersion":2,
               "reads":["*"],"writes":["mesh","deformer","part","parameter","hierarchy"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val mouthJawV4WorkerJar = registerIsolatedBindingWorker(
    "mouthJawV4", "mouth-jaw-v4", "worker-mouth-jaw-v4", "io.github.psd2live.algorithms.binding.MouthJawV4Worker",
)

tasks.register("pluginMouthJawV4") {
    dependsOn(mouthJawV4WorkerJar)
    val output = layout.buildDirectory.dir("plugin-mouth-jaw-v4")
    val packageVersion = project.version.toString()
    inputs.file(mouthJawV4WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", packageVersion)
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        mouthJawV4WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.6d6f7574682e6a6177.v4"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"$packageVersion","name":"Mouth jaw for fitted source",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/jaw","aliases":["mouth.jaw"],"version":4,"name":"Mouth jaw for fitted source","category":"mouth",
               "targets":["mesh"],"requires":["rig.scaffold","mouth/fitted"],"contextVersion":2,
               "reads":["canvas","mesh","parameter"],"writes":["mesh"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val mouthJawV5WorkerJar = registerIsolatedBindingWorker(
    "mouthJawV5", "mouth-jaw-v5", "worker-mouth-jaw-v5", "io.github.psd2live.algorithms.binding.MouthJawV5Worker",
)

tasks.register("pluginMouthJawV5") {
    dependsOn(mouthJawV5WorkerJar)
    val output = layout.buildDirectory.dir("plugin-mouth-jaw-v5")
    inputs.file(mouthJawV5WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", project.version.toString())
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        mouthJawV5WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.6d6f7574682e6a6177.v5"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"${project.version}","name":"Mouth jaw for fitted closed drawings",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/jaw","aliases":["mouth.jaw"],"version":5,"name":"Mouth jaw for fitted closed drawings","category":"mouth",
               "targets":["mesh"],"requires":["rig.scaffold","mouth/fitted"],"contextVersion":2,
               "reads":["mesh","parameter"],"writes":["mesh"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val mouthJawV6WorkerJar = registerIsolatedBindingWorker(
    "mouthJawV6", "mouth-jaw-v6", "worker-mouth-jaw-v6", "io.github.psd2live.algorithms.binding.MouthJawV6Worker",
)
kotlin.target.compilations.named("mouthJawV6") {
    associateWith(kotlin.target.compilations.getByName("mouthJawV5"))
}
mouthJawV6WorkerJar.configure {
    from(sourceSets["mouthJawV5"].output)
}

tasks.register("pluginMouthJawV6") {
    dependsOn(mouthJawV6WorkerJar)
    val output = layout.buildDirectory.dir("plugin-mouth-jaw-v6")
    inputs.file(mouthJawV6WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", project.version.toString())
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        mouthJawV6WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.6d6f7574682e6a6177.v6"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"${project.version}","name":"Mouth jaw with independent closed tongue",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/jaw","aliases":["mouth.jaw"],"version":6,"name":"Mouth jaw with independent closed tongue","category":"mouth",
               "targets":["mesh"],"requires":["rig.scaffold","mouth/fitted"],"contextVersion":2,
               "reads":["mesh","parameter"],"writes":["mesh"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val mouthJawV7WorkerJar = registerIsolatedBindingWorker(
    "mouthJawV7", "mouth-jaw-v7", "worker-mouth-jaw-v7", "io.github.psd2live.algorithms.binding.MouthJawV7Worker",
)
tasks.register("pluginMouthJawV7") {
    dependsOn(mouthJawV7WorkerJar)
    val output = layout.buildDirectory.dir("plugin-mouth-jaw-v7")
    inputs.file(mouthJawV7WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", project.version.toString())
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        mouthJawV7WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.6d6f7574682e6a6177.v7"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"${project.version}","name":"Mouth jaw",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/jaw","aliases":["mouth.jaw"],"version":7,"name":"Mouth jaw","category":"mouth",
               "targets":["mesh"],"requires":["rig.scaffold","mouth/fitted"],"contextVersion":2,
               "reads":["mesh","parameter"],"writes":["mesh"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}


val mouthJawV8WorkerJar = registerIsolatedBindingWorker(
    "mouthJawV8", "mouth-jaw-v8", "worker-mouth-jaw-v8", "io.github.psd2live.algorithms.binding.MouthJawV8Worker",
)

tasks.register("pluginMouthJawV8") {
    dependsOn(mouthJawV8WorkerJar)
    val output = layout.buildDirectory.dir("plugin-mouth-jaw-v8")
    inputs.file(mouthJawV8WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", project.version.toString())
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        mouthJawV8WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.6d6f7574682e6a6177.v8"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"${project.version}","name":"Mouth jaw",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/jaw","aliases":["mouth.jaw"],"version":8,"name":"Mouth jaw","category":"mouth",
               "targets":["mesh"],"requires":["rig.scaffold","mouth/fitted"],"contextVersion":2,
               "reads":["*"],"writes":["mesh"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val mouthJawV11WorkerJar = registerIsolatedBindingWorker(
    "mouthJawV11", "mouth-jaw-v11", "worker-mouth-jaw-v11", "io.github.psd2live.algorithms.binding.MouthJawV11Worker",
)

tasks.register("pluginMouthJawV11") {
    dependsOn(mouthJawV11WorkerJar)
    val output = layout.buildDirectory.dir("plugin-mouth-jaw-v11")
    inputs.file(mouthJawV11WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", project.version.toString())
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        mouthJawV11WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.6d6f7574682e6a6177.v11"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"${project.version}","name":"Mouth jaw",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/jaw","aliases":["mouth.jaw"],"version":11,"name":"Mouth jaw","category":"mouth",
               "targets":["mesh"],"requires":["rig.scaffold","mouth/fitted"],"contextVersion":2,
               "reads":["*"],"writes":["mesh"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val mouthJawV9WorkerJar = registerIsolatedBindingWorker(
    "mouthJawV9", "mouth-jaw-v9", "worker-mouth-jaw-v9", "io.github.psd2live.algorithms.binding.MouthJawV9Worker",
)

tasks.register("pluginMouthJawV9") {
    dependsOn(mouthJawV9WorkerJar)
    val output = layout.buildDirectory.dir("plugin-mouth-jaw-v9")
    inputs.file(mouthJawV9WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", project.version.toString())
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        mouthJawV9WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.6d6f7574682e6a6177.v9"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"${project.version}","name":"Mouth jaw",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/jaw","aliases":["mouth.jaw"],"version":9,"name":"Mouth jaw","category":"mouth",
               "targets":["mesh"],"requires":["rig.scaffold","mouth/fitted"],"contextVersion":2,
               "reads":["*"],"writes":["mesh"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val mouthJawV10WorkerJar = registerIsolatedBindingWorker(
    "mouthJawV10", "mouth-jaw-v10", "worker-mouth-jaw-v10", "io.github.psd2live.algorithms.binding.MouthJawV10Worker",
)

tasks.register("pluginMouthJawV10") {
    dependsOn(mouthJawV10WorkerJar)
    val output = layout.buildDirectory.dir("plugin-mouth-jaw-v10")
    inputs.file(mouthJawV10WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", project.version.toString())
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        mouthJawV10WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.6d6f7574682e6a6177.v10"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"${project.version}","name":"Layered mouth jaw",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/jaw","aliases":["mouth.jaw"],"version":10,"name":"Layered mouth jaw","category":"mouth",
               "targets":["mesh"],"requires":["rig.scaffold","mouth/fitted"],"contextVersion":2,
               "reads":["*"],"writes":["mesh"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val mouthJawV12WorkerJar = registerIsolatedBindingWorker(
    "mouthJawV12", "mouth-jaw-v12", "worker-mouth-jaw-v12", "io.github.psd2live.algorithms.binding.MouthJawV12Worker",
)

tasks.register("pluginMouthJawV12") {
    dependsOn(mouthJawV12WorkerJar)
    val output = layout.buildDirectory.dir("plugin-mouth-jaw-v12")
    inputs.file(mouthJawV12WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", project.version.toString())
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        mouthJawV12WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.6d6f7574682e6a6177.v12"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"${project.version}","name":"Layered mouth jaw",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/jaw","aliases":["mouth.jaw"],"version":12,"name":"Layered mouth jaw","category":"mouth",
               "targets":["mesh"],"requires":["rig.scaffold","mouth/fitted"],"contextVersion":2,
               "reads":["*"],"writes":["mesh"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val mouthJawV13WorkerJar = registerIsolatedBindingWorker(
    "mouthJawV13", "mouth-jaw-v13", "worker-mouth-jaw-v13", "io.github.psd2live.algorithms.binding.MouthJawV13Worker",
)

tasks.register("pluginMouthJawV13") {
    dependsOn(mouthJawV13WorkerJar)
    val output = layout.buildDirectory.dir("plugin-mouth-jaw-v13")
    inputs.file(mouthJawV13WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", project.version.toString())
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        mouthJawV13WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.6d6f7574682e6a6177.v13"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"${project.version}","name":"Single-eye mouth jaw with tongue",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/jaw","aliases":["mouth.jaw"],"version":13,"name":"Single-eye mouth jaw with tongue","category":"mouth",
               "targets":["mesh"],"requires":["rig.scaffold","mouth/fitted"],"contextVersion":2,
               "reads":["*"],"writes":["mesh"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val mouthJawV14WorkerJar = registerIsolatedBindingWorker(
    "mouthJawV14", "mouth-jaw-v14", "worker-mouth-jaw-v14", "io.github.psd2live.algorithms.binding.MouthJawV14Worker",
)

kotlin.target.compilations.named("mouthJawV14") {
    associateWith(kotlin.target.compilations.getByName("mouthTongueV9"))
}
mouthJawV14WorkerJar.configure { from(sourceSets["mouthTongueV9"].output) }

tasks.register("pluginMouthJawV14") {
    dependsOn(mouthJawV14WorkerJar)
    val output = layout.buildDirectory.dir("plugin-mouth-jaw-v14")
    inputs.file(mouthJawV14WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", project.version.toString())
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        mouthJawV14WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.6d6f7574682e6a6177.v14"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"${project.version}","name":"Layered mouth jaw with tongue tip",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/jaw","aliases":["mouth.jaw"],"version":14,"name":"Layered mouth jaw with tongue tip","category":"mouth",
               "targets":["mesh"],"requires":["rig.scaffold","mouth/fitted"],"contextVersion":2,
               "reads":["*"],"writes":["mesh"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val mouthCheekLidV14WorkerJar = registerIsolatedBindingWorker(
    "mouthCheekLidV14", "mouth-cheek-lid-v14", "worker-mouth-cheek-lid-v14", "io.github.psd2live.algorithms.binding.MouthCheekLidV14Worker",
)

kotlin.target.compilations.named("mouthCheekLidV14") {
    associateWith(kotlin.target.compilations.getByName("mouthTongueV9"))
    associateWith(kotlin.target.compilations.getByName("mouthJawV14"))
}
mouthCheekLidV14WorkerJar.configure { from(sourceSets["mouthTongueV9"].output, sourceSets["mouthJawV14"].output) }

tasks.register("pluginMouthCheekLidV14") {
    dependsOn(mouthCheekLidV14WorkerJar)
    val output = layout.buildDirectory.dir("plugin-mouth-cheek-lid-v14")
    val packageVersion = project.version.toString()
    inputs.file(mouthCheekLidV14WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", packageVersion)
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        mouthCheekLidV14WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.6d6f7574682e636865656b2d6c6964.v14"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"$packageVersion","name":"Layered cheek and lid coupling with tongue tip",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/coupling","aliases":["mouth.cheek-lid"],"version":14,"name":"Layered cheek and lid coupling with tongue tip","category":"mouth",
               "targets":["mesh"],"requires":["rig.scaffold","mouth/fitted","eye/fitted"],"contextVersion":2,
               "reads":["*"],"writes":["mesh","deformer","part","hierarchy"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val mouthSpringV5WorkerJar = registerIsolatedBindingWorker(
    "mouthSpringV5", "mouth-spring-v5", "worker-mouth-spring-v5", "io.github.psd2live.algorithms.binding.MouthSpringV5Worker",
)

tasks.register("pluginMouthSpringV5") {
    dependsOn(mouthSpringV5WorkerJar)
    val output = layout.buildDirectory.dir("plugin-mouth-spring-v5")
    val packageVersion = project.version.toString()
    inputs.file(mouthSpringV5WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", packageVersion)
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        mouthSpringV5WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.6d6f7574682e737072696e67.v5"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"$packageVersion","name":"Mouth spring",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/spring","aliases":["mouth.spring"],"version":5,"name":"Mouth spring","category":"mouth",
               "targets":["mesh"],"requires":["rig.scaffold","mouth/fitted","mouth.jaw"],"contextVersion":2,
               "reads":["mesh","deformer","parameter","hierarchy","physics"],"writes":["mesh","parameter","hierarchy","physics"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val mouthSpringV6WorkerJar = registerIsolatedBindingWorker(
    "mouthSpringV6", "mouth-spring-v6", "worker-mouth-spring-v6", "io.github.psd2live.algorithms.binding.MouthSpringV6Worker",
)

tasks.register("pluginMouthSpringV6") {
    dependsOn(mouthSpringV6WorkerJar)
    val output = layout.buildDirectory.dir("plugin-mouth-spring-v6")
    val packageVersion = project.version.toString()
    inputs.file(mouthSpringV6WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", packageVersion)
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        mouthSpringV6WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.6d6f7574682e737072696e67.v6"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"$packageVersion","name":"Mouth spring",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/spring","aliases":["mouth.spring"],"version":6,"name":"Mouth spring","category":"mouth",
               "targets":["mesh"],"requires":["rig.scaffold","mouth/fitted","mouth.jaw"],"contextVersion":2,
               "reads":["mesh","deformer","parameter","hierarchy","physics"],"writes":["mesh","parameter","hierarchy","physics"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val mouthSpringV7WorkerJar = registerIsolatedBindingWorker(
    "mouthSpringV7", "mouth-spring-v7", "worker-mouth-spring-v7", "io.github.psd2live.algorithms.binding.MouthSpringV7Worker",
)

kotlin.target.compilations.named("mouthSpringV7") {
    associateWith(kotlin.target.compilations.getByName("mouthSpringV6"))
}
mouthSpringV7WorkerJar.configure {
    from(sourceSets["mouthSpringV6"].output)
}

tasks.register("pluginMouthSpringV7") {
    dependsOn(mouthSpringV7WorkerJar)
    val output = layout.buildDirectory.dir("plugin-mouth-spring-v7")
    val packageVersion = project.version.toString()
    inputs.file(mouthSpringV7WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", packageVersion)
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        mouthSpringV7WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.6d6f7574682e737072696e67.v7"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"$packageVersion","name":"Mouth spring",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/spring","aliases":["mouth.spring"],"version":7,"name":"Mouth spring","category":"mouth",
               "targets":["mesh"],"requires":["rig.scaffold","mouth/fitted","mouth.jaw"],"contextVersion":2,
               "reads":["mesh","deformer","parameter","hierarchy","physics"],"writes":["mesh","parameter","hierarchy","physics"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val mouthSpringV8WorkerJar = registerIsolatedBindingWorker(
    "mouthSpringV8", "mouth-spring-v8", "worker-mouth-spring-v8", "io.github.psd2live.algorithms.binding.MouthSpringV8Worker",
)

tasks.register("pluginMouthSpringV8") {
    dependsOn(mouthSpringV8WorkerJar)
    val output = layout.buildDirectory.dir("plugin-mouth-spring-v8")
    val packageVersion = project.version.toString()
    inputs.file(mouthSpringV8WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", packageVersion)
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        mouthSpringV8WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.6d6f7574682e737072696e67.v8"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"$packageVersion","name":"Mouth spring",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/spring","aliases":["mouth.spring"],"version":8,"name":"Mouth spring","category":"mouth",
               "targets":["mesh"],"requires":["rig.scaffold","mouth/fitted","mouth.jaw"],"contextVersion":2,
               "reads":["mesh","deformer","parameter","hierarchy","physics"],"writes":["mesh","parameter","hierarchy","physics"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}


val mouthSpringV10WorkerJar = registerIsolatedBindingWorker(
    "mouthSpringV10", "mouth-spring-v10", "worker-mouth-spring-v10", "io.github.psd2live.algorithms.binding.MouthSpringV10Worker",
)

tasks.register("pluginMouthSpringV10") {
    dependsOn(mouthSpringV10WorkerJar)
    val output = layout.buildDirectory.dir("plugin-mouth-spring-v10")
    val packageVersion = project.version.toString()
    inputs.file(mouthSpringV10WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", packageVersion)
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        mouthSpringV10WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.6d6f7574682e737072696e67.v10"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"$packageVersion","name":"Mouth spring",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/spring","aliases":["mouth.spring"],"version":10,"name":"Mouth spring","category":"mouth",
               "targets":["mesh"],"requires":["rig.scaffold","body/legacy","face/fitted","eye/fitted","brow/fitted","mouth/fitted","mouth.outline","mouth.vowel","mouth.capture","mouth.jaw"],"contextVersion":2,
               "reads":["*"],"writes":["mesh","parameter","hierarchy","physics"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}


val mouthSpringV9WorkerJar = registerIsolatedBindingWorker(
    "mouthSpringV9", "mouth-spring-v9", "worker-mouth-spring-v9", "io.github.psd2live.algorithms.binding.MouthSpringV9Worker",
)

tasks.register("pluginMouthSpringV9") {
    dependsOn(mouthSpringV9WorkerJar)
    val output = layout.buildDirectory.dir("plugin-mouth-spring-v9")
    val packageVersion = project.version.toString()
    inputs.file(mouthSpringV9WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", packageVersion)
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        mouthSpringV9WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.6d6f7574682e737072696e67.v9"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"$packageVersion","name":"Mouth spring",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/spring","aliases":["mouth.spring"],"version":9,"name":"Mouth spring","category":"mouth",
               "targets":["mesh"],"requires":["rig.scaffold","mouth/fitted","mouth.jaw"],"contextVersion":2,
               "reads":["*"],"writes":["mesh","deformer","part","parameter","hierarchy","physics"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val mouthSpringV12WorkerJar = registerIsolatedBindingWorker(
    "mouthSpringV12", "mouth-spring-v12", "worker-mouth-spring-v12", "io.github.psd2live.algorithms.binding.MouthSpringV12Worker",
)

tasks.register("pluginMouthSpringV12") {
    dependsOn(mouthSpringV12WorkerJar)
    val output = layout.buildDirectory.dir("plugin-mouth-spring-v12")
    val packageVersion = project.version.toString()
    inputs.file(mouthSpringV12WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", packageVersion)
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        mouthSpringV12WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.6d6f7574682e737072696e67.v12"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"$packageVersion","name":"Mouth spring",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/spring","aliases":["mouth.spring"],"version":12,"name":"Mouth spring","category":"mouth",
               "targets":["mesh"],"requires":["rig.scaffold","mouth/fitted","mouth.jaw"],"contextVersion":2,
               "reads":["*"],"writes":["mesh","deformer","part","parameter","hierarchy","physics"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val mouthSpringV17WorkerJar = registerIsolatedBindingWorker(
    "mouthSpringV17", "mouth-spring-v17", "worker-mouth-spring-v17", "io.github.psd2live.algorithms.binding.MouthSpringV17Worker",
)

tasks.register("pluginMouthSpringV17") {
    dependsOn(mouthSpringV17WorkerJar)
    val output = layout.buildDirectory.dir("plugin-mouth-spring-v17")
    val packageVersion = project.version.toString()
    inputs.file(mouthSpringV17WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", packageVersion)
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        mouthSpringV17WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.6d6f7574682e737072696e67.v17"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"$packageVersion","name":"Mouth spring",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/spring","aliases":["mouth.spring"],"version":17,"name":"Mouth spring","category":"mouth",
               "targets":["mesh"],"requires":["rig.scaffold","mouth/fitted","mouth.jaw"],"contextVersion":2,
               "reads":["*"],"writes":["mesh","deformer","part","parameter","hierarchy","physics"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val mouthSpringV11WorkerJar = registerIsolatedBindingWorker(
    "mouthSpringV11", "mouth-spring-v11", "worker-mouth-spring-v11", "io.github.psd2live.algorithms.binding.MouthSpringV11Worker",
)

tasks.register("pluginMouthSpringV11") {
    dependsOn(mouthSpringV11WorkerJar)
    val output = layout.buildDirectory.dir("plugin-mouth-spring-v11")
    val packageVersion = project.version.toString()
    inputs.file(mouthSpringV11WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", packageVersion)
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        mouthSpringV11WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.6d6f7574682e737072696e67.v11"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"$packageVersion","name":"Layered mouth spring",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/spring","aliases":["mouth.spring"],"version":11,"name":"Layered mouth spring","category":"mouth",
               "targets":["mesh"],"requires":["rig.scaffold","mouth/fitted","mouth.jaw"],"contextVersion":2,
               "reads":["*"],"writes":["mesh","deformer","part","parameter","hierarchy","physics"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val mouthSpringV13WorkerJar = registerIsolatedBindingWorker(
    "mouthSpringV13", "mouth-spring-v13", "worker-mouth-spring-v13", "io.github.psd2live.algorithms.binding.MouthSpringV13Worker",
)

tasks.register("pluginMouthSpringV13") {
    dependsOn(mouthSpringV13WorkerJar)
    val output = layout.buildDirectory.dir("plugin-mouth-spring-v13")
    val packageVersion = project.version.toString()
    inputs.file(mouthSpringV13WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", packageVersion)
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        mouthSpringV13WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.6d6f7574682e737072696e67.v13"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"$packageVersion","name":"Layered mouth spring",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/spring","aliases":["mouth.spring"],"version":13,"name":"Layered mouth spring","category":"mouth",
               "targets":["mesh"],"requires":["rig.scaffold","mouth/fitted","mouth.jaw"],"contextVersion":2,
               "reads":["*"],"writes":["mesh","deformer","part","parameter","hierarchy","physics"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val mouthSpringV14WorkerJar = registerIsolatedBindingWorker(
    "mouthSpringV14", "mouth-spring-v14", "worker-mouth-spring-v14", "io.github.psd2live.algorithms.binding.MouthSpringV14Worker",
)

tasks.register("pluginMouthSpringV14") {
    dependsOn(mouthSpringV14WorkerJar)
    val output = layout.buildDirectory.dir("plugin-mouth-spring-v14")
    val packageVersion = project.version.toString()
    inputs.file(mouthSpringV14WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", packageVersion)
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        mouthSpringV14WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.6d6f7574682e737072696e67.v14"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"$packageVersion","name":"Layered mouth spring",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/spring","aliases":["mouth.spring"],"version":14,"name":"Layered mouth spring","category":"mouth",
               "targets":["mesh"],"requires":["rig.scaffold","mouth/fitted","mouth.jaw"],"contextVersion":2,
               "reads":["*"],"writes":["mesh","deformer","part","parameter","hierarchy","physics"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val eyeAftershockV3WorkerJar = registerIsolatedBindingWorker(
    "eyeAftershockV3", "eye-aftershock-v3", "worker-eye-aftershock-v3", "io.github.psd2live.algorithms.binding.EyeAftershockV3Worker",
)

kotlin.target.compilations.named("eyeAftershockV3") {
    associateWith(kotlin.target.compilations.getByName("eyeSmile"))
}
eyeAftershockV3WorkerJar.configure {
    from(sourceSets["eyeSmile"].output) { include("**/EyeSmileInputs*.class") }
}

tasks.register("pluginEyeAftershockV3") {
    dependsOn(eyeAftershockV3WorkerJar)
    val output = layout.buildDirectory.dir("plugin-eye-aftershock-v3")
    val packageVersion = project.version.toString()
    inputs.file(eyeAftershockV3WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", packageVersion)
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        eyeAftershockV3WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.6579652e616674657273686f636b.v3"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"$packageVersion","name":"Blink aftershock",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/aftershock","aliases":["eye.aftershock"],"version":3,"name":"Blink aftershock","category":"eye",
               "targets":["mesh"],"requires":["rig.scaffold","eye/fitted"],"contextVersion":2,
               "reads":["mesh","parameter","hierarchy","canvas","deformer","physics"],"writes":["mesh","parameter","hierarchy","physics"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter","physics"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val eyeAftershockV4WorkerJar = registerIsolatedBindingWorker(
    "eyeAftershockV4", "eye-aftershock-v4", "worker-eye-aftershock-v4", "io.github.psd2live.algorithms.binding.EyeAftershockV4Worker",
)

kotlin.target.compilations.named("eyeAftershockV4") {
    associateWith(kotlin.target.compilations.getByName("eyeSmile"))
}
eyeAftershockV4WorkerJar.configure {
    from(sourceSets["eyeSmile"].output) { include("**/EyeSmileInputs*.class") }
}

tasks.register("pluginEyeAftershockV4") {
    dependsOn(eyeAftershockV4WorkerJar)
    val output = layout.buildDirectory.dir("plugin-eye-aftershock-v4")
    val packageVersion = project.version.toString()
    inputs.file(eyeAftershockV4WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", packageVersion)
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        eyeAftershockV4WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.6579652e616674657273686f636b.v4"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"$packageVersion","name":"Blink aftershock",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/aftershock","aliases":["eye.aftershock"],"version":4,"name":"Blink aftershock","category":"eye",
               "targets":["mesh"],"requires":["rig.scaffold","eye/fitted"],"contextVersion":2,
               "reads":["mesh","parameter","hierarchy","canvas","deformer","physics"],"writes":["mesh","parameter","hierarchy","physics"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter","physics"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}


val eyeAftershockV5WorkerJar = registerIsolatedBindingWorker(
    "eyeAftershockV5", "eye-aftershock-v5", "worker-eye-aftershock-v5", "io.github.psd2live.algorithms.binding.EyeAftershockV5Worker",
)

tasks.register("pluginEyeAftershockV5") {
    dependsOn(eyeAftershockV5WorkerJar)
    val output = layout.buildDirectory.dir("plugin-eye-aftershock-v5")
    val packageVersion = project.version.toString()
    inputs.file(eyeAftershockV5WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", packageVersion)
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        eyeAftershockV5WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.6579652e616674657273686f636b.v5"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"$packageVersion","name":"Blink aftershock",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/aftershock","aliases":["eye.aftershock"],"version":5,"name":"Blink aftershock","category":"eye",
               "targets":["mesh"],"requires":["rig.scaffold","eye/fitted"],"contextVersion":2,
               "reads":["*"],"writes":["mesh","deformer","part","parameter","hierarchy","physics"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter","physics"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val mouthSpringV15WorkerJar = registerIsolatedBindingWorker(
    "mouthSpringV15", "mouth-spring-v15", "worker-mouth-spring-v15", "io.github.psd2live.algorithms.binding.MouthSpringV15Worker",
)

tasks.register("pluginMouthSpringV15") {
    dependsOn(mouthSpringV15WorkerJar)
    val output = layout.buildDirectory.dir("plugin-mouth-spring-v15")
    val packageVersion = project.version.toString()
    inputs.file(mouthSpringV15WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", packageVersion)
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        mouthSpringV15WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.6d6f7574682e737072696e67.v15"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"$packageVersion","name":"Single-eye mouth spring with tongue",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/spring","aliases":["mouth.spring"],"version":15,"name":"Single-eye mouth spring with tongue","category":"mouth",
               "targets":["mesh"],"requires":["rig.scaffold","mouth/fitted","mouth.jaw"],"contextVersion":2,
               "reads":["*"],"writes":["mesh","deformer","part","parameter","hierarchy","physics"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

val mouthSpringV16WorkerJar = registerIsolatedBindingWorker(
    "mouthSpringV16", "mouth-spring-v16", "worker-mouth-spring-v16", "io.github.psd2live.algorithms.binding.MouthSpringV16Worker",
)

tasks.register("pluginMouthSpringV16") {
    dependsOn(mouthSpringV16WorkerJar)
    val output = layout.buildDirectory.dir("plugin-mouth-spring-v16")
    val packageVersion = project.version.toString()
    inputs.file(mouthSpringV16WorkerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", packageVersion)
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val payload = directory.resolve("algorithm.jar")
        mouthSpringV16WorkerJar.get().archiveFile.get().asFile.copyTo(payload, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.readBytes()))
        val id = "builtin.6d6f7574682e737072696e67.v16"
        directory.resolve("plugin.json").writeText("""
            {"protocol":1,"id":"$id","version":"$packageVersion","name":"Single-eye mouth spring without tongue",
             "commands":{"jvm":["${'$'}JAVA","-jar","algorithm.jar"]},"files":{"algorithm.jar":"$digest"},
             "algorithms":[{"type":"$id/spring","aliases":["mouth.spring"],"version":16,"name":"Single-eye mouth spring without tongue","category":"mouth",
               "targets":["mesh"],"requires":["rig.scaffold","mouth/fitted","mouth.jaw"],"contextVersion":2,
               "reads":["*"],"writes":["mesh","deformer","part","parameter","hierarchy","physics"],
               "composableKinds":["mesh","part","deformer","hierarchy","relations","parameter"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
}

// Every binding package above runs on the same worker runtime. Shipping a copy of it inside each package made the app
// ~2 GB, so each package keeps only the classes that differ from the runtime and names a shared runtime.jar on its
// Class-Path; the app bundles that runtime once (see processResources in the root build).
val sharedRuntimeName = "runtime.jar"
val sharedRuntimeTime = GregorianCalendar(1980, Calendar.FEBRUARY, 1).timeInMillis

fun shareWorkerRuntime(directory: File, runtime: File) {
    val payload = directory.resolve("algorithm.jar")
    val manifestFile = directory.resolve("plugin.json")
    if (!payload.isFile || !manifestFile.isFile) return
    val shared = ZipFile(runtime).use { zip ->
        zip.entries().asSequence().filterNot { it.isDirectory }.associate { it.name to zip.getInputStream(it).readBytes() }
    }
    val (manifest, kept) = JarFile(payload).use { jar ->
        val manifest = Manifest(jar.manifest ?: Manifest())
        manifest.mainAttributes.putIfAbsent(Attributes.Name.MANIFEST_VERSION, "1.0")
        manifest.mainAttributes[Attributes.Name.CLASS_PATH] = sharedRuntimeName
        manifest to jar.entries().asSequence()
            .filter { !it.isDirectory && it.name != JarFile.MANIFEST_NAME }
            .map { it.name to jar.getInputStream(it).readBytes() }
            .filterNot { (name, bytes) -> shared[name]?.contentEquals(bytes) == true }
            .toList()
    }
    val thin = ByteArrayOutputStream()
    ZipOutputStream(thin).use { out ->
        fun put(name: String, bytes: ByteArray) {
            out.putNextEntry(ZipEntry(name).apply { time = sharedRuntimeTime })
            out.write(bytes)
            out.closeEntry()
        }
        put(JarFile.MANIFEST_NAME, ByteArrayOutputStream().also(manifest::write).toByteArray())
        kept.forEach { (name, bytes) -> put(name, bytes) }
    }
    payload.writeBytes(thin.toByteArray())
    runtime.copyTo(directory.resolve(sharedRuntimeName), overwrite = true)
    fun digest(file: File) = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(file.readBytes()))
    val files = Regex(""""files":\{"algorithm\.jar":"[0-9a-f]{64}"}""")
    val text = manifestFile.readText()
    require(files.findAll(text).count() == 1) { "Unexpected plugin manifest in $directory" }
    manifestFile.writeText(text.replace(files,
        """"files":{"algorithm.jar":"${digest(payload)}","$sharedRuntimeName":"${digest(directory.resolve(sharedRuntimeName))}"}"""))
}

tasks.matching { it.name.startsWith("plugin") }.configureEach {
    dependsOn(workerJar)
    inputs.file(workerJar.flatMap { it.archiveFile })
    doLast {
        val runtime = workerJar.get().archiveFile.get().asFile
        outputs.files.files.filter { it.isDirectory }.forEach { shareWorkerRuntime(it, runtime) }
    }
}
