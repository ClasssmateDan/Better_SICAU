import { test } from 'node:test';
import assert from 'node:assert/strict';
import { holidayForDate, holidayYearNote, createHolidayEvents } from '../dist/holidays.mjs';
import { createReminderPlan, BrowserReminderController } from '../dist/reminders.mjs';
import { createDemo } from '../dist/model.mjs';

test('2026 年官方安排包含 33 个假期日与 6 个调休上班日', () => {
  const counts = { rest: 0, work: 0 };
  for(let day=0;day<365;day++) {
    const date=new Date(Date.UTC(2026,0,1+day)).toISOString().slice(0,10);
    const info=holidayForDate(date);
    if(info && info.kind in counts)counts[info.kind]++;
  }
  assert.deepEqual(counts,{rest:33,work:6});
  assert.equal(holidayForDate('2026-02-15').kind,'rest');
  assert.equal(holidayForDate('2026-02-23').kind,'rest');
  assert.equal(holidayForDate('2026-02-24'),null);
  assert.equal(holidayForDate('2026-09-20').kind,'work');
  assert.equal(holidayForDate('2026-10-10').kind,'work');
});

test('2027 年节日日期与农历对照一致，不推算放假或调休', () => {
  for(const [date,name] of [['2027-02-05','除夕'],['2027-02-06','春节'],['2027-04-05','清明节'],['2027-06-09','端午节'],['2027-09-15','中秋节']]) {
    assert.deepEqual({kind:holidayForDate(date).kind,name:holidayForDate(date).name},{kind:'festival',name});
  }
  assert.equal(holidayForDate('2027-02-07'),null);
  assert.equal(holidayForDate('2027-10-02'),null);
  assert.match(holidayYearNote(2027),/仅显示节日日期/);
  assert.match(holidayYearNote(2028),/未内置/);
});

test('节假日通知按北京时间 09:00，每个假期首日与调休日只生成一次', () => {
  const original=process.env.TZ;
  try {
    process.env.TZ='America/Los_Angeles';
    const events=createHolidayEvents();
    assert.equal(events.length,21);
    assert.equal(new Set(events.map(item=>item.id)).size,21);
    assert.equal(events.find(item=>item.id==='holiday|2027-01-01|festival').at,Date.parse('2027-01-01T01:00:00Z'));
    assert.ok(!events.some(item=>item.id==='holiday|2026-10-02|rest'));
  } finally { if(original===undefined)delete process.env.TZ;else process.env.TZ=original; }
});

test('空课表也保留公共节假日，提醒总开关与去重正常生效', async () => {
  const data={...createDemo(),courses:[]};
  assert.deepEqual(createReminderPlan(data).events,createHolidayEvents());
  const at=Date.parse('2027-01-01T01:00:00Z');let clock=at-1000;
  const memory=new Map(),sent=[];
  class Notification { static permission='granted';constructor(title,detail){sent.push({title,detail});} }
  const controller=new BrowserReminderController({notificationApi:Notification,key:'holiday',now:()=>clock,
    storage:{getItem:key=>memory.get(key),setItem:(key,value)=>memory.set(key,value)}});
  controller.setData(data);clock=at;controller.tick();assert.equal(sent.length,0);
  clock=at-1;await controller.setEnabled(true);clock=at;controller.tick();controller.tick();
  assert.equal(sent.length,1);assert.match(sent[0].title,/节假日/);
});
