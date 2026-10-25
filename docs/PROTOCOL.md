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
