<!-- lang: zh en ko ja -->
> [中文](../../zh/guide/DEVELOPMENT.md) · [English](../../en/guide/DEVELOPMENT.md) · **한국어** · [日本語](../../ja/guide/DEVELOPMENT.md)

# 개발과 명령줄

[문서 목차](../README.md)

이 페이지는 소스에서 실행하거나, 명령줄을 쓰거나, 개발에 참여하는 사용자를 위한 것입니다. 배포 패키지에는 런타임이 들어 있으므로, 일반적인 사용에는 이 페이지가 필요 없습니다.

## 환경

- JDK 21. Windows는 JetBrains Runtime(JBR) 21을 권장합니다. Windows 배포 패키지가 이것을 쓰며, 주 창은 그 위에서 시스템 창 프레임을 유지합니다(최대화, 스냅, 가장자리 드래그로 크기 조절은 시스템이 처리). 제목 표시줄은 프로그램이 그립니다. 다른 JDK로도 빌드, 테스트, 실행은 됩니다. Linux의 불투명 창은 모든 JDK에서 시스템 제목 표시줄을 유지하고, 투명 창은 여전히 프로그램 제목 표시줄을 씁니다. Windows는 다른 JDK에서 테두리 없는 창을 씁니다. Windows 패키징(`createDistributable`, `packageExe`)은 JBR 21 SDK(jpackage 포함)가 필요하며, 다른 JDK로 패키징하려면 `-Ppsd2live.allowNonJbr=true`를 추가합니다. macOS의 DMG(`packageDmg`)는 Gradle을 실행하는 JDK로 빌드하며 JBR이 필요 없습니다. Swift 껍데기가 시스템 창 프레임을 유지하기 때문입니다. Gradle은 저장소에 포함된 Wrapper를 쓰므로 따로 설치하지 않아도 됩니다.
- Windows는 `.\gradlew.bat`, Linux / macOS는 `./gradlew`를 씁니다. 아래 예는 모두 `./gradlew`로 적습니다.
- 공식 Cubism SDK는 빌드 전제가 아닙니다. 소스 빌드는 기본적으로 내장 렌더를 씁니다. 네이티브 미리보기는 [Cubism Native 미리보기](CUBISM_SDK_SETUP.md)를 참고하세요.

## 실행

```bash
./gradlew run                     # 不带参数：启动 GUI
./gradlew run --args="--help"     # 查看 CLI 帮助
python tools/examples/fetch.py --case tml
./gradlew run --args="--input examples/tml/psd-input/tml.psd --output build/example-output"
```

Windows에서는 루트의 `run-gui.bat`을 바로 실행할 수도 있습니다. 인자 없이 실행하면 GUI가 시작됩니다. 인자가 있으면 CLI로 들어가며, 이때는 `--input`이 필요합니다(`--help` 제외). CLI는 PSD에서 내보내기 파일을 바로 만들며, 이어서 편집할 수 있는 `.psd2live` 프로젝트는 만들지 않습니다.

## CLI 파라미터

| 파라미터 | 기본값 | 작용 |
| --- | --- | --- |
| `--input <path>` | 필수 | 입력하는 레이어 PSD |
| `--output <path>` | PSD와 같은 디렉터리의 `psd2live-output` | 내보내기 디렉터리 |
| `--lang <zh\|en\|ja\|ko>` | 시스템 언어 | 로그 언어 |
| `--clear-user-data` | — | 단독 사용: 현재 사용자의 설정, 작업 공간 저장소, `~/.psd2live` 아래의 런타임과 캐시를 삭제한 뒤 종료합니다. 편집기가 실행 중이면 삭제하지 않고 1을 반환합니다. Windows 제거 시 데이터도 함께 삭제하도록 선택하면 호출됩니다 |
| `--atlas <size>` | 4096 | 텍스처 아틀라스 크기 |
| `--mesh-spacing <px>` | 64 | 메시 간격 |
| `--mesh-pixels` | 끄기 | 긴 변을 2048 px로 줄인 문서 픽셀이 아니라 원본 픽셀로 메시 길이를 계산합니다 |
| `--mesh-wrap <단위>` | 0 | 감싸기 토폴로지: 이 값(메시 단위)보다 좁은 윤곽 틈을 닫고, 가는 돌기는 하나의 외곽을 공유합니다 |
| `--head-strength <value>` | 1.0 | 머리 변형 폭 |
| `--body-strength <value>` | 1.0 | 몸 변형 폭 |
| `--mesh-only` | 끄기 | 메시만 생성합니다 |
| `--no-deformers` | 끄기 | 디포머를 생성하지 않습니다 |
| `--no-see-through` | 끄기 | 눈썹과 눈 모두 앞머리를 투과하지 않습니다(기본은 둘 다 투과. 출력에는 이전보다 눈썹 복제, 앞머리의 눈 구멍과 그 복제가 더 있습니다) |
| `--no-see-through-eyes` | 끄기 | 눈이 앞머리를 투과하지 않습니다(지금은 기본으로 켜진 `--see-through-eyes`도 여전히 받습니다) |
| `--see-through-opacity <0..1>` | 0.5 | 눈썹과 눈이 앞머리를 투과하는 정도 |
| `--no-motions` | 끄기 | 모션을 출력하지 않습니다 |
| `--no-physics` | 끄기 | 물리를 생성하지 않습니다 |
| `--no-cmo3` | 끄기 | CMO3를 출력하지 않습니다 |
| `--no-moc3` | 끄기 | MOC3를 출력하지 않습니다 |
| `--no-json` | 끄기 | 진단 JSON을 출력하지 않습니다 |
| `--upscale <1\|2\|4>` | 1 | 텍스처 고해상도화 배율. 1은 끄기입니다 |
| `--upscale-python <path>` | Windows는 `python`, 그 외는 `python3` | nunif 의존성이 설치된 Python |
| `--nunif-dir <path>` | 비어 있음 | nunif 소스 디렉터리 |
| `--upscale-model <path>` | 비어 있음 | 가중치 디렉터리 |
| `--upscale-tile <64..512>` | 256 | 추론 분할 크기 |
| `--upscale-noise <-1..3>` | 1 | 노이즈 감소 등급. -1은 노이즈 감소 없음 |
| `--no-upscale-neural-alpha` | 끄기 | 알파를 쌍선형으로 확대합니다 |

설명:

- 기본값은 [`Main.kt`](../../../src/main/kotlin/io/github/psd2live/Main.kt)를 기준으로 합니다. GUI의 초기 메시 간격은 `PipelineConfig`(40)에서 오며, CLI의 64와 다릅니다.
- CMO3, MOC3, 진단 JSON 중 적어도 하나는 남겨야 합니다.
- 신경 알파는 기본적으로 켜집니다. `--upscale-neural-alpha`는 이전 명령과의 호환을 위해서만 남습니다.
- 고해상도화 준비와 예는 [텍스처 고해상도화](TEXTURE_UPSCALE.md)를 참고하세요.

### 창 없는 MCP 서비스

`./gradlew run --args="mcp [--port N] [--token T] [--store DIR] [--profile core|full]"`는 창을 열지 않고 MCP 엔드포인트만 시작해, 에이전트가 따로 작업하게 합니다. 작업 공간은 애플리케이션 층 호스트가 맡고, ViewModel과 Compose 상태는 만들지 않습니다. 시작한 뒤 `endpoint`, `token`, `profile`, `store`, `capabilities`(`view_sample_motion=yes|no`, 빌드에 Cubism Core가 있는지)를 출력하고 프로세스가 끝날 때까지 유지합니다. 스크립트는 `python tools/mcp_proxy.py call <조작> [JSON] --edit` 또는 Python의 `mcp_proxy.Client`로 호출하며, `PSD2LIVE_MCP_ENDPOINT`와 `PSD2LIVE_MCP_TOKEN`을 그쪽으로 둡니다. 작업 공간 저장소의 기본은 사용자 디렉터리의 `psd2live-mcp` 아래이며, 편집기와 분리됩니다(히스토리와 인스턴스 잠금을 공유하지 않음). `--token`은 URL에 안전한 Base64 문자 32–256개여야 하며, 생략하면 무작위로 생성됩니다.

## 다른 형식으로 내보내기

`export` 명령은 `.psd2live` 프로젝트(현재 히스토리 상태) 또는 PSD(기본 설정으로 생성)에서 중립 IR을 거쳐 아무 대상으로 내보내고, 손실 보고서를 씁니다.

```bash
./gradlew run --args="targets"                                            # 列出导出目标
./gradlew run --args="export model.psd2live --target gif --set clip=Nod --set size=512"
./gradlew run --args="export model.psd2live --target psd-pose --set pose=ParamAngleX=20 --output out/pose"
```

- 파일은 `--output`에 기록됩니다(기본은 입력 옆의 `<이름>-<대상>`). 손실 보고서는 `<이름>.<대상>.report.json`입니다. `--name`은 기본 이름을 지정합니다.
- `--set key=value`는 반복할 수 있습니다. 각 대상의 설정 키는 [중립 리그 IR과 내보내기 대상](../spec/EXPORT_TARGETS.md)을 참고하세요.
- 종료 코드: 0 성공, 1 내보내기 실패, 2 파라미터 오류.

## 테스트와 패키징

```bash
./gradlew unitTest                               # 本地快速单元与契约测试（不含 scenario 与外部 worker/plugin 测试）
./gradlew integrationTest                        # 场景与外部 worker/plugin 集成测试
./gradlew test                                   # 各模块日常 JVM 回归
./gradlew test pluginBuildTest                   # 完整 JVM 回归，含插件实际编译、热替换、撤销和重开
./gradlew test --tests "io.github.psd2live.scenario.CatalogContractsTest"   # 单个测试类
./gradlew createDistributable                    # 带运行时的应用目录
./gradlew packageDistributionForCurrentOS        # 当前平台的安装包
./gradlew packageDmg                             # 仅 macOS：带 Swift 外壳的 DMG
```

Linux 앱 디렉터리는 매니페스트 `Class-Path` JAR로 의존 순서를 유지하고, 네이티브 런처 인자를 줄여,
[JDK-8380085](https://github.com/openjdk/jdk/commit/54c5e41cbaabcd21d7bbf8f5667b2e35cfe12dd7)
가 긴 클래스 경로에서 JVM이 시작되기 전에 충돌하는 일을 피합니다. JDK나 패키징 구성을 바꾼 뒤에는 다음 검사를 실행하세요. 임시 사용자 설정을 쓰며,
공백, 따옴표, 비영어 문자가 있는 이전 경로를 검사합니다.

```bash
python3 packaging/linux/smoke-test.py build/compose/binaries/main/app/PSD2Live
```

- `packageDmg`는 macOS에서만 쓸 수 있습니다. `createDistributable`이 패키지에 넣은 런타임을 쓰고, 시작 프로그램을 `packaging/macos`의 Swift 껍데기로 바꿉니다. 그래서 Dock 이름, 문서 유형, 창 버튼은 모두 시스템의 것입니다. 메뉴는 화면 위 메뉴 막대에 나타납니다. 창 안의 메뉴는 그대로 남는데, 거기에 모든 명령이 나열되어 있기 때문입니다. DMG의 아키텍처는 그것을 빌드한 Mac과 같습니다(Apple 칩 또는 Intel). macOS 13 이상이 필요합니다. 패키지는 공증되지 않습니다. 네트워크에서 받은 뒤에는 Finder에서 보조 클릭으로 연 다음 `PSD2Live`를 “응용 프로그램”으로 드래그합니다.
- `CI`는 Linux, Windows, macOS의 x64와 ARM64에서 네이티브 런타임과 알고리즘 플러그인을 빌드하고, JVM 회귀를 실행하며 패키지 안 worker를 검사합니다. Linux는 푼 Deb에서 CLI를 시작하고, Windows는 앱 이미지를 쓰며, macOS는 마운트한 DMG도 검증합니다. 중첩 Gradle을 시작하는 `pluginBuildTest`는 Linux x64, Windows x64, macOS ARM64에서 각각 한 번씩 실행되며, 로컬 `check`에도 포함됩니다. 네이티브 런처의 오류 처리를 검증하고, 패키지 안 런타임으로 마운트한 앱을 시작합니다. 공유 `.github/actions/package-macos`는 릴리스 워크플로에도 두 아키텍처의 DMG를 제공하며, Cubism SDK는 포함하지 않습니다.
- 두 패키징 모두 현재 빌드 플랫폼의 네이티브 라이브러리만 포함하고, 앱 리소스 디렉터리에 `LICENSE`, `THIRD_PARTY_NOTICES.md`, `licenses/`를 함께 둡니다.
- 공식 SDK 리소스(`src/main/resources/cubism/`)는 기본적으로 패키징하지 않으며, `-Ppsd2live.includeCubism=true`를 넘기거나 `PSD2LIVE_INCLUDE_CUBISM=true`를 설정할 때만 포함됩니다. SDK가 든 패키지는 공개 배포하면 안 됩니다. 릴리스 절차는 [CI와 릴리스](CUBISM_CI_RELEASE.md)를 참고하세요.
- Linux에서는 `./native/package_linux.sh`로 시스템 JDK 21이 필요한 로컬 시작 패키지를 만들 수도 있습니다(출력은 `dist/linux-<타임스탬프>/`). 자세한 내용은 [native/README.md](../../../native/README.md)를 참고하세요.
- 프로젝트에는 별도의 lint 작업이 없으며, 코드 스타일은 `kotlin.code.style=official`입니다.
- Rust 런타임은 `runtime/`에서 `cargo test`, `cargo build --release`로 빌드합니다. [런타임](../spec/RUNTIME.md)을 참고하세요. `./gradlew run`과 패키징은 쓸 수 있는 cargo가 없으면, Linux에서 CI(`runtime-prebuilt.yml`)가 같은 소스로 빌드한 라이브러리를 받습니다. `runtime/src`, `Cargo.toml`, `Cargo.lock`의 해시로 맞추고(`./gradlew printRuntimeHash`) sha256을 검사합니다. 찾지 못하거나 오프라인이면 내장 평가기로 돌아갑니다. `-Ppsd2live.prebuiltRuntime=false`는 다운로드를 끕니다. `runtime/`를 고쳐 반복 실행할 때는 `-Ppsd2live.fastRuntime=true`를 더하면 LTO가 꺼져 컴파일이 크게 짧아집니다. 생성된 라이브러리는 더 느리므로 패키징에는 쓰지 마세요.
- 테스트 스위트는 빠른 단위 테스트와 계약 테스트만 남기며, 전체 `./gradlew test`는 1분 안에 끝나야 합니다. 예제 PSD(tml, ds)에서 전체 파이프라인을 돌리거나, 콜드 재생을 단계적으로 비교하거나, 화면 전체를 렌더하는 테스트를 넣지 마세요. 이런 검사는 아래 개발 도구로 작성하고, 필요할 때 직접 실행합니다.

### 알고리즘 플러그인 런타임 검사

Windows에서 앱 이미지를 빌드할 때는 Windows SDK의 `mt.exe`가 필요합니다. 패키징 작업은 앱과 패키지 안 Java 런처에 UTF-8 매니페스트를 합쳐, 시스템을 넘는 코드 페이지의 설치 경로에서 런타임 DLL을 불러올 수 있게 합니다.

앱 안의 알고리즘 플러그인은 `java.home/bin/java`(Windows는 `java.exe`)로 독립 JVM을 시작하므로, 패키징한 런타임은 Java 시작 프로그램을 남겨야 합니다.
다음 검사는 패키지 안의 실제 알고리즘 worker를 쓰고, 시스템 JDK가 없는 상태, 임시 사용자 설정, 공백과 비영어 문자가 있는 이전 경로에서 시작을 검증합니다.

```bash
python3 packaging/smoke-test-workers.py build/compose/binaries/main/app/PSD2Live
# macOS 使用 build/PSD2Live.app；Windows 使用对应的 PSD2Live 应用目录。
```

### Linux 창 회귀 검사

실제 그래픽 세션이 필요합니다. JDK 21의 AWT는 Wayland 세션에서 XWayland를 통해 실행됩니다.
불투명 창의 이동, 크기 조절, 최대화는 창 관리자에 맡겨, 바탕 화면 전체의 작업 공간을 디스플레이 하나의 작업 공간으로 착각하지 않게 합니다.

```bash
./gradlew :test --tests '*NativeTitleBarTest' --tests '*BugListRegressionTest'
PSD2LIVE_TOOLS=1 ./gradlew :test --tests '*LinuxWindowModesTool'
```

그래픽 도구는 최대화 작업 공간, 복원 크기, 외부 창 상태, 한 축 최대화를 검사합니다. 일반 테스트 실행은 이것을 건너뜁니다.
사람이 시스템 제목 표시줄 드래그, 모서리 크기 조절, 최대화 버튼과 바탕 화면 단축키, 크기/배율이 다른 디스플레이 사이 이동,
디스플레이를 뽑은 뒤 다시 열기, F11 전체 화면 전환, 투명 창 드래그 복원을 확인합니다. Windows/macOS에서도 창 조작 스모크 테스트를 해야 합니다.

독립 설정으로 소스 빌드를 실행해, 이미 설치된 프로그램과 작업 공간을 공유하지 않게 합니다(먼저 `JAVA_HOME`을 JDK 21로 둡니다).

```bash
./gradlew writeRunArgs
mkdir -p build/gui-check/home
"$JAVA_HOME/bin/java" \
  "-Duser.home=$PWD/build/gui-check/home" \
  "-Djava.util.prefs.userRoot=$PWD/build/gui-check/home/prefs" \
  @build/run/jvm.args
```

## 외부 예제와 검수

예제 소재, 프로젝트, 전시 출력은 [psd2live-examples](https://github.com/tsunehimatoi/psd2live-examples)에서 관리하며, 라이선스를 따로 적지 않으면 기본은 CC0 1.0입니다. Python 3.11+ 다운로드 명령: `python tools/examples/fetch.py --case tml`. `--all`은 고정된 사례를 모두 받습니다. 파일은 여전히 `examples/<id>/`에 기록되고, 다운로드 캐시는 `build/example-assets/`에 있습니다.

주 프로젝트의 `tools/examples/fixtures.lock.json`은 소재 버전, 다운로드 주소, 압축 패키지, 파일별 SHA-256과 크기를 고정합니다. 소재 업그레이드는 잠금 파일을 따로 수정하며, 예제 저장소의 최신 분기를 따르지 않습니다. 다운로드 실패, 크기나 해시 불일치는 오류를 보고합니다.

일반 CI는 작은 합성 소재와 내부 계약 테스트를 쓰며, 큰 예제를 받지 않습니다. GitHub Actions의 `Example acceptance` 워크플로는 수동으로 켜며, 고정된 `tml`, `ds`를 받고 `ExportGoldenTool`로 전체 생성, 내보내기, CMO3 가져오기 경로를 검사하고, 네 변형의 내보내기와 미리보기 보고가 모두 있어야 합니다. 보고는 생성 경로 검사용이며, 시각 검수나 히스토리 기준 비교를 대신하지 않습니다. 릴리스 전에 실행하고 업로드된 보고를 확인할 수 있습니다. MCP 내부 회귀 테스트는 주 저장소에 남고, 외부 사용 사례와 발견 검사는 예제 저장소를 참고하세요.

## 개발 도구

`src/test/kotlin/io/github/psd2live/tools/`에는 눈으로 확인하고 측정하는 개발 도구가 있습니다. 테스트로 작성되어 파이프라인 내부 인터페이스를 호출할 수 있으며, `PSD2LIVE_TOOLS=1`을 설정할 때만 실행되고 평소의 `./gradlew test`는 건너뜁니다. 출력은 `build/tools/`에 기록됩니다. 개발 도구를 켜면 테스트 작업이 Gradle 결과 캐시와 건너뛰기 판정을 끄므로, 측정이 매번 실제로 실행됩니다.

```bash
python tools/examples/fetch.py --case ds
PSD2LIVE_TOOLS=1 PSD2LIVE_SAMPLE=ds ./gradlew test --tests "io.github.psd2live.tools.MotionSheetTool.body"
```

첫 프레임 도구의 측정 범위는 서로 다릅니다. 오프스크린 도구는 GPU 동기화와 되읽기를 포함하고, 모델 생성, GL context 생성, Compose 합성, 창 제시는 포함하지 않습니다. `renderer_cold`는 렌더러를 새로 만든다는 뜻이지 프로세스 콜드 스타트가 아닙니다. 창 도구는 실제 앱, 뷰포트, Skiko 재생으로 GPU 완료 회신을 얻으며, 현재는 P2LRT로 한정됩니다. 모델 생성은 끝났고 JVM과 창은 이미 따뜻하며, 창 교환, 시스템 바탕 화면 합성, 디스플레이 스캔은 다루지 않습니다. 두 지표 모두 완전한 편집 지연이 아니며, 알고리즘 생성 시간과 바로 이어 붙일 수도 없습니다.

`verifyReadback`, `smoke`, `verifyWindowPixels`는 기능 검사용이며 성능 분위를 출력하지 않습니다. `measure`는 장면／모드마다 유효 샘플을 최소 30개 모으며, 예열 샘플은 통계에 넣지 않습니다. 시간 초과나 검증 실패는 지연 0으로 기록하지 않고, 성공 통계를 출력하지 않습니다. 창 도구는 그래픽 초기화 전에 `PSD2LIVE_COMPOSITOR=1` 또는 `0`을 설정하고, 각각 실행해 실제 모드를 맞추며, 전환 전에 보고를 저장해야 합니다. 창 픽셀 되읽기와 PNG 인코딩은 소요 시간을 흔들므로, 그 입구는 성능 샘플로 쓰지 않고 창 교환이나 디스플레이 스캔도 측정하지 않습니다. GPU／성능 샘플링은 단독으로 실행해, 빌드나 다른 성능 작업과 병렬로 돌리지 않습니다. 입구 파라미터와 완전한 경계는 첫 올바른 화면의 측정 경계를 참고하세요.

| 도구 | 내용 | 출력 |
| --- | --- | --- |
| `FirstCorrectFrameTool.verifyReadback` / `.measure` | 세 단계 합성 메시의 알려진 픽셀 검사와, 캡처 개정부터 오프스크린 편집 캔버스 GPU 되읽기 완료까지의 측정. 각각 새로 만든 렌더러와 예열된 렌더러를 다룹니다 | `first-correct-frame-check/report.json`, `first-correct-frame/report.json` |
| `WindowActivationTool.smoke` / `.measure` | 실제 앱 창에서 P2LRT 모델 활성화 캡처부터 창 GPU 완료까지의 회신 검사와 측정. 두 compositor 모드를 각각 실행합니다 | `window-activation-smoke/report.json`, `window-activation/report.json` |
| `WindowActivationTool.verifyWindowPixels` | 실제 Skiko 창 Surface를 되읽어 네 패턴의 개정 색, 뷰포트 위치, 첫 GPU 회신 신원을 맞춥니다. 두 compositor 모드를 각각 실행하며 기능 검수용입니다 | `window-activation-pixels/report.json`, `window-activation-pixels/window-*.png` |
| `WindowActivationTool.verifyDocumentEdits` / `.measureDocumentEdits` | 실제 공개 편집 요청이 후보 구축과 CAS를 거쳐 창의 올바른 프레임까지 갑니다. 편집, 구축, 개정, 실제 픽셀을 맞추며 두 compositor 모드를 각각 실행합니다 | `window-document-edits/report.json`, `window-document-edits-measure/report.json` |
| `DeformerDeletionVisualTool` | 합성 비대칭 메시가 부모 디포머를 삭제하기 전과 후, MOC3 내보내기 되읽기 뒤의 실루엣 대조 | `deformer-deletion/comparison.png` |
| `MotionSheetTool.motions` | 각 프리셋 모션을 시간으로 펼친 콜라주, 대기 반복, 호흡 전후와 차분 그림, 몸 Z | `motion-sheet/<예제>-*.png` |
| `MotionSheetTool.body` | 몸 X × 몸 Y, 다리와 상반신 클로즈업, 앞뒤 기울기, 크기 변화, 다리 포즈. 각각 스켈레톤 없음과 자동 스켈레톤 | `motion-sheet/<예제>-{stance,lean,size,legposes}*.png` |
| `MotionSheetTool.tracking` | 포인터가 화면을 12초 동안 천천히 돌고, 머리와 몸이 미리보기의 게인과 속도로 따라갑니다 | `motion-frames/<예제>-track/` |
| `MotionSheetTool.idle` | 대기 12초의 프레임별 그림 | `motion-frames/<예제>-idle/` |
| `ModelProfileTool.cmo3` | `.cmo3`의 파라미터, 디포머 트리(메시 축과 범위), 그래픽 메시, 구간 운동 프로필, 몸 파라미터에서 각 메시의 변위, 몸 X × 몸 Y 실루엣, 물리 그룹 | `model-profile/<이름>.txt`, `.png`, `-physics.txt` |
| `RigStudyTool` | 완성된 Cubism 모델을 연구합니다(`PSD2LIVE_MOC3`: `.model3.json` 또는 그 디렉터리, 모델 여러 개 가능). 파라미터와 그룹 및 용도, 파츠 트리, 디포머 트리와 각 오브젝트의 키폼 축, 메시 밀도, 마스크, Glue, 물리, 표정, 모션, VTube Studio 매핑. 파라미터별 변형량, 불투명도 / 그리기 순서 / 색 변화와 반구간 비선형성. 기본 포즈, 파라미터마다 다섯 프레임 스캔(영향 범위로 자름), 머리 각도, 눈, 입, 몸 메시 그림과 메시 와이어프레임. `PSD2LIVE_STUDY_ONLY`는 경로로 거르고, `PSD2LIVE_STUDY_REVIEW=1`은 검토 도구의 포즈만으로 눈 여닫기, 크게 뜨기, 시선 시퀀스를 냅니다(`review-eye.png`). 큰 모델은 `-Ppsd2live.testHeap=16g`가 필요합니다 | `rig-study/<디렉터리>/profile.txt`, `effects.txt`, `*.png` |
| `FitDetectionTool` | 예제(`PSD2LIVE_SAMPLE`)의 특징점 맞춤을 원그림 위에 겹칩니다. 각 눈의 안쪽·바깥쪽 눈꼬리, 위·아래 눈꺼풀 가장자리, 원화 눈 감기 선, 맞춘 눈꺼풀이 각 열림 키에서 보인 위 눈꺼풀 가장자리(노랑에서 파랑). 입을 벌린 포즈의 윗입술 위 가장자리(하양)와 아랫입술 아래 가장자리(청록). 입 레이어를 나열하고, 맞추지 못해 기존 알고리즘으로 돌아간 이유를 출력합니다 | `fit-detection/<예제>.png` |
| `RigReviewTool` | 예제(`PSD2LIVE_SAMPLE`)를 새 프로젝트의 레시피와, 모듈을 도입하기 전의 기존 생성 방식으로 각각 한 번씩 생성하고, 검토 포즈와 모션 클립에서 대조합니다. 부위마다 포즈 판 한 장(위아래 두 줄), 클립마다 나란히 재생되고 물리가 있는 GIF 한 장(눈 깜빡임, 느린 깜빡임, 웃는 눈 깜빡임, 시선, 말하기, 모음, 눈썹, 고개 돌리기, 끄덕이고 기울이기, 고개 휘두르기, 몸, 대기). `PSD2LIVE_REVIEW`는 부위를 고릅니다(eye, brow, mouth, head, hair, body, motion, 기본은 전부). `PSD2LIVE_REVIEW_SIZE`는 한 프레임의 변 길이입니다 | `rig-review/<예제>/<부위>-poses.png`, `<클립>.gif` |
| `ModelProfileTool.sample` | 생성 모델(스켈레톤 없음과 자동 스켈레톤)의 구간 운동 프로필, 몸 레이어, 자동 본 | `model-profile/<예제>.txt` |
| `SkeletonAutoViewTool.view` | 자동 스켈레톤을 예제 위에 겹쳐 그리고 본마다 출력해 관절 위치와 갈림을 확인합니다. `PSD2LIVE_SAMPLES`는 쉼표로 여러 예제나 PSD 경로를 나누고, `PSD2LIVE_ZOOM=左,上,右,下`(캔버스 픽셀)로 부분을 자릅니다 | `skeleton-auto/<예제>.png` |
| `SimBakeSuite` | 모델 프리셋으로 뒷머리, 앞머리, 의상 등의 물리체를 생성한 뒤(위쪽 10%를 고정한 뒷머리를 더함), 이전 방법과 입력별 방법으로 베이크하고, 맞춤에 참여하지 않은 모션(얼굴 추적에 가까운 따라가기, 고개 기울이기, 내장 모션, 입력별 계단, 고개 돌리기, 드래그, 흔들기)에서 참조 시뮬레이션과 내보낸 모델의 시각 지표를 비교합니다. [시뮬레이션과 베이크](SIMULATION.md)를 참고하세요 | `build/tools/sim-bake/<예제>/`: `summary.md`, 각 설정의 JSON, 끝 궤적 그림 |
| `DirectSimulationReviewTool.exportedAnimations` | 같은 입력이 완전한 XPBD, 또는 MOC3와 물리 파일을 내보낸 뒤 되읽기를 재생합니다. 기본은 실제 프리셋과 키폼 5개입니다. `PSD2LIVE_DIRECT_REVIEW_TRACK=xpbd`는 60 Hz 완전 시뮬레이션을 기록하고, 되읽을 때 `PSD2LIVE_DIRECT_REVIEW_REFERENCE_OUT`을 참조 출력으로 두어 정점마다 비교합니다. `PSD2LIVE_DIRECT_REVIEW_FIXTURE=controlled`로 강한 허리 회전만 따로 시험하거나, `PSD2LIVE_DIRECT_REVIEW_KEYS=9`로 키폼 밀도를 확인합니다. `PSD2LIVE_DIRECT_REVIEW_MIX_ANALYSIS=1`은 혼합 구간의 축별 변위 겹침, 가중 제한, 3차 프레임 차이를 기록합니다. `PSD2LIVE_DIRECT_REVIEW_MIX_ONLY=1`은 혼합 구간만 실행합니다 | `build/tools/direct-simulation/`의 25 fps 이미지 시퀀스, 프레임별 CSV, 내보낸 모델과 참조 궤적 |
| `SimRootDofExperiment` | 물체가 뿌리의 이동과 회전에만 반응하는지를 검사합니다. 각 입력이 뿌리를 얼마나 움직이고 형태를 얼마나 더 바꾸는지, 실제 구동과 강체 구동, 다른 폭, 같은 뿌리 운동의 서로 다른 입력 사이 차이 | `build/tools/sim-root-dof/<예제>.md` |
| `SimBakeProfileTool` | `tml` 뒷머리를 단일 스레드와 다중 스레드로 각각 한 번씩 베이크하고, 진행 표시로 구간을 나누어 시간을 재며 모든 스레드를 샘플링합니다 | 표준 출력 |
| `PendulumKernelTool` | 진자 배열 커널(정확 모드와 빠른 모드)과 `PhysicsEngine`의 프레임당 소요 시간과 편차 | 표준 출력 |
| `CanvasPerfTool` | Xvfb에서 실제 창을 열고, 편집 캔버스에서 차례로 정지, 마우스 올림, 휠 확대·축소, 가운데 버튼 이동, 변형 모드에서 얼굴의 모든 점을 드래그합니다. GPU 렌더와 소프트웨어 렌더를 각각 한 바퀴 돌리고, 프레임 간격, 인터페이스 스레드 지연, 인터페이스 스레드 핫스팟, 단계별 편집 캔버스의 GPU 프레임 수를 보고합니다(GPU 한 바퀴가 0이면 캔버스가 GPU를 타지 않은 것). `PSD2LIVE_CANVAS_AA`는 캔버스 안티앨리어싱을 고르고, `PSD2LIVE_OVERLAY=1`은 성능 오버레이를 엽니다. `Robot`으로 실제 포인터를 움직이므로 전용 X 서버에서 실행해야 합니다(`xvfb-run -a -s "-screen 0 1920x1080x24"`, 또는 GPU가 있는 컴퓨터의 별도 Xorg). 일상적으로 쓰는 바탕 화면에서는 실행하지 마세요 | `canvas-perf/report.txt`, 각 단계 스크린샷과 `.jfr` |
| `DragonBonesFidelityTool` | `tml`, `ds`(스켈레톤 없음과 자동 스켈레톤)를 DragonBones로 내보내고, `tools/dragonbones-check`(공식 DragonBones 5.7 런타임 코어, node 필요)로 재생합니다. 파라미터 애니메이션을 각 키프레임에서 편집기 평가기와 비교하고(오차는 내보내기 보고의 허용 오차를 넘지 않음), 클립 오차를 측정합니다 | `dragonbones-fidelity/report.txt` |
| `RuntimeConformanceTool.generate` / `.physics` | Rust 런타임의 참조 데이터입니다. `generate`는 무작위 모델(Warp, 회전, 중첩, 성긴 메시, 블렌드 형태, Glue, 채널, 파츠), 샘플, `PSD2LIVE_RUNTIME_PROJECT`가 지정한 프로젝트가 무작위 포즈에서 보인 편집기 평가 결과입니다(스켈레톤 샘플에는 가장 촘촘히 샘플링한 `rig.fine.p2lrt`도 있음). `physics`는 무작위 진자 그룹과 샘플 물리의 프레임별 궤적입니다. `runtime/`의 `p2lrt-conformance`로 비교합니다 | `runtime-conformance/<사례>/`, `runtime-physics/<사례>/` |
| `RuntimeConformanceTool.simulation` / `.simulationRig` | 시뮬레이션의 참조 궤적입니다. `simulation`은 무작위 천 조각(위쪽 변 고정, 강성 무작위)이 흔들림, 회전, 튀는 목표와 고정점 구동 아래에서 편집기 XPBD로 푼 프레임별 결과이며, 절반은 움직이는 캡슐 충돌체가 있습니다. `simulationRig`는 `tml` 뒷머리(위쪽 10분의 1 고정)를 베이크한 뒤 내보낸 모델과 실시간 장면, 그리고 베이크하지 않은 Rig 위에서 편집기 장면이 머리와 몸 모션을 따른 결과입니다. 각각 `p2lrt-conformance --sim`, `--sim-rig`가 읽습니다 | `runtime-sim/<사례>/trace.bin`, `runtime-sim-rig/back-hair/` |
| `RuntimeSamplesTool` | 눈으로 플레이어를 확인하는 합성 모델입니다. 그레이디언트 위의 각 색 혼합 모드, 곱하기 색과 스크린 색, 마스크와 반전 마스크이며, 소프트웨어 래스터라이저로 참조 그림을 렌더합니다 | `runtime-samples/features.p2lrt`, `features.png`, `layout.txt` |
| `WarpProbeTool` | 편집기 평가기의 블랙박스 탐색입니다. Warp 매핑(격자 안팎), Warp 아래의 회전 프레임, 뒤집기, 블렌드 형태, 성긴 메시. 런타임이 따로 구현한 것과 대조합니다 | `warp-probe/*.tsv` |
| `GeneratorCostTool` | 각 생성기(Rig 생성의 스켈레톤 유무, 스켈레톤 베이크 캐시 전후, 물리 그룹 목록, 생성 모션 캐시 전후, Rig IR 컴파일, 스윙 두 개의 생성과 재생, 시뮬레이션 베이크와 다시 기록)의 소요 시간, 그리고 그 입력의 내용 해시 소요 시간. 생성기를 생성 캐시에 넣을 가치가 있는지 판단합니다. `PSD2LIVE_SAMPLE`은 샘플을 지정합니다(앞머리와 뒷머리가 있어야 함) | `generator-cost/report.txt` |
| `ExportGoldenTool` | `tml`, `ds`의 스켈레톤 없음, 자동 스켈레톤, 사용자 지정 모션 세 변형, 그리고 스켈레톤 없는 cmo3를 새 프로젝트로 가져온 뒤의 모든 내보내기 파일과 미리보기 패키지 요약(cmo3는 되읽은 뒤 moc3로 낮춘 요약과 물리 그룹 수). 재구성 전후의 내보내기를 바이트 단위로 비교합니다. `PSD2LIVE_GOLDEN_LABEL`은 출력 이름을 지정하고, `PSD2LIVE_GOLDEN_RECIPE=legacy`는 모듈 도입 전의 프로젝트(이전 레시피)로 생성해 이전 프로젝트의 내보내기가 바뀌지 않았는지 확인합니다 | `export-golden/<이름>.txt`, `<이름>/<예제>-<변형>/` |
| `LayoutGoldenTool` | `ExportGoldenTool`과 같은 요약이지만, 테스트용 합성 캐릭터(전신 캐릭터, 입)로 PSD를 만들어 예제를 받을 필요가 없습니다. 파이프라인이 세 변형을 내보내고, PSD를 가져온 뒤 내보내기 세션을 거친 moc3 / cmo3 내보내기입니다. 변경 전후의 내보내기를 바이트 단위로 비교합니다(CAN3에는 무작위 GUID가 있어 매번 다름). `PSD2LIVE_GOLDEN_LABEL`은 출력 이름을 지정합니다 | `layout-golden/<이름>.txt` |
| `LayoutPanelsTool` | 창 없이 소재 패널, 레이어 표 태그 칸, 생성 막대를 렌더합니다. 합성 PSD 가져오기, 소재 하나를 놓아 생성 대기, 더블 클릭으로 폴더 열기, 항목 선택, 항목을 캔버스 착지점으로 드래그해 배치(착지점은 테스트가 등록하고, 착지점을 중심으로 검사), 생성 버튼에 마우스 올림, 태그 메뉴를 열고 닫은 뒤 다시 드래그 앤 드롭 | `layout-panels/*.png` |
| `LayoutWindowTool` | 실제 편집기 창을 열고(OpenGL), 합성 PSD를 가져와 Layout 작업 공간으로 전환한 다음, AWT 마우스 이벤트로 소재 폴더를 소재 패널에서 캔버스로 드래그해 착지점을 검사하고, 생성 막대 버튼을 클릭해 생성이 끝났는지 검사합니다. 스크린샷은 창 자신의 Skia 프레임입니다 | `layout-window/report.txt`, `{layout,dragging,placed,generated}.png` |
| `ExportGoldenComparisonTool` | 이미 생성된 골든 샘플 보고 두 개를 비교합니다. CAN3는 짝이 되는 CMO3 파츠 ID로 무작위 GUID 참조를 푼 뒤 전체 XML을 비교하고, 나머지는 요약을 비교합니다. 보고 두 개와 대응하는 CAN3 / CMO3 파일을 남깁니다. `PSD2LIVE_GOLDEN_BEFORE` / `PSD2LIVE_GOLDEN_AFTER`의 기본은 `baseline` / `optimized`입니다 | `export-golden/comparison.txt` |
| `WorkspaceLifecyclePerfTool` | 노드 20,000개의 합성 히스토리, 작업 공간 20개에 캔버스 4개씩. 공유 히스토리 스냅샷, 표시 전용 저장 신원 재사용, 즉시 재구축의 소요 시간과 스레드 할당을 비교합니다. 개발 도구를 켤 때만 실행되며, 실제 렌더 프레임 속도를 나타내지 않습니다 | `workspace-lifecycle/performance.txt` |
| `ExportPerfTool` | 예제 PSD(`PSD2LIVE_SAMPLE`)를 정수 배 최근접으로 확대한 뒤(`PSD2LIVE_EXPORT_SCALES`, 기본 `1,2`), 내보내기 대상마다(`PSD2LIVE_EXPORT_TARGETS`, 기본은 동영상 제외 전부) 시간을 재고 각 내보내기 파일의 요약을 기록합니다(cmo3는 그 안의 이미지 항목과, 되읽은 뒤 moc3로 낮춘 요약). 내보내기 가속 전후의 소요 시간과 바이트를 비교합니다. 래스터 대상은 배율마다 1024픽셀, 5 fps를 출력합니다. `PSD2LIVE_GOLDEN_LABEL`은 출력 이름을 지정하고, 큰 배율은 `-Ppsd2live.testHeap=8g` 등으로 테스트 힙을 키워야 합니다 | `export-perf/<이름>.txt`, `<이름>.digest.txt` |
| `ArtPrimitiveV2VisualTool` | `tml`에서 다리를 옆 연결 덩어리로 나누고, 왼쪽 속눈썹 다각형을 나누고, 입 다각형을 나눈 것을 각각 버전 1과 버전 2 기록으로 쓴 다음, 나누지 않음, v1, v2, v1/v2 차이(×4)를 눈 뜨기/반쯤 감기/감기, 입 다물기/벌리기/벌리고 웃기 등의 포즈에서 대조해 이음매, 어긋남, 빠진 픽셀을 확인합니다 | `art-primitive-v2/{legs,eye,mouth}.png` |
| `SafetyGoldenTool` | 자동 스켈레톤 Rig에서 시드가 있는 무작위 지오메트리 편집 24조의 완전한 지오메트리 안전 보고(`coverage` 제외). 검사기 변경 전후의 분류를 바이트 단위로 비교합니다. `PSD2LIVE_GOLDEN_LABEL`은 출력 이름을 지정합니다 | `safety-golden/<이름>.txt` |
| `BundleProfileTool` | moc3 미리보기 패키지의 항목별 소요 시간(IR 컴파일, IR 되돌리기, 정지 메시를 캔버스 공간으로 바꾸기, physics3/motion3, moc 낮추기와 기록, cdi3)과 지오메트리 안전 검사 소요 시간. `PSD2LIVE_SAMPLER=1`은 스택 샘플의 핫스팟도 출력합니다 | 표준 출력만 |
| `CubismParityTool` | 예제(`PSD2LIVE_SAMPLE`)의 미리보기 패키지를 앱에 포함된 Cubism 런타임(`live2d_renderer`, 오프스크린)으로 렌더하고, 편집기 자신의 렌더와 나란히 대조합니다. 전신, 얼굴, 입 클로즈업이며 정지, 고개 돌리기, 입 벌리기, 입 모양, 눈 감기 등의 포즈를 다룹니다. `PSD2LIVE_PARITY_RECIPE=legacy`는 모듈 도입 전의 생성 방식을 씁니다. Windows만 해당합니다 | `cubism-parity/<예제>[-legacy]-<뷰>.png` |
| `CubismCoreCheckTool` | `tml`, `ds` 미리보기 패키지의 moc3를 공식 Cubism Core의 일관성 검사(`csmHasMocConsistency`)에 넘깁니다. `PSD2LIVE_TEST_CUBISM_CORE`로 Core 동적 라이브러리를 지정해야 하며, 아니면 건너뜁니다. 작은 새 모델의 같은 검사는 `:umamo`의 `MocDefaultColorsTest`에 있습니다 | 표준 출력만 |
| `TextureWorkspaceTool` | `tml`에 몇 가지 밀도와 잠금을 설정하고 메시 형태로 한 번 배치한 뒤, 텍스처 아틀라스 페이지와 텍스처 패널(중국어·영어, 단일 선택/다중 선택/선택 없음, 히트맵 스위치, 밀도 슬라이더 미리보기), 편집 캔버스의 텍스처 아틀라스 픽셀과 원본 픽셀 비교를 렌더합니다 | `texture-workspace/*.png` |
| `AtlasFramePerfTool` | 창 없이 소프트웨어 렌더로 `tml` 텍스처 아틀라스 페이지와 텍스처 패널의 프레임 소요 시간을 측정합니다. `ImageComposeScene`에 대기, 마우스 올림, 모서리 드래그(밀도), 휠 확대·축소, 조각 드래그, 와이어프레임을 끈 뒤 마우스 올림의 포인터 입력을 보내고, 렌더마다 소요 시간(중앙값, p90, 최대)을 기록하며, 모서리 드래그 중, 편집 세션, 정지 페이지, 확대 근경 네 프레임을 저장합니다. GPU 렌더에는 창의 Skia 컨텍스트가 필요하며, `AtlasWindowPerfTool`과 `PreviewWindowTool`이 측정합니다 | `atlas-frame-perf/report.txt`, `{corner-drag,session,frame,zoomed}.png` |
| `AtlasWindowPerfTool` | 실제 편집기 창을 열어 텍스처 작업 공간으로 들어갑니다(`PSD2LIVE_SAMPLE`로 `.psd` 또는 `.psd2live`를 지정할 수 있으며 사본을 쓰세요). AWT 이벤트로 창에 포인터 입력을 보냅니다(실제 커서는 움직이지 않음). 뷰 모델을 거쳐 이동과 밀도를 제출하고, 조각을 드래그하고, 모서리를 드래그하고, 기다리지 않는 연속 드래그를 여섯 번 합니다. 프레임 간격, 인터페이스 스레드 지연, 각 바쁨 플래그가 언제 내려가는지, 인터페이스 스레드 핫스팟, 드래그 미리보기 그리기 횟수, 각 보기의 GPU 프레임 수를 기록합니다. 스크린샷은 창 자신의 Skia 프레임이며 화면을 찍지 않습니다 | `atlas-window-perf/report.txt`, `*.jfr`, `*-after.png` |
| `PreviewFpsTool` | 실제 편집기 창을 열어 미리보기를 재생합니다(`PSD2LIVE_WORKSPACE`는 작업 공간 프리셋, `PSD2LIVE_BACKENDS`는 백엔드). 프레임 속도와 프레임 간격 분위, 창 프레임 주기에서 Compose가 쓴 시간, 인터페이스 스레드의 각종 이벤트 소요 시간, 문서 상태가 바뀌는 빈도를 보고합니다. `PSD2LIVE_VSYNC=0`은 디스플레이 새로 고침을 기다리지 않아 실제 비용을 재고, `PSD2LIVE_PANELS=0`은 옆 막대를 숨기며, `PSD2LIVE_TRACE=1`은 조합 가능 함수마다 초당 조합 횟수를 셉니다. `PSD2LIVE_JFR=1`은 기록하고 인터페이스 스레드 핫스팟을 나열하고, `PSD2LIVE_SVG=1`은 마지막 프레임의 모든 그리기를 SVG로 내보내고 세며, `PSD2LIVE_INVALIDATIONS=1`은 창 합성기가 화면 전체를 다시 그리게 한 요청 출처를 나열합니다. `PSD2LIVE_CAPTURE=1`은 일시 정지한 뒤 GPU에서 창 화면을 읽습니다(`PSD2LIVE_COMPOSITOR=0`은 합성기를 꺼 비교). `PSD2LIVE_SCRUB=EDIT`(또는 `PREVIEW`)는 그 모드의 캔버스에서 패널 방식으로 파라미터 슬라이더를 드래그합니다(`PSD2LIVE_SCRUB_PARAMETER`는 파라미터, `PSD2LIVE_SCRUB_HZ`는 초당 샘플 수, 기본 240). 각 샘플이 인터페이스 스레드에서 쓴 시간을 보고합니다. `PSD2LIVE_GESTURE=hover`(또는 `deform`)는 편집기 자신의 입구로 편집 캔버스의 얼굴 메시에 마우스를 올리거나(또는 Deform 모드에서 모든 점을 드래그) 시스템 포인터는 움직이지 않습니다. 합성기의 프레임 수와 화면 전체 다시 그리기 횟수도 보고합니다 | `preview-fps/report.txt`, `{cubism,p2lrt}.{jfr,svg}`, `*-paused.png` |
| `PreviewWindowTool` | 실제 편집기 창을 엽니다(Skia는 OpenGL). 편집 캔버스와 두 미리보기 백엔드가 창의 Skia 컨텍스트에서 프레임을 내는지, 2초 재생 동안 받은 프레임 수를 확인하고, GPU에서 각 보기 텍스처를 읽습니다(화면을 찍지 않음). 2분 동안 멈추면 스레드를 덤프하고 종료합니다 | `preview-window/report.txt`, `{edit,cubism,p2lrt}.png` |
| `TexturePerfTool` | 텍스처 명령(조각 고정, 밀도 변경, 자동 배치, 예산 변경)의 제출 소요 시간입니다. `runtime`은 앱 명령을 거쳐 재구축을 따로 재고, `TEXTURE_PERF_SCENARIO`는 `plain`, `skeleton`(자동 스켈레톤), `deleted`(자동 스켈레톤이고 레이어 하나를 소프트 삭제)를 취합니다. `TEXTURE_PERF_JFR=1`은 이 회차를 `texture-perf/runtime.jfr`로도 기록합니다. `desktop`은 뷰 모델과 데스크톱 어댑터를 타며, Swing 스레드 멈춤, 인터페이스 캡처, 페이지 PNG를 기록합니다. 환경 변수는 Gradle 작업 입력이 아니므로 장면을 바꿀 때 `--rerun`을 더합니다 | `texture-perf/runtime-<장면>.txt`, `desktop.txt` |
| `ExportDialogTool` | “파일” 메뉴가 “가져오기”와 “내보내기” 하위 메뉴를 펼친 것, 각 대상의 “다른 이름으로 내보내기” 대화 상자, Live2D와 PSD 내보내기 대화 상자, “내보내기 성공” 창(중국어·영어. 성공 창에는 밝은 테마도 있음). 메뉴 그룹, 라벨, 컨트롤 배치를 확인합니다 | `export-dialog/<언어>-<대상 또는 메뉴>.png` |
| `ModalDialogTool.renderDialogs` / `.depthSplit` | 공통 팝업 외곽 위의 팝업(중국어). 제목 표시줄, 본문, 아래 조작 막대가 같은지 확인합니다. `renderDialogs`는 설정, 도움말, 텍스처 고해상도화, 그리기 순서 팝업(어두운 테마)이고, `depthSplit`은 앞뒤 분리 팝업(어두움, 밝음, 큰 글자)입니다 | `modal-dialog/<팝업>.png`, `depth-split-{dark,light,large-text}.png` |
| `CanvasLookSheetTool.meshLook` / `.toolIcons` | 캔버스 그리기 스타일의 콜라주입니다. `meshLook`은 편집 가능한 지오메트리(`MeshLook`)가 어두운, 중간, 밝은, 채도 높은 바탕 위에서 보인 여러 상태(구조, 선택, 마우스 올림, 브러시 영향 범위)입니다. `toolIcons`는 모든 캔버스 도구 아이콘과 미리보기 옆 막대 스위치이며, 밝고 어두운 두 테마입니다 | `canvas-look/mesh-look.png`, `tool-icons-{dark,light}.png` |
| `CliPresetMigrationTool.compareRawPreset` | 같은 PSD의 히스토리 바인딩과 명시적 프리셋 스택을 대조합니다. `PSD2LIVE_SAMPLE`로 샘플을 고르고, `PSD2LIVE_CLI_PHYSICS=0` 또는 `PSD2LIVE_CLI_MOTIONS=0`으로 해당 출력을 끕니다. `PSD2LIVE_CLI_VERIFY=1`은 실제 CLI 입구로 내보내고 완전한 IR과 되읽기 결과를 맞춥니다. `PSD2LIVE_CLI_TARGET_LOADER=1`을 더하면 `export` 하위 명령의 기본 PSD 로드 경로를 맞출 수 있습니다 | `cli-preset-migration/report.txt`, `report-target-loader.txt` |
| `CliPresetMigrationTool.compareMeshOnly`, `compareWithoutDeformers` | 메시만 모드와 디포머 없음 모드를 각각 맞춥니다. 같은 샘플과 엄격한 검증 스위치로 이 옵션들의 독립된 차이를 기록합니다 | `cli-preset-migration/report-mesh-only.txt`, `report-no-deformers.txt` |
| `ModifierPerfTool.profile` | 애플리케이션 층을 거쳐 PSD를 완전히 가져옵니다. 권장 프리셋, 시뮬레이션 베이크, 최종 미리보기 패키지, 초기 히스토리가 포함됩니다. 같은 JVM에서 처음과 다시 가져오기를 각각 기록합니다(매번 새 프로젝트). 전체 소요 시간, 동기 베이크 소요 시간, 베이크를 뺀 소요 시간, 눈 조정과 다시 베이크 횟수를 기록합니다. `PSD2LIVE_SAMPLE`은 입력을 고릅니다. `PSD2LIVE_IMPORT_MODIFIER_VERSIONS=유형@버전`은 프리셋 평가, 베이크, 히스토리 초기화 전에 함께 있는 바인딩 버전을 잠급니다. `PSD2LIVE_VERIFY=1`은 조정 뒤 닫는 단계에 캐시된 완전한 IR 재구축 결과를 맞춥니다. `PSD2LIVE_VERIFY_INDEPENDENT=1`은 가져오기, 조정, 콜드 재생이 이전 템플릿을 요청하거나 이전 바인딩을 구축하지 않았는지 검사합니다. `PSD2LIVE_BASELINE_MODIFIER=유형@버전`은 조정된 스택과, 지정한 내장 버전을 잠근 결과의 완전한 IR을 대조하며, 여러 버전은 쉼표로 나누어 조합 효과를 비교합니다. `PSD2LIVE_JFR=1`은 샘플링을 기록합니다. 창 그리기와 네이티브 미리보기 로드는 포함하지 않습니다 | `modifier-perf/report.txt`, `profile.jfr` |
| `CommitPerfTool.profile` / `.desktop` | 저작 제출 한 번의 소요 시간입니다. `profile`은 애플리케이션 층 명령 경계를 타고 단계별로 나눕니다(개정 번호, 구성 디코드, 재구축, 지오메트리 검사). `desktop`은 데스크톱 뷰 모델과 어댑터를 타고, 메시 정점 편집과 브러시 획을 연속 제출하며, 제출 소요 시간과 인터페이스 스레드의 가장 긴 멈춤을 보고합니다. `JAVA_TOOL_OPTIONS=-XX:StartFlightRecording=...`와 함께 샘플링할 수 있습니다 | `commit-perf/report.txt`, `desktop.txt` |
| `CommitPerfTool.baseline` | 제출 경로의 단계별 기준선입니다. 자동 스켈레톤, 스윙 두 개, 베이크된 시뮬레이션 하나가 있는 프로젝트에서, 완전한 재구축(프로세스에 저장된 저작 Rig를 읽지 않고 기초 Rig를 생성한 뒤 로그를 재생)의 각 단계(분석, 텍스처 아틀라스 묶기와 PNG 인코딩, 기초 Rig, 스켈레톤 캐시 적중/미적중, 로그 재생, 스윙/시뮬레이션 다시 기록, 덮어쓰기, IR, moc3 패키징, `validateBundle`, 개정 번호 해시, 런타임 파일 기록), 그리고 지오메트리 제출, 작은 레이어 페인트, 그림 교체(같은 형태/메시 재구축), 관계없는 토폴로지 편집 뒤의 스켈레톤 캐시 적중, 로그가 50/200개까지 늘어날 때의 제출 소요 시간과 증가를 각각 측정합니다. 제출할 때마다 히스토리 저장소에 기록합니다. 네이티브 미리보기 다시 로드는 측정하지 않습니다(GL 컨텍스트 필요) | `commit-perf/baseline.json`, `baseline.md` |
| `CommitPerfTool.rigStages` | `baseline`의 준비와 생성류 제출만 합니다(레이어 분류, 레이어 메시). 제출마다 Rig 구축의 각 단계를 기록한 다음 결과를 완전히 재구축합니다. `PSD2LIVE_RIG_MODES`가 `staged` 또는 `unstaged`이면 단계 캐시의 한 모드만 재고, 기본은 둘 다 잽니다 | `commit-perf/rig-stages.json`, `rig-stages.md` |
| `SkeletonCommitTool.profile` / `.equivalence` | 스켈레톤 제출입니다. `profile`은 `PSD2LIVE_SCALE`배(기본 2)로 확대한 샘플에서 먼저 지오메트리 제출을 `PSD2LIVE_GEOMETRY_EDITS`번(기본 24) 한 다음, 명령 경계를 거쳐 본을 새로 만들고 바인딩하고 관절을 이동합니다(스킨이 가장 큰 메시의 팔다리 관절 포함). 제출 소요 시간과 Rig 구축 각 단계를 보고합니다. `equivalence`는 스켈레톤, 지오메트리, 정점 그룹 제출과 실행 취소/다시 실행/분기 전환 뒤에, 런타임 모델과 캐시를 비우고 저장된 저작 Rig를 쓰지 않는 콜드 재구축·재생을 단계적으로 비교합니다 | `skeleton-commit/report.txt`, `equivalence.txt` |
| `SkeletonBakeDiffTool` | 스켈레톤 베이크의 전후 비교입니다. 확대한 샘플(`PSD2LIVE_SCALE`, 기본 2)에서 각 스킨 메시가 각 키폼 격자와 블렌드 형태 키에서 보인 캔버스 좌표를 기록하고, 각 팔다리의 극한 포즈를 렌더합니다. `PSD2LIVE_LABEL`은 이번 결과의 이름이고, `PSD2LIVE_REFERENCE`를 두면 그 결과와 비교해 각 메시의 최대 정점 차(픽셀)와 렌더 차를 보고합니다 | `skeleton-bake/<label>.bin`, `<label>-limbs.png`, `<label>-vs-<reference>.png` |
| `OpenPerfTool.profile` | 앱의 경로로 프로젝트를 여는 소요 시간입니다(`ProjectRepository.open` 뒤 새로운 `WorkspacePreviewBuilder`가 머리를 구축). 자동 스켈레톤, 스윙 두 개, 베이크된 시뮬레이션 하나가 있는 프로젝트에서 아카이브 세 가지를 저장합니다. 앱이 지금 쓰는 방식(각 개정의 저작 Rig와 머리 캐시), 머리 캐시만(저장된 Rig가 없을 때의 생성과 재생), 둘 다 없음. 각각 빈 저작 Rig와 스켈레톤 캐시에서 3번 열고, 풀기/심기와 머리 구축을 측정하며 셋이 구축한 모델이 같은지 맞춥니다 | `open-perf/report.json`, `report.md` |
| `SavePerfTool.profile` | 현실 규모의 생성 프로젝트를 저장하는 소요 시간입니다(`PSD2LIVE_SAVE_LAYERS`, `PSD2LIVE_SAVE_SIZE`, `PSD2LIVE_SAVE_REVISIONS`). 같은 캡처를 3번 저장하고, 다시 연 뒤 2번 저장하고, 한 번 더 엽니다 | `save-perf/report.txt` |
| `ExportTargetsScenario`, `GenerateAndRigScenario`, `PaintAndReplaceScenario`, `SplitThenRegenerateScenario`, `Cmo3ImportScenario`, `MotionScenario` | 합성 캐릭터 위의 긴 엔드투엔드 장면입니다. 테스트 스위트의 장면(`scenario/`)과 같이 콜드 재생, 실행 취소, 품질을 단계적으로 검사합니다. 각 내보내기 대상으로 내보내기, 가져오기부터 내보내기까지의 첫 세션, 스켈레톤 아래의 페인트와 그림 교체, 나눈 뒤 다시 생성, cmo3를 가져와 편집한 뒤 cmo3로 다시 내보내기, 내장 모션 편집과 내보내기입니다. 파이프라인 전체가 너무 오래 걸려 테스트 스위트에 넣지 않습니다. 생성, 내보내기, 저장을 바꾼 뒤에 실행합니다 | 테스트 결과만 |
| `RigSourcePerfTool.profile` | 큰 그림(예제를 `PSD2LIVE_SCALE`배, 기본 3)에 생성 입력이 있을 때 재구축에서 픽셀 단위로 잰 단계입니다. 3번째 레이어마다 몇 픽셀을 작게 자르고(고정 사각형에 맞춰야 함), 5번째 레이어마다 2배 밀도입니다. 콜드 구축, 핫 재구축, 그린 뒤 재구축의 단계별 소요 시간, 콜드 합성 아틀라스 페이지와 미리보기 PNG 인코딩 | `rig-source-perf/report.txt` |
| `MeshTraceTool.compare` / `.wrap` | `compare`는 합성 레이어(1024² 텍스처 위의 속눈썹이 있는 눈, 가는 머리카락 묶음, 캔버스 해상도 레이어, 6000과 2048 문서의 레이어)를 각각 캔버스 윤곽 추적과 텍스처 윤곽 추적으로 메시를 만들고 나란히 렌더합니다(회색은 텍스처 알파, 파랑은 메시, 빨강은 메시 밖의 불투명 텍스처 픽셀). 정점 수, 소요 시간, `detail`, 메시 밖 픽셀, 메시 면적을 보고합니다. `wrap`는 텍스처 윤곽 추적으로 감싸기 0–32에서 속눈썹, 가는 머리카락 묶음, 손의 메시를 만들고, 정점 수, 윤곽 고리 수, 소요 시간, 메시 밖 픽셀을 보고합니다 | `mesh-trace/<사례>.png`, `report.txt`. `mesh-wrap/<사례>.png`, `report.txt` |
| `StructureMeshTool` | 텍스처 구조에 따라 점을 다시 배치합니다. 예제 PSD(`PSD2LIVE_STRUCTURE_PSD`, 기본 tml)의 옷과 머리카락 레이어(또는 `PSD2LIVE_STRUCTURE_LAYERS`가 나열한 레이어)에서 `layer_mesh_structure` 계획을 실행하고, 이전 메시와 새 메시, 인식한 선(주름 주황, 그림자 파랑, 무늬 보라, 채택하지 않은 선은 옅게)을 나란히 그립니다. 정점 수, 채택한 선, 주름 띠 수, 소요 시간을 보고합니다. `PSD2LIVE_STRUCTURE_SETTINGS`로 설정 JSON을 넘깁니다 | `structure-mesh/<메시>.png`, `report.txt` |
| `ReadmeShotsTool` | 실제 편집기 창을 열고, 시작 페이지에서 「전체」 프리셋을 고른 뒤 `PSD2LIVE_SAMPLE`을 가져오고, 「모델 전체의 독립 메시 검사…」로 검사하고 나누기를 확인한 다음, 시뮬레이션 베이크가 끝나기를 기다리고, 자동 스켈레톤을 제출하고, 각 작업 공간과 캔버스 각 모드의 창 프레임을 찍습니다(화면을 찍지 않음). 프로젝트를 `project.psd2live`로 저장합니다. `PSD2LIVE_SHOT_REUSE=1`은 저장된 프로젝트를 다시 열고, `PSD2LIVE_SHOT_W` / `_H`는 창 크기를 정합니다. 첫 페이지 스크린샷용입니다 | `readme-shots/<예제>/*.png`, `project.psd2live` |
| `ReadmeShowcaseTool` | 첫 페이지 테스트 그림입니다. `meshStress`, `meshParams`, `meshWrap`는 합성 레이어의 메시(구멍과 가는 다리, 가장자리 열 수 × 채우기 알고리즘, 외곽 너비)입니다. `rigHead`, `rigFace`, `rigBody`는 예제 두 개의 머리, 이목구비, 스켈레톤 프리셋 포즈입니다. `rigKnee`는 `PSD2LIVE_KNEE_SAMPLE`(프로젝트 또는 PSD)의 무릎 굽힘입니다. `bodyOrbit`는 `PSD2LIVE_ORBIT_PROJECTS`의 각 프로젝트에 몸 X / Y의 상하, 좌우, 타원 운동 구간을 잠시 더하고, 물리를 켠 것과 끈 것으로 PNG 프레임을 각각 내보냅니다(`PSD2LIVE_ORBIT`은 폭, `PSD2LIVE_ORBIT_PERIOD`는 한 바퀴의 초) | `readme-plates/*.png`, `readme-orbit/<프로젝트>-{on,off}/` |

`modifier legacy template` 행은 호환 템플릿의 구축 횟수를 셉니다. `modifier template consumer` 행은 같은 구축의 실제 호출자를 기록하며, 앞선 모디파이어 템플릿에 대한 요청이 포함됩니다. 총 소요 시간을 셀 때는 그중 한 묶음만 계산합니다.

| 환경 변수 | 작용 |
| --- | --- |
| `PSD2LIVE_SAMPLE` | 예제 이름(`tml`, `ds`) 또는 PSD 경로. 기본 `tml`. `CommitPerfTool.desktop`은 `.psd2live` 프로젝트도 받습니다 |
| `PSD2LIVE_CMO3` | `ModelProfileTool.cmo3`의 입력: `.cmo3` 파일 또는 그 디렉터리 |
| `PSD2LIVE_PROBES` | 운동 프로필 탐색의 파라미터, `id=값,...`. 기본은 몸 X, Y, Z의 끝점 |
| `PSD2LIVE_SHEET_PARAM` | 실루엣을 이 파라미터를 따라 펼칩니다. 몸 X × 몸 Y를 대신합니다 |
| `PSD2LIVE_BONES` | `MotionSheetTool.body`에서 자동 스켈레톤의 본 위치를 고칩니다. `id=머리x,머리y,꼬리x,꼬리y;...`(캔버스 픽셀) |
| `PSD2LIVE_BIND_LEGS` | `1`로 두면 다리와 신발 메시를 첫 허벅지 본에 바인딩합니다 |
| `PSD2LIVE_ZOOM` | 다리 클로즈업 범위, `左,上,右,下`. 캔버스 비율 |
| `PSD2LIVE_VERBOSE` | `1`로 두면 `motions`가 곡선마다 추가로 출력합니다 |
| `PSD2LIVE_BAKE_CONFIGS` | `SimBakeSuite`의 설정, `모드 수:키 수,...`. 기본 `preset`(각 파츠 자신의 설정) |
| `PSD2LIVE_SIM_PARTS` | `SimBakeSuite` / `SimRootDofExperiment`의 파츠. 쉼표로 구분, 기본은 전부 |
| `PSD2LIVE_BAKE_METHODS` | `SimBakeSuite`의 베이크 방법, `legacy,dof` |
| `PSD2LIVE_SIM_INPUTS` | `SimBakeSuite`가 이 입력만 남깁니다 |
| `PSD2LIVE_SIM_CSV` | `SimBakeSuite`가 이 모션의 끝 궤적, 모드 값, 입력을 CSV로 씁니다 |
| `PSD2LIVE_RUNTIME_PROJECT` | `RuntimeConformanceTool.generate`가 추가로 쓰는 프로젝트(`.psd2live` 또는 PSD). 사례 이름은 `project` |
| `PSD2LIVE_GEOMETRY_EDITS` | `SkeletonCommitTool.profile`이 스켈레톤 제출 전에 하는 지오메트리 제출 횟수. 기본 24 |
| `PSD2LIVE_RIG_MODES` | `CommitPerfTool.baseline` / `.rigStages`의 생성류 제출이 `staged` 또는 `unstaged`만 잽니다. 기본 `both` |
| `PSD2LIVE_TEST_CUBISM_CORE` | 공식 Cubism Core 동적 라이브러리 경로. `CubismCoreCheckTool`과 `:umamo`의 `MocDefaultColorsTest`용입니다. 설정하지 않으면 둘 다 건너뜁니다 |

### PSD 가져오기부터 모디파이어 전체 생성까지

데스크톱 시작 페이지는 `WorkspaceHost.importPsd`로 선택한 파일을 캡처합니다. `WorkspaceSourceImporter`가 PSD를 디코드하고, 레이어와 소재의 연결을 만들며, 빈 모디파이어 스택의 편집 가능한 메시 미리보기를 생성하고, 초기 아틀라스를 고정한 뒤 프로젝트를 설치합니다. 레이어 나누기와 이름 붙이기가 끝나면 `ImportPresetContinuation`이 같은 프로젝트에 `modifier_initialize`를 한 번만 제출합니다. 빈 스택을 고르면 절차는 가져오기에서 멈춥니다. MCP의 `asset_import_psd`도 한 번의 가져오기에서 프리셋을 지정할 수 있으며, 이 경로는 프로젝트를 설치하기 전에 아래 표의 각 단계를 마칩니다.

| 단계 | 실행 위치 | 하류가 쓰는 결과 |
| --- | --- | --- |
| 기초 입력 준비 | `WorkspacePresetInitialization` → `RigModifierPipeline` → `buildOrdinaryPreview` | 원본 그림을 분석하고, 아틀라스와 일반 메시를 만들며, 기존 편집을 재생하고, 모디파이어의 입력 IR을 컴파일합니다. |
| 계획과 바인딩 | `WorkspaceModifierPlan` → `RigModifierEvaluator` | 실제 메시에서 대상을 고르고, 의존에 따라 인스턴스를 만듭니다. 각 인스턴스는 이전 인스턴스의 출력을 읽습니다. 바인딩 결과가 쓸 수 있는 파라미터와 이미 구동된 물리 출력을 정합니다. |
| 물리, 모션, 시뮬레이션 | `WorkspacePresetInitialization` → `WorkspaceModifierSimulation.prepare` | 이미 생성된 파라미터에 따라 물리와 모션을 더합니다. 각 시뮬레이션은 자기 이전의 스택을 읽고, 대상, 충돌 메시, Glue로 연결된 메시, 조상 디포머를 추적하며, 베이크 결과를 문서에 기록합니다. |
| 최종 평가와 제출 | 직접 가져오기는 `WorkspaceSourceImporter`가 설치합니다. 미룬 초기화는 `WorkspacePreviewBuilder`가 후보를 재구축한 다음 `WorkspaceRuntime`을 거쳐 제출합니다 | 후보는 입력이 맞는 평가 접두사를 다시 쓰고, 최종 미리보기 패키지를 생성합니다. 미룬 초기화의 모디파이어 스택 전체는 히스토리 노드 하나만 추가합니다. |

CLI가 원본 PSD에서 바로 내보낼 때 `buildSourceExport`가 생성 분기를 고릅니다. 디포머를 켠 완전한 생성은 `CliPresetBindings.build`를 거치며, 위의 프리셋 계획과 순서 있는 평가를 공유한 다음 CLI 구성에 맞춰 내보내기 모델을 정리합니다. 메시만과 디포머 끄기는 각각 전용 분기가 있습니다.

`ModifierPerfTool.profile`로 권장 프리셋의 직접 가져오기를 재고, `ModifierPerfTool.deferredImport`로 빈 스택을 가져온 뒤 완전한 프리셋을 실행하는 명령 경로를 잽니다. `PSD2LIVE_IMPORT_ROUNDS`는 같은 JVM 안에서 새 프로젝트를 만드는 횟수를 조절하며 기본은 2입니다. 보고는 회차마다 소요 시간, GC 시간, 시뮬레이션 보정, 학습, 동적 맞춤 등 각 단계 소요 시간을 나열합니다. 하위 단계 소요 시간은 이미 소속된 상위 단계에 들어 있습니다. 미룬 초기화 기준선은 미리보기 패키지 파일과 시뮬레이션 베이크 데이터의 SHA-256도 `build/tools/modifier-perf/deferred.txt`에 기록합니다. `PSD2LIVE_VERIFY=1`을 설정하면 마지막으로 생성한 프로젝트를 저장하고 다시 열어, 완전한 IR, 모디파이어 보고, 정지와 고개를 돌려 입을 벌린 포즈의 화면을 맞추고 MOC3를 되읽습니다. 시간 측정은 애플리케이션 층 명령만 다루며, 검증, 창 그리기, 사람이 레이어를 나누는 시간은 모두 측정 범위 밖입니다.

## 코드 구조

```text
psd2live/
├─ src/                               # 产品层（根 Gradle 项目）
│  ├─ main/kotlin/io/github/psd2live/
│  │  ├─ core/                        # 生成流水线、质量检查与可重放编辑
│  │  ├─ document/                    # 源图、图层变换与纹理值类型
│  │  ├─ project/                     # WorkspaceDocument、WorkspaceStore、.psd2live 归档
│  │  ├─ application/                 # WorkspaceRuntime、宿主、命令注册与 schema
│  │  ├─ history/                     # 分支式撤销 / 重做
│  │  ├─ agent/                       # 本地 MCP 服务与工具定义
│  │  ├─ render/                      # 编辑画布与 p2lrt GPU 渲染
│  │  ├─ ui/                          # Compose Desktop 界面、ViewModel、主题与交互教程
│  │  ├─ i18n/                        # 多语言加载
│  │  └─ util/                        # 通用工具
│  ├─ main/resources/                 # i18n 文案、whatsnew、manual、皮肤预设等资源
│  └─ test/kotlin/io/github/psd2live/ # 回归测试与开发工具（tools/）
├─ umamo/                             # 通用引擎（GPL）：runtime、format、interop、render、edit
├─ umamo-ir-bridge/                   # GPL 转换桥（PuppetIr）
├─ format-model/                      # 中立绑定 IR（MIT）
├─ format-compile/                    # 导出编译框架与损失报告（MIT）
├─ format-eval/                       # 运行时求值绑定（MIT）
├─ algorithm-api/                     # 独立 JVM 算法契约与清单
├─ algorithm-worker/                  # 外部算法进程宿主机
├─ algorithms/                        # 内置算法模块（binding / motion / physics / simulation / swing）
├─ targets/                           # 导出目标（cubism / raster / psd / spine / runtime / web / gltf / unity / dragonbones）
├─ plugin-sdk/                        # 插件 SDK（Python / Rust）与打包脚本
├─ plugins/                           # 内置 / 示例原生插件（native-core）
├─ runtime/                           # Rust 运行时（p2lrt）、Web / Godot 绑定与一致性测试
├─ native/                            # 可选 Cubism Native 预览桥接（C++ / CMake）
├─ packaging/                         # Windows / macOS 安装包与启动器脚本
├─ tools/                             # mcp_proxy.py、示例拉取/校验与目标检查脚本
├─ examples/                          # 示例说明与多语言插件示例
├─ docs/                              # 中文主文档与图片资源
├─ licenses/                          # 第三方许可证文本
├─ build.gradle.kts                   # Gradle 构建与打包配置
├─ settings.gradle.kts                # 22 个 Gradle 子模块定义
└─ run-gui.bat                        # Windows 快捷启动脚本
```

소스는 여러 Gradle 모듈로 나뉘고, 모듈 사이 의존은 Gradle이 컴파일 때 제약합니다. Umamo에서 옮긴 엔진 `org.umamo.*`는 `:umamo`(`umamo/src/main/kotlin/`)에 있으며 제품 코드에 의존하지 않습니다. 중립 리그 IR `:format-model`, 내보내기 프레임워크 `:format-compile`, 래스터 내보내기 `:targets:raster`, 런타임 모델 `:targets:runtime`, 런타임 바인딩 `:format-eval`은 MIT이며 어떤 GPL 모듈에도 의존하지 않습니다. GPL 모듈 `:umamo-ir-bridge`는 `:umamo`와 `:format-model`에만 의존하고, 범용 변환기 `io.github.psd2live.bridge.PuppetIr`를 가집니다. 알고리즘과 내보내기 대상이 이것을 공유하며, 대상의 낮추기, 컴파일, 기록 구현을 거치지 않습니다. `:targets:cubism`은 Cubism 대상의 낮추기, 컴파일, 기록을 남기고, `:targets:psd`는 엔진을 씁니다. 자세한 내용은 [중립 리그 IR과 내보내기 대상](../spec/EXPORT_TARGETS.md)을 참고하세요.

제품 층 `io.github.psd2live.*`는 루트 프로젝트(`src/main/kotlin/`)에 있습니다. 애플리케이션 층 `application/SourceExport.kt`는 소스 파일 내보내기 사례와 CLI 프리셋을 고르고, `PSD2LivePipeline.run` 확장을 제공합니다. `core/PSD2LivePipeline.kt`는 생성과 내보내기 계산을 남깁니다. 원본 그림, 레이어 변환, 텍스처 값 유형은 루트 프로젝트의 `document` 패키지에 속하며, 독립 Gradle 모듈이 아닙니다. `project`의 호환 별칭은 이전 패키지 이름을 남깁니다.

제품 패키지 의존은 `LayerDependencyTest`가 검사합니다. `core/project/history/document/util`은 `application/agent/ui`를 역으로 호출하지 않고, `document/util/history`는 `core/project`에 의존하지 않습니다. 기존의 `core/project/render` 강연결 성분은 여전히 있으며, 그 패키지를 넘는 심벌 예외는 `src/test/resources/architecture/product-layer-exceptions.txt`에 나열됩니다. 순환을 새로 더하거나, 예외를 넓히거나, 패키지를 넘는 와일드카드 임포트로 검사를 우회하면 안 됩니다.

엔진 패키지는 다음과 같습니다.

| 패키지 | 역할 |
| --- | --- |
| `org.umamo.runtime` | `PuppetModel`, 키폼 보간과 평가 |
| `org.umamo.format` | PSD, CMO3, MOC3 및 이미지 형식의 읽기와 쓰기 |
| `org.umamo.interop` | `PuppetModel`과 CMO3 / MOC3의 상호 변환 |
| `org.umamo.render` | LWJGL / OpenGL 미리보기 |
| `org.umamo.edit` | 모델에 대한 불변 편집 원시 연산 |
| `io.github.psd2live.core` | 생성 파이프라인(`PSD2LivePipeline`, `LayerClassifier`, `AdaptiveMeshGenerator`, `RigBuilder`, `MotionGenerator`, `PhysicsGenerator`)과 각종 재생 가능한 편집 |
| `io.github.psd2live.project` | 중립 작업 공간 문서(`WorkspaceDocument`), 히스토리 저장소(`WorkspaceStore`), `.psd2live` 아카이브. `application`, Compose, MCP에 의존하지 않습니다 |
| `io.github.psd2live.application` | 중립 런타임(`WorkspaceRuntime`), 명령 등록과 schema, 미리보기 세션, 프로세스 안 백그라운드 작업. 인터페이스와 MCP가 공유합니다 |
| `io.github.psd2live.history` | 분기형 실행 취소 / 다시 실행 |
| `io.github.psd2live.agent` | 로컬 MCP 서비스와 공개 도구 정의 |
| `io.github.psd2live.render` | 편집 캔버스와 p2lrt 미리보기의 GPU 렌더(`CanvasGpu`, `GlCanvasRenderer`, `P2lrtGlRenderer`, `SkiaGpu`) |
| `io.github.psd2live.ui` | Compose 인터페이스: `state`(ViewModel, 단축키 레지스트리), `views`(작업 공간과 패널), `components`(대화 상자와 컨트롤), `tutorial`(대화형 튜토리얼). 캔버스 편집과 페인트는 `ui` 루트 패키지에 있습니다 |
| `io.github.psd2live.i18n` | 인터페이스 문안. 리소스는 `src/main/resources/i18n/`에 있습니다 |

생성과 내보내기 로직은 `core` / `project` / `application`에 두고, Compose 인터페이스 코드에 쓰지 않습니다.

시작, 도움말, 설정은 앱 페이지(`ui/state/AppPages.kt`의 `AppPage`)이며 프로젝트에 들어가지 않습니다. 각 페이지는 편집기의 도킹 프레임(`DockWorkspaceView`의 `DockSession`과 `DockTree`)을 다시 쓰고, 페이지마다 디렉터리 패널과 내용 패널입니다(시작 페이지에는 세부 패널도 있음). 패널 ID에는 페이지 접두사가 붙습니다(`start.*`, `help.*`, `settings.*`). `PageChrome.kt`의 디렉터리 행과 본문 컨테이너를 공유하고, 기본 배치는 `pageDockLayout`을 보며, 배치와 닫힌 패널은 이 컴퓨터의 환경설정(`AppSettings.pageArrangement`)에 있습니다. 패널은 드래그되고, 떠 있고, 다시 도킹됩니다. 서로 공유하는 상태는 ViewModel이나 `StartPageModel`에 두고, 패널 하나의 조합 안에 두지 않습니다. 기능 업데이트 글은 `src/main/resources/whatsnew/<언어>.md`에 있으며, 네 언어의 버전과 항목이 서로 대응해야 합니다(`WhatsNewTest`). 버전 번호는 빌드가 `psd2live/version.properties`에 기록하고, 코드는 `AppVersion`을 읽습니다.

## 핵심 제약: 재구축과 재생

`PuppetModel`은 지속 상태가 아닙니다. 프로젝트가 저장하는 것은 원본 그림, 레이어 분류와 설정, 그리고 직렬화할 수 있는 편집 기록(`RigEditOverlay`)입니다. 프로젝트를 열거나 수정이 일어날 때마다 다음을 합니다.

1. `RigBuilder`가 원본 그림에서 기초 Rig를 다시 생성합니다.
2. 저작 단계(`RigEditOverlay.replayAuthored`): 로그에서 마지막 `rig_checkpoint` 기록이 저장한 Rig에서 시작합니다. 없으면 기초 Rig에서 시작하고, 먼저 이전 형식의 정적 필드(파라미터 삭제 / 생성, Warp, 구조, 키폼)를 재생한 다음, 그 뒤의 `authoringJournal`을 실제 순서대로 재생합니다.
3. 마무리(`RigEditOverlay.finish`): 생성기 의존 그래프(`core/DocumentGenerators.kt`)에 따라 스윙, 시뮬레이션 등의 생성기를 실행하고, 생성 결과 덮어쓰기(`generated_override`)를 3자 병합한 다음, 생성 파라미터를 참조하는 패널 편집을 재생합니다.

저작 Rig가 이미 저장된 개정(이 프로세스가 구축했거나, 아카이브 `rig/`에 저장된 것)은 그것에서 바로 구축하며, 기초 Rig를 생성하지도 로그를 재생하지도 않습니다. 자세한 내용은 [문서 레이어](../spec/DOCUMENT_LAYER.md)를 참고하세요.

그래서 편집 기능을 새로 넣을 때는 다음을 지킵니다.

- 수정은 `RigEditOverlay`의 직렬화 가능한 필드에 기록해야 합니다(새 명령은 편집 로그의 JSON 명령을 우선). 작업 공간 상태 인코드·디코드에서 저장하고 복구해야 합니다. `PuppetModel`만 바꾸는 수정은 재구축 뒤에 사라집니다.
- 인터페이스와 MCP는 같은 편집 명령을 호출해야 합니다. `org.umamo.edit`의 하위 메서드가 자동으로 공개 인터페이스와 같지는 않습니다.
- 검수 경로: 도메인 데이터 → 히스토리 재생 → 프로젝트 저장과 복구 → 대상 Cubism 버전 처리 → 내보내기 되읽기 → 시각 확인.

인터페이스 문안을 추가할 때는 `Messages.properties`, `Messages_zh_CN.properties`, `Messages_ja.properties`, `Messages_ko.properties` 네 파일에 동시에 추가하고 키 수를 같게 유지합니다(`CatalogContractsTest`가 검사).

## 내보내기 결과 확인

플러그인 Git 배포 검수에는 이 컴퓨터에 Git과 `git daemon`이 설치되어 있어야 합니다. `PSD2LIVE_PLUGIN_GIT_TEST=1`을 설정한 뒤 `test --tests '*ModifierPluginTest.gitTagsAndCommitsInstallPrebuiltPinnedPackages'`를 실행합니다. 임시 디렉터리에 이미 빌드한 테스트 패키지를 만들고 이 컴퓨터만 수신하며, 태그, 커밋 번호, 기본 분기, 버전 공존, 실제 평가를 검사합니다. 외부 저장소에는 접근하지 않습니다. 일반 회귀는 이 항목을 건너뜁니다.

- `.model3.json`이 참조하는 모든 파일은 모델과 함께 전달해야 합니다.
- 로그 패널과 진단 JSON의 경고를 확인합니다.
- 이어서 편집해야 하면 `.psd2live` 프로젝트를 저장합니다. `.psd2live.json`은 보고일 뿐입니다.

관련: [Cubism Native 미리보기](CUBISM_SDK_SETUP.md) · [CI와 릴리스](CUBISM_CI_RELEASE.md) · [텍스처 고해상도화](TEXTURE_UPSCALE.md) · [`build.gradle.kts`](../../../build.gradle.kts)
