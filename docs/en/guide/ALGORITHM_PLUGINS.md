# Algorithm plugins

Every preset modifier evaluates through an algorithm interface. Bundled Kotlin algorithms run in-process; external algorithms run in persistent worker processes and may be written in Python, Kotlin, Rust, C, C++, or C#. A package may provide multiple algorithms.

Bundled entries are catalogued and enabled independently by algorithm type and revision. Swing, simulation, physics, motion, expression toggles, eye/brow see-through and skin clipping have separate Kotlin implementation entrypoints. New instances pin a `builtin.*` identity, a full version and a content digest. Independently packaged algorithms hash the complete package; remaining entries still hash entrypoint bytecode. A mismatched digest is unavailable rather than silently replaced. Historical unpinned instances retain their original revision without rewriting history on open. `id=builtin,digest=builtin` remains a compatibility operation controlling the whole bundled set.

External packages may declare dependencies on bundled package IDs and versions returned by `plugin_list`. Installation locks their content digests; disabling or losing a required bundled version makes the dependent package unavailable too. The aggregate `builtin` identity is reserved. Installable `builtin.*` archives must declare a stable type alias matching their package ID and algorithm revision, for historical bundled algorithm restoration. Enabled states persist in preferences but evaluation reads an in-memory snapshot instead of accessing system preferences per entry.

Except for physics groups, motion, swing, baked simulation, expression toggles, eye/brow see-through and skin clipping, these entries still compile and ship with the application, rather than independently installable JARs. Binding algorithms still call shared application generation services; the entrypoint digest does not cover the complete application and all underlying dependencies. Independent package distribution for the remaining algorithms, retaining their historical binaries and local binding computation remain migration work. Individual management and pinned identities do not establish completion of that work.

## Install and remove

The physics-group implementation now lives in `:algorithms:physics`. `PendulumAlgorithm` implements the public evaluation interface; configuration parsing, group conversion and output conflict checks run in that module using only `algorithm-api`. Application registration delegates to it, while ordinary physics export and other generators reuse the conversion. A process entrypoint uses `algorithm-worker`: `./gradlew :algorithms:physics:pluginPackage` produces `algorithms/physics/build/plugin`, containing `plugin.json` and a self-contained `algorithm.jar` accepted by `plugin_install`. Its package ID is `builtin.70687973696373.v1`, with a `/pendulum` entrypoint in that namespace. `aliases: ["physics"]` preserves the saved type, algorithm revision and output IDs.

The application ships and retains the complete physics, motion, swing, baked simulation, expression toggle, eye/brow see-through and skin clipping packages; new instances pin its full content digest. An exact match to the current package runs in-process. Historical references run through a worker only when the exact archive is installed, without falling back to the current implementation. Older archives can be removed and reinstalled; the current application bundle can only be enabled or disabled. Development-era references containing only an entrypoint-bytecode digest are not rewritten or substituted by this package; missing original contents remain unavailable.

The motion implementation lives in `:algorithms:motion`, with package ID `builtin.6d6f74696f6e.v1`, entrypoint `/clip` and stable alias `motion`. `MotionData` provides the original data model, JSON codec and curve conversion shared by the editor and standalone worker. The module does not depend on playback sessions, UI or Umamo. Build it with `./gradlew :algorithms:motion:pluginPackage`; the output directory is `algorithms/motion/build/plugin`.

The swing implementation lives in `:algorithms:swing`: package `builtin.7377696e67.v1`, entrypoint `/sway`, stable alias `swing`. Mesh wrapping, keyform evaluation and swing physics groups run in the algorithm module without project or history APIs. It uses the GPL Umamo engine and Cubism format converter, so it is a GPL algorithm, not part of the MIT API/format modules. It does not include the proprietary Cubism SDK. Build with `./gradlew :algorithms:swing:pluginPackage`.

Baked simulation evaluation lives in `:algorithms:simulation`: package `builtin.73696d756c6174696f6e.v1`, entrypoint `/baked`, stable alias `simulation`. Input includes the existing bake and captured automatic weights. The plugin checks bake validity and upstream inputs before writing weights, mode keyforms and physics groups. Authored weights take precedence; missing or stale bakes reject evaluation rather than produce partial content. Numerical baking remains in its existing pipeline without solver changes. This module also uses the GPL engine. Build with `./gradlew :algorithms:simulation:pluginPackage`.

An algorithm may declare `derivedOutputs` to retain existing deterministic names. Each rule contains `kind`, `localName`, `prefix`, `suffixes` (default `[""]`) and `numbered` (default `false`). The host accepts only `prefix + instance.outputId(localName) + suffix`; numbered rules additionally allow canonical decimal suffixes `_2` through `_10000`. Rules cannot cross object kinds or declared write scopes, and do not accept arbitrary regular expressions. Swing uses `Warp_Swing_` and `PhysicsSwing_` rules to preserve saved object IDs.

Use Preferences → Algorithm plugins to select a ZIP or enter an absolute directory or an HTTPS, SSH or git:// Git URL. Git sources accept an optional branch, tag or full commit hash and record the resolved commit. The repository root must contain a built package. The installer does not fetch submodules, run setup scripts, or compile source.

Packages include their platform executable and runtime dependencies. JVM workers use the application's Java runtime. Python/.NET packages must not depend on a separately installed development environment. Plugins execute local code; process isolation is not a security sandbox.

Installation validates the protocol, platform, schemas, file inventory, and SHA-256 checksums before atomic publication. Identical installation is idempotent. A package version cannot be overwritten with different contents. Versions coexist; modifier instances pin package version and digest.

Uninstall preserves document instances, configurations, and overrides. Missing/disabled plugins are marked unavailable. Because their output is unknown, the following stack suffix conservatively pauses. Affected documents cannot export. Reinstall the exact package to resume evaluation. Bundled algorithms can be disabled/restored but their application files cannot be removed. Package changes refresh open models in the background without adding history.

Preferences record the installation directory and enabled state. The default directory is `.psd2live/plugins` under the user's home directory. Projects, history, and preset JSON contain references, never executable payloads.

## MCP

GUI and MCP share one management service. `plugin_list` returns package identity, enabled state, algorithm configuration schemas/defaults, dependencies, targets, and read/write domains; `problems` reports unreadable packages.

`plugin_inspect` and `plugin_install` accept `source` and optional Git `ref`. For example:

```json
{"source":"D:/plugins/example-python.zip"}
```

`plugin_enable`, `plugin_disable`, and `plugin_uninstall` take the `id` and `digest` returned by the list, including individual bundled identities. The legacy pair of `builtin` values only supports aggregate enable/disable, not uninstall. All operations except listing return process jobs; use `job_wait` or `job_get`. No open project is required.

Use the existing `modifier_plan`, `modifier_add`, and `modifier_update` operations for external algorithms. Configurations are validated against their package schema. Plans pin their package reference; changing plugins invalidates prepared candidates. Public operations declare strict result schemas.

## Authoring and protocol

The JVM contract lives in the independent `:algorithm-api` module used by the application. It depends only on the neutral `:format-model` module and JSON serialization, not the application, Umamo, Compose, projects or history. `./gradlew :algorithm-api:build` produces the version `1.2.0` API and sources JARs.

`ModifierAlgorithmPlugin.evaluate(input, instance, checkpoint)` receives the upstream `RigIR`, an immutable `RigModifierInstance` and a cancellation checkpoint, and returns a candidate `RigIR`. Do not mutate input arrays; invoke the checkpoint in long loops and let failures propagate. Generate identities with `instance.outputId(localName)`, which preserves recorded aliases. The host owns output validation, dependencies, overrides, CAS and history. Targets, pinned package references, manifests, version ranges and dependency declarations are public in the same module. Existing JSON fields, serialized type names and object ID encoding are unchanged.

The API JAR is not an installable algorithm package. An independently compiled contract does not mean bundled implementations have left the application; package loading and historical implementation retention remain subject to the migration limitations above. External workers in all six languages continue to use the versioned protocol below.

### JVM model results

`algorithm-worker` runs public algorithm implementations in a persistent JVM. An entrypoint calls `AlgorithmWorker.run(mapOf((type to version) to implementation))` without loading application, editor or project code. Reserve stdout for protocol responses. Requests include the complete instance in `invocation`, including persisted output aliases, while keeping the existing `instance`, `config` and `targets` fields for other languages.

When the host advertises `rig_objects`, a response can contain `objects` instead of `commands`, never both. Its exact fields are `changed`, `removed`, `mesh_order` and `deformer_order`. Relative to the request input, it lists changed `frame`, `mesh:<id>` and `deformer:<id>` resource references and removed keys. References contain `sha256` and `bytes`; payloads use `RigIrObjects` binary encoding. The host checks hashes, sizes, object types and identities, with limits of 64 MiB per resource and 512 MiB total. Resource references cannot contain paths.

The host merges the object delta into the complete upstream input, preserving content excluded by read/write projection. It then checks structure, numeric values, declared writes and output IDs before overlays and the candidate transaction. Both execution modes call the same algorithm; the host does not rerun the physics algorithm from configuration commands. Other languages retain their existing command response protocol.

See [six-language examples and packaging instructions](../../../examples/plugins/README.md). Package-root `plugin.json` contains:

- `protocol: 1`, package `id`, `version`, and `name`.
- `commands`: argument arrays indexed by platform, e.g. `windows-x86_64`. JVM workers use `jvm: ["$JAVA", "-jar", "worker.jar"]`. Commands do not pass through a shell.
- `files`: relative payload paths and SHA-256 digests. Traversal, symlinks, and unlisted files are rejected.
- `algorithms`: `package-id/type`, algorithm version/name, targets, dependencies, exclusivity, read/write domains, composable kinds, `configSchema`, and `defaults`.
- Optional `parameters` declare shared parameter `id/name/min/max/default`. Existing definitions must be compatible; other new objects use instance-owned IDs.

Requests/responses are one UTF-8 JSON line each. Stdout is protocol-only; logs go to stderr. Requests contain `protocol/id/method/type/version/instance/config/targets`. Responses echo `protocol/id` and return `commands` or `error`. A worker handles one request at a time; faults/timeouts reset it. Cancelling an active request terminates its worker without committing partial results. Queued requests can be cancelled independently without interrupting another active request; cancellation is also checked while awaiting a response.

Inputs are projected to declared read/write domains; `*` or unknown domains conservatively receive the full input. `changed` and `removed` update the object index, with `reset` starting a session. `frame`, `mesh:<id>`, and `deformer:<id>` point to content-addressed resources in the request's `resource_directory`, also available as `PSD2LIVE_PLUGIN_RESOURCES`. These use the versioned exact `RigIrObjects` binary format. `mesh_order` and `deformer_order` preserve order.

The portable `meshes` view exposes target IDs, opacity, and position/UV buffer references; `parameters` exposes readable parameters. Float resources use little-endian `f32le`, named by SHA-256. Commands may also return such references in place of float arrays; the host validates checksums and finite values before expanding them.

Commands supported:

- `mesh_opacity`: target `id` and `value` in 0–1.
- `physics` and `motion`: `config` uses the corresponding bundled modifier format; the host assigns output IDs.
- Authoring primitives: `structure`, `set`, `parameter_keys`, `delete`, `canvas_geometry`, `canvas_topology`, `canvas_create_warp`, `canvas_create_rotation`, `canvas_create_glue`, `canvas_glue_edit`, `path_put`, and `path_delete`, using authoring-journal formats. They execute only on an isolated candidate.

New IDs use `modifier:<instance-id>:<UTF-8 hex of local name>`. The host validates actual writes, shared parameters, dependencies, overrides, and conflicts. Plugins never access document history or UI directly.

## Performance boundaries

Bundled calls avoid IPC. External workers reuse processes, content-addressed buffers, and incremental input synchronization. Author input, binding templates, and algorithms with declared dependencies have separate caches. Folder edits do not evaluate algorithms.

The acceptance limit is four seconds from reading the PSD through complete presets, preview and initial project history, minus the actual synchronous simulation-baking time. Baking must still finish; it cannot be deferred or omitted. Reports show total time, excluded baking time and counted time separately for first and repeated generation. Further baking optimization is currently deferred.

Some bundled binding algorithms still generate templates and extract their local contribution; their full separation into local algorithms is not complete. Stack evaluation remains serial, without independent-item scheduling. Plugin support and cache hits do not establish performance acceptance; first and repeated generation are evaluated separately.

## Versions and dependency graph

`protocol` versions the wire contract. Package `version` uses `major.minor.patch`, optionally with prerelease/build identifiers. Algorithm `version` remains the persisted integer revision; changing implementation language does not renumber it. Build metadata does not affect precedence, while content hashes distinguish payloads. Early integer package versions compare as `N.0.0`; publish new packages with full versions.

Package `dependencies` are code prerequisites; `conflicts` prevent simultaneous activation. Algorithm `requires` instead requires preceding model results on matching targets. It does not install packages or invoke another worker.

```json
{
  "dependencies": [{"id":"geometry-core","version":{"minimum":"1.2.0","maximum":"2.0.0"}}],
  "conflicts": [{"id":"other-cloth","version":{"exact":"1.0.0"}}]
}
```

Minimum is inclusive; maximum is exclusive. Exact cannot be combined with bounds. Omitted `version` accepts any version. Prereleases follow numeric/text identifier precedence without an implicit prerelease exclusion.

Installation chooses the highest enabled matching version and locks the transitive closure. Missing prerequisites, cycles and conflicts reject installation/activation. Dependencies are not downloaded automatically. Conflicts are symmetric even when declared by only one package. Disabling/removing prerequisites stops workers and leaves dependents installed but unavailable; restoring the original contents recovers them.

The installation stores dependency locks. Persisted plugin references additionally contain `dependencyDigests` (`package@version` to content hash). Installing a newer prerequisite cannot change existing instances. Reinstalling a package against different dependencies does not silently upgrade an old project.

`plugin_list` / `plugin_inspect` return `available`, `issues`, `dependencies`, `conflicts`, and `resolved_dependencies`. `enabled` describes the user switch, not actual availability. Preferences display the failure reasons. Request `dependencies` contains locked package identities, not callable plugin handles. Algorithms collaborate through model inputs and stack dependencies.

## Low-level interface and ownership

| Module | Responsibility |
| --- | --- |
| `ModifierAlgorithmPlugin` | Serializable package/algorithm/parameter/version contracts |
| `ModifierPluginDependencies` | Numeric version ordering, ranges, graph and conflict resolution |
| `ModifierPluginStore` | Validation, atomic installation, dependency locks and removal |
| `ModifierPluginWorkers` | Persistent workers, cancellation, timeouts and binary transport |
| `ModifierPluginOutput` | Candidate-only model commands and write-scope validation |
| `RigModifierEvaluator` | Stack semantics, parameter reuse, overrides, contributions and caching |
| Application layer | Shared GUI/MCP jobs, CAS and history publication |

Requests advertise supported command names in `capabilities`.

| Purpose | Commands | Typical write components |
| --- | --- | --- |
| Parameters, folders, links, parents | `structure` | `parameter/hierarchy/relations/mesh/deformer/part` |
| Keyforms and channels | `set/copy/delete/parameter_keys` | `mesh/deformer/parameter/glue` |
| Geometry, topology, deformers | `canvas_geometry/canvas_topology/canvas_create_warp/canvas_create_rotation/warp` | `mesh/deformer/hierarchy/authoring` |
| Glue and paths | `canvas_create_glue/canvas_glue_edit/path_put/path_delete` | `glue/authoring` |
| Vertex weights | `vertex_group_put/vertex_group_delete` | `authoring` |
| Physics and motion | `physics/motion` | `physics/motion` |
| Target opacity | `mesh_opacity` | `mesh` |

Declare all components actually changed. Other recognized components are `textures/canvas/advanced/hit_areas/rest_pose`; `*` covers the whole model. Declaring a component does not imply a specialized write command exists: direct external texture creation is not currently provided. Less granular reads use conservative cache keys.

Example channel edit:

```json
{"op":"set","target":"mesh:cloth","key":{"ParamAngleX":30},"channels":{"opacity":0.8}}
```

Weights use `vertex_group_put` with `target`, `name`, `kind`, and one `weights` value per vertex, or an f32le buffer reference carrying `sha256/bytes/count/format`. Supported weight kinds and detailed command fields follow the corresponding authoring primitives. Parameter creation uses `structure.edits` with `action=create,kind=parameter,id,name,min,max,default,parent_id`. Shared parameters must be declared with compatible definitions; other new objects use deterministic instance-owned IDs.

Commands execute in order on an isolated candidate. Host checks include sidecar authoring data (weights/paths), textures, canvas and render hierarchy, and report conflicting writes. Declaring only `mesh` does not authorize changes to authoring weights. Workers cannot directly publish history or documents. Process separation provides fault isolation, not a security sandbox.


### Author input context

API 1.2.0 adds the optional `ContextualModifierAlgorithmPlugin`, receiving a separate `ModifierAlgorithmContext`. `values` holds structured author inputs and `resources` maps logical names to immutable binary data. Inputs are separate from editable configuration and expose no project, history or local paths. The original plugin interface remains compatible.

The host advertises `author_context_v1`. The optional request `context` contains `version: 1`, a metadata resource reference `values`, and a logical-name-to-reference map `resources`. References use SHA-256 and size validation. Metadata is limited to 1 MiB; resources to 64 MiB each, 10000 entries and 512 MiB total. Identical payloads are written once. Unsupported versions and corrupt resources fail evaluation. An omitted context is empty.

Host definitions capture inputs using `authorContext`. Both prefix and component-dependency caches compare context contents, invalidating results when resources change. The API and transport are implemented; layer semantics and original-image adapters for the remaining binding algorithms are still being migrated and do not yet all use this entrypoint.

Expression application now lives in `ExpressionAlgorithm` in `:algorithms:binding`, implementing `ContextualModifierAlgorithmPlugin`. It constructs opacity tracks, merges authored channel residuals and adds expression inputs to chain physics without application, history or project services. Its `schema: "expression/1"` context contains `layers` (parameter names and optional switch indices), `meshes` (mesh-to-layer mapping), `opacity` (source opacity) and `chain_owners` (preceding chain modifier identities). The binary `parameters` resource is a `RigIrBinary` empty model carrying resolved parameter definitions only. `./gradlew :algorithms:binding:pluginPackage` produces `algorithms/binding/build/plugin`, package `builtin.65787072657373696f6e2e746f67676c6573.v1`, entrypoint `/toggles`, with the stable alias `expression.toggles`. The current package executes in-process; historical workers receive the same context. Host author-input adapters are attached only to known bundled aliases and algorithm revisions, with dependency, exclusion and composable-write declarations checked. Dynamic shared parameters are approved by the host from captured author inputs. Newly added definitions must exactly match that approval; workers cannot expand the allowlist. Aliases and canonical entrypoints use the same adapter. Standard-name collisions still use the compatibility generator for parameter resolution.

`MoodChannels` is an independent color kernel in the binding module, shared by complete generation and local templates. When blush/gloom follows existing bindings, the ordinary path reuses source geometry before target lowering, updates parameters and color/opacity tracks, then runs the original Cubism structural conversion. It does not reuse already folded keyforms: extra color axes can require turn carriers. Standard-name conflicts and missing reusable preceding inputs retain full generation. Disabling the stage cache also uses full generation for equivalence checks. These two modifier types do not yet have independently installable entrypoints.

`BlendShapeLowering` provides independent Cubism blend-shape conversion to keyforms and carrier deformers in the GPL binding module. Callers supply naming and cancellation callbacks; it has no application localization, project or history dependency. The application adapter preserves existing names and deterministic object IDs. Additional color axes can change keyform budgets, so local color evaluation must still run this step from pre-conversion input. This kernel does not establish completion of independent entrypoints for the remaining binding modifiers.

`MoodBinding` accepts the pre-conversion binding model, captured parameter definitions/order and per-mesh color weights. It evaluates color parameters, parameter-tree entries, generated tracks and Cubism conversion, returning both the pre-conversion input and final model. Repeated evaluation replaces owned color tracks without accumulating parameters; undeclared meshes retain their content. It does not accept project objects or edited previews; the host still merges authored overrides. This is a lower-level color evaluation interface, not completion of installable modifier entrypoints and their host override adapters.

`BindingComparison` caches canonical mesh/deformer records by object identity within one candidate. Local clipping checks compare only the target mesh and specified ancestors, without converting the entire model. Geometry array contents, channel keyforms, blend shapes and other mesh properties still participate in comparison. The cache must not be reused across mutable inputs.

`SeeThroughBinding` accepts captured coverage candidates, settings, naming and ID callbacks to compute feature copies, inverted bang masks, draw order and inherited opacity independently. Preview refresh accepts previous/current models and copy/window maps without reading project or UI state. The application `SeeThroughLayers` retains source-pixel coverage detection and configuration/localization adapters, preserving its types and call interface. Both modifiers evaluate through the contextual `SeeThroughAlgorithm` entrypoint. The `schema: "see-through/1"` context contains coverage `candidates`, the `eyes` category, `earlier_owners`, `draw_order_step`, `inverted_masks` and a localized `name_pattern` containing exactly one `{meshName}`. The host computes and shares pixel coverage from captured sources; the plugin filters candidates and bangs in current model order to preserve output ordering. Configuration `values.opacity` retains the 0–100 range. Gradle tasks `pluginEyeSeeThrough` / `pluginBrowSeeThrough` produce `algorithms/binding/build/plugin-eye` / `plugin-brow`, retaining `eye.see-through` / `brow.see-through` aliases with `/visibility` entrypoints. Packages can be installed, restored and evaluated in persistent workers; the current bundled versions execute in-process.

`ModelIdRemap` is the GPL binding module’s public reference-rewriting utility for keyform axes, blend-shape limits, parents/parts, masks, glue, parameter trees/links, paths, vertex groups and render trees. It applies caller-supplied maps; it does not allocate identities or bypass host conflict validation. The host still owns instance-derived IDs and write permissions. Unaffected model partitions and meshes retain their references for read-only input caching. The application `RigIdRemap` is a compatibility alias.

The binding module exposes `GeometryResidual`, `MeshBlendResidual` and `WarpBlendResidual` for three-way keyform geometry, mesh blend-shape and deformer blend-shape merging in authored coordinate frames. `BindingVertices` transfers x/y values through captured vertex-index or barycentric correspondences. Inputs are read-only models and correspondences; evaluation loops accept cancellation callbacks and return geometry grids/channels or blend shapes. These kernels do not read projects, history or UI state and do not commit results. Instance override ownership, conflict validation and atomic publication remain host responsibilities. Existing application names remain compatibility aliases.

`BindingFrames` exposes `carriedToParent`, `pointsInParent` and `reparent` to transport rest meshes, keyforms and blend shapes into a destination parent frame, including inversion limited to the authored branch below a shared ancestor. Ordinary editor rebinding and modifier merging share the implementation. Unreproducible neutral poses and parent cycles reject the result. The independent API accepts cancellation checkpoints, forwarded by modifier evaluation; it neither changes model identities nor publishes project state.

`BindingMerge.apply` is the independent binding-result merge entrypoint: it accepts the current read-only model, instance and candidate-scoped `BindingMerge.Source`, and returns a new model. `Source` supplies before/after generated projections, fixed ID maps, static coordinate projections, parameter compatibility decisions and vertex correspondences. It must not read live project/UI state or publish edits. Carrier insertion/removal, parameter trees/links, keyforms, blend shapes and masks are merged inside the module. `BindingAxes` shares axis union and bounded keyform coordinate enumeration. This is an in-process calculation contract, not a completed external transport protocol: the product adapter still supplies generated templates and mesh correspondences, while the host adds physics, mouth rest-pose policy and transactions. Remaining algorithms still require local template replacement and resource-backed inputs.

`SkinClipAlgorithm` adds face masks directly to skin-overlay IR meshes without model conversion or full binding generation. Its `schema: "skin-clip/1"` captures ordered face objects/bounds in `faces`, source-unclipped candidate overlay bounds in `overlays`, and targets already owned by preceding instances in `already_active`; instance targets determine scope. It retains the original 85% overlap rule, accepts identical masks and rejects conflicting authored masks/inversion, leaving other fields and objects unchanged. Source clipping, preceding owners and duplicate-target mappings are captured by the host. `./gradlew :algorithms:binding:pluginSkinClip` produces `algorithms/binding/build/plugin-skin`, retaining the `face.skin-clip` alias and `/clip` entrypoint suffix. Parameter declaration and evaluation for this entry no longer invoke binding templates; later algorithms may still generate templates containing clipping for their own inputs.

When a downstream binding requests a source template ending in skin clipping, the host reuses the preceding template and invokes the same clipping plugin with identities and mask state only, without rebuilding geometry. Changed masks are also applied lazily to the pre-lowering input; unrelated masks and channels remain untouched. Applied prefixes use captured clipping inputs without looking up removed active instances. Disabling stage caching retains full generation for equivalence checks. Other binding modules still build their own templates.

Mouth-outline source artwork is cached by captured analysis, authored split/deletion state, outline color/width, mesh settings and language. Motion recipes, binding strength and tuning do not affect this artwork key. Binding templates reuse lip rasters and appended atlas pages instead of repeatedly extracting contours and encoding identical images. Disabling stage caching recomputes these inputs. This optimizes host input preparation; mouth outlines do not yet have an independent installable entrypoint.

Captured-input contracts for historical built-in packages are checked when consumed. If dependency, exclusivity or composable-write declarations disagree with the host adapter, parameter queries, input capture and evaluation for that exact package fail with its package ID/version. Merely having it installed no longer prevents unrelated projects from constructing their algorithm catalog. Incompatible packages do not fall back to the current built-in algorithm.

`MouthContour`, `MouthColumn` and `MouthStrokeMesh` provide independent outline geometry kernels: boundary extraction from x/y vertex arrays and triangle indices, arc-length sampling of the upper/lower lips, crossed-tip paths, and fixed-topology ribbons with round joins/end caps. Callers supply captured geometry without product `MeshData`, project or UI objects. The product retains thin adapters and type aliases. Source semantics, rasterization, atlases and instance output merging still require migration into the outline plugin entrypoint. These geometry APIs neither publish results nor allocate project object IDs.

`MouthOutlineRaster` converts captured lip columns, color, width and a coordinate mapping into RGBA pixels, canvas placement and an opaque-pixel count. `perimeterColor` selects outline color from the exterior boundary of raw RGBA, excluding interior details and transparent RGB noise. Rasterization retains the original AWT triangle filling and seam strokes, accepts cancellation checks and releases graphics resources on failure. Product adapters classify source layers and wrap output layers; this kernel is not yet a complete independently installable outline modifier.
