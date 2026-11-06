// Binding kernels use the GPL engine; they are separate from the MIT API/format modules.
plugins { `java-library`; kotlin("jvm") }
group = "io.github.psd2live.algorithms"
version = "1.0.0"
kotlin { jvmToolchain(21) }
dependencies {
    api(project(":umamo"))
    api(project(":algorithm-api"))
    implementation(project(":format-compile"))
    implementation(project(":targets:cubism"))
    testImplementation(kotlin("test"))
}
tasks.withType<Test>().configureEach { useJUnitPlatform(); filter.isFailOnNoMatchingTests = false }
