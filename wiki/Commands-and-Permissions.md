# 指令与权限

## 玩家指令：`/agi`

直接私聊问 AI，不经公屏，独立上下文。

```
/agi <你想说的话>
```

别名：`/ai`、`/nxai`

行为：

1. 回显 `[你 -> NEX AGI] 你输入的内容`
2. action bar 开始跳点：`✦ thinkink` → `✦ thinkink.` → …（每 0.3 秒长一个点）
3. AI 调 `mc_agi` 工具后，内容逐字显示在 action bar
4. 全部打完，action bar 清空，完整回答以橙色 `=[NEXAGI]=` 前缀发到聊天栏——**只有提问的人能看到**

Tab 补全：输入 `/agi ` 按 Tab 会补出 `<在此输入聊天内容>`，把它替换成你的话即可。如果直接原样回车，插件会拦下并提示用法。

权限：`nexagi.ai`（默认所有玩家有）

## 管理指令：`/nexagi`

别名：`/nagi`、`/luoxic`

| 子指令 | 作用 |
|---|---|
| `/nexagi reload` | 重载 `config.yml` + `lang.yml`，并重启本地 HTTP 端口 |
| `/nexagi say <内容>` | 手动以 `[NEX AGI]` 身份公屏说一句，测试用 |
| `/nexagi thinktest` | 只给玩家用：播放 6 秒等待动画，不用等模型就能看效果 |

权限：`nexagi.admin`（默认 OP）

`/nexagi` 也支持 Tab 补全（`reload` / `say` / `thinktest`）。

## 权限节点

| 节点 | 默认 | 作用 |
|---|---|---|
| `nexagi.ai` | `true`（所有人） | 使用 `/agi` 私聊 |
| `nexagi.admin` | `op` | 使用 `/nexagi` 管理指令 |

只给某个组开放 `/agi`（LuckPerms）：

```
/lp group default permission set nexagi.ai false
/lp group vip permission set nexagi.ai true
```

取消所有人的 `/agi`，只留 OP：

```
/lp group default permission set nexagi.ai false
```

> 注意：`nexagi.ai` 只控制 `/agi` 与私聊回答。公屏唤醒由触发词决定，**不看权限**——任何玩家喊触发词都会进 AI 上下文（AI 可以选择不理）。

## 谁在说话

插件会把玩家的 `isOp()` 结果一起发给 AI，AI 依据 `[OP: 是]` / `[OP: 否]` 区别对待。这个判断只看 `ops.json`，不认 LuckPerms 的权限组。

如果你希望"有某个权限节点的人就算管理者"，改 `NexAGI.java` 里 `handleChat` / `buildBody` 的 `player.isOp()` 为：

```java
player.isOp() || player.hasPermission("your.node")
```
