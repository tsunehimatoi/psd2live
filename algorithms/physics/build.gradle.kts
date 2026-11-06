plugins {
    `java-library`
    kotlin("jvm")
}

group = "io.github.psd2live.algorithms"
version = "1.0.0"
kotlin { jvmToolchain(21) }
java { withSourcesJar() }

dependencies {
    api(project(":algorithm-api"))
    testImplementation(kotlin("test"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    filter.isFailOnNoMatchingTests = false
}
