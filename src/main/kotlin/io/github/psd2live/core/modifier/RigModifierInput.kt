package io.github.psd2live.core.modifier

import io.github.psd2live.format.compile.RigIrBinary
import io.github.psd2live.format.model.RigIR
import java.security.MessageDigest

/** Includes upstream geometry, forms, relationships and sidecars with their exact persisted float bits. */
internal object RigModifierInput {
    fun fingerprint(rig: RigIR): String = MessageDigest.getInstance("SHA-256").digest(RigIrBinary.encode(rig))
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}
