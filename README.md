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
│       └── ChatApi.java           # AI 服务接口：/ai/chat|tools|history|reset
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
6）、`chat_vision`（默认是否附图，默认 true）。对话均走 `/ai/chat`，工具清单由 MCP `listTools()`
实时生成，Python 侧加工具后无需改 Java。

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

### 4.2 mcpserver 运行日志

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

## 5. Docker 部署（host 进容器，mcpserver 留在宿主机）

`mcpserver` 要操作 macOS 本机窗口（pyobjc/Quartz + pynput + mss），容器里既没有 WindowServer
也没有辅助功能权限，塞进去只是个空壳；所以只把 `host` 容器化，它经 `host.docker.internal`
连宿主机上常驻的 `mcpserver`：

```bash
bash scripts/run-mcpserver.sh     # 终端 A：mcpserver 常驻 http://0.0.0.0:18090/mcp
docker compose up -d              # 终端 B：构建并启动 host 容器，对外 18080
docker compose logs -f host       # 出现「mcpserver 已就绪」即握手成功
curl -s localhost:18080/ai/tools  # 应返回 15 个工具
```

| 变量 | 作用 | 默认 |
| --- | --- | --- |
| `AUTOGAME_MCP_URL` | 容器内连 mcpserver 的完整端点 | `http://host.docker.internal:18090/mcp` |
| `AUTOGAME_MCP_TRANSPORT` / `_HOST` / `_PORT` / `_PATH` | mcpserver 的传输方式、监听地址与路径 | `streamable-http` / `0.0.0.0` / `18090` / `/mcp` |
| `AUTOGAME_MCP_ALLOWED_HOSTS` | mcpserver 的 DNS rebinding 白名单，容器访问须含 `host.docker.internal:*` | 已含该项 |
| `AUTOGAME_ROOT` | 容器内找配置/写日志/读截图的根目录 | `/app`（compose 挂载整个仓库） |
| `LLM_API_KEY` | 覆盖 `host/config.json` 里的密钥 | 空（用配置文件里的） |
| `HOST_BASE_IMAGE` | 构建 host 镜像的基础镜像 | `eclipse-temurin:21-jre` |

Docker Hub 直连在国内经常不通，表现为 `docker compose build` 长时间无输出（daemon 卡在连
`registry-1.docker.io` 上重试）。先换镜像源再构建：

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
（成功后状态栏显示「已连接 … 工具 15 个」）→ 输入框打字，按「发送」或 `Ctrl+Enter`。
上方是对话，下方是这一轮 AI 调用了哪些工具、参数、耗时、返回。

- 地址填一次就存进同目录 `client.properties`，下次自动带出；也可直接把
  `http://192.168.0.31:18080` 粘进「服务 IP」，端口会自动拆出来。
- host 跑在容器里时，Windows 连的是**宿主机 IP**（如 `192.168.0.31:18080`），不是容器 IP。
- 无界面自检（排障用，在本目录的 cmd 里执行），会打印生效地址、读到的配置文件位置、
  工具数量，连不上时给出具体异常：

```bat
runtime\bin\java.exe -jar client.jar --selftest --base=http://192.168.0.31:18080
```

命令行参数：`--host` / `--port` / `--base` / `--session` / `--vision`。

macOS 侧仍可用终端版对话框：`bash scripts/run-chat.sh`（`chat.jar`，同样支持 `--base`）。

## 7. 说明与约定

- **工具清单（17）**：`ping`、`list_windows`、`capture`、`ocr`、`layout`、`detect`、`click_point`、
  `click_text`、`press_key`、`advise`、`open_game`、`close_game`、`game_status`、`login`、
  `back_to_main`、`daily_task`、`market_scan`。
- **工具描述就是提示词**：每个工具的使用场景、触发说法、是否真实操作、返回字段都写在
  `mcpserver/game_mcp/server.py` 的 docstring 里（MCP 会把它当 description 透给模型）；
  host 的 `SYSTEM_PROMPT` 只保留对话协议（JSON 格式、tool/reply 二选一、失败两次停手），
  不再重复工具细节。新增工具时只需在 Python 侧写清描述，Java 不用同步。
- **多层视觉流水线**（`vision/screen.py`，模板优先）：

  ```
  截图 ─┬─ template 层：已知图标/固定元素 → Region(kind, source=template, 精确中心)
        ├─ yolo 层   ：UITag 分类控件 button/task/info/dialog/icon → 逐框 OCR 读区内文字
        ├─ general 层：通用检测找游戏实体（人物/NPC）→ Region(kind=entity, source=general)
        ├─ ocr 层    ：没被任何区域覆盖的文字 → 按「同行且水平相邻」聚成 label 兜底
        └─ 融合去重（template > yolo > general > ocr，IoU ≥ 0.5 丢弃重叠）→ Region 列表 → 可点/只读分流
  ```

  两级检测共用一套去重规则（见 `detect_layers`）：先跑 UITag，再跑通用模型；
  与 UITag 框 `IoU ≥ 0.5`、或中心点落在可点区域里（头像按钮之类）的实体框直接丢弃，
  其余作为 `kind=entity` 只读锚点——**不认领框内文字**，所以血条旁的怪物名照样进 `label` 层。

  只有 `vision.clickable_kinds`（现阶段仅 `button`）里的区域会带编号 id 交给模型选，
  其余区域只作为「类型: 内容」的只读信息供判断——所以模型不可能把正文里出现的「确定」二字当按钮点掉。
  可点集合为空时按 `vision.empty_clickable_fallback` 降级为纯文本候选（等价改造前行为），不会空转停摆。
  排查融合效果用 `layout` 工具，直接返回当前画面的 `regions[{kind,source,text,box,cx,cy}]`。
- **模板清单 + YOLO 区域分类**：模板不再由代码逐个查，而是 `template.items` 一张表
  （`{"file": "close_x.png", "kind": "button"}`），放图 + 加一行配置即生效，缺文件静默跳过；
  `scripts/crop_template.py --box x1,y1,x2,y2 --name close_x` 从 `debug/` 截图裁出模板并
  直接打印该往配置里加的那一行。模板项不单独声明「是否可点」，统一由 `kind` 决定。
  YOLO 只做区域分类、不区分「保存还是取消」（语义由区内 OCR 给出），推荐权重
  `assets/models/uitag-yolo11s-ui-detect-v1.pt`（YOLO11s 微调、桌面截图 9 类，含 Button）。
  模型类别名（`Button` / `Icon` / `Text` …）与内部 kind 不是一回事，检测后先查
  `yolo.class_map` 再查内置 `CLASS_MAP` 归一化成 button/icon/label/dialog/info/task；
  未收录的类别降成 `info`（只读锚点，不会被点）并且只 warning 一次——按提示补一行
  `"class_map": {"Checkbox": "button"}` 就能改正，换模型不用改代码。
  `yolo.imgsz` 默认 1280：UI 元素普遍很小，640 会把 25px 的按钮缩成 10px、召回明显掉。
  权重缺失时该层直接禁用并记 `_load_error`、**不回退 COCO**
  （COCO 的 person/car 当 UI 用只会坑下游），除非显式设 `yolo.allow_pretrained_fallback=true`。
  面积超过整图 35% 的框一律当容器：不参与可点、也不认领框内文字，
  避免「整屏一个窗口框」把 OCR 全吃进 `info`、使 `label` 兜底层失效导致流程空转。
- **通用检测层**（`general` 段，默认开）：拿 `assets/models/yolo11s.pt`（COCO 80 类）
  找游戏实体，`classes: ["person"]` 是白名单（COCO 的 tv/car 对游戏没用，不筛只会增加噪声），
  `default_kind: "entity"` 决定未收录类别名归成 `entity`（只读锚点）。
  它和 UITag 是两个独立 `YoloDetector`：任一层权重缺失只影响自己，不会拖垮另一层；
  `detect` 工具用 `layer=all|ui|general` 可分别查看两层结果。
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
  - `mcpserver/config.json`：`window` / `capture` / `input` / `human_guard` / `timing` / `ocr` /
    `template` / `vision` / `yolo`。

  Python 侧 `config.py` 读游戏配置时会自动把 `host/config.json` 的 `llm` 段合并进来，
  所以 `Advisor(cfg)` 等读 `cfg["llm"]` 的代码不变；密钥只存一处。可用环境变量覆盖：
  `AUTOGAME_CONFIG`（整份游戏配置）、`AUTOGAME_LLM_CONFIG`（AI 配置）、`LLM_API_KEY`（密钥）。
- **旧版单进程实现已移除**：原 `mhxy_bot/`、`mhxy`、`start.command`、根 `requirements.txt` 与 3.9 版 `venv/`
  已删除，全部能力收敛到 `mcpserver/game_mcp/`；`assets/`、`debug/` 仍留在项目根，
  由 `mcpserver` 相对项目根引用。
