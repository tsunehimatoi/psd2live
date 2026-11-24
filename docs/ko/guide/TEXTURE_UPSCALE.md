<!-- lang: zh en ko ja -->
> [中文](../../zh/guide/TEXTURE_UPSCALE.md) · [English](../../en/guide/TEXTURE_UPSCALE.md) · **한국어** · [日本語](../../ja/guide/TEXTURE_UPSCALE.md)

# 텍스처 고해상도화

[문서 목록](../README.md) · [조작 요약](USER_GUIDE.md) · [개발과 명령줄](DEVELOPMENT.md)

프로그램 안 기초 경로의 19과에 해당합니다. 고해상도화는 텍스처를 묶기 전에 레이어마다 텍스처를 확대하며, 기본은 꺼져 있습니다. 원본 PSD, 캔버스 크기, 리그는 바꾸지 않습니다. 미리보기와 내보내기는 처리된 텍스처를 사용합니다.

## 설정과 사용

**모델 → 텍스처 고해상도화…**(기본 `Ctrl+U`)를 열고 다음을 설정합니다.

| 필드 | 입력 내용 |
| --- | --- |
| Python | nunif 의존성이 설치된 Python 실행 파일. 인자가 붙은 명령 문자열이 아닙니다 |
| nunif 디렉터리 | `nunif`와 `waifu2x` 하위 디렉터리가 있는 로컬 소스 디렉터리 |
| 모델 디렉터리 | Art `.pth` 가중치를 실제로 저장한 디렉터리. 애플리케이션은 `swin_unet_v3/art`와 `swin_unet/art` 경로를 감지할 수 있습니다 |
| 배율 | 1은 끔. 2× 또는 4× 확대 |
| 노이즈 제거 | -1은 확대만. 0–3은 사용 모델이 지원하는 노이즈 제거 단계이며 기본은 1 |
| 신경 Alpha | 기본은 켬. 끄면 Alpha를 쌍선형으로 확대합니다 |
| 입력 타일 | 64–512, 기본 256. 비디오 메모리가 부족하면 줄입니다 |

로컬 Python, PyTorch, nunif, 맞는 가중치를 준비한 뒤 적용합니다. 사용 중인 업스트림 버전의 설치 요구는 그 버전에 딸린 설명을 확인합니다. 배포 패키지에는 완전한 추론 환경이 들어 있지 않습니다. 적용하면 미리보기를 다시 만들고, 설정은 프로젝트와 함께 저장됩니다. 컴퓨터를 바꾸면 로컬 경로를 다시 확인해야 합니다.

4×는 네이티브 4× 가중치를 우선 사용합니다. 없으면 현재 worker가 지원하는 2× 모델을 두 번 실행할 수 있습니다. 맞는 모델이 없으면 오류를 내며, 일반 보간으로 초해상도를 대신하지 않습니다.

## CLI 예

```powershell
.\gradlew.bat run --args="--input examples/tml/psd-input/tml.psd --output build/upscaled-model --upscale 2 --upscale-python C:/venv/Scripts/python.exe --nunif-dir C:/nunif --upscale-model C:/nunif/waifu2x/pretrained_models/swin_unet_v3/art --upscale-tile 256"
```

신경 Alpha를 끄려면 `--no-upscale-neural-alpha`를 사용합니다. 나머지 인자는 [CLI 표](DEVELOPMENT.md)를 참조합니다. 예제 경로는 이 컴퓨터의 경로로 바꿉니다. 공백이 있으면 런처 규칙에 따라 인용합니다.

## 결과와 리소스

RGB와 Alpha는 따로 처리합니다. 투명 영역의 색 확장은 샘플링 가장자리 오염을 줄이기 위한 것입니다. 신경 Alpha는 반투명 윤곽을 바꿀 수 있습니다. 밝은 배경과 어두운 배경에서 머리카락, 속눈썹, 입술선, 움직임 중의 마스크 가장자리를 확인합니다.

추론은 레이어를 직렬로 실행하며, 출력과 텍스처 묶기도 시스템 메모리를 사용합니다. 캐시는 사용자 디렉터리 `.psd2live/cache/upscale`에 있으며, 소재, 설정, 코드, 가중치 관련 식별을 포함합니다. 애플리케이션을 종료한 뒤 지울 수 있습니다. 모델 오류, 크기 초과, 시간 초과가 있으면 로그를 확인하고, 텍스처 아틀라스만 키워서 다시 시도하지 마세요.

구현 근거: [설정과 프로세스 관리](../../../src/main/kotlin/io/github/psd2live/core/TextureUpscale.kt), [nunif worker](../../../src/main/resources/upscale/nunif_worker.py).
