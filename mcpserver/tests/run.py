"""不装 pytest 也能跑回归用例：`python mcpserver/tests/run.py`。

把同目录 test_*.py 里的 test_* 函数挨个调用，打印结果，有失败就以非 0 退出。
装过 pytest 的话用 `pytest mcpserver/tests` 更好（有更细的失败输出）；
两条路径跑的是同一批用例，没有各自维护的断言。
"""
import importlib
import pathlib
import sys
import traceback

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))          # 让 `import support` 生效


def main() -> int:
    passed = failed = 0
    for path in sorted(HERE.glob("test_*.py")):
        mod = importlib.import_module(path.stem)
        for name in sorted(dir(mod)):
            fn = getattr(mod, name)
            if not name.startswith("test_") or not callable(fn):
                continue
            label = f"{path.stem}::{name}"
            try:
                fn()
            except Exception:  # noqa: BLE001 用例失败也要继续跑完其余的
                failed += 1
                print(f"FAIL {label}")
                traceback.print_exc()
            else:
                passed += 1
                print(f"ok   {label}")
    print(f"\n通过 {passed}，失败 {failed}")
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
