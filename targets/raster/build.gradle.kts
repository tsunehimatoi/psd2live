// Raster export targets (MIT): image sequences, sprite sheets and animated GIF, rendered through the
// host's FrameRenderer. Must not depend on any GPL module.
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
}
