# PSD 素材与命名

[Docs](../../README.md) · [User guide](../guide/USER_GUIDE.md)

本页面向素材准备；自动识别只是预设入口，导入后仍可在图层面板改类型、部件与侧别。

## 准备素材

使用 RGB、8 位、透明背景的分层 PSD。需要保留的文字、矢量、图层样式和调整效果先栅格化或合并到对应像素层。身体保持基本正立；头部可轻微倾斜，自动基准估计仍需预览核对。

## 部件分层

眼白、瞳孔、上睫毛分别绘制；瞳孔保留被眼皮遮住的完整部分。上睫毛预设不适合混入下眼眶线，即使命名器能识别“下睫毛”，也应检查或改用独立细节层。

嘴巴提供最大张口素材；可把口腔绘为整体，或另分上牙、下牙、舌头。闭口原图不能凭几何变形恢复不存在的口腔。前发与后发分开；被遮挡区域保留足够重叠以便运动。

## 名称参考

下表列出当前分类器的已知标签与部分别名，不包含 UNKNOWN。完整别名以源码为准。

| Tag | Names / 名称 |
| --- | --- |
| `BACK_HAIR` | `back hair`, `后发`, `后髪`, `后脑勺` |
| `FRONT_HAIR` | `front hair`, `前发`, `前髪`, `刘海` |
| `HEADWEAR` | `headwear`, `帽子`, `头饰`, `頭飾` |
| `FACE` | `face`, `脸`, `臉`, `脸部` |
| `FACE_DETAIL` | `facedetail`, `脸部细节`, `面部细节`, `腮红` |
| `IRIDES` | `irides`, `瞳孔`, `虹膜`, `眼珠` |
| `EYEBROW` | `eyebrow`, `眉毛`, `眉`, `まゆ毛` |
| `EYEWHITE` | `eyewhite`, `眼白`, `白眼`, `白目` |
| `EYELASH` | `eyelash`, `睫毛`, `まつ毛`, `まつげ` |
| `EYE_CLOSE` | `eye close`, `闭眼`, `閉眼`, `目閉じ` |
| `EYEWEAR` | `eyewear`, `眼镜`, `眼鏡`, `めがね` |
| `EARS` | `ears`, `耳朵`, `耳`, `みみ` |
| `EARWEAR` | `earwear`, `耳环`, `耳環`, `耳饰` |
| `NOSE` | `nose`, `鼻子`, `鼻`, `はな` |
| `MOUTH` | `mouth`, `口`, `嘴`, `嘴巴` |
| `MOUTH_OPEN` | `mouth open`, `张嘴`, `張嘴`, `开口` |
| `MOUTH_CLOSE` | `mouth close`, `闭嘴`, `閉嘴`, `闭口` |
| `TOOTH_T` | `tooth-t`, `上牙`, `上歯`, `上齿` |
| `TOOTH_B` | `tooth-b`, `下牙`, `下歯`, `下齿` |
| `TONGUE` | `tongue`, `舌头`, `舌頭`, `舌` |
| `NECK` | `neck`, `脖子`, `颈部`, `頸部` |
| `NECKWEAR` | `neckwear`, `领饰`, `領飾`, `围巾` |
| `TOPWEAR` | `topwear`, `上衣`, `衣服`, `服装` |
| `HANDWEAR` | `handwear`, `手臂`, `手`, `腕` |
| `BOTTOMWEAR` | `bottomwear`, `下装`, `下裝`, `裤子` |
| `LEGWEAR` | `legwear`, `腿`, `大腿`, `小腿` |
| `FOOTWEAR` | `footwear`, `脚`, `腳`, `鞋` |
| `TAIL` | `tail`, `尾巴`, `尾`, `しっぽ` |
| `WINGS` | `wings`, `翅膀`, `翼`, `つばさ` |
| `OBJECTS` | `objects`, `道具`, `物件` |

## 左右与拆分

左右指角色自身：角色左侧通常在画面右边。可用 `eyelash-l`、`eyelash-r`、`eyelash_left`、`左睫毛` 等名称。无侧别且符合条件的图层会基于生成网格的两个不相连分量拆分，例如眼睛、眉毛、发片或四肢；脸、嘴与口腔内部件排除在自动拆分之外。相连、位置异常或碎片过多时应手动分层并检查分类。

## 数字与差分

`front hair 1` 等数字可区分素材；分类器还清理部分 Photoshop 副本后缀。数字名称本身不等于已经建立表情切换：开关差分需指定参数，切换差分需共享参数并设置不同关联 ID。

## 未知图层与检查

未识别图层不会仅因名字未知而丢弃，可生成网格并使用回退归属；请在图层面板手动修正。检查中立、眼口开合、转头和头发运动，核对层序、左右、遮罩及露底。命名成功不代表自动造型已通过验收。

[LayerClassifier.kt](../../../src/main/kotlin/io/github/psd2live/core/LayerClassifier.kt) · [Parameters](DEFORMER_AND_PARAMETER_SPEC.md)
