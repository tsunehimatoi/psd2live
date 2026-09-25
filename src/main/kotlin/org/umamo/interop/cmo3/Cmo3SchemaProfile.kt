package org.umamo.interop.cmo3

import org.umamo.format.cmo3.Cmo3TargetVersion

/** The selected editor schema is separate from the model's runtime feature ceiling. */
internal val Int.supportsCmo3ExtendedBlend: Boolean
	get() = this == Cmo3TargetVersion.LATEST_VERSION_NO || this == Cmo3TargetVersion.V53.versionNo

/** These classes only occur in the 5.4 corpus, and must not appear in older fresh projects. */
internal val Int.supportsCmo3V54Classes: Boolean
	get() = this == Cmo3TargetVersion.LATEST_VERSION_NO
