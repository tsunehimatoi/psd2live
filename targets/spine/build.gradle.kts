// Spine 4.2 export target (MIT): skeleton JSON or binary + atlas with deformation baked from the host's evaluator.
// Must not depend on any GPL module.
plugins {
	kotlin("jvm")
	// Spine pose and binary readers, and MiniJson, which the DragonBones and glTF target tests share.
	`java-test-fixtures`
}

kotlin {
	jvmToolchain(21)
	explicitApi()
}

dependencies {
	api(project(":format-compile"))
	testImplementation(kotlin("test"))
}
