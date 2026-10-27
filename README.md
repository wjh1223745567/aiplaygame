# autogame · 问道游戏自动化（三层 MCP 架构）

视觉闭环自动化：**窗口截图 → OCR/模板/YOLO → 大模型决策 → 模拟鼠标键盘**。
运行时全部落在仓库内（JDK / Python 由脚本下载到 `.tools`、`mcpserver/.venv`），不改系统全局配置。

> ⚠️ 声明：仅用于个人学习与技术验证。游戏自动化违反游戏用户协议，可能导致封号，请使用测试账号并自担风险。

## 1. 目录结构

```
autogame/
├── host/                    # 宿主层（Spring Boot 3.3 / Java 21）+ AI 服务
│   ├── config.json                # AI 侧配置（llm 段，含密钥，已 gitignore）
│   ├── config.example.json        # 同上模板
│   └── src/main/java/com/autogame/host/
│       ├── HostApplication.java   # 入口：--action 一次性任务，否则常驻 AI 服务
│       ├── ActionRunner.java      # 命令行动作分发（含 chat）
│       ├── GameService.java       # 持有与 mcpserver 的唯一会话
│       ├── AiSettings.java        # 读 llm.* 配置（api_base / model / 轮数 / 附图）
│       ├── LlmClient.java         # OpenAI 兼容 /chat/completions
│       ├── AiChatService.java     # 对话循环：决策 -> 调 MCP 工具 -> 复述结果
│       ├── DecisionLog.java       # 模型判断日志（项目根 ai-decisions.log）
│       └── ChatApi.java           # AI 服务接口：/ai/chat|tools|frame|history|reset
├── mcpclient/               # MCP 客户端层（官方 MCP Java SDK）
│   ├── packaging/                 # Windows 客户端启动脚本与包内说明
│   └── src/main/java/com/autogame/client/
│       ├── McpClientFactory.java  # stdio 拉起 Python server + initialize 握手
│       ├── GameTools.java         # 少量类型化方法 + toolSpecs/invoke 泛型入口
│       ├── HostClient.java        # /ai/* 的 HTTP 门面（控制台与界面共用）
│       ├── ChatConsole.java       # 终端对话框（chat.jar 的主类）
│       ├── ClientConfig.java      # 客户端 IP/端口配置读写（client.properties）
│       └── ChatWindow.java        # 图形客户端（client.jar 的主类）
├── mcpserver/               # MCP 服务层（Python 3.13 / mcp SDK 2.x）
│   ├── config.json                # 游戏侧配置（窗口/截图/输入/OCR/模板/YOLO）
│   ├── config.example.json        # 同上模板
│   ├── game_mcp/
│   │   ├── server.py              # MCPServer("wendao-game") + 17 个 @tool（统一埋点）
│   │   ├── config.py              # 读 mcpserver/config.json，并合并 host/config.json 的 llm
│   │   ├── core/                  # 窗口 / 截图 / 输入 / 引擎 / 运行日志(log) / 判断日志(decision_log)
│   │   ├── vision/                # 多层融合(screen) / OCR / 模板匹配 / YOLO / LLM 决策
│   │   └── tools/                 # game.py(open_game/login/close_game) daily.py(师门任务/扫货)
│   ├── requirements.txt
│   └── smoke_test.py              # Python 侧 stdio 冒烟
├── scripts/
│   ├── bootstrap.sh         # 一键装 JDK + venv + 依赖 + 构建 Java
│   ├── env.sh               # source 后得到 JAVA_HOME / 三层目录变量
│   ├── run-host.sh          # 按需构建并运行 host（源文件比 jar 新就重构建）
│   ├── run-chat.sh          # 对话调试台（构建并运行 mcpclient/target/chat.jar）
│   ├── run-mcpserver.sh     # 以 Streamable HTTP 常驻 mcpserver（容器化 host 用）
│   ├── docker-host.sh       # 构建/启动 host 容器（基础镜像自愈 + 增量构建）
│   ├── build-client-win.sh  # 打 Windows 便携客户端（client.jar + JRE + bat -> zip）
│   └── crop_template.py     # 从 debug/ 截图裁 UI 模板图 -> assets/templates/<name>.png
├── assets/                  # 模板图、YOLO 权重（与 mcpserver 共用）
├── ai-decisions.log          # 运行时生成：大模型每次判断的日志（已 gitignore）
├── mcpserver.log             # 运行时生成：mcpserver 工具调用/动作/异常日志（已 gitignore）
├── host/Dockerfile           # host 镜像（只装 JRE + host.jar）
├── docker-compose.yml        # 编排：host 进容器，mcpserver 留在宿主机
└── pom.xml                  # 父 POM，聚合 mcpclient + host
```

调用链：`host --调用--> mcpclient --stdio--> mcpserver --截图/OCR/输入--> 游戏窗口`

## 2. 环境准备

```bash
bash scripts/bootstrap.sh      # 幂等：JDK21、CPython3.13、Python 依赖、mvn package
source scripts/env.sh          # 当前 shell 生效（JAVA_HOME 指向 .tools/jdk-21）
```

首次需在「系统设置 → 隐私与安全性」给终端授予**屏幕录制**与**辅助功能**权限。

## 3. 使用

```bash
bash scripts/run-host.sh ping            # {"result":"pong"}
bash scripts/run-host.sh windows         # 窗口列表（含 scale）
bash scripts/run-host.sh login          # 自动检测/启动游戏 -> 确认前置弹窗 -> 点「进入游戏」 -> 等主界面
bash scripts/run-host.sh status         # 游戏是否已打开（窗口可见/窗口隐藏/进程）
bash scripts/run-host.sh close          # 关游戏：正常退出 -> SIGTERM -> SIGKILL 逐级升级
bash scripts/run-host.sh close --force=true   # 直接强杀
bash scripts/run-host.sh main            # 回主界面：先关弹窗，再按 ESC 逐级退出
bash scripts/run-host.sh daily --goal=师门任务 --dry-run=true
bash scripts/run-host.sh serve           # 常驻 AI 服务，默认 18080
```

常驻模式只暴露 `/ai/*` 对话接口（见第 4 节），游戏动作统一走 `--action=...` 或对话。

Python 侧单独调试：

```bash
mcpserver/.venv/bin/python mcpserver/smoke_test.py ping
```

## 4. 对话模式（host 作 AI 服务）

host 同时承担 AI 服务：自然语言 → 模型决策 → 调 MCP 工具 → 复述结果；mcpclient 的
`ChatConsole` 只是终端对话框，本身不做任何决策，用来把整条工具链放到自然语言下调试。

```bash
bash scripts/run-host.sh serve     # 终端 A：起 AI 服务（默认 18080）
bash scripts/run-chat.sh           # 终端 B：进对话
```

直接用中文提问即可，每一步工具调用都会打印（工具名、参数、耗时、结果）：

```text
你> 游戏开了吗
  ↳ [1] game_status {} 812ms
     {"running":true,"visible":false,...}
AI> 游戏进程已在运行，但窗口不在前台，需要手动切到前台。
（1 次工具调用 / 2 轮模型请求 / 4.4s）
```

`:` 开头的本地命令不会发给模型：

| 命令 | 作用 |
| --- | --- |
| `:tools` | 列出 MCP 实时工具清单与必填参数 |
| `:history` | 查看当前会话的决策/工具历史 |
| `:reset` | 清空当前会话上下文 |
| `:session <id>` | 切换会话（上下文按 session 隔离） |
| `:vision [on\|off]` | 是否把截图附给模型（多模态） |
| `:help` / `:q` | 帮助 / 退出 |

单轮冒烟，以及不改代码直接一次性调用：

```bash
bash scripts/run-chat.sh --message="游戏开了吗" --vision=false
java -jar host/target/host.jar --action=chat --message="现在是什么界面" --vision=true --max-steps=4
```

相关配置在 `host/config.json` 的 `llm` 段：`chat_max_rounds`（单次对话最多几轮模型请求，默认
6）、`chat_vision`（是否把截图附给模型，默认 false，见下节）。对话均走 `/ai/chat`，工具清单由 MCP
`listTools()` 实时生成，Python 侧加工具后无需改 Java。

### 免费模型的限流与自动降级

智谱的免费视觉模型（`glm-4v-flash`）在高峰期会返回 `HTTP 429 code 1305「该模型当前访问量过大」`，
以前这会直接把整轮对话打成失败。现在按名单降级：

```json
"model": "glm-4v-flash",
"fallback_models": ["glm-4.1v-thinking-flash"]
```

- 逐个尝试，只对「换模型可能成功」的失败降级：`408/429/5xx`、超时、连接失败；`400/401` 直接报错。
- 主模型被限后冷却 120 秒，期间把备用模型排到前面，不再每轮先撞一次 429 白等几秒。
- `glm-4.1v-thinking-flash` 的返回是 `<think>…</think><answer><|begin_of_box|>{…}<|end_of_box|></answer>`，
  `LlmClient` 会先剥掉这层包装再交给上层解 JSON（普通模型的 ```json 代码块不受影响）。
- 实测：主模型正常 1.6s；主模型 429 时降级成功，整轮 5.7s。

两个候选**不一定要是视觉模型**：决策默认不发图（见下节），纯文本模型（如 `glm-4-flash`）也能用，
更快也更少被限流。host（Java）与 mcpserver（Python）读的是同一份名单，两边都会降级；
`ai-decisions.log` 里的 `model=` 记的是实际用上的那个，降级时一眼能看出来。

Python 侧额外一条：推理模型未闭合的 `<think>` 直接当本轮失败（而不是从思考里抽花括号当决策），
避免拿半截思考去点错按钮。

### 决策只发识别结果，不发截图

模型看到的不是截图，而是我方视觉流水线在**当前画面**上的识别结果——YOLO 区域分类 + OCR 四层融合
去重后的区域清单，每条带类型、文字、方位（相对整窗的九宫格）与像素尺寸：

```text
画面 788x619（每条末尾的方位按九宫格：上中=顶部居中，右下=右下角）
目标：推进当前任务

可点击项（只能从这些里选 id，不要发明新文字）：
[8] (按钮) 师门任务  @中右(655,208) 59x18
[9] (按钮) 与文殊天尊对话  @中右(677,232) 105x23

画面其他信息（只读，用于判断，不要选）：
实体: person  @下中(318,502) 40x86
信息: 15:45 云营洞 巡逻 刷道 9线  @上中(277,61) 536x71
```

- 好处：请求体从「十几万字符的 base64」降到「一千字的清单」，延迟更低、也更少触发限流；
  纯文本模型也能用；模型不可能「看图猜一个我点不到的坐标」——它只选 id，坐标由我方按区域回填。
- 代价：模型看不到画面，所以**方位和尺寸必须写全**，否则它分不清「顶部导航 / 中央弹窗 / 右下确认」，
  也分不清同名的两行文字。没文字的区域也不是全丢：`弹窗 @右下` 这种本身就有信息量，保留；
  空的 `信息` / `文本` 区域才是噪声。
- `chat_vision` 默认 `false`（Java 与 Python 两侧都是）。临时想让模型直接看图：`/ai/chat` 带
  `{"vision":true}`，或 CLI `--vision=true`，或 REPL `:vision on`；这时 `max_image_side` 才生效。
- 不发图时，host 会在用户消息末尾加一句提示，让模型需要看画面时自己调 `layout` 工具（返回的正是
  同一份区域清单）。

安全约定：**默认只读**——host 按这句话里有没有操作类措辞（打开 / 登录 / 帮我 / 点击 / 扫货…）
判定本轮是否只读；只读轮里模型一旦想调 `click_point` / `press_key` / `open_game` 等，会被
host 直接拦截并回一句“已拒绝 …”。同一「工具 + 参数」失败两次或重复三次也会中止并如实转述
错误，避免小模型反复撞墙。窗口不可见时只提示用户手动切到前台，不靠按键去切窗口。

### 4.1 模型判断日志

每次模型返回后，它的原始输出、解析出的判断（`thought` / `tool` / `args` / `done` / `reply`）、
工具执行耗时与结果、本轮最终回复，都会追加到项目根的 **`ai-decisions.log`**：

```bash
tail -f ai-decisions.log          # 跟着看模型每一步为什么这么判断
grep '拦截' ai-decisions.log      # 只看被只读护栏拦下的调用
grep 'ok=false' ai-decisions.log  # 只看失败的工具调用
```

一块日志对应一次用户提问（多轮工具调用记为 `#1 #2 …`）：

```text
────────────────────────────────────────────────────────────────────────
2026-10-23 10:20:31.402  source=host  session=cli  model=glm-4v-flash  vision=true  最多 6 轮
用户：游戏开了吗
#1  模型 812ms
  判断 tool=game_status  args={"tag":"chat"}  done=false
       thought=需要先确认游戏窗口是否在
  原始 {"thought":"需要先确认游戏窗口是否在","tool":"game_status",...}
  工具 132ms  ok=true
       结果 {"ok":true,"running":true,"window_visible":false,...}
结果 ok=true  rounds=1  用时 946ms
  回复：游戏进程在跑，但窗口不在前台，需要手动切到前台。
```

- `source=host` 是对话决策（Java 侧 `DecisionLog`），`source=mcpserver` 是游戏内大模型决策
  （Python 侧 `core/decision_log.py` 在 `Advisor.choose` 里埋点），两者写同一份文件，可对照看。
- 日志是旁路观察：写失败只打 WARN，不影响对话与游戏操作；超过 5MB 轮转为 `ai-decisions.log.1`。
- 日志已加入 `.gitignore`，换机器排查直接拷走即可。

### 4.2 发送给模型的请求体（prompt 原文）

判断跑偏时，第一手要看的是「到底发过去什么」。两侧都在发送前把请求体写进日志：

| 侧 | 落点 | 时机 |
|---|---|---|
| host（`LlmClient`） | 容器日志 `docker logs autogame-host` | 每次 HTTP 请求前 |
| mcpserver（`Advisor.choose`） | `mcpserver.log` | 每次决策前 |

```text
INFO  [vision.llm] 发送给模型（user 1167 字 / 候选 26 项 / 只读 2 项）:
画面 788x619（每条末尾的方位按九宫格：上中=顶部居中，右下=右下角）
目标：推进当前任务

可点击项（只能从这些里选 id，不要发明新文字）：
[8] (文本) 师门任务  @中右(655,208) 59x18
...
画面其他信息（只读，用于判断，不要选）：
实体: person  @下中(318,502) 40x86
```

两条长度约束，否则日志会自己把磁盘吃满：

- **截图只留占位**：`[截图已省略]`。开 `vision=true` 时一张图 base64 十几万字符，照打会一条日志上百 KB。
- **system 只打首次**：host 的 system 含全部工具描述（几千字），一轮多步对话里会重复 6 遍；内容哈希
  未变时只写 `--- system --- [同前一条，共 4523 字]`。mcpserver 的 `SYSTEM_PROMPT` 是固定模板，
  进程内首次打印。

再配上 `ai-decisions.log` 里的「原始」（模型返回原文），一次决策的输入与输出就都齐了。

### 4.3 mcpserver 运行日志

Python 侧的运行日志写在项目根的 **`mcpserver.log`**（与 `ai-decisions.log` 并列），
由 `game_mcp/core/log.py` 统一出口：**只写文件与 stderr，绝不写 stdout**——
stdio 传输下 stdout 就是 MCP 的 JSON-RPC 通道，任何 `print` 都会把协议流冲散。

```bash
tail -f mcpserver.log                 # 跟工具调用
grep '失败\|异常' mcpserver.log        # 只看出错
AUTOGAME_LOG_LEVEL=DEBUG ./scripts/run-host.sh chat   # 连截图/OCR/输入细节一起记
AUTOGAME_LOG_STDERR=INFO ./scripts/run-host.sh chat   # 同时把 INFO 回显到终端
```

每行格式固定为 `时间 级别 [模块] 内容`，一次工具调用至少一条 INFO（含耗时与参数）：

```text
2026-10-23 13:51:24.825 INFO  [server] MCP server 启动 pid=48213 python=3.13.17
2026-10-23 13:51:25.104 INFO  [config] 配置加载完成 .../mcpserver/config.json（llm=已合并）
2026-10-23 13:51:31.226 INFO  [server] 工具 ping 完成 0ms 参数={}
2026-10-23 13:52:02.918 INFO  [tools.game] open_game: 启动客户端 /Applications/问道.app
2026-10-23 13:52:09.441 WARN  [tools.game] open_game: 进程在跑但 90s 内未出现窗口
2026-10-23 13:53:10.507 ERROR [server] 工具 login 异常 120032ms 参数={"timeout":120.0}
2026-10-23 13:53:10.508 INFO  [server] MCP server 结束 pid=48213
```

- 工具成功记 INFO，`ok=false` 记 WARN，抛异常记 ERROR（带 traceback）；`_make_ctx` 找不到
  游戏窗口、截图回退 mss、权限缺失、登录弹窗逐个点掉等都会留痕。
- 未捕获异常（含子线程）由 `sys.excepthook` / `threading.excepthook` 记成 CRITICAL，
  stdio 下崩溃不再「终端什么也没有」。
- 进程生命周期首尾各一条：`MCP server 启动 ...` 与 `MCP server 结束 ...`。host 退出时会
  发 SIGTERM，服务端收到后先记 `收到 SIGTERM，正在退出` 再还原默认处理，因此即使被
  `Process terminated with code 143` 收尾，日志里也留得下这轮运行的边界。
- 级别：`AUTOGAME_LOG_LEVEL`（文件，默认 INFO）、`AUTOGAME_LOG_STDERR`（stderr，默认
  WARNING）；目录：`AUTOGAME_ROOT`。文件超过 5MB 轮转为 `mcpserver.log.1`。
- 日志已加入 `.gitignore`；写失败会自动降级为只出 stderr，不影响工具执行。

### 视觉识别结果（YOLO / OCR）

两层都把“这一帧到底识别到了什么”打在各自的出口（`vision/yolo.detect` 与 `vision/ocr.ocr_image`），
所以不管从哪个工具进来都能看到：

```text
INFO  [vision.yolo] YOLO[ui] 检出 5 个 (conf>=0.25 imgsz=640): Information_Display/info(0.71)@362,166 box=(82, 150, 642, 182) | Button/button(0.39)@531,506 box=(503, 483, 559, 529) | …
INFO  [vision.ocr] OCR 788x619 region=None min_conf=0.50 命中 49 条: '问道'(1.00)@393,14 | '18:10'(0.98)@94,39 | … | …另有 37 条
DEBUG [vision.screen] OCR 兜底层聚合 37 个 label: ['问道', '101', '18:10', …]
```

- 每条格式：YOLO `类别名/内部kind(置信度)@中心点 box=框`，OCR `'文本'(置信度)@中心点`。
- **分级**：INFO 只给前 12 条（行别太长）+ 总数；`AUTOGAME_LOG_LEVEL=DEBUG` 给全量，
  框内小区域 OCR（`region=…`）也只在 DEBUG 下打。
- 无检出时只记 DEBUG，不刷 INFO（安静场景每帧一行“没结果”纯属噪声）。
- 识别结果靠这些日志就能定位「为什么没框到按钮」「OCR 把字认错了」：先看 `conf>=` 与
  `imgsz=`，再看单框置信度，最后看框内 `region=…` 那条 ocr 日志。

## 5. Docker 部署（host 进容器，mcpserver 留在宿主机）

`mcpserver` 要操作 macOS 本机窗口（pyobjc/Quartz + pynput + mss），容器里既没有 WindowServer
也没有辅助功能权限，塞进去只是个空壳；所以只把 `host` 容器化，它经 `host.docker.internal`
连宿主机上常驻的 `mcpserver`：

```bash
bash scripts/run-mcpserver.sh     # 终端 A：mcpserver 常驻 http://0.0.0.0:18090/mcp
bash scripts/docker-host.sh       # 终端 B：构建并启动 host 容器，对外 18080（等到 healthy）
bash scripts/docker-host.sh logs  # 出现「mcpserver 已就绪」即握手成功
bash scripts/docker-host.sh restart  # 改了 host/config.json（换模型、换 key）后必须重启
curl -s localhost:18080/ai/tools  # 应返回 count=17（17 个工具）
curl -s localhost:18080/ai/frame -o /tmp/frame.jpg  # 当前画面快照（给眼睛看，不给模型）
```

`host/config.json` 是挂载进容器的（`./:/app`），进程只在启动时读一次：只跑 `up` 不会重启
容器（compose 认为镜像没变，容器保持 Running），配置不会生效。改配置请用 `restart`。

### 为什么以前构建很慢，现在怎么保证快

`docker compose build` 「很慢」几乎都卡在同一处：基础镜像 `eclipse-temurin:21-jre` 不在本地时，
BuildKit 要先连 Docker Hub 解析 metadata 再拉 341MB，而国内直连通常不通——实测这一跳会重试
**259 秒**才失败，真正的构建只要 0.4 秒。三件事已经固化下来：

- `scripts/docker-host.sh` 先把基础镜像备到本地（本地 → 离线 tar → 三个国内镜像源，逐个尝试），
  再交给 compose；镜像被清掉时会自动恢复，不用手工 pull
- `bash scripts/docker-host.sh save-base` 把基础镜像导出到 `.tools/docker-base-jre21.tar`
  （已 gitignore，352MB），Docker 被 prune 后也能离线恢复（实测 2.6 秒）
- 构建上下文收到 `host/` 一个目录（`context: ./host`），不再把 1.8G 仓库交给 daemon
  扫描，`host/config.json` 里的密钥也不会进上下文

实测（基础镜像就绪后）：`build` 0.4s，`up` 含启动与健康检查 6.8s。

| 变量 | 作用 | 默认 |
| --- | --- | --- |
| `AUTOGAME_MCP_URL` | 容器内连 mcpserver 的完整端点 | `http://host.docker.internal:18090/mcp` |
| `AUTOGAME_MCP_TRANSPORT` / `_HOST` / `_PORT` / `_PATH` | mcpserver 的传输方式、监听地址与路径 | `streamable-http` / `0.0.0.0` / `18090` / `/mcp` |
| `AUTOGAME_MCP_ALLOWED_HOSTS` | mcpserver 的 DNS rebinding 白名单，容器访问须含 `host.docker.internal:*` | 已含该项 |
| `AUTOGAME_ROOT` | 容器内找配置/写日志/读截图的根目录 | `/app`（compose 挂载整个仓库） |
| `LLM_API_KEY` | 覆盖 `host/config.json` 里的密钥 | 空（用配置文件里的） |
| `HOST_BASE_IMAGE` | 构建 host 镜像的基础镜像 | `eclipse-temurin:21-jre` |
| `DOCKER_PULL_TIMEOUT` | 拉基础镜像时单个源的超时（秒，仅脚本层生效） | `240` |

Docker Hub 直连在国内经常不通，表现为 `docker compose build` 长时间无输出（daemon 卡在连
`registry-1.docker.io` 上重试）。`scripts/docker-host.sh` 已内置镜像源回退；如果三个源都不行，
手工换源再构建：

```bash
docker pull docker.1ms.run/library/eclipse-temurin:21-jre
docker tag docker.1ms.run/library/eclipse-temurin:21-jre eclipse-temurin:21-jre
# 或者不改 tag，构建时直接指定基础镜像：
HOST_BASE_IMAGE=docker.1ms.run/library/eclipse-temurin:21-jre docker compose build
```

## 6. Windows 图形客户端（免装 Java）

给「Windows 上发话、macOS 上跑游戏」用：客户端只负责输入与展示，模型决策在 host，截图与
键鼠操作在 mcpserver。打包产物自带 JRE 21，目标机解压即用。

```bash
bash scripts/build-client-win.sh                                              # 默认预置 192.168.0.31:18080
CLIENT_HOST=192.168.0.50 CLIENT_PORT=18080 bash scripts/build-client-win.sh   # 换地址再打包
```

产物：`dist/autogame-client-win-x64.zip`（约 53M，解压约 151M；已 gitignore）。

```text
autogame-client-win-x64/
├── start-client.bat   启动器（用包内 runtime\bin\javaw.exe，无黑色控制台窗口）
├── client.jar         客户端本体（shade 已含 Jackson 等全部依赖）
├── runtime/           Adoptium JRE 21 windows-x64
├── client.properties  预置的服务地址
└── README.txt         包内使用与排查说明
```

用法：解压 → 双击 `start-client.bat` → 顶部「服务 IP / 端口」填 host 的地址点「连接」
（成功后状态栏显示「已连接 … 工具 17 个」）→ 输入框打字，按「发送」或 `Ctrl+Enter`。
上方是对话，下方是这一轮 AI 调用了哪些工具、参数、耗时、返回，右侧是当前画面。

- 地址填一次就存进同目录 `client.properties`，下次自动带出；也可直接把
  `http://192.168.0.31:18080` 粘进「服务 IP」，端口会自动拆出来。
- host 跑在容器里时，Windows 连的是**宿主机 IP**（如 `192.168.0.31:18080`），不是容器 IP。
- 无界面自检（排障用，在本目录的 cmd 里执行），会打印生效地址、读到的配置文件位置、
  工具数量，连不上时给出具体异常：

```bat
runtime\bin\java.exe -jar client.jar --selftest --base=http://192.168.0.31:18080
```

命令行参数：`--host` / `--port` / `--base` / `--session` / `--vision`。

### 右侧「当前画面」

不按秒轮询（每秒抓帧会把宿主机的取图拖满）：平时想看就点「看当前画面」；
只有本轮以「绕圈/卡住」收场时客户端才自动补一帧，标题下带上标签，
如「绕圈 · 10:41:02 788×619 · 99KB」。它解决的是「等太久，像卡住了但不知道卡在哪一步」。

- 链路是 `client -> host /ai/frame -> mcpserver /frame.jpg`，**不经过** `GameService` 的
  `synchronized`，也不抢 mcpserver 的写锁，所以正在跑的那一步不被迫排队；
- 抓帧**不落盘**（`debug/` 只写失败样本），也**不进工具清单**：约定一直是只把识别清单发给
  模型、不发截图，这块面板同理只给人看；
- 抓帧失败（游戏没开、窗口不在、stdio 模式没有该端点）时，标题下那行小字会给出原因，
  自动那一帧不会往对话区刷屏；手动点才会把错误写进对话区。
- 「绕圈/卡住」的判定在客户端侧：step 的返回里带「绕圈」（mcpserver 的画面指纹），
  或本轮 `ok=false` 且收尾文案含「疑似卡住 / 没有产生任何动作 / 同一操作重复 / 轮数已达上限」（host 的硬限制）。
- `--selftest` 会额外打印一帧的大小（KB），用来快速判断宿主机侧的截图权限与窗口状态。

macOS 侧仍可用终端版对话框：`bash scripts/run-chat.sh`（`chat.jar`，同样支持 `--base`）。

## 7. 说明与约定

- **工具清单（17）**：`ping`、`list_windows`、`capture`、`ocr`、`layout`、`detect`、`click_point`、
  `click_text`、`press_key`、`advise`、`open_game`、`close_game`、`game_status`、`login`、
  `back_to_main`、`daily_task`、`market_scan`。
- **画面快照只有人看**：`mcpserver` 的 `GET /frame.jpg` 与 `host` 的 `GET /ai/frame` 专供客户端
  右侧面板，不是工具，不会出现在工具清单里，也不会发给模型；抓帧不抢写锁、不落盘。
- **工具描述就是提示词**：每个工具的使用场景、触发说法、是否真实操作、返回字段都写在
  `mcpserver/game_mcp/server.py` 的 docstring 里（MCP 会把它当 description 透给模型）；
  host 的 `SYSTEM_PROMPT` 只保留对话协议（JSON 格式、tool/reply 二选一、失败两次停手），
  不再重复工具细节。新增工具时只需在 Python 侧写清描述，Java 不用同步。
- **多层视觉流水线**（`vision/screen.py`，模板优先）：

  ```
  截图 ─┬─ template 层：已知图标/固定元素 → Region(kind, source=template, 精确中心)
        ├─ yolo 层   ：UITag 分类控件 button/task/info/dialog/icon → 逐框 OCR 读区内文字
        ├─ general 层：通用检测找游戏实体（人物/NPC）→ Region(kind=entity, source=general)
        ├─ option 层 ：顶层面板内的彩色文本行 → Region(kind=button, source=option)（对话框选项没底框）
        ├─ ocr 层    ：没被任何区域覆盖的文字 → 按「同行且水平相邻」聚成 label 兜底
        └─ 融合去重（template > yolo > general > option > ocr，IoU ≥ 0.5 丢弃重叠）→ Region 列表 → 可点/只读分流
  ```

  两级检测共用一套去重规则（见 `detect_layers`）：先跑 UITag，再跑通用模型；
  与 UITag 框 `IoU ≥ 0.5`、或中心点落在可点区域里（头像按钮之类）的实体框直接丢弃，
  其余作为 `kind=entity` 只读锚点——**不认领框内文字**，所以血条旁的怪物名照样进 `label` 层。

  只有 `vision.clickable_kinds`（现阶段 `button` / `icon`）且来源在 `vision.clickable_sources`（默认
  `["template", "yolo", "option"]`）里的区域会带编号 id 交给模型选，其余区域只作为「类型: 内容」的只读信息供判断
  ——所以模型不可能把正文里出现的「确定」二字当按钮点掉。label 兜底层的文本行不作候选：看得见字不等于能点，
  任务追踪的步骤描述、聊天正文都长得像按钮；icon 算可点，因为 HUD 上的背包/好友本来就是图标控件。
  没有兜底提升：可点为空就如实报空（模型返回空 `choose` 等下一帧），而不是拿文本蒙一个坐标。
  **顶层面板门控**（`vision.gate_by_top_layer`，默认开）：弹窗打开时客户端会先盖一层半透黑遮罩再画面板，
  遮罩下的东西根本点不动，所以也不算候选。分层靠亮度双峰判：弹窗帧的网格亮度 p50=26 / p75=193（差 7 倍），
  主场景只有 48 / 75（没分层）；取两簇几何中点当阈值，再按投影取连片矩形（投影能排掉窗口标题栏那种横贯
  一条的亮带）。矩形之外的按钮降为只读上下文，且类型名写成「按钮（遮罩下·点不动）」——只把它挪进只读区不够，
  类型名还挂着「按钮」时模型照样想选它。实测不门控时，对话框打开后模板仍以 0.99 命中遮罩下的 战斗教学/好友/实时，
  模型对着背景连点 77 步画面纹丝不动。
  **面板内彩色选项**（`vision.option_by_color`，默认开）：问道对话框的选项没有底框，能点的画彩色、纯说明的画白色，
  所以靠字色分。判据是框内字身像元的饱和占比：实测同一帧选项 0.58-0.64、正文 0.12-0.13、NPC 名 0.22（差 4~5 倍），
  阈值取 0.45；行高、宽高比都分不开（选项行和正文行一样高）。只在检到顶层面板时启用，面板外的彩色字（聊天频道、
  任务追踪）一概不碰；已被模板/YOLO 认领的行不重复进候选；点击点取行中心。不做这件事，对话框里除了「离开」一个都
  点不到——「【师门】师门任务」这类选项 UITag 只给一个 Visual_Elements/icon 大框，按容器规则被降级成 info，
  等于没有可点信号。「大框当容器」有两条判据：面积超过整图 30%，**或者**框内读出 ≥ 3 行互不相干的文字。
  后一条是补出来的：那块 icon 框面积在 31%~40% 之间飘（同一个 NPC 面板，日志里 36%、重跑 33.8%），
  刚好掉到面积阈值下面就把 NPC 名 + 三条任务行合成一个候选，模型只能盲选、点了落到 NPC 名字上；
  面积会飘而「按钮只有一个标签、面板才有成片文字」不会飘，所以两条都要。长期正道还是微调按钮权重（见下节），到时候把这个开关关掉回到「只认检测层」。
  排查融合效果用 `layout` 工具，直接返回当前画面的 `regions[{kind,source,text,box,cx,cy}]`。
- **模板清单 + YOLO 区域分类**：模板不再由代码逐个查，而是 `template.items` 一张表
  （`{"file": "close_x.png", "kind": "button"}`），放图 + 加一行配置即生效，缺文件静默跳过；
  `scripts/crop_template.py --box x1,y1,x2,y2 --name close_x` 从 `debug/` 截图裁出模板并
  直接打印该往配置里加的那一行。模板项不单独声明「是否可点」，统一由 `kind` 决定。
  主页面已标定 10 个按钮模板（`btn_*`）：好友 / 背包 / 展开 / 实时 / 道具商城 / 外卖商城 /
  战斗教学 / 组队 / 接任务 / 师门任务——这些都是固定文案的功能按钮，裁的是文字本身
  （背景只留 2px），换地图背景变了也照样命中。任务追踪的行（「与文殊天尊对话」这类）
  文案随任务变化，不能做模板。
  YOLO 只做区域分类、不区分「保存还是取消」（语义由区内 OCR 给出），推荐权重
  `assets/models/yolo-ui.pt`（YOLO11s 在 GroundCUA 上微调，桌面截图 9 类，含 Button）。
  取权重不必走 HF（仓库 gated、要 token）：它随 `uitag` PyPI 包一起分发，
  `uv pip install --no-deps --target /tmp/u uitag` 后把 `uitag/models/yolo-ui.pt` 拷过来即可，
  不用装它那一堆 mlx-vlm / transformers 依赖。文件名就叫 `yolo-ui.pt`，别按仓库名重命名；
  权重是 640×640 tile 训练的，`yolo.imgsz` 必须留 640（给 1280 反而掉召回，实测最高 conf 0.71 → 0.31）。
  模型类别名（`Button` / `Icon` / `Text` …）与内部 kind 不是一回事，检测后先查
  `yolo.class_map` 再查内置 `CLASS_MAP` 归一化成 button/icon/label/dialog/info/task；
  未收录的类别降成 `info`（只读锚点，不会被点）并且只 warning 一次——按提示补一行
  `"class_map": {"Checkbox": "button"}` 就能改正，换模型不用改代码。
  `yolo.imgsz` 默认 640：UI 层权重是在 640×640 tile 上训练的，推理用 640 才匹配
  训练分布（给 1280 反而掉召回：同一张截图最高 conf 0.71 → 0.31，且慢一倍多）。
  权重缺失时该层直接禁用并记 `_load_error`、**不回退 COCO**
  （COCO 的 person/car 当 UI 用只会坑下游），除非显式设 `yolo.allow_pretrained_fallback=true`。
  面积超过整图 30%、或框内读出 ≥ 3 行独立文字的框一律当容器：不参与可点、也不认领框内文字，
  避免「整屏一个窗口框」把 OCR 全吃进 `info`、使 `label` 兜底层失效导致流程空转；
  后一条阈值不能只靠面积——UITag 的容器框面积在 31%~40% 之间飘，稳住的那一半是「一个按钮只有一个标签」。
- **通用检测层**（`general` 段，默认开）：拿 `assets/models/yolo11s.pt`（COCO 80 类）
  找游戏实体，`classes: ["person"]` 是白名单（COCO 的 tv/car 对游戏没用，不筛只会增加噪声），
  `default_kind: "entity"` 决定未收录类别名归成 `entity`（只读锚点）。
  它和 UITag 是两个独立 `YoloDetector`：任一层权重缺失只影响自己，不会拖垮另一层；
  `detect` 工具用 `layer=all|ui|general` 可分别查看两层结果。
- **按钮权重微调（失败案例驱动）**：模板管固定 HUD，动态界面（商城/背包/对话框/文案变化的
  列表行）靠模型补；现用的 UITag 权重在本游戏上召回低（实测主页面 0 个 Button），所以留了一条
  自训练通道——「可点候选为空」的画面就是当前识别链的盲区，自动存下来当训练数据。
  1. **采集（自动）**：`daily_task` 单步里 `build_candidates` 返回空 → 画面存入
     `debug/failed_shots/<时间戳>_<指纹8位>.png`，并追加 `index.jsonl`（ts/goal/区域数/
     文字数/screen_key/路径）。去重用图像感知哈希：8x8 均值哈希 + 汉明距离 ≤ 12 算同一画面（实测同画面
     帧间距离 0-6、不同画面 13-24），同一画面只存一张；不用区域文本集合当键——聊天频道一直在滚，
     同一弹窗相邻 4 帧的文本 Jaccard 只有 0.50，拿它当键会把同一画面当成新画面反复存；
     上限 `vision.max_failed_shots`（默认 300，到顶 warning 一次），开关
     `vision.collect_failed_shots`。收集是旁路：任何失败只 warning，不影响决策主流程；
     手动 `advise` 不收集（只收自动连续任务的失败画面）。
  2. **预标注**：`python3 scripts/label_helper.py` —— 离线重跑四层融合，`source=template` 的
     button 框直接写成 YOLO label，其余区域汇成 `datasets/ui/proposals.json` 待筛
     （含 kind/text/box/方位）。筛掉聊天正文/玩家名/步骤描述、补图标按钮后，再用
     X-AnyLabeling 打开 `datasets/ui/images` 抽查修正（建议至少 20 张再开训）。
  3. **训练**：`python3 scripts/train_button_yolo.py` —— 80/20 确定性切分（每 5 张抽 1，
     重跑不换验证集），COCO 预训练 yolo11n 起步、CPU 约 2~3 小时（MPS 在本机不可用）；
     训完自动 `val`，达标线 button 召回 > 80%，best.pt 拷到 `assets/models/yolo-game-ui.pt`。
  4. **接入**：`yolo.model_path` 改成 `assets/models/yolo-game-ui.pt` 后重启 mcpserver；
     回退就是改回 `yolo-ui.pt`（原文件保留），真机用 `layout` 看 `source=yolo kind=button` 数量。
  数据集 `datasets/ui/`：`images/` 与 `runs/` 已 gitignore（可由 failed_shots 重建），
  `data.yaml` / `labels/` / `proposals.json` 入库保住筛选成果。
- **单步推进，循环在 host**：`daily_task` / `market_scan` 一次调用只走一步（识别一帧 → 视觉模型选一步 →
  我方坐标执行），返回值里带着 `scene` / `reason` / `chosen` / `executed` / `done` / `note`，
  下一步由 host 的轮数上限驱动。以前是工具内部自己转 120 步、只回一个 `steps` 数字，
  外层模型看不到过程、无法纠偏，两个大脑各算各的。现在只有一个大脑：外层模型看过程下指令，内层只负责单步。
  绕圈记录（哪一步会导致绕回旧画面）从局部变量提到模块级（`vision/llm._STEP_STATE`），跨调用保留；
  参数重复不再算「卡住」，host 改看 `executed`：连续 3 步没有动作才停下。
- **前台不过就不动手**：鼠标与键盘事件是全局的，窗口没切到前台时发出去会打到用户正在用的别的应用上，
  对游戏本身也毫无作用（空转的帮凶）。`click` / `drag` / `press_key` / `hotkey` / `type_text` 一律先试着切前台，
  切不过去就记 warning 并返回 False，一个事件都不发；`click_point` / `click_text` 据此回 `ok=false`，
  决策循环也不再把它算作「已执行」。
- **写操作串行**：工具共用唯一的一套鼠标键盘，`click_point` / `click_text` / `press_key` / `open_game` /
  `close_game` / `login` / `back_to_main` / `daily_task` / `market_scan` 走同一把可重入锁。一次单步跑着的时候
  另一路调用会立刻拿到 `busy=true`（不排队：单步要等一轮视觉模型，排队的短工具只能表现成卡死）。
  只读工具（`ocr` / `layout` / `detect` / `advise`）不受影响。
- **异常兜底**：工具抛异常时，返回注解是 `dict` 的统一转成 `{ok:false, error:"<工具> 异常: ..."}`（模型看得懂
  下一步该干什么），返回 `str` / `list` 的工具没法塞 dict，仍原样抛给 MCP。
- **画面没变就不重算**：决策循环每步先算一次 32x32 均值指纹（~3ms），与上帧一致就直接复用上帧区域，
  省掉整图 OCR（≈1.3s）与模板/YOLO 融合（≈1.3s）——点了没反应时画面纹丝不动，这两笔钱本来每轮白付。
- **人工接管即暂停**：鼠标被**人**移动或点击后，自动化立刻停手（不点击/不按键/不移动，也不推进流程），
  鼠标静止满 `human_guard.idle_resume_sec`（默认 10s）自动恢复。程序自身的移动/点击由
  `program_action()` 计数 + `program_grace_sec`（0.6s）宽限期滤掉，不会自己触发自己。
  暂停期间用户可能已经点了别的界面，所以恢复后会丢弃暂停前算出的候选重新感知（`generation` 比对）。
  `game_status` 返回 `paused` / `resume_in_sec`；其他工具在暂停中最多阻塞 `human_guard.max_block_sec`
  （默认 30s）后返回 `paused=true`，避免短工具表现成卡死（`ping`/`list_windows`/`game_status` 立即返回）。
  该保护依赖 macOS「辅助功能/输入监控」权限，与合成键鼠同生共死；F12 急停由 `server.main()` 启动时
  注册（幂等，以前该热键从未生效）。
- **登录自动检测**：`login` 先用窗口列表（含最小化/其他桌面的窗口）判断游戏是否已打开；
  未打开则按 `auto_open=true` 自动 `open` 客户端并等待窗口；已在主场景时跳过点击直接返回
  `already_in_game`。登录按钮兼容「进入游戏」「登录游戏」两种客户端文案。登录页与登录过程中
  出现的公告、设备风险提示等弹窗，会先点「确定/确认/我知道了」关掉再继续（同一按钮最多点 3 次），
  关掉过哪些按钮会在返回值的 `dialogs` 里。游戏已开但窗口被最小化/在别的桌面时，会自动尝试激活，
  仍不可见则报错提示手动切到前台（macOS 13 无法可靠跨 Space 拉回窗口，此时截图会是全黑图，
  OCR 必然读不到登录按钮）。
- **回主界面**：`back_to_main` 用于从面板/弹窗里退出来：画面有「关闭/确定/确认/我知道了」
  就先点掉（同一按钮最多 3 次），认不出按钮时按 ESC 逐级返回；战斗界面不按 ESC（避免误触逃跑），
  直接返回 `stage=battle`；已在主界面则返回 `already_in_main` 且不做任何操作。`max_steps` 默认 8。
- **关闭游戏**：`close_game` 先请应用正常退出（等同 Cmd+Q，客户端有机会存档/下线），
  `timeout` 秒（默认 2.5）内没退就发 SIGTERM，再 1.5 秒仍在则 SIGKILL；`force=true`
  跳过前两级直接强杀。实测该客户端既忽略 Cmd+Q 也忽略 SIGTERM，只有 SIGKILL 生效，
  所以各级等待都很短（轮询 0.2s），避免为了「优雅」空等十几秒：正常路径约 4.5s 完成，
  返回值里的 `killed` 就是被强杀的 PID（`forced=true`）。本来就没在跑时返回 `already_closed`。
  窗口最小化/在其他桌面也照样能关（按 PID 结束，不依赖截图）。
- **收尾即返回**：`close_game`/`open_game`/`login` 返回 `ok=true` 时目标已达成，host 直接
  用工具返回拼出回复并结束本轮（判断日志备注「跳过模型复核」），不再多跑一轮模型（省 3~4s）。
- **Jackson 版本**：MCP Java SDK 1.1.5 默认用 Jackson 3，与 Spring Boot 3.3 管理的 Jackson 2 冲突
  （`JsonProperty.isRequired` 签名不同 → `NoSuchMethodError`），`mcpclient/pom.xml` 已排除
  `mcp-json-jackson3` 并换用 `mcp-json-jackson2`。
- **onnxruntime 钉版本**：1.21.1 是 macOS 13.0 上可用的最后一版（1.23+ 的 wheel 按 13.4 构建会缺 libc++ 符号）。
- **配置分两份**（均在各自模块下，模板见同目录 `config.example.json`）：
  - `host/config.json`：`llm` 段（api_base / api_key / model / 超时 / 轮数 / 附图）；含密钥，已 gitignore。
  - `mcpserver/config.json`：`window` / `capture` / `input` / `human_guard` / `timing` / `keywords` / `ocr` /
    `template` / `vision` / `yolo`。`timing` 管全部等待（`step_pause` / `click_pause` / `idle_pause` /
    `back_step_wait` / `enter_click_wait` / `dialog_click_pause` / `dialog_retry_pause` /
    `loading_poll_pause` / `advance_poll_pause` / `window_poll_pause`），`keywords` 管登录/关闭/弹窗/战斗/
    主场景这些文案表；两段都没配时回落到 `tools/game.py` 里的默认值，改文案或调节奏不必改代码。

  Python 侧 `config.py` 读游戏配置时会自动把 `host/config.json` 的 `llm` 段合并进来，
  所以 `Advisor(cfg)` 等读 `cfg["llm"]` 的代码不变；密钥只存一处。可用环境变量覆盖：
  `AUTOGAME_CONFIG`（整份游戏配置）、`AUTOGAME_LLM_CONFIG`（AI 配置）、`LLM_API_KEY`（密钥）。
- **旧版单进程实现已移除**：原 `mhxy_bot/`、`mhxy`、`start.command`、根 `requirements.txt` 与 3.9 版 `venv/`
  已删除，全部能力收敛到 `mcpserver/game_mcp/`；`assets/`、`debug/` 仍留在项目根，
  由 `mcpserver` 相对项目根引用。

## 8. 回归用例（离线）

`mcpserver/tests/` 是入库的回归用例：跑真代码路径、用 `tests/fixtures/` 里的固定截图，不需要游戏在跑。

```bash
# 有过 pytest 就用它（输出更细）；没有也能跑，两条路径是同一批用例
mcpserver/.venv/bin/python -m pytest -q mcpserver/tests
mcpserver/.venv/bin/python mcpserver/tests/run.py

# 一把梭：语法检查 + 全部用例
bash scripts/check.sh
```

| 用例 | 钉住的行为 |
|---|---|
| `test_vision_gate` | 弹窗帧要检出顶层面板、主场景不能检出；遮罩下的按钮不进候选且带「（遮罩下·点不动）」；面板内彩色选项进候选；UITag 的 icon 大框不得把三条任务行合成一个候选；画面指纹同图同值异图异值 |
| `test_step_once` | 单步只发一组动作（不自己循环）、过程字段齐全、窗口不在前台时 `executed=0` 且有原因、`done` 清空状态、绕圈记录跨调用保留 |
| `test_frame_route` | 抓帧端点返回客户端能解码的 JPEG（尺寸与源帧一致）；无窗口时 `503` + 人话；不进 MCP 工具清单 |
| `test_collect_dedupe` | 同画面多帧只存 1 张、不同画面照存、重启后从 `index.jsonl` 恢复基线不重存 |
| `test_input_guard` | 窗口不在前台时 `click`/`drag`/`hotkey`/`type_text`/`press_key` 一个事件都不发 |
| `test_tool_guard` | 写操作并发回 `busy=true`、锁可重入、dict 工具异常转 `ok=false`、关键词与节奏以 config 为准 |

样例帧在 `tests/fixtures/*.png`（约 3.4MB）。换样例帧就等于换基线，改之前先跑一遍确认现在是绿的。
