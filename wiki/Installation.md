# 安装

三步：**编插件** → **起中转** → **接 Agent**。

## 0. 环境要求

| 组件 | 要求 |
|---|---|
| 服务端 | Paper 或其分支（Leaf / Purpur / Folia 未测），1.21+ |
| JDK | 21 或更高（服务端与编译都用它） |
| Gradle | 8.x（用 Gradle 构建时） |
| Python | 3.9+（参考后端，无第三方依赖） |
| Agent | 任何 OpenAI 兼容 `/v1/chat/completions` 且支持 MCP 工具的运行时 |

## 1. 编译插件

### 方式 A：Gradle（推荐）

```bash
cd plugin
./gradlew build          # 第一次会自己下 Gradle
# 产物：plugin/build/libs/NexAGI-2.1.0.jar
```

如果用的是 **Leaf 这类分支**，`paper-api` 可能对不上你的版本，把 `build.gradle` 里的依赖换成你服务器自带的 API jar：

```groovy
dependencies {
    compileOnly files('libs/leaf-api-1.21.11-R0.1-SNAPSHOT.jar')
}
```

> 服务端目录下 `libraries/` 里那个 `xxx-api-<版本>.jar` 就是。

### 方式 B：直接用 javac（不想装 Gradle）

```powershell
# 以实例目录为例，把 <服务端目录> 换成你的
$cp = (Get-ChildItem "<服务端目录>\libraries" -Recurse -Filter *.jar | % FullName) -join ';'
javac -encoding UTF-8 --release 21 -cp $cp -d out `
  plugin\src\main\java\cn\nex\agi\MiniJson.java `
  plugin\src\main\java\cn\nex\agi\NexAGI.java

# 打包（把 resources 里的三个 yml 一起塞进去）
jar --create --file NexAGI-2.1.0.jar -C out . -C plugin\src\main\resources .
```

注意：`javac` 的 `-cp` 太长会报「命令行过长」，改用 `@argsfile`，并且参数文件里的路径要用**正斜杠**。

## 2. 部署插件

```
plugins/
  NexAGI-2.1.0.jar
```

启动服务器，首次运行会生成：

```
plugins/NexAGI/config.yml   功能配置
plugins/NexAGI/lang.yml     全部文案
```

日志里应该看到：

```
[NexAGI] NexAGI 已启动：实例=生存服 端口=8900 中转=http://127.0.0.1:8899/mc/message
```

自检端口是否真的起来了：

```bash
curl http://127.0.0.1:8900/health
# {"ok":true,"instance":"生存服","port":8900,"online":3}
```

## 3. 起中转

```bash
cd server
cp .env.example .env      # 按需修改网关地址与 Key
python mc_ai_bridge.py
```

看到这行就对了：

```
=== NexAGI 中转启动 端口=8899 网关=http://127.0.0.1:8642 ===
```

## 4. 把工具注册给 Agent

把 `server/mc_mcp_server.py` 作为 MCP 服务注册进去（以 Hermes 为例）：

```yaml
mcp_servers:
  mc-chat:
    command: C:\path\to\python.exe
    args:
      - C:\path\to\server\mc_mcp_server.py
```

重启 Agent 网关，确认工具列表里有 `mc_say` 和 `mc_status`。

## 5. 验证

1. 游戏里喊一句 `罗希 在吗`，Agent 若选择回应，会出现一行 `[NEX AGI] …`
2. 游戏里打 `/agi 在吗`，应该先看到 action bar 跳星星，然后逐字出字，最后聊天栏来一条紫色私聊

> `/agi` 只在有模型在线时才有效；模型没开时它会一直等，直到超时显示兜底文案。

## 多实例部署

每个服务端实例：**同一个 jar**，但 `config.yml` 里 `instance` 与 `listen-port` 必须不同。

```yaml
# 生存服
instance: "生存服"
listen-port: 8900

# 小游戏服
instance: "小游戏"
listen-port: 8901
```

中转会自动记录 `实例 -> 端口`，`mc_say` 不指定 `instance` 时发给最近说话的那个。

## 升级

1. 停服
2. 替换 `plugins/NexAGI.jar`
3. **不要删** `plugins/NexAGI/`（配置与语言文件）
4. 新版本若新增语言键，需要手动补进 `lang.yml`（插件不会覆盖已有文件）
