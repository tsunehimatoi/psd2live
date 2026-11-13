# Complete explicit source-brow candidate

This opt-in chain is not a default-version change:

`rig.scaffold@2 → body/legacy@2 → face/fitted@3 → eye/fitted@5 → brow/fitted@5 → brow.shape@7 → brow.lid-coupling@9 → brow.blink@7 → brow.spring@8`

## Scope

The first cohort is the intersection of the existing fitted-brow and source-lowering contracts: upright single-left-eye artwork, exactly one ordinary eyebrow, eyewhite and eyelash, one whole `MOUTH` (not `MOUTH_OPEN`), and no mouth interiors. Source artwork is flat, complete, original, unsplit and uncopied. Skeletons, custom structure, unsupported material classifications, anonymous owners and partial/foreign target scopes are rejected. Existing fitted5 and shared numerical kernels are not widened. Material admission is necessary but not sufficient: the actual preceding mesh/carrier state must still be exactly reproducible.

Shape7 reproduces fitted5's prior state from source observations and lowers the shaped state separately. Its before state uses existing parameters; its after state adds the same six X/Angle/Form parameters as Shape6, preserving compatible reuse, parameter-tree placement and output aliases. Both states pass full prior-mesh, full ancestry, one-to-one owner alias and carrier-scope checks through the future-only transition kernel.

Lid9 keeps Lid8's numerical eye coupling algorithm in a separate implementation. It uses actual upstream eye ancestors and ordinary gaze/brow-axis witnesses, preserves world-space validation and default-rest compensation, and retains existing carrier identities and foreign-descendant guards. It accepts only fitted5 with shape7.

## Version contracts

Blink7 accepts exactly `(fitted4, shape6, lid8)` or `(fitted5, shape7, lid9)`.

Spring8 accepts exactly `(fitted4, shape6, lid8, blink6)`, `(fitted4, shape6, lid8, blink7)`, or `(fitted5, shape7, lid9, blink7)`.

Version membership alone is insufficient. The complete tuple, required order, scaffold/base/dependency IDs, full target inventory, captured package references and output aliases must match. Missing required providers are reported before source capture.

## Capture and isolation

- Shape7 captures the prefix at its own actual position, excluding itself.
- Blink7 captures the real shape owner's position, including applied-source ownership.
- Spring8 captures its own actual prefix.
- Shape7, Blink7 and Spring8 transmit only numerical observations and `source_meshes`; no generated brow motion or branch resource is admitted.
- Lid9 transmits eye-source measurements/topology plus its exact source inventory and provider witnesses.
- Native measurement contexts may still generate discarded host motion. This is not a claim of zero dynamic host work.
- Shared future-only provider and transition helpers remain in an isolated compilation. Frozen ancestry/lowering files and published worker source closures are unchanged. New helpers do not enter binding `main` or historical packages.

## Dense-mesh boundary

A genuinely dense required-only source fixture can already fail frozen Lid8's prior-carrier resolution (`ArtMeshEyelashL_Turn`), before any Blink implementation runs. It is not evidence of a Blink failure, nor a valid positive baseline for claiming complete-chain dense support. The candidate retains fail-closed carrier checks rather than changing frozen Lid8 or adding a new Lid9 mapping algorithm in this change. Real historical carrier-witness coverage is kept separate from complete-chain acceptance coverage.

## Frozen alias baseline

The ordinary and deformer-only-alias fixtures retain exact native/historical/source comparisons. The combined deformer and parameter-folder alias fixture has a pre-existing native1 versus historical-chain difference: stages 4–10 differ only in `parameterLinks`; stages 11–14 differ in `parameterLinks`, `parameterTree` and `deformers`. The historical chain and the new source chain remain exactly equal in every raw field, binary result and cold replay for that fixture. Tests pin the historical difference fields rather than normalizing candidate output. Folder-only aliases are not inferred to be supported by frozen fitted4. The observed parameter-link difference is ordering only: native1 uses `(ParamAngleX, ParamAngleY)`, `(ParamBodyAngleX, ParamBodyAngleY)`, `(ParamEyeBallX, ParamEyeBallY)`; the historical chain keeps the body pair before the head pair, followed by the eye pair.

## Validation

The dedicated selector is `io.github.psd2live.application.WorkspaceSourceBrowChainTest`. It compares actual native, historical and new chains using the same artwork, instance IDs and settings; exercises direct and installed workers, runtime/export, aliases, parameter reuse, tuned controls, replay, archives, applied prefixes and physics; and checks corrupted source/owner/geometry/parameter boundaries.

Regression selectors include `WorkspaceBrowBlinkV7Test`, `WorkspaceBrowSpringV8Test`, historical fitted/shape/lid tests and bundled artifact compatibility checks. Builds and runtime tests are owned by the central integration task; this candidate's source worktree does not execute them. Static review is not a runtime pass. Historical package hashes and locks must be checked after the central build, before integration.

Central joint validation at `35eff20` passed all 61 tests with no skips: complete source chain 22, Blink7 20 and Spring8 19. All 456 historical package files remained byte-identical. The full root regression is running separately and is not yet recorded as passed here. No default selection or dense-support expansion is part of this candidate.

The subsequent serial root regression at `35eff20` passed: 1,407 tests, 1,242 passed, 165 conditional skips, zero failures or errors, in 25m51s. All 456 previously published algorithm package files remained byte-identical. CheekLid10 remains a separate, unvalidated candidate and is not part of this batch.
