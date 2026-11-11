package com.dan.kejian;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.os.*;
import android.graphics.Color;
import android.net.Uri;
import android.view.*;
import android.webkit.*;
import android.widget.*;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** This separate process has no native bridge and cannot read the timetable profile. */
public final class SigninActivity extends Activity {
    private WebView browser;
    private TextView status;
    private android.widget.Switch ipv4;
    private Button renew;
    private SigninRoute route;
    private boolean updatingRoute;
    private Button diagnose;
    private AlertDialog diagnosticDialog;
    private String currentQrUrl;
    private long loadStarted, ageOnStart;
    private long navigationStarted, navigationMs = -1;
    private boolean loadFailed, diagnosing;
    private final ExecutorService diagnosticWorker = Executors.newSingleThreadExecutor();
    private Callable<String> diagnosticProbe = () -> SigninDiagnostics.collect(getApplicationContext());
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        String url = getIntent().getStringExtra("url");
        if (!SigninUrl.isAllowed(url)) { finish(); return; }
        currentQrUrl = url;
        if (Build.VERSION.SDK_INT >= 28) SigninWebViewProfile.initialize(() -> WebView.setDataDirectorySuffix("signin"));
        LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL); layout.setBackgroundColor(Color.WHITE);
        MainActivity.applyInsets(layout);
        LinearLayout tools = new LinearLayout(this); tools.setPadding(dp(4), dp(4), dp(4), 0);
        Button close = action("返回课表", "signin-close"); close.setOnClickListener(view -> finish());
        renew = action("重新生成", "signin-renew"); renew.setOnClickListener(view -> {
            try { currentQrUrl = SigninUrl.renew(currentQrUrl, System.currentTimeMillis()); loadCurrentQr(); }
            catch (IllegalArgumentException error) { Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show(); }
        });
        diagnose = action("网络诊断", "signin-diagnose"); diagnose.setOnClickListener(view -> diagnose());
        for (Button button : new Button[]{close, renew, diagnose}) tools.addView(button, new LinearLayout.LayoutParams(0, dp(48), 1));
        layout.addView(tools);
        ipv4 = new android.widget.Switch(this); ipv4.setTag("signin-ipv4"); ipv4.setText("IPv4 兼容（试验）"); ipv4.setTextSize(13); ipv4.setPadding(dp(12),0,dp(12),0);
        layout.addView(ipv4,new LinearLayout.LayoutParams(-1,dp(44)));
        status = new TextView(this); status.setTag("signin-status"); status.setTextSize(12); status.setTextColor(Color.rgb(117,131,147)); status.setPadding(dp(12), dp(4), dp(12), dp(6)); layout.addView(status);
        browser = new WebView(this); browser.setTag("signin-browser"); layout.addView(browser, new LinearLayout.LayoutParams(-1, 0, 1)); setContentView(layout);
        WebSettings settings = browser.getSettings(); settings.setJavaScriptEnabled(true); settings.setDomStorageEnabled(true);
        settings.setUserAgentString("MicroMessenger"); settings.setAllowFileAccess(false); settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW); settings.setSupportMultipleWindows(false);
        CookieManager.getInstance().setAcceptThirdPartyCookies(browser, false);
        browser.setWebViewClient(new WebViewClient() {
            @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap icon) {
                loadStarted = SystemClock.elapsedRealtime(); loadFailed = false;
                status.setText("正在打开签到页面…");
            }
            @Override public void onPageFinished(WebView view, String url) {
                navigationMs = SystemClock.elapsedRealtime() - navigationStarted;
                if (!loadFailed) status.setText(String.format(Locale.ROOT, "页面加载 %.1f 秒 · 可按需重新生成", (SystemClock.elapsedRealtime() - loadStarted) / 1000.0));
            }
            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) { loadFailed = true; status.setText("网页载入失败（" + error.getErrorCode() + "），可进行网络诊断"); }
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if ("https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null && uri.getUserInfo() == null) return false;
                Toast.makeText(SigninActivity.this, "此页面仅支持安全网页链接", Toast.LENGTH_SHORT).show(); return true;
            }
        });
        browser.setDownloadListener((a, b, c, d, e) -> Toast.makeText(this, "签到窗口不下载文件", Toast.LENGTH_SHORT).show());
        if(route==null)route=new SigninIpv4Route();
        ipv4.setOnCheckedChangeListener((button,checked)->{if(!updatingRoute)applyRoute(checked,false);});
        applyRoute(getPreferences(MODE_PRIVATE).getBoolean("ipv4_compat_v1",false),true);
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private Button action(String title, String tag) {
        Button button = new Button(this); button.setText(title); button.setTag(tag); button.setAllCaps(false); button.setTextSize(14); button.setMinWidth(0); button.setMinimumWidth(0); button.setPadding(dp(4), 0, dp(4), 0); return button;
    }
    private void loadCurrentQr() {
        ageOnStart = System.currentTimeMillis() - SigninUrl.generatedAt(currentQrUrl);
        loadStarted = SystemClock.elapsedRealtime(); loadFailed = false;
        navigationStarted = loadStarted; navigationMs = -1;
        browser.loadUrl(currentQrUrl);
    }
    private void applyRoute(boolean enabled,boolean firstLoad){
        renew.setEnabled(false);ipv4.setEnabled(false);browser.stopLoading();
        status.setText("正在准备浏览器线路…");
        route.apply(enabled,()->{
            if(isFinishing()||isDestroyed())return;
            updatingRoute=true;ipv4.setChecked(enabled);updatingRoute=false;
            ipv4.setEnabled(true);renew.setEnabled(true);
            getPreferences(MODE_PRIVATE).edit().putBoolean("ipv4_compat_v1",enabled).apply();
            if(firstLoad){if(enabled)currentQrUrl=SigninUrl.renew(currentQrUrl,System.currentTimeMillis());loadCurrentQr();}
            else status.setText((enabled?"IPv4 兼容已开启":"已恢复系统线路")+"，请点击“重新生成”后测试。");
        },message->{
            if(isFinishing()||isDestroyed())return;
            // Restore system settings before allowing another navigation after setup failure.
            route.apply(false,()->{
                if(isFinishing()||isDestroyed())return;
                updatingRoute=true;ipv4.setChecked(false);updatingRoute=false;ipv4.setEnabled(true);renew.setEnabled(true);
                getPreferences(MODE_PRIVATE).edit().putBoolean("ipv4_compat_v1",false).apply();status.setText(message);
                if(firstLoad)loadCurrentQr();
            },restoreError->{if(!isFinishing()&&!isDestroyed())status.setText("浏览器线路未能恢复，请返回课表后重新打开。");});
        });
    }
    private void diagnose() {
        if (diagnosing) return;
        diagnosing = true; diagnose.setEnabled(false);
        TextView report = new TextView(this); report.setText("正在检查网络和站点首页…"); report.setTextSize(13); report.setTextIsSelectable(true); report.setPadding(dp(20), dp(8), dp(20), dp(8));
        ScrollView scroll = new ScrollView(this); scroll.addView(report);
        diagnosticDialog = new AlertDialog.Builder(this).setTitle("签到网络诊断").setView(scroll).setNegativeButton("关闭", null).setPositiveButton("复制结果", null).create();
        final AlertDialog dialog = diagnosticDialog;
        dialog.show(); dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
        final long requestAge = ageOnStart;
        final long pageMs = navigationMs;
        final String routeDescription = route.describe();
        diagnosticWorker.execute(() -> {
            String result;
            try { result = diagnosticProbe.call() + "\n" + routeDescription + String.format(Locale.ROOT, "\n本次打开时链接距生成：%.1f 秒\n", requestAge / 1000.0) +
                    (pageMs<0?"签到页本次导航：尚未完成\n":String.format(Locale.ROOT,"签到页本次导航：%.1f 秒（含页面跳转）\n",pageMs/1000.0)); }
            catch (Exception error) { result = "本次诊断未完成，可稍后重试。"; }
            final String text = result;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                diagnosing = false; diagnose.setEnabled(true);
                if (!dialog.isShowing()) return;
                report.setText(text);
                Button copy = dialog.getButton(AlertDialog.BUTTON_POSITIVE); copy.setEnabled(true);
                copy.setOnClickListener(view -> {
                    ((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("签到网络诊断", text));
                    Toast.makeText(this, "诊断结果已复制", Toast.LENGTH_SHORT).show();
                });
            });
        });
    }
    @Override public void onBackPressed() { if (browser != null && browser.canGoBack()) browser.goBack(); else super.onBackPressed(); }
    @Override public void onPause() { if (browser != null) browser.onPause(); super.onPause(); }
    @Override public void onResume() { super.onResume(); if (browser != null) browser.onResume(); }
    @Override public void onDestroy() {
        diagnosticWorker.shutdownNow();
        if (diagnosticDialog != null) { diagnosticDialog.dismiss(); diagnosticDialog = null; }
        if (browser != null) {
            browser.stopLoading();
            if (browser.getParent() instanceof ViewGroup) ((ViewGroup) browser.getParent()).removeView(browser);
            browser.destroy(); browser = null;
        }
        if(route!=null){route.close();route=null;}
        super.onDestroy();
    }
}
