// PSD2Live runtime target (MIT): the compiled .p2lrt rig the Rust runtime plays.
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
	// zstd chunk compression, pure Java (Apache-2.0).
	implementation("io.airlift:aircompressor:0.27")
	testImplementation(kotlin("test"))
}
