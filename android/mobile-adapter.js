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
    for (const [view, title, iconName] of [['timetable','课表','grid'],['courses','课程','book'],['planner','日历','calendar'],['qr','二维码','qr'],['learningpass','学习通',''],['more','设置','settings']]) {
      const button = document.createElement('button');
      button.type = 'button'; button.className = 'mobile-tab'; button.dataset.mobileView = view;
      const original = document.querySelector(`[data-view="${view}"] [data-icon="${iconName}"]`) || document.querySelector(`[data-icon="${iconName}"]`);
      const learningPassIcon = '<svg viewBox="0 0 24 24" aria-hidden="true"><rect x="5" y="5" width="14" height="16" rx="2"/><path d="M9 5V3h6v2m-6 8 2 2 4-5"/></svg>';
      button.innerHTML = (view === 'learningpass' ? learningPassIcon : original?.innerHTML || '') + '<span>' + title + '</span>';
      if (view === 'learningpass') { button.id = 'mobile-learningpass'; button.setAttribute('aria-label', '学习通签到'); }
      button.addEventListener('click', () => {
        if (view === 'more') $('mobile-more').showModal();
        else if (view === 'learningpass') window.__KEJIAN_NATIVE_BRIDGE__.postMessage({ type: 'learningpass:open' });
        else { location.hash = view === 'timetable' ? 'timetable' : view; window.scrollTo({ top: 0 }); }
      });
      nav.append(button);
    }
    document.body.append(nav);
    const more = document.createElement('dialog'); more.id = 'mobile-more'; more.className = 'modal';
    more.innerHTML = '<div class="modal-header"><h2>设置</h2><button type="button" class="button button-white" id="mobile-more-close">关闭</button></div><div class="modal-body mobile-menu"></div>';
    const menu = more.lastChild;
    for (const [target, title] of [['add-course','添加课程'],['import-button','导入课表'],['export-button','导出课表'],['settings-button','学期设置'],['reminders-button','提醒设置'],['help-button','使用指南']]) {
      const button = document.createElement('button'); button.type = 'button'; button.className = 'button button-white'; button.textContent = title;
      button.addEventListener('click', () => { more.close(); $(target).click(); });
      menu.append(button);
    }
    const version = document.createElement('p'); version.id = 'mobile-app-version'; version.className = 'mobile-app-version';
    version.textContent = '版本 ' + window.__KEJIAN_VERSION__; menu.append(version);
    document.body.append(more);
    $('mobile-more-close').onclick = () => more.close();
    const courseActions = document.querySelector('#course-dialog .course-footer-actions');
    const actionButtons = [...courseActions.querySelectorAll('button')];
    const layoutCourseActions = () => {
      const count = actionButtons.filter(button => !button.hidden).length;
      courseActions.hidden = count === 0;
      courseActions.classList.toggle('single-action', count === 1);
    };
    const courseActionObserver = new MutationObserver(layoutCourseActions);
    actionButtons.forEach(button => courseActionObserver.observe(button, { attributes: true, attributeFilter: ['hidden'] }));
    layoutCourseActions();
    const weekends = $('show-weekends');
    document.querySelector('.week-controls').append(document.querySelector('.weekend-control'));
    try { weekends.checked = localStorage.getItem('kejian.mobile.weekends.v1') !== 'false'; } catch { /* The current choice remains usable without storage. */ }
    weekends.dispatchEvent(new Event('change'));
    weekends.addEventListener('change', () => { try { localStorage.setItem('kejian.mobile.weekends.v1', String(weekends.checked)); } catch { /* The current choice remains usable without storage. */ } });
    function markView() {
      const view = ({ '#courses': 'courses', '#qr': 'qr', '#planner': 'planner' })[location.hash] || 'timetable';
      nav.querySelectorAll('button').forEach(button => {
        const active = button.dataset.mobileView === view;
        button.classList.toggle('active', active); button.setAttribute('aria-current', active ? 'page' : 'false');
      });
    }
    markView(); window.addEventListener('hashchange', markView);
    const native = $('native-reminder-section');
    native.querySelector('strong').textContent = '上下课与备忘录通知';
    native.querySelector('p:last-child').textContent = '提醒由手机系统安排，关闭页面后仍可运行。请允许通知；若需要准时提醒，可在下方允许“闹钟和提醒”。强行停止应用、关机或系统省电限制可能中断提醒，重新打开课间会恢复安排。';
    const exact = document.createElement('button'); exact.type = 'button'; exact.id = 'android-exact-reminders'; exact.className = 'button button-white'; exact.textContent = '允许准时提醒';
    exact.onclick = () => window.__KEJIAN_NATIVE_BRIDGE__.postMessage({ type: 'reminders:settings' }); native.append(exact);
    $('reminder-title').textContent = '提醒设置';
    document.querySelector('.help-content>div:nth-last-of-type(1) p').textContent = '课表保存在此手机。换手机、卸载或清除应用数据之前，请先导出课表 JSON；在新设备中通过“导入课表”恢复。';
    document.querySelectorAll('.help-content section p')[1].textContent = '课表左滑切换下一周，右滑切换上一周，也可使用顶部周次控件和周末开关。双击同一空白节次可添加课程，单击已有课程可编辑；上下滑动不会切周。添加课程、导入导出和版本信息位于“设置”。';

    // A double tap must land twice on the same cell without a scroll, drag or long press.
    const calendar = $('calendar');
    const calendarArea = calendar.parentElement;
    let pressed = null, previousTap = null, swipe = null, ignoreClickUntil = 0;
    let dragOffset = 0, weekMotion = null, switchingWeek = false, adjacentPreview = null;
    const reducedMotion = () => window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    const position = value => `translate3d(${value}px,0,0)`;
    const clearAdjacentPreview = () => {
      adjacentPreview?.element.remove();
      adjacentPreview = null;
    };
    const prepareAdjacentPreview = direction => {
      const button = $(direction < 0 ? 'next-week' : 'prev-week');
      if (button.disabled) { clearAdjacentPreview(); return null; }
      const week = Number($('week-select').value) - direction;
      if (adjacentPreview?.week === week && adjacentPreview.direction === direction) return adjacentPreview;
      clearAdjacentPreview();
      const request = { week, rendered: null };
      calendar.dispatchEvent(new CustomEvent('kejian:week-preview', { detail: request }));
      if (!request.rendered) return null;
      const bounds = calendar.getBoundingClientRect();
      const element = calendar.cloneNode(false);
      element.removeAttribute('id');
      element.classList.remove('week-dragging', 'week-transitioning');
      element.classList.add('week-transition-snapshot', 'week-adjacent-preview');
      element.classList.toggle('hide-weekends', request.rendered.dayCount === 5);
      element.dataset.previewWeek = String(week);
      element.setAttribute('aria-hidden', 'true'); element.setAttribute('inert', '');
      element.innerHTML = request.rendered.html;
      element.querySelectorAll('button').forEach(button => { button.tabIndex = -1; });
      element.style.setProperty('--day-count', request.rendered.dayCount);
      element.style.setProperty('--period-count', request.rendered.periodCount);
      Object.assign(element.style, { width: `${bounds.width}px`, height: `${bounds.height}px`, transform: position(-direction * bounds.width), transition: 'none' });
      calendar.before(element);
      adjacentPreview = { element, week, direction, width: bounds.width };
      return adjacentPreview;
    };
    const resetDrag = () => {
      clearAdjacentPreview();
      dragOffset = 0;
      calendar.style.removeProperty('transform');
      calendar.style.removeProperty('transition');
      calendar.classList.remove('week-dragging');
    };
    const finishWeekMotion = () => {
      const motion = weekMotion; weekMotion = null;
      if (motion) {
        clearTimeout(motion.timer); cancelAnimationFrame(motion.frame);
        calendar.removeEventListener('transitionend', motion.done);
        motion.snapshot?.remove();
      }
      resetDrag();
      calendar.classList.remove('week-transitioning');
      calendarArea.removeAttribute('aria-busy');
    };
    const cancelTap = () => { pressed = null; previousTap = null; swipe = null; if (!weekMotion) resetDrag(); };
    const animateToRest = (snapshot, incoming, outgoing, duration) => {
      const motion = { snapshot, timer: null, frame: null, done: null };
      weekMotion = motion;
      calendarArea.setAttribute('aria-busy', 'true');
      calendar.classList.add('week-transitioning');
      calendar.classList.remove('week-dragging');
      calendar.style.transform = position(incoming);
      // Commit the starting position before applying the CSS transition.
      calendar.getBoundingClientRect();
      const transition = `transform ${duration}ms cubic-bezier(.22,.61,.36,1)`;
      calendar.style.transition = transition;
      if (snapshot) snapshot.style.transition = transition;
      motion.done = event => {
        if (event.target === calendar && event.propertyName === 'transform' && weekMotion === motion) finishWeekMotion();
      };
      calendar.addEventListener('transitionend', motion.done);
      motion.frame = requestAnimationFrame(() => {
        if (weekMotion !== motion) return;
        calendar.style.transform = position(0);
        if (snapshot) snapshot.style.transform = position(outgoing);
      });
      motion.timer = setTimeout(() => { if (weekMotion === motion) finishWeekMotion(); }, duration + 100);
    };
    const springBack = () => {
      if (!dragOffset || reducedMotion()) { resetDrag(); return; }
      const preview = adjacentPreview; adjacentPreview = null;
      animateToRest(preview?.element ?? null, dragOffset, preview ? -preview.direction * preview.width : 0, 170);
    };
    const changeWeekWithMotion = direction => {
      const button = $(direction < 0 ? 'next-week' : 'prev-week');
      if (button.disabled) { springBack(); return; }
      if (reducedMotion()) { resetDrag(); button.click(); return; }
      const bounds = calendar.getBoundingClientRect(), start = dragOffset;
      const snapshot = calendar.cloneNode(true);
      snapshot.removeAttribute('id');
      snapshot.classList.remove('week-dragging', 'week-transitioning');
      snapshot.classList.add('week-transition-snapshot');
      snapshot.setAttribute('aria-hidden', 'true'); snapshot.setAttribute('inert', '');
      snapshot.querySelectorAll('button').forEach(button => { button.tabIndex = -1; });
      Object.assign(snapshot.style, { width: `${bounds.width}px`, height: `${bounds.height}px`, transform: position(start), transition: 'none' });
      clearAdjacentPreview();
      calendar.before(snapshot);
      switchingWeek = true;
      try { button.click(); } finally { switchingWeek = false; }
      animateToRest(snapshot, start - direction * bounds.width, direction * bounds.width, 220);
    };
    const stopMotionForControl = () => {
      if (!switchingWeek) { cancelTap(); finishWeekMotion(); }
    };
    document.querySelector('.week-controls').addEventListener('click', stopMotionForControl, true);
    document.querySelector('.week-controls').addEventListener('change', stopMotionForControl, true);
    document.addEventListener('click', event => {
      if ((weekMotion && calendarArea.contains(event.target)) || (performance.now() < ignoreClickUntil && calendar.contains(event.target))) {
        ignoreClickUntil = 0; event.preventDefault(); event.stopImmediatePropagation();
      }
    }, true);
    document.addEventListener('pointerdown', event => {
      const slot = event.target.closest('.slot');
      if (!event.isPrimary || event.button !== 0 || !calendar.contains(event.target) || document.querySelector('dialog[open]') || weekMotion) { cancelTap(); return; }
      ignoreClickUntil = 0;
      swipe = { id: event.pointerId, x: event.clientX, y: event.clientY, at: performance.now(), width: calendar.getBoundingClientRect().width, vertical: false, moved: false };
      pressed = slot ? { slot, id: event.pointerId, x: event.clientX, y: event.clientY, at: performance.now() } : null;
      if (!slot) previousTap = null;
    }, { passive: true });
    document.addEventListener('pointermove', event => {
      if (swipe && event.pointerId === swipe.id) {
        const dx = event.clientX - swipe.x, dy = Math.abs(event.clientY - swipe.y);
        if (dy > 12 && dy > Math.abs(dx) * 1.2) swipe.vertical = true;
        if (Math.hypot(dx, dy) > 8) { swipe.moved = true; pressed = null; previousTap = null; }
        if (!swipe.vertical && Math.abs(dx) > dy * 1.25 && Math.abs(dx) > 8) {
          const direction = dx < 0 ? -1 : 1;
          const blocked = $(direction < 0 ? 'next-week' : 'prev-week').disabled;
          if (blocked) clearAdjacentPreview();
          const preview = blocked ? null : prepareAdjacentPreview(direction);
          if (!blocked && !preview) { resetDrag(); return; }
          dragOffset = Math.max(-swipe.width * .9, Math.min(swipe.width * .9, dx)) * (blocked ? .22 : 1);
          calendar.classList.add('week-dragging');
          calendar.style.transform = position(dragOffset);
          if (preview) preview.element.style.transform = position(dragOffset - direction * preview.width);
        } else if (!weekMotion) resetDrag();
      }
    }, { passive: true });
    document.addEventListener('pointerup', event => {
      const gesture = swipe; swipe = null;
      if (gesture && event.pointerId === gesture.id) {
        const dx = event.clientX - gesture.x, dy = Math.abs(event.clientY - gesture.y);
        if (gesture.moved || Math.hypot(dx, dy) > 8) {
          pressed = null; previousTap = null; ignoreClickUntil = performance.now() + 350;
          const threshold = Math.max(48, Math.min(96, calendar.clientWidth * 0.18));
          if (!gesture.vertical && Math.abs(dx) >= threshold && dy <= Math.min(40, Math.abs(dx) * 0.45) && performance.now() - gesture.at <= 1000) {
            changeWeekWithMotion(dx < 0 ? -1 : 1);
          } else springBack();
          return;
        }
      }
      if (!weekMotion) resetDrag();
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
    const cancelWeekInteraction = () => { cancelTap(); finishWeekMotion(); };
    window.addEventListener('hashchange', cancelWeekInteraction);
    window.addEventListener('resize', cancelWeekInteraction);
    document.addEventListener('visibilitychange', () => { if (document.hidden) cancelWeekInteraction(); });
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
