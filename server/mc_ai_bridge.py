#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""mc_ai_bridge.py — Minecraft 插件 <-> AI Agent 的中转（参考实现）

职责：
  1. 接收插件的 /mc/message
       mode=chat   ：公屏消息 -> 转发给 Agent，立即返回（Agent 想说话必须调 mc_say 工具）
       mode=direct ：/agi 私聊 -> 独立会话，以 NDJSON 流式把回答回传给插件
  2. 维护 实例 -> 插件端口 的注册表，给 MCP 工具 mc_say 用

环境变量：
  NEXAGI_GATEWAY      Agent 网关（默认 http://127.0.0.1:8642）
  NEXAGI_API_KEY      网关 Key（默认空）
  NEXAGI_MODEL        模型名（默认 local-model）
  NEXAGI_BRIDGE_PORT  监听端口（默认 8899）
  NEXAGI_HOME         数据目录（默认 ~/.nexagi），放 mc_ports.json 与 bridge.log
  NEXAGI_SLUGS        可选，实例名->会话 slug 的 JSON 映射

只用 Python 标准库。
"""
import datetime
import hashlib
import json
import os
import sys
import threading
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

HERMES = os.environ.get("NEXAGI_GATEWAY", "http://127.0.0.1:8642").rstrip("/")
KEY = os.environ.get("NEXAGI_API_KEY", "")
MODEL = os.environ.get("NEXAGI_MODEL", "local-model")
LISTEN_PORT = int(os.environ.get("NEXAGI_BRIDGE_PORT", "8899"))

HOME = os.environ.get("NEXAGI_HOME") or os.path.join(os.path.expanduser("~"), ".nexagi")
PORTS_FILE = os.path.join(HOME, "mc_ports.json")
LOG = os.path.join(HOME, "bridge.log")

try:
    SLUGS = json.loads(os.environ.get("NEXAGI_SLUGS") or "{}")
except Exception:
    SLUGS = {}

_locks = {}
_lock_guard = threading.Lock()


def log(msg):
    line = "[%s] %s" % (datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S"), msg)
    print(line, flush=True)
    try:
        os.makedirs(HOME, exist_ok=True)
        with open(LOG, "a", encoding="utf-8") as f:
            f.write(line + "\n")
    except Exception:
        pass


def session_id(instance):
    """实例名 -> ASCII 会话 id（HTTP 头不能放中文）"""
    slug = SLUGS.get(instance)
    if not slug:
        ok = "".join(c for c in instance if c.isascii() and (c.isalnum() or c in "-_"))
        slug = ok if ok and ok == instance else "s" + hashlib.md5(instance.encode("utf-8")).hexdigest()[:6]
    return "mc-" + slug


def _session_lock(sid):
    with _lock_guard:
        return _locks.setdefault(sid, threading.Lock())


def remember_port(instance, port):
    """给 mc_say 工具留一份 实例 -> 本地端口 的路由表"""
    try:
        os.makedirs(HOME, exist_ok=True)
        data = {}
        if os.path.exists(PORTS_FILE):
            try:
                with open(PORTS_FILE, "r", encoding="utf-8") as f:
                    data = json.load(f) or {}
            except Exception:
                data = {}
        data[str(instance)] = {"port": int(port),
                              "updated": datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S")}
        data["_last"] = str(instance)
        with open(PORTS_FILE, "w", encoding="utf-8") as f:
            json.dump(data, f, ensure_ascii=False, indent=2)
    except Exception as e:
        log("写端口表失败: %s" % e)


def _payload(user_text, stream):
    return {
        "model": MODEL,
        "messages": [{"role": "user", "content": user_text}],
        "temperature": 1.0,
        "stream": stream,
    }


def _request(body, sid, stream):
    headers = {
        "Content-Type": "application/json",
        "Authorization": "Bearer " + KEY,
        "X-Hermes-Session-Id": sid,
    }
    if stream:
        headers["Accept"] = "text/event-stream"
    return urllib.request.Request(
        HERMES + "/v1/chat/completions",
        data=json.dumps(body, ensure_ascii=False).encode("utf-8"),
        headers=headers,
    )


def call_hermes(user_text, sid):
    """普通（非流式）调用，返回回答正文；失败返回空串"""
    try:
        req = _request(_payload(user_text, False), sid, False)
        with urllib.request.urlopen(req, timeout=900) as r:
            out = r.read().decode("utf-8", "replace")
        try:
            return json.loads(out)["choices"][0]["message"]["content"] or ""
        except Exception:
            return out[:500]
    except urllib.error.HTTPError as e:
        log("上游 %d: %s" % (e.code, e.read().decode("utf-8", "replace")[:300]))
        return ""
    except Exception as e:
        log("上游异常: %s" % e)
        return ""


def stream_hermes(user_text, sid, emit):
    """流式调用：每段文字回调 emit(piece)，返回完整文本"""
    body = _payload(user_text, True)
    # /agi 是"直接出正文"，尽量不给工具；网关不认就退回普通请求
    body["tools"] = []
    body["tool_choice"] = "none"
    try:
        resp = urllib.request.urlopen(_request(body, sid, True), timeout=900)
    except urllib.error.HTTPError as e:
        if e.code in (400, 422):
            log("/agi 关工具被拒（%d），退回普通请求" % e.code)
            body.pop("tools", None)
            body.pop("tool_choice", None)
            resp = urllib.request.urlopen(_request(body, sid, True), timeout=900)
        else:
            raise

    acc = []
    with resp as r:
        ctype = (r.headers.get("Content-Type") or "").lower()
        if "event-stream" not in ctype:
            # 上游没给流：一次性吐出来，插件照样会逐字打
            text = r.read().decode("utf-8", "replace")
            try:
                text = json.loads(text)["choices"][0]["message"]["content"] or ""
            except Exception:
                text = text[:500]
            if text:
                acc.append(text)
                emit(text)
            return "".join(acc)

        for raw in r:
            line = raw.decode("utf-8", "replace").strip()
            if not line or not line.startswith("data:"):
                continue
            data = line[5:].strip()
            if data == "[DONE]":
                break
            try:
                obj = json.loads(data)
                piece = obj["choices"][0]["delta"].get("content") or ""
            except Exception:
                piece = ""
            if piece:
                acc.append(piece)
                emit(piece)
    return "".join(acc)


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *a):
        pass

    def do_GET(self):
        if self.path.startswith("/health"):
            self._json(200, {"ok": True, "session_home": HOME, "ports": self._ports()})
        else:
            self._json(404, {"error": "not found"})

    def _ports(self):
        try:
            with open(PORTS_FILE, "r", encoding="utf-8") as f:
                return json.load(f)
        except Exception:
            return {}

    def do_POST(self):
        n = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(n).decode("utf-8", "replace")
        try:
            body = json.loads(raw or "{}")
        except Exception:
            return self._json(400, {"error": "bad json"})
        if not self.path.startswith("/mc/message"):
            return self._json(404, {"error": "not found"})
        return self._handle_mc(body)

    def _handle_mc(self, body):
        instance = str(body.get("instance") or "server")
        player = str(body.get("player") or "").strip() or "unknown"
        text = str(body.get("text") or "").strip() or "（空消息）"
        mode = str(body.get("mode") or "chat")
        port = body.get("port")
        op = body.get("op")
        if isinstance(op, bool):
            op = "是" if op else "否"
        op = str(op or "未知")

        if port:
            remember_port(instance, port)

        sid = session_id(instance)
        log("收到 [%s] %s op=%s mode=%s 端口=%s 内容=%s"
            % (instance, player, op, mode, port, text[:80]))

        # ---- /agi 私聊：独立会话 + NDJSON 流式 ----
        if mode == "direct":
            user_text = ("[MC-%s] [玩家: %s] [OP: %s] 用 /agi 私聊你，说：%s\n"
                         "（这条要直接回答：只输出你要说的话，一两句，别调用任何工具"
                         "——不要用 mc_say、不要用任何发消息工具；"
                         "你的正文会被插件自动私聊发给他本人）" % (instance, player, op, text))
            self.send_response(200)
            self.send_header("Content-Type", "application/x-ndjson; charset=utf-8")
            self.send_header("Cache-Control", "no-cache")
            self.send_header("Connection", "close")
            self.end_headers()

            def emit(piece):
                try:
                    self.wfile.write((json.dumps({"type": "chunk", "text": piece},
                                                 ensure_ascii=False) + "\n").encode("utf-8"))
                    self.wfile.flush()
                except Exception:
                    pass

            full = ""
            try:
                full = stream_hermes(user_text, sid + "-dm", emit)
            except Exception as e:
                log("流式失败: %s" % e)
            try:
                self.wfile.write((json.dumps({"type": "done", "text": full},
                                             ensure_ascii=False) + "\n").encode("utf-8"))
                self.wfile.flush()
            except Exception:
                pass
            self.close_connection = True
            return

        # ---- 公屏：先回 200，后台跑 ----
        user_text = ("[MC-%s] [玩家: %s] [OP: %s] %s\n"
                     "（这是游戏公屏：只有调用 mc_say 工具，玩家才看得到你的话；直接输出正文等于没说话）"
                     % (instance, player, op, text))
        self._json(200, {"ok": True, "session": sid})

        def work():
            with _session_lock(sid):
                reply = call_hermes(user_text, sid)
                log("[%s] 正文(不上屏): %s" % (sid, reply.replace("\n", " ")[:120]))

        threading.Thread(target=work, daemon=True).start()

    def _json(self, code, obj):
        b = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(b)))
        self.end_headers()
        try:
            self.wfile.write(b)
        except Exception:
            pass


if __name__ == "__main__":
    log("=== NexAGI 中转启动 端口=%d 网关=%s ===" % (LISTEN_PORT, HERMES))
    log("数据目录: %s" % HOME)
    ThreadingHTTPServer(("127.0.0.1", LISTEN_PORT), Handler).serve_forever()
