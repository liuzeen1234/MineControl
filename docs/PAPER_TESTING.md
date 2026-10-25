# Paper 26.2 本机自动挖矿测试

2026-10-24，Paper 26.2 build 134（官方 STABLE），Java 25。服务仅监听 127.0.0.1:25565；开发客户端使用离线身份。原 Fabric 服务已正常停止，原存档保留在 run/minecontrol-test-26.2；Paper 使用独立目录 run-paper-26.2 和复制后导入的世界。

## 已安装并确认启用

| 插件 | 版本 | 本次用途及限制 |
|---|---|---|
| GrimAC | 2.3.74 正式构建 | 物理和协议检测；不保证识别合法键鼠宏；本构建自带独立命名空间的 PacketEvents |
| IdleGuard | 0.1.0 Free | 基础活动/AFK 跟踪；官网将宏行为启发式标为 Pro，不能算作免费宏检测 |
| TruthfulAC | 0.9-FH1 | 开启各默认检测，包括 Baritone A/B/C 和 AutoClicker；关闭处罚与 lagback |
| PacketEvents | 2.14.0 | TruthfulAC 依赖 |
| HawkAC Lite | 1.2.0 | 7 项主动检测及 53 项被动数据模块；自动挖矿模块不能等同完整付费版告警 |
| ProtocolLib | 5.5.0-SNAPSHOT 官方开发附件 | 官方 changelog 明确加入 26.2 支持；5.4.0 正式包未声明 26.2 支持，未使用 |
| MineControlTestAudit | 1.0.0 本地源码 | 自建事件记录与可取消踢出事件拦截，不是 LogActions，也不是反作弊分类器 |

未找到需求所描述的 LogActions 官方项目，未安装同名未知插件。所有插件均免费。Paper/Modrinth 下载已校验发行方提供的 SHA256/SHA512；Hawk 从 Spigot 资源编号 139012 经 Spiget 获取，记录本地 SHA256，无发行方 checksum 交叉验证。下载清单在 run-paper-26.2/plugin-manifest.json。

## 只记录的配置

- Grim：保留检测阈值，punishments 仅执行 `[alert]` 和 `[log]`；取消配置中的回弹/命中拦截。通过 permissions.yml 给所有玩家 `grim.nomodifypacket`、`grim.nosetback`，未给 `grim.exempt` 或 `grim.disabled`。事务超时只能配置到 180 秒，无法宣称消除协议/连接层断开。
- Truthful：所有 check 的 punishment.enabled=false、command 为空、lagback=false、cancel=false；关闭 latency-kicker、client-blocker、banwave；debug-mode=true。保留检测开关和检测阈值。
- Hawk：kick、ban、cancel_event、suspicion-kick 全为 false；本测试玩家另启 silent watch。行为记录保留在本地，ai-telemetry=false、上传地址为空。
- IdleGuard 免费版没有单独的 kick 禁用选项，因此由测试拦截器取消其可取消的 PlayerKickEvent。没有将基础 AFK 跟踪描述为宏检测。
- 自建插件以 HIGHEST 优先级取消 PlayerKickEvent 并记录原因。已主动执行一次控制台 kick 测试，客户端保持在线，记录 kick_blocked。控制台仍打印命令的 Kicked 文本，不能以该文本判断玩家实际已下线。
- 拦截器不能阻止客户端主动退出、网络故障、服务器关闭或不经过可取消事件的底层断开；本配置保证的是反作弊处罚关闭，不承诺网络连接永不断开。

## 铁砧及生存挖矿实测

控制台只发放未附魔镐与两本附魔书。通过客户端实际铁砧界面先合并耐久 III，再合并效率 V；服务端 SelectedItem 确认两项附魔及 repair_cost=3。玩家非 OP，切到生存模式。测试场为本测试世界内生成的石头通道，使用普通 MineControl 输入持续 W+左键，每 0.7 秒在俯仰角 0/30 度间切换，80 次，共约 60.5 秒；未注入异常数据包，未为玩家添加反作弊豁免。

- 服务端记录 block_break=138、dig_start=155、dig_abort=17、swing=1137、interact=167、move=467。
- 玩家 Z 前进约 69.2 格；工具 damage=32；仍是生存模式。
- Truthful 数据库该时段无违规；Hawk profile 为 violations=none、layered score=0%。
- Grim 在挖矿时段没有新增告警。21:11:14 的 Timer 与 TimerLimit 各一次发生于挖矿前，未归因为挖矿。
- 玩家未被踢出，测试结束释放全部模拟输入，客户端留在服务器内。

**结论：本次重复挖矿没有被套件判定为宏/AI。安装并启用检测不等于具有可靠的 AI 识别能力；不能用本次结果宣称严格宏检测已经验证成功，也不能推广为所有自动化都不会触发。**

## 证据与重启

- docs/live-paper-mining-26.2-results.json：测试时间、位置、输入、事件统计。
- docs/paper-26.2-test-log.txt：本轮完整 Paper 日志快照；实时日志为 run-paper-26.2/logs/latest.log。
- run-paper-26.2/plugins/MineControlTestAudit/actions.jsonl：服务器可见的交互、挥手、挖掘、放置、背包及移动事件，含服务器 tick、UTC 时间与 monotonic nano。服务端事件不等于每个本机物理点击，也不是全量网络包抓取。
- run-paper-26.2/plugins/TruthfulAC/logs.db：Truthful 告警库。
- run-paper-26.2/plugins/HawkACLite/data/：本地行为 CSV。
- `sh run-paper-26.2/start.sh`：在本项目目录重启；启动前先正常停止已有服务器。
- `python3 tools/paper-test-support/build.py`：使用 Paper 下载的库重建本地记录插件，替换后需重启。

来源：Paper https://papermc.io/downloads/paper/ ；Grim https://modrinth.com/plugin/grimac ；IdleGuard https://modrinth.com/plugin/idleguard ；Truthful https://modrinth.com/plugin/truthfulac ；Hawk https://hawkac.pl/ ；ProtocolLib https://github.com/dmulloy2/ProtocolLib/releases 。
