(() => {
  const $ = id => document.getElementById(id);
  const pause = ms => new Promise(resolve => setTimeout(resolve, ms));
  const check = (condition, message) => { if (!condition) throw Error(message); };
  async function until(condition, message) { for(let i=0;i<100;i++){ if(condition()) return; await pause(40); } throw Error(message); }
  const read = () => JSON.parse(localStorage.getItem('kejian.timetable.public.v1'));
  (async () => {
    const resumed = !!read().calendar?.memos.length;
    location.hash = 'planner'; await until(() => !$('planner-view').hidden, '日历界面未显示');
    $('planner-month').value = '2026-09'; $('planner-month').dispatchEvent(new Event('change'));
    document.querySelector('[data-planner-date="2026-09-07"]').click();
    if (!resumed) {
      $('add-memo').click(); const form = $('memo-form');
      form.elements.title.value = '原生提醒测试'; form.elements.date.value = '2099-09-07';
      form.elements.time.value = '18:00'; form.elements.notes.value = '独立测试数据'; form.elements.remind.checked = true; form.requestSubmit();
      await until(() => !$('memo-dialog').open && $('reminder-dialog').open, '备忘录提醒未保存或未引导开启通知');
      $('reminder-dialog').close(); await pause(30);
      $('planner-month').value = '2026-09'; $('planner-month').dispatchEvent(new Event('change'));
      document.querySelector('[data-planner-date="2026-09-07"]').click();
      document.querySelector('[data-attend-session="0"]').click();
    }
    check(read().calendar.memos.length === 1 && read().calendar.attendance.length === 1, '日历数据未保留');
    check(document.querySelector('[data-attend-session="0"]').getAttribute('aria-pressed') === 'true', '签到标记未显示');
    $('toast').classList.remove('visible');
    window.__calendarSmoke = { ok:true, resumed, memos:read().calendar.memos.length, attendance:read().calendar.attendance.length };
  })().catch(error => { window.__calendarSmoke = { ok:false, error:error.stack }; });
})();
