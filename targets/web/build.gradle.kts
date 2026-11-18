// PSD2Live web player target (MIT): the runtime as WebAssembly, a WebGL player and the rig.
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
	implementation(project(":targets:runtime"))
	testImplementation(kotlin("test"))
}

// The player ships the runtime built for WebAssembly as a resource, so building PSD2Live needs no Rust
// toolchain; after changing runtime/, refresh it (needs `rustup target add wasm32-unknown-unknown`).
tasks.register<Exec>("updateWasm") {
	group = "build"
	description = "Rebuilds the runtime for WebAssembly into the web player's resources."
	val runtime = rootProject.file("runtime")
	workingDir = runtime
	commandLine("cargo", "build", "--release", "--target", "wasm32-unknown-unknown", "--lib")
	doLast {
		runtime.resolve("target/wasm32-unknown-unknown/release/p2l_runtime.wasm")
			.copyTo(file("src/main/resources/io/github/psd2live/targets/web/p2l_runtime.wasm"), overwrite = true)
	}
}
