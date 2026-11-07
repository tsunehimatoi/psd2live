# Algorithm plugins

Every preset modifier evaluates through an algorithm interface. Bundled Kotlin algorithms run in-process; external algorithms run in persistent worker processes and may be written in Python, Kotlin, Rust, C, C++, or C#. A package may provide multiple algorithms.

Bundled entries are catalogued and enabled independently by algorithm type and revision. Swing, simulation, physics, motion, expression toggles, eye/brow see-through, skin clipping and binding structure have separate Kotlin implementation entrypoints. New instances pin a `builtin.*` identity, a full version and a content digest. Independently packaged algorithms hash the complete package; remaining entries still hash entrypoint bytecode. A mismatched digest is unavailable rather than silently replaced. Historical unpinned instances retain their original revision without rewriting history on open. `id=builtin,digest=builtin` remains a compatibility operation controlling the whole bundled set.

External packages may declare dependencies on bundled package IDs and versions returned by `plugin_list`. Installation locks their content digests; disabling or losing a required bundled version makes the dependent package unavailable too. The aggregate `builtin` identity is reserved. Installable `builtin.*` archives must declare a stable type alias matching their package ID and algorithm revision, for historical bundled algorithm restoration. Enabled states persist in preferences but evaluation reads an in-memory snapshot instead of accessing system preferences per entry.

The application ships at least one independently executable package for each of the 49 modifier types. Historical instances replay their locked revisions. Some recommended presets still use v1 bindings that extract results from host-generated templates.

Select fitted face v2, fitted eyes v3, or fitted mouth v2 by version in the add menu. Fitted face v2 requires the complete target set of a neutral shared v2 scaffold. Fitted eyes v3 requires a compatible eye scaffold, and fitted mouth v2 requires a neutral mouth scaffold. Fitted eyes v4 also supports a complete shared v2 scaffold with fitted face v1 and, when present, legacy body v1. It can move the head frames needed by the eyes and rejects combinations where it cannot preserve authored geometry or references. New source projects with presets use legacy body v2 when the full shared v2 scaffold and the package are available. Legacy body v2 can also be selected by version. It computes body motion and head pitch from source measurements and requires a source-aware scaffold. Shared scaffolds require their full target set. Mouth outline v2 requires legacy mouth v2 on the same static scaffold with no other preceding active bindings. Iris squash v2 is also selectable by version and requires fitted eyes and the captured iris topology. Iris squash v3 supports fitted head turns and source-based lid bindings through aftershock v3. v4 also preserves highlight drift on a preceding highlight v3 carrier. Use v1 with edited iris topology. The document stores the stable modifier type and the selected package so later modifiers can still resolve the original dependency type.

Legacy eyes v2 is selectable by version. It requires the complete target set of a source-aware v2 scaffold. The legacy-style preset selects v2 for an unedited source with the default tuning and the ordinary head turn. Use v1 with fitted head turn or edited eye topology.

Fitted brows v2 is available through explicit version selection in the add panel. It requires a shared v2 scaffold and supports compatible preceding body, head turn, and eye versions. Recommended presets use fitted brows v1.

Fitted brows v3 can follow fitted face v3 on the same shared scaffold. The full recommended model has been checked with fitted eyes v2 and v5. The v2 combination was also tested with the installed brow JVM package. The fitted mouth compatibility path can still request a brow template.

Legacy brows v2 is selectable by version with a source-aware shared scaffold and compatible ordinary or fitted head turn. Use v1 for a reparented brow branch or fitted shapes that exceed the mesh budget.

Legacy face v2 is also available by explicit version selection. It computes head turn from source measurements and supports the full target set of a source-aware shared v2 scaffold, with an optional legacy body v1 or v2 on that same scaffold. New source projects created with the legacy preset select v2 when its source and target requirements are met and the bundled package is available. Switching to a built-in preset plans a fresh stack with the same selection rules. Saved instances replay their locked versions.

Depth turn v2 is selectable by version. Its shell-turn mode requires a source-aware shared v2 scaffold and legacy face v1 or v2, with optional body v1 or v2. Fitted face and depth turn are mutually exclusive.

Fitted face v3 is selectable after legacy body v1 or v2. It uses the body's pitch tuning and requires the same complete shared v2 scaffold and one preceding body provider. The modern preset still uses fitted face v1 while its fitted-eye path builds a face template.

Fitted eyes v5 requires a complete shared v2 scaffold, legacy body v1 or v2 and fitted face v1 or v3. The add plan includes any missing providers. The modern preset still uses fitted eyes v2, and some optional lid algorithms are tied to that version.

Eye widening v2 extends fitted eye opening and opacity from source measurements. Use v1 with preceding eye highlights, brow blink, or lid coupling.

Eye widening v3 can also recompute eyelid shapes after fitted head turns, including the head-turn deformer for a closed-eyelid drawing. Use v1 if widening would remove an existing deformer, refit a deformer shared with other meshes, or if the source measurements cannot reproduce the current binding.

Brow shape v2 is selectable by version with a shared v2 scaffold and compatible fitted brows. v3 supports fitted brows v3 and fitted eyes v5, and can follow iris squash v3. Add it before other brow motion modifiers. Use v1 if the brow height keys, topology, or parent frame have already been edited.

Brow spring v2 adds rebound shapes and physics. It requires shared scaffold v2, fitted brows v1 or v2, and fitted eyes v2 when fitted eyes are present. Use v1 with edited brow geometry or keyforms, or when a lid or brow coupling already precedes it.

Brow spring v3 is selectable after compatible brow blink and brow-lid coupling. It adds rebound shapes and physics while preserving the preceding brow frame and carriers. v4 also accepts fitted brows v3, fitted eyes v5, brow blink v3 and brow-lid coupling v4. Use v1 with edited source frames or if the preceding carrier cannot be reproduced.

Brow blink v2 links brow motion to blinking, widening and smiling. It requires a shared v2 scaffold and compatible fitted brows with their original height and angle grids. v3 also accepts fitted brows v3, fitted eyes v5 and the preceding source-based lid bindings in the recommended order. Use v1 if the brow topology or those keyforms have been edited.

Brow-lid coupling v2 through v4 are selectable by version. v2 and v3 require a shared v2 scaffold, fitted eyes v2 and compatible fitted brows. v3 supports fitted head turn and preceding gaze coupling while preserving existing deformer IDs. v4 also supports fitted eyes v5, fitted brows v3, and preceding gaze v3 and eye emotion in the verified TML preset order. Use v1 for edited eye topology or parent frames, earlier cheek-lid or brow-lid coupling, or a separate depth-turn modifier.

Eye smile v2 requires shared scaffold v2 and fitted eyes v2. It adds smile keys to the source eyelids. Use v1 when the eyelid geometry has been edited or when a coupled lid or brow modifier already precedes it.

Eye smile v3 also supports fitted eyes v5. It recomputes eyelid shapes after fitted head turns and can precede eye widening v3. Use v1 if the change would remove an existing deformer, refit a deformer shared with other meshes, or if the source measurements cannot reproduce the current binding.

Blink aftershock v2 adds eyelid rebound and its physics group. It requires a shared v2 scaffold and compatible fitted eyes. v3 supports fitted eyes v5 after gaze v3 and eye emotion, keeping the IDs of reproducible lid carriers. Use v1 when brow-lid or cheek-lid coupling already precedes it, or when the source eyelid frame or keyforms have been edited.

Gaze-following lids v2 adds shared and per-eye gaze from the source eyelids. It requires a shared v2 scaffold and compatible fitted eyes. v3 supports fitted eyes v5 after fitted head turns, including preceding smile and wide v3. It keeps any head-turn carrier it can reconstruct from the source. Use v1 if the source lid geometry has been edited or rebound, brow-lid or cheek-lid coupling already precedes it.

Eye emotion v2 adds fitted anger, sadness and squint shapes. It requires a shared v2 scaffold and compatible fitted eyes. v3 supports fitted eyes v5 after gaze v3 and preserves the IDs of reproducible carriers. Use v1 with edited lid geometry, preceding brow-lid or cheek-lid coupling, or if shared carriers need to change or be retired.

Eye highlight v2 computes parallax and fade from source measurements and the fitted eye and head bindings. v3 supports fitted eyes v5 and widening v3, including source artwork that needs a new head-turn deformer. Use v1 for reparented artwork. Use iris squash v4 when highlight v3 adds shapes to the same carrier.

Mouth vowels v2 adds the five standard vowel parameters and their fitted shapes. It requires a shared source scaffold and a compatible fitted mouth. Use v1 with a separate closed-mouth drawing, preceding mouth modules or edited mouth topology or frames.

Mouth capture v2 adds mouth-tracking keyforms using the original mesh and a compatible fitted mouth. It can follow an outline or vowel modifier. Capture v3 also supports fitted mouth v3 with outline v3 and vowel v4. Use v1 for separate closed-mouth drawings, edited or lowered meshes, or other preceding mouth modules.

Fitted mouth v3 is available by version with the shared v2 source scaffold and a fitted head turn. It evaluates the source mouth measurements and recomputes the head lattices and local shapes affected by the mouth landmarks. Outline v3, vowel v4, capture v3 and jaw v3 can follow fitted mouth v3.

Mouth outline v3 is available by version. With the shared v2 source scaffold and a fitted mouth, it generates outlines from the original pixels and lip measurements and recomputes the head lattices, hair chains, headwear and neck shapes affected by the mouth landmarks. Use v1 for edited source meshes or combinations with a private head-turn carrier. Presets still select v1, and downstream vowel v1 still builds a compatibility template for the preceding outline.

Mouth vowel v3 can follow outline v3 on the same shared scaffold. It generates five vowel keyforms directly from the source mouth measurements. Vowel v4 also supports fitted mouth v3. Use v1 for separate closed-mouth drawings, edited source meshes or other preceding mouth modules.

Mouth jaw v2 is opt-in. It uses a shared v2 source scaffold and a compatible fitted mouth to add opening motion to the face, nose and details below the mouth. Ordinary and fitted head turns, mouth outlines, vowels and capture can precede it. Jaw v3 uses the source measurements from fitted mouth v3. Jaw v4 also accepts preceding tongue v3 on the verified fitted mouth v3 and capture v3 chain. Use v1 for separately drawn lip layers, edited target meshes, or configurations that require an unsupported deformation carrier.

Mouth cheek-lid coupling v4 supports fitted mouth v3 and fitted eyes v2. It computes the lid coupling from source measurements and preserves compatible head-turn deformers. v5 also supports fitted eyes v5 and preceding gaze v3 when their original geometry and carriers can be reconstructed.

Mouth tongue v2 adds tongue-out forms to a fitted mouth with a tongue layer and a preceding capture modifier. V3 also supports fitted mouth v3 and capture v3. These versions use a single mouth drawing on the original source mesh. Use v1 for layered lips or a separate closed-mouth drawing.

Mouth interior depth v2 adds parallax to teeth and other interior layers and shades the tongue and inner mouth as it opens. It uses the original source frame and adds a head-turn deformer when the mesh keyform grid exceeds its budget. Use v1 for reparented or recolored targets. v3 supports fitted mouth v3 and capture v3. Synthetic artwork with interior layers verifies the actual color and deformation output.

Mouth spring v2 adds lower-face settling after the mouth opens. It uses the fitted mouth and jaw on a shared source scaffold and honors the physics toggle. Use v1 for separate closed-mouth drawings, edited target geometry or combinations with a preceding tongue modifier.

Mouth spring v3 is selectable by version and supports preceding tongue, cheek-lid coupling and interior depth in the modern preset. If lower-face geometry has been moved into a deformer, v3 replays and checks the source allocation before adding spring motion. V4 also supports fitted mouth v3 and jaw v3. v5 adds support for preceding jaw v4, tongue v3, and depth v3 while keeping the depth color channels and carrier IDs. These versions require the original fitted artwork and a shared v2 scaffold.

Cheek-lid coupling v2 is selectable by version. It requires a shared v2 scaffold, fitted eyes v2, and a fitted mouth with a live mouth form grid. Use v1 with edited lid meshes or parent frames, fitted head turn, or preceding gaze or brow-lid coupling.

Cheek-lid coupling v3 is also selectable by version. With the same source requirements, it supports fitted head turn and preceding gaze and brow-lid coupling, preserving the existing deformer IDs. Use v1 for edited lid meshes or parent frames, repeated cheek-lid coupling, or a separate depth-turn modifier.

Cheek color v2 adds blush and skin warmth to a shared v2 scaffold. Use v1 when another mood already applies, the target has an authored multiply-color grid, or the added color axis requires rebuilding a deformer.

Gloom v2 adds a cool shadow and fades highlights while preserving authored channel edits. Use v1 in the modern recommended preset or whenever adding the color axis changes the mesh keyform budget enough to move existing motion to a deformer.

Gloom v3 can recreate that deformer for face and nose meshes on a shared v2 scaffold. It verifies the original skin geometry and its upstream bindings before adding the color axis. Select v3 explicitly. Use v1 if the source frame has been edited, another Gloom already applies, or the budget is exceeded on a different part.

Skirt sway v2 is selectable for topwear or bottomwear attached under the torso. It uses a shared source frame and preserves the existing skirt mesh, physics and simulation bindings in compatible presets.

Arm swing v2 is selectable for the original hanging-arm frames of a shared v2 scaffold, with body and face bindings on that scaffold. V3 recomputes the arm frames from preceding fitted bindings and transfers the new swing keyforms when the parent frame of paired gloves has moved. Use v1 if an arm has been reparented, its neutral lattice edited, or existing authored keyforms would need to be transferred.

Ribbon and pendant sway v2 is selectable for targets that share a source frame. It adds motion and physics at the source attachments and preserves simulation bakes in compatible presets. Use v1 for targets with different source frames.

Animal ear, tail and wing sway v2 is selectable for targets sharing a source frame. It binds each part at its source attachment, keeps the animal ears' blink response, and leaves tail and wing motion to an enabled skeleton.

## Install and remove

Binding structure has separate packages for algorithm revisions 1 and 2. Build them with `./gradlew :algorithms:binding:pluginScaffold1 :algorithms:binding:pluginScaffold2`; outputs are `algorithms/binding/build/plugin-scaffold-1` and `plugin-scaffold-2`. Package IDs are `builtin.7269672e73636166666f6c64.v1` and `.v2`, each exposing `/structure` in its namespace with stable alias `rig.scaffold`. The revisions retain per-target and shared hierarchy semantics respectively.

The current `scaffold/5` contract captures the target identities, source layers, neutral coordinate measurements, unbound mesh topology and atlas UVs. The plugin computes the part tree, deformers, mesh attachment and default keyforms. Historical `scaffold/1` through `scaffold/4` packages retain their original input contracts, including template resources where required.

The physics-group implementation now lives in `:algorithms:physics`. `PendulumAlgorithm` implements the public evaluation interface; configuration parsing, group conversion and output conflict checks run in that module using only `algorithm-api`. Application registration delegates to it, while ordinary physics export and other generators reuse the conversion. A process entrypoint uses `algorithm-worker`: `./gradlew :algorithms:physics:pluginPackage` produces `algorithms/physics/build/plugin`, containing `plugin.json` and a self-contained `algorithm.jar` accepted by `plugin_install`. Its package ID is `builtin.70687973696373.v1`, with a `/pendulum` entrypoint in that namespace. `aliases: ["physics"]` preserves the saved type, algorithm revision and output IDs.

The application ships and retains the complete physics, motion, swing, baked simulation, expression toggle, eye/brow see-through, skin clipping and binding structure packages; new instances pin its full content digest. An exact match to the current package runs in-process. Historical references run through a worker only when the exact archive is installed, without falling back to the current implementation. Older archives can be removed and reinstalled; the current application bundle can only be enabled or disabled. Development-era references containing only an entrypoint-bytecode digest are not rewritten or substituted by this package; missing original contents remain unavailable.

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

GUI and MCP share one management service. `plugin_list` returns package identity, enabled state, algorithm configuration schemas and defaults, dependencies, targets, and read/write domains. Bundled packages report dependencies, conflicts, and read/write domains from their manifests. Built-in algorithm configuration schemas come from the host validation contract. `problems` reports unreadable packages.

`plugin_inspect` and `plugin_install` accept `source` and optional Git `ref`. For example:

```json
{"source":"D:/plugins/example-python.zip"}
```

`plugin_enable`, `plugin_disable`, and `plugin_uninstall` take the `id` and `digest` returned by the list, including individual bundled identities. The legacy pair of `builtin` values only supports aggregate enable/disable, not uninstall. All operations except listing return process jobs; use `job_wait` or `job_get`. No open project is required.

Use the existing `modifier_plan`, `modifier_add`, and `modifier_update` operations for external algorithms. Configurations are validated against their package schema. Plans pin their package reference; changing plugins invalidates prepared candidates. Public operations declare strict result schemas.

## Authoring and protocol

The JVM contract lives in the independent `:algorithm-api` module used by the application. It depends only on the neutral `:format-model` module and JSON serialization, not the application, Umamo, Compose, projects or history. `./gradlew :algorithm-api:build` produces the version `1.7.0` API and sources JARs.

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

On subsequent requests the host reuses the binary resources of unchanged mesh and deformer objects. A new object is encoded and compared by content hash before it is sent.

The portable `meshes` view exposes target IDs, opacity, and position/UV buffer references; `parameters` exposes readable parameters. Float resources use little-endian `f32le`, named by SHA-256. Commands may also return such references in place of float arrays; the host validates checksums and finite values before expanding them.

Commands supported:

- `mesh_opacity`: target `id` and `value` in 0–1.
- `physics` and `motion`: `config` uses the corresponding bundled modifier format; the host assigns output IDs.
- Authoring primitives: `structure`, `set`, `parameter_keys`, `delete`, `canvas_geometry`, `canvas_topology`, `canvas_create_warp`, `canvas_create_rotation`, `canvas_create_glue`, `canvas_glue_edit`, `path_put`, and `path_delete`, using authoring-journal formats. They execute only on an isolated candidate.

New IDs use `modifier:<instance-id>:<UTF-8 hex of local name>`. The host validates actual writes, shared parameters, dependencies, overrides, and conflicts. Plugins never access document history or UI directly.

## Performance boundaries

Bundled calls avoid IPC. External workers reuse processes, content-addressed buffers, and incremental input synchronization. Author input, binding templates, and algorithms with declared dependencies have separate caches. Folder edits do not evaluate algorithms.

Migration acceptance checks generation through the independent algorithms, output equivalence and historical replay. Performance optimization follows the migration. The diagnostic tool reports the first and repeated generation times with synchronous baking shown separately.

Some bundled binding algorithms still extract their results from generated templates. Adjacent built-in algorithms can compute changes in parallel when their declared read and write domains are disjoint and their dependencies are already satisfied. The host checks actual reads and write preconditions and merges changes in stack order. Other algorithms evaluate in sequence. First and repeated generation are timed separately.

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

`MoodChannels` is an independent color kernel in the binding module, shared by complete generation and local templates. When blush/gloom follows existing bindings, the ordinary path reuses source geometry before target lowering, updates parameters and color/opacity tracks, then runs the original Cubism structural conversion. It does not reuse already folded keyforms: extra color axes can require turn carriers. Standard-name conflicts and missing reusable preceding inputs retain full generation. Disabling the stage cache also uses full generation for equivalence checks. The Cheek v2 and Gloom v2/v3 packages use local color changes. Gloom v3 can also rebuild compatible face and nose carriers.

`BlendShapeLowering` provides independent Cubism blend-shape conversion to keyforms and carrier deformers in the GPL binding module. Callers supply naming and cancellation callbacks; it has no application localization, project or history dependency. The application adapter preserves existing names and deterministic object IDs. Additional color axes can change keyform budgets, so local color evaluation must still run this step from pre-conversion input. This kernel does not establish completion of independent entrypoints for the remaining binding modifiers.

`MoodBinding` accepts the pre-conversion binding model, captured parameter definitions/order and per-mesh color weights. It evaluates color parameters, parameter-tree entries, generated tracks and Cubism conversion, returning both the pre-conversion input and final model. Repeated evaluation replaces owned color tracks without accumulating parameters; undeclared meshes retain their content. It does not accept project objects or edited previews; the host still merges authored overrides. This is the pre-conversion color interface used by the compatibility path.

`BindingComparison` caches canonical mesh/deformer records by object identity within one candidate. Local clipping checks compare only the target mesh and specified ancestors, without converting the entire model. Geometry array contents, channel keyforms, blend shapes and other mesh properties still participate in comparison. The cache must not be reused across mutable inputs.

`SeeThroughBinding` accepts captured coverage candidates, settings, naming and ID callbacks to compute feature copies, inverted bang masks, draw order and inherited opacity independently. Preview refresh accepts previous/current models and copy/window maps without reading project or UI state. The application `SeeThroughLayers` retains source-pixel coverage detection and configuration/localization adapters, preserving its types and call interface. Both modifiers evaluate through the contextual `SeeThroughAlgorithm` entrypoint. The `schema: "see-through/1"` context contains coverage `candidates`, the `eyes` category, `earlier_owners`, `draw_order_step`, `inverted_masks` and a localized `name_pattern` containing exactly one `{meshName}`. The host computes and shares pixel coverage from captured sources; the plugin filters candidates and bangs in current model order to preserve output ordering. Configuration `values.opacity` retains the 0–100 range. Gradle tasks `pluginEyeSeeThrough` / `pluginBrowSeeThrough` produce `algorithms/binding/build/plugin-eye` / `plugin-brow`, retaining `eye.see-through` / `brow.see-through` aliases with `/visibility` entrypoints. Packages can be installed, restored and evaluated in persistent workers; the current bundled versions execute in-process.

`ModelIdRemap` is the GPL binding module’s public reference-rewriting utility for keyform axes, blend-shape limits, parents/parts, masks, glue, parameter trees/links, paths, vertex groups and render trees. It applies caller-supplied maps; it does not allocate identities or bypass host conflict validation. The host still owns instance-derived IDs and write permissions. Unaffected model partitions and meshes retain their references for read-only input caching. The application `RigIdRemap` is a compatibility alias.

The binding module exposes `GeometryResidual`, `MeshBlendResidual` and `WarpBlendResidual` for three-way keyform geometry, mesh blend-shape and deformer blend-shape merging in authored coordinate frames. `BindingVertices` transfers x/y values through captured vertex-index or barycentric correspondences. Inputs are read-only models and correspondences; evaluation loops accept cancellation callbacks and return geometry grids/channels or blend shapes. These kernels do not read projects, history or UI state and do not commit results. Instance override ownership, conflict validation and atomic publication remain host responsibilities. Existing application names remain compatibility aliases.

`BindingFrames` exposes `carriedToParent`, `pointsInParent` and `reparent` to transport rest meshes, keyforms and blend shapes into a destination parent frame, including inversion limited to the authored branch below a shared ancestor. Ordinary editor rebinding and modifier merging share the implementation. Unreproducible neutral poses and parent cycles reject the result. The independent API accepts cancellation checkpoints, forwarded by modifier evaluation; it neither changes model identities nor publishes project state.

`BindingMerge.apply` is the independent binding-result merge entrypoint: it accepts the current read-only model, instance and candidate-scoped `BindingMerge.Source`, and returns a new model. `Source` supplies before/after generated projections, fixed ID maps, static coordinate projections, parameter compatibility decisions and vertex correspondences. It must not read live project/UI state or publish edits. Carrier insertion/removal, parameter trees/links, keyforms, blend shapes and masks are merged inside the module. `BindingAxes` shares axis union and bounded keyform coordinate enumeration. This is an in-process calculation contract, not a completed external transport protocol: the product adapter still supplies generated templates and mesh correspondences, while the host adds physics, mouth rest-pose policy and transactions. Remaining algorithms still require local template replacement and resource-backed inputs.

`SkinClipAlgorithm` adds face masks directly to skin-overlay IR meshes without model conversion or full binding generation. Its `schema: "skin-clip/1"` captures ordered face objects/bounds in `faces`, source-unclipped candidate overlay bounds in `overlays`, and targets already owned by preceding instances in `already_active`; instance targets determine scope. It retains the original 85% overlap rule, accepts identical masks and rejects conflicting authored masks/inversion, leaving other fields and objects unchanged. Source clipping, preceding owners and duplicate-target mappings are captured by the host. `./gradlew :algorithms:binding:pluginSkinClip` produces `algorithms/binding/build/plugin-skin`, retaining the `face.skin-clip` alias and `/clip` entrypoint suffix. Parameter declaration and evaluation for this entry no longer invoke binding templates; later algorithms may still generate templates containing clipping for their own inputs.

When a downstream binding requests a source template ending in skin clipping, the host reuses the preceding template and invokes the same clipping plugin with identities and mask state only, without rebuilding geometry. Changed masks are also applied lazily to the pre-lowering input; unrelated masks and channels remain untouched. Applied prefixes use captured clipping inputs without looking up removed active instances. Disabling stage caching retains full generation for equivalence checks. Other binding modules still build their own templates.

Mouth-outline source artwork is cached by captured analysis, authored split/deletion state, outline color/width, mesh settings and language. Motion recipes, binding strength and tuning do not affect this artwork key. Binding templates reuse lip rasters and appended atlas pages instead of repeatedly extracting contours and encoding identical images. Disabling stage caching recomputes these inputs. This cache serves the compatibility path. The separately packaged mouth outline v2 generates outlines directly for compatible static legacy mouths.

Captured-input contracts for historical built-in packages are checked when consumed. If dependency, exclusivity or composable-write declarations disagree with the host adapter, parameter queries, input capture and evaluation for that exact package fail with its package ID/version. Merely having it installed no longer prevents unrelated projects from constructing their algorithm catalog. Incompatible packages do not fall back to the current built-in algorithm.

`MouthContour`, `MouthColumn` and `MouthStrokeMesh` provide independent outline geometry kernels: boundary extraction from x/y vertex arrays and triangle indices, arc-length sampling of the upper/lower lips, crossed-tip paths, and fixed-topology ribbons with round joins/end caps. Callers supply captured geometry without product `MeshData`, project or UI objects. The product retains thin adapters and type aliases. Source semantics, rasterization, atlases and instance output merging still require migration into the outline plugin entrypoint. These geometry APIs neither publish results nor allocate project object IDs.

`MouthOutlineRaster` converts captured lip columns, color, width and a coordinate mapping into RGBA pixels, canvas placement and an opaque-pixel count. `perimeterColor` selects outline color from the exterior boundary of raw RGBA, excluding interior details and transparent RGB noise. Rasterization retains the original AWT triangle filling and seam strokes, accepts cancellation checks and releases graphics resources on failure. Product adapters classify source layers and wrap output layers; this kernel is not yet a complete independently installable outline modifier.

Catalog snapshots and package dependency resolutions are reused within a management revision, with at most 256 exact-reference results, including missing references. Installing, uninstalling or enabling/disabling built-in or user packages causes the next query to rebuild the snapshot and resolve again; locked dependency contents are never upgraded implicitly. This cache does not store file-verification results or replace installation/pre-execution integrity checks. Stage statistics report dependency resolutions and cache hits.

The application lists shipped algorithms from their bundled manifests. It archives the exact executables when a workspace or a revision in an opened project references them, an installed package locks them as dependencies, or a worker needs to start. The archive preserves these versions across application upgrades. Merely listing the catalog performs no archive writes. Workspace evaluation can overlap archive I/O, and the candidate waits for verified archives before returning.

Payload SHA-256 verification during installation, catalog restoration and before worker startup uses a 64 KiB streaming buffer. Catalog restoration verifies separate installed packages on up to four threads. Cancellation is checked between blocks, all bytes are verified, and size limits remain enforced. The `plugin payload verification` stage reports the sum across verifications. The `plugin load: installed catalog` and `plugin load: retain bundled packages` stages report elapsed time.

The shared-deformer stage keys only the strength/tuning, base algorithm choices, head turn, gaze coupling, arm swing, clothing/part chains and hair settings it reads. Mesh-local eye shapes and outline color/width no longer invalidate the entire shared scaffold. Source geometry, anchors, parent frames, skeleton inputs and language remain part of their respective cache keys. This reduces repeated binding-template work; it does not mean all templates have been replaced by independent plugin evaluation.

For explicitly classified body layers (tops, bottoms, hands, feet, legs, neckwear and neck), mesh stages no longer key eye/brow/mouth-local modules. Chest motion, neck follow, head depth turn, base choices and the existing pixel/mesh/parent-frame inputs remain. Clothing chains that change a parent still invalidate the mesh key; other layer categories retain conservative input scopes.

`BrowFit`, `BrowRig` and `BrowOptions` now live in the independent binding module. Fitting accepts RGBA, dimensions, canvas placement, side, head level and a coordinate mapping, returning a brow frame and a 17-sample center line. Motion produces height/angle keyforms plus shape, blink/smile coupling and spring blend shapes, with cancellation checks. Shared `Bounds` and `Side` also live in the binding module; product names remain Kotlin type aliases and layer adapters only capture inputs. Formulas are unchanged. Complete installable brow modifier entrypoints still require instance merging and context transport.

`HeadTurn`/`HeadShell`, `BustRig`, `ThinPlateSpline` and `BiharmonicField` provide independent head-depth turn, chest motion, point-set fitting and smooth displacement fields. Inputs are numeric values, point sets/fixed nodes and shared geometry types. Motion retains the original keyform/blend-shape definitions; iterative solving and per-point output support cancellation. `ThinPlateSpline.solve` exposes the original pivoted elimination for other binding kernels without modifying the input matrix in place. Product type names remain aliases; layer recognition, instance composition and installable entrypoints still require adaptation.

`ThinPlateSpline.solveColumns` accepts multiple right-hand-side columns for one square matrix and performs pivoted elimination once. Spline X/Y fitting shares this entrypoint, preserving each column's arithmetic order and singular-pivot handling. Matrix and right-hand-side inputs remain unchanged, with cancellation checked on every elimination step.

When all expression parameters are left/right eye opening or mouth opening, the expression capture adapter queries fitted standard ranges without generating complete meshes and atlas output. Other standard names retain the compatibility path because Cubism lowering can change their parameter types; custom names continue to use the independent expression catalog. This optimizes host input capture but does not remove complete template generation from other binding modifiers.

Raw brow fitting is shared through the stage cache, keyed by the source layer, eye-line direction, face center and coordinate space. Shape, blink, spring and handle corrections do not change this key. Corrections apply after raw fitting, while motion still reads each instance's options; changed source layers or measurement coordinates invalidate the raw fit.

Binding template projection uses the engine's `withParametersDeleted` operation. Parameters are removed in caller order with the existing channel-collapse, lifted static-value and blend-removal semantics, deriving the render tree only once at the end. Cancellation is checked between parameters. The operation returns a model candidate and does not publish project or history changes.

The independent binding module exposes `BindingProjection.retain`, taking a model, retained parameter IDs and optional source-pose defaults. Removed axes preserve interpolated static mesh and deformer geometry. The host declares the mouth artwork's open pose through these defaults without coupling the kernel to the product parameter catalog; retained parameters are unaffected. Inputs remain unchanged, with cancellation checks between objects and axes. Template and static projections use this entrypoint. Source recognition and complete binding template generation remain in the host, so installable migration is not yet complete for all modifiers.

`ScaffoldBinding.apply` clones binding hierarchies, assigns stable IDs, checks shared-object conflicts, transports parent frames and moves organizational children directly through engine primitives, without the product's JSON edit dispatcher. `CapturedSource` accepts static templates indexed by target and ordinary material meshes. Only untouched positions, UVs and topology qualify for direct attachment; other meshes preserve their default-pose location through inverse coordinate transport. Version 1 per-target hierarchies, version 2 shared hierarchies and materialized aliases remain compatible. The host and installable worker use this kernel; the host capture adapter still computes structure templates from source art.

The independent binding module owns `EyeRig`, the `EyeFit`/`EyeArtwork` observation data, and the internal band, eyelid and widening models. It ships `rig/eye-opening.bin` as a module resource. Layer recognition and modifier composition still belong to the host; this extraction does not complete the eye modifier migration.

### Structured changes (JVM API 1.3)

`ChangingModifierAlgorithmPlugin.changes` returns `ModifierModelChanges`. Stable-ID parameters, parts, deformers, meshes, physics groups and clips use `ModifierObjectChanges` for explicit additions, updates, removals and reuse. Updates and removals carry expected previous objects; updates cannot change identity. Other values and ordered relations use `ModifierFieldChange`, distinguishing no change from replacement with null. Unchanged fields and buffers retain their references.

Before applying changes, the host checks actual sections against `changeWrites` and validates object preconditions. Duplicate operations, additions colliding with existing IDs and stale values are rejected. Applying produces an isolated candidate only. `contributions()` currently covers object collection operations, not complete user-facing contributions for singleton fields. Existing host logic still handles final reports, reference checks, overrides and transactions. The pendulum physics algorithm uses this entrypoint.

Workers negotiating `model_changes_v1` return `changes` without materializing and encoding a complete output. Hosts not advertising that capability still receive the original `objects` response. Exactly one of `commands`, `objects`, or `changes` is required; the outer protocol remains version 1.

The change envelope contains exactly `schema`, `before`, `after`, `objects`, and `fields`. `schema` is `model_changes_v1`. `before` and `after` reference SHA-256-addressed binary resources with byte counts, using existing RigIR binary version 6. These sparse carriers contain only involved objects and fields, excluding unchanged meshes. `objects` accepts `parameters`, `parts`, `deformers`, `meshes`, `physicsGroups`, and `clips`, each mapped to `[added, updated, removed, reused]` counts. The before carrier orders removed objects, reused objects, then old update values; the after carrier orders additions then new update values. Total object operations are limited to 10000, each carrier to 64 MiB.

`fields` names singleton or relation replacements using `ModifierModelChanges` property names. Listed fields take explicit before/after values, including null. `AlgorithmChanges` is the JVM codec helper. The host checks schema, keys, counts, resource integrity, declared write scope and old-object preconditions, followed by existing complete reference and output validation. Manifest write vocabulary retains names such as `textures` and `glue`.

The physics package has passed actual worker installation, repeated evaluation, store reopening, uninstall and reinstall equivalence checks. Other languages retain the existing command protocol; the new change payload has not yet been implemented and verified in all six languages. The host retains full contribution comparison. Read-only views, worker read tracking and generated artwork are described below.

### Captured source art (JVM API 1.4)

`ModifierSourceInputs` describes pre-generation artwork through `source_layers/1`, without generated templates or host callbacks. `ModifierSourceLayer` contains stable source identity, semantic tag/side/role, source-canvas origin, dimensions, observed bounds, centroid, opaque-pixel count and an RGBA resource name. `targets` maps stable model target IDs to source IDs. Pixels are straight RGBA8, row-major from the top left, without padding. Source coordinates, observed bounds and centroids use canvas pixels with Y pointing down.

`to_model` is a serializable source-canvas to model-canvas affine transform (`xx, xy, tx, yx, yy, ty`). Mirroring is supported and singular transforms are rejected. This is not a parent-deformer transform. `raster(layer)` returns a cached read-only view; `alpha(x,y)` returns zero outside bounds and `rgba()` returns a defensive copy. Resources have content digests and use existing `author_context_v1` transport. Construction validates unique source IDs, target references, finite coordinates, dimensions and RGBA lengths. Each raster is limited to 64 MiB, within aggregate context limits.

`context()`/`from()` encode and decode the snapshot. Host `RigSourceCapture` captures existing art and classification only. Eye-outline fitting now reads this contract in the independent binding module. Its host adapter retains the existing coordinate callback; standalone consumers can use `to_model` without product types. Motion design is unchanged. This does not complete eye modifier geometry generation, composition or packaging.

This snapshot currently covers raster observations, not separate mask resources, parent-space geometry views, upstream capability snapshots or read-set tracking. Those remain framework work. Existing input mask semantics are retained; this interface does not yet independently describe the entire source-art pipeline.

### Evaluation through observed reads (JVM API 1.5)

`ReadingModifierAlgorithmPlugin` receives `ModifierEvaluationInput` rather than direct access to the complete `RigIR`. `parameter(id)`, `mesh(id)`, `deformer(id)`, `part(id)`, `physics(id)` and `motion(id)` record object observations. Missing objects are observed too, so a later addition invalidates that read. Collection and singleton getters record complete field values and ordering; `context` currently tracks the complete author context.

`parentsOfMesh(id)` returns the immediate parent followed by its ancestors, preserving each object's declared local coordinates. It observes the mesh and every ancestor, rejecting cycles, missing parents and duplicate identities. It does not implicitly combine coordinates from different spaces; binding coordinate algorithms still perform frame transport.

`reads()` returns a snapshot of the invocation's observations. The host caches instance configuration, plugin implementation identity, author context, reads and structured changes. Following upstream edits, reuse requires unchanged reads and satisfied write preconditions; changed write preconditions trigger reevaluation. Reuse still passes write-scope, override, contribution-conflict and transaction handling. Failed or cancelled candidates do not replace the successful snapshot. The physics plugin reads its referenced parameters and physics configuration rather than all geometry.

The in-process cache checks the instance, implementation, captured context and actual read values. Worker support for the same observations is described under External read tracking and reuse.

### Explicit upstream capabilities (JVM API 1.6)

Algorithm manifests declare capability aliases in `provides`. `requires` can reference algorithm types or capability aliases; cycle validation covers both. Package semantic-version requirements remain in manifest `dependencies`/`conflicts`, distinct from instance execution prerequisites.

`ModifierAlgorithmContext.upstream` contains host-resolved preceding capabilities. Each `ModifierUpstreamCapability` records the requested capability, actual algorithm type and version, provider instance ID, targets, materialized configuration, output aliases and, when known, the exact package reference with version, digest and dependency content locks. The host selects preceding or materialized providers matching consumer targets and explicit provider selection. Missing package provenance in legacy records remains null rather than guessing an installed version. Applying a prefix preserves this identity so materialized content can continue satisfying dependencies.

`ModifierEvaluationInput.upstream`/`providers(capability)` participate in read tracking and cache invalidation. Source `values` schemas stay unchanged; capabilities are transported separately. External algorithms opt in with manifest `contextVersion: 2`; older manifests default to 1. Contexts without upstream data retain v1 encoding. Nonempty upstream contexts use v2, adding an `upstream` resource reference alongside `version`, `values` and `resources`, limited to 1 MiB of metadata. V2 receivers also accept v1; old packages are not forcibly sent v2 contexts.

Shared GUI/MCP plugin descriptions expose `context_version` and `provides`, with corresponding strict result-schema fields. Verified cases include capability alias resolution, upstream-configuration cache invalidation, prefix application, persisted package provenance and v1/v2 context codecs. Parent-space coordinates and worker read tracking are described below. The remaining algorithm migration is tracked in the migration specification.

### Parent-space coordinate services

The GPL binding module provides `BindingCoordinates.forMesh(input, meshId, pose)`. It captures the target mesh, complete ancestor chain and parameters referenced by those ancestors through `ModifierEvaluationInput`, without converting unrelated meshes or deformers. Explicit pose values override parameter defaults; live playback state is not read. Cycles, missing ancestors/parameters and invalid coordinates fail.

`toCanvas`/`fromCanvas` use pixel coordinates with Y pointing down. The service handles the runtime world's Y negation internally; plugins must not negate it again. Inversion uses the existing runtime solver with an optional local seed and rejects results that cannot reproject within 0.05 pixels. Arrays must contain finite XY pairs, inputs remain unchanged, and cancellation is checked at conversion boundaries.

`BindingCoordinates.reparent` reuses `BindingFrames.carriedToParent` and returns structured mesh changes carrying rest positions, keyforms and blend shapes while preserving non-geometric properties. It does not publish hierarchy/project edits or replace dependency validation. Targeted verification covers nested nonlinear warp/rotation frames, Y direction, observed read scope and rest/keyform canvas positions after reparenting. This shared service does not count as a completed modifier migration.

### External read tracking and reuse

When the host advertises `input_reads_v1`, JVM workers implementing `ReadingModifierAlgorithmPlugin` can return `reads` alongside `changes`. Each entry is `{section, id?}`: an omitted ID observes a complete field, while an ID observes one object, including absence. At most 10000 unique valid observations are accepted. The API `meshIds` view observes ordered mesh membership without geometry or drawing channels. Workers negotiate this read key through `input_mesh_ids_v1`; hosts without it still receive changes, but not read observations containing this key. Older hosts do not receive extra fields; older plugins retain object or command results.

The host reconstructs observed values from the actual transmitted input projection and context, never from worker-supplied previous values. Successful results are cached per package worker and instance, with a maximum of 128 entries. Matching requires the algorithm descriptor, full instance configuration, context and observed values. Structured changes are reapplied to the current complete model; failed write preconditions trigger reevaluation. Reuse still checks output identities, references, write scope and model validity, skipping only input binary serialization and the worker call.

A cache hit does not advance unsent input revisions. The next real request remains relative to the last transmitted state. Exited, disabled or uninstalled workers cannot bypass availability checks through caching; failures and cancellation discard the worker. Dedicated checks use `plugin worker evaluations` and `plugin read cache hits` counters.

The actual physics package passed checks showing no additional worker calls for repeated input or added unread parameters, and reevaluation after a read parameter changes, with equivalent final models. Reopening, uninstall and reinstall remain valid. Read-protocol support in the other five languages still needs verification.

### Host boundary for generated artwork

The API provides `ModifierRasterOutput`: a captured owner mesh with its expected state, a new mesh, source-layer metadata, immutable RGBA8 pixels and editable paths belonging to the new mesh. Source metadata carries canvas placement; geometry remains in parent-local coordinates. UVs default to source-raster pixels and are mapped onto the allocated atlas page by the host. `PAGE` preserves already-bound legacy UVs only.

The host validates the whole batch before packing: unchanged owners, unique output identities, matching image dimensions and byte counts, valid geometry and path references. Each resource is limited to 64 MiB and the batch to 256 MiB. Dedicated pages, source/tile records, meshes inserted before their owners, paths and editable source pixels are assembled in one isolated candidate. Existing pages are not repacked. Failure or cancellation publishes nothing; the outer CAS/history transaction still owns publication.

Existing mouth-outline attachment uses this boundary. API 1.9 adds `ModifierModelChanges.rasters`: ordinary changes are applied first, then raster owners must match that intermediate candidate. If the same result updates the owner mesh, the raster descriptor must contain the updated mesh. Calling `apply` without a host resolver fails explicitly; hosts use `applyWithResources`. Raster output conservatively requires mesh, part, hierarchy, textures and authoring write scopes.

After negotiating `model_changes_v2`, results containing artwork add a `rasters` array to the sparse change envelope. Results without artwork remain v1. Each item has exactly `model`, `pixels` and `uv_space`. The first two are `{sha256, bytes}` binary references; UV space is `RASTER_PIXELS` or legacy `PAGE`. The RigIR binary model contains only the owner/new mesh pair in that order, and authoring metadata with a single `raster` source containing the layer plus generated paths. Other fields must have empty defaults. Pixels are separate RGBA8 bytes. Limits are 64 MiB per resource, 256 MiB total model/pixel data and 10000 items. Bad hashes, extra fields, invalid carriers and unnegotiated output fail.

JVM workers encode this automatically. Other languages can implement the same protocol, but six-language artwork validation remains pending. Read-cache hits retain unpacked output and still run validation and allocation against the current candidate and captured host atlas configuration. The fitted mouth outline uses this contract in its standalone JVM package.


### Fitted mouth source inputs (migration in progress)

The GPL algorithm library provides `MouthSourceContext(fit, options, lineWidth)`. `encode()` returns a transferable `ModifierAlgorithmContext`; `decode(context).rig()` constructs `MouthRig` without loading the application module. The algorithm package carries the three-layer model resource. The application retains source classification and contour sampling. This input API does not mean the `mouth/fitted` installation entry and host integration are complete.

The control object contains exactly `schema: "mouth_source/1"` and `measurement: <SHA-256>`. The resource map uses that digest as its key. The binary resource uses big-endian Float32, ordered as mouth origin/axes/width, upper and lower contours, optional layered frame, all mouth options, and optional line width. Each contour has 49 scalar samples. A layered frame contains four 65-point XY contours, opening columns and their upper/lower boundaries, optional local part bounds, and teeth measurements. Variable column counts use bounded Int32 values; flags and optional-value markers use single bytes 0/1. Options preserve the seven-point closing curve and capture, vowel, jaw, depth, spring, and tongue controls.

The payload contains source measurements and configuration, never evaluated poses or a complete binding template. Decoding rejects unknown schemas, missing or mismatched resources, non-finite values, invalid lengths, and trailing bytes. Source canvas and local fitting coordinates retain their original floating-point values without normalization or resampling during transport.


The candidate protocol `mouth-fitted/1` can reference the scaffold's captured `source_meshes` resource with an explicit target-to-source mesh mapping. Original coordinates are used only when the parent, triangle indices, and every normalized neutral coordinate match bit for bit. Vertex edits, topology edits, or reparenting cause evaluation from the current geometry instead; UV-only edits do not invalidate original positions. The algorithm emits typed changes and can expose its pre-lowering candidate from the same evaluation for subsequent binding composition. The host remains responsible for authored residual merging and transactional publication.
