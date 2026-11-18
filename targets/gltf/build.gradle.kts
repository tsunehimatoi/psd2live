// glTF target (MIT): meshes with morph targets per parameter key, clips as weight animations.
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
	// MiniJson, the tests' JSON reader (MIT like this module).
	testImplementation(testFixtures(project(":targets:spine")))
}
