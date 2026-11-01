# 宏注册、调度与广播

Minecraft 26.2 的 F8 列表支持并行启动不同的 Java 宏文件；同一文件只允许一个进程。程序应在读取游戏状态之前声明身份：

```java
try (var mc = MineControl.connect(port, MineControl.MacroRole.DETECTION, "玩家检测")) {
    mc.subscribe("danger");
    while (!mc.stopRequested()) {
        // 读取 mc.observe() / mc.nearbyPlayers(10)，自行判断。
        var data = new com.google.gson.JsonObject();
        data.addProperty("message", "检测结果");
        mc.broadcast("danger", data);
        for (var message : mc.pollBroadcasts().messages()) {
            System.out.println(message.channel() + ": " + message.data());
        }
        Thread.sleep(500);
    }
    mc.exitReason("已响应停止请求");
}
```

| 身份 | 数量 | 权限 |
| --- | --- | --- |
| `EXECUTION` | 最多一个，和旧 JSON 宏互斥 | 读状态、操作游戏、收发广播 |
| `DETECTION` | 多个 | 只读状态、收发广播 |
| `SCHEDULER` | 最多一个 | 只读状态、收发广播、启动停止宏、暂停恢复系统 |

管理器在游戏线程串行检查权限；检测宏和调度宏不能按键、点击、发送聊天命令、清空输入或启动 JSON 宏。只有执行宏接管键鼠；只有检测宏或调度宏时仍可手动玩。Java 程序不是沙箱，这些限制针对 MineControl 协议，不能限制任意本机 Java 代码。

注册繁忙返回 `CommandException`，`code()` 为 `MACRO_BUSY`，不会自动排队。可以保留普通 `connect(port)` 得到的客户端，调用 `register(role, name)`，捕获繁忙错误后自行延时重试。注册成功前不得调用游戏状态或操作方法；托管旧宏第一次非 ping 请求默认注册为执行宏。实例令牌由管理器通过环境传给 SDK，后续请求携带令牌，不依赖 TCP 连接是否重建。

旧 SDK 和默认 `connect(port)` 的托管宏仍可工作，未声明类型时默认执行宏。旧版 CLI 无令牌操作在执行宏运行时被拒绝；旧 JSON 宏仍单任务，不能与 Java 执行宏并行。系统级广播目前针对注册的 Java 宏实例，旧 JSON 宏没有注册和广播接口。

## 调度 API

- `macros()`：列出实例 ID、名称、文件、身份、是否停止中和系统开关状态。启动中的进程身份为 `registering`。
- `startMacro(file)`：按宏目录的文件名启动进程。返回仅表示进程启动请求被接受；注册成功看全局 `started` 事件，失败退出看列表和日志。
- `stopMacro(instanceId, reason)`：请求指定实例退出，立即禁止它进一步控制游戏。它可以用 `stopRequested()` 检查；三秒后未退出的托管进程收到终止信号，再过一秒仍未退出则强制结束。
- `pauseMacros(reason)`：停止其他宏和 JSON 宏并禁止新注册，保留调度宏。
- `resumeMacros()`：允许新启动和注册；不会自动重启已经停止的程序，由调度逻辑决定。
- `exitReason(reason)`：设置关闭客户端时报告的退出理由。程序使用 try-with-resources 关闭连接，管理器会清理执行输入并发布退出事件。异常处理应显式设置失败理由。

退出、断线或心跳超时的调度宏会请求停止其余实例。宏切换时先请求停止旧实例，等待其 `exited` 事件或从列表消失，再启动新执行宏。退出后的令牌不可再操作；释放输入与释放执行名额在同一游戏线程完成。

F8 可手动启动和停止所选文件；执行宏接管输入时也可按 F8，管理界面允许手动点击和添加文件，返回游戏后继续屏蔽手动游戏输入。F9、退出世界及关闭客户端停止全部宏并锁住系统；F8 中的手动启动会解除锁定。调度宏不能在停止过程中恢复系统。全局管理器持续运行以处理查询和退出，暂停不是冻结 Java 线程。

外部注册客户端由 SDK 每五秒发送心跳，三十秒无请求会撤销身份；托管进程由进程退出监控回收。TCP 单个连接关闭不是宏退出，因为 SDK 每次请求使用独立连接。宏必须定期检查停止请求；外部进程不由管理器启动，停止超时只会撤销其令牌，不会杀死该本机进程。

## 广播

`subscribe(channel)` 订阅自定义通道；`broadcast(channel, JsonElement)` 发布结构化数据；`pollBroadcasts()` 返回消息及 `dropped` 丢弃数。订阅后仅接收新消息，系统不重放历史。可随时查询 `macros()` 获得运行快照。

每条消息包含 `sequence`、`channel`、毫秒时间 `time`、发送者名称 `sender`、实例 ID `sender_id` 和 `data`。每个实例最多订阅 32 个通道，单条数据最多 4096 个字符；最多积压 128 条，满时丢弃最旧消息并累计 `dropped`。需要可靠状态的调度逻辑遇到丢弃应重新查询或暂停，不应只依赖事件。

`$system` 是保留通道，所有注册实例自动接收，只允许管理器发布。`data` 包含：

- `event`：`started`、`stop_requested` 或 `exited`。
- `name`、`instance_id`、`role`：事件所属宏的身份。
- `reason`：启动为空，停止为请求原因，退出优先采用宏自己提供的理由。
- `exit_type`：正常退出 `normal`、异常退出 `failed`、被停止 `stopped`、外部心跳超时 `timeout`。
- `requested_by`、`requested_reason`：发起停止者和停止原因，与宏自己的退出理由分别保留。

同名宏通过实例 ID 区分。未注册成功不发布 `started`；同一注册实例只发布一次 `exited`。托管宏提交理由后保留执行名额，待进程实际结束再发布退出事件；因此主线程抛异常不会被 SDK 的默认关闭理由误报成正常退出。进程崩溃或未报告退出理由时，管理器补充进程退出状态。

## 可运行示例

把 `java-sdk/examples/BroadcastDetector.java`、`BroadcastExecutor.java`、`MacroScheduler.java` 添加到 F8 宏目录，启动 `MacroScheduler.java`。调度宏会启动检测和执行示例；检测宏发布十格内玩家状态，发现玩家后调度宏停止执行示例。执行示例只打印广播，没有默认移动操作，可自行添加。F9 停止全部。

## 世界切换与退出

- 同一连接内跨维度传送不会停止任何角色的宏。JSON 宏在世界未就绪或显示地形加载界面时暂停，恢复后从原指令继续，等待 tick 不计入加载期间。
- Java SDK 遇到 Mod 返回 `WORLD_LOADING` 时等待并重试；该响应保证操作尚未执行。等待期间仍允许心跳、关闭和其他线程查询状态。网络超时及其他错误不会自动重试，避免重复操作。
- 断开服务器或退出单人世界时，停止检测宏、执行宏、JSON 宏和尚未声明角色的宏，释放模拟输入；调度宏保留，宏系统不会因此暂停。调度宏可通过 `status.world_loaded` 判断世界是否存在，通过系统消息接收其他宏的停止与退出通知。
- 手动停止全部宏、调度宏主动退出以及关闭客户端仍按原规则停止相关宏。跨服务器切换若实际断开旧连接，按退出服务器处理。
- Java 程序须使用更新后的 SDK；独立运行且自带 SDK 的程序需要同步更新。程序自行设置的超时或未处理的其他异常仍可能使其退出。

### 隔离客户端回归

在项目根目录执行；准备时传入未被打开的测试存档，脚本将复制存档。测试会切换旁观模式、跨维度传送并退出世界，勿用于日常游戏目录。

```sh
./gradlew inputCaptureTestJar
python3 tests/live_world_lifecycle.py --prepare-from '测试存档路径'
./gradlew runClient -PrunDir=run-lifecycle-check --args='--quickPlaySingleplayer Lifecycle'
# 另一个终端，客户端启动后执行：
python3 tests/live_world_lifecycle.py
```

报告保存为 `run-lifecycle-check/lifecycle-report.json`。探针仅包含在测试 JAR 中，不进入发布 Mod。
