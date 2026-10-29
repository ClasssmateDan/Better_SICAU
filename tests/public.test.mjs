import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { createPreset, fillKnownClassids, CLASSID_REFERENCE } from '../dist/preset.mjs';
import { validateData } from '../dist/model.mjs';
import { createReminderPlan } from '../dist/reminders.mjs';
import { parseTimetableFile, exportTimetableFile } from '../dist/transfer.mjs';
import { buildStatic } from '../scripts/build.mjs';
import { createRequire } from 'node:module';

test('公开版默认和重置模板均为零课程、零学分及零提醒', () => {
  const data = createPreset();
  assert.deepEqual(validateData(data), data);
  assert.deepEqual(data.courses, []);
  assert.equal(data.semester.name, '新学期');
  assert.deepEqual(CLASSID_REFERENCE, []);
  assert.deepEqual(createReminderPlan(data).events, []);
  assert.deepEqual(parseTimetableFile(exportTimetableFile(data)), data);
});

test('classid 只保留明确填写的值，不推断教学班编号', async () => {
  const sample = JSON.parse(await readFile(new URL('../课表导入示例.json', import.meta.url), 'utf8'));
  delete sample.courses[0].classid;
  sample.courses[1].classid = '900000002';
  const result = fillKnownClassids(sample);
  assert.equal(result.data.courses[0].classid, '');
  assert.equal(result.data.courses[1].classid, '900000002');
  assert.equal(result.changed, true);
});

test('静态网页包含空白模板，示例文件不会自动成为初始课程', async () => {
  const built = await buildStatic();
  assert.ok(built.standalone.includes('重置为空白课表'));
  assert.ok(!built.standalone.includes('恢复截图课表'));
  assert.ok(!built.standalone.includes('screenshotTimetable'));
  assert.doesNotMatch(built.standalone, /<(?:script|link)\b[^>]*(?:src|href)=["'](?!data:)/i);
  assert.deepEqual(JSON.parse(await readFile(new URL('../空白课表.json', import.meta.url), 'utf8')).courses, []);
});

test('二维码核心仍支持显式 classid 与当前时间', () => {
  const require = createRequire(import.meta.url);
  const qr = require('../qr-tool/dist/qr-core.js');
  const result=qr.generateQr({classId:'900000001'},1793102400000);
  assert.equal(qr.parseQrContent(result.url).classId,'900000001');
});

test('签到方式随导入导出保留，旧文件默认教务系统', async () => {
  const raw=JSON.parse(await readFile(new URL('../课表导入示例.json',import.meta.url),'utf8'));
  raw.courses[0].signinMethod='学习通'; delete raw.courses[1].signinMethod;
  const imported=parseTimetableFile(JSON.stringify(raw));
  assert.equal(imported.courses[0].signinMethod,'学习通');
  assert.equal(imported.courses[1].signinMethod,'教务系统');
  assert.deepEqual(parseTimetableFile(exportTimetableFile(imported)),imported);
});
