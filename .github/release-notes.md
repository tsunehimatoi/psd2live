v3.7.1 预览版：**半成品，效果没有保证。** 这一版主要是首次提供 macOS 版本。

正在把自动生成的 Rig 重做为按区域（眼、眉、嘴、头、头发、身体、服装、表情、动作）组织的模块，贴合画出的五官来生成动作。目前只调过嘴，而且还没调完；其余模块只是初步实现，效果并不好。

- 只想试用新的嘴部生成（分阶段张开、元音、分层画法的嘴）或 macOS 版本时再下载；日常使用请继续用 [v3.6.7](https://github.com/tsunehimatoi/psd2live/releases/tag/v3.6.7)。
- 旧工程照旧按原方案生成，结果不变；新工程默认使用新模块。打开前请备份工程。
- macOS：提供 Apple 芯片（arm64）和 Intel（x86_64）两个 DMG。DMG 只做了 ad-hoc 签名，**没有经过 Apple 公证**，首次打开会被 Gatekeeper 拦下。请在"应用程序"里右键点 PSD2Live 选"打开"，或在终端执行 `xattr -d com.apple.quarantine /Applications/PSD2Live.app`。

<details>
<summary>English</summary>

v3.7.1 preview: **work in progress, results not guaranteed.** This is the first release with macOS builds.

The generated rig is being rebuilt as modules by area (eyes, brows, mouth, head, hair, body, outfit, expressions, motions) that fit the drawn features. So far only the mouth has been tuned, and not finished; the other modules are first passes and do not look good yet.

- Download it only to try the new mouth (staged opening, vowels, mouths drawn in layers) or the macOS build; for everyday use stay on [v3.6.7](https://github.com/tsunehimatoi/psd2live/releases/tag/v3.6.7).
- Older projects generate as before; new projects use the new modules. Back up your projects first.
- macOS: DMGs for Apple silicon (arm64) and Intel (x86_64). They are ad-hoc signed and **not notarized**, so Gatekeeper blocks the first launch. Right-click PSD2Live in Applications and choose Open, or run `xattr -d com.apple.quarantine /Applications/PSD2Live.app` in Terminal.

</details>
