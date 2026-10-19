#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""mc_mcp_server.py — 给 Agent 暴露 Minecraft 说话工具的 MCP 服务（stdio）

工具：
  mc_say(text, instance?)   在服务器公屏说一句话（工具门控：不调用就不说话）
  mc_status()               查看各实例聊天桥是否在线

端口来源：<NEXAGI_HOME>/mc_ports.json（由 mc_ai_bridge.py 维护），默认 ~/.nexagi。

注册方式（以 Hermes 为例，写到 config.yaml 的 mcp_servers）：
  mc-chat:
    command: <python 解释器绝对路径>
    args:
      - <本文件绝对路径>
"""
import json
import os
import sys
import urllib.error
import urllib.request

PROTOCOL_VERSION = "2024-11-05"
SERVER_INFO = {"name": "mc-chat", "version": "1.0.0"}

HOME = os.environ.get("NEXAGI_HOME") or os.path.join(os.path.expanduser("~"), ".nexagi")
PORTS_FILE = os.path.join(HOME, "mc_ports.json")
LOG = os.path.join(HOME, "mc_say.log")


def log(msg):
    try:
        os.makedirs(HOME, exist_ok=True)
        with open(LOG, "a", encoding="utf-8") as f:
            f.write(msg + "\n")
    except Exception:
        pass


def load_ports():
    try:
        with open(PORTS_FILE, "r", encoding="utf-8") as f:
            return json.load(f) or {}
    except Exception:
        return {}


def post_json(url, obj, timeout=10):
    data = json.dumps(obj, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(url, data=data,
                                 headers={"Content-Type": "application/json; charset=utf-8"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read().decode("utf-8", "replace") or "{}")


def _endpoint(a, api_path):
    """解析实例 -> http://127.0.0.1:<port><api_path>"""
    ports = load_ports()
    want = str(a.get("instance") or "").strip()
    if not want:
        want = str(ports.get("_last") or "")
    entry = ports.get(want) if want else None
    if not entry:
        known = [k for k in ports.keys() if not k.startswith("_")]
        return None, "没有 %s 的端口信息，已知实例: %s" % (want or "?", known), want, 0
    port = int(entry.get("port") or 0)
    return "http://127.0.0.1:%d%s" % (port, api_path), None, want, port


def t_mc_say(a):
    text = str(a.get("text") or "").strip()
    if not text:
        return {"ok": False, "error": "text 不能为空"}
    url, err, want, port = _endpoint(a, "/say")
    if err:
        return {"ok": False, "error": err}
    try:
        res = post_json(url, {"text": text, "instance": want}, timeout=8)
        log("[mc_say] %s:%d %s" % (want, port, text[:80]))
        return {"ok": bool(res.get("ok")), "instance": want, "port": port, "say": text}
    except urllib.error.URLError as e:
        return {"ok": False, "error": "连不上插件 %s: %s" % (url, e)}
    except Exception as e:
        return {"ok": False, "error": "%s: %s" % (type(e).__name__, e)}


def t_mc_agi(a):
    """私聊回复：只有目标玩家能看到（/agi 用这个说话）"""
    player = str(a.get("player") or "").strip()
    text = str(a.get("text") or "").strip()
    if not player:
        return {"ok": False, "error": "player 不能为空"}
    if not text:
        return {"ok": False, "error": "text 不能为空"}
    url, err, want, port = _endpoint(a, "/reply")
    if err:
        return {"ok": False, "error": err}
    try:
        res = post_json(url, {"player": player, "text": text, "instance": want}, timeout=8)
        log("[mc_agi] %s -> %s: %s" % (want, player, text[:80]))
        out = {"ok": bool(res.get("ok")), "instance": want, "player": res.get("player") or player, "say": text}
        if res.get("error"):
            out["error"] = res.get("error")
        return out
    except urllib.error.URLError as e:
        return {"ok": False, "error": "连不上插件 %s: %s" % (url, e)}
    except Exception as e:
        return {"ok": False, "error": "%s: %s" % (type(e).__name__, e)}


def t_server_command(a):
    """执行服务器指令；是否允许由插件判断（只认最近一次发起请求的 OP）"""
    cmd = str(a.get("command") or "").strip()
    if not cmd:
        return {"ok": False, "error": "command 不能为空"}
    url, err, want, port = _endpoint(a, "/cmd")
    if err:
        return {"ok": False, "error": err}
    try:
        res = post_json(url, {"command": cmd, "instance": want}, timeout=10)
        log("[server_command] %s %s -> %s" % (want, cmd[:60], res.get("ok")))
        out = {"ok": bool(res.get("ok")), "instance": want, "command": cmd}
        if res.get("error"):
            out["error"] = res.get("error")
        if res.get("by"):
            out["executed_for"] = res.get("by")
        return out
    except urllib.error.URLError as e:
        return {"ok": False, "error": "连不上插件 %s: %s" % (url, e)}
    except Exception as e:
        return {"ok": False, "error": "%s: %s" % (type(e).__name__, e)}


def t_mc_status(a):
    ports = load_ports()
    out = []
    for k, v in ports.items():
        if k.startswith("_"):
            continue
        port = int(v.get("port") or 0)
        try:
            with urllib.request.urlopen("http://127.0.0.1:%d/health" % port, timeout=3) as r:
                h = json.loads(r.read().decode("utf-8", "replace") or "{}")
            out.append({"instance": k, "port": port, "alive": True, "health": h})
        except Exception as e:
            out.append({"instance": k, "port": port, "alive": False, "error": str(e)[:80]})
    return {"ok": True, "instances": out, "last": ports.get("_last")}


TOOLS = [
    {"name": "mc_say",
     "description": ("在 Minecraft 服务器公屏说话：你输出的任何文字玩家都看不到，只有调用本工具才会真正发到公屏；"
                     "玩家喊你时必须用它回应；实事求是，不知道就说不知道，不要编造；无 OP 玩家索要数据、信息或问安全类敏感内容时，只回「无可奉告」。"),
     "inputSchema": {"type": "object", "properties": {
         "text": {"type": "string", "description": "要说的话，纯文本，60 字以内"},
         "instance": {"type": "string", "description": "服务器实例名；不填则用最近说话的那个"}},
         "required": ["text"]}},
    {"name": "mc_agi",
     "description": ("给 Minecraft 服务器里的某个玩家发私聊（只有他一个人看得到），/agi 私聊时用它回话；"
                     "你输出的正文玩家看不到；实事求是，不知道就说不知道，不要编造；"
                     "无 OP 玩家索要数据、信息或问安全类敏感内容时，只回「无可奉告」。"),
     "inputSchema": {"type": "object", "properties": {
         "player": {"type": "string", "description": "要私聊的玩家 ID（就是消息里 [玩家: xxx] 那个名字）"},
         "text": {"type": "string", "description": "要说的话，纯文本，60 字以内"},
         "instance": {"type": "string", "description": "服务器实例名，不填则用最近说话的那个"}},
         "required": ["player", "text"]}},
    {"name": "server_command",
     "description": ("执行一条 Minecraft 服务器指令（例如 time set day、weather clear、give 玩家 物品）；"
                     "只有 OP 玩家发起的请求才会真正执行，否则会返回拒绝原因；"
                     "执行成功要如实告诉玩家发生了什么，失败或没权限也要直说，绝对不要假装执行。"),
     "inputSchema": {"type": "object", "properties": {
         "command": {"type": "string", "description": "不带斜杠的指令，例如 time set day"},
         "instance": {"type": "string", "description": "服务器实例名，不填则用最近说话的那个"}},
         "required": ["command"]}},
    {"name": "mc_status",
     "description": "查看各 MC 实例的聊天桥是否在线（排查用）。",
     "inputSchema": {"type": "object", "properties": {}}},
]

HANDLERS = {"mc_say": t_mc_say, "mc_agi": t_mc_agi, "mc_status": t_mc_status}


def send(obj):
    sys.stdout.write(json.dumps(obj, ensure_ascii=False) + "\n")
    sys.stdout.flush()


def reply(req_id, result):
    send({"jsonrpc": "2.0", "id": req_id, "result": result})


def handle(msg):
    method = str(msg.get("method") or "")
    req_id = msg.get("id")
    params = msg.get("params") or {}
    if method == "initialize":
        reply(req_id, {"protocolVersion": params.get("protocolVersion") or PROTOCOL_VERSION,
                       "capabilities": {"tools": {"listChanged": False}}, "serverInfo": SERVER_INFO})
    elif method in ("notifications/initialized", "notifications/cancelled", "logging/setLevel"):
        return
    elif method == "ping":
        reply(req_id, {})
    elif method == "tools/list":
        reply(req_id, {"tools": TOOLS})
    elif method == "tools/call":
        name = str(params.get("name") or "")
        h = HANDLERS.get(name)
        if not h:
            reply(req_id, {"content": [{"type": "text",
                                        "text": json.dumps({"error": "未知工具 " + name}, ensure_ascii=False)}],
                           "isError": True})
            return
        try:
            out = h(params.get("arguments") or {})
            reply(req_id, {"content": [{"type": "text",
                                        "text": json.dumps(out, ensure_ascii=False, default=str)}],
                           "isError": not bool(out.get("ok"))})
        except Exception as exc:
            reply(req_id, {"content": [{"type": "text",
                                        "text": json.dumps({"error": str(exc)}, ensure_ascii=False)}],
                           "isError": True})
    elif method in ("resources/list", "prompts/list"):
        reply(req_id, {"resources": []} if method.startswith("resources") else {"prompts": []})
    elif req_id is not None:
        send({"jsonrpc": "2.0", "id": req_id,
              "error": {"code": -32601, "message": "Method not found: " + method}})


def main():
    for s in (sys.stdin, sys.stdout, sys.stderr):
        try:
            s.reconfigure(encoding="utf-8")
        except Exception:
            pass
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            handle(json.loads(line))
        except Exception:
            continue


if __name__ == "__main__":
    main()
