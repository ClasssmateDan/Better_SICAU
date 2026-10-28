"""One-shot, loopback-only receiver for the companion browser extension."""
from __future__ import annotations

import hmac
import json
import re
import secrets
import threading
import time
import uuid
from datetime import datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlsplit

from attendance_core import HOST, extract_capture_cookie, parse_capture


PORT = 18763
ORIGIN = f"http://127.0.0.1:{PORT}"
CAPTURE_PATH = "/course-har-capture"
MAX_BYTES = 8 * 1024 * 1024
PAGE = """<!doctype html><html lang="zh-CN"><meta charset="utf-8">
<title>从当前浏览器读取课程主页</title>
<style>body{font:17px system-ui;max-width:780px;margin:70px auto;padding:24px;line-height:1.8}h1{font-size:26px}code{background:#eee;padding:3px 6px}</style>
<h1>读取当前浏览器的学校登录状态</h1>
<p id="message">正在等待“课程主页 HAR 桥接”扩展。首次使用请先加载随工具附带的浏览器扩展，然后回到工具重新点击“读取本机浏览器登录”。</p>
<p>请保持本浏览器中已登录的学校网页标签打开。完成后，桌面工具会自动保存并导入 HAR。此页面无需填写密码。</p>
</html>""".encode("utf-8")


class BridgeSession:
    def __init__(self, folder, notify, *, clock=time.monotonic):
        self.folder = Path(folder).resolve()
        self.notify = notify
        self.clock = clock
        self.lock = threading.RLock()
        self.token = None
        self.deadline = 0

    def begin(self):
        with self.lock:
            self.token = secrets.token_urlsafe(32)
            self.deadline = self.clock() + 90
            return ORIGIN + CAPTURE_PATH + "#token=" + self.token

    def cancel(self):
        with self.lock:
            active = self.token is not None
            self.token = None
        if active:
            self.notify("browser_cancelled", "已取消本机浏览器读取。")

    def expire(self):
        with self.lock:
            expired = self.token is not None and self.clock() >= self.deadline
            if expired:
                self.token = None
        if expired:
            self.notify("browser_error", "等待浏览器扩展超时。请确认扩展已加载到当前已登录的浏览器，再重新点击读取；默认浏览器不一致时，可使用“复制连接链接”。")

    def authorized(self, headers):
        origin = headers.get("origin", "")
        if origin and not re.fullmatch(r"chrome-extension://[a-p]{32}", origin):
            return False
        value = headers.get("authorization", "")
        return bool(self.token and self.clock() < self.deadline and
                    hmac.compare_digest(value.encode("utf-8"), ("Bearer " + self.token).encode("ascii")))

    def handle(self, method, path, headers, body=b""):
        headers = {key.lower(): value for key, value in headers.items()}
        if headers.get("host") != f"127.0.0.1:{PORT}":
            return 403, {"error": "仅接受本机地址。"}
        if method == "GET" and path == CAPTURE_PATH:
            return 200, PAGE
        if method == "OPTIONS":
            if path in ("/api/authorize", "/api/result", "/api/failure") and re.fullmatch(r"chrome-extension://[a-p]{32}", headers.get("origin", "")):
                return 204, {"ok": True}
            return 403, {"error": "不接受网页来源的预检。"}
        with self.lock:
            if not self.authorized(headers):
                return 403, {"error": "本次连接无效、已取消或已过期。请从桌面工具重新发起。"}
            if method == "GET" and path == "/api/authorize":
                return 200, {"ok": True, "target": "https://" + HOST + "/Students"}
            if method != "POST" or path not in ("/api/result", "/api/failure"):
                return 404, {"error": "未知接口。"}
            if headers.get("content-type", "").split(";", 1)[0].strip().lower() != "application/json":
                return 415, {"error": "需要 JSON 正文。"}
            if len(body) > MAX_BYTES:
                return 413, {"error": "记录超过 8 MiB。"}
            try:
                document = json.loads(body)
                if path == "/api/failure":
                    # Only fixed error codes cross the bridge; no browser secrets in errors.
                    messages = {
                        "no_tab": "当前浏览器没有打开学校网页。请在日常浏览器登录学校网站，并保持标签打开。",
                        "no_cookie": "没有观察到请求 Cookie，请确认扩展的学校网站访问权限已开启。",
                        "logged_out": "读取主页没有成功，请先在浏览器确认学校网站仍处于登录状态。",
                        "capture_failed": "浏览器读取失败。请确认学校主页可访问、扩展已获准访问学校网站，再重试。",
                    }
                    if not isinstance(document, dict):
                        raise ValueError("错误消息格式无效。")
                    code = document.get("code")
                    message = messages.get(code, messages["capture_failed"]) if isinstance(code, str) else messages["capture_failed"]
                    self.token = None
                    self.notify("browser_error", message)
                    return 200, {"ok": True}
                self.validate_scope(document)
                capture = parse_capture(document)
                cookie = extract_capture_cookie(document, capture)
                if cookie is None:
                    raise ValueError("HAR 缺少该账号实际发送的 Cookie。")
                self.folder.mkdir(exist_ok=True)
                name = "学生主页-" + datetime.now().strftime("%Y%m%d-%H%M%S") + "-" + uuid.uuid4().hex[:6] + ".har"
                output = self.folder / name
                created = False
                try:
                    with output.open("xb") as destination:
                        created = True
                        destination.write(body)
                except OSError:
                    if created:
                        output.unlink(missing_ok=True)
                    raise
            except (ValueError, TypeError, KeyError, AttributeError, OSError):
                return 400, {"error": "主页记录无效或无法保存；请确认已登录并进入学生主页，然后重试。"}
            self.token = None  # A successful token cannot be replayed.
            self.notify("local_import", (capture, str(output), cookie))
            return 200, {"ok": True}

    @staticmethod
    def validate_scope(document):
        if not isinstance(document, dict):
            raise ValueError("无效记录。")
        entries = document.get("log", {}).get("entries", [])
        if not isinstance(entries, list) or len(entries) != 1:
            raise ValueError("仅接收一次学生主页请求。")
        request = entries[0]["request"]
        parsed = urlsplit(request["url"])
        if (parsed.scheme != "https" or parsed.hostname != HOST or parsed.port not in (None, 443)
                or parsed.username or parsed.password or parsed.path.lower() != "/students"
                or request.get("method") != "GET"):
            raise ValueError("仅接收学校学生主页的 HTTPS GET。")


class LocalBrowserBridge:
    def __init__(self, folder, notify):
        self.session = BridgeSession(folder, notify)
        self.server = None

    def start(self):
        if self.server is not None:
            return
        session = self.session

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *_args):
                pass

            def do_GET(self):
                self.dispatch()

            def do_POST(self):
                self.dispatch()

            def do_OPTIONS(self):
                self.dispatch()

            def dispatch(self):
                self.connection.settimeout(10)
                body = b""
                if self.command == "POST":
                    try:
                        length = int(self.headers.get("Content-Length", "-1"))
                    except ValueError:
                        length = -1
                    if length < 0 or length > MAX_BYTES:
                        self.respond(413, {"error": "正文长度无效或超过上限。"})
                        return
                    try:
                        body = self.rfile.read(length)
                    except OSError:
                        self.respond(400, {"error": "正文读取失败。"})
                        return
                    if len(body) != length:
                        self.respond(400, {"error": "正文不完整。"})
                        return
                status, payload = session.handle(self.command, self.path, dict(self.headers), body)
                self.respond(status, payload)

            def respond(self, status, payload):
                html = isinstance(payload, bytes)
                data = b"" if status == 204 else payload if html else json.dumps(payload, ensure_ascii=False).encode("utf-8")
                self.send_response(status)
                self.send_header("Content-Type", "text/html; charset=utf-8" if html else "application/json; charset=utf-8")
                self.send_header("Content-Length", str(len(data)))
                self.send_header("Cache-Control", "no-store")
                self.send_header("Referrer-Policy", "no-referrer")
                self.send_header("X-Frame-Options", "DENY")
                self.send_header("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; frame-ancestors 'none'")
                origin = self.headers.get("Origin", "")
                if self.headers.get("Host") == f"127.0.0.1:{PORT}" and re.fullmatch(r"chrome-extension://[a-p]{32}", origin):
                    self.send_header("Access-Control-Allow-Origin", origin)
                    self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
                    self.send_header("Access-Control-Allow-Headers", "Authorization, Content-Type")
                    self.send_header("Access-Control-Allow-Private-Network", "true")
                self.end_headers()
                try:
                    self.wfile.write(data)
                except OSError:
                    pass

        class Server(ThreadingHTTPServer):
            daemon_threads = True
            block_on_close = False

        self.server = Server(("127.0.0.1", PORT), Handler)
        threading.Thread(target=lambda: self.server.serve_forever(poll_interval=0.1), daemon=True).start()

    def stop(self):
        if self.server is not None:
            self.session.cancel()
            self.server.shutdown()
            self.server.server_close()
            self.server = None
