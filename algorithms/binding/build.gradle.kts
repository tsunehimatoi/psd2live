import java.security.MessageDigest
import java.util.HexFormat

// Binding kernels use the GPL engine; they are separate from the MIT API/format modules.
plugins {
    `java-library`
    kotlin("jvm")
    kotlin("plugin.serialization")
}

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

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    filter.isFailOnNoMatchingTests = false
}

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

private data class BindingManifestSpec(
    val slug: String,
    val type: String,
    val entrypoint: String,
    val name: String,
    val category: String,
    val requires: List<String> = listOf("rig.scaffold"),
    val provides: List<String> = emptyList(),
    val exclusiveFamily: String? = null,
    val writes: List<String>,
    val creates: List<String> = emptyList(),
    val modifies: List<String> = emptyList(),
    val composableKinds: List<String>,
    val parametersJson: String? = null,
)

private val defaultComposable = listOf("mesh", "part", "deformer", "hierarchy", "relations", "parameter")
private val physicsComposable = defaultComposable + "physics"
private val baseWrites = listOf("mesh", "part", "deformer", "hierarchy", "relations", "parameter")
private val restPoseWrites = baseWrites + "rest_pose"
private val physicsWrites = baseWrites + "physics"

private val bindingManifestSpecs = listOf(
    BindingManifestSpec("body-part", "body", "part", "Body", "body",
        provides = listOf("body/legacy"), exclusiveFamily = "base:body",
        writes = physicsWrites,
        creates = listOf("parameters", "warpDeformers", "rotationDeformers", "physicsGroups"),
        modifies = listOf("mesh.offsets", "deformer.keyforms"),
        composableKinds = physicsComposable),
    BindingManifestSpec("body-legacy", "body/legacy", "motion", "Legacy body", "body",
        exclusiveFamily = "base:body", writes = baseWrites, composableKinds = defaultComposable),
    BindingManifestSpec("face-part", "face", "part", "Face", "face",
        provides = listOf("face/fitted", "face/legacy"), exclusiveFamily = "base:face",
        writes = baseWrites,
        creates = listOf("parameters", "warpDeformers", "rotationDeformers"),
        modifies = listOf("mesh.offsets", "deformer.keyforms"),
        composableKinds = defaultComposable),
    BindingManifestSpec("face-legacy", "face/legacy", "binding", "Legacy face binding", "face",
        exclusiveFamily = "base:face", writes = baseWrites, composableKinds = defaultComposable),
    BindingManifestSpec("face-fitted", "face/fitted", "binding", "Fitted face", "face",
        provides = listOf("face/legacy"), exclusiveFamily = "base:face", writes = baseWrites, composableKinds = defaultComposable),
    BindingManifestSpec("eye-part", "eye", "part", "Eyes", "eye",
        provides = listOf("eye/fitted", "eye/legacy"), exclusiveFamily = "base:eye",
        writes = physicsWrites,
        creates = listOf("parameters", "warpDeformers", "rotationDeformers", "physicsGroups"),
        modifies = listOf("mesh.offsets", "mesh.opacity", "deformer.keyforms"),
        composableKinds = physicsComposable),
    BindingManifestSpec("eye-legacy", "eye/legacy", "legacy", "Legacy eyes (source)", "eye",
        exclusiveFamily = "base:eye", writes = baseWrites, composableKinds = defaultComposable),
    BindingManifestSpec("eye-fitted", "eye/fitted", "fitted", "Fitted eyes (source)", "eye",
        provides = listOf("eye/legacy"), exclusiveFamily = "base:eye", writes = baseWrites, composableKinds = defaultComposable),
    BindingManifestSpec("brow-part", "brow", "part", "Brows", "brow",
        provides = listOf("brow/fitted", "brow/legacy"), exclusiveFamily = "base:brow",
        writes = restPoseWrites + "physics",
        creates = listOf("parameters", "warpDeformers", "physicsGroups"),
        modifies = listOf("mesh.offsets", "deformer.keyforms", "restPose"),
        composableKinds = physicsComposable),
    BindingManifestSpec("brow-legacy", "brow/legacy", "binding", "Legacy brow binding", "brow",
        exclusiveFamily = "base:brow", writes = baseWrites, composableKinds = defaultComposable),
    BindingManifestSpec("brow-fitted", "brow/fitted", "binding", "Fitted brows", "brow",
        provides = listOf("brow/legacy"), exclusiveFamily = "base:brow", writes = restPoseWrites, composableKinds = defaultComposable),
    BindingManifestSpec("mouth-part", "mouth", "part", "Mouth", "mouth",
        provides = listOf("mouth/fitted", "mouth/legacy"), exclusiveFamily = "base:mouth",
        writes = restPoseWrites + listOf("physics", "textures", "authoring"),
        creates = listOf("parameters", "warpDeformers", "meshes", "physicsGroups"),
        modifies = listOf("mesh.offsets", "deformer.keyforms", "restPose"),
        composableKinds = physicsComposable + listOf("rest_pose", "textures", "authoring")),
    BindingManifestSpec("mouth-legacy", "mouth/legacy", "binding", "Legacy mouth", "mouth",
        exclusiveFamily = "base:mouth", writes = restPoseWrites, composableKinds = defaultComposable),
    BindingManifestSpec("mouth-fitted", "mouth/fitted", "binding", "Fitted mouth", "mouth",
        provides = listOf("mouth/legacy"), exclusiveFamily = "base:mouth", writes = restPoseWrites, composableKinds = defaultComposable),
    BindingManifestSpec("hair-part", "hair", "part", "Hair", "hair",
        provides = listOf("hair/chains", "hair/legacy"), exclusiveFamily = "base:hair",
        writes = physicsWrites,
        creates = listOf("parameters", "warpDeformers", "physicsGroups"),
        modifies = listOf("mesh.offsets", "deformer.keyforms"),
        composableKinds = physicsComposable),
    BindingManifestSpec("hair-legacy", "hair/legacy", "legacy", "Legacy hair", "hair",
        exclusiveFamily = "base:hair", writes = baseWrites, composableKinds = defaultComposable),
    BindingManifestSpec("hair-chains", "hair/chains", "chains", "Hair chains", "hair",
        provides = listOf("hair/legacy"), requires = listOf("rig.scaffold", "body/legacy", "face/legacy"),
        exclusiveFamily = "base:hair", writes = physicsWrites, composableKinds = physicsComposable),
    BindingManifestSpec("eye-smile", "eye.smile", "smile", "Eye smile", "eye",
        requires = listOf("rig.scaffold", "eye/fitted"), writes = baseWrites, composableKinds = defaultComposable),
    BindingManifestSpec("eye-squash", "eye.squash", "squash", "Iris squash", "eye",
        requires = listOf("rig.scaffold", "eye/fitted"), writes = baseWrites, composableKinds = defaultComposable),
    BindingManifestSpec("eye-wide", "eye.wide", "wide", "Eye widening", "eye",
        requires = listOf("rig.scaffold", "eye/fitted"), writes = baseWrites, composableKinds = defaultComposable),
    BindingManifestSpec("eye-lid-fade", "eye.lid-fade", "fade", "Lid lines fade early", "eye",
        requires = listOf("rig.scaffold", "eye/fitted"), writes = baseWrites, composableKinds = defaultComposable),
    BindingManifestSpec("eye-highlight", "eye.highlight", "highlight", "Highlight parallax", "eye",
        requires = listOf("rig.scaffold", "eye/fitted", "face/legacy"), writes = baseWrites, composableKinds = defaultComposable),
    BindingManifestSpec("eye-aftershock", "eye.aftershock", "aftershock", "Blink aftershock", "eye",
        requires = listOf("rig.scaffold", "eye/fitted"), writes = physicsWrites, composableKinds = physicsComposable),
    BindingManifestSpec("brow-shape", "brow.shape", "shape", "Brow shapes", "brow",
        requires = listOf("rig.scaffold", "brow/fitted"), writes = baseWrites, composableKinds = defaultComposable),
    BindingManifestSpec("brow-spring", "brow.spring", "spring", "Brow spring", "brow",
        requires = listOf("rig.scaffold", "brow/fitted"), writes = physicsWrites, composableKinds = physicsComposable),
    BindingManifestSpec("eye-emotion", "eye.emotion", "emotion", "Emotion eye shapes", "eye",
        requires = listOf("rig.scaffold", "eye/fitted"), writes = baseWrites, composableKinds = defaultComposable),
    BindingManifestSpec("mouth-vowel", "mouth.vowel", "vowel", "Mouth vowels", "mouth",
        requires = listOf("rig.scaffold", "mouth/fitted"), writes = baseWrites, composableKinds = defaultComposable),
    BindingManifestSpec("eye-gaze-lid", "eye.gaze-lid", "gaze", "Lids follow gaze", "eye",
        requires = listOf("rig.scaffold", "eye/fitted"), writes = baseWrites, composableKinds = defaultComposable,
        parametersJson = """[{"id":"ParamEyeBallLX","name":"Left gaze X","min":-1,"max":1,"default":0},{"id":"ParamEyeBallLY","name":"Left gaze Y","min":-1,"max":1,"default":0},{"id":"ParamEyeBallRX","name":"Right gaze X","min":-1,"max":1,"default":0},{"id":"ParamEyeBallRY","name":"Right gaze Y","min":-1,"max":1,"default":0}]"""),
    BindingManifestSpec("brow-blink", "brow.blink", "blink", "Brow blink", "brow",
        requires = listOf("rig.scaffold", "brow/fitted"), writes = baseWrites, composableKinds = defaultComposable),
    BindingManifestSpec("brow-lid", "brow.lid-coupling", "coupling", "Brow and lid together", "brow",
        requires = listOf("rig.scaffold", "brow/fitted", "eye/fitted"), writes = baseWrites, composableKinds = defaultComposable),
    BindingManifestSpec("mouth-cheek-lid", "mouth.cheek-lid", "coupling", "Cheeks lift lids", "mouth",
        requires = listOf("rig.scaffold", "mouth/fitted", "eye/fitted"), writes = baseWrites, composableKinds = defaultComposable),
    BindingManifestSpec("mouth-capture", "mouth.capture", "capture", "Mouth capture", "mouth",
        requires = listOf("rig.scaffold", "mouth/fitted"), writes = baseWrites, composableKinds = defaultComposable),
    BindingManifestSpec("mouth-tongue", "mouth.tongue", "tongue", "Mouth tongue", "mouth",
        requires = listOf("rig.scaffold", "mouth/fitted", "mouth.capture"), writes = baseWrites, composableKinds = defaultComposable),
    BindingManifestSpec("mouth-jaw", "mouth.jaw", "jaw", "Mouth jaw", "mouth",
        requires = listOf("rig.scaffold", "mouth/fitted"), writes = baseWrites, composableKinds = defaultComposable),
    BindingManifestSpec("mouth-depth", "mouth.interior-depth", "depth", "Mouth interior depth", "mouth",
        requires = listOf("rig.scaffold", "mouth/fitted", "face/legacy"), writes = baseWrites, composableKinds = defaultComposable),
    BindingManifestSpec("mouth-spring", "mouth.spring", "spring", "Mouth spring", "mouth",
        requires = listOf("rig.scaffold", "mouth/fitted", "mouth.jaw"), writes = physicsWrites, composableKinds = physicsComposable),
    BindingManifestSpec("mouth-outline", "mouth.outline", "outline", "Mouth outline", "mouth",
        requires = listOf("rig.scaffold"), exclusiveFamily = "mouth.outline",
        writes = baseWrites + listOf("textures", "authoring"), composableKinds = defaultComposable),
    BindingManifestSpec("cheek", "expression.cheek", "color", "Cheek", "expression",
        writes = baseWrites, creates = listOf("parameters"), modifies = listOf("mesh.offsets", "mesh.opacity"),
        composableKinds = defaultComposable),
    BindingManifestSpec("gloom", "expression.gloom", "gloom", "Gloom", "expression",
        writes = baseWrites, creates = listOf("parameters"), modifies = listOf("mesh.offsets", "mesh.opacity"),
        composableKinds = defaultComposable),
    BindingManifestSpec("expression-toggles", "expression.toggles", "toggles", "Expressions", "expression",
        exclusiveFamily = "expression.toggles", writes = listOf("*"),
        creates = listOf("parameters"), modifies = listOf("mesh.opacity", "physics.inputs"),
        composableKinds = physicsComposable),
    BindingManifestSpec("skin-clip", "face.skin-clip", "clip", "Skin clipping", "face",
        writes = listOf("mesh", "relations"), modifies = listOf("mesh.mask", "relations"),
        composableKinds = defaultComposable),
    BindingManifestSpec("face-neck", "face.neck", "tilt", "Neck tilt", "face",
        requires = listOf("rig.scaffold", "face/legacy"), writes = baseWrites, composableKinds = defaultComposable),
    BindingManifestSpec("face-depth-turn", "face.depth-turn", "turn", "Depth turn", "face",
        requires = listOf("rig.scaffold", "face/legacy"), writes = baseWrites, composableKinds = defaultComposable),
    BindingManifestSpec("body-bust", "body.bust", "bust", "Chest bounce", "body",
        requires = listOf("rig.scaffold", "body/legacy", "face/legacy"), writes = physicsWrites, composableKinds = physicsComposable),
    BindingManifestSpec("body-arms", "body.arms", "swing", "Arm swing", "body",
        requires = listOf("rig.scaffold", "body/legacy", "face/legacy"), writes = physicsWrites, composableKinds = physicsComposable),
    BindingManifestSpec("outfit-skirt", "outfit.skirt", "skirt", "Skirt sway", "outfit",
        requires = listOf("rig.scaffold", "body/legacy", "face/legacy"),
        exclusiveFamily = "chain:outfit.skirt", writes = physicsWrites,
        creates = listOf("parameters", "warpDeformers", "physicsGroups"),
        modifies = listOf("mesh.offsets", "deformer.keyforms", "hierarchy"),
        composableKinds = physicsComposable),
    BindingManifestSpec("outfit-ribbons", "outfit.ribbons", "ribbons", "Ribbon and pendant sway", "outfit",
        requires = listOf("rig.scaffold", "body/legacy", "face/legacy"),
        exclusiveFamily = "chain:outfit.ribbons", writes = physicsWrites,
        creates = listOf("parameters", "warpDeformers", "parts", "physicsGroups"),
        modifies = listOf("mesh.offsets", "deformer.keyforms", "hierarchy"),
        composableKinds = physicsComposable),
    BindingManifestSpec("outfit-creature", "outfit.creature", "creature", "Animal ears, tails and wings", "outfit",
        requires = listOf("rig.scaffold", "body/legacy", "face/legacy"),
        exclusiveFamily = "chain:outfit.creature", writes = physicsWrites,
        creates = listOf("parameters", "warpDeformers", "parts", "physicsGroups"),
        modifies = listOf("mesh.offsets", "deformer.keyforms", "hierarchy"),
        composableKinds = physicsComposable),
    BindingManifestSpec("see-through-eye", "eye.see-through", "visibility", "eye through bangs", "eye",
        writes = listOf("*"), creates = listOf("derivedMeshes"), modifies = listOf("mesh.opacity", "mesh.mask"),
        composableKinds = defaultComposable),
    BindingManifestSpec("see-through-brow", "brow.see-through", "visibility", "brow through bangs", "brow",
        writes = listOf("*"), creates = listOf("derivedMeshes"), modifies = listOf("mesh.opacity", "mesh.mask"),
        composableKinds = defaultComposable),
    BindingManifestSpec("mesh-split", "mesh.split", "split", "Mesh split", "mesh",
        writes = listOf("mesh", "part", "hierarchy"), creates = listOf("derivedMeshes"),
        modifies = listOf("mesh.visibility", "hierarchy"),
        composableKinds = listOf("mesh", "part", "hierarchy")),
)

val pluginPackages by tasks.registering {
    dependsOn(workerJar)
    val output = layout.buildDirectory.dir("plugin-packages")
    val packageVersion = project.version.toString()
    inputs.file(workerJar.flatMap { it.archiveFile })
    inputs.property("packageVersion", packageVersion)
    outputs.dir(output)
    doLast {
        fun jsonArray(items: List<String>): String = items.joinToString(",", "[", "]") { "\"$it\"" }
        fun pluginId(type: String, ver: Int): String =
            "builtin." + HexFormat.of().formatHex(type.toByteArray(Charsets.UTF_8)) + ".v$ver"

        val root = output.get().asFile.apply {
            deleteRecursively()
            mkdirs()
        }
        val runtime = root.resolve("shared/runtime.jar").apply { parentFile.mkdirs() }
        workerJar.get().archiveFile.get().asFile.copyTo(runtime, overwrite = true)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(runtime.readBytes()))

        val slugs = mutableListOf<String>()
        for (revision in 1..2) {
            val slug = "scaffold-$revision"
            slugs += slug
            val id = pluginId("rig.scaffold", revision)
            root.resolve("$slug/plugin.json").apply { parentFile.mkdirs() }.writeText("""
                {"protocol":1,"id":"$id","version":"$packageVersion","name":"Binding structure",
                 "commands":{"jvm":["${'$'}JAVA","-jar","runtime.jar"]},"files":{"runtime.jar":"$digest"},
                 "algorithms":[{"type":"$id/structure","aliases":["rig.scaffold"],"version":$revision,"name":"Binding structure","category":"body",
                   "targets":["mesh"],"exclusiveFamily":"rig.scaffold","reads":["*"],"writes":["mesh","part","deformer","hierarchy"],
                   "creates":["meshes","parts","warpDeformers","rotationDeformers"],"modifies":["hierarchy"],
                   "composableKinds":["mesh","part","deformer","hierarchy"],"configSchema":{"type":"object"},"defaults":{}}]}
            """.trimIndent(), Charsets.UTF_8)
        }
        for (spec in bindingManifestSpecs) {
            slugs += spec.slug
            val id = pluginId(spec.type, 1)
            val provides = if (spec.provides.isEmpty()) "" else """"provides":${jsonArray(spec.provides)},"""
            val exclusive = spec.exclusiveFamily?.let { """"exclusiveFamily":"$it",""" } ?: ""
            val creates = if (spec.creates.isEmpty()) "" else """"creates":${jsonArray(spec.creates)},"""
            val modifies = if (spec.modifies.isEmpty()) "" else """"modifies":${jsonArray(spec.modifies)},"""
            val parameters = spec.parametersJson?.let { """"parameters":$it,""" } ?: ""
            root.resolve("${spec.slug}/plugin.json").apply { parentFile.mkdirs() }.writeText("""
                {"protocol":1,"id":"$id","version":"$packageVersion","name":"${spec.name}",
                 "commands":{"jvm":["${'$'}JAVA","-jar","runtime.jar"]},"files":{"runtime.jar":"$digest"},
                 "algorithms":[{"type":"$id/${spec.entrypoint}","aliases":["${spec.type}"],"version":1,"name":"${spec.name}","category":"${spec.category}",
                   "targets":["mesh"],"requires":${jsonArray(spec.requires)},$provides$exclusive"contextVersion":2,
                   "reads":["*"],"writes":${jsonArray(spec.writes)},$creates$modifies"composableKinds":${jsonArray(spec.composableKinds)},$parameters
                   "configSchema":{"type":"object"},"defaults":{}}]}
            """.trimIndent(), Charsets.UTF_8)
        }
        root.resolve("index.txt").writeText(slugs.sorted().joinToString("\n", postfix = "\n"), Charsets.UTF_8)
    }
}

val bindingPluginsElements by configurations.creating {
    isCanBeConsumed = true
    isCanBeResolved = false
}

artifacts {
    add(bindingPluginsElements.name, pluginPackages.map { it.outputs.files.singleFile }) {
        builtBy(pluginPackages)
    }
}
