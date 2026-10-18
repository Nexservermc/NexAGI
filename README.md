# NexAGI

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](https://www.gnu.org/licenses/gpl-3.0)
[![Minecraft](https://img.shields.io/badge/Minecraft-1.21%2B-green.svg)](https://www.papermc.io/)
[![Java](https://img.shields.io/badge/Java-21-orange.svg)]()
[![Python](https://img.shields.io/badge/Python-3.9%2B-3776AB.svg)]()
[![Platform](https://img.shields.io/badge/Platform-Paper%20%7C%20Leaf%20%7C%20Purpur-8A2BE2.svg)]()
[![Stars](https://img.shields.io/github/stars/Nexservermc/NexAGI?style=flat)](https://github.com/Nexservermc/NexAGI)

把 AI 接进 Minecraft 服务器聊天，但**只有 AI 主动调用工具时才说话**。

普通 AI 聊天插件是"玩家问一句、插件把模型正文贴到公屏"；NexAGI 反过来：模型想说什么，必须调 `mc_say` 这个工具，不调就代表它选择沉默。这样它可以选择不理人，也不会在没人叫它的时候插嘴。

另外提供 `/agi` 单聊：独立会话、不进公屏、回答逐字打在屏幕上。

---

## 特性

- **工具门控公屏**：公屏消息只有走 `mc_say` 才会出现，模型正文不会被直接广播
- **`/agi` 独立上下文**：每个实例一个独立会话，私聊问答不污染公屏聊天记录
- **打字机流式**：回答逐字显示在 action bar（物品栏上方），打完再整条落到聊天栏
- **呼吸式等待标签**：亮蓝星号 + 文字 + 点号每 0.3 秒长一个（0 → 9 个点循环，一轮 3 秒），底色是白到高饱和深蓝的光带缓缓扫过；`/nexagi thinktest` 可当场预览
- **权限分离**：`nexagi.ai` 控制玩家能否用 `/agi`，`nexagi.admin` 控制管理指令
- **文案全可配**：所有输出文字在 `lang.yml`，支持 `&` 颜色码；`/nexagi reload` 热重载
- **多实例**：一个插件一个实例一个端口，多个服务器互不干扰
- **极轻**：无第三方依赖，只用 Bukkit/Paper API + JDK 自带 `HttpServer`，jar 约 14 KB

## 工作方式

```
                        ┌──────────────────────────────┐
 玩家公屏 "罗希 xxx" ──▶ │  NexAGI 插件 (Bukkit)        │
                        │  监听 AsyncChatEvent         │
                        └──────────┬───────────────────┘
                                   │ POST /mc/message  {mode: chat}
                                   ▼
                        ┌──────────────────────────────┐
                        │  中转 (bridge, Python 参考实现)│
                        │  按实例分会话，转发给 Agent    │
                        └──────────┬───────────────────┘
                                   │ POST /v1/chat/completions
                                   ▼
                        ┌──────────────────────────────┐
                        │  Agent（Hermes / 任意实现）    │
                        │  想说话 -> 调用工具 mc_say     │
                        └──────────┬───────────────────┘
                                   │ POST 127.0.0.1:8900/say
                                   ▼
                        插件广播到游戏公屏
```

`/agi` 走另一条路：`mode=direct`，中转以 NDJSON 流式回传，插件边收边打字，收完只发给提问的玩家。

## 仓库结构

```
plugin/          Bukkit 插件源码（Java 21）
  build.gradle
  src/main/java/cn/nex/agi/
    NexAGI.java      主类：聊天监听、/agi 流式渲染、本地 HTTP 服务
    MiniJson.java    极简 JSON（无依赖）
  src/main/resources/
    plugin.yml
    config.yml       功能配置
    lang.yml         全部文案
server/          参考后端（Python，无第三方依赖）
  mc_ai_bridge.py    中转：插件 <-> Agent，NDJSON 流式
  mc_mcp_server.py   MCP 工具服务：mc_say / mc_status
  .env.example
docs/
  PROTOCOL.md        插件 <-> 中转的协议说明
wiki/            Wiki 源文件（多页面，可直接贴到 GitHub Wiki）
```

## 快速开始

1. 按 [wiki/Installation.md](wiki/Installation.md) 编译并把 `NexAGI.jar` 放进 `plugins/`
2. 起中转：`python server/mc_ai_bridge.py`（环境变量见 `server/.env.example`）
3. 把 `server/mc_mcp_server.py` 注册成你的 Agent 的 MCP 工具
4. 游戏里 `/agi 在吗`

## 环境要求

- 服务端：Paper 及其分支（Leaf / Purpur 等），1.21+
- 插件构建：JDK 21+，Gradle 8+
- 后端：Python 3.9+（参考实现无第三方依赖）
- Agent：任何接受 OpenAI 兼容 `/v1/chat/completions` 并支持 MCP 工具的运行时

## 隐私与安全

- 插件会把**公屏聊天中带触发词的消息**发给后端 Agent；`/agi` 的内容也会发给 Agent
- 后端默认只监听 `127.0.0.1`，插件本地端口同样只绑定回环地址
- `mc_say` 只能发文字，**不能执行任何服务器指令**，不存在"AI 被诱导执行 deop"的风险
- 但 AI 可能被提示注入，说错话、复述不该复述的内容；上线前请自行评估，并给它加上必要的拒绝规则

## 许可证

GPL-3.0，见 [LICENSE](LICENSE)。
