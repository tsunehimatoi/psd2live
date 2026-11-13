# PSD2Live Standalone Plugin SDK

Independent, language-agnostic SDK and dependency manifest for external modifier plugins (`protocol: 2` binary-framed stdio & `protocol: 1` line JSON).

## Layout
- `deps-manifest.json` — Shared runtime & extensible dependency contract (`PSD2LIVE_PLUGIN_DEPS` -> `.runtime/deps.json`).
- `rust/psd2live_plugin_sdk/` — Zero-dependency pure Rust SDK (`PluginServer`, `protocol: 2` / `1`, `f32le` blob helpers, `load_dependencies()`).
- `python/psd2live_plugin.py` — Zero-dependency Python 3 SDK.
- `pack.py` — Standalone packager (`plugin.manifest.json` -> `plugin.json` + `.zip`).

## Packaging Usage
```bash
# Package a Rust plugin from its crate directory (outputs dist/<id>-<version>.zip)
cargo build --release --manifest-path plugins/native-core/Cargo.toml
python plugin-sdk/pack.py plugins/native-core

# Override version, add dependencies, or specify output path
python plugin-sdk/pack.py plugins/native-core -o out/native-core.zip --version 1.1.0 --dep psd2live.native.runtime
```
