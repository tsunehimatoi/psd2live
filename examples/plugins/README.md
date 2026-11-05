# Algorithm plugin examples

Each example multiplies the selected meshes' opacity by `factor`. Apply two instances to check composition (multiplication commutes, so this example does not verify order-sensitive behavior). The worker stays alive between requests. These are algorithm examples, not editor extensions.

Build on the plugin author's computer, then install the resulting ZIP through Preferences → Algorithm plugins or `plugin_install`. Installation never compiles source or runs setup scripts.

## Build and package

- Python: put `worker.py`, `sdk.py`, a redistributable Python interpreter and its standard library in a payload directory. Use `runtime/python.exe worker.py` as the packaged command. Do not point at an interpreter outside the package. Include the runtime's license.
- Kotlin: `gradlew buildPluginExample` produces `build/plugin-example/worker.jar`, a standalone JVM worker. Package it with platform `jvm` and command `$JAVA -jar worker.jar`. The application supplies Java.
- Rust: `cargo build --release --manifest-path examples/plugins/rust/Cargo.toml`; copy the executable into the payload directory.
- C: compile `c/worker.c`, for example `cc -O2 -o worker c/worker.c`.
- C++: compile `cpp/worker.cpp`, for example `c++ -O2 -o worker cpp/worker.cpp`.
- C#: `dotnet publish examples/plugins/csharp/Worker.csproj -c Release -r win-x64 --self-contained true -o build/plugin-payload/csharp`; package the entire published directory, including runtime notices/licenses.

Native binaries must include their redistributable runtime dependencies (or use static runtime linkage). Build separately for each OS/architecture. The C-compatible protocol helper also works in C++.

Package an already-built payload:

```shell
python examples/plugins/package.py --payload build/plugin-payload/c --output build/plugins/c.zip --language c --platform windows-x86_64 worker.exe
python examples/plugins/package.py --payload build/plugin-example --output build/plugins/kotlin.zip --language kotlin --platform jvm -- '$JAVA' -jar worker.jar
```

For Git installation, commit the **contents** of the ZIP, including `plugin.json`, at the repository root of a dedicated distribution branch. Do not put build scripts or unlisted source files in that package. Use `ref` to choose a branch, tag or commit; the installer records the resolved commit. No submodules are fetched.

See the [plugin protocol](../../docs/en/guide/ALGORITHM_PLUGINS.md) for requests, buffers, deterministic IDs, response operations and limitations.
