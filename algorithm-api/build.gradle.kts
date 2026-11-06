plugins {
    `java-library`
    kotlin("jvm")
    kotlin("plugin.serialization")
}

group = "io.github.psd2live"
version = "1.10.0"

kotlin { jvmToolchain(21) }
java { withSourcesJar() }

dependencies {
    api(project(":format-model"))
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    testImplementation(kotlin("test"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    filter.isFailOnNoMatchingTests = false
}
