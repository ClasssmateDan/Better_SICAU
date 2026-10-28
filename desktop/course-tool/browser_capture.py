"""Record a student homepage HAR after the user logs in in a visible browser.

Importing this module does not start a browser or connect to any website.
"""
from __future__ import annotations

import contextlib
import json
import os
import re
import threading
import time
import uuid
from pathlib import Path
from urllib.parse import urlsplit

from attendance_core import HOST, ORIGIN, CoursePage, extract_capture_cookie, parse_capture


# Exclude login POST bodies and unrelated browsing from the saved archive.
HOME_URLS = re.compile(r"^https://eol\.sicau\.edu\.cn/Students(?:/(?:Default(?:\.cshtml)?)?)?(?:\?.*)?$", re.I)


class CaptureCancelled(Exception):
    pass


def browser_channel():
    roots = [Path(os.environ.get(name, fallback)) for name, fallback in (
        ("PROGRAMFILES(X86)", r"C:\Program Files (x86)"),
        ("PROGRAMFILES", r"C:\Program Files"),
        ("LOCALAPPDATA", str(Path.home() / "AppData" / "Local")),
    )]
    for channel, relative in (("msedge", "Microsoft/Edge/Application/msedge.exe"),
                              ("chrome", "Google/Chrome/Application/chrome.exe")):
        if any((root / relative).is_file() for root in roots):
            return channel
    raise ValueError("未找到 Edge 或 Chrome，请先安装其中一个浏览器。")


def find_student_frame(context):
    """Inspect already loaded pages/frames; this never issues an HTTP request."""
    for page in context.pages:
        for frame in page.frames:
            parsed = urlsplit(frame.url)
            if parsed.hostname != HOST or not parsed.path.lower().startswith("/students"):
                continue
            try:
                html = frame.content()
            except Exception:
                continue  # A frame can navigate or disappear while the user logs in.
            parser = CoursePage()
            parser.feed(html)
            parser.close()
            if parser.identity() and parser.course_names:
                return frame
    return None


def record_browser_har(output, progress=None, cancel=None, *, timeout=300,
                       channel=None, playwright_factory=None):
    """Open a fresh visible browser; save one validated HAR and return its data.

    The caller must invoke this explicitly. There is no attendance submission.
    A temporary browser session avoids reading the user's everyday profile.
    """
    output = Path(output).resolve()
    if output.suffix.lower() != ".har":
        raise ValueError("请选择以 .har 结尾的保存路径。")
    if output.exists():
        raise ValueError("文件已经存在，请选择新的 HAR 文件名。")
    if not output.parent.is_dir():
        raise ValueError("HAR 保存目录不存在。")
    if playwright_factory is None:
        try:
            from playwright.sync_api import sync_playwright
        except ImportError as exc:
            raise ValueError("浏览器组件尚未安装。请关闭工具，运行“安装浏览器组件.cmd”，完成后重新启动工具。") from exc
        playwright_factory = sync_playwright
    selected_channel = channel or browser_channel()
    report = progress or (lambda _message: None)
    stop = cancel or threading.Event()
    partial = output.with_name(output.name + "." + uuid.uuid4().hex + ".part")
    context = browser = None
    try:
        if stop.is_set():
            raise CaptureCancelled("已取消浏览器获取。")
        report("正在打开独立浏览器窗口；请在新窗口登录并进入学生主页。")
        with playwright_factory() as playwright:
            try:
                browser = playwright.chromium.launch(channel=selected_channel, headless=False)
                context = browser.new_context(
                    record_har_path=str(partial),
                    record_har_mode="full",
                    record_har_content="embed",
                    record_har_url_filter=HOME_URLS,
                    service_workers="block",
                )
                context.set_default_timeout(5000)
                page = context.new_page()
                page.goto(ORIGIN + "/Students", wait_until="domcontentloaded", timeout=30000)
                deadline = time.monotonic() + timeout
                while True:
                    if stop.is_set():
                        raise CaptureCancelled("已取消浏览器获取。")
                    if time.monotonic() >= deadline:
                        raise ValueError("等待登录超时（5 分钟）。请重新点击浏览器自动获取。")
                    if not context.pages:
                        raise CaptureCancelled("浏览器窗口已关闭，本次获取已取消。")
                    frame = find_student_frame(context)
                    if frame is not None:
                        report("已识别学生主页，正在读取一次主页以记录当前 Cookie…")
                        # One ordinary GET after login ensures cookies appear in the HAR request.
                        frame.goto(ORIGIN + "/Students", wait_until="load", timeout=30000)
                        if stop.is_set():
                            raise CaptureCancelled("已取消浏览器获取。")
                        break
                    try:
                        context.pages[0].wait_for_timeout(400)
                    except Exception:
                        if not context.pages:
                            raise CaptureCancelled("浏览器窗口已关闭，本次获取已取消。") from None
                        raise
                report("正在关闭临时浏览器并保存 HAR…")
                context.close()  # Playwright flushes HAR here, before it is read.
                context = None
                if partial.stat().st_size > 100 * 1024 * 1024:
                    raise ValueError("生成的 HAR 超过 100 MiB，请只加载学生主页后重试。")
                document = json.loads(partial.read_text(encoding="utf-8-sig"))
                capture = parse_capture(document)
                cookie = extract_capture_cookie(document, capture)
                if cookie is None:
                    raise ValueError("已读到课程，但没有录到该账号的请求 Cookie。请重新登录后获取，或使用复制为 cURL 的方式。")
                if stop.is_set():
                    raise CaptureCancelled("已取消浏览器获取。")
                # Exclusive creation also protects an existing file created during login.
                data = partial.read_bytes()
                created = False
                try:
                    with output.open("xb") as destination:
                        created = True
                        destination.write(data)
                except OSError:
                    if created:
                        output.unlink(missing_ok=True)
                    raise
                return capture, cookie
            except Exception:
                if stop.is_set():
                    raise CaptureCancelled("已取消浏览器获取。") from None
                raise
            finally:
                if context is not None:
                    with contextlib.suppress(Exception):
                        context.close()
                if browser is not None:
                    with contextlib.suppress(Exception):
                        browser.close()
    finally:
        partial.unlink(missing_ok=True)
