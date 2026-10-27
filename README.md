# autogame · 问道游戏视觉识别（三层 MCP 架构）

只做一件事：**把游戏窗口画面变成结构化识别结果** —— YOLO 通用检测找实体 + OCR 认文字，
输出统一的 `Region` 列表。不做决策、不模拟键鼠，识别结果怎么用由调用方决定。

工程保留完整三层：`mcpclient`（Java 客户端）→ `host`（Spring Boot HTTP 门面）→
`mcpserver`（Python MCP 服务）。运行时全部落在仓库内（JDK / Python 由脚本下载到
`.tools`、`mcpserver/.venv`），不改系统全局配置。

> ⚠️ 声明：仅用于个人学习与技术验证。游戏自动化违反游戏用户协议，可能导致封号，请使用测试账号并自担风险。

## 1. 目录结构

```
autogame/
├── host/                    # 宿主层（Spring Boot 3.3 / Java 21）：HTTP 工具门面
│   └── src/main/java/com/autogame/host/
│       ├── HostApplication.java   # 入口：常驻 /ai/* 服务
│       ├── GameService.java       # 持有与 mcpserver 的唯一会话
│       ├── ToolApi.java           # /ai/tools | /ai/invoke | /ai/frame
│       └── FrameService.java      # 转发 mcpserver 的 GET /frame.jpg
├── mcpclient/               # MCP 客户端层（官方 MCP Java SDK）
│   ├── packaging/                 # Windows 客户端启动脚本与包内说明
│   └── src/main/java/com/autogame/client/
│       ├── McpClientFactory.java  # stdio 拉起 Python server + initialize 握手
│       ├── GameTools.java         # toolSpecs（清单）+ invoke（通用调用）
│       ├── HostClient.java        # /ai/* 的 HTTP 门面（终端与界面共用）
│       ├── ChatConsole.java       # 终端工具调用台（chat.jar 的主类）
│       ├── ClientConfig.java      # 客户端 IP/端口读写（client.properties）
│       └── ChatWindow.java        # 图形客户端（client.jar 的主类）
├── mcpserver/               # MCP 服务层（Python 3.13 / mcp SDK 2.x）
│   ├── config.json                # 游戏侧配置（window/capture/ocr/general，已 gitignore）
│   ├── config.example.json        # 同上模板
│   ├── game_mcp/
│   │   ├── server.py              # MCPServer("wendao-game") + 6 个只读 @tool + /frame.jpg
│   │   ├── config.py              # 读 mcpserver/config.json
│   │   ├── core/                  # 窗口定位 / 截图 / 引擎上下文 / 运行日志(log)
│   │   └── vision/                # 区域融合(screen) / OCR(ocr) / 通用检测(yolo)
│   ├── tests/                     # 离线回归用例（固定样例帧）
│   ├── requirements.txt
│   └── smoke_test.py              # Python 侧 stdio 冒烟
├── scripts/
│   ├── bootstrap.sh         # 一键装 JDK + venv + 依赖 + 构建 Java
│   ├── env.sh               # source 后得到 JAVA_HOME / 三层目录变量
│   ├── run-host.sh          # 按需构建并运行 host（源文件比 jar 新就重构建）
│   ├── run-chat.sh          # 终端工具调用台（构建并运行 chat.jar）
│   ├── run-mcpserver.sh     # 以 Streamable HTTP 常驻 mcpserver（容器化 host 用）
│   ├── docker-host.sh       # 构建/启动 host 容器（基础镜像自愈 + 增量构建）
│   ├── build-client-win.sh  # 打 Windows 便携客户端（client.jar + JRE + bat -> zip）
│   └── check.sh             # Python 语法 + 离线用例 + Java 编译
├── assets/models/yolo11s.pt # YOLO11s（COCO 通用检测，只找人物一类实体）
├── debug/                   # 运行时生成：capture 工具的截图（已 gitignore）
├── logs/                    # 运行时生成：host.log / mcpserver.log（已 gitignore）
├── host/Dockerfile          # host 镜像（只装 JRE + host.jar）
├── docker-compose.yml       # 编排：host 进容器，mcpserver 留在宿主机
└── pom.xml                  # 父 POM，聚合 mcpclient + host
```

## 2. 调用链

```
ChatWindow / ChatConsole          （Windows 客户端 / 终端）
        │  HTTP
        ▼
host :18080   /ai/tools  /ai/invoke?tool=xxx  /ai/frame
        │  MCP（默认 stdio 子进程；也可 Streamable HTTP）
        ▼
mcpserver   6 个只读工具 + GET /frame.jpg
        │  Quartz 截图 / RapidOCR / YOLO11s
        ▼
   游戏窗口
```

| 端点 | 说明 |
| --- | --- |
| `GET /ai/tools` | 工具清单（MCP `listTools()` 原样透出），客户端据此渲染下拉框 |
| `POST /ai/invoke?tool=xxx` | 调一次工具，body 就是该工具的参数 JSON；返回 `{ok, tool, result\|error}` |
| `GET /ai/frame` | 当前画面 JPEG（503 + 一句人话表示抓不到），给眼睛看，**不进工具清单** |
| `GET /frame.jpg`（mcpserver） | 同上，host 的抓帧接口就是转发它 |

抓帧刻意走独立 HTTP：`GameService` 整类是 `synchronized` 的，一次全图 OCR 能占住会话好几秒，
而「怎么还没出来，看一眼画面」恰好就发生在这段时间里。

## 3. 工具清单（全部只读）

| 工具 | 参数 | 返回 |
| --- | --- | --- |
| `ping` | — | 字符串 `pong` |
| `list_windows` | — | 可见窗口列表（title/x/y/width/height/scale/pid…），最小化窗口不在内 |
| `capture` | `tag="capture"` | 截图存 `debug/`，返回 `path/width/height/scale` |
| `ocr` | `keyword?`、`region?=[x1,y1,x2,y2]` | `count` + `texts[{text,confidence,box,cx,cy}]` |
| `layout` | — | `count` + `regions[{kind,source,text,box,cx,cy,confidence}]` |
| `detect` | `classes?=["person"]` | `count` + `model_classes` + `detections[...]`，一律 `kind=entity` |

约定：

- 返回 dict 的工具异常会转成 `{ok:false, error:"..."}`，不会让 MCP 调用直接炸掉；
  返回 list/str 的（`ping`/`list_windows`）保持原样抛，便于调用方判断协议层故障。
- 没有游戏窗口时统一抛 `WindowNotFound`，客户端看到的是「请手动打开游戏」这类人话。
- 参数与结果都不改写：host 只转发，识别口径由 mcpserver 的 docstring 决定。

## 4. 视觉流水线（两层）

```
截图
 ├─ general 层：COCO 模型找游戏实体（人物/NPC）→ Region(kind=entity, source=general)
 └─ ocr 层   ：全图 OCR 中未被实体框认领的文本 → Region(kind=label, source=ocr)
```

1. **general 层**（`general` 段，可关）跑一次找实体：框之间 `IoU >= 0.7` 只留置信度高的；
   **不认领框内文字**，所以血条旁的怪物名照样进 label 层。
2. **ocr 层**：全图 OCR 结果里中心点落在实体框内的丢弃，剩下的按「同一行且水平相邻」
   （间距 ≤ 字高 × 1.6）聚成一条 `label`，不是一个字一条。
3. **排序**：`entity` 在前、`label` 在后，各自内部按 `(cy, cx)` 阅读序，总数截断 `max_n`。

界面控件（按钮/图标/面板选项）**不走模型也不做模板匹配**：这一版只输出「哪里有实体」与
「写了什么字」，是否要据此动作由上层决定。通用检测未就绪时全部文字落到 `label`，
`layout` 就退化成一份按行聚合的文本清单。

## 5. 配置

只有一份：`mcpserver/config.json`（模板见同目录 `config.example.json`）。

| 段 | 键 | 作用 |
| --- | --- | --- |
| `window` | `title_keywords` / `exclude_keywords` | 按标题选窗口（默认 `["问道"]`，排除更新器/登录器） |
| `capture` | `debug_dir` | `capture` 工具落盘目录（默认 `debug`） |
| `ocr` | `engine` / `use_det` / `min_confidence` | RapidOCR 引擎与置信度下限 |
| `general` | `enabled` / `model_path` / `conf_threshold` / `device` / `imgsz` / `classes` | YOLO 通用检测 |

环境变量：

| 变量 | 作用 |
| --- | --- |
| `AUTOGAME_CONFIG` | 换一份配置文件的绝对路径 |
| `AUTOGAME_MCP_TRANSPORT` | `stdio`（默认）或 `streamable-http` |
| `AUTOGAME_MCP_HOST` / `PORT` / `PATH` | HTTP 监听地址（默认 `0.0.0.0:18090/mcp`） |
| `AUTOGAME_MCP_ALLOWED_HOSTS` | DNS rebinding 白名单（容器访问需含 `host.docker.internal:*`） |
| `AUTOGAME_MCP_URL` | host 侧连接地址；不设则 host 自己拉起 stdio 子进程 |
| `AUTOGAME_ROOT` | 日志/配置/截图的根目录（stdio 模式定位项目根时用） |

## 6. 快速开始

```bash
bash scripts/bootstrap.sh          # 首次：JDK 21 + venv + 依赖 + 构建
bash scripts/check.sh              # 自检：语法 + 离线用例 + Java 编译

# 方式 A：本机 stdio（host 自己拉起 Python 子进程，不用另开终端）
bash scripts/run-host.sh           # http://localhost:18080

# 方式 B：mcpserver 常驻 HTTP（容器化 / 想单独重启 Python 侧时）
bash scripts/run-mcpserver.sh      # 终端 A：0.0.0.0:18090/mcp
AUTOGAME_MCP_URL=http://127.0.0.1:18090/mcp bash scripts/run-host.sh   # 终端 B

# 客户端
bash scripts/run-chat.sh                                  # 终端调用台
bash scripts/run-chat.sh --tool=detect --args='{"max_n":20}'   # 单次调用（冒烟）
bash scripts/build-client-win.sh                          # 打 Windows 便携包 -> dist/
```

手动探一下也很快：

```bash
curl -s localhost:18080/ai/tools | head -c 400        # 应返回 count=6
curl -s -X POST 'localhost:18080/ai/invoke?tool=ping' # {"ok":true,...,"result":"pong"}
curl -s -X POST 'localhost:18080/ai/invoke?tool=ocr' -H 'Content-Type: application/json' -d '{}'
curl -s localhost:18080/ai/frame -o /tmp/frame.jpg    # 当前画面快照
```

## 7. 客户端

**终端（`scripts/run-chat.sh`）**：提示符下直接写「工具名 [JSON 参数]」，例如
`ocr`、`detect {"classes":["person"]}`。`:tools` 看清单，`:frame [路径]` 存一帧，
`:help` 看全部命令。

**图形（`client.jar`）**：顶部填 host 的 IP/端口 → 连接；中间选工具、填 JSON 参数、
回车即调用，返回的 JSON 原样贴在记录区；右侧是当前画面（只在连上/点按钮时抓一帧）。
地址存在同目录 `client.properties`。详细说明见 `mcpclient/packaging/README.txt`。

## 8. 测试与自检

```bash
bash scripts/check.sh        # Python 语法 + 离线回归用例 + Java 编译
mcpserver/.venv/bin/python mcpserver/tests/run.py          # 只跑 Python 用例
mcpserver/.venv/bin/python mcpserver/smoke_test.py ping ocr # 真窗口 stdio 冒烟
```

离线用例（`mcpserver/tests`，13 个）只吃 `tests/fixtures` 里的固定截图，不需要游戏在跑：

- `test_regions.py`：OCR 按行聚合、实体框只读不吞文字、关检测退化纯文本、`to_dict` 字段契约；
- `test_tool_guard.py`：dict 工具异常转 `ok=false`、list 工具照原样抛、工具清单正好 6 个只读工具；
- `test_frame_route.py`：抓帧能解码成 JPEG、没窗口时 503 + 人话、`frame` 不是 MCP 工具。

## 9. 日志

所有日志文件统一在项目根的 `logs/` 下（已 gitignore，不随代码提交）：

| 文件 | 写入方 | 内容 |
| --- | --- | --- |
| `logs/mcpserver.log` | Python（`core/log.py`） | 每次工具调用的参数摘要、耗时、异常，YOLO/OCR 加载情况；5MB 轮转为 `mcpserver.log.1` |
| `logs/host.log` | host（Spring Boot） | 启动、MCP 握手、`/ai/*` 请求与异常；控制台同时可见 |

`debug/` 只存 `capture` 工具显式调用时落盘的截图（不是日志）。

常用开关：`AUTOGAME_LOG_LEVEL=DEBUG`（mcpserver 写全量）、`AUTOGAME_LOG_STDERR=INFO`
（同时打到 stderr）、`AUTOGAME_LOG_FILE=/path/host.log`（改 host 日志位置）。

## 10. Docker 部署（可选）

mcpserver 依赖 pyobjc / Quartz 去截 macOS 本机窗口，容器里没有 WindowServer 也没有
辅助功能权限，所以**只有 host 进容器**，mcpserver 留在宿主机常驻：

```bash
bash scripts/run-mcpserver.sh     # 终端 A：宿主机 18090/mcp
bash scripts/docker-host.sh       # 终端 B：构建并启动 host 容器，对外 18080
bash scripts/docker-host.sh logs  # 出现「mcpserver 已就绪」即握手成功
bash scripts/docker-host.sh status
bash scripts/docker-host.sh down
```

镜像里只有 JRE + `host.jar`（`context: ./host`，不把整个仓库交给 daemon）；
容器通过 `AUTOGAME_MCP_URL=http://host.docker.internal:18090/mcp` 连宿主机。
容器的 `/app/logs` 挂回宿主机 `./logs`，所以 `logs/host.log` 在宿主机上也能直接看。

## 11. 设计说明（踩过的地方）

- **两层而不是四层**：模板匹配与面板彩色选项都被移除——它们对分辨率/皮肤极敏感，
  一个换肤就全废；留下的是「实体位置」与「文字内容」两条稳定信号。
- **不用 UI 控件分类模型**：小样本微调的 UI 分类器只在训练过的截图上像样，换场景就误报，
  且需要持续标数据；这类需求用 OCR 文字 + 布局更可控。
- **抓帧不进工具清单**：工具的 description 会进模型/客户端可见的清单，截图属于「给人看」的
  旁路，混进去只会让调用方误以为需要频繁截图。
- **MCP Java SDK 的 Jackson 冲突**：SDK 默认带 `mcp-json-jackson3`（Jackson 3），与
  Spring Boot 3.3 管理的 Jackson 2 冲突，故排除它并换用 `mcp-json-jackson2`。
- **onnxruntime 钉 1.21.1**：这是 macOS 13.0 上可用的最后一版（1.23+ 的 wheel 按 13.4
  构建，加载会缺 libc++ 符号）。
- **Windows 客户端字体**：Consolas 这类西文等宽字体没有中文字形，识别结果会画成方块；
  记录区按「系统真装了 + 能显示中文」挑字体，兜底用逻辑 `Monospaced`。
- **终端/图形共用门面**：`ChatConsole` 与 `ChatWindow` 都只碰 `HostClient`，
  换传输方式（stdio ↔ HTTP）不用改客户端。
