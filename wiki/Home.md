# NexAGI Wiki

把 AI 接进 Minecraft 服务器聊天，**只有 AI 主动调用工具时才说话**；另外提供 `/agi` 单聊，独立上下文、逐字流式。

## 从这里开始

| 我想… | 看这里 |
|---|---|
| 装上并跑起来 | [[Installation]] |
| 改端口、触发词、打字速度 | [[Configuration]] |
| 查指令和权限节点 | [[Commands and Permissions]] |
| 改所有显示文字 | [[Language File]] |
| 搞懂它到底怎么跑的 | [[How It Works]] |
| 接自己的 Agent / 写自己的后端 | [[Backend Setup]] |
| 出问题了 | [[Troubleshooting]] |
| 其他问题 | [[FAQ]] |

## 一图看懂

```
玩家公屏 "罗希 在吗"  ──┐
                        ├─▶  插件  ──POST /mc/message──▶  中转  ──▶  Agent
玩家 /agi 在吗  ────────┘                                     │
                                                              │ 调用 mc_say 工具
                                                              ▼
                       插件公屏广播  ◀──POST /say────────  插件本地端口
```

- **公屏**：Agent 必须调 `mc_say` 才会出现，回复正文不显示
- **`/agi`**：独立会话，回答逐字打在 action bar，打完发到聊天栏，只有提问者可见

## 版本

当前稳定版：**v2.1.0**（见仓库 `RELEASE_NOTES.md`）

## 许可

MIT。拿去改、拿去商用都行，改坏了别找我。
