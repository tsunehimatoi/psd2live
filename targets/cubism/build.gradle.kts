// Cubism export target (GPL-3): target lowering and moc3/cmo3 writers behind the neutral export interface.
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
	api(project(":format-model"))
	api(project(":format-compile"))
	api(project(":umamo-ir-bridge"))
	testImplementation(kotlin("test"))
}

tasks.withType<Test>().configureEach {
	maxHeapSize = "2g"
}
