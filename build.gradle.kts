import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

plugins {
	kotlin("jvm") version "2.4.10"
	kotlin("plugin.serialization") version "2.4.10"
	id("org.jetbrains.compose") version "1.11.1"
	id("org.jetbrains.kotlin.plugin.compose") version "2.4.10"
}

group = "io.github.psd2live"
version = "3.7.0"

// Cubism proprietary binaries under src/main/resources/cubism/ are opt-in only.
// Default jars/distributions must NOT embed them. Enable with:
//   -Ppsd2live.includeCubism=true
// or env PSD2LIVE_INCLUDE_CUBISM=true
val includeCubism: Boolean =
	(findProperty("psd2live.includeCubism")?.toString()?.equals("true", ignoreCase = true) == true) ||
		(System.getenv("PSD2LIVE_INCLUDE_CUBISM")?.equals("true", ignoreCase = true) == true)

// An ffmpeg shipped with the app for video and animated image export, opt-in like Cubism: the directory
// (ffmpeg executable plus its license) is copied to the app's resources/ffmpeg/. Enable with:
//   -Ppsd2live.ffmpegDir=<dir>
// or env PSD2LIVE_FFMPEG_DIR=<dir>
val bundledFfmpeg: File? =
	(findProperty("psd2live.ffmpegDir")?.toString() ?: System.getenv("PSD2LIVE_FFMPEG_DIR"))?.takeIf { it.isNotBlank() }?.let(::file)

val hostOs = System.getProperty("os.name").lowercase().let { os ->
	when {
		os.contains("mac") || os.contains("darwin") -> "macos"
		os.contains("linux") || os.contains("nix") -> "linux"
		else -> "windows"
	}
}
val hostArm = System.getProperty("os.arch").lowercase().let { it.startsWith("aarch64") || it.startsWith("arm") }

// Compose refuses to package on Homebrew's JDK unless this is set. The macOS app does not use the jpackage
// launcher (packaging/macos launches the bundled runtime itself), so that check does not apply here.
if (hostOs == "macos") {
	System.setProperty("compose.desktop.packaging.checkJdkVendor", "false")
	extensions.extraProperties.set("compose.desktop.packaging.checkJdkVendor", "false")
}

kotlin {
	jvmToolchain(21)
	// Kotlin's redundant-null-check bytecode pass can spend minutes in FastAnalyzer on the large
	// Compose/editor methods in this project, especially on a fresh Windows build. HotSpot still
	// optimizes the resulting bytecode at runtime; skipping this compiler pass keeps clean builds usable.
	compilerOptions {
		freeCompilerArgs.add("-Xno-optimize")
	}
}

dependencies {
	implementation(project(":algorithm-api"))
	implementation(project(path = ":algorithms:binding", configuration = "expressionV2Elements"))
	implementation(project(":algorithm-worker"))
	implementation(project(":algorithms:physics"))
	implementation(project(":algorithms:motion"))
	implementation(project(":algorithms:swing"))
	implementation(project(":algorithms:simulation"))
	implementation(project(":algorithms:binding"))
	implementation(project(path = ":algorithms:binding", configuration = "lidFadeV3Elements"))
	implementation(project(path = ":algorithms:binding", configuration = "eyeLegacyElements"))
	implementation(project(path = ":algorithms:binding", configuration = "eyeFittedElements"))
	implementation(project(path = ":algorithms:binding", configuration = "eyeFittedV4Elements"))
	implementation(project(path = ":algorithms:binding", configuration = "eyeFittedV5Elements"))
	implementation(project(path = ":algorithms:binding", configuration = "eyeSquashElements"))
	implementation(project(path = ":algorithms:binding", configuration = "eyeSquashV3Elements"))
	implementation(project(path = ":algorithms:binding", configuration = "eyeSquashV4Elements"))
	implementation(project(path = ":algorithms:binding", configuration = "eyeWideElements"))
	implementation(project(path = ":algorithms:binding", configuration = "eyeWideV3Elements"))
	implementation(project(path = ":algorithms:binding", configuration = "eyeSmileElements"))
	implementation(project(path = ":algorithms:binding", configuration = "eyeSmileV3Elements"))
	implementation(project(path = ":algorithms:binding", configuration = "eyeAftershockElements"))
	implementation(project(path = ":algorithms:binding", configuration = "eyeAftershockV3Elements"))
	implementation(project(path = ":algorithms:binding", configuration = "eyeAftershockV4Elements"))
	implementation(project(path = ":algorithms:binding", configuration = "eyeGazeLidElements"))
	implementation(project(path = ":algorithms:binding", configuration = "eyeGazeLidV3Elements"))
	implementation(project(path = ":algorithms:binding", configuration = "eyeEmotionElements"))
	implementation(project(path = ":algorithms:binding", configuration = "eyeEmotionV3Elements"))
	implementation(project(path = ":algorithms:binding", configuration = "eyeHighlightElements"))
	implementation(project(path = ":algorithms:binding", configuration = "eyeHighlightV3Elements"))
	implementation(project(path = ":algorithms:binding", configuration = "mouthCheekLidElements"))
	implementation(project(path = ":algorithms:binding", configuration = "mouthCheekLidV3Elements"))
	implementation(project(path = ":algorithms:binding", configuration = "mouthCheekLidV4Elements"))
	implementation(project(path = ":algorithms:binding", configuration = "mouthCheekLidV5Elements"))
	implementation(project(path = ":algorithms:binding", configuration = "mouthCheekLidV6Elements"))
	implementation(project(path = ":algorithms:binding", configuration = "mouthCheekLidV7Elements"))
	implementation(project(path = ":algorithms:binding", configuration = "skirtElements"))
	implementation(project(path = ":algorithms:binding", configuration = "ribbonsElements"))
	implementation(project(path = ":algorithms:binding", configuration = "creatureElements"))
	implementation(project(path = ":algorithms:binding", configuration = "browSpringElements"))
	implementation(project(path = ":algorithms:binding", configuration = "browSpringV3Elements"))
	implementation(project(path = ":algorithms:binding", configuration = "browSpringV4Elements"))
	implementation(project(path = ":algorithms:binding", configuration = "mouthSpringElements"))
	implementation(project(path = ":algorithms:binding", configuration = "mouthSpringV3Elements"))
	implementation(project(path = ":algorithms:binding", configuration = "mouthSpringV4Elements"))
	implementation(project(path = ":algorithms:binding", configuration = "mouthSpringV5Elements"))
	implementation(project(path = ":algorithms:binding", configuration = "mouthSpringV6Elements"))
	implementation(project(path = ":algorithms:binding", configuration = "mouthSpringV7Elements"))
	implementation(project(path = ":algorithms:binding", configuration = "mouthSpringV8Elements"))
	implementation(project(path = ":algorithms:binding", configuration = "bodyLegacyV2Elements"))
	implementation(project(path = ":algorithms:binding", configuration = "armSwingElements"))
	implementation(project(path = ":algorithms:binding", configuration = "armSwingV3Elements"))
	implementation(project(path = ":algorithms:binding", configuration = "faceLegacyV2Elements"))
	implementation(project(path = ":algorithms:binding", configuration = "browLegacyV2Elements"))
	implementation(project(path = ":algorithms:binding", configuration = "mouthFittedV2Elements"))
	implementation(project(path = ":algorithms:binding", configuration = "mouthFittedV3Elements"))
	implementation(project(path = ":algorithms:binding", configuration = "mouthFittedV4Elements"))
	implementation(project(path = ":algorithms:binding", configuration = "mouthFittedV5Elements"))
	implementation(project(path = ":algorithms:binding", configuration = "mouthVowelElements"))
	implementation(project(path = ":algorithms:binding", configuration = "mouthVowelV3Elements"))
	implementation(project(path = ":algorithms:binding", configuration = "mouthVowelV4Elements"))
	implementation(project(path = ":algorithms:binding", configuration = "mouthVowelV5Elements"))
	implementation(project(path = ":algorithms:binding", configuration = "mouthVowelV6Elements"))
	implementation(project(path = ":algorithms:binding", configuration = "mouthCaptureElements"))
	implementation(project(path = ":algorithms:binding", configuration = "mouthCaptureV3Elements"))
	implementation(project(path = ":algorithms:binding", configuration = "mouthCaptureV4Elements"))
	implementation(project(path = ":algorithms:binding", configuration = "mouthCaptureV5Elements"))
	implementation(project(path = ":algorithms:binding", configuration = "mouthJawElements"))
	implementation(project(path = ":algorithms:binding", configuration = "mouthJawV3Elements"))
	implementation(project(path = ":algorithms:binding", configuration = "mouthJawV4Elements"))
	implementation(project(path = ":algorithms:binding", configuration = "mouthJawV5Elements"))
	implementation(project(path = ":algorithms:binding", configuration = "mouthJawV6Elements"))
	implementation(project(path = ":algorithms:binding", configuration = "mouthJawV7Elements"))
	implementation(project(path = ":algorithms:binding", configuration = "cheekElements"))
	implementation(project(path = ":algorithms:binding", configuration = "cheekV3Elements"))
	implementation(project(path = ":algorithms:binding", configuration = "gloomV2Elements"))
	implementation(project(path = ":algorithms:binding", configuration = "gloomV3Elements"))
	implementation(project(path = ":algorithms:binding", configuration = "mouthTongueElements"))
	implementation(project(path = ":algorithms:binding", configuration = "mouthTongueV3Elements"))
	implementation(project(path = ":algorithms:binding", configuration = "mouthTongueV4Elements"))
	implementation(project(path = ":algorithms:binding", configuration = "mouthTongueV5Elements"))
	implementation(project(path = ":algorithms:binding", configuration = "mouthDepthElements"))
	implementation(project(path = ":algorithms:binding", configuration = "mouthDepthV3Elements"))
	implementation(project(path = ":algorithms:binding", configuration = "mouthDepthV4Elements"))
	implementation(project(path = ":algorithms:binding", configuration = "mouthDepthV5Elements"))
	implementation(project(path = ":algorithms:binding", configuration = "fittedFaceV2Elements"))
	implementation(project(path = ":algorithms:binding", configuration = "fittedFaceV3Elements"))
	implementation(project(path = ":algorithms:binding", configuration = "browFittedElements"))
	implementation(project(path = ":algorithms:binding", configuration = "browFittedV3Elements"))
	implementation(project(path = ":algorithms:binding", configuration = "depthTurnV2Elements"))
	implementation(project(path = ":algorithms:binding", configuration = "browShapeElements"))
	implementation(project(path = ":algorithms:binding", configuration = "browShapeV3Elements"))
	implementation(project(path = ":algorithms:binding", configuration = "browBlinkElements"))
	implementation(project(path = ":algorithms:binding", configuration = "browBlinkV3Elements"))
	implementation(project(path = ":algorithms:binding", configuration = "browLidElements"))
	implementation(project(path = ":algorithms:binding", configuration = "browLidV3Elements"))
	implementation(project(path = ":algorithms:binding", configuration = "browLidV4Elements"))
	implementation(project(path = ":algorithms:binding", configuration = "browLidV5Elements"))
	implementation(platform("io.ktor:ktor-bom:3.5.1"))
	// Engine ported from Umamo (format, runtime, interop, render, edit); it brings its own libraries.
	implementation(project(":umamo"))
	implementation(project(path = ":umamo", configuration = "contourVariantsElements"))
	// Neutral rig IR, export framework and targets (see docs/zh/spec/EXPORT_TARGETS.md).
	implementation(project(":targets:cubism"))
	implementation(project(":targets:raster"))
	implementation(project(":targets:psd"))
	implementation(project(":targets:spine"))
	implementation(project(":targets:runtime"))
	implementation(project(":format-eval"))
	implementation(project(":targets:web"))
	implementation(project(":targets:gltf"))
	implementation(project(":targets:unity"))
	implementation(project(":targets:dragonbones"))

	// LWJGL (OpenGL rendering pipeline)
	val lwjglNatives = "natives-$hostOs" + if (hostArm) "-arm64" else ""
	implementation(platform("org.lwjgl:lwjgl-bom:3.4.2"))
	implementation("org.lwjgl:lwjgl")
	implementation("org.lwjgl:lwjgl-opengl")
	// The editing canvas renders on its own hidden-window GL context (io.github.psd2live.render).
	implementation("org.lwjgl:lwjgl-glfw")
	runtimeOnly("org.lwjgl:lwjgl::$lwjglNatives")
	runtimeOnly("org.lwjgl:lwjgl-opengl::$lwjglNatives")
	runtimeOnly("org.lwjgl:lwjgl-glfw::$lwjglNatives")
	implementation("io.modelcontextprotocol:kotlin-sdk-server:0.15.0")
	implementation("io.ktor:ktor-server-cio")
	implementation("io.ktor:ktor-server-auth")
	implementation("io.ktor:ktor-server-content-negotiation")
	implementation("io.ktor:ktor-server-sse")
	implementation("io.ktor:ktor-serialization-kotlinx-json")
	runtimeOnly("org.slf4j:slf4j-nop:2.0.17")
	implementation("net.java.dev.jna:jna:5.18.0")
	// The main window's custom title bar on the JetBrains Runtime (Compose already brings this version).
	implementation("org.jetbrains.runtime:jbr-api:1.9.0")
	implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
	implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
	implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.11.0")
	implementation(compose.desktop.currentOs)
	implementation("org.jetbrains.compose.runtime:runtime:1.11.1")
	implementation("org.jetbrains.compose.foundation:foundation:1.11.1")
	implementation("org.jetbrains.compose.ui:ui:1.11.1")
	implementation("org.jetbrains.compose.material:material:1.11.1")
	implementation(project(path = ":algorithms:binding", configuration = "mouthOutlineElements"))
	implementation(project(path = ":algorithms:binding", configuration = "mouthOutlineV2Elements"))
	implementation(project(path = ":algorithms:binding", configuration = "mouthOutlineV4Elements"))
	implementation(project(path = ":algorithms:binding", configuration = "mouthOutlineV3Elements"))
	implementation(project(path = ":algorithms:binding", configuration = "mouthOutlineV5Elements"))
	implementation(project(path = ":algorithms:binding", configuration = "mouthOutlineV6Elements"))
	implementation(project(path = ":algorithms:binding", configuration = "mouthOutlineV7Elements"))
	testImplementation(kotlin("test"))
}

// JNA bundles native libraries for every platform. Resolve them with only the build
// host's, so run, tests and every Compose package (app image, Exe, Msi, Deb) carry just those.
abstract class KeepHostNatives : TransformAction<KeepHostNatives.Parameters> {
	interface Parameters : TransformParameters {
		@get:Input val jnaDir: Property<String>
	}

	@get:InputArtifact
	abstract val input: Provider<FileSystemLocation>

	override fun transform(outputs: TransformOutputs) {
		val jar = input.get().asFile
		if (!jar.name.startsWith("jna-")) {
			outputs.file(input)
			return
		}
		val jnaDir = "com/sun/jna/${parameters.jnaDir.get()}/"
		// Platform directories are the hyphenated ones (linux-x86-64); ptr/, win32/ etc. hold classes.
		val jnaPlatform = Regex("com/sun/jna/[^/]+-[^/]+/.*")
		fun keep(name: String) = when {
			name.matches(jnaPlatform) -> name.startsWith(jnaDir)
			else -> true
		}
		ZipFile(jar).use { zin ->
			ZipOutputStream(outputs.file(jar.name).outputStream().buffered()).use { zout ->
				for (entry in zin.entries()) {
					if (!keep(entry.name)) continue
					zout.putNextEntry(ZipEntry(entry.name).apply { time = entry.time })
					zin.getInputStream(entry).use { it.copyTo(zout) }
					zout.closeEntry()
				}
			}
		}
	}
}

val hostNativesOnly = Attribute.of("psd2live.hostNativesOnly", Boolean::class.javaObjectType)
dependencies {
	attributesSchema { attribute(hostNativesOnly) }
	artifactTypes.getByName("jar") { attributes.attribute(hostNativesOnly, false) }
	registerTransform(KeepHostNatives::class) {
		from.attribute(hostNativesOnly, false)
		to.attribute(hostNativesOnly, true)
		parameters {
			val arch = if (hostArm) "aarch64" else "x86-64"
			jnaDir.set(if (hostOs == "windows") "win32-$arch" else "${hostOs.replace("macos", "darwin")}-$arch")
		}
	}
}
configurations.runtimeClasspath { attributes.attribute(hostNativesOnly, true) }

tasks.named("compileTestKotlin").configure {
	enabled = true
}

// Test classes run in parallel JVMs. Each fork already uses every core for coroutine work, so a quarter of
// the cores (at most 8 forks of 2 GiB) is enough; -Ppsd2live.testForks=N overrides it, and -Ppsd2live.testHeap
// the heap (for tools on large inputs).
val testForks = providers.gradleProperty("psd2live.testForks").map(String::toInt)
	.orElse(providers.provider { (Runtime.getRuntime().availableProcessors() / 4).coerceIn(1, 8) })

tasks.withType<Test>().configureEach {
	useJUnitPlatform()
	// Development tools measure this invocation and write artifacts outside test reports.
	// Never substitute a cached/skipped test task for an actual measurement.
	val toolsEnabled = providers.environmentVariable("PSD2LIVE_TOOLS").orNull == "1"
	inputs.property("developmentTools", toolsEnabled)
	if (toolsEnabled) {
		outputs.upToDateWhen { false }
		outputs.doNotCacheIf("Development tools require fresh execution") { true }
	}
	maxHeapSize = providers.gradleProperty("psd2live.testHeap").getOrElse("2g")
	// `--tests` names one class from either module; the other module simply has nothing to run.
	filter.isFailOnNoMatchingTests = false
	maxParallelForks = testForks.get()
	// App settings use Java Preferences, which the platform shares between processes (and with the
	// user's real settings); every test JVM gets its own in-memory store instead.
	systemProperty("java.util.prefs.PreferencesFactory", "io.github.psd2live.testing.MemoryPreferencesFactory")
	// The Rust runtime, when built with `cargo build --release` in runtime/; tests that need it skip without it.
	systemProperty("psd2live.runtime.dir", file("runtime/target/release").absolutePath)
	// CI keeps no test reports, so a failure's message and stack must reach the log.
	testLogging {
		events(org.gradle.api.tasks.testing.logging.TestLogEvent.FAILED)
		exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
		showStackTraces = true
	}
}

tasks.test {
	enabled = true
}

tasks.register<Test>("testFittedFaceLatticesPackage") {
	val packageDirectory = project(":algorithms:binding").layout.buildDirectory.dir("plugin-fitted-face-lattices")
	dependsOn(":algorithms:binding:pluginFittedFaceLattices")
	inputs.dir(packageDirectory)
	testClassesDirs = sourceSets.test.get().output.classesDirs
	classpath = sourceSets.test.get().runtimeClasspath
	filter.includeTestsMatching("io.github.psd2live.core.FittedFaceLatticeParityTest")
	systemProperty("psd2live.fittedFaceLatticesPackage", packageDirectory.get().asFile.absolutePath)
}


// Keep Cubism out of classpath resources unless explicitly opted in.
tasks.processResources {
    dependsOn(":algorithms:binding:pluginMouthLegacy")
    from(project(":algorithms:binding").layout.buildDirectory.dir("plugin-mouth-legacy")) {
        into("algorithm-plugins/mouth-legacy")
    }
    for (revision in 1..2) {
        dependsOn(":algorithms:binding:pluginScaffold$revision")
        from(project(":algorithms:binding").layout.buildDirectory.dir("plugin-scaffold-$revision")) {
            into("algorithm-plugins/scaffold-$revision")
        }
    }
    dependsOn(":algorithms:binding:pluginBust")
    from(project(":algorithms:binding").layout.buildDirectory.dir("plugin-bust")) {
        into("algorithm-plugins/body-bust")
    }
    dependsOn(":algorithms:binding:pluginSkirt")
    from(project(":algorithms:binding").layout.buildDirectory.dir("plugin-outfit-skirt")) {
        into("algorithm-plugins/outfit-skirt")
    }
    dependsOn(":algorithms:binding:pluginRibbons")
    from(project(":algorithms:binding").layout.buildDirectory.dir("plugin-outfit-ribbons")) {
        into("algorithm-plugins/outfit-ribbons")
    }
    dependsOn(":algorithms:binding:pluginCreature")
    from(project(":algorithms:binding").layout.buildDirectory.dir("plugin-outfit-creature")) {
        into("algorithm-plugins/outfit-creature")
    }
    dependsOn(":algorithms:binding:pluginHairChains")
    from(project(":algorithms:binding").layout.buildDirectory.dir("plugin-hair-chains")) {
        into("algorithm-plugins/hair-chains")
    }
    dependsOn(":algorithms:binding:pluginFaceNeck")
    from(project(":algorithms:binding").layout.buildDirectory.dir("plugin-face-neck")) {
        into("algorithm-plugins/face-neck")
    }
    dependsOn(":algorithms:binding:pluginHairLegacy")
    from(project(":algorithms:binding").layout.buildDirectory.dir("plugin-hair-legacy")) {
        into("algorithm-plugins/hair-legacy")
    }
    dependsOn(":algorithms:binding:pluginMouthOutlineV3")
    from(project(":algorithms:binding").layout.buildDirectory.dir("plugin-mouth-outline-v3")) {
        into("algorithm-plugins/mouth-outline-v3")
    }
    dependsOn(":algorithms:binding:pluginMouthOutlineV5")
    from(project(":algorithms:binding").layout.buildDirectory.dir("plugin-mouth-outline-v5")) {
        into("algorithm-plugins/mouth-outline-v5")
    }
    dependsOn(":algorithms:binding:pluginMouthOutlineV6")
    from(project(":algorithms:binding").layout.buildDirectory.dir("plugin-mouth-outline-v6")) {
        into("algorithm-plugins/mouth-outline-v6")
    }
    dependsOn(":algorithms:binding:pluginMouthOutlineV7")
    from(project(":algorithms:binding").layout.buildDirectory.dir("plugin-mouth-outline-v7")) {
        into("algorithm-plugins/mouth-outline-v7")
    }
    dependsOn(":algorithms:binding:pluginMouthOutlineV2")
    from(project(":algorithms:binding").layout.buildDirectory.dir("plugin-mouth-outline-v2")) {
        into("algorithm-plugins/mouth-outline-v2")
    }
    dependsOn(":algorithms:binding:pluginMouthOutlineV4")
    from(project(":algorithms:binding").layout.buildDirectory.dir("plugin-mouth-outline-v4")) {
        into("algorithm-plugins/mouth-outline-v4")
    }
    dependsOn(":algorithms:binding:pluginMouthVowelV3")
    from(project(":algorithms:binding").layout.buildDirectory.dir("plugin-mouth-vowel-v3")) {
        into("algorithm-plugins/mouth-vowel-v3")
    }
    dependsOn(":algorithms:binding:pluginMouthVowelV4")
    from(project(":algorithms:binding").layout.buildDirectory.dir("plugin-mouth-vowel-v4")) {
        into("algorithm-plugins/mouth-vowel-v4")
    }
    dependsOn(":algorithms:binding:pluginMouthVowelV5")
    from(project(":algorithms:binding").layout.buildDirectory.dir("plugin-mouth-vowel-v5")) {
        into("algorithm-plugins/mouth-vowel-v5")
    }
    dependsOn(":algorithms:binding:pluginMouthVowelV6")
    from(project(":algorithms:binding").layout.buildDirectory.dir("plugin-mouth-vowel-v6")) {
        into("algorithm-plugins/mouth-vowel-v6")
    }
    dependsOn(":algorithms:binding:pluginMouthVowel")
    from(project(":algorithms:binding").layout.buildDirectory.dir("plugin-mouth-vowel")) {
        into("algorithm-plugins/mouth-vowel")
    }
    dependsOn(":algorithms:binding:pluginMouthCapture")
    from(project(":algorithms:binding").layout.buildDirectory.dir("plugin-mouth-capture")) {
        into("algorithm-plugins/mouth-capture")
    }
    dependsOn(":algorithms:binding:pluginMouthCaptureV3")
    from(project(":algorithms:binding").layout.buildDirectory.dir("plugin-mouth-capture-v3")) {
        into("algorithm-plugins/mouth-capture-v3")
    }
    dependsOn(":algorithms:binding:pluginMouthCaptureV4")
    from(project(":algorithms:binding").layout.buildDirectory.dir("plugin-mouth-capture-v4")) {
        into("algorithm-plugins/mouth-capture-v4")
    }
    dependsOn(":algorithms:binding:pluginMouthCaptureV5")
    from(project(":algorithms:binding").layout.buildDirectory.dir("plugin-mouth-capture-v5")) {
        into("algorithm-plugins/mouth-capture-v5")
    }
    dependsOn(":algorithms:binding:pluginMouthJawV3")
    from(project(":algorithms:binding").layout.buildDirectory.dir("plugin-mouth-jaw-v3")) {
        into("algorithm-plugins/mouth-jaw-v3")
    }
    dependsOn(":algorithms:binding:pluginMouthJawV4")
    from(project(":algorithms:binding").layout.buildDirectory.dir("plugin-mouth-jaw-v4")) {
        into("algorithm-plugins/mouth-jaw-v4")
    }
    dependsOn(":algorithms:binding:pluginMouthJawV5")
    from(project(":algorithms:binding").layout.buildDirectory.dir("plugin-mouth-jaw-v5")) {
        into("algorithm-plugins/mouth-jaw-v5")
    }
    dependsOn(":algorithms:binding:pluginMouthJawV6")
    from(project(":algorithms:binding").layout.buildDirectory.dir("plugin-mouth-jaw-v6")) {
        into("algorithm-plugins/mouth-jaw-v6")
    }
    dependsOn(":algorithms:binding:pluginMouthJawV7")
    from(project(":algorithms:binding").layout.buildDirectory.dir("plugin-mouth-jaw-v7")) {
        into("algorithm-plugins/mouth-jaw-v7")
    }
    dependsOn(":algorithms:binding:pluginMouthJaw")
    from(project(":algorithms:binding").layout.buildDirectory.dir("plugin-mouth-jaw")) {
        into("algorithm-plugins/mouth-jaw")
    }

    dependsOn(":algorithms:binding:pluginCheekV2", ":algorithms:binding:pluginCheekV3")
    from(project(":algorithms:binding").layout.buildDirectory.dir("plugin-cheek-v2")) {
        into("algorithm-plugins/cheek-v2")
    }
    from(project(":algorithms:binding").layout.buildDirectory.dir("plugin-cheek-v3")) {
        into("algorithm-plugins/cheek-v3")
    }
    dependsOn(":algorithms:binding:pluginGloomV2")
    from(project(":algorithms:binding").layout.buildDirectory.dir("plugin-gloom-v2")) {
        into("algorithm-plugins/gloom-v2")
    }
    dependsOn(":algorithms:binding:pluginGloomV3")
    from(project(":algorithms:binding").layout.buildDirectory.dir("plugin-gloom-v3")) {
        into("algorithm-plugins/gloom-v3")
    }
    dependsOn(":algorithms:binding:pluginMouthTongue")
    from(project(":algorithms:binding").layout.buildDirectory.dir("plugin-mouth-tongue")) {
        into("algorithm-plugins/mouth-tongue")
    }
    dependsOn(":algorithms:binding:pluginMouthTongueV3")
    from(project(":algorithms:binding").layout.buildDirectory.dir("plugin-mouth-tongue-v3")) {
        into("algorithm-plugins/mouth-tongue-v3")
    }
    dependsOn(":algorithms:binding:pluginMouthTongueV4")
    from(project(":algorithms:binding").layout.buildDirectory.dir("plugin-mouth-tongue-v4")) {
        into("algorithm-plugins/mouth-tongue-v4")
    }
    dependsOn(":algorithms:binding:pluginMouthTongueV5")
    from(project(":algorithms:binding").layout.buildDirectory.dir("plugin-mouth-tongue-v5")) {
        into("algorithm-plugins/mouth-tongue-v5")
    }
    dependsOn(":algorithms:binding:pluginMouthDepth")
    from(project(":algorithms:binding").layout.buildDirectory.dir("plugin-mouth-depth")) {
        into("algorithm-plugins/mouth-depth")
    }
    dependsOn(":algorithms:binding:pluginMouthDepthV3")
    from(project(":algorithms:binding").layout.buildDirectory.dir("plugin-mouth-depth-v3")) {
        into("algorithm-plugins/mouth-depth-v3")
    }
    dependsOn(":algorithms:binding:pluginMouthDepthV4")
    from(project(":algorithms:binding").layout.buildDirectory.dir("plugin-mouth-depth-v4")) {
        into("algorithm-plugins/mouth-depth-v4")
    }
    dependsOn(":algorithms:binding:pluginMouthDepthV5")
    from(project(":algorithms:binding").layout.buildDirectory.dir("plugin-mouth-depth-v5")) {
        into("algorithm-plugins/mouth-depth-v5")
    }
    dependsOn(":algorithms:binding:pluginMouthFittedV3")
    dependsOn(":algorithms:binding:pluginMouthFittedV2")
    dependsOn(":algorithms:binding:pluginBrowFittedV2")
    from(project(":algorithms:binding").layout.buildDirectory.dir("plugin-brow-fitted-v2")) {
        into("algorithm-plugins/brow-fitted-v2")
    }
    dependsOn(":algorithms:binding:pluginBrowFittedV3")
    from(project(":algorithms:binding").layout.buildDirectory.dir("plugin-brow-fitted-v3")) {
        into("algorithm-plugins/brow-fitted-v3")
    }
    dependsOn(":algorithms:binding:pluginArmSwingV3")
    from(project(":algorithms:binding").layout.buildDirectory.dir("plugin-arm-swing-v3")) {
        into("algorithm-plugins/body-arms-v3")
    }
    dependsOn(":algorithms:binding:pluginArmSwing")
    from(project(":algorithms:binding").layout.buildDirectory.dir("plugin-arm-swing")) {
        into("algorithm-plugins/body-arms")
    }
    dependsOn(":algorithms:binding:pluginBrowBlink")
    from(project(":algorithms:binding").layout.buildDirectory.dir("plugin-brow-blink")) {
        into("algorithm-plugins/brow-blink")
    }
    dependsOn(":algorithms:binding:pluginBrowBlinkV3")
    from(project(":algorithms:binding").layout.buildDirectory.dir("plugin-brow-blink-v3")) {
        into("algorithm-plugins/brow-blink-v3")
    }
    dependsOn(":algorithms:binding:pluginBrowLidV3")
    from(project(":algorithms:binding").layout.buildDirectory.dir("plugin-brow-lid-v3")) {
        into("algorithm-plugins/brow-lid-v3")
    }
    dependsOn(":algorithms:binding:pluginBrowLidV4")
    from(project(":algorithms:binding").layout.buildDirectory.dir("plugin-brow-lid-v4")) {
        into("algorithm-plugins/brow-lid-v4")
    }
    dependsOn(":algorithms:binding:pluginBrowLidV5")
    from(project(":algorithms:binding").layout.buildDirectory.dir("plugin-brow-lid-v5")) {
        into("algorithm-plugins/brow-lid-v5")
    }
    dependsOn(":algorithms:binding:pluginBrowLid")
    from(project(":algorithms:binding").layout.buildDirectory.dir("plugin-brow-lid")) {
        into("algorithm-plugins/brow-lid")
    }
    dependsOn(":algorithms:binding:pluginBrowShape")
    from(project(":algorithms:binding").layout.buildDirectory.dir("plugin-brow-shape")) {
        into("algorithm-plugins/brow-shape")
    }
    dependsOn(":algorithms:binding:pluginBrowShapeV3")
    from(project(":algorithms:binding").layout.buildDirectory.dir("plugin-brow-shape-v3")) {
        into("algorithm-plugins/brow-shape-v3")
    }
    dependsOn(":algorithms:binding:pluginFittedFaceV2")
    from(project(":algorithms:binding").layout.buildDirectory.dir("plugin-fitted-face-v2")) {
        into("algorithm-plugins/fitted-face-v2")
    }
    dependsOn(":algorithms:binding:pluginFittedFaceV3")
    from(project(":algorithms:binding").layout.buildDirectory.dir("plugin-fitted-face-v3")) {
        into("algorithm-plugins/fitted-face-v3")
    }
    dependsOn(":algorithms:binding:pluginDepthTurnV2")
    from(project(":algorithms:binding").layout.buildDirectory.dir("plugin-depth-turn-v2")) {
        into("algorithm-plugins/depth-turn-v2")
    }
    from(project(":algorithms:binding").layout.buildDirectory.dir("plugin-mouth-fitted-v2")) {
        into("algorithm-plugins/mouth-fitted-v2")
    }
    from(project(":algorithms:binding").layout.buildDirectory.dir("plugin-mouth-fitted-v3")) {
        into("algorithm-plugins/mouth-fitted-v3")
    }
    dependsOn(":algorithms:binding:pluginMouthFittedV4")
    from(project(":algorithms:binding").layout.buildDirectory.dir("plugin-mouth-fitted-v4")) {
        into("algorithm-plugins/mouth-fitted-v4")
    }
    dependsOn(":algorithms:binding:pluginMouthFittedV5")
    from(project(":algorithms:binding").layout.buildDirectory.dir("plugin-mouth-fitted-v5")) {
        into("algorithm-plugins/mouth-fitted-v5")
    }
    dependsOn(":algorithms:binding:pluginEyeLidFade")
    from(project(":algorithms:binding").layout.buildDirectory.dir("plugin-eye-lid-fade")) {
        into("algorithm-plugins/eye-lid-fade")
    }
    dependsOn(":algorithms:binding:pluginEyeLidFadeV3")
    from(project(":algorithms:binding").layout.buildDirectory.dir("plugin-eye-lid-fade-v3")) {
        into("algorithm-plugins/eye-lid-fade-v3")
    }
    dependsOn(":algorithms:binding:pluginEyeSmileV3")
    from(project(":algorithms:binding").layout.buildDirectory.dir("plugin-eye-smile-v3")) {
        into("algorithm-plugins/eye-smile-v3")
    }
    dependsOn(":algorithms:binding:pluginEyeSmile")
    from(project(":algorithms:binding").layout.buildDirectory.dir("plugin-eye-smile")) {
        into("algorithm-plugins/eye-smile")
    }
    dependsOn(":algorithms:binding:pluginEyeAftershock")
    from(project(":algorithms:binding").layout.buildDirectory.dir("plugin-eye-aftershock")) {
        into("algorithm-plugins/eye-aftershock")
    }
    dependsOn(":algorithms:binding:pluginEyeAftershockV3")
    from(project(":algorithms:binding").layout.buildDirectory.dir("plugin-eye-aftershock-v3")) {
        into("algorithm-plugins/eye-aftershock-v3")
    }
    dependsOn(":algorithms:binding:pluginEyeAftershockV4")
    from(project(":algorithms:binding").layout.buildDirectory.dir("plugin-eye-aftershock-v4")) {
        into("algorithm-plugins/eye-aftershock-v4")
    }
    dependsOn(":algorithms:binding:pluginEyeGazeLidV3")
    from(project(":algorithms:binding").layout.buildDirectory.dir("plugin-eye-gaze-lid-v3")) {
        into("algorithm-plugins/eye-gaze-lid-v3")
    }
    dependsOn(":algorithms:binding:pluginEyeGazeLid")
    from(project(":algorithms:binding").layout.buildDirectory.dir("plugin-eye-gaze-lid")) {
        into("algorithm-plugins/eye-gaze-lid")
    }
    dependsOn(":algorithms:binding:pluginEyeEmotionV3")
    from(project(":algorithms:binding").layout.buildDirectory.dir("plugin-eye-emotion-v3")) {
        into("algorithm-plugins/eye-emotion-v3")
    }
    dependsOn(":algorithms:binding:pluginEyeEmotion")
    from(project(":algorithms:binding").layout.buildDirectory.dir("plugin-eye-emotion")) {
        into("algorithm-plugins/eye-emotion")
    }
    dependsOn(":algorithms:binding:pluginEyeHighlightV3")
    from(project(":algorithms:binding").layout.buildDirectory.dir("plugin-eye-highlight-v3")) {
        into("algorithm-plugins/eye-highlight-v3")
    }
    dependsOn(":algorithms:binding:pluginEyeHighlight")
    from(project(":algorithms:binding").layout.buildDirectory.dir("plugin-eye-highlight")) {
        into("algorithm-plugins/eye-highlight")
    }
    dependsOn(":algorithms:binding:pluginMouthCheekLid")
    from(project(":algorithms:binding").layout.buildDirectory.dir("plugin-mouth-cheek-lid")) {
        into("algorithm-plugins/mouth-cheek-lid")
    }
    dependsOn(":algorithms:binding:pluginMouthCheekLidV3")
    from(project(":algorithms:binding").layout.buildDirectory.dir("plugin-mouth-cheek-lid-v3")) {
        into("algorithm-plugins/mouth-cheek-lid-v3")
    }
    dependsOn(":algorithms:binding:pluginMouthCheekLidV4")
    from(project(":algorithms:binding").layout.buildDirectory.dir("plugin-mouth-cheek-lid-v4")) {
        into("algorithm-plugins/mouth-cheek-lid-v4")
    }
    dependsOn(":algorithms:binding:pluginMouthCheekLidV5")
    from(project(":algorithms:binding").layout.buildDirectory.dir("plugin-mouth-cheek-lid-v5")) {
        into("algorithm-plugins/mouth-cheek-lid-v5")
    }
    dependsOn(":algorithms:binding:pluginMouthCheekLidV6")
    from(project(":algorithms:binding").layout.buildDirectory.dir("plugin-mouth-cheek-lid-v6")) {
        into("algorithm-plugins/mouth-cheek-lid-v6")
    }
    dependsOn(":algorithms:binding:pluginMouthCheekLidV7")
    from(project(":algorithms:binding").layout.buildDirectory.dir("plugin-mouth-cheek-lid-v7")) {
        into("algorithm-plugins/mouth-cheek-lid-v7")
    }
    dependsOn(":algorithms:binding:pluginEyeSquashV4")
    from(project(":algorithms:binding").layout.buildDirectory.dir("plugin-eye-squash-v4")) {
        into("algorithm-plugins/eye-squash-v4")
    }
    dependsOn(":algorithms:binding:pluginEyeSquashV3")
    from(project(":algorithms:binding").layout.buildDirectory.dir("plugin-eye-squash-v3")) {
        into("algorithm-plugins/eye-squash-v3")
    }
    dependsOn(":algorithms:binding:pluginEyeSquash")
    from(project(":algorithms:binding").layout.buildDirectory.dir("plugin-eye-squash")) {
        into("algorithm-plugins/eye-squash")
    }
    dependsOn(":algorithms:binding:pluginFaceLegacyV2")
    from(project(":algorithms:binding").layout.buildDirectory.dir("plugin-face-legacy-v2")) {
        into("algorithm-plugins/face-legacy-v2")
    }
    dependsOn(":algorithms:binding:pluginBrowLegacyV2")
    from(project(":algorithms:binding").layout.buildDirectory.dir("plugin-brow-legacy-v2")) {
        into("algorithm-plugins/brow-legacy-v2")
    }
    dependsOn(":algorithms:binding:pluginEyeWide")
    from(project(":algorithms:binding").layout.buildDirectory.dir("plugin-eye-wide")) {
        into("algorithm-plugins/eye-wide")
    }
    dependsOn(":algorithms:binding:pluginEyeWideV3")
    from(project(":algorithms:binding").layout.buildDirectory.dir("plugin-eye-wide-v3")) {
        into("algorithm-plugins/eye-wide-v3")
    }
    dependsOn(":algorithms:binding:pluginBrowSpring")
    from(project(":algorithms:binding").layout.buildDirectory.dir("plugin-brow-spring")) {
        into("algorithm-plugins/brow-spring")
    }

    dependsOn(":algorithms:binding:pluginBrowSpringV3")
    from(project(":algorithms:binding").layout.buildDirectory.dir("plugin-brow-spring-v3")) {
        into("algorithm-plugins/brow-spring-v3")
    }
    dependsOn(":algorithms:binding:pluginBrowSpringV4")
    from(project(":algorithms:binding").layout.buildDirectory.dir("plugin-brow-spring-v4")) {
        into("algorithm-plugins/brow-spring-v4")
    }
    dependsOn(":algorithms:binding:pluginMouthSpring")
    from(project(":algorithms:binding").layout.buildDirectory.dir("plugin-mouth-spring")) {
        into("algorithm-plugins/mouth-spring")
    }
    dependsOn(":algorithms:binding:pluginMouthSpringV3")
    from(project(":algorithms:binding").layout.buildDirectory.dir("plugin-mouth-spring-v3")) {
        into("algorithm-plugins/mouth-spring-v3")
    }
    dependsOn(":algorithms:binding:pluginMouthSpringV4")
    from(project(":algorithms:binding").layout.buildDirectory.dir("plugin-mouth-spring-v4")) {
        into("algorithm-plugins/mouth-spring-v4")
    }
    dependsOn(":algorithms:binding:pluginMouthSpringV5")
    from(project(":algorithms:binding").layout.buildDirectory.dir("plugin-mouth-spring-v5")) {
        into("algorithm-plugins/mouth-spring-v5")
    }

    dependsOn(":algorithms:binding:pluginMouthSpringV6")
    from(project(":algorithms:binding").layout.buildDirectory.dir("plugin-mouth-spring-v6")) {
        into("algorithm-plugins/mouth-spring-v6")
    }
    dependsOn(":algorithms:binding:pluginMouthSpringV7")
    from(project(":algorithms:binding").layout.buildDirectory.dir("plugin-mouth-spring-v7")) {
        into("algorithm-plugins/mouth-spring-v7")
    }
    dependsOn(":algorithms:binding:pluginMouthSpringV8")
    from(project(":algorithms:binding").layout.buildDirectory.dir("plugin-mouth-spring-v8")) {
        into("algorithm-plugins/mouth-spring-v8")
    }
    dependsOn(":algorithms:binding:pluginBodyLegacyV2")
    from(project(":algorithms:binding").layout.buildDirectory.dir("plugin-body-legacy-v2")) {
        into("algorithm-plugins/body-legacy-v2")
    }
    dependsOn(":algorithms:binding:pluginEyeLegacy")
    dependsOn(":algorithms:binding:pluginEyeFitted")
    dependsOn(":algorithms:binding:pluginEyeFittedV4")
    from(project(":algorithms:binding").layout.buildDirectory.dir("plugin-eye-fitted-v4")) {
        into("algorithm-plugins/eye-fitted-v4")
    }

    dependsOn(":algorithms:binding:pluginEyeFittedV5")
    from(project(":algorithms:binding").layout.buildDirectory.dir("plugin-eye-fitted-v5")) {
        into("algorithm-plugins/eye-fitted-v5")
    }
    from(project(":algorithms:binding").layout.buildDirectory.dir("plugin-eye-legacy")) {
        into("algorithm-plugins/eye-legacy")
    }
    from(project(":algorithms:binding").layout.buildDirectory.dir("plugin-eye-fitted")) {
        into("algorithm-plugins/eye-fitted")
    }
    dependsOn(":algorithms:binding:pluginSkinClip")
    from(project(":algorithms:binding").layout.buildDirectory.dir("plugin-skin")) {
        into("algorithm-plugins/skin-clip")
    }
    for (area in listOf("eye", "brow")) {
        dependsOn(":algorithms:binding:plugin" + area.replaceFirstChar { it.uppercase() } + "SeeThrough")
        from(project(":algorithms:binding").layout.buildDirectory.dir("plugin-$area")) {
            into("algorithm-plugins/see-through-$area")
        }
    }
    dependsOn(":algorithms:binding:pluginPackage")
    from(project(":algorithms:binding").layout.buildDirectory.dir("plugin")) {
        into("algorithm-plugins/binding")
    }
    dependsOn(":algorithms:binding:pluginExpressionV2")
    from(project(":algorithms:binding").layout.buildDirectory.dir("plugin-expression-v2")) {
        into("algorithm-plugins/expression-v2")
    }
    dependsOn(":algorithms:physics:pluginPackage")
    from(project(":algorithms:physics").layout.buildDirectory.dir("plugin")) {
        into("algorithm-plugins/physics")
    }
    dependsOn(":algorithms:motion:pluginPackage")
    from(project(":algorithms:motion").layout.buildDirectory.dir("plugin")) {
        into("algorithm-plugins/motion")
    }
    dependsOn(":algorithms:swing:pluginPackage")
    from(project(":algorithms:swing").layout.buildDirectory.dir("plugin")) {
        into("algorithm-plugins/swing")
    }
    dependsOn(":algorithms:simulation:pluginPackage")
    from(project(":algorithms:simulation").layout.buildDirectory.dir("plugin")) {
        into("algorithm-plugins/simulation")
    }
	if (!includeCubism) {
		exclude("cubism/**")
	}
	// The app reads its own version from here (AppVersion), so the about page cannot disagree with the build.
	val appVersion = project.version.toString()
	inputs.property("appVersion", appVersion)
	filesMatching("psd2live/version.properties") { expand("version" to appVersion) }
}

tasks.named<Jar>("jar") {
	if (!includeCubism) {
		exclude("cubism/**")
	}
}

// The Rust runtime (runtime/), built when cargo is available: exports bake through it and packages ship
// it; without it the app falls back to the editor's evaluator.
val runtimeLibrary = file("runtime/target/release/" + System.mapLibraryName("p2l_runtime"))
val cargoAvailable: Boolean = runCatching { ProcessBuilder("cargo", "--version").start().waitFor() == 0 }.getOrDefault(false)
// A rustc older than runtime/Cargo.toml's rust-version cannot build the dependencies, so it counts as no cargo at
// all: the build skips the runtime with a warning instead of failing `run`.
val runtimeRustVersion: String? = Regex("^rust-version\\s*=\\s*\"([0-9.]+)\"", RegexOption.MULTILINE)
	.find(file("runtime/Cargo.toml").readText())?.groupValues?.get(1)
val rustcVersion: String? = if (!cargoAvailable) null else runCatching {
	val process = ProcessBuilder("rustc", "--version").directory(file("runtime")).redirectErrorStream(true).start()
	val output = process.inputStream.bufferedReader().readText()
	if (process.waitFor() == 0) Regex("rustc (\\d+(\\.\\d+)*)").find(output)?.groupValues?.get(1) else null
}.getOrNull()
fun versionAtLeast(version: String, minimum: String): Boolean {
	val have = version.split('.').map { it.toIntOrNull() ?: 0 }
	val need = minimum.split('.').map { it.toIntOrNull() ?: 0 }
	for (i in 0 until maxOf(have.size, need.size)) {
		val a = have.getOrElse(i) { 0 }
		val b = need.getOrElse(i) { 0 }
		if (a != b) return a > b
	}
	return true
}
val buildRuntime = tasks.register<Exec>("buildRuntime") {
	group = "build"
	description = "Builds the Rust runtime library with cargo (skipped without cargo or with a rustc older than the runtime needs)."
	onlyIf {
		if (!cargoAvailable) {
			logger.lifecycle("Skipping the Rust runtime: cargo was not found. The app falls back to the editor's evaluator.")
			return@onlyIf false
		}
		val have = rustcVersion
		val need = runtimeRustVersion
		if (have != null && need != null && !versionAtLeast(have, need)) {
			logger.warn("Skipping the Rust runtime: rustc $have is older than $need, which runtime/Cargo.toml needs. " +
				"Run `rustup update` to build it; until then the app falls back to the editor's evaluator.")
			return@onlyIf false
		}
		true
	}
	workingDir = file("runtime")
	commandLine("cargo", "build", "--release", "--lib")
	// -Ppsd2live.fastRuntime=true trades the release profile's LTO and single codegen unit for a much quicker
	// build, for iterating on runtime/; the library is slower, so packages should not use it.
	val fastRuntime = findProperty("psd2live.fastRuntime")?.toString() == "true"
	inputs.property("fastRuntime", fastRuntime)
	if (fastRuntime) {
		environment("CARGO_PROFILE_RELEASE_LTO", "off")
		environment("CARGO_PROFILE_RELEASE_CODEGEN_UNITS", "16")
	}
	inputs.dir("runtime/src")
	inputs.file("runtime/Cargo.toml")
	inputs.file("runtime/Cargo.lock")
	outputs.file(runtimeLibrary)
}

// Without a usable cargo (none, or a rustc older than the runtime needs) a Linux build downloads the library CI
// built from the same runtime sources instead (.github/workflows/runtime-prebuilt.yml), so `run` and packages
// still get the Rust runtime without a Rust toolchain. The asset is named after a hash of those sources; a build
// whose sources have no published library, or no network, falls back to the editor's evaluator as before.
// Opt out with -Ppsd2live.prebuiltRuntime=false. `printRuntimeHash` is what CI names the asset with.
val runtimeBuildable: Boolean = cargoAvailable && run {
	val have = rustcVersion
	val need = runtimeRustVersion
	have == null || need == null || versionAtLeast(have, need)
}
val runtimeStamp = file("runtime/target/release/p2l_runtime.source-hash")
fun runtimeSourceHash(): String {
	val root = file("runtime")
	val files = (fileTree(root.resolve("src")).files + root.resolve("Cargo.toml") + root.resolve("Cargo.lock"))
		.sortedBy { it.relativeTo(root).invariantSeparatorsPath }
	val digest = MessageDigest.getInstance("SHA-256")
	for (file in files) {
		digest.update(file.relativeTo(root).invariantSeparatorsPath.toByteArray())
		digest.update(0.toByte())
		// Line endings differ between checkouts; the hash must not.
		digest.update(file.readBytes().filter { it != '\r'.code.toByte() }.toByteArray())
		digest.update(0.toByte())
	}
	return digest.digest().joinToString("") { "%02x".format(it) }.take(16)
}
tasks.register("printRuntimeHash") {
	group = "help"
	description = "Prints the hash of the runtime sources that names their prebuilt library."
	doLast { println(runtimeSourceHash()) }
}
val fetchPrebuiltRuntime = tasks.register("fetchPrebuiltRuntime") {
	group = "build"
	description = "Downloads the prebuilt Rust runtime library when cargo cannot build it (Linux only)."
	onlyIf { !runtimeBuildable && hostOs == "linux" && findProperty("psd2live.prebuiltRuntime")?.toString() != "false" }
	doLast {
		val hash = runtimeSourceHash()
		// A library with no stamp was built here with cargo; one stamped with other sources is out of date.
		if (runtimeLibrary.isFile && (!runtimeStamp.isFile || runtimeStamp.readText().trim() == hash)) return@doLast
		val name = "p2l_runtime-$hash-linux-${if (hostArm) "aarch64" else "x86_64"}.${runtimeLibrary.extension}"
		val base = "https://github.com/tsunehimatoi/psd2live/releases/download/runtime-prebuilt/"
		val client = HttpClient.newBuilder()
			.followRedirects(HttpClient.Redirect.NORMAL)
			.connectTimeout(Duration.ofSeconds(15)).build()
		fun download(asset: String): ByteArray? {
			val request = HttpRequest.newBuilder(URI.create(base + asset))
				.timeout(Duration.ofSeconds(120)).build()
			val response: HttpResponse<ByteArray> = client.send(request, HttpResponse.BodyHandlers.ofByteArray())
			return if (response.statusCode() == 200) response.body() else null
		}
		try {
			val library = download(name)
			val expected = download("$name.sha256")?.toString(Charsets.UTF_8)?.trim()?.substringBefore(' ')
			if (library == null || expected == null) {
				logger.lifecycle("No prebuilt Rust runtime for these sources ($name). Install Rust 1.87+ (rustup) to build it; " +
					"until then the app falls back to the editor's evaluator.")
				return@doLast
			}
			val actual = MessageDigest.getInstance("SHA-256").digest(library).joinToString("") { "%02x".format(it) }
			check(actual.equals(expected, ignoreCase = true)) { "checksum mismatch for $name" }
			runtimeLibrary.parentFile.mkdirs()
			val part = File(runtimeLibrary.path + ".part")
			part.writeBytes(library)
			Files.move(part.toPath(), runtimeLibrary.toPath(), StandardCopyOption.REPLACE_EXISTING)
			runtimeStamp.writeText(hash)
			logger.lifecycle("Downloaded the prebuilt Rust runtime ($name); cargo was not available.")
		} catch (e: Exception) {
			logger.warn("Could not download the prebuilt Rust runtime ($name): ${e.message}. The app falls back to the editor's evaluator.")
		}
	}
}

// Compose registers `run` once the project is evaluated.
afterEvaluate {
	tasks.named<JavaExec>("run") {
		dependsOn(buildRuntime, fetchPrebuiltRuntime)
		systemProperty("psd2live.runtime.dir", runtimeLibrary.parentFile.absolutePath)
	}
	// createDistributable does not track what prepareAppResources put together, so a build adding ffmpeg
	// (psd2live.ffmpegDir) after one without it kept the app image without it, and so did every package made from it.
	tasks.named("createDistributable") { inputs.files(tasks.named("prepareAppResources")).withPropertyName("appResources") }
}

// License texts and the stdio MCP bridge ship inside every package, under the app's resources directory, beside the runtime.
tasks.withType<Sync>().matching { it.name == "prepareAppResources" }.configureEach {
	dependsOn(buildRuntime, fetchPrebuiltRuntime)
	from("LICENSE", "THIRD_PARTY_NOTICES.md", "mcp_proxy.py")
	from("licenses") { into("licenses") }
	from(runtimeLibrary.parentFile) { include(runtimeLibrary.name) }
	bundledFfmpeg?.let { dir ->
		doFirst { check(dir.isDirectory) { "psd2live.ffmpegDir is not a directory: $dir" } }
		from(dir) { into("ffmpeg") }
	}
}

compose.desktop {
	application {
		mainClass = "io.github.psd2live.MainKt"
		// Half the machine's memory rather than a fixed 8 GB: a fixed cap left no room for the canvas's native
		// (GPU, Skia) memory on 8 and 16 GB machines, where the system killed the app instead of it running out of heap.
		jvmArgs += listOf("-XX:MaxRAMPercentage=50", "-Dfile.encoding=UTF-8", "-Dsun.java2d.uiScale.enabled=true")
		nativeDistributions {
			// ModelDownloader uses HttpClient. Compose's automatic
			// runtime module scan can miss this API because it is only loaded when
			// the optional texture-upscale workflow is opened.
			// LWJGL (the canvas GPU renderer) reaches native memory through sun.misc.Unsafe.
			modules("java.net.http", "jdk.unsupported")
			// Compose only packages formats supported on the build host; Deb is for Linux. The Windows
			// installer is built below from the app image instead, with Inno Setup.
			targetFormats(org.jetbrains.compose.desktop.application.dsl.TargetFormat.Deb)
			packageName = "PSD2Live"
			// One place bumps the version: the packages and the resource-stamped version.properties follow it.
			packageVersion = project.version.toString()
			description = "PSD2Live - Automated Live2D Rigging Pipeline"
			copyright = "© 2026 PSD2Live. Licensed under GPL-3.0."
			vendor = "PSD2Live"

			windows {
				iconFile.set(project.file("src/main/resources/icons/psd2live.ico"))
				menuGroup = "PSD2Live"
				upgradeUuid = "8e9c4b1a-2d3e-4f5a-6b7c-8d9e0f1a2b3c"
			}
		}
	}
}

// The released Windows app runs on the JetBrains Runtime: its custom title bar keeps the main window's native frame
// (maximize, Snap, resize edges) under the app's own title bar. Other JDKs still build, test and run it, with the bar
// drawn whole on an undecorated window, but a Windows package made on one would ship that.
// -Ppsd2live.allowNonJbr=true packages anyway. macOS packageDmg uses whatever JDK runs Gradle: the Swift shell keeps
// the system window frame, so the runtime does not have to be JBR.
afterEvaluate {
	tasks.withType<org.jetbrains.compose.desktop.application.tasks.AbstractJPackageTask>().configureEach {
		val home = javaHome
		val allowed = providers.gradleProperty("psd2live.allowNonJbr").map { it == "true" }.orElse(false)
		doFirst {
			val release = File(home.get(), "release")
			val implementor = if (release.isFile) release.readLines().firstOrNull { it.startsWith("IMPLEMENTOR=") }.orEmpty() else ""
			if ("JetBrains" !in implementor && !allowed.get() && hostOs != "macos") throw GradleException(
				"Packaging needs the JetBrains Runtime 21 SDK (JBR with jpackage), not ${home.get()} ($implementor). " +
					"Point JAVA_HOME at one, or pass -Ppsd2live.allowNonJbr=true to package with this JDK.")
		}
	}
}

// Windows installer (packageExe): Inno Setup packs the app image with packaging/windows/psd2live.iss, which installs
// in place, goes back to the installed folder on an upgrade and replaces the MSI packages of 3.1.x and earlier.
// ISCC.exe is found from -Ppsd2live.iscc, the ISCC environment variable or Inno Setup 6's default install folders.
if (hostOs == "windows") afterEvaluate {
	val distributions = compose.desktop.application.nativeDistributions
	val createDistributable = tasks.named<org.jetbrains.compose.desktop.application.tasks.AbstractJPackageTask>("createDistributable")
	val packageExe = tasks.register<Exec>("packageExe") {
		group = "compose desktop"
		description = "Builds the Windows .exe installer from the app image with Inno Setup."
		dependsOn(createDistributable)
		val appImage = createDistributable.get().destinationDir.dir(distributions.packageName!!)
		val script = file("packaging/windows/psd2live.iss")
		val dest = layout.buildDirectory.dir("compose/binaries/main/exe")
		inputs.dir(appImage); inputs.file(script)
		outputs.dir(dest)
		val iscc = sequenceOf(
			findProperty("psd2live.iscc")?.toString(),
			System.getenv("ISCC"),
			System.getenv("ProgramFiles(x86)")?.let { "$it/Inno Setup 6/ISCC.exe" },
			System.getenv("ProgramFiles")?.let { "$it/Inno Setup 6/ISCC.exe" },
			System.getenv("LOCALAPPDATA")?.let { "$it/Programs/Inno Setup 6/ISCC.exe" },
		).filterNotNull().map(::File).firstOrNull(File::isFile)
		executable = iscc?.path ?: "ISCC.exe"
		args("/Qp", "/DAppVersion=${distributions.packageVersion}", "/DAppImage=${appImage.get().asFile}",
			"/DOutputDir=${dest.get().asFile}", "/DIconFile=${distributions.windows.iconFile.get().asFile}",
			"/DArch=${if (hostArm) "arm64" else "x64"}", script)
		doFirst { dest.get().asFile.deleteRecursively() }
	}
	tasks.named("packageDistributionForCurrentOS") { dependsOn(packageExe) }
}

// macOS disk image (packageDmg): the Swift shell in packaging/macos replaces the jpackage launcher and keeps the
// runtime that createDistributable bundled. The image matches the machine that builds it (Apple silicon or Intel).
if (hostOs == "macos") afterEvaluate {
	val distributions = compose.desktop.application.nativeDistributions
	val createDistributable = tasks.named<org.jetbrains.compose.desktop.application.tasks.AbstractJPackageTask>("createDistributable")
	val packageDmg = tasks.register<Exec>("packageDmg") {
		group = "compose desktop"
		description = "Builds a macOS .dmg whose app is the Swift shell around the bundled runtime."
		dependsOn(createDistributable)
		val script = file("packaging/macos/build-dmg.sh")
		val image = createDistributable.get().destinationDir.dir("${distributions.packageName}.app")
		val arch = if (hostArm) "arm64" else "x86_64"
		val dmg = layout.buildDirectory.file("PSD2Live-${project.version}-macos-$arch.dmg")
		inputs.file(script)
		inputs.files("packaging/macos/launch.c", "packaging/macos/main.swift", "src/main/resources/icons/psd2live.png")
		inputs.dir(image)
		outputs.file(dmg)
		commandLine("bash", script.absolutePath, "--image", image.get().asFile.absolutePath, "--version", project.version.toString())
	}
	tasks.named("packageDistributionForCurrentOS") { dependsOn(packageDmg) }
}

afterEvaluate {
	// The run-gui scripts build through Gradle but start the JVM themselves from these files. Under
	// `gradlew run` the app is a child of the daemon, outside the terminal's process group, so Ctrl+C
	// only reaches it through Gradle's cancellation (and cmd's "Terminate batch job" prompt on Windows).
	val run = tasks.getByName<JavaExec>("run")
	tasks.register("writeRunArgs") {
		group = "compose desktop"
		description = "Builds the app and writes the java launcher and argument file used by run-gui scripts."
		dependsOn(run.taskDependencies)
		doLast {
			val dir = layout.buildDirectory.dir("run").get().asFile.apply { mkdirs() }
			fun quote(arg: String) = "\"" + arg.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
			// Gradle adds its daemon's locale; a direct launch should see the user's own.
			val jvmArgs = run.allJvmArgs.filterNot { arg -> listOf("-Duser.country", "-Duser.language", "-Duser.variant").any(arg::startsWith) }
			// Launch from a content-named copy of the app jar: a build while the app runs rewrites
			// build/libs, and classes loaded lazily from the rewritten jar fail (Windows refuses the
			// rewrite instead). Old copies go once nothing holds them; Windows keeps the open one.
			val jar = tasks.getByName<Jar>("jar").archiveFile.get().asFile
			val hash = MessageDigest.getInstance("SHA-256").digest(jar.readBytes())
				.joinToString("") { "%02x".format(it) }.take(16)
			val snapshot = dir.resolve("app-$hash.jar")
			if (!snapshot.exists()) {
				val partial = dir.resolve("${snapshot.name}.${ProcessHandle.current().pid()}.tmp")
				jar.copyTo(partial, overwrite = true)
				Files.move(partial.toPath(), snapshot.toPath(), StandardCopyOption.REPLACE_EXISTING)
			}
			dir.listFiles { file -> file.name.startsWith("app-") && file != snapshot }?.forEach { it.delete() }
			val classpath = run.classpath.files.map { if (it == jar) snapshot else it }
			val args = jvmArgs + listOf("-cp", classpath.joinToString(File.pathSeparator), run.mainClass.get())
			dir.resolve("jvm.args").writeText(args.joinToString("\n", postfix = "\n") { quote(it) })
			dir.resolve("java").writeText(run.javaLauncher.get().executablePath.asFile.absolutePath)
		}
	}

	tasks.findByName("compileTestKotlin")?.enabled = true
	tasks.findByName("test")?.enabled = true
}

// A standalone worker example; only the explicit task builds/distributes this source set.
val pluginExample = sourceSets.create("pluginExample")
kotlin.sourceSets.getByName("pluginExample").kotlin.srcDir("examples/plugins/kotlin")
configurations[pluginExample.implementationConfigurationName].extendsFrom(configurations["implementation"])
tasks.register<Jar>("buildPluginExample") {
    dependsOn(tasks.named(pluginExample.classesTaskName))
    archiveFileName.set("worker.jar")
    destinationDirectory.set(layout.buildDirectory.dir("plugin-example"))
    manifest { attributes["Main-Class"] = "examples.plugins.Worker" }
    from(pluginExample.output)
    from({ configurations[pluginExample.runtimeClasspathConfigurationName].filter {
        it.name.startsWith("kotlin-stdlib") || it.name.startsWith("kotlinx-serialization-") || it.name.startsWith("annotations-")
    }.map { zipTree(it) } })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA")
}

tasks.register<Test>("testMouthOutlinePackage") {
    dependsOn("testClasses", ":algorithms:binding:pluginMouthOutlineSource")
    val testSource = sourceSets.test.get()
    testClassesDirs = testSource.output.classesDirs
    classpath = testSource.runtimeClasspath
    filter.includeTestsMatching("io.github.psd2live.core.MouthOutlinePluginParityTest")
    val packageDirectory = project(":algorithms:binding").layout.buildDirectory.dir("plugin-mouth-outline-source")
    inputs.dir(packageDirectory)
    systemProperty("psd2live.mouthOutlinePackage", packageDirectory.get().asFile.absolutePath)
}

tasks.register<Test>("testMouthOutlineV3Package") {
    dependsOn("testClasses", ":algorithms:binding:pluginMouthOutlineV3")
    val testSource = sourceSets.test.get()
    testClassesDirs = testSource.output.classesDirs
    classpath = testSource.runtimeClasspath
    filter.includeTestsMatching("io.github.psd2live.core.MouthOutlineV3ParityTest")
    val packageDirectory = project(":algorithms:binding").layout.buildDirectory.dir("plugin-mouth-outline-v3")
    inputs.dir(packageDirectory)
    systemProperty("psd2live.mouthOutlineV3Package", packageDirectory.get().asFile.absolutePath)
}
