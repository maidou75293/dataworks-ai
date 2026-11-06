#!/usr/bin/env python3
# check-e2e-redo-report.py — E2E-REDO 卡面验收判据（项目负责人  定版，2026-11-03）。
# 判据语义定版（ 第3条）：只验「跑全+落册+字段齐全」，**红项多少不影响本卡转正**；
# 红项=逐项归因+当场立 BUG 卡（ 第2条），归因与卡号缺字段才是本判据的红。
# 齐全性硬断言（ 第2条）：A01..A18 + 契约 + 链1/链2/链3 逐号有结论，缺任何一号=不合格。
# 用法: python -I scripts/ci/check-e2e-redo-report.py [报告路径]   （缺省 docs/fulllink/E2E-REDO-REPORT.md）
#       python -I scripts/ci/check-e2e-redo-report.py --selftest   （红绿自检：好件过/坏件必红）
# 双侧形态（ 第4条）：纯 python 读文本，Win/WSL 同码可跑，不依赖 bash/Git。
import io, os, sys, tempfile

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

CODE = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
DEFAULT = os.path.join(CODE, "docs", "fulllink", "E2E-REDO-REPORT.md")
HEAD_COLS = ["用例号", "功能名", "输入", "期望输出", "实际输出", "判定", "失败归类", "bug卡号"]
IDS = ["A%02d" % i for i in range(1, 19)] + ["契约", "链1", "链2", "链3"]
VERDICTS = ("PASS", "FAIL", "SKIP")   # SKIP=环境不可达，同样必须归类=环境并给处置指针，不许空跳
CLS = ("产品BUG", "用例期望", "环境")


def check(path):
    """返回 errs 列表（空=绿）。逐号硬断言，拒绝『基本通过』。"""
    errs = []
    try:
        txt = io.open(path, encoding="utf-8").read()
    except OSError as exc:
        return ["报告读不到 %s: %r" % (path, exc)]
    rows = [l for l in txt.splitlines() if l.strip().startswith("|")]
    if not rows:
        return ["报告无表格（逐用例输出行缺失）"]
    hdr = [c.strip() for c in rows[0].strip().strip("|").split("|")]
    low = [c.lower() for c in hdr]
    col = {}
    for token in HEAD_COLS:
        hit = [i for i, c in enumerate(low) if token in c]
        if not hit:
            errs.append("表头缺列「%s」（实际=%s）" % (token, hdr))
        else:
            col[token] = hit[0]
    seen = {}
    for line in rows[1:]:
        if set(line.strip()) <= set("|-: "):
            continue
        cells = [c.strip() for c in line.strip().strip("|").split("|")]
        if not cells:
            continue
        cid = cells[0]
        if cid not in IDS:
            continue
        seen[cid] = line
        try:
            v = cells[col["判定"]].upper()
        except (KeyError, IndexError):
            errs.append("%s 判定列取不到" % cid)
            continue
        if v not in VERDICTS:
            errs.append("%s 判定非法: %r（只认 %s）" % (v and cid, cells[col["判定"]], "/".join(VERDICTS)))
            continue
        if v in ("FAIL", "SKIP"):
            try:
                k = cells[col["失败归类"]]
            except (KeyError, IndexError):
                errs.append("%s %s 缺失败归类" % (cid, v))
                continue
            if k not in CLS:
                errs.append("%s 失败归类非法: %r（只认 %s）" % (cid, k, "/".join(CLS)))
            elif k == "产品BUG":
                try:
                    b = cells[col["bug卡号"]]
                except (KeyError, IndexError):
                    errs.append("%s 产品BUG 缺 bug卡号列" % cid)
                    continue
                if not b or b in ("-", "—"):
                    errs.append("%s 产品BUG 未立卡（：测出即修，红一立一）" % cid)
    for cid in IDS:
        if cid not in seen:
            errs.append("缺号：%s 无结论行（齐全性硬断言）" % cid)
    return errs


def _mk(txt):
    fd, p = tempfile.mkstemp(suffix=".md", text=True)
    with os.fdopen(fd, "w", encoding="utf-8") as f:
        f.write(txt)
    return p


def selftest():
    hdr = "| " + " | ".join(HEAD_COLS) + " |"
    sep = "|" + "---|" * len(HEAD_COLS)
    good_rows = [hdr, sep]
    for i, cid in enumerate(IDS):
        v, k, b = ("PASS", "-", "-") if i % 2 == 0 else ("FAIL", "用例期望", "-")
        if cid == "链3":
            v, k, b = "FAIL", "产品BUG", "BUG-DEMO-1"
        good_rows.append("| %s | f | GET /x | 200 | 500 | %s | %s | %s |" % (cid, v, k, b))
    gp = _mk("\n".join(good_rows))
    e = check(gp)
    if e:
        print("SELFTEST-FAIL 好件被错杀:", e)
        return 1
    # 坏件1：缺 A18
    bad1 = "\n".join(r for r in good_rows if not r.startswith("| A18 "))
    # 坏件2：产品BUG 未立卡
    bad2 = "\n".join(r.replace("FAIL | 产品BUG | BUG-DEMO-1", "FAIL | 产品BUG | -")
                     for r in good_rows)
    # 坏件3：表头缺列
    bad3 = "\n".join(good_rows).replace("失败归类", "归因")
    for tag, txt in (("缺号", bad1), ("红项不立卡", bad2), ("缺列", bad3)):
        p = _mk(txt)
        e = check(p)
        if not e:
            print("SELFTEST-FAIL %s 坏件不红（假绿闸）" % tag)
            return 1
        print("  红检ok(%s): %s" % (tag, e[0][:60]))
    print("E2E_REDO_CHECK_SELFTEST_PASS")
    return 0


def main():
    args = sys.argv[1:]
    if "--selftest" in args:
        sys.exit(selftest())
    path = args[0] if args else DEFAULT
    errs = check(path)
    if errs:
        print("E2E-REDO 判据不满足（%d 条）:" % len(errs))
        for e in errs[:40]:
            print(" -", e)
        sys.exit(1)
    print("E2E-REDO 判据绿：22 号全有结论、字段齐全（报告=%s）" % path)


if __name__ == "__main__":
    main()
