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
