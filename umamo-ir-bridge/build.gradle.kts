// GPL-3.0: engine/model conversion, independent of target lowering, compilation and writers.
plugins {
    `java-library`
    kotlin("jvm")
}

kotlin {
    jvmToolchain(21)
    compilerOptions { freeCompilerArgs.add("-Xno-optimize") }
}

dependencies {
    api(project(":umamo"))
    api(project(":format-model"))
    testImplementation(kotlin("test"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    maxParallelForks = providers.gradleProperty("psd2live.testForks").orNull?.toInt() ?: 1
}
