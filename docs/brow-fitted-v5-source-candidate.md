# Fitted brow v5 source candidate

Status: the 2026-11-12 central targeted run passes all 14 fitted-brow tests, with zero failures or skips. The combined source-candidate and historical binding batch passes 41/41. Central comparison reports all 444 historical package files unchanged. The serial root regression completed 1,298 tests: 1,133 passed, 165 conditionally skipped, zero failures or errors. This is local regression evidence, not a cross-platform release acceptance claim.

## Explicit scope

`brow/fitted@5` is an explicit opt-in candidate. It does not change the default version, existing presets, old source workers, the shared binding runtime, or any previous package version.

The first cohort is deliberately narrow:

- Complete shared `rig.scaffold@2`, `body/legacy@2`, `face/fitted@3`, `eye/fitted@5` prefix, in that order.
- Current built-in providers, with their real IDs, configurations, aliases, target sets and complete package references preserved. Intersecting anonymous applied records and unknown, foreign-scaffold or incompletely identified named providers are rejected before source measurement. Disjoint applied records and additional capability labels on a valid known owner do not change eligibility.
- One upright, measurable LEFT eye and one matching primary brow. Upright means the native mesh-framed head estimator returns exactly zero in both source phases; horizontal source rectangles alone are not sufficient. For a single eye without a bilateral baseline, the estimator uses the upper-feature to mouth/nose axis.
- One ordinary whole `MOUTH` drawing, without separate closed-mouth art, teeth, tongue, or layered mouth components.
- Flat source artwork without folders, an unsplit complete target inventory, and preset, unclipped Normal materials. Eye lid/shadow/highlight details are allowed. Side-lock hair and styled bilateral face fields are not.

The demonstrated upright fixture changes only the brow mesh and an existing brow-local carrier. Its shared head remains unchanged. No accepted upright fixture that changes non-brow meshes or the shared head has been demonstrated. The original horizontally drawn single-eye fixture is tilted under the native upper-feature to mouth/nose estimator and remains explicitly unsupported.

Right-eye, interior-mouth and bilateral/DS cohorts remain unsupported until separately implemented and proved. A rejection is not a parity result. Existing downstream workers keep their strict `brow/fitted@4` contracts; v5 is a prefix-only candidate, not an alternative identity for v4.

## Independent boundary

`RigBrowFittedV5Modifier` captures two sets of mesh-framed source observations, raw source topology/atlas UVs, raw raster resources, corrected brow measurements, face landmarks, source frame descriptors, appearance metadata, and provider snapshots. It does not call native deformer generation, `outlineShellSource`, a host projection, or a prior binding algorithm. The adapter measures with `generateDeformers=false`; fitted observations receive explicit head alignment because `headSpaceFor` intentionally returns null in such a context.

The isolated `browFittedV5` worker reconstructs both phases. The own effect is fitted brow travel; the predecessor reconstruction also includes scaffold/branch and body frames, face motion, ordinary eye and brow keys, gaze, hair sway, mouth keys, shell turn and face lean. These predecessor keys must be present before lowering even when the final projection removes their axes.

Affected targets are determined from reconstructed parent/frame changes, head alignment changes and changed canonical ancestors. Eye witnesses remain present during global shape lowering, then are excluded from the transition unless independently affected. Actual aliases are resolved from the captured providers and the live input once per canonical carrier. Multiple live provider aliases for one canonical carrier, collisions between canonical carriers, and unowned live output-ID collisions are rejected. Only the uniquely resolved live mappings count as recognized carriers; possible alternate-provider IDs do not confer ownership. Uncaptured live mesh or deformer children of a changed shared frame are rejected. The phase retains ordinary source organization, not generated semantic parts, because unused-carrier alias comparisons depend on that distinction.

`BrowFittedV5Phase` and `BrowFittedV5Meshes` are new isolated code. Published lower-level numeric kernels are reused unchanged, with no rewritten provider versions. Full native parameter order, signed zeros, local key ordering, shape neutralization and MouthOpen=1 projection are part of the compatibility contract.

## Central evidence and remaining gaps

The final targeted central run passes all 14 tests in `WorkspaceBrowFittedV5SourceTest`, including:

- Exact historical brow1 / brow4 / brow5 comparison for minimal and tuned/aliased prefixes: full stage IR, raw modifier output, runtime model, export, static head rotation and sampled poses. Native before/after source angles and captured alignment are exactly zero. The minimal fixture explicitly changes only the brow mesh and preserves the complete shared head frame.
- Strict separately installed JVM-worker replay against actual v4, including uninstall and cold reinstall.
- Raw-only cold capture with native positive controls, source-pixel invalidation, authored geometry/UV/topology/head-angle residual parity, and actual saved/applied-prefix archive reopening.
- Dependency and package admission, anonymous/foreign owner rejection, valid applied-owner capability labels, unique canonical carrier ownership, and foreign mesh/deformer rejection on a real changed brow-local carrier.
- Explicit rejection of the original tilted artwork, the other five single-eye side/interior profiles and bilateral source artwork. Bilateral rejection is not DS parity evidence.

The combined batch passes 41 tests with zero failures, errors or skips: 14 fitted-brow source tests, 15 composable-skin tests and 12 historical legacy-binding tests. Central comparison also reports all 444 historical package files unchanged. Reports are in `source-composed-r4-results/`, including `TEST-io.github.psd2live.application.WorkspaceBrowFittedV5SourceTest.xml`, in the shared validation artifacts.

These results establish the documented upright-left, prefix-only, local-brow cohort. They do not establish an upright source transition that changes non-brow meshes or the shared head. Implemented affected-target detection and rejection guards alone are not acceptance evidence for those cases.

### Isolated runtime closure

The earlier installed-worker failure was a missing `HairLegacyMotion` class family, which the shared worker intentionally excludes. Brow5 adds only `HairLegacyMotion*.class` from unchanged compiled main output to its own isolated worker. Runtime thinning retains these candidate-only classes in `algorithm.jar`; its manifest continues to use the unchanged common `runtime.jar`. Other workers, published numeric sources and versions remain unchanged.

A read-only constant-pool and JVM-descriptor audit of the then-built `algorithm.jar` plus manifest `runtime.jar` traversed 689 reachable project/umamo classes. The only absent project classes were `HairLegacyMotion`, `$Frame`, `$Plane` and `$Tuning`; the isolated include covers all four. Their dependencies were already present. The subsequent strict installed-JVM and cold-reinstall test now passes, providing runtime evidence beyond that static audit.

## Remaining acceptance gates

1. Finish the central full-root regression. The focused and combined results above do not substitute for its result.
2. Preserve exact parity, raw-only capture, immutable old package files, default selection and strict downstream version contracts through any further changes. Profiling covers instrumented deformer/face-turn stages; adapter call-graph and resource inspection remain necessary for uninstrumented keyform/lowering paths.
3. Before claiming shared-frame-changing support, add an honestly upright source whose independently measured transition actually changes non-brow descendants or the shared head. Expand right-eye, interior-mouth and bilateral/DS cohorts only one at a time with exact acceptance evidence; do not relax the current guards or reinterpret rejection as parity.

Build entry points: `:algorithms:binding:compileBrowFittedV5Kotlin`, `:algorithms:binding:pluginBrowFittedV5`, and the focused `WorkspaceBrowFittedV5SourceTest` suite. Do not run parallel Gradle builds against another active validation batch.
