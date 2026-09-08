package org.umamo.format.cmo3.serialize

/**
 * Builds a [SerializeEngine] wired with every typed Cubism subsystem implemented so far. Tags not
 * yet modelled fall back to [VerbatimNode] (reported via [diagnostics]), so the result always reads
 * and writes a complete model losslessly - coverage simply grows over time.
 *
 * @param SerializeDiagnostics diagnostics Sink for unmodeled-tag reports (default no-op).
 * @return SerializeEngine The configured engine.
 *
 * @see <a href="https://docs.umamo.org/format/CMO3.md">CMO3.md §3 Payload: main.xml</a>
 */
public fun cubismEngine(diagnostics: SerializeDiagnostics = SerializeDiagnostics.None): SerializeEngine {
	val registry = SerializerRegistry()
	// Subsystems are added here as they are typed; order is irrelevant (tag-keyed).
	registerIdentitySubsystem(registry) // *Guid + *Id
	registerValueTypeSubsystem(registry) // GVector2, CRect, CColor, CAffine
	registerMeshSubsystem(registry) // CoordType, PointInTriangle, PointOnCurve, MeshPointRef
	registerCustomSubsystem(registry) // <file> + attribute-serialized leaves + CModelSource root
	org.umamo.format.cmo3.serialize.gen.registerGeneratedSubsystem(registry) // bulk reflective (generated)
	return SerializeEngine(registry, diagnostics)
}