# 中英文界面验证

2026-11-20，macOS arm64，Java 25，Minecraft 26.3 / Fabric Loader 0.19.5。

Mod 界面跟随游戏语言；简体中文使用中文资源，英语及尚无翻译的语言使用游戏提供的英文回退。宏文件名、宏自身输出和协议状态数据保持原样；管理器状态仅在界面展示时翻译。

`./gradlew build -Pmc=26.3 --offline` 完整通过；1.20.1、1.20.4、26.2 的 `compileJava --offline` 均通过。独立测试目录 `run-localization-26.3`、端口 25584，实际客户端通过游戏 LanguageManager 和资源重载完成英文→简体中文→原语言切换，21 项检查全部通过，结果见 [26.3-results.json](26.3-results.json)。覆盖宏管理界面、设置、添加文件、空路径校验、管理器状态、错误提示、HUD 文本、已创建组件的动态翻译，以及中文文件名/程序输出保留。

重跑：`./gradlew inputCaptureTestJar -Pmc=26.3`，仅将独立探针放入测试实例的 mods，创建 `config/localization-test.trigger` 后在主菜单等待。报告写入 `config/localization-test-report.json`，测试结束恢复原游戏语言。正式 Mod 不包含测试探针。
