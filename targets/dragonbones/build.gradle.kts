// DragonBones target (MIT): a 5.5 skeleton with mesh displays, deform (ffd) animations and a texture atlas.
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
