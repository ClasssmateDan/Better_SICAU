// Framework-independent domain model. Dates represent local calendar dates.
export const STORAGE_KEY = 'kejian.timetable.public.v1';
export const DAYS = ['星期一', '星期二', '星期三', '星期四', '星期五', '星期六', '星期日'];
export const COLORS = ['coral', 'blue', 'green', 'purple', 'amber', 'pink'];
export const DEFAULT_PERIODS = [
  ['08:00', '08:45'], ['08:55', '09:40'], ['10:00', '10:45'], ['10:55', '11:40'],
  ['14:00', '14:45'], ['14:55', '15:40'], ['16:00', '16:45'], ['16:55', '17:40'],
  ['19:00', '19:45'], ['19:55', '20:40'], ['20:50', '21:35'], ['21:45', '22:30'],
];

export function parseDate(value) {
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(value)) throw new Error('日期格式应为 YYYY-MM-DD');
  const [year, month, day] = value.split('-').map(Number);
  const date = new Date(year, month - 1, day, 12);
  if (date.getFullYear() !== year || date.getMonth() !== month - 1 || date.getDate() !== day) throw new Error('日期无效');
  return date;
}

export function dateKey(date) {
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`;
}

export function addDays(date, count) {
  const result = new Date(date);
  result.setDate(result.getDate() + count);
  return result;
}

export function mondayOf(date) {
  return addDays(date, -((date.getDay() + 6) % 7));
}

export function weekForDate(date, semester) {
  const start = parseDate(semester.startDate);
  // UTC calendar arithmetic avoids daylight-saving shifts between local dates.
  const day = Date.UTC(date.getFullYear(), date.getMonth(), date.getDate());
  const first = Date.UTC(start.getFullYear(), start.getMonth(), start.getDate());
  return Math.floor((day - first) / 604800000) + 1;
}

export function weekDates(semester, week) {
  const start = addDays(parseDate(semester.startDate), (week - 1) * 7);
  return Array.from({ length: 7 }, (_, index) => addDays(start, index));
}

export function meetingActive(meeting, week) {
  return week >= meeting.startWeek && week <= meeting.endWeek &&
    (meeting.parity === 'all' || (meeting.parity === 'odd' ? week % 2 === 1 : week % 2 === 0));
}

export function sessionsForWeek(courses, week) {
  return courses.flatMap(course => course.meetings.filter(meeting => meetingActive(meeting, week)).map(meeting => {
    const online = (meeting.onlineWeeks ?? []).includes(week);
    return { course, meeting, online, room: online ? '线上授课' : meeting.room || course.room };
  }))
    .sort((a, b) => a.meeting.day - b.meeting.day || a.meeting.start - b.meeting.start || a.course.name.localeCompare(b.course.name, 'zh-CN'));
}

export function minutes(value) {
  const [hours, mins] = value.split(':').map(Number);
  return hours * 60 + mins;
}

function boundedInteger(value, min, max, label) {
  if (!Number.isInteger(value) || value < min || value > max) throw new Error(`${label}应在 ${min}–${max} 之间`);
  return value;
}

function safeText(value, label, max, optional = false) {
  if (typeof value !== 'string' || value.length > max || (!optional && !value.trim())) throw new Error(`${label}不能为空且最多 ${max} 个字符`);
  return value.trim();
}

export function validateClassid(value) {
  if (typeof value !== 'string') throw new Error('签到 classid 应为数字文本');
  const classid = value.trim();
  if (classid && !/^[0-9]{1,32}$/.test(classid)) throw new Error('签到 classid 应为 1–32 位数字，也可留空');
  return classid;
}

export function validateSemester(input) {
  if (!input || typeof input !== 'object') throw new Error('学期设置格式不正确');
  const name = safeText(input.name, '学期名称', 60);
  const date = parseDate(input.startDate);
  if (date.getDay() !== 1) throw new Error('第一周开始日期必须为星期一');
  boundedInteger(input.weeks, 1, 30, '学期周数');
  if (!Array.isArray(input.periods) || input.periods.length < 1 || input.periods.length > 16) throw new Error('每天需要设置 1–16 节课');
  let previousEnd = -1;
  const periods = input.periods.map((period, index) => {
    if (!Array.isArray(period) || period.length !== 2 || !period.every(time => typeof time === 'string' && /^([01]\d|2[0-3]):[0-5]\d$/.test(time))) throw new Error(`第 ${index + 1} 节的时间格式不正确`);
    const [start, end] = period.map(minutes);
    if (start >= end || start < previousEnd) throw new Error(`第 ${index + 1} 节应晚于上一节，且下课时间须晚于上课时间`);
    previousEnd = end;
    return [...period];
  });
  return { name, startDate: input.startDate, weeks: input.weeks, periods };
}

export function validateCourse(input, semester) {
  if (!input || typeof input !== 'object') throw new Error('课程格式不正确');
  const id = safeText(input.id, '课程编号', 100);
  const name = safeText(input.name, '课程名称', 60);
  const teacher = safeText(input.teacher ?? '', '教师姓名', 40, true);
  const room = safeText(input.room ?? '', '上课地点', 80, true);
  const notes = safeText(input.notes ?? '', '备注', 1000, true);
  const classid = input.classid === undefined ? undefined : validateClassid(input.classid);
  if (!COLORS.includes(input.color)) throw new Error('请选择有效的课程颜色');
  if (typeof input.credits !== 'number' || !Number.isFinite(input.credits) || input.credits < 0 || input.credits > 30) throw new Error('学分应在 0–30 之间');
  if (input.unscheduled !== undefined && typeof input.unscheduled !== 'boolean') throw new Error('暂不排课标记格式不正确');
  if (!Array.isArray(input.meetings) || input.meetings.length > 20 || (!input.unscheduled && input.meetings.length < 1)) throw new Error('请设置 1–20 个上课安排，或勾选暂不排课');
  if (input.unscheduled && input.meetings.length) throw new Error('暂不排课的课程不能同时设置固定安排');
  const meetings = input.meetings.map(meeting => {
    if (!meeting || typeof meeting !== 'object') throw new Error('上课安排格式不正确');
    boundedInteger(meeting.day, 1, 7, '星期');
    boundedInteger(meeting.start, 1, semester.periods.length, '开始节次');
    boundedInteger(meeting.end, meeting.start, semester.periods.length, '结束节次');
    boundedInteger(meeting.startWeek, 1, semester.weeks, '开始周');
    boundedInteger(meeting.endWeek, meeting.startWeek, semester.weeks, '结束周');
    if (!['all', 'odd', 'even'].includes(meeting.parity)) throw new Error('请选择每周、单周或双周');
    if (!Array.from({ length: meeting.endWeek - meeting.startWeek + 1 }, (_, i) => meeting.startWeek + i).some(week => meetingActive(meeting, week))) throw new Error('此周次范围中没有符合单双周条件的上课日期');
    const clean = { day: meeting.day, start: meeting.start, end: meeting.end, startWeek: meeting.startWeek, endWeek: meeting.endWeek, parity: meeting.parity };
    if (meeting.room !== undefined) {
      const room = safeText(meeting.room, '本次上课地点', 80, true);
      if (room) clean.room = room;
    }
    if (meeting.onlineWeeks !== undefined) {
      if (!Array.isArray(meeting.onlineWeeks) || meeting.onlineWeeks.length > 30) throw new Error('线上周次应为周数列表');
      for (const week of meeting.onlineWeeks) {
        boundedInteger(week, meeting.startWeek, meeting.endWeek, '线上周次');
        if (!meetingActive(meeting, week)) throw new Error('线上周次必须是此安排实际上课的周次');
      }
      if (meeting.onlineWeeks.length) clean.onlineWeeks = [...new Set(meeting.onlineWeeks)].sort((a, b) => a - b);
    }
    return clean;
  });
  return { id, name, teacher, room, notes, credits: input.credits, color: input.color, meetings, ...(classid !== undefined ? { classid } : {}), ...(input.unscheduled ? { unscheduled: true } : {}) };
}

export function findConflicts(candidate, courses, semester) {
  const others = courses.filter(course => course.id !== candidate.id);
  const collisions = [];
  const checked = [];
  candidate.meetings.forEach(meeting => {
    const targets = [...others.flatMap(course => course.meetings.map(other => ({ course, other }))), ...checked.map(other => ({ course: candidate, other }))];
    targets.forEach(({ course, other }) => {
      if (meeting.day !== other.day || meeting.start > other.end || meeting.end < other.start) return;
      for (let week = 1; week <= semester.weeks; week++) {
        if (meetingActive(meeting, week) && meetingActive(other, week)) {
          collisions.push({ name: course.name, day: meeting.day, week, start: Math.max(meeting.start, other.start), end: Math.min(meeting.end, other.end) });
          break;
        }
      }
    });
    checked.push(meeting);
  });
  return collisions;
}

export function validateData(input) {
  if (!input || input.version !== 1 || !Array.isArray(input.courses) || input.courses.length > 500) throw new Error('请选择符合课间格式的 JSON 课表（version 为 1，最多 500 门课程）');
  const semester = validateSemester(input.semester);
  const courses = input.courses.map(course => validateCourse(course, semester));
  if (new Set(courses.map(course => course.id)).size !== courses.length) throw new Error('文件中存在重复的课程编号');
  for (const course of courses) {
    if (findConflicts(course, courses, semester).length) throw new Error(`「${course.name}」存在时间冲突，请修正后再导入`);
  }
  return { version: 1, semester, courses };
}

export function createDemo(now = new Date()) {
  const year = now.getFullYear();
  const autumn = now.getMonth() >= 7;
  const base = new Date(year, autumn ? 8 : 1, autumn ? 7 : 23, 12);
  const startDate = dateKey(mondayOf(base));
  const semester = { name: `${year} 年${autumn ? '秋' : '春'}季学期`, startDate, weeks: 20, periods: DEFAULT_PERIODS.map(period => [...period]) };
  const meeting = (day, start, end, parity = 'all', endWeek = 18) => ({ day, start, end, startWeek: 1, endWeek, parity });
  const courses = [
    { id: 'demo-math', name: '高等数学', teacher: '陈教授', room: '教学楼 A · 302', credits: 5, color: 'blue', notes: '带上课本和习题册。', meetings: [meeting(1, 1, 2), meeting(3, 1, 2), meeting(5, 3, 4)] },
    { id: 'demo-code', name: '程序设计基础', teacher: '李老师', room: '信息楼 · 501', credits: 4, color: 'coral', notes: '每周四的课程为上机实践。', meetings: [meeting(2, 3, 4), meeting(4, 5, 6)] },
    { id: 'demo-english', name: '大学英语', teacher: '王老师', room: '人文楼 · 206', credits: 3, color: 'purple', notes: '', meetings: [meeting(1, 3, 4), meeting(3, 5, 6)] },
    { id: 'demo-physics', name: '大学物理', teacher: '张教授', room: '理科楼 · 104', credits: 3, color: 'green', notes: '', meetings: [meeting(2, 1, 2), meeting(4, 1, 2)] },
    { id: 'demo-sports', name: '体育 · 羽毛球', teacher: '刘老师', room: '体育馆 · 2 号场', credits: 1, color: 'amber', notes: '穿运动鞋，带球拍。', meetings: [meeting(2, 7, 8)] },
    { id: 'demo-history', name: '中国近现代史纲要', teacher: '赵老师', room: '教学楼 B · 201', credits: 2, color: 'pink', notes: '', meetings: [meeting(5, 5, 6)] },
    { id: 'demo-lab', name: '物理实验', teacher: '周老师', room: '实验楼 · 308', credits: 1.5, color: 'green', notes: '实验前完成预习报告。', meetings: [meeting(3, 7, 8, 'odd', 16)] },
    { id: 'demo-art', name: '艺术与审美', teacher: '林老师', room: '艺术楼 · 101', credits: 2, color: 'purple', notes: '双周的通识选修课。', meetings: [meeting(4, 9, 10, 'even', 16)] },
  ];
  return { version: 1, semester, courses };
}
