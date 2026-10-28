"use strict";
(async () => {
  if (location.origin !== "http://127.0.0.1:18763" || location.pathname !== "/course-har-capture") return;
  const token = new URLSearchParams(location.hash.slice(1)).get("token");
  if (!/^[A-Za-z0-9_-]{43}$/.test(token || "")) return;
  const element = document.getElementById("message");
  if (!element) return;
  element.textContent = "正在通过现有登录会话读取学校主页，请稍候…";
  try {
    const result = await chrome.runtime.sendMessage({type: "COURSE_CAPTURE", token});
    element.textContent = result && result.message || "没有取得结果，请回桌面工具重试。";
  } catch {
    element.textContent = "扩展连接失败，请刷新扩展后回桌面工具重新发起读取。";
  } finally {
    history.replaceState(null, "", location.pathname);
  }
})();
