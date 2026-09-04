plugins {
	kotlin("jvm") version "2.4.10"
	id("org.jetbrains.compose") version "1.11.1"
	id("org.jetbrains.kotlin.plugin.compose") version "2.4.10"
	distribution
}

group = "io.github.psd2live"
version = "0.3.0"

kotlin {
	jvmToolchain(21)
}

dependencies {
	implementation(platform("io.ktor:ktor-bom:3.5.1"))
	implementation("local.umamo:format:local")
	implementation("local.umamo:runtime:local")
	implementation("local.umamo:interop:local")
	implementation("local.umamo:render:local")
	implementation("local.umamo:edit:local")
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
	implementation(compose.runtime)
	implementation(compose.foundation)
	implementation(compose.ui)
	implementation(compose.material)
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
			packageVersion = "0.3.0"
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
