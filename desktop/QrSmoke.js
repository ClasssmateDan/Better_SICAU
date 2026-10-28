(() => {
  const delay=ms=>new Promise(resolve=>setTimeout(resolve,ms));
  const until=async(predicate,message)=>{for(let i=0;i<100;i++){if(predicate())return;await delay(50);}throw Error(message);};
  (async()=>{
    document.querySelector('[data-view="qr"]').click();
    const frame=document.getElementById('qr-frame');
    await until(()=>frame.contentWindow?.QrCore && frame.contentDocument.getElementById('classid-input'),'二维码工具未载入');
    const win=frame.contentWindow,doc=frame.contentDocument;
    doc.getElementById('classid-input').value='900000001';
    doc.getElementById('classid-input').dispatchEvent(new win.Event('input',{bubbles:true}));
    doc.getElementById('generate-classid-button').click();
    await until(()=>!doc.getElementById('qr-canvas').hidden,'未根据显式编号生成二维码');
    if(win.QrCore.parseQrContent(doc.getElementById('generated-url').value).classId!=='900000001')throw Error('二维码参数不正确');
    window.__qrSmoke={ok:true,generated:true,synthetic:true};
  })().catch(error=>{window.__qrSmoke={ok:false,error:error.stack};});
})();
