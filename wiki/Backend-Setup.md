# 后端接入

插件只负责两件事：把消息推出去、把 `server_say` 的话收回来。中间那层（中转 + 工具）可以自己写。

## 参考后端

```
server/
  mc_ai_bridge.py     中转：插件 <-> Agent，NDJSON 流式
  mc_mcp_server.py    MCP 工具：server_say / mc_status
  .env.example
```

两个文件都只用标准库，不需要 `pip install`。

## 启动中转

```bash
cd server
cp .env.example .env       # 或直接设环境变量
python mc_ai_bridge.py
```

### 环境变量

| 变量 | 默认 | 说明 |
|---|---|---|
| `NEXAGI_GATEWAY` | `http://127.0.0.1:8642` | Agent 网关（OpenAI 兼容 `/v1/chat/completions`） |
| `NEXAGI_API_KEY` | 空 | 网关密钥 |
| `NEXAGI_MODEL` | `local-model` | 传给网关的 model 字段，多数网关会忽略它 |
| `NEXAGI_BRIDGE_PORT` | `8899` | 监听端口，要和插件 `bridge-url` 对上 |
| `NEXAGI_HOME` | `~/.nexagi` | 数据目录：`mc_ports.json`、`bridge.log` |
| `NEXAGI_SLUGS` | `{}` | 实例名 → 会话 slug 的 JSON，比如 `{"生存服":"survive"}` |

启动后自检：

```bash
curl http://127.0.0.1:8899/health
```

## 注册 MCP 工具

把 `mc_mcp_server.py` 交给你的 Agent。以 Hermes 为例，改 `~/.hermes/config.yaml`：

```yaml
mcp_servers:
  mc-chat:
    command: C:\Users\you\hermes\venv\Scripts\python.exe
    args:
      - C:\path\to\server\mc_mcp_server.py
```

重启网关后，工具列表里应该出现 `server_say`、`mc_status`。验证方式（不用模型）：

```bash
echo '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}' | python mc_mcp_server.py
```

## 接别的 Agent

只要满足两点就能用参考后端：

1. 提供 OpenAI 兼容的 `POST /v1/chat/completions`
2. 支持 MCP 工具（或能调 HTTP）

会话隔离靠请求头 `X-Hermes-Session-Id`。如果你的 Agent 用别的头（比如 `X-Session-Id`），改 `_request()` 里那行即可。

## 自己写后端

协议很短，见 [PROTOCOL.md](https://github.com/<you>/NexAGI/blob/main/docs/PROTOCOL.md)。最少要实现的：

| 需求 | 接口 |
|---|---|
| 收公屏消息 | `POST /mc/message`，`mode=chat`，返回 `{"ok":true}` |
| 收 `/agi` 消息 | 同上，`mode=direct`，返回 NDJSON 流（`chunk` / `done`） |
| 让 AI 说话 | 提供工具，由工具 `POST http://127.0.0.1:<port>/say` |

一个最小的 Node 后端骨架：

```js
import http from 'node:http';

http.createServer((req, res) => {
  let body = '';
  req.on('data', d => body += d);
  req.on('end', async () => {
    const m = JSON.parse(body || '{}');
    if (m.mode === 'direct') {
      res.writeHead(200, { 'Content-Type': 'application/x-ndjson' });
      res.write(JSON.stringify({ type: 'chunk', text: '收到' }) + '\n');
      res.write(JSON.stringify({ type: 'done', text: `收到：${m.text}` }) + '\n');
      res.end();
      return;
    }
    // chat 模式：丢给你的模型，模型若决定说话就 POST /say
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ ok: true }));
  });
}).listen(8899, '127.0.0.1');
```

## 两个工具的分工

| 工具 | 用途 | 落到插件哪个接口 |
|---|---|---|
| `server_say` | 公屏说话，全服可见 | `POST /say` |
| `mc_agi` | 私聊某个玩家，只有他可见 | `POST /reply` |
| `server_command` | 执行服务器指令（仅 OP 请求生效） | `POST /cmd` |

两者都要求模型**主动调用**：直接输出正文玩家看不到。工具描述里已经写了这点，中转还会在每条消息末尾补一句提醒，因为只靠描述时模型偶尔会忘记。

`/agi` 现在也是工具驱动（`mc_agi`），不再有"直接出正文"的特殊通道，所以也不需要再往请求里塞 `tools: []`。
