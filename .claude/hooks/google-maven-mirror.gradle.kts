// Use Google's Maven Central mirror in place of repo.maven.apache.org, which rate-limits this environment (HTTP 429).
// Replaced rather than put first: a 429 from Central fails resolution even for artifacts another repository has.
val mirror = "https://maven-central.storage-download.googleapis.com/maven2/"

fun RepositoryHandler.useMirror() {
	val central = filterIsInstance<MavenArtifactRepository>().filter { it.url.host == "repo.maven.apache.org" || it.url.host == "repo1.maven.org" }
	if (none { it is MavenArtifactRepository && it.url.toString() == mirror }) maven(mirror) { name = "GoogleMavenCentralMirror" }
	removeAll(central.toSet())
}

settingsEvaluated {
	pluginManagement.repositories.useMirror()
	dependencyResolutionManagement.repositories.useMirror()
}
allprojects {
	buildscript.repositories.useMirror()
	afterEvaluate { repositories.useMirror() }
}
