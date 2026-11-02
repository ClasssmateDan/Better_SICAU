package com.dan.qrbatch.network;

import com.dan.qrbatch.core.SignResult;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/** Pure offline regression tests; never calls request(), login(), prepare() or submit(). */
public final class ClientTest {
    private static int assertions;
    private static Constructor<?> response;
    private static Method classify;
    private static Method encrypt;

    public static void main(String[] args) throws Exception {
        Class<?> client = Class.forName("com.dan.qrbatch.network.ChaoxingClient");
        Class<?> responseClass = Class.forName("com.dan.qrbatch.network.ChaoxingClient$Response");
        response = responseClass.getDeclaredConstructor(int.class, String.class, String.class);
        response.setAccessible(true);
        classify = client.getDeclaredMethod("classify", responseClass);
        classify.setAccessible(true);
        encrypt = client.getDeclaredMethod("encryptPassword", String.class);
        encrypt.setAccessible(true);

        state("raw success", 200, "success", null, SignResult.State.SUCCESS);
        state("success with newline is unrecognized, never failed", 200, "success\n", null, SignResult.State.UNCERTAIN);
        state("success with spaces is unrecognized", 200, " success ", null, SignResult.State.UNCERTAIN);
        state("uppercase success is unrecognized", 200, "SUCCESS", null, SignResult.State.UNCERTAIN);
        state("exact Chinese already", 200, "您已签到", null, SignResult.State.ALREADY_SIGNED);
        state("already with terminal punctuation", 200, "您已经签到！", null, SignResult.State.ALREADY_SIGNED);
        state("exact already token", 200, "already_signed", null, SignResult.State.ALREADY_SIGNED);
        state("not signed", 200, "未签到", null, SignResult.State.UNCERTAIN);
        state("already query failed", 200, "查询已签到状态失败", null, SignResult.State.UNCERTAIN);
        state("no already record", 200, "没有已签到记录", null, SignResult.State.UNCERTAIN);
        state("not already token", 200, "not already signed", null, SignResult.State.UNCERTAIN);
        state("already advice is not a result", 200, "请勿重复签到", null, SignResult.State.UNCERTAIN);
        state("JSON mentions already", 200, "{\"msg\":\"查询已签到状态失败\"}", null, SignResult.State.UNCERTAIN);
        state("JSON success field unknown", 200, "{\"result\":\"success\"}", null, SignResult.State.UNCERTAIN);
        state("JSON login template", 200, "{\"help\":\"未登录时请登录\"}", null, SignResult.State.UNCERTAIN);
        state("JSON array already", 200, "[\"已签到\"]", null, SignResult.State.UNCERTAIN);
        state("HTML already label", 200, "<html><label>已签到</label></html>", null, SignResult.State.UNCERTAIN);
        state("HTML login form", 200, "<form action='fanyalogin'>请登录<input name=\"password\"></form>", null, SignResult.State.UNCERTAIN);
        state("HTML expired help", 200, "<p>二维码已过期，请刷新二维码</p>", null, SignResult.State.UNCERTAIN);
        state("empty body", 200, "", null, SignResult.State.UNCERTAIN);
        state("blank body", 200, " \r\n ", null, SignResult.State.UNCERTAIN);
        state("not expired", 200, "not expired", null, SignResult.State.UNCERTAIN);
        state("refresh advice", 200, "请刷新页面查看最新签到状态", null, SignResult.State.UNCERTAIN);
        state("plain refresh", 200, "refresh", null, SignResult.State.UNCERTAIN);
        state("refresh completed", 200, "refresh completed", null, SignResult.State.UNCERTAIN);
        state("explicit expired Chinese", 200, "二维码已过期", null, SignResult.State.EXPIRED);
        state("explicit expired instruction", 200, "二维码已过期，请重新扫描。", null, SignResult.State.EXPIRED);
        state("explicit expired token", 200, "expired", null, SignResult.State.EXPIRED);
        state("explicit need current QR token", 200, "fail-need-qrcode", null, SignResult.State.EXPIRED);
        state("explicit need refresh token", 200, "fail-need-refresh", null, SignResult.State.EXPIRED);
        state("expiry query failure", 200, "查询二维码已过期状态失败", null, SignResult.State.UNCERTAIN);
        state("location rejected", 200, "不在签到范围内", null, SignResult.State.FAILED);
        state("location info unknown", 200, "签到位置已更新", null, SignResult.State.UNCERTAIN);
        state("activity ended", 200, "活动已结束", null, SignResult.State.FAILED);
        state("activity end query failed", 200, "查询活动已结束状态失败", null, SignResult.State.UNCERTAIN);
        state("explicit historical fail", 200, "fail", null, SignResult.State.FAILED);
        state("unknown response", 200, "new-status-v9", null, SignResult.State.UNCERTAIN);
        state("HTTP 500 even with success body", 500, "success", null, SignResult.State.UNCERTAIN);
        state("HTTP 429 even with already body", 429, "已签到", null, SignResult.State.UNCERTAIN);
        state("HTTP 401", 401, "", null, SignResult.State.AUTH_REQUIRED);
        state("HTTP 403 JSON", 403, "{}", null, SignResult.State.AUTH_REQUIRED);
        state("exact login text", 200, "请重新登录", null, SignResult.State.AUTH_REQUIRED);
        state("exact login token", 200, "notlogin", null, SignResult.State.AUTH_REQUIRED);
        state("ambiguous login help", 200, "如果未登录，请登录后查看签到记录", null, SignResult.State.UNCERTAIN);
        state("known HTTPS login redirect", 302, "", "https://passport2.chaoxing.com/login?refer=x", SignResult.State.AUTH_REQUIRED);
        state("known relative login redirect", 302, "", "/mlogin", SignResult.State.AUTH_REQUIRED);
        state("unknown redirect", 302, "", "https://example.com/", SignResult.State.UNCERTAIN);
        state("deceptive redirect", 302, "", "https://passport2.chaoxing.com.example.org/login", SignResult.State.UNCERTAIN);
        state("redirect URL string in query", 302, "", "https://example.org/?next=https://passport2.chaoxing.com/login", SignResult.State.UNCERTAIN);
        state("HTTP login redirect not followed or trusted", 302, "", "http://passport2.chaoxing.com/login", SignResult.State.UNCERTAIN);
        state("login redirect userinfo", 302, "", "https://user@passport2.chaoxing.com/login", SignResult.State.UNCERTAIN);

        // Fixed vectors produced independently with the repository's crypto-js 4.1.1 DES/ECB/PKCS7.
        // The test inputs are artificial strings, never a real account password.
        encryption("password123", "9a97671096b3b45677039b75d66be6dc");
        encryption("测试🔒", "8090bdcadc8cd3266d6a6fbc92c1b4ec");
        encryption("", "f8073c058dc600ab");
        encryption("12345678", "b263c7c0590ad18df8073c058dc600ab");
        encryption("123456789", "b263c7c0590ad18db72fe812ba9f7860");

        SignResult secret = invoke(200, "unknown enc=TEST_SECRET_SENTINEL", null);
        check(!secret.message.contains("TEST_SECRET_SENTINEL"), "unknown body must not be echoed");
        System.out.println("ClientTest: " + assertions + " assertions passed (offline only)");
    }

    private static void state(String label, int http, String body, String location, SignResult.State expected) throws Exception {
        SignResult result = invoke(http, body, location);
        check(result.state == expected, label + ": expected " + expected + ", got " + result.state);
    }

    private static SignResult invoke(int status, String body, String location) throws Exception {
        return (SignResult) classify.invoke(null, response.newInstance(status, body, location));
    }

    private static void encryption(String plaintext, String expected) throws Exception {
        check(expected.equals(encrypt.invoke(null, plaintext)), "DES fixed vector mismatch");
    }

    private static void check(boolean value, String message) {
        assertions++;
        if (!value) throw new AssertionError(message);
    }
}
