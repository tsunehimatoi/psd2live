<!-- lang: zh en ko ja -->
> [中文](../README.md) · [English](../en/README.md) · **한국어** · [日本語](../ja/README.md)

# 문서 목록

[프로젝트 홈](../../README.ko.md) · [변경 이력](CHANGELOG.md) · [로드맵](ROADMAP.md) · [능력 실측](STATUS.md)

조작을 배울 때는 먼저 프로그램 안 도움말 페이지의 **튜토리얼**(`F1`)을 사용합니다. 기초가 없는 경로는 19과, Cubism 경험자 경로는 14과입니다. 아래 문서는 조작을 다시 보고 핵심 형식과 인터페이스를 찾을 때 씁니다. 과거의 설계 초안과 과정 기록은 보관했습니다(저장소 커밋 이력 또는 로컬 `local/archive/`를 참조).

## 디렉터리 구조

`en/`, `ja/`, `ko/`, `zh/`의 파일은 일대일로 대응합니다. 아래에서는 중국어만 펼칩니다. 나머지 세 언어도 같은 경로입니다.

```text
docs/
├─ README.md            # 中文文档总索引（本文件）
├─ branding/            # 应用图标资源
├─ imgs/                # README 与图文教程截图（WebP）
├─ zh/                  # 中文（源文）
│  ├─ CHANGELOG.md      # 版本更新日志
│  ├─ ROADMAP.md        # 后续规划
│  ├─ STATUS.md         # 真实任务能力实测记录
│  ├─ guide/            # 使用指南、开发与构建文档
│  ├─ spec/             # 格式规范与核心架构契约
│  └─ agent/            # MCP 接口与 Agent 验收说明
├─ en/                  # English
├─ ja/                  # 日本語
└─ ko/                  # 한국어
```

## 사용 가이드

| 문서 | 내용 |
| --- | --- |
| [변경 이력](CHANGELOG.md) | 각 버전에서 사용자가 느낄 수 있는 변화 |
| [조작 요약](guide/USER_GUIDE.md) | 프로그램 안 튜토리얼에 대응하는 문자판, 자주 쓰는 단축키, 작업 공간과 텍스처 작업 공간 |
| [캔버스 편집](guide/CANVAS_EDITOR.md) | 모드 메뉴, 변형, 메시 토폴로지, Glue, 가중치, 페인팅 세션 |
| [소재와 Layout](guide/LAYOUT.md) | 소재 라이브러리, 먼저 배치한 뒤 생성, 다시 생성, 의미 태그 |
| [앞뒤 분리 그림 튜토리얼](guide/DEPTH_SPLIT.md) | 옷깃과 목, 머리카락과 어깨 장식의 앞뒤 가림. 복제, 접착, 지우개로 가장자리 정리 |
| [스켈레톤과 자세](guide/SKELETON.md) | 스켈레톤 추론, 리그, IK, 프리셋 모션, 베이크 구조 |
| [스윙 생성](guide/SWING.md) | 좌우 / 위아래 스윙, 캔버스 핸들, 진자, 베이크 |
| [물리](guide/PHYSICS.md) | 물리 그룹의 출처, 진자 캔버스, 입력과 출력, 프리셋, 내보내기 |
| [시뮬레이션과 베이크](guide/SIMULATION.md) | 천 / 머리카락의 2D 시뮬레이션, 정점 가중치 그룹, Glue 역할. 파라미터, 키폼, 진자로 베이크 |
| [시뮬레이션 그림 튜토리얼](guide/SIMULATION_TUTORIAL.md) | 머리카락과 의상 프리셋에서 시작해 고정점 가중치를 조정하고, 다시 베이크한 뒤 내보내기 결과를 확인 |
| [프리셋과 모디파이어](guide/MODIFIERS.md) | 프리셋과 모디파이어 사용법 |
| [디폼 경로(실험적)](guide/DEFORM_PATHS.md) | 경로를 만들고, 묶고, 내보내는 방법 |
| [텍스처 고해상도화](guide/TEXTURE_UPSCALE.md) | 로컬 nunif 설정, 파라미터, 결과 확인 |
| [알고리즘 플러그인](guide/ALGORITHM_PLUGINS.md) | 알고리즘 플러그인 개발과 연결 |

## 빌드와 배포

| 문서 | 내용 |
| --- | --- |
| [개발과 명령줄](guide/DEVELOPMENT.md) | 소스 실행, CLI 인자, 테스트와 패키징, 코드 구조 |
| [Cubism Native 미리보기](guide/CUBISM_SDK_SETUP.md) | 선택 사항인 공식 SDK 미리보기 브리지의 빌드와 배포 |
| [CI와 배포](guide/CUBISM_CI_RELEASE.md) | GitHub Actions 테스트와 SDK가 포함된 배포 절차 |

## 형식과 핵심 계약

| 문서 | 내용 |
| --- | --- |
| [PSD 소재와 이름](spec/PSD_LAYER_SPEC.md) | 레이어 요구, 레이어 이름 표, 좌우와 차분 |
| [디포머와 파라미터](spec/DEFORMER_AND_PARAMETER_SPEC.md) | 자동 생성 구조, 좌표 규칙, 기본 파라미터 |
| [프로젝트 형식 v2](spec/PROJECT_FORMAT.md) | `.psd2live` 아카이브 배치, v1 마이그레이션, 저장과 검증 |
| [Layout 매니페스트](spec/LAYOUT_MANIFEST.md) | 레이어 소재의 JSON / zip 교환 형식 |
| [내보내기 대상](spec/EXPORT_TARGETS.md) | 중립 리그 IR, 각 내보내기 형식과 손실 보고서, 모듈 라이선스 |
| [런타임](spec/RUNTIME.md) | Rust 런타임, `.p2lrt` 형식, 웹 플레이어, Godot 노드 |
| [`.p2lrt` 2.0 형식](spec/P2LRT_V2.md) | 청크 컨테이너, 핵심 층과 확장 층, 진화 규칙 |
| [문서 레이어](spec/DOCUMENT_LAYER.md) | 생성기 의존 그래프, 생성 결과 덮어쓰기, 캐시 |
| [Rig 프리셋 아키텍처](spec/RIG_PRESETS.md) | 부위, 기초 알고리즘, 모듈, 레시피 |
| [간소 작업 공간과 모바일(설계 중)](spec/LITE_WORKSPACE.md) | 진입 장벽이 없는 간소 화면, 앞뒤 분리 터치 마법사, MCP 연결과 모바일 단계 방안 |

## Agent(MCP)

| 문서 | 내용 |
| --- | --- |
| [MCP 사용과 인터페이스](agent/MCP_AUTHORING.md) | 연결 방법, 공개 조작, 원자적 일괄과 시험 실행, 호출 예 |
| [Agent 설계와 검수](agent/AGENT_DESIGN.md) | 역할 경계, 도구 설계 원칙, 작업 검수 단계 |

## 기타

- [예제 소재와 출력](../../examples/readme.md)
- [네이티브 브리지 빌드 스크립트](../../native/README.md)
- [서드파티 구성 요소 고지](../../THIRD_PARTY_NOTICES.md)

## 유지 관리 규칙

1. README에는 위치, 시작 방법, 능력 경계, 진입점만 적습니다. 단계별 조작은 프로그램 안 대화형 튜토리얼을 기준으로 합니다.
2. 중국어가 원문입니다. 영어, 일본어, 한국어는 동기화된 번역입니다. 문서는 `docs/en`, `docs/ja`, `docs/ko`에 있고, 프로젝트 홈은 저장소 루트의 `README.en.md`, `README.ja.md`, `README.ko.md`입니다. 중국어를 고칠 때 이 세 벌도 함께 고칩니다. 각 페이지 맨 위의 언어 전환은 같은 경로의 네 언어를 가리킵니다.
3. 흩어진 과정용 설계 초안이나 검토 초안을 `docs/`에 새로 넣지 않습니다(과정 노트는 `local/`에 둡니다).
4. 설명은 소스와 맞아야 합니다. 튜토리얼 순서는 `InteractiveTutorial.kt`, 단축키는 `ShortcutRegistry.kt`, CLI는 `Main.kt`, MCP 도구는 애플리케이션 층 조작 정의와 `AgentOperationTools.kt`를 대조합니다.
