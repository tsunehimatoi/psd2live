package io.github.psd2live.format.eval

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import kotlin.test.assertNotNull

/** Native parity tests are optional in a JVM-only checkout. CI builds the library before running them. */
internal fun nativeRuntimeLibrary(): File {
	val library = P2lRuntime.locate()
	if (System.getenv("PSD2LIVE_NATIVE_REQUIRED") != "1") {
		assumeTrue(library != null, "Build the Rust runtime to check native parity")
	}
	return assertNotNull(library, "Missing native runtime for ${System.getProperty("os.arch")}")
}
