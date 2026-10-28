/* Shared pure helpers. No networking and no browser API calls in this file. */
(() => {
  "use strict";
  const BRIDGE = "http://127.0.0.1:18763";
  const CONNECT_PATH = "/course-har-capture";
  const SCHOOL = "https://eol.sicau.edu.cn";

  function connection(urlText, token) {
    const url = new URL(urlText);
    if (url.origin !== BRIDGE || url.pathname !== CONNECT_PATH || url.search ||
        url.username || url.password || !/^[A-Za-z0-9_-]{43}$/.test(token || "") ||
        new URLSearchParams(url.hash.slice(1)).get("token") !== token) {
      throw new Error("连接链接无效，请从桌面工具重新发起。");
    }
    return BRIDGE;
  }

  function schoolRequest(urlText) {
    try {
      const url = new URL(urlText);
      return url.origin === SCHOOL && !url.username && !url.password && url.pathname === "/Students";
    } catch {
      return false;
    }
  }

  function buildHar(result, observed, expectedURL) {
    if (!schoolRequest(expectedURL) || result.url !== expectedURL || result.status !== 200 ||
        !/text\/html/i.test(result.contentType || "") ||
        typeof result.bodyBase64 !== "string") {
      throw new Error("logged_out");
    }
    if (!observed || observed.url !== expectedURL || observed.method !== "GET") {
      throw new Error("no_cookie");
    }
    const headers = observed.requestHeaders || [];
    const cookieText = headers.filter(header => header.name.toLowerCase() === "cookie")
      .map(header => header.value || "").join("; ");
    if (!cookieText) throw new Error("no_cookie");
    const cookies = cookieText.split(";").map(part => {
      const index = part.indexOf("=");
      return index < 1 ? null : {name: part.slice(0, index).trim(), value: part.slice(index + 1).trim()};
    }).filter(Boolean);
    const url = new URL(expectedURL);
    const version = (observed.statusLine || "").split(" ")[0];
    return {log: {
      version: "1.2",
      creator: {name: "课程主页 HAR 桥接", version: "3.0.0"},
      comment: "单次学生主页 fetch 的实际请求标头及响应正文；未知网络大小标为 -1，耗时来自 fetch 计时。",
      entries: [{
        startedDateTime: result.startedDateTime,
        time: result.elapsed,
        request: {
          method: "GET", url: expectedURL, httpVersion: version || "",
          headers, cookies, queryString: [...url.searchParams].map(([name, value]) => ({name, value})),
          headersSize: -1, bodySize: 0
        },
        response: {
          status: result.status, statusText: result.statusText || "", httpVersion: version || "",
          headers: observed.responseHeaders || result.responseHeaders || [], cookies: [],
          content: {size: result.bodySize, mimeType: result.contentType, text: result.bodyBase64, encoding: "base64"},
          redirectURL: "", headersSize: -1, bodySize: -1
        },
        cache: {},
        timings: {send: 0, wait: result.headersElapsed, receive: Math.max(0, result.elapsed - result.headersElapsed)}
      }]
    }};
  }
  const helpers = {BRIDGE, CONNECT_PATH, SCHOOL, connection, schoolRequest, buildHar};
  globalThis.HarFormat = helpers;
  if (typeof module !== "undefined") module.exports = helpers;
})();
