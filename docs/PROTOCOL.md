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
| `mode` | string | `chat`（公屏触发）或 `agi`（`/agi` 私聊） |
| `context` | string | 仅 `chat` 模式：最近公屏聊天的若干条，按时间顺序换行拼接 |

### `context` 的取值范围

插件维护一个 200 条的环形缓冲，每收到一条公屏聊天就记一条（格式 `玩家名：内容`）。

触发时取 **上一次触发之后** 的消息，最多 20 条：

- 上次触发到现在不足 20 条 → 只发这一段
- 首次触发（没有"上次"）→ 取最近 20 条
- 缓冲里已滚掉的老消息不会补发

### 响应

两种模式都立即返回，不等模型：

```json
{"ok": true, "session": "mc-survive"}
```

`mode=agi` 时 `session` 是独立会话，形如 `mc-survive-dm`。

## 2. Agent → 插件

### 公屏说话（工具 `server_say`）

```
POST http://127.0.0.1:<port>/say
{"text": "大家好"}
```

### 私聊说话（工具 `mc_agi`）

```
POST http://127.0.0.1:<port>/reply
{"player": "Steve", "text": "只给你看的话"}
```

插件收到后：

1. 在 action bar 上逐字打出内容（打字机）
2. 打完，以 `[NEXAGI]=` 前缀发到聊天栏，**只有目标玩家可见**

### 执行指令（工具 `server_command`）

```
POST http://127.0.0.1:<port>/cmd
{"command": "time set day"}
```

安全模型（由插件判断，不信任调用方）：

1. 只认**最近一次**发起请求的玩家（公屏触发或 /agi，5 分钟内）
2. 该玩家必须是 OP（player.isOp()），否则拒绝
3. 指令第一个词命中 command-blacklist 就拒绝（默认 stop / op / deop / ban / kick / whitelist / reload / execute 等）
4. config.yml 的 `command-enabled: false` 可一键关闭整个功能
5. 执行时以**控制台身份**派发，控制台会留一行 `[NEX AGI 执行] /xxx （由 玩家 请求）`

拒绝时返回 `{"ok": false, "error": "..."}`，模型应当把原因如实转述给玩家。

### 健康检查

```
GET http://127.0.0.1:<port>/health
→ {"ok": true, "instance": "生存服", "port": 8900, "online": 8}
```

插件所有接口都只监听回环地址；配置了 `token` 时，请求需带 `X-Token` 头。

## 3. 后端与 Agent 之间

中转以 OpenAI 兼容格式调用 Agent，用会话头区分上下文：

- 公屏：`mc-<slug>`
- `/agi`：`mc-<slug>-dm`

每条消息都是"一次完整的用户消息"，不带 system 提示；行为约束写在工具的 `description` 里，外加消息末尾的一句提醒，例如：

```
[MC-生存服] 最近的公屏聊天（最多 20 条，按时间顺序）：
Alice：去挖矿吗
Bob：好啊
[玩家: Steve] [OP: 否] 现在他说：你怎么看
（这是游戏公屏：只有调用 server_say 工具，玩家才看得到你的话；直接输出正文等于没说话；实事求是，不知道就说不知道，不要编造）
```

`/agi` 则是：

```
[MC-生存服] [玩家: Steve] [OP: 否] 用 /agi 私聊你，说：在吗
（私聊场景：必须调用 mc_agi 工具，player 填 "Steve"，他才能看到你的回复；直接输出正文没人看得到；实事求是，不知道就说不知道，不要编造）
```

## 4. 端口注册表

`mc_mcp_server.py` 需要知道"哪个实例在哪个端口"，中转每次收到插件消息时刷新这个文件：

```json
{
  "生存服": {"port": 8900, "updated": "2026-10-19 13:55:49"},
  "_last": "生存服"
}
```

默认位置 `~/.nexagi/mc_ports.json`，可用 `NEXAGI_HOME` 改。
