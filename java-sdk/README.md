# MineControl Java SDK

在游戏外运行普通 Java 程序，通过本机 TCP 控制装有 MineControl 的客户端。SDK 要求 Java 17+，不依赖 Minecraft、Fabric 或游戏使用的 Java 版本。循环、变量、方法和异常使用 Java 本身的能力。

## 构建和使用

在仓库根目录执行：

```sh
./gradlew :java-sdk:build :java-sdk:publishToMavenLocal
```

SDK 产物在 `java-sdk/build/libs/`。用户的独立 Gradle 项目添加：

```groovy
repositories {
    mavenLocal()
    mavenCentral()
}
dependencies {
    implementation 'dev.minecontrol:java-sdk:1.0.0'
}
```

启动装有 MineControl 的游戏客户端，再运行自己的 Java main：

```java
import dev.minecontrol.sdk.MineControl;

public class Program {
    public static void main(String[] args) throws Exception {
        try (var mc = MineControl.connect()) {
            if (!mc.observe().active()) {
                throw new IllegalStateException("请先进入世界并关闭 GUI");
            }
            mc.key().holdAndWait("w", 20);
            mc.waitTicks(5);
            var hand = mc.observe().mainHand();
            if (hand != null && hand.maxDamage() > 0) {
                System.out.println("剩余耐久：" + hand.remainingDurability());
            }
        }
    }
}
```

完整移动示例见 `examples/Walk.java`。只读连接检查：`./gradlew :java-sdk:smoke`；自定义端口添加 `-PcontrolPort=25581`。

## 准星实体点击示例

Minecraft 26.2 游戏内界面：按 F8，或点击标题/暂停菜单的“MineControl 宏”按钮。文件管理式列表显示文件名、类型、大小和运行状态，默认包含实体延迟点击宏。选择文件后可启动，启动后自动返回游戏，并显示、释放系统鼠标光标；运行期间按 F9 停止。左上角显示运行文件和停止键，手动键鼠输入（包括 F8）暂时屏蔽，宏的模拟输入正常执行。停止后恢复手动操作；游戏有焦点且没有 GUI 时恢复鼠标捕获。停止键可在按键绑定中修改，未绑定时 F9 仍作为备用键。退出世界或游戏也会停止界面启动的程序。

宏文件位于当前游戏实例的 `config/minecontrol-programs/`。点击“添加文件”粘贴完整文件路径，或把一个/多个文件拖入游戏窗口；文件复制到宏目录，同名自动改名，原文件保留。支持：

- `.java`：含 `public static void main(String[] args)` 的单文件 Java 程序，游戏 Java 会即时编译运行，SDK 和 Gson 自动加入 classpath，无需额外安装。首个程序参数 `args[0]` 为当前控制端口，使用 `MineControl.connect(Integer.parseInt(args[0]))`。编译错误会记入宏日志。
- `.jar`：带有 `Main-Class` 的可执行 JAR，同样接收端口参数。Mod 提供的 SDK/Gson 运行库优先于 JAR 内嵌版本，以便修复 SDK 后已有宏也能生效；运行库不包含任何宏主类，不会覆盖用户程序入口。其他第三方依赖需自行打包或通过 JAR manifest 的 Class-Path 提供。

“删除所选”会移到同目录 `.trash/`，可在文件管理器显示隐藏文件后手动恢复；正在运行的文件须先停止再删。删除默认宏后不会自动恢复。点“打开文件夹”可在系统文件管理器中编辑或放入文件，列表每两秒自动扫描，也可手动刷新。运行库放在隐藏的 `.runtime/`，不出现在列表中。

支持一个执行宏、多个检测宏和一个调度宏并行，身份由程序注册声明。界面显示并管理自己启动的 Java 进程；外部注册实例可由调度宏管理。旧 JSON 宏仍使用原 CLI 管理，并占用执行名额。每次运行使用独立 Java 进程，源文件修改后停止并重新启动即可生效。宏相当于本机 Java 程序，运行权限与游戏相同。

宏退出失败时，界面会显示捕获到的异常原因，完整进程输出同时记录在当前游戏实例的 `logs/latest.log` 中，带有 `[Java 宏]` 前缀。观察数据中的 `nbt`、`components`、`state` 允许为空；SDK 会将显式 JSON null 转成 Java null，读取这些字段前应判空。

输入屏蔽只针对真人/原生键鼠回调，SDK 的输入、移动和 UI 方法保留通行。程序自行读取系统设备或调用其他 Mod 的接口不属于 MineControl 模拟输入。F9 停止由物理按键事件触发，SDK 中的 `key().tap("f9")` 不会伪装成物理停止键；需要程序主动结束时退出 main/循环并关闭客户端即可。

进入世界并关闭 GUI 后，在仓库根目录运行：

```sh
./gradlew :java-sdk:entityClick --console=plain
```

程序源码在 `examples/EntityClick.java`。每次观察到准星指向实体时，随机等待 10–30 个客户端 tick（含两端）后直接点击一次左键，不再检查实体是否存在或是否为同一实体。因此等待期间目标消失、目标变化或打开界面，也仍然发送点击。持续瞄准实体会重复随机等待并打击，不需要移开准星或重新打开界面；未瞄准实体时继续监听。等待期间不采样。Ctrl+C 停止并尝试释放输入。自定义端口添加 `-PcontrolPort=25581`。

此程序轮询客户端快照，可能错过两个采样之间短暂出现的实体；tick 等待从检测后的查询快照计时，网络与调度可能让实际点击略晚于随机 tick 数。

通信回归测试包含在 `:java-sdk:build` 中。可选真实输入检查：在客户端未进入世界且没有模拟输入时执行 `./gradlew :java-sdk:liveInputCheck`，它会短暂按住 Shift，验证到期释放和 close 清理，并读取菜单控件。此检查已在 Minecraft 26.2 测试客户端通过；世界内移动、背包和区域查询尚未进行本次实机验证。

## 方法

| 方法 | 用途 |
| --- | --- |
| `connect()` / `connect(port)` / `connect(port, Duration)` | 验证协议并创建客户端；默认 25580、单次网络操作超时 20 秒 |
| `status()` / `position()` / `observe()` / `inventory()` | 返回 `Snapshots` 中的 Java record；当前不可用字段可能为 null |
| `region(new BlockPos(...), new BlockPos(...))` | 查询区域及蓝图；返回端点为坐标列表 |
| `key().tap/down/up(key)` / `key().hold(key, ticks)` | 按键；key 沿用协议名称，如 w、space |
| `mouse().click/down/up(button)` / `mouse().hold(button, ticks)` | 左右键，使用 `MouseButton.LEFT/RIGHT` |
| `key().holdAndWait(...)` / `mouse().holdAndWait(...)` | 等到本次按住动作的到期 tick |
| `mouse().move(dx, dy)` / `mouse().goTo(x, y)` | 相对移动、GUI 绝对坐标；视角移动遵循游戏灵敏度 |
| `ui().list/click/set/type(...)` | 控件操作，type 默认替换，可传 `TextMode.APPEND` |
| `waitTicks(ticks)` / `waitTicks(ticks, Duration)` | 按实际客户端 tick 等待，默认总等待上限 5 分钟 |
| `waitScreen(screen, timeoutMs)` | 等待界面；timeoutMs 必须小于 Mod 的 requestTimeoutMs，也应小于 SDK 网络超时 |
| `resetInputs()` | 释放 Mod 所有模拟输入并停止现有宏 |
| `call(command, JsonObject)` | 扩展接口，返回原始 JSON；常用操作无需手写 JSON |

`hold()` 安排动作后立即返回，适合同时按多个键；`holdAndWait()` 阻塞外部程序直到动作到期。等待通过每次查询客户端 tick 实现，正常情况下每个查询也消耗客户端调度时间，因此不保证相邻调用同 tick 执行，也不保证返回时恰好处于目标 tick。游戏暂停、卡顿时使用真实 tick 进度，墙钟超时作为保护；单次网络调用可能额外耗尽其网络超时时间。等待跨客户端重启不受支持。

## 生命周期和错误

### 周围玩家触发传送（Minecraft 26.2）

`examples/PlayerGuard.java` 每个客户端 tick 扫描一次周围 10 格内已加载的其他存活玩家（排除自己和旁观者，按三维直线距离计算，包含恰好 10 格）。无需瞄准或视线无遮挡。检测到后发送一次 `/res tp liuhome` 并结束；再次监测需重新启动。服务器必须支持此命令，并允许当前账号传送；发送成功不代表传送成功。

运行 `./gradlew :java-sdk:playerGuardJar` 生成 `java-sdk/build/libs/player-guard.jar`，将源码或 JAR 添加到宏列表后启动。这个程序会发送传送命令，因此默认按执行宏运行，与攻击执行宏互斥；如需拆成只读检测和执行，请使用下方的广播示例。

SDK 新增 `nearbyPlayers(radius)`（半径大于 0 且不超过 128）和 `sendCommand(text)`，分别对应协议 `players.nearby` 和 `chat.command`。

### 自动点击与玩家警戒合并

`examples/GuardedEntityClick.java` 将自动点击与玩家警戒放在同一个进程中。准星前出现实体后随机等待 10–30 tick 再左键点击，不重新验证目标；等待期间仍扫描周围 10 格内已加载的其他存活非旁观玩家（不含自己）。

发现玩家时取消待执行点击、释放模拟输入并发送一次 `/home out`，进入避让状态。避让期间持续检测，只有周围连续 20 个客户端 tick 没有玩家，才发送一次 `/home work` 并恢复自动点击。中途出现玩家或世界暂时不可用，会清零无玩家计时；同一轮避让不重复发送 `/home out`。恢复后继续警戒，可反复进入避让。已移除原来的 20–40 分钟和返回后 10 秒等待。

运行 `./gradlew :java-sdk:guardedEntityClickJar` 生成 `java-sdk/build/libs/guarded-entity-click.jar`，宏列表只需启动这个 JAR，F9 可停止整个流程。跨维度传送保留宏与输入控制；世界短暂不可用时暂停操作、最多等待 30 秒。断开连接、加载超时或网络错误时结束宏。服务器需设置 `work`、`out` 家，并支持对应命令；宏确认命令发送，不确认传送成功。正常 20 TPS 下 20 tick 约为 1 秒，卡顿时更长；玩家查询与操作有调度间隔，检测仅覆盖客户端同步的数据。


务必使用 try-with-resources。注册或托管宏关闭时会报告退出理由，由管理器清理所属执行输入；检测宏关闭不会清理执行宏的输入。关闭幂等，另有 JVM shutdown hook 在 JVM 退出或 Ctrl+C 时尝试报告。托管进程的最终退出事件以实际进程结果为准。未注册的旧式客户端仍在发送过输入/UI 操作后使用 input.reset 清理；新程序建议显式注册身份。

强制结束进程、网络失败或游戏线程不响应时，不能保证立即释放。Mod 现有的 maxHoldTicks（默认 1200 tick）限制 down，hold 使用指定 tick；两者都按客户端 tick 到期释放。没有新增心跳租约或墙钟看门狗。

Mod 拒绝命令时抛 `CommandException`，用 `code()` 读取协议错误码；连接、帧格式和网络超时抛 `IOException`；可中断等待抛 `InterruptedException`。SDK 不自动重试动作，丢失回复不能证明动作未执行。长时间本地计算不会占住服务端连接，每个请求使用独立短连接。一个 SDK 实例的请求串行发送，但完整用户流程不具有事务性。

SDK 保留 Gson 作为公开依赖，以便访问原始 NBT、组件及扩展字段。只读快照受客户端可见性限制，不能代表服务端未同步数据。现有 CLI、JSON 宏和 Python 控制器仍可使用。

### 多宏调度与广播

使用 `MineControl.connect(port, MacroRole.DETECTION, "宏名称")` 声明身份，身份可选 `EXECUTION`、`DETECTION`、`SCHEDULER`。检测宏可多个，执行宏和调度宏各限一个。支持 `subscribe`、`broadcast`、`pollBroadcasts`、`stopRequested`、`exitReason`，调度宏另可调用 `macros`、`startMacro`、`stopMacro`、`pauseMacros`、`resumeMacros`。完整规则和运行示例见 [宏调度说明](../docs/MACRO-SCHEDULING.md)。
