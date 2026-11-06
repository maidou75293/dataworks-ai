# -*- coding: utf-8 -*-
"""capture-bi.py — REQ-OSS-README-DEMO-1 交互态补拍：BI 看板抽屉 + 数据查询结果。

与 capture-compose.py 同一制式（真跑真截、凭据零内置、DW_TOKEN_FILE 传入）。
两页都需要点击交互才有画面，故独立成脚本：
- bi-dashboard.png：/bi 点「周销趋势示例」行 → 抽屉渲染 ECharts → 截图
- query.png：/query 输入 SQL → 点「运行」 → 结果表格出现后截图
用法：DW_BASE=http://localhost:30090 DW_TOKEN_FILE=tmp/dwshots-token \
      python scripts/ci/readme-shots/capture-bi.py --out assets
"""
import os
import sys

from playwright.sync_api import sync_playwright

BASE = os.environ.get("DW_BASE", "http://localhost:30090")
TOKEN_FILE = os.environ.get("DW_TOKEN_FILE", "tmp/dwshots-token")
SQL_TEXT = "select 1 as ok, 'dataworks' as src"


def main():
    argv = sys.argv[1:]
    out = "assets"
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

        # 1) BI 看板：点行内「预览」开抽屉，等 canvas（ECharts）出现
        page.goto(BASE + "/bi", wait_until="networkidle", timeout=45000)
        page.wait_for_timeout(2000)
        try:
            row = page.locator("tr", has_text="demo_trend").first
            row.get_by_text("预览", exact=False).first.click(timeout=8000)
        except Exception:
            print("WARN bi: 「预览」未点到，退化为点行名")
            try:
                page.get_by_text("周销趋势示例").last.click(timeout=8000)
            except Exception as e2:
                print("WARN bi: 行名也点不动（%s），仍截图当前态" % type(e2).__name__)
        try:
            page.wait_for_selector("canvas", timeout=15000)
        except Exception:
            print("WARN bi: canvas 未出现，仍截图（如实）")
        page.wait_for_timeout(2500)
        page.screenshot(path=os.path.join(out, "bi-dashboard.png"))
        print("SHOT bi-dashboard.png")
        page.keyboard.press("Escape")
        page.wait_for_timeout(800)

        # 2) 数据查询：（可选）选执行位置数据源 → 输入 SQL → 点「运行」 → 等结果
        page.goto(BASE + "/query", wait_until="networkidle", timeout=45000)
        page.wait_for_timeout(2000)
        ds_name = os.environ.get("DW_QUERY_DS", "")
        if ds_name:
            # compose 形态「不选=平台内置 Doris」走的是 V98 烤死的 k8s DNS 行（bug 已上报），
            # 演示环境显式选一个可达数据源再跑
            try:
                # antd Select：点选择器本体，选项在挂 body 的下拉 portal 里
                page.locator(".ant-select-selector").first.click(timeout=5000)
                page.wait_for_timeout(600)
                page.locator(".ant-select-item-option-content",
                             has_text=ds_name).first.click(timeout=6000)
                page.wait_for_timeout(800)
            except Exception as e:
                print("WARN query: 选数据源 %s 未成（%s），按默认态跑" % (ds_name, type(e).__name__))
        ed = None
        for sel in (".monaco-editor textarea", "textarea.monaco-input",
                    "div.monaco-editor"):
            try:
                cand = page.locator(sel).first
                if cand.is_visible(timeout=1200):
                    ed = cand
                    break
            except Exception:
                pass
        if ed is not None:
            ed.click()
            page.keyboard.press("Control+A")
            page.keyboard.type(SQL_TEXT, delay=10)
        else:
            try:
                page.fill("textarea, input[type=text]", SQL_TEXT)
            except Exception:
                print("WARN query: 找不到输入面，仍截当前态")
        try:
            page.get_by_role("button", name="运行").first.click(timeout=5000)
        except Exception:
            print("WARN query: 「运行」按钮未点到，仍截当前态")
        page.wait_for_timeout(6000)
        page.screenshot(path=os.path.join(out, "query.png"))
        print("SHOT query.png")

        ctx.close()
        browser.close()
    print("CAPTURE_BI_DONE out=%s" % out)
    return 0


if __name__ == "__main__":
    sys.exit(main())
