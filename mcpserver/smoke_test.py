"""stdio 冒烟测试：用官方 MCP 客户端拉起 game_mcp.server，验证工具往返。

用法：
    mcpserver/.venv/bin/python mcpserver/smoke_test.py [ping|windows|ocr|capture]

默认依次跑 ping -> list_windows；再按需跑 capture/ocr（需游戏窗口在前台）。
"""
from __future__ import annotations

import asyncio
import json
import os
import sys

from mcp import Client
from mcp.client.stdio import StdioServerParameters

SERVER_DIR = os.path.dirname(os.path.abspath(__file__))
PYTHON = os.path.join(SERVER_DIR, ".venv", "bin", "python")


def _text(result) -> str:
    """把 CallToolResult 压成可读文本：优先结构化内容，否则拼接文本块。"""
    structured = getattr(result, "structured_content", None)
    if structured:
        return json.dumps(structured, ensure_ascii=False)
    parts = []
    for block in getattr(result, "content", []) or []:
        parts.append(getattr(block, "text", str(block)))
    return "\n".join(parts)


async def run(calls: list[tuple[str, dict]]) -> int:
    params = StdioServerParameters(
        command=PYTHON,
        args=["-m", "game_mcp.server"],
        cwd=SERVER_DIR,
    )
    failures = 0
    async with Client(params) as client:
        tools = await client.list_tools()
        names = sorted(t.name for t in tools.tools)
        print(f"[tools] {len(names)}: {', '.join(names)}")
        for name, args in calls:
            try:
                result = await client.call_tool(name, args, read_timeout_seconds=180)
            except Exception as e:  # noqa: BLE001
                failures += 1
                print(f"[{name}] EXCEPTION: {type(e).__name__}: {e}")
                continue
            text = _text(result)
            is_error = bool(getattr(result, "is_error", False))
            if is_error:
                failures += 1
            print(f"[{name}] {'ERROR' if is_error else 'OK'}: {text[:600]}")
    return failures


def main() -> int:
    which = sys.argv[1:] or ["ping", "list_windows"]
    table: dict[str, tuple[str, dict]] = {
        "ping": ("ping", {}),
        "windows": ("list_windows", {}),
        "capture": ("capture", {"tag": "smoke"}),
        "ocr": ("ocr", {}),
    }
    calls = [table[w] for w in which if w in table]
    if not calls:
        print(f"未知用例: {which}，可选 {list(table)}")
        return 2
    failures = asyncio.run(run(calls))
    print(f"\n{'全部通过' if failures == 0 else f'{failures} 个用例失败'}")
    return 0 if failures == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
