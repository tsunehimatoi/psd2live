// Layered PSD target (GPL-3, uses the engine's PSD writer): one posed layer per mesh, for returning
// a pose to the art workflow.
plugins {
	kotlin("jvm")
}

kotlin {
	jvmToolchain(21)
	compilerOptions {
		freeCompilerArgs.add("-Xno-optimize")
	}
}

dependencies {
	api(project(":format-compile"))
	api(project(":umamo"))
	testImplementation(kotlin("test"))
}

tasks.withType<Test>().configureEach {
	maxHeapSize = "2g"
}
