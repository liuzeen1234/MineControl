# 测试记录

日期：2026-10-04。宿主 macOS，Java 17.0.20.1；独立 Fabric 开发客户端。构建配置：Loom 1.7.4、Gradle 8.8、Fabric Loader 0.15.11，1.20.4 使用 Yarn build.3 / Fabric API 0.97.1，1.20.1 使用 Yarn build.10 / Fabric API 0.92.2。

## 已验证

- 两个版本均构建成功，Mixin 在实际客户端正常加载，TCP 绑定 127.0.0.1:25580。
- 两个版本各通过 17 项菜单/协议检查：ping、分包、同连接连续请求、无效 JSON / 未知命令、未知键、非法 hold、等待界面超时、控件点击、等待成功、Escape、GUI 坐标鼠标点击、组合按住、up/reset、键鼠定时释放、右键 down/up、Unicode 替换及追加、禁用/失效 ID 拒绝、布尔 CyclingButtonWidget 幂等设置、嵌套标签页。
- 1.20.4 的独立创造模式超平坦世界通过 8 项游戏检查：W 移动与释放、hold 移动与到期、Shift+W 与 reset、Space 跳跃、视角增量、左右键释放、E 打开背包及 Escape、退出世界释放未完成输入。
- 实际失焦测试：W 按住 0.5 秒移动约 2.115 格，释放后稳定坐标漂移 0；hold W 8 tick 移动约 1.723 格；Space 跳跃高度采样增加约 1.166 格。
- 默认灵敏度 0.5 下 mouse move 30 -10：yaw 增加 4.5°，pitch 减少 1.5°，与定义公式一致。
- 1.20.1 隔离运行目录 run-1.20.1，用 maxHoldTicks=10 验证 down 自动释放；默认发布配置仍为 1200 tick。
- 1.20.1 在 GUI 缩放 1、2、3、4、Auto 下实际执行 goto 和鼠标点击返回设置页，尺寸分别为 1708×960、854×480、570×320、427×240、427×240；最后恢复 Auto。
- 最大按住时长（键盘与鼠标）、65536 字节以上请求拒绝、布尔 ID 拒绝、部分请求空闲约 15 秒后下一个客户端恢复均通过。
- 正常退出测试客户端后控制端口随之关闭，再启动另一版本成功绑定同一端口。
- Python CLI 单元测试 4 项通过：参数转换、逐字节分包/UTF-8、ID 不匹配、未完成响应。
- Java protocolTest 验证 UTF-8、CRLF、多行、精确字节上限、超长与截断请求、配置持久化与缺省值、无效配置及占用端口。由 Gradle check 自动执行。

结果数据：[1.20.4 菜单](live-smoke-1.20.4.json)、[1.20.1 菜单](live-smoke-1.20.1.json)、[1.20.4 世界](live-world-results.json)、[可靠性](live-reliability-results.json)、[GUI 缩放](live-gui-scales.json)。测试世界截图：[PNG](test-world-1.20.4.png)。

## 测试边界

1.20.1 已做实际启动及菜单/输入状态回归，完整世界移动回归在 1.20.4 上执行。GUI 首轮尺寸为 427×240。左右键已验证游戏回调和按住释放，未将特定方块的挖掘/放置结果作为自动断言。自定义 Mod 控件、其他 Mod 的 K 设置界面、只读文本框实例、远程多人服务器及电影镜头模式未进行实际回归。现有实现对普通文本框尊重 editable，未知控件可使用坐标点击。

开发客户端可能输出原版 Realms 离线认证失败和缺失音效警告；这些不影响本地测试世界和控制服务。未连接任何多人服务器。原版世界菜单会暂停单人服务器；测试移动时保持无界面状态。

## 重跑

```sh
./gradlew build
./gradlew build -Pmc=1.20.1
python3 -m unittest discover -s tests -v
./gradlew runClient
# 新的空白开发实例进入 TitleScreen 后：
python3 tests/live_smoke.py
# 用创建界面新建创造模式超平坦测试世界，进入后：
python3 tests/live_world.py
# 单独隔离 1.20.1 测试数据：
./gradlew runClient -Pmc=1.20.1 -PrunDir=run-1.20.1
# config 中设置 maxHoldTicks=10、requestTimeoutMs=15000 后重启：
python3 tests/live_reliability.py
```

live_smoke 会操作菜单并停留在创建世界界面；要求实例中没有已有世界。live_world 会移动玩家、跳跃、打开背包、截图并保存退出到标题界面。只在独立开发实例或世界副本运行。

## 默认测试版本（2026-10-25 起）

默认构建与开发客户端使用 Minecraft 26.2，启动 Gradle 使用 Java 25。
`./gradlew build` 和 `./gradlew runClient` 自动选择 26.2，客户端目录为 `run-26.2`。
各版本运行数据默认放在 `run-<版本>`，原 `run` 中的旧测试数据保留，不自动升级世界。
测试旧版时显式添加 `-Pmc=1.20.4` 或 `-Pmc=1.20.1`；`-PrunDir` 仍可覆盖目录。
上文历史验证记录保持其原有版本归属。

## 客户端完整观察与宏路径条件（2026-10-25）

- 1.20.1、1.20.4、26.2：离线 build 成功，protocolTest（包含 MacroTest）通过。新增覆盖带冒号/点号的字段、数组下标、缺失字段、exists、all/any/not、非法条件和超大输出拒绝。
- 启动包含最终改动的 26.2 客户端，在独立 `MineControl Observation Test` 世界实测通过：
  - 主手 diamond_pickaxe 的 custom_data.tool_type=miner、number=42L、damage=123；JSON 字段可读，SNBT 保留 long 类型。有效组件包含默认属性。
  - 副手 stick 及自定义数据；空手输出 air/count=0。
  - 准星普通方块 ID、命中面和 BlockState；箱子目标输出客户端方块实体 NBT（id=minecraft:chest）。
  - 准星命中 pig，返回 UUID、实体类型和含 Pos 的客户端 NBT。
  - 宏通过 all/not、组件路径和 target.kind 条件执行 F 交换主副手，macro.status=completed，观察确认主手变为 stick，随后恢复。
  - 抬头未命中时 target=null；暂停界面 active=false；未加载世界时双手/目标均为 null。
  - dimension 输出 minecraft:overworld。
- 旧版本次只验证构建及公共回归，未进行新版对应的完整世界内实测。多人服务器、第三方 Mod 自定义序列化和未同步字段尚未实测，不据此宣称服务端 NBT 完整可见。

## 玩家位置与 AI Helper v3 区域查询（2026-10-25）

- 1.20.1、1.20.4、26.2 离线 build 和 protocolTest 全部通过。RegionQueryTest 覆盖反向坐标规范化、负坐标跨区块边界、含端点体积、空气省略、命名空间、高度上界、未加载区块预检、非法/溢出参数及响应超限。
- 启动包含本次最终代码的 26.2 客户端，在独立 Observation Test 世界实测：position 返回精确坐标 (-4.5,100,1.5)、方块坐标 (-5,100,1)、维度；observe.player 一致。
- 放置 oak_log[axis=x] 于 (-5,110,4)，查询反向两角 (-4,110,4) 到 (-5,110,4)，返回 volume=2、block_count=1，v3 中保留绝对坐标与 axis=x，空气不输出。
- 未加载远端区块、y=320、超体积、非整数分别返回 CHUNK_NOT_LOADED、OUT_OF_HEIGHT、REGION_TOO_LARGE、INVALID_ARGUMENT；y=319 查询正常。无世界时 position/region.query 返回 NO_WORLD。
- 宏通过 observe.player.x 条件连续执行 position、region.query，状态 completed；CLI --text 输出与协议 blueprint 完全相同。
- 将实际客户端导出的蓝图交给现有 minecraft-AI-helper 的 BlueprintParser/BlueprintData 源码编译后的解析器，确认识别为 v3、无 origin、单个方块坐标 (-5,110,4)、oak_log 与 axis=x 正确。该解析器会规范化去掉原版 minecraft: 前缀。
- 区域导出仅兼容 v3 方块列表；未实现 items/sign_text，未验证第三方 Mod 联动或多人服务器。旧版本次只有构建与公共回归验证。

## 下界合金镐 10 分钟生存挖矿（2026-10-25）

测试脚本为 `cli/mining_benchmark.py`，会生成当前 tick 截止条件的宏，按真实时间停止，并用客户端 inventory 前后数量差统计收获。独立世界与固定矿道见 `docs/mining-10min-fixture.json`。测试使用和平难度、生存模式和未附魔下界合金镐；矿石分布由测试场固定，不代表自然矿洞收益。

预跑第一次因后台桌面鼠标输入引起视角偏转，在约 14.3 秒提前停止，18 个圆石不计正式成绩。新增 BackgroundMouseMixin，在后台控制、真实窗口未聚焦且没有 GUI 时清零原生鼠标视角增量，保留协议 mouse.move 与 GUI 控制。修复后连续 30 秒预跑完成：39 圆石、2 煤炭、41 耐久、位移约 20.63 格。正式测试前恢复前 94 格矿道、清理掉落、清空背包、重新发放全新镐并恢复起点。

新增 inventory 快照和 observe.player 的 health/food/alive/game_mode 已在 26.2 游戏内验证；1.20.1、1.20.4、26.2 构建和公共回归通过。旧版未进行完整世界内实测。

正式结果：完整运行 **600.003 秒**，由真实时间截止主动停止，无异常和提前保护退出。拾取净增 **943 件**：圆石 757、煤炭 10、粗铁 10、粗铜 35、粗金 10、红石粉 44、青金石 69、钻石 8。消耗镐耐久 826，剩余 1205/2031，净位移 413.2 格；结束时生存模式、生命 20、饥饿 20。结束后另等 2 秒拾取掉落，不计挖矿窗口。

证据：`docs/mining-10min/macro.json`（当次生成的宏）、`before.json`、`report.json`（逐秒位置/宏状态和完整前后背包）、`verification.json`（输入释放与报告校验）。全程采样 yaw=0，X=1.5，沿矿道直线推进。测试完成后移除本次强制加载的区块并保存退出世界。固定矿道含测试布置的矿石，和平难度无怪物/饥饿风险；不能把 8 钻石等结果推广为自然地形收益。
