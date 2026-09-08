package org.umamo.interop

import org.umamo.format.cmo3.Cmo3TargetVersion
import org.umamo.format.moc3.moc.MocVersion
import org.umamo.runtime.model.RuntimeTarget

/*
 * The RuntimeTarget ↔ format-version mapping: which Cmo3TargetVersion / MocVersion each runtime
 * target reads from and persists as. It lives here rather than on the enum so :runtime stays free
 * of :format - the target's capability matrix (supports / restrictedFeatures) is runtime policy,
 * while the version encodings are a format concern owned by the conversion layer.
 */

/**
 * The moc version a MOC3 export of this target bakes: the matching version for a Cubism target,
 * the latest for NoTarget (nothing restricted), and V50 for Ayagami (its effective level).
 *
 * @return MocVersion The bake target.
 */
fun RuntimeTarget.mocVersion(): MocVersion =
	when (this) {
		RuntimeTarget.NoTarget -> MocVersion.V53
		RuntimeTarget.Ayagami -> MocVersion.V50
		RuntimeTarget.Cubism30 -> MocVersion.V30
		RuntimeTarget.Cubism33 -> MocVersion.V33
		RuntimeTarget.Cubism40 -> MocVersion.V40
		RuntimeTarget.Cubism42 -> MocVersion.V42
		RuntimeTarget.Cubism50 -> MocVersion.V50
		RuntimeTarget.Cubism53 -> MocVersion.V53
	}

/**
 * The CMO3 target version this target persists as, or null for NoTarget - a target-less document
 * has no target VERSION and persists through the latest sentinel instead (see
 * [cmo3TargetVersionNo]).  Ayagami has no CMO3 encoding of its own, so it persists at its
 * effective Cubism level: the gating level survives a CMO3 round-trip even though the Ayagami
 * identity cannot (a reopen shows Cubism 5.0; UMA will carry the identity).
 *
 * @return Cmo3TargetVersion? The CMO3-side version, or null for NoTarget.
 */
fun RuntimeTarget.cmo3TargetVersion(): Cmo3TargetVersion? =
	when (this) {
		RuntimeTarget.NoTarget -> null
		// Ayagami writes its effective Cubism level, in lockstep with cubismLevel and mocVersion.
		RuntimeTarget.Ayagami -> Cmo3TargetVersion.V50
		RuntimeTarget.Cubism30 -> Cmo3TargetVersion.V30
		RuntimeTarget.Cubism33 -> Cmo3TargetVersion.V33
		RuntimeTarget.Cubism40 -> Cmo3TargetVersion.V40
		RuntimeTarget.Cubism42 -> Cmo3TargetVersion.V42
		RuntimeTarget.Cubism50 -> Cmo3TargetVersion.V50
		RuntimeTarget.Cubism53 -> Cmo3TargetVersion.V53
	}

/**
 * The raw `targetVersionNo` a CMO3 save persists for this target: the version's literal, or the
 * "SDK(N/A)/Latest Cubism" sentinel for NoTarget.  Every target persists something.
 *
 * @return Int The value to write.
 */
fun RuntimeTarget.cmo3TargetVersionNo(): Int =
	// CMO3: CModelSource field targetVersionNo.
	cmo3TargetVersion()?.versionNo ?: Cmo3TargetVersion.LATEST_VERSION_NO

/**
 * Maps a CMO3 document's decoded target version to the runtime target it selects, with unknown or
 * absent values falling back to no restriction.
 *
 * @param Cmo3TargetVersion? version The decoded CMO3 target version, or null for unknown/absent.
 * @return RuntimeTarget The matching target, or [RuntimeTarget.NoTarget] for null.
 */
fun runtimeTargetOfCmo3Target(version: Cmo3TargetVersion?): RuntimeTarget =
	when (version) {
		null -> RuntimeTarget.NoTarget
		Cmo3TargetVersion.V30 -> RuntimeTarget.Cubism30
		Cmo3TargetVersion.V33 -> RuntimeTarget.Cubism33
		Cmo3TargetVersion.V40 -> RuntimeTarget.Cubism40
		Cmo3TargetVersion.V42 -> RuntimeTarget.Cubism42
		Cmo3TargetVersion.V50 -> RuntimeTarget.Cubism50
		Cmo3TargetVersion.V53 -> RuntimeTarget.Cubism53
	}

/**
 * Maps a baked moc's version byte to the runtime target it implies - the moc version is a hard
 * fact of the file, so an imported .moc3 starts at the matching Cubism target.
 *
 * @param MocVersion version The moc version.
 * @return RuntimeTarget The matching Cubism target.
 */
fun runtimeTargetOfMocVersion(version: MocVersion): RuntimeTarget =
	when (version) {
		MocVersion.V30 -> RuntimeTarget.Cubism30
		MocVersion.V33 -> RuntimeTarget.Cubism33
		MocVersion.V40 -> RuntimeTarget.Cubism40
		MocVersion.V42 -> RuntimeTarget.Cubism42
		MocVersion.V50 -> RuntimeTarget.Cubism50
		MocVersion.V53 -> RuntimeTarget.Cubism53
	}