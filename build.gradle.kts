plugins {
	kotlin("jvm") version "2.4.10"
	kotlin("plugin.serialization") version "2.4.10"
	id("org.jetbrains.compose") version "1.11.1"
	id("org.jetbrains.kotlin.plugin.compose") version "2.4.10"
	distribution
}

group = "io.github.psd2live"
version = "0.5.0"

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
	implementation("io.ktor:ktor-server-netty")
	implementation("io.ktor:ktor-server-auth")
	implementation("io.ktor:ktor-server-content-negotiation")
	implementation("io.ktor:ktor-server-sse")
	implementation("io.ktor:ktor-serialization-kotlinx-json")
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
			from("docs") { into("docs") }
			from("src/main/resources/cubism") { into("cubism") }
		}
	}
}

compose.desktop {
	application {
		mainClass = "io.github.psd2live.MainKt"
		jvmArgs += listOf("-Xmx8g", "-Dfile.encoding=UTF-8")
		nativeDistributions {
			targetFormats(
				org.jetbrains.compose.desktop.application.dsl.TargetFormat.Exe,
				org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi,
			)
			packageName = "PSD2Live"
			packageVersion = "0.5.0"
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

tasks.test {
	useJUnitPlatform()
	systemProperty("psd2live.cubism.smoke", System.getProperty("psd2live.cubism.smoke", "false"))
}
