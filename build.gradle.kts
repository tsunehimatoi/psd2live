import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import java.util.zip.ZipEntry

plugins {
	kotlin("jvm") version "2.4.10"
	kotlin("plugin.serialization") version "2.4.10"
	id("org.jetbrains.compose") version "1.11.1"
	id("org.jetbrains.kotlin.plugin.compose") version "2.4.10"
	distribution
}

group = "io.github.psd2live"
version = "0.7.1"

kotlin {
	jvmToolchain(21)
}

dependencies {
	implementation(platform("io.ktor:ktor-bom:3.5.1"))
	// Core engine dependencies (ported from Umamo: format, runtime, interop, render, edit)
	implementation(kotlin("reflect"))
	implementation("org.jdom:jdom:1.1.3")
	implementation("com.squareup.okio:okio:3.17.0")
	implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.8.0")
	implementation("app.cash.sqldelight:sqlite-driver:2.0.2")

	// LWJGL (OpenGL rendering pipeline)
	val lwjglNatives = run {
		val os = System.getProperty("os.name").lowercase()
		val arch = System.getProperty("os.arch").lowercase()
		val isArm = arch.startsWith("aarch64") || arch.startsWith("arm")
		when {
			os.contains("win") -> if (isArm) "natives-windows-arm64" else "natives-windows"
			os.contains("mac") || os.contains("darwin") -> if (isArm) "natives-macos-arm64" else "natives-macos"
			os.contains("linux") || os.contains("nix") -> if (isArm) "natives-linux-arm64" else "natives-linux"
			else -> "natives-windows"
		}
	}
	implementation(platform("org.lwjgl:lwjgl-bom:3.4.2"))
	implementation("org.lwjgl:lwjgl")
	implementation("org.lwjgl:lwjgl-opengl")
	runtimeOnly("org.lwjgl:lwjgl::$lwjglNatives")
	runtimeOnly("org.lwjgl:lwjgl-opengl::$lwjglNatives")
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
	testImplementation("io.modelcontextprotocol:kotlin-sdk-client:0.15.0")
	testImplementation("io.ktor:ktor-client-cio")
}


distributions {
	main {
		contents {
			from("README.md")
			from("README_en.md")
			from("README_ja.md")
			from("LICENSE")
			from("THIRD_PARTY_NOTICES.md")
			from("licenses") { into("licenses") }
			from("docs") {
				into("docs")
				exclude("imgs/**")
			}
			from("src/main/resources/cubism") { into("cubism") }
		}
	}
}

compose.desktop {
	application {
		mainClass = "io.github.psd2live.MainKt"
		jvmArgs += listOf("-Xmx8g", "-Dfile.encoding=UTF-8", "-Dsun.java2d.uiScale.enabled=true")
		nativeDistributions {
			// ModelDownloader uses java.net.http.HttpClient. Compose's automatic
			// runtime module scan can miss this API because it is only loaded when
			// the optional texture-upscale workflow is opened.
			modules("java.net.http")
			targetFormats(
				org.jetbrains.compose.desktop.application.dsl.TargetFormat.Exe,
				org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi,
			)
			packageName = "PSD2Live"
			packageVersion = "0.7.1"
			description = "PSD2Live - Automated Live2D Rigging Pipeline"
			copyright = "© 2026 PSD2Live. Licensed under GPL-3.0."
			vendor = "PSD2Live"

			windows {
				menuGroup = "PSD2Live"
				upgradeUuid = "8e9c4b1a-2d3e-4f5a-6b7c-8d9e0f1a2b3c"
			}
		}
	}
}

afterEvaluate {
	tasks.withType<org.jetbrains.compose.desktop.application.tasks.AbstractJPackageTask>().configureEach {
		packageFromUberJar.set(true)
	}
	listOf("packageUberJarForCurrentOS", "packageReleaseUberJarForCurrentOS").forEach { taskName ->
		tasks.findByName(taskName)?.let { task ->
			if (task is org.gradle.jvm.tasks.Jar) {
				task.apply {
					exclude("org/sqlite/native/FreeBSD/**")
					exclude("org/sqlite/native/Linux/**")
					exclude("org/sqlite/native/Linux-Android/**")
					exclude("org/sqlite/native/Linux-Musl/**")
					exclude("org/sqlite/native/Mac/**")
					exclude("org/sqlite/native/Windows/aarch64/**")
					exclude("org/sqlite/native/Windows/armv7/**")
					exclude("org/sqlite/native/Windows/x86/**")
					exclude("com/sun/jna/aix*/**")
					exclude("com/sun/jna/darwin*/**")
					exclude("com/sun/jna/dragonflybsd*/**")
					exclude("com/sun/jna/freebsd*/**")
					exclude("com/sun/jna/linux*/**")
					exclude("com/sun/jna/openbsd*/**")
					exclude("com/sun/jna/sunos*/**")
					exclude("com/sun/jna/win32-aarch64/**")
					exclude("com/sun/jna/win32-x86/**")
				}
			}
		}
	}

	tasks.named("createDistributable").configure {
		doLast {
			val appDir = file("build/compose/binaries/main/app/PSD2Live/app")
			if (appDir.exists()) {
				appDir.listFiles()?.forEach { jarFile ->
					if (jarFile.name.startsWith("sqlite-jdbc-") || jarFile.name.startsWith("jna-")) {
						val tempJar = File(jarFile.parentFile, jarFile.name + ".tmp")
						ZipFile(jarFile).use { zin ->
							ZipOutputStream(tempJar.outputStream().buffered()).use { zout ->
								val entries = zin.entries()
								while (entries.hasMoreElements()) {
									val entry = entries.nextElement()
									val p = entry.name.replace('\\', '/')
									var keep = true
									if (p.startsWith("org/sqlite/native/") && !p.startsWith("org/sqlite/native/Windows/x86_64/")) {
										keep = false
									}
									if (p.startsWith("com/sun/jna/") && (p.endsWith(".so") || p.endsWith(".dylib") || p.endsWith(".a") || p.endsWith(".jnilib")) && !p.contains("win32-x86-64")) {
										keep = false
									}
									if (p.startsWith("com/sun/jna/win32-") && !p.contains("win32-x86-64")) {
										keep = false
									}
									if (keep) {
										val newEntry = ZipEntry(entry.name).apply {
											time = entry.time
											comment = entry.comment
											if (entry.extra != null) {
												extra = entry.extra
											}
										}
										zout.putNextEntry(newEntry)
										zin.getInputStream(entry).copyTo(zout)
										zout.closeEntry()
									}
								}
							}
						}
						if (jarFile.delete()) {
							tempJar.renameTo(jarFile)
						} else {
							tempJar.delete()
						}
					}
				}
			}
		}
	}
}

tasks.test {
	useJUnitPlatform()
	systemProperty("psd2live.cubism.smoke", System.getProperty("psd2live.cubism.smoke", "false"))
}
