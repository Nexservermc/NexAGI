# 常见问题排查

## 症状速查表

| 症状 | 最可能的原因 | 处理 |
|---|---|---|
| 日志「本地端口 8900 启动失败」 | 端口被占 | 换 `listen-port`，并删 `~/.nexagi/mc_ports.json` |
| 喊触发词完全没反应 | 中转没起 / 地址不对 | `curl http://127.0.0.1:8899/health` |
| 喊了，AI 不回 | AI 选择了沉默（这是设计） | 换个说法，或用 `/agi` 强制对话 |
| `/agi` 一直显示星星不结束 | 模型没开或很慢 | 检查模型服务；插件最多等 300 秒 |
| `/agi` 立刻出「连不上中转」 | 中转挂了 | 重启 `mc_ai_bridge.py` |
| 公屏出现两条一样的回复 | 触发词被两条链路各处理一次 | 检查是否同时装了其他 AI 插件 |
| 中文全变乱码 | 文件编码不是 UTF-8 | 另存为 UTF-8 |
| 热加载后端口没起来 | 旧实例还没释放端口 | 等 3-5 秒再 load，或重启服务器 |
| 打字变成一次性蹦出 | 上游没流式 | 正常，打字机仍在工作（只是缓冲一次到） |
| 回答内容怀疑漏到公屏 | 模型调了 `server_say` | 见下方「`/agi` 漏到公屏」 |

## `/agi` 的回答跑到公屏了

现象：玩家用 `/agi` 提问，结果公屏出现了 `[NEX AGI] …`。

原因：模型该调 `mc_agi`（私聊），却调了 `server_say`（公屏）。

排查：

1. 看 `~/.nexagi/bridge.log`，这条是不是 `mode=agi`
2. 看 `mc_say.log` 里有没有 `[server_say]` 记录（有就是调错工具了）
3. 看用户消息里是否正确写了 `必须调用 mc_agi 工具，player 填 "玩家名"`

处理：

- 把 `mc_agi` 的描述写得比 `server_say` 更贴合私聊场景（比如强调"只有他能看到"）
- 也可以在自己的 Agent 配置里，给 `/agi` 会话挂一个只暴露 `mc_agi` 的工具集

## 喊了触发词没反应

按顺序查：

1. **插件在跑吗**：`/plugins` 里有没有 NexAGI；`curl http://127.0.0.1:8900/health`
2. **消息收到了吗**：`plugins/NexAGI/config.yml` 里把 `debug: true`，看控制台有没有 `[上行]`
3. **中转收到了吗**：`~/.nexagi/bridge.log` 有没有「收到 [实例] …」
4. **AI 说话了吗**：日志里有「正文(不上屏)」说明它答了，但没调工具 → 属于正常沉默
5. **工具调成功了吗**：`~/.nexagi/mc_say.log` 有没有 `[server_say]` 记录

## 聊天插件冲突（TrChat / FreedomChat 等）

有些聊天接管插件会 cancel 掉 `AsyncChatEvent` 自己渲染。NexAGI 在 `MONITOR` 优先级监听，被 cancel 的事件同样能收到，所以一般不受影响。

如果发现一条消息触发两次，说明两个事件都 fired 且去重没兜住——去重窗口是 2.5 秒（`handleChat` 里的 `2500L`），调大即可。

## 权限不生效

- `/agi` 用的是 `nexagi.ai`，默认 `true`（所有人有）。如果你之前用 LuckPerms 显式设过 `false`，它会记住。
- 管理指令 `/nexagi` 用 `nexagi.admin`，默认 `op`，需要真 OP（`ops.json`），LuckPerms 的 `*` 不一定算。
- 重启后权限才刷新？不是，`hasPermission` 是实时的；但 LuckPerms 需要 `/lp reload`。

## 多实例串台

现象：小游戏服的消息出现在生存服的对话里。

原因：两个实例的 `config.yml` 里 `instance` 或 `listen-port` 相同。

处理：`instance` 必须唯一（决定会话），`listen-port` 必须唯一（决定回传）。两者都要改。

## 升级后新配置项不生效

插件不会覆盖已有的 `config.yml` / `lang.yml`。升级后对比仓库里的同名文件，把新增的键手工补进去。

## 想看插件到底收到了什么

临时打开 `debug: true`：

```
[上行] Steve op=false -> 在吗
[下行] 在呢
[私聊] -> Steve: 在的
```

配合中转日志（`~/.nexagi/bridge.log`）就能定位断在哪一段。
