# 纹理高清化（nunif / waifu2x）

PSD2Live 可以在打包贴图前逐图层进行 2× / 4× 超分。默认关闭；原始 PSD、画布坐标、网格和绑定保持不变，预览、MOC3 和 CMO3 使用同一套高清纹理。

## 配置运行环境

这是可选的本地 Python 后端。应用不会自动下载或执行未知模型，发布包不包含 PyTorch 或模型权重。

1. 按 [nunif 官方安装说明](https://github.com/nagadomi/nunif#installation) 准备 Python 环境、匹配显卡的 PyTorch / torchvision，以及 nunif 依赖。建议使用独立虚拟环境。
2. 克隆 `https://github.com/nagadomi/nunif.git`（建议使用包含 SwinUNet v3 的最新 `dev` 分支）。
3. 执行模型准备：
   - **推荐（最新 SwinUNet v3 / 2026-08 官方模型）**：
     下载官方权重包 [waifu2x_pretrained_models_swin_unet_v3_art_20260816.zip](https://github.com/nagadomi/nunif/releases/download/0.0.0/waifu2x_pretrained_models_swin_unet_v3_art_20260816.zip)（SHA-256: `d623590525e4438b92d8cc0404ff58c427ab79b79c4b02de12ce132e6d89e82e`），解压至 `nunif/waifu2x/pretrained_models/swin_unet_v3/art`。
   - 或在 nunif 根目录执行 `python -m waifu2x.download_models`（在 `dev` 分支下会自动下载基础包并打入该补丁）。
4. 指定具体 Art 权重目录，优先推荐 `nunif/waifu2x/pretrained_models/swin_unet_v3/art`（亦向后兼容 `swin_unet/art`）。必须包含目标倍率可用的 `scale2x.pth` 或 `scale4x.pth`。2× 可以使用 nunif 支持转换的 4× 权重；4× 不会自动串联两次 2×。

**版本说明：** 官方最新的二次元动漫插画专用超分模型架构为 **swin_unet_v3**（`20260816` 权重包）；系统已默认优先识别并使用 `swin_unet_v3/art`，同时也完整向下兼容 `swin_unet/art`。更新模型后缓存会根据权重内容哈希自动隔离与重新计算。

## 桌面应用

展开右侧设置中的纹理选项，点击“纹理高清化”。填写：

- Python 可执行文件：虚拟环境中的 `python.exe`，不是附带命令参数的字符串。
- nunif 源码目录：包含 `waifu2x` 和 `nunif` 子目录的仓库根目录。
- Art 权重目录：实际 `.pth` 文件所在目录（默认优先检测 `swin_unet_v3/art`）。
- 倍率：建议先用 2×。
- 输入分块：默认 256，显存紧张可以用 128 或 64。
- Alpha：默认保守双线性插值；“用模型放大 Alpha”是实验选项，不建议用于柔光、腮红、阴影。

如果本机已安装在用户目录 `.psd2live/runtime/nunif` 和 `.psd2live/runtime/python`，对话框会显示“填入本机已安装的运行环境”按钮；点击后仍可检查和修改路径。

填写过程中不会启动推理，点击“应用”才重建现有模型预览。设置会随工程及历史快照保存；换电脑后需要调整本地路径。重置模型设置会关闭高清化。

## CLI

```powershell
.\gradlew.bat run --args='--input examples/tml/psd-input/tml.psd --output build/upscaled-model --upscale 2 --upscale-python C:/path/to/venv/Scripts/python.exe --nunif-dir C:/path/to/nunif --upscale-model C:/path/to/nunif/waifu2x/pretrained_models/swin_unet_v3/art --upscale-tile 256'
```

运行发布版 CLI 时使用相同参数。路径包含空格时，需要按启动器的参数转义规则加引号。

可选 `--upscale-neural-alpha`；`--upscale 1` 关闭。倍率仅支持 1 / 2 / 4，错误倍率不会回退成普通插值。

## 透明边缘与内存策略

- 每个图层单独推理，复用一次加载的模型，batch=1，无 TTA，有 CUDA 时启用混合精度；CPU 可运行但通常明显更慢。
- 透明区隐藏的黑/白/其他颜色先清除，再从可见像素向外延伸 RGB。可见像素的输入颜色和原始 Alpha 不受该步骤修改。
- RGB 与 Alpha 分开处理。默认双线性 Alpha 没有锐化过冲，适合半透明绘画图层；模型 Alpha 需要逐素材验收。
- 外层图块留 32 输入像素上下文和 16 输入像素重叠渐变，nunif 内层使用自身分块机制；输出图像拼接在 CPU，避免整张大输出驻留 GPU。
- 贴图间隔保持 Alpha=0，同时延伸边缘 RGB，降低 straight-alpha 采样黑边风险。
- 原始像素量超过 512 MiB 的高清贴图集会在推理前拒绝。该限制不等于总内存上限：PNG 编码、预览和导出还需要额外内存。单图层包含边距后仍不得超过 16384 像素。
- 缓存在用户目录 `.psd2live/cache/upscale`，键包含输入 RGBA、尺寸、配置、适配器代码、nunif Python 源码和实际模型权重。模型/素材更改后不会错误复用旧结果。缓存可在应用退出后手动清理。
- 模型错误、无匹配权重、尺寸错误会显式报错，不会悄悄用普通放大结果代替。单批推理有 30 分钟超时；任务取消和应用关闭会终止推理子进程。

这不是对所有素材“无异常”的保证。请在黑、白、灰背景和实际模型动作下检查发丝、睫毛、唇线及半透明效果。8GB 适配目标通过小分块与串行推理实现，具体峰值依赖模型、输入和驱动。

## 验证

```powershell
python -m unittest discover -s tests -p test_nunif_worker.py -v
.\gradlew.bat test --tests '*TextureUpscaleTest'
```

真实 nunif 集成测试默认跳过。设置以下环境变量后，测试会进行真实超分、导出 MOC3/CMO3、验证缓存命中，产物保存在 `build/upscale-smoke`：

```powershell
$env:PSD2LIVE_TEST_NUNIF = 'C:/path/to/nunif'
$env:PSD2LIVE_TEST_PYTHON = 'C:/path/to/venv/Scripts/python.exe'
$env:PSD2LIVE_TEST_MODEL = 'C:/path/to/nunif/waifu2x/pretrained_models/swin_unet/art'
.\gradlew.bat test --tests '*TextureUpscaleTest' --rerun-tasks
```

当前后端是 nunif，尚未加入 Real-CUGAN。安装与模型分发需要遵循上游对应代码及权重许可。
