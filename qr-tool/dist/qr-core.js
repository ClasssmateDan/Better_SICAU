(function (root, factory) {
  "use strict";

  var api = factory();
  if (typeof module === "object" && module.exports) {
    module.exports = api;
  }
  if (root) root.QrCore = api;
})(typeof globalThis !== "undefined" ? globalThis : this, function () {
  "use strict";

  var DEFAULT_ENDPOINT = "https://eol.sicau.edu.cn/Roll/qrsign";
  var SOURCE_TIME_OFFSET_MS = 8 * 60 * 60 * 1000;
  var CLASS_ID_PATTERN = /^\d{1,32}$/;
  var PAYLOAD_PATTERN = /^(\d{1,32}),([1-9]\d{12})$/;
  var BASE64_PATTERN = /^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/;
  var beijingFormatter = new Intl.DateTimeFormat("en-GB", {
    timeZone: "Asia/Shanghai",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
    hourCycle: "h23"
  });

  function fail(message, code) {
    var error = new Error(message);
    error.code = code;
    throw error;
  }

  function encodeBase64(payload) {
    if (typeof btoa === "function") return btoa(payload);
    if (typeof Buffer !== "undefined") {
      return Buffer.from(payload, "ascii").toString("base64");
    }
    fail("当前环境不支持 Base64 编码。", "BASE64_UNAVAILABLE");
  }

  function decodeBase64(qr) {
    if (!qr || !BASE64_PATTERN.test(qr)) {
      fail("二维码参数不是完整、有效的 Base64 字符串。", "INVALID_BASE64");
    }
    var payload;
    try {
      if (typeof atob === "function") payload = atob(qr);
      else if (typeof Buffer !== "undefined") {
        payload = Buffer.from(qr, "base64").toString("latin1");
      } else fail("当前环境不支持 Base64 解码。", "BASE64_UNAVAILABLE");
    } catch (error) {
      if (error.code === "BASE64_UNAVAILABLE") throw error;
      fail("二维码参数无法解码。", "INVALID_BASE64");
    }
    if (encodeBase64(payload) !== qr) {
      fail("二维码参数的 Base64 编码不规范。", "INVALID_BASE64");
    }
    return payload;
  }

  function validateEndpoint(url) {
    if (url.protocol !== "https:" || url.hostname !== "eol.sicau.edu.cn" ||
        url.port || url.username || url.password || url.pathname !== "/Roll/qrsign" ||
        url.hash) {
      fail("仅支持 eol.sicau.edu.cn 的 HTTPS 签到二维码链接。", "UNSUPPORTED_URL");
    }
    return url.origin + url.pathname;
  }

  function parsePayload(payload) {
    var match = PAYLOAD_PATTERN.exec(payload);
    if (!match) {
      fail("二维码内容应为数字 classid、英文逗号和 13 位毫秒时间值。", "INVALID_PAYLOAD");
    }
    return { classId: match[1], timestampMs: Number(match[2]) };
  }

  function parseQrContent(text) {
    if (typeof text !== "string" || !text.trim() || text.length > 4096) {
      fail("请提供有效的签到二维码内容。", "INVALID_INPUT");
    }
    var input = text.trim();
    var endpoint = DEFAULT_ENDPOINT;
    var originalUrl = null;
    var qr;
    var payload;
    var inputType;

    if (/^[A-Za-z][A-Za-z0-9+.-]*:/.test(input) || input.indexOf("://") !== -1) {
      var parsed;
      try { parsed = new URL(input); }
      catch (error) { fail("二维码中包含无效的链接。", "INVALID_URL"); }
      endpoint = validateEndpoint(parsed);
      var values = parsed.searchParams.getAll("qr");
      if (values.length !== 1 || !values[0]) {
        fail("签到链接必须包含唯一的 qr 参数。", "INVALID_QR_PARAMETER");
      }
      qr = values[0];
      payload = decodeBase64(qr);
      originalUrl = input;
      inputType = "url";
    } else if (input.indexOf(",") !== -1) {
      payload = input;
      qr = encodeBase64(payload);
      inputType = "payload";
    } else {
      qr = input;
      payload = decodeBase64(qr);
      inputType = "base64";
    }

    var fields = parsePayload(payload);
    return {
      classId: fields.classId,
      timestampMs: fields.timestampMs,
      endpoint: endpoint,
      originalUrl: originalUrl,
      qr: qr,
      payload: payload,
      inputType: inputType
    };
  }

  function generateQr(source, nowMs) {
    var record = typeof source === "string" ? parseQrContent(source) : source;
    if (!record || typeof record.classId !== "string" || !CLASS_ID_PATTERN.test(record.classId)) {
      fail("请先解析一个有效的签到二维码。", "INVALID_CLASS_ID");
    }
    var endpoint;
    try {
      var parsedEndpoint = new URL(record.endpoint || DEFAULT_ENDPOINT);
      endpoint = validateEndpoint(parsedEndpoint);
      if (parsedEndpoint.search) fail("签到地址不能附带额外参数。", "UNSUPPORTED_URL");
    } catch (error) {
      if (error.code) throw error;
      fail("签到地址无效。", "INVALID_URL");
    }
    var actualTimeMs = nowMs === undefined ? Date.now() : nowMs;
    if (!Number.isSafeInteger(actualTimeMs)) {
      fail("当前时间必须是有效的整数毫秒值。", "INVALID_TIME");
    }
    // This school's samples encode the Beijing clock face as UTC: Unix time + 8 h.
    // Add a fixed offset; do not use the computer's local timezone.
    var timestampMs = actualTimeMs + SOURCE_TIME_OFFSET_MS;
    if (!Number.isSafeInteger(timestampMs) || !/^[1-9]\d{12}$/.test(String(timestampMs))) {
      fail("生成的时间字段必须为 13 位毫秒值。", "INVALID_TIME");
    }
    var payload = record.classId + "," + timestampMs;
    var qr = encodeBase64(payload);
    var url = new URL(endpoint);
    url.searchParams.set("qr", qr);
    return {
      classId: record.classId,
      timestampMs: timestampMs,
      actualTimeMs: actualTimeMs,
      endpoint: endpoint,
      qr: qr,
      payload: payload,
      url: url.href
    };
  }

  function formatBeijingTime(nowMs) {
    var value = nowMs === undefined ? Date.now() : nowMs;
    if (!Number.isSafeInteger(value) || !Number.isFinite(new Date(value).getTime())) {
      fail("无法显示无效的时间。", "INVALID_TIME");
    }
    var parts = {};
    beijingFormatter.formatToParts(new Date(value)).forEach(function (part) {
      parts[part.type] = part.value;
    });
    var milliseconds = String(new Date(value).getUTCMilliseconds()).padStart(3, "0");
    return parts.year + "-" + parts.month + "-" + parts.day + " " +
      parts.hour + ":" + parts.minute + ":" + parts.second + "." + milliseconds;
  }

  return Object.freeze({
    DEFAULT_ENDPOINT: DEFAULT_ENDPOINT,
    SOURCE_TIME_OFFSET_MS: SOURCE_TIME_OFFSET_MS,
    parseQrContent: parseQrContent,
    generateQr: generateQr,
    formatBeijingTime: formatBeijingTime
  });
});
