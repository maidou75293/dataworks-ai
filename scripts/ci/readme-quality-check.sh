#!/usr/bin/env bash
# readme-quality-check.sh — REQ-OSS-README-DEMO-1 README 门面三件套质量闸
#
# 判据集合 = docs/开源-README标杆拆解-20261105.md §四 改造清单映射：
#   G1 拆解笔记在册（先学再写的凭据）
#   G2 英文门面结构（徽章栏/定位句/首屏图/## Quick start 一条命令+去哪打开/截图节/坑点/License）
#   G3 双语互链（README.md <-> README-zh.md，中文面各段齐）
#   G4 assets 引用闭环（被引用的图存在、是真图非纯色占位；assets 不留孤儿图）
#   G5 敏感面扫描（门面文件零内网 IP/集群 DNS/真实账号形态）
# 全绿末行 README_QUAL_PASS；任一红列明点位。可传仓库根为 $1（默认当前根）。
set -u
ROOT="$(cd "${1:-$(git rev-parse --show-toplevel 2>/dev/null || echo .)}" && pwd)"
PY="$(command -v python || command -v python3)"
[ -n "$PY" ] || { echo "README_QUAL_FAIL: python 不可用"; exit 1; }
FAIL=0
red() { echo "FAIL: $1"; FAIL=1; }
warn() { echo "WARN: $1"; }
ok()  { echo "  ok: $1"; }

echo "== G1 拆解笔记在册 =="
NOTE="$ROOT/docs/开源-README标杆拆解-20261105.md"
if [ ! -f "$NOTE" ]; then
  red "G1 拆解笔记缺失（$NOTE）——先学再写的凭据不在册"
else
  "$PY" -I -X utf8 - "$NOTE" <<'PYEOF' || FAIL=1
import sys
t = open(sys.argv[1], encoding="utf-8").read()
miss = [k for k in ("ollama", "supabase", "n8n", "dolphinscheduler", "共性", "改造清单")
        if k.lower() not in t.lower()]
if miss:
    print("FAIL: G1 笔记缺关键点: %s" % "、".join(miss)); sys.exit(1)
print("  ok: 笔记在册，样本四件+共性+改造清单齐")
PYEOF
fi

echo "== G2 英文门面结构（README.md 首屏） =="
"$PY" -I -X utf8 - "$ROOT/README.md" <<'PYEOF' || FAIL=1
import sys
p = sys.argv[1]
lines = open(p, encoding="utf-8").read().splitlines()
apx = next((i for i, l in enumerate(lines) if l.startswith("## 附录")), None)
front = lines[:apx] if apx is not None else lines
nonblank = [l for l in front if l.strip()]
def has_cjk(s):
    return any(0x3000 <= ord(c) <= 0x30ff or 0x3400 <= ord(c) <= 0x9fff
               or 0xff00 <= ord(c) <= 0xffef or 0xf900 <= ord(c) <= 0xfaff for c in s)
bad = 0
def need(cond, msg):
    global bad
    if cond: print("  ok: " + msg)
    else: print("FAIL: " + msg); bad = 1
if not nonblank:
    print("FAIL: README.md 空"); sys.exit(1)
low = [l.lower() for l in nonblank]
joined = "\n".join(nonblank)
joined_low = joined.lower()
need(sum(1 for l in nonblank[:20] if '<img alt=' in l or 'img.shields.io' in l) >= 2,
     "徽章栏（前 20 行 >=2 枚徽章图）")
pos = [i for i, l in enumerate(nonblank[:30]) if l.startswith(">") and "data platform" in l.lower()]
need(bool(pos), "一句定位（标题下 30 行内的 blockquote 含 'data platform'）")
hero = [i for i, l in enumerate(nonblank[:40]) if '](assets/' in l or 'src="assets/' in l]
need(bool(hero), "首屏真实截图（前 40 个非空行内引用 assets/）")
need(any(l.startswith("## quick start") for l in low), "## Quick start 分区")
qstart = low.index([l for l in low if l.startswith("## quick start")][0]) if any(
    l.startswith("## quick start") for l in low) else None
if qstart is not None:
    seg = "\n".join(nonblank[qstart:qstart + 40]).lower()
    need("up.sh" in seg, "Quick start 一条命令（段内含 up.sh）")
    need("localhost:" in seg, "Quick start 给「去哪打开」（段内含 localhost:）")
need(any(l.startswith("## screenshots") for l in low), "## Screenshots 分区")
shot_sec = joined[joined_low.find("## screenshots"):].lower() if "## screenshots" in joined_low else ""
n_imgs = shot_sec.count("](assets/")
need(n_imgs >= 5, "截图节逐页在册（assets 引用 >=5，实得 %d）" % n_imgs)
need(sum(1 for l in nonblank if l.strip().startswith("|")) >= 5, "关键数字表（| 行 >=5）")
need(any(l.startswith("## pitfalls") for l in low), "## Pitfalls 分区")
need("## license" in joined_low and "mit" in joined_low.split("## license")[-1][:400],
     "License 段含 MIT")
cjk = [i + 1 for i, l in enumerate(front) if l.strip() and has_cjk(l)]
need(not cjk, "首屏零 CJK（附录前）%s" % ("" if not cjk else "，违例行: %s" % cjk[:6]))
sys.exit(bad)
PYEOF

echo "== G3 双语互链 =="
ZH="$ROOT/README-zh.md"
if [ ! -f "$ZH" ]; then
  red "G3 中文门面 README-zh.md 不存在"
else
  "$PY" -I -X utf8 - "$ROOT/README.md" "$ZH" <<'PYEOF' || FAIL=1
import sys, re
en = open(sys.argv[1], encoding="utf-8").read()
zh = open(sys.argv[2], encoding="utf-8").read()
bad = 0
def need(cond, msg):
    global bad
    if cond: print("  ok: " + msg)
    else: print("FAIL: " + msg); bad = 1
need("README-zh.md" in en, "英文门面链向中文册（README-zh.md）")
need(re.search(r"\]\(README\.md\)|href=\"README\.md\"", zh) is not None, "中文册链回英文门面")
need(zh.count("assets/") >= 6, "中文册截图 >=6 张（实得 %d）" % zh.count("assets/"))
for k in ("Quick start", "30 秒"):
    pass
need("30 秒" in zh or "一条命令" in zh, "中文册 30 秒上手段")
need("MIT" in zh and "许可证" in zh, "中文册许可段（MIT）")
need("坑点" in zh or "已知未修" in zh, "中文册坑点/诚实边界段")
sys.exit(bad)
PYEOF
fi

echo "== G4 assets 引用闭环 + 非占位 =="
"$PY" -I -X utf8 - "$ROOT" <<'PYEOF' || FAIL=1
import os, re, sys
root = sys.argv[1]
refs = set()
for f in ("README.md", "README-zh.md"):
    text = open(os.path.join(root, f), encoding="utf-8").read()
    refs |= set(re.findall(r"(?:src=\"|\]\()(assets/[^)\"]+)", text))
bad = 0
def need(cond, msg):
    global bad
    if cond: print("  ok: " + msg)
    else: print("FAIL: " + msg); bad = 1
need(len(refs) >= 5, "门面合计引用 >=5 张图（实得 %d）" % len(refs))
for r in sorted(refs):
    p = os.path.join(root, r)
    if not os.path.isfile(p):
        print("FAIL: 引用缺文件 %s" % r); bad = 1
        continue
    sz = os.path.getsize(p)
    if sz < 20000:
        print("FAIL: 图过小疑似占位 %s (%d B)" % (r, sz)); bad = 1
        continue
    try:
        from PIL import Image
        im = Image.open(p).convert("RGB")
        w, h = im.size
        if w < 800 or h < 500:
            print("FAIL: 分辨率过低 %s (%dx%d)" % (r, w, h)); bad = 1
        else:
            small = im.resize((80, 50))
            colors = small.getcolors(maxcolors=100000)
            n = len(colors)
            if n < 8:
                print("FAIL: 纯色/占位嫌疑 %s（缩略色彩数 %d）" % (r, n)); bad = 1
            else:
                print("  ok: %s %dx%d 色彩 %d 非占位" % (r, w, h, n))
    except ImportError:
        print("WARN: PIL 不可用，跳过非占位色检（%s）" % r)
adir = os.path.join(root, "assets")
if os.path.isdir(adir):
    orphans = [x for x in os.listdir(adir) if x.endswith(".png")
               and ("assets/" + x) not in refs]
    need(not orphans, "assets 无孤儿图（未被任一 README 引用即删）%s"
         % ("" if not orphans else "，孤儿: %s" % orphans))
sys.exit(bad)
PYEOF

echo "== G5 敏感面扫描（门面件 + 笔记 + 台账） =="
"$PY" -I -X utf8 - "$ROOT" <<'PYEOF' || FAIL=1
import os, re, sys
root = sys.argv[1]
files = ["README.md", "README-zh.md", "assets/capture-manifest.json",
         "docs/开源-README标杆拆解-20261105.md"]
# 禁串按碎片拼法写（同 oss-snapshot._SUBS 纪律）：闸体自身不落可命中的字面量
D, U = chr(45), chr(95)
pats = [
    (r"10\.\d+\.\d+\.\d+", "内网 IP 段"),
    ("svc" + chr(92) + ".cluster" + chr(92) + ".local", "集群内部 DNS"),
    ("dw" + D + "dev", "集群命名空间形态"),
    ("dataworks" + D + "ai" + D + "4", "会话实名形态"),
    ("wx" + "id" + U, "私人微信触点形态"),
    (r"[A-Za-z0-9._%+-]+@(qq|163|gmail|outlook|foxmail)\.", "真实邮箱形态"),
    ("mai" + "dou", "真实 GitHub 账号"),
    (r"(PASSWORD|passwd)\s*=\s*\S", "明文口令形态"),
]
bad = 0
for f in files:
    p = os.path.join(root, f)
    if not os.path.isfile(p):
        print("FAIL: G5 点名文件缺失 %s" % f); bad = 1; continue
    text = open(p, encoding="utf-8").read()
    for pat, why in pats:
        hits = [i + 1 for i, l in enumerate(text.splitlines())
                if re.search(pat, l, re.IGNORECASE)]
        if hits:
            print("FAIL: G5 %s 命中[%s] 行 %s" % (f, why, hits[:5])); bad = 1
print("  ok: G5 门面/笔记/台账零敏感命中" if not bad else "")
sys.exit(bad)
PYEOF

if [ "$FAIL" -eq 0 ]; then
  echo "README_QUAL_PASS"
  exit 0
else
  echo "README_QUAL_FAIL"
  exit 1
fi
