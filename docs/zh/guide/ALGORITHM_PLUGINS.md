# 算法插件

预设中的每个修改器通过算法接口求值。内置算法在应用进程内执行；外部算法是常驻工作进程，可使用 Python、Kotlin、Rust、C、C++ 或 C#。安装包可以包含多个算法。

内置算法按类型和算法修订号分别列入插件目录，各项独立启停；摆动、模拟、物理和动作具有独立的 Kotlin 实现入口。新建实例记录 `builtin.*` 包身份、完整版本和内容摘要；已独立打包的算法覆盖完整包，其余入口仍使用字节码摘要。摘要不匹配时显示不可用，不自动替换。历史工程中没有插件引用的内置实例继续按原算法修订读取，不在打开时重写历史。`id=builtin,digest=builtin` 仅作为旧接口的整体启停兼容入口。

外部包可以在 `dependencies` 中引用 `plugin_list` 返回的内置包 ID 与版本；安装时锁定相同的内容摘要，内置项被停用或对应版本缺失时依赖包也不可用。`builtin` 整体身份保留；`builtin.*` 安装包必须声明与包 ID 和算法修订匹配的稳定类型别名，供历史内置算法恢复。启停状态保存在偏好中，求值从内存快照读取，不逐项访问系统偏好。

物理组和动作之外的内置入口仍随应用编译和发布，尚不是可单独分发、安装的 JAR。绑定算法仍调用应用提供的共享生成服务；入口摘要不是整个应用和全部底层依赖的摘要。其余算法的独立包发布、历史二进制保留和绑定算法局部计算仍需继续迁移，不能把逐项管理与身份固定视为这部分已经完成。

其中物理组实现已迁到 `:algorithms:physics`：`PendulumAlgorithm` 直接实现公共求值接口，配置解析、物理组转换和输出冲突检查均在该模块完成。算法仅使用 `algorithm-api`，应用注册入口只委托调用；普通物理导出和其他生成器复用同一转换实现。另由 `algorithm-worker` 提供工作进程入口，`./gradlew :algorithms:physics:pluginPackage` 在 `algorithms/physics/build/plugin` 生成独立安装目录（`plugin.json` 与携带依赖的 `algorithm.jar`），可交给 `plugin_install`。包 ID 为 `builtin.70687973696373.v1`，入口为该命名空间下的 `/pendulum`，`aliases: ["physics"]` 保留工程中的类型、算法修订和输出 ID。

应用携带并保留物理组和动作的完整安装包，新实例固定整个包的内容摘要。与当前包完全匹配时进程内执行；旧包引用只有在对应内容已安装时才通过工作进程执行，不回退到当前实现。旧版本安装包可以卸载与重装；当前应用自带版本只能启停。迁移之前仅记录入口字节码摘要的开发期引用不会被改写，也不能用新包替代；找不到原内容时仍报告不可用。

动作实现已迁到 `:algorithms:motion`，包 ID 为 `builtin.6d6f74696f6e.v1`，入口为 `/clip`，稳定别名为 `motion`。`MotionData` 提供原格式的数据模型、JSON 读写和曲线转换，应用编辑器与独立工作进程共用此实现；模块不依赖播放会话、UI 或 Umamo。构建命令为 `./gradlew :algorithms:motion:pluginPackage`，输出目录为 `algorithms/motion/build/plugin`。

## 安装与卸载

打开偏好设置的「算法插件」，选择 ZIP，或输入本地目录、HTTPS／SSH／git:// Git 地址。Git 地址可附分支、标签或完整提交号；安装器记录实际提交。仓库根目录必须是已构建的插件包，不拉取子模块，不运行安装脚本，不在用户机器编译源码。

包必须携带当前平台的程序和必要运行环境。Kotlin JAR 使用应用的 JVM；Python 和 .NET 插件不能假设用户另装了开发环境。插件执行本地代码，进程隔离不等于安全沙箱。

安装器校验协议、平台、配置 schema、文件清单和 SHA-256，再原子发布。相同内容重复安装不会产生副本；同一包版本不能用不同内容覆盖。不同版本可以并存，已有修改器固定引用创建时的版本和内容摘要。

卸载保留工程中的实例、参数和覆盖。缺失或停用的插件显示「插件缺失」；由于缺失算法的实际输出未知，其后缀保守暂停。受影响的工程不能导出，重新安装相同包后重新求值恢复。内置包可停用和恢复，不能删除应用自带文件。插件状态变化会后台刷新已打开工程，不追加历史节点。

偏好记录安装目录和启用状态，默认目录为用户目录下的 `.psd2live/plugins`。工程、历史和导出的预设 JSON 只保存包引用，不嵌入可执行代码。

## MCP

GUI 与 MCP 使用相同管理服务。`plugin_list` 返回包身份、启用状态、算法列表、配置 schema、默认值、依赖、目标类型和读写范围。`problems` 列出不能加载的包。

```json
{"source":"D:/plugins/example-python.zip"}
```

以上为 `plugin_install` 的业务请求；`plugin_inspect` 接受相同输入，只检查、不安装。Git 请求可增加 `ref`。

`plugin_enable`、`plugin_disable`、`plugin_uninstall` 使用 `plugin_list` 返回的 `id` 与 `digest`。内置项也使用列表返回的独立身份；旧的两个值均为 `builtin` 的调用只兼容整体启停，不用于卸载。除列表外均返回后台任务，通过 `job_wait`／`job_get` 查询终态。无需已打开工程。

添加和编辑外部修改器继续使用 `modifier_plan`、`modifier_add`、`modifier_update` 等现有操作；配置按插件提供的 schema 校验。计划结果固定包引用，提交期间插件改变则拒绝旧候选。所有公开操作均声明严格结果 schema。

## 编写插件

JVM 契约已拆为独立的 `:algorithm-api` 模块，应用和插件共用同一份声明。它只依赖中立 `:format-model` 与 JSON 序列化库，不依赖应用、Umamo、Compose、工程或历史。`./gradlew :algorithm-api:build` 生成版本为 `1.0.0` 的 API JAR 与源码 JAR。

`ModifierAlgorithmPlugin.evaluate(input, instance, checkpoint)` 接收上游 `RigIR`、不可变 `RigModifierInstance` 和取消检查函数，返回候选 `RigIR`。实现不能原地修改输入数组；长循环需调用 `checkpoint`，异常原样交回宿主。对象身份使用 `instance.outputId(localName)`，兼容已有别名；宿主仍负责输出验证、依赖、覆盖、CAS 和历史提交。API 同时公开目标引用、固定包引用、清单、版本范围与依赖声明；原 JSON 字段、序列化类型名称和对象 ID 编码保持不变。

API JAR 本身不是可安装算法包。独立编译契约不等于内置实现已迁出应用；内置包加载与历史实现保留仍按上面的迁移状态处理。外部六语言工作进程继续使用下述版本化协议。

### JVM 模型结果适配

`algorithm-worker` 可把 `ModifierAlgorithmPlugin` 放入常驻 JVM 进程。入口调用 `AlgorithmWorker.run(mapOf((type to version) to implementation))`，不需要依赖应用、编辑器或工程。标准输出仅用于协议响应。请求的 `invocation` 提供完整实例信息（含持久化输出别名），原有 `instance`、`config`、`targets` 字段继续兼容其他语言。

宿主在 `capabilities` 中声明 `rig_objects` 后，工作进程可返回 `objects`，替代 `commands`；两者不能同时出现。`objects` 必须恰含 `changed`、`removed`、`mesh_order`、`deformer_order`：以请求输入为基线，仅列出改变的 `frame`、`mesh:<id>`、`deformer:<id>` 资源引用及被删除的键。资源采用 `RigIrObjects` 二进制格式，引用包含 `sha256` 和 `bytes`。单个资源上限 64 MiB，总计上限 512 MiB；宿主验证摘要、大小、对象类型和 ID，不接受路径作为资源引用。

宿主将对象差异合并回完整上游输入，因此按读写声明裁剪掉的无关内容不会丢失。随后检查结构、数值、声明的写入类型和输出 ID，再进入候选、覆盖与事务流程。进程内与工作进程调用同一算法实现；宿主不再通过物理配置命令重新执行该算法。二进制结果适配不改变其他语言已有的命令输出协议。

六语言源码与打包工具见 [examples/plugins](../../../examples/plugins/README.md)。包根目录的 `plugin.json` 声明：

- `protocol: 1`，包 `id`、`version`、`name`。
- `commands`：以 `windows-x86_64` 等平台标识为键的参数数组；JVM 包使用 `jvm: ["$JAVA", "-jar", "worker.jar"]`。不经过 shell。
- `files`：每个有效载荷的相对路径及 SHA-256；禁止越界路径、符号链接和未列出的文件。
- `algorithms`：算法类型必须以 `包ID/` 开头；声明算法版本、名称、目标、依赖、互斥关系、读写范围、可组合写入类型、`configSchema` 和 `defaults`。
- 可选 `parameters` 声明共享标准参数的 `id/name/min/max/default`。定义必须兼容上游同名参数；其他新增对象使用实例所属 ID。

每次请求和响应各占一行 UTF-8 JSON。标准输出只用于协议，日志写标准错误。请求包含 `protocol/id/method/type/version/instance/config/targets`；响应回显 `protocol/id`，返回 `commands` 或 `error`。同一工作进程串行处理请求，故障或超时后重建进程；取消执行中的请求会终止进程，不提交其部分结果。排队中的请求可独立取消，不中断正在执行的其他请求；等待响应期间也检查取消。

模型输入按声明的读写组件裁剪；未知范围或 `*` 保守使用完整输入。`changed` 和 `removed` 同步对象索引，`reset` 表示全新会话。`frame`、`mesh:<id>`、`deformer:<id>` 的值为资源摘要，资源目录通过请求及 `PSD2LIVE_PLUGIN_RESOURCES` 提供；资源使用 `RigIrObjects` 的版本化精确二进制格式。`mesh_order` 和 `deformer_order` 保留顺序。

便携视图 `meshes` 提供目标网格 ID、透明度及位置／UV 缓冲引用；`parameters` 提供已声明可读的参数。浮点缓冲格式为小端 `f32le`，文件名等于 SHA-256。返回命令中的浮点数组也可用该引用表示，宿主校验摘要和有限数值后展开。

返回命令支持：

- `mesh_opacity`：目标网格 `id` 与范围 0–1 的 `value`。
- `physics`、`motion`：`config` 使用相应内置修改器的配置格式，输出 ID 由宿主管理。
- 编辑原语：`structure`、`set`、`parameter_keys`、`delete`、`canvas_geometry`、`canvas_topology`、`canvas_create_warp`、`canvas_create_rotation`、`canvas_create_glue`、`canvas_glue_edit`、`path_put`、`path_delete`，沿用作者编辑记录的格式。它们只在隔离候选内执行，不直接写历史。

新增对象 ID 为 `modifier:<实例ID>:<局部名称的UTF-8十六进制>`。宿主校验实际写入范围、共享参数、依赖、覆盖和冲突；插件不得直接操作文档、历史或 GUI。

## 性能边界

内置算法直接调用，不承担跨进程序列化开销。外部插件复用进程、按内容寻址传输缓冲，并增量同步输入。作者基础输入、绑定中间模板和可声明依赖的算法结果分别缓存；文件夹调整不触发算法求值。

验收上限为 4 秒：从读取 PSD 到完整预设、预览和工程历史初始化完成，扣除同步模拟烘焙的实际耗时。烘焙仍须完成，不能延后或省略；报告分别列出首次／重复生成的完整耗时、烘焙耗时和计入上限的耗时。模拟烘焙暂不作为优化目标。

目前内置绑定仍有生成模板后提取局部结果的兼容路径，尚未全部改为独立局部算法；算法调度仍按栈串行，未实现独立项并行。不能把插件接入或缓存命中等同于完整链路达标；首次和重复生成分别判定。

## 版本和依赖关系

三个版本层次独立：`protocol` 是通信协议版本；包 `version` 是 `主.次.修订` 版本；算法 `version` 是已持久化的整数修订号。算法整数号保留兼容，不因更换运行语言而重新编号。包版本支持 `1.2.3-rc.1+build.7`；构建标记不参与先后比较，完整包内容仍由摘要区分。早期包的整数版本按 `N.0.0` 比较，新的包请使用完整版本号。

包的 `dependencies` 声明代码前置条件，`conflicts` 声明不能同时启用的包版本。它们与算法 `requires` 不同：后者要求栈中已经有同目标的算法结果，不会安装或启动另一个包。

```json
{
  "dependencies": [
    {"id": "geometry-core", "version": {"minimum": "1.2.0", "maximum": "2.0.0"}}
  ],
  "conflicts": [
    {"id": "other-cloth", "version": {"exact": "1.0.0"}}
  ]
}
```

- `minimum` 包含边界，`maximum` 不包含边界；`exact` 不能与范围组合。省略 `version` 表示任意版本。预发布版本按数字／文本段排序，不隐含排除预发布版本。
- 安装时选择满足范围的最高已启用版本，锁定完整传递依赖。缺少前置、循环或冲突会拒绝安装／启用，不自动下载依赖，也不关闭其他插件。
- 冲突检查是双向的，一方声明即可生效。不同版本可以共存，但显式冲突仍会阻止启用。
- 宿主保存安装依赖锁；工程的插件引用保存 `dependencyDigests`（`包ID@版本` → 内容摘要）。后来安装更新版本不会替换旧实例的依赖。
- 禁用／卸载前置项会停止工作进程，下游保留并不可用；恢复原内容后重新可用。相同包重新安装但解析到不同依赖时，旧工程引用保持不可用，不能静默升级。
- `plugin_list` 和 `plugin_inspect` 返回 `available/issues/dependencies/conflicts/resolved_dependencies`。`enabled` 只表示用户开关，不能代替 `available`；偏好面板同时显示具体不可用原因。

依赖关系不是跨插件函数调用。算法协作通过上游模型内容和算法 `requires` 完成；请求中的 `dependencies` 提供锁定的包身份，不暴露工程对象或其他工作进程句柄。

## 底层能力与模块边界

| 层 | 责任 |
| --- | --- |
| `ModifierAlgorithmPlugin.kt` | 可序列化包、算法、目标、参数和版本关系契约 |
| `ModifierPluginDependencies.kt` | 版本比较、范围匹配、依赖图、冲突诊断 |
| `ModifierPluginStore.kt` | 校验、原子安装、依赖锁、停用及卸载 |
| `ModifierPluginWorkers.kt` | 常驻进程、取消／超时、增量输入及二进制资源 |
| `ModifierPluginOutput.kt` | 在候选模型执行底层命令，验证实际写入范围 |
| `RigModifierEvaluator.kt` | 栈语义、共享参数、覆盖、贡献、写入冲突与结果缓存 |
| 应用层 | GUI／MCP 操作、后台任务、CAS 和历史提交 |

请求中的 `capabilities` 列出宿主支持的命令。底层操作包含：

| 目的 | 命令 | 主要写入范围 |
| --- | --- | --- |
| 参数创建、范围、文件夹、关系、对象父子级 | `structure` | `parameter/hierarchy/relations/mesh/deformer/part`，按实际变化声明 |
| 关键形几何与通道、复制与删除 | `set/copy/delete/parameter_keys` | `mesh/deformer/parameter/glue` |
| 网格几何、拓扑和变形器创建 | `canvas_geometry/canvas_topology/canvas_create_warp/canvas_create_rotation/warp` | `mesh/deformer/hierarchy/authoring` |
| Glue 和路径 | `canvas_create_glue/canvas_glue_edit/path_put/path_delete` | `glue/authoring` |
| 顶点权重组 | `vertex_group_put/vertex_group_delete` | `authoring` |
| 物理组与动作 | `physics/motion` | `physics/motion` |
| 目标网格透明度 | `mesh_opacity` | `mesh` |

`reads/writes` 还支持 `textures/canvas/advanced/hit_areas/rest_pose`，`*` 表示整个模型。声明能力不等于有修改这些数据的专用命令；例如目前不提供外部插件直接创建纹理资源的命令。细分字段未声明的输入采用保守缓存，不推断其独立性。

权重命令示例（数组长度必须等于网格顶点数；也可用带 `bytes/count/format/sha256` 的缓冲引用）：

```json
{"op":"vertex_group_put","target":"mesh:cloth","name":"Cloth weights","kind":"pin","weights":[0,0.5,1]}
```

关键形示例：

```json
{"op":"set","target":"mesh:cloth","key":{"ParamAngleX":30},"channels":{"opacity":0.8}}
```

创建参数使用 `structure.edits` 中的 `action=create,kind=parameter,id,name,min,max,default,parent_id`。共享参数必须在算法 `parameters` 中预声明兼容定义；非共享新增对象使用实例所属确定性 ID。命令的详细字段沿用对应作者编辑原语，由宿主执行同一验证，不允许绕过模型编辑不变式。

所有命令先在隔离候选中按顺序运行；验证输出后才返回求值结果。附属数据（权重、路径等作者内容）、纹理、画布、渲染层级也参与实际写入检查与冲突报告。一个包不能仅声明 `mesh` 却通过命令修改作者权重。进程隔离不提供安全沙箱；插件仍是用户安装的本地可执行代码。
