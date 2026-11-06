# -*- coding: utf-8 -*-
"""capture-compose.py — REQ-OSS-README-DEMO-1 演示形态（compose）补拍：
登录页干净态、首页、数据开发列表/编辑器、数据查询。与 capture.py 同一制式：
真跑真截，凭据零内置（DW_TOKEN_FILE 传入）。
用法：DW_BASE=http://localhost:30090 DW_TOKEN_FILE=tmp/dwshots-token \
      python scripts/ci/readme-shots/capture-compose.py --out assets
"""
import os
import sys

from playwright.sync_api import sync_playwright

BASE = os.environ.get("DW_BASE", "http://localhost:30090")
TOKEN_FILE = os.environ.get("DW_TOKEN_FILE", "tmp/dwshots-token")

PAGES = [
    ("home.png", "/home", 2500),
    ("dev-list.png", "/dev", 2500),
    ("dev-editor.png", "/dev/1", 3000),
    ("query.png", "/query", 2500),
]


def main():
    out = "assets"
    argv = sys.argv[1:]
    if "--out" in argv:
        out = argv[argv.index("--out") + 1]
    with open(TOKEN_FILE) as f:
        token = f.read().strip()
    os.makedirs(out, exist_ok=True)
    with sync_playwright() as p:
        browser = p.chromium.launch(channel="msedge", headless=True)
        ctx = browser.new_context(viewport={"width": 1440, "height": 900},
                                  device_scale_factor=2)
        page = ctx.new_page()
        page.goto(BASE + "/login", wait_until="networkidle", timeout=45000)
        page.wait_for_timeout(1500)
        page.screenshot(path=os.path.join(out, "login.png"))
        page.goto(BASE + "/login", wait_until="domcontentloaded")
        page.evaluate(
            """(t) => {
                localStorage.setItem('dw_token', t);
                localStorage.setItem('dw_user', 'admin');
                localStorage.setItem('dw_role', 'ADMIN');
                localStorage.setItem('dw_display', 'admin');
            }""",
            token,
        )
        for name, route, settle in PAGES:
            try:
                page.goto(BASE + route, wait_until="networkidle", timeout=45000)
            except Exception:
                page.goto(BASE + route, wait_until="domcontentloaded", timeout=30000)
            page.wait_for_timeout(settle)
            page.screenshot(path=os.path.join(out, name))
            print("SHOT", name)
        ctx.close()
        browser.close()
    print("CAPTURE_COMPOSE_DONE out=%s" % out)
    return 0


if __name__ == "__main__":
    sys.exit(main())
