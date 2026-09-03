pluginManagement {
	repositories {
		mavenCentral()
		gradlePluginPortal()
		google()
	}
}

dependencyResolutionManagement {
	repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
	repositories {
		mavenCentral()
		google()
	}
}

rootProject.name = "psd2live"

// Keep the new application independent while consuming Umamo's format/runtime
// modules from the checked-out reference tree. Explicit substitution avoids
// publishing local snapshots or copying the reverse-engineered codecs.
includeBuild("../umamo") {
	dependencySubstitution {
		substitute(module("local.umamo:format")).using(project(":format"))
		substitute(module("local.umamo:runtime")).using(project(":runtime"))
		substitute(module("local.umamo:interop")).using(project(":interop"))
		substitute(module("local.umamo:render")).using(project(":render"))
	}
}

