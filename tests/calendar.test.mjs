import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createDemo, validateData, validateCalendar, sessionsForDate, attendanceKey } from '../dist/model.mjs';
import { createReminderPlan, dueReminderEvents, BrowserReminderController } from '../dist/reminders.mjs';
import { exportTimetableFile, parseTimetableFile } from '../dist/transfer.mjs';

const memo = (extra = {}) => ({ id: 'memo-1', title: '提交报告', date: '2026-09-07', time: '08:00', remind: true, notes: '检查附件', ...extra });
const mark = (extra = {}) => ({ date: '2026-09-07', courseId: 'demo-math', courseName: '高等数学', start: 1, end: 2, ...extra });
const table = () => ({ ...createDemo(new Date(2026, 8, 7)), calendar: { memos: [memo()], attendance: [mark()] } });

test('旧课表无需日历字段；备忘录与历史签到可完整往返、与输入隔离', () => {
  assert.equal(validateData(createDemo()).calendar, undefined);
  const source = table(), clean = validateData(source);
  source.calendar.memos[0].title = 'changed';
  source.calendar.attendance[0].courseName = 'changed';
  assert.equal(clean.calendar.memos[0].title, '提交报告');
  assert.equal(clean.calendar.attendance[0].courseName, '高等数学');
  const imported = parseTimetableFile(exportTimetableFile(clean));
  assert.deepEqual(imported.calendar, clean.calendar);
  assert.deepEqual(validateData({ ...clean, courses: [] }).calendar.attendance, [mark()]);
});

test('日历拒绝无效日期、时间、开关、空标题、重复及过多记录', () => {
  for (const change of [{ date: '2026-02-30' }, { date: '1999-12-31' }, { date: '2101-01-01' }, { time: '24:00' },
    { time: 123 }, { time: '', remind: true }, { remind: 'true' }, { title: ' ' }, { notes: 'a'.repeat(1501) }]) {
    assert.throws(() => validateCalendar({ memos: [memo(change)], attendance: [] }));
  }
  for (const calendar of [null, {}, { memos: [], attendance: 'bad' }, { memos: [memo(), memo()], attendance: [] },
    { memos: [], attendance: [mark(), mark()] }, { memos: [], attendance: [mark({ start: 3, end: 2 })] },
    { memos: [], attendance: [mark({ courseId: '' })] }, { memos: Array(2001).fill(memo()), attendance: [] }]) assert.throws(() => validateCalendar(calendar));
  assert.equal(validateCalendar({ memos: [memo({ date: '2028-02-29', time: '', remind: false })], attendance: [] }).memos[0].date, '2028-02-29');
});

test('按日期匹配实际周次、星期、单双周与线上地点，学期外为空', () => {
  const data = table();
  const firstMonday = sessionsForDate(data, '2026-09-07');
  assert.deepEqual(firstMonday.map(item => item.course.id), ['demo-math', 'demo-english']);
  assert.ok(sessionsForDate(data, '2026-09-09').some(item => item.course.id === 'demo-lab'));
  assert.ok(!sessionsForDate(data, '2026-09-16').some(item => item.course.id === 'demo-lab'));
  assert.deepEqual(sessionsForDate(data, '2026-09-06'), []);
  assert.deepEqual(sessionsForDate(data, '2028-01-03'), []);
});

test('签到按日期及课程节次区分，历史记录不因课程调整失效', () => {
  assert.notEqual(attendanceKey(mark()), attendanceKey(mark({ date: '2026-09-14' })));
  assert.notEqual(attendanceKey(mark()), attendanceKey(mark({ start: 3, end: 4 })));
  const data = table(); data.courses[0].name = '新课程名'; data.courses[0].meetings = [];
  data.courses[0].unscheduled = true;
  assert.equal(validateData(data).calendar.attendance[0].courseName, '高等数学');
});

test('备忘录提醒与上课提醒同时发生时均保留，修改及删除自动更新计划', () => {
  const data = table(), events = createReminderPlan(data).events;
  const reminder = events.find(item => item.id.startsWith('memo|'));
  assert.equal(reminder.at, Date.parse('2026-09-07T08:00:00+08:00'));
  assert.equal(reminder.body, '2026-09-07 08:00 · 提交报告\n检查附件');
  assert.equal(dueReminderEvents(events, reminder.at - 1, reminder.at).length, 2);
  data.calendar.memos[0].time = '09:00';
  assert.equal(createReminderPlan(data).events.find(item => item.id.startsWith('memo|')).at, reminder.at + 3600000);
  data.calendar.memos[0].remind = false;
  assert.ok(!createReminderPlan(data).events.some(item => item.id.startsWith('memo|')));
  data.calendar.memos = [];
  assert.ok(!createReminderPlan(data).events.some(item => item.id.startsWith('memo|')));
});

test('备忘录在海外时区仍按北京时间，关闭的、全天备忘录不生成提醒', () => {
  const zone = process.env.TZ;
  try {
    process.env.TZ = 'America/Los_Angeles';
    const data = table();
    data.calendar.memos.push(memo({ id: 'all-day', time: '', remind: false }), memo({ id: 'off', remind: false }));
    const reminders = createReminderPlan(data).events.filter(item => item.id.startsWith('memo|'));
    assert.equal(reminders.length, 1);
    assert.equal(reminders[0].at, Date.parse('2026-09-07T00:00:00Z'));
  } finally { if (zone === undefined) delete process.env.TZ; else process.env.TZ = zone; }
});

test('浏览器备忘录到时仅通知一次，同一日期不会重复弹出', async () => {
  const data = { ...table(), courses: [] };
  const at = createReminderPlan(data).events.find(event=>event.id.startsWith('memo|')).at;
  let now = at - 1000; const sent = [], saved = new Map();
  class Notification { static permission = 'granted'; constructor(title, detail) { sent.push({ title, detail }); } }
  const controller = new BrowserReminderController({ notificationApi: Notification, now: () => now, key: 'calendar-test',
    storage: { getItem: key => saved.get(key), setItem: (key, value) => saved.set(key, value) } });
  controller.setData(data); await controller.setEnabled(true);
  now = at; controller.tick(); controller.tick();
  assert.equal(sent.length, 1); assert.match(sent[0].title, /备忘录/);
});
