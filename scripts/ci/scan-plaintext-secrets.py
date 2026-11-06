#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""开源清密判据（REQ-OSS-SEC-CLEAN-1）：HEAD 跟踪文件明文凭据扫描。

三条规则（任一命中 → 退出码 1）：
  R1 指纹登记：曾泄漏的真实凭据值（sha256 哈希登记，本脚本不含明文）。
     覆盖除台账/文档披露区以外的全部跟踪文件；值一旦以任何形态回仓即红。
  R2 字面口令：scripts/**、backend/src/main/**、agent-service/**、services/**、
     mos/**、web/src/** 里「口令关键字 = 静态字面量」形态（白名单=文档公开夹具）。
     backend/src/test/** 只受 R1（测试 mock 口令允许，真实泄漏值仍禁止回仓）。
  R3 内网拓扑：主代码目录（backend/src、agent-service、web、services、
     mos/platform/backend）不允许出现 RE_R3 定义的内网节点前缀（10·10·0 网段形）。
     豁免：``*.md``（披露文档走快照卡）、``db/migration/**``（Flyway 校验和冻结）。
     deploy/** 的内网 IP = 部署面口径，归镜像/清单卡（开源就绪审计 P0-1），不在本判据。

历史 blob（洗史禁）不归本判据——发布路线=新仓快照启史（docs/开源就绪审计-20261104.md §七③）。

用法：
  python -I scripts/ci/scan-plaintext-secrets.py            # 全仓扫描
  python -I scripts/ci/scan-plaintext-secrets.py --selftest  # 红能力自检（守卫要红得起来）
"""
import hashlib
import os
import re
import subprocess
import sys

# ---------------------------------------------------------------- R1 登记表
# 每项 = 曾驻留 HEAD / 历史 blob 的真实凭据之 sha256。生成方法见
# docs/evidence/req-oss-sec-clean-1/README.md（构建期一次性工具，用后即删，
# 明文从未进入仓库；登记表来源=改动前 HEAD blob + 1c56e554 历史 .env + 披露文档引用值）。
FINGERPRINTS = frozenset("""
0beb8694026903cc2f912d8a0cf71ad70550bfefe6f8b5f91366f583593be099 15a8c4b811f16ff6b5c54c0da0a93dae4636741ba0b5cf6c1dba0153e31de1af 166be01e625259c7ceb64a3859567b71d566373ab51540a8dbe5654e8bb0be49 1cc5e62538993d7d959ec6cc33b66f8e71936b4729b1c6a0cf2b0f5e4937902b
20ccba6d7ddf1c1ca24e15a06d1c39f857061db0f1974e285e634ec691af3f7b 238aba74fa62011337f2d8224fb16d2632586f297d5a1e3c988d29c46685c51d 2a5b88f022add32c604848af9c43ca2d688c0f04db716e025967605fdecef2cd 2c7da03235ef9062b27f3ed679ec61d0681fd7787e102b672f14eeb9d2221de9
3494f95cbc4851ea8eb8952406c1f12a111a422de97646ca28c8d5a7ca661c4b 52b588d5ac6e81abd20bf926a5a7a63abfb6053a7e00ec36487dcb25bce1c0aa 626af6ab9cae93c32fb57b65a92a1a3dd37f6274a59b6d15817321de1909b29b 7240e90b4248d8a4e4144c03f99eafaae8931112c6cb5ccf3823e1b64bcaff6c
8897a4c5aa2d92b87aa85796445c50011e620b67a2c268f7bee1746c5ddea237 9416ed7f1f8f9bb5f56ac61348c24bc0243c6d8194e1d69407d1c6df09e2e61d 9c9d0def0c4f7d0f6db4b6533c33183821b943864d209b662bdc2bcaccbfe305 a07e9f63a0d8a77ba7cc1605f777ae8b315408ca1a109bdd337c4eec5f65980a
afd4972d0cc14833b9833442734a2d9440d42bd4393d93bd3a0b1357189d6f43 b622c499136723d9d811497890c1dcd6282a792747e294979a7e7a9348418b78 b64b5315910c72f35310231804e44af722c6ab990ac42f8bb9598343973a79d2 b9b7d9ce82371907c21b989f85d3cdb417c1b29d1b9768745b6fb764746ae071
df5ba79c07b19662dd59a40631be1d0910003bf7e7c6f5c92189f83c52e89fdc ea19165280b835de8c0b294979077c9f85fa24cabc0c2c2a4ac94def5bd82a59 eae0b9c2de32611e41bad969cb2692781fc1bb08334caa8af3c21b5b272fc0a5 edcef45895451fc127d302842d5bbfe0fcb7ced7e2673b90f380b6b9d87ed0ac
ee58990e67235fe3c9868156735a2f7424fe4ecdbc0507f66c8b896c68a98a01 fbaa4271d368dfd7b45b7e487b08ac128e2635b8e386420531304b691c81e818
""".split())

# 公开夹具口令（文档内置演示值 + 各 e2e 自建一次性账号口令，允许驻留，不算泄漏）：
FIXTURE_PASSWORDS = {"test123", "mock-admin", "minioadmin", "dataworks",
                    "OldPass#2026", "NewPass#2026", "Hijack@12345", "Hijack@98765",
                    "leakpass123", "Round4@", "Udf012@"}

# ---------------------------------------------------------------- 扫描范围
R1_EXCLUDE_DIRS = ("docs/", "internal-mechanism/", "logs/", "tmp/", "build/")
R1_EXCLUDE_DOCS = ("docs/运维-凭据轮换清单-20261026.md",)  # 掩码后必须过 R1，特意纳入
R2_DIRS = ("scripts/", "backend/src/main/", "agent-service/", "services/",
           "mos/", "web/src/")
R3_DIRS = ("backend/src/", "agent-service/", "web/", "services/",
           "mos/platform/backend/")
R3_EXCLUDE = ("backend/src/main/resources/db/migration/",)

# R2 在 scripts/** 只对本卡清洗过的文件常驻回归（其余 e2e 的一次性夹具口令不在本卡范围，
# 其真实泄漏值仍受 R1 指纹覆盖）。清单=REQ-OSS-SEC-CLEAN-1 交卷时 git diff 的 scripts 文件。
SANITIZED_SCRIPTS = frozenset([
    "scripts/dev/release/d53-live.py",
    "scripts/e2e/accept-010-guard.py",
    "scripts/e2e/accept-a01-a18.py",
    "scripts/e2e/access-asset-folder-test.py",
    "scripts/e2e/access-audit-fix-test.py",
    "scripts/e2e/access-request-ui-test.py",
    "scripts/e2e/agent-system-rules-test.py",
    "scripts/e2e/asset-folder-acl-test.py",
    "scripts/e2e/audit-batch2-p1.py",
    "scripts/e2e/audit-p0p1.py",
    "scripts/e2e/audit-round3.py",
    "scripts/e2e/byid-tenant-audit.py",
    "scripts/e2e/change-cancel-void-test.py",
    "scripts/e2e/change-request-test.py",
    "scripts/e2e/change-rollback-test.py",
    "scripts/e2e/clickhouse-regression.py",
    "scripts/e2e/d01-tenant-boundary-test.py",
    "scripts/e2e/defect-p1-test.py",
    "scripts/e2e/defect-p2-test.py",
    "scripts/e2e/department-project-db-ui-test.py",
    "scripts/e2e/doris-regression.py",
    "scripts/e2e/e2e-round4.py",
    "scripts/e2e/ftp-regression.py",
    "scripts/e2e/gate-acceptance.py",
    "scripts/e2e/identity-p0-round2.py",
    "scripts/e2e/ime-input-test.py",
    "scripts/e2e/integration-p1-acceptance.py",
    "scripts/e2e/menu-ia-test.py",
    "scripts/e2e/mt-tenant-switch-test.py",
    "scripts/e2e/query-defects-test.py",
    "scripts/e2e/s3-incremental-regression.py",
    "scripts/e2e/s3file-regression.py",
    "scripts/e2e/security-trigger-regression.py",
    "scripts/e2e/sftp-regression.py",
    "scripts/e2e/udf-012-acceptance.py",
    "scripts/e2e/udf-test.py",
])

SELF = "scripts/ci/scan-plaintext-secrets.py"

# ---------------------------------------------------------------- 分词与规则
# 候选切分符：空白、引号、逗号、分号、括号、竖线、反引号——之外的连续段。
SPLIT_CHARS = set(" \t\r\n\"'`,;()[]{}|")
SUBSPLIT = re.compile(r"[=:|]")


def tokenize(text):
    """R1 候选集：极大段 ∪ 段内再按 = : | 的子段（覆盖 pwd=Xx#1 这类黏连形态）。"""
    out = set()
    cur = []
    for ch in text:
        if ch in SPLIT_CHARS:
            if cur:
                out.add("".join(cur))
                cur = []
        else:
            cur.append(ch)
    if cur:
        out.add("".join(cur))
    for tok in list(out):
        for part in SUBSPLIT.split(tok):
            if len(part) >= 6:
                out.add(part)
    return {t for t in out if len(t) >= 6}


def r1_hits(text):
    """返回 [(行号, 命中哈希前12位)]，不回显命中值本身（防扫描输出二次泄漏）。"""
    fp_lines = {}
    for lineno, line in enumerate(text.splitlines(), 1):
        for tok in tokenize(line):
            h = hashlib.sha256(tok.encode("utf-8")).hexdigest()
            if h in FINGERPRINTS:
                fp_lines[lineno] = h[:12]
    return sorted(fp_lines.items())


# 标识符（可带 JSON 引号键）= 静态字面量；键名小写含下列任一线索即视为凭据位。
RE_LITERAL = re.compile(r"""["']?([A-Za-z0-9_.\-]+)["']?\s*[:=]\s*["']([^"'\s]{6,})["']""")
KEY_HINTS = ("password", "passwd", "pwd", "secret", "access_key", "accesskey",
             "api_key", "apikey", "ls_token", "credential")
# 值里出现代码结构符/模板符 = 不是口令本体（拼串、生成器、命令）。
RE_DYN = re.compile("[(){}<>$=|%]")
ENV_NAME = re.compile(r"^[A-Z][A-Z0-9_]{2,}$")
RE_CONCAT = re.compile(r"\s*\+")


def r2_hits(text):
    out = []
    for lineno, line in enumerate(text.splitlines(), 1):
        if "${" in line or "os.environ" in line or "getenv" in line \
                or "secret.sh" in line:
            continue
        for m in RE_LITERAL.finditer(line):
            key = m.group(1).lower()
            if not any(h in key for h in KEY_HINTS):
                continue
            val = m.group(2)
            if val in FIXTURE_PASSWORDS or ENV_NAME.match(val):
                continue
            # 值必须是「口令形态」：含 ASCII 字母，且混过大写/数字/强符号之一；
            # 掩码符、模型名、纯小写单词不算凭据驻留。
            if not re.search(r"[A-Za-z]", val):
                continue
            if not (any(c.isupper() for c in val) or any(c.isdigit() for c in val)
                    or any(c in "@#$%^&*!+=~?" for c in val)):
                continue
            if RE_DYN.search(val) or val.startswith("<") or "your" in val \
                    or "changeme" in val or val in ("localhost", "cluster.local"):
                continue
            # 拼串/格式化形态（"...@" + TAG、%s、.format）= 动态，不算驻留
            if RE_CONCAT.match(line[m.end():]):
                continue
            out.append((lineno, m.group(1).lower()))
    return out


# REQ-OSS-PUBSCRUB-1 自引字面量改写：本扫描器随发布面出厂，展示文本用碎片拼
#（正则 RE_R3 的反斜杠转义形本就不含发布面禁串逐字面，维持原样）。
RE_R3 = re.compile(r"10\.10\.0\.")
R3_DISP = "10." + "10.0.*"


def r3_hits(text):
    return [(n, R3_DISP) for n, line in enumerate(text.splitlines(), 1)
            if RE_R3.search(line)]


def read_text(path):
    try:
        with open(path, "rb") as f:
            return f.read().decode("utf-8")
    except (UnicodeDecodeError, OSError):
        return None  # 二进制/不可读文件跳过


def in_scope(path, dirs, excludes=()):
    if not any(path.replace("\\", "/").startswith(d) for d in dirs):
        return False
    return not any(path.replace("\\", "/").startswith(e) for e in excludes)


def scan(repo_root=None):
    cwd = repo_root or os.path.dirname(os.path.dirname(os.path.dirname(
        os.path.abspath(__file__))))
    files = subprocess.run(["git", "ls-files", "-z"], cwd=cwd,
                           capture_output=True).stdout.decode("utf-8").split("\0")
    hits = []
    scanned = 0
    for rel in [f for f in files if f]:
        p = rel.replace("\\", "/")
        rules = []
        excluded1 = (p.startswith(R1_EXCLUDE_DIRS)
                     and p not in R1_EXCLUDE_DOCS)
        if not excluded1:
            rules.append("R1")
        if p != SELF and in_scope(p, R2_DIRS) and (
                not p.startswith("scripts/") or p in SANITIZED_SCRIPTS):
            rules.append("R2")
        if in_scope(p, R3_DIRS, R3_EXCLUDE) and not p.endswith(".md"):
            rules.append("R3")
        if not rules:
            continue
        full = os.path.join(cwd, rel)
        text = read_text(full)
        if text is None:
            continue
        scanned += 1
        if "R1" in rules:
            for ln, hh in r1_hits(text):
                hits.append(("R1-指纹", p, ln, hh))
        if "R2" in rules:
            for ln, kw in r2_hits(text):
                hits.append(("R2-字面口令", p, ln, kw))
        if "R3" in rules:
            for ln, _ in r3_hits(text):
                hits.append(("R3-内网IP", p, ln, R3_DISP))
    return hits, scanned


# ---------------------------------------------------------------- 红能力自检
def selftest():
    """每条规则都必须能红：合成夹具走纯函数判定，不落盘、不含真实凭据。"""
    failures = []
    fake = "Synthetic#FpTest-9"
    saved = set(FINGERPRINTS)
    fp_fake = hashlib.sha256(fake.encode()).hexdigest()
    # R1：黏连形态也要抓到（pwd=<值> 走子段切分）
    text = 'PASSWORD = "%s"' % fake
    # 临时注入合成指纹（frozenset 不可变，用全局替身重跑 r1_hits）
    try:
        globals()["FINGERPRINTS"] = saved | {fp_fake}
        if not r1_hits(text):
            failures.append("R1 常规赋值形态没有红")
        if not r1_hits("db pwd=%s; ok" % fake):
            failures.append("R1 黏连子段形态没有红")
        if r1_hits("PASSWORD = 'Abcdef#123'  # 未登记值"):
            failures.append("R1 对未登记值假红")
    finally:
        globals()["FINGERPRINTS"] = frozenset(saved)
    # R2：静态字面口令红；夹具/动态/env 不红
    if not r2_hits('CC_PASSWORD = "Xylo#Phone2026"'):
        failures.append("R2 静态字面口令没有红")
    if r2_hits('CC_PASSWORD = os.environ.get("E2E_PASS", "")'):
        failures.append("R2 对 env 取值假红")
    if r2_hits('PASSWORD = "test123"'):
        failures.append("R2 对公开夹具假红")
    if r2_hits('password = f"Acc{TAG}x9"'):
        failures.append("R2 对动态口令假红")
    if r2_hits('STATE["password"] = "Round4@" + TAG'):
        failures.append("R2 对拼串口令假红")
    # R3：主代码内网 IP 红；127.0.0.1 占位不红
    if not r3_hits("doris.host:" + R3_DISP.replace("*", "11")):
        failures.append("R3 内网 IP 没有红")
    if r3_hits("doris.host:127.0.0.1"):
        failures.append("R3 对占位假红")
    if failures:
        print("SELFTEST FAIL:")
        for f in failures:
            print("  - " + f)
        return 1
    print("SELFTEST PASS（R1/R2/R3 均可红、夹具与 env 不误伤）")
    return 0


def main():
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass
    if "--selftest" in sys.argv:
        return selftest()
    hits, scanned = scan()
    if hits:
        print("命中 %d 处（共扫描 %d 个跟踪文件）：" % (len(hits), scanned))
        for rule, path, ln, detail in hits:
            print("  %s  %s:%d  %s" % (rule, path, ln, detail))
        print("\n处置：口令类改 env（E2E_PASS/DW_PASSWORD/CC_*_PASS 契约），"
              "内网地址改占位并经部署侧注入；详见 docs/开源就绪审计-20261104.md §二P0")
        return 1
    print("PASS  开源清密判据通过：跟踪文件 %d 个，R1 指纹/R2 字面口令/R3 内网 IP 零命中"
          % scanned)
    return 0


if __name__ == "__main__":
    sys.exit(main())
