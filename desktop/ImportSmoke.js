// Development checks using synthetic files and a workspace-only profile.
(() => {
  const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
  const check = (value, message) => { if (!value) throw new Error(message); };
  const $ = id => document.getElementById(id);
  const key = 'kejian.timetable.public.v1';
  async function until(predicate, message) {
    for (let attempt = 0; attempt < 100; attempt++) { if (predicate()) return; await delay(50); }
    throw new Error(message);
  }
  function choose(name, text) {
    const transfer = new DataTransfer();
    transfer.items.add(new File([text], name, { type: 'application/json' }));
    $('import-file').files = transfer.files;
    $('import-file').dispatchEvent(new Event('change', { bubbles: true }));
  }
  async function validPreview(text) {
    choose('我的课程表.json', text);
    await until(() => !$('import-preview').hidden && $('choose-import-file').getAttribute('aria-busy') === 'false', '有效文件没有进入预览');
    check($('import-preview-courses').children.length === 3, '预览课程数不正确');
    check(!$('apply-import').disabled, '校验后确认按钮不可用');
  }
  (async () => {
    const baseline = localStorage.getItem(key);
    $('import-button').click();
    check($('import-dialog').open, '导入界面没有打开');
    check($('choose-import-file').disabled && $('apply-import').disabled, '准备确认前不应允许选择或导入');
    check($('import-format-guide').textContent.includes('## meetings') && $('import-format-guide').textContent.includes('第一周'), '格式说明没有内嵌');
    let copied = '';
    const descriptor = Object.getOwnPropertyDescriptor(navigator, 'clipboard');
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText: async text => { copied = text; } } });
    $('copy-import-guide').click();
    await until(() => !!copied, '没有复制 AI 提示词');
    check(copied.includes('本人课表') && copied.includes('classid') && copied.includes('不要照抄示例'), '提示词缺少本人材料和格式要求');
    check(copied.includes('classid 的获取方式') && copied.includes('课表页面下方') && copied.includes('ClassTasks?classid=') && copied.includes('courseid'), '复制的说明缺少 classid 获取与核对方法');
    check($('classid-acquisition-guide').textContent.includes('编号'), '导入界面缺少下方明细获取提示');
    const originalExec = document.execCommand;
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText: async () => { throw new Error('test permission denied'); } } });
    document.execCommand = () => false;
    $('copy-import-guide').click();
    await until(() => $('import-full-format-guide').open, '复制不可用时没有展开完整说明');
    document.execCommand = originalExec;
    $('import-full-format-guide').open = false;
    document.querySelector('.import-body').scrollTop = 0;
    if (descriptor) Object.defineProperty(navigator, 'clipboard', descriptor); else delete navigator.clipboard;
    if (['guide', 'example', 'guide-cancel', 'guide-extension'].includes(window.__IMPORT_TEST_MODE__)) {
      window.__importSmoke = { ok: true, guide: true, copy: true, gated: true };
      return;
    }
    $('import-ready').click();
    check(!$('choose-import-file').disabled, '准备确认后不能选择文件');
    choose('错误.json', '{');
    await until(() => !$('import-error').hidden, '无效 JSON 没有报错');
    check($('apply-import').disabled && localStorage.getItem(key) === baseline, '无效文件改变了当前课表');
    const sample = JSON.parse(JSON.stringify(window.__IMPORT_TEST_EXAMPLE__));
    sample.courses[1].id = '900000001';
    delete sample.courses[1].classid;
    const text = JSON.stringify(sample);
    await validPreview(text);
    check(localStorage.getItem(key) === baseline, '预览时提前替换了课表');
    $('import-dialog').querySelector('[data-close]').click();
    await until(() => !$('import-dialog').open, '取消导入没有关闭界面');
    check(localStorage.getItem(key) === baseline, '取消导入改变了课表');
    $('import-button').click();
    $('import-ready').click();
    const originalText = File.prototype.text;
    File.prototype.text = async function () { if (this.name === '慢文件.json') await delay(200); return originalText.call(this); };
    choose('慢文件.json', text);
    $('import-dialog').querySelector('[data-close]').click();
    await delay(250);
    File.prototype.text = originalText;
    check(localStorage.getItem(key) === baseline && $('import-preview').hidden, '关闭后异步文件结果仍然生效');
    $('import-button').click();
    $('import-ready').click();
    await validPreview(text);
    await delay(450);
    window.__importPreviewReady = true;
    await until(() => window.__importPreviewCaptured, '预览截图未完成');
    if (window.__IMPORT_TEST_MODE__ === 'concurrent') {
      const external = JSON.parse(baseline);
      external.courses[0].notes = '另一个窗口的最新修改';
      const externalRaw = JSON.stringify(external);
      localStorage.setItem(key, externalRaw);
      $('apply-import').click();
      check(localStorage.getItem(key) === externalRaw && !$('import-error').hidden, '导入覆盖了其他窗口的新数据');
      window.__importSmoke = { ok: true, concurrentPreserved: true, guide: true, copy: true, gated: true, invalidPreserved: true, cancelled: true, staleIgnored: true };
      return;
    }
    $('apply-import').click();
    await until(() => !$('import-dialog').open && $('stat-courses').textContent === '3', '确认后课表没有更新');
    const saved = JSON.parse(localStorage.getItem(key));
    check(saved.semester.name === sample.semester.name && saved.courses.length === 3, '导入内容不一致');
    check(saved.courses.find(course => course.id === '900000001').classid === '', '导入套用了个人参照表');
    check($('stat-credits').textContent === '6', '导入后学分统计不正确');
    window.__importSmoke = { ok: true, guide: true, copy: true, gated: true, invalidPreserved: true, cancelled: true, staleIgnored: true, preview: true, imported: true, missingClassidBlank: true, courses: 3 };
  })().catch(error => { window.__importSmoke = { ok: false, error: error.stack || error.message }; });
})();
