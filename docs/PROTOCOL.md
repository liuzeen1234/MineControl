# MineControl 协议 v1

服务只绑定 127.0.0.1，默认端口 25580。TCP 字节流使用 UTF-8，每条 JSON 对象以 LF 结束；允许 CRLF。连接中可以发送多行，逐条按顺序接收回复。请求 ID 必须为字符串或数字，调用方负责唯一性。未知字段忽略。协议不需要认证，也不开放远程监听。

```json
{"id":"1","command":"key.hold","key":"W","ticks":20}
{"id":"1","ok":true,"result":{"screen":null,"world_loaded":true,"held_keys":[87]}}
{"id":"2","command":"key.tap","key":"unknown"}
{"id":"2","ok":false,"error":{"code":"UNKNOWN_KEY","message":"Unknown GLFW key: unknown"}}
```

这里的 result 为说明性节选，实际状态含完整字段。无法解析的请求使用 null ID；合法的 ID 会保留在错误响应中。输入由网络线程解析，经队列在客户端 tick 开始执行。超时且尚未执行的请求会被取消，不会稍后执行；已开始的动作不会回滚。按住状态不属于连接，正常断开保持状态直到 up/reset/到期/退出世界。串行服务不提供多客户端仲裁或动作事务。

| command | 参数 | result |
| --- | --- | --- |
| ping | 无 | pong=true, protocol=1 |
| status | 无 | 客户端状态 |
| key.tap/down/up | key: string | 执行后的状态 |
| key.hold | key, ticks: integer | 已安排按住后的状态 |
| mouse.click/down/up | button: left/right | 执行后的状态 |
| mouse.hold | button, ticks | 已安排按住后的状态 |
| mouse.goto | x, y: number | GUI 状态 |
| mouse.move | dx, dy: number | GUI 或游戏视角状态 |
| ui.list | 无 | 控件对象数组 |
| ui.click | widget: integer | 执行后状态 |
| ui.set | widget, value: boolean | 执行后状态 |
| ui.type | widget, text: string, mode: replace/append（默认 replace） | 执行后状态 |
| wait.screen | screen: string, timeout_ms: integer（默认配置超时减100ms） | 匹配时状态 |
| input.reset | 无 | 清理后状态 |

screen 支持全限定 Java 类名或区分大小写的简单类名（如 OptionsScreen），特殊值 none（不区分大小写）表示没有界面。status 的 screen 返回全限定类名或 null。状态还包括 world_loaded、window_focused、background_control、cursor_locked、tick、gui_width/height、cursor_x/y、held_keys（GLFW 数字）、held_buttons（0=左，1=右）。玩家存在时包含 yaw、pitch、player_x/y/z、on_ground。

ui.list 包括 id、type、label、enabled、visible、x/y、width/height；文本框含 editable、text，可识别的布尔控件含 value。嵌套控件递归枚举，实际点击通过当前 Screen 路由以保留父控件处理逻辑。ID 不含屏幕版本校验，调用方应在操作前重新读取列表。

| error.code | 含义 |
| --- | --- |
| INVALID_REQUEST | JSON 结构或 id/command 无效 |
| UNKNOWN_COMMAND | 不支持的 command |
| INVALID_ARGUMENT | 参数类型或范围错误 |
| UNKNOWN_KEY | 按键名称无法解析 |
| INPUT_HELD | 相同模拟输入已按住 |
| NO_SCREEN / NO_WORLD | 操作所需 GUI / 玩家不存在 |
| STALE_WIDGET | ID 不在当前控件列表中 |
| DISABLED_WIDGET / READ_ONLY | 控件禁用、隐藏 / 只读 |
| UNSUPPORTED_WIDGET | 控件类型或操作不支持 |
| REQUEST_TOO_LARGE | 请求超过字节上限；回复后关闭连接 |
| TIMEOUT | 客户端执行或等待界面超时 |
| STOPPED | 控制服务关闭 |
| INTERNAL_ERROR | 意外错误，查看客户端日志 |

CLI 退出码：0 成功；1 服务返回错误；2 连接、协议或读取错误。超过请求上限、未完成行就断开、空闲超时都会结束连接。回复不提供结果查询；timeout 后先检查实际状态。

## 宏（macro）

- `macro.run`: `script` 为 JSON 步骤数组，立即返回启动状态，后台按 tick 执行。
- `macro.status`: 查询宏状态。
- `macro.stop`: 停止当前宏并释放模拟输入；重复停止无副作用。

状态结果包含 `state`（idle/running/completed/stopped/failed）、`instruction`（从 0 开始的内部指令位置）、
`steps`（已执行内部指令数）；失败还包含 `error`。终态保留至下一次启动。
宏结构和示例见 README「宏脚本」。宏完成后的错误由 macro.status 查询，不会修改已返回的 macro.run 回复。

## 客户端观察（observe）

`{"id":"o1","command":"observe"}` 返回同一客户端 tick 的 `main_hand`、`off_hand` 和 `target`。CLI：`./minectl observe`。

- 无世界/玩家时双手和目标为 null；未命中时 target 为 null。`active` 仅在世界内且未打开 GUI 时为 true；GUI 打开时目标可能是背后的旧命中，宏应先检查 active。
- 物品包含 id、count、damage、max_damage、remaining_durability、format 和 nbt。非耐久物品 remaining_durability 为 0，应同时检查 max_damage。
- nbt 是 `{source:"client_snapshot",completeness:"unknown",data:{...},snbt:"..."}`。data 方便按路径读取；SNBT 保留数值类型和数组类型，JSON 数值不能代替 SNBT 做无损往返。
- 1.20.1/1.20.4 导出完整物品栈 NBT；26.2 导出物品栈保存数据（nbt）和包含默认值的有效组件（components，同样有 data/snbt）。仅支持游戏序列化器可保存的字段，运行时专用组件不保证能够导出。
- 方块 target 包含 kind=block、id、pos、state、face、distance 和 nbt；普通方块没有方块实体，nbt 为 null。
- 实体 target 包含 kind=entity、id、uuid、entity_id、distance 和 nbt。实体类型由外层 id 给出。序列化警告通过 serialization_warnings 返回。
- 只读客户端世界，不访问单人内置服务器，不要求 OP，不发送 NBT 查询包。客户端 NBT 包含本地默认/推导值，**不能据此断言服务端字段的真实值或完整性**。箱子库存等未同步信息不可知。
- 不设字段白名单，不静默截断。结果超过 900000 UTF-8 字节会返回 OBSERVATION_FAILED；序列化失败也会明确报错。

### 宏观察条件

宏条件快照在原 status 字段之外增加 `observe`，内容同 observe 命令。仅在执行条件时采集，普通 status 不携带原始数据。宏命令允许 observe，但其返回结果不会存为变量。

field 支持点路径或字符串数组；数组形式可精确访问含点号、冒号的键，列表下标使用十进制字符串：

```json
[{"if":{"all":[
  {"field":"observe.active","value":true},
  {"field":"observe.main_hand.max_damage","op":">","value":0},
  {"field":"observe.main_hand.remaining_durability","op":"<=","value":10}
]},"then":[{"command":"ping"}]}]
```

上例在耐久不足时执行 ping；实际挖矿脚本可在循环中改用 `{"break":true}`。下例是自定义数据分支：

```json
[{"if":{"field":["observe","main_hand","components","data","minecraft:custom_data","tool_type"],"value":"miner"},"then":[{"command":"ping"}]}]
```

组合条件支持非空 `all`/`any` 数组及 `not` 对象。`op:"exists",value:true/false` 判断路径是否存在（显式 null 也算存在）。合法根字段下路径不存在时所有普通比较均为 false，包括 !=；未知根字段仍报错。要判断“没有目标”，用 observe.target == null。组件/NBT 的客户端可见性和版本变化仍然适用。

## 玩家位置与区域方块查询

- `position`：返回玩家精确 x/y/z、向下取整的 `block:{x,y,z}`、yaw/pitch、dimension、tick。无玩家世界时 NO_WORLD。CLI：`./minectl position`。原 status 的 player_x/y/z 保留；observe 新增 `player:{x,y,z}`，无玩家时为 null，宏可读取 `observe.player.x` 等路径。
- `region.query`：`{"id":"r1","command":"region.query","from":[-10,60,-10],"to":[10,70,10]}`。两角包含端点，顺序不限；坐标必须是世界绝对整数坐标，查询当前客户端维度。
- CLI：`./minectl region -10 60 -10 10 70 10`。加 `--text` 只向 stdout 输出蓝图文本，可用 `> terrain.txt` 保存；默认输出协议 JSON。

区域结果包含 format=MCBLUEPRINT v3、source=client_snapshot、dimension、tick、规范化 from/to、volume、block_count、air_omitted=true、block_entity_data_included=false 和 blueprint 文本。例如：

```text
# MCBLUEPRINT v3
# name: region_query
# size: 2x1x1
# source: client_snapshot; air omitted; unloaded chunks rejected

## BLOCKS

# --- y=64 ---
-1,64,0   minecraft:oak_log   axis=y
0,64,0   example:custom_block   facing=north
```

与 AI Helper v3 的方块列表语法兼容，保留绝对坐标和完整命名空间，不依赖安装 AI Helper。它原有的服务端扫描器不能直接作为纯客户端数据源。区域输出仅包含方块及全部 BlockState 属性，暂不输出 v3 的 items/sign_text 扩展，也不读取服务器或假定客户端容器库存完整。单个准星目标的 NBT 仍可用 observe 获取。

单次最多 32768 格；先检查高度和所有涉及的区块，未加载区块返回 CHUNK_NOT_LOADED，不触发加载、不把缺失区块当成空气。超出世界高度返回 OUT_OF_HEIGHT；超体积返回 REGION_TOO_LARGE；过长文本或响应返回 RESPONSE_TOO_LARGE，均不返回截断蓝图。无世界返回 NO_WORLD，参数错误返回 INVALID_ARGUMENT。宏也允许调用 position 和 region.query，但命令结果不存为变量。

## 背包快照与生存状态

`inventory`（CLI `./minectl inventory`）只读返回玩家背包/快捷栏及装备槽中的非空物品，`slots:[{slot,stack}]` 中 stack 使用 observe 的完整物品结构；`counts` 按完整物品 ID 汇总数量，另含 tick/source。槽编号沿用当前版本原生 Inventory，跨版本应优先使用 counts。无玩家世界返回 NO_WORLD。此接口不包含箱子、合成界面临时物品或地面掉落，测试时应关闭界面，并用前后 counts 的差值统计背包净增量。

observe.player 新增 health、food、alive、game_mode；宏可用这些字段保护生存测试。

### 目标方块检测（仅 26.2）

`detection.scan`：在客户端线程分批扫描已加载区块，返回 `{world, dimension, hits:[{type,x,y,z}]}`。每次最多检查 512 个区段，约 3ms 的区段间时间预算，最多返回一个非空目标区段（4096 个候选）；不会强制加载区块。循环扫描以发现后续变化。

`detection.mark`：参数 `{world,dimension,type,x,y,z}`，只接受七种检测类型，通过 Xaero 小地图当前实际世界的路标管理器添加／确认路标，并保存。世界或维度已变化时拒绝。返回 `{marked:true}`。需安装兼容的 Xaero 小地图；不发送服务器指令。检测宏允许这两个专用命令，仍不允许控制玩家输入。70 格去重及发现文件由配套 `BlockDetector.java` 管理。
