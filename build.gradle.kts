import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
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
version = "3.3.0"

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
	implementation(platform("io.ktor:ktor-bom:3.5.1"))
	// Engine ported from Umamo (format, runtime, interop, render, edit); it brings its own libraries.
	implementation(project(":umamo"))
	// Neutral rig IR, export framework and targets (see docs/zh/spec/EXPORT_TARGETS.md).
	implementation(project(":targets:cubism"))
	implementation(project(":targets:raster"))
	implementation(project(":targets:psd"))
	implementation(project(":targets:spine"))
	implementation(project(":targets:runtime"))
	implementation(project(":format-eval"))
	implementation(project(":targets:web"))
	implementation(project(":targets:gltf"))
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
	implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
	implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
	implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.11.0")
	implementation(compose.desktop.currentOs)
	implementation("org.jetbrains.compose.runtime:runtime:1.11.1")
	implementation("org.jetbrains.compose.foundation:foundation:1.11.1")
	implementation("org.jetbrains.compose.ui:ui:1.11.1")
	implementation("org.jetbrains.compose.material:material:1.11.1")
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


// Keep Cubism out of classpath resources unless explicitly opted in.
tasks.processResources {
	if (!includeCubism) {
		exclude("cubism/**")
	}
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
	inputs.dir("runtime/src")
	inputs.file("runtime/Cargo.toml")
	outputs.file(runtimeLibrary)
}

// Compose registers `run` once the project is evaluated.
afterEvaluate {
	tasks.named<JavaExec>("run") {
		dependsOn(buildRuntime)
		systemProperty("psd2live.runtime.dir", runtimeLibrary.parentFile.absolutePath)
	}
	// createDistributable does not track what prepareAppResources put together, so a build adding ffmpeg
	// (psd2live.ffmpegDir) after one without it kept the app image without it, and so did every package made from it.
	tasks.named("createDistributable") { inputs.files(tasks.named("prepareAppResources")).withPropertyName("appResources") }
}

// License texts and the stdio MCP bridge ship inside every package, under the app's resources directory, beside the runtime.
tasks.withType<Sync>().matching { it.name == "prepareAppResources" }.configureEach {
	dependsOn(buildRuntime)
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
			// ModelDownloader uses java.net.http.HttpClient. Compose's automatic
			// runtime module scan can miss this API because it is only loaded when
			// the optional texture-upscale workflow is opened.
			// LWJGL (the canvas GPU renderer) reaches native memory through sun.misc.Unsafe.
			modules("java.net.http", "jdk.unsupported")
			// Compose only packages formats supported on the build host; Deb is for Linux. The Windows
			// installer is built below from the app image instead, with Inno Setup.
			targetFormats(org.jetbrains.compose.desktop.application.dsl.TargetFormat.Deb)
			packageName = "PSD2Live"
			packageVersion = "3.3.0"
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
