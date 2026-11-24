<!-- lang: zh en ko ja -->
> [中文](../../zh/guide/CUBISM_CI_RELEASE.md) · [English](../../en/guide/CUBISM_CI_RELEASE.md) · **한국어** · [日本語](../../ja/guide/CUBISM_CI_RELEASE.md)

# Cubism CI와 배포 워크플로

[문서 목록](../README.md) · [Cubism Native 미리보기](CUBISM_SDK_SETUP.md)

이 페이지는 저장소의 GitHub Actions 워크플로 두 가지를 설명합니다. 공개 테스트(SDK 없음)와 선택적인 Cubism 미리보기 패키징입니다. Live2D 라이선스를 대신하지 않으며, 독점 구성 요소의 재배포를 허가하지도 않습니다.

## 두 워크플로

| 워크플로 | 파일 | 역할 |
| --- | --- | --- |
| **CI** | `.github/workflows/ci.yml` | `push` / `pull_request`가 `master`(및 `main`)로 갈 때 Ubuntu와 Windows의 x64, arm64에서 `./gradlew test`를 실행합니다. Cubism SDK를 받지 않고 `PSD2LIVE_INCLUDE_CUBISM`을 설정하지 않습니다. |
| **Release Cubism** | `.github/workflows/release-cubism.yml` | 수동 `workflow_dispatch` 또는 `v*` 태그를 푸시할 때 SDK를 비공개로 받아 네이티브 브리지를 컴파일하고, Cubism이 포함된 Windows/Linux 설치 패키지를 만듭니다(Linux는 x64와 arm64). Cubism이 없는 Windows ARM64 패키지도 만들고, GitHub Release를 만들 수 있습니다. |

macOS 패키징은 보류이며, 이 워크플로는 빌드하지 않습니다.

## 공개 저장소와 SDK 유출(필수)

**공개 저장소에서 GitHub Release의 첨부 파일은 기본적으로 모든 사람에게 보입니다.** `CubismSdkForNative-*.zip`을 이 공개 애플리케이션 저장소의 Release에 걸지 마세요.

권장 방법:

1. **비공개 형제 저장소**에 SDK zip을 두고, prerelease / 태그를 답니다(예: `internal-sdk/cubism-5-r.5`). 첨부 파일 이름의 기본은 `CubismSdkForNative-5-r.5.zip`입니다.
2. 이 저장소에 변수 `CUBISM_SDK_SOURCE_REPO`를 그 비공개 저장소(`owner/name`)로 설정합니다.
3. 비밀 `SDK_FETCH_TOKEN`을 설정합니다. 그 비공개 저장소의 Release를 읽을 수 있는 PAT(또는 fine-grained token)입니다. 설정하지 않으면 `GITHUB_TOKEN`으로 돌아가며, 보통 다른 비공개 저장소는 **읽을 수 없습니다**.

대안: zip을 다른 비공개 저장소에 두고 워크플로의 받기 로직을 따로 바꿉니다. 현재 구현은 `gh release download`를 기준으로 합니다.

## 변수와 비밀

| 이름 | 유형 | 기본 | 설명 |
| --- | --- | --- | --- |
| `CUBISM_SDK_SOURCE_REPO` | Repository variable | 현재 저장소 `github.repository` | SDK Release가 있는 저장소. **공개 애플리케이션 저장소는 반드시 비공개 저장소로 바꿉니다.** |
| `CUBISM_SDK_RELEASE_TAG` | Repository variable | `internal-sdk/cubism-5-r.5` | 비공개 SDK Release / 태그 이름 |
| `CUBISM_SDK_ASSET_NAME` | Repository variable | `CubismSdkForNative-5-r.5.zip` | 첨부 파일 이름 |
| `SDK_FETCH_TOKEN` | Secret | (비어 있으면 `GITHUB_TOKEN`을 사용) | 비공개 SDK 저장소를 받을 때 사용합니다 |

## Environment 보호

작업 `build-windows`, `build-linux`, `release`는 GitHub Environment 이름 **`release-cubism`**을 사용합니다. 저장소 Settings → Environments에서 이 환경을 만들고, 필수 검토자 / 배포 브랜치 제한을 걸어 아무 협력자나 SDK가 포함된 패키지를 바로 만들지 못하게 합니다.

## 버전 하나 배포

전제: 비공개 SDK 저장소에 zip을 올려 두었고, 이 저장소에 위 표의 변수, 비밀, `release-cubism` 환경을 설정했습니다. 아래 `<version>`은 대상 버전 번호입니다. 예: `3.8.0`.

1. **먼저 버전 번호를 올립니다.** 워크플로는 저장소 내용을 수정하지 않습니다. `build.gradle.kts`의 `version`을 바꿉니다. `packageVersion`과 리소스의 `psd2live/version.properties`는 모두 이 값에서 만들어집니다. 명령줄 도움말의 버전도 바꿉니다(네 개의 `Messages*.properties`에 있는 `cli.usage`). 네 개의 `src/main/resources/whatsnew/<언어>.md`에서 첫 버전도 바꿉니다. `VersionConsistencyTest`가 CI에서 이 위치들과 빌드 버전이 같은지 확인합니다. `docs/zh/CHANGELOG.md`, `docs/en/CHANGELOG.md`, `docs/ja/CHANGELOG.md`, `docs/ko/CHANGELOG.md`에 이 버전 항목을 추가하고, 이 버전 설명을 `.github/release-notes.md`에 씁니다(중국어를 앞에 두고, 영어는 접힌 `<details><summary>English</summary>` 안에 둡니다. 다운로드 영역은 쓰지 않습니다. 배포할 때 `.github/scripts/release_notes.py`가 실제 첨부 파일에 따라 분류된 다운로드 배지를 만듭니다). 커밋하고 푸시합니다.
2. Actions → **Release Cubism** → **Run workflow**를 열고, `version`에 `<version>`을 넣습니다(`v` 없음).
3. 필요하면 `create_github_release`를 선택합니다(기본은 켬. 끄면 빌드 산출물만 만듭니다). 미리보기 버전은 `prerelease`를 선택해 사전 배포로 내고, 최신으로 표시하지 않습니다.
4. `release-cubism` 환경에 필수 검토자가 있으면 여기서 승인을 받습니다. 없으면 빌드가 바로 시작됩니다. Windows와 Linux 빌드가 끝나기를 기다립니다.
5. 배포를 켜면 워크플로가 태그 `v<version>`의 GitHub Release를 만들거나 업데이트합니다(기본은 정식판, `prerelease`를 선택하면 사전 배포). 첨부 파일은 다음과 같습니다.
   이름은 `PSD2Live-<version>-<시스템>-<아키텍처>[-<유형>][-ffmpeg].<확장자>`로 통일합니다. 시스템은 `windows` / `linux`, 아키텍처는 `x64` / `arm64`, Windows 유형은 `setup`(설치 패키지) / `portable`(무설치)입니다.
   - `PSD2Live-<version>-windows-x64-setup.exe`, `PSD2Live-<version>-windows-x64-portable.zip`
   - 위 두 항목에는 ffmpeg를 내장한 버전도 있습니다. `PSD2Live-<version>-windows-x64-setup-ffmpeg.exe`, `PSD2Live-<version>-windows-x64-portable-ffmpeg.zip`
   - `PSD2Live-<version>-windows-arm64-setup.exe`, `PSD2Live-<version>-windows-arm64-portable.zip`: Windows ARM64. Cubism 없음(Live2D가 이 플랫폼의 Cubism Core를 제공하지 않음). ffmpeg 버전 없음
   - `PSD2Live-<version>-linux-x64.deb`, `PSD2Live-<version>-linux-arm64.deb`(arm64는 SDK의 실험적 Cubism Core), 그리고 같은 내용의 `PSD2Live-<version>-linux-x64.tar.gz`, `PSD2Live-<version>-linux-arm64.tar.gz`(app image. Deb가 없는 배포판용)

   3.4.0 이전 버전은 이전 이름을 그대로 씁니다(예: `PSD2Live-<version>.exe`, `-windows-x86_64-portable.zip`, `-linux-amd64.deb`).

태그 `v<version>`을 직접 푸시해도 같은 워크플로가 시작됩니다. 두 진입점 중 하나만 골라, 전체 빌드 행렬이 중복 실행되지 않게 합니다.

## 산출물과 플랫폼 제한

- Linux 미리보기에는 X11/GLX가 필요합니다(XWayland / `xvfb-run` 포함). 순수 Wayland, musl/Alpine은 지원하지 않습니다. 자세한 내용은 [CUBISM_SDK_SETUP](CUBISM_SDK_SETUP.md)을 참조합니다.
- ARM64 패키지는 아직 실기 테스트를 하지 않았습니다. 첨부에 arm64 패키지가 있으면 `release_notes.py`가 배포 설명에 실기 미검증과 알려진 제한을 적습니다(Linux arm64의 Cubism Core는 실험 버전이며, OpenGL 3.1만 지원하는 GPU에서는 캔버스를 그리지 못할 수 있습니다). 실기 검증 뒤에 이 설명을 지웁니다.
- v1은 취약한 GUI 스모크를 하지 않습니다. Linux 쪽은 `.deb`가 있고 비어 있지 않은지를 기준으로 합니다.
- Windows 패키지는 두 번 만듭니다. 두 번째는 `-Ppsd2live.ffmpegDir=<디렉터리>`를 붙여, 고정 버전의 Gyan.dev ffmpeg essentials 빌드(GPLv3. 받은 뒤 SHA-256을 검증하고, 영상과 애니메이션 내보내기에 쓰는 인코더를 확인)를 그 `LICENSE.txt`, `README.txt`와 함께 애플리케이션의 `resources/ffmpeg/`에 넣습니다. ffmpeg를 올릴 때는 워크플로의 `FFMPEG_URL`과 `FFMPEG_SHA256`도 함께 바꿉니다. Linux 패키지에는 ffmpeg를 넣지 않습니다.
- Windows 설치 패키지는 `packageExe`가 Inno Setup 6으로 애플리케이션 이미지에서 빌드하며, 스크립트는 `packaging/windows/psd2live.iss`입니다. `ISCC.exe`는 `-Ppsd2live.iscc`, 환경 변수 `ISCC`, Inno Setup 6의 기본 설치 디렉터리 순으로 찾습니다. 워크플로는 Chocolatey로 Inno Setup을 설치합니다. 파일은 그 자리에서 복사하며, MSI처럼 이전 파일을 먼저 `Config.Msi`로 옮기지 않습니다. 기본은 모든 사용자 설치(관리자 필요)이며, 시작 대화 상자나 `/CURRENTUSER`로 현재 사용자만 설치할 수도 있습니다. 업그레이드할 때는 이미 설치된 디렉터리로 돌아가 당시의 설치 방식을 따르고, 이전 버전의 `app`, `runtime` 디렉터리를 지운 뒤 복사합니다. 설치 디렉터리에 사용자가 직접 둔 파일은 남습니다. ffmpeg가 있는 패키지와 없는 패키지는 같은 AppId를 쓰며 서로를 교체합니다. 설치 전에 PSD2Live가 설치 디렉터리에서 실행 중이면 닫고 다시 시도하라는 안내가 나옵니다(무인 설치는 바로 중단). 3.1.x 이전 버전은 jpackage가 만든 MSI입니다. 설치 프로그램은 그 업그레이드 코드로 찾아, 기본적으로 그 디렉터리에 설치하고, 파일을 복사하기 전에 그 설치가 기록한 설치 디렉터리를 비웁니다(3.0.0 이전은 제거할 때 그 디렉터리를 비움). 그 설치가 넣은 파일을 지운 뒤 `msiexec /x`로 무인 제거합니다. 이때 MSI는 등록 해제만 하며, 파일을 다시 `Config.Msi`로 옮길 필요가 없습니다. 제거 로그는 Setup 로그 옆에 씁니다(`PSD2Live MSI removal.log`). 현재 사용자만 설치하는데 이전 버전이 모든 사용자 설치이면 관리자 권한을 요청합니다. 제거할 때 사용자 데이터도 지울지 묻습니다(기본은 아니오. 애플리케이션의 `--clear-user-data`를, 제거 프로그램을 실행한 계정으로 실행합니다. 명령줄 제거는 `/CLEARUSERDATA=1`을 넘길 수 있습니다). 설치와 제거마다 `%TEMP%`에 로그를 씁니다(`Setup Log *.txt`).
- Cubism이 포함된 패키지는 라이선스가 허용하는 범위에서만 사용합니다. 독점 바이너리를 공개 채널에 **다시 배포하지 마세요**.

## 라이선스 알림

저장소의 브리지 코드는 GPL입니다. Live2D Cubism Core / Framework / 셰이더는 Live2D 독점 소프트웨어이므로 직접 받아 그 라이선스를 지켜야 합니다. [THIRD_PARTY_NOTICES.md](../../../THIRD_PARTY_NOTICES.md)를 참조합니다.

[로컬 SDK 설정](CUBISM_SDK_SETUP.md) · [native 스크립트](../../../native/README.md)
