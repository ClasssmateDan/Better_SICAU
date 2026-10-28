// Synthetic companion account; the companion blocks all real network and browser operations.
(() => {
  const $=id=>document.getElementById(id);
  const results=[];
  window.chrome.webview.addEventListener('message', event=>{if(event.data?.type==='course-tools:result')results.push(event.data);});
  const delay=ms=>new Promise(resolve=>setTimeout(resolve,ms));
  const until=async(predicate,message)=>{for(let i=0;i<220;i++){if(predicate())return;await delay(100);}throw Error(message);};
  (async()=>{
    if($('tools-course-select').options.length!==1)throw Error('空课表不应包含教学班选项');
    document.querySelector('[data-view="tools"]').click();
    $('tools-open').click();
    await until(()=>results.length===1,'通用课程工具没有打开');
    if(results[0].status!=='opened')throw Error(JSON.stringify(results[0]));
    $('tools-extension').click();
    await until(()=>results.length===2,'扩展目录未打开');
    if(results[1].status!=='opened')throw Error(JSON.stringify(results[1]));
    window.__toolSmoke={ok:true,results:results.map(item=>({status:item.status,action:item.action})),courses:0};
  })().catch(error=>{window.__toolSmoke={ok:false,error:error.stack};});
})();
