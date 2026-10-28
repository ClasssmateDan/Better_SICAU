(function () {
  'use strict';
  const $ = (id) => document.getElementById(id);
  const state = { source: null, current: null, qr: null, mode: null, courseId: null, courses: [], running: false, active: true, native: false, opening: false, interval: 1000, lastRefresh: 0, importToken: 0, busy: false, toastTimer: null };
  const embedded = window.parent !== window;
  function tellParent(message) { if (embedded) window.parent.postMessage({ scope: 'kejian:qr', ...message }, '*'); }
  window.addEventListener('message', event => {
    if (!embedded || event.source !== window.parent || event.data?.scope !== 'kejian:qr') return;
    const message = event.data;
    if (message.type === 'active') {
      const resumed = !state.active && message.active;
      state.active = !!message.active;
      if (typeof message.native === 'boolean') state.native = message.native;
      if (resumed && state.source && state.running) regenerate();
      syncControls();
    } else if (message.type === 'courses') {
      setCourses(message.courses);
    } else if (message.type === 'selectCourse') {
      const course = state.courses.find(item => item.id === message.courseId);
      if (course) { selectTab('course'); useCourse(course); }
    } else if (message.type === 'image:result') {
      notify(message.status === 'saved' ? '二维码图片已保存' : message.status === 'cancelled' ? '已取消保存二维码' : '图片未保存：' + message.message);
    } else if (message.type === 'open:result') {
      state.opening = false;
      syncControls();
      notify(message.status === 'opened' ? '已打开微信 UA 窗口，网页正在载入' : '网页未打开：' + message.message);
    }
  });
  const beijingParts = new Intl.DateTimeFormat('en-GB', { timeZone: 'Asia/Shanghai', hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23' });
  const dateFormatter = new Intl.DateTimeFormat('zh-CN', { timeZone: 'Asia/Shanghai', year: 'numeric', month: 'long', day: 'numeric', weekday: 'long' });

  function notify(message) {
    if (embedded) { tellParent({ type: 'notify', message }); return; }
    clearTimeout(state.toastTimer);
    $('toast').textContent = message;
    $('toast').hidden = false;
    state.toastTimer = setTimeout(() => { $('toast').hidden = true; }, 3200);
  }
  function showMessage(message, error = false) {
    $('import-message').textContent = message;
    $('import-message').classList.toggle('error', error);
    $('import-message').hidden = !message;
  }
  function selectTab(name, focus = false) {
    for (const tab of ['course', 'upload', 'link']) {
      const active = name === tab;
      $('tab-' + tab).setAttribute('aria-selected', String(active));
      $('tab-' + tab).tabIndex = active ? 0 : -1;
      $('panel-' + tab).hidden = !active;
    }
    if (focus) $('tab-' + name).focus();
  }
  const tabs = ['course', 'upload', 'link'];
  for (const name of tabs) {
    $('tab-' + name).addEventListener('click', () => selectTab(name));
    $('tab-' + name).addEventListener('keydown', (event) => {
      if (['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) {
        event.preventDefault();
        const index = event.key === 'Home' ? 0 : event.key === 'End' ? tabs.length - 1 : (tabs.indexOf(name) + (event.key === 'ArrowLeft' ? tabs.length - 1 : 1)) % tabs.length;
        selectTab(tabs[index], true);
      }
    });
  }

  function setBusy(busy) {
    state.busy = busy;
    $('drop-zone').classList.toggle('busy', busy);
    $('drop-zone').setAttribute('aria-busy', String(busy));
    $('drop-title').textContent = busy ? '正在识别二维码…' : '点击上传，或拖入二维码照片';
    $('parse-button').disabled = busy;
  }

  function drawCode(qr) {
    const canvas = $('qr-canvas');
    const count = qr.getModuleCount();
    const quiet = 4;
    const cell = Math.max(1, Math.floor(900 / (count + quiet * 2)));
    const size = (count + quiet * 2) * cell;
    canvas.width = size;
    canvas.height = size;
    const context = canvas.getContext('2d');
    context.fillStyle = '#ffffff';
    context.fillRect(0, 0, size, size);
    context.fillStyle = '#000000';
    for (let row = 0; row < count; row++) {
      for (let col = 0; col < count; col++) {
        if (qr.isDark(row, col)) context.fillRect((col + quiet) * cell, (row + quiet) * cell, cell, cell);
      }
    }
  }

  function regenerate() {
    if (!state.source) return;
    const now = Date.now();
    try {
      const current = QrCore.generateQr(state.source, now);
      const qr = qrcode(0, 'M');
      qr.addData(current.url, 'Byte');
      qr.make();
      drawCode(qr);
      state.current = current;
      state.qr = qr;
      state.lastRefresh = performance.now();
      $('qr-canvas').hidden = false;
      $('empty-state').hidden = true;
      $('generated-caption').textContent = '本次生成时间 · 北京时间';
      $('generated-time').replaceChildren(document.createTextNode(beijingParts.format(new Date(now))));
      const ms = document.createElement('span');
      ms.textContent = '.' + String(now % 1000).padStart(3, '0');
      $('generated-time').appendChild(ms);
      $('generated-time').dateTime = new Date(now).toISOString();
      $('generated-id').textContent = 'CLASSID ' + current.classId;
      $('generated-url').value = current.url;
      $('payload-value').textContent = current.payload;
      if (state.mode === 'course') {
        $('source-timestamp').textContent = String(current.timestampMs);
        $('source-time').textContent = QrCore.formatBeijingTime(now);
      }
    } catch (error) {
      state.running = false;
      syncControls();
      showMessage(error.message || '生成失败，请重新导入二维码。', true);
    }
  }

  function syncControls() {
    const ready = !!state.source;
    for (const id of ['pause-button', 'refresh-button', 'copy-button', 'download-button']) $(id).disabled = !ready;
    $('open-button').disabled = !ready || !state.native || state.opening;
    $('open-button-text').textContent = state.opening ? '正在打开…' : '以微信 UA 打开当前二维码';
    if (state.native) $('open-help').textContent = '点击后联网打开当前链接，首次请求前即设置 UA 为 MicroMessenger。窗口内可能仍需登录或微信授权。';
    $('live-status').className = 'status-badge' + (!ready ? ' idle' : !state.running ? ' paused' : '');
    $('live-status-text').textContent = !ready ? '等待导入' : state.running ? '实时更新' : '已暂停';
    $('pause-text').textContent = state.running ? '暂停' : '继续';
    $('pause-icon').setAttribute('href', state.running ? '#i-pause' : '#i-play');
    $('pause-button').setAttribute('aria-label', state.running ? '暂停自动刷新' : '继续自动刷新');
    $('paused-overlay').hidden = !ready || state.running;
    $('refresh-caption').replaceChildren();
    const dot = document.createElement('span');
    dot.className = 'tiny-dot';
    $('refresh-caption').append(dot, document.createTextNode(!ready ? '准备就绪' : state.running ? '每 ' + (state.interval / 1000) + ' 秒刷新' : '已保留当前二维码'));
    if (!state.running) $('refresh-progress').style.width = '0%';
  }

  function acceptContent(raw, name) {
    const source = QrCore.parseQrContent(raw);
    state.source = source;
    state.mode = 'import';
    state.courseId = null;
    state.running = true;
    $('source-info').hidden = false;
    $('class-id').textContent = source.classId;
    $('source-timestamp').textContent = String(source.timestampMs);
    $('source-name').textContent = name;
    $('source-name').title = name;
    $('source-time').textContent = QrCore.formatBeijingTime(source.timestampMs - QrCore.SOURCE_TIME_OFFSET_MS);
    $('timestamp-label').textContent = '原始时间值';
    $('source-status-text').textContent = '已识别';
    $('source-time-label').textContent = '原码北京时间';
    $('source-input').value = raw;
    showMessage('已解码课程编号和原始时间值，正在按当前时间自动刷新。');
    syncControls();
    regenerate();
  }

  function acceptClassid(value, name, courseId = null) {
    const classid = value.trim();
    const source = QrCore.generateQr({ classId: classid });
    ++state.importToken;
    setBusy(false);
    state.source = source;
    state.mode = 'course';
    state.courseId = courseId;
    state.running = true;
    $('source-info').hidden = false;
    $('class-id').textContent = classid;
    $('source-name').textContent = name;
    $('source-name').title = name;
    $('timestamp-label').textContent = '当前时间值';
    $('source-status-text').textContent = '已生成';
    $('source-time-label').textContent = '生成北京时间';
    showMessage('已按 classid 和当前时间生成，无需原二维码。');
    syncControls();
    regenerate();
  }

  function useCourse(course) {
    $('course-select').value = course.id;
    $('classid-input').value = course.classid;
    acceptClassid(course.classid, course.name, course.id);
  }

  function setCourses(input) {
    const selected = state.courseId;
    state.courses = Array.isArray(input) ? input.filter(item => item && typeof item.id === 'string' && typeof item.name === 'string' && typeof item.classid === 'string' && /^[0-9]{1,32}$/.test(item.classid)).slice(0, 500) : [];
    const select = $('course-select');
    const placeholder = document.createElement('option');
    placeholder.value = '';
    placeholder.textContent = state.courses.length ? '选择课程，即可自动生成' : '暂无已填写 classid 的课程';
    select.replaceChildren(placeholder);
    for (const course of state.courses) {
      const option = document.createElement('option');
      option.value = course.id;
      option.textContent = course.name + ' · ' + course.classid;
      select.append(option);
    }
    select.disabled = !state.courses.length;
    select.value = selected || '';
    if (state.mode === 'course' && selected) {
      const course = state.courses.find(item => item.id === selected);
      if (!course) { clearCurrent(); showMessage('该课程的 classid 已移除，请重新选择课程或手动填写。'); }
      else if (course.classid !== state.source.classId) useCourse(course);
      else { $('source-name').textContent = course.name; $('source-name').title = course.name; }
    }
  }

  $('course-select').addEventListener('change', () => {
    const course = state.courses.find(item => item.id === $('course-select').value);
    if (course) useCourse(course);
    else if (state.mode === 'course') clearCurrent();
  });
  $('classid-input').addEventListener('input', () => {
    $('course-select').value = '';
    if (state.mode === 'course' && $('classid-input').value.trim() !== state.source.classId) clearCurrent(false);
  });
  $('generate-classid-button').addEventListener('click', () => {
    try {
      const course = state.courses.find(item => item.id === $('course-select').value && item.classid === $('classid-input').value.trim());
      if (course) useCourse(course);
      else acceptClassid($('classid-input').value, '手动填写 classid');
    } catch (error) { showMessage(error.message || '请填写有效的 classid。', true); }
  });
  $('classid-input').addEventListener('keydown', event => { if (event.key === 'Enter') { event.preventDefault(); $('generate-classid-button').click(); } });

  async function importFile(file) {
    if (!file) return;
    const token = ++state.importToken;
    setBusy(true);
    showMessage('正在读取图片，请稍候…');
    try {
      const raw = await QrImage.read(file);
      if (token !== state.importToken) return;
      acceptContent(raw, file.name || '粘贴的图片');
    } catch (error) {
      if (token !== state.importToken) return;
      showMessage(error.message || '未能识别二维码，请尝试更清晰的图片。', true);
    } finally {
      if (token === state.importToken) setBusy(false);
      $('file-input').value = '';
    }
  }

  $('drop-zone').addEventListener('click', () => { if (!state.busy) $('file-input').click(); });
  $('file-input').addEventListener('change', (event) => importFile(event.target.files[0]));
  for (const name of ['dragenter', 'dragover']) $('drop-zone').addEventListener(name, (event) => { event.preventDefault(); $('drop-zone').classList.add('dragover'); });
  $('drop-zone').addEventListener('dragleave', () => $('drop-zone').classList.remove('dragover'));
  $('drop-zone').addEventListener('drop', (event) => { event.preventDefault(); $('drop-zone').classList.remove('dragover'); importFile(event.dataTransfer.files[0]); });
  document.addEventListener('dragover', (event) => { if (event.dataTransfer.types.includes('Files')) event.preventDefault(); });
  document.addEventListener('drop', (event) => { if (event.dataTransfer.types.includes('Files')) event.preventDefault(); });
  document.addEventListener('paste', (event) => {
    const fileItem = Array.from(event.clipboardData?.items || []).find((item) => item.kind === 'file' && item.type.startsWith('image/'));
    if (fileItem) { event.preventDefault(); selectTab('upload'); importFile(fileItem.getAsFile()); }
  });
  $('parse-button').addEventListener('click', () => {
    ++state.importToken;
    try { acceptContent($('source-input').value, '粘贴的二维码内容'); }
    catch (error) { showMessage(error.message || '内容无法解析，请检查原二维码链接。', true); }
  });
  $('source-input').addEventListener('keydown', (event) => { if (event.key === 'Enter' && (event.ctrlKey || event.metaKey)) $('parse-button').click(); });
  function clearCurrent(clearInputs = true) {
    ++state.importToken;
    state.source = null;
    state.current = null;
    state.qr = null;
    state.mode = null;
    state.courseId = null;
    state.running = false;
    setBusy(false);
    $('source-info').hidden = true;
    for (const id of ['class-id', 'source-timestamp', 'source-time', 'source-name']) $(id).textContent = '';
    $('source-name').title = '';
    if (clearInputs) { $('course-select').value = ''; $('classid-input').value = ''; }
    $('qr-canvas').hidden = true;
    $('empty-state').hidden = false;
    $('source-input').value = '';
    $('generated-url').value = '';
    $('payload-value').textContent = '—';
    $('generated-caption').textContent = '导入后自动开始';
    $('generated-time').textContent = '--:--:--.---';
    $('generated-time').removeAttribute('datetime');
    $('generated-id').textContent = '二维码将持续跟随当前时间更新';
    showMessage('');
    syncControls();
  }
  $('clear-button').addEventListener('click', () => clearCurrent());
  document.querySelectorAll('[data-interval]').forEach((button) => button.addEventListener('click', () => {
    state.interval = Number(button.dataset.interval);
    document.querySelectorAll('[data-interval]').forEach((item) => item.setAttribute('aria-pressed', String(item === button)));
    if (state.source && state.running) regenerate();
    syncControls();
  }));
  $('pause-button').addEventListener('click', () => {
    state.running = !state.running;
    if (state.running) regenerate();
    syncControls();
  });
  $('refresh-button').addEventListener('click', () => { regenerate(); notify('已按当前时间刷新'); });

  async function copyCurrent() {
    const url = state.current?.url;
    if (!url) return;
    try {
      await navigator.clipboard.writeText(url);
      notify('当前链接已复制');
    } catch (_) {
      const input = document.createElement('textarea');
      input.value = url;
      input.style.cssText = 'position:fixed;left:-10000px;top:0';
      document.body.appendChild(input);
      input.focus();
      input.select();
      let copied = false;
      try { copied = document.execCommand('copy'); } catch (_) { /* Show the link if clipboard permission is unavailable. */ }
      input.remove();
      if (copied) notify('当前链接已复制');
      else { $('payload-details').open = true; $('generated-url').focus(); $('generated-url').select(); notify('浏览器未允许复制，请手动复制已选中的链接'); }
    }
  }
  $('copy-button').addEventListener('click', copyCurrent);
  $('open-button').addEventListener('click', () => {
    if (!state.current || !state.native || state.opening) return;
    if (state.running) regenerate();
    state.opening = true;
    syncControls();
    tellParent({ type: 'open', url: state.current.url });
  });
  $('download-button').addEventListener('click', () => {
    if (!state.current) return;
    const name = 'checkin-' + state.current.classId + '-' + state.current.timestampMs + '.png';
    if (embedded && state.native) {
      tellParent({ type: 'image:save', filename: name, dataUrl: $('qr-canvas').toDataURL('image/png') });
      return;
    }
    const a = document.createElement('a');
    a.href = $('qr-canvas').toDataURL('image/png');
    a.download = name;
    document.body.appendChild(a);
    a.click();
    a.remove();
    notify('已下载当前二维码');
  });

  function tick() {
    const now = Date.now();
    $('wall-clock').textContent = beijingParts.format(new Date(now));
    $('wall-clock').dateTime = new Date(now).toISOString();
    $('wall-date').textContent = dateFormatter.format(new Date(now));
    if (state.source && state.running && state.active && !document.hidden) {
      if (performance.now() - state.lastRefresh >= state.interval) regenerate();
      const fraction = Math.max(0, Math.min(1, (performance.now() - state.lastRefresh) / state.interval));
      $('refresh-progress').style.width = (fraction * 100).toFixed(1) + '%';
    }
  }
  document.addEventListener('visibilitychange', () => { if (!document.hidden && state.active && state.source && state.running) regenerate(); });
  window.addEventListener('pageshow', () => { if (state.source && state.running) regenerate(); });
  syncControls();
  setCourses([]);
  tick();
  setInterval(tick, 100);
  if (embedded) {
    let previousHeight = 0;
    const reportHeight = () => {
      const height = Math.ceil(document.body.getBoundingClientRect().height) + 2;
      if (height !== previousHeight) { previousHeight = height; tellParent({ type: 'resize', height }); }
    };
    new ResizeObserver(reportHeight).observe(document.body);
    window.addEventListener('resize', reportHeight);
    reportHeight();
    tellParent({ type: 'ready' });
  }
})();
