package com.dan.qrbatch.network;

import com.dan.qrbatch.core.Account;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.net.ssl.SSLException;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Synthetic responses only; this transport never opens a URL or sends credentials. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class LoginProtocolTest {
    private static final String PHONE = "19900000000", PASSWORD = "synthetic-password";
    private static ChaoxingClient.Response reply(int status, String body) { return new ChaoxingClient.Response(status, body, null); }
    private static final class Script implements ChaoxingClient.Transport {
        int requests; String loginBody = "{\"status\":true}";
        String profile = "<input id='messageName' value='测试用户'>";
        String uid = "10001"; IOException failure; int status = 200;
        final List<Map<String, String>> sessions = new ArrayList<>();
        @Override public ChaoxingClient.Response send(String method, String address, Map<String, String> values, Map<String, String> cookies) throws IOException {
            requests++; if (failure != null) throw failure;
            if (address.endsWith("/fanyalogin")) {
                assertEquals("POST", method); assertNotEquals(PASSWORD, values.get("password"));
                sessions.add(cookies); if (uid != null) cookies.put("_uid", uid);
                cookies.put("synthetic-session", "test-cookie"); return reply(status, loginBody);
            }
            assertEquals("GET", method); assertTrue(address.endsWith("/mooc/accountManage")); return reply(200, profile);
        }
    }
    private String loginError(Script script) throws Exception {
        try { new ChaoxingClient(script).login(PHONE, PASSWORD); fail("Expected a login error"); return ""; }
        catch (IOException error) { return error.getMessage(); }
    }
    @Test public void successfulLoginReadsIdentityAndReportsStages() throws Exception {
        Script script = new Script(); List<String> stages = new ArrayList<>();
        Account account = new ChaoxingClient(script).login(PHONE, PASSWORD, stages::add);
        assertEquals(2, script.requests); assertEquals("10001", account.uid); assertEquals("测试用户", account.name);
        assertEquals("test-cookie", account.cookies.get("synthetic-session")); assertEquals(2, stages.size());
        assertTrue(stages.get(0).contains("验证")); assertTrue(stages.get(1).contains("账户资料"));
    }
    @Test public void rejectedPasswordDoesNotRequestProfile() throws Exception {
        Script script = new Script(); script.loginBody = "{\"status\":false,\"msg\":\"密码错误\"}";
        assertTrue(loginError(script).contains("账号或密码")); assertEquals(1, script.requests);
    }
    @Test public void extraVerificationIsReportedWithoutEchoingServerText() throws Exception {
        Script script = new Script(); script.loginBody = "{\"status\":false,\"msg\":\"需要验证码 SECRET_SENTINEL\"}";
        String error = loginError(script); assertTrue(error.contains("额外验证")); assertFalse(error.contains("SECRET_SENTINEL"));
    }
    @Test public void httpFailureShowsStatusAndStage() throws Exception {
        Script script = new Script(); script.status = 429;
        String error = loginError(script); assertTrue(error.contains("429")); assertTrue(error.contains("登录验证"));
    }
    @Test public void timeoutDnsAndTlsAreDistinctAndDoNotLeakUrl() throws Exception {
        Script script = new Script(); script.failure = new SocketTimeoutException("https://private.example/SECRET_SENTINEL");
        assertTrue(loginError(script).contains("超时")); assertFalse(loginError(script).contains("SECRET_SENTINEL"));
        script.failure = new UnknownHostException("SECRET_SENTINEL"); assertTrue(loginError(script).contains("域名解析"));
        script.failure = new SSLException("SECRET_SENTINEL"); assertTrue(loginError(script).contains("安全连接"));
    }
    @Test public void missingIdentityCannotProduceSavedAccount() throws Exception {
        Script script = new Script(); script.uid = null; assertTrue(loginError(script).contains("用户编号"));
        script.uid = "10001"; script.profile = "<html>changed</html>"; assertTrue(loginError(script).contains("姓名"));
    }
    @Test public void changedLoginSchemaCannotBeTreatedAsSuccess() throws Exception {
        Script script = new Script(); script.loginBody = "<html>new login page</html>";
        assertTrue(loginError(script).contains("响应格式")); assertEquals(1, script.requests);
    }
    @Test public void repeatedLoginsUseSeparateCookieMaps() throws Exception {
        Script script = new Script(); ChaoxingClient client = new ChaoxingClient(script);
        Account first = client.login(PHONE, PASSWORD); script.uid = "10002";
        Account second = client.login("19900000001", PASSWORD);
        assertNotSame(script.sessions.get(0), script.sessions.get(1)); assertEquals("10001", first.uid);
        assertEquals("10002", second.uid); assertEquals("10001", first.cookies.get("_uid"));
    }
    @Test public void regularTextProfileElementCanCompleteLogin() throws Exception {
        Script script = new Script(); script.profile = "<span id='messageName' title='完整姓名'>完整姓名</span>";
        assertEquals("完整姓名", new ChaoxingClient(script).login(PHONE, PASSWORD).name);
    }
    @Test public void suppliedNameIsUsedOnlyWhenAutomaticNameIsUnavailable() throws Exception {
        Script script = new Script(); ChaoxingClient client = new ChaoxingClient(script);
        assertEquals("测试用户", client.login(PHONE, PASSWORD, "补填姓名", ignored -> {}).name);
        script.profile = "<html>changed</html>"; List<String> stages = new ArrayList<>();
        Account account = client.login(PHONE, PASSWORD, " 补填姓名 ", stages::add);
        assertEquals("补填姓名", account.name); assertEquals("10001", account.uid);
        assertTrue(stages.stream().anyMatch(stage -> stage.contains("填写的真实姓名")));
    }
    @Test public void loginResponseCanSupplyVerifiedName() throws Exception {
        Script script = new Script(); script.profile = "<html>changed</html>";
        script.loginBody = "{\"status\":true,\"data\":{\"realName\":\"响应姓名\"}}";
        assertEquals("响应姓名", new ChaoxingClient(script).login(PHONE, PASSWORD).name);
    }
    @Test public void manualNameCannotBypassFailedAuthenticationOrMissingUid() throws Exception {
        Script script = new Script(); script.loginBody = "{\"status\":false}";
        assertThrows(IOException.class, () -> new ChaoxingClient(script).login(PHONE, PASSWORD, "补填姓名", ignored -> {}));
        assertEquals(1, script.requests);
        script.loginBody = "{\"status\":true}"; script.uid = null;
        assertThrows(IOException.class, () -> new ChaoxingClient(script).login(PHONE, PASSWORD, "补填姓名", ignored -> {}));
    }
    @Test public void invalidManualNameIsRejectedBeforeNetworkRequest() {
        Script script = new Script();
        assertThrows(IOException.class, () -> new ChaoxingClient(script).login(PHONE, PASSWORD, "{{name}}", ignored -> {}));
        assertEquals(0, script.requests);
    }
}
