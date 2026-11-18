// Export framework (MIT): target interface, capability profiles, loss reports and the lowering steps
// shared by exporters. Depends only on the neutral IR.
plugins {
	kotlin("jvm")
}

kotlin {
	jvmToolchain(21)
	explicitApi()
}

dependencies {
	api(project(":format-model"))
	testImplementation(kotlin("test"))
}

// The compiler version every export records, so two runs of one file are comparable.
val writeCompilerVersion by tasks.registering {
	val output = layout.buildDirectory.dir("generated/compiler-version")
	val version = rootProject.version.toString()
	inputs.property("version", version)
	outputs.dir(output)
	doLast {
		val file = output.get().file("io/github/psd2live/format/compile/compiler.properties").asFile
		file.parentFile.mkdirs()
		file.writeText("version=$version\n")
	}
}
sourceSets.main { resources.srcDir(writeCompilerVersion) }
