v3.7.0 预览版：自动生成的 Rig 改为按区域组织的模块——贴合画出的眼、眉与分阶段张开的嘴，头部纵深转动，头发与服装的链式摆动，更丰富的表情与自然动作。

> 这是预览版本，Rig 生成变化较大，建议先备份工程。

## 主要更新

### 新功能

- **按区域组织的 Rig 模块**：自动生成的 Rig 分为眼、眉、嘴、头、头发、身体、服装、表情、动作等区域，每个区域可选基础方案并叠加模块；新工程默认使用现代方案并开启全部适用模块，有预设与开销预算。
- **贴合画出的眼与眉**：眼睑按眼白轮廓与闭眼线生成，头部倾斜或转动时沿脸部闭眼；眉毛沿弧线转动并带动上眼睑，被刘海遮住时由眼睑表达情绪。
- **分阶段张开的嘴**：嘴按上下唇缘分阶段张开，含元音、面部捕捉、下颌与抿唇 / 张唇；支持按分层画法（上下唇、唇线、牙齿、口腔）绘制的嘴，牙齿与舌头随唇整体移动，可张到画面之外喊叫。
- **头部纵深转动**：头部按圆柱体转动，五官、前发、后发、耳朵各在不同深度，转到尽头时远侧鬓发转到脸后。
- **头发、服装与身体的链式摆动**：头发分段像摆锤链一样摆动；裙子、丝带、挂饰、动物耳朵、尾巴与翅膀成链摆动；胸部弹动、手臂摆动、颈部分担头部倾斜。
- **表情与自然动作**：新增脸红、阴郁等情绪参数，标准表情更丰富（新增害羞、困倦、阴郁）；自然眨眼与视线移动；为模块 Rig 调好的 VTube Studio 映射。
- **特征点工具**：`Shift+Y` 在画布上显示眼、眉、嘴的控制点，拖动即可重新贴合，所有姿势随之变化，Alt 单击还原。

### 改进

- 脸上的腮红、阴影与刘海投影裁剪到脸上，转头时不会跑出脸外。
- 工作区标签拖动更顺畅，可一直拖到两端。

### 兼容

- 旧工程按旧方案生成，结果与之前逐字节相同；可在提示中升级为模块 Rig。

<details>
<summary>English</summary>

v3.7.0 preview: the generated rig becomes modules by area: eyes, brows and a staged mouth fitted to the art, head turns in depth, hair and outfit in chains, richer expressions and natural motion.

> This is a preview with large changes to rig generation; back up your projects first.

## Highlights

### New

- **Rig modules by area**: the generated rig is organised into areas (eyes, brows, mouth, head, hair, body, outfit, expressions, motions), each with a choice of base and modules that add up; new projects use the modern recipe with every applicable module on, with presets and a cost budget.
- **Eyes and brows fitted to the art**: lids come from the eye white's outline and the closed-eye line and close down the face when the head tilts or turns; brows turn along an arc and move the upper lids, which carry the mood when bangs hide the brows.
- **A mouth that opens in stages**: the mouth opens by its lip edges in stages, with vowels, face capture, the jaw and pressed / parted lips; mouths drawn in layers (lips, lip lines, teeth, inside) are supported, teeth and tongue move whole with their lips, and the mouth can open past its drawing into a shout.
- **Head turns in depth**: the head turns as a cylinder with features, front hair, back hair and ears at their own depths; far side locks go behind the face at the end of a turn.
- **Hair, outfit and body in chains**: hair bends in segments like a pendulum chain; skirts, ribbons, pendants, animal ears, tails and wings swing in chains; the chest bounces, arms swing and the neck shares the head's tilt.
- **Expressions and natural motion**: Cheek and Gloom moods, richer standard expressions (shy, sleepy and gloomy added), natural blinks and glances, and a VTube Studio mapping tuned for module rigs.
- **Feature points**: `Shift+Y` shows the eyes', brows' and mouth's handles on the canvas; drag to refit, every pose following, Alt-click to return to the art.

### Improved

- Blush, shading and the bangs' shadow are clipped to the face, so a turn never carries them off it.
- Workspace tabs drag smoothly, all the way to either end.

### Compatibility

- Older projects generate with the legacy recipe, byte for byte as before, and are offered an upgrade to the module rig.

</details>
