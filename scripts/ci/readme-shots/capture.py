# -*- coding: utf-8 -*-
"""capture.py — REQ-OSS-README-DEMO-1 真实界面抓图（README 门面素材）。

真跑：对着活环境用 msedge headless（与 scripts/e2e/*-ui-test.py 同通道）逐页
渲染后截图，不摆拍不伪造——画面即环境真实状态。

凭据零内置：token 由环境变量 DW_TOKEN 传入（登录取 token 在调用侧完成，
密钥不落本脚本、不落 git）；DW_BASE 指定环境（默认集群完整形态）。

用法：
    DW_TOKEN=<token> python scripts/ci/readme-shots/capture.py [--out assets]
末行输出 CAPTURE_DONE n=<成功数> manifest=<路径>；失败页逐条点名。
"""
import json
import os
import sys

from playwright.sync_api import sync_playwright

# 演示形态默认 localhost；完整形态由调用方显式传 DW_BASE=<节点地址>:30090，
# 内网地址不进源码（发布面零内网 IP 纪律，同 G5）。
BASE = os.environ.get("DW_BASE", "http://localhost:30090")
TOKEN = os.environ.get("DW_TOKEN", "")

# (产出文件名, 路由, 稳定等待毫秒)——按 README「系统部分截图」节顺序
PAGES = [
    ("login.png", "/login", 1200),
    ("home.png", "/home", 2500),
    ("sql-ide.png", "/dev", 3000),
    ("integration.png", "/integration", 2500),
    ("scheduling.png", "/ops/instances", 2500),
    ("quality.png", "/quality", 2500),
    ("data-service.png", "/service", 2500),
    ("bi-dashboard.png", "/bi", 3000),
    ("stream.png", "/stream", 2500),
    ("labeling.png", "/mos/labels", 2500),
]


def main():
    argv = sys.argv[1:]
    out = "assets"
    if "--out" in argv:
        out = argv[argv.index("--out") + 1]
    global TOKEN
    if "--token-file" in argv:  # python -I 屏蔽 env 时的凭据通道（文件 600，gitignore）
        with open(argv[argv.index("--token-file") + 1]) as f:
            TOKEN = f.read().strip()
    if not TOKEN:
        print("FATAL: 需要 DW_TOKEN（登录取 token 后注入，本脚本不碰口令）")
        return 2
    os.makedirs(out, exist_ok=True)
    manifest = []
    with sync_playwright() as p:
        browser = p.chromium.launch(channel="msedge", headless=True)
        ctx = browser.new_context(viewport={"width": 1440, "height": 900},
                                  device_scale_factor=2)
        page = ctx.new_page()

        # 0) 登录页：未注入 token 的匿名态
        page.goto(BASE + "/login", wait_until="networkidle", timeout=45000)
        page.wait_for_timeout(1500)
        page.screenshot(path=os.path.join(out, "login.png"))
        manifest.append({"file": "login.png", "route": "/login", "anon": True})

        # 1) 注入会话（与 e2e UI 测试同制式的 localStorage 键）
        page.goto(BASE + "/login", wait_until="domcontentloaded")
        page.evaluate(
            """(t) => {
                localStorage.setItem('dw_token', t);
                localStorage.setItem('dw_user', 'admin');
                localStorage.setItem('dw_role', 'ADMIN');
                localStorage.setItem('dw_display', 'admin');
            }""",
            TOKEN,
        )
        for name, route, settle in PAGES:
            if name == "login.png":
                continue
            try:
                page.goto(BASE + route, wait_until="networkidle", timeout=45000)
            except Exception:
                page.goto(BASE + route, wait_until="domcontentloaded", timeout=30000)
            page.wait_for_timeout(settle)
            page.screenshot(path=os.path.join(out, name))
            manifest.append({"file": name, "route": route})
        ctx.close()
        browser.close()

    mpath = os.path.join(out, "capture-manifest.json")
    with open(mpath, "w", encoding="utf-8") as f:
        json.dump({"base_via": "live-render", "pages": manifest}, f,
                  ensure_ascii=False, indent=1)
    print("CAPTURE_DONE n=%d manifest=%s" % (len(manifest), mpath))
    return 0


if __name__ == "__main__":
    sys.exit(main())
