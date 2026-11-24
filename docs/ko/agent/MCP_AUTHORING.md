<!-- lang: zh en ko ja -->
> [中文](../../zh/agent/MCP_AUTHORING.md) · [English](../../en/agent/MCP_AUTHORING.md) · **한국어** · [日本語](../../ja/agent/MCP_AUTHORING.md)

# MCP 사용과 인터페이스

[문서 목차](../README.md) · [설계와 검수](AGENT_DESIGN.md) · [능력 실측](../STATUS.md)

공개 정의는 애플리케이션 층의 [WorkspaceAuthoringOperations.kt](../../../src/main/kotlin/io/github/psd2live/application/WorkspaceAuthoringOperations.kt), [WorkspaceDocumentBatch.kt](../../../src/main/kotlin/io/github/psd2live/application/WorkspaceDocumentBatch.kt), [WorkspaceAuxiliaryOperations.kt](../../../src/main/kotlin/io/github/psd2live/application/WorkspaceAuxiliaryOperations.kt), [WorkspaceOperations.kt](../../../src/main/kotlin/io/github/psd2live/application/WorkspaceOperations.kt)에 있고, MCP 바인딩은 [AgentToolCatalog.kt](../../../src/main/kotlin/io/github/psd2live/agent/AgentToolCatalog.kt)를 참조합니다. 도구는 `domain_operation` 이름을 씁니다. 이전 분기 도구는 삭제되었고, 호환 별명은 없습니다. `workspace_list_operations`로 페이지를 나눠 능력을 찾고, `workspace_get_operation`으로 항목의 전체 schema를 읽습니다.

## 접속

1. 데스크톱 앱을 시작하고, 작업 공간을 불러오거나 만듭니다.
2. **설정 → MCP**를 엽니다(제목 막대 오른쪽 톱니). 호스트에 맞춰 명령이나 설정을 복사합니다. Claude Code 명령, Codex TOML, 범용 `mcpServers` JSON(주소 필드는 호스트마다 다름) 또는 Stdio 프록시입니다. 「설치 안내 복사」는 Agent가 스스로 설정하게 하는 짧은 안내를 만듭니다.
3. Streamable HTTP와 Bearer 토큰을 우선 씁니다. 폐기된 `/sse` 끝점으로 바꾸지 않습니다.
4. Stdio만 지원하는 호스트는 Python 3으로 `tools/mcp_proxy.py`를 실행합니다. 프록시는 `PSD2LIVE_MCP_ENDPOINT`와 `PSD2LIVE_MCP_TOKEN`을 읽고, 설정되지 않았으면 Windows에서 앱이 저장한 포트와 토큰을 읽습니다. 스크립트와 명령줄도 이것으로 호출합니다. `python tools/mcp_proxy.py call <操作> [JSON | @文件] [--edit] [--out 目录]`(`--edit`는 현재 `state`를 자동으로 채우고, 그림은 PNG로 저장), `python tools/mcp_proxy.py operations`. Python에서는 `from mcp_proxy import Client` 뒤 `call` / `edit`를 쓰며, 두 도구 프로필에 모두 적용되고, 백그라운드 조작은 끝날 때까지 기다린 뒤 결과를 반환합니다.
5. 도구를 나열한 뒤 `workspace_inspect`를 호출해, 실제 객체 ID, 파라미터, 현재 `project_id`와 `state`를 읽습니다.

같은 대화 상자에서 서비스 사용 여부, 포트(1024–65535, 기본 23871), 접근 토큰(32–256자의 문자, 숫자, `-`, `_`. 다시 만들거나 직접 입력), 도구 집합을 설정합니다. 사용자 환경설정에 저장하고, 적용하면 끝점을 다시 시작합니다. 재시작은 프로세스 안의 작업과 요청 중복 제거 기록을 남깁니다. 이미 연결된 호스트는 다시 연결해야 하고, 포트나 토큰을 바꿨으면 호스트 설정도 갱신합니다.

토큰은 현재 작업 공간을 편집할 수 있으므로, 이 컴퓨터의 호스트 설정에 두어야 합니다. 도구는 이미지 생성 모델을 제공하지 않습니다. 새 그림은 이미 있는 픽셀, 페인팅, 또는 호스트의 이미지 생성기에서 올 수 있습니다.

## 도구 집합

도구 집합은 `tools/list`가 어떤 도구를 발행할지만 결정합니다. 모든 조작은 두 도구 집합에서 호출할 수 있고, 실행과 검증은 같습니다.

- **간소(기본)**: 도구 27개로 조작 123개를 직접 덮습니다(입력 schema와 설명 약 8.5만 자. 전체 도구 집합은 약 76만 자이고, 출력 schema가 약 426만 자 더 있습니다).
  - 단일 도구: `workspace_overview`, `workspace_inspect`, `workspace_list_operations`, `workspace_get_operation`, `workspace_apply_edits`, `workspace_preview_edits`, `rig_deform`, `keyform_apply`, 그리고 의도 도구 `author_axis`, `author_physics`, `author_compile`([의도 도구](#의도-도구) 참조).
  - 족 도구: `view`, `parameter`, `motion`, `expression`, `hit_area`, `pose`, `skeleton`, `path`, `physics`, `simulation`, `swing`, `modifier`, `source_paint`, `snapshot`, `history`, `project`, `job`. `{"op":"set_key","request":{...},"wait_ms":20000}`를 호출하면 `<族>_<op>`를 실행합니다(이 예는 `motion_set_key`). 결과의 `operation`은 그 조작 ID이고, `request`는 그 조작의 전체 schema로 검증합니다. 멤버는 레지스트리에서 그 접두가 있는 모든 조작이며, 비공개 초안, 시청, 실시간 미리보기 세션(`skeleton_draft_*`, `physics_audition*`, `*_preview*`), 히스토리 주석, 전역 물리 프리셋 라이브러리는 제외합니다. 족 도구는 각 멤버 필드의 평탄한 합집합을 발행합니다(`oneOf`, `const`, `$ref`는 넣지 않고, 멤버마다 형태가 다른 필드는 설명만 줍니다). 각 op의 필수 필드는 도구 설명에 있습니다.
  - 다른 조작은 `workspace_call`로 호출합니다. `{"operation":"layer_classify","request":{...}}`. 조작 ID는 동시에 `workspace_apply_edits` 멤버 이름입니다.
  - `outputSchema`는 발행하지 않습니다(결과는 여전히 `structuredContent`로 반환하고, 계약은 `workspace_get_operation`을 참조). 원자 배치의 `edits` 멤버는 조작 이름 열거와 `request` 객체만 발행하고, 멤버 필드는 실행할 때 단일 schema로 엄격히 검증합니다.
  - `request_id`와 `project_id`는 생략할 수 있습니다. `project_id`는 현재 불러온 프로젝트를 씁니다. `state`는 여전히 필수이며, 다른 곳에서 불러온 `state`는 평소처럼 `state_conflict`를 보고합니다. `request_id`는 조작과 파라미터(`state` 포함)에서 파생하고, 같은 파라미터의 재시도는 원래 결과를 가져옵니다. 지난번 같은 호출이 이미 실패했으면(작업 `failed/cancelled` 포함) 다시 호출할 때 새 시도로 실행합니다.
  - 백그라운드 조작은 `wait_ms`를 받습니다(0–30000, 기본 20000). 작업이 제한 시간 안에 끝나면 종료 상태와 결과를 바로 반환합니다(`job_wait`와 같은 구조). 아니면 실행 중인 작업을 반환하고, `job_wait`를 계속 씁니다. `wait_ms: 0`은 작업 핸들을 즉시 반환합니다.
- **전체**: 조작마다 도구 하나. 완전한 입력·출력 schema를 발행하고, 컨텍스트를 파생하지 않으며, 작업을 기다리지 않습니다. 도구를 필요할 때 불러오는 호스트나, 항목별 계약이 필요한 클라이언트에 맞습니다.

## 의도 도구

의도 도구는 레지스트리의 일반 공개 조작입니다(두 도구 집합 모두 호출 가능). 정의는 [WorkspaceIntentOperations.kt](../../../src/main/kotlin/io/github/psd2live/application/WorkspaceIntentOperations.kt)에 있습니다. 쓰기 종류는 요청 `state`에 대응하는 캡처 모델에서 의도를 기존 배치 멤버로 컴파일한 뒤, `workspace_apply_edits`와 같은 원자 배치(`startDocumentBatch`)를 탑니다. 재구축, 기하 검사, CAS가 모두 같고, 성공 한 번은 히스토리 노드 하나만 추가하며, 로그 의미를 새로 만들지 않습니다. 작업 결과는 `workspace_apply_edits`와 같습니다(`edit_count`는 컴파일된 멤버 수). 컴파일 중 발견한 문제는 `invalid_argument`로 즉시 반환하고, 멤버 실행 실패는 `invalid_edit`로 멤버 번호와 조작을 가리킵니다. 캡처 버전이 요청 `state`와 다르면 `state_conflict`를 보고합니다.

| 도구 | 컴파일 결과 | 설명 |
| --- | --- | --- |
| `workspace_overview` | 읽기 전용 조회 | 한 번 캡처해 캔버스, 파라미터, 레이어, 디포머 계층(부모와 자식 객체 수 포함), 메시 수, 물리 그룹(입력/출력 파라미터), 모션 클립을 반환합니다. 각 목록에는 `total`이 있고, `limit`(1–500, 기본 200)으로 자릅니다. 파라미터에는 `kind`와 `repeat`(순환 파라미터)가 있습니다 |
| `author_axis` | `parameter_create`(파라미터가 없을 때) → `keyform_apply` seed → `rig_deform` | `parameter.id`가 없으면 만듭니다(`name` 기본은 ID). 이미 있으면 준 `min/max/default`가 현재 값과 같아야 합니다. 각 형태 대상은 먼저 파라미터 기본값에서 seed해, 현재 형태가 중립 형태가 되게 합니다(그 축이 이미 기본값에 키 포인트가 있고 `key`를 주지 않으면 건너뜀). 그다음 `value`에서 `rig_deform`의 `operations/selection`을 실행합니다. 대상에 직접 바인딩된 다른 축은 각자 기본값을 쓰고, `key`로 조합 각의 값을 지정할 수 있습니다. `value`가 기본값과 같으면 거부합니다. `mirror: true`이면 각 형태를 반대 값으로 다시 미러합니다(`2×默认值 − value`, 범위 안이어야 함). 한쪽을 쓰면 양쪽이 모두 생깁니다. 새 파라미터 축의 키 포인트는 엔진이 min/기본/max로 만듭니다 |
| `author_physics` | `physics_put` → `physics_fit` | 필드는 `physics_put`과 같고, `fit_target`을 더합니다(10–300, 기본 100. `null`이면 피팅을 건너뜀). `id`와 `fit_target`만 주면 이미 있는 그룹만 다시 맞춥니다. 둘 다 바꾸지 않으면 거부합니다. 출력 파라미터에는 이미 저작 형태가 있어야 합니다 |
| `author_compile` | 읽기 전용 조회 | 의도가 제출할 멤버를 반환합니다. 아래를 참조합니다 |

의도 도구 자체는 시험 실행하지 않습니다. `author_compile`은 요청 `state`로 같은 의도를 컴파일하고, 제출할 멤버를 반환합니다(`{"intent":"author_axis","request":{...}}`. `request`에는 `state`가 있고 `request_id/project_id`는 없음). 프로젝트를 바꾸지 않습니다. 확인하거나 수정한 뒤 `workspace_preview_edits`에 넘겨 그중 기하 멤버를 시험 실행하거나, `workspace_apply_edits`에 넘겨 제출할 수 있습니다. 결과는 의도를 직접 호출한 것과 같습니다.

## 공개 도구 빠른 참조

| 도구 | 요청 구조 | 용도 |
| --- | --- | --- |
| `workspace_list_operations` | 선택 `domain`, `kind`, `query`, `offset`, `limit` | 도구, 업무 유형, 실행 메타데이터를 페이지로 찾습니다. `query`의 각 단어는 모두 조작 ID나 설명에 있어야 합니다(대소문자 구분 없음). 목록 항목은 설명의 첫 문장만 가지고, 전체 설명은 `workspace_get_operation`을 참조합니다 |
| `workspace_get_operation` | `id` | 단일 schema, 필드 설명, 실행 메타데이터를 읽습니다 |
| `workspace_apply_edits` | `state`, `edits`(1–128개), 선택 `summary` | 백그라운드에서 같은 후보 문서를 순서대로 편집하고, 모두 성공하면 히스토리 노드 하나를 제출합니다. 작업 핸들을 반환하고, 멤버 지원은 `batchable`을 참조합니다 |
| `workspace_preview_edits` | `state`, `edits`(1–128개), 공유 요청 컨텍스트 | 백그라운드에서 기하 저작 편집을 시험 실행하고, 후보 revision과 진단을 반환합니다. 문서, 포즈, 히스토리, 리소스는 발행하지 않습니다 |
| `workspace_preview_regeneration` | `state`, `edits`(1–128개, 리소스 레이어 조작 제외), 공유 요청 컨텍스트 | 백그라운드에서 Rig를 다시 생성하는 편집(설정, 분류, 분할, `rig_update_generation` 등)을 시험 실행하고, 후보 revision, 쓸 체크포인트와 그 병합 문제, 추가·삭제된 객체를 반환합니다. 아무것도 발행하지 않습니다. 명시적 스택 프로젝트는 `modifiers`도 반환하며, 후보 각 항목의 의존 상태, 실제 기여, 덮어쓰기 충돌이 들어 있어, 단일 항목이나 묶음을 추가하기 전에 예상 내용을 볼 수 있습니다. 같은 `state`와 요청으로 제출하면, 후보 리비전과 자동 할당된 인스턴스 정체는 그대로입니다 |
| `workspace_inspect` | `request` 안의 `scope` / `target` | scope는 `project/settings/preview/generation/objects/layers/parameters/physics/swings/paths/simulations/interiors/vertex_groups`입니다. 레이어 요약에는 유효 메시 설정, 의미 태그 `tags`가 있고, 생성 뒤 배치했거나 분류가 생성을 기다리는 레이어는 `pending:true`를 가지며, 소재에서 온 것은 `material_id`를 가집니다. `generation`은 생성했는지, 생성 대기 레이어, 기다리는 분류, 기하를 새로 고칠 수 있는 레이어를 반환합니다. 프로젝트를 불러왔으면 `project`에 `quality.overrides`가 있습니다. 관찰 보고서(버전 4), 검사 세 가지입니다. 기록대로 적용되지 않은 생성 결과 덮어쓰기(충돌 / 고립, 모두 warning. 색, 투명도 등 채널 덮어쓰기는 `evidence.channel`에 채널을 줌), 이후 버전 2 분할이 대체한 메시만 참조해서 무조작으로 재생된 로그 항목(`SUPERSEDED_ENTRY_SKIPPED`, info, `target`은 `journal:<序号>`), 설정이 요구하는데 만들지 못한 앞머리 투과 복제본(`SEE_THROUGH_DROPPED`, info, `target`은 원본 `mesh:<ID>`, `evidence.reason`은 `original_missing` / `masks_missing` / `id_taken`, 눈 레이어가 앞머리에 창을 열 때는 `bangs_clipped`(앞머리에 이미 자기 마스크가 있음) / `target_unsupported`(런타임 대상에 역 마스크가 없음)도 있음). 또 `quality.regeneration`이 있습니다. 관찰 보고서(버전 2, 검사 두 가지)입니다. 먼저 로그의 마지막 체크포인트에서 재생성 병합이 깨끗하게 이전하지 못한 내용을 나열합니다(`REGENERATION_CONFLICT`, `REGENERATION_TOPOLOGY_KEPT`는 warning, `REGENERATION_DROPPED`는 warning / validity, 다시 걸기, 생성기 교체에 따라 다시 걸기(`REGENERATION_FOLLOWED`), 퇴역 객체 유지, 부모 변경, 정점 이전, 사용자 토폴로지가 생성을 따름(`REGENERATION_TOPOLOGY_FOLLOWED`), 이 프로그램으로 다시 병합한 체크포인트(`REGENERATION_REMERGED`)는 info). 그다음 이전 버전의 병합이 이 프로그램으로 병합하면 다른 구조가 되고 Rig에 영향을 주는 재생성 체크포인트를 나열합니다(`REGENERATION_REPAIR_AVAILABLE`, warning, `target`은 `journal:<序号>`, `evidence.detail`은 구조가 다른 객체를 나열. `rig_update_generation`을 실행하면 고칠 수 있음. 모델마다 첫 검사는 다시 병합해야 해서 수 초가 걸릴 수 있음). `can_proceed`는 항상 true입니다. 또 `quality.skeleton`이 있습니다. 관찰 보고서(버전 1)입니다. 본에 바인딩되었지만 그 사지 본에 이끌리지 않는 메시(`SKELETON_BINDING_INEFFECTIVE`), 호흡 Warp 아래에 걸렸지만 스켈레톤 몸통 Warp를 우회한 메시(`SKELETON_TORSO_BYPASSED`)를 나열하며 모두 warning입니다. 그리고 Rig에 없는 메시를 바인딩한 것(`SKELETON_BINDING_MISSING`, info / validity)도 있습니다. `target`은 `mesh:<ID>`, `evidence.bone`은 그것을 바인딩한 본이며, `can_proceed`는 항상 true입니다 |
| `layer_classify` | `request` 안의 `state`, `layer_id` 및 분류 필드 | 백그라운드에서 이미 있는 원본 레이어의 유형, 파츠, 좌우, 파라미터 연관, 전환 ID를 갱신합니다. `part`는 레이어가 그 파츠 안에서 가지는 역할을 설정합니다(`highlight`, `pupil`, `lower`, `lid`, `shadow`, `blush`, `side`, `ahoge`, `ponytail`, `braid`, `animal`, `ribbon`, `pendant`, `rigid`, `sleeve`, `skirt`, `upperlip`, `lowerlip`, `upperline`, `lowerline`. `none`은 역할 없음, `auto`는 분석에 맡김). Rig 모듈만 이것을 읽습니다. 생략한 필드는 캡처 때의 원래 값을 유지합니다. 이미 생성된 레이어는 즉시 다시 생성하지 않습니다. 결과에 `pending:true`가 있고, 다음 `rig_generate` 때 적용됩니다. 바로 효과를 보려면 같은 `workspace_apply_edits` 배치 끝에 `rig_generate`를 더합니다. 생성되지 않은 레이어는 즉시 적용됩니다 |
| `interior_put` | `state`, `target`, 선택 `enabled/settings/motion/parameters/guides`(안내선 최대 64개) | 백그라운드에서 단일 메시 내부 조형을 생성/갱신합니다. target은 메시 ID 또는 mesh:ID입니다. enabled만 주면 레시피를 유지하고 효과만 켜고 끄며, 다시 샘플링하지 않습니다. 파라미터를 생략하면 몸 X/Y를 우선하고, 명시적 빈 ID는 새로 만듭니다. 네이티브 압축 키폼과 물리 그룹을 씁니다. workspace_apply_edits에 넣을 수 있고, UI와 저작 경로를 공유합니다 |
| `interior_delete` | `state`, `target`(Mesh ID 또는 mesh:ID) | 내부 조형과 자체 물리를 지웁니다. 다른 기능이 참조하는 파라미터는 남기고, 실행 취소할 수 있습니다. `workspace_inspect scope=interiors`는 설정, 안내선, 입력/출력 ID, 무효 이유를 읽어 옵니다. 변형 배열은 반환하지 않습니다 |
| `layer_mesh_update` | `request` 안의 `state`, `layer_id`, `changes` 또는 `reset` | 백그라운드에서 레이어별로 적응 메시 파라미터를 덮거나 재설정합니다. `workspace_inspect scope=layers`로 현재 값을 읽습니다 |
| `source_sample_color` | `layer_id`, `x`, `y` | 원본 레이어의 캔버스 정수 좌표 RGBA를 읽습니다. 문서나 히스토리는 바꾸지 않습니다 |
| `source_paint_brush / source_paint_pencil / source_paint_eraser / source_paint_bucket / source_paint_shape / source_paint_clear` | `request` | `brush/pencil/eraser/bucket/shape/clear`. 캔버스 단위 좌표이며, 레이어 래스터 밀도에 맞춰 찍고, 제스처 하나에 히스토리 노드 하나입니다 |
| `preview_set / preview_reset` | `request` 안의 `state` | `set/reset`. 현재 미리보기 파라미터 값과 잠금 상태를 수정하고, 키폼은 쓰지 않습니다. `set`은 `expression`(표정 ID)을 줄 수 있으며, 제출된 포즈(`values`를 먼저 적용) 위에 그 표정을 전체 가중치로 표시합니다. 애니메이션 패널의 표정 미리보기와 같습니다 |
| `snapshot_create / snapshot_update / snapshot_delete / snapshot_apply` | `state`. 갱신/삭제/적용은 `id`가 필요. 생성/갱신은 `name`, `values`를 줄 수 있음 | 파라미터 스냅샷을 저장, 이름 변경/덮어쓰기, 삭제, 적용합니다. 적용은 잠금을 유지하고, 삭제된 파라미터는 무시하며, 현재 범위로 클램프합니다. 타임라인 키프레임은 쓰지 않습니다 |
| `snapshot_get / snapshot_list` | 읽기는 `id`. 목록은 `offset`, `limit`을 줄 수 있음 | 같은 캡처의 프로젝트 상태, 스냅샷 ID/번호를 반환합니다. 목록은 values를 생략하고, 상세는 values를 반환합니다 |
| `history_annotation_put / history_annotation_delete / history_annotation_get` | `node_id`. 쓰기는 `title`, `note`, `hidden`도 필요 | 히스토리 노드의 표시 주석을 관리합니다. 보조 데이터는 프로젝트에 저장하고, 히스토리 노드와 Rig revision은 변하지 않습니다 |
| `timeline_bookmark_set / timeline_shy_set / timeline_freeze_set / timeline_marker_put / timeline_marker_delete` | `state`. 북마크는 `parameter`, `bookmarked`. 나머지는 `clip_id`(편집기의 모션 ID, 생성 모션은 `preset:<名称>`)와 `parameter`, `shy` / `frozen`(동결은 `value` 필요) 또는 마커의 `time`, `label`, `color`, `id` | 애니메이션 편집기의 타임라인 보조입니다. 파라미터 북마크, 가리고 얼린 트랙, 타임라인 마커입니다. 보조 데이터는 프로젝트와 함께 저장되고, 히스토리를 추가하지 않으며, 내보내지 않습니다. 동결은 편집기 재생 때 그 값을 유지할 뿐, `motion_*`이 키를 바꾸는 것을 막지 않습니다. `id`가 없는 마커는 다음 `marker_<n>`을 씁니다 |
| `timeline_aids_get` | `clip_id`를 줄 수 있음 | 북마크와 각 모션의 가림, 동결 값, 마커를 읽습니다 |
| `canvas_guides_get / canvas_guides_set` | `set`은 `state`, `guides`가 필요 | 편집 캔버스의 눈금자 안내선을 읽거나 통째로 바꿉니다. 각 줄은 `{axis: x / y, position, id?}`(캔버스 픽셀, `x`는 세로선)입니다. `id`가 없으면 다음 `guide_<n>`을 쓰고, 최대 256개이며, 빈 배열은 지웁니다. 캔버스 눈금자와 보조 CAS를 공유하고, 프로젝트와 함께 저장되며, 히스토리에 들어가지 않고 내보내지 않습니다 |
| `module_catalog` | 없음 | 읽기 전용이며, 프로젝트를 열 필요가 없습니다. Rig의 부위(눈, 눈썹, 입과 아랫얼굴, 머리와 얼굴, 머리카락, 몸, 의상과 장식, 표정, 모션), 부위마다의 기초 알고리즘(서로 배타적인 방식, 첫 번째는 모듈 도입 전의 `legacy`)과 모듈(겹칠 수 있는 능력, 각자 스위치. `tier`는 core / major / minor 중요도, `requires`는 의존), 조정할 수 있는 수치(단위, 범위, 기본값)를 나열합니다. 프로젝트를 열면 `modern`도 줍니다(모듈 도입 전에 저장한 프로젝트는 false이며 예전처럼 생성하고, `settings_update`의 `changes.rig: {}`로 업그레이드). 각 부위의 현재 기초 알고리즘, 각 모듈이 소재에 적용되는지, 켜져 있는지, 비용(추가 파라미터, 키폼, 블렌드 셰이프, 물리 그룹. 켜져 있으면 실측, 꺼져 있으면 추정 `estimated:true`)도 줍니다. [Rig 프리셋 구조](../spec/RIG_PRESETS.md)를 참조합니다 |
| `modifier_convert` | `state` | 백그라운드에서 이전 프로젝트를 명시적 모디파이어 스택으로 바꾸려 합니다. 모델과 내보내기 결과를 비교해 통과한 뒤에만 히스토리 노드 하나를 제출합니다. 차이가 있으면 `modifier_conversion_mismatch`를 반환하고 원래 프로젝트를 유지합니다. 오류에는 차이 필드, 객체 추가·삭제·수정의 수, 일부 ID가 들어갑니다 |
| `modifier_catalog` | 없음 | 등록 유형, 버전, 의존, 제공하는 능력, 상호 배타 족입니다. `groups`는 구／신 알고리즘의 풍부함 세 단계씩을 나열합니다. `face/fitted`와 `face/legacy`는 상호 배타입니다. 이전 `face.depth-turn`은 재생 호환만 남깁니다. 기초 강도 범위는 0–4이고, 다른 값은 목록 schema를 기준으로 합니다 |
| `modifier_plan` | `type` 또는 `group`, 고정된 `targets`. 네이티브 스윙／물리／시뮬레이션／모션은 `config`를 줄 수 있고, 모션과 물리는 `preset`만 따로 줄 수 있음 | 원본 그림 바인딩이나 네이티브 생성기를 읽기 전용으로 계획합니다. 스윙은 Warp를 지정할 수 있습니다. 나머지 계획 대상은 메시입니다. 네이티브 설정을 생략하면 이미 있는 파라미터와 원래 알고리즘으로 기본값을 만듭니다. 쓸 수 있는 파라미터가 없으면 거부합니다. 완전히 추가되는 `entries`(안정 ID를 이미 할당)와 선행 항목 `plan`을 반환하고, 추가, 재사용, 이미 고정을 구분합니다. 그룹 ID 여섯 가지는 `modifier_catalog.groups`를 참조합니다. `fitted`는 `modern-standard`의 호환 별명입니다. 반환된 `group`과 `entries`를 `workspace_preview_regeneration`에 넣고, 같은 `state`로 `modifier_add_group`을 제출할 수 있습니다. 계획 자체는 문서를 바꾸지 않습니다 |
| `modifier_stack` | 없음 | 캡처 버전의 스택, 고정 대상, 설정, 출처 그룹, 실제 기여, 상태를 읽고, 다음 수정에 쓸 `state`를 반환합니다. `owned_keyforms`는 생성 키폼의 객체와 파라미터 참조를 나열하고, 선택 `key`는 이미 있는 축에 추가된 칸의 전체 파라미터 좌표를 가리킵니다. `override_issues`는 덮어쓰기 충돌이나 맞출 수 없는 덮어쓰기를 보고합니다. 시뮬레이션 베이크는 요약만 반환하고 정점 배열은 반환하지 않습니다 |
| `modifier_add` / `modifier_add_group` | `state`. 단일의 `type`, `targets`, `config`, 또는 그룹의 `group`, `entries` | 이미 있는 명시적 스택에만 작용합니다. `id`로 인스턴스 정체를 미리 고정해 이후 배치 멤버가 참조할 수 있습니다. 생략하면 문서가 할당합니다. `version`을 생략하면 그 유형의 기본 버전을 고르고, 저장한 뒤 버전을 잠급니다. 명시적으로 고를 때만 쓰는 버전은 `version`으로 지정할 수 있고, `plugin_list`가 반환한 패키지 입구 유형을 `modifier_plan`에 넘길 수도 있습니다. `index`로 삽입 위치를 명시할 수 있습니다(0부터, 생략하면 추가). 이미 있는 항목을 자동으로 다시 정렬하지 않습니다. 설정은 유형별로 엄격히 검증합니다. 그룹은 공통 출처만 기록하고, 각 항목은 여전히 독립입니다. 호출 한 번 또는 배치 전체가 히스토리 노드 하나만 제출합니다 |
| `modifier_update` / `modifier_enable` | `state`, `id`. `type`과 완전한 `config`, 또는 `enabled` | 인스턴스 설정을 조정하거나 켜고 끕니다. 인스턴스 정체, 알고리즘 버전, 캡처 대상은 바꾸지 않습니다. 끄면 그 덮어쓰기 기록을 유지합니다 |
| `modifier_move` / `modifier_remove` | `state`, `id`. 이동은 0부터의 `index`를 더함 | 이동은 실제 평가 순서를 바꿉니다. 의존을 어기면 거부합니다. 제거는 잠든 덮어쓰기를 남기고 그 정체를 퇴역시키며, 새 인스턴스는 그것을 물려받지 않습니다 |
| `modifier_apply` | `state`, `id` | 그 항목까지의 완전한 접두사를 고정하고, 접두사 인스턴스를 제거하며 이후 항목은 남깁니다. 물리는 일반 물리 구성이 되고, 모션은 파일 이름을 유지한 일반 클립이 됩니다. 재구축 결과가 적용 전과 같아야 제출합니다. 위의 수정은 모두 프로세스 작업을 반환하며, `job_wait` / `job_get`으로 종료 상태를 읽습니다 |
| `settings_update` | `request` 안의 `state`, `changes` | 백그라운드에서 자동 Rig, 메시, 텍스처, 고화질화, 물리 프리셋, 모션, 내보내기 설정을 수정합니다. `rigTuning`은 필드별로 Rig 수치를 병합합니다(머리 회전, 눈, 눈썹, 입, 코와 귀, 머리카락, 그리고 몸의 몸 돌리기, 위아래, 앞뒤 기울기, 크기 변화, 몸 Z와 호흡, 입체와 원근. 단위는 모델 프리셋 각 부위 카드의 「수치」와 같고, 범위를 넘거나 알 수 없는 필드는 거부). `rig`는 Rig 레시피를 수정합니다. `bases: {部位: {base, values}}`는 기초 알고리즘을 고르고, `modules: {模块: {on, values}}`는 모듈을 켜고 끄며 수치를 조정합니다. 같은 기초 알고리즘이나 모듈의 `values`는 기존 수치와 병합하고, 항목이 `null`이면 기본으로 돌아갑니다(모듈은 켬으로). `rig: {}`는 모듈 도입 전의 프로젝트를 업그레이드하고, `rig: null`은 원래 생성 방식으로 되돌립니다. 원래 스위치를 이어 받는 모듈(`eye.see-through`, `eye.jelly`, `brow.see-through`, `mouth.outline`)의 `on`은 대응 설정에 씁니다. `moves: {特征: {手柄: [dx, dy] 或 null}}`는 피팅한 이목구비의 핸들을 이동합니다(특징 `eye.L` / `eye.R` / `brow.L` / `brow.R` / `mouth`, 핸들은 차례로 눈의 `inner` / `outer` / `upper` / `lower`, 눈썹의 `inner` / `outer` / `arch`, 입의 `left` / `right` / `upper` / `lower`. 단위는 캔버스 픽셀이며, 원화 피팅 위치 기준. `null`이면 핸들 또는 특징 전체가 피팅 위치로 돌아감). 알 수 없는 부위 / 기초 알고리즘 / 모듈 / 수치 / 특징 / 핸들과 범위를 넘는 값은 거부됩니다(부위와 모듈은 `module_catalog` 참조). `meshOnly`가 바뀌었는데 `generateDeformers`를 명시하지 않으면 `!meshOnly`로 연동합니다(CMO3 가져오기 제외). 생성 모션 하위 항목이 바뀌었는데 `exportMotions`를 명시하지 않으면 하위 항목이 모두 켜져 있는지에 연동하고, 명시 필드가 우선합니다. 출처를 끄면 그것이 구동하는 저작 포즈를 작업 공간마다 파라미터 기본값으로 해제합니다(잠긴 파라미터는 건너뜀). 설정과 같은 제출입니다. 문서는 원래 설정을 저장합니다. 먼저 `workspace_inspect scope=settings`로 읽습니다 |
| `project_import_psd` | `path`, 선택 `discard_unsaved`, `modifiers`, `preset` | 절대 PSD 경로에서 백그라운드로 프로젝트를 만듭니다. 초기 모디파이어는 `recommended`(기본), `empty`, `minimal`, `default`, `full`, `custom` 중 하나입니다. `custom`은 `preset`을 반드시 줍니다. `headStrength`, `bodyStrength`(0–4), `featureDisplacement`, `mouthOutline`, `motionBasic`, `motionSkeleton`, `clothing`, `eyeJelly` 불리언, `frontHair`, `backHair`(`CLASSIC`, `SIMULATION`, `OFF`)를 넣을 수 있습니다. 다른 모드는 `preset`을 줄 수 없습니다. 빠른 조합은 완전한 동등 검사 뒤 한 번에 설치합니다. 이미 불러왔으면 전환할 수 있고, 기본적으로 저장하지 않은 수정은 거부합니다 |
| `project_create_artwork` | `width`, `height`, `layers`, 선택 `discard_unsaved` | 로컬 PNG/WebP/TIFF/BMP를 위치로 새 프로젝트를 만듭니다. 레이어는 아래에서 위로입니다. 백그라운드 작업이며, 전환하려면 저장하거나 명시적으로 포기해야 합니다 |
| `project_create` | `width`, `height`, 선택 `discard_unsaved` | 백그라운드에서 생성하지 않은 빈 프로젝트를 새로 만듭니다. 소재를 먼저 가져오고, 배치한 뒤 생성합니다. [소재와 Layout](#소재와-layout)을 참조합니다 |
| `project_import_layout` | `path`, 선택 `discard_unsaved` | [Layout 목록](../spec/LAYOUT_MANIFEST.md)(절대 경로의 `.json`, 또는 루트에 목록이 있는 `.zip`)에서 백그라운드로 생성하지 않은 Layout 프로젝트를 새로 만듭니다 |
| `project_import_cmo3` | `path`, `mode`(`new/replace`), 선택 `discard_unsaved` | 절대 경로의 CMO3를 백그라운드로 가져옵니다. 새로 만들 때는 기본적으로 저장하지 않은 전환을 거부합니다. 교체는 객체 ID로 갱신하고, 나타나지 않은 객체는 남기며, 히스토리 편집 한 번으로 제출합니다 |
| `project_open` | `path`, 선택 `discard_unsaved` | 절대 경로의 프로젝트 아카이브를 백그라운드로 엽니다. 저장하지 않았으면 기본적으로 거부하며, 먼저 저장하거나 명시적으로 포기합니다. 빈 작업 공간에서 호출할 수 있습니다 |
| `project_save` / `project_save_as` | 저장은 추가 필드가 필요 없음. 다른 이름으로 저장은 `path`가 필요 | 백그라운드에서 프로젝트를 저장합니다. 첫 저장은 다른 이름으로 저장의 절대 경로를 쓰고, 이후 저장은 현재 경로를 씁니다. 변경이 없으면 히스토리 노드를 추가하지 않습니다 |
| `project_export_model` | `request_id`, `state`, `output_directory` | 백그라운드에서 모델 파일 묶음을 내보냅니다. 작업 핸들을 반환하고, 디렉터리는 절대 경로여야 합니다 |
| `project_export_target` | `request_id`, `state`, `target`, `output_directory`, 선택 `settings` | 백그라운드에서 내보내기 대상 하나로 제출된 모델을 내보냅니다. “파일 → 다른 형식으로 내보내기”와 같습니다(실험 대상 제외). `settings`는 그 대상 자신의 설정 키입니다(`psd2live targets`가 나열). 결과에는 파일과 손실이 들어갑니다 |
| `project_export_psd` | `request_id`, `state`, `path` | 백그라운드에서 PSD를 내보냅니다. 1/2/4배와 생성 레이어 옵션을 지원합니다 |
| `job_get` / `job_wait` | `id`. 대기는 `timeout_ms`(0–30000)를 줄 수 있음 | 진행, 종료 상태, 결과를 조회합니다. 대기를 취소해도 작업은 취소되지 않습니다 |
| `job_list` | 선택 `project_id`, `offset`, `limit` | 앱 안의 작업을 페이지로 조회합니다 |
| `job_cancel` | `request_id`, `id` | 취소를 요청합니다. 이미 완료된 제출은 completed 상태를 유지합니다 |
| `rig_deform` | `request` 안의 `state`, `changes` | 분명한 파라미터 키에서 Mesh / Warp의 연속 형태를 편집합니다. `selection.indices`는 인덱스로 점을 고릅니다(`mesh_get_topology`가 나열). `soft_radius`(`radius`와 같은 정규화 상자)와 `falloff`(`smooth`가 기본, 그 밖에 `sphere/root/inverse_square/sharp/linear/constant/random`)를 주면 주변 점이 거리에 따라 따라옵니다. 편집기의 소프트 선택입니다. 임시 변형 격자가 선택한 점을 미는 효과는 `landmarks`로 표현합니다 |
| `keyform_apply` | `request` 안의 `state`, `changes` | `op: seed/copy/set/delete/mirror/reset/interpolate/paste/multi_key`. 키폼 집합, 스칼라 / 색 채널, 회전 형태를 편집합니다. 회전 `set`이 일부 필드만 주면(예: `angle`만) 나머지 필드는 그 회전이 이 키 포인트에서 가지는 현재 값을 씁니다 |
| `rig_create_warp` | `name`, `targets`. 선택 `id`, `rows/columns`, `fit_local` | 백그라운드에서 부모 Warp를 공유하는 Mesh의 독립 Warp를 만듭니다. 원자 배치도 지원합니다. 종료 상태는 `target`을 반환합니다 |
| `object_edit_appearance` | `request` 안의 `state`, `edits` | 이름, 표시, 구조 등의 순서 있는 편집입니다. 메시 `bind`의 `space=local`은 국소 좌표와 키폼을 유지합니다(물려받은 운동과 위치는 새 부모에 따라 바뀜). `space=canvas`는 정지 메시, 키폼, 블렌드 셰이프를 새 부모로 환산해, 메시가 기본 포즈에서 캔버스 원래 자리에 남게 합니다(계층 트리 드래그와 인스펙터의 부모 변경이 이 항을 씀). 디포머 `move`는 `space=local`만 받습니다 |
| `rig_edit_structure` | `request` 안의 `state`, `edits` | 정적 객체 속성(`blend_mode`는 `workspace_inspect`가 읽어 온 같은 블렌드 모드 이름, 예: `Normal`, `Multiply`, `Screen`), 디포머 삭제와 Part 소속, 파라미터 폴더와 XY 연관입니다 |
| `object_paste / mesh_duplicate / object_rename / object_convert_id` | `request` 안의 `state`와 각자 필드 | 오브젝트를 복사해 붙여 넣고, 메시 토폴로지를 다른 메시에 복사하고, 이름과 ID를 일괄로 바꿉니다. 모두 배치할 수 있습니다. [오브젝트 복사, 찾기 바꾸기, ID 전환](#오브젝트-복사-찾기-바꾸기-id-전환)을 참조합니다 |
| `canvas_warp / canvas_rotation / canvas_glue / canvas_topology` | `request` | `warp/rotation/glue/topology`. `glue`는 서로 다른 아트 프리미티브 `mesh_a`와 `mesh_b`를 함께 줘야 합니다. `rotation`의 `origin`은 캔버스 픽셀입니다(y는 아래). 제출할 때 부모 디포머의 국소 공간으로 환산합니다. `preservePose` 기본은 true이며, 메시는 정지 포즈에서 원래 자리를 유지합니다. `topology`는 아래 [메시 토폴로지](#메시-토폴로지)를 참조합니다 |
| `view_render_model / view_render_layer / view_render_context / view_render_poses / view_render_pad / view_check_coverage / view_compare_history / view_sample_motion` | `request` | `model/layer/context/poses/pad/coverage/compare/motion`. 모션 샘플은 읽기 전용 작업 핸들을 반환하고, 종료 상태에는 샘플 콜라주와 파라미터 범위가 들어갑니다 |
| `parameter_create / parameter_update / parameter_delete` | `request` | `create/update/delete`. 삭제할 때는 마지막 기본값으로 가장 가까운, 이미 쓴 키 포인트 슬라이스를 고르고 축을 접습니다. 범위를 좁히면 모션에서 그 파라미터의 키 포인트와 핸들이 새 범위로 들어갑니다. 파라미터를 지우면 모션에서 그 파라미터의 곡선도 제거합니다 |
| `layer_import_images` | `request` | PNG, 무손실 WebP, TIFF, BMP 파일에서 원본 그림을 여러 레이어로 한 번에 가져옵니다. 백그라운드가 끝나면 레이어와 메시 핸들을 반환합니다 |
| `layer_get_texture / atlas_get / atlas_render_page` | `layer_id`. `atlas_get`은 선택 `page`. 렌더는 `page`가 필요하고 선택 `max_size` | 같은 캡처에서 레이어 캔버스 사각형, 래스터 픽셀, 네이티브 밀도, 텍스처 커버와 타일, 또는 텍스처 아틀라스 예산, fit, 안내, 페이지 점유, 모든 타일을 읽습니다. 페이지 렌더는 읽기 전용 백그라운드 작업이며, 종료 상태는 PNG를 반환합니다 |
| `layer_set_canvas_rect / layer_replace_image / layer_set_pixel_density / atlas_set_tile / atlas_set_budget / atlas_pack` | `request` 안의 `state`와 각자 필드 | 백그라운드에서 레이어 캔버스 사각형을 수정하고, 임의 해상도 이미지를 바꾸고, 텍스처 밀도와 잠금을 설정하고, 타일을 옮기고, 텍스처 아틀라스 예산과 자동 배치를 수정하거나, 한 번에 배치합니다. 모두 원자 배치에 넣을 수 있습니다. [텍스처와 텍스처 아틀라스](#텍스처와-텍스처-아틀라스)를 참조합니다 |
| `source_get_components / source_split_components / source_split_polygon / source_split_depth / rig_update_generation / asset_prepare_reference / asset_import_png / asset_register / asset_preview_composite / layer_add_from_asset / layer_set_placement / layer_finalize_placement / asset_inspect / asset_reprocess / layer_delete / layer_restore` | `request` | 원본 그림 분할, 분할 기록 업그레이드, 소재의 레퍼런스, 가져오기, 정합, 미리보기, 추가, 위치 지정, 확인, 검사, 재처리, 소프트 삭제, 복구입니다 |
| `swing_put / swing_delete` | `request` | `put/delete`. Warp 또는 Mesh(Warp를 한 겹 자동으로 감쌈)에 좌우 / 위아래 스윙과 진자를 만듭니다. `motions`는 좌우와 위아래를 조합하고, `parallel`은 여러 머리카락 다발이 평행하게 흔들리게 하며, `tilt` / `offset_along` / `offset_across`는 스윙 사각형을 회전하고 평행 이동합니다. `delete`는 `bake`로 일반 키에 남길 수 있습니다. [스윙 생성](../guide/SWING.md)을 참조합니다 |
| `physics_put / physics_delete / physics_simulate / physics_fit / physics_config / physics_import` | `request` | `put/delete/simulate/fit/config/import`. ID로 임의의 물리 그룹(생성된 프리셋, 본, 스윙 그룹 포함)을 새로 만들거나 일부만 수정하고, 사용자 그룹을 지우거나 생성 값을 복구하고, 백그라운드 읽기 전용 계단 샘플로 작업 결과에 피크와 안정 시간을 반환하고, 표준 흔들림 또는 `observed_peaks` 실측 피크로 출력 배율을 조정하고, 계산 순서와 계산 FPS를 설정하고, physics3.json을 가져옵니다. [물리](../guide/PHYSICS.md)를 참조합니다 |
| `simulation_put / simulation_delete / simulation_simulate / simulation_compare / simulation_bake / simulation_clear_bake` | `request` 안의 `state`, `id` | `put/delete/bake/clear_bake`는 프로세스 작업 핸들을 반환합니다(종료 상태에는 원래 편집 결과와 베이크 진단이 있음). `simulate`, `compare`도 읽기 전용 작업 핸들을 반환하고 프로젝트를 바꾸지 않습니다. `compare`는 `motions`의 모션으로 베이크 결과와 참조 시뮬레이션을 비교하고 시각 검사를 반환합니다. 메시 위의 2D 천 / 머리카락 시뮬레이션이며, 편집기 안에서만 돕니다. `bake`는 그것을 −30…30의 `ParamSim<id>_<k>` 파라미터(`keys`개 키 포인트, `blend_shapes`가 쓰는 방식을 고르고 기본은 자동)와 맞춘 진자 `PhysicsSim_<id>`로 베이크합니다. 끄덕임, 몸 위아래, 앞뒤 기울기는 평행 이동 입력이 있는 진자 `PhysicsSim_<id>_y`가 구동하는 `ParamSim<id>_Y`로 따로 베이크하고, 물체를 움직이지 않는 입력은 참여하지 않습니다. 각 입력의 읽기 결과, 검사 모션의 시각 검사(스윙 폭, 지연, 되돌아옴, 떨림 등), R², 오차, 파라미터 사용량, 극값에 닿은 프레임 비율, 저크 비를 반환합니다. `force_inputs`는 입력을 강제로 남기고, `training_clips`는 모델 자신의 모션이 학습에 참여하게 합니다. `exaggeration`은 모드 스윙을 키우며 다시 베이크할 필요가 없습니다. `auto_bake`가 켜져 있으면(기본) `put`이 같은 단계 안에서 다시 베이크합니다. 새로 만들 때 `inputs`를 주지 않으면 기본 입력을 쓰고, 빈 배열은 입력이 없다는 뜻입니다. `output_names`는 베이크한 파라미터와 진자의 이름을 바꿉니다. `input_ranges`는 입력별로 학습 범위를 설정하고, `vertical`은 위아래 파라미터를 켜고 끕니다(`null`은 자동). `outputs`는 생성된 모드 파라미터 ID로 쓰는 ID, 범위(±1…±100), 게인(0…3)을 바꾸며, 다시 베이크할 필요가 없습니다. `obstacles`(묶음 전체 교체)는 입자가 밖에 막히는 장애물입니다. 다른 메시를 따라 변형하는 원(정점 `a`) 또는 캡슐(`a`에서 `b`), `radius`에서 `radius_b`로 변하고, `friction`은 0…1이며, 시뮬레이션과 베이크에 참여하고 p2lrt / 웹 대상으로 내보냅니다. 물리 패널이 시뮬레이션 진자에 한 덮어쓰기는 다시 베이크한 뒤 삼자 병합합니다. [시뮬레이션과 베이크](../guide/SIMULATION.md)를 참조합니다 |
| `model_apply_preset` | `request` 안의 `preset`, `state`, `layers`, `sway` | `front_hair/back_hair/clothing/auto_weights/classic_front_hair/classic_back_hair/remove_clothing`. 백그라운드에서 가중치 그룹과 프리셋 물리체를 만들고 베이크하며, 작업 핸들을 반환하고, 성공하면 히스토리 노드 하나만 늘어납니다. 머리카락 프리셋은 그 종류 머리카락의 이전 스윙 파라미터, 디포머, 진자를 제거하고, `classic_*`는 복구합니다(`sway:false`이면 전통 스윙을 끔). `remove_clothing`은 의상 프리셋 물리체를 모두 지웁니다. `clothing`은 상의, 하의, 깃 장식, 소매, 다리에 걸친 것 중 헐거운 부분만 시뮬레이션하고, 메시마다 부위(하의는 치마 / 바지 판정과 허리선, 가랑이, 밑단도 포함), 헐거움 비율, 늘어짐 시작점, 시뮬레이션 여부를 반환합니다. [시뮬레이션과 베이크](../guide/SIMULATION.md#모델-프리셋)를 참조합니다 |
| `vertex_group_update` | `request` 안의 `state`, `target`, `name` | 규칙 `fill/outline/gradient/glue/region`으로 생성하거나 `delete`합니다. 이 소프트웨어만 쓰는 정점 가중치 그룹(고정점, 강성 등)이며, 내보내지 않습니다 |
| `skeleton_get / skeleton_propose / skeleton_auto / skeleton_put / skeleton_enable / skeleton_bone / skeleton_move / skeleton_bind / skeleton_remove / skeleton_delete / skeleton_pose` | `request` | `get/propose/auto/put/enable/bone/move/bind/remove/delete/pose`. 스켈레톤을 읽거나 추론하고, 완전한 스켈레톤을 제출하고, 본과 바인딩을 편집합니다. `delete`는 스켈레톤 전체를 지우며, 그 뒤 `auto`/`put`을 다시 할 수 있습니다. `pose`는 FK/IK 파라미터 값을 구하고, 히스토리는 쓰지 않습니다 |
| `motion_list / motion_get / motion_sample / motion_put / motion_delete / motion_seed_builtin / motion_set_key / motion_delete_key / motion_remove_curve` | `request` | `list/get/sample/put/delete/seed_builtin/set_key/delete_key/remove_curve`. 보간 포즈를 읽고, 모션 클립, 파라미터 트랙, 타임라인 키프레임 편집을 유지합니다 |
| `path_get / path_list / path_preview / path_put / path_delete / path_deform` | `request` | `get/list/preview/put/delete/deform` |
| `history_checkpoint / history_list / history_checkout` | `request` | 검사점을 만들고, 히스토리를 읽거나, 노드를 전환합니다 |

파츠 설정(`eye`, `brow`, `mouth`, `face`, `body`, `hair`)은 엄격한 검증을 유지합니다. 하위 기능 설정은 `subConfigs`에 들어가고, 키는 `mouth.outline` 같은 원자 기능 유형입니다. 값은 해당 알고리즘이 선언한 수치, 물리, 의존, 색 필드와 고정된 `targets`를 받습니다. 접기 기록은 하위 인스턴스 `id`, `version`, `outputAliases`도 저장하므로, 갱신할 때 편집하지 않은 필드를 남겨야 합니다. 하위 버전은 공개 facade 버전 `1`만 지원하고, 평가는 대응 구현 버전으로 매핑합니다. 알 수 없는 버전은 실행을 거부합니다. 스트로크 스위치는 `outline`입니다. 눈썹·눈 결합의 규범 키는 `lidCoupling`입니다. 역사적 `lid`는 규범 키가 없을 때만 읽고, 명시적 규범 키 `false`가 우선합니다. 인터페이스가 이 스위치를 바꾼 뒤에는 규범 키만 쓰고, 읽기와 히스토리 전환은 이전 설정을 다시 쓰지 않습니다.

새 프리셋은 순서, 정체, 대상, 의존을 유지할 수 있는 현재 패키지 후보만 접습니다. 하위 기능이 중복되거나, 멤버가 꺼져 있거나, 중간에 다른 단계가 있거나, 외부 하위 인스턴스를 참조하거나, 버전을 명시적으로 고르거나, 패키지를 덮으면 관련 그룹은 펼친 채로 남습니다. 저장한 정확한 패키지 잠금은 자동으로 변환하지 않습니다.

모션 모디파이어의 `config`는 선택 `parameter_roles`를 지원해, 런타임 역할 이름을 파라미터 ID 배열에 매핑합니다. 깜빡임 추가 계획은 `EyeBlink`를 채웁니다. 평가는 파라미터 참조를 검사하고, 같은 역할의 호환되지 않는 정의를 거부합니다. 조회는 관계 기여에서 변화를 보고합니다. 접두사를 적용한 뒤 역할은 일반 모델 메타데이터로 저장됩니다.

고정한 뒤의 모션 조회는 `captured_ranges: {参数ID: {min, max, repeat}}`를 반환할 수 있으며, 고정할 때의 곡선 제어점을 남기는 데 씁니다. 파라미터 이름 변경, 삭제, 범위 편집에 따라 갱신되고, 저장 복구에 들어갑니다.

물리 계획은 `preset: "PhysicsHairFront"`, `"PhysicsHairBack"`, `"PhysicsEyeJelly"`로 이전 규칙을 캡처할 수 있습니다. 모션 계획은 `natural:Idle`, `natural:Blink`, `natural:Nod`, `natural:Shake`, `natural:Talk`로 자연 모션을 캡처할 수 있습니다. 물리 프리셋과 명시적으로 고른 자연 모션은 빠진 기초 바인딩을 계획에 넣습니다. 둘 다 편집 가능한 설정을 반환합니다. 파라미터를 현재 이미 평가된 모디파이어가 만들었으면, 소비자는 그 제공자를 고정할 수 있습니다. 조회의 `parameter_providers`는 파라미터 ID에서 제공자 인스턴스 ID로의 매핑을 반환합니다.

표에는 업무 필드를 적었습니다. 모든 수정은 `request_id`도 가져야 하고, 작업 공간 수정은 `project_id`와 `state`도 가져야 합니다(간소 도구 집합은 앞의 둘을 생략할 수 있습니다. [도구 집합](#도구-집합) 참조). 읽기 전용 백그라운드 샘플 `physics_simulate/simulation_simulate/simulation_compare/view_sample_motion`도 이 세 필드를 요구합니다. 중복을 제거하고 샘플 버전을 고정하기 위해서입니다. 조작마다 필드가 다르므로, 호출 전에 현재 서비스가 제공하는 JSON Schema를 읽습니다. 모든 공개 도구는 `{"request": {...}}` 포장을 통일해 씁니다. 발행과 검증은 같은 `oneOf`, `const`, 필드 제약, 설명을 유지하고, 바깥과 업무 객체 모두 알 수 없는 필드를 거부합니다. 결과는 `{"ok":true,"operation":"...","data":{...}}`로 통일합니다. 오류에는 `ok:false`와 `error.code/message`가 있고, 필드 검증 오류에는 `field`도 있습니다. PNG는 MCP 이미지 내용으로 반환합니다.

공개 조작 195개 전부(그중 백그라운드 70개, 배치 가능 91개)는 완전한 `outputSchema`를 선언해야 하고, 전체 도구 집합이 그것을 발행하며, 능력 상세의 `output_schema`와 같습니다. 성공 data와 실패 error는 엄격히 서로 배타입니다. 레지스트리는 실행과 중복 제거 경계에서 업무 결과를 검증하고, MCP는 완전한 반환 포장을 검증하며, 결과 계약이 빠지면 등록할 수 없습니다. 백그라운드 조작은 종료 상태 `job_result_schema`가 따로 있어야 하고, 백그라운드가 아닌 조작은 그 필드를 허용하지 않습니다. 능력 상세는 로컬 `$defs/$ref`로 중첩 schema를 설명하고, 자기 schema를 조회해도 검증할 수 있습니다. 실제 HTTP는 모든 루트 제약을 유지합니다. `output_contract`는 서비스 구현의 결과가 선언과 맞지 않다는 뜻이며, 수정이 이미 롤백되었다는 증거가 될 수 없습니다.

그림 추가는 `layer_import_images`를 씁니다. 필수 `state`와 절대 경로 1–128개의 `paths`가 필요하고, 선택 `parent_deformer_id`는 이미 있는 부모 디포머를 가리킵니다. 생략하면 모델 루트에 바인딩합니다. 선택 `anchor_layer_id`는 묶음 전체를 그 레이어(또는 메시) 바로 앞에 그리게 하고, `in_front=false`는 바로 뒤입니다. 묶음 안의 첫 장이 가장 앞이며, 그리기 순서는 명시적 덮어쓰기로 씁니다. 선택 `at` `[x, y]`는 각 그림을 그 캔버스 좌표 가운데에 둡니다(캔버스 경계에 붙음). `as_reference=true`는 레이어 스택의 레퍼런스 레이어로 가져옵니다(메시 없음, 내보내지 않음). 레이어 목록으로 끌어 “레퍼런스로 설정”을 고른 것과 같으며, 여전히 히스토리 노드 하나입니다. 투명 가장자리를 자른 뒤 가운데에 둡니다. 래스터는 원래 해상도를 유지하고, 캔버스를 넘으면 캔버스 사각형만 캔버스 안에 맞게 비율로 줄입니다(레이어 밀도가 1픽셀/캔버스 단위보다 큼). 메시는 캔버스 해상도로 생성합니다. 파일 하나는 최대 64 MiB, 1600만 픽셀이고, 묶음 전체는 최대 3200만 픽셀입니다. 성공 한 번은 히스토리 노드 하나만 추가하고, 아무 파일이든 실패하면 묶음 전체를 발행하지 않습니다. 파일을 읽으므로 원자 문서 배치 멤버가 될 수 없습니다. 새 원본 그림과 메시 핸들은 작업 종료 상태의 `affectedLayerIds/affectedObjectIds`에서 가져옵니다. 원본 그림 픽셀은 프로젝트에 쓰이므로, 이후 다시 열 때 입력 파일에 의존하지 않습니다. 각 그림은 저작 상태 Rig에서 자기 메시를 얻습니다(`canvas_mesh_create`, 준 부모 아래). 생성기는 그것을 다시 생성하지 않습니다. 실행 취소는 가져오기를 취소합니다.

레이어 전체 변환은 `layer_transform`을 씁니다(어떤 레이어이든: 가져온 것이나 PSD의 것, 루트나 임의 디포머 아래, 바인딩 유무와 무관). 필수 `state/layer_id`에 `matrix [a,b,c,d,e,f]`(`x' = a·x + c·y + e`, `y' = b·x + d·y + f`, 캔버스 단위, y는 아래), 또는 `translate [x,y]`, `scale`(수 또는 `[sx,sy]`), `rotate`(도, 화면에서 시계 방향), `pivot [x,y]`를 줍니다. 레이어에 이미 있는 변환과 합성합니다. 픽셀, 텍스처 타일, 텍스처 좌표는 모두 변하지 않고, 재생성을 일으키지 않습니다. 레이어의 각 메시는 정지 포즈에서 그 변환만큼 이동합니다(키폼은 오프셋을 유지). 레이어는 자기 픽셀이 어디에 표시되는지 기록하고, 페인팅이 그에 맞습니다(회전한 레이어도 같음). 실행 취소할 수 있는 히스토리 제출 한 번이며, 원자 문서 배치에 넣을 수 있습니다.

메시 재구축은 `layer_mesh_rebuild`를 씁니다(필수 `state/layer_id`). 레이어의 현재 픽셀과 메시 설정으로 그 메시를 다시 만들고, 키폼, 경로, 가중치, Glue는 새 토폴로지로 이전하며, 전체를 옮긴 레이어는 위치를 유지합니다. 메시는 이럴 때만 다시 생성됩니다. 가져오기, 이 명령, 페인팅과 그림 교체의 `rebuild_mesh` 옵션, 메시 설정 변경, 재생성입니다. 메시가 없는 레이어는 처음 페인팅해 보이는 픽셀이 생기면 메시를 만듭니다. 메시 밖에 그린 픽셀은 표시되지 않고, 인터페이스의 메시 패널이 비율을 안내하며 “현재 픽셀로 다시 만들기”를 제공합니다.

텍스처 구조로 점을 다시 배치하는 것은 `layer_mesh_structure`입니다(필수 `state/target`. target은 메시 ID 또는 `mesh:<ID>`. 선택 `settings`: `sensitivity` 0–1, `min_length` 원본 픽셀, `near_density`는 선 위 점 간격이 유지한 가장자리 행 점 간격 대비 몇 배인지(기본 1), `fill`은 선 사이에 점을 더할지(기본 아니오: 가장자리 행과 선 위에만 정점), `far_density`는 채울 때 원래 안쪽 변 길이 대비 변 길이, `bands`는 그린 주름선 양쪽에 행과 가로대를 더할지(기본 아니오), `kinds`는 `FOLD`/`SHADE`/`PATTERN` 부분집합, `max_lines` ≤64, `max_vertices` ≤16000). 바깥 윤곽 행과 가장자리 띠는 움직이지 않고, 안쪽은 텍스처의 주름, 명암 경계, 무늬 선을 따라 다시 만듭니다. 키폼, 경로, 가중치, Glue는 평소처럼 이전하고, `canvas_mesh_rebuild` 한 줄을 씁니다. 대상에 내부 조형이 있으면 검출된 TOPOLOGY 안내선을 바꿉니다(손그림과 이미 FOLD로 표시한 것은 유지). 같은 히스토리 노드에서 다시 베이크합니다(`interior_rebaked`). 메시가 되지 않거나 `max_vertices`를 넘으면 오류를 내고 아무것도 수정하지 않습니다. `layer_mesh_structure_preview`(읽기 전용, 파라미터는 같음, `state` 없음)는 인식선(유형, 채택했는지 `followed`, 띠를 더했는지 `banded`, 원본 픽셀과 캔버스 좌표)과 새 메시의 캔버스 점, 삼각형을 반환합니다. 자세한 내용은 텍스처 구조에 따른 점 재배치를 참조합니다.

시뮬레이션 모디파이어의 설정은 `bake`를 생략할 수 있습니다. 후보 준비는 백그라운드에서 그 인스턴스 이전의 실제 스택을 베이크하고, 결과를 입력 정체와 함께 제출합니다. `auto_bake: false`이면 유효한 결과를 반드시 줘야 하고, 만료된 입력은 수정 전체를 거부합니다. 미리보기도 베이크를 발행하지 않습니다. 취소와 CAS 실패는 후보 접두사를 남기지 않습니다.

`object_edit_appearance`가 생성 객체에 하는 이름 변경, 표시, 정적 겉모습, 계층 편집은 그 객체를 만든 모디파이어에 속합니다. `parameter_update`가 생성 파라미터의 이름, 범위, 기본값을 수정한 것은 그 파라미터를 제공하는 인스턴스에 속합니다. 스택을 조회하면 재생할 수 없는 덮어쓰기를 볼 수 있습니다.

`rig.scaffold`의 선택 `featureDisplacement: true`는 이전 얼굴 특징 변위의 변형 계층을 남기는 데 씁니다. 기본은 끕니다. 선택 `corrections`는 구 레시피의 `moves`와 `eyeParts` 형식을 써, 피팅 핸들과 눈 구조 교정을 저장합니다.

`mouth.outline`은 적용되는 입 메시에 위·아래 입술과 독립 텍스처를 만듭니다. `values.thickness` 범위는 0.5–8 픽셀, 기본 1.5입니다. 선택 `color`는 0–16777215의 RGB 정수를 받고, 생략하면 원 소재에서 색을 가져옵니다. 적용할 때 메시와 텍스처를 함께 고정합니다.

`expression.toggles`는 대상의 스위치와 차분 레이어에 파라미터와 투명도 키폼을 만듭니다. 차분은 이전 알고리즘의 반 칸 겹침을 쓰고, 파라미터 정의가 호환되면 재사용합니다. 스위치와 차분 파라미터는 생성 체인의 물리 입력이 됩니다.

눈 원근(`eye.see-through`)과 눈썹 원근(`brow.see-through`)은 대상 메시와 원래 앞머리 알고리즘을 씁니다. `values.opacity`는 0–100, 기본 50입니다. 조회는 복제 메시와 마스크 관계를 보고합니다. 눈 창이 이미 있는 마스크와 충돌하면 추가를 거부합니다.

깜빡임 잔진동(`eye.aftershock`)과 눈썹 되튐(`brow.spring`)은 원래 알고리즘 물리 그룹을 함께 둡니다. 설정은 `physics: {"原生组 ID": 完整物理配置}`를 줄 수 있고, 모듈 목록이 선언한 그룹만 허용합니다. 호환되는 표준 출력 물리 그룹은 재사용하고 `reused`를 보고합니다. 호환되지 않는 출력은 트랜잭션 전체를 거부합니다. 물리 그룹은 접두사 적용과 함께 일반 구성이 되고, 수정한 값을 유지합니다.

물리 설정 키는 `eye.aftershock`의 `PhysicsEyeLBounce`／`PhysicsEyeRBounce`, `brow.spring`의 `PhysicsBrowLSpring`／`PhysicsBrowRSpring`입니다. 이것은 설정 슬롯 이름이며, 실제 출력 객체 ID는 지속 인스턴스가 정합니다.

혀 내밀기(`mouth.tongue`)는 입 모양 캡처(`mouth.capture`)에 의존하고, 입 되튐(`mouth.spring`)은 턱(`mouth.jaw`)에 의존합니다. 그 `dependencies`가 해당 인스턴스를 고정합니다. 구강 깊이(`mouth.interior-depth`)는 `face/legacy` 제공자를 고정합니다. 볼 붉히기／침울(`expression.cheek`／`expression.gloom`)은 색 채널을 포함합니다. 조회는 실제 색 축 기여를 제공합니다. 입 되튐의 물리 설정 슬롯은 `PhysicsMouthSpring`입니다.

피부 자르기(`face.skin-clip`)는 얼굴 겹침과 앞머리 그림자의 마스크 관계를 추가합니다. 목 연동(`face.neck`)은 머리 기울임 바인딩과 목 그림자 마스크를 추가하고, `dependencies`로 `face/legacy` 제공자를 고정합니다. 둘 다 이미 있는 소재를 재사용하고, 새 픽셀을 만들지 않습니다. 마스크 기여의 선택 `mask` 객체에는 `target`, `before`／`after` 출처 ID 목록, `before_inverted`／`after_inverted`가 있습니다. 이것은 그 항목을 실행할 때의 실제 변경이며, 최종 결과에서 거꾸로 추론하지 않습니다.

`hair/chains`, `body.bust`, `body.arms`, `outfit.skirt`, `outfit.ribbons`, `outfit.creature`의 추가 계획에는 `body/legacy`와 `face/legacy` 운동 제공자가 들어갑니다. 체인 파라미터, 싣는 디포머, 물리 그룹의 ID는 캡처한 소재와 인스턴스 정체가 정하며, 임시 체인 번호가 바뀌어도 ID를 다시 쓰지 않습니다. 물리 기여의 `physics_slot`은 모디파이어 `config.physics`의 대응 설정 키입니다. 쓸 수 있는 키는 실제 기여를 조회해 얻습니다.

명시적 스택의 물리 조회, 시뮬레이션, 재생은 실제 평가 그룹을 읽습니다. 출처는 `modifier`이며, 이전 전역 생성 필드는 더 이상 적용 여부를 결정하지 않습니다. 고정하지 않은 생성 그룹은 소속 모디파이어로 설정합니다. `physics_put`, `physics_delete`, `physics_fit`, 이전 재정렬 인터페이스는 이 그룹을 제어하기를 거부하고 모디파이어 조작을 쓰라고 안내합니다. 일반 구성으로 고정한 뒤에는 일반 물리 인터페이스를 계속 씁니다. 이전 설정 필드는 호환 값으로 남고, 실제 상태는 스택과 물리 조회를 기준으로 합니다.

### 메시 토폴로지

`canvas_topology`(`request.mode`가 `topology`)와 편집기의 토폴로지 조작은 같은 `canvas_topology` 저작 로그입니다. 필수 `state/id/action`(`id`는 메시 ID. `vertices`는 현재 메시의 정점 인덱스, 기본은 빈 값). `anchors`의 각 항은 이미 있는 정점 `{vertex}` 또는 자유점 `{x, y}` 중 하나이고, `add_points`는 자유점만 받습니다. 거부할 때 오류 코드는 `topology_refused`입니다. 인덱스는 현재 메시에서 오므로 최신 `state`를 써야 합니다. `mesh_get_topology`로 정점 인덱스와 정지 좌표(`add_points` 앵커가 쓰는 메시 자신의 공간), 삼각형, 윤곽 변을 읽습니다. 제출이 성공하면 결과의 `new_vertices`가 그 조작이 새 메시에서 새로 만들거나 선택으로 남긴 정점 인덱스를 나열합니다(`add_points`는 새로 만든 점, `knife`는 절단선 위의 점, `merge`는 병합 뒤의 점). 배치 멤버는 이 필드를 반환하지 않습니다. `action`:

| `action` | 입력 | 결과 |
| --- | --- | --- |
| `merge` / `duplicate` / `delete` | `vertices` | 한 점으로 합치고, 정점과 그것이 완전히 덮는 면을 복제하고, 지운 뒤 구멍을 메웁니다(정점은 최소 세 개 유지) |
| `connect` | `vertices`가 정확히 둘 | 두 점 사이 직선이 자르는 변을 나누고, 새 변으로 잇습니다. 이미 연결되어 있거나 직선을 따라 새 변을 자를 수 없으면 실패합니다 |
| `subdivide` / `split` | `vertices` 또는 `edges` | 선택한 정점이 관련된 변, 또는 준 변을 세분합니다 |
| `knife` | `anchors` | 꺾은 선을 따라 변을 더합니다(편집기 점 배치 도구의 「선」). 각 앵커는 메시 위에 떨어지고, 이웃 앵커 사이가 변으로 이어집니다. 앵커는 `{vertex}` 또는 메시 자신의 정지 공간 `{x, y}`입니다 |
| `add_points` | `anchors`(`{x, y}`, 메시 자신의 정지 공간, `knife`와 같음) | 점마다 정점 하나를 더합니다. 삼각형 안은 1→3, 변 위는 변을 나누고, 메시 밖은 가장 가까운 보이는 윤곽 변에서 삼각형 하나를 넓힙니다. 이미 있는 정점 위에 떨어진 점은 무시합니다. 새 정점의 UV, 키폼, 블렌드 셰이프, 가중치는 주변 정점에서 같은 가중치로 얻고, 이어서 텍스처를 바꾸지 않는 범위에서 변을 뒤집습니다 |
| `delete_edges` | `edges`(`[[a, b], …]`, 반드시 줌. `vertices`가 어느 변을 지울지 결정하지 않음) | 내부 변은 그 사각형 안에서 다른 대각선으로 바꿉니다(사각형이 정지 형태, 어느 키폼, 블렌드 셰이프에서 볼록하지 않으면 실패). 윤곽 변은 속한 삼각형을 지우고, 더 이상 쓰지 않는 정점도 함께 지웁니다. 정점이 점으로만 닿거나 삼각형을 모두 지우면 묶음 전체가 실패합니다 |
| `retriangulate` | `vertices`(빈 배열은 메시 전체) | 이 정점 주변에서 Delaunay 쪽으로 변을 뒤집습니다(기본 포즈의 캔버스 공간에서 판정). 텍스처 매핑이 변하지 않고, 정지 형태와 각 키폼, 블렌드 셰이프에서 모두 볼록한 사각형만 뒤집습니다. 정점과 키폼은 변하지 않습니다. 뒤집을 것이 없으면 변화 없음입니다(`applied:false`, 히스토리를 추가하지 않음) |

조작이 적용되지 않으면 오류를 반환하고, 아무것도 쓰지 않습니다. 키폼, 블렌드 셰이프, 정점 그룹, Glue는 토폴로지와 함께 이전합니다(지운 정점의 Glue 쌍은 삭제). 경로는 다시 바인딩합니다. 대상 메시에 내부 조형이 있고 바꾸기 전과 같으면, 같은 노드 안에서 새 메시로 다시 베이크합니다(베이크 실패는 편집을 거부하지 않고, 조형이 메시가 바뀌었다고 보고). 결과는 [기하 검사](#기하-검사와-시험-실행)를 통과해야 합니다. 알고리즘과 Cubism 메시 편집의 대조는 메시 토폴로지를 참조합니다.

### 오브젝트 복사, 찾기 바꾸기, ID 전환

`object_paste`(`sources`는 `mesh:/warp:/rotation:/part:/parameter:` 핸들. 선택 `parent`, `part`, `parameter_group`, `ids`)는 메시, 디포머(하위 트리와 그 안의 메시 포함), Part(그 자식 포함), 파라미터를 복사합니다. 새 ID는 기록할 때 정해집니다(기본은 `<ID>_copy`, 충돌하면 번호를 더하고, `ids`로 항목마다 지정 가능). 재생 결과와 ID는 고정되어 같습니다. 복제본 사이의 부모, 마스크, Glue, 경로, 정점 그룹은 복제본을 가리키게 바꾸고, 복사하지 않은 객체를 가리키는 참조는 남깁니다. `parent`를 주면 메시는 캔버스 좌표로, 디포머는 국소 좌표로 다시 겁니다. 붙여넣기는 저작 상태 Rig 위에서 계획합니다. 스윙, 시뮬레이션, 내부 조형이 생성한 객체는 그 안에 없으므로, 그것들을 지목하면 거부되고, 하위 트리를 복사할 때도 가져가지 않습니다. 복제본은 그 레이어의 메시로 치지 않습니다. 레이어로 메시를 찾을 때(집어 올리기, 분할, 삭제 때 메시 ID 유지)는 원 메시만 인정하고, 레이어를 지우면 복제본도 함께 지워집니다. 복제본은 자기 메시 ID로 그리기 순서를 설정하고, 레이어 전체 변환은 복제본을 움직이지 않습니다. 단독 호출이면 결과의 `ids`가 원 핸들에서 새 ID로의 매핑을 주며, 쓴 기록과 같습니다(요청은 캡처 버전으로 새 ID를 모두 고정한 뒤 제출). `paths`는 메시와 함께 복사된 변형 경로를 줍니다(`path:<原 ID>`에서 새 ID). 배치 멤버는 `changed`에서 새 핸들을 읽습니다.

`mesh_duplicate`(`source`, `targets`)는 원본 메시의 토폴로지를 캔버스 위치로 각 대상 메시에 씌우고, UV는 대상 자신의 텍스처를 씁니다. 각 대상은 `canvas_mesh_rebuild` 한 줄을 쓰고, 키폼, 경로, 가중치, Glue는 평소처럼 이전합니다.

`object_find`(읽기 전용)는 이름이나 ID(`field`)를 `kinds`에서 찾습니다. `find`/`replace`, `regex`, `match_case`, `prefix`, `suffix`, `swap_sides`(독립된 L/R, Left/Right, 左/右 교환), `standard_ids`(Cubism 2식 ID를 Cubism 3 표준 ID로)를 지원하고, 항목마다 바꾸기 전과 후를 반환합니다. `object_rename`은 같은 파라미터로 한 번에 제출합니다. 둘은 저작 상태 Rig의 객체만 나열하고, 생성기가 로그 뒤에 넣은 객체는 생성기가 준 이름과 ID를 유지합니다. ID로 이름을 바꾸는 것과 `object_convert_id`(`renames`: `target`과 새 `id`)는 모두 `convert_id` 로그 항목 한 줄을 씁니다. 그것을 참조하는 키폼, 블렌드 셰이프, 마스크, Glue, 경로, 정점 그룹, 모션 곡선, 표정, 포즈, 히트 영역, 사용자 물리, 스윙 대상이 동시에 이름이 바뀝니다(아직 문서에 넣지 않은 표준 표정도 새 ID로 표준 파라미터를 찾음. 앞머리 투과 복제본은 원본을 따라 ID가 바뀌고, 복제본 자체는 전환, 붙여넣기, 메시 복사를 할 수 없으며 `generated_copy`로 거부). 이후 생성 덮어쓰기와 미룬 패널 편집은 새 ID로 재생하고, 내장 물리, 모션 프리셋, 표정은 새 ID로 표준 파라미터를 다시 찾습니다. 파라미터 스냅샷, 각 작업 공간의 저작 포즈, 타임라인 북마크, 숨김, 동결은 같은 제출에서 새 ID를 쓰고, 실행 취소하거나 전환 전으로 체크아웃하면 원래 ID로 돌아갑니다. ID는 문자로 시작하는 1–64자의 ASCII 문자, 숫자, 밑줄이어야 합니다. 같은 종류의 이름 중복, 이미 사용 중, 생성기가 소유한 객체(시뮬레이션, 내부 조형, 스윙 파라미터)는 거부되고 아무것도 수정하지 않습니다. 본에 바인딩되거나 가중치가 있는 메시, 본 파라미터, 본 디포머도 거부됩니다(본은 로그 전에 원래 ID로 베이크). 반대로 본은 ID를 바꾼 메시나 로그가 새로 만든 메시(붙여넣기 복제본, 캔버스에서 새로 만든 메시)에 새로 바인딩할 수 없고, 자동 스켈레톤은 그것들을 건너뜁니다. 대상 ID에 지운 파라미터의 모션 곡선이나 표정 항목이 남아 있으면 전환을 거부하고 어느 모션이나 표정인지 가리킵니다. 분할, 레이어 삭제, 재생성 때 기초 단계는 전환 전 ID로 메시를 유지한 뒤 로그 항목이 이름을 바꿉니다. 재생성 삼자 병합도 생성 결과를 전환 후 ID로 환산합니다. 내보내기 전에 실제로 내보내는 모델의 ID를 검사합니다. 같은 종류(Warp, 회전, 런타임 가상 본이 공유. 파라미터 폴더, 시뮬레이션, 충돌체 포함)의 이름 중복은 내보내기를 거부하고, 문자가 규격에 맞지 않으면 경고만 합니다.

### 텍스처와 텍스처 아틀라스

레이어의 캔버스 사각형(캔버스 단위, 소수 가능)과 래스터 픽셀은 서로 독립입니다. 네이티브 밀도는 캔버스 단위당 래스터 픽셀이고, 텍스처 아틀라스 타일은 `栅格像素 × 密度 × fit`을 저장합니다. 밀도 기본은 1입니다. 예산을 넘으면, 잠그지 않은 타일이 fit 하나(≤1)를 공유해 함께 줄어들고, 잠금과 고정 위치를 넣을 수 없으면 취소됩니다(설명은 프로그램 로그에만 쓰고, 도구 결과에는 들어가지 않음). 기하는 텍스처 아틀라스를 알지 못합니다. 밀도, 예산, 고정 위치, 재배치는 타일과 바인딩 UV만 바꾸고, 메시는 바꾸지 않습니다.

- `layer_get_texture`(조회): `layer_id`. `canvas_rect`, 정수 외접 `bounds`, `raster`, `native_density`, `override{density,lock,pin}`, `deleted`, `tile`(페이지, 텍스처 픽셀 사각형, 래스터 픽셀당 텍스처 픽셀 `scale_x/scale_y`, 밀도, 잠금과 고정. 투명하거나 삭제한 레이어는 null), `atlas_fit`을 반환합니다. 모두 같은 캡처 버전에서 옵니다.
- `atlas_get`(조회): 선택 `page`로 타일을 거릅니다. 유효 `budget{page_size,max_pages,padding}`, `fit`, `notices`, `auto`(매번 자동 배치인지), `pages[{index,width,height,tile_count,occupancy}]`, `tiles`를 반환합니다. `occupancy`는 메시로 배치한 타일에서 메시가 실제로 쓰는 셀만 셉니다. `notices`는 페이지 수가 예산을 넘었는지, 페이지에 넣으려고 설정 밀도보다 낮게 저장한 타일을 가리킵니다. 저장한 배치는 `fit`을 유지하므로, 더 높은 해상도 이미지로 바꾼 뒤에도 이 두 경우는 `fit`에 나타나지 않습니다. 텍스처 편집과 텍스처 편집이 들어 있는 `workspace_apply_edits` 결과에도 `atlas_fit`과 `notices`가 있습니다. `atlas_set_budget`에 `atlas_pack`을 더하면(교체와 같은 배치에 둘 수 있음) 예산 안으로 되돌릴 수 있습니다.
- `atlas_render_page`(읽기 전용 백그라운드 작업, `request_id/project_id/state` 필요): `page`, 선택 `max_size`(64–16384, 기본 2048, 긴 변 기준으로 축소). 종료 상태에는 `revision`, 원래 크기와 렌더 크기, `sha256`, 그 페이지의 레이어가 있고, PNG는 이미지 내용으로 반환하며, `job_get/job_wait`로 그림을 다시 가져올 수 있습니다.
- `layer_set_canvas_rect`: `layer_id`, `rect{left,top,width,height}`. 레이어를 이동하고 축소·확대해, 캔버스가 그 사각형에서 픽셀을 표시하게 합니다. 현재 표시 위치에서 출발하는 `layer_transform` 한 번입니다(회전한 레이어는 거부하고, `layer_transform`을 씀). 픽셀, 텍스처, 생성 입력은 변하지 않고, 메시는 키폼과 함께 이동합니다. 어떤 레이어든 가능합니다. `layer_get_texture`의 `canvas_rect`는 레이어가 현재 표시되는 위치입니다. 같은 사각형은 변화가 없습니다.
- `layer_replace_image`: `layer_id`와 `path`(절대 경로, PNG/WebP/TIFF/BMP, 단일만), `png_base64`, `material_id`(소재 라이브러리의 소재. 레이어가 그 소재와 연관) 중 하나. 선택 `fit`(`stretch`는 기본으로 가득 채움, `contain`은 비율을 유지해 가운데 맞추고 투명을 채움)과 `rebuild_mesh`(기본 false). 캔버스 사각형은 변하지 않고, 생성 입력은 첫 교체 전의 픽셀에 얼리며, 메시, 키폼, 바인딩은 남고, 타일과 UV만 바뀝니다. `rebuild_mesh=true`이면 새 픽셀로 그 레이어 메시를 다시 생성하고, 저작 편집이나 실체화 기하가 있으면 거부합니다. 단일은 먼저 상태를 대조한 뒤 파일을 읽습니다. 배치 멤버는 `png_base64`만 받고, `path`를 주면 배치 전체가 `invalid_edit`로 실패합니다. 최대 16MP이며, 픽셀이 같으면 변화 없음입니다.
- `atlas_check_placement`(조회): `placements`는 1–128개 `{layer_id, page?, x?, y?, rotation?, density?}`입니다. 생략한 필드는 제출된 값을 이어 씁니다. `clear`와 각 타일의 목표 `page/x/y/width/height/rotation`, `outside_page`(회전 뒤 외접 상자가 페이지를 넘거나 페이지 번호가 예산을 넘는지), `overlaps`(메시가 만날 타일)를 반환합니다. 다른 제출된 타일 및 이번에 함께 검사하는 타일과 비교하며, 규칙은 제출 때와 완전히 같습니다. 두 타일이 간격만큼 바깥으로 늘린 외접 상자가 만나면, 그때 메시가 실제로 쓰는 셀(간격을 바깥으로 늘림)로 판단하고, 둘 다 메시가 없고 회전하지 않았으면 사각형으로 판단합니다. 텍스처 아틀라스 뷰에서 드래그, 확대·축소, 회전할 때도 이 규칙을 씁니다. `clear`인 배치는 반드시 그대로 놓이고, 충돌이 있는 배치는 아래 명령이 거부합니다.
- `layer_set_pixel_density`: 유일한 `layer_ids` 1–128개, 필수 `density`(1/64–16 연속 값, `null`은 1로 복구), 선택 `lock`(생략하면 원래 값 유지). 배치가 이미 있으면 각 타일은 왼쪽 위를 유지하고, 커진 뒤 페이지를 넘거나 다른 타일의 메시와 만나면 `tile_collides`로 거부하고 아무것도 수정하지 않습니다.
- `atlas_set_tile`: `layer_id`와 `pin{page,x,y,rotation?}`(텍스처 픽셀. `x`, `y`는 회전하지 않은 사각형의 왼쪽 위, `rotation`은 타일 중심을 축으로 한 임의 각도, 기본 0) 또는 `null`로 빈 영역에 넣습니다. 페이지 번호는 예산 안이어야 합니다. 위치는 배치(`atlasArrangement` 설정)에 저장합니다. 자동 배치 텍스처 아틀라스는 먼저 현재 배치를 저장하고(`auto`가 false가 됨), 배치가 이미 있으면 먼저 현재 위치로 각 타일을 저장하고 메시 커버 영역을 보탭니다. 그래서 다른 타일은 움직이지 않고, 옮긴 뒤 빈 자리로 밀려 들어가지도 않습니다. 회전한 뒤 페이지를 넘거나 다른 타일의 메시와 만나는 위치는 `tile_collides`로 거부하고, 아무것도 수정하지 않습니다. 먼저 `atlas_check_placement`로 검사합니다. 이동과 확대·축소를 동시에 하려면(예: 대각을 유지한 확대·축소, 또는 두 타일 위치 교환), `layer_set_pixel_density`와 `atlas_set_tile`을 같은 `workspace_apply_edits`에 넣습니다. 배치 전체 이후의 배치만 검사하며, 텍스처 아틀라스 뷰의 “적용” 조정 세션과 완전히 같습니다.
- `atlas_set_budget`: `page_size`(256–16384의 2의 거듭제곱), `max_pages`(1–64), `padding`(0–32), `auto`. 적어도 한 항을 주고, 생략한 항은 현재 값을 이어 씁니다. 예산은 `atlas` 설정에 씁니다. `auto=true`는 저장한 배치를 지우고, 그 뒤 재구축마다 사각형 자동 배치(MaxRects)를 합니다. `auto=false`는 현재 배치를 저장합니다.
- `atlas_pack`: 한 번에 배치하고 결과를 저장합니다(`auto`가 false가 됨). 선택 `shape`: `mesh`(기본)는 각 레이어 최종 메시의 텍스처 좌표 커버 영역(칸마다 래스터 픽셀 4, 간격 추가)으로 배치하고, 사각형은 서로 끼워 넣을 수 있으며, 각 타일은 자기 셀만 씁니다. `rect`는 사각형 기준입니다. 점유 면적이 큰 것부터 놓고, 예산 페이지 수에 들어가지 않으면 잠그지 않은 모든 타일이 1/64 간격으로 함께 줄어듭니다. 선택 `layer_ids`는 이 타일만 옮기고, 나머지는 원래 자리와 원래 fit을 유지합니다. 동시에 자동 배치의 고정 위치와, 더 이상 없는 레이어의 커버를 지웁니다. 같은 입력으로 다시 배치하면 변화가 없습니다. 그 뒤 메시 편집이 배치 때의 커버 영역을 넘으면, `atlas_get`의 `notices`가 다시 배치하라고 알립니다. 타일 `shaped`는 메시 커버 영역으로 배치했음을 나타냅니다.

편집 여섯 가지는 모두 프로세스 작업이자 원자 배치 멤버이며, `WorkspaceTextureEdits` 순수 후보, 완전한 재구축, CAS로 제출합니다. 성공 한 번은 히스토리 노드 하나만 추가하고, 실행 취소는 이전 타일을 복구합니다. 종료 상태는 `project_id/state/history_node_id/revision/applied/layers/atlas_fit`(`geometry_diagnostics`를 포함할 수 있음)이며, `layers`는 텍스처가 실제로 바뀐 레이어입니다.

## 프로젝트와 내보내기 작업

새로 추가한 상호작용 업무 입구도 `workspace_get_operation`이 전체 schema를 제공합니다.

| 도구 | 공통 동작 |
| --- | --- |
| `preview_pose` | 저작 포즈와 선택한 모션의 자동 키 찍기가 같은 CAS입니다. 임시 재생 프레임은 이 편집을 일으키지 않습니다 |
| `preview_playback / preview_playback_get / preview_physics` | 프로세스가 소유하는 재생, 단일 모션, 애니메이션, 마우스 추적, 물리 평가입니다. 임시 프레임은 프로젝트 포즈나 히스토리에 쓰지 않습니다 |
| `canvas_deform_stroke` | brush/smooth/inflate, 여러 메시, 연결 제한, Glue 유지입니다. 샘플은 캔버스 픽셀이고, edit는 이미지를 남기며, deform은 정확한 키폼을 씁니다. 획 전체가 제출 한 번입니다 |
| `canvas_glue_edit / vertex_group_paint` | Glue 브러시 연결, 방향 가중치, 바인딩 해제, 다시 연결, 시뮬레이션 가중치 브러시/그라데이션/반전입니다. 연속 Glue 순서와 애니메이션 채널을 유지합니다 |
| `layer_draw_order` | `target`으로 0–1000의 명시적 그리기 순서를 설정합니다. `order:null`은 원래 생성 또는 저작 애니메이션 순서로 재설정합니다 |
| `hit_area_put / hit_area_delete / hit_area_preset` | 문서의 클릭 영역(`rigEdits.hitAreas`)입니다. 배치할 수 있는 문서 편집이며, 인스펙터와 계층 트리 오른쪽 클릭 메뉴와 공유합니다. `put`은 `id`(Cubism ID 알파벳, 예: `HitAreaHead`)로 새로 만들거나 수정하고, `meshes`는 `mesh:<id>` 목록입니다(새로 만들 때 필수, 주면 통째로 교체). `name`은 호스트가 조회하는 이름입니다(기본은 `HitArea` 접두를 뺀 ID). `new_id`는 이름을 바꾸고, `before_id`는 다른 영역 앞에 둡니다(`null`은 맨 뒤). `delete`는 `id`로 지웁니다. `preset`은 의미에 따라 Head(얼굴)와 Body(상의, 없으면 목 또는 하의)를 넣고, 같은 ID가 이미 있으면 유지합니다. 영역은 목록 순서로 판정하고, 먼저 맞은 것이 우선입니다. 내보내기는 model3.json `HitAreas`(항목마다 ArtMesh 하나, 메시가 여러 개인 영역은 같은 이름의 여러 항목)와 `.p2lrt`의 `HITA`에 쓰며, 영역이 없으면 런타임은 자동 Head/Body를 씁니다. 단일 호출의 `changed`는 `hit_area:<id>`로 관련 영역을 나열합니다(이름을 바꾸면 이전·새 ID가 모두 있고, `preset`은 새로 더한 영역을 나열) |
| `hit_area_list / hit_area_test` | 조회이며, 한 번 제출된 캡처에서 옵니다. `list`는 테스트 순서로 각 영역의 현재 메시와 더 이상 없는 `missing` 메시를 반환하고, `automatic=true`는 영역을 하나도 설정하지 않았다는 뜻입니다. `test`는 캔버스 점 `x, y`(캔버스 단위, y는 아래), 선택 `pose` 포즈에서 런타임 규칙으로 히트 테스트합니다(메시가 보이고, 불투명도가 0보다 크고, 삼각형이 그 점을 포함). `hit`와 맞은 영역의 `id/name/meshes`를 반환합니다. `pose`에 모델에 없는 파라미터가 있으면 거부하고, 프로젝트를 불러오지 않았으면 `invalid_state`입니다 |
| `warp_get_controls` | 한 번 제출된 캡처에서 Bezier 구간 수와 편집 제어를 읽습니다. `detail=summary`는 제어점이 없고, `points`는 앵커, 접선 순서로 페이지를 나눕니다(페이지당 최대 256). 좌표는 부모 국소 공간이며, `persisted=false`는 제어가 샘플 격자점에서 재구축되었음을 뜻합니다. CMO3는 샘플 기하와 구간 정보만 저장하고, 이 편집 제어는 넣지 않습니다 |
| `mesh_get_topology` | 한 번 제출된 캡처에서 메시 하나(`target`은 `mesh:ID`)의 토폴로지를 읽습니다. `canvas_topology`가 인덱스로 가리키는 내용입니다. `section`은 `vertices`(기본, `[索引, x, y]`, 메시 자신의 정지 공간), `triangles`(`[索引, a, b, c]`), `outline`(삼각형 하나만 쓰는 윤곽 변 `[a, b]`) 중 하나이며, `offset`/`limit`(1–4096, 기본 1024)으로 `items`에 페이지를 나눕니다. 정점 수, 삼각형 수, 윤곽 변 수, `bounds`는 항상 반환합니다. 반환된 `state`를 `canvas_topology`에 넘겨야 인덱스가 유효합니다 |
| `warp_set_topology` | 백그라운드에서 일반 키폼과 블렌드 키폼을 모두 다시 샘플링합니다. 부모 국소 공간의 실제 표면 샘플 오차를 반환합니다 |
| `warp_bezier_divisions / warp_bezier_anchor / warp_bezier_handle / warp_bezier_reset` | 백그라운드에서 구간 수, 앵커, 접선을 저장하거나 재설정합니다. 제어 좌표는 부모 국소 공간이며, 완전한 편집 제어와 샘플 격자점을 유지합니다 |
| `swing_preview / swing_preview_get / swing_preview_render / swing_preview_commit` | 비공개 스윙 초안, 시청 시계, PNG 관찰입니다. 확인은 히스토리 제출 한 번이고, 취소는 초안을 발행하지 않습니다 |
| `skeleton_draft_open / skeleton_draft_list / skeleton_draft_get / skeleton_draft_edit / skeleton_draft_preview_transfer / skeleton_draft_commit / skeleton_draft_cancel` | 본 편집 도구와 공유하는 비공개 스켈레톤 초안입니다. 열 때 자기 포즈 CAS로 정지 포즈로 돌아가고, 반환된 `state`는 초안 계보입니다. `edit`는 1–128개의 유형 있는 의도(일괄 변환, 복사/미러, 세분/해소, 꼬리/날개 체인, 유연 본 체인 `flex`(분기는 유연 본 하나 아래 유연 자식 본 여러 개), 곧게 펴기 `straighten`(먼저 관절을 원화 중심선에 맞추고, 정지는 곧으며, 원화는 포즈로 저장), 가중치 칠하기/정리/지우기/이전, `revert` 등)로 초안을 전부 또는 전무로 수정합니다. `commit`은 그 계보에서만 CAS하고, 이후 포즈/문서 수정, 프로젝트 다시 열기, 새 초안은 모두 충돌하며, 스켈레톤이 변하지 않으면 히스토리 노드가 생기지 않습니다 |
| `physics_audition / physics_audition_step / physics_audition_get` | 물리 패널 진자와 공유하는 선택한 그룹의 시청 세션입니다. 문서, 히스토리, 저장한 포즈를 바꾸지 않습니다. `start`는 작업 공간 저작 포즈(`values`를 겹칠 수 있음)로 `group_id`를 구동하고 그 작업 공간의 이전 세션을 끝냅니다. `target`은 진자 뿌리를 −1…1로 드래그하고, `release/reset/reset_peaks/stop`이 있습니다. `step`은 dt ≤ 0.1초로 1–240스텝을 진행하고, 복제본 위에서 풀며, 거부된 요청은 접두사를 남기지 않습니다. `get`은 시계를 진행하지 않습니다. 같은 로드 안의 문서 편집은 그 그룹을 갱신하고, 프로젝트를 다시 열거나 그룹을 지우면 세션은 `stale`입니다. 반환된 `peaks`는 `physics_fit`의 `observed_peaks`로 바로 쓸 수 있습니다 |
| `canvas_visibility / canvas_visibility_get` | `workspace_id/canvas_id/mode`(edit/preview)로 주소하는 국소 레이어/디포머 표시와 solo입니다. 계층 트리의 눈과 solo와 처리기를 공유합니다. `action`은 `layers/deformers/all_layers/invert_layers/solo/unsolo`이며, 수정은 `state`를 진행하지만 문서, 히스토리, 다른 캔버스, 내보내기는 바꾸지 않고, 변화가 없으면 진행하지 않습니다. 조회는 각 캔버스 세션의 표시, solo 기록, `hidden_layer_ids`를 반환하고, 편집하지 않은 캔버스는 모두 보입니다. 내보내기 가시성은 여전히 `object_edit_appearance`가 수정합니다 |
| `physics_preset_list / physics_preset_put / physics_preset_rename / physics_preset_delete / physics_apply_preset` | 안정 ID의 전역 입력/진자 프리셋 라이브러리입니다. 라이브러리 수정은 `library_state`를 쓰고, 적용은 배치할 수 있는 문서 편집입니다 |
| `simulation_preview / simulation_preview_step / simulation_preview_get / simulation_preview_render` | 비공개 실시간 시뮬레이션 장면입니다. 시작/재시작/정지와 명시적 dt 스텝은 백그라운드 세션이고, 읽기/렌더는 시계를 진행하지 않습니다 |
| `paint_session_begin / paint_session_list / paint_session_control / paint_session_commit` | 비공개 여러 획 초안, 실행 취소/다시 실행/이동/색 추출/PNG입니다. 제스처와 색 추출은 캔버스 단위를 쓰고, 초안은 레이어 자신의 래스터입니다(`width/height` 픽셀이 `canvas_rect`를 덮음). 확인 전에는 프로젝트 픽셀을 수정하지 않고, 확인은 히스토리 제출 한 번입니다. `paint_session_begin`에 `material_id`(`layer_id` 대신)를 넘기면 소재 라이브러리 항목 자신의 픽셀에 그립니다. 결과에 `material_id`가 있고, 다른 편집 제출은 세션에 영향을 주지 않으며, 그 소재 픽셀이 바뀌어야 무효가 됩니다. 제출은 `material_set_pixels` 한 번으로 쓰이고, 그 소재를 표시하는 레이어가 함께 갱신되며, `rebuild_mesh`는 적용되지 않습니다. `paint_session_control`의 `select` 동작은 세션 선택 영역을 바꿉니다(`rectangle` / `ellipse` / `lasso` / `wand` / `quick`—`points`와 `radius`로 이루는 한 획 브러시, 비슷한 색을 따라 가장자리까지 확장, `tolerance` 기본 32—또는 `opaque`—레이어 투명도—를 `op`로 새로 만들기, 더하기, 빼기, 교차, 또는 `all` / `none` / `invert` / `feather` / `expand` / `contract`. 뒤의 셋은 `radius`를 씀). 이후 제스처와 획은 선택 영역 안에만 떨어집니다. 선택 영역은 획 히스토리에 들어가지 않고, `session.selection`은 그 래스터 외접 상자입니다. 제스처에는 `transform`(`matrix` `[a,b,c,d,e,f]`는 캔버스 단위 아핀 사상. 선택 영역 안 픽셀을 이동—선택 영역이 없으면 레이어 전체 내용—하고 선택 영역도 함께 이동. `smooth:false`는 가장 가까운 픽셀로 샘플), `blur`(`opacity`가 강도), `fill`(선택 영역 외접 상자 안의 선택 영역을 채움. 선택 영역이 없으면 레이어 원래 영역과 이미 그린 영역을 채움), `gradient`(`end_color`, `kind: linear / radial`)가 있습니다. 페인트 통은 `contiguous:false`를 넘길 수 있고, 각 제스처는 `lock_alpha`로 이미 있는 픽셀만 바꿀 수 있습니다(`eraser` / `clear`는 받지 않음. 브러시로 배경색을 칠하세요). `brush` / `pencil` / `eraser` / `blur`는 `symmetry`(`vertical` / `horizontal` / `both`)를 넘길 수 있으며, 캔버스 중심선을 따라 미러하고, 미러와 원래 획은 한 획에 속하며, 겹친 곳은 한 번만 찍습니다. 편집기의 대칭과 같습니다. 올가미는 최대 4096점입니다 |
| `motion_create / motion_duplicate / motion_rename / motion_properties / motion_pose / motion_move_keys / motion_delete_keys / motion_paste_keys / motion_replace_keys / motion_preset / motion_close_loop` | 모션 수명, 포즈 키 찍기, 키 이동/삭제/붙여넣기/교체, 생성 프리셋입니다. 모두 원자 배치에 참여할 수 있습니다. `motion_close_loop`는 나열한 곡선(기본은 전부)의 처음과 끝을 잇습니다. 0과 끝에 각각 키프레임이 있고, 끝은 시작 값을 씁니다(순환 파라미터는 도는 방향으로 정수 바퀴를 취하며, 64바퀴를 넘으면 오류). 끝으로 들어가는 구간이 이징이면 끝은 시작의 기울기로 들어갑니다. `id`가 없는 `motion_create` / `motion_duplicate`의 새 ID는 타임라인 보조 데이터가 남아 있는 지운 모션 ID를 건너뜁니다. `parameter_create` / `parameter_update`가 `repeat: true`로 설정한 파라미터는 키프레임이 범위를 최대 64바퀴까지 넘을 수 있고, 내보내기는 원래 값을 쓰며 런타임이 감아 되돌립니다(Cubism 5.3 대상이 필요. 더 이전 대상은 손실 보고서에 범위를 넘는 모션 곡선을 항목별로 나열하고, 런타임은 자름). `cmo3`는 `isRepeat`를 씁니다 |
| `motion_set_event / motion_delete_event / motion_events` | 모션 Event(motion3 `UserData`)의 쓰기, 이동(`from_time`), 삭제입니다. `motion_events`는 모든 Event와 메시 사용자 데이터를 읽기 전용으로 나열합니다 |
| `expression_create / expression_put / expression_update / expression_set_entries / expression_capture / expression_delete / expression_duplicate / expression_move / expression_reset / expression_list / expression_get` | 표정(exp3)입니다. 포즈, 스냅샷, 현재 파라미터에서 새로 만들고, 항목 혼합 방식은 `ADD` / `MULTIPLY` / `OVERWRITE`, 페이드 인·아웃과 순서입니다. 편집은 원자 배치에 참여할 수 있고, 이번에 쓰는 항목만 검증하며, 읽기 결과의 `missing`은 Rig에 더 이상 없는 파라미터를 나열합니다 |
| `pose_put / pose_add_group / pose_set_group / pose_delete_group / pose_update / pose_clear / pose_get` | 포즈(pose3) 그룹, 링크 파츠, 전환 페이드 인입니다. 편집은 원자 배치에 참여할 수 있고, 새로 넣은 파츠만 검증하며, `pose_get`의 `missing_parts`는 Rig에 더 이상 없는 파츠를 나열합니다. 그룹은 인덱스로 주소하고, 삭제한 뒤 그 다음 그룹 인덱스는 앞으로 이동합니다. 같은 배치에서는 먼저 고친 뒤 지우거나, 큰 인덱스부터 지웁니다 |

제어 요청은 여전히 수정 중복 제거와 작업 공간 토큰 규칙을 따릅니다. 세션은 자기 토큰을 따로 가질 수 있습니다. 전역 물리 라이브러리는 독립 라이브러리 토큰을 씁니다. 읽기, 렌더, 재생 프레임, 비공개 초안은 문서 제출과 같지 않습니다. `kind/job_backed/batchable`로 메타데이터를 찾아 실행 방식을 구분합니다.

GUI와 MCP의 프로젝트 저장, 열기는 같은 애플리케이션 층 수명 주기 인터페이스를 호출하고, 데스크톱 어댑터가 가진 프로젝트 컨트롤러가 실행합니다. GUI 입구는 먼저 사용자에게 저장하지 않은 수정을 확인한 뒤 포기 플래그를 분명히 넘길 수 있습니다. MCP는 확인 대화 상자를 호출하지 않고, 기본적으로 저장하지 않은 전환을 거부합니다. GUI 호출은 시작할 때의 프로젝트와 상태를 유지하고, 신뢰할 수 있는 사용자 신분으로 제출합니다.

GUI 필드 완료는 애플리케이션 층 큐에서 비동기로 재구축하고 제출하며, 저장은 먼저 큐에 있는 완료를 기다립니다. 거부된 초안은 저장으로 프로젝트를 암묵적으로 덮을 수 없습니다. 큐는 자기 연속 제출만 받고, 외부 수정이나 프로젝트 다시 열기는 여전히 이전 상태를 무효로 만듭니다. 조회는 제출된 캡처를 계속 읽고, 아직 끝나지 않은 인터페이스 초안은 발행하지 않습니다. `workspace_inspect`와 스켈레톤/모션 복합 조회는 같은 독립 세션을 쓰고, 반환된 상태, 모델, 설정, 히스토리는 같은 캡처에 속합니다. 조회 동안의 제출이나 다시 열기는 이 결과에 섞이지 않습니다. `scope=preview`는 제출된 포즈/잠금을 읽습니다. 애니메이션 평가 프레임과 아직 제출하지 않은 GUI 값은 그 포즈를 대신하지 않습니다. 목록 찾기는 `items/total`과 적용되는 `next`를 반환합니다. inspect 결과에는 `scope`가 있고, 대상 상세는 `object`로 고정되며, 빈 페이지도 작용 범위를 구분할 수 있습니다. `scope=settings`는 기본 항목을 채운, 저장한 생성 설정과 문서 메시 덮어쓰기를 반환합니다. 이전 확장 필드는 프로젝트 문서에 계속 저장됩니다.

`workspace_apply_edits`, `project_import_psd`, `project_create_artwork`, `project_import_cmo3`, `project_open`, `project_save`, `project_save_as`, `project_export_model`, `project_export_target`, `project_export_psd`는 `{"ok":true,"operation":"...","data":{"id":"job-...","status":"..."}}`를 반환합니다. `job_wait` 또는 `job_get`으로 `data.result`의 결과를 읽습니다. 프로젝트 조작은 `project_id`, `state`, `history_node_id`를 반환하고, 내보내기는 파일과 경고를 반환합니다. 저장이 끝난 뒤의 늦은 취소는 completed와 이미 쓴 결과를 유지합니다. 아카이브를 바꾸기 전의 취소는 이전 파일을 유지합니다. 작업은 앱이 보유하므로, 연결이 끊긴 뒤 다시 연결해도 조회할 수 있습니다. 앱을 다시 시작한 뒤에는 복구하지 않습니다.

위 작업의 능력 상세에는 `job_result_schema`도 있어, completed의 업무 결과를 설명합니다. 시작과 조회의 outputSchema는 작업 상태도 함께 설명합니다. `queued/running/cancelling`의 terminal은 false이며 result/error가 없습니다. `cancelled`에는 result/error가 없고, `failed`는 구조화된 error를 요구하며, `completed`는 operation에 대응하는 result를 요구합니다. 작업 목록은 `items/total`과 적용되는 next를 반환하고, 성공 result는 생략하지만 실패 error는 남깁니다. 완료 결과와 지속 완료 표시는 같은 업무 검증을 쓰고, 제출 뒤의 취소는 실제 성공을 유지합니다.

`settings_update/layer_mesh_update/layer_classify`도 프로세스 작업 핸들을 반환하고, `request_id/project_id/state`를 요구합니다. `job_get/job_wait`의 `data.result`로 새 상태와 히스토리 노드를 얻습니다. 세 조작은 독립 애플리케이션 명령을 공유하고, 준비와 재구축이 진행을 보고하며, 제출 전 취소나 동시 충돌은 원래 문서와 히스토리를 유지합니다. CAS 뒤의 늦은 취소, 인터페이스 새로 고침 실패, 원래 요청 재시도는 완전한 성공 종료 상태를 유지합니다. 분류에서 생략한 필드는 시작 상태 검사 뒤의 캡처 모델에서 병합하고, 변화가 없으면 상태를 진행하거나 히스토리를 추가하지 않습니다. GUI의 연속 드래그와 분류 문자 입력은 세션이 끝날 때 한 번 제출하고, 단일 레이어 메시의 임시 미리보기는 정식 지속 후보를 대신하지 않습니다.

PSD 가져오기와 그림 만들기는 독립 애플리케이션 층 임포터를 공유합니다. `project_import_psd`는 절대 `path`를 요구합니다. `project_create_artwork`는 캔버스 `width/height`와 1–32개의 `layers`를 주며, 각 레이어는 절대 `path`와 `name`이 필요하고, 선택 정수 캔버스 위치 `x/y`와 열거 분류 `role/side`가 있습니다. 좌표와 열거의 발행 schema는 실제 검증과 같습니다. 둘은 빈 작업 공간에서 만들거나 이미 불러온 프로젝트를 전환하고, 기본적으로 저장하지 않은 수정을 거부하며, 먼저 저장하거나 `discard_unsaved:true`를 분명히 줍니다. 읽기 전에 상태를 캡처하고, 재구축 뒤 새 프로젝트 ID/로드 세대를 설치하며, 이전 Rig/journal, 레이어/메시/그리기 순서 덮어쓰기, 보조 데이터는 새 원본 그림으로 가져가지 않습니다. 작업 성공 결과에는 새 원본 레이어 `layers` ID도 들어갑니다.

CMO3 가져오기는 독립 애플리케이션 층 임포터를 공유합니다. GUI 입구는 확인한 뒤 신뢰할 수 있는 사용자 신분을 가지고 호출하고, MCP는 대화 상자에 들어가지 않습니다. `mode=new`는 새 프로젝트 ID와 로드 세대를 씁니다. `mode=replace`는 불러온 프로젝트가 필요하고, 나타나지 않은 객체와 파라미터 스냅샷, 히스토리 주석을 유지하며, 문서와 포즈 재설정은 같은 제출에서 발행합니다. 가져온 모델에는 PSD 자동 Rig나 모션 프리셋을 씌우지 않습니다. 작업 진행은 파일 읽기, 후보 준비, 재구축, 제출을 차례로 덮습니다. 제출 전 취소는 원래 프로젝트를 유지하고, 제출 뒤 취소는 완료 결과를 유지합니다.

같은 프로젝트 안에서, 같은 `request_id`와 같은 조작, 파라미터, 저자의 재시도는 원래 결과를 반환합니다(내보내기는 원래 작업 핸들). 바꾼 뒤에는 새 ID를 써야 합니다. 성공과 실패 결과는 최근 512개를 남기고, 더 이른 것은 지워집니다. 지워진 ID를 재시도하면 다시 실행합니다(이전 `state`가 있는 수정은 충돌로 실패하며, 중복 적용되지 않음). 이 중복 제거는 앱 프로세스 안에서 공유되고, MCP 연결에 의존하지 않습니다. 작업 진행은 조회로 반환하고, 이미 끝난 호출의 progress token을 이어 쓰지 않습니다. 이전 `export`, `export_psd` 이름은 제거되었습니다.

```json
{"request":{"request_id":"export-001","project_id":"project-id-from-inspect","state":"opaque-state-from-inspect","output_directory":"D:/exports/model"}}
```

모든 수정 조작은 `request_id`를 요구합니다. 작업 공간 수정은 `project_id`와 `state`도 요구합니다. `workspace_inspect`는 항상 현재 상태를 반환하며, 불러오지 않았을 때의 상태 토큰도 포함합니다. 빈 작업 공간을 만들거나 가져올 때 `project_id`는 `null`입니다. `state`는 로드 세대와 지속 버전을 담은 불투명 토큰이며, 같은 히스토리 노드를 다시 열어도 바뀌므로, 히스토리 HEAD를 채울 수 없습니다. 문서 쓰기는 자기 제출 토큰과 `history_node_id`를 반환합니다. 변화가 없으면 노드를 만들지 않습니다. 공유 컨텍스트는 레지스트리가 발행하고 검증하며, 제출 쪽이 다시 검사합니다. 요청이 연결 끊김을 기다려도 프로세스가 가진 실행은 취소되지 않습니다. 파라미터 스냅샷과 히스토리 주석은 이미 같은 애플리케이션 명령과 지속 버전을 쓰며, Rig 히스토리 노드를 추가하지 않습니다. GUI와 MCP의 명시적 pose 수정도 상태를 진행하고, 재생/물리 평가 프레임과 스냅샷 호버는 상태를 진행하지 않습니다. GUI가 파라미터를 바꿀 때는 먼저 로컬에 표시한 뒤 순서대로 제출합니다. 제출이 반영되기 전 `scope=preview`는 여전히 제출된 포즈를 반환합니다. 이때 MCP가 먼저 포즈나 다른 수정을 제출하면, GUI 큐의 수정은 충돌로 제출된 포즈로 롤백합니다.

`physics_simulate/simulation_simulate`는 시작 전에 요청의 프로젝트와 상태를 대조하고, 독립 조회 세션을 한 번 캡처합니다. 이후 수정이나 프로젝트 다시 열기는 이번 샘플을 바꾸지 않습니다. completed의 `result`에는 원래 샘플 진단과 캡처 때의 `project_id/state/revision`이 들어갑니다. `job_get/job_wait`로 조회하고, `job_cancel`로 교정, 정지, 프레임별 풀이를 중단합니다. 대기 취소나 연결 끊김은 작업을 계속하게 하고, 원래 요청 재시도는 같은 작업을 가져옵니다. 이들은 `read_only:true`를 유지하고, 히스토리를 늘리지 않으며, 지속 버전을 바꾸지 않고, 문서 배치에 넣을 수 없습니다. `simulation_simulate`의 `hold/release`는 각각 0–20초입니다.

```json
{"request":{"request_id":"sample-001","project_id":"project-id-from-inspect","state":"opaque-state-from-inspect","id":"simulation-id-from-inspect","hold":0.5,"release":1.5}}
```

## 오류와 복구

즉시 호출이 실패하면 도구는 `ok:false`와 구조화된 `error`를 반환합니다. 백그라운드 작업이 실패하면, `job_get/job_wait` 조회 자체는 여전히 `ok:true`를 반환하므로 `data.status`를 검사해야 합니다. `failed` 작업의 `data.error`는 같은 구조를 쓰고, 성공 `result`는 없습니다. 작업 조회, 목록, 재연결, 원래 요청 재시도는 모두 원래 오류를 유지합니다. 문제를 고친 뒤 새 시도를 시작하려면 새 `request_id`를 써야 하고, 이전 ID를 재사용하면 원래 결과를 얻습니다.

| `error.code` | 복구 근거 |
| --- | --- |
| `invalid_request` | 현재 schema를 읽습니다. `field`가 필드를 가리킵니다 |
| `output_contract` | 서비스 구현이 선언과 맞지 않는 결과를 반환했습니다. `operation/field`가 계약 문제를 가리킵니다. 먼저 프로젝트나 작업의 실제 상태를 조회하고, 이것으로 수정이 이미 롤백되었다고 가정하거나 바로 다시 하지 않습니다 |
| `invalid_argument` / `invalid_edit` | 파라미터나 편집을 고칩니다. 배치 오류에는 `edit_index`와 `edit_operation`이 더 있습니다 |
| `not_found` / `id_conflict` / `owned_by_generator` / `key_incomplete` / `topology_refused` | 구분할 수 있는 거부이며, `target`(`kind:id`, 예: `parameter:ParamAngleX`, `hit_area:HitAreaHead`)이 있고, 배치 멤버에는 `edit_index`와 `edit_operation`이 더 있습니다. 가리킨 객체, 키, 항목이 없습니다. 새 ID나 전환 뒤 ID가 이미 사용 중입니다(지운 파라미터가 남긴 모션 곡선이나 표정 항목 포함). 객체가 스윙, 시뮬레이션, 내부 조형, 본의 소유이므로 그 패널에서 처리해야 합니다. 키폼 편집의 키에 대상이 직접 바인딩한 축이 없습니다. 토폴로지 조작이 그 메시와 선택에 적용되지 않습니다. 모두 어떤 수정도 제출하지 않았습니다 |
| `tile_collides` | 이동, 회전, 밀도 변경 뒤 `layer_ids`의 타일이 배치 위치에서 밀립니다(페이지를 넘거나 다른 타일의 메시와 만남). 수정 전체는 제출되지 않았습니다. `atlas_check_placement`로 다른 위치를 고르거나, 관련 이동을 같은 배치에 넣습니다 |
| `state_conflict` | 프로젝트를 다시 읽습니다. 오류에 `expected_state`와 `actual_state`가 있습니다 |
| `project_conflict` | 대상 프로젝트를 확인합니다. 오류에 비울 수 있는 `expected_project`와 `actual_project`가 있습니다 |
| `request_id_reused` | 같은 ID가 다른 조작, 파라미터, 저자에 쓰였습니다. 새 요청 ID를 씁니다 |
| `unsaved_changes` | 먼저 저장하거나, 지원하는 포기 플래그를 분명히 넘깁니다 |
| `workspace_busy` | 진행 중인 작업이 끝나기를 기다린 뒤 현재 상태를 읽습니다 |
| `io_error` / `permission_denied` | `message`에 따라 파일 경로, 접근 권한, 디스크 문제를 확인합니다 |
| `invalid_state` / `operation_failed` | `message`와 현재 프로젝트 상태로 업무 전제나 실행 문제를 진단합니다 |

`cancelled`는 독립 작업 상태이며, 오류 코드가 아닙니다. 성공 제출 뒤의 늦은 취소나 새로 고침 이상은 `completed`와 제출된 결과를 유지하며, 이것으로 수정이 롤백되었다고 말할 수 없습니다.

`rig_create_warp`는 캡처한 후보 모델에서 `mesh:<id>`를 해석하고, 유일한 대상과 공통 부모 Warp를 요구합니다. `id`를 생략하면 한 번만 할당합니다. 명시적 `id`는 같은 배치의 이후 항이 참조할 수 있고, 새 Warp는 배치 `changed`에도 나타납니다. `rows/columns` 기본은 16, 범위는 1–32이며, 실제 격자점은 부모 격자점에 맞추고, 최종적으로 축마다 64를 넘지 않습니다. 객체 상세의 실제 분할을 기준으로 합니다. `fit_local` 기본은 true이며, 일반 키폼과 독립 블렌드 셰이프를 겹친 보수적 포락으로 자르고, 기하와 블렌드 셰이프를 함께 다시 매핑하며, UV, 스칼라 채널, 경로, 정점 가중치를 유지합니다. 포락이 부모 영역을 넘으면 피팅을 거부합니다. 먼저 부모 영역을 넓히거나 `fit_local:false`로 완전한 부모 영역을 남길 수 있습니다. 자유롭게 놓은 캔버스 Warp는 여전히 `canvas_warp`를 씁니다. 단일 종료 상태 `job_wait/job_get.result`에는 `target/state/history_node_id`가 있습니다. 제출 전 실패나 취소는 새 Warp를 발행하지 않고, 제출 뒤 새로 고침 실패나 늦은 취소는 성공과 핸들을 유지합니다.

## 원자 문서 편집

`workspace_apply_edits`는 바깥의 `request_id`, `project_id`, `state`를 공유합니다. `edits`의 각 항은 `{"operation":"...","request":{...}}`이며, 내부 request는 그 단일 도구의 업무 필드만 채우고, 컨텍스트 필드를 반복하지 않습니다. 멤버는 단일 도구가 발행한 같은 업무 schema를 씁니다. 알 수 없는 필드와 지원하지 않는 멤버는 후보 편집 전에 거부됩니다.

### 기하 검사와 시험 실행

`rig_deform`, `keyform_apply`, `rig_edit_structure`, `object_edit_appearance`, `canvas_warp/rotation/glue/topology`, `canvas_deform_stroke`, `path_deform`의 기하 저작 로그는 애플리케이션 층에서 완전히 재구축한 뒤, 정식 CAS 전에 기하 검사를 받습니다. 대응 GUI journal과 MCP는 같은 규칙을 씁니다. 배치는 최종 후보만 검사하므로, 앞 항이 일시적으로 퇴화하고 뒤 항이 고치는 것을 허용합니다. 다른 문서 조작은 이 규칙 때문에 검사 범위가 자동으로 넓어지지 않습니다.

`workspace_preview_edits`는 정식 제출과 같은 순서 있는 후보 준비, 재구축, 검사를 쓰지만, 투영이나 CAS를 호출하지 않고, state, 히스토리, 저장하지 않음 표시, 포즈, 리소스를 바꾸지 않습니다. 읽기 전용 백그라운드 작업이며, 여전히 `request_id/project_id/state`를 요구하고, `job_wait/job_get.result`로 `dry_run:true`, 입력 `revision`, `candidate_revision`, `would_change`, `would_commit`, 새 객체의 `changed` 핸들, `diagnostics`를 얻습니다. Warp, Rotation, Glue를 만들 때는 명시적 `id`를 줘야 하고, 그 뒤 같은 state에서 `workspace_apply_edits`에 같은 edits를 제출해야 후보 revision을 반복 비교할 수 있습니다. 시험 실행은 객체 ID를 예약하지 않고, 이후 상태 충돌 검사를 우회할 권한도 주지 않습니다. 기하 밖의 멤버는 시험 실행을 받지 않으며, 지원 범위는 그 조작의 schema를 기준으로 합니다.

`workspace_preview_regeneration`(간소 도구 집합에서는 `workspace_call`로 호출)은 같은 방식으로 임의의 문서 조작(리소스 레이어 조작 제외)을 시험 실행해, 제출 전에 재생성 결과를 보게 합니다. `checkpoints`는 제출이 로그에 쓸 체크포인트를 나열합니다(`index`는 후보 로그에서의 위치. `kind: "regeneration"`은 생성 결과를 사용자 Rig에 병합한 것이고, `issues`는 `rig_update_generation`의 문제 형식과 같습니다. `kind: "materialized"`는 새 항목 앞에 고정한 현재 저작 상태). `added`/`removed`는 후보가 추가·삭제한 객체 핸들이며, `dry_run:true`, `revision`, `candidate_revision`, `would_change`도 있습니다.

검사는 영향을 받은 객체의 부모 국소 기하를 덮으며, 일반 키 포인트, 블렌드 셰이프 키 포인트, 가중치 제한 점의 조합을 포함합니다. 유한하지 않은 좌표, 잘못된 토폴로지, 새로 생긴 면적 0 퇴화는 제출을 막고, `geometry_unsafe`와 구조화된 `diagnostics.violations`를 반환합니다. 새로 생긴 국소 뒤집힘과, 면적이 참조 삼각형의 1%에 못 미치는 국소 붕괴는 `diagnostics.warnings`에 저장되고, 기본적으로 막지 않습니다. 기존 저작 흐름은 의도적 접기를 허용하므로, 뒤집힘 수를 제작 실패로 바로 볼 수 없습니다. 이미 있는 뒤집힘, 퇴화, 붕괴는 따로 세며, 새 결함이 없는 편집을 막지 않습니다. 표면 전체의 가역 아핀 미러와 압축은 통과를 허용하고, 면적 0 변환은 여전히 거부합니다. 토폴로지 변경이 이전 삼각형 정체를 이어 쓸 수 없으면, 새 메시의 참조 기하를 씁니다. 혼합 좌표의 데카르트 곱이 16384를 넘으면, 결정적 분산 샘플로 바꾸고, 양 끝 조합을 남기며, GEOMETRY_SAMPLING_LIMIT으로 후보를 거부하지 않습니다. 보고서 scope는 큰 조합 영역이 샘플을 쓴다고 분명히 적습니다. 이것은 모든 조합의 증명이 아닙니다.

이전 프로젝트에서 앞머리를 투과하는 복제본(`<原件 ID>_SeeThrough`)은 구축할 때마다 설정에 따라 다시 생성되며, 직접 편집할 수 없습니다. 참조 필드(`target`, `id`, `layer_id`, `meshes` 등)에서 복제본을 지목하는 문서 조작은 `generated_copy`를 반환하고, 이름 같은 자유 텍스트는 세지 않습니다(`details.target`은 복제본, `details.original`은 편집해야 할 원본). 원본을 고치거나 설정 `seeThroughBrows` / `seeThroughEyes` / `seeThroughOpacity`를 바꿉니다. `objects`로 복제본을 볼 때 `topologyInfo.layerId`는 원본의 레이어이고, `topologyInfo.seeThroughOf`는 원본입니다. 눈은 앞머리에 창을 열어 투과하도록 바뀌었습니다. 완성 Rig는 눈 레이어로 역 클리핑해 그것을 덮는 앞머리를 자르고, 그것으로 자른 앞머리 복제본을 한 장 그립니다(`<刘海 ID>_SeeThrough`, `seeThroughOf`는 앞머리). 이 마스크는 완성 Rig에만 있고, 앞머리의 `topologyInfo.seeThroughWindow`가 그것들을 나열하며, 문서에서 앞머리 자신의 마스크는 여전히 비어 있습니다. 그것들을 앞머리의 `masked_by`에 다시 쓰지 않습니다.

후보가 시작 문서 대비 저작 로그만 추가했으면, 검사 범위는 새 명령에서 도출합니다. 명령이 직접 가리키는 메시, Warp, Rotation, 그 모든 자식 객체, 관련 메시의 Glue 상대, 베이크하지 않은 스윙과 베이크한 시뮬레이션의 대상(이 두 생성기는 재생할 때마다 대상을 다시 계산)입니다. 객체를 만들거나 다시 걸기, 메시 재구축처럼 영향을 받은 객체를 항목별로 나열할 수 없는 명령, 그리고 로그 밖의 문서 내용도 함께 바꾸는 후보는, 모든 객체를 비교하는 쪽으로 돌아갑니다. 범위 안의 분류는 전체 비교와 같습니다. 보고서 `coverage`는 `mode`(`full` 또는 `scoped`), 실제로 비교한 객체 수 `checkedTargets`, 양쪽 모델의 객체 총수 `totalTargets`를 줍니다. 범위를 줄이면 `scope` 텍스트가 나머지 객체는 비교하지 않았다고 적습니다.

진단은 이 샘플 점만 덮으며, 키 포인트 사이의 보간, 부모 조합 변형, Glue, 마스크, 픽셀 커버, 물리, 시각적 아름다움을 증명하지 않습니다. 시험 실행 결과는 입력 버전에 속합니다. 프로젝트를 나중에 편집하거나 다시 열면, 결과가 새 버전의 근거가 되지 않습니다.

정식 기하 저작 제출도 `geometry_diagnostics`를 반환할 수 있으며, 같은 차단 항목과 warnings가 들어갑니다. 기하 검사가 관련 객체가 없으면 그 필드를 생략합니다. 뒤집힘 경고를 수정 롤백으로 표현할 필요는 없습니다.

면적 0 판정에는 부동소수점 오차가 들어갑니다. 삼각형 유향 면적 절댓값이 `1e-12`에 못 미치거나, 면적 비율이 참조 형태의 `1e-6`을 넘지 않으면 수치 퇴화로 봅니다. 정점이 겹쳐 아주 작은 음수 면적이 남았다고 뒤집힘 경고만 보고하지 않습니다.

GUI 파라미터 정의, 폴더 위치, 파라미터 키 포인트는 공유 제출 한 번으로 묶을 수 있습니다. 캔버스 journal, 내부 typed 편집, 필드 완료 큐도 같은 후보/재구축/CAS 경계에 들어갑니다. GUI 스윙 세션, 필드 편집, 시뮬레이션 수정, 오프라인 베이크는 시작 때의 상태를 유지해, 이전 결과를 다시 열었거나 이미 바뀐 프로젝트에 제출하지 않게 합니다. GUI 저자는 신뢰할 수 있는 어댑터가 `user`로 지정합니다.

파라미터 정의는 이제 실제 편집 순서로 journal에 쓰이고, 삭제는 이전 키폼과 마지막 기본값을 읽습니다. 이전 프로젝트의 정적 파라미터 덮어쓰기는 호환 읽기를 유지하고, 히스토리를 다시 쓰지 않습니다. 투명도와 색 같은 순수 채널 수정도 유지되며, 이미 있고 같은 키 포인트를 반복 캡처하면 히스토리를 추가하지 않습니다.

현재 87개 항목이 배치를 지원합니다(소재 레이어 추가, 정합 위치, 확인, 레이어 전체 변환, 메시 재구축, 텍스처 편집 여섯 가지 포함). 설정, 레이어 분류/메시 구성, 원본 그림 다각형/연결 덩어리/깊이 분할과 삭제/복구, 파라미터 정의, 독립 Warp 생성, Rig 변형/키폼/구조/겉모습/정점 그룹, 원본 그림 페인팅 여섯 가지, 스켈레톤 수정 일곱 가지, 모션 수정 여섯 가지, 캔버스 편집 네 가지, 경로 put/delete/deform, 스윙 put/delete, 물리 put/delete/config/fit, simulation put/delete/bake/clear_bake, model_apply_preset입니다. 설정, 분류, 메시 구성, 원본 그림 분할, 그림 위치/취소, 레이어 소프트 삭제/복구, 독립 Warp, 페인팅, 물리, 시뮬레이션, 프리셋의 단일 호출은 백그라운드 작업을 씁니다. 배치는 같은 애플리케이션 층 후보를 직접 실행하고, 분할, 페인팅, 베이크, 피팅 진행에는 소속 배치 멤버가 들어갑니다. 준비 중에 배치 전체를 취소할 수 있고, 멤버의 단일 작업을 중첩하지 않습니다. `classic_front_hair/classic_back_hair`는 `sway:false`를 지원하고, 시뮬레이션을 제거한 뒤 그 종류의 전통 스윙을 끕니다. 찾기 도구가 `batchable:true`를 반환해야 지원입니다. GUI 필드 완료는 비동기 후보 큐에 들어갔고, 생성 설정/분류/메시 초안은 공유 순수 후보로 바뀝니다.

소재 레이어 추가는 가져온 모델과 직접 만든 부모 디포머를 지원합니다. 애플리케이션 후보는 원본 캔버스 기하를 실제 부모의 중립 좌표로 변환하고, 메시 생성 기록을 저장합니다. 다시 정합하면 원래 처리 픽셀을 쓰고, 제자리에서 생성 기하를 바꾸며 객체 ID와 부모는 유지합니다. 이미 전용 바인딩이나 마스크 의존이 있으면 단일 레이어 위치 지정을 여전히 거부합니다. 소재 레이어는 소재의 원래 해상도를 유지합니다. 투명 가장자리를 자른 뒤 픽셀이 바로 레이어 래스터가 되고, 소재 배치나 정합이 준 캔버스 사각형이 레이어 사각형이 됩니다(`canvasUnitsPerPixelX/Y`가 실제 비율). 예를 들어 1024픽셀 소재를 32단위 영역에 정합하면 32단위, 1024픽셀 레이어입니다. 평행 이동/확대·축소(명시적 미러 포함, 행과 열만 뒤집음) 정합은 보간을 거치지 않습니다. 회전이 있는 정합만 소재 자신의 밀도로 외접 상자에 래스터화합니다. 소재 목록과 레이어는 모두 원래 픽셀을 저장하고, 캔버스 해상도로 줄이지 않습니다. 할당 전에 좌표, 외접 상자 16MP, 래스터 16MP, 밀도 상한을 검사하고, 준비 단계는 취소에 응답합니다. GUI의 연속 배치는 독립 애플리케이션 세션을 거치고, 저장은 미리보기를 빼며 정식 확인을 기다립니다.

각 항목은 이전 항목의 후보 문서와 재구축 모델 위에서 실행되므로, 먼저 파라미터를 만든 뒤 같은 배치에서 키폼이나 모션 트랙을 바인딩할 수 있습니다. 캔버스 객체를 만든 뒤 이후 편집에서 그것을 참조할 수도 있습니다. 이후에 참조할 객체는 안정 ID를 줘야 합니다. 선택 ID를 생략하면, 결과 `changed`에 생성 객체의 `kind:id` 핸들이 들어갑니다. 같은 요청을 재시도하면 원래 핸들을 반환합니다.

최종 CAS가 성공해야 문서, 모델, 히스토리 노드 하나를 발행합니다. 멤버 편집이나 재구축 실패, 제출 전 취소, 상태 충돌은 모두 원래 작업 공간을 유지합니다. 변화가 없거나 원래 문서로 돌아온 배치는 state를 진행하거나 히스토리를 추가하지 않습니다. 멤버 실행 오류는 `invalid_edit`를 반환하고, 0부터의 `edit_index`와 `edit_operation`을 붙입니다. schema 오류는 `invalid_request`와 필드 경로를 반환합니다. 세션 수정, 프로젝트 전환, 리소스 I/O, 읽기 전용 시간 시뮬레이션(simulation_simulate), 중첩 배치는 멤버가 될 수 없습니다. 배치는 프로세스 작업 핸들을 반환하고, `job_wait/job_get`으로 상태와 `data.result`를 읽습니다. 성공 결과에는 `project_id/state/history_node_id/revision/applied/edit_count/changed`가 들어갑니다. 항목별 준비와 베이크 때 진행을 보고하고, 제출 전 취소는 후보를 발행하지 않습니다. 성공 CAS 직후 완전한 결과를 유지하고, 늦은 취소나 새로 고침 실패도 completed를 반환합니다. 업무 실패는 작업의 `error`에 위 분류와 인덱스를 남기고, schema 오류는 작업을 만들기 전에 반환합니다. 연결 끊김이나 대기 취소는 작업을 멈추지 않고, 원래 요청 ID 재시도는 같은 작업을 가져옵니다. GUI 내부의 직접 호출도 같은 명령 경계를 씁니다.

```json
{
  "request": {
    "request_id": "batch-001",
    "project_id": "project-id-from-inspect",
    "state": "opaque-state-from-inspect",
    "summary": "Create an axis and its motion key",
    "edits": [
      {"operation": "parameter_create", "request": {"parameter_id": "ParamAccessory", "name": "Accessory", "min": -1, "max": 1}},
      {"operation": "motion_put", "request": {"clip": {"id": "accessory", "name": "Accessory", "duration": 1}}},
      {"operation": "motion_set_key", "request": {"id": "accessory", "parameter": "ParamAccessory", "key": {"time": 0, "value": 1}}}
    ]
  }
}
```

## 파라미터 스냅샷과 히스토리 주석

`snapshot_create`가 `values`를 생략하면 현재 authored pose를 저장합니다. 재생 중인 평가 값을 저장하려면 샘플 값을 명시적으로 넘깁니다. `snapshot_update`는 이름만 바꾸거나, values만 바꾸거나, 둘 다 수정할 수 있습니다. 생략한 필드는 원래 값을 유지합니다. 스냅샷을 지워도 다른 스냅샷의 번호는 바뀌지 않습니다. GUI 스냅샷 막대와 MCP는 중립 편집 명령을 공유하고, 데이터는 런타임 캡처에서 오며, 저장/다시 열기는 스냅샷 ID, 번호, 파라미터 값, 히스토리 주석을 유지합니다.

`snapshot_apply`는 잠긴 파라미터의 값을 유지하고, 스냅샷에서 이미 지운 파라미터는 무시하며, 현재 파라미터 범위로 이전 값을 클램프합니다. GUI의 스냅샷 적용은 MCP와 같고, 타임라인 자동 키프레임 스위치의 영향을 받지 않습니다. 타임라인을 기록하려면 모션 편집 도구를 따로 호출합니다. 변화가 없으면 원래 `state`를 유지합니다. 실제 수정은 자기 `state`, `project_id`, `history_node_id`를 반환합니다. 히스토리 주석은 노드의 표시 제목, 설명, 숨김 표시만 바꾸고, 노드 내용과 브랜치 관계는 그대로입니다.

`preview_set`에서 생략한 파라미터와 잠금은 런타임이 제출한 포즈의 값을 유지합니다. `snapshot_apply`도 제출된 잠금을 기준으로 하고, 맞지 않은 파라미터는 원래 값을 유지합니다. 애니메이션 평가 프레임과 제출하지 않은 GUI 표시 값은 병합 기준선이 되지 않습니다. `preview_reset`은 모든 기본값을 복구하고 잠금을 해제합니다. 이 세 가지는 독립 애플리케이션 명령과 보조 CAS를 공유하고, Rig 히스토리나 자동 키프레임을 추가하지 않습니다. 논리적으로 변화가 없으면 상태를 진행하지 않지만, 올바른 인터페이스 포즈를 복구하고 재생을 멈출 수는 있습니다. 저장도 작업 공간마다 캡처한 지속 포즈와 잠금을 쓰고, 복사한 작업 공간의 포즈는 즉시 등록됩니다. 아카이브에서는 `workspace.json`으로 투영된 작업 공간 필드입니다.

## 최소 호출

아래 JSON은 해당 도구의 파라미터이며, HTTP나 JSON-RPC 바깥층이 아닙니다. 예제 ID, `state`, 좌표는 현재 프로젝트가 반환한 값으로 바꿔야 합니다.

`workspace_inspect`:

```json
{
  "request": {
    "scope": "project"
  }
}
```

레이어 분류는 먼저 `workspace_inspect scope=layers`가 반환한 `type/role/side/parameter/switch_id`를 읽고, 필요에 따라 갱신합니다. 예를 들어 이미 있는 소재를 전환 차분으로 바꿉니다.

```json
{
  "request": {
    "project_id": "project-id-from-inspect",
    "request_id": "unique-request-id",
    "state": "opaque-state-from-inspect",
    "layer_id": "actualLayerId",
    "type": "switch",
    "parameter": "expression",
    "switch_id": 1
  }
}
```

이 세 필드는 UI 레이어 표에 대응합니다. `parameter_update`는 Cubism 파라미터 정의만 수정하고, 원본 레이어의 분류나 차분 연관은 바꾸지 않습니다.

필요할 때 조회한 뒤, 단일 객체를 읽습니다.

```json
{
  "request": {
    "scope": "objects",
    "query": "hair",
    "limit": 24
  }
}
```

```json
{
  "request": {
    "target": "mesh:actualMeshId"
  }
}
```

`view_render_poses`의 고정 카메라 포즈 비교:

```json
{
  "request": {
    "viewport": {
      "mode": "canvas_rect",
      "left": 0,
      "top": 0,
      "width": 1000,
      "height": 1000
    },
    "poses": [
      {
        "ParamAngleX": -30
      },
      {
        "ParamAngleX": 0
      },
      {
        "ParamAngleX": 30
      }
    ],
    "columns": 3,
    "target_long_edge": 1024
  }
}
```

`rig_deform`:

```json
{
  "request": {
    "project_id": "project-id-from-inspect",
    "request_id": "unique-request-id",
    "state": "opaque-state-from-inspect",
    "changes": [
      {
        "target": "warp:actualWarpId",
        "key": {
          "ParamCustom": 1
        },
        "operations": [
          {
            "type": "translate",
            "delta": [
              0.02,
              0
            ]
          }
        ]
      }
    ]
  }
}
```

여기의 `key`는 대상이 직접 바인딩한 관련 축을 모두 덮어야 합니다. 관찰 포즈의 모든 부모 파라미터를 객체 바인딩으로 그대로 옮기지 않습니다. 마친 뒤 반환된 새 `state`를 쓰고, 실제 모델을 렌더해 확인합니다.

## 상태와 히스토리

- 모델 히스토리를 진행하는 쓰기 호출은 최신 `state`가 필요하고, 성공하면 이후 호출에 쓸 수 있는 상태를 반환합니다. 만료되면 다시 검사하고 편집을 맞추며, 덮어쓰지 않습니다.
- 배치 안 편집은 먼저 검증하고 재구축한 뒤 성공하면 제출합니다. `rig_deform` / `keyform_apply` 한 번에 변경 1–128개를 넣을 수 있고, 형태 한 줄에 조작 1–16개를 넣을 수 있습니다.
- 스윙과 시뮬레이션이 생성한 키폼(키에 생성 파라미터가 있고 기본값이 아님): `rig_deform`과 `keyform_apply`의 `set`은 생성 결과 덮어쓰기로 기록하고, 생성기 뒤에 병합합니다. 이 칸으로 또는 이 칸에서 `copy`, 생성기 축에 작용하는 `delete`와 `parameter_keys`는 거부하고, 오류에 생성기를 가리킵니다. 다른 객체에서 생성 파라미터를 쓰면(키 찍기, 이름 변경, 범위 변경) 먼저 그 파라미터를 생성기의 현재 정의로 프로젝트에 쓰고, 그 뒤는 일반 파라미터와 같습니다. 생성기 자신의 파라미터는 `parameter_delete`로 지울 수 없습니다.
- 일반 무변화 쓰기는 히스토리 노드를 만들면 안 됩니다. `checkpoint`는 점을 명시적으로 남기는 예외입니다. 복구는 쓰기 조작이며 HEAD를 옮깁니다. 이전 노드에서 계속 편집하면 브랜치가 생기고, 원래 브랜치는 남습니다.
- `project_save_as`는 절대 경로로 첫 저장이나 다른 이름 저장 위치를 지정합니다. `project_save`는 현재 위치에 저장합니다. `project_open`은 프로젝트 아카이브를 열고, 저장하지 않았으면 먼저 저장 작업이 성공하기를 기다리거나 `discard_unsaved=true`를 분명히 줍니다. 이 조작은 GUI 대화 상자를 열지 않습니다. `project_export_model`은 전달 파일을 쓰지만, 프로젝트 저장을 대신하지 않습니다. 임시 저장 소재는 모델에 이미 들어간 것도, 디스크에 저장된 것도 아닙니다.
- 시간 초과나 연결 끊김 뒤에는 `workspace_inspect`와 `history_list`로 이미 제출했는지 확인한 뒤 다음을 정합니다.
- 여러 도구에 걸친 트랜잭션, 구조화된 `history_diff`, `task_*` 실행 제어는 현재 공개 인터페이스로 제공하지 않습니다.

## 형태, 경로, 물리

### 본과 모션

먼저 `skeleton_propose`를 호출해 현재 레이어로 추론한 스켈레톤을 확인합니다. `skeleton_auto`가 그것을 프로젝트에 씁니다. `skeleton_get`은 완전한 `spec`을 반환하며, 본 좌표, 계층, 아트 프리미티브 바인딩, 관절 각도 범위, 관절 띠 너비가 들어갑니다. `skeleton_put`은 반환된 `spec`으로 전체를 바꿀 수 있습니다. `bone`은 본 하나의 필드를 병합하고, `move`는 연결된 관절을 함께 이동하며, `bind`는 아트 프리미티브를 지정 본에 바인딩합니다(`bone_id`를 생략하면 바인딩 해제). `remove`는 몸이 아닌 본을 지우고, `delete`는 스켈레톤 전체를 지웁니다. 아트 프리미티브 ID는 `workspace_inspect scope=objects`에서 얻습니다. 쓰기는 최신 `state`를 쓰고, 성공하면 재구축하고 히스토리를 제출합니다. 유효하지 않은 스켈레톤이나 없는 아트 프리미티브는 쓰기를 거부합니다.

```json
{"request":{"mode":"propose"}}
```

```json
{
  "request": {
    "project_id": "project-id-from-inspect",
    "request_id": "unique-request-id",
    "state": "opaque-state-from-inspect",
    "bone_id": "actualBoneId",
    "end": "tail",
    "point": [
      420,
      610
    ]
  }
}
```

`skeleton_pose`는 캔버스 픽셀 좌표로 본 방향을 구합니다. `ik: true`는 끝과 최대 두 단계 부모 본의 각도를 구합니다. 반환된 `values`는 계산 결과이며, `view_render_poses`에 넘겨 렌더로 확인할 수 있습니다(사용자의 파라미터 슬라이더를 움직이지 않음). 또는 `motion_set_key`, `motion_pose`로 모션의 파라미터 트랙에 씁니다. 스켈레톤, 키폼, 히스토리는 바꾸지 않습니다. 본 형태는 이미 파라미터, 디포머, 메시 키폼으로 베이크되어 있으므로, 형태 보정은 여전히 `keyform_apply` / `rig_deform`을 쓰고, 물리는 `physics_put` 등 물리 조작을 씁니다.

```json
{
  "request": {
    "bone_id": "actualBoneId",
    "target": [
      560,
      440
    ],
    "ik": true
  }
}
```

`motion_list / motion_get`은 지속 `MotionClip`을 읽고, `sample`은 지정 시각에 클립 보간 뒤의 파라미터 값을 반환합니다. `seed_builtin`은 생성 모션을 편집할 수 있는 같은 이름의 덮어쓰기 클립으로 바꿉니다. `put`은 클립을 만들거나 전체를 바꾸며, `get`이 반환한 JSON으로 왕복 편집할 수 있습니다. 클립은 길이, 반복, FPS, 페이드 인·아웃, 파라미터 곡선을 설정할 수 있습니다. 키는 `LINEAR`, `BEZIER`, `STEPPED`, `INVERSE_STEPPED`와 `in` / `out` 제어 핸들을 지원합니다. `set_key`는 지정 시각에 키를 쓰거나 바꾸고, `delete_key`와 `remove_curve`는 키나 트랙 전체를 지웁니다. 파라미터는 존재해야 하고, 키 값과 시간은 파라미터와 클립 범위 안에 있어야 합니다. 모션을 내보내려면 `settings_update exportMotions`를 켜야 합니다. 생성된 기초 모션(Idle, Blink, Nod, Shake)은 `settings_update motionBasic`도 켜야 하고, 본 프리셋은 `settings_update motionSkeleton`도 켜야 합니다.

```json
{
  "request": {
    "project_id": "project-id-from-inspect",
    "request_id": "unique-request-id",
    "state": "opaque-state-from-inspect",
    "clip": {
      "id": "wave_custom",
      "name": "WaveCustom",
      "duration": 2,
      "curves": [
        {
          "parameter": "ParamArmRA",
          "keys": [
            {
              "time": 0,
              "value": 0
            },
            {
              "time": 1,
              "value": 45
            },
            {
              "time": 2,
              "value": 0
            }
          ]
        }
      ]
    }
  }
}
```

### 범용 형태와 물리

`rig_deform` 조작에는 `translate`, `scale`, `rotate`, `arc`, `curve`, `landmarks`가 있습니다. 좌표는 고정 입력 경계의 정규화 규약을 쓰고, X는 오른쪽, Y는 아래이며, 각도는 도입니다. 선택 영역과 감쇠로 작용 범위를 제한할 수 있습니다. 임의 메시의 점별 배열은 받지 않습니다.

`keyform_apply op=seed`는 지정 키에서 이미 있는 형태를 샘플링합니다. `copy`는 분명한 출처 키를 복사합니다. `set`은 채널이나 회전 형태를 씁니다. `delete`는 해당 키 데이터를 지웁니다. 파라미터를 새로 만드는 것만으로는 움직임이 생기지 않으며, 형태와 파라미터 바인딩이 필요합니다.

`keyform_apply op=mirror`는 키폼을 세로 캔버스 선을 따라 좌우로 미러하고, 제출할 때 일반 `set`(및 `copy`)으로 펼치며, 재생은 더 이상 미러를 계산하지 않습니다.

| 필드 | 의미 |
| --- | --- |
| `target` | 출처 메시, Warp, 또는 회전 디포머 |
| `destination` | 같은 종류 대상. 기본은 `target`(같은 객체). `auto`는 ID나 이름의 L/R, Left/Right, 左/右로 반대쪽 객체를 찾습니다 |
| `from` / `all_keys` | 출처 키. 또는 `all_keys: true`로 출처의 모든 키폼 칸을 처리하고, 같은 객체이면 `side`(`positive` 기본 / `negative`) 한쪽의 키만 취합니다 |
| `key` | 대상 키. 기본은 `from`에서 도출합니다. 좌우 파라미터는 기본값을 중심으로 부호를 뒤집고, 좌우가 있는 파라미터(ParamEyeLOpen)는 대상이 반대쪽을 바인딩했거나 둘 다 바인딩하지 않았으면 반대쪽으로 바꾸며, 대상에 없는 축은 기본값을 씁니다. 명시적으로 주면 대상의 모든 직접 바인딩 축을 포함해야 합니다 |
| `reflect` | 부호를 뒤집는 파라미터. 기본은 범위가 기본값에 대칭이고, 2차원 연관의 가로 축이거나 ID가 X, Z로 끝나는 일반 파라미터입니다 |
| `axis_x` | 미러 선의 캔버스 x. 기본은 두 객체 기본 포즈 외접 상자 중심의 중점입니다(같은 객체는 자기 중심) |
| `channels` | 가져가는 내용. `geometry`와 `copy`의 채널 이름. 기본은 기하만 미러합니다 |

미러하는 것은 객체 자신의 변형입니다. 두 객체의 점을 기본 포즈의 부모 사상으로 캔버스에 놓고, 출처 각 점이 기본 형태 대비 가지는 변위의 x를 뒤집은 뒤, 대상 점의 미러 위치에서 출처 삼각형(Warp는 격자 대각 분할) 위에 보간하고, 다시 대상의 부모 공간으로 매핑합니다. 회전 디포머는 원점 변위를 미러하고 각도 변화의 부호를 뒤집습니다. 대칭 객체나 쌍 객체는 점과 점이 대응합니다. 융합 변형 키는 지원하지 않습니다.

`keyform_apply op=reset / interpolate / paste`는 키 하나의 기하 형태 전체를 씁니다(`vertices`가 준 점만 쓸 수도 있음). 제출할 때 마찬가지로 일반 `set`으로 펼치고, 재생은 다른 키를 더 이상 읽지 않습니다. 기하 `set`이 생성기가 소유한 키폼에 작용하면 생성 결과 덮어쓰기로 기록합니다. `paste`가 쓰는 것은 변위입니다. 메시는 원래 형태 대비, Warp와 회전 디포머는 기본 키 형태 대비입니다. 출처는 그 명령을 컴파일할 때 샘플링합니다. MCP 배치에서 이후 `paste`는 앞 멤버가 쓴 뒤의 후보 모델을 읽습니다(그래서 `paste` 두 번으로 A, B를 바꿀 수 없습니다. 같은 배치든 두 번 제출이든, 두 번째는 첫 번째가 쓴 뒤의 결과를 읽습니다. 바꾸려면 먼저 `copy`로 A를 빈 키 C에 저장한 뒤, B를 A에 붙이고 C를 B에 붙이며, 같은 배치에서 이 순서로 할 수 있습니다). 편집기의 붙여넣기는 복사할 때 출처를 고정하고, 붙여넣기 전의 모델로 모든 `set`을 한 번에 계산합니다.

| 필드 | 의미 |
| --- | --- |
| `target` | 메시, Warp, 또는 회전 디포머. `paste`일 때는 출처 |
| `key` | 쓰는 키. 대상의 모든 직접 바인딩 축을 포함해야 합니다 |
| `vertices` | 이 점만 바꿉니다(메시 정점 또는 Warp 제어점 번호). 나머지 점은 그 키의 현재 형태를 유지합니다. 회전 디포머에는 적용되지 않습니다 |
| `to`(reset) | `default`(기본): `along`의 파라미터가 기본값일 때의 형태. `rest`: 메시만, 변형하지 않은 메시 |
| `along`(reset) | `to: default`와 함께 씀. 기본값을 취하는 파라미터. 기본은 키의 모든 파라미터 |
| `parameter`(interpolate) | 어느 축을 따라 보간할지. 기본은 키가 정확히 두 키 사이에 끼인 그 축이며, 여러 개이면 반드시 줘야 합니다. 위치에 따라 양쪽 이웃 키의 형태 사이를 선형 보간합니다 |
| `from`(paste) | 출처 키. 출처에 없는 축은 기본값을 쓰고, 형태는 그 자리의 보간으로 읽습니다 |
| `destination`(paste) | 기본은 `target`. 기하를 쓸 때 다른 객체는 같은 종류이고, 삼각형(Warp는 행·열)이 같으며 부모가 같아야 하고, 그렇지 않으면 거부합니다 |
| `all_keys`(reset) | `key` 대신. 객체의 각 키폼이 기본 키 형태로 돌아갑니다(디포머 형태 복구) |
| `ratio`(paste) | 0–1, 기본 1. 현재 키가 붙여넣기 값으로 이동하는 비율이며, 1보다 작으면 혼합입니다 |
| `flip`(paste) | `horizontal` 또는 `vertical`. Warp는 열이나 행을 뒤집습니다. 메시는 정지 위치로 미러에 대응하는 정점을 찾습니다(정지 형태가 그 방향으로 대칭이어야 하며, 아니면 거부). 각 점은 대응 점의 변위를 취하고 방향을 뒤집습니다. 회전 디포머는 각도 변화의 부호를 뒤집습니다 |
| `channels`(paste) | `geometry`, `opacity`, `drawOrder`, `multiplyColor`, `screenColor`의 조합. 기본은 기하만 붙여 넣습니다. 채널 값도 `ratio`로 섞습니다 |

`keyform_apply op=multi_key`는 여러 객체의 여러 키폼 채널을 한 번에 수정하고, 키폼마다 채널만 있는 `set` 한 줄로 펼칩니다. `targets`(메시, 디포머, 파츠). `keys`(부분 키 목록, 키폼은 그중 하나와 맞아야 함)와 `ranges`(각 파라미터가 허용하는 키 값. 그 파라미터에 없는 키폼은 제한받지 않음). 기본은 모든 키폼입니다. `opacity`(0–1), `drawOrder`(0–1000, 디포머는 쓸 수 없음), `multiplyColor`, `screenColor`(`[r, g, b]`). `method`는 `overwrite`(기본), `add`, `multiply`, `screen`입니다(그리기 순서는 앞의 둘만 받음). 결과는 합법 범위로 제한합니다. 각 채널은 자기 트랙이 이미 있는 키에만 쓰고, 트랙이 아직 없을 때만 객체의 기하 키로 씁니다. `keys` / `ranges`는 이 키를 거릅니다. 쓰는 것은 일반 채널 키폼이며, 생성 결과 덮어쓰기로 기록하지 않습니다. 맞는 키폼이 하나도 없으면 거부합니다.

`path_get / path_list / path_preview / path_put / path_delete / path_deform`의 점은 Mesh 국소 좌표를 쓰며, 읽기 전용 조회, 미리보기, 바인딩, 키폼 베이크를 지원합니다. 경로는 편집 보조이며, 호환은 [변형 경로](../guide/DEFORM_PATHS.md)를 참조합니다.

물리 그룹은 Cubism 진자입니다. `inputs`(`parameter`, `weight` 0–100, `type`은 `x` 위치 X 또는 `angle` 기울기 중력, `reflect`)가 1–16절의 `segments`(`length`, `mobility`, `delay`, `acceleration`)를 밀고, `outputs`(`parameter`, `vertex`, `scale`, `weight`, `reflect`)는 `vertex`번째 절 끝이 이전 절 대비 가지는 각도를 읽습니다. `scale`은 라디안당 파라미터 값입니다(Cubism 네이티브 런타임은 변위 유형 출력의 배율을 읽지 않고, 이런 출력은 항상 0이므로 각도 출력만 제공합니다). 내보낸 physics3.json과 CMO3는 프로젝트의 계산 FPS를 선언합니다(기본 60). `normalization.position/angle`의 `min/default/max`는 입력 범위 사상의 목표입니다.

- `physics_put`은 준 필드만 수정합니다. 이미 있는 ID(생성 그룹 포함)는 현재 그룹을 기초로 하고, 새 ID는 「머리와 몸 입력, 한 절 길이 10, 출력 없음」을 기초로 합니다. 목록 필드는 통째로 바꿉니다. `length`는 줄 전체 길이이며 각 절을 비율로 스케일하고, `mobility/delay/acceleration`과 `output_scale`은 모든 구간 / 출력에 작용하며, `segment_count`는 절 수를 조정합니다. `enabled`만 주면 그 그룹만 켜고 끕니다. 생성 그룹을 수정하면 생성 버전을 대체하고, `physics_delete`까지 유지됩니다. 구버전 `input_parameter` / `output_parameter`는 여전히 단일 입력 단일 출력으로 읽습니다.
- 같은 파라미터는 작용하는 그룹 하나만 구동할 수 있습니다. 사용자 그룹이 생성 그룹의 출력을 구동하면, 생성 그룹이 양보합니다. `workspace_inspect scope=physics`는 `fps`와 계산 순서의 그룹을 반환하고, 각 그룹에는 `origin`, `enabled`, `active`, `overridden`, `replaced_by`, `issue`가 있습니다.
- `physics_config`: `order`는 먼저 계산할 그룹 ID를 나열하고, 나머지 그룹은 원래 순서로 뒤에 옵니다. Cubism은 순서대로 계산하므로, 뒤 그룹은 같은 스텝에서 앞 그룹의 출력을 읽습니다. `fps`는 프로젝트의 물리 프레임률이며, 1–240의 정수이고, 0은 제한 없음입니다(물리는 각 렌더 프레임마다 계산하고, 내보내기는 `Fps`를 선언하지 않음). 미리보기 프레임은 이것의 제한을 받지 않습니다. 미리보기 프레임률은 사용자 설정이며, 기본은 디스플레이를 따릅니다.
- `physics_import`: `path`는 physics3.json의 절대 경로입니다. 파일의 그룹은 사용자 그룹이 됩니다(같은 ID는 이미 있는 그룹을 대체하고, 생성 그룹은 `physics_delete` 전까지 대체된 채로 남음). 파일 순서대로 기존 그룹 뒤에 놓입니다. 같은 출력을 구동하는 다른 사용자 그룹은 꺼집니다. 파일의 `Fps`가 프로젝트의 계산 FPS가 됩니다. 가져온 ID, 꺼진 그룹, 없는 파라미터를 반환합니다.
- `physics_fit`: 패널 응답 곡선의 표준 흔들림(오른쪽으로 1초 당긴 뒤 놓음)으로 그 그룹을 돌리고, 각 출력의 배율을 피크가 파라미터 끝점의 `target`%(기본 100)에 딱 맞게 조정합니다. 움직이지 않는 출력은 원래 배율을 유지합니다. `observed_peaks`를 주면(키는 출력 번호 `"0"`, `"1"`…, 값은 `physics_audition`이 반환한 도달 비율, 1이 파라미터 끝점) 이번 실측 응답으로 맞추며, 패널에서 진자를 드래그한 뒤의 「최댓값으로 배율 조정」과 후보를 공유합니다. 번호가 잘못되었거나, 값이 음이 아닌 수가 아니거나, 어떤 출력도 움직이지 않으면 제출 전에 거부합니다.

`physics_put/physics_delete/physics_config/physics_import/physics_fit`은 프로세스 작업 핸들을 반환하고, 모두 `request_id/project_id/state`를 요구합니다. `job_wait/job_get`으로 제출 뒤의 상태와 결과를 읽습니다. 가져오기 결과에는 `imported`와, 적용되는 `disabled/missing_parameters/fps`도 있습니다. 읽기, 피팅, 재구축, 제출이 진행을 보고합니다. 피팅의 정지와 프레임별 풀이는 취소를 지원하고, 제출 전 취소나 상태 충돌은 원래 프로젝트를 유지하며, 제출 뒤의 늦은 취소나 인터페이스 새로 고침 이상은 성공 결과를 유지합니다. 연결 끊김은 작업을 멈추지 않고, 원래 요청 재시도는 같은 작업을 가져옵니다. 피팅은 원자 배치를 지원하고 앞선 후보를 읽습니다. 파일 가져오기는 배치를 지원하지 않습니다. 이미 목표 배율에 도달한 뒤의 다시 피팅은 히스토리 노드를 만들지 않고, 출력 응답이 전혀 없는 그룹은 여전히 오류를 냅니다.
- 먼저 출력 파라미터의 운동 끝점을 만든 뒤 물리를 연결합니다. 정적 포즈 콜라주에는 시간 진행이 없습니다. `physics_simulate`로 계단 입력을 검사해 진폭, 오버슈트, 안정 시간을 보고, 전체 모션은 `view_sample_motion`을 씁니다(그 네이티브 샘플 환경을 쓸 수 있어야 함).

## View와 공간 매핑

View는 모델 데이터에서 PNG를 렌더하며, 데스크톱 스크린샷에 의존하지 않습니다. `canvas_rect`는 캔버스 사각형을 주고, `focus_layers`는 객체 주변으로 프레이밍합니다. 출력 픽셀 크기와 모델 캔버스 크기는 별개의 개념입니다.

`view_render_layer/view_render_context/view_render_model/view_render_poses/view_check_coverage`는 각각 캡처한 프로젝트 버전을 한 번 읽습니다. 여러 포즈의 각 그림은 그 모델과 보이는 레이어를 공유하고, 이후 편집, 다시 열기, 프로젝트 전환은 결과에 섞이지 않으며, 이미 캡처한 조회를 무효로 만들지도 않습니다. PNG, 공간 매핑, revision은 일관되고, 그림과 공간 참조는 캡처 때의 프로젝트에 저장됩니다.

- `poses`는 같은 버전, 같은 캔버스 사각형 안에서 포즈 1–9개를 비교하고, 라벨이 있는 콜라주 한 장을 반환합니다. 전체 크기 예산은 그림 전체에 작용합니다.
- 비교 포즈는 백그라운드에서 캡처한 제출 버전으로 렌더하며, 저작 포즈와 GUI 파라미터 슬라이더를 읽지도 수정하지도 않습니다. `preview_set`으로 포즈를 바꿔 비교 그림을 만들지 않습니다. 사용자의 저작 포즈를 바꾸고 `state`를 진행합니다.
- 요청에 주지 않은 파라미터는 **파라미터 기본값**으로 렌더하며, 사용자의 현재 저작 포즈가 아닙니다. 현재 포즈를 기준으로 하려면, 먼저 `workspace_inspect scope=preview`로 제출된 포즈를 읽고, 공유 `parameters`에 넣은 뒤, `poses`에는 차이만 씁니다.
- `include_layer_ids`를 생략하면 현재 활성 캔버스의 국소 표시를 씁니다. 사용자가 숨긴 레이어, 숨긴 디포머 아래의 레이어, solo 밖의 레이어는 그림에 나타나지 않습니다. 사용자 캔버스와 무관한 안정된 결과가 필요하면 레이어 ID를 명시적으로 넘깁니다(`workspace_inspect scope=layers`에서 얻을 수 있음).
- `view_compare_history`는 히스토리 버전을 비교합니다. `view_sample_motion`은 시간으로 샘플링합니다. `view_check_coverage`는 지정 사각형과 지정 레이어의 Alpha 커버만 잽니다.
- 반환된 픽셀↔캔버스 매핑으로 위치를 잡습니다. 여러 포즈의 콜라주 전체는 단일 소재의 공간 기준으로 바로 쓸 수 없습니다.
- Alpha 커버, 메시 진단, 파일 쓰기 성공은 미술 품질 점수가 아니며, 샘플하지 않은 포즈가 정상이라는 증명도 아닙니다.

`view_sample_motion`은 읽기 전용 작업 핸들을 반환하고, `request_id/project_id/state`도 요구합니다. `frames`는 엄격한 프레임 객체 2–32개(`time/parameters`)이며, 시간은 0부터 엄격히 증가하고, 최장 10초입니다. 파라미터를 생략하면 이전 값을 이어 씁니다. `samples`는 모션 길이 안의 시간점 1–9개이고, `fps`는 15–120(기본 60)이며, 결과 시간은 샘플 프레임률로 아래로 양자화합니다. 작업이 시작하기 전에 모델, 보이는 레이어, 리소스 대상을 캡처합니다. 이후 수정, 다시 열기, GUI 애니메이션은 샘플에 섞이지 않습니다. 네이티브 Cubism 평가, 그림별 렌더, 콜라주가 진행을 보고하고 취소를 지원하며, 선택 네이티브 런타임을 설치해야 합니다.

`job_get/job_wait`으로 종료 상태 진단과 PNG를 읽고, 다시 읽으면 같은 그림을 반환합니다. 완료 결과에는 `project_id/state/revision`이 있고, 이미 있는 콜라주 `revisionId`도 같은 캡처 버전을 가리킵니다. 네이티브 샘플은 임시 모델을 쓰며, GUI의 실시간 애니메이션과 물리 상태를 바꾸지 않습니다. 큐에 있는 작업을 취소하면 실행하지 않고, 프레임 루프를 취소한 뒤 모델과 임시 파일을 해제합니다. 이 읽기 전용 조작은 문서 배치를 지원하지 않습니다. 히스토리 비교는 여전히 즉시 조회이지만, 같은 캡처의 히스토리 트리를 쓰며, 프로젝트를 다시 열어도 그 비교 근거를 바꾸지 않습니다.

## 소재와 Layout

프로젝트 안의 소재 라이브러리와 “먼저 배치하고, 그다음 생성” 흐름입니다. 설계와 경계는 MATERIAL_LAYOUT_WORKSPACE.md를 참조합니다. 문서 조작은 모두 `workspace_apply_edits` 멤버가 될 수 있습니다. 단독 호출은 프로세스 작업 핸들을 반환하고, `job_wait/job_get`의 `data.result`는 배치 결과와 같습니다(`state/history_node_id/edit_count/changed`). 새 소재는 `material:<id>`, 새 레이어는 `layer:<id>`로 `changed`에 나타납니다.

| 도구 | 주요 파라미터 | 설명 |
| --- | --- | --- |
| `project_create` | `width`, `height`, 선택 `discard_unsaved` | 생성하지 않은 빈 프로젝트를 새로 만듭니다 |
| `project_import_layout` | `path`(목록 `.json` 또는 목록이 들어 있는 `.zip`의 절대 경로), 선택 `discard_unsaved` | [Layout 목록](../spec/LAYOUT_MANIFEST.md)으로 생성하지 않은 프로젝트를 새로 만듭니다. 그림은 원래 픽셀을 `rect` 위에 깔고, 레이어는 목록의 위아래 순서, 그룹, 태그, 차분, 가시성, 불투명도를 유지하며, 동시에 소재로 등록하고 연관합니다. 결과는 `project_create`와 같습니다 |
| `material_import` | `state`, `paths`(절대 경로 1–64개), 선택 `folder_id`, `as_reference`, `reference_under`(`as_reference`와 함께 쓰며, 모델 뒤에 두고, 같은 히스토리 편집) | 그림(PNG/BMP/TIFF/WebP/JPEG), PSD(레이어 그룹은 하위 폴더, 각 레이어는 위치를 기록), CMO3의 각 ArtMesh 그림(`project_import_cmo3`로 얻은 레이어 픽셀과 같음), SVG(자기 크기로 래스터화), Layout 목록 또는 목록이 들어 있는 zip(그룹은 하위 폴더, 각 레이어는 `rect`를 기본 위치로 하고 태그를 붙임), 폴더(중첩 구조를 유지하고, 하위 폴더는 소재 폴더가 되며, 숨긴 항목과 그 안의 목록, zip은 건너뜀)를 읽어, 히스토리 노드 하나로 소재 라이브러리에 넣습니다. `as_reference=true`이면 첫 항목을 동시에 레퍼런스 그림으로 설정합니다. 그림 한 장은 16MP를 넘지 않습니다 |
| `material_add` | `state`, `staging`, 선택 `folder_id`, `as_reference`, `reference_under` | `material_import`가 읽은 뒤 실제로 제출하는 멤버입니다. `staging`은 프로세스 안에 임시 저장한 내용 요약이며, 보통 직접 호출하지 않습니다 |
| `material_list` | 선택 `folder_id`, `recursive`, `query`, `kind`, `unused`, `offset`, `limit`(≤256) | 폴더와 항목을 나열합니다. `query`는 이름과 태그(폴더 태그 포함)에 맞고, `references`는 그것을 쓰는 레이어 수입니다. PSD / CMO3에서 읽은 폴더에는 `origin` 파일 이름이 있습니다 |
| `material_get` | `id`, 선택 `include_svg` | 항목이나 폴더 상세입니다. 항목은 그것을 쓰는 `layers`를 반환하고, SVG에는 `vector_size`가 더 있으며, `include_svg`이면 저장한 SVG 원문을 붙입니다 |
| `material_preview` | `id`, 선택 `size`(16–4096, 기본 512) | 항목이나 폴더를 합성한 PNG 이미지를 반환합니다 |
| `material_folder_create` | `state`, `name`, 선택 `parent_id`, `id` | 폴더를 새로 만듭니다. `id`는 같은 배치의 이후 항이 참조합니다 |
| `material_move` / `material_rename` | `ids` + `folder_id`(null은 최상위) / `id` + `name` | 정리합니다. 폴더는 자기 안으로 옮길 수 없습니다 |
| `material_delete` | `ids` | 항목과 폴더(그 안의 모든 내용 포함)를 지웁니다. 아직 지우지 않은 레이어가 쓰면 거부하고 레이어를 나열합니다 |
| `material_tag` | `ids`, `set` 또는 `add`/`remove` | 항목과 폴더의 태그입니다. 배치할 때 레이어 태그에 합칩니다 |
| `material_set_resolution` | `id`, `width`, `height`(한 변 ≤4096) | SVG의 텍스처 해상도입니다. 그것을 쓰는 레이어의 캔버스 사각형은 변하지 않고, 픽셀만 바뀝니다 |
| `material_paint` | `state`, `id`, 그리고 한 획(`mode`와 그 파라미터) 또는 `gestures`(1–512획, 각각 `mode`와 그 파라미터, 순서대로 수정 한 번). `mode`는 `brush/pencil/eraser/bucket/shape/clear`. 파라미터: `points`+`radius`+`hardness`, `point`+`tolerance`, `from`+`to`+`shape`+`stroke_width`+`filled`. 선택 `color`(RGBA 바이트), `opacity` | 항목 자신의 픽셀 좌표(왼쪽 위가 원점)에 한 획을 그립니다. 알고리즘은 `paint`와 같습니다. 그것을 쓰는 레이어의 캔버스 사각형은 변하지 않고 픽셀만 바뀌며, SVG는 비트맵이 됩니다 |
| `material_fork` | `state`, `layer_ids`(1–128) | 다른 레이어와 소재를 공유하는 레이어에 각자 자기 복제본을 줍니다(소재 라이브러리에서 원 소재 바로 뒤). 픽셀은 변하지 않습니다. 소재가 없는 레이어는 하나를 얻습니다. 각 레이어는 소재 하나를 표시하고, 그것이 유일한 출처입니다. 공유 소재의 레이어에서 `source_paint_*`와 `paint_session_begin`(`layer_id`)은 소재에 그리고, 그것을 쓰는 모든 레이어가 동기화됩니다. 레이어 픽셀을 바꾸는 다른 편집은 그것만 그 소재를 쓸 때 소재가 따라가고, 아니면 복제본을 주며, 연관은 끊기지 않습니다. `material_unlink`는 이전 이름입니다 |
| `material_adopt_layers` | `state` | 아직 소재가 없는 레이어마다 소재를 하나 등록합니다(`Layers` 폴더, 그룹은 하위 폴더). 이전의 이전 프로젝트용이며, 새 가져오기와 편집은 스스로 등록합니다 |
| `material_set_pixels` | `state`, `id`, `staging` | 소재 페인팅 세션이 임시 저장한 픽셀을 씁니다. `paint_session_commit`이 시작하며, 보통 직접 호출하지 않습니다 |
| `material_adjust` | `state`, `id`, 적어도 한 항: `flip`(`horizontal/vertical`), `hue`(±180), `saturation`/`lightness`/`contrast`(±100) | 먼저 뒤집고 그다음 색을 조정하며, 크기는 변하지 않습니다. 레이어와 SVG 동기화 규칙은 위와 같습니다 |
| `material_export_psd` | `state`, `folder_id`, `path`(절대 `.psd`) | 백그라운드에서 폴더를 PSD로 씁니다. 항목은 기본 위치(없으면 왼쪽 위부터 늘어놓고), 하위 폴더는 레이어 그룹입니다 |
| `material_export_png` | `state`, `id`, `path`(절대 `.png`) | 백그라운드에서 항목 자신의 픽셀을 그대로 PNG로 씁니다(확대·축소하지 않음). 예를 들어 레퍼런스 그림 클로즈업을 외부 도구에 넘겨 더 선명한 파츠로 그립니다 |
| `layout_set_reference` | `state`, 선택 `id`(기본은 첫 장, 없으면 새로 만듦), `material_id`(null은 제거), `rect`, `opacity`(0–1, 기본 0.5), `visible`, `rotation`(중심을 축으로 시계 방향 각도), `locked`, `under`(모델 아래에 깔기), `points`(키폼 점 배열, 각각은 파라미터 ID → 값, 어느 한 점에서 표시. `[]`는 취소) 또는 단일 점 `pin` | 레퍼런스 그림입니다. 항목을 캔버스 위에 겹치거나 모델 아래에 깔아 배치와 대조에 씁니다. 레이어가 아니므로 생성, 내보내기, Rig는 그것을 보지 않습니다. 새로 설정할 때 `rect`를 주지 않으면 캔버스 안에 맞게 비율로 키우거나 줄이고 가운데에 둡니다. 생략한 필드는 원래 값을 유지합니다. 레퍼런스 조회와 렌더는 첫 장을 씁니다. `material_list`는 첫 장(`reference`)과 전부(`references`)를 반환합니다 |
| `layout_add_reference` | `state`, `material_id`, 나머지 필드는 위와 같음 | 레퍼런스 그림을 하나 더하며, 다른 레퍼런스 그림 위에 그립니다 |
| `layout_order_reference` | `state`, `id`, `index` | 레퍼런스 그림을 그리기 순서의 `index`로 옮깁니다(0이 가장 먼저 그림) |
| `reference_set_mode` | `state`, `mode`(`background` / `layer` / `foreground`), `reference_id` 또는 `layer_id` 중 하나 | 레퍼런스 하나를 배경, 레이어 스택, 전경으로 옮깁니다. 레퍼런스 그림이 레이어 스택에 들어가면 레퍼런스 레이어가 되고, 레퍼런스 레이어가 스택에서 나오면 레퍼런스 그림이 됩니다. 소재, 사각형, 불투명도, 표시, 고정은 남고, 레퍼런스 그림의 회전과 잠금은 레이어로 들어가지 않습니다 |
| `reference_set_opacity` | `state`, `opacity`(0–1), `reference_id` 또는 `layer_id` 중 하나 | 레퍼런스 그림이나 레이어 안 레퍼런스 그림의 불투명도를 조정합니다. 생성 전후 모두 가능합니다. 명령 한 번이 히스토리 한 줄입니다 |
| `material_crop` | `state`, `id`, `rect`(항목 픽셀) 또는 `canvas_rect`(캔버스 단위, 항목은 레퍼런스 그림이거나 이 캔버스에 배치되어 있어야 함), 선택 `name`, `new_id`, `folder_id` | 손실 없이 새 항목을 잘라 냅니다. 새 항목은 이 픽셀이 캔버스에서 어디에 있는지 기억하고, `layer_place`에 `rect`를 주지 않으면 레퍼런스 그림 위에 정확히 떨어집니다. 태그와 폴더는 유지합니다 |
| `material_set_placement` | `state`, `id`, `canvas_rect`(null은 지움) 또는 `from`(다른 항목) | 항목이 캔버스에서 가지는 기본 위치를 설정합니다. 픽셀은 변하지 않습니다. 외부에서 클로즈업으로 만든 고화질 그림은 클로즈업의 위치를 쓰고, 넘치는 픽셀이 더 높은 텍스처 밀도입니다 |
| `material_peel` | `state`, `id`(현재 그림), `revealed`(같은 크기, 파츠를 뺀 생성 그림), 선택 `region`(같은 크기 마스크, 흰색이나 불투명한 곳이 파츠일 수 있음), `threshold`([낮음, 높음] 색차 0–255, 기본 [8, 30]), `grow`, `core`, `close`(기본 2), `band`(기본 24), `color_match`(기본 true), `keep`(파츠 위의 `[x, y]` 점, 항목 픽셀, 1–64개), `compare_scale`(1–8, 기본 1), `name`, `part_id`, `base_id` | 파츠 하나를 벗겨, `id` 위치에 항목 두 개를 새로 만듭니다. 파츠(`id` 자신의 픽셀, 투명도는 두 그림의 차이에서 오고, `region`에 `grow`를 더한 안으로 제한, `core`는 수축한 마스크를 강제로 합치고, `keep`는 이 점과 연결된 덩어리만 남기며 먼 조각은 버림)와 그 아래 그림(파츠 영역에서만 `revealed`를 취하고, 주변 `band` 픽셀 너비의 바꾸지 않은 영역으로 Lab 색을 맞춤). 파츠 밖은 원래 픽셀을 유지하고, 파츠를 다시 겹치면 원그림입니다. 그림 전체의 색조 이동(각 채널 차이 중앙값)은 차이로 치지 않습니다. 생성 그림이 더 작은 크기에서 확대한 것이면, `compare_scale`이 확대 배수를 주고, 원래 생성 크기에서 비교해 확대가 만든 선 차이는 넣지 않습니다. 가장 앞 파츠부터 시작하고, 새 밑그림에서 다음을 계속 벗깁니다 |
| `material_draw` | `state`, `id`, 선택 `svg`, `edits`, `density`(0.05–16, 캔버스 단위당 텍스처 픽셀), `name`, `folder_id` | 벡터로 파츠를 그리거나 다시 그립니다. `id`가 비어 있으면 새로 만듭니다. SVG의 `viewBox`가 파츠가 캔버스에서 있는 위치입니다(캔버스 단위). 경로는 캔버스 좌표를 직접 씁니다. `density`로 렌더합니다(생략하면 항목의 현재 밀도를 이어 쓰고, 새 항목은 1. 한 변 ≤4096 픽셀). Skia가 렌더할 수 있는 내용(경로, 그라데이션, 자르기, 마스크, `feGaussianBlur` 등 필터, 불투명도) 외에, `<style>` 안의 요소, `#id`, `.class` 규칙, `var(--name)`이 그리기 키트의 색을 취하는 것, 경로의 `data-taper`(처음부터 끝까지 고르게 분포한 너비 계수, 예: `"0.1 1 0.2"`)가 스트로크를 양 끝이 가늘어지는 채움 선으로 바꾸는 것을 지원합니다. 블렌드 모드와 사용자 CSS 변수는 렌더하지 않습니다. 그것을 쓰는 레이어는 픽셀이 바뀌고, 이전 위치에 있던 것은 새 `viewBox`로 이동합니다. `edits`는 요소 `id`로 현재 그리기(또는 함께 준 `svg`)를 국소 수정합니다. `set`은 속성을 설정하고, null은 속성을 지우며, `text`는 `<style>` 텍스트를 바꿉니다. 예를 들어 경로 하나의 `d`나 색 하나만 바꿉니다. `svg`와 `edits`를 주지 않으면 새 `density`로 다시 렌더합니다 |
| `layout_set_drawing_kit` | `state`, 선택 `colors`(이름 → `#rgb`/`#rrggbb`, 병합, null은 삭제), `styles`(클래스 규칙 스타일시트), `defs`(SVG 정의: 그라데이션, 필터) | 모든 그리기가 공유하는 색, 클래스 규칙, 정의입니다. `styles`, `defs`는 통째로 바꾸고, 그리기 자신의 같은 이름 규칙과 id가 우선합니다. 모든 벡터 항목은 원래 해상도로 다시 렌더하고, 바뀐 것은 레이어에 동기화합니다. 어떤 그리기에 색이 빠지게 하면 거부합니다 |
| `reference_sample` | `points`(≤256개의 [x, y]), `radius`(캔버스 단위, 평균을 취함), `rect`, `colors`(1–16, 기본 6) 중 적어도 하나 | 레퍼런스 그림 색을 읽습니다. 각 점의 색과 alpha. `rect` 안 불투명 픽셀의 주색(명도 어두운 것에서 밝은 것 순, 비율 첨부)으로 키트 색을 정합니다 |
| `reference_trace` | `seed`([x, y]), 선택 `tolerance`(Lab 거리, 기본 12), `compare`(`seed`/`neighbor`), `simplify`(캔버스 단위, 기본 0.6) | 마법 지팡이입니다. 씨앗에서 씨앗 색(`neighbor`는 이웃 픽셀과 비교, 부드러운 그림자를 넘어 선까지 갈 수 있음)과 가까운 불투명 픽셀로 확장하고, 바깥 윤곽의 단순화 다각형 `polygon`과 그것을 지나는 매끄러운 닫힌 경로 `path`(캔버스 좌표, 그리기의 밑그림으로 바로 사용), 면적, 외접 상자, 평균색, 내부 구멍 수를 반환합니다. `touches_edge`는 레퍼런스 그림 가장자리까지 번졌음을 뜻합니다. `holes`(0–16)는 가장 큰 구멍 윤곽 `hole_outlines`를 추가로 반환합니다. 배경에서 취하면 인물 실루엣입니다(인물이 이미지 가장자리에 닿으면 실루엣은 바깥 윤곽 안에 있음) |
| `reference_snap` | `points`(1–256), 선택 `radius`(캔버스 단위, 기본 4) | 점을 `radius` 안의 가장 가까운 선 중심에 붙입니다(주변보다 분명히 어두운 픽셀 중 가장 가까운 것을 취한 뒤, 그 근처에서 가장 어두운 곳). 근처에 선이 없는 점은 움직이지 않고 `snapped`는 false입니다 |
| `layer_place` | `state`, `material_id`, 선택 `rect`(단일 항목), `at`([x, y] 중심), `layer_id`(단일 항목의 새 레이어 ID), `reference`(`true` / `false`는 전부를 레퍼런스 또는 모델 레이어로 배치, 생략하면 각 항목의 표시를 따름) | 항목이나 폴더 전체를 새 원본 레이어로 놓고, 이미 있는 레이어 위에 그립니다. 기본은 PSD 위치입니다(PSD 캔버스를 프로젝트 캔버스 가운데에 매핑하고, 넘으면 비율로 줄이며, 크기가 같으면 제자리). 아니면 소재 픽셀 1 = 캔버스 단위 1로 캔버스 안에 넣습니다. 픽셀은 다시 샘플링하지 않습니다 |
| `layer_tag` | `state`, `layer_ids`(1–128), `set` 또는 `add`/`remove` | 의미 태그입니다. 부위 단어와 `left/right`가 분류를 결정합니다(예: `{hair, front}`, `{eye, iris, left}`). 나머지는 사용자 태그입니다. 이미 생성된 레이어의 분류 변화는 `rig_generate`를 기다립니다 |
| `layer_set_variant` | `state`, `layer_id`, `variant`(`none/toggle/switch`), 선택 `parameter`, `switch_id` | 차분 속성입니다. 태그는 유지합니다 |
| `layer_mark_reference` | `state`, `layer_ids`(1–128), `reference` | 레퍼런스 레이어로 설정(`true`)하거나 모델 레이어로 복구(`false`)합니다. 레퍼런스 레이어는 캔버스와 위아래 순서에 남지만, 생성이 읽지 않고, Rig와 내보내기에도 들어가지 않습니다. 생성하지 않은 레이어, 생성 대기 레이어, 레퍼런스 레이어만 받으며, 이미 생성된 레이어는 오류를 반환합니다. 복구한 레이어는 이미 생성된 프로젝트에서 `rig_generate`를 기다립니다. `workspace_inspect scope=generation`의 `reference_layers`가 그것들을 나열합니다 |
| `layer_pin_reference` | `state`, `layer_ids`, `points`(키폼 점 배열) 또는 단일 점 `pin` | 레퍼런스 레이어를 키폼 점에 고정합니다. 각 점은 파라미터 ID → 값(각 파라미터 범위 안, 파라미터 최대 16개)이며, 레퍼런스는 어느 한 점에서 표시됩니다(각 파라미터 차이가 범위의 0.5%를 넘지 않음). 나머지 포즈에서는 숨습니다. `[]`, `{}`, null은 취소입니다. 원래 점을 바꿉니다 |
| `reference_place_grid` | `state`, `material_id`, `x_parameter`, 선택 `y_parameter`, `x_values`, `y_values`, `columns`, `rows`, `pixel_rect`, `rect`, `id_prefix` | 키폼 격자로 그린 레퍼런스 그림(예: 3×3 머리 각도)을 칸으로 균등하게 나누고, 각 칸을 소재로 잘라 레퍼런스 레이어로 놓으며, 대응 키폼에 고정합니다. 열은 `x_values`를 왼쪽에서 오른쪽, 행은 `y_values`를 위에서 아래이며, `view_render_pad`의 배열과 같습니다. 값을 생략하면 파라미터의 키폼 값을 씁니다(y는 큰 것에서 작은 것, 2D pad와 같음). 이때 수량은 `columns`/`rows`와 같아야 합니다. `y_parameter`가 없으면 한 행입니다. `pixel_rect`는 격자가 그림에서 차지하는 픽셀 범위입니다(기본은 전체). `rect`는 각 칸이 캔버스에서 있는 위치입니다(기본은 각 칸을 비율로 캔버스에 넣고 가운데). 레이어와 소재 ID는 `<id_prefix>_<行>_<列>`입니다. 히스토리 노드 하나입니다 |
| `material_mark_reference` | `state`, `ids`(항목 또는 폴더, 1–1024), `reference` | 항목(폴더이면 그 안의 모든 항목)을 `layer_place`가 지정하지 않았을 때 레퍼런스 레이어로 배치할지입니다 |
| `layer_reorder` | `state`, `layer_ids`(1–128), `to`(`front/back/forward/backward/above/below`), `above/below`는 `target`이 필요 | 위아래 순서를 조정합니다(기초 그리기 순서). 맨 앞이나 맨 뒤로, 움직이지 않은 레이어 하나를 넘거나, `target` 바로 위나 바로 아래로 옮기며, 옮긴 레이어 사이의 원래 순서는 유지합니다. 생성기가 읽지 않은 레이어만 받습니다(생성하지 않은 프로젝트의 레이어, 생성 대기 레이어). 이미 생성된 레이어는 오류를 반환하고, 그 그리기 순서는 `layer_draw_order`로 조정합니다 |
| `rig_generate` | `state`, 선택 `refresh_geometry`, `preset_group` | 생성하거나 다시 생성합니다. 생성하지 않은 프로젝트는 현재 모든 레이어로 구축합니다. 이미 생성된 프로젝트는 지난 생성 뒤에 놓은 레이어와 기다리는 분류를 합치고, 삼자 병합으로 사용자 편집을 남기며, 깨끗하게 이전하지 못한 내용은 `quality.regeneration`을 참조합니다. `refresh_geometry`는 현재 픽셀로 메시를 다시 만들 이미 생성된 레이어를 나열하고, 생략하면 그림을 바꾼 레이어 전부이며, `[]`는 모두 유지합니다. 명시적 스택은 바인딩하지 않은 소재에만 고른 그룹을 추가하고, 기본은 `modern-standard`이며, `none`은 추가하지 않습니다 |

생성하지 않은 프로젝트, 빈 캔버스, 생성 대기 레이어는 모두 일반 편집 작업 공간을 쓰며, 메시, 디포머, 파라미터, 계층, 회전, 텍스처 편집을 지원하고, 내보내기에는 현재 모델이 들어갑니다. `rig_generate`는 태그에 따라 이미 있는 메시 위에 바인딩과 파라미터를 생성하고, 메시 토폴로지를 유지합니다. `refresh_geometry`로 명시적으로 새로 고칠 때는 예외입니다. PSD를 가져온 새 프로젝트도 각 레이어를 소재로 등록하고 연관합니다.

전형적인 흐름: `project_create` → `material_import` → `material_list` → 여러 `layer_place`(또는 PSD 폴더 전체를 배치) → `layer_tag` → `layer_reorder` → `rig_generate` → 저작 편집 → 다시 `layer_place` → `rig_generate`. 외부 도구가 이미 파츠를 나눠 두었으면 Layout 목록을 쓰고, `project_import_layout`으로 배치된 생성 전 프로젝트를 한 번에 얻은 뒤, 확인 후 `rig_generate`합니다.

`view_render_model`은 레퍼런스 레이어(`layer_mark_reference`)를 모델 아래에 깔아 그립니다(`reference.mode`가 `difference`이면 모델만 비교). `view_render_model`에는 `grid`(몇 캔버스 단위마다 좌표 주석이 있는 격자선을 그리고, 너무 촘촘하면 ×2, ×5, ×10…으로 넓히며, 결과의 `gridStep`이 실제 간격)와 `outline_material_ids`(캔버스 위치가 있는 항목—그리기와 잘라 낸 클로즈업—의 불투명 가장자리를 각자 색으로 뷰에 그리고, id를 붙임)가 있어, 좌표를 읽고 그리기와 레퍼런스 그림을 대조합니다. 레퍼런스 그림만 보려면 `include_layer_ids: []`와 `reference: {mode: under, opacity: 1}`을 줍니다.

`view_render_pad`는 모델이 키폼 묶음에서 어떻게 보이는지를 주석이 있는 아틀라스 한 장으로 모아, 2D pad 전체를 한 번에 보게 합니다. 열은 `x_parameter`가 `x_values`에서 왼쪽에서 오른쪽, 행은 `y_parameter`가 `y_values`에서 위에서 아래입니다. 값을 생략하면 파라미터의 키폼 값을 씁니다(y는 큰 것에서 작은 것). 2D pad에 속한 파라미터에 `y_parameter`를 주지 않으면 짝을 자동으로 붙입니다. `columns` / `rows`(`[起, 止]`, 양 끝 포함, 위 값의 인덱스)는 임의의 부분 사각형을 자릅니다. 예를 들어 5×3 pad에서 `columns: [1, 3]`, `rows: [0, 1]`을 취합니다. `show`가 각 칸의 내용을 정합니다. `model`(모델만), `reference`(그 키폼에 표시되는 레퍼런스 레이어—여기에 고정했거나 고정하지 않은 것), `under` / `over`(레퍼런스를 모델 아래에 깔거나, `reference_opacity`로 위에 겹침), `difference`(같은 곳은 검고, 칸마다 0–1의 `difference`를 줌), `side_by_side`(모델 | 레퍼런스). 결과의 각 칸 `imageRect` `[x,y,width,height]`는 `canvasRect`에 매핑되고, 보여 준 레퍼런스 레이어를 나열합니다. 최대 64칸이며, `target_long_edge` / `max_bytes`가 그림 전체를 제한합니다. 외부에서 같은 순서로 그린 격자 레퍼런스 그림은 `reference_place_grid`로 고정 레퍼런스로 읽어 들일 수 있습니다.

레퍼런스 그림으로 파츠를 그리는 흐름: `layout_set_reference` → `reference_sample`로 색을 취하고, `layout_set_drawing_kit`로 색과 선 클래스를 정함 → `grid`가 있는 `view_render_model`로 파츠를 확대해 보고, `reference_trace`로 윤곽을 취하고, `reference_snap`으로 선 위의 점을 교정 → `material_draw`(가려진 부분은 형태대로 온전히 그리고, 원화의 결함은 그대로 베끼지 않음) → `outline_material_ids` 또는 배치한 뒤 `reference`로 대조 → 반복 수정 → `layer_place`, `layer_tag` → `rig_generate` → `view_render_poses`로 돌릴 때 드러나는 부분을 확인합니다.

레퍼런스 그림으로 파츠를 나누는 흐름: `material_import` 레퍼런스 그림 → `layout_set_reference` → `material_crop`(`canvas_rect`)으로 클로즈업을 자름 → `material_export_png` → 외부에서 확대하거나 다시 그림 → `material_import` → `material_set_placement`(`from` 클로즈업) → 레이어마다: 외부에서 가장 앞 파츠를 뺀 그림을 생성 → `material_peel` → 새 밑그림에서 계속 → `layer_place` → `view_render_model`의 `reference`(`difference`로 정렬을 보고, `referenceDifference`가 평균 차이)와 `density`(텍스처 밀도 히트맵, `texelDensity`가 각 레이어의 캔버스 단위당 텍스처 픽셀을 나열)로 확인 → `layer_set_canvas_rect` / `layer_transform`으로 미세 조정 → `layer_tag` → `rig_generate` → `view_render_poses`로 모션을 확인합니다.

## 소재 작업 흐름(asset_*)

`asset_prepare_reference / asset_import_png / asset_register / asset_reprocess`는 프로세스 작업 핸들을 반환하고, 모두 `request_id/project_id/state`를 요구합니다. `job_wait/job_get`의 `data.result`에서 업무 결과와 제출 뒤의 `state/project_id/history_node_id`를 읽습니다. 레퍼런스 그림, 정합 미리보기, 재처리 그림은 다시 가져올 수 있습니다. 연결 끊김이나 대기 취소는 작업을 멈추지 않고, 원래 요청 재시도는 같은 작업을 얻습니다. 준비 실패, 제출 전 취소, 상태 충돌은 소재를 발행하지 않습니다. CAS 뒤의 늦은 취소는 completed와 실제 결과를 유지합니다.

네 가지 쓰기는 독립 애플리케이션 층 소재 세션을 공유합니다. 먼저 비공개 후보를 준비한 뒤, 보조 CAS 안에서 불변 파일과 소재 목록을 발행합니다. 실제로 추가한 소재는 이전 state를 무효로 만들지만, Rig revision을 바꾸거나 히스토리를 추가하지는 않습니다. 같은 내용과 매핑을 반복 가져오는 것은 변화 없음입니다. 검사와 시험 배치는 캡처한 문서와 소재 목록을 쓰고, GUI 초안이나 나중에 한 정합은 읽지 않습니다. 저장은 캡처 때 프로젝트에 속한 소재만 복사합니다(`workspace.json`의 `assetCatalog`). PNG 가져오기는 픽셀 메모리를 할당하기 전에 1600만 픽셀 상한을 검사합니다.

새 소재에는 보통 `reference → import → register → preview → add`를 쓰고, 필요하면 `place → finalize`를 씁니다. `project_create_artwork`는 배치한 소재에서 백그라운드로 새 프로젝트를 만들 수 있고, 전환할 때 기본적으로 저장하지 않은 수정을 거부합니다. `source_split_polygon`은 캔버스 다각형으로 안쪽과 나머지로 나누고, `source_split_components`는 현재 메시의 연결 덩어리로 현재 픽셀을 나눕니다. 둘 다 가려진 내용을 그려 넣지 않습니다.

`source_get_components`는 읽기 전용 조회입니다. `layer_id`를 넘기면, 캡처 때의 `project_id/state/revision`, `can_split/count`, 컴포넌트 중심 `canvas_x/canvas_y`를 반환합니다. 컴포넌트는 원본 그림 Y, 그다음 X로 정렬합니다. 이 순서로 `source_split_components`의 `names`에 이름을 붙이고, 같은 길이의 `sides`와 유일한 `piece_ids`를 선택으로 줄 수 있습니다. 이름은 앞뒤 공백을 뺀 뒤 비어 있지 않고 유일해야 하며, 수량은 현재 컴포넌트와 맞아야 합니다. 좌우를 생략하거나 `none`을 쓰면 원 레이어를 상속합니다. 컴포넌트 검출은 조회가 캡처한 현재 메시를 씁니다. 이후 수정은 이전 조회 결과를 바꾸지 않고, 제출은 그 캡처 상태를 가져야 합니다.

`source_split_polygon/source_split_components`는 `request_id/project_id/state`를 요구하고, 백그라운드 작업 핸들을 반환합니다. `job_get/job_wait`의 `result`는 새 상태, 히스토리 노드, 새 원본 그림 `layers` ID를 유지합니다. 다각형은 캔버스 정점 3–32개와 `names` 두 개(안쪽, 나머지)가 필요하며, 양쪽 모두 보이는 픽셀이 있어야 합니다. 유일한 `piece_ids` 두 개를 선택할 수 있습니다. 새 ID를 지정하면 같은 원자 배치의 이후 멤버에서 그것들을 참조할 수 있고, 배치 `changed`에는 새 원본 그림의 `layer:<id>` 핸들과 생성 객체 핸들이 함께 들어갑니다. 준비/재구축은 취소할 수 있고, 제출 전 충돌이나 실패는 원래 문서를 유지합니다. 제출 뒤의 늦은 취소나 새로 고침 이상은 완전한 성공 종료 상태를 유지하고, 원래 요청은 같은 작업을 가져옵니다.

파츠가 원래 원본 레이어를 대체합니다. 원 레이어는 원본 그림에서 떠나고(`deletedLayerIds`에 들어가지 않음), 파츠는 일반 원본 레이어가 되어 분류, 부모, 가시성, 레이어별 메시, 그리기 순서 덮어쓰기를 상속합니다. 분할은 재생성 한 번입니다. 파츠는 원 레이어 위치에서 생성에 참여하고, 원 레이어에서 사용자가 한 키폼, 채널, 블렌드 셰이프, 경로, 정점 가중치, Glue는 삼자 병합으로 파츠에 실리며(스윙과 시뮬레이션이 생성한 축은 제외), 이후 얼굴 설정, 선 자세, 스켈레톤, 분류 변화도 마찬가지로 파츠에 도달합니다. 시뮬레이션의 대상, 베이크 오프셋, 연결 역할은 파츠로 이전합니다. 로그는 분할의 `art_primitive` 기록 뒤에 체크포인트 하나를 쓰고, 기록 자체는 더 이상 재생하지 않습니다. 텍스처 아틀라스와 내보내기에는 원 레이어가 더 이상 들어가지 않습니다. `layer_restore`(`layer_ids`를 생략한 전체 복구 포함)는 그것을 되돌릴 수 없고, 실행 취소/히스토리 전환으로 분할 전에 돌아가는 것만 가능합니다. 그 뒤 원 레이어나 원 메시 ID를 참조하는 요청은 실패하고, 오류 메시지가 그것을 대체한 파츠 ID를 나열합니다. `source_get_components`도 같습니다. 파츠는 다시 나눌 수 있습니다. CMO3를 가져온 모델은 여전히 이전 규칙으로 원 레이어를 소프트 삭제합니다. 기록 형식은 [문서 레이어](../spec/DOCUMENT_LAYER.md#분할-실체화-아트-프리미티브-기록-art_primitive)를 참조합니다. 일반, 분할, 새로 만든, CMO3를 가져온 메시는 단일 레이어/전역 메시 갱신과 재설정 때 순서 있는 교체로 바인딩과 이미 있는 시뮬레이션 오프셋을 유지하며, 지운 메시의 복구도 포함합니다. 다각형 분할이 만든 새 절단점과 경계 정점 복제본은 방향 Glue로 그 원 정점을 가진 파츠를 따르고, 절개 양쪽은 붙어 있습니다. 삭제된 원본 그림, 마스크(클리핑), 채널 마스크가 있거나 일반 블렌드가 아닌 원본 그림은 나눌 수 없습니다. 컴포넌트 조회는 실제 텍스처 주소를 쓰고, 원본 그림 가장자리 보강, 회전, 확대·축소를 지원하며, 편집한 일반 대상은 `can_split:true`를 반환할 수 있습니다. GUI 메시 연결 덩어리 분할 대화 상자는 같은 애플리케이션 명령을 호출하고, 검출 때의 상태를 유지하며, 여러 레이어 결정은 사용자 히스토리 제출 한 번입니다. 다각형 조작은 현재 MCP 입구이며, 이것으로 GUI에 올가미 분할 메뉴가 있다고 말할 수 없습니다.

`rig_update_generation`은 이 프로그램의 생성기로 Rig를 다시 생성합니다(GUI의 **모델 → 생성 결과 업데이트**에 대응). 로그에 체크포인트가 있는 프로젝트는 체크포인트를 쓸 때의 생성기 출력을 유지하고, 프로그램을 올려도 자동으로 바뀌지 않습니다. 이 조작은 체크포인트가 저장한 생성 스냅샷, 이 프로그램이 지금 같은 입력으로 생성한 결과, 현재 저작 상태 Rig를 삼자 병합합니다. 사용자가 바꾸지 않은 객체는 새 출력을 따르고, 사용자의 수정은 남으며, 깨끗하게 이전하지 못한 내용은 `issues`에 나열합니다(마찬가지로 `workspace_inspect`의 `quality.regeneration`에 들어감). 결과는 새 체크포인트로 로그 끝에 추가됩니다. 로그에서 더 이른 재생성 체크포인트가 이 프로그램으로 병합하면 다른 구조가 되면(부모, 정점 수, 키폼 축, 블렌드 셰이프가 다름), 먼저 이 프로그램으로 다시 병합하고, 보정을 삼자 병합으로 이후 체크포인트와 현재 저작 상태 Rig에 가져갑니다(문제 `remerged`, `target`은 `journal:<序号>`, `detail`은 구조가 다른 객체를 나열). 이후 편집은 남습니다. 요청은 `request_id/project_id/state`만 필요하고, 백그라운드 작업 핸들을 반환하며, 원자 배치 멤버이기도 합니다. 작업 `result`는 `state/project_id/history_node_id/revision/applied`, `updated`, `issues`(각 항 `{kind, target, detail?}`)입니다. 생성 결과가 같고 매번 병합이 일치하면 `updated:false`이며, 히스토리 노드를 추가하지 않습니다. 체크포인트가 없는 프로젝트는 구축할 때마다 원래 이 프로그램이 생성하고, CMO3를 가져온 모델에는 생성 결과가 없으므로, 둘 다 갱신이 필요 없습니다(후자는 거부).

`source_split_depth`는 GUI의 깊이 분할 메뉴, 다중 선택, 대화 상자에 대응합니다. 현재 메시의 원래 `source_id`와 비어 있지 않고 유일한 `middle_ids`(`mesh:` 접두 없음, source를 포함하지 않음)를 넘깁니다. 선택 `names`는 뒤 레이어, 앞 레이어의 비어 있지 않고 유일한 이름 두 개이며, 선택 `front_layer_id/front_mesh_id/back_layer_id/back_mesh_id/glue_id`로 새 ID를 지정합니다. 뒤 레이어, 앞 레이어 두 개의 새 레이어와 메시로 원본 메시와 그 레이어를 대체하고, 두 레이어 모두 원래 픽셀을 가지며 독립적으로 그릴 수 있고, 가운데 메시는 두 레이어 사이에 있습니다. 결과의 `layers`는 차례로 앞 레이어, 뒤 레이어입니다. 두 레이어는 원본 메시의 현재 부모, 기하, 채널 키폼, 블렌드 셰이프, 경로, 정점 가중치를 얻습니다. 뒤 레이어가 원본 메시의 Glue, 마스크, 시뮬레이션 대상을 인수하고, 방향 Glue가 앞 레이어를 뒤 레이어에 따르게 합니다. 두 레이어는 고정 그리기 순서를 쓰며, 원래 그리기 순서 애니메이션을 바꿉니다. 입과 파생 입술은 고른 메시만 복사하고, 입술을 추가로 만들지 않습니다. 앞 레이어를 지워도 토폴로지는 남습니다. CMO3를 가져온 모델은 이전 기록을 이어 씁니다. 원본 메시와 그 레이어를 유지하고, 그 위에 앞 레이어와 방향 Glue를 복사합니다(뒤 레이어는 새로 만들지 않음).

단일도 `request_id/project_id/state`를 요구하고, 작업 종료 상태의 `layers`는 새 레이어 ID입니다(CMO3를 가져온 모델은 새 앞 레이어만). 유일한 `front_layer_id/front_mesh_id/back_layer_id/back_mesh_id/glue_id`를 지정해, 같은 원자 배치의 이후 멤버가 새 객체에 그리거나 편집할 수 있습니다. 배치 `changed`는 `layer:<id>/mesh:<id>/glue:<id>`를 반환합니다. GUI는 메뉴나 대화 상자를 열었을 때의 상태를 유지하고, 보조 데이터 수정도 이전 확인을 무효로 만듭니다. 준비는 취소할 수 있고, 제출 뒤 정확한 종료 상태를 유지합니다. 히스토리 재생, 저장 후 다시 열기, CMO3 다시 읽기는 운동과 Glue 연결 가중치를 유지합니다. 이것은 현재 내용을 복사하므로, 숨은 부분은 여전히 페인팅으로 보충해야 합니다.

이미 있는 PSD는 `project_import_psd`로 로컬 절대 경로에서 엽니다. `source_paint_brush / source_paint_pencil / source_paint_eraser / source_paint_bucket / source_paint_shape / source_paint_clear` 여섯 가지 페인팅은 프로세스 작업 핸들을 반환하고, `job_wait/job_get`으로 `data.result`의 제출 상태, 히스토리 노드, 생성 핸들을 얻습니다. 능력 상세는 `job_backed:true`, `batchable:true`를 표시하고 `job_result_schema`를 제공합니다. 브러시, 지우개, 페인트 통, 형태는 UI의 래스터 알고리즘을 씁니다. 점, 반지름, 선 두께는 모두 캔버스 단위이며, 레이어 자신의 래스터에 떨어집니다. 래스터 밀도(래스터 픽셀 / 캔버스 단위, [레이어별 크기](../spec/DOCUMENT_LAYER.md#레이어별-크기) 참조)가 1인 레이어는 이전과 같고, 32단위 사각형에 1024² 래스터를 저장한 레이어에서 반지름 2의 브러시는 래스터에 반지름 64픽셀의 획을 그리며, 제출 뒤 래스터는 원래 해상도를 유지하고 캔버스 해상도로 줄지 않습니다. 사각형 밖에 그리면 사각형이 같은 밀도로 확장되고, 불투명 픽셀로 자르면 밀도는 변하지 않습니다. 완전히 지운 레이어는 캔버스 해상도로 투명 픽셀 하나를 남깁니다. 비공개 초안도 레이어 래스터를 바닥으로 씁니다. `paint_session_begin` 등 결과의 `width/height`는 초안 래스터 픽셀이고, `canvas_rect`는 그것이 덮는 캔버스 사각형 `[left, top, width, height]`이며, 색 추출은 캔버스 픽셀 중심에서 래스터를 읽습니다. 초안이 16MP를 넘으면 사각형 주변의 그릴 수 있는 여백을 줄입니다. 기본 `rebuild_mesh:false`는 이미 있는 메시와 모든 바인딩을 유지하고, 레이어 메시로 자릅니다. 메시 위에 떨어진 픽셀은 레이어에 남고(연관 소재의 레이어는 소재에 쓰며, 연관된 모든 레이어가 동기화), 메시 밖에 떨어진 픽셀은 바로 위의 새 레이어로 나뉩니다(이름은 원래 이름에 `+`, 자체 메시, 같은 부모와 파츠 아래, 원 레이어 소재가 있는 폴더에 새 소재로 두고 연관). 메시 밖의 지우기는 적용되지 않습니다. 아직 메시가 없는 레이어는 자르지 않습니다. 레이어 자신의 메시가 새 영역을 덮어야 하면 `rebuild_mesh:true`를 넘기고, 레이어는 모든 픽셀로 확장하며 키폼, 블렌드 셰이프, 경로, 정점 그룹, Glue를 이전합니다. 두 방식 모두 제출 한 번에 히스토리 노드 하나입니다. 이미 있는 키폼, Warp, Glue는 더 이상 페인팅을 막지 않습니다. `clear`나 모든 픽셀 지우기는 레이어와 바인딩을 유지하고, 마지막 레이어도 비운 뒤 다시 그릴 수 있습니다. 레이어 삭제는 `layer_soft_delete`를 씁니다. 앞뒤 분리의 앞 레이어는 항상 토폴로지를 유지합니다. 일반으로 편집한 레이어, 깊이 앞뒤 레이어, CMO3를 가져온 것의 삭제/복구는 먼저 편집을 재생한 뒤 활성 객체를 거르고, 복구에 필요한 ID, 키폼, 바인딩을 유지합니다. 삭제 중에 메시 설정을 수정해도 숨은 메시의 다시 바인딩 결과를 저장합니다. 이전 프로젝트가 처음 실제로 삭제하거나 복구하면 새 후보에서 정체와 생성 기준선을 고정해, 현재 편집이 레이어 복구 때문에 다른 메시를 가리키지 않게 합니다. 이전 히스토리 노드는 다시 쓰지 않습니다. `layer_mesh_update`는 단일 레이어 메시 파라미터를 수정하고, 재설정 뒤 전역 값을 상속합니다.

일반 투명 레이어가 처음 보이는 픽셀을 그리면 메시를 자동으로 만들며, 먼저 `rebuild_mesh:true`를 넘길 필요가 없습니다. 입의 첫 페인팅도 파생 입술과 그 생성 키폼을 만듭니다. 단일과 배치 작업 완료 결과의 `changed`에는 새 객체의 `mesh:<id>` 등 핸들이 들어 있어 계속 편집할 수 있습니다. 같은 요청 재시도는 같은 작업을 가져오고, 다시 만들거나 히스토리를 추가하지 않습니다. 완전히 지워도 재구축 플래그를 넘기면 메시와 바인딩을 유지합니다.

생성 입술은 명시적 재구축이나 첫 생성 때 저장한 윤곽과 색을 따르고, 이후 메시를 유지하는 페인팅은 파생 텍스처를 다시 생성하지 않습니다. GUI가 얼린 픽셀과 MCP 제스처는 독립 `WorkspaceRasterCommands`를 통해 문서 후보, 재구축, 상태 검사를 공유합니다. 생성 데이터는 순서 있는 로그에 들어가고, 저장 후 다시 열기와 내보내기는 현재 텍스처를 다시 해석합니다. 준비 중에 진행을 보고하고 취소를 검사하며, 제출 전 취소는 원래 픽셀과 히스토리를 유지합니다. 성공 제출 뒤의 늦은 취소나 새로 고침 실패도 완전한 완료 결과를 유지합니다. 연결 끊김이나 대기 취소는 작업을 멈추지 않습니다. 새 메시의 단일 레이어 설정 갱신도 문서에 쓰이고, ID, 다시 바인딩한 경로, 가중치를 유지합니다.

`layer_delete`와 `layer_restore`는 모두 프로세스 작업을 반환하고, 원자 배치를 지원합니다. `job_wait/job_get`의 `result`로 실제 상태, 히스토리 노드, 영향을 받은 레이어를 읽습니다. `layer_delete`는 레이어를 정말로 지웁니다. 픽셀은 프로젝트에서 떠나고, 그 메시는 로그 기록 `layer_delete`로 저작 상태 Rig에서 떠나며, 그것들을 참조하는 마스크, Glue, 경로, 가중치도 함께입니다. 생성기가 읽는 레이어는 동시에 생성 입력에서 떠나, 이후 재생성은 그것을 위해 메시를 만들지 않고, 어떤 프레임에도 영향을 주지 않습니다. 이전 히스토리 노드는 그것을 유지하고, `history_checkout`이 실행 취소입니다. 마지막 레이어는 지울 수 없습니다. 입술 띠, 구버전 좌우 반쪽처럼 독립 원본 레이어가 아닌 행은 여전히 이전 방식으로 숨깁니다. `layer_restore`는 이전 버전이 소프트 삭제한 레이어만 복구합니다. 유일한 `layer_ids` 1–256개를 넘길 수 있고, 생략하면 모두 복구합니다(분할로 대체된 원 레이어는 그 안에 없고, 그것을 지정하면 오류를 내며 파츠를 나열). 지울 내용이 없으면 변화 없음이며, 히스토리를 추가하지 않습니다.

전역 메시 단위는 `settings_update`의 `meshUnits`를 씁니다. `DOCUMENT`는 문서 긴 변 2048픽셀을 기준으로 하고, 같은 이미지를 키운 뒤에도 같은 밀도를 씁니다. `PIXELS`는 원본 픽셀 길이를 씁니다. 두 모드의 단일 레이어 파라미터는 여전히 고른 단위 안의 길이를 나타냅니다. 이전 v1 설정에 `meshSpacing`은 있고 단위가 없으면 `PIXELS`를 이어 써, 다시 열 때 이전 메시가 바뀌지 않게 합니다. 새 설정, 생성 기준선, 내보내기는 실제 단위를 저장합니다. GUI 단위 스위치와 공개 수정은 같은 후보 재생성과 토폴로지 이전을 씁니다. `meshTrace`는 윤곽이 무엇을 읽을지 정합니다. `TEXTURE`(새 프로젝트 기본)는 레이어 자신의 픽셀을 읽고, 최대 alpha로 최대 메시 단위의 4배 촘촘한 해상도까지 줄이며, 캔버스 픽셀보다 가는 획도 메시 안에 남고, 정점 간격은 변하지 않습니다. `CANVAS`는 캔버스 해상도로 축소한 뷰를 읽습니다. 그 필드가 없는 기존 설정은 `CANVAS`로 읽고, `CANVAS`는 그 필드를 쓰지 않으므로, 이전 프로젝트의 설정 텍스트와 메시는 변하지 않습니다. `meshWrap`(0–64 메시 단위, 기본 0)은 감싸는 토폴로지입니다. 윤곽을 그리기 전에 그 값보다 좁은 틈과 오목한 곳을 닫아, 속눈썹, 머리카락 끝 같은 가는 돌출이 포락 하나를 공유하고, 모든 그린 픽셀은 여전히 메시 안에 있습니다. `layer_mesh`의 `changes.wrap`은 레이어별로 덮고, `inspect`의 레이어 메시 설정은 실제 `wrap`을 반환합니다. 값이 0이면 필드를 쓰지 않고, 필드가 없는 설정은 0으로 읽습니다. 자세한 내용은 메시 토폴로지를 참조합니다.

`import`는 이미 있는 로컬 PNG 절대 경로나 이미지 바이트를 받습니다. 모델이 Base64를 글자 그대로 만들게 하지 않습니다. `solid_background`를 생략하면 원래 Alpha를 유지합니다. 바닥을 제거해야 하면 실제 단색을 명시적으로 줍니다. 바둑판 스크린샷은 투명 소재가 아닙니다.

`register`는 프레임, 대응 앵커, 절대 변환을 쓸 수 있습니다. 앵커의 목표 위치는 캔버스 좌표이고, 생성 그림 좌표는 원래 완전한 PNG 픽셀입니다. 미러, 비등방 늘이기는 분명히 선언해야 합니다. 해상도를 높인다고 모델 안의 점유 면적이 자동으로 커지면 안 됩니다.

먼저 시험 배치하고 부모, 그리기 순서, 마스크, 운동 여유를 확인한 뒤, 정식으로 넣거나 대체 레이어를 지웁니다. 이미 독립 바인딩, 키폼, 위치 확정이 끝난 소재는 전체 재배치를 거부할 수 있으며, 실제 오류와 Schema를 기준으로 합니다.

## 관련 구현

- [공개 Schema와 어댑터](../../../src/main/kotlin/io/github/psd2live/application/WorkspaceAuthoringOperations.kt)
- [소재 도구](../../../src/main/kotlin/io/github/psd2live/application/WorkspaceAssetCommands.kt)
- [경로 도구](../../../src/main/kotlin/io/github/psd2live/application/WorkspacePathEdits.kt)
- [프로젝트 저장](../../../src/main/kotlin/io/github/psd2live/project/WorkspaceStore.kt)

이 페이지는 인터페이스를 기록하며, 이것으로 [능력 실측](../STATUS.md)의 평가를 올리지 않습니다. 완전한 효과는 여전히 실제 모델, 호스트, 작업 표본으로 검증해야 합니다.

### 눈 구조 교정

`module_catalog`의 `eye_structure`는 눈 컴포넌트와 `sourceStamp`를 반환합니다. `settings`의 `changes.rig.eyeParts`로, 레이어 ID와 컴포넌트 ID에 따라 `side`(left/right), `region`(upper/lower), `primitive`(line/tuft/side/skin/auto), `contacts`, `path`를 설정합니다. 컴포넌트를 처음 설정할 때 `sourceStamp`가 필요합니다. 좌표는 눈 너비로 정규화하고, 얼굴 아래를 양으로 하는 눈 국소 좌표입니다. 컴포넌트나 레이어가 null이면 재설정합니다. 원화 요약과 맞지 않는 덮어쓰기는 남지만 적용되지 않습니다. 이 편집은 정상 설정 히스토리에 들어가고 프로젝트와 함께 저장됩니다.

### 전체 프리셋 전환과 의상 시뮬레이션

`modifier_catalog.baselines`는 환경설정 라이브러리의 전환 가능한 프리셋 ID를 반환하며, 내장과 사용자 프리셋이 들어갑니다. `modifier_switch`는 `state`와 `group`을 받아, 현재 프리셋 전체를 바꾸고 일반 저작 내용은 남깁니다. 격리 후보, CAS, 단일 히스토리 노드를 탑니다. 구버전／신버전은 모디파이어 그룹 구성이며, 인스턴스를 직접 만들고 같은 스택이 평가합니다. 이전 프로젝트 생성과 변환은 실행하지 않습니다. 생성 내용 덮어쓰기나 이미 고정한 능력을 안전하게 이전할 수 없으면 전환을 거부합니다.

`modifier_plan`의 `type: "simulation"`은 `preset: "clothing" | "front_hair" | "back_hair"`와 함께 쓸 수 있고, 원래 인식과 자동 가중치 흐름을 호출합니다. 반환된 시뮬레이션 설정에는 `automatic_weights`가 있고, 가중치는 인스턴스 평가와 함께 가며, 저작자가 이미 가진 같은 이름 가중치가 우선합니다. 추가를 확인하면 백그라운드에서 베이크하고 바인딩과 물리를 만듭니다. 계획 자체는 베이크하지 않습니다. 이전 `groups`와 `modifier_add_group`은 추가 의미를 유지해 기존 클라이언트와 호환합니다.

프리셋 라이브러리의 전환 가능한 항목은 `modifier_catalog.presets`가 반환합니다(`id`, `name`, 비울 수 있는 `name_key`). `modifier_switch.group`은 그 ID를 쓰며, 사용자가 저장하고 가져온 JSON 프리셋이 들어갑니다. `modifier_organize`는 `state`와 완전한 `nodes` 배열을 받습니다. 각 노드에는 `id`, 비울 수 있는 `parent`, 비울 수 있는 `name`이 있고, 이름이 있으면 폴더이며, 이름이 없으면 모디파이어 인스턴스 참조입니다. 조직 배치는 문서 트랜잭션과 함께 저장되고, 실행 순서는 바꾸지 않습니다. 실제 재정렬은 여전히 `modifier_move`를 씁니다. `modifier_stack.organization`이 배치를 반환합니다.

### 알고리즘 플러그인 관리

`plugin_list`, `plugin_inspect`, `plugin_install`, `plugin_enable`, `plugin_disable`, `plugin_uninstall`은 프로젝트를 열어 둘 것을 요구하지 않습니다. 목록 외에는 백그라운드 작업을 반환합니다. 설치 출처, 버전 고정, 요청 형식은 [알고리즘 플러그인](../guide/ALGORITHM_PLUGINS.md)을 참조합니다.

플러그인 설명에서 각 알고리즘의 `context_version`은 지원하는 컨텍스트 버전을 나타내고, `provides`는 제공하는 능력 별명을 나열합니다. `requires`는 여전히 실행 선행 항목을 뜻합니다.
