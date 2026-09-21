# 纹理高清化

[操作速查](USER_GUIDE.md) · [开发与 CLI](DEVELOPMENT.md) · [文档目录](../../README.md)

对应程序内第 14 课。高清化在贴图打包前逐层放大纹理，默认关闭；不改变原始 PSD、画布尺寸或绑定。预览与导出使用处理后的纹理。

## 配置与使用

打开 **工具 → 纹理高清化…**（默认 `Ctrl+U`），配置：

| 字段 | 填写内容 |
| --- | --- |
| Python | 安装了 nunif 依赖的 Python 可执行文件，不是带参数的命令字符串 |
| nunif 目录 | 包含 `nunif` 和 `waifu2x` 子目录的本地源码目录 |
| 模型目录 | 实际保存 Art `.pth` 权重的目录；应用可检测 `swin_unet_v3/art` 与 `swin_unet/art` 路径 |
| 倍率 | 1 关闭；2× 或 4× 放大 |
| 降噪 | -1 只放大；0–3 为所用模型支持的降噪级别，默认 1 |
| 神经 Alpha | 默认开启；关闭后以双线性方式放大 Alpha |
| 输入分块 | 64–512，默认 256；显存不足可减小 |

准备本地 Python、PyTorch、nunif 及匹配权重后再应用。具体上游版本的安装要求应查阅随所用版本提供的说明，发布包不包含完整推理环境。应用时重建预览，配置随工程保存；换电脑后需重新检查本地路径。

4× 优先使用原生 4× 权重；缺少时，当前 worker 可把支持的 2× 模型执行两次。缺少匹配模型会报错，不会自动用普通插值冒充超分。

## CLI 示例

```powershell
.\gradlew.bat run --args="--input examples/tml/psd-input/tml.psd --output build/upscaled-model --upscale 2 --upscale-python C:/venv/Scripts/python.exe --nunif-dir C:/nunif --upscale-model C:/nunif/waifu2x/pretrained_models/swin_unet_v3/art --upscale-tile 256"
```

关闭神经 Alpha 用 `--no-upscale-neural-alpha`。其余参数见 [CLI 表](DEVELOPMENT.md)。示例路径须换成本机路径；包含空格时按启动器规则引用。

## 效果与资源

RGB 和 Alpha 分开处理，透明区的颜色扩展用于减轻采样边缘污染；神经 Alpha 仍可能改变半透明轮廓。请在深浅背景下检查发丝、睫毛、唇线及运动中的遮罩边缘。

推理按图层串行执行，输出与贴图打包还会占用系统内存。缓存位于用户目录 `.psd2live/cache/upscale`，包含素材、配置、代码和权重相关标识；应用退出后可清理。模型错误、尺寸超限或超时应检查日志，不要仅扩大贴图集重试。

## 开发验证

```powershell
python -m unittest discover -s tests -p test_nunif_worker.py -v
.\gradlew.bat test --tests '*TextureUpscaleTest'
```

真实推理测试需配置 `PSD2LIVE_TEST_NUNIF`、`PSD2LIVE_TEST_PYTHON`、`PSD2LIVE_TEST_MODEL`，未配置时不代表验证过真实模型效果。

实现依据：[配置与进程管理](../../../src/main/kotlin/io/github/psd2live/core/TextureUpscale.kt)、[nunif worker](../../../src/main/resources/upscale/nunif_worker.py)。
