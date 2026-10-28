"use strict";
importScripts("har_format.js");

const captures = new Map();
const filter = {urls: ["https://eol.sicau.edu.cn/*"]};

chrome.webRequest.onSendHeaders.addListener(details => {
  const pending = captures.get(details.url);
  if (!pending || ![pending.tabId, -1].includes(details.tabId)) return;
  pending.url = details.url;
  pending.method = details.method;
  pending.requestHeaders = (details.requestHeaders || []).map(({name, value}) => ({name, value: value || ""}));
}, filter, ["requestHeaders", "extraHeaders"]);

chrome.webRequest.onHeadersReceived.addListener(details => {
  const pending = captures.get(details.url);
  if (!pending || ![pending.tabId, -1].includes(details.tabId)) return;
  pending.statusLine = details.statusLine;
  pending.responseHeaders = (details.responseHeaders || []).map(({name, value}) => ({name, value: value || ""}));
}, filter, ["responseHeaders", "extraHeaders"]);

// This function runs in the existing school tab's isolated extension world.
async function readHomepage(url) {
  if (location.origin !== "https://eol.sicau.edu.cn") throw new Error("logged_out");
  const target = new URL(url);
  if (target.origin !== location.origin || target.pathname !== "/Students") throw new Error("logged_out");
  const start = performance.now();
  const startedDateTime = new Date().toISOString();
  const response = await fetch(url, {
    method: "GET", credentials: "include", cache: "no-store", redirect: "error",
    signal: AbortSignal.timeout(20000)
  });
  const headersElapsed = performance.now() - start;
  const bytes = new Uint8Array(await response.arrayBuffer());
  if (bytes.byteLength > 4 * 1024 * 1024) throw new Error("capture_failed");
  const chunks = [];
  for (let offset = 0; offset < bytes.length; offset += 8192) {
    chunks.push(String.fromCharCode(...bytes.subarray(offset, offset + 8192)));
  }
  return {
    url: response.url, status: response.status, statusText: response.statusText,
    contentType: response.headers.get("content-type") || "",
    responseHeaders: [...response.headers].map(([name, value]) => ({name, value})),
    bodyBase64: btoa(chunks.join("")), bodySize: bytes.byteLength,
    startedDateTime, headersElapsed, elapsed: performance.now() - start
  };
}

async function bridgeFetch(path, token, data) {
  const options = {
    method: data === undefined ? "GET" : "POST",
    headers: {Authorization: "Bearer " + token},
    redirect: "error", cache: "no-store", signal: AbortSignal.timeout(10000)
  };
  if (data !== undefined) {
    options.headers["Content-Type"] = "application/json";
    options.body = JSON.stringify(data);
  }
  const response = await fetch(HarFormat.BRIDGE + path, options);
  const payload = await response.json();
  if (!response.ok) throw new Error(payload.error || "本机工具拒绝连接，请重新发起读取。");
  return payload;
}

async function captureCurrentBrowser(token, sender) {
  HarFormat.connection(sender.url, token);
  await bridgeFetch("/api/authorize", token); // Confirm a live user-initiated desktop job first.
  let captureURL;
  try {
    const tabs = (await chrome.tabs.query({url: [HarFormat.SCHOOL + "/*"]}))
      .filter(tab => Boolean(tab.incognito) === Boolean(sender.tab.incognito));
    tabs.sort((first, second) => (second.lastAccessed || 0) - (first.lastAccessed || 0));
    const tab = tabs[0];
    if (!tab) throw new Error("no_tab");
    captureURL = HarFormat.SCHOOL + "/Students?course_har_capture=" + crypto.randomUUID();
    const observed = {tabId: tab.id};
    captures.set(captureURL, observed);
    const injection = await chrome.scripting.executeScript({
      target: {tabId: tab.id}, world: "ISOLATED", func: readHomepage, args: [captureURL]
    });
    const result = injection[0] && injection[0].result;
    if (!result) throw new Error("capture_failed");
    const har = HarFormat.buildHar(result, observed, captureURL);
    await bridgeFetch("/api/result", token, har);
    return {ok: true, message: "读取完成：HAR 已保存到桌面工具的“本机记录”，课程和 Cookie 已自动填入。可以关闭此标签页。"};
  } catch (error) {
    const code = ["no_tab", "no_cookie", "logged_out"].includes(error.message) ? error.message : "capture_failed";
    await bridgeFetch("/api/failure", token, {code}).catch(() => {});
    throw error;
  } finally {
    if (captureURL) captures.delete(captureURL);
  }
}

chrome.runtime.onMessage.addListener((message, sender, sendResponse) => {
  if (!message || message.type !== "COURSE_CAPTURE" || sender.id !== chrome.runtime.id || !sender.tab) return false;
  captureCurrentBrowser(message.token, sender).then(sendResponse).catch(error => {
    const messages = {
      no_tab: "请先在这个浏览器中打开并登录学校网站，保持标签页打开，再回桌面工具重新读取。",
      no_cookie: "未观察到 Cookie，请在扩展详情中允许访问学校网站，然后重新读取。",
      logged_out: "学校主页读取没有成功，请先在浏览器确认登录状态。",
      capture_failed: "读取失败，请确认学校网页可访问、扩展网站权限已开启，再回工具重新读取。"
    };
    sendResponse({ok: false, message: messages[error.message] || "连接或读取失败，请回到桌面工具查看提示并重新发起。"});
  });
  return true;
});
