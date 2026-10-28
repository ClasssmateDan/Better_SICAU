"""Packaged companion. The timetable only selects a course; every request stays manual."""
import argparse
import ctypes
import hashlib
import json
import os
import socket
import sys
import time
import traceback
import webbrowser
from pathlib import Path
import tkinter as tk
from tkinter import ttk

from attendance_tool import AttendanceTool
from attendance_core import Capture, Course, SavedCookie, ORIGIN, normalize_cookie
from report_tool import ReportWindow
from integration import matched_course, read_request, write_json


class IntegratedTool(AttendanceTool):
    def __init__(self, data_dir, context_file, smoke=False):
        self.data_dir = Path(data_dir).resolve()
        self.context_file = Path(context_file).resolve()
        self.integration_smoke = smoke
        self.integration_request = None
        self.attempted_context = None
        self.active_report = None
        super().__init__()
        self.title("课间 · 课程签到与作业")
        self.record_folder = self.data_dir / "records"
        footer = ttk.Frame(self, padding=(14, 3, 14, 6))
        footer.pack(side="bottom", fill="x")
        self.integration_hint = tk.StringVar(value="从课表带入的课程会在读取本人登录后核对；不会自动签到或上传。")
        ttk.Label(footer, textvariable=self.integration_hint, foreground="#626f7e", wraplength=830).pack(side="left", fill="x", expand=True)
        ttk.Button(footer, text="扩展文件夹", command=self.open_extension).pack(side="right", padx=(8, 0))
        if smoke:
            self.withdraw()
            self.after(30, self.seed_smoke_capture)
            self.after(30000, self.close_window)
        self.after(100, self.poll_context)

    def open_extension(self):
        folder = self.data_dir / "browser-extension"
        if folder.is_dir():
            os.startfile(str(folder))
        else:
            self.integration_hint.set("请从电脑版课表的“课程工具”中打开扩展文件夹。")

    def open_reports(self):
        if self.active_report is not None and self.active_report.winfo_exists():
            if not self.integration_smoke:
                self.active_report.lift()
            return
        if self.busy or not self.capture:
            return
        try:
            cookie = normalize_cookie(self.cookie.get())
        except ValueError:
            self.integration_hint.set("请先读取本人登录信息，再打开作业窗口。")
            return
        course = self.selected_course()
        self.active_report = ReportWindow(self, self.capture, cookie, self.ip.get(), course.classid if course else "", self.proxy.get())
        if self.integration_smoke:
            self.active_report.withdraw()

    def poll_context(self):
        if self.integration_smoke and (self.data_dir / "smoke-stop.request").exists():
            self.close_window()
            return
        try:
            request = read_request(self.context_file, self.data_dir)
            if self.integration_request is None or request.request_id != self.integration_request.request_id:
                self.integration_request = request
                self.attempted_context = None
                if not self.integration_smoke:
                    self.deiconify()
                    self.lift()
                self.publish_state("pending")
            signature = (request.request_id, id(self.capture), hashlib.sha256(self.cookie.get().encode()).hexdigest())
            report_busy = self.active_report is not None and self.active_report.winfo_exists()
            if not self.busy and not report_busy and self.attempted_context != signature:
                self.attempted_context = signature
                course = matched_course(request, self.capture)
                if course:
                    self.courses.selection_set(course.classid)
                    self.courses.focus(course.classid)
                    self.courses.see(course.classid)
                    self.update_preview()
                    self.integration_hint.set("已核对课表课程：" + course.name + "。请检查账号、IP 和预览后手动操作。")
                elif request.classid:
                    if self.courses.selection():
                        self.courses.selection_remove(*self.courses.selection())
                    self.update_preview()
                    self.integration_hint.set("待核对课表课程：" + request.course_name + "。请读取本人登录；只匹配本次课程列表中的 classid。")
                else:
                    self.integration_hint.set("请读取本人登录并选择课程；打开工具不会发送签到或作业请求。")
                if request.action == "reports" and course and self.cookie.get().strip():
                    self.open_reports()
                self.publish_state("matched" if course else "pending")
        except FileNotFoundError:
            pass
        except (ValueError, OSError, tk.TclError):
            self.integration_hint.set("课程定位信息暂时不可用，请从课表重新打开；已有账号不会被替换。")
        self.after(200, self.poll_context)

    def publish_state(self, selection):
        request = self.integration_request
        write_json(self.data_dir / "ready.json", {"version": 1, "requestId": request.request_id, "status": "ready", "selection": selection,
            "classid": request.classid, "action": request.action, "pid": os.getpid(),
            "reportOpen": bool(self.active_report is not None and self.active_report.winfo_exists())})

    def seed_smoke_capture(self):
        courses = (Course("900000001", "离线模拟课程 A"), Course("900000002", "离线模拟课程 B"))
        capture = Capture("202499999", "离线测试账号", courses, "2026-10-27T00:00:00Z", ORIGIN + "/Students", 1)
        self.events.put(("import_ok", (capture, "开发检查中的模拟 HAR", None, SavedCookie("Session=offline-mock", ""), True)))
        self.ip.set("192.0.2.10")
        write_json(self.data_dir / "smoke-guard.json", {"networkBlocked": True, "browserBlocked": True, "fixtureOnly": True})


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--data-dir", required=True)
    parser.add_argument("--context-file", required=True)
    parser.add_argument("--integration-smoke", action="store_true")
    args = parser.parse_args()
    folder = Path(args.data_dir).resolve()
    folder.mkdir(parents=True, exist_ok=True)
    read_request(args.context_file, folder)
    mutex = None
    if os.name == "nt":
        kernel = ctypes.WinDLL("kernel32", use_last_error=True)
        kernel.CreateMutexW.argtypes = [ctypes.c_void_p, ctypes.c_bool, ctypes.c_wchar_p]
        kernel.CreateMutexW.restype = ctypes.c_void_p
        identity = hashlib.sha256(str(folder).casefold().encode()).hexdigest()
        mutex = kernel.CreateMutexW(None, False, "Local\\KeJianCourseTools-" + identity)
        if not mutex:
            raise OSError("无法启动课程工具")
        if ctypes.get_last_error() == 183:
            kernel.CloseHandle.argtypes = [ctypes.c_void_p]
            kernel.CloseHandle(mutex)
            return
    if args.integration_smoke:
        def blocked(*_args, **_kwargs):
            raise AssertionError("开发检查禁止真实网络与浏览器操作")
        socket.create_connection = blocked
        socket.socket.connect = blocked
        socket.socket.bind = blocked
        webbrowser.open = blocked
    try:
        IntegratedTool(folder, args.context_file, args.integration_smoke).mainloop()
    finally:
        if mutex:
            kernel.CloseHandle.argtypes = [ctypes.c_void_p]
            kernel.CloseHandle(mutex)


if __name__ == "__main__":
    try:
        main()
    except Exception:
        # Startup failures do not contain an account or request payload.
        target = Path(sys.argv[sys.argv.index("--data-dir") + 1]) if "--data-dir" in sys.argv else Path.cwd()
        target.mkdir(parents=True, exist_ok=True)
        (target / "startup-error.txt").write_text(traceback.format_exc(), encoding="utf-8")
        sys.exit(1)
