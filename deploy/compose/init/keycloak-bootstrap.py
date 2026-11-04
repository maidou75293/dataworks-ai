#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""一次性：Keycloak realm + 两个 client 初始化（REQ-OSS-QUICKSTART-1 compose 形态）。

与 deploy/tasks/55-keycloak.yml 同语义、幂等：realm 已存在就跳过，client 按 clientId 查重。
只用标准库（容器 = python:3.12-alpine），跑完即退。口令/密钥全走环境变量，不打印。
"""
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

KEYCLOAK_URL = os.environ.get("KEYCLOAK_URL", "http://keycloak:8080").rstrip("/")
ADMIN_USER = os.environ.get("KEYCLOAK_ADMIN_USER", "")
ADMIN_PASSWORD = os.environ.get("KEYCLOAK_ADMIN_PASSWORD", "")
MCP_SECRET = os.environ.get("KEYCLOAK_MCP_CLIENT_SECRET", "")
DEMO_ORIGIN = os.environ.get("DEMO_ORIGIN", "http://localhost:30090")
REALM = "dataworks"
TIMEOUT_TOTAL = 600


def req(method, url, data=None, headers=None, form=None):
    body = None
    hd = dict(headers or {})
    if data is not None:
        body = json.dumps(data).encode("utf-8")
        hd["Content-Type"] = "application/json"
    elif form is not None:
        body = urllib.parse.urlencode(form).encode("utf-8")
        hd["Content-Type"] = "application/x-www-form-urlencoded"
    r = urllib.request.Request(url, data=body, headers=hd, method=method)
    try:
        with urllib.request.urlopen(r, timeout=10) as resp:
            raw = resp.read()
            return resp.status, (json.loads(raw) if raw else None)
    except urllib.error.HTTPError as e:
        raw = e.read()
        try:
            return e.code, (json.loads(raw) if raw else None)
        except Exception:
            return e.code, None


def log(msg):
    print("[keycloak-bootstrap] %s" % msg, flush=True)


def wait_up():
    deadline = time.time() + TIMEOUT_TOTAL
    while time.time() < deadline:
        try:
            code, _ = req("GET", KEYCLOAK_URL + "/realms/master")
            if code == 200:
                return True
        except Exception:
            pass
        time.sleep(5)
    log("超时：Keycloak %s 秒内没有起来（看 compose logs keycloak）" % TIMEOUT_TOTAL)
    return False


def admin_token():
    deadline = time.time() + TIMEOUT_TOTAL
    while time.time() < deadline:
        code, js = req("POST", KEYCLOAK_URL + "/realms/master/protocol/openid-connect/token",
                       form={"grant_type": "password", "client_id": "admin-cli",
                             "username": ADMIN_USER, "password": ADMIN_PASSWORD})
        if code == 200 and js and js.get("access_token"):
            return js["access_token"]
        time.sleep(5)
    log("超时：拿不到 master 管理员令牌（检查 KEYCLOAK_ADMIN_USER/PASSWORD 是否被改过）")
    return None


def main():
    if not wait_up():
        return 1
    token = admin_token()
    if not token:
        return 1
    auth = {"Authorization": "Bearer " + token}

    # realm
    code, _ = req("GET", "%s/admin/realms/%s" % (KEYCLOAK_URL, REALM), headers=auth)
    if code == 404:
        code, _ = req("POST", KEYCLOAK_URL + "/admin/realms", headers=auth, data={
            "realm": REALM, "enabled": True, "displayName": "DataWorks", "sslRequired": "none"})
        log("realm %s 创建：HTTP %s" % (REALM, code))
    elif code == 200:
        log("realm %s 已存在，跳过" % REALM)
    else:
        log("realm 查询异常 HTTP %s" % code)
        return 1

    def client_exists(cid):
        code, js = req("GET", "%s/admin/realms/%s/clients?clientId=%s"
                       % (KEYCLOAK_URL, REALM, urllib.parse.quote(cid)), headers=auth)
        return code == 200 and isinstance(js, list) and len(js) > 0

    if not client_exists("dataworks-web"):
        code, _ = req("POST", "%s/admin/realms/%s/clients" % (KEYCLOAK_URL, REALM), headers=auth, data={
            "clientId": "dataworks-web", "protocol": "openid-connect",
            "publicClient": True, "standardFlowEnabled": True, "directAccessGrantsEnabled": True,
            "rootUrl": DEMO_ORIGIN, "baseUrl": DEMO_ORIGIN,
            "redirectUris": [DEMO_ORIGIN + "/*"], "webOrigins": ["+", DEMO_ORIGIN]})
        log("client dataworks-web 创建：HTTP %s" % code)
    else:
        log("client dataworks-web 已存在，跳过")

    if not client_exists("mcp-gateway"):
        code, _ = req("POST", "%s/admin/realms/%s/clients" % (KEYCLOAK_URL, REALM), headers=auth, data={
            "clientId": "mcp-gateway", "protocol": "openid-connect",
            "publicClient": False, "secret": MCP_SECRET,
            "serviceAccountsEnabled": True, "standardFlowEnabled": False,
            "directAccessGrantsEnabled": False})
        log("client mcp-gateway 创建：HTTP %s" % code)
    else:
        log("client mcp-gateway 已存在，跳过")

    if code not in (201, 409) and not client_exists("mcp-gateway"):
        return 1
    log("完成")
    return 0


if __name__ == "__main__":
    sys.exit(main())
