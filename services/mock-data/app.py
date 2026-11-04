"""模拟业务数据生成服务：用户 / 商品 / 订单。

- 不依赖第三方 Web 框架：标准库 http.server + requests（dw-runtime 已带）。
- 写 Doris 走 Stream Load（HTTP 8030），不占用 MySQL 连接，也不需要 pymysql。
- 表结构由「表管理」创建；本服务只负责造数和写入。

接口：
  GET  /health
  POST /seed?users=200&products=50&orders=1000   一次性造数（追加写）
  POST /stream?rate=5&seconds=60                 持续生成订单（后台线程）
  GET  /stats                                    本次进程写入计数

用法（集群内）：
  http://mock-data.dw-dev.svc.cluster.local:8090
"""
import json
import os
import random
import threading
import time
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import requests

DORIS_HTTP = os.environ.get("DORIS_HTTP", "http://doris-fe.dw-dev.svc.cluster.local:8030").rstrip("/")
DORIS_USER = os.environ.get("DORIS_USER", "dataworks")
DORIS_PASSWORD = os.environ.get("DORIS_PASSWORD", "")
DATABASE = os.environ.get("MOCK_DATABASE", "demo_retail")
PORT = int(os.environ.get("MOCK_PORT", "8090"))

CITIES = ["北京", "上海", "广州", "深圳", "杭州", "成都", "武汉", "西安"]
LEVELS = [1, 2, 3, 4, 5]
CATEGORIES = ["手机", "电脑", "家电", "服饰", "食品", "图书", "美妆", "运动"]
STATUSES = ["PAID", "PAID", "PAID", "SHIPPED", "COMPLETED", "CANCELLED"]

LOCK = threading.Lock()
# 最近一次 seed 的商品价格：流式生成订单时按商品定价，金额才和商品表一致
PRICES = {}
COUNTER = {"user": 0, "product": 0, "order": 0, "batches": 0, "failures": 0}
NEXT_ORDER_ID = [1]
STREAM_STOP = threading.Event()


def stream_load(table, rows):
    """把一批 JSON 行写入 Doris；返回 (ok, message)。"""
    if not rows:
        return True, "empty"
    label = f"mock_{table}_{int(time.time() * 1000)}_{random.randint(1000, 9999)}"
    url = f"{DORIS_HTTP}/api/{DATABASE}/{table}/_stream_load"
    body = json.dumps(rows, ensure_ascii=False).encode("utf-8")
    headers = {
        "format": "json",
        "strip_outer_array": "true",
        "label": label,
        "Expect": "100-continue",
    }
    try:
        # Doris 会把 Stream Load 307 到某个 BE。requests 跨主机重定向时会删掉
        # Authorization 头，BE 于是报 NOT_AUTHORIZED；必须手动跟随并重新带认证。
        resp = requests.put(url, data=body, headers=headers,
                            auth=(DORIS_USER, DORIS_PASSWORD), timeout=60,
                            allow_redirects=False)
        hops = 0
        while resp.status_code in (301, 302, 307, 308) and hops < 3:
            location = resp.headers.get("Location")
            if not location:
                break
            resp = requests.put(location, data=body, headers=headers,
                                auth=(DORIS_USER, DORIS_PASSWORD), timeout=60,
                                allow_redirects=False)
            hops += 1
        try:
            result = resp.json()
        except Exception:  # noqa: BLE001
            result = {"Status": "Fail", "Message": resp.text[:300]}
        if resp.status_code >= 300 or result.get("Status") not in ("Success", "Publish Timeout"):
            return False, f"{resp.status_code} {result.get('Status')} {result.get('Message')}"
        return True, result.get("Status", "")
    except Exception as e:  # noqa: BLE001
        return False, str(e)


def gen_users(n):
    rows = []
    for i in range(n):
        rows.append({
            "user_id": i + 1,
            "name": f"user_{i + 1:05d}",
            "city": random.choice(CITIES),
            "level": random.choice(LEVELS),
            "created_at": time.strftime("%Y-%m-%d %H:%M:%S",
                                        time.localtime(time.time() - random.randint(0, 86400 * 30))),
        })
    return rows


def gen_products(n):
    rows = []
    for i in range(n):
        category = random.choice(CATEGORIES)
        rows.append({
            "product_id": i + 1,
            "name": f"{category}_{i + 1:04d}",
            "category": category,
            "price": round(random.uniform(9.9, 8999.0), 2),
            "created_at": time.strftime("%Y-%m-%d %H:%M:%S",
                                        time.localtime(time.time() - random.randint(0, 86400 * 60))),
        })
    return rows


def gen_orders(n, user_max, product_max, price_by_product):
    rows = []
    with LOCK:
        start = NEXT_ORDER_ID[0]
        NEXT_ORDER_ID[0] += n
    for i in range(n):
        user_id = random.randint(1, max(1, user_max))
        product_id = random.randint(1, max(1, product_max))
        qty = random.randint(1, 5)
        price = price_by_product.get(product_id, round(random.uniform(10, 500), 2))
        rows.append({
            "order_id": start + i,
            "user_id": user_id,
            "product_id": product_id,
            "qty": qty,
            "amount": round(price * qty, 2),
            "status": random.choice(STATUSES),
            "order_time": time.strftime("%Y-%m-%d %H:%M:%S"),
        })
    return rows


def seed(users, products, orders):
    results = {}
    user_rows = gen_users(users)
    ok, msg = stream_load("ods_user", user_rows)
    results["ods_user"] = {"ok": ok, "rows": len(user_rows), "message": msg}

    product_rows = gen_products(products)
    ok, msg = stream_load("ods_product", product_rows)
    results["ods_product"] = {"ok": ok, "rows": len(product_rows), "message": msg}

    prices = {row["product_id"]: row["price"] for row in product_rows}
    with LOCK:
        PRICES.clear()
        PRICES.update(prices)
    order_rows = gen_orders(orders, users, products, prices)
    ok, msg = stream_load("ods_order", order_rows)
    results["ods_order"] = {"ok": ok, "rows": len(order_rows), "message": msg}

    with LOCK:
        COUNTER["user"] += len(user_rows)
        COUNTER["product"] += len(product_rows)
        COUNTER["order"] += len(order_rows)
        COUNTER["batches"] += 3
        COUNTER["failures"] += sum(0 if r["ok"] else 1 for r in results.values())
    return results


def stream_worker(rate, seconds, user_max, product_max, prices):
    """持续生成订单：按 rate 条/秒，最多 seconds 秒。"""
    STREAM_STOP.clear()
    deadline = time.time() + seconds
    while time.time() < deadline and not STREAM_STOP.is_set():
        rows = gen_orders(rate, user_max, product_max, prices)
        ok, msg = stream_load("ods_order", rows)
        with LOCK:
            if ok:
                COUNTER["order"] += len(rows)
                COUNTER["batches"] += 1
            else:
                COUNTER["failures"] += 1
        time.sleep(1)
    STREAM_STOP.set()


class Handler(BaseHTTPRequestHandler):
    server_version = "dw-mock-data/1.0"

    def log_message(self, fmt, *args):  # noqa: A003
        print("%s - %s" % (self.address_string(), fmt % args), flush=True)

    def send_json(self, code, payload):
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def query(self):
        parsed = urllib.parse.urlparse(self.path)
        return parsed.path, urllib.parse.parse_qs(parsed.query)

    def do_GET(self):  # noqa: N802
        path, _ = self.query()
        if path == "/health":
            self.send_json(200, {"status": "ok", "database": DATABASE})
        elif path == "/stats":
            with LOCK:
                self.send_json(200, dict(COUNTER))
        else:
            self.send_json(404, {"message": "not found"})

    def do_POST(self):  # noqa: N802
        path, params = self.query()
        try:
            if path == "/seed":
                users = int(params.get("users", ["200"])[0])
                products = int(params.get("products", ["50"])[0])
                orders = int(params.get("orders", ["1000"])[0])
                if not (0 <= users <= 20000 and 0 <= products <= 5000 and 0 <= orders <= 100000):
                    raise ValueError("参数超出范围")
                results = seed(users, products, orders)
                code = 200 if all(r["ok"] for r in results.values()) else 502
                self.send_json(code, {"database": DATABASE, "result": results})
            elif path == "/stream":
                rate = int(params.get("rate", ["5"])[0])
                seconds = int(params.get("seconds", ["60"])[0])
                users = int(params.get("users", ["200"])[0])
                products = int(params.get("products", ["50"])[0])
                if not (1 <= rate <= 200 and 1 <= seconds <= 3600):
                    raise ValueError("rate 1-200，seconds 1-3600")
                # 用最近一次 seed 的商品价格；没 seed 过就用默认价
                with LOCK:
                    prices = dict(PRICES)
                t = threading.Thread(target=stream_worker,
                                     args=(rate, seconds, users, products, prices), daemon=True)
                t.start()
                self.send_json(200, {"started": True, "rate": rate, "seconds": seconds})
            elif path == "/stream/stop":
                STREAM_STOP.set()
                self.send_json(200, {"stopped": True})
            else:
                self.send_json(404, {"message": "not found"})
        except Exception as e:  # noqa: BLE001
            self.send_json(400, {"message": str(e)})


if __name__ == "__main__":
    print(f"mock-data listening :{PORT} -> {DORIS_HTTP}/{DATABASE}", flush=True)
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
