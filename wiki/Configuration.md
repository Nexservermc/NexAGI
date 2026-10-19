# 配置说明（config.yml）

路径：`plugins/NexAGI/config.yml`。改完执行 `/nexagi reload`，无需重启服务器。

```yaml
instance: "生存服"
trigger: "罗希"
bridge-url: "http://127.0.0.1:8899/mc/message"
listen-port: 8900
token: ""
cooldown-ms: 1500
stream-tick-period: 2
stream-chars-per-tick: 1
thinking-speed: 0.03
debug: false
```

## 字段表

| 字段 | 默认 | 说明 |
|---|---|---|
| `instance` | `生存服` | 实例名。会写进给 AI 的上下文，也是多实例路由的键。**同一台机器上每个实例必须不同** |
| `trigger` | `罗希` | 公屏触发词。玩家聊天里包含它才会唤醒 AI，可填多个吗？只能一个，但可以是任意字符串 |
| `bridge-url` | `http://127.0.0.1:8899/mc/message` | 中转地址。换了中转端口记得同步改 |
| `listen-port` | `8900` | 插件自己的本地 HTTP 端口，用于接收 `mc_say` 的回传。**每个实例必须不同**，被占用时插件会在日志报「本地端口启动失败」 |
| `token` | 空 | 回传接口的令牌。留空 = 不校验（接口只绑定 `127.0.0.1`）。填了之后，请求需带 `X-Token` 头 |
| `cooldown-ms` | `1500` | 同一玩家两次**公屏**呼叫的最小间隔，防刷屏。`/agi` 不受此限制 |
| `stream-tick-period` | `2` | 打字机节奏：每几 tick 放一次字。`2` = 每 100ms 一次 |
| `stream-chars-per-tick` | `1` | 每次放几个字。`1` = 逐字，`2` = 两字一跳 |
| `thinking-speed` | `0.03` | 等待动画的光带速度：每帧相位增量，一轮 ≈ `1 / 该值` 帧（帧 = `stream-tick-period`）。`0.03` ≈ 3.3 秒，越大越活跃 |
| `debug` | `false` | 打开后把收发内容写进控制台，排查用 |

> 等待动画的字符/文字/颜色在 `lang.yml`（`thinking-spinner` / `thinking-text` / `thinking-colors`），不在本文件。

## 打字速度怎么调

速度 = `stream-chars-per-tick × (1000 / (stream-tick-period × 50))` 字/秒

| 配置 | 效果 |
|---|---|
| `period=2, chars=1` | 10 字/秒，舒服的逐字感（默认） |
| `period=2, chars=2` | 20 字/秒，快 |
| `period=4, chars=1` | 5 字/秒，慢吞吞 |
| `period=1, chars=1` | 20 字/秒（上限，tick 是最小单位） |

另外有自适应加速：如果模型一次吐了很多，积压超过 40 字，每 tick 出字量会自动放大（约为积压量的 1/10），长回答不会念半分钟。

## 关于触发词的两个细节

- 触发词**区分大小写的还是不分**？不分。内部统一转小写比较。
- 触发词后面的标点会被丢掉：`罗希，你好` → `你好`。
- 只说触发词不带内容（比如就发一句「罗希」），会转成「（只喊了你的名字，没别的了）」给 AI。

## 端口冲突排查

```powershell
Get-NetTCPConnection -State Listen -LocalPort 8900
```

如果 8900 被别的程序占了，换 `listen-port` 并把中转那边的端口表清掉重记（文件在 `~/.nexagi/mc_ports.json`，删掉即可，插件下次说话会重新写入）。
