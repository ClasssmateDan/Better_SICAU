"""HTTP request building and single-send transport; importing this module does no I/O."""
from __future__ import annotations

import ipaddress
import json
import mimetypes
import re
import time
import uuid
import zlib
from dataclasses import dataclass
from pathlib import Path
from urllib.error import HTTPError
from urllib.parse import urlencode, urlsplit
from urllib.request import (
    HTTPRedirectHandler, ProxyHandler, Request, build_opener,
)


BODY_MODES = ("无正文", "JSON", "原始文本", "表单 URL 编码", "表单 + 文件上传")
MANAGED_HEADERS = {"host", "content-length", "transfer-encoding", "connection", "accept-encoding"}
SECRET_HEADERS = {"cookie", "authorization", "proxy-authorization", "set-cookie"}
MAX_RESPONSE_BYTES = 2 * 1024 * 1024
TOKEN = re.compile(r"^[!#$%&'*+.^_`|~0-9A-Za-z-]+$")


@dataclass(frozen=True)
class PreparedRequest:
    url: str
    method: str
    headers: dict[str, str]
    body: bytes | None
    timeout: float


@dataclass(frozen=True)
class Response:
    url: str
    status: int
    reason: str
    headers: list[tuple[str, str]]
    body: bytes
    elapsed: float
    truncated: bool


def parse_headers(text: str) -> dict[str, str]:
    result = {}
    names = set()
    for line_number, line in enumerate(text.splitlines(), 1):
        if not line.strip():
            continue
        if ":" not in line:
            raise ValueError(f"请求头第 {line_number} 行需要 Name: Value 格式。")
        name, value = line.split(":", 1)
        name, value = name.strip(), value.strip()
        lower = name.lower()
        if not TOKEN.fullmatch(name):
            raise ValueError(f"无效的请求头名称：{name}")
        if lower in names:
            raise ValueError(f"请求头重复：{name}。多个 IP 请在同一行用逗号分隔。")
        if lower in MANAGED_HEADERS:
            raise ValueError(f"{name} 由客户端自动生成，请从自定义请求头中移除。")
        if any(ord(c) < 32 and c != "\t" or ord(c) == 127 for c in value):
            raise ValueError(f"请求头 {name} 包含控制字符。")
        try:
            value.encode("latin-1")
        except UnicodeEncodeError as exc:
            raise ValueError(f"请求头 {name} 含不可直接发送的字符，请按接口要求编码。") from exc
        result[name] = value
        names.add(lower)
    return result


def parse_fields(text: str) -> list[tuple[str, str]]:
    fields = []
    for number, line in enumerate(text.splitlines(), 1):
        if not line.strip():
            continue
        if "=" not in line:
            raise ValueError(f"表单第 {number} 行需要 name=value 格式。")
        key, value = line.split("=", 1)
        if not key.strip():
            raise ValueError(f"表单第 {number} 行字段名为空。")
        fields.append((key.strip(), value))
    return fields


def quoted_part(value: str) -> str:
    if any(c in value for c in "\r\n\x00"):
        raise ValueError("文件或表单字段名中不能包含换行或空字符。")
    return value.replace("\\", "\\\\").replace('"', '\\"')


def build_multipart(fields: list[tuple[str, str]], field: str, filename: str) -> tuple[bytes, str]:
    if not field.strip():
        raise ValueError("请填写文件字段名，需与真实上传表单一致。")
    if not filename:
        raise ValueError("请先选择要上传的本地文件。")
    path = Path(filename)
    if not path.is_file():
        raise ValueError("选择的文件不存在或不是普通文件。")
    boundary = "----LocalHeaderTool" + uuid.uuid4().hex
    parts = []
    for name, value in fields:
        parts.append((
            f'--{boundary}\r\nContent-Disposition: form-data; name="{quoted_part(name)}"'
            f'\r\n\r\n{value}\r\n'
        ).encode("utf-8"))
    media_type = mimetypes.guess_type(path.name)[0] or "application/octet-stream"
    parts.append((
        f'--{boundary}\r\nContent-Disposition: form-data; name="{quoted_part(field.strip())}"; '
        f'filename="{quoted_part(path.name)}"\r\nContent-Type: {media_type}\r\n\r\n'
    ).encode("utf-8"))
    parts.extend((path.read_bytes(), f"\r\n--{boundary}--\r\n".encode("ascii")))
    return b"".join(parts), f"multipart/form-data; boundary={boundary}"


def prepare_request(url: str, method: str, header_text: str, cookie: str,
                    mode: str, text: str, file_field: str = "file", filename: str = "",
                    timeout: str = "15") -> PreparedRequest:
    url = url.strip()
    if any(ord(c) < 33 for c in url):
        raise ValueError("URL 含空白或控制字符，请先进行 URL 编码。")
    parsed = urlsplit(url)
    if parsed.scheme not in ("http", "https") or not parsed.hostname:
        raise ValueError("请填写完整的 http:// 或 https:// 地址。")
    if parsed.username is not None or parsed.password is not None:
        raise ValueError("不要在 URL 中放用户名或密码；需要时使用 Authorization 请求头。")
    if parsed.fragment:
        raise ValueError("URL 中的 #片段不会发送给服务器，请移除后再发送。")
    try:
        parsed.port
        seconds = float(timeout)
    except ValueError as exc:
        raise ValueError("端口或超时时间无效。") from exc
    if not 1 <= seconds <= 120:
        raise ValueError("超时时间需要在 1–120 秒之间。")
    method = method.strip().upper()
    if method not in ("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS"):
        raise ValueError("请选择支持的请求方式。")
    if mode not in BODY_MODES:
        raise ValueError("请选择正文类型。")
    headers = parse_headers(header_text)
    if cookie.strip():
        if any(c in cookie for c in "\r\n\x00"):
            raise ValueError("Cookie 需要填写在同一行，不能包含换行或空字符。")
        if any(name.lower() == "cookie" for name in headers):
            raise ValueError("Cookie 同时出现在两处，请只保留一处。")
        headers.update(parse_headers("Cookie: " + cookie.strip()))
    content_type = None
    body = None
    if mode in ("JSON", "原始文本"):
        if mode == "JSON":
            try:
                json.loads(text)
            except ValueError as exc:
                raise ValueError(f"JSON 格式不正确：{exc}") from exc
            content_type = "application/json; charset=utf-8"
        else:
            content_type = "text/plain; charset=utf-8"
        body = text.encode("utf-8")
    elif mode == "表单 URL 编码":
        body = urlencode(parse_fields(text)).encode("ascii")
        content_type = "application/x-www-form-urlencoded; charset=utf-8"
    elif mode == "表单 + 文件上传":
        body, content_type = build_multipart(parse_fields(text), file_field, filename)
        if any(name.lower() == "content-type" for name in headers):
            raise ValueError("文件上传时请移除自定义 Content-Type，由工具生成匹配的 boundary。")
    if method in ("GET", "HEAD") and body is not None:
        raise ValueError("GET / HEAD 请使用“无正文”；表单正文请使用真实接口规定的方法。")
    lower_names = {name.lower() for name in headers}
    if content_type and "content-type" not in lower_names:
        headers["Content-Type"] = content_type
    if "user-agent" not in lower_names:
        headers["User-Agent"] = "LocalHeaderDebugger/1.0"
    if "accept" not in lower_names:
        headers["Accept"] = "*/*"
    return PreparedRequest(url, method, headers, body, seconds)


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def send_once(request: PreparedRequest, use_system_proxy: bool = False) -> Response:
    """One explicit request, no application retries, no redirects, default TLS checks."""
    opener = build_opener(ProxyHandler() if use_system_proxy else ProxyHandler({}), NoRedirect())
    outgoing = Request(request.url, data=request.body, headers=request.headers, method=request.method)
    started = time.monotonic()
    try:
        incoming = opener.open(outgoing, timeout=request.timeout)
    except HTTPError as response:
        incoming = response  # Display 3xx/4xx/5xx responses as well.
    with incoming:
        body = incoming.read(MAX_RESPONSE_BYTES + 1)
        return Response(
            incoming.geturl(), incoming.code, str(incoming.reason), list(incoming.headers.items()),
            body[:MAX_RESPONSE_BYTES], time.monotonic() - started, len(body) > MAX_RESPONSE_BYTES,
        )


def preview_request(request: PreparedRequest) -> str:
    lines = [f"{request.method} {request.url}", ""]
    lines.extend(f"{key}: {'<已隐藏>' if key.lower() in SECRET_HEADERS else value}"
                 for key, value in request.headers.items())
    lines.extend(("", f"正文长度：{len(request.body or b'')} 字节", "超时：%g 秒；不跟随重定向" % request.timeout))
    if request.body is not None:
        media = next((v for k, v in request.headers.items() if k.lower() == "content-type"), "")
        if media.startswith("multipart/"):
            lines.append("文件上传正文已构建；二进制内容不显示。")
        else:
            lines.extend(("", request.body[:8192].decode("utf-8", errors="replace")))
            if len(request.body) > 8192:
                lines.append("[请求正文预览已截断]")
    return "\n".join(lines)


def display_response(response: Response) -> str:
    headers = {k.lower(): v for k, v in response.headers}
    data = response.body
    encoding = headers.get("content-encoding", "").lower()
    if not response.truncated and encoding in ("gzip", "deflate"):
        try:
            if encoding == "gzip":
                data = zlib.decompressobj(16 + zlib.MAX_WBITS).decompress(data, MAX_RESPONSE_BYTES + 1)
            else:
                data = zlib.decompressobj().decompress(data, MAX_RESPONSE_BYTES + 1)
        except zlib.error:
            data = response.body
    match = re.search(r"charset\s*=\s*[\"']?([\w.-]+)", headers.get("content-type", ""), re.I)
    charset = match.group(1) if match else "utf-8"
    try:
        text = data[:MAX_RESPONSE_BYTES].decode(charset, errors="replace")
    except LookupError:
        text = data[:MAX_RESPONSE_BYTES].decode("utf-8", errors="replace")
    lines = [f"HTTP {response.status} {response.reason}", f"地址：{response.url}",
             f"耗时：{response.elapsed:.3f} 秒；读取：{len(response.body)} 字节", ""]
    lines.extend(f"{key}: {'<已隐藏>' if key.lower() in SECRET_HEADERS else value}"
                 for key, value in response.headers)
    if response.truncated or len(data) > MAX_RESPONSE_BYTES:
        lines.append("\n[响应预览达到 2 MiB 上限，已截断]")
    lines.extend(("", text))
    return "\n".join(lines)


def ip_headers(header_text: str, address: str) -> str:
    ip = str(ipaddress.ip_address(address.strip()))
    retained = [line for line in header_text.splitlines()
                if line.split(":", 1)[0].strip().lower() not in {"x-forwarded-for", "x-real-ip"}]
    return "\n".join(retained + [f"X-Forwarded-For: {ip}", f"X-Real-IP: {ip}"])


def import_har_request(entry: dict) -> dict:
    """Turn one saved HAR request into editable values without sending anything."""
    request = entry["request"]
    headers = []
    cookie = ""
    for header in request.get("headers", []):
        name, value = header["name"], header["value"]
        if name.lower() == "cookie":
            cookie = value
        elif name.lower() not in MANAGED_HEADERS and not name.startswith(":"):
            headers.append(f"{name}: {value}")
    if not cookie:
        cookie = "; ".join(f"{c['name']}={c['value']}" for c in request.get("cookies", []))
    mode, text, file_field = "无正文", "", "file"
    post = request.get("postData")
    if post:
        mime = post.get("mimeType", "").lower()
        params = post.get("params", [])
        if mime.startswith("multipart/"):
            mode = "表单 + 文件上传"
            text = "\n".join(f"{p['name']}={p.get('value', '')}" for p in params if not p.get("fileName"))
            files = [p for p in params if p.get("fileName")]
            if len(files) > 1:
                raise ValueError("这条 HAR 请求包含多个文件；当前工具一次只支持一个文件。")
            if files:
                file_field = files[0]["name"]
            headers = [line for line in headers if line.split(":", 1)[0].lower() != "content-type"]
        elif mime.startswith("application/x-www-form-urlencoded") and params:
            mode = "表单 URL 编码"
            text = "\n".join(f"{p['name']}={p.get('value', '')}" for p in params)
        else:
            mode, text = "原始文本", post.get("text", "")
    return {"url": request["url"], "method": request.get("method", "GET"),
            "headers": "\n".join(headers), "cookie": cookie, "mode": mode,
            "text": text, "file_field": file_field}
