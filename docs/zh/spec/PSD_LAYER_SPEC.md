# PSD 素材与命名

[文档目录](../../README.md) · [English](../../en/spec/PSD_LAYER_SPEC.md) · [日本語](../../ja/spec/PSD_LAYER_SPEC.md) · [操作速查](../guide/USER_GUIDE.md)

本页面向素材准备；自动识别只是预设入口，导入后仍可在图层表里修改标签与差分（见[语义标签](#语义标签)）。

## 准备素材

使用 RGB、8 位、透明背景的分层 PSD。需要保留的文字、矢量、图层样式和调整效果先栅格化或合并到对应像素层。身体保持基本正立；头部可轻微倾斜，自动基准估计仍需预览核对。

图层混合模式：线性减淡（添加）、发光添加与线性光生成 Cubism 的加算，正片叠底、线性加深与颜色加深生成乘算，滤色保持滤色（Cubism 5.3 的混合模式；导出到更早的运行时版本时改为加算，并写进损失报告），其余模式按普通绘制。图层组的混合模式不生效，需要时先把组合并为一个图层。

## 不用 PSD：Layout 清单

由程序拆好的部件（例如 AI 分层的结果）可以不经 PSD，写成 [Layout 清单](LAYOUT_MANIFEST.md) 交给 PSD2Live：一个 JSON（或根目录放着它的 zip），逐层写明图片、画布矩形、上下顺序、组、标签、差分，以及是否只作参考（如拆件前的原图，显示在画布上但不生成、不导出）。小部件可以带比画布更多的像素，打开后得到摆好的未生成工程。没有标签的图层按本页的名称规则识别。

## 部件分层

眼白、瞳孔、上睫毛分别绘制；瞳孔保留被眼皮遮住的完整部分。上睫毛预设不适合混入下眼眶线，即使命名器能识别“下睫毛”，也应检查或改用独立细节层。

嘴巴提供最大张口素材；可把口腔绘为整体，或另分上牙、下牙、舌头。闭口原图不能凭几何变形恢复不存在的口腔。

厚涂唇或想让嘴开合更干净时，把嘴按建模惯例拆成两组：口腔组为 `口腔`（张到最大时的开口形状，整块填色，用来裁切口腔里的其它层；也可以是不可见的形状，图层不透明度设为 0；边缘画到唇线宽度的中间，不超出线的外缘，除非唇块会把超出的部分盖住）、口腔底色（如 `口腔底`，画得比嘴任何时候能张开的范围都大，在 PSD 里剪切到 `口腔` 上，这样任何口形下开口里都不会露出后面的东西）、`上牙`、`下牙`、`舌`（同样画得比开口大，靠口腔裁掉；牙画成一排真实牙列的形状，平的或略呈 U 形、两端稍高，上下牙曲率相近，不要照开口的形状截成 ∩ 形，否则笑口时和下凹的上唇方向相反；宽度可以占满并越过嘴角，也可以只占中间一部分），口腔里的暗部可另成一层并命名为阴影；嘴皮组为 `上唇`、`下唇`（唇连同周围一圈与脸同色的皮肤，唇色、光泽、阴影画在里面），以及有线稿时的 `上唇线`、`下唇线`（两条线合起来是一圈闭合的轮廓，在左右嘴角相接于同一点，粗细均匀）。唇块随唇整体移动而不被压扁，闭嘴时盖住口腔；画了唇块的嘴，口腔闭合时按「口腔收起」设置只收起一部分。线随唇缘移动并保持粗细，闭嘴时两条线合成一条。拆了上下唇或唇线的嘴不再生成嘴部描边。前发与后发分开；被遮挡区域保留足够重叠以便运动。

## 名称参考

下表列出当前分类器的已知标签与部分别名，不包含 UNKNOWN。完整别名以源码为准。

| Tag | Names / 名称 |
| --- | --- |
| `BACK_HAIR` | `back hair`, `后发`, `后髪`, `后脑勺`, `뒷머리` |
| `FRONT_HAIR` | `front hair`, `前发`, `前髪`, `刘海`, `앞머리` |
| `HEADWEAR` | `headwear`, `帽子`, `头饰`, `頭飾`, `모자` |
| `FACE` | `face`, `脸`, `臉`, `脸部`, `얼굴` |
| `FACE_DETAIL` | `facedetail`, `脸部细节`, `面部细节`, `腮红`, `볼터치` |
| `IRIDES` | `irides`, `瞳孔`, `虹膜`, `眼珠`, `눈동자` |
| `EYEBROW` | `eyebrow`, `眉毛`, `眉`, `まゆ毛`, `눈썹` |
| `EYEWHITE` | `eyewhite`, `眼白`, `白眼`, `白目`, `흰자` |
| `EYELASH` | `eyelash`, `睫毛`, `まつ毛`, `まつげ`, `속눈썹` |
| `EYE_CLOSE` | `eye close`, `闭眼`, `閉眼`, `目閉じ`, `감은 눈` |
| `EYEWEAR` | `eyewear`, `眼镜`, `眼鏡`, `めがね`, `안경` |
| `EARS` | `ears`, `耳朵`, `耳`, `みみ`, `귀` |
| `EARWEAR` | `earwear`, `耳环`, `耳環`, `耳饰`, `귀걸이` |
| `NOSE` | `nose`, `鼻子`, `鼻`, `はな`, `코` |
| `MOUTH` | `mouth`, `口`, `嘴`, `嘴巴`, `입`, `上唇`, `下唇`, `上唇线`, `下唇线`, `upper lip`, `lower lip line` |
| `MOUTH_OPEN` | `mouth open`, `张嘴`, `張嘴`, `开口`, `벌린 입` |
| `MOUTH_CLOSE` | `mouth close`, `闭嘴`, `閉嘴`, `闭口`, `다문 입` |
| `TOOTH_T` | `tooth-t`, `上牙`, `上歯`, `上齿`, `윗니` |
| `TOOTH_B` | `tooth-b`, `下牙`, `下歯`, `下齿`, `아랫니` |
| `TONGUE` | `tongue`, `舌头`, `舌頭`, `舌`, `혀` |
| `NECK` | `neck`, `脖子`, `颈部`, `頸部`, `목` |
| `NECKWEAR` | `neckwear`, `领饰`, `領飾`, `围巾`, `목도리` |
| `TOPWEAR` | `topwear`, `上衣`, `衣服`, `服装`, `상의` |
| `HANDWEAR` | `handwear`, `手臂`, `手`, `腕`, `팔` |
| `BOTTOMWEAR` | `bottomwear`, `下装`, `下裝`, `裤子`, `치마` |
| `LEGWEAR` | `legwear`, `腿`, `大腿`, `小腿`, `다리` |
| `FOOTWEAR` | `footwear`, `脚`, `腳`, `鞋`, `신발` |
| `TAIL` | `tail`, `尾巴`, `尾`, `しっぽ`, `꼬리` |
| `WINGS` | `wings`, `翅膀`, `翼`, `つばさ`, `날개` |
| `OBJECTS` | `objects`, `道具`, `物件`, `소품` |

## 语义标签

名称识别出的部件与侧别在编辑器里显示为一组标签，可以随时增删。生成器认识的组合如下，其余文字是用户自己的标签，不影响生成；`left` / `right` 表示侧别。词表与匹配规则见 [LayerTags.kt](../../../src/main/kotlin/io/github/psd2live/core/LayerTags.kt) 与[素材与 Layout](MATERIAL_LAYOUT_WORKSPACE.md#语义标签)。

| Tag | 标签 | Tag | 标签 |
| --- | --- | --- | --- |
| `BACK_HAIR` | `hair` `back` | `MOUTH` | `mouth` |
| `FRONT_HAIR` | `hair` `front` | `MOUTH_OPEN` | `mouth` `open` |
| `HEADWEAR` | `headwear` | `MOUTH_CLOSE` | `mouth` `closed` |
| `FACE` | `face` | `TOOTH_T` | `teeth` `upper` |
| `FACE_DETAIL` | `face` `detail` | `TOOTH_B` | `teeth` `lower` |
| `IRIDES` | `eye` `iris` | `TONGUE` | `tongue` |
| `EYEBROW` | `brow` | `NECK` | `neck` |
| `EYEWHITE` | `eye` `white` | `NECKWEAR` | `neck` `accessory` |
| `EYELASH` | `eye` `lash` | `TOPWEAR` | `top` |
| `EYE_CLOSE` | `eye` `closed` | `HANDWEAR` | `arm` |
| `EYEWEAR` | `eyewear` | `BOTTOMWEAR` | `bottom` |
| `EARS` | `ear` | `LEGWEAR` | `leg` |
| `EARWEAR` | `ear` `accessory` | `FOOTWEAR` | `foot` |
| `NOSE` | `nose` | `TAIL` / `WINGS` / `OBJECTS` | `tail` / `wings` / `object` |

## 左右与拆分

左右指角色自身：角色左侧通常在画面右边。可用 `eyelash-l`、`eyelash-r`、`eyelash_left`、`左睫毛`、`왼쪽 속눈썹`、`속눈썹_우` 等名称；韩文单字 `좌` / `우` 需用分隔符或括号隔开。无侧别的双侧图层（例如两只眼睛画在同一层）导入时不会自动拆分，会作为一个部件生成；需要左右分开时在 PSD 里分层，或在编辑器中按网格、多边形拆分（见[网格拓扑与拆分](MESH_TOPOLOGY_AND_SPLIT.md#两种拆分路径)），并检查分类。较早版本自动拆出的 `:r` / `:l` 部件在旧工程中保留。

## 数字与差分

`front hair 1` 等数字可区分素材；分类器还清理部分 Photoshop 副本后缀。数字名称本身不等于已经建立表情切换：开关差分需指定参数，切换差分需共享参数并设置不同关联 ID。

## 未知图层与检查

未识别图层不会仅因名字未知而丢弃，可生成网格并使用回退归属；请在图层面板手动修正。检查中立、眼口开合、转头和头发运动，核对层序、左右、遮罩及露底。命名成功不代表自动造型已通过验收。

[LayerClassifier.kt](../../../src/main/kotlin/io/github/psd2live/core/LayerClassifier.kt) · [Parameters](DEFORMER_AND_PARAMETER_SPEC.md)
