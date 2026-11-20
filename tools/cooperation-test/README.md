# 三角色协作测试宏

三份 Java 17 独立可执行 jar，适用于 MineControl 的 1.20.1、1.20.4、26.2、26.3 版本。不会放进主 Mod、隐藏运行库或自动预装进正式实例。

```sh
python3 tools/cooperation-test/build.py
```

产物位于 `build/macros/cooperation/`：

| 文件 | 角色 | 职责 |
| --- | --- | --- |
| `cooperation-detector.jar` | 检测宏 | 验证只读权限，广播真实世界、按键和视角快照 |
| `cooperation-executor.jar` | 执行宏 | 接收调度指令，按住 Shift 并轻微改变视角 |
| `cooperation-scheduler.jar` | 调度宏 | 启动两种宏、等待检测反馈、停止/重启、暂停/恢复 |

手动将三份 jar 全部添加到同一实例的宏目录，进入**单独的测试世界**，选择启动调度宏即可。默认执行 `normal` 场景。检测宏和执行宏由调度宏启动，不需要逐个启动。三份 jar 均包含 SDK，可独立用 `java -jar 文件.jar 端口` 运行；调度宏运行目录需包含另外两份 jar 且已加入 Mod 宏目录。

在宏目录写入 `cooperation-scenario.txt` 可选择：

- `normal`：检查广播、角色权限、真实输入与视角、停止执行宏而保留检测宏、执行宏重启和生命周期事件。
- `pause`：检查系统暂停后工作宏停止、输入释放、禁止启动；恢复后重新协作。
- `cascade`：工作宏持有输入时让调度宏正常退出，由 Mod 自动停止其余宏；外部测试器检查所有进程退出、输入与隔离解除。

每组都有 60 秒上限，不会无限运行。不挖掘、放置、移动玩家或发送聊天指令。Shift 和视角会改变，仍应使用测试世界。

## 所有版本的真实客户端测试

```sh
python3 tools/cooperation-test/run.py
# 单版本；测试器会复制世界，不改源世界：
python3 tools/cooperation-test/run.py 26.3 --world /path/to/test-world
# 调试时继续使用已启动的对应测试客户端：
python3 tools/cooperation-test/run.py --attach 26.3
```

默认从此工作区已有的各版本测试世界复制；新工作区应使用 `--world` 明确指定测试世界。每次只启动一个版本，端口 25585，实例目录为 `run-cooperation-<版本>`。测试器明确复制三份宏并通过独立探针启动调度宏，属于手动、显式测试安装。探针通过 `probe.init.gradle` 构建，不修改正式 Mod 的打包配置。

报告见 `docs/cooperation-test/<版本>/`。调度宏输出每组断言和生命周期事件，外部 Python 测试器另外检查进程、按键和输入隔离的清理结果。失败时保留客户端供诊断；修正后可用 `--attach` 继续。正式客户端可使用停止全部宏快捷键终止测试。
