"""Local timetable context; deliberately contains no login or request execution."""
import json
import re
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class CourseRequest:
    request_id: str
    action: str
    classid: str
    course_name: str


def read_request(filename, data_dir):
    filename, folder = Path(filename).resolve(), Path(data_dir).resolve()
    if filename.parent != folder or filename.name != "request.json":
        raise ValueError("课程定位文件必须位于工具数据目录内")
    if filename.stat().st_size > 20000:
        raise ValueError("课程定位文件过大")
    value = json.loads(filename.read_text(encoding="utf-8-sig"))
    if not isinstance(value, dict) or set(value) != {"version", "requestId", "action", "classid", "courseName"} or value["version"] != 1:
        raise ValueError("课程定位文件格式不正确")
    request_id, action, classid, name = (value[key] for key in ("requestId", "action", "classid", "courseName"))
    if not isinstance(request_id, str) or not re.fullmatch(r"[0-9a-f]{32}", request_id):
        raise ValueError("课程定位编号不正确")
    if action not in ("signin", "reports") or not isinstance(classid, str) or classid and not re.fullmatch(r"[0-9]{1,32}", classid):
        raise ValueError("课程定位操作或 classid 不正确")
    if not isinstance(name, str) or len(name) > 120 or any(ord(char) < 32 for char in name):
        raise ValueError("课程名称不正确")
    return CourseRequest(request_id, action, classid, name)


def matched_course(request, capture):
    if not capture or not request.classid:
        return None
    return next((course for course in capture.courses if course.classid == request.classid), None)


def write_json(filename, value):
    filename = Path(filename)
    temporary = filename.with_suffix(".tmp")
    temporary.write_text(json.dumps(value, ensure_ascii=False), encoding="utf-8")
    temporary.replace(filename)
