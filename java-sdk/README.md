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

Minecraft 26.2 游戏内界面：按 F8，或点击标题/暂停菜单的“MineControl 宏”按钮。文件管理式列表显示文件名、类型、大小和运行状态，默认包含实体延迟点击宏。选择文件后可启动，运行时可停止。启动后自动返回游戏；关闭界面不会停止宏，可再次打开后停止。退出世界或游戏会停止界面启动的程序。

宏文件位于当前游戏实例的 `config/minecontrol-programs/`。点击“添加文件”粘贴完整文件路径，或把一个/多个文件拖入游戏窗口；文件复制到宏目录，同名自动改名，原文件保留。支持：

- `.java`：含 `public static void main(String[] args)` 的单文件 Java 程序，游戏 Java 会即时编译运行，SDK 和 Gson 自动加入 classpath，无需额外安装。首个程序参数 `args[0]` 为当前控制端口，使用 `MineControl.connect(Integer.parseInt(args[0]))`。编译错误会记入宏日志。
- `.jar`：带有 `Main-Class` 的可执行 JAR，自动加入 SDK/Gson 运行库，同样接收端口参数。其他第三方依赖需自行打包或通过 JAR manifest 的 Class-Path 提供。

“删除所选”会移到同目录 `.trash/`，可在文件管理器显示隐藏文件后手动恢复；正在运行的文件须先停止再删。删除默认宏后不会自动恢复。点“打开文件夹”可在系统文件管理器中编辑或放入文件，列表每两秒自动扫描，也可手动刷新。运行库放在隐藏的 `.runtime/`，不出现在列表中。

支持管理多个文件，同一时间运行一个，避免角色输入冲突。界面只管理自己启动的 Java 程序，不能停止另一个终端启动的控制器；旧 JSON 宏仍使用原 CLI 管理。每次运行使用独立 Java 进程，源文件修改后停止并重新启动即可生效。宏相当于本机 Java 程序，运行权限与游戏相同。

进入世界并关闭 GUI 后，在仓库根目录运行：

```sh
./gradlew :java-sdk:entityClick --console=plain
```

程序源码在 `examples/EntityClick.java`。准星首次指向实体时，随机等待 10–30 个客户端 tick（含两端）后直接点击一次左键，不再检查实体是否存在或是否为同一实体。因此等待期间目标消失、目标变化或打开界面，也仍然发送点击。持续指向实体不会重复触发；程序观察到准星不再指向实体后才重新触发。等待期间不采样，所以期间离开又返回的变化不会单独触发。Ctrl+C 停止并尝试释放输入。自定义端口添加 `-PcontrolPort=25581`。

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

务必使用 try-with-resources。SDK 执行过输入/UI 操作后，close 会发送 input.reset；程序抛异常时同样执行。关闭幂等，只读客户端关闭不会清理其他程序的输入。另有 JVM shutdown hook，在正常 JVM 退出或 Ctrl+C 时尝试清理。请先停止旧宏，并且只运行一个控制程序：现有协议的输入状态是全局状态，没有客户端所有权，reset 会影响所有控制来源。

强制结束进程、网络失败或游戏线程不响应时，不能保证立即释放。Mod 现有的 maxHoldTicks（默认 1200 tick）限制 down，hold 使用指定 tick；两者都按客户端 tick 到期释放。没有新增心跳租约或墙钟看门狗。

Mod 拒绝命令时抛 `CommandException`，用 `code()` 读取协议错误码；连接、帧格式和网络超时抛 `IOException`；可中断等待抛 `InterruptedException`。SDK 不自动重试动作，丢失回复不能证明动作未执行。长时间本地计算不会占住服务端连接，每个请求使用独立短连接。一个 SDK 实例的请求串行发送，但完整用户流程不具有事务性。

SDK 保留 Gson 作为公开依赖，以便访问原始 NBT、组件及扩展字段。只读快照受客户端可见性限制，不能代表服务端未同步数据。现有 CLI、JSON 宏和 Python 控制器仍可使用。
