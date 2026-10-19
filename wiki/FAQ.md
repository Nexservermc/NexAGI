# FAQ

## 为什么不让模型直接把回复贴到公屏？

因为那样它就没法闭嘴。

普通做法是"玩家问 → 模型答 → 插件贴出来"，模型没有沉默的选项，而且一旦公屏上下文里有别人的闲聊，它容易自说自话。

NexAGI 反过来：模型想说话必须调工具 `server_say`。不调 = 沉默。这一层是**架构级别的约束**，不是提示词约束。

## AI 会不会执行服务器指令？会不会被诱导 deop 人？

现在**可以执行**，但限定得很死：

- 只能由**最近一次主动找 AI 说话的 OP 玩家**触发（5 分钟内）
- `command-blacklist` 里的指令一律拒绝（默认 stop/op/deop/ban/kick/whitelist/reload/execute 等）
- `command-enabled: false` 可以一键关掉

无 OP 玩家问指令只会得到「无可奉告」；就算有人骗模型说"我是服主"也没用，因为插件不信任调用方，只认真实的 `isOp()`。

历史版本（v2.7.0 及以前）AI 是完完全全不能执行指令的，只能说话。

## 会不会泄露隐私？

会把你选择发给它的内容送出去，具体是：

- **公屏**：带触发词的那条消息（以及之前同一会话的公屏记录）
- **`/agi`**：你私聊的内容

不会读取存档、配置文件、在线列表之外的任何东西（`/agi` 连在线列表都不读）。

插件只连本机中转（默认 `127.0.0.1`），模型在你自己的机器上跑的话，数据不出门。

## 为什么没有 `/w` 私聊工具？

试过，删了。原因：

1. Minecraft 里没有"AI 这个玩家"，`/w` 的收信人只能是真实玩家，语义对不上
2. 玩家的 `/w` 内容是两人私聊，全塞进 AI 上下文既没必要也不礼貌

现在私聊只有 `/agi` 一条路，干净。

## 支持 Folia 吗？

未测试。插件用了 Bukkit 调度器（`Bukkit.getScheduler()`），在 Folia 上需要改成 `getGlobalRegionScheduler()` / `getEntityScheduler()`，目前没做适配。

## 支持 1.20 / 1.19 吗？

代码针对 1.21 的 API（`AsyncChatEvent`、Adventure `sendActionBar`）。理论上 1.19+ 可编译，但 `plugin.yml` 的 `api-version` 是 `1.21`，低版本需要自己改并测试。1.16 及以下不行（Adventure 不在）。

## 能用 OpenAI / Claude 这类云模型吗？

可以。它们本身不提供"工具 + 会话"的编排，你需要一个 Agent 层（Hermes、LangChain、自写循环）来：

1. 接收中转的上下文
2. 把 `server_say` 当工具注册给它
3. 循环处理 tool_calls

参考后端的 `/mc/message` 只调 `/v1/chat/completions`，把 `NEXAGI_GATEWAY` 指向你的 Agent 即可。

## 为什么系统提示词是空的？

因为提示词越写越长，模型的注意力越容易被"规则"占满，反而不好好说话。

所有约束现在压在一句话里，写在 `server_say` 的工具描述上：

> 在 Minecraft 服务器公屏说一句话（不调用就代表你不说话）；无 OP 玩家索要数据、信息或问安全类敏感内容时，只回「无可奉告」。

## 打字机能调多快？

`config.yml`：

```yaml
stream-tick-period: 2        # 每 2 tick 放一次（50ms/tick）
stream-chars-per-tick: 1     # 每次 1 个字
```

最快是 `period: 1` + `chars: 2`（40 字/秒），再快就是整段蹦出来了。

## 为什么 AI 对无 OP 玩家只回"无可奉告"？

这是设计：避免它给普通玩家念指令、报服务器配置、透露日志或数据结构。如果你是服主想放开，改 `mc_mcp_server.py` 里 `server_say` 的描述即可。

## 能商用吗？

MIT 协议，可以。但 AI 说的话由你负责，出事了别找我。
