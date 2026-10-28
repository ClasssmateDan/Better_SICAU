import {
  STORAGE_KEY, DAYS, COLORS, DEFAULT_PERIODS, dateKey, weekForDate,
  weekDates, sessionsForWeek, validateCourse, validateSemester, validateData, findConflicts, minutes,
} from './model.mjs';
import { createPreset, fillKnownClassids } from './preset.mjs';
import { BrowserReminderController, createReminderPlan } from './reminders.mjs';
import { parseTimetableFile, exportTimetableFile, summarizeTimetable } from './transfer.mjs';

const $ = selector => document.querySelector(selector);
const $$ = selector => [...document.querySelectorAll(selector)];
const desktopBootstrap = window.__KEJIAN_DESKTOP__;
const androidPlatform = !!window.__KEJIAN_ANDROID__;
const pcPlatform = !androidPlatform;
document.body.classList.toggle('pc-platform', pcPlatform);
const nativeBridge = androidPlatform ? window.__KEJIAN_NATIVE_BRIDGE__ : desktopBootstrap?.desktop ? window.chrome?.webview : null;
const courseToolsSupported = !androidPlatform && !!nativeBridge && desktopBootstrap?.courseTools === true;
// Replaced with the complete offline document by scripts/build.mjs.
const qrDocument = /* qr:document */ '';
const importGuide = /* transfer:guide */ '';
const importExample = /* transfer:example */ '';
const esc = value => String(value ?? '').replace(/[&<>"']/g, character => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[character]));
const paths = {
  calendar: '<rect x="4" y="5" width="16" height="16" rx="3"/><path d="M8 3v4m8-4v4M4 10h16m-12 4h2m4 0h2m-8 3h2"/>',
  grid: '<rect x="3" y="3" width="7" height="7" rx="1.7"/><rect x="14" y="3" width="7" height="7" rx="1.7"/><rect x="3" y="14" width="7" height="7" rx="1.7"/><rect x="14" y="14" width="7" height="7" rx="1.7"/>',
  qr: '<rect x="3" y="3" width="6" height="6" rx="1"/><rect x="15" y="3" width="6" height="6" rx="1"/><rect x="3" y="15" width="6" height="6" rx="1"/><path d="M15 15h3v3h3m-6 3h3M21 15v1M6 5v2m12-2v2M6 17v2"/>',
  tools: '<path d="M14 4a5 5 0 0 0-6 6L3 15a3 3 0 0 0 4 4l5-5a5 5 0 0 0 6-6l-3 3-3-3 3-3Z"/>',
  book: '<path d="M12 5C9 3 5 3 3 4v15c3-1 6-1 9 1 3-2 6-2 9-1V4c-2-1-6-1-9 1v15"/>',
  settings: '<path d="m9 3-1 3-3 1-2 3 2 2-1 3 2 3 3-1 3 2 3-2 3 1 2-3-1-3 2-2-2-3-3-1-1-3z"/><circle cx="11.5" cy="11" r="3"/>',
  clock: '<circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/>',
  award: '<circle cx="12" cy="8" r="5"/><path d="m8 12-1 9 5-3 5 3-1-9"/>',
  plus: '<path d="M12 5v14M5 12h14"/>',
  download: '<path d="M12 3v12m-5-5 5 5 5-5M4 16v4h16v-4"/>',
  upload: '<path d="M12 16V4m-5 5 5-5 5 5M4 16v4h16v-4"/>',
  help: '<circle cx="12" cy="12" r="9"/><path d="M9.5 9a2.5 2.5 0 0 1 5 0c0 2-2.5 2-2.5 4m0 3h.01"/>',
  left: '<path d="m14 6-6 6 6 6"/>', right: '<path d="m10 6 6 6-6 6"/>', down: '<path d="m6 9 6 6 6-6"/>',
  close: '<path d="m6 6 12 12M6 18 18 6"/>',
  search: '<circle cx="10.5" cy="10.5" r="6.5"/><path d="m16 16 5 5"/>',
  pointer: '<path d="m5 3 13 9-7 1-3 7z"/>',
  trash: '<path d="M3 6h18M9 6V3h6v3m-10 0 1 15h12l1-15M10 10v7m4-7v7"/>',
  pin: '<path d="M19 10c0 5-7 11-7 11S5 15 5 10a7 7 0 1 1 14 0Z"/><circle cx="12" cy="10" r="2.5"/>',
  user: '<circle cx="12" cy="7" r="4"/><path d="M4 21v-2a8 8 0 0 1 16 0v2"/>',
  sun: '<circle cx="12" cy="12" r="4"/><path d="M12 2v2m0 16v2M2 12h2m16 0h2M5 5l1 1m12 12 1 1M5 19l1-1M18 6l1-1"/>',
};
const icon = name => `<svg viewBox="0 0 24 24" aria-hidden="true">${paths[name] || paths.calendar}</svg>`;
function hydrateIcons(root = document) {
  root.querySelectorAll('[data-icon]').forEach(element => { element.innerHTML = icon(element.dataset.icon); });
}
hydrateIcons();

let recoveryRaw = null;
let storageMessage = '';
let expectedRaw = null;
let externallyChanged = false;
let data;
try {
  expectedRaw = localStorage.getItem(STORAGE_KEY);
  data = expectedRaw === null ? desktopBootstrap?.timetable ? validateData(desktopBootstrap.timetable) : createPreset() : validateData(JSON.parse(expectedRaw));
  const upgrade = fillKnownClassids(data);
  data = upgrade.data;
  if (expectedRaw === null || upgrade.changed) {
    const initialRaw = JSON.stringify(data);
    localStorage.setItem(STORAGE_KEY, initialRaw);
    expectedRaw = initialRaw;
  }
} catch (error) {
  recoveryRaw = expectedRaw;
  storageMessage = expectedRaw === null
    ? '浏览器暂时无法保存数据。请允许此页面使用本地存储后刷新。'
    : '保存的数据暂时无法读取。原始数据已保留，请先下载原始数据，再导入有效备份或重置为空白课表。';
  data = createPreset();
}

const viewFromHash = () => location.hash === '#tools' && courseToolsSupported ? 'tools' : ({ '#courses': 'courses', '#qr': 'qr' }[location.hash] || 'timetable');
let view = viewFromHash();
let selectedWeek = Math.max(1, Math.min(data.semester.weeks, weekForDate(new Date(), data.semester)));
let editingId = null;
let toastTimer;
let returnFocus;
let reminderController;
let qrLoaded = false;
let qrReady = false;
let pendingQrCourseId = null;
let courseOpenPending = false;
const qrFrame = $('#qr-frame');
function tellQr(message) { qrFrame.contentWindow?.postMessage({ scope: 'kejian:qr', ...message }, '*'); }
function syncQrContext() {
  if (!qrReady) return;
  tellQr({ type: 'active', active: view === 'qr', native: !!nativeBridge });
  tellQr({ type: 'courses', courses: data.courses.filter(course => course.classid).map(({ id, name, classid }) => ({ id, name, classid })) });
  if (pendingQrCourseId) { tellQr({ type: 'selectCourse', courseId: pendingQrCourseId }); pendingQrCourseId = null; }
}
qrFrame.addEventListener('load', () => tellQr({ type: 'active', active: view === 'qr', native: !!nativeBridge }));
window.addEventListener('message', event => {
  if (event.source !== qrFrame.contentWindow || event.data?.scope !== 'kejian:qr') return;
  const message = event.data;
  if (message.type === 'ready') { qrReady = true; syncQrContext(); }
  if (message.type === 'notify' && typeof message.message === 'string' && message.message.length <= 500) toast(message.message);
  if (message.type === 'resize' && Number.isFinite(message.height)) qrFrame.style.height = `${Math.max(500, Math.min(6000, Math.ceil(message.height)))}px`;
  if (message.type === 'image:save' && nativeBridge && typeof message.dataUrl === 'string' && message.dataUrl.length <= 3000000) {
    nativeBridge.postMessage({ type: 'qr:image-export', filename: message.filename, dataUrl: message.dataUrl });
  }
  if (message.type === 'open' && nativeBridge && typeof message.url === 'string' && message.url.length <= 4096) {
    nativeBridge.postMessage({ type: 'qr:open', url: message.url });
  }
});
const colorNames = { coral: '珊瑚橙', blue: '天空蓝', green: '鼠尾草绿', purple: '薰衣草紫', amber: '暖沙黄', pink: '樱花粉' };

function toast(message) {
  const importFeedback = $('#import-feedback');
  if ($('#import-dialog')?.open && importFeedback) { importFeedback.textContent = message; importFeedback.hidden = false; }
  clearTimeout(toastTimer);
  $('#toast').textContent = message;
  $('#toast').classList.add('visible');
  toastTimer = setTimeout(() => $('#toast').classList.remove('visible'), 3600);
}

function showCourseQr(id) {
  const course = data.courses.find(item => item.id === id);
  if (!course?.classid) { toast('请先在课程中填写签到 classid。'); return; }
  pendingQrCourseId = course.id;
  if ($('#course-dialog').open) $('#course-dialog').close();
  if (location.hash === '#qr') render();
  else location.hash = 'qr';
}

function setCourseOpenPending(pending) {
  courseOpenPending = pending;
  $$('[data-signin-course-id],#course-signin-button').forEach(button => { button.disabled = pending; });
}

function openCourseSignin(id) {
  const course = data.courses.find(item => item.id === id);
  if (!course?.classid) { toast('请先在课程中填写签到 classid。'); return; }
  if (!nativeBridge) { showCourseQr(id); toast('二维码已按课程生成。以微信 UA 打开请使用 EXE，或在浏览器中手动设置。'); return; }
  if (courseOpenPending) return;
  try {
    const current = window.QrCore.generateQr({ classId: course.classid });
    setCourseOpenPending(true);
    nativeBridge.postMessage({ type: 'qr:open', url: current.url });
  } catch (error) { setCourseOpenPending(false); toast('签到页未打开：' + error.message); }
}

function showError(selector, message) {
  $(selector).textContent = message;
  $(selector).hidden = !message;
  if (message) $(selector).scrollIntoView({ block: 'nearest', behavior: 'smooth' });
}

function download(text, filename, kind = 'backup') {
  if (nativeBridge) {
    nativeBridge.postMessage({ type: 'backup:export', text, filename, kind });
    return;
  }
  const url = URL.createObjectURL(new Blob([text], { type: filename.endsWith('.md') ? 'text/markdown;charset=utf-8' : 'application/json;charset=utf-8' }));
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = filename;
  document.body.append(anchor);
  anchor.click();
  anchor.remove();
  setTimeout(() => URL.revokeObjectURL(url), 15000);
}

function renderStorageWarning() {
  const warning = $('#storage-warning');
  warning.hidden = !storageMessage;
  warning.replaceChildren();
  if (!storageMessage) return;
  warning.append(document.createTextNode(storageMessage));
  const refresh = document.createElement('button');
  refresh.textContent = '刷新页面';
  refresh.addEventListener('click', () => location.reload());
  warning.append(refresh);
  if (recoveryRaw !== null) {
    const recover = document.createElement('button');
    recover.textContent = '下载原始数据';
    recover.addEventListener('click', () => download(recoveryRaw, `课间-待恢复数据-${dateKey(new Date())}.json`));
    warning.append(recover);
  }
}

function commit(next, { recover = false } = {}) {
  try {
    const validated = validateData(next);
    if (recoveryRaw !== null && !recover) throw new Error('请先下载原始数据，再导入课表或重置为空白课表后继续编辑。');
    if (externallyChanged || localStorage.getItem(STORAGE_KEY) !== expectedRaw) {
      externallyChanged = true;
      storageMessage = '另一个窗口已修改了课程数据。请刷新页面后继续，避免覆盖其他窗口的修改。';
      renderStorageWarning();
      throw new Error('数据已在其他窗口更新，请刷新页面后继续。');
    }
    const encoded = JSON.stringify(validated);
    localStorage.setItem(STORAGE_KEY, encoded);
    expectedRaw = encoded;
    data = validated;
    reminderController?.setData(data);
    recoveryRaw = null;
    storageMessage = '';
    selectedWeek = Math.max(1, Math.min(selectedWeek, data.semester.weeks));
    render();
    syncNativeTimetable();
    return true;
  } catch (error) {
    if (error.name === 'QuotaExceededError' || error.name === 'SecurityError') {
      storageMessage = '浏览器未能保存本次修改，原来的课表仍保留。请检查存储权限或空间，并导出备份。';
      renderStorageWarning();
    }
    toast(error.message || '保存失败，请检查输入后重试。');
    return false;
  }
}

function matches(course, search) {
  return [course.name, course.teacher, course.room, course.notes, course.id, course.classid || '', ...course.meetings.map(meeting => meeting.room || '')].some(text => text.toLocaleLowerCase().includes(search.trim().toLocaleLowerCase()));
}

function parityText(parity) { return { all: '每周', odd: '单周', even: '双周' }[parity]; }
function meetingText(meeting) {
  return `周${'一二三四五六日'[meeting.day - 1]} · ${meeting.start}${meeting.start === meeting.end ? '' : '–' + meeting.end} 节 · ${parityText(meeting.parity)}`;
}
function dateLabel(date) { return `${String(date.getMonth() + 1).padStart(2, '0')}.${String(date.getDate()).padStart(2, '0')}`; }
function periodTime(meeting) { return `${data.semester.periods[meeting.start - 1][0]}–${data.semester.periods[meeting.end - 1][1]}`; }

function renderToday() {
  const now = new Date();
  $('#current-date').textContent = new Intl.DateTimeFormat('zh-CN', { month: 'long', day: 'numeric', weekday: 'long' }).format(now);
  const actualWeek = weekForDate(now, data.semester);
  const day = ((now.getDay() + 6) % 7) + 1;
  const sessions = actualWeek >= 1 && actualWeek <= data.semester.weeks
    ? sessionsForWeek(data.courses, actualWeek).filter(session => session.meeting.day === day) : [];
  const currentMinute = now.getHours() * 60 + now.getMinutes();
  $('#today-count').textContent = sessions.length ? `${sessions.length} 堂课` : '';
  $('#today-agenda').innerHTML = sessions.length ? sessions.map(({ course, meeting, room }) =>
    `<div class="agenda-item ${course.color} ${minutes(data.semester.periods[meeting.end - 1][1]) < currentMinute ? 'finished' : ''}"><span class="agenda-time">${periodTime(meeting)}</span><strong>${esc(course.name)}</strong><p class="agenda-room">${esc(room || '地点待定')}</p></div>`
  ).join('') : `<div class="agenda-empty">${icon('sun')}${actualWeek < 1 ? '新学期还没开始，<br>先安排好期待的一周。' : actualWeek > data.semester.weeks ? '学期已经结束，<br>给自己一段休息时光。' : '今天没有课程，<br>享受一点自由时光。'}</div>`;
}

function renderCalendar() {
  const dates = weekDates(data.semester, selectedWeek);
  const today = dateKey(new Date());
  const dayCount = $('#show-weekends').checked ? 7 : 5;
  const search = $('#search-input').value;
  const sessions = sessionsForWeek(data.courses, selectedWeek).filter(({ course, meeting }) => meeting.day <= dayCount && matches(course, search));
  const calendar = $('#calendar');
  calendar.classList.toggle('hide-weekends', dayCount === 5);
  calendar.style.setProperty('--day-count', dayCount);
  calendar.style.setProperty('--period-count', data.semester.periods.length);
  let html = `<div class="time-corner" style="grid-column:1;grid-row:1">${androidPlatform ? `<span>第${selectedWeek}周</span><small>${dates[0].getMonth() + 1}月</small>` : '节次 / 时间'}</div>`;
  for (let day = 1; day <= dayCount; day++) {
    const date = dates[day - 1];
    html += `<div class="day-header ${dateKey(date) === today ? 'is-today' : ''}" style="grid-column:${day + 1};grid-row:1"><span class="day-name">${DAYS[day - 1]}</span><span class="day-date">${String(date.getDate()).padStart(2, '0')}</span></div>`;
  }
  data.semester.periods.forEach(([start, end], index) => {
    const breakClass = index > 0 && minutes(start) - minutes(data.semester.periods[index - 1][1]) >= 60 ? 'is-break' : '';
    html += `<div class="period-label ${breakClass}" style="grid-row:${index + 2};grid-column:1"><strong>${String(index + 1).padStart(2, '0')}</strong><small>${start}</small><small>${end}</small></div>`;
    for (let day = 1; day <= dayCount; day++) {
      const occupied = sessions.some(({ meeting }) => meeting.day === day && index + 1 >= meeting.start && index + 1 <= meeting.end);
      html += `<button class="slot ${breakClass} ${day > 5 ? 'is-weekend' : ''} ${dateKey(dates[day - 1]) === today ? 'is-today' : ''}" style="grid-column:${day + 1};grid-row:${index + 2}" data-day="${day}" data-period="${index + 1}" aria-label="${DAYS[day - 1]}第 ${index + 1} 节，${androidPlatform ? '双击' : ''}添加课程" ${occupied ? 'tabindex="-1"' : ''}></button>`;
    }
  });
  for (const { course, meeting, room, online } of sessions) {
    const displayRoom = androidPlatform ? online ? '线上' : room.replace(/^雅安\s*·\s*/, '') : room;
    html += `<button class="course-event ${course.color} ${meeting.start === meeting.end ? 'single-period' : ''}" style="grid-column:${meeting.day + 1};grid-row:${meeting.start + 1} / ${meeting.end + 2}" data-course-id="${esc(course.id)}" title="${esc(`${course.name} · ${course.teacher || '教师待定'}\n${room || '地点待定'}\n${meetingText(meeting)} · ${periodTime(meeting)}\n第 ${meeting.startWeek}–${meeting.endWeek} 周\n点击编辑课程`)}" aria-label="${esc(`${course.name}，${DAYS[meeting.day - 1]}第 ${meeting.start} 至 ${meeting.end} 节，${room || '地点待定'}，点击编辑`)}"><span class="event-title">${esc(course.name)}</span><span class="event-room">${esc(displayRoom || '地点待定')}</span><span class="event-bottom"><span>${meeting.start}${meeting.start !== meeting.end ? '–' + meeting.end : ''} 节</span>${online || meeting.parity !== 'all' ? `<span class="event-parity">${online ? '线上' : parityText(meeting.parity)}</span>` : ''}</span></button>`;
  }
  calendar.innerHTML = html;
  $('#week-select').innerHTML = Array.from({ length: data.semester.weeks }, (_, index) => `<option value="${index + 1}" ${index + 1 === selectedWeek ? 'selected' : ''}>第 ${index + 1} 周</option>`).join('');
  $('#week-range').textContent = `${dateLabel(dates[0])} — ${dateLabel(dates[6])}`;
  $('#prev-week').disabled = selectedWeek <= 1;
  $('#next-week').disabled = selectedWeek >= data.semester.weeks;
  $('#semester-caption').textContent = `${data.semester.name} · 共 ${data.semester.weeks} 周`;
  const hiddenWeekendCount = dayCount === 5 ? sessionsForWeek(data.courses, selectedWeek).filter(({ meeting }) => meeting.day > 5).length : 0;
  const status = $('#filter-status');
  status.hidden = !search.trim() && hiddenWeekendCount === 0;
  status.textContent = [search.trim() ? `搜索「${search.trim()}」：当前视图共 ${sessions.length} 堂课` : '', hiddenWeekendCount ? `另有 ${hiddenWeekendCount} 堂周末课程，打开「周末」可查看` : ''].filter(Boolean).join(' · ');
}

function renderCourses() {
  const search = $('#course-search').value;
  const courses = data.courses.filter(course => matches(course, search));
  $('#course-list-count').textContent = `(${courses.length})`;
  $('#course-list').innerHTML = courses.length ? courses.map(course => `<article class="course-card ${course.color}">
    <button class="course-card-edit" data-course-id="${esc(course.id)}" aria-label="编辑${esc(course.name)}">
      <div class="course-card-top"><span class="course-card-icon">${icon('book')}</span><span class="credit-badge">${course.credits} 学分</span></div>
      <h3>${esc(course.name)}</h3><div class="course-meta">${icon('user')}${esc(course.teacher || '教师待定')}</div><div class="course-meta">${icon('pin')}${esc(course.room || '地点待定')}</div>
      <div class="meeting-tags">${course.unscheduled ? '<span class="meeting-tag">暂不排课 · 教师安排／自主学习</span>' : course.meetings.map(meeting => `<span class="meeting-tag">${meetingText(meeting)} · 第 ${meeting.startWeek}–${meeting.endWeek} 周 · ${esc(meeting.room || course.room || '地点待定')}${meeting.onlineWeeks?.length ? ` · 第 ${meeting.onlineWeeks.join('、')} 周线上` : ''}</span>`).join('')}</div>
      ${course.notes ? `<p class="course-notes">${esc(course.notes)}</p>` : ''}
      <div class="course-card-footer"><span>${esc(course.id)} · ${course.unscheduled ? '暂不排课' : `${course.meetings.length} 个安排`}</span>${icon('right')}</div>
    </button>
    ${course.classid ? `<div class="course-signin"><span class="course-classid">classid · ${esc(course.classid)}</span><div class="course-signin-actions"><button type="button" class="button button-white" data-qr-course-id="${esc(course.id)}">生成二维码</button><button type="button" class="button button-primary" data-signin-course-id="${esc(course.id)}" ${courseOpenPending ? 'disabled' : ''}>${nativeBridge ? '跳转签到页' : '签到页说明'}</button>${courseToolsSupported ? `<button type="button" class="button button-white" data-tools-course-id="${esc(course.id)}">签到与作业</button>` : ''}</div></div>` : ''}
  </article>`).join('') : `<div class="empty-state">${icon(search.trim() ? 'search' : 'book')}<h3>${search.trim() ? '没有找到相关课程' : '新学期，从第一门课开始'}</h3><p>${search.trim() ? '试试课程名称、教师姓名、classid 或上课地点。' : '添加课程，把这一周安排得从容一点。'}</p>${!search.trim() ? '<button class="button button-primary" data-empty-add>添加第一门课</button>' : ''}</div>`;
}

function render() {
  renderStorageWarning();
  $('#nav-count').textContent = data.courses.length;
  $('#stat-courses').textContent = data.courses.length;
  $('#stat-credits').textContent = Number(data.courses.reduce((sum, course) => sum + course.credits, 0).toFixed(2));
  $('#stat-lessons').textContent = sessionsForWeek(data.courses, selectedWeek).reduce((sum, { meeting }) => sum + meeting.end - meeting.start + 1, 0);
  $('#stat-lessons-label').textContent = `第 ${selectedWeek} 周课时`;
  $('#timetable-view').hidden = view !== 'timetable';
  $('#courses-view').hidden = view !== 'courses';
  $('#qr-view').hidden = view !== 'qr';
  $('#tools-view').hidden = view !== 'tools';
  document.body.classList.toggle('qr-active', view === 'qr');
  if (androidPlatform) document.body.classList.toggle('timetable-active', view === 'timetable');
  document.body.classList.toggle('pc-timetable-active', pcPlatform && view === 'timetable');
  $('.overview').hidden = view === 'qr' || view === 'tools';
  $('.header-actions').hidden = view === 'qr' || view === 'tools';
  const viewTitle = { timetable: '我的课表', courses: '课程管理', qr: '二维码预测', tools: '课程工具' }[view];
  $('#page-title').innerHTML = `${viewTitle}<span class="title-dot">.</span>`;
  $('#breadcrumb-current').textContent = viewTitle;
  $('#page-subtitle').textContent = { timetable: `${data.semester.name} · 共 ${data.semester.weeks} 周`, courses: '管理当前学期的课程，包含实践课与 MOOC。', qr: '四川农业大学 EOL · 按课程 classid 和当前时间生成，无需原二维码。', tools: '沿用本人浏览器登录，手动签到与提交文件型作业。' }[view];
  if (view === 'qr' && !qrLoaded) { qrLoaded = true; qrFrame.srcdoc = qrDocument; }
  syncQrContext();
  const pendingCount = data.courses.filter(course => course.unscheduled).length;
  $('#unscheduled-summary').hidden = !pendingCount;
  $('#unscheduled-count').textContent = pendingCount;
  $$('[data-view]').forEach(button => button.classList.toggle('active', button.dataset.view === view));
  renderToday();
  renderCalendar();
  renderCourses();
  renderCourseTools();
}

let toolsPending = false;
function renderCourseTools() {
  $('#course-tools-nav').hidden = !courseToolsSupported;
  if (!courseToolsSupported) return;
  const select = $('#tools-course-select');
  const previous = select.value;
  select.innerHTML = '<option value="">在工具中选择本人课程</option>' + data.courses.filter(course => course.classid).map(course => `<option value="${esc(course.id)}">${esc(course.name)} · ${esc(course.classid)}</option>`).join('');
  if ([...select.options].some(option => option.value === previous)) select.value = previous;
  $$('#tools-open,#tools-extension').forEach(button => { button.disabled = toolsPending; });
}
function showCourseTools(id) {
  if (!courseToolsSupported) return;
  $('#tools-course-select').value = id;
  location.hash = 'tools';
}
function sendCourseTool(action) {
  if (!courseToolsSupported || toolsPending) return;
  toolsPending = true;
  $('#tools-status').classList.remove('tools-error');
  $('#tools-status').textContent = action === 'extension' ? '正在打开扩展文件夹…' : '正在准备课程工具窗口，首次启动可能需要几秒…';
  renderCourseTools();
  nativeBridge.postMessage({ type: 'course-tools:open', action, courseId: $('#tools-course-select').value });
}
$('#tools-open').addEventListener('click', () => sendCourseTool('signin'));
$('#tools-extension').addEventListener('click', () => {
  sendCourseTool('extension');
  $('#extension-install-guide').scrollIntoView({ block: 'start', behavior: 'smooth' });
});

function openDialog(dialog) {
  returnFocus = document.activeElement;
  dialog.showModal();
}

function askConfirm(title, message, action = '确认') {
  const dialog = $('#confirm-dialog');
  $('#confirm-title').textContent = title;
  $('#confirm-message').textContent = message;
  $('#confirm-action').textContent = action;
  dialog.returnValue = '';
  return new Promise(resolve => {
    dialog.addEventListener('close', () => resolve(dialog.returnValue === 'confirm'), { once: true });
    dialog.showModal();
    $('#confirm-dialog [data-close]').focus();
  });
}

function options(from, to, value, suffix = '') {
  return Array.from({ length: to - from + 1 }, (_, index) => `<option value="${index + from}" ${index + from === value ? 'selected' : ''}>${index + from}${suffix}</option>`).join('');
}

function meetingRow(meeting) {
  const row = document.createElement('div');
  row.className = 'meeting-row';
  row.innerHTML = `<label>星期<select data-field="day" aria-label="上课星期">${DAYS.map((day, index) => `<option value="${index + 1}" ${meeting.day === index + 1 ? 'selected' : ''}>${day}</option>`).join('')}</select></label><label>开始节次<select data-field="start" aria-label="开始节次">${options(1, data.semester.periods.length, meeting.start, ' 节')}</select></label><label>结束节次<select data-field="end" aria-label="结束节次">${options(1, data.semester.periods.length, meeting.end, ' 节')}</select></label><label>开始周<input type="number" data-field="startWeek" aria-label="开始周" min="1" max="${data.semester.weeks}" required value="${meeting.startWeek}"></label><label>结束周<input type="number" data-field="endWeek" aria-label="结束周" min="1" max="${data.semester.weeks}" required value="${meeting.endWeek}"></label><label>重复<select data-field="parity" aria-label="单双周">${['all', 'odd', 'even'].map(parity => `<option value="${parity}" ${meeting.parity === parity ? 'selected' : ''}>${parityText(parity)}</option>`).join('')}</select></label><label class="meeting-location">本次地点（选填）<input type="text" data-field="room" aria-label="本次上课地点" maxlength="80" placeholder="使用课程地点" value="${esc(meeting.room || '')}"></label><label class="meeting-online">线上周次（选填）<input type="text" data-field="onlineWeeks" aria-label="线上周次" placeholder="例如：1,5,7" value="${esc((meeting.onlineWeeks || []).join(','))}"></label><button type="button" class="icon-button remove-meeting" aria-label="删除此上课安排" title="删除此上课安排">${icon('trash')}</button>`;
  row.querySelector('[data-field="start"]').addEventListener('change', event => {
    const end = row.querySelector('[data-field="end"]');
    if (Number(end.value) < Number(event.target.value)) end.value = event.target.value;
  });
  row.querySelector('.remove-meeting').addEventListener('click', () => {
    if ($('#meeting-rows').children.length <= 1) { toast('至少保留一个上课安排。'); return; }
    row.remove();
  });
  return row;
}

function syncScheduleFields() {
  const unscheduled = $('#unscheduled-course').checked;
  $('#meeting-rows').hidden = unscheduled;
  $('#meeting-rows').querySelectorAll('input,select,button').forEach(field => { field.disabled = unscheduled; });
  $('#add-meeting').disabled = unscheduled;
}

$('#unscheduled-course').addEventListener('change', syncScheduleFields);

function syncCourseSignin() {
  const course = data.courses.find(item => item.id === editingId);
  const button = $('#course-signin-button');
  button.hidden = !course?.classid || $('#course-form').elements.classid.value.trim() !== course.classid;
  button.disabled = courseOpenPending;
  button.textContent = nativeBridge ? '跳转签到页' : '生成签到二维码';
}
$('#course-form').elements.classid.addEventListener('input', syncCourseSignin);
$('#course-signin-button').addEventListener('click', () => openCourseSignin(editingId));

function openCourse(id = null, preset = {}) {
  const course = id ? data.courses.find(item => item.id === id) : null;
  if (id && !course) return;
  editingId = course?.id ?? null;
  $('#course-form').reset();
  $('#course-dialog-title').textContent = course ? '编辑课程' : '添加课程';
  $('#delete-course').hidden = !course;
  for (const field of ['name', 'teacher', 'room', 'notes', 'credits', 'classid']) $('#course-form').elements[field].value = course?.[field] ?? (field === 'credits' ? 2 : '');
  const selectedColor = course?.color ?? COLORS[data.courses.length % COLORS.length];
  $('#color-picker').innerHTML = COLORS.map(color => `<label class="color-choice ${color}" title="${colorNames[color]}"><input type="radio" name="color" value="${color}" ${color === selectedColor ? 'checked' : ''} aria-label="${colorNames[color]}"><span></span></label>`).join('');
  $('#meeting-rows').replaceChildren(...(course?.meetings.length ? course.meetings : [{ day: preset.day ?? 1, start: preset.period ?? 1, end: Math.min((preset.period ?? 1) + 1, data.semester.periods.length), startWeek: 1, endWeek: data.semester.weeks, parity: 'all' }]).map(meetingRow));
  $('#unscheduled-course').checked = course?.unscheduled ?? false;
  syncScheduleFields();
  syncCourseSignin();
  showError('#course-error', '');
  openDialog($('#course-dialog'));
  $('#course-form').elements.name.focus();
}

$('#course-form').addEventListener('submit', event => {
  event.preventDefault();
  showError('#course-error', '');
  try {
    const form = new FormData(event.currentTarget);
    const unscheduled = $('#unscheduled-course').checked;
    const meetings = unscheduled ? [] : $$('#meeting-rows .meeting-row').map(row => Object.fromEntries([...row.querySelectorAll('[data-field]')].map(input => {
      const field = input.dataset.field;
      const value = field === 'onlineWeeks' ? input.value.trim() ? input.value.trim().split(/[,，\s]+/).map(Number) : []
        : ['parity', 'room'].includes(field) ? input.value : Number(input.value);
      return [field, value];
    })));
    const candidate = validateCourse({ id: editingId ?? crypto.randomUUID(), name: form.get('name'), teacher: form.get('teacher'), room: form.get('room'), notes: form.get('notes'), classid: form.get('classid'), credits: Number(form.get('credits')), color: form.get('color'), meetings, unscheduled }, data.semester);
    const conflicts = findConflicts(candidate, data.courses, data.semester);
    if (conflicts.length) {
      const first = conflicts[0];
      throw new Error(`与「${first.name}」时间冲突：第 ${first.week} 周，${DAYS[first.day - 1]}第 ${first.start}${first.end !== first.start ? '–' + first.end : ''} 节。请调整上课时间或周次。`);
    }
    const courses = editingId ? data.courses.map(course => course.id === editingId ? candidate : course) : [...data.courses, candidate];
    if (commit({ ...data, courses })) {
      $('#course-dialog').close();
      toast(editingId ? '课程已更新，新的安排已保存。' : '课程已添加。');
    }
  } catch (error) { showError('#course-error', error.message); }
});

$('#delete-course').addEventListener('click', async () => {
  const course = data.courses.find(item => item.id === editingId);
  if (!course) return;
  if (await askConfirm('删除这门课程？', `「${course.name}」的所有上课安排也会一并移除。`, '删除课程')) {
    if (commit({ ...data, courses: data.courses.filter(item => item.id !== editingId) })) {
      $('#course-dialog').close();
      toast('课程已删除。');
    }
  }
});

$('#add-meeting').addEventListener('click', () => {
  if ($('#meeting-rows').children.length >= 20) { toast('一门课最多添加 20 个上课安排。'); return; }
  $('#meeting-rows').append(meetingRow({ day: 1, start: 1, end: Math.min(2, data.semester.periods.length), startWeek: 1, endWeek: data.semester.weeks, parity: 'all' }));
});

function renderPeriodRow(period, index) {
  const row = document.createElement('div');
  row.className = 'period-row';
  row.innerHTML = `<span>第 ${index + 1} 节</span><input type="time" required value="${period[0]}" aria-label="第 ${index + 1} 节上课时间"><input type="time" required value="${period[1]}" aria-label="第 ${index + 1} 节下课时间"><button type="button" class="icon-button" aria-label="移除此节次" title="只能移除最后一个节次">${icon('trash')}</button>`;
  row.querySelector('button').addEventListener('click', () => {
    const rows = $$('#period-rows .period-row');
    if (rows.length === 1) { toast('每天至少保留一节课。'); return; }
    if (row !== rows.at(-1)) { toast('请从最后一个节次开始移除，避免更改已有课程的节次编号。'); return; }
    row.remove();
  });
  return row;
}

function openSettings() {
  const form = $('#settings-form');
  try { $('#legacy-data-tools').hidden = localStorage.getItem('kejian.timetable.v1') === null; } catch { $('#legacy-data-tools').hidden = true; }
  form.elements.semesterName.value = data.semester.name;
  form.elements.startDate.value = data.semester.startDate;
  form.elements.weeks.value = data.semester.weeks;
  $('#period-rows').replaceChildren(...data.semester.periods.map(renderPeriodRow));
  showError('#settings-error', '');
  openDialog($('#settings-dialog'));
}

$('#add-period').addEventListener('click', () => {
  const rows = $$('#period-rows .period-row');
  if (rows.length >= 16) { toast('每天最多设置 16 节课。'); return; }
  const lastEnd = rows.at(-1)?.querySelectorAll('input')[1].value || '08:00';
  const format = mins => `${String(Math.floor(mins / 60)).padStart(2, '0')}:${String(mins % 60).padStart(2, '0')}`;
  const start = Math.min(minutes(lastEnd) + 10, 23 * 60 + 13);
  const end = Math.min(start + 45, 23 * 60 + 59);
  $('#period-rows').append(renderPeriodRow([format(start), format(end)], rows.length));
});

$('#settings-form').addEventListener('submit', event => {
  event.preventDefault();
  try {
    const form = new FormData(event.currentTarget);
    const semester = validateSemester({ name: form.get('semesterName'), startDate: form.get('startDate'), weeks: Number(form.get('weeks')), periods: $$('#period-rows .period-row').map(row => [...row.querySelectorAll('input')].map(input => input.value)) });
    for (const course of data.courses) {
      for (const meeting of course.meetings) {
        if (meeting.endWeek > semester.weeks) throw new Error(`「${course.name}」安排到第 ${meeting.endWeek} 周。请先调整该课程，再缩短学期。`);
        if (meeting.end > semester.periods.length) throw new Error(`「${course.name}」使用了第 ${meeting.end} 节。请先调整该课程，再移除节次。`);
      }
    }
    if (commit({ ...data, semester })) {
      selectedWeek = Math.max(1, Math.min(semester.weeks, weekForDate(new Date(), semester)));
      render();
      $('#settings-dialog').close();
      toast('学期设置已保存。');
    }
  } catch (error) { showError('#settings-error', error.message); }
});

$('#reset-preset').addEventListener('click', async () => {
  if (await askConfirm('重置为空白课表？', '将恢复空白课表模板及学期设置。请先导出需要保留的当前数据。', '重置为空白课表')) {
    if (commit(createPreset(), { recover: true })) {
      $('#settings-dialog').close();
      toast('已重置为空白课表。');
    }
  }
});

function exportCurrentTimetable() {
  try {
    download(exportTimetableFile(data), `课间-${data.semester.name.replace(/[<>:"/\\|?*]/g, '-')}-${dateKey(new Date())}.json`);
    if (!nativeBridge) toast('已发起课表下载。恢复时请使用“导入课表”，无需双击 JSON 文件。');
  } catch (error) { toast('课表未导出：' + error.message); }
}
$('#export-button').addEventListener('click', exportCurrentTimetable);

let pendingImportData = null;
let importReadToken = 0;
const importDialog = $('#import-dialog');
$('#import-format-guide').textContent = importGuide;
function resetImportPreview() {
  pendingImportData = null;
  $('#import-preview').hidden = true;
  $('#import-preview-courses').replaceChildren();
  $('#apply-import').disabled = true;
}
function openTimetableImport() {
  importReadToken++;
  resetImportPreview();
  $('#import-ready').checked = false;
  $('#choose-import-file').disabled = true;
  $('#choose-import-file').setAttribute('aria-busy', 'false');
  $('#import-filename').textContent = '文件不超过 4 MB';
  $('#import-feedback').hidden = true;
  $('#import-feedback').textContent = '';
  showError('#import-error', '');
  $('.import-body').scrollTop = 0;
  openDialog(importDialog);
}
$('#import-button').addEventListener('click', openTimetableImport);
importDialog.addEventListener('close', () => { importReadToken++; resetImportPreview(); });
$('#import-ready').addEventListener('change', event => {
  $('#choose-import-file').disabled = !event.target.checked;
  $('#apply-import').disabled = !event.target.checked || !pendingImportData;
});
$('#choose-import-file').addEventListener('click', () => { if ($('#import-ready').checked) $('#import-file').click(); });
$('#export-before-import').addEventListener('click', exportCurrentTimetable);
$('#download-import-guide').addEventListener('click', () => {
  download(importGuide, '课表数据格式说明.md', 'guide');
  if (!nativeBridge) toast('已发起格式说明下载，可与自己的课表材料一起交给 AI。');
});
$('#download-import-example').addEventListener('click', () => {
  download(importExample, '课表导入示例.json', 'example');
  if (!nativeBridge) toast('已发起示例文件下载，请将示例内容替换为本人课程信息。');
});
$('#copy-import-guide').addEventListener('click', async () => {
  const text = '请将我接下来提供的本人课表材料整理为课间可导入的 JSON 文件。请先阅读完整说明，缺少必要信息时向我确认，不要照抄示例。\n\n' + importGuide;
  try {
    try { await navigator.clipboard.writeText(text); }
    catch {
      const previous = document.activeElement;
      const input = document.createElement('textarea');
      input.value = text;
      input.style.cssText = 'position:fixed;left:-10000px;top:0';
      importDialog.append(input);
      try {
        input.focus(); input.select();
        if (!document.execCommand('copy')) throw new Error('复制权限不可用');
      } finally { input.remove(); previous?.focus?.(); }
    }
    toast('AI 提示词和格式说明已复制，请与自己的课表一起发送给 AI。');
  } catch {
    $('#import-full-format-guide').open = true;
    $('#import-format-guide').focus();
    toast('无法自动复制，已展开格式说明，可手动选择复制或下载文件。');
  }
});

function renderImportPreview(candidate) {
  const summary = summarizeTimetable(candidate);
  $('#import-summary').textContent = `${summary.courses} 门课程 · ${summary.credits} 学分 · ${summary.unscheduled} 门待安排 · ${summary.classids} 门已填写 classid`;
  $('#import-semester-summary').textContent = `${candidate.semester.name} · 第一周星期一 ${candidate.semester.startDate} · ${candidate.semester.weeks} 周 · 每天 ${candidate.semester.periods.length} 节`;
  $('#import-preview-courses').innerHTML = candidate.courses.map(course => `<tr><td><strong>${esc(course.name)}</strong><br>${esc(course.id)}</td><td>${esc(course.teacher || '教师待定')}<br>${esc(course.room || '地点待定')}</td><td>${course.unscheduled ? '教师安排／自主学习' : course.meetings.map(meeting => `${DAYS[meeting.day - 1]} ${meeting.start}–${meeting.end} 节，第 ${meeting.startWeek}–${meeting.endWeek} 周（${parityText(meeting.parity)}）${meeting.room ? ' · ' + esc(meeting.room) : ''}${meeting.onlineWeeks?.length ? ' · 第 ' + meeting.onlineWeeks.join('、') + ' 周线上' : ''}`).join('<br>')}</td><td>${esc(course.classid || '未填写')}</td></tr>`).join('');
  $('#import-preview').hidden = false;
  $('#apply-import').disabled = !$('#import-ready').checked;
  $('#import-preview').scrollIntoView({ block: 'start', behavior: 'smooth' });
}

$('#import-file').addEventListener('change', async event => {
  const file = event.target.files[0];
  event.target.value = '';
  if (!file) return;
  const token = ++importReadToken;
  resetImportPreview();
  showError('#import-error', '');
  $('#import-feedback').hidden = true;
  if (!importDialog.open || !$('#import-ready').checked) { showError('#import-error', '请先准备自己的课表文件，并勾选确认。'); return; }
  $('#import-filename').textContent = `正在校验：${file.name}`;
  $('#choose-import-file').setAttribute('aria-busy', 'true');
  try {
    if (!file.name.toLowerCase().endsWith('.json')) throw new Error('请选择 .json 课表文件。');
    if (file.size > 4000000) throw new Error('课表文件不能超过 4 MB。');
    const candidate = parseTimetableFile(await file.text());
    if (token !== importReadToken || !importDialog.open) return;
    pendingImportData = candidate;
    $('#import-filename').textContent = file.name;
    renderImportPreview(candidate);
  } catch (error) {
    if (token !== importReadToken || !importDialog.open) return;
    $('#import-filename').textContent = file.name;
    showError('#import-error', error.message);
  } finally {
    if (token === importReadToken) $('#choose-import-file').setAttribute('aria-busy', 'false');
  }
});
$('#apply-import').addEventListener('click', () => {
  if (!pendingImportData || !$('#import-ready').checked) return;
  if (commit(pendingImportData, { recover: true })) {
    pendingImportData = null;
    $('#apply-import').disabled = true;
    selectedWeek = Math.max(1, Math.min(data.semester.weeks, weekForDate(new Date(), data.semester)));
    $('#search-input').value = '';
    $('#course-search').value = '';
    render();
    importDialog.close();
    location.hash = 'timetable';
    toast(`已导入 ${data.courses.length} 门课程，学期和作息已更新。`);
  } else {
    showError('#import-error', '课表未导入。请处理当前存储提示后重试，原文件和现有课表已保留。');
  }
});

document.addEventListener('click', event => {
  const toolButton = event.target.closest('[data-tools-course-id]');
  if (toolButton) { showCourseTools(toolButton.dataset.toolsCourseId); return; }
  const qrButton = event.target.closest('[data-qr-course-id]');
  if (qrButton) { showCourseQr(qrButton.dataset.qrCourseId); return; }
  const signinButton = event.target.closest('[data-signin-course-id]');
  if (signinButton) { openCourseSignin(signinButton.dataset.signinCourseId); return; }
  const close = event.target.closest('[data-close]');
  if (close) close.closest('dialog').close('');
  const courseButton = event.target.closest('[data-course-id]');
  if (courseButton) openCourse(courseButton.dataset.courseId);
  const emptySlot = event.target.closest('.slot');
  if (emptySlot && !androidPlatform) openCourse(null, { day: Number(emptySlot.dataset.day), period: Number(emptySlot.dataset.period) });
  if (event.target.closest('[data-empty-add]')) openCourse();
});

$('#calendar').addEventListener('kejian:slot-doubletap', event => {
  if (!androidPlatform || document.querySelector('dialog[open]')) return;
  const slot = event.target.closest('.slot');
  if (slot && $('#calendar').contains(slot)) openCourse(null, { day: Number(slot.dataset.day), period: Number(slot.dataset.period) });
});

$$('[data-view]').forEach(button => button.addEventListener('click', () => { location.hash = button.dataset.view; }));
window.addEventListener('hashchange', () => { view = viewFromHash(); render(); window.scrollTo(0, 0); });
$('#settings-button').addEventListener('click', openSettings);
$('#help-button').addEventListener('click', () => openDialog($('#help-dialog')));
$('#add-course').addEventListener('click', () => openCourse());
$('#confirm-action').addEventListener('click', () => $('#confirm-dialog').close('confirm'));
$('#prev-week').addEventListener('click', () => { if (selectedWeek > 1) { selectedWeek--; render(); } });
$('#next-week').addEventListener('click', () => { if (selectedWeek < data.semester.weeks) { selectedWeek++; render(); } });
$('#week-select').addEventListener('change', event => { selectedWeek = Number(event.target.value); render(); });
$('#show-weekends').addEventListener('change', renderCalendar);
$('#this-week').addEventListener('click', () => {
  const week = weekForDate(new Date(), data.semester);
  selectedWeek = Math.max(1, Math.min(week, data.semester.weeks));
  render();
  if (week < 1 || week > data.semester.weeks) toast(week < 1 ? '当前学期还未开始，已显示第一周。' : '当前学期已结束，已显示最后一周。');
});
$('#search-input').addEventListener('input', renderCalendar);
$('#course-search').addEventListener('input', renderCourses);
$$('dialog').forEach(dialog => dialog.addEventListener('close', () => {
  if (dialog.id !== 'confirm-dialog') returnFocus?.focus?.();
}));
document.addEventListener('keydown', event => {
  if (view === 'qr' || view === 'tools') return;
  if (event.ctrlKey || event.metaKey || event.altKey || event.isComposing || document.querySelector('dialog[open]') || /INPUT|TEXTAREA|SELECT/.test(event.target.tagName)) return;
  if (event.key.toLowerCase() === 'n') { event.preventDefault(); openCourse(); }
  if (event.key === '/') { event.preventDefault(); $(view === 'timetable' ? '#search-input' : '#course-search').focus(); }
});
window.addEventListener('storage', event => {
  if (event.key === STORAGE_KEY || event.key === null) {
    externallyChanged = true;
    storageMessage = '另一个窗口修改或清除了课程数据。请刷新页面后继续编辑。';
    renderStorageWarning();
  }
});
setInterval(renderToday, 60000);
render();

$('#export-legacy').addEventListener('click', () => {
  try {
    const raw = localStorage.getItem('kejian.timetable.v1');
    if (raw === null) { toast('没有找到更新前的课表。'); return; }
    download(raw, '课间-更新前课表.json');
  } catch (error) { toast(error.message || '暂时无法读取旧课表。'); }
});

const browserRemindersSupported = !nativeBridge && location.protocol !== 'file:' && window.isSecureContext && typeof window.Notification === 'function';
let reminderRequestPending = false;
function renderReminderSettings() {
  if (!reminderController) return;
  const control = $('#browser-reminders');
  control.checked = reminderController.enabled;
  control.disabled = !browserRemindersSupported || reminderRequestPending;
  $('#test-reminder').disabled = !reminderController.enabled || !browserRemindersSupported;
  $('#reminder-status').textContent = !browserRemindersSupported
    ? '当前本地文件不支持浏览器系统通知，请使用上方的 Windows 静态版提醒。'
    : reminderController.error || (reminderController.enabled ? '已开启：每节上课、下课时发送系统通知。请保持网页打开。' : '已关闭。开启时，浏览器会请求通知权限。');
}
reminderController = new BrowserReminderController({ notificationApi: browserRemindersSupported ? window.Notification : undefined,
  storage: { getItem: key => localStorage.getItem(key), setItem: (key, value) => localStorage.setItem(key, value) }, key: STORAGE_KEY + '.reminders', onChange: renderReminderSettings });
reminderController.setData(data);
renderReminderSettings();
$('#reminders-button').addEventListener('click', () => { reminderController.sync(); openDialog($('#reminder-dialog')); });
$('#browser-reminders').addEventListener('change', async event => {
  const enabled = event.target.checked;
  reminderRequestPending = true;
  try { await reminderController.setEnabled(enabled); }
  catch (error) { toast('无法开启通知：' + error.message); }
  finally { reminderRequestPending = false; renderReminderSettings(); }
});
$('#test-reminder').addEventListener('click', () => {
  try { reminderController.test(); } catch (error) { toast(error.message); }
});
$('#export-reminder-plan').addEventListener('click', () => {
  download(JSON.stringify(createReminderPlan(data), null, 2), '课间提醒计划.json');
  if (!nativeBridge) toast('已发起提醒计划下载，请在 Windows 提醒工具中载入。');
});
async function checkDesktopReminders() {
  try {
    if (navigator.locks?.request) await navigator.locks.request(STORAGE_KEY + '.reminder-check', { ifAvailable: true }, lock => { if (lock) reminderController.tick(); });
    else reminderController.tick();
  } catch (error) { $('#reminder-status').textContent = '提醒检查失败：' + error.message; }
}
setInterval(checkDesktopReminders, 1000);
window.addEventListener('focus', checkDesktopReminders);
document.addEventListener('visibilitychange', () => { if (!document.hidden) checkDesktopReminders(); });
window.addEventListener('storage', event => { if (event.key === STORAGE_KEY + '.reminders') reminderController.sync(); });

function syncNativeTimetable() {
  if (!nativeBridge) return;
  try { nativeBridge.postMessage({ type: 'timetable:sync', data, plan: createReminderPlan(data) }); }
  catch (error) { toast('课表已保存在程序中，但本机提醒同步失败：' + error.message); }
}
if (nativeBridge) {
  $('#native-reminder-section').hidden = false;
  $('#standalone-reminder-section').hidden = true;
  $('#browser-reminder-section').hidden = true;
  $('.local-indicator').innerHTML = '<span></span>' + (androidPlatform ? '数据自动保存在此手机' : '数据保存在程序目录');
  $('.help-content>p').textContent = androidPlatform
    ? '这是你的离线手机课表。一屏显示一周七天，双击空白格添加课程，点击已有课程可以编辑。在“更多”中切换教学周、导入导出和设置提醒。课表自动保存在此手机。'
    : '桌面课程表支持离线使用。课程自动保存到程序旁的“课间数据”文件夹，可导入、导出 JSON；开启“桌面提醒”后可接收上下课通知。移动或备份时请带上整个程序文件夹。';
  if (!androidPlatform) $$('.help-content section p')[2].textContent = '课表、提醒、课程工具、扩展和应用浏览器配置都在程序旁的“课间数据”中。请整体移动或备份，单独复制 EXE 不包含后续编辑的数据。也可导出 JSON，在其他设备中导入恢复。';
  let nativeReady = false;
  let nativeError = '';
  nativeBridge.addEventListener('message', event => {
    const message = event.data;
    if (message?.type === 'desktop:state') {
      nativeReady = message.ready;
      $('#native-reminders').checked = message.enabled;
      $('#native-reminders').disabled = !nativeReady;
      $('#native-reminder-status').textContent = nativeError || (androidPlatform && message.message) || (nativeReady ? message.enabled ? '已开启：每节上课、下课各提醒一次，课程和作息修改后自动同步。' : '已关闭。打开开关即可接收 Windows 桌面通知。' : '正在同步课表…');
    } else if (message?.type === 'course-tools:result') {
      toolsPending = false;
      renderCourseTools();
      $('#tools-status').textContent = message.message;
      $('#tools-status').classList.toggle('tools-error', message.status === 'error');
    } else if (message?.type === 'backup:result') {
      const label = message.kind === 'guide' ? '格式说明' : message.kind === 'example' ? '示例文件' : '备份';
      if (message.status === 'saved') toast(message.kind === 'guide' ? '格式说明已保存，请与自己的课表一起交给 AI。' : message.kind === 'example' ? '示例文件已保存，请替换为自己的课程内容后再导入。' : '备份已保存。恢复时请点击左侧“导入课表”，无需双击 JSON 文件。');
      else if (message.status === 'cancelled') toast('已取消导出，未保存' + label + '。');
      else toast(label + '未保存：' + message.message);
    } else if (message?.type === 'qr:image-result') {
      tellQr({ type: 'image:result', status: message.status, message: message.message });
    } else if (message?.type === 'qr:open-result') {
      if (courseOpenPending) {
        setCourseOpenPending(false);
        toast(message.status === 'opened' ? '已按课程 classid 和当前时间打开微信 UA 签到页。' : '签到页未打开：' + message.message);
      }
      tellQr({ type: 'open:result', status: message.status, message: message.message });
    } else if (message?.type === 'desktop:error') {
      nativeError = message.message;
      $('#native-reminder-status').textContent = nativeError;
      toast(nativeError);
    }
  });
  $('#native-reminders').addEventListener('change', event => {
    nativeError = '';
    event.target.disabled = true;
    nativeBridge.postMessage({ type: 'reminders:setEnabled', enabled: event.target.checked });
  });
  $('#test-native-reminder').addEventListener('click', () => nativeBridge.postMessage({ type: 'reminders:test' }));
  nativeBridge.postMessage({ type: 'app:ready' });
  syncNativeTimetable();
}
