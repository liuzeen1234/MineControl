# Minecraft 26.3 适配验证

日期：2026-11-19。环境：macOS arm64、Java 25.0.3、Gradle 9.5.1、Loom 1.17.21、Fabric Loader 0.19.5、Fabric API 0.161.0+26.3。

- `./gradlew build inputCaptureTestJar -Pmc=26.3 --offline` 完整通过；包含协议、宏进程/调度、SDK 和方块类型回归。
- `./gradlew compileJava -Pmc=26.2 --offline` 通过，原默认目标仍可编译。
- `python3 -m unittest discover -s tests -v`：5 项通过。
- 实际启动 26.3 开发客户端，使用独立目录 `run-26.3`、端口 25583，以及 26.2 测试世界的副本。原世界未修改。
- [菜单验证](menu-results.json)：11 项通过，包括控件点击、SDL Escape、GUI 坐标鼠标点击、Shift+W、reset、右键按下/释放、旧版按键名称别名、定时释放和宏管理界面。可在此客户端主菜单重跑 `python3 tests/live_smoke_26_3.py`。
- [输入隔离验证](input-capture-results.json)：31 项通过，包括 Java/JSON 宏启动、手动输入拦截、模拟键鼠/视角/文字输入通行、F8、F9、停止键改绑与回退、停止后恢复输入和鼠标捕获。探针在实际加载的世界内执行。
- 主 Mod jar 的嵌入 jar 仅为 `minecontrol/macro-runtime.jar`；独立宏和测试探针不进入正式 Mod。

启动命令：`./gradlew runClient -Pmc=26.3 -x downloadAssets --offline`。测试所需资源已齐全，跳过了剩余的其他语言包下载。首次解析依赖时存在间歇 TLS 失败；最终构建通过。开发账户的 Realms 认证错误不影响本地回归。

26.3 复用未变化的 26.2 现代 API 代码，覆盖 SDL 输入相关适配。CLI 命名键保持不变，`status.held_keys` / `held_buttons` 使用游戏原生 SDL 编号，例如 W=26、左 Shift=225、鼠标左键=1、右键=3。不得依赖旧版 GLFW 编号。

边界：没有对 26.3 的 Xaero 小地图联动、远程多人服务器、所有独立宏的完整游戏流程进行实测；已有 26.2 的相关记录不能视为 26.3 实测。
