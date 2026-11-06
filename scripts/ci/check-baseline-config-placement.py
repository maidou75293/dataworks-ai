#!/usr/bin/env python3
"""REQ-DW-11 静态守卫：任务基线配置在任务详情，基线菜单页只读。

判据对齐卡面：
  ① 基线菜单页无编辑入口（无新建/编辑/删除 Modal、表单、Popconfirm，无写请求）；
  ② 任务配置区存在基线配置组件，且能完整发起 POST/PUT。

用法：
  python -I scripts/ci/check-baseline-config-placement.py            # 检查工作树
  python -I scripts/ci/check-baseline-config-placement.py --base DIR # 检查 DIR 下的副本（红检用）
退出码：0=全部通过，1=存在违反。
"""
import argparse
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

FAILS: list[str] = []


def read(base: Path, rel: str) -> str | None:
    p = base / rel
    if not p.exists():
        FAILS.append(f"缺文件 {rel}")
        return None
    return p.read_text(encoding="utf-8")


def must_absent(text: str | None, tokens: list[str], where: str) -> None:
    if text is None:
        return
    for t in tokens:
        if t in text:
            FAILS.append(f"{where}: 不应再出现 {t!r}")


def must_present(text: str | None, tokens: list[str], where: str) -> None:
    if text is None:
        return
    for t in tokens:
        if t not in text:
            FAILS.append(f"{where}: 缺少 {t!r}")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default=str(ROOT), help="待检查目录（含 web/、scripts/）")
    args = ap.parse_args()
    base = Path(args.base)

    # ① 基线菜单页：纯列表，无编辑入口
    page = read(base, "web/src/pages/Baselines.tsx")
    must_absent(page, [
        "新建基线", "编辑基线", "openCreate", "openEdit",
        "<Modal", "<Form", "Popconfirm",
        "put(", "del(",
    ], "Baselines.tsx")
    must_present(page, ["去任务配置", "/dev/"], "Baselines.tsx")

    # ② 任务编辑器右侧配置面板挂了基线配置组件
    editor = read(base, "web/src/pages/WorkflowEditor.tsx")
    must_present(editor, ["BaselineConfig"], "WorkflowEditor.tsx")

    # ③ 基线配置组件可完整设置（新建 POST / 修改 PUT + 关键字段）
    comp = read(base, "web/src/components/BaselineConfig.tsx")
    must_present(comp, [
        "post(", "put(", "承诺完成时间", "级别", "负责人", "保存基线",
    ], "BaselineConfig.tsx")

    if FAILS:
        for f in FAILS:
            print(f"FAIL {f}")
        print(f"RESULT: 基线配置落位守卫 违反 {len(FAILS)} 条")
        return 1
    print("PASS Baselines.tsx 无编辑入口（纯列表 + 跳转任务配置）")
    print("PASS WorkflowEditor.tsx 配置面板挂载 BaselineConfig")
    print("PASS BaselineConfig.tsx 具备完整新建/修改能力")
    print("RESULT: 基线配置落位守卫 3/3 PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
