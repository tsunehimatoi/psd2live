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
    exclude(neckWorkerClasses + bustWorkerClasses + hairLegacyWorkerClasses + lidFadeWorkerClasses)
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
               "reads":["*"],"writes":["mesh","parameter","hierarchy"],"composableKinds":["mesh","parameter","hierarchy"],
               "configSchema":{"type":"object"},"defaults":{}}]}
        """.trimIndent())
    }
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
    exclude(neckWorkerClasses + bustWorkerClasses + hairLegacyWorkerClasses + lidFadeWorkerClasses)
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
