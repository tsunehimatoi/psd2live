// Engine ported from Umamo (GPL-3): model, keyform evaluation, edit primitives, PSD/PNG/CMO3/MOC3
// formats and their interop. It must not depend on the PSD2Live product code.
plugins {
	kotlin("jvm")
	kotlin("plugin.serialization")
}

kotlin {
	jvmToolchain(21)
	compilerOptions {
		freeCompilerArgs.add("-Xno-optimize")
	}
}

dependencies {
	implementation(kotlin("reflect"))
	implementation("org.jdom:jdom:1.1.3")
	implementation("com.squareup.okio:okio:3.17.0")
	implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.8.0")
	api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
	api(platform("org.lwjgl:lwjgl-bom:3.4.2"))
	api("org.lwjgl:lwjgl")
	api("org.lwjgl:lwjgl-opengl")
	testImplementation(kotlin("test"))
	// MocDefaultColorsTest hands fresh models to the official Cubism Core, when PSD2LIVE_TEST_CUBISM_CORE names it.
	testImplementation("net.java.dev.jna:jna:5.18.0")
}

// The application needs several precisions of one trace. Keep this extension out of pinned worker JARs.
val contourVariants = sourceSets.create("contourVariants")
kotlin.target.compilations.named(contourVariants.name) {
	associateWith(kotlin.target.compilations.getByName("main"))
}
val contourVariantsJar = tasks.register<Jar>("contourVariantsJar") {
	archiveClassifier.set("contour-variants")
	isPreserveFileTimestamps = false
	isReproducibleFileOrder = true
	from(contourVariants.output)
}
val contourVariantsElements = configurations.create("contourVariantsElements") {
	isCanBeConsumed = true
	isCanBeResolved = false
}
artifacts.add(contourVariantsElements.name, contourVariantsJar)

tasks.withType<Test>().configureEach {
	useJUnitPlatform()
	maxHeapSize = "2g"
	// `--tests` names one class from either module; the other module simply has nothing to run.
	filter.isFailOnNoMatchingTests = false
	testLogging {
		events(org.gradle.api.tasks.testing.logging.TestLogEvent.FAILED)
		exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
		showStackTraces = true
	}
}

// Every engine test is quick.
tasks.register("quickTest") {
	group = "verification"
	description = "Runs the engine tests."
	dependsOn(tasks.test)
}
