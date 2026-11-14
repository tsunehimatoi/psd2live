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
	useJUnitPlatform()
	maxHeapSize = "2g"
	filter.isFailOnNoMatchingTests = false
	testLogging {
		events(org.gradle.api.tasks.testing.logging.TestLogEvent.FAILED)
		exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
	}
}

tasks.register("quickTest") {
	group = "verification"
	description = "Runs the Cubism target tests."
	dependsOn(tasks.test)
}
