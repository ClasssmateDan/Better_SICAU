"""Report upload window. Networking occurs only after explicit button clicks."""
from __future__ import annotations

import queue
import threading
import tkinter as tk
from pathlib import Path
from tkinter import filedialog, messagebox, ttk
from tkinter.scrolledtext import ScrolledText

from attendance_core import ORIGIN, normalize_cookie
from report_core import (
    RESUBMIT_IP_NOTE, build_upload_request, interpret_upload, parse_tasks, parse_upload_form,
    report_get_request, require_html, upload_preview,
)
from request_core import display_response, send_once
from build_info import source_label, window_title


class ReportWindow(tk.Toplevel):
    def __init__(self, parent, capture, cookie, address="", selected_classid="", proxy=False):
        super().__init__(parent)
        self.title(window_title("实验报告提交", __file__))
        self.geometry(f"{min(1100, self.winfo_screenwidth()-70)}x{min(920, self.winfo_screenheight()-85)}")
        self.minsize(900, 700)
        self.transient(parent)
        self.capture = capture
        self.cookie = normalize_cookie(cookie)
        self.busy = False
        self.close_requested = False
        self.events = queue.Queue()
        self.tasks = ()
        self.form = None
        self.last_result = ""
        self.ip = tk.StringVar(value=address)
        self.timeout = tk.StringVar(value="60")
        self.proxy = tk.BooleanVar(value=proxy)
        self.course_labels = {f"{course.name}  [{course.classid}]": course for course in capture.courses}
        selected_label = next((label for label, course in self.course_labels.items() if course.classid == selected_classid), "")
        self.course = tk.StringVar(value=selected_label)
        self.filename = tk.StringVar()
        self.tasktitle = tk.StringVar()
        self.groupsnos = tk.StringVar()
        self.form_hint = tk.StringVar(value="先读取任务，选择实验，再读取上传表单。")
        self.status = tk.StringVar(value="尚未发送请求。读取任务、读取表单和上传文件均需手动操作。")
        self._layout()
        for value in (self.ip, self.timeout, self.filename, self.tasktitle, self.groupsnos):
            value.trace_add("write", lambda *_args: self.update_preview())
        self.protocol("WM_DELETE_WINDOW", self.close_window)
        self.after(100, self.poll)
        self.update_preview()
        self.grab_set()

    def _layout(self):
        box = ttk.Frame(self, padding=12)
        box.pack(fill="both", expand=True)
        ttk.Label(box, text="提交实验报告", font=("Microsoft YaHei UI", 17, "bold")).pack(anchor="w")
        ttk.Label(box, text=source_label(__file__), foreground="#666666", wraplength=1030).pack(anchor="w", pady=(2, 4))
        ttk.Label(box, text=f"当前账号：{self.capture.name}（{self.capture.sno}） · 沿用主窗口 Cookie；失效时请关闭本窗口重新获取。", wraplength=1030).pack(anchor="w", pady=(3, 8))
        course_row = ttk.Frame(box)
        course_row.pack(fill="x")
        ttk.Label(course_row, text="课程").pack(side="left")
        self.course_picker = ttk.Combobox(course_row, textvariable=self.course, values=tuple(self.course_labels), state="readonly")
        self.course_picker.pack(side="left", fill="x", expand=True, padx=8)
        self.course_picker.bind("<<ComboboxSelected>>", lambda _event: self.reset_course())
        self.tasks_button = ttk.Button(course_row, text="1. 读取 / 刷新任务", command=self.read_tasks)
        self.tasks_button.pack(side="right")
        ip_row = ttk.Frame(box)
        ip_row.pack(fill="x", pady=8)
        ttk.Label(ip_row, text="请求头 IP").pack(side="left")
        self.ip_entry = ttk.Entry(ip_row, textvariable=self.ip, width=25)
        self.ip_entry.pack(side="left", padx=8)
        ttk.Label(ip_row, text="可留空；填写时写入两个 IP 头").pack(side="left")
        ttk.Label(ip_row, text="超时/秒").pack(side="left", padx=(18, 4))
        self.timeout_entry = ttk.Entry(ip_row, textvariable=self.timeout, width=5)
        self.timeout_entry.pack(side="left")
        self.proxy_toggle = ttk.Checkbutton(ip_row, text="使用系统/环境代理", variable=self.proxy)
        self.proxy_toggle.pack(side="right")

        task_box = ttk.LabelFrame(box, text="2. 选择实验任务", padding=6)
        task_box.pack(fill="both", expand=True)
        self.tree = ttk.Treeview(task_box, columns=("id", "name", "state", "ip"), show="headings", selectmode="browse", height=5)
        for key, label, width in (("id", "编号", 60), ("name", "实验 / 任务名称", 490), ("state", "网页状态", 175), ("ip", "平台提交 IP", 170)):
            self.tree.heading(key, text=label)
            self.tree.column(key, width=width, stretch=key == "name", anchor="w")
        scroll = ttk.Scrollbar(task_box, orient="vertical", command=self.tree.yview)
        self.tree.configure(yscrollcommand=scroll.set)
        scroll.pack(side="right", fill="y")
        self.tree.pack(fill="both", expand=True)
        self.tree.bind("<<TreeviewSelect>>", lambda _event: self.reset_form())
        form_row = ttk.Frame(box)
        form_row.pack(fill="x", pady=8)
        self.form_button = ttk.Button(form_row, text="3. 读取上传表单", command=self.read_form)
        self.form_button.pack(side="left")
        ttk.Label(form_row, textvariable=self.form_hint, wraplength=800).pack(side="left", padx=10)

        ttk.Label(box, text=RESUBMIT_IP_NOTE, foreground="#9b5200", wraplength=1030).pack(anchor="w", pady=(0, 6))

        options = ttk.Frame(box)
        options.pack(fill="x", pady=(0, 7))
        ttk.Label(options, text="任务标题").pack(side="left")
        self.title_entry = ttk.Entry(options, textvariable=self.tasktitle, width=32, state="disabled")
        self.title_entry.pack(side="left", padx=6)
        ttk.Label(options, text="组员学号（表单有此项时可填）").pack(side="left", padx=(12, 0))
        self.group_entry = ttk.Entry(options, textvariable=self.groupsnos, width=29, state="disabled")
        self.group_entry.pack(side="left", fill="x", expand=True, padx=6)
        file_row = ttk.Frame(box)
        file_row.pack(fill="x")
        ttk.Label(file_row, text="报告文件").pack(side="left")
        ttk.Entry(file_row, textvariable=self.filename, state="readonly").pack(side="left", fill="x", expand=True, padx=8)
        self.file_button = ttk.Button(file_row, text="4. 选择文件…", command=self.pick_file)
        self.file_button.pack(side="right")

        actions = ttk.Frame(box)
        actions.pack(fill="x", pady=8)
        self.preview_button = ttk.Button(actions, text="预览上传内容", command=self.show_preview)
        self.preview_button.pack(side="left")
        self.submit_button = ttk.Button(actions, text="5. 上传并提交一次", command=self.submit)
        self.submit_button.pack(side="right")
        self.result_title = ttk.Label(box, text="尚未提交", style="Result.TLabel")
        self.result_title.pack(anchor="w")
        self.result_message = ttk.Label(box, text="提交前核对课程、实验任务、文件及 IP。", wraplength=1030)
        self.result_message.pack(anchor="w", pady=(3, 5))
        self.tabs = ttk.Notebook(box)
        self.tabs.pack(fill="both", expand=True)
        self.preview_view = ScrolledText(self.tabs, height=8, wrap="none", font=("Consolas", 10), state="disabled")
        self.response_view = ScrolledText(self.tabs, height=8, wrap="none", font=("Consolas", 10), state="disabled")
        self.tabs.add(self.preview_view, text="上传预览（Cookie 已隐藏）")
        self.tabs.add(self.response_view, text="服务器原始响应")
        footer = ttk.Frame(box)
        footer.pack(fill="x", pady=(7, 0))
        ttk.Label(footer, textvariable=self.status, wraplength=850).pack(side="left", fill="x", expand=True)
        self.save_button = ttk.Button(footer, text="保存本次结果…", command=self.save_result, state="disabled")
        self.save_button.pack(side="right")
        ttk.Label(box, text="请求头 IP 不改变真实网络出口；提交后可刷新任务查看平台记录的 IP。", foreground="#666666").pack(anchor="w", pady=(5, 0))

    @staticmethod
    def display(widget, text):
        widget.configure(state="normal")
        widget.delete("1.0", "end")
        widget.insert("1.0", text)
        widget.configure(state="disabled")

    def selected_task(self):
        selection = self.tree.selection()
        return next((task for task in self.tasks if selection and task.taskid == selection[0]), None)

    def reset_course(self):
        self.tasks = ()
        for item in self.tree.get_children():
            self.tree.delete(item)
        self.reset_form()

    def reset_form(self):
        self.form = None
        self.filename.set("")
        self.tasktitle.set("")
        self.groupsnos.set("")
        self.form_hint.set("请选择实验任务，再读取它的上传表单。")
        self.update_preview()

    def edits(self):
        if self.form is None:
            return {}
        return {name: variable.get() for name, variable in (("tasktitle", self.tasktitle), ("groupsnos", self.groupsnos)) if name in self.form.editable}

    def preview(self):
        if self.form is None:
            raise ValueError("请先读取所选实验的上传表单。")
        return upload_preview(self.form, self.filename.get(), self.cookie, self.ip.get(), self.edits(), self.timeout.get())

    def update_preview(self):
        if not hasattr(self, "preview_view"):
            return
        try:
            summary, _file = self.preview()
            ready = True
        except (ValueError, OSError) as exc:
            summary = str(exc)
            ready = False
        self.display(self.preview_view, summary)
        editable = self.form.editable if self.form else ()
        self.title_entry.configure(state="normal" if "tasktitle" in editable and not self.busy else "disabled")
        self.group_entry.configure(state="normal" if "groupsnos" in editable and not self.busy else "disabled")
        self.submit_button.configure(state="normal" if ready and not self.busy else "disabled")
        task = self.selected_task()
        self.form_button.configure(state="normal" if task and task.submit_url and not self.busy else "disabled")
        self.tasks_button.configure(state="normal" if self.course.get() in self.course_labels and not self.busy else "disabled")
        self.file_button.configure(state="normal" if self.form and not self.busy else "disabled")
        self.preview_button.configure(state="normal" if ready and not self.busy else "disabled")

    def set_busy(self, busy):
        self.busy = busy
        self.course_picker.configure(state="disabled" if busy else "readonly")
        self.ip_entry.configure(state="disabled" if busy else "normal")
        self.timeout_entry.configure(state="disabled" if busy else "normal")
        self.proxy_toggle.configure(state="disabled" if busy else "normal")
        self.tree.state(["disabled"] if busy else ["!disabled"])
        self.update_preview()

    def run_worker(self, kind, request, parse, summary):
        use_proxy = self.proxy.get()
        self.set_busy(True)
        self.status.set("正在" + summary + "；不自动重试、不跟随重定向。")
        self.last_result = ""
        self.save_button.configure(state="disabled")
        def worker():
            raw = ""
            try:
                response = send_once(request, use_proxy)
                raw = display_response(response)
                value = parse(response)
                self.events.put((kind, (value, raw)))
            except Exception as exc:
                self.events.put(("read_error", (str(exc), raw)))
        threading.Thread(target=worker, daemon=True).start()

    def read_tasks(self):
        if self.busy:
            return
        course = self.course_labels.get(self.course.get())
        if course is None:
            return
        try:
            request = report_get_request(ORIGIN + "/Students/ClassTasks?classid=" + course.classid, self.cookie, self.ip.get(), self.timeout.get())
        except ValueError as exc:
            messagebox.showerror("读取条件不完整", str(exc), parent=self)
            return
        self.reset_form()
        self.run_worker("tasks_ok", request, lambda response: parse_tasks(require_html(response), course, self.capture.sno), "读取任务记录")

    def read_form(self):
        if self.busy:
            return
        task = self.selected_task()
        if task is None or not task.submit_url:
            return
        try:
            request = report_get_request(task.submit_url, self.cookie, self.ip.get(), self.timeout.get())
        except ValueError as exc:
            messagebox.showerror("读取条件不完整", str(exc), parent=self)
            return
        self.reset_form()
        self.run_worker("form_ok", request, lambda response: parse_upload_form(require_html(response), task, self.capture.sno, self.cookie), "读取上传表单")

    def pick_file(self):
        filename = filedialog.askopenfilename(parent=self, title="选择要提交的实验报告", filetypes=[("报告文件", "*.doc *.docx *.pdf *.zip *.rar *.txt"), ("所有文件", "*.*")])
        if filename:
            self.filename.set(filename)
            self.tabs.select(self.preview_view)

    def show_preview(self):
        self.update_preview()
        self.tabs.select(self.preview_view)

    def submit(self):
        if self.busy:
            return
        try:
            summary, file = self.preview()
        except (ValueError, OSError) as exc:
            messagebox.showerror("无法提交", str(exc), parent=self)
            return
        form, edits, address, timeout, use_proxy = self.form, self.edits(), self.ip.get(), self.timeout.get(), self.proxy.get()
        self.set_busy(True)
        self.status.set("正在读取文件并上传一次；不自动重试。")
        self.result_title.configure(text="正在上传报告…", style="Result.TLabel")
        self.result_message.configure(text=f"{form.task.name} · {Path(file.path).name} · {file.size} 字节")
        self.last_result = ""
        self.save_button.configure(state="disabled")
        def worker():
            try:
                request = build_upload_request(form, file, self.cookie, address, edits, timeout)
                response = send_once(request, use_proxy)
                outcome = interpret_upload(response, form.task)
                self.events.put(("submit_ok", (outcome, summary, display_response(response))))
            except Exception as exc:
                self.events.put(("submit_error", (str(exc), summary)))
        threading.Thread(target=worker, daemon=True).start()

    def poll(self):
        try:
            kind, payload = self.events.get_nowait()
        except queue.Empty:
            self.after(100, self.poll)
            return
        if kind == "tasks_ok":
            self.tasks, raw = payload
            for item in self.tree.get_children():
                self.tree.delete(item)
            for task in self.tasks:
                self.tree.insert("", "end", iid=task.taskid, values=(task.taskid, task.name, task.state, task.recorded_ip))
            self.last_result = raw
            self.display(self.response_view, raw)
            self.result_title.configure(text="任务记录已读取", style="Result.TLabel")
            self.result_message.configure(text="已完成状态及提交 IP 来自本次读取的平台记录；选择任务后可读取上传表单。")
            self.status.set(f"已读取 {len(self.tasks)} 项任务；平台已记录的提交 IP 显示在列表中。请选择任务并读取上传表单。")
        elif kind == "form_ok":
            form, raw = payload
            self.form = form
            self.tasktitle.set(dict(form.fields).get("tasktitle", ""))
            self.groupsnos.set(dict(form.fields).get("groupsnos", ""))
            suffix = " · 标题必填" if "tasktitle" in form.required else ""
            mode = "重交" if form.task.resubmit else "首次提交"
            self.form_hint.set(f"{form.task.name} · {mode} · 学期 {dict(form.fields)['semester']} · ≤{form.maxsize/1024/1024:g} MiB" + suffix)
            self.last_result = raw
            self.display(self.response_view, raw)
            self.result_title.configure(text="上传表单已读取，尚未提交", style="Result.TLabel")
            self.result_message.configure(text="请选择报告文件，核对任务、文件及 IP 后手动上传。")
            self.status.set("上传表单已读取；请选择文件，核对预览后手动上传。")
        elif kind == "read_error":
            message, raw = payload
            self.result_title.configure(text="读取失败", style="Error.TLabel")
            self.result_message.configure(text=message)
            self.last_result = message + "\n\n" + raw
            self.display(self.response_view, self.last_result)
            self.tabs.select(self.response_view)
            self.status.set("未上传任何报告，请查看提示。")
        elif kind == "submit_ok":
            outcome, summary, raw = payload
            self.form = None  # A new form must be explicitly read before another POST.
            self.form_hint.set("本次提交已结束；再次上传前请刷新任务并重新读取表单。")
            self.result_title.configure(text=outcome.title, style="Success.TLabel" if outcome.kind == "success" else "Error.TLabel")
            self.result_message.configure(text=outcome.message)
            self.last_result = summary + "\n\n" + outcome.title + "\n" + outcome.message + "\n\n" + raw
            self.display(self.response_view, raw)
            self.tabs.select(self.response_view)
            self.status.set("本次上传结束，未自动重传。可点击“读取 / 刷新任务”核对提交状态及记录 IP。")
        elif kind == "submit_error":
            message, summary = payload
            self.form = None
            self.form_hint.set("请求异常，请先刷新任务核对记录，再决定是否重新读取表单上传。")
            self.result_title.configure(text="未取得完整提交结果", style="Error.TLabel")
            self.result_message.configure(text="未自动重传；请求可能已到达服务器，请先刷新任务记录核对。")
            self.last_result = summary + "\n\n请求异常：" + message
            self.display(self.response_view, self.last_result)
            self.tabs.select(self.response_view)
            self.status.set("本次请求异常，未自动重传。")
        self.save_button.configure(state="normal" if self.last_result else "disabled")
        if self.close_requested:
            self.destroy()
            return
        self.set_busy(False)
        self.after(100, self.poll)

    def close_window(self):
        if self.busy:
            self.close_requested = True
            self.status.set("正在等待本次请求结束，结束后关闭窗口。")
        else:
            self.destroy()

    def save_result(self):
        if not self.last_result:
            return
        filename = filedialog.asksaveasfilename(parent=self, title="保存本次结果", initialfile="实验报告请求结果.txt", defaultextension=".txt", filetypes=[("文本文件", "*.txt")])
        if filename:
            try:
                Path(filename).write_text(self.last_result, encoding="utf-8")
            except OSError as exc:
                messagebox.showerror("保存失败", str(exc), parent=self)
            else:
                self.status.set("本次结果已保存。")
