"""Visible build identity; no network or browser operations."""
import os
import sys
from pathlib import Path

EDITION = "本机登录 + 实验报告 · 20261026.3"
IP_USAGE_NOTE = "用于尝试修改签到和作业提交记录 IP，效果不稳定。推荐机房 IP 段：10.4.211.xxx、10.4.217.xxx、10.4.209.xxx（xxx 请替换为实际末段数字）。"


def window_title(name, script):
    if getattr(sys, "frozen", False):
        return "课间 · " + name
    return f"{name} · {Path(script).resolve().parent.name} · PID {os.getpid()}"


def source_label(script):
    if getattr(sys, "frozen", False):
        return IP_USAGE_NOTE
    return f"版本：{EDITION}\n程序：{Path(script).resolve()} · PID {os.getpid()}"
