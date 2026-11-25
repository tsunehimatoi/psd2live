<!-- lang: zh en ko ja -->
> [中文](../../zh/guide/CUBISM_SDK_SETUP.md) · [English](../../en/guide/CUBISM_SDK_SETUP.md) · **한국어** · [日本語](../../ja/guide/CUBISM_SDK_SETUP.md)

# 선택적 Cubism Native 미리보기

[문서 목록](../README.md) · [CI와 배포](CUBISM_CI_RELEASE.md)

내장 렌더와 기본 내보내기에는 공식 SDK가 필요 없습니다. 이 페이지는 이 저장소의 네이티브 미리보기 브리지만 설정하며, 공식 런타임의 렌더와 물리를 확인할 때 씁니다. 편집기의 모든 기능이나 모든 픽셀과 일치한다고 약속하지 않습니다.

## 준비

### Windows x64
- CMake 3.16+
- Visual Studio 2022 C++ / MSVC 143
- 로컬 Cubism 5 SDK for Native(5-r.5 디렉터리 배치)
- 필요: `Core/lib/windows/x86_64/143/Live2DCubismCore_MT.lib`

### Linux x86_64 / arm64
- 지원 플랫폼: X11/GLX가 있는 Linux x86_64와 arm64. XWayland와 `xvfb-run`을 포함합니다. arm64는 SDK의 실험적 Cubism Core를 사용합니다
- 지원하지 않음: XWayland가 없는 순수 Wayland, 또는 musl 기반 Alpine 등의 시스템
- CMake 3.16+
- GCC 또는 Clang, C++14 지원
- OpenGL / GLX 개발 패키지. 예: Debian/Ubuntu는 `libgl1-mesa-dev`와 `libglx-dev`(또는 `libglx-mesa-dev`). Fedora는 `mesa-libGL-devel`
- X11 개발 라이브러리(`libx11-dev` / `libX11-devel`)
- 런타임에는 사용 가능한 X11 `DISPLAY`가 필요합니다(데스크톱 세션, 또는 그래픽 환경이 없으면 `xvfb-run`)
- 로컬 Cubism 5 SDK for Native(5-r.5 디렉터리 배치)
- 필요: `Core/lib/linux/x86_64/libLive2DCubismCore.a`(arm64는 `Core/lib/experimental/linux/arm64/libLive2DCubismCore.a`)

## 빌드와 배포

### Windows

```powershell
$env:CUBISM_SDK_ROOT = "D:\path\to\CubismSdkForNative-5-r.5"
.\native\build_live2d_renderer.bat -Clean -Deploy
```

저장소 루트에서 실행합니다. `-Deploy`는 DLL과 셰이더를 아래 gitignore된 디렉터리로 복사하고, `-Clean`은 이전 빌드 캐시를 지운 뒤 다시 빌드합니다.

```text
src/main/resources/cubism/windows-x86_64/
├── live2d_renderer.dll
└── FrameworkShaders/
```

### Linux

```bash
export CUBISM_SDK_ROOT=/path/to/CubismSdkForNative-5-r.5
./native/build_live2d_renderer.sh --clean --deploy
```

저장소 루트에서 실행합니다. `--deploy`는 공유 라이브러리와 셰이더를 아래 gitignore된 디렉터리로 복사하고, `--clean`은 이전 빌드 캐시를 지운 뒤 다시 빌드합니다.

```text
src/main/resources/cubism/linux-x86_64/   （arm64 为 linux-arm64/）
├── liblive2d_renderer.so
└── FrameworkShaders/
```

## 확인

애플리케이션을 시작하고 모델을 연 뒤, 미리보기의 렌더러 상태와 로그를 확인합니다. 네이티브 리소스가 없거나 불러올 수 없으면 미리보기는 PSD2Live 런타임(p2lrt)으로 바뀌고, 로그에 Cubism을 쓸 수 없는 이유가 남습니다. 런타임 라이브러리도 쓸 수 없을 때만 편집 캔버스의 그리기로 돌아갑니다. 구체적인 오류를 확인하세요. 화면에 무언가 나온다는 것만으로 SDK가 켜졌다고 판단할 수 없습니다.

## 다른 경로와 장애

저장소의 `cubism/windows-x86_64/`(또는 `cubism/linux-x86_64/`)에 두거나, `CUBISM_SDK_PATH`, `LIVE2D_SDK_PATH`, JVM 속성 `psd2live.cubism.path`로 배포 디렉터리를 가리킬 수도 있습니다. 이것들은 런타임 경로입니다. 빌드에 쓰는 `CUBISM_SDK_ROOT`는 SDK 소스 루트입니다.

네이티브 라이브러리와 FrameworkShaders는 짝이 맞아야 합니다.

### Windows
VCRUNTIME / MSVCP가 없으면 /MD 빌드를 잘못 썼는지 확인합니다. 현재 스크립트는 /MT와 정적 Core를 사용합니다. `dumpbin /DEPENDENTS`로 확인할 수 있습니다.

### Linux
`ldd liblive2d_renderer.so`로 의존성을 확인합니다. 예상 시스템 라이브러리: `libGL.so`, `libGLX.so`(또는 Mesa GLX), `libX11.so`, `libpthread.so`, `libdl.so`.

오프스크린 미리보기는 GLX 컨텍스트를 만들므로 유효한 X11 `DISPLAY`가 필요합니다. 그래픽 환경이 없으면 Xvfb를 설치합니다. 예: `xvfb-run -a ./gradlew run`(또는 `xvfb-run -a java -jar …`). `DISPLAY`가 없으면 네이티브 초기화가 실패하고, 미리보기는 PSD2Live 런타임으로 바뀝니다(런타임 라이브러리도 쓸 수 없을 때만 편집 캔버스의 그리기로 돌아갑니다).

환경 변수를 바꾼 뒤에는 애플리케이션을 띄운 프로세스를 다시 시작합니다.

저장소는 오픈 소스 브리지 코드만 제공하며, 공식 Core, Framework, 셰이더는 포함하지 않습니다. SDK는 직접 받아 그 라이선스를 지키세요. 이 페이지는 라이선스 허가가 아닙니다.

자동 빌드와 Cubism이 포함된 배포 절차는 [CUBISM_CI_RELEASE](CUBISM_CI_RELEASE.md)를 참조합니다(공개 저장소에서는 SDK zip을 공개 Release에 첨부하지 마세요).

[네이티브 빌드](../../../native/live2d_renderer/README.md) · [빌드 스크립트](../../../native/) · [서드파티 고지](../../../THIRD_PARTY_NOTICES.md)
