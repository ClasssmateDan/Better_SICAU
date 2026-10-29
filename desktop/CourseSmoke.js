// Synthetic data only: add a course through the form and reset to the public blank template.
(() => {
  const $ = id => document.getElementById(id);
  const check = (value, message) => { if (!value) throw Error(message); };
  const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
  const until = async (predicate, message) => { for (let i=0;i<100;i++) { if(predicate())return; await delay(40); } throw Error(message); };
  (async () => {
    check(JSON.parse(localStorage.getItem('kejian.timetable.public.v1')).courses.length === 0, '公开版首次课表不为空');
    $('add-course').click();
    const form=$('course-form');
    form.elements.name.value='离线测试课程';
    form.elements.classid.value='900000001';
    if(form.elements.signinMethod.value!=='教务系统')throw Error('新课程签到方式不正确');
    form.elements.signinMethod.value='学习通';
    form.requestSubmit();
    await until(() => !$('course-dialog').open && $('stat-courses').textContent === '1', '无法添加课程');
    document.querySelector('[data-signin-course-id]').click();
    await until(()=>$('confirm-dialog').open,'非教务方式没有确认');
    check($('confirm-message').textContent.includes('学习通'),'确认没有显示已保存的签到方式');
    $('confirm-dialog').querySelector('[data-close]').click();
    await until(()=>!$('confirm-dialog').open&&!document.querySelector('[data-signin-course-id]').disabled,'取消跳转后没有恢复');
    $('settings-button').click();
    $('reset-preset').click();
    await until(() => $('confirm-dialog').open, '重置前没有确认');
    check($('confirm-title').textContent.includes('空白课表'), '公开版仍提供个人课表恢复');
    $('confirm-action').click();
    await until(() => $('stat-courses').textContent === '0', '重置后课表不为空');
    window.__courseSmoke={ok:true,added:true,resetBlank:true};
  })().catch(error => { window.__courseSmoke={ok:false,error:error.stack}; });
})();
