plugins {
	kotlin("jvm") version "2.4.10"
	application
}

group = "io.github.autolive2d"
version = "0.1.0"

kotlin {
	jvmToolchain(21)
}

dependencies {
	implementation("local.umamo:format:local")
	implementation("local.umamo:runtime:local")
	implementation("local.umamo:interop:local")
	implementation("local.umamo:render:local")
    implementation("net.java.dev.jna:jna:5.18.0")
	implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
	testImplementation(kotlin("test"))
}

application {
	mainClass.set("io.github.autolive2d.MainKt")
	applicationDefaultJvmArgs = listOf("-Xmx8g", "-Dfile.encoding=UTF-8")
}

distributions {
	main {
		contents {
			from("README.md")
			from("LICENSE")
			from("THIRD_PARTY_NOTICES.md")
			from("licenses") { into("licenses") }
			from("docs") { into("docs") }
		}
	}
}

tasks.test {
	useJUnitPlatform()
	systemProperty("autolive2d.cubism.smoke", System.getProperty("autolive2d.cubism.smoke", "false"))
}
