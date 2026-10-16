// Unity export target (MIT): a folder Unity imports as a prefab, Animator clips and blend shapes.
// Must not depend on any GPL module.
plugins {
	kotlin("jvm")
}

kotlin {
	jvmToolchain(21)
	explicitApi()
}

dependencies {
	api(project(":format-compile"))
	testImplementation(kotlin("test"))
	testImplementation(testFixtures(project(":targets:spine")))
}

tasks.withType<Test>().configureEach {
	useJUnitPlatform()
	filter.isFailOnNoMatchingTests = false
}

tasks.register("quickTest") {
	group = "verification"
	description = "Runs the Unity target tests."
	dependsOn(tasks.test)
}
