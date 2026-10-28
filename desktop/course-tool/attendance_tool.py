"""Manual desktop client. Imports and course selection never send network requests."""
from __future__ import annotations

import json
import queue
import threading
import tkinter as tk
import webbrowser
from datetime import datetime, timedelta, timezone
from pathlib import Path
from tkinter import filedialog, messagebox, ttk
from tkinter.scrolledtext import ScrolledText

from attendance_core import (
    SIGN_ENDPOINT, build_sign_request, extract_capture_cookie, interpret_response,
    normalize_cookie, parse_capture,
)
from request_core import display_response, preview_request, send_once
from local_browser_bridge import LocalBrowserBridge
from report_tool import ReportWindow
from build_info import source_label, window_title


CHINA_TIME = timezone(timedelta(hours=8))


def captured_time(value):
    try:
        moment = datetime.fromisoformat(value.replace("Z", "+00:00"))
        if moment.tzinfo:
            return moment.astimezone(CHINA_TIME).strftime("%Y-%m-%d %H:%M:%S 北京时间")
    except (ValueError, AttributeError):
        pass
    return value or "未提供时间"


class AttendanceTool(tk.Tk):
    def __init__(self):
        super().__init__()
        self.title(window_title("课程工具 · 签到与实验报告", __file__))
        self.geometry(f"{min(1100, self.winfo_screenwidth()-70)}x{min(890, self.winfo_screenheight()-90)}")
        self.minsize(880, 680)
        self.option_add("*Font", ("Microsoft YaHei UI", 10))
        self.capture = None
        self.busy = False
        self.browser_capturing = False
        self.capture_stop = threading.Event()
        self.close_pending = False
        self.local_bridge = None
        self.local_link = ""
        self.events = queue.Queue()
        self.cookie = tk.StringVar()
        self.show_cookie = tk.BooleanVar(value=False)
        self.auto_cookie = tk.BooleanVar(value=True)
        self.cookie_hint = tk.StringVar(value="含 Cookie 的 HAR 可以自动填入；也可粘贴 Cookie、请求标头或复制为 cURL 的文本。")
        self.ip = tk.StringVar()
        self.timeout = tk.StringVar(value="15")
        self.proxy = tk.BooleanVar(value=False)
        self.account = tk.StringVar(value="当前账号：等待导入")
        self.source = tk.StringVar(value="尚未导入 .devtools / HAR 文件")
        self.course_label = tk.StringVar(value="已选课程：尚未选择")
        self.readiness = tk.StringVar(value="请先导入学生主页的网络记录。")
        self.status = tk.StringVar(value="尚未发送请求。导入文件、填写 Cookie 和选择课程均在本地完成。")
        self.last_output = ""
        self.sending_summary = ""
        self._layout()
        for value in (self.cookie, self.ip, self.timeout):
            value.trace_add("write", lambda *_args: self.update_preview())
        self.update_preview()
        self.after(100, self.poll)
        self.protocol("WM_DELETE_WINDOW", self.close_window)

    def _layout(self):
        style = ttk.Style(self)
        style.configure("Heading.TLabel", font=("Microsoft YaHei UI", 18, "bold"))
        style.configure("Success.TLabel", foreground="#167047", font=("Microsoft YaHei UI", 12, "bold"))
        style.configure("Error.TLabel", foreground="#b43c30", font=("Microsoft YaHei UI", 12, "bold"))
        style.configure("Result.TLabel", font=("Microsoft YaHei UI", 12, "bold"))
        box = ttk.Frame(self, padding=14)
        box.pack(fill="both", expand=True)
        ttk.Label(box, text="课程签到与实验报告", style="Heading.TLabel").pack(anchor="w")
        ttk.Label(box, text=source_label(__file__), foreground="#666666", wraplength=1030).pack(anchor="w", pady=(2, 4))
        ttk.Label(box, text="本机浏览器已登录 → 自动获取 HAR / Cookie → 选择课程 → 手动发送一次", foreground="#555555").pack(anchor="w", pady=(3, 10))

        browser_row = ttk.Frame(box)
        browser_row.pack(fill="x", pady=(0, 8))
        self.browser_button = ttk.Button(browser_row, text="读取本机浏览器登录", command=self.capture_browser)
        self.browser_button.pack(side="left")
        self.cancel_browser_button = ttk.Button(browser_row, text="取消获取", command=self.cancel_capture, state="disabled")
        self.cancel_browser_button.pack(side="left", padx=8)
        self.copy_link_button = ttk.Button(browser_row, text="复制连接链接", command=self.copy_local_link, state="disabled")
        self.copy_link_button.pack(side="left", padx=(0, 8))
        ttk.Label(browser_row, text="沿用日常浏览器登录；首次需加载随工具附带的“浏览器扩展”。", foreground="#666666", wraplength=550).pack(side="left")

        source_row = ttk.Frame(box)
        source_row.pack(fill="x")
        self.import_button = ttk.Button(source_row, text="1. 导入 .devtools / HAR…", command=self.import_capture)
        self.import_button.pack(side="left")
        self.auto_cookie_toggle = ttk.Checkbutton(source_row, text="从 HAR 自动填入 Cookie", variable=self.auto_cookie)
        self.auto_cookie_toggle.pack(side="left", padx=(10, 0))
        ttk.Label(source_row, textvariable=self.account, font=("Microsoft YaHei UI", 11, "bold")).pack(side="left", padx=15)
        ttk.Label(box, textvariable=self.source, foreground="#666666", wraplength=1000).pack(anchor="w", pady=(5, 8))

        course_box = ttk.LabelFrame(box, text="2. 选择一门课程", padding=8)
        course_box.pack(fill="both", expand=True)
        columns = ("number", "name", "classid", "snapshot")
        self.courses = ttk.Treeview(course_box, columns=columns, show="headings", selectmode="browse", height=7)
        for key, title, width in (("number", "序号", 45), ("name", "课程名称", 470), ("classid", "课程班编号 classid", 175), ("snapshot", "抓包时的签到入口", 170)):
            self.courses.heading(key, text=title)
            self.courses.column(key, width=width, stretch=key == "name", anchor="w" if key == "name" else "center")
        scrollbar = ttk.Scrollbar(course_box, orient="vertical", command=self.courses.yview)
        self.courses.configure(yscrollcommand=scrollbar.set)
        scrollbar.pack(side="right", fill="y")
        self.courses.pack(fill="both", expand=True)
        self.courses.bind("<<TreeviewSelect>>", lambda _event: self.update_preview())
        ttk.Label(box, text="课程和按钮状态来自离线抓包，不代表当前实时状态；学号按抓包中的当前登录账号识别。", foreground="#666666").pack(anchor="w", pady=(4, 8))

        settings = ttk.LabelFrame(box, text="3. 填写请求信息", padding=10)
        settings.pack(fill="x")
        settings.columnconfigure(1, weight=1)
        ttk.Label(settings, text="完整 Cookie").grid(row=0, column=0, sticky="w", padx=(0, 8))
        self.cookie_entry = ttk.Entry(settings, textvariable=self.cookie, show="●")
        self.cookie_entry.grid(row=0, column=1, sticky="ew")
        self.paste_button = ttk.Button(settings, text="粘贴 Cookie / cURL", command=self.paste_cookie)
        self.paste_button.grid(row=0, column=2, padx=(7, 0))
        ttk.Checkbutton(settings, text="显示", variable=self.show_cookie, command=lambda: self.cookie_entry.configure(show="" if self.show_cookie.get() else "●")).grid(row=0, column=3, padx=(7, 0))
        ttk.Label(settings, textvariable=self.cookie_hint, foreground="#666666", wraplength=860).grid(row=1, column=1, columnspan=3, sticky="w", pady=(3, 8))
        ttk.Label(settings, text="请求头 IP").grid(row=2, column=0, sticky="w")
        ip_row = ttk.Frame(settings)
        ip_row.grid(row=2, column=1, columnspan=3, sticky="ew")
        self.ip_entry = ttk.Entry(ip_row, textvariable=self.ip, width=24)
        self.ip_entry.pack(side="left")
        ttk.Label(ip_row, text="  同时写入 X-Forwarded-For 和 X-Real-IP").pack(side="left")
        ttk.Label(ip_row, text="超时/秒").pack(side="left", padx=(15, 3))
        ttk.Entry(ip_row, textvariable=self.timeout, width=5).pack(side="left")
        ttk.Checkbutton(ip_row, text="使用系统/环境代理", variable=self.proxy).pack(side="right")

        action = ttk.Frame(box)
        action.pack(fill="x", pady=(10, 7))
        ttk.Label(action, textvariable=self.course_label, font=("Microsoft YaHei UI", 11, "bold")).pack(side="left", fill="x", expand=True)
        self.send_button = ttk.Button(action, text="4. 发送一次签到请求", command=self.send)
        self.send_button.pack(side="right")
        self.report_button = ttk.Button(action, text="提交实验报告…", command=self.open_reports)
        self.report_button.pack(side="right", padx=8)
        ttk.Label(box, textvariable=self.readiness, foreground="#555555", wraplength=1000).pack(anchor="w", pady=(0, 7))

        self.tabs = ttk.Notebook(box)
        self.tabs.pack(fill="both", expand=True)
        result_frame = ttk.Frame(self.tabs, padding=8)
        self.result_title = ttk.Label(result_frame, text="尚未发送签到请求", style="Result.TLabel")
        self.result_title.pack(anchor="w")
        self.result_message = ttk.Label(result_frame, text="选择课程并检查“请求预览”；只有点击发送按钮才会产生请求。", wraplength=1000)
        self.result_message.pack(anchor="w", pady=5)
        self.response_view = ScrolledText(result_frame, height=8, wrap="none", font=("Consolas", 10), state="disabled")
        self.response_view.pack(fill="both", expand=True)
        self.preview_view = ScrolledText(self.tabs, height=10, wrap="none", font=("Consolas", 10), state="disabled")
        self.tabs.add(result_frame, text="服务器结果 / 原始响应")
        self.tabs.add(self.preview_view, text="请求预览（Cookie 已隐藏）")
        footer = ttk.Frame(box)
        footer.pack(fill="x", pady=(7, 0))
        ttk.Label(footer, textvariable=self.status, wraplength=850).pack(side="left", fill="x", expand=True)
        self.save_button = ttk.Button(footer, text="保存本次结果…", command=self.save_result, state="disabled")
        self.save_button.pack(side="right")
        ttk.Label(box, text="请求头 IP 不改变真实网络出口；返回成功也不代表平台记录的 IP 已改变。", foreground="#666666").pack(anchor="w", pady=(6, 0))

    @staticmethod
    def display(widget, text):
        widget.configure(state="normal")
        widget.delete("1.0", "end")
        widget.insert("1.0", text)
        widget.configure(state="disabled")

    def selected_course(self):
        selected = self.courses.selection()
        if not self.capture or not selected:
            return None
        return next((course for course in self.capture.courses if course.classid == selected[0]), None)

    def prepared(self):
        course = self.selected_course()
        if not self.capture:
            raise ValueError("请先导入包含学生主页的 .devtools / HAR 文件。")
        if not course:
            raise ValueError("请从课程列表中选择一门课。")
        return build_sign_request(self.capture, course.classid, self.cookie.get(), self.ip.get(), self.timeout.get())

    def update_preview(self):
        if not hasattr(self, "preview_view"):
            return
        course = self.selected_course()
        self.course_label.set("已选课程：" + course.name if course else "已选课程：尚未选择")
        try:
            request = self.prepared()
        except (ValueError, OSError) as exc:
            self.readiness.set(str(exc))
            summary = f"POST {SIGN_ENDPOINT}\n\n"
            if self.capture:
                summary += f"当前账号：{self.capture.name}（{self.capture.sno}）\n"
            if course:
                summary += f"课程：{course.name}\nclassid：{course.classid}\n"
            self.display(self.preview_view, summary + "\n准备条件：" + str(exc))
            ready = False
        else:
            self.readiness.set("已就绪。请核对当前账号与课程，再手动发送一次请求。")
            self.display(self.preview_view, f"课程：{course.name}\n当前账号：{self.capture.name}（{self.capture.sno}）\n\n" + preview_request(request))
            ready = True
        self.send_button.configure(state="normal" if ready and not self.busy else "disabled")
        self.report_button.configure(state="normal" if self.capture and self.cookie.get().strip() and not self.busy else "disabled")

    def open_reports(self):
        if self.busy or self.capture is None:
            return
        try:
            cookie = normalize_cookie(self.cookie.get())
        except ValueError as exc:
            messagebox.showerror("请先获取登录信息", str(exc), parent=self)
            return
        course = self.selected_course()
        ReportWindow(self, self.capture, cookie, self.ip.get(), course.classid if course else "", self.proxy.get())

    def paste_cookie(self):
        try:
            value = normalize_cookie(self.clipboard_get())
        except (tk.TclError, ValueError) as exc:
            messagebox.showerror("无法粘贴 Cookie", str(exc), parent=self)
            return
        self.cookie.set(value)
        self.cookie_hint.set("已从剪贴板提取 Cookie，仅保存在本地内存；没有执行 cURL，也没有验证登录有效期。")
        self.status.set("Cookie 已填入，未执行命令或发送请求。")

    def set_busy(self, busy):
        self.busy = busy
        self.browser_button.configure(state="disabled" if busy else "normal")
        self.cancel_browser_button.configure(state="normal" if busy and self.browser_capturing and not self.capture_stop.is_set() else "disabled")
        self.copy_link_button.configure(state="normal" if busy and self.browser_capturing and not self.capture_stop.is_set() else "disabled")
        self.import_button.configure(state="disabled" if busy else "normal")
        self.auto_cookie_toggle.configure(state="disabled" if busy else "normal")
        self.paste_button.configure(state="disabled" if busy else "normal")
        self.cookie_entry.configure(state="disabled" if busy else "normal")
        self.ip_entry.configure(state="disabled" if busy else "normal")
        self.courses.state(["disabled"] if busy else ["!disabled"])
        self.update_preview()

    def capture_browser(self):
        if self.busy:
            return
        folder = getattr(self, "record_folder", Path(__file__).resolve().parent / "本机记录")
        try:
            if self.local_bridge is None:
                self.local_bridge = LocalBrowserBridge(folder, lambda kind, value: self.events.put((kind, value)))
            self.local_bridge.start()
        except OSError as exc:
            messagebox.showerror("本机连接启动失败", "无法使用本机端口 18763，请关闭其他版本的工具后重试。\n" + str(exc), parent=self)
            return
        self.local_link = self.local_bridge.session.begin()
        self.capture_stop.clear()
        self.browser_capturing = True
        self.set_busy(True)
        self.status.set("正在等待日常浏览器的扩展读取主页；请保持已登录的学校标签页打开。浏览器不一致时请复制连接链接。")
        try:
            opened = webbrowser.open(self.local_link, new=2)
        except (OSError, webbrowser.Error):
            opened = False
        if not opened:
            self.status.set("请点击“复制连接链接”，粘贴到已登录学校网站并加载扩展的浏览器中打开。")

    def copy_local_link(self):
        if self.local_link and self.browser_capturing:
            self.clipboard_clear()
            self.clipboard_append(self.local_link)
            self.status.set("连接链接已复制，请粘贴到已登录学校网站的同一个浏览器中打开；链接仅本次有效。")

    def cancel_capture(self):
        self.capture_stop.set()
        self.cancel_browser_button.configure(state="disabled")
        self.copy_link_button.configure(state="disabled")
        if self.local_bridge is not None:
            self.local_bridge.session.cancel()
        self.status.set("正在取消本次读取；日常浏览器窗口继续保留。")

    def close_window(self):
        if self.local_bridge is not None:
            self.local_bridge.stop()
        self.destroy()

    def import_capture(self):
        if self.busy:
            return
        filename = filedialog.askopenfilename(parent=self, title="选择学生主页导出的网络记录", filetypes=[("HAR / DevTools", "*.devtools *.har *.json"), ("全部文件", "*.*")])
        if not filename:
            return
        old_sno = self.capture.sno if self.capture else None
        read_cookie = self.auto_cookie.get()
        self.set_busy(True)
        self.status.set("正在本地解析账号和课程列表，不会发送请求…")
        def worker():
            try:
                path = Path(filename)
                if path.stat().st_size > 100 * 1024 * 1024:
                    raise ValueError("记录超过 100 MiB，请只记录学生主页加载后重新导出。")
                document = json.loads(path.read_text(encoding="utf-8-sig"))
                capture = parse_capture(document)
                saved_cookie = extract_capture_cookie(document, capture) if read_cookie else None
                self.events.put(("import_ok", (capture, path.name, old_sno, saved_cookie, read_cookie)))
            except Exception as exc:
                self.events.put(("import_error", str(exc)))
        threading.Thread(target=worker, daemon=True).start()

    def send(self):
        if self.busy:
            return
        try:
            request = self.prepared()
        except (ValueError, OSError) as exc:
            messagebox.showerror("请检查请求信息", str(exc), parent=self)
            return
        course = self.selected_course()
        stamp = datetime.now(CHINA_TIME).strftime("%Y-%m-%d %H:%M:%S 北京时间")
        self.sending_summary = f"发送时间：{stamp}\n课程：{course.name}\n当前账号：{self.capture.name}（{self.capture.sno}）\n请求头 IP：{self.ip.get().strip()}\n\n" + preview_request(request)
        use_proxy = self.proxy.get()
        self.set_busy(True)
        self.last_output = ""
        self.save_button.configure(state="disabled")
        self.result_title.configure(text="正在发送一次请求…", style="Result.TLabel")
        self.result_message.configure(text=f"课程：{course.name}；不会自动重试或跟随重定向。")
        self.display(self.response_view, "等待服务器响应…")
        self.tabs.select(0)
        self.status.set("正在等待服务器响应；其他课程不会被发送。")
        def worker():
            try:
                response = send_once(request, use_proxy)
                outcome = interpret_response(response)
                self.events.put(("send_ok", (outcome, display_response(response))))
            except Exception as exc:
                self.events.put(("send_error", str(exc)))
        threading.Thread(target=worker, daemon=True).start()

    def poll(self):
        if self.local_bridge is not None:
            self.local_bridge.session.expire()
        try:
            kind, value = self.events.get_nowait()
        except queue.Empty:
            self.after(100, self.poll)
            return
        if kind == "local_import":
            capture, filename, saved_cookie = value
            old_sno = self.capture.sno if self.capture else None
            kind = "import_ok"
            value = (capture, filename, old_sno, saved_cookie, True)
        if kind == "import_ok":
            capture, filename, old_sno, saved_cookie, read_cookie = value
            if old_sno and old_sno != capture.sno:
                self.cookie.set("")
            self.capture = capture
            if saved_cookie:
                self.cookie.set(saved_cookie.value)
                self.cookie_hint.set("已从该账号的 HAR 请求自动读取 Cookie；有效期未经联网验证，无需更改浏览器的 HttpOnly 设置。")
            elif read_cookie:
                self.cookie_hint.set("HAR 未包含该账号的 Cookie。可导出包含敏感数据的 HAR，或点击“粘贴 Cookie / cURL”。")
            else:
                self.cookie_hint.set("未启用自动读取 Cookie；请填写与当前账号对应的 Cookie。")
            for item in self.courses.get_children():
                self.courses.delete(item)
            for number, course in enumerate(capture.courses, 1):
                self.courses.insert("", "end", iid=course.classid, values=(number, course.name, course.classid, "有按钮" if course.button_seen else "未见按钮"))
            self.account.set(f"当前账号：{capture.name}（{capture.sno}）")
            self.source.set(f"{filename} · {len(capture.courses)} 门课程 · 快照：{captured_time(capture.captured_at)}")
            self.last_output = ""
            self.save_button.configure(state="disabled")
            self.result_title.configure(text="课程解析完成，尚未发送", style="Result.TLabel")
            self.result_message.configure(text="请选择课程并填写请求头 IP。" + ("Cookie 已从 HAR 自动填入。" if saved_cookie else "Cookie 可通过剪贴板提取。"))
            self.display(self.response_view, "")
            self.status.set("已离线解析课程" + ("并读取 Cookie" if saved_cookie else "") + "。请选择课程；导入不会自动签到。")
        elif kind in ("import_error", "browser_error"):
            if not self.close_pending:
                messagebox.showerror("获取失败" if kind == "browser_error" else "导入失败", value, parent=self)
            self.status.set("本次导入失败。" + ("仍保留先前已导入的账号和课程。" if self.capture else "请重新导出学生主页。"))
        elif kind == "browser_cancelled":
            self.status.set(value + " 没有改变原有账号和课程。")
        elif kind == "send_ok":
            outcome, raw = value
            style = "Success.TLabel" if outcome.kind == "success" else "Error.TLabel"
            self.result_title.configure(text=outcome.title, style=style)
            self.result_message.configure(text=outcome.message)
            self.display(self.response_view, raw)
            self.last_output = self.sending_summary + "\n\n业务结果：" + outcome.title + "\n" + outcome.message + "\n\n" + raw
            self.save_button.configure(state="normal")
            self.status.set("本次请求结束，未自动重试。实际签到及记录 IP 以平台记录为准。")
        elif kind == "send_error":
            self.result_title.configure(text="未取得完整响应", style="Error.TLabel")
            self.result_message.configure(text="请求可能未到达或响应未完整返回，未自动重试；重复发送前请先核对平台记录。")
            self.display(self.response_view, value)
            self.last_output = self.sending_summary + "\n\n请求异常：" + value
            self.save_button.configure(state="normal")
            self.status.set("本次请求异常；没有自动重试。")
        self.browser_capturing = False
        if self.close_pending:
            self.destroy()
            return
        self.set_busy(False)
        self.after(100, self.poll)

    def save_result(self):
        if not self.last_output:
            return
        filename = filedialog.asksaveasfilename(parent=self, title="保存本次结果", initialfile="签到请求结果.txt", defaultextension=".txt", filetypes=[("文本文件", "*.txt")])
        if filename:
            try:
                Path(filename).write_text(self.last_output, encoding="utf-8")
            except OSError as exc:
                messagebox.showerror("保存失败", str(exc), parent=self)
            else:
                self.status.set("结果已保存；请求预览中的 Cookie 已隐藏。")


if __name__ == "__main__":
    AttendanceTool().mainloop()
