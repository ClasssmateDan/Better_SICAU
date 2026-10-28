"""Read course task/form HTML and prepare one explicit report upload."""
from __future__ import annotations

import base64
import hashlib
import ipaddress
import re
from dataclasses import dataclass, field
from html.parser import HTMLParser
from pathlib import Path
from urllib.parse import parse_qs, urljoin, urlsplit

from attendance_core import CoursePage, HOST, ORIGIN, Outcome, normalize_cookie, response_body_text, valid_integer
from request_core import PreparedRequest, build_multipart, prepare_request, preview_request


SUBMIT_PATH = "/Students/ClassTaskGetForm"
MAX_FILE_BYTES = 200 * 1024 * 1024
RESUBMIT_IP_NOTE = "当前请求头 IP 设置无法更改报告重交时的平台记录 IP，平台仍记录真实 IP。"


@dataclass(frozen=True)
class ReportTask:
    classid: str
    taskid: str
    name: str
    state: str
    submit_url: str
    resubmit: bool = False
    recorded_ip: str = ""


@dataclass(frozen=True)
class UploadForm:
    sno: str
    task: ReportTask
    action: str
    fields: tuple[tuple[str, str], ...] = field(repr=False)
    file_field: str
    maxsize: int
    accept: str
    required: tuple[str, ...]
    editable: tuple[str, ...]
    cookie_digest: str = field(repr=False)


@dataclass(frozen=True)
class UploadFile:
    path: str
    size: int
    modified_ns: int


def school_url(url):
    parsed = urlsplit(url)
    if (parsed.scheme != "https" or parsed.hostname != HOST or parsed.port not in (None, 443)
            or parsed.username or parsed.password or parsed.fragment):
        raise ValueError("仅支持学校网站的 HTTPS 地址。")
    return parsed


def account_matches(html, sno):
    parser = CoursePage()
    parser.feed(html)
    parser.close()
    identity = parser.identity()
    if identity is None:
        raise ValueError("页面没有已登录学生账号。请在浏览器确认登录后，重新获取 Cookie。")
    if identity[0] != sno:
        raise ValueError("页面账号与导入学号不一致，请重新获取当前账号，避免混用。")


class TaskTable(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.ignored = 0
        self.table = None
        self.headers = {}
        self.rows = []
        self.cells = None
        self.cell = None
        self.links = []
        self.is_header = False

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if tag in ("script", "style"):
            self.ignored += 1
        if self.ignored:
            return
        if tag == "table" and attrs.get("id") in ("mytable1", "mytable2"):
            self.table = attrs["id"]
        elif self.table and tag == "tr":
            self.cells, self.links, self.is_header = [], [], False
        elif self.cells is not None and tag in ("td", "th"):
            self.cell = []
            self.is_header |= tag == "th"
        elif self.cells is not None and tag == "a":
            self.links.append(attrs.get("href", ""))

    def handle_data(self, text):
        if not self.ignored and self.cell is not None:
            self.cell.append(text)

    def handle_endtag(self, tag):
        if tag in ("script", "style"):
            self.ignored = max(0, self.ignored - 1)
            return
        if self.ignored:
            return
        if tag in ("td", "th") and self.cell is not None:
            self.cells.append(" ".join(" ".join(self.cell).split()))
            self.cell = None
        elif tag == "tr" and self.cells is not None:
            if self.is_header:
                self.headers[self.table] = self.cells
            else:
                self.rows.append((self.table, self.cells, self.links))
            self.cells = None
        elif tag == "table":
            self.table = None


def parse_tasks(html, course, sno):
    account_matches(html, sno)
    parser = TaskTable()
    parser.feed(html)
    parser.close()
    if not parser.headers and not parser.rows:
        raise ValueError("没有找到课程任务列表，可能返回了其他页面。")
    tasks = {}
    for table, cells, links in parser.rows:
        if len(cells) < 2 or not valid_integer(cells[0]):
            continue
        taskid, name = cells[:2]
        submit_url, resubmit = "", False
        for href in links:
            target = urljoin(ORIGIN + "/Students/ClassTasks", href)
            try:
                parsed = school_url(target)
            except ValueError:
                continue
            query = parse_qs(parsed.query)
            if (parsed.path.lower() == "/students/classtasksubmit" and
                    query.get("classid") == [course.classid] and query.get("taskid") == [taskid] and
                    query.get("resubmit", ["0"]) in (["0"], ["1"])):
                submit_url = target
                resubmit = query.get("resubmit") == ["1"]
                break
        state = "待提交" if table == "mytable1" else "已完成"
        state += "，可重交" if resubmit else "" if submit_url or table == "mytable2" else "（无提交入口）"
        recorded_ip = ""
        for index, heading in enumerate(parser.headers.get(table, [])):
            if "提交IP" in heading.replace(" ", "").upper() and index < len(cells):
                recorded_ip = cells[index]
        if taskid in tasks:
            raise ValueError("同一页面出现重复任务编号，请在网页核对后重新读取。")
        tasks[taskid] = ReportTask(course.classid, taskid, name, state, submit_url, resubmit, recorded_ip)
    return tuple(tasks.values())


class FormMarkup(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.forms = []
        self.current = None
        self.ignored = 0
        self.textarea = None
        self.select = None

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if tag in ("script", "style"):
            self.ignored += 1
        if self.ignored:
            return
        if tag == "form":
            self.current = {"attrs": attrs, "fields": [], "files": [], "required": []}
            self.forms.append(self.current)
        if self.current is None:
            return
        name = attrs.get("name", "")
        if tag == "input" and name and "disabled" not in attrs:
            kind = attrs.get("type", "text").lower()
            if kind == "file":
                self.current["files"].append(attrs)
            elif kind not in ("button", "submit", "reset", "image", "password"):
                if kind not in ("checkbox", "radio") or "checked" in attrs:
                    self.current["fields"].append((name, attrs.get("value", "on" if kind in ("checkbox", "radio") else "")))
            if "required" in attrs and kind != "file":
                self.current["required"].append(name)
        elif tag == "textarea" and name and "disabled" not in attrs:
            self.textarea = (name, [])
            if "required" in attrs:
                self.current["required"].append(name)
        elif tag == "select" and name and "disabled" not in attrs:
            self.select = {"name": name, "options": [], "option": None}
        elif tag == "option" and self.select is not None:
            self.select["option"] = (attrs, [])

    def handle_data(self, text):
        if self.ignored:
            return
        if self.textarea is not None:
            self.textarea[1].append(text)
        if self.select is not None and self.select["option"] is not None:
            self.select["option"][1].append(text)

    def handle_endtag(self, tag):
        if tag in ("script", "style"):
            self.ignored = max(0, self.ignored - 1)
            return
        if self.ignored or self.current is None:
            return
        if tag == "textarea" and self.textarea is not None:
            name, text = self.textarea
            self.current["fields"].append((name, "".join(text)))
            self.textarea = None
        elif tag == "option" and self.select is not None and self.select["option"] is not None:
            attrs, text = self.select["option"]
            if "disabled" not in attrs:
                self.select["options"].append((attrs.get("value", "".join(text)), "selected" in attrs))
            self.select["option"] = None
        elif tag == "select" and self.select is not None:
            options = self.select["options"]
            selected = next((value for value, checked in options if checked), options[0][0] if options else "")
            self.current["fields"].append((self.select["name"], selected))
            self.select = None
        elif tag == "form":
            self.current = None


def cookie_digest(cookie):
    return hashlib.sha256(normalize_cookie(cookie).encode("utf-8")).hexdigest()


def parse_upload_form(html, task, sno, cookie):
    if not task.submit_url:
        raise ValueError("此任务没有网页提供的提交或重交入口。")
    account_matches(html, sno)
    parser = FormMarkup()
    parser.feed(html)
    parser.close()
    forms = [form for form in parser.forms if form["attrs"].get("id") == "submitform"]
    if len(forms) != 1:
        raise ValueError("没有唯一的上传表单，可能已提交、尚未开放或需要重新登录。")
    form = forms[0]
    attrs = form["attrs"]
    action = urljoin(task.submit_url, attrs.get("action", ""))
    if (school_url(action).path.lower() != SUBMIT_PATH.lower() or urlsplit(action).query or attrs.get("method", "get").lower() != "post"
            or attrs.get("enctype", "").lower() != "multipart/form-data"):
        raise ValueError("上传表单的地址、方法或类型与已知协议不一致，请在网页核对。")
    fields = form["fields"]
    values = {}
    for name, value in fields:
        if name in values:
            raise ValueError("上传表单出现重复字段，无法确定提交数据。")
        values[name] = value
    for name in ("semester", "classid", "itemid", "maxsize", "isresubmit", "submittype"):
        if not values.get(name):
            raise ValueError("上传表单缺少必要字段：" + name)
    if values["classid"] != task.classid or values["itemid"] != task.taskid:
        raise ValueError("上传表单的课程或任务编号与选择不一致。")
    if values["isresubmit"] != ("1" if task.resubmit else "0"):
        raise ValueError("表单重交状态与网页入口不一致，请重新读取任务。")
    if values["submittype"] != "file":
        raise ValueError("此任务要求文本或代码提交；当前功能只支持上传文件型实验报告。")
    if not values["maxsize"].isdigit() or int(values["maxsize"]) <= 0:
        raise ValueError("表单文件大小限制无效。")
    if len(form["files"]) != 1:
        raise ValueError("当前功能只支持一个报告文件。")
    required = list(form["required"])
    if "tasktitle" in values and re.search(r"\bvar\s+titlerequire\s*=\s*['\"]1['\"]", html):
        required.append("tasktitle")
    if any(name not in ("tasktitle", "groupsnos") and not values.get(name) for name in required):
        raise ValueError("此表单还有其他必填项，请通过学校网页提交。")
    editable = [name for name in ("tasktitle", "groupsnos") if name in values]
    if re.search(r"\bvar\s+isgroup\s*=\s*['\"]0['\"]", html) and "groupsnos" in editable:
        editable.remove("groupsnos")
    return UploadForm(sno, task, action, tuple(fields), form["files"][0]["name"],
                      min(int(values["maxsize"]), MAX_FILE_BYTES), form["files"][0].get("accept", ""),
                      tuple(required), tuple(editable), cookie_digest(cookie))


def report_get_request(url, cookie, address="", timeout="30"):
    parsed = school_url(url)
    if parsed.path.lower() not in ("/students/classtasks", "/students/classtasksubmit"):
        raise ValueError("不支持的报告读取地址。")
    headers = report_headers(address, ORIGIN + "/Students")
    return prepare_request(url, "GET", headers, normalize_cookie(cookie), "无正文", "", timeout=timeout)


def report_headers(address, referer):
    headers = ["Accept: text/html", "Referer: " + referer, "Origin: " + ORIGIN]
    if address.strip():
        try:
            ip = str(ipaddress.ip_address(address.strip()))
        except ValueError as exc:
            raise ValueError("请求头 IP 需为有效 IPv4 / IPv6；也可留空。") from exc
        headers.extend(("X-Forwarded-For: " + ip, "X-Real-IP: " + ip))
    return "\n".join(headers)


def require_html(response):
    if 300 <= response.status < 400:
        location = dict((key.lower(), value) for key, value in response.headers).get("location", "")
        if urlsplit(urljoin(ORIGIN, location)).path.lower().startswith("/login"):
            raise ValueError("服务器要求重新登录，请在浏览器登录并重新获取 Cookie。")
        messages = parse_qs(urlsplit(location).query).get("msg", [])
        message = decode_platform_message(messages[0]) if len(messages) == 1 else ""
        if message:
            raise ValueError("服务器返回提示：" + message)
        raise ValueError("服务器返回重定向，未跟随。请在原始响应中查看提示或刷新任务记录。")
    if response.status != 200:
        raise ValueError(f"读取失败：HTTP {response.status}。")
    return response_body_text(response)


def inspect_file(form, filename):
    if not filename:
        raise ValueError("请选择要上传的实验报告文件。")
    path = Path(filename).resolve()
    if not path.is_file():
        raise ValueError("报告文件不存在或不是普通文件。")
    stat = path.stat()
    if stat.st_size == 0:
        raise ValueError("报告文件为 0 字节，请先保存有效文档。")
    if stat.st_size > form.maxsize:
        raise ValueError(f"报告文件超过本次上传上限 {form.maxsize / 1024 / 1024:g} MiB。")
    return UploadFile(str(path), stat.st_size, stat.st_mtime_ns)


def form_fields(form, edits):
    values = dict(form.fields)
    for name, value in edits.items():
        if name not in form.editable or name not in values:
            raise ValueError("不可修改任务标识或隐藏字段。")
        if name == "groupsnos":
            value = value.replace("，", ",").replace(" ", "").strip(",")
            if value and not re.fullmatch(r"[0-9]{8,12}(?:,[0-9]{8,12})*", value):
                raise ValueError("组员学号需用英文逗号分隔。")
        values[name] = value
    for name in form.required:
        if not values.get(name, "").strip():
            raise ValueError("请填写表单必填项：" + name)
    return [(name, values[name]) for name, _value in form.fields]


def upload_preview(form, filename, cookie, address, edits, timeout="60"):
    if cookie_digest(cookie) != form.cookie_digest:
        raise ValueError("Cookie 已改变，请重新读取上传表单。")
    file = inspect_file(form, filename)
    fields = form_fields(form, edits)
    base = prepare_request(form.action, "POST", report_headers(address, form.task.submit_url), normalize_cookie(cookie), "无正文", "", timeout=timeout)
    header_preview = "\n".join(line for line in preview_request(base).splitlines() if not line.startswith("正文长度："))
    lines = [f"课程班：{form.task.classid} · 任务：{form.task.name}（{form.task.taskid}）", f"学号：{form.sno}",
             "提交类型：" + ("重交（isresubmit=1）\n" + RESUBMIT_IP_NOTE if form.task.resubmit else "首次提交（isresubmit=0）"),
             header_preview, "", "正文类型：multipart/form-data，发送时读取文件并生成 boundary", "文件：" + file.path,
             f"文件大小：{file.size} 字节；上限：{form.maxsize} 字节", "文件字段：" + form.file_field, "表单字段："]
    for name, value in fields:
        secret = bool(re.search(r"token|csrf|verification|password|auth", name, re.I))
        lines.append(name + "=" + ("<已隐藏>" if secret else value))
    return "\n".join(lines), file


def build_upload_request(form, file, cookie, address, edits, timeout="60"):
    if cookie_digest(cookie) != form.cookie_digest:
        raise ValueError("Cookie 已改变，请重新读取上传表单。")
    if inspect_file(form, file.path) != file:
        raise ValueError("报告文件在预览后发生变化，请重新选择并核对。")
    base = prepare_request(form.action, "POST", report_headers(address, form.task.submit_url), normalize_cookie(cookie), "无正文", "", timeout=timeout)
    body, content_type = build_multipart(form_fields(form, edits), form.file_field, file.path)
    if inspect_file(form, file.path) != file:
        raise ValueError("构建请求时文件发生变化，本次未发送。")
    headers = {**base.headers, "Content-Type": content_type}
    return PreparedRequest(base.url, "POST", headers, body, base.timeout)


def decode_platform_message(token):
    if not token or token[-1] not in "012" or len(token) > 16384:
        return ""
    try:
        data = base64.b64decode(token[:-1].replace("-", "+").replace("_", "/") + "=" * int(token[-1]), validate=True)
        try:
            return data.decode("utf-8")
        except UnicodeDecodeError:
            return data.decode("gb18030")
    except (ValueError, UnicodeDecodeError):
        return ""


def interpret_upload(response, task):
    headers = {key.lower(): value for key, value in response.headers}
    location = urljoin(ORIGIN, headers.get("location", ""))
    if 300 <= response.status < 400:
        target = urlsplit(location)
        if target.path.lower().startswith("/login"):
            return Outcome("login", "登录未通过", "请在浏览器重新登录并获取 Cookie。")
        query = parse_qs(target.query)
        try:
            school_url(location)
        except ValueError:
            return Outcome("unknown", "结果未确认", "服务器返回了其他地址，未跟随重定向。")
        if target.path.lower() == "/students/classtasks" and query.get("classid") == [task.classid]:
            messages = query.get("msg", [])
            message = decode_platform_message(messages[0]) if len(messages) == 1 else ""
            if message:
                if re.search(r"未成功|不成功|失败|错误|禁止|未提交|不能|不允许", message):
                    return Outcome("rejected", "服务器未确认提交成功", message)
                if re.match(r"^(?:(?:任务|报告|文件)?(?:提交|上传)成功|已成功(?:提交|上传))(?:[！!。，,\s]|$)", message):
                    return Outcome("success", "服务器提示提交成功", message + " 请刷新任务记录核对文件及记录 IP。")
                return Outcome("unknown", "服务器返回提示", message + " 请刷新任务记录核对。")
        return Outcome("unknown", "收到重定向，结果待核对", "未跟随重定向；请点击“读取 / 刷新任务”核对平台记录。")
    if not 200 <= response.status < 300:
        return Outcome("http_error", "上传请求未成功", f"HTTP {response.status}，请查看原始响应。")
    try:
        text = response_body_text(response)
    except ValueError as exc:
        return Outcome("unknown", "结果未确认", str(exc))
    if re.search(r"<title[^>]*>[^<]*(?:登录|Login)", text, re.I):
        return Outcome("login", "返回登录页面", "请在浏览器重新登录并获取 Cookie。")
    return Outcome("unknown", "已收到响应，结果待核对", "HTTP 状态本身不能确认报告已提交。请刷新任务记录核对，不会自动重传。")
