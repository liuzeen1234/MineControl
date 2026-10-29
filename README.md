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
# 使用 Java 25 启动 Gradle；默认构建和测试 Minecraft 26.2。
./gradlew build
./gradlew build -Pmc=1.20.1
./gradlew runClient
python3 -m unittest discover -s tests -v
# 独立开发客户端停留在主菜单且没有已有世界时：
python3 tests/live_smoke.py
# 新建独立测试世界并进入后：
python3 tests/live_world.py
```

构建产物带 Minecraft 版本，三个版本可同时保存在 build/libs。开发客户端数据默认按版本隔离：26.2 使用 `run-26.2`，旧版使用 `run-1.20.4` 或 `run-1.20.1`；原 `run` 目录保留。可以用 `-PrunDir=目录` 指定其他测试目录。实际验证及限制见 [测试记录](docs/TESTING.md)，协议见 [协议文档](docs/PROTOCOL.md)。

## MC 26.2 适配

参考 debug_menu 的 `-Pmc` 多版本构建方式，各目标分别生成 jar。
Gradle 9.5.1 / Loom 1.17.21 需用 Java 25 启动；编译 1.20.x 时还需安装 JDK 17。
默认构建和开发测试目标为 **26.2**，`./gradlew build` 和 `./gradlew runClient` 均无需指定版本；旧版测试需显式指定 `-Pmc=1.20.4` 或 `-Pmc=1.20.1`。

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

## Java 编程接口

现在可以使用独立运行的 Java 17+ 程序，通过 SDK 方法控制角色，直接使用 Java 的变量、循环、函数和第三方库。接入、示例和方法列表见 [Java SDK 文档](java-sdk/README.md)。Mod 继续负责在客户端线程执行操作；现有 CLI 和 JSON 宏保持兼容。

```java
try (var mc = dev.minecontrol.sdk.MineControl.connect()) {
    mc.key().holdAndWait("w", 20);
    System.out.println(mc.position());
}
```

请先进入世界并选择可安全移动的位置。构建 SDK：`./gradlew :java-sdk:build`；只读连接检查：`./gradlew :java-sdk:smoke`。

## 宏脚本

宏在 Mod 内随客户端 tick 执行，CLI 返回后继续运行。当前每次只运行一个宏。

```sh
./minectl macro run examples/macro-demo.json
./minectl macro status
./minectl macro stop
```

示例在世界内向前移动三次，每次按住 W 20 tick，再等待 25 tick；没有加载世界时退出循环。
宏文件是 JSON 数组，步骤支持：

- 指令：`{"command":"mouse.click","button":"left"}`，参数同协议。
- 等待：`{"wait":20}`，等待客户端 tick，范围 1–1000000。
- 循环：`{"repeat":10,"do":[...]}`，次数范围 0–1000000。
- 条件循环：`{"while":{"field":"world_loaded","value":true},"do":[...]}`。
- 分支：`{"if":{"field":"screen","value":null},"then":[...],"else":[...]}`，else 可省略。
- 跳出最近的循环：`{"break":true}`。

条件读取当前 `status` 的顶层字段，`op` 支持 `==`（默认）、`!=`、`<`、`<=`、`>`、`>=`；
大小比较只接受数字。无界面用 JSON `null` 判断；界面名使用 status 返回的完整类名。
未知字段会使宏报错。当前不支持变量、算术表达式或发送游戏聊天命令。手持物耐久和 NBT 条件见下方观察接口。

宏可调用现有键鼠、UI、ping、status 和 wait.screen 指令，不能调用宏管理或 input.reset。
`hold` 仅安排按住，不会等待释放；后续需用 wait 留出足够时间。
每 tick 最多执行一条内部指令，循环和分支也占 tick，实际运行时间会比 wait 总和更长。
wait.screen 遵循现有超时规则。

宏正常结束、停止、出错、退出或切换世界时释放本 Mod 的所有模拟输入；
`input reset` 也会停止宏。结束前若仍有 hold，会被立即释放，应在脚本末尾等待其完成。
宏不会保存或自动恢复，重启后需重新运行。脚本最多 4096 条编译指令、嵌套 32 层，
上传请求仍受 maxRequestBytes（默认 65536 字节）限制。脚本结构在启动前检查，
具体指令参数在执行时检查；已有宏运行时再次启动会被拒绝。

## 手持物与准星目标观察

`./minectl observe` 导出主副手物品、准星方块状态以及方块实体/实体的客户端完整可序列化 NBT，无字段白名单。旧版提供物品 NBT，26.2 另提供有效数据组件（含默认值）。同时返回 JSON 与保留类型的 SNBT。

宏条件可用 `observe.main_hand.remaining_durability`、`observe.target.id` 等路径，支持路径数组、exists、all/any/not。原始数据位于 `nbt.data`，26.2 有效组件位于 `components.data`。完整字段与示例见 [协议文档](docs/PROTOCOL.md#客户端观察observe)。

这是客户端快照，不代表服务端完整 NBT；未同步数据无法取得。普通 status 保持轻量，完整观察通过 observe 按需读取。

## 玩家位置与区域地形

```sh
./minectl position
./minectl region -10 60 -10 10 70 10
./minectl region -10 60 -10 10 70 10 --text > terrain.txt
```

区域按两个角的绝对坐标查询，包含端点，输出兼容 AI Helper 的 MCBLUEPRINT v3 方块列表（方块 ID、全部状态属性，省略空气）。无需安装 AI Helper。单次最多 32768 格，只读客户端已加载区块；不会触发区块加载。区域暂不包含容器物品和告示牌文字。详见[协议](docs/PROTOCOL.md#玩家位置与区域方块查询)。

## 10 分钟生存挖矿基准

```sh
python3 cli/mining_benchmark.py --seconds 600 --out mining-results
```

先进入已准备好的安全双格矿道，生存模式、主手下界合金镐、俯仰角 0。默认鼠标灵敏度 0.5、关闭 Y 轴反转；其他灵敏度需要校准 --pitch-dy（默认 200，约 30 度）。脚本生成 macro.json，通过键鼠宏交替挖上/下方块，真实时间达到 600 秒后停止，并写入 before.json/report.json。无需发送游戏聊天命令即可执行已准备场地的测试。

保护条件检查世界/界面、游戏模式、生命 >=16、饥饿 >=4、工具类型、剩余耐久 >32 和目标方块白名单。不满足条件会提前结束，报告记录实际时间，不能算作完整 10 分钟。退出/异常尽力停止宏，断开控制连接本身不保证宏立即结束，宏另有客户端 tick 截止条件。可用 `./minectl macro stop` 手动停止。

report.json 记录背包净增物品、耐久消耗和位移。结束后额外等待 2 秒收取附近掉落，这 2 秒不计挖矿时间；未拾取/销毁的掉落不算收获。净增量不等于精确挖掉的方块数。实际结果应注明场地和难度，固定矿道数据不能代表自然矿洞收益。

`macro.json` 含本次客户端 tick 的绝对截止值，仅作为本次运行记录；再次测试请重新运行 mining_benchmark.py 生成。后台测试会忽略未聚焦窗口的原生鼠标视角增量，协议 mouse.move 仍正常生效，避免桌面鼠标移动偏转矿道方向。

## 直线挖矿（只依据可见方块）

```sh
python3 cli/straight_miner.py --seconds 600 --out straight-results
```

进入已准备好的生存矿道，站在方块中心附近、手持下界合金镐，朝向要前进的方向；脚本将方向对齐最近的东西南北方向，沿同一高度开一格宽、两格高的直线矿道。默认灵敏度 0.5，关闭鼠标 Y 轴反转；可用 `--degrees-per-mouse` 校准。

路线不由矿石分布决定。脚本只使用准星首先命中的方块来清理前方、确认落脚地面和检查身边表面；每次挖开后重新观察，只采集可见且够得到的矿石，不绕路挖隐藏矿，不预查整条矿脉，也不承诺整条矿脉或全部掉落已收集。脚本的命令白名单禁止 `region.query`，包含误调用继承方法的情况。

前方矿道或落脚面遇到沙砾/沙子时，从侧面挖除并等待下落，再重新观察；落脚面缺失时，先确认自身稳定支撑，蹲到边缘，用快捷栏中的实心方块搭路，确认放置成功后才前进。搭路期间临时切换材料，随后恢复下界合金镐；请将镐及圆石、深板岩圆石、石头等材料放在快捷栏。材料不足、放置失败、下落方块持续填充或无法安全到达边缘时停止。生命低于 16、饥饿低于 6、镐剩余耐久不超过 40、界面打开、不支持的方块或移动/挖掘超时也会停止；不会自动吃饭或战斗。普通准星检测不能提前发现墙后岩浆，也不能可靠识别所有液体，因此须在允许自动化的测试环境使用，不能保证任意洞穴都安全。每次运行输出 `report.json`（实际时长、停止原因、位移步数、背包净变化、耐久消耗）和 `actions.json`（实际观察及动作），退出时释放输入并尝试暂停单人世界。紧急停止先对脚本按 Ctrl+C；也可打开游戏菜单使状态检查停止，必要时再执行 `./minectl input reset`。计时在当前工作单元完成后检查，实际时长以报告为准。

下面保留的旧版扫描脚本会读取未暴露的客户端方块，**不符合当前普通挖矿需求**；新测试与服务器正常挖矿请使用上面的直线脚本。

## 双格竖井下挖后挖矿（无需 OP）

```sh
python3 cli/shaft_miner.py --target-y 32 --heading west --mine-seconds 120 --out shaft-results
```

在地表稳定方块中心附近进入生存模式，手持现有下界合金镐。脚本交替使用两格竖井，每次从旁边清理另一格、确认下一层可见实心落脚面后下降一格，不挖当前唯一支撑；达到指定脚部 Y 后自动朝 `--heading` 开始直线挖矿。默认深度为 Y=-54；目标必须低于当前脚部高度。`--descent-timeout` 默认 1800 秒，`--mine-seconds` 默认 600 秒；默认鼠标灵敏度 0.5，关闭轴反转。

全程仅使用客户端准星首个命中、位置、现有背包及普通键鼠输入，拒绝区域查询、聊天命令和服务端操作。不创建装备、不传送、不更改模式，不需要 OP。脚本可清理暴露的藤蔓、草、原木和树叶，但不会把它们误认为可靠落脚面。空洞/水面荷叶不当作实心支撑；缺少可靠落脚面、持续落沙、移动停滞或生命/饥饿/耐久保护触发时停止，下挖失败不会继续水平挖矿。进水等复杂情况目前需要玩家先用正常放块操作处理，再重新运行；不能保证自动完成任意天然竖井。

`descent/` 保存下挖报告、逐层动作及完整客户端指令列表；`mining/` 只在成功达到目标后生成。脚本不会自动建造返回地面的梯子，测试前需自行安排回程。无 OP 联机实测见 [下挖测试记录](docs/noop-shaft-test/summary.json)。

## 鱼骨挖矿与矿道外一格搜索

```sh
python3 cli/fishbone_miner.py --heading north --spacing 3 --branch-length 12 --main-length 64 --seconds 600 --out fishbone-results
```

在现有安全矿道内、生存模式且手持下界合金镐时运行。主道按指定方向延伸；每前进 `--spacing` 格，先开左侧直角支路、原路返回，再开右侧并返回，继续主道。每格矿道一格宽、两格高，保留已有清沙砾和搭路行为。主道和支路长度由参数决定，不随查询到的矿石位置偏转。

鱼骨模式将现有 `region.query` 用作受限方块搜索：只查询已挖矿道单元外一格（3×4×3 中排除矿道本身的两格，共 34 格），矿石挖掉后仅查询新空腔外一格（26 格）。矿道边界内可确认的空气用于判断暴露面，但空气不能作为继续扩大查询的起点。找到候选矿石后，必须有暴露面，并通过准星首个命中确认才挖；不会隔墙追逐查询到的假矿，也不打开旧版全区域矿脉扫描。

追挖遵循“挖一块、查新空腔周围一格”，可进入同高度已经挖掉的矿石格，采完沿已知通道返回当前鱼骨矿道单元。当前脚下支撑不挖，超出够取距离、需要升降的矿、超出离矿道 8 格的临时路径等不保证采尽；单次工作最多采 128 块，未确认/暂不可处理的候选记录在 `deferred_candidates`，不宣称整条天然矿脉已挖尽。异常高度变化、生命/饥饿/工具保护等会停止。水流仍可能把角色推离狭窄搭路，当前实现不是任意洞穴的安全保证。

下挖后接鱼骨模式可使用：

```sh
python3 cli/shaft_miner.py --target-y 32 --heading north --pattern fishbone --spacing 3 --branch-length 12 --mine-seconds 600
```

输出 `report.json`（路线、搜索边界、已挖矿石、待确认候选和背包变化）及 `actions.json`。到时检查发生在工作单元边界；如果结束时位于支路中，不保证自动返回总入口。直线和竖井模式仍禁止一般区域查询，只有鱼骨模式的受限一格搜索入口可以查询。[鱼骨实测](docs/fishbone-test/summary.json)。

## 自然地形扫描与完整矿脉追踪

```sh
python3 cli/vein_miner.py --seconds 600 --out vein-results
```

这是在现有客户端指令接口上运行的有状态控制脚本：扫描周围方块，按同类矿物的 26 邻接追踪整条矿脉（含斜角、深层变种，以及天然粗铁/粗铜块），规划有已知落脚面的路线，通过普通键鼠输入挖掘，最后重新查询全部原矿块和邻接边界。只有边界完整、没有同类矿块残留时才标记 complete=true；背包收获数量不能代替矿脉完整性验证。每条矿脉的成员、残留、错误和拾取不可达位置写入 veins.json，动作和背包差值另行保存。

测试前进入生存模式，快捷栏第 1 格放下界合金镐，第 2 格食物，第 3 格火把。当前脚本会尝试插火把，但不会自动战斗或进食；生命低于 16、饥饿低于 6、耐久不足、遇液体/未知边界或无法安全行走时停止。鼠标灵敏度 0.5、关闭轴反转；其他灵敏度需校准 --degrees-per-mouse。脚本会尽力释放输入并暂停游戏。它不负责创建世界、传送或发放物品。

当前识别主世界常见矿石，单次矿脉追踪上限 512 块。已加载区块中的隐藏矿石也能被查询到。计时结束后不再开始新矿脉，最多额外用 180 秒完成正在处理的矿脉；超时/危险情况下会明确记录未完成，不能保证任意天然矿脉都能安全挖尽。零散静态 JSON 宏没有保存查询结果和图搜索的能力，因此该流程由 Python 控制器组合现有输入、observe、region.query 和 inventory 指令完成。

需要从中断记录继续时：`python3 cli/vein_miner.py --resume vein-results/veins.json --out resumed-results`。`--max-veins 2` 可将验收限制为两条已复查矿脉（包含恢复复查）。自然实测、分段时长、未完成原因和最终独立边界检查见 [测试记录](docs/TESTING.md) 与 [自然矿脉汇总](docs/natural-vein-summary.json)。矿块清空与掉落拾取分别记录，不能把 complete=true 当作掉落已全收集。
