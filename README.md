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
│       └── ChatApi.java           # AI 服务接口：/ai/chat|tools|history|reset
├── mcpclient/               # MCP 客户端层（官方 MCP Java SDK）
│   └── src/main/java/com/autogame/client/
│       ├── McpClientFactory.java  # stdio 拉起 Python server + initialize 握手
│       ├── GameTools.java         # 少量类型化方法 + toolSpecs/invoke 泛型入口
│       └── ChatConsole.java       # 对话调试台（chat.jar 的主类）
├── mcpserver/               # MCP 服务层（Python 3.13 / mcp SDK 2.x）
│   ├── config.json                # 游戏侧配置（窗口/截图/输入/OCR/模板/YOLO）
│   ├── config.example.json        # 同上模板
│   ├── game_mcp/
│   │   ├── server.py              # MCPServer("wendao-game") + 14 个 @app.tool()
│   │   ├── config.py              # 读 mcpserver/config.json，并合并 host/config.json 的 llm
│   │   ├── core/                  # 窗口 / 截图 / 输入 / 引擎
│   │   ├── vision/                # OCR / 模板匹配 / YOLO / LLM 决策
│   │   └── tools/                 # game.py(open_game/login) daily.py(日常/扫货)
│   ├── requirements.txt
│   └── smoke_test.py              # Python 侧 stdio 冒烟
├── scripts/
│   ├── bootstrap.sh         # 一键装 JDK + venv + 依赖 + 构建 Java
│   ├── env.sh               # source 后得到 JAVA_HOME / 三层目录变量
│   ├── run-host.sh          # 按需构建并运行 host（源文件比 jar 新就重构建）
│   └── run-chat.sh          # 对话调试台（构建并运行 mcpclient/target/chat.jar）
├── assets/                  # 模板图、YOLO 权重（与 mcpserver 共用）
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
bash scripts/run-host.sh login          # 自动检测/启动游戏 -> 点“登录游戏” -> 等主界面
bash scripts/run-host.sh status         # 游戏是否已打开（窗口可见/窗口隐藏/进程）
bash scripts/run-host.sh daily --goal=师门 --dry-run=true
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

## 5. 说明与约定

- **工具清单（14）**：`ping`、`list_windows`、`capture`、`ocr`、`detect`、`click_point`、
  `click_text`、`press_key`、`advise`、`open_game`、`game_status`、`login`、`daily_task`、`market_scan`。
- **登录自动检测**：`login` 先用窗口列表（含最小化/其他桌面的窗口）判断游戏是否已打开；
  未打开则按 `auto_open=true` 自动 `open` 客户端并等待窗口；已在主场景时跳过点击直接返回
  `already_in_game`。游戏已开但窗口被最小化/在别的桌面时，会自动尝试激活，仍不可见则报错提示
  手动切到前台（macOS 13 无法可靠跨 Space 拉回窗口）。
- **Jackson 版本**：MCP Java SDK 1.1.5 默认用 Jackson 3，与 Spring Boot 3.3 管理的 Jackson 2 冲突
  （`JsonProperty.isRequired` 签名不同 → `NoSuchMethodError`），`mcpclient/pom.xml` 已排除
  `mcp-json-jackson3` 并换用 `mcp-json-jackson2`。
- **onnxruntime 钉版本**：1.21.1 是 macOS 13.0 上可用的最后一版（1.23+ 的 wheel 按 13.4 构建会缺 libc++ 符号）。
- **配置分两份**（均在各自模块下，模板见同目录 `config.example.json`）：
  - `host/config.json`：`llm` 段（api_base / api_key / model / 超时 / 轮数 / 附图）；含密钥，已 gitignore。
  - `mcpserver/config.json`：`window` / `capture` / `input` / `timing` / `ocr` / `template` / `yolo`。

  Python 侧 `config.py` 读游戏配置时会自动把 `host/config.json` 的 `llm` 段合并进来，
  所以 `Advisor(cfg)` 等读 `cfg["llm"]` 的代码不变；密钥只存一处。可用环境变量覆盖：
  `AUTOGAME_CONFIG`（整份游戏配置）、`AUTOGAME_LLM_CONFIG`（AI 配置）、`LLM_API_KEY`（密钥）。
- **旧版单进程实现已移除**：原 `mhxy_bot/`、`mhxy`、`start.command`、根 `requirements.txt` 与 3.9 版 `venv/`
  已删除，全部能力收敛到 `mcpserver/game_mcp/`；`assets/`、`debug/` 仍留在项目根，
  由 `mcpserver` 相对项目根引用。
