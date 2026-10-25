# MineControl

Minecraft Fabric 客户端控制 Mod，配套标准库 Python CLI `minectl`。支持 Minecraft **26.2 / 1.20.4 / 1.20.1**。26.2 使用 Java 25，1.20.x 使用 Java 17。

## 安装

1. 使用匹配 Minecraft 版本的 Fabric Loader（26.2 需 0.19.3+；1.20.x 需 0.15.11+），并安装相应版本 Fabric API。
2. 将 `build/libs/minecontrol-mc26.2-1.0.0.jar`、`minecontrol-mc1.20.4-1.0.0.jar` 或 `minecontrol-mc1.20.1-1.0.0.jar` 放入该实例的 `mods`。选择对应版本的普通 jar，sources jar 用于阅读源码。
3. 启动游戏。配置首次自动写入实例的 `config/minecontrol.json`；日志出现 `MineControl listening on 127.0.0.1:25580` 表示就绪。
4. 安装 Python 3.9+，在此项目目录运行 `./minectl ping`。Windows 使用 `python cli/minectl.py ping`。

CLI 无第三方 Python 依赖，可以单独复制 `cli/minectl.py` 使用。服务只接受本机回环连接；本机程序可操作游戏。当前服务按连接顺序串行处理，空闲连接会超时退出。

## 用法

```sh
./minectl ping
./minectl status
./minectl ui list
./minectl ui click 4
./minectl ui set 9 on
./minectl ui type 6 '测试世界'
./minectl ui type 6 '追加内容' --mode append
./minectl key tap ESCAPE
./minectl key down LEFT_SHIFT
./minectl key down W
./minectl key up W
./minectl key up LEFT_SHIFT
./minectl key hold SPACE 10
./minectl mouse click left
./minectl mouse down right
./minectl mouse up right
./minectl mouse hold left 20
./minectl mouse goto 200 120
./minectl mouse move 30 -10
./minectl wait screen OptionsScreen --timeout-ms 10000
./minectl wait screen none
./minectl input reset
./minectl --port 25581 --timeout 20 status
```

控件 ID 从 0 开始，只有当前屏幕有效。每次改变屏幕后重新 `ui list`；可使用控件类名、标签、位置和 `value` 定位。`ui set` 支持 CheckboxWidget 和布尔值 CyclingButtonWidget，重复设置不会反复切换。其他控件用 `ui click`，无法枚举的自定义界面用坐标鼠标输入。禁用或隐藏控件和只读输入框不会被强制操作。文本的长度上限和过滤仍由 Minecraft 控件决定。

按键不区分大小写，使用 GLFW 名称，如 `W`、`SPACE`、`F3`、`LEFT_CONTROL`、`RIGHT_SHIFT`。别名包括 `CTRL`、`SHIFT`、`ALT`、`ESC`、`RETURN`、`CMD`。组合输入通过多个 down 再逐个 up 完成。键盘命令模拟按键事件；Unicode 文字通过 `ui type` 输入。

`tap`、`click` 的按下状态维持一个客户端 tick，下一 tick 开始时释放；回复表示已按下并安排释放。`hold` 的回复也在安排成功后返回。客户端通常每秒 20 tick。CLI 退出不会释放 down，up/reset、到期或退出世界会释放。重复 down/tap 同一个已经模拟按住的键返回 `INPUT_HELD`，避免意外破坏已有按住状态。

GUI 的 goto/move 使用 GUI 缩放后坐标，原点在左上角；status 返回尺寸和光标位置。无 GUI 时 move 使用原始鼠标增量单位，遵循普通鼠标灵敏度及反转 Y：每单位角度为 `0.15 × 8 × (0.6 × sensitivity + 0.2)^3` 度；默认灵敏度 0.5 时为 0.15°。视角直接更新，无平滑或电影镜头滤波；pitch 会被游戏限制。`mouse goto` 要求打开 GUI。

## 配置

```json
{
  "enabled": true,
  "port": 25580,
  "backgroundControl": true,
  "maxHoldTicks": 1200,
  "requestTimeoutMs": 15000,
  "maxRequestBytes": 65536
}
```

修改后重启客户端。默认后台控制在游戏内允许失焦输入，并在本次会话禁用失焦暂停；正常关闭时恢复原失焦暂停值。`status.window_focused` 报告实际焦点。设为 false 使用原版焦点行为。世界菜单仍遵循原版暂停机制，客户端输入 tick 继续运行；先关闭暂停菜单再控制移动。`maxHoldTicks` 是所有 down 的最长客户端 tick 时长，也是 hold 上限。CLI 可设置 `MINECONTROL_PORT` 环境变量。

请求超时后先查看 status，再决定是否重试；已开始的游戏动作无法回滚。`wait screen` 超时必须小于服务的 requestTimeoutMs，默认 10 秒。退出世界和正常关闭客户端清理模拟输入。`input reset` 可随时释放本 Mod 保存的输入。

## 构建和验证

```sh
# Gradle 8.8 需要兼容的启动 JDK；建议 JAVA_HOME 指向 Java 17。
./gradlew build
./gradlew build -Pmc=1.20.1
./gradlew runClient
python3 -m unittest discover -s tests -v
# 独立开发客户端停留在主菜单且没有已有世界时：
python3 tests/live_smoke.py
# 新建独立测试世界并进入后：
python3 tests/live_world.py
```

构建产物带 Minecraft 版本，两个版本可同时保存在 build/libs。开发客户端数据默认位于本项目 run，和普通游戏实例分离。可以用 `-PrunDir=run-1.20.1` 为不同版本指定隔离目录。实际验证及限制见 [测试记录](docs/TESTING.md)，协议见 [协议文档](docs/PROTOCOL.md)。

## MC 26.2 适配

参考 debug_menu 的 `-Pmc` 多版本构建方式，各目标分别生成 jar。
Gradle 9.5.1 / Loom 1.17.21 需用 Java 25 启动；编译 1.20.x 时还需安装 JDK 17。
默认目标仍为 1.20.4。

```sh
./gradlew build -Pmc=26.2
./gradlew build -Pmc=1.20.4
./gradlew build -Pmc=1.20.1
```

26.2 使用官方无混淆类名，版本适配代码在 `src/mc26.2/java`，旧版在
`src/main/java`；协议服务和配置在 `src/common/java` 共用。构建中间文件按版本隔离，
可安装 jar 汇总到 `build/libs`。修改控制功能时须同步两套适配并验证三个目标。
26.2 的 ui list 返回官方控件类名（如 EditBox、Checkbox、CycleButton），
wait screen 同样使用该版本的屏幕类名；CLI 命令与协议保持一致。

验证：三个目标的 build 与协议检查、4 项 CLI 单元测试均通过；26.2 开发客户端
已实测 GUI 点击、失焦键鼠输入、高 DPI 坐标、组合键、重置、定时释放、
EditBox 中文输入以及布尔 CycleButton 的幂等设置。未完成进入世界后的移动实测。
