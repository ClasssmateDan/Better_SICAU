package com.dan.kejian;

import android.app.Activity;
import android.os.*;
import android.graphics.Color;
import android.net.Uri;
import android.view.*;
import android.webkit.*;
import android.widget.*;

/** This separate process has no native bridge and cannot read the timetable profile. */
public final class SigninActivity extends Activity {
    private WebView browser;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        String url = getIntent().getStringExtra("url");
        if (!SigninUrl.isAllowed(url)) { finish(); return; }
        if (Build.VERSION.SDK_INT >= 28) SigninWebViewProfile.initialize(() -> WebView.setDataDirectorySuffix("signin"));
        LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL); layout.setBackgroundColor(Color.WHITE);
        MainActivity.applyInsets(layout);
        Button close = new Button(this); close.setText("返回课表 · 签到页面"); close.setOnClickListener(view -> finish());
        layout.addView(close, new LinearLayout.LayoutParams(-1, 52 * getResources().getDisplayMetrics().densityDpi / 160));
        browser = new WebView(this); layout.addView(browser, new LinearLayout.LayoutParams(-1, 0, 1)); setContentView(layout);
        WebSettings settings = browser.getSettings(); settings.setJavaScriptEnabled(true); settings.setDomStorageEnabled(true);
        settings.setUserAgentString("MicroMessenger"); settings.setAllowFileAccess(false); settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW); settings.setSupportMultipleWindows(false);
        CookieManager.getInstance().setAcceptThirdPartyCookies(browser, false);
        browser.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if ("https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null && uri.getUserInfo() == null) return false;
                Toast.makeText(SigninActivity.this, "此页面仅支持安全网页链接", Toast.LENGTH_SHORT).show(); return true;
            }
        });
        browser.setDownloadListener((a, b, c, d, e) -> Toast.makeText(this, "签到窗口不下载文件", Toast.LENGTH_SHORT).show());
        browser.loadUrl(url);
    }
    @Override public void onBackPressed() { if (browser != null && browser.canGoBack()) browser.goBack(); else super.onBackPressed(); }
    @Override public void onPause() { if (browser != null) browser.onPause(); super.onPause(); }
    @Override public void onResume() { super.onResume(); if (browser != null) browser.onResume(); }
    @Override public void onDestroy() {
        if (browser != null) {
            browser.stopLoading();
            if (browser.getParent() instanceof ViewGroup) ((ViewGroup) browser.getParent()).removeView(browser);
            browser.destroy(); browser = null;
        }
        super.onDestroy();
    }
}
