"""Parse saved course pages and prepare one manually initiated sign-in request."""
from __future__ import annotations

import base64
import ipaddress
import json
import re
import shlex
import zlib
from dataclasses import dataclass, field
from html.parser import HTMLParser
from urllib.parse import parse_qs, urljoin, urlsplit

from request_core import MAX_RESPONSE_BYTES, PreparedRequest, Response, prepare_request


HOST = "eol.sicau.edu.cn"
ORIGIN = "https://" + HOST
SIGN_ENDPOINT = ORIGIN + "/Students/DaoService.asmx/SignByClassidAndSno"
MAX_INTEGER = 2**53 - 1
VOID_TAGS = {"area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param", "source", "track", "wbr"}


@dataclass(frozen=True)
class Course:
    classid: str
    name: str
    button_seen: bool = False


@dataclass(frozen=True)
class Capture:
    sno: str
    name: str
    courses: tuple[Course, ...]
    captured_at: str
    source_url: str
    request_count: int


@dataclass(frozen=True)
class Outcome:
    kind: str
    title: str
    message: str


@dataclass(frozen=True)
class SavedCookie:
    value: str = field(repr=False)
    captured_at: str


class CoursePage(HTMLParser):
    """Read only visible account/course markup; never execute captured scripts."""
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.depth = 0
        self.ignored = 0
        self.welcome_depth = None
        self.welcome_text = []
        self.welcomes = []
        self.table_depth = None
        self.anchor_depth = None
        self.anchor_id = None
        self.anchor_text = []
        self.course_names = {}
        self.buttons = set()

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if tag not in VOID_TAGS:
            self.depth += 1
        if tag in ("script", "style"):
            self.ignored += 1
        if self.ignored:
            return
        if "welcome" in attrs.get("class", "").split() and self.welcome_depth is None:
            self.welcome_depth = self.depth
            self.welcome_text = []
        if tag == "table" and attrs.get("id", "").lower() == "mytable":
            self.table_depth = self.depth
        if tag == "a" and self.table_depth is not None:
            parsed = urlsplit(urljoin(ORIGIN + "/Students", attrs.get("href", "")))
            if parsed.hostname == HOST and parsed.path.lower() == "/students/classtasks":
                parameters = {k.lower(): v for k, v in parse_qs(parsed.query).items()}
                ids = parameters.get("classid", [])
                if len(ids) == 1 and valid_integer(ids[0]):
                    self.anchor_depth = self.depth
                    self.anchor_id = ids[0]
                    self.anchor_text = []
        onclick = attrs.get("onclick", "")
        match = re.search(r"\bDao\s*\(\s*this\s*,\s*['\"]?(\d+)['\"]?\s*,\s*['\"]?(\d+)['\"]?\s*\)", onclick)
        if match:
            self.buttons.add((match[1], match[2]))

    def handle_startendtag(self, tag, attrs):
        self.handle_starttag(tag, attrs)
        if tag not in VOID_TAGS:
            self.handle_endtag(tag)

    def handle_data(self, text):
        if self.ignored:
            return
        if self.welcome_depth is not None:
            self.welcome_text.append(text)
        if self.anchor_depth is not None:
            self.anchor_text.append(text)

    def handle_endtag(self, tag):
        if tag in VOID_TAGS:
            return
        if self.welcome_depth == self.depth:
            self.welcomes.append("".join(self.welcome_text))
            self.welcome_depth = None
        if self.anchor_depth == self.depth:
            label = " ".join("".join(self.anchor_text).split())
            if label:
                self.course_names.setdefault(self.anchor_id, label)
            self.anchor_depth = None
            self.anchor_id = None
        if self.table_depth == self.depth:
            self.table_depth = None
        if tag in ("script", "style"):
            self.ignored = max(0, self.ignored - 1)
        self.depth = max(0, self.depth - 1)

    def identity(self):
        identities = {}
        for text in self.welcomes:
            match = re.match(r"\s*(.*?)\s*[（(](\d{8,12})[）)]", text)
            if match:
                identities[match[2]] = match[1].strip()
        if len(identities) > 1:
            raise ValueError("同一页面含多个登录账号，无法确定当前学号。请重新导出学生主页。")
        return next(iter(identities.items())) if identities else None


def valid_integer(value):
    return isinstance(value, str) and bool(re.fullmatch(r"[0-9]{1,16}", value)) and 0 < int(value) <= MAX_INTEGER


def content_text(content):
    text = content.get("text")
    if not isinstance(text, str):
        return ""
    if content.get("encoding") != "base64":
        return text
    try:
        data = base64.b64decode(text, validate=True)
    except ValueError as exc:
        raise ValueError("HAR 中的页面正文 Base64 格式无效。") from exc
    for encoding in ("utf-8-sig", "gb18030"):
        try:
            return data.decode(encoding)
        except UnicodeDecodeError:
            pass
    return data.decode("utf-8", errors="replace")


def parse_capture(document):
    if not isinstance(document, dict) or not isinstance(document.get("log"), dict):
        raise ValueError("不是 HAR 网络记录。请导入 Network 导出的 .har / .devtools 文件。")
    entries = document["log"].get("entries")
    if not isinstance(entries, list) or not entries:
        raise ValueError("HAR 中没有请求。请在学生主页刷新并重新导出。")
    pages = []
    for index, entry in enumerate(entries):
        if not isinstance(entry, dict):
            continue
        request = entry.get("request", {})
        response = entry.get("response", {})
        parsed = urlsplit(request.get("url", ""))
        if parsed.hostname != HOST or not parsed.path.lower().startswith("/students"):
            continue
        if response.get("status") != 200:
            continue
        content = response.get("content", {})
        if "html" not in content.get("mimeType", "").lower():
            continue
        html = content_text(content)
        if not html:
            continue
        page = CoursePage()
        page.feed(html)
        page.close()
        account = page.identity()
        if account:
            # HAR entry order is the browser's recorded request order.
            pages.append((index, account, page, entry.get("startedDateTime", ""), request["url"]))
    if not pages:
        raise ValueError("没有读到已登录学生的页面及学号。请在学校真实网页打开“我的主页”，刷新后重新导出；错误页或登录页无法识别账号。")
    latest_account = pages[-1][1]
    candidates = [page for page in pages if page[1][0] == latest_account[0] and page[2].course_names]
    if not candidates:
        raise ValueError("已识别最近登录的账号，但缺少该账号的完整课程列表。请打开“我的主页”刷新后导出，避免混用其他账号的课程。")
    _index, (sno, name), page, captured_at, source_url = candidates[-1]
    courses = tuple(Course(key, value, (key, sno) in page.buttons) for key, value in page.course_names.items())
    return Capture(sno, name, courses, captured_at, source_url, len(entries))


def cookie_from_curl(text):
    """Parse copied text only. Never execute cURL or read its referenced files."""
    command = re.sub(r"\^\r?\n", " ", text)
    command = re.sub(r"\\\r?\n", " ", command)
    if '^"' in command:
        command = re.sub(r'\^(["^&|<>%()])', lambda match: match[1], command)
    try:
        tokens = shlex.split(command, posix=True)
    except ValueError as exc:
        raise ValueError("cURL 文本不完整，无法读取引号中的 Cookie。请重新复制一条请求。") from exc
    urls = [token[6:] if token.startswith("--url=") else token for token in tokens
            if token.startswith(("https://", "http://", "--url=https://", "--url=http://"))]
    if len(urls) != 1 or urlsplit(urls[0]).hostname != HOST:
        raise ValueError("请复制 eol.sicau.edu.cn 的单条请求为 cURL，避免混入其他网站的 Cookie。")
    cookies = []
    index = 1
    while index < len(tokens):
        token = tokens[index]
        value = None
        kind = None
        if token in ("-b", "--cookie", "-H", "--header"):
            if index + 1 >= len(tokens):
                raise ValueError("cURL 参数缺少内容，请重新复制。")
            kind = "cookie" if token in ("-b", "--cookie") else "header"
            index += 1
            value = tokens[index]
        elif token.startswith("--cookie="):
            kind, value = "cookie", token[len("--cookie="):]
        elif token.startswith("--header="):
            kind, value = "header", token[len("--header="):]
        elif token.startswith("-b") and len(token) > 2:
            kind, value = "cookie", token[2:]
        elif token.startswith("-H") and len(token) > 2:
            kind, value = "header", token[2:]
        if kind == "cookie":
            if "=" not in value:
                raise ValueError("这条 cURL 引用的是 Cookie 文件，工具不会读取它。请复制带完整 Cookie 值的请求。")
            cookies.append(value)
        elif kind == "header" and ":" in value:
            name, content = value.split(":", 1)
            if name.strip().lower() == "cookie":
                cookies.append(content.strip())
        index += 1
    if not cookies:
        raise ValueError("这条 cURL 没有 Cookie。请复制学校网站当前已登录页面的请求。")
    return "; ".join(cookies)


def normalize_cookie(text):
    value = text.strip()
    if not value:
        raise ValueError("请粘贴当前账号的完整 Cookie。")
    if re.match(r"^curl(?:\.exe)?(?:\s|$)", value, re.I):
        value = cookie_from_curl(value)
    if re.match(r"set-cookie\s*:", value, re.I):
        raise ValueError("请复制请求标头 Cookie，而不是响应里的 Set-Cookie。")
    cookie_lines = re.findall(r"^\s*cookie\s*:\s*([^\r\n]+)", value, re.I | re.M)
    if cookie_lines:
        if len(cookie_lines) != 1:
            raise ValueError("发现多行 Cookie，请只复制一个已登录请求的 Cookie。")
        value = cookie_lines[0].strip()
    if any(ord(char) < 32 or ord(char) == 127 for char in value):
        raise ValueError("Cookie 需要是一行“名称=值; 名称=值”，不能包含换行或控制字符。")
    pairs = []
    for part in value.split(";"):
        if not part.strip():
            continue
        if "=" not in part:
            raise ValueError("Cookie 每一项都需要“名称=值”，不能只粘贴值。")
        name, content = part.strip().split("=", 1)
        if not re.fullmatch(r"[!#$%&'*+.^_`|~0-9A-Za-z-]+", name):
            raise ValueError("Cookie 名称不正确，请复制完整请求标头的值。")
        pairs.append(name + "=" + content)
    if not pairs:
        raise ValueError("Cookie 中没有有效的名称和值。")
    return "; ".join(pairs)


def extract_capture_cookie(document, capture):
    """Use only saved requests whose HTML identifies the selected account."""
    entries = document.get("log", {}).get("entries", [])
    for entry in reversed(entries):
        if not isinstance(entry, dict):
            continue
        request = entry.get("request", {})
        response = entry.get("response", {})
        parsed = urlsplit(request.get("url", ""))
        content = response.get("content", {})
        if parsed.hostname != HOST or not parsed.path.lower().startswith("/students"):
            continue
        if response.get("status") != 200 or "html" not in content.get("mimeType", "").lower():
            continue
        page = CoursePage()
        page.feed(content_text(content))
        page.close()
        account = page.identity()
        if not account:
            continue
        if account[0] != capture.sno:
            break  # Do not cross an account change to reuse older credentials.
        headers = [header.get("value", "") for header in request.get("headers", [])
                   if header.get("name", "").lower() == "cookie" and isinstance(header.get("value"), str)]
        values = "; ".join(headers)
        if not values:
            values = "; ".join(cookie["name"] + "=" + cookie["value"]
                               for cookie in request.get("cookies", [])
                               if isinstance(cookie.get("name"), str) and isinstance(cookie.get("value"), str))
        if not values:
            continue
        try:
            normalized = normalize_cookie(values)
        except ValueError:
            continue
        return SavedCookie(normalized, entry.get("startedDateTime", ""))
    return None


def build_sign_request(capture: Capture, classid: str, cookie: str, address: str, timeout="15") -> PreparedRequest:
    if not any(course.classid == classid for course in capture.courses):
        raise ValueError("请选择本次导入课程列表中的一门课程。")
    if not valid_integer(classid) or not re.fullmatch(r"[0-9]{8,12}", capture.sno):
        raise ValueError("课程班编号或学号格式不正确，请重新导入记录。")
    try:
        ip = str(ipaddress.ip_address(address.strip()))
    except ValueError as exc:
        raise ValueError("请填写有效的 IPv4 或 IPv6 地址。") from exc
    cookie = normalize_cookie(cookie)
    headers = "\n".join((
        "X-Forwarded-For: " + ip,
        "X-Real-IP: " + ip,
        "Accept: application/json",
        "Referer: " + ORIGIN + "/Students",
        "Origin: " + ORIGIN,
    ))
    payload = json.dumps({"classid": int(classid), "sno": int(capture.sno)}, separators=(",", ":"))
    return prepare_request(SIGN_ENDPOINT, "POST", headers, cookie, "JSON", payload, timeout=timeout)


def response_body_text(response):
    headers = {key.lower(): value for key, value in response.headers}
    data = response.body
    coding = headers.get("content-encoding", "").lower()
    if not response.truncated and coding in ("gzip", "deflate"):
        try:
            bits = 16 + zlib.MAX_WBITS if coding == "gzip" else zlib.MAX_WBITS
            data = zlib.decompressobj(bits).decompress(data, MAX_RESPONSE_BYTES + 1)
        except zlib.error as exc:
            raise ValueError("服务器响应压缩格式不完整，未确认签到结果。") from exc
    if response.truncated or len(data) > MAX_RESPONSE_BYTES:
        raise ValueError("响应超过预览上限，未确认签到结果。")
    charset = re.search(r"charset\s*=\s*['\"]?([\w.-]+)", headers.get("content-type", ""), re.I)
    try:
        return data.decode(charset[1] if charset else "utf-8-sig")
    except (LookupError, UnicodeDecodeError) as exc:
        raise ValueError("无法准确解码服务器响应，未确认签到结果。") from exc


def interpret_response(response: Response) -> Outcome:
    headers = {key.lower(): value for key, value in response.headers}
    location = headers.get("location", "")
    if 300 <= response.status < 400:
        if urlsplit(urljoin(ORIGIN, location)).path.lower().startswith("/login"):
            return Outcome("login", "登录未通过", "服务器跳转到登录页，请更新与导入账号对应的完整 Cookie。")
        return Outcome("unknown", "收到重定向", "未自动跟随，也未确认签到结果。Location: " + location)
    if not 200 <= response.status < 300:
        return Outcome("http_error", "HTTP 请求未成功", f"服务器返回 HTTP {response.status}，请查看原始响应。")
    try:
        text = response_body_text(response)
    except ValueError as exc:
        return Outcome("unknown", "结果未确认", str(exc))
    try:
        document = json.loads(text)
    except ValueError:
        title = re.search(r"<title[^>]*>(.*?)</title>", text, re.I | re.S)
        if title and ("登录" in title[1] or re.search(r"\blogin\b", title[1], re.I)):
            return Outcome("login", "返回了登录页面", "HTTP 状态虽然成功，但未取得签到业务响应。请更新 Cookie。")
        return Outcome("unknown", "未识别的响应", "服务器没有返回预期的 JSON 签到结果，请查看原始响应。")
    if not isinstance(document, dict) or not isinstance(document.get("d"), str) or "|" not in document["d"]:
        return Outcome("unknown", "未识别的业务格式", "没有找到预期的 result.d = 标记|提示，不能仅凭 HTTP 状态判断签到成功。")
    flag, message = document["d"].split("|", 1)
    if flag == "1":
        return Outcome("success", "服务器返回签到成功", message or "业务标记为 1。最终记录的 IP 仍需在平台确认。")
    return Outcome("rejected", "服务器未返回成功", message or "业务标记为：" + flag)
