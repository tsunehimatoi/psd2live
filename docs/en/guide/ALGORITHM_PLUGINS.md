# Algorithm plugins

Every preset modifier evaluates through an algorithm interface. Bundled Kotlin algorithms run in-process; external algorithms run in persistent worker processes and may be written in Python, Kotlin, Rust, C, C++, or C#. A package may provide multiple algorithms.

## Install and remove

Use Preferences → Algorithm plugins to select a ZIP or enter an absolute directory or HTTPS/SSH Git URL. Git sources accept an optional branch/tag and record the resolved commit. The repository root must contain a built package. The installer does not fetch submodules, run setup scripts, or compile source.

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

`plugin_enable`, `plugin_disable`, and `plugin_uninstall` take the `id` and `digest` returned by the list. Both values are `builtin` for bundled algorithms. All operations except listing return process jobs; use `job_wait` or `job_get`. No open project is required.

Use the existing `modifier_plan`, `modifier_add`, and `modifier_update` operations for external algorithms. Configurations are validated against their package schema. Plans pin their package reference; changing plugins invalidates prepared candidates. Public operations declare strict result schemas.

## Authoring and protocol

See [six-language examples and packaging instructions](../../../examples/plugins/README.md). Package-root `plugin.json` contains:

- `protocol: 1`, package `id`, `version`, and `name`.
- `commands`: argument arrays indexed by platform, e.g. `windows-x86_64`. JVM workers use `jvm: ["$JAVA", "-jar", "worker.jar"]`. Commands do not pass through a shell.
- `files`: relative payload paths and SHA-256 digests. Traversal, symlinks, and unlisted files are rejected.
- `algorithms`: `package-id/type`, algorithm version/name, targets, dependencies, exclusivity, read/write domains, composable kinds, `configSchema`, and `defaults`.
- Optional `parameters` declare shared parameter `id/name/min/max/default`. Existing definitions must be compatible; other new objects use instance-owned IDs.

Requests/responses are one UTF-8 JSON line each. Stdout is protocol-only; logs go to stderr. Requests contain `protocol/id/method/type/version/instance/config/targets`. Responses echo `protocol/id` and return `commands` or `error`. A worker handles one request at a time; faults/timeouts reset it, and cancellation terminates it without committing partial results.

Inputs are projected to declared read/write domains; `*` or unknown domains conservatively receive the full input. `changed` and `removed` update the object index, with `reset` starting a session. `frame`, `mesh:<id>`, and `deformer:<id>` point to content-addressed resources in the request's `resource_directory`, also available as `PSD2LIVE_PLUGIN_RESOURCES`. These use the versioned exact `RigIrObjects` binary format. `mesh_order` and `deformer_order` preserve order.

The portable `meshes` view exposes target IDs, opacity, and position/UV buffer references; `parameters` exposes readable parameters. Float resources use little-endian `f32le`, named by SHA-256. Commands may also return such references in place of float arrays; the host validates checksums and finite values before expanding them.

Commands supported:

- `mesh_opacity`: target `id` and `value` in 0–1.
- `physics` and `motion`: `config` uses the corresponding bundled modifier format; the host assigns output IDs.
- Authoring primitives: `structure`, `set`, `parameter_keys`, `delete`, `canvas_geometry`, `canvas_topology`, `canvas_create_warp`, `canvas_create_rotation`, `canvas_create_glue`, `canvas_glue_edit`, `path_put`, and `path_delete`, using authoring-journal formats. They execute only on an isolated candidate.

New IDs use `modifier:<instance-id>:<UTF-8 hex of local name>`. The host validates actual writes, shared parameters, dependencies, overrides, and conflicts. Plugins never access document history or UI directly.

## Performance boundaries

Bundled calls avoid IPC. External workers reuse processes, content-addressed buffers, and incremental input synchronization. Author input, binding templates, and algorithms with declared dependencies have separate caches. Folder edits do not evaluate algorithms.

Some bundled binding algorithms still generate templates and extract their local contribution; their full separation into local algorithms is not complete. Stack evaluation remains serial, without independent-item scheduling. The complete-generation three-second target has not been reached; plugin support and cache hits do not establish that target.
