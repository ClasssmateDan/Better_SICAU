package com.dan.qrbatch.network;

import com.dan.qrbatch.core.Account;
import com.dan.qrbatch.core.ActivityContext;
import com.dan.qrbatch.core.LocationValue;
import com.dan.qrbatch.core.QrPayload;
import com.dan.qrbatch.core.SignResult;
import com.dan.qrbatch.core.SigninGateway;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpCookie;
import java.net.HttpURLConnection;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Collections;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;
import java.util.zip.GZIPInputStream;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLException;

/**
 * A conservative port of the protocol in the local chaoxing-signin source.
 * This class is not a declaration that the historical endpoints remain compatible.
 * It never requests the scanned URL, invents enc/c, or retries final submissions.
 * Passwords are used only within login and are never stored on Account.
 */
public final class ChaoxingClient implements SigninGateway {
    interface Transport {
        Response send(String method, String address, Map<String, String> values, Map<String, String> cookies) throws IOException;
    }
    private final Transport transport;
    public ChaoxingClient() { this(ChaoxingClient::request); }
    ChaoxingClient(Transport transport) { this.transport = transport; }
    private static final String PASSPORT = "https://passport2.chaoxing.com";
    private static final String MOBILE = "https://mobilelearn.chaoxing.com";
    private static final String COURSES = "https://mooc1-1.chaoxing.com/visit/courselistdata";
    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final int READ_TIMEOUT_MS = 15000;
    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    private static final int MAX_COURSES = 1000;
    private static final int MAX_ACTIVITIES = 1000;
    private static final int PARALLEL_COURSES = 3;
    private static final Pattern COURSE = Pattern.compile("\\bcourse_([0-9]+)_([0-9]+)\\b");
    private static final Pattern ANALYSIS_CODE = Pattern.compile(
            "code\\s*=\\s*['\"]\\s*\\+\\s*['\"]([^'\"]+)['\"]");

    @Override
    public Account login(String phone, String password) throws Exception {
        return login(phone, password, ignored -> {});
    }

    public Account login(String phone, String password, Consumer<String> progress) throws Exception {
        return login(phone, password, "", progress);
    }

    public Account login(String phone, String password, String suppliedName, Consumer<String> progress) throws Exception {
        if (phone == null || phone.trim().length() == 0 || password == null || password.length() == 0) {
            throw new IOException("请输入手机号和密码");
        }
        String fallbackName = ProfileNames.clean(suppliedName);
        if (suppliedName != null && !suppliedName.trim().isEmpty() && fallbackName.isEmpty())
            throw new IOException("姓名格式无效，请填写学习通账户中的完整真实姓名。");
        Map<String, String> cookies = new LinkedHashMap<String, String>();
        Map<String, String> form = params("uname", phone.trim(), "password", encryptPassword(password),
                "fid", "-1", "t", "true", "refer", "https%3A%2F%2Fi.chaoxing.com",
                "forbidotherlogin", "0", "validate", "");
        progress.accept("正在验证账号和密码…");
        Response response = loginRequest("POST", PASSPORT + "/fanyalogin", form, cookies, "登录验证");
        JSONObject login = json(response.body, "登录响应格式已变化，无法确认登录结果");
        if (!login.optBoolean("status", false)) {
            throw new IOException(loginFailure(login));
        }

        // Account metadata is obtained with this login's cookie map only.
        progress.accept("账号验证通过，正在读取账户资料…");
        Response info = loginRequest("GET", PASSPORT + "/mooc/accountManage", null, cookies, "读取账户资料");
        if (requiresAuth(info)) throw new IOException("账户资料需要重新验证，请先在官方应用完成登录验证后重试。");
        JSONObject data = login.optJSONObject("data");
        String uid = first(cookies.get("_uid"), cookies.get("UID"), cookies.get("uid"),
                data == null ? "" : data.optString("uid", ""), login.optString("uid", ""));
        if (!digits(uid)) throw new IOException("登录响应没有有效用户编号，无法继续");
        String name = first(ProfileNames.fromHtml(info.body), jsonName(data), jsonName(login));
        if (name.isEmpty()) {
            if (fallbackName.isEmpty()) throw new IOException("账号验证已通过，但未能自动读取完整姓名。请在登录框的“真实姓名”栏填写学习通账户中的实际姓名，再重试登录。");
            name = fallbackName;
            progress.accept("已使用你填写的真实姓名，正在保存会话…");
        }
        String fid = first(cookies.get("fid"), data == null ? "" : data.optString("fid", ""),
                login.optString("fid", ""), "-1");
        if (!digits(fid) && !"-1".equals(fid)) throw new IOException("账户学校编号格式异常");
        return new Account(phone.trim(), uid, name, fid, cookies);
    }

    private Response loginRequest(String method, String address, Map<String, String> values,
                                  Map<String, String> cookies, String stage) throws IOException {
        final Response response;
        try { response = transport.send(method, address, values, cookies); }
        catch (IOException error) { throw new IOException(stage + "失败：" + networkProblem(error)); }
        if (response.status < 200 || response.status >= 300) {
            throw new IOException(stage + "失败（HTTP " + response.status + "）。请稍后重试；如仍失败，请先在官方应用完成验证。");
        }
        return response;
    }

    private static String loginFailure(JSONObject response) {
        // Classify known reasons only. Never echo a raw server message which could include account data.
        String reason = first(response.optString("msg", ""), response.optString("mes", ""), response.optString("message", ""));
        if (reason.contains("验证码") || reason.contains("滑块") || reason.contains("验证"))
            return "登录需要额外验证，请先在学习通官方应用完成验证后重试。";
        if (reason.contains("频繁") || reason.contains("上限") || reason.contains("次数"))
            return "登录尝试次数受限，请稍后重试。";
        if (reason.contains("密码") || reason.contains("账号") || reason.contains("帐号"))
            return "账号或密码未通过验证，请核对输入；如输入无误，请在官方应用确认账号状态。";
        return "登录未成功，请在官方应用核对账号、密码或完成验证。";
    }

    private static String networkProblem(IOException error) {
        if (error instanceof TransportFailure) return ((TransportFailure) error).problem;
        if (error instanceof SocketTimeoutException) return "连接或读取超时，请检查网络后重试。";
        if (error instanceof UnknownHostException) return "域名解析失败，请检查网络或 DNS 设置。";
        if (error instanceof SSLException) return "安全连接失败，请检查系统时间和网络。";
        if (error instanceof ConnectException) return "无法连接登录服务，请检查网络后重试。";
        return "网络请求或响应异常，请检查网络后重试。";
    }

    private static final class TransportFailure extends IOException {
        final String problem;
        TransportFailure(String problem) {
            super(problem + " 提交后发生此错误时，结果可能已生效，请核对后再操作。");
            this.problem = problem;
        }
    }

    @Override
    public List<ActivityContext> prepare(Account account) throws Exception {
        return prepare(account, ignored -> {}, () -> false);
    }

    public List<ActivityContext> prepare(Account account, Consumer<String> progress, BooleanSupplier cancelled) throws Exception {
        requireAccount(account);
        checkCancelled(cancelled);
        progress.accept("正在读取课程列表…");
        Response courseResponse = transport.send("POST", COURSES,
                params("courseType", "1", "courseFolderId", "0", "courseFolderSize", "0"), account.cookies);
        checkCancelled(cancelled);
        requireOk(courseResponse);
        Map<String, String[]> courses = new LinkedHashMap<String, String[]>();
        Matcher matcher = COURSE.matcher(courseResponse.body);
        while (matcher.find()) {
            courses.put(matcher.group(1) + "|" + matcher.group(2), new String[]{matcher.group(1), matcher.group(2)});
            if (courses.size() > MAX_COURSES) throw new IOException("课程数量超出处理上限");
        }
        if (courses.isEmpty() && courseResponse.body.trim().length() == 0) {
            throw new IOException("课程响应为空，请重新准备");
        }

        Map<String, ActivityContext> found = new LinkedHashMap<>();
        for (List<ActivityContext> courseActivities : discoverCourses(account, new ArrayList<>(courses.values()), progress, cancelled)) {
            for (ActivityContext activity : courseActivities) {
                if (!found.containsKey(activity.activeId)) found.put(activity.activeId, activity);
                if (found.size() > MAX_ACTIVITIES) throw new IOException("活动数量超出处理上限");
            }
        }
        // All slow preparation happens before scanning. A context is returned only after its preparation succeeds.
        int prepared = 0;
        for (ActivityContext activity : found.values()) {
            checkCancelled(cancelled);
            progress.accept("正在预签：" + (prepared + 1) + "/" + found.size());
            preSign(account, activity, cancelled);
            prepared++;
        }
        checkCancelled(cancelled);
        return new ArrayList<ActivityContext>(found.values());
    }

    private List<List<ActivityContext>> discoverCourses(Account account, List<String[]> courses,
            Consumer<String> progress, BooleanSupplier cancelled) throws Exception {
        if (courses.isEmpty()) return Collections.emptyList();
        int parallel = Math.min(PARALLEL_COURSES, courses.size());
        ExecutorService queries = Executors.newFixedThreadPool(parallel);
        CompletionService<CourseResult> completed = new ExecutorCompletionService<>(queries);
        List<List<ActivityContext>> results = new ArrayList<>(Collections.nCopies(courses.size(), null));
        int submitted = 0, finished = 0;
        try {
            progress.accept("正在查询课程活动：0/" + courses.size());
            for (; submitted < parallel; submitted++) submitCourse(completed, account, courses, submitted, cancelled);
            while (finished < courses.size()) {
                checkCancelled(cancelled);
                Future<CourseResult> future = completed.poll(100, TimeUnit.MILLISECONDS);
                if (future == null) continue;
                CourseResult result;
                try { result = future.get(); }
                catch (ExecutionException error) {
                    Throwable cause = error.getCause();
                    if (cause instanceof Exception) throw (Exception) cause;
                    throw new IOException("课程活动查询异常，请重试准备。");
                }
                checkCancelled(cancelled);
                results.set(result.index, result.activities); finished++;
                progress.accept("正在查询课程活动：" + finished + "/" + courses.size());
                if (submitted < courses.size()) submitCourse(completed, account, courses, submitted++, cancelled);
            }
            return results;
        } finally {
            // Do not let late responses mutate cookies after the caller has persisted the account.
            queries.shutdownNow();
            boolean interrupted = false;
            for (;;) {
                try { if (queries.awaitTermination(100, TimeUnit.MILLISECONDS)) break; }
                catch (InterruptedException error) { interrupted = true; }
            }
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private void submitCourse(CompletionService<CourseResult> completion, Account account,
            List<String[]> courses, int index, BooleanSupplier cancelled) throws IOException {
        checkCancelled(cancelled);
        completion.submit(() -> new CourseResult(index, discoverCourse(account, courses.get(index), cancelled)));
    }

    private List<ActivityContext> discoverCourse(Account account, String[] course, BooleanSupplier cancelled) throws Exception {
        checkCancelled(cancelled);
        Response activities = get(MOBILE + "/v2/apis/active/student/activelist",
                params("fid", "0", "courseId", course[0], "classId", course[1],
                        "_", String.valueOf(System.currentTimeMillis())), account.cookies);
        checkCancelled(cancelled); requireOk(activities);
        JSONObject root = json(activities.body, "活动响应格式已变化，请重新准备");
        JSONObject data = root.optJSONObject("data");
        if (data == null) throw new IOException("活动响应无有效数据，可能请求过于频繁或登录已失效");
        JSONArray list = data.optJSONArray("activeList");
        if (list == null) throw new IOException("活动列表格式已变化，无法确认活动");
        List<ActivityContext> found = new ArrayList<>();
        for (int i = 0; i < list.length(); i++) {
            JSONObject item = list.optJSONObject(i);
            if (item == null || item.optInt("status", -1) != 1 || item.optInt("otherId", -1) != 2) continue;
            String id = item.optString("id", "");
            if (!digits(id)) throw new IOException("活动编号格式异常，无法继续");
            found.add(new ActivityContext(id, course[0], course[1], item.optString("nameOne", "二维码签到")));
            if (found.size() > MAX_ACTIVITIES) throw new IOException("活动数量超出处理上限");
        }
        return found;
    }

    private static final class CourseResult {
        final int index;
        final List<ActivityContext> activities;
        CourseResult(int index, List<ActivityContext> activities) { this.index = index; this.activities = activities; }
    }

    private static void checkCancelled(BooleanSupplier cancelled) throws IOException {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new IOException("准备已停止，未继续发送请求。");
    }

    private void preSign(Account account, ActivityContext activity, BooleanSupplier cancelled) throws Exception {
        checkCancelled(cancelled);
        Response pre = get(MOBILE + "/newsign/preSign", params("courseId", activity.courseId,
                "classId", activity.classId, "activePrimaryId", activity.activeId, "general", "1",
                "sys", "1", "ls", "1", "appType", "15", "tid", "", "uid", account.uid, "ut", "s"), account.cookies);
        checkCancelled(cancelled); requireOk(pre);
        Response analysis = get(MOBILE + "/pptSign/analysis",
                params("vs", "1", "DB_STRATEGY", "RANDOM", "aid", activity.activeId), account.cookies);
        checkCancelled(cancelled); requireOk(analysis);
        Matcher match = ANALYSIS_CODE.matcher(analysis.body);
        if (!match.find() || match.group(1).trim().length() == 0 || match.group(1).length() > 4096) {
            throw new IOException("预签分析响应格式已变化，请在官方应用操作");
        }
        Response second = get(MOBILE + "/pptSign/analysis2",
                params("DB_STRATEGY", "RANDOM", "code", match.group(1)), account.cookies);
        checkCancelled(cancelled); requireOk(second);
        // The historical implementation waited after analysis2, never after receiving the QR code.
        try {
            for (int i = 0; i < 5; i++) { checkCancelled(cancelled); Thread.sleep(100); }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("准备已中断");
        }
    }

    @Override
    public SignResult submit(Account account, ActivityContext activity, QrPayload qr, LocationValue location) throws Exception {
        requireAccount(account);
        if (activity == null || qr == null || !activity.activeId.equals(qr.activeId)) {
            return result(SignResult.State.SKIPPED, "二维码与已准备活动不匹配");
        }
        Map<String, String> values = params("enc", qr.enc, "name", account.name, "activeId", activity.activeId,
                "uid", account.uid, "clientip", "", "latitude", "-1", "longitude", "-1",
                "fid", account.fid, "appType", "15");
        if (location != null && location.isPresent()) {
            JSONObject value = new JSONObject();
            value.put("result", "1");
            value.put("address", location.address);
            value.put("latitude", Double.parseDouble(location.latitude));
            value.put("longitude", Double.parseDouble(location.longitude));
            if (location.altitude.length() > 0) value.put("altitude", Double.parseDouble(location.altitude));
            values.put("location", value.toString());
        }
        // The old QR submission used enc only. c is parsed and retained by core but its current server
        // semantics are unknown; do not invent c, generate enc, or claim compatibility with a changed protocol.
        // With no user-provided coordinates, omit location entirely instead of fabricating a location object.
        Response response = get(MOBILE + "/pptSign/stuSignajax", values, account.cookies);
        return classify(response);
    }

    private static SignResult classify(Response response) {
        if (authStatus(response)) return result(SignResult.State.AUTH_REQUIRED, "登录已失效，请重新登录并准备");
        if (response.status < 200 || response.status >= 300) {
            return result(SignResult.State.UNCERTAIN, "服务响应异常，结果不确定；请在官方应用核对，未自动重试");
        }
        // Only the exact, raw historical success token is accepted as a successful submission.
        if ("success".equals(response.body)) return result(SignResult.State.SUCCESS, "签到成功");
        String text = response.body.trim();
        // An unknown schema or page is not a submission result, even if it contains a familiar status word.
        // In particular, do not turn a JSON error about querying an already-signed state into a completed entry.
        if (text.length() == 0 || text.startsWith("<") || text.startsWith("{") || text.startsWith("[")) {
            return result(SignResult.State.UNCERTAIN, "未收到可识别的签到结果；请在官方应用核对，未自动重试");
        }
        String token = statusToken(text);
        if (oneOf(token, "请登录", "请先登录", "用户未登录", "未登录", "请重新登录", "登录已失效", "登录已过期",
                "登录过期", "登录失效，请重新登录", "登录已过期，请重新登录", "login", "notlogin", "nologin")) {
            return result(SignResult.State.AUTH_REQUIRED, "登录已失效，请重新登录并准备");
        }
        if (oneOf(token, "已签到", "您已签到", "你已签到", "已经签到", "您已经签到", "你已经签到", "已签过",
                "您已签过", "您已签到，请勿重复签到", "您已经签到，请勿重复签到", "already", "already_signed", "already signed")) {
            return result(SignResult.State.ALREADY_SIGNED, "服务端提示已签到");
        }
        if (oneOf(token, "二维码过期", "二维码已过期", "二维码已失效", "二维码已过期，请刷新二维码", "二维码已过期，请重新扫描",
                "二维码已失效，请刷新二维码", "二维码已失效，请重新扫描", "二维码已刷新，请重新扫描", "二维码已更新，请重新扫描",
                "expired", "qrcode expired", "qr code expired", "fail-need-qrcode", "fail-need-refresh")) {
            return result(SignResult.State.EXPIRED, "二维码已过期或需要刷新，请补扫新码");
        }
        if (oneOf(token, "不在签到范围内", "超出签到范围", "超出签到范围，请在指定位置签到", "位置不符合要求", "位置校验失败",
                "签到位置不正确", "距离过远")) {
            return result(SignResult.State.FAILED, "服务端未确认成功，请核对活动的位置要求");
        }
        if (oneOf(token, "签到已结束", "活动已结束", "签到已经结束", "活动已经结束", "签到已截止", "签到未开始", "活动未开始")) {
            return result(SignResult.State.FAILED, "活动未开始或已结束");
        }
        // Do not display arbitrary server text: it may contain a credential, URL, enc or an HTML page.
        if (oneOf(token, "fail", "签到失败")) return result(SignResult.State.FAILED, "服务端明确返回签到失败，未自动重试");
        return result(SignResult.State.UNCERTAIN, "未收到可识别的签到结果；请在官方应用核对，未自动重试");
    }

    private static String statusToken(String text) {
        String token = text.toLowerCase(Locale.ROOT);
        // Permit only terminal punctuation on a complete known phrase, never matching arbitrary substrings.
        while (token.endsWith("。") || token.endsWith("！") || token.endsWith("!") || token.endsWith(".")) {
            token = token.substring(0, token.length() - 1);
        }
        return token;
    }

    private static boolean oneOf(String token, String... known) {
        for (String value : known) if (value.equals(token)) return true;
        return false;
    }

    private static SignResult result(SignResult.State state, String message) {
        return new SignResult(state, message);
    }

    private Response get(String endpoint, Map<String, String> values, Map<String, String> cookies) throws IOException {
        return transport.send("GET", endpoint + "?" + encode(values), null, cookies);
    }

    /** No global CookieHandler, redirects, custom trust manager, logs, or retry loop. */
    private static Response request(String method, String address, Map<String, String> values, Map<String, String> cookies) throws IOException {
        HttpsURLConnection connection = null;
        try {
            URL url = new URL(address);
            String host = url.getHost();
            if (!"https".equals(url.getProtocol()) || url.getUserInfo() != null || (url.getPort() != -1 && url.getPort() != 443)
                    || !("passport2.chaoxing.com".equals(host) || "mobilelearn.chaoxing.com".equals(host)
                    || "mooc1-1.chaoxing.com".equals(host))) {
                throw new IOException("请求地址不在允许的安全接口内");
            }
            connection = (HttpsURLConnection) url.openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestMethod(method);
            connection.setRequestProperty("Accept", "application/json, text/html, text/plain, */*");
            connection.setRequestProperty("Accept-Encoding", "gzip");
            connection.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) QRBatch/1.0");
            connection.setRequestProperty("X-Requested-With", "XMLHttpRequest");
            // Only explicitly read-only discovery can reuse a TLS connection. Pre-sign and signup stay one-shot.
            if (!reusableQuery(method,url)) connection.setRequestProperty("Connection", "close");
            String cookie = cookieHeader(cookies);
            if (cookie.length() > 0) connection.setRequestProperty("Cookie", cookie);
            if ("POST".equals(method)) {
                byte[] body = encode(values).getBytes(StandardCharsets.UTF_8);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
                connection.setFixedLengthStreamingMode(body.length);
                try (OutputStream output = connection.getOutputStream()) { output.write(body); }
            }
            int status = connection.getResponseCode();
            updateCookies(cookies, connection.getHeaderFields());
            InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            String body = "";
            if (stream != null) {
                if ("gzip".equalsIgnoreCase(connection.getContentEncoding())) stream = new GZIPInputStream(stream);
                try (InputStream input = stream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        if (out.size() + count > MAX_RESPONSE_BYTES) throw new IOException("响应超过大小上限");
                        out.write(buffer, 0, count);
                    }
                    body = new String(out.toByteArray(), StandardCharsets.UTF_8);
                }
            }
            return new Response(status, body, connection.getHeaderField("Location"));
        } catch (IOException | RuntimeException e) {
            // Deliberately discard the cause: transport exceptions may embed the full URL including enc.
            throw new TransportFailure(e instanceof IOException ? networkProblem((IOException) e) : "网络请求或响应异常，请检查网络后重试。");
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    static boolean reusableQuery(String method,URL url) {
        String host=url.getHost(),path=url.getPath();
        return ("GET".equals(method)&&"mobilelearn.chaoxing.com".equals(host)&&"/v2/apis/active/student/activelist".equals(path))
                ||("POST".equals(method)&&"mooc1-1.chaoxing.com".equals(host)&&"/visit/courselistdata".equals(path));
    }

    private static String cookieHeader(Map<String, String> cookies) {
        StringBuilder header = new StringBuilder();
        synchronized (cookies) {
            for (Map.Entry<String, String> entry : cookies.entrySet()) {
                String name = entry.getKey(), value = entry.getValue();
                if (!cookieName(name) || value == null || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0 || value.indexOf(';') >= 0) continue;
                if (header.length() > 0) header.append("; ");
                header.append(name).append('=').append(value);
            }
            // The old client sent UID even when the login response used _uid.
            if (!cookies.containsKey("UID") && cookies.containsKey("_uid") && digits(cookies.get("_uid"))) {
                if (header.length() > 0) header.append("; ");
                header.append("UID=").append(cookies.get("_uid"));
            }
        }
        return header.toString();
    }

    private static void updateCookies(Map<String, String> cookies, Map<String, List<String>> headers) {
        if (headers == null) return;
        synchronized (cookies) {
            for (Map.Entry<String, List<String>> header : headers.entrySet()) {
                if (header.getKey() == null || !header.getKey().equalsIgnoreCase("Set-Cookie") || header.getValue() == null) continue;
                for (String line : header.getValue()) {
                    try {
                        for (HttpCookie cookie : HttpCookie.parse(line)) {
                            if (!cookieName(cookie.getName())) continue;
                            if (cookie.hasExpired()) cookies.remove(cookie.getName());
                            else cookies.put(cookie.getName(), cookie.getValue());
                        }
                    } catch (IllegalArgumentException ignored) { /* Ignore malformed cookie headers, never log them. */ }
                }
            }
        }
    }

    private static boolean cookieName(String value) {
        return value != null && value.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+");
    }

    private static void requireAccount(Account account) throws IOException {
        if (account == null || !digits(account.uid) || account.cookies == null || account.cookies.isEmpty()) {
            throw new IOException("账户登录信息不完整，请重新登录");
        }
    }

    private static void requireOk(Response response) throws IOException {
        if (requiresAuth(response)) throw new IOException("登录状态已失效，请重新登录");
        if (response.status < 200 || response.status >= 300) throw new IOException("服务响应异常，请稍后重新准备");
    }

    private static boolean requiresAuth(Response response) {
        if (authStatus(response)) return true;
        String text = response.body.trim();
        String lower = text.toLowerCase(Locale.ROOT);
        return text.contains("请登录") || text.contains("未登录") || text.contains("重新登录") || text.contains("登录已过期")
                || lower.equals("login") || lower.equals("notlogin") || lower.equals("nologin")
                || lower.contains("passport2.chaoxing.com/login") || lower.contains("passport2.chaoxing.com/mlogin")
                || (lower.contains("<form") && (lower.contains("fanyalogin") || lower.contains("name=\"password\"")));
    }

    private static boolean authStatus(Response response) {
        if (response.status == HttpURLConnection.HTTP_UNAUTHORIZED || response.status == HttpURLConnection.HTTP_FORBIDDEN) return true;
        if (response.status >= 300 && response.status < 400 && response.location != null) {
            try {
                URL redirect = new URL(new URL(MOBILE), response.location);
                if (!"https".equalsIgnoreCase(redirect.getProtocol()) || redirect.getUserInfo() != null
                        || (redirect.getPort() != -1 && redirect.getPort() != 443)) return false;
                String host = redirect.getHost().toLowerCase(Locale.ROOT);
                String path = redirect.getPath();
                if (("passport2.chaoxing.com".equals(host) || "mobilelearn.chaoxing.com".equals(host))
                        && ("/login".equals(path) || "/mlogin".equals(path) || "/fanyalogin".equals(path))) return true;
            } catch (IOException ignored) { /* Unknown redirect remains uncertain and is never followed. */ }
        }
        return false;
    }

    private static JSONObject json(String body, String message) throws IOException {
        try { return new JSONObject(body); }
        catch (JSONException e) { throw new IOException(message); }
    }

    private static String encryptPassword(String password) throws IOException {
        try {
            // CryptoJS DES uses the first 8 bytes of its supplied word array; PKCS7 for DES equals PKCS5Padding.
            byte[] key = Arrays.copyOf("u2oh6Vu^HWe40fj".getBytes(StandardCharsets.UTF_8), 8);
            Cipher cipher = Cipher.getInstance("DES/ECB/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "DES"));
            byte[] encrypted = cipher.doFinal(password.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(encrypted.length * 2);
            for (byte value : encrypted) {
                hex.append(Character.forDigit((value >>> 4) & 15, 16));
                hex.append(Character.forDigit(value & 15, 16));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IOException("无法在本机完成登录密码编码");
        }
    }

    private static Map<String, String> params(String... pairs) {
        Map<String, String> values = new LinkedHashMap<String, String>();
        for (int i = 0; i < pairs.length; i += 2) values.put(pairs[i], pairs[i + 1] == null ? "" : pairs[i + 1]);
        return values;
    }

    private static String encode(Map<String, String> values) throws IOException {
        StringBuilder query = new StringBuilder();
        if (values != null) for (Map.Entry<String, String> entry : values.entrySet()) {
            if (query.length() > 0) query.append('&');
            query.append(URLEncoder.encode(entry.getKey(), "UTF-8")).append('=')
                    .append(URLEncoder.encode(entry.getValue() == null ? "" : entry.getValue(), "UTF-8"));
        }
        return query.toString();
    }

    private static String jsonName(JSONObject source) {
        if (source != null) for (String field : new String[]{"realname", "realName", "name"}) {
            Object value = source.opt(field);
            if (value instanceof String) {
                String name = ProfileNames.clean((String) value);
                if (!name.isEmpty()) return name;
            }
        }
        return "";
    }

    private static String first(String... values) {
        for (String value : values) if (value != null && value.length() > 0 && !"null".equals(value)) return value;
        return "";
    }

    private static boolean digits(String value) { return value != null && value.matches("[0-9]+"); }

    static final class Response {
        final int status;
        final String body;
        final String location;
        Response(int status, String body, String location) {
            this.status = status;
            this.body = body;
            this.location = location;
        }
    }
}
