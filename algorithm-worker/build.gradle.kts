plugins {
    `java-library`
    kotlin("jvm")
}
group = "io.github.psd2live"
version = "1.0.0"
kotlin { jvmToolchain(21) }
java { withSourcesJar() }
dependencies {
    api(project(":algorithm-api"))
    implementation(project(":format-compile"))
    testImplementation(kotlin("test"))
}
tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    filter.isFailOnNoMatchingTests = false
}
