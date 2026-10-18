# 协议说明

插件与后端之间只有两种通信：**插件向上推送消息**、**Agent 向下发话**。全部走 HTTP，只绑定 `127.0.0.1`。

## 1. 插件 → 中转

```
POST http://<bridge>/mc/message
Content-Type: application/json
```

请求体：

| 字段 | 类型 | 说明 |
|---|---|---|
| `instance` | string | 实例名，如 `生存服`，用于分会话与路由 |
| `player` | string | 玩家名（`event.getPlayer().getName()`） |
| `uuid` | string | 玩家 UUID |
| `op` | bool | `player.isOp()`，由插件直接判定 |
| `text` | string | 消息正文（公屏已去掉触发词） |
| `port` | int | 该实例插件的本地端口，用于回传 |
| `mode` | string | `chat`（公屏）或 `direct`（`/agi` 私聊） |

### mode=chat 的响应

立即返回，不等模型：

```json
{"ok": true, "session": "mc-survive"}
```

### mode=direct 的响应

`Content-Type: application/x-ndjson`，一行一个 JSON，连接关闭表示结束：

```
{"type": "chunk", "text": "在"}
{"type": "chunk", "text": "的，"}
{"type": "done",  "text": "在的，我一直在看你们打游戏。"}
```

| type | 含义 |
|---|---|
| `chunk` | 一段增量文字，插件追加到缓冲、逐字显示 |
| `done` | 完整回答，插件在打字机追平后发到聊天栏 |
| `error` | 出错文本，插件直接按私聊回复显示 |

> 若上游不支持流式，后端可以只发一个 `chunk` + 一个 `done`，插件的打字机效果不受影响。

## 2. Agent → 插件

### 说话（`mc_say` 工具）

```
POST http://127.0.0.1:<port>/say
{"text": "大家好"}
```

### 健康检查

```
GET http://127.0.0.1:<port>/health
→ {"ok": true, "instance": "生存服", "port": 8900, "online": 8}
```

插件所有接口都只监听回环地址；配置了 `token` 时，请求需带 `X-Token` 头。

## 3. 后端与 Agent 之间

中转以 OpenAI 兼容格式调用 Agent，用 `X-Hermes-Session-Id` 之类的会话头区分上下文：

- 公屏：`mc-<slug>`，例如 `mc-survive`
- `/agi`：`mc-<slug>-dm`，独立会话

`/agi` 请求里会带：

```json
{"tools": [], "tool_choice": "none"}
```

不支持这两个字段的网关会返回 400，中转会自动去掉它们重试一次；同时消息正文里还有一句"直接输出、别调用工具"的明确要求作为兜底。

## 4. 端口注册表

`mc_mcp_server.py` 需要知道"哪个实例在哪个端口"，中转每次收到插件消息时刷新这个文件：

```json
{
  "生存服": {"port": 8900, "updated": "2026-10-18 16:21:21"},
  "_last": "生存服"
}
```

默认位置 `~/.nexagi/mc_ports.json`，可用 `NEXAGI_HOME` 改。
