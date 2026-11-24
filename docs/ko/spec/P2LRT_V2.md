<!-- lang: zh en ko ja -->
> [中文](../../zh/spec/P2LRT_V2.md) · [English](../../en/spec/P2LRT_V2.md) · **한국어** · [日本語](../../ja/spec/P2LRT_V2.md)

# `.p2lrt` 2.0 형식

[런타임](RUNTIME.md)

상태: 컨테이너, 핵심 블록, `PGUI`, `META`, 확장 블록 `BONE`, `SKIN`, `COLL`, `SIMS`와 부가 블록 `EXPR`, `HITA`, `UDAT`, `POSE`는 구현되었습니다. 내보내기는 기본적으로 버전 2를 씁니다. 리더는 모든 압축 방식, 배열 인코딩, 텍스처 페이지 유형을 지원합니다. 라이터는 deflate와 zstd 압축을 쓸 수 있고, 배열은 f32이며, 텍스처 페이지는 내장 PNG입니다.

## 목표

- **진화 규칙을 한 번에 정합니다.** 2.0 이후에 기능을 더할 때는 블록만 추가하고, 이미 있는 기록 레이아웃은 바꾸지 않으며, 주 버전을 다시 올리지 않습니다.
- **핵심 층은 Cubism과 동등합니다.** 핵심 블록만 읽어도 완전히 재생할 수 있고, 결과는 v1과 비트 단위로 같으며, 의미는 moc3와 일대일입니다.
- **확장 층은 선택입니다.** 런타임 스키닝, 정점 시뮬레이션, 충돌 같은 고급 데이터는 선택 블록에 두고, 그것을 모르는 런타임은 Cubism 효과로 돌아갑니다(같은 파일의 이중 모드).
- **불러오기 쉽게 합니다.** 임의 접근, 필요 시 로드, 배열 제로 카피입니다. 파일 하나가 기존 사이드카 내용을 담을 수 있습니다.
- **재현할 수 있습니다.** 같은 IR을 쓰면 바이트가 완전히 같고, 타임스탬프 같은 변하기 쉬운 데이터는 넣지 않습니다.

목표가 아닌 것: 범용 장면 형식, 편집 가능한 프로젝트 형식(프로젝트는 여전히 `.psd2live`), 암호화나 난독화입니다.

## 컨테이너

리틀 엔디안입니다. 파일은 파일 헤더, 블록 목록(TOC), 블록 데이터로 이루어집니다.

```
Header  (32 字节)
  u8[8]  magic        "P2LRT\0\0\0"
  u16    major        = 2
  u16    minor        写出器所知的最高次版本，仅作信息
  u32    flags        bit0 名称已剥离；其余保留，写 0
  u32    chunk_count
  u32    toc_offset   = 32
  u32    reserved[2]  写 0
TOC     chunk_count × 40 字节
  u8[4]  tag          四字符 ASCII，大写为规范块，小写为厂商/私有块
  u16    version      块版本（布局与求值语义）
  u16    flags        bit0 必需；bit1 有 CRC；bit2–3 压缩方式（见下）；其余写 0
  u64    offset       块数据相对文件头的偏移，16 字节对齐
  u64    length       块在文件中存储的字节数（不含填充；压缩时为压缩后长度）
  u64    raw_length   解压后的字节数；未压缩时等于 length
  u32    crc32        对存储字节（压缩后）计算的 IEEE CRC32（bit1 为 0 时写 0）
  u32    reserved     写 0
Chunks  各块数据，块间用 0 填充到 16 字节对齐
```

- 파일 헤더의 `magic` 앞 5바이트는 v1과 같습니다. v1은 `magic` 뒤에 `u32 version = 1`이고, v2는 같은 위치에 `u16 major = 2, u16 minor`입니다. 리더는 u32를 읽은 뒤, 값이 1이면 v1로 해석하고, 하위 16비트가 2이면 v2로 해석하며, 나머지는 거부합니다.
- 같은 규범 태그는 파일 하나에 최대 한 번입니다. 파일 안의 블록 순서는 제한이 없고, 라이터는 이 문서 “핵심 블록” 표의 순서로 씁니다.
- 블록끼리는 겹칠 수 없고, 모든 블록은 파일 안에 있어야 합니다. 블록 뒤, 파일 끝 밖에는 남는 데이터가 있으면 안 됩니다(패딩은 제외).
- 오프셋과 길이는 u64입니다. 리더는 자기 주소 너비를 넘으면(예: WASM32) 그 파일을 거부하며, 잘라 내지 않습니다.

### 블록 압축

| flags bit2–3 | 방식 | 저장 내용 |
| --- | --- | --- |
| 0 | 없음 | 원본 블록 데이터 |
| 1 | deflate | zlib 스트림(RFC 1950) |
| 2 | zstd | zstd 프레임 하나(RFC 8878). 외부 사전은 쓰지 않습니다 |
| 3 | 예약 | 리더가 거부합니다 |

- 리더는 deflate와 zstd를 지원해야 합니다. 푼 뒤의 길이는 `raw_length`와 같아야 하며, 그렇지 않으면 거부합니다. 푼 결과는 16바이트 정렬 버퍼에 넣고, 그 뒤 압축하지 않은 블록으로 해석합니다.
- 압축은 저장만 바꾸고 블록 내용은 바꾸지 않습니다. 블록 안의 오프셋과 배열 정렬은 모두 푼 뒤의 데이터 기준입니다.
- 압축 블록은 제로 카피할 수 없습니다. 메모리 매핑이나 스트림 로드가 필요한 호스트는 압축하지 않은 파일을 골라야 합니다.
- 라이터는 기본적으로 압축하지 않습니다. 내보내기 옵션에서 압축을 켜면, 작아지는 블록만 압축합니다. 이미 압축된 텍스처 페이로드(PNG, KTX2)가 있는 `TEXR`은 압축하지 않습니다. 압축한 뒤 원본보다 작지 않으면, 그 블록은 압축하지 않은 채로 씁니다.
- 바이트 안정은 같은 라이터 버전, 같은 압축 설정에서만 요구합니다. 일치 비교는 푼 뒤의 블록 내용을 기준으로 합니다.

### 호환 규칙

| 경우 | 리더 동작 |
| --- | --- |
| `major`가 1도 2도 아님 | 거부 |
| 아는 태그이고, `version` ≤ 지원 버전 | 해당 버전으로 해석하며, `length` 바이트를 정확히 다 읽어야 합니다 |
| 아는 태그이고, `version`이 더 높음 | 모르는 블록으로 봅니다 |
| 모르는 블록, 필수가 아님 | 건너뜀 |
| 모르는 블록, 필수 | 거부. 오류 메시지에 그 태그를 나열합니다(“특성 XXXX가 필요”) |
| 필수 핵심 블록이 없음 | 거부 |
| CRC가 맞지 않음(호스트가 검증을 요구할 때만 검사) | 거부 |
| 압축 방식이 예약 값이거나, 풀기에 실패하거나, 푼 길이가 `raw_length`와 다름 | 거부 |

`minor`는 판단에 참여하지 않고, 진단에만 씁니다.

### 진화 규칙

1. 같은 주 버전 안에서, 이미 발표한 블록 버전의 레이아웃과 평가 의미는 영원히 바꾸지 않습니다.
2. 이미 있는 객체에 속성을 더할 때는 “평행 블록”을 새로 만들고, 객체 인덱스로 그 열을 저장하며, 원래 기록은 바꾸지 않습니다. 예를 들어 메시에 스키닝을 더하려면 `SKIN` 블록을 씁니다(메시 인덱스 → 스키닝 데이터). `MESH`는 그대로입니다.
3. 블록 안에서 “어떤 플래그가 1이면 바이트를 몇 개 더 읽는다” 같은 조건 필드는 허용하지 않습니다. 선택 내용은 모두 독립 블록이나 명시적 길이 포장으로 둡니다.
4. 어떤 블록의 평가 의미를 바꾸려면 그 블록 버전을 올려야 하고, 이전 버전은 여전히 이전 의미로 평가합니다.
5. 핵심 블록은 확장 블록에 영원히 의존하지 않습니다. 확장 블록은 핵심 블록과 더 일찍 정의된 확장 블록만 참조할 수 있습니다.
6. 벤더나 실험 블록은 소문자 태그를 쓰고, 필수가 아닌 블록이어야 합니다. 규범 블록은 대문자 태그를 씁니다.

## 기초 인코딩

| 이름 | 인코딩 |
| --- | --- |
| `u8/u16/u32/i32/f32` | 리틀 엔디안. f32는 유한 값이어야 합니다 |
| `ref` | u32 인덱스. 비울 수 있는 참조는 i32이며, -1이 빔 |
| `sref` | u32 문자열 표 인덱스(`STRS`). `0xFFFFFFFF`가 빔 |
| `rgb` | 3 × f32 |
| `str` | `STRS` 안에서만 씁니다. 다른 위치의 문자열은 모두 `sref`입니다 |
| `array<T>` | 먼저 0으로 채워 블록 안 4바이트 정렬에 맞춘 뒤, `u8 codec, u8 reserved[3], u32 count`를 쓰고, 이어서 원소 `count`개 |
| `grid<F>` | v1과 같음. `u16 axes`(`0xFFFF`는 격자 없음) × `{ ref parameter, array<f32> keys }`, `u32 cells` × `{ u16 coordinate[axes], F }` |
| `channels` | v1과 같음. `u8 n` × `{ u8 channel, u8 kind, grid<value> }`. channel 순서는 DrawOrder, Opacity, MultiplyColor, ScreenColor, FlipX, FlipY, GlueIntensity. kind 0은 스칼라, 1은 색, 2는 플래그 |
| `bindings<S>` | v1과 같음. `u16 n` × `{ ref parameter, array<f32> keys, u32 neutral, u8 present + 키 포인트마다 S, u16 n × { ref limit_parameter, u16 n × { f32 value, f32 weight } } }` |

배열 인코딩(`codec`):

| 값 | 원소 | 용도 |
| --- | --- | --- |
| 0 | f32 | 기본. 모든 일치 테스트가 사용 |
| 1 | f16 | 선택적 용량 최적화(위치, 오프셋) |
| 2 | unorm16 | 선택적 용량 최적화(UV, 범위 [0,1]) |
| 16 | u16 | 인덱스. 정점 수 ≤ 65535일 때 |
| 17 | u32 | 인덱스 |
| 32 | u8 | 바이트열(텍스처 페이로드) |

부동소수점 배열의 codec 1, 2는 호스트가 “배포 용량 최적화”를 명시적으로 골랐을 때만 씁니다. 리더는 모든 인코딩을 지원해야 하며, 디코딩 뒤에는 f32와 동등하게 다룹니다. 배열은 스스로 정렬합니다. 헤더는 블록 안 4바이트 정렬 지점에서 시작하고 길이는 8바이트이므로, 원소도 4바이트 정렬입니다. 블록 자체는 16바이트 정렬이므로, codec 0/16/17 배열은 슬라이스로 바로 읽을 수 있습니다. 배열 앞의 패딩 외에는 기록 안에 다른 패딩이 없고, 스칼라는 정렬하지 않아도 됩니다.

## 핵심 블록

모두 필수 블록입니다(bit0 표시). 블록 버전은 `PHYS`를 제외하고 모두 1이며, 평가 의미는 [런타임](RUNTIME.md)의 “평가 규칙”, “물리”, “모션 클립” 각 절입니다. 필드 순서와 개수 너비는 v1과 같고, 차이는 이것뿐입니다. 문자열은 `sref`로 바꾸고, 부동소수점 배열, 인덱스, 바이트열은 `array<T>`로 바꾸며, 텍스처 페이지에 유형을 더합니다.

| 태그 | 내용 | 반드시 존재 |
| --- | --- | --- |
| `STRS` | 문자열 표 | 예 |
| `CANV` | 캔버스: f32 width, height, originX, originY, pixelsPerUnit(−1은 없음) | 예 |
| `PARM` | 파라미터 | 예(0개일 수 있음) |
| `DEFM` | 디포머. 부모가 앞 | 예(0개일 수 있음) |
| `PART` | 파츠 | 예 |
| `MESH` | 메시 | 예 |
| `GLUE` | Glue | 아니오(없으면 0개) |
| `DRAW` | 그리기 트리 | 예 |
| `TEXR` | 텍스처 페이지 | 아니오(없으면 텍스처 없음) |
| `PHYS` | 물리 그룹 | 아니오 |
| `CLIP` | 모션 클립 | 아니오 |
| `ROLE` | 파라미터 역할 | 아니오 |

모션 클립의 이벤트, 파라미터가 아닌 곡선, 곡선 페이드 인·아웃은 필수가 아닌 부가 블록 `CEXT`에 있습니다(아래 “부가 블록” 참조).

“반드시 존재”가 아니오인 블록이 없으면 빈 집합으로 처리합니다. 있을 때는 여전히 필수로 표시합니다. 핵심 평가에 참여하기 때문입니다.

### `STRS`

```
u32 count
u32 offsets[count + 1]   每个字符串在 blob 中的起止偏移
u8  blob[...]            UTF-8，不以 0 结尾
```

라이터는 중복을 제거합니다. 파일 헤더 flags bit0(이름을 벗김)이 1이면, 모든 `name` 필드는 빈 `sref`이고, `id`는 남으며, 호스트는 id로 파라미터와 메시를 찾습니다.

### `PARM`

`u32 n` × `{ sref id, sref name, f32 min, max, default, u8 flags (1 blend, 2 repeat) }`

### `DEFM`

`u32 n` × 디포머. 부모는 자식보다 앞에 있어야 합니다.

```
u8 kind (0 Warp, 1 Rotation), sref id, i32 parent, i32 part,
u8 flags (1 visible, 2 enabled, 4 flipX, 8 flipY, 16 bilinear),
f32 opacity, rgb multiply, rgb screen
Warp:     u32 columns, rows, grid<array<f32> points>, channels,
          bindings<{ array<f32> points, f32 opacity, rgb multiply, rgb screen }>
Rotation: f32 baseAngle, grid<{ f32 x, y, angle, scale }>, channels,
          bindings<{ f32 x, y, angle, scale, u8 flipX, u8 flipY, f32 opacity, rgb multiply, rgb screen }>
```

### `PART`

```
u32 n × { sref id, sref name, u8 flags (1 visible, 2 sketch), u8 groupMode, i32 drawOrder,
          u32 n × { u8 kind (0 part, 1 mesh), ref target },
          channels, composite,
          bindings<{ f32 drawOrder, f32 opacity, rgb multiply, rgb screen }> }
composite = { u8 blend, u8 alphaBlend, u32 n × ref maskMesh, u32 n × ref maskPart,
              u8 invertMask, f32 opacity, rgb multiply, rgb screen }
```

### `MESH`

```
u32 n × { sref id, sref name, i32 parent, u8 blend, u8 alphaBlend, u32 n × ref maskMesh,
          u8 flags (1 invertMask, 2 culling, 4 visible, 8 geometry), i32 texturePage,
          [geometry] array<f32> positions, array<f32> uvs, array<u16|u32> indices,
          grid<array<f32> deltas>, channels,
          f32 drawOrder, f32 opacity, rgb multiply, rgb screen,
          bindings<{ array<f32> deltas, f32 drawOrder, f32 opacity, rgb multiply, rgb screen }> }
```

`[geometry]`는 flags bit3이 1일 때만 나타납니다. `CLIP`에서 Bezier 구간의 제어점도 구간 유형이 1일 때만 나타납니다. 이 두 곳은 v1이 남긴 조건 필드이며, v1과 일대일로 맞추려고 남긴 것이고, 규칙 3의 유일한 예외입니다.

### `GLUE`

`u32 n × { sref id, ref meshA, ref meshB, f32 intensity, u32 n × { u32 a, u32 b, f32 weightA, f32 weightB }, channels }`

### `DRAW`

그리기 트리의 루트 그룹이며, 재귀입니다. `{ i32 part, i32 drawOrder, channels, u8 hasComposite, [composite], u32 n × { u8 kind (0 group, 1 mesh), group | ref mesh } }`

### `TEXR`

```
u32 n × { u8 kind, u32 width, height, payload }
kind 0  PNG       payload = array<u8> png      所有运行时必须支持
kind 1  KTX2      payload = array<u8> ktx2     可选；不支持的运行时报告该页不可用
kind 2  外部文件  payload = sref uri           相对模型文件的路径，由宿主加载
```

`p2l_texture_png`은 PNG가 아닌 페이지에 빈 값을 반환합니다. 추가된 `p2l_texture_info(i)`는 kind, 너비·높이, uri를 반환합니다.

### `PHYS`

`f32 fps (0이면 런타임 기본), u32 n × { sref id, sref name, inputs, outputs, segments, normalization }`. 각 필드는 v1과 같습니다.

입력과 출력의 `u8 source`는 0이 가로(X), 1이 각도, 2가 세로(Y, Cubism physics3의 `Y`)입니다. 블록 버전 2는 세로를 허용합니다. 라이터는 세로를 쓸 때만 버전 2를 쓰고, 그보다 앞선 런타임은 이것으로 “PHYS 버전 2가 필요”를 보고하며, 잘못 읽지 않습니다. 버전 1의 파일 레이아웃은 완전히 같습니다. 세로 입력은 정규화 값을 뿌리의 y 변위에 더하고, 세로 출력은 그 정점이 이전 정점 대비 가지는 y 오프셋을 읽습니다. 가로와 같이, 평행 이동 출력의 스케일은 0입니다(Cubism의 동작). 버전 1 파일(내보내기 옵션 `v1`)은 세로 입력·출력을 쓰지 않고, 손실 보고서에 적습니다.

### `CLIP`

v1과 같으며, 문자열은 `sref`로 바꿉니다.

### `ROLE`

`u32 n × { sref role, u32 n × ref parameter }`

## 파라미터 인터페이스 블록 `PGUI`(필수 아님)

호스트가 파라미터 패널을 보여 주는 메타데이터이며, 평가에 참여하지 않습니다. 모르거나 필요 없는 런타임은 바로 건너뜁니다. 블록 버전 1:

```
u32 n × { ref parameter, array<f32> keys }        吸附值：只列有吸附值的参数，keys 升序
u32 n × { ref horizontal, ref vertical }          二维摇杆：两个参数组成一个平面控件
u32 n × node                                      参数分组树，先序展开
node = { u8 kind (0 参数, 1 分组), u8 open, u8 label (0 无, 1 预设, 2 自定义), u8 reserved,
         i32 parameter (分组为 -1), sref id, sref name, sref preset, u32 argb, u32 children }
```

- 노드는 고정 길이 기록이며, 모든 필드를 항상 씁니다. 파라미터 노드의 `id`, `name`, `preset`은 비어 있고, 그룹 노드의 `parameter`는 -1입니다. `label`이 1이 아니면 `preset`은 비어 있고, 2가 아니면 `argb`는 0입니다. `children`은 바로 뒤따르는 직접 자식 노드 수입니다.
- 파일 헤더 flags bit0(이름을 벗김)이 1이면, 그룹 `name`은 비어 있습니다.
- 그룹 트리에 들어가지 않은 파라미터는 호스트가 `PARM` 순서대로 끝에 둡니다.
- `RigIR.restPose`는 캔버스 공간 기초 메시로 저장하는 형식(예: Spine)에만 쓰입니다. p2lrt는 파라미터로 직접 평가하므로 쓰지 않습니다.

## 정보 블록 `META`(필수 아님)

`u32 n × { sref key, sref value }`. 규범 키: `generator`, `generator_version`, `source_hash`(프로젝트 원본 데이터 해시. 캐시와 진단에 사용), `capabilities`(공백으로 구분한 파일 안 확장 태그. 호스트가 불러오기 전에 보여 줌). 타임스탬프는 쓰지 않아 재현할 수 있습니다.

## 평가 단계와 확장 걸이점

핵심 평가는 다음 단계로 고정됩니다. 확장 블록은 표시된 걸이점에서만 작용하며, 블록 규범은 어느 점에 걸고 어떤 기여를 바꾸는지 적어야 합니다.

| 번호 | 단계 | 핵심 내용 | 확장 걸이점 |
| --- | --- | --- | --- |
| 1 | 파라미터 | 모션 클립 → 절차적 동작 → Cubism 진자 → 클램프 / 랩 | **H1 파라미터 덮어쓰기**: 바뀐 파라미터는 이번 평가에서 기본값을 쓰고, 바뀐 진자 그룹은 건너뜁니다 |
| 2 | 디포머 | 부모가 앞. Warp / 회전 프레임 | **H2 프레임 교체**: 정확히 합성한 프레임으로 지정 디포머를 바꿉니다(예: 접힌 링크) |
| 3 | 본 프레임 | 없음(핵심 층에는 본이 없음) | **H3 본 프레임**: 디포머 프레임이나 가상 프레임에서 각 본의 캔버스 변환을 얻어, H4, 충돌체, 호스트 장식에 씁니다 |
| 4 | 메시 | 정지 위치 + 키폼 오프셋 + 블렌드 셰이프 → 부모 변환 | **H4 메시 기여 교체**: 지정 메시의 지정 기여(스키닝 축 키폼, 지정 블렌드 셰이프)를 확장 계산 결과로 바꾸고, 나머지는 그대로입니다 |
| 5 | Glue | 정점 쌍이 서로 당김 | 없음 |
| 6 | 시간 스텝 후처리 | 없음 | **H6 정점 시뮬레이션**: dt가 필요합니다. 이번 평가의 정점을 목표로 고정 스텝 시뮬레이션을 하고, 대상 메시 정점을 덮습니다. 충돌은 이 단계 안에서 풉니다 |
| 7 | 그리기 순서 | 그리기 트리 정렬 | 없음 |

걸이점의 공통 규칙:

- 확장을 켜지 않으면 모든 걸이점은 무조작이고, 코드 경로는 핵심 평가와 같습니다.
- H1의 덮어쓰기는 평가 전체에 작용합니다. 라이터는 바뀐 기하만 구동하는 파라미터(예: `ParamSim*`)만 나열할 수 있으므로, 채널은 영향을 받지 않습니다.
- `p2l_evaluate`(dt 없음)는 H6을 진행하지 않습니다. 시뮬레이션 메시는 지난 시뮬레이션 상태를 표시하고, 한 번도 진행하지 않았으면 목표를 표시합니다.

### 확장 블록의 공통 헤더

각 확장 블록의 페이로드는 통일된 덮어쓰기 선언으로 시작합니다. 리더는 블록 내용을 이해하지 않아도 무엇을 바꾸는지 알 수 있습니다(진단과 `META` 표시용).

```
u16 feature_bit           对应 p2l_set_advanced 的特性位
u16 hooks                 位集：H1=1, H2=2, H3=4, H4=8, H6=32
u32 n × ref parameter     H1 被覆盖为默认值的参数
u32 n × ref physicsGroup  H1 被跳过的摆锤组
u32 n × ref deformer      H2 被替换框架的变形器
u32 n × { ref mesh, u32 n × ref axisParameter, u32 n × u32 binding }
                          H4 被替换的贡献：这些关键形轴取默认值、这些混合形不计入
u32 n × u8[4] depends_on  依赖的其他扩展块标签
```

그 뒤가 블록 자신의 내용입니다. 의존 블록이 없거나 켜지지 않으면, 그 확장은 자동으로 멈춥니다.

### 특성 비트와 모드 스위치

| 비트 | 블록 | 설명 |
| --- | --- | --- |
| 1 | `SKIN`(가상 본을 참조할 때 `BONE`이 필요) | 런타임 스키닝 |
| 2 | `BONE`의 원호 | 정밀 링크(원호를 따라 보간하는 피벗) |
| 4 | `SIMS` | 정점 단위 실시간 시뮬레이션 |
| 8 | `COLL`(`SIMS`가 필요. 시뮬레이션과 함께 켜짐) | 충돌 |

- `p2l_advanced_available()`는 파일이 가지고 있고 런타임이 지원하는 비트를 반환합니다. `p2l_set_advanced(bits)`는 실제로 켠 비트를 반환하고, 0은 Cubism 모드입니다(기본). 전환할 때 시뮬레이션 상태를 재설정합니다.
- `p2l_bone_count / p2l_bone_id / p2l_bone_transform`은 H3의 본 프레임을 출력합니다.
- 시간 스텝 확장은 고정 스텝이어야 합니다(스텝 길이는 블록 안에 씀). 단일 스레드, 고정 순회 순서이며, 업데이트마다 스텝 수에 상한이 있고 넘는 시간은 버립니다. 출력은 마지막 두 스텝 사이를 보간하며, `PHYS`의 방식과 같습니다.

## 확장 블록 `BONE`(버전 1)

```
公共头                      feature 2，hooks H2 | H3，deformers = 有圆弧的变形器
u32 n × deformer            虚拟骨骼：DEFM 格式的旋转变形器记录，第 i 个的下标为 n_DEFM + i；
                            父级是 DEFM 中的变形器或更早的虚拟骨骼
u32 n × { ref deformer, sref id }            宿主可挂接的骨骼（DEFM 或虚拟骨骼）
u32 n × { ref deformer, ref parameter, f32 cx, cy }   圆弧
```

- **가상 본**: 스켈레톤을 베이크할 때 메시가 직접 걸리지 않아 잘리거나 접힌 본입니다. 런타임 스키닝에서만 평가하고, 핵심 평가에는 참여하지 않습니다. 본 파라미터와 각도의 관계는 베이크 전의 회전 디포머와 같습니다. 각도 = 파라미터 값 × 방향, 피벗은 본 위치입니다.
- **원호**: 그 회전 디포머의 피벗이 `parameter` 축 하나에만 키를 두고, 각 키의 위치가 (cx, cy)를 중심으로 한 같은 원 위에 있습니다(부모 공간). 정밀 링크를 켜면, 이웃한 두 키 사이의 피벗 위치는 각도와 반지름으로 보간하며, 현을 취하지 않습니다. 라이터는 키가 넷 이상이고, 모두 같은 원 위에 있으며, 도는 방향이 같고, 스텝마다 반 바퀴보다 작은 피벗에만 원호를 씁니다.
- `p2l_bone_count / p2l_bone_id / p2l_bone_transform`은 걸 수 있는 본을 나열합니다. 가상 본의 변환은 현재 파라미터로 즉시 구합니다.

## 확장 블록 `SKIN`(버전 1)

```
公共头                      feature 1，hooks H4，不替换任何贡献
u32 n × {
  ref mesh                  其父级是旋转变形器，且在 bones 中
  u32 n × { ref parameter, array<f32> keys }   蒙皮轴：烘焙时取样的参数及其键
  u32 n × ref deformer      骨骼：DEFM 或虚拟骨骼中的旋转变形器
  array<u16|u32> from, array<u16|u32> to, array<f32> weight   每顶点的两骨权重；长度为 0 时由运行时拟合
}
```

평가(H4, 메시의 부모 공간, 부모 변환 전):

1. 각 본 b가 정지 포즈 대비 정점을 옮기는 변환: A_b = H⁻¹ · B · B_rest⁻¹ · H_rest. H는 메시 부모(home)의 프레임, B는 본 프레임이며, 모두 현재 파라미터에서의 캔버스 변환입니다.
2. 두 본 선형 혼합: LBS(v) = (1 − w) · A_from(v) · p + w · A_to(v) · p. p는 정지 위치입니다.
3. 메시에 LBS(현재 값) − Σ_c w_c · LBS(c)를 더합니다. c는 현재 값 양쪽의 스키닝 축 키 조합을 돌고, w_c는 베이크와 같은 다중 선형 가중치입니다. 나머지 파라미터는 현재 값을 유지합니다.

이 보정은 각 키 조합에서 정확히 0이므로, 고급 모드는 베이크한 각 키에서 Cubism 모드와 같습니다. 키 사이에서는 베이크의 현을 본의 호로 바꿉니다. 가중치를 주지 않으면, 런타임은 켤 때 맞춥니다. 모든 키 조합(최대 1024개, 넘으면 축별)에서 각 정점에 베이크 위치를 가장 잘 재현하는 본 쌍과 가중치를 고르고, 동점이면 본 하나를 강체로 따릅니다.

라이터는 부모가 회전 디포머이고, 그 아래(회전 디포머로 연결, 가상 본을 포함할 수 있음)에 메시 축으로 키를 둔 회전 디포머가 있는 메시에 `SKIN`을 씁니다. 본은 최대 16개, 축은 8개입니다. 다리 메시는 선 자세 Warp에 걸려 있으며, 베이크를 유지합니다.

## 확장 블록 `COLL`(버전 1)

```
公共头                      feature 8，hooks H6
u32 n × { sref id, u8 capsule, i32 deformer, i32 mesh, u32 vertexA, u32 vertexB,
          f32 ax, ay, bx, by, radiusA, radiusB, friction }
```

충돌체는 원(capsule이 0, a 끝만 사용)이거나, a에서 b까지 반지름이 radiusA에서 radiusB로 변하는 캡슐입니다. 반지름은 캔버스 픽셀입니다. 위치는 다음 중 하나로 정합니다.

- **메시 정점을 따름**: mesh가 −1이 아니면, 양 끝은 그 메시의 vertexA / vertexB가 이번 평가에서 있는 위치입니다. 편집기가 내보낸 충돌체는 모두 이 방식이므로, 편집기와 런타임이 보는 위치가 같습니다.
- **디포머를 따름**: deformer가 −1이 아니면(가상 본일 수 있음), (ax, ay), (bx, by)는 그 디포머 국소 공간의 점입니다.
- **고정**: 둘 다 −1이면, 양 끝은 캔버스의 고정점입니다.

friction은 0–1이며, 접촉할 때 상쇄하는 미끄러짐 비율입니다. 충돌은 시뮬레이션에서만 작용합니다. `SIMS`의 각 시뮬레이션이 피할 충돌체를 나열합니다.

## 확장 블록 `SIMS`(버전 1)

```
公共头                      feature 4，hooks H1 | H4 | H6；parameters、physics groups 为各模拟替换项的并集
u32 n × {
  sref id, f32 fps, u32 substeps, f32 gravityX, gravityY, windX, windY, pinCompliance
  u32 n × { ref mesh, u32 vertexCount }                 目标网格，粒子按此顺序排列
  array<f32> invMass, damping, windFactor, pinWeight, goalCompliance, goalOffsetX, goalOffsetY
  array<u32> anchorMesh (0xFFFFFFFF 为跟随自身), array<u32> anchorVertex
  array<u32> stretchA, stretchB; array<f32> rest, compliance, compressionCompliance
  array<u32> triA, triB, triC; array<f32> areaCompliance
  array<u32> bendT1, bendT2; array<f32> bendCompliance
  array<u32> weldA, weldB; array<f32> weightA, weightB, weldCompliance
  array<u32> longParticle, longRoot; array<f32> maxDistance
  u32 n × ref parameter                                 本模拟替换的烘焙模式参数
  u32 n × u32 physicsGroup                              本模拟替换的摆锤组（PHYS 下标）
  u32 n × { ref parameter, array<f32> keys, u32 n × { ref mesh, keys × array<f32> offsets } }   烘焙加入的静态修正
  u32 n × u32 collider                                  COLL 下标
}
```

장면과 편집기의 XPBD 솔버(`XpbdSolver`)와 항목마다 대응합니다. 월드 공간은 캔버스 x, 부호를 뒤집은 캔버스 y입니다(y가 위). compliance가 음수이면 그 제약이 없습니다(편집기의 무한대).

시뮬레이션을 켠 뒤:

- **H1**: 바꾼 모드 파라미터는 기본값을 유지하고, 대응 진자 그룹은 건너뜁니다.
- **H4**: 대상 메시에서 정적 보정을 뺍니다(키 사이는 선형 보간, 베이크가 키폼에 쓰는 방식과 같음). 이때 평가된 대상 메시가 베이크하지 않은 바인딩이며, 각 입자의 목표입니다. 고정된 입자는 자신 또는 따라가는 메시 정점의 위치를 따릅니다.
- **H6**: `p2l_update`는 장면 자신의 fps로 고정 스텝을 진행합니다. 업데이트마다 최대 4스텝이고, 넘는 시간은 버리며, 그리기는 마지막 두 스텝 사이를 보간합니다. `p2l_evaluate`는 이전 스텝만 그립니다. 첫 업데이트 때 입자는 그때의 포즈에서 정지합니다. `p2l_sim_reset`은 다음 업데이트가 다시 시작하게 하고, `p2l_sim_wind`는 호스트의 바람을 겹칩니다.

내보낼 때(런타임과 웹 대상만), 편집기는 최종 바인딩 위에서 베이크된 시뮬레이션마다 다시 구축하고 교정합니다. 기본 포즈에서 모드 파라미터는 0이고 정적 보정은 정지 키에 있으므로, 장면은 베이크할 때와 같습니다.

## 부가 블록(필수 아님, 버전 1, 공통 헤더 없음)

### `EXPR`

```
u32 n × { sref id, sref name, f32 fadeIn, f32 fadeOut, u32 n × { ref parameter, u8 blend (0 Add, 1 Multiply, 2 Overwrite), f32 value } }
```

표정은 Cubism의 exp3와 같습니다. 가중치 w는 사인 이징의 페이드 인·아웃 계수이며, 각 혼합 방식의 계산은 다음과 같습니다.

- Add: 값 + v·w
- Multiply: 값 · (1 + (v − 1)·w)
- Overwrite: 값 · (1 − w) + v·w

런타임은 한 번에 표정 하나를 재생하고, 전환할 때 이전 표정이 페이드 아웃합니다. 갱신 순서는 모션 클립 → 표정 → 절차적 동작 → 물리입니다. 내보낼 때 편집기는 바인딩에 있는 파라미터로 미소, 슬픔, 화남, 놀람 표정 네 개를 만들고, 모델에 해당 모듈 파라미터가 있으면 수줍음, 졸림, 침울을 더합니다. 페이드 인·아웃은 각각 0.5초입니다.

### `HITA`

```
u32 n × { sref id, sref name, u32 n × ref mesh }
```

클릭 영역은 그 메시가 그리는 범위를 덮습니다. `p2l_hit_test(x, y)`는 파일 순서대로, 보이는 메시가 그 캔버스 점을 포함하는 첫 영역을 반환합니다. 내보낼 때 문서 설정에 있는 클릭 영역을 씁니다([내보내기 대상](EXPORT_TARGETS.md#클릭-영역) 참조). 문서에 설정이 없으면 `HitAreaHead`(얼굴)와 `HitAreaBody`(상의, 없으면 목 또는 하의)를 생성합니다.

### `UDAT`

```
u32 n × { u8 kind (0 = 网格), ref target, sref value }
```

메시의 사용자 데이터입니다(가져온 CMO3에서). `p2l_mesh_user_data`로 읽습니다.

### `POSE`

```
f32 fadeIn, u32 n × { u32 n × { ref part, u32 n × ref linkedPart } }
```

파츠 상호 배타 그룹은 Cubism의 pose3와 같습니다. 각 그룹은 동시에 파츠 하나만 표시하고, 전환할 때 선택된 파츠는 fadeIn 초 안에 페이드 인하며, 나머지는 Cubism 규칙(φ = 0.5, 뒷면 불투명도 임계값 0.15)으로 페이드 아웃합니다. 링크 파츠는 소속 파츠를 따릅니다.

이것은 평가 뒤의 한 단계입니다. 메시 불투명도에 모든 조상 파츠의 포즈 불투명도를 곱합니다. 어느 그룹에도 없는 파츠는 1이므로, `POSE`가 없는 파일의 평가는 변하지 않습니다. 핵심 평가에서 파츠 불투명도는 여전히 메시 불투명도에 들어가지 않습니다. 처음에는 각 그룹이 첫 파츠를 표시합니다.

호스트는 `p2l_pose_show(group, entry)`로 전환합니다. 내보내기 설정 `pose_groups`는 `파츠|파츠; 파츠|파츠`로 그룹을 지정하고, `파츠+연결 파츠`는 링크를 나타냅니다.

### `CEXT`

```
u32 n × { ref clip,
          u32 n × { f32 time, sref value },                                    事件，按时间升序
          u32 n × { u8 target, i32 part, f32 fadeIn, f32 fadeOut,
                    f32 startTime, f32 startValue, u32 n × segment },          非参数曲线
          u32 n × { u32 curve, f32 fadeIn, f32 fadeOut } }                     片段自身曲线的淡入淡出
```

모션 클립의 확장입니다. Cubism motion3에서 파라미터 곡선 이외의 내용에 대응합니다. 필수가 아니며, 모르는 런타임은 `CLIP`의 파라미터 곡선만 재생합니다. 클립마다 기록은 최대 한 줄입니다.

- **이벤트**(motion3의 `UserData`): 클립 시간이 이벤트 시간을 지날 때 `p2l_event_count` / `p2l_event`로 호스트에 알립니다. 반복 클립은 바퀴마다 한 번 발생하고, 건너뛰기(`p2l_clip_seek`)로 넘어간 이벤트는 발생하지 않습니다.
- **파라미터가 아닌 곡선**: `target` 1은 파츠 불투명도(`part`는 파츠 인덱스), 2는 전체 불투명도, 3은 깜빡임 효과, 4는 입 모양 효과입니다(뒤의 셋은 `part`에 −1을 씀). 곡선 구간 인코딩은 `CLIP`과 같습니다. 파츠 불투명도와 전체 불투명도는 곡선 값으로 직접 설정합니다(페이드 인·아웃을 따르지 않음. Cubism과 같음). 파츠 아래의 메시와 모델 전체에 곱으로 작용합니다. 깜빡임 효과는 `EyeBlink` 역할 파라미터에 곱하고, 입 모양 효과는 `LipSync` 역할 파라미터에 더합니다. 클립에 그 파라미터 곡선이 있으면 곡선 값에 작용하고, 없으면 클립 가중치로 현재 값에 작용합니다.
- **페이드 인·아웃**: 음수는 클립 설정을 그대로 쓴다는 뜻입니다. `curve`는 그 곡선이 `CLIP`에 쓰인 순번입니다(파일에 있는 파라미터만 셉니다). 자체 페이드 인·아웃이 있는 곡선은 Cubism 규칙으로 가중치를 취합니다. 페이드 인 인자는 곡선의 페이드 인(또는 클립의 것)을 쓰고, 페이드 아웃 인자는 곡선의 페이드 아웃(또는 클립의 것)을 쓰며, 둘을 곱합니다.

확장 블록의 공통 제약:

- 핵심 평가의 특정 기여만 바꾸거나 보충합니다(예: 보정 키폼, `ParamSim*`이 구동하는 형태). 파라미터, 모션, 동작, Cubism 진자는 그대로 구동합니다.
- 기본은 끔입니다. 호스트가 `p2l_set_advanced(features)`로 켜고, 끄면 평가 결과는 핵심 블록만 있는 파일과 비트 단위로 같습니다.
- 라이터는 핵심 블록에 대응하는 Cubism 베이크 결과를 항상 남깁니다.

## 런타임과 라이터

- **읽기**: v1과 v2를 함께 지원합니다. 둘 다 같은 메모리 구조 `Rig`로 해석하고, 평가 코드는 한 벌입니다.
- **쓰기**: `P2lrtTarget`은 기본적으로 v2를 씁니다. 내보내기 옵션 `v1`은 핵심 내용의 v1 레이아웃만 쓰며, 이때 확장 데이터가 있으면 `CapabilityScan`이 베이크 형태로 남았다고 보고합니다. `compress`는 작아지는 블록을 deflate로 압축하고, `strip_names`는 이름을 벗깁니다.
- **능력 조회**: `p2l_format_support()`를 추가했습니다. 지원하는 주 버전과 블록 태그 및 버전 목록을 반환합니다. `p2l_rig_load_ex(bytes, len, flags)`의 flags bit0은 CRC 검증을 요구합니다.
- **압축 의존**: Rust 읽기 쪽은 순수 Rust의 `miniz_oxide`(deflate)와 `ruzstd`(zstd 디코딩)를 쓰며, WASM으로 컴파일할 수 있고, 런타임은 디코딩만 하면 됩니다. 그래서 WASM 빌드는 208 KB에서 400 KB로 늘고, 그중 버전 2 읽기와 deflate가 약 79 KB, zstd가 약 113 KB입니다. Kotlin 쓰기 쪽의 deflate는 JDK `Deflater`를 쓰고, zstd는 순수 Java의 aircompressor를 씁니다.
- **바이트 안정**: 같은 IR을 두 번 쓰면 바이트가 같습니다. 이름 벗기기와 배열 인코딩은 내보내기 옵션이 결정하며, 환경의 영향을 받지 않습니다.

## 검증

- **v1/v2 비트 단위 일치**: `RuntimeConformanceTool`의 모든 무작위 모델, 예제, 물리 궤적을 v1과 v2로 함께 쓰고, 런타임 평가 결과를 `to_bits()`로 비교합니다. 정점, 불투명도, 색, 그리기 순서는 완전히 같아야 합니다.
- **리더**: v1은 받습니다. v2 최소 파일은 받습니다. 알 수 없는 선택 블록은 건너뜁니다. 알 수 없는 필수 블록은 거부하고 태그를 보고합니다. 블록이 범위를 넘거나, 겹치거나, 더 읽거나, 덜 읽거나, 규범 태그가 중복되거나, 필수 핵심 블록이 없거나, 정렬이 잘못되거나, CRC가 맞지 않으면(검증을 켰을 때) 모두 거부합니다. Rust(`runtime/src/tests.rs`)와 Kotlin(`P2lrtTest.kt`)에 각각 한 벌입니다.
- **재현**: 같은 IR, 같은 설정을 두 번 쓰면 바이트가 같습니다. `STRS`는 중복 제거 뒤 쓰기 순서와 무관한 내용이 같습니다.
- **압축**: 같은 모델을 무압축, deflate, zstd로 쓰면, 푼 뒤 각 블록 내용이 바이트 단위로 같고, 평가가 비트 단위로 같습니다. `raw_length`가 맞지 않거나, 잘린 압축 스트림이거나, 예약된 압축 방식이면 거부됩니다. Kotlin이 쓰고 Rust와 WASM이 읽는 세 방식을 모두 덮습니다.
- **`PGUI`**: 그룹 트리, 2차원 스틱, 스냅 값을 쓴 뒤 읽어 `RigIR`과 같습니다. `PGUI`를 빼도 평가는 변하지 않습니다.
- **배열 인코딩**: f16 / unorm16 모델과 f32 모델의 정점 오차는 인코딩 정밀도 안이며, 따로 보고하고, 비트 단위 일치 말뭉치에는 넣지 않습니다.
- **언어를 넘김**: Kotlin이 쓰고 Rust가 읽습니다. WASM 빌드는 같은 리더 테스트 묶음을 통과합니다.
- **확장이 있어도 Cubism 모드는 변하지 않음**: 확장 블록이 있는 파일은 특성 비트가 0일 때, 확장 블록을 뺀 같은 파일과 평가가 비트 단위로 같습니다. 켰다가 꺼도 같습니다(남은 상태 없음).
- **확장의 참조 구현**: 각 확장 블록에는 Kotlin 참조 구현이 있고, `RuntimeConformanceTool`의 무작위 말뭉치에 넣어 런타임과 대조합니다.

## 고급 모드 노선과의 관계

이 형식은 런타임 고급 모드(런타임 스키닝, 정밀 링크, 정점 시뮬레이션, 충돌, 같은 파일의 이중 모드)의 컨테이너 기초입니다. 그 “단계 0 형식 파이프”에 대응하고, 그 노선이 처음에 생각한 “v1 본체 뒤에 블록을 추가”하는 방안을 대체합니다. 핵심 내용도 다시 블록으로 나누고, 모든 내용이 같은 진화 규칙을 따릅니다. 이후 단계는 각자 `BONE`/`SKIN`, `SIMS`, `COLL`의 블록 안 레이아웃을 정의하며, 걸이점, 공통 헤더, 특성 비트는 이 문서를 기준으로 합니다.

노선에서 실측 결과를 보고 정하기로 한 몇 가지는 다음과 같이 결정했습니다.

- **런타임 ARAP**: 시행하지 않습니다. 스키닝 보정은 각 키에서 0이고, 고급 모드는 베이크한 각 키에서 그것과 완전히 같습니다(일치 예제는 모두 0). 키 사이는 이미 본 호를 따릅니다.
- **선 자세 Warp에 걸린 메시(다리)**: 베이크를 유지합니다. 다리는 선 자세 Warp 안에서 굽고, 스켈레톤은 다리 본의 회전 프레임을 만들지 않아 스키닝이 프레임을 가져올 곳이 없습니다.
- **네 본 가중치**: 필요 없습니다. 편집기의 스키닝은 정점마다 최대 두 본을 넘습니다.

## 정해진 사항

- 블록 압축: deflate와 zstd 두 가지를 정의하고, 리더는 둘 다 지원해야 합니다. 라이터는 기본적으로 압축하지 않고, 내보내기 설정 `compress`는 deflate를 고르며, `zstd`를 더하면 zstd를 고릅니다.
- 오프셋과 길이 너비: u64.
- 파라미터 그룹, 2차원 스틱, 스냅 값은 필수가 아닌 `PGUI` 블록에 넣고, `PARM`은 v1과 일대일입니다. `restPose`는 쓰지 않습니다.
