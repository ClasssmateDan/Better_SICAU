import { test } from 'node:test';
import assert from 'node:assert/strict';
import { addDays, createDemo, dateKey, findConflicts, meetingActive, mondayOf, parseDate, sessionsForWeek, validateCourse, validateData, validateSemester, weekDates, weekForDate } from '../dist/model.mjs';

const demo = () => createDemo(new Date(2026, 9, 25, 12));
const semester = demo().semester;
const meeting = (patch = {}) => ({ day: 1, start: 1, end: 2, startWeek: 1, endWeek: 18, parity: 'all', ...patch });
const course = (patch = {}) => ({ id: 'test', name: '测试课程', teacher: '', room: '', notes: '', credits: 2, color: 'blue', meetings: [meeting()], ...patch });

test('演示课表通过完整校验，秋季学期从周一开始', () => {
  const data = validateData(demo());
  assert.equal(data.courses.length, 8);
  assert.equal(data.semester.startDate, '2026-09-07');
  assert.equal(parseDate(data.semester.startDate).getDay(), 1);
});

test('周数计算覆盖周日前后、开学前和跨年', () => {
  assert.equal(weekForDate(parseDate('2026-09-06'), semester), 0);
  assert.equal(weekForDate(parseDate('2026-09-07'), semester), 1);
  assert.equal(weekForDate(parseDate('2026-09-13'), semester), 1);
  assert.equal(weekForDate(parseDate('2026-09-14'), semester), 2);
  assert.equal(weekForDate(parseDate('2026-10-25'), semester), 7);
  assert.equal(weekForDate(parseDate('2027-01-04'), semester), 18);
});

test('日期工具处理闰日、月末和周日对应周一', () => {
  assert.equal(dateKey(addDays(parseDate('2024-02-28'), 1)), '2024-02-29');
  assert.equal(dateKey(addDays(parseDate('2026-12-31'), 1)), '2027-01-01');
  assert.equal(dateKey(mondayOf(parseDate('2026-10-25'))), '2026-10-19');
  assert.deepEqual(weekDates(semester, 7).map(dateKey), ['2026-10-19', '2026-10-20', '2026-10-21', '2026-10-22', '2026-10-23', '2026-10-24', '2026-10-25']);
});

test('无效日期不会自动滚动到下个月', () => {
  for (const value of ['2026-02-29', '2026-13-01', '2026-04-31', '2026-1-1', '', null]) assert.throws(() => parseDate(value));
});

test('单双周和起止周同时生效', () => {
  const odd = meeting({ startWeek: 3, endWeek: 7, parity: 'odd' });
  assert.equal(meetingActive(odd, 1), false);
  assert.equal(meetingActive(odd, 3), true);
  assert.equal(meetingActive(odd, 4), false);
  assert.equal(meetingActive(odd, 7), true);
  assert.equal(meetingActive(odd, 9), false);
  assert.equal(meetingActive(meeting({ parity: 'even' }), 2), true);
});

test('一周中的所有课程按星期、节次排序，单双周有区别', () => {
  const odd = sessionsForWeek(demo().courses, 7);
  const even = sessionsForWeek(demo().courses, 8);
  assert.equal(odd.length, 12);
  assert.equal(even.length, 12);
  assert.ok(odd.some(item => item.course.id === 'demo-lab'));
  assert.ok(!odd.some(item => item.course.id === 'demo-art'));
  assert.ok(even.some(item => item.course.id === 'demo-art'));
  assert.ok(!even.some(item => item.course.id === 'demo-lab'));
  assert.equal(odd.reduce((sum, { meeting }) => sum + meeting.end - meeting.start + 1, 0), 24);
  assert.equal(sessionsForWeek(demo().courses, 20).length, 0);
});

test('同一天重叠节次会返回首次冲突的具体周次', () => {
  const candidate = course({ id: 'new', meetings: [meeting({ start: 2, end: 3, startWeek: 5 })] });
  const conflicts = findConflicts(candidate, [course()], semester);
  assert.deepEqual(conflicts[0], { name: '测试课程', day: 1, week: 5, start: 2, end: 2 });
});

test('相邻节次、不同星期和错开的周次不会误报冲突', () => {
  for (const changed of [{ start: 3, end: 4 }, { day: 2 }, { startWeek: 19, endWeek: 20 }]) {
    assert.deepEqual(findConflicts(course({ id: 'new', meetings: [meeting(changed)] }), [course()], semester), []);
  }
});

test('单双周课程可复用同一时段，交叉奇偶范围按实际发生周次判断', () => {
  const old = course({ meetings: [meeting({ parity: 'odd' })] });
  assert.deepEqual(findConflicts(course({ id: 'new', meetings: [meeting({ parity: 'even' })] }), [old], semester), []);
  const conflicts = findConflicts(course({ id: 'new', meetings: [meeting({ startWeek: 2, endWeek: 4 })] }), [old], semester);
  assert.equal(conflicts[0].week, 3);
});

test('编辑已有课程不与自身旧数据冲突，但新安排之间仍检查冲突', () => {
  const original = course();
  assert.deepEqual(findConflicts(course(), [original], semester), []);
  const candidate = course({ meetings: [meeting(), meeting({ start: 2, end: 4 })] });
  assert.equal(findConflicts(candidate, [original], semester).length, 1);
});

test('校验上课安排拒绝倒序、超界、空单双周和无效颜色', () => {
  for (const changed of [{ day: 0 }, { day: 8 }, { start: 0 }, { start: 3, end: 2 }, { end: 13 }, { startWeek: 0 }, { endWeek: 21 }, { startWeek: 8, endWeek: 7 }, { startWeek: 2, endWeek: 2, parity: 'odd' }, { parity: 'invalid' }]) {
    assert.throws(() => validateCourse(course({ meetings: [meeting(changed)] }), semester));
  }
  for (const changed of [{ name: '' }, { credits: NaN }, { credits: -1 }, { color: 'unknown' }, { meetings: [] }]) assert.throws(() => validateCourse(course(changed), semester));
});

test('学期校验支持自定义节次，拒绝非周一和交叉的作息时间', () => {
  assert.equal(validateSemester({ ...semester, periods: [['09:00', '10:00']] }).periods.length, 1);
  assert.throws(() => validateSemester({ ...semester, startDate: '2026-09-08' }), /星期一/);
  assert.throws(() => validateSemester({ ...semester, periods: [['09:00', '10:00'], ['09:45', '10:30']] }), /晚于上一节/);
  assert.throws(() => validateSemester({ ...semester, periods: [['25:00', '26:00']] }));
  assert.throws(() => validateSemester({ ...semester, periods: [] }));
  assert.throws(() => validateSemester({ ...semester, weeks: 31 }));
});

test('备份可以完整往返，也允许空课表', () => {
  const data = demo();
  assert.deepEqual(validateData(JSON.parse(JSON.stringify(data))), data);
  assert.deepEqual(validateData({ ...data, courses: [] }).courses, []);
});

test('导入拒绝重复编号、课程冲突、错误版本和缺失字段', () => {
  assert.throws(() => validateData({ version: 1, semester, courses: [course(), course()] }), /重复/);
  assert.throws(() => validateData({ version: 1, semester, courses: [course(), course({ id: 'another' })] }), /冲突/);
  for (const value of [null, {}, { ...demo(), version: 2 }, { ...demo(), courses: 'wrong' }]) assert.throws(() => validateData(value));
});

test('导入数据复制允许字段，不继承无关状态或可变原始数组', () => {
  const raw = demo();
  raw.extra = 'ignored';
  raw.courses[0].extra = 'ignored';
  const clean = validateData(raw);
  assert.equal(clean.extra, undefined);
  assert.equal(clean.courses[0].extra, undefined);
  raw.courses[0].meetings[0].day = 7;
  raw.semester.periods[0][0] = '00:00';
  assert.equal(clean.courses[0].meetings[0].day, 1);
  assert.equal(clean.semester.periods[0][0], '08:00');
});


test('classid 独立于课程编号保存，支持留空、长编号与备份往返并拒绝非法值', () => {
  const raw = demo();
  raw.courses[0].classid = '900000002';
  assert.equal(validateData(JSON.parse(JSON.stringify(raw))).courses[0].classid, '900000002');
  assert.equal(validateCourse(course({ classid: ' 00123456789012345678901234567890 ' }), semester).classid, '00123456789012345678901234567890');
  assert.equal(validateCourse(course({ classid: '' }), semester).classid, '');
  assert.equal(validateCourse(course({ classid: '  ' }), semester).classid, '');
  for (const classid of [123, null, 'abc', '1,2', '-1', '1'.repeat(33), '<script>']) assert.throws(() => validateCourse(course({ classid }), semester), /classid/);
});
