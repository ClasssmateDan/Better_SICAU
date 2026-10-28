import { addDays, dateKey, parseDate, sessionsForWeek } from './model.mjs';

export function createReminderPlan(timetable) {
  const events = [];
  const first = parseDate(timetable.semester.startDate);
  for (let week = 1; week <= timetable.semester.weeks; week++) {
    for (const { course, meeting, room } of sessionsForWeek(timetable.courses, week)) {
      const day = addDays(first, (week - 1) * 7 + meeting.day - 1);
      for (let period = meeting.start; period <= meeting.end; period++) {
        const times = timetable.semester.periods[period - 1];
        for (const [kind, time] of [['start', times[0]], ['end', times[1]]]) {
          const [hours, minutes] = time.split(':').map(Number);
          const at = new Date(day.getFullYear(), day.getMonth(), day.getDate(), hours, minutes).getTime();
          events.push({
            id: `${dateKey(day)}|${course.id}|${period}|${kind}|${time}`,
            at, title: `课间 · ${kind === 'start' ? '上课' : '下课'}提醒`,
            body: `${time} · ${course.name}\n第 ${week} 周 · 第 ${period} 节 · ${room || '地点待定'}${course.teacher ? '\n' + course.teacher : ''}`,
          });
        }
      }
    }
  }
  events.sort((a, b) => a.at - b.at || a.id.localeCompare(b.id));
  return { kind: 'kejian-reminder-plan', version: 1, semesterName: timetable.semester.name, events };
}

export function dueReminderEvents(events, after, now, grace = 60000) {
  if (now < after) return [];
  const lower = Math.max(after, now - grace);
  return events.filter(event => event.at > lower && event.at <= now);
}

export class BrowserReminderController {
  constructor({ notificationApi, storage, key, now = () => Date.now(), onChange = () => {} }) {
    this.api = notificationApi;
    this.storage = storage;
    this.key = key;
    this.now = now;
    this.onChange = onChange;
    this.events = [];
    this.error = '';
    this.lastCheck = now() - 1;
    this.enabled = false;
    try { this.enabled = storage.getItem(key) === 'true' && this.api?.permission === 'granted'; }
    catch { this.error = '无法保存提醒开关，请检查浏览器存储权限。'; }
  }

  setData(timetable) { this.events = createReminderPlan(timetable).events; }

  async setEnabled(enabled) {
    this.error = '';
    if (enabled) {
      if (!this.api) { this.error = '此浏览器不支持系统通知，请使用 Windows 桌面提醒。'; this.onChange(); return false; }
      let permission = this.api.permission;
      if (permission === 'default') {
        try { permission = await this.api.requestPermission(); }
        catch (error) { this.error = '通知权限请求失败：' + error.message; this.onChange(); return false; }
      }
      if (permission !== 'granted') { this.error = '通知权限未获允许，请使用 Windows 桌面提醒，或在支持的网页中允许通知。'; this.onChange(); return false; }
    }
    try { this.storage.setItem(this.key, String(enabled)); }
    catch { this.error = '开关未保存，请检查浏览器存储权限。'; this.onChange(); return false; }
    this.enabled = enabled;
    this.lastCheck = this.now() - 1;
    this.onChange();
    return true;
  }

  sync() {
    try { this.enabled = this.storage.getItem(this.key) === 'true' && this.api?.permission === 'granted'; }
    catch { this.enabled = false; }
    this.onChange();
  }

  show(event) {
    const notification = new this.api(event.title, { body: event.body, tag: event.id });
    notification.onclick = () => { globalThis.focus?.(); notification.close(); };
    notification.onerror = () => { this.error = '浏览器未能显示系统通知，可使用 Windows 桌面提醒。'; this.onChange(); };
    return notification;
  }

  test() {
    if (!this.enabled || this.api?.permission !== 'granted') throw new Error('请先开启并允许浏览器提醒。');
    this.show({ id: 'kejian-test', title: '课间 · 测试通知', body: '上课、下课时将显示桌面通知。请确认桌面右下角能够正常显示通知。' });
  }

  tick() {
    const now = this.now();
    const after = this.lastCheck;
    this.lastCheck = now;
    this.sync();
    if (!this.enabled) return;
    for (const event of dueReminderEvents(this.events, after, now)) {
      try {
        const sentKey = this.key + '.sent.' + dateKey(new Date(now));
        const sent = new Set(JSON.parse(this.storage.getItem(sentKey) || '[]'));
        if (sent.has(event.id)) continue;
        this.show(event);
        sent.add(event.id);
        this.storage.setItem(sentKey, JSON.stringify([...sent]));
      } catch (error) {
        this.enabled = false;
        try { this.storage.setItem(this.key, 'false'); } catch { /* already reporting failure */ }
        this.error = '提醒发送失败：' + error.message;
        this.onChange();
        break;
      }
    }
  }
}
