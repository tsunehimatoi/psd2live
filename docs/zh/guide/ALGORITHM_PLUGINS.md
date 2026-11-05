# 算法插件

预设中的每个修改器通过算法接口求值。内置算法在应用进程内执行；外部算法是常驻工作进程，可使用 Python、Kotlin、Rust、C、C++ 或 C#。安装包可以包含多个算法。

## 安装与卸载

打开偏好设置的「算法插件」，选择 ZIP，或输入本地目录、HTTPS／SSH Git 地址。Git 地址可附分支或标签；安装器记录实际提交。仓库根目录必须是已构建的插件包，不拉取子模块，不运行安装脚本，不在用户机器编译源码。

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

`plugin_enable`、`plugin_disable`、`plugin_uninstall` 使用 `plugin_list` 返回的 `id` 与 `digest`。内置包的两个值都是 `builtin`。除列表外均返回后台任务，通过 `job_wait`／`job_get` 查询终态。无需已打开工程。

添加和编辑外部修改器继续使用 `modifier_plan`、`modifier_add`、`modifier_update` 等现有操作；配置按插件提供的 schema 校验。计划结果固定包引用，提交期间插件改变则拒绝旧候选。所有公开操作均声明严格结果 schema。

## 编写插件

六语言源码与打包工具见 [examples/plugins](../../../examples/plugins/README.md)。包根目录的 `plugin.json` 声明：

- `protocol: 1`，包 `id`、`version`、`name`。
- `commands`：以 `windows-x86_64` 等平台标识为键的参数数组；JVM 包使用 `jvm: ["$JAVA", "-jar", "worker.jar"]`。不经过 shell。
- `files`：每个有效载荷的相对路径及 SHA-256；禁止越界路径、符号链接和未列出的文件。
- `algorithms`：算法类型必须以 `包ID/` 开头；声明算法版本、名称、目标、依赖、互斥关系、读写范围、可组合写入类型、`configSchema` 和 `defaults`。
- 可选 `parameters` 声明共享标准参数的 `id/name/min/max/default`。定义必须兼容上游同名参数；其他新增对象使用实例所属 ID。

每次请求和响应各占一行 UTF-8 JSON。标准输出只用于协议，日志写标准错误。请求包含 `protocol/id/method/type/version/instance/config/targets`；响应回显 `protocol/id`，返回 `commands` 或 `error`。同一工作进程串行处理请求，故障或超时后重建进程；取消终止正在使用的进程，不提交其部分结果。

模型输入按声明的读写组件裁剪；未知范围或 `*` 保守使用完整输入。`changed` 和 `removed` 同步对象索引，`reset` 表示全新会话。`frame`、`mesh:<id>`、`deformer:<id>` 的值为资源摘要，资源目录通过请求及 `PSD2LIVE_PLUGIN_RESOURCES` 提供；资源使用 `RigIrObjects` 的版本化精确二进制格式。`mesh_order` 和 `deformer_order` 保留顺序。

便携视图 `meshes` 提供目标网格 ID、透明度及位置／UV 缓冲引用；`parameters` 提供已声明可读的参数。浮点缓冲格式为小端 `f32le`，文件名等于 SHA-256。返回命令中的浮点数组也可用该引用表示，宿主校验摘要和有限数值后展开。

返回命令支持：

- `mesh_opacity`：目标网格 `id` 与范围 0–1 的 `value`。
- `physics`、`motion`：`config` 使用相应内置修改器的配置格式，输出 ID 由宿主管理。
- 编辑原语：`structure`、`set`、`parameter_keys`、`delete`、`canvas_geometry`、`canvas_topology`、`canvas_create_warp`、`canvas_create_rotation`、`canvas_create_glue`、`canvas_glue_edit`、`path_put`、`path_delete`，沿用作者编辑记录的格式。它们只在隔离候选内执行，不直接写历史。

新增对象 ID 为 `modifier:<实例ID>:<局部名称的UTF-8十六进制>`。宿主校验实际写入范围、共享参数、依赖、覆盖和冲突；插件不得直接操作文档、历史或 GUI。

## 性能边界

内置算法直接调用，不承担跨进程序列化开销。外部插件复用进程、按内容寻址传输缓冲，并增量同步输入。作者基础输入、绑定中间模板和可声明依赖的算法结果分别缓存；文件夹调整不触发算法求值。

目前内置绑定仍有生成模板后提取局部结果的兼容路径，尚未全部改为独立局部算法；算法调度仍按栈串行，未实现独立项并行。完整生成的 3 秒目标尚未达成。不能把这次插件接入或缓存命中等同于完整链路达标。
