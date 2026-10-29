package com.dan.kejian;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.webkit.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class MainActivity extends Activity {
    static final String ORIGIN = "https://appassets.androidplatform.net", PAGE = ORIGIN + "/assets/index.html";
    private WebView browser;
    private TimetableStore store;
    private byte[] page;
    private boolean ready, pendingEnable;
    private ValueCallback<Uri[]> fileCallback;
    private byte[] exportBytes;
    private String exportKind, exportType, exportName;
    private static final int PICK = 41, SAVE = 42, NOTIFY = 43;

    static void applyInsets(View view) {
        view.setOnApplyWindowInsetsListener((root, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets safe = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                root.setPadding(safe.left, safe.top, safe.right, safe.bottom);
            } else root.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(), insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
    }
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        store = new TimetableStore(this);
        try {
            String html = new String(TimetableStore.readBounded(getAssets().open("index.html"), 6000000), StandardCharsets.UTF_8);
            JSONObject initial = store.load();
            // A script-string literal must escape HTML terminators, not just JSON quotes.
            String jsonString = JSONObject.quote(initial.toString()).replace("<", "\\u003c").replace(">", "\\u003e").replace("\u2028", "\\u2028").replace("\u2029", "\\u2029");
            html = html.replace("/* KEJIAN_BOOTSTRAP */", "window.__KEJIAN_ANDROID__=true;window.__KEJIAN_DESKTOP__={desktop:true,timetable:JSON.parse(" + jsonString + ")};");
            page = html.getBytes(StandardCharsets.UTF_8);
        } catch (Exception error) {
            new AlertDialog.Builder(this).setTitle("课表暂时无法读取").setMessage("已保留原始文件。请保留应用数据后重试，或恢复课表备份。\n" + error.getMessage()).setPositiveButton("关闭", (a, b) -> finish()).setCancelable(false).show(); return;
        }
        LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL); layout.setBackgroundColor(Color.rgb(247,248,250));
        applyInsets(layout); browser = new WebView(this); layout.addView(browser, new LinearLayout.LayoutParams(-1,-1)); setContentView(layout);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | (Build.VERSION.SDK_INT >= 26 ? View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR : 0));
        WebSettings settings = browser.getSettings(); settings.setJavaScriptEnabled(true); settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false); settings.setAllowContentAccess(true); settings.setAllowFileAccessFromFileURLs(false); settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW); settings.setSupportMultipleWindows(false);
        settings.setMediaPlaybackRequiresUserGesture(true); settings.setTextZoom(100);
        CookieManager.getInstance().setAcceptThirdPartyCookies(browser, false);
        browser.addJavascriptInterface(new NativeBridge(), "KeJianAndroid");
        browser.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if ("GET".equals(request.getMethod()) && ORIGIN.equals(uri.getScheme() + "://" + uri.getAuthority()) && "/assets/index.html".equals(uri.getPath())) {
                    Map<String,String> headers = new HashMap<>();
                    headers.put("Cache-Control", "no-store"); headers.put("X-Content-Type-Options", "nosniff");
                    headers.put("Content-Security-Policy", "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src data: blob:; frame-src 'self' about: blob:; connect-src 'none'; base-uri 'none'; form-action 'none'");
                    return new WebResourceResponse("text/html", "UTF-8", 200, "OK", headers, new ByteArrayInputStream(page));
                }
                // Local packaged content never falls through to a network request.
                return new WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", Collections.emptyMap(), new ByteArrayInputStream(new byte[0]));
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String value = request.getUrl().toString();
                return !(value.equals(PAGE) || value.startsWith(PAGE + "#"));
            }
            @Override public void onPageFinished(WebView view, String url) { if (ready) sendState(); }
        });
        browser.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (!isLocal()) return false;
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                boolean image = Arrays.stream(params.getAcceptTypes()).anyMatch(type -> type.startsWith("image/"));
                Intent choose = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(image ? "image/*" : "*/*");
                try { startActivityForResult(choose, PICK); }
                catch (ActivityNotFoundException error) { fileCallback.onReceiveValue(null); fileCallback = null; message("没有找到文件选择器"); }
                return true;
            }
            @Override public boolean onJsAlert(WebView view, String url, String text, JsResult result) { new AlertDialog.Builder(MainActivity.this).setMessage(text).setPositiveButton("确定", (a,b) -> result.confirm()).setOnCancelListener(a -> result.cancel()).show(); return true; }
        });
        browser.setDownloadListener((a,b,c,d,e) -> message("请使用课表中的导出按钮保存文件"));
        browser.loadUrl(PAGE);
    }
    private boolean isLocal() { String url = browser == null ? null : browser.getUrl(); return url != null && (url.equals(PAGE) || url.startsWith(PAGE + "#")); }
    private final class NativeBridge {
        @JavascriptInterface public void postMessage(String json) {
            if (json == null || json.length() > 6000000) return;
            runOnUiThread(() -> { if (!isLocal()) return; try { handle(new JSONObject(json)); } catch (Exception error) { sendError(error.getMessage()); } });
        }
    }
    private void handle(JSONObject request) throws Exception {
        switch (request.getString("type")) {
            case "app:ready": ready = true; sendState(); break;
            case "timetable:sync":
                store.save(request.getJSONObject("data")); ReminderReceiver.schedule(this); sendState(); break;
            case "reminders:setEnabled":
                if (!request.getBoolean("enabled")) { pendingEnable = false; ReminderReceiver.setEnabled(this, false); sendState(); }
                else {
                    pendingEnable = true;
                    if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) requestPermissions(new String[] { Manifest.permission.POST_NOTIFICATIONS }, NOTIFY);
                    else finishEnable();
                }
                break;
            case "reminders:test":
                if (!ReminderReceiver.canNotify(this)) { sendError("请先允许课间的通知权限"); break; }
                ReminderReceiver.notify(this, "课间 · 测试通知", "上课、下课时会在这里提醒你。", 12345); sendState(); break;
            case "reminders:settings":
                Intent settings;
                if (Build.VERSION.SDK_INT >= 31 && !ReminderReceiver.exactAllowed(this)) settings = new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).setData(Uri.parse("package:" + getPackageName()));
                else settings = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
                try { startActivity(settings); } catch (ActivityNotFoundException error) { message("请在手机设置中打开课间的通知与提醒权限"); }
                break;
            case "clipboard:copy":
                JSONObject copied = new JSONObject().put("type", "clipboard:result").put("requestId", request.getLong("requestId"));
                try {
                    String text = request.getString("text"); if (text.length() > 300000) throw new IOException("复制内容过长");
                    ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("课间", text)); copied.put("ok", true);
                } catch (Exception error) { copied.put("ok", false).put("message", error.getMessage()); }
                send(copied); break;
            case "backup:export":
                String kind = request.optString("kind", "backup");
                if (!Arrays.asList("backup", "guide", "example").contains(kind)) throw new IOException("不支持的文件类型");
                String text = request.getString("text");
                if (text.getBytes(StandardCharsets.UTF_8).length > 4000000) throw new IOException("导出文件过大");
                beginExport(text.getBytes(StandardCharsets.UTF_8), request.getString("filename"), kind.equals("guide") ? ".md" : ".json", kind, "backup:result"); break;
            case "qr:image-export":
                String image = request.getString("dataUrl"), prefix = "data:image/png;base64,";
                if (!image.startsWith(prefix) || image.length() > 3000000) throw new IOException("二维码图片格式不正确");
                byte[] bytes = android.util.Base64.decode(image.substring(prefix.length()), android.util.Base64.DEFAULT);
                if (bytes.length < 8 || bytes[0] != (byte)137 || bytes[1] != 80 || bytes[2] != 78 || bytes[3] != 71) throw new IOException("图片不是 PNG 格式");
                beginExport(bytes, request.getString("filename"), ".png", "image", "qr:image-result"); break;
            case "qr:open":
                String url = request.getString("url");
                if (!SigninUrl.isAllowed(url)) { send(new JSONObject().put("type", "qr:open-result").put("status", "error").put("message", "签到链接格式不正确")); break; }
                try {
                    startActivity(new Intent(this, SigninActivity.class).putExtra("url", url));
                    send(new JSONObject().put("type", "qr:open-result").put("status", "opened"));
                } catch (Exception error) { send(new JSONObject().put("type", "qr:open-result").put("status", "error").put("message", "无法打开签到页面")); }
                break;
            default: throw new IOException("不支持的操作");
        }
    }
    private void beginExport(byte[] bytes, String filename, String extension, String kind, String type) throws Exception {
        if (exportBytes != null) { send(new JSONObject().put("type", type).put("status", "error").put("kind", kind).put("message", "请先完成当前文件的保存")); return; }
        filename = filename.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "-");
        if (!filename.toLowerCase(Locale.ROOT).endsWith(extension) || filename.length() > 200) throw new IOException("导出文件扩展名不正确");
        exportBytes = bytes; exportKind = kind; exportType = type; exportName = filename;
        Intent create = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType(extension.equals(".png") ? "image/png" : extension.equals(".md") ? "text/markdown" : "application/json").putExtra(Intent.EXTRA_TITLE, filename);
        try { startActivityForResult(create, SAVE); }
        catch (Exception error) { exportResult("error", "无法打开系统保存窗口"); }
    }
    private void exportResult(String status, String error) {
        try { send(new JSONObject().put("type", exportType).put("kind", exportKind).put("status", status).put("filename", exportName).put("message", error)); }
        catch (Exception ignored) { /* UI can retry. */ }
        exportBytes = null; exportKind = exportType = exportName = null;
    }
    @Override protected void onActivityResult(int code, int result, Intent intent) {
        super.onActivityResult(code, result, intent);
        if (code == PICK && fileCallback != null) {
            Uri[] selected = null;
            try {
                Uri uri = intent == null ? null : intent.getData();
                if (result == RESULT_OK && uri != null && "content".equals(uri.getScheme())) {
                    // Bound provider content too; file metadata may be missing or inaccurate.
                    TimetableStore.readBounded(getContentResolver().openInputStream(uri), 16000000); selected = new Uri[] { uri };
                }
            } catch (Exception error) { message("文件无法读取或超过 16 MB，请换一个文件"); }
            fileCallback.onReceiveValue(selected); fileCallback = null;
        } else if (code == SAVE && exportBytes != null) {
            Uri uri = intent == null ? null : intent.getData();
            if (result != RESULT_OK || uri == null) { exportResult("cancelled", ""); return; }
            try {
                if (!"content".equals(uri.getScheme())) throw new IOException("请选择系统提供的保存位置");
                try (OutputStream output = getContentResolver().openOutputStream(uri, "wt")) {
                    if (output == null) throw new IOException("保存位置不可用"); output.write(exportBytes); output.flush();
                }
                exportResult("saved", "");
            } catch (Exception error) { exportResult("error", "文件未能完整保存，请检查保存位置后重试"); }
        }
    }
    private void finishEnable() {
        try {
            if (pendingEnable && ReminderReceiver.canNotify(this)) ReminderReceiver.setEnabled(this, true);
            else if (pendingEnable) message("通知尚未获允许，可在“允许准时提醒”中进入通知设置");
        } catch (Exception error) { sendError(error.getMessage()); }
        finally { pendingEnable = false; sendState(); }
    }
    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) { super.onRequestPermissionsResult(code, permissions, results); if (code == NOTIFY) finishEnable(); }
    @Override public void onResume() {
        super.onResume(); if (browser == null) return; browser.onResume();
        try { ReminderReceiver.schedule(this); } catch (Exception error) { if (ready) sendError("提醒安排失败：" + error.getMessage()); }
        if (ready) sendState();
    }
    @Override public void onPause() { if (browser != null) browser.onPause(); super.onPause(); }
    private void sendState() {
        if (!ready) return;
        try {
            boolean enabled = ReminderReceiver.enabled(this), allowed = ReminderReceiver.canNotify(this), exact = ReminderReceiver.exactAllowed(this);
            String text = !allowed ? "系统通知尚未允许，请在手机设置中允许课间通知。" : !enabled ? "已关闭。打开开关后，每节上课和下课各提醒一次。" : exact ? "已开启：每节上课、下课各提醒一次；课表修改后自动同步。" : "已开启普通提醒。尚未允许“闹钟和提醒”，系统可能延迟；可点击下方允许准时提醒。";
            send(new JSONObject().put("type", "desktop:state").put("ready", true).put("enabled", enabled).put("exact", exact).put("message", text));
        } catch (Exception ignored) { /* next sync retries */ }
    }
    private void send(JSONObject value) { if (browser != null && isLocal()) browser.evaluateJavascript("window.__kejianNativeReceive&&window.__kejianNativeReceive(" + value.toString().replace("\u2028", "\\u2028").replace("\u2029", "\\u2029") + ")", null); }
    private void sendError(String text) { try { send(new JSONObject().put("type", "desktop:error").put("message", text)); } catch (Exception ignored) { } }
    private void message(String text) { Toast.makeText(this, text, Toast.LENGTH_LONG).show(); }
    @Override public void onBackPressed() {
        if (browser == null) { super.onBackPressed(); return; }
        browser.evaluateJavascript("window.__kejianBack?window.__kejianBack():false", result -> { if (!"true".equals(result)) moveTaskToBack(true); });
    }
    @Override public void onDestroy() {
        if (fileCallback != null) { fileCallback.onReceiveValue(null); fileCallback = null; }
        if (browser != null) { browser.removeJavascriptInterface("KeJianAndroid"); browser.stopLoading(); browser.destroy(); browser = null; }
        super.onDestroy();
    }
}
