# Development and CLI

[Docs](../../README.md) · [User guide](USER_GUIDE.md)

Source builds require JDK 21. Use the bundled Gradle Wrapper. Windows release packages include a runtime; source builds use your local JDK.

```powershell
.\gradlew.bat run
.\gradlew.bat run --args="--help"
.\gradlew.bat run --args="--input examples/tml/psd-input/tml.psd --output build/example-output"
```

Linux / macOS: `./gradlew`.

No arguments starts the GUI. Arguments select the CLI, which requires `--input` except for `--help`. The CLI generates from PSD; it does not reopen a desktop project for editing.

## CLI

| Option | Default | Meaning |
| --- | --- | --- |
| `--input <path>` | required | Layered PSD |
| `--output <path>` | psd2live-output beside PSD | Export directory |
| `--lang <zh\|en\|ja>` | system language | Log language |
| `--atlas <size>` | 4096 | Atlas size |
| `--mesh-spacing <px>` | 64 | CLI mesh spacing |
| `--head-strength <value>` | 1.0 | Head strength |
| `--body-strength <value>` | 1.0 | Body strength |
| `--mesh-only` | off | Mesh-only mode |
| `--no-deformers` | off | Disable deformers |
| `--no-motions` | off | Omit motions |
| `--no-physics` | off | Disable physics |
| `--no-cmo3` | off | Omit CMO3 |
| `--no-moc3` | off | Omit MOC3 |
| `--no-json` | off | Omit diagnostic JSON |
| `--upscale <1\|2\|4>` | 1 | 1 disables upscaling |
| `--upscale-python <path>` | python | Python executable |
| `--nunif-dir <path>` | empty | Source checkout |
| `--upscale-model <path>` | empty | Weights directory |
| `--upscale-tile <64..512>` | 256 | Input tile |
| `--upscale-noise <-1..3>` | 1 | -1 disables denoising |
| `--no-upscale-neural-alpha` | off | Disable neural alpha |

Defaults below come from `Main.kt`. The GUI PipelineConfig starts with mesh spacing 40, while the CLI defaults to 64. Keep at least one of CMO3, MOC3 or diagnostic JSON enabled. `--upscale-neural-alpha` is a compatibility flag: neural alpha is already enabled, and `--no-upscale-neural-alpha` disables it.

## Build and validation

```powershell
.\gradlew.bat test
.\gradlew.bat distZip
.\gradlew.bat createDistributable
.\gradlew.bat packageDistributionForCurrentOS
```

Run tests relevant to your change. `distZip` collects the documentation and other content configured in `distributions.main`; it is not a complete portable app. `createDistributable` builds the application directory with its runtime, and `packageDistributionForCurrentOS` builds the configured installer formats for the current OS. Build the optional native bridge separately and do not commit or redistribute proprietary SDK resources with this project.

## Inspect outputs

Deliver every resource referenced by model3.json, and read diagnostics and export warnings. Desktop .psd2live archives preserve editing projects; CLI .psd2live.json files are reports only.

[Main.kt](../../../src/main/kotlin/io/github/psd2live/Main.kt) · [PipelineConfig](../../../src/main/kotlin/io/github/psd2live/core/Model.kt) · [Gradle](../../../build.gradle.kts) · [SDK](CUBISM_SDK_SETUP.md) · [Texture upscale / 高清化](../../zh/guide/TEXTURE_UPSCALE.md)
