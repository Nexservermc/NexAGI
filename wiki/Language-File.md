# 语言文件（lang.yml）

路径：`plugins/NexAGI/lang.yml`。**所有玩家能看到的文字都在这里**，改完 `/nexagi reload` 生效。

- 支持 `&` 和 `§` 两种颜色码（`&6`、`&l` 等）
- `{xxx}` 是占位符，**花括号里的名字别改**
- 文件不会自动更新：插件升级新增的键，需要手动补进去

## 全部键

### 公屏

| 键 | 默认 | 说明 |
|---|---|---|
| `broadcast-format` | `&6[NEX AGI] &f` | AI 公屏说话时的前缀 |
| `console-format` | `[NEX AGI] {text}` | 控制台记录行，`{text}` = 内容 |

### `/agi` 私聊

| 键 | 默认 | 说明 |
|---|---|---|
| `private-format` | `&6=[NEXAGI]= &f` | 最终回答的前缀 |
| `echo-format` | `&7[你 -> NEX AGI] &f{text}` | 玩家发出提问后的回显，`{text}` = 提问内容 |
| `thinking-prefix` | `&9✦ ` | 文字前面的静态标记（默认亮蓝星号），支持颜色码 |
| `thinking-text` | `thinkink` | 中间的文字（点号在下一项配） |
| `thinking-dots` | 从 `""` 到 `"........."` 共 10 项 | 点号循环列表：0 个点 → 9 个点 |
| `thinking-dot-interval-ms` | `300` | 点号切换间隔（毫秒）；默认节奏下每 3 帧长一个点，一轮约 3 秒 |
| `thinking-colors` | `#FFFFFF` `#2E5BFF` `#00238C` `#FFFFFF` | 渐变色调（白 → 亮蓝 → 深而饱的蓝 → 白） |
| `thinking-frames` | 3 行 `✦/✧ thinkink. …` | **备用模式**：`thinking-text` 留空时才用，支持 `&` 颜色码 |
| `stream-format` | `&d✦ &7{text}` | 流式打字时的前缀，`{text}` = 已显示的片段 |
| `empty-answer` | `（我这会儿没想出要说什么）` | 模型返回空内容时的兜底 |
| `bridge-down` | `（连不上中转，我现在说不出话）` | 中转不可达时的兜底 |

### `/agi` 的 Tab 提示

| 键 | 默认 | 说明 |
|---|---|---|
| `tab-hint` | `<在此输入聊天内容>` | Tab 补全插入的占位文字；玩家原样发出时会被拦截 |
| `hint-only` | `&e在 /agi 后面直接写你想说的话…` | 拦截占位符后的提示 |

### 权限与错误

| 键 | 默认 |
|---|---|
| `player-only` | `&c这条指令只能在游戏里用` |
| `no-permission` | `&c你没有使用 NEX AGI 的权限` |
| `no-admin` | `&c没有权限` |

### `/nexagi`

| 键 | 默认 | 占位符 |
|---|---|---|
| `admin-usage` | `&e用法: /nexagi reload \| /nexagi say <内容>` | - |
| `reload-done` | `&a已重载：实例={instance} 端口={port} 触发词={trigger}` | `{instance}` `{port}` `{trigger}` |
| `reload-hint` | `&a私聊指令 /agi 权限节点 nexagi.ai…` | - |

## 示例：把等待动画换成别的

### 换色调（最简单）

```yaml
thinking-colors:
  - "#FFFFFF"
  - "#2E5BFF"
  - "#00238C"
  - "#FFFFFF"
```

颜色数量不限，两个就够；多了相当于把色带拆成多段。

> 插值是在 **HSV** 里做的：色相会锁在“最鲜艳那个色”的蓝，只揺饱和度和明度。
> 好处是中间不会经过灰白，颜色一直是饱的，所以即使你只写白和深蓝两个，也不会发闷。

### 换文字和点号

```yaml
thinking-text: "thinking"
thinking-dots:
  - ""
  - " ."
  - " .."
  - " ..."
thinking-dot-interval-ms: 200
```

点号列表可以任意长，第一项可以是空字符串（什么都不显示）。

### 关掉点号动画，只留光带

```yaml
thinking-dots: []
```

把 `thinking-dots` 设为空列表，文字就不会跳动，只剩颜色流动。

## 示例：把前缀改成纯文本

```yaml
broadcast-format: "&7[AI] "
private-format: "&7[AI 私聊] "
```

## 常见坑

- **颜色不生效**：用了 `#RRGGBB` 十六进制颜色——这个插件只认 `&`/`§` 传统色码。
- **符号没了**：`{text}` 写成了 `{Text}` 或删掉了花括号。
- **Tab 提示变空**：`tab-hint` 被删了，插件会退回默认值 `<在此输入聊天内容>`。
- **中文乱码**：文件必须存成 **UTF-8**（带不带 BOM 都行）。
