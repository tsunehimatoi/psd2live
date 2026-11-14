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

include(":umamo")
include(":umamo-ir-bridge")
include(":format-model")
include(":algorithm-api")
include(":algorithm-worker")
include(":algorithms:physics")
include(":algorithms:motion")
include(":algorithms:swing")
include(":algorithms:simulation")
include(":algorithms:binding")
include(":format-compile")
include(":targets:cubism")
include(":targets:raster")
include(":targets:psd")
include(":targets:spine")
include(":targets:runtime")
include(":format-eval")
include(":targets:web")
include(":targets:gltf")
include(":targets:unity")
include(":targets:dragonbones")
