// Neutral rig IR shared by every export target. MIT: it must not depend on any GPL module,
// nor contain code copied or adapted from Umamo.
plugins {
	kotlin("jvm")
}

kotlin {
	jvmToolchain(21)
	explicitApi()
}

dependencies {
	testImplementation(kotlin("test"))
}
