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
include(":format-model")
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
