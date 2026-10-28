// PC-only layout and navigation checks in the private development profile.
(() => {
  const $=id=>document.getElementById(id);
  const check=(condition,text)=>{if(!condition)throw Error(text);};
  const delay=ms=>new Promise(resolve=>setTimeout(resolve,ms));
  (async()=>{
    check(document.body.classList.contains('pc-platform'),'PC 样式未启用');
    check(getComputedStyle(document.querySelector('.topbar')).display==='none','学习空间面包屑仍显示');
    check(getComputedStyle(document.querySelector('.workspace-label')).display==='none','多余的学习空间标签仍显示');
    check(getComputedStyle(document.querySelector('.page-header .eyebrow')).display==='none','英文标语仍显示');
    check(getComputedStyle(document.querySelector('.overview')).display==='none','首页三张统计卡片仍显示');
    const font=parseFloat(getComputedStyle($('page-title')).fontSize);
    const top=document.querySelector('.calendar-panel').getBoundingClientRect().top;
    check(font<=26&&top<130,'页头仍占用过多空间');
    document.querySelector('[data-view="courses"]').click();
    for(let i=0;i<50&&$('courses-view').hidden;i++)await delay(40);
    check(!$('courses-view').hidden&&getComputedStyle(document.querySelector('.overview')).display!=='none','课程管理统计被删除');
    document.querySelector('[data-view="timetable"]').click();
    for(let i=0;i<50&&$('timetable-view').hidden;i++)await delay(40);
    check(!$('timetable-view').hidden&&getComputedStyle(document.querySelector('.overview')).display==='none','回到首页后统计重新出现');
    window.scrollTo(0,0);
    window.__layoutSmoke={ok:true,titleFont:font,calendarTop:top,homeStatsHidden:true,courseStatsVisible:true};
  })().catch(error=>{window.__layoutSmoke={ok:false,error:error.stack};});
})();
