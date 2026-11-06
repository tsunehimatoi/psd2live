# Algorithm plugins

Every preset modifier evaluates through an algorithm interface. Bundled Kotlin algorithms run in-process; external algorithms run in persistent worker processes and may be written in Python, Kotlin, Rust, C, C++, or C#. A package may provide multiple algorithms.

Bundled entries are catalogued and enabled independently by algorithm type and revision. Swing, simulation, physics and motion have separate Kotlin implementation entrypoints. New instances pin a `builtin.*` identity, a full version and a content digest. Independently packaged algorithms hash the complete package; remaining entries still hash entrypoint bytecode. A mismatched digest is unavailable rather than silently replaced. Historical unpinned instances retain their original revision without rewriting history on open. `id=builtin,digest=builtin` remains a compatibility operation controlling the whole bundled set.

External packages may declare dependencies on bundled package IDs and versions returned by `plugin_list`. Installation locks their content digests; disabling or losing a required bundled version makes the dependent package unavailable too. The aggregate `builtin` identity is reserved. Installable `builtin.*` archives must declare a stable type alias matching their package ID and algorithm revision, for historical bundled algorithm restoration. Enabled states persist in preferences but evaluation reads an in-memory snapshot instead of accessing system preferences per entry.

Except for physics groups, motion and swing, these entries still compile and ship with the application, rather than independently installable JARs. Binding algorithms still call shared application generation services; the entrypoint digest does not cover the complete application and all underlying dependencies. Independent package distribution for the remaining algorithms, retaining their historical binaries and local binding computation remain migration work. Individual management and pinned identities do not establish completion of that work.

## Install and remove

The physics-group implementation now lives in `:algorithms:physics`. `PendulumAlgorithm` implements the public evaluation interface; configuration parsing, group conversion and output conflict checks run in that module using only `algorithm-api`. Application registration delegates to it, while ordinary physics export and other generators reuse the conversion. A process entrypoint uses `algorithm-worker`: `./gradlew :algorithms:physics:pluginPackage` produces `algorithms/physics/build/plugin`, containing `plugin.json` and a self-contained `algorithm.jar` accepted by `plugin_install`. Its package ID is `builtin.70687973696373.v1`, with a `/pendulum` entrypoint in that namespace. `aliases: ["physics"]` preserves the saved type, algorithm revision and output IDs.

The application ships and retains the complete physics, motion and swing packages; new instances pin its full content digest. An exact match to the current package runs in-process. Historical references run through a worker only when the exact archive is installed, without falling back to the current implementation. Older archives can be removed and reinstalled; the current application bundle can only be enabled or disabled. Development-era references containing only an entrypoint-bytecode digest are not rewritten or substituted by this package; missing original contents remain unavailable.

The motion implementation lives in `:algorithms:motion`, with package ID `builtin.6d6f74696f6e.v1`, entrypoint `/clip` and stable alias `motion`. `MotionData` provides the original data model, JSON codec and curve conversion shared by the editor and standalone worker. The module does not depend on playback sessions, UI or Umamo. Build it with `./gradlew :algorithms:motion:pluginPackage`; the output directory is `algorithms/motion/build/plugin`.

The swing implementation lives in `:algorithms:swing`: package `builtin.7377696e67.v1`, entrypoint `/sway`, stable alias `swing`. Mesh wrapping, keyform evaluation and swing physics groups run in the algorithm module without project or history APIs. It uses the GPL Umamo engine and Cubism format converter, so it is a GPL algorithm, not part of the MIT API/format modules. It does not include the proprietary Cubism SDK. Build with `./gradlew :algorithms:swing:pluginPackage`.

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

The JVM contract lives in the independent `:algorithm-api` module used by the application. It depends only on the neutral `:format-model` module and JSON serialization, not the application, Umamo, Compose, projects or history. `./gradlew :algorithm-api:build` produces the version `1.1.0` API and sources JARs.

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
