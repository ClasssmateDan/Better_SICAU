(() => {
  'use strict';
  const listeners = new Set();
  const requests = new Map();
  let sequence = 0;
  window.__KEJIAN_NATIVE_BRIDGE__ = {
    postMessage: message => window.KeJianAndroid.postMessage(JSON.stringify(message)),
    addEventListener: (type, listener) => { if (type === 'message') listeners.add(listener); },
  };
  window.__kejianNativeReceive = message => {
    if (message.type === 'clipboard:result' && requests.has(message.requestId)) {
      const pending = requests.get(message.requestId);
      requests.delete(message.requestId); clearTimeout(pending.timer);
      if (message.ok) pending.resolve(); else pending.reject(new Error(message.message || '复制失败'));
    }
    for (const listener of listeners) listener({ data: message });
  };
  const copy = text => new Promise((resolve, reject) => {
    const requestId = ++sequence;
    const timer = setTimeout(() => { requests.delete(requestId); reject(new Error('复制超时')); }, 5000);
    requests.set(requestId, { resolve, reject, timer });
    window.__KEJIAN_NATIVE_BRIDGE__.postMessage({ type: 'clipboard:copy', text: String(text), requestId });
  });
  try { Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText: copy } }); } catch { /* Selection fallback remains available. */ }
  document.addEventListener('DOMContentLoaded', () => {
    document.body.classList.add('android-platform');
    const $ = id => document.getElementById(id);
    const nav = document.createElement('nav');
    nav.className = 'mobile-bottom'; nav.setAttribute('aria-label', '主导航');
    for (const [view, title, iconName] of [['timetable','课表','grid'],['courses','课程','book'],['qr','二维码','qr'],['more','更多','settings']]) {
      const button = document.createElement('button');
      button.type = 'button'; button.className = 'mobile-tab'; button.dataset.mobileView = view;
      const original = document.querySelector(`[data-view="${view}"] [data-icon="${iconName}"]`) || document.querySelector(`[data-icon="${iconName}"]`);
      button.innerHTML = (original?.innerHTML || '') + '<span>' + title + '</span>';
      button.addEventListener('click', () => {
        if (view === 'more') $('mobile-more').showModal();
        else { location.hash = view === 'timetable' ? 'timetable' : view; window.scrollTo({ top: 0 }); }
      });
      nav.append(button);
    }
    document.body.append(nav);
    const more = document.createElement('dialog'); more.id = 'mobile-more'; more.className = 'modal';
    more.innerHTML = '<div class="modal-header"><h2>更多</h2><button type="button" class="button button-white" id="mobile-more-close">关闭</button></div><div class="modal-body mobile-menu"></div>';
    const menu = more.lastChild;
    const weekSettings = document.createElement('section'); weekSettings.className = 'mobile-week-settings';
    weekSettings.innerHTML = '<h3>查看周次</h3>';
    weekSettings.append(document.querySelector('.week-controls'));
    menu.append(weekSettings);
    for (const [target, title] of [['add-course','添加课程'],['import-button','导入课表'],['export-button','导出课表'],['settings-button','学期设置'],['reminders-button','上下课提醒'],['help-button','使用指南']]) {
      const button = document.createElement('button'); button.type = 'button'; button.className = 'button button-white'; button.textContent = title;
      button.addEventListener('click', () => { more.close(); $(target).click(); });
      menu.append(button);
    }
    document.body.append(more);
    $('mobile-more-close').onclick = () => more.close();
    function markView() {
      const view = ({ '#courses': 'courses', '#qr': 'qr' })[location.hash] || 'timetable';
      nav.querySelectorAll('button').forEach(button => {
        const active = button.dataset.mobileView === view;
        button.classList.toggle('active', active); button.setAttribute('aria-current', active ? 'page' : 'false');
      });
    }
    markView(); window.addEventListener('hashchange', markView);
    const native = $('native-reminder-section');
    native.querySelector('strong').textContent = '上课／下课通知';
    native.querySelector('p:last-child').textContent = '提醒由手机系统安排，关闭页面后仍可运行。请允许通知；若需要准时提醒，可在下方允许“闹钟和提醒”。强行停止应用、关机或系统省电限制可能中断提醒，重新打开课间会恢复安排。';
    const exact = document.createElement('button'); exact.type = 'button'; exact.id = 'android-exact-reminders'; exact.className = 'button button-white'; exact.textContent = '允许准时提醒';
    exact.onclick = () => window.__KEJIAN_NATIVE_BRIDGE__.postMessage({ type: 'reminders:settings' }); native.append(exact);
    $('reminder-title').textContent = '上下课提醒';
    document.querySelector('.help-content>div:nth-last-of-type(1) p').textContent = '课表保存在此手机。换手机、卸载或清除应用数据之前，请先导出课表 JSON；在新设备中通过“导入课表”恢复。';
    document.querySelectorAll('.help-content section p')[1].textContent = '一周七天按屏幕宽度显示。双击同一空白节次可添加课程，单击已有课程可编辑；滑动和单击空白格不会打开添加窗口。周次切换和添加课程也可在“更多”中操作。';

    // A double tap must land twice on the same cell without a scroll, drag or long press.
    const calendar = $('calendar');
    let pressed = null, previousTap = null;
    const cancelTap = () => { pressed = null; previousTap = null; };
    document.addEventListener('pointerdown', event => {
      const slot = event.target.closest('.slot');
      if (!event.isPrimary || event.button !== 0 || !slot || !calendar.contains(slot) || document.querySelector('dialog[open]')) { cancelTap(); return; }
      pressed = { slot, id: event.pointerId, x: event.clientX, y: event.clientY, at: performance.now() };
    }, { passive: true });
    document.addEventListener('pointermove', event => {
      if (pressed && event.pointerId === pressed.id && Math.hypot(event.clientX - pressed.x, event.clientY - pressed.y) > 8) cancelTap();
    }, { passive: true });
    document.addEventListener('pointerup', event => {
      const current = pressed; pressed = null;
      if (!current || event.pointerId !== current.id || event.target.closest('.slot') !== current.slot ||
          Math.hypot(event.clientX - current.x, event.clientY - current.y) > 8 || performance.now() - current.at > 350) { previousTap = null; return; }
      const at = performance.now();
      if (previousTap?.slot === current.slot && at - previousTap.at <= 400 &&
          Math.hypot(event.clientX - previousTap.x, event.clientY - previousTap.y) <= 24) {
        previousTap = null;
        current.slot.dispatchEvent(new CustomEvent('kejian:slot-doubletap', { bubbles: true }));
      } else previousTap = { slot: current.slot, at, x: event.clientX, y: event.clientY };
    }, { passive: true });
    document.addEventListener('pointercancel', cancelTap, { passive: true });
    document.addEventListener('scroll', cancelTap, { capture: true, passive: true });
    window.addEventListener('hashchange', cancelTap);
    // QR iframe shares only packaged code. Give its copy action the same native clipboard helper.
    $('qr-frame').addEventListener('load', () => {
      try { Object.defineProperty($('qr-frame').contentWindow.navigator, 'clipboard', { configurable: true, value: { writeText: copy } }); } catch { /* User can select the displayed link. */ }
    });
    window.__kejianBack = () => {
      const dialogs = [...document.querySelectorAll('dialog[open]')];
      if (dialogs.length) { dialogs.at(-1).close(); return true; }
      if (location.hash && location.hash !== '#timetable') { location.hash = 'timetable'; return true; }
      return false;
    };
  });
})();
